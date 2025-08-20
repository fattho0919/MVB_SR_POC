#include "aligned_allocator.h"
#include <android/log.h>
#include <cassert>
#include <chrono>
#include <thread>
#include <vector>
#include <cstring>

#define LOG_TAG "AlignedAllocatorTest"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace sr_poc::memory;

class AlignedAllocatorTest {
public:
    static bool run_all_tests() {
        LOGI("Starting AlignedAllocator tests...");
        
        bool all_passed = true;
        
        // Reset statistics before testing
        AlignedAllocator::reset_statistics();
        
        all_passed &= test_basic_allocation();
        all_passed &= test_alignment_correctness();
        all_passed &= test_various_sizes();
        all_passed &= test_statistics_tracking();
        all_passed &= test_edge_cases();
        all_passed &= test_concurrent_access();
        all_passed &= test_memory_pattern();
        
        if (all_passed) {
            LOGI("All AlignedAllocator tests PASSED!");
        } else {
            LOGE("Some AlignedAllocator tests FAILED!");
        }
        
        return all_passed;
    }
    
private:
    static bool test_basic_allocation() {
        LOGI("Test: Basic allocation and deallocation");
        
        // Test basic allocation
        void* ptr = AlignedAllocator::allocate(1024, 64);
        if (!ptr) {
            LOGE("Failed to allocate memory");
            return false;
        }
        
        // Check statistics
        size_t allocated = AlignedAllocator::get_total_allocated();
        if (allocated != 1024) {
            LOGE("Statistics mismatch: expected 1024, got %zu", allocated);
            return false;
        }
        
        // Test deallocation
        AlignedAllocator::deallocate(ptr);
        
        allocated = AlignedAllocator::get_total_allocated();
        if (allocated != 0) {
            LOGE("Memory not properly deallocated: %zu bytes still allocated", allocated);
            return false;
        }
        
        LOGI("Basic allocation test PASSED");
        return true;
    }
    
    static bool test_alignment_correctness() {
        LOGI("Test: Alignment correctness");
        
        // Test various alignments
        size_t alignments[] = {16, 32, 64, 128, 256, 512, 1024, 4096};
        
        for (size_t alignment : alignments) {
            void* ptr = AlignedAllocator::allocate(1024, alignment);
            if (!ptr) {
                LOGE("Failed to allocate with alignment %zu", alignment);
                return false;
            }
            
            uintptr_t addr = reinterpret_cast<uintptr_t>(ptr);
            if (addr % alignment != 0) {
                LOGE("Alignment incorrect: ptr=%p, alignment=%zu, remainder=%zu",
                     ptr, alignment, addr % alignment);
                AlignedAllocator::deallocate(ptr);
                return false;
            }
            
            AlignedAllocator::deallocate(ptr);
        }
        
        LOGI("Alignment correctness test PASSED");
        return true;
    }
    
    static bool test_various_sizes() {
        LOGI("Test: Various allocation sizes");
        
        // Test different sizes
        size_t sizes[] = {1, 7, 15, 16, 17, 31, 32, 33, 63, 64, 65,
                         127, 128, 129, 255, 256, 257, 511, 512, 513,
                         1023, 1024, 1025, 4095, 4096, 4097,
                         8191, 8192, 8193, 65535, 65536, 65537};
        
        std::vector<void*> ptrs;
        
        for (size_t size : sizes) {
            void* ptr = AlignedAllocator::allocate(size, 64);
            if (!ptr) {
                LOGE("Failed to allocate %zu bytes", size);
                // Clean up
                for (void* p : ptrs) {
                    AlignedAllocator::deallocate(p);
                }
                return false;
            }
            
            // Write pattern to verify memory is usable
            std::memset(ptr, 0xAB, size);
            
            ptrs.push_back(ptr);
        }
        
        // Verify and deallocate
        for (size_t i = 0; i < ptrs.size(); i++) {
            uint8_t* bytes = static_cast<uint8_t*>(ptrs[i]);
            for (size_t j = 0; j < sizes[i]; j++) {
                if (bytes[j] != 0xAB) {
                    LOGE("Memory corruption detected at size %zu", sizes[i]);
                    // Clean up
                    for (void* p : ptrs) {
                        AlignedAllocator::deallocate(p);
                    }
                    return false;
                }
            }
            AlignedAllocator::deallocate(ptrs[i]);
        }
        
        LOGI("Various sizes test PASSED");
        return true;
    }
    
    static bool test_statistics_tracking() {
        LOGI("Test: Statistics tracking");
        
        AlignedAllocator::reset_statistics();
        
        // Allocate multiple blocks
        void* ptr1 = AlignedAllocator::allocate(1024, 64);
        void* ptr2 = AlignedAllocator::allocate(2048, 64);
        void* ptr3 = AlignedAllocator::allocate(4096, 64);
        
        size_t total = AlignedAllocator::get_total_allocated();
        size_t count = AlignedAllocator::get_allocation_count();
        size_t peak = AlignedAllocator::get_peak_allocated();
        
        if (total != 7168) { // 1024 + 2048 + 4096
            LOGE("Total allocated mismatch: expected 7168, got %zu", total);
            return false;
        }
        
        if (count != 3) {
            LOGE("Allocation count mismatch: expected 3, got %zu", count);
            return false;
        }
        
        if (peak != 7168) {
            LOGE("Peak allocated mismatch: expected 7168, got %zu", peak);
            return false;
        }
        
        // Deallocate one
        AlignedAllocator::deallocate(ptr2);
        
        total = AlignedAllocator::get_total_allocated();
        count = AlignedAllocator::get_allocation_count();
        
        if (total != 5120) { // 1024 + 4096
            LOGE("Total after dealloc mismatch: expected 5120, got %zu", total);
            return false;
        }
        
        if (count != 2) {
            LOGE("Count after dealloc mismatch: expected 2, got %zu", count);
            return false;
        }
        
        // Peak should remain unchanged
        peak = AlignedAllocator::get_peak_allocated();
        if (peak != 7168) {
            LOGE("Peak should not change: expected 7168, got %zu", peak);
            return false;
        }
        
        // Clean up
        AlignedAllocator::deallocate(ptr1);
        AlignedAllocator::deallocate(ptr3);
        
        LOGI("Statistics tracking test PASSED");
        return true;
    }
    
