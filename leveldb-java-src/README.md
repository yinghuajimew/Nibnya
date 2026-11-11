# LevelDB (MCPE) — trimmed for Nibnya

**English** · [简体中文](./README.zh-CN.md)

A trimmed build of [HiveGamesOSS/leveldb-mcpe-java](https://github.com/HiveGamesOSS/leveldb-mcpe-java) `1.1.0` —
the pure-Java LevelDB fork that can read Minecraft Bedrock (MCPE) worlds.

This copy exists for one reason: **the upstream jar needs Guava, and we don't want to ship 2.3 MB of it.**

---

## Why this fork exists

Upstream's `leveldb` module declares:

```xml
<dependency>org.xerial.snappy:snappy-java</dependency>  <!-- optional -->
<dependency>org.iq80.snappy:snappy</dependency>          <!-- optional -->
<dependency>com.google.guava:guava</dependency>          <!-- required -->
```

`guava-20.0.jar` is **2.33 MB across 1814 classes**. We use **30 types** from it.

Packing only the reachable closure was measured at 1027 classes (57%) — `cache.Cache`
and `collect.ImmutableList` drag in too much. So instead of cherry-picking classes,
each of the 30 types was reimplemented in a single file, `minimal/Minimal.java`.

Snappy is removed outright: Minecraft stores chunks as raw deflate
(`CompressionType.ZLIB_RAW`) and never emits Snappy blocks.

### Result

| | Upstream | This build |
|---|---|---|
| `leveldb` jar | 266 KB | **255 KB** |
| `leveldb-api` jar | 9 KB | **8 KB** |
| `guava.jar` | 2330 KB | **0** |
| snappy (optional) | ~1000 KB | **0** |
| **Total to ship** | **2605 KB** | **263 KB** |

---

## Verification

Correctness was checked by hashing **every key and value** in a real 127k-entry
world database, before and after trimming. Comparing key counts alone is not
enough — that misses "right key, wrong value".

```
Upstream (Guava) : keys=125559  bytes=211780071
                   digest=62e289b6a013838bf0956d06da6ff94e9c83f933420efdb5e2d64c2acdf4b49b
This build       : keys=125559  bytes=211780071
                   digest=62e289b6a013838bf0956d06da6ff94e9c83f933420efdb5e2d64c2acdf4b49b
```

Identical. The walker feeds `key + 0x00 + value + 0x00` per entry into SHA-256.

---

## What changed

Only these files differ from upstream. Everything else is untouched.

### `minimal/Minimal.java` (new, ~700 lines)

Drop-in equivalents for the Guava API surface actually used:

| Guava | Replacement | Notes |
|---|---|---|
| `base.Preconditions` | static methods | assertions, same behaviour |
| `base.Strings.padStart` | `Minimal.padStart` | zero-padding filenames |
| `base.Throwables.propagate*` | `Minimal.propagate*` | rethrow helpers |
| `base.Joiner` | `Minimal.join` | skips nulls |
| `base.Function` | `Minimal.Function` | same signature |
| `collect.ImmutableList` | `Minimal.ImmutableList` | ArrayList, no mutators exposed |
| `collect.Multimap` | `Minimal.Multimap` | ordered and unordered variants |
| `collect.Maps.immutableEntry` | `Minimal.immutableEntry` | |
| `collect.Lists.transform` | `Minimal.transform` | eager |
| `collect.Iterators.peekingIterator` | `Minimal.PeekingIterator` | same semantics |
| `collect.ComparisonChain` | `Minimal.ComparisonChain` | short-circuits |
| `collect.MapMaker.weakKeys` | `WeakHashMap` | equivalent |
| `collect.ImmutableSortedSet.of` | `Minimal.immutableSortedSetOf` | |
| `cache.CacheBuilder` / `LoadingCache` | same names | evicts by **entry count** |
| `cache.maximumWeight` + `Weigher` | `Minimal.WeightedCache` | evicts by **weight** |
| `io.Files.map` / `asCharSource` | `Minimal.map*` / `readFile` | |
| `io.Closer` | `Minimal.Closer` | closes in reverse order |
| `util.concurrent.ThreadFactoryBuilder` | `Minimal.ThreadFactoryBuilder` | daemon threads |
| `annotations.VisibleForTesting` | removed | marker only |

### `util/Snappy.java` (rewritten)

`available()` always returns `false`; the rest throws `UnsupportedOperationException`.
`DbImpl` therefore fails fast if a database ever asks for Snappy, instead of
dying halfway through a read.

### `util/ZLib.java` (Android dependency removed)

Upstream branches on `Build.VERSION.SDK_INT` to decide whether
`Inflater.setInput(ByteBuffer)` (Android 15+) is usable. That requires compiling
against `android.jar`, which rules out building on a desktop JVM.

Replaced with a **reflective capability probe** at class-init: if the method
exists it takes the zero-copy path, otherwise the `byte[]` path. One set of
bytecode now works on both JVM and Android.

### `fileenv/ByteBufferSupport.java` (MethodHandle removed)

Upstream invokes `Unsafe.invokeCleaner` through `MethodHandle`. D8 rejects that
below API 26:

```
D8: MethodHandle.invoke and MethodHandle.invokeExact are only supported
    starting with Android O (--min-api 26)
```

Our `minSdk` is 23. Rewritten using plain reflection (`Method.invoke`), which has
no such floor. If `Unsafe` cannot be obtained (some ROMs strip it), the call is
skipped — the mapping is then released by the GC instead, which only delays
handle release and does not affect correctness.

### 41 other files

Import rewrites and call-site renames (`Preconditions.checkX` → `Minimal.checkX`),
plus converting a lambda `RemovalListener` in `TableCache` to an anonymous class.

---

## Behaviour differences

| Area | Impact |
|---|---|
| Concurrent reads | `LoadingCache` uses a single lock instead of Guava's sharded locks. LevelDB's `TableCache` is already serialized upstream, so this is fine in practice. |
| Eviction timing | Guava evicts asynchronously; this build evicts synchronously. For `TableCache` that is strictly safer — no window where an entry is gone but its file handle is still open. |
| Databases written with Snappy | **Cannot be read.** Minecraft never produces these. |
| Writing Snappy blocks | `DbImpl` throws instead of silently degrading. |

---

## Building

From the repository root (`编辑器/`, one level above this folder):

```bash
python build_leveldb_java.py
```

That compiles with `javac` and packs two jars straight into `app/libs/`:

```
app/libs/leveldb-java-api.jar    14 classes   ~8 KB
app/libs/leveldb-java.jar       177 classes  ~255 KB
```

**No Maven required.** The root `pom.xml` inherits `io.airlift:airbase`, which
pulls a large plugin tree over the network for no benefit here. The script also
fails the build if any `com/google` class sneaks into the output.

### Requirements

- JDK 17 (or set `JAVA_HOME`)

---

## Upstream usage

If you are not using this trimmed copy and just want the stock library, upstream
publishes it as:

```xml
<dependency>
    <groupId>com.hivemc.leveldb</groupId>
    <artifactId>leveldb</artifactId>
    <version>1.0.0</version>
</dependency>
```

This project is a drop-in replacement for [pcmind/leveldb](https://github.com/pcmind/leveldb),
adding the changes from [Mojang/leveldb-mcpe](https://github.com/Mojang/leveldb-mcpe)
needed to read Bedrock worlds. See the upstream repositories for API documentation.

---

## Using it from Nibnya

The library is wired through `LevelDb` (`app/src/main/java/yhjmew/nibnya/db/LevelDb.kt`),
which has two backends:

| Backend | Class | Notes |
|---|---|---|
| C++ (JNI) | `LitlLevelDb` | default, fast, needs `libleveldbjni.so` for the device ABI |
| Java (this) | `JavaBackedLevelDb` | slow, no native dependency, works on any ABI |

Users switch between them in **Settings → Advanced → Database engine**. The Java
backend exists as an escape hatch for devices where the `.so` will not load, or
for worlds the native build refuses to open.

---

## License

Apache License 2.0 — see [license.txt](./license.txt).
Copyright belongs to the original authors; this folder only carries the trimming
changes described above.
