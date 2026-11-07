package dev.horizon.android

import android.content.Context
import android.os.StatFs
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * A private ARM64 Linux build environment running inside ZaRust.
 *
 * PRoot and its loader must be packaged in the APK's jniLibs directory. Android 10+
 * does not allow executing arbitrary files from an app's writable data directory.
 *
 * The Termux bootstrap is used as a package payload only: ZaRust installs and manages
 * its private userspace itself and never starts or depends on the Termux application.
 */
object ZaRustNativeBuildEnvironment {
    private const val BOOTSTRAP_URL =
        "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.11.01-r1%2Bapt.android-7/bootstrap-aarch64.zip"
    private const val BOOTSTRAP_SHA256 =
        "5a1429b6c68ee3bdc17b5fd6e47ba42933ec924cd7283fe74992574e1d5badae"
    private const val PREFIX = "/data/data/com.termux/files/usr"
    private const val HOME = "/data/data/com.termux/files/home"
    private const val GUEST_WORK = "/data/data/com.zarust.launcher/files/native-builder"
    private const val MAX_BOOTSTRAP_DOWNLOAD = 128L * 1024L * 1024L
    private const val MAX_BOOTSTRAP_EXPANDED = 768L * 1024L * 1024L
    private const val MIN_PREPARE_FREE_SPACE = 3L * 1024L * 1024L * 1024L
    private const val OUTPUT_LIMIT = 512 * 1024

    data class CommandResult(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean
    ) {
        val success: Boolean get() = exitCode == 0 && !timedOut
    }

    data class Status(
        val abiSupported: Boolean,
        val nativeRunnerInstalled: Boolean,
        val bootstrapInstalled: Boolean,
        val rustInstalled: Boolean,
        val rustStdInstalled: Boolean,
        val ndkSysrootInstalled: Boolean,
        val freeBytes: Long
    ) {
        val ready: Boolean
            get() = abiSupported && nativeRunnerInstalled && bootstrapInstalled &&
                rustInstalled && rustStdInstalled && ndkSysrootInstalled
    }

    private data class HostPaths(
        val rootfs: File,
        val work: File,
        val proot: File,
        val loader: File,
        val prootTmp: File,
        val link2symlink: File
    )

    fun workDirectory(context: Context): File =
        File(context.filesDir, "native-builder").apply { mkdirs() }

    fun rootfsDirectory(context: Context): File =
        File(workDirectory(context), "rootfs")

    fun sourceDirectory(context: Context): File =
        File(workDirectory(context), "sources")

    fun importedModsDirectory(context: Context): File =
        File(workDirectory(context), "mods").apply { mkdirs() }

    fun buildsDirectory(context: Context): File =
        File(workDirectory(context), "builds").apply { mkdirs() }

    fun status(context: Context): Status {
        val prefix = guestFile(context, PREFIX)
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val free = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
        return Status(
            abiSupported = android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" },
            nativeRunnerInstalled = File(nativeDir, "libproot.so").canExecute() &&
                File(nativeDir, "libproot-loader.so").isFile,
            bootstrapInstalled = File(prefix, "bin/apt-get").isFile,
            rustInstalled = File(prefix, "bin/rustc").isFile &&
                File(prefix, "bin/cargo").isFile,
            // Cargo must be able to compile build.rs binaries and proc-macro crates on-device.
            // Termux ships the matching host rlibs in rust-std-aarch64-linux-android.
            rustStdInstalled = File(prefix, "lib/rustlib/aarch64-linux-android/lib")
                .listFiles()?.any { it.name.startsWith("libstd-") && it.name.endsWith(".rlib") } == true &&
                File(prefix, "lib/rustlib/aarch64-linux-android/lib")
                    .listFiles()?.any { it.name.startsWith("libcore-") && it.name.endsWith(".rlib") } == true &&
                File(prefix, "lib/rustlib/aarch64-linux-android/lib")
                    .listFiles()?.any { it.name.startsWith("liballoc-") && it.name.endsWith(".rlib") } == true,
            ndkSysrootInstalled = File(prefix, "share/ndk-sysroot").isDirectory ||
                File(prefix, "opt/ndk-sysroot").isDirectory ||
                File(prefix, "include/android").isDirectory,
            freeBytes = free
        )
    }

