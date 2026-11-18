// =============================================================================
//  wrapper_arm64.cpp   (Zai-Bridge translation core)
//
//  ARM32 -> ARM64 userspace bridge: runs the legacy ARMv7 libraries of any
//  imported APK inside Zai-Bridge's 64-bit process by emulating them with
//  Unicorn and translating the JNI boundary in both directions.
//
//  The payload set is no longer discovered next to this shared object. It is
//  supplied per session by GameHostActivity, which asks the core to map
//  <filesDir>/games/<package>/payload/lib*32.so. That directory is assembled by
//  the ingestion engine from the guest APK's lib/armeabi-v7a entry.
//
//  Layout of the emulated (guest) 32-bit address space
//  ---------------------------------------------------
//    0x3000_0000  guest heap      - shadow structs, strings, ref handles
//    0x4000_0000  image #0        - libNimble.so      (16 MB slot per library)
//    0x4100_0000  image #1        - libapp.so
//    0x5000_0000  trap page       - JNI thunks + unresolved-import stubs
//    0x500F_0000  return sentinel - unmapped; uc_emu_start() "until" address
//    0x7FE0_0000  guest stack     - 1 MB, grows down from 0x7FF0_0000
//
//  How a JNI call is intercepted
//  -----------------------------
//  The guest never sees a real JNIEnv*. It sees a 32-bit shadow whose only
//  field points at a table of 32-bit "stub" addresses, one per JNI slot. Each
//  stub is a `bx lr` in the trap page that is caught by a UC_HOOK_CODE hook
//  before it executes; the hook performs the real (64-bit) JNI call, writes the
//  result to R0 and returns through LR. Calls in the other direction (ART ->
//  native method) enter through a generated ARM64 trampoline that spills x0-x7,
//  calls back into C++, and re-enters the emulator at the ARM32 function.
//
//  Intentionally NOT implemented yet (scaffold scope)
//  --------------------------------------------------
//   * Syscall-level bridging (open/read/mmap/pthread/clock_gettime/EAGL): only
//     the libc memory, string and log surface is forwarded to the host today.
//     Anything else still resolves to a logging stub that returns 0, so the
//     loader reports exactly what is missing instead of jumping into garbage.
//   * A host pointer can never become a guest pointer (the upper 32 bits would
//     be truncated), so malloc() is served from the emulated heap and the
//     memory routines marshal through guest memory rather than wrapping the
//     host call blindly.
//   * Long/double arguments and float/double returns across the JNI boundary.
//   * Nested re-entry (guest -> JNI method -> guest) inside a hook.
//   * ARM32 TLS (CP15 TPIDRURO), caller-saved VFP state, exception unwinding.
//   * Full JNINativeInterface coverage; unimplemented slots are logged once.
// =============================================================================

#include <jni.h>
#include <unicorn/unicorn.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_activity.h>
#include <android/configuration.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

#include "cpu_compat.h"
#include "egl_config_fallback.h"
#include "gl_shader_patcher.h"
#include "gpu_compositor.h"

#include <dirent.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cerrno>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <chrono>
#include <mutex>
#include <thread>
#include <cstring>
#include <ctime>
#include <functional>
#include <iterator>
#include <map>
#include <string>
#include <sys/time.h>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#define LOG_TAG "ARM64_WRAPPER"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Implemented at file scope: referenced by the trampoline generator and by the
// module-directory probe before their definitions are reached.
extern "C" uint64_t bridge_native_entry(uint32_t id, uint64_t* host_args);
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved);

namespace {

// Name of the import handler currently running, for crash diagnostics.
std::string g_last_import;

// ---------------------------------------------------------------------------
// Guest address space
// ---------------------------------------------------------------------------
constexpr uint32_t kHeapBase   = 0x30000000u;
constexpr size_t   kHeapSize   = 64u * 1024u * 1024u;

constexpr uint32_t kImageBase0 = 0x40000000u;
constexpr uint32_t kImageStride = 0x01000000u;  // 16 MB per library slot

constexpr uint32_t kStubBase     = 0x50000000u;
constexpr size_t   kStubStride   = 8;           // bytes per stub
constexpr size_t   kStubCount    = 1024;
constexpr size_t   kStubRegionSz = kStubCount * kStubStride;

constexpr uint32_t kReturnSentinel = 0x500F0000u;

constexpr uint32_t kStackTop  = 0x7FF00000u;
constexpr size_t   kStackSize = 1u * 1024u * 1024u;

// Guard against runaway guest code (a broken import bridge shows up as a hang).
// This is deliberately generous: a level-load legitimately burns hundreds of
// millions of instructions decoding textures, so the wall-clock limit in call()
// is what actually catches a guest that never returns.
constexpr uint64_t kMaxInstructions = 4000ull * 1000ull * 1000ull;

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------
constexpr uint32_t align_down(uint32_t v, uint32_t a) { return v & ~(a - 1u); }
constexpr uint32_t align_up(uint32_t v, uint32_t a) { return (v + a - 1u) & ~(a - 1u); }

constexpr uint32_t kPageSize = 4096u;

// ===========================================================================
// 1. ARM64 trampoline generator (host -> guest entry points)
//
// ART hands a native method whatever its Java signature says, so a single C
// function cannot serve every method. Each registered method gets a 40-byte
// ARM64 stub that spills x0-x7 into an array, calls the common C handler with
// (id, args), and returns whatever the handler leaves in x0.
// ===========================================================================
class HostTrampolines {
public:
    // The stub spills both the integer and the floating-point argument
    // registers: a Java native method with a double argument receives it in
    // xmmN / dN, never in the x0-x7 / rdi-r9 spill the original stub saved.
    // 8 int slots + 8 FP slots = 128 bytes, plus the literal pool.
    static constexpr size_t kStubSize = 160;
    static constexpr size_t kPoolSize = 256 * 1024;

    static bool init() {
        if (pool_) return true;
        void* p = mmap(nullptr, kPoolSize, PROT_READ | PROT_WRITE | PROT_EXEC,
                       MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (p == MAP_FAILED) {
            LOGE("trampoline pool: RWX mmap failed (%s). JNI downcalls unavailable.",
                 strerror(errno));
            return false;
        }
        pool_ = static_cast<uint8_t*>(p);
        used_ = 0;
        LOGI("trampoline pool: %p (%zu KB, RWX)", p, kPoolSize / 1024);
        return true;
    }

    static void* emit(uint32_t id, void* target) {
        if (!pool_ || used_ + kStubSize > kPoolSize) return nullptr;
        uintptr_t base = reinterpret_cast<uintptr_t>(pool_ + used_);
        std::memset(reinterpret_cast<void*>(base), 0, kStubSize);

#if defined(__aarch64__)
        uint32_t* w = reinterpret_cast<uint32_t*>(base);
        w[0]  = 0xD10203FFu; // sub  sp, sp, #128
        w[1]  = 0xA90007E0u; // stp  x0, x1, [sp, #0]
        w[2]  = 0xA9010FE2u; // stp  x2, x3, [sp, #16]
        w[3]  = 0xA90217E4u; // stp  x4, x5, [sp, #32]
        w[4]  = 0xA9031FE6u; // stp  x6, x7, [sp, #48]
        w[5]  = 0x6D0407E0u; // stp  d0, d1, [sp, #64]
        w[6]  = 0x6D050FE2u; // stp  d2, d3, [sp, #80]
        w[7]  = 0x6D0617E4u; // stp  d4, d5, [sp, #96]
        w[8]  = 0x6D071FE6u; // stp  d6, d7, [sp, #112]
        w[9]  = 0xD2800000u | ((id & 0xFFFFu) << 5);  // mov x0, #id
        w[10] = 0x910003E1u; // mov  x1, sp
        // Absolute indirect call. A direct `bl` only reaches +-128 MB and this
        // pool is allocated far away from the library on 64-bit Android, so a
        // relative branch would silently truncate and jump into arbitrary code.
        w[11] = 0x580000B0u; // ldr  x16, [pc, #20]  -> literal at w[16]
        w[12] = 0xD63F0200u; // blr  x16
        w[13] = 0x910203FFu; // add  sp, sp, #128
        w[14] = 0xD65F03C0u; // ret
        const uint64_t t = reinterpret_cast<uintptr_t>(target);
        w[15] = 0xD503201Fu; // nop (execution never reaches here)
        w[16] = static_cast<uint32_t>(t & 0xFFFFFFFFu);        // .quad target
        w[17] = static_cast<uint32_t>(t >> 32);
        w[18] = 0xD503201Fu; // padding
        w[19] = 0xD503201Fu; // padding
#elif defined(__x86_64__)
        // System V AMD64. Integer/pointer arguments arrive in rdi, rsi, rdx,
        // rcx, r8, r9 (7th+ on the stack); floating-point arguments arrive in
        // xmm0-xmm7. Both sets are spilled into one 16-slot array.
        uint8_t* b = reinterpret_cast<uint8_t*>(base);
        size_t at = 0;
        const uint8_t prologue[] = {
                0x48, 0x81, 0xEC, 0x88, 0x00, 0x00, 0x00,  // sub  rsp, 0x88
                0x48, 0x89, 0x3C, 0x24,              // mov  [rsp+0x00], rdi
                0x48, 0x89, 0x74, 0x24, 0x08,        // mov  [rsp+0x08], rsi
                0x48, 0x89, 0x54, 0x24, 0x10,        // mov  [rsp+0x10], rdx
                0x48, 0x89, 0x4C, 0x24, 0x18,        // mov  [rsp+0x18], rcx
                0x4C, 0x89, 0x44, 0x24, 0x20,        // mov  [rsp+0x20], r8
                0x4C, 0x89, 0x4C, 0x24, 0x28,        // mov  [rsp+0x28], r9
                0x48, 0x8B, 0x84, 0x24, 0x90, 0x00, 0x00, 0x00, // mov rax,[rsp+0x90]
                0x48, 0x89, 0x44, 0x24, 0x30,        // mov  [rsp+0x30], rax
                0x48, 0x8B, 0x84, 0x24, 0x98, 0x00, 0x00, 0x00, // mov rax,[rsp+0x98]
                0x48, 0x89, 0x44, 0x24, 0x38,        // mov  [rsp+0x38], rax
                0xF2, 0x0F, 0x11, 0x44, 0x24, 0x40,  // movsd [rsp+0x40], xmm0
                0xF2, 0x0F, 0x11, 0x4C, 0x24, 0x48,  // movsd [rsp+0x48], xmm1
                0xF2, 0x0F, 0x11, 0x54, 0x24, 0x50,  // movsd [rsp+0x50], xmm2
                0xF2, 0x0F, 0x11, 0x5C, 0x24, 0x58,  // movsd [rsp+0x58], xmm3
                0xF2, 0x0F, 0x11, 0x64, 0x24, 0x60,  // movsd [rsp+0x60], xmm4
                0xF2, 0x0F, 0x11, 0x6C, 0x24, 0x68,  // movsd [rsp+0x68], xmm5
                0xF2, 0x0F, 0x11, 0x74, 0x24, 0x70,  // movsd [rsp+0x70], xmm6
                0xF2, 0x0F, 0x11, 0x7C, 0x24, 0x78,  // movsd [rsp+0x78], xmm7
        };
        std::memcpy(b + at, prologue, sizeof prologue);
        at += sizeof prologue;
        b[at++] = 0xBF;                              // mov edi, imm32
        std::memcpy(b + at, &id, sizeof id);
        at += sizeof id;
        const uint8_t call_seq[] = {
                0x48, 0x89, 0xE6,                    // mov  rsi, rsp
                0x48, 0xB8,                          // mov  rax, imm64
        };
        std::memcpy(b + at, call_seq, sizeof call_seq);
        at += sizeof call_seq;
        const uint64_t t64 = reinterpret_cast<uintptr_t>(target);
        std::memcpy(b + at, &t64, sizeof t64);
        at += sizeof t64;
        const uint8_t epilogue[] = {
                0xFF, 0xD0,                          // call rax
                0x48, 0x81, 0xC4, 0x88, 0x00, 0x00, 0x00, // add rsp, 0x88
                0xC3,                                // ret
        };
        std::memcpy(b + at, epilogue, sizeof epilogue);
        at += sizeof epilogue;
        (void)at;
#else
#error "Zai-Bridge: unsupported host architecture"
#endif

        __builtin___clear_cache(reinterpret_cast<char*>(base),
                                reinterpret_cast<char*>(base + kStubSize));
        used_ += kStubSize;
        return reinterpret_cast<void*>(base);
    }

private:
    static uint8_t* pool_;
    static size_t   used_;
};

uint8_t* HostTrampolines::pool_ = nullptr;
size_t   HostTrampolines::used_ = 0;

// ===========================================================================
// 2. Emulator: one ARM32 Unicorn instance + guest memory helpers
// ===========================================================================
class Emulator {
public:
    bool init() {
        // UC_MODE_ARM is 0, so ARM|THUMB selects Thumb-2 with ARM interworking.
        const uc_mode mode = static_cast<uc_mode>(UC_MODE_ARM | UC_MODE_THUMB);
        uc_err err = uc_open(UC_ARCH_ARM, mode, &uc_);
        if (err != UC_ERR_OK) {
            LOGE("uc_open(ARM32) failed: %s", uc_strerror(err));
            return false;
        }

        // ---- VFP / NEON bring-up ------------------------------------------
        // A real kernel enables CP10/CP11 in CPACR and sets FPEXC.EN before it
        // ever returns to userspace. Unicorn leaves both cleared, so the first
        // VFP instruction a constructor executes (`vmov s0, r0`, `vldr s0,[r5]`)
        // is rejected as an undefined instruction. Every Android ARMv7 binary
        // is compiled assuming the kernel already did this, so the emulator has
        // to stand in for the kernel here, once, before any guest code runs.
        int32_t cpacr = 0x00F00000;   // cp10 + cp11: full access
        int32_t fpexc = 0x40000000;   // EN: VFP/NEON active
        uc_reg_write(uc_, UC_ARM_REG_C1_C0_2, &cpacr);
        uc_reg_write(uc_, UC_ARM_REG_FPEXC, &fpexc);

        int32_t seen_cpacr = 0, seen_fpexc = 0;
        uc_reg_read(uc_, UC_ARM_REG_C1_C0_2, &seen_cpacr);
        uc_reg_read(uc_, UC_ARM_REG_FPEXC, &seen_fpexc);
        LOGI("VFP bring-up: CPACR=%08x (cp10/11 %s), FPEXC=%08x (EN %s)", seen_cpacr,
             (seen_cpacr & 0x00F00000) == 0x00F00000 ? "granted" : "DENIED", seen_fpexc,
             (seen_fpexc & 0x40000000) ? "set" : "CLEAR");
        if ((seen_cpacr & 0x00F00000) != 0x00F00000 || !(seen_fpexc & 0x40000000)) {
            LOGE("VFP bring-up failed: every float instruction will trap");
            return false;
        }

        // TCG needs an executable buffer; report early if the app sandbox
        // forbids it rather than failing later inside uc_emu_start().
        void* probe = mmap(nullptr, kPageSize, PROT_READ | PROT_WRITE | PROT_EXEC,
                           MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (probe == MAP_FAILED) {
            LOGE("executable mmap denied (%s); TCG cannot run in this sandbox",
                 strerror(errno));
            return false;
        }
        munmap(probe, kPageSize);

        if (!map(kHeapBase, kHeapSize, "heap") ||
            !map(kStackTop - kStackSize, kStackSize, "stack") ||
            !map(kStubBase, kStubRegionSz, "trap page")) {
            return false;
        }

        // Every stub starts as `bx lr`; the code hook intercepts before it runs,
        // so this is only a safety net if a hook is ever missed.
        std::vector<uint8_t> stub(kStubStride, 0);
        for (size_t i = 0; i < kStubCount; ++i) {
            stub[0] = 0x1E; stub[1] = 0xFF; stub[2] = 0x2F; stub[3] = 0xE1;
            if (!write(kStubBase + static_cast<uint32_t>(i * kStubStride), stub.data(), 4)) {
                return false;
            }
        }

        // Log the exact faulting address and register file before the
        // emulator aborts. Without this, a wild data pointer only shows up as
        // "UC_ERR_READ_UNMAPPED" with no way to tell which guest table was
        // resolved to a code stub.
        err = uc_hook_add(uc_, &mem_hook_,
                          UC_HOOK_MEM_READ_UNMAPPED | UC_HOOK_MEM_WRITE_UNMAPPED |
                                  UC_HOOK_MEM_FETCH_UNMAPPED,
                          reinterpret_cast<void*>(&Emulator::mem_fault_thunk),
                          this, 1, 0);
        if (err != UC_ERR_OK) {
            LOGW("uc_hook_add(mem faults) failed: %s", uc_strerror(err));
        }

        LOGI("uc_open(UC_ARCH_ARM, ARM|THUMB) ok - guest heap @ %08x, stack @ %08x",
             kHeapBase, kStackTop - static_cast<uint32_t>(kStackSize));
        return true;
    }

    static void mem_fault_thunk(uc_engine* uc, uc_mem_type type, uint64_t address,
                                int size, int64_t value, void* user_data) {
        (void)value;
        (void)user_data;
        uint32_t pc = 0, lr = 0, sp = 0;
        uc_reg_read(uc, UC_ARM_REG_PC, &pc);
        uc_reg_read(uc, UC_ARM_REG_LR, &lr);
        uc_reg_read(uc, UC_ARM_REG_SP, &sp);
        uint32_t r[13] = {0};
        for (int i = 0; i < 13; ++i) {
            uc_reg_read(uc, UC_ARM_REG_R0 + i, &r[i]);
        }
        const char* kind = type == UC_MEM_READ_UNMAPPED ? "read"
                         : type == UC_MEM_WRITE_UNMAPPED ? "write"
                         : type == UC_MEM_FETCH_UNMAPPED ? "fetch" : "access";
        LOGE("guest %s UNMAPPED addr=%08llx size=%d pc=%08x lr=%08x sp=%08x "
             "r0-r3=%08x %08x %08x %08x r4-r7=%08x %08x %08x %08x "
             "r8-r11=%08x %08x %08x %08x r12=%08x last_import=%s",
             kind, static_cast<unsigned long long>(address), size, pc, lr, sp,
             r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7],
             r[8], r[9], r[10], r[11], r[12], g_last_import.c_str());
    }

    uc_engine* uc() const { return uc_; }

    bool map(uint32_t addr, size_t size, const char* what) {
        uc_err err = uc_mem_map(uc_, addr, size, UC_PROT_READ | UC_PROT_WRITE | UC_PROT_EXEC);
        if (err != UC_ERR_OK) {
            LOGE("uc_mem_map(%s) %08x+%zu failed: %s", what, addr, size, uc_strerror(err));
            return false;
        }
        return true;
    }

    // Guest heap: a bump allocator over a fixed arena with a first-fit free
    // list. A pure bump allocator survives startup but dies a few thousand
    // frames into a render loop, because games churn the allocator constantly
    // and a host pointer can never be handed back to 32-bit code.
    uint32_t alloc(size_t bytes, size_t alignment = 8) {
        if (bytes == 0) bytes = 1;
        const uint32_t want = align_up(static_cast<uint32_t>(bytes), 8);

        for (auto it = free_list_.begin(); it != free_list_.end(); ++it) {
            if (it->second < want) continue;
            const uint32_t at = it->first;
            const uint32_t rest = it->second - want;
            free_list_.erase(it);
            if (rest >= 8) free_list_[at + want] = rest;
            return at;
        }

        const uint32_t at = align_up(heap_cursor_, static_cast<uint32_t>(alignment));
        if (at + want > kHeapBase + kHeapSize) {
            LOGE("guest heap exhausted (want %zu bytes, %zu bytes live)", bytes,
                 static_cast<size_t>(heap_cursor_ - kHeapBase));
            return 0;
        }
        heap_cursor_ = at + want;
        return at;
    }

    // Return a guest block to the free list and coalesce with its neighbours.
    void release(uint32_t p, size_t bytes) {
        if (p < kHeapBase || p >= kHeapBase + kHeapSize || bytes == 0) return;
        const uint32_t want = align_up(static_cast<uint32_t>(bytes), 8);
        auto ins = free_list_.emplace(p, want).first;

        auto next = std::next(ins);
        if (next != free_list_.end() && p + ins->second == next->first) {
            ins->second += next->second;
            free_list_.erase(next);
        }
        if (ins != free_list_.begin()) {
            auto prev = std::prev(ins);
            if (prev->first + prev->second == p) {
                prev->second += ins->second;
                free_list_.erase(ins);
            }
        }
    }

    size_t heap_live() const { return static_cast<size_t>(heap_cursor_ - kHeapBase); }

    bool write(uint32_t addr, const void* data, size_t len) {
        uc_err err = uc_mem_write(uc_, addr, data, len);
        if (err != UC_ERR_OK) {
            LOGE("uc_mem_write(%08x, %zu) failed: %s", addr, len, uc_strerror(err));
            return false;
        }
        return true;
    }

    bool read(uint32_t addr, void* out, size_t len) {
        uc_err err = uc_mem_read(uc_, addr, out, len);
        return err == UC_ERR_OK;
    }

    bool write_u32(uint32_t addr, uint32_t v) { return write(addr, &v, sizeof v); }

    uint32_t read_u32(uint32_t addr, bool* ok = nullptr) {
        uint32_t v = 0;
        bool r = read(addr, &v, sizeof v);
        if (ok) *ok = r;
        return v;
    }

    std::string read_cstr(uint32_t addr, size_t max = 1024) {
        std::string s;
        for (size_t i = 0; i < max; ++i) {
            uint8_t c = 0;
            if (!read(addr + static_cast<uint32_t>(i), &c, 1) || c == 0) break;
            s.push_back(static_cast<char>(c));
        }
        return s;
    }

    bool reg_write(int reg, uint32_t v) {
        return uc_reg_write(uc_, reg, &v) == UC_ERR_OK;
    }

    bool reg_read(int reg, uint32_t& v) {
        v = 0;
        return uc_reg_read(uc_, reg, &v) == UC_ERR_OK;
    }

    // A guest call that overran its time budget.
    //
    // The guest's frame is still on its own stack and the emulator registers are
    // exactly where the last chunk stopped, so the call can be continued later
    // instead of being lost. `saved` is the pre-call context snapshot that a
    // normal return would have restored.
    struct Pause {
        bool        active = false;
        uc_context* saved = nullptr;
        uint32_t    entry = 0;
        std::string what;
    };

    // Enter the guest, stopping when PC reaches the return sentinel.
    //
    // AAPCS: the first four words travel in R0-R3 and everything after that is
    // pushed onto the stack at [SP], [SP+4], ... Native methods with more than
    // four parameters (and their JNIEnv*/jobject prefix) need the stack path.
    //
    // `budget_ms` bounds how long the calling ART thread stays inside the guest.
    // A legacy renderer can park its UI thread on the first frame -- Tux Rider's
    // GLSurfaceView waits inside surfaceChanged() for the GL thread to finish
    // `resize` -- so an engine initialisation that takes seconds is reported by
    // the system as an input-dispatch ANR. When the budget expires the call is
    // suspended and resume_pending() finishes it from the next native entry,
    // which is the GL thread, i.e. exactly where the work would have run anyway.
    bool call(uint32_t entry, const std::vector<uint32_t>& args, uint32_t& ret,
              const char* what = "", int budget_ms = 0, bool* paused_out = nullptr) {
        if (pending_.active) {
            LOGW("call(%s): a suspended call is still pending", what);
            return false;
        }
        uc_context* saved = nullptr;
        if (uc_context_alloc(uc_, &saved) != UC_ERR_OK) return false;
        uc_context_save(uc_, saved);

        // One 4 KB frame per nesting level, so a guest->Java->guest round trip
        // cannot clobber the frame of the call it is nested inside.
        const uint32_t sp = align_down(kStackTop - 0x1000u * (depth_ + 1), 8);

        reg_write(UC_ARM_REG_SP, sp);
        reg_write(UC_ARM_REG_LR, kReturnSentinel);
        // Enter in the instruction set the symbol address encodes. uc_reg_write
        // of PC does not always carry the low Thumb bit into CPSR.T.
        {
            uint32_t cpsr = 0;
            if (reg_read(UC_ARM_REG_CPSR, cpsr)) {
                cpsr = (entry & 1u) ? (cpsr | 0x20u) : (cpsr & ~0x20u);
                uc_reg_write(uc_, UC_ARM_REG_CPSR, &cpsr);
            }
        }
        for (size_t i = 0; i < args.size() && i < 4; ++i) {
            reg_write(UC_ARM_REG_R0 + static_cast<int>(i), args[i]);
        }
        for (size_t i = 4; i < args.size(); ++i) {
            write_u32(sp + static_cast<uint32_t>((i - 4) * 4), args[i]);
        }

        if (call_trace_ < 60) {
            ++call_trace_;
            LOGI("emu call #%d: %s @%08x (%zu args, depth %u)", call_trace_, what, entry,
                 args.size(), depth_);
        }

        ++depth_;
        uint32_t pc = entry;
        uc_err err = UC_ERR_OK;
        bool paused = false;
        run_to_return(pc, what, budget_ms, paused, err);
        if (paused) {
            pending_.active = true;
            pending_.saved = saved;
            pending_.entry = entry;
            pending_.what = what;
            if (paused_out) *paused_out = true;
            LOGI("call(%s) @%08x suspended at pc=%08x after %dms: "
                 "ART thread released, continuing on the next native entry",
                 what, entry, pc, budget_ms);
            return false;   // depth_ and `saved` stay with the suspended call
        }
        --depth_;

        bool ok = false;
        if (err == UC_ERR_OK) {
            ok = reg_read(UC_ARM_REG_R0, ret);
        } else {
            uint32_t pc = 0, lr = 0, sp = 0, cpsr = 0;
            uint32_t r0 = 0, r1 = 0, r2 = 0, r3 = 0;
            reg_read(UC_ARM_REG_PC, pc);
            reg_read(UC_ARM_REG_LR, lr);
            reg_read(UC_ARM_REG_SP, sp);
            reg_read(UC_ARM_REG_CPSR, cpsr);
            reg_read(UC_ARM_REG_R0, r0);
            reg_read(UC_ARM_REG_R1, r1);
            reg_read(UC_ARM_REG_R2, r2);
            reg_read(UC_ARM_REG_R3, r3);
            LOGE("call(%s) @%08x aborted: %s (pc=%08x lr=%08x sp=%08x cpsr=%08x %s "
                 "r0=%08x r1=%08x r2=%08x r3=%08x last_import=%s)",
                 what, entry, uc_strerror(err), pc, lr, sp, cpsr,
                 (cpsr & 0x20u) ? "THUMB" : "ARM",
                 r0, r1, r2, r3, g_last_import.c_str());
        }

        uc_context_restore(uc_, saved);
        uc_context_free(saved);
        return ok;
    }

    /**
     * Continues a suspended call from the PC it stopped at.
     *
     * @return true when the call really returned (`ret` holds R0), false when it
     *         is still suspended (`paused`) or aborted.
     */
    bool resume_pending(uint32_t& ret, int budget_ms, bool& paused) {
        if (!pending_.active) return true;
        uc_context* saved = pending_.saved;
        const std::string what = pending_.what;
        const uint32_t entry = pending_.entry;
        pending_ = Pause{};

        // uc_emu_start() takes the instruction set from bit 0 of the start
        // address, so the suspended PC has to be re-encoded from CPSR.T.
        uint32_t raw_pc = 0, cpsr = 0;
        reg_read(UC_ARM_REG_PC, raw_pc);
        const bool thumb = reg_read(UC_ARM_REG_CPSR, cpsr) && (cpsr & 0x20u) != 0;
        uint32_t pc = raw_pc | (thumb ? 1u : 0u);

        uc_err err = UC_ERR_OK;
        run_to_return(pc, what.c_str(), budget_ms, paused, err);
        if (paused) {
            pending_.active = true;
            pending_.saved = saved;
            pending_.entry = entry;
            pending_.what = what;
            return false;
        }
        --depth_;

        bool ok = false;
        if (err == UC_ERR_OK) {
            ok = reg_read(UC_ARM_REG_R0, ret);
        } else {
            LOGE("resume(%s) @%08x aborted: %s (pc=%08x)", what.c_str(), entry,
                 uc_strerror(err), pc);
        }
        uc_context_restore(uc_, saved);
        uc_context_free(saved);
        LOGI("call(%s): resumed and completed (ret=%d)", what.c_str(), static_cast<int>(ret));
        return ok;
    }

    bool has_pending() const { return pending_.active; }

private:
    // Chunked execution from `pc` up to the return sentinel.
    //
    // Chunks keep a guest that spins (typically a wait loop for a worker thread
    // this single-threaded emulator never starts) visible instead of hanging the
    // host silently. `budget_ms` additionally caps how long the caller is held
    // inside the guest: when it expires the loop stops at a chunk boundary with
    // the guest frame intact and `paused` is set.
    void run_to_return(uint32_t& pc, const char* what, int budget_ms, bool& paused,
                       uc_err& err) {
        constexpr uint32_t kChunk = 4u * 1000u * 1000u;
        const auto started = std::chrono::steady_clock::now();
        uint64_t budget = kMaxInstructions;
        int state_retries = 0;
        for (;;) {
            err = uc_emu_start(uc_, pc, kReturnSentinel, 0, kChunk);
            if (err == UC_ERR_INSN_INVALID && state_retries < 16) {
                // An ARM/Thumb interworking transition can be lost across a
                // block boundary (the payload mixes both instruction sets).
                // The faulting PC is still in the guest image, so retry it in
                // the opposite state once before declaring the call dead.
                uint32_t bad_pc = 0, cpsr = 0;
                reg_read(UC_ARM_REG_PC, bad_pc);
                if (reg_read(UC_ARM_REG_CPSR, cpsr)) {
                    const bool was_thumb = (cpsr & 0x20u) != 0;
                    cpsr ^= 0x20u;
                    uc_reg_write(uc_, UC_ARM_REG_CPSR, &cpsr);
                    const uint32_t restart = bad_pc;
                    uint32_t flr = 0;
                    reg_read(UC_ARM_REG_LR, flr);
                    LOGW("call(%s): invalid %s instruction at pc=%08x lr=%08x; "
                         "retrying as %s", what, was_thumb ? "Thumb" : "ARM",
                         bad_pc, flr, was_thumb ? "ARM" : "Thumb");
                    ++state_retries;
                    // uc_emu_start() derives the instruction set from the start
                    // address, overriding CPSR.T; encode the state in bit 0.
                    pc = was_thumb ? (restart & ~1u) : (restart | 1u);
                    continue;
                }
            }
            if (err != UC_ERR_OK) return;

            uint32_t cur = 0;
            reg_read(UC_ARM_REG_PC, cur);
            if (cur == kReturnSentinel) return;   // normal return

            budget -= std::min<uint64_t>(budget, kChunk);
            const auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(
                std::chrono::steady_clock::now() - started).count();

            if (budget == 0 || elapsed > 90) {
                uint32_t lr = 0;
                reg_read(UC_ARM_REG_LR, lr);
                LOGE("call(%s) never returned: spinning at pc=%08x lr=%08x "
                     "after %llds", what, cur, lr, static_cast<long long>(elapsed));
                emu_ok_ = false;
                return;
            }
            if (budget_ms > 0) {
                const auto held = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - started).count();
                if (held >= budget_ms) {
                    paused = true;
                    uint32_t live_cpsr = 0;
                    const bool live_thumb = reg_read(UC_ARM_REG_CPSR, live_cpsr) &&
                                            (live_cpsr & 0x20u) != 0;
                    pc = cur | (live_thumb ? 1u : 0u);
                    return;
                }
            }
            if (elapsed / 10 > last_report_) {
                last_report_ = elapsed / 10;
                LOGW("call(%s): still in guest at pc=%08x (%llds)", what, cur,
                     static_cast<long long>(elapsed));
            }
            // uc_emu_start() sets CPSR.T from bit 0 of the start address
            // (arm_cpu_set_pc), so resuming a chunk at a raw even PC would
            // silently drop a Thumb guest into ARM mode and the next block
            // would decode as an invalid ARM instruction. Carry the state in
            // bit 0 instead.
            uint32_t cur_cpsr = 0;
            const bool cur_thumb = reg_read(UC_ARM_REG_CPSR, cur_cpsr) &&
                                   (cur_cpsr & 0x20u) != 0;
            pc = cur | (cur_thumb ? 1u : 0u);
        }
    }

    uc_engine* uc_ = nullptr;

public:
    // A session owns its engine: ending it must actually release the 64 MB
    // guest address space, otherwise launching a second game leaks one heap.
    ~Emulator() {
        if (uc_) {
            uc_close(uc_);
            uc_ = nullptr;
        }
    }

private:
    uc_hook    mem_hook_ = 0;
    uint32_t   heap_cursor_ = kHeapBase;
    uint32_t   depth_ = 0;                   // nested call depth
    bool       emu_ok_ = true;               // last call made progress
    int        call_trace_ = 0;              // bounded entry logging for uc calls
    long long  last_report_ = 0;             // seconds bucket for spin reports
    std::map<uint32_t, size_t> free_list_;   // guest address -> size
    Pause      pending_;                     // call suspended by the time budget
};

// ===========================================================================
// 3. Static linker: maps ELF32 PT_LOAD segments and resolves dynamic symbols
// ===========================================================================
struct LoadedLibrary {
    std::string path;
    std::string soname;
    std::vector<std::string> needed;
    uint32_t    bias = 0;
    uint32_t    map_addr = 0;
    uint32_t    map_size = 0;
    std::unordered_map<std::string, uint32_t> exports;
    std::vector<uint32_t> init_array;
    uint32_t    init_fn = 0;

    // Dynamic-table metadata, kept so relocations can run in a second pass
    // once every payload is mapped (libapp32.so imports symbols that
    // libNimble32.so / libfmodex32.so export, and those are not mapped yet when
    // libapp32.so is first parsed).
    uint32_t symtab = 0, strtab = 0, strsz = 0;
    uint32_t rel = 0, relsz = 0, jmprel = 0, pltrelsz = 0;

    // DT_INIT_ARRAY is filled in with link-time relative addresses and only
    // becomes usable once R_ARM_RELATIVE has been applied, so it is read back
    // after linking, not at map time.
    uint32_t init_array_v = 0;
    uint32_t init_array_sz = 0;

    // Relocations are applied exactly once. Runtime dlopen() adds libraries to
    // an already-linked set, and re-running R_ARM_RELATIVE would add the bias
    // a second time and corrupt every pointer in the earlier payloads.
    bool linked = false;
};

// A `Java_<class>_<method>` export found in a guest library: the host ART must
// be able to bind this name, so it becomes a registered native method.
struct JavaExport {
    std::string class_path;   // com/ea/EAIO/EAIO
    std::string method;       // StartupNativeImpl
    uint32_t    fn = 0;       // guest address of the function
};

class StaticLinker {
public:
    explicit StaticLinker(Emulator& emu) : emu_(emu) {}

    // Called for symbols that no loaded library defines.
    void set_import_resolver(std::function<uint32_t(const std::string&)> fn) {
        import_resolver_ = std::move(fn);
    }

    LoadedLibrary* load(const std::string& path, uint32_t image_base) {
        std::vector<uint8_t> buf;
        if (!read_file(path, buf)) return nullptr;

        Elf32_Ehdr eh{};
        memcpy(&eh, buf.data(), sizeof eh);
        if (memcmp(eh.e_ident, ELFMAG, SELFMAG) != 0 ||
            eh.e_ident[EI_CLASS] != ELFCLASS32 ||
            eh.e_ident[EI_DATA] != ELFDATA2LSB ||
            eh.e_machine != EM_ARM) {
            LOGE("%s: not an ARM32 little-endian ELF", path.c_str());
            return nullptr;
        }

        std::vector<Elf32_Phdr> phdrs(eh.e_phnum);
        memcpy(phdrs.data(), buf.data() + eh.e_phoff, eh.e_phnum * sizeof(Elf32_Phdr));

        uint32_t min_v = UINT32_MAX, max_v = 0;
        const Elf32_Phdr* dyn_ph = nullptr;
        for (const auto& ph : phdrs) {
            if (ph.p_type == PT_LOAD) {
                min_v = std::min(min_v, align_down(ph.p_vaddr, kPageSize));
                max_v = std::max(max_v, align_up(ph.p_vaddr + ph.p_memsz, kPageSize));
            } else if (ph.p_type == PT_DYNAMIC) {
                dyn_ph = &ph;
            }
        }
        if (min_v >= max_v || image_base < min_v) {
            LOGE("%s: unusable PT_LOAD range", path.c_str());
            return nullptr;
        }

        auto lib = new LoadedLibrary();
        lib->path = path;
        lib->bias = image_base - min_v;
        lib->map_addr = image_base;
        lib->map_size = max_v - min_v;

        if (!emu_.map(lib->map_addr, lib->map_size, "image")) {
            delete lib;
            return nullptr;
        }

        for (const auto& ph : phdrs) {
            if (ph.p_type != PT_LOAD) continue;
            if (ph.p_filesz == 0) continue;   // pure .bss: already zero from uc_mem_map
            if (ph.p_offset + ph.p_filesz > buf.size()) {
                LOGE("%s: PT_LOAD outside file bounds", path.c_str());
                delete lib;
                return nullptr;
            }
            if (!emu_.write(lib->bias + ph.p_vaddr, buf.data() + ph.p_offset, ph.p_filesz)) {
                delete lib;
                return nullptr;
            }
        }

        LOGI("%s loaded: bias %08x, %u bytes, %zu segments", path.c_str(), lib->bias,
             lib->map_size, phdrs.size());

        if (dyn_ph) {
            if (!parse_dynamic(*lib, buf, *dyn_ph)) {
                delete lib;
                return nullptr;
            }
        }

        libs_.push_back(lib);
        return lib;
    }

    uint32_t resolve_global(const std::string& name) {
        for (auto* lib : libs_) {
            auto it = lib->exports.find(name);
            if (it != lib->exports.end()) return it->second;
        }
        return 0;
    }

    // Second pass: every payload is mapped, so symbol resolution is no longer
    // order-dependent. apply_relocs() builds import stubs for anything the
    // guest libraries plus the host routing table cannot satisfy.
    void link_all() {
        for (auto* lib : libs_) {
            if (lib->linked) continue;
            size_t applied = 0, failed = 0;
            apply_relocs(*lib, lib->symtab, lib->strtab, lib->strsz,
                         lib->rel, lib->relsz, applied, failed);
            apply_relocs(*lib, lib->symtab, lib->strtab, lib->strsz,
                         lib->jmprel, lib->pltrelsz, applied, failed);
            LOGI("%s: %zu relocations applied, %zu unresolved slots", lib->path.c_str(),
                 applied, failed);

            // Now that the array entries have been relocated, they hold real
            // guest addresses.
            if (lib->init_array_sz) {
                const uint32_t entries = lib->init_array_sz / sizeof(uint32_t);
                for (uint32_t i = 0; i < entries; ++i) {
                    const uint32_t fn = emu_.read_u32(lib->bias + lib->init_array_v + i * 4);
                    if (fn == 0 || fn == 0xFFFFFFFFu) continue;
                    lib->init_array.push_back(fn);
                }
            }
            lib->linked = true;
        }
    }

    const std::vector<LoadedLibrary*>& libs() const { return libs_; }

    // Every `Java_*` export across the mapped payloads. This is what makes the
    // bridge APK-agnostic: no symbol name is hardcoded anywhere.
    std::vector<JavaExport> java_exports() const {
        std::vector<JavaExport> out;
        for (const auto* lib : libs_) {
            for (const auto& kv : lib->exports) {
                JavaExport e;
                if (parse_java_symbol(kv.first, e.class_path, e.method)) {
                    e.fn = kv.second;
                    out.push_back(e);
                }
            }
        }
        return out;
    }

    // Java_com_example_Foo_bar -> ("com/example/Foo", "bar")
    // Underscore escapes per the JNI spec: _1 '_', _2 ';', _3 '[', _0XXXX.
    static bool parse_java_symbol(const std::string& sym, std::string& cls,
                                  std::string& method) {
        const std::string prefix = "Java_";
        if (sym.compare(0, prefix.size(), prefix) != 0) return false;

        // Drop the optional `__<mangled-args>` suffix used for overloads.
        std::string body = sym.substr(prefix.size());
        const size_t dbl = body.find("__");
        if (dbl != std::string::npos) body = body.substr(0, dbl);
        if (body.empty()) return false;

        std::vector<std::string> parts;
        std::string cur;
        for (size_t i = 0; i < body.size(); ++i) {
            const char c = body[i];
            if (c != '_') { cur.push_back(c); continue; }
            if (i + 1 >= body.size()) return false;
            const char n = body[i + 1];
            if (n == '1') { cur.push_back('_'); i += 1; }
            else if (n == '2') { cur.push_back(';'); i += 1; }
            else if (n == '3') { cur.push_back('['); i += 1; }
            else if (n == '0' && i + 5 < body.size() + 1) {
                cur.push_back(static_cast<char>(std::strtol(body.substr(i + 1, 4).c_str(),
                                                            nullptr, 16)));
                i += 4;
            } else {
                parts.push_back(cur);
                cur.clear();
            }
        }
        parts.push_back(cur);
        if (parts.size() < 2) return false;

        method = parts.back();
        cls.clear();
        for (size_t i = 0; i + 1 < parts.size(); ++i) {
            if (i) cls.push_back('/');
            cls += parts[i];
        }
        return !cls.empty() && !method.empty();
    }

private:
    static bool read_file(const std::string& path, std::vector<uint8_t>& out) {
        int fd = open(path.c_str(), O_RDONLY);
        if (fd < 0) {
            LOGE("open(%s): %s", path.c_str(), strerror(errno));
            return false;
        }
        struct stat st{};
        if (fstat(fd, &st) != 0 || st.st_size <= 0) {
            close(fd);
            return false;
        }
        out.resize(static_cast<size_t>(st.st_size));
        size_t got = 0;
        while (got < out.size()) {
            ssize_t n = read(fd, out.data() + got, out.size() - got);
            if (n <= 0) { close(fd); return false; }
            got += static_cast<size_t>(n);
        }
        close(fd);
        return true;
    }

    uint32_t sym_count(const LoadedLibrary& lib, uint32_t hash_v, uint32_t symtab_v,
                       uint32_t strtab_v) const {
        if (hash_v) {
            // DT_HASH: nbucket, nchain -> nchain is the symbol count.
            bool ok = false;
            uint32_t nchain = emu_.read_u32(lib.bias + hash_v + 4, &ok);
            if (ok) return nchain;
        }
        if (strtab_v > symtab_v) return (strtab_v - symtab_v) / sizeof(Elf32_Sym);
        return 0;
    }

    std::string sym_name(const LoadedLibrary& lib, uint32_t strtab, uint32_t strsz,
                         uint32_t name_off) const {
        if (name_off >= strsz) return {};
        return emu_.read_cstr(lib.bias + strtab + name_off, strsz - name_off);
    }

    uint32_t lookup_in_libs(const std::string& name) { return resolve_global(name); }

    bool parse_dynamic(LoadedLibrary& lib, const std::vector<uint8_t>& buf,
                       const Elf32_Phdr& dyn_ph) {
        const size_t n = dyn_ph.p_filesz / sizeof(Elf32_Dyn);
        const Elf32_Dyn* dyn = reinterpret_cast<const Elf32_Dyn*>(buf.data() + dyn_ph.p_offset);

        uint32_t symtab = 0, strtab = 0, strsz = 0, hash_v = 0;
        uint32_t rel = 0, relsz = 0, jmprel = 0, pltrelsz = 0;
        uint32_t init_array = 0, init_arraysz = 0;
        std::vector<uint32_t> needed_offsets;

        for (size_t i = 0; i < n; ++i) {
            switch (dyn[i].d_tag) {
                case DT_NEEDED:     needed_offsets.push_back(dyn[i].d_un.d_val); break;
                case DT_SYMTAB:     symtab = dyn[i].d_un.d_ptr; break;
                case DT_STRTAB:     strtab = dyn[i].d_un.d_ptr; break;
                case DT_STRSZ:      strsz = dyn[i].d_un.d_val; break;
                case DT_HASH:       hash_v = dyn[i].d_un.d_ptr; break;
                case DT_REL:        rel = dyn[i].d_un.d_ptr; break;
                case DT_RELSZ:      relsz = dyn[i].d_un.d_val; break;
                case DT_JMPREL:     jmprel = dyn[i].d_un.d_ptr; break;
                case DT_PLTRELSZ:   pltrelsz = dyn[i].d_un.d_val; break;
                case DT_INIT_ARRAY: init_array = dyn[i].d_un.d_ptr; break;
                case DT_INIT_ARRAYSZ: init_arraysz = dyn[i].d_un.d_val; break;
                case DT_INIT:       lib.init_fn = lib.bias + dyn[i].d_un.d_ptr; break;
                case DT_SONAME:
                    if (strtab) lib.soname = emu_.read_cstr(lib.bias + strtab + dyn[i].d_un.d_val);
                    break;
                default: break;
            }
        }

        if (!symtab || !strtab) {
            LOGW("%s: no dynamic symbol table", lib.path.c_str());
            return true;
        }

        // ---- DT_NEEDED dependencies ----------------------------------------
        for (uint32_t off : needed_offsets) {
            std::string dep = sym_name(lib, strtab, strsz, off);
            if (!dep.empty()) {
                lib.needed.push_back(dep);
            }
        }

        // ---- exports -------------------------------------------------------
        const uint32_t count = sym_count(lib, hash_v, symtab, strtab);
        for (uint32_t i = 1; i < count; ++i) {
            Elf32_Sym sym{};
            if (!emu_.read(lib.bias + symtab + i * sizeof(Elf32_Sym), &sym, sizeof sym)) break;
            if (sym.st_name == 0 || sym.st_name >= strsz) continue;
            if (sym.st_shndx == SHN_UNDEF || sym.st_value == 0) continue;
            if (ELF32_ST_BIND(sym.st_info) != STB_GLOBAL &&
                ELF32_ST_BIND(sym.st_info) != STB_WEAK) continue;
            std::string name = sym_name(lib, strtab, strsz, sym.st_name);
            if (!name.empty()) lib.exports[name] = lib.bias + sym.st_value;
        }
        LOGI("%s: %zu dynamic symbols, soname=%s", lib.path.c_str(), lib.exports.size(),
             lib.soname.empty() ? "<none>" : lib.soname.c_str());

        // ---- relocations ---------------------------------------------------
        // Deferred: see link_all(). Only record where they live.
        lib.symtab = symtab;   lib.strtab = strtab;  lib.strsz = strsz;
        lib.rel = rel;         lib.relsz = relsz;
        lib.jmprel = jmprel;   lib.pltrelsz = pltrelsz;

        // ---- static constructors ------------------------------------------
        // Recorded, not read: the entries are still unrelocated here.
        lib.init_array_v = init_array;
        lib.init_array_sz = init_arraysz;
        return true;
    }

    void apply_relocs(LoadedLibrary& lib, uint32_t symtab, uint32_t strtab, uint32_t strsz,
                      uint32_t rel_v, uint32_t relsz, size_t& applied, size_t& failed) {
        if (!rel_v || !relsz) return;
        const uint32_t count = relsz / sizeof(Elf32_Rel);
        unsigned unknown = 0;

        for (uint32_t i = 0; i < count; ++i) {
            Elf32_Rel r{};
            if (!emu_.read(lib.bias + rel_v + i * sizeof(Elf32_Rel), &r, sizeof r)) return;

            const uint32_t slot = lib.bias + r.r_offset;
            const uint32_t type = ELF32_R_TYPE(r.r_info);
            const uint32_t symi = ELF32_R_SYM(r.r_info);
            const uint32_t addend = emu_.read_u32(slot);

            switch (type) {
                case R_ARM_RELATIVE:
                    emu_.write_u32(slot, lib.bias + addend);
                    ++applied;
                    break;
                case R_ARM_ABS32:
                case R_ARM_GLOB_DAT:
                case R_ARM_JUMP_SLOT: {
                    const uint32_t target = resolve_symbol(lib, symtab, strtab, strsz, symi);
                    if (!target) { ++failed; break; }
                    emu_.write_u32(slot, type == R_ARM_ABS32 ? target + addend : target);
                    ++applied;
                    break;
                }
                case R_ARM_NONE:
                    break;
                default:
                    if (unknown++ < 5) {
                        LOGW("%s: unsupported relocation type %u at %08x", lib.path.c_str(),
                             type, r.r_offset);
                    }
                    ++failed;
                    break;
            }
        }
    }

    uint32_t resolve_symbol(const LoadedLibrary& lib, uint32_t symtab, uint32_t strtab,
                            uint32_t strsz, uint32_t index) {
        if (index == 0) return 0;

        Elf32_Sym sym{};
        if (!emu_.read(lib.bias + symtab + index * sizeof(Elf32_Sym), &sym, sizeof sym)) {
            return 0;
        }
        if (sym.st_shndx != SHN_UNDEF && sym.st_value != 0) return lib.bias + sym.st_value;

        std::string name = sym_name(lib, strtab, strsz, sym.st_name);
        if (name.empty()) return 0;

        uint32_t addr = lookup_in_libs(name);
        if (addr) return addr;

        // Cross-library or bionic import: route through the bridge.
        if (import_resolver_) return import_resolver_(name);
        return 0;
    }

    Emulator&      emu_;
    std::vector<LoadedLibrary*> libs_;
    std::function<uint32_t(const std::string&)> import_resolver_;
};

// ===========================================================================
// 4. JNI bridge
// ===========================================================================
enum StubKind : int { kStubJniEnv = 1, kStubJniVm, kStubImport, kStubCallback };

struct StubSlot {
    StubKind    kind;
    int         index;
    std::string name;
};

struct NativeEntry {
    uint32_t    id = 0;
    uint32_t    guest_fn = 0;
    std::string cls;
    std::string name;
    std::string sig;
    char        ret_type = 'V';
    std::vector<char> arg_types;
    void*       code = nullptr;
};

#define JNIENV_IDX(field) (static_cast<int>(offsetof(JNINativeInterface, field) / sizeof(void*)))
#define JNIVM_IDX(field)  (static_cast<int>(offsetof(JNIInvokeInterface, field) / sizeof(void*)))

constexpr int kIdxGetVersion       = JNIENV_IDX(GetVersion);
constexpr int kIdxFindClass        = JNIENV_IDX(FindClass);
constexpr int kIdxGetObjectClass   = JNIENV_IDX(GetObjectClass);
constexpr int kIdxGetSuperclass    = JNIENV_IDX(GetSuperclass);
constexpr int kIdxGetMethodID      = JNIENV_IDX(GetMethodID);
constexpr int kIdxGetStaticMethodID= JNIENV_IDX(GetStaticMethodID);
constexpr int kIdxGetFieldID       = JNIENV_IDX(GetFieldID);
constexpr int kIdxNewStringUTF     = JNIENV_IDX(NewStringUTF);
constexpr int kIdxGetStringUTFLength = JNIENV_IDX(GetStringUTFLength);
constexpr int kIdxGetStringUTFChars  = JNIENV_IDX(GetStringUTFChars);
constexpr int kIdxReleaseStringUTFChars = JNIENV_IDX(ReleaseStringUTFChars);
constexpr int kIdxRegisterNatives  = JNIENV_IDX(RegisterNatives);
constexpr int kIdxExceptionOccurred = JNIENV_IDX(ExceptionOccurred);
constexpr int kIdxExceptionDescribe = JNIENV_IDX(ExceptionDescribe);
constexpr int kIdxExceptionClear   = JNIENV_IDX(ExceptionClear);
constexpr int kIdxExceptionCheck   = JNIENV_IDX(ExceptionCheck);
constexpr int kIdxNewGlobalRef     = JNIENV_IDX(NewGlobalRef);
constexpr int kIdxDeleteGlobalRef  = JNIENV_IDX(DeleteGlobalRef);
constexpr int kIdxDeleteLocalRef   = JNIENV_IDX(DeleteLocalRef);
constexpr int kIdxNewLocalRef      = JNIENV_IDX(NewLocalRef);
constexpr int kIdxIsSameObject     = JNIENV_IDX(IsSameObject);
constexpr int kIdxGetJavaVM        = JNIENV_IDX(GetJavaVM);
constexpr int kIdxIsInstanceOf     = JNIENV_IDX(IsInstanceOf);
constexpr int kIdxIsAssignableFrom = JNIENV_IDX(IsAssignableFrom);
constexpr int kIdxThrow            = JNIENV_IDX(Throw);
constexpr int kIdxThrowNew         = JNIENV_IDX(ThrowNew);
constexpr int kIdxFatalError       = JNIENV_IDX(FatalError);
constexpr int kIdxPushLocalFrame   = JNIENV_IDX(PushLocalFrame);
constexpr int kIdxPopLocalFrame    = JNIENV_IDX(PopLocalFrame);
constexpr int kIdxEnsureLocalCapacity = JNIENV_IDX(EnsureLocalCapacity);
constexpr int kIdxAllocObject      = JNIENV_IDX(AllocObject);
constexpr int kIdxNewObject        = JNIENV_IDX(NewObject);
constexpr int kIdxNewObjectV       = JNIENV_IDX(NewObjectV);
constexpr int kIdxNewObjectA       = JNIENV_IDX(NewObjectA);
constexpr int kIdxToReflectedMethod = JNIENV_IDX(ToReflectedMethod);
constexpr int kIdxFromReflectedMethod = JNIENV_IDX(FromReflectedMethod);

// The Call<Type>Method{,V,A} blocks are 30 consecutive slots each. Order is
// type-major and, within a type, plain / V (va_list) / A (jvalue array).
constexpr int kIdxCallObjectMethod       = JNIENV_IDX(CallObjectMethod);
constexpr int kIdxCallStaticObjectMethod = JNIENV_IDX(CallStaticObjectMethod);
constexpr char kCallTypeOrder[10] = {'L', 'Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D', 'V'};

// Array entry points, also laid out in fixed runs we can index arithmetically.
constexpr int kIdxGetArrayLength        = JNIENV_IDX(GetArrayLength);
constexpr int kIdxNewObjectArray        = JNIENV_IDX(NewObjectArray);
constexpr int kIdxGetObjectArrayElement = JNIENV_IDX(GetObjectArrayElement);
constexpr int kIdxSetObjectArrayElement = JNIENV_IDX(SetObjectArrayElement);
constexpr int kIdxNewBooleanArray       = JNIENV_IDX(NewBooleanArray);
constexpr int kIdxGetBooleanArrayRegion  = JNIENV_IDX(GetBooleanArrayRegion);
constexpr int kIdxGetBooleanArrayElements = JNIENV_IDX(GetBooleanArrayElements);

constexpr int kIdxVmDestroy        = JNIVM_IDX(DestroyJavaVM);
constexpr int kIdxVmAttach         = JNIVM_IDX(AttachCurrentThread);
constexpr int kIdxVmDetach         = JNIVM_IDX(DetachCurrentThread);
constexpr int kIdxVmGetEnv         = JNIVM_IDX(GetEnv);

// Resolve a class through the guest application's DexClassLoader. ART's
// FindClass uses the loader of the *host* application, where the guest's
// classes do not exist, so a Java_<class> export could never bind. Going
// through ClassLoader.loadClass by name reaches the injected guest dex.
jclass find_class_through(JNIEnv* env, jobject loader, const std::string& slash_name) {
    if (loader && !slash_name.empty()) {
        jclass loader_cls = env->FindClass("java/lang/ClassLoader");
        if (loader_cls) {
            jmethodID load = env->GetMethodID(loader_cls, "loadClass",
                                              "(Ljava/lang/String;)Ljava/lang/Class;");
            if (load) {
                std::string dotted = slash_name;
                std::replace(dotted.begin(), dotted.end(), '/', '.');
                jstring name = env->NewStringUTF(dotted.c_str());
                if (name) {
                    auto cls = static_cast<jclass>(env->CallObjectMethod(loader, load, name));
                    env->DeleteLocalRef(name);
                    env->DeleteLocalRef(loader_cls);
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();   // not in the guest dex (yet)
                        return nullptr;
                    }
                    return cls;
                }
            }
            env->DeleteLocalRef(loader_cls);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    return env->FindClass(slash_name.c_str());
}

class JniBridge {
public:
    // std::function rather than a member pointer: the routing table is now
    // large and mostly one-line lambdas over host libc/libm entry points.
    using ImportHandler = std::function<uint32_t()>;

    explicit JniBridge(Emulator& emu) : emu_(emu), linker_(emu) {}

    bool init(JavaVM* vm, JNIEnv* env) {
        env_ = env;
        vm_ = vm;
        if (!HostTrampolines::init()) return false;

        linker_.set_import_resolver([this](const std::string& name) {
            return import_stub(name);
        });

        // Host routing table for the imports every libc-linked ARM32 binary
        // needs. Anything absent here still gets a stub and is reported.
        // The host graphics libraries must be resolved first: the EGL/GLES
        // handler table is only installed when libEGL is actually loaded.
        open_host_graphics();
        install_import_handlers();
        install_platform_handlers();

        // Data imports (address-of, not a call) get real guest memory instead
        // of a code stub: the guest dereferences these.
        stack_guard_ = emu_.alloc(4, 4);
        errno_cell_ = emu_.alloc(4, 4);
        return_gadget_ = emu_.alloc(8, 4);
        if (stack_guard_ && errno_cell_ && return_gadget_) {
            emu_.write_u32(stack_guard_, 0x00A5A5A5u);
            emu_.write_u32(errno_cell_, 0);
            // mov r0, #0 ; ldr pc, [sp], #4  - a guest `ret` that also clears r0.
            const uint32_t gadget[] = {0xE3A00000u, 0xE49DF004u};
            emu_.write(return_gadget_, gadget, sizeof gadget);
            // __stack_chk_guard is a real data symbol (the guest loads it), but
            // __errno is a *function* on ARM bionic: it returns the address of
            // the thread's errno cell. Registering it as data makes every
            // guest `__errno()` call branch into a data page.
            data_slots_["__stack_chk_guard"] = stack_guard_;
        }

        // Data symbols a legacy payload dereferences rather than calls. They
        // must be readable guest memory: a code stub here is what turns a
        // printf() into a wild jump, because the guest loads a FILE* field out
        // of the address and calls through it.
        const uint32_t stdio_files = emu_.alloc(3 * 256, 16);   // bionic __sF[3]
        const uint32_t environ_slot = emu_.alloc(4, 4);
        const uint32_t environ_array = emu_.alloc(4, 4);
        if (stdio_files && environ_slot && environ_array) {
            // environ is a char** whose first entry is the NULL terminator.
            // Pointing the symbol straight at NULL makes TclSetupEnv load
            // *environ and fault at address 0.
            emu_.write_u32(environ_array, 0);
            emu_.write_u32(environ_slot, environ_array);
            data_slots_["__sF"] = stdio_files;
            data_slots_["stdin"] = stdio_files;
            data_slots_["stdout"] = stdio_files + 256;
            data_slots_["stderr"] = stdio_files + 512;
            data_slots_["environ"] = environ_slot;
            LOGI("data imports: __sF @%08x, environ @%08x", stdio_files, environ_slot);
        }

        install_locale_data();

        // 32-bit JNIEnv shadow: one word pointing at the function table.
        env_funcs_ = emu_.alloc(256 * sizeof(uint32_t), 16);
        env_shadow_ = emu_.alloc(4, 4);
        if (!env_funcs_ || !env_shadow_) return false;

        for (int i = 0; i < 256; ++i) {
            uint32_t stub = make_stub(kStubJniEnv, i, jni_env_name(i));
            if (!emu_.write_u32(env_funcs_ + static_cast<uint32_t>(i) * 4, stub)) return false;
        }
        if (!emu_.write_u32(env_shadow_, env_funcs_)) return false;

        // 32-bit JavaVM shadow.
        vm_funcs_ = emu_.alloc(16 * sizeof(uint32_t), 16);
        vm_shadow_ = emu_.alloc(4, 4);
        if (!vm_funcs_ || !vm_shadow_) return false;
        for (int i = 0; i < 16; ++i) {
            uint32_t stub = make_stub(kStubJniVm, i, jni_vm_name(i));
            if (!emu_.write_u32(vm_funcs_ + static_cast<uint32_t>(i) * 4, stub)) return false;
        }
        if (!emu_.write_u32(vm_shadow_, vm_funcs_)) return false;

        // One code hook covers the whole trap page.
        // (block/mem-watch debug hooks removed: they cost roughly an order of
        // magnitude of emulation throughput and the mode-loss bug they were
        // chasing is fixed by preserving CPSR.T across chunk restarts.)
        uc_err err = uc_hook_add(emu_.uc(), &hook_, UC_HOOK_CODE,
                                 reinterpret_cast<void*>(&JniBridge::code_hook_thunk),
                                 this, static_cast<uint64_t>(kStubBase),
                                 static_cast<uint64_t>(kStubBase + kStubRegionSz - 1));
        if (err != UC_ERR_OK) {
            LOGE("uc_hook_add failed: %s", uc_strerror(err));
            return false;
        }

        LOGI("JNI bridge ready: env shadow %08x, vm shadow %08x, trap page %08x",
             env_shadow_, vm_shadow_, kStubBase);
        LOGI("JNIEnv slot map: FindClass=%d GetObjectClass=%d GetMethodID=%d "
             "GetFieldID=%d GetStaticMethodID=%d NewStringUTF=%d GetJavaVM=%d",
             kIdxFindClass, kIdxGetObjectClass, kIdxGetMethodID, kIdxGetFieldID,
             kIdxGetStaticMethodID, kIdxNewStringUTF, kIdxGetJavaVM);
        return true;
    }

    LoadedLibrary* map_library(const std::string& path, uint32_t image_base) {
        return linker_.load(path, image_base);
    }

    // Second pass, once every payload is mapped: relocations can now resolve
    // symbols across libraries instead of depending on load order.
    void link_all() { linker_.link_all(); }

    const std::vector<LoadedLibrary*>& libraries() const { return linker_.libs(); }

    void run_all_constructors() {
        for (const auto* lib : linker_.libs()) run_constructors(*lib);
    }

    // Look up an export in one specific library: both libNimble.so and libapp.so
    // export JNI_OnLoad, and we must run the one belonging to libapp.so.
    static uint32_t export_of(const LoadedLibrary* lib, const std::string& name) {
        if (!lib) return 0;
        auto it = lib->exports.find(name);
        return it == lib->exports.end() ? 0 : it->second;
    }

    // guest JNI_OnLoad(vm, reserved)
    bool call_onload(uint32_t fn, uint32_t& ret) {
        return emu_.call(fn, {vm_shadow_, 0}, ret, "JNI_OnLoad");
    }

    void report_imports() { log_import_report(); }

    // =====================================================================
    // Universal JNI binding
    //
    // ART resolves a native method by looking for the symbol
    // `Java_<class>_<method>` in the library handle. Those symbols exist, but
    // they live inside the ARM32 payload and ART - a 64-bit process - can never
    // see them. Exporting them by name would hardcode one APK, so instead we
    // read the guest's dynamic symbol table, and for every `Java_*` export we
    // ask the class (via reflection on the real JNIEnv) for that method's true
    // descriptor, then bind ART to a generated 64-bit trampoline that re-enters
    // the emulator at the guest function.
    // =====================================================================
    void bind_java_exports() {
        const std::vector<JavaExport> exports = linker_.java_exports();
        if (exports.empty()) {
            LOGW("no Java_* exports in the mapped payloads");
            return;
        }
        natives_.reserve(natives_.size() + exports.size());
        LOGI("binding %zu Java_* exports into the host runtime", exports.size());

        size_t bound = 0;
        for (const auto& e : exports) {
            if (bind_one(e)) ++bound;
            else pending_.push_back(e);
        }
        LOGI("Java_* binding: %zu/%zu bound, %zu deferred (class not loaded yet)",
             bound, exports.size(), pending_.size());
    }

    // Retried on later JNI traffic: a class that was not resolvable from
    // JNI_OnLoad is usually available once the app has started.
    void retry_pending_bindings() {
        if (pending_.empty()) return;
        // A guest callback that threw leaves an exception pending; resolving a
        // class while one is pending aborts the process under CheckJNI.
        if (env_->ExceptionCheck()) {
            LOGW("deferred binding: clearing pending exception");
            env_->ExceptionClear();
        }
        std::vector<JavaExport> still;
        for (const auto& e : pending_) {
            if (!bind_one(e)) still.push_back(e);
        }
        const size_t done = pending_.size() - still.size();
        pending_.swap(still);
        if (done) LOGI("deferred Java_* bindings: %zu resolved, %zu left", done, pending_.size());
    }

    // Run DT_INIT / DT_INIT_ARRAY of a freshly loaded library.
    void run_constructors(const LoadedLibrary& lib) {
        std::vector<uint32_t> fns;
        if (lib.init_fn) fns.push_back(lib.init_fn);
        for (uint32_t f : lib.init_array) fns.push_back(f);

        for (uint32_t fn : fns) {
            uint32_t ret = 0;
            if (!emu_.call(fn, {}, ret, "constructor")) {
                LOGW("%s: constructor %08x failed", lib.path.c_str(), fn);
            }
        }
        if (!fns.empty()) LOGI("%s: %zu static constructors ran", lib.path.c_str(), fns.size());
    }

    uint32_t env_shadow() const { return env_shadow_; }
    uint32_t vm_shadow() const { return vm_shadow_; }

    uint32_t entry_point(const std::string& cls, const std::string& name) const {
        auto it = entry_points_.find(cls + "." + name);
        return it == entry_points_.end() ? 0u : it->second;
    }

    // The guest dex loader supplied by GameHostActivity. Kept as a plain
    // reference: the Java side holds the loader for the whole session.
    void set_guest_loader(jobject loader) { guest_loader_ = loader; }
    void set_guest_activity(jobject activity) { guest_thiz_ = activity; }

    // Per-session filesystem context. Everything the guest believes it can see
    // outside the sandbox is folded back into the game's private directory.
    void set_game_context(const std::string& root, const std::string& package_name) {
        game_root_ = root;
        package_ = package_name;
        payload_dir_ = root + "/payload";
        obb_dir_ = root + "/obb";
        data_dir_ = root + "/data";
        // Synthetic /proc/cpuinfo: every SoC's guest sees ARMv7-A + NEON.
        virtual_cpuinfo_ = zai::cpu::virtual_cpuinfo_path(data_dir_);
        ::mkdir(game_root_.c_str(), 0700);
        ::mkdir(obb_dir_.c_str(), 0700);
        ::mkdir(data_dir_.c_str(), 0700);
        ::mkdir((game_root_ + "/cache").c_str(), 0700);
        translate_log_.clear();
    }

    void set_host_window(ANativeWindow* window) { host_window_ = window; }

    void set_runtime_root(const std::string& root) { runtime_root_ = root; }

    // The guest's virtual working directory. The host process has one cwd for
    // every ART thread, so chdir() is emulated per session instead.
    std::string guest_cwd_;

    // Directory that holds the bundled 32-bit compatibility libraries for the
    // architecture this host is emulating.
    std::string runtime_sysroot() const {
        if (runtime_root_.empty()) return {};
#if defined(__x86_64__)
        std::string cand = runtime_root_ + "/sysroot_x86";
        if (::access(cand.c_str(), R_OK) == 0) return cand;
        return runtime_root_ + "/sysroot_x86_64";
#else
        std::string cand = runtime_root_ + "/sysroot_arm32";
        if (::access(cand.c_str(), R_OK) == 0) return cand;
        return runtime_root_ + "/sysroot_arm64";
#endif
    }

    // A system library the guest asks for by name (or by absolute /system path)
    // is answered from the bundled sysroot when it is there. This is what keeps
    // imports self-contained and offline: no host system image is required to
    // satisfy libGLESv1_CM, libOpenSLES, libz, libc++_shared, ...
    std::string sysroot_library(const std::string& name) const {
        if (runtime_root_.empty() || name.empty()) return {};

        std::string base = name;
        const size_t slash = base.find_last_of('/');
        if (slash != std::string::npos) base = base.substr(slash + 1);
        if (base.empty()) return {};

        const std::string roots[] = {
            runtime_sysroot(),
            runtime_root_ + "/sysroot_arm32",
            runtime_root_ + "/sysroot_x86",
            runtime_root_ + "/sysroot_arm64",
            runtime_root_ + "/sysroot_x86_64"
        };

        const char* dirs[] = {"/", "/lib/", "/system/lib/", "/vendor/lib/"};
        for (const auto& r : roots) {
            if (r.empty()) continue;
            for (const char* d : dirs) {
                const std::string candidate = r + d + base;
                if (::access(candidate.c_str(), R_OK) == 0) return candidate;
            }
        }
        return {};
    }

    // Guest images 0..n-1 are the payloads mapped at session start; anything
    // dlopen()ed later takes the next slot.
    void reserve_image_slots(size_t used) { next_runtime_slot_ = static_cast<uint32_t>(used); }

    // ---- 64-bit -> 32-bit reference map -----------------------------------
    uint32_t to_guest_ref(void* p) {
        if (!p) return 0;
        auto it = ref_reverse_.find(p);
        if (it != ref_reverse_.end()) return it->second;
        const uint32_t h = next_ref_++;
        refs_[h] = p;
        ref_reverse_[p] = h;
        return h;
    }

    void* from_guest_ref(uint32_t h) {
        auto it = refs_.find(h);
        return it == refs_.end() ? nullptr : it->second;
    }

    // A string returned by a host library is copied into the guest arena and
    // interned. The guest owns a normal 32-bit pointer; the same GL/driver
    // string is not re-allocated on every frame.
    uint32_t guest_str(const std::string& s) {
        auto it = interned_strings_.find(s);
        if (it != interned_strings_.end()) return it->second;
        const uint32_t at = emu_.alloc(s.size() + 1, 4);
        if (!at) {
            LOGE("guest string table exhausted (%zu bytes)", s.size() + 1);
            return 0;
        }
        emu_.write(at, s.c_str(), s.size() + 1);
        interned_strings_.emplace(s, at);
        return at;
    }

    std::string guest_string(uint32_t addr) { return emu_.read_cstr(addr); }

    // Guest pointers are untrusted. NULL or an unmapped address must produce a
    // diagnostic and a safe empty string, never a UC_ERR_READ_UNMAPPED abort in
    // the middle of a strcmp()/strstr() handler.
    std::string safe_guest_string(uint32_t addr, const char* who) {
        if (!addr) {
            warn_guest_pointer(who, addr);
            return {};
        }
        uint8_t first = 0;
        if (!emu_.read(addr, &first, 1)) {
            warn_guest_pointer(who, addr);
            return {};
        }
        return emu_.read_cstr(addr, 4096);
    }

    void warn_guest_pointer(const char* who, uint32_t addr) {
        if (guest_pointer_warnings_++ >= 32) return;
        uint32_t pc = 0;
        emu_.reg_read(UC_ARM_REG_PC, pc);
        LOGW("%s: invalid guest pointer %08x (pc=%08x, last_import=%s)",
             who, addr, pc, g_last_import.c_str());
    }    // A native method is called from whichever ART thread the guest engine
    // uses — SDL's GLThread, an audio feeder, the main thread. JNIEnv is
    // per-thread, so the JNIEnv captured at session start must never be reused
    // there: CheckJNI aborts with "using JNIEnv* from thread ...". This scope
    // swaps in the caller's own environment for the duration of the downcall.
    struct EnvScope {
        JNIEnv*& slot;
        JNIEnv*  saved;
        JNIEnv*  caller = nullptr;
        explicit EnvScope(JNIEnv*& s, JavaVM* vm) : slot(s), saved(s) {
            if (!vm) return;
            if (vm->GetEnv(reinterpret_cast<void**>(&caller), JNI_VERSION_1_6) != JNI_OK) {
                // Not attached yet: attach for the life of the thread. It is
                // never detached explicitly; ART does that on thread exit.
                if (vm->AttachCurrentThread(&caller, nullptr) != JNI_OK) caller = nullptr;
            }
            if (caller) slot = caller;
        }
        ~EnvScope() { slot = saved; }
    };

    // ---- called from the generated ARM64 trampoline -----------------------
    uint64_t native_entry(uint32_t id, uint64_t* host_args) {
        EnvScope env_scope(env_, vm_);
        // The guest CPU is a single register file: two ART threads entering it
        // at once would interleave into one another. Recursive because a guest
        // ->Java->guest round trip re-enters from the same thread.
        std::lock_guard<std::recursive_mutex> emu_lock(emu_mu_);

        if (id >= natives_.size()) return 0;
        const NativeEntry& e = natives_[id];

        // Trace the first few calls per method; RunLoop ticks are per-frame and
        // would otherwise drown the log.
        auto seen = native_calls_.find(id);
        const int n = (seen == native_calls_.end()) ? 0 : seen->second;
        native_calls_[id] = n + 1;
        if (n < 3 || e.name == "StartupNativeImpl") {
            LOGI("native %s%s <- ART (call #%d)", e.name.c_str(), e.sig.c_str(), n + 1);
        }

        std::vector<uint32_t> args;
        args.push_back(env_shadow_);
        args.push_back(to_guest_ref(reinterpret_cast<void*>(host_args[1])));  // thiz

        // host_args layout: [0..7] integer registers, [8..15] FP registers.
        // The ARM32 guest uses the softfp ABI: a double is two core words on
        // an even register/stack slot, a float is one word.
        size_t int_index = 2;   // env, thiz already consumed
        size_t fp_index = 8;
        for (char t : e.arg_types) {
            if (t == 'F' || t == 'D') {
                const uint64_t bits = host_args[fp_index++];
                if (t == 'D') {
                    if (args.size() & 1u) args.push_back(0);   // even slot
                    args.push_back(static_cast<uint32_t>(bits));
                    args.push_back(static_cast<uint32_t>(bits >> 32));
                } else {
                    args.push_back(static_cast<uint32_t>(bits));
                }
                continue;
            }

            const uint64_t v = host_args[int_index++];
            if (t == 'L' || t == '[') {
                args.push_back(to_guest_ref(reinterpret_cast<void*>(v)));
            } else if (t == 'J') {
                if (args.size() & 1u) args.push_back(0);
                args.push_back(static_cast<uint32_t>(v));
                args.push_back(static_cast<uint32_t>(v >> 32));
            } else {
                args.push_back(static_cast<uint32_t>(v));
            }
        }

        uint32_t ret = 0;
        const int budget_ms = startup_budget_ms();

        // A call the budget suspended last time is still sitting on the guest's
        // stack. Finish it before dispatching anything else; if it needs more
        // than this call's budget, let ART have this call back too. Dropping a
        // frame here is harmless -- the engine is still initialising, and the
        // frame the UI thread waits for has already been answered.
        if (emu_.has_pending()) {
            bool still_paused = false;
            uint32_t ignored = 0;
            emu_.resume_pending(ignored, budget_ms, still_paused);
            if (still_paused) {
                // Remember this call so its effect is not lost. Dropping a
                // frame is harmless, dropping a `resize` is not: the engine
                // would keep the viewport of the first surface size.
                deferred_.active = true;
                deferred_.fn = e.guest_fn;
                deferred_.args = args;
                deferred_.name = e.name;
                LOGI("native %s%s deferred: guest engine still initialising",
                     e.name.c_str(), e.sig.c_str());
                return 0;
            }
            if (deferred_.active) {
                uint32_t replayed = 0;
                LOGI("native %s%s replayed after the suspended call completed",
                     deferred_.name.c_str(), e.sig.c_str());
                emu_.call(deferred_.fn, deferred_.args, replayed,
                          deferred_.name.c_str(), 0);
                deferred_ = Deferred{};
            }
        }

        const bool ok = emu_.call(e.guest_fn, args, ret, e.name.c_str(), budget_ms);
        if (n < 3) LOGI("native %s -> %s (ret=%d)", e.name.c_str(), ok ? "returned" : "FAILED",
                        static_cast<int>(ret));
        if (ok && e.name == "render") {
            // The swap itself belongs to the guest's Java renderer: it owns the
            // EGL context (javax.microedition.khronos.egl) and calls
            // eglSwapBuffers from its GLSurfaceView thread as soon as this
            // native method returns, so this is the frame's swap boundary.
            ++frames_;
            if (frames_ <= 60 || frames_ % 60 == 0) {
                LOGI("eglSwapBuffers: frame %u presented (guest mode %d)",
                     frames_, static_cast<int>(ret));
            }
        }
        if (!ok) return 0;
        return static_cast<uint64_t>(ret);
    }

private:
    // ---- Java_* binding helpers --------------------------------------------
    bool bind_one(const JavaExport& e) {
        jclass cls = find_class_through(env_, guest_loader_, e.class_path);
        if (!cls) {
            env_->ExceptionClear();   // normal while the class is still loading
            return false;
        }

        std::string sig;
        if (!find_method_signature(cls, e.method, sig)) {
            LOGW("Java_* %s.%s: no Java method with that name", e.class_path.c_str(),
                 e.method.c_str());
            env_->DeleteLocalRef(cls);
            return false;
        }

        NativeEntry ne;
        ne.id = static_cast<uint32_t>(natives_.size());
        ne.guest_fn = e.fn;
        ne.cls = e.class_path;
        ne.name = e.method;
        ne.sig = sig;
        parse_signature(sig, ne);

        void* code = HostTrampolines::emit(ne.id, reinterpret_cast<void*>(&bridge_native_entry));
        if (!code) {
            env_->DeleteLocalRef(cls);
            return false;
        }
        ne.code = code;
        natives_.push_back(ne);

        JNINativeMethod m{natives_.back().name.c_str(), natives_.back().sig.c_str(), code};
        const jint rc = env_->RegisterNatives(cls, &m, 1);
        if (rc != JNI_OK) {
            env_->ExceptionClear();
            natives_.pop_back();
            env_->DeleteLocalRef(cls);
            return false;
        }

        entry_points_[e.class_path + "." + e.method] = e.fn;
        LOGI("  bound %s.%s%s -> guest %08x", e.class_path.c_str(), e.method.c_str(),
             sig.c_str(), e.fn);
        env_->DeleteLocalRef(cls);
        return true;
    }

    // Walks the class's declared methods and returns the descriptor of the one
    // whose name matches, e.g. "(Ljava/lang/String;I)V".
    bool find_method_signature(jclass cls, const std::string& want, std::string& sig) {
        jclass class_cls = env_->FindClass("java/lang/Class");
        jclass method_cls = env_->FindClass("java/lang/reflect/Method");
        if (!class_cls || !method_cls) {
            env_->ExceptionClear();
            return false;
        }

        jmethodID get_declared = env_->GetMethodID(class_cls, "getDeclaredMethods",
                                                   "()[Ljava/lang/reflect/Method;");
        jmethodID get_name = env_->GetMethodID(method_cls, "getName", "()Ljava/lang/String;");
        jmethodID get_ret = env_->GetMethodID(method_cls, "getReturnType", "()Ljava/lang/Class;");
        jmethodID get_params = env_->GetMethodID(method_cls, "getParameterTypes",
                                                 "()[Ljava/lang/Class;");
        if (!get_declared || !get_name || !get_ret || !get_params) {
            env_->ExceptionClear();
            return false;
        }

        auto methods = static_cast<jobjectArray>(env_->CallObjectMethod(cls, get_declared));
        if (!methods) {
            env_->ExceptionClear();
            return false;
        }

        bool found = false;
        const jsize count = env_->GetArrayLength(methods);
        for (jsize i = 0; i < count && !found; ++i) {
            jobject m = env_->GetObjectArrayElement(methods, i);
            if (!m) continue;

            if (method_name(m, get_name) == want) {
                auto ret = static_cast<jclass>(env_->CallObjectMethod(m, get_ret));
                auto params = static_cast<jobjectArray>(env_->CallObjectMethod(m, get_params));
                if (ret && params) {
                    sig = "(";
                    const jsize argc = env_->GetArrayLength(params);
                    for (jsize k = 0; k < argc; ++k) {
                        auto pc = static_cast<jclass>(env_->GetObjectArrayElement(params, k));
                        sig += class_descriptor(pc);
                        if (pc) env_->DeleteLocalRef(pc);
                    }
                    sig += ")";
                    sig += class_descriptor(ret);
                    found = true;
                }
                if (ret) env_->DeleteLocalRef(ret);
                if (params) env_->DeleteLocalRef(params);
            }
            env_->DeleteLocalRef(m);
        }
        env_->DeleteLocalRef(methods);

        if (env_->ExceptionCheck()) {
            env_->ExceptionClear();
            return false;
        }
        return found;
    }

    std::string method_name(jobject method, jmethodID get_name) {
        auto jn = static_cast<jstring>(env_->CallObjectMethod(method, get_name));
        if (!jn) return {};
        std::string out;
        const char* p = env_->GetStringUTFChars(jn, nullptr);
        if (p) {
            out = p;
            env_->ReleaseStringUTFChars(jn, p);
        }
        env_->DeleteLocalRef(jn);
        return out;
    }

    std::string class_descriptor(jclass c) {
        if (!c) return "V";
        jclass class_cls = env_->FindClass("java/lang/Class");
        if (!class_cls) { env_->ExceptionClear(); return "V"; }
        jmethodID get_name = env_->GetMethodID(class_cls, "getName", "()Ljava/lang/String;");
        env_->DeleteLocalRef(class_cls);
        if (!get_name) return "V";

        auto jn = static_cast<jstring>(env_->CallObjectMethod(c, get_name));
        std::string name;
        if (jn) {
            const char* p = env_->GetStringUTFChars(jn, nullptr);
            if (p) { name = p; env_->ReleaseStringUTFChars(jn, p); }
            env_->DeleteLocalRef(jn);
        }
        if (name.empty()) return "V";

        if (name[0] == '[') {
            for (char& ch : name) if (ch == '.') ch = '/';
            return name;
        }

        static const std::unordered_map<std::string, std::string> prim = {
            {"void", "V"}, {"boolean", "Z"}, {"byte", "B"}, {"char", "C"},
            {"short", "S"}, {"int", "I"}, {"long", "J"}, {"float", "F"},
            {"double", "D"},
        };
        auto it = prim.find(name);
        if (it != prim.end()) return it->second;

        for (char& ch : name) if (ch == '.') ch = '/';
        return "L" + name + ";";
    }

    // ---- stub table --------------------------------------------------------
    uint32_t make_stub(StubKind kind, int index, const std::string& name) {
        const size_t slot = slots_.size();
        if (slot >= kStubCount) {
            LOGE("trap page exhausted (%zu stubs)", kStubCount);
            return kStubBase;  // slot 0 is still a valid `bx lr`
        }
        slots_.push_back({kind, index, name});
        return kStubBase + static_cast<uint32_t>(slot * kStubStride);
    }

    // A guest-callable trampoline for a host callback. Used wherever the guest
    // has to invoke a function pointer a host library handed it: OpenSL ES
    // interface vtables, ALooper callbacks, JNI weak hooks.
    uint32_t make_callback_stub(std::function<uint32_t()> fn, const std::string& name) {
        const size_t slot = slots_.size();
        if (slot >= kStubCount) {
            LOGE("trap page exhausted (%zu stubs)", kStubCount);
            return kStubBase;
        }
        callbacks_.push_back(std::move(fn));
        slots_.push_back({kStubCallback, static_cast<int>(callbacks_.size() - 1), name});
        return kStubBase + static_cast<uint32_t>(slot * kStubStride);
    }

    // bionic exports its ctype tables as *pointer variables*:
    //
    //     extern const char  *_ctype_;         // 257 bytes, indexed [c + 1]
    //     extern const short *_tolower_tab_;   // 257 shorts, indexed [c + 1]
    //     extern const short *_toupper_tab_;
    //
    // A payload compiled against bionic loads the pointer out of the GOT and
    // then indexes it. Resolving these to a code stub made Tcl read `bx lr`
    // (0xe12fff1e) as the table address and fault at pc=0x40094bd2. Build the
    // real BSD tables in guest memory and point the exported cells at them.
    bool install_locale_data() {
        enum : uint8_t {
            kUpper = 0x01, kLower = 0x02, kDigit = 0x04, kSpace = 0x08,
            kPunct = 0x10, kCntrl = 0x20, kXdigit = 0x40, kBlank = 0x80,
        };
        std::vector<uint8_t> ctype(257, 0);
        std::vector<int16_t> lower(257, 0), upper(257, 0);
        for (int c = 0; c < 256; ++c) {
            uint8_t f = 0;
            if (c < 0x20 || c == 0x7f) f |= kCntrl;
            if (c == '\t' || c == '\n' || c == '\v' || c == '\f' || c == '\r' || c == ' ') {
                f |= kSpace;
                if (c == ' ') f |= kBlank;
            }
            if (c >= '0' && c <= '9') f |= kDigit | kXdigit;
            if (c >= 'A' && c <= 'Z') f |= kUpper;
            if (c >= 'a' && c <= 'z') f |= kLower;
            if ((c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f')) f |= kXdigit;
            if ((c >= 0x21 && c <= 0x2f) || (c >= 0x3a && c <= 0x40) ||
                (c >= 0x5b && c <= 0x60) || (c >= 0x7b && c <= 0x7e)) {
                f |= kPunct;
            }
            ctype[static_cast<size_t>(c) + 1] = f;
            lower[static_cast<size_t>(c) + 1] =
                    static_cast<int16_t>((c >= 'A' && c <= 'Z') ? c + 32 : c);
            upper[static_cast<size_t>(c) + 1] =
                    static_cast<int16_t>((c >= 'a' && c <= 'z') ? c - 32 : c);
        }

        const uint32_t ctype_bytes = emu_.alloc(ctype.size(), 8);
        const uint32_t lower_bytes = emu_.alloc(lower.size() * sizeof(int16_t), 8);
        const uint32_t upper_bytes = emu_.alloc(upper.size() * sizeof(int16_t), 8);
        const uint32_t ctype_cell = emu_.alloc(4, 4);
        const uint32_t lower_cell = emu_.alloc(4, 4);
        const uint32_t upper_cell = emu_.alloc(4, 4);
        if (!ctype_bytes || !lower_bytes || !upper_bytes ||
            !ctype_cell || !lower_cell || !upper_cell) {
            LOGE("locale tables: guest heap exhausted");
            return false;
        }

        emu_.write(ctype_bytes, ctype.data(), ctype.size());
        emu_.write(lower_bytes, lower.data(), lower.size() * sizeof(int16_t));
        emu_.write(upper_bytes, upper.data(), upper.size() * sizeof(int16_t));
        emu_.write_u32(ctype_cell, ctype_bytes);
        emu_.write_u32(lower_cell, lower_bytes);
        emu_.write_u32(upper_cell, upper_bytes);

        data_slots_["_ctype_"] = ctype_cell;
        data_slots_["_tolower_tab_"] = lower_cell;
        data_slots_["_toupper_tab_"] = upper_cell;
        LOGI("data imports: _ctype_ table @%08x, _tolower_tab_ @%08x, "
             "_toupper_tab_ @%08x", ctype_bytes, lower_bytes, upper_bytes);
        return true;
    }

    uint32_t import_stub(const std::string& name) {
        auto it = import_slots_.find(name);
        if (it != import_slots_.end()) return it->second;

        // Data symbols must resolve to readable memory, not to a code stub.
        auto d = data_slots_.find(name);
        if (d != data_slots_.end()) {
            import_slots_[name] = d->second;
            return d->second;
        }

        const uint32_t addr = make_stub(kStubImport, 0, name);
        import_slots_[name] = addr;
        return addr;
    }

    static void code_hook_thunk(uc_engine* uc, uint64_t address, uint32_t size,
                                void* user_data) {
        (void)uc; (void)size;
        static_cast<JniBridge*>(user_data)->on_stub(static_cast<uint32_t>(address));
    }

    void on_stub(uint32_t pc) {
        const size_t slot = (pc - kStubBase) / kStubStride;
        if (slot >= slots_.size()) return;

        redirected_ = false;
        const StubSlot& s = slots_[slot];
        uint32_t ret = 0;
        switch (s.kind) {
            case kStubJniEnv: ret = dispatch_env(s.index); break;
            case kStubJniVm:  ret = dispatch_vm(s.index);  break;
            case kStubImport: ret = dispatch_import(slot, s); break;
            case kStubCallback:
                ret = s.index >= 0 && static_cast<size_t>(s.index) < callbacks_.size()
                          ? callbacks_[static_cast<size_t>(s.index)]() : 0u;
                break;
        }

        // A handler may have re-pointed PC itself (an in-guest call such as
        // pthread_once); in that case returning to LR here would skip it.
        if (redirected_) return;

        emu_.reg_write(UC_ARM_REG_R0, ret);
        // Leave PC on the stub's `bx lr`. Executing the real branch is what
        // preserves ARM/Thumb interworking: writing PC from the hook clears
        // CPSR.T, so the next Thumb opcode decodes as an ARM coprocessor
        // instruction and the guest takes an undefined-instruction exception.
    }

    // Call a guest function from inside a hook. uc_emu_start() is not
    // reentrant, so instead of nesting we push the caller's LR, point LR at a
    // guest trampoline that clears R0 and returns, and jump straight to the
    // target. The guest's own `ret` then lands back in the caller.
    uint32_t call_in_guest(uint32_t fn, uint32_t first_arg = 0) {
        if (!fn || !return_gadget_) return 0;
        uint32_t sp = 0, lr = 0;
        emu_.reg_read(UC_ARM_REG_SP, sp);
        emu_.reg_read(UC_ARM_REG_LR, lr);
        if (sp < kHeapBase || sp > kStackTop) {
            LOGW("call_in_guest(%08x): implausible SP %08x", fn, sp);
            return 0;
        }
        if (!emu_.write_u32(sp - 4, lr)) return 0;
        emu_.reg_write(UC_ARM_REG_SP, sp - 4);
        emu_.reg_write(UC_ARM_REG_R0, first_arg);
        emu_.reg_write(UC_ARM_REG_LR, return_gadget_);
        emu_.reg_write(UC_ARM_REG_PC, fn);
        redirected_ = true;
        return 0;
    }

    uint32_t arg(size_t i) {
        uint32_t v = 0;
        emu_.reg_read(UC_ARM_REG_R0 + static_cast<int>(i), v);
        return v;
    }

    // =====================================================================
    // Filesystem redirection
    //
    // A legacy game hardcodes /sdcard/Android/obb/<package>/... and friends.
    // This process can never open those paths (scoped storage, and no shared
    // storage grant on many devices), so every path that names the package is
    // rewritten onto the ingested copy before it reaches the host.
    // =====================================================================
    std::string translate_path(const std::string& in, const char* why) {
        if (in.empty()) return in;

        // A relative path is resolved against the guest's virtual cwd. The host
        // process cwd is shared by every ART thread and must never move.
        if (in[0] != '/' && !guest_cwd_.empty()) {
            const std::string out = guest_cwd_ + "/" + in;
            if (translate_log_.insert(in).second) {
                LOGI("%s: %s -> %s (cwd)", why, in.c_str(), out.c_str());
            }
            return out;
        }

        std::string out = in;
        bool changed = false;

        if (!package_.empty()) {
            auto fold = [&](const std::string& marker, const std::string& target) {
                const size_t at = in.find(marker);
                if (at == std::string::npos) return false;
                const size_t rest = at + marker.size();
                if (rest < in.size() && in[rest] != '/') return false;
                out = target + in.substr(rest);
                changed = true;
                return true;
            };

            changed = fold("/Android/obb/" + package_, obb_dir_);
            if (!changed) changed = fold("/Android/data/" + package_, data_dir_ + "/external");
            if (!changed) changed = fold("/data/data/" + package_, data_dir_);
            if (!changed) changed = fold("/data/user/0/" + package_, data_dir_);
            if (!changed) changed = fold("/data/user_de/0/" + package_, data_dir_);

            // The guest's own data directory, wherever the platform used to
            // put it: /sdcard/<package>/courses/bunny_hill/course.tcl and
            // friends are built at runtime from a storage path the game only
            // learns at start-up. Everything after the package name has to
            // survive the rewrite -- keeping the basename alone turned
            // courses/bunny_hill/course.tcl into <data>/course.tcl, which does
            // not exist, and the engine then aborts while parsing the course.
            if (!changed && !in.empty() && in[0] == '/') {
                static const char* kSharedRoots[] = {
                    "/sdcard/", "/mnt/sdcard/", "/storage/emulated/0/",
                    "/storage/self/primary/",
                };
                for (const char* shared : kSharedRoots) {
                    const std::string base = std::string(shared) + package_;
                    if (in.compare(0, base.size(), base) != 0) continue;
                    if (in.size() > base.size() && in[base.size()] != '/') continue;
                    out = data_dir_ + in.substr(base.size());
                    changed = true;
                    break;
                }
            }

            // Any other absolute path under shared storage that mentions the
            // package: keep only its basename inside our private data dir.
            if (!changed && !in.empty() && in[0] == '/' &&
                in.find(package_) != std::string::npos &&
                (in.compare(0, 7, "/sdcard") == 0 ||
                 in.compare(0, 8, "/storage") == 0 ||
                 in.compare(0, 11, "/mnt/sdcard") == 0)) {
                const size_t slash = in.find_last_of('/');
                out = data_dir_ + "/" + in.substr(slash + 1);
                changed = true;
            }
        }

        if (changed && translate_log_.insert(in).second) {
            LOGI("%s: %s -> %s", why, in.c_str(), out.c_str());
        }
        return out;
    }

    // =====================================================================
    // Runtime dlopen / dlsym
    //
    // A guest that loads another 32-bit library must get it mapped into the
    // emulated address space, never handed to the host linker. The guest sees
    // an opaque 32-bit handle; the real object is a LoadedLibrary.
    // =====================================================================
    std::string payload_library_path(const std::string& name) {
        if (name.empty()) return {};
        std::string base = name;
        const size_t slash = base.find_last_of('/');
        if (slash != std::string::npos) base = base.substr(slash + 1);
        if (base.empty()) return {};

        // An absolute request is either a redirected game path or a system
        // library the guest expects to find on the device.
        if (name[0] == '/') {
            const std::string translated = translate_path(name, "dlopen");
            if (::access(translated.c_str(), R_OK) == 0) return translated;
            return sysroot_library(base);
        }

        std::string tagged = base;
        if (tagged.size() > 3 && tagged.compare(tagged.size() - 3, 3, ".so") == 0) {
            tagged = tagged.substr(0, tagged.size() - 3) + "32.so";
        }
        const std::string candidate = payload_dir_ + "/" + tagged;
        if (::access(candidate.c_str(), R_OK) == 0) return candidate;

        const std::string untagged = payload_dir_ + "/" + base;
        if (::access(untagged.c_str(), R_OK) == 0) return untagged;

        // Last resort: the bundled 32-bit compatibility sysroot.
        const std::string from_runtime = sysroot_library(base);
        if (!from_runtime.empty()) {
            LOGI("dlopen(%s): resolved from bundled sysroot (%s)", name.c_str(),
                 from_runtime.c_str());
        }
        return from_runtime;
    }

public:
    void resolve_all_needed() {
        bool added = true;
        while (added) {
            added = false;
            const auto& current_libs = linker_.libs();
            const size_t count = current_libs.size();
            for (size_t i = 0; i < count; ++i) {
                const auto* lib = current_libs[i];
                for (const auto& dep : lib->needed) {
                    bool found = false;
                    for (const auto* l : linker_.libs()) {
                        if (l->soname == dep || l->path.find(dep) != std::string::npos) {
                            found = true;
                            break;
                        }
                    }
                    if (found) continue;

                    std::string path = payload_library_path(dep);
                    if (path.empty() || ::access(path.c_str(), R_OK) != 0) {
                        continue;
                    }

                    if (next_runtime_slot_ >= (kStackTop - kImageBase0) / kImageStride) {
                        LOGE("DT_NEEDED(%s): no free guest image slots", dep.c_str());
                        break;
                    }

                    const uint32_t base = kImageBase0 + (next_runtime_slot_++) * kImageStride;
                    LoadedLibrary* child = linker_.load(path, base);
                    if (child) {
                        LOGI("DT_NEEDED: %s -> mapped %s (%s) at %08x",
                             lib->soname.empty() ? lib->path.c_str() : lib->soname.c_str(),
                             dep.c_str(), path.c_str(), base);
                        added = true;
                    }
                }
            }
        }
    }

    uint32_t guest_dlopen(const std::string& name) {
        if (name.empty()) return 0;
        auto known = dl_handles_.find(name);
        if (known != dl_handles_.end()) return known->second;

        const std::string path = payload_library_path(name);
        if (path.empty() || ::access(path.c_str(), R_OK) != 0) {
            LOGW("dlopen(%s): not found in payload/ or the bundled sysroot", name.c_str());
            return 0;
        }

        // Guest images live between kImageBase0 and the stack top. There are
        // at most 60 usable 16 MB slots before that would collide with the
        // stack, so a runaway dlopen loop is refused rather than corrupting
        // the address space.
        if (next_runtime_slot_ >= (kStackTop - kImageBase0) / kImageStride) {
            LOGE("dlopen(%s): no free guest image slots", name.c_str());
            return 0;
        }
        const uint32_t base = kImageBase0 + (next_runtime_slot_++) * kImageStride;
        LoadedLibrary* lib = linker_.load(path, base);
        if (!lib) return 0;

        resolve_all_needed();
        linker_.link_all();   // relocates only the newly loaded object
        run_constructors(*lib);
        bind_exports_of(lib);

        const uint32_t onload = export_of(lib, "JNI_OnLoad");
        if (onload) {
            uint32_t ret = 0;
            call_onload(onload, ret);
        }

        const uint32_t handle = to_guest_ref(
            reinterpret_cast<void*>(static_cast<uintptr_t>(base)));
        dl_handles_[name] = handle;
        dl_libs_[base] = lib;
        LOGI("dlopen(%s) -> %s @ %08x (guest handle %08x)", name.c_str(), path.c_str(),
             base, handle);
        return handle;
    }

    uint32_t guest_dlsym(uint32_t handle, const std::string& symbol) {
        if (symbol.empty()) return 0;
        auto lookup = [&symbol](const LoadedLibrary* lib) -> uint32_t {
            if (!lib) return 0;
            auto it = lib->exports.find(symbol);
            return it == lib->exports.end() ? 0u : it->second;
        };

        if (handle) {
            const uint32_t base = static_cast<uint32_t>(reinterpret_cast<uintptr_t>(
                from_guest_ref(handle)));
            auto it = dl_libs_.find(base);
            if (it != dl_libs_.end()) return lookup(it->second);
        }
        for (const auto* lib : linker_.libs()) {
            const uint32_t at = lookup(lib);
            if (at) return at;
        }
        LOGW("dlsym(handle %08x, %s) not found in any mapped payload", handle,
             symbol.c_str());
        return 0;
    }

    void bind_exports_of(LoadedLibrary* lib) {
        if (!lib) return;
        size_t bound = 0;
        for (const auto& kv : lib->exports) {
            JavaExport e;
            if (!StaticLinker::parse_java_symbol(kv.first, e.class_path, e.method)) continue;
            e.fn = kv.second;
            if (bind_one(e)) ++bound;
            else pending_.push_back(e);
        }
        if (bound) {
            LOGI("%s: %zu Java_* exports bound after runtime load", lib->path.c_str(), bound);
        }
    }

    // =====================================================================
    // mmap / munmap
    //
    // Anonymous mappings come from the guest heap; file-backed mappings are
    // read into guest memory. The guest never receives a host address, and no
    // host page is mapped into the emulator.
    // =====================================================================
    static constexpr uint32_t kMapFailed = 0xFFFFFFFFu;

    // AAPCS puts arguments 0..3 in r0..r3 and everything after that on the
    // stack. arg() only reads registers, so the 5th/6th arguments of calls like
    // mmap() must be fetched from [sp].
    uint32_t stacked_arg(size_t i) {
        if (i < 4) return arg(i);
        uint32_t sp = 0;
        emu_.reg_read(UC_ARM_REG_SP, sp);
        return emu_.read_u32(sp + static_cast<uint32_t>((i - 4) * 4));
    }

    float stacked_f_arg(size_t i) {
        const uint32_t bits = stacked_arg(i);
        float f;
        std::memcpy(&f, &bits, sizeof f);
        return f;
    }

    uint32_t imp_mmap(bool mmap64) {
        (void)mmap64;
        const uint32_t len = arg(1);
        if (len == 0) return kMapFailed;

        const int fd = static_cast<int>(stacked_arg(4));
        const uint32_t off = stacked_arg(5);
        const uint32_t p = emu_.alloc(len, kPageSize);
        if (!p) {
            LOGE("mmap(%u) failed: guest heap exhausted", len);
            return kMapFailed;
        }

        if (fd >= 0) {
            const int hfd = host_fd(static_cast<uint32_t>(fd));
            if (hfd >= 0) {
                std::vector<uint8_t> buf(1u << 20);
                size_t done = 0;
                while (done < len) {
                    const size_t want = std::min(buf.size(), len - done);
                    const ssize_t n = ::pread(hfd, buf.data(), want, off + done);
                    if (n <= 0) break;
                    emu_.write(p + static_cast<uint32_t>(done), buf.data(),
                               static_cast<size_t>(n));
                    done += static_cast<size_t>(n);
                }
            } else {
                LOGW("mmap: guest fd %d is not open", fd);
            }
        }
        LOGI("mmap(len=%u, fd=%d, off=%u) -> guest %08x", len, fd, off, p);
        return p;
    }

    uint32_t imp_munmap() {
        emu_.release(arg(0), arg(1));
        return 0u;
    }

    // =====================================================================
    // EGL / GLES forwarding
    //
    // The guest calls the real host EGL and GLES drivers, but every opaque
    // 64-bit object pointer (display, config, context, surface) is exchanged
    // for a 32-bit guest handle. Window surfaces are re-pointed at the
    // ANativeWindow that GameHostActivity's SurfaceView produced, which is what
    // puts the guest's frames on the actual screen.
    // =====================================================================
    void* host_gl_symbol(const char* name) {
        auto it = host_syms_.find(name);
        if (it != host_syms_.end()) return it->second;

        // GLES1 first. A legacy engine is a fixed-function client: it has a
        // GLES1 context current and its entry points (glVertexPointer,
        // glMatrixMode, glAlphaFunc, ...) only exist in libGLESv1_CM. Resolving
        // them from libGLESv2 yields NULL, every fixed-function call silently
        // disappears and the driver ends up with a draw that has "no data
        // bound". Both libraries feed the same host translation layer, so the
        // shared entry points are interchangeable.
        void* sym = nullptr;
        if (gles1_lib_) sym = ::dlsym(gles1_lib_, name);
        if (!sym && egl_lib_) sym = ::dlsym(egl_lib_, name);
        if (!sym && gles_lib_) sym = ::dlsym(gles_lib_, name);
        host_syms_[name] = sym;
        return sym;
    }

    template <typename T>
    T gl_proc(const char* name) {
        return reinterpret_cast<T>(host_gl_symbol(name));
    }

    EGLDisplay guest_display(uint32_t handle) {
        return static_cast<EGLDisplay>(from_guest_ref(handle));
    }

    std::vector<EGLint> read_egl_ints(uint32_t addr) {
        std::vector<EGLint> out;
        if (!addr) return out;
        for (int i = 0; i < 256; ++i) {
            bool ok = false;
            const uint32_t v = emu_.read_u32(addr + static_cast<uint32_t>(i * 4), &ok);
            if (!ok) break;
            out.push_back(static_cast<EGLint>(v));
            if (v == EGL_NONE) break;
        }
        return out;
    }

    void open_host_graphics() {
        egl_lib_ = ::dlopen("libEGL.so", RTLD_NOW | RTLD_LOCAL);
        gles1_lib_ = ::dlopen("libGLESv1_CM.so", RTLD_NOW | RTLD_LOCAL);
        gles_lib_ = ::dlopen("libGLESv2.so", RTLD_NOW | RTLD_LOCAL);
        LOGI("host graphics: libEGL=%p libGLESv1_CM=%p libGLESv2=%p",
             egl_lib_, gles1_lib_, gles_lib_);
    }

    uint32_t dispatch_import(size_t slot, const StubSlot& s) {
        g_last_import = s.name;
        auto it = import_handlers_.find(s.name);
        if (it != import_handlers_.end()) return it->second();

        const int key = 3 * 100000 + static_cast<int>(slot);
        if (logged_.insert(key).second) {
            LOGW("unimplemented import: %s (returning 0)", s.name.c_str());
        }
        return 0;
    }

    uint32_t dispatch_env(int index) {
        if (!pending_.empty()) retry_pending_bindings();

        switch (index) {
            case kIdxGetVersion:
                return static_cast<uint32_t>(env_->GetVersion());

            case kIdxFindClass: {
                const std::string name = guest_string(arg(1));
                // CheckJNI turns a FindClass with a pending exception into a
                // process abort. A guest callback that threw has already been
                // reported; drop the exception so the bridge can continue.
                if (env_->ExceptionCheck()) {
                    LOGW("FindClass(%s) with a pending exception; clearing", name.c_str());
                    env_->ExceptionClear();
                }
                // Must resolve through the guest DexClassLoader: env_->FindClass
                // uses the host application's loader, where the game's classes
                // do not exist.
                jclass c = find_class_through(env_, guest_loader_, name);
                if (!c) { env_->ExceptionClear(); LOGE("FindClass(%s) failed", name.c_str()); }
                return to_guest_ref(c);
            }

            case kIdxGetObjectClass: {
                auto* o = static_cast<jobject>(from_guest_ref(arg(1)));
                return o ? to_guest_ref(env_->GetObjectClass(o)) : 0;
            }

            case kIdxGetSuperclass: {
                auto* c = static_cast<jclass>(from_guest_ref(arg(1)));
                return c ? to_guest_ref(env_->GetSuperclass(c)) : 0;
            }

            case kIdxGetMethodID: {
                auto* c = static_cast<jclass>(from_guest_ref(arg(1)));
                if (!c) return 0;
                const std::string name = guest_string(arg(2));
                const std::string sig = guest_string(arg(3));
                jmethodID m = env_->GetMethodID(c, name.c_str(), sig.c_str());
                if (!m) { env_->ExceptionClear(); LOGW("GetMethodID(%s%s) failed", name.c_str(), sig.c_str()); }
                // Remembered so a later Call*Method can rebuild the argument list.
                else method_sigs_[static_cast<void*>(m)] = sig;
                return to_guest_ref(m);
            }

            case kIdxGetStaticMethodID: {
                const std::string name = guest_string(arg(2));
                const std::string sig = guest_string(arg(3));
                auto* c = static_cast<jclass>(from_guest_ref(arg(1)));
                // CheckJNI aborts the process on a null class, so this has to be
                // a logged miss: a guest class that failed FindClass must not
                // take the whole bridge down.
                if (!c) {
                    LOGW("GetStaticMethodID(%s%s) on null class", name.c_str(), sig.c_str());
                    return 0;
                }
                jmethodID m = env_->GetStaticMethodID(c, name.c_str(), sig.c_str());
                if (!m) { env_->ExceptionClear(); LOGW("GetStaticMethodID(%s%s) failed", name.c_str(), sig.c_str()); }
                else method_sigs_[static_cast<void*>(m)] = sig;
                return to_guest_ref(m);
            }

            case kIdxGetFieldID: {
                const std::string name = guest_string(arg(2));
                const std::string sig = guest_string(arg(3));
                auto* c = static_cast<jclass>(from_guest_ref(arg(1)));
                if (!c) {
                    LOGW("GetFieldID(%s%s) on null class", name.c_str(), sig.c_str());
                    return 0;
                }
                return to_guest_ref(env_->GetFieldID(c, name.c_str(), sig.c_str()));
            }

            case kIdxNewStringUTF: {
                const std::string s = guest_string(arg(1));
                return to_guest_ref(env_->NewStringUTF(s.c_str()));
            }

            case kIdxGetStringUTFLength: {
                auto* s = static_cast<jstring>(from_guest_ref(arg(1)));
                return s ? static_cast<uint32_t>(env_->GetStringUTFLength(s)) : 0u;
            }

            case kIdxGetStringUTFChars: {
                auto* str = static_cast<jstring>(from_guest_ref(arg(1)));
                if (!str) return 0;
                const char* chars = env_->GetStringUTFChars(str, nullptr);
                if (!chars) return 0;
                // The guest must get a real 32-bit pointer it can dereference,
                // so hand it a private copy in guest memory.
                const uint32_t at = guest_str(chars);
                env_->ReleaseStringUTFChars(str, chars);
                return at;
            }

            case kIdxReleaseStringUTFChars:
                // The guest got a private copy; nothing to release on the host.
                return 0;

            case kIdxNewGlobalRef:
                return to_guest_ref(env_->NewGlobalRef(
                    static_cast<jobject>(from_guest_ref(arg(1)))));

            case kIdxDeleteGlobalRef:
                return 0;

            case kIdxDeleteLocalRef:
                return 0;

            case kIdxNewLocalRef:
                return to_guest_ref(env_->NewLocalRef(
                    static_cast<jobject>(from_guest_ref(arg(1)))));

            case kIdxIsSameObject:
                return env_->IsSameObject(static_cast<jobject>(from_guest_ref(arg(1))),
                                          static_cast<jobject>(from_guest_ref(arg(2))))
                           ? JNI_TRUE : JNI_FALSE;

            case kIdxGetJavaVM: {
                const uint32_t out = arg(1);
                if (out) emu_.write_u32(out, vm_shadow_);
                return JNI_OK;
            }

            case kIdxExceptionOccurred:
                return to_guest_ref(env_->ExceptionOccurred());

            case kIdxExceptionDescribe:
                env_->ExceptionDescribe();
                return 0;

            case kIdxExceptionClear:
                env_->ExceptionClear();
                return 0;

            case kIdxExceptionCheck:
                return env_->ExceptionCheck() ? JNI_TRUE : JNI_FALSE;

            case kIdxRegisterNatives:
                return register_natives();

            case kIdxIsInstanceOf: {
                auto* o = static_cast<jobject>(from_guest_ref(arg(1)));
                auto* c = static_cast<jclass>(from_guest_ref(arg(2)));
                return (o && c && env_->IsInstanceOf(o, c)) ? JNI_TRUE : JNI_FALSE;
            }

            case kIdxIsAssignableFrom:
                return env_->IsAssignableFrom(static_cast<jclass>(from_guest_ref(arg(1))),
                                              static_cast<jclass>(from_guest_ref(arg(2))))
                           ? JNI_TRUE : JNI_FALSE;

            case kIdxThrow:
                return static_cast<uint32_t>(
                    env_->Throw(static_cast<jthrowable>(from_guest_ref(arg(1)))));

            case kIdxThrowNew: {
                const std::string msg = guest_string(arg(2));
                LOGE("guest throws %s: %s", guest_string(arg(1)).c_str(), msg.c_str());
                return static_cast<uint32_t>(env_->ThrowNew(
                    static_cast<jclass>(from_guest_ref(arg(1))), msg.c_str()));
            }

            case kIdxFatalError:
                LOGE("guest FatalError: %s", guest_string(arg(1)).c_str());
                return 0;

            case kIdxPushLocalFrame:
                return static_cast<uint32_t>(
                    env_->PushLocalFrame(static_cast<jint>(arg(1))));

            case kIdxPopLocalFrame:
                return to_guest_ref(
                    env_->PopLocalFrame(static_cast<jobject>(from_guest_ref(arg(1)))));

            case kIdxEnsureLocalCapacity:
                return static_cast<uint32_t>(
                    env_->EnsureLocalCapacity(static_cast<jint>(arg(1))));

            case kIdxAllocObject:
                return to_guest_ref(
                    env_->AllocObject(static_cast<jclass>(from_guest_ref(arg(1)))));

            case kIdxNewObject:   return new_object(0);
            case kIdxNewObjectV:  return new_object(1);
            case kIdxNewObjectA:  return new_object(2);

            case kIdxGetArrayLength:
                return static_cast<uint32_t>(
                    env_->GetArrayLength(static_cast<jarray>(from_guest_ref(arg(1)))));

            case kIdxNewObjectArray:
                return to_guest_ref(env_->NewObjectArray(
                    static_cast<jsize>(arg(1)),
                    static_cast<jclass>(from_guest_ref(arg(2))),
                    static_cast<jobject>(from_guest_ref(arg(3)))));

            case kIdxGetObjectArrayElement:
                return to_guest_ref(env_->GetObjectArrayElement(
                    static_cast<jobjectArray>(from_guest_ref(arg(1))),
                    static_cast<jsize>(arg(2))));

            case kIdxSetObjectArrayElement:
                env_->SetObjectArrayElement(
                    static_cast<jobjectArray>(from_guest_ref(arg(1))),
                    static_cast<jsize>(arg(2)),
                    static_cast<jobject>(from_guest_ref(arg(3))));
                return 0;

            default:
                // Fixed-layout blocks, indexed arithmetically rather than with
                // dozens of individual case labels.
                if (index >= kIdxCallObjectMethod &&
                    index < kIdxCallObjectMethod + 30) {
                    const int k = index - kIdxCallObjectMethod;
                    return call_java(kCallTypeOrder[k / 3], false, k % 3);
                }
                if (index >= kIdxCallStaticObjectMethod &&
                    index < kIdxCallStaticObjectMethod + 30) {
                    const int k = index - kIdxCallStaticObjectMethod;
                    return call_java(kCallTypeOrder[k / 3], true, k % 3);
                }
                if (index >= kIdxNewBooleanArray && index < kIdxNewBooleanArray + 8) {
                    return new_primitive_array(array_type(index - kIdxNewBooleanArray));
                }
                if (index >= kIdxGetBooleanArrayRegion &&
                    index < kIdxGetBooleanArrayRegion + 16) {
                    const int k = index - kIdxGetBooleanArrayRegion;
                    return array_region(array_type(k / 2), (k % 2) == 1);
                }
                if (index >= kIdxGetBooleanArrayElements &&
                    index < kIdxGetBooleanArrayElements + 16) {
                    const int k = index - kIdxGetBooleanArrayElements;
                    const char t = array_type(k / 2);
                    return (k % 2) == 0 ? array_elements(t) : release_array_elements(t);
                }
                log_once_env(index);
                return 0;
        }
    }

    uint32_t dispatch_vm(int index) {
        switch (index) {
            case kIdxVmGetEnv:
                if (arg(1)) emu_.write_u32(arg(1), env_shadow_);
                return JNI_OK;
            case kIdxVmAttach:
                if (arg(1)) emu_.write_u32(arg(1), env_shadow_);
                return JNI_OK;
            case kIdxVmDetach:
            case kIdxVmDestroy:
                return JNI_OK;
            default:
                log_once_vm(index);
                return 0;
        }
    }

    // =====================================================================
    // Host routing table
    //
    // ARM32 Android uses the *softfp* ABI: a `float` argument travels in a core
    // register as its raw bit pattern and a `double` in an even/odd register
    // pair, so every handler reinterprets R0..R3 instead of trusting the host
    // calling convention. 64-bit results are written back to R1 as well as R0.
    // =====================================================================
    float f_arg(int i) {
        const uint32_t b = arg(i);
        float f;
        std::memcpy(&f, &b, sizeof f);
        return f;
    }

    double d_arg(int i) {
        const uint32_t lo = arg(i), hi = arg(i + 1);
        const uint64_t b = static_cast<uint64_t>(lo) | (static_cast<uint64_t>(hi) << 32);
        double d;
        std::memcpy(&d, &b, sizeof d);
        return d;
    }

    uint32_t ret_f(float f) {
        uint32_t b;
        std::memcpy(&b, &f, sizeof b);
        return b;
    }

    uint32_t ret_d(double d) {
        uint64_t b;
        std::memcpy(&b, &d, sizeof b);
        emu_.reg_write(UC_ARM_REG_R1, static_cast<uint32_t>(b >> 32));
        return static_cast<uint32_t>(b);
    }

    uint32_t ret_i64(int64_t v) {
        const uint64_t b = static_cast<uint64_t>(v);
        emu_.reg_write(UC_ARM_REG_R1, static_cast<uint32_t>(b >> 32));
        return static_cast<uint32_t>(b);
    }

    void write_double(uint32_t at, double d) {
        uint64_t b;
        std::memcpy(&b, &d, sizeof b);
        emu_.write_u32(at, static_cast<uint32_t>(b));
        emu_.write_u32(at + 4, static_cast<uint32_t>(b >> 32));
    }

    // ---- pthread / TLS ---------------------------------------------------
    // The guest is single-threaded inside the emulator, so mutual exclusion is
    // trivially satisfied and the lock calls are honest no-ops. What must be
    // real is pthread_once (static initialisation runs exactly once) and the
    // TLS key/value pairs, or C++ statics in libapp.so never initialise.
    uint32_t imp_pthread_once() {
        const uint32_t once = arg(0), fn = arg(1);
        const uint32_t state = emu_.read_u32(once);
        if (state != 0) return 0;
        if (!emu_.write_u32(once, 1)) return 0;
        LOGI("pthread_once: running init routine %08x in-guest", fn);
        return call_in_guest(fn);
    }

    uint32_t imp_pthread_key_create() {
        const uint32_t out = arg(0);
        const uint32_t key = next_tls_key_++;
        if (out) emu_.write_u32(out, key);
        return 0;
    }

    uint32_t imp_pthread_getspecific() {
        auto it = tls_values_.find(arg(0));
        return it == tls_values_.end() ? 0 : it->second;
    }

    uint32_t imp_pthread_setspecific() {
        tls_values_[arg(0)] = arg(1);
        return 0;
    }

    // ---- time ------------------------------------------------------------
    uint32_t imp_gettimeofday() {
        struct timeval tv{};
        ::gettimeofday(&tv, nullptr);
        const uint32_t out = arg(0);
        if (out) {
            emu_.write_u32(out + 0, static_cast<uint32_t>(tv.tv_sec));
            emu_.write_u32(out + 4, static_cast<uint32_t>(tv.tv_usec));
        }
        return 0;
    }

    uint32_t imp_clock_gettime() {
        struct timespec ts{};
        ::clock_gettime(static_cast<clockid_t>(arg(0)), &ts);
        const uint32_t out = arg(1);
        if (out) {
            emu_.write_u32(out + 0, static_cast<uint32_t>(ts.tv_sec));
            emu_.write_u32(out + 4, static_cast<uint32_t>(ts.tv_nsec));
        }
        return 0;
    }

    // ---- POSIX file / stdio -----------------------------------------------
    // Guest paths are ordinary Android paths, so the host opens them directly.
    // Only the handle is narrowed: the guest gets a small integer, the host
    // keeps the real descriptor / FILE*.
    int host_fd(uint32_t g) {
        auto it = fd_map_.find(g);
        return it == fd_map_.end() ? -1 : it->second;
    }

    uint32_t imp_open() {
        std::string path = translate_path(guest_string(arg(0)), "open");
        const bool cpuinfo = is_proc_cpuinfo(path) && !virtual_cpuinfo_.empty();
        if (cpuinfo) path = virtual_cpuinfo_;
        const int fd = ::open(path.c_str(),
                              cpuinfo ? O_RDONLY : static_cast<int>(arg(1)),
                              static_cast<int>(arg(2)));
        if (fd < 0) {
            LOGW("open(%s) -> %s", path.c_str(), strerror(errno));
            return static_cast<uint32_t>(-1);
        }
        const uint32_t g = next_handle_++;
        fd_map_[g] = fd;
        return g;
    }

    uint32_t imp_close() {
        auto it = fd_map_.find(arg(0));
        if (it == fd_map_.end()) return 0;
        ::close(it->second);
        fd_map_.erase(it);
        return 0;
    }

    uint32_t imp_read() {
        const int fd = host_fd(arg(0));
        if (fd < 0) return static_cast<uint32_t>(-1);
        const uint32_t dst = arg(1);
        uint32_t n = arg(2);
        std::vector<uint8_t> buf(n);
        const ssize_t got = ::read(fd, buf.data(), n);
        if (got <= 0) return static_cast<uint32_t>(got);
        emu_.write(dst, buf.data(), static_cast<size_t>(got));
        return static_cast<uint32_t>(got);
    }

    uint32_t imp_write() {
        const int fd = host_fd(arg(0));
        if (fd < 0) return static_cast<uint32_t>(-1);
        const uint32_t src = arg(1);
        const uint32_t n = arg(2);
        std::vector<uint8_t> buf(n);
        if (n && !emu_.read(src, buf.data(), n)) return static_cast<uint32_t>(-1);
        return static_cast<uint32_t>(::write(fd, buf.data(), n));
    }

    uint32_t imp_lseek() {
        const int fd = host_fd(arg(0));
        if (fd < 0) return static_cast<uint32_t>(-1);
        const uint64_t lo = arg(1), hi = arg(2);
        const uint64_t off = lo | (hi << 32);
        const off_t r = ::lseek(fd, static_cast<off_t>(static_cast<int64_t>(off)),
                                static_cast<int>(arg(3)));
        return ret_i64(static_cast<int64_t>(r));
    }

    // bionic ARM32 `struct stat` is 104 bytes with these offsets (verified
    // against the NDK's armv7 headers). The host layout is different, so it is
    // marshalled field by field rather than memcpy'd.
    void write_guest_stat(uint32_t at, const struct stat& st) {
        if (!at) return;
        std::vector<uint8_t> buf(104, 0);
        auto put32 = [&](size_t off, uint32_t v) { std::memcpy(&buf[off], &v, 4); };
        auto put64 = [&](size_t off, uint64_t v) { std::memcpy(&buf[off], &v, 8); };
        put64(0,  static_cast<uint64_t>(st.st_dev));
        put32(16, static_cast<uint32_t>(st.st_mode));
        put64(48, static_cast<uint64_t>(st.st_size));
        put32(80, static_cast<uint32_t>(st.st_mtime));
        put64(96, static_cast<uint64_t>(st.st_ino));
        emu_.write(at, buf.data(), buf.size());
    }

    // A guest probes for optional files with stat()/access() and branches on
    // errno. Returning -1 with a stale (often 0) guest errno makes callers that
    // test `errno != ENOENT` treat "file absent" as a fatal error, so every
    // failing wrapper has to publish the host errno into the guest cell.
    void set_guest_errno(int value) {
        if (errno_cell_) emu_.write_u32(errno_cell_, static_cast<uint32_t>(value));
    }

    void set_guest_errno_from_host() {
        set_guest_errno(errno ? errno : EIO);
    }

    uint32_t imp_stat_common(bool by_fd) {
        const uint32_t out = arg(1);
        struct stat st{};
        int rc = -1;
        if (by_fd) {
            const int fd = host_fd(arg(0));
            if (fd < 0) {
                set_guest_errno(EBADF);
                return static_cast<uint32_t>(-1);
            }
            rc = ::fstat(fd, &st);
        } else {
            rc = ::stat(translate_path(guest_string(arg(0)), "stat").c_str(), &st);
        }
        if (rc != 0) {
            set_guest_errno_from_host();
            return static_cast<uint32_t>(-1);
        }
        write_guest_stat(out, st);
        return 0;
    }

    uint32_t imp_fopen() {
        std::string path = translate_path(guest_string(arg(0)), "fopen");
        const std::string mode = guest_string(arg(1));
        // A read-only probe of /proc/cpuinfo is served from the synthetic
        // file; a write attempt still opens the real (host) path so nothing
        // silently succeeds where the kernel would refuse.
        const bool cpuinfo = is_proc_cpuinfo(path) && !virtual_cpuinfo_.empty() &&
                             mode.find('w') == std::string::npos &&
                             mode.find('a') == std::string::npos &&
                             mode.find('+') == std::string::npos;
        if (cpuinfo) path = virtual_cpuinfo_;
        FILE* f = ::fopen(path.c_str(), mode.c_str());
        if (!f) {
            LOGW("fopen(%s, %s) -> %s", path.c_str(), mode.c_str(), strerror(errno));
            set_guest_errno_from_host();
            return 0;
        }
        const uint32_t h = next_handle_++;
        file_map_[h] = f;
        return h;
    }

    FILE* host_file(uint32_t h) {
        auto it = file_map_.find(h);
        return it == file_map_.end() ? nullptr : it->second;
    }

    uint32_t imp_fread() {
        FILE* f = host_file(arg(3));
        if (!f) return 0;
        const size_t sz = arg(1), n = arg(2);
        std::vector<uint8_t> buf(sz * n);
        const size_t got = ::fread(buf.data(), sz, n, f);
        if (got) emu_.write(arg(0), buf.data(), got * sz);
        return static_cast<uint32_t>(got);
    }

    uint32_t imp_fwrite() {
        FILE* f = host_file(arg(3));
        if (!f) return 0;
        const size_t sz = arg(1), n = arg(2);
        std::vector<uint8_t> buf(sz * n);
        if (!buf.empty() && !emu_.read(arg(0), buf.data(), buf.size())) return 0;
        return static_cast<uint32_t>(::fwrite(buf.data(), sz, n, f));
    }

    uint32_t imp_opendir() {
        const std::string path = translate_path(guest_string(arg(0)), "opendir");
        DIR* d = ::opendir(path.c_str());
        if (!d) return 0;
        const uint32_t h = next_handle_++;
        const uint32_t buf = guest_alloc(280);   // bionic ARM32 struct dirent
        if (!buf) { ::closedir(d); return 0; }
        dir_map_[h] = {d, buf};
        return h;
    }

    uint32_t imp_readdir() {
        auto it = dir_map_.find(arg(0));
        if (it == dir_map_.end()) return 0;
        struct dirent* e = ::readdir(it->second.host);
        if (!e) return 0;

        std::vector<uint8_t> buf(280, 0);
        const uint64_t ino = static_cast<uint64_t>(e->d_ino);
        std::memcpy(&buf[0], &ino, 8);
        const uint16_t reclen = 280;
        std::memcpy(&buf[16], &reclen, 2);
        buf[18] = static_cast<uint8_t>(e->d_type);   // d_name starts at 19
        std::strncpy(reinterpret_cast<char*>(&buf[19]), e->d_name, 255);
        emu_.write(it->second.buf, buf.data(), buf.size());
        return it->second.buf;
    }

    uint32_t imp_closedir() {
        auto it = dir_map_.find(arg(0));
        if (it == dir_map_.end()) return 0;
        ::closedir(it->second.host);
        emu_.release(it->second.buf, 280);
        dir_map_.erase(it);
        return 0;
    }

    // ---- misc libc -------------------------------------------------------
    uint32_t imp_abort() {
        LOGE("guest called abort() - stopping the run here");
        emu_.reg_write(UC_ARM_REG_PC, kReturnSentinel);
        redirected_ = true;
        return 0;
    }

    uint32_t imp_assert2() {
        LOGE("guest assertion failed: %s (%s:%u)", guest_string(arg(3)).c_str(),
             guest_string(arg(0)).c_str(), emu_.read_u32(arg(1)));
        return 0;
    }

    uint32_t imp_sysconf() {
        // The guest is told about the host's real cores. A legacy engine sizes
        // its job/batching pools from these values, so a hard-coded 4 was both
        // wrong on an 8-core Dimensity and a bottleneck on a 4-core part.
        switch (static_cast<int>(arg(0))) {
            case 39: return 4096;    // _SC_PAGESIZE
            case 30: return 4096;    // _SC_PAGE_SIZE
            case 96: return static_cast<uint32_t>(zai::cpu::topology().cores_conf);
            case 97: return static_cast<uint32_t>(zai::cpu::topology().cores_online);
            default: return 1;       // a plausible "supported" answer
        }
    }

    bool is_proc_cpuinfo(const std::string& path) const {
        return path == "/proc/cpuinfo" || path == "/proc/self/cpuinfo";
    }

    // bionic ARM32 struct statfs, filled from the host's statvfs.
    uint32_t imp_statfs() {
        const std::string path = translate_path(guest_string(arg(0)), "statfs");
        const uint32_t out = arg(1);
        if (!out) return static_cast<uint32_t>(-1);

        struct statvfs vfs {};
        if (::statvfs(path.c_str(), &vfs) != 0) return static_cast<uint32_t>(-1);

        std::vector<uint8_t> buf(84, 0);   // sizeof(struct statfs) on arm32 bionic
        auto put64 = [&](size_t off, uint64_t v) { std::memcpy(&buf[off], &v, 8); };
        auto put32 = [&](size_t off, uint32_t v) { std::memcpy(&buf[off], &v, 4); };
        put64(0, vfs.f_bsize);      // f_type: value is not inspected for storage checks
        put64(8, vfs.f_bsize);
        put64(16, vfs.f_blocks);
        put64(24, vfs.f_bfree);
        put64(32, vfs.f_bavail);
        put64(40, vfs.f_files);
        put64(48, vfs.f_ffree);
        put64(64, vfs.f_namemax);
        put32(72, static_cast<uint32_t>(vfs.f_frsize));
        emu_.write(out, buf.data(), buf.size());
        return 0u;
    }

    // =====================================================================
    // EGL / GLES entry points
    //
    // EGLDisplay / EGLConfig / EGLContext / EGLSurface are 64-bit opaque
    // pointers on the host. The guest gets a 32-bit handle from the same ref
    // map JNI uses, so a guest register never has to hold a truncated pointer.
    // =====================================================================
    // ---- GLES guest-memory marshalling ------------------------------------
    //
    // The host driver only reads 64-bit process memory. Every GLES call that
    // points at a buffer is staged: the guest bytes are copied into host
    // scratch memory, the host call runs, and the scratch is dropped. Client
    // vertex arrays are re-staged at draw time, because the driver dereferences
    // those pointers only when the draw executes.
    struct ClientAttribute {
        bool set = false;
        GLint size = 4;
        GLenum type = GL_FLOAT;
        GLboolean normalized = GL_FALSE;
        GLsizei stride = 0;
        uint32_t ptr = 0;
    };

    ClientAttribute client_attributes_[16] = {};

    // OpenGL ES 1.x fixed-function vertex arrays. GLES1 has no attrib
    // locations: the engine binds one array per semantic and the driver reads
    // it at draw time, so the same guest-memory staging applies.
    enum FixedArrayKind { kVertexArray = 0, kNormalArray, kColorArray, kTexCoordArray,
                          kFixedArrayCount };
    struct FixedArray {
        bool     set = false;
        bool     enabled = false;
        GLint    size = 4;
        GLenum   type = GL_FLOAT;
        GLsizei  stride = 0;
        uint32_t ptr = 0;
        size_t   element = 0;   // bytes per element, from size*type
    };
    FixedArray fixed_arrays_[kFixedArrayCount] = {};

    GLuint bound_array_buffer_ = 0;
    GLuint bound_element_buffer_ = 0;
    uint32_t brk_base_ = 0;
    uint32_t brk_used_ = 0;
    uint32_t tm_guest_ = 0;

    static size_t gl_type_bytes(GLenum type) {
        switch (type) {
            case GL_UNSIGNED_BYTE:
            case GL_BYTE:
                return 1;
            case GL_UNSIGNED_SHORT:
            case GL_SHORT:
                return 2;
            default:
                return 4;
        }
    }

    // Components per pixel for the formats the engine uploads, times the
    // element size, with the packed 16/32-bit types handled explicitly.
    static size_t gl_pixel_bytes(GLenum format, GLenum type) {
        constexpr GLenum kUnsignedShort565 = 0x8363;
        constexpr GLenum kUnsignedShort4444 = 0x8033;
        constexpr GLenum kUnsignedShort5551 = 0x8034;
        constexpr GLenum kUnsignedInt2101010 = 0x8368;
        constexpr GLenum kUnsignedInt248 = 0x84FA;
        if (type == kUnsignedShort565 || type == kUnsignedShort4444 ||
            type == kUnsignedShort5551) {
            return 2;
        }
        if (type == kUnsignedInt2101010 || type == kUnsignedInt248) return 4;

        size_t components = 4;
        switch (format) {
            case GL_ALPHA:
            case GL_LUMINANCE:
            case GL_DEPTH_COMPONENT:
                components = 1;
                break;
            case GL_LUMINANCE_ALPHA:
                components = 2;
                break;
            case GL_RGB:
                components = 3;
                break;
            default:
                components = 4;
                break;
        }
        return components * gl_type_bytes(type);
    }

    bool stage_guest(uint32_t addr, size_t len, std::vector<uint8_t>& out) {
        out.clear();
        if (len == 0) return true;
        constexpr size_t kMaxStage = 64u * 1024u * 1024u;
        if (len > kMaxStage) {
            LOGW("GLES marshalling: %zu bytes exceeds the stage cap", len);
            return false;
        }
        out.resize(len);
        if (!emu_.read(addr, out.data(), len)) {
            out.clear();
            return false;
        }
        return true;
    }

    // Re-issues glVertexAttribPointer for every client-side attribute with a
    // host copy of the guest range the draw will touch.
    void stage_client_attributes(size_t vertex_count,
                                 std::vector<std::vector<uint8_t>>& storage) {
        auto fn = gl_proc<void (*)(GLuint, GLint, GLenum, GLboolean, GLsizei,
                                   const void*)>("glVertexAttribPointer");
        if (!fn || bound_array_buffer_ != 0) return;
        storage.reserve(16);
        for (size_t i = 0; i < 16; ++i) {
            ClientAttribute& a = client_attributes_[i];
            if (!a.set) continue;
            const size_t elem = static_cast<size_t>(a.size) * gl_type_bytes(a.type);
            const size_t stride = a.stride ? static_cast<size_t>(a.stride) : elem;
            if (elem == 0 || stride == 0) continue;
            const size_t bytes = stride * vertex_count + elem;
            storage.emplace_back();
            if (!stage_guest(a.ptr, bytes, storage.back())) {
                storage.pop_back();
                continue;
            }
            fn(static_cast<GLuint>(i), a.size, a.type, a.normalized,
               static_cast<GLsizei>(stride), storage.back().data());
        }
    }

    int host_gles_major() {
        if (gles_major_ != 0) return gles_major_;
        gles_major_ = 2;
        auto fn = gl_proc<const GLubyte* (*)(GLenum)>("glGetString");
        if (fn) {
            const char* version = reinterpret_cast<const char*>(fn(GL_VERSION));
            if (version && version[0] >= '1' && version[0] <= '3') {
                gles_major_ = version[0] - '0';
            }
        }
        LOGI("host GLES major: %d", gles_major_);
        return gles_major_;
    }

    // Binds the compositor's offscreen target when internal resolution scaling
    // is active, so the guest renders at 50-75% and eglSwapBuffers resolves it
    // to the physical display. When scaling is off the guest keeps drawing
    // straight into the window and the compositor only post-processes.
    void attach_compositor_target(EGLDisplay display, EGLSurface surface) {
        zai::GpuCompositor& compositor = zai::GpuCompositor::instance();
        compositor_bound_ = false;
        compositor_fbo_ = 0;
        compositor_scale_ = 1.0f;
        current_fbo_ = 0;
        if (!compositor.active() || !compositor.internal_scaling() ||
            surface == EGL_NO_SURFACE) {
            compositor.release_internal_target();
            return;
        }

        auto query = gl_proc<EGLBoolean (*)(EGLDisplay, EGLSurface, EGLint, EGLint*)>(
                "eglQuerySurface");
        EGLint width = 0, height = 0;
        if (!query || query(display, surface, EGL_WIDTH, &width) != EGL_TRUE ||
            query(display, surface, EGL_HEIGHT, &height) != EGL_TRUE ||
            width <= 0 || height <= 0) {
            return;
        }

        int internal_w = 0, internal_h = 0;
        const GLuint fbo = compositor.bind_internal_target(width, height,
                                                          &internal_w, &internal_h);
        if (fbo == 0) return;

        compositor_bound_ = true;
        compositor_fbo_ = fbo;
        current_fbo_ = fbo;
        compositor_scale_ = static_cast<float>(internal_w) / static_cast<float>(width);
        LOGI("compositor: guest renders into FBO %u at %dx%d (display %dx%d, %.0f%%)",
             fbo, internal_w, internal_h, width, height, compositor_scale_ * 100.0f);
    }

    void install_graphics_handlers() {
        if (!egl_lib_) {
            LOGW("graphics: host libEGL.so unavailable; EGL imports stay stubbed");
            return;
        }

        import_handlers_["eglGetDisplay"] = [this] {
            auto fn = gl_proc<EGLDisplay (*)(EGLNativeDisplayType)>("eglGetDisplay");
            if (!fn) return 0u;
            EGLDisplay d = fn(reinterpret_cast<EGLNativeDisplayType>(
                static_cast<uintptr_t>(arg(0))));
            return to_guest_ref(d);
        };

        import_handlers_["eglInitialize"] = [this] {
            auto fn = gl_proc<EGLBoolean (*)(EGLDisplay, EGLint*, EGLint*)>("eglInitialize");
            if (!fn) return 0u;
            EGLint major = 0, minor = 0;
            const EGLBoolean ok = fn(guest_display(arg(0)), &major, &minor);
            if (arg(1)) emu_.write_u32(arg(1), static_cast<uint32_t>(major));
            if (arg(2)) emu_.write_u32(arg(2), static_cast<uint32_t>(minor));
            LOGI("eglInitialize -> %d (EGL %d.%d)", ok, major, minor);
            return static_cast<uint32_t>(ok);
        };

        import_handlers_["eglTerminate"] = [this] {
            auto fn = gl_proc<EGLBoolean (*)(EGLDisplay)>("eglTerminate");
            return fn ? static_cast<uint32_t>(fn(guest_display(arg(0)))) : 0u;
        };

        import_handlers_["eglQueryString"] = [this] {
            auto fn = gl_proc<const char* (*)(EGLDisplay, EGLint)>("eglQueryString");
            if (!fn) return 0u;
            const char* s = fn(guest_display(arg(0)), static_cast<EGLint>(arg(1)));
            return s ? guest_str(s) : 0u;
        };

        import_handlers_["eglGetConfigs"] = [this] {
            auto fn = gl_proc<EGLBoolean (*)(EGLDisplay, EGLConfig*, EGLint, EGLint*)>(
                "eglGetConfigs");
            if (!fn) return 0u;
            const EGLint want = static_cast<EGLint>(arg(2));
            std::vector<EGLConfig> configs(want > 0 ? static_cast<size_t>(want) : 1);
            EGLint num = 0;
            const EGLBoolean ok = fn(guest_display(arg(0)), configs.data(), want, &num);
            if (arg(3)) emu_.write_u32(arg(3), static_cast<uint32_t>(num));
            if (arg(1) && want > 0) {
                for (EGLint i = 0; i < std::min(num, want); ++i) {
                    emu_.write_u32(arg(1) + static_cast<uint32_t>(i * 4),
                                   to_guest_ref(configs[static_cast<size_t>(i)]));
                }
            }
            return static_cast<uint32_t>(ok);
        };

        import_handlers_["eglChooseConfig"] = [this] {
            auto fn = gl_proc<EGLBoolean (*)(EGLDisplay, const EGLint*, EGLConfig*,
                                             EGLint, EGLint*)>("eglChooseConfig");
            if (!fn) return 0u;
            std::vector<EGLint> attribs = read_egl_ints(arg(1));
            const EGLint want = static_cast<EGLint>(arg(3));
            std::vector<EGLConfig> configs(want > 0 ? static_cast<size_t>(want) : 1);
            EGLint num = 0;
            int strategy = 0;
            // A guest asking for RGB565 + legacy attributes can match nothing
            // on a modern driver. choose_config() retries with RGBA8888 and a
            // real depth buffer before giving up, so the guest never sees
            // "IllegalArgumentException: No config chosen".
            const EGLBoolean ok = zai::eglc::choose_config(
                    fn, guest_display(arg(0)), attribs, configs.data(), want, &num,
                    &strategy);
            if (strategy != 0) {
                LOGI("eglChooseConfig: %s (%d configs)",
                     zai::eglc::strategy_name(strategy), num);
            }
            if (arg(4)) emu_.write_u32(arg(4), static_cast<uint32_t>(num));
            if (arg(2) && want > 0) {
                for (EGLint i = 0; i < std::min(num, want); ++i) {
                    emu_.write_u32(arg(2) + static_cast<uint32_t>(i * 4),
                                   to_guest_ref(configs[static_cast<size_t>(i)]));
                }
            }
            LOGI("eglChooseConfig -> %d (%d configs)", ok, num);
            return static_cast<uint32_t>(ok);
        };

        import_handlers_["eglGetConfigAttrib"] = [this] {
            auto fn = gl_proc<EGLBoolean (*)(EGLDisplay, EGLConfig, EGLint, EGLint*)>(
                "eglGetConfigAttrib");
            if (!fn) return 0u;
            EGLint value = 0;
            const EGLBoolean ok = fn(guest_display(arg(0)),
                                     static_cast<EGLConfig>(from_guest_ref(arg(1))),
                                     static_cast<EGLint>(arg(2)), &value);
            if (arg(3)) emu_.write_u32(arg(3), static_cast<uint32_t>(value));
            return static_cast<uint32_t>(ok);
        };

        import_handlers_["eglCreateContext"] = [this] {
            auto fn = gl_proc<EGLContext (*)(EGLDisplay, EGLConfig, EGLContext,
                                             const EGLint*)>("eglCreateContext");
            if (!fn) return 0u;
            std::vector<EGLint> attribs = read_egl_ints(arg(3));
            EGLContext ctx = fn(guest_display(arg(0)),
                                static_cast<EGLConfig>(from_guest_ref(arg(1))),
                                static_cast<EGLContext>(from_guest_ref(arg(2))),
                                attribs.data());
            LOGI("eglCreateContext -> %p", ctx);
            return to_guest_ref(ctx);
        };

        // The single most important call in the whole bridge: it binds the
        // guest's rendering to the real ANativeWindow behind the host's
        // SurfaceView, which is what makes frames visible.
        import_handlers_["eglCreateWindowSurface"] = [this] {
            auto fn = gl_proc<EGLSurface (*)(EGLDisplay, EGLConfig, EGLNativeWindowType,
                                             const EGLint*)>("eglCreateWindowSurface");
            if (!fn) return 0u;

            ANativeWindow* window = static_cast<ANativeWindow*>(from_guest_ref(arg(2)));
            if (!window) window = host_window_;
            if (!window) {
                LOGE("eglCreateWindowSurface: no host ANativeWindow; "
                     "did GameHostActivity attach its Surface?");
                return 0u;
            }

            std::vector<EGLint> attribs = read_egl_ints(arg(3));
            EGLSurface surface = fn(guest_display(arg(0)),
                                    static_cast<EGLConfig>(from_guest_ref(arg(1))),
                                    reinterpret_cast<EGLNativeWindowType>(window),
                                    attribs.data());
            if (surface) ++egl_window_surfaces_;
            LOGI("eglCreateWindowSurface(window=%p) -> %p", window, surface);
            return to_guest_ref(surface);
        };

        import_handlers_["eglCreatePbufferSurface"] = [this] {
            auto fn = gl_proc<EGLSurface (*)(EGLDisplay, EGLConfig, const EGLint*)>(
                "eglCreatePbufferSurface");
            if (!fn) return 0u;
            std::vector<EGLint> attribs = read_egl_ints(arg(2));
            EGLSurface s = fn(guest_display(arg(0)),
                              static_cast<EGLConfig>(from_guest_ref(arg(1))),
                              attribs.data());
            return to_guest_ref(s);
        };

        import_handlers_["eglMakeCurrent"] = [this] {
            auto fn = gl_proc<EGLBoolean (*)(EGLDisplay, EGLSurface, EGLSurface,
                                             EGLContext)>("eglMakeCurrent");
            if (!fn) return 0u;
            const EGLBoolean ok = fn(guest_display(arg(0)),
                                     static_cast<EGLSurface>(from_guest_ref(arg(1))),
                                     static_cast<EGLSurface>(from_guest_ref(arg(2))),
                                     static_cast<EGLContext>(from_guest_ref(arg(3))));
            LOGI("eglMakeCurrent -> %d", ok);
            if (ok) {
                attach_compositor_target(guest_display(arg(0)),
                                         static_cast<EGLSurface>(from_guest_ref(arg(1))));
            }
            return static_cast<uint32_t>(ok);
        };

        import_handlers_["eglSwapBuffers"] = [this] {
            auto fn = gl_proc<EGLBoolean (*)(EGLDisplay, EGLSurface)>("eglSwapBuffers");
            if (!fn) return 0u;
            const EGLDisplay dpy = guest_display(arg(0));
            const EGLSurface surf = static_cast<EGLSurface>(from_guest_ref(arg(1)));

            // A settings change from the in-game quick menu is applied here:
            // the internal target is re-created at the newly requested scale.
            {
                zai::GpuCompositor& comp = zai::GpuCompositor::instance();
                const bool want_internal = comp.active() && comp.internal_scaling();
                const float wanted =
                        want_internal ? comp.config().scale_percent / 100.0f : 1.0f;
                if (want_internal &&
                    (!compositor_bound_ ||
                     std::fabs(wanted - compositor_scale_) > 0.01f)) {
                    attach_compositor_target(dpy, surf);
                } else if (!want_internal && compositor_bound_) {
                    comp.release_internal_target();
                    compositor_bound_ = false;
                    compositor_fbo_ = 0;
                    compositor_scale_ = 1.0f;
                    current_fbo_ = 0;
                }
            }

            // The compositor resolves the guest frame to the physical surface
            // and, when frame generation is on, presents F_{t-0.5} first.
            zai::GpuCompositor& compositor = zai::GpuCompositor::instance();
            const bool composited = compositor.begin(dpy, surf, compositor_bound_);
            if (composited) {
                ++swap_count_;
                const int extras = compositor.synthetic_count();
                for (int i = 0; i < extras; ++i) {
                    if (!compositor.present_synthetic(i)) break;
                    fn(dpy, surf);
                    compositor.wait_subframe();
                }
                compositor.present_current();
                compositor.end_frame();
            }

            const EGLBoolean ok = fn(dpy, surf);
            if (swap_count_ <= 10 || swap_count_ % 30 == 0) {
                LOGI("eglSwapBuffers #%u -> %d%s", swap_count_, ok,
                     composited ? " (composited)" : "");
            }
            return static_cast<uint32_t>(ok);
        };

        import_handlers_["eglSwapInterval"] = [this] {
            auto fn = gl_proc<EGLBoolean (*)(EGLDisplay, EGLint)>("eglSwapInterval");
            return fn ? static_cast<uint32_t>(
                            fn(guest_display(arg(0)), static_cast<EGLint>(arg(1))))
                      : 0u;
        };

        import_handlers_["eglQuerySurface"] = [this] {
            auto fn = gl_proc<EGLBoolean (*)(EGLDisplay, EGLSurface, EGLint, EGLint*)>(
                "eglQuerySurface");
            if (!fn) return 0u;
            EGLint value = 0;
            const EGLBoolean ok = fn(guest_display(arg(0)),
                                     static_cast<EGLSurface>(from_guest_ref(arg(1))),
                                     static_cast<EGLint>(arg(2)), &value);
            if (arg(3)) emu_.write_u32(arg(3), static_cast<uint32_t>(value));
            return static_cast<uint32_t>(ok);
        };

        import_handlers_["eglSurfaceAttrib"] = [this] {
            auto fn = gl_proc<EGLBoolean (*)(EGLDisplay, EGLSurface, EGLint, EGLint)>(
                "eglSurfaceAttrib");
            return fn ? static_cast<uint32_t>(fn(guest_display(arg(0)),
                                                 static_cast<EGLSurface>(from_guest_ref(arg(1))),
                                                 static_cast<EGLint>(arg(2)),
                                                 static_cast<EGLint>(arg(3))))
                      : 0u;
        };

        import_handlers_["eglDestroySurface"] = [this] {
            auto fn = gl_proc<EGLBoolean (*)(EGLDisplay, EGLSurface)>("eglDestroySurface");
            if (!fn) return 0u;
            const EGLBoolean ok = fn(guest_display(arg(0)),
                                     static_cast<EGLSurface>(from_guest_ref(arg(1))));
            if (ok && egl_window_surfaces_) --egl_window_surfaces_;
            return static_cast<uint32_t>(ok);
        };

        import_handlers_["eglDestroyContext"] = [this] {
            auto fn = gl_proc<EGLBoolean (*)(EGLDisplay, EGLContext)>("eglDestroyContext");
            return fn ? static_cast<uint32_t>(fn(guest_display(arg(0)),
                                                 static_cast<EGLContext>(from_guest_ref(arg(1)))))
                      : 0u;
        };

        import_handlers_["eglGetError"] = [this] {
            auto fn = gl_proc<EGLint (*)()>("eglGetError");
            return fn ? static_cast<uint32_t>(fn()) : 0u;
        };

        import_handlers_["eglBindAPI"] = [this] {
            auto fn = gl_proc<EGLBoolean (*)(EGLenum)>("eglBindAPI");
            return fn ? static_cast<uint32_t>(fn(static_cast<EGLenum>(arg(0)))) : 0u;
        };

        import_handlers_["eglQueryAPI"] = [this] {
            auto fn = gl_proc<EGLenum (*)()>("eglQueryAPI");
            return fn ? static_cast<uint32_t>(fn()) : 0u;
        };

        import_handlers_["eglGetCurrentContext"] = [this] {
            auto fn = gl_proc<EGLContext (*)()>("eglGetCurrentContext");
            return fn ? to_guest_ref(fn()) : 0u;
        };
        import_handlers_["eglGetCurrentDisplay"] = [this] {
            auto fn = gl_proc<EGLDisplay (*)()>("eglGetCurrentDisplay");
            return fn ? to_guest_ref(fn()) : 0u;
        };
        import_handlers_["eglGetCurrentSurface"] = [this] {
            auto fn = gl_proc<EGLSurface (*)(EGLint)>("eglGetCurrentSurface");
            return fn ? to_guest_ref(fn(static_cast<EGLint>(arg(0)))) : 0u;
        };
        import_handlers_["eglReleaseThread"] = [] { return 0u; };
        import_handlers_["eglWaitGL"] = [] { return 1u; };
        import_handlers_["eglWaitClient"] = [] { return 1u; };

        // Resolving through the guest's own eglGetProcAddress must hand back a
        // guest-callable address, not a host pointer. A trap-page stub looks up
        // the same handler table by name.
        import_handlers_["eglGetProcAddress"] = [this] {
            const std::string name = guest_string(arg(0));
            if (name.empty()) return 0u;
            return make_stub(kStubImport, 0, name);
        };

        // ---- GLES2 surface clearing / state subset ----------------------
        // Only calls whose arguments are scalars are forwarded directly. Any
        // call that takes a guest-memory pointer (glDrawElements, glTexImage2D,
        // glBufferData, glShaderSource, ...) must be marshalled first, so it is
        // deliberately left to the report-only stub.
#define ZB_GL_V(name, sig, call)                                  \
    import_handlers_[name] = [this] {                             \
        auto fn = gl_proc<void (*) sig>(name);                    \
        if (fn) fn call;                                          \
        return 0u;                                                \
    };
#define ZB_GL_R(name, sig, call)                                  \
    import_handlers_[name] = [this] {                             \
        auto fn = gl_proc<GLenum (*) sig>(name);                  \
        return fn ? static_cast<uint32_t>(fn call) : 0u;          \
    };

        ZB_GL_V("glClearColor", (GLfloat, GLfloat, GLfloat, GLfloat),
                (f_arg(0), f_arg(1), f_arg(2), f_arg(3)));
        ZB_GL_V("glClear", (GLbitfield), (static_cast<GLbitfield>(arg(0))));
        ZB_GL_V("glEnable", (GLenum), (static_cast<GLenum>(arg(0))));
        ZB_GL_V("glDisable", (GLenum), (static_cast<GLenum>(arg(0))));
        ZB_GL_V("glBlendFunc", (GLenum, GLenum),
                (static_cast<GLenum>(arg(0)), static_cast<GLenum>(arg(1))));
        ZB_GL_V("glBlendFuncSeparate", (GLenum, GLenum, GLenum, GLenum),
                (static_cast<GLenum>(arg(0)), static_cast<GLenum>(arg(1)),
                 static_cast<GLenum>(arg(2)), static_cast<GLenum>(arg(3))));
        ZB_GL_V("glDepthFunc", (GLenum), (static_cast<GLenum>(arg(0))));
        ZB_GL_V("glDepthMask", (GLboolean), (static_cast<GLboolean>(arg(0) & 1u)));
        ZB_GL_V("glColorMask", (GLboolean, GLboolean, GLboolean, GLboolean),
                (static_cast<GLboolean>(arg(0) & 1u), static_cast<GLboolean>(arg(1) & 1u),
                 static_cast<GLboolean>(arg(2) & 1u), static_cast<GLboolean>(arg(3) & 1u)));
        ZB_GL_V("glCullFace", (GLenum), (static_cast<GLenum>(arg(0))));
        ZB_GL_V("glFrontFace", (GLenum), (static_cast<GLenum>(arg(0))));
        ZB_GL_V("glLineWidth", (GLfloat), (f_arg(0)));
        ZB_GL_V("glClearDepthf", (GLfloat), (f_arg(0)));
        ZB_GL_V("glClearStencil", (GLint), (static_cast<GLint>(arg(0))));
        ZB_GL_V("glDepthRangef", (GLfloat, GLfloat), (f_arg(0), f_arg(1)));
        ZB_GL_V("glPolygonOffset", (GLfloat, GLfloat), (f_arg(0), f_arg(1)));
        ZB_GL_V("glPixelStorei", (GLenum, GLint),
                (static_cast<GLenum>(arg(0)), static_cast<GLint>(arg(1))));
        ZB_GL_V("glUseProgram", (GLuint), (static_cast<GLuint>(arg(0))));
        ZB_GL_V("glBindBuffer", (GLenum, GLuint),
                (static_cast<GLenum>(arg(0)), static_cast<GLuint>(arg(1))));
        ZB_GL_V("glBindTexture", (GLenum, GLuint),
                (static_cast<GLenum>(arg(0)), static_cast<GLuint>(arg(1))));
        ZB_GL_V("glBindRenderbuffer", (GLenum, GLuint),
                (static_cast<GLenum>(arg(0)), static_cast<GLuint>(arg(1))));
        ZB_GL_V("glActiveTexture", (GLenum), (static_cast<GLenum>(arg(0))));
        ZB_GL_V("glDrawArrays", (GLenum, GLint, GLsizei),
                (static_cast<GLenum>(arg(0)), static_cast<GLint>(arg(1)),
                 static_cast<GLsizei>(arg(2))));
        ZB_GL_V("glTexParameteri", (GLenum, GLenum, GLint),
                (static_cast<GLenum>(arg(0)), static_cast<GLenum>(arg(1)),
                 static_cast<GLint>(arg(2))));
        ZB_GL_V("glTexParameterf", (GLenum, GLenum, GLfloat),
                (static_cast<GLenum>(arg(0)), static_cast<GLenum>(arg(1)), f_arg(2)));
        ZB_GL_V("glGenerateMipmap", (GLenum), (static_cast<GLenum>(arg(0))));
        ZB_GL_V("glEnableVertexAttribArray", (GLuint), (static_cast<GLuint>(arg(0))));
        ZB_GL_V("glDisableVertexAttribArray", (GLuint), (static_cast<GLuint>(arg(0))));
        ZB_GL_V("glStencilMask", (GLuint), (static_cast<GLuint>(arg(0))));
        ZB_GL_V("glStencilFunc", (GLenum, GLint, GLuint),
                (static_cast<GLenum>(arg(0)), static_cast<GLint>(arg(1)),
                 static_cast<GLuint>(arg(2))));
        ZB_GL_V("glStencilOp", (GLenum, GLenum, GLenum),
                (static_cast<GLenum>(arg(0)), static_cast<GLenum>(arg(1)),
                 static_cast<GLenum>(arg(2))));
        ZB_GL_V("glSampleCoverage", (GLfloat, GLboolean),
                (f_arg(0), static_cast<GLboolean>(arg(1) & 1u)));
        ZB_GL_V("glUniform1i", (GLint, GLint),
                (static_cast<GLint>(arg(0)), static_cast<GLint>(arg(1))));
        ZB_GL_V("glUniform1f", (GLint, GLfloat),
                (static_cast<GLint>(arg(0)), f_arg(1)));
        ZB_GL_V("glUniform2f", (GLint, GLfloat, GLfloat),
                (static_cast<GLint>(arg(0)), f_arg(1), f_arg(2)));
        ZB_GL_V("glUniform3f", (GLint, GLfloat, GLfloat, GLfloat),
                (static_cast<GLint>(arg(0)), f_arg(1), f_arg(2), f_arg(3)));
        ZB_GL_V("glUniform4f", (GLint, GLfloat, GLfloat, GLfloat, GLfloat),
                (static_cast<GLint>(arg(0)), f_arg(1), f_arg(2), f_arg(3),
                 stacked_f_arg(4)));
        ZB_GL_V("glUniform2i", (GLint, GLint, GLint),
                (static_cast<GLint>(arg(0)), static_cast<GLint>(arg(1)),
                 static_cast<GLint>(arg(2))));
        ZB_GL_V("glUniform3i", (GLint, GLint, GLint, GLint),
                (static_cast<GLint>(arg(0)), static_cast<GLint>(arg(1)),
                 static_cast<GLint>(arg(2)), static_cast<GLint>(arg(3))));
        ZB_GL_V("glUniform4i", (GLint, GLint, GLint, GLint, GLint),
                (static_cast<GLint>(arg(0)), static_cast<GLint>(arg(1)),
                 static_cast<GLint>(arg(2)), static_cast<GLint>(arg(3)),
                 static_cast<GLint>(stacked_arg(4))));
        ZB_GL_V("glFlush", (), ());
        ZB_GL_V("glFinish", (), ());
        ZB_GL_R("glGetError", (), ());
        ZB_GL_R("glCheckFramebufferStatus", (GLenum), (static_cast<GLenum>(arg(0))));

#undef ZB_GL_V
#undef ZB_GL_R

        // ---- compositor-aware framebuffer / viewport redirection ---------
        // While internal scaling is active the guest's "default framebuffer"
        // is the compositor's offscreen target, and every viewport/scissor it
        // sets in display coordinates is scaled into that target. Guest-owned
        // FBOs (shadow maps, render-to-texture) are left completely alone.
        import_handlers_["glBindFramebuffer"] = [this] {
            auto fn = gl_proc<void (*)(GLenum, GLuint)>("glBindFramebuffer");
            const GLenum target = static_cast<GLenum>(arg(0));
            GLuint id = static_cast<GLuint>(arg(1));
            if (compositor_bound_ && id == 0 && target == GL_FRAMEBUFFER) {
                id = compositor_fbo_;
            }
            current_fbo_ = id;
            if (fn) fn(target, id);
            return 0u;
        };

        import_handlers_["glViewport"] = [this] {
            auto fn = gl_proc<void (*)(GLint, GLint, GLint, GLint)>("glViewport");
            GLint x = static_cast<GLint>(arg(0));
            GLint y = static_cast<GLint>(arg(1));
            GLint w = static_cast<GLint>(arg(2));
            GLint h = static_cast<GLint>(arg(3));
            if (compositor_bound_ && current_fbo_ == compositor_fbo_) {
                x = static_cast<GLint>(x * compositor_scale_);
                y = static_cast<GLint>(y * compositor_scale_);
                w = static_cast<GLint>(w * compositor_scale_);
                h = static_cast<GLint>(h * compositor_scale_);
            }
            if (fn) fn(x, y, w, h);
            return 0u;
        };

        import_handlers_["glScissor"] = [this] {
            auto fn = gl_proc<void (*)(GLint, GLint, GLint, GLint)>("glScissor");
            GLint x = static_cast<GLint>(arg(0));
            GLint y = static_cast<GLint>(arg(1));
            GLint w = static_cast<GLint>(arg(2));
            GLint h = static_cast<GLint>(arg(3));
            if (compositor_bound_ && current_fbo_ == compositor_fbo_) {
                x = static_cast<GLint>(x * compositor_scale_);
                y = static_cast<GLint>(y * compositor_scale_);
                w = static_cast<GLint>(w * compositor_scale_);
                h = static_cast<GLint>(h * compositor_scale_);
            }
            if (fn) fn(x, y, w, h);
            return 0u;
        };

        // Strings come from the host driver as 64-bit pointers; copy them into
        // guest memory instead of truncating them into R0.
        import_handlers_["glGetString"] = [this] {
            auto fn = gl_proc<const GLubyte* (*)(GLenum)>("glGetString");
            if (!fn) return 0u;
            const GLenum name = static_cast<GLenum>(arg(0));
            const char* s = reinterpret_cast<const char*>(fn(name));
            if (!s) return 0u;
            // Expose the standard OES/EXT set a legacy title probes for, even
            // when a vendor driver leaves it out of the ES2 extension string.
            if (name == GL_EXTENSIONS) {
                const std::string merged = zai::glsl::merge_extensions(s);
                return guest_str(merged);
            }
            return guest_str(s);
        };
        import_handlers_["glGetStringi"] = [this] {
            auto fn = gl_proc<const GLubyte* (*)(GLenum, GLuint)>("glGetStringi");
            if (!fn) return 0u;
            const char* s = reinterpret_cast<const char*>(
                    fn(static_cast<GLenum>(arg(0)), static_cast<GLuint>(arg(1))));
            return s ? guest_str(s) : 0u;
        };

        import_handlers_["glGetIntegerv"] = [this] {
            auto fn = gl_proc<void (*)(GLenum, GLint*)>("glGetIntegerv");
            const uint32_t out = arg(1);
            if (!fn || !out) return 0u;
            GLint values[16] = {0};
            fn(static_cast<GLenum>(arg(0)), values);
            size_t count = 1;
            switch (static_cast<GLenum>(arg(0))) {
                case GL_VIEWPORT:
                case GL_SCISSOR_BOX:
                case GL_COLOR_CLEAR_VALUE:
                    count = 4;
                    break;
                case GL_MAX_VIEWPORT_DIMS:
                    count = 2;
                    break;
                default:
                    break;
            }
            emu_.write(out, values, count * sizeof(GLint));
            return 0u;
        };

        import_handlers_["glGetFloatv"] = [this] {
            auto fn = gl_proc<void (*)(GLenum, GLfloat*)>("glGetFloatv");
            const uint32_t out = arg(1);
            if (!fn || !out) return 0u;
            GLfloat values[16] = {0};
            fn(static_cast<GLenum>(arg(0)), values);
            emu_.write(out, values, sizeof(GLfloat));
            return 0u;
        };

        // ---- shader / program pipeline --------------------------------
        import_handlers_["glCreateShader"] = [this] {
            auto fn = gl_proc<GLuint (*)(GLenum)>("glCreateShader");
            return fn ? static_cast<uint32_t>(fn(static_cast<GLenum>(arg(0)))) : 0u;
        };
        import_handlers_["glCreateProgram"] = [this] {
            auto fn = gl_proc<GLuint (*)()>("glCreateProgram");
            return fn ? static_cast<uint32_t>(fn()) : 0u;
        };

        auto one_uint = [this](const char* name) {
            import_handlers_[name] = [this, name] {
                auto fn = gl_proc<void (*)(GLuint)>(name);
                if (fn) fn(static_cast<GLuint>(arg(0)));
                return 0u;
            };
        };
        one_uint("glCompileShader");
        one_uint("glLinkProgram");
        one_uint("glValidateProgram");
        one_uint("glDeleteShader");
        one_uint("glDeleteProgram");

        auto two_uints = [this](const char* name) {
            import_handlers_[name] = [this, name] {
                auto fn = gl_proc<void (*)(GLuint, GLuint)>(name);
                if (fn) fn(static_cast<GLuint>(arg(0)), static_cast<GLuint>(arg(1)));
                return 0u;
            };
        };
        two_uints("glAttachShader");
        two_uints("glDetachShader");

        auto get_iv = [this](const char* name) {
            import_handlers_[name] = [this, name] {
                auto fn = gl_proc<void (*)(GLuint, GLenum, GLint*)>(name);
                const uint32_t out = arg(2);
                if (!fn || !out) return 0u;
                GLint value = 0;
                fn(static_cast<GLuint>(arg(0)), static_cast<GLenum>(arg(1)), &value);
                emu_.write(out, &value, sizeof value);
                return 0u;
            };
        };
        get_iv("glGetShaderiv");
        get_iv("glGetProgramiv");

        auto info_log = [this](const char* name) {
            import_handlers_[name] = [this, name] {
                auto fn = gl_proc<void (*)(GLuint, GLsizei, GLsizei*, GLchar*)>(name);
                const GLsizei capacity = static_cast<GLsizei>(arg(1));
                const uint32_t length_out = arg(2);
                const uint32_t buffer = arg(3);
                if (!fn || !buffer) return 0u;
                std::vector<char> text(capacity > 0 ? static_cast<size_t>(capacity) : 1u, '\0');
                GLsizei written = 0;
                fn(static_cast<GLuint>(arg(0)), capacity, &written, text.data());
                if (written < 0) written = 0;
                if (written > capacity) written = capacity;
                emu_.write(buffer, text.data(), static_cast<size_t>(written));
                if (length_out) emu_.write(length_out, &written, sizeof written);
                return 0u;
            };
        };
        info_log("glGetShaderInfoLog");
        info_log("glGetProgramInfoLog");

        import_handlers_["glShaderSource"] = [this] {
            auto fn = gl_proc<void (*)(GLuint, GLsizei, const GLchar* const*,
                                       const GLint*)>("glShaderSource");
            if (!fn) return 0u;
            const GLsizei count = static_cast<GLsizei>(arg(1));
            if (count <= 0 || count > 64) return 0u;
            std::vector<uint32_t> pointers(static_cast<size_t>(count), 0);
            if (!emu_.read(arg(2), pointers.data(), pointers.size() * sizeof(uint32_t))) {
                return 0u;
            }
            std::vector<std::vector<char>> storage(static_cast<size_t>(count));
            std::vector<const GLchar*> strings(static_cast<size_t>(count), nullptr);
            for (GLsizei i = 0; i < count; ++i) {
                const size_t slot = static_cast<size_t>(i);
                int32_t declared = -1;
                if (arg(3)) {
                    emu_.read(arg(3) + static_cast<uint32_t>(slot) * 4, &declared, 4);
                }
                if (declared <= 0) {
                    const std::string s = emu_.read_cstr(pointers[slot], 1u << 20);
                    storage[slot].assign(s.begin(), s.end());
                } else {
                    storage[slot].resize(static_cast<size_t>(declared));
                    emu_.read(pointers[slot], storage[slot].data(),
                              static_cast<size_t>(declared));
                }
                strings[slot] = storage[slot].data();
            }
            // Normalise before the driver sees it: missing precision header,
            // desktop #version, legacy syntax on a strict ES3 context.
            std::string combined;
            for (GLsizei i = 0; i < count; ++i) {
                combined.append(storage[static_cast<size_t>(i)].data(),
                                storage[static_cast<size_t>(i)].size());
            }
            zai::glsl::Stage stage = zai::glsl::Stage::Fragment;
            auto type_fn = gl_proc<void (*)(GLuint, GLenum, GLint*)>("glGetShaderiv");
            if (type_fn) {
                GLint shader_type = 0x8B30;   // GL_FRAGMENT_SHADER
                type_fn(static_cast<GLuint>(arg(0)), 0x8B4F, &shader_type);  // GL_SHADER_TYPE
                if (shader_type == 0x8B31) stage = zai::glsl::Stage::Vertex;
            }
            const zai::glsl::Patch patch = zai::glsl::patch_shader_source(
                    combined, stage, host_gles_major());
            const GLchar* text = patch.source.c_str();
            fn(static_cast<GLuint>(arg(0)), 1, &text, nullptr);
            return 0u;
        };

        import_handlers_["glBindAttribLocation"] = [this] {
            auto fn = gl_proc<void (*)(GLuint, GLuint, const GLchar*)>(
                    "glBindAttribLocation");
            if (!fn) return 0u;
            const std::string name = guest_string(arg(2));
            fn(static_cast<GLuint>(arg(0)), static_cast<GLuint>(arg(1)), name.c_str());
            return 0u;
        };

        auto get_location = [this](const char* name) {
            import_handlers_[name] = [this, name] {
                auto fn = gl_proc<GLint (*)(GLuint, const GLchar*)>(name);
                if (!fn) return 0u;
                const std::string symbol = guest_string(arg(1));
                return static_cast<uint32_t>(fn(static_cast<GLuint>(arg(0)),
                                                 symbol.c_str()));
            };
        };
        get_location("glGetUniformLocation");
        get_location("glGetAttribLocation");

        import_handlers_["glGetShaderSource"] = [this] {
            auto fn = gl_proc<void (*)(GLuint, GLsizei, GLsizei*, GLchar*)>(
                    "glGetShaderSource");
            const GLsizei capacity = static_cast<GLsizei>(arg(1));
            const uint32_t length_out = arg(2);
            const uint32_t buffer = arg(3);
            if (!fn || !buffer) return 0u;
            std::vector<char> text(capacity > 0 ? static_cast<size_t>(capacity) : 1u, '\0');
            GLsizei written = 0;
            fn(static_cast<GLuint>(arg(0)), capacity, &written, text.data());
            if (written < 0) written = 0;
            if (written > capacity) written = capacity;
            emu_.write(buffer, text.data(), static_cast<size_t>(written));
            if (length_out) emu_.write(length_out, &written, sizeof written);
            return 0u;
        };

        // ---- buffer objects ------------------------------------------
        import_handlers_["glBindBuffer"] = [this] {
            auto fn = gl_proc<void (*)(GLenum, GLuint)>("glBindBuffer");
            const GLenum target = static_cast<GLenum>(arg(0));
            const GLuint id = static_cast<GLuint>(arg(1));
            if (target == GL_ARRAY_BUFFER) bound_array_buffer_ = id;
            if (target == GL_ELEMENT_ARRAY_BUFFER) bound_element_buffer_ = id;
            if (fn) fn(target, id);
            return 0u;
        };

        import_handlers_["glBufferData"] = [this] {
            auto fn = gl_proc<void (*)(GLenum, GLsizeiptr, const void*, GLenum)>(
                    "glBufferData");
            const GLsizeiptr size = static_cast<GLsizeiptr>(arg(1));
            std::vector<uint8_t> staged;
            if (fn && size > 0 && arg(2) &&
                stage_guest(arg(2), static_cast<size_t>(size), staged)) {
                fn(static_cast<GLenum>(arg(0)), size, staged.data(),
                   static_cast<GLenum>(arg(3)));
            } else if (fn) {
                fn(static_cast<GLenum>(arg(0)), size, nullptr, static_cast<GLenum>(arg(3)));
            }
            return 0u;
        };

        import_handlers_["glBufferSubData"] = [this] {
            auto fn = gl_proc<void (*)(GLenum, GLintptr, GLsizeiptr, const void*)>(
                    "glBufferSubData");
            const GLsizeiptr size = static_cast<GLsizeiptr>(arg(2));
            std::vector<uint8_t> staged;
            if (fn && size > 0 && stage_guest(arg(3), static_cast<size_t>(size), staged)) {
                fn(static_cast<GLenum>(arg(0)), static_cast<GLintptr>(arg(1)), size,
                   staged.data());
            }
            return 0u;
        };

        auto gen_names = [this](const char* name) {
            import_handlers_[name] = [this, name] {
                auto fn = gl_proc<void (*)(GLsizei, GLuint*)>(name);
                const GLsizei count = static_cast<GLsizei>(arg(0));
                const uint32_t out = arg(1);
                if (!fn || !out || count <= 0 || count > 1024) return 0u;
                std::vector<GLuint> ids(static_cast<size_t>(count), 0);
                fn(count, ids.data());
                emu_.write(out, ids.data(), ids.size() * sizeof(GLuint));
                return 0u;
            };
        };
        gen_names("glGenTextures");
        gen_names("glGenBuffers");
        gen_names("glGenFramebuffers");
        gen_names("glGenRenderbuffers");

        auto delete_names = [this](const char* name) {
            import_handlers_[name] = [this, name] {
                auto fn = gl_proc<void (*)(GLsizei, const GLuint*)>(name);
                const GLsizei count = static_cast<GLsizei>(arg(0));
                if (!fn || count <= 0 || count > 4096) return 0u;
                std::vector<GLuint> ids(static_cast<size_t>(count), 0);
                if (!emu_.read(arg(1), ids.data(), ids.size() * sizeof(GLuint))) return 0u;
                fn(count, ids.data());
                return 0u;
            };
        };
        delete_names("glDeleteTextures");
        delete_names("glDeleteBuffers");
        delete_names("glDeleteFramebuffers");
        delete_names("glDeleteRenderbuffers");

        // ---- textures -------------------------------------------------
        import_handlers_["glTexImage2D"] = [this] {
            auto fn = gl_proc<void (*)(GLenum, GLint, GLint, GLsizei, GLsizei, GLint,
                                       GLenum, GLenum, const void*)>("glTexImage2D");
            if (!fn) return 0u;
            // (target, level, internalformat, width, height, border, format, type, pixels)
            const GLsizei width = static_cast<GLsizei>(arg(3));
            const GLsizei height = static_cast<GLsizei>(stacked_arg(4));
            const GLint border = static_cast<GLint>(stacked_arg(5));
            const GLenum format = static_cast<GLenum>(stacked_arg(6));
            const GLenum type = static_cast<GLenum>(stacked_arg(7));
            const uint32_t pixels = stacked_arg(8);
            std::vector<uint8_t> staged;
            if (pixels && width > 0 && height > 0) {
                stage_guest(pixels, static_cast<size_t>(width) *
                                             static_cast<size_t>(height) *
                                             gl_pixel_bytes(format, type),
                             staged);
            }
            fn(static_cast<GLenum>(arg(0)), static_cast<GLint>(arg(1)),
               static_cast<GLint>(arg(2)), width, height, border, format, type,
               staged.empty() ? nullptr : staged.data());
            return 0u;
        };

        import_handlers_["glTexSubImage2D"] = [this] {
            auto fn = gl_proc<void (*)(GLenum, GLint, GLint, GLint, GLsizei, GLsizei,
                                       GLenum, GLenum, const void*)>("glTexSubImage2D");
            if (!fn) return 0u;
            const GLsizei width = static_cast<GLsizei>(stacked_arg(4));
            const GLsizei height = static_cast<GLsizei>(stacked_arg(5));
            const GLenum format = static_cast<GLenum>(stacked_arg(6));
            const GLenum type = static_cast<GLenum>(stacked_arg(7));
            const uint32_t pixels = stacked_arg(8);
            std::vector<uint8_t> staged;
            if (pixels && width > 0 && height > 0) {
                stage_guest(pixels, static_cast<size_t>(width) *
                                             static_cast<size_t>(height) *
                                             gl_pixel_bytes(format, type),
                             staged);
            }
            fn(static_cast<GLenum>(arg(0)), static_cast<GLint>(arg(1)),
               static_cast<GLint>(arg(2)), static_cast<GLint>(arg(3)), width, height,
               format, type, staged.empty() ? nullptr : staged.data());
            return 0u;
        };

        import_handlers_["glTexParameterfv"] = [this] {
            auto fn = gl_proc<void (*)(GLenum, GLenum, const GLfloat*)>("glTexParameterfv");
            std::vector<uint8_t> staged;
            if (fn && stage_guest(arg(2), 4 * sizeof(GLfloat), staged)) {
                fn(static_cast<GLenum>(arg(0)), static_cast<GLenum>(arg(1)),
                   reinterpret_cast<const GLfloat*>(staged.data()));
            }
            return 0u;
        };

        import_handlers_["glReadPixels"] = [this] {
            auto fn = gl_proc<void (*)(GLint, GLint, GLsizei, GLsizei, GLenum, GLenum,
                                       void*)>("glReadPixels");
            const GLsizei width = static_cast<GLsizei>(arg(2));
            const GLsizei height = static_cast<GLsizei>(arg(3));
            const GLenum format = static_cast<GLenum>(stacked_arg(4));
            const GLenum type = static_cast<GLenum>(stacked_arg(5));
            const uint32_t pixels = stacked_arg(6);
            if (!fn || !pixels || width <= 0 || height <= 0) return 0u;
            const size_t bytes = static_cast<size_t>(width) * static_cast<size_t>(height) *
                                 gl_pixel_bytes(format, type);
            std::vector<uint8_t> staged(bytes, 0);
            fn(static_cast<GLint>(arg(0)), static_cast<GLint>(arg(1)), width, height,
               format, type, staged.data());
            emu_.write(pixels, staged.data(), bytes);
            return 0u;
        };

        // ---- vertex attributes and draws -------------------------------
        import_handlers_["glVertexAttribPointer"] = [this] {
            const GLuint index = static_cast<GLuint>(arg(0));
            if (index >= 16) return 0u;
            ClientAttribute& a = client_attributes_[index];
            a.size = static_cast<GLint>(arg(1));
            a.type = static_cast<GLenum>(arg(2));
            a.normalized = static_cast<GLboolean>(arg(3) & 1u);
            a.stride = static_cast<GLsizei>(stacked_arg(4));
            a.ptr = stacked_arg(5);
            a.set = bound_array_buffer_ == 0;
            if (bound_array_buffer_ != 0) {
                auto fn = gl_proc<void (*)(GLuint, GLint, GLenum, GLboolean, GLsizei,
                                           const void*)>("glVertexAttribPointer");
                if (fn) fn(index, a.size, a.type, a.normalized, a.stride,
                           reinterpret_cast<const void*>(
                                   static_cast<uintptr_t>(a.ptr)));
            }
            return 0u;
        };

        auto attrib_scalar = [this](const char* name, int components) {
            import_handlers_[name] = [this, name, components] {
                auto fn = gl_proc<void (*)(GLuint, GLfloat, GLfloat, GLfloat, GLfloat)>(name);
                if (!fn) return 0u;
                GLfloat v[4] = {f_arg(1), f_arg(2), f_arg(3), stacked_f_arg(4)};
                fn(static_cast<GLuint>(arg(0)), v[0], v[1], v[2], v[3]);
                (void)components;
                return 0u;
            };
        };
        attrib_scalar("glVertexAttrib1f", 1);
        attrib_scalar("glVertexAttrib2f", 2);
        attrib_scalar("glVertexAttrib3f", 3);

        import_handlers_["glVertexAttrib4fv"] = [this] {
            auto fn = gl_proc<void (*)(GLuint, const GLfloat*)>("glVertexAttrib4fv");
            std::vector<uint8_t> staged;
            if (fn && stage_guest(arg(1), 4 * sizeof(GLfloat), staged)) {
                fn(static_cast<GLuint>(arg(0)),
                   reinterpret_cast<const GLfloat*>(staged.data()));
            }
            return 0u;
        };
        import_handlers_["glVertexAttrib2fv"] = [this] {
            auto fn = gl_proc<void (*)(GLuint, const GLfloat*)>("glVertexAttrib2fv");
            std::vector<uint8_t> staged;
            if (fn && stage_guest(arg(1), 2 * sizeof(GLfloat), staged)) {
                fn(static_cast<GLuint>(arg(0)),
                   reinterpret_cast<const GLfloat*>(staged.data()));
            }
            return 0u;
        };
        import_handlers_["glVertexAttrib3fv"] = [this] {
            auto fn = gl_proc<void (*)(GLuint, const GLfloat*)>("glVertexAttrib3fv");
            std::vector<uint8_t> staged;
            if (fn && stage_guest(arg(1), 3 * sizeof(GLfloat), staged)) {
                fn(static_cast<GLuint>(arg(0)),
                   reinterpret_cast<const GLfloat*>(staged.data()));
            }
            return 0u;
        };

        import_handlers_["glDrawArrays"] = [this] {
            auto fn = gl_proc<void (*)(GLenum, GLint, GLsizei)>("glDrawArrays");
            if (!fn) return 0u;
            const GLint first = static_cast<GLint>(arg(1));
            const GLsizei count = static_cast<GLsizei>(arg(2));
            std::vector<std::vector<uint8_t>> storage;
            if (count > 0) {
                stage_client_attributes(static_cast<size_t>(first) +
                                        static_cast<size_t>(count), storage);
                stage_fixed_arrays(static_cast<size_t>(first) +
                                   static_cast<size_t>(count), storage);
            }
            fn(static_cast<GLenum>(arg(0)), first, count);
            return 0u;
        };

        import_handlers_["glDrawElements"] = [this] {
            auto fn = gl_proc<void (*)(GLenum, GLsizei, GLenum, const void*)>(
                    "glDrawElements");
            if (!fn) return 0u;
            const GLsizei count = static_cast<GLsizei>(arg(1));
            const GLenum type = static_cast<GLenum>(arg(2));
            const uint32_t indices = arg(3);
            if (count <= 0) return 0u;

            if (bound_element_buffer_ != 0) {
                fn(static_cast<GLenum>(arg(0)), count, type,
                   reinterpret_cast<const void*>(static_cast<uintptr_t>(indices)));
                return 0u;
            }

            const size_t index_bytes = static_cast<size_t>(count) * gl_type_bytes(type);
            std::vector<uint8_t> staged_index;
            if (!stage_guest(indices, index_bytes, staged_index)) {
                LOGW("glDrawElements: indices %08x not readable", indices);
                return 0u;
            }

            // The largest index determines how much vertex data the driver
            // will touch, so stage exactly that range.
            uint32_t max_index = 0;
            for (GLsizei i = 0; i < count; ++i) {
                uint32_t v = 0;
                if (type == GL_UNSIGNED_BYTE) {
                    v = staged_index[static_cast<size_t>(i)];
                } else if (type == GL_UNSIGNED_SHORT) {
                    v = reinterpret_cast<const uint16_t*>(staged_index.data())[
                            static_cast<size_t>(i)];
                } else {
                    v = reinterpret_cast<const uint32_t*>(staged_index.data())[
                            static_cast<size_t>(i)];
                }
                if (v > max_index) max_index = v;
            }

            std::vector<std::vector<uint8_t>> storage;
            stage_client_attributes(static_cast<size_t>(max_index) + 1, storage);
            stage_fixed_arrays(static_cast<size_t>(max_index) + 1, storage);
            fn(static_cast<GLenum>(arg(0)), count, type, staged_index.data());
            return 0u;
        };

        // ---- uniform arrays -------------------------------------------
        auto uniform_floats = [this](const char* name, int components) {
            import_handlers_[name] = [this, name, components] {
                auto fn = gl_proc<void (*)(GLint, GLsizei, const GLfloat*)>(name);
                const GLsizei count = static_cast<GLsizei>(arg(1));
                std::vector<uint8_t> staged;
                if (fn && count > 0 &&
                    stage_guest(arg(2), static_cast<size_t>(count) * components *
                                                 sizeof(GLfloat), staged)) {
                    fn(static_cast<GLint>(arg(0)), count,
                       reinterpret_cast<const GLfloat*>(staged.data()));
                }
                return 0u;
            };
        };
        uniform_floats("glUniform1fv", 1);
        uniform_floats("glUniform2fv", 2);
        uniform_floats("glUniform3fv", 3);
        uniform_floats("glUniform4fv", 4);

        auto uniform_matrix = [this](const char* name, int order) {
            import_handlers_[name] = [this, name, order] {
                auto fn = gl_proc<void (*)(GLint, GLsizei, GLboolean, const GLfloat*)>(name);
                const GLsizei count = static_cast<GLsizei>(arg(1));
                const size_t floats = static_cast<size_t>(order) * order;
                std::vector<uint8_t> staged;
                if (fn && count > 0 &&
                    stage_guest(arg(3), static_cast<size_t>(count) * floats *
                                                 sizeof(GLfloat), staged)) {
                    fn(static_cast<GLint>(arg(0)), count,
                       static_cast<GLboolean>(arg(2) & 1u),
                       reinterpret_cast<const GLfloat*>(staged.data()));
                }
                return 0u;
            };
        };
        uniform_matrix("glUniformMatrix2fv", 2);
        uniform_matrix("glUniformMatrix3fv", 3);
        uniform_matrix("glUniformMatrix4fv", 4);

        auto uniform_ints = [this](const char* name, int components) {
            import_handlers_[name] = [this, name, components] {
                auto fn = gl_proc<void (*)(GLint, GLsizei, const GLint*)>(name);
                const GLsizei count = static_cast<GLsizei>(arg(1));
                std::vector<uint8_t> staged;
                if (fn && count > 0 &&
                    stage_guest(arg(2), static_cast<size_t>(count) * components *
                                                 sizeof(GLint), staged)) {
                    fn(static_cast<GLint>(arg(0)), count,
                       reinterpret_cast<const GLint*>(staged.data()));
                }
                return 0u;
            };
        };
        uniform_ints("glUniform2iv", 2);
        uniform_ints("glUniform3iv", 3);
        uniform_ints("glUniform4iv", 4);

        // ---- misc state ------------------------------------------------
        import_handlers_["glStencilOpSeparate"] = [this] {
            auto fn = gl_proc<void (*)(GLenum, GLenum, GLenum, GLenum)>(
                    "glStencilOpSeparate");
            if (fn) {
                fn(static_cast<GLenum>(arg(0)), static_cast<GLenum>(arg(1)),
                   static_cast<GLenum>(arg(2)), static_cast<GLenum>(arg(3)));
            }
            return 0u;
        };
        import_handlers_["glBlendEquation"] = [this] {
            auto fn = gl_proc<void (*)(GLenum)>("glBlendEquation");
            if (fn) fn(static_cast<GLenum>(arg(0)));
            return 0u;
        };
        import_handlers_["glBlendEquationSeparate"] = [this] {
            auto fn = gl_proc<void (*)(GLenum, GLenum)>("glBlendEquationSeparate");
            if (fn) fn(static_cast<GLenum>(arg(0)), static_cast<GLenum>(arg(1)));
            return 0u;
        };
        import_handlers_["glRenderbufferStorage"] = [this] {
            auto fn = gl_proc<void (*)(GLenum, GLenum, GLsizei, GLsizei)>(
                    "glRenderbufferStorage");
            if (fn) {
                fn(static_cast<GLenum>(arg(0)), static_cast<GLenum>(arg(1)),
                   static_cast<GLsizei>(arg(2)), static_cast<GLsizei>(arg(3)));
            }
            return 0u;
        };
        import_handlers_["glFramebufferTexture2D"] = [this] {
            auto fn = gl_proc<void (*)(GLenum, GLenum, GLenum, GLuint, GLint)>(
                    "glFramebufferTexture2D");
            if (fn) {
                fn(static_cast<GLenum>(arg(0)), static_cast<GLenum>(arg(1)),
                   static_cast<GLenum>(arg(2)), static_cast<GLuint>(arg(3)),
                   static_cast<GLint>(stacked_arg(4)));
            }
            return 0u;
        };
        import_handlers_["glFramebufferRenderbuffer"] = [this] {
            auto fn = gl_proc<void (*)(GLenum, GLenum, GLenum, GLuint)>(
                    "glFramebufferRenderbuffer");
            if (fn) {
                fn(static_cast<GLenum>(arg(0)), static_cast<GLenum>(arg(1)),
                   static_cast<GLenum>(arg(2)), static_cast<GLuint>(arg(3)));
            }
            return 0u;
        };
        auto is_query = [this](const char* name) {
            import_handlers_[name] = [this, name] {
                auto fn = gl_proc<GLboolean (*)(GLuint)>(name);
                return fn ? static_cast<uint32_t>(fn(static_cast<GLuint>(arg(0)))) : 0u;
            };
        };
        is_query("glIsFramebuffer");
        is_query("glIsRenderbuffer");
        is_query("glIsTexture");
        is_query("glIsBuffer");
        is_query("glIsProgram");
        is_query("glIsShader");
        is_query("glIsEnabled");

        // The GLES2 block undefines these; the fixed-function block below needs
        // the same forwarding shapes, so they are declared again here.
#define ZB_GL_V(name, sig, call)                                  \
    import_handlers_[name] = [this] {                             \
        auto fn = gl_proc<void (*) sig>(name);                    \
        if (fn) fn call;                                          \
        return 0u;                                                \
    };
#define ZB_GL_R(name, sig, call)                                  \
    import_handlers_[name] = [this] {                             \
        auto fn = gl_proc<GLenum (*) sig>(name);                  \
        return fn ? static_cast<uint32_t>(fn call) : 0u;          \
    };

        // ---- GLES 1.x fixed-function pipeline ----------------------------
        // A legacy engine never calls glVertexAttribPointer: it fills host-visible
        // structs in guest memory, points glVertexPointer / glTexCoordPointer at
        // them, and draws. The pointer is a 32-bit guest address and can never be
        // handed to the driver, so the arrays are recorded here and staged into
        // host buffers immediately before each draw.
        auto fixed_array = [this](const char* name, FixedArrayKind kind, int default_size) {
            import_handlers_[name] = [this, name, kind, default_size] {
                FixedArray& a = fixed_arrays_[kind];
                a.set = true;
                a.size = default_size > 0 ? static_cast<GLint>(arg(0)) : 1;
                a.type = static_cast<GLenum>(arg(default_size > 0 ? 1 : 0));
                a.stride = static_cast<GLsizei>(arg(default_size > 0 ? 2 : 1));
                a.ptr = arg(default_size > 0 ? 3 : 2);
                a.element = static_cast<size_t>(a.size) * gl_type_bytes(a.type);
                return 0u;
            };
        };
        fixed_array("glVertexPointer", kVertexArray, 3);
        fixed_array("glColorPointer", kColorArray, 4);
        fixed_array("glTexCoordPointer", kTexCoordArray, 4);
        fixed_array("glNormalPointer", kNormalArray, 0);

        // The enable flag has to reach the driver: it is the driver that
        // decides which arrays a draw consumes, and a draw issued with no
        // enabled array is discarded with "no data bound to the command".
        import_handlers_["glEnableClientState"] = [this] {
            const GLenum state = static_cast<GLenum>(arg(0));
            const int kind = fixed_kind(state);
            if (kind >= 0) fixed_arrays_[kind].enabled = true;
            auto fn = gl_proc<void (*)(GLenum)>("glEnableClientState");
            if (fn) fn(state);
            return 0u;
        };
        import_handlers_["glDisableClientState"] = [this] {
            const GLenum state = static_cast<GLenum>(arg(0));
            const int kind = fixed_kind(state);
            if (kind >= 0) fixed_arrays_[kind].enabled = false;
            auto fn = gl_proc<void (*)(GLenum)>("glDisableClientState");
            if (fn) fn(state);
            return 0u;
        };

        // Matrix stack: the uniforms live in guest memory but the driver keeps
        // its own state, so every one of these is forwarded as scalars.
        ZB_GL_V("glMatrixMode", (GLenum), (static_cast<GLenum>(arg(0))));
        ZB_GL_V("glLoadIdentity", (), ());
        ZB_GL_V("glPushMatrix", (), ());
        ZB_GL_V("glPopMatrix", (), ());
        ZB_GL_V("glTranslatef", (GLfloat, GLfloat, GLfloat),
                (f_arg(0), f_arg(1), f_arg(2)));
        ZB_GL_V("glRotatef", (GLfloat, GLfloat, GLfloat, GLfloat),
                (f_arg(0), f_arg(1), f_arg(2), f_arg(3)));
        ZB_GL_V("glScalef", (GLfloat, GLfloat, GLfloat),
                (f_arg(0), f_arg(1), f_arg(2)));
        ZB_GL_V("glOrthof", (GLfloat, GLfloat, GLfloat, GLfloat, GLfloat, GLfloat),
                (f_arg(0), f_arg(1), f_arg(2), f_arg(3), f_arg(4), f_arg(5)));
        ZB_GL_V("glFrustumf", (GLfloat, GLfloat, GLfloat, GLfloat, GLfloat, GLfloat),
                (f_arg(0), f_arg(1), f_arg(2), f_arg(3), f_arg(4), f_arg(5)));
        ZB_GL_V("glShadeModel", (GLenum), (static_cast<GLenum>(arg(0))));
        ZB_GL_V("glAlphaFunc", (GLenum, GLfloat),
                (static_cast<GLenum>(arg(0)), f_arg(1)));
        ZB_GL_V("glTexEnvi", (GLenum, GLenum, GLint),
                (static_cast<GLenum>(arg(0)), static_cast<GLenum>(arg(1)),
                 static_cast<GLint>(arg(2))));
        ZB_GL_V("glTexEnvf", (GLenum, GLenum, GLfloat),
                (static_cast<GLenum>(arg(0)), static_cast<GLenum>(arg(1)), f_arg(2)));
        ZB_GL_V("glColor4f", (GLfloat, GLfloat, GLfloat, GLfloat),
                (f_arg(0), f_arg(1), f_arg(2), f_arg(3)));
        ZB_GL_V("glColor4ub", (GLubyte, GLubyte, GLubyte, GLubyte),
                (static_cast<GLubyte>(arg(0)), static_cast<GLubyte>(arg(1)),
                 static_cast<GLubyte>(arg(2)), static_cast<GLubyte>(arg(3))));
        ZB_GL_V("glNormal3f", (GLfloat, GLfloat, GLfloat),
                (f_arg(0), f_arg(1), f_arg(2)));
        ZB_GL_V("glPointSize", (GLfloat), (f_arg(0)));
        ZB_GL_V("glClientActiveTexture", (GLenum), (static_cast<GLenum>(arg(0))));

        import_handlers_["glLoadMatrixf"] = [this] {
            auto fn = gl_proc<void (*)(const GLfloat*)>("glLoadMatrixf");
            std::vector<uint8_t> staged;
            if (fn && stage_guest(arg(0), 16 * sizeof(GLfloat), staged)) {
                fn(reinterpret_cast<const GLfloat*>(staged.data()));
            }
            return 0u;
        };
        import_handlers_["glMultMatrixf"] = [this] {
            auto fn = gl_proc<void (*)(const GLfloat*)>("glMultMatrixf");
            std::vector<uint8_t> staged;
            if (fn && stage_guest(arg(0), 16 * sizeof(GLfloat), staged)) {
                fn(reinterpret_cast<const GLfloat*>(staged.data()));
            }
            return 0u;
        };
        import_handlers_["glMaterialfv"] = [this] {
            auto fn = gl_proc<void (*)(GLenum, GLenum, const GLfloat*)>("glMaterialfv");
            std::vector<uint8_t> staged;
            if (fn && stage_guest(arg(2), 4 * sizeof(GLfloat), staged)) {
                fn(static_cast<GLenum>(arg(0)), static_cast<GLenum>(arg(1)),
                   reinterpret_cast<const GLfloat*>(staged.data()));
            }
            return 0u;
        };
        import_handlers_["glLightfv"] = [this] {
            auto fn = gl_proc<void (*)(GLenum, GLenum, const GLfloat*)>("glLightfv");
            std::vector<uint8_t> staged;
            if (fn && stage_guest(arg(2), 4 * sizeof(GLfloat), staged)) {
                fn(static_cast<GLenum>(arg(0)), static_cast<GLenum>(arg(1)),
                   reinterpret_cast<const GLfloat*>(staged.data()));
            }
            return 0u;
        };


#undef ZB_GL_V
#undef ZB_GL_R

        // ---- client vertex arrays in a draw ------------------------------
        // glVertexAttribPointer never reached the driver for client arrays; the
        // staging happens inside glDrawArrays / glDrawElements above.
    }

    static int fixed_kind(GLenum state) {
        switch (state) {
            case 0x8074: return kVertexArray;    // GL_VERTEX_ARRAY
            case 0x8075: return kNormalArray;    // GL_NORMAL_ARRAY
            case 0x8076: return kColorArray;     // GL_COLOR_ARRAY
            case 0x8078: return kTexCoordArray;  // GL_TEXTURE_COORD_ARRAY
            default: return -1;
        }
    }

    // Re-points every enabled fixed-function array at a host copy of the guest
    // range the upcoming draw will read.
    void stage_fixed_arrays(size_t vertex_count,
                            std::vector<std::vector<uint8_t>>& storage) {
        auto vertex = gl_proc<void (*)(GLint, GLenum, GLsizei, const void*)>(
                "glVertexPointer");
        auto color = gl_proc<void (*)(GLint, GLenum, GLsizei, const void*)>(
                "glColorPointer");
        auto texcoord = gl_proc<void (*)(GLint, GLenum, GLsizei, const void*)>(
                "glTexCoordPointer");
        auto normal = gl_proc<void (*)(GLenum, GLsizei, const void*)>("glNormalPointer");
        auto issue = [&](int kind, void* /*tag*/) {
            FixedArray& a = fixed_arrays_[kind];
            if (!a.enabled || !a.set || a.element == 0) return;
            const size_t stride = a.stride ? static_cast<size_t>(a.stride) : a.element;
            const size_t bytes = stride * vertex_count + a.element;
            storage.emplace_back();
            if (!stage_guest(a.ptr, bytes, storage.back())) {
                storage.pop_back();
                return;
            }
            const void* host = storage.back().data();
            switch (kind) {
                case kVertexArray:
                    if (vertex) vertex(a.size, a.type, static_cast<GLsizei>(stride), host);
                    break;
                case kColorArray:
                    if (color) color(a.size, a.type, static_cast<GLsizei>(stride), host);
                    break;
                case kTexCoordArray:
                    if (texcoord) texcoord(a.size, a.type, static_cast<GLsizei>(stride), host);
                    break;
                case kNormalArray:
                    if (normal) normal(a.type, static_cast<GLsizei>(stride), host);
                    break;
                default:
                    break;
            }
        };
        for (int kind = 0; kind < kFixedArrayCount; ++kind) issue(kind, nullptr);
    }

    // =====================================================================
    // libandroid / libOpenSLES
    //
    // NativeActivity-era payloads do not only draw: they poll an ALooper, read
    // assets through AAssetManager, consume AInputQueue events and push PCM at
    // an OpenSL ES buffer queue. All four are bridged to the real host
    // libraries; opaque host pointers cross as 32-bit handles and every
    // guest-memory buffer is copied, never truncated.
    // =====================================================================
    void install_platform_handlers() {
        android_lib_ = ::dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        opensles_lib_ = ::dlopen("libOpenSLES.so", RTLD_NOW | RTLD_LOCAL);
        LOGI("host platform: libandroid=%p libOpenSLES=%p", android_lib_, opensles_lib_);

        install_looper_handlers();
        install_asset_handlers();
        install_config_handlers();
        install_input_handlers();
        install_opensles_handlers();
        current_assets();
    }

    void install_looper_handlers() {
        import_handlers_["ALooper_prepare"] = [this] {
            auto fn = platform_proc<void* (*)(int)>("ALooper_prepare");
            return fn ? to_guest_ref(fn(static_cast<int>(arg(0)))) : 0u;
        };
        import_handlers_["ALooper_acquire"] = [this] {
            auto fn = platform_proc<void (*)(void*)>("ALooper_acquire");
            if (fn) fn(from_guest_ref(arg(0)));
            return 0u;
        };
        import_handlers_["ALooper_release"] = [this] {
            auto fn = platform_proc<void (*)(void*)>("ALooper_release");
            if (fn) fn(from_guest_ref(arg(0)));
            return 0u;
        };
        import_handlers_["ALooper_wake"] = [this] {
            auto fn = platform_proc<void (*)(void*)>("ALooper_wake");
            if (fn) fn(from_guest_ref(arg(0)));
            return 0u;
        };
        // ALooper_pollOnce(looper, timeoutMillis, outFd, outEvents, outData):
        // the ident is the return value, the three out-parameters are guest
        // memory and are filled from local host variables.
        import_handlers_["ALooper_pollOnce"] = [this] {
            auto fn = platform_proc<int (*)(void*, int, int*, int*, void**)>(
                    "ALooper_pollOnce");
            if (!fn) return static_cast<uint32_t>(-1);
            int fd = -1, events = 0;
            void* data = nullptr;
            const int ident = fn(from_guest_ref(arg(0)), static_cast<int>(arg(1)),
                                 &fd, &events, &data);
            if (arg(2)) emu_.write_u32(arg(2), static_cast<uint32_t>(fd));
            if (arg(3)) emu_.write_u32(arg(3), static_cast<uint32_t>(events));
            if (arg(4)) emu_.write_u32(arg(4), to_guest_ref(data));
            return static_cast<uint32_t>(ident);
        };
        import_handlers_["ALooper_pollAll"] = [this] {
            auto fn = platform_proc<int (*)(int, int*, int*, void**)>
                    ("ALooper_pollAll");
            if (!fn) return static_cast<uint32_t>(-1);
            int fd = -1, events = 0;
            void* data = nullptr;
            const int ident = fn(static_cast<int>(arg(0)), &fd, &events, &data);
            if (arg(1)) emu_.write_u32(arg(1), static_cast<uint32_t>(fd));
            if (arg(2)) emu_.write_u32(arg(2), static_cast<uint32_t>(events));
            if (arg(3)) emu_.write_u32(arg(3), to_guest_ref(data));
            return static_cast<uint32_t>(ident);
        };
        // The callback is a guest function pointer; the host looper cannot call
        // into the emulator, so the fd is registered without one and the guest
        // observes readiness through pollOnce's outEvents instead.
        import_handlers_["ALooper_addFd"] = [this] {
            auto fn = platform_proc<int (*)(void*, int, int, int, void*, void*)>(
                    "ALooper_addFd");
            if (!fn) return static_cast<uint32_t>(-1);
            if (arg(4)) {
                LOGW("ALooper_addFd: guest callback %08x cannot be invoked from the "
                     "host looper; polling only", arg(4));
            }
            return static_cast<uint32_t>(fn(from_guest_ref(arg(0)),
                                           static_cast<int>(arg(1)),
                                           static_cast<int>(arg(2)),
                                           static_cast<int>(arg(3)), nullptr, nullptr));
        };
        import_handlers_["ALooper_removeFd"] = [this] {
            auto fn = platform_proc<int (*)(void*, int)>("ALooper_removeFd");
            return fn ? static_cast<uint32_t>(fn(from_guest_ref(arg(0)),
                                                 static_cast<int>(arg(1))))
                      : static_cast<uint32_t>(-1);
        };
    }

    void install_asset_handlers() {
        import_handlers_["AAssetManager_fromJava"] = [this] {
            auto fn = platform_proc<void* (*)(JNIEnv*, jobject)>("AAssetManager_fromJava");
            if (!fn) return 0u;
            jobject jmgr = static_cast<jobject>(from_guest_ref(arg(1)));
            if (!jmgr) jmgr = assets_object_;
            void* mgr = fn(env_, jmgr);
            if (!mgr) {
                LOGW("AAssetManager_fromJava: no AssetManager on the guest context");
                return 0u;
            }
            return to_guest_ref(mgr);
        };
        import_handlers_["AAssetManager_open"] = [this] {
            auto fn = platform_proc<void* (*)(void*, const char*, int)>( "AAssetManager_open");
            if (!fn) return 0u;
            const std::string name = guest_string(arg(1));
            void* asset = fn(from_guest_ref(arg(0)), name.c_str(), static_cast<int>(arg(2)));
            if (!asset) LOGW("AAssetManager_open(%s) -> null", name.c_str());
            return asset ? to_guest_ref(asset) : 0u;
        };
        import_handlers_["AAssetManager_openDir"] = [this] {
            auto fn = platform_proc<void* (*)(void*, const char*)>("AAssetManager_openDir");
            return fn ? to_guest_ref(fn(from_guest_ref(arg(0)), guest_string(arg(1)).c_str()))
                      : 0u;
        };
        import_handlers_["AAssetDir_getNextFileName"] = [this] {
            auto fn = platform_proc<const char* (*)(void*)>("AAssetDir_getNextFileName");
            if (!fn) return 0u;
            const char* name = fn(from_guest_ref(arg(0)));
            return name ? guest_str(name) : 0u;
        };
        import_handlers_["AAssetDir_close"] = [this] {
            auto fn = platform_proc<void (*)(void*)>("AAssetDir_close");
            if (fn) fn(from_guest_ref(arg(0)));
            return 0u;
        };
        import_handlers_["AAsset_read"] = [this] {
            auto fn = platform_proc<int (*)(void*, void*, size_t)>("AAsset_read");
            if (!fn) return 0u;
            const uint32_t dst = arg(1);
            const size_t len = static_cast<size_t>(arg(2));
            if (!dst || len == 0) return 0u;
            std::vector<uint8_t> buf(len);
            const int got = fn(from_guest_ref(arg(0)), buf.data(), len);
            if (got > 0) emu_.write(dst, buf.data(), static_cast<size_t>(got));
            return static_cast<uint32_t>(got);
        };
        import_handlers_["AAsset_seek"] = [this] {
            auto fn = platform_proc<int (*)(void*, int, int)>("AAsset_seek");
            return fn ? static_cast<uint32_t>(fn(from_guest_ref(arg(0)),
                                                 static_cast<int>(arg(1)),
                                                 static_cast<int>(arg(2))))
                      : 0u;
        };
        import_handlers_["AAsset_getLength"] = [this] {
            auto fn = platform_proc<int (*)(void*)>("AAsset_getLength");
            return fn ? static_cast<uint32_t>(fn(from_guest_ref(arg(0)))) : 0u;
        };
        import_handlers_["AAsset_getRemainingLength"] = [this] {
            auto fn = platform_proc<int (*)(void*)>("AAsset_getRemainingLength");
            return fn ? static_cast<uint32_t>(fn(from_guest_ref(arg(0)))) : 0u;
        };
        import_handlers_["AAsset_close"] = [this] {
            auto fn = platform_proc<void (*)(void*)>("AAsset_close");
            if (fn) fn(from_guest_ref(arg(0)));
            return 0u;
        };
        import_handlers_["AAsset_isAllocated"] = [this] {
            auto fn = platform_proc<int (*)(void*)>("AAsset_isAllocated");
            return fn ? static_cast<uint32_t>(fn(from_guest_ref(arg(0)))) : 0u;
        };
        // getBuffer hands out a host address; the guest gets a copy in its own
        // address space instead, owned by the asset handle.
        import_handlers_["AAsset_getBuffer"] = [this] {
            auto fn = platform_proc<const void* (*)(void*)>("AAsset_getBuffer");
            auto len = platform_proc<int (*)(void*)>("AAsset_getLength");
            if (!fn || !len) return 0u;
            const uint32_t handle = arg(0);
            auto cached = asset_buffers_.find(handle);
            if (cached != asset_buffers_.end()) return cached->second;
            const void* host = fn(from_guest_ref(handle));
            const int bytes = len(from_guest_ref(handle));
            if (!host || bytes <= 0) return 0u;
            const uint32_t copy = emu_.alloc(static_cast<size_t>(bytes), 64);
            if (!copy) return 0u;
            emu_.write(copy, host, static_cast<size_t>(bytes));
            asset_buffers_[handle] = copy;
            return copy;
        };
    }

    void install_config_handlers() {
        auto ref_new = [this](const char* name) {
            import_handlers_[name] = [this, name] {
                auto fn = platform_proc<void* (*)()>(name);
                return fn ? to_guest_ref(fn()) : 0u;
            };
        };
        ref_new("AConfiguration_new");

        import_handlers_["AConfiguration_delete"] = [this] {
            auto fn = platform_proc<void (*)(void*)>("AConfiguration_delete");
            if (fn) fn(from_guest_ref(arg(0)));
            return 0u;
        };
        import_handlers_["AConfiguration_fromAssetManager"] = [this] {
            auto fn = platform_proc<void (*)(void*, void*)>(
                    "AConfiguration_fromAssetManager");
            if (fn) fn(from_guest_ref(arg(0)), from_guest_ref(arg(1)));
            return 0u;
        };
        import_handlers_["AConfiguration_getAssetManager"] = [this] {
            auto fn = platform_proc<void (*)(void*, void**)>(
                    "AConfiguration_getAssetManager");
            if (!fn) return 0u;
            void* mgr = nullptr;
            fn(from_guest_ref(arg(0)), &mgr);
            return to_guest_ref(mgr);
        };
        import_handlers_["AConfiguration_copy"] = [this] {
            auto fn = platform_proc<void (*)(void*, void*)>("AConfiguration_copy");
            if (fn) fn(from_guest_ref(arg(0)), from_guest_ref(arg(1)));
            return 0u;
        };
        // The remaining accessors all share (config, args..., out) shapes, so
        // they are forwarded one by one with explicit widths.
        auto get_int = [this](const char* name) {
            import_handlers_[name] = [this, name] {
                auto fn = platform_proc<int32_t (*)(void*)>(name);
                return fn ? static_cast<uint32_t>(fn(from_guest_ref(arg(0)))) : 0u;
            };
        };
        get_int("AConfiguration_getDensity");
        get_int("AConfiguration_getKeyboard");
        get_int("AConfiguration_getNavigation");
        get_int("AConfiguration_getOrientation");
        get_int("AConfiguration_getScreenSize");
        get_int("AConfiguration_getScreenLong");
        get_int("AConfiguration_getSdkVersion");
        get_int("AConfiguration_getUiModeType");
        get_int("AConfiguration_getUiModeNight");
        get_int("AConfiguration_getTouchscreen");

        auto set_int = [this](const char* name) {
            import_handlers_[name] = [this, name] {
                auto fn = platform_proc<int32_t (*)(void*, int32_t)>(name);
                return fn ? static_cast<uint32_t>(fn(from_guest_ref(arg(0)),
                                                    static_cast<int32_t>(arg(1))))
                          : 0u;
            };
        };
        set_int("AConfiguration_setDensity");
        set_int("AConfiguration_setKeyboard");
        set_int("AConfiguration_setNavigation");
        set_int("AConfiguration_setOrientation");
        set_int("AConfiguration_setScreenSize");
        set_int("AConfiguration_setScreenLong");
        set_int("AConfiguration_setSdkVersion");
        set_int("AConfiguration_setUiModeType");
        set_int("AConfiguration_setUiModeNight");
        set_int("AConfiguration_setTouchscreen");

        auto get_locale = [this](const char* name) {
            import_handlers_[name] = [this, name] {
                auto fn = platform_proc<void (*)(void*, char*)>(name);
                if (!fn || !arg(1)) return 0u;
                char buf[16] = {0};
                fn(from_guest_ref(arg(0)), buf);
                emu_.write(arg(1), buf, 2);
                return 0u;
            };
        };
        get_locale("AConfiguration_getLanguage");
        get_locale("AConfiguration_getCountry");

        import_handlers_["AConfiguration_diff"] = [this] {
            auto fn = platform_proc<int32_t (*)(void*, void*)>("AConfiguration_diff");
            return fn ? static_cast<uint32_t>(fn(from_guest_ref(arg(0)),
                                                 from_guest_ref(arg(1))))
                      : 0u;
        };
        import_handlers_["AConfiguration_equal"] = [this] {
            auto fn = platform_proc<int (*)(void*, void*)>("AConfiguration_equal");
            return fn ? static_cast<uint32_t>(fn(from_guest_ref(arg(0)),
                                                 from_guest_ref(arg(1))))
                      : 0u;
        };
        import_handlers_["AConfiguration_isBetterThan"] = [this] {
            auto fn = platform_proc<int32_t (*)(void*, void*, void*)>(
                    "AConfiguration_isBetterThan");
            return fn ? static_cast<uint32_t>(fn(from_guest_ref(arg(0)),
                                                 from_guest_ref(arg(1)),
                                                 from_guest_ref(arg(2))))
                      : 0u;
        };
    }

    void install_input_handlers() {
        import_handlers_["AInputQueue_attachLooper"] = [this] {
            auto fn = platform_proc<void (*)(void*, void*, int, void*, void*)>(
                    "AInputQueue_attachLooper");
            if (fn) {
                fn(from_guest_ref(arg(0)), from_guest_ref(arg(1)),
                   static_cast<int>(arg(2)), nullptr, nullptr);
            }
            return 0u;
        };
        import_handlers_["AInputQueue_detachLooper"] = [this] {
            auto fn = platform_proc<void (*)(void*)>("AInputQueue_detachLooper");
            if (fn) fn(from_guest_ref(arg(0)));
            return 0u;
        };
        import_handlers_["AInputQueue_hasEvents"] = [this] {
            auto fn = platform_proc<int32_t (*)(void*)>("AInputQueue_hasEvents");
            return fn ? static_cast<uint32_t>(fn(from_guest_ref(arg(0)))) : 0u;
        };
        import_handlers_["AInputQueue_getEvent"] = [this] {
            auto fn = platform_proc<int32_t (*)(void*, void**)>("AInputQueue_getEvent");
            if (!fn) return static_cast<uint32_t>(-1);
            void* event = nullptr;
            const int32_t r = fn(from_guest_ref(arg(0)), &event);
            if (r >= 0 && arg(1)) emu_.write_u32(arg(1), to_guest_ref(event));
            return static_cast<uint32_t>(r);
        };
        import_handlers_["AInputQueue_preDispatchEvent"] = [this] {
            auto fn = platform_proc<int32_t (*)(void*, void*)>(
                    "AInputQueue_preDispatchEvent");
            return fn ? static_cast<uint32_t>(fn(from_guest_ref(arg(0)),
                                                 from_guest_ref(arg(1))))
                      : 0u;
        };
        import_handlers_["AInputQueue_finishEvent"] = [this] {
            auto fn = platform_proc<void (*)(void*, void*, int) >("AInputQueue_finishEvent");
            if (fn) fn(from_guest_ref(arg(0)), from_guest_ref(arg(1)),
                       static_cast<int>(arg(2)));
            return 0u;
        };

        auto event_int = [this](const char* name) {
            import_handlers_[name] = [this, name] {
                auto fn = platform_proc<int32_t (*)(void*)>(name);
                return fn ? static_cast<uint32_t>(fn(from_guest_ref(arg(0)))) : 0u;
            };
        };
        event_int("AInputEvent_getType");
        event_int("AInputEvent_getDeviceId");
        event_int("AInputEvent_getSource");
        event_int("AKeyEvent_getAction");
        event_int("AKeyEvent_getKeyCode");
        event_int("AKeyEvent_getMetaState");
        event_int("AKeyEvent_getRepeatCount");
        event_int("AKeyEvent_getScanCode");
        event_int("AKeyEvent_getFlags");
        event_int("AMotionEvent_getPointerCount");
        event_int("AMotionEvent_getMetaState");

        auto event_long = [this](const char* name) {
            import_handlers_[name] = [this, name] {
                auto fn = platform_proc<int64_t (*)(void*)>(name);
                if (!fn) return 0u;
                const int64_t v = fn(from_guest_ref(arg(0)));
                return static_cast<uint32_t>(static_cast<uint64_t>(v));
            };
        };
        event_long("AKeyEvent_getEventTime");
        event_long("AMotionEvent_getEventTime");
        event_long("AMotionEvent_getDownTime");

        import_handlers_["AMotionEvent_getAction"] = [this] {
            auto fn = platform_proc<int32_t (*)(void*)>("AMotionEvent_getAction");
            return fn ? static_cast<uint32_t>(fn(from_guest_ref(arg(0)))) : 0u;
        };
        import_handlers_["AMotionEvent_getX"] = [this] {
            auto fn = platform_proc<float (*)(void*, size_t)>("AMotionEvent_getX");
            if (!fn) return 0u;
            const float v = fn(from_guest_ref(arg(0)), static_cast<size_t>(arg(1)));
            uint32_t bits = 0;
            std::memcpy(&bits, &v, sizeof bits);
            return bits;
        };
        import_handlers_["AMotionEvent_getY"] = [this] {
            auto fn = platform_proc<float (*)(void*, size_t)>("AMotionEvent_getY");
            if (!fn) return 0u;
            const float v = fn(from_guest_ref(arg(0)), static_cast<size_t>(arg(1)));
            uint32_t bits = 0;
            std::memcpy(&bits, &v, sizeof bits);
            return bits;
        };
        import_handlers_["AMotionEvent_getPointerId"] = [this] {
            auto fn = platform_proc<int32_t (*)(void*, size_t)>("AMotionEvent_getPointerId");
            return fn ? static_cast<uint32_t>(fn(from_guest_ref(arg(0)),
                                                 static_cast<size_t>(arg(1))))
                      : 0u;
        };
    }

    // ---- OpenSL ES ------------------------------------------------------
    //
    // A guest object is a two-word guest struct: { vtable, token }. The vtable
    // is an array of trap-page stubs, so guest code can call it with the normal
    // ARM32 interface convention while the implementation lives here. The host
    // objects behind each token are the real ones from libOpenSLES.so, which is
    // what makes audio actually audible.
    struct SlHostObject {
        SLObjectItf obj = nullptr;
        SLEngineItf engine = nullptr;
        SLPlayItf play = nullptr;
        SLAndroidSimpleBufferQueueItf queue = nullptr;
        uint32_t guest = 0;        // guest address of the two-word struct
        uint32_t engine_iface = 0;
        uint32_t play_iface = 0;
        uint32_t queue_iface = 0;
    };

    uint32_t sl_new_object(std::vector<std::function<uint32_t()>>& cbs) {
        std::vector<uint32_t> table;
        table.reserve(cbs.size());
        for (auto& cb : cbs) table.push_back(make_callback_stub(std::move(cb), "sl_itf"));
        const uint32_t vt = emu_.alloc(table.size() * 4, 4);
        if (!vt) return 0;
        emu_.write(vt, table.data(), table.size() * 4);
        const uint32_t obj = emu_.alloc(8, 4);
        if (!obj) return 0;
        emu_.write_u32(obj, vt);
        emu_.write_u32(obj + 4, 0xFFFFFFFFu);
        return obj;
    }

    void install_opensles_handlers() {
        if (!opensles_lib_) {
            LOGW("OpenSL ES: host libOpenSLES.so unavailable; slCreateEngine stubbed");
            import_handlers_["slCreateEngine"] = [] { return static_cast<uint32_t>(0x0C); };
            return;
        }

        // Interface identifiers are data imports: a guest word whose value is
        // the address of a guest-side SLInterfaceID_ struct (id in word 0).
        auto publish_id = [this](const char* symbol, uint32_t id) {
            const uint32_t sid = emu_.alloc(16, 4);
            const uint32_t slot = emu_.alloc(4, 4);
            if (!sid || !slot) return;
            emu_.write_u32(sid, id);
            emu_.write_u32(slot, sid);
            data_slots_[symbol] = slot;
        };
        publish_id("SL_IID_ENGINE", 1);
        publish_id("SL_IID_PLAY", 2);
        publish_id("SL_IID_ANDROIDSIMPLEBUFFERQUEUE", 3);
        publish_id("SL_IID_OUTPUTMIX", 4);
        publish_id("SL_IID_VOLUME", 5);
        publish_id("SL_IID_BUFFERQUEUE", 6);
        publish_id("SL_IID_ANDROIDCONFIGURATION", 7);

        using CreateEngineFn = SLresult (*)(SLObjectItf*, SLuint32, const SLEngineOption*,
                                            SLuint32, const SLInterfaceID*,
                                            const SLboolean*);

        import_handlers_["slCreateEngine"] = [this] {
            auto fn = platform_proc<CreateEngineFn>("slCreateEngine");
            if (!fn) return static_cast<uint32_t>(SL_RESULT_FEATURE_UNSUPPORTED);
            SLObjectItf host = nullptr;
            const SLresult r = fn(&host, 0, nullptr, 0, nullptr, nullptr);
            if (r != SL_RESULT_SUCCESS || !host) {
                LOGW("slCreateEngine -> %u", static_cast<unsigned>(r));
                return static_cast<uint32_t>(r);
            }
            const size_t token = sl_objects_.size();
            sl_objects_.emplace_back();
            sl_objects_[token].obj = host;

            std::vector<std::function<uint32_t()>> vt;
            // Realize
            vt.push_back([this, token] {
                SlHostObject& o = sl_objects_[token];
                const SLresult rr = (*o.obj)->Realize(o.obj, SL_BOOLEAN_FALSE);
                if (rr == SL_RESULT_SUCCESS) {
                    (*o.obj)->GetInterface(o.obj, SL_IID_ENGINE, &o.engine);
                    o.guest = arg(0);
                    LOGI("slCreateEngine: engine realized (host %p)", o.engine);
                }
                return static_cast<uint32_t>(rr);
            });
            // Resume
            vt.push_back([this, token] {
                SlHostObject& o = sl_objects_[token];
                return static_cast<uint32_t>((*o.obj)->Resume(o.obj, SL_BOOLEAN_FALSE));
            });
            // GetState
            vt.push_back([this, token] {
                SlHostObject& o = sl_objects_[token];
                SLuint32 state = SL_OBJECT_STATE_UNREALIZED;
                const SLresult rr = (*o.obj)->GetState(o.obj, &state);
                if (arg(1)) emu_.write_u32(arg(1), state);
                return static_cast<uint32_t>(rr);
            });
            // GetInterface(SLObjectItf self, SLInterfaceID iid, SLboolean required,
            //              void* out)
            vt.push_back([this, token] {
                SlHostObject& o = sl_objects_[token];
                const uint32_t id = sl_id(arg(1));
                uint32_t guest = 0;
                if (id == 1) guest = engine_interface(token);
                else if (id == 2) guest = play_interface(token);
                else if (id == 3 || id == 6) guest = queue_interface(token);
                if (guest && arg(3)) emu_.write_u32(arg(3), guest);
                if (!guest) {
                    LOGW("SL GetInterface(id=%u) unsupported", id);
                    return static_cast<uint32_t>(SL_RESULT_FEATURE_UNSUPPORTED);
                }
                return static_cast<uint32_t>(SL_RESULT_SUCCESS);
            });
            // Destroy
            vt.push_back([this, token] {
                SlHostObject& o = sl_objects_[token];
                if (o.obj) (*o.obj)->Destroy(o.obj);
                o.obj = nullptr;
                return static_cast<uint32_t>(SL_RESULT_SUCCESS);
            });

            const uint32_t obj = sl_new_object(vt);
            if (arg(0)) emu_.write_u32(arg(0), obj);
            LOGI("slCreateEngine -> guest object %08x", obj);
            return static_cast<uint32_t>(SL_RESULT_SUCCESS);
        };
    }

    uint32_t sl_id(uint32_t iid) { return iid ? read_guest_u32(iid) : 0u; }

    uint32_t engine_interface(size_t token) {
        SlHostObject& o = sl_objects_[token];
        if (o.engine_iface) return o.engine_iface;
        std::vector<std::function<uint32_t()>> vt;
        // Slot order matches SLEngineItf_: 0 CreateLEDDevice, 1 CreateVibraDevice,
        // 2 CreateAudioPlayer, 3 CreateAudioRecorder, 4 CreateMidiPlayer,
        // 5 CreateListener, 6 Create3DGroup, 7 CreateOutputMix, ...
        for (int i = 0; i < 12; ++i) {
            vt.push_back([this, token, i] {
                if (i == 7) return create_output_mix(token);
                if (i == 2) return create_audio_player(token);
                return static_cast<uint32_t>(SL_RESULT_FEATURE_UNSUPPORTED);
            });
        }
        o.engine_iface = sl_new_object(vt);
        return o.engine_iface;
    }

    uint32_t create_output_mix(size_t token) {
        SlHostObject& o = sl_objects_[token];
        if (!o.engine) return static_cast<uint32_t>(SL_RESULT_PRECONDITIONS_VIOLATED);
        const size_t mixToken = sl_objects_.size();
        sl_objects_.emplace_back();
        SLObjectItf mix = nullptr;
        const SLresult r = (*o.engine)->CreateOutputMix(o.engine, &mix, 0, nullptr, nullptr);
        if (r != SL_RESULT_SUCCESS || !mix) {
            LOGW("CreateOutputMix -> %u", static_cast<unsigned>(r));
            return static_cast<uint32_t>(r);
        }
        sl_objects_[mixToken].obj = mix;
        (*mix)->Realize(mix, SL_BOOLEAN_FALSE);
        // The guest gets an object-like handle whose only supported interface is
        // OUTPUTMIX (no methods are ever called on it in practice).
        std::vector<std::function<uint32_t()>> vt;
        vl_fill_noop(vt);
        const uint32_t guest = sl_new_object(vt);
        if (arg(1)) emu_.write_u32(arg(1), guest);
        LOGI("CreateOutputMix -> guest %08x", guest);
        return static_cast<uint32_t>(SL_RESULT_SUCCESS);
    }

    void vl_fill_noop(std::vector<std::function<uint32_t()>>& vt) {
        for (int i = 0; i < 5; ++i) {
            vt.push_back([] { return static_cast<uint32_t>(SL_RESULT_SUCCESS); });
        }
    }

    uint32_t create_audio_player(size_t token) {
        SlHostObject& o = sl_objects_[token];
        if (!o.engine) return static_cast<uint32_t>(SL_RESULT_PRECONDITIONS_VIOLATED);

        // 32-bit guest layouts of the source/sink descriptors.
        const uint32_t src = arg(2), sink = arg(3);
        const uint32_t locator = src ? read_guest_u32(src) : 0u;
        const uint32_t format = src ? read_guest_u32(src + 4) : 0u;
        const uint32_t sinkLocator = sink ? read_guest_u32(sink) : 0u;
        if (!locator || !format || !sinkLocator) {
            LOGW("CreateAudioPlayer: guest source/sink descriptors unreadable");
            return static_cast<uint32_t>(SL_RESULT_PARAMETER_INVALID);
        }

        SLDataLocator_AndroidSimpleBufferQueue hostLoc = {};
        hostLoc.locatorType = read_guest_u32(locator);
        hostLoc.numBuffers = read_guest_u32(locator + 4);

        SLDataFormat_PCM hostFmt = {};
        hostFmt.formatType = read_guest_u32(format);
        hostFmt.numChannels = read_guest_u32(format + 4);
        hostFmt.samplesPerSec = read_guest_u32(format + 8);
        hostFmt.bitsPerSample = read_guest_u32(format + 12);
        hostFmt.containerSize = read_guest_u32(format + 16);
        hostFmt.channelMask = read_guest_u32(format + 20);
        hostFmt.endianness = read_guest_u32(format + 24);

        SLDataLocator_OutputMix hostSinkLoc = {};
        hostSinkLoc.locatorType = read_guest_u32(sinkLocator);
        hostSinkLoc.outputMix = static_cast<SLObjectItf>(
                from_guest_ref(read_guest_u32(sinkLocator + 4)));

        SLDataSource hostSrc = {};
        hostSrc.pLocator = &hostLoc;
        hostSrc.pFormat = &hostFmt;
        SLDataSink hostSink = {};
        hostSink.pLocator = &hostSinkLoc;
        hostSink.pFormat = nullptr;

        const SLInterfaceID ids[2] = {SL_IID_PLAY, SL_IID_ANDROIDSIMPLEBUFFERQUEUE};
        const SLboolean req[2] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE};
        SLObjectItf player = nullptr;
        const SLresult r = (*o.engine)->CreateAudioPlayer(o.engine, &player, &hostSrc,
                                                          &hostSink, 2, ids, req);
        if (r != SL_RESULT_SUCCESS || !player) {
            LOGW("CreateAudioPlayer -> %u", static_cast<unsigned>(r));
            return static_cast<uint32_t>(r);
        }

        const size_t playerToken = sl_objects_.size();
        sl_objects_.emplace_back();
        SlHostObject& p = sl_objects_[playerToken];
        p.obj = player;
        (*player)->Realize(player, SL_BOOLEAN_FALSE);
        (*player)->GetInterface(player, SL_IID_PLAY, &p.play);
        (*player)->GetInterface(player, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &p.queue);

        std::vector<std::function<uint32_t()>> vt;
        // SLObjectItf subset: Realize, Resume, GetState, GetInterface, Destroy.
        vt.push_back([this, playerToken] {
            SlHostObject& po = sl_objects_[playerToken];
            return static_cast<uint32_t>((*po.obj)->Realize(po.obj, SL_BOOLEAN_FALSE));
        });
        vt.push_back([this, playerToken] {
            SlHostObject& po = sl_objects_[playerToken];
            return static_cast<uint32_t>((*po.obj)->Resume(po.obj, SL_BOOLEAN_FALSE));
        });
        vt.push_back([this, playerToken] {
            SlHostObject& po = sl_objects_[playerToken];
            SLuint32 state = 0;
            const SLresult rr = (*po.obj)->GetState(po.obj, &state);
            if (arg(1)) emu_.write_u32(arg(1), state);
            return static_cast<uint32_t>(rr);
        });
        vt.push_back([this, playerToken] {
            const uint32_t id = sl_id(arg(1));
            uint32_t guest = 0;
            if (id == 2) guest = play_interface(playerToken);
            else if (id == 3 || id == 6) guest = queue_interface(playerToken);
            if (guest && arg(3)) emu_.write_u32(arg(3), guest);
            return static_cast<uint32_t>(guest ? SL_RESULT_SUCCESS
                                               : SL_RESULT_FEATURE_UNSUPPORTED);
        });
        vt.push_back([this, playerToken] {
            SlHostObject& po = sl_objects_[playerToken];
            if (po.obj) (*po.obj)->Destroy(po.obj);
            po.obj = nullptr;
            return static_cast<uint32_t>(SL_RESULT_SUCCESS);
        });

        p.guest = sl_new_object(vt);
        LOGI("CreateAudioPlayer -> guest %08x play=%p queue=%p", p.guest, p.play, p.queue);
        if (arg(1)) emu_.write_u32(arg(1), p.guest);
        return static_cast<uint32_t>(SL_RESULT_SUCCESS);
    }

    uint32_t play_interface(size_t token) {
        SlHostObject& o = sl_objects_[token];
        if (o.play_iface) return o.play_iface;
        std::vector<std::function<uint32_t()>> vt;
        // 0 SetPlayState, 1 GetPlayState, 2 GetDuration, 3 GetPosition,
        // 4 RegisterCallback, 5 SetCallbackEventsMask, 6 GetCallbackEventsMask
        vt.push_back([this, token] {
            SlHostObject& po = sl_objects_[token];
            if (!po.play) return static_cast<uint32_t>(SL_RESULT_PRECONDITIONS_VIOLATED);
            const SLresult r = (*po.play)->SetPlayState(po.play,
                                                        static_cast<SLuint32>(arg(1)));
            LOGI("SLPlay SetPlayState(%u) -> %u", arg(1), static_cast<unsigned>(r));
            return static_cast<uint32_t>(r);
        });
        vt.push_back([this, token] {
            SlHostObject& po = sl_objects_[token];
            if (!po.play) return static_cast<uint32_t>(SL_RESULT_PRECONDITIONS_VIOLATED);
            SLuint32 state = 0;
            const SLresult r = (*po.play)->GetPlayState(po.play, &state);
            if (arg(1)) emu_.write_u32(arg(1), state);
            return static_cast<uint32_t>(r);
        });
        vt.push_back([this, token] {
            SlHostObject& po = sl_objects_[token];
            if (!po.play) return static_cast<uint32_t>(SL_RESULT_PRECONDITIONS_VIOLATED);
            SLuint32 ms = 0;
            const SLresult r = (*po.play)->GetDuration(po.play, &ms);
            if (arg(1)) emu_.write_u32(arg(1), ms);
            return static_cast<uint32_t>(r);
        });
        vt.push_back([this, token] {
            SlHostObject& po = sl_objects_[token];
            if (!po.play) return static_cast<uint32_t>(SL_RESULT_PRECONDITIONS_VIOLATED);
            SLuint32 ms = 0;
            const SLresult r = (*po.play)->GetPosition(po.play, &ms);
            if (arg(1)) emu_.write_u32(arg(1), ms);
            return static_cast<uint32_t>(r);
        });
        // RegisterCallback: the host would call a guest function pointer from an
        // OpenSL thread, which the emulator cannot re-enter. Accepted and
        // ignored; the guest learns of consumption via GetState.
        vt.push_back([] { return static_cast<uint32_t>(SL_RESULT_SUCCESS); });
        vt.push_back([] { return static_cast<uint32_t>(SL_RESULT_SUCCESS); });
        vt.push_back([] { return static_cast<uint32_t>(SL_RESULT_SUCCESS); });
        o.play_iface = sl_new_object(vt);
        return o.play_iface;
    }

    uint32_t queue_interface(size_t token) {
        SlHostObject& o = sl_objects_[token];
        if (o.queue_iface) return o.queue_iface;
        std::vector<std::function<uint32_t()>> vt;
        // 0 Enqueue, 1 Clear, 2 GetState, 3 RegisterCallback
        vt.push_back([this, token] {
            SlHostObject& po = sl_objects_[token];
            if (!po.queue) return static_cast<uint32_t>(SL_RESULT_PRECONDITIONS_VIOLATED);
            const uint32_t at = arg(1);
            const size_t bytes = static_cast<size_t>(arg(2));
            if (!at || bytes == 0 || bytes > (8u * 1024u * 1024u)) {
                return static_cast<uint32_t>(SL_RESULT_PARAMETER_INVALID);
            }
            // The host queue reads the buffer asynchronously, so it must stay
            // alive after this call returns.
            sl_pcm_.emplace_back(bytes);
            if (!emu_.read(at, sl_pcm_.back().data(), bytes)) {
                sl_pcm_.pop_back();
                return static_cast<uint32_t>(SL_RESULT_PARAMETER_INVALID);
            }
            const SLresult r = (*po.queue)->Enqueue(po.queue, sl_pcm_.back().data(),
                                                    static_cast<SLuint32>(bytes));
            if (sl_pcm_.size() > 64) sl_pcm_.erase(sl_pcm_.begin(), sl_pcm_.begin() + 32);
            return static_cast<uint32_t>(r);
        });
        vt.push_back([this, token] {
            SlHostObject& po = sl_objects_[token];
            if (!po.queue) return static_cast<uint32_t>(SL_RESULT_PRECONDITIONS_VIOLATED);
            return static_cast<uint32_t>((*po.queue)->Clear(po.queue));
        });
        vt.push_back([this, token] {
            SlHostObject& po = sl_objects_[token];
            if (!po.queue) return static_cast<uint32_t>(SL_RESULT_PRECONDITIONS_VIOLATED);
            SLAndroidSimpleBufferQueueState state = {};
            const SLresult r = (*po.queue)->GetState(po.queue, &state);
            if (arg(1)) {
                emu_.write_u32(arg(1), state.count);
                emu_.write_u32(arg(1) + 4, state.index);
            }
            return static_cast<uint32_t>(r);
        });
        vt.push_back([] { return static_cast<uint32_t>(SL_RESULT_SUCCESS); });
        o.queue_iface = sl_new_object(vt);
        return o.queue_iface;
    }

    template <typename T>
    T platform_proc(const char* symbol) {
        const std::string key(symbol);
        auto it = platform_syms_.find(key);
        if (it != platform_syms_.end()) return reinterpret_cast<T>(it->second);
        void* p = android_lib_ ? ::dlsym(android_lib_, symbol) : nullptr;
        if (!p && opensles_lib_) p = ::dlsym(opensles_lib_, symbol);
        platform_syms_[key] = p;
        if (!p) LOGW("platform symbol unavailable: %s", symbol);
        return reinterpret_cast<T>(p);
    }

    uint32_t read_guest_u32(uint32_t at) {
        uint32_t v = 0;
        if (!emu_.read(at, &v, sizeof v)) return 0;
        return v;
    }

    // The process AssetManager, used when guest code calls
    // AAssetManager_fromJava with a null/torn-down manager. Guest APK assets are
    // already merged into it by GuestContext, so a native open() finds them.
    jobject current_assets() {
        if (assets_object_ || !env_) return assets_object_;
        jclass thread = env_->FindClass("android/app/ActivityThread");
        if (!thread) return nullptr;
        jmethodID current = env_->GetStaticMethodID(
                thread, "currentApplication", "()Landroid/app/Application;");
        jobject app = current ? env_->CallStaticObjectMethod(thread, current) : nullptr;
        env_->DeleteLocalRef(thread);
        if (!app) return nullptr;
        jclass appCls = env_->GetObjectClass(app);
        jmethodID getAssets = env_->GetMethodID(appCls, "getAssets",
                                               "()Landroid/content/res/AssetManager;");
        jobject assets = getAssets ? env_->CallObjectMethod(app, getAssets) : nullptr;
        env_->DeleteLocalRef(appCls);
        if (env_->ExceptionCheck()) env_->ExceptionClear();
        if (assets) assets_object_ = env_->NewGlobalRef(assets);
        if (assets_object_) LOGI("asset bridge: process AssetManager %p", assets_object_);
        return assets_object_;
    }

    void install_import_handlers() {
        // ---------------- memory ---------------------------------------
        import_handlers_["malloc"]  = [this] { return imp_malloc(); };
        // brk-style heap for payloads that allocate before malloc is usable.
        import_handlers_["sbrk"] = [this] {
            constexpr size_t kSbrkSize = 16u * 1024u * 1024u;
            if (!brk_base_) {
                brk_base_ = emu_.alloc(kSbrkSize, 4096);
                brk_used_ = 0;
            }
            if (!brk_base_) return 0xFFFFFFFFu;
            const uint32_t previous = brk_base_ + brk_used_;
            const int64_t next = static_cast<int64_t>(brk_used_) +
                                 static_cast<int32_t>(arg(0));
            if (next < 0 || next > static_cast<int64_t>(kSbrkSize)) return 0xFFFFFFFFu;
            brk_used_ = static_cast<uint32_t>(next);
            return previous;
        };
        import_handlers_["__errno"] = [this] { return errno_cell_; };
        import_handlers_["__errno_location"] = [this] { return errno_cell_; };
        import_handlers_["calloc"]  = [this] { return imp_calloc(); };
        import_handlers_["realloc"] = [this] { return imp_realloc(); };
        import_handlers_["free"]    = [this] { return imp_free(); };
        import_handlers_["memcpy"]  = [this] { return imp_memcpy(); };
        import_handlers_["memmove"] = [this] { return imp_memmove(); };
        import_handlers_["memset"]  = [this] { return imp_memset(); };
        import_handlers_["memcmp"]  = [this] { return imp_memcmp(); };
        import_handlers_["__aeabi_memcpy"]  = [this] { return imp_memcpy(); };
        import_handlers_["__aeabi_memcpy4"] = [this] { return imp_memcpy(); };
        import_handlers_["__aeabi_memcpy8"] = [this] { return imp_memcpy(); };
        import_handlers_["__aeabi_memmove"]  = [this] { return imp_memmove(); };
        import_handlers_["__aeabi_memmove4"] = [this] { return imp_memmove(); };
        import_handlers_["__aeabi_memmove8"] = [this] { return imp_memmove(); };
        import_handlers_["__aeabi_memclr"]  = [this] { return imp_memclr(); };
        import_handlers_["__aeabi_memclr4"] = [this] { return imp_memclr(); };
        import_handlers_["__aeabi_memclr8"] = [this] { return imp_memclr(); };
        import_handlers_["__aeabi_memset"]  = [this] { return imp_aeabi_memset(); };
        import_handlers_["__aeabi_memset4"] = [this] { return imp_aeabi_memset(); };
        import_handlers_["__aeabi_memset8"] = [this] { return imp_aeabi_memset(); };
        import_handlers_["bzero"] = [this] { return imp_memclr(); };
        import_handlers_["bcopy"] = [this] {
            copy_guest(arg(0), arg(1), arg(2));
            return 0u;
        };
        // struct tm is narrower on 32-bit; the guest gets the bionic layout.
        import_handlers_["gmtime"] = [this] {
            int32_t seconds = 0;
            if (!emu_.read(arg(0), &seconds, sizeof seconds)) return 0u;
            time_t t = static_cast<time_t>(seconds);
            struct tm out {};
            if (!::gmtime_r(&t, &out)) return 0u;
            if (!tm_guest_) tm_guest_ = emu_.alloc(64, 8);
            if (!tm_guest_) return 0u;
            const int32_t fields[11] = {
                    out.tm_sec, out.tm_min, out.tm_hour, out.tm_mday, out.tm_mon,
                    out.tm_year, out.tm_wday, out.tm_yday, out.tm_isdst,
                    static_cast<int32_t>(out.tm_gmtoff), 0};
            emu_.write(tm_guest_, fields, sizeof fields);
            return tm_guest_;
        };

        import_handlers_["memchr"] = [this] {
            uint32_t p = arg(0);
            const uint8_t want = static_cast<uint8_t>(arg(1) & 0xFFu);
            uint32_t n = arg(2);
            while (n--) {
                uint8_t c = 0;
                if (!emu_.read(p, &c, 1)) return 0u;
                if (c == want) return p;
                ++p;
            }
            return 0u;
        };

        // ---------------- strings --------------------------------------
        import_handlers_["puts"] = [this] {
            LOGI("guest: %s", safe_guest_string(arg(0), "puts").c_str());
            return 0u;
        };
        import_handlers_["strlen"] = [this] { return imp_strlen(); };
        import_handlers_["strcpy"] = [this] { return imp_strcpy(); };
        import_handlers_["strcmp"] = [this] { return imp_strcmp(); };
        import_handlers_["strncmp"] = [this] {
            const std::string a = safe_guest_string(arg(0), "strncmp");
            const std::string b = safe_guest_string(arg(1), "strncmp");
            return static_cast<uint32_t>(std::strncmp(a.c_str(), b.c_str(), arg(2)));
        };
        import_handlers_["strcasecmp"] = [this] {
            const std::string a = safe_guest_string(arg(0), "strcasecmp");
            const std::string b = safe_guest_string(arg(1), "strcasecmp");
            return static_cast<uint32_t>(::strcasecmp(a.c_str(), b.c_str()));
        };
        import_handlers_["strncpy"] = [this] {
            const uint32_t dst = arg(0);
            const std::string src = guest_string(arg(1));
            const uint32_t n = arg(2);
            std::vector<uint8_t> buf(n, 0);
            const size_t copy = std::min<size_t>(src.size(), n);
            std::memcpy(buf.data(), src.data(), copy);
            emu_.write(dst, buf.data(), buf.size());
            return dst;
        };
        import_handlers_["strcat"] = [this] {
            const uint32_t dst = arg(0);
            const std::string base = guest_string(dst);
            const std::string add = guest_string(arg(1));
            const std::string joined = base + add;
            emu_.write(dst, joined.c_str(), joined.size() + 1);
            return dst;
        };
        import_handlers_["strchr"] = [this] {
            const uint32_t s = arg(0);
            const char c = static_cast<char>(arg(1) & 0xFFu);
            const std::string str = safe_guest_string(s, "strchr");
            const size_t at = str.find(c);
            if (at != std::string::npos) return s + static_cast<uint32_t>(at);
            return c == 0 ? s + static_cast<uint32_t>(str.size()) : 0u;
        };
        import_handlers_["strrchr"] = [this] {
            const uint32_t s = arg(0);
            const char c = static_cast<char>(arg(1) & 0xFFu);
            const std::string str = safe_guest_string(s, "strrchr");
            const size_t at = str.rfind(c);
            if (at != std::string::npos) return s + static_cast<uint32_t>(at);
            return c == 0 ? s + static_cast<uint32_t>(str.size()) : 0u;
        };
        import_handlers_["strstr"] = [this] {
            const uint32_t h = arg(0);
            const std::string hay = safe_guest_string(h, "strstr");
            const std::string needle = safe_guest_string(arg(1), "strstr");
            const size_t at = hay.find(needle);
            return at == std::string::npos ? 0u : h + static_cast<uint32_t>(at);
        };
        import_handlers_["strdup"] = [this] { return strdup_into_guest(arg(0), SIZE_MAX); };
        import_handlers_["strndup"] = [this] { return strdup_into_guest(arg(0), arg(1)); };
        import_handlers_["atoi"] = [this] {
            return static_cast<uint32_t>(std::strtol(guest_string(arg(0)).c_str(), nullptr, 10));
        };
        import_handlers_["atol"] = [this] {
            return static_cast<uint32_t>(std::strtol(guest_string(arg(0)).c_str(), nullptr, 10));
        };
        import_handlers_["strtol"] = [this] {
            const std::string s = guest_string(arg(0));
            const uint32_t endp = arg(1);
            char* end = nullptr;
            const long v = std::strtol(s.c_str(), &end, static_cast<int>(arg(2)));
            if (endp) {
                emu_.write_u32(endp, arg(0) + static_cast<uint32_t>(end ? end - s.c_str() : 0));
            }
            return static_cast<uint32_t>(v);
        };
        import_handlers_["strtoul"] = [this] {
            const std::string s = guest_string(arg(0));
            return static_cast<uint32_t>(std::strtoul(s.c_str(), nullptr, static_cast<int>(arg(2))));
        };

        // ---------------- libm (softfp in / softfp out) -----------------
        auto un_f = [this](float (*fn)(float)) {
            return [this, fn] { return ret_f(fn(f_arg(0))); };
        };
        auto un_d = [this](double (*fn)(double)) {
            return [this, fn] { return ret_d(fn(d_arg(0))); };
        };
        auto bi_f = [this](float (*fn)(float, float)) {
            return [this, fn] { return ret_f(fn(f_arg(0), f_arg(1))); };
        };
        auto bi_d = [this](double (*fn)(double, double)) {
            return [this, fn] { return ret_d(fn(d_arg(0), d_arg(2))); };
        };

        import_handlers_["sqrtf"]  = un_f(::sqrtf);
        import_handlers_["sinf"]   = un_f(::sinf);
        import_handlers_["cosf"]   = un_f(::cosf);
        import_handlers_["tanf"]   = un_f(::tanf);
        import_handlers_["asinf"]  = un_f(::asinf);
        import_handlers_["acosf"]  = un_f(::acosf);
        import_handlers_["atanf"]  = un_f(::atanf);
        import_handlers_["expf"]   = un_f(::expf);
        import_handlers_["logf"]   = un_f(::logf);
        import_handlers_["log10f"] = un_f(::log10f);
        import_handlers_["floorf"] = un_f(::floorf);
        import_handlers_["ceilf"]  = un_f(::ceilf);
        import_handlers_["fabsf"]  = un_f(::fabsf);
        import_handlers_["roundf"] = un_f(::roundf);
        import_handlers_["truncf"] = un_f(::truncf);

        import_handlers_["sqrt"]  = un_d(::sqrt);
        import_handlers_["sin"]   = un_d(::sin);
        import_handlers_["cos"]   = un_d(::cos);
        import_handlers_["tan"]   = un_d(::tan);
        import_handlers_["asin"]  = un_d(::asin);
        import_handlers_["acos"]  = un_d(::acos);
        import_handlers_["atan"]  = un_d(::atan);
        import_handlers_["exp"]   = un_d(::exp);
        import_handlers_["log"]   = un_d(::log);
        import_handlers_["log10"] = un_d(::log10);
        import_handlers_["floor"] = un_d(::floor);
        import_handlers_["ceil"]  = un_d(::ceil);
        import_handlers_["fabs"]  = un_d(::fabs);
        import_handlers_["round"] = un_d(::round);
        import_handlers_["trunc"] = un_d(::trunc);

        import_handlers_["powf"]   = bi_f(::powf);
        import_handlers_["fmodf"]  = bi_f(::fmodf);
        import_handlers_["atan2f"] = bi_f(::atan2f);
        import_handlers_["fminf"]  = bi_f(::fminf);
        import_handlers_["fmaxf"]  = bi_f(::fmaxf);

        import_handlers_["pow"]   = bi_d(::pow);
        import_handlers_["fmod"]  = bi_d(::fmod);
        import_handlers_["atan2"] = bi_d(::atan2);
        import_handlers_["fmin"]  = bi_d(::fmin);
        import_handlers_["fmax"]  = bi_d(::fmax);

        import_handlers_["modf"] = [this] {
            const double v = d_arg(0);
            double ip = 0;
            const double fp = ::modf(v, &ip);
            const uint32_t out = arg(2);
            if (out) write_double(out, ip);
            return ret_d(fp);
        };
        import_handlers_["modff"] = [this] {
            const float v = f_arg(0);
            float ip = 0;
            const float fp = ::modff(v, &ip);
            const uint32_t out = arg(1);
            if (out) emu_.write_u32(out, ret_f(ip));
            return ret_f(fp);
        };
        import_handlers_["ldexp"] = [this] { return ret_d(::ldexp(d_arg(0), static_cast<int>(arg(2)))); };
        import_handlers_["frexp"] = [this] {
            int e = 0;
            const double m = ::frexp(d_arg(0), &e);
            const uint32_t out = arg(2);
            if (out) emu_.write_u32(out, static_cast<uint32_t>(e));
            return ret_d(m);
        };

        // ---------------- ARM AEABI helpers -----------------------------
        // These are the ARM-specific runtime routines the compiler emits for
        // conversions and 64-bit division; without them every float-to-int cast
        // in the game lands on a stub that returns 0.
        import_handlers_["__aeabi_d2lz"] = [this] { return ret_i64(static_cast<long long>(d_arg(0))); };
        import_handlers_["__aeabi_d2ulz"] = [this] { return ret_i64(static_cast<long long>(static_cast<unsigned long long>(d_arg(0)))); };
        import_handlers_["__aeabi_f2lz"] = [this] { return ret_i64(static_cast<long long>(f_arg(0))); };
        import_handlers_["__aeabi_f2ulz"] = [this] { return ret_i64(static_cast<long long>(static_cast<unsigned long long>(f_arg(0)))); };
        import_handlers_["__aeabi_d2iz"] = [this] { return static_cast<uint32_t>(static_cast<int>(d_arg(0))); };
        import_handlers_["__aeabi_d2uiz"] = [this] { return static_cast<uint32_t>(static_cast<unsigned>(d_arg(0))); };
        import_handlers_["__aeabi_f2iz"] = [this] { return static_cast<uint32_t>(static_cast<int>(f_arg(0))); };
        import_handlers_["__aeabi_f2uiz"] = [this] { return static_cast<uint32_t>(static_cast<unsigned>(f_arg(0))); };
        import_handlers_["__aeabi_l2f"] = [this] {
            return ret_f(static_cast<float>(static_cast<long long>(d_arg(0))));
        };
        import_handlers_["__aeabi_ul2f"] = [this] {
            const uint64_t v = static_cast<uint64_t>(arg(0)) | (static_cast<uint64_t>(arg(1)) << 32);
            return ret_f(static_cast<float>(v));
        };
        import_handlers_["__aeabi_l2d"] = [this] {
            return ret_d(static_cast<double>(static_cast<long long>(d_arg(0))));
        };
        import_handlers_["__aeabi_i2f"] = [this] { return ret_f(static_cast<float>(static_cast<int32_t>(arg(0)))); };
        import_handlers_["__aeabi_ui2f"] = [this] { return ret_f(static_cast<float>(arg(0))); };
        import_handlers_["__aeabi_i2d"] = [this] { return ret_d(static_cast<double>(static_cast<int32_t>(arg(0)))); };
        import_handlers_["__aeabi_ui2d"] = [this] { return ret_d(static_cast<double>(arg(0))); };
        import_handlers_["__aeabi_f2d"] = [this] { return ret_d(static_cast<double>(f_arg(0))); };
        import_handlers_["__aeabi_d2f"] = [this] { return ret_f(static_cast<float>(d_arg(0))); };
        import_handlers_["__aeabi_idiv"] = [this] {
            const int32_t b = static_cast<int32_t>(arg(1));
            return b == 0 ? 0u : static_cast<uint32_t>(static_cast<int32_t>(arg(0)) / b);
        };
        import_handlers_["__aeabi_uidiv"] = [this] {
            const uint32_t b = arg(1);
            return b == 0 ? 0u : arg(0) / b;
        };
        import_handlers_["__aeabi_idivmod"] = [this] {
            const int32_t a = static_cast<int32_t>(arg(0));
            const int32_t b = static_cast<int32_t>(arg(1));
            if (b == 0) return 0u;
            emu_.reg_write(UC_ARM_REG_R1, static_cast<uint32_t>(a % b));
            return static_cast<uint32_t>(a / b);
        };
        import_handlers_["__aeabi_uidivmod"] = [this] {
            const uint32_t a = arg(0), b = arg(1);
            if (b == 0) return 0u;
            emu_.reg_write(UC_ARM_REG_R1, a % b);
            return a / b;
        };

        // ---------------- pthread / TLS ---------------------------------
        import_handlers_["pthread_once"] = [this] { return imp_pthread_once(); };
        import_handlers_["pthread_key_create"] = [this] { return imp_pthread_key_create(); };
        import_handlers_["pthread_getspecific"] = [this] { return imp_pthread_getspecific(); };
        import_handlers_["pthread_setspecific"] = [this] { return imp_pthread_setspecific(); };
        import_handlers_["pthread_self"] = [] { return 1u; };
        import_handlers_["pthread_equal"] = [this] {
            return arg(0) == arg(1) ? 1u : 0u;
        };
        import_handlers_["pthread_create"] = [this] {
            LOGW("pthread_create(%s): guest threads are not emulated, returning EAGAIN",
                 guest_string(arg(2)).c_str());
            return 11u;   // EAGAIN: the engine falls back to its sync path
        };
        import_handlers_["pthread_join"] = [] { return 0u; };
        import_handlers_["pthread_detach"] = [] { return 0u; };
        for (const char* n : {"pthread_mutex_init", "pthread_mutex_lock", "pthread_mutex_trylock",
                              "pthread_mutex_unlock", "pthread_mutex_destroy",
                              "pthread_mutexattr_init", "pthread_mutexattr_settype",
                              "pthread_mutexattr_destroy",
                              "pthread_cond_init", "pthread_cond_wait", "pthread_cond_timedwait",
                              "pthread_cond_signal", "pthread_cond_broadcast",
                              "pthread_cond_destroy", "pthread_condattr_init",
                              "pthread_condattr_destroy", "pthread_condattr_setclock",
                              "pthread_rwlock_init", "pthread_rwlock_rdlock",
                              "pthread_rwlock_wrlock", "pthread_rwlock_unlock",
                              "pthread_rwlock_destroy", "pthread_attr_init",
                              "pthread_attr_destroy", "pthread_attr_setdetachstate",
                              "pthread_attr_setstacksize", "pthread_attr_setschedparam",
                              "pthread_attr_getschedparam", "pthread_attr_setschedpolicy",
                              "pthread_attr_setguardsize", "pthread_setname_np"}) {
            import_handlers_[n] = [] { return 0u; };
        }

        import_handlers_["sem_init"] = [this] {
            const uint32_t sem = arg(0);
            if (sem) emu_.write_u32(sem, arg(2));
            return 0u;
        };
        import_handlers_["sem_destroy"] = [] { return 0u; };
        import_handlers_["sem_wait"] = [] { return 0u; };
        import_handlers_["sem_trywait"] = [] { return 0u; };
        import_handlers_["sem_timedwait"] = [] { return 0u; };
        import_handlers_["sem_post"] = [] { return 0u; };
        import_handlers_["sem_getvalue"] = [this] {
            const uint32_t out = arg(1);
            if (out) emu_.write_u32(out, 1);
            return 0u;
        };

        // ---------------- time / scheduling -----------------------------
        import_handlers_["gettimeofday"] = [this] { return imp_gettimeofday(); };
        import_handlers_["clock_gettime"] = [this] { return imp_clock_gettime(); };
        // POSIX 48-bit LCG. The engine seeds the course randomiser with
        // srand48 and reads lrand48, so "return 0" would pin every game to the
        // same level order.
        import_handlers_["srand48"] = [this] {
            drand48_state_ = ((static_cast<uint64_t>(arg(0)) & 0xFFFFFFFFull) << 16) | 0x330Eull;
            return 0u;
        };
        import_handlers_["lrand48"] = [this] {
            drand48_state_ = (drand48_state_ * 0x5DEECE66Dull + 0xBull) & 0xFFFFFFFFFFFFull;
            return static_cast<uint32_t>(drand48_state_ >> 17);
        };
        import_handlers_["time"] = [this] {
            const uint32_t out = arg(0);
            const uint32_t now = static_cast<uint32_t>(::time(nullptr));
            if (out) emu_.write_u32(out, now);
            return now;
        };
        import_handlers_["clock"] = [] { return static_cast<uint32_t>(::clock()); };
        import_handlers_["usleep"] = [this] {
            ::usleep(arg(0));
            return 0u;
        };
        import_handlers_["sleep"] = [this] {
            ::sleep(arg(0));
            return 0u;
        };
        import_handlers_["sched_yield"] = [] { return 0u; };

        // ---------------- file / stdio ----------------------------------
        // Every path-taking entry point goes through translate_path(), so an
        // OBB under /sdcard/Android/obb/<pkg>/ resolves to the ingested copy.
        import_handlers_["open"]    = [this] { return imp_open(); };
        import_handlers_["open64"]  = [this] { return imp_open(); };
        import_handlers_["__open_2"] = [this] { return imp_open(); };
        import_handlers_["openat"] = [this] {
            std::string p = translate_path(guest_string(arg(1)), "openat");
            const bool cpuinfo = is_proc_cpuinfo(p) && !virtual_cpuinfo_.empty();
            if (cpuinfo) p = virtual_cpuinfo_;
            const int fd = ::openat(cpuinfo ? AT_FDCWD : static_cast<int>(arg(0)),
                                    p.c_str(),
                                    cpuinfo ? O_RDONLY : static_cast<int>(arg(2)),
                                    static_cast<int>(arg(3)));
            if (fd < 0) return static_cast<uint32_t>(-1);
            const uint32_t g = next_handle_++;
            fd_map_[g] = fd;
            return g;
        };
        import_handlers_["close"]   = [this] { return imp_close(); };
        import_handlers_["read"]    = [this] { return imp_read(); };
        import_handlers_["write"]   = [this] { return imp_write(); };
        import_handlers_["lseek"]   = [this] { return imp_lseek(); };
        import_handlers_["lseek64"] = [this] { return imp_lseek(); };

        // C++ runtime allocation. An unresolved operator new returns 0 and the
        // first virtual call through the resulting object jumps to PC=0, which
        // is exactly what a legacy engine does on its very first frame.
        import_handlers_["_Znwj"]  = [this] { return imp_malloc(); };
        import_handlers_["_Znaj"]  = [this] { return imp_malloc(); };
        import_handlers_["_ZnwjRKSt9nothrow_t"] = [this] { return imp_malloc(); };
        import_handlers_["_ZnajRKSt9nothrow_t"] = [this] { return imp_malloc(); };
        import_handlers_["_ZdlPv"] = [this] { return imp_free(); };
        import_handlers_["_ZdaPv"] = [this] { return imp_free(); };

        // strtok walks guest memory and NUL-terminates in place; handing the
        // guest a constant 0 makes every config parse fail and the engine then
        // jumps through a null table entry.
        import_handlers_["strtok"] = [this] {
            uint32_t at = arg(0);
            if (!at) at = strtok_last_;
            if (!at) return 0u;
            const std::string delims = guest_string(arg(1));
            auto is_delim = [&delims](char c) {
                return c != '\0' && delims.find(c) != std::string::npos;
            };
            // Skip leading delimiters.
            char c = 0;
            while (emu_.read(at, &c, 1) && c != '\0' && is_delim(c)) ++at;
            if (!emu_.read(at, &c, 1) || c == '\0') {
                strtok_last_ = 0;
                return 0u;
            }
            const uint32_t token = at;
            while (emu_.read(at, &c, 1) && c != '\0') {
                if (is_delim(c)) {
                    const char zero = '\0';
                    emu_.write(at, &zero, 1);
                    strtok_last_ = at + 1;
                    return token;
                }
                ++at;
            }
            strtok_last_ = 0;
            return token;
        };
        import_handlers_["strtok_r"] = [this] {
            const uint32_t save = arg(2);
            uint32_t at = arg(0);
            if (!at && save) emu_.read(save, &at, sizeof at);
            if (!at) return 0u;
            const std::string delims = guest_string(arg(1));
            auto is_delim = [&delims](char c) {
                return c != '\0' && delims.find(c) != std::string::npos;
            };
            char c = 0;
            while (emu_.read(at, &c, 1) && c != '\0' && is_delim(c)) ++at;
            if (!emu_.read(at, &c, 1) || c == '\0') {
                if (save) { const uint32_t z = 0; emu_.write(save, &z, sizeof z); }
                return 0u;
            }
            const uint32_t token = at;
            while (emu_.read(at, &c, 1) && c != '\0') {
                if (is_delim(c)) {
                    const char zero = '\0';
                    emu_.write(at, &zero, 1);
                    if (save) { const uint32_t next = at + 1; emu_.write(save, &next, sizeof next); }
                    return token;
                }
                ++at;
            }
            if (save) { const uint32_t z = 0; emu_.write(save, &z, sizeof z); }
            return token;
        };

        import_handlers_["strerror"] = [this] {
            const char* s = ::strerror(static_cast<int>(arg(0)));
            return s ? guest_str(s) : 0u;
        };

        // printf-family formatting has to be rewritten against guest varargs:
        // a host vsnprintf() would follow 32-bit guest pointers as if they were
        // host addresses. sprintf/snprintf are what Tcl uses for number and
        // list formatting during startup.
        import_handlers_["sprintf"] = [this] {
            const uint32_t dst = arg(0);
            if (!dst) return 0u;
            const std::string fmt = safe_guest_string(arg(1), "sprintf");
            uint32_t sp = 0;
            emu_.reg_read(UC_ARM_REG_SP, sp);
            size_t index = 0;
            auto next = [&]() -> uint32_t {
                uint32_t v = 0;
                if (index == 0) v = arg(2);
                else if (index == 1) v = arg(3);
                else v = emu_.read_u32(sp + static_cast<uint32_t>((index - 2) * 4));
                ++index;
                return v;
            };
            const std::string out = format_guest(fmt, next);
            emu_.write(dst, out.c_str(), out.size() + 1);
            return static_cast<uint32_t>(out.size());
        };
        import_handlers_["snprintf"] = [this] {
            const uint32_t dst = arg(0);
            const uint32_t cap = arg(1);
            if (!dst || cap == 0) return 0u;
            const std::string fmt = safe_guest_string(arg(2), "snprintf");
            uint32_t sp = 0;
            emu_.reg_read(UC_ARM_REG_SP, sp);
            size_t index = 0;
            auto next = [&]() -> uint32_t {
                uint32_t v = 0;
                if (index == 0) v = arg(3);
                else v = emu_.read_u32(sp + static_cast<uint32_t>((index - 1) * 4));
                ++index;
                return v;
            };
            std::string out = format_guest(fmt, next);
            const uint32_t written = static_cast<uint32_t>(
                    std::min<size_t>(out.size(), static_cast<size_t>(cap) - 1));
            emu_.write(dst, out.c_str(), written);
            const uint8_t zero = 0;
            emu_.write(dst + written, &zero, 1);
            return static_cast<uint32_t>(out.size());
        };
        import_handlers_["getcwd"] = [this] {
            const uint32_t buf = arg(0);
            const size_t size = static_cast<size_t>(arg(1));
            if (!buf || size < 2) return 0u;
            const std::string cwd = guest_cwd_.empty() ? "/" : guest_cwd_;
            const size_t n = std::min(cwd.size() + 1, size);
            emu_.write(buf, cwd.c_str(), n);
            if (n < size) {
                const uint8_t zero = 0;
                emu_.write(buf + static_cast<uint32_t>(n), &zero, 1);
            }
            return buf;
        };
        import_handlers_["chdir"] = [this] {
            const std::string raw = safe_guest_string(arg(0), "chdir");
            const std::string path = translate_path(raw, "chdir");
            struct stat st = {};
            if (::stat(path.c_str(), &st) != 0 || !S_ISDIR(st.st_mode)) {
                LOGW("chdir(%s) -> %s failed", raw.c_str(), path.c_str());
                return static_cast<uint32_t>(-1);
            }
            guest_cwd_ = path;
            LOGI("chdir(%s) -> %s", raw.c_str(), guest_cwd_.c_str());
            return 0u;
        };

        // printf family. The format string and every %s argument come from
        // guest memory, so each one is rewritten before the host logger sees
        // it. This is also the only way to surface a Tcl/engine error message
        // that would otherwise be lost on the guest's stderr.
        auto guest_varargs = [this](size_t first_reg, uint32_t& sp) {
            emu_.reg_read(UC_ARM_REG_SP, sp);
            return [this, first_reg, &sp, index = size_t{0}]() mutable -> uint32_t {
                uint32_t v = 0;
                if (first_reg + index < 4) {
                    v = arg(static_cast<int>(first_reg + index));
                } else {
                    v = emu_.read_u32(sp + static_cast<uint32_t>(
                            (first_reg + index - 4) * 4));
                }
                ++index;
                return v;
            };
        };
        import_handlers_["printf"] = [this, guest_varargs] {
            uint32_t sp = 0;
            const std::string fmt = safe_guest_string(arg(0), "printf");
            const std::string out = format_guest(fmt, guest_varargs(1, sp));
            LOGI("guest printf: %s", out.c_str());
            return static_cast<uint32_t>(out.size());
        };
        import_handlers_["fprintf"] = [this, guest_varargs] {
            uint32_t sp = 0;
            const std::string fmt = safe_guest_string(arg(1), "fprintf");
            const std::string out = format_guest(fmt, guest_varargs(2, sp));
            LOGW("guest fprintf: %s", out.c_str());
            return static_cast<uint32_t>(out.size());
        };
        auto guest_va_list = [this](uint32_t va) {
            return [this, va, index = size_t{0}]() mutable -> uint32_t {
                const uint32_t v = emu_.read_u32(va + static_cast<uint32_t>(index * 4));
                ++index;
                return v;
            };
        };
        import_handlers_["vprintf"] = [this, guest_va_list] {
            const std::string fmt = safe_guest_string(arg(0), "vprintf");
            const std::string out = format_guest(fmt, guest_va_list(arg(1)));
            LOGI("guest vprintf: %s", out.c_str());
            return static_cast<uint32_t>(out.size());
        };
        import_handlers_["vfprintf"] = [this, guest_va_list] {
            const std::string fmt = safe_guest_string(arg(1), "vfprintf");
            const std::string out = format_guest(fmt, guest_va_list(arg(2)));
            LOGW("guest vfprintf: %s", out.c_str());
            return static_cast<uint32_t>(out.size());
        };
        import_handlers_["asprintf"] = [this, guest_varargs] {
            const uint32_t outpp = arg(0);
            if (!outpp) return static_cast<uint32_t>(-1);
            uint32_t sp = 0;
            const std::string fmt = safe_guest_string(arg(1), "asprintf");
            const std::string out = format_guest(fmt, guest_varargs(2, sp));
            const uint32_t at = guest_alloc(out.size() + 1);
            if (!at) return static_cast<uint32_t>(-1);
            emu_.write(at, out.c_str(), out.size() + 1);
            emu_.write_u32(outpp, at);
            return static_cast<uint32_t>(out.size());
        };
        import_handlers_["fcntl"] = [this] {
            const int fd = host_fd(arg(0));
            if (fd < 0) return static_cast<uint32_t>(-1);
            return static_cast<uint32_t>(::fcntl(fd, static_cast<int>(arg(1)),
                                                 static_cast<int>(arg(2))));
        };
        import_handlers_["isatty"] = [this] {
            const int fd = host_fd(arg(0));
            return fd < 0 ? 0u : static_cast<uint32_t>(::isatty(fd));
        };
        import_handlers_["putchar"] = [this] { return static_cast<uint32_t>(arg(0)); };
        import_handlers_["puts"] = [this] {
            LOGI("guest: %s", guest_string(arg(0)).c_str());
            return 0u;
        };
        import_handlers_["fputc"] = [this] { return static_cast<uint32_t>(arg(0)); };
        import_handlers_["perror"] = [this] {
            LOGW("guest perror: %s", guest_string(arg(0)).c_str());
            return 0u;
        };
        import_handlers_["fstat"]   = [this] { return imp_stat_common(true); };
        import_handlers_["stat"]    = [this] { return imp_stat_common(false); };
        import_handlers_["stat64"]  = [this] { return imp_stat_common(false); };
        import_handlers_["lstat"]   = [this] { return imp_stat_common(false); };
        import_handlers_["lstat64"] = [this] { return imp_stat_common(false); };
        import_handlers_["statfs"]  = [this] { return imp_statfs(); };
        import_handlers_["__statfs"] = [this] { return imp_statfs(); };
        import_handlers_["access"]  = [this] {
            const std::string p = translate_path(guest_string(arg(0)), "access");
            if (::access(p.c_str(), static_cast<int>(arg(1))) == 0) return 0u;
            set_guest_errno_from_host();
            return static_cast<uint32_t>(-1);
        };
        import_handlers_["faccessat"] = [this] {
            const std::string p = translate_path(guest_string(arg(1)), "faccessat");
            if (::faccessat(static_cast<int>(arg(0)), p.c_str(),
                            static_cast<int>(arg(2)), 0) == 0) return 0u;
            set_guest_errno_from_host();
            return static_cast<uint32_t>(-1);
        };
        import_handlers_["unlink"] = [this] {
            return ::unlink(translate_path(guest_string(arg(0)), "unlink").c_str()) == 0
                       ? 0u : static_cast<uint32_t>(-1);
        };
        import_handlers_["rename"] = [this] {
            return ::rename(translate_path(guest_string(arg(0)), "rename").c_str(),
                            translate_path(guest_string(arg(1)), "rename").c_str()) == 0
                       ? 0u : static_cast<uint32_t>(-1);
        };
        import_handlers_["mkdir"] = [this] {
            return ::mkdir(translate_path(guest_string(arg(0)), "mkdir").c_str(),
                           static_cast<mode_t>(arg(1))) == 0
                       ? 0u : static_cast<uint32_t>(-1);
        };
        import_handlers_["rmdir"] = [this] {
            return ::rmdir(translate_path(guest_string(arg(0)), "rmdir").c_str()) == 0
                       ? 0u : static_cast<uint32_t>(-1);
        };
        import_handlers_["chmod"] = [this] {
            return ::chmod(translate_path(guest_string(arg(0)), "chmod").c_str(),
                           static_cast<mode_t>(arg(1))) == 0
                       ? 0u : static_cast<uint32_t>(-1);
        };
        import_handlers_["ftruncate"] = [this] {
            const int fd = host_fd(arg(0));
            if (fd < 0) return static_cast<uint32_t>(-1);
            return ::ftruncate(fd, static_cast<off_t>(arg(1))) == 0 ? 0u
                                                                   : static_cast<uint32_t>(-1);
        };
        import_handlers_["fsync"] = [this] {
            const int fd = host_fd(arg(0));
            if (fd < 0) return static_cast<uint32_t>(-1);
            return ::fsync(fd) == 0 ? 0u : static_cast<uint32_t>(-1);
        };
        import_handlers_["opendir"]  = [this] { return imp_opendir(); };
        import_handlers_["readdir"]  = [this] { return imp_readdir(); };
        import_handlers_["closedir"] = [this] { return imp_closedir(); };

        import_handlers_["fopen"]  = [this] { return imp_fopen(); };
        import_handlers_["fopen64"] = [this] { return imp_fopen(); };
        import_handlers_["fread"]  = [this] { return imp_fread(); };
        import_handlers_["fwrite"] = [this] { return imp_fwrite(); };
        import_handlers_["fclose"] = [this] {
            FILE* f = host_file(arg(0));
            if (!f) return static_cast<uint32_t>(-1);
            ::fclose(f);
            file_map_.erase(arg(0));
            return 0u;
        };
        import_handlers_["fseek"] = [this] {
            FILE* f = host_file(arg(0));
            if (!f) return static_cast<uint32_t>(-1);
            return ::fseek(f, static_cast<long>(arg(1)), static_cast<int>(arg(2))) == 0
                       ? 0u : static_cast<uint32_t>(-1);
        };
        import_handlers_["ftell"] = [this] {
            FILE* f = host_file(arg(0));
            return f ? static_cast<uint32_t>(::ftell(f)) : static_cast<uint32_t>(-1);
        };
        import_handlers_["feof"] = [this] {
            FILE* f = host_file(arg(0));
            return f ? static_cast<uint32_t>(::feof(f)) : 0u;
        };
        import_handlers_["fflush"] = [this] {
            FILE* f = host_file(arg(0));
            return ::fflush(f) == 0 ? 0u : static_cast<uint32_t>(-1);
        };
        import_handlers_["fgetc"] = [this] {
            FILE* f = host_file(arg(0));
            return f ? static_cast<uint32_t>(::fgetc(f)) : static_cast<uint32_t>(-1);
        };
        import_handlers_["putc"] = [this] {
            FILE* f = host_file(arg(1));
            return f ? static_cast<uint32_t>(::putc(static_cast<int>(arg(0)), f))
                     : static_cast<uint32_t>(-1);
        };
        import_handlers_["fgets"] = [this] {
            FILE* f = host_file(arg(2));
            if (!f) return 0u;
            char tmp[1024];
            const int n = static_cast<int>(std::min<uint32_t>(arg(1), sizeof tmp));
            if (!::fgets(tmp, n, f)) return 0u;
            emu_.write(arg(0), tmp, std::strlen(tmp) + 1);
            return arg(0);
        };
        import_handlers_["fileno"] = [this] {
            FILE* f = host_file(arg(0));
            if (!f) return static_cast<uint32_t>(-1);
            const uint32_t g = next_handle_++;
            fd_map_[g] = ::fileno(f);
            return g;
        };

        // ---------------- misc libc -------------------------------------
        import_handlers_["__errno"] = [this] { return errno_cell_; };
        import_handlers_["__stack_chk_fail"] = [this] {
            LOGE("guest stack protector tripped (__stack_chk_fail) - stopping");
            emu_.reg_write(UC_ARM_REG_PC, kReturnSentinel);
            redirected_ = true;
            return 0u;
        };
        import_handlers_["abort"] = [this] { return imp_abort(); };
        import_handlers_["exit"] = [this] { return imp_abort(); };
        import_handlers_["_exit"] = [this] { return imp_abort(); };
        import_handlers_["__assert2"] = [this] { return imp_assert2(); };
        import_handlers_["__assert"] = [this] { return imp_assert2(); };
        import_handlers_["sysconf"] = [this] { return imp_sysconf(); };
        // ARMv7 capability words. A guest that probes AT_HWCAP instead of
        // parsing cpuinfo must reach the same NEON-capable conclusion on
        // Snapdragon, Dimensity, Exynos, Tensor, Kirin and Unisoc parts.
        import_handlers_["getauxval"] = [this] {
            switch (static_cast<int>(arg(0))) {
                case 16: return zai::cpu::guest_hwcap();    // AT_HWCAP
                case 26: return zai::cpu::guest_hwcap2();   // AT_HWCAP2
                default: return 0u;                          // AT_PAGESZ etc.
            }
        };
        import_handlers_["getenv"] = [this] {
            LOGI("getenv(%s) -> (unset)", safe_guest_string(arg(0), "getenv").c_str());
            return 0u;
        };
        import_handlers_["setlocale"] = [this] {
            if (!arg(1)) return guest_str("C");
            const std::string requested = safe_guest_string(arg(1), "setlocale");
            LOGI("setlocale(%d, \"%s\") -> C", static_cast<int>(arg(0)),
                 requested.c_str());
            return guest_str(requested.empty() ? "C" : requested);
        };
        import_handlers_["setenv"] = [] { return 0u; };
        import_handlers_["getpid"] = [] { return static_cast<uint32_t>(::getpid()); };
        import_handlers_["getegid"] = [] { return static_cast<uint32_t>(::getegid()); };
        import_handlers_["getuid"] = [] { return static_cast<uint32_t>(::getuid()); };
        import_handlers_["prctl"] = [] { return 0u; };
        import_handlers_["syscall"] = [] { return static_cast<uint32_t>(-1); };
        import_handlers_["system"] = [] { return static_cast<uint32_t>(-1); };
        import_handlers_["waitpid"] = [] { return static_cast<uint32_t>(-1); };
        import_handlers_["dup2"] = [] { return static_cast<uint32_t>(-1); };
        import_handlers_["setjmp"] = [] { return 0u; };
        import_handlers_["sigsetjmp"] = [] { return 0u; };
        import_handlers_["__aeabi_atexit"] = [] { return 0u; };
        import_handlers_["fwide"] = [] { return 0u; };
        import_handlers_["strncasecmp"] = [this] {
            const std::string a = safe_guest_string(arg(0), "strncasecmp");
            const std::string b = safe_guest_string(arg(1), "strncasecmp");
            return static_cast<uint32_t>(::strncasecmp(a.c_str(), b.c_str(), arg(2)));
        };
        import_handlers_["strpbrk"] = [this] {
            const uint32_t s = arg(0);
            const std::string hay = safe_guest_string(s, "strpbrk");
            const std::string set = safe_guest_string(arg(1), "strpbrk");
            const size_t at = hay.find_first_of(set);
            return at == std::string::npos ? 0u : s + static_cast<uint32_t>(at);
        };
        import_handlers_["memrchr"] = [this] {
            uint32_t base = arg(0);
            const uint8_t want = static_cast<uint8_t>(arg(1) & 0xFFu);
            uint32_t n = arg(2);
            while (n--) {
                uint8_t c = 0;
                if (!emu_.read(base + n, &c, 1)) return 0u;
                if (c == want) return base + n;
            }
            return 0u;
        };
        import_handlers_["btowc"] = [this] { return arg(0) & 0xFFu; };
        import_handlers_["strtok_r"] = [this] {
            const uint32_t savep = arg(2);
            const std::string delim = guest_string(arg(1));
            uint32_t cur = arg(0) ? arg(0) : (savep ? emu_.read_u32(savep) : 0);
            if (!cur) return 0u;

            auto is_delim = [&](uint8_t c) {
                return c != 0 && delim.find(static_cast<char>(c)) != std::string::npos;
            };

            for (;;) {                      // skip leading delimiters
                uint8_t c = 0;
                if (!emu_.read(cur, &c, 1) || c == 0) {
                    if (savep) emu_.write_u32(savep, 0);
                    return 0u;
                }
                if (!is_delim(c)) break;
                ++cur;
            }

            const uint32_t start = cur;
            for (;;) {                      // find the end of the token
                uint8_t c = 0;
                if (!emu_.read(cur, &c, 1) || c == 0) {
                    if (savep) emu_.write_u32(savep, 0);
                    return start;
                }
                if (is_delim(c)) {
                    const uint8_t zero = 0;
                    emu_.write(cur, &zero, 1);
                    if (savep) emu_.write_u32(savep, cur + 1);
                    return start;
                }
                ++cur;
            }
        };
        for (const char* n : {"isalpha", "isprint", "isgraph", "isdigit", "isspace",
                              "isalnum", "isupper", "islower", "isxdigit", "ispunct",
                              "iscntrl", "toupper", "tolower"}) {
            const std::string name = n;
            import_handlers_[n] = [this, name] {
                const int c = static_cast<int>(arg(0) & 0xFFu);
                if (name == "toupper") return static_cast<uint32_t>(::toupper(c));
                if (name == "tolower") return static_cast<uint32_t>(::tolower(c));
                if (name == "isalpha") return ::isalpha(c) ? 1u : 0u;
                if (name == "isprint") return ::isprint(c) ? 1u : 0u;
                if (name == "isgraph") return ::isgraph(c) ? 1u : 0u;
                if (name == "isdigit") return ::isdigit(c) ? 1u : 0u;
                if (name == "isspace") return ::isspace(c) ? 1u : 0u;
                if (name == "isalnum") return ::isalnum(c) ? 1u : 0u;
                if (name == "isupper") return ::isupper(c) ? 1u : 0u;
                if (name == "islower") return ::islower(c) ? 1u : 0u;
                if (name == "isxdigit") return ::isxdigit(c) ? 1u : 0u;
                if (name == "ispunct") return ::ispunct(c) ? 1u : 0u;
                return ::iscntrl(c) ? 1u : 0u;
            };
        }
        import_handlers_["getauxval"] = [this] {
            return arg(0) == 6 ? 4096u : 0u;   // AT_PAGESZ
        };
        import_handlers_["__cxa_atexit"] = [] { return 0u; };
        import_handlers_["__cxa_finalize"] = [] { return 0u; };
        import_handlers_["dlopen"] = [this] {
            const std::string name = guest_string(arg(0));
            const uint32_t handle = guest_dlopen(name);
            return handle;
        };
        import_handlers_["dlsym"] = [this] {
            return guest_dlsym(arg(0), guest_string(arg(1)));
        };
        import_handlers_["dlclose"] = [] { return 0u; };   // objects stay mapped
        import_handlers_["dlerror"] = [] { return 0u; };
        import_handlers_["dladdr"] = [] { return 0u; };

        // ---------------- memory mapping -------------------------------
        import_handlers_["mmap"]   = [this] { return imp_mmap(false); };
        import_handlers_["mmap64"] = [this] { return imp_mmap(true); };
        import_handlers_["munmap"] = [this] { return imp_munmap(); };
        import_handlers_["mprotect"] = [] { return 0u; };
        import_handlers_["msync"] = [] { return 0u; };
        import_handlers_["madvise"] = [] { return 0u; };

        // ---------------- Android NDK surface glue ----------------------
        import_handlers_["ANativeWindow_fromSurface"] = [this] {
            jobject surface = static_cast<jobject>(from_guest_ref(arg(1)));
            if (!surface) return 0u;
            ANativeWindow* window = ANativeWindow_fromSurface(env_, surface);
            if (!window) {
                LOGE("ANativeWindow_fromSurface failed");
                return 0u;
            }
            LOGI("ANativeWindow_fromSurface -> %p", window);
            return to_guest_ref(window);   // same handle EGL will map back
        };
        import_handlers_["ANativeWindow_acquire"] = [] { return 0u; };
        import_handlers_["ANativeWindow_release"] = [] { return 0u; };
        import_handlers_["ANativeWindow_getWidth"] = [this] {
            ANativeWindow* w = static_cast<ANativeWindow*>(from_guest_ref(arg(0)));
            if (!w) w = host_window_;
            return w ? static_cast<uint32_t>(ANativeWindow_getWidth(w)) : 0u;
        };
        import_handlers_["ANativeWindow_getHeight"] = [this] {
            ANativeWindow* w = static_cast<ANativeWindow*>(from_guest_ref(arg(0)));
            if (!w) w = host_window_;
            return w ? static_cast<uint32_t>(ANativeWindow_getHeight(w)) : 0u;
        };
        import_handlers_["ANativeWindow_getFormat"] = [this] {
            ANativeWindow* w = static_cast<ANativeWindow*>(from_guest_ref(arg(0)));
            if (!w) w = host_window_;
            return w ? static_cast<uint32_t>(ANativeWindow_getFormat(w)) : 0u;
        };
        import_handlers_["ANativeWindow_setBuffersGeometry"] = [this] {
            ANativeWindow* w = static_cast<ANativeWindow*>(from_guest_ref(arg(0)));
            if (!w) w = host_window_;
            if (!w) return static_cast<uint32_t>(-1);
            return static_cast<uint32_t>(ANativeWindow_setBuffersGeometry(
                w, static_cast<int32_t>(arg(1)), static_cast<int32_t>(arg(2)),
                static_cast<int32_t>(arg(3))));
        };
        import_handlers_["__gnu_Unwind_Find_exidx"] = [] { return 0u; };
        import_handlers_["fork"] = [] { return static_cast<uint32_t>(-1); };
        import_handlers_["execv"] = [] { return static_cast<uint32_t>(-1); };
        import_handlers_["execl"] = [] { return static_cast<uint32_t>(-1); };
        for (const char* n : {"sigaction", "sigprocmask", "sigemptyset", "sigfillset",
                              "sigaddset", "sigdelset", "sigismember", "signal",
                              "pthread_sigmask", "setpriority", "nice", "setbuf",
                              "pthread_attr_setstack", "pthread_getattr_np",
                              "pthread_getschedparam", "pthread_exit", "gmtime_r",
                              "localtime_r"}) {
            import_handlers_[n] = [] { return 0u; };
        }
        import_handlers_["getpwuid_r"] = [] { return static_cast<uint32_t>(-1); };
        for (const char* n : {"AndroidBitmap_getInfo", "AndroidBitmap_lockPixels",
                              "AndroidBitmap_unlockPixels"}) {
            import_handlers_[n] = [] { return static_cast<uint32_t>(-1); };
        }

        // ---------------- liblog ----------------------------------------
        import_handlers_["__android_log_print"] = [this] { return imp_log_print(); };
        import_handlers_["__android_log_write"] = [this] {
            const std::string text = guest_string(arg(2));
            __android_log_print(static_cast<int>(arg(0)),
                                guest_string(arg(1)).c_str(), "%s", text.c_str());
            return static_cast<uint32_t>(text.size());
        };
        import_handlers_["__android_log_assert"] = [this] {
            LOGE("guest assert: %s", guest_string(arg(2)).c_str());
            return 0u;
        };
        import_handlers_["__android_log_vprint"] = [this] { return imp_log_print(); };

        install_graphics_handlers();
    }

    // malloc'd copy of a guest string, owned by the guest heap.
    uint32_t strdup_into_guest(uint32_t src, size_t cap) {
        const std::string s = guest_string(src);
        const size_t n = std::min(s.size(), cap == SIZE_MAX ? s.size() : cap);
        const uint32_t at = guest_alloc(n + 1);
        if (!at) return 0;
        emu_.write(at, s.data(), n);
        emu_.write_u32(at + static_cast<uint32_t>(n), 0);
        return at;
    }

    // =====================================================================
    // Bionic / libm / liblog import bridging
    //
    // These handlers run as host code but every argument and result is a
    // 32-bit guest address. Two consequences drive the implementation:
    //   * allocation is served from the emulated heap, never from host
    //     malloc(), because a 64-bit host pointer cannot be returned in R0;
    //   * the memory routines copy through the emulator's address space
    //     instead of calling the host memcpy() on guest addresses.
    // =====================================================================
    uint32_t guest_alloc(size_t n) {
        if (n == 0) n = 1;
        const uint32_t p = emu_.alloc(n, 8);
        if (p) guest_allocs_[p] = n;
        return p;
    }

    void copy_guest(uint32_t src, uint32_t dst, uint32_t n) {
        uint8_t buf[4096];
        while (n > 0) {
            const size_t chunk = std::min<size_t>(n, sizeof buf);
            if (!emu_.read(src, buf, chunk)) {
                LOGW("memcpy: unreadable source %08x", src);
                return;
            }
            if (!emu_.write(dst, buf, chunk)) {
                LOGW("memcpy: unwritable destination %08x", dst);
                return;
            }
            src += static_cast<uint32_t>(chunk);
            dst += static_cast<uint32_t>(chunk);
            n -= static_cast<uint32_t>(chunk);
        }
    }

    size_t guest_strlen(uint32_t addr) {
        constexpr size_t kCap = 1u << 20;
        uint8_t buf[256];
        size_t len = 0;
        while (len < kCap) {
            const size_t chunk = std::min<size_t>(sizeof buf, kCap - len);
            if (!emu_.read(addr + static_cast<uint32_t>(len), buf, chunk)) break;
            for (size_t i = 0; i < chunk; ++i) {
                if (buf[i] == 0) return len + i;
            }
            len += chunk;
        }
        return len;
    }

    uint32_t imp_malloc() { return guest_alloc(arg(0)); }

    uint32_t imp_calloc() {
        const uint64_t total = static_cast<uint64_t>(arg(0)) * static_cast<uint64_t>(arg(1));
        if (total > 0x7FFFFFFFu) return 0;
        // The heap arena is mapped zeroed and never recycled, so fresh
        // allocations are already content-free.
        return guest_alloc(static_cast<size_t>(total));
    }

    uint32_t imp_realloc() {
        const uint32_t old_p = arg(0);
        const uint32_t size = arg(1);
        if (!old_p) return guest_alloc(size);

        auto it = guest_allocs_.find(old_p);
        const size_t old_size = it == guest_allocs_.end() ? 0 : it->second;
        const uint32_t new_p = guest_alloc(size);
        if (!new_p) return 0;
        copy_guest(old_p, new_p, static_cast<uint32_t>(std::min<size_t>(old_size, size)));
        if (it != guest_allocs_.end()) {
            emu_.release(old_p, it->second);
            guest_allocs_.erase(it);
        }
        return new_p;
    }

    uint32_t imp_free() {
        const uint32_t p = arg(0);
        auto it = guest_allocs_.find(p);
        if (it == guest_allocs_.end()) return 0;   // not ours: ignore
        emu_.release(it->first, it->second);
        guest_allocs_.erase(it);
        return 0;
    }

    uint32_t imp_memcpy() {
        const uint32_t dst = arg(0);
        copy_guest(arg(1), dst, arg(2));
        return dst;
    }

    // memmove semantics matter: the ARM32 `__aeabi_memmove` call sites rely on
    // overlapping regions being handled correctly.
    uint32_t imp_memmove() {
        const uint32_t dst = arg(0), src = arg(1), n = arg(2);
        if (overlaps(dst, src, n)) {
            std::vector<uint8_t> tmp(n);
            if (n && !emu_.read(src, tmp.data(), n)) return dst;
            if (n) emu_.write(dst, tmp.data(), n);
        } else {
            copy_guest(src, dst, n);
        }
        return dst;
    }

    static bool overlaps(uint32_t a, uint32_t b, uint32_t n) {
        if (n == 0) return false;
        return a < b + n && b < a + n;
    }

    uint32_t imp_memset() {
        const uint32_t dst = arg(0);
        const uint8_t c = static_cast<uint8_t>(arg(1) & 0xFFu);
        uint32_t n = arg(2);
        uint8_t buf[1024];
        std::memset(buf, c, sizeof buf);
        uint32_t at = dst;
        while (n > 0) {
            const size_t chunk = std::min<size_t>(n, sizeof buf);
            if (!emu_.write(at, buf, chunk)) break;
            at += static_cast<uint32_t>(chunk);
            n -= static_cast<uint32_t>(chunk);
        }
        return dst;
    }

    // __aeabi_memset(dst, n, c) - note the swapped n/c order versus memset().
    uint32_t imp_aeabi_memset() {
        uint32_t dst = arg(0), n = arg(1);
        const uint8_t c = static_cast<uint8_t>(arg(2) & 0xFFu);
        uint8_t buf[1024];
        std::memset(buf, c, sizeof buf);
        const uint32_t start = dst;
        while (n > 0) {
            const size_t chunk = std::min<size_t>(n, sizeof buf);
            if (!emu_.write(dst, buf, chunk)) break;
            dst += static_cast<uint32_t>(chunk);
            n -= static_cast<uint32_t>(chunk);
        }
        return start;
    }

    uint32_t imp_memclr() {
        uint32_t dst = arg(0), n = arg(1);
        const uint32_t start = dst;
        uint8_t zero[1024] = {};
        while (n > 0) {
            const size_t chunk = std::min<size_t>(n, sizeof zero);
            if (!emu_.write(dst, zero, chunk)) break;
            dst += static_cast<uint32_t>(chunk);
            n -= static_cast<uint32_t>(chunk);
        }
        return start;
    }

    uint32_t imp_memcmp() {
        uint32_t a = arg(0), b = arg(1), n = arg(2);
        uint8_t ba[512], bb[512];
        while (n > 0) {
            const size_t chunk = std::min<size_t>(n, sizeof ba);
            if (!emu_.read(a, ba, chunk) || !emu_.read(b, bb, chunk)) return 0;
            const int r = std::memcmp(ba, bb, chunk);
            if (r) return static_cast<uint32_t>(r);
            a += static_cast<uint32_t>(chunk);
            b += static_cast<uint32_t>(chunk);
            n -= static_cast<uint32_t>(chunk);
        }
        return 0;
    }

    uint32_t imp_strlen() {
        const uint32_t a = arg(0);
        uint8_t probe = 0;
        if (!a || !emu_.read(a, &probe, 1)) {
            warn_guest_pointer("strlen", a);
            return 0u;
        }
        return static_cast<uint32_t>(guest_strlen(a));
    }

    uint32_t imp_strcpy() {
        const uint32_t dst = arg(0), src = arg(1);
        const size_t len = guest_strlen(src);
        std::vector<uint8_t> buf(len + 1, 0);
        if (len && !emu_.read(src, buf.data(), len)) return dst;
        emu_.write(dst, buf.data(), buf.size());
        return dst;
    }

    uint32_t imp_strcmp() {
        const std::string a = safe_guest_string(arg(0), "strcmp");
        const std::string b = safe_guest_string(arg(1), "strcmp");
        return static_cast<uint32_t>(std::strcmp(a.c_str(), b.c_str()));
    }

    static uint64_t read_u64(const std::function<uint32_t()>& next) {
        const uint64_t lo = next();
        const uint64_t hi = next();
        return lo | (hi << 32);
    }

    static double read_double(const std::function<uint32_t()>& next) {
        const uint64_t bits = read_u64(next);
        double d = 0;
        std::memcpy(&d, &bits, sizeof d);
        return d;
    }

    // Rewrites a guest format string against guest varargs, so the host logger
    // never sees a 32-bit guest pointer.
    std::string format_guest(const std::string& fmt,
                             const std::function<uint32_t()>& next) {
        std::string out;
        char tmp[512];

        for (size_t i = 0; i < fmt.size(); ++i) {
            if (fmt[i] != '%') { out.push_back(fmt[i]); continue; }
            if (i + 1 >= fmt.size()) { out.push_back('%'); break; }
            if (fmt[i + 1] == '%') { out.push_back('%'); ++i; continue; }

            size_t j = i + 1;
            std::string spec = "%";
            while (j < fmt.size() && std::strchr("-+ #0", fmt[j]) != nullptr) {
                spec.push_back(fmt[j++]);
            }
            while (j < fmt.size() && std::isdigit(static_cast<unsigned char>(fmt[j]))) {
                spec.push_back(fmt[j++]);
            }
            if (j < fmt.size() && fmt[j] == '.') {
                spec.push_back(fmt[j++]);
                while (j < fmt.size() && std::isdigit(static_cast<unsigned char>(fmt[j]))) {
                    spec.push_back(fmt[j++]);
                }
            }
            std::string len;
            while (j < fmt.size() && std::strchr("hljztL", fmt[j]) != nullptr) {
                len.push_back(fmt[j]);
                spec.push_back(fmt[j]);
                ++j;
            }
            if (j >= fmt.size()) break;

            const char conv = fmt[j];
            spec.push_back(conv);
            const bool wide = len.find("ll") != std::string::npos;

            switch (conv) {
                case 's': {
                    const uint32_t p = next();
                    const std::string s = p ? guest_string(p) : std::string("(null)");
                    snprintf(tmp, sizeof tmp, spec.c_str(), s.c_str());
                    out += tmp;
                    break;
                }
                case 'd': case 'i':
                    if (wide) {
                        snprintf(tmp, sizeof tmp, spec.c_str(),
                                 static_cast<long long>(read_u64(next)));
                    } else {
                        snprintf(tmp, sizeof tmp, spec.c_str(), static_cast<int>(next()));
                    }
                    out += tmp;
                    break;
                case 'u': case 'o': case 'x': case 'X':
                    if (wide) {
                        snprintf(tmp, sizeof tmp, spec.c_str(),
                                 static_cast<unsigned long long>(read_u64(next)));
                    } else {
                        snprintf(tmp, sizeof tmp, spec.c_str(), static_cast<unsigned>(next()));
                    }
                    out += tmp;
                    break;
                case 'c':
                    out.push_back(static_cast<char>(next() & 0xFFu));
                    break;
                case 'p':
                    snprintf(tmp, sizeof tmp, "0x%08x", next());
                    out += tmp;
                    break;
                case 'f': case 'F': case 'g': case 'G': case 'e': case 'E':
                    snprintf(tmp, sizeof tmp, spec.c_str(), read_double(next));
                    out += tmp;
                    break;
                default:
                    out += spec;
                    break;
            }
            i = j;
        }
        return out;
    }

    uint32_t imp_log_print() {
        const int prio = static_cast<int>(arg(0));
        const std::string tag = guest_string(arg(1));
        const std::string fmt = guest_string(arg(2));

        // ARM32: varargs start in R3 and continue on the stack at [SP].
        uint32_t sp = 0;
        emu_.reg_read(UC_ARM_REG_SP, sp);
        size_t index = 0;
        auto next = [&]() -> uint32_t {
            if (index == 0) { ++index; return arg(3); }
            const uint32_t v = emu_.read_u32(sp + static_cast<uint32_t>((index - 1) * 4));
            ++index;
            return v;
        };

        const std::string text = format_guest(fmt, next);
        __android_log_print(prio, tag.empty() ? "GUEST" : tag.c_str(), "%s", text.c_str());
        return static_cast<uint32_t>(text.size());
    }

    // Import-coverage report: what the guest asked for versus what the host
    // actually implements. Printed once the guest has been given a chance to run.
    void log_import_report() {
        size_t bridged = 0;
        std::string missing;
        for (const auto& kv : import_slots_) {
            if (import_handlers_.count(kv.first)) { ++bridged; continue; }
            if (missing.size() < 1200) {
                missing += kv.first;
                missing.push_back(' ');
            }
        }
        const size_t total = import_slots_.size();
        LOGI("IMPORT COVERAGE: %zu/%zu imports bridged, %zu unresolved",
             bridged, total, total - bridged);
        LOGI("IMPORT UNRESOLVED: %s", missing.c_str());
    }

    void log_once_env(int index) {
        const uint32_t key = 1u * 100000u + static_cast<uint32_t>(index);
        if (logged_.insert(static_cast<int>(key)).second) {
            LOGW("unimplemented JNIEnv slot #%d (%s)", index, jni_env_name(index));
        }
    }

    void log_once_vm(int index) {
        const uint32_t key = 2u * 100000u + static_cast<uint32_t>(index);
        if (logged_.insert(static_cast<int>(key)).second) {
            LOGW("unimplemented JavaVM slot #%d (%s)", index, jni_vm_name(index));
        }
    }

    // Guest -> env->RegisterNatives(env, clazz, methods, n)
    uint32_t register_natives() {
        auto* clazz = static_cast<jclass>(from_guest_ref(arg(1)));
        const uint32_t methods = arg(2);
        const uint32_t count = arg(3);

        if (!clazz) {
            LOGE("RegisterNatives: unknown class handle %08x", arg(1));
            return JNI_ERR;
        }
        if (count > 512) {
            LOGE("RegisterNatives: implausible method count %u", count);
            return JNI_ERR;
        }

        std::vector<JNINativeMethod> host_methods;
        host_methods.reserve(count);
        // host_methods[] stores c_str() pointers into natives_; reserve now so
        // the strings never move underneath those pointers.
        natives_.reserve(natives_.size() + count);

        for (uint32_t i = 0; i < count; ++i) {
            const uint32_t base = methods + i * 12;   // sizeof(JNINativeMethod32)
            const uint32_t name_p = emu_.read_u32(base + 0);
            const uint32_t sig_p  = emu_.read_u32(base + 4);
            const uint32_t fn_p   = emu_.read_u32(base + 8);

            std::string name = guest_string(name_p);
            std::string sig  = guest_string(sig_p);

            NativeEntry e;
            e.id = static_cast<uint32_t>(natives_.size());
            e.guest_fn = fn_p;
            e.name = name;
            e.sig = sig;
            parse_signature(sig, e);

            void* code = HostTrampolines::emit(e.id, reinterpret_cast<void*>(&bridge_native_entry));
            if (!code) {
                LOGE("RegisterNatives: trampoline pool exhausted");
                return JNI_ERR;
            }
            e.code = code;
            natives_.push_back(e);

            host_methods.push_back(
                {natives_.back().name.c_str(), natives_.back().sig.c_str(), code});
            LOGI("  native %s%s -> guest %08x (trampoline %p)", name.c_str(), sig.c_str(),
                 fn_p, code);
        }

        jint rc = env_->RegisterNatives(clazz, host_methods.data(),
                                        static_cast<jint>(host_methods.size()));
        LOGI("RegisterNatives: %u methods -> rc=%d%s", count, rc,
             rc == JNI_OK ? "" : " (retry after the class is loaded)");
        if (rc != JNI_OK) env_->ExceptionClear();
        return static_cast<uint32_t>(rc);
    }

    // =====================================================================
    // Guest -> Java calls and array traffic
    //
    // The guest cannot call Java directly: a jmethodID it holds is one of our
    // 32-bit ref handles, and its arguments live in guest memory. Every call is
    // rebuilt as an array of `jvalue`s and issued through the *A entry points,
    // which is the only variadic-free form the host offers.
    // =====================================================================
    static size_t element_size(char t) {
        switch (t) {
            case 'Z': case 'B': return 1;
            case 'C': case 'S': return 2;
            case 'I': case 'F': return 4;
            case 'J': case 'D': return 8;
            default:            return 4;
        }
    }

    // Z,B,C,S,I,J,F,D - the order New<Type>Array and the array blocks use.
    static char array_type(int i) { return kCallTypeOrder[i + 1]; }

    uint32_t new_primitive_array(char t) {
        const jsize len = static_cast<jsize>(arg(1));
        switch (t) {
            case 'Z': return to_guest_ref(env_->NewBooleanArray(len));
            case 'B': return to_guest_ref(env_->NewByteArray(len));
            case 'C': return to_guest_ref(env_->NewCharArray(len));
            case 'S': return to_guest_ref(env_->NewShortArray(len));
            case 'I': return to_guest_ref(env_->NewIntArray(len));
            case 'J': return to_guest_ref(env_->NewLongArray(len));
            case 'F': return to_guest_ref(env_->NewFloatArray(len));
            case 'D': return to_guest_ref(env_->NewDoubleArray(len));
            default:  return 0;
        }
    }

    uint32_t array_region(char t, bool set) {
        jarray arr = static_cast<jarray>(from_guest_ref(arg(1)));
        const jsize start = static_cast<jsize>(arg(2));
        const jsize len = static_cast<jsize>(arg(3));
        const uint32_t buf = arg(4);
        if (!arr || len <= 0) return 0;

        std::vector<uint8_t> tmp(static_cast<size_t>(len) * element_size(t));
        if (set && !emu_.read(buf, tmp.data(), tmp.size())) {
            LOGE("SetArrayRegion: unreadable guest buffer %08x", buf);
            return 0;
        }

        switch (t) {
            case 'Z':
                if (set) env_->SetBooleanArrayRegion((jbooleanArray)arr, start, len, (jboolean*)tmp.data());
                else     env_->GetBooleanArrayRegion((jbooleanArray)arr, start, len, (jboolean*)tmp.data());
                break;
            case 'B':
                if (set) env_->SetByteArrayRegion((jbyteArray)arr, start, len, (jbyte*)tmp.data());
                else     env_->GetByteArrayRegion((jbyteArray)arr, start, len, (jbyte*)tmp.data());
                break;
            case 'C':
                if (set) env_->SetCharArrayRegion((jcharArray)arr, start, len, (jchar*)tmp.data());
                else     env_->GetCharArrayRegion((jcharArray)arr, start, len, (jchar*)tmp.data());
                break;
            case 'S':
                if (set) env_->SetShortArrayRegion((jshortArray)arr, start, len, (jshort*)tmp.data());
                else     env_->GetShortArrayRegion((jshortArray)arr, start, len, (jshort*)tmp.data());
                break;
            case 'I':
                if (set) env_->SetIntArrayRegion((jintArray)arr, start, len, (jint*)tmp.data());
                else     env_->GetIntArrayRegion((jintArray)arr, start, len, (jint*)tmp.data());
                break;
            case 'J':
                if (set) env_->SetLongArrayRegion((jlongArray)arr, start, len, (jlong*)tmp.data());
                else     env_->GetLongArrayRegion((jlongArray)arr, start, len, (jlong*)tmp.data());
                break;
            case 'F':
                if (set) env_->SetFloatArrayRegion((jfloatArray)arr, start, len, (jfloat*)tmp.data());
                else     env_->GetFloatArrayRegion((jfloatArray)arr, start, len, (jfloat*)tmp.data());
                break;
            case 'D':
                if (set) env_->SetDoubleArrayRegion((jdoubleArray)arr, start, len, (jdouble*)tmp.data());
                else     env_->GetDoubleArrayRegion((jdoubleArray)arr, start, len, (jdouble*)tmp.data());
                break;
            default: break;
        }

        if (!set) emu_.write(buf, tmp.data(), tmp.size());
        return 0;
    }

    struct ArrayPin {
        jarray arr = nullptr;
        void*  host = nullptr;
        char   type = 'B';
        jsize  len = 0;
    };

    uint32_t array_elements(char t) {
        jarray arr = static_cast<jarray>(from_guest_ref(arg(1)));
        if (!arr) return 0;

        void* host = nullptr;
        switch (t) {
            case 'Z': host = env_->GetBooleanArrayElements((jbooleanArray)arr, nullptr); break;
            case 'B': host = env_->GetByteArrayElements((jbyteArray)arr, nullptr); break;
            case 'C': host = env_->GetCharArrayElements((jcharArray)arr, nullptr); break;
            case 'S': host = env_->GetShortArrayElements((jshortArray)arr, nullptr); break;
            case 'I': host = env_->GetIntArrayElements((jintArray)arr, nullptr); break;
            case 'J': host = env_->GetLongArrayElements((jlongArray)arr, nullptr); break;
            case 'F': host = env_->GetFloatArrayElements((jfloatArray)arr, nullptr); break;
            case 'D': host = env_->GetDoubleArrayElements((jdoubleArray)arr, nullptr); break;
            default: return 0;
        }
        if (!host) return 0;

        const jsize len = env_->GetArrayLength(arr);
        const size_t bytes = static_cast<size_t>(len) * element_size(t);
        const uint32_t at = guest_alloc(bytes ? bytes : 1);
        if (!at) return 0;
        emu_.write(at, host, bytes);
        pins_[at] = {arr, host, t, len};
        return at;
    }

    uint32_t release_array_elements(char t) {
        const uint32_t at = arg(1);
        auto it = pins_.find(at);
        if (it == pins_.end()) return 0;

        const jint mode = static_cast<jint>(arg(2));
        const size_t bytes = static_cast<size_t>(it->second.len) * element_size(t);
        if (mode != JNI_ABORT && bytes) {
            emu_.read(at, it->second.host, bytes);
        }

        jarray arr = it->second.arr;
        void* host = it->second.host;
        pins_.erase(it);
        emu_.release(at, bytes ? bytes : 1);

        switch (t) {
            case 'Z': env_->ReleaseBooleanArrayElements((jbooleanArray)arr, (jboolean*)host, mode); break;
            case 'B': env_->ReleaseByteArrayElements((jbyteArray)arr, (jbyte*)host, mode); break;
            case 'C': env_->ReleaseCharArrayElements((jcharArray)arr, (jchar*)host, mode); break;
            case 'S': env_->ReleaseShortArrayElements((jshortArray)arr, (jshort*)host, mode); break;
            case 'I': env_->ReleaseIntArrayElements((jintArray)arr, (jint*)host, mode); break;
            case 'J': env_->ReleaseLongArrayElements((jlongArray)arr, (jlong*)host, mode); break;
            case 'F': env_->ReleaseFloatArrayElements((jfloatArray)arr, (jfloat*)host, mode); break;
            case 'D': env_->ReleaseDoubleArrayElements((jdoubleArray)arr, (jdouble*)host, mode); break;
            default: break;
        }
        return 0;
    }

    std::string signature_of(jmethodID mid) {
        auto it = method_sigs_.find(mid);
        return it == method_sigs_.end() ? std::string() : it->second;
    }

    // Rebuilds the guest's argument list as host jvalues.
    //   variant 0: direct varargs (R3, then the stack)
    //   variant 1: va_list - on ARM32 that is a pointer to the argument words
    //   variant 2: const jvalue* - 8 bytes per argument
    bool read_args(const std::string& sig, int variant, std::vector<jvalue>& out) {
        const size_t open = sig.find('(');
        if (open == std::string::npos) return false;

        size_t word_index = 0;
        size_t jv_index = 0;
        uint32_t jv_base = 0, va_ptr = 0, sp = 0;

        if (variant == 2) {
            jv_base = arg(3);
        } else if (variant == 1) {
            const uint32_t va = arg(3);
            va_ptr = va ? emu_.read_u32(va) : 0;
        } else {
            emu_.reg_read(UC_ARM_REG_SP, sp);
        }

        auto next_word = [&]() -> uint32_t {
            uint32_t v = 0;
            if (variant == 0) {
                if (word_index == 0) v = arg(3);
                else v = emu_.read_u32(sp + static_cast<uint32_t>((word_index - 1) * 4));
            } else {
                v = emu_.read_u32(va_ptr + static_cast<uint32_t>(word_index * 4));
            }
            ++word_index;
            return v;
        };

        auto read_one = [&](char t) -> jvalue {
            jvalue v{};
            const uint32_t at = jv_base + static_cast<uint32_t>(jv_index * 8);
            auto w32 = [&]() -> uint32_t {
                return variant == 2 ? emu_.read_u32(at) : next_word();
            };
            auto w64 = [&]() -> uint64_t {
                if (variant == 2) {
                    const uint64_t lo = emu_.read_u32(at), hi = emu_.read_u32(at + 4);
                    return lo | (hi << 32);
                }
                const uint64_t lo = next_word(), hi = next_word();
                return lo | (hi << 32);
            };
            switch (t) {
                case 'Z': v.z = static_cast<jboolean>(w32() & 0xFFu); break;
                case 'B': v.b = static_cast<jbyte>(w32() & 0xFFu); break;
                case 'C': v.c = static_cast<jchar>(w32() & 0xFFFFu); break;
                case 'S': v.s = static_cast<jshort>(w32() & 0xFFFFu); break;
                case 'I': v.i = static_cast<jint>(w32()); break;
                case 'J': v.j = static_cast<jlong>(w64()); break;
                case 'F': { const uint32_t b = w32(); float f; std::memcpy(&f, &b, 4); v.f = f; break; }
                case 'D': { const uint64_t b = w64(); double d; std::memcpy(&d, &b, 8); v.d = d; break; }
                default:  v.l = static_cast<jobject>(from_guest_ref(w32())); break;   // 'L'/'['
            }
            ++jv_index;
            return v;
        };

        size_t i = open + 1;
        while (i < sig.size() && sig[i] != ')') {
            char t = sig[i];
            if (t == '[') {
                while (i + 1 < sig.size() && sig[i + 1] == '[') ++i;
                if (i + 1 < sig.size() && sig[i + 1] == 'L') {
                    i = sig.find(';', i);
                    if (i == std::string::npos) return false;
                } else {
                    ++i;
                }
                out.push_back(read_one('L'));
                ++i;
                continue;
            }
            if (t == 'L') {
                i = sig.find(';', i);
                if (i == std::string::npos) return false;
                out.push_back(read_one('L'));
                ++i;
                continue;
            }
            out.push_back(read_one(t));
            ++i;
        }
        return true;
    }

    uint32_t call_java(char ret, bool is_static, int variant) {
        jobject recv = is_static ? nullptr : static_cast<jobject>(from_guest_ref(arg(1)));
        jclass  cls  = is_static ? static_cast<jclass>(from_guest_ref(arg(1))) : nullptr;
        jmethodID mid = static_cast<jmethodID>(from_guest_ref(arg(2)));
        if (!mid) { LOGW("Call*Method: null methodID"); return 0; }
        if (!recv && !cls) { LOGW("Call*Method: null receiver"); return 0; }

        const std::string sig = signature_of(mid);
        if (sig.empty()) {
            LOGW("Call*Method: no signature cached for method %p", mid);
            return 0;
        }

        std::vector<jvalue> args;
        args.reserve(sig.size());
        if (!read_args(sig, variant, args)) return 0;
        jvalue* a = args.empty() ? nullptr : args.data();

        switch (ret) {
            case 'V':
                if (is_static) env_->CallStaticVoidMethodA(cls, mid, a);
                else           env_->CallVoidMethodA(recv, mid, a);
                return 0;
            case 'L': {
                jobject r = is_static ? env_->CallStaticObjectMethodA(cls, mid, a)
                                      : env_->CallObjectMethodA(recv, mid, a);
                return to_guest_ref(r);
            }
            case 'Z':
                return (is_static ? env_->CallStaticBooleanMethodA(cls, mid, a)
                                  : env_->CallBooleanMethodA(recv, mid, a)) ? JNI_TRUE : JNI_FALSE;
            case 'B':
                return static_cast<uint32_t>(static_cast<int8_t>(
                    is_static ? env_->CallStaticByteMethodA(cls, mid, a)
                              : env_->CallByteMethodA(recv, mid, a)));
            case 'C':
                return static_cast<uint32_t>(is_static ? env_->CallStaticCharMethodA(cls, mid, a)
                                                       : env_->CallCharMethodA(recv, mid, a));
            case 'S':
                return static_cast<uint32_t>(static_cast<int16_t>(
                    is_static ? env_->CallStaticShortMethodA(cls, mid, a)
                              : env_->CallShortMethodA(recv, mid, a)));
            case 'I':
                return static_cast<uint32_t>(is_static ? env_->CallStaticIntMethodA(cls, mid, a)
                                                       : env_->CallIntMethodA(recv, mid, a));
            case 'J':
                return ret_i64(static_cast<int64_t>(is_static ? env_->CallStaticLongMethodA(cls, mid, a)
                                                              : env_->CallLongMethodA(recv, mid, a)));
            case 'F':
                return ret_f(is_static ? env_->CallStaticFloatMethodA(cls, mid, a)
                                       : env_->CallFloatMethodA(recv, mid, a));
            case 'D':
                return ret_d(is_static ? env_->CallStaticDoubleMethodA(cls, mid, a)
                                       : env_->CallDoubleMethodA(recv, mid, a));
            default:
                return 0;
        }
    }

    uint32_t new_object(int variant) {
        jclass cls = static_cast<jclass>(from_guest_ref(arg(1)));
        jmethodID mid = static_cast<jmethodID>(from_guest_ref(arg(2)));
        if (!cls || !mid) return 0;

        const std::string sig = signature_of(mid);
        if (sig.empty()) {
            LOGW("NewObject: no signature cached for constructor %p", mid);
            return 0;
        }
        std::vector<jvalue> args;
        if (!read_args(sig, variant, args)) return 0;
        return to_guest_ref(env_->NewObjectA(cls, mid, args.empty() ? nullptr : args.data()));
    }

    // Minimal JNI signature parser: enough to classify and count arguments.
    static void parse_signature(const std::string& sig, NativeEntry& e) {
        size_t i = sig.find('(');
        if (i == std::string::npos) return;
        ++i;
        for (; i < sig.size() && sig[i] != ')'; ++i) {
            char c = sig[i];
            if (c == '[') {
                e.arg_types.push_back('[');
                while (i + 1 < sig.size() && sig[i + 1] == '[') ++i;
                if (i + 1 < sig.size() && sig[i + 1] == 'L') {
                    i = sig.find(';', i);
                    if (i == std::string::npos) return;
                } else {
                    ++i;
                }
                continue;
            }
            if (c == 'L') {
                e.arg_types.push_back('L');
                i = sig.find(';', i);
                if (i == std::string::npos) return;
                continue;
            }
            e.arg_types.push_back(c);
        }
        e.ret_type = (i + 1 < sig.size()) ? sig[i + 1] : 'V';
    }

    static const char* jni_env_name(int idx);
    static const char* jni_vm_name(int idx);

    Emulator&    emu_;
    StaticLinker linker_;
    JNIEnv*      env_ = nullptr;
    JavaVM*      vm_ = nullptr;

    uint32_t env_shadow_ = 0, env_funcs_ = 0;
    uint32_t vm_shadow_ = 0,  vm_funcs_ = 0;

    std::vector<StubSlot> slots_;
    std::vector<std::function<uint32_t()>> callbacks_;
    std::unordered_map<std::string, uint32_t> import_slots_;
    std::unordered_map<std::string, uint32_t> interned_strings_;
    int guest_pointer_warnings_ = 0;
    std::unordered_map<uint32_t, void*> refs_;
    std::unordered_map<void*, uint32_t> ref_reverse_;
    uint32_t next_ref_ = 0x00010000;

    std::vector<NativeEntry> natives_;
    // Until the first frame is presented no single guest call may hold the
    // calling ART thread for longer than this.
    //
    // A legacy renderer parks its UI thread on the first frame -- Tux Rider's
    // GLSurfaceView sits in surfaceChanged() waiting for the GL thread to finish
    // `resize` -- so an engine initialisation measured in seconds reads to the
    // system as a frozen app and turns into an input-dispatch ANR. Slicing the
    // call keeps the UI thread responsive while the GL thread, which is the
    // thread that has to do the work, drains it frame by frame. Once a frame has
    // been presented the budget is lifted and calls run to completion as before.
    int startup_budget_ms() {
        if (frames_ > 0) return 0;
        const auto now = std::chrono::steady_clock::now();
        if (startup_begin_.time_since_epoch().count() == 0) startup_begin_ = now;
        // A guest that never renders must not be sliced forever.
        if (now - startup_begin_ > std::chrono::seconds(60)) return 0;
        return kStartupCallBudgetMs;
    }

    // A call that arrived while the guest was still inside a suspended one.
    struct Deferred {
        bool        active = false;
        uint32_t    fn = 0;
        std::vector<uint32_t> args;
        std::string name;
    };
    Deferred deferred_;

    static constexpr int kStartupCallBudgetMs = 250;
    std::recursive_mutex emu_mu_;                     // serialises ART -> guest
    std::chrono::steady_clock::time_point startup_begin_{};

    std::unordered_map<std::string, ImportHandler> import_handlers_;
    std::unordered_map<uint32_t, size_t> guest_allocs_;
    std::unordered_map<std::string, uint32_t> data_slots_;
    std::unordered_set<int> logged_;
    uc_hook hook_ = 0;

    uint32_t strtok_last_ = 0;      // state of the emulated strtok()
    uint32_t stack_guard_ = 0;      // guest word behind __stack_chk_guard
    uint32_t   errno_cell_ = 0;     // guest word behind __errno
    uint64_t   drand48_state_ = 0x1234ABCD330Eull;   // POSIX 48-bit LCG state
    uint32_t   frames_ = 0;         // guest frames presented (eglSwapBuffers)
    uint32_t return_gadget_ = 0;    // guest `mov r0,#0 ; ret` trampoline
    bool     redirected_ = false;   // a handler already set PC itself

    struct DirHandle {
        DIR* host = nullptr;
        uint32_t buf = 0;   // guest copy of the current struct dirent
    };

    std::unordered_map<uint32_t, int>    fd_map_;      // guest fd -> host fd
    std::unordered_map<uint32_t, FILE*>  file_map_;    // guest handle -> FILE*
    std::unordered_map<uint32_t, DirHandle> dir_map_;
    uint32_t next_handle_ = 0x1000;   // never collides with 0/1/2

    // guest filesystem / runtime-loading context
    std::string game_root_, package_, payload_dir_, obb_dir_, data_dir_;
    std::string runtime_root_;   // unpacked assets/runtime (sysroot_arm32, sysroot_x86)
    std::unordered_set<std::string> translate_log_;
    std::unordered_map<std::string, uint32_t> dl_handles_;
    std::unordered_map<uint32_t, LoadedLibrary*> dl_libs_;
    uint32_t next_runtime_slot_ = 0;

    // host platform libraries
    void* android_lib_ = nullptr;
    void* opensles_lib_ = nullptr;
    std::unordered_map<std::string, void*> platform_syms_;
    jobject assets_object_ = nullptr;
    std::unordered_map<uint32_t, uint32_t> asset_buffers_;

    // OpenSL ES host objects, indexed by the token stored in the guest struct.
    std::vector<SlHostObject> sl_objects_;
    std::vector<std::vector<uint8_t>> sl_pcm_;

    // host graphics
    void* egl_lib_ = nullptr;
    void* gles1_lib_ = nullptr;             // GLES1 fixed-function entry points
    void* gles_lib_ = nullptr;
    ANativeWindow* host_window_ = nullptr;
    std::unordered_map<std::string, void*>    host_syms_;

    uint32_t swap_count_ = 0;
    uint32_t egl_window_surfaces_ = 0;
    // GPU compositor / universal-compatibility state
    bool     compositor_bound_ = false;
    GLuint   compositor_fbo_ = 0;
    float    compositor_scale_ = 1.0f;
    GLuint   current_fbo_ = 0;
    int      gles_major_ = 0;
    std::string virtual_cpuinfo_;
    std::map<std::string, uint32_t> entry_points_;
    jobject guest_thiz_ = nullptr;

    std::unordered_map<void*, std::string> method_sigs_;   // jmethodID -> descriptor
    std::unordered_map<uint32_t, ArrayPin> pins_;          // guest buffer -> pinned array
    std::unordered_map<uint32_t, uint32_t> tls_values_;    // pthread key -> value
    uint32_t next_tls_key_ = 1;

    jobject guest_loader_ = nullptr;    // DexClassLoader over the ingested APK
    std::vector<JavaExport> pending_;   // Java_* exports awaiting their class
    std::unordered_map<uint32_t, int> native_calls_;   // trampoline id -> count
};

// JNIEnv / JavaVM slot names, for diagnostics only.
const char* JniBridge::jni_env_name(int idx) {
    switch (idx) {
        case kIdxGetVersion: return "GetVersion";
        case kIdxFindClass: return "FindClass";
        case kIdxGetObjectClass: return "GetObjectClass";
        case kIdxGetMethodID: return "GetMethodID";
        case kIdxRegisterNatives: return "RegisterNatives";
        case kIdxNewStringUTF: return "NewStringUTF";
        case kIdxGetStringUTFChars: return "GetStringUTFChars";
        case kIdxExceptionCheck: return "ExceptionCheck";
        case kIdxGetJavaVM: return "GetJavaVM";
        default: return "?";
    }
}

const char* JniBridge::jni_vm_name(int idx) {
    switch (idx) {
        case kIdxVmAttach: return "AttachCurrentThread";
        case kIdxVmDetach: return "DetachCurrentThread";
        case kIdxVmGetEnv: return "GetEnv";
        case kIdxVmDestroy: return "DestroyJavaVM";
        default: return "?";
    }
}

JniBridge* g_bridge = nullptr;
Emulator*  g_emulator = nullptr;
JavaVM*    g_vm = nullptr;

// One emulator per process: the guest address space is fixed (see the layout
// at the top of this file), so a second payload set only fits after a teardown
// that is not implemented yet. GameHostActivity surfaces this as "already
// running" instead of silently corrupting the first session.
bool g_session_active = false;

// The window behind GameHostActivity's SurfaceView. Set from the UI thread,
// consumed by the guest's EGL calls, which run on the emulation thread.
std::atomic<ANativeWindow*> g_host_window{nullptr};

std::string jstring_to_std(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

// Entry point for every generated ARM64 trampoline: x0 = method id, x1 = spilled
// x0..x7. Must have C linkage so the `bl` offset is stable.
extern "C" uint64_t bridge_native_entry(uint32_t id, uint64_t* host_args) {
    return g_bridge ? g_bridge->native_entry(id, host_args) : 0;
}

// ===========================================================================
// 5. JNI entry points
// ===========================================================================
namespace {

// Every ARM32 payload staged by the ingestion engine in a game's private
// directory. The `*32.so` suffix is the only contract: whatever the APK
// shipped in lib/armeabi-v7a, renamed, gets mapped. Nothing here knows about
// libapp.so, libNimble.so or libfmodex.so by name.
std::vector<std::string> discover_payloads(const std::string& dir) {
    std::vector<std::string> out;
    const std::string root = dir.empty() ? std::string(".") : dir;

    DIR* d = opendir(root.c_str());
    if (!d) {
        LOGE("opendir(%s): %s", root.c_str(), strerror(errno));
        return out;
    }
    const std::string suffix = "32.so";
    while (struct dirent* e = readdir(d)) {
        const std::string name = e->d_name;
        if (name.size() <= suffix.size()) continue;
        if (name.compare(0, 3, "lib") != 0) continue;
        if (name.compare(name.size() - suffix.size(), suffix.size(), suffix) != 0) continue;
        out.push_back(root + "/" + name);
    }
    closedir(d);

    // C++ runtime first (every payload imports from it), then the rest by
    // name, then the application payload last (it imports from all of them).
    auto rank = [](const std::string& p) {
        if (p.find("libc++_shared32.so") != std::string::npos) return 0;
        if (p.find("libapp32.so") != std::string::npos) return 2;
        return 1;
    };
    std::sort(out.begin(), out.end(), [&](const std::string& a, const std::string& b) {
        const int ra = rank(a), rb = rank(b);
        return ra != rb ? ra < rb : a < b;
    });
    return out;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_vm = vm;
    LOGI("==============================================================");    LOGI("Zai-Bridge translation core (Unicorn backend)");
#if defined(__aarch64__)
    LOGI(" guest: ARM32 / Thumb-2     host: arm64-v8a");
#else
    LOGI(" guest: ARM32 / Thumb-2     host: x86_64");
#endif
    LOGI("==============================================================");

    // No payload is mapped at load time: the library is resident before any
    // game is chosen, and each session is opened explicitly by
    // GameHostActivity through nativeStartSession().
    return JNI_VERSION_1_6;
}

// ---------------------------------------------------------------------------
// Session entry points
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_ziauddin_zaibridge_core_UnicornBridge_nativeStartSession(
        JNIEnv* env, jclass /*clazz*/, jstring game_dir, jstring obb_path,
        jstring package_name, jobject guest_loader, jobject guest_activity) {
    const std::string dir = jstring_to_std(env, game_dir);
    const std::string obb = jstring_to_std(env, obb_path);
    const std::string pkg = jstring_to_std(env, package_name);

    LOGI("==============================================================");
    LOGI(" Zai-Bridge: opening guest session");
    LOGI("   game dir : %s", dir.c_str());
    LOGI("   expansion: %s", obb.empty() ? "<none>" : obb.c_str());
    LOGI("==============================================================");

    if (g_session_active) {
        LOGW("guest session already active in this process");
        return 1;
    }
    if (dir.empty()) {
        LOGE("no game directory supplied");
        return -2;
    }

    g_emulator = new Emulator();
    g_bridge = new JniBridge(*g_emulator);
    if (!g_emulator->init() || !g_bridge->init(g_vm, env)) {
        LOGE("bridge initialisation failed");
        delete g_bridge;
        g_bridge = nullptr;
        delete g_emulator;
        g_emulator = nullptr;
        return -1;
    }

    // Guest classes (Java_* exports, RegisterNatives targets) must resolve
    // through the DexClassLoader the host positioned on the ingested APK.
    //
    // The loader is used from every thread the guest runs on, so it has to be a
    // global reference: a local reference belongs to the JNI transition frame
    // that created it and CheckJNI rejects it on any other thread ("invalid
    // JNI transition frame reference").
    if (guest_loader) {
        g_bridge->set_guest_loader(static_cast<jobject>(env->NewGlobalRef(guest_loader)));
    }
    // The engine's native side calls back into Java through the Activity it was
    // handed, so it needs a real object, not null, as the JNI `this`.
    if (guest_activity) {
        g_bridge->set_guest_activity(static_cast<jobject>(env->NewGlobalRef(guest_activity)));
    }

    // Filesystem redirection root, and the ANativeWindow the SurfaceView has
    // already produced. Both are consumed by the EGL window-surface handler.
    g_bridge->set_game_context(dir, pkg);
    g_bridge->set_host_window(g_host_window.load());

    // ---- Universal CPU compatibility -------------------------------------
    // Detect the big.LITTLE layout once, pin this thread to the performance
    // cluster, and publish the synthetic /proc/cpuinfo the guest reads. This
    // runs before any payload constructor, so an engine that decides its code
    // path from CPU features sees ARMv7-A + VFPv3-D32 + NEON on every SoC.
    zai::cpu::topology();
    zai::cpu::pin_current_thread_to_performance();
    LOGI("cpu compatibility: %s", zai::cpu::summary().c_str());

    // ---- Phase 1: map every ingested ARM32 payload -------------------------
    const std::string payload_dir = dir + "/payload";
    const std::vector<std::string> payloads = discover_payloads(payload_dir);
    if (payloads.empty()) {
        LOGE("no ARM32 payloads (lib*32.so) in %s", payload_dir.c_str());
        return -2;
    }
    LOGI("discovered %zu ARM32 payloads in %s", payloads.size(), payload_dir.c_str());
    g_bridge->reserve_image_slots(payloads.size());

    for (size_t i = 0; i < payloads.size(); ++i) {
        const uint32_t base = kImageBase0 + static_cast<uint32_t>(i) * kImageStride;
        if (!g_bridge->map_library(payloads[i], base)) {
            LOGE("failed to map %s", payloads[i].c_str());
            return -3;
        }
    }

    // Resolve and map DT_NEEDED dependencies from payload/ and bundled sysroot
    g_bridge->resolve_all_needed();

    // ---- Phase 2: relocate across the whole link set, then run DT_INIT ----
    g_bridge->link_all();
    g_bridge->run_all_constructors();

    // ---- Phase 3: make every Java_* export visible to ART -----------------
    // Bound before the guests' own JNI_OnLoad runs, so a library that prefers
    // RegisterNatives still wins for the methods it registers itself.
    g_bridge->bind_java_exports();

    size_t onloads = 0;
    for (const auto* lib : g_bridge->libraries()) {
        const uint32_t onload = JniBridge::export_of(lib, "JNI_OnLoad");
        if (!onload) continue;
        uint32_t ret = 0;
        if (g_bridge->call_onload(onload, ret)) {
            LOGI("%s: JNI_OnLoad -> %d (0x%08x)", lib->path.c_str(),
                 static_cast<int>(ret), ret);
            ++onloads;
        }
    }
    LOGI("called JNI_OnLoad on %zu payload(s)", onloads);

    g_bridge->report_imports();
    g_session_active = true;
    LOGI("Zai-Bridge session ready (engine-agnostic: the guest drives its own frame loop)");

    // Nothing is driven from here on purpose.
    //
    // Every payload this bridge supports (a GLSurfaceView.Renderer, a
    // NativeActivity, SDL2's thread, an EGL paint loop) reaches its native code
    // through RegisterNatives / Java_* exports, which are already bound to
    // host trampolines. ART calls them on the guest's own thread, the guest's
    // eglSwapBuffers is forwarded to the real driver bound to the Activity's
    // window, and frames present themselves. A per-engine pump in this thread
    // used to force that from the outside and only worked for one game.
    return 0;
}

// Attaches or clears the host surface. Safe to call on every SurfaceHolder
// callback: replacing the window keeps the EGL surface pointed at the live one.
extern "C" JNIEXPORT void JNICALL
Java_com_ziauddin_zaibridge_core_UnicornBridge_nativeSetSurface(
        JNIEnv* env, jclass /*clazz*/, jobject surface) {
    ANativeWindow* previous = g_host_window.exchange(nullptr);
    if (surface) {
        ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
        g_host_window.store(window);
        if (g_bridge) g_bridge->set_host_window(window);
        if (window) {
            LOGI("host Surface attached: %p (%dx%d)", window,
                 ANativeWindow_getWidth(window), ANativeWindow_getHeight(window));
        } else {
            LOGE("ANativeWindow_fromSurface returned null");
        }
    } else {
        if (g_bridge) g_bridge->set_host_window(nullptr);
        LOGI("host Surface detached");
    }

    // EGL retains its own reference to a window once a surface is created from
    // it, so dropping ours here is safe and avoids leaking per rotation.
    if (previous) ANativeWindow_release(previous);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ziauddin_zaibridge_core_UnicornBridge_nativeVersion(JNIEnv* env, jclass /*clazz*/) {
    unsigned int major = 0, minor = 0;
    uc_version(&major, &minor);
    char buf[160];
    snprintf(buf, sizeof buf, "Unicorn %u.%u - ARM32 guest on %s host", major, minor,
#if defined(__aarch64__)
             "arm64-v8a"
#elif defined(__x86_64__)
             "x86_64"
#else
             "32-bit"
#endif
    );
    return env->NewStringUTF(buf);
}

// Root of the unpacked runtime bundle. The guest's DT_NEEDED resolver searches
// it after the payload directory, which is what makes a self-contained,
// offline legacy runtime possible.
extern "C" JNIEXPORT void JNICALL
Java_com_ziauddin_zaibridge_core_UnicornBridge_nativeSetRuntimeSysroot(
        JNIEnv* env, jclass /*clazz*/, jstring path) {
    const std::string root = jstring_to_std(env, path);
    if (g_bridge) g_bridge->set_runtime_root(root);
    LOGI("runtime sysroot: %s", root.empty() ? "<none>" : root.c_str());
}

// The guest Activity only exists after newActivity(); the core is opened before
// it, because a legacy static initialiser calls System.loadLibrary as soon as
// the class is touched.
extern "C" JNIEXPORT void JNICALL
Java_com_ziauddin_zaibridge_core_UnicornBridge_nativeSetGuestActivity(
        JNIEnv* env, jclass /*clazz*/, jobject activity) {
    if (!g_bridge || !activity) return;
    g_bridge->set_guest_activity(static_cast<jobject>(env->NewGlobalRef(activity)));
    LOGI("guest Activity registered with the core");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ziauddin_zaibridge_core_UnicornBridge_nativeIsSessionActive(
        JNIEnv* /*env*/, jclass /*clazz*/) {
    return g_session_active ? JNI_TRUE : JNI_FALSE;
}

// Clean session teardown. The guest Activity is already destroyed when the
// launcher calls this; the compositor and the Unicorn engine are released so a
// second game can be launched in the same process with a fresh address space.
extern "C" JNIEXPORT void JNICALL
Java_com_ziauddin_zaibridge_core_UnicornBridge_nativeEndSession(
        JNIEnv* /*env*/, jclass /*clazz*/) {
    if (!g_session_active && g_bridge == nullptr && g_emulator == nullptr) return;
    LOGI("Zai-Bridge: closing guest session");
    zai::GpuCompositor::instance().teardown();
    delete g_bridge;
    g_bridge = nullptr;
    delete g_emulator;
    g_emulator = nullptr;
    g_session_active = false;
    LOGI("Zai-Bridge: session closed; a new import can start");
}

// NATIVE_ACTIVITY_BRIDGE_START
struct ANativeActivityCallbacks32 {
    uint32_t onStart, onResume, onSaveInstanceState, onPause, onStop, onDestroy;
    uint32_t onWindowFocusChanged, onNativeWindowCreated, onNativeWindowResized;
    uint32_t onNativeWindowRedrawNeeded, onNativeWindowDestroyed;
    uint32_t onInputQueueCreated, onInputQueueDestroyed, onContentRectChanged;
    uint32_t onConfigurationChanged, onLowMemory;
};
struct ANativeActivity32 {
    uint32_t callbacks, vm, env, clazz, internalDataPath, externalDataPath;
    int32_t sdkVersion; uint32_t instance, assetManager, obbPath;
};
static uint32_t g_guest_onStart=0,g_guest_onResume=0,g_guest_onPause=0,g_guest_onStop=0,g_guest_onDestroy=0;
static uint32_t g_guest_onWindowFocusChanged=0,g_guest_onLowMemory=0,g_guest_onConfigurationChanged=0,g_guest_onSaveInstanceState=0;
static uint32_t g_guest_onNativeWindowCreated=0,g_guest_onNativeWindowResized=0,g_guest_onNativeWindowRedrawNeeded=0,g_guest_onNativeWindowDestroyed=0;
static uint32_t g_guest_onInputQueueCreated=0,g_guest_onInputQueueDestroyed=0,g_guest_onContentRectChanged=0;
static uint32_t g_shadow_activity_addr=0;
static void host_fwd_void(uint32_t cb){ if(!cb||!g_emulator) return; uint32_t r=0; g_emulator->call(cb,{g_shadow_activity_addr},r,"NA fwd"); }
static void hc_onStart(ANativeActivity*a){(void)a;host_fwd_void(g_guest_onStart);}
static void hc_onResume(ANativeActivity*a){(void)a;host_fwd_void(g_guest_onResume);}
static void hc_onPause(ANativeActivity*a){(void)a;host_fwd_void(g_guest_onPause);}
static void hc_onStop(ANativeActivity*a){(void)a;host_fwd_void(g_guest_onStop);}
static void hc_onDestroy(ANativeActivity*a){(void)a;host_fwd_void(g_guest_onDestroy);}
static void hc_onLowMemory(ANativeActivity*a){(void)a;host_fwd_void(g_guest_onLowMemory);}
static void hc_onConfigurationChanged(ANativeActivity*a){(void)a;host_fwd_void(g_guest_onConfigurationChanged);}
static void hc_onWindowFocusChanged(ANativeActivity*a,int f){(void)a;(void)f;host_fwd_void(g_guest_onWindowFocusChanged);}
static void* hc_onSaveInstanceState(ANativeActivity*a,size_t*out){(void)a;if(out)*out=0;if(!g_guest_onSaveInstanceState||!g_emulator)return nullptr;uint32_t r=0;g_emulator->call(g_guest_onSaveInstanceState,{g_shadow_activity_addr},r,"NA save");return nullptr;}
static void hc_onNativeWindowCreated(ANativeActivity*a,ANativeWindow*w){(void)a;(void)w;host_fwd_void(g_guest_onNativeWindowCreated);}
static void hc_onNativeWindowResized(ANativeActivity*a,ANativeWindow*w){(void)a;(void)w;host_fwd_void(g_guest_onNativeWindowResized);}
static void hc_onNativeWindowRedrawNeeded(ANativeActivity*a,ANativeWindow*w){(void)a;(void)w;host_fwd_void(g_guest_onNativeWindowRedrawNeeded);}
static void hc_onNativeWindowDestroyed(ANativeActivity*a,ANativeWindow*w){(void)a;(void)w;host_fwd_void(g_guest_onNativeWindowDestroyed);}
static void hc_onInputQueueCreated(ANativeActivity*a,AInputQueue*q){(void)a;(void)q;host_fwd_void(g_guest_onInputQueueCreated);}
static void hc_onInputQueueDestroyed(ANativeActivity*a,AInputQueue*q){(void)a;(void)q;host_fwd_void(g_guest_onInputQueueDestroyed);}
static void hc_onContentRectChanged(ANativeActivity*a,const ARect*r){(void)a;(void)r;host_fwd_void(g_guest_onContentRectChanged);}
static ANativeActivityCallbacks g_host_callbacks={};
static bool g_cb_init=false;
static void init_host_callbacks(){ if(g_cb_init) return;
g_host_callbacks.onStart=hc_onStart;g_host_callbacks.onResume=hc_onResume;g_host_callbacks.onPause=hc_onPause;g_host_callbacks.onStop=hc_onStop;g_host_callbacks.onDestroy=hc_onDestroy;
g_host_callbacks.onWindowFocusChanged=hc_onWindowFocusChanged;g_host_callbacks.onNativeWindowCreated=hc_onNativeWindowCreated;g_host_callbacks.onNativeWindowResized=hc_onNativeWindowResized;g_host_callbacks.onNativeWindowRedrawNeeded=hc_onNativeWindowRedrawNeeded;g_host_callbacks.onNativeWindowDestroyed=hc_onNativeWindowDestroyed;
g_host_callbacks.onInputQueueCreated=hc_onInputQueueCreated;g_host_callbacks.onInputQueueDestroyed=hc_onInputQueueDestroyed;g_host_callbacks.onContentRectChanged=hc_onContentRectChanged;g_host_callbacks.onConfigurationChanged=hc_onConfigurationChanged;g_host_callbacks.onLowMemory=hc_onLowMemory;g_host_callbacks.onSaveInstanceState=hc_onSaveInstanceState;g_cb_init=true;}
extern "C" JNIEXPORT void ANativeActivity_finish(ANativeActivity*a){(void)a;}
extern "C" JNIEXPORT void ANativeActivity_setWindowFormat(ANativeActivity*a,int32_t f){(void)a;(void)f;}
extern "C" JNIEXPORT void ANativeActivity_setWindowFlags(ANativeActivity*a,uint32_t add,uint32_t rem){(void)a;(void)add;(void)rem;}
extern "C" JNIEXPORT void ANativeActivity_showSoftInput(ANativeActivity*a,uint32_t f){(void)a;(void)f;}
extern "C" JNIEXPORT void ANativeActivity_hideSoftInput(ANativeActivity*a,uint32_t f){(void)a;(void)f;}
extern "C" JNIEXPORT void ANativeActivity_onCreate(ANativeActivity* activity, void* savedState, size_t savedStateSize) {
    LOGI("ANativeActivity_onCreate invoked by framework (%p)", (void*)activity);
    if (!activity) return;
    init_host_callbacks();
    activity->callbacks = &g_host_callbacks;
    if (!g_bridge || !g_emulator) { LOGW("bridge not initialised"); return; }
    uint32_t shadow = g_emulator->alloc(sizeof(ANativeActivity32), 8);
    if (!shadow) { LOGE("shadow alloc failed"); return; }
    g_shadow_activity_addr = shadow;
    const char* internal = activity->internalDataPath ? activity->internalDataPath : "";
    const char* external = activity->externalDataPath ? activity->externalDataPath : "";
    const char* obb = activity->obbPath ? activity->obbPath : "";
    uint32_t si = g_emulator->alloc(strlen(internal)+1,4);
    uint32_t se = g_emulator->alloc(strlen(external)+1,4);
    uint32_t so = g_emulator->alloc(strlen(obb)+1,4);
    if(si) g_emulator->write(si,internal,strlen(internal)+1);
    if(se) g_emulator->write(se,external,strlen(external)+1);
    if(so) g_emulator->write(so,obb,strlen(obb)+1);
    uint32_t ss = 0;
    if (savedState && savedStateSize>0) { ss = g_emulator->alloc(savedStateSize,8); if(ss) g_emulator->write(ss,savedState,savedStateSize); }
    uint32_t cb = g_emulator->alloc(sizeof(ANativeActivityCallbacks32),8);
    std::vector<uint8_t> zero(sizeof(ANativeActivityCallbacks32),0);
    if(cb) g_emulator->write(cb,zero.data(),zero.size());
    ANativeActivity32 sa{};
    sa.callbacks=cb; sa.vm=g_bridge->vm_shadow(); sa.env=g_bridge->env_shadow();
    sa.clazz = g_bridge->to_guest_ref(activity->clazz);
    sa.internalDataPath=si; sa.externalDataPath=se; sa.sdkVersion=activity->sdkVersion;
    sa.instance=0; sa.assetManager=0; sa.obbPath=so;
    g_emulator->write(shadow,&sa,sizeof(sa));
    uint32_t guest_onCreate=0;
    for (const auto* lib : g_bridge->libraries()) { uint32_t fn=JniBridge::export_of(lib,"ANativeActivity_onCreate"); if(fn){guest_onCreate=fn;break;} }
    if (!guest_onCreate) { LOGW("no guest ANativeActivity_onCreate"); return; }
    uint32_t ret=0;
    g_emulator->call(guest_onCreate,{shadow,ss,(uint32_t)savedStateSize},ret,"guest ANativeActivity_onCreate");
    ANativeActivityCallbacks32 c32{};
    if(cb) g_emulator->read(cb,&c32,sizeof(c32));
    g_guest_onStart=c32.onStart; g_guest_onResume=c32.onResume; g_guest_onSaveInstanceState=c32.onSaveInstanceState;
    g_guest_onPause=c32.onPause; g_guest_onStop=c32.onStop; g_guest_onDestroy=c32.onDestroy;
    g_guest_onWindowFocusChanged=c32.onWindowFocusChanged; g_guest_onNativeWindowCreated=c32.onNativeWindowCreated;
    g_guest_onNativeWindowResized=c32.onNativeWindowResized; g_guest_onNativeWindowRedrawNeeded=c32.onNativeWindowRedrawNeeded;
    g_guest_onNativeWindowDestroyed=c32.onNativeWindowDestroyed; g_guest_onInputQueueCreated=c32.onInputQueueCreated;
    g_guest_onInputQueueDestroyed=c32.onInputQueueDestroyed; g_guest_onContentRectChanged=c32.onContentRectChanged;
    g_guest_onConfigurationChanged=c32.onConfigurationChanged; g_guest_onLowMemory=c32.onLowMemory;
    LOGI("ANativeActivity_onCreate bridge complete");
}
// NATIVE_ACTIVITY_BRIDGE_DONE
// NATIVE_ACTIVITY_BRIDGE_END

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    (void)vm; (void)reserved;
    LOGI("JNI_OnUnload");
}


