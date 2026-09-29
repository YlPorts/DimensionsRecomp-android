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

if(NOT EXISTS "${REX_PORT_DIR}/generated/default/sources.cmake")
    message(FATAL_ERROR
        "Generated recompilation sources are missing at rexlego/generated/default. "
        "Generate them from your own LEGO Dimensions Xbox 360 dump/TU23 first; "
        "see README-dev.md and android/README.md.")
endif()

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Android is Vulkan-only. Keep diagnostics lean for the first boot.
set(REXGLUE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(REXGLUE_ENABLE_TRACY OFF CACHE BOOL "" FORCE)
set(REXGLUE_ENABLE_FIDELITYFX OFF CACHE BOOL "" FORCE)
set(REXGLUE_USE_D3D12 OFF CACHE BOOL "" FORCE)
set(REXGLUE_USE_VULKAN ON CACHE BOOL "" FORCE)

if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_compile_options(-O3 -DNDEBUG)
    add_link_options(-Wl,--gc-sections -Wl,--as-needed)
endif()

add_subdirectory("${REXSDK_DIR}" rexglue-sdk)

# ---------------------------------------------------------------------------
# Recompiled Xbox 360 guest code.
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

add_library(legodimensions_recomp_android STATIC ${REX_GEN_SOURCES})
target_include_directories(legodimensions_recomp_android PRIVATE
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
    ${LEGODIMENSIONS_HOST_SOURCES}
    "${REXSDK_DIR}/src/ui/rex_app.cpp"
)

target_include_directories(main PRIVATE
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
