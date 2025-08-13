package com.example.sr_poc;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;

/**
 * Test activity for GPU detection and verification.
 * Use this to debug GPU initialization issues.
 */
public class GpuTestActivity extends Activity {
    private static final String TAG = "GpuTest";
    private TextView tvResults;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Simple layout with TextView
        tvResults = new TextView(this);
        tvResults.setPadding(20, 20, 20, 20);
        setContentView(tvResults);
        
        runGpuTests();
    }
    
    private void runGpuTests() {
        StringBuilder results = new StringBuilder();
        results.append("GPU Detection Test Results\n");
        results.append("========================================\n\n");
        
        // Test 1: Check CompatibilityList
        results.append("Test 1: GPU Compatibility Check\n");
        try {
            CompatibilityList compatList = new CompatibilityList();
            boolean isSupported = compatList.isDelegateSupportedOnThisDevice();
            results.append("  GPU Delegate Supported: ").append(isSupported).append("\n");
            Log.d(TAG, "GPU Delegate Supported: " + isSupported);
        } catch (Exception e) {
            results.append("  ERROR: ").append(e.getMessage()).append("\n");
            Log.e(TAG, "CompatibilityList error", e);
        }
        results.append("\n");
        
        // Test 2: Try to create GPU delegate with different options
        results.append("Test 2: GPU Delegate Creation\n");
        
        // Test 2a: Default options
        results.append("  2a. Default options: ");
        try {
            GpuDelegate.Options defaultOptions = new GpuDelegate.Options();
            GpuDelegate delegate = new GpuDelegate(defaultOptions);
            results.append("SUCCESS\n");
            Log.d(TAG, "Default GPU delegate created successfully");
            delegate.close();
        } catch (Exception e) {
            results.append("FAILED - ").append(e.getMessage()).append("\n");
            Log.e(TAG, "Default GPU delegate failed", e);
        }
        
        // Test 2b: With inference preference
        results.append("  2b. With FAST_SINGLE_ANSWER: ");
        try {
            GpuDelegate.Options fastOptions = new GpuDelegate.Options();
            fastOptions.setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER);
            GpuDelegate delegate = new GpuDelegate(fastOptions);
            results.append("SUCCESS\n");
            Log.d(TAG, "Fast GPU delegate created successfully");
            delegate.close();
        } catch (Exception e) {
            results.append("FAILED - ").append(e.getMessage()).append("\n");
            Log.e(TAG, "Fast GPU delegate failed", e);
        }
        
        // Test 2c: With precision loss allowed
        results.append("  2c. With precision loss: ");
        try {
            GpuDelegate.Options precisionOptions = new GpuDelegate.Options();
            precisionOptions.setPrecisionLossAllowed(true);
            GpuDelegate delegate = new GpuDelegate(precisionOptions);
            results.append("SUCCESS\n");
            Log.d(TAG, "Precision loss GPU delegate created successfully");
            delegate.close();
        } catch (Exception e) {
            results.append("FAILED - ").append(e.getMessage()).append("\n");
            Log.e(TAG, "Precision loss GPU delegate failed", e);
        }
        
        // Test 2d: Full optimizations
        results.append("  2d. Full optimizations: ");
        try {
            GpuDelegate.Options fullOptions = new GpuDelegate.Options();
            fullOptions.setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER);
            fullOptions.setPrecisionLossAllowed(true);
            GpuDelegate delegate = new GpuDelegate(fullOptions);
            results.append("SUCCESS\n");
            Log.d(TAG, "Fully optimized GPU delegate created successfully");
            delegate.close();
        } catch (Exception e) {
            results.append("FAILED - ").append(e.getMessage()).append("\n");
            Log.e(TAG, "Fully optimized GPU delegate failed", e);
        }
        
        results.append("\n");
        
        // Test 3: Device information
        results.append("Test 3: Device Information\n");
        results.append("  Manufacturer: ").append(android.os.Build.MANUFACTURER).append("\n");
        results.append("  Model: ").append(android.os.Build.MODEL).append("\n");
        results.append("  Hardware: ").append(android.os.Build.HARDWARE).append("\n");
        results.append("  Board: ").append(android.os.Build.BOARD).append("\n");
        results.append("  SoC Model: ").append(android.os.Build.SOC_MODEL).append("\n");
        results.append("  Android Version: ").append(android.os.Build.VERSION.SDK_INT).append("\n");
        
        // Check if emulator
        boolean isEmulator = HardwareValidator.isEmulator();
        results.append("  Is Emulator: ").append(isEmulator).append("\n");
        
        results.append("\n");
        
        // Test 4: Full validation
        results.append("Test 4: Full GPU Validation\n");
        HardwareValidator.ValidationResult validation = HardwareValidator.validateGPU(this, null);
        results.append("  Available: ").append(validation.isAvailable).append("\n");
        if (validation.isAvailable) {
            results.append("  Device Info: ").append(validation.deviceInfo).append("\n");
        } else {
            results.append("  Failure Reason: ").append(validation.failureReason).append("\n");
        }
        if (!validation.warnings.isEmpty()) {
            results.append("  Warnings:\n");
            for (String warning : validation.warnings) {
                results.append("    - ").append(warning).append("\n");
            }
        }
        
        results.append("\n========================================\n");
        results.append("Check logcat for detailed logs\n");
        
        // Display results
        tvResults.setText(results.toString());
        
        // Log summary
        Log.d(TAG, "========================================");
        Log.d(TAG, "GPU Test Complete");
        Log.d(TAG, results.toString());
        Log.d(TAG, "========================================");
    }
}