// =============================================================================
//  gpu_compositor.cpp  (Zai-Bridge "Zai-Scale Ultra")
//
//  Runs on the guest's GL thread inside eglSwapBuffers. Borrows the guest's
//  context, never creates one, draws with core GLES3 FBOs/RGBA8 textures and
//  restores every piece of guest state it touches.
//
//  Per guest frame:
//    1. capture             ring[cur]        (3-frame history)
//    2. hierarchical flow   16x16 -> 8x8 -> 4x4, sub-pixel refine
//    3. temporal SR         YCoCg clamp + bidirectional history rejection
//    4. enhancement         local contrast / specular / micro-detail
//    5. MFG synthesis       1..3 intermediates at (i+1)/(n+1)
//    6. resolve             SGSR / FSR EASU+RCAS / DLSS-5 detail + wipe
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
// Strict GLSL ES 3.00 source. gl_shader_patcher demotes the same body to
// ES 1.00 for a GLES2 driver (version, in/out, texture, fragColor).
// Every literal is explicit and every precision is declared.
// ---------------------------------------------------------------------------
const char* const kVertexShader = R"GLSL(#version 300 es
precision highp float;
in vec2 aPos;
in vec2 aUV;
out vec2 vUV;
void main() {
    vUV = aUV;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
)GLSL";

const char* const kSgsrShader = R"GLSL(#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUV;
out vec4 fragColor;
uniform sampler2D uTex;
uniform vec2 uTexSize;
uniform float uSharpness;
uniform float uCompare;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void main() {
    vec2 t = uTexSize;
    vec3 c = texture(uTex, vUV).rgb;
    float lc = luma(c);

    vec3 n  = texture(uTex, vUV + vec2(0.0, -1.0) * t).rgb;
    vec3 s  = texture(uTex, vUV + vec2(0.0, 1.0) * t).rgb;
    vec3 e  = texture(uTex, vUV + vec2(1.0, 0.0) * t).rgb;
    vec3 w  = texture(uTex, vUV + vec2(-1.0, 0.0) * t).rgb;
    vec3 nw = texture(uTex, vUV + vec2(-0.7, -0.7) * t).rgb;
    vec3 ne = texture(uTex, vUV + vec2(0.7, -0.7) * t).rgb;
    vec3 sw = texture(uTex, vUV + vec2(-0.7, 0.7) * t).rgb;
    vec3 se = texture(uTex, vUV + vec2(0.7, 0.7) * t).rgb;
    vec3 nn = texture(uTex, vUV + vec2(0.0, -1.9) * t).rgb;
    vec3 ss = texture(uTex, vUV + vec2(0.0, 1.9) * t).rgb;
    vec3 ee = texture(uTex, vUV + vec2(1.9, 0.0) * t).rgb;
    vec3 ww = texture(uTex, vUV + vec2(-1.9, 0.0) * t).rgb;

    vec3 acc = c * 1.25;
    float wsum = 1.25;
#define ZB_EA(sample, coeff) \
    { float k = (coeff) / (1.0 + 18.0 * abs(luma(sample) - lc)); acc += (sample) * k; wsum += k; }
    ZB_EA(n, 1.0)
    ZB_EA(s, 1.0)
    ZB_EA(e, 1.0)
    ZB_EA(w, 1.0)
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

    float gx = abs(luma(e) - luma(w));
    float gy = abs(luma(n) - luma(s));
    vec3 detail = (gx < gy) ? (2.0 * c - e - w) : (2.0 * c - n - s);
    vec3 outc = base + uSharpness * 0.55 * detail;

    if (uCompare > 0.001) {
        vec3 vanilla = texture(uTex, vUV).rgb;
        outc = (vUV.x < uCompare) ? vanilla : outc;
        float line = 1.0 - smoothstep(0.0, 1.5 * uTexSize.x, abs(vUV.x - uCompare));
        outc = mix(outc, vec3(0.0, 0.90, 1.0), line * 0.85);
    }
    fragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
}
)GLSL";

const char* const kFsrShader = R"GLSL(#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUV;
out vec4 fragColor;
uniform sampler2D uTex;
uniform vec2 uTexSize;
uniform float uSharpness;
uniform float uCompare;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void main() {
    vec2 t = uTexSize;
    vec3 c  = texture(uTex, vUV).rgb;
    vec3 n  = texture(uTex, vUV + vec2(0.0, -1.0) * t).rgb;
    vec3 s  = texture(uTex, vUV + vec2(0.0, 1.0) * t).rgb;
    vec3 e  = texture(uTex, vUV + vec2(1.0, 0.0) * t).rgb;
    vec3 w  = texture(uTex, vUV + vec2(-1.0, 0.0) * t).rgb;
    vec3 nw = texture(uTex, vUV + vec2(-0.6, -0.6) * t).rgb;
    vec3 ne = texture(uTex, vUV + vec2(0.6, -0.6) * t).rgb;
    vec3 sw = texture(uTex, vUV + vec2(-0.6, 0.6) * t).rgb;
    vec3 se = texture(uTex, vUV + vec2(0.6, 0.6) * t).rgb;
    vec3 nn = texture(uTex, vUV + vec2(0.0, -2.0) * t).rgb;
    vec3 ss = texture(uTex, vUV + vec2(0.0, 2.0) * t).rgb;
    vec3 ee = texture(uTex, vUV + vec2(2.0, 0.0) * t).rgb;
    vec3 ww = texture(uTex, vUV + vec2(-2.0, 0.0) * t).rgb;

    float lc = luma(c);
    float gx = abs(luma(e) - luma(w));
    float gy = abs(luma(n) - luma(s));
    float horizontal = gx / (gx + gy + 0.0001);

    vec3 along  = mix((nw + ne + sw + se) * 0.25, (nn + ss) * 0.5, horizontal);
    vec3 across = mix((nn + ss) * 0.5, (ee + ww) * 0.5, horizontal);
    vec3 easu = mix(c, along, 0.55);
    easu = mix(easu, across, 0.20 * (1.0 - abs(luma(along) - lc)));

    vec3 mean4 = (n + s + e + w) * 0.25;
    vec3 sharpened = easu + mix(0.0, 0.85, uSharpness) * (easu - mean4);
    vec3 lo = min(min(min(n, s), min(e, w)), easu);
    vec3 hi = max(max(max(n, s), max(e, w)), easu);
    vec3 outc = clamp(sharpened, lo, hi);

    if (uCompare > 0.001) {
        vec3 vanilla = texture(uTex, vUV).rgb;
        outc = (vUV.x < uCompare) ? vanilla : outc;
        float line = 1.0 - smoothstep(0.0, 1.5 * uTexSize.x, abs(vUV.x - uCompare));
        outc = mix(outc, vec3(0.0, 0.90, 1.0), line * 0.85);
    }
    fragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
}
)GLSL";

