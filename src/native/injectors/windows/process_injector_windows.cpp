#include <jni.h>
#define NOMINMAX
#include <windows.h>
#include <tlhelp32.h>
#include <psapi.h>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
struct IndeterminateRemoteOperation : std::runtime_error {
    using std::runtime_error::runtime_error;
};
struct Handle {
    HANDLE value;
    explicit Handle(HANDLE handle) : value(handle) {}
    ~Handle() { if (value && value != INVALID_HANDLE_VALUE) CloseHandle(value); }
    Handle(const Handle&) = delete;
    Handle& operator=(const Handle&) = delete;
};
struct Image {
    HMODULE value;
    explicit Image(const wchar_t* path) : value(LoadLibraryExW(path, nullptr, DONT_RESOLVE_DLL_REFERENCES)) {}
    ~Image() { if (value) FreeLibrary(value); }
};
struct RemotePath {
    HANDLE process;
    void* value;
    bool retain = false;
    RemotePath(HANDLE p, SIZE_T size) : process(p), value(VirtualAllocEx(p, nullptr, size, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE)) {}
    ~RemotePath() { if (value && !retain) VirtualFreeEx(process, value, 0, MEM_RELEASE); }
};
[[noreturn]] void error(const char* operation) {
    throw std::runtime_error(std::string(operation) + " (Windows error " + std::to_string(GetLastError()) + ")");
}
std::vector<MODULEENTRY32W> modules(DWORD pid) {
    HANDLE raw = INVALID_HANDLE_VALUE;
    for (int attempt = 0; attempt < 8; ++attempt) {
        raw = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, pid);
        if (raw != INVALID_HANDLE_VALUE || GetLastError() != ERROR_BAD_LENGTH) break;
    }
    Handle snapshot(raw);
    if (raw == INVALID_HANDLE_VALUE) error("Cannot enumerate target modules");
    MODULEENTRY32W entry{};
    entry.dwSize = sizeof(entry);
    if (!Module32FirstW(raw, &entry)) error("Cannot read target modules");
    std::vector<MODULEENTRY32W> result;
    do { result.push_back(entry); } while (Module32NextW(raw, &entry));
    if (GetLastError() != ERROR_NO_MORE_FILES) error("Target module enumeration failed");
    return result;
}
uintptr_t loadedAgent(DWORD pid, HANDLE process, const std::wstring& path) {
    Handle expected(CreateFileW(path.c_str(), FILE_READ_ATTRIBUTES, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING, 0, nullptr));
    BY_HANDLE_FILE_INFORMATION expectedInfo{};
    bool haveIdentity = expected.value != INVALID_HANDLE_VALUE && GetFileInformationByHandle(expected.value, &expectedInfo);
    for (const auto& module : modules(pid)) {
        std::vector<wchar_t> buffer(32768);
        DWORD count = GetModuleFileNameExW(process, module.hModule, buffer.data(), static_cast<DWORD>(buffer.size()));
        if (!count || count >= buffer.size()) continue;
        std::wstring actualPath(buffer.data(), count);
        if (_wcsicmp(actualPath.c_str(), path.c_str()) == 0) return reinterpret_cast<uintptr_t>(module.modBaseAddr);
        if (!haveIdentity) continue;
        Handle actual(CreateFileW(actualPath.c_str(), FILE_READ_ATTRIBUTES, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING, 0, nullptr));
        BY_HANDLE_FILE_INFORMATION info{};
        if (actual.value != INVALID_HANDLE_VALUE && GetFileInformationByHandle(actual.value, &info)
                && info.dwVolumeSerialNumber == expectedInfo.dwVolumeSerialNumber
                && info.nFileIndexHigh == expectedInfo.nFileIndexHigh && info.nFileIndexLow == expectedInfo.nFileIndexLow)
            return reinterpret_cast<uintptr_t>(module.modBaseAddr);
    }
    return 0;
}
LPTHREAD_START_ROUTINE remoteLoader(DWORD pid) {
    FARPROC function = GetProcAddress(GetModuleHandleW(L"kernel32.dll"), "LoadLibraryW");
    if (!function) error("Cannot resolve LoadLibraryW");
    uintptr_t address = reinterpret_cast<uintptr_t>(function);
    for (const auto& local : modules(GetCurrentProcessId())) {
        uintptr_t base = reinterpret_cast<uintptr_t>(local.modBaseAddr);
        if (address < base || address - base >= local.modBaseSize) continue;
        uintptr_t offset = address - base;
        for (const auto& remote : modules(pid)) {
            if (_wcsicmp(local.szModule, remote.szModule) == 0 && remote.modBaseSize == local.modBaseSize && offset < remote.modBaseSize)
                return reinterpret_cast<LPTHREAD_START_ROUTINE>(reinterpret_cast<uintptr_t>(remote.modBaseAddr) + offset);
        }
    }
    throw std::runtime_error("Target has no compatible Windows loader module");
}
void wait(HANDLE thread, const char* operation) {
    DWORD result = WaitForSingleObject(thread, 15000);
    if (result == WAIT_TIMEOUT) throw IndeterminateRemoteOperation(std::string(operation) + " timed out; remote thread is not terminated and may finish later. No automatic fallback or retry is safe");
    if (result != WAIT_OBJECT_0) error(operation);
}
void inject(DWORD pid, const std::wstring& path, const std::string& options) {
    if (!pid || pid == GetCurrentProcessId()) throw std::runtime_error("Invalid target PID");
    Handle process(OpenProcess(PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION | PROCESS_VM_OPERATION | PROCESS_VM_WRITE | PROCESS_VM_READ, FALSE, pid));
    if (!process.value) error("Cannot open target process");
    BOOL selfWow = FALSE, targetWow = FALSE;
    if (!IsWow64Process(GetCurrentProcess(), &selfWow) || !IsWow64Process(process.value, &targetWow)) error("Cannot determine target architecture");
    if (selfWow != targetWow) throw std::runtime_error("Injector and target must have the same architecture");
    bool jvm = false;
    for (const auto& module : modules(pid)) if (_wcsicmp(module.szModule, L"jvm.dll") == 0) jvm = true;
    if (!jvm) throw std::runtime_error("Target has no jvm.dll");

    Image image(path.c_str());
    if (!image.value) error("Cannot inspect agent image");
    FARPROC initialize = GetProcAddress(image.value, options.empty()
            ? "JPI_InitializeInjectedAgentThread" : "JPI_InitializeInjectedCommunicationThread");
    if (!initialize) throw std::runtime_error("Agent has no JPI_InitializeInjectedAgentThread entry");
    uintptr_t offset = reinterpret_cast<uintptr_t>(initialize) - reinterpret_cast<uintptr_t>(image.value);
    uintptr_t base = loadedAgent(pid, process.value, path);
    if (!base) {
        SIZE_T bytes = (path.size() + 1) * sizeof(wchar_t);
        RemotePath argument(process.value, bytes);
        if (!argument.value) error("Cannot allocate remote DLL path");
        SIZE_T written = 0;
        if (!WriteProcessMemory(process.value, argument.value, path.c_str(), bytes, &written) || written != bytes) error("Cannot write remote DLL path");
        Handle loader(CreateRemoteThread(process.value, nullptr, 0, remoteLoader(pid), argument.value, 0, nullptr));
        if (!loader.value) error("Cannot start remote DLL loader");
        argument.retain = true;
        wait(loader.value, "DLL loading");
        argument.retain = false;
        base = loadedAgent(pid, process.value, path);
        if (!base) {
            DWORD result = 0;
            GetExitCodeThread(loader.value, &result);
            throw std::runtime_error("LoadLibraryW did not map the requested agent (thread result " + std::to_string(result) + ")");
        }
    }
    RemotePath arguments(process.value, options.size() + 1);
    if (!arguments.value) error("Cannot allocate remote bootstrap options");
    SIZE_T written = 0;
    if (!WriteProcessMemory(process.value, arguments.value, options.c_str(), options.size() + 1, &written)
            || written != options.size() + 1) error("Cannot write remote bootstrap options");
    Handle worker(CreateRemoteThread(process.value, nullptr, 0, reinterpret_cast<LPTHREAD_START_ROUTINE>(base + offset),
            options.empty() ? nullptr : arguments.value, 0, nullptr));
    if (!worker.value) error("Cannot start JVMTI initialization thread");
    arguments.retain = true;
    wait(worker.value, "JVMTI initialization");
    arguments.retain = false;
    DWORD result = 0;
    if (!GetExitCodeThread(worker.value, &result)) error("Cannot read JVMTI initialization result");
    if (result != 0) throw std::runtime_error("Injected JVMTI initialization failed (code " + std::to_string(result) + ")");
}
}

