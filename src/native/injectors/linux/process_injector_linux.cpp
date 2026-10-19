#include <jni.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/user.h>
#include <sys/uio.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <elf.h>
#include <signal.h>
#include <cerrno>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#include <array>
#include <algorithm>
#include <limits.h>
#include <dirent.h>
#include <cstdlib>

extern "C" const unsigned char jpi_worker_start[], jpi_worker_end[];

namespace {
using Address = std::uint64_t;
using Clock = std::chrono::steady_clock;
class IndeterminateRemoteOperation : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};
constexpr std::size_t allocation_size = 128 * 1024;
constexpr std::size_t page_size = 4096;

[[noreturn]] void error(const std::string& operation) {
    throw std::runtime_error(operation + ": " + std::strerror(errno));
}
long trace(enum __ptrace_request request, pid_t pid, void* address = nullptr, void* data = nullptr) {
    errno = 0;
    long result = ptrace(request, pid, address, data);
    if (result == -1 && errno) error("ptrace " + std::to_string(request));
    return result;
}

struct Mapping { Address start, end, offset; std::string permissions, path; };
std::vector<Mapping> mappings(pid_t pid) {
    std::ifstream input("/proc/" + std::to_string(pid) + "/maps");
    if (!input) throw std::runtime_error("Cannot read target maps (permissions or process exited)");
    std::vector<Mapping> result;
    std::string line;
    while (std::getline(input, line)) {
        std::istringstream fields(line);
        Mapping m{};
        char dash;
        std::string device;
        unsigned long inode;
        if (!(fields >> std::hex >> m.start >> dash >> m.end >> m.permissions >> m.offset >> device >> inode)) continue;
        std::getline(fields >> std::ws, m.path);
        result.push_back(m);
    }
    return result;
}

Address symbol(pid_t pid, const std::vector<Mapping>& maps, const char* name) {
    for (const auto& map : maps) {
        if (map.offset != 0 || map.path.empty() || map.path[0] != '/') continue;
        if (map.path.find("libc.so") == std::string::npos && map.path.find("libdl.so") == std::string::npos
                && map.path.find("libpthread.so") == std::string::npos) continue;
        std::ifstream file("/proc/" + std::to_string(pid) + "/root" + map.path, std::ios::binary);
        if (!file) continue;
        Elf64_Ehdr header{};
        file.read(reinterpret_cast<char*>(&header), sizeof(header));
        if (!file || std::memcmp(header.e_ident, ELFMAG, SELFMAG) || header.e_ident[EI_CLASS] != ELFCLASS64
                || header.e_machine != EM_X86_64 || header.e_type != ET_DYN
                || header.e_shentsize != sizeof(Elf64_Shdr) || header.e_shnum > 4096) continue;
        std::vector<Elf64_Shdr> sections(header.e_shnum);
        file.seekg(static_cast<std::streamoff>(header.e_shoff));
        file.read(reinterpret_cast<char*>(sections.data()), static_cast<std::streamsize>(sections.size() * sizeof(Elf64_Shdr)));
        if (!file) continue;
        for (const auto& section : sections) {
            if (section.sh_type != SHT_DYNSYM || section.sh_entsize != sizeof(Elf64_Sym)
                    || section.sh_size > 16 * 1024 * 1024 || section.sh_link >= sections.size()) continue;
            const auto& strings_section = sections[section.sh_link];
            if (strings_section.sh_size > 16 * 1024 * 1024) continue;
            std::vector<char> strings(strings_section.sh_size);
            file.clear();
            file.seekg(static_cast<std::streamoff>(strings_section.sh_offset));
            file.read(strings.data(), static_cast<std::streamsize>(strings.size()));
            std::vector<Elf64_Sym> symbols(section.sh_size / sizeof(Elf64_Sym));
            file.seekg(static_cast<std::streamoff>(section.sh_offset));
            file.read(reinterpret_cast<char*>(symbols.data()), static_cast<std::streamsize>(symbols.size() * sizeof(Elf64_Sym)));
            if (!file) continue;
            for (const auto& s : symbols) {
                if (s.st_shndx == SHN_UNDEF || ELF64_ST_TYPE(s.st_info) != STT_FUNC || s.st_name >= strings.size()) continue;
                const char* text = strings.data() + s.st_name;
                if (!std::memchr(text, 0, strings.size() - s.st_name) || std::strcmp(text, name)) continue;
                Address value = map.start + s.st_value;
                for (const auto& executable : maps) {
                    if (executable.path == map.path && executable.permissions.find('x') != std::string::npos
                            && value >= executable.start && value < executable.end) return value;
                }
            }
        }
    }
    throw std::runtime_error(std::string("Target glibc does not export ") + name);
}

