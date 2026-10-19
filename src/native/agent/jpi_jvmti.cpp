#include <jni.h>
#include <jvmti.h>
#include <algorithm>
#include <cstdint>
#include <exception>
#include <mutex>
#include <new>
#include <sstream>
#include <string>
#include <vector>
#include <atomic>
#include <deque>
#include <cstdio>
#include <cstring>
#include <map>
#include "capture_archive.h"
#ifdef __linux__
#include <dlfcn.h>
#elif defined(_WIN32)
#define NOMINMAX
#include <windows.h>
#endif


namespace {
    jvmtiEnv* tooling = nullptr;
    JavaVM* machine = nullptr;
    std::recursive_mutex operations;
    const char* startup_mode = "unknown";
    constexpr jint max_class_bytes = 4 * 1024 * 1024;
    constexpr jint max_patch_bytes = 24 * 1024 * 1024;
    constexpr jint max_classes = 250000;
    constexpr jlong max_heap_instances = 10000000;

    struct Capture {
        jclass target;
        std::vector<jbyte> bytes;
        bool oversized = false;
        bool allocation_failed = false;
    };

    thread_local Capture* pending = nullptr;
    constexpr std::size_t stream_event_limit = 256;
    constexpr std::size_t stream_byte_limit = 16 * 1024 * 1024;
    struct ClassEvent {
        jlong sequence;
        std::string name;
        bool redefine;
        std::vector<jbyte> bytes;
    };
    std::atomic<bool> streaming{false};
    std::mutex stream_mutex;
    std::deque<ClassEvent> class_events;
    std::size_t stream_bytes = 0;
    jlong next_sequence = 1;
    std::atomic<jlong> dropped_events{0};
    std::string communication_options;
    std::recursive_mutex communication_lifecycle;
    std::atomic<int> communication_state{0};

    void JNICALL initialize_communication(jvmtiEnv*, JNIEnv* env, jthread) noexcept {
        std::lock_guard<std::recursive_mutex> lock(communication_lifecycle);
        jclass agent = env->FindClass("dev/whitedev/jpi/agent/InspectorAgent");
        jmethodID entry = agent ? env->GetStaticMethodID(agent, "nativeAgentMain", "(Ljava/lang/String;)V") : nullptr;
        jstring options = entry ? env->NewStringUTF(communication_options.c_str()) : nullptr;
        if (options) env->CallStaticVoidMethod(agent, entry, options);
        bool failed = !options || env->ExceptionCheck();
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (options) env->DeleteLocalRef(options);
        if (agent) env->DeleteLocalRef(agent);
        communication_state.store(failed ? 3 : 2);
        std::fill(communication_options.begin(), communication_options.end(), '\0');
        communication_options.clear();
        if (failed) std::fputs("[JPI native] Communication bootstrap failed: no native endpoint was established. Check the provisioned JAR, library digest, options and port.\n", stderr);
    }

