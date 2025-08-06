package com.example.sr_poc.tiling;

import android.graphics.Bitmap;
import android.util.Log;

import com.example.sr_poc.ThreadSafeSRProcessor;
import com.example.sr_poc.ConfigManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * High-performance parallel tile processor using 6-thread architecture
 * Designed to maximize utilization of MT8195's 8-core CPU
 * Target: 12 tiles in 2 parallel rounds = ~204ms total time
 */
public class ParallelTileProcessor {
    
    private static final String TAG = "ParallelTileProcessor";
    
    // Optimal thread configuration for CPU parallel processing
    private static final int PARALLEL_THREADS = 4; // CPU 8-core supports true parallelism
    
    private final ThreadSafeSRProcessor srProcessor;
    private final ConfigManager configManager;
    private final int scaleFactor;
    
    // Thread pool for parallel tile processing
    private ExecutorService tileExecutor;
    
    public interface ParallelProcessingCallback {
        void onProgress(String message);
        void onTileProgress(int completedTiles, int totalTiles);
        void onResult(Bitmap resultBitmap, long totalTime);
        void onError(String error);
    }
    
    public ParallelTileProcessor(ThreadSafeSRProcessor srProcessor, ConfigManager configManager) {
        this.srProcessor = srProcessor;
        this.configManager = configManager;
        this.scaleFactor = configManager.getExpectedScaleFactor();
        
        // Initialize 4-thread executor service for optimal CPU parallel performance
        this.tileExecutor = Executors.newFixedThreadPool(PARALLEL_THREADS);
        
        Log.d(TAG, "ParallelTileProcessor initialized with " + PARALLEL_THREADS + " threads for CPU parallel processing");
    }
    
    /**
     * Process image using 4-thread CPU parallel tile processing
     * Target performance: 12 tiles in 3 rounds = ~525ms (3 × 175ms)
     */
    public void processImage(Bitmap inputBitmap, ThreadSafeSRProcessor.ProcessingMode mode, 
                           boolean forceTiling, ParallelProcessingCallback callback) {
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
        
        // Proceed with parallel tiling
        callback.onProgress("Starting 4-thread CPU parallel tile processing");
        processWithParallelTiling(inputBitmap, mode, callback, startTime);
    }
    
