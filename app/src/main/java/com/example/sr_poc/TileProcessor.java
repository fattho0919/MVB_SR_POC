package com.example.sr_poc;

import android.graphics.Bitmap;
import android.util.Log;
import android.content.Context;
import com.example.sr_poc.pool.PooledBitmapFactory;
import com.example.sr_poc.pool.LargeBitmapProcessor;

import java.util.ArrayList;
import java.util.List;

public class TileProcessor {
    
    private static final String TAG = "TileProcessor";
    
    private ThreadSafeSRProcessor srProcessor;
    private ConfigManager configManager;
    private PooledBitmapFactory bitmapFactory;
    private LargeBitmapProcessor largeBitmapProcessor;
    private int tileSize; // 動態設定的tile尺寸
    private int outputScale; // 動態計算的輸出倍率
    private int overlapPixels; // 來自配置的overlap像素數
    
    // Track last tile count for reporting
    private int lastTileCount = 0;
    
    public TileProcessor(ThreadSafeSRProcessor processor) {
        this.srProcessor = processor;
        
        // Initialize pooled bitmap factory and large bitmap processor
        if (processor != null) {
            try {
                // Get context from processor via reflection
                java.lang.reflect.Field contextField = processor.getClass().getDeclaredField("context");
                contextField.setAccessible(true);
                Context context = (Context) contextField.get(processor);
                this.bitmapFactory = new PooledBitmapFactory(context);
                this.largeBitmapProcessor = new LargeBitmapProcessor(context);
            } catch (Exception e) {
                Log.w(TAG, "Failed to initialize bitmap processors", e);
            }
        }
        
        // 使用預設overlap值（向後兼容）
        this.overlapPixels = 32;
        
        // 動態獲取模型尺寸
        int inputWidth = processor.getModelInputWidth();
        int inputHeight = processor.getModelInputHeight();
        int outputWidth = processor.getModelOutputWidth();
        int outputHeight = processor.getModelOutputHeight();
        
        // 使用輸入尺寸的較小值作為tile尺寸
        this.tileSize = Math.min(inputWidth, inputHeight);
        
        // 計算輸出倍率
        this.outputScale = Math.max(outputWidth / inputWidth, outputHeight / inputHeight);
        
        Log.d(TAG, "TileProcessor initialized - Tile size: " + tileSize + 
                  ", Output scale: " + outputScale + "x, Overlap: " + overlapPixels + "px (default)");
    }
    
    public TileProcessor(ThreadSafeSRProcessor processor, ConfigManager configManager) {
        this.srProcessor = processor;
        this.configManager = configManager;
        
        // Initialize pooled bitmap factory and large bitmap processor
        if (processor != null) {
            try {
                java.lang.reflect.Field contextField = processor.getClass().getDeclaredField("context");
                contextField.setAccessible(true);
                Context context = (Context) contextField.get(processor);
                this.bitmapFactory = new PooledBitmapFactory(context);
                this.largeBitmapProcessor = new LargeBitmapProcessor(context);
                // Configure overlap from config
                this.largeBitmapProcessor.setTileOverlap(this.overlapPixels);
            } catch (Exception e) {
                Log.w(TAG, "Failed to initialize bitmap processors", e);
            }
        }
        
        // 從配置獲取overlap像素數
        this.overlapPixels = configManager.getOverlapPixels();
        
        // 動態獲取模型尺寸
        int inputWidth = processor.getModelInputWidth();
        int inputHeight = processor.getModelInputHeight();
        int outputWidth = processor.getModelOutputWidth();
        int outputHeight = processor.getModelOutputHeight();
        
        // 使用輸入尺寸的較小值作為tile尺寸
        this.tileSize = Math.min(inputWidth, inputHeight);
        
        // 計算輸出倍率
        this.outputScale = Math.max(outputWidth / inputWidth, outputHeight / inputHeight);
        
        Log.d(TAG, "TileProcessor initialized - Tile size: " + tileSize + 
                  ", Output scale: " + outputScale + "x, Overlap: " + overlapPixels + "px");
    }
    
    /**
     * 將大圖片分塊處理以避免記憶體溢出
     */
    public Bitmap processByTiles(Bitmap inputBitmap, ProcessCallback callback) {
        return processByTiles(inputBitmap, ThreadSafeSRProcessor.ProcessingMode.CPU, callback);
    }
    
