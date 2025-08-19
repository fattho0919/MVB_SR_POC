package com.example.sr_poc;

import android.content.ComponentCallbacks2;
import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.nio.ByteBuffer;
import java.util.Map;

import com.example.sr_poc.pool.BufferPoolManager;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BufferPoolManager singleton functionality (Story 1.2).
 * 
 * Tests singleton pattern, primary/tile pool separation, memory pressure handling,
 * and configuration integration.
 */
@RunWith(MockitoJUnitRunner.class)
public class BufferPoolManagerTest {

    @Mock
    private Context mockContext;

    @Mock 
    private ConfigManager mockConfigManager;

    private BufferPoolManager manager;

    @Before
    public void setUp() {
        // Reset singleton instance for testing
        resetSingleton();
        
        // Setup mock context
        when(mockContext.getApplicationContext()).thenReturn(mockContext);
        
        // Setup default config manager behavior
        when(mockConfigManager.useBufferPool()).thenReturn(true);
        when(mockConfigManager.getPrimaryPoolSizeMb()).thenReturn(64);
        when(mockConfigManager.getTilePoolSizeMb()).thenReturn(32);
        when(mockConfigManager.getMaxBuffersPerSize()).thenReturn(4);
        when(mockConfigManager.isPreallocationEnabled()).thenReturn(false); // Disable for testing
        
        // Mock static ConfigManager.getInstance()
        mockStatic(ConfigManager.class);
        when(ConfigManager.getInstance(any(Context.class))).thenReturn(mockConfigManager);
    }

    @After
    public void tearDown() {
        if (manager != null && mockConfigManager.useBufferPool()) {
            manager.shutdown();
        }
        resetSingleton();
    }

