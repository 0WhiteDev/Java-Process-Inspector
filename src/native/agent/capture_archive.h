#pragma once

#include <jni.h>
#include <string>
#include <vector>

namespace jpi_archive {
void start(const std::string& directory, jlong limit);
void stop();
void enqueue(jlong sequence, const std::string& name, bool redefine, const std::vector<jbyte>& bytes) noexcept;
void dropped() noexcept;
std::string status();
}
