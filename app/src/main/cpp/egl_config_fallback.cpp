// =============================================================================
//  egl_config_fallback.cpp
// =============================================================================

#include "egl_config_fallback.h"

#include <cstddef>

namespace {

bool find_key(const std::vector<EGLint>& list, EGLint key, size_t* at) {
    for (size_t i = 0; i + 1 < list.size(); i += 2) {
        if (list[i] == key) {
            if (at != nullptr) *at = i;
            return true;
        }
    }
    return false;
}

bool get_value(const std::vector<EGLint>& list, EGLint key, EGLint* value) {
    size_t at = 0;
    if (!find_key(list, key, &at)) return false;
    if (value != nullptr) *value = list[at + 1];
    return true;
}

void put_value(std::vector<EGLint>& list, EGLint key, EGLint value) {
    size_t at = 0;
    if (find_key(list, key, &at)) {
        list[at + 1] = value;
        return;
    }
    // Insert before the EGL_NONE terminator.
    if (!list.empty() && list.back() == EGL_NONE) {
        list.insert(list.end() - 1, {key, value});
    } else {
        list.push_back(key);
        list.push_back(value);
        list.push_back(EGL_NONE);
    }
}

void remove_key(std::vector<EGLint>& list, EGLint key) {
    size_t at = 0;
    if (find_key(list, key, &at)) {
        list.erase(list.begin() + static_cast<std::ptrdiff_t>(at),
                   list.begin() + static_cast<std::ptrdiff_t>(at + 2));
    }
}

EGLint renderable_of(const std::vector<EGLint>& list) {
    EGLint renderable = EGL_OPENGL_ES2_BIT;
    get_value(list, EGL_RENDERABLE_TYPE, &renderable);
    return renderable;
}

// Attempt 1: keep every non-colour requirement, but ask for a modern 8888
// colour buffer with a real depth attachment. This is the request every driver
// on sale implements; it converts "no config" into a working surface.
std::vector<EGLint> relaxed_color(const std::vector<EGLint>& in) {
    std::vector<EGLint> out = in;
    put_value(out, EGL_RED_SIZE, 8);
    put_value(out, EGL_GREEN_SIZE, 8);
    put_value(out, EGL_BLUE_SIZE, 8);
    put_value(out, EGL_ALPHA_SIZE, 8);
    put_value(out, EGL_BUFFER_SIZE, 32);
    EGLint depth = 24;
    if (!get_value(out, EGL_DEPTH_SIZE, &depth) || depth < 16) depth = 24;
    put_value(out, EGL_DEPTH_SIZE, depth);
    // The config must be usable as a window, otherwise a game that also
    // renders to an FBO still cannot present.
    put_value(out, EGL_SURFACE_TYPE, EGL_WINDOW_BIT);
    return out;
}

// Attempt 2: drop the requirements that genuinely do not exist on every
// driver (alpha channel, multisampling, stencil) while keeping the depth
// buffer a legacy game's depth-tested scene needs.
std::vector<EGLint> relaxed_depth(const std::vector<EGLint>& in) {
    std::vector<EGLint> out = in;
    remove_key(out, EGL_SAMPLE_BUFFERS);
    remove_key(out, EGL_SAMPLES);
    remove_key(out, EGL_ALPHA_SIZE);
    remove_key(out, EGL_STENCIL_SIZE);
    remove_key(out, EGL_TRANSPARENT_TYPE);
    remove_key(out, EGL_TRANSPARENT_RED_VALUE);
    remove_key(out, EGL_TRANSPARENT_GREEN_VALUE);
    remove_key(out, EGL_TRANSPARENT_BLUE_VALUE);
    remove_key(out, EGL_BIND_TO_TEXTURE_RGB);
    remove_key(out, EGL_BIND_TO_TEXTURE_RGBA);
    put_value(out, EGL_RED_SIZE, 8);
    put_value(out, EGL_GREEN_SIZE, 8);
    put_value(out, EGL_BLUE_SIZE, 8);
    put_value(out, EGL_DEPTH_SIZE, 16);
    put_value(out, EGL_SURFACE_TYPE, EGL_WINDOW_BIT);
    EGLint renderable = renderable_of(in);
    if (renderable == 0) renderable = EGL_OPENGL_ES2_BIT;
    put_value(out, EGL_RENDERABLE_TYPE, renderable);
    put_value(out, EGL_CONFIG_CAVEAT, EGL_NONE);
    return out;
}

// Attempt 3: the smallest request that produces a window-renderable config for
// the API the game bound. Everything optional is gone.
std::vector<EGLint> minimal(const std::vector<EGLint>& in) {
    std::vector<EGLint> out;
    EGLint renderable = renderable_of(in);
    if (renderable == 0) renderable = EGL_OPENGL_ES2_BIT;
    out.push_back(EGL_SURFACE_TYPE);
    out.push_back(EGL_WINDOW_BIT);
    out.push_back(EGL_RENDERABLE_TYPE);
    out.push_back(renderable);
    out.push_back(EGL_RED_SIZE);
    out.push_back(8);
    out.push_back(EGL_GREEN_SIZE);
    out.push_back(8);
    out.push_back(EGL_BLUE_SIZE);
    out.push_back(8);
    out.push_back(EGL_DEPTH_SIZE);
    out.push_back(16);
    out.push_back(EGL_NONE);
    return out;
}

std::vector<EGLint> any_window_es(const std::vector<EGLint>& in) {
    std::vector<EGLint> out;
    EGLint renderable = renderable_of(in);
    if (renderable == 0) renderable = EGL_OPENGL_ES2_BIT;
    out.push_back(EGL_SURFACE_TYPE);
    out.push_back(EGL_WINDOW_BIT);
    out.push_back(EGL_RENDERABLE_TYPE);
    out.push_back(renderable);
    out.push_back(EGL_NONE);
    return out;
}

std::vector<EGLint> any_window() {
    return { EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_NONE };
}

std::vector<EGLint> any_config() {
    return { EGL_NONE };
}

}  // namespace

