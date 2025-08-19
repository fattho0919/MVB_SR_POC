package com.example.sr_poc.pool;

import android.graphics.Bitmap;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class BitmapPoolTest {
    
    private BitmapPool bitmapPool;
    
    @Mock
    private Bitmap mockBitmap;
    
    @Before
    public void setUp() {
        // Create pool with 10MB limit and max 3 bitmaps per size
        bitmapPool = new BitmapPool(10, 3);
    }
    
    @Test
    public void testAcquireCreatesNewBitmap() {
        try (MockedStatic<Bitmap> bitmapStatic = mockStatic(Bitmap.class)) {
            // Setup mock
            when(mockBitmap.getWidth()).thenReturn(100);
            when(mockBitmap.getHeight()).thenReturn(100);
            when(mockBitmap.getConfig()).thenReturn(Bitmap.Config.ARGB_8888);
            when(mockBitmap.isRecycled()).thenReturn(false);
            when(mockBitmap.isMutable()).thenReturn(true);
            
            bitmapStatic.when(() -> Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888))
                    .thenReturn(mockBitmap);
            
            // Acquire bitmap
            Bitmap result = bitmapPool.acquire(100, 100, Bitmap.Config.ARGB_8888);
            
            // Verify
            assertNotNull(result);
            assertEquals(mockBitmap, result);
            
            // Check metrics
            BitmapPool.BitmapPoolMetrics metrics = bitmapPool.getMetrics();
            assertEquals(1, metrics.getMisses());
            assertEquals(0, metrics.getHits());
            assertEquals(1, metrics.getAllocations());
        }
    }
    
    @Test
    public void testReleaseAndReuse() {
        try (MockedStatic<Bitmap> bitmapStatic = mockStatic(Bitmap.class)) {
            // Setup mock
            when(mockBitmap.getWidth()).thenReturn(100);
            when(mockBitmap.getHeight()).thenReturn(100);
            when(mockBitmap.getConfig()).thenReturn(Bitmap.Config.ARGB_8888);
            when(mockBitmap.isRecycled()).thenReturn(false);
            when(mockBitmap.isMutable()).thenReturn(true);
            
            bitmapStatic.when(() -> Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888))
                    .thenReturn(mockBitmap);
            
            // First acquire
            Bitmap first = bitmapPool.acquire(100, 100, Bitmap.Config.ARGB_8888);
            assertNotNull(first);
            
            // Release back to pool
            bitmapPool.release(first);
            
            // Second acquire should reuse
            Bitmap second = bitmapPool.acquire(100, 100, Bitmap.Config.ARGB_8888);
            
            // Verify reuse
            assertEquals(first, second);
            
            // Check metrics
            BitmapPool.BitmapPoolMetrics metrics = bitmapPool.getMetrics();
            assertEquals(1, metrics.getHits());
            assertEquals(1, metrics.getMisses());
            assertEquals(1, metrics.getReleases());
        }
    }
    
    @Test
    public void testPoolSizeLimit() {
        try (MockedStatic<Bitmap> bitmapStatic = mockStatic(Bitmap.class)) {
            List<Bitmap> mockBitmaps = new ArrayList<>();
            
            // Create 4 mock bitmaps
            for (int i = 0; i < 4; i++) {
                Bitmap mock = mock(Bitmap.class);
                when(mock.getWidth()).thenReturn(100);
                when(mock.getHeight()).thenReturn(100);
                when(mock.getConfig()).thenReturn(Bitmap.Config.ARGB_8888);
                when(mock.isRecycled()).thenReturn(false);
                when(mock.isMutable()).thenReturn(true);
                mockBitmaps.add(mock);
            }
            
            // Setup static mock to return different instances
            AtomicInteger callCount = new AtomicInteger(0);
            bitmapStatic.when(() -> Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888))
                    .thenAnswer(invocation -> mockBitmaps.get(callCount.getAndIncrement()));
            
            // Acquire and release 4 bitmaps
            for (int i = 0; i < 4; i++) {
                Bitmap bitmap = bitmapPool.acquire(100, 100, Bitmap.Config.ARGB_8888);
                bitmapPool.release(bitmap);
            }
            
            // Pool should only keep 3 (maxBitmapsPerSize)
            // The 4th should be recycled
            verify(mockBitmaps.get(3)).recycle();
            
            // Check metrics
            BitmapPool.BitmapPoolMetrics metrics = bitmapPool.getMetrics();
            assertEquals(1, metrics.getEvictions());
        }
    }
    
    @Test
    public void testConcurrentAccess() throws InterruptedException {
        try (MockedStatic<Bitmap> bitmapStatic = mockStatic(Bitmap.class)) {
            final int numThreads = 10;
            final int operationsPerThread = 100;
            final CountDownLatch latch = new CountDownLatch(numThreads);
            final ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            
            // Setup mock to always return a new bitmap
            bitmapStatic.when(() -> Bitmap.createBitmap(anyInt(), anyInt(), any(Bitmap.Config.class)))
                    .thenAnswer(invocation -> {
                        Bitmap mock = mock(Bitmap.class);
                        when(mock.getWidth()).thenReturn(invocation.getArgument(0));
                        when(mock.getHeight()).thenReturn(invocation.getArgument(1));
                        when(mock.getConfig()).thenReturn(invocation.getArgument(2));
                        when(mock.isRecycled()).thenReturn(false);
                        when(mock.isMutable()).thenReturn(true);
                        return mock;
                    });
            
            // Run concurrent operations
            for (int i = 0; i < numThreads; i++) {
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < operationsPerThread; j++) {
                            Bitmap bitmap = bitmapPool.acquire(100, 100, Bitmap.Config.ARGB_8888);
                            assertNotNull(bitmap);
                            
                            // Simulate some work
                            Thread.sleep(1);
                            
                            bitmapPool.release(bitmap);
                        }
                    } catch (Exception e) {
                        fail("Thread failed: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            // Wait for all threads to complete
            assertTrue("Timeout waiting for threads", latch.await(30, TimeUnit.SECONDS));
            executor.shutdown();
            
            // Verify no errors and check metrics
            BitmapPool.BitmapPoolMetrics metrics = bitmapPool.getMetrics();
            assertTrue(metrics.getHitRate() > 0); // Should have some hits due to reuse
            assertTrue(metrics.getReleases() > 0);
        }
    }
    
    @Test
    public void testInvalidDimensions() {
        assertThrows(IllegalArgumentException.class, () -> {
            bitmapPool.acquire(0, 100, Bitmap.Config.ARGB_8888);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            bitmapPool.acquire(100, -1, Bitmap.Config.ARGB_8888);
        });
    }
    
    @Test
    public void testReleaseNullOrRecycled() {
        // Should not throw
        bitmapPool.release(null);
        
        // Release recycled bitmap
        when(mockBitmap.isRecycled()).thenReturn(true);
        bitmapPool.release(mockBitmap);
        
        // Check no releases recorded
        assertEquals(0, bitmapPool.getMetrics().getReleases());
    }
    
    @Test
    public void testReleaseImmutableBitmap() {
        when(mockBitmap.isRecycled()).thenReturn(false);
        when(mockBitmap.isMutable()).thenReturn(false);
        
        bitmapPool.release(mockBitmap);
        
        // Should not be added to pool
        assertEquals(0, bitmapPool.getMetrics().getReleases());
    }
    
    @Test
    public void testClearPool() {
        try (MockedStatic<Bitmap> bitmapStatic = mockStatic(Bitmap.class)) {
            // Create mock bitmaps
            List<Bitmap> mocks = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Bitmap mock = mock(Bitmap.class);
                when(mock.getWidth()).thenReturn(100);
                when(mock.getHeight()).thenReturn(100);
                when(mock.getConfig()).thenReturn(Bitmap.Config.ARGB_8888);
                when(mock.isRecycled()).thenReturn(false);
                when(mock.isMutable()).thenReturn(true);
                mocks.add(mock);
            }
            
            AtomicInteger index = new AtomicInteger(0);
            bitmapStatic.when(() -> Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888))
                    .thenAnswer(inv -> mocks.get(index.getAndIncrement()));
            
            // Acquire and release bitmaps
            for (int i = 0; i < 3; i++) {
                Bitmap bitmap = bitmapPool.acquire(100, 100, Bitmap.Config.ARGB_8888);
                bitmapPool.release(bitmap);
            }
            
            // Clear pool
            bitmapPool.clear();
            
            // All bitmaps should be recycled
            for (Bitmap mock : mocks) {
                verify(mock).recycle();
            }
            
            // Metrics should be reset
            BitmapPool.BitmapPoolMetrics metrics = bitmapPool.getMetrics();
            assertEquals(0, metrics.getHits());
            assertEquals(0, metrics.getMisses());
            assertEquals(0, metrics.getCurrentPoolSize());
        }
    }
    
    @Test
    public void testTrimPool() {
        try (MockedStatic<Bitmap> bitmapStatic = mockStatic(Bitmap.class)) {
            List<Bitmap> mocks = new ArrayList<>();
            
            // Create 6 mock bitmaps (2 different sizes)
            for (int i = 0; i < 6; i++) {
                Bitmap mock = mock(Bitmap.class);
                int size = (i < 3) ? 100 : 200;
                when(mock.getWidth()).thenReturn(size);
                when(mock.getHeight()).thenReturn(size);
                when(mock.getConfig()).thenReturn(Bitmap.Config.ARGB_8888);
                when(mock.isRecycled()).thenReturn(false);
                when(mock.isMutable()).thenReturn(true);
                mocks.add(mock);
            }
            
            AtomicInteger index = new AtomicInteger(0);
            bitmapStatic.when(() -> Bitmap.createBitmap(anyInt(), anyInt(), any(Bitmap.Config.class)))
                    .thenAnswer(inv -> mocks.get(index.getAndIncrement()));
            
            // Fill pool with bitmaps
            for (int i = 0; i < 3; i++) {
                Bitmap b1 = bitmapPool.acquire(100, 100, Bitmap.Config.ARGB_8888);
                bitmapPool.release(b1);
                
                Bitmap b2 = bitmapPool.acquire(200, 200, Bitmap.Config.ARGB_8888);
                bitmapPool.release(b2);
            }
            
            // Trim 50%
            bitmapPool.trim(0.5f);
            
            // Some bitmaps should be recycled
            int recycledCount = 0;
            for (Bitmap mock : mocks) {
                try {
                    verify(mock).recycle();
                    recycledCount++;
                } catch (AssertionError e) {
                    // Not recycled
                }
            }
            
            assertTrue("Should have recycled some bitmaps", recycledCount > 0);
            assertTrue("Should not recycle all bitmaps", recycledCount < 6);
        }
    }
    
    @Test
    public void testDifferentBitmapConfigs() {
        try (MockedStatic<Bitmap> bitmapStatic = mockStatic(Bitmap.class)) {
            Bitmap.Config[] configs = {
                    Bitmap.Config.ARGB_8888,
                    Bitmap.Config.RGB_565,
                    Bitmap.Config.ALPHA_8
            };
            
            for (Bitmap.Config config : configs) {
                Bitmap mock = mock(Bitmap.class);
                when(mock.getWidth()).thenReturn(100);
                when(mock.getHeight()).thenReturn(100);
                when(mock.getConfig()).thenReturn(config);
                when(mock.isRecycled()).thenReturn(false);
                when(mock.isMutable()).thenReturn(true);
                
                bitmapStatic.when(() -> Bitmap.createBitmap(100, 100, config))
                        .thenReturn(mock);
                
                // Acquire and release
                Bitmap bitmap = bitmapPool.acquire(100, 100, config);
                assertNotNull(bitmap);
                bitmapPool.release(bitmap);
                
                // Should be able to reuse
                Bitmap reused = bitmapPool.acquire(100, 100, config);
                assertEquals(bitmap, reused);
            }
        }
    }
}