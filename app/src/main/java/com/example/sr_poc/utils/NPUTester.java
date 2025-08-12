package com.example.sr_poc.utils;

import android.content.Context;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.nnapi.NnApiDelegate;
import org.tensorflow.lite.DataType;

import java.nio.ByteBuffer;

import com.example.sr_poc.ConfigManager;
import com.example.sr_poc.HardwareInfo;

public class NPUTester {
    
    private static final String TAG = "NPUTester";
    
    public interface NPUTestCallback {
        void onTestResult(TestResult result);
    }
    
    public static class TestResult {
        public boolean success;
        public String message;
        public String errorDetails;
        public long initTime;
        public ModelInfo modelInfo;
        
        public TestResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
    
    public static class ModelInfo {
        public int[] inputShape;
        public int[] outputShape;
        public DataType inputDataType;
        public DataType outputDataType;
        public String modelPath;
        public long modelSizeKB;
    }
    
    private Context context;
    private ConfigManager configManager;
    
    public NPUTester(Context context) {
        this.context = context;
        this.configManager = ConfigManager.getInstance(context);
    }
    
    public void testNPUModelLoading(String modelPath, NPUTestCallback callback) {
        Log.d(TAG, "=== Starting NPU Model Loading Test ===");
        Log.d(TAG, "Target model: " + modelPath);
        
        TestResult result = new TestResult(false, "");
        long startTime = System.currentTimeMillis();
        
        try {
            // Load model file
            Log.d(TAG, "Loading model file from assets...");
            ByteBuffer tfliteModel = FileUtil.loadMappedFile(context, modelPath);
            long modelSizeKB = tfliteModel.capacity() / 1024;
            Log.d(TAG, "Model loaded successfully: " + modelSizeKB + "KB");
            
            // Hardware diagnostics
            logHardwareDiagnostics();
            
            // Test NPU delegate creation
            Log.d(TAG, "Creating NPU delegate...");
            NnApiDelegate.Options npuOptions = createNPUDelegateOptions();
            NnApiDelegate npuDelegate = new NnApiDelegate(npuOptions);
            Log.d(TAG, "NPU delegate created successfully");
            
            // Test interpreter creation
            Log.d(TAG, "Creating TensorFlow Lite interpreter with NPU delegate...");
            Interpreter.Options interpreterOptions = new Interpreter.Options();
            interpreterOptions.addDelegate(npuDelegate);
            
            Interpreter interpreter = new Interpreter(tfliteModel, interpreterOptions);
            Log.d(TAG, "Interpreter created successfully with NPU delegate");
            
            // Extract model information
            ModelInfo modelInfo = extractModelInfo(interpreter, modelPath, modelSizeKB);
            logModelInfo(modelInfo);
            
            // Verify model compatibility
            verifyModelCompatibility(modelInfo);
            
            result.success = true;
            result.initTime = System.currentTimeMillis() - startTime;
            result.modelInfo = modelInfo;
            result.message = String.format("NPU model loading successful in %dms", result.initTime);
            
            Log.d(TAG, "=== NPU Test Completed Successfully ===");
            Log.d(TAG, "Total initialization time: " + result.initTime + "ms");
            
            // Cleanup
            interpreter.close();
            npuDelegate.close();
            
        } catch (Exception e) {
            result.success = false;
            result.initTime = System.currentTimeMillis() - startTime;
            result.message = "NPU model loading failed: " + e.getMessage();
            result.errorDetails = getDetailedErrorAnalysis(e);
            
            Log.e(TAG, "=== NPU Test Failed ===");
            Log.e(TAG, "Error: " + e.getMessage(), e);
            Log.e(TAG, "Detailed analysis: " + result.errorDetails);
        }
        
        callback.onTestResult(result);
    }
    
    private void logHardwareDiagnostics() {
        Log.d(TAG, "=== Hardware Diagnostics ===");
        Log.d(TAG, "Device: " + android.os.Build.MODEL);
        Log.d(TAG, "SoC: " + android.os.Build.SOC_MODEL);
        Log.d(TAG, "Android version: " + android.os.Build.VERSION.RELEASE);
        Log.d(TAG, "API level: " + android.os.Build.VERSION.SDK_INT);
        
        if (HardwareInfo.isMT8195()) {
            Log.d(TAG, "MediaTek MT8195 detected - APU 3.0 NPU available");
            Log.d(TAG, "Expected NPU capabilities: INT8, INT16, FP16 support");
        } else {
            Log.w(TAG, "Non-MT8195 device - NPU support may vary");
        }
        
        String acceleratorInfo = HardwareInfo.getAcceleratorInfo();
        Log.d(TAG, "Hardware accelerator info: " + acceleratorInfo);
    }
    
    private NnApiDelegate.Options createNPUDelegateOptions() {
        Log.d(TAG, "=== NPU Delegate Configuration ===");
        NnApiDelegate.Options npuOptions = new NnApiDelegate.Options();
        
        String acceleratorName = configManager.getNpuAcceleratorName();
        if (!acceleratorName.isEmpty()) {
            npuOptions.setAcceleratorName(acceleratorName);
            Log.d(TAG, "NPU accelerator name: " + acceleratorName);
        } else {
            Log.d(TAG, "NPU accelerator: auto-detect");
        }
        
        boolean allowFp16 = configManager.isAllowFp16OnNpu();
        npuOptions.setAllowFp16(allowFp16);
        Log.d(TAG, "Allow FP16: " + allowFp16);
        
        boolean useNpuForQuantized = configManager.isUseNpuForQuantized();
        Log.d(TAG, "Use NPU for quantized models: " + useNpuForQuantized);
        
        String modelPath = configManager.getSelectedModelPath();
        if (modelPath.contains("integer_quant")) {
            Log.d(TAG, "INT8 quantized model detected");
            Log.d(TAG, "Expected behavior: NPU should handle INT8 operations natively");
            Log.d(TAG, "Interface compatibility: TensorFlow Lite may provide FLOAT32 interface");
        }
        
        return npuOptions;
    }
    
