# Story 1.1: DirectByteBuffer Migration

## 📋 Story 概要

**目標**: 將所有模型的記憶體分配從 HeapByteBuffer 遷移到 DirectByteBuffer，減少 Java heap 壓力和 GC 活動。

**預期成果**:
- 性能提升 5-10%
- GC 暫停時間減少 50%
- JNI 傳輸效率提升

## 🎯 背景與動機

### 現況問題
- 目前只有 INT8 模型使用 DirectByteBuffer
- FLOAT32 和 FLOAT16 模型仍使用 heap memory
- 每次推論都觸發 Java ↔ Native 資料複製
- GC 壓力導致不可預測的延遲

### 技術原理
```java
// 現況 (Heap Buffer)
ByteBuffer heapBuffer = ByteBuffer.allocate(size);
// JNI 需要複製資料: Java Heap → Native Memory

// 優化後 (Direct Buffer)  
ByteBuffer directBuffer = ByteBuffer.allocateDirect(size);
// JNI 直接存取: No Copy Needed
```

## 📝 實作範圍

### 需要修改的檔案
1. `ThreadSafeSRProcessor.java`
   - `allocateBuffers()` 方法
   - `createFloatBuffer()` 方法
   - `createFloat16Buffer()` 方法

2. `TensorBuffer` 使用處
   - 確保所有 TensorBuffer 使用 direct backing

3. `BitmapConverter.java`
   - 輸入/輸出轉換使用 direct buffer

## 💻 實作細節

### Step 1: 修改 Buffer 分配邏輯

```java
// ThreadSafeSRProcessor.java
private void allocateBuffers() {
    DataType inputDataType = currentInterpreter.getInputTensor(0).dataType();
    DataType outputDataType = currentInterpreter.getOutputTensor(0).dataType();
    
    // 統一使用 DirectByteBuffer
    if (inputDataType == DataType.FLOAT32) {
        int bufferSize = actualInputWidth * actualInputHeight * 3 * 4; // FLOAT32 = 4 bytes
        inputByteBuffer = ByteBuffer.allocateDirect(bufferSize);
        inputByteBuffer.order(ByteOrder.nativeOrder());
        inputBuffer = TensorBuffer.createFrom(
            TensorBuffer.createFixedSize(new int[]{1, actualInputHeight, actualInputWidth, 3}, 
            DataType.FLOAT32), 
            inputByteBuffer
        );
    } else if (inputDataType == DataType.FLOAT16) {
        int bufferSize = actualInputWidth * actualInputHeight * 3 * 2; // FLOAT16 = 2 bytes
        inputByteBuffer = ByteBuffer.allocateDirect(bufferSize);
        inputByteBuffer.order(ByteOrder.nativeOrder());
        // Create wrapper for FLOAT16
    }
    // ... similar for output buffers
}
```

### Step 2: 優化 Bitmap 轉換

```java
// BitmapConverter.java
public static void bitmapToDirectBuffer(Bitmap bitmap, ByteBuffer buffer) {
    // 確保 buffer 是 direct
    if (!buffer.isDirect()) {
        throw new IllegalArgumentException("Buffer must be direct");
    }
    
    // 使用 copyPixelsToBuffer 直接寫入
    bitmap.copyPixelsToBuffer(buffer);
    buffer.rewind();
}
```

### Step 3: 記憶體對齊優化

```java
// 確保 64-byte 對齊以優化 cache line
private ByteBuffer allocateAlignedDirectBuffer(int size) {
    int alignedSize = ((size + 63) / 64) * 64;
    ByteBuffer buffer = ByteBuffer.allocateDirect(alignedSize);
    buffer.order(ByteOrder.nativeOrder());
    buffer.limit(size); // 設定實際使用大小
    return buffer;
}
```

## ✅ 驗收標準

### 功能驗證
- [x] **代碼實作完成** - 所有 DirectByteBuffer 邏輯已實作 ✅
- [x] **編譯成功** - 無編譯錯誤，APK 生成成功 ✅
- [x] **Feature Flag** - 可安全啟用/禁用功能 ✅
- [ ] **實際推論測試** - 需要在實機上測試各種模型 ⏳
- [ ] **記憶體洩漏檢測** - 需要長時間運行測試 ⏳

