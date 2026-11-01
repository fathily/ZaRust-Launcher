package com.movtery.zalithlauncher.ui.screens.content.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.horizon.android.RustEditionBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun RustEditionSettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember { RustEditionBridge.readSettings(context) }

    var renderer by remember { mutableStateOf(initial.optString("renderer", "vulkan-compatibility")) }
    var orientation by remember { mutableStateOf(initial.optString("orientation", "landscape")) }
    var inputMode by remember { mutableStateOf(initial.optString("input_host", "auto")) }
    var profile by remember { mutableStateOf(initial.optInt("profile", 0).coerceIn(0, 4).toString()) }
    var aspect by remember { mutableStateOf(initial.optString("aspect", "native")) }
    var renderScale by remember { mutableStateOf(initial.optInt("render_scale", 100).toFloat().coerceIn(25f, 100f)) }
    var immersive by remember { mutableStateOf(initial.optBoolean("immersive", true)) }
    var avoidCutout by remember { mutableStateOf(initial.optBoolean("avoid_cutout", false)) }
    var performanceHints by remember { mutableStateOf(initial.optBoolean("performance_hints", false)) }
    var gpuProfile by remember { mutableStateOf(initial.optBoolean("gpu_profile", false)) }
    var recordPerformance by remember { mutableStateOf(initial.optBoolean("record_performance", false)) }
    var displayHz by remember { mutableStateOf(initial.optInt("display_hz", 0).toString()) }
    var playerName by remember { mutableStateOf(initial.optString("name", "")) }
    var server by remember { mutableStateOf(initial.optString("server", "")) }

    var ready by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }
    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(JSONObject()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save(key: String, value: Any) {
        runCatching {
            val json = RustEditionBridge.readSettings(context)
            json.put(key, value)
            RustEditionBridge.writeSettings(context, json)
        }.onFailure { error = it.message ?: "Gagal menyimpan pengaturan Rust" }
    }

    LaunchedEffect(Unit) {
        ready = runCatching { withContext(Dispatchers.IO) { RustEditionBridge.isReady(context) } }.getOrDefault(false)
        checking = false
    }
    LaunchedEffect(installing) {
        while (installing) {
            progress = RustEditionBridge.readProgress(context)
            delay(350)
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Rust Edition", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Pengaturan engine Horizon khusus Rust. Tidak mengubah konfigurasi Java Edition.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Minecraft Java 26.3", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        checking -> "Memeriksa resource…"
                        installing -> progress.optString("stage", "Menyiapkan resource…")
                        ready -> "Resource siap. Rust Edition bisa dimainkan dari Game → Versi."
                        else -> "Resource belum terpasang. Instalasi dijalankan dari UI ZaRust."
                    }
                )
                if (installing) {
                    val done = progress.optLong("done", 0).coerceAtLeast(0)
                    val total = progress.optLong("total", 0).coerceAtLeast(0)
                    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        if (total > 0) formatMiB(done) + " / " + formatMiB(total) + " MiB" else "Menyiapkan file…",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Button(
                    enabled = !checking && !installing,
                    onClick = {
                        scope.launch {
                            installing = true
                            error = null
                            val result = runCatching {
                                withContext(Dispatchers.IO) { RustEditionBridge.install(context) }
                            }.getOrElse { it.message ?: "Gagal memasang resource Minecraft" }
                            installing = false
                            ready = runCatching {
                                withContext(Dispatchers.IO) { RustEditionBridge.isReady(context) }
                            }.getOrDefault(false)
                            if (result.isNotBlank()) error = result
                        }
                    }
                ) {
                    Text(if (ready) "Periksa / perbaiki resource" else "Install Minecraft 26.3")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Perubahan pengaturan diterapkan saat Rust Edition diluncurkan berikutnya.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Grafis", style = MaterialTheme.typography.titleMedium)
                RustOptionRow(
                    "Rendering API", renderer,
                    listOf("Vulkan Compatibility" to "vulkan-compatibility", "Vulkan" to "vulkan", "OpenGL ES (eksperimental)" to "open-gl")
                ) {
                    renderer = it
                    save("renderer", it)
                }
                Text("Resolusi render: " + renderScale.toInt() + "%")
                Slider(
                    value = renderScale,
                    onValueChange = { renderScale = it },
                    valueRange = 25f..100f,
                    steps = 14,
                    onValueChangeFinished = { save("render_scale", renderScale.toInt()) }
                )
                RustSwitchRow("Fullscreen imersif", immersive) {
                    immersive = it
                    save("immersive", it)
                }
                RustSwitchRow("Hindari camera cutout", avoidCutout) {
                    avoidCutout = it
                    save("avoid_cutout", it)
                }
                RustOptionRow(
                    "Aspect ratio", aspect,
                    listOf("Native" to "native", "16:9" to "wide", "4:3" to "classic", "1:1" to "square")
                ) {
                    aspect = it
                    save("aspect", it)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Kontrol & profil", style = MaterialTheme.typography.titleMedium)
                RustOptionRow(
                    "Metode input", inputMode,
                    listOf("Otomatis" to "auto", "Touch" to "touch",
                        "Keyboard & mouse" to "keyboard-mouse", "Controller" to "controller")
                ) {
                    inputMode = it
                    save("input_host", it)
                }
                RustOptionRow(
                    "Profil Rust", profile,
                    (0..4).map { "Profile " + it to it.toString() }
                ) {
                    profile = it
                    save("profile", it.toInt())
                }
                Text("Performa", style = MaterialTheme.typography.titleMedium)
                RustSwitchRow("Performance hints Android", performanceHints) {
                    performanceHints = it
                    save("performance_hints", it)
                }
                RustSwitchRow("GPU profiling", gpuProfile) {
                    gpuProfile = it
                    save("gpu_profile", it)
                }
                RustSwitchRow("Performance recording", recordPerformance) {
                    recordPerformance = it
                    save("record_performance", it)
                }
                RustOptionRow(
                    "Refresh rate", displayHz,
                    listOf("System default" to "0", "30 Hz" to "30", "60 Hz" to "60", "90 Hz" to "90",
                        "120 Hz" to "120", "144 Hz" to "144", "165 Hz" to "165")
                ) {
                    displayHz = it
                    save("display_hz", it.toInt())
                }
                RustOptionRow(
                    "Orientasi game", orientation,
                    listOf("Landscape" to "landscape", "Portrait" to "portrait",
                        "Reverse landscape" to "reverse-landscape", "Reverse portrait" to "reverse-portrait",
                        "Otomatis" to "auto")
                ) {
                    orientation = it
                    save("orientation", it)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Akun mengikuti ZaRust", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Rust Edition otomatis memakai akun aktif dari menu Accounts ZaRust saat game dijalankan. Tidak perlu login Microsoft lagi di Horizon.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Untuk mengganti akun, buka Accounts di ZaRust. Rust Edition akan menyinkronkan akun yang dipilih berikutnya.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Profil & Multiplayer", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = playerName,
                    onValueChange = { playerName = it; save("name", it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nama offline") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it; save("server", it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Server otomatis (opsional)") },
                    singleLine = true
                )
            }
        }
    }
}

@Composable
private fun RustSwitchRow(title: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}

@Composable
private fun RustOptionRow(
    title: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit
) {
    var expanded by remember(title) { mutableStateOf(false) }
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { expanded = true }) {
            Text(options.firstOrNull { it.second == selected }?.first ?: selected)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (label, value) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    expanded = false
                    onSelected(value)
                })
            }
        }
    }
}

private fun formatMiB(bytes: Long): String =
    String.format(java.util.Locale.ROOT, "%.1f", bytes / 1048576.0)