    bool provision_communication(const char* options) {
        if (!options || !*options) return true;
        if (std::strncmp(options, "jpi.bootstrap=", 14) != 0 && !std::strstr(options, ";jpi.bootstrap=")) return true;
        std::lock_guard<std::recursive_mutex> lock(communication_lifecycle);
        if (communication_state.load() == 1) return false;
        if (communication_state.load() == 2) {
            JNIEnv* current = nullptr;
            if (machine->GetEnv(reinterpret_cast<void**>(&current), JNI_VERSION_1_6) != JNI_OK) return false;
            jclass agent = current->FindClass("dev/whitedev/jpi/agent/InspectorAgent");
            jmethodID available = agent ? current->GetStaticMethodID(agent, "nativeCommunicationAvailable", "()Z") : nullptr;
            bool can_restart = available && current->CallStaticBooleanMethod(agent, available) == JNI_TRUE;
            if (current->ExceptionCheck()) { current->ExceptionClear(); can_restart = false; }
            if (agent) current->DeleteLocalRef(agent);
            if (!can_restart) return false;
        }
        if (std::strlen(options) > 16384) return false;
        std::map<std::string, std::string> values;
        std::istringstream entries(options);
        std::string entry;
        while (std::getline(entries, entry, ';')) {
            auto separator = entry.find('=');
            if (separator == std::string::npos || separator == 0) return false;
            if (!values.emplace(entry.substr(0, separator), entry.substr(separator + 1)).second) return false;
        }
        auto enabled = values.find("jpi.bootstrap");
        if (enabled == values.end()) return true;
        if (enabled->second != "true") return false;
        const auto jar = values.find("jpi.jar");
        if (jar == values.end() || jar->second.empty()
                || jar->second.find_first_of("\r\n") != std::string::npos) return false;
#ifdef _WIN32
        const auto& path = jar->second;
        if (!(path.size() >= 3 && path[1] == ':' && (path[2] == '\\' || path[2] == '/'))
                && path.rfind("\\\\", 0) != 0) return false;
#else
        if (jar->second[0] != '/') return false;
#endif
        jvmtiPhase phase{};
        if (tooling->GetPhase(&phase) != JVMTI_ERROR_NONE
                || (phase != JVMTI_PHASE_ONLOAD && phase != JVMTI_PHASE_LIVE)) return false;
        JNIEnv* env = nullptr;
        if (phase == JVMTI_PHASE_LIVE && machine->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return false;
        if (tooling->AddToSystemClassLoaderSearch(jar->second.c_str()) != JVMTI_ERROR_NONE) return false;
        communication_options = options;
        communication_state.store(1);
        if (phase == JVMTI_PHASE_ONLOAD) {
            if (tooling->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_INIT, nullptr) == JVMTI_ERROR_NONE) return true;
            communication_state.store(3);
            std::fill(communication_options.begin(), communication_options.end(), '\0');
            communication_options.clear();
            return false;
        }
        initialize_communication(tooling, env, nullptr);
        return communication_state.load() == 2;
    }

    std::string base64(const std::string& input) {
        static constexpr char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        std::string output;
        unsigned value = 0; int bits = -6;
        for (unsigned char c : input) {
            value = (value << 8) | c; bits += 8;
            while (bits >= 0) { output += alphabet[(value >> bits) & 63]; bits -= 6; }
        }
        if (bits > -6) output += alphabet[((value << 8) >> (bits + 8)) & 63];
        while (output.size() % 4) output += '=';
        return output;
    }

    void record_class(const char* name, bool redefine, jint length, const unsigned char* bytes) noexcept {
        if (!streaming.load()) return;
        if (length <= 0 || length > max_class_bytes) { dropped_events.fetch_add(1); jpi_archive::dropped(); return; }
        bool offered = false;
        try {
            std::lock_guard<std::mutex> lock(stream_mutex);
            if (!streaming.load()) return;
            ClassEvent event{next_sequence++, name ? std::string(name) : std::string("(unnamed)"), redefine,
                             std::vector<jbyte>(reinterpret_cast<const jbyte*>(bytes), reinterpret_cast<const jbyte*>(bytes) + length)};
            jpi_archive::enqueue(event.sequence, event.name, event.redefine, event.bytes);
            offered = true;
            while (!class_events.empty() && (class_events.size() >= stream_event_limit || stream_bytes + event.bytes.size() > stream_byte_limit)) {
                stream_bytes -= class_events.front().bytes.size(); class_events.pop_front(); dropped_events.fetch_add(1);
            }
            class_events.push_back(std::move(event));
            stream_bytes += static_cast<std::size_t>(length);
        } catch (...) { dropped_events.fetch_add(1); if (!offered) jpi_archive::dropped(); }
    }

    struct HeapStats {
        jlong limit;
        jlong count = 0;
        jlong bytes = 0;
        jlong largest = 0;
        bool truncated = false;
    };

    jvmtiIterationControl JNICALL heap_instance(jlong, jlong size, jlong*, void* user_data) {
        auto* stats = static_cast<HeapStats*>(user_data);
        if (stats->count >= stats->limit) {
            stats->truncated = true;
            return JVMTI_ITERATION_ABORT;
        }
        stats->count++;
        stats->bytes += size;
        stats->largest = std::max(stats->largest, size);
        return JVMTI_ITERATION_CONTINUE;
    }

    jint initialize(JavaVM* vm, const char* mode);

    struct CaptureGuard {
        jvmtiEnv* environment;
        bool active = true;

        ~CaptureGuard() {
            pending = nullptr;
            if (active && !streaming.load()) environment->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, nullptr);
        }

