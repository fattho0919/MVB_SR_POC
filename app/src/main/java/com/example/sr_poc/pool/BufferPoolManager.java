package com.example.sr_poc.pool;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.Map;

import com.example.sr_poc.ConfigManager;

/**
 * Global singleton manager for DirectByteBuffer pools.
 * 
 * Manages separate pools for primary inference and tiling operations,
 * handles memory pressure callbacks, and provides unified access to
 * buffer pool functionality across the application.
 */
public class BufferPoolManager implements ComponentCallbacks2 {
    
    private static final String TAG = "BufferPoolManager";
    
    // Singleton instance
    private static volatile BufferPoolManager instance;
    
    // Buffer pools
    private final DirectBufferPool primaryPool;
    private final DirectBufferPool tilePool;
    
    // Configuration
    private final ConfigManager configManager;
    private final Context context;
    
    // Statistics
    private volatile long creationTime;
    
    /**
     * Private constructor for singleton pattern.
     * 
     * @param context Application context
     */
    private BufferPoolManager(Context context) {
        this.context = context.getApplicationContext();
        this.configManager = ConfigManager.getInstance(context);
        this.creationTime = System.currentTimeMillis();
        
        // Calculate pool sizes based on configuration and device memory
        long deviceMemory = Runtime.getRuntime().maxMemory();
        int primaryPoolSizeMb = calculatePoolSize(configManager.getPrimaryPoolSizeMb(), deviceMemory);
        int tilePoolSizeMb = calculatePoolSize(configManager.getTilePoolSizeMb(), deviceMemory);
        
        Log.d(TAG, String.format("Initializing BufferPoolManager - Device Memory: %dMB, " +
                "Primary Pool: %dMB, Tile Pool: %dMB",
                deviceMemory / 1024 / 1024, primaryPoolSizeMb, tilePoolSizeMb));
        
        // Create buffer pools
        this.primaryPool = new DirectBufferPool(
                primaryPoolSizeMb,
                configManager.getMaxBuffersPerSize(),
                configManager.isPreallocationEnabled()
        );
        
        this.tilePool = new DirectBufferPool(
                tilePoolSizeMb,
                Math.max(2, configManager.getMaxBuffersPerSize() / 2), // Fewer buffers for tile pool
                false // No preallocation for tile pool to save memory
        );
        
        // Register for memory pressure callbacks
        context.registerComponentCallbacks(this);
        
        Log.d(TAG, "BufferPoolManager initialized successfully");
    }
    
    /**
     * Gets the singleton instance of BufferPoolManager.
     * Thread-safe lazy initialization.
     * 
     * @param context Application context
     * @return BufferPoolManager instance
     */
    public static BufferPoolManager getInstance(Context context) {
        if (instance == null) {
            synchronized (BufferPoolManager.class) {
                if (instance == null) {
                    instance = new BufferPoolManager(context);
                }
            }
        }
        return instance;
    }
    
    /**
     * Calculates appropriate pool size based on configuration and device constraints.
     * 
     * @param configuredSizeMb Size from configuration
     * @param deviceMemoryBytes Total device memory in bytes
     * @return Adjusted pool size in MB
     */
    private int calculatePoolSize(int configuredSizeMb, long deviceMemoryBytes) {
        // Limit pool size based on device memory
        long deviceMemoryMb = deviceMemoryBytes / 1024 / 1024;
        int maxRecommendedMb = (int) (deviceMemoryMb / 8); // Use max 1/8 of device memory
        
        int adjustedSize = Math.min(configuredSizeMb, maxRecommendedMb);
        
        if (adjustedSize != configuredSizeMb) {
            Log.w(TAG, String.format("Pool size adjusted from %dMB to %dMB due to device memory constraints",
                    configuredSizeMb, adjustedSize));
        }
        
        return adjustedSize;
    }
    
    // Primary pool methods - for main inference operations
    
