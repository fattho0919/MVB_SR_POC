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
                
                allocateBuffers();
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
                    gpuInterpreter = new Interpreter(tfliteModel, gpuOptions);
                    Log.d(TAG, "GPU interpreter created successfully");
                    
                    // 檢查模型輸入輸出格式
                    int[] inputShape = gpuInterpreter.getInputTensor(0).shape();
                    int[] outputShape = gpuInterpreter.getOutputTensor(0).shape();
                    DataType inputDataType = gpuInterpreter.getInputTensor(0).dataType();
                    DataType outputDataType = gpuInterpreter.getOutputTensor(0).dataType();
                    
                    Log.d(TAG, "GPU Model input shape: " + java.util.Arrays.toString(inputShape));
                    Log.d(TAG, "GPU Model output shape: " + java.util.Arrays.toString(outputShape));
                    Log.d(TAG, "GPU Input data type: " + inputDataType);
                    Log.d(TAG, "GPU Output data type: " + outputDataType);
                    
                    // 讀取並驗證模型尺寸
                    readModelDimensions(gpuInterpreter);
                    
                    // 特別檢查 float16 模型在 Mali GPU 上的兼容性
                    String modelPath = configManager.getDefaultModelPath();
                    if (modelPath.contains("float16")) {
                        Log.d(TAG, "Float16 model detected - checking Mali GPU compatibility");
                        Log.d(TAG, "Mali-G57 should support float16, continuing...");
                    }
                    
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
            Interpreter.Options cpuOptions = new Interpreter.Options();
            if (configManager.isUseNnapi()) {
                cpuOptions.setUseNNAPI(true);
            }
            setupCpu(cpuOptions);
            
            cpuInterpreter = new Interpreter(tfliteModel, cpuOptions);
            Log.d(TAG, "CPU interpreter created successfully");
            
            // 如果GPU初始化失敗，從CPU interpreter讀取尺寸
            if (actualInputWidth == 0) {
                readModelDimensions(cpuInterpreter);
            }
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create CPU interpreter: " + e.getMessage());
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
            Log.d(TAG, "=== GPU Setup ===");
            Log.d(TAG, "Device: " + android.os.Build.MODEL + " (SoC: " + android.os.Build.SOC_MODEL + ")");
            
            // 對於 MediaTek MT8195，直接創建 GPU delegate（跳過誤報的兼容性檢查）
            if (android.os.Build.SOC_MODEL != null && android.os.Build.SOC_MODEL.contains("MT8195")) {
                Log.d(TAG, "MediaTek MT8195 detected - using optimized GPU setup");
                
                GpuDelegate.Options gpuOptions = new GpuDelegate.Options();
                gpuOptions.setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER);
                gpuOptions.setPrecisionLossAllowed(true);
                
                Log.d(TAG, "Creating GPU delegate for Mali-G57...");
                gpuDelegate = new GpuDelegate(gpuOptions);
                options.addDelegate(gpuDelegate);
                Log.d(TAG, "GPU delegate configured successfully");
                return true;
            }
            
            // 對於其他設備，還是使用標準檢查流程
            CompatibilityList compatList = new CompatibilityList();
            boolean isSupported = compatList.isDelegateSupportedOnThisDevice();
            Log.d(TAG, "GPU delegate supported: " + isSupported);
            
            if (isSupported) {
                GpuDelegate.Options gpuOptions = new GpuDelegate.Options();
                gpuOptions.setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER);
                gpuOptions.setPrecisionLossAllowed(true);
                
                gpuDelegate = new GpuDelegate(gpuOptions);
                options.addDelegate(gpuDelegate);
                Log.d(TAG, "GPU delegate configured");
                return true;
            } else {
                Log.w(TAG, "GPU delegate not supported - trying fallback");
                // 嘗試強制創建
                GpuDelegate.Options gpuOptions = new GpuDelegate.Options();
                gpuOptions.setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER);
                gpuOptions.setPrecisionLossAllowed(true);
                
                gpuDelegate = new GpuDelegate(gpuOptions);
                options.addDelegate(gpuDelegate);
                Log.d(TAG, "Fallback GPU delegate created");
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "GPU setup failed: " + e.getMessage(), e);
        }
        
        return false;
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
        int[] pixels = new int[actualInputWidth * actualInputHeight];
        bitmap.getPixels(pixels, 0, actualInputWidth, 0, 0, actualInputWidth, actualInputHeight);
        
        inputBuffer.getBuffer().rewind();
        
        DataType inputDataType = currentInterpreter.getInputTensor(0).dataType();
        // 減少轉換過程中的日誌以提高性能
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Converting bitmap to buffer, input data type: " + inputDataType);
            Log.v(TAG, "Buffer capacity: " + inputBuffer.getBuffer().capacity() + ", pixels: " + pixels.length);
        }
        
        try {
            if (inputDataType == DataType.FLOAT32) {
                // 優化的float32輸入處理 - 批量轉換
                convertPixelsToFloat32Buffer(pixels);
            } else if (inputDataType == DataType.UINT8 || inputDataType == DataType.INT8) {
                // 優化的uint8/int8輸入處理 - 批量轉換
                convertPixelsToUint8Buffer(pixels);
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
     * 優化的像素到float32緩衝區轉換
     */
    private void convertPixelsToFloat32Buffer(int[] pixels) {
        // 預分配float數組用於批量寫入
        int totalFloats = pixels.length * 3;
        float[] floatData = new float[totalFloats];
        
        // 批量轉換像素到float數組
        for (int i = 0, floatIndex = 0; i < pixels.length; i++, floatIndex += 3) {
            int pixel = pixels[i];
            
            // 使用位運算快速提取RGB並轉換為float
            floatData[floatIndex] = ((pixel >> 16) & 0xFF) * 0.003921569f; // / 255.0f的優化版本
            floatData[floatIndex + 1] = ((pixel >> 8) & 0xFF) * 0.003921569f;
            floatData[floatIndex + 2] = (pixel & 0xFF) * 0.003921569f;
        }
        
        // 批量寫入到buffer
        inputBuffer.getBuffer().asFloatBuffer().put(floatData);
    }
    
    /**
     * 優化的像素到uint8緩衝區轉換
     */
    private void convertPixelsToUint8Buffer(int[] pixels) {
        // 預分配byte數組用於批量寫入
        int totalBytes = pixels.length * 3;
        byte[] byteData = new byte[totalBytes];
        
        // 批量轉換像素到byte數組
        for (int i = 0, byteIndex = 0; i < pixels.length; i++, byteIndex += 3) {
            int pixel = pixels[i];
            
            // 使用位運算快速提取RGB
            byteData[byteIndex] = (byte) ((pixel >> 16) & 0xFF);
            byteData[byteIndex + 1] = (byte) ((pixel >> 8) & 0xFF);
            byteData[byteIndex + 2] = (byte) (pixel & 0xFF);
        }
        
        // 批量寫入到buffer
        inputBuffer.getBuffer().put(byteData);
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
        
        int[] pixels = new int[outputWidth * outputHeight];
        outputBuffer.getBuffer().rewind();
        
        try {
            if (outputDataType == DataType.FLOAT32) {
                // 優化的float32輸出處理 - 批量處理
                convertFloat32OutputToPixels(pixels, outputWidth * outputHeight);
            } else if (outputDataType == DataType.UINT8) {
                // 優化的uint8輸出處理 - 批量處理
                convertUint8OutputToPixels(pixels, outputWidth * outputHeight);
            } else {
                Log.e(TAG, "Unsupported output data type: " + outputDataType);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error converting output buffer to bitmap", e);
            return null;
        }
        
        return Bitmap.createBitmap(pixels, outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
    }
    
    /**
     * 優化的float32到像素轉換 - 並行處理版本
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
     * 優化的uint8到像素轉換 - 並行處理版本
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