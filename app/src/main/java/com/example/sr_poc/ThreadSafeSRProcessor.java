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
import java.util.List;
import java.util.ArrayList;
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
                Log.d(TAG, "Initializing SR processor");
                long initStartTime = System.currentTimeMillis();
                
                String modelPath = configManager.getDefaultModelPath();
                ByteBuffer tfliteModel = FileUtil.loadMappedFile(context, modelPath);
                Log.d(TAG, "Model loaded: " + modelPath + " (" + (tfliteModel.capacity() / 1024) + "KB)");
                
                // 初始化GPU解釋器
                boolean gpuSuccess = initializeGpuInterpreter(tfliteModel);
                // boolean gpuSuccess = false;
                
                // 初始化CPU解釋器  
                boolean cpuSuccess = initializeCpuInterpreter(tfliteModel);
                // boolean cpuSuccess = false;
                
                // 初始化NPU解釋器
                boolean npuSuccess = initializeNpuInterpreter(tfliteModel);
                // boolean npuSuccess = false;

                
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
                    Log.d(TAG, "Default mode: GPU");
                } else {
                    currentInterpreter = cpuInterpreter;
                    currentMode = ProcessingMode.CPU;
                    Log.d(TAG, "Default mode: CPU");
                }
                
                ensureBuffersAreCorrectSize();
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
        int outputBytesPerElement = (inputDataType == DataType.FLOAT32) ? 4 : 1;
        
        long requiredInputBytes = inputElements * inputBytesPerElement;
        long requiredOutputBytes = outputElements * outputBytesPerElement;
        
        Log.d(TAG, "Required input buffer: " + requiredInputBytes + " bytes (" + inputElements + " elements)");
        Log.d(TAG, "Required output buffer: " + requiredOutputBytes + " bytes (" + outputElements + " elements)");
        
        // 檢查是否需要重新分配輸入緩衝區
        boolean needNewInputBuffer;
        if (inputDataType == DataType.INT8) {
            needNewInputBuffer = inputByteBuffer == null || inputByteBuffer.capacity() != requiredInputBytes;
        } else {
            needNewInputBuffer = inputBuffer == null || 
                               inputBuffer.getBuffer().capacity() != requiredInputBytes ||
                               inputBuffer.getDataType() != inputDataType;
        }
                                   
        // 檢查是否需要重新分配輸出緩衝區
        boolean needNewOutputBuffer;
        if (outputDataType == DataType.INT8) {
            needNewOutputBuffer = outputByteBuffer == null || outputByteBuffer.capacity() != requiredOutputBytes;
        } else {
            needNewOutputBuffer = outputBuffer == null || 
                                outputBuffer.getBuffer().capacity() != requiredOutputBytes ||
                                outputBuffer.getDataType() != outputDataType;
        }
        
        if (needNewInputBuffer) {
            Log.d(TAG, "Reallocating input buffer");
            if (inputDataType == DataType.INT8) {
                // Use direct ByteBuffer for INT8 since TensorBuffer doesn't support it
                inputByteBuffer = ByteBuffer.allocateDirect((int) requiredInputBytes);
                inputByteBuffer.order(java.nio.ByteOrder.nativeOrder());
                inputBuffer = null; // Clear TensorBuffer
                Log.d(TAG, "New input ByteBuffer capacity: " + inputByteBuffer.capacity() + " bytes");
            } else {
                inputBuffer = TensorBuffer.createFixedSize(inputShape, inputDataType);
                inputByteBuffer = null; // Clear direct buffer
                Log.d(TAG, "New input TensorBuffer capacity: " + inputBuffer.getBuffer().capacity() + " bytes");
            }
        }
        
        if (needNewOutputBuffer) {
            Log.d(TAG, "Reallocating output buffer");
            if (outputDataType == DataType.INT8) {
                // Use direct ByteBuffer for INT8 since TensorBuffer doesn't support it
                outputByteBuffer = ByteBuffer.allocateDirect((int) requiredOutputBytes);
                outputByteBuffer.order(java.nio.ByteOrder.nativeOrder());
                outputBuffer = null; // Clear TensorBuffer
                Log.d(TAG, "New output ByteBuffer capacity: " + outputByteBuffer.capacity() + " bytes");
            } else {
                outputBuffer = TensorBuffer.createFixedSize(outputShape, outputDataType);
                outputByteBuffer = null; // Clear direct buffer
                Log.d(TAG, "New output TensorBuffer capacity: " + outputBuffer.getBuffer().capacity() + " bytes");
            }
        }
        
        if (!needNewInputBuffer && !needNewOutputBuffer) {
            Log.d(TAG, "Buffers are already correct size");
        }
    }
    
    public interface InferenceCallback {
        void onResult(Bitmap result, long inferenceTime);
        void onError(String error);
    }
    
    public interface BatchInferenceCallback {
        void onResult(List<Bitmap> results, long inferenceTime);
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
                            Log.d(TAG, "Model: " + modelPath);
                            Log.d(TAG, "NPU allow_fp16: " + allowFp16);
                        }
                        
                        // Run inference with appropriate buffers
                        ByteBuffer inputBuf = (inputBuffer != null) ? inputBuffer.getBuffer() : inputByteBuffer;
                        ByteBuffer outputBuf = (outputBuffer != null) ? outputBuffer.getBuffer() : outputByteBuffer;
                        currentInterpreter.run(inputBuf, outputBuf);
                        long pureInferenceTime = System.currentTimeMillis() - inferenceStart;

                        Log.d(TAG, "Pure inference time: " + pureInferenceTime + "ms"); 
                    }
                    
                catch (java.nio.BufferOverflowException e) {
                    Log.e(TAG, "BufferOverflowException during inference, attempting to fix", e);
                    
                    // 強制重新分配緩衝區
                    inputBuffer = null;
                    outputBuffer = null;
                    inputByteBuffer = null;
                    outputByteBuffer = null;
                    ensureBuffersAreCorrectSize();
                    
                    // 重新轉換輸入
                    convertBitmapToBuffer(resizedInput);
                    
                    // 確保緩衝區位置正確
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
                    
                    Log.d(TAG, "Retrying inference with new buffers");
                    
                    // 重試推理
                    ByteBuffer retryInputBuf = (inputBuffer != null) ? inputBuffer.getBuffer() : inputByteBuffer;
                    ByteBuffer retryOutputBuf = (outputBuffer != null) ? outputBuffer.getBuffer() : outputByteBuffer;
                    currentInterpreter.run(retryInputBuf, retryOutputBuf);
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
        
        // Debug original float values
        debugFloat32Values(cachedOutputFloatArray, totalFloats);
        
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
    
    
    private void debugFloat32Values(float[] floatArray, int totalFloats) {
        if (totalFloats == 0) return;
        
        // Sample float values for analysis
        int sampleSize = Math.min(300, totalFloats); // Sample 100 pixels = 300 floats (RGB)
        
        float min = Float.MAX_VALUE, max = Float.MIN_VALUE;
        float sum = 0;
        int zeroCount = 0, negativeCount = 0, aboveOneCount = 0;
        
        for (int i = 0; i < sampleSize; i++) {
            float val = floatArray[i];
            min = Math.min(min, val);
            max = Math.max(max, val);
            sum += val;
            
            if (val == 0.0f) zeroCount++;
            if (val < 0.0f) negativeCount++;
            if (val > 1.0f) aboveOneCount++;
        }
        
        float mean = sum / sampleSize;
        
        Log.d(TAG, "=== Raw Float32 Output Analysis ===");
        Log.d(TAG, "Sample size: " + sampleSize + "/" + totalFloats);
        Log.d(TAG, "Range: [" + min + ", " + max + "]");
        Log.d(TAG, "Mean: " + mean);
        Log.d(TAG, "Zero values: " + zeroCount + " (" + (100.0 * zeroCount / sampleSize) + "%)");
        Log.d(TAG, "Negative values: " + negativeCount + " (" + (100.0 * negativeCount / sampleSize) + "%)");
        Log.d(TAG, "Above 1.0 values: " + aboveOneCount + " (" + (100.0 * aboveOneCount / sampleSize) + "%)");
        
        // Check for problematic ranges
        if (min == 0.0f && max == 0.0f) {
            Log.e(TAG, "⚠️ ALL FLOAT VALUES ARE ZERO! Model output is completely black.");
        } else if (min < -1.0f || max > 1.0f) {
            Log.w(TAG, "⚠️ Values outside typical [0,1] or [-1,1] range. May need different normalization.");
        } else if (min >= 0.0f && max <= 1.0f) {
            Log.d(TAG, "✓ Values in [0,1] range - standard normalization");
        } else if (min >= -1.0f && max <= 1.0f) {
            Log.d(TAG, "⚠️ Values in [-1,1] range - may need adjustment for INT8 quantized models");
        }
        
        // Log first few values for debugging
        Log.d(TAG, "First 9 float values (3 pixels RGB): ");
        for (int i = 0; i < Math.min(9, sampleSize); i++) {
            Log.d(TAG, "  [" + i + "] = " + floatArray[i]);
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
                return "GPU";
            case NPU:
                return "NPU (Neural Processing Unit)";
            case CPU:
            default:
                return "CPU";
        }
    }
    
    /**
     * Get detailed information about current delegate
     */
    private String getCurrentDelegate() {
        String delegateInfo = "Mode: " + currentMode.name();
        
        switch (currentMode) {
            case GPU:
                delegateInfo += ", GPU Delegate: " + (gpuDelegate != null ? "Active" : "Null");
                break;
            case NPU:
                delegateInfo += ", NPU Delegate: " + (npuDelegate != null ? "Active" : "Null");
                break;
            case CPU:
                delegateInfo += ", CPU Only";
                break;
        }
        
        return delegateInfo;
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
    
    /**
     * Check if the current model supports batch processing
     * @return true if model has batch dimension > 1
     */
    public boolean isModelBatchCapable() {
        if (currentInterpreter == null) return false;
        
        int[] inputShape = currentInterpreter.getInputTensor(0).shape();
        int[] outputShape = currentInterpreter.getOutputTensor(0).shape();
        
        // Detailed shape logging for verification
        Log.d(TAG, "=== MODEL SHAPE VERIFICATION ===");
        Log.d(TAG, "Input tensor shape: " + java.util.Arrays.toString(inputShape));
        Log.d(TAG, "Output tensor shape: " + java.util.Arrays.toString(outputShape));
        Log.d(TAG, "Input tensor data type: " + currentInterpreter.getInputTensor(0).dataType());
        Log.d(TAG, "Output tensor data type: " + currentInterpreter.getOutputTensor(0).dataType());
        
        boolean isBatchCapable = inputShape != null && inputShape.length >= 4 && inputShape[0] > 1;
        Log.d(TAG, "Model batch capable: " + isBatchCapable);
        Log.d(TAG, "Current delegate: " + getCurrentDelegate());
        Log.d(TAG, "=================================");
        
        return isBatchCapable;
    }
    
    /**
     * Get the batch size supported by the current model
     * @return batch size, or 1 if not batch capable
     */
    public int getModelBatchSize() {
        if (!isModelBatchCapable()) return 1;
        
        int[] inputShape = currentInterpreter.getInputTensor(0).shape();
        return inputShape[0];
    }
    
    /**
     * Process multiple images in a single batch inference call
     * Requires a model with batch dimension > 1
     */
    public void processBatch(List<Bitmap> inputBitmaps, ProcessingMode forceMode, BatchInferenceCallback callback) {
        if (!isInitialized) {
            callback.onError("Processor not initialized");
            return;
        }
        
        if (inputBitmaps == null || inputBitmaps.isEmpty()) {
            callback.onError("No input bitmaps provided");
            return;
        }
        
        if (!isModelBatchCapable()) {
            callback.onError("Current model does not support batch processing");
            return;
        }
        
        int modelBatchSize = getModelBatchSize();
        if (inputBitmaps.size() != modelBatchSize) {
            callback.onError("Input batch size (" + inputBitmaps.size() + ") does not match model batch size (" + modelBatchSize + ")");
            return;
        }
        
        srHandler.post(() -> {
            try {
                Log.d(TAG, "Processing batch of " + inputBitmaps.size() + " images");
                
                // Switch mode if needed (same logic as single image processing)
                if (forceMode != null && forceMode != currentMode) {
                    switchToMode(forceMode);
                }
                
                long startTime = System.currentTimeMillis();
                List<Bitmap> results = performBatchInference(inputBitmaps);
                long inferenceTime = System.currentTimeMillis() - startTime;
                
                if (results != null && results.size() == inputBitmaps.size()) {
                    new Handler(Looper.getMainLooper()).post(() -> 
                        callback.onResult(results, inferenceTime));
                } else {
                    new Handler(Looper.getMainLooper()).post(() -> 
                        callback.onError("Batch inference failed to produce expected results"));
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Exception during batch processing", e);
                new Handler(Looper.getMainLooper()).post(() -> 
                    callback.onError("Batch processing failed: " + e.getMessage()));
            }
        });
    }
    
    /**
     * Perform the actual batch inference on the SR thread
     */
    private List<Bitmap> performBatchInference(List<Bitmap> inputBitmaps) throws Exception {
        if (currentInterpreter == null) {
            throw new IllegalStateException("No interpreter available");
        }
        
        int batchSize = inputBitmaps.size();
        
        Log.d(TAG, "=== BATCH INFERENCE PROFILING START ===");
        Log.d(TAG, "Batch size: " + batchSize);
        Log.d(TAG, "Current delegate: " + getCurrentDelegate());
        
        // Stack input bitmaps into batch tensor
        long stackingStart = System.currentTimeMillis();
        ByteBuffer batchInputBuffer = stackBitmapsToBatchTensor(inputBitmaps);
        long stackingTime = System.currentTimeMillis() - stackingStart;
        Log.d(TAG, "Tensor stacking time: " + stackingTime + "ms");
        
        // Prepare output buffer for batch
        int outputSize = actualOutputWidth * actualOutputHeight * 3 * batchSize;
        ByteBuffer batchOutputBuffer = ByteBuffer.allocateDirect(outputSize);
        batchOutputBuffer.order(java.nio.ByteOrder.nativeOrder());
        Log.d(TAG, "Output buffer size: " + outputSize + " bytes (" + (outputSize/1024/1024) + "MB)");
        
        // Run batch inference with detailed timing
        Log.d(TAG, "=== STARTING CORE INFERENCE ===");
        Log.d(TAG, "Running batch inference with " + batchSize + " images on " + currentMode.name());
        
        long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long inferenceStart = System.currentTimeMillis();
        
        currentInterpreter.run(batchInputBuffer, batchOutputBuffer);
        
        long inferenceEnd = System.currentTimeMillis();
        long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long inferenceTime = inferenceEnd - inferenceStart;
        
        Log.d(TAG, "=== CORE INFERENCE COMPLETED ===");
        Log.d(TAG, "Pure inference time: " + inferenceTime + "ms");
        Log.d(TAG, "Memory before: " + (memoryBefore/1024/1024) + "MB");
        Log.d(TAG, "Memory after: " + (memoryAfter/1024/1024) + "MB");
        Log.d(TAG, "Memory delta: " + ((memoryAfter-memoryBefore)/1024/1024) + "MB");
        Log.d(TAG, "Average time per tile: " + (inferenceTime/batchSize) + "ms");
        
        // Unstack output tensor back to individual bitmaps
        long unstackingStart = System.currentTimeMillis();
        List<Bitmap> results = unstackBatchTensorToBitmaps(batchOutputBuffer, batchSize);
        long unstackingTime = System.currentTimeMillis() - unstackingStart;
        
        Log.d(TAG, "Tensor unstacking time: " + unstackingTime + "ms");
        Log.d(TAG, "Total batch processing time: " + (System.currentTimeMillis() - stackingStart) + "ms");
        Log.d(TAG, "=== BATCH INFERENCE PROFILING END ===");
        
        return results;
    }
    
    /**
     * Stack multiple bitmaps into a single batch tensor
     */
    private ByteBuffer stackBitmapsToBatchTensor(List<Bitmap> bitmaps) throws Exception {
        int batchSize = bitmaps.size();
        int tensorSize = batchSize * actualInputWidth * actualInputHeight * 3;
        
        ByteBuffer batchBuffer = ByteBuffer.allocateDirect(tensorSize);
        batchBuffer.order(java.nio.ByteOrder.nativeOrder());
        
        Log.d(TAG, "Stacking " + batchSize + " bitmaps into batch tensor (" + tensorSize + " bytes)");
        
        for (int i = 0; i < batchSize; i++) {
            Bitmap bitmap = bitmaps.get(i);
            
            // Ensure bitmap is correct size
            if (bitmap.getWidth() != actualInputWidth || bitmap.getHeight() != actualInputHeight) {
                bitmap = Bitmap.createScaledBitmap(bitmap, actualInputWidth, actualInputHeight, true);
            }
            
            // Convert bitmap to tensor data and append to batch buffer
            byte[] tensorData = BitmapConverter.bitmapToInt8Tensor(bitmap, actualInputWidth, actualInputHeight);
            batchBuffer.put(tensorData);
            
            Log.v(TAG, "Stacked bitmap " + i + " into batch tensor");
        }
        
        batchBuffer.rewind();
        return batchBuffer;
    }
    
    /**
     * Unstack batch output tensor back to individual bitmaps
     */
    private List<Bitmap> unstackBatchTensorToBitmaps(ByteBuffer batchBuffer, int batchSize) throws Exception {
        List<Bitmap> results = new ArrayList<>();
        int singleOutputSize = actualOutputWidth * actualOutputHeight * 3;
        
        Log.d(TAG, "Unstacking batch tensor to " + batchSize + " bitmaps");
        
        batchBuffer.rewind();
        for (int i = 0; i < batchSize; i++) {
            // Extract single output tensor data
            byte[] outputData = new byte[singleOutputSize];
            batchBuffer.get(outputData);
            
            // Convert tensor data back to bitmap
            Bitmap resultBitmap = BitmapConverter.int8TensorToBitmap(outputData, actualOutputWidth, actualOutputHeight);
            
            if (resultBitmap != null) {
                results.add(resultBitmap);
                Log.v(TAG, "Unstacked bitmap " + i + " from batch tensor");
            } else {
                throw new Exception("Failed to convert output tensor " + i + " to bitmap");
            }
        }
        
        Log.d(TAG, "Successfully unstacked " + results.size() + " bitmaps");
        return results;
    }
}