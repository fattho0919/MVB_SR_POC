package com.example.sr_poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.example.sr_poc.utils.Constants;

public class ConfigManager {
    
    private static final String TAG = "ConfigManager";
    
    private static ConfigManager instance;
    private JSONObject config;
    private Context context;
    
    // Cached values for performance
    private String defaultModelPath;
    private List<String> alternativeModels;
    
    // Dynamic model selection
    private String selectedModelPath = null;
    private SharedPreferences preferences;
    private List<String> discoveredModels = new ArrayList<>();
    private int expectedScaleFactor;
    private int channels;
    private int defaultNumThreads;
    private boolean useXnnpack;
    private boolean allowFp16Precision;
    private boolean useNnapi;
    private int overlapPixels;
    private double memoryThresholdPercentage;
    private int maxInputSizeWithoutTiling;
    private int forceTilingAboveMb;
    private int lowMemoryWarningMb;
    private boolean gcAfterInference;
    private boolean enableMemoryLogging;
    private boolean useDirectByteBuffer;
    private int directMemorySizeMb;
    private boolean defaultTilingEnabled;
    private boolean showDetailedTiming;
    private boolean showMemoryStats;
    private boolean enableVerboseLogging;
    private boolean logModelShapes;
    private boolean logPerformanceStats;
    private boolean enableBufferValidation;
    
    // GPU optimization parameters
    private String gpuInferencePreference;
    private boolean gpuPrecisionLossAllowed;
    private String gpuWaitType;
    private String gpuBackend;
    private boolean enableQuantizedInference;
    private boolean experimentalGpuOptimizations;
    
    // NPU optimization parameters
    private boolean enableNpu;
    private boolean allowFp16OnNpu;
    private String npuAcceleratorName;
    private boolean useNpuForQuantized;
    
    // Buffer pool configuration (Story 1.2)
    private boolean useBufferPool;
    private int maxPoolSizeMb;
    private int primaryPoolSizeMb;
    private int tilePoolSizeMb;
    private boolean preallocationEnabled;
    private int maxBuffersPerSize;
    private boolean enablePoolMetrics;
    
    // Bitmap pool configuration (Story 1.3)
    private boolean useBitmapPool;
    private int bitmapPoolMaxSizeMb;
    private int maxBitmapsPerSize;
    
    private ConfigManager(Context context) {
        this.context = context.getApplicationContext();
        loadConfig();
        initPreferences();
    }
    
    public static synchronized ConfigManager getInstance(Context context) {
        if (instance == null) {
            instance = new ConfigManager(context);
        }
        return instance;
    }
    
    private void loadConfig() {
        try {
            String configJson = loadJsonFromAssets();
            config = new JSONObject(configJson);
            cacheConfigValues();
            Log.d(TAG, "Configuration loaded successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load configuration, using defaults", e);
            useDefaultValues();
        }
    }
    
    // Initialize preferences and discover models dynamically
    private void initPreferences() {
        preferences = context.getSharedPreferences("sr_prefs", Context.MODE_PRIVATE);
        discoverAvailableModels();
        selectedModelPath = preferences.getString("selected_model", defaultModelPath);
        
        // Validate selected model still exists
        if (!discoveredModels.contains(selectedModelPath)) {
            selectedModelPath = !discoveredModels.isEmpty() ? 
                discoveredModels.get(0) : defaultModelPath;
        }
    }
    
    // Discover all .tflite files in assets/models folder
    private void discoverAvailableModels() {
        discoveredModels.clear();
        try {
            String[] modelFiles = context.getAssets().list("models");
            if (modelFiles != null) {
                for (String file : modelFiles) {
                    if (file.endsWith(".tflite")) {
                        discoveredModels.add("models/" + file);
                        Log.d(TAG, "Discovered model: " + file);
                    }
                }
            }
            // Sort models by filename for consistent ordering
            Collections.sort(discoveredModels);
        } catch (IOException e) {
            Log.e(TAG, "Failed to discover models", e);
            // Fallback to default if discovery fails
            if (discoveredModels.isEmpty() && alternativeModels != null) {
                discoveredModels.addAll(alternativeModels);
            }
        }
    }
    
