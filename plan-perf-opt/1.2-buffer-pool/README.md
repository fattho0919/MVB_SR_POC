# Story 1.2: Buffer Pool Implementation

## 📋 Story 概要

**目標**: 實作可重用的 ByteBuffer 池，避免重複分配和釋放記憶體，減少記憶體碎片和分配開銷。

**預期成果**:
- 記憶體峰值降低 20%
- Buffer 分配時間 < 1ms
- 零動態記憶體分配during推論

**依賴**: Story 1.1 (DirectByteBuffer Migration) 必須先完成

## 🎯 背景與動機

### 現況問題
- 每次模型切換都重新分配 buffer
- 大量臨時 buffer 造成記憶體碎片
- Direct memory 分配成本高
- 記憶體使用峰值不可控

### 解決方案
```java
// 現況: 每次都分配新 buffer
ByteBuffer buffer = ByteBuffer.allocateDirect(size); // Expensive!

// 優化: 從池中重用
ByteBuffer buffer = bufferPool.acquire(size); // Fast!
// 使用完畢
bufferPool.release(buffer); // Return to pool
```

## 📝 實作範圍

### 新增類別
1. `DirectBufferPool.java` - 核心池實現
2. `PooledByteBuffer.java` - 包裝類別追蹤使用
3. `BufferPoolManager.java` - 全域池管理

### 修改類別
1. `ThreadSafeSRProcessor.java` - 使用池化 buffer
2. `TileProcessor.java` - Tile buffer 池化
3. `BitmapConverter.java` - 轉換 buffer 池化

## 💻 實作細節

### DirectBufferPool 核心實現

```java
public class DirectBufferPool {
    private static final String TAG = "DirectBufferPool";
    
    // 按大小分組的 buffer 池
    private final Map<Integer, Queue<ByteBuffer>> pools = new ConcurrentHashMap<>();
    private final AtomicLong totalAllocated = new AtomicLong(0);
    private final long maxPoolSize;
    
    // 常用大小的預分配
    private static final int[] COMMON_SIZES = {
        1280 * 720 * 3 * 4,   // 720p FLOAT32
        1280 * 720 * 3 * 2,   // 720p FLOAT16
        1920 * 1080 * 3 * 4,  // 1080p FLOAT32
        1920 * 1080 * 3 * 2   // 1080p FLOAT16
    };
    
    public DirectBufferPool(long maxPoolSizeMB) {
        this.maxPoolSize = maxPoolSizeMB * 1024 * 1024;
        preallocateCommonSizes();
    }
    
    private void preallocateCommonSizes() {
        for (int size : COMMON_SIZES) {
            Queue<ByteBuffer> queue = new ConcurrentLinkedQueue<>();
            for (int i = 0; i < 2; i++) { // 預分配 2 個
                ByteBuffer buffer = allocateAlignedBuffer(size);
                queue.offer(buffer);
                totalAllocated.addAndGet(size);
            }
            pools.put(size, queue);
        }
        Log.d(TAG, "Preallocated buffers for common sizes");
    }
    
    public ByteBuffer acquire(int requestedSize) {
        // 找到最接近的大小
        int poolSize = findPoolSize(requestedSize);
        Queue<ByteBuffer> pool = pools.computeIfAbsent(poolSize, 
            k -> new ConcurrentLinkedQueue<>());
        
        ByteBuffer buffer = pool.poll();
        if (buffer == null) {
            // 池中沒有可用 buffer，分配新的
            if (totalAllocated.get() + poolSize <= maxPoolSize) {
                buffer = allocateAlignedBuffer(poolSize);
                totalAllocated.addAndGet(poolSize);
                Log.d(TAG, "Allocated new buffer, size: " + poolSize);
            } else {
                // 超過池大小限制，等待或拋出異常
                throw new OutOfMemoryError("Buffer pool exhausted");
            }
        }
        
        buffer.clear();
        buffer.limit(requestedSize);
        return buffer;
    }
    
    public void release(ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect()) {
            return;
        }
        
        buffer.clear();
        int capacity = buffer.capacity();
        Queue<ByteBuffer> pool = pools.get(capacity);
        
        if (pool != null && pool.size() < 4) { // 每個大小最多保留 4 個
            pool.offer(buffer);
        } else {
            // 池已滿，讓 GC 回收
            cleanDirectBuffer(buffer);
            totalAllocated.addAndGet(-capacity);
        }
    }
    
    private int findPoolSize(int requestedSize) {
        // 向上取整到最近的 2 的冪次或常用大小
        for (int commonSize : COMMON_SIZES) {
            if (commonSize >= requestedSize) {
                return commonSize;
            }
        }
        // 如果不是常用大小，向上取整到 2 的冪次
        return Integer.highestOneBit(requestedSize - 1) << 1;
    }
    
    private ByteBuffer allocateAlignedBuffer(int size) {
        // 64-byte 對齊
        ByteBuffer buffer = ByteBuffer.allocateDirect(size);
        buffer.order(ByteOrder.nativeOrder());
        return buffer;
    }
    
    private void cleanDirectBuffer(ByteBuffer buffer) {
        // 使用 Cleaner API 立即釋放
        if (buffer.isDirect()) {
            try {
                Method cleanerMethod = buffer.getClass().getMethod("cleaner");
                cleanerMethod.setAccessible(true);
                Object cleaner = cleanerMethod.invoke(buffer);
                if (cleaner != null) {
                    Method cleanMethod = cleaner.getClass().getMethod("clean");
                    cleanMethod.invoke(cleaner);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to clean direct buffer", e);
            }
        }
    }
    
    public void clear() {
        for (Queue<ByteBuffer> pool : pools.values()) {
            ByteBuffer buffer;
            while ((buffer = pool.poll()) != null) {
                cleanDirectBuffer(buffer);
            }
        }
        pools.clear();
        totalAllocated.set(0);
        Log.d(TAG, "Buffer pool cleared");
    }
    
    public long getTotalAllocated() {
        return totalAllocated.get();
    }
    
    public Map<Integer, Integer> getPoolStats() {
        Map<Integer, Integer> stats = new HashMap<>();
        for (Map.Entry<Integer, Queue<ByteBuffer>> entry : pools.entrySet()) {
            stats.put(entry.getKey(), entry.getValue().size());
        }
        return stats;
    }
}
```

