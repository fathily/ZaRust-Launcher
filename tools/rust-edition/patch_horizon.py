#!/usr/bin/env python3
"""Apply the small ZaRust integration patch to the pinned Horizon source tree."""
from pathlib import Path

root = Path(__file__).resolve().parents[2] / "HorizonEngine"
main = root / "crates/horizon-client/src/android_main.rs"
platform = root / "crates/horizon-client/src/android/platform.rs"

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
print("Horizon storage isolation patch applied.")
