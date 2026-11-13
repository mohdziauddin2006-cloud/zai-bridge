cmake_minimum_required(VERSION 3.22.1)
project(zai_bridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# =============================================================================
# Unicorn execution backend (statically linked)
#
# The core is cross-compiled for the Android NDK as ARM32/Thumb guest only and
# staged at prebuilt/<abi>/lib/libunicorn.a with its headers beside it. Only
# arm64-v8a is shipped: the host process is the 64-bit side of the bridge.
# =============================================================================
if(NOT ANDROID_ABI)
    set(ANDROID_ABI "arm64-v8a")
endif()

set(UNICORN_PREFIX  "${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}")
set(UNICORN_ARCHIVE "${UNICORN_PREFIX}/lib/libunicorn.a")

if(NOT EXISTS "${UNICORN_ARCHIVE}")
    message(FATAL_ERROR
        "libunicorn.a not found at ${UNICORN_ARCHIVE}\n"
        "Stage the ARM32-guest Unicorn build for ${ANDROID_ABI} before building.")
endif()

add_library(unicorn_core STATIC IMPORTED)
set_target_properties(unicorn_core PROPERTIES
    IMPORTED_LOCATION             "${UNICORN_ARCHIVE}"
    INTERFACE_INCLUDE_DIRECTORIES "${UNICORN_PREFIX}/include"
)

# =============================================================================
# The bridge: ELF loader + ARM32 emulation + JNI translation layer.
#
# The payloads it maps are not compiled in and not fixed at build time; they are
# read per session from the ingested game's private directory.
# =============================================================================
add_library(wrapper_arm64 SHARED
    wrapper_arm64.cpp
)

target_link_libraries(wrapper_arm64 PRIVATE
    unicorn_core
    android
    log
    dl
    m
)

# Unicorn's internal (TCG / softmmu) symbols must stay out of the exported
# dynamic symbol table: only JNI_OnLoad and the JNI thunks should be exported.
target_link_options(wrapper_arm64 PRIVATE "-Wl,--exclude-libs,ALL")
