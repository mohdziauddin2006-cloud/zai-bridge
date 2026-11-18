// =============================================================================
//  gpu_compositor.cpp  (Zai-Bridge offscreen upscale / frame-gen compositor)
//
//  Everything here runs on the guest's own GL thread, inside the swap
//  boundary. No context is created: the compositor borrows the guest's current
//  context, draws entirely with core GLES2 objects (FBO + RGBA8 textures), and
//  restores the state it touched before returning control.
//
//  Shaders are written in GLSL ES 1.00 and promoted to `#version 300 es` by
//  gl_shader_patcher when the host context is GLES3, which keeps one shader
//  body compiling on every driver generation.
// =============================================================================

#include "gpu_compositor.h"

#include "gl_shader_patcher.h"

#include <GLES3/gl3.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <thread>

#define LOG_TAG "ARM64_WRAPPER"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace zai {
namespace {

// ---------------------------------------------------------------------------
// Shader bodies (GLSL ES 1.00; promoted to 300 es on GLES3 hosts)
// ---------------------------------------------------------------------------
const char* const kVertexShader = R"GLSL(
attribute vec2 aPos;
attribute vec2 aUV;
varying vec2 vUV;
void main() {
    vUV = aUV;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
)GLSL";

// SGSR: single-pass 12-tap edge-adaptive reconstruction with directional
// sharpening. No motion vectors, no depth, no history buffer.
const char* const kSgsrShader = R"GLSL(
precision mediump float;
varying vec2 vUV;
uniform sampler2D uTex;
uniform vec2 uTexSize;
uniform float uSharpness;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void main() {
    vec2 t = uTexSize;
    vec3 c = texture2D(uTex, vUV).rgb;
    float lc = luma(c);

    vec3 n  = texture2D(uTex, vUV + vec2( 0.0, -1.0) * t).rgb;
    vec3 s  = texture2D(uTex, vUV + vec2( 0.0,  1.0) * t).rgb;
    vec3 e  = texture2D(uTex, vUV + vec2( 1.0,  0.0) * t).rgb;
    vec3 w  = texture2D(uTex, vUV + vec2(-1.0,  0.0) * t).rgb;

    vec3 nw = texture2D(uTex, vUV + vec2(-0.7, -0.7) * t).rgb;
    vec3 ne = texture2D(uTex, vUV + vec2( 0.7, -0.7) * t).rgb;
    vec3 sw = texture2D(uTex, vUV + vec2(-0.7,  0.7) * t).rgb;
    vec3 se = texture2D(uTex, vUV + vec2( 0.7,  0.7) * t).rgb;

    vec3 nn = texture2D(uTex, vUV + vec2( 0.0, -1.9) * t).rgb;
    vec3 ss = texture2D(uTex, vUV + vec2( 0.0,  1.9) * t).rgb;
    vec3 ee = texture2D(uTex, vUV + vec2( 1.9,  0.0) * t).rgb;
    vec3 ww = texture2D(uTex, vUV + vec2(-1.9,  0.0) * t).rgb;

    // Edge-adaptive Lanczos-like weighting: the sharper the local luminance
    // step, the less the tap contributes, so edges stay crisp instead of
    // being smeared by the wider kernels.
    vec3 acc = c * 1.25;
    float wsum = 1.25;

#define ZB_EA(sample, coeff)                       \
    {                                              \
        float k = (coeff) / (1.0 + 18.0 * abs(luma(sample) - lc)); \
        acc += (sample) * k;                       \
        wsum += k;                                 \
    }
    ZB_EA(n, 1.00)
    ZB_EA(s, 1.00)
    ZB_EA(e, 1.00)
    ZB_EA(w, 1.00)
    ZB_EA(nw, 0.75)
    ZB_EA(ne, 0.75)
    ZB_EA(sw, 0.75)
    ZB_EA(se, 0.75)
    ZB_EA(nn, 0.42)
    ZB_EA(ss, 0.42)
    ZB_EA(ee, 0.42)
    ZB_EA(ww, 0.42)
#undef ZB_EA

    vec3 base = acc / wsum;

    // Sharpen along the axis with the smallest gradient (the flat direction),
    // which avoids ringing on a hard diagonal edge.
    float gx = abs(luma(e) - luma(w));
    float gy = abs(luma(n) - luma(s));
    vec3 detail = (gx < gy) ? (2.0 * c - e - w) : (2.0 * c - n - s);
    vec3 outColor = base + uSharpness * 0.55 * detail;
    gl_FragColor = vec4(clamp(outColor, 0.0, 1.0), 1.0);
}
)GLSL";

