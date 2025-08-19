# Story 3.1: JNI Project Setup

## 📋 Story 概要

**目標**: 建立 JNI/NDK 基礎架構，為後續 Native 開發奠定基礎。

**預期成果**:
- NDK 構建系統配置完成
- 基本 JNI 橋接運作
- Native 日誌和除錯環境就緒

**依賴**: 無（可獨立開始）

## 🎯 背景與動機

### 為何需要 JNI
- 直接調用 TensorFlow Lite C API
- 實現零拷貝資料傳輸
- 精確控制記憶體管理
- 存取硬體特定功能

### 架構設計
```
MainActivity.java
    ↓ System.loadLibrary("sr_native")
NativeBridge.java (JNI Interface)
    ↓ Native Methods
native-lib.cpp (JNI Implementation)
    ↓
SREngine.cpp (Business Logic)
```

## 📝 實作範圍

### 新增檔案
1. `app/src/main/cpp/CMakeLists.txt` - 構建配置
2. `app/src/main/cpp/native-lib.cpp` - JNI 入口
3. `app/src/main/cpp/sr_engine.h/cpp` - 業務邏輯
4. `app/src/main/cpp/jni_utils.h` - JNI 輔助工具
5. `app/src/main/java/.../NativeBridge.java` - Java 介面

### 修改檔案
1. `app/build.gradle.kts` - 添加 NDK 配置
2. `MainActivity.java` - 載入 native library

## 💻 實作細節

### Step 1: 配置 build.gradle.kts

```kotlin
// app/build.gradle.kts
android {
    // ... existing config ...
    
    defaultConfig {
        // ... existing config ...
        
        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++17",
                    "-fexceptions",
                    "-frtti",
                    "-O3",
                    "-flto",
                    "-fvisibility=hidden"
                )
                
                // 針對不同 ABI 的優化
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
                
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_TOOLCHAIN=clang",
                    "-DANDROID_ARM_NEON=ON"
                )
            }
        }
        
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
    
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    
    packagingOptions {
        jniLibs {
            useLegacyPackaging = false
            pickFirsts += "**/*.so"
        }
    }
}

dependencies {
    // ... existing dependencies ...
}
```

### Step 2: 創建 CMakeLists.txt

```cmake
# app/src/main/cpp/CMakeLists.txt
cmake_minimum_required(VERSION 3.22.1)
project("sr_native")

# C++ 標準
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 編譯優化
set(CMAKE_BUILD_TYPE Release)
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -flto -fvisibility=hidden -ffunction-sections -fdata-sections")
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "-Wl,--gc-sections -Wl,--strip-all")

# 平台特定優化
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+fp16")
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon-vfpv4 -mfloat-abi=softfp")
endif()

# 查找必要的庫
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

# 源文件
add_library(sr_native SHARED
    native-lib.cpp
    sr_engine.cpp
    jni_utils.cpp
    memory_pool.cpp
)

# Include 目錄
target_include_directories(sr_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# 連結庫
target_link_libraries(sr_native
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
)

# 啟用 Link Time Optimization
set_property(TARGET sr_native PROPERTY INTERPROCEDURAL_OPTIMIZATION TRUE)
```

### Step 3: JNI 橋接實現

