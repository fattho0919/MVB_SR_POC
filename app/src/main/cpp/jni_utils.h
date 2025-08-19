#pragma once

#include <jni.h>
#include <string>
#include <chrono>
#include <android/log.h>

#define JNI_TAG "JNIUtils"
#define JNI_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, JNI_TAG, __VA_ARGS__)
#define JNI_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, JNI_TAG, __VA_ARGS__)

namespace jni_utils {

// Helper class for automatic string release
class ScopedUtfChars {
public:
    ScopedUtfChars(JNIEnv* env, jstring str) 
        : env_(env), str_(str), chars_(nullptr) {
        if (str) {
            chars_ = env->GetStringUTFChars(str, nullptr);
        }
    }
    
    ~ScopedUtfChars() {
        if (chars_) {
            env_->ReleaseStringUTFChars(str_, chars_);
        }
    }
    
    const char* c_str() const { return chars_; }
    bool is_valid() const { return chars_ != nullptr; }
    
private:
    JNIEnv* env_;
    jstring str_;
    const char* chars_;
};

// Helper class for local reference management
class LocalRefGuard {
public:
    LocalRefGuard(JNIEnv* env, jobject obj) 
        : env_(env), obj_(obj) {}
    
    ~LocalRefGuard() {
        if (obj_) {
            env_->DeleteLocalRef(obj_);
        }
    }
    
private:
    JNIEnv* env_;
    jobject obj_;
};

// Exception handling utilities
inline void ThrowRuntimeException(JNIEnv* env, const char* msg) {
    jclass exClass = env->FindClass("java/lang/RuntimeException");
    if (exClass != nullptr) {
        env->ThrowNew(exClass, msg);
        env->DeleteLocalRef(exClass);
    }
}

inline void ThrowOutOfMemoryError(JNIEnv* env, const char* msg) {
    jclass exClass = env->FindClass("java/lang/OutOfMemoryError");
    if (exClass != nullptr) {
        env->ThrowNew(exClass, msg);
        env->DeleteLocalRef(exClass);
    }
}

inline bool CheckException(JNIEnv* env) {
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return true;
    }
    return false;
}

// Buffer utilities
inline bool ValidateDirectBuffer(JNIEnv* env, jobject buffer, 
                                const char* bufferName, 
                                size_t minSize = 0) {
    if (!buffer) {
        JNI_LOGE("%s is null", bufferName);
        return false;
    }
    
    void* ptr = env->GetDirectBufferAddress(buffer);
    if (!ptr) {
        JNI_LOGE("%s is not a direct buffer", bufferName);
        return false;
    }
    
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (capacity < 0) {
        JNI_LOGE("%s has invalid capacity", bufferName);
        return false;
    }
    
    if (minSize > 0 && static_cast<size_t>(capacity) < minSize) {
        JNI_LOGE("%s size %lld is less than required %zu", 
                bufferName, (long long)capacity, minSize);
        return false;
    }
    
    return true;
}

// Array utilities
template<typename T>
class ScopedPrimitiveArray {
public:
    ScopedPrimitiveArray(JNIEnv* env, jarray array, bool is_copy = false)
        : env_(env), array_(array), elements_(nullptr), is_copy_(is_copy) {}
    
    ~ScopedPrimitiveArray() {
        Release();
    }
    
    void Release() {
        if (elements_) {
            ReleaseElements();
            elements_ = nullptr;
        }
    }
    
protected:
    JNIEnv* env_;
    jarray array_;
    T* elements_;
    bool is_copy_;
    
    virtual void ReleaseElements() = 0;
};

class ScopedByteArray : public ScopedPrimitiveArray<jbyte> {
public:
    ScopedByteArray(JNIEnv* env, jbyteArray array) 
        : ScopedPrimitiveArray(env, array) {
        if (array) {
            elements_ = env->GetByteArrayElements(array, nullptr);
        }
    }
    
    jbyte* data() { return elements_; }
    const jbyte* data() const { return elements_; }
    
protected:
    void ReleaseElements() override {
        env_->ReleaseByteArrayElements(
            static_cast<jbyteArray>(array_), elements_, 0);
    }
};

class ScopedIntArray : public ScopedPrimitiveArray<jint> {
public:
    ScopedIntArray(JNIEnv* env, jintArray array) 
        : ScopedPrimitiveArray(env, array) {
        if (array) {
            elements_ = env->GetIntArrayElements(array, nullptr);
        }
    }
    
    jint* data() { return elements_; }
    const jint* data() const { return elements_; }
    
protected:
    void ReleaseElements() override {
        env_->ReleaseIntArrayElements(
            static_cast<jintArray>(array_), elements_, 0);
    }
};

// Performance measurement
class ScopedTimer {
public:
    ScopedTimer(const char* operation) 
        : operation_(operation),
          start_(std::chrono::high_resolution_clock::now()) {}
    
    ~ScopedTimer() {
        auto end = std::chrono::high_resolution_clock::now();
        auto duration = std::chrono::duration_cast<std::chrono::microseconds>(end - start_);
        JNI_LOGD("%s took %lld us", operation_, (long long)duration.count());
    }
    
private:
    const char* operation_;
    std::chrono::time_point<std::chrono::high_resolution_clock> start_;
};

} // namespace jni_utils