// FSR 1.0 style: EASU directional reconstruction followed by an RCAS
// contrast-adaptive sharpen, in one pass.
const char* const kFsrShader = R"GLSL(
precision mediump float;
varying vec2 vUV;
uniform sampler2D uTex;
uniform vec2 uTexSize;
uniform float uSharpness;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void main() {
    vec2 t = uTexSize;
    vec3 c  = texture2D(uTex, vUV).rgb;
    vec3 n  = texture2D(uTex, vUV + vec2( 0.0, -1.0) * t).rgb;
    vec3 s  = texture2D(uTex, vUV + vec2( 0.0,  1.0) * t).rgb;
    vec3 e  = texture2D(uTex, vUV + vec2( 1.0,  0.0) * t).rgb;
    vec3 w  = texture2D(uTex, vUV + vec2(-1.0,  0.0) * t).rgb;
    vec3 nw = texture2D(uTex, vUV + vec2(-0.6, -0.6) * t).rgb;
    vec3 ne = texture2D(uTex, vUV + vec2( 0.6, -0.6) * t).rgb;
    vec3 sw = texture2D(uTex, vUV + vec2(-0.6,  0.6) * t).rgb;
    vec3 se = texture2D(uTex, vUV + vec2( 0.6,  0.6) * t).rgb;
    vec3 nn = texture2D(uTex, vUV + vec2( 0.0, -2.0) * t).rgb;
    vec3 ss = texture2D(uTex, vUV + vec2( 0.0,  2.0) * t).rgb;
    vec3 ee = texture2D(uTex, vUV + vec2( 2.0,  0.0) * t).rgb;
    vec3 ww = texture2D(uTex, vUV + vec2(-2.0,  0.0) * t).rgb;

    float lc = luma(c);
    float gx = abs(luma(e) - luma(w));
    float gy = abs(luma(n) - luma(s));
    float horizontal = gx / (gx + gy + 0.0001);

    // EASU: blend along the dominant edge more strongly than across it.
    vec3 along  = mix((nw + ne + sw + se) * 0.25, (nn + ss) * 0.5, horizontal);
    vec3 across = mix((nn + ss) * 0.5, (ee + ww) * 0.5, horizontal);
    vec3 easu = mix(c, along, 0.55);
    easu = mix(easu, across, 0.20 * (1.0 - abs(luma(along) - lc)));

    // RCAS: sharpen, then clamp to the local neighbourhood so no overshoot
    // halo is produced.
    vec3 mean4 = (n + s + e + w) * 0.25;
    vec3 sharpened = easu + mix(0.0, 0.85, uSharpness) * (easu - mean4);
    vec3 lo = min(min(min(n, s), min(e, w)), easu);
    vec3 hi = max(max(max(n, s), max(e, w)), easu);
    gl_FragColor = vec4(clamp(sharpened, lo, hi), 1.0);
}
)GLSL";

// ES 2.0 / GLES1 safety net: bilinear resolve plus a light unsharp mask.
const char* const kBilinearShader = R"GLSL(
precision mediump float;
varying vec2 vUV;
uniform sampler2D uTex;
uniform vec2 uTexSize;
uniform float uSharpness;

void main() {
    vec2 t = uTexSize;
    vec3 c = texture2D(uTex, vUV).rgb;
    vec3 blur = (texture2D(uTex, vUV + vec2(0.0, -1.0) * t).rgb +
                 texture2D(uTex, vUV + vec2(0.0,  1.0) * t).rgb +
                 texture2D(uTex, vUV + vec2(1.0,  0.0) * t).rgb +
                 texture2D(uTex, vUV + vec2(-1.0, 0.0) * t).rgb) * 0.25;
    gl_FragColor = vec4(clamp(c + uSharpness * 0.35 * (c - blur), 0.0, 1.0), 1.0);
}
)GLSL";

// Block matching. One output pixel per 8x8 block: RG = motion vector (+16)/32,
// B = residual, A = confidence. Zero-motion blocks (HUD, text, static UI) and
// scene cuts (residual above the threshold) are rejected by A.
const char* const kMotionShader = R"GLSL(
precision mediump float;
varying vec2 vUV;
uniform sampler2D uPrev;
uniform sampler2D uCurr;
uniform vec2 uBlockCount;
uniform vec2 uFrameSize;
uniform float uSearch;
uniform float uSad;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

float blockSum(sampler2D tex, vec2 uv, vec2 offset) {
    vec2 p = uv + offset * uFrameSize;
    vec2 q = 1.5 * uFrameSize;
    return luma(texture2D(tex, p + vec2(-1.0, -1.0) * q).rgb) +
           luma(texture2D(tex, p + vec2( 1.0, -1.0) * q).rgb) +
           luma(texture2D(tex, p + vec2(-1.0,  1.0) * q).rgb) +
           luma(texture2D(tex, p + vec2( 1.0,  1.0) * q).rgb);
}

float sadAt(vec2 uv, vec2 d) {
    float a = blockSum(uPrev, uv, -d);
    float b = blockSum(uCurr, uv,  d);
    return abs(a - b) * 0.25;
}

