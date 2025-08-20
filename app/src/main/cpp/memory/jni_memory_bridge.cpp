#include <jni.h>
#include "memory_pool.h"
#include "memory_tracker.h"
#include "aligned_allocator.h"
#include <android/log.h>
#include <mutex>
#include <cstring>

#define LOG_TAG "JNIMemoryBridge"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace sr_poc::memory;

// Global memory pool instance
static std::unique_ptr<MemoryPool> g_memory_pool;
static std::mutex g_pool_mutex;

extern "C" {

/**
 * Initialize the native memory pool
 */
JNIEXPORT jboolean JNICALL
Java_com_example_sr_1poc_NativeBridge_nativeInitMemoryPool(
    JNIEnv* env,
    jobject /* this */,
    jint smallBlockSize,
    jint mediumBlockSize,
    jint largeBlockSize,
    jint smallPoolCount,
    jint mediumPoolCount,
    jint largePoolCount) {
    
    std::lock_guard<std::mutex> lock(g_pool_mutex);
    
    if (g_memory_pool) {
        LOGW("Memory pool already initialized, resetting...");
        g_memory_pool.reset();
    }
    
    MemoryPool::Config config;
    config.small_block_size = static_cast<size_t>(smallBlockSize);
    config.medium_block_size = static_cast<size_t>(mediumBlockSize);
    config.large_block_size = static_cast<size_t>(largeBlockSize);
    config.small_pool_count = static_cast<size_t>(smallPoolCount);
    config.medium_pool_count = static_cast<size_t>(mediumPoolCount);
    config.large_pool_count = static_cast<size_t>(largePoolCount);
    config.enable_statistics = true;
    config.zero_on_dealloc = true;
    config.allow_expansion = true;
    
    try {
        g_memory_pool = std::make_unique<MemoryPool>(config);
        g_memory_pool->warmup();
        
        LOGI("Memory pool initialized successfully");
        LOGI("  Small: %dx%d bytes", smallPoolCount, smallBlockSize);
        LOGI("  Medium: %dx%d bytes", mediumPoolCount, mediumBlockSize);
        LOGI("  Large: %dx%d bytes", largePoolCount, largeBlockSize);
        
        return JNI_TRUE;
        
    } catch (const std::exception& e) {
        LOGE("Failed to initialize memory pool: %s", e.what());
        return JNI_FALSE;
    }
}

/**
 * Allocate aligned DirectByteBuffer from pool
 */
JNIEXPORT jobject JNICALL
Java_com_example_sr_1poc_NativeBridge_nativeAllocateDirectBuffer(
    JNIEnv* env,
    jobject /* this */,
    jint size,
    jint alignment) {
    
    if (!g_memory_pool) {
        LOGE("Memory pool not initialized");
        return nullptr;
    }
    
    if (size <= 0) {
        LOGE("Invalid size: %d", size);
        return nullptr;
    }
    
    // Allocate from pool
    void* ptr = g_memory_pool->allocate(static_cast<size_t>(size));
    if (!ptr) {
        LOGE("Failed to allocate %d bytes from pool", size);
        return nullptr;
    }
    
    // Verify alignment
    uintptr_t addr = reinterpret_cast<uintptr_t>(ptr);
    if (alignment > 0 && (addr % alignment) != 0) {
        LOGW("Allocated memory not aligned as requested: ptr=%p, alignment=%d", ptr, alignment);
    }
    
    // Create DirectByteBuffer
    jobject buffer = env->NewDirectByteBuffer(ptr, size);
    if (!buffer) {
        g_memory_pool->deallocate(ptr);
        LOGE("Failed to create DirectByteBuffer for %d bytes", size);
        return nullptr;
    }
    
    LOGD("Allocated DirectByteBuffer: size=%d, ptr=%p", size, ptr);
    return buffer;
}

/**
 * Deallocate DirectByteBuffer back to pool
 */
JNIEXPORT void JNICALL
Java_com_example_sr_1poc_NativeBridge_nativeDeallocateDirectBuffer(
    JNIEnv* env,
    jobject /* this */,
    jobject buffer) {
    
    if (!buffer || !g_memory_pool) {
        return;
    }
    
    void* ptr = env->GetDirectBufferAddress(buffer);
    if (!ptr) {
        LOGW("Failed to get DirectBuffer address");
        return;
    }
    
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    
    g_memory_pool->deallocate(ptr);
    LOGD("Deallocated DirectByteBuffer: ptr=%p, size=%lld", ptr, capacity);
}

/**
 * Get memory pool statistics
 */
JNIEXPORT jobject JNICALL
Java_com_example_sr_1poc_NativeBridge_nativeGetMemoryStatistics(
    JNIEnv* env,
    jobject /* this */) {
    
    if (!g_memory_pool) {
        LOGW("Memory pool not initialized");
        return nullptr;
    }
    
    auto stats = g_memory_pool->get_statistics();
    
    // Find MemoryStatistics class
    jclass statsClass = env->FindClass("com/example/sr_poc/MemoryStatistics");
    if (!statsClass) {
        LOGE("MemoryStatistics class not found");
        return nullptr;
    }
    
    // Get constructor
    jmethodID constructor = env->GetMethodID(statsClass, "<init>", "()V");
    if (!constructor) {
        LOGE("MemoryStatistics constructor not found");
        return nullptr;
    }
    
    // Create instance
    jobject statsObj = env->NewObject(statsClass, constructor);
    if (!statsObj) {
        LOGE("Failed to create MemoryStatistics object");
        return nullptr;
    }
    
    // Set fields
    jfieldID field;
    
    field = env->GetFieldID(statsClass, "totalAllocated", "J");
    if (field) env->SetLongField(statsObj, field, static_cast<jlong>(stats.total_allocated));
    
    field = env->GetFieldID(statsClass, "totalDeallocated", "J");
    if (field) env->SetLongField(statsObj, field, static_cast<jlong>(stats.total_deallocated));
    
    field = env->GetFieldID(statsClass, "currentUsage", "J");
    if (field) env->SetLongField(statsObj, field, static_cast<jlong>(stats.current_usage));
    
    field = env->GetFieldID(statsClass, "peakUsage", "J");
    if (field) env->SetLongField(statsObj, field, static_cast<jlong>(stats.peak_usage));
    
    field = env->GetFieldID(statsClass, "allocationCount", "J");
    if (field) env->SetLongField(statsObj, field, static_cast<jlong>(stats.allocation_count));
    
    field = env->GetFieldID(statsClass, "deallocationCount", "J");
    if (field) env->SetLongField(statsObj, field, static_cast<jlong>(stats.deallocation_count));
    
    field = env->GetFieldID(statsClass, "cacheHits", "J");
    if (field) env->SetLongField(statsObj, field, static_cast<jlong>(stats.cache_hits));
    
    field = env->GetFieldID(statsClass, "cacheMisses", "J");
    if (field) env->SetLongField(statsObj, field, static_cast<jlong>(stats.cache_misses));
    
    field = env->GetFieldID(statsClass, "hitRate", "D");
    if (field) env->SetDoubleField(statsObj, field, stats.hit_rate);
    
    LOGD("Retrieved memory statistics: current=%zu, peak=%zu, hit_rate=%.2f%%",
         stats.current_usage, stats.peak_usage, stats.hit_rate * 100);
    
    return statsObj;
}

/**
 * Reset memory pool
 */
JNIEXPORT void JNICALL
Java_com_example_sr_1poc_NativeBridge_nativeResetMemoryPool(
    JNIEnv* env,
    jobject /* this */) {
    
    std::lock_guard<std::mutex> lock(g_pool_mutex);
    
    if (g_memory_pool) {
        g_memory_pool->reset();
        LOGI("Memory pool reset");
    } else {
        LOGW("Memory pool not initialized");
    }
}

/**
 * Warmup memory pool
 */
JNIEXPORT void JNICALL
Java_com_example_sr_1poc_NativeBridge_nativeWarmupMemoryPool(
    JNIEnv* env,
    jobject /* this */) {
    
    if (g_memory_pool) {
        g_memory_pool->warmup();
        LOGI("Memory pool warmed up");
    } else {
        LOGW("Memory pool not initialized");
    }
}

/**
 * Dump memory pool state for debugging
 */
JNIEXPORT void JNICALL
Java_com_example_sr_1poc_NativeBridge_nativeDumpMemoryPoolState(
    JNIEnv* env,
    jobject /* this */) {
    
    if (g_memory_pool) {
        g_memory_pool->dump_state();
    } else {
        LOGW("Memory pool not initialized");
    }
}

/**
 * Get aligned allocator statistics
 */
JNIEXPORT jstring JNICALL
Java_com_example_sr_1poc_NativeBridge_nativeGetAllocatorStats(
    JNIEnv* env,
    jobject /* this */) {
    
    char buffer[1024];
    
    // Get MemoryTracker stats
    auto tracker_stats = MemoryTracker::getInstance().get_statistics();
    
    snprintf(buffer, sizeof(buffer),
             "=== AlignedAllocator Stats ===\n"
             "  Total allocated: %zu bytes\n"
             "  Active allocations: %zu\n"
             "  Peak allocated: %zu bytes\n"
             "\n=== MemoryTracker Stats ===\n"
             "  Total allocations: %zu\n"
             "  Total deallocations: %zu\n"
             "  Current tracked: %zu bytes\n"
             "  Peak tracked: %zu bytes\n"
             "  Total bytes allocated: %zu\n"
             "  Total bytes deallocated: %zu",
             AlignedAllocator::get_total_allocated(),
             AlignedAllocator::get_allocation_count(),
             AlignedAllocator::get_peak_allocated(),
             tracker_stats.total_allocations,
             tracker_stats.total_deallocations,
             tracker_stats.current_allocated,
             tracker_stats.peak_allocated,
             tracker_stats.total_bytes_allocated,
             tracker_stats.total_bytes_deallocated);
    
    return env->NewStringUTF(buffer);
}

/**
 * Detect memory leaks
 */
JNIEXPORT jboolean JNICALL
Java_com_example_sr_1poc_NativeBridge_nativeDetectMemoryLeaks(
    JNIEnv* env,
    jobject /* this */) {
    
    auto leaks = MemoryTracker::getInstance().detect_leaks();
    
    if (!leaks.empty()) {
        LOGW("Detected %zu memory leaks", leaks.size());
        MemoryTracker::getInstance().dump_allocations();
        return JNI_TRUE;
    }
    
    LOGI("No memory leaks detected");
    return JNI_FALSE;
}

/**
 * Clear memory tracker
 */
JNIEXPORT void JNICALL
Java_com_example_sr_1poc_NativeBridge_nativeClearMemoryTracker(
    JNIEnv* env,
    jobject /* this */) {
    
    MemoryTracker::getInstance().clear();
    LOGI("Memory tracker cleared");
}

/**
 * Test aligned allocator functionality
 */
JNIEXPORT jboolean JNICALL
Java_com_example_sr_1poc_NativeBridge_nativeTestAlignedAllocator(
    JNIEnv* env,
    jobject /* this */) {
    
    LOGI("Testing AlignedAllocator functionality...");
    
    bool all_tests_passed = true;
    
    // Test 1: Basic allocation and deallocation
    {
        void* ptr = AlignedAllocator::allocate(1024, 16);
        if (!ptr) {
            LOGE("Test 1 FAILED: Could not allocate 1024 bytes with 16-byte alignment");
            all_tests_passed = false;
        } else {
            uintptr_t addr = reinterpret_cast<uintptr_t>(ptr);
            if ((addr % 16) != 0) {
                LOGE("Test 1 FAILED: Memory not properly aligned (addr=%p)", ptr);
                all_tests_passed = false;
            } else {
                LOGI("Test 1 PASSED: Basic allocation with alignment");
            }
            AlignedAllocator::deallocate(ptr);
        }
    }
    
    // Test 2: Various alignment sizes
    {
        size_t alignments[] = {16, 32, 64, 128, 256};
        for (size_t alignment : alignments) {
            void* ptr = AlignedAllocator::allocate(512, alignment);
            if (!ptr) {
                LOGE("Test 2 FAILED: Could not allocate with %zu-byte alignment", alignment);
                all_tests_passed = false;
            } else {
                uintptr_t addr = reinterpret_cast<uintptr_t>(ptr);
                if ((addr % alignment) != 0) {
                    LOGE("Test 2 FAILED: Incorrect alignment for %zu bytes", alignment);
                    all_tests_passed = false;
                }
                AlignedAllocator::deallocate(ptr);
            }
        }
        if (all_tests_passed) {
            LOGI("Test 2 PASSED: Various alignment sizes");
        }
    }
    
    // Test 3: Memory pattern test
    {
        const size_t test_size = 256;
        const uint8_t pattern = 0xAB;
        
        void* ptr = AlignedAllocator::allocate(test_size, 32);
        if (ptr) {
            // Write pattern
            memset(ptr, pattern, test_size);
            
            // Verify pattern
            bool pattern_intact = true;
            uint8_t* bytes = static_cast<uint8_t*>(ptr);
            for (size_t i = 0; i < test_size; i++) {
                if (bytes[i] != pattern) {
                    pattern_intact = false;
                    break;
                }
            }
            
            if (!pattern_intact) {
                LOGE("Test 3 FAILED: Memory pattern corrupted");
                all_tests_passed = false;
            } else {
                LOGI("Test 3 PASSED: Memory pattern integrity");
            }
            
            AlignedAllocator::deallocate(ptr);
        } else {
            LOGE("Test 3 FAILED: Could not allocate memory");
            all_tests_passed = false;
        }
    }
    
    // Test 4: Statistics tracking
    {
        size_t initial_allocated = AlignedAllocator::get_total_allocated();
        size_t initial_count = AlignedAllocator::get_allocation_count();
        
        void* ptr1 = AlignedAllocator::allocate(1024, 64);
        void* ptr2 = AlignedAllocator::allocate(2048, 64);
        
        size_t after_alloc = AlignedAllocator::get_total_allocated();
        size_t after_count = AlignedAllocator::get_allocation_count();
        
        if ((after_alloc - initial_allocated) != 3072) {
            LOGE("Test 4 FAILED: Incorrect allocation tracking");
            all_tests_passed = false;
        }
        if ((after_count - initial_count) != 2) {
            LOGE("Test 4 FAILED: Incorrect allocation count");
            all_tests_passed = false;
        }
        
        AlignedAllocator::deallocate(ptr1);
        AlignedAllocator::deallocate(ptr2);
        
        size_t final_allocated = AlignedAllocator::get_total_allocated();
        if (final_allocated != initial_allocated) {
            LOGE("Test 4 FAILED: Memory not properly deallocated");
            all_tests_passed = false;
        } else {
            LOGI("Test 4 PASSED: Statistics tracking");
        }
    }
    
    // Test 5: Large allocation
    {
        const size_t large_size = 1024 * 1024; // 1MB
        void* ptr = AlignedAllocator::allocate(large_size, 256);
        if (!ptr) {
            LOGE("Test 5 FAILED: Could not allocate large block");
            all_tests_passed = false;
        } else {
            uintptr_t addr = reinterpret_cast<uintptr_t>(ptr);
            if ((addr % 256) != 0) {
                LOGE("Test 5 FAILED: Large block not properly aligned");
                all_tests_passed = false;
            } else {
                LOGI("Test 5 PASSED: Large allocation with alignment");
            }
            AlignedAllocator::deallocate(ptr);
        }
    }
    
    if (all_tests_passed) {
        LOGI("All AlignedAllocator tests PASSED");
    } else {
        LOGE("Some AlignedAllocator tests FAILED");
    }
    
    return all_tests_passed ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"