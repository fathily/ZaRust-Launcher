#!/usr/bin/env bash
# Build a Starling Android libhorizon_android.so with selected native Horizon mixins.
# Usage: native_build.sh SOURCE OUT APP_VERSION BUNDLE...
set -euo pipefail

MIXINS_GIT=https://github.com/horizon-rust/horizon-mixins
MIXINS_REV=0d6b103c70633d8fd9443d8d0acd3aadaacedb06

fail() { echo "native_build: $*" >&2; exit 1; }
[ $# -ge 4 ] || fail "usage: native_build.sh SOURCE OUT APP_VERSION BUNDLE..."
source_dir=$(cd "$1" && pwd)
mkdir -p "$2"
out=$(cd "$2" && pwd)
app_version=$3
shift 3
[[ "$app_version" =~ ^[0-9]+([.][0-9]+){1,3}$ ]] || fail "invalid ZaRust app version: $app_version"
cache=${NATIVE_CACHE:-$HOME/.cache/zarust-rust-native}
profile=${PROFILE:-fast}
script_dir=$(cd "$(dirname "$0")" && pwd)
patch_script=$script_dir/patch_starling.py
[ -f "$patch_script" ] || fail "missing Starling patch script: $patch_script"
mkdir -p "$cache"

tools=$cache/mixins-${MIXINS_REV:0:12}
if [ ! -x "$tools/bin/horizon-mixins" ]; then
  cargo install --locked --git "$MIXINS_GIT" --rev "$MIXINS_REV" --root "$tools" horizon-mixins-cli
fi

commit=$(git -C "$source_dir" rev-parse HEAD)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir "$work/source"
git -C "$source_dir" archive --format=tar HEAD | tar -x -C "$work/source"
find "$work/source" -type l -delete
echo "$commit" > "$work/source/.horizon-revision"

# Apply the exact ZaRust Android/account/storage patches to the modded library build as well.
python3 "$patch_script" "$work/source"

manifest=crates/horizon-android/Cargo.toml
lib_line='path = "../horizon-client/src/android_lib.rs"'
grep -qxF "$lib_line" "$work/source/$manifest" || fail "$manifest no longer has: $lib_line"
sed -i "s|^path = \"../horizon-client/src/android_lib.rs\"$|path = \"plan-stub.rs\"|" \
  "$work/source/$manifest"
: > "$work/source/crates/horizon-android/plan-stub.rs"

mkdir "$work/mods"
for bundle in "$@"; do
  [ -f "$bundle/horizon-mixin.toml" ] || fail "$bundle has no horizon-mixin.toml"
  copy=$work/mods/$(basename "$bundle")
  [ ! -e "$copy" ] || fail "two bundles named $(basename "$bundle")"
  cp -r "$bundle" "$copy"
  sed -i -E "s|^horizon_revision = \"[0-9a-f]*\"|horizon_revision = \"$commit\"|" \
    "$copy/horizon-mixin.toml"
done

key=$(
  {
    echo "$commit $profile $MIXINS_REV $app_version"
    (cd "$work/mods" && find . -type f -print0 | sort -z | xargs -0 sha256sum)
  } | sha256sum | cut -c1-16
)
echo "native_build: ZaRust $app_version, engine $commit, mods: $(ls "$work/mods" | tr '\n' ' ')"

cargo fetch --locked --manifest-path "$work/source/Cargo.toml" > /dev/null
"$tools/bin/horizon-mixins" apply --workspace "$work/source" --mods "$work/mods" \
  --out "$work/stage"

build=$cache/build
mkdir -p "$build"
rsync -r --checksum --delete --exclude /target/ --exclude "/$manifest" \
  --exclude /crates/horizon-android/plan-stub.rs "$work/stage/" "$build/"
git -C "$source_dir" show "HEAD:$manifest" > "$work/manifest"
cmp -s "$work/manifest" "$build/$manifest" || cp "$work/manifest" "$build/$manifest"

# Build the embedded JavaScript engine when its sources have changed. This is part
# of the native game's runtime, not an Android APK packaging step.
js_dir=$build/crates/starling-mod-sdk/js-engine
js_engine=$build/target/js-engine/engine.wasm
if [ ! -s "$js_engine" ] || [ "$js_engine" -ot "$js_dir/engine.js" ] || \
   [ "$js_engine" -ot "$js_dir/build.mjs" ] || [ "$js_engine" -ot "$js_dir/package.json" ] || \
   [ "$js_engine" -ot "$build/crates/starling-mod-sdk/wit/starling.wit" ]; then
  if [ ! -d "$js_dir/node_modules" ]; then
    (cd "$js_dir" && npm install --no-audit --no-fund)
  fi
  (cd "$js_dir" && npm run build)
fi

android_home=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[ -n "$android_home" ] || fail "ANDROID_HOME / ANDROID_SDK_ROOT is not set"
ndk=${ANDROID_NDK_HOME:-$android_home/ndk/28.2.13676358}
[ -d "$ndk" ] || fail "Android NDK not found: $ndk"
llvm_strip=$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip
[ -x "$llvm_strip" ] || fail "llvm-strip not found: $llvm_strip"

export ANDROID_HOME="$android_home"
export ANDROID_NDK_HOME="$ndk"
export RUSTFLAGS="${RUSTFLAGS:+$RUSTFLAGS }-C link-arg=-Wl,-z,max-page-size=16384"

# Compile only the arm64-v8a game library. Do not call build_apk.py here: that
# script also assembles and signs a stand-alone Starling APK, which ZaRust does not need.
cargo ndk -t arm64-v8a --platform 26 build --profile "$profile" -p horizon-android

built=$build/target/aarch64-linux-android/$profile/libhorizon_android.so
[ -s "$built" ] || fail "Android game library not produced: $built"
"$llvm_strip" --strip-unneeded -o "$out/libhorizon_android.so" "$built"
[ -s "$out/libhorizon_android.so" ] || fail "Stripped Android game library is empty"
readelf -h "$out/libhorizon_android.so" | grep -q "AArch64" || fail "Native library is not arm64-v8a"

python3 - "$build" "$work/mods" "$out" "$key" "$commit" "$profile" "$MIXINS_REV" "$app_version" <<'PY'
import hashlib, json, pathlib, re, sys, tomllib
build, mods, out, key, commit, profile, mixins, app_version = sys.argv[1:]
starling_version = re.search(
    r'^VERSION_NAME = "([^"]+)"',
    (pathlib.Path(build) / "packaging/android/build_apk.py").read_text(),
    re.M,
).group(1)
library = pathlib.Path(out) / "libhorizon_android.so"
listed = []
for folder in sorted(pathlib.Path(mods).iterdir()):
    manifest = tomllib.loads((folder / "horizon-mixin.toml").read_text())["mod"]
    listed.append({"id": manifest["id"], "version": manifest["version"]})
metadata = {
    "key": key,
    "version": app_version,
    "starling_version": starling_version,
    "commit": commit,
    "profile": profile,
    "mixins": mixins,
    "mods": listed,
    "abi": "arm64-v8a",
    "library": "libhorizon_android.so",
    "sha256": hashlib.sha256(library.read_bytes()).hexdigest(),
    "size": library.stat().st_size,
}
(pathlib.Path(out) / "build.json").write_text(json.dumps(metadata, indent=2) + "\n")
PY
cat "$out/build.json"