    private ModelInfo extractModelInfo(Interpreter interpreter, String modelPath, long modelSizeKB) {
        ModelInfo info = new ModelInfo();
        info.modelPath = modelPath;
        info.modelSizeKB = modelSizeKB;
        
        info.inputShape = interpreter.getInputTensor(0).shape();
        info.outputShape = interpreter.getOutputTensor(0).shape();
        info.inputDataType = interpreter.getInputTensor(0).dataType();
        info.outputDataType = interpreter.getOutputTensor(0).dataType();
        
        return info;
    }
    
    private void logModelInfo(ModelInfo info) {
        Log.d(TAG, "=== Model Information ===");
        Log.d(TAG, "Model path: " + info.modelPath);
        Log.d(TAG, "Model size: " + info.modelSizeKB + "KB");
        Log.d(TAG, "Input shape: " + java.util.Arrays.toString(info.inputShape));
        Log.d(TAG, "Output shape: " + java.util.Arrays.toString(info.outputShape));
        Log.d(TAG, "Input data type: " + info.inputDataType);
        Log.d(TAG, "Output data type: " + info.outputDataType);
        
        if (info.modelPath.contains("integer_quant")) {
            Log.d(TAG, "=== INT8 Quantization Analysis ===");
            if (info.inputDataType == DataType.FLOAT32 && info.outputDataType == DataType.FLOAT32) {
                Log.d(TAG, "✓ FLOAT32 interface maintained despite internal INT8 quantization");
                Log.d(TAG, "✓ This confirms TensorFlow Lite automatic conversion support");
            } else {
                Log.w(TAG, "⚠ Unexpected data types for INT8 quantized model");
                Log.w(TAG, "Expected: FLOAT32 interface, Got: Input=" + info.inputDataType + ", Output=" + info.outputDataType);
            }
        }
    }
    
    private void verifyModelCompatibility(ModelInfo info) {
        Log.d(TAG, "=== Model Compatibility Verification ===");
        
        // Check expected scale factor
        int expectedScaleFactor = configManager.getExpectedScaleFactor();
        if (info.inputShape.length >= 3 && info.outputShape.length >= 3) {
            int inputWidth = info.inputShape[info.inputShape.length - 2];
            int inputHeight = info.inputShape[info.inputShape.length - 3];
            int outputWidth = info.outputShape[info.outputShape.length - 2];
            int outputHeight = info.outputShape[info.outputShape.length - 3];
            
            int actualScaleFactorW = outputWidth / inputWidth;
            int actualScaleFactorH = outputHeight / inputHeight;
            
            if (actualScaleFactorW == expectedScaleFactor && actualScaleFactorH == expectedScaleFactor) {
                Log.d(TAG, "✓ Scale factor verification passed: " + expectedScaleFactor + "x");
            } else {
                Log.w(TAG, "⚠ Scale factor mismatch - Expected: " + expectedScaleFactor + 
                          "x, Got: " + actualScaleFactorW + "x" + actualScaleFactorH);
            }
        }
        
        // Check channel count
        int expectedChannels = configManager.getChannels();
        if (info.inputShape.length >= 1) {
            int actualChannels = info.inputShape[info.inputShape.length - 1];
            if (actualChannels == expectedChannels) {
                Log.d(TAG, "✓ Channel count verification passed: " + expectedChannels);
            } else {
                Log.w(TAG, "⚠ Channel count mismatch - Expected: " + expectedChannels + 
                          ", Got: " + actualChannels);
            }
        }
    }
    
    private String getDetailedErrorAnalysis(Exception e) {
        StringBuilder analysis = new StringBuilder();
        analysis.append("Error analysis:\n");
        
        String errorMsg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        
        if (errorMsg.contains("nnapi") || errorMsg.contains("npu")) {
            analysis.append("• NPU/NNAPI related error\n");
            analysis.append("• Possible causes:\n");
            analysis.append("  - NPU driver compatibility issues\n");
            analysis.append("  - INT8 quantized model not supported by NPU\n");
            analysis.append("  - NNAPI delegate configuration problems\n");
            analysis.append("• Suggestions:\n");
            analysis.append("  - Try different FP16 settings\n");
            analysis.append("  - Test with FLOAT32 models first\n");
            analysis.append("  - Check NPU accelerator name configuration\n");
        } else if (errorMsg.contains("model") || errorMsg.contains("tflite")) {
            analysis.append("• Model loading related error\n");
            analysis.append("• Possible causes:\n");
            analysis.append("  - Model file corruption or incompatibility\n");
            analysis.append("  - Unsupported quantization format\n");
            analysis.append("  - TensorFlow Lite version mismatch\n");
        } else if (errorMsg.contains("memory") || errorMsg.contains("allocation")) {
            analysis.append("• Memory related error\n");
            analysis.append("• Possible causes:\n");
            analysis.append("  - Insufficient device memory\n");
            analysis.append("  - NPU memory allocation failure\n");
        } else {
            analysis.append("• General initialization error\n");
            analysis.append("• Check device logs for more details\n");
        }
        
        return analysis.toString();
    }
}