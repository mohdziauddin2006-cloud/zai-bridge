// =============================================================================
//  cpu_compat.cpp  (Zai-Bridge: universal mobile CPU compatibility layer)
// =============================================================================

#include "cpu_compat.h"

#include <android/log.h>
#include <sched.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <map>
#include <mutex>
#include <sstream>

#ifndef CPU_SETSIZE
#include <sys/types.h>
#endif

#define LOG_TAG "ARM64_WRAPPER"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {
namespace cpu_ns = zai::cpu;

std::string read_text(const std::string& path) {
    FILE* f = ::fopen(path.c_str(), "re");
    if (f == nullptr) return {};
    std::string out;
    char buf[512];
    size_t n = 0;
    while ((n = ::fread(buf, 1, sizeof buf, f)) > 0) out.append(buf, n);
    ::fclose(f);
    return out;
}

std::string trim(std::string s) {
    size_t b = 0;
    while (b < s.size() && std::isspace(static_cast<unsigned char>(s[b]))) ++b;
    size_t e = s.size();
    while (e > b && std::isspace(static_cast<unsigned char>(s[e - 1]))) --e;
    return s.substr(b, e - b);
}

// Reads the value after "Key" in a cpuinfo-style text block.
std::string info_value(const std::string& text, const char* key) {
    const std::string want(key);
    size_t at = 0;
    while ((at = text.find(want, at)) != std::string::npos) {
        const size_t colon = text.find(':', at + want.size());
        if (colon == std::string::npos) break;
        const size_t eol = text.find('\n', colon);
        return trim(text.substr(colon + 1, (eol == std::string::npos ? text.size() : eol) - colon - 1));
    }
    return {};
}

long read_number(const std::string& path) {
    const std::string s = trim(read_text(path));
    if (s.empty()) return -1;
    char* end = nullptr;
    const long v = std::strtol(s.c_str(), &end, 10);
    return (end && end != s.c_str()) ? v : -1;
}

std::string lower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return s;
}

std::string detect_soc(const std::string& host_cpuinfo, std::string* hardware_out) {
    std::string machine = trim(read_text("/sys/devices/soc0/machine"));
    if (machine.empty()) machine = trim(read_text("/proc/device-tree/model"));
    std::string hardware = info_value(host_cpuinfo, "Hardware");
    if (hardware.empty()) hardware = machine;
    if (hardware_out != nullptr) *hardware_out = hardware;

    const std::string probe = lower(machine + " " + hardware + " " + host_cpuinfo);
    if (probe.find("qcom") != std::string::npos ||
        probe.find("snapdragon") != std::string::npos ||
        probe.find("qualcomm") != std::string::npos) {
        return "Snapdragon";
    }
    if (probe.find("mt6") != std::string::npos ||
        probe.find("mt8") != std::string::npos ||
        probe.find("mediatek") != std::string::npos ||
        probe.find("dimensity") != std::string::npos ||
        probe.find("helio") != std::string::npos) {
        return "Dimensity/Helio";
    }
    if (probe.find("exynos") != std::string::npos ||
        probe.find("universal") != std::string::npos ||
        probe.find("samsung") != std::string::npos) {
        return "Exynos";
    }
    if (probe.find("tensor") != std::string::npos ||
        probe.find("gs101") != std::string::npos ||
        probe.find("gs201") != std::string::npos) {
        return "Tensor";
    }
    if (probe.find("kirin") != std::string::npos ||
        probe.find("hi3") != std::string::npos ||
        probe.find("hisilicon") != std::string::npos) {
        return "Kirin";
    }
    if (probe.find("unisoc") != std::string::npos ||
        probe.find("sprd") != std::string::npos ||
        probe.find("sc98") != std::string::npos) {
        return "Unisoc";
    }
    return "generic ARM";
}

