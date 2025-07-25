package com.example.sr_poc;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class SuperResolutionProcessor {
    
    private static final String TAG = "SuperResolutionProcessor";
    
    private Context context;
    private ConfigManager configManager;
    private Interpreter tflite;
    private TensorBuffer inputBuffer;
    private TensorBuffer outputBuffer;
    private boolean isQuantizedModel = false;
    private boolean isUsingGpu = false;
    private GpuDelegate gpuDelegate;
    private int actualInputWidth;
    private int actualInputHeight;
    private int actualOutputWidth;
    private int actualOutputHeight;
    
    public SuperResolutionProcessor(Context context) {
        this.context = context;
        this.configManager = ConfigManager.getInstance(context);
    }
    
    public boolean loadModel() {
        try {
            String modelPath = configManager.getDefaultModelPath();
            Log.d(TAG, "Loading model from: " + modelPath);
            
            // 檢查可用記憶體（如果配置啟用記憶體記錄）
            if (configManager.isEnableMemoryLogging()) {
                Runtime runtime = Runtime.getRuntime();
                long maxMemory = runtime.maxMemory();
                long totalMemory = runtime.totalMemory();
                long freeMemory = runtime.freeMemory();
                Log.d(TAG, "Memory - Max: " + (maxMemory / 1024 / 1024) + "MB, " +
                          "Total: " + (totalMemory / 1024 / 1024) + "MB, " +
                          "Free: " + (freeMemory / 1024 / 1024) + "MB");
            }
            
            ByteBuffer tfliteModel = FileUtil.loadMappedFile(context, modelPath);
            Log.d(TAG, "Model file loaded, size: " + (tfliteModel.capacity() / 1024) + "KB");
            
            Interpreter.Options options = new Interpreter.Options();
            
            // 首先嘗試GPU加速
            if (tryLoadWithGpu(options)) {
                Log.d(TAG, "Successfully loaded with GPU acceleration");
                isUsingGpu = true;
            } else {
                Log.d(TAG, "GPU not available, falling back to CPU");
                isUsingGpu = false;
                setupCpuOptions(options);
            }
            
            tflite = new Interpreter(tfliteModel, options);
            Log.d(TAG, "Interpreter created successfully with " + (isUsingGpu ? "GPU" : "CPU"));
            
            allocateBuffers();
            Log.d(TAG, "Model loaded successfully");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to load model", e);
            return false;
        }
    }
    
    private boolean tryLoadWithGpu(Interpreter.Options options) {
        try {
            CompatibilityList compatList = new CompatibilityList();
            if (compatList.isDelegateSupportedOnThisDevice()) {
                Log.d(TAG, "GPU delegate is supported on this device");
                
                // 使用默認選項創建GPU delegate
                gpuDelegate = new GpuDelegate();
                options.addDelegate(gpuDelegate);
                
                Log.d(TAG, "GPU delegate configured successfully");
                return true;
            } else {
                Log.w(TAG, "GPU delegate not supported on this device");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to setup GPU delegate: " + e.getMessage());
            return false;
        }
    }
    
    private void setupCpuOptions(Interpreter.Options options) {
        int numThreads = configManager.getDefaultNumThreads();
        options.setNumThreads(numThreads);
        
        if (configManager.isAllowFp16Precision()) {
            options.setAllowFp16PrecisionForFp32(true);
        }
        
        if (configManager.isUseXnnpack()) {
            try {
                options.setUseXNNPACK(true);
                Log.d(TAG, "Using XNNPACK delegate for CPU with " + numThreads + " threads");
            } catch (Exception e) {
                Log.w(TAG, "XNNPACK not available: " + e.getMessage());
            }
        }
    }
    
    private void allocateBuffers() {
        // 檢查模型的輸入輸出類型和形狀
        DataType inputDataType = tflite.getInputTensor(0).dataType();
        DataType outputDataType = tflite.getOutputTensor(0).dataType();
        
        // 從模型獲取實際的輸入輸出形狀
        int[] actualInputShape = tflite.getInputTensor(0).shape();
        int[] actualOutputShape = tflite.getOutputTensor(0).shape();
        
        isQuantizedModel = (inputDataType == DataType.UINT8 || inputDataType == DataType.INT8);
        
        Log.d(TAG, "Input data type: " + inputDataType);
        Log.d(TAG, "Output data type: " + outputDataType);
        Log.d(TAG, "Is quantized model: " + isQuantizedModel);
        Log.d(TAG, "Actual input shape: " + java.util.Arrays.toString(actualInputShape));
        Log.d(TAG, "Actual output shape: " + java.util.Arrays.toString(actualOutputShape));
        
        // 解析實際的輸入輸出尺寸
        if (actualInputShape.length >= 3) {
            actualInputHeight = actualInputShape[1];
            actualInputWidth = actualInputShape[2];
        } else {
            throw new RuntimeException("Invalid input shape: " + java.util.Arrays.toString(actualInputShape));
        }
        
        if (actualOutputShape.length >= 3) {
            actualOutputHeight = actualOutputShape[1];
            actualOutputWidth = actualOutputShape[2];
        } else {
            throw new RuntimeException("Invalid output shape: " + java.util.Arrays.toString(actualOutputShape));
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
        
        Log.d(TAG, "Actual input size: " + actualInputWidth + "x" + actualInputHeight);
        Log.d(TAG, "Actual output size: " + actualOutputWidth + "x" + actualOutputHeight);
        
        // 使用模型的實際形狀創建TensorBuffer
        inputBuffer = TensorBuffer.createFixedSize(actualInputShape, inputDataType);
        outputBuffer = TensorBuffer.createFixedSize(actualOutputShape, outputDataType);
        
        Log.d(TAG, "Input buffer size: " + inputBuffer.getBuffer().capacity() + " bytes");
        Log.d(TAG, "Output buffer size: " + outputBuffer.getBuffer().capacity() + " bytes");
    }
    
    public Bitmap performSuperResolution(Bitmap inputBitmap) {
        if (tflite == null) {
            Log.e(TAG, "Model not loaded");
            return null;
        }
        
        try {
            Log.d(TAG, "Starting super resolution inference using " + (isUsingGpu ? "GPU" : "CPU"));
            
            // 檢查當前記憶體狀況
            Runtime runtime = Runtime.getRuntime();
            long freeMemoryBefore = runtime.freeMemory();
            Log.d(TAG, "Free memory before inference: " + (freeMemoryBefore / 1024 / 1024) + "MB");
            
            // Preprocess input image
            Log.d(TAG, "Preprocessing input image");
            Bitmap resizedBitmap = preprocessImage(inputBitmap);
            if (resizedBitmap == null) {
                Log.e(TAG, "Failed to preprocess image");
            return null;
            }
            
            // Convert bitmap to input buffer
            Log.d(TAG, "Converting bitmap to buffer");
            convertBitmapToBuffer(resizedBitmap);
            
            // 釋放調整後的圖片(如果不是原圖)
            if (resizedBitmap != inputBitmap && !resizedBitmap.isRecycled()) {
                resizedBitmap.recycle();
            }
            
            // 再次檢查記憶體
            long freeMemoryAfterPreprocess = runtime.freeMemory();
            Log.d(TAG, "Free memory after preprocess: " + (freeMemoryAfterPreprocess / 1024 / 1024) + "MB");
            
            // Run inference
            Log.d(TAG, "Running TensorFlow Lite inference");
            long startTime = System.currentTimeMillis();
            tflite.run(inputBuffer.getBuffer(), outputBuffer.getBuffer());
            long inferenceTime = System.currentTimeMillis() - startTime;
            Log.d(TAG, "Inference completed in " + inferenceTime + "ms");
            
            // 檢查推理後的記憶體
            long freeMemoryAfterInference = runtime.freeMemory();
            Log.d(TAG, "Free memory after inference: " + (freeMemoryAfterInference / 1024 / 1024) + "MB");
            
            // Convert output to bitmap
            Log.d(TAG, "Converting output to bitmap");
            Bitmap resultBitmap = convertOutputToBitmap();
            
            long freeMemoryAfterConversion = runtime.freeMemory();
            Log.d(TAG, "Free memory after conversion: " + (freeMemoryAfterConversion / 1024 / 1024) + "MB");
            
            if (resultBitmap == null) {
                Log.e(TAG, "Failed to convert output to bitmap");
                return null;
            }
            
            Log.d(TAG, "Super resolution completed successfully");
            return resultBitmap;
            
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Out of memory during super resolution", e);
            // GC移除以避免性能暫停
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error during super resolution", e);
            return null;
        }
    }
    
    private Bitmap preprocessImage(Bitmap bitmap) {
        // Resize to actual model input size
        if (bitmap.getWidth() != actualInputWidth || bitmap.getHeight() != actualInputHeight) {
            Log.d(TAG, "Resizing input from " + bitmap.getWidth() + "x" + bitmap.getHeight() + 
                      " to " + actualInputWidth + "x" + actualInputHeight);
            return Bitmap.createScaledBitmap(bitmap, actualInputWidth, actualInputHeight, true);
        }
        return bitmap;
    }
    
    private void convertBitmapToBuffer(Bitmap bitmap) {
        int[] pixels = new int[actualInputWidth * actualInputHeight];
        bitmap.getPixels(pixels, 0, actualInputWidth, 0, 0, actualInputWidth, actualInputHeight);
        
        inputBuffer.getBuffer().rewind();
        
        if (isQuantizedModel) {
            // For quantized models (int8/uint8)
            for (int pixel : pixels) {
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                
                inputBuffer.getBuffer().put((byte) r);
                inputBuffer.getBuffer().put((byte) g);
                inputBuffer.getBuffer().put((byte) b);
            }
        } else {
            // For float32 models
            for (int pixel : pixels) {
                float r = ((pixel >> 16) & 0xFF) / 255.0f;
                float g = ((pixel >> 8) & 0xFF) / 255.0f;
                float b = (pixel & 0xFF) / 255.0f;
                
                inputBuffer.getBuffer().putFloat(r);
                inputBuffer.getBuffer().putFloat(g);
                inputBuffer.getBuffer().putFloat(b);
            }
        }
    }
    
    private Bitmap convertOutputToBitmap() {
        int[] pixels = new int[actualOutputWidth * actualOutputHeight];
        outputBuffer.getBuffer().rewind();
        
        Log.d(TAG, "Converting output buffer to bitmap: " + actualOutputWidth + "x" + actualOutputHeight);
        
        if (isQuantizedModel) {
            // For quantized output
            for (int i = 0; i < actualOutputWidth * actualOutputHeight; i++) {
                int r = outputBuffer.getBuffer().get() & 0xFF;
                int g = outputBuffer.getBuffer().get() & 0xFF;
                int b = outputBuffer.getBuffer().get() & 0xFF;
                
                pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        } else {
            // For float32 output
            for (int i = 0; i < actualOutputWidth * actualOutputHeight; i++) {
                float r = Math.max(0, Math.min(1, outputBuffer.getBuffer().getFloat()));
                float g = Math.max(0, Math.min(1, outputBuffer.getBuffer().getFloat()));
                float b = Math.max(0, Math.min(1, outputBuffer.getBuffer().getFloat()));
                
                int red = (int) (r * 255);
                int green = (int) (g * 255);
                int blue = (int) (b * 255);
                
                pixels[i] = 0xFF000000 | (red << 16) | (green << 8) | blue;
            }
        }
        
        return Bitmap.createBitmap(pixels, actualOutputWidth, actualOutputHeight, Bitmap.Config.ARGB_8888);
    }
    
    public void close() {
        if (tflite != null) {
            tflite.close();
            tflite = null;
        }
        if (gpuDelegate != null) {
            gpuDelegate.close();
            gpuDelegate = null;
        }
        Log.d(TAG, "SuperResolutionProcessor closed and resources released");
    }
    
    public boolean isUsingGpu() {
        return isUsingGpu;
    }
    
    public String getAcceleratorInfo() {
        return isUsingGpu ? "GPU (TensorFlow Lite GPU Delegate)" : "CPU (XNNPACK/Native)";
    }
}