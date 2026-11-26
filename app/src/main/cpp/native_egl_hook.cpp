// =============================================================================
//  native_egl_hook.cpp  (Zai-Bridge: 64-bit / 32-bit PLT/GOT hook)
//
//  Why this exists
//  ---------------
//  Java's GLSurfaceView swaps through framework native methods, which
//  egl_swap_hook.cpp already owns. A modern (64-bit or split-APK) game instead
//  links libEGL.so and libGLESv2.so directly: its renderer calls
//  eglSwapBuffers / glBindFramebuffer / glViewport through its own GOT, on its
//  own thread. Without interposing those slots the guest renders straight to
//  the window and Zai-Scale Ultra never sees a frame.
//
//  How it works
//  ------------
//   * dl_iterate_phdr() enumerates every mapped module.
//   * Each module's PT_DYNAMIC is parsed for DT_SYMTAB / DT_STRTAB and the
//     REL(A) relocation tables (DT_JMPREL + DT_REL/DT_RELA).
//   * A relocation whose symbol is one of our targets and whose type is
//     GLOB_DAT/JUMP_SLOT is a pointer-sized slot: it is rewritten to the
//     bridge's trampoline. The page is made writable first, and returned to
//     read-only when it lives inside PT_GNU_RELRO, so the hardening the loader
//     applied stays applied.
//   * The bridge's own module and the system graphics libraries are skipped,
//     so the compositor's internal GL calls keep their real entry points and no
//     recursion is possible.
//
//  Every trampoline forwards to the real symbol resolved with dlsym() and is
//  guarded by a thread-local reentrancy flag: an unsupported driver, a failed
//  mprotect or a missing symbol degrades to plain passthrough.
// =============================================================================

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>

#include <EGL/egl.h>
#include <GLES3/gl3.h>

#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "gpu_compositor.h"
#include "native_egl_hook.h"

#define LOG_TAG "ARM64_WRAPPER"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if defined(__LP64__)
#define ZAI_R_SYM(info) ELF64_R_SYM(info)
#define ZAI_R_TYPE(info) ELF64_R_TYPE(info)
#else
#define ZAI_R_SYM(info) ELF32_R_SYM(info)
#define ZAI_R_TYPE(info) ELF32_R_TYPE(info)
#endif

