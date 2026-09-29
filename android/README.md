# Dimensions Recompiled — Android bootstrap

This directory is the Android/ARM64 bring-up layer for Dimensions Recompiled.
It uses the existing ReXGlue static recompilation pipeline — it is not an
emulator wrapper.

## Current status

Already wired and CI-validated:

- Android arm64-v8a platform support in ReXGlue.
- SDL3 Android window/event layer.
- Vulkan presentation and the Xenos GPU plugin.
- ReXGlue runtime, UI and `rexgpu-xenos` all compile for Android ARM64.
- App-specific game, update, save, shader-cache and log directories.
- Storage Access Framework launcher that imports the extracted base game and
  TU23 without broad storage permissions.
- Existing `LegodimensionsApp` host code and generated PPC-to-C++ code path.
- Native emulated ToyPad path; physical USB passthrough falls back safely on
  Android until an Android USB-host bridge exists.
- In-app seven-slot Toy Pad manager for 180-byte NTAG213 figure dumps, with
  saved figure state restored into the native loopback ToyPad on the next run.

The Android shell also has a multi-touch Xbox-style controller backed by an
SDL3 virtual gamepad, so touch and physical controllers both use ReXGlue's
normal SDL input path.

Still after the first complete game APK/boot: device-side Toy Pad validation,
crash/graphics validation and Samsung A15 performance tuning.

## Important: generated game code is not in Git

The repository intentionally does **not** contain the hundreds of megabytes of
machine-generated recompiled code or copyrighted LEGO Dimensions game data.

For an Android build, **prepare the Android ReXGlue tree first**, then
run codegen. The Android patch changes the generated PPC memory barriers and
physical-memory address handling for ARM64; a `generated/` tree created before
the patch is not safe to reuse even if it still compiles.

```bash
chmod +x android/prepare-rexglue.sh
./android/prepare-rexglue.sh
# Then follow ../README-dev.md and regenerate from your own dump / TU23.
```

The finished tree must contain:

```
rexlego/generated/default/sources.cmake
rexlego/generated/default/legodimensions_pch.h
...generated source files...
```

If you generated these files before preparing ReXGlue for Android, force a
regeneration (or remove the old codegen stamp) before building the APK.

## Android prerequisites

- Android Studio / Android SDK with API 35 installed.
- Android NDK `27.2.12479018`.
- CMake 3.31.1.
- Java 17.

## Prepare ReXGlue for Android

From the repository root:

```bash
chmod +x android/prepare-rexglue.sh
./android/prepare-rexglue.sh
```

Do **not** use a blanket `git submodule update --init --recursive` on this SDK
revision: it contains an old FidelityFX gitlink without a URL. The preparation
script initializes the ReXGlue submodule and only the nested dependencies that
are actually declared in its `.gitmodules`.

The compatibility patch is pinned to a specific public revision and then the
Dimensions-specific Android fix is applied from `android/patches/`.

## Game-data setup on the phone

The app now launches into a setup screen. Pick the **extracted base-game
folder whose root contains `Default.xex`**, then optionally pick the extracted
TU23 folder. Android's Storage Access Framework grants read access and the
launcher copies the selected trees into:

```
Android/data/com.ylports.dimensionsrecomp/files/
  game/
  update/
  userdata/
  cache/
  logs/
```

No broad storage permission is requested. The imported copy belongs to the app,
so keep the original dump elsewhere before uninstalling.

## Build

Open `android/` in Android Studio and build the `app` module, or use a local
Gradle installation:

```bash
cd android
gradle :app:assembleDebug
```

The normal native configure intentionally fails with a clear message if
`rexlego/generated/default/sources.cmake` is absent. CI can pass
`-DREX_ANDROID_HOST_SMOKE=ON` to compile/link the Android host against a tiny
generated-code stub; that verifies the port code without pretending to be a
playable game build.

## Bring-up checklist

- [x] ReXGlue configures as Android arm64-v8a.
- [x] ReXGlue runtime/UI compile on Android.
- [x] Xenos Vulkan renderer compiles and links on Android.
- [x] Scoped-storage game/TU importer exists.
- [ ] Full `libmain.so` build with locally generated Dimensions sources.
- [ ] First game frame on a physical Android device.
- [x] Multi-touch controller through an SDL3 virtual gamepad.
- [x] In-app ToyPad figure management (Java/protocol path CI-validated).
- [ ] Samsung A15 profiling and 60 FPS tuning.
