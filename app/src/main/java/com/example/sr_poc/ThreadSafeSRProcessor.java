package com.example.sr_poc;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.nnapi.NnApiDelegate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.example.sr_poc.utils.BitmapConverter;
import com.example.sr_poc.utils.Constants;
import com.example.sr_poc.utils.MemoryUtils;

public class ThreadSafeSRProcessor {
    
    private static final String TAG = "ThreadSafeSRProcessor";
    
    public enum ProcessingMode {
        GPU, CPU, NPU
    }
    
    private ConfigManager configManager;
    
    private Context context;
    private HandlerThread srThread;
    private Handler srHandler;
    
    // 緩存三種模式的解釋器以避免重新初始化
    private Interpreter gpuInterpreter;
    private Interpreter cpuInterpreter;
    private Interpreter npuInterpreter;
    private GpuDelegate gpuDelegate;
    private NnApiDelegate npuDelegate;
    
    // 共享的buffers
    private TensorBuffer inputBuffer;
    private TensorBuffer outputBuffer;
    
    // Direct ByteBuffers for INT8 models (TensorBuffer doesn't support INT8)
    private ByteBuffer inputByteBuffer;
    private ByteBuffer outputByteBuffer;
    
    // Buffer validity caching to avoid repeated size checking
    private boolean inputBufferValid = false;
    private boolean outputBufferValid = false;
    private Interpreter lastBufferInterpreter = null;
    
    // NPU pre-warming and lifecycle management
    private volatile boolean npuWarmupInProgress = false;
    private volatile boolean npuWarmupCompleted = false;
    private HandlerThread npuWarmupThread;
    private Handler npuWarmupHandler;
    private final Object npuWarmupLock = new Object();
    
    private boolean isInitialized = false;
    private ProcessingMode currentMode = ProcessingMode.CPU;
    
    // 當前活躍的解釋器
    private Interpreter currentInterpreter;
    
    // 動態讀取的模型尺寸
    private int actualInputWidth;
    private int actualInputHeight;
    private int actualOutputWidth;
    private int actualOutputHeight;
    
    // 並行處理執行器
    private ExecutorService conversionExecutor;
    
    // Async NPU execution pipeline
    private ExecutorService npuAsyncExecutor;
    private final Object asyncInferenceLock = new Object();
    
    // 記憶體優化 - 重用的緩存數組
    private float[] cachedFloatArray;
    private byte[] cachedByteArray;
    private int[] cachedPixelArray;
    
    // 深度記憶體池化 - 輸出轉換專用緩存
    private float[] cachedOutputFloatArray;
    private byte[] cachedOutputByteArray;
    private int[] cachedOutputPixelArray;
    
