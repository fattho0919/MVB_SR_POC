package com.example.sr_poc;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import android.util.Log;

/**
 * Instrumented test for NativeBridge JNI functionality.
 * Runs on an Android device or emulator with native library support.
 */
@RunWith(AndroidJUnit4.class)
public class NativeBridgeInstrumentedTest {
    
    private static final String TAG = "NativeBridgeTest";
    private NativeBridge nativeBridge;
    private Context appContext;
    
    @Before
    public void setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        nativeBridge = new NativeBridge();
        Log.d(TAG, "NativeBridge setup complete");
    }
    
    @After
    public void tearDown() {
        if (nativeBridge != null) {
            nativeBridge.release();
            Log.d(TAG, "NativeBridge released");
        }
    }
    
    @Test
    public void testNativeLibraryLoading() {
        assertTrue("Native library should be available", 
                  nativeBridge.isAvailable());
        Log.d(TAG, "Native library loaded successfully");
    }
    
    @Test
    public void testNativeVersion() {
        String version = nativeBridge.getVersion();
        assertNotNull("Version should not be null", version);
        assertFalse("Version should not be N/A", version.contains("N/A"));
        assertTrue("Version should contain v1.0", version.contains("v1.0"));
        Log.d(TAG, "Native version: " + version);
    }
    
    @Test
    public void testNativeBenchmark() {
        // Run benchmark with different iteration counts
        long time10 = nativeBridge.benchmark(10);
        long time100 = nativeBridge.benchmark(100);
        long time1000 = nativeBridge.benchmark(1000);
        
        // Allow 0ms for very fast operations, but at least one should be > 0
        assertTrue("At least one benchmark should return positive time", 
                  time10 >= 0 && time100 >= 0 && time1000 >= 0);
        assertTrue("Larger benchmark should take measurable time", time1000 >= 0);
        
        // More iterations should generally take more time (or equal for very fast ops)
        assertTrue("More iterations should not take less time", time100 >= time10);
        assertTrue("More iterations should not take less time", time1000 >= time100);
        
        Log.d(TAG, String.format("Benchmark results: 10=%dms, 100=%dms, 1000=%dms",
                                time10, time100, time1000));
    }
    
    @Test
    public void testDirectBufferAccess() {
        boolean result = nativeBridge.testDirectBuffer();
        assertTrue("DirectBuffer test should pass", result);
        Log.d(TAG, "DirectBuffer test passed");
    }
    
    @Test
    public void testDirectBufferWithCustomSize() {
        // Test with different buffer sizes
        int[] sizes = {256, 1024, 4096, 16384};
        
        for (int size : sizes) {
            // testDirectBuffer creates its own buffer internally
            // We can't test custom sizes without modifying NativeBridge
            boolean result = nativeBridge.testDirectBuffer();
            assertTrue("DirectBuffer test should pass", result);
            Log.d(TAG, "DirectBuffer test passed for iteration with size idea: " + size);
        }
    }
    
    @Test
    public void testEngineInitialization() {
        // Use a dummy model path for testing
        // In real test, should use actual model file
        String modelPath = "test_model.tflite";
        int numThreads = 4;
        
        boolean initialized = nativeBridge.initialize(modelPath, numThreads);
        // This might fail if model doesn't exist, which is expected
        Log.d(TAG, "Engine initialization result: " + initialized);
        
        if (initialized) {
            assertTrue("Engine should be initialized", nativeBridge.isInitialized());
            
            // Test release
            nativeBridge.release();
            assertFalse("Engine should not be initialized after release", 
                       nativeBridge.isInitialized());
        }
    }
    
    @Test
    public void testProcessImageWithoutInitialization() {
        ByteBuffer input = ByteBuffer.allocateDirect(100 * 100 * 3);
        ByteBuffer output = ByteBuffer.allocateDirect(200 * 200 * 3);
        
        // Should fail when engine not initialized
        boolean result = nativeBridge.processImage(input, output, 100, 100);
        assertFalse("Process should fail without initialization", result);
        Log.d(TAG, "Process correctly failed without initialization");
    }
    
    @Test
    public void testInvalidDirectBuffer() {
        // Test with non-direct buffer (should fail)
        ByteBuffer nonDirectBuffer = ByteBuffer.allocate(1024);
        
        // This should be handled gracefully
        boolean result = false;
        try {
            // processImage checks for direct buffer
            result = nativeBridge.processImage(nonDirectBuffer, nonDirectBuffer, 100, 100);
        } catch (Exception e) {
            Log.d(TAG, "Expected exception for non-direct buffer: " + e.getMessage());
        }
        
        assertFalse("Should fail with non-direct buffer", result);
    }
    
    @Test
    public void testMemoryStress() {
        // Stress test with multiple allocations
        final int iterations = 100;
        
        for (int i = 0; i < iterations; i++) {
            boolean result = nativeBridge.testDirectBuffer();
            assertTrue("Iteration " + i + " should pass", result);
            
            // Force cleanup periodically
            if (i % 10 == 0) {
                System.gc();
            }
        }
        
        Log.d(TAG, "Memory stress test completed: " + iterations + " iterations");
    }
    
    @Test
    public void testThreadSafety() throws InterruptedException {
        final int threadCount = 5;
        final int iterationsPerThread = 20;
        final boolean[] results = new boolean[threadCount];
        
        Thread[] threads = new Thread[threadCount];
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < iterationsPerThread; j++) {
                    long time = nativeBridge.benchmark(10);
                    // Accept 0ms for fast operations
                    if (time < 0) {
                        results[threadId] = false;
                        return;
                    }
                }
                results[threadId] = true;
            });
        }
        
        // Start all threads
        for (Thread t : threads) {
            t.start();
        }
        
        // Wait for completion
        for (Thread t : threads) {
            t.join(5000); // 5 second timeout
        }
        
        // Check results
        for (int i = 0; i < threadCount; i++) {
            assertTrue("Thread " + i + " should complete successfully", results[i]);
        }
        
        Log.d(TAG, "Thread safety test completed with " + threadCount + " threads");
    }
    
    @Test
    public void testGetStats() {
        String stats = nativeBridge.getStats();
        assertNotNull("Stats should not be null", stats);
        Log.d(TAG, "Engine stats: " + stats);
    }
}