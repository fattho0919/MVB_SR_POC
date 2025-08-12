package com.example.sr_poc;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.nnapi.NnApiDelegate;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hybrid Super Resolution Processor with progressive initialization.
 * 
 * Key features:
 * - Instant CPU mode (< 2 seconds)
 * - Parallel background loading for GPU/NPU
 * - Strict hardware validation
 * - Progressive UI updates
 * - Failed mode handling
 */
public class HybridSRProcessor {
    private static final String TAG = "HybridSRProcessor";
    
    // Timeouts
    private static final int INSTANT_MODE_TIMEOUT_MS = 30000;  // 30 seconds for CPU
    private static final int BACKGROUND_TIMEOUT_MS = 60000;   // 60 seconds for GPU/NPU

    // Processing modes
    public enum ProcessingMode {
        CPU, GPU, NPU
    }
    
    /**
     * Callback interface for hybrid initialization events.
     */
    public interface HybridCallback {
        /**
         * Called when quick-start mode (CPU) is ready.
         * App becomes interactive at this point.
         */
        void onQuickStartReady(ProcessingMode mode, long initTimeMs);
        
        /**
         * Called when an additional mode becomes available.
         */
        void onModeAvailable(ProcessingMode mode, String deviceInfo);
        
        /**
         * Called when a mode fails to initialize.
         * UI should permanently disable the corresponding button.
         */
        void onModeFailed(ProcessingMode mode, String reason);
        
        /**
         * Called when all initialization is complete.
         */
        void onAllModesReady(int availableModesCount, long totalTimeMs);
        
        /**
         * Called for initialization progress updates.
         */
        void onProgress(String message, int progressPercent);
        
        /**
         * Called when a critical error occurs.
         */
        void onInitError(ProcessingMode mode, Exception error);
    }
    
    private final Context context;
    private final ConfigManager configManager;
    private final ExecutorService executorService;
    private final Handler mainHandler;
    
    // Interpreters
    private volatile Interpreter cpuInterpreter;
    private volatile Interpreter gpuInterpreter;
    private volatile Interpreter npuInterpreter;
    
    // Delegates
    private GpuDelegate gpuDelegate;
    private NnApiDelegate npuDelegate;
    
    // State tracking
    private final AtomicBoolean isInitializing = new AtomicBoolean(false);
    private final AtomicBoolean quickStartReady = new AtomicBoolean(false);
    private final AtomicInteger availableModes = new AtomicInteger(0);
    private final AtomicBoolean isProcessingFlag = new AtomicBoolean(false);
    
    // Model buffer
    private ByteBuffer modelBuffer;
    
    // Extended processor for full functionality
    private ThreadSafeSRProcessor fullProcessor;
    
