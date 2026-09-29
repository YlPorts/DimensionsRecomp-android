#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

usage() {
  cat >&2 <<'EOF'
Usage:
  ./android/generate-guest.sh <extracted-game-root> <extracted-tu23-root>

Environment:
  REXGLUE_CODEGEN=/absolute/path/to/rexglue[.exe]

The game root must contain Default.xex.
The TU23 root must contain Default.xexp.
EOF
}

[[ $# -eq 2 ]] || { usage; exit 2; }

GAME_ROOT="$(cd "$1" && pwd)"
TU23_ROOT="$(cd "$2" && pwd)"

find_ci_file() {
  local root="$1"
  local name="$2"
  find "$root" -maxdepth 1 -type f -iname "$name" -print -quit
}

BASE_XEX="$(find_ci_file "$GAME_ROOT" "default.xex")"
TU_XEXP="$(find_ci_file "$TU23_ROOT" "default.xexp")"

if [[ -z "$BASE_XEX" ]]; then
  echo "No Default.xex found in: $GAME_ROOT" >&2
  exit 3
fi
if [[ -z "$TU_XEXP" ]]; then
  echo "No Default.xexp found in TU23 root: $TU23_ROOT" >&2
  exit 4
fi

# ReXGlue's XEX loader discovers the title update as <xex path> + "p".
# Keep one canonical lowercase sibling because the generated Android runtime
# also validates this exact layout before launch.
GAME_XEXP="$GAME_ROOT/default.xexp"
if [[ "$(cd "$(dirname "$TU_XEXP")" && pwd)/$(basename "$TU_XEXP")" != "$GAME_XEXP" ]]; then
  echo "Installing TU23 executable patch beside Default.xex..."
  cp -f "$TU_XEXP" "$GAME_XEXP"
fi

"$ROOT/android/prepare-rexglue.sh"

CODEGEN="${REXGLUE_CODEGEN:-}"
if [[ -z "$CODEGEN" ]]; then
  candidates=(
    "$ROOT/rexglue-sdk/out/install/win-amd64/bin/rexglue.exe"
    "$ROOT/rexglue-sdk/out/install/linux-amd64/bin/rexglue"
    "$ROOT/rexglue-sdk/out/install/mac-amd64/bin/rexglue"
    "$ROOT/rexglue-sdk/out/build/linux-amd64-release/src/rexglue/rexglue"
    "$ROOT/rexglue-sdk/out/build/win-amd64-release/src/rexglue/rexglue.exe"
  )
  for candidate in "${candidates[@]}"; do
    if [[ -x "$candidate" ]]; then
      CODEGEN="$candidate"
      break
    fi
  done
fi
if [[ -z "$CODEGEN" ]] && command -v rexglue >/dev/null 2>&1; then
  CODEGEN="$(command -v rexglue)"
fi

# A clean Android checkout normally has no host rexglue CLI yet. Build just the
# CLI automatically with the computer's native Clang toolchain; codegen itself
# must run on the development computer, not inside an Android cross build.
if [[ -z "$CODEGEN" || ! -x "$CODEGEN" ]]; then
  echo "Host ReXGlue CLI not found; building rexglue codegen tool..."
  HOST_BUILD="$ROOT/android/.host-rexglue-build"
  cmake -S "$ROOT/rexglue-sdk" -B "$HOST_BUILD" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_COMPILER=clang \
    -DCMAKE_CXX_COMPILER=clang++ \
    -DREXGLUE_BUILD_TOOLS=ON \
    -DREXGLUE_BUILD_TESTS=OFF \
    -DREXGLUE_ENABLE_TRACY=OFF \
    -DREXGLUE_ENABLE_FIDELITYFX=OFF \
    -DREXGLUE_ENABLE_DESKTOP_SDL_BACKENDS=OFF \
    -DREXGLUE_USE_VULKAN=ON
  cmake --build "$HOST_BUILD" --target rexglue --parallel

  CODEGEN="$(find "$ROOT/rexglue-sdk/out" -maxdepth 3 -type f \
    \( -name rexglue -o -name rexglue.exe \) -perm -111 -print 2>/dev/null |
    grep -v '/android-' | head -n 1 || true)"
fi

if [[ -z "$CODEGEN" || ! -x "$CODEGEN" ]]; then
  cat >&2 <<'EOF'
Could not build or locate a host ReXGlue codegen executable.
ReXGlue requires a native Clang 18+ toolchain and Ninja.

You can point at an existing host build explicitly:
  REXGLUE_CODEGEN=/absolute/path/to/rexglue
EOF
  exit 5
fi

case "$GAME_ROOT$BASE_XEX" in
  *"'"*)
    echo "Paths containing a single quote are not supported by the generated TOML manifest." >&2
    exit 6
    ;;
esac

MANIFEST="$ROOT/rexlego/legodimensions_manifest.toml"
cat > "$MANIFEST" <<EOF
# Generated locally by android/generate-guest.sh. Not committed.
[project]
name = "legodimensions"
sdk_version = "0.10.0"
game_root = '$GAME_ROOT'

[entrypoint]
file_path = '$BASE_XEX'
out_directory_path = "generated/default"
includes = ["legodimensions_config.toml"]
EOF

echo "Generating Android-safe PPC C++ from TU23..."
(
  cd "$ROOT/rexlego"
  "$CODEGEN" codegen --pointer_table_scan --ignore-stamp legodimensions_manifest.toml
)

PCH="$ROOT/rexlego/generated/default/legodimensions_pch.h"
SOURCES="$ROOT/rexlego/generated/default/sources.cmake"
if [[ ! -f "$PCH" || ! -f "$SOURCES" ]]; then
  echo "Codegen finished without the expected generated/default output." >&2
  exit 7
fi
if ! grep -q "g_guest_phys_host_offset" "$PCH" ||
   ! grep -q "REX_SYNC_FENCE" "$PCH"; then
  echo "Generated output does not contain the Android ARM64 codegen markers." >&2
  exit 8
fi

echo "Generated guest is ready:"
echo "  $SOURCES"