        jvmtiError finish() {
            pending = nullptr;
            active = false;
            return streaming.load() ? JVMTI_ERROR_NONE : environment->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, nullptr);
        }
    };

    void JNICALL class_bytes(jvmtiEnv*, JNIEnv* env, jclass type, jobject, const char* name, jobject,
                             jint length, const unsigned char* data, jint*, unsigned char**) {
        record_class(name, type != nullptr, length, data);
        if (pending == nullptr || type == nullptr || !env->IsSameObject(pending->target, type)) return;
        if (length < 0 || length > max_class_bytes) {
            pending->oversized = true;
            return;
        }
        try {
            pending->bytes.assign(reinterpret_cast<const jbyte*>(data), reinterpret_cast<const jbyte*>(data) + length);
        } catch (const std::bad_alloc&) {
            pending->allocation_failed = true;
        }
    }

    void fail(JNIEnv* env, const char* message) {
        jclass type = env->FindClass("java/io/IOException");
        if (type != nullptr) env->ThrowNew(type, message);
    }

    bool check(JNIEnv* env, jvmtiError error, const char* operation) {
        if (error == JVMTI_ERROR_NONE) return true;
        char* name = nullptr;
        tooling->GetErrorName(error, &name);
        std::string message = std::string(operation) + ": " + (name == nullptr ? std::to_string(error) : name);
        if (name != nullptr) tooling->Deallocate(reinterpret_cast<unsigned char*>(name));
        fail(env, message.c_str());
        return false;
    }

    bool ready(JNIEnv* env) {
        if (tooling != nullptr) return true;
        fail(env, "JVMTI agent is not initialized. Use -agentpath or VirtualMachine.loadAgentPath first.");
        return false;
    }

    jint initialize(JavaVM* vm, const char* mode) {
        std::lock_guard<std::recursive_mutex> lock(operations);
        if (tooling != nullptr) return machine == vm ? JNI_OK : JNI_ERR;
        jvmtiEnv* candidate = nullptr;
        if (vm->GetEnv(reinterpret_cast<void**>(&candidate), JVMTI_VERSION_1_2) != JNI_OK) return JNI_ERR;
        jvmtiCapabilities potential{};
        jvmtiCapabilities requested{};
        jvmtiEventCallbacks callbacks{};
        if (candidate->GetPotentialCapabilities(&potential) != JVMTI_ERROR_NONE) {
            candidate->DisposeEnvironment();
            return JNI_ERR;
        }
        requested.can_retransform_classes = potential.can_retransform_classes;
        requested.can_redefine_classes = potential.can_redefine_classes;
        requested.can_generate_all_class_hook_events = potential.can_generate_all_class_hook_events;
        requested.can_tag_objects = potential.can_tag_objects;
        callbacks.ClassFileLoadHook = class_bytes;
        callbacks.VMInit = initialize_communication;
        if (candidate->AddCapabilities(&requested) != JVMTI_ERROR_NONE
                || candidate->SetEventCallbacks(&callbacks, static_cast<jint>(sizeof(callbacks))) != JVMTI_ERROR_NONE) {
            candidate->DisposeEnvironment();
            return JNI_ERR;
        }
        machine = vm;
        tooling = candidate;
        startup_mode = mode;
        return JNI_OK;
    }

    const char* phase_name(jvmtiPhase phase) {
        switch (phase) {
            case JVMTI_PHASE_ONLOAD: return "onload";
            case JVMTI_PHASE_PRIMORDIAL: return "primordial";
            case JVMTI_PHASE_START: return "start";
            case JVMTI_PHASE_LIVE: return "live";
            case JVMTI_PHASE_DEAD: return "dead";
            default: return "unknown";
        }
    }
}

extern "C" JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char* options, void*) try {
    jint result = initialize(vm, "startup");
    return result == JNI_OK && provision_communication(options) ? JNI_OK : JNI_ERR;
} catch (const std::exception&) {
    return JNI_ERR;
}

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options, void*) try {
    jint result = initialize(vm, "attach");
    return result == JNI_OK && provision_communication(options) ? JNI_OK : JNI_ERR;
} catch (const std::exception&) {
    return JNI_ERR;
}