    public Bitmap processByTiles(Bitmap inputBitmap, ThreadSafeSRProcessor.ProcessingMode mode, ProcessCallback callback) {
        if (inputBitmap == null) {
            Log.e(TAG, "Input bitmap is null");
            return null;
        }
        
        int inputWidth = inputBitmap.getWidth();
        int inputHeight = inputBitmap.getHeight();
        
        Log.d(TAG, "Processing " + inputWidth + "x" + inputHeight + " image by tiles");
        
        // 計算有效的tile步長 (去除overlap)
        int effectiveTileStep = tileSize - overlapPixels;
        
        // 計算分塊數量
        int tilesX = (int) Math.ceil((double) inputWidth / effectiveTileStep);
        int tilesY = (int) Math.ceil((double) inputHeight / effectiveTileStep);
        
        Log.d(TAG, "Will process " + tilesX + "x" + tilesY + " tiles");
        
        // 計算正確的輸出尺寸
        int effectiveOutputStep = effectiveTileStep * outputScale;
        int lastTileInputX = Math.min(tileSize, inputWidth - (tilesX - 1) * effectiveTileStep);
        int lastTileInputY = Math.min(tileSize, inputHeight - (tilesY - 1) * effectiveTileStep);
        
        int outputWidth = (tilesX - 1) * effectiveOutputStep + (lastTileInputX * outputScale);
        int outputHeight = (tilesY - 1) * effectiveOutputStep + (lastTileInputY * outputScale);
        
        Log.d(TAG, "Calculated output size: " + outputWidth + "x" + outputHeight);
        
        // Use pooled bitmap factory for result bitmap (Story 1.3)
        Bitmap resultBitmap = (bitmapFactory != null) ? 
                bitmapFactory.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888) :
                Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(resultBitmap);
        
        int processedTiles = 0;
        int totalTiles = tilesX * tilesY;
        long totalTileTime = 0;
        
        // Store tile count for reporting
        lastTileCount = totalTiles;
        
