package com.example.sr_poc;

import android.graphics.Bitmap;
import android.util.Log;

import com.example.sr_poc.utils.MemoryUtils;

/**
 * Intelligent processing strategy selector for optimal performance
 * Selects between NPU Batch, CPU Parallel Tiling, or CPU Sequential based on:
 * - Available memory
 * - Model batch capability  
 * - Tile count
 * - Hardware availability
 */
public class ProcessingStrategySelector {
    
    private static final String TAG = "ProcessingStrategySelector";
    
    public enum ProcessingStrategy {
        NPU_BATCH,          // Best: Single NPU batch inference (~200ms for 12 tiles)
        CPU_PARALLEL_TILING, // Good: CPU parallel processing (~525ms for 12 tiles)  
        CPU_SEQUENTIAL,     // Fallback: CPU sequential processing (~2100ms for 12 tiles)
        DIRECT_PROCESSING   // No tiling needed
    }
    
    public static class StrategyDecision {
        public final ProcessingStrategy strategy;
        public final String reason;
        public final boolean canUseTiling;
        public final int estimatedTimeMs;
        
        public StrategyDecision(ProcessingStrategy strategy, String reason, 
                               boolean canUseTiling, int estimatedTimeMs) {
            this.strategy = strategy;
            this.reason = reason;
            this.canUseTiling = canUseTiling;
            this.estimatedTimeMs = estimatedTimeMs;
        }
        
        @Override
        public String toString() {
            return String.format("%s (%s) - Est: %dms", strategy, reason, estimatedTimeMs);
        }
    }
    
    private final ThreadSafeSRProcessor srProcessor;
    private final ConfigManager configManager;
    
    public ProcessingStrategySelector(ThreadSafeSRProcessor srProcessor, ConfigManager configManager) {
        this.srProcessor = srProcessor;
        this.configManager = configManager;
    }
    
    /**
     * Select optimal processing strategy based on current conditions
     */
    public StrategyDecision selectStrategy(Bitmap inputBitmap, 
                                         ThreadSafeSRProcessor.ProcessingMode requestedMode,
                                         boolean userRequestedTiling) {
        
        if (inputBitmap == null) {
            return new StrategyDecision(ProcessingStrategy.DIRECT_PROCESSING, 
                                      "Invalid input", false, 0);
        }
        
        // Calculate tile requirements
        int imageWidth = inputBitmap.getWidth();
        int imageHeight = inputBitmap.getHeight();
        int tileCount = calculateTileCount(imageWidth, imageHeight);
        
        // Check if tiling is actually needed
        boolean needsTiling = shouldUseTiling(imageWidth, imageHeight);
        if (!needsTiling && !userRequestedTiling) {
            int directTime = estimateDirectProcessingTime(requestedMode);
            return new StrategyDecision(ProcessingStrategy.DIRECT_PROCESSING,
                                      "Image small enough for direct processing", 
                                      false, directTime);
        }
        
        // Get memory status
        MemoryUtils.MemoryInfo memInfo = MemoryUtils.getCurrentMemoryInfo();
        String memoryCategory = MemoryUtils.getMemoryCategory();
        int batchMemoryRequired = configManager.getBatchProcessingRequiredMb();
        
        Log.d(TAG, "Strategy selection - Image: " + imageWidth + "x" + imageHeight + 
                   ", Tiles: " + tileCount + ", Memory: " + memoryCategory + 
                   " (" + memInfo.availableMemoryMB + "MB)");
        
        // Strategy selection logic
        if (canUseNpuBatch(tileCount, memInfo.availableMemoryMB, batchMemoryRequired, requestedMode)) {
            String reason = determineNpuBatchReason(requestedMode, memInfo.availableMemoryMB, batchMemoryRequired);
            return new StrategyDecision(ProcessingStrategy.NPU_BATCH, reason, 
                                      true, estimateNpuBatchTime(tileCount));
        } else if (canUseCpuParallel(memInfo.availableMemoryMB)) {
            return new StrategyDecision(ProcessingStrategy.CPU_PARALLEL_TILING,
                                      getFallbackReason(memInfo.availableMemoryMB, batchMemoryRequired),
                                      true, estimateCpuParallelTime(tileCount));
        } else {
            return new StrategyDecision(ProcessingStrategy.CPU_SEQUENTIAL,
                                      "Low memory - using sequential processing",
                                      true, estimateCpuSequentialTime(tileCount));
        }
    }
    
