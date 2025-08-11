package com.example.sr_poc;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class PerformanceMonitor {
    
    private static final String TAG = "PerformanceMonitor";
    
    // Performance tracking
    private static final ConcurrentHashMap<String, List<Long>> initializationTimes = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> modeUsageCount = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> totalProcessingTime = new ConcurrentHashMap<>();
    private static long firstInteractionTime = 0;
    private static long applicationStartTime = System.currentTimeMillis();
    
    public static class InferenceStats {
        public long inferenceTime;
        public long memoryBefore;
        public long memoryAfter;
        public String accelerator;
        public int inputWidth;
        public int inputHeight;
        public int outputWidth;
        public int outputHeight;
        public boolean usedTileProcessing;
        
        @Override
        public String toString() {
            return String.format(
                "Inference Stats:\n" +
                "Time: %dms\n" +
                "Accelerator: %s\n" +
                "Input: %dx%d\n" +
                "Output: %dx%d\n" +
                "Memory Before: %dMB\n" +
                "Memory After: %dMB\n" +
                "Tile Processing: %s",
                inferenceTime, accelerator, inputWidth, inputHeight, 
                outputWidth, outputHeight, memoryBefore, memoryAfter,
                usedTileProcessing ? "Yes" : "No"
            );
        }
    }
    
    public static void logPerformanceStats(InferenceStats stats) {
        Log.d(TAG, "=== Performance Statistics ===");
        Log.d(TAG, stats.toString());
        
        // 計算效能指標
        long pixelsProcessed = (long) stats.inputWidth * stats.inputHeight;
        double pixelsPerSecond = pixelsProcessed * 1000.0 / stats.inferenceTime;
        double megapixelsPerSecond = pixelsPerSecond / 1_000_000.0;
        
        Log.d(TAG, String.format("Processing Speed: %.2f MP/s", megapixelsPerSecond));
        
        // GPU vs CPU 效能比較參考
        if (stats.accelerator.contains("GPU")) {
            Log.d(TAG, "GPU acceleration active - expect 5-10x speedup vs CPU");
        } else {
            Log.d(TAG, "CPU processing - consider GPU acceleration for better performance");
        }
        
        // 記憶體使用分析
        long memoryUsed = stats.memoryAfter - stats.memoryBefore;
        if (memoryUsed > 100) {
            Log.w(TAG, "High memory usage detected: " + memoryUsed + "MB");
        }
        
        Log.d(TAG, "=== End Performance Statistics ===");
    }
    
    public static InferenceStats createStats() {
        return new InferenceStats();
    }
    
    /**
     * Track initialization time for a specific mode.
     */
    public static void trackInitializationTime(String mode, long timeMs) {
        initializationTimes.computeIfAbsent(mode, k -> new ArrayList<>()).add(timeMs);
        Log.d(TAG, "Initialization time for " + mode + ": " + timeMs + "ms");
    }
    
    /**
     * Track first interaction time.
     */
    public static void trackFirstInteraction() {
        if (firstInteractionTime == 0) {
            firstInteractionTime = System.currentTimeMillis() - applicationStartTime;
            Log.d(TAG, "First interaction time: " + firstInteractionTime + "ms");
        }
    }
    
    /**
     * Track mode usage.
     */
    public static void trackModeUsage(String mode, long processingTimeMs) {
        modeUsageCount.computeIfAbsent(mode, k -> new AtomicLong(0)).incrementAndGet();
        totalProcessingTime.computeIfAbsent(mode, k -> new AtomicLong(0)).addAndGet(processingTimeMs);
    }
    
    /**
     * Get performance summary.
     */
    public static String getPerformanceSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("=== Performance Summary ===\n");
        
        // Initialization times
        summary.append("\nInitialization Times:\n");
        for (String mode : initializationTimes.keySet()) {
            List<Long> times = initializationTimes.get(mode);
            if (!times.isEmpty()) {
                long avg = times.stream().mapToLong(Long::longValue).sum() / times.size();
                summary.append(String.format("  %s: %dms (avg)\n", mode, avg));
            }
        }
        
        // First interaction
        if (firstInteractionTime > 0) {
            summary.append(String.format("\nTime to First Interaction: %dms\n", firstInteractionTime));
        }
        
        // Mode usage statistics
        summary.append("\nMode Usage Statistics:\n");
        for (String mode : modeUsageCount.keySet()) {
            long count = modeUsageCount.get(mode).get();
            long totalTime = totalProcessingTime.get(mode).get();
            if (count > 0) {
                long avgTime = totalTime / count;
                summary.append(String.format("  %s: %d uses, %dms avg processing\n", 
                    mode, count, avgTime));
            }
        }
        
        summary.append("\n=== End Performance Summary ===");
        return summary.toString();
    }
    
    /**
     * Reset all performance metrics.
     */
    public static void resetMetrics() {
        initializationTimes.clear();
        modeUsageCount.clear();
        totalProcessingTime.clear();
        firstInteractionTime = 0;
        applicationStartTime = System.currentTimeMillis();
        Log.d(TAG, "Performance metrics reset");
    }
}