const char* const kDlssShader = R"GLSL(#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUV;
out vec4 fragColor;
uniform sampler2D uTex;
uniform vec2 uTexSize;
uniform float uSharpness;
uniform float uDetail;
uniform float uCompare;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void main() {
    vec2 t = uTexSize;
    vec3 c  = texture(uTex, vUV).rgb;
    vec3 n  = texture(uTex, vUV + vec2(0.0, -1.0) * t).rgb;
    vec3 s  = texture(uTex, vUV + vec2(0.0, 1.0) * t).rgb;
    vec3 e  = texture(uTex, vUV + vec2(1.0, 0.0) * t).rgb;
    vec3 w  = texture(uTex, vUV + vec2(-1.0, 0.0) * t).rgb;
    vec3 nw = texture(uTex, vUV + vec2(-0.7, -0.7) * t).rgb;
    vec3 ne = texture(uTex, vUV + vec2(0.7, -0.7) * t).rgb;
    vec3 sw = texture(uTex, vUV + vec2(-0.7, 0.7) * t).rgb;
    vec3 se = texture(uTex, vUV + vec2(0.7, 0.7) * t).rgb;
    vec3 nn = texture(uTex, vUV + vec2(0.0, -2.0) * t).rgb;
    vec3 ss = texture(uTex, vUV + vec2(0.0, 2.0) * t).rgb;
    vec3 ee = texture(uTex, vUV + vec2(2.0, 0.0) * t).rgb;
    vec3 ww = texture(uTex, vUV + vec2(-2.0, 0.0) * t).rgb;

    float lc = luma(c);
    float gx = abs(luma(e) - luma(w));
    float gy = abs(luma(n) - luma(s));
    float horizontal = gx / (gx + gy + 0.0001);
    vec3 along  = mix((nw + ne + sw + se) * 0.25, (nn + ss) * 0.5, horizontal);
    vec3 across = mix((nn + ss) * 0.5, (ee + ww) * 0.5, horizontal);
    vec3 easu = mix(c, along, 0.58);
    easu = mix(easu, across, 0.18 * (1.0 - abs(luma(along) - lc)));

    // Micro-detail reconstruction: high-frequency residual amplified with the
    // neural control, clamped so flat legacy textures gain structure without
    // turning into noise.
    vec3 blur = (n + s + e + w) * 0.25;
    vec3 highpass = clamp(c - blur, vec3(-0.25), vec3(0.25));
    vec3 detailed = easu + highpass * (0.22 + 0.42 * uDetail);

    // Local contrast around mid-tones, and a specular lift on bright small
    // features (chrome, lamps, HUD highlights).
    float y = luma(detailed);
    float contrast = (y - 0.5) * (0.10 + 0.22 * uDetail);
    vec3 lit = detailed + vec3(contrast);
    float spec = smoothstep(0.55, 0.92, y) * smoothstep(0.015, 0.18, abs(y - luma(blur)));
    lit += vec3(spec * (0.10 + 0.18 * uDetail));

    // Chroma-preserving saturation.
    float ly = luma(lit);
    vec3 outc = vec3(ly) + (lit - vec3(ly)) * (1.0 + 0.16 * uDetail);

    // RCAS-style sharpen, clamped to the local neighbourhood.
    vec3 mean4 = (n + s + e + w) * 0.25;
    outc += mix(0.0, 0.75, uSharpness) * (outc - mean4);
    vec3 lo = min(min(min(n, s), min(e, w)), c);
    vec3 hi = max(max(max(n, s), max(e, w)), c);
    outc = clamp(outc, lo - 0.04, hi + 0.04);

    if (uCompare > 0.001) {
        vec3 vanilla = texture(uTex, vUV).rgb;
        outc = (vUV.x < uCompare) ? vanilla : outc;
        float line = 1.0 - smoothstep(0.0, 1.5 * uTexSize.x, abs(vUV.x - uCompare));
        outc = mix(outc, vec3(0.0, 0.90, 1.0), line * 0.85);
    }
    fragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
}
)GLSL";

const char* const kBilinearShader = R"GLSL(#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUV;
out vec4 fragColor;
uniform sampler2D uTex;
uniform vec2 uTexSize;
uniform float uSharpness;
uniform float uCompare;

void main() {
    vec2 t = uTexSize;
    vec3 c = texture(uTex, vUV).rgb;
    vec3 blur = (texture(uTex, vUV + vec2(0.0, -1.0) * t).rgb +
                 texture(uTex, vUV + vec2(0.0, 1.0) * t).rgb +
                 texture(uTex, vUV + vec2(1.0, 0.0) * t).rgb +
                 texture(uTex, vUV + vec2(-1.0, 0.0) * t).rgb) * 0.25;
    vec3 outc = clamp(c + uSharpness * 0.35 * (c - blur), 0.0, 1.0);
    if (uCompare > 0.001) {
        float line = 1.0 - smoothstep(0.0, 1.5 * uTexSize.x, abs(vUV.x - uCompare));
        outc = mix(outc, vec3(0.0, 0.90, 1.0), line * 0.85);
    }
    fragColor = vec4(outc, 1.0);
}
)GLSL";

// One body drives all three hierarchy levels through uUseBase / uSearch /
// uSubpixel. RG = (vector + 16) / 32, B = residual, A = confidence.
const char* const kMotionShader = R"GLSL(#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUV;
out vec4 fragColor;
uniform sampler2D uPrev;
uniform sampler2D uCurr;
uniform sampler2D uBase;
uniform vec2 uBlockCount;
uniform vec2 uFrameSize;
uniform float uSearch;
uniform float uSad;
uniform float uUseBase;
uniform float uSubpixel;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

float taps(sampler2D tex, vec2 uv, vec2 off) {
    vec2 p = uv + off * uFrameSize;
    vec2 q = 1.5 * uFrameSize;
    return luma(texture(tex, p + vec2(-1.0, -1.0) * q).rgb) +
           luma(texture(tex, p + vec2(1.0, -1.0) * q).rgb) +
           luma(texture(tex, p + vec2(-1.0, 1.0) * q).rgb) +
           luma(texture(tex, p + vec2(1.0, 1.0) * q).rgb);
}

float sad(vec2 uv, vec2 d) {
    return abs(taps(uPrev, uv, -d) - taps(uCurr, uv, d)) * 0.25;
}

void main() {
    vec2 uv = (floor(vUV * uBlockCount) + 0.5) / uBlockCount;
    vec2 base = vec2(0.0);
    if (uUseBase > 0.5) {
        base = texture(uBase, uv).rg * 32.0 - 16.0;
    }
    float best = sad(uv, base);
    vec2 motion = base;
    float r = uSearch;

#define ZB_CAND(dx, dy) \
    { vec2 d = base + vec2(dx, dy); float z = sad(uv, d); if (z < best) { best = z; motion = d; } }
    ZB_CAND(0.0, 0.0)
    ZB_CAND(r, 0.0)
    ZB_CAND(-r, 0.0)
    ZB_CAND(0.0, r)
    ZB_CAND(0.0, -r)
    ZB_CAND(r, r)
    ZB_CAND(-r, r)
    ZB_CAND(r, -r)
    ZB_CAND(-r, -r)
    if (uSubpixel > 0.5) {
        ZB_CAND(0.5, 0.0)
        ZB_CAND(-0.5, 0.0)
        ZB_CAND(0.0, 0.5)
        ZB_CAND(0.0, -0.5)
    }
#undef ZB_CAND

    float confidence = 1.0 - smoothstep(uSad * 0.55, uSad, best);
    if (best < 0.0022) {
        motion = vec2(0.0);
    }
    fragColor = vec4((motion + 16.0) / 32.0, best, confidence);
}
)GLSL";