namespace {

using SwapFn = EGLBoolean (*)(EGLDisplay, EGLSurface);
using SwapDamageFn = EGLBoolean (*)(EGLDisplay, EGLSurface, const EGLint*, EGLint);
using BindFramebufferFn = void (*)(GLenum, GLuint);
using ViewportFn = void (*)(GLint, GLint, GLsizei, GLsizei);
using ScissorFn = void (*)(GLint, GLint, GLsizei, GLsizei);
using CurrentDisplayFn = EGLDisplay (*)();
using CurrentSurfaceFn = EGLSurface (*)(EGLint);
using QuerySurfaceFn = EGLBoolean (*)(EGLDisplay, EGLSurface, EGLint, EGLint*);

std::once_flag g_resolve_once;
SwapFn g_real_swap = nullptr;
SwapDamageFn g_real_swap_damage = nullptr;
BindFramebufferFn g_real_bind_framebuffer = nullptr;
ViewportFn g_real_viewport = nullptr;
ScissorFn g_real_scissor = nullptr;
CurrentDisplayFn g_egl_current_display = nullptr;
CurrentSurfaceFn g_egl_current_surface = nullptr;
QuerySurfaceFn g_egl_query_surface = nullptr;

std::atomic<uint64_t> g_swaps{0};
std::atomic<uint64_t> g_generated{0};
std::atomic<uint64_t> g_damage_swaps{0};
std::atomic<uint64_t> g_redirects{0};
std::atomic<uint64_t> g_passthrough_errors{0};

thread_local bool t_in_trampoline = false;
thread_local bool t_guest_target = false;

const std::chrono::steady_clock::time_point g_started = std::chrono::steady_clock::now();

// ---------------------------------------------------------------------------
// Module bookkeeping
// ---------------------------------------------------------------------------
struct ModuleInfo {
    std::string name;
    uintptr_t relro_begin = 0;
    uintptr_t relro_end = 0;
};

std::mutex g_targets_mutex;
std::vector<zai::plthook::Target> g_targets;
std::atomic<bool> g_installed{false};

std::atomic<int> g_modules_scanned{0};
std::atomic<int> g_slots_seen{0};
std::atomic<int> g_slots_patched{0};

bool contains_icase(const char* haystack, const char* needle) {
    if (!haystack || !needle || !*needle) return false;
    const size_t nlen = std::strlen(needle);
    for (const char* p = haystack; *p != '\0'; ++p) {
        size_t i = 0;
        while (i < nlen && p[i] != '\0' &&
               std::tolower(static_cast<unsigned char>(p[i])) ==
               std::tolower(static_cast<unsigned char>(needle[i]))) {
            ++i;
        }
        if (i == nlen) return true;
    }
    return false;
}

bool is_excluded(const char* name) {
    if (name == nullptr || name[0] == '\0') return true;  // main executable
    // Never patch platform/system/vendor/APEX/OEM GPU driver modules across
    // Qualcomm Adreno, ARM Mali/Immortalis, PowerVR/IMG, Samsung Xclipse, or ANGLE.
    static const char* const kDenyPrefixes[] = {
            "/system/",
            "/vendor/",
            "/apex/",
            "/odm/",
            "/product/",
            "/system_ext/",
    };
    for (const char* prefix : kDenyPrefixes) {
        if (std::strncmp(name, prefix, std::strlen(prefix)) == 0) return true;
    }
    static const char* const kDeny[] = {
            "libwrapper.so",
            "libEGL",
            "libGLES",
            "libGLES_mali",
            "libmali",
            "libadreno",
            "libxclipse",
            "libvulkan",
            "libIMG",
            "libPVR",
            "libusc",
            "angle",
            "swiftshader",
            "libc.so",
            "libm.so",
            "libdl.so",
            "libandroid",
            "libui.so",
            "libgui.so",
            "libhwui",
            "linker",
            "libutils.so",
            "libcutils.so",
    };
    for (const char* deny : kDeny) {
        if (contains_icase(name, deny)) return true;
    }
    // Guest renderers keep their original basename, e.g. libunity.so / libil2cpp.so / libUE4.so.
    return false;
}

uintptr_t relocated(const dl_phdr_info* info, ElfW(Addr) address) {
    return address >= info->dlpi_addr ? static_cast<uintptr_t>(address)
                                      : static_cast<uintptr_t>(info->dlpi_addr + address);
}

bool slot_writable(uintptr_t address) {
    const long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) return false;
    const uintptr_t begin = address & ~(static_cast<uintptr_t>(page) - 1);
    return mprotect(reinterpret_cast<void*>(begin), static_cast<size_t>(page),
                    PROT_READ | PROT_WRITE) == 0;
}

void slot_harden(uintptr_t address, bool inside_relro) {
    if (!inside_relro) return;  // leave a non-RELRO GOT writable, as it was
    const long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) return;
    const uintptr_t begin = address & ~(static_cast<uintptr_t>(page) - 1);
    mprotect(reinterpret_cast<void*>(begin), static_cast<size_t>(page), PROT_READ);
}

