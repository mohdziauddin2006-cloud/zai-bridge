// =============================================================================
//  cpu_compat.h  (Zai-Bridge: universal mobile CPU compatibility layer)
//
//  The 32-bit guest is told that it runs on an ARMv7-A core with VFPv3-D32 and
//  NEON, whatever the host actually is. This is what makes a Snapdragon,
//  Dimensity/Helio, Exynos, Tensor, Kirin or Unisoc handset advertise the same
//  CPU capabilities: a legacy engine that probes /proc/cpuinfo or AT_HWCAP for
//  NEON never falls back to its (often missing) software renderer.
//
//  The same module detects the host's big.LITTLE clusters and pins the
//  emulation thread to the highest-capacity cluster, so Unicorn's translation
//  loop does not get scheduled onto a little core in the middle of a frame.
// =============================================================================
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace zai {
namespace cpu {

struct Topology {
    int cores_online = 1;                 // sysconf(_SC_NPROCESSORS_ONLN)
    int cores_conf = 1;                   // sysconf(_SC_NPROCESSORS_CONF)
    int clusters = 1;                     // distinct cpufreq/capacity groups
    std::vector<int> performance_cores;   // highest-capacity cluster
    std::vector<int> efficiency_cores;    // everything else
    std::string soc;                      // "Snapdragon", "Dimensity", ...
    std::string hardware;                 // host /proc/cpuinfo Hardware line
    std::string features;                 // host feature list, diagnostics only
};

// Detected once, on first use.
const Topology& topology();

// Synthetic /proc/cpuinfo describing an ARMv7-A + NEON part. This is what the
// emulated guest reads instead of the host's aarch64 description.
std::string cpuinfo_text();

// Writes the synthetic cpuinfo into cache_dir (once) and returns its path, or
// an empty string when the directory is not writable. The translation core
// redirects guest opens of /proc/cpuinfo here.
std::string virtual_cpuinfo_path(const std::string& cache_dir);

// AT_HWCAP / AT_HWCAP2 values an ARMv7-A + VFPv3-D32 + NEON part reports.
uint32_t guest_hwcap();
uint32_t guest_hwcap2();

// Pins the calling thread to the performance cluster. Returns the number of
// cores in the requested set, or 0 when the kernel refused (cpuset limits are
// normal on Android; the caller logs and continues unpinned).
int pin_current_thread_to_performance();

// One-line summary for logcat and the diagnostics UI.
std::string summary();

}  // namespace cpu
}  // namespace zai
