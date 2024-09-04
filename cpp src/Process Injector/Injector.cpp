#include <windows.h>
#include <jni.h>
#include <iostream>
#include <string>

std::string GetDllDirectory(HMODULE hModule) {
    char path[MAX_PATH];
    GetModuleFileName(hModule, path, MAX_PATH);
    std::string fullPath(path);
    size_t pos = fullPath.find_last_of("\\/");
    return (std::string::npos == pos) ? "" : fullPath.substr(0, pos + 1);
}

void LoadJarToJVM(JNIEnv* env, const char* jarPath, const char* mainClassName) {
    jclass classLoaderClass = env->FindClass("java/net/URLClassLoader");
    jmethodID constructor = env->GetMethodID(classLoaderClass, "<init>", "([Ljava/net/URL;)V");

    jclass fileClass = env->FindClass("java/io/File");
    jmethodID fileConstructor = env->GetMethodID(fileClass, "<init>", "(Ljava/lang/String;)V");

    jstring jJarPath = env->NewStringUTF(jarPath);
    jobject fileObject = env->NewObject(fileClass, fileConstructor, jJarPath);

    jmethodID toURI = env->GetMethodID(fileClass, "toURI", "()Ljava/net/URI;");
    jobject uriObject = env->CallObjectMethod(fileObject, toURI);

    jclass uriClass = env->FindClass("java/net/URI");
    jmethodID toURL = env->GetMethodID(uriClass, "toURL", "()Ljava/net/URL;");
    jobject urlObject = env->CallObjectMethod(uriObject, toURL);

    jobjectArray urlArray = env->NewObjectArray(1, env->FindClass("java/net/URL"), urlObject);
    jobject urlClassLoader = env->NewObject(classLoaderClass, constructor, urlArray);

    jmethodID loadClass = env->GetMethodID(classLoaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring jMainClassName = env->NewStringUTF(mainClassName);
    jclass mainClass = (jclass)env->CallObjectMethod(urlClassLoader, loadClass, jMainClassName);

    if (mainClass == nullptr) {
        std::cerr << "Could not find main class: " << mainClassName << std::endl;
        return;
    }

    jmethodID mainMethod = env->GetStaticMethodID(mainClass, "main", "([Ljava/lang/String;)V");
    if (mainMethod == nullptr) {
        std::cerr << "Could not find main method in class: " << mainClassName << std::endl;
        return;
    }

    jobjectArray mainArgs = env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    env->CallStaticVoidMethod(mainClass, mainMethod, mainArgs);
}

JNIEnv* GetJNIEnv() {
    JavaVM* jvm;
    JNIEnv* env;
    JavaVMInitArgs vmArgs;
    JavaVMOption options[1];

    vmArgs.version = JNI_VERSION_1_8;
    vmArgs.nOptions = 1;
    vmArgs.options = options;
    vmArgs.ignoreUnrecognized = false;

    jint result = JNI_GetCreatedJavaVMs(&jvm, 1, nullptr);
    if (result != JNI_OK || jvm == nullptr) {
        std::cerr << "Failed to get created JVMs. Error code: " << result << std::endl;
        return nullptr;
    }

    result = jvm->AttachCurrentThread((void**)&env, nullptr);
    if (result != JNI_OK) {
        std::cerr << "Failed to attach to JVM. Error code: " << result << std::endl;
        return nullptr;
    }

    return env;
}

extern "C" __declspec(dllexport) void InjectJar(const char* jarPath, const char* mainClassName) {
    JNIEnv* env = GetJNIEnv();
    if (env == nullptr) {
        std::cerr << "Failed to obtain JNI environment." << std::endl;
        return;
    }

    LoadJarToJVM(env, jarPath, mainClassName);
}

BOOL APIENTRY DllMain(HMODULE hModule, DWORD ul_reason_for_call, LPVOID lpReserved) {
    switch (ul_reason_for_call) {
    case DLL_PROCESS_ATTACH: {
        std::string dllDir = GetDllDirectory(hModule);
        std::string jarFullPath = dllDir + "JPI-1.0.jar";
        LoadJarToJVM(GetJNIEnv(), jarFullPath.c_str(), "uk.whitedev.InjectableMain");
        break;
    }
    case DLL_THREAD_ATTACH:
    case DLL_THREAD_DETACH:
    case DLL_PROCESS_DETACH:
        break;
    }
    return TRUE;
}
