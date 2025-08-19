#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <string>
#include <memory>
#include <chrono>
#include <cmath>
#include <algorithm>
#include "sr_engine.h"
#include "jni_utils.h"

#define LOG_TAG "SRNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Global engine instance management
static std::unique_ptr<SREngine> g_engine;

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGD("JNI_OnLoad called");
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    
    // Register native methods if needed
    // RegisterNativeMethods(env);
    
    LOGD("JNI_OnLoad completed successfully");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved) {
    LOGD("JNI_OnUnload called");
    g_engine.reset();
}

// Initialize engine
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
        config.use_gpu = false; // Initial version uses CPU
        
        if (!engine->Initialize(config)) {
            LOGE("Failed to initialize engine");
            env->ReleaseStringUTFChars(modelPath, path);
            return 0;
        }
        
        env->ReleaseStringUTFChars(modelPath, path);
        
        // Return pointer as handle
        SREngine* enginePtr = engine.release();
        LOGD("Engine created successfully: %p", enginePtr);
        return reinterpret_cast<jlong>(enginePtr);
        
    } catch (const std::exception& e) {
        LOGE("Exception creating engine: %s", e.what());
        env->ReleaseStringUTFChars(modelPath, path);
        return 0;
    }
}

// Destroy engine
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

// Test function - Get version
JNIEXPORT jstring JNICALL
Java_com_example_sr_1poc_NativeBridge_nativeGetVersion(
    JNIEnv* env,
    jobject /* this */) {
    
    std::string version = "SR Native v1.0.0";
    LOGD("Version requested: %s", version.c_str());
    return env->NewStringUTF(version.c_str());
}

// Performance benchmark
JNIEXPORT jlong JNICALL
Java_com_example_sr_1poc_NativeBridge_nativeBenchmark(
    JNIEnv* env,
    jobject /* this */,
    jint iterations) {
    
    LOGD("Running benchmark with %d iterations", iterations);
    
    auto start = std::chrono::high_resolution_clock::now();
    
    // Simple computation test
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

// DirectByteBuffer access test
JNIEXPORT jboolean JNICALL
Java_com_example_sr_1poc_NativeBridge_nativeTestDirectBuffer(
    JNIEnv* env,
    jobject /* this */,
    jobject buffer) {
    
    // Get direct buffer address
    void* bufferPtr = env->GetDirectBufferAddress(buffer);
    jlong bufferSize = env->GetDirectBufferCapacity(buffer);
    
    if (bufferPtr == nullptr || bufferSize <= 0) {
        LOGE("Invalid direct buffer");
        return JNI_FALSE;
    }
    
    LOGD("Direct buffer access successful - ptr: %p, size: %lld", 
         bufferPtr, (long long)bufferSize);
    
    // Test write and read
    uint8_t* data = static_cast<uint8_t*>(bufferPtr);
    jlong testSize = std::min(static_cast<jlong>(100), bufferSize);
    for (int i = 0; i < testSize; i++) {
        data[i] = i % 256;
    }
    
    // Verify data
    for (int i = 0; i < testSize; i++) {
        if (data[i] != (i % 256)) {
            LOGE("Data verification failed at index %d", i);
            return JNI_FALSE;
        }
    }
    
    LOGI("DirectBuffer test passed");
    return JNI_TRUE;
}

// Process image using native engine
JNIEXPORT jboolean JNICALL
Java_com_example_sr_1poc_NativeBridge_nativeProcessImage(
    JNIEnv* env,
    jobject /* this */,
    jlong engineHandle,
    jobject inputBuffer,
    jobject outputBuffer,
    jint width,
    jint height) {
    
    if (engineHandle == 0) {
        LOGE("Invalid engine handle");
        return JNI_FALSE;
    }
    
    SREngine* engine = reinterpret_cast<SREngine*>(engineHandle);
    
    // Get buffer pointers
    uint8_t* inputPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(inputBuffer));
    uint8_t* outputPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(outputBuffer));
    
    if (!inputPtr || !outputPtr) {
        LOGE("Failed to get buffer addresses");
        return JNI_FALSE;
    }
    
    LOGD("Processing image: %dx%d", width, height);
    
    try {
        bool result = engine->Process(inputPtr, outputPtr, width, height);
        if (!result) {
            LOGE("Engine processing failed");
            return JNI_FALSE;
        }
        
        LOGD("Image processing completed");
        return JNI_TRUE;
        
    } catch (const std::exception& e) {
        LOGE("Exception during processing: %s", e.what());
        return JNI_FALSE;
    }
}

} // extern "C"