void main() {
    vec2 uv = (floor(vUV * uBlockCount) + 0.5) / uBlockCount;
    float best = sadAt(uv, vec2(0.0));
    vec2 motion = vec2(0.0);
    float r = uSearch;

#define ZB_CAND(dx, dy)                        \
    {                                          \
        vec2 d = vec2(dx, dy);                 \
        float s = sadAt(uv, d);                \
        if (s < best) { best = s; motion = d; } \
    }
    ZB_CAND( r,  0.0)
    ZB_CAND(-r,  0.0)
    ZB_CAND( 0.0,  r)
    ZB_CAND( 0.0, -r)
    ZB_CAND( r,   r)
    ZB_CAND(-r,   r)
    ZB_CAND( r,  -r)
    ZB_CAND(-r,  -r)
#undef ZB_CAND

    float confidence = 1.0 - smoothstep(uSad * 0.55, uSad, best);
    // No motion at all: HUD, subtitles, a pause overlay. Never warp these.
    if (best < 0.0022) { motion = vec2(0.0); }
    gl_FragColor = vec4((motion + 16.0) / 32.0, best, confidence);
}
)GLSL";

// Warp the pair halfway along the block's motion vector. Blocks whose warped
// samples disagree (occlusion, cut, wrong vector) fall back to a plain blend.
const char* const kSynthShader = R"GLSL(
precision mediump float;
varying vec2 vUV;
uniform sampler2D uPrev;
uniform sampler2D uCurr;
uniform sampler2D uMotion;
uniform vec2 uFrameSize;
uniform vec2 uBlockCount;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void main() {
    vec2 blockUV = (floor(vUV * uBlockCount) + 0.5) / uBlockCount;
    vec4 m = texture2D(uMotion, blockUV);
    vec2 motion = m.rg * 32.0 - 16.0;
    float confidence = m.a;

    vec2 halfStep = motion * 0.5 * uFrameSize;
    vec3 fromPrev = texture2D(uPrev, clamp(vUV - halfStep, 0.0, 1.0)).rgb;
    vec3 fromCurr = texture2D(uCurr, clamp(vUV + halfStep, 0.0, 1.0)).rgb;
    vec3 warped = (fromPrev + fromCurr) * 0.5;

    vec3 plain = (texture2D(uPrev, vUV).rgb + texture2D(uCurr, vUV).rgb) * 0.5;
    float disagreement = abs(luma(fromPrev) - luma(fromCurr));
    float weight = confidence * (1.0 - smoothstep(0.20, 0.45, disagreement));
    gl_FragColor = vec4(mix(plain, warped, weight), 1.0);
}
)GLSL";

// ---------------------------------------------------------------------------
// GL objects
// ---------------------------------------------------------------------------
struct Target {
    GLuint fbo = 0;
    GLuint tex = 0;
    GLuint depth = 0;
    int w = 0;
    int h = 0;
};

struct Program {
    GLuint id = 0;
};

struct GlState {
    GLint fbo = 0;
    GLint viewport[4] = {0, 0, 0, 0};
    GLint program = 0;
    GLint active_texture = GL_TEXTURE0;
    GLint tex0 = 0;
    GLint tex1 = 0;
    GLint tex2 = 0;
    GLint vao = 0;
    GLint array_buffer = 0;
    GLboolean blend = GL_FALSE;
    GLboolean depth = GL_FALSE;
    GLboolean scissor = GL_FALSE;
    GLboolean cull = GL_FALSE;
    GLboolean stencil = GL_FALSE;

    struct Attrib {
        GLint enabled = 0;
        GLint size = 4;
        GLint type = GL_FLOAT;
        GLint stride = 0;
        GLint normalized = 0;
        GLint buffer = 0;
        void* pointer = nullptr;
    } attribs[4];
};

const float kQuad[] = {
    // x, y, u, v
    -1.0f, -1.0f, 0.0f, 0.0f,
     1.0f, -1.0f, 1.0f, 0.0f,
    -1.0f,  1.0f, 0.0f, 1.0f,
     1.0f, -1.0f, 1.0f, 0.0f,
    -1.0f,  1.0f, 0.0f, 1.0f,
     1.0f,  1.0f, 1.0f, 1.0f,
};

int clamp_int(int value, int lo, int hi) {
    return value < lo ? lo : (value > hi ? hi : value);
}

}  // namespace

// ---------------------------------------------------------------------------
// Implementation
// ---------------------------------------------------------------------------
struct GpuCompositor::Impl {
    std::atomic<int> upscaler{kUpscalerOff};
    std::atomic<int> scale{100};
    std::atomic<int> sharpness{50};
    std::atomic<int> frame_gen{kFrameGenOff};
    std::atomic<bool> internal_bound{false};

    std::mutex mutex;                  // guards GL resource bookkeeping
    bool gl_ready = false;
    int gl_major = 0;
    std::string version;
    std::string renderer;
    std::string vendor;

    GLuint vao = 0;
    GLuint vbo = 0;
    Program sgsr;
    Program fsr;
    Program bilinear;
    Program motion;
    Program synth;

    Target frames[2];
    int current = 0;
    bool has_prev = false;
    Target internal;
    Target motion_target;
    Target intermediate;

