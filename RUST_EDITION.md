# ZaRust Rust Edition

ZaRust Launcher contains two independent game launch paths in one Android application:

- **Java Edition** remains the existing Zalith/Pojav-based launcher. Its Java runtimes, versions, mods, shader packs, worlds, and settings are not replaced by Rust Edition.
- **Rust Edition** appears in ZaRust's Game → Versions list. Its resource installer, progress UI, and engine settings live in ZaRust's Jetpack Compose screens. Tapping Play starts only the native Minecraft game surface in a private process of the same `com.zarust.launcher` application; it does not install or open a second launcher app, and Horizon's Play/Mods/Packs/Shaders/Settings launcher pages are bypassed. Rust data, profiles, worlds, native mods, and cache stay in the dedicated `rust-edition/` directory.

## Engine and compatibility

Rust Edition is built from the pinned [Horizon Android](https://github.com/Vasysik/horizon-android) source revision `7301f6fdd7f470b11317276e7a11b4dc615c137a`. The engine targets its pinned Minecraft Java 26.3 implementation; it is not a general-purpose launcher for arbitrary Minecraft Java versions.

The initial integrated build targets **arm64-v8a** and **Android 9 / API 28 or newer**. Other Java Edition ABI builds remain available through the existing workflow. The Rust version entry is only shown when the Rust-enabled arm64 build is installed on a compatible device.

Horizon uses native Rust and does not use the Java Edition JRE. Its native `.hmod` mods are a separate format. Fabric, Forge, NeoForge, Quilt, Java `.jar` mods, Iris, and Sodium do not become compatible by copying them into Rust Edition.

## ZaRust UI, settings, and accounts

`Settings → Rust Edition` stores the native engine settings in the app-private `rust-edition/android-launcher.json`. The Compose screen owns installation/progress, graphics API, render scale, fullscreen/aspect, input mode, orientation, refresh-rate/performance hints, profile selection, and optional auto-join server. There is no second editable Rust player-name field: the currently selected ZaRust account is the source of truth. A ZaRust Microsoft account's Minecraft access token and refresh token are synchronized over JNI into Horizon's Android Keystore-backed secret store before launch. A ZaRust Local/offline profile is passed to Horizon as the same offline player name. The account type is preserved; a Local account is not silently turned into a Microsoft account. Third-party auth-server accounts are rejected with an explanation rather than creating a second profile. The Java Edition settings and JVM configuration are left untouched.

The same screen has toggles for six official [Starling](https://github.com/JuanLuci/starling) Horizon mods: **FPS Counter**, **Keystrokes**, **Far View**, **Minimap**, **Zoom**, and **Recipes**. Their `.hmod` packages are compiled from Starling's pinned source and bundled into the same ZaRust APK, then installed under `rust-edition/horizon-mods/`. The toggles update Horizon's `horizon-mods.txt` and take effect on the next game launch. All six start disabled so the player chooses which features to use; Far View can increase render and memory load and is not a guaranteed FPS boost.

The NativeActivity is only the Vulkan/OpenGL game surface required by Horizon's Android/Winit runtime; it runs in the `:rustgame` process so closing the game returns to the still-running ZaRust launcher process.

## Storage isolation

The integration patches Horizon's Android data and cache roots into the app-private `rust-edition/` directory. The Rust game files therefore do not share Java Edition's `versions/`, `mods/`, `saves/`, or runtime directories.

The Rust native engine is staged only during the dedicated workflow. The upstream engine source is checked out at the pinned revision during CI; its binary and generated engine data are not committed to this repository.

## Build

Run **Actions → Build ZaRust with Rust Edition → Run workflow** and choose Debug or Release. This first integration workflow builds arm64-v8a only, prepares Horizon's pinned resource tables, compiles the native library, stages its public engine data, and then builds the combined ZaRust APK with the Rust Edition feature flag.

The ordinary **Build APK** workflow is unchanged and continues to produce Java Edition builds without bundling the Rust native engine.

## Upstream credits and licenses

Rust Edition uses [Horizon Android](https://github.com/Vasysik/horizon-android), copyright © 2026 Ludovic Marie and Horizon contributors, under the MIT License. It also bundles official `.hmod` mods from [JuanLuci/Starling](https://github.com/JuanLuci/starling), pinned to `1d53294cf27dbe676197fdd826669d6b7030d2e1`, under its MIT License. Both licenses and Horizon's third-party notices are included under `assets/rust-edition/`. The rest of ZaRust continues to follow the licenses of its respective upstream components.
