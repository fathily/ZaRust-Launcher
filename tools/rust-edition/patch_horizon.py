#!/usr/bin/env python3
"""Apply the small ZaRust integration patch to the pinned Horizon source tree."""
from pathlib import Path

root = Path(__file__).resolve().parents[2] / "HorizonEngine"
main = root / "crates/horizon-client/src/android_main.rs"
platform = root / "crates/horizon-client/src/android/platform.rs"
launcher = root / "crates/horizon-client/src/android/launcher.rs"

def replace_once(path: Path, old: str, new: str) -> None:
    source = path.read_text(encoding="utf-8")
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one patch anchor in {path}: found {count}: {old!r}")
    path.write_text(source.replace(old, new), encoding="utf-8")

replace_once(
    main,
    "app.borrow().as_ref().and_then(|a| a.internal_data_path())",
    'app.borrow().as_ref().and_then(|a| a.internal_data_path().map(|path| path.join("rust-edition")))',
)
replace_once(
    main,
    "let home = APP.with(|app| app.borrow().as_ref().unwrap().internal_data_path().unwrap());",
    'let home = APP.with(|app| app.borrow().as_ref().unwrap().internal_data_path().unwrap().join("rust-edition"));',
)
replace_once(
    main,
    "let Some(home) = app.internal_data_path() else {",
    'let Some(home) = app.internal_data_path().map(|path| path.join("rust-edition")) else {',
)
replace_once(
    platform,
    "        Ok(std::path::PathBuf::from(text))\n    })\n}\n\npub fn open_repository()",
    '        Ok(std::path::PathBuf::from(text).join("rust-edition"))\n    })\n}\n\npub fn open_repository()',
)

# Start directly in the Minecraft client when ZaRust launches the Rust version.
# The marker is created by HorizonActivity in the isolated app-private data folder.
replace_once(
    launcher,
    "pub(super) struct Launcher {\n    home: PathBuf,",
    "pub(super) struct Launcher {\n    home: PathBuf,\n    launch_from_zarust: bool,\n    auto_launch_pending: bool,",
)
replace_once(
    launcher,
    "    pub(super) fn new(home: PathBuf) -> Self {\n        // Upgrade existing installations",
    "    pub(super) fn new(home: PathBuf) -> Self {\n        let launch_marker = home.join(\"launch-from-zarust\");\n        let launch_from_zarust = launch_marker.is_file();\n        let _ = std::fs::remove_file(&launch_marker);\n        // Upgrade existing installations",
)
replace_once(
    launcher,
    "        Self {\n            home,\n            controller_edges:",
    "        Self {\n            home,\n            launch_from_zarust,\n            auto_launch_pending: launch_from_zarust,\n            controller_edges:",
)
replace_once(
    launcher,
    "            let game = match game::android_game() {",
    "            self.auto_launch_pending = false;\n            let game = match game::android_game() {",
)
replace_once(
    launcher,
    "        if let Err(error) = self.open(events) {\n            self.fail(error);\n            events.exit();\n        }\n    }\n    fn suspended",
    "        if let Err(error) = self.open(events) {\n            self.fail(error);\n            events.exit();\n        } else if self.auto_launch_pending {\n            if let Err(error) = self.action(events) {\n                self.fail(error);\n            }\n        }\n    }\n    fn suspended",
)
replace_once(
    launcher,
    "            if let Err(error) = result {\n                let message = error.to_string();",
    "            match result {\n                Ok(()) if self.auto_launch_pending => {\n                    if let Err(error) = self.action(events) { self.fail(error); }\n                }\n                Ok(()) => {}\n                Err(error) => {\n                let message = error.to_string();",
)
replace_once(
    launcher,
    "                }\n            }\n        }\n        for out in crate::platform::poll() {",
    "                }\n                }\n            }\n        }\n        for out in crate::platform::poll() {",
)
replace_once(
    launcher,
    "        if let Some(mut game) = self.game.take() {\n            game.exiting(events);\n            drop(game);\n        }\n        crate::performance::stop();",
    "        if let Some(mut game) = self.game.take() {\n            game.exiting(events);\n            drop(game);\n        }\n        if self.launch_from_zarust {\n            crate::performance::stop();\n            crate::platform::hide();\n            horizon_client::auth::clear_current();\n            crate::diagnostics::heartbeat(\"returned_to_zarust\", || serde_json::json!({}));\n            events.exit();\n            return;\n        }\n        crate::performance::stop();",
)

print("Horizon storage isolation and direct-launch patches applied.")
