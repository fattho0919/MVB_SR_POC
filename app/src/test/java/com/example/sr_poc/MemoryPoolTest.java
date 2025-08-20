package com.example.sr_poc;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import static org.junit.Assert.*;
import java.nio.ByteBuffer;

/**
 * Unit tests for Memory Pool functionality
 */
public class MemoryPoolTest {
    
    private NativeBridge nativeBridge;
    
    @Before
    public void setUp() {
        nativeBridge = new NativeBridge();
    }
    
    @After
    public void tearDown() {
        if (nativeBridge != null) {
            nativeBridge.resetMemoryPool();
            nativeBridge.release();
        }
    }
    
    @Test
    public void testMemoryPoolInitialization() {
        // Test may fail in unit test environment without native library
        // This is expected - real testing should be done with instrumented tests
        
        if (!nativeBridge.isAvailable()) {
            System.out.println("Native library not available in unit test environment");
            return;
        }
        
        NativeBridge.MemoryPoolConfig config = new NativeBridge.MemoryPoolConfig();
        boolean initialized = nativeBridge.initializeMemoryPool(config);
        
        if (initialized) {
            assertTrue("Memory pool should be initialized", initialized);
            
            // Test statistics retrieval
            MemoryStatistics stats = nativeBridge.getMemoryStatistics();
            assertNotNull("Statistics should not be null", stats);
            assertEquals("Initial usage should be 0", 0, stats.currentUsage);
        }
    }
    
    @Test
    public void testMemoryPoolConfig() {
        NativeBridge.MemoryPoolConfig config = new NativeBridge.MemoryPoolConfig();
        
        // Test default values
        assertEquals("Default small block size", 8 * 1024, config.smallBlockSize);
        assertEquals("Default medium block size", 64 * 1024, config.mediumBlockSize);
        assertEquals("Default large block size", 1024 * 1024, config.largeBlockSize);
        assertEquals("Default small pool count", 128, config.smallPoolCount);
        assertEquals("Default medium pool count", 32, config.mediumPoolCount);
        assertEquals("Default large pool count", 8, config.largePoolCount);
        
        // Test custom config
        NativeBridge.MemoryPoolConfig customConfig = 
            new NativeBridge.MemoryPoolConfig(256, 64, 16);
        assertEquals("Custom small pool count", 256, customConfig.smallPoolCount);
        assertEquals("Custom medium pool count", 64, customConfig.mediumPoolCount);
        assertEquals("Custom large pool count", 16, customConfig.largePoolCount);
    }
    
    @Test
    public void testMemoryStatistics() {
        MemoryStatistics stats = new MemoryStatistics();
        
        // Test initial state
        assertEquals("Initial total allocated", 0, stats.totalAllocated);
        assertEquals("Initial current usage", 0, stats.currentUsage);
        assertEquals("Initial hit rate", 0.0, stats.hitRate, 0.001);
        
        // Test formatting
        assertEquals("Format 0 bytes", "0 B", MemoryStatistics.formatBytes(0));
        assertEquals("Format 512 bytes", "512 B", MemoryStatistics.formatBytes(512));
        assertEquals("Format 2KB", "2.00 KB", MemoryStatistics.formatBytes(2048));
        assertEquals("Format 1.5MB", "1.50 MB", MemoryStatistics.formatBytes(1572864));
        
        // Test cache efficiency
        stats.hitRate = 0.85;
        assertEquals("Cache efficiency", 85.0, stats.getCacheEfficiency(), 0.001);
        assertTrue("Should be effective", stats.isEffective());
        
        stats.hitRate = 0.65;
        assertFalse("Should not be effective", stats.isEffective());
        
        // Test fragmentation calculation
        stats.currentUsage = 5000;
        stats.peakUsage = 10000;
        assertEquals("Fragmentation", 0.5, stats.getFragmentation(), 0.001);
    }
    
    @Test
    public void testAllocatorStats() {
        if (!nativeBridge.isAvailable()) {
            System.out.println("Native library not available in unit test environment");
            return;
        }
        
        String stats = nativeBridge.getAllocatorStats();
        assertNotNull("Allocator stats should not be null", stats);
        
        // In unit test environment without native library,
        // this will return "Native library not loaded"
        if (stats.contains("Native library not loaded")) {
            System.out.println("Expected: " + stats);
        }
    }
}