package yhjmew.nibnya.map

/**
 * 区块地表缓存：把一个区块压缩成「256 列的颜色 + 高度」。
 *
 * ## 为什么必须有这一层
 * 一块 128×128 的瓦片在 `blocksPerCell = 4` 时覆盖 512×512 方块 = 32×32 区块。
 * 如果每画一个采样点就去 [ChunkReader.readChunk]，那是 1024 次区块解析、
 * 每次还要扫 24 个子区块键 —— 一块瓦片要几十万次 LevelDB 读取，根本不可用。
 *
 * 所以按区块缓存：**每个区块只解析一次**，得到 256 列的地表信息，
 * 之后所有采样点都从这份扁平数组里取。
 *
 * ## 为什么用 IntArray 而不是 PaletteEntry
 * [ChunkReader] 返回的 [ParsedChunk] 里有大量 `PaletteEntry` / `BlockLayer`
 * 对象，一个区块能占几百 KB。1000 个区块就是几百 MB，必然 OOM。
 * 这里预先算好颜色（已经过群系染色）和高度，**一个区块固定 1.5 KB**。
 *
 * ## 高度用来做什么
 * 不是给 UI 显示的，是给瓦片做**浮雕明暗**用的 ——
 * 参照 SeedCraft `SaveTiles.render` 的 0.06/0.04 系数：
 * 相邻列高度差越大，颜色越亮或越暗，地形就有了起伏感。
 *
 * ## 线程
 * [SaveTiles] 用 2 个线程并发生成瓦片，**两边会同时查同一个缓存**。
 * 所以这里内部必须加锁 —— `LinkedHashMap` 本身不是线程安全的，
 * 并发 `get` 触发 accessOrder 重排时会把链表写坏，
 * 表现为 `NullPointerException` 或 CPU 跑满的死循环（JDK 著名的
 * HashMap 并发扩容问题）。锁粒度就是整个 [get]，因为解析一个区块
 * 本来就要几百微秒，再细分没有收益。
 */
