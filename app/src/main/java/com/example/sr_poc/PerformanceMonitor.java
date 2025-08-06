package com.example.sr_poc;

import android.util.Log;

public class PerformanceMonitor {
    
    private static final String TAG = "PerformanceMonitor";
    
    public static class InferenceStats {
        public long inferenceTime;
        public long estimatedTime; // Strategy selector's estimation
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
            String accuracyInfo = "";
            if (estimatedTime > 0) {
                long difference = Math.abs(inferenceTime - estimatedTime);
                double accuracy = 100.0 * (1.0 - (double)difference / estimatedTime);
                accuracyInfo = String.format("\nEstimated: %dms (%.1f%% accuracy)", estimatedTime, accuracy);
            }
            
            return String.format(
                "Inference Stats:\n" +
                "Time: %dms%s\n" +
                "Accelerator: %s\n" +
                "Input: %dx%d\n" +
                "Output: %dx%d\n" +
                "Memory Before: %dMB\n" +
                "Memory After: %dMB\n" +
                "Tile Processing: %s",
                inferenceTime, accuracyInfo, accelerator, inputWidth, inputHeight, 
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
        
        // Strategy effectiveness analysis
        if (stats.estimatedTime > 0) {
            long difference = Math.abs(stats.inferenceTime - stats.estimatedTime);
            double accuracy = 100.0 * (1.0 - (double)difference / stats.estimatedTime);
            
            Log.d(TAG, "=== Strategy Analysis ===");
            Log.d(TAG, String.format("Estimation Accuracy: %.1f%%", accuracy));
            if (accuracy < 80.0) {
                Log.w(TAG, "Strategy estimation was significantly off - consider tuning");
            } else if (accuracy > 95.0) {
                Log.d(TAG, "Excellent strategy estimation!");
            }
        }
        
        // Memory efficiency analysis
        long memoryUsed = stats.memoryAfter - stats.memoryBefore;
        Log.d(TAG, String.format("Memory Delta: %+dMB", memoryUsed));
        if (memoryUsed > 100) {
            Log.w(TAG, "High memory usage detected");
        }
        
        Log.d(TAG, "=== End Performance Statistics ===");
    }
    
    public static InferenceStats createStats() {
        return new InferenceStats();
    }
}