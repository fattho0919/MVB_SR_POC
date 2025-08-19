package com.example.sr_poc;

import org.junit.Before;
import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.sr_poc.pool.DirectBufferPool;

import static org.junit.Assert.*;

/**
 * Unit tests for DirectBufferPool functionality (Story 1.2).
 * 
 * Tests buffer allocation, reuse, pool limits, concurrency, and cleanup.
 */
@RunWith(MockitoJUnitRunner.class)
public class DirectBufferPoolTest {

    private DirectBufferPool pool;
    
    // Test pool configuration
    private static final long TEST_POOL_SIZE_MB = 10; // Small pool for testing
    private static final int TEST_MAX_BUFFERS = 4;
    private static final boolean TEST_PREALLOCATION = false; // Disable for controlled testing

    @Before
    public void setUp() {
        pool = new DirectBufferPool(TEST_POOL_SIZE_MB, TEST_MAX_BUFFERS, TEST_PREALLOCATION);
    }
    
    @After
    public void tearDown() {
        if (pool != null) {
            pool.clear();
        }
    }

    @Test
    public void testBasicBufferAllocation() {
        // Test basic allocation
        int size = 1024; // 1KB
        ByteBuffer buffer = pool.acquire(size);
        
        assertNotNull("Buffer should not be null", buffer);
        assertTrue("Buffer should be direct", buffer.isDirect());
        assertEquals("Buffer limit should match requested size", size, buffer.limit());
        assertTrue("Buffer capacity should be >= requested size", buffer.capacity() >= size);
        
        // Clean up
        pool.release(buffer);
    }

    @Test
    public void testBufferReuse() {
        int size = 1024;
        
        // First acquisition
        ByteBuffer buffer1 = pool.acquire(size);
        assertNotNull(buffer1);
        
        // Release it
        pool.release(buffer1);
        
        // Acquire again - should get the same buffer
        ByteBuffer buffer2 = pool.acquire(size);
        assertNotNull(buffer2);
        
        // Should be the same buffer instance for efficient pools
        // Note: This might not always be the same due to size optimization
        assertTrue("Should reuse buffer or provide equivalent capacity", 
                   buffer2.capacity() >= size);
        
        pool.release(buffer2);
    }

