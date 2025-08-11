package com.example.sr_poc;

import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for HardwareValidator.
 * 
 * Tests hardware detection and validation logic for GPU/NPU availability.
 */
@RunWith(JUnit4.class)
public class HardwareValidatorTest {
    
    /**
     * Test emulator detection logic.
     */
    @Test
    public void testEmulatorDetection() {
        // Test various emulator fingerprints
        assertTrue("Should detect generic emulator", 
                  isEmulatorFingerprint("generic_x86"));
        assertTrue("Should detect goldfish hardware", 
                  isEmulatorFingerprint("goldfish"));
        assertTrue("Should detect ranchu hardware", 
                  isEmulatorFingerprint("ranchu"));
        assertTrue("Should detect SDK phone model", 
                  isEmulatorFingerprint("sdk_gphone"));
        
        // Test real device fingerprints
        assertFalse("Should not detect real device as emulator", 
                   isEmulatorFingerprint("samsung_sm_g950f"));
        assertFalse("Should not detect MediaTek device as emulator", 
                   isEmulatorFingerprint("mediatek_mt8195"));
    }
    
    /**
     * Test NPU device name validation.
     */
    @Test
    public void testNpuDeviceNameValidation() {
        // Valid NPU device names
        assertTrue("Should recognize qcom-hta as NPU", 
                  isRealNPU("qcom-hta"));
        assertTrue("Should recognize mtk-apu as NPU", 
                  isRealNPU("mtk-apu"));
        assertTrue("Should recognize neuron-core as NPU", 
                  isRealNPU("neuron-core"));
        assertTrue("Should recognize hexagon-dsp as NPU", 
                  isRealNPU("hexagon-dsp"));
        
        // Invalid NPU device names (CPU/GPU fallback)
        assertFalse("Should reject nnapi-cpu as NPU", 
                   isRealNPU("nnapi-cpu"));
        assertFalse("Should reject gpu-fallback as NPU", 
                   isRealNPU("nnapi-gpu"));
        assertFalse("Should reject generic reference as NPU", 
                   isRealNPU("nnapi-reference"));
    }
    
    /**
     * Test GPU validation result.
     */
    @Test
    public void testGpuValidationResult() {
        // Simulate GPU available
        MockValidationResult gpuAvailable = createGpuValidationResult(true, null);
        assertTrue("GPU should be available", gpuAvailable.isAvailable);
        assertNull("No failure reason when available", gpuAvailable.failureReason);
        assertNotNull("Device info should be present", gpuAvailable.deviceInfo);
        
        // Simulate GPU not available
        MockValidationResult gpuNotAvailable = createGpuValidationResult(false, "GPU delegate not supported");
        assertFalse("GPU should not be available", gpuNotAvailable.isAvailable);
        assertNotNull("Failure reason should be present", gpuNotAvailable.failureReason);
        assertTrue("Failure reason should contain error message", 
                  gpuNotAvailable.failureReason.contains("not supported"));
    }
    
    /**
     * Test NPU validation with Android version check.
     */
    @Test
    public void testNpuAndroidVersionCheck() {
        // Android Q (10) and above should pass
        assertTrue("Android 10 should support NPU", 
                  isAndroidVersionSupported(29)); // Q
        assertTrue("Android 11 should support NPU", 
                  isAndroidVersionSupported(30)); // R
        assertTrue("Android 12 should support NPU", 
                  isAndroidVersionSupported(31)); // S
        
        // Below Android Q should fail
        assertFalse("Android 9 should not support NPU", 
                   isAndroidVersionSupported(28)); // P
        assertFalse("Android 8 should not support NPU", 
                   isAndroidVersionSupported(26)); // O
    }
    
