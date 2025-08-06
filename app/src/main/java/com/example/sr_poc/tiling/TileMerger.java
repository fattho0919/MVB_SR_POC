package com.example.sr_poc.tiling;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;

/**
 * Efficiently merges processed tiles back into a single result image
 * Supports both serial and parallel merging
 */
public class TileMerger {
    
    private static final String TAG = "TileMerger";
    
    private final int originalImageWidth;
    private final int originalImageHeight;
    private final int tileWidth;
    private final int tileHeight;
    private final int overlapPixels;
    private final int scaleFactor;
    
    public TileMerger(int originalImageWidth, int originalImageHeight, 
                     int tileWidth, int tileHeight, int overlapPixels, int scaleFactor) {
        this.originalImageWidth = originalImageWidth;
        this.originalImageHeight = originalImageHeight;
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        this.overlapPixels = overlapPixels;
        this.scaleFactor = scaleFactor;
        
        Log.d(TAG, "TileMerger created - Original: " + originalImageWidth + "x" + originalImageHeight + 
              ", Tile: " + tileWidth + "x" + tileHeight + ", Overlap: " + overlapPixels + "px, Scale: " + scaleFactor + "x");
    }
    
    /**
     * Merge tiles into final result image
     */
    public Bitmap mergeTiles(List<TileExtractor.TileInfo> originalTiles, List<Bitmap> processedTiles) {
        if (originalTiles.size() != processedTiles.size()) {
            Log.e(TAG, "Mismatch between original tiles (" + originalTiles.size() + 
                      ") and processed tiles (" + processedTiles.size() + ")");
            return null;
        }
        
        if (originalTiles.isEmpty()) {
            Log.e(TAG, "No tiles to merge");
            return null;
        }
        
        // Calculate final output dimensions
        int outputWidth = originalImageWidth * scaleFactor;
        int outputHeight = originalImageHeight * scaleFactor;
        
        Log.d(TAG, "Merging " + processedTiles.size() + " tiles into " + outputWidth + "x" + outputHeight + " result");
        
        // Create result bitmap
        Bitmap resultBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(resultBitmap);
        
        // Calculate grid dimensions
        int effectiveStepX = tileWidth - overlapPixels;
        int effectiveStepY = tileHeight - overlapPixels;
        int tilesX = (int) Math.ceil((double) originalImageWidth / effectiveStepX);
        
        // Merge each tile
        for (int i = 0; i < originalTiles.size(); i++) {
            TileExtractor.TileInfo originalTile = originalTiles.get(i);
            Bitmap processedTile = processedTiles.get(i);
            
            if (processedTile == null || processedTile.isRecycled()) {
                Log.w(TAG, "Skipping null/recycled tile at index " + i);
                continue;
            }
            
            // Calculate tile position in grid
            int tileX = i % tilesX;
            int tileY = i / tilesX;
            
            // Calculate output position
            int outputX = originalTile.originalX * scaleFactor;
            int outputY = originalTile.originalY * scaleFactor;
            
            // Calculate crop area to handle overlaps
            Rect cropRect = calculateCropRect(originalTile, processedTile, tileX, tileY, tilesX);
            Rect dstRect = new Rect(outputX + cropRect.left, outputY + cropRect.top,
                                   outputX + cropRect.right, outputY + cropRect.bottom);
            
            // Draw tile to result canvas
            canvas.drawBitmap(processedTile, cropRect, dstRect, null);
            
            Log.v(TAG, "Merged tile " + i + " (" + tileX + "," + tileY + ") " +
                      "crop: " + cropRect + " -> dst: " + dstRect);
        }
        
        Log.d(TAG, "Successfully merged tiles into " + outputWidth + "x" + outputHeight + " result");
        return resultBitmap;
    }
    
