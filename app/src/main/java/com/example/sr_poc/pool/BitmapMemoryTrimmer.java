package com.example.sr_poc.pool;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Advanced memory trimmer for bitmap pools with adaptive management strategies.
 * 
 * Implements sophisticated memory pressure response, LRU eviction, pool size
 * auto-tuning, and preallocation strategies based on usage patterns.
 */
public class BitmapMemoryTrimmer implements ComponentCallbacks2 {
    
    private static final String TAG = "BitmapMemoryTrimmer";
    
    // Memory pressure levels and corresponding actions
    private static final float TRIM_LIGHT = 0.25f;      // Trim 25%
    private static final float TRIM_MODERATE = 0.50f;   // Trim 50%
    private static final float TRIM_HEAVY = 0.75f;      // Trim 75%
    private static final float TRIM_COMPLETE = 1.00f;   // Clear all
    
    // Auto-tuning parameters
    private static final long TUNE_INTERVAL_MS = 60000; // Auto-tune every minute
    private static final float GROWTH_RATE = 1.25f;     // Grow pool by 25%
    private static final float SHRINK_RATE = 0.75f;     // Shrink pool by 25%
    private static final float TARGET_HIT_RATE = 0.80f; // Target 80% hit rate
    
    private final Context context;
    private final BitmapPoolManager poolManager;
    private final BitmapPool bitmapPool;
    
    // Preallocation configuration
    private final List<PreallocSize> preallocSizes;
    private boolean preallocationEnabled = true;
    
    // LRU tracking for eviction
    private final Map<String, Long> lastAccessTimes;
    private final AtomicLong accessCounter;
    
    // Auto-tuning state
    private HandlerThread tunerThread;
    private Handler tunerHandler;
    private boolean autoTuningEnabled = false;
    private long lastTuneTime = 0;
    private float lastHitRate = 0;
    
    // Weak references for fallback
    private final Map<String, List<WeakReference<Bitmap>>> weakCache;
    private boolean useWeakReferences = false;
    
    // Statistics
    private int trimCount = 0;
    private int preallocCount = 0;
    private int evictionCount = 0;
    
    /**
     * Represents a preallocation size configuration.
     */
    public static class PreallocSize {
        public final int width;
        public final int height;
        public final Bitmap.Config config;
        public final int count;
        
        public PreallocSize(int width, int height, Bitmap.Config config, int count) {
            this.width = width;
            this.height = height;
            this.config = config;
            this.count = count;
        }
    }
    
    /**
     * Creates a new BitmapMemoryTrimmer.
     * 
     * @param context Application context
     */
    public BitmapMemoryTrimmer(Context context) {
        this.context = context.getApplicationContext();
        this.poolManager = BitmapPoolManager.getInstance(context);
        this.bitmapPool = getBitmapPoolInstance();
        
        this.lastAccessTimes = new ConcurrentHashMap<>();
        this.accessCounter = new AtomicLong(0);
        this.weakCache = new ConcurrentHashMap<>();
        this.preallocSizes = new ArrayList<>();
        
        // Setup default preallocation sizes
        setupDefaultPreallocSizes();
        
        // Register for memory callbacks
        context.registerComponentCallbacks(this);
        
        Log.d(TAG, "BitmapMemoryTrimmer initialized");
    }
    
    /**
     * Gets the BitmapPool instance via reflection.
     */
    private BitmapPool getBitmapPoolInstance() {
        try {
            java.lang.reflect.Field poolField = poolManager.getClass().getDeclaredField("bitmapPool");
            poolField.setAccessible(true);
            return (BitmapPool) poolField.get(poolManager);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get BitmapPool instance", e);
            return null;
        }
    }
    