    int display_w = 0;
    int display_h = 0;
    int source_w = 0;
    int source_h = 0;
    int blocks_w = 0;
    int blocks_h = 0;
    bool last_internal = false;

    uint64_t swaps = 0;
    uint64_t presented = 0;
    uint64_t generated = 0;
    int shader_failures = 0;
    std::string last_error;

    double interval_ms = 16.7;
    std::chrono::steady_clock::time_point last_frame{};
    std::chrono::steady_clock::time_point started = std::chrono::steady_clock::now();

    // ---- shader helpers ---------------------------------------------------
    GLuint compile(GLenum type, const char* body, const char* label) {
        const std::string header = gl_major >= 3 ? "#version 300 es\n"
                                                 : "#version 100\n";
        const zai::glsl::Patch patch = zai::glsl::patch_shader_source(
                header + body,
                type == GL_VERTEX_SHADER ? zai::glsl::Stage::Vertex
                                         : zai::glsl::Stage::Fragment,
                gl_major);

        GLuint shader = glCreateShader(type);
        if (shader == 0) {
            note_failure(std::string(label) + ": glCreateShader failed");
            return 0;
        }
        const char* source = patch.source.c_str();
        glShaderSource(shader, 1, &source, nullptr);
        glCompileShader(shader);

        GLint ok = GL_FALSE;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
        if (ok != GL_TRUE) {
            char log[768] = {0};
            GLsizei written = 0;
            glGetShaderInfoLog(shader, sizeof log - 1, &written, log);
            note_failure(std::string(label) + ": " + std::string(log, written > 0 ? written : 0));
            glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    Program link(const char* fragment_body, const char* label) {
        Program program;
        GLuint vs = compile(GL_VERTEX_SHADER, kVertexShader, "fullscreen.vert");
        GLuint fs = compile(GL_FRAGMENT_SHADER, fragment_body, label);
        if (vs == 0 || fs == 0) {
            if (vs) glDeleteShader(vs);
            if (fs) glDeleteShader(fs);
            return program;
        }
        GLuint id = glCreateProgram();
        glAttachShader(id, vs);
        glAttachShader(id, fs);
        glBindAttribLocation(id, 0, "aPos");
        glBindAttribLocation(id, 1, "aUV");
        glLinkProgram(id);
        glDeleteShader(vs);
        glDeleteShader(fs);

        GLint ok = GL_FALSE;
        glGetProgramiv(id, GL_LINK_STATUS, &ok);
        if (ok != GL_TRUE) {
            char log[768] = {0};
            GLsizei written = 0;
            glGetProgramInfoLog(id, sizeof log - 1, &written, log);
            note_failure(std::string(label) + ": link " + std::string(log, written > 0 ? written : 0));
            glDeleteProgram(id);
            return program;
        }
        program.id = id;
        LOGI("compositor: program '%s' compiled on GLES%d", label, gl_major);
        return program;
    }

    void note_failure(const std::string& reason) {
        ++shader_failures;
        last_error = reason;
        LOGE("compositor shader: %s", reason.c_str());
    }

    // ---- context ----------------------------------------------------------
    int detect_context() {
        const GLubyte* version = glGetString(GL_VERSION);
        if (version == nullptr) return 0;
        version = glGetString(GL_VERSION);
        this->version.assign(reinterpret_cast<const char*>(version));
        const GLubyte* renderer = glGetString(GL_RENDERER);
        if (renderer != nullptr) this->renderer.assign(reinterpret_cast<const char*>(renderer));
        const GLubyte* vendor = glGetString(GL_VENDOR);
        if (vendor != nullptr) this->vendor.assign(reinterpret_cast<const char*>(vendor));

        const char first = this->version.empty() ? '2' : this->version[0];
        const int major = (first >= '0' && first <= '9') ? first - '0' : 2;
        return major;   // GLES1 has no FBOs or shaders; callers reject it
    }

    // ---- targets ----------------------------------------------------------
    bool make_target(Target& target, int w, int h, bool with_depth,
                     bool nearest = false) {
        if (w <= 0 || h <= 0) return false;
        if (target.fbo != 0 && target.w == w && target.h == h) return true;
        destroy_target(target);

        glGenTextures(1, &target.tex);
        glBindTexture(GL_TEXTURE_2D, target.tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA,
                     GL_UNSIGNED_BYTE, nullptr);
        const GLint filter = nearest ? GL_NEAREST : GL_LINEAR;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glGenFramebuffers(1, &target.fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, target.fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                               GL_TEXTURE_2D, target.tex, 0);

        if (with_depth) {
            glGenRenderbuffers(1, &target.depth);
            glBindRenderbuffer(GL_RENDERBUFFER, target.depth);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, w, h);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                                      GL_RENDERBUFFER, target.depth);
        }

        const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            note_failure("incomplete FBO " + std::to_string(w) + "x" +
                         std::to_string(h) + " status=0x" +
                         std::to_string(static_cast<int>(status)));
            destroy_target(target);
            return false;
        }
        target.w = w;
        target.h = h;
        return true;
    }

    void destroy_target(Target& target) {
        if (target.fbo) glDeleteFramebuffers(1, &target.fbo);
        if (target.tex) glDeleteTextures(1, &target.tex);
        if (target.depth) glDeleteRenderbuffers(1, &target.depth);
        target = Target{};
    }

    // ---- GL state ---------------------------------------------------------
    void save_state(GlState& state) {
        glGetIntegerv(GL_FRAMEBUFFER_BINDING, &state.fbo);
        glGetIntegerv(GL_VIEWPORT, state.viewport);
        glGetIntegerv(GL_CURRENT_PROGRAM, &state.program);
        glGetIntegerv(GL_ACTIVE_TEXTURE, &state.active_texture);
        glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &state.array_buffer);

        glActiveTexture(GL_TEXTURE0);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &state.tex0);
        glActiveTexture(GL_TEXTURE1);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &state.tex1);
        glActiveTexture(GL_TEXTURE2);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &state.tex2);
        glActiveTexture(static_cast<GLenum>(state.active_texture));

        state.blend = glIsEnabled(GL_BLEND);
        state.depth = glIsEnabled(GL_DEPTH_TEST);
        state.scissor = glIsEnabled(GL_SCISSOR_TEST);
        state.cull = glIsEnabled(GL_CULL_FACE);
        state.stencil = glIsEnabled(GL_STENCIL_TEST);

        if (gl_major >= 3) {
            glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &state.vao);
        } else {
            // No VAOs: the guest's client-array setup lives in the context, so
            // it has to be captured and restored around our draw.
            for (int i = 0; i < 4; ++i) {
                GlState::Attrib& a = state.attribs[i];
                glGetVertexAttribiv(i, GL_VERTEX_ATTRIB_ARRAY_ENABLED, &a.enabled);
                glGetVertexAttribiv(i, GL_VERTEX_ATTRIB_ARRAY_SIZE, &a.size);
                glGetVertexAttribiv(i, GL_VERTEX_ATTRIB_ARRAY_TYPE, &a.type);
                glGetVertexAttribiv(i, GL_VERTEX_ATTRIB_ARRAY_STRIDE, &a.stride);
                glGetVertexAttribiv(i, GL_VERTEX_ATTRIB_ARRAY_NORMALIZED, &a.normalized);
                glGetVertexAttribiv(i, GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING, &a.buffer);
                glGetVertexAttribPointerv(i, GL_VERTEX_ATTRIB_ARRAY_POINTER, &a.pointer);
            }
        }
    }

    void restore_state(const GlState& state) {
        glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(state.fbo));
        glViewport(state.viewport[0], state.viewport[1], state.viewport[2],
                   state.viewport[3]);
        glUseProgram(static_cast<GLuint>(state.program));

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(state.tex0));
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(state.tex1));
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(state.tex2));
        glActiveTexture(static_cast<GLenum>(state.active_texture));

        if (state.blend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
        if (state.depth) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
        if (state.scissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
        if (state.cull) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
        if (state.stencil) glEnable(GL_STENCIL_TEST); else glDisable(GL_STENCIL_TEST);

        glBindBuffer(GL_ARRAY_BUFFER, static_cast<GLuint>(state.array_buffer));

        if (gl_major >= 3) {
            glBindVertexArray(static_cast<GLuint>(state.vao));
        } else {
            for (int i = 0; i < 4; ++i) {
                const GlState::Attrib& a = state.attribs[i];
                glBindBuffer(GL_ARRAY_BUFFER, static_cast<GLuint>(a.buffer));
                glVertexAttribPointer(i, a.size, static_cast<GLenum>(a.type),
                                      static_cast<GLboolean>(a.normalized), a.stride,
                                      a.pointer);
                if (a.enabled) glEnableVertexAttribArray(i);
                else glDisableVertexAttribArray(i);
            }
        }
    }

    bool init_gl(int major) {
        if (gl_ready && gl_major == major) return true;

        if (gl_ready) {
            // Context recreated: every object from the old one is gone.
            vao = vbo = 0;
            sgsr = Program{};
            fsr = Program{};
            bilinear = Program{};
            motion = Program{};
            synth = Program{};
            frames[0] = frames[1] = Target{};
            internal = motion_target = intermediate = Target{};
            has_prev = false;
        }

        gl_major = major;
        GlState saved;
        save_state(saved);
        if (gl_major >= 3) {
            glGenVertexArrays(1, &vao);
            glBindVertexArray(vao);
        }
        glGenBuffers(1, &vbo);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, sizeof kQuad, kQuad, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                              reinterpret_cast<const void*>(0));
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                              reinterpret_cast<const void*>(2 * sizeof(float)));
        if (gl_major >= 3) glBindVertexArray(0);
        restore_state(saved);

        sgsr = link(kSgsrShader, "sgsr12");
        fsr = link(kFsrShader, "fsr_easu_rcas");
        bilinear = link(kBilinearShader, "bilinear_sharpen");
        motion = link(kMotionShader, "blockmatch_8x8");
        synth = link(kSynthShader, "motion_synth");

        if (bilinear.id == 0) {
            note_failure("no usable upscale program; compositor disabled");
            gl_ready = false;
            return false;
        }
        gl_ready = true;
        LOGI("compositor ready: upscale=%s frame-gen=%s gl=%s",
             sgsr.id ? "sgsr" : (fsr.id ? "fsr" : "bilinear"),
             (motion.id && synth.id) ? "2x" : "unavailable", version.c_str());
        return true;
    }

    // ---- passes -----------------------------------------------------------
    void bind_quad() {
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                              reinterpret_cast<const void*>(0));
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                              reinterpret_cast<const void*>(2 * sizeof(float)));
    }

    void prepare_draw(GLuint fbo, int w, int h) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, w, h);
        glDisable(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_STENCIL_TEST);
    }

    void set1i(GLuint program, const char* name, int value) {
        const GLint at = glGetUniformLocation(program, name);
        if (at >= 0) glUniform1i(at, value);
    }
    void set1f(GLuint program, const char* name, float value) {
        const GLint at = glGetUniformLocation(program, name);
        if (at >= 0) glUniform1f(at, value);
    }
    void set2f(GLuint program, const char* name, float x, float y) {
        const GLint at = glGetUniformLocation(program, name);
        if (at >= 0) glUniform2f(at, x, y);
    }

    Program& upscale_program() {
        const int mode = upscaler.load();
        if (mode == kUpscalerSgsr && sgsr.id) return sgsr;
        if (mode == kUpscalerFsr && fsr.id) return fsr;
        return bilinear;
    }

    // Resolves `source` (source_w x source_h) into the window backbuffer.
    void resolve(GLuint source, int sw, int sh) {
        if (source == 0 || sw <= 0 || sh <= 0) return;
        Program& program = upscale_program();
        if (program.id == 0) return;

        prepare_draw(0, display_w, display_h);
        glUseProgram(program.id);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, source);
        set1i(program.id, "uTex", 0);
        set2f(program.id, "uTexSize", 1.0f / static_cast<float>(sw),
              1.0f / static_cast<float>(sh));
        set1f(program.id, "uSharpness",
              static_cast<float>(sharpness.load()) / 100.0f);
        bind_quad();
        glDrawArrays(GL_TRIANGLES, 0, 6);
        ++presented;
    }

    void capture(GLuint read_fbo, Target& destination, int w, int h) {
        prepare_draw(read_fbo, w, h);
        glBindTexture(GL_TEXTURE_2D, destination.tex);
        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
    }

    bool motion_available() const {
        return frame_gen.load() != kFrameGenOff && motion.id != 0 &&
               synth.id != 0 && has_prev;
    }
};

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------
GpuCompositor::GpuCompositor() : impl_(new Impl()) {}