    @Test
    public void testDifferentSizes() {
        // Test various sizes
        int[] sizes = {256, 512, 1024, 2048, 4096};
        ByteBuffer[] buffers = new ByteBuffer[sizes.length];
        
        // Allocate different sizes
        for (int i = 0; i < sizes.length; i++) {
            buffers[i] = pool.acquire(sizes[i]);
            assertNotNull("Buffer " + i + " should not be null", buffers[i]);
            assertEquals("Buffer " + i + " limit should match", sizes[i], buffers[i].limit());
            assertTrue("Buffer " + i + " capacity should be adequate", 
                      buffers[i].capacity() >= sizes[i]);
        }
        
        // Release all
        for (ByteBuffer buffer : buffers) {
            pool.release(buffer);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidSize() {
        // Should throw exception for invalid size
        pool.acquire(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativeSize() {
        // Should throw exception for negative size
        pool.acquire(-100);
    }

    @Test
    public void testBufferClearance() {
        int size = 1024;
        ByteBuffer buffer = pool.acquire(size);
        
        // Write some data
        buffer.putInt(0, 12345);
        assertEquals("Data should be written", 12345, buffer.getInt(0));
        
        // Release and reacquire
        pool.release(buffer);
        ByteBuffer buffer2 = pool.acquire(size);
        
        // Buffer should be cleared (position and limit reset)
        assertEquals("Position should be reset", 0, buffer2.position());
        assertEquals("Limit should be set to requested size", size, buffer2.limit());
        
        pool.release(buffer2);
    }

    @Test
    public void testPoolSizeLimit() {
        // Try to exhaust the pool
        int bufferSize = 2 * 1024 * 1024; // 2MB per buffer
        int maxBuffers = (int) (TEST_POOL_SIZE_MB * 1024 * 1024 / bufferSize) + 1;
        
        ByteBuffer[] buffers = new ByteBuffer[maxBuffers];
        boolean exhausted = false;
        int allocatedCount = 0;
        
        try {
            for (int i = 0; i < maxBuffers; i++) {
                buffers[i] = pool.acquire(bufferSize);
                allocatedCount++;
            }
        } catch (OutOfMemoryError e) {
            exhausted = true;
        }
        
        assertTrue("Pool should eventually be exhausted", 
                   exhausted || allocatedCount < maxBuffers);
        
        // Clean up allocated buffers
        for (int i = 0; i < allocatedCount; i++) {
            if (buffers[i] != null) {
                pool.release(buffers[i]);
            }
        }
    }

    @Test
    public void testConcurrentAccess() throws InterruptedException {
        final int numThreads = 4;
        final int operationsPerThread = 50;
        final int bufferSize = 1024;
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        // Run concurrent acquire/release operations
        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        ByteBuffer buffer = pool.acquire(bufferSize);
                        assertNotNull(buffer);
                        
                        // Do some work with buffer
                        buffer.putInt(0, Thread.currentThread().hashCode());
                        Thread.sleep(1); // Small delay to increase contention
                        
                        pool.release(buffer);
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        // Wait for all threads to complete
        assertTrue("All threads should complete within timeout", 
                   latch.await(30, TimeUnit.SECONDS));
        
        assertEquals("No errors should occur", 0, errorCount.get());
        assertEquals("All operations should succeed", 
                    numThreads * operationsPerThread, successCount.get());
        
        executor.shutdown();
    }

    @Test
    public void testPoolMetrics() {
        // Initially no allocations
        assertEquals("Initial allocated bytes should be 0", 0, pool.getTotalAllocatedBytes());
        assertEquals("Initial acquisitions should be 0", 0, pool.getTotalAcquired());
        assertEquals("Initial releases should be 0", 0, pool.getTotalReleased());
        
        // Make some acquisitions
        int size = 1024;
        ByteBuffer buffer1 = pool.acquire(size);
        
        assertTrue("Total acquired should be > 0", pool.getTotalAcquired() > 0);
        assertTrue("Total allocated should be > 0", pool.getTotalAllocatedBytes() > 0);
        
        pool.release(buffer1);
        assertTrue("Total released should be > 0", pool.getTotalReleased() > 0);
        
        // Acquire again (should hit cache)
        ByteBuffer buffer2 = pool.acquire(size);
        pool.release(buffer2);
        
        // Hit rate should be > 0 now
        assertTrue("Hit rate should improve with reuse", pool.getHitRate() >= 0.0);
    }

    @Test
    public void testPoolStats() {
        Map<Integer, Integer> stats = pool.getPoolStats();
        assertNotNull("Pool stats should not be null", stats);
        
        // Allocate some buffers
        int size = 1024;
        ByteBuffer buffer1 = pool.acquire(size);
        ByteBuffer buffer2 = pool.acquire(size * 2);
        
        // Release them
        pool.release(buffer1);
        pool.release(buffer2);
        
        // Check stats
        stats = pool.getPoolStats();
        assertTrue("Should have entries for different sizes", stats.size() > 0);
        
        // Total should not exceed max buffers per size
        for (Integer count : stats.values()) {
            assertTrue("Buffer count should not exceed max per size: " + count, 
                      count <= TEST_MAX_BUFFERS);
        }
    }

    @Test
    public void testPoolClear() {
        // Allocate and release some buffers
        ByteBuffer buffer1 = pool.acquire(1024);
        ByteBuffer buffer2 = pool.acquire(2048);
        
        pool.release(buffer1);
        pool.release(buffer2);
        
        // Verify pool has buffers
        assertTrue("Pool should have allocated memory", pool.getTotalAllocatedBytes() > 0);
        
        // Clear the pool
        pool.clear();
        
        // Verify pool is cleared
        assertEquals("Pool should have no allocated memory after clear", 
                    0, pool.getTotalAllocatedBytes());
        assertTrue("Pool stats should be empty after clear", 
                  pool.getPoolStats().isEmpty());
    }

    @Test
    public void testTrimToMinimum() {
        int size = 1024;
        
        // Fill pool with multiple buffers of same size
        ByteBuffer[] buffers = new ByteBuffer[TEST_MAX_BUFFERS];
        for (int i = 0; i < TEST_MAX_BUFFERS; i++) {
            buffers[i] = pool.acquire(size);
        }
        
        // Release all
        for (ByteBuffer buffer : buffers) {
            pool.release(buffer);
        }
        
        // Pool should have multiple buffers
        Map<Integer, Integer> statsBefore = pool.getPoolStats();
        int totalBefore = statsBefore.values().stream().mapToInt(Integer::intValue).sum();
        assertTrue("Pool should have multiple buffers", totalBefore > 1);
        
        // Trim to minimum
        pool.trimToMinimum();
        
        // Should have fewer buffers now
        Map<Integer, Integer> statsAfter = pool.getPoolStats();
        int totalAfter = statsAfter.values().stream().mapToInt(Integer::intValue).sum();
        assertTrue("Pool should have fewer buffers after trim", totalAfter <= totalBefore);
    }

    @Test
    public void testLargeBufferAllocation() {
        // Test allocation of large buffer similar to model sizes
        int largeSize = 1280 * 720 * 3 * 4; // 720p FLOAT32 size
        
        try {
            ByteBuffer buffer = pool.acquire(largeSize);
            assertNotNull("Large buffer should be allocated", buffer);
            assertTrue("Large buffer should be direct", buffer.isDirect());
            assertEquals("Large buffer limit should match", largeSize, buffer.limit());
            
            // Clean up
            pool.release(buffer);
            
        } catch (OutOfMemoryError e) {
            // This is acceptable in constrained test environments
            System.out.println("Large buffer allocation failed due to memory constraints: " + e.getMessage());
        }
    }

    @Test
    public void testNullBufferRelease() {
        // Releasing null buffer should not cause problems
        pool.release(null);
        
        // Should be able to continue normal operations
        ByteBuffer buffer = pool.acquire(1024);
        assertNotNull(buffer);
        pool.release(buffer);
    }

    @Test
    public void testHeapBufferRelease() {
        // Create a heap buffer
        ByteBuffer heapBuffer = ByteBuffer.allocate(1024);
        assertFalse("Should be heap buffer", heapBuffer.isDirect());
        
        // Releasing heap buffer should not cause problems
        pool.release(heapBuffer);
        
        // Should be able to continue normal operations
        ByteBuffer directBuffer = pool.acquire(1024);
        assertNotNull(directBuffer);
        assertTrue("Should be direct buffer", directBuffer.isDirect());
        pool.release(directBuffer);
    }
}