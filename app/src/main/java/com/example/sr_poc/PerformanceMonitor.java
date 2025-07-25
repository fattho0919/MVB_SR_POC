package com.example.sr_poc;

import android.util.Log;

public class PerformanceMonitor {
    
    private static final String TAG = "PerformanceMonitor";
    
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
}