GpuCompositor& GpuCompositor::instance() {
    static GpuCompositor compositor;
    return compositor;
}

void GpuCompositor::configure(int upscaler, int scale_percent, int sharpness_percent,
                              int frame_gen) {
    Impl& d = *impl_;
    const int new_upscaler =
            (upscaler == kUpscalerSgsr || upscaler == kUpscalerFsr) ? upscaler
                                                                   : kUpscalerOff;
    const int new_scale = clamp_int(scale_percent, 40, 100);
    const int new_sharp = clamp_int(sharpness_percent, 0, 100);
    const int new_gen = (frame_gen == kFrameGen2x) ? kFrameGen2x : kFrameGenOff;

    const bool changed = d.upscaler.load() != new_upscaler ||
                         d.scale.load() != new_scale ||
                         d.frame_gen.load() != new_gen;
    d.upscaler.store(new_upscaler);
    d.scale.store(new_scale);
    d.sharpness.store(new_sharp);
    d.frame_gen.store(new_gen);

    if (changed) {
        // History is invalid across a mode change; the first frame after it is
        // always presented as-is rather than warped from a stale pair.
        d.has_prev = false;
        d.current = 0;
    }
    LOGI("graphics: upscaler=%d scale=%d%% sharpness=%d%% frameGen=%d",
         new_upscaler, new_scale, new_sharp, new_gen);
}