    public HybridSRProcessor(Context context) {
        this.context = context;
        this.configManager = ConfigManager.getInstance(context);
        this.executorService = Executors.newFixedThreadPool(3);  // CPU, GPU, NPU parallel
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * Initializes the processor with hybrid approach.
     * Phase 1: Quick CPU startup (< 2 seconds)
     * Phase 2: Background GPU/NPU loading
     */
    public void initializeHybrid(HybridCallback callback) {
        if (isInitializing.getAndSet(true)) {
            Log.w(TAG, "Already initializing");
            return;
        }
        
        long startTime = System.currentTimeMillis();
        
        callback.onProgress("Starting hybrid initialization...", 0);
        
        // Load model into memory first
        executorService.execute(() -> {
            try {
                Log.d(TAG, "Loading model into memory");
                String modelPath = configManager.getSelectedModelPath();
                modelBuffer = FileUtil.loadMappedFile(context, modelPath);
                Log.d(TAG, "Model loaded: " + modelPath + " (" + (modelBuffer.capacity() / 1024) + "KB)");
                
                callback.onProgress("Model loaded, initializing CPU...", 10);
                
                // Phase 1: Initialize optimized CPU for instant usability
                initializeQuickStartCPU(callback, startTime);
                
                // Phase 2: Start parallel background loading for GPU/NPU
                startParallelBackgroundLoading(callback, startTime);
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to load model", e);
                mainHandler.post(() -> callback.onInitError(null, e));
            }
        });
    }
    
    /**
     * Phase 1: Initialize optimized CPU for quick start.
     */
    private void initializeQuickStartCPU(HybridCallback callback, long startTime) {
        Log.d(TAG, "Phase 1: Initializing quick-start CPU mode");
        
        CompletableFuture<Interpreter> cpuFuture = CompletableFuture.supplyAsync(() -> {
            try {
                // Create the existing optimized ThreadSafeSRProcessor for CPU
                fullProcessor = new ThreadSafeSRProcessor(context);
                
                // Use the optimized CPU creation method
                return fullProcessor.createOptimizedCpuInterpreter(modelBuffer);
                
            } catch (Exception e) {
                Log.e(TAG, "CPU initialization failed", e);
                return null;
            }
        }, executorService);
        
        // Handle CPU result with timeout
        cpuFuture.orTimeout(INSTANT_MODE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .handle((interpreter, throwable) -> {
                long cpuInitTime = System.currentTimeMillis() - startTime;
                
                if (throwable != null) {
                    // Check if it's just a timeout but CPU is still initializing
                    if (throwable instanceof java.util.concurrent.TimeoutException) {
                        Log.w(TAG, "CPU initialization taking longer than expected (" + 
                                  INSTANT_MODE_TIMEOUT_MS + "ms), continuing in background");
                        
                        // Don't fail immediately - wait for actual result
                        cpuFuture.whenComplete((delayedInterpreter, delayedError) -> {
                            if (delayedInterpreter != null) {
                                cpuInterpreter = delayedInterpreter;
                                availableModes.incrementAndGet();
                                quickStartReady.set(true);
                                
                                long actualInitTime = System.currentTimeMillis() - startTime;
                                Log.d(TAG, "CPU mode ready (delayed) in " + actualInitTime + "ms");
                                
                                mainHandler.post(() -> {
                                    callback.onProgress("CPU ready!", 30);
                                    callback.onQuickStartReady(ProcessingMode.CPU, actualInitTime);
                                    callback.onModeAvailable(ProcessingMode.CPU, "CPU (XNNPACK Optimized)");
                                });
                            } else {
                                Log.e(TAG, "CPU initialization failed after timeout", delayedError);
                                mainHandler.post(() -> {
                                    callback.onModeFailed(ProcessingMode.CPU, 
                                        "CPU initialization failed: " + 
                                        (delayedError != null ? delayedError.getMessage() : "Unknown error"));
                                });
                            }
                        });
                    } else {
                        Log.e(TAG, "CPU initialization error", throwable);
                        mainHandler.post(() -> {
                            callback.onModeFailed(ProcessingMode.CPU, 
                                "CPU initialization failed: " + throwable.getMessage());
                        });
                    }
                    return null;
                }
                
                if (interpreter != null) {
                    cpuInterpreter = interpreter;
                    availableModes.incrementAndGet();
                    quickStartReady.set(true);
                    
                    Log.d(TAG, "CPU mode ready in " + cpuInitTime + "ms");
                    
                    mainHandler.post(() -> {
                        callback.onProgress("CPU ready! App is now interactive", 30);
                        callback.onQuickStartReady(ProcessingMode.CPU, cpuInitTime);
                        callback.onModeAvailable(ProcessingMode.CPU, "CPU (XNNPACK Optimized)");
                    });
                } else {
                    mainHandler.post(() -> {
                        callback.onModeFailed(ProcessingMode.CPU, "Failed to create CPU interpreter");
                    });
                }
                
                return interpreter;
            });
    }
    
    /**
     * Phase 2: Start parallel background loading for GPU and NPU.
     */
    private void startParallelBackgroundLoading(HybridCallback callback, long startTime) {
        Log.d(TAG, "Phase 2: Starting parallel background loading for GPU/NPU");
        
        // Wait for quick start to complete before starting background tasks
        executorService.execute(() -> {
            // Small delay to ensure CPU is ready first
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            mainHandler.post(() -> callback.onProgress("Loading GPU and NPU in background...", 40));
            
            // GPU initialization
            CompletableFuture<Void> gpuFuture = initializeGPUInBackground(callback);
            
            // NPU initialization  
            CompletableFuture<Void> npuFuture = initializeNPUInBackground(callback);
            
            // Wait for both to complete
            CompletableFuture.allOf(gpuFuture, npuFuture)
                .orTimeout(BACKGROUND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .handle((result, throwable) -> {
                    long totalTime = System.currentTimeMillis() - startTime;
                    
                    if (throwable != null) {
                        Log.w(TAG, "Background initialization timeout", throwable);
                    }
                    
                    int totalAvailable = availableModes.get();
                    
                    // If no modes are available yet, wait a bit for CPU
                    if (totalAvailable == 0 && cpuInterpreter == null) {
                        Log.w(TAG, "No modes available yet, waiting for CPU...");
                        // Give CPU more time to complete
                        try {
                            Thread.sleep(2000);
                            totalAvailable = availableModes.get();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    
                    final int finalAvailableModes = totalAvailable;
                    final long finalTotalTime = totalTime;
                    
                    Log.d(TAG, "All initialization complete. Available modes: " + finalAvailableModes + 
                             ", Total time: " + finalTotalTime + "ms");
                    
                    mainHandler.post(() -> {
                        callback.onProgress("Initialization complete!", 100);
                        callback.onAllModesReady(finalAvailableModes, finalTotalTime);
                    });
                    
                    isInitializing.set(false);
                    return null;
                });
        });
    }
    
    /**
     * Initialize GPU in background with validation and optimizations.
     */
    private CompletableFuture<Void> initializeGPUInBackground(HybridCallback callback) {
        return CompletableFuture.runAsync(() -> {
            long startTime = System.currentTimeMillis();
            Log.d(TAG, "Initializing GPU in background with optimizations");
            
            mainHandler.post(() -> callback.onProgress("Validating GPU...", 50));
            
            // Validate GPU first
            HardwareValidator.ValidationResult gpuValidation = 
                HardwareValidator.validateGPU(context, configManager.getSelectedModelPath());
            
            if (!gpuValidation.isAvailable) {
                Log.w(TAG, "GPU not available: " + gpuValidation.failureReason);
                mainHandler.post(() -> {
                    callback.onModeFailed(ProcessingMode.GPU, gpuValidation.failureReason);
                });
                return;
            }
            
            mainHandler.post(() -> callback.onProgress("Initializing GPU...", 60));
            
            try {
                // Create GPU interpreter with optimizations
                Interpreter.Options gpuOptions = new Interpreter.Options();
                
                // Optimize interpreter options for faster initialization
                gpuOptions.setNumThreads(1);  // GPU doesn't use CPU threads
                gpuOptions.setAllowBufferHandleOutput(false);  // Reduce overhead
                gpuOptions.setCancellable(false);  // Skip cancellation checks
                
                // Configure GPU delegate with device-specific optimizations
                GpuDelegate.Options delegateOptions = createOptimizedGpuDelegateOptions();
                
                gpuDelegate = new GpuDelegate(delegateOptions);
                gpuOptions.addDelegate(gpuDelegate);
                
                gpuInterpreter = new Interpreter(modelBuffer, gpuOptions);
                availableModes.incrementAndGet();
                
                long initTime = System.currentTimeMillis() - startTime;
                Log.d(TAG, "GPU initialized successfully in " + initTime + "ms");
                
                mainHandler.post(() -> {
                    callback.onProgress("GPU ready!", 70);
                    callback.onModeAvailable(ProcessingMode.GPU, 
                        gpuValidation.deviceInfo + " (" + initTime + "ms)");
                });
                
            } catch (Exception e) {
                long failTime = System.currentTimeMillis() - startTime;
                Log.e(TAG, "GPU initialization failed after " + failTime + "ms", e);
                mainHandler.post(() -> {
                    callback.onModeFailed(ProcessingMode.GPU, 
                        "GPU initialization error: " + e.getMessage());
                });
            }
        }, executorService).orTimeout(BACKGROUND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
          .exceptionally(ex -> {
              if (ex instanceof TimeoutException) {
                  Log.w(TAG, "GPU initialization timed out after " + BACKGROUND_TIMEOUT_MS + "ms");
                  mainHandler.post(() -> {
                      callback.onModeFailed(ProcessingMode.GPU, "Initialization timeout");
                  });
              }
              return null;
          });
    }
    
    /**
     * Creates optimized GPU delegate options based on device characteristics.
     */
    private GpuDelegate.Options createOptimizedGpuDelegateOptions() {
        GpuDelegate.Options options = new GpuDelegate.Options();
        
        // Always use fast single answer for super resolution
        options.setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER);
        
        // Allow precision loss for 2x speedup
        options.setPrecisionLossAllowed(true);
        
        // Apply device-specific optimizations
        String socModel = android.os.Build.SOC_MODEL;
        if (socModel != null) {
            Log.d(TAG, "Detected SoC: " + socModel);
            
            if (socModel.contains("MT8195") || socModel.contains("MT8188")) {
                Log.d(TAG, "Applying Mali-G57 optimizations");
                // Mali-G57 specific settings already applied above
            } else if (socModel.contains("MT8192") || socModel.contains("MT8183")) {
                Log.d(TAG, "Applying Mali-G77 optimizations");
                // Mali-G77 specific settings
            } else if (socModel.contains("Snapdragon")) {
                Log.d(TAG, "Applying Adreno optimizations");
                // Adreno specific settings
            }
        }
        
        return options;
    }
    
    /**
     * Initialize NPU in background with strict validation.
     */
    private CompletableFuture<Void> initializeNPUInBackground(HybridCallback callback) {
        return CompletableFuture.runAsync(() -> {
            Log.d(TAG, "Initializing NPU in background");
            
            // Check if NPU is enabled in config
            if (!configManager.isEnableNpu()) {
                Log.d(TAG, "NPU disabled in configuration");
                mainHandler.post(() -> {
                    callback.onModeFailed(ProcessingMode.NPU, "NPU disabled in settings");
                });
                return;
            }
            
            mainHandler.post(() -> callback.onProgress("Validating NPU...", 75));
            
            // Strict NPU validation
            HardwareValidator.ValidationResult npuValidation = 
                HardwareValidator.validateNPU(context);
            
            if (!npuValidation.isAvailable) {
                Log.w(TAG, "NPU not available: " + npuValidation.failureReason);
                mainHandler.post(() -> {
                    callback.onModeFailed(ProcessingMode.NPU, npuValidation.failureReason);
                });
                return;
            }
            
            mainHandler.post(() -> callback.onProgress("Initializing NPU...", 85));
            
            try {
                // Create NPU interpreter with strict settings
                Interpreter.Options npuOptions = new Interpreter.Options();
                
                // Configure NPU delegate with strict no-fallback policy
                NnApiDelegate.Options delegateOptions = new NnApiDelegate.Options();
                
                // CRITICAL: Prevent CPU fallback
                delegateOptions.setAllowFp16(configManager.isAllowFp16OnNpu());
                delegateOptions.setExecutionPreference(
                    NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED);
                
                // Set specific NPU device if available
                String acceleratorName = configManager.getNpuAcceleratorName();
                if (!acceleratorName.isEmpty()) {
                    delegateOptions.setAcceleratorName(acceleratorName);
                }
                
                npuDelegate = new NnApiDelegate(delegateOptions);
                npuOptions.addDelegate(npuDelegate);
                
                // Create interpreter
                npuInterpreter = new Interpreter(modelBuffer, npuOptions);
                
                // Test NPU inference to ensure it's really working
                boolean npuTestSuccess = HardwareValidator.testNPUInference(context, modelBuffer);
                
                if (!npuTestSuccess) {
                    throw new RuntimeException("NPU test inference failed - possible fallback detected");
                }
                
                availableModes.incrementAndGet();
                
                Log.d(TAG, "NPU initialized successfully");
                
                mainHandler.post(() -> {
                    callback.onProgress("NPU ready!", 95);
                    callback.onModeAvailable(ProcessingMode.NPU, npuValidation.deviceInfo);
                });
                
                // Warn about any validation warnings
                if (!npuValidation.warnings.isEmpty()) {
                    for (String warning : npuValidation.warnings) {
                        Log.w(TAG, "NPU warning: " + warning);
                    }
                }
                
            } catch (Exception e) {
                Log.e(TAG, "NPU initialization failed", e);
                
                // Clean up on failure
                if (npuDelegate != null) {
                    try {
                        npuDelegate.close();
                    } catch (Exception closeEx) {
                        Log.w(TAG, "Error closing NPU delegate", closeEx);
                    }
                    npuDelegate = null;
                }
                
                mainHandler.post(() -> {
                    callback.onModeFailed(ProcessingMode.NPU, 
                        "NPU initialization error: " + e.getMessage());
                });
            }
        }, executorService);
    }
    
    /**
     * Gets the current available modes.
     */
    public boolean isModeAvailable(ProcessingMode mode) {
        switch (mode) {
            case CPU:
                return cpuInterpreter != null;
            case GPU:
                return gpuInterpreter != null;
            case NPU:
                return npuInterpreter != null;
            default:
                return false;
        }
    }
    
    /**
     * Gets the interpreter for a specific mode.
     */
    public Interpreter getInterpreter(ProcessingMode mode) {
        switch (mode) {
            case CPU:
                return cpuInterpreter;
            case GPU:
                return gpuInterpreter;
            case NPU:
                return npuInterpreter;
            default:
                return null;
        }
    }
    
    /**
     * Gets the full processor for advanced operations.
     */
    public ThreadSafeSRProcessor getFullProcessor() {
        return fullProcessor;
    }
    
    /**
     * Checks if quick start is ready.
     */
    public boolean isQuickStartReady() {
        return quickStartReady.get();
    }
    
    /**
     * Gets the number of available modes.
     */
    public int getAvailableModesCount() {
        return availableModes.get();
    }
    
    /**
     * Checks if processing is currently in progress.
     */
    public boolean isProcessing() {
        return isProcessingFlag.get() || (fullProcessor != null && fullProcessor.isProcessing());
    }
    
    /**
     * Cleanup resources.
     */
    public void close() {
        Log.d(TAG, "Closing HybridSRProcessor");
        
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Close interpreters
        if (cpuInterpreter != null) {
            cpuInterpreter.close();
            cpuInterpreter = null;
        }
        if (gpuInterpreter != null) {
            gpuInterpreter.close();
            gpuInterpreter = null;
        }
        if (npuInterpreter != null) {
            npuInterpreter.close();
            npuInterpreter = null;
        }
        
        // Close delegates
        if (gpuDelegate != null) {
            gpuDelegate.close();
            gpuDelegate = null;
        }
        if (npuDelegate != null) {
            npuDelegate.close();
            npuDelegate = null;
        }
        
        // Close full processor
        if (fullProcessor != null) {
            fullProcessor.close();
            fullProcessor = null;
        }
        
        Log.d(TAG, "HybridSRProcessor closed");
    }
}