    /**
     * Merge tiles with parallel processing for better performance
     */
    public Bitmap mergeTilesParallel(List<TileExtractor.TileInfo> originalTiles, List<Bitmap> processedTiles) {
        if (originalTiles.size() != processedTiles.size()) {
            Log.e(TAG, "Mismatch between original tiles (" + originalTiles.size() + 
                      ") and processed tiles (" + processedTiles.size() + ")");
            return null;
        }
        
        if (originalTiles.isEmpty()) {
            Log.e(TAG, "No tiles to merge");
            return null;
        }
        
        long startTime = System.currentTimeMillis();
        
        // Calculate final output dimensions  
        int outputWidth = originalImageWidth * scaleFactor;
        int outputHeight = originalImageHeight * scaleFactor;
        
        Log.d(TAG, "Starting parallel merge of " + processedTiles.size() + " tiles into " + outputWidth + "x" + outputHeight + " result");
        
        // Create result bitmap
        Bitmap resultBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(resultBitmap);
        
        // Calculate grid dimensions
        int effectiveStepX = tileWidth - overlapPixels;
        int effectiveStepY = tileHeight - overlapPixels;
        int tilesX = (int) Math.ceil((double) originalImageWidth / effectiveStepX);
        
        // Use multiple threads for tile processing (but synchronize canvas operations)
        int numThreads = Math.min(4, originalTiles.size());
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        try {
            // Divide tiles into chunks for parallel processing
            int tilesPerThread = (int) Math.ceil((double) originalTiles.size() / numThreads);
            List<Future<Void>> futures = new java.util.ArrayList<>();
            
            for (int threadId = 0; threadId < numThreads; threadId++) {
                final int startIdx = threadId * tilesPerThread;
                final int endIdx = Math.min(startIdx + tilesPerThread, originalTiles.size());
                
                if (startIdx >= endIdx) break;
                
                Callable<Void> mergeTask = new Callable<Void>() {
                    @Override
                    public Void call() {
                        // Process tiles in this chunk
                        for (int i = startIdx; i < endIdx; i++) {
                            TileExtractor.TileInfo originalTile = originalTiles.get(i);
                            Bitmap processedTile = processedTiles.get(i);
                            
                            if (processedTile == null || processedTile.isRecycled()) {
                                Log.w(TAG, "Skipping null/recycled tile at index " + i);
                                continue;
                            }
                            
                            // Calculate tile position in grid
                            int tileX = i % tilesX;
                            int tileY = i / tilesX;
                            
                            // Calculate output position
                            int outputX = originalTile.originalX * scaleFactor;
                            int outputY = originalTile.originalY * scaleFactor;
                            
                            // Calculate crop area to handle overlaps
                            Rect cropRect = calculateCropRect(originalTile, processedTile, tileX, tileY, tilesX);
                            Rect dstRect = new Rect(outputX + cropRect.left, outputY + cropRect.top,
                                                   outputX + cropRect.right, outputY + cropRect.bottom);
                            
                            // Synchronize canvas operations
                            synchronized (canvas) {
                                canvas.drawBitmap(processedTile, cropRect, dstRect, null);
                                Log.v(TAG, "Parallel merged tile " + i + " (" + tileX + "," + tileY + ") " +
                                          "crop: " + cropRect + " -> dst: " + dstRect);
                            }
                        }
                        return null;
                    }
                };
                
                futures.add(executor.submit(mergeTask));
            }
            
            // Wait for all merge tasks to complete
            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    Log.e(TAG, "Error in parallel tile merge", e);
                }
            }
            
        } finally {
            executor.shutdown();
        }
        
        long totalTime = System.currentTimeMillis() - startTime;
        Log.d(TAG, "Parallel merge completed in " + totalTime + "ms for " + originalTiles.size() + " tiles");
        
        return resultBitmap;
    }
    
    /**
     * Calculate crop rectangle for a tile to handle overlaps
     */
    private Rect calculateCropRect(TileExtractor.TileInfo originalTile, Bitmap processedTile, 
                                  int tileX, int tileY, int tilesX) {
        int processedTileWidth = processedTile.getWidth();
        int processedTileHeight = processedTile.getHeight();
        
        // Calculate effective overlap in output space
        int scaledOverlap = overlapPixels * scaleFactor;
        int halfOverlap = scaledOverlap / 2;
        
        // Default to full tile
        int cropLeft = 0;
        int cropTop = 0;
        int cropRight = processedTileWidth;
        int cropBottom = processedTileHeight;
        
        // Adjust for horizontal overlaps
        if (tileX > 0) {
            // Not leftmost tile - crop left half of overlap
            cropLeft = halfOverlap;
        }
        if (tileX < tilesX - 1) {
            // Not rightmost tile - crop right half of overlap
            cropRight = Math.min(cropRight, processedTileWidth - halfOverlap);
        }
        
        // Adjust for vertical overlaps
        int tilesY = (int) Math.ceil((double) originalImageHeight / (tileHeight - overlapPixels));
        if (tileY > 0) {
            // Not topmost tile - crop top half of overlap
            cropTop = halfOverlap;
        }
        if (tileY < tilesY - 1) {
            // Not bottommost tile - crop bottom half of overlap
            cropBottom = Math.min(cropBottom, processedTileHeight - halfOverlap);
        }
        
        // Handle edge tiles that might be smaller
        if (originalTile.needsPadding) {
            int expectedWidth = originalTile.tileWidth * scaleFactor;
            int expectedHeight = originalTile.tileHeight * scaleFactor;
            
            cropRight = Math.min(cropRight, expectedWidth);
            cropBottom = Math.min(cropBottom, expectedHeight);
        }
        
        // Ensure valid rectangle
        cropRight = Math.max(cropLeft + 1, cropRight);
        cropBottom = Math.max(cropTop + 1, cropBottom);
        
        return new Rect(cropLeft, cropTop, cropRight, cropBottom);
    }
    
    /**
     * Calculate expected output dimensions
     */
    public static int[] calculateOutputDimensions(int originalWidth, int originalHeight, int scaleFactor) {
        return new int[]{originalWidth * scaleFactor, originalHeight * scaleFactor};
    }
}