    /**
     * Core parallel tiling implementation
     * 12 tiles processed in 2 rounds with 6 threads
     */
    private void processWithParallelTiling(Bitmap inputBitmap, ThreadSafeSRProcessor.ProcessingMode mode,
                                         ParallelProcessingCallback callback, long startTime) {
        try {
            // Extract tiles
            callback.onProgress("Extracting tiles from image");
            
            TileExtractor extractor = new TileExtractor(
                srProcessor.getModelInputWidth(),
                srProcessor.getModelInputHeight(),
                configManager.getOverlapPixels()
            );
            
            List<TileExtractor.TileInfo> tiles = extractor.extractTiles(inputBitmap);
            
            if (tiles.isEmpty()) {
                callback.onError("Failed to extract tiles");
                return;
            }
            
            Log.d(TAG, "Extracted " + tiles.size() + " tiles for 4-thread CPU parallel processing");
            callback.onProgress("Processing " + tiles.size() + " tiles with " + PARALLEL_THREADS + " CPU threads");
            
            // Process tiles in parallel
            long processingStart = System.currentTimeMillis();
            List<Bitmap> processedTiles = processParallelTiles(tiles, mode, callback);
            long processingTime = System.currentTimeMillis() - processingStart;
            
            if (processedTiles == null || processedTiles.size() != tiles.size()) {
                callback.onError("Parallel tile processing failed");
                cleanupTiles(tiles);
                return;
            }
            
            Log.d(TAG, "Parallel processing completed in " + processingTime + "ms");
            
            // Merge results
            callback.onProgress("Merging tiles into final result");
            
            TileMerger merger = new TileMerger(
                inputBitmap.getWidth(), inputBitmap.getHeight(),
                srProcessor.getModelInputWidth(), srProcessor.getModelInputHeight(),
                configManager.getOverlapPixels(), scaleFactor
            );
            
            Bitmap result = merger.mergeTilesParallel(tiles, processedTiles);
            
            // Clean up intermediate results
            for (Bitmap tile : processedTiles) {
                if (tile != null && !tile.isRecycled()) {
                    tile.recycle();
                }
            }
            cleanupTiles(tiles);
            
            if (result != null) {
                long totalTime = System.currentTimeMillis() - startTime;
                Log.d(TAG, "4-thread CPU parallel tile processing completed in " + totalTime + "ms");
                callback.onResult(result, totalTime);
            } else {
                callback.onError("Failed to merge tiles");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error during parallel tile processing", e);
            callback.onError("Parallel tile processing failed: " + e.getMessage());
        }
    }
    
    /**
     * Process tiles using 4-thread CPU parallel execution
     * 12 tiles → 3 rounds of 4 parallel tasks = ~525ms target (3 × 175ms)
     */
    private List<Bitmap> processParallelTiles(List<TileExtractor.TileInfo> tiles, 
                                            ThreadSafeSRProcessor.ProcessingMode mode,
                                            ParallelProcessingCallback callback) {
        
        List<Bitmap> results = new ArrayList<>(tiles.size());
        for (int i = 0; i < tiles.size(); i++) {
            results.add(null); // Pre-allocate with nulls
        }
        
        final AtomicInteger completedCount = new AtomicInteger(0);
        final AtomicReference<String> error = new AtomicReference<>();
        final CountDownLatch completionLatch = new CountDownLatch(tiles.size());
        
        Log.d(TAG, "Starting CPU parallel execution of " + tiles.size() + " tiles with " + PARALLEL_THREADS + " threads");
        
        // Submit all tile processing tasks
        for (int i = 0; i < tiles.size(); i++) {
            final int tileIndex = i;
            final TileExtractor.TileInfo tileInfo = tiles.get(i);
            
            Callable<Void> tileTask = new Callable<Void>() {
                @Override
                public Void call() {
                    String threadName = Thread.currentThread().getName();
                    long tileStart = System.currentTimeMillis();
                    
                    Log.d(TAG, "Thread " + threadName + " processing tile " + tileIndex);
                    
                    try {
                        // Process single tile
                        final AtomicReference<Bitmap> tileResult = new AtomicReference<>();
                        final CountDownLatch tileLatch = new CountDownLatch(1);
                        final AtomicReference<String> tileError = new AtomicReference<>();
                        
                        ThreadSafeSRProcessor.InferenceCallback tileCallback = new ThreadSafeSRProcessor.InferenceCallback() {
                            @Override
                            public void onResult(Bitmap resultBitmap, long inferenceTime) {
                                tileResult.set(resultBitmap);
                                tileLatch.countDown();
                            }
                            
                            @Override
                            public void onError(String errorMsg) {
                                tileError.set(errorMsg);
                                tileLatch.countDown();
                            }
                        };
                        
                        // Execute tile processing
                        if (mode != null) {
                            srProcessor.processImageWithMode(tileInfo.bitmap, mode, tileCallback);
                        } else {
                            srProcessor.processImage(tileInfo.bitmap, tileCallback);
                        }
                        
                        // Wait for tile completion
                        tileLatch.await();
                        
                        if (tileError.get() != null) {
                            Log.e(TAG, "Tile " + tileIndex + " processing failed: " + tileError.get());
                            error.set("Tile processing failed: " + tileError.get());
                        } else {
                            results.set(tileIndex, tileResult.get());
                            int completed = completedCount.incrementAndGet();
                            
                            long tileTime = System.currentTimeMillis() - tileStart;
                            Log.d(TAG, "Thread " + threadName + " completed tile " + tileIndex + " in " + tileTime + "ms");
                            
                            // Report progress
                            callback.onTileProgress(completed, tiles.size());
                        }
                        
                    } catch (Exception e) {
                        Log.e(TAG, "Exception processing tile " + tileIndex, e);
                        error.set("Tile processing exception: " + e.getMessage());
                    } finally {
                        completionLatch.countDown();
                    }
                    
                    return null;
                }
            };
            
            // Submit task to thread pool
            tileExecutor.submit(tileTask);
        }
        
        // Wait for all tiles to complete
        try {
            completionLatch.await();
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while waiting for parallel tiles", e);
            Thread.currentThread().interrupt();
            return null;
        }
        
        // Check for errors
        if (error.get() != null) {
            Log.e(TAG, "Parallel processing failed: " + error.get());
            return null;
        }
        
        Log.d(TAG, "All " + tiles.size() + " tiles completed successfully");
        return results;
    }
    
    /**
     * Process directly without tiling
     */
    private void processDirectly(Bitmap inputBitmap, ThreadSafeSRProcessor.ProcessingMode mode,
                               ParallelProcessingCallback callback, long startTime) {
        final AtomicReference<Bitmap> result = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> error = new AtomicReference<>();
        
        ThreadSafeSRProcessor.InferenceCallback inferenceCallback = new ThreadSafeSRProcessor.InferenceCallback() {
            @Override
            public void onResult(Bitmap resultBitmap, long inferenceTime) {
                result.set(resultBitmap);
                latch.countDown();
            }
            
            @Override
            public void onError(String errorMsg) {
                error.set(errorMsg);
                latch.countDown();
            }
        };
        
        if (mode != null) {
            srProcessor.processImageWithMode(inputBitmap, mode, inferenceCallback);
        } else {
            srProcessor.processImage(inputBitmap, inferenceCallback);
        }
        
        try {
            latch.await();
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while waiting for direct processing", e);
            Thread.currentThread().interrupt();
            callback.onError("Processing interrupted");
            return;
        }
        
        if (error.get() != null) {
            callback.onError(error.get());
        } else {
            long totalTime = System.currentTimeMillis() - startTime;
            callback.onResult(result.get(), totalTime);
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
     * Shutdown the parallel processor
     */
    public void shutdown() {
        if (tileExecutor != null && !tileExecutor.isShutdown()) {
            tileExecutor.shutdown();
            Log.d(TAG, "ParallelTileProcessor executor shutdown");
        }
    }
    
    /**
     * Check if parallel tiling would be beneficial
     */
    public static boolean shouldUseParallelTiling(int imageWidth, int imageHeight, 
                                                int modelInputWidth, int modelInputHeight) {
        return TileExtractor.shouldUseTiling(imageWidth, imageHeight, 
                                           Math.min(modelInputWidth, modelInputHeight) * 2);
    }
}