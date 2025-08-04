package com.example.sr_poc.utils;

import android.graphics.Bitmap;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

public final class BitmapConverter {
    
    private static final String TAG = "BitmapConverter";
    
    private BitmapConverter() {
        // Prevent instantiation
    }
    
    /**
     * Convert pixel array to float32 array for model input
     */
    public static void convertPixelsToFloat32(int[] pixels, float[] floatArray) {
        for (int i = 0, floatIndex = 0; i < pixels.length; i++, floatIndex += 3) {
            int pixel = pixels[i];
            floatArray[floatIndex] = ((pixel >> 16) & 0xFF) * Constants.RGB_TO_FLOAT_MULTIPLIER;
            floatArray[floatIndex + 1] = ((pixel >> 8) & 0xFF) * Constants.RGB_TO_FLOAT_MULTIPLIER;
            floatArray[floatIndex + 2] = (pixel & 0xFF) * Constants.RGB_TO_FLOAT_MULTIPLIER;
        }
    }
    
    /**
     * Convert pixel array to uint8 array for model input
     */
    public static void convertPixelsToUint8(int[] pixels, byte[] byteArray) {
        for (int i = 0, byteIndex = 0; i < pixels.length; i++, byteIndex += 3) {
            int pixel = pixels[i];
            byteArray[byteIndex] = (byte) ((pixel >> 16) & 0xFF);
            byteArray[byteIndex + 1] = (byte) ((pixel >> 8) & 0xFF);
            byteArray[byteIndex + 2] = (byte) (pixel & 0xFF);
        }
    }
    
    /**
     * Convert pixel array to int8 array for model input
     * Converts RGB [0,255] to INT8 [-128,127] by subtracting 128
     */
    public static void convertPixelsToInt8(int[] pixels, byte[] byteArray) {
        for (int i = 0, byteIndex = 0; i < pixels.length; i++, byteIndex += 3) {
            int pixel = pixels[i];
            // Convert RGB [0,255] to INT8 [-128,127] by subtracting 128
            byteArray[byteIndex] = (byte) (((pixel >> 16) & 0xFF) - 128);
            byteArray[byteIndex + 1] = (byte) (((pixel >> 8) & 0xFF) - 128);
            byteArray[byteIndex + 2] = (byte) ((pixel & 0xFF) - 128);
        }
    }
    
    /**
     * Convert float32 array to pixels (sequential version for small images)
     */
    public static void convertFloat32ToPixels(float[] floatArray, int[] pixels) {
        for (int i = 0, floatIndex = 0; i < pixels.length; i++, floatIndex += 3) {
            float r = floatArray[floatIndex];
            float g = floatArray[floatIndex + 1];
            float b = floatArray[floatIndex + 2];
            
            int red = clampToByteRange(r);
            int green = clampToByteRange(g);
            int blue = clampToByteRange(b);
            
            pixels[i] = 0xFF000000 | (red << 16) | (green << 8) | blue;
        }
    }
    
