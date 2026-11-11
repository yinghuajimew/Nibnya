# 裁剪版 Java LevelDB（无 Guava / 无 Snappy）

来源：https://github.com/HiveGamesOSS/leveldb-mcpe-java 版本 `1.1.0`
（即 `com.hivemc.leveldb:leveldb:1.1.0`）

构建：`python build_leveldb_java.py`（在本目录的上一级）

---

## 为什么要裁剪

原版 `leveldb` 模块的依赖：

```xml
<dependency>org.xerial.snappy:snappy-java</dependency>  <!-- optional -->
<dependency>org.iq80.snappy:snappy</dependency>          <!-- optional -->
<dependency>com.google.guava:guava</dependency>          <!-- 必需 -->
```

Guava 是 `guava-20.0.jar`，**2.33 MB / 1814 个类**。

而本库实际只用到其中 **30 个类型**。按可达性闭包算过：即使只保留被牵连的
类，仍然要 1027 个（57%）—— 因为 `cache.Cache` 和 `collect.ImmutableList`
的传递依赖太广，省不下多少。

所以没有走「挑类打包」，而是**把这 30 个类型各自实现一遍**。
代码量很小，集中在 `minimal/Minimal.java` 一个文件里。

Snappy 更直接：**整个用不上**。Minecraft 存档的区块是 raw deflate
（`CompressionType.ZLIB_RAW`），从不使用 Snappy 块。

---

## 结果

| | 原版 | 裁剪后 |
|---|---|---|
| leveldb jar | 266 KB | **255 KB** |
| leveldb-api jar | 9 KB | **8 KB** |
| guava.jar | 2330 KB | **0** |
| snappy（可选） | ~1000 KB | **0** |
| 需要打包进 APK | 2605 KB | **263 KB** |

APK 里不再需要 `guava.jar`。

---

## 改了哪些文件

### 1. `minimal/Minimal.java`（新增，约 700 行）

用到的 Guava API 的等价实现：

| Guava | 替代 | 说明 |
|---|---|---|
| `base.Preconditions` | 静态方法 | 断言，行为一致 |
| `base.Strings.padStart` | `Minimal.padStart` | 文件名补零 |
| `base.Throwables.propagate*` | `Minimal.propagate*` | 异常转抛 |
| `base.Joiner` | `Minimal.join` | 跳过 null |
| `base.Function` | `Minimal.Function` | 同签名 |
| `collect.ImmutableList` | `Minimal.ImmutableList` | 构造后不可变的 ArrayList |
| `collect.Multimap` | `Minimal.Multimap` | 键有序 / 无序两种 |
| `collect.Maps.immutableEntry` | `Minimal.immutableEntry` | |
| `collect.Lists.transform` | `Minimal.transform` | 非惰性 |
| `collect.Iterators.peekingIterator` | `Minimal.PeekingIterator` | 语义一致 |
| `collect.ComparisonChain` | `Minimal.ComparisonChain` | 短路比较 |
| `collect.MapMaker.weakKeys` | `WeakHashMap` | 语义一致 |
| `collect.ImmutableSortedSet.of` | `Minimal.immutableSortedSetOf` | |
| `cache.CacheBuilder / LoadingCache` | 同名字段 | **按条数** LRU |
| `cache.maximumWeight + Weigher` | `Minimal.WeightedCache` | **按权重**淘汰 |
| `io.Files.map / asCharSource` | `Minimal.map* / readFile` | |
| `io.Closer` | `Minimal.Closer` | 逆序关闭 |
| `util.concurrent.ThreadFactoryBuilder` | `Minimal.ThreadFactoryBuilder` | |
| `annotations.VisibleForTesting` | 直接删掉 | 仅标记用 |

### 2. `util/Snappy.java`（重写）

`available()` 恒返回 `false`，其余方法抛 `UnsupportedOperationException`。
`DbImpl` 在要求 SNAPPY 压缩时会立刻报错，而不是读到一半才崩。

### 3. `util/ZLib.java`（去 Android 依赖）

原版用 `Build.VERSION.SDK_INT` 判断是否支持
`Inflater.setInput(ByteBuffer)`（Android 15 才有）。
这要求编译时依赖 `android.jar`，桌面 JVM 上编不过。

改成**反射探测方法是否存在**：有就用零拷贝版本，没有走 `byte[]` 版本。

### 4. `fileenv/ByteBufferSupport.java`（去 MethodHandle）

原版用 `MethodHandle` 调 `Unsafe.invokeCleaner`，D8 要求 `min-api 26`，
而本项目 `minSdk 23`，dex 直接失败：

```
D8: MethodHandle.invoke and MethodHandle.invokeExact are only supported
    starting with Android O (--min-api 26)
```

改成纯反射（`Method.invoke`）。拿不到 `Unsafe` 就静默跳过 ——
映射由 GC 回收，只影响句柄释放时机，不影响数据正确性。

### 5. 其余 41 个文件

只是 import 替换 + 调用点改名（`Preconditions.checkX` → `Minimal.checkX`），
以及 `TableCache` 的匿名内部类替代 lambda。

---

## 验证（改完必须跑）

用真实存档逐键算 SHA-256，与裁剪前对比。**哈希必须完全一致。**

```
原版（Guava）  : keys=125559  bytes=211780071
                 digest=62e289b6a013838bf0956d06da6ff94e9c83f933420efdb5e2d64c2acdf4b49b
裁剪后（无 Guava）: keys=125559  bytes=211780071
                 digest=62e289b6a013838bf0956d06da6ff94e9c83f933420efdb5e2d64c2acdf4b49b
```

测试程序（`V.java`）做的事：遍历整库，把每个 `key + 0 + value + 0`
喂进 SHA-256。这比只比键数量严格得多 —— 后者漏了「键对但值错」。

样本：`C:\MinecraftRef\samples\ext\w1\db`（127202 个 SST 键的存档）

---

## 已知行为差异

| 场景 | 影响 |
|---|---|
| 并发读 | `LoadingCache` 是单锁，高并发下略慢于 Guava 分片锁。LevelDB 的 TableCache 本来就靠上层同步，够用。 |
| 缓存淘汰 | Guava 异步清理；这里同步。对 TableCache 反而更安全（不会有「已淘汰但句柄没关」的窗口）。 |
| Snappy 压缩的库 | **读不了**。MC 存档不会产生这种块。若将来要支持，需把真实现加回来。 |
| 写 SNAPPY | 同上，`DbImpl` 会直接抛错而不是静默降级。 |

---

## 重新构建

```bash
cd C:\AI文件夹\樱喵编辑器
python build_leveldb_java.py
```

产物直接覆盖 `编辑器/app/libs/leveldb-java*.jar`，然后正常 `sync-build.bat` + gradle。

如果以后 leveldb 代码需要**新增** Guava 用法（比如引用了 `base.Optional`），
在 `minimal/Minimal.java` 里补上对应实现即可，不用重新引入 guava.jar。