    public List<String> getAvailableModels() {
        return new ArrayList<>(discoveredModels);
    }
    
    public String getModelDisplayName(String modelPath) {
        // Return full filename with extension
        if (modelPath == null) return "Unknown";
        int lastSlash = modelPath.lastIndexOf('/');
        return lastSlash >= 0 ? modelPath.substring(lastSlash + 1) : modelPath;
    }
    
    public String getSelectedModelPath() {
        return selectedModelPath != null ? selectedModelPath : defaultModelPath;
    }
    
    public void setSelectedModelPath(String modelPath) {
        if (discoveredModels.contains(modelPath)) {
            selectedModelPath = modelPath;
            preferences.edit().putString("selected_model", modelPath).apply();
            Log.d(TAG, "Model changed to: " + getModelDisplayName(modelPath));
        }
    }
    
    private String loadJsonFromAssets() throws IOException {
        InputStream inputStream = context.getAssets().open(Constants.CONFIG_FILE_PATH);
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder stringBuilder = new StringBuilder();
        String line;
        
        while ((line = reader.readLine()) != null) {
            stringBuilder.append(line);
        }
        
        reader.close();
        inputStream.close();
        
        return stringBuilder.toString();
    }
    
    private void cacheConfigValues() throws JSONException {
        // Model configuration
        JSONObject modelConfig = config.getJSONObject("model");
        defaultModelPath = modelConfig.getString("default_model_path");
        expectedScaleFactor = modelConfig.getInt("expected_scale_factor");
        channels = modelConfig.getInt("channels");
        
        // Alternative models
        alternativeModels = new ArrayList<>();
        JSONArray altModelsArray = modelConfig.getJSONArray("alternative_models");
        for (int i = 0; i < altModelsArray.length(); i++) {
            alternativeModels.add(altModelsArray.getString(i));
        }
        
        // Processing configuration
        JSONObject processingConfig = config.getJSONObject("processing");
        defaultNumThreads = processingConfig.getInt("default_num_threads");
        useXnnpack = processingConfig.getBoolean("use_xnnpack");
        allowFp16Precision = processingConfig.getBoolean("allow_fp16_precision");
        useNnapi = processingConfig.getBoolean("use_nnapi");
        
        // GPU optimization parameters
        gpuInferencePreference = processingConfig.optString("gpu_inference_preference", "FAST_SINGLE_ANSWER");
        gpuPrecisionLossAllowed = processingConfig.optBoolean("gpu_precision_loss_allowed", true);
        gpuWaitType = processingConfig.optString("gpu_wait_type", "PASSIVE");
        gpuBackend = processingConfig.optString("gpu_backend", "OPENCL");
        enableQuantizedInference = processingConfig.optBoolean("enable_quantized_inference", false);
        experimentalGpuOptimizations = processingConfig.optBoolean("experimental_gpu_optimizations", true);
        
        // NPU optimization parameters
        JSONObject npuConfig = config.optJSONObject("npu");
        if (npuConfig != null) {
            enableNpu = npuConfig.optBoolean("enable_npu", true);
            allowFp16OnNpu = npuConfig.optBoolean("allow_fp16_on_npu", true);
            npuAcceleratorName = npuConfig.optString("npu_accelerator_name", "");
            useNpuForQuantized = npuConfig.optBoolean("use_npu_for_quantized", true);
        } else {
            // Default NPU values if config section doesn't exist
            enableNpu = true;
            allowFp16OnNpu = true;
            npuAcceleratorName = "";
            useNpuForQuantized = true;
        }
        
        // Tiling configuration
        JSONObject tilingConfig = config.getJSONObject("tiling");
        overlapPixels = tilingConfig.getInt("overlap_pixels");
        memoryThresholdPercentage = tilingConfig.getDouble("memory_threshold_percentage");
        maxInputSizeWithoutTiling = tilingConfig.getInt("max_input_size_without_tiling");
        forceTilingAboveMb = tilingConfig.getInt("force_tiling_above_mb");
        
        // Memory configuration
        JSONObject memoryConfig = config.getJSONObject("memory");
        lowMemoryWarningMb = memoryConfig.getInt("low_memory_warning_mb");
        gcAfterInference = memoryConfig.getBoolean("gc_after_inference");
        enableMemoryLogging = memoryConfig.getBoolean("enable_memory_logging");
        useDirectByteBuffer = memoryConfig.optBoolean("use_direct_byte_buffer", false);
        directMemorySizeMb = memoryConfig.optInt("direct_memory_size_mb", 512);
        
        // Buffer pool configuration (Story 1.2)
        JSONObject bufferPoolConfig = config.optJSONObject("buffer_pool");
        if (bufferPoolConfig != null) {
            useBufferPool = bufferPoolConfig.optBoolean("use_buffer_pool", false);
            maxPoolSizeMb = bufferPoolConfig.optInt("max_pool_size_mb", 512);
            primaryPoolSizeMb = bufferPoolConfig.optInt("primary_pool_size_mb", 256);
            tilePoolSizeMb = bufferPoolConfig.optInt("tile_pool_size_mb", 128);
            preallocationEnabled = bufferPoolConfig.optBoolean("preallocation_enabled", true);
            maxBuffersPerSize = bufferPoolConfig.optInt("max_buffers_per_size", 4);
            enablePoolMetrics = bufferPoolConfig.optBoolean("enable_pool_metrics", true);
        } else {
            // Default buffer pool values if config section doesn't exist
            useBufferPool = false;
            maxPoolSizeMb = 512;
            primaryPoolSizeMb = 256;
            tilePoolSizeMb = 128;
            preallocationEnabled = true;
            maxBuffersPerSize = 4;
            enablePoolMetrics = true;
        }
        
        // Bitmap pool configuration (Story 1.3)
        JSONObject bitmapPoolConfig = config.optJSONObject("bitmap_pool");
        if (bitmapPoolConfig != null) {
            useBitmapPool = bitmapPoolConfig.optBoolean("enabled", false);
            bitmapPoolMaxSizeMb = bitmapPoolConfig.optInt("max_pool_size_mb", 64);
            maxBitmapsPerSize = bitmapPoolConfig.optInt("max_bitmaps_per_size", 3);
        } else {
            // Default bitmap pool values if config section doesn't exist
            useBitmapPool = false;
            bitmapPoolMaxSizeMb = 64;
            maxBitmapsPerSize = 3;
        }
        
        // UI configuration
        JSONObject uiConfig = config.getJSONObject("ui");
        defaultTilingEnabled = uiConfig.getBoolean("default_tiling_enabled");
        showDetailedTiming = uiConfig.getBoolean("show_detailed_timing");
        showMemoryStats = uiConfig.getBoolean("show_memory_stats");
        
        // Debugging configuration
        JSONObject debugConfig = config.getJSONObject("debugging");
        enableVerboseLogging = debugConfig.getBoolean("enable_verbose_logging");
        logModelShapes = debugConfig.getBoolean("log_model_shapes");
        logPerformanceStats = debugConfig.getBoolean("log_performance_stats");
        enableBufferValidation = debugConfig.getBoolean("enable_buffer_validation");
    }
    
