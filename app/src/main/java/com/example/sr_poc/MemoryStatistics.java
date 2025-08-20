package com.example.sr_poc;

/**
 * Memory pool statistics data class
 */
public class MemoryStatistics {
    public long totalAllocated;
    public long totalDeallocated;
    public long currentUsage;
    public long peakUsage;
    public long allocationCount;
    public long deallocationCount;
    public long cacheHits;
    public long cacheMisses;
    public double hitRate;
    
    public MemoryStatistics() {
        // Default constructor for JNI
    }
    
    /**
     * Get cache efficiency percentage
     */
    public double getCacheEfficiency() {
        return hitRate * 100.0;
    }
    
    /**
     * Check if memory pool is being effectively used
     */
    public boolean isEffective() {
        return hitRate > 0.7; // 70% hit rate threshold
    }
    
    /**
     * Get fragmentation estimate
     */
    public double getFragmentation() {
        if (totalAllocated == 0) return 0;
        return 1.0 - (double)currentUsage / peakUsage;
    }
    
    @Override
    public String toString() {
        return String.format(
            "MemoryStats[current=%s, peak=%s, allocations=%d, hitRate=%.2f%%, fragmentation=%.2f%%]",
            formatBytes(currentUsage),
            formatBytes(peakUsage),
            allocationCount,
            hitRate * 100,
            getFragmentation() * 100
        );
    }
    
    /**
     * Format bytes to human readable string
     */
    public static String formatBytes(long bytes) {
        if (bytes < 0) return "N/A";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    /**
     * Get detailed statistics report
     */
    public String getDetailedReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Memory Pool Statistics ===\n");
        sb.append(String.format("Current Usage: %s\n", formatBytes(currentUsage)));
        sb.append(String.format("Peak Usage: %s\n", formatBytes(peakUsage)));
        sb.append(String.format("Total Allocated: %s\n", formatBytes(totalAllocated)));
        sb.append(String.format("Total Deallocated: %s\n", formatBytes(totalDeallocated)));
        sb.append(String.format("Allocations: %d\n", allocationCount));
        sb.append(String.format("Deallocations: %d\n", deallocationCount));
        sb.append(String.format("Cache Hits: %d\n", cacheHits));
        sb.append(String.format("Cache Misses: %d\n", cacheMisses));
        sb.append(String.format("Hit Rate: %.2f%%\n", hitRate * 100));
        sb.append(String.format("Fragmentation: %.2f%%\n", getFragmentation() * 100));
        sb.append(String.format("Effectiveness: %s\n", isEffective() ? "Good" : "Poor"));
        return sb.toString();
    }
}