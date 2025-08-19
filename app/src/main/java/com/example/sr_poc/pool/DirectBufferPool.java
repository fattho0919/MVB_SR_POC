package com.example.sr_poc.pool;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Queue;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import com.example.sr_poc.utils.DirectMemoryUtils;

/**
 * Thread-safe DirectByteBuffer pool for efficient memory management.
 * 
 * Provides reusable DirectByteBuffer allocation to reduce allocation overhead
 * and memory fragmentation. Supports common model sizes with preallocation
 * and automatic size optimization.
 */
public class DirectBufferPool {
    
    private static final String TAG = "DirectBufferPool";
    
    // Common model sizes for preallocation (bytes)
    private static final int[] COMMON_SIZES = {
        1280 * 720 * 3 * 4,    // 720p FLOAT32: 11,059,200 bytes (~10.5MB)
        1280 * 720 * 3 * 2,    // 720p FLOAT16: 5,529,600 bytes (~5.3MB)
        1280 * 720 * 3 * 1,    // 720p UINT8/INT8: 2,764,800 bytes (~2.6MB)
        1920 * 1080 * 3 * 4,   // 1080p FLOAT32: 24,883,200 bytes (~23.7MB)
        1920 * 1080 * 3 * 2,   // 1080p FLOAT16: 12,441,600 bytes (~11.9MB)
        1920 * 1080 * 3 * 1,   // 1080p UINT8/INT8: 6,220,800 bytes (~5.9MB)
        
        // Common tile sizes (for tiling operations)
        512 * 512 * 3 * 4,     // 512x512 FLOAT32: 3,145,728 bytes (~3MB)
        512 * 512 * 3 * 2,     // 512x512 FLOAT16: 1,572,864 bytes (~1.5MB)
        256 * 256 * 3 * 4,     // 256x256 FLOAT32: 786,432 bytes (~768KB)
        256 * 256 * 3 * 2      // 256x256 FLOAT16: 393,216 bytes (~384KB)
    };
    
    // Thread-safe buffer pools organized by size
    private final Map<Integer, Queue<ByteBuffer>> pools;
    private final AtomicLong totalAllocated;
    private final AtomicLong totalAcquired;
    private final AtomicLong totalReleased;
    private final long maxPoolSizeBytes;
    private final int maxBuffersPerSize;
    private final boolean preallocationEnabled;
    
    // Pool statistics
    private final PoolMetrics metrics;
    
    /**
     * Creates a new DirectBufferPool with specified configuration.
     * 
     * @param maxPoolSizeMB Maximum pool size in megabytes
     * @param maxBuffersPerSize Maximum buffers to keep per size category
     * @param preallocationEnabled Whether to preallocate common sizes
     */
    public DirectBufferPool(long maxPoolSizeMB, int maxBuffersPerSize, boolean preallocationEnabled) {
        this.pools = new ConcurrentHashMap<>();
        this.totalAllocated = new AtomicLong(0);
        this.totalAcquired = new AtomicLong(0);
        this.totalReleased = new AtomicLong(0);
        this.maxPoolSizeBytes = maxPoolSizeMB * 1024 * 1024;
        this.maxBuffersPerSize = maxBuffersPerSize;
        this.preallocationEnabled = preallocationEnabled;
        this.metrics = new PoolMetrics();
        
        Log.d(TAG, String.format("DirectBufferPool created: maxSize=%dMB, maxBuffersPerSize=%d, preallocation=%s",
                maxPoolSizeMB, maxBuffersPerSize, preallocationEnabled));
        
        if (preallocationEnabled) {
            preallocateCommonSizes();
        }
    }
    
    /**
     * Preallocates buffers for common model sizes to improve performance.
     */
    private void preallocateCommonSizes() {
        Log.d(TAG, "Preallocating buffers for common sizes...");
        
        for (int size : COMMON_SIZES) {
            // Check if we have enough space for preallocation
            if (totalAllocated.get() + size * 2 > maxPoolSizeBytes) {
                Log.w(TAG, "Skipping preallocation for size " + size + " due to pool size limit");
                continue;
            }
            
            Queue<ByteBuffer> queue = new ConcurrentLinkedQueue<>();
            
            // Preallocate 2 buffers per common size
            for (int i = 0; i < 2; i++) {
                try {
                    ByteBuffer buffer = allocateAlignedBuffer(size);
                    queue.offer(buffer);
                    totalAllocated.addAndGet(size);
                    Log.v(TAG, String.format("Preallocated buffer %d/%d for size %d bytes", 
                            i + 1, 2, size));
                } catch (OutOfMemoryError e) {
                    Log.w(TAG, "Failed to preallocate buffer for size " + size + ": " + e.getMessage());
                    break;
                }
            }
            
            if (!queue.isEmpty()) {
                pools.put(size, queue);
                Log.d(TAG, String.format("Preallocated %d buffers for size %d bytes (%.1fMB)",
                        queue.size(), size, size / 1024.0 / 1024.0));
            }
        }
        
        long totalPreallocated = totalAllocated.get();
        Log.d(TAG, String.format("Preallocation complete: %.1fMB allocated across %d size categories",
                totalPreallocated / 1024.0 / 1024.0, pools.size()));
    }
    