    private void useDefaultValues() {
        // Fallback default values
        defaultModelPath = "models/DSCF_float32.tflite";
        alternativeModels = new ArrayList<>();
        expectedScaleFactor = 4;
        channels = 3;
        defaultNumThreads = 4;
        useXnnpack = true;
        allowFp16Precision = true;
        useNnapi = true;
        overlapPixels = 32;
        memoryThresholdPercentage = 0.6;
        maxInputSizeWithoutTiling = 2048;
        forceTilingAboveMb = 500;
        lowMemoryWarningMb = 100;
        gcAfterInference = false;
        enableMemoryLogging = true;
        useDirectByteBuffer = false;
        directMemorySizeMb = 512;
        
        // Buffer pool defaults (Story 1.2)
        useBufferPool = false;
        maxPoolSizeMb = 512;
        primaryPoolSizeMb = 256;
        tilePoolSizeMb = 128;
        preallocationEnabled = true;
        maxBuffersPerSize = 4;
        enablePoolMetrics = true;
        
        // Bitmap pool defaults (Story 1.3)
        useBitmapPool = false;
        bitmapPoolMaxSizeMb = 64;
        maxBitmapsPerSize = 3;
        
        defaultTilingEnabled = true;
        showDetailedTiming = true;
        showMemoryStats = true;
        enableVerboseLogging = false;
        logModelShapes = true;
        logPerformanceStats = true;
        enableBufferValidation = false;
        
        // GPU optimization defaults
        gpuInferencePreference = "FAST_SINGLE_ANSWER";
        gpuPrecisionLossAllowed = true;
        gpuWaitType = "PASSIVE";
        gpuBackend = "OPENCL";
        enableQuantizedInference = false;
        experimentalGpuOptimizations = true;
        
        // NPU optimization defaults
        enableNpu = true;
        allowFp16OnNpu = true;
        npuAcceleratorName = "";
        useNpuForQuantized = true;
    }
    