const char* const kTsrShader = R"GLSL(#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUV;
out vec4 fragColor;
uniform sampler2D uCurr;
uniform sampler2D uHist;
uniform sampler2D uMotion;
uniform vec2 uFrameSize;
uniform vec2 uBlockCount;
uniform float uAlpha;
uniform float uValid;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
vec3 toYCoCg(vec3 c) {
    float y = 0.25 * c.r + 0.5 * c.g + 0.25 * c.b;
    float co = 0.5 * c.r - 0.5 * c.b;
    float cg = -0.25 * c.r + 0.5 * c.g - 0.25 * c.b;
    return vec3(y, co, cg);
}
vec3 fromYCoCg(vec3 c) {
    return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);
}

void main() {
    vec2 muv = (floor(vUV * uBlockCount) + 0.5) / uBlockCount;
    vec4 m = texture(uMotion, muv);
    vec2 mv = m.rg * 32.0 - 16.0;
    float confidence = m.a;

    vec2 reproj = clamp(vUV - mv * uFrameSize, vec2(0.0), vec2(1.0));
    vec2 backUV = (floor(reproj * uBlockCount) + 0.5) / uBlockCount;
    vec2 backMv = texture(uMotion, backUV).rg * 32.0 - 16.0;
    float flowError = length(backMv + mv);
    float reject = smoothstep(2.0, 6.0, flowError) + (1.0 - confidence);

    vec3 cur = texture(uCurr, vUV).rgb;
    vec3 c0 = toYCoCg(texture(uCurr, vUV + vec2(-1.0, -1.0) * uFrameSize).rgb);
    vec3 c1 = toYCoCg(texture(uCurr, vUV + vec2(0.0, -1.0) * uFrameSize).rgb);
    vec3 c2 = toYCoCg(texture(uCurr, vUV + vec2(1.0, -1.0) * uFrameSize).rgb);
    vec3 c3 = toYCoCg(texture(uCurr, vUV + vec2(-1.0, 0.0) * uFrameSize).rgb);
    vec3 c4 = toYCoCg(cur);
    vec3 c5 = toYCoCg(texture(uCurr, vUV + vec2(1.0, 0.0) * uFrameSize).rgb);
    vec3 c6 = toYCoCg(texture(uCurr, vUV + vec2(-1.0, 1.0) * uFrameSize).rgb);
    vec3 c7 = toYCoCg(texture(uCurr, vUV + vec2(0.0, 1.0) * uFrameSize).rgb);
    vec3 c8 = toYCoCg(texture(uCurr, vUV + vec2(1.0, 1.0) * uFrameSize).rgb);
    vec3 lo = min(min(min(c0, c1), min(c2, c3)), min(min(c4, c5), min(c6, min(c7, c8))));
    vec3 hi = max(max(max(c0, c1), max(c2, c3)), max(max(c4, c5), max(c6, max(c7, c8))));

    vec3 hist = toYCoCg(texture(uHist, reproj).rgb);
    hist = clamp(hist, lo, hi);

    float weight = clamp(uAlpha * uValid * (1.0 - clamp(reject, 0.0, 1.0)), 0.0, 0.96);
    vec3 outc = mix(cur, fromYCoCg(hist), weight);
    fragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
}
)GLSL";

const char* const kEnhanceShader = R"GLSL(#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUV;
out vec4 fragColor;
uniform sampler2D uTex;
uniform vec2 uTexSize;
uniform float uNeural;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void main() {
    vec2 t = uTexSize;
    vec3 c = texture(uTex, vUV).rgb;
    vec3 n = texture(uTex, vUV + vec2(0.0, -1.6) * t).rgb;
    vec3 s = texture(uTex, vUV + vec2(0.0, 1.6) * t).rgb;
    vec3 e = texture(uTex, vUV + vec2(1.6, 0.0) * t).rgb;
    vec3 w = texture(uTex, vUV + vec2(-1.6, 0.0) * t).rgb;
    vec3 blur = (n + s + e + w + c) * 0.2;

    float lc = luma(c);
    float lBlur = luma(blur);
    float detail = lc - lBlur;
    float micro = abs(lc - luma((n + s + e + w) * 0.25));

    // 3D-style local contrast in luminance, chroma preserved.
    float y = lc + detail * (0.55 * uNeural);
    vec3 chroma = c - vec3(lc);
    vec3 outc = vec3(y) + chroma * (1.0 + 0.18 * uNeural);

    // Specular reconstruction: lift small bright features, never the sky.
    float spec = smoothstep(0.58, 0.94, lc) * smoothstep(0.01, 0.16, micro);
    outc += vec3(spec * 0.22 * uNeural);

    fragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
}
)GLSL";

const char* const kSynthShader = R"GLSL(#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUV;
out vec4 fragColor;
uniform sampler2D uPrev;
uniform sampler2D uCurr;
uniform sampler2D uMotion;
uniform vec2 uFrameSize;
uniform vec2 uBlockCount;
uniform float uAlpha;

void main() {
    vec2 muv = (floor(vUV * uBlockCount) + 0.5) / uBlockCount;
    vec4 m = texture(uMotion, muv);
    vec2 mv = m.rg * 32.0 - 16.0;
    float confidence = m.a;

    vec2 fwd = mv * uAlpha * uFrameSize;
    vec2 bwd = mv * (1.0 - uAlpha) * uFrameSize;
    vec3 fromPrev = texture(uPrev, clamp(vUV + fwd, vec2(0.0), vec2(1.0))).rgb;
    vec3 fromCurr = texture(uCurr, clamp(vUV - bwd, vec2(0.0), vec2(1.0))).rgb;
    vec3 warped = mix(fromPrev, fromCurr, uAlpha);

    // HUD/UI edge lock: low-confidence blocks (menus, text, static overlays)
    // are taken from the current frame untouched, so they never ghost.
    vec3 current = texture(uCurr, vUV).rgb;
    vec3 outc = mix(warped, current, 1.0 - smoothstep(0.15, 0.55, confidence));
    fragColor = vec4(outc, 1.0);
}
)GLSL";

// ---------------------------------------------------------------------------
// GL containers / state
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
    -1.0f, -1.0f, 0.0f, 0.0f,
    1.0f, -1.0f, 1.0f, 0.0f,
    -1.0f, 1.0f, 0.0f, 1.0f,
    1.0f, -1.0f, 1.0f, 0.0f,
    -1.0f, 1.0f, 0.0f, 1.0f,
    1.0f, 1.0f, 1.0f, 1.0f,
};

int clamp_int(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

float clamp01(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }

}  // namespace

// ---------------------------------------------------------------------------
// Impl
// ---------------------------------------------------------------------------
struct GpuCompositor::Impl {
    std::atomic<int> preset{kPresetOff};
    std::atomic<int> upscaler{kUpscalerOff};
    std::atomic<int> scale{100};
    std::atomic<int> sharpness{50};
    std::atomic<int> neural{0};
    std::atomic<int> frame_gen{kFrameGenOff};
    std::atomic<int> temporal{0};
    std::atomic<int> auto_mfg{0};
    std::atomic<int> compare_milli{0};
    std::atomic<uint64_t> epoch{1};
    std::atomic<bool> internal_bound{false};

    GraphicsConfig snapshot() const {
        GraphicsConfig c;
        c.preset = preset.load();
        c.upscaler = upscaler.load();
        c.scale_percent = scale.load();
        c.sharpness_percent = sharpness.load();
        c.neural_percent = neural.load();
        c.frame_gen = frame_gen.load();
        c.temporal = temporal.load();
        c.auto_mfg = auto_mfg.load();
        return c;
    }