#if defined(__linux__) || defined(_WIN32)
static jint initialize_injected(const char* options) {
    using GetVMs = jint (JNICALL *)(JavaVM**, jsize, jsize*);
#ifdef _WIN32
    HMODULE jvm = GetModuleHandleW(L"jvm.dll");
    auto get_vms = jvm ? reinterpret_cast<GetVMs>(GetProcAddress(jvm, "JNI_GetCreatedJavaVMs")) : nullptr;
#else
    auto get_vms = reinterpret_cast<GetVMs>(dlsym(RTLD_DEFAULT, "JNI_GetCreatedJavaVMs"));
#endif
    JavaVM* vm = nullptr;
    jsize count = 0;
    if (!get_vms || get_vms(&vm, 1, &count) != JNI_OK || count != 1 || !vm) return JNI_ERR;
    JNIEnv* env = nullptr;
    if (vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return JNI_ERR;
    jint result = Agent_OnAttach(vm, const_cast<char*>(options), nullptr);
    jint detached = vm->DetachCurrentThread();
    return result == JNI_OK ? detached : result;
}

extern "C" JNIEXPORT jint JPI_InitializeInjectedAgent() try {
    return initialize_injected(nullptr);
} catch (const std::exception&) { return JNI_ERR; }

extern "C" JNIEXPORT jint JPI_InitializeInjectedAgentV3(jint* retain_reference, const char* options) try {
    static std::mutex lifecycle;
    static bool pinned_reference = false;
    std::lock_guard<std::mutex> lock(lifecycle);
    if (!retain_reference) return JNI_ERR;
    *retain_reference = pinned_reference ? 0 : 1;
    pinned_reference = true;
    return initialize_injected(options);
} catch (const std::exception&) { return JNI_ERR; }

extern "C" JNIEXPORT jint JPI_InitializeInjectedAgentV2(jint* retain_reference) {
    return JPI_InitializeInjectedAgentV3(retain_reference, nullptr);
}
#endif

#ifdef _WIN32
extern "C" JNIEXPORT DWORD WINAPI JPI_InitializeInjectedAgentThread(LPVOID) {
    return static_cast<DWORD>(JPI_InitializeInjectedAgent());
}
extern "C" JNIEXPORT DWORD WINAPI JPI_InitializeInjectedCommunicationThread(LPVOID options) {
    jint retain = 1;
    return static_cast<DWORD>(JPI_InitializeInjectedAgentV3(&retain, static_cast<const char*>(options)));
}
#endif

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) try {
    std::lock_guard<std::recursive_mutex> lock(operations);
    return tooling != nullptr && machine == vm ? JNI_VERSION_1_6 : JNI_ERR;
} catch (const std::exception&) {
    return JNI_ERR;
}

