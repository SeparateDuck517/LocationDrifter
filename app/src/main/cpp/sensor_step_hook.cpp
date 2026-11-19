#include <jni.h>
#include <android/log.h>
#include <android/sensor.h>
#include <shadowhook.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <elf.h>
#include <fcntl.h>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#define LOG_TAG "LocationDrifterSensor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int32_t kSensorTypeStepDetector = 18;
constexpr int32_t kSensorTypeStepCounter = 19;

using SendEventsFn = int32_t (*)(
    void* self,
    const ASensorEvent* buffer,
    size_t numEvents,
    ASensorEvent* scratch,
    const void* mapFlushEventsToConnections
);

SendEventsFn g_orig_send_events = nullptr;
void* g_hook_stub = nullptr;

std::atomic<bool> g_hook_installed{false};
std::atomic<bool> g_step_sim_enabled{false};
std::atomic<uint64_t> g_sim_step_delta{0};
std::atomic<uint64_t> g_last_pushed_delta{0};
std::atomic<uint64_t> g_last_real_step_counter{0};
std::atomic<uint64_t> g_last_emitted_step_counter{0};
std::atomic<int32_t> g_step_counter_handle{-1};

std::string g_hook_status = "uninitialized";

int64_t nowBoottimeNanos() {
    struct timespec ts{};
    if (clock_gettime(CLOCK_BOOTTIME, &ts) == 0) {
        return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + static_cast<int64_t>(ts.tv_nsec);
    }
    return 0;
}

/**
 * 拦截 SensorService::SensorEventConnection::sendEvents（运行于 system_server 进程内）：
 * - 严格零三方应用注入，仅修改发往客户端的 STEP_COUNTER / STEP_DETECTOR 传感器事件；
 * - 未开启实验性步数模拟时零拷贝直通原函数。
 */
int32_t hookedSendEvents(
    void* self,
    const ASensorEvent* buffer,
    size_t numEvents,
    ASensorEvent* scratch,
    const void* mapFlushEventsToConnections
) {
    if (!g_orig_send_events) {
        return 0;
    }
    if (buffer == nullptr || numEvents == 0 || numEvents > 512) {
        return g_orig_send_events(self, buffer, numEvents, scratch, mapFlushEventsToConnections);
    }

    const bool enabled = g_step_sim_enabled.load(std::memory_order_relaxed);
    const uint64_t simDelta = g_sim_step_delta.load(std::memory_order_relaxed);

    // 先轻量记录真实 STEP_COUNTER 句柄与基线，便于静止状态下合成增量事件
    bool hasStepCounterInBatch = false;
    for (size_t i = 0; i < numEvents; ++i) {
        if (buffer[i].type == kSensorTypeStepCounter) {
            hasStepCounterInBatch = true;
            g_step_counter_handle.store(buffer[i].sensor, std::memory_order_relaxed);
            g_last_real_step_counter.store(buffer[i].u64.step_counter, std::memory_order_relaxed);
        }
    }

    if (!enabled || simDelta == 0) {
        return g_orig_send_events(self, buffer, numEvents, scratch, mapFlushEventsToConnections);
    }

    const uint64_t lastPushedDelta = g_last_pushed_delta.load(std::memory_order_relaxed);
    const int32_t stepHandle = g_step_counter_handle.load(std::memory_order_relaxed);
    const bool needSyntheticAppend = !hasStepCounterInBatch && (stepHandle >= 0) && (simDelta > lastPushedDelta);

    if (!hasStepCounterInBatch && !needSyntheticAppend) {
        return g_orig_send_events(self, buffer, numEvents, scratch, mapFlushEventsToConnections);
    }

    constexpr size_t kStackCap = 64;
    ASensorEvent stackBuf[kStackCap];
    std::vector<ASensorEvent> heapBuf;
    const size_t outCount = numEvents + (needSyntheticAppend ? 1 : 0);

    ASensorEvent* outBuf = stackBuf;
    if (outCount > kStackCap) {
        heapBuf.resize(outCount);
        outBuf = heapBuf.data();
    }

    std::memcpy(outBuf, buffer, numEvents * sizeof(ASensorEvent));

    for (size_t i = 0; i < numEvents; ++i) {
        if (outBuf[i].type == kSensorTypeStepCounter) {
            uint64_t candidate = outBuf[i].u64.step_counter + simDelta;
            uint64_t prev = g_last_emitted_step_counter.load(std::memory_order_relaxed);
            if (candidate < prev) {
                candidate = prev;
            }
            outBuf[i].u64.step_counter = candidate;
            g_last_emitted_step_counter.store(candidate, std::memory_order_relaxed);
            g_last_pushed_delta.store(simDelta, std::memory_order_relaxed);
        }
    }

    if (needSyntheticAppend) {
        ASensorEvent syn{};
        syn.version = static_cast<int32_t>(sizeof(ASensorEvent));
        syn.sensor = stepHandle;
        syn.type = kSensorTypeStepCounter;
        syn.timestamp = nowBoottimeNanos();
        uint64_t baseSteps = g_last_real_step_counter.load(std::memory_order_relaxed);
        uint64_t candidate = baseSteps + simDelta;
        uint64_t prev = g_last_emitted_step_counter.load(std::memory_order_relaxed);
        if (candidate < prev) {
            candidate = prev;
        }
        syn.u64.step_counter = candidate;
        outBuf[numEvents] = syn;
        g_last_emitted_step_counter.store(candidate, std::memory_order_relaxed);
        g_last_pushed_delta.store(simDelta, std::memory_order_relaxed);
    }

    return g_orig_send_events(self, outBuf, outCount, scratch, mapFlushEventsToConnections);
}