    /**
     * Acquires a buffer from the primary pool for inference operations.
     * 
     * @param size Required buffer size in bytes
     * @return DirectByteBuffer ready for use
     * @throws OutOfMemoryError if pool is exhausted
     */
    public ByteBuffer acquirePrimaryBuffer(int size) {
        if (!configManager.useBufferPool()) {
            // If buffer pool is disabled, fall back to direct allocation
            Log.v(TAG, "Buffer pool disabled, using direct allocation");
            return ByteBuffer.allocateDirect(size);
        }
        
        try {
            ByteBuffer buffer = primaryPool.acquire(size);
            Log.v(TAG, String.format("Acquired primary buffer: %d bytes", size));
            return buffer;
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Primary pool exhausted, falling back to direct allocation", e);
            // Fallback to direct allocation if pool fails
            return ByteBuffer.allocateDirect(size);
        }
    }
    
    /**
     * Returns a buffer to the primary pool.
     * 
     * @param buffer Buffer to release (null buffers ignored)
     */
    public void releasePrimaryBuffer(ByteBuffer buffer) {
        if (buffer == null) {
            return;
        }
        
        if (!configManager.useBufferPool()) {
            // If buffer pool is disabled, nothing to do (GC will handle it)
            return;
        }
        
        primaryPool.release(buffer);
        Log.v(TAG, "Released primary buffer");
    }
    
    // Tile pool methods - for tiling operations
    
    /**
     * Acquires a buffer from the tile pool for tiling operations.
     * 
     * @param size Required buffer size in bytes
     * @return DirectByteBuffer ready for use
     * @throws OutOfMemoryError if pool is exhausted
     */
    public ByteBuffer acquireTileBuffer(int size) {
        if (!configManager.useBufferPool()) {
            // If buffer pool is disabled, fall back to direct allocation
            return ByteBuffer.allocateDirect(size);
        }
        
        try {
            ByteBuffer buffer = tilePool.acquire(size);
            Log.v(TAG, String.format("Acquired tile buffer: %d bytes", size));
            return buffer;
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Tile pool exhausted, falling back to direct allocation", e);
            // Fallback to direct allocation if pool fails
            return ByteBuffer.allocateDirect(size);
        }
    }
    
    /**
     * Returns a buffer to the tile pool.
     * 
     * @param buffer Buffer to release (null buffers ignored)
     */
    public void releaseTileBuffer(ByteBuffer buffer) {
        if (buffer == null) {
            return;
        }
        
        if (!configManager.useBufferPool()) {
            // If buffer pool is disabled, nothing to do
            return;
        }
        
        tilePool.release(buffer);
        Log.v(TAG, "Released tile buffer");
    }
    
    // Statistics and monitoring
    
    /**
     * @return Total memory allocated by all pools in bytes
     */
    public long getTotalAllocatedBytes() {
        if (!configManager.useBufferPool()) {
            return 0;
        }
        return primaryPool.getTotalAllocatedBytes() + tilePool.getTotalAllocatedBytes();
    }
    
    /**
     * @return Combined hit rate across all pools
     */
    public double getOverallHitRate() {
        if (!configManager.useBufferPool()) {
            return 0.0;
        }
        
        long primaryHits = primaryPool.getTotalAcquired() - primaryPool.getAllocationCount();
        long tileHits = tilePool.getTotalAcquired() - tilePool.getAllocationCount();
        long totalRequests = primaryPool.getTotalAcquired() + tilePool.getTotalAcquired();
        
        if (totalRequests == 0) {
            return 0.0;
        }
        
        return (double) (primaryHits + tileHits) / totalRequests;
    }
    
    /**
     * @return Statistics for primary pool
     */
    public Map<Integer, Integer> getPrimaryPoolStats() {
        return primaryPool.getPoolStats();
    }
    
    /**
     * @return Statistics for tile pool
     */
    public Map<Integer, Integer> getTilePoolStats() {
        return tilePool.getPoolStats();
    }
    
