cmake_minimum_required(VERSION 3.22.1)
project(zai_bridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Diagnostic tracing (per-block wild-branch guard, guest byte windows, guest
# svc/import dumps). Off by default: the release build carries no trace code.
option(ZAI_VERBOSE_TRACE "Emit Zai-Bridge guest diagnostic tracing" OFF)

# Ensure 16 KB page-size compatibility for Android 15/16
add_link_options("-Wl,-z,max-page-size=16384")

# =============================================================================
# One APK, four ABIs, two backends.
#
#   arm64-v8a / x86_64  -> wrapper.cpp, the Unicorn ARM32 translation core,
#                          linked against prebuilt/<abi>/lib/libunicorn.a
#   armeabi-v7a / x86   -> wrapper_passthrough.cpp, no translation: the guest
#                          payload is already the process architecture
#
# Both variants export the same JNI surface, so UnicornBridge never branches.
#
# Shared by both backends:
#   egl_swap_hook.cpp     Java EGL/GLES boundary: compositor, config fallback,
#                         GLSL patcher, extension negotiation
#   native_egl_hook.cpp   in-process PLT/GOT interposition of eglSwapBuffers,
#                         glBindFramebuffer and glViewport for 64-bit games
#                         that link libEGL/libGLESv2 directly
#   vulkan_hook.cpp       vkQueuePresentKHR passthrough + live telemetry
#   gpu_compositor.cpp    SGSR/FSR upscaling + 2-8x optical-flow frame generation
#   cpu_compat.cpp        universal ARMv7/NEON reporting, big.LITTLE pinning
#   gl_shader_patcher.cpp legacy GLSL normalisation
#   egl_config_fallback.cpp  driver-agnostic eglChooseConfig negotiation
# =============================================================================
if(NOT ANDROID_ABI)
    set(ANDROID_ABI "arm64-v8a")
endif()

set(ZAI_COMMON_SOURCES
    egl_swap_hook.cpp
    native_egl_hook.cpp
    vulkan_hook.cpp
    gpu_compositor.cpp
    cpu_compat.cpp
    gl_shader_patcher.cpp
    egl_config_fallback.cpp
)

set(ZAI_TRANSLATING_ABI FALSE)
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    set(ZAI_TRANSLATING_ABI TRUE)
endif()

if(ZAI_TRANSLATING_ABI)
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

    # The bridge: ELF loader + ARM32 emulation + JNI translation layer. The
    # payloads it maps are read per session, never compiled in.
    add_library(wrapper SHARED
        wrapper.cpp
        ${ZAI_COMMON_SOURCES}
    )

    target_link_libraries(wrapper PRIVATE
        unicorn_core
        android
        log
        dl
        m
        EGL
        GLESv2
        GLESv3
        OpenSLES
        # The guest opens its own APK as a zip: inflate/inflateInit_ are guest
        # imports the core has to serve, and the host copy of zlib is what
        # actually decompresses the entries.
        z
    )

    if(ZAI_VERBOSE_TRACE)
        target_compile_definitions(wrapper PRIVATE ZAI_VERBOSE_TRACE)
        message(STATUS "zai-bridge: ZAI_VERBOSE_TRACE enabled")
    endif()

    # Unicorn's internal (TCG / softmmu) symbols must stay out of the exported
    # dynamic symbol table: only JNI_OnLoad and the JNI thunks should be exported.
    target_link_options(wrapper PRIVATE "-Wl,--exclude-libs,ALL")
else()
    # The same EGL/GLES compatibility and compositor code runs on a 32-bit
    # host, where the guest payload is the process architecture.
    add_library(wrapper SHARED
        wrapper_passthrough.cpp
        ${ZAI_COMMON_SOURCES}
    )
    target_link_libraries(wrapper PRIVATE
        android
        log
        dl
        m
        EGL
        GLESv2
        GLESv3
    )
    message(STATUS "zai-bridge: ${ANDROID_ABI} uses the native passthrough backend")
endif()

# =============================================================================
# Guest alias shim — staged as lib<name>.so for the guest's System.loadLibrary.
#
# The guest dex loader used to stage a byte copy of libwrapper.so for every
# library name the guest asked for. ART accepts that (each name is a distinct
# path) but it produces a second runtime instance, so the framework's
# ANativeActivity_onCreate landed in a copy whose session state was empty and
# NativeActivity titles never started. This shim is tiny, links against nothing
# but libc, and forwards every entry point to the single live libwrapper.so.
#
# It is not a DT_NEEDED dependency on purpose: the alias directory is the guest
# class loader's library search path, where libwrapper.so need not be
# resolvable. The shim resolves the resident instance at runtime instead and
# falls back to the /proc/self/maps path.
# =============================================================================
add_library(bridge_alias_shim SHARED alias_shim.cpp)
target_link_libraries(bridge_alias_shim PRIVATE dl log)
target_compile_options(bridge_alias_shim PRIVATE -Wall -Wextra)
# The shim is plain C: it must not acquire a DT_NEEDED on libc++_shared.so, which
# the guest class loader's linker namespace cannot resolve (that namespace only
# sees this alias directory). Without -nostdlib++ the NDK adds the C++ runtime to
# every C++ target, and ART rejects the alias with "library libc++_shared.so not
# found: needed by .../native/libmain.so".
target_link_options(bridge_alias_shim PRIVATE -nostdlib++)
set_target_properties(bridge_alias_shim PROPERTIES
    OUTPUT_NAME "bridge_alias_shim"
    POSITION_INDEPENDENT_CODE ON
)
