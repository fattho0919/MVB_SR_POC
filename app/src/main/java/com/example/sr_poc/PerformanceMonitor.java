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
        
        // Detailed timing breakdown
        public long bufferAllocationTime;
        public long inputConversionTime;
        public long pureInferenceTime;
        public long outputConversionTime;
        public long bitmapCreationTime;
        
        // CPU/NPU specific metrics
        public int threadCount;
        public boolean usingNNAPI;
        public boolean pureCpuMode;
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== DETAILED INFERENCE PERFORMANCE ===\n");
            sb.append(String.format("Total Time: %dms\n", inferenceTime));
            sb.append(String.format("Accelerator: %s\n", accelerator));
            sb.append(String.format("Input: %dx%d → Output: %dx%d\n", inputWidth, inputHeight, outputWidth, outputHeight));
            
            // Detailed timing breakdown
            if (bufferAllocationTime > 0 || inputConversionTime > 0 || pureInferenceTime > 0 || outputConversionTime > 0) {
                sb.append("\n--- TIMING BREAKDOWN ---\n");
                if (bufferAllocationTime > 0) sb.append(String.format("Buffer Allocation: %dms\n", bufferAllocationTime));
                if (inputConversionTime > 0) sb.append(String.format("Input Conversion: %dms\n", inputConversionTime));
                if (pureInferenceTime > 0) sb.append(String.format("Pure Inference: %dms\n", pureInferenceTime));
                if (outputConversionTime > 0) sb.append(String.format("Output Conversion: %dms\n", outputConversionTime));
                if (bitmapCreationTime > 0) sb.append(String.format("Bitmap Creation: %dms\n", bitmapCreationTime));
                
                long accountedTime = bufferAllocationTime + inputConversionTime + pureInferenceTime + outputConversionTime + bitmapCreationTime;
                long unaccountedTime = inferenceTime - accountedTime;
                if (unaccountedTime > 0) sb.append(String.format("Other Overhead: %dms\n", unaccountedTime));
            }
            
            // CPU/NPU specific info
            sb.append("\n--- EXECUTION DETAILS ---\n");
            if (threadCount > 0) sb.append(String.format("Thread Count: %d\n", threadCount));
            sb.append(String.format("Using NNAPI: %s\n", usingNNAPI ? "Yes" : "No"));
            if (pureCpuMode) sb.append("Pure CPU Mode: Enabled\n");
            
            sb.append(String.format("Memory: %dMB → %dMB (%+dMB)\n", memoryBefore, memoryAfter, memoryAfter - memoryBefore));
            sb.append(String.format("Tile Processing: %s", usedTileProcessing ? "Yes" : "No"));
            
            return sb.toString();
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