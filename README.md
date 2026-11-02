# ZaRust Launcher

> **Unofficial community project.** ZaRust combines the Zalith Launcher Android experience with a Rust Edition powered by the Horizon engine and its Android port, Starling. It is not an official Zalith, Horizon, Mojang, or Microsoft product.

## What is ZaRust?

- **Java Edition:** keeps the existing Zalith/Pojav-based launcher and its Java game setup.
- **Rust Edition:** adds the Horizon Rust game engine inside the same ZaRust app. ZaRust owns the launcher screens, account hand-off, Rust settings, and mod import UI; the native engine supplies the in-game rendering surface.
- **Starling:** provides the Android integration layer used to run Horizon on Android. ZaRust embeds that Android engine instead of asking players to install a second launcher.

## Mods and packs

ZaRust supports .hmod content mods and imports .hnmod native-source bundles into a separate review/staging area. Reviewed native bundles can be built by GitHub Actions into an Android arm64-v8a engine library; ZaRust's Native Build Manager downloads the version-matched library, verifies its SHA-256, and selects it for the next Rust launch. Locally imported .hnmod files are not executed or built automatically: they must be reviewed and added to the repository's approved native-mod set first. Native mods run with full game access. Java .jar, Fabric, Forge, NeoForge, Iris, and Sodium mods are not automatically compatible.

## Upstream projects

- [Zalith Launcher 2](https://github.com/ZalithLauncher/ZalithLauncher2) — Android launcher foundation.
- [Horizon Rust](https://github.com/horizon-rust/horizon) — Rust Minecraft game engine and mod formats.
- [Starling Android](https://github.com/JuanLuci/starling) — Android port/integration used by ZaRust.
- [Horizon Android (Vasysik)](https://github.com/Vasysik/horizon-android) — related Android implementation and reference.

ZaRust tracks the Android-compatible Starling branch separately from Horizon's desktop/mainline repository. Mainline Horizon changes are reviewed for Android compatibility before they are included.

## Builds and updates

Use the [Actions](https://github.com/fathily/ZaRust-Launcher/actions) tab for CI builds. Rust Edition builds currently target **arm64-v8a**. Follow this repository or watch its Releases to receive updates about combined ZaRust APK builds and upstream compatibility notices.

## Credits

- Zalith Launcher 2 contributors
- Horizon Rust contributors
- JuanLuci and Starling contributors
- Vasysik and Horizon Android contributors

Maintained/customized by **AlfaaBEJIRR**.

TikTok: [@alfathgpp](https://tiktok.com/@alfathgpp)

## License

ZaRust follows the upstream Zalith Launcher license and the licenses of the bundled components. See [LICENSE](./LICENSE), the bundled third-party notices, and the upstream repositories for their respective terms.
