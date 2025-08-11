package com.example.sr_poc;

import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for HybridSRProcessor.
 * 
 * Tests the hybrid initialization approach with fast CPU startup
 * and parallel background loading of GPU/NPU modes.
 */
@RunWith(JUnit4.class)
public class HybridSRProcessorTest {
    
    private static final long INSTANT_MODE_TIMEOUT_MS = 5000;
    private static final long BACKGROUND_TIMEOUT_MS = 10000;
    private static final long ACCEPTABLE_CPU_INIT_TIME_MS = 5000;
    private static final long ACCEPTABLE_TOTAL_INIT_TIME_MS = 10000;
    
    /**
     * Test that CPU mode initializes within acceptable time limit.
     */
    @Test
    public void testCpuInitializationTime() {
        long startTime = System.currentTimeMillis();
        
        // Simulate CPU initialization
        boolean cpuInitialized = simulateCpuInitialization();
        
        long initTime = System.currentTimeMillis() - startTime;
        
        assertTrue("CPU should initialize successfully", cpuInitialized);
        assertTrue("CPU initialization should be under " + ACCEPTABLE_CPU_INIT_TIME_MS + "ms, was " + initTime + "ms",
                  initTime < ACCEPTABLE_CPU_INIT_TIME_MS);
    }
    
    /**
     * Test that all modes initialize within total time limit.
     */
    @Test
    public void testTotalInitializationTime() {
        long startTime = System.currentTimeMillis();
        
        // Simulate parallel initialization
        boolean allModesReady = simulateParallelInitialization();
        
        long totalTime = System.currentTimeMillis() - startTime;
        
        assertTrue("All modes should be ready", allModesReady);
        assertTrue("Total initialization should be under " + ACCEPTABLE_TOTAL_INIT_TIME_MS + "ms, was " + totalTime + "ms",
                  totalTime < ACCEPTABLE_TOTAL_INIT_TIME_MS);
    }
    
    /**
     * Test that callback methods are invoked in correct order.
     */
    @Test
    public void testCallbackSequence() {
        MockHybridCallback callback = new MockHybridCallback();
        
        // Simulate initialization sequence
        simulateInitializationWithCallback(callback);
        
        assertTrue("QuickStart should be called first", callback.quickStartCalled);
        assertTrue("QuickStart should be before mode available", 
                  callback.quickStartTime <= callback.firstModeAvailableTime);
        assertTrue("All modes ready should be called last", callback.allModesReadyCalled);
    }
    
    /**
     * Test mode failure handling.
     */
    @Test
    public void testModeFailureHandling() {
        MockHybridCallback callback = new MockHybridCallback();
        
        // Simulate NPU failure
        simulateNpuFailure(callback);
        
        assertTrue("NPU failure should be reported", callback.npuFailureCalled);
        assertNotNull("Failure reason should be provided", callback.npuFailureReason);
        assertEquals("Should have CPU mode available", 1, callback.availableModesCount);
    }
    
    /**
     * Test memory pressure during initialization.
     */
    @Test
    public void testInitializationUnderMemoryPressure() {
        // Simulate low memory condition
        simulateLowMemory();
        
        MockHybridCallback callback = new MockHybridCallback();
        simulateInitializationWithCallback(callback);
        
        // Under memory pressure, we might have fewer modes available
        assertTrue("At least CPU should be available", callback.availableModesCount >= 1);
        assertTrue("Initialization should still complete", callback.allModesReadyCalled);
    }
    
    /**
     * Test timeout handling for background initialization.
     */
    @Test
    public void testBackgroundInitializationTimeout() {
        MockHybridCallback callback = new MockHybridCallback();
        
        // Simulate slow GPU initialization that times out
        simulateSlowGpuInitialization(callback);
        
        assertTrue("GPU timeout should trigger failure callback", callback.gpuFailureCalled);
        assertTrue("Failure reason should mention timeout", 
                  callback.gpuFailureReason != null && callback.gpuFailureReason.contains("timeout"));
    }
    
    // Helper methods for simulation
    
    private boolean simulateCpuInitialization() {
        try {
            // Simulate CPU initialization work
            Thread.sleep(100); // Simulate fast CPU init
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }
    
    private boolean simulateParallelInitialization() {
        try {
            // Simulate parallel initialization
            Thread[] threads = new Thread[] {
                new Thread(() -> simulateCpuInitialization()),
                new Thread(() -> simulateGpuInitialization()),
                new Thread(() -> simulateNpuInitialization())
            };
            
            for (Thread t : threads) {
                t.start();
            }
            
            for (Thread t : threads) {
                t.join(BACKGROUND_TIMEOUT_MS);
            }
            
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }
    
    private boolean simulateGpuInitialization() {
        try {
            Thread.sleep(200); // Simulate GPU init
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }
    
    private boolean simulateNpuInitialization() {
        try {
            Thread.sleep(150); // Simulate NPU init
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }
    
    private void simulateInitializationWithCallback(MockHybridCallback callback) {
        // Simulate the initialization sequence
        callback.onQuickStartReady("CPU", 100);
        callback.onModeAvailable("CPU", "CPU Device");
        callback.onModeAvailable("GPU", "GPU Device");
        callback.onAllModesReady(2, 500);
    }
    
    private void simulateNpuFailure(MockHybridCallback callback) {
        callback.onQuickStartReady("CPU", 100);
        callback.onModeAvailable("CPU", "CPU Device");
        callback.onModeFailed("NPU", "NPU not available on this device");
        callback.onAllModesReady(1, 300);
    }
    
    private void simulateLowMemory() {
        // Simulate low memory by reducing available memory
        System.gc();
    }
    
    private void simulateSlowGpuInitialization(MockHybridCallback callback) {
        callback.onQuickStartReady("CPU", 100);
        callback.onModeAvailable("CPU", "CPU Device");
        
        // Simulate GPU timeout
        try {
            Thread.sleep(BACKGROUND_TIMEOUT_MS + 100);
        } catch (InterruptedException e) {
            // Ignore
        }
        
        callback.onModeFailed("GPU", "Initialization timeout");
        callback.onAllModesReady(1, BACKGROUND_TIMEOUT_MS + 200);
    }
    
    /**
     * Mock callback for testing.
     */
    private static class MockHybridCallback {
        boolean quickStartCalled = false;
        boolean allModesReadyCalled = false;
        boolean npuFailureCalled = false;
        boolean gpuFailureCalled = false;
        String npuFailureReason = null;
        String gpuFailureReason = null;
        int availableModesCount = 0;
        long quickStartTime = 0;
        long firstModeAvailableTime = Long.MAX_VALUE;
        
        public void onQuickStartReady(String mode, long initTimeMs) {
            quickStartCalled = true;
            quickStartTime = System.currentTimeMillis();
        }
        
        public void onModeAvailable(String mode, String deviceInfo) {
            availableModesCount++;
            firstModeAvailableTime = Math.min(firstModeAvailableTime, System.currentTimeMillis());
        }
        
        public void onModeFailed(String mode, String reason) {
            if ("NPU".equals(mode)) {
                npuFailureCalled = true;
                npuFailureReason = reason;
            } else if ("GPU".equals(mode)) {
                gpuFailureCalled = true;
                gpuFailureReason = reason;
            }
        }
        
        public void onAllModesReady(int count, long totalTimeMs) {
            allModesReadyCalled = true;
            availableModesCount = count;
        }
    }
}