/**
 * 在已映射到当前进程 (system_server) 的 libsensorservice.so 中查找导出符号名
 * 匹配同时包含 "SensorEventConnection" 与 "sendEvents" 的 C++ mangled 符号。
 */
std::string findSendEventsSymbolInLoadedElf() {
    FILE* maps = std::fopen("/proc/self/maps", "r");
    if (!maps) return "";

    std::string libPath;
    char line[512];
    while (std::fgets(line, sizeof(line), maps)) {
        if (std::strstr(line, "libsensorservice.so") != nullptr) {
            char* pathStart = std::strchr(line, '/');
            if (pathStart) {
                char* nl = std::strchr(pathStart, '\n');
                if (nl) *nl = '\0';
                libPath = pathStart;
                break;
            }
        }
    }
    std::fclose(maps);

    if (libPath.empty()) return "";

    int fd = ::open(libPath.c_str(), O_RDONLY);
    if (fd < 0) return "";

    struct stat st{};
    if (::fstat(fd, &st) != 0 || st.st_size < static_cast<off_t>(sizeof(Elf64_Ehdr))) {
        ::close(fd);
        return "";
    }

    void* mapBase = ::mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ, MAP_PRIVATE, fd, 0);
    ::close(fd);
    if (mapBase == MAP_FAILED) return "";

    std::string matched;
    const auto* bytes = static_cast<const uint8_t*>(mapBase);
    const size_t fileSize = static_cast<size_t>(st.st_size);

#if defined(__LP64__)
    const auto* ehdr = reinterpret_cast<const Elf64_Ehdr*>(bytes);
    if (std::memcmp(ehdr->e_ident, ELFMAG, SELFMAG) == 0 &&
        ehdr->e_ident[EI_CLASS] == ELFCLASS64 &&
        ehdr->e_shoff > 0 &&
        ehdr->e_shoff + static_cast<size_t>(ehdr->e_shnum) * sizeof(Elf64_Shdr) <= fileSize) {
        const auto* shdrs = reinterpret_cast<const Elf64_Shdr*>(bytes + ehdr->e_shoff);
        for (uint16_t i = 0; i < ehdr->e_shnum && matched.empty(); ++i) {
            if (shdrs[i].sh_type != SHT_DYNSYM && shdrs[i].sh_type != SHT_SYMTAB) continue;
            if (shdrs[i].sh_link >= ehdr->e_shnum) continue;
            const auto& strSec = shdrs[shdrs[i].sh_link];
            if (shdrs[i].sh_offset + shdrs[i].sh_size > fileSize ||
                strSec.sh_offset + strSec.sh_size > fileSize) continue;
            const auto* syms = reinterpret_cast<const Elf64_Sym*>(bytes + shdrs[i].sh_offset);
            const size_t symCount = shdrs[i].sh_size / sizeof(Elf64_Sym);
            const char* strtab = reinterpret_cast<const char*>(bytes + strSec.sh_offset);
            for (size_t s = 0; s < symCount; ++s) {
                if (syms[s].st_name == 0 || syms[s].st_name >= strSec.sh_size) continue;
                const char* name = strtab + syms[s].st_name;
                if (std::strstr(name, "SensorEventConnection") != nullptr &&
                    std::strstr(name, "sendEvents") != nullptr) {
                    matched = name;
                    break;
                }
            }
        }
    }
