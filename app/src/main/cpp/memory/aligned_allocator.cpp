#include "aligned_allocator.h"
#include "memory_tracker.h"
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <new>

#define LOG_TAG "AlignedAllocator"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace sr_poc {
namespace memory {

// Static member definitions
std::atomic<size_t> AlignedAllocator::total_allocated_{0};
std::atomic<size_t> AlignedAllocator::allocation_count_{0};
std::atomic<size_t> AlignedAllocator::peak_allocated_{0};
std::atomic<size_t> AlignedAllocator::total_deallocated_{0};

void* AlignedAllocator::allocate(size_t size, size_t alignment) {
    // Validate alignment (must be power of 2)
    if (alignment == 0 || (alignment & (alignment - 1)) != 0) {
        LOGE("Invalid alignment: %zu (must be power of 2)", alignment);
        return nullptr;
    }
    
    // Minimum alignment for header
    if (alignment < sizeof(AllocationHeader)) {
        alignment = sizeof(AllocationHeader);
    }
    
    // Calculate total size needed
    size_t header_size = sizeof(AllocationHeader);
    size_t total_size = size + alignment + header_size;
    
    // Allocate raw memory
    void* raw_ptr = std::malloc(total_size);
    if (!raw_ptr) {
        LOGE("Failed to allocate %zu bytes", total_size);
        return nullptr;
    }
    
    // Calculate aligned address for user data
    uintptr_t raw_addr = reinterpret_cast<uintptr_t>(raw_ptr);
    uintptr_t user_addr = align_up(raw_addr + header_size, alignment);
    
    // Store header just before user data
    AllocationHeader* header = reinterpret_cast<AllocationHeader*>(user_addr - header_size);
    header->raw_ptr = raw_ptr;
    header->size = size;
    header->alignment = alignment;
    header->magic = MAGIC_NUMBER;
    
    // Update statistics
    size_t prev_total = total_allocated_.fetch_add(size, std::memory_order_relaxed);
    allocation_count_.fetch_add(1, std::memory_order_relaxed);
    update_peak_memory(prev_total + size);
    
    // Track allocation in MemoryTracker
    MemoryTracker::getInstance().track_allocation(
        reinterpret_cast<void*>(user_addr), size, alignment, "AlignedAllocator"
    );
    
    LOGD("Allocated %zu bytes with alignment %zu at %p (raw: %p)", 
         size, alignment, reinterpret_cast<void*>(user_addr), raw_ptr);
    
    return reinterpret_cast<void*>(user_addr);
}

void AlignedAllocator::deallocate(void* ptr) {
    if (!ptr) {
        return;
    }
    
    // Get header
    AllocationHeader* header = reinterpret_cast<AllocationHeader*>(
        reinterpret_cast<uintptr_t>(ptr) - sizeof(AllocationHeader)
    );
    
    // Validate magic number
    if (header->magic != MAGIC_NUMBER) {
        if (header->magic == FREED_MAGIC) {
            LOGE("Double free detected at %p", ptr);
        } else {
            LOGE("Invalid pointer or corruption detected at %p (magic: 0x%08x)", 
                 ptr, header->magic);
        }
        std::abort();
    }
    
    // Update statistics
    total_allocated_.fetch_sub(header->size, std::memory_order_relaxed);
    total_deallocated_.fetch_add(header->size, std::memory_order_relaxed);
    allocation_count_.fetch_sub(1, std::memory_order_relaxed);
    
    // Track deallocation in MemoryTracker
    MemoryTracker::getInstance().track_deallocation(ptr);
    
    LOGD("Deallocated %zu bytes at %p (raw: %p)", 
         header->size, ptr, header->raw_ptr);
    
    // Mark as freed (helps detect double-free)
    void* raw_ptr = header->raw_ptr;
    header->magic = FREED_MAGIC;
    
    // Free raw memory
    std::free(raw_ptr);
}

void AlignedAllocator::update_peak_memory(size_t current) {
    size_t peak = peak_allocated_.load(std::memory_order_relaxed);
    while (current > peak) {
        if (peak_allocated_.compare_exchange_weak(peak, current,
                                                  std::memory_order_relaxed,
                                                  std::memory_order_relaxed)) {
            LOGD("New peak memory: %zu bytes", current);
            break;
        }
    }
}

void AlignedAllocator::reset_statistics() {
    total_allocated_.store(0, std::memory_order_relaxed);
    allocation_count_.store(0, std::memory_order_relaxed);
    peak_allocated_.store(0, std::memory_order_relaxed);
    total_deallocated_.store(0, std::memory_order_relaxed);
    LOGD("Statistics reset");
}

} // namespace memory
} // namespace sr_poc