// Applies one relocation table. Returns the number of slots rewritten.
int patch_relocations(const dl_phdr_info* info, const ElfW(Rela)* rela, size_t count,
                      const ElfW(Sym)* symtab, const char* strtab, bool has_addend,
                      uintptr_t relro_begin, uintptr_t relro_end) {
    int patched = 0;
    for (size_t i = 0; i < count; ++i) {
        const uintptr_t info_word = has_addend ? rela[i].r_info
                                               : reinterpret_cast<const ElfW(Rel)*>(rela)[i].r_info;
        const int type = static_cast<int>(ZAI_R_TYPE(info_word));
#if defined(__aarch64__)
        const bool pointer_slot = type == R_AARCH64_GLOB_DAT || type == R_AARCH64_JUMP_SLOT;
#elif defined(__arm__)
        const bool pointer_slot = type == R_ARM_GLOB_DAT || type == R_ARM_JUMP_SLOT;
#elif defined(__x86_64__)
        const bool pointer_slot = type == R_X86_64_GLOB_DAT || type == R_X86_64_JUMP_SLOT;
#else
        const bool pointer_slot = type == R_386_GLOB_DAT || type == R_386_JMP_SLOT;
#endif
        if (!pointer_slot) continue;

        const size_t symbol_index = static_cast<size_t>(ZAI_R_SYM(info_word));
        if (symbol_index == 0 || strtab == nullptr || symtab == nullptr) continue;
        const char* symbol = strtab + symtab[symbol_index].st_name;
        if (symbol == nullptr || symbol[0] == '\0') continue;

        ++g_slots_seen;

        const uintptr_t offset = has_addend ? rela[i].r_offset
                                            : reinterpret_cast<const ElfW(Rel)*>(rela)[i].r_offset;
        const uintptr_t slot = relocated(info, static_cast<ElfW(Addr)>(offset));

        for (const zai::plthook::Target& target : g_targets) {
            if (std::strcmp(symbol, target.symbol) != 0) continue;
            void* current = *reinterpret_cast<void**>(slot);
            if (current == target.replacement) break;  // already ours
            if (!slot_writable(slot)) {
                if (g_passthrough_errors++ == 0) {
                    LOGE("PLT hook: mprotect failed for %s in %s", symbol,
                         info->dlpi_name);
                }
                break;
            }
            *reinterpret_cast<void**>(slot) = target.replacement;
            const bool relro = relro_end > relro_begin && slot >= relro_begin &&
                               slot < relro_end;
            slot_harden(slot, relro);
            ++patched;
            ++g_slots_patched;
            break;
        }
    }
    return patched;
}

int patch_module(const dl_phdr_info* info) {
    if (info == nullptr || is_excluded(info->dlpi_name)) return 0;

    const ElfW(Dyn)* dynamic = nullptr;
    uintptr_t relro_begin = 0;
    uintptr_t relro_end = 0;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr)& ph = info->dlpi_phdr[i];
        if (ph.p_type == PT_DYNAMIC) {
            dynamic = reinterpret_cast<const ElfW(Dyn)*>(
                    relocated(info, static_cast<ElfW(Addr)>(ph.p_vaddr)));
        } else if (ph.p_type == PT_GNU_RELRO) {
            relro_begin = relocated(info, static_cast<ElfW(Addr)>(ph.p_vaddr));
            relro_end = relro_begin + static_cast<uintptr_t>(ph.p_memsz);
        }
    }
    if (dynamic == nullptr) return 0;

    uintptr_t symtab_addr = 0;
    uintptr_t strtab_addr = 0;
    uintptr_t jmprel_addr = 0;
    size_t jmprel_size = 0;
    uintptr_t rel_addr = 0;
    size_t rel_size = 0;
    uintptr_t rela_addr = 0;
    size_t rela_size = 0;
    int64_t plt_rel_type = DT_RELA;

    for (const ElfW(Dyn)* d = dynamic; d->d_tag != DT_NULL; ++d) {
        switch (d->d_tag) {
            case DT_SYMTAB: symtab_addr = relocated(info, d->d_un.d_ptr); break;
            case DT_STRTAB: strtab_addr = relocated(info, d->d_un.d_ptr); break;
            case DT_JMPREL: jmprel_addr = relocated(info, d->d_un.d_ptr); break;
            case DT_PLTRELSZ: jmprel_size = static_cast<size_t>(d->d_un.d_val); break;
            case DT_PLTREL: plt_rel_type = static_cast<int64_t>(d->d_un.d_val); break;
            case DT_REL: rel_addr = relocated(info, d->d_un.d_ptr); break;
            case DT_RELSZ: rel_size = static_cast<size_t>(d->d_un.d_val); break;
            case DT_RELA: rela_addr = relocated(info, d->d_un.d_ptr); break;
            case DT_RELASZ: rela_size = static_cast<size_t>(d->d_un.d_val); break;
            default: break;
        }
    }
    if (symtab_addr == 0 || strtab_addr == 0) return 0;

    const ElfW(Sym)* symtab = reinterpret_cast<const ElfW(Sym)*>(symtab_addr);
    const char* strtab = reinterpret_cast<const char*>(strtab_addr);

    ++g_modules_scanned;
    int patched = 0;

    if (jmprel_addr != 0 && jmprel_size > 0) {
        if (plt_rel_type == DT_RELA) {
            patched += patch_relocations(
                    info, reinterpret_cast<const ElfW(Rela)*>(jmprel_addr),
                    jmprel_size / sizeof(ElfW(Rela)), symtab, strtab, true,
                    relro_begin, relro_end);
        } else {
            patched += patch_relocations(
                    info, reinterpret_cast<const ElfW(Rela)*>(jmprel_addr),
                    jmprel_size / sizeof(ElfW(Rel)), symtab, strtab, false,
                    relro_begin, relro_end);
        }
    }
    if (rela_addr != 0 && rela_size > 0) {
        patched += patch_relocations(
                info, reinterpret_cast<const ElfW(Rela)*>(rela_addr),
                rela_size / sizeof(ElfW(Rela)), symtab, strtab, true,
                relro_begin, relro_end);
    }
    if (rel_addr != 0 && rel_size > 0) {
        patched += patch_relocations(
                info, reinterpret_cast<const ElfW(Rela)*>(rel_addr),
                rel_size / sizeof(ElfW(Rel)), symtab, strtab, false,
                relro_begin, relro_end);
    }
    return patched;
}