class Tracee {
    pid_t pid;
    bool attached = false, stopped = false, saved = false;
    user_regs_struct original{};
    std::vector<unsigned char> xstate = std::vector<unsigned char>(65536);
    std::size_t xstate_size = 0;
    int pending_signal = 0;
    bool indeterminate = false;
    bool breakpoint_stop = false;
    std::chrono::milliseconds operation_timeout;
public:
    explicit Tracee(pid_t target, std::chrono::milliseconds timeout = std::chrono::seconds(10))
        : pid(target), operation_timeout(timeout) {}
    bool hasIndeterminateOperation() const { return indeterminate; }
    void attach() {
        trace(PTRACE_SEIZE, pid);
        attached = true;
        trace(PTRACE_INTERRUPT, pid);
        waitStop(Clock::now() + std::chrono::seconds(5));
        trace(PTRACE_GETREGS, pid, nullptr, &original);
        iovec data{xstate.data(), xstate.size()};
        trace(PTRACE_GETREGSET, pid, reinterpret_cast<void*>(NT_X86_XSTATE), &data);
        xstate_size = data.iov_len;
        saved = true;
    }
    void waitStop(Clock::time_point deadline) {
        for (;;) {
            int status = 0;
            pid_t result = waitpid(pid, &status, __WALL | WNOHANG);
            if (result < 0 && errno == EINTR) continue;
            if (result < 0) error("waitpid");
            if (result == pid) {
                if (!WIFSTOPPED(status)) { attached = false; throw std::runtime_error("Target exited during injection"); }
                stopped = true;
                int sig = WSTOPSIG(status);
                breakpoint_stop = false;
                bool user_trap = false;
                if (sig == SIGTRAP && (static_cast<unsigned>(status) >> 16) == 0) {
                    siginfo_t info{};
                    trace(PTRACE_GETSIGINFO, pid, nullptr, &info);
                    user_trap = info.si_code <= 0;
                    breakpoint_stop = info.si_code == TRAP_BRKPT || info.si_code == SI_KERNEL;
                }
                if (sig != SIGTRAP || user_trap) {
                    pending_signal = sig;
                    throw std::runtime_error("Target received signal " + std::to_string(sig) + " during injection");
                }
                return;
            }
            if (Clock::now() >= deadline) throw std::runtime_error("Timed out waiting for target thread");
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
    }
    void read(Address address, void* output, std::size_t length) {
        auto* bytes = static_cast<unsigned char*>(output);
        for (std::size_t i = 0; i < length; i += sizeof(long)) {
            long word = trace(PTRACE_PEEKDATA, pid, reinterpret_cast<void*>(address + i));
            std::memcpy(bytes + i, &word, std::min(sizeof(word), length - i));
        }
    }
    void write(Address address, const void* input, std::size_t length) {
        const auto* bytes = static_cast<const unsigned char*>(input);
        for (std::size_t i = 0; i < length; i += sizeof(long)) {
            long word;
            std::size_t count = std::min(sizeof(word), length - i);
            if (count != sizeof(word)) read(address + i, &word, sizeof(word));
            std::memcpy(&word, bytes + i, count);
            trace(PTRACE_POKEDATA, pid, reinterpret_cast<void*>(address + i), reinterpret_cast<void*>(word));
        }
    }
    void restore() {
        if (!saved) return;
        iovec data{xstate.data(), xstate_size};
        trace(PTRACE_SETREGSET, pid, reinterpret_cast<void*>(NT_X86_XSTATE), &data);
        trace(PTRACE_SETREGS, pid, nullptr, &original);
    }
    Address execute(user_regs_struct regs, enum __ptrace_request request, Address expected_ip) {
        if (indeterminate) throw IndeterminateRemoteOperation("No further remote operations allowed after an indeterminate result");
        trace(PTRACE_SETREGS, pid, nullptr, &regs);
        try { trace(request, pid); }
        catch (...) { restore(); throw; }
        stopped = false;
        try {
            waitStop(Clock::now() + operation_timeout);
            trace(PTRACE_GETREGS, pid, nullptr, &regs);
            if (regs.rip != expected_ip) {
                if (breakpoint_stop) pending_signal = SIGTRAP;
                throw std::runtime_error("Unexpected target instruction pointer after remote operation");
            }
            restore();
            return regs.rax;
        }
        catch (const std::exception& failure) {
            indeterminate = true;
            if (attached && !stopped) {
                try { trace(PTRACE_INTERRUPT, pid); waitStop(Clock::now() + std::chrono::seconds(5)); } catch (...) {}
            }
            user_regs_struct failed{};
            if (attached && stopped) {
                try { trace(PTRACE_GETREGS, pid, nullptr, &failed); restore(); } catch (...) { }
            }
            std::ostringstream details;
            details << failure.what() << " (remote " << (request == PTRACE_SINGLESTEP ? "syscall" : "call")
                    << ", RIP=0x" << std::hex << failed.rip << ", expected=0x" << expected_ip
                    << ", RAX=0x" << failed.rax << ")";
            throw IndeterminateRemoteOperation(details.str());
        }
    }
    Address syscall(Address instruction, Address number, std::array<Address, 6> args) {
        auto regs = original;
        regs.rip = instruction;
        regs.rax = number;
        regs.orig_rax = static_cast<Address>(-1);
        regs.rdi = args[0]; regs.rsi = args[1]; regs.rdx = args[2];
        regs.r10 = args[3]; regs.r8 = args[4]; regs.r9 = args[5];
        Address result = execute(regs, PTRACE_SINGLESTEP, instruction + 2);
        if (result >= static_cast<Address>(-4095)) {
            errno = static_cast<int>(-result); error("Remote syscall " + std::to_string(number));
        }
        return result;
    }
    Address call(Address trampoline, Address function, Address stack, std::array<Address, 6> args, const char* name) {
        auto regs = original;
        regs.rip = trampoline;
        regs.rsp = stack & ~Address(15);
        regs.rax = function;
        regs.orig_rax = static_cast<Address>(-1);
        regs.rdi = args[0]; regs.rsi = args[1]; regs.rdx = args[2];
        regs.rcx = args[3]; regs.r8 = args[4]; regs.r9 = args[5];
        try { return execute(regs, PTRACE_CONT, trampoline + 3); }
        catch (const IndeterminateRemoteOperation& failure) { throw IndeterminateRemoteOperation(std::string(name) + ": " + failure.what()); }
        catch (const std::exception& failure) { throw std::runtime_error(std::string(name) + ": " + failure.what()); }
    }
    void resume() {
        restore();
        trace(PTRACE_CONT, pid);
        stopped = false;
        saved = false;
    }
    void interrupt() {
        trace(PTRACE_INTERRUPT, pid);
        waitStop(Clock::now() + std::chrono::seconds(5));
        trace(PTRACE_GETREGS, pid, nullptr, &original);
        iovec data{xstate.data(), xstate.size()};
        trace(PTRACE_GETREGSET, pid, reinterpret_cast<void*>(NT_X86_XSTATE), &data);
        xstate_size = data.iov_len;
        saved = true;
    }
    void detach() {
        if (!attached) return;
        if (!stopped) interrupt();
        restore();
        trace(PTRACE_DETACH, pid, nullptr, reinterpret_cast<void*>(static_cast<intptr_t>(pending_signal)));
        attached = false;
    }
    ~Tracee() {
        try { detach(); } catch (...) {}
    }
};

struct Context {
    Address dlopen, dlsym, dlclose, options, path, entry, thread;
    std::int32_t result, done;
    Address handle;
    Address entry_v2 = 0;
    std::int32_t retain_reference = 1, padding = 0;
};
static_assert(offsetof(Context, path) == 32 && offsetof(Context, result) == 56
              && offsetof(Context, done) == 60 && offsetof(Context, handle) == 64
              && offsetof(Context, entry_v2) == 72 && offsetof(Context, retain_reference) == 80);

void inject(pid_t pid, const std::string& path, const std::string& options = {}) {
    if (pid <= 1 || pid == getpid()) throw std::runtime_error("Refusing invalid PID or self injection");
    struct stat target{}, library{}, local_library{};
    std::string proc = "/proc/" + std::to_string(pid);
    if (stat(proc.c_str(), &target)) error("Target process");
    if (target.st_uid != getuid() || getuid() != geteuid()) throw std::runtime_error("Injection requires a same-user target and no setuid credentials");
    if (path.empty() || path.front() != '/' || path.size() >= PATH_MAX) throw std::runtime_error("Invalid absolute agent path");
    if (stat(path.c_str(), &local_library) || stat((proc + "/root" + path).c_str(), &library)) error("Target-visible agent path");
    if (!S_ISREG(library.st_mode) || library.st_dev != local_library.st_dev || library.st_ino != local_library.st_ino
            || (library.st_mode & (S_IWGRP | S_IWOTH))) throw std::runtime_error("Agent must be the same regular file in both mount namespaces and not group/world writable");
    auto maps = mappings(pid);
    bool jvm = false;
    for (const auto& m : maps) if (m.path.size() >= 10 && m.path.substr(m.path.size() - 10) == "/libjvm.so") jvm = true;
    if (!jvm) throw std::runtime_error("Target has no mapped libjvm.so");
    Address create = symbol(pid, maps, "pthread_create");
    Address join = symbol(pid, maps, "pthread_tryjoin_np");
    Address detach = symbol(pid, maps, "pthread_detach");
    Context context{symbol(pid, maps, "dlopen"), symbol(pid, maps, "dlsym"), symbol(pid, maps, "dlclose"), 0, 0, 0, 0, -1, 0, 0};
    pid_t tid = 0;
    DIR* tasks = opendir((proc + "/task").c_str());
    if (!tasks) error("Open target thread list");
    while (dirent* entry = readdir(tasks)) {
        char* end = nullptr;
        long value = std::strtol(entry->d_name, &end, 10);
        if (*entry->d_name && end && !*end && value > 0 && value <= INT_MAX && value != pid
                && (!tid || value < tid)) tid = static_cast<pid_t>(value);
    }
    closedir(tasks);
    if (!tid) throw std::runtime_error("No non-leader JVM thread is available for safe attachment");
    Tracee tracee(tid);
    tracee.attach();
    Address syscall_ip = 0;
    for (const auto& map : maps) if (map.path == "[vdso]" && map.end - map.start <= 65536) {
        std::vector<unsigned char> vdso(map.end - map.start);
        tracee.read(map.start, vdso.data(), vdso.size());
        for (std::size_t i = 0; i + 1 < vdso.size(); ++i) if (vdso[i] == 0x0f && vdso[i + 1] == 0x05) { syscall_ip = map.start + i; break; }
    }
    if (!syscall_ip) throw std::runtime_error("No syscall instruction in target vDSO");
    Address memory = tracee.syscall(syscall_ip, SYS_mmap, {0, allocation_size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, static_cast<Address>(-1), 0});
    bool worker_started = false, worker_may_run = false, worker_joined = false;
    try {
        Address context_address = memory + page_size;
        context.path = context_address + sizeof(Context);
        context.entry = context.path + path.size() + 1;
        tracee.write(context_address, &context, sizeof(context));
        tracee.write(context.path, path.c_str(), path.size() + 1);
        const char entry[] = "JPI_InitializeInjectedAgent";
        context.entry_v2 = context.entry + sizeof(entry);
        tracee.write(context_address, &context, sizeof(context));
        tracee.write(context.entry, entry, sizeof(entry));
        const std::string entry_v2 = options.empty() ? "JPI_InitializeInjectedAgentV2" : "JPI_InitializeInjectedAgentV3";
        tracee.write(context.entry_v2, entry_v2.c_str(), entry_v2.size() + 1);
        if (!options.empty()) {
            context.options = context.entry_v2 + entry_v2.size() + 1;
            if (context.options + options.size() + 1 >= memory + allocation_size / 2)
                throw std::runtime_error("Native bootstrap options exceed scratch data region");
            tracee.write(context.options, options.c_str(), options.size() + 1);
            tracee.write(context_address, &context, sizeof(context));
        }
        const unsigned char trampoline[] = {0xff, 0xd0, 0xcc};
        tracee.write(memory, trampoline, sizeof(trampoline));
        std::size_t worker_size = static_cast<std::size_t>(jpi_worker_end - jpi_worker_start);
        if (worker_size > page_size - 16) throw std::runtime_error("Worker exceeds code page");
        tracee.write(memory + 16, jpi_worker_start, worker_size);
        tracee.syscall(syscall_ip, SYS_mprotect, {memory, page_size, PROT_READ | PROT_EXEC, 0, 0, 0});
        Address stack = memory + allocation_size - 16;
        worker_may_run = true;
        Address result = tracee.call(memory, create, stack, {context_address + offsetof(Context, thread), 0, memory + 16, context_address, 0, 0}, "pthread_create");
        if (result) {
            worker_may_run = false;
            throw std::runtime_error("Target pthread_create returned " + std::to_string(result));
        }
        worker_started = true;
        tracee.read(context_address, &context, sizeof(context));
        auto deadline = Clock::now() + std::chrono::seconds(15);
        for (;;) {
            tracee.resume();
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            tracee.interrupt();
            result = tracee.call(memory, join, stack, {context.thread, 0, 0, 0, 0, 0}, "pthread_tryjoin_np");
            if (result == 0) { worker_joined = true; break; }
            if (result != EBUSY) throw std::runtime_error("Target pthread_tryjoin_np returned " + std::to_string(result));
            if (Clock::now() >= deadline) throw IndeterminateRemoteOperation("Agent worker timed out; it may still complete. Worker memory retained for safety");
        }
        tracee.read(context_address, &context, sizeof(context));
        if (!context.done || context.result != 0) throw std::runtime_error("Agent worker initialization failed (" + std::to_string(context.result) + "): -2=dlopen, -3=entry missing, otherwise JNI/JVMTI error");
        tracee.syscall(syscall_ip, SYS_munmap, {memory, allocation_size, 0, 0, 0, 0});
        memory = 0;
        tracee.detach();
    } catch (...) {
        if (!tracee.hasIndeterminateOperation() && memory && (!worker_may_run || worker_joined)) {
            try { tracee.syscall(syscall_ip, SYS_munmap, {memory, allocation_size, 0, 0, 0, 0}); } catch (...) {}
        } else if (!tracee.hasIndeterminateOperation() && worker_started) {
            try { tracee.call(memory, detach, memory + allocation_size - 16, {context.thread, 0, 0, 0, 0, 0}, "pthread_detach"); } catch (...) {}
        }
        throw;
    }
}
}

