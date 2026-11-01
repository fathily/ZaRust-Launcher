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
    "        if self.auto_launch_pending {\n            if let Err(error) = self.action(events) {\n                self.fail(error);\n                events.exit();\n            }\n            return;\n        }\n        if let Err(error) = self.open(events) {\n            self.fail(error);\n            events.exit();\n        }\n    }\n    fn suspended",
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


# Expose the pinned Horizon resource installer to ZaRust's Jetpack Compose UI.
# It runs before NativeActivity starts, so the user never sees Horizon's installer UI.
replace_once(
    main,
    "    std::process::exit(0);\\n}",
    """    std::process::exit(0);
}

fn zarust_jni_string(
    env: &mut jni::JNIEnv<'_>,
    value: &jni::objects::JString<'_>,
) -> Result<String, String> {
    env.get_string(value).map(Into::into).map_err(|error| error.to_string())
}

#[unsafe(no_mangle)]
extern "system" fn Java_dev_horizon_android_RustEditionBridge_nativeIsReady(
    mut env: jni::JNIEnv<'_>,
    _class: jni::objects::JClass<'_>,
    home: jni::objects::JString<'_>,
) -> jni::sys::jboolean {
    let Ok(home) = zarust_jni_string(&mut env, &home) else {
        return jni::sys::JNI_FALSE;
    };
    let home = std::path::PathBuf::from(home);
    if horizon_client::official_install::ready(&home) && home.join("engine-data.zip").is_file() {
        jni::sys::JNI_TRUE
    } else {
        jni::sys::JNI_FALSE
    }
}

#[unsafe(no_mangle)]
extern "system" fn Java_dev_horizon_android_RustEditionBridge_nativeInstall(
    mut env: jni::JNIEnv<'_>,
    _class: jni::objects::JClass<'_>,
    home: jni::objects::JString<'_>,
    cache: jni::objects::JString<'_>,
    progress: jni::objects::JString<'_>,
) -> jni::sys::jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let home = std::path::PathBuf::from(zarust_jni_string(&mut env, &home)?);
        let cache = std::path::PathBuf::from(zarust_jni_string(&mut env, &cache)?);
        let progress = std::path::PathBuf::from(zarust_jni_string(&mut env, &progress)?);
        horizon_client::official_install::configure_download_cache_dir(cache);
        horizon_client::official_install::install_with_progress(&home, |update| {
            let value = serde_json::json!({
                "stage": update.stage.label(),
                "done": update.done,
                "total": update.total,
                "complete": false,
                "error": null
            });
            if let Ok(bytes) = serde_json::to_vec(&value) {
                let temporary = progress.with_extension("partial");
                if std::fs::write(&temporary, bytes).is_ok() {
                    let _ = std::fs::rename(&temporary, &progress);
                }
            }
        }).map_err(|error| error.to_string())?;
        let value = serde_json::json!({
            "stage": "Ready",
            "done": 1,
            "total": 1,
            "complete": true,
            "error": null
        });
        if let Ok(bytes) = serde_json::to_vec(&value) {
            let temporary = progress.with_extension("partial");
            if std::fs::write(&temporary, bytes).is_ok() {
                let _ = std::fs::rename(&temporary, &progress);
            }
        }
        Ok::<String, String>(String::new())
    }))
    .unwrap_or_else(|_| Err("Horizon resource installation panicked".to_string()));
    let message = match result {
        Ok(message) => message,
        Err(error) => error,
    };
    env.new_string(message)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}""",
)

print("Horizon storage isolation and direct-launch patches applied.")