```cpp
// app/src/main/cpp/native-lib.cpp
#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <string>
#include <memory>
#include "sr_engine.h"
#include "jni_utils.h"

#define LOG_TAG "SRNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 全域引擎實例管理
static std::unique_ptr<SREngine> g_engine;

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGD("JNI_OnLoad called");
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    
    // 註冊 native methods (如果需要)
    // RegisterNativeMethods(env);
    
    LOGD("JNI_OnLoad completed successfully");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved) {
    LOGD("JNI_OnUnload called");
    g_engine.reset();
}

// 初始化引擎
JNIEXPORT jlong JNICALL
Java_com_example_sr_1poc_NativeBridge_nativeCreateEngine(
    JNIEnv* env,
    jobject /* this */,
    jstring modelPath,
    jint numThreads) {
    
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGD("Creating engine with model: %s, threads: %d", path, numThreads);
    
    try {
        auto engine = std::make_unique<SREngine>();
        
        SREngine::Config config;
        config.model_path = path;
        config.num_threads = numThreads;
        config.use_gpu = false; // 初始版本先用 CPU
        
        if (!engine->Initialize(config)) {
            LOGE("Failed to initialize engine");
            env->ReleaseStringUTFChars(modelPath, path);
            return 0;
        }
        
        env->ReleaseStringUTFChars(modelPath, path);
        
        // 返回指針作為 handle
        SREngine* enginePtr = engine.release();
        LOGD("Engine created successfully: %p", enginePtr);
        return reinterpret_cast<jlong>(enginePtr);
        
    } catch (const std::exception& e) {
        LOGE("Exception creating engine: %s", e.what());
        env->ReleaseStringUTFChars(modelPath, path);
        return 0;
    }
}

// 銷毀引擎
JNIEXPORT void JNICALL
Java_com_example_sr_1poc_NativeBridge_nativeDestroyEngine(
    JNIEnv* env,
    jobject /* this */,
    jlong engineHandle) {
    
    if (engineHandle != 0) {
        SREngine* engine = reinterpret_cast<SREngine*>(engineHandle);
        LOGD("Destroying engine: %p", engine);
        delete engine;
    }
}

// 測試函數
JNIEXPORT jstring JNICALL
Java_com_example_sr_1poc_NativeBridge_nativeGetVersion(
    JNIEnv* env,
    jobject /* this */) {
    
    std::string version = "SR Native v1.0.0";
    LOGD("Version requested: %s", version.c_str());
    return env->NewStringUTF(version.c_str());
}

// 性能測試
JNIEXPORT jlong JNICALL
Java_com_example_sr_1poc_NativeBridge_nativeBenchmark(
    JNIEnv* env,
    jobject /* this */,
    jint iterations) {
    
    LOGD("Running benchmark with %d iterations", iterations);
    
    auto start = std::chrono::high_resolution_clock::now();
    
    // 簡單的運算測試
    volatile float result = 0;
    for (int i = 0; i < iterations; i++) {
        for (int j = 0; j < 1000; j++) {
            result += std::sin(i * j) * std::cos(i + j);
        }
    }
    
    auto end = std::chrono::high_resolution_clock::now();
    auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end - start);
    
    LOGD("Benchmark completed in %lld ms", (long long)duration.count());
    return duration.count();
}

// DirectByteBuffer 存取測試
JNIEXPORT jboolean JNICALL
Java_com_example_sr_1poc_NativeBridge_nativeTestDirectBuffer(
    JNIEnv* env,
    jobject /* this */,
    jobject buffer) {
    
    // 獲取 direct buffer 地址
    void* bufferPtr = env->GetDirectBufferAddress(buffer);
    jlong bufferSize = env->GetDirectBufferCapacity(buffer);
    
    if (bufferPtr == nullptr || bufferSize <= 0) {
        LOGE("Invalid direct buffer");
        return JNI_FALSE;
    }
    
    LOGD("Direct buffer access successful - ptr: %p, size: %lld", 
         bufferPtr, (long long)bufferSize);
    
    // 測試寫入和讀取
    uint8_t* data = static_cast<uint8_t*>(bufferPtr);
    for (int i = 0; i < std::min(100LL, bufferSize); i++) {
        data[i] = i % 256;
    }
    
    return JNI_TRUE;
}

} // extern "C"
```

### Step 4: Java 端介面

```java
// app/src/main/java/com/example/sr_poc/NativeBridge.java
package com.example.sr_poc;

import android.util.Log;
import java.nio.ByteBuffer;

public class NativeBridge {
    private static final String TAG = "NativeBridge";
    private static boolean isLibraryLoaded = false;
    
    // Native library 載入
    static {
        try {
            System.loadLibrary("sr_native");
            isLibraryLoaded = true;
            Log.d(TAG, "Native library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library", e);
            isLibraryLoaded = false;
        }
    }
    
    // Engine handle
    private long nativeEngineHandle = 0;
    
    // Native methods
    private native long nativeCreateEngine(String modelPath, int numThreads);
    private native void nativeDestroyEngine(long engineHandle);
    private native String nativeGetVersion();
    private native long nativeBenchmark(int iterations);
    private native boolean nativeTestDirectBuffer(ByteBuffer buffer);
    
    // Public API
    public boolean isAvailable() {
        return isLibraryLoaded;
    }
    
    public boolean initialize(String modelPath, int numThreads) {
        if (!isLibraryLoaded) {
            Log.e(TAG, "Native library not loaded");
            return false;
        }
        
        if (nativeEngineHandle != 0) {
            Log.w(TAG, "Engine already initialized");
            return true;
        }
        
        nativeEngineHandle = nativeCreateEngine(modelPath, numThreads);
        return nativeEngineHandle != 0;
    }
    
    public void release() {
        if (nativeEngineHandle != 0) {
            nativeDestroyEngine(nativeEngineHandle);
            nativeEngineHandle = 0;
        }
    }
    
    public String getVersion() {
        return isLibraryLoaded ? nativeGetVersion() : "N/A";
    }
    
    public long benchmark(int iterations) {
        return isLibraryLoaded ? nativeBenchmark(iterations) : -1;
    }
    
    public boolean testDirectBuffer() {
        if (!isLibraryLoaded) {
            return false;
        }
        
        ByteBuffer buffer = ByteBuffer.allocateDirect(1024);
        return nativeTestDirectBuffer(buffer);
    }
}
```

