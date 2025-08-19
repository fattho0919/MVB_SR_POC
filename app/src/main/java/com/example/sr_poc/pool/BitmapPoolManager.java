package com.example.sr_poc.pool;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.util.Log;

import com.example.sr_poc.ConfigManager;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Global singleton manager for Bitmap pools.
 * 
 * Manages bitmap pooling lifecycle, handles memory pressure callbacks,
 * and provides unified access to bitmap pool functionality across the application.
 */
public class BitmapPoolManager implements ComponentCallbacks2 {
    
    private static final String TAG = "BitmapPoolManager";
    
    // Singleton instance
    private static volatile BitmapPoolManager instance;
    
    // Bitmap pool
    private BitmapPool bitmapPool;
    
    // Configuration
    private final ConfigManager configManager;
    private final Context context;
    
    // Pool configuration
    private boolean enabled;
    private long maxPoolSizeMB;
    private int maxBitmapsPerSize;
    private boolean trimOnMemoryPressure;
    private List<String> preallocSizes;
    
    // Statistics
    private volatile long creationTime;
    private volatile int trimCount = 0;
    
    /**
     * Private constructor for singleton pattern.
     * 
     * @param context Application context
     */
    private BitmapPoolManager(Context context) {
        this.context = context.getApplicationContext();
        this.configManager = ConfigManager.getInstance(context);
        this.creationTime = System.currentTimeMillis();
        
        // Load configuration
        loadConfiguration();
        
        // Initialize pool if enabled
        if (enabled) {
            initializePool();
            
            // Register for memory pressure callbacks
            context.registerComponentCallbacks(this);
            
            Log.d(TAG, "BitmapPoolManager initialized successfully");
        } else {
            Log.d(TAG, "BitmapPoolManager disabled by configuration");
        }
    }
    
    /**
     * Gets the singleton instance of BitmapPoolManager.
     * Thread-safe lazy initialization.
     * 
     * @param context Application context
     * @return BitmapPoolManager instance
     */
    public static BitmapPoolManager getInstance(Context context) {
        if (instance == null) {
            synchronized (BitmapPoolManager.class) {
                if (instance == null) {
                    instance = new BitmapPoolManager(context);
                }
            }
        }
        return instance;
    }
    
