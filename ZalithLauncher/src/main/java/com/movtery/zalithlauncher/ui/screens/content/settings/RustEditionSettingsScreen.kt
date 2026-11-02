package com.movtery.zalithlauncher.ui.screens.content.settings

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.movtery.zalithlauncher.game.account.AccountsManager
import dev.horizon.android.RustEditionBridge
import java.io.File
import java.io.FileOutputStream

private data class RustConfigDocument(
    val title: String,
    val fileName: String,
    val description: String,
    val initialContent: String = ""
)

private val rustConfigDocuments = listOf(
    RustConfigDocument(
        "Game & Keyboard / Mouse",
        "options.txt",
        "Pengaturan game bergaya vanilla: FOV, render distance, FPS, audio, bahasa, chat, sensitivitas mouse, dan key binding. Key binding memakai baris key_<nama>:key.keyboard.<tombol> atau key.mouse.<tombol>."
    ),
    RustConfigDocument(
        "Horizon Graphics",
        "horizon-graphics.txt",
        "Pengaturan renderer Horizon: LOD distance, culling, chunk upload budget, worker threads, resolution scale, FXAA, battery mode, frame pacing, overlay FPS, dan graphics API.",

    ),
    RustConfigDocument(
        "Controller & On-screen Keyboard",
        "horizon-controller.txt",
        "Pengaturan gamepad Horizon: enable, vibration, adaptive triggers, deadzone, sensitivity, button glyph, menu navigation, dan keyboard virtual. Tetap gunakan pilihan input Zalith / Starling di atas.",

    ),
    RustConfigDocument(
        "Horizon Extras",
        "horizon-extras.txt",
        "Fitur tambahan Horizon: fullbright, dynamic lights, zoom, FPS counter, keystrokes/CPS, minimap, auto reconnect, mouse tweaks, dan item sorting.",

    ),
    RustConfigDocument(
        "Horizon Mods",
        "horizon-mods.txt",
        "Aktifkan mod yang sudah dipasang di folder horizon-mods. Satu mod per baris dengan format mod.<id>:true. File .hmod tetap diimpor lewat bagian Mod Manager.",
        ""
    )
)

