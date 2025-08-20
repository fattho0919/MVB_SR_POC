#include "memory_pool.h"
#include "memory_tracker.h"
#include <android/log.h>
#include <algorithm>
#include <cstring>

#define LOG_TAG "MemoryPool"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace sr_poc {
namespace memory {

// BlockPool Implementation
MemoryPool::BlockPool::BlockPool(size_t block_size, size_t initial_count, 
                                 size_t alignment, bool allow_expansion)
    : block_size_(block_size), alignment_(alignment), allow_expansion_(allow_expansion) {
    
    blocks_.reserve(initial_count);
    
    // Pre-allocate blocks
    for (size_t i = 0; i < initial_count; ++i) {
        void* ptr = AlignedAllocator::allocate(block_size, alignment);
        if (ptr) {
            auto block = std::make_unique<Block>(ptr);
            free_list_.push(block.get());
            blocks_.push_back(std::move(block));
        } else {
            LOGW("Failed to pre-allocate block %zu of size %zu", i, block_size);
            break;
        }
    }
    
    LOGI("BlockPool created: size=%zu, count=%zu/%zu, alignment=%zu", 
         block_size, blocks_.size(), initial_count, alignment);
}

MemoryPool::BlockPool::~BlockPool() {
    size_t in_use_count = 0;
    for (const auto& block : blocks_) {
        if (block->in_use) {
            in_use_count++;
        }
    }
    
    if (in_use_count > 0) {
        LOGW("BlockPool destroyed with %zu blocks still in use", in_use_count);
    }
    
    LOGD("BlockPool destroyed: size=%zu, total blocks=%zu", 
         block_size_, blocks_.size());
}

void* MemoryPool::BlockPool::acquire() {
    std::lock_guard<std::mutex> lock(mutex_);
    
    if (free_list_.empty()) {
        if (allow_expansion_) {
            expand_pool();
        }
        
        if (free_list_.empty()) {
            LOGW("BlockPool exhausted: size=%zu", block_size_);
            return nullptr;
        }
    }
    
    Block* block = free_list_.front();
    free_list_.pop();
    block->in_use = true;
    
    return block->data.get();
}

void MemoryPool::BlockPool::release(void* ptr) {
    if (!ptr) return;
    
    std::lock_guard<std::mutex> lock(mutex_);
    
    // Find the block
    for (auto& block : blocks_) {
        if (block->data.get() == ptr) {
            if (!block->in_use) {
                LOGW("Double release detected for block at %p", ptr);
                return;
            }
            
            block->in_use = false;
            free_list_.push(block.get());
            return;
        }
    }
    
    LOGE("Attempted to release non-pool memory: %p", ptr);
}

bool MemoryPool::BlockPool::owns(void* ptr) const {
    if (!ptr) return false;
    
    std::lock_guard<std::mutex> lock(mutex_);
    
    for (const auto& block : blocks_) {
        if (block->data.get() == ptr) {
            return true;
        }
    }
    
    return false;
}

size_t MemoryPool::BlockPool::get_free_count() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return free_list_.size();
}

void MemoryPool::BlockPool::expand_pool() {
    // Expand by 25% or at least 1 block
    size_t expansion_count = std::max(size_t(1), blocks_.size() / 4);
    size_t allocated = 0;
    
    for (size_t i = 0; i < expansion_count; ++i) {
        void* ptr = AlignedAllocator::allocate(block_size_, alignment_);
        if (ptr) {
            auto block = std::make_unique<Block>(ptr);
            free_list_.push(block.get());
            blocks_.push_back(std::move(block));
            allocated++;
        } else {
            break;
        }
    }
    
    if (allocated > 0) {
        LOGI("BlockPool expanded: added %zu blocks (total: %zu)", 
             allocated, blocks_.size());
    }
}

// MemoryPool Implementation
MemoryPool::MemoryPool(const Config& config) : config_(config), stats_{} {
    LOGI("Initializing MemoryPool with config: small=%zu×%zu, medium=%zu×%zu, large=%zu×%zu",
         config.small_block_size, config.small_pool_count,
         config.medium_block_size, config.medium_pool_count,
         config.large_block_size, config.large_pool_count);
    
    small_pool_ = std::make_unique<BlockPool>(
        config.small_block_size,
        config.small_pool_count,
        config.alignment,
        config.allow_expansion
    );
    
    medium_pool_ = std::make_unique<BlockPool>(
        config.medium_block_size,
        config.medium_pool_count,
        config.alignment,
        config.allow_expansion
    );
    
    large_pool_ = std::make_unique<BlockPool>(
        config.large_block_size,
        config.large_pool_count,
        config.alignment,
        config.allow_expansion
    );
    
    LOGI("MemoryPool initialized successfully");
}

