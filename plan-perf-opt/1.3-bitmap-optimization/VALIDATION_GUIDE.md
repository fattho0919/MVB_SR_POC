# Story 1.3: Bitmap Memory Optimization - Validation Guide

## 🎯 驗證目標

驗證 Bitmap Memory Optimization 是否達到以下關鍵指標：
1. **Bitmap 重用率 > 80%**
2. **OOM 頻率 < 0.01%**
3. **GC 時間 < 5% of runtime**
4. **記憶體峰值 < 320MB for 1080p**
5. **分配速率 < 1MB/s steady state**

## 📋 驗證方法

### 方法 1: 自動化測試 (推薦)

#### 步驟 1: 執行 Instrumented Tests
```bash
# 連接 Android 設備或模擬器
adb devices

# 執行完整驗證測試套件
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.sr_poc.BitmapPoolValidationTest

# 或執行單一測試
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.sr_poc.BitmapPoolValidationTest#testBitmapReuseRate
```

#### 步驟 2: 查看測試結果
```bash
# 查看 logcat 輸出
adb logcat -s BitmapPoolValidation:D

# 查看測試報告
open app/build/reports/androidTests/connected/index.html
```

#### 預期結果：
```
=== Test 1: Bitmap Reuse Rate ===
Reuse rate: 85.00% (Hits: 85)
✓ PASSED

=== Test 2: OOM Prevention ===
OOM frequency: 0.00% (0/20)
✓ PASSED

=== Test 3: GC Impact ===
GC frequency: 2.00% (10 GCs in 500 iterations)
✓ PASSED

=== Test 4: Memory Peak for 1080p ===
Peak memory increase: 245MB
✓ PASSED

=== Test 5: Allocation Rate ===
Allocation rate: 0.45 MB/s
✓ PASSED
```

### 方法 2: 視覺化 Demo 測試

#### 步驟 1: 註冊 Demo Activity
在 `AndroidManifest.xml` 中添加：
```xml
<activity 
    android:name=".BitmapPoolDemoActivity"
    android:label="Bitmap Pool Demo" />
```

#### 步驟 2: 啟動 Demo Activity
在 MainActivity 中添加測試按鈕或直接啟動：
```java
// 在 MainActivity 的某個按鈕點擊事件中
Intent intent = new Intent(this, BitmapPoolDemoActivity.class);
startActivity(intent);
```

#### 步驟 3: 執行測試場景

1. **點擊 "Test WITH Pool"**
   - 觀察記憶體增長
   - 查看 Hit Rate 是否逐漸上升
   - 記錄完成時間和記憶體使用

2. **點擊 "Test WITHOUT Pool"**
   - 對比記憶體增長（應該更高）
   - 觀察 GC 頻率（應該更頻繁）
   - 對比完成時間（應該更慢）

3. **點擊 "Stress Test"**
   - 測試大圖處理
   - 驗證 OOM 預防機制
   - 確認成功率 > 99%

### 方法 3: 生產環境驗證

#### 步驟 1: 啟用配置
確認 `sr_config.json` 中的配置：
```json
{
  "bitmap_pool": {
    "enabled": true,
    "max_pool_size_mb": 64,
    "max_bitmaps_per_size": 3
  }
}
```

#### 步驟 2: 執行實際圖像處理
```bash
# 監控記憶體使用
adb shell dumpsys meminfo com.example.sr_poc

# 監控 GC 活動
adb logcat -s dalvikvm:D art:D
```

#### 步驟 3: 使用 Android Studio Profiler
1. 打開 Android Studio
2. Run → Profile 'app'
3. 選擇 Memory Profiler
4. 執行圖像處理操作
5. 觀察：
   - Allocations 圖表（應該平穩）
   - GC events（應該稀少）
   - Memory usage（應該有上限）

## 📊 驗證檢查清單

### 功能驗證
- [ ] Bitmap 可以成功從池中獲取
- [ ] Bitmap 可以正確釋放回池
- [ ] InBitmap 解碼正常工作
- [ ] 大圖像自動分塊處理
- [ ] 記憶體壓力響應正常