    /**
     * Sets up default preallocation sizes based on common usage patterns.
     */
    private void setupDefaultPreallocSizes() {
        // Common sizes for SR processing
        preallocSizes.add(new PreallocSize(1280, 720, Bitmap.Config.ARGB_8888, 2));   // 720p
        preallocSizes.add(new PreallocSize(1920, 1080, Bitmap.Config.ARGB_8888, 1));  // 1080p
        preallocSizes.add(new PreallocSize(512, 512, Bitmap.Config.ARGB_8888, 4));    // Tiles
        preallocSizes.add(new PreallocSize(256, 256, Bitmap.Config.ARGB_8888, 4));    // Small tiles
    }
    
    /**
     * Preallocates bitmaps based on expected usage patterns.
     */
    public void preallocateBitmaps() {
        if (!preallocationEnabled || bitmapPool == null) {
            return;
        }
        
        Log.d(TAG, "Starting bitmap preallocation...");
        int allocated = 0;
        
        for (PreallocSize size : preallocSizes) {
            for (int i = 0; i < size.count; i++) {
                try {
                    // Create and immediately release to pool
                    Bitmap bitmap = Bitmap.createBitmap(size.width, size.height, size.config);
                    if (bitmap != null) {
                        bitmapPool.release(bitmap);
                        allocated++;
                    }
                } catch (OutOfMemoryError e) {
                    Log.w(TAG, String.format("OOM during preallocation at %dx%d", 
                                           size.width, size.height));
                    break;
                }
            }
        }
        
        preallocCount += allocated;
        Log.d(TAG, String.format("Preallocated %d bitmaps", allocated));
    }
    
    /**
     * Tracks bitmap access for LRU eviction.
     * 
     * @param key Bitmap pool key
     */
    public void trackAccess(String key) {
        lastAccessTimes.put(key, accessCounter.incrementAndGet());
    }
    
    /**
     * Implements LRU eviction when pool exceeds limits.
     * 
     * @param targetReduction Number of bitmaps to evict
     */
    public void evictLRU(int targetReduction) {
        if (bitmapPool == null || targetReduction <= 0) {
            return;
        }
        
        Log.d(TAG, String.format("Starting LRU eviction, target: %d bitmaps", targetReduction));
        
        // Get pool stats
        Map<String, Integer> poolStats = bitmapPool.getPoolStats();
        if (poolStats == null || poolStats.isEmpty()) {
            return;
        }
        
        // Find least recently used keys
        List<String> keysToEvict = new ArrayList<>();
        List<Map.Entry<String, Long>> entries = new ArrayList<>(lastAccessTimes.entrySet());
        
        // Sort by access time (oldest first)
        entries.sort((a, b) -> Long.compare(a.getValue(), b.getValue()));
        
        int evicted = 0;
        for (Map.Entry<String, Long> entry : entries) {
            if (evicted >= targetReduction) {
                break;
            }
            
            String key = entry.getKey();
            Integer count = poolStats.get(key);
            if (count != null && count > 0) {
                keysToEvict.add(key);
                evicted += count;
            }
        }
        
        // Perform eviction
        for (String key : keysToEvict) {
            evictPoolKey(key);
        }
        
        evictionCount += evicted;
        Log.d(TAG, String.format("Evicted %d bitmaps via LRU", evicted));
    }
    
