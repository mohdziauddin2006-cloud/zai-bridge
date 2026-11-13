cmake_minimum_required(VERSION 3.22.1)
project(zai_bridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# =============================================================================
# Unicorn execution backend (statically linked)
#
# arm-softmmu is the only guest compiled; the host half is whatever 64-bit ABI
# the APK is being built for. Each ABI needs its own archive at
# prebuilt/<abi>/lib/libunicorn.a, produced by vendor/build_unicorn_android.sh.
# =============================================================================
if(NOT ANDROID_ABI)
    set(ANDROID_ABI "arm64-v8a")
endif()

set(UNICORN_PREFIX  "${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}")
set(UNICORN_ARCHIVE "${UNICORN_PREFIX}/lib/libunicorn.a")

if(NOT EXISTS "${UNICORN_ARCHIVE}")
    message(FATAL_ERROR
        "libunicorn.a not found at ${UNICORN_ARCHIVE}\n"
        "Stage the ARM32-guest Unicorn build for ${ANDROID_ABI} "
        "(UNICORN_ABI=${ANDROID_ABI} ./vendor/build_unicorn_android.sh).")
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
add_library(wrapper SHARED
    wrapper.cpp
)

target_link_libraries(wrapper PRIVATE
    unicorn_core
    android
    log
    dl
    m
)

# Unicorn's internal (TCG / softmmu) symbols must stay out of the exported
# dynamic symbol table: only JNI_OnLoad and the JNI thunks should be exported.
target_link_options(wrapper PRIVATE "-Wl,--exclude-libs,ALL")