namespace zai {
namespace eglc {

EGLBoolean choose_config(ChooseConfigFn fn, EGLDisplay display,
                         const std::vector<EGLint>& attribs, EGLConfig* configs,
                         EGLint want, EGLint* num, int* strategy) {
    if (strategy != nullptr) *strategy = 0;
    if (num != nullptr) *num = 0;
    if (fn == nullptr) return EGL_FALSE;

    EGLConfig* out_configs = (want > 0) ? configs : nullptr;
    EGLint out_want = (out_configs != nullptr) ? want : 0;

    std::vector<EGLint> request = attribs;
    if (request.empty() || request.back() != EGL_NONE) request.push_back(EGL_NONE);

    EGLint count = 0;
    EGLBoolean ok = fn(display, request.data(), out_configs, out_want, &count);
    if (ok == EGL_TRUE && count > 0) {
        if (num != nullptr) *num = count;
        return EGL_TRUE;
    }

    const std::vector<EGLint> attempts[] = {
            relaxed_color(request),
            relaxed_depth(request),
            minimal(request),
            any_window_es(request),
            any_window(),
            any_config(),
    };

    for (int i = 0; i < 6; ++i) {
        count = 0;
        ok = fn(display, attempts[i].data(), out_configs, out_want, &count);
        if (ok == EGL_TRUE && count > 0) {
            if (num != nullptr) *num = count;
            if (strategy != nullptr) *strategy = i + 1;
            return EGL_TRUE;
        }
    }

    if (num != nullptr) *num = 0;
    if (strategy != nullptr) *strategy = -1;
    return EGL_FALSE;
}

const char* strategy_name(int strategy) {
    switch (strategy) {
        case 0: return "original";
        case 1: return "RGBA8888 + depth24";
        case 2: return "depth16, no alpha/aa";
        case 3: return "minimal window RGBA8888";
        case 4: return "any window ES";
        case 5: return "any window";
        case 6: return "any config";
        default: return "no config";
    }
}

}  // namespace eglc
}  // namespace zai