@Composable
fun RustEditionSettingsScreen() {
    val context = LocalContext.current
    val account by AccountsManager.currentAccountFlow.collectAsStateWithLifecycle()
    var inputMode by remember { mutableStateOf(RustEditionBridge.getInputMode(context)) }
    var pickerMessage by remember { mutableStateOf<String?>(null) }
    var selectedDocument by remember { mutableIntStateOf(0) }
    var editorText by remember {
        mutableStateOf(readRustConfig(RustEditionBridge.gameHome(context), rustConfigDocuments[0]))
    }

    val gameHome = RustEditionBridge.gameHome(context)
    val modsDir = RustEditionBridge.modsDirectory(context)
    val shadersDir = RustEditionBridge.shaderPacksDirectory(context)
    val resourcePacksDir = File(gameHome, "resourcepacks")
    val nativeDir = RustEditionBridge.nativeModsDirectory(context)
    val selected = rustConfigDocuments[selectedDocument]

    LaunchedEffect(selectedDocument, gameHome.absolutePath) {
        editorText = readRustConfig(gameHome, selected)
    }

    fun copySelected(uri: android.net.Uri?, destination: File, accepted: Set<String>) {
        if (uri == null) return
        runCatching {
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            } ?: "imported-file"
            val extension = name.substringAfterLast('.', "").lowercase()
            require(extension in accepted) {
                "Format tidak didukung. Gunakan: " + accepted.joinToString(", ")
            }
            require(destination.isDirectory || destination.mkdirs()) {
                "Tidak bisa membuat folder \${destination.absolutePath}"
            }
            val target = File(destination, name)
            val temporary = File(destination, name + ".partial")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "File tidak bisa dibaca" }
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (target.exists() && !target.delete()) {
                throw IllegalStateException("Tidak bisa mengganti file lama: $name")
            }
            check(temporary.renameTo(target)) { "Gagal menyimpan $name" }
            pickerMessage = "Berhasil memasang $name"
        }.onFailure {
            pickerMessage = it.message ?: "Gagal mengimpor file"
        }
    }

    val hmodPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        copySelected(uri, modsDir, setOf("hmod"))
    }
    val shaderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        copySelected(uri, shadersDir, setOf("hshader", "zip"))
    }
    val resourcePackPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        copySelected(uri, resourcePacksDir, setOf("zip"))
    }

    val gameFilesReady = File(File(context.filesDir, "minecraft"), "starling-files.json").isFile

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Rust Edition", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Kelola Rust Edition langsung dari pengaturan ZaRust. Akun aktif tetap memakai akun ZaRust; tidak perlu layar login kedua.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Status Game", style = MaterialTheme.typography.titleMedium)
                Text(if (gameFilesReady) "File game sudah terpasang." else "File game belum terdeteksi. Tekan Play Rust untuk menyiapkan file game resmi.")
                Text("Akun: " + (account?.let { "\${it.username} · \${it.accountType ?: "Local"}" } ?: "Offline"))
                Text("Folder game: \${gameHome.absolutePath}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Input & Kontrol", style = MaterialTheme.typography.titleMedium)
                Text("Preferensi disimpan di ZaRust. Integrasi overlay touch Zalith ke jendela native Rust masih perlu adapter input; pilihan Starling tetap memakai input engine.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = inputMode == "zalith",
                        onClick = {
                            inputMode = "zalith"
                            RustEditionBridge.setInputMode(context, inputMode)
                        },
                        label = { Text("Zalith") }
                    )
                    FilterChip(
                        selected = inputMode == "starling",
                        onClick = {
                            inputMode = "starling"
                            RustEditionBridge.setInputMode(context, inputMode)
                        },
                        label = { Text("Starling") }
                    )
                }
                Text(
                    "Keyboard fisik dan mouse: mapping disimpan di options.txt. Pengaturan keyboard virtual dan gamepad Horizon ada di tab Controller & On-screen Keyboard.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Horizon Settings", style = MaterialTheme.typography.titleMedium)
                Text("Semua berkas konfigurasi yang dipakai Horizon tersedia dari ZaRust. Pilih kategori, ubah nilainya, lalu simpan sebelum menekan Play Rust.")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rustConfigDocuments.forEachIndexed { index, document ->
                        FilterChip(
                            selected = selectedDocument == index,
                            onClick = { selectedDocument = index },
                            label = { Text(document.title) }
                        )
                    }
                }
                Text(selected.fileName, style = MaterialTheme.typography.labelLarge)
                Text(selected.description, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = editorText,
                    onValueChange = { editorText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 10,
                    maxLines = 24,
                    label = { Text("Isi \${selected.fileName}") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        pickerMessage = runCatching {
                            writeRustConfig(gameHome, selected, editorText)
                            "Tersimpan: \${selected.fileName}"
                        }.getOrElse { "Gagal menyimpan: \${it.message}" }
                    }) {
                        Text("Simpan Pengaturan")
                    }
                    TextButton(onClick = {
                        editorText = readRustConfig(gameHome, selected)
                        pickerMessage = "Perubahan editor dibatalkan."
                    }) {
                        Text("Muat Ulang")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mod Manager · HMOD", style = MaterialTheme.typography.titleMedium)
                Text("Impor file .hmod ke folder Horizon. Status aktif/nonaktif mod disimpan di horizon-mods.txt.")
                Text(modsDir.absolutePath, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { hmodPicker.launch(arrayOf("*/*")) }) { Text("Impor HMOD") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shader Manager · HShader", style = MaterialTheme.typography.titleMedium)
                Text("Impor .hshader atau shader pack ZIP ke folder shaderpacks.")
                Text(shadersDir.absolutePath, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { shaderPicker.launch(arrayOf("*/*")) }) { Text("Impor Shader") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Resource Pack Manager", style = MaterialTheme.typography.titleMedium)
                Text("Impor resource pack ZIP ke folder game Rust Edition. Daftar pack yang aktif tetap mengikuti resourcePacks di options.txt.")
                Text(resourcePacksDir.absolutePath, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { resourcePackPicker.launch(arrayOf("*/*")) }) { Text("Impor Resource Pack") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Native Mods", style = MaterialTheme.typography.titleMedium)
                Text("Folder native build Starling: \${nativeDir.absolutePath}")
                Text("Native build upstream disimpan sebagai library .so beserta metadata; bukan file .hnmod.")
            }
        }

        pickerMessage?.let {
            Text(
                it,
                color = if (it.startsWith("Tersimpan") || it.startsWith("Berhasil")) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
    }
}

private fun readRustConfig(home: File, document: RustConfigDocument): String {
    val file = File(home, document.fileName)
    return runCatching {
        if (file.isFile) file.readText() else document.initialContent
    }.getOrDefault(document.initialContent)
}

private fun writeRustConfig(home: File, document: RustConfigDocument, content: String) {
    require(home.isDirectory || home.mkdirs()) { "Folder game tidak dapat dibuat: \${home.absolutePath}" }
    val target = File(home, document.fileName)
    val temporary = File(home, document.fileName + ".partial")
    FileOutputStream(temporary).use { output ->
        output.write(content.toByteArray(Charsets.UTF_8))
        output.fd.sync()
    }
    if (target.exists() && !target.delete()) {
        temporary.delete()
        error("Tidak dapat mengganti \${target.name}")
    }
    check(temporary.renameTo(target)) {
        temporary.delete()
        "Gagal menyimpan \${target.name}"
    }
}