### 性能驗證 
- [x] **實作就緒** - DirectMemoryUtils 提供記憶體監控 ✅
- [ ] **Memory Profiler 測量** - 需要實機 profiling ⏳ 
- [ ] **GC 頻率測量** - 需要實際運行測試 ⏳
- [ ] **性能基準對比** - 需要 before/after 測量 ⏳

### 實作驗證
- [x] **配置支援** - sr_config.json 中的 feature flag ✅
- [x] **記憶體工具** - DirectMemoryUtils 完整實作 ✅  
- [x] **核心修改** - ThreadSafeSRProcessor 支援所有資料類型 ✅
- [x] **清理機制** - close() 方法中的 buffer cleanup ✅
- [x] **錯誤處理** - 異常情況的 fallback 機制 ✅

### 測試案例
```java
@Test
public void testDirectBufferAllocation() {
    // Given
    ThreadSafeSRProcessor processor = new ThreadSafeSRProcessor(context);
    
    // When
    processor.initializeForMode(ProcessingMode.CPU);
    
    // Then
    assertTrue(processor.getInputBuffer().isDirect());
    assertTrue(processor.getOutputBuffer().isDirect());
}

@Test
public void testGCReduction() {
    // 執行 100 次推論
    long gcCountBefore = getGCCount();
    for (int i = 0; i < 100; i++) {
        processor.processImage(testImage);
    }
    long gcCountAfter = getGCCount();
    
    // GC 次數應該 < 5
    assertTrue((gcCountAfter - gcCountBefore) < 5);
}
```

## 🚨 風險與緩解

### 風險 1: Direct Memory 不足
**描述**: DirectByteBuffer 使用 off-heap memory，可能耗盡
**緩解**: 
- 設定 `-XX:MaxDirectMemorySize=512m`
- 實作 buffer 重用池
- 監控 direct memory 使用

### 風險 2: Memory Leak
**描述**: DirectByteBuffer 不被 GC 自動管理
**緩解**:
- 實作顯式 `close()` 方法
- 使用 `Cleaner` API 確保釋放
- 加入 memory leak detection

### 風險 3: 相容性問題
**描述**: 某些裝置可能對 direct memory 有限制
**緩解**:
- 實作 fallback 機制
- 運行時檢測可用 direct memory
- 保留 heap buffer 選項

## 📊 監控指標

```java
// 監控 Direct Memory 使用
public class DirectMemoryMonitor {
    public static long getUsedDirectMemory() {
        return sun.misc.VM.maxDirectMemory() - 
               sun.misc.SharedSecrets.getJavaNioAccess().getDirectBufferPool().getMemoryUsed();
    }
    
    public static void logMemoryStats() {
        Log.d(TAG, "Direct Memory: " + getUsedDirectMemory() / 1024 / 1024 + "MB");
        Log.d(TAG, "Heap Memory: " + Runtime.getRuntime().totalMemory() / 1024 / 1024 + "MB");
    }
}
```

## 🔄 回滾計畫

如果出現問題，可通過 feature flag 快速回滾：

```java
// ConfigManager.java
public boolean useDirectByteBuffer() {
    return config.getBoolean("use_direct_buffer", false);
}

// ThreadSafeSRProcessor.java
if (configManager.useDirectByteBuffer()) {
    buffer = ByteBuffer.allocateDirect(size);
} else {
    buffer = ByteBuffer.allocate(size);
}
```

## 📈 預期效果

### Before
- Heap Memory: 300MB
- GC Pause: 20-50ms
- JNI Transfer: 5-10ms

### After  
- Heap Memory: 200MB (-33%)
- GC Pause: 5-10ms (-75%)
- JNI Transfer: < 1ms (-90%)

## 🔗 相關 Stories

- **後續**: 1.2 Buffer Pool Implementation (建立在 direct buffer 基礎上)
- **相關**: 3.3 Zero-Copy JNI Bridge (充分利用 direct buffer)