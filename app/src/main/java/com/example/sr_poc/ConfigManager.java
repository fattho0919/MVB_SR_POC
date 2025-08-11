package com.example.sr_poc;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import com.example.sr_poc.utils.Constants;

public class ConfigManager {
    
    private static final String TAG = "ConfigManager";
    
    private static ConfigManager instance;
    private JSONObject config;
    private Context context;
    
    // Essential cached values
    private String defaultModelPath;
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
    private boolean defaultTilingEnabled;
    
    // GPU optimization parameters
    private String gpuInferencePreference;
    private boolean gpuPrecisionLossAllowed;
    
    // NPU optimization parameters
    private boolean enableNpu;
    private boolean allowFp16OnNpu;
    private String npuAcceleratorName;
    
    private ConfigManager(Context context) {
        this.context = context.getApplicationContext();
        loadConfig();
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
        
        
        // Processing configuration
        JSONObject processingConfig = config.getJSONObject("processing");
        defaultNumThreads = processingConfig.getInt("default_num_threads");
        useXnnpack = processingConfig.getBoolean("use_xnnpack");
        allowFp16Precision = processingConfig.getBoolean("allow_fp16_precision");
        useNnapi = processingConfig.getBoolean("use_nnapi");
        
        // Essential GPU parameters
        gpuInferencePreference = processingConfig.optString("gpu_inference_preference", "FAST_SINGLE_ANSWER");
        gpuPrecisionLossAllowed = processingConfig.optBoolean("gpu_precision_loss_allowed", true);
        
        // Essential NPU parameters
        JSONObject npuConfig = config.optJSONObject("npu");
        if (npuConfig != null) {
            enableNpu = npuConfig.optBoolean("enable_npu", true);
            allowFp16OnNpu = npuConfig.optBoolean("allow_fp16_on_npu", true);
            npuAcceleratorName = npuConfig.optString("npu_accelerator_name", "");
        } else {
            enableNpu = true;
            allowFp16OnNpu = true;
            npuAcceleratorName = "";
        }
        
        // Tiling configuration
        JSONObject tilingConfig = config.getJSONObject("tiling");
        overlapPixels = tilingConfig.getInt("overlap_pixels");
        memoryThresholdPercentage = tilingConfig.getDouble("memory_threshold_percentage");
        maxInputSizeWithoutTiling = tilingConfig.getInt("max_input_size_without_tiling");
        forceTilingAboveMb = tilingConfig.getInt("force_tiling_above_mb");
        
        // Memory and UI configuration
        JSONObject memoryConfig = config.getJSONObject("memory");
        lowMemoryWarningMb = memoryConfig.getInt("low_memory_warning_mb");
        
        JSONObject uiConfig = config.getJSONObject("ui");
        defaultTilingEnabled = uiConfig.getBoolean("default_tiling_enabled");
    }
    
    private void useDefaultValues() {
        // Essential default values
        defaultModelPath = "models/DSCF_float32.tflite";
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
        defaultTilingEnabled = true;
        
        // GPU defaults
        gpuInferencePreference = "FAST_SINGLE_ANSWER";
        gpuPrecisionLossAllowed = true;
        
        // NPU defaults
        enableNpu = true;
        allowFp16OnNpu = true;
        npuAcceleratorName = "";
    }
    
    // Essential getter methods
    public String getDefaultModelPath() { return defaultModelPath; }
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
    public boolean isDefaultTilingEnabled() { return defaultTilingEnabled; }
    
    // GPU getters
    public String getGpuInferencePreference() { return gpuInferencePreference; }
    public boolean isGpuPrecisionLossAllowed() { return gpuPrecisionLossAllowed; }
    
    // NPU getters
    public boolean isEnableNpu() { return enableNpu; }
    public boolean isAllowFp16OnNpu() { return allowFp16OnNpu; }
    public String getNpuAcceleratorName() { return npuAcceleratorName; }
    
    // Simple setters
    public void setDefaultTilingEnabled(boolean enabled) {
        this.defaultTilingEnabled = enabled;
    }
    
    
    public String getConfigSummary() {
        return "Model: " + defaultModelPath + ", Scale: " + expectedScaleFactor + "x, " +
               "Threads: " + defaultNumThreads + ", NPU: " + (enableNpu ? "On" : "Off");
    }
}