    /**
     * Loads configuration from ConfigManager.
     */
    private void loadConfiguration() {
        try {
            // Check if bitmap_pool section exists
            if (configManager.getConfig().has("bitmap_pool")) {
                var bitmapConfig = configManager.getConfig().getJSONObject("bitmap_pool");
                
                enabled = bitmapConfig.optBoolean("enabled", false);
                maxPoolSizeMB = bitmapConfig.optLong("max_pool_size_mb", 64);
                maxBitmapsPerSize = bitmapConfig.optInt("max_bitmaps_per_size", 3);
                trimOnMemoryPressure = bitmapConfig.optBoolean("trim_on_memory_pressure", true);
                
                // Load preallocation sizes
                preallocSizes = new ArrayList<>();
                JSONArray sizes = bitmapConfig.optJSONArray("prealloc_sizes");
                if (sizes != null) {
                    for (int i = 0; i < sizes.length(); i++) {
                        preallocSizes.add(sizes.getString(i));
                    }
                }
                
                Log.d(TAG, String.format("Bitmap pool config loaded: enabled=%s, maxSize=%dMB, maxPerSize=%d",
                        enabled, maxPoolSizeMB, maxBitmapsPerSize));
            } else {
                // Default configuration
                enabled = false;
                maxPoolSizeMB = 64;
                maxBitmapsPerSize = 3;
                trimOnMemoryPressure = true;
                preallocSizes = new ArrayList<>();
                
                Log.w(TAG, "No bitmap_pool configuration found, using defaults (disabled)");
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error loading bitmap pool configuration", e);
            enabled = false;
        }
    }
    
    /**
     * Initializes the bitmap pool.
     */
    private void initializePool() {
        // Calculate appropriate pool size based on device memory
        long deviceMemory = Runtime.getRuntime().maxMemory();
        long deviceMemoryMB = deviceMemory / (1024 * 1024);
        
        // Limit pool size to 1/8 of device memory
        long adjustedPoolSize = Math.min(maxPoolSizeMB, deviceMemoryMB / 8);
        
        if (adjustedPoolSize != maxPoolSizeMB) {
            Log.w(TAG, String.format("Adjusted pool size from %dMB to %dMB based on device memory (%dMB)",
                    maxPoolSizeMB, adjustedPoolSize, deviceMemoryMB));
        }
        
        // Create the pool
        bitmapPool = new BitmapPool(adjustedPoolSize, maxBitmapsPerSize);
        
        // Preallocate common sizes if configured
        if (!preallocSizes.isEmpty()) {
            preallocateCommonSizes();
        }
    }
    
    /**
     * Preallocates bitmaps for common sizes.
     */
    private void preallocateCommonSizes() {
        Log.d(TAG, "Preallocating common bitmap sizes...");
        
        for (String sizeStr : preallocSizes) {
            try {
                String[] parts = sizeStr.split("x");
                if (parts.length == 2) {
                    int width = Integer.parseInt(parts[0]);
                    int height = Integer.parseInt(parts[1]);
                    
                    // Preallocate one bitmap of this size
                    Bitmap bitmap = bitmapPool.acquire(width, height, Bitmap.Config.ARGB_8888);
                    if (bitmap != null) {
                        bitmapPool.release(bitmap);
                        Log.d(TAG, String.format("Preallocated bitmap: %dx%d", width, height));
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to preallocate size: " + sizeStr, e);
            }
        }
    }
    
    /**
     * Acquires a bitmap from the pool.
     * 
     * @param width Required width
     * @param height Required height
     * @param config Bitmap configuration
     * @return Bitmap instance
     */
    public Bitmap acquireBitmap(int width, int height, Bitmap.Config config) {
        if (!enabled || bitmapPool == null) {
            // Pool disabled, create directly
            return Bitmap.createBitmap(width, height, config);
        }
        
        try {
            return bitmapPool.acquire(width, height, config);
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "OOM acquiring bitmap, falling back to direct allocation", e);
            // Try direct allocation as last resort
            return Bitmap.createBitmap(width, height, config);
        }
    }
    
    /**
     * Acquires a bitmap with default ARGB_8888 config.
     */
    public Bitmap acquireBitmap(int width, int height) {
        return acquireBitmap(width, height, Bitmap.Config.ARGB_8888);
    }
    
    /**
     * Returns a bitmap to the pool.
     * 
     * @param bitmap Bitmap to release
     */
    public void releaseBitmap(Bitmap bitmap) {
        if (enabled && bitmapPool != null && bitmap != null) {
            bitmapPool.release(bitmap);
        }
    }
    
    /**
     * Gets current pool statistics.
     */
    public BitmapPool.BitmapPoolMetrics getMetrics() {
        if (bitmapPool != null) {
            return bitmapPool.getMetrics();
        }
        return null;
    }
    
    /**
     * Gets detailed pool status.
     */
    public Map<String, Integer> getPoolStats() {
        if (bitmapPool != null) {
            return bitmapPool.getPoolStats();
        }
        return null;
    }
    
    /**
     * Logs comprehensive statistics.
     */
    public void logStats() {
        if (!enabled) {
            Log.d(TAG, "Bitmap pool is disabled");
            return;
        }
        
        if (bitmapPool == null) {
            Log.d(TAG, "Bitmap pool not initialized");
            return;
        }
        
        long uptimeMinutes = (System.currentTimeMillis() - creationTime) / 1000 / 60;
        
        Log.d(TAG, String.format("BitmapPoolManager Stats - Uptime: %d min, Trim Count: %d",
                uptimeMinutes, trimCount));
        
        BitmapPool.BitmapPoolMetrics metrics = bitmapPool.getMetrics();
        if (metrics != null) {
            metrics.logStats(TAG);
        }
        
        // Log pool distribution
        Map<String, Integer> poolStats = bitmapPool.getPoolStats();
        if (poolStats != null && !poolStats.isEmpty()) {
            Log.d(TAG, "Pool distribution:");
            for (Map.Entry<String, Integer> entry : poolStats.entrySet()) {
                Log.d(TAG, String.format("  %s: %d bitmaps", entry.getKey(), entry.getValue()));
            }
        }
    }
    
    /**
     * Checks if bitmap pooling is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Clears all bitmaps from the pool.
     */
    public void clearPool() {
        if (bitmapPool != null) {
            bitmapPool.clear();
            Log.d(TAG, "Bitmap pool cleared manually");
        }
    }
    
    // ComponentCallbacks2 implementation
    
    @Override
    public void onTrimMemory(int level) {
        if (!enabled || !trimOnMemoryPressure || bitmapPool == null) {
            return;
        }
        
        Log.d(TAG, "Memory pressure detected, level: " + level);
        trimCount++;
        
        switch (level) {
            case TRIM_MEMORY_UI_HIDDEN:
                // App is in background, light trimming
                Log.d(TAG, "App backgrounded, light pool trimming");
                bitmapPool.trim(0.25f);
                break;
                
            case TRIM_MEMORY_BACKGROUND:
            case TRIM_MEMORY_MODERATE:
                // More aggressive trimming
                Log.d(TAG, "Moderate memory pressure, trimming 50%");
                bitmapPool.trim(0.5f);
                break;
                
            case TRIM_MEMORY_COMPLETE:
                // Critical memory situation
                Log.w(TAG, "Critical memory pressure, clearing bitmap pool");
                bitmapPool.clear();
                break;
                
            case TRIM_MEMORY_RUNNING_MODERATE:
            case TRIM_MEMORY_RUNNING_LOW:
                // App running but memory is tight
                Log.d(TAG, "Running with low memory, trimming 30%");
                bitmapPool.trim(0.3f);
                break;
                
            case TRIM_MEMORY_RUNNING_CRITICAL:
                // Very critical while running
                Log.w(TAG, "Running with critical memory, aggressive trimming");
                bitmapPool.trim(0.75f);
                break;
        }
        
        // Log stats after trimming
        logStats();
    }
    
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // Configuration changes don't affect bitmap pools directly
    }
    
    @Override
    public void onLowMemory() {
        // This is equivalent to TRIM_MEMORY_COMPLETE
        onTrimMemory(TRIM_MEMORY_COMPLETE);
    }
    
    /**
     * Shuts down the manager and releases all resources.
     * Should be called when the application is terminating.
     */
    public void shutdown() {
        Log.d(TAG, "Shutting down BitmapPoolManager");
        
        if (bitmapPool != null) {
            bitmapPool.clear();
            bitmapPool = null;
        }
        
        // Unregister callbacks
        try {
            context.unregisterComponentCallbacks(this);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister component callbacks", e);
        }
        
        Log.d(TAG, "BitmapPoolManager shutdown complete");
    }
}