MemoryPool::~MemoryPool() {
    auto stats = get_statistics();
    
    LOGI("MemoryPool destroyed - Statistics:");
    LOGI("  Total allocations: %zu", stats.allocation_count);
    LOGI("  Peak usage: %zu bytes", stats.peak_usage);
    LOGI("  Hit rate: %.2f%%", stats.hit_rate * 100);
    LOGI("  Small pool hits: %zu", stats.small_pool_hits);
    LOGI("  Medium pool hits: %zu", stats.medium_pool_hits);
    LOGI("  Large pool hits: %zu", stats.large_pool_hits);
    LOGI("  Direct allocations: %zu", stats.direct_allocations);
    
    if (!direct_allocations_.empty()) {
        LOGW("MemoryPool destroyed with %zu direct allocations still active",
             direct_allocations_.size());
    }
}

void* MemoryPool::allocate(size_t size) {
    if (size == 0) {
        return nullptr;
    }
    
    BlockPool* pool = select_pool(size);
    void* ptr = nullptr;
    bool is_pool_hit = false;
    
    if (pool) {
        ptr = pool->acquire();
        is_pool_hit = (ptr != nullptr);
        
        if (ptr) {
            // Track which pool was used
            if (pool == small_pool_.get()) {
                std::lock_guard<std::mutex> lock(stats_mutex_);
                stats_.small_pool_hits++;
            } else if (pool == medium_pool_.get()) {
                std::lock_guard<std::mutex> lock(stats_mutex_);
                stats_.medium_pool_hits++;
            } else if (pool == large_pool_.get()) {
                std::lock_guard<std::mutex> lock(stats_mutex_);
                stats_.large_pool_hits++;
            }
        }
    }
    
    // Fall back to direct allocation if pool allocation failed or size too large
    if (!ptr) {
        ptr = AlignedAllocator::allocate(size, config_.alignment);
        
        if (ptr) {
            std::lock_guard<std::mutex> lock(direct_mutex_);
            direct_allocations_[ptr] = {ptr, size};
            
            std::lock_guard<std::mutex> stats_lock(stats_mutex_);
            stats_.direct_allocations++;
        }
    }
    
    if (ptr) {
        if (config_.zero_on_dealloc) {
            std::memset(ptr, 0, size);
        }
        
        update_statistics(size, true, is_pool_hit);
        
        // Track in memory tracker if enabled
        MemoryTracker::getInstance().track_allocation(ptr, size, config_.alignment, "MemoryPool");
    }
    
    return ptr;
}

void MemoryPool::deallocate(void* ptr) {
    if (!ptr) return;
    
    // Check each pool
    if (small_pool_->owns(ptr)) {
        if (config_.zero_on_dealloc) {
            std::memset(ptr, 0, config_.small_block_size);
        }
        small_pool_->release(ptr);
        update_statistics(config_.small_block_size, false, true);
    } else if (medium_pool_->owns(ptr)) {
        if (config_.zero_on_dealloc) {
            std::memset(ptr, 0, config_.medium_block_size);
        }
        medium_pool_->release(ptr);
        update_statistics(config_.medium_block_size, false, true);
    } else if (large_pool_->owns(ptr)) {
        if (config_.zero_on_dealloc) {
            std::memset(ptr, 0, config_.large_block_size);
        }
        large_pool_->release(ptr);
        update_statistics(config_.large_block_size, false, true);
    } else {
        // Check direct allocations
        std::lock_guard<std::mutex> lock(direct_mutex_);
        auto it = direct_allocations_.find(ptr);
        if (it != direct_allocations_.end()) {
            size_t size = it->second.size;
            direct_allocations_.erase(it);
            AlignedAllocator::deallocate(ptr);
            update_statistics(size, false, false);
        } else {
            LOGE("Attempted to deallocate unknown pointer: %p", ptr);
        }
    }
    
    // Track in memory tracker
    MemoryTracker::getInstance().track_deallocation(ptr);
}

void MemoryPool::reset() {
    LOGI("Resetting memory pool...");
    
    // Clear direct allocations
    {
        std::lock_guard<std::mutex> lock(direct_mutex_);
        for (const auto& pair : direct_allocations_) {
            AlignedAllocator::deallocate(pair.second.ptr);
        }
        direct_allocations_.clear();
    }
    
    // Recreate pools
    small_pool_ = std::make_unique<BlockPool>(
        config_.small_block_size,
        config_.small_pool_count,
        config_.alignment,
        config_.allow_expansion
    );
    
    medium_pool_ = std::make_unique<BlockPool>(
        config_.medium_block_size,
        config_.medium_pool_count,
        config_.alignment,
        config_.allow_expansion
    );
    
    large_pool_ = std::make_unique<BlockPool>(
        config_.large_block_size,
        config_.large_pool_count,
        config_.alignment,
        config_.allow_expansion
    );
    
    // Reset statistics
    {
        std::lock_guard<std::mutex> lock(stats_mutex_);
        stats_ = Statistics();
    }
    
    LOGI("Memory pool reset completed");
}

