#include "../injectors/linux/process_injector_linux.cpp"
#include <poll.h>
#include <iostream>
#include <sys/prctl.h>

namespace {
int notification = -1;
void notify(int signal) {
    const char value = static_cast<char>(signal);
    if (notification >= 0) { ssize_t ignored = write(notification, &value, 1); (void)ignored; }
}
struct Fixture { Address code, stack; };
struct Child {
    pid_t pid;
    int events;
    Fixture fixture{};
    explicit Child(bool dumpable = true) {
        int ready[2], signals[2];
        if (pipe(ready) || pipe(signals)) throw std::runtime_error("pipe failed");
        pid = fork();
        if (pid < 0) throw std::runtime_error("fork failed");
        if (!pid) {
            close(ready[0]); close(signals[0]); notification = signals[1];
            struct sigaction action{};
            action.sa_handler = notify; sigemptyset(&action.sa_mask);
            sigaction(SIGUSR1, &action, nullptr); sigaction(SIGTRAP, &action, nullptr);
            if (!dumpable) prctl(PR_SET_DUMPABLE, 0);
            auto* code = static_cast<unsigned char*>(mmap(nullptr, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
            void* stack = mmap(nullptr, 65536, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            if (code == MAP_FAILED || stack == MAP_FAILED) _exit(2);
            const unsigned char syscall_code[] = {0x0f, 0x05, 0xcc};
            const unsigned char spin[] = {0xeb, 0xfe};
            const unsigned char trampoline[] = {0xff, 0xd0, 0xcc};
            std::memcpy(code, syscall_code, sizeof(syscall_code));
            std::memcpy(code + 16, spin, sizeof(spin));
            std::memcpy(code + 32, trampoline, sizeof(trampoline));
            code[48] = 0xcc; code[49] = 0xc3;
            if (mprotect(code, 4096, PROT_READ | PROT_EXEC)) _exit(3);
            Fixture data{reinterpret_cast<Address>(code), reinterpret_cast<Address>(stack) + 65536 - 16};
            if (write(ready[1], &data, sizeof(data)) != sizeof(data)) _exit(4);
            close(ready[1]);
            for (;;) pause();
        }
        close(ready[1]); close(signals[1]); events = signals[0];
        if (read(ready[0], &fixture, sizeof(fixture)) != sizeof(fixture)) throw std::runtime_error("Child startup failed");
        close(ready[0]);
    }
    void received(int signal) {
        pollfd descriptor{events, POLLIN, 0};
        if (poll(&descriptor, 1, 2000) != 1) throw std::runtime_error("Signal was not replayed to child");
        char value = 0;
        if (read(events, &value, 1) != 1 || value != signal) throw std::runtime_error("Unexpected child signal");
    }
    ~Child() { kill(pid, SIGKILL); while (waitpid(pid, nullptr, 0) < 0 && errno == EINTR) {} close(events); }
};

std::vector<unsigned char> xstate(pid_t pid) {
    std::vector<unsigned char> bytes(65536);
    iovec data{bytes.data(), bytes.size()};
    trace(PTRACE_GETREGSET, pid, reinterpret_cast<void*>(NT_X86_XSTATE), &data);
    bytes.resize(data.iov_len);
    return bytes;
}
void preservedState() {
    Child child;
    Tracee target(child.pid);
    target.attach();
    user_regs_struct before{}, after{};
    trace(PTRACE_GETREGS, child.pid, nullptr, &before);
    auto extended = xstate(child.pid);
    Address result = target.syscall(child.fixture.code, SYS_getpid, {});
    trace(PTRACE_GETREGS, child.pid, nullptr, &after);
    if (result != static_cast<Address>(child.pid) || std::memcmp(&before, &after, sizeof(before)) || extended != xstate(child.pid))
        throw std::runtime_error("Registers or xstate changed after successful remote syscall");
    target.detach();
    kill(child.pid, SIGUSR1); child.received(SIGUSR1);
}
void signalReplay(int signal) {
    Child child;
    Tracee target(child.pid);
    target.attach();
    kill(child.pid, signal);
    bool refused = false;
    try { target.syscall(child.fixture.code, SYS_getpid, {}); }
    catch (const IndeterminateRemoteOperation&) { refused = true; }
    if (!refused || !target.hasIndeterminateOperation()) throw std::runtime_error("Interrupted operation was not classified as uncertain");
    target.detach();
    child.received(signal);
    kill(child.pid, SIGUSR1); child.received(SIGUSR1);
}
void uncertainCall() {
    Child child;
    Tracee target(child.pid, std::chrono::milliseconds(50));
    target.attach();
    bool refused = false;
    try { target.call(child.fixture.code + 32, child.fixture.code + 16, child.fixture.stack, {}, "test spin"); }
    catch (const IndeterminateRemoteOperation&) { refused = true; }
    if (!refused || !target.hasIndeterminateOperation()) throw std::runtime_error("Native call timeout was not classified as uncertain");
    refused = false;
    try { target.syscall(child.fixture.code, SYS_getpid, {}); }
    catch (const IndeterminateRemoteOperation&) { refused = true; }
    if (!refused) throw std::runtime_error("Remote operations continued after an uncertain call");
    target.detach();
    kill(child.pid, SIGUSR1); child.received(SIGUSR1);
}
void deniedTrace() {
    Child child(false);
    bool denied = false;
    try { Tracee target(child.pid); target.attach(); }
    catch (const std::runtime_error&) { denied = true; }
    if (!denied) throw std::runtime_error("Non-dumpable child unexpectedly allowed ptrace (run without SYS_PTRACE)");
    kill(child.pid, SIGUSR1); child.received(SIGUSR1);
}
void unexpectedBreakpoint() {
    Child child;
    Tracee target(child.pid);
    target.attach();
    bool refused = false;
    try { target.call(child.fixture.code + 32, child.fixture.code + 48, child.fixture.stack, {}, "test breakpoint"); }
    catch (const IndeterminateRemoteOperation&) { refused = true; }
    if (!refused) throw std::runtime_error("Unexpected breakpoint was swallowed");
    target.detach(); child.received(SIGTRAP);
    kill(child.pid, SIGUSR1); child.received(SIGUSR1);
}
}
int main(int argc, char** argv) {
    try {
        std::string test = argc > 1 ? argv[1] : "";
        if (test == "state") preservedState();
        else if (test == "usr1") signalReplay(SIGUSR1);
        else if (test == "trap") signalReplay(SIGTRAP);
        else if (test == "timeout") uncertainCall();
        else if (test == "denied") deniedTrace();
        else if (test == "breakpoint") unexpectedBreakpoint();
        else throw std::runtime_error("Unknown recovery test");
        std::cout << test << ": passed\n";
        return 0;
    } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
