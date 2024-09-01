#include <windows.h>
#include <tlhelp32.h>
#include <iostream>
#include <vector>
#include <string>

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

std::vector<DWORD> ListJavaProcesses() {
    std::vector<DWORD> javaProcessIDs;

    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE_VALUE) {
        std::cerr << "Failed to take process snapshot." << std::endl;
        return javaProcessIDs;
    }

    PROCESSENTRY32W processEntry;
    processEntry.dwSize = sizeof(PROCESSENTRY32W);

    if (Process32FirstW(snapshot, &processEntry)) {
        do {
            if (wcscmp(processEntry.szExeFile, L"java.exe") == 0) {
                javaProcessIDs.push_back(processEntry.th32ProcessID);
            }
        } while (Process32NextW(snapshot, &processEntry));
    } else {
        std::cerr << "Failed to retrieve first process entry." << std::endl;
    }

    CloseHandle(snapshot);
    return javaProcessIDs;
}

void ListProcesses() {
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE_VALUE) {
        std::cerr << "Failed to take process snapshot." << std::endl;
        return;
    }

    PROCESSENTRY32 processEntry;
    processEntry.dwSize = sizeof(PROCESSENTRY32);

    if (Process32First(snapshot, &processEntry)) {
        std::cout << "Process ID\tProcess Name" << std::endl;
        do {
            std::wcout << processEntry.th32ProcessID << "\t\t" << processEntry.szExeFile << std::endl;
        } while (Process32Next(snapshot, &processEntry));
    } else {
        std::cerr << "Failed to retrieve first process entry." << std::endl;
    }

    CloseHandle(snapshot);
}

int main() {
    ListProcesses();

    std::cout << "Enter the Process ID to inject the class: ";
    DWORD processID;
    std::cin >> processID;

    std::cin.ignore();

    std::cout << "Enter the full path to the DLL containing the class: ";
    std::string dllPath;
    std::getline(std::cin, dllPath);

    if (InjectDLL(processID, dllPath)) {
        std::cout << "DLL injected successfully!" << std::endl;
    } else {
        std::cerr << "DLL injection failed." << std::endl;
    }

    return 0;
}
