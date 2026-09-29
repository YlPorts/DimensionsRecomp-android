#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="$ROOT/rexglue-sdk"
CACHE="$ROOT/android/.cache"
PATCH="$CACHE/rexglue-v0.10.0-android.patch"

# Temporary compatibility layer while Android support is split into a
# maintained SDK fork. The patch supplies Android/arm64, ANativeWindow,
# bionic/ucontext, JNI filesystem glue and SDL3 platform fixes.
PATCH_URL="https://raw.githubusercontent.com/Player124413/Sonic-Generations-recomp-android-and-pc-edition/a163e7d5cb9464056d8e34ab55f84fe38459a979/android/patches/rexglue-sdk-v0.10.0-android.patch"

git -C "$ROOT" submodule update --init --recursive rexglue-sdk

mkdir -p "$CACHE"
if [[ ! -s "$PATCH" ]]; then
  echo "Fetching pinned ReXGlue Android compatibility patch..."
  curl --fail --location --retry 3 "$PATCH_URL" -o "$PATCH"
fi

if git -C "$SDK" apply --reverse --check "$PATCH" >/dev/null 2>&1; then
  echo "ReXGlue Android patch is already applied."
  exit 0
fi

echo "Checking ReXGlue Android patch..."
git -C "$SDK" apply --check "$PATCH"
git -C "$SDK" apply "$PATCH"

echo "ReXGlue Android compatibility layer applied."