    // Getter methods for cached values
    public String getDefaultModelPath() { return defaultModelPath; }
    public List<String> getAlternativeModels() { return alternativeModels; }
    public int getExpectedScaleFactor() { return expectedScaleFactor; }
    public int getChannels() { return channels; }
    public int getDefaultNumThreads() { return defaultNumThreads; }
    public boolean isUseXnnpack() { return useXnnpack; }
    public boolean isAllowFp16Precision() { return allowFp16Precision; }
    public boolean isUseNnapi() { return useNnapi; }
    public int getOverlapPixels() { return overlapPixels; }
    public double getMemoryThresholdPercentage() { return memoryThresholdPercentage; }
    public int getMaxInputSizeWithoutTiling() { return maxInputSizeWithoutTiling; }
    public int getForceTilingAboveMb() { return forceTilingAboveMb; }
    public int getLowMemoryWarningMb() { return lowMemoryWarningMb; }
    public boolean isGcAfterInference() { return gcAfterInference; }
    public boolean isEnableMemoryLogging() { return enableMemoryLogging; }
    public boolean useDirectByteBuffer() { return useDirectByteBuffer; }
    public int getDirectMemorySizeMb() { return directMemorySizeMb; }
    public boolean isDefaultTilingEnabled() { return defaultTilingEnabled; }
    public boolean isShowDetailedTiming() { return showDetailedTiming; }
    public boolean isShowMemoryStats() { return showMemoryStats; }
    public boolean isEnableVerboseLogging() { return enableVerboseLogging; }
    public boolean isLogModelShapes() { return logModelShapes; }
    public boolean isLogPerformanceStats() { return logPerformanceStats; }
    public boolean isEnableBufferValidation() { return enableBufferValidation; }
    
    // GPU optimization getters
    public String getGpuInferencePreference() { return gpuInferencePreference; }
    public boolean isGpuPrecisionLossAllowed() { return gpuPrecisionLossAllowed; }
    public String getGpuWaitType() { return gpuWaitType; }
    public String getGpuBackend() { return gpuBackend; }
    public boolean isEnableQuantizedInference() { return enableQuantizedInference; }
    public boolean isExperimentalGpuOptimizations() { return experimentalGpuOptimizations; }
    
    // NPU optimization getters
    public boolean isEnableNpu() { return enableNpu; }
    public boolean isAllowFp16OnNpu() { return allowFp16OnNpu; }
    public String getNpuAcceleratorName() { return npuAcceleratorName; }
    public boolean isUseNpuForQuantized() { return useNpuForQuantized; }
    