class ChunkSurfaceCache(
    private val reader: ChunkReader,
    private val colors: BlockColorTable,
    /** 最多缓存多少个区块。1.5 KB/个 → 4096 个约 6 MB。 */
    private val maxEntries: Int = 4096,
) {

    companion object {
        /** 一个区块 16×16 = 256 列。 */
        const val COLUMNS = 256

        /** 记录不存在时的颜色（透明，让下层或底色透出来）。 */
        const val NO_COLOR = 0

        private const val TAG = "ChunkSurface"

        /** 位打包用的偏移 / 掩码，见 [packKey]。 */
        private const val OFFSET = 1L shl 21
        private const val MASK22 = (1L shl 22) - 1
        private const val MASK20 = (1L shl 20) - 1
    }

    /**
     * 一个区块的地表。
     *
     * 索引约定与 [ChunkReader] 的 `entryAt(x, y, z)` 一致：
     * `idx = z * 16 + x`（即 `[]` 里存的是 `[z][x]`）。
     */
    class Surface(
        /** ARGB；[NO_COLOR] 表示这一列没有任何可显示的方块。 */
        val argb: IntArray,
        /** 地表方块的世界 Y 坐标；[NO_COLOR] 的列填 [NO_HEIGHT]。 */
        val height: ShortArray,
    )

    /** 高度未知的哨兵值。用 Short.MIN_VALUE，不和任何合法 Y 冲突。 */
    private val NO_HEIGHT = Short.MIN_VALUE

    /**
     * 简单的 LRU。
     *
     * 用 `LinkedHashMap(accessOrder = true)`：每次 [get] 命中会把它移到队尾，
     * 淘汰时从队头拿 —— 这是 JDK 自带的最省事写法。
     */
    private val lru = object : LinkedHashMap<Long, Surface>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Surface>?): Boolean =
            size > maxEntries
    }

    /** 统计：命中/未命中，只用来打日志判断缓存够不够。 */
    private var hits = 0
    private var misses = 0

    /**
     * 打包成 64 位键。
     *
     * ⚠️ 不能用 `cx shl 40 xor cz`：区块坐标可能到 ±3000 万，
     * 两者异或之后会互相覆盖（A 的 x 进位到 z 的位里），
     * 表现为「某个区块画出来的颜色和实际不符」——极难查。
     * 这里用 **质数混合**，冲突率与 HashMap 的整数散列同级。
     */
    private fun keyOf(cx: Int, cz: Int, dim: Int): Long {
        var k = cx * 73856093L
        k = k xor (cz * 19349663L)
        k = k xor (dim * 83492791L)
        return k
    }

    /**
     * 取一个区块的地表；没有缓存时解析。
     *
     * 区块不存在（或整列全空）时返回一张全 [NO_COLOR] 的表，
     * 不返回 null —— 调用方每列都要判一次空，多一层判空没必要。
     */
    /**
     * 锁只保护 [lru] 和计数器，**不保护 [reader]**。
     *
     * 解析放在锁外：两个线程解析不同区块时可以并行，
     * 只有真正读写缓存时才互斥。
     * 代价是极端情况下同一区块会被解析两次（后写入的覆盖前者），
     * 结果一样，只是多花一点时间 —— 比「整段锁住导致并发失效」划算。
     */
    private val lock = Any()

    /** 长期记录「哪些区块见过地表」，不受 LRU 淘汰影响。见 [isPopulated]。 */
    private val populated = HashSet<Long>()

    fun get(cx: Int, cz: Int, dim: Int): Surface {
        val k = keyOf(cx, cz, dim)
        synchronized(lock) {
            lru[k]?.let {
                hits++
                return it
            }
            misses++
        }

        val parsed = try {
            reader.readChunk(cx, cz, dim)
        } catch (_: Exception) {
            null
        }
        // 解析失败也缓存成空表，避免每次滑过去都重试同一批坏区块。
        // 返回空表而不是 null：调用方只需要「有没有颜色」，不必多一层判空。
        val surface = parsed?.let { build(it) } ?: EMPTY
        synchronized(lock) {
            lru[k] = surface
            // 顺手登记「这里有地表」，供描边使用（见 isPopulated）
            if (surface !== EMPTY) populated.add(packKey(cx, cz, dim))
        }
        return surface
    }

    /** 表示「这个区块确实没数据」，和 null（没查过）区分开。 */
    private val EMPTY = Surface(IntArray(COLUMNS), ShortArray(COLUMNS) { NO_HEIGHT })

    private fun build(chunk: ParsedChunk): Surface? {
        if (!chunk.hasTerrain) return null
        val range = chunk.yIndexRange ?: return null

        val argb = IntArray(COLUMNS)
        val height = ShortArray(COLUMNS) { NO_HEIGHT }
        var anyColumn = false

        for (x in 0 until 16) {
            for (z in 0 until 16) {
                val biome = chunk.biomes?.at(x, z) ?: -1
                val hit = topBlockOf(chunk, range, x, z) ?: continue
                val idx = z * 16 + x
                argb[idx] = colors.colorOf(hit.entry.name, hit.entry.tags, biome)
                height[idx] = hit.y.toShort()
                anyColumn = true
            }
        }

        if (!anyColumn) {
            // 有子区块但整列全空：洞穴口很常见，静默返回空
            return null
        }
        return Surface(argb, height)
    }

    /** 地表方块 + 它的世界高度。 */
    private class Hit(val entry: PaletteEntry, val y: Int)

    /**
     * 从最高子区块往下找第一个非空气方块 —— 逻辑与 [TerrainMapBuilder] 一致。
     *
     * 每层先看主体层，是空气才回看覆盖层（水/植物），
     * 这样海洋显示水面而不是海底沙石。
     */
    private fun topBlockOf(
        chunk: ParsedChunk,
        range: IntRange,
        x: Int,
        z: Int,
    ): Hit? {
        for (yIndex in range.reversed()) {
            val sub = chunk.subChunks[yIndex] ?: continue
            for (y in 15 downTo 0) {
                val primary = sub.layer.entryAt(x, y, z)
                if (primary != null && !isTransparent(primary.name)) {
                    return Hit(primary, yIndex * 16 + y)
                }
                val overlay = sub.overlay?.entryAt(x, y, z)
                if (overlay != null && !isTransparent(overlay.name)) {
                    return Hit(overlay, yIndex * 16 + y)
                }
            }
        }
        return null
    }

    private fun isTransparent(name: String): Boolean = when (name) {
        "minecraft:air",
        "minecraft:unknown",
        "minecraft:structure_void",
        "minecraft:light_block",
        "minecraft:barrier" -> true
        else -> false
    }

    /** 缓存统计，只在切世界时打一行，看 LRU 是否够用。 */
    fun statsString(): String = synchronized(lock) {
        "hits=$hits misses=$misses size=${lru.size}"
    }

    /**
     * 这个区块是否**曾经**解析出过地表。
     *
     * ## 为什么不能只看 LRU
     * LRU 只有 [maxEntries] 个名额。缩到最远时，一块 256 档位的瓦片
     * 就要覆盖 32×32 = 1024 个区块 —— 滑几屏就把缓存挤空了，
     * 描边会随着内存压力忽隐忽现，看起来像 bug。
     *
     * 所以另外维护一个长期集合记「见过有数据的区块」。
     * 每个区块只占 8 字节（Long 打包），10 万个也才 0.8 MB，
     * 比把 Surface 全部留在内存里便宜得多。
     */
    fun isPopulated(cx: Int, cz: Int, dim: Int): Boolean =
        synchronized(lock) { populated.contains(packKey(cx, cz, dim)) }

    /**
     * 把 (cx, cz, dim) 打成一个 Long，省内存。
     *
     * ⚠️ 必须先**加偏移**再取位，不能直接 `and`：
     * 直接 mask 会把 `-134217728` 折成 `0`，和原点区块撞键 ——
     * 描边就会在凭空出现一个「已探索」的原点。
     * 加上 2^21 之后，`[-2^21, 2^21)` 一一映射到 `[0, 2^22)`。
     *
     * 范围：±2,097,152 区块 = ±33,554,432 方块，
     * 超过 Minecraft 的世界边界（±30,000,000 方块），够用。
     *
     * 22+22+20 = 64 位刚好用满，所以 key **可能是负数** ——
     * 这是正常的，`HashSet<Long>` 不在意符号。只是调试时会看到
     * `-6170657...` 这种值，别当成溢出 bug。
     */
    private fun packKey(cx: Int, cz: Int, dim: Int): Long =
        (((cx.toLong() + OFFSET) and MASK22) shl 42) or
                (((cz.toLong() + OFFSET) and MASK22) shl 20) or
                (dim.toLong() and MASK20)


    fun clear() = synchronized(lock) {
        lru.clear()
        populated.clear()
        hits = 0
        misses = 0
    }
}