int iterate_and_patch() {
    int total = 0;
    dl_iterate_phdr([](struct dl_phdr_info* info, size_t, void* data) -> int {
        int* sum = static_cast<int*>(data);
        *sum += patch_module(info);
        return 0;  // keep walking every module
    }, &total);
    return total;
}

// ---------------------------------------------------------------------------
// Real symbol resolution
// ---------------------------------------------------------------------------
void resolve_egl_once() {
    void* egl = dlopen("libEGL.so", RTLD_NOW | RTLD_LOCAL);
    if (egl != nullptr) {
        g_real_swap = reinterpret_cast<SwapFn>(dlsym(egl, "eglSwapBuffers"));
        g_real_swap_damage = reinterpret_cast<SwapDamageFn>(
                dlsym(egl, "eglSwapBuffersWithDamageKHR"));
        g_egl_current_display = reinterpret_cast<CurrentDisplayFn>(
                dlsym(egl, "eglGetCurrentDisplay"));
        g_egl_current_surface = reinterpret_cast<CurrentSurfaceFn>(
                dlsym(egl, "eglGetCurrentSurface"));
        g_egl_query_surface = reinterpret_cast<QuerySurfaceFn>(
                dlsym(egl, "eglQuerySurface"));
    }
    void* gles = dlopen("libGLESv2.so", RTLD_NOW | RTLD_LOCAL);
    if (gles != nullptr) {
        g_real_bind_framebuffer = reinterpret_cast<BindFramebufferFn>(
                dlsym(gles, "glBindFramebuffer"));
        g_real_viewport = reinterpret_cast<ViewportFn>(dlsym(gles, "glViewport"));
        g_real_scissor = reinterpret_cast<ScissorFn>(dlsym(gles, "glScissor"));
    }
}

// ---------------------------------------------------------------------------
// Frame presentation shared by both swap trampolines
// ---------------------------------------------------------------------------
bool surface_size(EGLDisplay dpy, EGLSurface surf, int* width, int* height) {
    if (dpy == EGL_NO_DISPLAY || surf == EGL_NO_SURFACE || g_egl_query_surface == nullptr) {
        return false;
    }
    EGLint w = 0;
    EGLint h = 0;
    if (g_egl_query_surface(dpy, surf, EGL_WIDTH, &w) != EGL_TRUE ||
        g_egl_query_surface(dpy, surf, EGL_HEIGHT, &h) != EGL_TRUE) {
        return false;
    }
    if (w <= 0 || h <= 0) return false;
    *width = static_cast<int>(w);
    *height = static_cast<int>(h);
    return true;
}

