#!/data/data/com.termux/files/usr/bin/bash
# Build Horizon's Android shared library inside ZaRust's private PRoot userspace.
# Usage: native_build_android.sh SOURCE OUT APP_VERSION STARLING_COMMIT BUNDLE...
set -euo pipefail

MIXINS_GIT="https://github.com/horizon-rust/horizon-mixins"
MIXINS_REV="0d6b103c70633d8fd9443d8d0acd3aadaacedb06"
fail() { echo "native_build_android: $*" >&2; exit 1; }
[ "$#" -ge 5 ] || fail "usage: native_build_android.sh SOURCE OUT APP_VERSION STARLING_COMMIT BUNDLE..."
source_dir=$(cd "$1" && pwd)
mkdir -p "$2"
out=$(cd "$2" && pwd)
app_version="$3"
commit="$4"
shift 4
[[ "$app_version" =~ ^[0-9]+([.][0-9]+){1,3}$ ]] || fail "invalid ZaRust version"
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || fail "Starling must be pinned to a full commit SHA"
[ -f "$source_dir/Cargo.toml" ] || fail "Starling source Cargo.toml missing"
[ -d "$source_dir/crates/horizon-android" ] || fail "Starling source does not contain horizon-android"
[ -x "$PREFIX/bin/rustc" ] || fail "Rust compiler is not installed in ZaRust's build environment"
[ -x "$PREFIX/bin/clang" ] || fail "Clang is not installed in ZaRust's build environment"
[ -d "$PREFIX/include/android" ] || fail "Android NDK sysroot is not installed"

cache="$NATIVE_CACHE"
mkdir -p "$cache"
script_dir=$(cd "$(dirname "$0")" && pwd)
patch_script="$script_dir/patch_starling.py"
[ -f "$patch_script" ] || fail "missing patch_starling.py"

# Termux's on-device Rust host uses Android/Bionic. Create a target Clang alias for
# the game library target; it uses the installed Android NDK sysroot and LLD.
linker="$PREFIX/bin/aarch64-linux-android26-clang"
cat > "$linker" <<'SH'
#!/data/data/com.termux/files/usr/bin/sh
exec /data/data/com.termux/files/usr/bin/clang --target=aarch64-linux-android26 "$@"
SH
chmod 700 "$linker"
export CC_aarch64_linux_android="$linker"
export CXX_aarch64_linux_android="$PREFIX/bin/clang++"
export AR_aarch64_linux_android="$PREFIX/bin/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$linker"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR="$PREFIX/bin/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-landroid-execinfo -C link-arg=-lc++_shared"

tools="$cache/mixins-\${MIXINS_REV:0:12}"
if [ ! -x "$tools/bin/horizon-mixins" ]; then
  cargo install --locked --git "$MIXINS_GIT" --rev "$MIXINS_REV" --root "$tools" horizon-mixins-cli
fi

key=$(
  {
    echo "$commit fast $MIXINS_REV $app_version"
    for bundle in "$@"; do
      [ -f "$bundle/horizon-mixin.toml" ] || fail "$bundle has no horizon-mixin.toml"
      find "$bundle" -type f -print0 | sort -z | xargs -0 sha256sum
    done
  } | sha256sum | cut -c1-16
)
build="$cache/build-$key"
work="$cache/work-$key"
rm -rf "$work"
mkdir -p "$work/source" "$work/mods" "$out"
cp -a "$source_dir/." "$work/source/"
echo "$commit" > "$work/source/.horizon-revision"

# Apply ZaRust account, storage and launcher bridge patches before mixin injection.
python "$patch_script" "$work/source"

manifest="crates/horizon-android/Cargo.toml"
cp "$work/source/$manifest" "$work/manifest.original"
lib_line='path = "../horizon-client/src/android_lib.rs"'
grep -qxF "$lib_line" "$work/source/$manifest" || fail "$manifest no longer matches the expected Android library layout"
sed -i "s|^path = \"../horizon-client/src/android_lib.rs\"$|path = \"plan-stub.rs\"|" "$work/source/$manifest"
: > "$work/source/crates/horizon-android/plan-stub.rs"

