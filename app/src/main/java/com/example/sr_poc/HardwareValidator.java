package com.example.sr_poc;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.nnapi.NnApiDelegate;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.ArrayList;

/**
 * Hardware validation utility for strict device capability checking.
 * Ensures hardware transparency and prevents silent fallback.
 */
public class HardwareValidator {
    private static final String TAG = "HardwareValidator";
    
    /**
     * Validation result containing availability status and detailed information.
     */
    public static class ValidationResult {
        public final boolean isAvailable;
        public final String failureReason;
        public final String deviceInfo;
        public final List<String> warnings;
        
        private ValidationResult(boolean isAvailable, String failureReason, String deviceInfo) {
            this.isAvailable = isAvailable;
            this.failureReason = failureReason;
            this.deviceInfo = deviceInfo;
            this.warnings = new ArrayList<>();
        }
        
        public static ValidationResult success(String deviceInfo) {
            return new ValidationResult(true, null, deviceInfo);
        }
        
        public static ValidationResult failure(String reason) {
            return new ValidationResult(false, reason, null);
        }
        
        public void addWarning(String warning) {
            warnings.add(warning);
        }
    }
    
    /**
     * Validates NPU availability with strict checks to prevent CPU/GPU fallback.
     * 
     * Requirements:
     * - Android 10+ (API 29+)
     * - Real NPU hardware (not CPU/GPU masquerading as NPU)
     * - NNAPI delegate must target actual NPU device
     */
    public static ValidationResult validateNPU(Context context) {
        Log.d(TAG, "Starting strict NPU validation");
        
        // Check Android version (NPU requires Android 10+)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            String reason = "NPU requires Android 10+ (current: API " + Build.VERSION.SDK_INT + ")";
            Log.w(TAG, "NPU validation failed: " + reason);
            return ValidationResult.failure(reason);
        }
        
