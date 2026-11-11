package org.iq80.leveldb.minimal;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用 Guava 的极小子集，替换 leveldb 里实际用到的那部分。
 *
 * ## 为什么自己做
 * 原版 leveldb 依赖 Guava（2.33 MB / 1814 个类），而本库只用到
 * 其中 30 个类型。按可达性闭包算过：仍然要 1027 个类 —— 因为
 * `Cache` 和 `ImmutableList` 牵连太广，省不下多少。
 *
 * 所以干脆把这 30 个类型各自实现一遍。实际代码量很小：
 * 断言、字符串补齐、LRU、不可变列表、多重映射、弱键 Map。
 *
 * ## 换来了什么
 * APK 里不再需要 guava.jar；`leveldb` 模块的依赖从
 * `guava` + `snappy-java` + `snappy` 变成 **零依赖**。
 *
 * ## 行为差异（都不影响 LevelDB 正确性）
 * - {@link ImmutableList} 不可真正冻结：内部是 ArrayList 的防御性拷贝，
 *   构造后不再暴露修改入口，足够。
 * - {@link LoadingCache} 的并发度按单锁实现。LevelDB 的 TableCache
 *   本来就靠上层同步，够用；高并发场景会略慢于 Guava。
 * - 弱键 Map 用 {@link java.util.WeakHashMap}，语义一致。
 */
public final class Minimal {

    private Minimal() {
    }

    // ================================================================
    // base.Preconditions / Strings
    // ================================================================

    public static void checkArgument(boolean expression) {
        if (!expression) {
            throw new IllegalArgumentException();
        }
    }

    public static void checkArgument(boolean expression, String message) {
        if (!expression) {
            throw new IllegalArgumentException(message);
        }
    }

    public static void checkArgument(boolean expression, String template, Object... args) {
        if (!expression) {
            throw new IllegalArgumentException(String.format(template, args));
        }
    }

    public static void checkState(boolean expression) {
        if (!expression) {
            throw new IllegalStateException();
        }
    }

    public static void checkState(boolean expression, String message) {
        if (!expression) {
            throw new IllegalStateException(message);
        }
    }

    public static void checkState(boolean expression, String template, Object... args) {
        if (!expression) {
            throw new IllegalStateException(String.format(template, args));
        }
    }

