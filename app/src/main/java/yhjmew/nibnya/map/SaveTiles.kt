package yhjmew.nibnya.map

import android.graphics.Bitmap
import yhjmew.nibnya.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * 存档地表图层：从 LevelDB 读区块，画成瓦片。
 *
 * ## 结构（对照 SeedCraft 的 SaveTiles）
 * | 项 | SeedCraft | 这里 |
 * |---|---|---|
 * | 瓦片尺寸 | 128 采样点 | 同 |
 * | 缓存 | `LruCache<String,Bitmap>` 20 MB | 同，按 `byteCount` 计 |
 * | 生成线程 | 2 | 2（I/O 密集，多了抢 LevelDB 锁没意义） |
 * | 待办去重 | `pending` + `wanted` 两个 Set | 同 |
 * | 过期作废 | `generation` 整数 | 同 |
 *
 * 差别：SeedCraft 有预导入的定长记录（`SaveCache`），这里**直接查 LevelDB**。
 * 好处是不用先导一遍、打开就能看；代价是每块瓦片第一次生成要解析区块，
 * 由 [ChunkSurfaceCache] 兜住，所以只有首屏慢一点。
 *
 * ## 渲染流程
 * ```
 * 瓦片 128×128 采样点
 *   ↓ 每个采样点 → 该点所在区块 → ChunkSurfaceCache
 *   ↓ 取该列的 argb + height
 *   ↓ 相邻列高度差 → 明暗系数（浮雕感）
 *   ↓ 写入 IntArray → Bitmap
 * ```
 *
 * ## 线程
 * [get] 在 UI 线程（只查缓存）；[request] 投递到 [pool]；
 * 生成完回调 [invalidation]，由 MapView 转成 `invalidate()`。
 */