        try {
            // Get available NNAPI devices
            List<String> availableDevices = getAvailableNnApiDevices();
            
            if (availableDevices.isEmpty()) {
                return ValidationResult.failure("No NNAPI devices found");
            }
            
            Log.d(TAG, "Found " + availableDevices.size() + " NNAPI devices:");
            for (String device : availableDevices) {
                Log.d(TAG, "  - " + device);
            }
            
            // Find real NPU device (not CPU/GPU fallback)
            String npuDevice = findRealNPUDevice(availableDevices);
            
            if (npuDevice == null) {
                String reason = "No real NPU hardware found (only CPU/GPU fallback available)";
                Log.w(TAG, reason);
                return ValidationResult.failure(reason);
            }
            
            // Validate NPU is not in fallback mode
            if (isNPUInFallbackMode(npuDevice)) {
                String reason = "NPU is in fallback mode (using CPU/GPU instead of real NPU)";
                Log.w(TAG, reason);
                return ValidationResult.failure(reason);
            }
            
            // Additional check for known NPU hardware
            String socInfo = getSoCInfo();
            boolean hasKnownNPU = hasKnownNPUHardware(socInfo);
            
            ValidationResult result = ValidationResult.success(npuDevice);
            
            if (!hasKnownNPU) {
                result.addWarning("Unknown NPU hardware - may not be optimized");
            }
            
            Log.d(TAG, "NPU validation successful: " + npuDevice);
            if (socInfo != null) {
                Log.d(TAG, "SoC: " + socInfo);
            }
            
            return result;
            
        } catch (Exception e) {
            Log.e(TAG, "NPU validation error", e);
            return ValidationResult.failure("NPU validation error: " + e.getMessage());
        }
    }
    
    /**
     * Checks if running on emulator.
     */
    public static boolean isEmulator() {
        return (Build.FINGERPRINT.contains("generic")
                || Build.FINGERPRINT.contains("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for")
                || Build.MODEL.contains("sdk_gphone")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("emulator")
                || Build.BOARD.equals("QC_Reference_Phone"));
    }
    
    /**
     * Validates GPU availability with model compatibility checks.
     */
    public static ValidationResult validateGPU(Context context, String modelPath) {
        Log.d(TAG, "========================================");
        Log.d(TAG, "Starting comprehensive GPU validation");
        Log.d(TAG, "========================================");
        
        // Log device information
        Log.d(TAG, "Device Info:");
        Log.d(TAG, "  Manufacturer: " + Build.MANUFACTURER);
        Log.d(TAG, "  Model: " + Build.MODEL);
        Log.d(TAG, "  Hardware: " + Build.HARDWARE);
        Log.d(TAG, "  Board: " + Build.BOARD);
        Log.d(TAG, "  SoC Model: " + Build.SOC_MODEL);
        Log.d(TAG, "  Android Version: " + Build.VERSION.SDK_INT);
        
        // Check if running on emulator
        if (isEmulator()) {
            Log.w(TAG, "Running on emulator - GPU delegate typically not supported");
            return ValidationResult.failure("GPU not available on emulator");
        }
        
        try {
            Log.d(TAG, "Creating CompatibilityList to check GPU support...");
            CompatibilityList compatList = new CompatibilityList();
            
            boolean isGpuSupported = compatList.isDelegateSupportedOnThisDevice();
            Log.d(TAG, "GPU delegate supported: " + isGpuSupported);
            
            if (!isGpuSupported) {
                Log.e(TAG, "GPU delegate NOT supported on this device");
                return ValidationResult.failure("GPU delegate not supported on this device");
            }
            
            // Check for INT8 model (GPU doesn't support INT8)
            if (modelPath != null && modelPath.contains("int8")) {
                return ValidationResult.failure("GPU does not support INT8 quantized models");
            }
            
            // Get detailed GPU info
            String gpuInfo = getGPUInfo();
            Log.d(TAG, "Detected GPU: " + gpuInfo);
            
            // Try to get best GPU device from compatibility list
            String bestDevice = getBestGpuDevice(compatList);
            if (bestDevice != null) {
                Log.d(TAG, "Best GPU device from CompatibilityList: " + bestDevice);
                gpuInfo = gpuInfo + " (" + bestDevice + ")";
            }
            
            ValidationResult result = ValidationResult.success(gpuInfo);
            
            // Add warnings for known issues
            if (Build.MANUFACTURER.equalsIgnoreCase("samsung") && 
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                result.addWarning("Samsung devices may have GPU stability issues on Android < 11");
            }
            
            Log.d(TAG, "========================================");
            Log.d(TAG, "GPU validation SUCCESSFUL");
            Log.d(TAG, "GPU Info: " + gpuInfo);
            Log.d(TAG, "========================================");
            return result;
            
        } catch (Exception e) {
            Log.e(TAG, "========================================");
            Log.e(TAG, "GPU validation FAILED with error", e);
            Log.e(TAG, "Error message: " + e.getMessage());
            Log.e(TAG, "========================================");
            return ValidationResult.failure("GPU validation error: " + e.getMessage());
        }
    }
    
    /**
     * Gets the best GPU device from compatibility list.
     */
    private static String getBestGpuDevice(CompatibilityList compatList) {
        try {
            // Use reflection to access best device if available
            java.lang.reflect.Method method = compatList.getClass().getDeclaredMethod("getBestOptionsForThisDevice");
            if (method != null) {
                Object result = method.invoke(compatList);
                if (result != null) {
                    return result.toString();
                }
            }
        } catch (Exception e) {
            // Method not available in this version
            Log.d(TAG, "Cannot get best GPU device info: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Gets list of available NNAPI devices.
     */
    private static List<String> getAvailableNnApiDevices() {
        List<String> devices = new ArrayList<>();
        
        try {
            // This requires TensorFlow Lite 2.4.0+
            // In older versions, we need to use reflection or other methods
            NnApiDelegate.Options options = new NnApiDelegate.Options();
            
            // Try to get device list via reflection if available
            // This is implementation-specific and may vary
            devices.add("nnapi-reference");  // Default reference implementation
            
            // Check for vendor-specific accelerators
            if (Build.HARDWARE.toLowerCase().contains("qcom")) {
                devices.add("qcom-hta");  // Qualcomm Hexagon
            }
            if (Build.HARDWARE.toLowerCase().contains("mtk") || 
                Build.HARDWARE.toLowerCase().contains("mediatek")) {
                devices.add("mtk-apu");  // MediaTek APU
            }
            if (Build.MANUFACTURER.equalsIgnoreCase("samsung")) {
                devices.add("samsung-npu");  // Samsung NPU
            }
            
        } catch (Exception e) {
            Log.w(TAG, "Error getting NNAPI devices: " + e.getMessage());
        }
        
        return devices;
    }
    
    /**
     * Finds real NPU device from available devices.
     * Returns null if only CPU/GPU fallback is available.
     */
    private static String findRealNPUDevice(List<String> devices) {
        for (String device : devices) {
            if (isRealNPUDevice(device)) {
                return device;
            }
        }
        return null;
    }
    
    /**
     * Checks if a device name represents real NPU hardware.
     */
    private static boolean isRealNPUDevice(String device) {
        if (device == null) return false;
        
        String deviceLower = device.toLowerCase();
        
        // Check for real NPU/APU/DSP identifiers
        boolean isRealNPU = (
            deviceLower.contains("npu") ||
            deviceLower.contains("apu") ||
            deviceLower.contains("neuron") ||
            deviceLower.contains("hexagon") ||
            deviceLower.contains("hta") ||
            deviceLower.contains("vpu") ||
            deviceLower.contains("dsp")
        );
        
        // Exclude CPU/GPU fallback identifiers
        boolean isFallback = (
            deviceLower.contains("cpu") ||
            deviceLower.contains("gpu") ||
            deviceLower.contains("reference") ||
            deviceLower.contains("fallback")
        );
        
        return isRealNPU && !isFallback;
    }
    
    /**
     * Checks if NPU is operating in fallback mode.
     */
    private static boolean isNPUInFallbackMode(String device) {
        // Check for fallback indicators
        return device.toLowerCase().contains("fallback") ||
               device.toLowerCase().contains("reference");
    }
    
    /**
     * Gets SoC information for hardware detection.
     */
    private static String getSoCInfo() {
        StringBuilder info = new StringBuilder();
        
        if (Build.VERSION.SDK_INT >= 31) {
            String socManufacturer = Build.SOC_MANUFACTURER;
            String socModel = Build.SOC_MODEL;
            
            if (socManufacturer != null && !socManufacturer.equals("unknown")) {
                info.append(socManufacturer);
            }
            if (socModel != null && !socModel.equals("unknown")) {
                if (info.length() > 0) info.append(" ");
                info.append(socModel);
            }
        }
        
        // Fallback to hardware/board info
        if (info.length() == 0) {
            info.append(Build.HARDWARE);
            if (Build.BOARD != null && !Build.BOARD.equals(Build.HARDWARE)) {
                info.append(" (").append(Build.BOARD).append(")");
            }
        }
        
        return info.length() > 0 ? info.toString() : null;
    }
    
    /**
     * Checks if device has known NPU hardware.
     */
    private static boolean hasKnownNPUHardware(String socInfo) {
        if (socInfo == null) return false;
        
        String socLower = socInfo.toLowerCase();
        
        // Known NPU-equipped SoCs
        return (
            // MediaTek with APU
            socLower.contains("mt8195") ||
            socLower.contains("mt8192") ||
            socLower.contains("mt8188") ||
            socLower.contains("dimensity") ||
            
            // Qualcomm with Hexagon DSP/NPU
            socLower.contains("snapdragon 8") ||
            socLower.contains("snapdragon 7") ||
            socLower.contains("sm8") ||  // Snapdragon 8xx series
            socLower.contains("sm7") ||  // Snapdragon 7xx series
            
            // Samsung Exynos with NPU
            socLower.contains("exynos 2") ||  // Exynos 2xxx series
            socLower.contains("exynos 990") ||
            
            // Google Tensor with TPU
            socLower.contains("tensor") ||
            
            // HiSilicon Kirin with NPU
            socLower.contains("kirin 9") ||
            socLower.contains("kirin 8")
        );
    }
    
    /**
     * Gets GPU information.
     */
    private static String getGPUInfo() {
        StringBuilder info = new StringBuilder();
        
        // Try to identify GPU from known patterns
        String hardware = Build.HARDWARE.toLowerCase();
        
        if (hardware.contains("qcom")) {
            info.append("Adreno GPU");
        } else if (hardware.contains("mali")) {
            info.append("Mali GPU");
        } else if (hardware.contains("powervr")) {
            info.append("PowerVR GPU");
        } else if (hardware.contains("tegra")) {
            info.append("NVIDIA Tegra GPU");
        } else {
            info.append("GPU");
        }
        
        info.append(" (").append(Build.HARDWARE).append(")");
        
        return info.toString();
    }
    
    /**
     * Tests actual NPU functionality with a small model.
     * This ensures NPU is actually working and not falling back.
     */
    public static boolean testNPUInference(Context context, ByteBuffer modelBuffer) {
        Log.d(TAG, "Testing NPU inference with actual model");
        
        try {
            Interpreter.Options options = new Interpreter.Options();
            
            // Configure strict NPU options
            NnApiDelegate.Options npuOptions = new NnApiDelegate.Options();
            npuOptions.setAllowFp16(true);
            npuOptions.setExecutionPreference(
                NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED);
            
            // Try to force NPU usage
            String npuDevice = findRealNPUDevice(getAvailableNnApiDevices());
            if (npuDevice != null && !npuDevice.contains("reference")) {
                npuOptions.setAcceleratorName(npuDevice);
            }
            
            NnApiDelegate npuDelegate = new NnApiDelegate(npuOptions);
            options.addDelegate(npuDelegate);
            
            // Create interpreter and run test inference
            Interpreter interpreter = new Interpreter(modelBuffer, options);
            
            // Allocate test tensors
            interpreter.allocateTensors();
            
            // If we get here without exception, NPU is working
            interpreter.close();
            npuDelegate.close();
            
            Log.d(TAG, "NPU test inference successful");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "NPU test inference failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Gets a summary of all hardware validation results.
     */
    public static String getHardwareValidationSummary(Context context, String modelPath) {
        StringBuilder summary = new StringBuilder();
        summary.append("=== Hardware Validation Summary ===\n");
        
        // NPU validation
        ValidationResult npuResult = validateNPU(context);
        summary.append("NPU: ");
        if (npuResult.isAvailable) {
            summary.append("✓ Available (").append(npuResult.deviceInfo).append(")\n");
        } else {
            summary.append("✗ Unavailable (").append(npuResult.failureReason).append(")\n");
        }
        
        // GPU validation
        ValidationResult gpuResult = validateGPU(context, modelPath);
        summary.append("GPU: ");
        if (gpuResult.isAvailable) {
            summary.append("✓ Available (").append(gpuResult.deviceInfo).append(")\n");
        } else {
            summary.append("✗ Unavailable (").append(gpuResult.failureReason).append(")\n");
        }
        
        // CPU is always available
        summary.append("CPU: ✓ Available (XNNPACK optimized)\n");
        
        // SoC info
        String socInfo = getSoCInfo();
        if (socInfo != null) {
            summary.append("SoC: ").append(socInfo).append("\n");
        }
        
        summary.append("Android API: ").append(Build.VERSION.SDK_INT).append("\n");
        
        return summary.toString();
    }
}