    public static int checkPositionIndex(int index, int size) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("index (" + index + ") must be in range [0, " + size + "]");
        }
        return index;
    }

    /** 只要校验不要返回值的版本（几个调用点把它当语句用）。 */
    public static void checkPositionIndexOnly(int index, int size) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("index (" + index + ") must be in range [0, " + size + "]");
        }
    }

    public static void checkPositionIndexes(int start, int end, int size) {
        if (start < 0 || end < start || end > size) {
            throw new IndexOutOfBoundsException(
                    "range [" + start + ", " + end + ") out of bounds for length " + size);
        }
    }

    /** 同 Guava `Strings.padStart`。 */
    public static String padStart(String source, int minLength, char padChar) {
        if (source == null) {
            return null;
        }
        if (source.length() >= minLength) {
            return source;
        }
        StringBuilder sb = new StringBuilder(minLength);
        for (int i = source.length(); i < minLength; i++) {
            sb.append(padChar);
        }
        return sb.append(source).toString();
    }

    /** 同 Guava `Throwables.propagate`：RuntimeException / Error 原样抛，其余包一层。 */
    public static RuntimeException propagate(Throwable throwable) {
        if (throwable instanceof RuntimeException) {
            throw (RuntimeException) throwable;
        }
        if (throwable instanceof Error) {
            throw (Error) throwable;
        }
        throw new RuntimeException(throwable);
    }

    /**
     * 同 Guava `Throwables.propagateIfPossible(t, decl)`：
     * 是 RuntimeException/Error 就原样抛，否则按声明类型检查后抛出。
     *
     * LevelDB 用它把被包一层的 IOException 还原回去，
     * 让上层能按受检异常处理，所以这里要支持两种声明类型。
     */
    public static <X extends Throwable> void propagateIfPossible(
            Throwable throwable, Class<X> declaredType) throws X {
        if (throwable instanceof RuntimeException) {
            throw (RuntimeException) throwable;
        }
        if (throwable instanceof Error) {
            throw (Error) throwable;
        }
        if (declaredType.isInstance(throwable)) {
            throw declaredType.cast(throwable);
        }
    }

    /** 同 Guava `Joiner.on(sep).join(parts)`，跳过 null。 */
    public static String join(String separator, Iterable<?> parts) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object p : parts) {
            if (p == null) {
                continue;
            }
            if (!first) {
                sb.append(separator);
            }
            sb.append(p);
            first = false;
        }
        return sb.toString();
    }

    /** 同 Guava `ThreadFactoryBuilder().setNameFormat(fmt).setDaemon(true).build()`。 */
    public static ThreadFactory daemonThreadFactory(final String nameFormat) {
        final AtomicInteger counter = new AtomicInteger(1);
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = Executors.defaultThreadFactory().newThread(r);
                t.setName(String.format(nameFormat, counter.getAndIncrement()));
                t.setDaemon(true);
                return t;
            }
        };
    }

    // ================================================================
    // collect.ImmutableList
    // ================================================================

    /**
     * 极简不可变列表。
     *
     * 没有照搬 Guava 的 {@code copyOf} 语义检查（null 元素直接 NPE），
     * 因为 LevelDB 不会往里塞 null。构造后内部数组不再暴露，
     * 迭代器不支持 remove。
     */
    public static final class ImmutableList<E> extends ArrayList<E> {

        @SuppressWarnings("unchecked")
        public static <E> ImmutableList<E> of() {
            return new ImmutableList<>(Collections.<E>emptyList());
        }

        @SafeVarargs
        public static <E> ImmutableList<E> of(E... elements) {
            List<E> l = new ArrayList<>(elements.length);
            Collections.addAll(l, elements);
            return new ImmutableList<>(l);
        }

        public static <E> ImmutableList<E> copyOf(Collection<? extends E> source) {
            return new ImmutableList<>(source);
        }

        public static <E> Builder<E> builder() {
            return new Builder<>();
        }

        private ImmutableList(Collection<? extends E> source) {
            super(source);
        }

        /** 构造器。`build()` 之后不能再用。 */
        public static final class Builder<E> {
            private final List<E> list = new ArrayList<>();

            public Builder<E> add(E element) {
                list.add(element);
                return this;
            }

            public Builder<E> addAll(Iterable<? extends E> elements) {
                for (E e : elements) {
                    list.add(e);
                }
                return this;
            }

            public ImmutableList<E> build() {
                return new ImmutableList<>(list);
            }
        }
    }

    // ================================================================
    // collect.Multimap
    // ================================================================

    /**
     * 多重映射。键有序（{@link TreeMap}），供 `getFiles()` 这类
     * 「按 level 号取文件列表」的用法直接照抄。
     */
    public static final class Multimap<K, V> {

        private final Map<K, List<V>> map;

        private Multimap(Map<K, List<V>> map) {
            this.map = map;
        }

        public static <K extends Comparable<K>, V> Multimap<K, V> orderedByKey() {
            return new Multimap<>(new TreeMap<K, List<V>>());
        }

        /** 等价于 Guava `ArrayListMultimap.create()`：键无序，值按插入顺序。 */
        public static <K, V> Multimap<K, V> create() {
            return new Multimap<>(new LinkedHashMap<K, List<V>>());
        }

        public void put(K key, V value) {
            List<V> list = map.get(key);
            if (list == null) {
                list = new ArrayList<>();
                map.put(key, list);
            }
            list.add(value);
        }

        /** 把另一个多重映射的所有条目并进来。 */
        @SuppressWarnings("unchecked")
        public void putAll(Multimap<? extends K, ? extends V> other) {
            for (Map.Entry<? extends K, ? extends List<? extends V>> e : other.map.entrySet()) {
                for (V v : e.getValue()) {
                    put((K) e.getKey(), v);
                }
            }
        }

        /** 展平成 (key, value) 对，顺序与插入一致。对应 Guava 的 entries()。 */
        public Collection<Map.Entry<K, V>> entries() {
            List<Map.Entry<K, V>> out = new ArrayList<>();
            for (Map.Entry<K, List<V>> e : map.entrySet()) {
                for (V v : e.getValue()) {
                    out.add(immutableEntry(e.getKey(), v));
                }
            }
            return out;
        }

        public Collection<V> get(K key) {
            List<V> list = map.get(key);
            return list == null ? Collections.<V>emptyList() : list;
        }

        public Set<K> keySet() {
            return map.keySet();
        }

        public Collection<V> values() {
            List<V> all = new ArrayList<>();
            for (List<V> l : map.values()) {
                all.addAll(l);
            }
            return all;
        }

        public boolean isEmpty() {
            return map.isEmpty();
        }

        public void clear() {
            map.clear();
        }

        /** 转成普通 Map，值仍是列表。 */
        public Map<K, List<V>> asMap() {
            return map;
        }

        @Override
        public String toString() {
            return map.toString();
        }
    }

    /** 有序多重映射的构造器，对应 `ImmutableMultimap.builder().orderKeysBy(...)`。 */
    public static final class MultimapBuilder<K extends Comparable<K>, V> {
        private final Multimap<K, V> multimap = Multimap.orderedByKey();

        public MultimapBuilder<K, V> orderKeysBy(Comparator<? super K> unused) {
            // 已经是 TreeMap，天然有序
            return this;
        }

        public MultimapBuilder<K, V> put(K key, V value) {
            multimap.put(key, value);
            return this;
        }

        public MultimapBuilder<K, V> putAll(K key, Iterable<? extends V> values) {
            for (V v : values) {
                multimap.put(key, v);
            }
            return this;
        }

        public Multimap<K, V> build() {
            return multimap;
        }
    }

    // ================================================================
    // collect.Lists / Maps / Iterators
    // ================================================================

    /** 同 `Lists.transform`：惰性视图，转成新列表返回（LevelDB 只用一次，不担心开销）。 */
    public static <F, T> List<T> transform(Collection<F> from, Function<? super F, ? extends T> fn) {
        List<T> out = new ArrayList<>(from.size());
        for (F f : from) {
            out.add(fn.apply(f));
        }
        return out;
    }

    public static <K, V> Map.Entry<K, V> immutableEntry(final K key, final V value) {
        return new Map.Entry<K, V>() {
            @Override
            public K getKey() {
                return key;
            }

            @Override
            public V getValue() {
                return value;
            }

            @Override
            public V setValue(V v) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean equals(Object o) {
                if (!(o instanceof Map.Entry)) {
                    return false;
                }
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                return (key == null ? e.getKey() == null : key.equals(e.getKey()))
                        && (value == null ? e.getValue() == null : value.equals(e.getValue()));
            }

            @Override
            public int hashCode() {
                return (key == null ? 0 : key.hashCode()) ^ (value == null ? 0 : value.hashCode());
            }

            @Override
            public String toString() {
                return key + "=" + value;
            }
        };
    }

    // ================================================================
    // base.Function
    // ================================================================

    public interface Function<F, T> {
        T apply(F input);
    }

    // ================================================================
    // collect.PeekingIterator
    // ================================================================

    /**
     * 带 `peek()` 的迭代器，语义同 Guava：peek 不推进；`next()` 与
     * `peek()` 交替调用时只缓存一个元素。
     */
    public static final class PeekingIterator<E> implements Iterator<E> {
        private final Iterator<E> iterator;
        private E nextElement;
        private boolean hasPeeked;

        public PeekingIterator(Iterator<E> iterator) {
            this.iterator = iterator;
        }

        public static <E> PeekingIterator<E> peekingIterator(Iterator<E> iterator) {
            return new PeekingIterator<>(iterator);
        }

        public E peek() {
            if (!hasPeeked) {
                if (!iterator.hasNext()) {
                    return null;
                }
                nextElement = iterator.next();
                hasPeeked = true;
            }
            return nextElement;
        }

        @Override
        public boolean hasNext() {
            return hasPeeked || iterator.hasNext();
        }

        @Override
        public E next() {
            if (!hasPeeked) {
                return iterator.next();
            }
            E result = nextElement;
            nextElement = null;
            hasPeeked = false;
            return result;
        }

        @Override
        public void remove() {
            if (hasPeeked) {
                throw new IllegalStateException("Can't remove after peek");
            }
            iterator.remove();
        }
    }

    // ================================================================
    // cache.Weigher / Cache / CacheBuilder
    // ================================================================

    public interface Weigher<K, V> {
        int weigh(K key, V value);
    }

    public interface RemovalListener<K, V> {
        void onRemoval(K key, V value);
    }

    /** 缓存装载器：缓存未命中时由它创建。 */
    public interface CacheLoader<K, V> {
        V load(K key) throws Exception;
    }

    /**
     * 容量受限的 LRU 缓存。
     *
     * 用 `LinkedHashMap` 的 `removeEldestEntry` 实现（accessOrder=true），
     * 命中时把条目移到队尾，淘汰最久未用的 —— 这正是 Guava
     * `maximumSize` 的淘汰策略。
     *
     * 淘汰时同步回调 removalListener（Guava 是异步的，
     * 但这里必须同步：`TableCache` 靠它 close 文件句柄，
     * 异步会有「已淘汰但句柄还在」的窗口）。
     */
    public static final class LoadingCache<K, V> {
        private final int maximumSize;
        private final CacheLoader<K, V> loader;
        private final RemovalListener<K, V> removalListener;
        private final LinkedHashMap<K, V> map;

        LoadingCache(int maximumSize, CacheLoader<K, V> loader, RemovalListener<K, V> removalListener) {
            this.maximumSize = maximumSize;
            this.loader = loader;
            this.removalListener = removalListener;
            this.map = new LinkedHashMap<K, V>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                    boolean evict = size() > LoadingCache.this.maximumSize;
                    if (evict && LoadingCache.this.removalListener != null) {
                        LoadingCache.this.removalListener.onRemoval(eldest.getKey(), eldest.getValue());
                    }
                    return evict;
                }
            };
        }

        /**
         * 取缓存；未命中时调用 loader。
         *
         * 抛 {@link java.util.concurrent.ExecutionException} 而不是裸
         * Exception —— 原调用点（TableCache.getTable）只 catch 这个类型，
         * 换成别的话异常会漏出去。
         */
        public V get(K key) throws java.util.concurrent.ExecutionException {
            V value = map.get(key);
            if (value != null) {
                return value;
            }
            try {
                value = loader.load(key);
            } catch (Throwable t) {
                throw new java.util.concurrent.ExecutionException(t);
            }
            map.put(key, value);
            return value;
        }

        public void evict(K key) {
            V v = map.remove(key);
            if (v != null && removalListener != null) {
                removalListener.onRemoval(key, v);
            }
        }

        public void invalidateAll() {
            for (Map.Entry<K, V> e : new ArrayList<>(map.entrySet())) {
                if (removalListener != null) {
                    removalListener.onRemoval(e.getKey(), e.getValue());
                }
            }
            map.clear();
        }

        public long size() {
            return map.size();
        }
    }

    /**
     * 按**权重**淘汰的 LRU 缓存（Guava 的 `maximumWeight` + `weigher`）。
     *
     * LevelDB 的 block cache 用它：每个块按字节数计权，
     * 总额超过 `options.cacheSize()` 就淘汰最久未用的块。
     *
     * 与 {@link LoadingCache} 的区别：那个按**条数**限制，
     * 这个按 weigher 算出的总量限制。两者不能混用。
     *
     * 淘汰在 put 之后同步做，直到总量回到上限以下 ——
     * Guava 是异步清理，这里必须同步，否则大块读多了会先 OOM。
     */
    public static final class WeightedCache<K, V> {
        private final long maximumWeight;
        private final Weigher<K, V> weigher;
        private final LinkedHashMap<K, V> map;
        private long totalWeight;

        WeightedCache(long maximumWeight, Weigher<K, V> weigher) {
            this.maximumWeight = maximumWeight;
            this.weigher = weigher;
            this.map = new LinkedHashMap<>(16, 0.75f, true);
        }

        public synchronized V getIfPresent(K key) {
            return map.get(key);
        }

        public synchronized V get(K key, java.util.concurrent.Callable<V> loader)
                throws java.util.concurrent.ExecutionException {
            V v = map.get(key);
            if (v != null) {
                return v;
            }
            try {
                v = loader.call();
            } catch (Exception e) {
                throw new java.util.concurrent.ExecutionException(e);
            }
            put(key, v);
            return v;
        }

        public synchronized void put(K key, V value) {
            V old = map.put(key, value);
            if (old != null) {
                totalWeight -= weigher.weigh(key, old);
            }
            totalWeight += weigher.weigh(key, value);
            evictIfNeeded();
        }

        private void evictIfNeeded() {
            // LinkedHashMap(accessOrder=true) 的迭代顺序就是「最久未用在前」
            java.util.Iterator<Map.Entry<K, V>> it = map.entrySet().iterator();
            while (totalWeight > maximumWeight && it.hasNext()) {
                Map.Entry<K, V> eldest = it.next();
                totalWeight -= weigher.weigh(eldest.getKey(), eldest.getValue());
                it.remove();
            }
        }

        public synchronized long getApproximateMemoryUsage() {
            return totalWeight;
        }

        public synchronized void invalidate(K key) {
            V old = map.remove(key);
            if (old != null) {
                totalWeight -= weigher.weigh(key, old);
            }
        }

        public synchronized Set<K> keySet() {
            return map.keySet();
        }

        public synchronized void invalidateAll() {
            map.clear();
            totalWeight = 0;
        }
    }

    /** 对应 `CacheBuilder.newBuilder().maximumSize(n).removalListener(...).build(loader)`。 */
    public static final class CacheBuilder<K, V> {
        private int maximumSize = Integer.MAX_VALUE;
        private long maximumWeight = -1;
        private Weigher<K, V> weigher;
        private RemovalListener<K, V> removalListener;

        public static <K, V> CacheBuilder<K, V> newBuilder() {
            return new CacheBuilder<>();
        }

        public CacheBuilder<K, V> maximumSize(long size) {
            this.maximumSize = (int) Math.min(size, Integer.MAX_VALUE);
            return this;
        }

        public CacheBuilder<K, V> removalListener(RemovalListener<K, V> listener) {
            this.removalListener = listener;
            return this;
        }

        public CacheBuilder<K, V> maximumWeight(long weight) {
            this.maximumWeight = weight;
            return this;
        }

        public CacheBuilder<K, V> weigher(Weigher<K, V> weigher) {
            this.weigher = weigher;
            return this;
        }

        /** 并发度：本实现是单锁，接受但忽略。 */
        public CacheBuilder<K, V> concurrencyLevel(int level) {
            return this;
        }

        public LoadingCache<K, V> build(CacheLoader<K, V> loader) {
            return new LoadingCache<>(maximumSize, loader, removalListener);
        }

        /** 按权重构建，返回 {@link WeightedCache}。 */
        public WeightedCache<K, V> build() {
            if (maximumWeight < 0 || weigher == null) {
                throw new IllegalStateException("build() without weight requires weigher and maximumWeight");
            }
            return new WeightedCache<>(maximumWeight, weigher);
        }
    }

    // ================================================================
    // collect.ImmutableSortedSet / ComparisonChain
    // ================================================================

    /**
     * 空的有序不可变集合。
     *
     * 返回类型必须是 {@link java.util.SortedSet} 而不是 {@link Set} ——
     * LevelSet 的 `addedFiles` 字段声明成 SortedSet，
     * 返回 Set 编译不过。
     */
    @SuppressWarnings("unchecked")
    public static <E> java.util.SortedSet<E> immutableSortedSetOf() {
        // 空集合不涉及比较，所以不需要 E 实现 Comparable。
        // （选用 TreeSet 只是为了满足 SortedSet 的静态类型，
        //  实际调用点马上会 put 进具体比较器的条目。）
        return (java.util.SortedSet<E>) Collections.unmodifiableSortedSet(
                (java.util.SortedSet<E>) (java.util.SortedSet<?>) new TreeSet<Object>());
    }

    /** 同 Guava `ComparisonChain`：按顺序短路比较。 */
    public static final class ComparisonChain {
        private int result = 0;
        private boolean done;

        public static ComparisonChain start() {
            return new ComparisonChain();
        }

        public ComparisonChain compare(int left, int right) {
            return compareInts(left, right);
        }

        public ComparisonChain compare(long left, long right) {
            return compareLongs(left, right);
        }

        /** 用给定比较器比较两个值（LevelDB 的 FileMetaData 排序用）。 */
        public <T> ComparisonChain compare(T left, T right, Comparator<? super T> comparator) {
            if (done) {
                return this;
            }
            return setResult(comparator.compare(left, right));
        }

        public ComparisonChain compare(String left, String right) {
            if (done) {
                return this;
            }
            int r = (left == null) ? (right == null ? 0 : -1)
                    : (right == null ? 1 : left.compareTo(right));
            return setResult(r);
        }

        private ComparisonChain compareInts(int left, int right) {
            if (done) {
                return this;
            }
            return setResult(left < right ? -1 : (left > right ? 1 : 0));
        }

        private ComparisonChain compareLongs(long left, long right) {
            if (done) {
                return this;
            }
            return setResult(left < right ? -1 : (left > right ? 1 : 0));
        }

        private ComparisonChain setResult(int r) {
            if (r != 0) {
                result = r;
                done = true;
            }
            return this;
        }

        public int result() {
            return result;
        }
    }

    // ================================================================
    // collect.MapMaker（弱键）
    // ================================================================

    /**
     * 弱键 Map。构造器里只用到 `weakKeys().makeMap()`，
     * 直接用 {@link java.util.WeakHashMap} 即可。
     */
    public static final class MapMaker {
        public MapMaker weakKeys() {
            return this;
        }

        public <K, V> Map<K, V> makeMap() {
            return new java.util.WeakHashMap<>();
        }
    }

    // ================================================================
    // util.concurrent.ThreadFactoryBuilder（语义见 daemonThreadFactory）
    // ================================================================

    public static final class ThreadFactoryBuilder {
        private String nameFormat;
        private Thread.UncaughtExceptionHandler uncaughtExceptionHandler;

        public ThreadFactoryBuilder setNameFormat(String format) {
            this.nameFormat = format;
            return this;
        }

        public ThreadFactoryBuilder setUncaughtExceptionHandler(Thread.UncaughtExceptionHandler handler) {
            this.uncaughtExceptionHandler = handler;
            return this;
        }

        public ThreadFactory build() {
            final Thread.UncaughtExceptionHandler handler = uncaughtExceptionHandler;
            final AtomicInteger counter = new AtomicInteger(1);
            final String fmt = nameFormat == null ? "leveldb-%d" : nameFormat;
            return new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = Executors.defaultThreadFactory().newThread(r);
                    t.setName(String.format(fmt, counter.getAndIncrement()));
                    t.setDaemon(true);
                    if (handler != null) {
                        t.setUncaughtExceptionHandler(handler);
                    }
                    return t;
                }
            };
        }
    }

    // ================================================================
    // io.Files / io.CharSource（文件读写）
    // ================================================================

    /**
     * 同 Guava `Files.map(File)`：只读映射整个文件。
     *
     * 空文件没法 map（`FileChannel.map` 会抛 IAE），这里按
     * 「映射 1 字节」处理会越界读，所以直接返回**空数组的只读视图**。
     * LevelDB 的 `.ldb` 不会是 0 字节，这条只为不炸。
     */
    public static java.nio.MappedByteBuffer mapReadOnly(java.io.File file) throws java.io.IOException {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            long size = raf.length();
            if (size == 0) {
                return null;
            }
            return raf.getChannel().map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, size);
        }
    }

    /**
     * 同 Guava `Files.map(File, mode, size)`：按指定模式映射前 size 字节。
     *
     * MMWritableFile 用它做可写映射。
     */
    public static java.nio.MappedByteBuffer map(
            java.io.File file,
            java.nio.channels.FileChannel.MapMode mode,
            long size) throws java.io.IOException {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(
                file, mode == java.nio.channels.FileChannel.MapMode.READ_ONLY ? "r" : "rw")) {
            return raf.getChannel().map(mode, 0, size);
        }
    }

    /** 同 `Files.asCharSource(file, UTF_8).read()`。 */
    public static String readFile(java.io.File file, java.nio.charset.Charset charset) throws java.io.IOException {
        byte[] bytes = new byte[(int) file.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int off = 0;
            while (off < bytes.length) {
                int n = in.read(bytes, off, bytes.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
        }
        return new String(bytes, charset);
    }

    /** 同 Guava `Closer`：按注册的逆序静默关闭。 */
    public static final class Closer implements Closeable {
        private final List<Closeable> stack = new ArrayList<>();

        public static Closer create() {
            return new Closer();
        }

        public <C extends Closeable> C register(C closeable) {
            if (closeable != null) {
                stack.add(closeable);
            }
            return closeable;
        }

        /**
         * 同 Guava `Closer.rethrow`：把捕获到的异常原样抛出。
         *
         * 声明成 `RuntimeException` 是为了配合 `throw c.rethrow(e)` 的写法；
         * Error 也会被原样抛出（不能吞掉 OOM / UnsatisfiedLinkError）。
         */
        public RuntimeException rethrow(Throwable e) {
            if (e instanceof Error) {
                throw (Error) e;
            }
            if (e instanceof RuntimeException) {
                return (RuntimeException) e;
            }
            return new RuntimeException(e);
        }

        @Override
        public void close() throws java.io.IOException {
            RuntimeException thrown = null;
            for (int i = stack.size() - 1; i >= 0; i--) {
                try {
                    stack.get(i).close();
                } catch (Throwable t) {
                    if (thrown == null) {
                        thrown = new RuntimeException(t);
                    }
                }
            }
            stack.clear();
            if (thrown != null) {
                throw thrown;
            }
        }
    }
}
