package com.example.sr_poc.pool;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.opengl.GLES20;
import android.util.Log;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

import java.util.ArrayList;
import java.util.List;

/**
 * Processor for handling large bitmaps that exceed GPU texture size limits.
 * 
 * Automatically detects MAX_TEXTURE_SIZE and implements intelligent tiling
 * strategies to process large images (4K+) without OOM errors.
 */
public class LargeBitmapProcessor {
    
    private static final String TAG = "LargeBitmapProcessor";
    
    // Default tile size if GPU query fails
    private static final int DEFAULT_TILE_SIZE = 2048;
    private static final int MIN_TILE_SIZE = 512;
    private static final int MAX_TILES = 64; // Safety limit
    
    private final Context context;
    private final PooledBitmapFactory bitmapFactory;
    private final BitmapPoolManager poolManager;
    
    private int maxTextureSize = -1;
    private boolean initialized = false;
    
    // Tile processing parameters
    private int tileOverlap = 32; // Pixels to overlap between tiles
    private float memoryThreshold = 0.7f; // Max memory usage before quality reduction
    
    /**
     * Creates a new LargeBitmapProcessor.
     * 
     * @param context Application context
     */
    public LargeBitmapProcessor(Context context) {
        this.context = context;
        this.bitmapFactory = new PooledBitmapFactory(context);
        this.poolManager = BitmapPoolManager.getInstance(context);
        
        // Detect max texture size on initialization
        detectMaxTextureSize();
    }
    
    /**
     * Detects the maximum texture size supported by the GPU.
     */
    private void detectMaxTextureSize() {
        try {
            // Create a temporary EGL context to query GPU capabilities
            EGL10 egl = (EGL10) EGLContext.getEGL();
            EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            
            int[] version = new int[2];
            egl.eglInitialize(display, version);
            
            int[] configAttribs = {
                EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                EGL10.EGL_NONE
            };
            
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            egl.eglChooseConfig(display, configAttribs, configs, 1, numConfigs);
            
            if (numConfigs[0] > 0) {
                int[] contextAttribs = {
                    0x3098, 2, // EGL_CONTEXT_CLIENT_VERSION, 2
                    EGL10.EGL_NONE
                };
                
                EGLContext eglContext = egl.eglCreateContext(display, configs[0], 
                                                            EGL10.EGL_NO_CONTEXT, contextAttribs);
                
                if (eglContext != EGL10.EGL_NO_CONTEXT) {
                    // Query max texture size
                    int[] maxSize = new int[1];
                    GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0);
                    
                    if (maxSize[0] > 0) {
                        maxTextureSize = maxSize[0];
                        Log.d(TAG, "Detected MAX_TEXTURE_SIZE: " + maxTextureSize);
                    }
                    
                    egl.eglDestroyContext(display, eglContext);
                }
            }
            
            egl.eglTerminate(display);
            
        } catch (Exception e) {
            Log.w(TAG, "Failed to detect max texture size, using default", e);
        }
        
        // Use default if detection failed
        if (maxTextureSize <= 0) {
            maxTextureSize = DEFAULT_TILE_SIZE;
            Log.d(TAG, "Using default MAX_TEXTURE_SIZE: " + maxTextureSize);
        }
        