extern "C" JNIEXPORT jstring JNICALL Java_dev_whitedev_jpi_agent_jvmti_NativeJvmtiBridge_status0(JNIEnv* env, jclass) try {
    std::lock_guard<std::recursive_mutex> lock(operations);
    if (!ready(env)) return nullptr;
    jvmtiCapabilities granted{};
    jvmtiCapabilities potential{};
    jvmtiPhase phase{};
    jint version = 0;
    if (!check(env, tooling->GetCapabilities(&granted), "GetCapabilities")
            || !check(env, tooling->GetPotentialCapabilities(&potential), "GetPotentialCapabilities")
            || !check(env, tooling->GetPhase(&phase), "GetPhase")
            || !check(env, tooling->GetVersionNumber(&version), "GetVersionNumber")) return nullptr;
    std::ostringstream out;
    out << "backend=JVMTI\nmode=" << startup_mode << "\nphase=" << phase_name(phase)
        << "\nversion=" << ((version & JVMTI_VERSION_MASK_MAJOR) >> JVMTI_VERSION_SHIFT_MAJOR)
        << '.' << ((version & JVMTI_VERSION_MASK_MINOR) >> JVMTI_VERSION_SHIFT_MINOR)
        << "\ncan_retransform_classes=" << granted.can_retransform_classes
        << "\ncan_redefine_classes=" << granted.can_redefine_classes
        << "\ncan_generate_all_class_hook_events=" << granted.can_generate_all_class_hook_events
        << "\ncan_tag_objects=" << granted.can_tag_objects
        << "\npotential_can_tag_objects=" << potential.can_tag_objects
        << "\nclass_byte_limit=" << max_class_bytes
        << "\nclass_patch_limit=" << max_patch_bytes
        << "\nclass_inventory_limit=" << max_classes
        << "\nheap_instance_limit=" << max_heap_instances
        << "\ncontinuous_class_capture=" << (streaming.load() ? 1 : 0)
        << "\ncapture_event_limit=" << stream_event_limit << "\ncapture_byte_limit=" << stream_byte_limit
        << "\ncapture_dropped=" << dropped_events.load() << '\n';
    const char* states[] = {"unconfigured", "pending", "started", "failed"};
    out << "communication_bootstrap=" << states[communication_state.load()] << '\n';
    return env->NewStringUTF(out.str().c_str());
} catch (const std::exception&) {
    fail(env, "Native JVMTI status allocation failed");
    return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL Java_dev_whitedev_jpi_agent_jvmti_NativeJvmtiBridge_isModifiableClass0(JNIEnv* env, jclass, jclass type) try {
    std::lock_guard<std::recursive_mutex> lock(operations);
    if (!ready(env)) return JNI_FALSE;
    if (!type) { fail(env, "Class is required"); return JNI_FALSE; }
    jboolean modifiable = JNI_FALSE;
    if (!check(env, tooling->IsModifiableClass(type, &modifiable), "IsModifiableClass")) return JNI_FALSE;
    return modifiable;
} catch (const std::exception&) {
    fail(env, "Native class modifiability query failed");
    return JNI_FALSE;
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_dev_whitedev_jpi_agent_jvmti_NativeJvmtiBridge_loadedClasses0(JNIEnv* env, jclass) try {
    std::lock_guard<std::recursive_mutex> lock(operations);
    if (!ready(env)) return nullptr;
    jint count = 0;
    jclass* classes = nullptr;
    if (!check(env, tooling->GetLoadedClasses(&count, &classes), "GetLoadedClasses")) return nullptr;
    jobjectArray result = nullptr;
    if (count <= max_classes) {
        jclass class_type = env->FindClass("java/lang/Class");
        if (class_type != nullptr) result = env->NewObjectArray(count, class_type, nullptr);
        for (jint index = 0; index < count && result != nullptr && !env->ExceptionCheck(); index++) {
            env->SetObjectArrayElement(result, index, classes[index]);
        }
    } else fail(env, "JVMTI class inventory exceeds the 250,000-definition limit. Use Instrumentation or narrow the target.");
    for (jint index = 0; index < count; index++) env->DeleteLocalRef(classes[index]);
    tooling->Deallocate(reinterpret_cast<unsigned char*>(classes));
    return result;
} catch (const std::exception&) {
    fail(env, "Native JVMTI class inventory failed");
    return nullptr;
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_whitedev_jpi_agent_jvmti_NativeJvmtiBridge_classBytes0(JNIEnv* env, jclass, jclass target) try {
    std::lock_guard<std::recursive_mutex> lock(operations);
    if (!ready(env)) return nullptr;
    if (target == nullptr) {
        fail(env, "Class is required");
        return nullptr;
    }
    jvmtiCapabilities granted{};
    jboolean modifiable = JNI_FALSE;
    if (!check(env, tooling->GetCapabilities(&granted), "GetCapabilities")
            || !check(env, tooling->IsModifiableClass(target, &modifiable), "IsModifiableClass")) return nullptr;
    if (!granted.can_retransform_classes || !modifiable) {
        fail(env, "JVMTI retransformation is unavailable for this class or loading phase");
        return nullptr;
    }
    Capture capture{target, {}, false, false};
    if (pending != nullptr) {
        fail(env, "Recursive JVMTI bytecode capture is not supported");
        return nullptr;
    }
    if (!check(env, tooling->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, nullptr), "Enable ClassFileLoadHook")) return nullptr;
    CaptureGuard guard{tooling};
    pending = &capture;
    jvmtiError transformed = tooling->RetransformClasses(1, &target);
    jvmtiError disabled = guard.finish();
    if (!check(env, transformed, "RetransformClasses") || !check(env, disabled, "Disable ClassFileLoadHook")) return nullptr;
    if (capture.oversized || capture.allocation_failed || capture.bytes.empty()) {
        fail(env, capture.oversized ? "Class bytecode exceeds 4 MiB" : capture.allocation_failed ? "Native capture allocation failed" : "JVM did not deliver class bytes to this JVMTI environment");
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(capture.bytes.size()));
    if (result != nullptr) env->SetByteArrayRegion(result, 0, static_cast<jsize>(capture.bytes.size()), capture.bytes.data());
    return result;
} catch (const std::exception&) {
    pending = nullptr;
    fail(env, "Native JVMTI bytecode capture failed");
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL Java_dev_whitedev_jpi_agent_jvmti_NativeJvmtiBridge_redefineClass0(JNIEnv* env, jclass, jclass target, jbyteArray bytecode) try {
    std::lock_guard<std::recursive_mutex> lock(operations);
    if (!ready(env)) return;
    if (target == nullptr || bytecode == nullptr) {
        fail(env, "Class and replacement bytecode are required");
        return;
    }
    jsize length = env->GetArrayLength(bytecode);
    if (length < 16 || length > max_patch_bytes) {
        fail(env, "Replacement class bytecode size is outside the supported range");
        return;
    }
    jvmtiCapabilities granted{};
    jboolean modifiable = JNI_FALSE;
    if (!check(env, tooling->GetCapabilities(&granted), "GetCapabilities")
            || !check(env, tooling->IsModifiableClass(target, &modifiable), "IsModifiableClass")) return;
    if (!granted.can_redefine_classes || !modifiable) {
        fail(env, "JVMTI class redefinition is unavailable for this class or loading phase");
        return;
    }
    std::vector<unsigned char> bytes(static_cast<std::size_t>(length));
    env->GetByteArrayRegion(bytecode, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) return;
    jvmtiClassDefinition definition{};
    definition.klass = target;
    definition.class_byte_count = length;
    definition.class_bytes = bytes.data();
    check(env, tooling->RedefineClasses(1, &definition), "RedefineClasses");
} catch (const std::exception&) {
    fail(env, "Native JVMTI class redefinition failed");
}

extern "C" JNIEXPORT jlongArray JNICALL Java_dev_whitedev_jpi_agent_jvmti_NativeJvmtiBridge_heapStats0(JNIEnv* env, jclass, jclass target, jlong limit) try {
    std::lock_guard<std::recursive_mutex> lock(operations);
    if (!ready(env)) return nullptr;
    if (target == nullptr || limit < 1 || limit > max_heap_instances) {
        fail(env, "A class and an instance limit between 1 and 10000000 are required");
        return nullptr;
    }
    jvmtiCapabilities granted{};
    if (!check(env, tooling->GetCapabilities(&granted), "GetCapabilities")) return nullptr;
    if (!granted.can_tag_objects) {
        fail(env, "JVMTI heap iteration requires can_tag_objects");
        return nullptr;
    }
    HeapStats stats{limit};
    if (!check(env, tooling->IterateOverInstancesOfClass(target, JVMTI_HEAP_OBJECT_EITHER, heap_instance, &stats), "IterateOverInstancesOfClass")) return nullptr;
    jlong values[] = {stats.count, stats.bytes, stats.largest, stats.truncated ? 1 : 0};
    jlongArray result = env->NewLongArray(4);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 4, values);
    return result;
} catch (const std::exception&) {
    fail(env, "Native JVMTI heap inspection failed");
    return nullptr;
}

extern "C" JNIEXPORT jlong JNICALL Java_dev_whitedev_jpi_agent_jvmti_NativeJvmtiBridge_objectSize0(JNIEnv* env, jclass, jobject target) try {
    std::lock_guard<std::recursive_mutex> lock(operations);
    if (!ready(env)) return 0;
    if (target == nullptr) {
        fail(env, "Object is required");
        return 0;
    }
    jlong size = 0;
    return check(env, tooling->GetObjectSize(target, &size), "GetObjectSize") ? size : 0;
} catch (const std::exception&) {
    fail(env, "Native JVMTI object sizing failed");
    return 0;
}

extern "C" JNIEXPORT void JNICALL Java_dev_whitedev_jpi_agent_jvmti_NativeJvmtiBridge_startCapture0(JNIEnv* env, jclass) try {
    std::lock_guard<std::recursive_mutex> operation(operations);
    if (!ready(env)) return;
    jpi_archive::stop();
    jvmtiCapabilities granted{};
    if (!check(env, tooling->GetCapabilities(&granted), "GetCapabilities")) return;
    if (!granted.can_generate_all_class_hook_events) { fail(env, "Continuous interception is not available in this JVM environment"); return; }
    {
        std::lock_guard<std::mutex> lock(stream_mutex);
        streaming.store(false); class_events.clear(); stream_bytes = 0; dropped_events.store(0);
        streaming.store(true);
    }
    if (!check(env, tooling->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, nullptr), "Enable continuous ClassFileLoadHook"))
        streaming.store(false);
} catch (const std::exception&) { streaming.store(false); fail(env, "Could not start continuous class capture"); }

extern "C" JNIEXPORT void JNICALL Java_dev_whitedev_jpi_agent_jvmti_NativeJvmtiBridge_stopCapture0(JNIEnv* env, jclass, jboolean clear) try {
    std::lock_guard<std::recursive_mutex> operation(operations);
    if (!ready(env)) return;
    streaming.store(false);
    jpi_archive::stop();
    jvmtiError result = tooling->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, nullptr);
    if (clear) {
        std::lock_guard<std::mutex> lock(stream_mutex);
        class_events.clear(); stream_bytes = 0; dropped_events.store(0);
    }
    check(env, result, "Disable continuous ClassFileLoadHook");
} catch (const std::exception&) { fail(env, "Could not stop continuous class capture"); }

