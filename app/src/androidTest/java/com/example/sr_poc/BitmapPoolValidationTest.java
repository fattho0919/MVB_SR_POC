package com.example.sr_poc;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.sr_poc.pool.BitmapPool;
import com.example.sr_poc.pool.BitmapPoolManager;
import com.example.sr_poc.pool.PooledBitmapFactory;
import com.example.sr_poc.pool.LargeBitmapProcessor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * Comprehensive validation tests for Story 1.3: Bitmap Memory Optimization
 * 
 * Run with: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4.class)
public class BitmapPoolValidationTest {
    
    private static final String TAG = "BitmapPoolValidation";
    
    private Context context;
    private BitmapPoolManager poolManager;
    private PooledBitmapFactory bitmapFactory;
    private LargeBitmapProcessor largeProcessor;
    
    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        poolManager = BitmapPoolManager.getInstance(context);
        bitmapFactory = new PooledBitmapFactory(context);
        largeProcessor = new LargeBitmapProcessor(context);
    }
    
    /**
     * Test 1: Verify bitmap reuse rate > 80%
     */
    @Test
    public void testBitmapReuseRate() {
        Log.d(TAG, "=== Test 1: Bitmap Reuse Rate ===");
        
        // Clear pool to start fresh
        poolManager.clearPool();
        
        // Create and release bitmaps
        int testCount = 100;
        int width = 1280;
        int height = 720;
        
        // First pass - populate pool
        for (int i = 0; i < 10; i++) {
            Bitmap bitmap = bitmapFactory.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            assertNotNull("Bitmap creation failed", bitmap);
            bitmapFactory.releaseBitmap(bitmap);
        }
        
        // Get initial metrics
        BitmapPool.BitmapPoolMetrics metricsStart = poolManager.getMetrics();
        assertNotNull("Metrics should not be null", metricsStart);
        long initialHits = metricsStart.getHits();
        
        // Second pass - should reuse
        for (int i = 0; i < testCount; i++) {
            Bitmap bitmap = bitmapFactory.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            assertNotNull("Bitmap creation failed", bitmap);
            bitmapFactory.releaseBitmap(bitmap);
        }
        
        // Calculate reuse rate
        BitmapPool.BitmapPoolMetrics metricsEnd = poolManager.getMetrics();
        long totalHits = metricsEnd.getHits() - initialHits;
        double reuseRate = metricsEnd.getHitRate();
        
        Log.d(TAG, String.format("Reuse rate: %.2f%% (Hits: %d)", 
                                reuseRate * 100, totalHits));
        
        // Verify > 80% reuse rate
        assertTrue("Reuse rate should be > 80%", reuseRate > 0.80);
        
        // Log detailed metrics
        metricsEnd.logStats(TAG);
    }
    
    /**
     * Test 2: Verify OOM prevention for large images
     */
    @Test
    public void testOOMPrevention() {
        Log.d(TAG, "=== Test 2: OOM Prevention ===");
        
        AtomicInteger oomCount = new AtomicInteger(0);
        int testIterations = 20;
        
        for (int i = 0; i < testIterations; i++) {
            try {
                // Try to create a large bitmap (4K resolution)
                Bitmap largeBitmap = bitmapFactory.createBitmap(3840, 2160, Bitmap.Config.ARGB_8888);
                assertNotNull("Large bitmap should be created", largeBitmap);
                
                // Simulate processing
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                
                // Release back to pool
                bitmapFactory.releaseBitmap(largeBitmap);
                
            } catch (OutOfMemoryError e) {
                oomCount.incrementAndGet();
                Log.w(TAG, "OOM occurred at iteration " + i);
                
                // Try to recover
                System.gc();
                poolManager.clearPool();
            }
        }
        
        double oomFrequency = (double) oomCount.get() / testIterations;
        Log.d(TAG, String.format("OOM frequency: %.2f%% (%d/%d)", 
                                oomFrequency * 100, oomCount.get(), testIterations));
        
        // Verify OOM frequency < 0.01 (1%)
        assertTrue("OOM frequency should be < 1%", oomFrequency < 0.01);
    }
    
    /**
     * Test 3: Measure GC impact
     */
    @Test
    public void testGCImpact() {
        Log.d(TAG, "=== Test 3: GC Impact ===");
        
        // Measure baseline GC
        Runtime runtime = Runtime.getRuntime();
        long startMemory = runtime.totalMemory() - runtime.freeMemory();
        long startTime = System.currentTimeMillis();
        
        // Force initial GC
        System.gc();
        System.runFinalization();
        
        // Track GC events
        long gcStartTime = System.currentTimeMillis();
        int gcCount = 0;
        
        // Process many bitmaps
        int iterations = 500;
        for (int i = 0; i < iterations; i++) {
            Bitmap bitmap = bitmapFactory.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            
            // Simulate work
            bitmap.eraseColor(0xFF000000 | i);
            
            // Release
            bitmapFactory.releaseBitmap(bitmap);
            
            // Check if GC occurred (memory dropped significantly)
            long currentMemory = runtime.totalMemory() - runtime.freeMemory();
            if (currentMemory < startMemory * 0.8) {
                gcCount++;
                startMemory = currentMemory;
            }
        }
        
        long totalTime = System.currentTimeMillis() - gcStartTime;
        double gcFrequency = (double) gcCount / iterations;
        
        Log.d(TAG, String.format("GC frequency: %.2f%% (%d GCs in %d iterations)", 
                                gcFrequency * 100, gcCount, iterations));
        Log.d(TAG, String.format("Total time: %dms, Avg per iteration: %.2fms", 
                                totalTime, (double) totalTime / iterations));
        
        // Verify GC impact is minimal
        assertTrue("GC frequency should be < 5%", gcFrequency < 0.05);
    }
    
    /**
     * Test 4: Verify memory peak for 1080p images
     */
    @Test
    public void testMemoryPeak1080p() {
        Log.d(TAG, "=== Test 4: Memory Peak for 1080p ===");
        
        Runtime runtime = Runtime.getRuntime();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();
        long peakMemory = initialMemory;
        
        // Process multiple 1080p images
        List<Bitmap> bitmaps = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Bitmap bitmap = bitmapFactory.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
            bitmaps.add(bitmap);
            
            long currentMemory = runtime.totalMemory() - runtime.freeMemory();
            peakMemory = Math.max(peakMemory, currentMemory);
        }
        
        // Release all bitmaps
        for (Bitmap bitmap : bitmaps) {
            bitmapFactory.releaseBitmap(bitmap);
        }
        bitmaps.clear();
        
        long memoryIncrease = peakMemory - initialMemory;
        long memoryIncreaseMB = memoryIncrease / (1024 * 1024);
        
        Log.d(TAG, String.format("Peak memory increase: %dMB", memoryIncreaseMB));
        Log.d(TAG, String.format("Initial: %dMB, Peak: %dMB", 
                                initialMemory / (1024 * 1024), 
                                peakMemory / (1024 * 1024)));
        
        // Verify peak memory < 320MB increase for 1080p
        assertTrue("Memory peak should be < 320MB for 1080p", memoryIncreaseMB < 320);
    }
    
    /**
     * Test 5: Verify allocation rate in steady state
     */
    @Test
    public void testAllocationRate() {
        Log.d(TAG, "=== Test 5: Allocation Rate ===");
        
        // Warm up pool
        for (int i = 0; i < 10; i++) {
            Bitmap bitmap = bitmapFactory.createBitmap(1280, 720);
            bitmapFactory.releaseBitmap(bitmap);
        }
        
        // Measure steady state allocation
        Runtime runtime = Runtime.getRuntime();
        long startMemory = runtime.totalMemory() - runtime.freeMemory();
        long startTime = System.currentTimeMillis();
        
        // Process for 10 seconds
        int processedCount = 0;
        while (System.currentTimeMillis() - startTime < 10000) {
            Bitmap bitmap = bitmapFactory.createBitmap(1280, 720);
            // Simulate processing
            bitmap.eraseColor(0xFF00FF00);
            bitmapFactory.releaseBitmap(bitmap);
            processedCount++;
        }
        
        long endTime = System.currentTimeMillis();
        long endMemory = runtime.totalMemory() - runtime.freeMemory();
        
        long duration = endTime - startTime;
        long memoryGrowth = Math.max(0, endMemory - startMemory);
        double allocationRate = (double) memoryGrowth / duration; // bytes per ms
        double allocationRateMBps = allocationRate * 1000 / (1024 * 1024); // MB per second
        
        Log.d(TAG, String.format("Processed %d bitmaps in %dms", processedCount, duration));
        Log.d(TAG, String.format("Memory growth: %dKB", memoryGrowth / 1024));
        Log.d(TAG, String.format("Allocation rate: %.2f MB/s", allocationRateMBps));
        
        // Verify allocation rate < 1MB/s in steady state
        assertTrue("Allocation rate should be < 1MB/s", allocationRateMBps < 1.0);
    }
    
    /**
     * Test 6: Verify concurrent access thread safety
     */
    @Test
    public void testConcurrentAccess() throws InterruptedException {
        Log.d(TAG, "=== Test 6: Concurrent Access ===");
        
        final int numThreads = 10;
        final int operationsPerThread = 100;
        final CountDownLatch latch = new CountDownLatch(numThreads);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        // Random size to test different pools
                        int size = (threadId % 2 == 0) ? 720 : 1080;
                        int width = (size == 720) ? 1280 : 1920;
                        
                        Bitmap bitmap = bitmapFactory.createBitmap(width, size);
                        if (bitmap != null) {
                            // Simulate work
                            bitmap.eraseColor(0xFF000000 | (threadId << 16) | i);
                            try {
                                Thread.sleep(1);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            
                            bitmapFactory.releaseBitmap(bitmap);
                            successCount.incrementAndGet();
                        } else {
                            errorCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Thread " + threadId + " error", e);
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        // Wait for all threads
        assertTrue("Threads should complete within 30s", 
                  latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();
        
        int totalOperations = numThreads * operationsPerThread;
        double successRate = (double) successCount.get() / totalOperations;
        
        Log.d(TAG, String.format("Success rate: %.2f%% (%d/%d)", 
                                successRate * 100, successCount.get(), totalOperations));
        Log.d(TAG, String.format("Errors: %d", errorCount.get()));
        
        // Verify thread safety
        assertTrue("Success rate should be > 99%", successRate > 0.99);
    }
    
    /**
     * Test 7: Verify large image processing with LargeBitmapProcessor
     */
    @Test
    public void testLargeImageProcessing() {
        Log.d(TAG, "=== Test 7: Large Image Processing ===");
        
        // Detect max texture size
        int maxTextureSize = largeProcessor.getMaxTextureSize();
        Log.d(TAG, "Max texture size: " + maxTextureSize);
        
        // Test with 4K image
        int width = 3840;
        int height = 2160;
        
        boolean needsTiling = largeProcessor.needsTiling(width, height);
        Log.d(TAG, String.format("4K image (%dx%d) needs tiling: %b", 
                                width, height, needsTiling));
        
        // Calculate optimal tile size
        int optimalTileSize = largeProcessor.calculateOptimalTileSize(width, height, 512);
        Log.d(TAG, "Optimal tile size: " + optimalTileSize);
        
        // Verify tile layout
        List<LargeBitmapProcessor.TileInfo> tiles = 
            largeProcessor.calculateTileLayout(width, height, optimalTileSize, 2);
        
        Log.d(TAG, String.format("Tile count: %d for 4K image", tiles.size()));
        
        // Verify reasonable tile count
        assertTrue("Tile count should be reasonable (< 64)", tiles.size() < 64);
        assertTrue("Should have multiple tiles for 4K", tiles.size() > 1);
    }
    
    /**
     * Test 8: Verify memory pressure response
     */
    @Test
    public void testMemoryPressureResponse() {
        Log.d(TAG, "=== Test 8: Memory Pressure Response ===");
        
        // Fill pool with bitmaps
        List<Bitmap> bitmaps = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Bitmap bitmap = bitmapFactory.createBitmap(1280, 720);
            bitmaps.add(bitmap);
        }
        
        // Release to pool
        for (Bitmap bitmap : bitmaps) {
            bitmapFactory.releaseBitmap(bitmap);
        }
        
        // Get metrics before trim
        BitmapPool.BitmapPoolMetrics metricsBefore = poolManager.getMetrics();
        long poolSizeBefore = metricsBefore.getCurrentPoolSize();
        
        Log.d(TAG, String.format("Pool size before trim: %d bitmaps", poolSizeBefore));
        
        // Simulate memory pressure
        poolManager.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE);
        
        // Get metrics after trim
        BitmapPool.BitmapPoolMetrics metricsAfter = poolManager.getMetrics();
        long poolSizeAfter = metricsAfter.getCurrentPoolSize();
        
        Log.d(TAG, String.format("Pool size after trim: %d bitmaps", poolSizeAfter));
        
        // Verify pool was trimmed
        assertTrue("Pool should be trimmed on memory pressure", 
                  poolSizeAfter < poolSizeBefore);
    }
    
    /**
     * Helper method to log test summary
     */
    private void logTestSummary() {
        Log.d(TAG, "=== Bitmap Pool Validation Summary ===");
        
        BitmapPool.BitmapPoolMetrics metrics = poolManager.getMetrics();
        if (metrics != null) {
            Log.d(TAG, String.format("Hit Rate: %.2f%%", metrics.getHitRate() * 100));
            Log.d(TAG, String.format("Total Hits: %d", metrics.getHits()));
            Log.d(TAG, String.format("Total Misses: %d", metrics.getMisses()));
            Log.d(TAG, String.format("Current Pool Size: %d", metrics.getCurrentPoolSize()));
            Log.d(TAG, String.format("Allocations: %d", metrics.getAllocations()));
            Log.d(TAG, String.format("Releases: %d", metrics.getReleases()));
            Log.d(TAG, String.format("Evictions: %d", metrics.getEvictions()));
        }
        
        poolManager.logStats();
    }
    
    @Test
    public void runAllValidationTests() {
        Log.d(TAG, "========================================");
        Log.d(TAG, "Story 1.3: Bitmap Memory Optimization");
        Log.d(TAG, "Validation Test Suite");
        Log.d(TAG, "========================================");
        
        // Run all tests and log summary
        try {
            testBitmapReuseRate();
            testOOMPrevention();
            testGCImpact();
            testMemoryPeak1080p();
            testAllocationRate();
            testConcurrentAccess();
            testLargeImageProcessing();
            testMemoryPressureResponse();
            
            Log.d(TAG, "========================================");
            Log.d(TAG, "ALL VALIDATION TESTS PASSED ✅");
            Log.d(TAG, "========================================");
            
            logTestSummary();
            
        } catch (Exception e) {
            Log.e(TAG, "Validation test failed", e);
            fail("Validation test failed: " + e.getMessage());
        }
    }
}