### BufferPoolManager 單例管理

```java
public class BufferPoolManager {
    private static volatile BufferPoolManager instance;
    private final DirectBufferPool primaryPool;
    private final DirectBufferPool tilePool;
    
    private BufferPoolManager(Context context) {
        // 根據裝置記憶體決定池大小
        long maxMemory = Runtime.getRuntime().maxMemory();
        long poolSize = Math.min(maxMemory / 4, 512 * 1024 * 1024); // 最多 512MB
        
        primaryPool = new DirectBufferPool(poolSize / 1024 / 1024);
        tilePool = new DirectBufferPool(poolSize / 2 / 1024 / 1024); // Tile 池較小
        
        // 註冊記憶體壓力回調
        context.registerComponentCallbacks(new ComponentCallbacks2() {
            @Override
            public void onTrimMemory(int level) {
                if (level >= TRIM_MEMORY_MODERATE) {
                    trimPools();
                }
            }
        });
    }
    
    public static BufferPoolManager getInstance(Context context) {
        if (instance == null) {
            synchronized (BufferPoolManager.class) {
                if (instance == null) {
                    instance = new BufferPoolManager(context);
                }
            }
        }
        return instance;
    }
    
    public ByteBuffer acquirePrimaryBuffer(int size) {
        return primaryPool.acquire(size);
    }
    
    public void releasePrimaryBuffer(ByteBuffer buffer) {
        primaryPool.release(buffer);
    }
    
    public ByteBuffer acquireTileBuffer(int size) {
        return tilePool.acquire(size);
    }
    
    public void releaseTileBuffer(ByteBuffer buffer) {
        tilePool.release(buffer);
    }
    
    private void trimPools() {
        // 在記憶體壓力下減少池大小
        Log.d(TAG, "Trimming buffer pools due to memory pressure");
        // 實作池縮減邏輯
    }
}
```

### 整合到 ThreadSafeSRProcessor