for bundle in "$@"; do
  [ -f "$bundle/horizon-mixin.toml" ] || fail "$bundle has no horizon-mixin.toml"
  id=$(sed -n 's/^id = "\([^"]*\)"/\1/p' "$bundle/horizon-mixin.toml" | head -n1)
  [[ "$id" =~ ^[a-z0-9][a-z0-9_-]{0,40}$ ]] || fail "invalid native mod ID in $bundle"
  copy="$work/mods/$id"
  [ ! -e "$copy" ] || fail "duplicate native mod ID: $id"
  cp -a "$bundle/." "$copy/"
  sed -i -E "s|^horizon_revision = \"[0-9a-f]*\"|horizon_revision = \"$commit\"|" "$copy/horizon-mixin.toml"
done

echo "ZaRust Native Builder: engine $commit, native mods: $(ls "$work/mods" | tr '\n' ' ')"
cargo fetch --locked --manifest-path "$work/source/Cargo.toml"
"$tools/bin/horizon-mixins" apply --workspace "$work/source" --mods "$work/mods" --out "$work/stage"

mkdir -p "$build"
rsync -r --checksum --delete \
  --exclude /target/ \
  --exclude "/$manifest" \
  --exclude /crates/horizon-android/plan-stub.rs \
  "$work/stage/" "$build/"
cp "$work/manifest.original" "$work/manifest"
cmp -s "$work/manifest" "$build/$manifest" || cp "$work/manifest" "$build/$manifest"

# Starling's JavaScript module engine is part of the Rust runtime.
js_dir="$build/crates/starling-mod-sdk/js-engine"
js_engine="$build/target/js-engine/engine.wasm"
if [ ! -s "$js_engine" ] || [ "$js_engine" -ot "$js_dir/engine.js" ] || \
   [ "$js_engine" -ot "$js_dir/build.mjs" ] || [ "$js_engine" -ot "$js_dir/package.json" ] || \
   [ "$js_engine" -ot "$build/crates/starling-mod-sdk/wit/starling.wit" ]; then
  if [ ! -d "$js_dir/node_modules" ]; then
    (cd "$js_dir" && npm install --no-audit --no-fund)
  fi
  (cd "$js_dir" && npm run build)
fi

# Cargo uses Termux's native Android host compiler and the NDK sysroot package.
cd "$build"
export CARGO_BUILD_JOBS="\${CARGO_BUILD_JOBS:-2}"
export CARGO_TARGET_DIR="$build/target"
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-landroid-execinfo -C link-arg=-lc++_shared"
cargo build --locked --target aarch64-linux-android --profile fast -p horizon-android

built="$CARGO_TARGET_DIR/aarch64-linux-android/fast/libhorizon_android.so"
[ -s "$built" ] || fail "Android native library was not produced: $built"
"$PREFIX/bin/llvm-strip" --strip-unneeded -o "$out/libhorizon_android.so" "$built"
[ -s "$out/libhorizon_android.so" ] || fail "stripped Android native library is empty"
"$PREFIX/bin/readelf" -h "$out/libhorizon_android.so" | grep -q "AArch64" || fail "native library is not ARM64"

python - "$build" "$work/mods" "$out" "$key" "$commit" "$MIXINS_REV" "$app_version" <<'PY'
import hashlib, json, pathlib, re, sys, tomllib
build, mods, out, key, commit, mixins, app_version = sys.argv[1:]
manifest_text = (pathlib.Path(build) / "packaging/android/build_apk.py").read_text()
match = re.search(r'^VERSION_NAME = "([^"]+)"', manifest_text, re.M)
if not match:
    raise SystemExit("Could not determine Starling Android version.")
library = pathlib.Path(out) / "libhorizon_android.so"
mods_out = []
for folder in sorted(pathlib.Path(mods).iterdir()):
    info = tomllib.loads((folder / "horizon-mixin.toml").read_text())["mod"]
    mods_out.append({"id": info["id"], "version": info["version"]})
metadata = {
    "key": key,
    "zaRustVersion": app_version,
    "starlingVersion": match.group(1),
    "starlingCommit": commit,
    "mixinsRevision": mixins,
    "mods": mods_out,
    "abi": "arm64-v8a",
    "library": "libhorizon_android.so",
    "sha256": hashlib.sha256(library.read_bytes()).hexdigest(),
    "size": library.stat().st_size,
}
(pathlib.Path(out) / "build.json").write_text(json.dumps(metadata, indent=2) + "\n")
PY
cat "$out/build.json"
echo "LOCAL_NATIVE_BUILD_SUCCESS"
