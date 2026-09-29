# Dimensions Recompiled — Android bootstrap

This directory is the first Android/ARM64 bring-up layer for Dimensions
Recompiled. It is built around the existing ReXGlue recompiler rather than an
emulator wrapper.

## What this milestone wires up

- Android application package (arm64-v8a only).
- SDL3 activity/window/event loop.
- Vulkan presentation through ReXGlue's Xenos GPU plugin.
- Android-safe application, save, shader-cache and log directories.
- The existing `LegodimensionsApp` and generated PPC-to-C++ code.
- A temporary pinned Android compatibility patch for ReXGlue 0.10.0.

Touch controls, a Storage Access Framework game-data importer, Toy Pad UI and
performance tuning intentionally come after the first native boot.

## Important: generated game code is not in Git

The repository intentionally does **not** contain the hundreds of megabytes of
generated recompiled code or copyrighted LEGO Dimensions game data.

First follow `../README-dev.md` and generate:

```
rexlego/generated/default/sources.cmake
rexlego/generated/default/legodimensions_pch.h
...generated source files...
```

from your own Xbox 360 LEGO Dimensions dump / TU23.

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

The compatibility patch is pinned to a specific public revision so it cannot
silently change. It is temporary: the goal is to replace it with a maintained
Android SDK fork once the bring-up is stable.

## Data layout for the first boot

The bootstrap creates these folders under Android's app-specific external
storage:

```
Android/data/com.ylports.dimensionsrecomp/files/
  game/       # extracted base game; default.xex must be here
  update/     # extracted TU23/update tree
  userdata/   # saves/profile data
  cache/      # shader/runtime cache
  logs/       # legodimensions.log
```

This avoids broad storage permissions on Android 11+.

## Build

Open `android/` in Android Studio and build the `app` module, or use a local
Gradle installation:

```bash
cd android
gradle :app:assembleDebug
```

The native configure intentionally fails with a clear message if
`rexlego/generated/default/sources.cmake` is absent.

## First bring-up checklist

1. CMake config reaches the patched ReXGlue Android platform.
2. `libmain.so` and `librexgpu-xenos.so` build for arm64-v8a.
3. SDLActivity opens a landscape surface.
4. ReXGlue finds `--gpu_plugin=xenos`.
5. The runtime maps guest memory and loads the generated image.
6. Vulkan presents the first frame.
7. Only after that: touch controller, data importer, Toy Pad workflow and
   Samsung A15 performance profiling.
