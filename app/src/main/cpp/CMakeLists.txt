cmake_minimum_required(VERSION 3.22.1)
project(zai_bridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

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
    )

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