```java
public class ThreadSafeSRProcessor {
    private BufferPoolManager poolManager;
    private ByteBuffer pooledInputBuffer;
    private ByteBuffer pooledOutputBuffer;
    
    private void allocateBuffersFromPool() {
        // 從池中獲取 buffer
        int inputSize = calculateInputBufferSize();
        int outputSize = calculateOutputBufferSize();
        
        // 釋放舊 buffer
        if (pooledInputBuffer != null) {
            poolManager.releasePrimaryBuffer(pooledInputBuffer);
        }
        if (pooledOutputBuffer != null) {
            poolManager.releasePrimaryBuffer(pooledOutputBuffer);
        }
        
        // 獲取新 buffer
        pooledInputBuffer = poolManager.acquirePrimaryBuffer(inputSize);
        pooledOutputBuffer = poolManager.acquirePrimaryBuffer(outputSize);
        
        // 包裝成 TensorBuffer
        wrapBuffersAsTensors();
    }
    
    @Override
    public void close() {
        // 確保返還 buffer 到池
        if (pooledInputBuffer != null) {
            poolManager.releasePrimaryBuffer(pooledInputBuffer);
            pooledInputBuffer = null;
        }
        if (pooledOutputBuffer != null) {
            poolManager.releasePrimaryBuffer(pooledOutputBuffer);
            pooledOutputBuffer = null;
        }
        super.close();
    }
}
```

## ✅ 驗收標準

### 功能驗證
- [ ] Buffer 正確重用，無資料污染
- [ ] 記憶體壓力下自動縮減池大小
- [ ] 無記憶體洩漏

### 性能驗證
- [ ] Buffer 獲取時間 < 1ms
- [ ] 連續處理 10 張圖片，分配次數 <= 3
- [ ] 記憶體峰值降低 > 20%

### 測試案例
```java
@Test
public void testBufferReuse() {
    DirectBufferPool pool = new DirectBufferPool(100);
    
    // 第一次獲取
    ByteBuffer buffer1 = pool.acquire(1024);
    assertNotNull(buffer1);
    
    // 釋放
    pool.release(buffer1);
    
    // 再次獲取應該得到相同的 buffer
    ByteBuffer buffer2 = pool.acquire(1024);
    assertSame(buffer1, buffer2);
}

@Test
public void testPoolSizeLimit() {
    DirectBufferPool pool = new DirectBufferPool(1); // 1MB limit
    
    // 分配接近限制
    ByteBuffer buffer1 = pool.acquire(500 * 1024);
    ByteBuffer buffer2 = pool.acquire(400 * 1024);
    
    // 超過限制應該拋出異常
    assertThrows(OutOfMemoryError.class, () -> {
        pool.acquire(200 * 1024);
    });
}

@Test
public void testMemoryPressure() {
    BufferPoolManager manager = BufferPoolManager.getInstance(context);
    
    // 分配多個 buffer
    List<ByteBuffer> buffers = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
        buffers.add(manager.acquirePrimaryBuffer(1024 * 1024));
    }
    
    // 模擬記憶體壓力
    simulateMemoryPressure();
    
    // 池應該自動縮減
    assertTrue(manager.getTotalAllocated() < 10 * 1024 * 1024);
}
```

## 🚨 風險與緩解

### 風險 1: Buffer 資料污染
**描述**: 重用的 buffer 可能包含舊資料
**緩解**: 
- acquire() 時自動 clear()
- 添加 debug 模式填充特定 pattern

### 風險 2: 池大小不當
**描述**: 池太大浪費記憶體，太小頻繁分配
**緩解**:
- 動態調整池大小
- 監控命中率和分配頻率
- 根據使用模式優化

### 風險 3: 並發問題
**描述**: 多線程同時存取池
**緩解**:
- 使用 ConcurrentLinkedQueue
- 原子操作追蹤大小
- 線程安全的統計

## 📊 監控指標

```java
public class PoolMetrics {
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong allocations = new AtomicLong();
    
    public double getHitRate() {
        long total = hits.get() + misses.get();
        return total == 0 ? 0 : (double) hits.get() / total;
    }
    
    public void logStats() {
        Log.d(TAG, String.format(
            "Pool Stats - Hit Rate: %.2f%%, Allocations: %d, Pool Size: %dMB",
            getHitRate() * 100,
            allocations.get(),
            getTotalPoolSize() / 1024 / 1024
        ));
    }
}
```

## 📈 預期效果

### Before (無池化)
- 每次推論: 2-3 次 buffer 分配
- 分配時間: 5-10ms
- 記憶體峰值: 400MB
- GC 頻率: 高

### After (池化)
- 每次推論: 0 次 buffer 分配
- 獲取時間: < 1ms
- 記憶體峰值: 320MB (-20%)
- GC 頻率: 極低

## 🔗 相關 Stories

- **前置依賴**: 1.1 DirectByteBuffer Migration
- **後續優化**: 1.3 Bitmap Memory Optimization
- **相關**: 6.1 Triple Buffer Pipeline (使用池化 buffer)