bool present_through_compositor(EGLDisplay dpy, EGLSurface surf, bool guest_internal) {
    zai::GpuCompositor& compositor = zai::GpuCompositor::instance();
    if (!compositor.active()) return false;
    if (!compositor.begin(dpy, surf, guest_internal)) {
        if (guest_internal) {
            compositor.release_internal_target();
            if (g_real_bind_framebuffer != nullptr) g_real_bind_framebuffer(GL_FRAMEBUFFER, 0);
        }
        return false;
    }

    const int extras = compositor.synthetic_count();
    for (int i = 0; i < extras; ++i) {
        if (!compositor.present_synthetic(i)) break;
        if (g_real_swap != nullptr && g_real_swap(dpy, surf) == EGL_TRUE) {
            zai::plthook::note_swap(true);
        }
        compositor.wait_subframe();
    }
    compositor.present_current();
    compositor.end_frame();
    return true;
}

EGLBoolean trampoline_swap(EGLDisplay dpy, EGLSurface surf) {
    std::call_once(g_resolve_once, resolve_egl_once);
    if (g_real_swap == nullptr) return EGL_FALSE;
    if (t_in_trampoline) return g_real_swap(dpy, surf);

    t_in_trampoline = true;
    zai::GpuCompositor& compositor = zai::GpuCompositor::instance();
    const bool active = compositor.active();
    const bool guest_internal = t_guest_target && compositor.guest_drew_internal();
    if (active) {
        if (!present_through_compositor(dpy, surf, guest_internal)) {
            if (guest_internal) {
                compositor.release_internal_target();
                if (g_real_bind_framebuffer != nullptr) g_real_bind_framebuffer(GL_FRAMEBUFFER, 0);
            }
            compositor.note_passthrough_swap();
        }
    } else {
        // Zai-Scale is Off (100% scale, Off MFG): direct hardware swap with zero FBO blits.
        if (t_guest_target || compositor.guest_drew_internal()) {
            compositor.release_internal_target();
            if (g_real_bind_framebuffer != nullptr) g_real_bind_framebuffer(GL_FRAMEBUFFER, 0);
        }
        compositor.note_passthrough_swap();
    }
    t_guest_target = false;

    const EGLBoolean ok = g_real_swap(dpy, surf);
    if (ok == EGL_TRUE) {
        ++g_swaps;
        zai::plthook::note_swap(false);
    }
    t_in_trampoline = false;
    return ok;
}

EGLBoolean trampoline_swap_damage(EGLDisplay dpy, EGLSurface surf, const EGLint* rects,
                                  EGLint count) {
    std::call_once(g_resolve_once, resolve_egl_once);
    if (g_real_swap_damage == nullptr) {
        return trampoline_swap(dpy, surf);
    }
    if (t_in_trampoline) return g_real_swap_damage(dpy, surf, rects, count);

    ++g_damage_swaps;
    t_in_trampoline = true;
    zai::GpuCompositor& compositor = zai::GpuCompositor::instance();
    const bool guest_internal = t_guest_target && compositor.guest_drew_internal();
    if (compositor.active()) {
        if (!present_through_compositor(dpy, surf, guest_internal)) {
            if (guest_internal) {
                compositor.release_internal_target();
                if (g_real_bind_framebuffer != nullptr) g_real_bind_framebuffer(GL_FRAMEBUFFER, 0);
            }
            compositor.note_passthrough_swap();
        }
    } else {
        if (t_guest_target || compositor.guest_drew_internal()) {
            compositor.release_internal_target();
            if (g_real_bind_framebuffer != nullptr) g_real_bind_framebuffer(GL_FRAMEBUFFER, 0);
        }
        compositor.note_passthrough_swap();
    }
    t_guest_target = false;

    const EGLBoolean ok = g_real_swap_damage(dpy, surf, rects, count);
    if (ok == EGL_TRUE) {
        ++g_swaps;
        zai::plthook::note_swap(false);
    }
    t_in_trampoline = false;
    return ok;
}