    /**
     * Convert uint8 array to pixels (sequential version for small images)
     */
    public static void convertUint8ToPixels(byte[] byteArray, int[] pixels) {
        for (int i = 0, byteIndex = 0; i < pixels.length; i++, byteIndex += 3) {
            int r = byteArray[byteIndex] & 0xFF;
            int g = byteArray[byteIndex + 1] & 0xFF;
            int b = byteArray[byteIndex + 2] & 0xFF;
            
            pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }
    
    /**
     * Convert float32 array to pixels using parallel processing for large images
     */
    public static void convertFloat32ToPixelsParallel(float[] floatArray, int[] pixels, 
                                                     ExecutorService executor) {
        if (pixels.length <= Constants.LARGE_IMAGE_PIXEL_THRESHOLD) {
            convertFloat32ToPixels(floatArray, pixels);
            return;
        }
        
        int numThreads = Math.min(Constants.MAX_CONVERSION_THREADS, 
                                Runtime.getRuntime().availableProcessors());
        int pixelsPerThread = pixels.length / numThreads;
        CountDownLatch latch = new CountDownLatch(numThreads);
        
        for (int t = 0; t < numThreads; t++) {
            final int threadIndex = t;
            final int startPixel = t * pixelsPerThread;
            final int endPixel = (t == numThreads - 1) ? pixels.length : (t + 1) * pixelsPerThread;
            
            executor.submit(() -> {
                try {
                    for (int i = startPixel, floatIndex = startPixel * 3; i < endPixel; i++, floatIndex += 3) {
                        float r = floatArray[floatIndex];
                        float g = floatArray[floatIndex + 1];
                        float b = floatArray[floatIndex + 2];
                        
                        int red = clampToByteRange(r);
                        int green = clampToByteRange(g);
                        int blue = clampToByteRange(b);
                        
                        pixels[i] = 0xFF000000 | (red << 16) | (green << 8) | blue;
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await();
        } catch (InterruptedException e) {
            Log.e(TAG, "Parallel conversion interrupted", e);
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Convert uint8 array to pixels using parallel processing for large images
     */
    public static void convertUint8ToPixelsParallel(byte[] byteArray, int[] pixels, 
                                                   ExecutorService executor) {
        if (pixels.length <= Constants.LARGE_IMAGE_PIXEL_THRESHOLD) {
            convertUint8ToPixels(byteArray, pixels);
            return;
        }
        
        int numThreads = Math.min(Constants.MAX_CONVERSION_THREADS, 
                                Runtime.getRuntime().availableProcessors());
        int pixelsPerThread = pixels.length / numThreads;
        CountDownLatch latch = new CountDownLatch(numThreads);
        
        for (int t = 0; t < numThreads; t++) {
            final int threadIndex = t;
            final int startPixel = t * pixelsPerThread;
            final int endPixel = (t == numThreads - 1) ? pixels.length : (t + 1) * pixelsPerThread;
            
            executor.submit(() -> {
                try {
                    for (int i = startPixel, byteIndex = startPixel * 3; i < endPixel; i++, byteIndex += 3) {
                        int r = byteArray[byteIndex] & 0xFF;
                        int g = byteArray[byteIndex + 1] & 0xFF;
                        int b = byteArray[byteIndex + 2] & 0xFF;
                        
                        pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await();
        } catch (InterruptedException e) {
            Log.e(TAG, "Parallel conversion interrupted", e);
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Convert int8 array to pixels using parallel processing for large images
     * Converts INT8 [-128,127] to RGB [0,255] by adding 128
     */
    public static void convertInt8ToPixelsParallel(byte[] byteArray, int[] pixels, 
                                                  ExecutorService executor) {
        if (pixels.length <= Constants.LARGE_IMAGE_PIXEL_THRESHOLD) {
            convertInt8ToPixels(byteArray, pixels);
            return;
        }
        
        int numThreads = Math.min(Constants.MAX_CONVERSION_THREADS, 
                                Runtime.getRuntime().availableProcessors());
        int pixelsPerThread = pixels.length / numThreads;
        CountDownLatch latch = new CountDownLatch(numThreads);
        
        for (int t = 0; t < numThreads; t++) {
            final int threadIndex = t;
            final int startPixel = t * pixelsPerThread;
            final int endPixel = (t == numThreads - 1) ? pixels.length : (t + 1) * pixelsPerThread;
            
            executor.submit(() -> {
                try {
                    for (int i = startPixel, byteIndex = startPixel * 3; i < endPixel; i++, byteIndex += 3) {
                        // Convert INT8 [-128,127] to RGB [0,255] by adding 128
                        int r = (byteArray[byteIndex] + 128) & 0xFF;
                        int g = (byteArray[byteIndex + 1] + 128) & 0xFF;
                        int b = (byteArray[byteIndex + 2] + 128) & 0xFF;
                        
                        pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await();
        } catch (InterruptedException e) {
            Log.e(TAG, "Parallel conversion interrupted", e);
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Convert int8 array to pixels (sequential version for small images)
     * Converts INT8 [-128,127] to RGB [0,255] by adding 128
     */
    public static void convertInt8ToPixels(byte[] byteArray, int[] pixels) {
        for (int i = 0, byteIndex = 0; i < pixels.length; i++, byteIndex += 3) {
            // Convert INT8 [-128,127] to RGB [0,255] by adding 128
            int r = (byteArray[byteIndex] + 128) & 0xFF;
            int g = (byteArray[byteIndex + 1] + 128) & 0xFF;
            int b = (byteArray[byteIndex + 2] + 128) & 0xFF;
            
            pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }
    
    private static int clampToByteRange(float value) {
        // Handle both [0,1] and [-1,1] ranges for different model types
        if (value < 0) {
            // If negative, assume [-1,1] range and convert to [0,1]
            value = (value + 1.0f) / 2.0f;
        }
        return (int) (Math.max(0, Math.min(1, value)) * 255);
    }
}