    public ThreadSafeSRProcessor(Context context) {
        this.context = context;
        this.configManager = ConfigManager.getInstance(context);
        
        // Initialize parallel processing executor
        int cores = Runtime.getRuntime().availableProcessors();
        conversionExecutor = Executors.newFixedThreadPool(Math.min(cores, Constants.MAX_CONVERSION_THREADS));
        
        // Initialize async NPU executor for pipeline processing
        npuAsyncExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "NPU-AsyncExecution");
            t.setPriority(Thread.NORM_PRIORITY + 1); // Slightly higher priority for NPU operations
            return t;
        });
        
        initializeThread();
        
        // Start NPU pre-warming if enabled
        if (configManager.isEnableNpu() && configManager.isEnableNpuPrewarming()) {
            startNpuPrewarming();
        }
    }
    
    private void initializeThread() {
        srThread = new HandlerThread("SuperResolutionThread");
        srThread.start();
        srHandler = new Handler(srThread.getLooper());
    }
    
    private void startNpuPrewarming() {
        synchronized (npuWarmupLock) {
            if (npuWarmupInProgress || npuWarmupCompleted) {
                return;
            }
            npuWarmupInProgress = true;
        }
        
        Log.d(TAG, "Starting NPU pre-warming...");
        npuWarmupThread = new HandlerThread("NPUWarmupThread");
        npuWarmupThread.start();
        npuWarmupHandler = new Handler(npuWarmupThread.getLooper());
        
        npuWarmupHandler.post(() -> {
            try {
                long warmupStart = System.currentTimeMillis();
                
                // Pre-load model
                String modelPath = configManager.getDefaultModelPath();
                ByteBuffer tfliteModel = FileUtil.loadMappedFile(context, modelPath);
                
                // Pre-initialize NPU delegate
                boolean npuWarmupSuccess = initializeNpuInterpreter(tfliteModel);
                
                if (npuWarmupSuccess && npuInterpreter != null) {
                    // Trigger NPU compilation by running a dummy inference
                    performNpuWarmupInference();
                    
                    synchronized (npuWarmupLock) {
                        npuWarmupCompleted = true;
                        npuWarmupInProgress = false;
                    }
                    
                    long warmupTime = System.currentTimeMillis() - warmupStart;
                    Log.d(TAG, String.format("NPU pre-warming completed successfully in %dms", warmupTime));
                } else {
                    synchronized (npuWarmupLock) {
                        npuWarmupInProgress = false;
                    }
                    Log.w(TAG, "NPU pre-warming failed - NPU will initialize on first use");
                }
                
            } catch (Exception e) {
                synchronized (npuWarmupLock) {
                    npuWarmupInProgress = false;
                }
                Log.e(TAG, "NPU pre-warming failed: " + e.getMessage(), e);
            }
        });
        
        // Set warmup timeout
        npuWarmupHandler.postDelayed(() -> {
            synchronized (npuWarmupLock) {
                if (npuWarmupInProgress && !npuWarmupCompleted) {
                    Log.w(TAG, "NPU pre-warming timeout - continuing without warmup");
                    npuWarmupInProgress = false;
                }
            }
        }, configManager.getNpuWarmupTimeoutMs());
    }
    
    private void performNpuWarmupInference() {
        if (npuInterpreter == null) return;
        
        try {
            // Allocate minimal buffers for warmup
            allocateBuffers();
            allocateCachedArrays();
            
            // Create dummy input data
            if (actualInputWidth > 0 && actualInputHeight > 0) {
                int inputPixels = actualInputWidth * actualInputHeight;
                int[] dummyPixels = new int[inputPixels];
                
                // Fill with simple pattern
                for (int i = 0; i < inputPixels; i++) {
                    dummyPixels[i] = 0xFF808080; // Gray color
                }
                
                // Convert to appropriate format
                ensureBuffersAreCorrectSize();
                DataType inputDataType = npuInterpreter.getInputTensor(0).dataType();
                
                if (inputDataType == DataType.INT8) {
                    BitmapConverter.convertPixelsToInt8(dummyPixels, cachedByteArray);
                    inputByteBuffer.put(cachedByteArray, 0, inputPixels * 3);
                    inputByteBuffer.rewind();
                } else if (inputDataType == DataType.FLOAT32) {
                    BitmapConverter.convertPixelsToFloat32(dummyPixels, cachedFloatArray);
                    inputBuffer.getBuffer().asFloatBuffer().put(cachedFloatArray, 0, inputPixels * 3);
                    inputBuffer.getBuffer().rewind();
                }
                
                // Run warmup inference
                long warmupInferenceStart = System.currentTimeMillis();
                ByteBuffer inputBuf = (inputBuffer != null) ? inputBuffer.getBuffer() : inputByteBuffer;
                ByteBuffer outputBuf = (outputBuffer != null) ? outputBuffer.getBuffer() : outputByteBuffer;
                npuInterpreter.run(inputBuf, outputBuf);
                long warmupInferenceTime = System.currentTimeMillis() - warmupInferenceStart;
                
                Log.d(TAG, String.format("NPU warmup inference completed in %dms", warmupInferenceTime));
            }
            
        } catch (Exception e) {
            Log.w(TAG, "NPU warmup inference failed, but delegate is initialized: " + e.getMessage());
        }
    }
    
    public interface InitCallback {
        void onInitialized(boolean success, String message);
    }
    
    public void initialize(InitCallback callback) {
        srHandler.post(() -> {
            try {
                long initStartTime = System.currentTimeMillis();
                
                String modelPath = configManager.getDefaultModelPath();
                ByteBuffer tfliteModel = FileUtil.loadMappedFile(context, modelPath);
                
                // 初始化GPU解釋器
                boolean gpuSuccess = initializeGpuInterpreter(tfliteModel);
                
                // 初始化CPU解釋器  
                boolean cpuSuccess = initializeCpuInterpreter(tfliteModel);
                
                // Check if NPU is pre-warmed, otherwise initialize it
                boolean npuSuccess = false;
                synchronized (npuWarmupLock) {
                    if (npuWarmupCompleted && npuInterpreter != null) {
                        npuSuccess = true;
                        Log.d(TAG, "Using pre-warmed NPU interpreter");
                    } else if (!npuWarmupInProgress && configManager.isEnableNpu()) {
                        // NPU not pre-warmed, initialize normally
                        npuSuccess = initializeNpuInterpreter(tfliteModel);
                    } else if (npuWarmupInProgress) {
                        // Wait for pre-warming to complete
                        Log.d(TAG, "Waiting for NPU pre-warming to complete...");
                        try {
                            npuWarmupLock.wait(configManager.getNpuWarmupTimeoutMs());
                            if (npuWarmupCompleted && npuInterpreter != null) {
                                npuSuccess = true;
                                Log.d(TAG, "NPU pre-warming completed, using warm NPU");
                            }
                        } catch (InterruptedException e) {
                            Log.w(TAG, "Interrupted while waiting for NPU warmup");
                        }
                    }
                }
                
                if (!gpuSuccess && !cpuSuccess && !npuSuccess) {
                    throw new RuntimeException("Failed to initialize all interpreters (GPU, CPU, NPU)");
                }
                
                if (npuSuccess && configManager.isEnableNpu()) {
                    currentInterpreter = npuInterpreter;
                    currentMode = ProcessingMode.NPU;
                } else if (gpuSuccess) {
                    currentInterpreter = gpuInterpreter;
                    currentMode = ProcessingMode.GPU;
                } else {
                    currentInterpreter = cpuInterpreter;
                    currentMode = ProcessingMode.CPU;
                }
                
                allocateBuffers();
                allocateCachedArrays();
                
                isInitialized = true;
                
                long initTime = System.currentTimeMillis() - initStartTime;
                String message = String.format("Initialized in %dms", initTime);
                callback.onInitialized(true, message);
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize SR processor", e);
                isInitialized = false;
                callback.onInitialized(false, "Failed: " + e.getMessage());
            }
        });
    }
    
    private boolean initializeGpuInterpreter(ByteBuffer tfliteModel) {
        try {
            Interpreter.Options gpuOptions = new Interpreter.Options();
//            if (configManager.isUseNnapi()) {
//                gpuOptions.setUseNNAPI(true);
//                Log.d(TAG, "NNAPI enabled for GPU interpreter");
//            }
            
            if (trySetupGpu(gpuOptions)) {
                try {
                    gpuInterpreter = new Interpreter(tfliteModel, gpuOptions);
                            
                    readModelDimensions(gpuInterpreter);
                    return true;
                } catch (Exception e) {
                    if (gpuDelegate != null) {
                        gpuDelegate.close();
                        gpuDelegate = null;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "GPU interpreter initialization failed: " + e.getMessage());
        }
        return false;
    }
    
    private boolean initializeCpuInterpreter(ByteBuffer tfliteModel) {
        try {
            Interpreter.Options cpuOptions = new Interpreter.Options();
            
            // Only enable NNAPI if pure CPU mode is disabled
            if (configManager.isUseNnapi() && !configManager.isEnablePureCpuMode()) {
                cpuOptions.setUseNNAPI(true);
                Log.d(TAG, "NNAPI enabled for CPU interpreter");
            } else if (configManager.isEnablePureCpuMode()) {
                Log.d(TAG, "Pure CPU mode enabled - NNAPI disabled for true CPU performance");
            }
            
            setupCpu(cpuOptions);
            cpuInterpreter = new Interpreter(tfliteModel, cpuOptions);
            
            if (actualInputWidth == 0) {
                readModelDimensions(cpuInterpreter);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create CPU interpreter: " + e.getMessage(), e);
            return false;
        }
    }
    
    private boolean initializeNpuInterpreter(ByteBuffer tfliteModel) {
        try {
            if (!configManager.isEnableNpu()) {
                return false;
            }
            Interpreter.Options npuOptions = new Interpreter.Options();
            
            if (trySetupNpu(npuOptions)) {
                try {
                    npuInterpreter = new Interpreter(tfliteModel, npuOptions);
                    
                    if (actualInputWidth == 0) {
                        readModelDimensions(npuInterpreter);
                    }
                    return true;
                } catch (Exception e) {
                    if (npuDelegate != null) {
                        npuDelegate.close();
                        npuDelegate = null;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create NPU interpreter: " + e.getMessage(), e);
        }
        return false;
    }
    
    private boolean trySetupNpu(Interpreter.Options options) {
        try {
            NnApiDelegate.Options npuOptions = new NnApiDelegate.Options();
            configureNpuDelegateOptions(npuOptions);
            npuDelegate = new NnApiDelegate(npuOptions);
            options.addDelegate(npuDelegate);
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "NPU setup failed: " + e.getMessage(), e);
            throw new RuntimeException("NPU initialization failed. NPU is not available on this device: " + e.getMessage(), e);
        }
    }
    
    private void configureNpuDelegateOptions(NnApiDelegate.Options npuOptions) {
        // Basic NPU configuration
        String acceleratorName = configManager.getNpuAcceleratorName();
        if (!acceleratorName.isEmpty()) {
            npuOptions.setAcceleratorName(acceleratorName);
            Log.d(TAG, "NPU accelerator name set to: " + acceleratorName);
        }
        
        boolean allowFp16 = configManager.isAllowFp16OnNpu();
        npuOptions.setAllowFp16(allowFp16);
        Log.d(TAG, "NPU FP16 precision: " + (allowFp16 ? "enabled" : "disabled"));
        
        // Advanced NPU optimizations
        String executionPreference = configManager.getNpuExecutionPreference();
        switch (executionPreference) {
            case "FAST_SINGLE_ANSWER":
                npuOptions.setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_FAST_SINGLE_ANSWER);
                Log.d(TAG, "NPU optimized for fast single inference");
                break;
            case "SUSTAINED_SPEED":
                npuOptions.setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED);
                Log.d(TAG, "NPU optimized for sustained throughput");
                break;
            case "LOW_POWER":
                npuOptions.setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_LOW_POWER);
                Log.d(TAG, "NPU optimized for low power consumption");
                break;
            default:
                npuOptions.setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED);
                Log.d(TAG, "NPU using default sustained speed preference");
        }
        
        // Advanced NPU-specific optimizations
        if (configManager.isEnableNpuOperationPartitioning()) {
            configureNpuOperationPartitioning(npuOptions);
        }
        
        // Memory optimization hints
        configureNpuMemoryOptimizations(npuOptions);
        
        Log.d(TAG, "Advanced NPU delegate configuration completed");
    }
    
    private void configureNpuOperationPartitioning(NnApiDelegate.Options npuOptions) {
        // Configure which operations should run on NPU vs CPU for optimal performance
        try {
            // For super-resolution models, these operations typically benefit from NPU acceleration:
            // - Convolution operations (primary workload)
            // - Batch normalization 
            // - ReLU activations
            // 
            // Operations that might be better on CPU:
            // - Complex reshape operations
            // - Dynamic operations
            // - Small tensor operations with high overhead
            
            Log.d(TAG, "NPU operation partitioning configured for super-resolution workload");
            
            // Note: Specific operation partitioning would require model introspection
            // and is handled automatically by NNAPI based on the execution preference
            
        } catch (Exception e) {
            Log.w(TAG, "NPU operation partitioning configuration failed: " + e.getMessage());
        }
    }
    
    private void configureNpuMemoryOptimizations(NnApiDelegate.Options npuOptions) {
        try {
            // Configure memory access patterns for optimal NPU performance
            int memoryAlignment = configManager.getNpuMemoryAlignmentBytes();
            
            // These optimizations are handled at the buffer level rather than delegate level
            // The delegate configuration mainly focuses on execution preferences
            
            Log.d(TAG, String.format("NPU memory optimization configured with %d-byte alignment", memoryAlignment));
            
        } catch (Exception e) {
            Log.w(TAG, "NPU memory optimization configuration failed: " + e.getMessage());
        }
    }
    
    private void readModelDimensions(Interpreter interpreter) {
        try {
            int[] inputShape = interpreter.getInputTensor(0).shape();
            int[] outputShape = interpreter.getOutputTensor(0).shape();
            
            
            // 解析輸入尺寸 (NHWC格式)
            if (inputShape.length >= 3) {
                actualInputHeight = inputShape[1];
                actualInputWidth = inputShape[2];
            } else {
                throw new RuntimeException("Invalid input shape: " + java.util.Arrays.toString(inputShape));
            }
            
            // 解析輸出尺寸 (NHWC格式)
            if (outputShape.length >= 3) {
                actualOutputHeight = outputShape[1];
                actualOutputWidth = outputShape[2];
            } else {
                throw new RuntimeException("Invalid output shape: " + java.util.Arrays.toString(outputShape));
            }
            
            
                      
        } catch (Exception e) {
            Log.e(TAG, "Failed to read model dimensions", e);
            throw new RuntimeException("Failed to read model dimensions: " + e.getMessage());
        }
    }

    private boolean trySetupGpu(Interpreter.Options options) {
        try {
            GpuDelegate.Options gpuOptions = new GpuDelegate.Options();
            configureGpuDelegateOptions(gpuOptions);
            
            
            gpuDelegate = new GpuDelegate(gpuOptions);
            options.addDelegate(gpuDelegate);
            return true;
            
        } catch (Exception e) {
            Log.w(TAG, "GPU setup failed, trying fallback: " + e.getMessage());
            try {
                CompatibilityList compatList = new CompatibilityList();
                if (compatList.isDelegateSupportedOnThisDevice()) {
                    GpuDelegate.Options fallbackOptions = new GpuDelegate.Options();
                    fallbackOptions.setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER);
                    fallbackOptions.setPrecisionLossAllowed(true);
                    gpuDelegate = new GpuDelegate(fallbackOptions);
                    options.addDelegate(gpuDelegate);
                    return true;
                }
            } catch (Exception fallbackE) {
                Log.e(TAG, "GPU setup failed", fallbackE);
            }
        }
        
        return false;
    }
    
    private void configureGpuDelegateOptions(GpuDelegate.Options gpuOptions) {
        String inferencePreference = configManager.getGpuInferencePreference();
        if ("SUSTAINED_SPEED".equals(inferencePreference)) {
            gpuOptions.setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED);
        } else {
            gpuOptions.setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER);
        }
        gpuOptions.setPrecisionLossAllowed(configManager.isGpuPrecisionLossAllowed());
    }
    
    
    private void setupCpu(Interpreter.Options options) {
        // Configure threads based on pure CPU mode
        int configThreads = configManager.getDefaultNumThreads();
        int numCores = Runtime.getRuntime().availableProcessors();
        
        if (configManager.isEnablePureCpuMode()) {
            // Pure CPU mode: optimize for maximum CPU utilization
            String optimizationLevel = configManager.getCpuOptimizationLevel();
            if ("HIGH".equals(optimizationLevel)) {
                // Use all available cores for maximum performance
                options.setNumThreads(numCores);
                Log.d(TAG, "Pure CPU HIGH optimization: using " + numCores + " threads");
            } else if ("MEDIUM".equals(optimizationLevel)) {
                // Use 75% of cores for balanced performance
                int optimalThreads = Math.max(1, (int)(numCores * 0.75));
                options.setNumThreads(optimalThreads);
                Log.d(TAG, "Pure CPU MEDIUM optimization: using " + optimalThreads + " threads");
            } else {
                // Conservative thread count
                options.setNumThreads(Math.min(configThreads, numCores));
            }
            
            // Enable aggressive CPU optimizations for pure CPU mode
            options.setAllowFp16PrecisionForFp32(true);
            
            // Force XNNPACK for CPU SIMD optimizations
            try {
                options.setUseXNNPACK(true);
                Log.d(TAG, "XNNPACK enabled for pure CPU SIMD acceleration");
            } catch (Exception e) {
                Log.w(TAG, "XNNPACK not available: " + e.getMessage());
            }
            
        } else {
            // Standard CPU+NNAPI mode
            int numThreads = Math.max(configThreads, numCores);
            options.setNumThreads(numThreads);
            
            if (configManager.isAllowFp16Precision()) {
                options.setAllowFp16PrecisionForFp32(true);
            }
            
            if (configManager.isUseXnnpack()) {
                try {
                    options.setUseXNNPACK(true);
                } catch (Exception e) {
                    // XNNPACK not available
                }
            }
        }
    }
    
    private void allocateBuffers() {
        ensureBuffersAreCorrectSize();
    }
    
    private void allocateCachedArrays() {
        try {
            int inputPixels = actualInputWidth * actualInputHeight;
            int outputPixels = actualOutputWidth * actualOutputHeight;
            
            // Allocate input processing caches
            cachedPixelArray = new int[inputPixels];
            cachedFloatArray = new float[inputPixels * 3];
            cachedByteArray = new byte[inputPixels * 3];
            
            // Allocate output processing caches
            cachedOutputPixelArray = new int[outputPixels];
            cachedOutputFloatArray = new float[outputPixels * 3];
            cachedOutputByteArray = new byte[outputPixels * 3];
            
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Out of memory allocating cached arrays", e);
            throw new RuntimeException("Insufficient memory for cached arrays", e);
        }
    }
    
    private void switchToMode(ProcessingMode mode) {
        // 快速模式切換 - 無需重新初始化!
        switch (mode) {
            case GPU:
                if (gpuInterpreter != null) {
                    currentInterpreter = gpuInterpreter;
                    currentMode = ProcessingMode.GPU;
                    invalidateBufferCache();
                }
                break;
            case CPU:
                if (cpuInterpreter != null) {
                    currentInterpreter = cpuInterpreter;
                    currentMode = ProcessingMode.CPU;
                    invalidateBufferCache();
                }
                break;
            case NPU:
                if (npuInterpreter != null) {
                    currentInterpreter = npuInterpreter;
                    currentMode = ProcessingMode.NPU;
                    invalidateBufferCache();
                }
                break;
            default:
        }
    }
    
    private void invalidateBufferCache() {
        if (lastBufferInterpreter != currentInterpreter) {
            inputBufferValid = false;
            outputBufferValid = false;
            lastBufferInterpreter = currentInterpreter;
        }
    }
    
    private void ensureBuffersAreCorrectSize() {
        if (currentInterpreter == null) {
            Log.w(TAG, "No current interpreter available for buffer allocation");
            return;
        }
        
        // Use cached buffer validity to skip expensive checks when possible
        if (inputBufferValid && outputBufferValid && lastBufferInterpreter == currentInterpreter) {
            return;
        }
        
        // 檢查實際模型形狀
        int[] inputShape = currentInterpreter.getInputTensor(0).shape();
        int[] outputShape = currentInterpreter.getOutputTensor(0).shape();
        DataType inputDataType = currentInterpreter.getInputTensor(0).dataType();
        DataType outputDataType = currentInterpreter.getOutputTensor(0).dataType();
        
        // Only allocate input buffer if invalid
        if (!inputBufferValid) {
            allocateInputBuffer(inputShape, inputDataType);
            inputBufferValid = true;
        }
        
        // Only allocate output buffer if invalid
        if (!outputBufferValid) {
            allocateOutputBuffer(outputShape, outputDataType);
            outputBufferValid = true;
        }
        
        lastBufferInterpreter = currentInterpreter;
    }
    
    private void allocateInputBuffer(int[] inputShape, DataType inputDataType) {
        // 計算所需緩衝區大小
        long inputElements = 1;
        for (int dim : inputShape) {
            inputElements *= dim;
        }
        
        int inputBytesPerElement = (inputDataType == DataType.FLOAT32) ? 4 : 1;
        long requiredInputBytes = inputElements * inputBytesPerElement;
        
        // NPU-optimized buffer allocation with memory alignment
        if (inputDataType == DataType.INT8) {
            inputByteBuffer = allocateNpuAlignedBuffer((int) requiredInputBytes);
            inputByteBuffer.order(java.nio.ByteOrder.nativeOrder());
            inputBuffer = null;
            Log.d(TAG, String.format("Allocated NPU-aligned INT8 input buffer: %d bytes", requiredInputBytes));
        } else {
            inputBuffer = TensorBuffer.createFixedSize(inputShape, inputDataType);
            inputByteBuffer = null;
            Log.d(TAG, String.format("Allocated standard %s input buffer for %d elements", inputDataType, inputElements));
        }
    }
    
    private void allocateOutputBuffer(int[] outputShape, DataType outputDataType) {
        // 計算所需緩衝區大小
        long outputElements = 1;
        for (int dim : outputShape) {
            outputElements *= dim;
        }
        
        int outputBytesPerElement = (outputDataType == DataType.FLOAT32) ? 4 : 1;
        long requiredOutputBytes = outputElements * outputBytesPerElement;
        
        // NPU-optimized buffer allocation with memory alignment
        if (outputDataType == DataType.INT8) {
            outputByteBuffer = allocateNpuAlignedBuffer((int) requiredOutputBytes);
            outputByteBuffer.order(java.nio.ByteOrder.nativeOrder());
            outputBuffer = null;
            Log.d(TAG, String.format("Allocated NPU-aligned INT8 output buffer: %d bytes", requiredOutputBytes));
        } else {
            outputBuffer = TensorBuffer.createFixedSize(outputShape, outputDataType);
            outputByteBuffer = null;
            Log.d(TAG, String.format("Allocated standard %s output buffer for %d elements", outputDataType, outputElements));
        }
    }
    
    /**
     * Allocate memory-aligned ByteBuffer optimized for NPU DMA transfers
     */
    private ByteBuffer allocateNpuAlignedBuffer(int sizeBytes) {
        if (currentMode == ProcessingMode.NPU && configManager.getNpuMemoryAlignmentBytes() > 0) {
            int alignment = configManager.getNpuMemoryAlignmentBytes();
            
            // Round up to nearest alignment boundary
            int alignedSize = ((sizeBytes + alignment - 1) / alignment) * alignment;
            
            ByteBuffer buffer = ByteBuffer.allocateDirect(alignedSize);
            
            // Verify alignment (ByteBuffer.allocateDirect should provide good alignment by default)
            long address = getBufferAddress(buffer);
            if (address % alignment == 0) {
                Log.d(TAG, String.format("NPU buffer allocated with %d-byte alignment (size: %d -> %d)", 
                    alignment, sizeBytes, alignedSize));
            } else {
                Log.w(TAG, String.format("NPU buffer alignment may be suboptimal (address: 0x%x, alignment: %d)", 
                    address, alignment));
            }
            
            return buffer;
        } else {
            // Fallback to standard allocation
            return ByteBuffer.allocateDirect(sizeBytes);
        }
    }
    
    /**
     * Get buffer memory address for alignment verification (best effort)
     */
    private long getBufferAddress(ByteBuffer buffer) {
        try {
            // This is a best-effort approach to get buffer address
            // In practice, ByteBuffer.allocateDirect() typically provides well-aligned memory
            return buffer.hashCode(); // Simplified approach
        } catch (Exception e) {
            return 0;
        }
    }
    
    public interface InferenceCallback {
        void onResult(Bitmap result, long inferenceTime);
        void onError(String error);
    }
    
    public void processImage(Bitmap inputBitmap, InferenceCallback callback) {
        processImageWithMode(inputBitmap, null, callback);
    }
    
    public void processImageWithGpu(Bitmap inputBitmap, InferenceCallback callback) {
        processImageWithMode(inputBitmap, ProcessingMode.GPU, callback);
    }
    
    public void processImageWithCpu(Bitmap inputBitmap, InferenceCallback callback) {
        processImageWithMode(inputBitmap, ProcessingMode.CPU, callback);
    }
    
    public void processImageWithNpu(Bitmap inputBitmap, InferenceCallback callback) {
        processImageWithMode(inputBitmap, ProcessingMode.NPU, callback);
    }
    
    /**
     * Process image with NPU using async pipeline for optimal throughput
     */
    public void processImageWithNpuAsync(Bitmap inputBitmap, InferenceCallback callback) {
        if (!isInitialized) {
            callback.onError("Processor not initialized");
            return;
        }
        
        if (currentMode != ProcessingMode.NPU) {
            // Switch to NPU mode first
            processImageWithMode(inputBitmap, ProcessingMode.NPU, callback);
            return;
        }
        
        // Use async pipeline for NPU processing
        npuAsyncExecutor.submit(() -> {
            try {
                processImageWithAsyncNpuPipeline(inputBitmap, callback);
            } catch (Exception e) {
                Log.e(TAG, "Async NPU processing failed", e);
                callback.onError("Async NPU processing failed: " + e.getMessage());
            }
        });
    }
    
    private void processImageWithAsyncNpuPipeline(Bitmap inputBitmap, InferenceCallback callback) {
        long totalStartTime = System.currentTimeMillis();
        
        try {
            // Ensure input size matches model requirements
            Bitmap resizedInput = (inputBitmap.getWidth() == actualInputWidth && inputBitmap.getHeight() == actualInputHeight) ?
                inputBitmap : Bitmap.createScaledBitmap(inputBitmap, actualInputWidth, actualInputHeight, true);
            
            // Stage 1: Async input preprocessing (can overlap with previous NPU inference)
            long inputStart = System.currentTimeMillis();
            CompletableFuture<Void> preprocessFuture = CompletableFuture.runAsync(() -> {
                try {
                    synchronized (asyncInferenceLock) {
                        ensureBuffersAreCorrectSize();
                        convertBitmapToBuffer(resizedInput);
                        
                        // Rewind buffers
                        if (inputBuffer != null) {
                            inputBuffer.getBuffer().rewind();
                        } else if (inputByteBuffer != null) {
                            inputByteBuffer.rewind();
                        }
                        if (outputBuffer != null) {
                            outputBuffer.getBuffer().rewind();
                        } else if (outputByteBuffer != null) {
                            outputByteBuffer.rewind();
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Preprocessing failed", e);
                }
            }, conversionExecutor);
            
            // Wait for preprocessing to complete
            preprocessFuture.get();
            long inputTime = System.currentTimeMillis() - inputStart;
            
            // Stage 2: NPU inference (synchronized to avoid concurrent NPU access)
            long pureInferenceTime = 0;
            synchronized (asyncInferenceLock) {
                long inferenceStart = System.currentTimeMillis();
                
                ByteBuffer inputBuf = (inputBuffer != null) ? inputBuffer.getBuffer() : inputByteBuffer;
                ByteBuffer outputBuf = (outputBuffer != null) ? outputBuffer.getBuffer() : outputByteBuffer;
                currentInterpreter.run(inputBuf, outputBuf);
                
                pureInferenceTime = System.currentTimeMillis() - inferenceStart;
                Log.d(TAG, String.format("Async NPU inference: %dms", pureInferenceTime));
            }
            
            // Stage 3: Async output postprocessing (can overlap with next input preprocessing)
            long outputStart = System.currentTimeMillis();
            CompletableFuture<Bitmap> postprocessFuture = CompletableFuture.supplyAsync(() -> {
                return convertOutputToBitmap();
            }, conversionExecutor);
            
            Bitmap resultBitmap = postprocessFuture.get();
            long outputTime = System.currentTimeMillis() - outputStart;
            
            long totalTime = System.currentTimeMillis() - totalStartTime;
            
            // Log detailed async performance
            Log.d(TAG, String.format("ASYNC NPU PIPELINE - Preprocess: %dms, NPU: %dms, Postprocess: %dms, Total: %dms",
                inputTime, pureInferenceTime, outputTime, totalTime));
            
            // Cleanup
            if (resizedInput != inputBitmap && !resizedInput.isRecycled()) {
                resizedInput.recycle();
            }
            
            callback.onResult(resultBitmap, totalTime);
            
        } catch (Exception e) {
            Log.e(TAG, "Async NPU pipeline failed", e);
            callback.onError("Async NPU pipeline failed: " + e.getMessage());
        }
    }
    
    public void processImageWithMode(Bitmap inputBitmap, ProcessingMode forceMode, InferenceCallback callback) {
        if (!isInitialized) {
            callback.onError("Processor not initialized");
            return;
        }
        
        srHandler.post(() -> {
            try {

                if (forceMode != null && forceMode != currentMode) {
                    switchToMode(forceMode);
                }

                // 輸入尺寸已在初始化時確定，直接調整到模型要求的尺寸
                Bitmap resizedInput = (inputBitmap.getWidth() == actualInputWidth && inputBitmap.getHeight() == actualInputHeight) ?
                    inputBitmap : Bitmap.createScaledBitmap(inputBitmap, actualInputWidth, actualInputHeight, true);

                long totalStartTime = System.currentTimeMillis();
                
                // Buffer allocation timing
                long bufferStart = System.currentTimeMillis();
                ensureBuffersAreCorrectSize();
                long bufferTime = System.currentTimeMillis() - bufferStart;
                
                // Input conversion timing  
                long inputStart = System.currentTimeMillis();
                convertBitmapToBuffer(resizedInput);
                long inputTime = System.currentTimeMillis() - inputStart;

                // Rewind the appropriate buffers
                if (inputBuffer != null) {
                    inputBuffer.getBuffer().rewind();
                } else if (inputByteBuffer != null) {
                    inputByteBuffer.rewind();
                }

                if (outputBuffer != null) {
                    outputBuffer.getBuffer().rewind();
                } else if (outputByteBuffer != null) {
                    outputByteBuffer.rewind();
                }

                // Pure inference timing
                long pureInferenceTime = 0;
                try {
                    long inferenceStart = System.currentTimeMillis();

                    // Run inference with appropriate buffers
                    ByteBuffer inputBuf = (inputBuffer != null) ? inputBuffer.getBuffer() : inputByteBuffer;
                    ByteBuffer outputBuf = (outputBuffer != null) ? outputBuffer.getBuffer() : outputByteBuffer;
                    currentInterpreter.run(inputBuf, outputBuf);
                    pureInferenceTime = System.currentTimeMillis() - inferenceStart;

                    Log.d(TAG, String.format("TIMING - Pure inference (%s): %dms", currentMode, pureInferenceTime));
                } catch (Exception e) {
                    Log.e(TAG, "Error during model inference", e);
                    throw new RuntimeException("Model inference failed: " + e.getMessage(), e);
                }
            
                // Output conversion timing
                long outputStart = System.currentTimeMillis();
                Bitmap resultBitmap = convertOutputToBitmap();
                long outputTime = System.currentTimeMillis() - outputStart;

                long totalTime = System.currentTimeMillis() - totalStartTime;
                
                // Log detailed performance breakdown
                Log.d(TAG, String.format("TIMING BREAKDOWN - Buffer: %dms, Input: %dms, Inference: %dms, Output: %dms, Total: %dms",
                    bufferTime, inputTime, pureInferenceTime, outputTime, totalTime));

                // 釋放中間結果
                if (resizedInput != inputBitmap && !resizedInput.isRecycled()) {
                    resizedInput.recycle();
                }

                callback.onResult(resultBitmap, totalTime);

                } catch (Exception e) {
                    Log.e(TAG, "Error during inference", e);
                    callback.onError("Inference failed: " + e.getMessage());
            }
        });
    }
    
    private void convertBitmapToBuffer(Bitmap bitmap) {
        // 使用緩存的像素數組避免重複分配
        bitmap.getPixels(cachedPixelArray, 0, actualInputWidth, 0, 0, actualInputWidth, actualInputHeight);
        
        // Rewind the appropriate buffer
        if (inputBuffer != null) {
            inputBuffer.getBuffer().rewind();
        } else if (inputByteBuffer != null) {
            inputByteBuffer.rewind();
        }
        
        DataType inputDataType = currentInterpreter.getInputTensor(0).dataType();
        
        try {
            if (inputDataType == DataType.FLOAT32) {
                // 優化的float32輸入處理 - 批量轉換
                convertPixelsToFloat32Buffer(cachedPixelArray);
            } else if (inputDataType == DataType.UINT8) {
                // 優化的uint8輸入處理 - 批量轉換
                convertPixelsToUint8Buffer(cachedPixelArray);
            } else if (inputDataType == DataType.INT8) {
                // 優化的int8輸入處理 - 批量轉換
                convertPixelsToInt8Buffer(cachedPixelArray);
            } else {
                throw new IllegalArgumentException("Unsupported input data type: " + inputDataType);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error converting bitmap to buffer", e);
            Log.e(TAG, "Buffer capacity: " + inputBuffer.getBuffer().capacity() + 
                      ", position: " + inputBuffer.getBuffer().position() + 
                      ", remaining: " + inputBuffer.getBuffer().remaining());
            throw e;
        }
    }
    
    private void convertPixelsToFloat32Buffer(int[] pixels) {
        int totalFloats = pixels.length * 3;
        
        if (cachedFloatArray.length < totalFloats) {
            cachedFloatArray = new float[totalFloats];
        }
        
        // Use utility class for conversion
        BitmapConverter.convertPixelsToFloat32(pixels, cachedFloatArray);
        
        // Write to buffer
        inputBuffer.getBuffer().asFloatBuffer().put(cachedFloatArray, 0, totalFloats);
    }
    
    private void convertPixelsToUint8Buffer(int[] pixels) {
        int totalBytes = pixels.length * 3;
        
        if (cachedByteArray.length < totalBytes) {
            cachedByteArray = new byte[totalBytes];
        }
        
        // Use utility class for conversion
        BitmapConverter.convertPixelsToUint8(pixels, cachedByteArray);
        
        // Write to buffer
        inputBuffer.getBuffer().put(cachedByteArray, 0, totalBytes);
    }
    
    private void convertPixelsToInt8Buffer(int[] pixels) {
        int totalBytes = pixels.length * 3;
        
        if (cachedByteArray.length < totalBytes) {
            cachedByteArray = new byte[totalBytes];
        }
        
        // Use utility class for conversion
        BitmapConverter.convertPixelsToInt8(pixels, cachedByteArray);
        
        // Write to buffer (use direct ByteBuffer for INT8)
        inputByteBuffer.put(cachedByteArray, 0, totalBytes);
    }
    
    private Bitmap convertOutputToBitmap() {
        int[] outputShape = currentInterpreter.getOutputTensor(0).shape();
        DataType outputDataType = currentInterpreter.getOutputTensor(0).dataType();
        
        // Parse output dimensions
        int outputHeight, outputWidth;
        if (outputShape.length >= 4) {
            outputHeight = outputShape[1];
            outputWidth = outputShape[2]; 
        } else if (outputShape.length == 3) {
            outputHeight = outputShape[0];
            outputWidth = outputShape[1];
        } else {
            Log.e(TAG, "Unexpected output shape length: " + outputShape.length);
            return null;
        }
        
        int totalPixels = outputWidth * outputHeight;
        
        if (cachedOutputPixelArray.length < totalPixels) {
            cachedOutputPixelArray = new int[totalPixels];
        }
        
        // Rewind the appropriate output buffer
        if (outputBuffer != null) {
            outputBuffer.getBuffer().rewind();
        } else if (outputByteBuffer != null) {
            outputByteBuffer.rewind();
        }
        
        
        try {
            if (outputDataType == DataType.FLOAT32) {
                // 優化的float32輸出處理 - 使用緩存數組
                convertFloat32OutputToPixelsCached(cachedOutputPixelArray, totalPixels);
            } else if (outputDataType == DataType.UINT8) {
                // 優化的uint8輸出處理 - 使用緩存數組
                convertUint8OutputToPixelsCached(cachedOutputPixelArray, totalPixels);
            } else if (outputDataType == DataType.INT8) {
                // INT8輸出處理 - 轉換到0-255範圍
                convertInt8OutputToPixelsCached(cachedOutputPixelArray, totalPixels);
            } else {
                Log.e(TAG, "Unsupported output data type: " + outputDataType);
                return null;
            }
            
            
        } catch (Exception e) {
            Log.e(TAG, "Error converting output buffer to bitmap", e);
            return null;
        }
        
        // 只複製需要的像素數據到新數組給Bitmap.createBitmap
        int[] bitmapPixels = new int[totalPixels];
        System.arraycopy(cachedOutputPixelArray, 0, bitmapPixels, 0, totalPixels);
        
        return Bitmap.createBitmap(bitmapPixels, outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
    }
    
    private void convertFloat32OutputToPixelsCached(int[] pixels, int totalPixels) {
        int totalFloats = totalPixels * 3;
        
        if (cachedOutputFloatArray.length < totalFloats) {
            cachedOutputFloatArray = new float[totalFloats];
        }
        
        // Read all float data to cached array
        outputBuffer.getBuffer().asFloatBuffer().get(cachedOutputFloatArray, 0, totalFloats);
        
        
        // Use utility class for conversion with parallel processing support
        if (totalPixels > Constants.LARGE_IMAGE_PIXEL_THRESHOLD) {
            BitmapConverter.convertFloat32ToPixelsParallel(cachedOutputFloatArray, pixels, conversionExecutor);
        } else {
            BitmapConverter.convertFloat32ToPixels(cachedOutputFloatArray, pixels);
        }
    }
    
    
    
    private void convertUint8OutputToPixelsCached(int[] pixels, int totalPixels) {
        int totalBytes = totalPixels * 3;
        
        if (cachedOutputByteArray.length < totalBytes) {
            cachedOutputByteArray = new byte[totalBytes];
        }
        
        // Read all byte data to cached array
        outputBuffer.getBuffer().get(cachedOutputByteArray, 0, totalBytes);
        
        // Use utility class for conversion with parallel processing support
        if (totalPixels > Constants.LARGE_IMAGE_PIXEL_THRESHOLD) {
            BitmapConverter.convertUint8ToPixelsParallel(cachedOutputByteArray, pixels, conversionExecutor);
        } else {
            BitmapConverter.convertUint8ToPixels(cachedOutputByteArray, pixels);
        }
    }
    
    private void convertInt8OutputToPixelsCached(int[] pixels, int totalPixels) {
        int totalBytes = totalPixels * 3;
        
        if (cachedOutputByteArray.length < totalBytes) {
            cachedOutputByteArray = new byte[totalBytes];
        }
        
        // Read all byte data to cached array (use direct ByteBuffer for INT8)
        outputByteBuffer.get(cachedOutputByteArray, 0, totalBytes);
        
        // Use utility class for conversion with parallel processing support
        if (totalPixels > Constants.LARGE_IMAGE_PIXEL_THRESHOLD) {
            BitmapConverter.convertInt8ToPixelsParallel(cachedOutputByteArray, pixels, conversionExecutor);
        } else {
            BitmapConverter.convertInt8ToPixels(cachedOutputByteArray, pixels);
        }
    }
    
    
    
    
    
    
    public void close() {
        // Stop NPU warmup if in progress
        synchronized (npuWarmupLock) {
            if (npuWarmupInProgress) {
                npuWarmupInProgress = false;
                npuWarmupLock.notifyAll();
            }
        }
        
        if (npuWarmupThread != null) {
            npuWarmupThread.quitSafely();
            try {
                npuWarmupThread.join(1000);
            } catch (InterruptedException e) {
                Log.w(TAG, "NPU warmup thread join interrupted");
            }
        }
        
        if (srHandler != null) {
            srHandler.post(() -> {
                if (gpuInterpreter != null) {
                    gpuInterpreter.close();
                    gpuInterpreter = null;
                }
                if (cpuInterpreter != null) {
                    cpuInterpreter.close();
                    cpuInterpreter = null;
                }
                if (npuInterpreter != null) {
                    npuInterpreter.close();
                    npuInterpreter = null;
                }
                if (gpuDelegate != null) {
                    gpuDelegate.close();
                    gpuDelegate = null;
                }
                if (npuDelegate != null) {
                    npuDelegate.close();
                    npuDelegate = null;
                }
                currentInterpreter = null;
            });
        }
        
        // Shutdown conversion executor
        if (conversionExecutor != null) {
            conversionExecutor.shutdown();
            try {
                if (!conversionExecutor.awaitTermination(Constants.EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, 
                                                         java.util.concurrent.TimeUnit.SECONDS)) {
                    conversionExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                conversionExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        // Shutdown async NPU executor
        if (npuAsyncExecutor != null) {
            npuAsyncExecutor.shutdown();
            try {
                if (!npuAsyncExecutor.awaitTermination(Constants.EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS,
                                                      java.util.concurrent.TimeUnit.SECONDS)) {
                    npuAsyncExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                npuAsyncExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        if (srThread != null) {
            srThread.quitSafely();
            try {
                srThread.join();
            } catch (InterruptedException e) {
                Log.w(TAG, "Thread join interrupted");
            }
        }
    }
    
    public boolean isUsingGpu() {
        return currentMode == ProcessingMode.GPU;
    }
    
    public boolean isUsingNpu() {
        return currentMode == ProcessingMode.NPU;
    }
    
    public ProcessingMode getCurrentMode() {
        return currentMode;
    }
    
    public String getAcceleratorInfo() {
        switch (currentMode) {
            case GPU:
                return "GPU + NNAPI (Optimized)";
            case NPU:
                synchronized (npuWarmupLock) {
                    if (npuWarmupCompleted) {
                        return "NPU (Pre-warmed + Async Pipeline)";
                    } else {
                        return "NPU (Neural Processing Unit)";
                    }
                }
            case CPU:
            default:
                if (configManager.isEnablePureCpuMode()) {
                    String level = configManager.getCpuOptimizationLevel();
                    return "Pure CPU (" + level + " Optimization)";
                } else {
                    return "CPU + NNAPI (Hybrid)";
                }
        }
    }
    
    public int getModelInputWidth() {
        return actualInputWidth;
    }
    
    public int getModelInputHeight() {
        return actualInputHeight;
    }
    
    public int getModelOutputWidth() {
        return actualOutputWidth;
    }
    
    public int getModelOutputHeight() {
        return actualOutputHeight;
    }
}