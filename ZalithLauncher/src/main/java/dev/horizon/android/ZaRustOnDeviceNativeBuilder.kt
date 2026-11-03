package dev.horizon.android

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Builds the Android Horizon library locally inside ZaRust's private PRoot userspace.
 * Each build starts from a clean resolved Starling commit; only Cargo/dependency caches are reused.
 * Native .hnmod bundles are staged locally and are never uploaded to a build service.
 */
object ZaRustOnDeviceNativeBuilder {
    const val DEFAULT_STARLING_COMMIT =
        "1d45d81bad530e20cfa18e9b47b1d7247b469ea4"
    const val HORIZON_MIXINS_REVISION =
        "0d6b103c70633d8fd9443d8d0acd3aadaacedb06"

    private const val SOURCE_REPO = "JuanLuci/starling"
    private const val MAX_SOURCE_ARCHIVE = 420L * 1024L * 1024L
    private const val MAX_SOURCE_EXPANDED = 2_500L * 1024L * 1024L
    private const val MAX_MOD_EXPANDED = 128L * 1024L * 1024L
    private const val MAX_MOD_FILES = 5000
    private val shaPattern = "[0-9a-f]{40}".toRegex()
    private val modIdPattern = "[a-z0-9][a-z0-9_-]{0,40}".toRegex()

    data class BuildResult(
        val name: String,
        val starlingCommit: String,
        val mods: List<String>,
        val library: File,
        val metadata: File
    )

    suspend fun resolveStarlingCommit(reference: String): String {
        val value = reference.trim()
        require(value.isNotBlank() && value.length <= 128) {
            "Enter a Starling commit SHA, tag, or branch name."
        }
        if (shaPattern.matches(value)) return value
        val encoded = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
        val json = JSONObject(readText("https://api.github.com/repos/$SOURCE_REPO/commits/$encoded"))
        val sha = json.optString("sha")
        require(shaPattern.matches(sha)) {
            "GitHub did not return a valid Starling commit for '$value'."
        }
        return sha
    }