void trampoline_bind_framebuffer(GLenum target, GLuint framebuffer) {
    std::call_once(g_resolve_once, resolve_egl_once);
    if (g_real_bind_framebuffer == nullptr) return;
    if (t_in_trampoline) {
        g_real_bind_framebuffer(target, framebuffer);
        return;
    }

    if (target == GL_READ_FRAMEBUFFER) {
        g_real_bind_framebuffer(target, framebuffer);
        return;
    }

    zai::GpuCompositor& compositor = zai::GpuCompositor::instance();
    if (!compositor.active() || !compositor.internal_scaling()) {
        if (t_guest_target) {
            t_guest_target = false;
            compositor.release_internal_target();
        }
        g_real_bind_framebuffer(target, framebuffer);
        return;
    }

    if (framebuffer != 0) {
        t_guest_target = false;
        g_real_bind_framebuffer(target, framebuffer);
        return;
    }

    EGLDisplay dpy = g_egl_current_display != nullptr
                             ? g_egl_current_display() : EGL_NO_DISPLAY;
    EGLSurface surf = g_egl_current_surface != nullptr
                              ? g_egl_current_surface(EGL_DRAW) : EGL_NO_SURFACE;
    int width = 0;
    int height = 0;
    if (surface_size(dpy, surf, &width, &height)) {
        int internal_w = 0;
        int internal_h = 0;
        t_in_trampoline = true;
        GLuint fbo = compositor.bind_internal_target(width, height,
                                                     &internal_w, &internal_h);
        if (fbo != 0) {
            g_real_bind_framebuffer(target, fbo);
            t_in_trampoline = false;
            t_guest_target = true;
            ++g_redirects;
            return;
        }
        t_in_trampoline = false;
    }
    t_guest_target = false;
    g_real_bind_framebuffer(target, framebuffer);
}

void trampoline_viewport(GLint x, GLint y, GLsizei width, GLsizei height) {
    std::call_once(g_resolve_once, resolve_egl_once);
    if (g_real_viewport == nullptr) return;
    zai::GpuCompositor& compositor = zai::GpuCompositor::instance();
    if (t_in_trampoline || !t_guest_target || !compositor.active() || !compositor.internal_scaling()) {
        g_real_viewport(x, y, width, height);
        return;
    }
    const int scale = compositor.scale_percent();
    const float factor = static_cast<float>(scale) / 100.0f;
    const GLsizei scaled_w = static_cast<GLsizei>(static_cast<float>(width) * factor + 0.5f);
    const GLsizei scaled_h = static_cast<GLsizei>(static_cast<float>(height) * factor + 0.5f);
    const GLint scaled_x = static_cast<GLint>(static_cast<float>(x) * factor + 0.5f);
    const GLint scaled_y = static_cast<GLint>(static_cast<float>(y) * factor + 0.5f);
    g_real_viewport(scaled_x, scaled_y,
                    scaled_w > 0 ? scaled_w : width,
                    scaled_h > 0 ? scaled_h : height);
}

void trampoline_scissor(GLint x, GLint y, GLsizei width, GLsizei height) {
    std::call_once(g_resolve_once, resolve_egl_once);
    if (g_real_scissor == nullptr) return;
    zai::GpuCompositor& compositor = zai::GpuCompositor::instance();
    if (t_in_trampoline || !t_guest_target || !compositor.active() || !compositor.internal_scaling()) {
        g_real_scissor(x, y, width, height);
        return;
    }
    const int scale = compositor.scale_percent();
    const float factor = static_cast<float>(scale) / 100.0f;
    const GLsizei scaled_w = static_cast<GLsizei>(static_cast<float>(width) * factor + 0.5f);
    const GLsizei scaled_h = static_cast<GLsizei>(static_cast<float>(height) * factor + 0.5f);
    const GLint scaled_x = static_cast<GLint>(static_cast<float>(x) * factor + 0.5f);
    const GLint scaled_y = static_cast<GLint>(static_cast<float>(y) * factor + 0.5f);
    g_real_scissor(scaled_x, scaled_y,
                   scaled_w > 0 ? scaled_w : width,
                   scaled_h > 0 ? scaled_h : height);
}

}  // namespace

