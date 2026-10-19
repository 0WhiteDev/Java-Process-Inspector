#include <chrono>
#include <thread>
#include <pthread.h>
#ifndef JPI_TEST_DELAY
#define JPI_TEST_DELAY 0
#endif
extern "C" __attribute__((visibility("default"))) int JPI_InitializeInjectedAgent() {
    if (JPI_TEST_DELAY) {
        pthread_setname_np(pthread_self(), "jpi-audit-slow");
        std::this_thread::sleep_for(std::chrono::seconds(JPI_TEST_DELAY));
    }
    return -17;
}
#ifdef JPI_TEST_RELEASE
extern "C" __attribute__((visibility("default"))) int JPI_InitializeInjectedAgentV2(int* retain) {
    *retain = 0;
    return 0;
}
#endif