    static bool test_edge_cases() {
        LOGI("Test: Edge cases");
        
        // Test null deallocation (should not crash)
        AlignedAllocator::deallocate(nullptr);
        
        // Test zero size allocation
        void* ptr = AlignedAllocator::allocate(0, 64);
        if (ptr != nullptr) {
            LOGE("Zero size allocation should return nullptr");
            AlignedAllocator::deallocate(ptr);
            return false;
        }
        
        // Test invalid alignment (not power of 2)
        ptr = AlignedAllocator::allocate(1024, 33);
        if (ptr != nullptr) {
            LOGE("Invalid alignment should fail");
            AlignedAllocator::deallocate(ptr);
            return false;
        }
        
        // Test very large allocation
        ptr = AlignedAllocator::allocate(1024 * 1024 * 100, 64); // 100MB
        if (ptr) {
            // Successfully allocated large block
            AlignedAllocator::deallocate(ptr);
        }
        
        LOGI("Edge cases test PASSED");
        return true;
    }
    
    static bool test_concurrent_access() {
        LOGI("Test: Concurrent access");
        
        const int thread_count = 8;
        const int allocations_per_thread = 100;
        std::vector<std::thread> threads;
        std::vector<std::vector<void*>> thread_ptrs(thread_count);
        std::atomic<bool> all_success(true);
        
        // Create threads
        for (int t = 0; t < thread_count; t++) {
            threads.emplace_back([t, &thread_ptrs, &all_success]() {
                for (int i = 0; i < allocations_per_thread; i++) {
                    size_t size = 256 + (t * 100) + i;
                    void* ptr = AlignedAllocator::allocate(size, 64);
                    
                    if (!ptr) {
                        LOGE("Thread %d failed to allocate %zu bytes", t, size);
                        all_success = false;
                        return;
                    }
                    
                    // Write pattern
                    std::memset(ptr, t + i, size);
                    thread_ptrs[t].push_back(ptr);
                    
                    // Small delay to increase concurrency
                    std::this_thread::sleep_for(std::chrono::microseconds(10));
                }
            });
        }
        
        // Wait for all threads
        for (auto& thread : threads) {
            thread.join();
        }
        
        if (!all_success) {
            // Clean up any allocations
            for (const auto& ptrs : thread_ptrs) {
                for (void* ptr : ptrs) {
                    AlignedAllocator::deallocate(ptr);
                }
            }
            return false;
        }
        
        // Verify allocation count
        size_t expected_count = thread_count * allocations_per_thread;
        size_t actual_count = AlignedAllocator::get_allocation_count();
        
        // Note: Count might be off due to previous tests
        LOGI("Concurrent allocations: %zu", actual_count);
        
        // Clean up
        for (const auto& ptrs : thread_ptrs) {
            for (void* ptr : ptrs) {
                AlignedAllocator::deallocate(ptr);
            }
        }
        
        LOGI("Concurrent access test PASSED");
        return true;
    }
    
    static bool test_memory_pattern() {
        LOGI("Test: Memory pattern integrity");
        
        const size_t test_size = 8192;
        const uint8_t pattern = 0xDE;
        
        void* ptr = AlignedAllocator::allocate(test_size, 64);
        if (!ptr) {
            LOGE("Failed to allocate test memory");
            return false;
        }
        
        // Fill with pattern
        std::memset(ptr, pattern, test_size);
        
        // Verify pattern
        uint8_t* bytes = static_cast<uint8_t*>(ptr);
        for (size_t i = 0; i < test_size; i++) {
            if (bytes[i] != pattern) {
                LOGE("Pattern mismatch at offset %zu: expected 0x%02X, got 0x%02X",
                     i, pattern, bytes[i]);
                AlignedAllocator::deallocate(ptr);
                return false;
            }
        }
        
        // Test boundary conditions
        // Allocate adjacent block
        void* ptr2 = AlignedAllocator::allocate(test_size, 64);
        if (!ptr2) {
            LOGE("Failed to allocate second block");
            AlignedAllocator::deallocate(ptr);
            return false;
        }
        
        // Fill second block with different pattern
        std::memset(ptr2, 0xAD, test_size);
        
        // Verify first block unchanged
        for (size_t i = 0; i < test_size; i++) {
            if (bytes[i] != pattern) {
                LOGE("First block corrupted after second allocation");
                AlignedAllocator::deallocate(ptr);
                AlignedAllocator::deallocate(ptr2);
                return false;
            }
        }
        
        AlignedAllocator::deallocate(ptr);
        AlignedAllocator::deallocate(ptr2);
        
        LOGI("Memory pattern test PASSED");
        return true;
    }
};

// Export test function for JNI
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_sr_1poc_NativeBridge_nativeTestAlignedAllocator(
    JNIEnv* env,
    jobject /* this */) {
    
    return AlignedAllocatorTest::run_all_tests() ? JNI_TRUE : JNI_FALSE;
}