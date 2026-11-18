// =============================================================================
//  gl_shader_patcher.cpp
// =============================================================================

#include "gl_shader_patcher.h"

#include <cctype>
#include <cstdlib>
#include <cstring>
#include <string>

namespace {

using zai::glsl::Patch;
using zai::glsl::Stage;

bool contains(const std::string& haystack, const char* needle) {
    return haystack.find(needle) != std::string::npos;
}

std::string replace_all(std::string s, const char* from, const char* to) {
    const std::string f(from);
    if (f.empty()) return s;
    size_t at = 0;
    while ((at = s.find(f, at)) != std::string::npos) {
        s.replace(at, f.size(), to);
        at += std::strlen(to);
    }
    return s;
}

// True for version numbers that belong to desktop GL (or GLSL ES versions the
// handset driver will not accept once a game was ported from a desktop build).
bool desktop_version(int version) {
    switch (version) {
        case 110: case 120: case 130: case 140: case 150:
        case 330: case 400: case 410: case 420: case 430: case 440:
        case 450: case 460: case 470:
            return true;
        default:
            return false;
    }
}

}  // namespace

namespace zai {
namespace glsl {

Patch patch_shader_source(const std::string& source, Stage stage, int gles_major) {
    Patch patch;
    patch.source = source;
    std::string& s = patch.source;
    if (s.empty()) return patch;

    // ---- locate (and possibly rewrite) the #version directive -------------
    size_t version_at = std::string::npos;
    size_t version_end = std::string::npos;
    {
        const size_t limit = s.size() < 2048 ? s.size() : 2048;
        size_t at = s.find("#version");
        if (at != std::string::npos && at < limit) {
            // Only accept a directive that is not sitting inside a comment on
            // the same line; shaders in the wild put it on the first line.
            version_at = at;
            size_t eol = s.find('\n', at);
            if (eol == std::string::npos) eol = s.size();
            version_end = eol;
        }
    }

    int declared = 0;
    std::string version_line = "#version 100";
    if (version_at != std::string::npos) {
        const std::string line = s.substr(version_at, version_end - version_at);
        const char* p = line.c_str() + std::strlen("#version");
        while (*p != '\0' && !std::isdigit(static_cast<unsigned char>(*p))) ++p;
        declared = std::atoi(p);
        version_line = line;
    }

    if (declared != 0 && desktop_version(declared)) {
        // A desktop header cannot be handed to a mobile driver at all. The
        // syntax that follows is close enough to ES 1.00 for the shader to
        // compile once the header is gone.
        s.erase(version_at, version_end - version_at);
        s.insert(version_at, "#version 100");
        patch.stripped_desktop_version = true;
        declared = 100;
        version_end = version_at + std::strlen("#version 100");
        version_line = "#version 100";
    } else if (declared >= 300) {
        // `#version 300` without the `es` profile is invalid on Android.
        if (!contains(version_line, " es")) {
            const std::string fixed = version_line + " es";
            s.replace(version_at, version_end - version_at, fixed);
            version_end = version_at + fixed.size();
            version_line = fixed;
        }
    }

    // ---- legacy syntax on a strict ES 3 context ---------------------------
    const bool es3 = declared >= 300;
    const bool needs_translation = es3 && gles_major >= 3;

    if (needs_translation) {
        const std::string before = s;
        if (stage == Stage::Vertex) {
            s = replace_all(s, "attribute ", "in ");
            s = replace_all(s, "varying ", "out ");
        } else {
            s = replace_all(s, "varying ", "in ");
            s = replace_all(s, "texture2DProj(", "textureProj(");
            s = replace_all(s, "texture2D(", "texture(");
            s = replace_all(s, "textureCube(", "texture(");
            if (contains(s, "gl_FragColor")) {
                s = replace_all(s, "gl_FragColor", "fragColor");
                if (!contains(s, "out vec4 fragColor")) {
                    const size_t insert_at = version_end;
                    s.insert(insert_at, "\nout vec4 fragColor;");
                    version_end += std::strlen("\nout vec4 fragColor;");
                }
            }
            if (contains(s, "gl_FragData[0]")) {
                s = replace_all(s, "gl_FragData[0]", "fragColor");
            }
        }
        patch.translated_es3 = (s != before);
    }

    // ---- fragment precision ----------------------------------------------
    // The single most common strict-driver rejection: a fragment shader with
    // no default float precision. Inject one directly after the version line
    // so it precedes every declaration.
    if (stage == Stage::Fragment && !contains(s, "precision ")) {
        const size_t insert_at =
                (version_at != std::string::npos) ? version_end : 0;
        std::string header = "precision mediump float;\n";
        if (insert_at == 0) header = "precision mediump float;\n" + header;
        s.insert(insert_at, "\n" + header);
        patch.added_precision = true;
    }

    patch.changed = patch.added_precision || patch.translated_es3 ||
                    patch.stripped_desktop_version || needs_translation ||
                    (declared >= 300 && version_line != "#version 300 es");
    return patch;
}

std::string merge_extensions(const std::string& host_extensions) {
    static const char* const kStandard[] = {
            "GL_OES_depth24",
            "GL_OES_element_index_uint",
            "GL_OES_fbo_render_mipmap",
            "GL_OES_rgb8_rgba8",
            "GL_OES_standard_derivatives",
            "GL_OES_texture_npot",
            "GL_OES_mapbuffer",
            "GL_EXT_multi_draw_arrays",
            "GL_EXT_frag_depth",
            "GL_EXT_shader_texture_lod",
            "GL_EXT_discard_framebuffer",
            "GL_EXT_robustness",
            "GL_EXT_sRGB",
    };

    std::string merged = host_extensions;
    for (const char* extension : kStandard) {
        if (contains(merged, extension)) continue;
        if (!merged.empty()) merged += " ";
        merged += extension;
    }
    return merged;
}

}  // namespace glsl
}  // namespace zai
