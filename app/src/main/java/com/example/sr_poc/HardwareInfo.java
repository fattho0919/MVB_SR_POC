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
        Log.d(TAG, "Android API Level: " + android.os.Build.VERSION.SDK_INT);
        Log.d(TAG, "Device Model: " + android.os.Build.MODEL);
        Log.d(TAG, "Manufacturer: " + android.os.Build.MANUFACTURER);
        
        // 檢測SoC信息 - 需要API 31+或使用備用方法
        String socModel = null;
        String socManufacturer = null;
        
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            // Android 12+ 可以直接使用SOC字段
            socManufacturer = android.os.Build.SOC_MANUFACTURER;
            socModel = android.os.Build.SOC_MODEL;
            Log.d(TAG, "SoC detection method: Direct API (Android 12+)");
            Log.d(TAG, "SoC Manufacturer: " + socManufacturer);
            Log.d(TAG, "SoC Model: " + socModel);
        } else {
            // Android 11及以下使用備用檢測方法
            Log.d(TAG, "SoC detection method: Fallback (API < 31)");
            
            // 記錄備用檢測的詳細信息
            String hardwareFromCpuInfo = readHardwareFromCpuInfo();
            Log.d(TAG, "Hardware from /proc/cpuinfo: " + hardwareFromCpuInfo);
            Log.d(TAG, "Build.HARDWARE: " + android.os.Build.HARDWARE);
            
            try {
                String platformProp = System.getProperty("ro.board.platform");
                Log.d(TAG, "ro.board.platform: " + platformProp);
            } catch (Exception e) {
                Log.d(TAG, "ro.board.platform: not accessible");
            }
            
            socModel = getSocModelFallback();
            socManufacturer = getSocManufacturerFallback();
            Log.d(TAG, "SoC Manufacturer (fallback): " + socManufacturer);
            Log.d(TAG, "SoC Model (fallback): " + socModel);
        }
        
        // 根據 SoC 型號推斷 GPU 型號
        if (socModel != null && !socModel.isEmpty()) {
            String gpuInfo = getGpuInfoBySoc(socModel);
            if (gpuInfo != null) {
                Log.d(TAG, "GPU (inferred): " + gpuInfo);
            } else {
                Log.d(TAG, "GPU: Unable to infer from SoC model '" + socModel + "'");
            }
            
            // 專門診斷 MediaTek MT8195 的檢測情況
            if (socModel.toUpperCase().contains("MT8195")) {
                Log.d(TAG, "MediaTek MT8195 detected - NPU performance analysis enabled");
                Log.d(TAG, "Expected GPU: ARM Mali-G57 MC5");
                Log.d(TAG, "NPU Support: Should support NNAPI acceleration");
            }
        } else {
            Log.w(TAG, "SoC Model not available - using generic GPU detection");
            // 嘗試基於Hardware字段推斷
            String hardware = android.os.Build.HARDWARE;
            String gpuInfo = getGpuInfoByHardware(hardware);
            if (gpuInfo != null) {
                Log.d(TAG, "GPU (from hardware): " + gpuInfo);
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
        
        // 記錄 NPU 相關信息
        logNpuInfo();
    }
    
    /**
     * 記錄 NPU 相關的硬體信息
     */
    private static void logNpuInfo() {
        Log.d(TAG, "=== NPU Information ===");
        
        String socModel = getSocModel();
        if (socModel != null && socModel.toUpperCase().contains("MT8195")) {
            Log.d(TAG, "MediaTek MT8195 NPU Analysis:");
            Log.d(TAG, "  - NNAPI Support: Available (but deprecated in Android 15)");
            Log.d(TAG, "  - Expected NPU: MediaTek APU 3.0");
            Log.d(TAG, "  - Float16 Support: Hardware supported");
            Log.d(TAG, "  - TensorFlow Lite Version: 2.17.0");
            Log.d(TAG, "  - Note: Float16/Float32 performance parity may indicate NNAPI delegation issues");
        } else if (socModel != null) {
            Log.d(TAG, "SoC Model: " + socModel);
            Log.d(TAG, "NPU Support: Detection requires SoC-specific analysis");
        } else {
            Log.d(TAG, "NPU Support: Cannot determine without SoC model");
        }
        
        // 檢查 Android 版本對 NNAPI 的影響
        int apiLevel = android.os.Build.VERSION.SDK_INT;
        if (apiLevel >= 34) { // Android 14+
            Log.d(TAG, "NNAPI Status: Deprecated in Android 14+, using TensorFlow Lite built-in delegate");
        } else if (apiLevel >= 27) { // Android 8.1+
            Log.d(TAG, "NNAPI Status: Native support available");
        } else {
            Log.d(TAG, "NNAPI Status: Not supported (requires Android 8.1+)");
        }
    }

    /**
     * 為API < 31的設備實現SoC型號檢測
     */
    private static String getSocModelFallback() {
        // 方法1: 從/proc/cpuinfo讀取Hardware字段
        String hardware = readHardwareFromCpuInfo();
        if (hardware != null) {
            // MediaTek SoCs通常在Hardware字段包含型號信息
            if (hardware.contains("MT8195")) return "MT8195";
            if (hardware.contains("MT8188")) return "MT8188";
            if (hardware.contains("MT8192")) return "MT8192";
            if (hardware.contains("MT6893")) return "MT6893";
        }
        
        // 方法2: 使用Build.HARDWARE字段
        String buildHardware = android.os.Build.HARDWARE;
        if (buildHardware != null) {
            if (buildHardware.contains("mt8195")) return "MT8195";
            if (buildHardware.contains("mt8188")) return "MT8188";
            if (buildHardware.contains("mt8192")) return "MT8192";
        }
        
        // 方法3: 檢查系統屬性
        try {
            String platform = System.getProperty("ro.board.platform");
            if (platform != null) {
                if (platform.contains("mt8195")) return "MT8195";
                if (platform.contains("mt8188")) return "MT8188";
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to read system property ro.board.platform: " + e.getMessage());
        }
        
        // 如果都失敗，返回Build.HARDWARE作為最後備用
        return buildHardware;
    }
    
    /**
     * 為API < 31的設備實現SoC製造商檢測
     */
    private static String getSocManufacturerFallback() {
        String socModel = getSocModelFallback();
        if (socModel != null) {
            if (socModel.startsWith("MT")) return "MediaTek";
            if (socModel.contains("Snapdragon")) return "Qualcomm";
            if (socModel.contains("Exynos")) return "Samsung";
            if (socModel.contains("Kirin")) return "HiSilicon";
        }
        
        // 基於Build.MANUFACTURER推斷
        String manufacturer = android.os.Build.MANUFACTURER;
        if ("Xiaomi".equalsIgnoreCase(manufacturer) || "OPPO".equalsIgnoreCase(manufacturer) || 
            "OnePlus".equalsIgnoreCase(manufacturer) || "Realme".equalsIgnoreCase(manufacturer)) {
            return "MediaTek"; // 這些品牌經常使用MediaTek
        }
        
        return "Unknown";
    }
    
    /**
     * 從/proc/cpuinfo讀取Hardware信息
     */
    private static String readHardwareFromCpuInfo() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Hardware")) {
                    reader.close();
                    return line.split(":")[1].trim();
                }
            }
            reader.close();
        } catch (IOException e) {
            Log.w(TAG, "Could not read hardware from /proc/cpuinfo: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 基於SoC型號推斷GPU型號
     */
    private static String getGpuInfoBySoc(String socModel) {
        if (socModel != null) {
            String upper = socModel.toUpperCase();
            if (upper.contains("MT8195")) {
                return "ARM Mali-G57 MC5";
            } else if (upper.contains("MT8188")) {
                return "ARM Mali-G57 MC3";
            } else if (upper.contains("MT8192")) {
                return "ARM Mali-G77 MC5";
            } else if (upper.contains("SNAPDRAGON")) {
                return "Qualcomm Adreno";
            } else if (upper.contains("EXYNOS")) {
                return "ARM Mali";
            } else if (upper.contains("KIRIN")) {
                return "ARM Mali";
            }
        }
        return null;
    }
    
    /**
     * 基於Hardware字段推斷GPU型號 (備用方法)
     */
    private static String getGpuInfoByHardware(String hardware) {
        if (hardware != null) {
            String upper = hardware.toUpperCase();
            if (upper.contains("MALI")) {
                return "ARM Mali (Generic)";
            } else if (upper.contains("ADRENO")) {
                return "Qualcomm Adreno (Generic)";
            } else if (upper.contains("MEDIATEK") || upper.contains("MTK")) {
                return "ARM Mali (MediaTek)";
            }
        }
        return "Unknown GPU";
    }

    public static String getAcceleratorInfo() {
        StringBuilder info = new StringBuilder();
        info.append("Device: ").append(android.os.Build.MODEL).append("\n");
        info.append("Hardware: ").append(android.os.Build.HARDWARE).append("\n");
        
        // 獲取SoC信息
        String socModel = null;
        String socManufacturer = null;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            socModel = android.os.Build.SOC_MODEL;
            socManufacturer = android.os.Build.SOC_MANUFACTURER;
        } else {
            socModel = getSocModelFallback();
            socManufacturer = getSocManufacturerFallback();
        }
        
        if (socModel != null && !socModel.isEmpty()) {
            info.append("SoC: ").append(socManufacturer).append(" ").append(socModel).append("\n");
            
            // 推斷GPU信息
            String gpuInfo = getGpuInfoBySoc(socModel);
            if (gpuInfo != null) {
                info.append("GPU: ").append(gpuInfo).append("\n");
            }
        }
        
        info.append("Cores: ").append(Runtime.getRuntime().availableProcessors()).append("\n");

        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        info.append("Max Memory: ").append(maxMemory / 1024 / 1024).append("MB");

        return info.toString();
    }
    
    /**
     * 專門用於獲取SoC型號的公開方法 (用於Float16性能診斷)
     */
    public static String getSocModel() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            return android.os.Build.SOC_MODEL;
        } else {
            return getSocModelFallback();
        }
    }
    
    /**
     * 檢查是否為MediaTek MT8195
     */
    public static boolean isMT8195() {
        String socModel = getSocModel();
        return socModel != null && socModel.toUpperCase().contains("MT8195");
    }
}