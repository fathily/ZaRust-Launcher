# ZaRust Launcher

<p align="center">
  <b>One Android launcher. Two ways to play Minecraft: Java Edition.</b><br/>
  Zalith-based Java Edition + Horizon Rust Edition powered on Android by Starling.
</p>

<p align="center">
  <a href="https://github.com/fathily/ZaRust-Launcher/actions"><img src="https://img.shields.io/github/actions/workflow/status/fathily/ZaRust-Launcher/build.yml?label=Build%20CI" alt="Build CI"/></a>
  <a href="https://github.com/fathily/ZaRust-Launcher/blob/main/LICENSE"><img src="https://img.shields.io/badge/launcher-GPL--3.0-blue" alt="GPL-3.0"/></a>
  <a href="https://github.com/JuanLuci/starling/blob/android/LICENSE"><img src="https://img.shields.io/badge/Starling-MIT-green" alt="Starling MIT"/></a>
</p>

> **Unofficial community modification.** ZaRust Launcher is maintained and modified by **AlfaaBEJIRR**. It is not an official product of Zalith Launcher, Horizon, Starling, Mojang, or Microsoft, and this project is not endorsed by those upstream projects.

## What is ZaRust?

ZaRust brings two game engines into one Android application and one launcher experience. The goal is to let players choose an edition without installing or switching between two separate launcher apps.

| Edition | Foundation | Role inside ZaRust |
| --- | --- | --- |
| **Java Edition** | Zalith Launcher 2 | Existing Java launcher, version management, Java runtimes, accounts, renderers, mods, controls, and game launch flow. |
| **Rust Edition** | Horizon Rust engine + Starling Android port | Native Rust-based game engine integrated into ZaRust. Starling supplies the Android port/platform integration; ZaRust provides the surrounding launcher entry, settings integration, and account hand-off. |

**Important distinction:** Starling is not a ZaRust-owned engine. It is a separate upstream Android project by JuanLuci, based on Horizon. ZaRust embeds/adapts the Android engine so both editions can be managed from the ZaRust app. Credit and upstream links are provided below.

## Project goals

- Keep both editions inside the same `com.zarust.launcher` application.
- Preserve the existing Zalith-based Java Edition workflow.
- Manage Rust Edition from ZaRust screens rather than opening a second launcher UI.
- Reuse the selected ZaRust account where the supported account type allows it.
- Keep Rust Edition game data separate from Java Edition versions, mods, saves, and runtime files.
- Provide a choice of ZaRust/Zalith or Starling input handling where supported by the integrated build.

Some integration work is still under development. Features can vary by build and device; do not assume every option is production-ready just because it appears in Settings.

## Native mods

ZaRust includes a staging and review path for Horizon native `.hnmod` source bundles. A reviewed bundle can be built for Android `arm64-v8a` by the repository's native-build workflow and delivered as a version-matched native library. Imported files are **not** executed or built automatically. Only reviewed bundles committed to the approved repository set are eligible for the cloud build.

Native code runs inside the game process and can access game memory and files. Review source carefully before approving a native mod. Java `.jar`, Fabric, Forge, NeoForge, Iris, and Sodium mods are not automatically compatible with the Rust Edition.

## Upstream projects and credits

This project combines and adapts work from separate open-source projects. Their maintainers retain credit for their own work; ZaRust does not claim authorship of upstream code.

| Project / contributor | Contribution | Source |
| --- | --- | --- |
| **AlfaaBEJIRR** | ZaRust maintainer, integration, modifications, launcher UI and project packaging | [ZaRust repository](https://github.com/fathily/ZaRust-Launcher) · [TikTok @alfathgpp](https://www.tiktok.com/@alfathgpp) |
| **Zalith Launcher 2** and contributors | Original Android launcher foundation used by the Java Edition | [Upstream repository](https://github.com/ZalithLauncher/ZalithLauncher2) |
| **JuanLuci** | Maintainer of the Starling Android port used to provide Android support for the Rust engine | [Starling Android repository](https://github.com/JuanLuci/starling) |
| **Ludovic Marie and Horizon contributors** | Horizon Rust game engine on which Starling is based | [Horizon repository](https://github.com/ludovic111/horizon) |
| **Vasysik** | Horizon Android implementation/reference used during integration research | [Horizon Android repository](https://github.com/Vasysik/horizon-android) |

### Upstream license notices

- ZaRust's launcher code follows the GNU General Public License v3.0. See [LICENSE](./LICENSE).
- Starling is published under the MIT License. Its upstream copyright and license notice must be retained with redistributed copies or substantial portions. See [Starling LICENSE](https://github.com/JuanLuci/starling/blob/android/LICENSE).
- Horizon and other bundled dependencies retain their own licenses and notices. Check their upstream repositories before redistributing modified builds.
- Minecraft is proprietary software. ZaRust, Starling, and Horizon are unofficial projects and do not include or grant a Minecraft license. Use game files from a copy of Minecraft you are entitled to use.

## Builds and development status

- General launcher builds: [GitHub Actions](https://github.com/fathily/ZaRust-Launcher/actions).
- Combined Rust Edition build: the dedicated Starling + ZaRust workflow targets Android `arm64-v8a`.
- Source branch for the current integration work: `feature/rust-edition-horizon`.
- Report reproducible issues with the affected device, Android version, ABI, build/run link, and relevant log. Do not report an integration bug to an upstream maintainer unless it can also be reproduced in that upstream project without ZaRust changes.

This repository is an active community modification. Treat test builds as experimental until the relevant CI checks and real-device gameplay tests pass.

## Maintainer

**AlfaaBEJIRR** — ZaRust maintainer and integrator.

- TikTok: [@alfathgpp](https://www.tiktok.com/@alfathgpp)
- Repository: [fathily/ZaRust-Launcher](https://github.com/fathily/ZaRust-Launcher)

