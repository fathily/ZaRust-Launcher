# ZaRust Rust Edition

ZaRust is one Android app with two game paths:

- **Java Edition** continues to use the existing Zalith/Pojav launcher.
- **Rust Edition** embeds the Android-compatible Starling engine, which runs the Horizon Rust game engine. ZaRust remains the launcher UI; Starling's native activity is used as the game surface, not as a second launcher app.

## Upstream and version tracking

The Android build checks out [JuanLuci/Starling](https://github.com/JuanLuci/starling) at the pinned Android-compatible revision 6ce7ca2b3043ec1c347ae88cb907890dee3f0186. This tracks Starling Android 0.2.2 (`6ce7ca2`), including the latest Android-side fixes available on 2026-11-07.

[Horizon Rust](https://github.com/horizon-rust/horizon) is the upstream engine repository. Its current mainline is not a drop-in replacement for the Android port: Android activity glue, JNI, storage setup, and Android build packaging must remain compatible. Mainline changes therefore need to be reviewed and ported through the Android branch rather than blindly replacing the Starling source. The [Vasysik Horizon Android repository](https://github.com/Vasysik/horizon-android) remains a related reference implementation.

## Mods and local native builds

- `.hmod`: content/data mod package. ZaRust imports it into the Rust Edition mod folder.
- `.hshader`: Horizon shader package, stored in the Rust Edition shaderpacks folder.
- `.hnmod`: Horizon native source bundle. ZaRust validates the ZIP path structure, `horizon-native.json`, the mod ID, schema, and pinned Horizon Mixins revision before it is eligible for local compilation.

### ZaRust Native Builder

The on-device builder is designed to compile `libhorizon_android.so` on the Android device itself. It does not use GitHub Actions, a cloud build worker, a second launcher app, or the Termux app.

1. Open **Settings → Rust Edition → ZaRust Native Builder**.
2. Prepare the private build tools. ZaRust installs an ARM64 userspace, Rust/Cargo, Clang/LLD, and Android NDK sysroot into its own app-private storage. A small PRoot host runner is bundled into the ZaRust APK at app build time so the compiler can run inside Android's app sandbox.
3. Enter a Starling base reference: the default is the pinned Android-compatible commit `1d45d81bad530e20cfa18e9b47b1d7247b469ea4`. A branch, tag, or full commit SHA can be selected; ZaRust resolves it to a fixed commit before building.
4. Import one or more `.hnmod` packages in the **Native Mods · HNMOD** section.
5. Press **Build Native Here**. ZaRust starts from a clean copy of the selected Starling commit, applies the imported native mixin bundles, and compiles the ARM64 library locally.
6. The resulting library and JSON manifest are checked for app version, ABI, ELF machine type, file size, and SHA-256 before the runtime selector is updated.
7. Restart Rust Edition to load the custom build. Use **Use Built-in Runtime** to return to the library bundled in the installed ZaRust APK.

Each build begins from the selected Starling base, not from a previous modded source tree. The toolchain and package caches remain in ZaRust's private `files/native-builder/` directory. Temporary source snapshots and Cargo target output are removed after a successful build; a later rebuild downloads the selected Starling source again.

### Device requirements and current limitations

- ARM64 Android devices only. The PRoot host runner is packaged for `arm64-v8a`.
- The first toolchain setup requires at least 3 GB free before installation. After setup and source download, the builder checks free space dynamically (at least 3 GB, adjusted for the source size) before compiling. It removes temporary patched workspaces, Node modules, staged mods, and Cargo target output after the build instead of retaining multiple full source/build copies.
- An internet connection is required for the first setup and for downloading a Starling source revision and Cargo dependencies. Source retrieval happens inside ZaRust; it does not open a GitHub page or start Actions. Previously downloaded sources and build dependencies are cached locally.
- Native compilation can be memory-intensive and may take a long time on mobile hardware. The default Cargo job count is deliberately limited.
- A downloaded `.hnmod` remains inactive until the local native compile succeeds and its resulting library passes validation.
- Native mod code is executable code inside the game process and is not sandboxed. Only build source packages you trust.
- This builder is an experimental Android port. The PRoot host binary and APK packaging need a successful CI build, and the first end-to-end native compile plus runtime launch still need physical-device validation. Do not treat the feature as release-ready until those checks pass.

Java `.jar`, Fabric, Forge, NeoForge, Iris, and Sodium mods are not automatically compatible with Horizon.

## Accounts, storage, and controls

ZaRust passes the selected profile to the embedded engine. Rust Edition uses its own app-private data/runtime storage and does not reuse Java Edition's `versions/`, `mods/`, or Java runtime directories.

When **ZaRust Controls** is selected, the launcher reads the selected Control Manager layout and exports its mapped positions and key bindings to the Rust engine's `touch-layout.json` before launch. Every unmapped built-in touch action is written as off, and the adapter refuses to launch if it cannot find a valid movement control instead of silently using Starling's default stick. The game surface is still a native Rust activity, so the native engine renders and receives those mapped controls; this is not the launcher's Compose overlay being drawn over a separate Java game surface. Physical-device testing is still required for each control layout and device aspect ratio.

## Build the ZaRust APK

The standard Android APK build still compiles and packages the embedded Starling engine into ZaRust. The on-device builder's PRoot host runner is compiled for `arm64-v8a` and packaged in the APK's `jniLibs/arm64-v8a` when Rust Edition is enabled. This is an APK packaging prerequisite only; after the app is installed, the user compiles custom native libraries directly on the device. The ordinary Java Edition build remains separate.

## Credits and licenses

- [Zalith Launcher 2](https://github.com/ZalithLauncher/ZalithLauncher2)
- [Horizon Rust](https://github.com/horizon-rust/horizon) — MIT
- [Starling](https://github.com/JuanLuci/starling) — see its bundled license and notices
- [Vasysik Horizon Android](https://github.com/Vasysik/horizon-android)

The app includes upstream license/third-party notices for components that are staged into the APK. See the repository's [LICENSE](./LICENSE).
