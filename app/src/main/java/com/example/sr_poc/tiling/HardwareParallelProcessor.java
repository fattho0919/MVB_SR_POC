package com.example.sr_poc.tiling;

import android.graphics.Bitmap;
import android.util.Log;

import com.example.sr_poc.ThreadSafeSRProcessor;
import com.example.sr_poc.ConfigManager;
import com.example.sr_poc.utils.BitmapConverter;

import java.util.ArrayList;
import java.util.List;

/**
 * Hardware-level parallel processor using single TensorFlow Lite interpreter
 * with batch tensor processing for true NPU SIMD parallelism
 * 
 * Based on Real-ESRGAN tile processing best practices:
 * - 128px tiles with 16px overlap (padding)
 * - Single interpreter instance with batch tensor stacking
 * - NPU hardware-level parallelism through NNAPI delegate
 */
public class HardwareParallelProcessor {
    
    private static final String TAG = "HardwareParallelProcessor";
    
    // Use existing model tile configuration (384x384 with 32px overlap)
    private static final int OPTIMAL_TILE_SIZE = 384;
    private static final int TILE_PADDING = 32;
    private static final int EFFECTIVE_TILE_STEP = OPTIMAL_TILE_SIZE - TILE_PADDING;
    
    private final ThreadSafeSRProcessor srProcessor;
    private final ConfigManager configManager;
    private final int scaleFactor;
    
    public interface HardwareParallelCallback {
        void onProgress(String message);
        void onTileProgress(int completedTiles, int totalTiles);
        void onResult(Bitmap resultBitmap, long totalTime);
        void onError(String error);
    }
    
    public HardwareParallelProcessor(ThreadSafeSRProcessor srProcessor, ConfigManager configManager) {
        this.srProcessor = srProcessor;
        this.configManager = configManager;
        this.scaleFactor = configManager.getExpectedScaleFactor();
        
        Log.d(TAG, "HardwareParallelProcessor initialized for NPU hardware-level parallelism");
        Log.d(TAG, "Using existing model strategy: " + OPTIMAL_TILE_SIZE + "px tiles + " + TILE_PADDING + "px padding");
    }
    
    /**
     * Process image using NPU hardware-level parallel processing
     * Single interpreter instance with batch tensor stacking
     */
    public void processImage(Bitmap inputBitmap, ThreadSafeSRProcessor.ProcessingMode mode, 
                           boolean forceTiling, HardwareParallelCallback callback) {
        if (inputBitmap == null || inputBitmap.isRecycled()) {
            callback.onError("Invalid input bitmap");
            return;
        }
        
        long startTime = System.currentTimeMillis();
        
        // Use tiling only if explicitly requested
        if (!forceTiling) {
            Log.d(TAG, "Tiling not requested, using direct processing");
            callback.onProgress("Processing directly (tiling disabled)");
            processDirectly(inputBitmap, mode, callback, startTime);
            return;
        }
        
        // Proceed with hardware-level parallel tiling
        callback.onProgress("Starting NPU hardware-level parallel processing");
        processWithHardwareParallelism(inputBitmap, mode, callback, startTime);
    }
    
