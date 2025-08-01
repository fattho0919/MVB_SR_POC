package com.example.sr_poc;

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
                runOnUiThread(() -> tvInferenceTime.setText(message));
            }
            
            @Override
            public void onSuccess(Bitmap resultBitmap, String timeMessage) {
                runOnUiThread(() -> {
                    processedBitmap = resultBitmap;
                    updateComparisonView();
                    tvInferenceTime.setText(timeMessage);
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
}