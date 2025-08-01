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
        Log.d(TAG, "Initialized conversion executor with " + Math.min(cores, Constants.MAX_CONVERSION_THREADS) + " threads");
        
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
                Log.d(TAG, "Initializing SR processor with tri-mode setup (GPU/CPU/NPU)");
                long initStartTime = System.currentTimeMillis();
                
                String modelPath = configManager.getDefaultModelPath();
                ByteBuffer tfliteModel = FileUtil.loadMappedFile(context, modelPath);
                Log.d(TAG, "Model loaded: " + modelPath + " (" + (tfliteModel.capacity() / 1024) + "KB)");
                
                // 初始化GPU解釋器
                boolean gpuSuccess = initializeGpuInterpreter(tfliteModel);
                
                // 初始化CPU解釋器  
                boolean cpuSuccess = initializeCpuInterpreter(tfliteModel);
                
                // 初始化NPU解釋器
                boolean npuSuccess = initializeNpuInterpreter(tfliteModel);
                
                if (!gpuSuccess && !cpuSuccess && !npuSuccess) {
                    throw new RuntimeException("Failed to initialize all interpreters (GPU, CPU, NPU)");
                }
                
                // 默認優先級: NPU -> GPU -> CPU
                if (npuSuccess && configManager.isEnableNpu()) {
                    currentInterpreter = npuInterpreter;
                    currentMode = ProcessingMode.NPU;
                    Log.d(TAG, "Default mode: NPU");
                } else if (gpuSuccess) {
                    currentInterpreter = gpuInterpreter;
                    currentMode = ProcessingMode.GPU;
                    Log.d(TAG, "Default mode: GPU + NNAPI");
                } else {
                    currentInterpreter = cpuInterpreter;
                    currentMode = ProcessingMode.CPU;
                    Log.d(TAG, "Default mode: NNAPI/CPU");
                }
                
                allocateBuffers();
                allocateCachedArrays();
                
                isInitialized = true;
                
                long initTime = System.currentTimeMillis() - initStartTime;
                String message = String.format("Initialized tri-mode in %dms. GPU: %s, CPU: %s, NPU: %s", 
                    initTime, gpuSuccess ? "✓" : "✗", cpuSuccess ? "✓" : "✗", npuSuccess ? "✓" : "✗");
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
            Log.d(TAG, "Initializing GPU interpreter");
            Interpreter.Options gpuOptions = new Interpreter.Options();
