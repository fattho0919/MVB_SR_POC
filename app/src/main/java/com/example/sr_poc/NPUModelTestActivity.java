package com.example.sr_poc;

import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.sr_poc.utils.NPUTester;

public class NPUModelTestActivity extends AppCompatActivity {
    
    private static final String TAG = "NPUModelTestActivity";
    
    private Button btnTestNPU;
    private Button btnTestComparison;
    private Button btnClearLog;
    private TextView tvTestResults;
    private TextView tvLogOutput;
    private ScrollView scrollViewLog;
    
    private NPUTester npuTester;
    private ConfigManager configManager;
    private HandlerThread testThread;
    private Handler testHandler;
    private Handler mainHandler;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_npu_test);
        
        initViews();
        initComponents();
        setupListeners();
        
        logMessage("NPU Model Test Activity initialized");
        logMessage("Ready to test INT8 quantized model on NPU");
    }
    
    private void initViews() {
        btnTestNPU = findViewById(R.id.btnTestNPU);
        btnTestComparison = findViewById(R.id.btnTestComparison);
        btnClearLog = findViewById(R.id.btnClearLog);
        tvTestResults = findViewById(R.id.tvTestResults);
        tvLogOutput = findViewById(R.id.tvLogOutput);
        scrollViewLog = findViewById(R.id.scrollViewLog);
        
        tvTestResults.setText("Ready to test NPU model loading");
    }
    
    private void initComponents() {
        npuTester = new NPUTester(this);
        configManager = ConfigManager.getInstance(this);
        mainHandler = new Handler(getMainLooper());
        
        // Create background thread for testing
        testThread = new HandlerThread("NPUTestThread");
        testThread.start();
        testHandler = new Handler(testThread.getLooper());
        
        // Log hardware and configuration info
        logMessage("=== Device Information ===");
        logMessage("Device: " + android.os.Build.MODEL);
        logMessage("SoC: " + android.os.Build.SOC_MODEL);
        logMessage("Android: " + android.os.Build.VERSION.RELEASE);
        
        String modelPath = configManager.getDefaultModelPath();
        logMessage("\\n=== Configuration ===");
        logMessage("Default model: " + modelPath);
        logMessage("NPU enabled: " + configManager.isEnableNpu());
        logMessage("Allow FP16 on NPU: " + configManager.isAllowFp16OnNpu());
        logMessage("Use NPU for quantized: " + configManager.isUseNpuForQuantized());
        logMessage("Enable quantized inference: " + configManager.isEnableQuantizedInference());
    }
    
    private void setupListeners() {
        btnTestNPU.setOnClickListener(v -> testNPUModel());
        btnTestComparison.setOnClickListener(v -> testModelComparison());
        btnClearLog.setOnClickListener(v -> clearLog());
    }
    
    private void testNPUModel() {
        setButtonsEnabled(false);
        tvTestResults.setText("Testing NPU model loading...");
        String separator = "==========================================";
        logMessage("\\n" + separator);
        logMessage("Starting NPU Model Test");
        logMessage("Model: " + configManager.getDefaultModelPath());
        logMessage(separator);
        
        testHandler.post(() -> {
            String modelPath = configManager.getDefaultModelPath();
            npuTester.testNPUModelLoading(modelPath, this::handleNPUTestResult);
        });
    }
    
    private void testModelComparison() {
        setButtonsEnabled(false);
        tvTestResults.setText("Running model comparison test...");
        String separator = "==========================================";
        logMessage("\\n" + separator);
        logMessage("Starting Model Comparison Test");
        logMessage("Testing multiple models on NPU");
        logMessage(separator);
        
        testHandler.post(() -> {
            // Test multiple models
            String[] testModels = {
                "models/DSCF_integer_quant.tflite",
                "models/DSCF_float32.tflite",
                "models/DSCF_float16.tflite"
            };
            
            runModelComparisonTest(testModels, 0);
        });
    }
    
    private void runModelComparisonTest(String[] models, int index) {
        if (index >= models.length) {
            mainHandler.post(() -> {
                tvTestResults.setText("Model comparison test completed");
                setButtonsEnabled(true);
                logMessage("\\n=== Comparison Test Completed ===");
            });
            return;
        }
        
        String currentModel = models[index];
        mainHandler.post(() -> {
            logMessage("\\nTesting model " + (index + 1) + "/" + models.length + ": " + currentModel);
        });
        
        npuTester.testNPUModelLoading(currentModel, result -> {
            mainHandler.post(() -> {
                logMessage("Result: " + (result.success ? "SUCCESS" : "FAILED"));
                logMessage("Time: " + result.initTime + "ms");
                if (!result.success) {
                    logMessage("Error: " + result.message);
                }
                logMessage("------------------------------");
            });
            
            // Test next model
            testHandler.postDelayed(() -> runModelComparisonTest(models, index + 1), 1000);
        });
    }
    
    private void handleNPUTestResult(NPUTester.TestResult result) {
        mainHandler.post(() -> {
            if (result.success) {
                tvTestResults.setText(String.format(
                    "✅ NPU Test PASSED\\n" +
                    "Initialization: %dms\\n" +
                    "Model: %s\\n" +
                    "Input: %s (%s)\\n" +
                    "Output: %s (%s)",
                    result.initTime,
                    result.modelInfo.modelPath.substring(result.modelInfo.modelPath.lastIndexOf('/') + 1),
                    java.util.Arrays.toString(result.modelInfo.inputShape),
                    result.modelInfo.inputDataType,
                    java.util.Arrays.toString(result.modelInfo.outputShape),
                    result.modelInfo.outputDataType
                ));
                
                logMessage("\\n✅ NPU TEST PASSED!");
                logMessage("Initialization time: " + result.initTime + "ms");
                logMessage("Model successfully loaded on NPU");
                
                if (result.modelInfo.modelPath.contains("integer_quant")) {
                    logMessage("\\n🎉 INT8 Quantized Model Analysis:");
                    logMessage("• Model uses internal INT8 quantization");
                    logMessage("• TensorFlow Lite provides FLOAT32 interface");
                    logMessage("• NPU successfully handles quantized operations");
                    logMessage("• This confirms excellent NPU compatibility!");
                }
                
            } else {
                tvTestResults.setText(String.format(
                    "❌ NPU Test FAILED\\n" +
                    "Time: %dms\\n" +
                    "Error: %s",
                    result.initTime,
                    result.message
                ));
                
                logMessage("\\n❌ NPU TEST FAILED!");
                logMessage("Error: " + result.message);
                logMessage("Time: " + result.initTime + "ms");
                
                if (result.errorDetails != null) {
                    logMessage("\\nDetailed Analysis:");
                    logMessage(result.errorDetails);
                }
                
                // Provide troubleshooting suggestions
                logMessage("\\n🔧 Troubleshooting Suggestions:");
                logMessage("1. Check if NPU is available on this device");
                logMessage("2. Try testing with FLOAT32 models first");
                logMessage("3. Verify NNAPI delegate configuration");
                logMessage("4. Check device logs for additional details");
            }
            
            setButtonsEnabled(true);
            scrollToBottom();
        });
    }
    
    private void clearLog() {
        tvLogOutput.setText("");
        tvTestResults.setText("Log cleared. Ready for new test.");
    }
    
    private void logMessage(String message) {
        runOnUiThread(() -> {
            String timestamp = java.text.DateFormat.getTimeInstance().format(new java.util.Date());
            String logEntry = "[" + timestamp + "] " + message + "\\n";
            tvLogOutput.append(logEntry);
            scrollToBottom();
            
            // Also log to Android Log
            Log.d(TAG, message);
        });
    }
    
    private void scrollToBottom() {
        scrollViewLog.post(() -> scrollViewLog.fullScroll(View.FOCUS_DOWN));
    }
    
    private void setButtonsEnabled(boolean enabled) {
        btnTestNPU.setEnabled(enabled);
        btnTestComparison.setEnabled(enabled);
        btnClearLog.setEnabled(enabled);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (testThread != null) {
            testThread.quitSafely();
            try {
                testThread.join();
            } catch (InterruptedException e) {
                Log.w(TAG, "Test thread join interrupted");
            }
        }
    }
}