    /**
     * Acquires a DirectByteBuffer of the exact requested size.
     * 
     * @param requestedSize Required buffer size in bytes
     * @return DirectByteBuffer ready for use, with capacity exactly matching requestedSize
     * @throws OutOfMemoryError if buffer pool is exhausted
     */
    public ByteBuffer acquire(int requestedSize) {
        if (requestedSize <= 0) {
            throw new IllegalArgumentException("Buffer size must be positive: " + requestedSize);
        }
        
        totalAcquired.incrementAndGet();
        
        // Try to get buffer from existing pool with exact size
        Queue<ByteBuffer> pool = pools.get(requestedSize);
        ByteBuffer buffer = null;
        
        if (pool != null) {
            buffer = pool.poll();
            if (buffer != null) {
                metrics.recordHit();
                Log.v(TAG, String.format("Reused exact buffer: size=%d", requestedSize));
            }
        }
        
        // If no exact buffer available, allocate new one
        if (buffer == null) {
            metrics.recordMiss();
            
            // Check if we can allocate within pool limits
            if (totalAllocated.get() + requestedSize <= maxPoolSizeBytes) {
                try {
                    buffer = allocateAlignedBuffer(requestedSize);
                    totalAllocated.addAndGet(requestedSize);
                    metrics.recordAllocation();
                    
                    Log.d(TAG, String.format("Allocated new exact buffer: size=%d (%.1fMB)",
                            requestedSize, requestedSize / 1024.0 / 1024.0));
                } catch (OutOfMemoryError e) {
                    Log.e(TAG, "Failed to allocate buffer of size " + requestedSize + ": " + e.getMessage());
                    throw new OutOfMemoryError("DirectBufferPool: Failed to allocate " + 
                            requestedSize + " bytes. Pool exhausted.");
                }
            } else {
                long currentSize = totalAllocated.get();
                Log.e(TAG, String.format("Pool size limit exceeded: current=%.1fMB, requested=%.1fMB, limit=%.1fMB",
                        currentSize / 1024.0 / 1024.0, 
                        requestedSize / 1024.0 / 1024.0,
                        maxPoolSizeBytes / 1024.0 / 1024.0));
                throw new OutOfMemoryError("DirectBufferPool exhausted: " + 
                        (currentSize / 1024 / 1024) + "MB used, limit " + 
                        (maxPoolSizeBytes / 1024 / 1024) + "MB");
            }
        }
        
        // Prepare buffer for use - no limit setting needed since capacity matches exactly
        buffer.clear();
        buffer.position(0);
        
        return buffer;
    }
    
    /**
     * Returns a buffer to the pool for reuse.
     * 
     * @param buffer DirectByteBuffer to return (null buffers are ignored)
     */
    public void release(ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect()) {
            return;
        }
        
        totalReleased.incrementAndGet();
        
        // Clear buffer to prevent data leakage
        buffer.clear();
        
        int capacity = buffer.capacity();
        Queue<ByteBuffer> pool = pools.computeIfAbsent(capacity, 
                k -> new ConcurrentLinkedQueue<>());
        