//            if (configManager.isUseNnapi()) {
//                gpuOptions.setUseNNAPI(true);
//                Log.d(TAG, "NNAPI enabled for GPU interpreter");
//            }
            
            if (trySetupGpu(gpuOptions)) {
                try {
                    gpuInterpreter = new Interpreter(tfliteModel, gpuOptions);
                    Log.d(TAG, "GPU interpreter created successfully");
                    
                    readModelDimensions(gpuInterpreter);
                    return true;
                } catch (Exception e) {
                    Log.e(TAG, "Failed to create GPU interpreter: " + e.getMessage());
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
            Log.d(TAG, "Initializing CPU interpreter");
            Interpreter.Options cpuOptions = new Interpreter.Options();
            if (configManager.isUseNnapi()) {
                cpuOptions.setUseNNAPI(true);
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
            Log.d(TAG, "Initializing NPU interpreter");
            Interpreter.Options npuOptions = new Interpreter.Options();
            
            if (trySetupNpu(npuOptions)) {
                try {
                    npuInterpreter = new Interpreter(tfliteModel, npuOptions);
                    
                    if (actualInputWidth == 0) {
                        readModelDimensions(npuInterpreter);
                    }
                    return true;
                } catch (Exception e) {
                    Log.e(TAG, "Failed to create NPU interpreter: " + e.getMessage());
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
            Log.d(TAG, "NPU delegate configured");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "NPU setup failed: " + e.getMessage(), e);
        }
        
        return false;
    }
    
    private void configureNpuDelegateOptions(NnApiDelegate.Options npuOptions) {
        String acceleratorName = configManager.getNpuAcceleratorName();
        if (!acceleratorName.isEmpty()) {
            npuOptions.setAcceleratorName(acceleratorName);
        }
        
        boolean allowFp16 = configManager.isAllowFp16OnNpu();
        npuOptions.setAllowFp16(allowFp16);
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
            
            Log.d(TAG, "Model: " + actualInputWidth + "x" + actualInputHeight + 
                      " -> " + actualOutputWidth + "x" + actualOutputHeight);
                      
        } catch (Exception e) {
            Log.e(TAG, "Failed to read model dimensions", e);
            throw new RuntimeException("Failed to read model dimensions: " + e.getMessage());
        }
    }

    private boolean trySetupGpu(Interpreter.Options options) {
        try {
            GpuDelegate.Options gpuOptions = new GpuDelegate.Options();
            configureGpuDelegateOptions(gpuOptions);
            
            if (android.os.Build.SOC_MODEL != null && android.os.Build.SOC_MODEL.contains("MT8195")) {
                applyMaliOptimizations(gpuOptions);
            }
            
            gpuDelegate = new GpuDelegate(gpuOptions);
            options.addDelegate(gpuDelegate);
            Log.d(TAG, "GPU delegate configured");
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
    
    private void applyMaliOptimizations(GpuDelegate.Options gpuOptions) {
        // Mali-G57 optimizations (placeholder for future enhancements)
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
            
            Log.d(TAG, "Cached arrays allocated - Input: " + inputPixels + ", Output: " + outputPixels + " pixels");
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
                    Log.d(TAG, "Switched to GPU mode (instant)");
                } else {
                    Log.w(TAG, "GPU interpreter not available, keeping current mode");
                }
                break;
            case CPU:
                if (cpuInterpreter != null) {
                    currentInterpreter = cpuInterpreter;
                    currentMode = ProcessingMode.CPU;
                    Log.d(TAG, "Switched to CPU mode (instant)");
                } else {
                    Log.w(TAG, "CPU interpreter not available, keeping current mode");
                }
                break;
            case NPU:
                if (npuInterpreter != null) {
                    currentInterpreter = npuInterpreter;
                    currentMode = ProcessingMode.NPU;
                    Log.d(TAG, "Switched to NPU mode (instant)");
                } else {
                    Log.w(TAG, "NPU interpreter not available, keeping current mode");
                }
                break;
            default:
                Log.w(TAG, "Unknown processing mode, keeping current mode");
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
    
    public void processImageWithGpu(Bitmap inputBitmap, InferenceCallback callback) {
        processImageWithMode(inputBitmap, ProcessingMode.GPU, callback);
    }
    
    public void processImageWithCpu(Bitmap inputBitmap, InferenceCallback callback) {
        processImageWithMode(inputBitmap, ProcessingMode.CPU, callback);
    }
    
    public void processImageWithNpu(Bitmap inputBitmap, InferenceCallback callback) {
        processImageWithMode(inputBitmap, ProcessingMode.NPU, callback);
    }
    
    public void processImageWithMode(Bitmap inputBitmap, ProcessingMode forceMode, InferenceCallback callback) {
        if (!isInitialized) {
            callback.onError("Processor not initialized");
            return;
        }
        
        srHandler.post(() -> {
            try {
                Log.d(TAG, "Processing image: " + inputBitmap.getWidth() + "x" + inputBitmap.getHeight());
                
                // 快速模式切換 - 無延遲!
                if (forceMode != null && forceMode != currentMode) {
                    long switchStart = System.currentTimeMillis();
                    switchToMode(forceMode);
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
                
                ensureBuffersAreCorrectSize();
                convertBitmapToBuffer(resizedInput);
                inputBuffer.getBuffer().rewind();
                outputBuffer.getBuffer().rewind();
                
                // Execute inference
                String accelerator = getAcceleratorInfo();
                Log.d(TAG, "Running inference on " + accelerator);
                
                try {
                    long inferenceStart = System.currentTimeMillis();
                    
                    // NPU性能診斷
                    if (currentMode == ProcessingMode.NPU) {
                        String modelPath = configManager.getDefaultModelPath();
                        boolean isFloat16Model = modelPath.contains("float16");
                        boolean allowFp16 = configManager.isAllowFp16OnNpu();
                        
                        Log.d(TAG, "=== NPU Inference Diagnostics ===");
                        Log.d(TAG, "Model: " + modelPath + " (" + (isFloat16Model ? "Float16" : "Float32") + ")");
                        Log.d(TAG, "NPU allow_fp16: " + allowFp16);
                        Log.d(TAG, "Expected behavior: " + 
                              (isFloat16Model && allowFp16 ? "Native Float16 (or upconvert to Float32)" : 
                               isFloat16Model && !allowFp16 ? "Force Float32 processing" :
                               "Native Float32"));
                    }
                    
                    currentInterpreter.run(inputBuffer.getBuffer(), outputBuffer.getBuffer());
                    long pureInferenceTime = System.currentTimeMillis() - inferenceStart;
                    
                    // NPU性能分析
                    if (currentMode == ProcessingMode.NPU) {
                        Log.d(TAG, "NPU Pure inference time: " + pureInferenceTime + "ms");
                        if (HardwareInfo.isMT8195()) {
                            Log.d(TAG, "MediaTek MT8195 NPU Performance Analysis:");
                            Log.d(TAG, "  - Inference time: " + pureInferenceTime + "ms");
                            Log.d(TAG, "  - If Float16/Float32 times are identical, NNAPI may be upconverting");
                        }
                    } else {
                        Log.d(TAG, "Pure inference time: " + pureInferenceTime + "ms");
                    }
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
        
        outputBuffer.getBuffer().rewind();
        
        
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
        
        // Read all byte data to cached array
        outputBuffer.getBuffer().get(cachedOutputByteArray, 0, totalBytes);
        
        // Convert INT8 to pixels (INT8 range is -128 to 127, convert to 0-255)
        if (totalPixels > Constants.LARGE_IMAGE_PIXEL_THRESHOLD) {
            convertInt8ToPixelsParallel(cachedOutputByteArray, pixels);
        } else {
            convertInt8ToPixelsSequential(cachedOutputByteArray, pixels, totalPixels);
        }
    }
    
    private void convertInt8ToPixelsSequential(byte[] byteArray, int[] pixels, int totalPixels) {
        for (int i = 0, byteIndex = 0; i < totalPixels; i++, byteIndex += 3) {
            // Convert INT8 [-128, 127] to [0, 255]
            int r = (byteArray[byteIndex] + 128) & 0xFF;
            int g = (byteArray[byteIndex + 1] + 128) & 0xFF;
            int b = (byteArray[byteIndex + 2] + 128) & 0xFF;
            
            pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }
    
    private void convertInt8ToPixelsParallel(byte[] byteArray, int[] pixels) {
        int totalPixels = pixels.length;
        int numThreads = Math.min(Constants.MAX_CONVERSION_THREADS, 
                                Runtime.getRuntime().availableProcessors());
        int pixelsPerThread = totalPixels / numThreads;
        CountDownLatch latch = new CountDownLatch(numThreads);
        
        for (int t = 0; t < numThreads; t++) {
            final int threadIndex = t;
            final int startPixel = t * pixelsPerThread;
            final int endPixel = (t == numThreads - 1) ? totalPixels : (t + 1) * pixelsPerThread;
            
            conversionExecutor.submit(() -> {
                try {
                    for (int i = startPixel, byteIndex = startPixel * 3; i < endPixel; i++, byteIndex += 3) {
                        // Convert INT8 [-128, 127] to [0, 255]
                        int r = (byteArray[byteIndex] + 128) & 0xFF;
                        int g = (byteArray[byteIndex + 1] + 128) & 0xFF;
                        int b = (byteArray[byteIndex + 2] + 128) & 0xFF;
                        
                        pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await();
        } catch (InterruptedException e) {
            Log.e(TAG, "Parallel INT8 conversion interrupted", e);
            Thread.currentThread().interrupt();
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
                Log.d(TAG, "All resources released");
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
                return "NPU (Neural Processing Unit)";
            case CPU:
            default:
                return "NNAPI/CPU (Optimized)";
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