static void inject_java(JNIEnv* env, jint pid, jbyteArray path, jbyteArray encoded_options) {
    try {
        if (!path) throw std::runtime_error("Agent path is required");
        jsize size = env->GetArrayLength(path);
        if (size <= 0 || size >= PATH_MAX) throw std::runtime_error("Invalid agent path length");
        std::string library(static_cast<std::size_t>(size), '\0');
        env->GetByteArrayRegion(path, 0, size, reinterpret_cast<jbyte*>(library.data()));
        if (env->ExceptionCheck()) return;
        if (library.find('\0') != std::string::npos) throw std::runtime_error("Agent path contains a NUL byte");
        std::string options;
        if (encoded_options) {
            jsize length = env->GetArrayLength(encoded_options);
            if (length <= 0 || length > 16384) throw std::runtime_error("Invalid native bootstrap options length");
            options.resize(static_cast<std::size_t>(length));
            env->GetByteArrayRegion(encoded_options, 0, length, reinterpret_cast<jbyte*>(options.data()));
            if (env->ExceptionCheck()) return;
            if (options.find('\0') != std::string::npos) throw std::runtime_error("Native bootstrap options contain NUL");
        }
        inject(pid, library, options);
    }
    catch (const IndeterminateRemoteOperation& failure) {
        jclass type = env->FindClass("dev/whitedev/jpi/attach/IndeterminateInjectionException");
        if (type) env->ThrowNew(type, (std::string("Linux injection outcome is indeterminate: ") + failure.what()
            + ". No automatic fallback or retry is safe. Scratch memory may be retained until target exit; native calls may have side effects.").c_str());
    }
    catch (const std::exception& failure) {
        jclass type = env->FindClass("java/io/IOException");
        if (type) env->ThrowNew(type, (std::string("Linux injection failed: ") + failure.what()
            + ". Check same UID, dumpability, Yama ptrace_scope, seccomp, and container permissions; JPI does not relax them.").c_str());
    }
}

extern "C" JNIEXPORT void JNICALL Java_dev_whitedev_jpi_attach_ProcessInjector_inject0(JNIEnv* env, jclass, jint pid, jbyteArray path) {
    inject_java(env, pid, path, nullptr);
}
extern "C" JNIEXPORT void JNICALL Java_dev_whitedev_jpi_attach_ProcessInjector_injectCommunication0(JNIEnv* env, jclass, jint pid, jbyteArray path, jbyteArray options) {
    inject_java(env, pid, path, options);
}
