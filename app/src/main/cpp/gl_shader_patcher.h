// =============================================================================
//  gl_shader_patcher.h  (Zai-Bridge: driver-agnostic GLSL normaliser)
//
//  Legacy titles ship shaders written for whatever GPU the port was built on:
//  a fragment shader with no precision qualifier (rejected by strict Mali and
//  Adreno ES 3 drivers), desktop GLSL with a `#version 120` header, or ES 3
//  source that still uses `texture2D` / `varying` / `attribute`. Every source
//  string that reaches glShaderSource is normalised here first, so the same
//  game compiles on Adreno, Mali, Immortalis, PowerVR and Xclipse.
// =============================================================================
#pragma once

#include <string>

namespace zai {
namespace glsl {

enum class Stage { Vertex, Fragment };

struct Patch {
    std::string source;
    bool changed = false;
    bool added_precision = false;
    bool translated_es3 = false;
    bool stripped_desktop_version = false;
};

// Normalises a shader for the host context. `gles_major` is the host's GLES
// major version (1, 2 or 3).
Patch patch_shader_source(const std::string& source, Stage stage, int gles_major);

// Extends the driver's GL_EXTENSIONS string with the standard OES/EXT
// capabilities that every supported mobile GPU class has but some drivers
// still omit from the legacy ES2 string. Only capabilities whose entry points
// are core in ES3 (or already universal) are added, so a guest that probes one
// never receives a promise the driver cannot keep.
std::string merge_extensions(const std::string& host_extensions);

}  // namespace glsl
}  // namespace zai
