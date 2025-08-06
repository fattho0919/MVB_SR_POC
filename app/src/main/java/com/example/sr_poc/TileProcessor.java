package com.example.sr_poc;

import android.graphics.Bitmap;
import android.util.Log;

import com.example.sr_poc.tiling.ParallelTileProcessor;

/**
 * Simplified tile processor using CPU parallel processing
 * User-controlled tiling with fallback to direct processing
 */
public class TileProcessor {
    
    private static final String TAG = "TileProcessor";
    
    private final ParallelTileProcessor parallelProcessor;
    
    public interface ProcessCallback {
        void onProgress(int completed, int total);
    }
    
    public TileProcessor(ThreadSafeSRProcessor processor) {
        // Create with default config - this constructor is for backward compatibility
        this.parallelProcessor = new ParallelTileProcessor(processor, ConfigManager.getInstance(null));
        Log.d(TAG, "TileProcessor created with CPU parallel backend");
    }
    
    public TileProcessor(ThreadSafeSRProcessor processor, ConfigManager configManager) {
        this.parallelProcessor = new ParallelTileProcessor(processor, configManager);
        Log.d(TAG, "TileProcessor created with CPU parallel backend");
    }
    
    /**
     * Process image using CPU parallel tiling (user-controlled)
     * Compatible with existing ProcessingController interface
     */
    public Bitmap processByTiles(Bitmap inputBitmap, ProcessCallback progressCallback) {
        return processByTiles(inputBitmap, true, progressCallback);
    }
    
    /**
     * Process image with explicit tiling control using CPU parallel processing
     */
    public Bitmap processByTiles(Bitmap inputBitmap, boolean enableTiling, ProcessCallback progressCallback) {
        if (inputBitmap == null || inputBitmap.isRecycled()) {
            Log.e(TAG, "Invalid input bitmap");
            return null;
        }
        
        Log.d(TAG, "Starting CPU parallel processing for " + 
              inputBitmap.getWidth() + "x" + inputBitmap.getHeight() + " image (tiling: " + enableTiling + ")");
        
        final Object lock = new Object();
        final Bitmap[] result = new Bitmap[1];
        final boolean[] completed = new boolean[1];
        final String[] errorMessage = new String[1];
        
        ParallelTileProcessor.ParallelProcessingCallback callback = new ParallelTileProcessor.ParallelProcessingCallback() {
            @Override
            public void onProgress(String message) {
                Log.d(TAG, "Progress: " + message);
            }
            
            @Override
            public void onTileProgress(int completedTiles, int totalTiles) {
                if (progressCallback != null) {
                    progressCallback.onProgress(completedTiles, totalTiles);
                }
                Log.d(TAG, "CPU parallel progress: " + completedTiles + "/" + totalTiles);
            }
            
            @Override
            public void onResult(Bitmap resultBitmap, long totalTime) {
                synchronized (lock) {
                    result[0] = resultBitmap;
                    completed[0] = true;
                    lock.notify();
                }
                Log.d(TAG, "CPU parallel processing completed in " + totalTime + "ms");
            }
            
            @Override
            public void onError(String error) {
                synchronized (lock) {
                    errorMessage[0] = error;
                    completed[0] = true;
                    lock.notify();
                }
                Log.e(TAG, "CPU parallel processing failed: " + error);
            }
        };
        
        // Use CPU mode with user-controlled tiling for reliable parallel processing
        parallelProcessor.processImage(inputBitmap, ThreadSafeSRProcessor.ProcessingMode.CPU, 
                                     enableTiling, callback);
        
        // Wait for completion
        synchronized (lock) {
            while (!completed[0]) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Log.e(TAG, "Wait interrupted", e);
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        if (errorMessage[0] != null) {
            Log.e(TAG, "CPU parallel processing failed: " + errorMessage[0]);
            return null;
        }
        
        return result[0];
    }
    
    /**
     * Check if tile processing should be used for the given image
     * Note: This is now mainly for backwards compatibility - 
     * tiling decision should be user-controlled via UI
     */
    public static boolean shouldUseTileProcessing(Bitmap bitmap) {
        if (bitmap == null) return false;
        
        // For very large images, suggest tiling (but user still controls)
        int pixels = bitmap.getWidth() * bitmap.getHeight();
        return pixels > 2048 * 2048; // 4MP threshold
    }
    
    /**
     * Check if tile processing should be used based on configuration
     * Note: This is now mainly for backwards compatibility -
     * tiling decision should be user-controlled via UI
     */
    public static boolean shouldUseTileProcessing(Bitmap bitmap, ConfigManager config) {
        if (bitmap == null) return false;
        
        // For very large images, suggest tiling (but user still controls)
        int pixels = bitmap.getWidth() * bitmap.getHeight();
        return pixels > 2048 * 2048; // 4MP threshold
    }
    
    /**
     * Shutdown the tile processor and release resources
     */
    public void shutdown() {
        if (parallelProcessor != null) {
            parallelProcessor.shutdown();
            Log.d(TAG, "TileProcessor shutdown completed");
        }
    }
}