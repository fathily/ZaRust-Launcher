#!/usr/bin/env python3
"""Copy Starling's Android-native build into ZaRust's app source set."""
from pathlib import Path
import argparse
import shutil
import os

parser = argparse.ArgumentParser()
parser.add_argument("--starling-root", default="StarlingEngine")
args = parser.parse_args()

starling = Path(args.starling_root)
out = starling / "target/android"
app = Path("ZalithLauncher/src/main")
jni = app / "jniLibs/arm64-v8a"
assets = app / "assets"

native_names = [
    "libhorizon_android.so",
    "libstarling_java.so",
    "libstarling_javac.so",
    "libc++_shared.so",
]
jni.mkdir(parents=True, exist_ok=True)
for name in native_names[:-1]:
    source = out / name
    if not source.is_file() or source.stat().st_size == 0:
        raise SystemExit(f"Starling native output missing or empty: {source}")
    shutil.copy2(source, jni / name)

# build_apk.py links this C++ runtime directly from the NDK sysroot into its standalone APK;
# it does not copy it into target/android, so source it from the same pinned NDK here.
android_home = Path(os.environ.get("ANDROID_HOME", ""))
ndk = Path(os.environ.get("ANDROID_NDK_HOME") or android_home / "ndk/28.2.13676358")
libcxx = ndk / "toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
if not libcxx.is_file() or libcxx.stat().st_size == 0:
    raise SystemExit(f"Starling NDK C++ runtime missing or empty: {libcxx}")
shutil.copy2(libcxx, jni / "libc++_shared.so")

source_assets = out / "assets"
if not source_assets.is_dir():
    raise SystemExit(f"Starling game assets directory is missing: {source_assets}")
assets.mkdir(parents=True, exist_ok=True)
for source in source_assets.rglob("*"):
    if source.is_file():
        target = assets / source.relative_to(source_assets)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)

required_assets = ["prepare-kit.zip", "lowbudget.zip", "StarlingVisuals.zip"]
for name in required_assets:
    target = assets / name
    if not target.is_file() or target.stat().st_size == 0:
        raise SystemExit(f"Required Starling asset missing or empty: {target}")

java_source = starling / "packaging/android/java"
if not java_source.is_dir():
    raise SystemExit(f"Starling Android Java bridge is missing: {java_source}")
copied_java = 0
for source in java_source.rglob("*.java"):
    target = app / "java" / source.relative_to(java_source)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    copied_java += 1
if copied_java < 5:
    raise SystemExit(f"Only {copied_java} Starling Java bridge source files were copied")

resource_source = starling / "packaging/android/res"
if not resource_source.is_dir():
    raise SystemExit(f"Starling Android resources are missing: {resource_source}")
for source in resource_source.rglob("*"):
    if source.is_file():
        target = app / "res" / source.relative_to(resource_source)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)

notice_dir = assets / "rust-edition"
notice_dir.mkdir(parents=True, exist_ok=True)
for name in ["LICENSE", "THIRD_PARTY_NOTICES.md"]:
    source = starling / name
    if not source.is_file():
        raise SystemExit(f"Starling license notice is missing: {source}")
    shutil.copy2(source, notice_dir / ("STARLING-" + name))

print(
    "Starling staged:",
    ", ".join(native_names),
    f"{copied_java} Java bridge files",
    len(required_assets), "verified game assets",
)
