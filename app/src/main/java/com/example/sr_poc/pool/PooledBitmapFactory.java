package com.example.sr_poc.pool;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.Log;

import java.io.InputStream;

/**
 * Factory class for creating and decoding bitmaps using the bitmap pool.
 * 
 * Provides convenient methods for common bitmap operations while
 * automatically leveraging the bitmap pool for memory efficiency.
 */
public class PooledBitmapFactory {
    
    private static final String TAG = "PooledBitmapFactory";
    
    private final BitmapPoolManager poolManager;
    
    /**
     * Creates a new PooledBitmapFactory.
     * 
     * @param context Application context
     */
    public PooledBitmapFactory(Context context) {
        this.poolManager = BitmapPoolManager.getInstance(context);
    }
    
    /**
     * Creates a bitmap using the pool.
     * 
     * @param width Bitmap width
     * @param height Bitmap height
     * @param config Bitmap configuration
     * @return Pooled bitmap
     */
    public Bitmap createBitmap(int width, int height, Bitmap.Config config) {
        return poolManager.acquireBitmap(width, height, config);
    }
    
    /**
     * Creates a bitmap with default ARGB_8888 configuration.
     */
    public Bitmap createBitmap(int width, int height) {
        return createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }
    
    /**
     * Creates a bitmap for common 720p size.
     */
    public Bitmap create720pBitmap() {
        return createBitmap(1280, 720, Bitmap.Config.ARGB_8888);
    }
    
    /**
     * Creates a bitmap for common 1080p size.
     */
    public Bitmap create1080pBitmap() {
        return createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
    }
    
