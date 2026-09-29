#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [[ $# -lt 2 || $# -gt 3 ]]; then
  cat >&2 <<'EOF'
Usage:
  ./android/build-from-dump.sh <extracted-game-root> <extracted-tu23-root> [debug|release]

This prepares the Android ReXGlue patches, regenerates the PPC guest from your
own LEGO Dimensions + TU23 files, validates the ARM64 codegen markers, then
builds the APK.
EOF
  exit 2
fi

GAME_ROOT="$1"
TU23_ROOT="$2"
VARIANT="${3:-debug}"

"$ROOT/android/generate-guest.sh" "$GAME_ROOT" "$TU23_ROOT"
"$ROOT/android/build-android.sh" "$VARIANT"