cpu_ns::Topology detect() {
    cpu_ns::Topology t;

    const long online = ::sysconf(_SC_NPROCESSORS_ONLN);
    const long conf = ::sysconf(_SC_NPROCESSORS_CONF);
    t.cores_online = online > 0 ? static_cast<int>(online) : 1;
    t.cores_conf = conf > 0 ? static_cast<int>(conf) : t.cores_online;

    const std::string host_cpuinfo = read_text("/proc/cpuinfo");
    t.hardware = info_value(host_cpuinfo, "Hardware");
    t.features = info_value(host_cpuinfo, "Features");
    t.soc = detect_soc(host_cpuinfo, &t.hardware);

    // big.LITTLE / DynamIQ: group cores by the maximum frequency of their
    // cpufreq policy, falling back to cpu_capacity, then to core index order.
    std::map<int, std::vector<int>> groups;
    for (int i = 0; i < t.cores_conf; ++i) {
        char path[128];
        std::snprintf(path, sizeof path,
                      "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        long freq = read_number(path);
        if (freq <= 0) {
            std::snprintf(path, sizeof path,
                          "/sys/devices/system/cpu/cpu%d/cpu_capacity", i);
            freq = read_number(path);
        }
        if (freq > 0) groups[static_cast<int>(freq)].push_back(i);
    }

    if (groups.size() > 1) {
        t.clusters = static_cast<int>(groups.size());
        auto top = groups.rbegin();          // highest frequency / capacity
        t.performance_cores = top->second;
        for (auto it = groups.begin(); it != groups.end(); ++it) {
            if (it->first == top->first) continue;
            t.efficiency_cores.insert(t.efficiency_cores.end(),
                                      it->second.begin(), it->second.end());
        }
    } else if (t.cores_online >= 4) {
        // No cpufreq information (a locked-down kernel or an emulator). The
        // convention on every heterogeneous part is that the later cores are
        // the big ones, so pin to the upper half.
        t.clusters = 2;
        const int half = t.cores_online / 2;
        for (int i = half; i < t.cores_online; ++i) t.performance_cores.push_back(i);
        for (int i = 0; i < half; ++i) t.efficiency_cores.push_back(i);
    } else {
        for (int i = 0; i < t.cores_online; ++i) t.performance_cores.push_back(i);
    }

    if (t.performance_cores.empty()) {
        for (int i = 0; i < t.cores_online; ++i) t.performance_cores.push_back(i);
    }

    // Never offer more than four cores to Unicorn: its TCG loop is single
    // threaded, and a wider affinity mask only invites migrations.
    if (t.performance_cores.size() > 4) {
        t.performance_cores.resize(4);
    }
    return t;
}

}  // namespace

