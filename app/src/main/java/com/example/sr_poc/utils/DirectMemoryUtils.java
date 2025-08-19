package com.example.sr_poc.utils;

import android.util.Log;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Utilities for DirectByteBuffer management and monitoring.
 * 
 * Provides aligned buffer allocation, memory monitoring, and cleanup
 * for optimal performance with TensorFlow Lite.
 */
public class DirectMemoryUtils {
    
    private static final String TAG = "DirectMemoryUtils";
    
    // Memory alignment for optimal performance (64-byte cache line)
    private static final int MEMORY_ALIGNMENT = 64;
    
    private DirectMemoryUtils() {
        // Prevent instantiation
    }
    
    /**
     * Allocate aligned DirectByteBuffer for optimal memory access.
     * 
     * @param size Required buffer size in bytes
     * @return Aligned DirectByteBuffer with native byte order
     */
    public static ByteBuffer allocateAlignedDirectBuffer(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size must be positive");
        }
        
        try {
            // Calculate aligned size to ensure proper cache alignment
            int alignedSize = ((size + MEMORY_ALIGNMENT - 1) / MEMORY_ALIGNMENT) * MEMORY_ALIGNMENT;
            
            // Allocate direct buffer
            ByteBuffer buffer = ByteBuffer.allocateDirect(alignedSize);
            buffer.order(ByteOrder.nativeOrder());
            
            // Set limit to actual requested size
            buffer.limit(size);
            
            long address = getDirectBufferAddress(buffer);
            if (address != 0) {
                Log.d(TAG, String.format("Allocated aligned buffer: requested=%d, aligned=%d, address=0x%x", 
                        size, alignedSize, address));
            } else {
                Log.d(TAG, String.format("Allocated aligned buffer: requested=%d, aligned=%d, address=0x0", 
                        size, alignedSize));
            }
            
            return buffer;
            
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Failed to allocate direct buffer of size " + size, e);
            throw e;
        }
    }
    
    /**
     * Get the native memory address of a DirectByteBuffer.
     * 
     * @param buffer DirectByteBuffer to get address from
     * @return Native memory address (0 if not direct buffer)
     */
    public static long getDirectBufferAddress(ByteBuffer buffer) {
        if (!buffer.isDirect()) {
            return 0;
        }
        
        try {
            // Try the more accessible approach first (Android 13+ compatible)
            Method getAddress = ByteBuffer.class.getMethod("address");
            return (Long) getAddress.invoke(buffer);
        } catch (NoSuchMethodException e) {
            // Fallback to getDeclaredMethod for older Android versions
            try {
                Method getAddress = buffer.getClass().getDeclaredMethod("address");
                getAddress.setAccessible(true);
                return (Long) getAddress.invoke(buffer);
            } catch (Exception ex) {
                Log.w(TAG, "Could not get buffer address via reflection (Android 13+ restriction)", ex);
                // Return 0 to indicate address unavailable, but don't fail the allocation
                return 0;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get buffer address", e);
            return 0;
        }
    }
    
    /**
     * Get current used direct memory in bytes.
     * 
     * @return Used direct memory in bytes, or -1 if cannot be determined
     */
    public static long getUsedDirectMemory() {
        try {
            // Try modern Android approach first
            try {
                Class<?> runtimeClass = Class.forName("dalvik.system.VMRuntime");
                Method getRuntimeMethod = runtimeClass.getDeclaredMethod("getRuntime");
                Object runtime = getRuntimeMethod.invoke(null);
                
                Method getExternalBytesAllocatedMethod = runtimeClass.getDeclaredMethod("getExternalBytesAllocated");
                return (Long) getExternalBytesAllocatedMethod.invoke(runtime);
            } catch (Exception androidException) {
                // Fallback to Java/OpenJDK approach
                Class<?> vmClass = Class.forName("sun.misc.VM");
                Method maxDirectMemoryMethod = vmClass.getDeclaredMethod("maxDirectMemory");
                
                Class<?> bitsClass = Class.forName("java.nio.Bits");
                Method reservedMemoryMethod = bitsClass.getDeclaredMethod("reservedMemory");
                reservedMemoryMethod.setAccessible(true);
                long reservedMemory = (Long) reservedMemoryMethod.invoke(null);
                
                return reservedMemory;
            }
            
        } catch (Exception e) {
            Log.w(TAG, "Could not determine direct memory usage (Android 13+ restriction)", e);
            return -1;
        }
    }
    
    /**
     * Get maximum direct memory limit in bytes.
     * 
     * @return Maximum direct memory limit, or -1 if cannot be determined
     */
    public static long getMaxDirectMemory() {
        try {
            Class<?> vmClass = Class.forName("sun.misc.VM");
            Method maxDirectMemoryMethod = vmClass.getDeclaredMethod("maxDirectMemory");
            return (Long) maxDirectMemoryMethod.invoke(null);
        } catch (Exception e) {
            Log.w(TAG, "Could not determine max direct memory", e);
            return -1;
        }
    }
    
    /**
     * Log current memory statistics.
     */
    public static void logMemoryStats() {
        Runtime runtime = Runtime.getRuntime();
        
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        long heapMax = runtime.maxMemory();
        long directUsed = getUsedDirectMemory();
        long directMax = getMaxDirectMemory();
        
        Log.d(TAG, "=== Memory Statistics ===");
        Log.d(TAG, String.format("Heap Memory: %d MB / %d MB (%.1f%%)",
                heapUsed / 1024 / 1024, 
                heapMax / 1024 / 1024,
                (heapUsed * 100.0) / heapMax));
        
        if (directUsed >= 0 && directMax >= 0) {
            Log.d(TAG, String.format("Direct Memory: %d MB / %d MB (%.1f%%)",
                    directUsed / 1024 / 1024,
                    directMax / 1024 / 1024,
                    (directUsed * 100.0) / directMax));
        } else {
            Log.d(TAG, "Direct Memory: Unable to determine");
        }
        
        Log.d(TAG, "========================");
    }
    
    /**
     * Check if there's sufficient direct memory available.
     * 
     * @param requiredBytes Required memory in bytes
     * @return true if sufficient memory available
     */
    public static boolean hasSufficientDirectMemory(long requiredBytes) {
        long maxDirect = getMaxDirectMemory();
        long usedDirect = getUsedDirectMemory();
        
        if (maxDirect < 0 || usedDirect < 0) {
            // Cannot determine, assume it's available
            Log.w(TAG, "Cannot determine direct memory limits, assuming sufficient");
            return true;
        }
        
        long available = maxDirect - usedDirect;
        boolean sufficient = available >= requiredBytes;
        
        Log.d(TAG, String.format("Direct memory check: required=%d MB, available=%d MB, sufficient=%s",
                requiredBytes / 1024 / 1024,
                available / 1024 / 1024,
                sufficient));
        
        return sufficient;
    }
    
    /**
     * Force cleanup of a DirectByteBuffer.
     * 
     * @param buffer Buffer to clean up
     */
    public static void cleanDirectBuffer(ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect()) {
            return;
        }
        
        try {
            // Use Cleaner API to immediately release direct memory
            Method cleanerMethod = buffer.getClass().getMethod("cleaner");
            cleanerMethod.setAccessible(true);
            Object cleaner = cleanerMethod.invoke(buffer);
            
            if (cleaner != null) {
                Method cleanMethod = cleaner.getClass().getMethod("clean");
                cleanMethod.invoke(cleaner);
                Log.d(TAG, "DirectBuffer cleaned successfully");
            }
            
        } catch (Exception e) {
            Log.w(TAG, "Failed to clean DirectBuffer", e);
        }
    }
    
    /**
     * Detect potential memory leaks by checking if direct memory usage
     * is growing unexpectedly.
     * 
     * @param expectedMaxMB Expected maximum direct memory usage in MB
     * @return true if potential leak detected
     */
    public static boolean detectMemoryLeak(int expectedMaxMB) {
        long usedDirect = getUsedDirectMemory();
        
        if (usedDirect < 0) {
            return false; // Cannot determine
        }
        
        long usedMB = usedDirect / 1024 / 1024;
        boolean leakSuspected = usedMB > expectedMaxMB;
        
        if (leakSuspected) {
            Log.w(TAG, String.format("Potential memory leak detected: using %d MB, expected max %d MB",
                    usedMB, expectedMaxMB));
        }
        
        return leakSuspected;
    }
    
    /**
     * Validate that a buffer is properly aligned for optimal performance.
     * 
     * @param buffer Buffer to validate
     * @return true if properly aligned
     */
    public static boolean isBufferAligned(ByteBuffer buffer) {
        if (!buffer.isDirect()) {
            return false; // Heap buffers are not aligned for our purposes
        }
        
        long address = getDirectBufferAddress(buffer);
        if (address == 0) {
            return false;
        }
        
        boolean aligned = (address % MEMORY_ALIGNMENT) == 0;
        if (!aligned) {
            Log.w(TAG, String.format("Buffer not optimally aligned: address=0x%x, alignment=%d",
                    address, MEMORY_ALIGNMENT));
        }
        
        return aligned;
    }
}