#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VARIANT="${1:-debug}"

case "$VARIANT" in
  debug)   TASK=":app:assembleDebug";   OUT="debug" ;;
  release) TASK=":app:assembleRelease"; OUT="release" ;;
  *)
    echo "Usage: $0 [debug|release]" >&2
    exit 2
    ;;
esac

echo "[1/3] Preparing ReXGlue Android support..."
"$ROOT/android/prepare-rexglue.sh"

GEN="$ROOT/rexlego/generated/default"
if [[ ! -f "$GEN/sources.cmake" || ! -f "$GEN/legodimensions_pch.h" ]]; then
  cat >&2 <<'EOF'
Generated LEGO Dimensions sources are missing.

This repository intentionally does not ship them. Generate rexlego/generated/
from your own Xbox 360 LEGO Dimensions dump with TU23, after applying the
Android ReXGlue patches. See README-dev.md and android/README.md.
EOF
  exit 3
fi

if ! grep -q "g_guest_phys_host_offset" "$GEN/legodimensions_pch.h" ||
   ! grep -q "REX_SYNC_FENCE" "$GEN/legodimensions_pch.h"; then
  cat >&2 <<'EOF'
The generated tree is older than the Android ARM64 codegen fixes.
Re-run ReXGlue codegen with --ignore-stamp after android/prepare-rexglue.sh.
EOF
  exit 4
fi

echo "[2/3] Building Dimensions Recompiled Android ($VARIANT)..."
(
  cd "$ROOT/android"
  ./gradlew "$TASK"
)

echo "[3/3] APK output:"
find "$ROOT/android/app/build/outputs/apk/$OUT" -maxdepth 1 -name '*.apk' -print
