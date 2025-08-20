#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <atomic>

namespace sr_poc {
namespace memory {

/**
 * AlignedAllocator provides memory allocation with specific alignment requirements
 * for optimal SIMD and cache performance.
 */
class AlignedAllocator {
public:
    // Supported alignment sizes
    enum Alignment {
        SIMD_128 = 16,      // NEON/SSE alignment
        SIMD_256 = 32,      // AVX alignment  
        CACHE_LINE = 64,    // CPU cache line alignment
        SIMD_512 = 64,      // AVX-512 alignment
        PAGE = 4096         // Memory page alignment
    };
    
    /**
     * Allocate memory with specified alignment
     * @param size Size in bytes to allocate
     * @param alignment Required alignment (must be power of 2)
     * @return Aligned memory pointer or nullptr on failure
     */
    static void* allocate(size_t size, size_t alignment = CACHE_LINE);
    
    /**
     * Deallocate previously allocated aligned memory
     * @param ptr Pointer returned by allocate()
     */
    static void deallocate(void* ptr);
    
    /**
     * Get total bytes currently allocated
     */
    static size_t get_total_allocated() {
        return total_allocated_.load(std::memory_order_relaxed);
    }
    
    /**
     * Get number of active allocations
     */
    static size_t get_allocation_count() {
        return allocation_count_.load(std::memory_order_relaxed);
    }
    
    /**
     * Get peak memory usage
     */
    static size_t get_peak_allocated() {
        return peak_allocated_.load(std::memory_order_relaxed);
    }
    
    /**
     * Reset statistics (for testing)
     */
    static void reset_statistics();
    
private:
    // Allocation header stored before aligned memory
    struct AllocationHeader {
        void* raw_ptr;      // Original allocation pointer
        size_t size;        // User requested size
        size_t alignment;   // Alignment used
        uint32_t magic;     // Magic number for validation
    };
    
    static constexpr uint32_t MAGIC_NUMBER = 0xDEADBEEF;
    static constexpr uint32_t FREED_MAGIC = 0xFEEDFACE;
    
    // Statistics tracking
    static std::atomic<size_t> total_allocated_;
    static std::atomic<size_t> allocation_count_;
    static std::atomic<size_t> peak_allocated_;
    static std::atomic<size_t> total_deallocated_;
    
    /**
     * Align address up to specified alignment
     */
    static inline uintptr_t align_up(uintptr_t addr, size_t alignment) {
        return (addr + alignment - 1) & ~(alignment - 1);
    }
    
    /**
     * Update peak memory if current is higher
     */
    static void update_peak_memory(size_t current);
};

} // namespace memory
} // namespace sr_poc