package com.example.sr_poc;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class HardwareInfo {

    private static final String TAG = "HardwareInfo";

    public static void logHardwareInfo(Context context) {
        Log.d(TAG, "=== Hardware Information ===");

        // CPU資訊
        logCpuInfo();

        // 記憶體資訊
        logMemoryInfo();

        // Android版本資訊
        logAndroidInfo();

        // GPU資訊 (如果可以獲取)
        logGpuInfo();

        Log.d(TAG, "=== End Hardware Information ===");
    }

    private static void logCpuInfo() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String line;
            int coreCount = 0;
            String cpuName = "Unknown";

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("processor")) {
                    coreCount++;
                } else if (line.startsWith("model name") || line.startsWith("Hardware")) {
                    cpuName = line.split(":")[1].trim();
                }
            }
            reader.close();

            Log.d(TAG, "=== CPU Information ===");
            Log.d(TAG, "CPU: " + cpuName);
            Log.d(TAG, "CPU Cores: " + coreCount);
            Log.d(TAG, "Available Processors: " + Runtime.getRuntime().availableProcessors());

        } catch (IOException e) {
            Log.w(TAG, "Could not read CPU info: " + e.getMessage());
        }
    }

    private static void logMemoryInfo() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;


        Log.d(TAG, "=== Memory Information ===");
        Log.d(TAG, "Memory - Max: " + (maxMemory / 1024 / 1024) + "MB");
        Log.d(TAG, "Memory - Total: " + (totalMemory / 1024 / 1024) + "MB");
        Log.d(TAG, "Memory - Used: " + (usedMemory / 1024 / 1024) + "MB");
        Log.d(TAG, "Memory - Free: " + (freeMemory / 1024 / 1024) + "MB");
    }

    private static void logAndroidInfo() {
        Log.d(TAG, "Android Version: " + android.os.Build.VERSION.RELEASE);
        Log.d(TAG, "API Level: " + android.os.Build.VERSION.SDK_INT);
        Log.d(TAG, "Device: " + android.os.Build.DEVICE);
        Log.d(TAG, "Model: " + android.os.Build.MODEL);
        Log.d(TAG, "Manufacturer: " + android.os.Build.MANUFACTURER);
        Log.d(TAG, "Board: " + android.os.Build.BOARD);
        Log.d(TAG, "Hardware: " + android.os.Build.HARDWARE);
    }

    private static void logGpuInfo() {
        Log.d(TAG, "=== GPU Information ===");
        Log.d(TAG, "Hardware Platform: " + android.os.Build.HARDWARE);
        Log.d(TAG, "Board: " + android.os.Build.BOARD);
        Log.d(TAG, "SoC Manufacturer: " + android.os.Build.SOC_MANUFACTURER);
        Log.d(TAG, "SoC Model: " + android.os.Build.SOC_MODEL);
        
        // 根據 SoC 型號推斷 GPU 型號
        String socModel = android.os.Build.SOC_MODEL;
        if (socModel != null) {
            String gpuInfo = getGpuInfoBySoc(socModel);
            if (gpuInfo != null) {
                Log.d(TAG, "GPU (inferred): " + gpuInfo);
            }
        }
        
        // 嘗試從系統屬性獲取 OpenGL ES 版本
        try {
            String glVersion = System.getProperty("ro.opengles.version");
            if (glVersion != null) {
                try {
                    int version = Integer.parseInt(glVersion, 16);
                    int major = version >> 16;
                    int minor = version & 0xffff;
                    Log.d(TAG, "OpenGL ES Version: " + major + "." + minor);
                } catch (NumberFormatException e) {
                    Log.d(TAG, "OpenGL ES Version (raw): " + glVersion);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read OpenGL version: " + e.getMessage());
        }
        
        // 移除會產生 SELinux 警告的檔案路徑
        // 只保留較安全的路徑嘗試
        try {
            String[] gpuFiles = {
                "/proc/driver/mali/version"
            };

            for (String file : gpuFiles) {
                try {
                    BufferedReader reader = new BufferedReader(new FileReader(file));
                    String line = reader.readLine();
                    if (line != null && !line.trim().isEmpty()) {
                        Log.d(TAG, "GPU Info from " + file + ": " + line.trim());
                    }
                    reader.close();
                } catch (IOException e) {
                    // 忽略，嘗試下一個文件
                }
            }
            
        } catch (Exception e) {
            Log.w(TAG, "Could not read GPU files: " + e.getMessage());
        }
        
        Log.d(TAG, "Note: For detailed GPU info, OpenGL ES context is required");
    }

    private static String getGpuInfoBySoc(String socModel) {
        if (socModel.contains("MT8195")) {
            return "ARM Mali-G57 MC5";
        } else if (socModel.contains("MT8188")) {
            return "ARM Mali-G57 MC3";
        } else if (socModel.contains("Snapdragon")) {
            return "Qualcomm Adreno";
        }
        // 可以繼續添加其他 SoC 的 GPU 對應
        return null;
    }

    public static String getAcceleratorInfo() {
        StringBuilder info = new StringBuilder();
        info.append("Device: ").append(android.os.Build.MODEL).append("\n");
        info.append("Hardware: ").append(android.os.Build.HARDWARE).append("\n");
        info.append("Cores: ").append(Runtime.getRuntime().availableProcessors()).append("\n");

        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        info.append("Max Memory: ").append(maxMemory / 1024 / 1024).append("MB");

        return info.toString();
    }
}