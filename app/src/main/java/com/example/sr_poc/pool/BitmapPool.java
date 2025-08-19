package com.example.sr_poc.pool;

import android.graphics.Bitmap;
import android.util.Log;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe Bitmap object pool for efficient memory management.
 * 
 * Provides reusable Bitmap allocation to reduce allocation overhead
 * and memory fragmentation. Supports different bitmap configurations
 * and sizes with automatic pooling and reuse.
 */
public class BitmapPool {
    
    private static final String TAG = "BitmapPool";
    
    // Pool storage organized by size key
    private final Map<String, Queue<Bitmap>> pools;
    
    // Configuration
    private final int maxBitmapsPerSize;
    private final long maxPoolSizeBytes;
    
    // Statistics
    private final BitmapPoolMetrics metrics;
    
    /**
     * Creates a new BitmapPool with specified configuration.
     * 
     * @param maxPoolSizeMB Maximum pool size in megabytes
     * @param maxBitmapsPerSize Maximum bitmaps to keep per size category
     */
    public BitmapPool(long maxPoolSizeMB, int maxBitmapsPerSize) {
        this.pools = new ConcurrentHashMap<>();
        this.maxBitmapsPerSize = maxBitmapsPerSize;
        this.maxPoolSizeBytes = maxPoolSizeMB * 1024 * 1024;
        this.metrics = new BitmapPoolMetrics();
        
        Log.d(TAG, String.format("BitmapPool created: maxSize=%dMB, maxBitmapsPerSize=%d",
                maxPoolSizeMB, maxBitmapsPerSize));
    }
    