    /**
     * Installs the private userspace and toolchain. This does not compile a game yet.
     * The PRoot host executable must already be bundled in this ZaRust APK.
     */
    suspend fun prepare(
        context: Context,
        onLine: (String) -> Unit = {}
    ): Status = withContext(Dispatchers.IO) {
        require(android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) {
            "On-device native builds currently require an ARM64 Android device."
        }
        requireRunner(context)

        val free = StatFs(context.filesDir.absolutePath).availableBytes
        require(free >= MIN_PREPARE_FREE_SPACE) {
            "At least 3 GB of free internal storage is required to unpack the Android bootstrap and install Rust/Cargo plus the Android sysroot."
        }

        val prefix = guestFile(context, PREFIX)
        if (!File(prefix, "bin/apt-get").isFile) {
            onLine("Downloading the ARM64 Android userspace…")
            installBootstrap(context, onLine)
        }

        val update = run(
            context,
            listOf("$PREFIX/bin/apt-get", "update"),
            timeoutMs = 15 * 60 * 1000L,
            onLine = onLine
        )
        require(update.success) {
            "Could not update the local package index (exit ${update.exitCode}).\n" +
                update.output.takeLast(5000)
        }

        // ndk-sysroot supplies Android headers and target libraries; Clang/LLD
        // provide the linker, while Rust is the host compiler running under PRoot.
        val install = run(
            context,
            listOf(
                "$PREFIX/bin/apt-get", "install", "-y",
                "rust", "rust-std-aarch64-linux-android", "clang", "lld", "ndk-sysroot", "libandroid-execinfo", "git", "python",
                "nodejs", "cmake", "make", "ninja", "pkg-config",
                "patch", "tar", "unzip", "coreutils", "findutils",
                "grep", "sed", "binutils", "openssl", "zlib"
            ),
            timeoutMs = 60 * 60 * 1000L,
            onLine = onLine
        )
        require(install.success) {
            "Could not install the local Rust/Android build tools (exit ${install.exitCode}).\n" +
                install.output.takeLast(8000)
        }

        val check = run(
            context,
            listOf(
                "$PREFIX/bin/sh", "-lc",
                "rustc --version && cargo --version && clang --version && " +
                    "clang++ --version && llvm-ar --version && llvm-strip --version && " +
                    "git --version && python --version && node --version && npm --version && " +
                    "readelf --version && " +
                    "test -d $PREFIX/share/ndk-sysroot -o -d $PREFIX/opt/ndk-sysroot " +
                    "-o -d $PREFIX/include/android && " +
                    "test -n \"$(find $PREFIX/lib/rustlib/aarch64-linux-android/lib -maxdepth 1 -name 'libstd-*.rlib' -print -quit)\" && " +
                    "test -n \"$(find $PREFIX/lib/rustlib/aarch64-linux-android/lib -maxdepth 1 -name 'libcore-*.rlib' -print -quit)\" && " +
                    "test -n \"$(find $PREFIX/lib/rustlib/aarch64-linux-android/lib -maxdepth 1 -name 'liballoc-*.rlib' -print -quit)\" && " +
                    "test -s $PREFIX/lib/libandroid-execinfo.a"
            ),
            timeoutMs = 60_000,
            onLine = onLine
        )
        require(check.success) {
            "The local Android build toolchain failed its self-check.\n" +
                check.output.takeLast(5000)
        }
        val smokeRoot = File(workDirectory(context), "cargo-host-smoke")
        if (smokeRoot.exists()) require(smokeRoot.deleteRecursively()) {
            "Could not clear the previous Rust host compiler test."
        }
        val procMacro = File(smokeRoot, "derive/src").apply { mkdirs() }
        val appSource = File(smokeRoot, "app/src").apply { mkdirs() }
        File(smokeRoot, "Cargo.toml").writeText(
            "[workspace]\nmembers = [\"derive\", \"app\"]\nresolver = \"2\"\n",
            Charsets.UTF_8
        )
        File(smokeRoot, "derive/Cargo.toml").writeText(
            "[package]\nname = \"zarust-smoke-derive\"\nversion = \"0.1.0\"\nedition = \"2021\"\n" +
                "[lib]\nproc-macro = true\n",
            Charsets.UTF_8
        )
        File(procMacro, "lib.rs").writeText(
            "extern crate proc_macro;\nuse proc_macro::TokenStream;\n" +
                "#[proc_macro_derive(ZaRustSmoke)]\npub fn derive_smoke(_: TokenStream) -> TokenStream { " +
                "\"fn zarust_smoke() {}\".parse().unwrap() }\n",
            Charsets.UTF_8
        )
        File(smokeRoot, "app/Cargo.toml").writeText(
            "[package]\nname = \"zarust-smoke-app\"\nversion = \"0.1.0\"\nedition = \"2021\"\n" +
                "[dependencies]\nzarust-smoke-derive = { path = \"../derive\" }\n",
            Charsets.UTF_8
        )
        File(smokeRoot, "app/build.rs").writeText(
            "fn main() { println!(\"cargo:rerun-if-changed=build.rs\"); }\n",
            Charsets.UTF_8
        )
        File(appSource, "main.rs").writeText(
            "use zarust_smoke_derive::ZaRustSmoke;\n#[derive(ZaRustSmoke)] struct Smoke;\n" +
                "fn main() { zarust_smoke(); }\n",
            Charsets.UTF_8
        )
        val smokeResult = run(
            context,
            listOf(
                "$PREFIX/bin/sh", "-lc",
                "cargo build --offline --manifest-path $GUEST_WORK/cargo-host-smoke/Cargo.toml"
            ),
            timeoutMs = 180_000,
            onLine = onLine
        )
        require(smokeResult.success) {
            "The installed Rust compiler cannot build Cargo build scripts and proc-macros on this device. " +
                "Update the matching rust and rust-std packages, then retry.\\n" +
                smokeResult.output.takeLast(5000)
        }
        // Remove the one-off smoke-test workspace and release downloaded .deb archives.
        runCatching { smokeRoot.deleteRecursively() }
        runCatching {
            run(
                context,
                listOf(
                    "$PREFIX/bin/sh", "-lc",
                    "apt-get clean && rm -rf $PREFIX/var/lib/apt/lists/*"
                ),
                timeoutMs = 60_000,
                onLine = onLine
            )
        }
        onLine("Rust host compiler passed build-script and proc-macro checks.")
        status(context)
    }