// ---------------------------------------------------------------------------
// Public engine
// ---------------------------------------------------------------------------
namespace zai {
namespace plthook {

int patch(const Target* targets, int count) {
    std::call_once(g_resolve_once, resolve_egl_once);

    {
        std::lock_guard<std::mutex> lock(g_targets_mutex);
        for (int i = 0; i < count; ++i) {
            if (targets[i].symbol == nullptr || targets[i].replacement == nullptr) continue;
            bool known = false;
            for (const Target& existing : g_targets) {
                if (std::strcmp(existing.symbol, targets[i].symbol) == 0) {
                    known = true;
                    break;
                }
            }
            if (!known) g_targets.push_back(targets[i]);
        }
    }

    const int patched = iterate_and_patch();
    if (patched > 0) g_installed.store(true);
    LOGI("PLT hook: patched %d slot(s) across %d module(s), %d slot(s) inspected",
         patched, g_modules_scanned.load(), g_slots_seen.load());
    return patched;
}

int refresh() { return iterate_and_patch(); }

bool installed() { return g_installed.load(); }

void note_swap(bool generated) {
    if (generated) ++g_generated;
}

std::string stats() {
    const double seconds = std::chrono::duration<double>(
            std::chrono::steady_clock::now() - g_started).count();
    const uint64_t swaps = g_swaps.load();
    char buf[320];
    std::snprintf(buf, sizeof buf,
                  "plt=modules:%d slots:%d patched:%d redirects:%llu swaps:%llu "
                  "generated:%llu damageSwaps:%llu fps:%.1f",
                  g_modules_scanned.load(), g_slots_seen.load(), g_slots_patched.load(),
                  static_cast<unsigned long long>(g_redirects.load()),
                  static_cast<unsigned long long>(swaps),
                  static_cast<unsigned long long>(g_generated.load()),
                  static_cast<unsigned long long>(g_damage_swaps.load()),
                  seconds > 0.0 ? static_cast<double>(swaps) / seconds : 0.0);
    return std::string(buf);
}

}  // namespace plthook
}  // namespace zai

// ---------------------------------------------------------------------------
// JNI: installed by EglSwapHook.install() right after the Java EGL hooks
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ziauddin_zaibridge_core_EglSwapHook_nativeInstallPltHooks(
        JNIEnv* /*env*/, jclass /*clazz*/) {
    std::call_once(g_resolve_once, resolve_egl_once);

    static zai::plthook::Target kTargets[] = {
            {"eglSwapBuffers", reinterpret_cast<void*>(&trampoline_swap)},
            {"eglSwapBuffersWithDamageKHR", reinterpret_cast<void*>(&trampoline_swap_damage)},
            {"glBindFramebuffer", reinterpret_cast<void*>(&trampoline_bind_framebuffer)},
            {"glViewport", reinterpret_cast<void*>(&trampoline_viewport)},
            {"glScissor", reinterpret_cast<void*>(&trampoline_scissor)},
    };
    const int patched = zai::plthook::patch(kTargets, 5);
    if (patched == 0) {
        LOGI("PLT hook: no game module imports EGL/GLES yet; they will be patched "
             "after the renderer dlopen()s");
    }
    return patched > 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ziauddin_zaibridge_core_EglSwapHook_nativeRefreshPltHooks(
        JNIEnv* /*env*/, jclass /*clazz*/) {
    return static_cast<jint>(zai::plthook::refresh());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ziauddin_zaibridge_core_EglSwapHook_nativePltStats(
        JNIEnv* env, jclass /*clazz*/) {
    return env->NewStringUTF(zai::plthook::stats().c_str());
}
