#include <windows.h>
#include <tlhelp32.h>
#include <iostream>
#include <vector>
#include <string>
#include <cstring>
#include <map>
#include <jni.h>

extern "C" {
    JNIEXPORT jobject JNICALL Java_uk_whitedev_memory_MemoryEditor_listProcesses(JNIEnv* env, jobject obj);
    JNIEXPORT jobjectArray JNICALL Java_uk_whitedev_memory_MemoryEditor_getOpenWindows(JNIEnv *env, jclass clazz);
    JNIEXPORT jobjectArray JNICALL Java_uk_whitedev_memory_MemoryEditor_scanMemory(JNIEnv *env, jclass clazz, jlong pid, jstring searchStr, jint dataType);
    JNIEXPORT void JNICALL Java_uk_whitedev_memory_MemoryEditor_modifyMemory(JNIEnv *env, jclass clazz, jlong pid, jobjectArray locationsArray, jstring newValueStr, jint type);
}

JNIEXPORT jobject JNICALL Java_uk_whitedev_memory_MemoryEditor_listProcesses(JNIEnv* env, jobject obj) {
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE_VALUE) {
        std::cerr << "Failed to take process snapshot." << std::endl;
        return nullptr;
    }

    PROCESSENTRY32 processEntry;
    processEntry.dwSize = sizeof(PROCESSENTRY32);

    std::map<DWORD, std::string> processMap;

    if (Process32First(snapshot, &processEntry)) {
        do {
            processMap[processEntry.th32ProcessID] = processEntry.szExeFile;
        } while (Process32Next(snapshot, &processEntry));
    } else {
        std::cerr << "Failed to retrieve first process entry." << std::endl;
    }

    CloseHandle(snapshot);

    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jmethodID hashMapPut = env->GetMethodID(hashMapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    jobject hashMapObject = env->NewObject(hashMapClass, hashMapInit);

    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID integerInit = env->GetMethodID(integerClass, "<init>", "(I)V");

    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID stringInit = env->GetMethodID(stringClass, "<init>", "(Ljava/lang/String;)V");

    for (const auto& entry : processMap) {
        jobject pidObject = env->NewObject(integerClass, integerInit, entry.first);
        jstring nameString = env->NewStringUTF(entry.second.c_str());
        env->CallObjectMethod(hashMapObject, hashMapPut, pidObject, nameString);
    }

    return hashMapObject;
}

struct WindowInfo {
    std::wstring title;
    DWORD pid;
};

std::vector<WindowInfo> windowList;

BOOL CALLBACK EnumWindowsProc(HWND hwnd, LPARAM lParam) {
    if (!IsWindowVisible(hwnd)) {
        return TRUE;
    }

    DWORD pid;
    GetWindowThreadProcessId(hwnd, &pid);

    wchar_t windowTitle[256];
    GetWindowTextW(hwnd, windowTitle, sizeof(windowTitle) / sizeof(wchar_t));

    if (wcslen(windowTitle) > 0) {
        windowList.push_back({windowTitle, pid});
    }

    return TRUE;
}

JNIEXPORT jobjectArray JNICALL Java_uk_whitedev_memory_MemoryEditor_getOpenWindows(JNIEnv *env, jclass clazz) {
    windowList.clear();
    EnumWindows(EnumWindowsProc, 0);

    jclass windowInfoClass = env->FindClass("uk/whitedev/memory/WindowInfo");
    if (windowInfoClass == nullptr) return nullptr;

    jmethodID constructor = env->GetMethodID(windowInfoClass, "<init>", "(Ljava/lang/String;J)V");
    if (constructor == nullptr) return nullptr;

    jobjectArray resultArray = env->NewObjectArray(windowList.size(), windowInfoClass, nullptr);
    if (resultArray == nullptr) return nullptr;

    for (size_t i = 0; i < windowList.size(); ++i) {
        const WindowInfo &info = windowList[i];

        jstring titleString = env->NewString(reinterpret_cast<const jchar*>(info.title.c_str()), info.title.length());
        jobject windowInfoObj = env->NewObject(windowInfoClass, constructor, titleString, info.pid);
        env->SetObjectArrayElement(resultArray, i, windowInfoObj);
        env->DeleteLocalRef(windowInfoObj);
        env->DeleteLocalRef(titleString);
    }

    return resultArray;
}

struct MemoryLocation {
    uintptr_t address;
    std::string value;
};