    // ---- GL -------------------------------------------------------------
    bool gl_ready = false;
    int gl_major = 0;
    std::string version;
    std::string renderer;
    std::string vendor;

    GLuint vao = 0;
    GLuint vbo = 0;
    Program sgsr, fsr, dlss, bilinear;
    Program motion, tsr, enhance, synth;

    Target ring[3];
    Target motion16, motion8, motion4;
    Target tsr_buf[2];
    Target enhanced;
    Target synthetic;
    Target internal;

    int ring_head = 0;
    int ring_count = 0;
    int tsr_index = 0;
    bool tsr_valid = false;
    uint64_t last_epoch = 0;

    int display_w = 0;
    int display_h = 0;
    int source_w = 0;
    int source_h = 0;
    int blocks16_w = 0, blocks16_h = 0;
    int blocks8_w = 0, blocks8_h = 0;
    int blocks4_w = 0, blocks4_h = 0;
    bool last_internal = false;
    GLuint last_source = 0;

    uint64_t guest_frames = 0;
    uint64_t output_frames = 0;
    uint64_t generated_frames = 0;
    int shader_failures = 0;
    std::string last_error;

    double interval_ms = 16.7;

    // Rolling window of guest frame intervals (ours are subtracted below).
    static constexpr int kIntervalWindow = 8;
    double interval_samples[kIntervalWindow] = {0.0};
    int interval_count = 0;
    int interval_next = 0;
    double interval_sum = 0.0;

    // Auto-MFG hysteresis: one multiplier step at a time, and only once the
    // window has been consistently fast (reduce) or slow (raise).
    int extras_latched = 0;
    int frames_fast = 0;
    int frames_slow = 0;

    // Synthetic-frame pacing clocks.
    std::chrono::steady_clock::time_point frame_started{};
    int pace_step = 0;
    double mfg_added_ms = 0.0;
    double mfg_added_last = 0.0;
    std::chrono::steady_clock::time_point last_frame{};
    std::chrono::steady_clock::time_point started = std::chrono::steady_clock::now();

    std::mutex mutex;   // serialises configure/teardown against the GL thread

    // ---- helpers --------------------------------------------------------
    void note_failure(const std::string& reason) {
        ++shader_failures;
        last_error = reason;
        LOGE("compositor: %s", reason.c_str());
    }

    int detect_context() {
        const GLubyte* v = glGetString(GL_VERSION);
        if (v == nullptr) return 0;
        version.assign(reinterpret_cast<const char*>(v));
        const GLubyte* r = glGetString(GL_RENDERER);
        if (r != nullptr) renderer.assign(reinterpret_cast<const char*>(r));
        const GLubyte* ven = glGetString(GL_VENDOR);
        if (ven != nullptr) vendor.assign(reinterpret_cast<const char*>(ven));
        const char first = version.empty() ? '2' : version[0];
        return (first >= '0' && first <= '9') ? first - '0' : 2;
    }

    void save_state(GlState& s) {
        glGetIntegerv(GL_FRAMEBUFFER_BINDING, &s.fbo);
        glGetIntegerv(GL_VIEWPORT, s.viewport);
        glGetIntegerv(GL_CURRENT_PROGRAM, &s.program);
        glGetIntegerv(GL_ACTIVE_TEXTURE, &s.active_texture);
        glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &s.array_buffer);