GraphicsConfig GpuCompositor::config() const {
    const Impl& d = *impl_;
    GraphicsConfig c;
    c.upscaler = d.upscaler.load();
    c.scale_percent = d.scale.load();
    c.sharpness_percent = d.sharpness.load();
    c.frame_gen = d.frame_gen.load();
    return c;
}

bool GpuCompositor::active() const {
    const Impl& d = *impl_;
    return d.upscaler.load() != kUpscalerOff || d.frame_gen.load() != kFrameGenOff;
}

bool GpuCompositor::frame_generation() const {
    return impl_->frame_gen.load() != kFrameGenOff;
}

bool GpuCompositor::internal_scaling() const {
    const Impl& d = *impl_;
    return d.upscaler.load() != kUpscalerOff && d.scale.load() < 100;
}

void GpuCompositor::note_shader_failure(const std::string& reason) {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    impl_->note_failure(reason);
}

unsigned int GpuCompositor::bind_internal_target(int display_width, int display_height,
                                                 int* out_width, int* out_height) {
    Impl& d = *impl_;
    if (!active() || !internal_scaling()) return 0;

    std::lock_guard<std::mutex> lock(d.mutex);
    const int major = d.detect_context();
    if (major < 2) return 0;
    if (!d.init_gl(major)) return 0;

    const int dw = clamp_int(display_width, 1, 4096);
    const int dh = clamp_int(display_height, 1, 4096);
    const int iw = clamp_int(static_cast<int>(dw * d.scale.load() / 100.0f + 0.5f), 64, 4096);
    const int ih = clamp_int(static_cast<int>(dh * d.scale.load() / 100.0f + 0.5f), 64, 4096);

    GlState state;
    d.save_state(state);
    const bool ok = d.make_target(d.internal, iw, ih, /*with_depth=*/true);
    d.restore_state(state);
    if (!ok) return 0;

    d.display_w = dw;
    d.display_h = dh;
    d.internal_bound.store(true);
    glBindFramebuffer(GL_FRAMEBUFFER, d.internal.fbo);
    glViewport(0, 0, iw, ih);
    if (out_width != nullptr) *out_width = iw;
    if (out_height != nullptr) *out_height = ih;
    return d.internal.fbo;
}

