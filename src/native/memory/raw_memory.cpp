#include <jni.h>
#include <cstdint>
#include <limits>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
#include <fstream>
#ifdef _WIN32
#define NOMINMAX
#include <windows.h>
#else
#include <sys/uio.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif

namespace {
constexpr jint limit = 1024 * 1024;
std::mutex memory_operations;
struct Region { uintptr_t start, end; bool read, write, execute; };
void fail(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/io/IOException");
    if (type) env->ThrowNew(type, message);
}
std::vector<Region> regions() {
    std::vector<Region> result;
#ifdef _WIN32
    uintptr_t address = 0;
    MEMORY_BASIC_INFORMATION info{};
    while (VirtualQuery(reinterpret_cast<void*>(address), &info, sizeof(info))) {
        uintptr_t start = reinterpret_cast<uintptr_t>(info.BaseAddress);
        if (info.RegionSize > std::numeric_limits<uintptr_t>::max() - start) break;
        uintptr_t end = start + info.RegionSize;
        DWORD p = info.Protect & 0xff;
        if (info.State == MEM_COMMIT && !(info.Protect & PAGE_GUARD) && p != PAGE_NOACCESS) {
            bool r = p == PAGE_READONLY || p == PAGE_READWRITE || p == PAGE_WRITECOPY
                || p == PAGE_EXECUTE_READ || p == PAGE_EXECUTE_READWRITE || p == PAGE_EXECUTE_WRITECOPY;
            bool w = p == PAGE_READWRITE || p == PAGE_WRITECOPY || p == PAGE_EXECUTE_READWRITE || p == PAGE_EXECUTE_WRITECOPY;
            bool x = p == PAGE_EXECUTE || p == PAGE_EXECUTE_READ || p == PAGE_EXECUTE_READWRITE || p == PAGE_EXECUTE_WRITECOPY;
            result.push_back({start, end, r, w, x});
        }
        if (end <= address) break;
        address = end;
    }
#else
    std::ifstream maps("/proc/self/maps");
    if (!maps) throw std::runtime_error("Cannot read /proc/self/maps");
    std::string line;
    while (std::getline(maps, line)) {
        std::istringstream input(line);
        uintptr_t start, end;
        char dash;
        std::string permissions;
        if (!(input >> std::hex >> start >> dash >> end >> permissions) || dash != '-' || permissions.size() < 3)
            throw std::runtime_error("Invalid process memory map");
        result.push_back({start, end, permissions[0] == 'r', permissions[1] == 'w', permissions[2] == 'x'});
    }
#endif
    return result;
}
uintptr_t validate(jlong address, jint length, bool write) {
    uint64_t value = static_cast<uint64_t>(address);
    if (!value || length < 1 || length > limit || value > std::numeric_limits<uintptr_t>::max()
            || static_cast<uint64_t>(length) > std::numeric_limits<uintptr_t>::max() - value)
        throw std::runtime_error("Invalid address or length (maximum 1 MiB)");
    uintptr_t start = static_cast<uintptr_t>(value), end = start + length;
    for (const auto& region : regions()) {
        if (start >= region.start && end <= region.end && region.read
                && (!write || (region.write && !region.execute))) return start;
    }
    throw std::runtime_error("Range must fit one readable region: patches require writable non-executable data");
}
void transfer(uintptr_t address, void* bytes, size_t size, bool write) {
#ifdef _WIN32
    SIZE_T count = 0;
    BOOL ok = write ? WriteProcessMemory(GetCurrentProcess(), reinterpret_cast<void*>(address), bytes, size, &count)
                    : ReadProcessMemory(GetCurrentProcess(), reinterpret_cast<void*>(address), bytes, size, &count);
    if (!ok || count != size) throw std::runtime_error("Memory transfer failed or was partial: inspect the range before retrying");
#else
    iovec local{bytes, size}, remote{reinterpret_cast<void*>(address), size};
    ssize_t count = syscall(write ? SYS_process_vm_writev : SYS_process_vm_readv,
                            getpid(), &local, 1UL, &remote, 1UL, 0UL);
    if (count < 0 || static_cast<size_t>(count) != size)
        throw std::runtime_error("Memory transfer failed or was partial, inspect the range before retrying");
#endif
}
}

extern "C" JNIEXPORT jstring JNICALL Java_dev_whitedev_jpi_agent_jvmti_NativeJvmtiBridge_memoryRegions0(JNIEnv* env, jclass) try {
    std::ostringstream output;
    for (const auto& r : regions()) output << std::hex << r.start << '\t' << r.end << '\t'
        << (r.read ? 'r' : '-') << (r.write ? 'w' : '-') << (r.execute ? 'x' : '-') << '\n';
    return env->NewStringUTF(output.str().c_str());
} catch (const std::exception& error) { fail(env, error.what()); return nullptr; }

extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_whitedev_jpi_agent_jvmti_NativeJvmtiBridge_readMemory0(JNIEnv* env, jclass, jlong address, jint length) try {
    std::lock_guard<std::mutex> lock(memory_operations);
    uintptr_t start = validate(address, length, false);
    std::vector<jbyte> bytes(static_cast<size_t>(length));
    transfer(start, bytes.data(), bytes.size(), false);
    jbyteArray result = env->NewByteArray(length);
    if (result) env->SetByteArrayRegion(result, 0, length, bytes.data());
    return result;
} catch (const std::exception& error) { fail(env, error.what()); return nullptr; }

extern "C" JNIEXPORT void JNICALL Java_dev_whitedev_jpi_agent_jvmti_NativeJvmtiBridge_patchMemory0(JNIEnv* env, jclass, jlong address, jbyteArray expected, jbyteArray replacement) try {
    std::lock_guard<std::mutex> lock(memory_operations);
    if (!expected || !replacement) throw std::runtime_error("Expected and replacement bytes are required");
    jint length = env->GetArrayLength(expected);
    if (env->GetArrayLength(replacement) != length) throw std::runtime_error("Patch lengths must match");
    uintptr_t start = validate(address, length, true);
    std::vector<jbyte> before(length), after(length), current(length);
    env->GetByteArrayRegion(expected, 0, length, before.data());
    env->GetByteArrayRegion(replacement, 0, length, after.data());
    if (env->ExceptionCheck()) return;
    transfer(start, current.data(), current.size(), false);
    if (current != before) throw std::runtime_error("Memory changed: expected bytes do not match: nothing written");
    validate(address, length, true);
    transfer(start, after.data(), after.size(), true);
    transfer(start, current.data(), current.size(), false);
    if (current != after) throw std::runtime_error("Patch verification failed: target may have changed the bytes");
} catch (const std::exception& error) { fail(env, error.what()); }