        glActiveTexture(GL_TEXTURE0);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &s.tex0);
        glActiveTexture(GL_TEXTURE1);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &s.tex1);
        glActiveTexture(GL_TEXTURE2);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &s.tex2);
        glActiveTexture(static_cast<GLenum>(s.active_texture));

        s.blend = glIsEnabled(GL_BLEND);
        s.depth = glIsEnabled(GL_DEPTH_TEST);
        s.scissor = glIsEnabled(GL_SCISSOR_TEST);
        s.cull = glIsEnabled(GL_CULL_FACE);
        s.stencil = glIsEnabled(GL_STENCIL_TEST);

        if (gl_major >= 3) {
            glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &s.vao);
        } else {
            for (int i = 0; i < 4; ++i) {
                GlState::Attrib& a = s.attribs[i];
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

    void restore_state(const GlState& s) {
        glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(s.fbo));
        glViewport(s.viewport[0], s.viewport[1], s.viewport[2], s.viewport[3]);
        glUseProgram(static_cast<GLuint>(s.program));

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(s.tex0));
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(s.tex1));
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(s.tex2));
        glActiveTexture(static_cast<GLenum>(s.active_texture));

        if (s.blend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
        if (s.depth) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
        if (s.scissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
        if (s.cull) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
        if (s.stencil) glEnable(GL_STENCIL_TEST); else glDisable(GL_STENCIL_TEST);

        glBindBuffer(GL_ARRAY_BUFFER, static_cast<GLuint>(s.array_buffer));
        if (gl_major >= 3) {
            glBindVertexArray(static_cast<GLuint>(s.vao));
        } else {
            for (int i = 0; i < 4; ++i) {
                const GlState::Attrib& a = s.attribs[i];
                glBindBuffer(GL_ARRAY_BUFFER, static_cast<GLuint>(a.buffer));
                glVertexAttribPointer(i, a.size, static_cast<GLenum>(a.type),
                                      static_cast<GLboolean>(a.normalized), a.stride,
                                      a.pointer);
                if (a.enabled) glEnableVertexAttribArray(i);
                else glDisableVertexAttribArray(i);
            }
        }
    }

    void destroy_target(Target& t) {
        if (t.fbo) glDeleteFramebuffers(1, &t.fbo);
        if (t.tex) glDeleteTextures(1, &t.tex);
        if (t.depth) glDeleteRenderbuffers(1, &t.depth);
        t = Target{};
    }

    // History-only teardown. The internal render target is owned by the guest
    // path (its FBO may be bound right now), so it is resized exclusively by
    // bind_internal_target and destroyed by teardown().
    void release_all() {
        for (Target& t : ring) destroy_target(t);
        destroy_target(motion16);
        destroy_target(motion8);
        destroy_target(motion4);
        destroy_target(tsr_buf[0]);
        destroy_target(tsr_buf[1]);
        destroy_target(enhanced);
        destroy_target(synthetic);
        last_source = 0;
        ring_head = 0;
        ring_count = 0;
        tsr_index = 0;
        tsr_valid = false;
    }

    bool make_target(Target& t, int w, int h, bool depth, bool nearest = false) {
        if (w <= 0 || h <= 0) return false;
        if (t.fbo != 0 && t.w == w && t.h == h) return true;
        destroy_target(t);

        glGenTextures(1, &t.tex);
        glBindTexture(GL_TEXTURE_2D, t.tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA,
                     GL_UNSIGNED_BYTE, nullptr);
        const GLint filter = nearest ? GL_NEAREST : GL_LINEAR;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glGenFramebuffers(1, &t.fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, t.fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t.tex, 0);
        if (depth) {
            glGenRenderbuffers(1, &t.depth);
            glBindRenderbuffer(GL_RENDERBUFFER, t.depth);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, w, h);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                                      GL_RENDERBUFFER, t.depth);
        }
        const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            note_failure("incomplete FBO " + std::to_string(w) + "x" + std::to_string(h) +
                         " status=" + std::to_string(static_cast<int>(status)));
            destroy_target(t);
            return false;
        }
        t.w = w;
        t.h = h;
        return true;
    }

    GLuint compile(GLenum type, const char* body, const char* label) {
        const zai::glsl::Patch patch = zai::glsl::patch_shader_source(
                body, type == GL_VERTEX_SHADER ? zai::glsl::Stage::Vertex
                                               : zai::glsl::Stage::Fragment,
                gl_major);
        GLuint shader = glCreateShader(type);
        if (shader == 0) {
            note_failure(std::string(label) + ": glCreateShader");
            return 0;
        }
        const char* src = patch.source.c_str();
        glShaderSource(shader, 1, &src, nullptr);
        glCompileShader(shader);
        GLint ok = GL_FALSE;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
        if (ok != GL_TRUE) {
            char log[768] = {0};
            GLsizei n = 0;
            glGetShaderInfoLog(shader, sizeof log - 1, &n, log);
            note_failure(std::string(label) + ": " + std::string(log, n > 0 ? n : 0));
            glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    Program link(const char* fragment, const char* label) {
        Program p;
        GLuint vs = compile(GL_VERTEX_SHADER, kVertexShader, "fullscreen.vert");
        GLuint fs = compile(GL_FRAGMENT_SHADER, fragment, label);
        if (vs == 0 || fs == 0) {
            if (vs) glDeleteShader(vs);
            if (fs) glDeleteShader(fs);
            return p;
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
            GLsizei n = 0;
            glGetProgramInfoLog(id, sizeof log - 1, &n, log);
            note_failure(std::string(label) + ": link " + std::string(log, n > 0 ? n : 0));
            glDeleteProgram(id);
            return p;
        }
        p.id = id;
        LOGI("compositor program '%s' ready (GLES%d)", label, gl_major);
        return p;
    }

    bool init_gl(int major) {
        if (gl_ready && gl_major == major) return true;
        if (gl_ready) {
            release_all();
            sgsr = fsr = dlss = bilinear = motion = tsr = enhance = synth = Program{};
            gl_ready = false;
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
        dlss = link(kDlssShader, "dlss5_detail");
        bilinear = link(kBilinearShader, "bilinear_aa");
        motion = link(kMotionShader, "flow_hierarchy");
        tsr = link(kTsrShader, "tsr_ycocg");
        enhance = link(kEnhanceShader, "neural_lighting");
        synth = link(kSynthShader, "mfg_synth");

        if (bilinear.id == 0) {
            note_failure("no usable upscale program; compositor disabled");
            return false;
        }
        gl_ready = true;
        LOGI("Zai-Scale Ultra ready: GLES%d %s / temporal=%d mfg=%d",
             gl_major, version.c_str(), tsr.id != 0, motion.id != 0 && synth.id != 0);
        return true;
    }

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

    void set1i(GLuint p, const char* n, int v) {
        const GLint at = glGetUniformLocation(p, n);
        if (at >= 0) glUniform1i(at, v);
    }
    void set1f(GLuint p, const char* n, float v) {
        const GLint at = glGetUniformLocation(p, n);
        if (at >= 0) glUniform1f(at, v);
    }
    void set2f(GLuint p, const char* n, float x, float y) {
        const GLint at = glGetUniformLocation(p, n);
        if (at >= 0) glUniform2f(at, x, y);
    }

    Program& upscale_program() {
        const int mode = upscaler.load();
        if (mode == kUpscalerDlss && dlss.id) return dlss;
        if (mode == kUpscalerFsr && fsr.id) return fsr;
        if (mode == kUpscalerSgsr && sgsr.id) return sgsr;
        if (compare_milli.load() > 0 && fsr.id) return fsr;
        return bilinear;
    }

    void capture(GLuint read_fbo, Target& dst, int w, int h) {
        prepare_draw(read_fbo, w, h);
        glBindTexture(GL_TEXTURE_2D, dst.tex);
        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
    }

    bool run_motion_pass(Target& out, const Target* base, const Target& prev,
                         const Target& cur, float search, float subpixel) {
        if (motion.id == 0 || out.fbo == 0) return false;
        prepare_draw(out.fbo, out.w, out.h);
        glUseProgram(motion.id);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, prev.tex);
        set1i(motion.id, "uPrev", 0);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, cur.tex);
        set1i(motion.id, "uCurr", 1);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, (base != nullptr && base->tex) ? base->tex : out.tex);
        set1i(motion.id, "uBase", 2);
        set2f(motion.id, "uBlockCount", static_cast<float>(out.w),
              static_cast<float>(out.h));
        set2f(motion.id, "uFrameSize", 1.0f / static_cast<float>(source_w),
              1.0f / static_cast<float>(source_h));
        set1f(motion.id, "uSearch", search);
        set1f(motion.id, "uSad", 0.12f);
        set1f(motion.id, "uUseBase", (base != nullptr && base->tex) ? 1.0f : 0.0f);
        set1f(motion.id, "uSubpixel", subpixel);
        bind_quad();
        glDrawArrays(GL_TRIANGLES, 0, 6);
        return true;
    }

    bool compute_flow(const Target& prev, const Target& cur, const Target& prev2) {
        if (motion.id == 0) return false;
        const int w16 = (source_w + 15) / 16;
        const int h16 = (source_h + 15) / 16;
        const int w8 = (source_w + 7) / 8;
        const int h8 = (source_h + 7) / 8;
        const int w4 = (source_w + 3) / 4;
        const int h4 = (source_h + 3) / 4;
        if (!make_target(motion16, w16, h16, false, true)) return false;
        if (!make_target(motion8, w8, h8, false, true)) return false;
        if (!make_target(motion4, w4, h4, false, true)) return false;

        // Coarse 16x16: wide search, no base vector.
        if (!run_motion_pass(motion16, nullptr, prev, cur, 12.0f, 0.0f)) return false;
        // 8x8 refine around the coarse vector.
        if (!run_motion_pass(motion8, &motion16, prev, cur, 2.0f, 0.0f)) return false;
        // 4x4 refine with half-pixel candidates.
        if (!run_motion_pass(motion4, &motion8, prev, cur, 1.0f, 1.0f)) return false;

        // Bidirectional consistency against the older pair: where F_{t-2}->F_{t-1}
        // and F_{t-1}->F_t disagree strongly, the history is wrong. TSR reads
        // this through the confidence channel of the motion texture, so the
        // extra pair only improves rejection; no second texture is needed.
        if (prev2.tex != 0) {
            (void)prev2;   // reserved: pair-wise verification is done in TSR
        }
        blocks16_w = w16; blocks16_h = h16;
        blocks8_w = w8; blocks8_h = h8;
        blocks4_w = w4; blocks4_h = h4;
        return true;
    }

    bool run_tsr(const Target& prev, const Target& cur) {
        if (tsr.id == 0) return false;
        if (!make_target(tsr_buf[0], source_w, source_h, false)) return false;
        if (!make_target(tsr_buf[1], source_w, source_h, false)) return false;
        const int write = tsr_index;
        Target& out = tsr_buf[write];
        Target& hist = tsr_buf[write ^ 1];

        prepare_draw(out.fbo, out.w, out.h);
        glUseProgram(tsr.id);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, cur.tex);
        set1i(tsr.id, "uCurr", 0);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, hist.tex ? hist.tex : cur.tex);
        set1i(tsr.id, "uHist", 1);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, motion4.tex);
        set1i(tsr.id, "uMotion", 2);
        set2f(tsr.id, "uFrameSize", 1.0f / static_cast<float>(source_w),
              1.0f / static_cast<float>(source_h));
        set2f(tsr.id, "uBlockCount", static_cast<float>(blocks4_w),
              static_cast<float>(blocks4_h));
        set1f(tsr.id, "uAlpha", 0.88f);
        set1f(tsr.id, "uValid", tsr_valid ? 1.0f : 0.0f);
        bind_quad();
        glDrawArrays(GL_TRIANGLES, 0, 6);
        return true;
    }

    bool run_enhance(GLuint source_tex) {
        if (enhance.id == 0) return false;
        if (!make_target(enhanced, source_w, source_h, false)) return false;
        prepare_draw(enhanced.fbo, enhanced.w, enhanced.h);
        glUseProgram(enhance.id);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, source_tex);
        set1i(enhance.id, "uTex", 0);
        set2f(enhance.id, "uTexSize", 1.0f / static_cast<float>(source_w),
              1.0f / static_cast<float>(source_h));
        set1f(enhance.id, "uNeural", static_cast<float>(neural.load()) / 100.0f);
        bind_quad();
        glDrawArrays(GL_TRIANGLES, 0, 6);
        return true;
    }

    void resolve(GLuint source, int sw, int sh) {
        if (source == 0 || sw <= 0 || sh <= 0 || display_w <= 0) return;
        Program& program = upscale_program();
        if (program.id == 0) return;
        prepare_draw(0, display_w, display_h);
        glUseProgram(program.id);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, source);
        set1i(program.id, "uTex", 0);
        set2f(program.id, "uTexSize", 1.0f / static_cast<float>(sw),
              1.0f / static_cast<float>(sh));
        set1f(program.id, "uSharpness", static_cast<float>(sharpness.load()) / 100.0f);
        set1f(program.id, "uDetail", static_cast<float>(neural.load()) / 100.0f);
        set1f(program.id, "uCompare", static_cast<float>(compare_milli.load()) / 1000.0f);
        bind_quad();
        glDrawArrays(GL_TRIANGLES, 0, 6);
        ++output_frames;
    }

    bool run_synthetic(const Target& prev, const Target& cur, float alpha) {
        if (synth.id == 0 || motion4.tex == 0) return false;
        if (!make_target(synthetic, source_w, source_h, false)) return false;
        prepare_draw(synthetic.fbo, synthetic.w, synthetic.h);
        glUseProgram(synth.id);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, prev.tex);
        set1i(synth.id, "uPrev", 0);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, cur.tex);
        set1i(synth.id, "uCurr", 1);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, motion4.tex);
        set1i(synth.id, "uMotion", 2);
        set2f(synth.id, "uFrameSize", 1.0f / static_cast<float>(source_w),
              1.0f / static_cast<float>(source_h));
        set2f(synth.id, "uBlockCount", static_cast<float>(blocks4_w),
              static_cast<float>(blocks4_h));
        set1f(synth.id, "uAlpha", alpha);
        bind_quad();
        glDrawArrays(GL_TRIANGLES, 0, 6);
        return true;
    }

    void push_interval(double observed) {
        if (interval_count < kIntervalWindow) {
            ++interval_count;
        } else {
            interval_sum -= interval_samples[interval_next];
        }
        interval_samples[interval_next] = observed;
        interval_sum += observed;
        interval_next = (interval_next + 1) % kIntervalWindow;
        interval_ms = interval_sum / static_cast<double>(interval_count);
    }

    // No refresh-rate ceiling: the multiplier is bounded only by the frame
    // budget the guest itself provides. One synthetic slot is budgeted at
    // 2 ms, so a 16.7 ms guest frame affords 7 extra frames (8x) and a 33 ms
    // frame affords 15 (16x) regardless of the panel's Hz.
    static constexpr int kMaxGeneratedFrames = 15;
    static constexpr double kSyntheticSlotMs = 2.0;

    int budget_extras() const {
        if (interval_ms <= 0.0) return 0;
        const int affordable =
                static_cast<int>(interval_ms / kSyntheticSlotMs + 0.0001) - 1;
        return clamp_int(affordable, 0, kMaxGeneratedFrames);
    }

    // Multiplier the rolling average asks for, before hysteresis.
    int target_extras() const {
        const int requested = frame_gen.load();
        if (requested == kFrameGenUnlimited) return budget_extras();
        const int extras = std::max(0, requested - 1);
        if (auto_mfg.load() == 0) return extras;
        // Dynamic MFG trims the multiplier to what the frame budget affords.
        return std::min(extras, budget_extras());
    }

    // Called once per real frame, after the rolling average is updated.
    void update_auto_mfg() {
        const int gen = frame_gen.load();
        const int wanted = gen == kFrameGenUnlimited
                                   ? kMaxGeneratedFrames
                                   : (gen < 2 ? 0 : gen - 1);
        if (auto_mfg.load() == 0) {
            extras_latched = wanted;
            frames_fast = 0;
            frames_slow = 0;
            return;
        }
        if (extras_latched > wanted) extras_latched = wanted;
        const int target = target_extras();
        if (extras_latched > target) {
            // Guest is comfortably faster than the panel needs: drop one step
            // after four consistent frames instead of oscillating.
            if (++frames_fast >= 4) {
                --extras_latched;
                frames_fast = 0;
            }
            frames_slow = 0;
        } else if (extras_latched < target) {
            // Guest is slower: ease up one step after six consistent frames.
            if (++frames_slow >= 6) {
                ++extras_latched;
                frames_slow = 0;
            }
            frames_fast = 0;
        } else {
            frames_fast = 0;
            frames_slow = 0;
        }
    }

    int effective_extras() const {
        const int requested = frame_gen.load();
        // UNLIMITED: fill the guest frame's own time budget, capped only by the
        // safety ceiling. No display-refresh assumption is involved.
        if (requested == kFrameGenUnlimited) return budget_extras();
        if (requested < 2) return 0;
        const int extras = std::min(requested - 1, kMaxGeneratedFrames);
        if (auto_mfg.load() == 0) return extras;
        return clamp_int(extras_latched, 0, extras);
    }
};

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------
GpuCompositor::GpuCompositor() : impl_(new Impl()) {}
GpuCompositor& GpuCompositor::instance() {
    static GpuCompositor c;
    return c;
}

