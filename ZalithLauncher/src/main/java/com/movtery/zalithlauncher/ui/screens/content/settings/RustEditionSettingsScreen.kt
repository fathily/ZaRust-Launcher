package com.movtery.zalithlauncher.ui.screens.content.settings

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

@Composable
fun RustEditionSettingsScreen() {
    val context = LocalContext.current
    val account by AccountsManager.currentAccountFlow.collectAsStateWithLifecycle()
    var inputMode by remember { mutableStateOf(RustEditionBridge.getInputMode(context)) }
    var pickerMessage by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }

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
                "Tidak bisa membuat folder ${destination.absolutePath}"
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

    val hmodPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        copySelected(uri, RustEditionBridge.modsDirectory(context), setOf("hmod"))
    }
    val shaderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        copySelected(uri, RustEditionBridge.shaderPacksDirectory(context), setOf("hshader", "zip"))
    }

    val gameHome = RustEditionBridge.gameHome(context)
    val modsDir = RustEditionBridge.modsDirectory(context)
    val shadersDir = RustEditionBridge.shaderPacksDirectory(context)
    val nativeDir = RustEditionBridge.nativeModsDirectory(context)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Rust Edition · Starling", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Starling berjalan sebagai engine Rust di dalam ZaRust. Java Edition, akun, dan pengaturan launcher tetap memakai sistem ZaRust yang sudah ada.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Akun ZaRust", style = MaterialTheme.typography.titleMedium)
                Text(
                    account?.let { "Akun aktif: ${it.username} · ${it.accountType ?: "Local"}" }
                        ?: "Belum ada akun dipilih. Starling akan berjalan sebagai pemain offline."
                )
                Text(
                    "Akun aktif diteruskan ke Starling. Token Microsoft disegel menggunakan Android Keystore; tidak ada layar login kedua.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Touch Control", style = MaterialTheme.typography.titleMedium)
                Text("Pilih preferensi kontrol. Mode kontrol perlu diuji pada perangkat untuk memastikan tidak ada input ganda.")
                TextButton(onClick = { expanded = true }) {
                    Text(if (inputMode == "zalith") "Preferensi: Zalith" else "Preferensi: Starling")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Zalith") },
                        onClick = {
                            inputMode = "zalith"
                            RustEditionBridge.setInputMode(context, inputMode)
                            expanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Starling") },
                        onClick = {
                            inputMode = "starling"
                            RustEditionBridge.setInputMode(context, inputMode)
                            expanded = false
                        }
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("HMOD · Mods", style = MaterialTheme.typography.titleMedium)
                Text("Impor mod .hmod ke folder mod Starling. Daftar mod, status aktif/nonaktif dan halaman konfigurasi dibaca engine dari folder ini.")
                Text(modsDir.absolutePath, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { hmodPicker.launch(arrayOf("*/*")) }) {
                    Text("Impor HMOD")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("HShader · Shader Packs", style = MaterialTheme.typography.titleMedium)
                Text("Impor .hshader atau shader pack ZIP. Pengaturan shader lanjutan dan pilihan shader dijalankan melalui menu video Starling.")
                Text(shadersDir.absolutePath, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { shaderPicker.launch(arrayOf("*/*")) }) {
                    Text("Impor HShader / ZIP")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Native Mods", style = MaterialTheme.typography.titleMedium)
                Text("Starling menyimpan native mod sebagai library .so dengan metadata/build di folder native. Builder dan pemilih build tersedia melalui menu Native Mods di dalam game.")
                Text(nativeDir.absolutePath, style = MaterialTheme.typography.bodySmall)
                Text("Catatan kompatibilitas: source Starling yang dipin tidak menyediakan ekstensi .hnmod; format native build upstream adalah .so + metadata.")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Folder Game", style = MaterialTheme.typography.titleMedium)
                Text(gameHome.absolutePath, style = MaterialTheme.typography.bodySmall)
                Text("Saves, resource packs, shader packs, dan mods disimpan di folder game Starling. File game resmi akan diverifikasi dan diunduh oleh engine.")
            }
        }

        pickerMessage?.let {
            Text(
                it,
                color = if (it.startsWith("Berhasil")) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
    }
}
