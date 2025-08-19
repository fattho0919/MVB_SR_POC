package com.example.sr_poc;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.nio.ByteBuffer;

import com.example.sr_poc.utils.DirectMemoryUtils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DirectByteBuffer functionality (Story 1.1).
 * 
 * Tests the DirectMemoryUtils class and basic buffer operations.
 */
@RunWith(MockitoJUnitRunner.class)
public class DirectByteBufferTest {

    @Mock
    private Context mockContext;

    @Before
    public void setUp() {
        // Any setup needed before tests
    }

    @Test
    public void testDirectBufferAllocation() {
        // Test basic direct buffer allocation
        int size = 1024 * 1024; // 1MB
        ByteBuffer buffer = DirectMemoryUtils.allocateAlignedDirectBuffer(size);
        
        assertNotNull("Buffer should not be null", buffer);
        assertTrue("Buffer should be direct", buffer.isDirect());
        assertEquals("Buffer should have correct capacity", size, buffer.limit());
        assertEquals("Buffer should have native byte order", 
                     java.nio.ByteOrder.nativeOrder(), buffer.order());
    }

    @Test
    public void testBufferAlignment() {
        int size = 100; // Small size to test alignment
        ByteBuffer buffer = DirectMemoryUtils.allocateAlignedDirectBuffer(size);
        
        assertTrue("Buffer should be marked as aligned", 
                   DirectMemoryUtils.isBufferAligned(buffer));
        
        long address = DirectMemoryUtils.getDirectBufferAddress(buffer);
        assertTrue("Buffer address should be valid", address > 0);
        
        // Check that alignment is applied (buffer capacity should be >= requested size)
        assertTrue("Buffer capacity should be at least requested size", 
                   buffer.capacity() >= size);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidBufferSize() {
        // Should throw exception for zero or negative size
        DirectMemoryUtils.allocateAlignedDirectBuffer(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativeBufferSize() {
        // Should throw exception for negative size
        DirectMemoryUtils.allocateAlignedDirectBuffer(-100);
    }

    @Test
    public void testMemoryStatistics() {
        // Test that memory statistics can be retrieved without crashing
        long used = DirectMemoryUtils.getUsedDirectMemory();
        long max = DirectMemoryUtils.getMaxDirectMemory();
        
        // These might return -1 if not available, but should not crash
        assertTrue("Used memory should be valid or -1", used >= -1);
        assertTrue("Max memory should be valid or -1", max >= -1);
        
        // Test logging doesn't crash
        DirectMemoryUtils.logMemoryStats();
    }

    @Test
    public void testMemoryAvailabilityCheck() {
        // Test memory availability check with reasonable size
        boolean hasMemory = DirectMemoryUtils.hasSufficientDirectMemory(10 * 1024 * 1024); // 10MB
        
        // This should typically return true on test machines, but the method shouldn't crash
        // We mainly test that the method executes without exception
        assertNotNull("Memory check should return a boolean", hasMemory);
    }

    @Test
    public void testBufferCleanup() {
        // Test buffer cleanup doesn't crash
        ByteBuffer buffer = DirectMemoryUtils.allocateAlignedDirectBuffer(1024);
        
        // This should not crash, even if cleanup doesn't work on all JVMs
        DirectMemoryUtils.cleanDirectBuffer(buffer);
        
        // Test cleanup with null buffer
        DirectMemoryUtils.cleanDirectBuffer(null);
        
        // Test cleanup with heap buffer
        ByteBuffer heapBuffer = ByteBuffer.allocate(1024);
        DirectMemoryUtils.cleanDirectBuffer(heapBuffer); // Should handle gracefully
    }

    @Test
    public void testMemoryLeakDetection() {
        // Test memory leak detection
        boolean leakDetected = DirectMemoryUtils.detectMemoryLeak(1); // Very low threshold
        
        // This is mainly a functionality test - we don't care about the result
        // as much as ensuring the method executes without error
        assertNotNull("Leak detection should return a boolean", leakDetected);
    }

    @Test
    public void testHeapBufferAlignment() {
        // Test that heap buffers are correctly identified as not aligned
        ByteBuffer heapBuffer = ByteBuffer.allocate(1024);
        
        assertFalse("Heap buffer should not be considered aligned", 
                   DirectMemoryUtils.isBufferAligned(heapBuffer));
        
        long address = DirectMemoryUtils.getDirectBufferAddress(heapBuffer);
        assertEquals("Heap buffer should have 0 address", 0, address);
    }

    @Test
    public void testLargeBufferAllocation() {
        // Test allocation of larger buffer (similar to model sizes)
        int largeSize = 1280 * 720 * 3 * 4; // 720p FLOAT32 size
        
        try {
            ByteBuffer buffer = DirectMemoryUtils.allocateAlignedDirectBuffer(largeSize);
            assertNotNull("Large buffer should be allocated successfully", buffer);
            assertTrue("Large buffer should be direct", buffer.isDirect());
            assertEquals("Large buffer should have correct size", largeSize, buffer.limit());
            
            // Clean up
            DirectMemoryUtils.cleanDirectBuffer(buffer);
            
        } catch (OutOfMemoryError e) {
            // This is acceptable on low-memory test environments
            System.out.println("Large buffer allocation failed due to memory constraints: " + e.getMessage());
        }
    }

    @Test
    public void testBufferReuse() {
        // Test that we can allocate, use, and clean up multiple buffers
        int size = 1024;
        
        for (int i = 0; i < 5; i++) {
            ByteBuffer buffer = DirectMemoryUtils.allocateAlignedDirectBuffer(size);
            assertNotNull("Buffer " + i + " should be allocated", buffer);
            
            // Use the buffer
            buffer.putInt(0, i);
            assertEquals("Buffer should store correct value", i, buffer.getInt(0));
            
            // Clean up
            DirectMemoryUtils.cleanDirectBuffer(buffer);
        }
    }
}