        for (int y = 0; y < tilesY; y++) {
            for (int x = 0; x < tilesX; x++) {
                long tileStartTime = System.currentTimeMillis();
                
                // 計算當前分塊的位置和大小
                int tileLeft = (x == 0) ? 0 : x * effectiveTileStep;
                int tileTop = (y == 0) ? 0 : y * effectiveTileStep;
                int tileRight = Math.min(tileLeft + tileSize, inputWidth);
                int tileBottom = Math.min(tileTop + tileSize, inputHeight);
                
                int tileWidth = tileRight - tileLeft;
                int tileHeight = tileBottom - tileTop;
                
                Log.d(TAG, "Processing tile (" + x + "," + y + "): " + 
                          tileLeft + "," + tileTop + " to " + tileRight + "," + tileBottom +
                          " size: " + tileWidth + "x" + tileHeight);
                
                // 提取分塊，確保符合模型輸入尺寸
                Bitmap tileBitmap;
                if (tileWidth == tileSize && tileHeight == tileSize) {
                    // Use direct crop from source bitmap
                    tileBitmap = Bitmap.createBitmap(inputBitmap, tileLeft, tileTop, tileWidth, tileHeight);
                } else {
                    // 對於邊界tile，需要padding到模型輸入尺寸
                    // Use pooled bitmap factory for tile bitmap (Story 1.3)
                    tileBitmap = (bitmapFactory != null) ?
                            bitmapFactory.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888) :
                            Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas tileCanvas = new android.graphics.Canvas(tileBitmap);
                    
                    // Create source tile (direct crop)
                    Bitmap sourceTile = Bitmap.createBitmap(inputBitmap, tileLeft, tileTop, tileWidth, tileHeight);
                    tileCanvas.drawBitmap(sourceTile, 0, 0, null);
                    
                    // 如果需要padding，複製邊緣像素
                    if (tileWidth < tileSize || tileHeight < tileSize) {
                        android.graphics.Paint paint = new android.graphics.Paint();
                        paint.setFilterBitmap(false);
                        
                        if (tileWidth < tileSize) {
                            android.graphics.Rect srcRight = new android.graphics.Rect(tileWidth-1, 0, tileWidth, tileHeight);
                            android.graphics.Rect dstRight = new android.graphics.Rect(tileWidth, 0, tileSize, tileHeight);
                            tileCanvas.drawBitmap(sourceTile, srcRight, dstRight, paint);
                        }
                        
                        if (tileHeight < tileSize) {
                            android.graphics.Rect srcBottom = new android.graphics.Rect(0, tileHeight-1, tileSize, tileHeight);
                            android.graphics.Rect dstBottom = new android.graphics.Rect(0, tileHeight, tileSize, tileSize);
                            tileCanvas.drawBitmap(tileBitmap, srcBottom, dstBottom, paint);
                        }
                    }
                    
                    // Recycle source tile (not pooled)
                    sourceTile.recycle();
                }
                
                // 處理分塊
                final Object lock = new Object();
                final Bitmap[] result = new Bitmap[1];
                final boolean[] completed = new boolean[1];
                
                srProcessor.processImage(tileBitmap, new ThreadSafeSRProcessor.InferenceCallback() {
                    @Override
                    public void onResult(Bitmap resultBitmap, long inferenceTime) {
                        synchronized (lock) {
                            result[0] = resultBitmap;
                            completed[0] = true;
                            lock.notify();
                        }
                    }
                    
                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "Tile processing failed: " + error);
                        synchronized (lock) {
                            result[0] = null;
                            completed[0] = true;
                            lock.notify();
                        }
                    }
                });
                
                // 等待處理完成
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
                
                Bitmap processedTile = result[0];
                if (processedTile != null) {
                    // 計算在輸出畫布上的位置
                    int outputLeft = x * effectiveOutputStep;
                    int outputTop = y * effectiveOutputStep;
                    
                    // 確定要裁剪的區域以避免overlap
                    int cropLeft = 0;
                    int cropTop = 0;
                    int cropRight = processedTile.getWidth();
                    int cropBottom = processedTile.getHeight();
                    
                    int halfOverlap = (overlapPixels * outputScale) / 2;
                    
                    if (x == 0) {
                        cropLeft = 0;
                        if (tilesX > 1) {
                            cropRight = processedTile.getWidth() - halfOverlap;
                        }
                    } else if (x == tilesX - 1) {
                        cropLeft = halfOverlap;
                        int expectedLastTileOutputWidth = lastTileInputX * outputScale;
                        cropRight = Math.min(processedTile.getWidth(), expectedLastTileOutputWidth);
                    } else {
                        cropLeft = halfOverlap;
                        cropRight = processedTile.getWidth() - halfOverlap;
                    }
                    
                    if (y == 0) {
                        cropTop = 0;
                        if (tilesY > 1) {
                            cropBottom = processedTile.getHeight() - halfOverlap;
                        }
                    } else if (y == tilesY - 1) {
                        cropTop = halfOverlap;
                        int expectedLastTileOutputHeight = lastTileInputY * outputScale;
                        cropBottom = Math.min(processedTile.getHeight(), expectedLastTileOutputHeight);
                    } else {
                        cropTop = halfOverlap;
                        cropBottom = processedTile.getHeight() - halfOverlap;
                    }
                    
                    int actualCropWidth = cropRight - cropLeft;
                    int actualCropHeight = cropBottom - cropTop;
                    
                    int availableWidth = outputWidth - outputLeft;
                    int availableHeight = outputHeight - outputTop;
                    
                    actualCropWidth = Math.min(actualCropWidth, availableWidth);
                    actualCropHeight = Math.min(actualCropHeight, availableHeight);
                    
                    if (actualCropWidth > 0 && actualCropHeight > 0) {
                        if (cropLeft + actualCropWidth <= processedTile.getWidth() && 
                            cropTop + actualCropHeight <= processedTile.getHeight()) {
                            
                            // Use pooled bitmap factory for cropped tile (Story 1.3)
                            Bitmap croppedTile = (bitmapFactory != null) ?
                                    bitmapFactory.createCroppedBitmap(processedTile, cropLeft, cropTop, actualCropWidth, actualCropHeight) :
                                    Bitmap.createBitmap(processedTile, cropLeft, cropTop, actualCropWidth, actualCropHeight);
                            canvas.drawBitmap(croppedTile, outputLeft, outputTop, null);
                            // Release cropped tile back to pool if pooled
                            if (bitmapFactory != null) {
                                bitmapFactory.releaseBitmap(croppedTile);
                            } else {
                                croppedTile.recycle();
                            }
                        }
                    }
                    
                    // Recycle processed tile (comes from SR processor)
                    processedTile.recycle();
                    processedTiles++;
                }
                
                // Release tile bitmap back to pool if pooled
                if (bitmapFactory != null && tileWidth != tileSize) {
                    // Only pooled bitmaps (padded tiles) go back to pool
                    bitmapFactory.releaseBitmap(tileBitmap);
                } else {
                    tileBitmap.recycle();
                }
                
                long tileTime = System.currentTimeMillis() - tileStartTime;
                totalTileTime += tileTime;
                
                Log.d(TAG, "Tile (" + x + "," + y + ") completed in " + tileTime + "ms");
                
                // 更新進度
                if (callback != null) {
                    callback.onProgress(processedTiles, totalTiles);
                }
            }
        }
        
        Log.d(TAG, "Tile processing completed: " + processedTiles + "/" + totalTiles + " tiles successful");
        Log.d(TAG, "Total tile processing time: " + totalTileTime + "ms, Average: " + (totalTileTime/Math.max(1,processedTiles)) + "ms/tile");
        Log.d(TAG, "Final result bitmap size: " + resultBitmap.getWidth() + "x" + resultBitmap.getHeight());
        
        return resultBitmap;
    }
    
    public interface ProcessCallback {
        void onProgress(int completed, int total);
    }
    
    /**
     * Get the number of tiles used in the last processing
     */
    public int getLastTileCount() {
        return lastTileCount;
    }
    
    /**
     * 檢查是否需要分塊處理
     */
    public static boolean shouldUseTileProcessing(Bitmap bitmap) {
        if (bitmap == null) return false;
        
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        
        // 計算超解析度後的記憶體需求（假設最多4倍放大）
        long outputPixels = (long) width * height * 16; // 4x寬 * 4x高 = 16倍像素
        long estimatedMemoryMB = outputPixels * 4 / (1024 * 1024); // ARGB每像素4字節
        
        Log.d(TAG, "Input: " + width + "x" + height + 
                   ", estimated output memory: " + estimatedMemoryMB + "MB");
        
        // 動態閾值：基於可用記憶體和圖片大小
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long availableMemory = maxMemory - (runtime.totalMemory() - runtime.freeMemory());
        long availableMemoryMB = availableMemory / (1024 * 1024);
        
        // 如果預估記憶體需求超過可用記憶體的60%，或輸入圖片太大，使用tiling
        boolean useTiling = estimatedMemoryMB > (availableMemoryMB * 0.6) || 
                           width > 2048 || height > 2048;
        
        Log.d(TAG, "Available memory: " + availableMemoryMB + "MB, Should use tile processing: " + useTiling);
        return useTiling;
    }
    
    /**
     * 基於配置的檢查是否需要分塊處理
     */
    public static boolean shouldUseTileProcessing(Bitmap bitmap, ConfigManager config) {
        if (bitmap == null) return false;
        
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        
        // 計算超解析度後的記憶體需求
        int scaleFactor = config.getExpectedScaleFactor();
        long outputPixels = (long) width * height * scaleFactor * scaleFactor;
        long estimatedMemoryMB = outputPixels * 4 / (1024 * 1024); // ARGB每像素4字節
        
        Log.d(TAG, "Input: " + width + "x" + height + 
                   ", estimated output memory: " + estimatedMemoryMB + "MB");
        
        // 使用配置中的閾值
        double memoryThreshold = config.getMemoryThresholdPercentage();
        int maxSizeWithoutTiling = config.getMaxInputSizeWithoutTiling();
        int forceTilingAboveMb = config.getForceTilingAboveMb();
        
        // 動態閾值：基於可用記憶體和圖片大小
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long availableMemory = maxMemory - (runtime.totalMemory() - runtime.freeMemory());
        long availableMemoryMB = availableMemory / (1024 * 1024);
        
        // 基於配置參數決定是否使用tiling
        boolean useTiling = estimatedMemoryMB > (availableMemoryMB * memoryThreshold) || 
                           width > maxSizeWithoutTiling || height > maxSizeWithoutTiling ||
                           estimatedMemoryMB > forceTilingAboveMb;
        
        Log.d(TAG, "Available memory: " + availableMemoryMB + "MB, " +
                   "Threshold: " + (int)(memoryThreshold * 100) + "%, " +
                   "Max size: " + maxSizeWithoutTiling + "px, " +
                   "Force above: " + forceTilingAboveMb + "MB, " +
                   "Should use tile processing: " + useTiling);
        return useTiling;
    }
    
    /**
     * Process a large image using optimized tiling with LargeBitmapProcessor.
     * This method is specifically designed for handling 4K+ images.
     * 
     * @param inputBitmap Input bitmap to process
     * @param mode Processing mode (GPU/CPU/NPU)
     * @param callback Progress callback
     * @return Processed output bitmap
     */
    public Bitmap processLargeImage(Bitmap inputBitmap, 
                                   ThreadSafeSRProcessor.ProcessingMode mode,
                                   ProcessCallback callback) {
        if (largeBitmapProcessor == null) {
            Log.w(TAG, "LargeBitmapProcessor not initialized, falling back to standard processing");
            return processByTiles(inputBitmap, mode, callback);
        }
        
        int inputWidth = inputBitmap.getWidth();
        int inputHeight = inputBitmap.getHeight();
        
        Log.d(TAG, String.format("Processing large image: %dx%d using LargeBitmapProcessor", 
                                inputWidth, inputHeight));
        
        // Check if image exceeds GPU texture limits
        boolean needsSpecialHandling = largeBitmapProcessor.needsTiling(inputWidth, inputHeight);
        
        if (!needsSpecialHandling) {
            // Image fits within texture limits, use standard tiling
            return processByTiles(inputBitmap, mode, callback);
        }
        
        // Calculate optimal tile size based on memory and GPU constraints
        int optimalTileSize = largeBitmapProcessor.calculateOptimalTileSize(
            inputWidth, inputHeight, tileSize);
        
        // Get tile layout
        List<LargeBitmapProcessor.TileInfo> tileInfos = 
            largeBitmapProcessor.calculateTileLayout(inputWidth, inputHeight, 
                                                    optimalTileSize, outputScale);
        
        // Process each tile
        List<Bitmap> processedTiles = new ArrayList<>();
        int totalTiles = tileInfos.size();
        int processedCount = 0;
        
        Log.d(TAG, String.format("Processing %d tiles with size %d", totalTiles, optimalTileSize));
        
        for (LargeBitmapProcessor.TileInfo tileInfo : tileInfos) {
            try {
                // Extract tile
                Bitmap tile = largeBitmapProcessor.extractTile(inputBitmap, tileInfo, optimalTileSize);
                
                // Process tile using SR processor with synchronous wrapper
                final Bitmap[] processedTileHolder = new Bitmap[1];
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                
                srProcessor.processImageWithMode(tile, mode, new ThreadSafeSRProcessor.InferenceCallback() {
                    @Override
                    public void onResult(Bitmap result, long inferenceTime) {
                        processedTileHolder[0] = result;
                        latch.countDown();
                    }
                    
                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "Error processing tile: " + error);
                        latch.countDown();
                    }
                });
                
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interrupted while waiting for tile processing", e);
                }
                
                Bitmap processedTile = processedTileHolder[0];
                
                if (processedTile != null) {
                    processedTiles.add(processedTile);
                } else {
                    // Create empty tile if processing failed
                    Bitmap emptyTile = bitmapFactory.createBitmap(
                        optimalTileSize * outputScale, 
                        optimalTileSize * outputScale, 
                        Bitmap.Config.ARGB_8888);
                    processedTiles.add(emptyTile);
                }
                
                // Release input tile
                bitmapFactory.releaseBitmap(tile);
                
                processedCount++;
                
                // Report progress
                if (callback != null) {
                    callback.onProgress(processedCount, totalTiles);
                }
                
            } catch (OutOfMemoryError e) {
                Log.e(TAG, "OOM processing tile, attempting quality reduction", e);
                
                // Try to recover by reducing quality
                System.gc();
                
                // Release already processed tiles to free memory
                for (Bitmap processed : processedTiles) {
                    if (processed != null) {
                        bitmapFactory.releaseBitmap(processed);
                    }
                }
                processedTiles.clear();
                
                // Reduce input quality and retry
                Bitmap reducedInput = largeBitmapProcessor.reduceQualityForMemory(inputBitmap, 100);
                return processByTiles(reducedInput, mode, callback);
            }
        }
        
        // Calculate output dimensions
        int outputWidth = inputWidth * outputScale;
        int outputHeight = inputHeight * outputScale;
        
        // Merge tiles into final output
        Bitmap result = largeBitmapProcessor.mergeTiles(processedTiles, tileInfos, 
                                                       outputWidth, outputHeight, outputScale);
        
        // Release processed tiles
        largeBitmapProcessor.releaseTiles(processedTiles);
        
        // Store tile count for reporting
        lastTileCount = totalTiles;
        
        Log.d(TAG, String.format("Large image processing complete: %dx%d -> %dx%d using %d tiles",
                               inputWidth, inputHeight, outputWidth, outputHeight, totalTiles));
        
        return result;
    }
    
    /**
     * Checks if the current device can handle a given image size.
     * 
     * @param width Image width
     * @param height Image height
     * @return true if the image can be processed
     */
    public boolean canProcessImageSize(int width, int height) {
        if (largeBitmapProcessor == null) {
            // Conservative estimate without LargeBitmapProcessor
            return width <= 4096 && height <= 4096;
        }
        
        int maxTextureSize = largeBitmapProcessor.getMaxTextureSize();
        
        // Check if we can process with tiling
        if (width > maxTextureSize || height > maxTextureSize) {
            // Calculate required tiles
            int tilesNeeded = ((width + maxTextureSize - 1) / maxTextureSize) * 
                            ((height + maxTextureSize - 1) / maxTextureSize);
            
            // Limit to reasonable tile count
            return tilesNeeded <= 64;
        }
        
        return true;
    }
}