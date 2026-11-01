#!/usr/bin/env python3
"""Apply the small ZaRust integration patch to the pinned Horizon source tree."""
from pathlib import Path

root = Path(__file__).resolve().parents[2] / "HorizonEngine"
main = root / "crates/horizon-client/src/android_main.rs"
platform = root / "crates/horizon-client/src/android/platform.rs"
launcher = root / "crates/horizon-client/src/android/launcher.rs"
account = root / "launcher/src/account.rs"

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


# Allow ZaRust's already-authenticated Microsoft account to be imported securely.
replace_once(
    account,
    "impl Secrets {\n    fn new(refresh_token: String, mc: Minecraft) -> Self {",
    """impl Secrets {
    /// Import the selected Zalith Microsoft account into Android Keystore storage.
    pub fn from_zalith(
        refresh_token: String,
        minecraft_token: String,
        minecraft_expires_at: u64,
        xuid: Option<String>,
    ) -> Self {
        Self {
            refresh_token,
            minecraft_token,
            minecraft_expires_at,
            xuid,
            owns: true,
        }
    }

    fn new(refresh_token: String, mc: Minecraft) -> Self {""",
)

# Expose the pinned Horizon resource installer to ZaRust's Jetpack Compose UI.
# It runs before NativeActivity starts, so the user never sees Horizon's installer UI.
replace_once(
    main,
    "    std::process::exit(0);\n}",
    """    std::process::exit(0);
}

fn zarust_jni_string(
    env: &mut jni::JNIEnv<'_>,
    value: &jni::objects::JString<'_>,
) -> std::result::Result<String, String> {
    env.get_string(value).map(|text| text.into()).map_err(|error| error.to_string())
}

fn zarust_account_home(
    env: &mut jni::JNIEnv<'_>,
    value: &jni::objects::JString<'_>,
) -> std::result::Result<std::path::PathBuf, String> {
    let home = std::path::PathBuf::from(zarust_jni_string(env, value)?);
    crate::backend::initialize(home.clone());
    let vm = env.get_java_vm().map_err(|error| error.to_string())?;
    crate::secure_store::initialize(vm);
    Ok(home)
}

#[unsafe(no_mangle)]
extern "system" fn Java_dev_horizon_android_RustEditionBridge_nativeSyncZalithAccount(
    mut env: jni::JNIEnv<'_>,
    _class: jni::objects::JClass<'_>,
    home_arg: jni::objects::JString<'_>,
    uuid_arg: jni::objects::JString<'_>,
    name_arg: jni::objects::JString<'_>,
    access_arg: jni::objects::JString<'_>,
    refresh_arg: jni::objects::JString<'_>,
    expires_arg: jni::sys::jlong,
    xuid_arg: jni::objects::JString<'_>,
    microsoft: jni::sys::jboolean,
) -> jni::sys::jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let _home = zarust_account_home(&mut env, &home_arg)?;
        let uuid = zarust_jni_string(&mut env, &uuid_arg)?;
        let name = zarust_jni_string(&mut env, &name_arg)?;
        let mut accounts = crate::account::Accounts::load();
        if microsoft != 0 {
            let access = zarust_jni_string(&mut env, &access_arg)?;
            let refresh = zarust_jni_string(&mut env, &refresh_arg)?;
            let xuid = zarust_jni_string(&mut env, &xuid_arg)?;
            if uuid.trim().is_empty() || name.trim().is_empty()
                || access.trim().is_empty() || refresh.trim().is_empty()
            {
                return Err("Akun Microsoft ZaRust belum lengkap. Refresh akun dari menu Accounts.".to_string());
            }
            let account = crate::account::Account {
                uuid: uuid.replace('-', "").to_lowercase(),
                name,
                skin: None,
                cape: None,
            };
            let secrets = crate::account::Secrets::from_zalith(
                refresh,
                access,
                (expires_arg.max(0) as u64) / 1000,
                if xuid.is_empty() { None } else { Some(xuid) },
            );
            accounts.add(crate::account::store(), account, &secrets)?;
            accounts.save()?;
        } else {
            accounts.selected = None;
            accounts.offline_selected = if name.trim().is_empty() { None } else { Some(name) };
            accounts.save()?;
        }
        Ok::<_, String>("ok".to_string())
    })).unwrap_or_else(|_| Err("Gagal menyinkronkan akun ZaRust ke Rust Edition".to_string()));
    let message = match result { Ok(value) => value, Err(error) => error };
    env.new_string(message).map(|value| value.into_raw()).unwrap_or(std::ptr::null_mut())
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
