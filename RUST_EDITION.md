# ZaRust Rust Edition

ZaRust Launcher contains two independent game launch paths in one Android application:

- **Java Edition** remains the existing Zalith/Pojav-based launcher. Its Java runtimes, versions, mods, shader packs, worlds, and settings are not replaced by Rust Edition.
- **Rust Edition** appears in ZaRust's Game → Versions list and starts the Horizon Minecraft client directly in a native game activity inside the same ZaRust APK. It does not install a second launcher app or show Horizon's launcher home during normal launches. First-run resource installation runs before the game starts. Rust has its own account/profile settings, game resources, worlds, native mods, and cache.

## Engine and compatibility

Rust Edition is built from the pinned [Horizon Android](https://github.com/Vasysik/horizon-android) source revision `7301f6fdd7f470b11317276e7a11b4dc615c137a`. The engine targets its pinned Minecraft Java 26.3 implementation; it is not a general-purpose launcher for arbitrary Minecraft Java versions.

The initial integrated build targets **arm64-v8a** and **Android 9 / API 28 or newer**. Other Java Edition ABI builds remain available through the existing workflow. The Rust version entry is only shown when the Rust-enabled arm64 build is installed on a compatible device.

Horizon uses native Rust and does not use the Java Edition JRE. Its native `.hmod` mods are a separate format. Fabric, Forge, NeoForge, Quilt, Java `.jar` mods, Iris, and Sodium do not become compatible by copying them into Rust Edition.

## Storage isolation

The integration patches Horizon's Android data and cache roots into the app-private `rust-edition/` directory. The Rust game files therefore do not share Java Edition's `versions/`, `mods/`, `saves/`, or runtime directories.

The Rust native engine is staged only during the dedicated workflow. The upstream engine source is checked out at the pinned revision during CI; its binary and generated engine data are not committed to this repository.

## Build

Run **Actions → Build ZaRust with Rust Edition → Run workflow** and choose Debug or Release. This first integration workflow builds arm64-v8a only, prepares Horizon's pinned resource tables, compiles the native library, stages its public engine data, and then builds the combined ZaRust APK with the Rust Edition feature flag.

The ordinary **Build APK** workflow is unchanged and continues to produce Java Edition builds without bundling the Rust native engine.

## Upstream credits and licenses

Rust Edition is based on [Horizon Android](https://github.com/Vasysik/horizon-android), copyright © 2026 Ludovic Marie and Horizon contributors, under the MIT License. The integrated APK includes the Horizon MIT license and its third-party notices under `assets/rust-edition/`. The rest of ZaRust continues to follow the licenses of its respective upstream components.