static void inject_java(JNIEnv* env, jint pid, jbyteArray encoded, jbyteArray encoded_options) try {
    if (!encoded || pid <= 0) throw std::runtime_error("PID and UTF-8 agent path are required");
    jsize size = env->GetArrayLength(encoded);
    if (size <= 0 || size > 131068) throw std::runtime_error("Invalid agent path length");
    std::string utf8(static_cast<size_t>(size), '\0');
    env->GetByteArrayRegion(encoded, 0, size, reinterpret_cast<jbyte*>(&utf8[0]));
    if (env->ExceptionCheck()) return;
    if (utf8.find('\0') != std::string::npos) throw std::runtime_error("Agent path contains NUL");
    int count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, utf8.data(), size, nullptr, 0);
    if (count <= 0 || count >= 32767) throw std::runtime_error("Invalid UTF-8 agent path");
    std::wstring path(static_cast<size_t>(count), L'\0');
    if (!MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, utf8.data(), size, &path[0], count)) error("Cannot decode agent path");
    std::string options;
    if (encoded_options) {
        jsize length = env->GetArrayLength(encoded_options);
        if (length <= 0 || length > 16384) throw std::runtime_error("Invalid native bootstrap options length");
        options.resize(static_cast<size_t>(length));
        env->GetByteArrayRegion(encoded_options, 0, length, reinterpret_cast<jbyte*>(options.data()));
        if (env->ExceptionCheck()) return;
        if (options.find('\0') != std::string::npos) throw std::runtime_error("Native bootstrap options contain NUL");
    }
    inject(static_cast<DWORD>(pid), path, options);
} catch (const IndeterminateRemoteOperation& failure) {
    jclass type = env->FindClass("dev/whitedev/jpi/attach/IndeterminateInjectionException");
    if (type) env->ThrowNew(type, failure.what());
} catch (const std::exception& failure) {
    jclass type = env->FindClass("java/io/IOException");
    if (type) env->ThrowNew(type, failure.what());
}

extern "C" JNIEXPORT void JNICALL Java_dev_whitedev_jpi_attach_ProcessInjector_inject0(JNIEnv* env, jclass, jint pid, jbyteArray path) {
    inject_java(env, pid, path, nullptr);
}
extern "C" JNIEXPORT void JNICALL Java_dev_whitedev_jpi_attach_ProcessInjector_injectCommunication0(JNIEnv* env, jclass, jint pid, jbyteArray path, jbyteArray options) {
    inject_java(env, pid, path, options);
}
