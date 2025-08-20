#pragma once

#include <vector>
#include <queue>
#include <mutex>
#include <memory>
#include <chrono>
#include <atomic>
#include <unordered_map>
#include "aligned_allocator.h"

namespace sr_poc {
namespace memory {

/**
 * High-performance memory pool with tiered block sizes
 * Reduces allocation overhead and fragmentation
 */
class MemoryPool {
public:
    struct Config {
        // Block sizes for different tiers
        size_t small_block_size;
        size_t medium_block_size;
        size_t large_block_size;
        
        // Pre-allocated pool counts
        size_t small_pool_count;
        size_t medium_pool_count;
        size_t large_pool_count;
        
        // Alignment requirements
        size_t alignment;
        
        // Behavior flags
        bool enable_statistics;
        bool zero_on_dealloc;
        bool allow_expansion;
        
        // Constructor with defaults
        Config() 
            : small_block_size(8 * 1024),      // 8KB
              medium_block_size(64 * 1024),    // 64KB
              large_block_size(1024 * 1024),   // 1MB
              small_pool_count(128),           // 1MB total
              medium_pool_count(32),           // 2MB total
              large_pool_count(8),             // 8MB total
              alignment(AlignedAllocator::CACHE_LINE),
              enable_statistics(true),
              zero_on_dealloc(true),           // Security: zero memory on release
              allow_expansion(true) {}         // Allow dynamic pool expansion
    };
    
    // Pool statistics
    struct Statistics {
        size_t total_allocated;
        size_t total_deallocated;
        size_t current_usage;
        size_t peak_usage;
        size_t allocation_count;
        size_t deallocation_count;
        size_t cache_hits;
        size_t cache_misses;
        double hit_rate;
        
        // Per-tier statistics
        size_t small_pool_hits;
        size_t medium_pool_hits;
        size_t large_pool_hits;
        size_t direct_allocations;
        
        // Constructor
        Statistics() 
            : total_allocated(0),
              total_deallocated(0),
              current_usage(0),
              peak_usage(0),
              allocation_count(0),
              deallocation_count(0),
              cache_hits(0),
              cache_misses(0),
              hit_rate(0.0),
              small_pool_hits(0),
              medium_pool_hits(0),
              large_pool_hits(0),
              direct_allocations(0) {}
    };
    
    explicit MemoryPool(const Config& config = Config());
    ~MemoryPool();
    
    /**
     * Allocate memory from pool
     * @param size Required size in bytes
     * @return Allocated memory or nullptr on failure
     */
    void* allocate(size_t size);
    
    /**
     * Return memory to pool
     * @param ptr Memory to deallocate
     */
    void deallocate(void* ptr);
    
    /**
     * Reset all pools, releasing all memory
     */
    void reset();
    
    /**
     * Pre-allocate memory to warm up pools
     */
    void warmup();
    
    /**
     * Get current statistics
     */
    Statistics get_statistics() const;
    
    /**
     * Dump pool state for debugging
     */
    void dump_state() const;
    
private:
    /**
     * Internal block pool for a specific size tier
     */
    class BlockPool {
    public:
        BlockPool(size_t block_size, size_t initial_count, size_t alignment, bool allow_expansion);
        ~BlockPool();
        
        void* acquire();
        void release(void* ptr);
        bool owns(void* ptr) const;
        size_t get_block_size() const { return block_size_; }
        size_t get_free_count() const;
        size_t get_total_count() const { return blocks_.size(); }
        
    private:
        struct Block {
            std::unique_ptr<uint8_t, decltype(&AlignedAllocator::deallocate)> data;
            bool in_use;
            
            Block(void* ptr) 
                : data(static_cast<uint8_t*>(ptr), &AlignedAllocator::deallocate), 
                  in_use(false) {}
        };
        
        size_t block_size_;
        size_t alignment_;
        bool allow_expansion_;
        std::vector<std::unique_ptr<Block>> blocks_;
        std::queue<Block*> free_list_;
        mutable std::mutex mutex_;
        
        void expand_pool();
    };
    
    Config config_;
    std::unique_ptr<BlockPool> small_pool_;
    std::unique_ptr<BlockPool> medium_pool_;
    std::unique_ptr<BlockPool> large_pool_;
    
    mutable std::mutex stats_mutex_;
    Statistics stats_;
    
    // Tracking for direct allocations (larger than pool sizes)
    struct DirectAllocation {
        void* ptr;
        size_t size;
    };
    std::unordered_map<void*, DirectAllocation> direct_allocations_;
    mutable std::mutex direct_mutex_;
    
    // Helper methods
    BlockPool* select_pool(size_t size);
    void update_statistics(size_t size, bool is_allocation, bool is_pool_hit);
    void update_peak_usage();
};

} // namespace memory
} // namespace sr_poc