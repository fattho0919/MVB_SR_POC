package com.example.sr_poc.processing;

import android.graphics.Bitmap;
import android.util.Log;

import com.example.sr_poc.ConfigManager;
import com.example.sr_poc.ImageManager;
import com.example.sr_poc.PerformanceMonitor;
import com.example.sr_poc.ThreadSafeSRProcessor;
import com.example.sr_poc.TileProcessor;
import com.example.sr_poc.utils.MemoryUtils;

public class ProcessingController {
    
    private static final String TAG = "ProcessingController";
    
    private final ThreadSafeSRProcessor srProcessor;
    private final ConfigManager configManager;
    private final ImageManager imageManager;
    
    public interface ProcessingCallback {
        void onStart();
        void onProgress(String message);
        void onSuccess(Bitmap resultBitmap, String timeMessage);
        void onError(String error);
        void onComplete();
    }
    
    public ProcessingController(ThreadSafeSRProcessor srProcessor, ConfigManager configManager, ImageManager imageManager) {
        this.srProcessor = srProcessor;
        this.configManager = configManager;
        this.imageManager = imageManager;
    }
    
    public void processImage(ThreadSafeSRProcessor.ProcessingMode mode, boolean forceTiling, ProcessingCallback callback) {
        new Thread(() -> {
            try {
                callback.onStart();
                
                Bitmap currentBitmap = imageManager.getCurrentBitmap();
                if (currentBitmap == null) {
                    callback.onError("No image loaded");
                    return;
                }
                
                // Check memory before processing
                MemoryUtils.logMemoryWarningIfNeeded();
                
                // Create performance stats
                PerformanceMonitor.InferenceStats stats = createPerformanceStats(currentBitmap, mode);
                
                long startTime = System.currentTimeMillis();
                Bitmap resultBitmap;
                
                // Determine processing method
                boolean shouldUseTiling = forceTiling || 
                    TileProcessor.shouldUseTileProcessing(currentBitmap, configManager);
                
                if (shouldUseTiling) {
                    callback.onProgress("Using tile processing for large image");
                    resultBitmap = processByTiles(currentBitmap, mode, callback);
                    stats.usedTileProcessing = true;
                } else {
                    callback.onProgress("Using direct processing");
                    resultBitmap = processDirect(currentBitmap, mode);
                    stats.usedTileProcessing = false;
                }
                
                long endTime = System.currentTimeMillis();
                completeProcessing(stats, resultBitmap, endTime - startTime, callback);
                
            } catch (OutOfMemoryError e) {
                Log.e(TAG, "Out of memory error", e);
                callback.onError("Out of memory! Try closing other apps.");
            } catch (Exception e) {
                Log.e(TAG, "Exception during processing", e);
                callback.onError("Error: " + e.getClass().getSimpleName());
            } finally {
                callback.onComplete();
            }
        }).start();
    }
    
    private PerformanceMonitor.InferenceStats createPerformanceStats(Bitmap bitmap, ThreadSafeSRProcessor.ProcessingMode mode) {
        PerformanceMonitor.InferenceStats stats = PerformanceMonitor.createStats();
        stats.inputWidth = bitmap.getWidth();
        stats.inputHeight = bitmap.getHeight();
        stats.accelerator = mode != null ? mode.name() + " (Forced)" : srProcessor.getAcceleratorInfo();
        
        // Enhanced performance details
        if (mode == ThreadSafeSRProcessor.ProcessingMode.CPU || srProcessor.getCurrentMode() == ThreadSafeSRProcessor.ProcessingMode.CPU) {
            stats.pureCpuMode = configManager.isEnablePureCpuMode();
            stats.usingNNAPI = configManager.isUseNnapi() && !stats.pureCpuMode;
            stats.threadCount = configManager.getDefaultNumThreads();
        } else if (mode == ThreadSafeSRProcessor.ProcessingMode.NPU || srProcessor.getCurrentMode() == ThreadSafeSRProcessor.ProcessingMode.NPU) {
            stats.usingNNAPI = true;
            stats.threadCount = 1; // NPU typically single-threaded
        }
        
        MemoryUtils.MemoryInfo memInfo = MemoryUtils.getCurrentMemoryInfo();
        stats.memoryBefore = memInfo.usedMemoryMB;
        
        return stats;
    }
    
    private Bitmap processByTiles(Bitmap bitmap, ThreadSafeSRProcessor.ProcessingMode mode, ProcessingCallback callback) {
        TileProcessor tileProcessor = new TileProcessor(srProcessor, configManager);
        return tileProcessor.processByTiles(bitmap, new TileProcessor.ProcessCallback() {
            @Override
            public void onProgress(int completed, int total) {
                String progressMsg = mode != null ? 
                    "Processing with " + mode.name() + " - tiles: " + completed + "/" + total :
                    "Processing tiles: " + completed + "/" + total;
                callback.onProgress(progressMsg);
            }
        });
    }
    
    private Bitmap processDirect(Bitmap bitmap, ThreadSafeSRProcessor.ProcessingMode mode) {
        final Object lock = new Object();
        final Bitmap[] result = new Bitmap[1];
        final boolean[] completed = new boolean[1];
        
        ThreadSafeSRProcessor.InferenceCallback inferenceCallback = new ThreadSafeSRProcessor.InferenceCallback() {
            @Override
            public void onResult(Bitmap resultImage, long inferenceTime) {
                synchronized (lock) {
                    result[0] = resultImage;
                    completed[0] = true;
                    lock.notify();
                }
            }
            
            @Override
            public void onError(String error) {
                Log.e(TAG, "Direct processing failed: " + error);
                synchronized (lock) {
                    result[0] = null;
                    completed[0] = true;
                    lock.notify();
                }
            }
        };
        
        if (mode != null) {
            srProcessor.processImageWithMode(bitmap, mode, inferenceCallback);
        } else {
            srProcessor.processImage(bitmap, inferenceCallback);
        }
        
        // Wait for completion
        synchronized (lock) {
            while (!completed[0]) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Log.e(TAG, "Wait interrupted");
                    break;
                }
            }
        }
        
        return result[0];
    }
    
    private void completeProcessing(PerformanceMonitor.InferenceStats stats, Bitmap resultBitmap, 
                                  long totalTime, ProcessingCallback callback) {
        stats.inferenceTime = totalTime;
        
        if (resultBitmap != null) {
            stats.outputWidth = resultBitmap.getWidth();
            stats.outputHeight = resultBitmap.getHeight();
            
            MemoryUtils.MemoryInfo memInfo = MemoryUtils.getCurrentMemoryInfo();
            stats.memoryAfter = memInfo.usedMemoryMB;
            
            PerformanceMonitor.logPerformanceStats(stats);
            
            String timeMessage = String.format("Inference time: %d ms", stats.inferenceTime);
            if (stats.accelerator.contains("(Forced)")) {
                timeMessage = String.format("Inference time (%s): %d ms", 
                    stats.accelerator.replace(" (Forced)", ""), stats.inferenceTime);
            }
            
            callback.onSuccess(resultBitmap, timeMessage);
        } else {
            callback.onError("Processing returned null result");
        }
    }
}