        // Only keep buffer if pool isn't full
        if (pool.size() < maxBuffersPerSize) {
            pool.offer(buffer);
            Log.v(TAG, String.format("Released buffer to pool: size=%d, poolCount=%d/%d",
                    capacity, pool.size(), maxBuffersPerSize));
        } else {
            // Pool is full, clean up buffer immediately
            DirectMemoryUtils.cleanDirectBuffer(buffer);
            totalAllocated.addAndGet(-capacity);
            
            Log.v(TAG, String.format("Pool full, cleaned buffer: size=%d, poolSize=%.1fMB",
                    capacity, totalAllocated.get() / 1024.0 / 1024.0));
        }
    }
    
    /**
     * Finds the optimal pool size for a requested buffer size.
     * Uses common sizes when possible, otherwise rounds up to power of 2.
     * 
     * @param requestedSize Requested buffer size
     * @return Optimal pool size (>= requestedSize)
     */
    private int findOptimalPoolSize(int requestedSize) {
        // First, try to match common sizes
        for (int commonSize : COMMON_SIZES) {
            if (commonSize >= requestedSize) {
                return commonSize;
            }
        }
        
        // If not a common size, round up to next power of 2
        // This reduces pool fragmentation
        if (requestedSize <= 0) {
            return 64; // Minimum size
        }
        
        // Find next power of 2 that can accommodate the request
        int powerOf2 = Integer.highestOneBit(requestedSize);
        if (powerOf2 < requestedSize) {
            powerOf2 <<= 1; // Next power of 2
        }
        
        return powerOf2;
    }
    
    /**
     * Allocates a new aligned DirectByteBuffer.
     * Uses DirectMemoryUtils for optimal memory alignment.
     * 
     * @param size Buffer size in bytes
     * @return Aligned DirectByteBuffer
     */
    private ByteBuffer allocateAlignedBuffer(int size) {
        return DirectMemoryUtils.allocateAlignedDirectBuffer(size);
    }
    
    /**
     * Clears all buffers from the pool and releases memory.
     */
    public void clear() {
        Log.d(TAG, "Clearing buffer pool...");
        
        int buffersCleared = 0;
        long memoryFreed = 0;
        
        for (Map.Entry<Integer, Queue<ByteBuffer>> entry : pools.entrySet()) {
            Queue<ByteBuffer> pool = entry.getValue();
            ByteBuffer buffer;
            
            while ((buffer = pool.poll()) != null) {
                int capacity = buffer.capacity();
                DirectMemoryUtils.cleanDirectBuffer(buffer);
                memoryFreed += capacity;
                buffersCleared++;
            }
        }
        
        pools.clear();
        totalAllocated.set(0);
        
        Log.d(TAG, String.format("Pool cleared: %d buffers freed, %.1fMB memory released",
                buffersCleared, memoryFreed / 1024.0 / 1024.0));
    }
    
    /**
     * Trims the pool by removing excess buffers, keeping only one per size.
     * Called during memory pressure.
     */
    public void trimToMinimum() {
        Log.d(TAG, "Trimming buffer pool to minimum size...");
        
        int buffersRemoved = 0;
        long memoryFreed = 0;
        
        for (Map.Entry<Integer, Queue<ByteBuffer>> entry : pools.entrySet()) {
            Queue<ByteBuffer> pool = entry.getValue();
            
            // Keep only one buffer per size
            while (pool.size() > 1) {
                ByteBuffer buffer = pool.poll();
                if (buffer != null) {
                    int capacity = buffer.capacity();
                    DirectMemoryUtils.cleanDirectBuffer(buffer);
                    totalAllocated.addAndGet(-capacity);
                    memoryFreed += capacity;
                    buffersRemoved++;
                }
            }
        }
        
        Log.d(TAG, String.format("Pool trimmed: %d buffers removed, %.1fMB memory freed, %.1fMB remaining",
                buffersRemoved, memoryFreed / 1024.0 / 1024.0, totalAllocated.get() / 1024.0 / 1024.0));
    }
    
    // Statistics and monitoring methods
    
    /**
     * @return Total memory allocated by this pool in bytes
     */
    public long getTotalAllocatedBytes() {
        return totalAllocated.get();
    }
    
    /**
     * @return Total number of acquire() calls
     */
    public long getTotalAcquired() {
        return totalAcquired.get();
    }
    
    /**
     * @return Total number of release() calls
     */
    public long getTotalReleased() {
        return totalReleased.get();
    }
    
    /**
     * @return Pool hit rate as percentage (0.0 - 1.0)
     */
    public double getHitRate() {
        return metrics.getHitRate();
    }
    
    /**
     * @return Number of new allocations performed
     */
    public long getAllocationCount() {
        return metrics.getAllocations();
    }
    
    /**
     * @return Map of pool sizes to buffer counts
     */
    public Map<Integer, Integer> getPoolStats() {
        Map<Integer, Integer> stats = new HashMap<>();
        for (Map.Entry<Integer, Queue<ByteBuffer>> entry : pools.entrySet()) {
            stats.put(entry.getKey(), entry.getValue().size());
        }
        return stats;
    }
    
    /**
     * Logs comprehensive pool statistics.
     */
    public void logPoolStats() {
        long totalMB = totalAllocated.get() / 1024 / 1024;
        double hitRate = getHitRate() * 100;
        long acquired = totalAcquired.get();
        long released = totalReleased.get();
        long allocations = getAllocationCount();
        
        Log.d(TAG, String.format("Pool Stats - Memory: %dMB, Hit Rate: %.1f%%, " +
                "Acquired: %d, Released: %d, Allocations: %d, Active: %d",
                totalMB, hitRate, acquired, released, allocations, acquired - released));
        
        // Log detailed pool breakdown
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            for (Map.Entry<Integer, Integer> entry : getPoolStats().entrySet()) {
                int sizeBytes = entry.getKey();
                int count = entry.getValue();
                if (count > 0) {
                    Log.d(TAG, String.format("  Size: %.1fMB x %d buffers",
                            sizeBytes / 1024.0 / 1024.0, count));
                }
            }
        }
    }
}