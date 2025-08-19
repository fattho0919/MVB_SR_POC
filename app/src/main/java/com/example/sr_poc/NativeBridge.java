package com.example.sr_poc;

import android.util.Log;
import java.nio.ByteBuffer;

/**
 * Bridge class for JNI native methods.
 * Provides interface to native C++ SR engine implementation.
 */
public class NativeBridge {
    private static final String TAG = "NativeBridge";
    private static boolean isLibraryLoaded = false;
    
    // Native library loading
    static {
        try {
            System.loadLibrary("sr_native");
            isLibraryLoaded = true;
            Log.d(TAG, "Native library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library", e);
            isLibraryLoaded = false;
        }
    }
    
    // Engine handle
    private long nativeEngineHandle = 0;
    
    // Native methods
    private native long nativeCreateEngine(String modelPath, int numThreads);
    private native void nativeDestroyEngine(long engineHandle);
    private native String nativeGetVersion();
    private native long nativeBenchmark(int iterations);
    private native boolean nativeTestDirectBuffer(ByteBuffer buffer);
    private native boolean nativeProcessImage(long engineHandle, 
                                              ByteBuffer inputBuffer, 
                                              ByteBuffer outputBuffer,
                                              int width, int height);
    
    // Public API
    
    /**
     * Check if native library is available
     */
    public boolean isAvailable() {
        return isLibraryLoaded;
    }
    
    /**
     * Initialize the native engine
     * @param modelPath Path to the TensorFlow Lite model
     * @param numThreads Number of threads to use
     * @return true if initialization successful
     */
    public boolean initialize(String modelPath, int numThreads) {
        if (!isLibraryLoaded) {
            Log.e(TAG, "Native library not loaded");
            return false;
        }
        
        if (nativeEngineHandle != 0) {
            Log.w(TAG, "Engine already initialized");
            return true;
        }
        
        try {
            nativeEngineHandle = nativeCreateEngine(modelPath, numThreads);
            if (nativeEngineHandle != 0) {
                Log.d(TAG, "Engine initialized successfully");
                return true;
            } else {
                Log.e(TAG, "Failed to create native engine");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception during engine initialization", e);
            return false;
        }
    }
    
    /**
     * Release native resources
     */
    public void release() {
        if (nativeEngineHandle != 0) {
            try {
                nativeDestroyEngine(nativeEngineHandle);
                nativeEngineHandle = 0;
                Log.d(TAG, "Engine released");
            } catch (Exception e) {
                Log.e(TAG, "Error releasing engine", e);
            }
        }
    }
    
    /**
     * Get native library version
     */
    public String getVersion() {
        if (!isLibraryLoaded) {
            return "N/A - Library not loaded";
        }
        
        try {
            return nativeGetVersion();
        } catch (Exception e) {
            Log.e(TAG, "Error getting version", e);
            return "Error";
        }
    }
    
    /**
     * Run performance benchmark
     * @param iterations Number of iterations to run
     * @return Time in milliseconds, or -1 if error
     */
    public long benchmark(int iterations) {
        if (!isLibraryLoaded) {
            return -1;
        }
        
        try {
            return nativeBenchmark(iterations);
        } catch (Exception e) {
            Log.e(TAG, "Error running benchmark", e);
            return -1;
        }
    }
    
    /**
     * Test DirectByteBuffer access
     * @return true if test passed
     */
    public boolean testDirectBuffer() {
        if (!isLibraryLoaded) {
            return false;
        }
        
        try {
            ByteBuffer buffer = ByteBuffer.allocateDirect(1024);
            return nativeTestDirectBuffer(buffer);
        } catch (Exception e) {
            Log.e(TAG, "Error testing direct buffer", e);
            return false;
        }
    }
    
    /**
     * Process image using native engine
     * @param inputBuffer Direct ByteBuffer containing input image
     * @param outputBuffer Direct ByteBuffer for output image
     * @param width Image width
     * @param height Image height
     * @return true if processing successful
     */
    public boolean processImage(ByteBuffer inputBuffer, ByteBuffer outputBuffer, 
                               int width, int height) {
        if (!isLibraryLoaded || nativeEngineHandle == 0) {
            Log.e(TAG, "Native engine not ready");
            return false;
        }
        
        if (!inputBuffer.isDirect() || !outputBuffer.isDirect()) {
            Log.e(TAG, "Buffers must be direct ByteBuffers");
            return false;
        }
        
        try {
            return nativeProcessImage(nativeEngineHandle, inputBuffer, 
                                    outputBuffer, width, height);
        } catch (Exception e) {
            Log.e(TAG, "Error processing image", e);
            return false;
        }
    }
    
    /**
     * Check if engine is initialized
     */
    public boolean isInitialized() {
        return nativeEngineHandle != 0;
    }
    
    /**
     * Get engine statistics (placeholder for future implementation)
     */
    public String getStats() {
        if (!isInitialized()) {
            return "Engine not initialized";
        }
        
        // TODO: Implement native stats retrieval
        return "Stats: Processing ready";
    }
}