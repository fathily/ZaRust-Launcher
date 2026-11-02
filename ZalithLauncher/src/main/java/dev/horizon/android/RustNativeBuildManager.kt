package dev.horizon.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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
            ?: error("Versi aplikasi tidak ditemukan")
        return raw.removeSuffix("-debug")
    }

    fun currentBuild(context: Context): String? {
        val file = File(context.filesDir, "native/current")
        val name = runCatching { file.readText().trim() }.getOrNull() ?: return null
        if (!safeName.matches(name) || name.startsWith('.')) return null
        val folder = File(context.filesDir, "native/$name")
        return name.takeIf {
            File(folder, LIBRARY_NAME).isFile &&
                File(folder, "for").let { versionFile ->
                    runCatching { versionFile.readText().trim() == appVersion(context) }
                        .getOrDefault(false)
                }
        }
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
        val prefix = "v$version__"
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
            "Nama native build tidak valid"
        }
        val version = appVersion(context)
        val metadata = JSONObject(getText(build.metadataUrl))
        require(metadata.optString("version") == version) {
            "Native build ini bukan untuk ZaRust $version"
        }
        require(metadata.optString("abi") == "arm64-v8a") {
            "Native build tidak cocok dengan ABI arm64-v8a"
        }
        require(metadata.optString("library") == LIBRARY_NAME) {
            "Nama library native tidak cocok"
        }
        val expectedHash = metadata.optString("sha256")
        require(sha256Pattern.matches(expectedHash)) { "SHA-256 native build tidak valid" }
        val expectedSize = metadata.optLong("size", -1L)
        require(expectedSize in 1L..(200L * 1024L * 1024L)) {
            "Ukuran native build di luar batas yang diizinkan"
        }

        val nativeRoot = File(context.filesDir, "native")
        val folder = File(nativeRoot, build.name)
        require(folder.canonicalFile.parentFile == nativeRoot.canonicalFile) {
            "Lokasi native build tidak valid"
        }
        require(folder.isDirectory || folder.mkdirs()) {
            "Tidak bisa membuat folder native build"
        }
        val part = File(folder, "$LIBRARY_NAME.part")
        val target = File(folder, LIBRARY_NAME)
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        try {
            val connection = open(build.libraryUrl)
            try {
                val code = connection.responseCode
                require(code in 200..299) { "Download native library gagal: HTTP $code" }
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
                                "File native build lebih besar dari metadata"
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
                "Ukuran file native tidak cocok: $received / $expectedSize byte"
            }
            val actualHash = digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, "0") }
            require(actualHash == expectedHash) {
                "SHA-256 tidak cocok. File dihapus agar library rusak tidak dipakai."
            }
            val magic = part.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
            require(magic.contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))) {
                "File hasil build bukan library ELF Android"
            }

            if (target.exists() && !target.delete()) error("Tidak bisa mengganti library lama")
            check(part.renameTo(target)) { "Gagal menyimpan library native" }
            atomicWrite(File(folder, "for"), version)
            atomicWrite(File(folder, "build.json"), metadata.toString(2))
            atomicWrite(File(nativeRoot, "current"), build.name)
            build.name
        } catch (error: Throwable) {
            part.delete()
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
            error("Tidak bisa mengganti berkas " + target.name)
        }
        check(temp.renameTo(target)) {
            temp.delete()
            "Gagal menyimpan " + target.name
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
                if (code == 404) "Belum ada native build yang diterbitkan untuk ZaRust."
                else "GitHub API mengembalikan HTTP $code"
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(value: String): HttpURLConnection {
        val url = URL(value)
        require(url.protocol == "https") { "Koneksi harus menggunakan HTTPS" }
        require(url.host == "api.github.com" || url.host == "github.com") {
            "Host download tidak dipercaya"
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
