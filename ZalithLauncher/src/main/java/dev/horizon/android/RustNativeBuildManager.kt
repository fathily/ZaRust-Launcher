package dev.horizon.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class RustNativeBuild(
    val name: String,
    val mods: List<String>,
    val metadataUrl: String,
    val libraryUrl: String
) {
    val label: String
        get() = mods.joinToString(", ") { id ->
            id.replace('-', ' ').replace('_', ' ')
                .split(' ')
                .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        }
}

/** Downloads version-matched Android game libraries built by this repository's isolated CI. */
object RustNativeBuildManager {
    private const val RELEASE_API =
        "https://api.github.com/repos/fathily/ZaRust-Launcher/releases/tags/native-builds"
    private const val LIBRARY_NAME = "libhorizon_android.so"
    private val safeName = Regex("[a-z0-9][a-z0-9._-]{0,120}")
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    fun appVersion(context: Context): String {
        val raw = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            ?: error("App version was not found")
        return raw.removeSuffix("-debug")
    }

    fun currentBuild(context: Context): String? {
        val file = File(context.filesDir, "native/current")
        val name = runCatching { file.readText().trim() }.getOrNull() ?: return null
        if (!safeName.matches(name) || name.startsWith('.')) return null
        val folder = File(context.filesDir, "native/$name")
        val library = File(folder, LIBRARY_NAME)
        val metadataFile = File(folder, "build.json")
        if (!library.isFile || !metadataFile.isFile) return null
        val versionMatches = runCatching {
            File(folder, "for").readText().trim() == appVersion(context)
        }.getOrDefault(false)
        if (!versionMatches) return null
        val metadataMatches = runCatching {
            val metadata = JSONObject(metadataFile.readText(Charsets.UTF_8))
            metadata.optString("zaRustVersion") == appVersion(context) &&
                metadata.optString("abi") == "arm64-v8a" &&
                metadata.optString("library") == LIBRARY_NAME &&
                metadata.optLong("size", -1L) == library.length()
        }.getOrDefault(false)
        return name.takeIf { metadataMatches }
    }

    /** Returns the verified app-private library selected for the next Rust launch. */
    fun currentLibrary(context: Context): File? {
        val name = currentBuild(context) ?: return null
        val library = File(context.filesDir, "native/$name/$LIBRARY_NAME")
        val magic = runCatching {
            RandomAccessFile(library, "r").use { file ->
                val ident = ByteArray(5)
                file.readFully(ident)
                ident[0] == 0x7f.toByte() &&
                    ident[1] == 0x45.toByte() &&
                    ident[2] == 0x4c.toByte() &&
                    ident[3] == 0x46.toByte() &&
                    ident[4] == 2.toByte()
            }
        }.getOrDefault(false)
        return library.takeIf { magic }
    }