    /**
     * Evicts all bitmaps for a specific pool key.
     */
    private void evictPoolKey(String key) {
        if (bitmapPool == null) {
            return;
        }
        
        try {
            // Use reflection to access pool internals
            java.lang.reflect.Field poolsField = bitmapPool.getClass().getDeclaredField("pools");
            poolsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, java.util.Queue<Bitmap>> pools = 
                (Map<String, java.util.Queue<Bitmap>>) poolsField.get(bitmapPool);
            
            if (pools != null) {
                java.util.Queue<Bitmap> queue = pools.get(key);
                if (queue != null) {
                    // Move to weak references if enabled
                    if (useWeakReferences) {
                        List<WeakReference<Bitmap>> weakList = 
                            weakCache.computeIfAbsent(key, k -> new ArrayList<>());
                        
                        Bitmap bitmap;
                        while ((bitmap = queue.poll()) != null) {
                            weakList.add(new WeakReference<>(bitmap));
                        }
                    } else {
                        // Just clear and recycle
                        Bitmap bitmap;
                        while ((bitmap = queue.poll()) != null) {
                            bitmap.recycle();
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to evict pool key: " + key, e);
        }
    }
    
    /**
     * Starts auto-tuning of pool size based on usage patterns.
     */
    public void startAutoTuning() {
        if (autoTuningEnabled) {
            return;
        }
        
        autoTuningEnabled = true;
        
        // Create tuner thread
        tunerThread = new HandlerThread("BitmapPoolTuner");
        tunerThread.start();
        tunerHandler = new Handler(tunerThread.getLooper());
        
        // Schedule periodic tuning
        tunerHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (autoTuningEnabled) {
                    performAutoTuning();
                    tunerHandler.postDelayed(this, TUNE_INTERVAL_MS);
                }
            }
        }, TUNE_INTERVAL_MS);
        
        Log.d(TAG, "Auto-tuning started");
    }
    
    /**
     * Performs auto-tuning of pool size.
     */
    private void performAutoTuning() {
        if (poolManager == null || bitmapPool == null) {
            return;
        }
        
        // Get current metrics
        BitmapPool.BitmapPoolMetrics metrics = poolManager.getMetrics();
        if (metrics == null) {
            return;
        }
        
        float currentHitRate = (float) metrics.getHitRate();
        long currentPoolSize = metrics.getCurrentPoolSize();
        
        Log.d(TAG, String.format("Auto-tuning: Hit rate %.1f%%, Pool size %.1fMB", 
                               currentHitRate * 100, currentPoolSize / (1024f * 1024f)));
        
        // Decide action based on hit rate
        if (currentHitRate < TARGET_HIT_RATE && currentHitRate < lastHitRate) {
            // Hit rate declining and below target - grow pool
            adjustPoolSize(GROWTH_RATE);
            Log.d(TAG, "Growing pool size due to low hit rate");
            
        } else if (currentHitRate > TARGET_HIT_RATE + 0.1f) {
            // Hit rate well above target - can shrink to save memory
            adjustPoolSize(SHRINK_RATE);
            Log.d(TAG, "Shrinking pool size due to high hit rate");
        }
        
        // Update state
        lastHitRate = currentHitRate;
        lastTuneTime = System.currentTimeMillis();
    }
    
    /**
     * Adjusts pool size by a given factor.
     */
    private void adjustPoolSize(float factor) {
        if (bitmapPool == null) {
            return;
        }
        
        try {
            // Get current max size
            java.lang.reflect.Field maxSizeField = bitmapPool.getClass().getDeclaredField("maxPoolSizeMB");
            maxSizeField.setAccessible(true);
            long currentMax = maxSizeField.getLong(bitmapPool);
            
            // Calculate new size
            long newMax = (long)(currentMax * factor);
            newMax = Math.max(16, Math.min(256, newMax)); // Clamp between 16-256 MB
            
            // Update max size
            maxSizeField.setLong(bitmapPool, newMax);
            
            Log.d(TAG, String.format("Adjusted pool size: %dMB -> %dMB", currentMax, newMax));
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to adjust pool size", e);
        }
    }
    
    /**
     * Stops auto-tuning.
     */
    public void stopAutoTuning() {
        autoTuningEnabled = false;
        
        if (tunerHandler != null) {
            tunerHandler.removeCallbacksAndMessages(null);
            tunerHandler = null;
        }
        
        if (tunerThread != null) {
            tunerThread.quit();
            tunerThread = null;
        }
        
        Log.d(TAG, "Auto-tuning stopped");
    }
    
    /**
     * Tries to recover bitmaps from weak references.
     * 
     * @param key Pool key
     * @param count Number of bitmaps needed
     * @return List of recovered bitmaps
     */
    public List<Bitmap> recoverFromWeakCache(String key, int count) {
        List<Bitmap> recovered = new ArrayList<>();
        
        List<WeakReference<Bitmap>> weakList = weakCache.get(key);
        if (weakList != null) {
            List<WeakReference<Bitmap>> toRemove = new ArrayList<>();
            
            for (WeakReference<Bitmap> ref : weakList) {
                if (recovered.size() >= count) {
                    break;
                }
                
                Bitmap bitmap = ref.get();
                if (bitmap != null && !bitmap.isRecycled()) {
                    recovered.add(bitmap);
                    toRemove.add(ref);
                } else {
                    toRemove.add(ref); // Remove dead references
                }
            }
            
            weakList.removeAll(toRemove);
        }
        
        if (!recovered.isEmpty()) {
            Log.d(TAG, String.format("Recovered %d bitmaps from weak cache", recovered.size()));
        }
        
        return recovered;
    }
    
    // ComponentCallbacks2 implementation
    
    @Override
    public void onTrimMemory(int level) {
        if (bitmapPool == null) {
            return;
        }
        
        trimCount++;
        Log.d(TAG, String.format("Memory trim requested, level: %d, count: %d", level, trimCount));
        
        float trimRatio = 0;
        
        switch (level) {
            case TRIM_MEMORY_UI_HIDDEN:
                // App in background, light trim
                trimRatio = TRIM_LIGHT;
                break;
                
            case TRIM_MEMORY_BACKGROUND:
            case TRIM_MEMORY_MODERATE:
                // Moderate memory pressure
                trimRatio = TRIM_MODERATE;
                break;
                
            case TRIM_MEMORY_COMPLETE:
                // Critical memory situation
                trimRatio = TRIM_COMPLETE;
                break;
                
            case TRIM_MEMORY_RUNNING_MODERATE:
            case TRIM_MEMORY_RUNNING_LOW:
                // Running but memory tight
                trimRatio = TRIM_MODERATE;
                // Also trigger LRU eviction
                evictLRU(10);
                break;
                
            case TRIM_MEMORY_RUNNING_CRITICAL:
                // Running with critical memory
                trimRatio = TRIM_HEAVY;
                // Aggressive LRU eviction
                evictLRU(20);
                break;
        }
        
        if (trimRatio > 0) {
            bitmapPool.trim(trimRatio);
            Log.d(TAG, String.format("Trimmed pool by %.0f%%", trimRatio * 100));
        }
        
        // Clear weak cache on heavy pressure
        if (trimRatio >= TRIM_HEAVY) {
            weakCache.clear();
            Log.d(TAG, "Cleared weak reference cache");
        }
    }
    
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // Handle configuration changes if needed
    }
    
