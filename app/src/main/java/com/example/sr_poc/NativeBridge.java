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
    
    // Memory pool native methods
    private native boolean nativeInitMemoryPool(int smallBlockSize, int mediumBlockSize, 
                                               int largeBlockSize, int smallPoolCount, 
                                               int mediumPoolCount, int largePoolCount);
    private native ByteBuffer nativeAllocateDirectBuffer(int size, int alignment);
    private native void nativeDeallocateDirectBuffer(ByteBuffer buffer);
    private native MemoryStatistics nativeGetMemoryStatistics();
    private native void nativeResetMemoryPool();
    private native void nativeWarmupMemoryPool();
    private native void nativeDumpMemoryPoolState();
    private native String nativeGetAllocatorStats();
    private native boolean nativeDetectMemoryLeaks();
    private native void nativeClearMemoryTracker();
    private native boolean nativeTestAlignedAllocator();
    
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
    
    // Memory Pool API
    
    /**
     * Memory pool configuration
     */
    public static class MemoryPoolConfig {
        public int smallBlockSize = 8 * 1024;      // 8KB
        public int mediumBlockSize = 64 * 1024;    // 64KB
        public int largeBlockSize = 1024 * 1024;   // 1MB
        public int smallPoolCount = 128;
        public int mediumPoolCount = 32;
        public int largePoolCount = 8;
        
        public MemoryPoolConfig() {
            // Default constructor
        }
        
        public MemoryPoolConfig(int smallCount, int mediumCount, int largeCount) {
            this.smallPoolCount = smallCount;
            this.mediumPoolCount = mediumCount;
            this.largePoolCount = largeCount;
        }
    }
    
    /**
     * Initialize memory pool with configuration
     */
    public boolean initializeMemoryPool(MemoryPoolConfig config) {
        if (!isLibraryLoaded) {
            Log.e(TAG, "Native library not loaded");
            return false;
        }
        
        try {
            boolean result = nativeInitMemoryPool(
                config.smallBlockSize, config.mediumBlockSize, config.largeBlockSize,
                config.smallPoolCount, config.mediumPoolCount, config.largePoolCount
            );
            
            if (result) {
                Log.d(TAG, "Memory pool initialized successfully");
            } else {
                Log.e(TAG, "Failed to initialize memory pool");
            }
            
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error initializing memory pool", e);
            return false;
        }
    }
    
    /**
     * Initialize memory pool with default configuration
     */
    public boolean initializeMemoryPool() {
        return initializeMemoryPool(new MemoryPoolConfig());
    }
    
    /**
     * Allocate aligned DirectByteBuffer from pool
     * @param size Size in bytes
     * @param alignment Alignment requirement (must be power of 2)
     * @return Allocated DirectByteBuffer or null on failure
     */
    public ByteBuffer allocateAlignedBuffer(int size, int alignment) {
        if (!isLibraryLoaded) {
            Log.e(TAG, "Native library not loaded");
            return null;
        }
        
        try {
            ByteBuffer buffer = nativeAllocateDirectBuffer(size, alignment);
            if (buffer != null) {
                Log.d(TAG, "Allocated buffer: size=" + size + ", alignment=" + alignment);
            }
            return buffer;
        } catch (Exception e) {
            Log.e(TAG, "Error allocating buffer", e);
            return null;
        }
    }
    
    /**
     * Allocate DirectByteBuffer with default cache-line alignment (64 bytes)
     */
    public ByteBuffer allocateAlignedBuffer(int size) {
        return allocateAlignedBuffer(size, 64);
    }
    
    /**
     * Deallocate DirectByteBuffer back to pool
     */
    public void deallocateBuffer(ByteBuffer buffer) {
        if (!isLibraryLoaded || buffer == null) {
            return;
        }
        
        if (!buffer.isDirect()) {
            Log.w(TAG, "Can only deallocate direct buffers");
            return;
        }
        
        try {
            nativeDeallocateDirectBuffer(buffer);
            Log.d(TAG, "Deallocated buffer");
        } catch (Exception e) {
            Log.e(TAG, "Error deallocating buffer", e);
        }
    }
    
    /**
     * Get memory pool statistics
     */
    public MemoryStatistics getMemoryStatistics() {
        if (!isLibraryLoaded) {
            return new MemoryStatistics();
        }
        
        try {
            return nativeGetMemoryStatistics();
        } catch (Exception e) {
            Log.e(TAG, "Error getting memory statistics", e);
            return new MemoryStatistics();
        }
    }
    
    /**
     * Reset memory pool (releases all memory)
     */
    public void resetMemoryPool() {
        if (!isLibraryLoaded) {
            return;
        }
        
        try {
            nativeResetMemoryPool();
            Log.d(TAG, "Memory pool reset");
        } catch (Exception e) {
            Log.e(TAG, "Error resetting memory pool", e);
        }
    }
    
    /**
     * Warmup memory pool for better initial performance
     */
    public void warmupMemoryPool() {
        if (!isLibraryLoaded) {
            return;
        }
        
        try {
            nativeWarmupMemoryPool();
            Log.d(TAG, "Memory pool warmed up");
        } catch (Exception e) {
            Log.e(TAG, "Error warming up memory pool", e);
        }
    }
    
    /**
     * Dump memory pool state for debugging
     */
    public void dumpMemoryPoolState() {
        if (!isLibraryLoaded) {
            return;
        }
        
        try {
            nativeDumpMemoryPoolState();
        } catch (Exception e) {
            Log.e(TAG, "Error dumping memory pool state", e);
        }
    }
    
    /**
     * Get allocator statistics as string
     */
    public String getAllocatorStats() {
        if (!isLibraryLoaded) {
            return "Native library not loaded";
        }
        
        try {
            return nativeGetAllocatorStats();
        } catch (Exception e) {
            Log.e(TAG, "Error getting allocator stats", e);
            return "Error retrieving stats";
        }
    }
    
    /**
     * Detect memory leaks
     */
    public boolean detectMemoryLeaks() {
        if (!isLibraryLoaded) {
            return false;
        }
        
        try {
            return nativeDetectMemoryLeaks();
        } catch (Exception e) {
            Log.e(TAG, "Error detecting memory leaks", e);
            return false;
        }
    }
    
    /**
     * Clear memory tracker
     */
    public void clearMemoryTracker() {
        if (!isLibraryLoaded) {
            return;
        }
        
        try {
            nativeClearMemoryTracker();
            Log.d(TAG, "Memory tracker cleared");
        } catch (Exception e) {
            Log.e(TAG, "Error clearing memory tracker", e);
        }
    }
    
    /**
     * Test aligned allocator functionality
     */
    public boolean testAlignedAllocator() {
        if (!isLibraryLoaded) {
            return false;
        }
        
        try {
            boolean result = nativeTestAlignedAllocator();
            Log.d(TAG, "Aligned allocator test: " + (result ? "PASSED" : "FAILED"));
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error testing aligned allocator", e);
            return false;
        }
    }
}