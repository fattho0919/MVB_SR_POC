package com.example.sr_poc.utils;

import android.graphics.Bitmap;
import android.util.Log;

public final class MemoryUtils {
    
    private static final String TAG = "MemoryUtils";
    
    private MemoryUtils() {
        // Prevent instantiation
    }
    
    public static class MemoryInfo {
        public final long maxMemoryMB;
        public final long totalMemoryMB;
        public final long usedMemoryMB;
        public final long freeMemoryMB;
        public final long availableMemoryMB;
        
        public MemoryInfo(long maxMemoryMB, long totalMemoryMB, long usedMemoryMB, 
                         long freeMemoryMB, long availableMemoryMB) {
            this.maxMemoryMB = maxMemoryMB;
            this.totalMemoryMB = totalMemoryMB;
            this.usedMemoryMB = usedMemoryMB;
            this.freeMemoryMB = freeMemoryMB;
            this.availableMemoryMB = availableMemoryMB;
        }
        
        @Override
        public String toString() {
            return String.format("Memory - Max: %dMB, Used: %dMB, Available: %dMB", 
                               maxMemoryMB, usedMemoryMB, availableMemoryMB);
        }
    }
    
    public static MemoryInfo getCurrentMemoryInfo() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long availableMemory = maxMemory - usedMemory;
        
        return new MemoryInfo(
            maxMemory / (1024 * 1024),
            totalMemory / (1024 * 1024),
            usedMemory / (1024 * 1024),
            freeMemory / (1024 * 1024),
            availableMemory / (1024 * 1024)
        );
    }
    
    public static boolean isLowMemory() {
        return getCurrentMemoryInfo().availableMemoryMB < Constants.LOW_MEMORY_WARNING_MB;
    }
    
    public static boolean hasEnoughMemoryForProcessing(Bitmap bitmap, int scaleFactor) {
        if (bitmap == null) return false;
        
        long outputPixels = (long) bitmap.getWidth() * bitmap.getHeight() * scaleFactor * scaleFactor;
        long estimatedMemoryMB = outputPixels * 4 / (1024 * 1024); // ARGB 4 bytes per pixel
        
        MemoryInfo memInfo = getCurrentMemoryInfo();
        boolean hasEnough = estimatedMemoryMB < (memInfo.availableMemoryMB * Constants.MEMORY_USAGE_THRESHOLD);
        
        Log.d(TAG, "Memory check - Required: " + estimatedMemoryMB + "MB, Available: " + 
              memInfo.availableMemoryMB + "MB, Sufficient: " + hasEnough);
        
        return hasEnough;
    }
    
    public static void logMemoryWarningIfNeeded() {
        MemoryInfo memInfo = getCurrentMemoryInfo();
        if (memInfo.availableMemoryMB < Constants.LOW_MEMORY_WARNING_MB) {
            Log.w(TAG, "Low memory warning: " + memInfo);
        }
    }
    
    public static void safeRecycleBitmap(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }
}