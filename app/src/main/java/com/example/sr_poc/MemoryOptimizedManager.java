package com.example.sr_poc;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.util.Log;

import com.example.sr_poc.utils.MemoryUtils;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Memory optimization manager for SR processors.
 * 
 * Key features:
 * - Responds to system memory pressure
 * - Intelligently evicts inactive interpreters
 * - Tracks interpreter usage patterns
 * - Provides memory management policies
 */
public class MemoryOptimizedManager implements ComponentCallbacks2 {
    
    private static final String TAG = "MemoryOptimizedManager";
    
    // Maximum number of interpreters to keep in memory
    private static final int MAX_INTERPRETERS_HIGH_END = 3;
    private static final int MAX_INTERPRETERS_MID_RANGE = 2;
    private static final int MAX_INTERPRETERS_LOW_END = 1;
    
    // Memory thresholds (MB)
    private static final int HIGH_END_MEMORY_THRESHOLD = 4000;  // 4GB+
    private static final int MID_RANGE_MEMORY_THRESHOLD = 2000; // 2GB+
    
    private final Context context;
    private ThreadSafeSRProcessor processor;
    private final AtomicInteger activeInterpreters = new AtomicInteger(0);
    
    // Usage tracking for intelligent eviction
    private long lastGpuUsage = 0;
    private long lastCpuUsage = 0;
    private long lastNpuUsage = 0;
    
    // Device capability
    private final int maxInterpreters;
    
    public MemoryOptimizedManager(Context context) {
        this.context = context.getApplicationContext();
        this.maxInterpreters = determineMaxInterpreters();
        
        // Register for memory callbacks
        context.registerComponentCallbacks(this);
        
        Log.d(TAG, "Memory manager initialized - Max interpreters: " + maxInterpreters);
    }
    
    /**
     * Determine maximum interpreters based on device memory.
     */
    private int determineMaxInterpreters() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemoryMB = runtime.maxMemory() / (1024 * 1024);
        