    /**
     * Acquires a Bitmap from the pool or creates a new one if necessary.
     * 
     * @param width Required bitmap width
     * @param height Required bitmap height
     * @param config Bitmap configuration
     * @return Bitmap ready for use
     */
    public Bitmap acquire(int width, int height, Bitmap.Config config) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid dimensions: " + width + "x" + height);
        }
        
        String key = generateKey(width, height, config);
        Queue<Bitmap> pool = pools.get(key);
        
        Bitmap bitmap = null;
        if (pool != null) {
            bitmap = pool.poll();
        }
        
        if (bitmap != null && !bitmap.isRecycled()) {
            // Reused from pool
            metrics.recordHit();
            Log.v(TAG, String.format("Reused bitmap from pool: %s", key));
        } else {
            // Need to create new bitmap
            metrics.recordMiss();
            
            // Check if we can allocate within limits
            long bitmapSize = calculateBitmapSize(width, height, config);
            if (metrics.getCurrentPoolSize() + bitmapSize <= maxPoolSizeBytes) {
                try {
                    bitmap = Bitmap.createBitmap(width, height, config);
                    metrics.recordAllocation(bitmapSize);
                    Log.v(TAG, String.format("Created new bitmap: %s (%.1fMB)", 
                            key, bitmapSize / 1024.0 / 1024.0));
                } catch (OutOfMemoryError e) {
                    Log.e(TAG, "Failed to allocate bitmap: " + key, e);
                    throw e;
                }
            } else {
                Log.w(TAG, String.format("Pool size limit exceeded for %s, creating without tracking", key));
                bitmap = Bitmap.createBitmap(width, height, config);
            }
        }
        
        return bitmap;
    }
    
    /**
     * Returns a bitmap to the pool for reuse.
     * 
     * @param bitmap Bitmap to release (null or recycled bitmaps are ignored)
     */
    public void release(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        
        if (!bitmap.isMutable()) {
            Log.w(TAG, "Cannot pool immutable bitmap");
            return;
        }
        
        String key = generateKey(bitmap.getWidth(), bitmap.getHeight(), bitmap.getConfig());
        Queue<Bitmap> pool = pools.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());
        
        // Only keep bitmap if pool isn't full
        if (pool.size() < maxBitmapsPerSize) {
            pool.offer(bitmap);
            metrics.recordRelease();
            Log.v(TAG, String.format("Released bitmap to pool: %s, poolCount=%d/%d",
                    key, pool.size(), maxBitmapsPerSize));
        } else {
            // Pool is full, let GC handle it
            bitmap.recycle();
            long bitmapSize = calculateBitmapSize(bitmap.getWidth(), bitmap.getHeight(), bitmap.getConfig());
            metrics.recordEviction(bitmapSize);
            Log.v(TAG, String.format("Pool full, recycled bitmap: %s", key));
        }
    }
    
    /**
     * Clears all bitmaps from the pool and releases memory.
     */
    public void clear() {
        Log.d(TAG, "Clearing bitmap pool...");
        
        int bitmapsCleared = 0;
        long memoryFreed = 0;
        
        for (Map.Entry<String, Queue<Bitmap>> entry : pools.entrySet()) {
            Queue<Bitmap> pool = entry.getValue();
            Bitmap bitmap;
            
            while ((bitmap = pool.poll()) != null) {
                if (!bitmap.isRecycled()) {
                    long size = calculateBitmapSize(bitmap.getWidth(), 
                            bitmap.getHeight(), bitmap.getConfig());
                    bitmap.recycle();
                    memoryFreed += size;
                    bitmapsCleared++;
                }
            }
        }
        
        pools.clear();
        metrics.reset();
        
        Log.d(TAG, String.format("Pool cleared: %d bitmaps freed, %.1fMB memory released",
                bitmapsCleared, memoryFreed / 1024.0 / 1024.0));
    }
    
    /**
     * Trims the pool by removing excess bitmaps.
     * Called during memory pressure.
     * 
     * @param level Trim level (0.0 = no trim, 1.0 = clear all)
     */
    public void trim(float level) {
        if (level <= 0) return;
        if (level >= 1) {
            clear();
            return;
        }
        
        Log.d(TAG, String.format("Trimming bitmap pool by %.0f%%", level * 100));
        
        int bitmapsRemoved = 0;
        long memoryFreed = 0;
        
        for (Map.Entry<String, Queue<Bitmap>> entry : pools.entrySet()) {
            Queue<Bitmap> pool = entry.getValue();
            int toRemove = (int) (pool.size() * level);
            
            for (int i = 0; i < toRemove; i++) {
                Bitmap bitmap = pool.poll();
                if (bitmap != null && !bitmap.isRecycled()) {
                    long size = calculateBitmapSize(bitmap.getWidth(), 
                            bitmap.getHeight(), bitmap.getConfig());
                    bitmap.recycle();
                    memoryFreed += size;
                    bitmapsRemoved++;
                }
            }
        }
        
        metrics.recordTrim(memoryFreed);
        Log.d(TAG, String.format("Pool trimmed: %d bitmaps removed, %.1fMB freed",
                bitmapsRemoved, memoryFreed / 1024.0 / 1024.0));
    }
    
    /**
     * Generates a unique key for a bitmap configuration.
     */
    private String generateKey(int width, int height, Bitmap.Config config) {
        return width + "_" + height + "_" + (config != null ? config.name() : "ARGB_8888");
    }
    
    /**
     * Calculates the memory size of a bitmap in bytes.
     */
    private long calculateBitmapSize(int width, int height, Bitmap.Config config) {
        int bytesPerPixel = 4; // Default to ARGB_8888
        
        if (config != null) {
            switch (config) {
                case ALPHA_8:
                    bytesPerPixel = 1;
                    break;
                case RGB_565:
                case ARGB_4444:
                    bytesPerPixel = 2;
                    break;
                case ARGB_8888:
                case RGBA_F16:
                    bytesPerPixel = 4;
                    break;
            }
        }
        
        return (long) width * height * bytesPerPixel;
    }
    
    /**
     * Returns current pool statistics.
     */
    public BitmapPoolMetrics getMetrics() {
        return metrics;
    }
    
    /**
     * Returns detailed pool status for debugging.
     */
    public Map<String, Integer> getPoolStats() {
        Map<String, Integer> stats = new ConcurrentHashMap<>();
        for (Map.Entry<String, Queue<Bitmap>> entry : pools.entrySet()) {
            stats.put(entry.getKey(), entry.getValue().size());
        }
        return stats;
    }
    
    /**
     * Inner class for tracking pool metrics.
     */
    public static class BitmapPoolMetrics {
        private final AtomicLong hits = new AtomicLong(0);
        private final AtomicLong misses = new AtomicLong(0);
        private final AtomicLong allocations = new AtomicLong(0);
        private final AtomicLong releases = new AtomicLong(0);
        private final AtomicLong evictions = new AtomicLong(0);
        private final AtomicLong currentPoolSize = new AtomicLong(0);
        private final AtomicLong totalAllocatedBytes = new AtomicLong(0);
        private final AtomicLong totalEvictedBytes = new AtomicLong(0);
        
        void recordHit() {
            hits.incrementAndGet();
        }
        
        void recordMiss() {
            misses.incrementAndGet();
        }
        
        void recordAllocation(long bytes) {
            allocations.incrementAndGet();
            currentPoolSize.addAndGet(bytes);
            totalAllocatedBytes.addAndGet(bytes);
        }
        
        void recordRelease() {
            releases.incrementAndGet();
        }
        
        void recordEviction(long bytes) {
            evictions.incrementAndGet();
            currentPoolSize.addAndGet(-bytes);
            totalEvictedBytes.addAndGet(bytes);
        }
        
        void recordTrim(long bytes) {
            currentPoolSize.addAndGet(-bytes);
        }
        
        void reset() {
            hits.set(0);
            misses.set(0);
            allocations.set(0);
            releases.set(0);
            evictions.set(0);
            currentPoolSize.set(0);
            totalAllocatedBytes.set(0);
            totalEvictedBytes.set(0);
        }
        
        public long getHits() { return hits.get(); }
        public long getMisses() { return misses.get(); }
        public long getAllocations() { return allocations.get(); }
        public long getReleases() { return releases.get(); }
        public long getEvictions() { return evictions.get(); }
        public long getCurrentPoolSize() { return currentPoolSize.get(); }
        public long getTotalAllocatedBytes() { return totalAllocatedBytes.get(); }
        public long getTotalEvictedBytes() { return totalEvictedBytes.get(); }
        
        public double getHitRate() {
            long total = hits.get() + misses.get();
            return total > 0 ? (double) hits.get() / total : 0.0;
        }
        
        public void logStats(String tag) {
            Log.d(tag, String.format("BitmapPool Stats - Hit Rate: %.1f%%, Hits: %d, Misses: %d, " +
                            "Allocations: %d, Releases: %d, Evictions: %d, Pool Size: %.1fMB",
                    getHitRate() * 100, hits.get(), misses.get(),
                    allocations.get(), releases.get(), evictions.get(),
                    currentPoolSize.get() / 1024.0 / 1024.0));
        }
    }
}