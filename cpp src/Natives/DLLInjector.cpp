#include <windows.h>
#include <tlhelp32.h>
#include <iostream>
#include <vector>
#include <string>

#include <jni.h>

extern "C" {
    JNIEXPORT jboolean JNICALL Java_uk_whitedev_injector_DLLInjector_injectDLL(JNIEnv *env, jclass clazz, jlong pid, jstring dllPath);
}

bool InjectDLL(DWORD processID, const std::string& dllPath) {
    HANDLE hProcess = OpenProcess(PROCESS_ALL_ACCESS, FALSE, processID);
    if (!hProcess) {
        std::cerr << "OpenProcess failed" << std::endl;
        return false;
    }

    LPVOID pRemoteMemory = VirtualAllocEx(hProcess, NULL, dllPath.size() + 1, MEM_COMMIT, PAGE_READWRITE);
    if (!pRemoteMemory) {
        std::cerr << "VirtualAllocEx failed" << std::endl;
        CloseHandle(hProcess);
        return false;
    }

    if (!WriteProcessMemory(hProcess, pRemoteMemory, dllPath.c_str(), dllPath.size() + 1, NULL)) {
        std::cerr << "WriteProcessMemory failed" << std::endl;
        VirtualFreeEx(hProcess, pRemoteMemory, 0, MEM_RELEASE);
        CloseHandle(hProcess);
        return false;
    }

    HMODULE hKernel32 = GetModuleHandleA("kernel32.dll");
    if (!hKernel32) {
        std::cerr << "GetModuleHandleA failed" << std::endl;
        VirtualFreeEx(hProcess, pRemoteMemory, 0, MEM_RELEASE);
        CloseHandle(hProcess);
        return false;
    }

    LPVOID pLoadLibrary = (LPVOID)GetProcAddress(hKernel32, "LoadLibraryA");
    if (!pLoadLibrary) {
        std::cerr << "GetProcAddress failed" << std::endl;
        VirtualFreeEx(hProcess, pRemoteMemory, 0, MEM_RELEASE);
        CloseHandle(hProcess);
        return false;
    }

    HANDLE hThread = CreateRemoteThread(hProcess, NULL, 0, (LPTHREAD_START_ROUTINE)pLoadLibrary, pRemoteMemory, 0, NULL);
    if (!hThread) {
        std::cerr << "CreateRemoteThread failed" << std::endl;
        VirtualFreeEx(hProcess, pRemoteMemory, 0, MEM_RELEASE);
        CloseHandle(hProcess);
        return false;
    }

    WaitForSingleObject(hThread, INFINITE);

    VirtualFreeEx(hProcess, pRemoteMemory, 0, MEM_RELEASE);
    CloseHandle(hThread);
    CloseHandle(hProcess);

    return true;
}

JNIEXPORT jboolean JNICALL Java_uk_whitedev_injector_DLLInjector_injectDLL(JNIEnv *env, jclass clazz, jlong pid, jstring dllPath) {
    const char *pathChars = env->GetStringUTFChars(dllPath, NULL);
    if (!pathChars) {
        std::cerr << "Failed to convert jstring to C string" << std::endl;
        return JNI_FALSE;
    }

    std::string dllPathStr(pathChars);
    env->ReleaseStringUTFChars(dllPath, pathChars);

    bool result = InjectDLL(static_cast<DWORD>(pid), dllPathStr);

    return result ? JNI_TRUE : JNI_FALSE;
}