        if (maxMemoryMB >= HIGH_END_MEMORY_THRESHOLD) {
            Log.d(TAG, "High-end device detected: " + maxMemoryMB + "MB");
            return MAX_INTERPRETERS_HIGH_END;
        } else if (maxMemoryMB >= MID_RANGE_MEMORY_THRESHOLD) {
            Log.d(TAG, "Mid-range device detected: " + maxMemoryMB + "MB");
            return MAX_INTERPRETERS_MID_RANGE;
        } else {
            Log.d(TAG, "Low-end device detected: " + maxMemoryMB + "MB");
            return MAX_INTERPRETERS_LOW_END;
        }
    }
    
    /**
     * Set the processor to manage.
     */
    public void setProcessor(ThreadSafeSRProcessor processor) {
        this.processor = processor;
    }
    
    /**
     * Record usage of a specific processing mode.
     */
    public void recordUsage(ThreadSafeSRProcessor.ProcessingMode mode) {
        long now = System.currentTimeMillis();
        
        switch (mode) {
            case GPU:
                lastGpuUsage = now;
                break;
            case CPU:
                lastCpuUsage = now;
                break;
            case NPU:
                lastNpuUsage = now;
                break;
        }
    }
    
    /**
     * Check if we should keep an interpreter in memory.
     */
    public boolean shouldKeepInterpreter(ThreadSafeSRProcessor.ProcessingMode mode) {
        if (activeInterpreters.get() < maxInterpreters) {
            return true;
        }
        
        // If at limit, only keep recently used interpreters
        long now = System.currentTimeMillis();
        long ageThreshold = 60000; // 1 minute
        
        switch (mode) {
            case GPU:
                return (now - lastGpuUsage) < ageThreshold;
            case CPU:
                return (now - lastCpuUsage) < ageThreshold;
            case NPU:
                return (now - lastNpuUsage) < ageThreshold;
            default:
                return false;
        }
    }
    
    /**
     * Called when interpreter is created.
     */
    public void onInterpreterCreated(ThreadSafeSRProcessor.ProcessingMode mode) {
        int count = activeInterpreters.incrementAndGet();
        Log.d(TAG, "Interpreter created: " + mode + " (Total: " + count + ")");
        
        // Check if we need to evict
        if (count > maxInterpreters) {
            evictLeastRecentlyUsed();
        }
    }
    
    /**
     * Called when interpreter is released.
     */
    public void onInterpreterReleased(ThreadSafeSRProcessor.ProcessingMode mode) {
        int count = activeInterpreters.decrementAndGet();
        Log.d(TAG, "Interpreter released: " + mode + " (Total: " + count + ")");
    }
    
    /**
     * Evict least recently used interpreter.
     */
    private void evictLeastRecentlyUsed() {
        if (processor == null) return;
        
        long now = System.currentTimeMillis();
        
        // Find LRU interpreter
        ThreadSafeSRProcessor.ProcessingMode lruMode = null;
        long oldestUsage = now;
        
        if (lastGpuUsage < oldestUsage && processor.hasInterpreter(ThreadSafeSRProcessor.ProcessingMode.GPU)) {
            oldestUsage = lastGpuUsage;
            lruMode = ThreadSafeSRProcessor.ProcessingMode.GPU;
        }
        
        if (lastCpuUsage < oldestUsage && processor.hasInterpreter(ThreadSafeSRProcessor.ProcessingMode.CPU)) {
            oldestUsage = lastCpuUsage;
            lruMode = ThreadSafeSRProcessor.ProcessingMode.CPU;
        }
        
        if (lastNpuUsage < oldestUsage && processor.hasInterpreter(ThreadSafeSRProcessor.ProcessingMode.NPU)) {
            oldestUsage = lastNpuUsage;
            lruMode = ThreadSafeSRProcessor.ProcessingMode.NPU;
        }
        
        // Evict if found
        if (lruMode != null) {
            Log.d(TAG, "Evicting LRU interpreter: " + lruMode);
            processor.releaseInterpreter(lruMode);
        }
    }
    
    @Override
    public void onTrimMemory(int level) {
        Log.d(TAG, "onTrimMemory called with level: " + level);
        
        if (processor == null) return;
        
        switch (level) {
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
                Log.w(TAG, "Memory running low - keeping only active interpreter");
                evictInactiveInterpreters(1);
                break;
                
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
                Log.e(TAG, "Memory critically low - releasing all inactive interpreters");
                evictInactiveInterpreters(0);
                break;
                
            case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN:
                Log.d(TAG, "UI hidden - releasing GPU interpreter");
                processor.releaseInterpreter(ThreadSafeSRProcessor.ProcessingMode.GPU);
                break;
                
            case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND:
            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                Log.d(TAG, "Background memory pressure - releasing all interpreters");
                processor.releaseAllInterpreters();
                break;
                
            default:
                Log.d(TAG, "Unhandled trim level: " + level);
                break;
        }
        
        // Log memory status after trimming
        MemoryUtils.MemoryInfo memInfo = MemoryUtils.getCurrentMemoryInfo();
        Log.d(TAG, "Memory after trim: " + memInfo);
    }
    
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // Not used for memory management
    }
    
    @Override
    public void onLowMemory() {
        Log.e(TAG, "onLowMemory called - emergency memory release");
        if (processor != null) {
            processor.releaseAllInterpreters();
        }
        
        // Force garbage collection
        System.gc();
    }
    
    /**
     * Evict inactive interpreters, keeping only the specified number.
     */
    private void evictInactiveInterpreters(int keepCount) {
        if (processor == null) return;
        
        int currentCount = activeInterpreters.get();
        int toEvict = Math.max(0, currentCount - keepCount);
        
        Log.d(TAG, "Evicting " + toEvict + " interpreters (keeping " + keepCount + ")");
        
        for (int i = 0; i < toEvict; i++) {
            evictLeastRecentlyUsed();
        }
    }
    
    /**
     * Get memory optimization status.
     */
    public String getOptimizationStatus() {
        MemoryUtils.MemoryInfo memInfo = MemoryUtils.getCurrentMemoryInfo();
        
        return String.format(
            "Memory Optimization Status:\n" +
            "Active Interpreters: %d/%d\n" +
            "Memory Available: %dMB\n" +
            "Memory Used: %dMB\n" +
            "Device Class: %s",
            activeInterpreters.get(),
            maxInterpreters,
            memInfo.availableMemoryMB,
            memInfo.usedMemoryMB,
            getDeviceClass()
        );
    }
    
    private String getDeviceClass() {
        if (maxInterpreters == MAX_INTERPRETERS_HIGH_END) {
            return "High-end";
        } else if (maxInterpreters == MAX_INTERPRETERS_MID_RANGE) {
            return "Mid-range";
        } else {
            return "Low-end";
        }
    }
    
    /**
     * Cleanup and unregister callbacks.
     */
    public void cleanup() {
        context.unregisterComponentCallbacks(this);
        processor = null;
    }
}