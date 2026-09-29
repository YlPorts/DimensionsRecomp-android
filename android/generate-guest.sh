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

# ReXGlue's XEX loader discovers the title update as <exact xex path> + "p".
# Preserve the XEX filename's case: on Linux Default.xex requires Default.xexp.
GAME_XEXP="${BASE_XEX}p"
if [[ "$(cd "$(dirname "$TU_XEXP")" && pwd)/$(basename "$TU_XEXP")" != "$GAME_XEXP" ]]; then
  echo "Installing TU23 executable patch beside Default.xex..."
  cp -f "$TU_XEXP" "$GAME_XEXP"
fi

"$ROOT/android/prepare-rexglue.sh"

CODEGEN="${REXGLUE_CODEGEN:-}"

# Unless the caller explicitly provides a codegen binary, build the CLI from
# this exact patched SDK checkout. Reusing an arbitrary older rexglue binary
# can generate desktop-era PCH/templates and only fail after a long codegen.
if [[ -z "$CODEGEN" ]]; then
  echo "Building/updating host ReXGlue CLI from the patched SDK tree..."
  HOST_BUILD="$ROOT/android/.host-rexglue-build"
  HOST_CC="${CC:-clang}"
  HOST_CXX="${CXX:-clang++}"

  for tool in cmake ninja "$HOST_CC" "$HOST_CXX"; do
    if ! command -v "$tool" >/dev/null 2>&1; then
      echo "$tool is required to build the host ReXGlue codegen tool." >&2
      exit 5
    fi
  done

  HOST_CMAKE_STDLIB=()
  if [[ "$(uname -s)" == "Linux" ]]; then
    # Clang 18 + Ubuntu's libstdc++ combination hides std::expected because of
    # feature-test macro differences. ReXGlue already uses libc++ on Android,
    # so use libc++ for the native codegen CLI too.
    if ! printf '#include <expected>\nint main(){std::expected<int,int> x=1;return *x;}\n' |
         "$HOST_CXX" -std=c++23 -stdlib=libc++ -x c++ - -fsyntax-only >/dev/null 2>&1; then
      cat >&2 <<'EOF'
Clang libc++ development headers are required for the Linux host codegen build.
On Ubuntu install, for example:
  sudo apt install libc++-18-dev libc++abi-18-dev libx11-xcb-dev libwayland-dev
EOF
      exit 5
    fi
    HOST_CMAKE_STDLIB+=(
      "-DCMAKE_CXX_FLAGS=-stdlib=libc++"
      "-DCMAKE_EXE_LINKER_FLAGS=-stdlib=libc++"
      "-DCMAKE_SHARED_LINKER_FLAGS=-stdlib=libc++"
    )
  fi

  cmake -S "$ROOT/rexglue-sdk" -B "$HOST_BUILD" -G Ninja \
    "${HOST_CMAKE_STDLIB[@]}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_COMPILER="$HOST_CC" \
    -DCMAKE_CXX_COMPILER="$HOST_CXX" \
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
On Linux the ReXGlue UI dependency also needs x11-xcb and Wayland development
packages (for Ubuntu: libx11-xcb-dev libwayland-dev).

You can point at a known-good patched host build explicitly:
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