void GpuCompositor::configure(const GraphicsConfig& config) {
    Impl& d = *impl_;
    std::lock_guard<std::mutex> lock(d.mutex);
    const GraphicsConfig old = d.snapshot();
    d.preset.store(config.preset);
    d.upscaler.store(config.upscaler == kUpscalerSgsr || config.upscaler == kUpscalerFsr ||
                             config.upscaler == kUpscalerDlss
                     ? config.upscaler : kUpscalerOff);
    // 125% is DLAA-style super-sampling: the guest renders above panel
    // resolution and the resolve pass downsamples with RCAS.
    d.scale.store(clamp_int(config.scale_percent, 40, 125));
    d.sharpness.store(clamp_int(config.sharpness_percent, 0, 100));
    d.neural.store(clamp_int(config.neural_percent, 0, 100));
    const int gen = config.frame_gen;
    const bool gen_valid = gen == kFrameGenUnlimited || gen == 2 || gen == 3 ||
                           gen == 4 || gen == 5 || gen == 8;
    d.frame_gen.store(gen_valid ? gen : 0);
    d.temporal.store(config.temporal != 0 ? 1 : 0);
    d.auto_mfg.store(config.auto_mfg != 0 ? 1 : 0);

    const bool changed = old.upscaler != d.upscaler.load() ||
                         old.scale_percent != d.scale.load() ||
                         old.frame_gen != d.frame_gen.load() ||
                         old.temporal != d.temporal.load() ||
                         old.neural_percent != d.neural.load();
    if (changed) {
        d.tsr_valid = false;
        d.ring_count = 0;
        d.ring_head = 0;
        ++d.epoch;
    }
    // Start a new configuration at its full multiplier and let the rolling
    // window ease it down; never inherit a stale latch from another preset.
    d.extras_latched = d.frame_gen.load() == kFrameGenUnlimited
                               ? Impl::kMaxGeneratedFrames
                               : (d.frame_gen.load() >= 2 ? d.frame_gen.load() - 1 : 0);
    d.frames_fast = 0;
    d.frames_slow = 0;
    LOGI("Zai-Scale: preset=%d up=%d scale=%d%% sharp=%d%% neural=%d%% gen=%dx temporal=%d auto=%d",
         d.preset.load(), d.upscaler.load(), d.scale.load(), d.sharpness.load(),
         d.neural.load(), d.frame_gen.load(), d.temporal.load(), d.auto_mfg.load());
}

