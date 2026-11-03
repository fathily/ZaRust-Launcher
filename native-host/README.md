# ZaRust Android native host runner

This directory builds the small ARM64 Android PRoot host executable and its external loader.
The outputs are packaged by the Android Gradle build in:

`ZalithLauncher/src/main/jniLibs/arm64-v8a/`

They are part of the ZaRust APK. ZaRust does not launch or require the Termux application.

## Build

The Gradle task `buildZaRustProotHost` invokes `native-host/build.sh` when
`-PrustEditionEnabled=true` and the selected APK architecture includes ARM64.

The host build requires the Android NDK version pinned in
`ZalithLauncher/build.gradle.kts`, Git, curl, make, tar, and sha256sum.

## Runtime

The PRoot executable is started from Android's extracted native-library directory
(`ApplicationInfo.nativeLibraryDir`). It runs a private ARM64 Android userspace under
`files/native-builder/rootfs`, while the source tree, Cargo cache, imported HNMOD staging
area, and output libraries remain inside ZaRust's application-private storage.

The Android-side builder downloads a pinned bootstrap archive and verifies its SHA-256 before
extracting it. No binary downloaded to writable app-data storage is executed directly.

## Third-party code and notices

- PRoot is built from the pinned DroidNative fork commit
  `4ac94ecbb567608725e1d68d5ea617800a76c9c4`. See `COPYING.proot`.
- The Android compatibility helper files under `talloc/` were adapted from
  DroidNative/DroidNativeApp. See `COPYING.droidnative`.
- The build links Samba talloc 2.5.0 and SQLite 3.54.1; their upstream licenses and notices
  must accompany distributable source/binary releases. See `COPYING.talloc` and the SQLite
  public-domain notice where supplied by the upstream source archive.

Do not remove these notices when packaging ZaRust.