#else
    const auto* ehdr = reinterpret_cast<const Elf32_Ehdr*>(bytes);
    if (std::memcmp(ehdr->e_ident, ELFMAG, SELFMAG) == 0 &&
        ehdr->e_ident[EI_CLASS] == ELFCLASS32 &&
        ehdr->e_shoff > 0 &&
        ehdr->e_shoff + static_cast<size_t>(ehdr->e_shnum) * sizeof(Elf32_Shdr) <= fileSize) {
        const auto* shdrs = reinterpret_cast<const Elf32_Shdr*>(bytes + ehdr->e_shoff);
        for (uint16_t i = 0; i < ehdr->e_shnum && matched.empty(); ++i) {
            if (shdrs[i].sh_type != SHT_DYNSYM && shdrs[i].sh_type != SHT_SYMTAB) continue;
            if (shdrs[i].sh_link >= ehdr->e_shnum) continue;
            const auto& strSec = shdrs[shdrs[i].sh_link];
            if (shdrs[i].sh_offset + shdrs[i].sh_size > fileSize ||
                strSec.sh_offset + strSec.sh_size > fileSize) continue;
            const auto* syms = reinterpret_cast<const Elf32_Sym*>(bytes + shdrs[i].sh_offset);
            const size_t symCount = shdrs[i].sh_size / sizeof(Elf32_Sym);
            const char* strtab = reinterpret_cast<const char*>(bytes + strSec.sh_offset);
            for (size_t s = 0; s < symCount; ++s) {
                if (syms[s].st_name == 0 || syms[s].st_name >= strSec.sh_size) continue;
                const char* name = strtab + syms[s].st_name;
                if (std::strstr(name, "SensorEventConnection") != nullptr &&
                    std::strstr(name, "sendEvents") != nullptr) {
                    matched = name;
                    break;
                }
            }
        }
    }
#endif

    ::munmap(mapBase, fileSize);
    return matched;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_separ_locationdrifter_xposed_NativeStepBridge_nativeInstallStepHook(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    if (g_hook_installed.load(std::memory_order_acquire)) {
        return 0;
    }

    int initRet = shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false);
    if (initRet != 0 && initRet != SHADOWHOOK_ERRNO_OK) {
        g_hook_status = "shadowhook_init_failed:" + std::to_string(initRet);
        LOGW("shadowhook_init failed: %d", initRet);
        return -1;
    }

    void* handle = shadowhook_dlopen("libsensorservice.so");
    if (!handle) {
        g_hook_status = "dlopen_failed";
        LOGW("shadowhook_dlopen(libsensorservice.so) failed");
        return -2;
    }

    // 优先通过 ELF 符号表动态匹配当前 ROM 精确的 C++ mangled 符号
    std::vector<std::string> candidates;
    std::string discovered = findSendEventsSymbolInLoadedElf();
    if (!discovered.empty()) {
        candidates.push_back(discovered);
    }
    // AOSP Android 9–16 常见 mangled 符号兜底列表
    candidates.emplace_back("_ZN7android13SensorService21SensorEventConnection10sendEventsEPK15sensors_event_tmPS2_PKNS_2wpIKS1_EE");
    candidates.emplace_back("_ZN7android13SensorService21SensorEventConnection10sendEventsEPK15sensors_event_tjPS2_PKNS_2wpIKS1_EE");
    candidates.emplace_back("_ZN7android13SensorService21SensorEventConnection10sendEventsEPK15sensors_event_tmPS2_PNS_2wpIS1_EE");

    void* targetAddr = nullptr;
    std::string matchedSym;
    for (const auto& sym : candidates) {
        void* addr = shadowhook_dlsym(handle, sym.c_str());
        if (addr != nullptr) {
            targetAddr = addr;
            matchedSym = sym;
            break;
        }
    }
    shadowhook_dlclose(handle);

    if (!targetAddr) {
        g_hook_status = "symbol_not_found";
        LOGW("SensorEventConnection::sendEvents symbol not found in libsensorservice.so");
        return -3;
    }

    void* orig = nullptr;
    void* stub = shadowhook_hook_sym_addr(
        targetAddr,
        reinterpret_cast<void*>(hookedSendEvents),
        &orig
    );
    if (!stub || !orig) {
        int err = shadowhook_get_errno();
        g_hook_status = "hook_failed:" + std::to_string(err);
        LOGW("shadowhook_hook_sym_addr failed: %d", err);
        return -4;
    }

    g_orig_send_events = reinterpret_cast<SendEventsFn>(orig);
    g_hook_stub = stub;
    g_hook_installed.store(true, std::memory_order_release);
    g_hook_status = "active:" + matchedSym;
    LOGI("Installed system_server step sensor hook on %s", matchedSym.c_str());
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_separ_locationdrifter_xposed_NativeStepBridge_nativeUpdateStepState(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jboolean enabled,
    jlong simStepDelta
) {
    bool isEn = (enabled == JNI_TRUE);
    uint64_t delta = simStepDelta > 0 ? static_cast<uint64_t>(simStepDelta) : 0ULL;
    g_step_sim_enabled.store(isEn, std::memory_order_relaxed);
    g_sim_step_delta.store(delta, std::memory_order_relaxed);
    if (!isEn) {
        g_last_pushed_delta.store(0, std::memory_order_relaxed);
        g_last_emitted_step_counter.store(0, std::memory_order_relaxed);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_separ_locationdrifter_xposed_NativeStepBridge_nativeGetHookStatus(
    JNIEnv* env,
    jobject /*thiz*/
) {
    return env->NewStringUTF(g_hook_status.c_str());
}