extern "C" JNIEXPORT jstring JNICALL Java_dev_whitedev_jpi_agent_jvmti_NativeJvmtiBridge_captureEvents0(JNIEnv* env, jclass) try {
    std::lock_guard<std::recursive_mutex> operation(operations);
    if (!ready(env)) return nullptr;
    std::ostringstream text;
    {
        std::lock_guard<std::mutex> lock(stream_mutex);
        text << "active=" << (streaming.load() ? 1 : 0) << "\nevents=" << class_events.size()
             << "\nbytes=" << stream_bytes << "\ndropped=" << dropped_events.load() << '\n';
        for (const auto& event : class_events)
            text << event.sequence << '\t' << (event.redefine ? "redefine" : "load") << '\t'
                 << event.bytes.size() << '\t' << base64(event.name) << '\n';
    }
    text << jpi_archive::status();
    return env->NewStringUTF(text.str().c_str());
} catch (const std::exception&) { fail(env, "Could not snapshot class capture events"); return nullptr; }

extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_whitedev_jpi_agent_jvmti_NativeJvmtiBridge_captureBytes0(JNIEnv* env, jclass, jlong sequence) try {
    std::lock_guard<std::recursive_mutex> operation(operations);
    if (!ready(env)) return nullptr;
    std::vector<jbyte> bytes;
    {
        std::lock_guard<std::mutex> lock(stream_mutex);
        for (const auto& event : class_events) if (event.sequence == sequence) { bytes = event.bytes; break; }
    }
    if (bytes.empty()) { fail(env, "Capture event is absent or was evicted; refresh the event snapshot"); return nullptr; }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()), bytes.data());
    return result;
} catch (const std::exception&) { fail(env, "Could not read captured class bytes"); return nullptr; }

