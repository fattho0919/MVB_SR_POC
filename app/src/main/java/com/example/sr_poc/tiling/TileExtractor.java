package com.example.sr_poc.tiling;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts tiles from input images for parallel processing
 * Optimized for 384x384 tile size with 32px overlap
 */
public class TileExtractor {
    
    private static final String TAG = "TileExtractor";
    
    private final int tileWidth;
    private final int tileHeight;
    private final int overlapPixels;
    
    public static class TileInfo {
        public Bitmap bitmap;
        public int originalX;
        public int originalY;
        public int tileWidth;
        public int tileHeight;
        public boolean needsPadding;
        
        public TileInfo(Bitmap bitmap, int originalX, int originalY, 
                       int tileWidth, int tileHeight, boolean needsPadding) {
            this.bitmap = bitmap;
            this.originalX = originalX;
            this.originalY = originalY;
            this.tileWidth = tileWidth;
            this.tileHeight = tileHeight;
            this.needsPadding = needsPadding;
        }
    }
    
    public TileExtractor(int tileWidth, int tileHeight, int overlapPixels) {
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        this.overlapPixels = overlapPixels;
        
        Log.d(TAG, "TileExtractor created - Tile size: " + tileWidth + "x" + tileHeight + 
              ", Overlap: " + overlapPixels + "px");
    }
    
    /**
     * Extract tiles from input bitmap for parallel processing
     */
    public List<TileInfo> extractTiles(Bitmap inputBitmap) {
        if (inputBitmap == null || inputBitmap.isRecycled()) {
            Log.e(TAG, "Invalid input bitmap");
            return new ArrayList<>();
        }
        
        int inputWidth = inputBitmap.getWidth();
        int inputHeight = inputBitmap.getHeight();
        
        Log.d(TAG, "Extracting tiles from " + inputWidth + "x" + inputHeight + " image");
        
        // Calculate effective step size (tile size minus overlap)
        int effectiveStepX = tileWidth - overlapPixels;
        int effectiveStepY = tileHeight - overlapPixels;
        
        // Calculate number of tiles needed
        int tilesX = (int) Math.ceil((double) inputWidth / effectiveStepX);
        int tilesY = (int) Math.ceil((double) inputHeight / effectiveStepY);
        
        Log.d(TAG, "Will extract " + tilesX + "x" + tilesY + " = " + (tilesX * tilesY) + " tiles");
        
        List<TileInfo> tiles = new ArrayList<>();
        
        for (int y = 0; y < tilesY; y++) {
            for (int x = 0; x < tilesX; x++) {
                // Calculate tile position
                int tileLeft = x * effectiveStepX;
                int tileTop = y * effectiveStepY;
                int tileRight = Math.min(tileLeft + tileWidth, inputWidth);
                int tileBottom = Math.min(tileTop + tileHeight, inputHeight);
                
                int actualTileWidth = tileRight - tileLeft;
                int actualTileHeight = tileBottom - tileTop;
                
                boolean needsPadding = (actualTileWidth < tileWidth) || (actualTileHeight < tileHeight);
                
                Log.v(TAG, "Extracted tile (" + x + "," + y + ") at (" + tileLeft + "," + tileTop + 
                      ") size " + actualTileWidth + "x" + actualTileHeight + 
                      (needsPadding ? " [padded]" : ""));
                
                // Extract tile bitmap
                Bitmap tileBitmap = extractSingleTile(inputBitmap, tileLeft, tileTop, 
                                                    actualTileWidth, actualTileHeight, needsPadding);
                
                if (tileBitmap != null) {
                    TileInfo tileInfo = new TileInfo(tileBitmap, tileLeft, tileTop, 
                                                   actualTileWidth, actualTileHeight, needsPadding);
                    tiles.add(tileInfo);
                }
            }
        }
        
        Log.d(TAG, "Successfully extracted " + tiles.size() + " tiles");
        return tiles;
    }
    
    /**
     * Extract a single tile with proper padding if needed
     */
    private Bitmap extractSingleTile(Bitmap inputBitmap, int left, int top, 
                                    int actualWidth, int actualHeight, boolean needsPadding) {
        try {
            if (!needsPadding) {
                // Simple case: exact tile size
                return Bitmap.createBitmap(inputBitmap, left, top, actualWidth, actualHeight);
            }
            
            // Need padding to reach target tile size
            Bitmap paddedTile = Bitmap.createBitmap(tileWidth, tileHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(paddedTile);
            
            // Draw the actual tile content
            Bitmap sourceTile = Bitmap.createBitmap(inputBitmap, left, top, actualWidth, actualHeight);
            canvas.drawBitmap(sourceTile, 0, 0, null);
            
            // Pad with edge pixels if needed
            Paint paint = new Paint();
            paint.setFilterBitmap(false);
            
            // Horizontal padding (repeat rightmost column)
            if (actualWidth < tileWidth) {
                Rect srcRect = new Rect(actualWidth - 1, 0, actualWidth, actualHeight);
                Rect dstRect = new Rect(actualWidth, 0, tileWidth, actualHeight);
                canvas.drawBitmap(sourceTile, srcRect, dstRect, paint);
            }
            
            // Vertical padding (repeat bottom row)
            if (actualHeight < tileHeight) {
                Rect srcRect = new Rect(0, actualHeight - 1, tileWidth, actualHeight);
                Rect dstRect = new Rect(0, actualHeight, tileWidth, tileHeight);
                canvas.drawBitmap(paddedTile, srcRect, dstRect, paint);
            }
            
            sourceTile.recycle();
            return paddedTile;
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting tile at (" + left + "," + top + ")", e);
            return null;
        }
    }
    
    /**
     * Check if tiling should be used based on image size
     */
    public static boolean shouldUseTiling(int imageWidth, int imageHeight, int maxDirectSize) {
        // Use tiling if image is larger than maxDirectSize in either dimension
        boolean shouldTile = imageWidth > maxDirectSize || imageHeight > maxDirectSize;
        
        Log.d(TAG, "Image " + imageWidth + "x" + imageHeight + 
              ", max direct size: " + maxDirectSize + 
              ", should use tiling: " + shouldTile);
        
        return shouldTile;
    }
}