    /**
     * Runs a command inside the private ARM64 userspace. All build scripts, the source tree,
     * Cargo cache and output remain inside ZaRust's private files directory.
     */
    suspend fun run(
        context: Context,
        argv: List<String>,
        cwd: String = GUEST_WORK,
        timeoutMs: Long = 30 * 60 * 1000L,
        onLine: (String) -> Unit = {}
    ): CommandResult = withContext(Dispatchers.IO) {
        requireRunner(context)
        require(argv.isNotEmpty() && argv.none { it.contains('\u0000') }) {
            "Invalid local build command."
        }

        val paths = hostPaths(context)
        val args = ArrayList<String>()
        args += paths.proot.absolutePath
        args += listOf(
            "--kill-on-exit", "--link2symlink", "--sysvipc", "-L", "-0",
            "--rootfs=${paths.rootfs.canonicalPath}",
            "--cwd=$cwd",
            "--bind=/system", "--bind=/dev", "--bind=/proc", "--bind=/sys",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=${paths.work.canonicalPath}:$GUEST_WORK"
        )
        for (hostPath in listOf("/apex", "/vendor", "/odm")) {
            if (File(hostPath).exists()) args += "--bind=$hostPath"
        }
        args += listOf(
            "$PREFIX/bin/env", "-i",
            "HOME=$HOME",
            "PREFIX=$PREFIX",
            "PATH=$PREFIX/bin:/system/bin",
            "TMPDIR=$PREFIX/tmp",
            "LANG=C.UTF-8",
            "TERM=xterm-256color",
            "CARGO_HOME=$HOME/.cargo",
            "RUSTUP_HOME=$HOME/.rustup",
            "CARGO_BUILD_JOBS=2",
            "NATIVE_CACHE=$GUEST_WORK/cache",
            "ANDROID_HOME=$PREFIX/opt/android-sdk",
            "ANDROID_NDK_HOME=$PREFIX/opt/android-ndk"
        )
        args += argv

        val builder = ProcessBuilder(args).redirectErrorStream(true)
        val environment = builder.environment()
        environment.clear()
        environment["PROOT_LOADER"] = paths.loader.absolutePath
        environment["PROOT_TMP_DIR"] = paths.prootTmp.absolutePath
        environment["PROOT_L2S_DIR"] = paths.link2symlink.absolutePath
        environment["PATH"] = "/system/bin"
        environment["ANDROID_ROOT"] = "/system"
        environment["ANDROID_DATA"] = "/data"

        val process = builder.start()
        process.outputStream.close()
        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    onLine(line)
                    synchronized(output) {
                        if (output.length < OUTPUT_LIMIT) output.append(line).append('\n')
                    }
                }
            }
        }.apply {
            name = "ZaRustNativeBuilderOutput"
            isDaemon = true
            start()
        }

        val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completed) {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }
        reader.join(5000)
        val text = synchronized(output) { output.toString() }
        CommandResult(
            exitCode = if (completed) process.exitValue() else -1,
            output = text,
            timedOut = !completed
        )
    }

    private suspend fun installBootstrap(
        context: Context,
        onLine: (String) -> Unit
    ) {
        val paths = hostPaths(context)
        val rootfs = paths.rootfs
        val work = paths.work
        val usrParent = File(rootfs, "data/data/com.termux/files")
        val staging = File(usrParent, "usr-staging")
        val prefix = guestFile(context, PREFIX)
        val archive = File(work, "bootstrap-aarch64.zip")
        val partial = File(work, "bootstrap-aarch64.zip.partial")

        if (staging.exists()) check(staging.deleteRecursively()) {
            "Could not clear the incomplete bootstrap staging directory."
        }
        if (prefix.exists()) check(prefix.deleteRecursively()) {
            "Could not replace the incomplete bootstrap."
        }
        check(staging.mkdirs() || staging.isDirectory) {
            "Could not create the bootstrap staging directory."
        }

        val bundledBootstrap = runCatching {
            context.assets.open("rust-builder/bootstrap-aarch64.zip")
        }.getOrNull()
        if (bundledBootstrap != null) {
            copyBundledVerified(bundledBootstrap, partial, BOOTSTRAP_SHA256, onLine)
            onLine("Using the SHA-256 verified Android bootstrap bundled in this APK.")
        } else {
            onLine("Bundled bootstrap is unavailable; downloading the pinned archive.")
            downloadVerified(BOOTSTRAP_URL, partial, BOOTSTRAP_SHA256, onLine)
        }
        if (archive.exists()) check(archive.delete()) {
            "Could not remove a stale Android bootstrap archive."
        }
        check(partial.renameTo(archive)) { "Could not finalize the bootstrap archive." }

        onLine("Extracting the Android userspace…")
        val symlinks = ArrayList<Pair<String, String>>()
        var expanded = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "SYMLINKS.txt") {
                    val data = zip.readLimited(4 * 1024 * 1024)
                    data.toString(Charsets.UTF_8).lineSequence()
                        .filter { it.isNotBlank() }.forEach { line ->
                            val pair = line.split('←')
                            require(pair.size == 2) { "Malformed bootstrap symlink entry." }
                            symlinks += pair[0] to safeRelativePath(pair[1])
                        }
                } else {
                    val relative = safeRelativePath(entry.name)
                    val target = File(staging, relative)
                    require(target.canonicalFile.path.startsWith(staging.canonicalFile.path + File.separator)) {
                        "Unsafe path in the Android bootstrap archive."
                    }
                    if (entry.isDirectory) {
                        check(target.mkdirs() || target.isDirectory) {
                            "Could not extract bootstrap directory."
                        }
                    } else {
                        check(target.parentFile?.mkdirs() != false) {
                            "Could not create a bootstrap parent directory."
                        }
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                expanded += count
                                require(expanded <= MAX_BOOTSTRAP_EXPANDED) {
                                    "Expanded Android bootstrap exceeds the safety limit."
                                }
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                        if (relative.startsWith("bin/") || relative.startsWith("libexec/") ||
                            relative.startsWith("lib/apt/apt-helper") ||
                            relative.startsWith("lib/apt/methods/")
                        ) {
                            runCatching { Os.chmod(target.absolutePath, 0x1C0) }
                        }
                    }
                }
                zip.closeEntry()
            }
        }

        require(symlinks.isNotEmpty()) { "The Android bootstrap has no symlink table." }
        for ((target, relative) in symlinks) {
            val link = File(staging, relative)
            check(link.parentFile?.mkdirs() != false) {
                "Could not create a bootstrap symlink parent."
            }
            if (link.exists() || isSymlink(link)) link.delete()
            Os.symlink(target, link.absolutePath)
        }

        check(staging.renameTo(prefix)) {
            "Could not activate the Android bootstrap."
        }
        File(rootfs, HOME.removePrefix("/")).mkdirs()
        File(rootfs, "tmp").mkdirs()
        archive.delete()
        onLine("Android userspace installed.")
    }

    private suspend fun copyBundledVerified(
        input: java.io.InputStream,
        destination: File,
        expectedSha256: String,
        onLine: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        destination.parentFile?.mkdirs()
        try {
            FileOutputStream(destination).use { output ->
                input.use { source ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        received += count
                        require(received <= MAX_BOOTSTRAP_DOWNLOAD) {
                            "Bundled Android bootstrap exceeds the safety limit."
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
                output.fd.sync()
            }
            require(received > 0L) { "Bundled Android bootstrap asset is empty." }
            require(digest.digest().toHex() == expectedSha256) {
                "Bundled Android bootstrap SHA-256 verification failed."
            }
            onLine("Verified bundled Android bootstrap (${received / 1024 / 1024} MB).")
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    private suspend fun downloadVerified(
        url: String,
        destination: File,
        expectedSha256: String,
        onLine: (String) -> Unit
    ) {
        val connection = open(url)
        try {
            require(connection.responseCode in 200..299) {
                "Could not download the Android userspace (HTTP ${connection.responseCode})."
            }
            val length = connection.getHeaderFieldLong("Content-Length", -1L)
            require(length in 1..MAX_BOOTSTRAP_DOWNLOAD) {
                "Android bootstrap archive size is invalid."
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            destination.parentFile?.mkdirs()
            FileOutputStream(destination).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        require(received <= MAX_BOOTSTRAP_DOWNLOAD) {
                            "Android bootstrap download exceeds the safety limit."
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        onLine("Downloaded ${received / 1024 / 1024} MB of the Android userspace.")
                    }
                }
                output.fd.sync()
            }
            require(received == length) { "Android bootstrap download is incomplete." }
            val actual = digest.digest().toHex()
            require(actual == expectedSha256) {
                "Android bootstrap SHA-256 verification failed."
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun open(value: String): HttpURLConnection {
        val url = URL(value)
        require(url.protocol == "https" &&
            (url.host == "github.com" || url.host == "objects.githubusercontent.com")) {
            "Untrusted Android bootstrap URL."
        }
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ZaRust-Launcher")
        }
    }

    private fun hostPaths(context: Context): HostPaths {
        val rootfs = rootfsDirectory(context)
        val work = workDirectory(context)
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val proot = File(nativeDir, "libproot.so")
        val loader = File(nativeDir, "libproot-loader.so")
        val tmp = File(work, "proot-tmp").apply { mkdirs() }
        val l2s = File(rootfs, ".l2s").apply { mkdirs() }
        listOf("system", "apex", "vendor", "odm", "dev", "proc", "sys",
            GUEST_WORK.removePrefix("/"), HOME.removePrefix("/"),
            PREFIX.removePrefix("/").substringBeforeLast("/")).forEach {
            val directory = File(rootfs, it)
            if (!directory.exists()) directory.mkdirs()
        }
        return HostPaths(rootfs, work, proot, loader, tmp, l2s)
    }

    private fun guestFile(context: Context, guestPath: String): File =
        File(rootfsDirectory(context), guestPath.removePrefix("/"))

    private fun requireRunner(context: Context) {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        require(File(nativeDir, "libproot.so").canExecute() &&
            File(nativeDir, "libproot-loader.so").isFile) {
            "This ZaRust APK does not include the private Android build runner. Rebuild ZaRust with the native-host component enabled."
        }
    }

    private fun safeRelativePath(value: String): String {
        val normalized = value.replace('\\', '/').trimStart('/')
        require(normalized.isNotBlank() &&
            normalized.split('/').none { it == "." || it == ".." } &&
            !normalized.startsWith("../")) {
            "Unsafe path in the Android bootstrap archive."
        }
        return normalized
    }

    private fun isSymlink(file: File): Boolean =
        runCatching { Os.readlink(file.absolutePath); true }.getOrDefault(false)

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun java.io.InputStream.readLimited(maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(out.size() + count <= maxBytes) { "Bootstrap metadata entry is too large." }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}