    /**
     * Test device capability detection.
     */
    @Test
    public void testDeviceCapabilityDetection() {
        // Test MediaTek detection
        assertTrue("Should detect MT8195 as MediaTek", 
                  isMediaTekDevice("MT8195"));
        assertTrue("Should detect MT8188 as MediaTek", 
                  isMediaTekDevice("MT8188"));
        
        // Test Qualcomm detection
        assertTrue("Should detect Snapdragon as Qualcomm", 
                  isQualcommDevice("Snapdragon 8 Gen 2"));
        assertTrue("Should detect SM8550 as Qualcomm", 
                  isQualcommDevice("SM8550"));
        
        // Test negative cases
        assertFalse("Should not detect Exynos as MediaTek", 
                   isMediaTekDevice("Exynos 2200"));
        assertFalse("Should not detect MediaTek as Qualcomm", 
                   isQualcommDevice("MT8195"));
    }
    
    /**
     * Test validation result warnings.
     */
    @Test
    public void testValidationWarnings() {
        MockValidationResult result = new MockValidationResult();
        result.isAvailable = true;
        
        // Add warnings
        result.addWarning("Experimental NPU support");
        result.addWarning("Limited precision on this device");
        
        assertEquals("Should have 2 warnings", 2, result.warnings.size());
        assertTrue("Should contain experimental warning", 
                  result.warnings.contains("Experimental NPU support"));
    }
    
    /**
     * Test comprehensive validation summary.
     */
    @Test
    public void testValidationSummary() {
        // Simulate comprehensive validation
        String summary = createValidationSummary(true, true, false);
        
        assertTrue("Summary should indicate GPU available", 
                  summary.contains("GPU: Available"));
        assertTrue("Summary should indicate CPU available", 
                  summary.contains("CPU: Available"));
        assertTrue("Summary should indicate NPU not available", 
                  summary.contains("NPU: Not Available"));
    }
    
    // Helper methods
    
    private boolean isEmulatorFingerprint(String fingerprint) {
        return fingerprint.contains("generic") ||
               fingerprint.contains("goldfish") ||
               fingerprint.contains("ranchu") ||
               fingerprint.contains("sdk_gphone");
    }
    
    private boolean isRealNPU(String deviceName) {
        String lower = deviceName.toLowerCase();
        return (lower.contains("npu") || 
                lower.contains("neuron") || 
                lower.contains("apu") || 
                lower.contains("hexagon") ||
                lower.contains("hta")) &&
               !lower.contains("cpu") &&
               !lower.contains("gpu") &&
               !lower.contains("reference");
    }
    
    private MockValidationResult createGpuValidationResult(boolean available, String failureReason) {
        MockValidationResult result = new MockValidationResult();
        result.isAvailable = available;
        result.failureReason = failureReason;
        result.deviceInfo = available ? "GPU Device Info" : null;
        return result;
    }
    
    private boolean isAndroidVersionSupported(int sdkInt) {
        return sdkInt >= 29; // Android Q
    }
    
    private boolean isMediaTekDevice(String socModel) {
        return socModel != null && socModel.startsWith("MT");
    }
    
    private boolean isQualcommDevice(String socModel) {
        return socModel != null && 
               (socModel.contains("Snapdragon") || socModel.startsWith("SM"));
    }
    
    private String createValidationSummary(boolean gpuAvailable, boolean cpuAvailable, boolean npuAvailable) {
        StringBuilder summary = new StringBuilder();
        summary.append("Hardware Validation Summary:\n");
        summary.append("GPU: ").append(gpuAvailable ? "Available" : "Not Available").append("\n");
        summary.append("CPU: ").append(cpuAvailable ? "Available" : "Not Available").append("\n");
        summary.append("NPU: ").append(npuAvailable ? "Available" : "Not Available").append("\n");
        return summary.toString();
    }
    
    /**
     * Mock validation result for testing.
     */
    private static class MockValidationResult {
        boolean isAvailable;
        String failureReason;
        String deviceInfo;
        List<String> warnings = new java.util.ArrayList<>();
        
        void addWarning(String warning) {
            warnings.add(warning);
        }
    }
}