#include "memory_tracker.h"
#include <android/log.h>
#include <execinfo.h>
#include <sstream>

#define LOG_TAG "MemoryTracker"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace sr_poc {
namespace memory {

MemoryTracker& MemoryTracker::getInstance() {
    static MemoryTracker instance;
    return instance;
}

void MemoryTracker::track_allocation(void* ptr, size_t size, size_t alignment, 
                                    const std::string& tag) {
    if (!enabled_ || !ptr) return;
    
    std::lock_guard<std::mutex> lock(mutex_);
    
    AllocationInfo info;
    info.size = size;
    info.alignment = alignment;
    info.tag = tag;
    info.timestamp = std::chrono::steady_clock::now();
    
    // Capture simplified stack trace (if available)
    info.stack_depth = 0;
    // Note: backtrace may not work well on Android, keeping it simple
    
    allocations_[ptr] = info;
    
    // Update statistics
    stats_.total_allocations++;
    stats_.current_allocated += size;
    stats_.total_bytes_allocated += size;
    
    if (!tag.empty()) {
        stats_.allocations_by_tag[tag] += size;
    }
    
    update_peak_memory();
    
    LOGD("Tracked allocation: %p, size=%zu, tag=%s", ptr, size, tag.c_str());
}

void MemoryTracker::track_deallocation(void* ptr) {
    if (!enabled_ || !ptr) return;
    
    std::lock_guard<std::mutex> lock(mutex_);
    
    auto it = allocations_.find(ptr);
    if (it == allocations_.end()) {
        LOGW("Deallocating untracked pointer: %p", ptr);
        return;
    }
    
    size_t size = it->second.size;
    std::string tag = it->second.tag;
    
    allocations_.erase(it);
    
    // Update statistics
    stats_.total_deallocations++;
    stats_.current_allocated -= size;
    stats_.total_bytes_deallocated += size;
    
    if (!tag.empty()) {
        stats_.allocations_by_tag[tag] -= size;
        if (stats_.allocations_by_tag[tag] == 0) {
            stats_.allocations_by_tag.erase(tag);
        }
    }
    
    LOGD("Tracked deallocation: %p, size=%zu", ptr, size);
}

MemoryTracker::Statistics MemoryTracker::get_statistics() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return stats_;
}

std::vector<void*> MemoryTracker::detect_leaks() const {
    std::lock_guard<std::mutex> lock(mutex_);
    
    std::vector<void*> leaks;
    for (const auto& pair : allocations_) {
        leaks.push_back(pair.first);
    }
    
    if (!leaks.empty()) {
        LOGW("Detected %zu memory leaks", leaks.size());
    }
    
    return leaks;
}

void MemoryTracker::dump_allocations() const {
    std::lock_guard<std::mutex> lock(mutex_);
    
    LOGI("=== Memory Allocations Dump ===");
    LOGI("Total allocations: %zu", allocations_.size());
    LOGI("Current allocated: %zu bytes", stats_.current_allocated);
    LOGI("Peak allocated: %zu bytes", stats_.peak_allocated);
    
    size_t index = 0;
    for (const auto& pair : allocations_) {
        const auto& info = pair.second;
        auto duration = std::chrono::steady_clock::now() - info.timestamp;
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(duration).count();
        
        LOGI("[%zu] ptr=%p, size=%zu, align=%zu, tag=%s, age=%lldms",
             index++, pair.first, info.size, info.alignment, 
             info.tag.c_str(), ms);
    }
    
    LOGI("=== Allocations by Tag ===");
    for (const auto& pair : stats_.allocations_by_tag) {
        LOGI("  %s: %zu bytes", pair.first.c_str(), pair.second);
    }
    
    LOGI("=== End Memory Dump ===");
}

void MemoryTracker::clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    allocations_.clear();
    stats_ = Statistics();
    LOGD("Memory tracker cleared");
}

void MemoryTracker::update_peak_memory() {
    if (stats_.current_allocated > stats_.peak_allocated) {
        stats_.peak_allocated = stats_.current_allocated;
        LOGD("New peak memory: %zu bytes", stats_.peak_allocated);
    }
}

// ScopedMemoryTracker implementation
ScopedMemoryTracker::~ScopedMemoryTracker() {
    size_t end_bytes = get_current_bytes();
    size_t delta = end_bytes - start_bytes_;
    
    if (delta > 0) {
        LOGI("Scope '%s' allocated %zu bytes", tag_.c_str(), delta);
    }
}

size_t ScopedMemoryTracker::get_current_bytes() const {
    auto stats = MemoryTracker::getInstance().get_statistics();
    return stats.current_allocated;
}

} // namespace memory
} // namespace sr_poc