class SaveTiles(
    private val reader: ChunkReader,
    private val colors: BlockColorTable,
) : TileSource {

    companion object {
        /** 一块瓦片 128×128 采样点。 */
        const val CELLS = 128

        /** 缓存上限：20 MB ≈ 40 块瓦片（ARGB_8888 128²=64 KB）。 */
        private const val CACHE_BYTES = 20 * 1024 * 1024

        private const val TAG = "SaveTiles"

        /**
         * 浮雕明暗范围。
         *
         * 取自 SeedCraft `SaveTiles.render`：X 方向 0.06、Z 方向 0.04，
         * 夹在 0.62~1.38。系数小是因为高度差通常只有 1~3 格，
         * 再大就会变成噪点。
         */
        private const val SHADE_X = 0.06f
        private const val SHADE_Z = 0.04f
        private const val SHADE_MIN = 0.62f
        private const val SHADE_MAX = 1.38f

        /**
         * 向下取整除法。
         *
         * ⚠️ 不用 `Math.floorDiv` —— 它是 **API 24** 才有的，
         * 而本项目 minSdk = 23。在 23 上调用会抛 NoSuchMethodError，
         * 且只在低版本真机才复现，很容易漏掉。
         */
        fun floorDiv(a: Long, b: Long): Long {
            val q = a / b
            return if (a % b != 0L && (a xor b) < 0L) q - 1 else q
        }
    }

    /** 当前维度。切维度时 [generation] 一起递增。 */
    @Volatile
    var dimension: Int = 0
        set(value) {
            if (field != value) {
                field = value
                bumpGeneration()
            }
        }

    private val pool: ExecutorService = Executors.newFixedThreadPool(2)

    /** 按字节计的 LRU。键是 [key] 的结果。 */
    private val cache = object : android.util.LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 正在生成中的瓦片键。 */
    private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 视图当前真正需要的瓦片键。不在里面的生成结果直接丢。 */
    @Volatile
    private var wanted: Set<String> = emptySet()

    @Volatile
    private var generation = 0

    @Volatile
    private var invalidation: (() -> Unit)? = null

    /** 生成完成的瓦片计数，用来判断「是不是所有瓦片都空了」。 */
    private val generatedTiles = AtomicInteger(0)

    /** 有颜色的瓦片计数。0 且 [generatedTiles] 也够多 → 说明这个维度没数据。 */
    private val coloredTiles = AtomicInteger(0)

    private val surfaces = ChunkSurfaceCache(reader, colors)

    override fun tileCells(): Int = CELLS

    /**
     * 存档层的采样粒度 = 视图给的档位，夹在 1..256。
     *
     * 1 这一档是必须的：`bpp < 0.67` 时若还用 4，一个采样点覆盖 4 个方块、
     * 放大到占 20 屏幕像素，画面是 4×4 色块，看不清单方块。
     */
    override fun blocksPerCell(scale: Int): Int = scale.coerceIn(1, 256)

    override fun get(scale: Int, tileX: Int, tileZ: Int): Bitmap? =
        cache.get(key(scale, tileX, tileZ))

    override fun request(scale: Int, tileX: Int, tileZ: Int) {
        val k = key(scale, tileX, tileZ)
        if (cache.get(k) != null) return
        if (!pending.add(k)) return
        val gen = generation
        pool.execute {
            try {
                if (gen != generation) return@execute
                // 视图已经滑走了就跳过 —— 否则快速拖动时会积压一堆没人要的瓦片
                if (!wanted.contains(k)) return@execute

                val bmp = render(scale, tileX, tileZ) ?: return@execute
                if (gen != generation) {
                    bmp.recycle()
                    return@execute
                }
                cache.put(k, bmp)
                invalidation?.invoke()
            } catch (e: Throwable) {
                AppLogger.warn(TAG, "瓦片 $k 生成失败: ${e.message}")
            } finally {
                pending.remove(k)
            }
        }
    }

    override fun setWanted(keys: Collection<String>) {
        wanted = keys.toHashSet()
    }

    override fun key(scale: Int, tileX: Int, tileZ: Int): String =
        "$generation/$dimension/$scale/$tileX/$tileZ"

    override fun isReady(): Boolean = true

    override fun generationId(): Int = generation

    override fun setInvalidation(callback: (() -> Unit)?) {
        invalidation = callback
    }

    override fun hasAnyData(): Boolean =
        coloredTiles.get() > 0 || generatedTiles.get() == 0

    /** 切世界：清缓存、作废在途任务。 */
    fun bumpGeneration() {
        generation++
        pending.clear()
        wanted = emptySet()
        cache.evictAll()
        surfaces.clear()
        generatedTiles.set(0)
        coloredTiles.set(0)
    }

    /**
     * 关掉生成线程池。
     *
     * ## 必须等线程真正退出
     * 调用链是 `SaveTiles.shutdown()` → `MapSession.close()` → `db.close()`。
     * 如果这里只 `shutdownNow()` 就返回，后台线程可能正卡在
     * `ChunkReader.readChunk()` 里读 LevelDB；紧接着句柄被关掉，
     * 线程再访问就是**已释放的原生指针** ——
     * 这是 SIGSEGV，Java 层 try/catch 完全拦不住，表现为「退出地图页闪退」。
     *
     * `shutdownNow()` 会 interrupt 任务，但 LevelDB 的 get 是本地调用、
     * 不响应中断，所以必须 [awaitTermination] 等它真的读完返回。
     *
     * 2 秒是经验值：最坏情况是一块瓦片还要读几百个区块，
     * 正常都在几十毫秒内结束。超时也继续往下走 ——
     * 宁可冒一点风险也不能卡死 UI（真卡死就是 ANR）。
     */
    fun shutdown() {
        pool.shutdownNow()
        try {
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                AppLogger.warn(TAG, "瓦片线程 2s 内未退出，继续关闭（可能有正在读库的任务）")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        cache.evictAll()
        surfaces.clear()
    }

    /**
     * 这个区块是否已经渲染过、并且确实有地表数据。
     *
     * 给 [MapView.ChunkMask] 描边用。
     *
     * ## 为什么用「已渲染」而不是「DB 里存在」
     * 查 DB 需要一次 LevelDB get，而描边在 UI 线程每个区块要问 4 次，
     * 屏幕上几十个区块就是几百次磁盘查询，必然掉帧。
     * 已渲染集合本来就存在内存里（[ChunkSurfaceCache]），零成本。
     *
     * 代价：描边描述的是**已加载数据的轮廓**，而不是存档的真实形状。
     * 视觉上表现为「加载到哪，边就画到哪」，滑动时边界会逐渐外扩。
     * 这个语义对「这地方我还没看过」的提示反而更直接。
     */
    fun isChunkPopulated(cx: Int, cz: Int): Boolean =
        surfaces.isPopulated(cx, cz, dimension)

    /** 缓存与解析统计，切世界时打一行。 */
    fun debugStats(): String =
        "tiles=${cache.size()} 区块缓存=${surfaces.statsString()}"

    // ── 生成 ──

    private fun render(scale: Int, tileX: Int, tileZ: Int): Bitmap? {
        val step = blocksPerCell(scale)
        val blockX0 = tileX.toLong() * CELLS * step
        val blockZ0 = tileZ.toLong() * CELLS * step

        /*
         * 分两次遍历：
         *   ① 取色 —— 逐列查区块，得到 argb / height
         *   ② 打光 —— 需要右邻和下邻的高度，所以必须等①全部填完
         * 合成一次做不到，因为明暗依赖邻居。
         */
        val argb = IntArray(CELLS * CELLS)
        val height = ShortArray(CELLS * CELLS)

        /*
         * 逐列取色。
         *
         * ⚠️ `surfaces.get()` 内部要加锁，而这里的调用密度很高：
         *   · step=4    → 4 个采样点共用同一区块
         *   · step=256  → 16 个采样点共用同一区块
         * 直接调的话一块瓦片要查 16384 次，其中 3/4 ~ 15/16 是同一个键。
         * 所以缓存「上一个区块」——因为 i / j 是连续递增的，
         * 同一个区块一定出现在连续的一段里，只需记住上一次的 cx/cz。
         */
        var any = false
        var lastCx = Int.MIN_VALUE
        var lastCz = Int.MIN_VALUE
        var lastSurface: ChunkSurfaceCache.Surface? = null
        val dim = dimension

        for (j in 0 until CELLS) {
            val bz = blockZ0 + j.toLong() * step
            val cz = floorDiv(bz, 16L).toInt()
            val lz = (bz - cz * 16L).toInt()
            // 换行时 z 变了，上一行的记忆必然失效
            lastCx = Int.MIN_VALUE

            for (i in 0 until CELLS) {
                val bx = blockX0 + i.toLong() * step
                val cx = floorDiv(bx, 16L).toInt()
                val lx = (bx - cx * 16L).toInt()

                if (cx != lastCx || cz != lastCz) {
                    lastSurface = surfaces.get(cx, cz, dim)
                    lastCx = cx
                    lastCz = cz
                }
                // ⚠️ ChunkSurfaceCache 内部索引是 z*16+x，不是 x*16+z
                val idx = lz * 16 + lx
                val surface = lastSurface!!
                val c = surface.argb[idx]
                if (c == ChunkSurfaceCache.NO_COLOR) continue

                val p = j * CELLS + i
                argb[p] = c
                height[p] = surface.height[idx]
                any = true
            }
        }

        // 两个计数必须一起读（见 hasAnyData），所以同一步更新
        generatedTiles.incrementAndGet()
        if (!any) {
            // 空瓦片也缓存：滑到没探索的地方时不必反复重算
            return Bitmap.createBitmap(CELLS, CELLS, Bitmap.Config.ARGB_8888)
        }
        coloredTiles.incrementAndGet()

        // ② 浮雕：只对「自己和邻居都有颜色」的格子打光
        for (j in 0 until CELLS) {
            for (i in 0 until CELLS) {
                val p = j * CELLS + i
                val c = argb[p]
                if (c == 0) continue

                val h = height[p].toInt()
                val hx = if (i + 1 < CELLS && argb[p + 1] != 0) height[p + 1].toInt() else h
                val hz = if (j + 1 < CELLS && argb[p + CELLS] != 0) height[p + CELLS].toInt() else h

                val dx = hx - h
                val dz = hz - h
                var shade = 1f + dx * SHADE_X + dz * SHADE_Z
                shade = shade.coerceIn(SHADE_MIN, SHADE_MAX)

                val r = min(255, ((c shr 16 and 0xFF) * shade).toInt())
                val g = min(255, ((c shr 8 and 0xFF) * shade).toInt())
                val b = min(255, ((c and 0xFF) * shade).toInt())
                argb[p] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }

        val bmp = Bitmap.createBitmap(CELLS, CELLS, Bitmap.Config.ARGB_8888)
        bmp.setPixels(argb, 0, CELLS, 0, 0, CELLS, CELLS)
        return bmp
    }
}