    // Buffer pool getters (Story 1.2)
    public boolean useBufferPool() { return useBufferPool; }
    public int getMaxPoolSizeMb() { return maxPoolSizeMb; }
    public int getPrimaryPoolSizeMb() { return primaryPoolSizeMb; }
    public int getTilePoolSizeMb() { return tilePoolSizeMb; }
    public boolean isPreallocationEnabled() { return preallocationEnabled; }
    public int getMaxBuffersPerSize() { return maxBuffersPerSize; }
    public boolean isEnablePoolMetrics() { return enablePoolMetrics; }
    
    // Bitmap pool getters (Story 1.3)
    public boolean useBitmapPool() { return useBitmapPool; }
    public int getBitmapPoolMaxSizeMb() { return bitmapPoolMaxSizeMb; }
    public int getMaxBitmapsPerSize() { return maxBitmapsPerSize; }
    
    // Config access for BitmapPoolManager
    public JSONObject getConfig() { return config; }
    
    // Runtime configuration update methods
    public void setDefaultTilingEnabled(boolean enabled) {
        this.defaultTilingEnabled = enabled;
        updateConfigValue("ui", "default_tiling_enabled", enabled);
    }
    
    public void setEnableVerboseLogging(boolean enabled) {
        this.enableVerboseLogging = enabled;
        updateConfigValue("debugging", "enable_verbose_logging", enabled);
    }
    
    public void setMemoryThresholdPercentage(double percentage) {
        this.memoryThresholdPercentage = percentage;
        updateConfigValue("tiling", "memory_threshold_percentage", percentage);
    }
    
    private void updateConfigValue(String section, String key, Object value) {
        try {
            if (config != null) {
                JSONObject sectionObj = config.getJSONObject(section);
                if (value instanceof Boolean) {
                    sectionObj.put(key, (Boolean) value);
                } else if (value instanceof Double) {
                    sectionObj.put(key, (Double) value);
                } else if (value instanceof Integer) {
                    sectionObj.put(key, (Integer) value);
                } else if (value instanceof String) {
                    sectionObj.put(key, (String) value);
                }
                Log.d(TAG, "Updated config: " + section + "." + key + " = " + value);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to update config value: " + section + "." + key, e);
        }
    }
    
    public void reloadConfig() {
        Log.d(TAG, "Reloading configuration...");
        loadConfig();
    }
    
    public String getConfigSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("SuperResolution Configuration Summary:\n");
        summary.append("  Model: ").append(defaultModelPath).append("\n");
        summary.append("  Scale Factor: ").append(expectedScaleFactor).append("x\n");
        summary.append("  Threads: ").append(defaultNumThreads).append("\n");
        summary.append("  NNAPI: ").append(useNnapi ? "✓" : "✗").append("\n");
        summary.append("  XNNPACK: ").append(useXnnpack ? "✓" : "✗").append("\n");
        summary.append("  GPU Preference: ").append(gpuInferencePreference).append("\n");
        summary.append("  Quantized Inference: ").append(enableQuantizedInference ? "✓" : "✗").append("\n");
        summary.append("  Experimental GPU: ").append(experimentalGpuOptimizations ? "✓" : "✗").append("\n");
        summary.append("  NPU Enabled: ").append(enableNpu ? "✓" : "✗").append("\n");
        summary.append("  NPU FP16: ").append(allowFp16OnNpu ? "✓" : "✗").append("\n");
        summary.append("  Tiling Overlap: ").append(overlapPixels).append("px\n");
        summary.append("  Memory Threshold: ").append((int)(memoryThresholdPercentage * 100)).append("%\n");
        summary.append("  Max Size (no tiling): ").append(maxInputSizeWithoutTiling).append("px\n");
        summary.append("  Default Tiling: ").append(defaultTilingEnabled ? "✓" : "✗").append("\n");
        return summary.toString();
    }
}