extern "C" JNIEXPORT void JNICALL Java_dev_whitedev_jpi_agent_jvmti_NativeJvmtiBridge_startCaptureArchive0(JNIEnv* env, jclass type, jbyteArray encoded, jlong limit) try {
    std::lock_guard<std::recursive_mutex> operation(operations);
    if (!ready(env)) return;
    if (!encoded) { fail(env, "An archive directory is required"); return; }
    jsize length = env->GetArrayLength(encoded);
    if (length < 1 || length > 32768) { fail(env, "Invalid archive directory length"); return; }
    std::string directory(static_cast<std::size_t>(length), '\0');
    env->GetByteArrayRegion(encoded, 0, length, reinterpret_cast<jbyte*>(directory.data()));
    if (env->ExceptionCheck()) return;
    if (directory.find_first_of(std::string("\0\r\n", 3)) != std::string::npos) { fail(env, "Invalid archive directory"); return; }
    Java_dev_whitedev_jpi_agent_jvmti_NativeJvmtiBridge_startCapture0(env, type);
    if (env->ExceptionCheck()) return;
    try { jpi_archive::start(directory, limit); }
    catch (...) {
        Java_dev_whitedev_jpi_agent_jvmti_NativeJvmtiBridge_stopCapture0(env, type, JNI_FALSE);
        throw;
    }
} catch (const std::exception&) {
    if (!env->ExceptionCheck()) fail(env, "Could not start capture archive. Check the target directory, byte limit and filesystem permissions");
}