void GpuCompositor::release_internal_target() {
    Impl& d = *impl_;
    if (!d.internal_bound.exchange(false)) return;
    // Leave the real window framebuffer bound; the next frame re-binds the
    // internal target from eglMakeCurrent / eglCreateWindowSurface.
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

bool GpuCompositor::begin(EGLDisplay display, EGLSurface surface,
                          bool guest_drew_internal) {
    Impl& d = *impl_;
    if (!active()) return false;

    EGLint width = 0;
    EGLint height = 0;
    if (eglQuerySurface(display, surface, EGL_WIDTH, &width) != EGL_TRUE ||
        eglQuerySurface(display, surface, EGL_HEIGHT, &height) != EGL_TRUE ||
        width <= 0 || height <= 0) {
        return false;
    }

    std::lock_guard<std::mutex> lock(d.mutex);
    const int major = d.detect_context();
    if (major < 2) return false;
    if (!d.init_gl(major)) return false;

    const int dw = clamp_int(static_cast<int>(width), 1, 4096);
    const int dh = clamp_int(static_cast<int>(height), 1, 4096);
    const bool use_internal = guest_drew_internal && d.internal_bound.load() &&
                              d.internal.fbo != 0;
    const int sw = use_internal ? d.internal.w : dw;
    const int sh = use_internal ? d.internal.h : dh;
    if (sw <= 0 || sh <= 0) return false;

    if (dw != d.display_w || dh != d.display_h || sw != d.source_w || sh != d.source_h) {
        // A resize invalidates every history texture.
        d.has_prev = false;
        d.current = 0;
    }
    d.display_w = dw;
    d.display_h = dh;
    d.source_w = sw;
    d.source_h = sh;
    d.last_internal = use_internal;

    d.blocks_w = std::max(1, (sw + 7) / 8);
    d.blocks_h = std::max(1, (sh + 7) / 8);

    GlState state;
    d.save_state(state);
    const bool frames_ok = d.make_target(d.frames[0], sw, sh, false) &&
                           d.make_target(d.frames[1], sw, sh, false);
    bool generated_ok = true;
    if (d.frame_gen.load() != kFrameGenOff) {
        generated_ok = d.make_target(d.motion_target, d.blocks_w, d.blocks_h,
                                     false, /*nearest=*/true) &&
                       d.make_target(d.intermediate, sw, sh, false);
    }
    if (use_internal) {
        // The guest's frame is already in the internal target; copy it into the
        // history ring so the next frame has a real F_{t-1}.
        d.capture(d.internal.fbo, d.frames[d.current], sw, sh);
    } else {
        d.capture(static_cast<GLuint>(state.fbo), d.frames[d.current], sw, sh);
    }
    d.restore_state(state);

    if (!frames_ok) return false;
    if (!generated_ok) {
        // Frame generation requested but its targets failed: fall back to the
        // spatial pass only for this frame.
        d.has_prev = false;
    }
    ++d.swaps;

    const auto now = std::chrono::steady_clock::now();
    if (d.last_frame.time_since_epoch().count() != 0) {
        const double observed =
                std::chrono::duration<double, std::milli>(now - d.last_frame).count();
        if (observed > 0.5 && observed < 200.0) {
            d.interval_ms = d.interval_ms * 0.88 + observed * 0.12;
        }
    }
    d.last_frame = now;
    return true;
}

void GpuCompositor::present_current() {
    Impl& d = *impl_;
    const GLuint source = d.last_internal ? d.internal.tex : d.frames[d.current].tex;

    GlState state;
    d.save_state(state);
    d.resolve(source, d.source_w, d.source_h);
    d.restore_state(state);
}

bool GpuCompositor::present_intermediate() {
    Impl& d = *impl_;
    if (!d.motion_available()) return false;
    if (d.frames[0].tex == 0 || d.frames[1].tex == 0 || d.motion_target.tex == 0 ||
        d.intermediate.tex == 0) {
        return false;
    }
    const int prev = d.current ^ 1;

    GlState state;
    d.save_state(state);

    // ---- block matching --------------------------------------------------
    d.prepare_draw(d.motion_target.fbo, d.motion_target.w, d.motion_target.h);
    glUseProgram(d.motion.id);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, d.frames[prev].tex);
    d.set1i(d.motion.id, "uPrev", 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, d.frames[d.current].tex);
    d.set1i(d.motion.id, "uCurr", 1);
    d.set2f(d.motion.id, "uBlockCount", static_cast<float>(d.blocks_w),
            static_cast<float>(d.blocks_h));
    d.set2f(d.motion.id, "uFrameSize", 1.0f / static_cast<float>(d.source_w),
            1.0f / static_cast<float>(d.source_h));
    d.set1f(d.motion.id, "uSearch", 6.0f);
    d.set1f(d.motion.id, "uSad", 0.12f);
    d.bind_quad();
    glDrawArrays(GL_TRIANGLES, 0, 6);

    // ---- synthesis -------------------------------------------------------
    d.prepare_draw(d.intermediate.fbo, d.intermediate.w, d.intermediate.h);
    glUseProgram(d.synth.id);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, d.frames[prev].tex);
    d.set1i(d.synth.id, "uPrev", 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, d.frames[d.current].tex);
    d.set1i(d.synth.id, "uCurr", 1);
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, d.motion_target.tex);
    d.set1i(d.synth.id, "uMotion", 2);
    d.set2f(d.synth.id, "uFrameSize", 1.0f / static_cast<float>(d.source_w),
            1.0f / static_cast<float>(d.source_h));
    d.set2f(d.synth.id, "uBlockCount", static_cast<float>(d.blocks_w),
            static_cast<float>(d.blocks_h));
    d.bind_quad();
    glDrawArrays(GL_TRIANGLES, 0, 6);

    // ---- resolve the synthetic frame to the window -----------------------
    d.resolve(d.intermediate.tex, d.intermediate.w, d.intermediate.h);
    ++d.generated;

    d.restore_state(state);
    return true;
}

