package com.example.sr_poc;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import android.content.ComponentCallbacks2;

import com.example.sr_poc.processing.ProcessingController;
import com.example.sr_poc.utils.MemoryUtils;
import android.widget.ProgressBar;
import android.graphics.Color;
import androidx.appcompat.app.AlertDialog;

import com.example.sr_poc.R;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements ComponentCallbacks2 {
    
    private ImageView imageView;
    private ImageComparisonView imageComparisonView;
    private Button btnSwitchImage;
    private Button btnGpuProcess;
    private Button btnCpuProcess;
    private Button btnNpuProcess;
    private Button btnResetImage;
    private CheckBox cbEnableTiling;
    private TextView tvInferenceTime;
    private TextView tvImageInfo;
    private TextView tvDetailedInfo;
    private Handler detailHideHandler = new Handler();
    private Runnable detailHideRunnable;
    
    // Model selection UI
    private Spinner spinnerModelSelection;
    private String currentModelPath;
    private ArrayAdapter<String> modelAdapter;
    
    private ImageManager imageManager;
    private ThreadSafeSRProcessor srProcessor;
    private HybridSRProcessor hybridProcessor;  // New hybrid processor
    private ConfigManager configManager;
    private Bitmap originalBitmap;
    private Bitmap processedBitmap;
    private boolean useHybridInit = false;  // Flag to use hybrid initialization (DISABLED due to memory issues)
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initViews();
        initComponents();
        setupListeners();
        loadCurrentImage();
        
        // Track first interaction time
        PerformanceMonitor.trackFirstInteraction();
    }
    
    private void initViews() {
        imageView = findViewById(R.id.imageView);
        imageComparisonView = findViewById(R.id.imageComparisonView);
        btnSwitchImage = findViewById(R.id.btnSwitchImage);
        btnGpuProcess = findViewById(R.id.btnGpuProcess);
        btnCpuProcess = findViewById(R.id.btnCpuProcess);
        btnNpuProcess = findViewById(R.id.btnNpuProcess);
        btnResetImage = findViewById(R.id.btnResetImage);
        cbEnableTiling = findViewById(R.id.cbEnableTiling);
        tvInferenceTime = findViewById(R.id.tvInferenceTime);
        tvImageInfo = findViewById(R.id.tvImageInfo);
        tvDetailedInfo = findViewById(R.id.tvDetailedInfo);
        spinnerModelSelection = findViewById(R.id.spinnerModelSelection);
        
        // 初始化時禁用所有按鈕並提示選擇模型
        setButtonsEnabled(false);
        tvInferenceTime.setText("Please select a model to start");
        
        // Keep original button text during initialization
        btnCpuProcess.setText("CPU");
        btnGpuProcess.setText("GPU");
        btnNpuProcess.setText("NPU");
    }
    
    private void initComponents() {
        // 初始化配置管理器
        configManager = ConfigManager.getInstance(this);
        Log.d("MainActivity", "Configuration loaded: " + configManager.getConfigSummary());
        
        // Setup model selection
        setupModelSelection();
        
        // 設置UI預設值
        cbEnableTiling.setChecked(configManager.isDefaultTilingEnabled());
        
        // 記錄硬體資訊
        HardwareInfo.logHardwareInfo(this);
        
        imageManager = new ImageManager(this);
        
        // Don't initialize processors here - wait for model selection
        // Processors will be initialized when user selects a model
        
        // 在UI上顯示硬體資訊
        String acceleratorInfo = HardwareInfo.getAcceleratorInfo();
        Log.d("MainActivity", "Running on: " + acceleratorInfo);
    }
    
    private void setupModelSelection() {
        // Get available models from ConfigManager
        List<String> availableModels = configManager.getAvailableModels();
        List<String> modelDisplayNames = new ArrayList<>();
        
        // Add prompt as first item
        modelDisplayNames.add("-- Select a model --");
        
        // Create display names (full filenames with extensions)
        for (String modelPath : availableModels) {
            modelDisplayNames.add(configManager.getModelDisplayName(modelPath));
        }
        
        // Create adapter with dynamic model list
        modelAdapter = new ArrayAdapter<>(this, 
            android.R.layout.simple_spinner_item, modelDisplayNames);
        modelAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item);
        spinnerModelSelection.setAdapter(modelAdapter);
        
        // Set to prompt by default (no model selected initially)
        spinnerModelSelection.setSelection(0);
        currentModelPath = null;
        
        spinnerModelSelection.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, 
                                       int position, long id) {
                // Position 0 is the prompt "-- Select a model --"
                if (position == 0) {
                    // User selected the prompt, keep buttons disabled
                    return;
                }
                
                // Adjust for the prompt item (position - 1)
                List<String> models = configManager.getAvailableModels();
                int modelIndex = position - 1;
                
                if (modelIndex >= 0 && modelIndex < models.size()) {
                    String newModelPath = models.get(modelIndex);
                    if (!newModelPath.equals(currentModelPath)) {
                        onModelChanged(newModelPath);
                    }
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }
    
    private void onModelChanged(String newModelPath) {
        // Prevent model change during processing
        if (isProcessing()) {
            Toast.makeText(this, "Cannot change model during processing", 
                          Toast.LENGTH_SHORT).show();
            // Reset spinner to current selection
            if (currentModelPath != null) {
                int currentIndex = configManager.getAvailableModels()
                                               .indexOf(currentModelPath);
                // Add 1 to account for the prompt item
                spinnerModelSelection.setSelection(currentIndex + 1);
            } else {
                // No model selected yet, go back to prompt
                spinnerModelSelection.setSelection(0);
            }
            return;
        }
        
        currentModelPath = newModelPath;
        configManager.setSelectedModelPath(newModelPath);
        
        String modelName = configManager.getModelDisplayName(newModelPath);
        Toast.makeText(this, "Switching to " + modelName + "...", 
                      Toast.LENGTH_SHORT).show();
        
        // Disable UI during model switching
        setButtonsEnabled(false);
        tvInferenceTime.setText("Loading model...");
        
        // Perform cleanup and reinitialization in background
        new Thread(() -> {
            try {
                // Step 1: Close existing processors (releases memory)
                if (hybridProcessor != null) {
                    hybridProcessor.close();
                    hybridProcessor = null;
                }
                if (srProcessor != null) {
                    srProcessor.close();
                    srProcessor = null;
                }
                
                // Step 2: Force garbage collection to free memory
                System.gc();
                System.runFinalization();
                
                // Step 3: Small delay to ensure resources are freed
                Thread.sleep(100);
                
                // Step 4: Reinitialize with new model
                runOnUiThread(() -> {
                    if (useHybridInit) {
                        initializeHybridProcessor();
                    } else {
                        initializeOriginalProcessor();
                    }
                    Toast.makeText(this, "Model loaded: " + modelName, 
                                 Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e("MainActivity", "Error switching model", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Failed to load model", 
                                 Toast.LENGTH_SHORT).show();
                    setButtonsEnabled(true);
                });
            }
        }).start();
    }
    
    // Helper method to check if processing is in progress
    private boolean isProcessing() {
        return (hybridProcessor != null && hybridProcessor.isProcessing()) ||
               (srProcessor != null && srProcessor.isProcessing());
    }
    
    private void setupListeners() {
        btnSwitchImage.setOnClickListener(v -> switchToNextImage());
        btnGpuProcess.setOnClickListener(v -> performSuperResolutionWithGpu());
        btnCpuProcess.setOnClickListener(v -> performSuperResolutionWithCpu());
        btnNpuProcess.setOnClickListener(v -> performSuperResolutionWithNpu());
        btnResetImage.setOnClickListener(v -> resetToOriginalImage());
        
        // Click inference time to toggle detailed view
        tvInferenceTime.setOnClickListener(v -> {
            if (tvDetailedInfo.getVisibility() == View.VISIBLE) {
                tvDetailedInfo.setVisibility(View.GONE);
            } else if (!tvDetailedInfo.getText().toString().isEmpty()) {
                tvDetailedInfo.setVisibility(View.VISIBLE);
                
                // Reset auto-hide timer
                if (detailHideRunnable != null) {
                    detailHideHandler.removeCallbacks(detailHideRunnable);
                    detailHideHandler.postDelayed(detailHideRunnable, 10000);
                }
            }
        });
        
        // 長按checkbox顯示配置摘要
        cbEnableTiling.setOnLongClickListener(v -> {
            String configSummary = configManager.getConfigSummary();
            Toast.makeText(this, configSummary, Toast.LENGTH_LONG).show();
            Log.d("MainActivity", configSummary);
            return true;
        });
        
        // checkbox變更時更新配置
        cbEnableTiling.setOnCheckedChangeListener((buttonView, isChecked) -> {
            configManager.setDefaultTilingEnabled(isChecked);
            Log.d("MainActivity", "Tiling default updated: " + isChecked);
        });
    }
    
    private void performSuperResolution() {
        performSuperResolutionWithMode(null);
    }
    
    private void switchToNextImage() {
        imageManager.switchToNext();
        loadCurrentImage();
    }
    
    private void performSuperResolutionWithGpu() {
        performSuperResolutionWithMode(ThreadSafeSRProcessor.ProcessingMode.GPU);
    }
    
    private void performSuperResolutionWithCpu() {
        performSuperResolutionWithMode(ThreadSafeSRProcessor.ProcessingMode.CPU);
    }
    
    private void performSuperResolutionWithNpu() {
        performSuperResolutionWithMode(ThreadSafeSRProcessor.ProcessingMode.NPU);
    }
    
    private void performSuperResolutionWithMode(ThreadSafeSRProcessor.ProcessingMode processingMode) {
        // Track performance
        long startTime = System.currentTimeMillis();
        
        // Show memory warning if needed
        if (MemoryUtils.isLowMemory()) {
            MemoryUtils.MemoryInfo memInfo = MemoryUtils.getCurrentMemoryInfo();
            Toast.makeText(this, "Warning: Low memory (" + memInfo.availableMemoryMB + "MB available)", 
                         Toast.LENGTH_LONG).show();
        }
        
        // Check if requested mode is available
        boolean modeAvailable = isModeAvailable(processingMode);
        if (!modeAvailable && processingMode != ThreadSafeSRProcessor.ProcessingMode.CPU) {
            // Show confirmation dialog for fallback
            showFallbackConfirmationDialog(processingMode);
            return;
        }
        
        // Disable buttons during processing
        setProcessingButtonsEnabled(false);
        
        ProcessingController controller = new ProcessingController(srProcessor, configManager, imageManager);
        controller.processImage(processingMode, cbEnableTiling.isChecked(), new ProcessingController.ProcessingCallback() {
            @Override
            public void onStart() {
                runOnUiThread(() -> {
                    String modeText = processingMode != null ? processingMode.name() : "auto";
                    tvInferenceTime.setText("Processing with " + modeText + "...");
                });
            }
            
            @Override
            public void onProgress(String message) {
                runOnUiThread(() -> tvInferenceTime.setText(message));
            }
            
            @Override
            public void onSuccess(Bitmap resultBitmap, String timeMessage) {
                runOnUiThread(() -> {
                    processedBitmap = resultBitmap;
                    updateComparisonView();
                    
                    // Get the stats from the last processing
                    PerformanceMonitor.InferenceStats stats = 
                        PerformanceMonitor.getLastInferenceStats();
                    
                    if (stats != null) {
                        // Update summary display
                        tvInferenceTime.setText(stats.formatSummary());
                        
                        // Show detailed info
                        tvDetailedInfo.setText(stats.formatDetailed());
                        tvDetailedInfo.setVisibility(View.VISIBLE);
                        
                        // Cancel previous hide task
                        if (detailHideRunnable != null) {
                            detailHideHandler.removeCallbacks(detailHideRunnable);
                        }
                        
                        // Auto-hide after 10 seconds
                        detailHideRunnable = () -> {
                            if (tvDetailedInfo != null) {
                                tvDetailedInfo.setVisibility(View.GONE);
                            }
                        };
                        detailHideHandler.postDelayed(detailHideRunnable, 10000);
                    } else {
                        // Fallback to simple display
                        tvInferenceTime.setText(timeMessage);
                    }
                    
                    // Track performance
                    long processingTime = System.currentTimeMillis() - startTime;
                    String modeStr = processingMode != null ? processingMode.name() : "AUTO";
                    PerformanceMonitor.trackModeUsage(modeStr, processingTime);
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                    tvInferenceTime.setText("Failed");
                });
            }
            
            @Override
            public void onComplete() {
                runOnUiThread(() -> setProcessingButtonsEnabled(true));
            }
            
            @Override
            public void onModeFallback(String message) {
                runOnUiThread(() -> {
                    // Show a warning toast to the user
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    Log.w("MainActivity", message);
                });
            }
        });
    }
    
    private void resetToOriginalImage() {
        if (originalBitmap != null) {
            // 清除處理後的圖片
            processedBitmap = null;
            
            // 切換回單一圖片顯示模式
            imageComparisonView.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            imageView.setImageBitmap(originalBitmap);
            
            tvInferenceTime.setText("Reset to original");
            Log.d("MainActivity", "Image reset to original");
            Toast.makeText(this, "Image reset to original", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "No original image available", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void loadCurrentImage() {
        Bitmap bitmap = imageManager.getCurrentBitmap();
        if (bitmap != null) {
            // 保存原始圖片的副本
            originalBitmap = bitmap.copy(bitmap.getConfig(), false);
            
            // 清除之前的處理結果
            processedBitmap = null;
            
            // 切換回單一圖片顯示模式
            imageComparisonView.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            imageView.setImageBitmap(bitmap);
            
            String imageInfo = String.format("%s (%d/%d)", 
                imageManager.getCurrentImageName(),
                imageManager.getCurrentIndex() + 1,
                imageManager.getTotalImages());
            tvImageInfo.setText(imageInfo);
            tvInferenceTime.setText("Ready");
        }
    }
    
    private void updateComparisonView() {
        if (originalBitmap != null && processedBitmap != null) {
            // 切換到比較模式
            imageView.setVisibility(View.GONE);
            imageComparisonView.setVisibility(View.VISIBLE);
            
            // 設置比較圖片
            imageComparisonView.setOriginalBitmap(originalBitmap);
            imageComparisonView.setProcessedBitmap(processedBitmap);
            
            Log.d("MainActivity", "Switched to comparison view");
        }
    }
    
    private void setButtonsEnabled(boolean enabled) {
        btnSwitchImage.setEnabled(enabled);
        btnGpuProcess.setEnabled(enabled);
        btnCpuProcess.setEnabled(enabled);
        btnNpuProcess.setEnabled(enabled);
        btnResetImage.setEnabled(enabled);
        cbEnableTiling.setEnabled(enabled);
    }
    
    private void setProcessingButtonsEnabled(boolean enabled) {
        // Only enable buttons that were not permanently disabled
        String gpuText = btnGpuProcess.getText().toString();
        if (!gpuText.contains("UNAVAILABLE") && !gpuText.contains("Unavailable")) {
            btnGpuProcess.setEnabled(enabled);
        }
        
        String cpuText = btnCpuProcess.getText().toString();
        if (!cpuText.contains("UNAVAILABLE") && !cpuText.contains("Unavailable")) {
            btnCpuProcess.setEnabled(enabled);
        }
        
        String npuText = btnNpuProcess.getText().toString();
        if (!npuText.contains("UNAVAILABLE") && !npuText.contains("Unavailable")) {
            btnNpuProcess.setEnabled(enabled);
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // Clean up handlers
        if (detailHideHandler != null && detailHideRunnable != null) {
            detailHideHandler.removeCallbacks(detailHideRunnable);
        }
        
        // Clean up processors
        if (hybridProcessor != null) {
            hybridProcessor.close();
            hybridProcessor = null;
        }
        if (srProcessor != null) {
            srProcessor.close();
            srProcessor = null;
        }
        
        // Clean up bitmaps
        if (imageManager != null) {
            MemoryUtils.safeRecycleBitmap(imageManager.getCurrentBitmap());
        }
        MemoryUtils.safeRecycleBitmap(originalBitmap);
        MemoryUtils.safeRecycleBitmap(processedBitmap);
    }
    
    @Override
    protected void onPause() {
        super.onPause();
    }
    
    /**
     * Initialize using the new HybridSRProcessor for fast startup.
     */
    private void initializeHybridProcessor() {
        Log.d("MainActivity", "Starting hybrid initialization");
        
        hybridProcessor = new HybridSRProcessor(this);
        
        hybridProcessor.initializeHybrid(new HybridSRProcessor.HybridCallback() {
            @Override
            public void onQuickStartReady(HybridSRProcessor.ProcessingMode mode, long initTimeMs) {
                Log.d("MainActivity", "Quick start ready: " + mode + " in " + initTimeMs + "ms");
                runOnUiThread(() -> {
                    tvInferenceTime.setText("Ready (CPU)");
                    // Enable basic functionality immediately
                    btnSwitchImage.setEnabled(true);
                    btnResetImage.setEnabled(true);
                    cbEnableTiling.setEnabled(true);
                    
                    // CPU is ready
                    btnCpuProcess.setEnabled(true);
                    updateButtonText(btnCpuProcess, "CPU");
                    
                    Toast.makeText(MainActivity.this, 
                        "App ready! CPU mode available in " + (initTimeMs/1000.0) + "s", 
                        Toast.LENGTH_SHORT).show();
                });
            }
            
            @Override
            public void onModeAvailable(HybridSRProcessor.ProcessingMode mode, String deviceInfo) {
                Log.d("MainActivity", "Mode available: " + mode + " (" + deviceInfo + ")");
                runOnUiThread(() -> {
                    switch (mode) {
                        case CPU:
                            btnCpuProcess.setEnabled(true);
                            updateButtonText(btnCpuProcess, "CPU");
                            break;
                        case GPU:
                            btnGpuProcess.setEnabled(true);
                            updateButtonText(btnGpuProcess, "GPU");
                            break;
                        case NPU:
                            btnNpuProcess.setEnabled(true);
                            updateButtonText(btnNpuProcess, "NPU");
                            break;
                    }
                });
            }
            
            @Override
            public void onModeFailed(HybridSRProcessor.ProcessingMode mode, String reason) {
                Log.w("MainActivity", "Mode failed: " + mode + " - " + reason);
                runOnUiThread(() -> {
                    switch (mode) {
                        case CPU:
                            permanentlyDisableButton(btnCpuProcess, "CPU", reason);
                            break;
                        case GPU:
                            permanentlyDisableButton(btnGpuProcess, "GPU", reason);
                            break;
                        case NPU:
                            permanentlyDisableButton(btnNpuProcess, "NPU", reason);
                            break;
                    }
                });
            }
            
            @Override
            public void onAllModesReady(int availableModesCount, long totalTimeMs) {
                Log.d("MainActivity", "All modes ready: " + availableModesCount + 
                      " modes in " + totalTimeMs + "ms");
                runOnUiThread(() -> {
                    if (availableModesCount == 0) {
                        // No modes available - fallback to original processor
                        Log.w("MainActivity", "No modes available from hybrid init, falling back to original processor");
                        tvInferenceTime.setText("Fallback mode");
                        Toast.makeText(MainActivity.this, 
                            "Warning: Using fallback mode. Performance may be limited.", 
                            Toast.LENGTH_LONG).show();
                        
                        // Initialize original processor as fallback
                        initializeOriginalProcessor();
                    } else {
                        String message = String.format("Initialization complete: %d mode%s in %.1fs", 
                            availableModesCount, 
                            availableModesCount > 1 ? "s" : "",
                            totalTimeMs/1000.0);
                        tvInferenceTime.setText("Ready");
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        
                        // Get the full processor for processing
                        if (hybridProcessor != null && hybridProcessor.getFullProcessor() != null) {
                            srProcessor = hybridProcessor.getFullProcessor();
                        } else if (srProcessor == null) {
                            // Create a basic processor if needed
                            Log.w("MainActivity", "Creating basic processor as fallback");
                            srProcessor = new ThreadSafeSRProcessor(MainActivity.this);
                        }
                    }
                });
            }
            
            @Override
            public void onProgress(String message, int progressPercent) {
                Log.d("MainActivity", "Progress: " + message + " (" + progressPercent + "%)");
                runOnUiThread(() -> {
                    tvInferenceTime.setText(message);
                });
            }
            
            @Override
            public void onInitError(HybridSRProcessor.ProcessingMode mode, Exception error) {
                Log.e("MainActivity", "Init error for " + mode, error);
                runOnUiThread(() -> {
                    String errorMsg = "Initialization error" + 
                        (mode != null ? " for " + mode : "") + ": " + error.getMessage();
                    Toast.makeText(MainActivity.this, errorMsg, Toast.LENGTH_LONG).show();
                    tvInferenceTime.setText("Error");
                });
            }
        });
    }
    
    /**
     * Fallback to original ThreadSafeSRProcessor initialization.
     */
    private void initializeOriginalProcessor() {
        // Avoid creating duplicate processors
        if (srProcessor != null) {
            Log.w("MainActivity", "Processor already exists, skipping duplicate initialization");
            return;
        }
        
        srProcessor = new ThreadSafeSRProcessor(this);
        
        // 異步初始化SR處理器
        srProcessor.initialize(new ThreadSafeSRProcessor.InitCallback() {
            @Override
            public void onInitialized(boolean success, String message) {
                runOnUiThread(() -> {
                    if (success) {
                        Log.d("MainActivity", "SR Processor: " + message);
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        tvInferenceTime.setText("Ready");
                        
                        // Parse the message to check which modes are available
                        boolean gpuAvailable = message.contains("GPU: ✓");
                        boolean npuAvailable = message.contains("NPU: ✓");
                        
                        // Enable buttons based on availability
                        setButtonsEnabled(true);
                        
                        // Disable unavailable modes
                        if (!gpuAvailable) {
                            permanentlyDisableButton(btnGpuProcess, "GPU", "GPU initialization failed or not supported");
                        }
                        if (!npuAvailable) {
                            permanentlyDisableButton(btnNpuProcess, "NPU", "NPU not available on this device");
                        }
                    } else {
                        Log.e("MainActivity", "SR Processor failed: " + message);
                        Toast.makeText(MainActivity.this, "Failed to initialize: " + message, Toast.LENGTH_LONG).show();
                        tvInferenceTime.setText("Failed");
                        // 初始化失敗仍然保持按鈕禁用狀態
                        finish();
                    }
                });
            }
        });
    }
    
    /**
     * Permanently disable a hardware button when the mode is unavailable.
     * This provides clear visual feedback that the hardware is not available.
     */
    private void permanentlyDisableButton(Button button, String modeName, String reason) {
        button.setEnabled(false);
        button.setClickable(false);
        button.setFocusable(false);
        
        // Update button appearance
        button.setAlpha(0.4f);
        button.setText(modeName + " (Unavailable)");
        
        // Set background to indicate unavailable state
        button.setBackgroundColor(Color.parseColor("#E0E0E0"));
        
        // Show detailed reason on long press (even when disabled)
        button.setOnLongClickListener(v -> {
            String message = modeName + " unavailable:\n" + reason;
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            Log.d("MainActivity", message);
            return true;
        });
        
        Log.d("MainActivity", "Button permanently disabled: " + modeName + " - " + reason);
    }
    
    /**
     * Update button text while preserving enabled state.
     */
    private void updateButtonText(Button button, String text) {
        button.setText(text);
    }
    
    /**
     * Check if a processing mode is available.
     */
    private boolean isModeAvailable(ThreadSafeSRProcessor.ProcessingMode mode) {
        if (srProcessor == null) return false;
        
        switch (mode) {
            case GPU:
                return !btnGpuProcess.getText().toString().contains("UNAVAILABLE");
            case NPU:
                return !btnNpuProcess.getText().toString().contains("UNAVAILABLE");
            case CPU:
                return !btnCpuProcess.getText().toString().contains("UNAVAILABLE");
            default:
                return false;
        }
    }
    
    /**
     * Show confirmation dialog when mode needs to fallback to CPU.
     */
    private void showFallbackConfirmationDialog(ThreadSafeSRProcessor.ProcessingMode requestedMode) {
        String message = String.format(
            "%s is not available on this device.\nWould you like to use CPU instead?",
            requestedMode.name()
        );
        
        new AlertDialog.Builder(this)
            .setTitle("Hardware Not Available")
            .setMessage(message)
            .setPositiveButton("Use CPU", (dialog, which) -> {
                // Process with CPU instead
                Log.d("MainActivity", "User confirmed fallback from " + requestedMode + " to CPU");
                performSuperResolutionWithMode(ThreadSafeSRProcessor.ProcessingMode.CPU);
            })
            .setNegativeButton("Cancel", (dialog, which) -> {
                Log.d("MainActivity", "User cancelled fallback from " + requestedMode);
            })
            .setCancelable(true)
            .show();
    }
    
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Log.d("MainActivity", "onTrimMemory called with level: " + level);
        
        // Memory callbacks are handled by MemoryOptimizedManager in ThreadSafeSRProcessor
        // Additional app-level handling can be added here if needed
        
        switch (level) {
            case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN:
                // UI is hidden, could pause heavy operations
                Log.d("MainActivity", "UI hidden - consider pausing operations");
                break;
                
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
                // Memory is low while app is running
                Log.w("MainActivity", "Memory running low/critical");
                Toast.makeText(this, "Low memory - some features may be limited", 
                    Toast.LENGTH_SHORT).show();
                break;
        }
    }
    
    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Log.e("MainActivity", "onLowMemory called - critical memory situation");
        Toast.makeText(this, "Critical memory warning!", Toast.LENGTH_LONG).show();
    }
}