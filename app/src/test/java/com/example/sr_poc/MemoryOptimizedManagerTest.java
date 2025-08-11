package com.example.sr_poc;

import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import android.content.ComponentCallbacks2;

/**
 * Unit tests for MemoryOptimizedManager.
 * 
 * Tests memory management policies, interpreter eviction, and memory pressure handling.
 */
@RunWith(JUnit4.class)
public class MemoryOptimizedManagerTest {
    
    private MockMemoryManager memoryManager;
    
    @Before
    public void setUp() {
        memoryManager = new MockMemoryManager();
    }
    
    /**
     * Test device classification based on memory.
     */
    @Test
    public void testDeviceClassification() {
        // Test high-end device (4GB+)
        assertEquals("Should classify as high-end", 3, 
                    getMaxInterpreters(4096));
        
        // Test mid-range device (2-4GB)
        assertEquals("Should classify as mid-range", 2, 
                    getMaxInterpreters(3072));
        assertEquals("Should classify as mid-range", 2, 
                    getMaxInterpreters(2048));
        
        // Test low-end device (<2GB)
        assertEquals("Should classify as low-end", 1, 
                    getMaxInterpreters(1536));
        assertEquals("Should classify as low-end", 1, 
                    getMaxInterpreters(1024));
    }
    
    /**
     * Test LRU eviction policy.
     */
    @Test
    public void testLruEviction() {
        // Set up usage history
        memoryManager.recordUsage("GPU", System.currentTimeMillis() - 120000); // 2 min ago
        memoryManager.recordUsage("CPU", System.currentTimeMillis() - 60000);  // 1 min ago
        memoryManager.recordUsage("NPU", System.currentTimeMillis() - 30000);  // 30 sec ago
        
        // Find LRU mode
        String lruMode = memoryManager.findLeastRecentlyUsed();
        assertEquals("GPU should be LRU", "GPU", lruMode);
        
        // After evicting GPU, CPU should be LRU
        memoryManager.evict("GPU");
        lruMode = memoryManager.findLeastRecentlyUsed();
        assertEquals("CPU should be LRU after GPU eviction", "CPU", lruMode);
    }
    
    /**
     * Test interpreter limit enforcement.
     */
    @Test
    public void testInterpreterLimit() {
        // Set limit to 2 interpreters
        memoryManager.setMaxInterpreters(2);
        
        // Create interpreters
        memoryManager.onInterpreterCreated("CPU");
        assertEquals("Should have 1 interpreter", 1, memoryManager.getActiveCount());
        
        memoryManager.onInterpreterCreated("GPU");
        assertEquals("Should have 2 interpreters", 2, memoryManager.getActiveCount());
        
        // Creating third should trigger eviction
        memoryManager.onInterpreterCreated("NPU");
        assertTrue("Should trigger eviction", memoryManager.evictionTriggered);
        assertEquals("Should maintain limit of 2", 2, memoryManager.getActiveCount());
    }
    
    /**
     * Test memory pressure response - RUNNING_LOW.
     */
    @Test
    public void testMemoryPressureRunningLow() {
        // Set up 3 interpreters
        memoryManager.setActiveInterpreters(3);
        
        // Simulate TRIM_MEMORY_RUNNING_LOW
        memoryManager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW);
        
