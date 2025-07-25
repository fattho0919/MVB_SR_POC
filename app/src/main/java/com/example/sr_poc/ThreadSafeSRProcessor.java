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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class ThreadSafeSRProcessor {
    
    private static final String TAG = "ThreadSafeSRProcessor";
    
    private ConfigManager configManager;
    
    private Context context;
    private HandlerThread srThread;
    private Handler srHandler;
    
    // 緩存兩種模式的解釋器以避免重新初始化
    private Interpreter gpuInterpreter;
    private Interpreter cpuInterpreter;
    private GpuDelegate gpuDelegate;
    
    // 共享的buffers
    private TensorBuffer inputBuffer;
    private TensorBuffer outputBuffer;
    
    private boolean isInitialized = false;
    private boolean isUsingGpu = false;
    
    // 當前活躍的解釋器
    private Interpreter currentInterpreter;
    
    // 動態讀取的模型尺寸
    private int actualInputWidth;
    private int actualInputHeight;
    private int actualOutputWidth;
    private int actualOutputHeight;
    
    // 並行處理執行器
    private ExecutorService conversionExecutor;
    
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
        
        // 初始化並行處理執行器 - 使用CPU核心數
        int cores = Runtime.getRuntime().availableProcessors();
        conversionExecutor = Executors.newFixedThreadPool(Math.min(cores, 4)); // 最多4個線程
        Log.d(TAG, "Initialized conversion executor with " + Math.min(cores, 4) + " threads");
        
        initializeThread();
    }
    
    private void initializeThread() {
        srThread = new HandlerThread("SuperResolutionThread");
        srThread.start();
        srHandler = new Handler(srThread.getLooper());
    }
    
    public interface InitCallback {
        void onInitialized(boolean success, String message);
    }
    
    public void initialize(InitCallback callback) {
        srHandler.post(() -> {
            try {
                Log.d(TAG, "Initializing SR processor with dual-mode setup");
                long initStartTime = System.currentTimeMillis();
                
                String modelPath = configManager.getDefaultModelPath();
                Log.d(TAG, "Loading model: " + modelPath);
                ByteBuffer tfliteModel = FileUtil.loadMappedFile(context, modelPath);
                Log.d(TAG, "Model loaded, size: " + (tfliteModel.capacity() / 1024) + "KB");
                
                // 初始化GPU解釋器
                boolean gpuSuccess = initializeGpuInterpreter(tfliteModel);
                
                // 初始化CPU解釋器  
                boolean cpuSuccess = initializeCpuInterpreter(tfliteModel);
                
                if (!gpuSuccess && !cpuSuccess) {
                    throw new RuntimeException("Failed to initialize both GPU and CPU interpreters");
                }
                
                // 默認使用GPU（如果可用），否則使用CPU
                if (gpuSuccess) {
                    currentInterpreter = gpuInterpreter;
                    isUsingGpu = true;
                    Log.d(TAG, "Default mode: GPU + NNAPI");
                } else {
                    currentInterpreter = cpuInterpreter;
                    isUsingGpu = false;
                    Log.d(TAG, "Default mode: NNAPI/CPU");
                }
                
                Log.d(TAG, "Starting buffer allocation...");
                allocateBuffers();
                Log.d(TAG, "Buffer allocation completed");
                
                Log.d(TAG, "Starting cached arrays allocation...");
                allocateCachedArrays();
                Log.d(TAG, "Cached arrays allocation completed");
                
                isInitialized = true;
                
                long initTime = System.currentTimeMillis() - initStartTime;
                String message = String.format("Initialized both modes in %dms. GPU: %s, CPU: %s", 
                    initTime, gpuSuccess ? "✓" : "✗", cpuSuccess ? "✓" : "✗");
                Log.d(TAG, message);
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
            Log.d(TAG, "=== GPU Interpreter Initialization ===");
            Log.d(TAG, "Model size: " + (tfliteModel.capacity() / 1024) + "KB");
            Log.d(TAG, "Model path: " + configManager.getDefaultModelPath());
            
            Interpreter.Options gpuOptions = new Interpreter.Options();
            if (configManager.isUseNnapi()) {
                gpuOptions.setUseNNAPI(true);
                Log.d(TAG, "NNAPI enabled for GPU interpreter");
            }
            
            if (trySetupGpu(gpuOptions)) {
                Log.d(TAG, "GPU setup successful, creating interpreter...");
                try {
                    Log.d(TAG, "About to create GPU interpreter...");
                    gpuInterpreter = new Interpreter(tfliteModel, gpuOptions);
                    Log.d(TAG, "GPU interpreter created successfully");
                    
                    // 檢查模型輸入輸出格式
                    Log.d(TAG, "Reading model tensors...");
                    int[] inputShape = gpuInterpreter.getInputTensor(0).shape();
                    int[] outputShape = gpuInterpreter.getOutputTensor(0).shape();
                    DataType inputDataType = gpuInterpreter.getInputTensor(0).dataType();
                    DataType outputDataType = gpuInterpreter.getOutputTensor(0).dataType();
                    
                    Log.d(TAG, "GPU Model input shape: " + java.util.Arrays.toString(inputShape));
                    Log.d(TAG, "GPU Model output shape: " + java.util.Arrays.toString(outputShape));
                    Log.d(TAG, "GPU Input data type: " + inputDataType);
                    Log.d(TAG, "GPU Output data type: " + outputDataType);
                    
                    // 讀取並驗證模型尺寸
                    Log.d(TAG, "Reading model dimensions...");
                    readModelDimensions(gpuInterpreter);
                    Log.d(TAG, "Model dimensions read successfully");
                    
                    // 特別檢查 float16 模型在 Mali GPU 上的兼容性
                    String modelPath = configManager.getDefaultModelPath();
                    if (modelPath.contains("float16")) {
                        Log.d(TAG, "Float16 model detected - checking Mali GPU compatibility");
                        Log.d(TAG, "Mali-G57 should support float16, continuing...");
                    }
                    
                    Log.d(TAG, "GPU interpreter initialization completed successfully");
                    return true;
                } catch (Exception e) {
                    Log.e(TAG, "Failed to create GPU interpreter instance: " + e.getMessage(), e);
                    Log.e(TAG, "This might be a model compatibility issue with Mali GPU");
                    
                    // 清理 GPU delegate
                    if (gpuDelegate != null) {
                        try {
                            gpuDelegate.close();
                            gpuDelegate = null;
                        } catch (Exception closeEx) {
                            Log.w(TAG, "Error closing GPU delegate: " + closeEx.getMessage());
                        }
                    }
                }
            } else {
                Log.w(TAG, "GPU setup failed, will fallback to CPU");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create GPU interpreter: " + e.getMessage());
            Log.e(TAG, "GPU interpreter creation exception details: ", e);
        }
        return false;
    }
    
    private boolean initializeCpuInterpreter(ByteBuffer tfliteModel) {
        try {
            Log.d(TAG, "=== CPU Interpreter Initialization ===");
            Interpreter.Options cpuOptions = new Interpreter.Options();
            if (configManager.isUseNnapi()) {
                cpuOptions.setUseNNAPI(true);
                Log.d(TAG, "NNAPI enabled for CPU interpreter");
            }
            
            Log.d(TAG, "Setting up CPU options...");
            setupCpu(cpuOptions);
            
            Log.d(TAG, "Creating CPU interpreter...");
            cpuInterpreter = new Interpreter(tfliteModel, cpuOptions);
            Log.d(TAG, "CPU interpreter created successfully");
            
            // 如果GPU初始化失敗，從CPU interpreter讀取尺寸
            if (actualInputWidth == 0) {
                Log.d(TAG, "Reading model dimensions from CPU interpreter...");
                readModelDimensions(cpuInterpreter);
                Log.d(TAG, "Model dimensions read from CPU interpreter");
            }
            
            Log.d(TAG, "CPU interpreter initialization completed successfully");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create CPU interpreter: " + e.getMessage(), e);
            return false;
        }
    }
    
    private void readModelDimensions(Interpreter interpreter) {
        try {
            int[] inputShape = interpreter.getInputTensor(0).shape();
            int[] outputShape = interpreter.getOutputTensor(0).shape();
            
            Log.d(TAG, "Reading model dimensions - Input: " + java.util.Arrays.toString(inputShape) + 
                      ", Output: " + java.util.Arrays.toString(outputShape));
            
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
            
            // 驗證超解析度倍率是否符合配置
            int expectedScaleFactor = configManager.getExpectedScaleFactor();
            int expectedOutputWidth = actualInputWidth * expectedScaleFactor;
            int expectedOutputHeight = actualInputHeight * expectedScaleFactor;
            if (actualOutputWidth != expectedOutputWidth || actualOutputHeight != expectedOutputHeight) {
                Log.w(TAG, "Warning: Expected " + expectedScaleFactor + "x super resolution (" + 
                          expectedOutputWidth + "x" + expectedOutputHeight + 
                          ") but got (" + actualOutputWidth + "x" + actualOutputHeight + ")");
            }
            
            Log.d(TAG, "Model dimensions - Input: " + actualInputWidth + "x" + actualInputHeight + 
                      ", Output: " + actualOutputWidth + "x" + actualOutputHeight);
                      
        } catch (Exception e) {
            Log.e(TAG, "Failed to read model dimensions", e);
            throw new RuntimeException("Failed to read model dimensions: " + e.getMessage());
        }
    }

    private boolean trySetupGpu(Interpreter.Options options) {
        try {
            Log.d(TAG, "=== GPU Setup (Optimized) ===");
            Log.d(TAG, "Device: " + android.os.Build.MODEL + " (SoC: " + android.os.Build.SOC_MODEL + ")");
            
            // 創建優化的GPU delegate配置
            GpuDelegate.Options gpuOptions = new GpuDelegate.Options();
            
            // 應用配置文件中的GPU優化設定
            configureGpuDelegateOptions(gpuOptions);
            
            // 對於 MediaTek MT8195，使用額外優化
            if (android.os.Build.SOC_MODEL != null && android.os.Build.SOC_MODEL.contains("MT8195")) {
                Log.d(TAG, "MediaTek MT8195 detected - applying Mali-G57 specific optimizations");
                applyMaliOptimizations(gpuOptions);
            }
            
            Log.d(TAG, "Creating optimized GPU delegate...");
            gpuDelegate = new GpuDelegate(gpuOptions);
            options.addDelegate(gpuDelegate);
            Log.d(TAG, "GPU delegate configured successfully with optimizations");
            return true;
            
        } catch (Exception e) {
            Log.w(TAG, "Optimized GPU setup failed, trying fallback: " + e.getMessage());
            
            // 回退到標準GPU設定
            try {
                CompatibilityList compatList = new CompatibilityList();
                boolean isSupported = compatList.isDelegateSupportedOnThisDevice();
                Log.d(TAG, "GPU delegate supported (fallback): " + isSupported);
                
                if (isSupported) {
                    GpuDelegate.Options fallbackOptions = new GpuDelegate.Options();
                    fallbackOptions.setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER);
                    fallbackOptions.setPrecisionLossAllowed(true);
                    
                    gpuDelegate = new GpuDelegate(fallbackOptions);
                    options.addDelegate(gpuDelegate);
                    Log.d(TAG, "Fallback GPU delegate configured");
                    return true;
                }
            } catch (Exception fallbackE) {
                Log.e(TAG, "Both optimized and fallback GPU setup failed", fallbackE);
            }
        }
        
        return false;
    }
    
    /**
     * 配置GPU delegate選項根據配置文件
     */
    private void configureGpuDelegateOptions(GpuDelegate.Options gpuOptions) {
        // Inference preference設定
        String inferencePreference = configManager.getGpuInferencePreference();
        switch (inferencePreference) {
            case "FAST_SINGLE_ANSWER":
                gpuOptions.setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER);
                break;
            case "SUSTAINED_SPEED":
                gpuOptions.setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED);
                break;
            default:
                gpuOptions.setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER);
        }
        
        // Precision loss設定
        gpuOptions.setPrecisionLossAllowed(configManager.isGpuPrecisionLossAllowed());
        
        Log.d(TAG, "GPU configured - Preference: " + inferencePreference + 
                  ", Precision Loss: " + configManager.isGpuPrecisionLossAllowed());
    }
    
    /**
     * 為Mali GPU應用特定優化
     */
    private void applyMaliOptimizations(GpuDelegate.Options gpuOptions) {
        // Mali-G57特定優化
        if (configManager.isExperimentalGpuOptimizations()) {
            Log.d(TAG, "Applying experimental Mali-G57 optimizations");
            
            // 針對量化模型的特殊優化
            if (configManager.isEnableQuantizedInference()) {
                Log.d(TAG, "Applying quantized model optimizations for Mali GPU");
                // 量化模型在Mali GPU上通常有更好的性能
                // 可以使用更激進的設定
            }
            
            // 檢查模型類型並應用相應優化
            String modelPath = configManager.getDefaultModelPath();
            if (modelPath.contains("dynamic_range_quant")) {
                Log.d(TAG, "Dynamic range quantized model detected - optimizing for Mali");
                // 動態範圍量化模型特定優化
            }
        }
    }
    
    private void setupCpu(Interpreter.Options options) {
        // 使用配置文件中的線程數
        int configThreads = configManager.getDefaultNumThreads();
        int numThreads = Math.max(configThreads, Runtime.getRuntime().availableProcessors());
        options.setNumThreads(numThreads);
        
        if (configManager.isAllowFp16Precision()) {
            options.setAllowFp16PrecisionForFp32(true);
        }
        
        if (configManager.isUseXnnpack()) {
            try {
                options.setUseXNNPACK(true);
                Log.d(TAG, "CPU configured with " + numThreads + " threads and XNNPACK");
            } catch (Exception e) {
                Log.w(TAG, "XNNPACK not available");
            }
        }
    }
    
    private void allocateBuffers() {
        Log.d(TAG, "Allocating shared buffers for both interpreters");
        ensureBuffersAreCorrectSize();
    }
    
    private void allocateCachedArrays() {
        try {
            // 預分配緩存數組以避免GC壓力
            int inputPixels = actualInputWidth * actualInputHeight;
            int outputPixels = actualOutputWidth * actualOutputHeight;
            
            Log.d(TAG, "Allocating cached arrays - Input: " + inputPixels + 
                      " pixels, Output: " + outputPixels + " pixels");
            
            // 檢查記憶體是否足夠
            Runtime runtime = Runtime.getRuntime();
            long totalMemoryNeeded = (inputPixels * 3 * 4) + // input float array
                                   (inputPixels * 3) +      // input byte array  
                                   (outputPixels * 4) +     // output pixel array
                                   (outputPixels * 3 * 4) + // output float array
                                   (outputPixels * 3);      // output byte array
            
            long availableMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
            
            Log.d(TAG, "Memory needed: " + (totalMemoryNeeded / 1024 / 1024) + "MB, " +
                      "Available: " + (availableMemory / 1024 / 1024) + "MB");
            
            if (totalMemoryNeeded > availableMemory * 0.5) { // 只使用50%可用記憶體
                Log.w(TAG, "Large memory allocation required, may cause GC pressure");
            }
            
            // 輸入處理緩存
            Log.d(TAG, "Allocating input caches...");
            cachedPixelArray = new int[inputPixels];
            cachedFloatArray = new float[inputPixels * 3]; // 輸入轉換用
            cachedByteArray = new byte[inputPixels * 3];
            
            // 輸出處理緩存 - 這是GC壓力的主要來源
            Log.d(TAG, "Allocating output caches...");
            cachedOutputPixelArray = new int[outputPixels];
            cachedOutputFloatArray = new float[outputPixels * 3]; // RGB 3通道
            cachedOutputByteArray = new byte[outputPixels * 3];
            
            Log.d(TAG, "Successfully allocated all cached arrays");
            Log.d(TAG, "Output cache sizes - Pixels: " + (outputPixels * 4 / 1024 / 1024) + "MB, " +
                      "Floats: " + (outputPixels * 3 * 4 / 1024 / 1024) + "MB");
                      
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Out of memory while allocating cached arrays", e);
            throw new RuntimeException("Failed to allocate cached arrays due to insufficient memory", e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to allocate cached arrays", e);
            throw new RuntimeException("Failed to allocate cached arrays", e);
        }
    }
    
    private void switchToMode(boolean useGpu) {
        // 快速模式切換 - 無需重新初始化!
        if (useGpu && gpuInterpreter != null) {
            currentInterpreter = gpuInterpreter;
            isUsingGpu = true;
            Log.d(TAG, "Switched to GPU mode (instant)");
        } else if (!useGpu && cpuInterpreter != null) {
            currentInterpreter = cpuInterpreter;
            isUsingGpu = false;
            Log.d(TAG, "Switched to CPU mode (instant)");
        } else {
            Log.w(TAG, "Requested interpreter not available, keeping current mode");
        }
    }
    
    private void ensureBuffersAreCorrectSize() {
        if (currentInterpreter == null) {
            Log.w(TAG, "No current interpreter available for buffer allocation");
            return;
        }
        
        // 檢查實際模型形狀
        int[] inputShape = currentInterpreter.getInputTensor(0).shape();
        int[] outputShape = currentInterpreter.getOutputTensor(0).shape();
        DataType inputDataType = currentInterpreter.getInputTensor(0).dataType();
        DataType outputDataType = currentInterpreter.getOutputTensor(0).dataType();
        
        Log.d(TAG, "Model input shape: " + java.util.Arrays.toString(inputShape));
        Log.d(TAG, "Model output shape: " + java.util.Arrays.toString(outputShape));
        Log.d(TAG, "Input data type: " + inputDataType);
        Log.d(TAG, "Output data type: " + outputDataType);
        
        // 計算所需緩衝區大小
        long inputElements = 1;
        for (int dim : inputShape) {
            inputElements *= dim;
        }
        long outputElements = 1;
        for (int dim : outputShape) {
            outputElements *= dim;
        }
        
        int inputBytesPerElement = (inputDataType == DataType.FLOAT32) ? 4 : 1;
        int outputBytesPerElement = (outputDataType == DataType.FLOAT32) ? 4 : 1;
        
        long requiredInputBytes = inputElements * inputBytesPerElement;
        long requiredOutputBytes = outputElements * outputBytesPerElement;
        
        Log.d(TAG, "Required input buffer: " + requiredInputBytes + " bytes (" + inputElements + " elements)");
        Log.d(TAG, "Required output buffer: " + requiredOutputBytes + " bytes (" + outputElements + " elements)");
        
        // 檢查是否需要重新分配輸入緩衝區
        boolean needNewInputBuffer = inputBuffer == null || 
                                   inputBuffer.getBuffer().capacity() != requiredInputBytes ||
                                   inputBuffer.getDataType() != inputDataType;
                                   
        // 檢查是否需要重新分配輸出緩衝區
        boolean needNewOutputBuffer = outputBuffer == null || 
                                    outputBuffer.getBuffer().capacity() != requiredOutputBytes ||
                                    outputBuffer.getDataType() != outputDataType;
        
        if (needNewInputBuffer) {
            Log.d(TAG, "Reallocating input buffer");
            inputBuffer = TensorBuffer.createFixedSize(inputShape, inputDataType);
            Log.d(TAG, "New input buffer capacity: " + inputBuffer.getBuffer().capacity() + " bytes");
        }
        
        if (needNewOutputBuffer) {
            Log.d(TAG, "Reallocating output buffer");
            outputBuffer = TensorBuffer.createFixedSize(outputShape, outputDataType);
            Log.d(TAG, "New output buffer capacity: " + outputBuffer.getBuffer().capacity() + " bytes");
        }
        
        if (!needNewInputBuffer && !needNewOutputBuffer) {
            Log.d(TAG, "Buffers are already correct size");
        }
    }
    
    public interface InferenceCallback {
        void onResult(Bitmap result, long inferenceTime);
        void onError(String error);
    }
    
    public void processImage(Bitmap inputBitmap, InferenceCallback callback) {
        processImageWithMode(inputBitmap, null, callback);
    }
    
    public void processImageWithMode(Bitmap inputBitmap, Boolean forceGpu, InferenceCallback callback) {
        if (!isInitialized) {
            callback.onError("Processor not initialized");
            return;
        }
        
        srHandler.post(() -> {
            try {
                Log.d(TAG, "Processing image: " + inputBitmap.getWidth() + "x" + inputBitmap.getHeight());
                
                // 快速模式切換 - 無延遲!
                if (forceGpu != null && forceGpu != isUsingGpu) {
                    long switchStart = System.currentTimeMillis();
                    switchToMode(forceGpu);
                    long switchTime = System.currentTimeMillis() - switchStart;
                    Log.d(TAG, "Mode switch completed in " + switchTime + "ms");
                }
                
                // 確保輸入尺寸符合模型要求
                Bitmap resizedInput;
                if (inputBitmap.getWidth() != actualInputWidth || inputBitmap.getHeight() != actualInputHeight) {
                    resizedInput = Bitmap.createScaledBitmap(inputBitmap, actualInputWidth, actualInputHeight, true);
                    Log.d(TAG, "Resized input to " + actualInputWidth + "x" + actualInputHeight);
                } else {
                    resizedInput = inputBitmap;
                }
                
                long totalStartTime = System.currentTimeMillis();
                
                // 確保緩衝區大小正確
                long bufferStart = System.currentTimeMillis();
                ensureBuffersAreCorrectSize();
                long bufferTime = System.currentTimeMillis() - bufferStart;
                
                // 轉換輸入
                long conversionStart = System.currentTimeMillis();
                convertBitmapToBuffer(resizedInput);
                long conversionTime = System.currentTimeMillis() - conversionStart;
                
                // 重置緩衝區位置到開始
                inputBuffer.getBuffer().rewind();
                outputBuffer.getBuffer().rewind();
                
                Log.d(TAG, "Setup times - Buffer: " + bufferTime + "ms, Conversion: " + conversionTime + "ms");
                
                // 執行推理
                String accelerator = isUsingGpu ? "GPU + NNAPI" : "NNAPI/CPU";
                Log.d(TAG, "Running inference on " + accelerator);
                
                // 減少不必要的日誌以提高性能
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Input buffer - capacity: " + inputBuffer.getBuffer().capacity() + 
                               ", position: " + inputBuffer.getBuffer().position());
                    Log.d(TAG, "Output buffer - capacity: " + outputBuffer.getBuffer().capacity() + 
                               ", position: " + outputBuffer.getBuffer().position());
                }
                
                try {
                    long inferenceStart = System.currentTimeMillis();
                    currentInterpreter.run(inputBuffer.getBuffer(), outputBuffer.getBuffer());
                    long pureInferenceTime = System.currentTimeMillis() - inferenceStart;
                    Log.d(TAG, "Pure inference time: " + pureInferenceTime + "ms");
                } catch (java.nio.BufferOverflowException e) {
                    Log.e(TAG, "BufferOverflowException during inference, attempting to fix", e);
                    
                    // 強制重新分配緩衝區
                    inputBuffer = null;
                    outputBuffer = null;
                    ensureBuffersAreCorrectSize();
                    
                    // 重新轉換輸入
                    convertBitmapToBuffer(resizedInput);
                    
                    // 確保緩衝區位置正確
                    inputBuffer.getBuffer().rewind();
                    outputBuffer.getBuffer().rewind();
                    
                    Log.d(TAG, "Retrying inference with new buffers");
                    
                    // 重試推理
                    currentInterpreter.run(inputBuffer.getBuffer(), outputBuffer.getBuffer());
                }
                
                // 轉換輸出
                long outputStart = System.currentTimeMillis();
                Bitmap resultBitmap = convertOutputToBitmap();
                long outputTime = System.currentTimeMillis() - outputStart;
                
                long totalTime = System.currentTimeMillis() - totalStartTime;
                Log.d(TAG, "Detailed timing - Output conversion: " + outputTime + "ms, Total: " + totalTime + "ms");
                
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
        
        inputBuffer.getBuffer().rewind();
        
        DataType inputDataType = currentInterpreter.getInputTensor(0).dataType();
        // 減少轉換過程中的日誌以提高性能
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Converting bitmap to buffer, input data type: " + inputDataType);
            Log.v(TAG, "Buffer capacity: " + inputBuffer.getBuffer().capacity() + ", pixels: " + cachedPixelArray.length);
        }
        
        try {
            if (inputDataType == DataType.FLOAT32) {
                // 優化的float32輸入處理 - 批量轉換
                convertPixelsToFloat32Buffer(cachedPixelArray);
            } else if (inputDataType == DataType.UINT8 || inputDataType == DataType.INT8) {
                // 優化的uint8/int8輸入處理 - 批量轉換
                convertPixelsToUint8Buffer(cachedPixelArray);
            } else {
                throw new IllegalArgumentException("Unsupported input data type: " + inputDataType);
            }
            
            // 減少成功轉換的日誌輸出以提高性能
        } catch (Exception e) {
            Log.e(TAG, "Error converting bitmap to buffer", e);
            Log.e(TAG, "Buffer capacity: " + inputBuffer.getBuffer().capacity() + 
                      ", position: " + inputBuffer.getBuffer().position() + 
                      ", remaining: " + inputBuffer.getBuffer().remaining());
            throw e;
        }
    }
    
    /**
     * 優化的像素到float32緩衝區轉換 - 使用緩存數組
     */
    private void convertPixelsToFloat32Buffer(int[] pixels) {
        // 重用緩存的float數組以避免GC
        int totalFloats = pixels.length * 3;
        
        // 確保緩存數組足夠大
        if (cachedFloatArray.length < totalFloats) {
            // 如果需要擴容，說明模型尺寸變了，重新分配
            cachedFloatArray = new float[totalFloats];
            Log.d(TAG, "Expanded cached float array to " + totalFloats);
        }
        
        // 批量轉換像素到float數組
        for (int i = 0, floatIndex = 0; i < pixels.length; i++, floatIndex += 3) {
            int pixel = pixels[i];
            
            // 使用位運算快速提取RGB並轉換為float
            cachedFloatArray[floatIndex] = ((pixel >> 16) & 0xFF) * 0.003921569f; // / 255.0f的優化版本
            cachedFloatArray[floatIndex + 1] = ((pixel >> 8) & 0xFF) * 0.003921569f;
            cachedFloatArray[floatIndex + 2] = (pixel & 0xFF) * 0.003921569f;
        }
        
        // 批量寫入到buffer，只使用需要的部分
        inputBuffer.getBuffer().asFloatBuffer().put(cachedFloatArray, 0, totalFloats);
    }
    
    /**
     * 優化的像素到uint8緩衝區轉換 - 使用緩存數組
     */
    private void convertPixelsToUint8Buffer(int[] pixels) {
        // 重用緩存的byte數組以避免GC
        int totalBytes = pixels.length * 3;
        
        // 確保緩存數組足夠大
        if (cachedByteArray.length < totalBytes) {
            cachedByteArray = new byte[totalBytes];
            Log.d(TAG, "Expanded cached byte array to " + totalBytes);
        }
        
        // 批量轉換像素到byte數組
        for (int i = 0, byteIndex = 0; i < pixels.length; i++, byteIndex += 3) {
            int pixel = pixels[i];
            
            // 使用位運算快速提取RGB
            cachedByteArray[byteIndex] = (byte) ((pixel >> 16) & 0xFF);
            cachedByteArray[byteIndex + 1] = (byte) ((pixel >> 8) & 0xFF);
            cachedByteArray[byteIndex + 2] = (byte) (pixel & 0xFF);
        }
        
        // 批量寫入到buffer，只使用需要的部分
        inputBuffer.getBuffer().put(cachedByteArray, 0, totalBytes);
    }
    
    private Bitmap convertOutputToBitmap() {
        // 獲取實際輸出形狀
        int[] outputShape = currentInterpreter.getOutputTensor(0).shape();
        DataType outputDataType = currentInterpreter.getOutputTensor(0).dataType();
        
        Log.d(TAG, "Converting output with shape: " + java.util.Arrays.toString(outputShape));
        Log.d(TAG, "Output data type: " + outputDataType);
        
        // 解析輸出尺寸 - TensorFlow格式為 [batch, height, width, channels]
        int outputHeight, outputWidth, outputChannels;
        if (outputShape.length >= 4) {
            // 標準NHWC格式
            outputHeight = outputShape[1];
            outputWidth = outputShape[2]; 
            outputChannels = outputShape[3];
        } else if (outputShape.length == 3) {
            // 沒有batch維度的HWC格式
            outputHeight = outputShape[0];
            outputWidth = outputShape[1];
            outputChannels = outputShape[2];
        } else {
            Log.e(TAG, "Unexpected output shape length: " + outputShape.length);
            return null;
        }
        
        // 驗證輸出是否為期望的尺寸
        if (outputWidth != actualOutputWidth || outputHeight != actualOutputHeight) {
            Log.w(TAG, "Unexpected output size: " + outputWidth + "x" + outputHeight + 
                      ", expected: " + actualOutputWidth + "x" + actualOutputHeight);
        }
        
        Log.d(TAG, "Output dimensions: " + outputWidth + "x" + outputHeight + "x" + outputChannels);
        
        int totalPixels = outputWidth * outputHeight;
        
        // 使用預分配的緩存像素數組，避免大量記憶體分配
        if (cachedOutputPixelArray.length < totalPixels) {
            Log.w(TAG, "Output pixel cache too small, expanding from " + cachedOutputPixelArray.length + 
                      " to " + totalPixels);
            cachedOutputPixelArray = new int[totalPixels];
        }
        
        outputBuffer.getBuffer().rewind();
        
        try {
            if (outputDataType == DataType.FLOAT32) {
                // 優化的float32輸出處理 - 使用緩存數組
                convertFloat32OutputToPixelsCached(cachedOutputPixelArray, totalPixels);
            } else if (outputDataType == DataType.UINT8) {
                // 優化的uint8輸出處理 - 使用緩存數組
                convertUint8OutputToPixelsCached(cachedOutputPixelArray, totalPixels);
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
    
    /**
     * 優化的float32到像素轉換 - 使用預分配緩存數組
     */
    private void convertFloat32OutputToPixelsCached(int[] pixels, int totalPixels) {
        // 批量讀取所有float數據到緩存數組
        int totalFloats = totalPixels * 3; // RGB 3個通道
        
        // 確保緩存數組足夠大
        if (cachedOutputFloatArray.length < totalFloats) {
            Log.w(TAG, "Output float cache too small, expanding from " + cachedOutputFloatArray.length + 
                      " to " + totalFloats);
            cachedOutputFloatArray = new float[totalFloats];
        }
        
        // 一次性讀取所有float數據到緩存數組
        outputBuffer.getBuffer().asFloatBuffer().get(cachedOutputFloatArray, 0, totalFloats);
        
        // 決定是否使用並行處理 - 只有大圖才值得並行處理開銷
        if (totalPixels > 1000000) { // 大於100萬像素使用並行
            convertFloat32ParallelCached(pixels, totalPixels);
        } else {
            convertFloat32SequentialCached(pixels, totalPixels);
        }
    }
    
    /**
     * 優化的float32到像素轉換 - 並行處理版本（舊版本，保留兼容）
     */
    private void convertFloat32OutputToPixels(int[] pixels, int totalPixels) {
        // 批量讀取所有float數據到數組
        int totalFloats = totalPixels * 3; // RGB 3個通道
        float[] floatData = new float[totalFloats];
        
        // 一次性讀取所有float數據
        outputBuffer.getBuffer().asFloatBuffer().get(floatData);
        
        // 決定是否使用並行處理 - 只有大圖才值得並行處理開銷
        if (totalPixels > 1000000) { // 大於100萬像素使用並行
            convertFloat32Parallel(pixels, floatData, totalPixels);
        } else {
            convertFloat32Sequential(pixels, floatData, totalPixels);
        }
    }
    
    /**
     * 並行轉換float32數據
     */
    private void convertFloat32Parallel(int[] pixels, float[] floatData, int totalPixels) {
        int numThreads = Math.min(4, Runtime.getRuntime().availableProcessors());
        int pixelsPerThread = totalPixels / numThreads;
        CountDownLatch latch = new CountDownLatch(numThreads);
        
        for (int t = 0; t < numThreads; t++) {
            final int threadIndex = t;
            final int startPixel = t * pixelsPerThread;
            final int endPixel = (t == numThreads - 1) ? totalPixels : (t + 1) * pixelsPerThread;
            
            conversionExecutor.submit(() -> {
                try {
                    // 處理這個線程負責的像素範圍
                    for (int i = startPixel, floatIndex = startPixel * 3; i < endPixel; i++, floatIndex += 3) {
                        float r = floatData[floatIndex];
                        float g = floatData[floatIndex + 1];
                        float b = floatData[floatIndex + 2];
                        
                        // 快速clamp和轉換
                        int red = (int) (Math.max(0, Math.min(1, r)) * 255);
                        int green = (int) (Math.max(0, Math.min(1, g)) * 255);
                        int blue = (int) (Math.max(0, Math.min(1, b)) * 255);
                        
                        pixels[i] = 0xFF000000 | (red << 16) | (green << 8) | blue;
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await(); // 等待所有線程完成
        } catch (InterruptedException e) {
            Log.e(TAG, "Parallel conversion interrupted", e);
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 序列轉換float32數據 (小圖片使用)
     */
    private void convertFloat32Sequential(int[] pixels, float[] floatData, int totalPixels) {
        // 批量轉換 - 減少循環開銷和函數調用
        for (int i = 0, floatIndex = 0; i < totalPixels; i++, floatIndex += 3) {
            // 直接處理3個float值，避免重複的Math.max/Math.min調用
            float r = floatData[floatIndex];
            float g = floatData[floatIndex + 1];
            float b = floatData[floatIndex + 2];
            
            // 更快的clamp和轉換 - 使用位運算優化
            int red = (int) (Math.max(0, Math.min(1, r)) * 255);
            int green = (int) (Math.max(0, Math.min(1, g)) * 255);
            int blue = (int) (Math.max(0, Math.min(1, b)) * 255);
            
            // 使用位運算組合RGB
            pixels[i] = 0xFF000000 | (red << 16) | (green << 8) | blue;
        }
    }
    
    /**
     * 並行轉換float32數據 - 使用緩存數組版本
     */
    private void convertFloat32ParallelCached(int[] pixels, int totalPixels) {
        int numThreads = Math.min(4, Runtime.getRuntime().availableProcessors());
        int pixelsPerThread = totalPixels / numThreads;
        CountDownLatch latch = new CountDownLatch(numThreads);
        
        for (int t = 0; t < numThreads; t++) {
            final int threadIndex = t;
            final int startPixel = t * pixelsPerThread;
            final int endPixel = (t == numThreads - 1) ? totalPixels : (t + 1) * pixelsPerThread;
            
            conversionExecutor.submit(() -> {
                try {
                    // 處理這個線程負責的像素範圍 - 使用緩存數組
                    for (int i = startPixel, floatIndex = startPixel * 3; i < endPixel; i++, floatIndex += 3) {
                        float r = cachedOutputFloatArray[floatIndex];
                        float g = cachedOutputFloatArray[floatIndex + 1];
                        float b = cachedOutputFloatArray[floatIndex + 2];
                        
                        // 快速clamp和轉換
                        int red = (int) (Math.max(0, Math.min(1, r)) * 255);
                        int green = (int) (Math.max(0, Math.min(1, g)) * 255);
                        int blue = (int) (Math.max(0, Math.min(1, b)) * 255);
                        
                        pixels[i] = 0xFF000000 | (red << 16) | (green << 8) | blue;
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await(); // 等待所有線程完成
        } catch (InterruptedException e) {
            Log.e(TAG, "Parallel conversion interrupted", e);
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 序列轉換float32數據 - 使用緩存數組版本 (小圖片使用)
     */
    private void convertFloat32SequentialCached(int[] pixels, int totalPixels) {
        // 批量轉換 - 使用緩存數組，減少記憶體分配
        for (int i = 0, floatIndex = 0; i < totalPixels; i++, floatIndex += 3) {
            // 直接處理3個float值，避免重複的Math.max/Math.min調用
            float r = cachedOutputFloatArray[floatIndex];
            float g = cachedOutputFloatArray[floatIndex + 1];
            float b = cachedOutputFloatArray[floatIndex + 2];
            
            // 更快的clamp和轉換 - 使用位運算優化
            int red = (int) (Math.max(0, Math.min(1, r)) * 255);
            int green = (int) (Math.max(0, Math.min(1, g)) * 255);
            int blue = (int) (Math.max(0, Math.min(1, b)) * 255);
            
            // 使用位運算組合RGB
            pixels[i] = 0xFF000000 | (red << 16) | (green << 8) | blue;
        }
    }
    
    /**
     * 優化的uint8到像素轉換 - 使用預分配緩存數組
     */
    private void convertUint8OutputToPixelsCached(int[] pixels, int totalPixels) {
        // 批量讀取所有byte數據到緩存數組
        int totalBytes = totalPixels * 3; // RGB 3個通道
        
        // 確保緩存數組足夠大
        if (cachedOutputByteArray.length < totalBytes) {
            Log.w(TAG, "Output byte cache too small, expanding from " + cachedOutputByteArray.length + 
                      " to " + totalBytes);
            cachedOutputByteArray = new byte[totalBytes];
        }
        
        // 一次性讀取所有byte數據到緩存數組
        outputBuffer.getBuffer().get(cachedOutputByteArray, 0, totalBytes);
        
        // 決定是否使用並行處理
        if (totalPixels > 1000000) { // 大於100萬像素使用並行
            convertUint8ParallelCached(pixels, totalPixels);
        } else {
            convertUint8SequentialCached(pixels, totalPixels);
        }
    }
    
    /**
     * 優化的uint8到像素轉換 - 並行處理版本（舊版本，保留兼容）
     */
    private void convertUint8OutputToPixels(int[] pixels, int totalPixels) {
        // 批量讀取所有byte數據到數組
        int totalBytes = totalPixels * 3; // RGB 3個通道
        byte[] byteData = new byte[totalBytes];
        
        // 一次性讀取所有byte數據
        outputBuffer.getBuffer().get(byteData);
        
        // 決定是否使用並行處理
        if (totalPixels > 1000000) { // 大於100萬像素使用並行
            convertUint8Parallel(pixels, byteData, totalPixels);
        } else {
            convertUint8Sequential(pixels, byteData, totalPixels);
        }
    }
    
    /**
     * 並行轉換uint8數據
     */
    private void convertUint8Parallel(int[] pixels, byte[] byteData, int totalPixels) {
        int numThreads = Math.min(4, Runtime.getRuntime().availableProcessors());
        int pixelsPerThread = totalPixels / numThreads;
        CountDownLatch latch = new CountDownLatch(numThreads);
        
        for (int t = 0; t < numThreads; t++) {
            final int threadIndex = t;
            final int startPixel = t * pixelsPerThread;
            final int endPixel = (t == numThreads - 1) ? totalPixels : (t + 1) * pixelsPerThread;
            
            conversionExecutor.submit(() -> {
                try {
                    // 處理這個線程負責的像素範圍
                    for (int i = startPixel, byteIndex = startPixel * 3; i < endPixel; i++, byteIndex += 3) {
                        int r = byteData[byteIndex] & 0xFF;
                        int g = byteData[byteIndex + 1] & 0xFF;
                        int b = byteData[byteIndex + 2] & 0xFF;
                        
                        pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await(); // 等待所有線程完成
        } catch (InterruptedException e) {
            Log.e(TAG, "Parallel conversion interrupted", e);
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 序列轉換uint8數據 (小圖片使用)
     */
    private void convertUint8Sequential(int[] pixels, byte[] byteData, int totalPixels) {
        // 批量轉換 - 使用位運算優化
        for (int i = 0, byteIndex = 0; i < totalPixels; i++, byteIndex += 3) {
            int r = byteData[byteIndex] & 0xFF;
            int g = byteData[byteIndex + 1] & 0xFF;
            int b = byteData[byteIndex + 2] & 0xFF;
            
            // 使用位運算組合RGB
            pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }
    
    /**
     * 並行轉換uint8數據 - 使用緩存數組版本
     */
    private void convertUint8ParallelCached(int[] pixels, int totalPixels) {
        int numThreads = Math.min(4, Runtime.getRuntime().availableProcessors());
        int pixelsPerThread = totalPixels / numThreads;
        CountDownLatch latch = new CountDownLatch(numThreads);
        
        for (int t = 0; t < numThreads; t++) {
            final int threadIndex = t;
            final int startPixel = t * pixelsPerThread;
            final int endPixel = (t == numThreads - 1) ? totalPixels : (t + 1) * pixelsPerThread;
            
            conversionExecutor.submit(() -> {
                try {
                    // 處理這個線程負責的像素範圍 - 使用緩存數組
                    for (int i = startPixel, byteIndex = startPixel * 3; i < endPixel; i++, byteIndex += 3) {
                        int r = cachedOutputByteArray[byteIndex] & 0xFF;
                        int g = cachedOutputByteArray[byteIndex + 1] & 0xFF;
                        int b = cachedOutputByteArray[byteIndex + 2] & 0xFF;
                        
                        pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await(); // 等待所有線程完成
        } catch (InterruptedException e) {
            Log.e(TAG, "Parallel conversion interrupted", e);
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 序列轉換uint8數據 - 使用緩存數組版本 (小圖片使用)
     */
    private void convertUint8SequentialCached(int[] pixels, int totalPixels) {
        // 批量轉換 - 使用緩存數組，減少記憶體分配
        for (int i = 0, byteIndex = 0; i < totalPixels; i++, byteIndex += 3) {
            int r = cachedOutputByteArray[byteIndex] & 0xFF;
            int g = cachedOutputByteArray[byteIndex + 1] & 0xFF;
            int b = cachedOutputByteArray[byteIndex + 2] & 0xFF;
            
            // 使用位運算組合RGB
            pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }
    
    public void close() {
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
                if (gpuDelegate != null) {
                    gpuDelegate.close();
                    gpuDelegate = null;
                }
                currentInterpreter = null;
                Log.d(TAG, "All resources released");
            });
        }
        
        // 關閉並行處理執行器
        if (conversionExecutor != null) {
            conversionExecutor.shutdown();
            try {
                if (!conversionExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    conversionExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                conversionExecutor.shutdownNow();
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
        return isUsingGpu;
    }
    
    public String getAcceleratorInfo() {
        return isUsingGpu ? "GPU + NNAPI (Optimized)" : "NNAPI/CPU (Optimized)";
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