### Step 5: 引擎骨架

```cpp
// app/src/main/cpp/sr_engine.h
#pragma once

#include <string>
#include <memory>
#include <vector>

class SREngine {
public:
    struct Config {
        std::string model_path;
        int num_threads = 4;
        bool use_gpu = false;
        bool use_npu = false;
    };
    
    SREngine();
    ~SREngine();
    
    bool Initialize(const Config& config);
    bool Process(const uint8_t* input, uint8_t* output, 
                int width, int height);
    void Release();
    
    // 禁止拷貝
    SREngine(const SREngine&) = delete;
    SREngine& operator=(const SREngine&) = delete;
    
private:
    class Impl;
    std::unique_ptr<Impl> pImpl;
};

// app/src/main/cpp/sr_engine.cpp
#include "sr_engine.h"
#include <android/log.h>

#define LOG_TAG "SREngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

class SREngine::Impl {
public:
    Config config;
    
    bool Initialize(const Config& cfg) {
        config = cfg;
        LOGD("Initializing with model: %s", config.model_path.c_str());
        // TODO: 實際初始化邏輯
        return true;
    }
    
    bool Process(const uint8_t* input, uint8_t* output, 
                int width, int height) {
        LOGD("Processing %dx%d image", width, height);
        // TODO: 實際處理邏輯
        return true;
    }
};

SREngine::SREngine() : pImpl(std::make_unique<Impl>()) {}
SREngine::~SREngine() = default;

bool SREngine::Initialize(const Config& config) {
    return pImpl->Initialize(config);
}

bool SREngine::Process(const uint8_t* input, uint8_t* output, 
                      int width, int height) {
    return pImpl->Process(input, output, width, height);
}

void SREngine::Release() {
    pImpl.reset();
}
```

## ✅ 驗收標準

### 功能驗證
- [ ] Native library 成功載入
- [ ] JNI 方法調用正常
- [ ] DirectByteBuffer 存取成功
- [ ] 無 UnsatisfiedLinkError

### 構建驗證
- [ ] Debug 和 Release 構建都成功
- [ ] arm64-v8a 和 armeabi-v7a 都生成
- [ ] APK 大小增加 < 500KB

### 測試案例
```java
@Test
public void testNativeLibraryLoading() {
    NativeBridge bridge = new NativeBridge();
    assertTrue(bridge.isAvailable());
}

@Test
public void testNativeVersion() {
    NativeBridge bridge = new NativeBridge();
    String version = bridge.getVersion();
    assertNotNull(version);
    assertTrue(version.contains("v1.0"));
}

@Test
public void testDirectBufferAccess() {
    NativeBridge bridge = new NativeBridge();
    assertTrue(bridge.testDirectBuffer());
}

@Test
public void testBenchmark() {
    NativeBridge bridge = new NativeBridge();
    long time = bridge.benchmark(100);
    assertTrue(time > 0);
}
```

## 🚨 風險與緩解

### 風險 1: ABI 相容性
**描述**: 不同架構的相容性問題
**緩解**: 
- 只支援主流 ABI (arm64-v8a, armeabi-v7a)
- 提供 fallback 到 Java 實現
- 運行時檢測 ABI

### 風險 2: 符號衝突
**描述**: 與其他 native library 衝突
**緩解**:
- 使用 -fvisibility=hidden
- 命名空間隔離
- 靜態連結常用庫

### 風險 3: 記憶體洩漏
**描述**: JNI 層記憶體管理錯誤
**緩解**:
- RAII 模式管理資源
- 使用智慧指標
- AddressSanitizer 檢測

## 📈 預期效果

### 基準測試結果
- JNI 調用開銷: < 0.1ms
- DirectBuffer 存取: < 0.01ms
- Native 運算速度: 2-3x faster than Java

### 後續優化空間
- TensorFlow Lite C API 整合
- SIMD 優化
- 多線程處理

## 🔗 相關 Stories

- **後續**: 3.2 Native Memory Allocator
- **後續**: 3.3 Zero-Copy JNI Bridge
- **相關**: 4.1 TFLite C Library Setup