    /**
     * Creates a bitmap for tile processing.
     */
    public Bitmap createTileBitmap(int tileSize) {
        return createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888);
    }
    
    /**
     * Decodes a bitmap from byte array using inBitmap for reuse.
     * 
     * @param data Byte array containing image data
     * @param offset Offset into the byte array
     * @param length Number of bytes to decode
     * @param targetWidth Target width (for inBitmap)
     * @param targetHeight Target height (for inBitmap)
     * @return Decoded bitmap
     */
    public Bitmap decodeByteArray(byte[] data, int offset, int length, 
                                  int targetWidth, int targetHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        
        // First decode bounds only
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, offset, length, options);
        
        // Calculate sample size if needed
        options.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight);
        
        // Try to use inBitmap for reuse
        options.inJustDecodeBounds = false;
        options.inMutable = true;
        
        if (poolManager.isEnabled()) {
            // Calculate the actual dimensions after sampling
            int actualWidth = options.outWidth / options.inSampleSize;
            int actualHeight = options.outHeight / options.inSampleSize;
            
            // Try to get a bitmap from pool for reuse
            Bitmap inBitmap = poolManager.acquireBitmap(actualWidth, actualHeight, 
                    options.inPreferredConfig != null ? options.inPreferredConfig : Bitmap.Config.ARGB_8888);
            
            if (inBitmap != null) {
                options.inBitmap = inBitmap;
                
                try {
                    Bitmap result = BitmapFactory.decodeByteArray(data, offset, length, options);
                    if (result != null) {
                        Log.v(TAG, String.format("Decoded with inBitmap reuse: %dx%d", 
                                result.getWidth(), result.getHeight()));
                        return result;
                    }
                } catch (IllegalArgumentException e) {
                    // inBitmap didn't match, fall back to normal decode
                    Log.w(TAG, "inBitmap mismatch, falling back to normal decode: " + e.getMessage());
                    poolManager.releaseBitmap(inBitmap);
                    options.inBitmap = null;
                }
            }
        }
        
        // Normal decode without inBitmap
        return BitmapFactory.decodeByteArray(data, offset, length, options);
    }
    
    /**
     * Decodes a bitmap from input stream using inBitmap for reuse.
     */
    public Bitmap decodeStream(InputStream stream, int targetWidth, int targetHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        
        // Mark stream for reset
        stream.mark(Integer.MAX_VALUE);
        
        // First decode bounds only
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(stream, null, options);
        
        // Reset stream
        try {
            stream.reset();
        } catch (Exception e) {
            Log.e(TAG, "Failed to reset stream", e);
            return null;
        }
        
        // Calculate sample size
        options.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight);
        
        // Try to use inBitmap
        options.inJustDecodeBounds = false;
        options.inMutable = true;
        
        if (poolManager.isEnabled()) {
            int actualWidth = options.outWidth / options.inSampleSize;
            int actualHeight = options.outHeight / options.inSampleSize;
            
            Bitmap inBitmap = poolManager.acquireBitmap(actualWidth, actualHeight, 
                    options.inPreferredConfig != null ? options.inPreferredConfig : Bitmap.Config.ARGB_8888);
            
            if (inBitmap != null) {
                options.inBitmap = inBitmap;
                
                try {
                    Bitmap result = BitmapFactory.decodeStream(stream, null, options);
                    if (result != null) {
                        Log.v(TAG, String.format("Decoded stream with inBitmap: %dx%d", 
                                result.getWidth(), result.getHeight()));
                        return result;
                    }
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "inBitmap mismatch for stream: " + e.getMessage());
                    poolManager.releaseBitmap(inBitmap);
                    options.inBitmap = null;
                    
                    // Reset stream again for retry
                    try {
                        stream.reset();
                    } catch (Exception ex) {
                        return null;
                    }
                }
            }
        }
        
        // Normal decode
        return BitmapFactory.decodeStream(stream, null, options);
    }
    
    /**
     * Creates a scaled bitmap using pooled resources.
     * 
     * @param source Source bitmap
     * @param targetWidth Target width
     * @param targetHeight Target height
     * @param filter Use filtering
     * @return Scaled bitmap
     */
    public Bitmap createScaledBitmap(Bitmap source, int targetWidth, int targetHeight, boolean filter) {
        if (source == null) {
            return null;
        }
        
        // If same size, return source
        if (source.getWidth() == targetWidth && source.getHeight() == targetHeight) {
            return source;
        }
        
        // Get bitmap from pool
        Bitmap scaled = poolManager.acquireBitmap(targetWidth, targetHeight, source.getConfig());
        
        // Draw source onto scaled bitmap
        Canvas canvas = new Canvas(scaled);
        Rect srcRect = new Rect(0, 0, source.getWidth(), source.getHeight());
        Rect dstRect = new Rect(0, 0, targetWidth, targetHeight);
        
        android.graphics.Paint paint = null;
        if (filter) {
            paint = new android.graphics.Paint();
            paint.setFilterBitmap(true);
            paint.setAntiAlias(true);
        }
        
        canvas.drawBitmap(source, srcRect, dstRect, paint);
        
        return scaled;
    }
    
    /**
     * Creates a cropped bitmap using pooled resources.
     * 
     * @param source Source bitmap
     * @param x Starting x coordinate
     * @param y Starting y coordinate
     * @param width Crop width
     * @param height Crop height
     * @return Cropped bitmap
     */
    public Bitmap createCroppedBitmap(Bitmap source, int x, int y, int width, int height) {
        if (source == null) {
            return null;
        }
        
        // Validate bounds
        if (x < 0 || y < 0 || x + width > source.getWidth() || y + height > source.getHeight()) {
            throw new IllegalArgumentException("Invalid crop bounds");
        }
        
        // Get bitmap from pool
        Bitmap cropped = poolManager.acquireBitmap(width, height, source.getConfig());
        
        // Draw cropped region
        Canvas canvas = new Canvas(cropped);
        Rect srcRect = new Rect(x, y, x + width, y + height);
        Rect dstRect = new Rect(0, 0, width, height);
        canvas.drawBitmap(source, srcRect, dstRect, null);
        
        return cropped;
    }
    
    /**
     * Copies a bitmap using pooled resources.
     * 
     * @param source Source bitmap
     * @param isMutable Whether the copy should be mutable
     * @return Copied bitmap
     */
    public Bitmap copyBitmap(Bitmap source, boolean isMutable) {
        if (source == null) {
            return null;
        }
        
        Bitmap copy = poolManager.acquireBitmap(source.getWidth(), source.getHeight(), source.getConfig());
        
        Canvas canvas = new Canvas(copy);
        canvas.drawBitmap(source, 0, 0, null);
        
        return copy;
    }
    
    /**
     * Releases a bitmap back to the pool.
     * 
     * @param bitmap Bitmap to release
     */
    public void releaseBitmap(Bitmap bitmap) {
        poolManager.releaseBitmap(bitmap);
    }
    
    /**
     * Calculates optimal sample size for decoding.
     */
    private int calculateInSampleSize(BitmapFactory.Options options, 
                                     int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            
            // Calculate the largest inSampleSize value that is a power of 2
            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        
        return inSampleSize;
    }
}