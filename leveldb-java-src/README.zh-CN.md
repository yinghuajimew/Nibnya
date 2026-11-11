# LevelDB (MCPE) — 樱喵裁剪版

[English](./README.md) · **简体中文**

基于 [HiveGamesOSS/leveldb-mcpe-java](https://github.com/HiveGamesOSS/leveldb-mcpe-java) `1.1.0` 的裁剪版 ——
一个能读 Minecraft 基岩版（MCPE）存档的纯 Java LevelDB 实现。

这份拷贝存在的唯一理由：**上游的 jar 需要 Guava，而我们不想为此多背 2.3 MB。**

---

## 为什么要裁剪

上游 `leveldb` 模块的依赖声明：

```xml
<dependency>org.xerial.snappy:snappy-java</dependency>  <!-- optional -->
<dependency>org.iq80.snappy:snappy</dependency>          <!-- optional -->
<dependency>com.google.guava:guava</dependency>          <!-- 必需 -->
```

`guava-20.0.jar` 是 **2.33 MB / 1814 个类**，而本库只用到其中 **30 个类型**。

试过只打包可达闭包：仍然要 1027 个类（57%）——
`cache.Cache` 和 `collect.ImmutableList` 的传递依赖太广，省不下多少。
所以没有走「挑类打包」，而是把这 30 个类型**各自实现一遍**，
全部集中在 `minimal/Minimal.java` 一个文件里。

Snappy 直接整个去掉：Minecraft 的区块是 raw deflate
（`CompressionType.ZLIB_RAW`），从不产生 Snappy 块。

### 结果

| | 上游 | 本裁剪版 |
|---|---|---|
| `leveldb` jar | 266 KB | **255 KB** |
| `leveldb-api` jar | 9 KB | **8 KB** |
| `guava.jar` | 2330 KB | **0** |
| snappy（可选） | ~1000 KB | **0** |
| **需要打包进 APK** | **2605 KB** | **263 KB** |

---

## 验证方式

正确性是拿一份真实的 12.7 万键存档，在裁剪前后**逐键逐值**算哈希比对的。
只比键数量不够 —— 那会漏掉「键对但值错」的情况。

```
上游（Guava）   : keys=125559  bytes=211780071
                  digest=62e289b6a013838bf0956d06da6ff94e9c83f933420efdb5e2d64c2acdf4b49b
本裁剪版         : keys=125559  bytes=211780071
                  digest=62e289b6a013838bf0956d06da6ff94e9c83f933420efdb5e2d64c2acdf4b49b
```

完全一致。遍历程序把每条 `key + 0x00 + value + 0x00` 依次喂进 SHA-256。

---

## 改了哪些文件

与上游相比**只有下面这几个文件不同**，其余原样保留。

### `minimal/Minimal.java`（新增，约 700 行）

把实际用到的 Guava API 逐个实现：

| Guava | 替代实现 | 说明 |
|---|---|---|
| `base.Preconditions` | 静态方法 | 断言，行为一致 |
| `base.Strings.padStart` | `Minimal.padStart` | 文件名补零 |
| `base.Throwables.propagate*` | `Minimal.propagate*` | 异常转抛 |
| `base.Joiner` | `Minimal.join` | 跳过 null |
| `base.Function` | `Minimal.Function` | 同签名 |
| `collect.ImmutableList` | `Minimal.ImmutableList` | 构造后不暴露修改入口 |
| `collect.Multimap` | `Minimal.Multimap` | 键有序 / 无序两种 |
| `collect.Maps.immutableEntry` | `Minimal.immutableEntry` | |
| `collect.Lists.transform` | `Minimal.transform` | 非惰性 |
| `collect.Iterators.peekingIterator` | `Minimal.PeekingIterator` | 语义一致 |
| `collect.ComparisonChain` | `Minimal.ComparisonChain` | 短路比较 |
| `collect.MapMaker.weakKeys` | `WeakHashMap` | 语义等价 |
| `collect.ImmutableSortedSet.of` | `Minimal.immutableSortedSetOf` | |
| `cache.CacheBuilder` / `LoadingCache` | 同名字段 | 按**条数**淘汰 |
| `cache.maximumWeight` + `Weigher` | `Minimal.WeightedCache` | 按**权重**淘汰 |
| `io.Files.map` / `asCharSource` | `Minimal.map*` / `readFile` | |
| `io.Closer` | `Minimal.Closer` | 逆序关闭 |
| `util.concurrent.ThreadFactoryBuilder` | `Minimal.ThreadFactoryBuilder` | 守护线程 |
| `annotations.VisibleForTesting` | 直接删掉 | 仅作标记 |

### `util/Snappy.java`（重写）

`available()` 恒返回 `false`，其余方法抛 `UnsupportedOperationException`。
这样 `DbImpl` 在遇到要求 Snappy 的库时会**立刻报错**，
而不是读到一半才崩。

### `util/ZLib.java`（去掉 Android 依赖）

上游用 `Build.VERSION.SDK_INT` 判断能否使用
`Inflater.setInput(ByteBuffer)`（Android 15+ 才有）。
这要求编译时依赖 `android.jar`，桌面 JVM 上根本编不过。

改成**类初始化时反射探测**：有这个方法是零拷贝路径，没有就走 `byte[]` 版本。
同一份字节码现在在 JVM 和 Android 上都能跑。

### `fileenv/ByteBufferSupport.java`（去掉 MethodHandle）

上游用 `MethodHandle` 调 `Unsafe.invokeCleaner`，D8 对它的要求是：

```
D8: MethodHandle.invoke and MethodHandle.invokeExact are only supported
    starting with Android O (--min-api 26)
```

本项目 `minSdk = 23`，直接 dex 失败。改成纯反射（`Method.invoke`），没有这个下限。
拿不到 `Unsafe`（部分 ROM 会裁掉）就跳过 —— 映射交给 GC 回收，
只影响句柄释放的时机，不影响数据正确性。

### 其余 41 个文件

只是 import 替换 + 调用点改名（`Preconditions.checkX` → `Minimal.checkX`），
以及把 `TableCache` 里的一处 lambda `RemovalListener` 改成匿名内部类。

---

## 行为差异

| 场景 | 影响 |
|---|---|
| 并发读 | `LoadingCache` 用单锁，不是 Guava 的分片锁。LevelDB 的 `TableCache` 本来就被上层串行化，够用。 |
| 淘汰时机 | Guava 异步清理；这里同步。对 `TableCache` 反而更安全 —— 不会出现「条目已淘汰但文件句柄还开着」的窗口。 |
| 用 Snappy 写的库 | **读不了**。Minecraft 不会产生这种库。 |
| 写 Snappy 块 | `DbImpl` 直接抛错，不会静默降级。 |

---

## 构建

在仓库根目录（`编辑器/`，即本目录的上一级）执行：

```bash
python build_leveldb_java.py
```

脚本用 `javac` 编译，然后直接把两个 jar 打包到 `app/libs/`：

```
app/libs/leveldb-java-api.jar    14 个 class   ~8 KB
app/libs/leveldb-java.jar       177 个 class  ~255 KB
```

**不需要 Maven。** 根 `pom.xml` 继承 `io.airlift:airbase`，为了编译这个库
要联网拉一大棵插件树，不值得。脚本还会检查产物里有没有混进 `com/google` 的类。

### 环境要求

- JDK 17（或设置 `JAVA_HOME`）

---

## 上游用法

如果你不用这份裁剪版、只想要原版，上游发布在：

```xml
<dependency>
    <groupId>com.hivemc.leveldb</groupId>
    <artifactId>leveldb</artifactId>
    <version>1.0.0</version>
</dependency>
```

本项目是 [pcmind/leveldb](https://github.com/pcmind/leveldb) 的 drop-in 替代，
并加入了 [Mojang/leveldb-mcpe](https://github.com/Mojang/leveldb-mcpe)
里读取基岩版存档所需的改动。API 文档见上游仓库。

---

## 在樱喵里怎么用

库通过 `LevelDb` 接口接入（`app/src/main/java/yhjmew/nibnya/db/LevelDb.kt`），有两个后端：

| 后端 | 类 | 说明 |
|---|---|---|
| C++（JNI） | `LitlLevelDb` | 默认，快，需要该设备 ABI 的 `libleveldbjni.so` |
| Java（本库） | `JavaBackedLevelDb` | 慢，不依赖原生库，任何 ABI 都能跑 |

用户在 **设置 → 高级 → 数据库引擎** 里切换。Java 后端存在的意义是逃生通道：
设备加载不了 `.so`，或者某个存档原生版就是打不开时，还有路可走。

---

## 许可

Apache License 2.0 —— 见 [license.txt](./license.txt)。
版权归原作者所有；本目录只承载上述裁剪改动。
