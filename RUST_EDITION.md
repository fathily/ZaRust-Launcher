# ZaRust Rust Edition

ZaRust is one Android app with two game paths:

- **Java Edition** continues to use the existing Zalith/Pojav launcher.
- **Rust Edition** embeds the Android-compatible Starling engine, which runs the Horizon Rust game engine. ZaRust remains the launcher UI; Starling's native activity is used as the game surface, not as a second launcher app.

## Upstream and version tracking

The Android build checks out [JuanLuci/Starling](https://github.com/JuanLuci/starling) at the pinned Android-compatible revision 1d45d81bad530e20cfa18e9b47b1d7247b469ea4. This advances the previous pin by four commits.

[Horizon Rust](https://github.com/horizon-rust/horizon) is the upstream engine repository. Its current mainline is not a drop-in replacement for the Android port: Android activity glue, JNI, storage setup, and Android build packaging must remain compatible. Mainline changes therefore need to be reviewed and ported through the Android branch rather than blindly replacing the Starling source. The [Vasysik Horizon Android repository](https://github.com/Vasysik/horizon-android) remains a related reference implementation.

## Mods

## Android native mod builds

The Android native-mod workflow is based on Starling's cloud build process. It applies reviewed Horizon mixin bundles to the pinned Starling Android source and compiles a matching `libhorizon_android.so` for **arm64-v8a**. A versioned `.so` and JSON manifest are published in the `native-builds` prerelease; ZaRust verifies the library's SHA-256 before selecting it for the next game launch.

Only reviewed bundles committed under `tools/rust-edition/native-mods/<id>/mixins/<id>/` can be built. The current bundled example is Wrench. A file imported from the device remains staged until its source is reviewed and committed to that approved folder. Native mods are not sandboxed.

- .hmod: content/data mod package. ZaRust imports it into the Rust Edition mod folder.
- .hnmod: native Rust source bundle. ZaRust validates its ZIP structure and horizon-native.json manifest, then stores it under the app-private rust-edition/imported-hnmod/ folder.

**Important:** .hnmod import is staging only at this point. It does not make a native mod active in the running Android engine. Horizon native mods must be built against matching Horizon and Horizon Mixins revisions and included in a compatible engine build. ZaRust does not yet perform that Android native-mod build dynamically. Do not treat a staged package as enabled.

Java .jar, Fabric, Forge, NeoForge, Iris, and Sodium mods are not automatically compatible with Horizon.

## Accounts, storage, and controls

ZaRust passes the selected profile to the embedded engine. Rust Edition uses its own app-private data/runtime storage and does not reuse Java Edition's versions/, mods/, or Java runtime directories.

When **ZaRust Controls** is selected, the launcher reads the selected Control Manager layout and exports its mapped positions and key bindings to the Rust engine's touch-layout.json before launch. Every unmapped built-in touch action is written as off, and the adapter refuses to launch if it cannot find a valid movement control instead of silently using Starling's default stick. The game surface is still a native Rust activity, so the native engine renders and receives those mapped controls; this is not the launcher's Compose overlay being drawn over a separate Java game surface. Physical-device testing is still required for each control layout and device aspect ratio.

## Build

### Build only the installable native library (no APK)

After the **Build ZaRust native Rust library** workflow is available on the default branch:

1. Open the repository's Actions tab and select **Build ZaRust native Rust library**.
2. Choose **Run workflow**.
3. Keep **ZaRust version** at the version installed in the app (currently 2.4.11).
4. Enter approved native mod IDs. The bundled example is `wrench`.
5. Run the workflow. It compiles only `libhorizon_android.so` for **arm64-v8a**, verifies the version/ABI/size/SHA-256 manifest, and publishes the library plus JSON manifest to the `native-builds` prerelease. It does **not** assemble Starling.apk or ZaRust.apk.
6. In ZaRust, open **Settings → Rust Edition → Native Build Manager**, tap **Refresh Builds**, then **Download & Activate**. Restart Rust Edition to load the selected library.
7. To return to the library bundled in the installed ZaRust APK, tap **Use Built-in Runtime**.

Only reviewed bundles committed under `tools/rust-edition/native-mods/<id>/mixins/<id>/` can be built. A device-imported .hnmod is staging only until reviewed and added to an approved bundle directory. Native mods are not sandboxed.

### Build the full ZaRust APK

The separate workflow **Build ZaRust with Starling Rust Edition** compiles the embedded engine and packages it into the ZaRust APK. Current Rust Edition target: **arm64-v8a**. This workflow is for app releases and is not needed when building only a custom native library. The ordinary Java Edition build remains separate.

## Credits and licenses

- [Zalith Launcher 2](https://github.com/ZalithLauncher/ZalithLauncher2)
- [Horizon Rust](https://github.com/horizon-rust/horizon) — MIT
- [Starling](https://github.com/JuanLuci/starling) — see its bundled license and notices
- [Vasysik Horizon Android](https://github.com/Vasysik/horizon-android)

The app includes upstream license/third-party notices for components that are staged into the APK. See the repository's [LICENSE](./LICENSE).
