package com.example.sr_poc.processing;

import android.graphics.Bitmap;
import android.util.Log;

import com.example.sr_poc.ConfigManager;
import com.example.sr_poc.ImageManager;
import com.example.sr_poc.PerformanceMonitor;
import com.example.sr_poc.ProcessingStrategySelector;
import com.example.sr_poc.ThreadSafeSRProcessor;
import com.example.sr_poc.TileProcessor;
import com.example.sr_poc.tiling.HardwareParallelProcessor;
import com.example.sr_poc.utils.MemoryUtils;

public class ProcessingController {
    
    private static final String TAG = "ProcessingController";
    
    private final ThreadSafeSRProcessor srProcessor;
    private final ConfigManager configManager;
    private final ImageManager imageManager;
    private final ProcessingStrategySelector strategySelector;
    
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
        this.strategySelector = new ProcessingStrategySelector(srProcessor, configManager);
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
                
                // Use intelligent strategy selection
                ProcessingStrategySelector.StrategyDecision decision = 
                    strategySelector.selectStrategy(currentBitmap, mode, forceTiling);
                
                Log.d(TAG, "Selected strategy: " + decision);
                callback.onProgress("Strategy: " + ProcessingStrategySelector.getStrategyDescription(decision.strategy));
                
                // Create performance stats
                PerformanceMonitor.InferenceStats stats = createPerformanceStats(currentBitmap, mode);
                stats.estimatedTime = decision.estimatedTimeMs;
                
                long startTime = System.currentTimeMillis();
                Bitmap resultBitmap = executeStrategy(currentBitmap, mode, decision, callback, stats);
                
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
    
    private Bitmap executeStrategy(Bitmap currentBitmap, ThreadSafeSRProcessor.ProcessingMode mode,
                                 ProcessingStrategySelector.StrategyDecision decision, 
                                 ProcessingCallback callback, PerformanceMonitor.InferenceStats stats) {
        
        switch (decision.strategy) {
            case NPU_BATCH:
                callback.onProgress("Using NPU batch processing (hardware parallel)");
                stats.usedTileProcessing = true;
                stats.accelerator = "NPU (Batch)";
                return processWithNpuBatch(currentBitmap, mode, callback);
                
            case CPU_PARALLEL_TILING:
                callback.onProgress("Using CPU parallel tiling (" + decision.reason + ")");
                stats.usedTileProcessing = true;
                stats.accelerator = "CPU (Parallel)";
                return processByTiles(currentBitmap, ThreadSafeSRProcessor.ProcessingMode.CPU, callback);
                
            case CPU_SEQUENTIAL:
                callback.onProgress("Using CPU sequential processing (" + decision.reason + ")");
                stats.usedTileProcessing = true;
                stats.accelerator = "CPU (Sequential)";
                return processByTilesSequential(currentBitmap, callback);
                
            case DIRECT_PROCESSING:
            default:
                callback.onProgress("Using direct processing (no tiling needed)");
                stats.usedTileProcessing = false;
                stats.accelerator = mode != null ? mode.name() : "Auto";
                return processDirect(currentBitmap, mode);
        }
    }
    
    private Bitmap processWithNpuBatch(Bitmap bitmap, ThreadSafeSRProcessor.ProcessingMode mode, ProcessingCallback callback) {
        HardwareParallelProcessor hardwareProcessor = new HardwareParallelProcessor(srProcessor, configManager);
        
        final Object lock = new Object();
        final Bitmap[] result = new Bitmap[1];
        final boolean[] completed = new boolean[1];
        final String[] error = new String[1];
        
        HardwareParallelProcessor.HardwareParallelCallback hardwareCallback = 
            new HardwareParallelProcessor.HardwareParallelCallback() {
                @Override
                public void onProgress(String message) {
                    callback.onProgress(message);
                }
                
                @Override
                public void onTileProgress(int completed, int total) {
                    callback.onProgress("NPU batch processing tiles: " + completed + "/" + total);
                }
                
                @Override
                public void onResult(Bitmap resultBitmap, long totalTime) {
                    synchronized (lock) {
                        result[0] = resultBitmap;
                        completed[0] = true;
                        lock.notify();
                    }
                }
                
                @Override
                public void onError(String errorMsg) {
                    synchronized (lock) {
                        error[0] = errorMsg;
                        completed[0] = true;
                        lock.notify();
                    }
                }
            };
        
        // Use NPU mode for hardware parallel processing
        hardwareProcessor.processImage(bitmap, 
            mode != null ? mode : ThreadSafeSRProcessor.ProcessingMode.NPU, 
            true, hardwareCallback);
        
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
        
        if (error[0] != null) {
            Log.e(TAG, "NPU batch processing failed: " + error[0]);
            // Fallback to CPU parallel tiling
            callback.onProgress("NPU batch failed, falling back to CPU parallel tiling");
            return processByTiles(bitmap, ThreadSafeSRProcessor.ProcessingMode.CPU, callback);
        }
        
        return result[0];
    }
    
    private Bitmap processByTilesSequential(Bitmap bitmap, ProcessingCallback callback) {
        // Use CPU mode with sequential processing (single thread)
        TileProcessor tileProcessor = new TileProcessor(srProcessor, configManager);
        return tileProcessor.processByTiles(bitmap, true, new TileProcessor.ProcessCallback() {
            @Override
            public void onProgress(int completed, int total) {
                callback.onProgress("Processing tiles (sequential): " + completed + "/" + total);
            }
        });
    }
    
    private PerformanceMonitor.InferenceStats createPerformanceStats(Bitmap bitmap, ThreadSafeSRProcessor.ProcessingMode mode) {
        PerformanceMonitor.InferenceStats stats = PerformanceMonitor.createStats();
        stats.inputWidth = bitmap.getWidth();
        stats.inputHeight = bitmap.getHeight();
        stats.accelerator = mode != null ? mode.name() + " (Forced)" : srProcessor.getAcceleratorInfo();
        
        MemoryUtils.MemoryInfo memInfo = MemoryUtils.getCurrentMemoryInfo();
        stats.memoryBefore = memInfo.usedMemoryMB;
        
        return stats;
    }
    
    private Bitmap processByTiles(Bitmap bitmap, ThreadSafeSRProcessor.ProcessingMode mode, ProcessingCallback callback) {
        TileProcessor tileProcessor = new TileProcessor(srProcessor, configManager);
        // Always enable tiling when this method is called (user has enabled tiling)
        return tileProcessor.processByTiles(bitmap, true, new TileProcessor.ProcessCallback() {
            @Override
            public void onProgress(int completed, int total) {
                String progressMsg = mode != null ? 
                    "Processing with " + mode.name() + " (CPU parallel) - tiles: " + completed + "/" + total :
                    "Processing tiles (CPU parallel): " + completed + "/" + total;
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