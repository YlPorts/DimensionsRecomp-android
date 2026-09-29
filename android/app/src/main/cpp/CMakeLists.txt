cmake_minimum_required(VERSION 3.25)
project(dimensions_android LANGUAGES C CXX ASM)

set(REX_PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." CACHE PATH
    "DimensionsRecomp checkout")
set(REXSDK_DIR "${REX_PROJECT_ROOT}/rexglue-sdk" CACHE PATH
    "Android-patched ReXGlue SDK")
set(REX_PORT_DIR "${REX_PROJECT_ROOT}/rexlego" CACHE PATH
    "Dimensions Recompiled project")

if(NOT EXISTS "${REXSDK_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "ReXGlue SDK is missing. Run: git submodule update --init --recursive")
endif()

option(REX_ANDROID_HOST_SMOKE
    "Build the Android host against a generated-code stub (CI compile/link check only)" OFF)

if(NOT REX_ANDROID_HOST_SMOKE AND
   NOT EXISTS "${REX_PORT_DIR}/generated/default/sources.cmake")
    message(FATAL_ERROR
        "Generated recompilation sources are missing at rexlego/generated/default. "
        "Generate them from your own LEGO Dimensions Xbox 360 dump/TU23 first; "
        "see README-dev.md and android/README.md.")
endif()

# Android/ARM64 needs generated code produced by the patched codegen templates:
# PPC sync/lwsync/eieio must become real host fences, and the physical-memory
# host offset is resolved dynamically for Android page layouts. A desktop-era
# generated tree may compile successfully and then fail nondeterministically,
# so reject it early rather than shipping a subtly incorrect APK.
if(NOT REX_ANDROID_HOST_SMOKE)
    set(REX_ANDROID_PCH "${REX_PORT_DIR}/generated/default/legodimensions_pch.h")
    if(NOT EXISTS "${REX_ANDROID_PCH}")
        message(FATAL_ERROR
            "Missing generated/default/legodimensions_pch.h. "
            "Run android/prepare-rexglue.sh before regenerating the game.")
    endif()
    file(READ "${REX_ANDROID_PCH}" REX_ANDROID_PCH_TEXT)
    string(FIND "${REX_ANDROID_PCH_TEXT}" "g_guest_phys_host_offset"
           REX_ANDROID_PHYS_OFFSET_MARKER)
    string(FIND "${REX_ANDROID_PCH_TEXT}" "REX_SYNC_FENCE"
           REX_ANDROID_FENCE_MARKER)
    if(REX_ANDROID_PHYS_OFFSET_MARKER EQUAL -1 OR
       REX_ANDROID_FENCE_MARKER EQUAL -1)
        message(FATAL_ERROR
            "The generated Dimensions sources predate the Android ARM64 codegen fixes. "
            "Run ./android/prepare-rexglue.sh, then regenerate rexlego/generated/default "
            "from your own patched TU23 dump (force codegen / remove the old codegen stamp).")
    endif()
endif()

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Android is Vulkan-only. Keep diagnostics lean for the first boot.
set(REXGLUE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(REXGLUE_ENABLE_TRACY OFF CACHE BOOL "" FORCE)
set(REXGLUE_ENABLE_FIDELITYFX OFF CACHE BOOL "" FORCE)
set(REXGLUE_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(REXGLUE_USE_D3D12 OFF CACHE BOOL "" FORCE)
set(REXGLUE_USE_VULKAN ON CACHE BOOL "" FORCE)

if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_compile_options(-O3 -DNDEBUG)
    add_link_options(-Wl,--gc-sections -Wl,--as-needed)
endif()

add_subdirectory("${REXSDK_DIR}" rexglue-sdk)

# ---------------------------------------------------------------------------
# Recompiled Xbox 360 guest code.
#
# The real build consumes the machine-generated sources created from the
# player's own game dump. CI also has a host-smoke mode: it generates a tiny
# PPCImageConfig stub so every Android-specific host source can be compiled and
# linked without committing copyrighted/generated game code.
if(REX_ANDROID_HOST_SMOKE)
    set(REX_ANDROID_GENERATED_ROOT "${CMAKE_CURRENT_BINARY_DIR}/host-smoke/rexlego")
    set(REX_GEN_DIR "${REX_ANDROID_GENERATED_ROOT}/generated/default")
    file(MAKE_DIRECTORY "${REX_GEN_DIR}")

    file(WRITE "${REX_GEN_DIR}/legodimensions_init.h" [=[
#pragma once
#include <rex/image_info.h>
extern const rex::PPCImageInfo PPCImageConfig;
]=])

    file(WRITE "${REX_GEN_DIR}/legodimensions_pch.h" [=[
#pragma once
#include <rex/image_info.h>
]=])

    file(WRITE "${REX_GEN_DIR}/legodimensions_stub.cpp" [=[
#include "legodimensions_init.h"
#include <rex/ppc/context.h>

const rex::PPCImageInfo PPCImageConfig{};

// The real generated guest library supplies these symbols. Host smoke mode
// links without copyrighted/generated guest code, so provide weak no-op
// fallbacks for every guest entry referenced directly by the handwritten
// Dimensions host sources. Strong hook definitions in the host override these.
#define DIMENSIONS_SMOKE_GUEST_STUB(name) \
  extern "C" __attribute__((weak)) void name(PPCContext&, uint8_t*) {}

DIMENSIONS_SMOKE_GUEST_STUB(__imp__sub_82A3D980);
DIMENSIONS_SMOKE_GUEST_STUB(__imp__sub_82F03C40);
DIMENSIONS_SMOKE_GUEST_STUB(__imp__sub_82FBE7A8);
DIMENSIONS_SMOKE_GUEST_STUB(__imp__sub_82FE4A80);
DIMENSIONS_SMOKE_GUEST_STUB(__imp__sub_83470AF8);
DIMENSIONS_SMOKE_GUEST_STUB(__imp__sub_834B8168);
DIMENSIONS_SMOKE_GUEST_STUB(__imp__sub_836F5AD0);
DIMENSIONS_SMOKE_GUEST_STUB(__imp__sub_83715F00);

DIMENSIONS_SMOKE_GUEST_STUB(sub_82A3D980);
DIMENSIONS_SMOKE_GUEST_STUB(sub_82A435C8);
DIMENSIONS_SMOKE_GUEST_STUB(sub_82D70708);
DIMENSIONS_SMOKE_GUEST_STUB(sub_82EF9560);
DIMENSIONS_SMOKE_GUEST_STUB(sub_82EFEFA0);
DIMENSIONS_SMOKE_GUEST_STUB(sub_82F03C40);
DIMENSIONS_SMOKE_GUEST_STUB(sub_82F0D340);
DIMENSIONS_SMOKE_GUEST_STUB(sub_82FBE7A8);
DIMENSIONS_SMOKE_GUEST_STUB(sub_82FE4A80);
DIMENSIONS_SMOKE_GUEST_STUB(sub_83470AF8);
DIMENSIONS_SMOKE_GUEST_STUB(sub_834B8168);
DIMENSIONS_SMOKE_GUEST_STUB(sub_836F5AD0);
DIMENSIONS_SMOKE_GUEST_STUB(sub_83715F00);
DIMENSIONS_SMOKE_GUEST_STUB(sub_83835180);
DIMENSIONS_SMOKE_GUEST_STUB(sub_83835188);
DIMENSIONS_SMOKE_GUEST_STUB(sub_83A0AB68);

#undef DIMENSIONS_SMOKE_GUEST_STUB
]=])

    set(REX_GEN_SOURCES "${REX_GEN_DIR}/legodimensions_stub.cpp")
else()
    set(REX_ANDROID_GENERATED_ROOT "${REX_PORT_DIR}")
    set(REX_GEN_DIR "${REX_PORT_DIR}/generated/default")
    include("${REX_GEN_DIR}/sources.cmake")

    set(REX_GEN_SOURCES "")
    foreach(src ${GENERATED_SOURCES})
        if(IS_ABSOLUTE "${src}")
            list(APPEND REX_GEN_SOURCES "${src}")
        else()
            list(APPEND REX_GEN_SOURCES "${REX_GEN_DIR}/${src}")
        endif()
    endforeach()
endif()

add_library(legodimensions_recomp_android STATIC ${REX_GEN_SOURCES})
target_include_directories(legodimensions_recomp_android PRIVATE
    "${REX_ANDROID_GENERATED_ROOT}"
    "${REX_PORT_DIR}"
    "${REX_PORT_DIR}/src"
    "${REX_GEN_DIR}"
)
target_precompile_headers(legodimensions_recomp_android PRIVATE
    "${REX_GEN_DIR}/legodimensions_pch.h"
)
target_compile_options(legodimensions_recomp_android PRIVATE
    -g0
    -O3
    -fno-rtti
    -Wno-unused-variable
    -Wno-unused-label
    -Wno-unused-but-set-variable
    -Wno-unused-value
)
set_target_properties(legodimensions_recomp_android PROPERTIES
    INTERPROCEDURAL_OPTIMIZATION OFF
)
target_link_libraries(legodimensions_recomp_android PUBLIC rex::runtime rexui)

# ---------------------------------------------------------------------------
# Android host application. Keep the game-specific host code intact wherever
# it is already cross-platform; Windows-only features are guarded in-source.
set(LEGODIMENSIONS_HOST_SOURCES
    "${REX_PORT_DIR}/src/main.cpp"
    "${REX_PORT_DIR}/src/discord_presence.cpp"
    "${REX_PORT_DIR}/src/ui_theme.cpp"
    "${REX_PORT_DIR}/src/mod_menu.cpp"
    "${REX_PORT_DIR}/src/main_menu_extras.cpp"
    "${REX_PORT_DIR}/src/skip_cutscenes.cpp"
    "${REX_PORT_DIR}/src/cheat_engine.cpp"
    "${REX_PORT_DIR}/src/cheat_menu.cpp"
    "${REX_PORT_DIR}/src/toypad_app.cpp"
    "${REX_PORT_DIR}/src/updates.cpp"
    "${REX_PORT_DIR}/src/name_trace.cpp"
)

add_library(main SHARED
    android_main.cpp
    android_gamepad.cpp
    ${LEGODIMENSIONS_HOST_SOURCES}
    "${REXSDK_DIR}/src/ui/rex_app.cpp"
)

target_include_directories(main PRIVATE
    "${REX_ANDROID_GENERATED_ROOT}"
    "${REX_PORT_DIR}"
    "${REX_PORT_DIR}/src"
    "${REX_GEN_DIR}"
    "${REXSDK_DIR}/include"
    "${REXSDK_DIR}/src/ui"
)
target_compile_definitions(main PRIVATE
    REXGLUE_BUILD_CONFIG="$<CONFIG>"
    REX_ANDROID_DIMENSIONS=1
)
target_compile_options(main PRIVATE -Wno-unused-variable)
target_link_libraries(main PRIVATE
    legodimensions_recomp_android
    rex::runtime
    rexui
    android
    log
    dl
)

# The Xenos renderer is runtime-loaded through --gpu_plugin=xenos.
add_dependencies(main rexgpu-xenos)
target_link_options(main PRIVATE -Wl,--export-dynamic)
set_target_properties(main PROPERTIES
    CXX_VISIBILITY_PRESET default
    VISIBILITY_INLINES_HIDDEN OFF
)
