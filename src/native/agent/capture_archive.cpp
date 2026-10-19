#include "capture_archive.h"
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <thread>

namespace {
struct Event {
    jlong sequence;
    std::string name;
    bool redefine;
    std::vector<jbyte> bytes;
};

std::string encode(const std::string& value) {
    constexpr char hex[] = "0123456789abcdef";
    std::string result;
    result.reserve(value.size() * 2);
    for (unsigned char c : value) { result += hex[c >> 4]; result += hex[c & 15]; }
    return result;
}

class Archive {
    std::mutex mutex;
    std::condition_variable changed;
    std::thread writer;
    std::deque<Event> queue;
    std::filesystem::path directory;
    std::size_t queued_bytes = 0;
    std::uint64_t reserved_bytes = 0, limit = 0, accepted = 0, written = 0, bytes_written = 0, dropped = 0, errors = 0;
    bool accepting = false, stopping = true, quota_reached = false;
    std::string last_error;

    void run(std::ofstream manifest) noexcept {
        try {
            for (;;) {
                Event event;
                {
                    std::unique_lock<std::mutex> lock(mutex);
                    changed.wait(lock, [&] { return stopping || !queue.empty(); });
                    if (queue.empty()) break;
                    event = std::move(queue.front());
                    queue.pop_front();
                    queued_bytes -= event.bytes.size();
                }
                const std::string filename = std::to_string(event.sequence) + ".class";
                const auto path = directory / filename;
                std::ofstream file(path, std::ios::binary | std::ios::out | std::ios::trunc);
                if (!file) throw std::runtime_error("Could not create a captured class file");
                file.write(reinterpret_cast<const char*>(event.bytes.data()), static_cast<std::streamsize>(event.bytes.size()));
                file.close();
                if (!file) {
                    std::error_code ignored;
                    std::filesystem::remove(path, ignored);
                    throw std::runtime_error("Could not finish writing a captured class file");
                }
                manifest << event.sequence << '\t' << (event.redefine ? "redefine" : "load") << '\t'
                         << event.bytes.size() << '\t' << encode(event.name) << '\t' << filename << '\n';
                manifest.flush();
                if (!manifest) throw std::runtime_error("Could not update the capture manifest");
                {
                    std::lock_guard<std::mutex> lock(mutex);
                    ++written;
                    bytes_written += event.bytes.size();
                }
            }
        } catch (...) {
            std::lock_guard<std::mutex> lock(mutex);
            ++errors;
            dropped += queue.size() + 1;
            queue.clear();
            queued_bytes = 0;
            accepting = false;
            last_error = "Archive writer failed. Check target filesystem access and free disk space";
        }
    }

public:
    ~Archive() { stop(); }

    void stop() {
        {
            std::lock_guard<std::mutex> lock(mutex);
            accepting = false;
            stopping = true;
        }
        changed.notify_all();
        if (writer.joinable()) writer.join();
    }

    void start(const std::string& path, jlong byte_limit) {
        if (byte_limit < 1 || byte_limit > 16LL * 1024 * 1024 * 1024)
            throw std::runtime_error("Archive byte limit must be between 1 byte and 16 GiB");
        auto destination = std::filesystem::u8path(path);
        if (!destination.is_absolute() || !std::filesystem::is_directory(destination.parent_path()))
            throw std::runtime_error("Select an existing absolute archive parent directory in the target filesystem");
        stop();
        if (!std::filesystem::create_directory(destination))
            throw std::runtime_error("Archive session directory already exists");
        try {
            std::ofstream manifest(destination / "manifest.tsv", std::ios::binary | std::ios::out);
            manifest << "sequence\tkind\tbytes\tname_modified_utf8_hex\tfile\n";
            manifest.flush();
            if (!manifest) throw std::runtime_error("Could not create the capture manifest");
            {
                std::lock_guard<std::mutex> lock(mutex);
                directory = destination;
                limit = static_cast<std::uint64_t>(byte_limit);
                queue.clear();
                queued_bytes = 0;
                reserved_bytes = accepted = written = bytes_written = dropped = errors = 0;
                last_error.clear();
                quota_reached = false;
                stopping = false;
                accepting = true;
            }
            writer = std::thread(&Archive::run, this, std::move(manifest));
        } catch (...) {
            {
                std::lock_guard<std::mutex> lock(mutex);
                accepting = false;
                stopping = true;
                queue.clear();
                queued_bytes = 0;
            }
            std::error_code ignored;
            std::filesystem::remove(destination / "manifest.tsv", ignored);
            std::filesystem::remove(destination, ignored);
            throw;
        }
    }

    void enqueue(jlong sequence, const std::string& name, bool redefine, const std::vector<jbyte>& bytes) noexcept {
        try {
            std::lock_guard<std::mutex> lock(mutex);
            if (!accepting) return;
            if (bytes.size() > limit - reserved_bytes || accepted >= 100000) {
                quota_reached = true;
                ++dropped;
                return;
            }
            if (queue.size() >= 256 || queued_bytes + bytes.size() > 16 * 1024 * 1024) { ++dropped; return; }
            try { queue.push_back(Event{sequence, name, redefine, bytes}); }
            catch (...) { ++dropped; return; }
            queued_bytes += bytes.size();
            reserved_bytes += bytes.size();
            ++accepted;
            changed.notify_one();
        } catch (...) { }
    }

    std::string status() {
        std::lock_guard<std::mutex> lock(mutex);
        std::ostringstream text;
        text << "archive_active=" << (accepting ? 1 : 0) << "\narchive_written=" << written
             << "\narchive_bytes=" << bytes_written << "\narchive_queued=" << queue.size()
             << "\narchive_dropped=" << dropped << "\narchive_errors=" << errors
             << "\narchive_quota_reached=" << (quota_reached ? 1 : 0) << "\narchive_limit=" << limit
             << "\narchive_directory_hex=" << encode(directory.u8string())
             << "\narchive_error=" << last_error << '\n';
        return text.str();
    }

    void droppedEvent() noexcept {
        try {
            std::lock_guard<std::mutex> lock(mutex);
            if (accepting) ++dropped;
        } catch (...) { }
    }
};

Archive& archive() { static Archive value; return value; }
}

void jpi_archive::start(const std::string& directory, jlong limit) { archive().start(directory, limit); }
void jpi_archive::stop() { archive().stop(); }
void jpi_archive::enqueue(jlong sequence, const std::string& name, bool redefine, const std::vector<jbyte>& bytes) noexcept {
    archive().enqueue(sequence, name, redefine, bytes);
}
std::string jpi_archive::status() { return archive().status(); }
void jpi_archive::dropped() noexcept { archive().droppedEvent(); }