    /**
     * Logs comprehensive statistics for all pools.
     */
    public void logAllStats() {
        if (!configManager.useBufferPool()) {
            Log.d(TAG, "Buffer pool is disabled");
            return;
        }
        
        long uptimeMinutes = (System.currentTimeMillis() - creationTime) / 1000 / 60;
        long totalMemoryMB = getTotalAllocatedBytes() / 1024 / 1024;
        double overallHitRate = getOverallHitRate() * 100;
        
        Log.d(TAG, String.format("BufferPoolManager Stats - Uptime: %d min, " +
                "Total Memory: %dMB, Overall Hit Rate: %.1f%%",
                uptimeMinutes, totalMemoryMB, overallHitRate));
        
        Log.d(TAG, "=== Primary Pool ===");
        primaryPool.logPoolStats();
        
        Log.d(TAG, "=== Tile Pool ===");
        tilePool.logPoolStats();
    }
    
    // Memory pressure handling (ComponentCallbacks2 implementation)
    
    @Override
    public void onTrimMemory(int level) {
        if (!configManager.useBufferPool()) {
            return;
        }
        
        Log.d(TAG, "Memory pressure detected, level: " + level);
        
        switch (level) {
            case TRIM_MEMORY_UI_HIDDEN:
                // App is in background, light trimming
                Log.d(TAG, "App backgrounded, performing light pool trimming");
                // Could trim tile pool since it's less critical
                tilePool.trimToMinimum();
                break;
                
            case TRIM_MEMORY_BACKGROUND:
            case TRIM_MEMORY_MODERATE:
                // More aggressive trimming
                Log.d(TAG, "Moderate memory pressure, trimming both pools");
                primaryPool.trimToMinimum();
                tilePool.trimToMinimum();
                break;
                
            case TRIM_MEMORY_COMPLETE:
                // Critical memory situation - clear everything
                Log.w(TAG, "Critical memory pressure, clearing all pools");
                primaryPool.clear();
                tilePool.clear();
                break;
                
            case TRIM_MEMORY_RUNNING_MODERATE:
            case TRIM_MEMORY_RUNNING_LOW:
                // App is still running but memory is tight
                Log.d(TAG, "Running with low memory, trimming tile pool");
                tilePool.trimToMinimum();
                break;
                
            case TRIM_MEMORY_RUNNING_CRITICAL:
                // Very critical - trim aggressively
                Log.w(TAG, "Running with critical memory, aggressive trimming");
                primaryPool.trimToMinimum();
                tilePool.clear();
                break;
        }
        
        logAllStats(); // Log status after trimming
    }
    
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // Configuration changes don't affect buffer pools directly
    }
    
    @Override
    public void onLowMemory() {
        // This is equivalent to TRIM_MEMORY_COMPLETE
        onTrimMemory(TRIM_MEMORY_COMPLETE);
    }
    
    // Cleanup methods
    
    /**
     * Clears all pools and releases all memory.
     * Should be called when the application is shutting down.
     */
    public void shutdown() {
        Log.d(TAG, "Shutting down BufferPoolManager");
        
        if (configManager.useBufferPool()) {
            primaryPool.clear();
            tilePool.clear();
        }
        
        // Unregister callbacks
        try {
            context.unregisterComponentCallbacks(this);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister component callbacks", e);
        }
        
        Log.d(TAG, "BufferPoolManager shutdown complete");
    }
    
    /**
     * Forces garbage collection of any unused pool memory.
     * This is a potentially expensive operation.
     */
    public void forceGC() {
        Log.d(TAG, "Forcing garbage collection");
        Runtime.getRuntime().gc();
        
        // Log memory status after GC
        if (configManager.isEnableMemoryLogging()) {
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            Log.d(TAG, String.format("Post-GC Memory: %dMB used, %dMB free, %dMB max",
                    usedMemory / 1024 / 1024,
                    runtime.freeMemory() / 1024 / 1024,
                    runtime.maxMemory() / 1024 / 1024));
        }
    }
}