std::vector<MemoryLocation> ScanMemory(HANDLE processHandle, const std::string& searchStr, int dataType) {
    SYSTEM_INFO sysInfo;
    GetSystemInfo(&sysInfo);

    MEMORY_BASIC_INFORMATION mbi;
    uintptr_t address = (uintptr_t)sysInfo.lpMinimumApplicationAddress;

    std::vector<MemoryLocation> foundLocations;

    while (address < (uintptr_t)sysInfo.lpMaximumApplicationAddress) {
        if (VirtualQueryEx(processHandle, (LPCVOID)address, &mbi, sizeof(mbi)) == sizeof(mbi)) {
            if (mbi.State == MEM_COMMIT && (mbi.Protect & PAGE_READWRITE)) {
                std::vector<char> buffer(mbi.RegionSize);
                SIZE_T bytesRead;

                if (ReadProcessMemory(processHandle, mbi.BaseAddress, buffer.data(), mbi.RegionSize, &bytesRead)) {
                    switch (dataType) {
                        case 0: {
                            size_t searchLen = searchStr.length();
                            for (size_t i = 0; i < bytesRead - searchLen + 1; ++i) {
                                if (std::memcmp(buffer.data() + i, searchStr.c_str(), searchLen) == 0) {
                                    MemoryLocation loc;
                                    loc.address = (uintptr_t)mbi.BaseAddress + i;
                                    loc.value = searchStr;
                                    foundLocations.push_back(loc);
                                }
                            }
                            break;
                        }

                        case 1: {
                            int searchValue = std::stoi(searchStr);
                            for (size_t i = 0; i < bytesRead - sizeof(int) + 1; ++i) {
                                int valueAtAddr;
                                std::memcpy(&valueAtAddr, buffer.data() + i, sizeof(int));
                                if (valueAtAddr == searchValue) {
                                    MemoryLocation loc;
                                    loc.address = (uintptr_t)mbi.BaseAddress + i;
                                    loc.value = std::to_string(searchValue);
                                    foundLocations.push_back(loc);
                                }
                            }
                            break;
                        }

                        case 2: {
                            double searchValue = std::stod(searchStr);
                            for (size_t i = 0; i < bytesRead - sizeof(double) + 1; ++i) {
                                double valueAtAddr;
                                std::memcpy(&valueAtAddr, buffer.data() + i, sizeof(double));
                                if (valueAtAddr == searchValue) {
                                    MemoryLocation loc;
                                    loc.address = (uintptr_t)mbi.BaseAddress + i;
                                    loc.value = std::to_string(searchValue);
                                    foundLocations.push_back(loc);
                                }
                            }
                            break;
                        }

                        case 3: {
                            long searchValue = std::stol(searchStr);
                            for (size_t i = 0; i < bytesRead - sizeof(long) + 1; ++i) {
                                long valueAtAddr;
                                std::memcpy(&valueAtAddr, buffer.data() + i, sizeof(long));
                                if (valueAtAddr == searchValue) {
                                    MemoryLocation loc;
                                    loc.address = (uintptr_t)mbi.BaseAddress + i;
                                    loc.value = std::to_string(searchValue);
                                    foundLocations.push_back(loc);
                                }
                            }
                            break;
                        }

                        case 4: {
                            float searchValue = std::stof(searchStr);
                            for (size_t i = 0; i < bytesRead - sizeof(float) + 1; ++i) {
                                float valueAtAddr;
                                std::memcpy(&valueAtAddr, buffer.data() + i, sizeof(float));
                                if (valueAtAddr == searchValue) {
                                    MemoryLocation loc;
                                    loc.address = (uintptr_t)mbi.BaseAddress + i;
                                    loc.value = std::to_string(searchValue);
                                    foundLocations.push_back(loc);
                                }
                            }
                            break;
                        }

                        case 5: {
                            short searchValue = std::stoi(searchStr);
                            for (size_t i = 0; i < bytesRead - sizeof(short) + 1; ++i) {
                                short valueAtAddr;
                                std::memcpy(&valueAtAddr, buffer.data() + i, sizeof(short));
                                if (valueAtAddr == searchValue) {
                                    MemoryLocation loc;
                                    loc.address = (uintptr_t)mbi.BaseAddress + i;
                                    loc.value = std::to_string(searchValue);
                                    foundLocations.push_back(loc);
                                }
                            }
                            break;
                        }
                    }
                }
            }
            address += mbi.RegionSize;
        } else {
            break;
        }
    }

    return foundLocations;
}

JNIEXPORT jobjectArray JNICALL Java_uk_whitedev_memory_MemoryEditor_scanMemory(JNIEnv *env, jclass clazz, jlong pid, jstring searchStr, jint dataType) {
    HANDLE processHandle = OpenProcess(PROCESS_ALL_ACCESS, FALSE, pid);
    if (processHandle == nullptr) return nullptr;
    const char *nativeSearchStr = env->GetStringUTFChars(searchStr, 0);
    std::vector<MemoryLocation> locations = ScanMemory(processHandle, nativeSearchStr, dataType);
    env->ReleaseStringUTFChars(searchStr, nativeSearchStr);

    jclass memoryLocationClass = env->FindClass("uk/whitedev/memory/MemoryLocation");
    if (memoryLocationClass == nullptr) return nullptr;

    jobjectArray resultArray = env->NewObjectArray(locations.size(), memoryLocationClass, nullptr);
    if (resultArray == nullptr) return nullptr;

    jmethodID constructor = env->GetMethodID(memoryLocationClass, "<init>", "(JLjava/lang/String;)V");
    if (constructor == nullptr) return nullptr;

    for (size_t i = 0; i < locations.size(); ++i) {
        const MemoryLocation &loc = locations[i];

        jstring valueString = env->NewStringUTF(loc.value.c_str());
        jobject memoryLocationObj = env->NewObject(memoryLocationClass, constructor, loc.address, valueString);
        env->DeleteLocalRef(valueString);

        env->SetObjectArrayElement(resultArray, i, memoryLocationObj);
        env->DeleteLocalRef(memoryLocationObj);
    }

    return resultArray;
}