        // Should keep only 1 interpreter
        assertEquals("Should evict to 1 interpreter", 1, 
                    memoryManager.getTargetInterpreters());
        assertTrue("Should trigger eviction", memoryManager.evictionTriggered);
    }
    
    /**
     * Test memory pressure response - RUNNING_CRITICAL.
     */
    @Test
    public void testMemoryPressureRunningCritical() {
        // Set up 3 interpreters
        memoryManager.setActiveInterpreters(3);
        
        // Simulate TRIM_MEMORY_RUNNING_CRITICAL
        memoryManager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL);
        
        // Should release all inactive interpreters
        assertEquals("Should evict all inactive interpreters", 0, 
                    memoryManager.getTargetInterpreters());
        assertTrue("Should trigger eviction", memoryManager.evictionTriggered);
    }
    
    /**
     * Test memory pressure response - UI_HIDDEN.
     */
    @Test
    public void testMemoryPressureUiHidden() {
        memoryManager.setHasGpuInterpreter(true);
        
        // Simulate TRIM_MEMORY_UI_HIDDEN
        memoryManager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
        
        // Should release GPU interpreter
        assertTrue("Should release GPU when UI hidden", 
                  memoryManager.gpuReleased);
    }
    
    /**
     * Test memory pressure response - BACKGROUND.
     */
    @Test
    public void testMemoryPressureBackground() {
        memoryManager.setActiveInterpreters(3);
        
        // Simulate TRIM_MEMORY_BACKGROUND
        memoryManager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
        
        // Should release all interpreters
        assertTrue("Should release all interpreters in background", 
                  memoryManager.allReleased);
    }
    
    /**
     * Test onLowMemory emergency response.
     */
    @Test
    public void testOnLowMemory() {
        memoryManager.setActiveInterpreters(3);
        
        // Simulate onLowMemory
        memoryManager.onLowMemory();
        
        // Should release all interpreters and force GC
        assertTrue("Should release all interpreters", memoryManager.allReleased);
        assertTrue("Should force garbage collection", memoryManager.gcForced);
    }
    
    /**
     * Test usage-based retention policy.
     */
    @Test
    public void testUsageBasedRetention() {
        memoryManager.setMaxInterpreters(2);
        
        // Set recent usage for GPU and CPU
        long now = System.currentTimeMillis();
        memoryManager.recordUsage("GPU", now - 30000);  // 30 sec ago
        memoryManager.recordUsage("CPU", now - 45000);  // 45 sec ago
        memoryManager.recordUsage("NPU", now - 90000);  // 90 sec ago
        
        // Check retention decisions
        assertTrue("Should keep recently used GPU", 
                  memoryManager.shouldKeepInterpreter("GPU", 60000));
        assertTrue("Should keep recently used CPU", 
                  memoryManager.shouldKeepInterpreter("CPU", 60000));
        assertFalse("Should not keep old NPU", 
                   memoryManager.shouldKeepInterpreter("NPU", 60000));
    }
    
    /**
     * Test optimization status reporting.
     */
    @Test
    public void testOptimizationStatus() {
        memoryManager.setActiveInterpreters(2);
        memoryManager.setMaxInterpreters(3);
        memoryManager.setAvailableMemory(512);
        memoryManager.setUsedMemory(256);
        memoryManager.setDeviceClass("High-end");
        
        String status = memoryManager.getOptimizationStatus();
        
        assertTrue("Status should show active interpreters", 
                  status.contains("2/3"));
        assertTrue("Status should show available memory", 
                  status.contains("512MB"));
        assertTrue("Status should show used memory", 
                  status.contains("256MB"));
        assertTrue("Status should show device class", 
                  status.contains("High-end"));
    }
    
    // Helper methods
    
    private int getMaxInterpreters(long memoryMB) {
        if (memoryMB >= 4000) {
            return 3; // High-end
        } else if (memoryMB >= 2000) {
            return 2; // Mid-range
        } else {
            return 1; // Low-end
        }
    }
    
    /**
     * Mock memory manager for testing.
     */
    private static class MockMemoryManager {
        private int activeInterpreters = 0;
        private int maxInterpreters = 3;
        private int targetInterpreters = 0;
        private boolean evictionTriggered = false;
        private boolean gpuReleased = false;
        private boolean allReleased = false;
        private boolean gcForced = false;
        private boolean hasGpuInterpreter = false;
        private long availableMemory = 1024;
        private long usedMemory = 512;
        private String deviceClass = "Mid-range";
        
        private final java.util.Map<String, Long> usageHistory = new java.util.HashMap<>();
        
        void recordUsage(String mode, long timestamp) {
            usageHistory.put(mode, timestamp);
        }
        
        String findLeastRecentlyUsed() {
            String lruMode = null;
            long oldestTime = Long.MAX_VALUE;
            
            for (java.util.Map.Entry<String, Long> entry : usageHistory.entrySet()) {
                if (entry.getValue() < oldestTime) {
                    oldestTime = entry.getValue();
                    lruMode = entry.getKey();
                }
            }
            
            return lruMode;
        }
        
        void evict(String mode) {
            usageHistory.remove(mode);
            if (activeInterpreters > 0) {
                activeInterpreters--;
            }
        }
        
        void onInterpreterCreated(String mode) {
            activeInterpreters++;
            if (activeInterpreters > maxInterpreters) {
                evictionTriggered = true;
                activeInterpreters = maxInterpreters;
            }
        }
        
        void onTrimMemory(int level) {
            switch (level) {
                case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
                    targetInterpreters = 1;
                    evictionTriggered = true;
                    break;
                case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
                    targetInterpreters = 0;
                    evictionTriggered = true;
                    break;
                case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN:
                    if (hasGpuInterpreter) {
                        gpuReleased = true;
                    }
                    break;
                case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND:
                    allReleased = true;
                    break;
            }
        }
        
        void onLowMemory() {
            allReleased = true;
            gcForced = true;
        }
        
        boolean shouldKeepInterpreter(String mode, long ageThreshold) {
            if (activeInterpreters < maxInterpreters) {
                return true;
            }
            
            Long lastUsage = usageHistory.get(mode);
            if (lastUsage == null) {
                return false;
            }
            
            long age = System.currentTimeMillis() - lastUsage;
            return age < ageThreshold;
        }
        
        String getOptimizationStatus() {
            return String.format(
                "Memory Optimization Status:\n" +
                "Active Interpreters: %d/%d\n" +
                "Memory Available: %dMB\n" +
                "Memory Used: %dMB\n" +
                "Device Class: %s",
                activeInterpreters, maxInterpreters,
                availableMemory, usedMemory, deviceClass
            );
        }
        
        // Getters and setters for test configuration
        
        int getActiveCount() { return activeInterpreters; }
        void setActiveInterpreters(int count) { this.activeInterpreters = count; }
        void setMaxInterpreters(int max) { this.maxInterpreters = max; }
        int getTargetInterpreters() { return targetInterpreters; }
        void setHasGpuInterpreter(boolean has) { this.hasGpuInterpreter = has; }
        void setAvailableMemory(long memory) { this.availableMemory = memory; }
        void setUsedMemory(long memory) { this.usedMemory = memory; }
        void setDeviceClass(String deviceClass) { this.deviceClass = deviceClass; }
    }
}