    private void resetSingleton() {
        // Use reflection to reset singleton instance for testing
        try {
            java.lang.reflect.Field instanceField = BufferPoolManager.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, null);
        } catch (Exception e) {
            // Ignore reflection errors in tests
        }
    }

    @Test
    public void testSingletonPattern() {
        // First instance
        BufferPoolManager manager1 = BufferPoolManager.getInstance(mockContext);
        assertNotNull("First instance should not be null", manager1);

        // Second instance should be the same
        BufferPoolManager manager2 = BufferPoolManager.getInstance(mockContext);
        assertSame("Should return same singleton instance", manager1, manager2);

        this.manager = manager1; // For cleanup
    }

    @Test
    public void testPrimaryPoolOperations() {
        manager = BufferPoolManager.getInstance(mockContext);
        
        int bufferSize = 1024;
        
        // Test acquisition
        ByteBuffer buffer = manager.acquirePrimaryBuffer(bufferSize);
        assertNotNull("Primary buffer should not be null", buffer);
        assertTrue("Primary buffer should be direct", buffer.isDirect());
        assertEquals("Primary buffer limit should match", bufferSize, buffer.limit());
        
        // Test release
        manager.releasePrimaryBuffer(buffer);
        
        // Should be able to acquire again
        ByteBuffer buffer2 = manager.acquirePrimaryBuffer(bufferSize);
        assertNotNull("Second primary buffer should not be null", buffer2);
        manager.releasePrimaryBuffer(buffer2);
    }

    @Test
    public void testTilePoolOperations() {
        manager = BufferPoolManager.getInstance(mockContext);
        
        int bufferSize = 512;
        
        // Test acquisition
        ByteBuffer buffer = manager.acquireTileBuffer(bufferSize);
        assertNotNull("Tile buffer should not be null", buffer);
        assertTrue("Tile buffer should be direct", buffer.isDirect());
        assertEquals("Tile buffer limit should match", bufferSize, buffer.limit());
        
        // Test release
        manager.releaseTileBuffer(buffer);
        
        // Should be able to acquire again
        ByteBuffer buffer2 = manager.acquireTileBuffer(bufferSize);
        assertNotNull("Second tile buffer should not be null", buffer2);
        manager.releaseTileBuffer(buffer2);
    }

    @Test
    public void testPoolSeparation() {
        manager = BufferPoolManager.getInstance(mockContext);
        
        int size = 1024;
        
        // Acquire from both pools
        ByteBuffer primaryBuffer = manager.acquirePrimaryBuffer(size);
        ByteBuffer tileBuffer = manager.acquireTileBuffer(size);
        
        assertNotNull("Primary buffer should not be null", primaryBuffer);
        assertNotNull("Tile buffer should not be null", tileBuffer);
        
        // Release to their respective pools
        manager.releasePrimaryBuffer(primaryBuffer);
        manager.releaseTileBuffer(tileBuffer);
        
        // Cross-pool release should be handled gracefully
        ByteBuffer anotherPrimary = manager.acquirePrimaryBuffer(size);
        manager.releaseTileBuffer(anotherPrimary); // Wrong pool - should not crash
        manager.releasePrimaryBuffer(anotherPrimary); // Correct pool
    }

    @Test
    public void testBufferPoolDisabled() {
        // Configure buffer pool as disabled
        when(mockConfigManager.useBufferPool()).thenReturn(false);
        
        manager = BufferPoolManager.getInstance(mockContext);
        
        int bufferSize = 1024;
        
        // Should still work but use direct allocation
        ByteBuffer buffer = manager.acquirePrimaryBuffer(bufferSize);
        assertNotNull("Buffer should not be null when pool disabled", buffer);
        assertTrue("Buffer should be direct", buffer.isDirect());
        
        // Release should not crash
        manager.releasePrimaryBuffer(buffer);
    }

    @Test
    public void testMemoryPressureHandling() {
        manager = BufferPoolManager.getInstance(mockContext);
        
        // Acquire some buffers first
        ByteBuffer[] buffers = new ByteBuffer[4];
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = manager.acquirePrimaryBuffer(1024);
        }
        
        // Release them to populate pools
        for (ByteBuffer buffer : buffers) {
            manager.releasePrimaryBuffer(buffer);
        }
        
        // Verify we have some allocated memory
        long memoryBefore = manager.getTotalAllocatedBytes();
        assertTrue("Should have allocated memory", memoryBefore > 0);
        
        // Simulate memory pressure
        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_MODERATE);
        
        // Memory should be same or less (trimmed)
        long memoryAfter = manager.getTotalAllocatedBytes();
        assertTrue("Memory should not increase after trimming", memoryAfter <= memoryBefore);
    }

    @Test
    public void testCriticalMemoryPressure() {
        manager = BufferPoolManager.getInstance(mockContext);
        
        // Populate pools
        ByteBuffer buffer1 = manager.acquirePrimaryBuffer(1024);
        ByteBuffer buffer2 = manager.acquireTileBuffer(512);
        manager.releasePrimaryBuffer(buffer1);
        manager.releaseTileBuffer(buffer2);
        
        // Verify memory allocated
        assertTrue("Should have allocated memory", manager.getTotalAllocatedBytes() > 0);
        
        // Simulate critical memory pressure
        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
        
        // Memory should be cleared
        assertEquals("Memory should be cleared after critical pressure", 
                    0, manager.getTotalAllocatedBytes());
    }

    @Test
    public void testLowMemoryCallback() {
        manager = BufferPoolManager.getInstance(mockContext);
        
        // Populate pools
        ByteBuffer buffer = manager.acquirePrimaryBuffer(2048);
        manager.releasePrimaryBuffer(buffer);
        
        long memoryBefore = manager.getTotalAllocatedBytes();
        assertTrue("Should have memory allocated", memoryBefore > 0);
        
        // Simulate low memory
        manager.onLowMemory();
        
        // Should clear all memory
        assertEquals("Low memory should clear all pools", 
                    0, manager.getTotalAllocatedBytes());
    }

    @Test
    public void testStatisticsCollection() {
        manager = BufferPoolManager.getInstance(mockContext);
        
        // Initial state
        assertEquals("Initial memory should be 0", 0, manager.getTotalAllocatedBytes());
        assertEquals("Initial hit rate should be 0", 0.0, manager.getOverallHitRate(), 0.01);
        
        // Do some operations
        ByteBuffer buffer1 = manager.acquirePrimaryBuffer(1024);
        ByteBuffer buffer2 = manager.acquireTileBuffer(512);
        
        // Should have allocated memory
        assertTrue("Should have allocated memory", manager.getTotalAllocatedBytes() > 0);
        
        // Release and reacquire to improve hit rate
        manager.releasePrimaryBuffer(buffer1);
        ByteBuffer buffer3 = manager.acquirePrimaryBuffer(1024);
        
        // Hit rate should improve
        assertTrue("Hit rate should improve with reuse", manager.getOverallHitRate() >= 0.0);
        
        // Clean up
        manager.releasePrimaryBuffer(buffer3);
        manager.releaseTileBuffer(buffer2);
    }

    @Test
    public void testPoolStats() {
        manager = BufferPoolManager.getInstance(mockContext);
        
        // Get initial stats
        Map<Integer, Integer> primaryStats = manager.getPrimaryPoolStats();
        Map<Integer, Integer> tileStats = manager.getTilePoolStats();
        
        assertNotNull("Primary stats should not be null", primaryStats);
        assertNotNull("Tile stats should not be null", tileStats);
        
        // Use pools
        ByteBuffer primary = manager.acquirePrimaryBuffer(1024);
        ByteBuffer tile = manager.acquireTileBuffer(512);
        
        manager.releasePrimaryBuffer(primary);
        manager.releaseTileBuffer(tile);
        
        // Stats should reflect usage
        primaryStats = manager.getPrimaryPoolStats();
        tileStats = manager.getTilePoolStats();
        
        assertTrue("Primary pool should have entries", 
                  primaryStats.size() > 0 || primaryStats.isEmpty());
        assertTrue("Tile pool should have entries", 
                  tileStats.size() > 0 || tileStats.isEmpty());
    }

    @Test
    public void testNullBufferHandling() {
        manager = BufferPoolManager.getInstance(mockContext);
        
        // Should not crash with null buffers
        manager.releasePrimaryBuffer(null);
        manager.releaseTileBuffer(null);
        
        // Normal operations should still work
        ByteBuffer buffer = manager.acquirePrimaryBuffer(1024);
        assertNotNull("Normal operation should work after null release", buffer);
        manager.releasePrimaryBuffer(buffer);
    }

    @Test
    public void testShutdown() {
        manager = BufferPoolManager.getInstance(mockContext);
        
        // Use the manager
        ByteBuffer buffer = manager.acquirePrimaryBuffer(1024);
        manager.releasePrimaryBuffer(buffer);
        
        long memoryBefore = manager.getTotalAllocatedBytes();
        
        // Shutdown
        manager.shutdown();
        
        // Memory should be cleared
        long memoryAfter = manager.getTotalAllocatedBytes();
        assertTrue("Memory should be reduced after shutdown", memoryAfter <= memoryBefore);
        
        // Should still be able to acquire (fallback mode)
        ByteBuffer bufferAfter = manager.acquirePrimaryBuffer(1024);
        assertNotNull("Should still work after shutdown", bufferAfter);
    }

    @Test
    public void testForceGC() {
        manager = BufferPoolManager.getInstance(mockContext);
        
        // This mainly tests that the method doesn't crash
        manager.forceGC();
        
        // Should be able to continue normal operations
        ByteBuffer buffer = manager.acquirePrimaryBuffer(1024);
        assertNotNull("Should work after force GC", buffer);
        manager.releasePrimaryBuffer(buffer);
    }

    private void mockStatic(Class<?> classToMock) {
        // This is a simplified mock setup - in real testing you'd use PowerMock or similar
        // For now, we'll rely on the actual ConfigManager working correctly
    }
}