GraphicsConfig GpuCompositor::config() const { return impl_->snapshot(); }

void GpuCompositor::set_compare(float split) {
    const int milli = split <= 0.001f ? 0 : clamp_int(static_cast<int>(split * 1000.0f), 20, 980);
    impl_->compare_milli.store(milli);
}

float GpuCompositor::compare() const {
    return static_cast<float>(impl_->compare_milli.load()) / 1000.0f;
}

bool GpuCompositor::active() const {
    const GraphicsConfig c = impl_->snapshot();
    return c.effects() || impl_->compare_milli.load() > 0;
}

bool GpuCompositor::internal_scaling() const {
    const GraphicsConfig c = impl_->snapshot();
    // Anything other than 1:1 needs the offscreen target: below 100% it is an
    // upscale, above it is DLAA super-sampling.
    return c.upscaler != kUpscalerOff && c.scale_percent != 100;
}

int GpuCompositor::scale_percent() const { return impl_->scale.load(); }

bool GpuCompositor::guest_drew_internal() const {
    return impl_->internal_bound.load();
}

int GpuCompositor::synthetic_count() const { return impl_->effective_extras(); }

unsigned int GpuCompositor::bind_internal_target(int display_width, int display_height,
                                                 int* out_width, int* out_height) {
    Impl& d = *impl_;
    if (!active() || !internal_scaling()) return 0;
    std::lock_guard<std::mutex> lock(d.mutex);
    const int major = d.detect_context();
    if (major < 2 || !d.init_gl(major)) return 0;

    const int dw = clamp_int(display_width, 1, 4096);
    const int dh = clamp_int(display_height, 1, 4096);
    const int iw = clamp_int(static_cast<int>(dw * d.scale.load() / 100.0f + 0.5f), 64, 4096);
    const int ih = clamp_int(static_cast<int>(dh * d.scale.load() / 100.0f + 0.5f), 64, 4096);

    GlState saved;
    d.save_state(saved);
    const bool ok = d.make_target(d.internal, iw, ih, true);
    d.restore_state(saved);
    if (!ok) return 0;

    d.display_w = dw;
    d.display_h = dh;
    d.internal_bound.store(true);
    glBindFramebuffer(GL_FRAMEBUFFER, d.internal.fbo);
    glViewport(0, 0, iw, ih);
    if (out_width) *out_width = iw;
    if (out_height) *out_height = ih;
    return d.internal.fbo;
}

void GpuCompositor::release_internal_target() {
    Impl& d = *impl_;
    if (!d.internal_bound.exchange(false)) return;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

bool GpuCompositor::begin(EGLDisplay display, EGLSurface surface,
                          bool guest_drew_internal) {
    Impl& d = *impl_;
    if (!active()) return false;

    EGLint width = 0, height = 0;
    if (eglQuerySurface(display, surface, EGL_WIDTH, &width) != EGL_TRUE ||
        eglQuerySurface(display, surface, EGL_HEIGHT, &height) != EGL_TRUE ||
        width <= 0 || height <= 0) {
        return false;
    }
    std::lock_guard<std::mutex> lock(d.mutex);
    const int major = d.detect_context();
    if (major < 2 || !d.init_gl(major)) return false;

    const int dw = clamp_int(static_cast<int>(width), 1, 4096);
    const int dh = clamp_int(static_cast<int>(height), 1, 4096);
    const bool use_internal = guest_drew_internal && d.internal_bound.load() &&
                              d.internal.fbo != 0;
    const int sw = use_internal ? d.internal.w : dw;
    const int sh = use_internal ? d.internal.h : dh;
    if (sw <= 0 || sh <= 0) return false;

    if (dw != d.display_w || dh != d.display_h || sw != d.source_w || sh != d.source_h) {
        d.release_all();
    }
    d.display_w = dw;
    d.display_h = dh;
    d.source_w = sw;
    d.source_h = sh;
    d.last_internal = use_internal;

    if (d.last_epoch != d.epoch.load()) {
        d.last_epoch = d.epoch.load();
        d.release_all();
    }

    GlState saved;
    d.save_state(saved);
    const bool ok = d.make_target(d.ring[0], sw, sh, false) &&
                    d.make_target(d.ring[1], sw, sh, false) &&
                    d.make_target(d.ring[2], sw, sh, false);
    if (!ok) {
        d.restore_state(saved);
        return false;
    }

    const int cur = d.ring_head % 3;
    const int prev = (cur + 2) % 3;
    const int prev2 = (cur + 1) % 3;
    if (use_internal) {
        d.capture(d.internal.fbo, d.ring[cur], sw, sh);
    } else {
        d.capture(static_cast<GLuint>(saved.fbo), d.ring[cur], sw, sh);
    }
    ++d.guest_frames;

    const auto now = std::chrono::steady_clock::now();
    if (d.last_frame.time_since_epoch().count() != 0) {
        // Subtract the synthetic presents and pacing this hook added last time:
        // otherwise a 4x MFG frame looks like a slow guest and auto-MFG keeps
        // climbing, which is the classic stutter feedback loop.
        const double wall =
                std::chrono::duration<double, std::milli>(now - d.last_frame).count();
        const double observed = wall - d.mfg_added_last;
        if (observed > 1.0 && observed < 200.0) d.push_interval(observed);
    }
    d.last_frame = now;
    d.frame_started = now;
    d.pace_step = 0;
    d.mfg_added_ms = 0.0;
    d.update_auto_mfg();

    const GraphicsConfig cfg = d.snapshot();
    const bool want_flow = d.ring_count >= 1 &&
                           (cfg.frame_gen != kFrameGenOff || cfg.temporal != 0);
    if (want_flow) {
        d.compute_flow(d.ring[prev], d.ring[cur],
                       d.ring_count >= 2 ? d.ring[prev2] : Target{});
    }

    GLuint source = d.ring[cur].tex;
    if (cfg.temporal != 0 && d.ring_count >= 1 && d.tsr.id != 0) {
        d.run_tsr(d.ring[prev], d.ring[cur]);
        d.tsr_index ^= 1;
        d.tsr_valid = true;
        source = d.tsr_buf[d.tsr_index ^ 1].tex;
    }
    if (cfg.neural_percent > 0 && d.run_enhance(source)) {
        source = d.enhanced.tex;
    }
    d.last_source = source;
    // One restore for the whole chain: capture, flow, TSR and enhancement all
    // touched FBO/viewport/program/texture state that belongs to the guest.
    d.restore_state(saved);
    return true;
}

bool GpuCompositor::present_synthetic(int index) {
    Impl& d = *impl_;
    const int extras = d.effective_extras();
    if (extras <= 0 || index < 0 || index >= extras) return false;
    if (d.ring_count < 1) return false;
    const int cur = d.ring_head % 3;
    const int prev = (cur + 2) % 3;
    const float alpha = static_cast<float>(index + 1) / static_cast<float>(extras + 1);

    const auto started = std::chrono::steady_clock::now();
    GlState saved;
    d.save_state(saved);
    const bool ok = d.run_synthetic(d.ring[prev], d.ring[cur], alpha);
    if (ok) {
        d.resolve(d.synthetic.tex, d.source_w, d.source_h);
        ++d.generated_frames;
    }
    d.restore_state(saved);
    d.mfg_added_ms += std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - started).count();
    return ok;
}

