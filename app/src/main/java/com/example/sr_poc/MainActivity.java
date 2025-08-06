package com.example.sr_poc;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.example.sr_poc.processing.ProcessingController;
import com.example.sr_poc.utils.MemoryUtils;

public class MainActivity extends AppCompatActivity {
    
    private ImageView imageView;
    private ImageComparisonView imageComparisonView;
    private Button btnSwitchImage;
    private Button btnGpuProcess;
    private Button btnCpuProcess;
    private Button btnNpuProcess;
    private Button btnResetImage;
    private Button btnNpuTest;
    private CheckBox cbEnableTiling;
    private TextView tvInferenceTime;
    private TextView tvImageInfo;
    
    private ImageManager imageManager;
    private ThreadSafeSRProcessor srProcessor;
    private ConfigManager configManager;
    private Bitmap originalBitmap;
    private Bitmap processedBitmap;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initViews();
        initComponents();
        setupListeners();
        loadCurrentImage();
    }
    
    private void initViews() {
        imageView = findViewById(R.id.imageView);
        imageComparisonView = findViewById(R.id.imageComparisonView);
        btnSwitchImage = findViewById(R.id.btnSwitchImage);
        btnGpuProcess = findViewById(R.id.btnGpuProcess);
        btnCpuProcess = findViewById(R.id.btnCpuProcess);
        btnNpuProcess = findViewById(R.id.btnNpuProcess);
        btnResetImage = findViewById(R.id.btnResetImage);
        btnNpuTest = findViewById(R.id.btnNpuTest);
        cbEnableTiling = findViewById(R.id.cbEnableTiling);
        tvInferenceTime = findViewById(R.id.tvInferenceTime);
        tvImageInfo = findViewById(R.id.tvImageInfo);
        
        // 初始化時禁用所有按鈕並顯示 initing 狀態
        setButtonsEnabled(false);
        tvInferenceTime.setText("Initing...");
    }
    
    private void initComponents() {
        // 初始化配置管理器
        configManager = ConfigManager.getInstance(this);
        Log.d("MainActivity", "Configuration loaded: " + configManager.getConfigSummary());
        
        // 設置UI預設值
        cbEnableTiling.setChecked(configManager.isDefaultTilingEnabled());
        
        // 記錄硬體資訊
        HardwareInfo.logHardwareInfo(this);
        
        imageManager = new ImageManager(this);
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
                        // 初始化成功後啟用所有按鈕
                        setButtonsEnabled(true);
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
        
        // 在UI上顯示硬體資訊
        String acceleratorInfo = HardwareInfo.getAcceleratorInfo();
        Log.d("MainActivity", "Running on: " + acceleratorInfo);
    }
    
    private void setupListeners() {
        btnSwitchImage.setOnClickListener(v -> switchToNextImage());
        btnGpuProcess.setOnClickListener(v -> performSuperResolutionWithGpu());
        btnCpuProcess.setOnClickListener(v -> performSuperResolutionWithCpu());
        btnNpuProcess.setOnClickListener(v -> performSuperResolutionWithNpu());
        btnResetImage.setOnClickListener(v -> resetToOriginalImage());
        btnNpuTest.setOnClickListener(v -> startNpuTest());
        
        // 長按checkbox顯示配置摘要
        cbEnableTiling.setOnLongClickListener(v -> {
            String configSummary = configManager.getConfigSummary();
            Toast.makeText(this, configSummary, Toast.LENGTH_LONG).show();
            Log.d("MainActivity", configSummary);
            return true;
        });
        
        // 長按inference time顯示詳細策略信息
        tvInferenceTime.setOnLongClickListener(v -> {
            showDetailedStrategyInfo();
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
        // 檢查記憶體狀況，但不阻止執行
        MemoryUtils.MemoryInfo memInfo = MemoryUtils.getCurrentMemoryInfo();
        int requiredMemory = configManager.getBatchProcessingRequiredMb();
        
        if (memInfo.availableMemoryMB < requiredMemory) {
            String warning = String.format("⚠️ Low Memory Warning: %dMB available < %dMB required.\n" +
                                         "NPU batch processing may cause out-of-memory error.\n" +
                                         "Proceeding as requested...", 
                                         memInfo.availableMemoryMB, requiredMemory);
            Toast.makeText(this, warning, Toast.LENGTH_LONG).show();
            Log.w("MainActivity", "NPU batch processing with insufficient memory: " + 
                  memInfo.availableMemoryMB + "MB < " + requiredMemory + "MB");
        }
        
        performSuperResolutionWithMode(ThreadSafeSRProcessor.ProcessingMode.NPU);
    }
    
    private void performSuperResolutionWithMode(ThreadSafeSRProcessor.ProcessingMode processingMode) {
        // Show memory warning if needed
        if (MemoryUtils.isLowMemory()) {
            MemoryUtils.MemoryInfo memInfo = MemoryUtils.getCurrentMemoryInfo();
            Toast.makeText(this, "Warning: Low memory (" + memInfo.availableMemoryMB + "MB available)", 
                         Toast.LENGTH_LONG).show();
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
                runOnUiThread(() -> {
                    // Show memory category along with progress message
                    String memoryCategory = MemoryUtils.getMemoryCategory();
                    String displayMessage = message;
                    if (message.startsWith("Strategy:")) {
                        displayMessage = message + " [Memory: " + memoryCategory + "]";
                    }
                    tvInferenceTime.setText(displayMessage);
                });
            }
            
            @Override
            public void onSuccess(Bitmap resultBitmap, String timeMessage) {
                runOnUiThread(() -> {
                    processedBitmap = resultBitmap;
                    updateComparisonView();
                    
                    // Enhanced time message with memory info
                    MemoryUtils.MemoryInfo memInfo = MemoryUtils.getCurrentMemoryInfo();
                    String enhancedMessage = timeMessage + " [" + memInfo.availableMemoryMB + "MB available]";
                    tvInferenceTime.setText(enhancedMessage);
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
        btnNpuTest.setEnabled(true); // NPU Test button is always enabled
        cbEnableTiling.setEnabled(enabled);
    }
    
    private void setProcessingButtonsEnabled(boolean enabled) {
        btnGpuProcess.setEnabled(enabled);
        btnCpuProcess.setEnabled(enabled);
        btnNpuProcess.setEnabled(enabled);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (srProcessor != null) {
            srProcessor.close();
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
    
    private void startNpuTest() {
        Intent intent = new Intent(this, NPUModelTestActivity.class);
        startActivity(intent);
    }
    
    private void showDetailedStrategyInfo() {
        if (originalBitmap == null) {
            Toast.makeText(this, "No image loaded", Toast.LENGTH_SHORT).show();
            return;
        }
        
        StringBuilder info = new StringBuilder();
        info.append("=== Processing Strategy Analysis ===\n");
        
        // Memory information
        MemoryUtils.MemoryInfo memInfo = MemoryUtils.getCurrentMemoryInfo();
        String memoryCategory = MemoryUtils.getMemoryCategory();
        info.append("Memory Status: ").append(memoryCategory).append("\n");
        info.append("Available: ").append(memInfo.availableMemoryMB).append("MB\n");
        info.append("Required for Batch: ").append(configManager.getBatchProcessingRequiredMb()).append("MB\n\n");
        
        // Model information
        info.append("Model Batch Support: ").append(srProcessor.isModelBatchCapable() ? "YES" : "NO").append("\n");
        if (srProcessor.isModelBatchCapable()) {
            info.append("Batch Size: ").append(srProcessor.getModelBatchSize()).append("\n");
        }
        info.append("Input Size: ").append(srProcessor.getModelInputWidth()).append("x").append(srProcessor.getModelInputHeight()).append("\n\n");
        
        // Image analysis
        int imageWidth = originalBitmap.getWidth();
        int imageHeight = originalBitmap.getHeight();
        info.append("Current Image: ").append(imageWidth).append("x").append(imageHeight).append("\n");
        
        // Simulate strategy selection
        ProcessingStrategySelector selector = new ProcessingStrategySelector(srProcessor, configManager);
        ProcessingStrategySelector.StrategyDecision decision = selector.selectStrategy(
            originalBitmap, null, cbEnableTiling.isChecked());
        
        info.append("Recommended Strategy: ").append(ProcessingStrategySelector.getStrategyDescription(decision.strategy)).append("\n");
        info.append("Reason: ").append(decision.reason).append("\n");
        info.append("Estimated Time: ").append(decision.estimatedTimeMs).append("ms\n");
        
        // Hardware info
        info.append("\nHardware: ").append(HardwareInfo.getSocModel()).append("\n");
        info.append("NPU Enabled: ").append(configManager.isEnableNpu() ? "YES" : "NO").append("\n");
        
        // Show in dialog-like manner with system log
        Log.d("MainActivity", info.toString());
        Toast.makeText(this, "Detailed strategy info logged to console", Toast.LENGTH_LONG).show();
        
        // Also show key info in shorter toast
        String shortInfo = String.format("Strategy: %s\nMemory: %s (%dMB)\nEstimate: %dms", 
            ProcessingStrategySelector.getStrategyDescription(decision.strategy),
            memoryCategory, memInfo.availableMemoryMB, decision.estimatedTimeMs);
        Toast.makeText(this, shortInfo, Toast.LENGTH_LONG).show();
    }
}