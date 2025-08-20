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
import java.util.ArrayList;
import java.util.List;
import android.util.Log;

/**
 * Instrumented test for Memory Pool functionality
 * Runs on actual device/emulator with native library support
 */
@RunWith(AndroidJUnit4.class)
public class MemoryPoolInstrumentedTest {
    
    private static final String TAG = "MemoryPoolTest";
    private NativeBridge nativeBridge;
    private Context appContext;
    
    @Before
    public void setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        nativeBridge = new NativeBridge();
        
        assertTrue("Native library should be available", nativeBridge.isAvailable());
        
        // Initialize memory pool with default config
        boolean initialized = nativeBridge.initializeMemoryPool();
        assertTrue("Memory pool should initialize", initialized);
        
        Log.d(TAG, "Memory pool initialized for testing");
    }
    
    @After
    public void tearDown() {
        if (nativeBridge != null) {
            // Get final statistics before cleanup
            MemoryStatistics finalStats = nativeBridge.getMemoryStatistics();
            Log.d(TAG, "Final statistics: " + finalStats.toString());
            
            nativeBridge.resetMemoryPool();
            nativeBridge.release();
        }
    }
    
    @Test
    public void testMemoryPoolInitialization() {
        // Pool should already be initialized in setUp
        MemoryStatistics stats = nativeBridge.getMemoryStatistics();
        assertNotNull("Statistics should not be null", stats);
        
        // Initial state checks
        assertEquals("Initial current usage should be 0", 0, stats.currentUsage);
        assertEquals("Initial allocations should be 0", 0, stats.allocationCount);
        
        Log.d(TAG, "Initial stats: " + stats.toString());
    }
    
    @Test
    public void testAlignedBufferAllocation() {
        // Test various alignments
        int[] alignments = {16, 32, 64};
        int[] sizes = {1024, 8192, 65536};
        
        for (int alignment : alignments) {
            for (int size : sizes) {
                ByteBuffer buffer = nativeBridge.allocateAlignedBuffer(size, alignment);
                assertNotNull("Buffer should be allocated for size=" + size + 
                            ", alignment=" + alignment, buffer);
                
                assertTrue("Buffer should be direct", buffer.isDirect());
                assertEquals("Buffer capacity should match requested size", 
                           size, buffer.capacity());
                
                // Verify we can write and read
                buffer.putInt(0, 0x12345678);
                assertEquals("Should read back written value", 
                           0x12345678, buffer.getInt(0));
                
                // Clean up
                nativeBridge.deallocateBuffer(buffer);
            }
        }
        
        // Check statistics after allocations
        MemoryStatistics stats = nativeBridge.getMemoryStatistics();
        assertTrue("Should have allocation count > 0", stats.allocationCount > 0);
        Log.d(TAG, "After allocation test: " + stats.toString());
    }
    
    @Test
    public void testMemoryPoolPerformance() {
        final int iterations = 1000;
        final int bufferSize = 8192; // 8KB - should hit small pool
        
        // Warmup
        nativeBridge.warmupMemoryPool();
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            ByteBuffer buffer = nativeBridge.allocateAlignedBuffer(bufferSize);
            assertNotNull("Buffer should be allocated", buffer);
            
            // Simple write to ensure buffer is usable
            buffer.putInt(0, i);
            
            nativeBridge.deallocateBuffer(buffer);
        }
        
        long elapsedNanos = System.nanoTime() - startTime;
        long elapsedMillis = elapsedNanos / 1_000_000;
        
        Log.d(TAG, String.format("Allocated/deallocated %d buffers in %d ms (%.2f μs per operation)",
                               iterations, elapsedMillis, 
                               (double)elapsedNanos / (iterations * 1000)));
        
        // Check pool efficiency
        MemoryStatistics stats = nativeBridge.getMemoryStatistics();
        double hitRate = stats.getCacheEfficiency();
        
        Log.d(TAG, String.format("Pool statistics: allocations=%d, hit rate=%.2f%%",
                               stats.allocationCount, hitRate));
        
        // Should have high hit rate for repeated same-size allocations
        assertTrue("Hit rate should be > 70%", hitRate > 70.0);
    }
    
    @Test
    public void testMultipleSizeAllocations() {
        List<ByteBuffer> buffers = new ArrayList<>();
        
        // Allocate buffers of different sizes to test all pools
        int[] sizes = {
            4 * 1024,      // 4KB - small pool
            8 * 1024,      // 8KB - small pool
            32 * 1024,     // 32KB - medium pool
            64 * 1024,     // 64KB - medium pool
            512 * 1024,    // 512KB - large pool
            1024 * 1024    // 1MB - large pool
        };
        
        for (int size : sizes) {
            ByteBuffer buffer = nativeBridge.allocateAlignedBuffer(size);
            assertNotNull("Should allocate buffer of size " + size, buffer);
            assertEquals("Buffer capacity should match", size, buffer.capacity());
            buffers.add(buffer);
        }
        
        // Check statistics
        MemoryStatistics stats = nativeBridge.getMemoryStatistics();
        assertTrue("Current usage should be > 0", stats.currentUsage > 0);
        assertEquals("Allocation count should match", sizes.length, stats.allocationCount);
        
        Log.d(TAG, "After multi-size allocation: " + stats.toString());
        
        // Deallocate all
        for (ByteBuffer buffer : buffers) {
            nativeBridge.deallocateBuffer(buffer);
        }
        
        // Check after deallocation
        stats = nativeBridge.getMemoryStatistics();
        assertEquals("Current usage should be 0 after deallocation", 0, stats.currentUsage);
        assertEquals("Deallocation count should match allocation count", 
                   stats.allocationCount, stats.deallocationCount);
    }
    
    @Test
    public void testMemoryPoolReset() {
        // Allocate some buffers
        List<ByteBuffer> buffers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            buffers.add(nativeBridge.allocateAlignedBuffer(8192));
        }
        
        MemoryStatistics beforeReset = nativeBridge.getMemoryStatistics();
        assertTrue("Should have allocations before reset", beforeReset.allocationCount > 0);
        
        // Reset pool
        nativeBridge.resetMemoryPool();
        
        // Statistics should be cleared
        MemoryStatistics afterReset = nativeBridge.getMemoryStatistics();
        assertEquals("Allocations should be 0 after reset", 0, afterReset.allocationCount);
        assertEquals("Current usage should be 0 after reset", 0, afterReset.currentUsage);
        
        // Should still be able to allocate after reset
        ByteBuffer newBuffer = nativeBridge.allocateAlignedBuffer(8192);
        assertNotNull("Should be able to allocate after reset", newBuffer);
        nativeBridge.deallocateBuffer(newBuffer);
    }
    
    @Test
    public void testConcurrentAllocations() throws InterruptedException {
        final int threadCount = 5;
        final int allocationsPerThread = 100;
        final Thread[] threads = new Thread[threadCount];
        final boolean[] success = new boolean[threadCount];
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                try {
                    for (int j = 0; j < allocationsPerThread; j++) {
                        ByteBuffer buffer = nativeBridge.allocateAlignedBuffer(4096);
                        if (buffer == null) {
                            success[threadId] = false;
                            return;
                        }
                        
                        // Simple operation
                        buffer.putInt(0, threadId * 1000 + j);
                        
                        // Small delay to increase concurrency
                        Thread.sleep(1);
                        
                        nativeBridge.deallocateBuffer(buffer);
                    }
                    success[threadId] = true;
                } catch (Exception e) {
                    Log.e(TAG, "Thread " + threadId + " failed", e);
                    success[threadId] = false;
                }
            });
        }
        
        // Start all threads
        for (Thread t : threads) {
            t.start();
        }
        
        // Wait for completion
        for (Thread t : threads) {
            t.join(10000); // 10 second timeout
        }
        
        // Check results
        for (int i = 0; i < threadCount; i++) {
            assertTrue("Thread " + i + " should complete successfully", success[i]);
        }
        
        // Check final statistics
        MemoryStatistics stats = nativeBridge.getMemoryStatistics();
        Log.d(TAG, "After concurrent test: " + stats.toString());
        
        // Should have processed all allocations
        assertTrue("Should have processed many allocations", 
                  stats.allocationCount >= threadCount * allocationsPerThread);
    }
    
    @Test
    public void testMemoryPoolDump() {
        // Allocate some buffers to populate pools
        ByteBuffer small = nativeBridge.allocateAlignedBuffer(4096);
        ByteBuffer medium = nativeBridge.allocateAlignedBuffer(32768);
        ByteBuffer large = nativeBridge.allocateAlignedBuffer(524288);
        
        // Dump state (will output to logcat)
        nativeBridge.dumpMemoryPoolState();
        
        // Get allocator stats
        String allocatorStats = nativeBridge.getAllocatorStats();
        assertNotNull("Allocator stats should not be null", allocatorStats);
        Log.d(TAG, "Allocator stats:\n" + allocatorStats);
        
        // Clean up
        nativeBridge.deallocateBuffer(small);
        nativeBridge.deallocateBuffer(medium);
        nativeBridge.deallocateBuffer(large);
    }
}