void MemoryPool::warmup() {
    LOGI("Warming up memory pool...");
    
    std::vector<void*> allocations;
    
    // Warm up small pool
    for (size_t i = 0; i < config_.small_pool_count / 2; ++i) {
        void* ptr = allocate(config_.small_block_size);
        if (ptr) allocations.push_back(ptr);
    }
    
    // Warm up medium pool
    for (size_t i = 0; i < config_.medium_pool_count / 2; ++i) {
        void* ptr = allocate(config_.medium_block_size);
        if (ptr) allocations.push_back(ptr);
    }
    
    // Warm up large pool
    for (size_t i = 0; i < config_.large_pool_count / 2; ++i) {
        void* ptr = allocate(config_.large_block_size);
        if (ptr) allocations.push_back(ptr);
    }
    
    // Release all allocations
    for (void* ptr : allocations) {
        deallocate(ptr);
    }
    
    LOGI("Memory pool warmup completed: warmed %zu allocations", allocations.size());
}

MemoryPool::Statistics MemoryPool::get_statistics() const {
    std::lock_guard<std::mutex> lock(stats_mutex_);
    
    Statistics stats = stats_;
    
    // Calculate hit rate
    size_t total_requests = stats.cache_hits + stats.cache_misses;
    if (total_requests > 0) {
        stats.hit_rate = static_cast<double>(stats.cache_hits) / total_requests;
    }
    
    return stats;
}

void MemoryPool::dump_state() const {
    auto stats = get_statistics();
    
    LOGI("=== MemoryPool State Dump ===");
    LOGI("Configuration:");
    LOGI("  Small: %zu bytes × %zu", config_.small_block_size, config_.small_pool_count);
    LOGI("  Medium: %zu bytes × %zu", config_.medium_block_size, config_.medium_pool_count);
    LOGI("  Large: %zu bytes × %zu", config_.large_block_size, config_.large_pool_count);
    LOGI("  Alignment: %zu", config_.alignment);
    
    LOGI("Pool Status:");
    LOGI("  Small: %zu/%zu free", small_pool_->get_free_count(), small_pool_->get_total_count());
    LOGI("  Medium: %zu/%zu free", medium_pool_->get_free_count(), medium_pool_->get_total_count());
    LOGI("  Large: %zu/%zu free", large_pool_->get_free_count(), large_pool_->get_total_count());
    
    LOGI("Statistics:");
    LOGI("  Current usage: %zu bytes", stats.current_usage);
    LOGI("  Peak usage: %zu bytes", stats.peak_usage);
    LOGI("  Allocations: %zu", stats.allocation_count);
    LOGI("  Deallocations: %zu", stats.deallocation_count);
    LOGI("  Hit rate: %.2f%%", stats.hit_rate * 100);
    LOGI("  Direct allocations: %zu active", direct_allocations_.size());
    
    LOGI("=== End State Dump ===");
}

MemoryPool::BlockPool* MemoryPool::select_pool(size_t size) {
    if (size <= config_.small_block_size) {
        return small_pool_.get();
    } else if (size <= config_.medium_block_size) {
        return medium_pool_.get();
    } else if (size <= config_.large_block_size) {
        return large_pool_.get();
    }
    return nullptr;
}

void MemoryPool::update_statistics(size_t size, bool is_allocation, bool is_pool_hit) {
    if (!config_.enable_statistics) return;
    
    std::lock_guard<std::mutex> lock(stats_mutex_);
    
    if (is_allocation) {
        stats_.total_allocated += size;
        stats_.current_usage += size;
        stats_.allocation_count++;
        
        if (is_pool_hit) {
            stats_.cache_hits++;
        } else {
            stats_.cache_misses++;
        }
        
        update_peak_usage();
    } else {
        stats_.total_deallocated += size;
        stats_.current_usage -= size;
        stats_.deallocation_count++;
    }
}

void MemoryPool::update_peak_usage() {
    if (stats_.current_usage > stats_.peak_usage) {
        stats_.peak_usage = stats_.current_usage;
    }
}

} // namespace memory
} // namespace sr_poc