namespace zai {
namespace cpu {

const Topology& topology() {
    static Topology detected;
    static std::once_flag once;
    std::call_once(once, [] {
        detected = detect();
        // Build the core list into a named string: returning c_str() from a
        // temporary would log a dangling pointer.
        std::string perf;
        for (int c : detected.performance_cores) {
            if (!perf.empty()) perf += ",";
            perf += std::to_string(c);
        }
        LOGI("CPU topology: cores=%d conf=%d clusters=%d soc=%s perf=[%s]",
             detected.cores_online, detected.cores_conf, detected.clusters,
             detected.soc.c_str(), perf.c_str());
    });
    return detected;
}

uint32_t guest_hwcap() {
    // ARMv7-A, Thumb-2, fast multiplies, VFPv3-D32, NEON, TLS, VFPv4,
    // integer divide, LPAE and the ARMv8 crypto/eventstream bits. Reporting the
    // full modern set is what keeps a game from selecting a legacy code path.
    constexpr uint32_t kSwp       = 1u << 0;
    constexpr uint32_t kHalf      = 1u << 1;
    constexpr uint32_t kThumb     = 1u << 2;
    constexpr uint32_t kFastMult  = 1u << 4;
    constexpr uint32_t kVfp       = 1u << 6;
    constexpr uint32_t kEdsp      = 1u << 7;
    constexpr uint32_t kNeon      = 1u << 12;
    constexpr uint32_t kVfpv3     = 1u << 13;
    constexpr uint32_t kTls       = 1u << 15;
    constexpr uint32_t kVfpv4     = 1u << 16;
    constexpr uint32_t kIdiva     = 1u << 17;
    constexpr uint32_t kIdivt     = 1u << 18;
    constexpr uint32_t kVfpd32    = 1u << 19;
    constexpr uint32_t kLpae      = 1u << 20;
    constexpr uint32_t kEvtstrm   = 1u << 21;
    constexpr uint32_t kAes       = 1u << 22;
    constexpr uint32_t kPmull     = 1u << 23;
    constexpr uint32_t kSha1      = 1u << 24;
    constexpr uint32_t kSha2      = 1u << 25;
    constexpr uint32_t kCrc32     = 1u << 26;
    return kSwp | kHalf | kThumb | kFastMult | kVfp | kEdsp | kNeon | kVfpv3 |
           kTls | kVfpv4 | kIdiva | kIdivt | kVfpd32 | kLpae | kEvtstrm |
           kAes | kPmull | kSha1 | kSha2 | kCrc32;
}

uint32_t guest_hwcap2() {
    constexpr uint32_t kAes = 1u << 0;    // HWCAP2_AES
    constexpr uint32_t kPmull = 1u << 1;  // HWCAP2_PMULL
    constexpr uint32_t kSha1 = 1u << 2;
    constexpr uint32_t kSha2 = 1u << 3;
    constexpr uint32_t kCrc32 = 1u << 4;
    return kAes | kPmull | kSha1 | kSha2 | kCrc32;
}

std::string cpuinfo_text() {
    const Topology& t = topology();
    std::ostringstream out;

    // One block per core: several older engines count "processor" lines and
    // decide their worker-thread count from it.
    for (int i = 0; i < t.cores_online; ++i) {
        out << "processor\t: " << i << "\n";
        out << "model name\t: ARMv7 Processor rev 4 (v7l)\n";
        out << "BogoMIPS\t: 38.40\n";
        out << "Features\t: swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 "
               "idiva idivt\n";
        out << "CPU implementer\t: 0x41\n";
        out << "CPU architecture: 7\n";
        out << "CPU variant\t: 0x0\n";
        out << "CPU part\t: 0xc09\n";
        out << "CPU revision\t: 4\n\n";
    }
    out << "Hardware\t: " << (t.hardware.empty() ? std::string("Zai-Bridge Mobile")
                                                 : t.hardware) << "\n";
    out << "Revision\t: 0000\n";
    out << "Serial\t\t: 0000000000000000\n";
    return out.str();
}

std::string virtual_cpuinfo_path(const std::string& cache_dir) {
    if (cache_dir.empty()) return {};
    ::mkdir(cache_dir.c_str(), 0755);
    const std::string path = cache_dir + "/.zai-cpuinfo";
    struct stat st {};
    if (::stat(path.c_str(), &st) == 0 && st.st_size > 0) return path;

    FILE* f = ::fopen(path.c_str(), "we");
    if (f == nullptr) return {};
    const std::string text = cpuinfo_text();
    ::fwrite(text.data(), 1, text.size(), f);
    ::fclose(f);
    LOGI("virtual /proc/cpuinfo published at %s (%zu bytes)", path.c_str(), text.size());
    return path;
}

int pin_current_thread_to_performance() {
    const Topology& t = topology();
    if (t.performance_cores.empty()) return 0;

    cpu_set_t set;
    CPU_ZERO(&set);
    for (int core : t.performance_cores) {
        if (core >= 0 && core < CPU_SETSIZE) CPU_SET(core, &set);
    }
    if (::sched_setaffinity(0, sizeof set, &set) != 0) {
        LOGW("sched_setaffinity(perf cluster) refused (errno=%d); running unpinned",
             errno);
        return 0;
    }
    LOGI("emulation thread pinned to performance cores (%zu of %d)",
         t.performance_cores.size(), t.cores_online);
    return static_cast<int>(t.performance_cores.size());
}

std::string summary() {
    const Topology& t = topology();
    std::ostringstream out;
    out << "cores=" << t.cores_online << "/" << t.cores_conf
        << " clusters=" << t.clusters
        << " soc=" << t.soc
        << " hwcap=0x" << std::hex << guest_hwcap()
        << " perf=";
    for (size_t i = 0; i < t.performance_cores.size(); ++i) {
        if (i) out << ",";
        out << t.performance_cores[i];
    }
    return out.str();
}

}  // namespace cpu
}  // namespace zai
