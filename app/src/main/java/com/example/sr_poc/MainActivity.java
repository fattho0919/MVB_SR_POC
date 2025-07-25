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

public class MainActivity extends AppCompatActivity {
    
    private ImageView imageView;
    private ImageComparisonView imageComparisonView;
    private Button btnSwitchImage;
    private Button btnGpuProcess;
    private Button btnCpuProcess;
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
        btnResetImage = findViewById(R.id.btnResetImage);
        cbEnableTiling = findViewById(R.id.cbEnableTiling);
        tvInferenceTime = findViewById(R.id.tvInferenceTime);
        tvImageInfo = findViewById(R.id.tvImageInfo);
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
        tvInferenceTime.setText("Initializing...");
        srProcessor.initialize(new ThreadSafeSRProcessor.InitCallback() {
            @Override
            public void onInitialized(boolean success, String message) {
                runOnUiThread(() -> {
                    if (success) {
                        Log.d("MainActivity", "SR Processor: " + message);
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        tvInferenceTime.setText("Ready");
                    } else {
                        Log.e("MainActivity", "SR Processor failed: " + message);
                        Toast.makeText(MainActivity.this, "Failed to initialize: " + message, Toast.LENGTH_LONG).show();
                        tvInferenceTime.setText("Failed");
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
        // 檢查可用記憶體是否足夠
        Runtime runtime = Runtime.getRuntime();
        long freeMemory = runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - freeMemory;
        long availableMemory = maxMemory - usedMemory;
        
        // 如果可用記憶體少於100MB，警告用戶
        if (availableMemory < 100 * 1024 * 1024) {
            Toast.makeText(this, "Warning: Low memory (" + (availableMemory / 1024 / 1024) + "MB available)", 
                         Toast.LENGTH_LONG).show();
            // GC移除以避免性能暫停
        }
        
        tvInferenceTime.setText("Processing...");
        
        new Thread(() -> {
            try {
                Log.d("MainActivity", "Starting super resolution task");
                
                Bitmap currentBitmap = imageManager.getCurrentBitmap();
                if (currentBitmap == null) {
                    Log.e("MainActivity", "No current bitmap available");
                    runOnUiThread(() -> {
                        Toast.makeText(this, "No image loaded", Toast.LENGTH_SHORT).show();
                        tvInferenceTime.setText("Ready");
                    });
                    return;
                }
                
                Log.d("MainActivity", "Current bitmap size: " + 
                    currentBitmap.getWidth() + "x" + currentBitmap.getHeight());
                
                // 創建效能統計
                PerformanceMonitor.InferenceStats stats = PerformanceMonitor.createStats();
                stats.inputWidth = currentBitmap.getWidth();
                stats.inputHeight = currentBitmap.getHeight();
                stats.accelerator = srProcessor.getAcceleratorInfo();
                
                // 檢查記憶體
                long freeMemoryBeforeSR = runtime.freeMemory();
                long maxMemoryBeforeSR = runtime.maxMemory();
                stats.memoryBefore = (maxMemoryBeforeSR - freeMemoryBeforeSR) / 1024 / 1024;
                Log.d("MainActivity", "Memory before SR - Free: " + 
                    (freeMemoryBeforeSR / 1024 / 1024) + "MB, Max: " + (maxMemoryBeforeSR / 1024 / 1024) + "MB");
                
                long startTime = System.currentTimeMillis();
                Bitmap resultBitmap;
                
                // 檢查是否啟用分塊處理
                boolean shouldUseTiling;
                if (cbEnableTiling.isChecked()) {
                    // 如果用戶勾選了 checkbox，強制啟用 tiling（或使用推薦邏輯）
                    shouldUseTiling = true;
                    Log.d("MainActivity", "Tiling forced by user selection");
                } else {
                    // 否則使用自動判斷邏輯
                    shouldUseTiling = TileProcessor.shouldUseTileProcessing(currentBitmap, configManager);
                }
                
                if (shouldUseTiling) {
                    Log.d("MainActivity", "Using tile processing for large image");
                    TileProcessor tileProcessor = new TileProcessor(srProcessor, configManager);
                    resultBitmap = tileProcessor.processByTiles(currentBitmap, new TileProcessor.ProcessCallback() {
                        @Override
                        public void onProgress(int completed, int total) {
                            runOnUiThread(() -> {
                                tvInferenceTime.setText("Processing tiles: " + completed + "/" + total);
                            });
                        }
                    });
                    stats.usedTileProcessing = true;
                } else {
                    Log.d("MainActivity", "Using direct processing");
                    
                    final Object lock = new Object();
                    final Bitmap[] result = new Bitmap[1];
                    final boolean[] completed = new boolean[1];
                    
                    srProcessor.processImage(currentBitmap, new ThreadSafeSRProcessor.InferenceCallback() {
                        @Override
                        public void onResult(Bitmap resultImage, long inferenceTime) {
                            synchronized (lock) {
                                result[0] = resultImage;
                                completed[0] = true;
                                lock.notify();
                            }
                        }
                        
                        @Override
                        public void onError(String error) {
                            Log.e("MainActivity", "Direct processing failed: " + error);
                            synchronized (lock) {
                                result[0] = null;
                                completed[0] = true;
                                lock.notify();
                            }
                        }
                    });
                    
                    // 等待處理完成
                    synchronized (lock) {
                        while (!completed[0]) {
                            try {
                                lock.wait();
                            } catch (InterruptedException e) {
                                Log.e("MainActivity", "Wait interrupted");
                                break;
                            }
                        }
                    }
                    
                    resultBitmap = result[0];
                    stats.usedTileProcessing = false;
                }
                
                long endTime = System.currentTimeMillis();
                
                // 完成效能統計
                stats.inferenceTime = endTime - startTime;
                if (resultBitmap != null) {
                    stats.outputWidth = resultBitmap.getWidth();
                    stats.outputHeight = resultBitmap.getHeight();
                }
                long freeMemoryAfterSR = runtime.freeMemory();
                stats.memoryAfter = (maxMemoryBeforeSR - freeMemoryAfterSR) / 1024 / 1024;
                
                // 記錄效能統計
                PerformanceMonitor.logPerformanceStats(stats);
                
                Log.d("MainActivity", "Super resolution completed, time: " + stats.inferenceTime + "ms");
                
                runOnUiThread(() -> {
                    if (resultBitmap != null) {
                        Log.d("MainActivity", "Result bitmap size: " + 
                            resultBitmap.getWidth() + "x" + resultBitmap.getHeight());
                        
                        // 保存處理後的圖片
                        processedBitmap = resultBitmap;
                        
                        // 更新比較視圖
                        updateComparisonView();
                        
                        tvInferenceTime.setText(String.format("Inference time: %d ms", stats.inferenceTime));
                    } else {
                        Log.e("MainActivity", "Super resolution returned null");
                        Toast.makeText(this, "Super resolution failed", Toast.LENGTH_SHORT).show();
                        tvInferenceTime.setText("Failed");
                    }
                });
                
            } catch (OutOfMemoryError e) {
                Log.e("MainActivity", "Out of memory error", e);
                // GC移除以避免性能暫停
                runOnUiThread(() -> {
                    Toast.makeText(this, "Out of memory! Try closing other apps.", Toast.LENGTH_LONG).show();
                    tvInferenceTime.setText("Out of Memory");
                });
            } catch (Exception e) {
                Log.e("MainActivity", "Exception during super resolution", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
                    tvInferenceTime.setText("Error");
                });
            }
        }).start();
    }
    
    private void switchToNextImage() {
        imageManager.switchToNext();
        loadCurrentImage();
    }
    
    private void performSuperResolutionWithGpu() {
        performSuperResolutionWithMode(true);
    }
    
    private void performSuperResolutionWithCpu() {
        performSuperResolutionWithMode(false);
    }
    
    private void performSuperResolutionWithMode(boolean forceGpu) {
        // 檢查可用記憶體是否足夠
        Runtime runtime = Runtime.getRuntime();
        long freeMemory = runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - freeMemory;
        long availableMemory = maxMemory - usedMemory;
        
        // 如果可用記憶體少於100MB，警告用戶
        if (availableMemory < 100 * 1024 * 1024) {
            Toast.makeText(this, "Warning: Low memory (" + (availableMemory / 1024 / 1024) + "MB available)", 
                         Toast.LENGTH_LONG).show();
            // GC移除以避免性能暫停
        }
        
        btnGpuProcess.setEnabled(false);
        btnCpuProcess.setEnabled(false);
        tvInferenceTime.setText("Processing with " + (forceGpu ? "GPU" : "CPU") + "...");
        
        new Thread(() -> {
            try {
                Log.d("MainActivity", "Starting super resolution task with " + (forceGpu ? "GPU" : "CPU"));
                
                Bitmap currentBitmap = imageManager.getCurrentBitmap();
                if (currentBitmap == null) {
                    Log.e("MainActivity", "No current bitmap available");
                    runOnUiThread(() -> {
                        Toast.makeText(this, "No image loaded", Toast.LENGTH_SHORT).show();
                        btnGpuProcess.setEnabled(true);
                        btnCpuProcess.setEnabled(true);
                        tvInferenceTime.setText("Ready");
                    });
                    return;
                }
                
                Log.d("MainActivity", "Current bitmap size: " + 
                    currentBitmap.getWidth() + "x" + currentBitmap.getHeight());
                
                // 創建效能統計
                PerformanceMonitor.InferenceStats stats = PerformanceMonitor.createStats();
                stats.inputWidth = currentBitmap.getWidth();
                stats.inputHeight = currentBitmap.getHeight();
                stats.accelerator = forceGpu ? "GPU (Forced)" : "CPU (Forced)";
                
                // 檢查記憶體
                long freeMemoryBeforeSR = runtime.freeMemory();
                long maxMemoryBeforeSR = runtime.maxMemory();
                stats.memoryBefore = (maxMemoryBeforeSR - freeMemoryBeforeSR) / 1024 / 1024;
                Log.d("MainActivity", "Memory before SR - Free: " + 
                    (freeMemoryBeforeSR / 1024 / 1024) + "MB, Max: " + (maxMemoryBeforeSR / 1024 / 1024) + "MB");
                
                long startTime = System.currentTimeMillis();
                Bitmap resultBitmap;
                
                // 檢查是否啟用分塊處理
                boolean shouldUseTiling;
                if (cbEnableTiling.isChecked()) {
                    // 如果用戶勾選了 checkbox，強制啟用 tiling（或使用推薦邏輯）
                    shouldUseTiling = true;
                    Log.d("MainActivity", "Tiling forced by user selection");
                } else {
                    // 否則使用自動判斷邏輯
                    shouldUseTiling = TileProcessor.shouldUseTileProcessing(currentBitmap, configManager);
                }
                
                if (shouldUseTiling) {
                    Log.d("MainActivity", "Using tile processing for large image");
                    TileProcessor tileProcessor = new TileProcessor(srProcessor, configManager);
                    resultBitmap = tileProcessor.processByTiles(currentBitmap, new TileProcessor.ProcessCallback() {
                        @Override
                        public void onProgress(int completed, int total) {
                            runOnUiThread(() -> {
                                tvInferenceTime.setText("Processing with " + (forceGpu ? "GPU" : "CPU") + 
                                                      " - tiles: " + completed + "/" + total);
                            });
                        }
                    });
                    stats.usedTileProcessing = true;
                } else {
                    Log.d("MainActivity", "Using direct processing");
                    
                    final Object lock = new Object();
                    final Bitmap[] result = new Bitmap[1];
                    final boolean[] completed = new boolean[1];
                    
                    srProcessor.processImageWithMode(currentBitmap, forceGpu, new ThreadSafeSRProcessor.InferenceCallback() {
                        @Override
                        public void onResult(Bitmap resultImage, long inferenceTime) {
                            synchronized (lock) {
                                result[0] = resultImage;
                                completed[0] = true;
                                lock.notify();
                            }
                        }
                        
                        @Override
                        public void onError(String error) {
                            Log.e("MainActivity", "Direct processing failed: " + error);
                            synchronized (lock) {
                                result[0] = null;
                                completed[0] = true;
                                lock.notify();
                            }
                        }
                    });
                    
                    // 等待處理完成
                    synchronized (lock) {
                        while (!completed[0]) {
                            try {
                                lock.wait();
                            } catch (InterruptedException e) {
                                Log.e("MainActivity", "Wait interrupted");
                                break;
                            }
                        }
                    }
                    
                    resultBitmap = result[0];
                    stats.usedTileProcessing = false;
                }
                
                long endTime = System.currentTimeMillis();
                
                // 完成效能統計
                stats.inferenceTime = endTime - startTime;
                if (resultBitmap != null) {
                    stats.outputWidth = resultBitmap.getWidth();
                    stats.outputHeight = resultBitmap.getHeight();
                }
                long freeMemoryAfterSR = runtime.freeMemory();
                stats.memoryAfter = (maxMemoryBeforeSR - freeMemoryAfterSR) / 1024 / 1024;
                
                // 記錄效能統計
                PerformanceMonitor.logPerformanceStats(stats);
                
                Log.d("MainActivity", "Super resolution completed, time: " + stats.inferenceTime + "ms");
                
                runOnUiThread(() -> {
                    if (resultBitmap != null) {
                        Log.d("MainActivity", "Result bitmap size: " + 
                            resultBitmap.getWidth() + "x" + resultBitmap.getHeight());
                        
                        // 保存處理後的圖片
                        processedBitmap = resultBitmap;
                        
                        // 更新比較視圖
                        updateComparisonView();
                        
                        tvInferenceTime.setText(String.format("Inference time (%s): %d ms", 
                                              forceGpu ? "GPU" : "CPU", stats.inferenceTime));
                    } else {
                        Log.e("MainActivity", "Super resolution returned null");
                        Toast.makeText(this, "Super resolution failed", Toast.LENGTH_SHORT).show();
                        tvInferenceTime.setText("Failed");
                    }
                    btnGpuProcess.setEnabled(true);
                    btnCpuProcess.setEnabled(true);
                });
                
            } catch (OutOfMemoryError e) {
                Log.e("MainActivity", "Out of memory error", e);
                // GC移除以避免性能暫停
                runOnUiThread(() -> {
                    Toast.makeText(this, "Out of memory! Try closing other apps.", Toast.LENGTH_LONG).show();
                    tvInferenceTime.setText("Out of Memory");
                    btnGpuProcess.setEnabled(true);
                    btnCpuProcess.setEnabled(true);
                });
            } catch (Exception e) {
                Log.e("MainActivity", "Exception during super resolution", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
                    tvInferenceTime.setText("Error");
                    btnGpuProcess.setEnabled(true);
                    btnCpuProcess.setEnabled(true);
                });
            }
        }).start();
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
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (srProcessor != null) {
            srProcessor.close();
        }
        // 釋放圖片記憶體
        if (imageManager != null) {
            Bitmap currentBitmap = imageManager.getCurrentBitmap();
            if (currentBitmap != null && !currentBitmap.isRecycled()) {
                currentBitmap.recycle();
            }
        }
        // 釋放比較視圖中的圖片
        if (originalBitmap != null && !originalBitmap.isRecycled()) {
            originalBitmap.recycle();
        }
        if (processedBitmap != null && !processedBitmap.isRecycled()) {
            processedBitmap.recycle();
        }
        // GC移除以避免性能暫停
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        // GC移除以避免性能暫停
    }
}