package com.example.sr_poc;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.sr_poc.pool.BitmapPool;
import com.example.sr_poc.pool.BitmapPoolManager;
import com.example.sr_poc.pool.PooledBitmapFactory;

import java.util.Locale;
import java.util.Random;

/**
 * Demo Activity for visualizing Bitmap Pool performance
 * 
 * Add to AndroidManifest.xml:
 * <activity android:name=".BitmapPoolDemoActivity" />
 * 
 * Launch with:
 * Intent intent = new Intent(this, BitmapPoolDemoActivity.class);
 * startActivity(intent);
 */
public class BitmapPoolDemoActivity extends Activity {
    
    private TextView tvStats;
    private TextView tvMemory;
    private TextView tvMetrics;
    private ImageView imageView;
    private Button btnWithPool;
    private Button btnWithoutPool;
    private Button btnStressTest;
    private Button btnClearPool;
    
    private BitmapPoolManager poolManager;
    private PooledBitmapFactory bitmapFactory;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Random random = new Random();
    
    private boolean isRunning = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
        
        poolManager = BitmapPoolManager.getInstance(this);
        bitmapFactory = new PooledBitmapFactory(this);
        
        setupButtons();
        updateStats();
        
        // Start periodic stats update
        handler.postDelayed(statsUpdater, 1000);
    }
    
    private View createContentView() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);
        
        // Title
        TextView title = new TextView(this);
        title.setText("Bitmap Pool Validation Demo");
        title.setTextSize(24);
        title.setPadding(0, 0, 0, 20);
        layout.addView(title);
        
        // Stats display
        tvStats = new TextView(this);
        tvStats.setTextSize(14);
        tvStats.setTypeface(android.graphics.Typeface.MONOSPACE);
        layout.addView(tvStats);
        
        // Memory display
        tvMemory = new TextView(this);
        tvMemory.setTextSize(14);
        tvMemory.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvMemory.setPadding(0, 10, 0, 0);
        layout.addView(tvMemory);
        
        // Metrics display
        tvMetrics = new TextView(this);
        tvMetrics.setTextSize(14);
        tvMetrics.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvMetrics.setPadding(0, 10, 0, 0);
        layout.addView(tvMetrics);
        
        // Image view
        imageView = new ImageView(this);
        imageView.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 400));
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setBackgroundColor(Color.LTGRAY);
        layout.addView(imageView);
        
        // Buttons
        android.widget.LinearLayout buttonLayout = new android.widget.LinearLayout(this);
        buttonLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        
        btnWithPool = new Button(this);
        btnWithPool.setText("Test WITH Pool");
        buttonLayout.addView(btnWithPool);
        
        btnWithoutPool = new Button(this);
        btnWithoutPool.setText("Test WITHOUT Pool");
        buttonLayout.addView(btnWithoutPool);
        
        layout.addView(buttonLayout);
        
        android.widget.LinearLayout buttonLayout2 = new android.widget.LinearLayout(this);
        buttonLayout2.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        
        btnStressTest = new Button(this);
        btnStressTest.setText("Stress Test");
        buttonLayout2.addView(btnStressTest);
        
        btnClearPool = new Button(this);
        btnClearPool.setText("Clear Pool");
        buttonLayout2.addView(btnClearPool);
        
        layout.addView(buttonLayout2);
        
        return layout;
    }
    
    private void setupButtons() {
        btnWithPool.setOnClickListener(v -> testWithPool());
        btnWithoutPool.setOnClickListener(v -> testWithoutPool());
        btnStressTest.setOnClickListener(v -> runStressTest());
        btnClearPool.setOnClickListener(v -> {
            poolManager.clearPool();
            Toast.makeText(this, "Pool cleared", Toast.LENGTH_SHORT).show();
            updateStats();
        });
    }
    
    private void testWithPool() {
        if (isRunning) return;
        isRunning = true;
        
        new Thread(() -> {
            long startTime = System.currentTimeMillis();
            long startMemory = getUsedMemory();
            
            for (int i = 0; i < 50; i++) {
                // Use pool
                Bitmap bitmap = bitmapFactory.createBitmap(1280, 720, Bitmap.Config.ARGB_8888);
                
                // Draw something
                drawPattern(bitmap, i);
                
                // Update UI
                final Bitmap finalBitmap = bitmap;
                final int iteration = i;
                handler.post(() -> {
                    imageView.setImageBitmap(finalBitmap);
                    tvStats.setText(String.format("WITH Pool - Iteration: %d/50", iteration + 1));
                });
                
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                
                // Release back to pool
                bitmapFactory.releaseBitmap(bitmap);
            }
            
            long endTime = System.currentTimeMillis();
            long endMemory = getUsedMemory();
            
            long duration = endTime - startTime;
            long memoryGrowth = endMemory - startMemory;
            
            handler.post(() -> {
                tvStats.setText(String.format(
                    "WITH Pool Complete\nTime: %dms\nMemory Growth: %.2fMB",
                    duration, memoryGrowth / (1024.0 * 1024.0)));
                imageView.setImageBitmap(null);
                updateStats();
                isRunning = false;
            });
        }).start();
    }
    
    private void testWithoutPool() {
        if (isRunning) return;
        isRunning = true;
        
        new Thread(() -> {
            long startTime = System.currentTimeMillis();
            long startMemory = getUsedMemory();
            
            for (int i = 0; i < 50; i++) {
                // Create without pool
                Bitmap bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888);
                
                // Draw something
                drawPattern(bitmap, i);
                
                // Update UI
                final Bitmap finalBitmap = bitmap;
                final int iteration = i;
                handler.post(() -> {
                    imageView.setImageBitmap(finalBitmap);
                    tvStats.setText(String.format("WITHOUT Pool - Iteration: %d/50", iteration + 1));
                });
                
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                
                // Just let it be GC'd (no pool)
                bitmap = null;
            }
            
            // Force GC to see impact
            System.gc();
            
            long endTime = System.currentTimeMillis();
            long endMemory = getUsedMemory();
            
            long duration = endTime - startTime;
            long memoryGrowth = endMemory - startMemory;
            
            handler.post(() -> {
                tvStats.setText(String.format(
                    "WITHOUT Pool Complete\nTime: %dms\nMemory Growth: %.2fMB",
                    duration, memoryGrowth / (1024.0 * 1024.0)));
                imageView.setImageBitmap(null);
                updateStats();
                isRunning = false;
            });
        }).start();
    }
    
    private void runStressTest() {
        if (isRunning) return;
        isRunning = true;
        
        new Thread(() -> {
            int successCount = 0;
            int oomCount = 0;
            long startTime = System.currentTimeMillis();
            
            for (int i = 0; i < 100; i++) {
                try {
                    // Random size
                    int width = 1920 + random.nextInt(1920); // 1920-3840
                    int height = 1080 + random.nextInt(1080); // 1080-2160
                    
                    Bitmap bitmap = bitmapFactory.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    
                    // Quick operation
                    bitmap.eraseColor(Color.rgb(random.nextInt(256), 
                                              random.nextInt(256), 
                                              random.nextInt(256)));
                    
                    final int iteration = i;
                    handler.post(() -> {
                        tvStats.setText(String.format("Stress Test: %d/100", iteration + 1));
                    });
                    
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    
                    bitmapFactory.releaseBitmap(bitmap);
                    successCount++;
                    
                } catch (OutOfMemoryError e) {
                    oomCount++;
                    System.gc();
                    poolManager.clearPool();
                }
            }
            
            long duration = System.currentTimeMillis() - startTime;
            final int finalSuccess = successCount;
            final int finalOOM = oomCount;
            
            handler.post(() -> {
                tvStats.setText(String.format(
                    "Stress Test Complete\nSuccess: %d/100\nOOM: %d\nTime: %dms",
                    finalSuccess, finalOOM, duration));
                updateStats();
                isRunning = false;
            });
        }).start();
    }
    
    private void drawPattern(Bitmap bitmap, int iteration) {
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        android.graphics.Paint paint = new android.graphics.Paint();
        
        // Background
        canvas.drawColor(Color.rgb(200 + iteration % 56, 200, 200));
        
        // Draw some circles
        paint.setColor(Color.BLUE);
        for (int i = 0; i < 10; i++) {
            float x = random.nextFloat() * bitmap.getWidth();
            float y = random.nextFloat() * bitmap.getHeight();
            float radius = 20 + random.nextFloat() * 100;
            canvas.drawCircle(x, y, radius, paint);
        }
        
        // Draw text
        paint.setColor(Color.BLACK);
        paint.setTextSize(100);
        canvas.drawText("Frame " + iteration, 100, 200, paint);
    }
    
    private void updateStats() {
        // Memory stats
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        tvMemory.setText(String.format(Locale.US,
            "Memory:\n" +
            "  Used: %.2f MB\n" +
            "  Free: %.2f MB\n" +
            "  Total: %.2f MB\n" +
            "  Max: %.2f MB",
            usedMemory / (1024.0 * 1024.0),
            freeMemory / (1024.0 * 1024.0),
            totalMemory / (1024.0 * 1024.0),
            maxMemory / (1024.0 * 1024.0)));
        
        // Pool metrics
        BitmapPool.BitmapPoolMetrics metrics = poolManager.getMetrics();
        if (metrics != null) {
            tvMetrics.setText(String.format(Locale.US,
                "Pool Metrics:\n" +
                "  Hit Rate: %.1f%%\n" +
                "  Hits: %d\n" +
                "  Misses: %d\n" +
                "  Pool Size: %d\n" +
                "  Allocations: %d\n" +
                "  Releases: %d\n" +
                "  Evictions: %d",
                metrics.getHitRate() * 100,
                metrics.getHits(),
                metrics.getMisses(),
                metrics.getCurrentPoolSize(),
                metrics.getAllocations(),
                metrics.getReleases(),
                metrics.getEvictions()));
        }
    }
    
    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
    
    private Runnable statsUpdater = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) {
                updateStats();
            }
            handler.postDelayed(this, 1000);
        }
    };
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(statsUpdater);
    }
}