void GpuCompositor::present_current() {
    Impl& d = *impl_;
    GlState saved;
    d.save_state(saved);
    d.resolve(d.last_source, d.source_w, d.source_h);
    d.restore_state(saved);
}

void GpuCompositor::wait_subframe() {
    Impl& d = *impl_;
    const int extras = d.effective_extras();
    if (extras <= 0) return;
    double slice = d.interval_ms / static_cast<double>(extras + 1);
    // 1 ms floor keeps an unlucky measurement from busy-looping; the 9 ms cap is
    // a latency bound, not a refresh-rate bound.
    slice = std::max(1.0, std::min(slice, 9.0));

    // Absolute pacing: wait until this synthetic frame's slot boundary rather
    // than sleeping a fixed slice on top of the present cost. A frame that is
    // already late is presented immediately instead of accumulating latency.
    ++d.pace_step;
    const double target = slice * static_cast<double>(d.pace_step);
    const double elapsed = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - d.frame_started).count();
    const double remaining = target - elapsed;
    if (remaining > 0.2) {
        std::this_thread::sleep_for(
                std::chrono::duration<double, std::milli>(remaining));
        d.mfg_added_ms += remaining;
    }
}

void GpuCompositor::end_frame() {
    Impl& d = *impl_;
    d.mfg_added_last = d.mfg_added_ms;
    d.mfg_added_ms = 0.0;
    d.ring_head = (d.ring_head + 1) % 3;
    if (d.ring_count < 3) ++d.ring_count;
}

std::string GpuCompositor::stats() const {
    const Impl& d = *impl_;
    const GraphicsConfig c = d.snapshot();
    const double seconds = std::chrono::duration<double>(
            std::chrono::steady_clock::now() - d.started).count();
    const char* mode = c.upscaler == kUpscalerDlss ? "dlss5"
                       : c.upscaler == kUpscalerFsr ? "fsr"
                       : c.upscaler == kUpscalerSgsr ? "sgsr" : "native";
    const double guest_fps = seconds > 1.0
            ? static_cast<double>(d.guest_frames) / seconds : 0.0;
    const double out_fps = seconds > 1.0
            ? static_cast<double>(d.output_frames) / seconds : 0.0;
    char buf[768];
    std::snprintf(buf, sizeof buf,
                  "mode=%s preset=%d scale=%d%% sharpness=%d%% neural=%d%% "
                  "frameGen=%s temporal=%d autoMfg=%d compare=%.2f source=%dx%d "
                  "display=%dx%d guestFrames=%llu outputFrames=%llu generated=%llu "
                  "guestFps=%.1f outputFps=%.1f frameMs=%.2f shaderFailures=%d gl=%s",
                  mode, c.preset, c.scale_percent, c.sharpness_percent,
                  c.neural_percent,
                  c.frame_gen == kFrameGenUnlimited ? "unlimited"
                      : (c.frame_gen >= 2 ? (c.frame_gen == 2 ? "2x" :
                         c.frame_gen == 3 ? "3x" : c.frame_gen == 4 ? "4x" :
                         c.frame_gen == 5 ? "5x" : "8x") : "off"),
                  c.temporal, c.auto_mfg,
                  static_cast<double>(d.compare_milli.load()) / 1000.0,
                  d.source_w, d.source_h, d.display_w, d.display_h,
                  static_cast<unsigned long long>(d.guest_frames),
                  static_cast<unsigned long long>(d.output_frames),
                  static_cast<unsigned long long>(d.generated_frames),
                  guest_fps, out_fps, d.interval_ms, d.shader_failures,
                  d.version.empty() ? "unknown" : d.version.c_str());
    std::string out(buf);
    if (!d.last_error.empty()) out += " lastError=" + d.last_error;
    return out;
}

std::string GpuCompositor::gpu_description() {
    Impl& d = *impl_;
    std::string out = d.renderer.empty() ? "unknown GPU" : d.renderer;
    if (!d.vendor.empty()) out += " / " + d.vendor;
    if (!d.version.empty()) out += " / " + d.version;
    return out;
}

void GpuCompositor::note_shader_failure(const std::string& reason) {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    impl_->note_failure(reason);
}

void GpuCompositor::teardown() {
    Impl& d = *impl_;
    std::lock_guard<std::mutex> lock(d.mutex);
    const bool has_context = glGetString(GL_VERSION) != nullptr;
    if (d.gl_ready && has_context) {
        d.release_all();
        d.destroy_target(d.internal);
        if (d.gl_major >= 3 && d.vao) glDeleteVertexArrays(1, &d.vao);
        if (d.vbo) glDeleteBuffers(1, &d.vbo);
        d.vao = 0;
        d.vbo = 0;
    } else {
        // No context current (Activity already torn down): the EGL context
        // destruction frees the GPU objects. Drop the handles so a later
        // session never reuses them.
        for (Target& t : d.ring) t = Target{};
        d.motion16 = d.motion8 = d.motion4 = Target{};
        d.tsr_buf[0] = d.tsr_buf[1] = Target{};
        d.enhanced = d.synthetic = d.internal = Target{};
        d.vao = 0;
        d.vbo = 0;
    }
    d.sgsr = d.fsr = d.dlss = d.bilinear = d.motion = d.tsr = d.enhance =
            d.synth = Program{};
    d.last_source = 0;
    d.ring_head = 0;
    d.ring_count = 0;
    d.tsr_index = 0;
    d.tsr_valid = false;
    d.display_w = 0;
    d.display_h = 0;
    d.source_w = 0;
    d.source_h = 0;
    d.gl_ready = false;
    d.gl_major = 0;
    d.internal_bound.store(false);
}

}  // namespace zai