void GpuCompositor::wait_half_frame() {
    Impl& d = *impl_;
    double half = d.interval_ms * 0.5;
    if (half <= 0.0) half = 8.0;
    half = std::min(half, 10.0);   // never stall the guest thread for long
    std::this_thread::sleep_for(
            std::chrono::duration<double, std::milli>(half));
}

void GpuCompositor::end_frame() {
    Impl& d = *impl_;
    d.has_prev = true;
    d.current ^= 1;
}

std::string GpuCompositor::stats() const {
    const Impl& d = *impl_;
    const double seconds = std::chrono::duration<double>(
            std::chrono::steady_clock::now() - d.started).count();
    const char* upscaler_name = d.upscaler.load() == kUpscalerSgsr
                                        ? "sgsr"
                                        : (d.upscaler.load() == kUpscalerFsr ? "fsr1" : "off");
    char buf[640];
    std::snprintf(buf, sizeof buf,
                  "upscaler=%s scale=%d%% internal=%dx%d display=%dx%d sharpness=%d%% "
                  "frameGen=%dx swaps=%llu presented=%llu generated=%llu "
                  "avgFrameMs=%.2f shaderFailures=%d program=%s%s",
                  upscaler_name, d.scale.load(), d.internal.w, d.internal.h,
                  d.display_w, d.display_h, d.sharpness.load(),
                  d.frame_gen.load() == kFrameGen2x ? 2 : 0,
                  static_cast<unsigned long long>(d.swaps),
                  static_cast<unsigned long long>(d.presented),
                  static_cast<unsigned long long>(d.generated), d.interval_ms,
                  d.shader_failures,
                  d.version.empty() ? "unknown" : d.version.c_str(),
                  d.last_error.empty() ? "" : " lastError=");
    std::string out(buf);
    if (!d.last_error.empty()) out += d.last_error;
    if (seconds > 1.0) {
        char rate[96];
        std::snprintf(rate, sizeof rate, " outputFps=%.1f",
                      static_cast<double>(d.presented) / seconds);
        out += rate;
    }
    return out;
}

std::string GpuCompositor::gpu_description() {
    Impl& d = *impl_;
    std::string out = d.renderer.empty() ? "unknown GPU" : d.renderer;
    if (!d.vendor.empty()) out += " / " + d.vendor;
    if (!d.version.empty()) out += " / " + d.version;
    return out;
}

}  // namespace zai