    /**
     * Core hardware parallel implementation using single interpreter + batch tensors
     */
    private void processWithHardwareParallelism(Bitmap inputBitmap, ThreadSafeSRProcessor.ProcessingMode mode,
                                              HardwareParallelCallback callback, long startTime) {
        try {
            // Extract tiles using existing strategy (384x384 + 32px overlap)
            callback.onProgress("Extracting tiles with existing model strategy");
            
            TileExtractor extractor = new TileExtractor(
                OPTIMAL_TILE_SIZE, OPTIMAL_TILE_SIZE, TILE_PADDING
            );
            
            List<TileExtractor.TileInfo> tiles = extractor.extractTiles(inputBitmap);
            
            if (tiles.isEmpty()) {
                callback.onError("Failed to extract tiles");
                return;
            }
            
            Log.d(TAG, "Extracted " + tiles.size() + " tiles for NPU hardware parallel processing");
            callback.onProgress("Processing " + tiles.size() + " tiles with NPU hardware parallelism");
            
            // Process tiles using hardware-level parallelism
            long processingStart = System.currentTimeMillis();
            List<Bitmap> processedTiles = processHardwareParallelTiles(tiles, mode, callback);
            long processingTime = System.currentTimeMillis() - processingStart;
            
            if (processedTiles == null || processedTiles.size() != tiles.size()) {
                callback.onError("Hardware parallel processing failed");
                cleanupTiles(tiles);
                return;
            }
            
            Log.d(TAG, "NPU hardware parallel processing completed in " + processingTime + "ms");
            
            // Merge results with existing strategy
            callback.onProgress("Merging tiles with existing model strategy");
            
            TileMerger merger = new TileMerger(
                inputBitmap.getWidth(), inputBitmap.getHeight(),
                OPTIMAL_TILE_SIZE, OPTIMAL_TILE_SIZE, TILE_PADDING, scaleFactor
            );
            
            Bitmap result = merger.mergeTiles(tiles, processedTiles);
            
            // Clean up intermediate results
            for (Bitmap tile : processedTiles) {
                if (tile != null && !tile.isRecycled()) {
                    tile.recycle();
                }
            }
            cleanupTiles(tiles);
            
            if (result != null) {
                long totalTime = System.currentTimeMillis() - startTime;
                Log.d(TAG, "NPU hardware-level parallel processing completed in " + totalTime + "ms");
                callback.onResult(result, totalTime);
            } else {
                callback.onError("Failed to merge tiles");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error during hardware parallel processing", e);
            callback.onError("Hardware parallel processing failed: " + e.getMessage());
        }
    }
    
    /**
     * Process tiles using NPU hardware-level parallelism
     * Key innovation: Single interpreter with batch tensor processing
     */
    private List<Bitmap> processHardwareParallelTiles(List<TileExtractor.TileInfo> tiles, 
                                                    ThreadSafeSRProcessor.ProcessingMode mode,
                                                    HardwareParallelCallback callback) {
        
        Log.d(TAG, "Starting NPU hardware parallel execution of " + tiles.size() + " tiles");
        
        // Check if we can use batch processing for true hardware parallelism
        if (srProcessor.isModelBatchCapable() && tiles.size() <= srProcessor.getModelBatchSize()) {
            Log.d(TAG, "=== PERFORMANCE COMPARISON TEST ===");
            
            // For testing: run both batch and sequential to compare
            Log.d(TAG, "Testing batch processing performance...");
            List<Bitmap> batchResults = processWithBatchTensorStacking(tiles, mode, callback);
            
            // Uncomment the following lines to also test sequential processing for comparison
            // Log.d(TAG, "Testing sequential processing performance...");
            // List<Bitmap> sequentialResults = processSequentialOptimized(tiles, mode, callback);
            
            return batchResults;
        } else {
            // Fallback to optimized sequential processing
            Log.w(TAG, "Model not batch capable or too many tiles, using optimized sequential processing");
            return processSequentialOptimized(tiles, mode, callback);
        }
    }
    
    /**
     * True hardware parallelism: Process all tiles in single batch inference
     */
    private List<Bitmap> processWithBatchTensorStacking(List<TileExtractor.TileInfo> tiles,
                                                       ThreadSafeSRProcessor.ProcessingMode mode,
                                                       HardwareParallelCallback callback) {
        
        int batchSize = tiles.size();
        Log.d(TAG, "Using batch tensor processing for " + batchSize + " tiles (true NPU hardware parallelism)");
        
        try {
            // Extract tile bitmaps for batch processing
            List<Bitmap> tileBitmaps = new ArrayList<>();
            for (TileExtractor.TileInfo tileInfo : tiles) {
                tileBitmaps.add(tileInfo.bitmap);
            }
            
            // Single batch inference call - this is where the magic happens!
            long inferenceStart = System.currentTimeMillis();
            
            final Object lock = new Object();
            final List<Bitmap>[] result = new List[1];
            final boolean[] completed = new boolean[1];
            final String[] error = new String[1];
            
            // Create callback for batch processing
            ThreadSafeSRProcessor.BatchInferenceCallback batchCallback = new ThreadSafeSRProcessor.BatchInferenceCallback() {
                @Override
                public void onResult(List<Bitmap> resultBitmaps, long inferenceTime) {
                    synchronized (lock) {
                        result[0] = resultBitmaps;
                        completed[0] = true;
                        lock.notify();
                    }
                    Log.d(TAG, "NPU batch inference completed in " + inferenceTime + "ms for " + batchSize + " tiles");
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
            
            // This is where NPU hardware parallelism happens!
            // Single call to processBatch with all tiles
            srProcessor.processBatch(tileBitmaps, mode != null ? mode : ThreadSafeSRProcessor.ProcessingMode.NPU, batchCallback);
            
            // Wait for NPU hardware parallel processing to complete
            synchronized (lock) {
                while (!completed[0]) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("NPU processing interrupted", e);
                    }
                }
            }
            
            if (error[0] != null) {
                throw new RuntimeException("NPU batch processing failed: " + error[0]);
            }
            
            long totalInferenceTime = System.currentTimeMillis() - inferenceStart;
            Log.d(TAG, "True NPU hardware parallelism completed in " + totalInferenceTime + "ms");
            
            callback.onTileProgress(batchSize, batchSize);
            
            return result[0];
            
        } catch (Exception e) {
            Log.e(TAG, "Batch tensor processing failed", e);
            return processSequentialOptimized(tiles, mode, callback);
        }
    }
    
    
    /**
     * Optimized sequential processing with detailed timing for comparison
     */
    private List<Bitmap> processSequentialOptimized(List<TileExtractor.TileInfo> tiles,
                                                   ThreadSafeSRProcessor.ProcessingMode mode,
                                                   HardwareParallelCallback callback) {
        
        List<Bitmap> results = new ArrayList<>();
        int completedCount = 0;
        
        Log.d(TAG, "=== SEQUENTIAL PROCESSING COMPARISON START ===");
        Log.d(TAG, "Processing " + tiles.size() + " tiles sequentially with " + mode.name());
        
        long totalSequentialStart = System.currentTimeMillis();
        long totalInferenceTime = 0;
        
        for (TileExtractor.TileInfo tileInfo : tiles) {
            long tileStart = System.currentTimeMillis();
            
            // Process each tile with detailed timing
            Bitmap result = processSingleTileOptimized(tileInfo.bitmap, mode);
            
            long tileEnd = System.currentTimeMillis();
            long tileTime = tileEnd - tileStart;
            totalInferenceTime += tileTime;
            
            if (result != null) {
                results.add(result);
                completedCount++;
                
                Log.d(TAG, "Sequential tile " + completedCount + "/" + tiles.size() + " processed in " + tileTime + "ms");
                callback.onTileProgress(completedCount, tiles.size());
            } else {
                Log.e(TAG, "Failed to process tile " + completedCount);
                return null;
            }
        }
        
        long totalSequentialTime = System.currentTimeMillis() - totalSequentialStart;
        
        Log.d(TAG, "=== SEQUENTIAL PROCESSING COMPARISON END ===");
        Log.d(TAG, "Total sequential time: " + totalSequentialTime + "ms");
        Log.d(TAG, "Total inference time: " + totalInferenceTime + "ms");
        Log.d(TAG, "Average per tile: " + (totalInferenceTime / tiles.size()) + "ms");
        Log.d(TAG, "Overhead time: " + (totalSequentialTime - totalInferenceTime) + "ms");
        Log.d(TAG, "Sequential processing completed: " + results.size() + " tiles");
        
        return results;
    }
    
    
    /**
     * Process single tile with optimized inference
     */
    private Bitmap processSingleTileOptimized(Bitmap tileBitmap, ThreadSafeSRProcessor.ProcessingMode mode) {
        // Use existing ThreadSafeSRProcessor but with optimization
        final Object lock = new Object();
        final Bitmap[] result = new Bitmap[1];
        final boolean[] completed = new boolean[1];
        
        ThreadSafeSRProcessor.InferenceCallback callback = new ThreadSafeSRProcessor.InferenceCallback() {
            @Override
            public void onResult(Bitmap resultBitmap, long inferenceTime) {
                synchronized (lock) {
                    result[0] = resultBitmap;
                    completed[0] = true;
                    lock.notify();
                }
            }
            
            @Override
            public void onError(String errorMsg) {
                synchronized (lock) {
                    completed[0] = true;
                    lock.notify();
                }
            }
        };
        
        // Process with specified mode
        if (mode != null) {
            srProcessor.processImageWithMode(tileBitmap, mode, callback);
        } else {
            srProcessor.processImage(tileBitmap, callback);
        }
        
        // Wait for completion
        synchronized (lock) {
            while (!completed[0]) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        return result[0];
    }
    
    /**
     * Process directly without tiling
     */
    private void processDirectly(Bitmap inputBitmap, ThreadSafeSRProcessor.ProcessingMode mode,
                               HardwareParallelCallback callback, long startTime) {
        Bitmap result = processSingleTileOptimized(inputBitmap, mode);
        
        if (result != null) {
            long totalTime = System.currentTimeMillis() - startTime;
            callback.onResult(result, totalTime);
        } else {
            callback.onError("Direct processing failed");
        }
    }
    
    /**
     * Clean up tile bitmaps
     */
    private void cleanupTiles(List<TileExtractor.TileInfo> tiles) {
        for (TileExtractor.TileInfo tile : tiles) {
            if (tile.bitmap != null && !tile.bitmap.isRecycled()) {
                tile.bitmap.recycle();
            }
        }
    }
    
    
    /**
     * Check if hardware parallel tiling would be beneficial
     */
    public static boolean shouldUseHardwareParallelTiling(int imageWidth, int imageHeight) {
        // Use Real-ESRGAN strategy: tile if image requires more than 4 tiles
        int tilesX = (int) Math.ceil((double) imageWidth / EFFECTIVE_TILE_STEP);
        int tilesY = (int) Math.ceil((double) imageHeight / EFFECTIVE_TILE_STEP);
        int totalTiles = tilesX * tilesY;
        
        boolean shouldTile = totalTiles > 4;
        
        Log.d(TAG, "Image " + imageWidth + "x" + imageHeight + 
              " would require " + totalTiles + " tiles, should use hardware parallel tiling: " + shouldTile);
        
        return shouldTile;
    }
}