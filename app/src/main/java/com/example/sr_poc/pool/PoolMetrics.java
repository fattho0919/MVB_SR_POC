package com.example.sr_poc.pool;

import android.util.Log;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe metrics collection for DirectBufferPool performance monitoring.
 * 
 * Tracks hits, misses, allocations, and calculates performance statistics
 * for buffer pool optimization and debugging.
 */
public class PoolMetrics {
    
    private static final String TAG = "PoolMetrics";
    
    // Thread-safe counters
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong allocations = new AtomicLong(0);
    
    // Timing statistics (optional)
    private volatile long lastLogTime = System.currentTimeMillis();
    private static final long LOG_INTERVAL_MS = 30000; // 30 seconds
    
    /**
     * Records a cache hit (buffer found in pool).
     */
    public void recordHit() {
        hits.incrementAndGet();
    }
    
    /**
     * Records a cache miss (buffer not found in pool).
     */
    public void recordMiss() {
        misses.incrementAndGet();
    }
    
    /**
     * Records a new buffer allocation.
     */
    public void recordAllocation() {
        allocations.incrementAndGet();
    }
    
    /**
     * Calculates the current hit rate as a percentage.
     * 
     * @return Hit rate between 0.0 and 1.0, or 0.0 if no requests yet
     */
    public double getHitRate() {
        long totalHits = hits.get();
        long totalMisses = misses.get();
        long totalRequests = totalHits + totalMisses;
        
        if (totalRequests == 0) {
            return 0.0;
        }
        
        return (double) totalHits / totalRequests;
    }
    
    /**
     * @return Total number of cache hits
     */
    public long getHits() {
        return hits.get();
    }
    
    /**
     * @return Total number of cache misses
     */
    public long getMisses() {
        return misses.get();
    }
    
    /**
     * @return Total number of buffer allocations
     */
    public long getAllocations() {
        return allocations.get();
    }
    
    /**
     * @return Total number of buffer requests (hits + misses)
     */
    public long getTotalRequests() {
        return hits.get() + misses.get();
    }
    
    /**
     * Resets all metrics to zero.
     */
    public void reset() {
        hits.set(0);
        misses.set(0);
        allocations.set(0);
        lastLogTime = System.currentTimeMillis();
        Log.d(TAG, "Pool metrics reset");
    }
    
    /**
     * Logs current metrics if enough time has passed since last log.
     * This prevents log spam while providing regular performance updates.
     */
    public void maybeLogStats() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastLogTime > LOG_INTERVAL_MS) {
            logStats();
            lastLogTime = currentTime;
        }
    }
    
    /**
     * Immediately logs current performance statistics.
     */
    public void logStats() {
        long totalRequests = getTotalRequests();
        long currentHits = getHits();
        long currentMisses = getMisses();
        long currentAllocations = getAllocations();
        double hitRate = getHitRate();
        
        if (totalRequests > 0) {
            Log.d(TAG, String.format("Pool Metrics - Requests: %d, Hits: %d, Misses: %d, " +
                    "Hit Rate: %.1f%%, Allocations: %d",
                    totalRequests, currentHits, currentMisses, hitRate * 100, currentAllocations));
        } else {
            Log.d(TAG, "Pool Metrics - No requests yet");
        }
    }
    
    /**
     * Returns a formatted string with current metrics for display/debugging.
     * 
     * @return Human-readable metrics summary
     */
    public String getSummary() {
        long totalRequests = getTotalRequests();
        if (totalRequests == 0) {
            return "No pool activity yet";
        }
        
        return String.format("Requests: %d, Hit Rate: %.1f%%, Allocations: %d",
                totalRequests, getHitRate() * 100, getAllocations());
    }
    
    /**
     * Checks if the hit rate is below a threshold, indicating poor pool performance.
     * 
     * @param threshold Hit rate threshold (0.0 to 1.0)
     * @return true if hit rate is below threshold and we have enough samples
     */
    public boolean isHitRateBelowThreshold(double threshold) {
        return getTotalRequests() >= 10 && getHitRate() < threshold;
    }
    
    /**
     * Checks if we're allocating too frequently, indicating pool size issues.
     * 
     * @param maxAllocationRate Maximum acceptable allocation rate (allocations per request)
     * @return true if allocation rate is too high
     */
    public boolean isAllocationRateTooHigh(double maxAllocationRate) {
        long totalRequests = getTotalRequests();
        if (totalRequests < 10) {
            return false; // Not enough data
        }
        
        double currentRate = (double) getAllocations() / totalRequests;
        return currentRate > maxAllocationRate;
    }
}