    fun importedHnmods(context: Context): List<File> {
        val folder = File(context.filesDir, "rust-edition/imported-hnmod")
        return folder.listFiles()
            ?.filter { it.isFile && it.extension.equals("hnmod", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    suspend fun build(
        context: Context,
        starlingReference: String,
        onLine: (String) -> Unit = {}
    ): BuildResult {
        val status = ZaRustNativeBuildEnvironment.status(context)
        require(status.abiSupported) {
            "On-device native builds currently require an ARM64 Android device."
        }
        require(status.nativeRunnerInstalled) {
            "This APK is missing its bundled PRoot host runner. Rebuild ZaRust with the native-host component enabled."
        }
        require(status.freeBytes >= 5L * 1024L * 1024L * 1024L) {
            "At least 5 GB of free internal storage is required before starting a native build."
        }
        if (!status.ready) {
            onLine("Preparing ZaRust's private Android toolchain…")
            ZaRustNativeBuildEnvironment.prepare(context, onLine)
        }

        val starlingCommit = resolveStarlingCommit(starlingReference)
        onLine("Selected Starling commit: $starlingCommit")
        obtainStarlingSource(context, starlingCommit, onLine)
        val mods = stageImportedMods(context, onLine)
        val work = ZaRustNativeBuildEnvironment.workDirectory(context)
        val toolsDir = File(work, "tools").apply { mkdirs() }
        val outDir = File(ZaRustNativeBuildEnvironment.buildsDirectory(context), "staging-$starlingCommit")
        if (outDir.exists()) require(outDir.deleteRecursively()) {
            "Could not clear the previous incomplete build output."
        }
        require(outDir.mkdirs()) { "Could not create the local native build output folder." }

        copyAsset(context, "rust-builder/native_build_android.sh", File(toolsDir, "native_build_android.sh"))
        copyAsset(context, "rust-builder/patch_starling.py", File(toolsDir, "patch_starling.py"))

        val guestRoot = "/data/data/com.zarust.launcher/files/native-builder"
        val guestSource = "$guestRoot/sources/starling-$starlingCommit"
        val guestOutput = "$guestRoot/builds/staging-$starlingCommit"
        val guestScript = "$guestRoot/tools/native_build_android.sh"
        val guestMods = mods.map { "$guestRoot/build-mods/${it.name}" }

        val command = ArrayList<String>()
        command += "/data/data/com.termux/files/usr/bin/bash"
        command += guestScript
        command += guestSource
        command += guestOutput
        command += RustNativeBuildManager.appVersion(context)
        command += starlingCommit
        command += guestMods

        onLine("Starting the local Android compiler. Source code and mod bundles stay on this device.")
        val result = ZaRustNativeBuildEnvironment.run(
            context = context,
            argv = command,
            cwd = guestRoot,
            timeoutMs = 4L * 60L * 60L * 1000L,
            onLine = onLine
        )
        require(result.success) {
            val reason = if (result.timedOut) "The on-device native build exceeded its time limit."
            else "The on-device native build failed with exit code ${result.exitCode}."
            "$reason\n${result.output.takeLast(10_000)}"
        }

        val library = File(outDir, "libhorizon_android.so")
        val metadataFile = File(outDir, "build.json")
        require(library.isFile && metadataFile.isFile) {
            "The build process ended without producing libhorizon_android.so and build.json."
        }
        val metadata = JSONObject(metadataFile.readText(Charsets.UTF_8))
        require(metadata.optString("starlingCommit") == starlingCommit) {
            "The compiled library metadata does not match the selected Starling commit."
        }
        require(metadata.optString("abi") == "arm64-v8a") {
            "The compiled native library is not ARM64."
        }

        val modIds = ArrayList<String>()
        val modsArray = metadata.optJSONArray("mods")
        if (modsArray != null) {
            for (i in 0 until modsArray.length()) {
                modsArray.optJSONObject(i)?.optString("id")
                    ?.takeIf { modIdPattern.matches(it) }?.let(modIds::add)
            }
        }
        val key = metadata.optString("key")
        require(key.matches("[a-z0-9][a-z0-9_-]{0,80}".toRegex())) {
            "The native build returned an invalid build identifier."
        }

        val buildName = "local-" + starlingCommit.take(12) + "-" + key
        val installedName = RustNativeBuildManager.installLocal(
            context = context,
            buildName = buildName,
            sourceLibrary = library,
            metadataFile = metadataFile
        )
        onLine("Native library installed locally: $installedName")
        return BuildResult(installedName, starlingCommit, modIds, library, metadataFile)
    }

    private suspend fun obtainStarlingSource(
        context: Context,
        commit: String,
        onLine: (String) -> Unit
    ): File {
        val source = File(ZaRustNativeBuildEnvironment.sourceDirectory(context), "starling-$commit")
        if (File(source, "Cargo.toml").isFile &&
            File(source, "crates/horizon-android/Cargo.toml").isFile
        ) {
            onLine("Reusing the clean cached Starling source for $commit.")
            return source
        }
        if (source.exists()) require(source.deleteRecursively()) {
            "Could not remove the incomplete cached Starling source."
        }

        val work = ZaRustNativeBuildEnvironment.workDirectory(context)
        val archive = File(work, "starling-$commit.zip")
        val temporary = File(work, archive.name + ".partial")
        download(
            "https://codeload.github.com/$SOURCE_REPO/zip/$commit",
            temporary,
            MAX_SOURCE_ARCHIVE,
            onLine
        )
        require(temporary.renameTo(archive)) { "Could not finalize the Starling source archive." }

        onLine("Extracting clean Starling source $commit…")
        val parent = source.parentFile ?: error("Invalid Starling source cache path.")
        require(parent.mkdirs() || parent.isDirectory) {
            "Could not create the Starling source cache directory."
        }
        val staging = File(parent, ".starling-$commit.partial")
        if (staging.exists()) staging.deleteRecursively()
        require(staging.mkdirs()) { "Could not create the Starling source staging directory." }

        var expanded = 0L
        var fileCount = 0
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = safeZipPath(entry.name)
                val components = path.split('/')
                require(components.first().startsWith("starling-")) {
                    "Unexpected top-level folder in the Starling source archive."
                }
                val relative = components.drop(1).joinToString("/")
                if (relative.isBlank()) {
                    zip.closeEntry()
                    continue
                }
                val target = File(staging, relative)
                require(target.canonicalFile.path.startsWith(staging.canonicalFile.path + File.separator)) {
                    "Unsafe path in the Starling source archive."
                }
                if (entry.isDirectory) {
                    require(target.mkdirs() || target.isDirectory) {
                        "Could not extract a Starling source directory."
                    }
                } else {
                    fileCount++
                    require(fileCount <= 250_000) { "Starling source archive has too many files." }
                    require(target.parentFile?.mkdirs() != false) {
                        "Could not create a Starling source folder."
                    }
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            expanded += count
                            require(expanded <= MAX_SOURCE_EXPANDED) {
                                "Expanded Starling source exceeds the 2.5 GB safety limit."
                            }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                zip.closeEntry()
            }
        }
        require(File(staging, "Cargo.toml").isFile &&
            File(staging, "crates/horizon-android/Cargo.toml").isFile
        ) { "Downloaded source is not a compatible Starling Android workspace." }
        if (source.exists()) source.deleteRecursively()
        check(staging.renameTo(source)) { "Could not activate the clean Starling source folder." }
        archive.delete()
        return source
    }

    private suspend fun stageImportedMods(
        context: Context,
        onLine: (String) -> Unit
    ): List<File> {
        val imported = importedHnmods(context)
        val buildMods = File(ZaRustNativeBuildEnvironment.workDirectory(context), "build-mods")
        if (buildMods.exists()) require(buildMods.deleteRecursively()) {
            "Could not clear the previous native mod staging folder."
        }
        require(buildMods.mkdirs()) { "Could not create the native mod staging folder." }

        val ids = HashSet<String>()
        val staged = ArrayList<File>()
        for (packageFile in imported) {
            onLine("Validating native mod ${packageFile.name}…")
            require(packageFile.length() in 1L..(128L * 1024L * 1024L)) {
                "The .hnmod package ${packageFile.name} is empty or too large."
            }
            ZipFile(packageFile).use { zip ->
                val manifestEntry = zip.getEntry("horizon-native.json")
                    ?: error("${packageFile.name} has no horizon-native.json manifest.")
                val manifest = JSONObject(
                    zip.getInputStream(manifestEntry).bufferedReader().use { it.readText() }
                )
                val id = manifest.optString("id")
                require(modIdPattern.matches(id)) {
                    "The native mod ID in ${packageFile.name} is invalid."
                }
                require(ids.add(id)) { "The native mod '$id' was imported more than once." }
                require(manifest.optInt("schema", -1) == 1) {
                    "Native mod '$id' uses an unsupported manifest schema."
                }
                require(manifest.optString("mixins") == HORIZON_MIXINS_REVISION) {
                    "Native mod '$id' requires a different Horizon Mixins revision. This builder currently supports $HORIZON_MIXINS_REVISION."
                }

                val sourcePrefix = "mixins/$id/"
                val destination = File(buildMods, id)
                require(destination.mkdirs() || destination.isDirectory) {
                    "Could not create the staging folder for native mod '$id'."
                }
                val entries = zip.entries()
                var count = 0
                var expanded = 0L
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.startsWith(sourcePrefix)) continue
                    val relative = safeZipPath(entry.name.removePrefix(sourcePrefix))
                    count++
                    require(count <= MAX_MOD_FILES) { "Native mod '$id' contains too many files." }
                    val output = File(destination, relative)
                    require(output.canonicalFile.path.startsWith(destination.canonicalFile.path + File.separator)) {
                        "Native mod '$id' contains an unsafe file path."
                    }
                    require(output.parentFile?.mkdirs() != false) {
                        "Could not create a folder in native mod '$id'."
                    }
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(output).use { stream ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                expanded += n
                                require(expanded <= MAX_MOD_EXPANDED) {
                                    "Native mod '$id' expands beyond the 128 MB limit."
                                }
                                stream.write(buffer, 0, n)
                            }
                            stream.fd.sync()
                        }
                    }
                }
                require(File(destination, "horizon-mixin.toml").isFile) {
                    "Native mod '$id' has no mixins/$id/horizon-mixin.toml file."
                }
                staged += destination
            }
        }
        return staged
    }

    private fun copyAsset(context: Context, asset: String, target: File) {
        require(target.parentFile?.mkdirs() != false) {
            "Could not create the native builder tools directory."
        }
        val temp = File(target.parentFile, target.name + ".partial")
        context.assets.open(asset).use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output, 64 * 1024)
                output.fd.sync()
            }
        }
        if (target.exists() && !target.delete()) {
            temp.delete()
            error("Could not replace the local native build script.")
        }
        check(temp.renameTo(target)) { "Could not install the local native build script." }
    }

    private suspend fun download(
        url: String,
        destination: File,
        maximumBytes: Long,
        onLine: (String) -> Unit
    ) {
        val parsed = URL(url)
        require(parsed.protocol == "https" && parsed.host == "codeload.github.com") {
            "Untrusted Starling source download host."
        }
        val connection = (parsed.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ZaRust-Launcher")
        }
        try {
            require(connection.responseCode in 200..299) {
                "Starling source download failed with HTTP ${connection.responseCode}."
            }
            val length = connection.getHeaderFieldLong("Content-Length", -1L)
            require(length == -1L || length in 1..maximumBytes) {
                "Starling source archive is too large."
            }
            destination.parentFile?.mkdirs()
            var downloaded = 0L
            FileOutputStream(destination).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        require(downloaded <= maximumBytes) {
                            "Starling source archive exceeded the download limit."
                        }
                        output.write(buffer, 0, count)
                        onLine("Downloading Starling source: ${downloaded / 1024 / 1024} MB")
                    }
                }
                output.fd.sync()
            }
            if (length > 0) require(downloaded == length) {
                "Starling source download was incomplete."
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun readText(url: String): String {
        val parsed = URL(url)
        require(parsed.protocol == "https" && parsed.host == "api.github.com") {
            "Untrusted Starling version lookup host."
        }
        val connection = (parsed.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ZaRust-Launcher")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            require(connection.responseCode in 200..299) {
                "Could not resolve the selected Starling version (HTTP ${connection.responseCode})."
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun safeZipPath(value: String): String {
        val normalized = value.replace('\\', '/').trimStart('/')
        require(normalized.isNotBlank() &&
            normalized.split('/').none { it == "." || it == ".." } &&
            normalized.none { it == '\u0000' }) {
            "Unsafe path in a source or native mod archive."
        }
        return normalized
    }
}
