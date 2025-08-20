#pragma once

#include <unordered_map>
#include <mutex>
#include <string>
#include <chrono>
#include <vector>

namespace sr_poc {
namespace memory {

/**
 * MemoryTracker provides detailed tracking of memory allocations
 * for debugging and profiling purposes.
 */
class MemoryTracker {
public:
    struct AllocationInfo {
        size_t size;
        size_t alignment;
        std::string tag;
        std::chrono::steady_clock::time_point timestamp;
        void* stack_trace[8];  // Simplified stack trace
        int stack_depth;
    };
    
    struct Statistics {
        size_t total_allocations = 0;
        size_t total_deallocations = 0;
        size_t current_allocated = 0;
        size_t peak_allocated = 0;
        size_t total_bytes_allocated = 0;
        size_t total_bytes_deallocated = 0;
        std::unordered_map<std::string, size_t> allocations_by_tag;
    };
    
    /**
     * Get singleton instance
     */
    static MemoryTracker& getInstance();
    
    /**
     * Track a new allocation
     */
    void track_allocation(void* ptr, size_t size, size_t alignment, 
                         const std::string& tag = "");
    
    /**
     * Track a deallocation
     */
    void track_deallocation(void* ptr);
    
    /**
     * Get current statistics
     */
    Statistics get_statistics() const;
    
    /**
     * Check for memory leaks
     * @return Vector of leaked allocation pointers
     */
    std::vector<void*> detect_leaks() const;
    
    /**
     * Dump current allocations to log
     */
    void dump_allocations() const;
    
    /**
     * Clear all tracking data
     */
    void clear();
    
    /**
     * Enable/disable tracking
     */
    void set_enabled(bool enabled) { enabled_ = enabled; }
    bool is_enabled() const { return enabled_; }
    
private:
    MemoryTracker() = default;
    ~MemoryTracker() = default;
    
    // Prevent copying
    MemoryTracker(const MemoryTracker&) = delete;
    MemoryTracker& operator=(const MemoryTracker&) = delete;
    
    mutable std::mutex mutex_;
    std::unordered_map<void*, AllocationInfo> allocations_;
    Statistics stats_;
    bool enabled_ = true;
    
    void update_peak_memory();
};

/**
 * RAII helper for scoped memory tracking
 */
class ScopedMemoryTracker {
public:
    explicit ScopedMemoryTracker(const std::string& tag)
        : tag_(tag), start_bytes_(get_current_bytes()) {
    }
    
    ~ScopedMemoryTracker();
    
private:
    std::string tag_;
    size_t start_bytes_;
    
    size_t get_current_bytes() const;
};

} // namespace memory
} // namespace sr_poc