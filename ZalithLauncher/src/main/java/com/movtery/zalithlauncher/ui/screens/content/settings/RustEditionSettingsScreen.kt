package com.movtery.zalithlauncher.ui.screens.content.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.movtery.zalithlauncher.game.account.AccountsManager
import dev.horizon.android.RustEditionBridge
import dev.horizon.android.RustNativeBuild
import dev.horizon.android.RustNativeBuildManager
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import org.json.JSONObject
import kotlinx.coroutines.launch

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
        "Vanilla-style game settings: FOV, render distance, FPS, audio, language, chat, mouse sensitivity, and key bindings. Use key_<name>:key.keyboard.<key> or key.mouse.<button> entries."
    ),
    RustConfigDocument(
        "Horizon Graphics",
        "horizon-graphics.txt",
        "Horizon renderer settings: LOD distance, culling, chunk upload budget, worker threads, resolution scale, FXAA, battery mode, frame pacing, FPS overlay, and graphics API.",

    ),
    RustConfigDocument(
        "Controller & On-screen Keyboard",
        "horizon-controller.txt",
        "Horizon gamepad settings: enable, vibration, adaptive triggers, deadzone, sensitivity, button glyphs, menu navigation, and virtual keyboard. The input mode selected above controls the on-screen layout.",

    ),
    RustConfigDocument(
        "Horizon Extras",
        "horizon-extras.txt",
        "Horizon extras: fullbright, dynamic lights, zoom, FPS counter, keystrokes/CPS, minimap, auto-reconnect, mouse tweaks, and item sorting.",

    ),
    RustConfigDocument(
        "Horizon Mods",
        "horizon-mods.txt",
        "Enable mods installed in the horizon-mods folder. Add one mod per line using mod.<id>:true. Import .hmod files from the Mod Manager section.",
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
    val coroutineScope = rememberCoroutineScope()
    val nativeAppVersion = remember { RustNativeBuildManager.appVersion(context) }
    var nativeBuilds by remember { mutableStateOf<List<RustNativeBuild>>(emptyList()) }
    var nativeBuildLoading by remember { mutableStateOf(false) }
    var nativeBuildInstalling by remember { mutableStateOf<String?>(null) }
    var nativeBuildProgress by remember { mutableStateOf(0f) }
    var nativeBuildMessage by remember { mutableStateOf<String?>(null) }
    var activeNativeBuild by remember { mutableStateOf(RustNativeBuildManager.currentBuild(context)) }

    val gameHome = RustEditionBridge.gameHome(context)
    val modsDir = RustEditionBridge.modsDirectory(context)
    val shadersDir = RustEditionBridge.shaderPacksDirectory(context)
    val resourcePacksDir = File(gameHome, "resourcepacks")
    val nativeDir = RustEditionBridge.nativeModsDirectory(context)
    val hnmodImportDir = File(context.filesDir, "rust-edition/imported-hnmod")
    val selected = rustConfigDocuments[selectedDocument]

    LaunchedEffect(selectedDocument, gameHome.absolutePath) {
        editorText = readRustConfig(gameHome, selected)
    }

    fun refreshNativeBuilds() {
        coroutineScope.launch {
            nativeBuildLoading = true
            val result = runCatching { RustNativeBuildManager.list(context) }
            result.onSuccess {
                nativeBuilds = it
                nativeBuildMessage = if (it.isEmpty()) {
                    "No native builds have been published for ZaRust $nativeAppVersion yet."
                } else {
                    "Found ${it.size} build(s) compatible with ZaRust $nativeAppVersion."
                }
            }.onFailure {
                nativeBuildMessage = it.message ?: "Could not load the native build list."
            }
            nativeBuildLoading = false
        }
    }

    LaunchedEffect(nativeAppVersion) {
        refreshNativeBuilds()
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
                "Unsupported file type. Allowed extensions: " + accepted.joinToString(", ")
            }
            require(destination.isDirectory || destination.mkdirs()) {
                "Could not create folder: ${destination.absolutePath}"
            }
            val target = File(destination, name)
            val temporary = File(destination, name + ".partial")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Could not read the selected file." }
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (target.exists() && !target.delete()) {
                throw IllegalStateException("Could not replace the existing file: $name")
            }
            check(temporary.renameTo(target)) { "Could not save $name." }
            pickerMessage = "Successfully installed $name."
        }.onFailure {
            pickerMessage = it.message ?: "Could not import the selected file."
        }
    }

    val hmodPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        copySelected(uri, modsDir, setOf("hmod"))
    }

    val hnmodPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                require(hnmodImportDir.isDirectory || hnmodImportDir.mkdirs()) {
                    "Could not create the native mod import folder."
                }
                val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                } ?: "native-mod.hnmod"
                require(displayName.endsWith(".hnmod", ignoreCase = true)) {
                    "Select a file with the .hnmod extension."
                }
                val temporary = File(hnmodImportDir, displayName + ".partial")
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Could not read the selected file." }
                    FileOutputStream(temporary).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                ZipFile(temporary).use { archive ->
                    val entries = archive.entries()
                    var count = 0
                    var totalExpanded = 0L
                    var manifestText: String? = null
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        count++
                        require(count <= 5000) { "The archive contains too many files." }
                        require(!entry.name.startsWith("/") && !entry.name.split('/').contains("..")) {
                            "Archive contains an unsafe file path."
                        }
                        if (entry.size > 0) totalExpanded += entry.size
                        require(totalExpanded <= 128L * 1024L * 1024L) {
                            "The expanded archive is too large."
                        }
                        if (entry.name == "horizon-native.json") {
                            require(entry.size in 1L..262144L) { "The native mod manifest is invalid." }
                            manifestText = archive.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                        }
                    }
                    val manifest = JSONObject(requireNotNull(manifestText) {
                        "horizon-native.json manifest was not found."
                    })
                    val modId = manifest.optString("id")
                    require(modId.matches(Regex("[a-z0-9][a-z0-9_-]{0,40}"))) {
                        "The mod ID in the manifest is invalid."
                    }
                    require(manifest.optString("version").isNotBlank()) {
                        "The mod version is missing from the manifest."
                    }
                    require(archive.getEntry("mixins/$modId/horizon-mixin.toml") != null) {
                        "Mixin source was not found at mixins/$modId/horizon-mixin.toml."
                    }
                }
                val target = File(hnmodImportDir, displayName)
                if (target.exists() && !target.delete()) error("Could not replace the existing file.")
                check(temporary.renameTo(target)) { "Could not save the .hnmod package." }
                pickerMessage = "Imported $displayName. The package is saved and waiting for a native build."
            }.onFailure {
                pickerMessage = it.message ?: "Could not import the .hnmod package."
            }
        }
    }
    val shaderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        copySelected(uri, shadersDir, setOf("hshader", "zip"))
    }
    val resourcePackPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        copySelected(uri, resourcePacksDir, setOf("zip"))
    }

    // Starling's accounts::files_root() stores Mojang files in private_home()/minecraft,
    // separate from game_home() (filesDir/rust-edition).
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
            "Manage Rust Edition from ZaRust settings. Rust Edition uses your active ZaRust account; a second sign-in screen is not required.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Game Status", style = MaterialTheme.typography.titleMedium)
                Text(if (gameFilesReady) "Game files are installed." else "Game files were not found. Press Play Rust to set up the official game files.")
                Text("Account: " + (account?.let { "${it.username} · ${it.accountType ?: "Local"}" } ?: "Offline"))
                Text("Game folder: ${gameHome.absolutePath}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Input & Controls", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (inputMode == "zalith") {
                        "ZaRust Controls uses the layout currently selected in Control Manager. Before launch, ZaRust exports its button positions and key bindings to the Rust engine and disables every unmapped built-in control. Only the mapped layout is active in-game."
                    } else {
                        "Starling Controls restores the saved Starling touch layout and its built-in controls."
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = inputMode == "zalith",
                        onClick = {
                            inputMode = "zalith"
                            RustEditionBridge.setInputMode(context, inputMode)
                        },
                        label = { Text("ZaRust Controls") }
                    )
                    FilterChip(
                        selected = inputMode == "starling",
                        onClick = {
                            inputMode = "starling"
                            RustEditionBridge.setInputMode(context, inputMode)
                        },
                        label = { Text("Starling Controls") }
                    )
                }
                Text(
                    "Physical keyboard and mouse input continue to use the game's key bindings in options.txt. Horizon virtual keyboard and gamepad options are available in the Controller & On-screen Keyboard tab.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Horizon Settings", style = MaterialTheme.typography.titleMedium)
                Text("Horizon configuration files are managed here. Select a category, edit its values, and save before pressing Play Rust.")
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
                    label = { Text("Edit ${selected.fileName}") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        pickerMessage = runCatching {
                            writeRustConfig(gameHome, selected, editorText)
                            "Saved: ${selected.fileName}"
                        }.getOrElse { "Could not save: ${it.message}" }
                    }) {
                        Text("Save Settings")
                    }
                    TextButton(onClick = {
                        editorText = readRustConfig(gameHome, selected)
                        pickerMessage = "Editor changes discarded."
                    }) {
                        Text("Reload")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mod Manager · HMOD", style = MaterialTheme.typography.titleMedium)
                Text("Import .hmod files into the Horizon mods folder. Mod enable/disable states are stored in horizon-mods.txt.")
                Text(modsDir.absolutePath, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { hmodPicker.launch(arrayOf("*/*")) }) { Text("Import HMOD") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shader Manager · HShader", style = MaterialTheme.typography.titleMedium)
                Text("Import .hshader files or ZIP shader packs into the shaderpacks folder.")
                Text(shadersDir.absolutePath, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { shaderPicker.launch(arrayOf("*/*")) }) { Text("Import Shader") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Resource Pack Manager", style = MaterialTheme.typography.titleMedium)
                Text("Import ZIP resource packs into the Rust Edition game folder. The active pack list remains controlled by resourcePacks in options.txt.")
                Text(resourcePacksDir.absolutePath, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { resourcePackPicker.launch(arrayOf("*/*")) }) { Text("Import Resource Pack") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Native Mods · HNMOD", style = MaterialTheme.typography.titleMedium)
                Text("Import a Horizon native source package (.hnmod). ZaRust validates its manifest and stores the package separately from the runtime library.")
                Text("Saved packages: ${hnmodImportDir.listFiles()?.count { it.isFile && it.extension.equals("hnmod", true) } ?: 0}")
                Text(hnmodImportDir.absolutePath, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { hnmodPicker.launch(arrayOf("*/*")) }) { Text("Import .HNMOD") }
                Text(
                    "Imported packages are stored on this device. To compile one in the cloud, its bundle must first be reviewed and committed under the repository's native-mods folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Native runtime folder: ${nativeDir.absolutePath}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Native Build Manager · Android ARM64", style = MaterialTheme.typography.titleMedium)
                Text(
                    "This workflow compiles only the ARM64 native library (.so). It does not build or replace the ZaRust APK. The output is published to the native-builds release and can be installed from this screen.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Build steps: open the workflow, choose Run workflow, keep the app version at $nativeAppVersion, and enter an approved mod ID (the default is wrench). After the workflow succeeds, return here, tap Refresh Builds, then Download & Activate.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Target version: $nativeAppVersion")
                Text(
                    activeNativeBuild?.let { "Custom native build active for the next launch: $it" }
                        ?: "No custom native build is active. The built-in Rust runtime bundled in the ZaRust APK will be used."
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { refreshNativeBuilds() }, enabled = !nativeBuildLoading) {
                        Text(if (nativeBuildLoading) "Loading..." else "Refresh Builds")
                    }
                    TextButton(onClick = {
                        val uri = Uri.parse("https://github.com/fathily/ZaRust-Launcher/actions/workflows/rust-edition-native-mods.yml")
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }) {
                        Text("Open Native Build Workflow")
                    }
                }
                if (nativeBuildLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                nativeBuilds.forEach { build ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(build.label.ifBlank { build.name }, style = MaterialTheme.typography.titleSmall)
                            Text(build.name, style = MaterialTheme.typography.bodySmall)
                            Button(
                                enabled = nativeBuildInstalling == null &&
                                    activeNativeBuild != build.name,
                                onClick = {
                                    coroutineScope.launch {
                                        nativeBuildInstalling = build.name
                                        nativeBuildProgress = 0f
                                        nativeBuildMessage = "Downloading and verifying ${build.label}..."
                                        val result = runCatching {
                                            RustNativeBuildManager.install(context, build) { done, total ->
                                                coroutineScope.launch {
                                                    nativeBuildProgress =
                                                        if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f)
                                                        else 0f
                                                }
                                            }
                                        }
                                        result.onSuccess {
                                            activeNativeBuild = it
                                            nativeBuildMessage = "Native build installed. Restart Rust Edition to use it."
                                        }.onFailure {
                                            nativeBuildMessage = it.message ?: "Could not install the native build."
                                        }
                                        nativeBuildInstalling = null
                                    }
                                }
                            ) {
                                Text(if (nativeBuildInstalling == build.name) "Downloading..." else "Download & Activate")
                            }
                            if (nativeBuildInstalling == build.name) {
                                LinearProgressIndicator(
                                    progress = nativeBuildProgress,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
                if (activeNativeBuild != null) {
                    TextButton(onClick = {
                        RustNativeBuildManager.disable(context)
                        activeNativeBuild = null
                        nativeBuildMessage = "Custom native build disabled. Rust Edition will use the built-in runtime bundled in the ZaRust APK on the next launch."
                    }) {
                        Text("Use Built-in Runtime")
                    }
                }
                nativeBuildMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Security: native libraries are executable code with full game access. Only activate builds you trust. Downloaded files are verified with SHA-256 before use.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        pickerMessage?.let {
            Text(
                it,
                color = if (it.startsWith("Saved") || it.startsWith("Successfully") || it.startsWith("Imported")) MaterialTheme.colorScheme.primary
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
    require(home.isDirectory || home.mkdirs()) { "Could not create the game folder: ${home.absolutePath}" }
    val target = File(home, document.fileName)
    val temporary = File(home, document.fileName + ".partial")
    FileOutputStream(temporary).use { output ->
        output.write(content.toByteArray(Charsets.UTF_8))
        output.fd.sync()
    }
    if (target.exists() && !target.delete()) {
        temporary.delete()
        error("Could not replace ${target.name}.")
    }
    check(temporary.renameTo(target)) {
        temporary.delete()
        "Could not save ${target.name}."
    }
}