    private boolean canUseNpuBatch(int tileCount, long availableMemoryMB, 
                                   int requiredMemoryMB, ThreadSafeSRProcessor.ProcessingMode requestedMode) {
        boolean hasModelSupport = srProcessor.isModelBatchCapable() && 
                                 tileCount <= srProcessor.getModelBatchSize();
        boolean hasNpuSupport = configManager.isEnableNpu();
        boolean isNpuRequested = requestedMode == ThreadSafeSRProcessor.ProcessingMode.NPU;
        
        // 如果用戶明確選擇NPU，跳過記憶體檢查，直接嘗試使用NPU batch
        if (isNpuRequested && hasModelSupport && hasNpuSupport) {
            Log.d(TAG, "NPU explicitly requested - forcing NPU batch processing regardless of memory");
            return true;  // 強制使用NPU batch，不管記憶體
        }
        
        // 自動選擇時才考慮記憶體限制
        return hasModelSupport && 
               availableMemoryMB >= requiredMemoryMB && 
               (requestedMode == null) &&
               hasNpuSupport;
    }
    
    private boolean canUseCpuParallel(long availableMemoryMB) {
        return availableMemoryMB >= 200; // Reasonable threshold for CPU parallel processing
    }
    
    private String determineNpuBatchReason(ThreadSafeSRProcessor.ProcessingMode requestedMode, 
                                         long availableMemoryMB, int requiredMemoryMB) {
        boolean isNpuRequested = requestedMode == ThreadSafeSRProcessor.ProcessingMode.NPU;
        boolean hasEnoughMemory = availableMemoryMB >= requiredMemoryMB;
        
        if (isNpuRequested && !hasEnoughMemory) {
            return String.format("NPU batch processing (forced - low memory: %dMB < %dMB)", 
                               availableMemoryMB, requiredMemoryMB);
        } else if (isNpuRequested && hasEnoughMemory) {
            return "NPU batch processing (user requested)";
        } else if (hasEnoughMemory) {
            return "NPU batch processing (optimal)";
        } else {
            return "NPU batch processing";
        }
    }
    
    private String getFallbackReason(long availableMemoryMB, int requiredMemoryMB) {
        if (availableMemoryMB < requiredMemoryMB) {
            return String.format("Insufficient memory (%dMB < %dMB required)", 
                                availableMemoryMB, requiredMemoryMB);
        } else if (!srProcessor.isModelBatchCapable()) {
            return "Model doesn't support batch processing";
        } else if (!configManager.isEnableNpu()) {
            return "NPU disabled in configuration";
        } else {
            return "Using CPU parallel processing";
        }
    }
    
    private int calculateTileCount(int imageWidth, int imageHeight) {
        int tileWidth = srProcessor.getModelInputWidth();
        int tileHeight = srProcessor.getModelInputHeight();
        int overlapPixels = configManager.getOverlapPixels();
        
        int effectiveStepX = tileWidth - overlapPixels;
        int effectiveStepY = tileHeight - overlapPixels;
        
        int tilesX = (int) Math.ceil((double) imageWidth / effectiveStepX);
        int tilesY = (int) Math.ceil((double) imageHeight / effectiveStepY);
        
        return tilesX * tilesY;
    }
    
    private boolean shouldUseTiling(int imageWidth, int imageHeight) {
        int maxDirectSize = configManager.getMaxInputSizeWithoutTiling();
        return imageWidth > maxDirectSize || imageHeight > maxDirectSize;
    }
    
    // Performance estimation methods
    private int estimateDirectProcessingTime(ThreadSafeSRProcessor.ProcessingMode mode) {
        switch (mode != null ? mode : ThreadSafeSRProcessor.ProcessingMode.CPU) {
            case NPU: return 150;
            case GPU: return 200;
            case CPU:
            default: return 300;
        }
    }
    
    private int estimateNpuBatchTime(int tileCount) {
        // NPU batch processing is very efficient - single inference for all tiles
        return 200 + (tileCount * 2); // Base time + small overhead per tile
    }
    
    private int estimateCpuParallelTime(int tileCount) {
        // 4-thread parallel processing
        int threadsUsed = Math.min(4, tileCount);
        int rounds = (int) Math.ceil((double) tileCount / threadsUsed);
        return rounds * 175; // ~175ms per round
    }
    
    private int estimateCpuSequentialTime(int tileCount) {
        return tileCount * 175; // Sequential processing
    }
    
    /**
     * Get user-friendly description of the strategy
     */
    public static String getStrategyDescription(ProcessingStrategy strategy) {
        switch (strategy) {
            case NPU_BATCH:
                return "NPU Batch (Hardware Parallel)";
            case CPU_PARALLEL_TILING:
                return "CPU Parallel Tiling";
            case CPU_SEQUENTIAL:
                return "CPU Sequential";
            case DIRECT_PROCESSING:
            default:
                return "Direct Processing";
        }
    }
}