    @Override
    public void onLowMemory() {
        // Equivalent to TRIM_MEMORY_COMPLETE
        onTrimMemory(TRIM_MEMORY_COMPLETE);
    }
    
    /**
     * Gets trimmer statistics.
     */
    public String getStatistics() {
        return String.format("BitmapMemoryTrimmer Stats - Trims: %d, Preallocs: %d, Evictions: %d",
                           trimCount, preallocCount, evictionCount);
    }
    
    /**
     * Enables or disables weak reference fallback.
     */
    public void setUseWeakReferences(boolean enable) {
        this.useWeakReferences = enable;
        if (!enable) {
            weakCache.clear();
        }
    }
    
    /**
     * Enables or disables preallocation.
     */
    public void setPreallocationEnabled(boolean enable) {
        this.preallocationEnabled = enable;
    }
    
    /**
     * Shuts down the trimmer and releases resources.
     */
    public void shutdown() {
        Log.d(TAG, "Shutting down BitmapMemoryTrimmer");
        
        // Stop auto-tuning
        stopAutoTuning();
        
        // Unregister callbacks
        try {
            context.unregisterComponentCallbacks(this);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister callbacks", e);
        }
        
        // Clear caches
        lastAccessTimes.clear();
        weakCache.clear();
        
        Log.d(TAG, getStatistics());
    }
}