    suspend fun list(context: Context): List<RustNativeBuild> = withContext(Dispatchers.IO) {
        val version = appVersion(context)
        val release = JSONObject(getText(RELEASE_API))
        val assets = release.optJSONArray("assets")
            ?: return@withContext emptyList()
        val urls = HashMap<String, String>()
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (safeName.matches(name) && isTrustedAssetUrl(url, name)) {
                urls[name] = url
            }
        }
        val prefix = "v${version}__"
        urls.keys.asSequence()
            .filter { it.endsWith(".json") && it.startsWith(prefix) }
            .mapNotNull { jsonName ->
                val buildName = jsonName.removeSuffix(".json")
                val ids = buildName.removePrefix(prefix).split("__")
                val libraryUrl = urls["$buildName.so"]
                val jsonUrl = urls[jsonName]
                if (ids.isEmpty() || ids.any { !safeName.matches(it) } ||
                    jsonUrl == null || libraryUrl == null
                ) null else RustNativeBuild(buildName, ids, jsonUrl, libraryUrl)
            }
            .sortedWith(compareBy<RustNativeBuild> { it.mods.size }.thenBy { it.name })
            .toList()
    }

    suspend fun install(
        context: Context,
        build: RustNativeBuild,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ): String = withContext(Dispatchers.IO) {
        require(safeName.matches(build.name) && !build.name.startsWith('.')) {
            "Invalid native build name"
        }
        val version = appVersion(context)
        require(isTrustedAssetUrl(build.metadataUrl, "${build.name}.json")) {
            "Untrusted native build metadata URL"
        }
        require(isTrustedAssetUrl(build.libraryUrl, "${build.name}.so")) {
            "Untrusted native library URL"
        }
        val metadata = JSONObject(getText(build.metadataUrl))
        require(metadata.optString("version") == version) {
            "This native build was not compiled for ZaRust $version"
        }
        require(metadata.optString("abi") == "arm64-v8a") {
            "Native build ABI does not match arm64-v8a"
        }
        require(metadata.optString("library") == LIBRARY_NAME) {
            "Native library name does not match"
        }
        val expectedHash = metadata.optString("sha256")
        require(sha256Pattern.matches(expectedHash)) { "Native build SHA-256 is invalid" }
        val expectedSize = metadata.optLong("size", -1L)
        require(expectedSize in 1L..(200L * 1024L * 1024L)) {
            "Native build size is outside the allowed limit"
        }

        val nativeRoot = File(context.filesDir, "native")
        val folder = File(nativeRoot, build.name)
        require(folder.canonicalFile.parentFile == nativeRoot.canonicalFile) {
            "Invalid native build location"
        }
        require(folder.isDirectory || folder.mkdirs()) {
            "Could not create the native build folder"
        }
        val part = File(folder, "$LIBRARY_NAME.part")
        val target = File(folder, LIBRARY_NAME)
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        try {
            val connection = open(build.libraryUrl)
            try {
                val code = connection.responseCode
                require(code in 200..299) { "Native library download failed: HTTP $code" }
                val total = connection.getHeaderFieldLong("Content-Length", expectedSize)
                    .takeIf { it > 0 } ?: expectedSize
                connection.inputStream.use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            require(received <= expectedSize) {
                                "Native build file is larger than its manifest"
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            onProgress(received, total)
                        }
                        output.fd.sync()
                    }
                }
            } finally {
                connection.disconnect()
            }

            require(received == expectedSize) {
                "Native file size mismatch: $received / $expectedSize bytes"
            }
            val actualHash = digest.digest().joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
            require(actualHash == expectedHash) {
                "SHA-256 mismatch. The downloaded file was removed to prevent loading a corrupted library."
            }
            val magic = part.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
            require(magic.contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))) {
                "The downloaded file is not an Android ELF library"
            }

            if (target.exists() && !target.delete()) error("Could not replace the previous native library")
            check(part.renameTo(target)) { "Could not save the native library" }
            atomicWrite(File(folder, "for"), version)
            atomicWrite(File(folder, "build.json"), metadata.toString(2))
            atomicWrite(File(nativeRoot, "current"), build.name)
            build.name
        } catch (error: Throwable) {
            part.delete()
            throw error
        }
    }

    /**
     * Installs a native library compiled locally by ZaRust. No release or network asset is used.
     * The library and manifest are verified again before the runtime selector is updated.
     */
    suspend fun installLocal(
        context: Context,
        buildName: String,
        sourceLibrary: File,
        metadataFile: File
    ): String = withContext(Dispatchers.IO) {
        require(safeName.matches(buildName) && !buildName.startsWith('.')) {
            "Invalid local native build name"
        }
        require(sourceLibrary.isFile && sourceLibrary.length() in 1L..(200L * 1024L * 1024L)) {
            "The locally compiled native library is missing or has an invalid size."
        }
        require(metadataFile.isFile && metadataFile.length() <= 1024L * 1024L) {
            "The local native build manifest is missing or too large."
        }
        val version = appVersion(context)
        val metadata = JSONObject(metadataFile.readText(Charsets.UTF_8))
        require(metadata.optString("zaRustVersion") == version) {
            "This native build was compiled for ZaRust ${metadata.optString("zaRustVersion")}, not $version."
        }
        require(metadata.optString("abi") == "arm64-v8a") {
            "The local native build is not arm64-v8a."
        }
        require(metadata.optString("library") == LIBRARY_NAME) {
            "The local native build has an unexpected library name."
        }
        val expectedSize = metadata.optLong("size", -1L)
        require(expectedSize == sourceLibrary.length()) {
            "The native library size does not match its manifest."
        }
        val expectedHash = metadata.optString("sha256")
        require(sha256Pattern.matches(expectedHash)) {
            "The local native build manifest contains an invalid SHA-256."
        }

        val digest = MessageDigest.getInstance("SHA-256")
        sourceLibrary.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actualHash = digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        require(actualHash == expectedHash) {
            "Local native build SHA-256 mismatch. The result was not installed."
        }

        RandomAccessFile(sourceLibrary, "r").use { file ->
            val ident = ByteArray(5)
            file.readFully(ident)
            require(
                ident[0] == 0x7f.toByte() &&
                    ident[1] == 0x45.toByte() &&
                    ident[2] == 0x4c.toByte() &&
                    ident[3] == 0x46.toByte() &&
                    ident[4] == 2.toByte()
            ) { "The local result is not a 64-bit Android ELF library." }
            file.seek(18)
            val machine = file.readUnsignedByte() or (file.readUnsignedByte() shl 8)
            require(machine == 183) { "The local native library is not compiled for ARM64." }
        }

        val nativeRoot = File(context.filesDir, "native")
        require(nativeRoot.isDirectory || nativeRoot.mkdirs()) {
            "Could not create the native runtime directory."
        }
        val folder = File(nativeRoot, buildName)
        require(folder.canonicalFile.parentFile == nativeRoot.canonicalFile) {
            "Invalid local native build destination."
        }
        val staging = File(nativeRoot, ".$buildName.partial")
        if (staging.exists()) staging.deleteRecursively()
        require(staging.mkdirs()) { "Could not create a temporary runtime folder." }

        try {
            val target = File(staging, LIBRARY_NAME)
            sourceLibrary.inputStream().use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output, 256 * 1024)
                    output.fd.sync()
                }
            }
            FileOutputStream(File(staging, "for")).use { output ->
                output.write(version.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            FileOutputStream(File(staging, "build.json")).use { output ->
                output.write(metadata.toString(2).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }

            if (folder.exists()) {
                require(folder.deleteRecursively()) {
                    "Could not replace the previous local native build."
                }
            }
            check(staging.renameTo(folder)) { "Could not activate the local native build folder." }
            atomicWrite(File(nativeRoot, "current"), buildName)
            buildName
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun disable(context: Context) {
        File(context.filesDir, "native/current").delete()
    }

    private fun atomicWrite(target: File, content: String) {
        val temp = File(target.parentFile, target.name + ".partial")
        FileOutputStream(temp).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (target.exists() && !target.delete()) {
            temp.delete()
            error("Could not replace file " + target.name)
        }
        check(temp.renameTo(target)) {
            temp.delete()
            "Could not save " + target.name
        }
    }

    private fun isTrustedAssetUrl(value: String, name: String): Boolean = runCatching {
        val url = URL(value)
        url.protocol == "https" &&
            url.host == "github.com" &&
            url.path.startsWith("/fathily/ZaRust-Launcher/releases/download/native-builds/") &&
            url.path.endsWith("/$name")
    }.getOrDefault(false)

    private fun getText(url: String): String {
        val connection = open(url)
        try {
            val code = connection.responseCode
            require(code in 200..299) {
                if (code == 404) "No native build has been published for ZaRust yet."
                else "GitHub API mengembalikan HTTP $code"
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(value: String): HttpURLConnection {
        val url = URL(value)
        require(url.protocol == "https") { "Connection must use HTTPS" }
        require(url.host == "api.github.com" || url.host == "github.com") {
            "Download host is not trusted"
        }
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 90_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ZaRust-Launcher")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
    }
}