void ModifyMemory(HANDLE processHandle, const std::vector<MemoryLocation>& locations, const std::string& newValue, int dataType) {
    size_t newLen;
    std::vector<char> newBuffer;

    for (const auto& loc : locations) {
        switch (dataType) {
            case 0:
                newLen = newValue.length();
                newBuffer.resize(newLen);
                std::memcpy(newBuffer.data(), newValue.c_str(), newLen);
                break;

            case 1: {
                int newIntValue = std::stoi(newValue);
                newLen = sizeof(int);
                newBuffer.resize(newLen);
                std::memcpy(newBuffer.data(), &newIntValue, newLen);
                break;
            }

            case 2: {
                double newDoubleValue = std::stod(newValue);
                newLen = sizeof(double);
                newBuffer.resize(newLen);
                std::memcpy(newBuffer.data(), &newDoubleValue, newLen);
                break;
            }

            case 3: {
                long newLongValue = std::stol(newValue);
                newLen = sizeof(long);
                newBuffer.resize(newLen);
                std::memcpy(newBuffer.data(), &newLongValue, newLen);
                break;
            }

            case 4: {
                float newFloatValue = std::stof(newValue);
                newLen = sizeof(float);
                newBuffer.resize(newLen);
                std::memcpy(newBuffer.data(), &newFloatValue, newLen);
                break;
            }

            case 5: {
                short newShortValue = std::stoi(newValue);
                newLen = sizeof(short);
                newBuffer.resize(newLen);
                std::memcpy(newBuffer.data(), &newShortValue, newLen);
                break;
            }
        }

        SIZE_T bytesWritten;
        if (!WriteProcessMemory(processHandle, (LPVOID)loc.address, newBuffer.data(), newLen, &bytesWritten)) {
            std::cerr << "Failed to write memory at address: " << std::hex << loc.address << std::endl;
        } else {
            std::cout << "Modified memory at: " << std::hex << loc.address << " to value: " << newValue << std::endl;
        }

        if (newLen < loc.value.length()) {
            size_t bytesToClear = loc.value.length() - newLen;
            std::vector<char> clearBuffer(bytesToClear, '\0');
            if (!WriteProcessMemory(processHandle, (LPVOID)(loc.address + newLen), clearBuffer.data(), bytesToClear, &bytesWritten)) {
                std::cerr << "Failed to clear remaining bytes at address: " << std::hex << (loc.address + newLen) << std::endl;
            }
        }
    }
}

JNIEXPORT void JNICALL Java_uk_whitedev_memory_MemoryEditor_modifyMemory(JNIEnv *env, jclass clazz, jlong pid, jobjectArray locationsArray, jstring newValueStr, jint type) {
    HANDLE processHandle = OpenProcess(PROCESS_ALL_ACCESS, FALSE, pid);
    if (processHandle == nullptr) return;

    jclass memoryLocationClass = env->FindClass("uk/whitedev/memory/MemoryLocation");
    if (memoryLocationClass == nullptr) return;

    jmethodID getAddressMethod = env->GetMethodID(memoryLocationClass, "getAddress", "()J");
    jmethodID getValueMethod = env->GetMethodID(memoryLocationClass, "getValue", "()Ljava/lang/String;");
    if (getAddressMethod == nullptr || getValueMethod == nullptr) return;

    jsize length = env->GetArrayLength(locationsArray);
    std::vector<MemoryLocation> locations;
    for (jsize i = 0; i < length; ++i) {
        jobject locationObj = env->GetObjectArrayElement(locationsArray, i);
        if (locationObj == nullptr) continue;

        jlong address = env->CallLongMethod(locationObj, getAddressMethod);
        jstring valueStr = (jstring)env->CallObjectMethod(locationObj, getValueMethod);
        const char *valueChars = env->GetStringUTFChars(valueStr, nullptr);
        std::string value(valueChars);
        env->ReleaseStringUTFChars(valueStr, valueChars);

        locations.push_back({ static_cast<uintptr_t>(address), value });
        env->DeleteLocalRef(locationObj);
    }

    const char *newValueChars = env->GetStringUTFChars(newValueStr, nullptr);
    std::string newValue(newValueChars);
    env->ReleaseStringUTFChars(newValueStr, newValueChars);

    ModifyMemory(processHandle, locations, newValue, type);

    CloseHandle(processHandle);
}
