package com.movtery.zalithlauncher.ui.screens.content.settings

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.movtery.zalithlauncher.game.account.AccountsManager
import dev.horizon.android.RustEditionBridge
import dev.horizon.android.ZaRustNativeBuildEnvironment
import dev.horizon.android.ZaRustOnDeviceNativeBuilder
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
    val bundledBootstrapAvailable = remember {
        runCatching {
            context.assets.open("rust-builder/bootstrap-aarch64.zip").use { it.read() >= 0 }
        }.getOrDefault(false)
    }
    val gameHome = RustEditionBridge.gameHome(context)
    val modsDir = RustEditionBridge.modsDirectory(context).apply { mkdirs() }
    val shadersDir = RustEditionBridge.shaderPacksDirectory(context).apply { mkdirs() }
    val resourcePacksDir = File(gameHome, "resourcepacks").apply { mkdirs() }
    val hnmodImportDir = File(context.filesDir, "rust-edition/imported-hnmod").apply { mkdirs() }
    val nativeDir = File(context.filesDir, "native").apply { mkdirs() }
    val account by AccountsManager.currentAccountFlow.collectAsStateWithLifecycle()
    var inputMode by remember { mutableStateOf(RustEditionBridge.getInputMode(context)) }
    var pickerMessage by remember { mutableStateOf<String?>(null) }
    var selectedDocument by remember { mutableIntStateOf(0) }
    val selected = rustConfigDocuments[selectedDocument]
    var editorText by remember {
        mutableStateOf(readRustConfig(RustEditionBridge.gameHome(context), rustConfigDocuments[0]))
    }
    val coroutineScope = rememberCoroutineScope()
    val nativeAppVersion = remember { RustNativeBuildManager.appVersion(context) }
    var starlingBase by remember {
        mutableStateOf(ZaRustOnDeviceNativeBuilder.DEFAULT_STARLING_COMMIT)
    }
    var environmentStatus by remember {
        mutableStateOf(ZaRustNativeBuildEnvironment.status(context))
    }
    var nativeBuilderPreparing by remember { mutableStateOf(false) }
    var nativeBuildRunning by remember { mutableStateOf(false) }
    var showNativeTrustDialog by remember { mutableStateOf(false) }
    var nativeBuilderMessage by remember { mutableStateOf<String?>(null) }
    var nativeBuilderLog by remember { mutableStateOf<List<String>>(emptyList()) }
    var activeNativeBuild by remember {
        mutableStateOf(RustNativeBuildManager.currentBuild(context))
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val freeStorageGiB = environmentStatus.freeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    val canPrepareNativeTools = environmentStatus.freeBytes >= 3L * 1024L * 1024L * 1024L
    val canBuildNativeHere = environmentStatus.freeBytes >= 5L * 1024L * 1024L * 1024L

    fun appendNativeLog(line: String) {
        mainHandler.post {
            nativeBuilderLog = (nativeBuilderLog + line).takeLast(80)
        }
    }

    fun prepareNativeBuilder() {
        coroutineScope.launch {
            nativeBuilderPreparing = true
            nativeBuilderMessage = null
            nativeBuilderLog = emptyList()
            val result = runCatching {
                ZaRustNativeBuildEnvironment.prepare(context, ::appendNativeLog)
            }
            result.onSuccess {
                environmentStatus = it
                nativeBuilderMessage = if (it.ready) {
                    "The on-device ARM64 build environment is ready."
                } else {
                    "The build environment is installed but did not pass every readiness check."
                }
            }.onFailure {
                environmentStatus = ZaRustNativeBuildEnvironment.status(context)
                nativeBuilderMessage = it.message ?: "Could not prepare the on-device build environment."
            }
            nativeBuilderPreparing = false
        }
    }

    fun buildNativeOnDevice() {
        coroutineScope.launch {
            nativeBuildRunning = true
            nativeBuilderMessage = null
            nativeBuilderLog = emptyList()
            val result = runCatching {
                ZaRustOnDeviceNativeBuilder.build(context, starlingBase, ::appendNativeLog)
            }
            result.onSuccess {
                activeNativeBuild = it.name
                environmentStatus = ZaRustNativeBuildEnvironment.status(context)
                nativeBuilderMessage =
                    "Local native build installed: ${it.name}. Restart Rust Edition to load it."
            }.onFailure {
                nativeBuilderMessage = it.message ?: "The on-device native build failed."
                environmentStatus = ZaRustNativeBuildEnvironment.status(context)
            }
            nativeBuildRunning = false
        }
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
                            RustEditionBridge.setInputMode(context, "zalith")
                        },
                        label = { Text("ZaRust Controls") }
                    )
                    FilterChip(
                        selected = inputMode == "starling",
                        onClick = {
                            inputMode = "starling"
                            RustEditionBridge.setInputMode(context, "starling")
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
                Text("Import Horizon native source packages (.hnmod). ZaRust validates the manifest and stages them for local native compilation.")
                Text("Saved packages: ${hnmodImportDir.listFiles()?.count { it.isFile && it.extension.equals("hnmod", true) } ?: 0}")
                Text(hnmodImportDir.absolutePath, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { hnmodPicker.launch(arrayOf("*/*")) }) { Text("Import .HNMOD") }
                Text(
                    "Imported packages are stored on this device. The Native Builder applies imported packages to the selected Starling base and compiles them locally on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Native runtime folder: ${nativeDir.absolutePath}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ZaRust Native Builder", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Compile the Horizon Rust engine on this device. No Termux app, separate launcher, GitHub Actions, or remote build worker is used. The private Android toolchain and PRoot runner are managed by ZaRust.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("ZaRust version: $nativeAppVersion")
                OutlinedTextField(
                    value = starlingBase,
                    onValueChange = { starlingBase = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Starling base version / commit") },
                    supportingText = {
                        Text("Default pinned base: ${ZaRustOnDeviceNativeBuilder.DEFAULT_STARLING_COMMIT}. You can enter a branch, tag, or 40-character commit SHA.")
                    }
                )
                Text(
                    "Each build starts from a clean Starling source snapshot. Imported HNMOD bundles are applied to that base before Cargo compiles the ARM64 library.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Imported HNMOD packages: ${ZaRustOnDeviceNativeBuilder.importedHnmods(context).size}")
                Text(
                    "Build environment: ${if (environmentStatus.ready) "Ready" else "Not ready"} · " +
                        "Free storage: ${java.lang.String.format(java.util.Locale.ROOT, "%.1f GB", freeStorageGiB)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "PRoot host runner: ${if (environmentStatus.nativeRunnerInstalled) "Bundled in this APK" else "Missing from this APK"} · " +
                        "Rust/Cargo: ${if (environmentStatus.rustInstalled) "Installed" else "Setup required"} · " +
                        "Rust host libraries: ${if (environmentStatus.rustStdInstalled) "Installed" else "Setup required"} · " +
                        "Android sysroot: ${if (environmentStatus.ndkSysrootInstalled) "Installed" else "Setup required"}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Android bootstrap archive: ${if (bundledBootstrapAvailable) "Bundled in this APK and SHA-256 verified during setup" else "Missing from this APK"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (bundledBootstrapAvailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { prepareNativeBuilder() },
                        enabled = !nativeBuilderPreparing && !nativeBuildRunning &&
                            environmentStatus.nativeRunnerInstalled && canPrepareNativeTools
                    ) {
                        Text(if (nativeBuilderPreparing) "Preparing…" else "Prepare Build Tools")
                    }
                    Button(
                        onClick = { showNativeTrustDialog = true },
                        enabled = !nativeBuilderPreparing && !nativeBuildRunning &&
                            environmentStatus.nativeRunnerInstalled && canBuildNativeHere &&
                            starlingBase.isNotBlank()
                    ) {
                        Text(if (nativeBuildRunning) "Building…" else "Build Native Here")
                    }
                }
                if (nativeBuilderPreparing || nativeBuildRunning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    activeNativeBuild?.let { "Custom native runtime selected: $it" }
                        ?: "Runtime selection: Built-in Rust library bundled in this ZaRust APK.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (activeNativeBuild != null) {
                    TextButton(
                        onClick = {
                            RustNativeBuildManager.disable(context)
                            activeNativeBuild = null
                            nativeBuilderMessage =
                                "Custom native runtime disabled. The built-in Rust library will be used on the next launch."
                        },
                        enabled = !nativeBuildRunning
                    ) {
                        Text("Use Built-in Runtime")
                    }
                }
                nativeBuilderMessage?.let {
                    Text(
                        it,
                        color = if (it.startsWith("Local native build installed") ||
                            it.startsWith("The on-device ARM64 build environment is ready.")
                        ) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (nativeBuilderLog.isNotEmpty()) {
                    Text("Build Log", style = MaterialTheme.typography.labelLarge)
                    Text(
                        nativeBuilderLog.takeLast(16).joinToString("\n"),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    "Storage requirements: Prepare Build Tools needs 3 GB free to install Rust/Cargo and the Android sysroot. Build Native Here needs 5 GB free working space for the source snapshot, Cargo build output, and temporary staging. The PRoot runner and verified bootstrap archive are bundled in this APK; the compiler packages are installed into ZaRust's private storage during preparation. Native HNMOD source is executable code; only build packages you trust.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        if (showNativeTrustDialog) {
            val importedNames = ZaRustOnDeviceNativeBuilder.importedHnmods(context)
                .joinToString { it.name }
                .ifBlank { "No HNMOD packages (base engine build only)" }
            AlertDialog(
                onDismissRequest = { showNativeTrustDialog = false },
                title = { Text("Trust and build native code?") },
                text = {
                    Text(
                        "The selected Starling source and these native packages will be compiled locally: " +
                            importedNames + ". HNMOD code and Cargo build scripts are executable code, " +
                            "are not sandboxed, and can access data available to ZaRust. Only continue " +
                            "if you have reviewed and trust every source. This build runs on this device."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showNativeTrustDialog = false
                            buildNativeOnDevice()
                        }
                    ) { Text("I trust these sources — Build") }
                },
                dismissButton = {
                    TextButton(onClick = { showNativeTrustDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
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