        initialized = true;
    }
    
    /**
     * Checks if a bitmap needs tiling based on its dimensions.
     * 
     * @param width Bitmap width
     * @param height Bitmap height
     * @return true if tiling is needed
     */
    public boolean needsTiling(int width, int height) {
        if (!initialized) {
            detectMaxTextureSize();
        }
        
        return width > maxTextureSize || height > maxTextureSize;
    }
    
    /**
     * Calculates optimal tile size for a given image.
     * 
     * @param imageWidth Image width
     * @param imageHeight Image height
     * @param modelInputSize Model's expected input size
     * @return Optimal tile size
     */
    public int calculateOptimalTileSize(int imageWidth, int imageHeight, int modelInputSize) {
        if (!initialized) {
            detectMaxTextureSize();
        }
        
        // Start with model input size
        int tileSize = modelInputSize;
        
        // Ensure tile size doesn't exceed max texture size
        tileSize = Math.min(tileSize, maxTextureSize);
        
        // Ensure tile size is not too small
        tileSize = Math.max(tileSize, MIN_TILE_SIZE);
        
        // Check memory constraints
        long availableMemory = getAvailableMemory();
        long requiredMemory = estimateMemoryForTileSize(tileSize);
        
        // Reduce tile size if memory is constrained
        while (requiredMemory > availableMemory * memoryThreshold && tileSize > MIN_TILE_SIZE) {
            tileSize = tileSize * 3 / 4; // Reduce by 25%
            requiredMemory = estimateMemoryForTileSize(tileSize);
        }
        
        Log.d(TAG, String.format("Optimal tile size: %d for %dx%d image", 
                                tileSize, imageWidth, imageHeight));
        
        return tileSize;
    }
    
    /**
     * Represents a tile region in the original image.
     */
    public static class TileInfo {
        public final int x, y, width, height;
        public final int outputX, outputY;
        
        public TileInfo(int x, int y, int width, int height, int outputX, int outputY) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.outputX = outputX;
            this.outputY = outputY;
        }
    }
    
    /**
     * Calculates tile layout for processing a large image.
     * 
     * @param imageWidth Image width
     * @param imageHeight Image height
     * @param tileSize Size of each tile
     * @param outputScale Scale factor for output
     * @return List of tile information
     */
    public List<TileInfo> calculateTileLayout(int imageWidth, int imageHeight, 
                                              int tileSize, int outputScale) {
        List<TileInfo> tiles = new ArrayList<>();
        
        // Calculate effective tile size (accounting for overlap)
        int effectiveTileSize = tileSize - tileOverlap;
        
        // Calculate number of tiles needed
        int tilesX = (imageWidth + effectiveTileSize - 1) / effectiveTileSize;
        int tilesY = (imageHeight + effectiveTileSize - 1) / effectiveTileSize;
        
        // Safety check for maximum tiles
        if (tilesX * tilesY > MAX_TILES) {
            Log.w(TAG, String.format("Too many tiles (%d x %d = %d), adjusting...", 
                                    tilesX, tilesY, tilesX * tilesY));
            
            // Increase tile size to reduce tile count
            while (tilesX * tilesY > MAX_TILES && tileSize < maxTextureSize) {
                tileSize = Math.min(tileSize * 5 / 4, maxTextureSize);
                effectiveTileSize = tileSize - tileOverlap;
                tilesX = (imageWidth + effectiveTileSize - 1) / effectiveTileSize;
                tilesY = (imageHeight + effectiveTileSize - 1) / effectiveTileSize;
            }
        }
        
        // Generate tile information
        for (int y = 0; y < tilesY; y++) {
            for (int x = 0; x < tilesX; x++) {
                int tileX = x * effectiveTileSize;
                int tileY = y * effectiveTileSize;
                
                // Calculate actual tile dimensions (may be smaller at edges)
                int tileWidth = Math.min(tileSize, imageWidth - tileX);
                int tileHeight = Math.min(tileSize, imageHeight - tileY);
                
                // Calculate output position
                int outputX = tileX * outputScale;
                int outputY = tileY * outputScale;
                
                tiles.add(new TileInfo(tileX, tileY, tileWidth, tileHeight, outputX, outputY));
            }
        }
        
        Log.d(TAG, String.format("Created %d tiles (%dx%d) for %dx%d image", 
                                tiles.size(), tilesX, tilesY, imageWidth, imageHeight));
        
        return tiles;
    }
    
    /**
     * Extracts a tile from the source bitmap.
     * 
     * @param source Source bitmap
     * @param tileInfo Tile information
     * @param targetSize Target size for the tile (for padding)
     * @return Extracted tile bitmap
     */
    public Bitmap extractTile(Bitmap source, TileInfo tileInfo, int targetSize) {
        // Get tile bitmap from pool
        Bitmap tile = bitmapFactory.createBitmap(targetSize, targetSize, source.getConfig());
        
        // Create canvas for drawing
        Canvas canvas = new Canvas(tile);
        
        // Define source and destination rectangles
        Rect srcRect = new Rect(tileInfo.x, tileInfo.y, 
                                tileInfo.x + tileInfo.width, 
                                tileInfo.y + tileInfo.height);
        Rect dstRect = new Rect(0, 0, tileInfo.width, tileInfo.height);
        
        // Draw the tile
        canvas.drawBitmap(source, srcRect, dstRect, null);
        
        return tile;
    }
    
    /**
     * Merges processed tiles back into a single output bitmap.
     * 
     * @param tiles List of processed tile bitmaps
     * @param tileInfos Tile information list
     * @param outputWidth Output bitmap width
     * @param outputHeight Output bitmap height
     * @param outputScale Scale factor used
     * @return Merged output bitmap
     */
    public Bitmap mergeTiles(List<Bitmap> tiles, List<TileInfo> tileInfos, 
                            int outputWidth, int outputHeight, int outputScale) {
        if (tiles.size() != tileInfos.size()) {
            throw new IllegalArgumentException("Tiles and tile info count mismatch");
        }
        
        // Create output bitmap from pool
        Bitmap output = bitmapFactory.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        
        // Merge each tile
        for (int i = 0; i < tiles.size(); i++) {
            Bitmap tile = tiles.get(i);
            TileInfo info = tileInfos.get(i);
            
            if (tile != null) {
                // Calculate crop region to remove overlap
                int cropLeft = (info.x > 0) ? tileOverlap / 2 : 0;
                int cropTop = (info.y > 0) ? tileOverlap / 2 : 0;
                int cropRight = tile.getWidth();
                int cropBottom = tile.getHeight();
                
                // Adjust for edge tiles
                if (info.x + info.width < outputWidth / outputScale) {
                    cropRight -= tileOverlap / 2;
                }
                if (info.y + info.height < outputHeight / outputScale) {
                    cropBottom -= tileOverlap / 2;
                }
                
                // Define source and destination rectangles
                Rect srcRect = new Rect(cropLeft * outputScale, cropTop * outputScale, 
                                       cropRight * outputScale, cropBottom * outputScale);
                Rect dstRect = new Rect(info.outputX + cropLeft * outputScale, 
                                       info.outputY + cropTop * outputScale,
                                       info.outputX + cropRight * outputScale, 
                                       info.outputY + cropBottom * outputScale);
                
                // Draw the tile to output
                canvas.drawBitmap(tile, srcRect, dstRect, null);
            }
        }
        
        Log.d(TAG, String.format("Merged %d tiles into %dx%d output", 
                                tiles.size(), outputWidth, outputHeight));
        
        return output;
    }
    
    /**
     * Implements progressive quality reduction for memory-constrained scenarios.
     * 
     * @param bitmap Original bitmap
     * @param targetMemoryMB Target memory usage in MB
     * @return Quality-reduced bitmap
     */
    public Bitmap reduceQualityForMemory(Bitmap bitmap, int targetMemoryMB) {
        int currentWidth = bitmap.getWidth();
        int currentHeight = bitmap.getHeight();
        
        // Calculate current memory usage
        int bytesPerPixel = 4; // ARGB_8888
        long currentMemory = (long)currentWidth * currentHeight * bytesPerPixel;
        long targetMemory = targetMemoryMB * 1024L * 1024L;
        
        if (currentMemory <= targetMemory) {
            return bitmap; // No reduction needed
        }
        
        // Calculate scale factor
        float scale = (float)Math.sqrt((double)targetMemory / currentMemory);
        int newWidth = (int)(currentWidth * scale);
        int newHeight = (int)(currentHeight * scale);
        
        Log.d(TAG, String.format("Reducing quality: %dx%d -> %dx%d (%.1f%% scale)", 
                                currentWidth, currentHeight, newWidth, newHeight, scale * 100));
        
        // Create scaled bitmap
        Bitmap scaled = bitmapFactory.createScaledBitmap(bitmap, newWidth, newHeight, true);
        
        // Release original if different
        if (scaled != bitmap) {
            bitmapFactory.releaseBitmap(bitmap);
        }
        
        return scaled;
    }
    
    /**
     * Estimates memory required for a given tile size.
     */
    private long estimateMemoryForTileSize(int tileSize) {
        // Assume ARGB_8888 (4 bytes per pixel)
        // Account for input tile + output tile + processing overhead
        return (long)tileSize * tileSize * 4 * 3;
    }
    
    /**
     * Gets available memory in bytes.
     */
    private long getAvailableMemory() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        return maxMemory - usedMemory;
    }
    
    /**
     * Sets the overlap pixels between tiles.
     * 
     * @param pixels Overlap in pixels
     */
    public void setTileOverlap(int pixels) {
        this.tileOverlap = Math.max(0, pixels);
    }
    
    /**
     * Sets the memory threshold for quality reduction.
     * 
     * @param threshold Threshold as fraction (0.0 - 1.0)
     */
    public void setMemoryThreshold(float threshold) {
        this.memoryThreshold = Math.max(0.1f, Math.min(1.0f, threshold));
    }
    
    /**
     * Gets the detected maximum texture size.
     */
    public int getMaxTextureSize() {
        if (!initialized) {
            detectMaxTextureSize();
        }
        return maxTextureSize;
    }
    
    /**
     * Releases a list of tile bitmaps back to the pool.
     * 
     * @param tiles List of tile bitmaps to release
     */
    public void releaseTiles(List<Bitmap> tiles) {
        if (tiles != null) {
            for (Bitmap tile : tiles) {
                if (tile != null) {
                    bitmapFactory.releaseBitmap(tile);
                }
            }
        }
    }
}