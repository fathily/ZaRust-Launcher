#!/usr/bin/env bash
# Build the Android ARM64 PRoot host executable used by ZaRust's in-app native builder.
# The output is packaged in jniLibs so Android can execute it without Termux.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/.cache/zarust-proot"
OUT="$ROOT/ZalithLauncher/src/main/jniLibs/arm64-v8a"
PROOT_REPO="https://github.com/DroidNative/proot.git"
PROOT_COMMIT="4ac94ecbb567608725e1d68d5ea617800a76c9c4"
TALLOC_VERSION="2.5.0"
TALLOC_SHA256="912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007"
SQLITE_VERSION="3540100"
SQLITE_SHA256="926ef8d3eab93fbf6e06011901273eec076b25594a4e1cdadd50c987f4f6d64e"
API=24
fail() { echo "native-host: $*" >&2; exit 1; }

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}"
if [ -z "$NDK" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [ -n "$SDK" ] || fail "Set ANDROID_HOME or ANDROID_NDK_HOME."
  NDK="$SDK/ndk/25.2.9519653"
fi
[ -d "$NDK" ] || fail "Android NDK not found: $NDK"
case "$(uname -s)" in
  Linux) HOST=linux-x86_64 ;;
  Darwin) HOST=darwin-x86_64 ;;
  *) fail "The PRoot APK host builder currently supports Linux and macOS build hosts." ;;
esac
TC="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
CC="$TC/aarch64-linux-android${API}-clang"
[ -x "$CC" ] || fail "Android NDK Clang not found: $CC"
for tool in llvm-ar llvm-strip llvm-objcopy llvm-objdump llvm-readelf; do
  [ -x "$TC/$tool" ] || fail "Android NDK tool not found: $TC/$tool"
done

mkdir -p "$CACHE" "$OUT"
fetch() {
  local url="$1" dest="$2" digest="$3"
  if [ ! -s "$dest" ] || ! echo "$digest  $dest" | sha256sum -c --status; then
    rm -f "$dest"
    curl --fail --location --retry 4 --retry-all-errors --output "$dest.part" "$url"
    echo "$digest  $dest.part" | sha256sum -c --quiet
    mv "$dest.part" "$dest"
  fi
}

# PRoot fork with Android storage emulation and an external loader.
if [ ! -d "$CACHE/proot-src/.git" ]; then
  git clone --filter=blob:none "$PROOT_REPO" "$CACHE/proot-src"
fi
if ! git -C "$CACHE/proot-src" cat-file -e "$PROOT_COMMIT^{commit}" 2>/dev/null; then
  git -C "$CACHE/proot-src" fetch --depth=1 origin "$PROOT_COMMIT"
fi
git -C "$CACHE/proot-src" checkout --detach "$PROOT_COMMIT"

fetch "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz" \
  "$CACHE/talloc-$TALLOC_VERSION.tar.gz" "$TALLOC_SHA256"
if [ ! -d "$CACHE/talloc-$TALLOC_VERSION" ]; then
  tar -xzf "$CACHE/talloc-$TALLOC_VERSION.tar.gz" -C "$CACHE"
fi
fetch "https://www.sqlite.org/2026/sqlite-autoconf-$SQLITE_VERSION.tar.gz" \
  "$CACHE/sqlite-autoconf-$SQLITE_VERSION.tar.gz" "$SQLITE_SHA256"
if [ ! -d "$CACHE/sqlite-autoconf-$SQLITE_VERSION" ]; then
  tar -xzf "$CACHE/sqlite-autoconf-$SQLITE_VERSION.tar.gz" -C "$CACHE"
fi

BUILD="$CACHE/build-arm64"
PROOT="$BUILD/proot"
rm -rf "$PROOT"
mkdir -p "$PROOT"
git clone --shared "$CACHE/proot-src" "$PROOT"
git -C "$PROOT" checkout --detach "$PROOT_COMMIT"
git -C "$PROOT" clean -fdx

TALLOC="$BUILD/talloc"
mkdir -p "$TALLOC/include"
"$CC" -c -O2 -fPIC -D_GNU_SOURCE -D__STDC_WANT_LIB_EXT1__=1 \
  -I"$ROOT/native-host/talloc" \
  -I"$CACHE/talloc-$TALLOC_VERSION/lib/replace" \
  -I"$CACHE/talloc-$TALLOC_VERSION" \
  "$CACHE/talloc-$TALLOC_VERSION/talloc.c" -o "$TALLOC/talloc.o"
"$CC" -c -O2 -fPIC "$ROOT/native-host/talloc/replace_min.c" \
  -o "$TALLOC/replace_min.o"
"$TC/llvm-ar" rcs "$TALLOC/libtalloc.a" "$TALLOC/talloc.o" "$TALLOC/replace_min.o"
cp "$CACHE/talloc-$TALLOC_VERSION/talloc.h" "$TALLOC/include/"

SQLITE_DIR="$CACHE/sqlite-autoconf-$SQLITE_VERSION"
make -C "$PROOT/src" clean
CPPFLAGS="-I$TALLOC/include -DARG_MAX=131072 -DVERSION=\\\"dn-\${PROOT_COMMIT:0:12}\\\"" \
LDFLAGS="-L$TALLOC -Wl,-z,max-page-size=16384" \
SQLITE_DIR="$SQLITE_DIR" \
make -C "$PROOT/src" -j"\${JOBS:-2}" proot \
  CC="$CC" STRIP="$TC/llvm-strip" \
  OBJCOPY="$TC/llvm-objcopy" OBJDUMP="$TC/llvm-objdump" \
  PROOT_UNBUNDLE_LOADER=/nonexistent

[ -s "$PROOT/src/proot" ] || fail "PRoot executable was not produced."
[ -s "$PROOT/src/loader/loader" ] || fail "PRoot external loader was not produced."
"$TC/llvm-strip" -o "$OUT/libproot.so" "$PROOT/src/proot"
cp "$PROOT/src/loader/loader" "$OUT/libproot-loader.so"
chmod 755 "$OUT/libproot.so" "$OUT/libproot-loader.so"
"$TC/llvm-readelf" -h "$OUT/libproot.so" | grep -q "AArch64" || fail "PRoot host is not ARM64."
"$TC/llvm-readelf" -h "$OUT/libproot-loader.so" | grep -q "AArch64" || fail "PRoot loader is not ARM64."
echo "Built ZaRust Android PRoot host tools:"
ls -l "$OUT/libproot.so" "$OUT/libproot-loader.so"
