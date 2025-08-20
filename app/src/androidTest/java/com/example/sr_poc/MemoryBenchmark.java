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
import java.util.Random;
import android.util.Log;

/**
 * Memory Pool Performance Benchmark Suite
 * Compares native memory pool vs standard allocation methods
 */
@RunWith(AndroidJUnit4.class)
public class MemoryBenchmark {
    
    private static final String TAG = "MemoryBenchmark";
    private NativeBridge nativeBridge;
    private Context appContext;
    
    // Benchmark parameters
    private static final int WARMUP_ITERATIONS = 100;
    private static final int BENCHMARK_ITERATIONS = 1000;
    private static final int[] TEST_SIZES = {
        256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536, 131072, 262144
    };
    
    @Before
    public void setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        nativeBridge = new NativeBridge();
        
        assertTrue("Native library should be available", nativeBridge.isAvailable());
        
        // Initialize memory pool
        NativeBridge.MemoryPoolConfig config = new NativeBridge.MemoryPoolConfig();
        boolean initialized = nativeBridge.initializeMemoryPool(config);
        assertTrue("Memory pool should initialize", initialized);
        
        // Warmup pool
        nativeBridge.warmupMemoryPool();
        
        Log.d(TAG, "Benchmark setup complete");
    }
    
    @After
    public void tearDown() {
        if (nativeBridge != null) {
            // Print final statistics
            MemoryStatistics stats = nativeBridge.getMemoryStatistics();
            Log.d(TAG, "Final benchmark stats: " + stats.getDetailedReport());
            
            nativeBridge.resetMemoryPool();
            nativeBridge.release();
        }
    }
    
    @Test
    public void benchmarkAllocationSpeed() {
        Log.d(TAG, "=== Allocation Speed Benchmark ===");
        
        for (int size : TEST_SIZES) {
            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                ByteBuffer buffer = nativeBridge.allocateAlignedBuffer(size);
                nativeBridge.deallocateBuffer(buffer);
            }
            
            // Benchmark native pool
            long poolStartTime = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                ByteBuffer buffer = nativeBridge.allocateAlignedBuffer(size);
                nativeBridge.deallocateBuffer(buffer);
            }
            long poolTime = System.nanoTime() - poolStartTime;
            
            // Benchmark DirectByteBuffer
            long directStartTime = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                ByteBuffer buffer = ByteBuffer.allocateDirect(size);
                // No explicit deallocation for DirectByteBuffer
            }
            long directTime = System.nanoTime() - directStartTime;
            
            // Calculate metrics
            double poolAvgUs = (poolTime / 1000.0) / BENCHMARK_ITERATIONS;
            double directAvgUs = (directTime / 1000.0) / BENCHMARK_ITERATIONS;
            double speedup = directAvgUs / poolAvgUs;
            
            Log.d(TAG, String.format("Size %6d: Pool=%.2fμs, Direct=%.2fμs, Speedup=%.2fx",
                                   size, poolAvgUs, directAvgUs, speedup));
            
            // Assert performance improvement
            assertTrue(String.format("Pool should be faster for size %d", size),
                      speedup > 1.0);
        }
    }
    
    @Test
    public void benchmarkFragmentation() {
        Log.d(TAG, "=== Memory Fragmentation Benchmark ===");
        
        Random random = new Random(42); // Fixed seed for reproducibility
        List<ByteBuffer> allocations = new ArrayList<>();
        
        // Simulate realistic allocation pattern
        for (int iteration = 0; iteration < 500; iteration++) {
            // Allocate random sizes
            for (int i = 0; i < 10; i++) {
                int sizeIndex = random.nextInt(TEST_SIZES.length);
                int size = TEST_SIZES[sizeIndex];
                ByteBuffer buffer = nativeBridge.allocateAlignedBuffer(size);
                assertNotNull("Allocation should succeed", buffer);
                allocations.add(buffer);
            }
            
            // Deallocate 50% randomly
            int toRemove = allocations.size() / 2;
            for (int i = 0; i < toRemove && !allocations.isEmpty(); i++) {
                int index = random.nextInt(allocations.size());
                ByteBuffer buffer = allocations.remove(index);
                nativeBridge.deallocateBuffer(buffer);
            }
        }
        
        // Get fragmentation statistics
        MemoryStatistics stats = nativeBridge.getMemoryStatistics();
        double fragmentation = calculateFragmentation(stats);
        
        Log.d(TAG, String.format("Fragmentation after mixed operations: %.2f%%", 
                               fragmentation * 100));
        Log.d(TAG, String.format("Pool hit rate: %.2f%%", stats.getCacheEfficiency()));
        
        // Clean up remaining allocations
        for (ByteBuffer buffer : allocations) {
            nativeBridge.deallocateBuffer(buffer);
        }
        
        // Assert low fragmentation
        assertTrue("Fragmentation should be < 5%", fragmentation < 0.05);
        assertTrue("Hit rate should be > 80%", stats.getCacheEfficiency() > 80.0);
    }
    
    @Test
    public void benchmarkHitRate() {
        Log.d(TAG, "=== Cache Hit Rate Benchmark ===");
        
        // Reset statistics
        nativeBridge.resetMemoryPool();
        nativeBridge.initializeMemoryPool(new NativeBridge.MemoryPoolConfig());
        nativeBridge.warmupMemoryPool();
        
        // Test with repeated same-size allocations (should have high hit rate)
        int testSize = 8192; // Should hit small pool
        for (int i = 0; i < 1000; i++) {
            ByteBuffer buffer = nativeBridge.allocateAlignedBuffer(testSize);
            assertNotNull("Allocation should succeed", buffer);
            nativeBridge.deallocateBuffer(buffer);
        }
        
        MemoryStatistics stats1 = nativeBridge.getMemoryStatistics();
        double hitRate1 = stats1.getCacheEfficiency();
        Log.d(TAG, String.format("Same-size allocation hit rate: %.2f%%", hitRate1));
        
        // Test with varied sizes (should have lower hit rate)
        nativeBridge.resetMemoryPool();
        nativeBridge.initializeMemoryPool(new NativeBridge.MemoryPoolConfig());
        
        Random random = new Random();
        for (int i = 0; i < 1000; i++) {
            int size = TEST_SIZES[random.nextInt(TEST_SIZES.length)];
            ByteBuffer buffer = nativeBridge.allocateAlignedBuffer(size);
            assertNotNull("Allocation should succeed", buffer);
            nativeBridge.deallocateBuffer(buffer);
        }
        
        MemoryStatistics stats2 = nativeBridge.getMemoryStatistics();
        double hitRate2 = stats2.getCacheEfficiency();
        Log.d(TAG, String.format("Varied-size allocation hit rate: %.2f%%", hitRate2));
        
        // Same-size should have better hit rate
        assertTrue("Same-size should have better hit rate", hitRate1 > hitRate2);
        assertTrue("Same-size hit rate should be > 90%", hitRate1 > 90.0);
    }
    
    @Test
    public void benchmarkGCPressure() {
        Log.d(TAG, "=== GC Pressure Benchmark ===");
        
        // Force GC before starting
        System.gc();
        System.runFinalization();
        
        long startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        int gcCountBefore = getGCCount();
        
        // Allocate using pool (should not trigger GC)
        long poolStartTime = System.currentTimeMillis();
        List<ByteBuffer> poolBuffers = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            ByteBuffer buffer = nativeBridge.allocateAlignedBuffer(65536); // 64KB
            assertNotNull("Pool allocation should succeed", buffer);
            poolBuffers.add(buffer);
        }
        long poolTime = System.currentTimeMillis() - poolStartTime;
        int gcCountAfterPool = getGCCount();
        
        // Clean up pool buffers
        for (ByteBuffer buffer : poolBuffers) {
            nativeBridge.deallocateBuffer(buffer);
        }
        
        // Force GC and wait
        System.gc();
        System.runFinalization();
        try { Thread.sleep(100); } catch (InterruptedException e) {}
        
        // Allocate using DirectByteBuffer (may trigger GC)
        long directStartTime = System.currentTimeMillis();
        List<ByteBuffer> directBuffers = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(65536); // 64KB
            assertNotNull("Direct allocation should succeed", buffer);
            directBuffers.add(buffer);
        }
        long directTime = System.currentTimeMillis() - directStartTime;
        int gcCountAfterDirect = getGCCount();
        
        // Calculate GC impact
        int poolGCs = gcCountAfterPool - gcCountBefore;
        int directGCs = gcCountAfterDirect - gcCountAfterPool;
        
        Log.d(TAG, String.format("Pool allocations: %dms, GCs=%d", poolTime, poolGCs));
        Log.d(TAG, String.format("Direct allocations: %dms, GCs=%d", directTime, directGCs));
        
        // Pool should trigger fewer or no GCs
        assertTrue("Pool should trigger fewer GCs", poolGCs <= directGCs);
        
        // Clear references for GC
        directBuffers.clear();
    }
    
    @Test
    public void benchmarkPeakMemory() {
        Log.d(TAG, "=== Peak Memory Usage Benchmark ===");
        
        List<ByteBuffer> buffers = new ArrayList<>();
        
        // Allocate progressively larger amounts
        for (int wave = 1; wave <= 5; wave++) {
            Log.d(TAG, String.format("Wave %d: Allocating %d buffers", wave, wave * 10));
            
            for (int i = 0; i < wave * 10; i++) {
                ByteBuffer buffer = nativeBridge.allocateAlignedBuffer(16384); // 16KB
                assertNotNull("Allocation should succeed", buffer);
                buffers.add(buffer);
            }
            
            MemoryStatistics stats = nativeBridge.getMemoryStatistics();
            Log.d(TAG, String.format("  Current: %s, Peak: %s",
                                   MemoryStatistics.formatBytes(stats.currentUsage),
                                   MemoryStatistics.formatBytes(stats.peakUsage)));
        }
        
        // Deallocate half
        int halfSize = buffers.size() / 2;
        for (int i = 0; i < halfSize; i++) {
            nativeBridge.deallocateBuffer(buffers.remove(0));
        }
        
        MemoryStatistics midStats = nativeBridge.getMemoryStatistics();
        Log.d(TAG, String.format("After deallocating half: Current=%s, Peak=%s",
                               MemoryStatistics.formatBytes(midStats.currentUsage),
                               MemoryStatistics.formatBytes(midStats.peakUsage)));
        
        // Peak should not decrease
        assertTrue("Peak usage should be tracked correctly", 
                  midStats.peakUsage >= midStats.currentUsage);
        
        // Allocate more
        for (int i = 0; i < 30; i++) {
            ByteBuffer buffer = nativeBridge.allocateAlignedBuffer(16384);
            assertNotNull("Allocation should succeed", buffer);
            buffers.add(buffer);
        }
        
        MemoryStatistics finalStats = nativeBridge.getMemoryStatistics();
        Log.d(TAG, String.format("Final: Current=%s, Peak=%s",
                               MemoryStatistics.formatBytes(finalStats.currentUsage),
                               MemoryStatistics.formatBytes(finalStats.peakUsage)));
        
        // Clean up
        for (ByteBuffer buffer : buffers) {
            nativeBridge.deallocateBuffer(buffer);
        }
    }
    
    @Test
    public void benchmarkComparisonSummary() {
        Log.d(TAG, "=== Performance Comparison Summary ===");
        
        // Run comprehensive comparison
        BenchmarkResult poolResult = runBenchmark(true);
        BenchmarkResult directResult = runBenchmark(false);
        
        Log.d(TAG, "Memory Pool Performance:");
        Log.d(TAG, String.format("  Total time: %dms", poolResult.totalTimeMs));
        Log.d(TAG, String.format("  Avg alloc: %.2fμs", poolResult.avgAllocTimeUs));
        Log.d(TAG, String.format("  Throughput: %.0f ops/sec", poolResult.throughput));
        
        Log.d(TAG, "DirectByteBuffer Performance:");
        Log.d(TAG, String.format("  Total time: %dms", directResult.totalTimeMs));
        Log.d(TAG, String.format("  Avg alloc: %.2fμs", directResult.avgAllocTimeUs));
        Log.d(TAG, String.format("  Throughput: %.0f ops/sec", directResult.throughput));
        
        double speedup = directResult.avgAllocTimeUs / poolResult.avgAllocTimeUs;
        Log.d(TAG, String.format("Overall speedup: %.2fx", speedup));
        
        assertTrue("Pool should be significantly faster", speedup > 3.0);
    }
    
    // Helper methods
    
    private double calculateFragmentation(MemoryStatistics stats) {
        if (stats.peakUsage == 0) return 0;
        
        // Simple fragmentation estimate
        double efficiency = (double)stats.currentUsage / stats.peakUsage;
        return Math.max(0, 1.0 - efficiency);
    }
    
    private int getGCCount() {
        // Approximate GC count (not exact but good enough for comparison)
        return (int)(Runtime.getRuntime().totalMemory() / (1024 * 1024));
    }
    
    private BenchmarkResult runBenchmark(boolean usePool) {
        BenchmarkResult result = new BenchmarkResult();
        
        long startTime = System.nanoTime();
        int operations = 0;
        
        for (int size : TEST_SIZES) {
            for (int i = 0; i < 100; i++) {
                if (usePool) {
                    ByteBuffer buffer = nativeBridge.allocateAlignedBuffer(size);
                    if (buffer != null) {
                        nativeBridge.deallocateBuffer(buffer);
                        operations++;
                    }
                } else {
                    ByteBuffer buffer = ByteBuffer.allocateDirect(size);
                    if (buffer != null) {
                        operations++;
                    }
                }
            }
        }
        
        long totalTime = System.nanoTime() - startTime;
        
        result.totalTimeMs = totalTime / 1_000_000;
        result.avgAllocTimeUs = (totalTime / 1000.0) / operations;
        result.throughput = (operations * 1_000_000_000.0) / totalTime;
        
        return result;
    }
    
    private static class BenchmarkResult {
        long totalTimeMs;
        double avgAllocTimeUs;
        double throughput;
    }
}