### 性能驗證
- [ ] Hit Rate > 80% 在穩定狀態
- [ ] 記憶體使用有明確上限
- [ ] GC 頻率明顯降低
- [ ] 無 OOM 崩潰
- [ ] 處理速度提升

### 穩定性驗證
- [ ] 多線程並發訪問安全
- [ ] 長時間運行無記憶體洩漏
- [ ] 配置變更正確處理
- [ ] 錯誤恢復機制有效

## 🔍 問題診斷

### 如果 Hit Rate 低於 80%
```bash
# 檢查池配置
adb shell getprop | grep bitmap

# 查看池統計
adb logcat -s BitmapPoolManager:D BitmapPool:D

# 可能原因：
# 1. 池大小太小
# 2. 圖像尺寸變化太大
# 3. 池被過早清理
```

### 如果仍有 OOM
```bash
# 檢查記憶體使用
adb shell dumpsys meminfo com.example.sr_poc | grep -A 10 "App Summary"

# 可能原因：
# 1. 池限制設置過高
# 2. 其他記憶體洩漏
# 3. 設備記憶體太小
```

### 如果 GC 頻繁
```bash
# 監控 GC 日誌
adb logcat -s dalvikvm-heap:D

# 可能原因：
# 1. 池未正常工作
# 2. Bitmap 未正確釋放
# 3. 其他對象頻繁分配
```

## 📈 性能基準對比

### 測試設備建議
- **低階設備**: 2-3GB RAM (如 Pixel 3a)
- **中階設備**: 4-6GB RAM (如 Pixel 5)
- **高階設備**: 8+GB RAM (如 Pixel 7 Pro)

### 預期改善數據

| 指標 | 無池化 | 有池化 | 改善 |
|------|--------|--------|------|
| 720p 處理時間 | ~120ms | ~80ms | -33% |
| 1080p 處理時間 | ~280ms | ~180ms | -36% |
| 記憶體峰值 | ~500MB | ~300MB | -40% |
| GC 暫停時間 | ~15ms | ~5ms | -67% |
| OOM 崩潰率 | 2-5% | <0.01% | -99% |

## 🚀 快速驗證腳本

創建 `validate_bitmap_pool.sh`:
```bash
#!/bin/bash

echo "=== Bitmap Pool Validation ==="
echo "1. Building and installing app..."
./gradlew installDebug

echo "2. Running validation tests..."
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.sr_poc.BitmapPoolValidationTest

echo "3. Extracting metrics..."
adb logcat -d | grep "BitmapPoolValidation" > validation_results.txt

echo "4. Checking memory..."
adb shell dumpsys meminfo com.example.sr_poc | grep -A 20 "TOTAL" >> validation_results.txt

echo "5. Results saved to validation_results.txt"
cat validation_results.txt | grep -E "(PASSED|FAILED|Hit Rate|OOM|Memory)"

echo "=== Validation Complete ==="
```

## 📝 驗證報告模板

```markdown
## Bitmap Pool Validation Report

**Date**: [DATE]
**Device**: [DEVICE MODEL]
**Android Version**: [VERSION]

### Test Results

| Test | Target | Actual | Status |
|------|--------|--------|--------|
| Reuse Rate | >80% | [X]% | ✓/✗ |
| OOM Rate | <0.01% | [X]% | ✓/✗ |
| GC Time | <5% | [X]% | ✓/✗ |
| Memory Peak | <320MB | [X]MB | ✓/✗ |
| Allocation Rate | <1MB/s | [X]MB/s | ✓/✗ |

### Performance Comparison
- WITH Pool: [X]ms average, [X]MB memory
- WITHOUT Pool: [X]ms average, [X]MB memory
- Improvement: [X]% faster, [X]% less memory

### Issues Found
- [List any issues]

### Recommendation
- [PASS/FAIL for production]
```

## ✅ 驗證成功標準

Story 1.3 驗證成功需要滿足：
1. 所有自動化測試通過
2. Demo 顯示明顯性能改善
3. 無新增崩潰或 ANR
4. 記憶體使用符合預期
5. 用戶體驗無負面影響

執行完整驗證後，Story 1.3 即可視為完成並準備部署！