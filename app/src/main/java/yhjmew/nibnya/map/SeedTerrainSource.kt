package yhjmew.nibnya.map

import android.graphics.Bitmap
import android.util.Log
import yhjmew.nibnya.util.AppLogger
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 种子地形层 —— 用世界种子推演群系，**任何地方都有内容**。
 *
 * ## 为什么需要它
 * [SaveTiles] 只能画出**玩家去过**的区块。刚打开一个存档时，
 * 除了出生点周围几块，其余全是黑的 —— 用户根本不知道世界长什么样。
 * 这一层补上「没导入存档也能看见大致轮廓」。
 *
 * ## 两层怎么配合
 * ```
 * 底层  SeedTerrainSource（本类）  永远有内容，颜色 = 群系色
 * 上层  SaveTiles                  缺失区块保持透明
 * ```
 * [MapView] 先画底层再画上层，所以已探索区域被真实地表盖住，
 * 未探索区域露出群系色。两层采样档位可以不同（各自独立瓦片网格）。
 *
 * ## 引擎
 * cubiomes（MIT，© Cubitect）经 JNI 调用，见 `cpp/nibnya_seed.c`。
 * 产物 so 名固定为 `libnibnya.so`。
 *
 * ## 线程
 * 与 [SaveTiles] 同构：`get`/`key` 在 UI 线程，`request` 投递到线程池。
 * 两块瓦片可以并行 —— JNI 侧是纯函数，没有共享状态。
 */
class SeedTerrainSource(
    /** 世界种子。负数也要能正确处理，所以用 Long。 */
    private val seed: Long,
    /** 版本档位，见 [MC_LEVEL] 的说明。由 [fromVersionString] 算出。 */
    private val mcLevel: Int,
    /** 维度：0 主世界，-1 下界，1 末地。 */
    private val dimension: Int,
) : TileSource {

    companion object {
        private const val TAG = "SeedTerrain"

        /** 一块瓦片的采样点数。与 [SaveTiles.CELLS] 保持一致，便于叠加。 */
        private const val CELLS = 128

        /**
         * 缓存上限。一块瓦片 128×128×4 字节 = 64 KB，
         * 64 块约 4 MB —— 种子层是「远景底图」，没必要缓存太多。
         */
        private const val CACHE_TILES = 64

        /**
         * 版本档位 → cubiomes 的 MCVersion。
         *
         * 与 `nibnya_seed.c` 的 `map_mc()` **必须一致**。
         * 分开定义是因为 Kotlin 侧要打日志说明「你用的是哪一版地形」。
         *
         * 注意：cubiomes 最新只到 1.21，而新版存档可能是 1.26。
         * 超出范围时钳到最新 —— 宁可地形略有偏差也不能全黑。
         */
        private val MC_LEVEL_NAMES = arrayOf(
            "1.21+", "1.20", "1.19", "1.18", "1.17", "1.16"
        )

        /**
         * 从 `level.dat` 的 `InventoryVersion` 或
         * `lastOpenedWithVersion` 解析出档位。
         *
         * 输入形如 `"1.26.52"`、`"1.21.50"`。
         * 解析失败返回 0（= 最新的 1.21+ 档），这是最不会出错的选择：
         * 群系布局在各版本之间差异主要在 1.18 前后，
         * 取最新的总比取最老的好。
         */
        fun fromVersionString(version: String?): Int {
            if (version.isNullOrBlank()) return 0
            val parts = version.split('.')
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return 0
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: return 0

            // 1.x 的世界生成版本线：1.16 / 1.17 / 1.18 / 1.19 / 1.20 / 1.21+
            if (major != 1) return 0
            return when {
                minor >= 21 -> 0
                minor == 20 -> 1
                minor == 19 -> 2
                minor == 18 -> 3
                minor == 17 -> 4
                minor == 16 -> 5
                else -> 5   // 1.15 及更早：cubiomes 支持，但没必要细分
            }
        }

        fun mcName(level: Int): String =
            MC_LEVEL_NAMES.getOrNull(level) ?: MC_LEVEL_NAMES[0]

        /** so 是否加载成功。false 时整层降级为「不画」。 */
        var available: Boolean = false
            private set

        /** 日志级别：1=info 2=warn 3=error（与 C 侧约定） */
        private const val LVL_ERROR = 3

        /**
         * C 侧回调入口。
         *
         * ⚠️ 由 native 线程直接调用，可能在任意时刻进来。
         * 只做「打日志」，不要碰 UI、不要加锁、不要 throw。
         */
        @JvmStatic
        fun onNativeLog(level: Int, msg: String) {
            try {
                when {
                    level >= LVL_ERROR -> Log.e(TAG, msg)
                    level == 2 -> Log.w(TAG, msg)
                    else -> Log.i(TAG, msg)
                }
                // 只把 warn/error 进 AppLogger：info 是逐块统计，
                // 一秒几十条会把 500 条环形缓冲刷满，把真正的错误挤掉。
                if (level >= 2) {
                    AppLogger.warn(TAG, msg)
                }
            } catch (_: Throwable) {
                // 日志本身失败不能反过来引发崩溃
            }
        }

        /*
         * 这两个是 static native（C 侧第二个参数是 jclass）。
         * 声明必须带 @JvmStatic 且在 companion 里，否则 Kotlin 会把它们
         * 编译成实例方法，JNI 就找不到对应的 Java_yhjmew_..._nativeXxx。
         */
        @JvmStatic
        private external fun nativeInstallCrashHandler(path: String): Int

        @JvmStatic
        private external fun nativeStats(): LongArray?

        /**
         * 安装 native 崩溃捕获。
         *
         * Java 的 UncaughtExceptionHandler 抓不到 SIGSEGV —— 内核直接杀进程，
         * 一行 Java 都执行不到。所以必须在 native 里装信号处理器。
         *
         * @param path 崩溃日志绝对路径（App 私有目录，保证可写）
         * @return 0 成功；-1 so 未加载；-2 调用异常；其他见 C 侧
         */
        fun installNativeCrashHandler(path: String): Int {
            if (!available) return -1
            return try {
                nativeInstallCrashHandler(path)
            } catch (t: Throwable) {
                Log.w(TAG, "安装 native 崩溃捕获失败", t)
                -2
            }
        }

        /** native 统计：{成功瓦片数, 失败瓦片数, 累计毫秒}。失败返回 null。 */
        fun stats(): LongArray? {
            if (!available) return null
            return try {
                nativeStats()
            } catch (_: Throwable) {
                null
            }
        }

        init {
            available = try {
                System.loadLibrary("nibnya")
                true
            } catch (t: Throwable) {
                Log.w(TAG, "libnibnya.so 加载失败，种子层禁用", t)
                false
            }
        }
    }

    // ── JNI（方法名必须与 nibnya_seed.c 的 Java_yhjmew_nibnya_map_SeedTerrainSource_* 对应）──

    /**
     * 生成一块瓦片。
     *
     * @param out 长度 >= cells*cells 的 ARGB 缓冲，由 C 侧直接填色
     * @return 0 成功；负数见 `nibnya_seed.c` 的注释
     */
    private external fun nativeTile(
        seed: Long, mcLevel: Int, dim: Int,
        blockX: Int, blockZ: Int, cells: Int, blocksPerCell: Int,
        out: IntArray,
    ): Int

    private external fun nativeBiomeAt(
        seed: Long, mcLevel: Int, dim: Int, blockX: Int, blockZ: Int,
    ): Int

    private external fun nativeSpawn(seed: Long, mcLevel: Int): IntArray?

    /*
     * 以下两个是 static —— 由 C 侧用 CallStaticVoidMethod 调用，
     * 不依赖具体实例（崩溃可能发生在任何一块瓦片上）。
     */


    // ── C → Java 的日志桥 ──

    /** 错误码 → 用户能看懂的原因。 */
    private fun describeRc(rc: Int): String = when (rc) {
        0 -> "成功"
        -1 -> "参数非法"
        -2 -> "版本档位不支持"
        -3 -> "内存不足"
        -4 -> "缓存尺寸计算失败"
        -5 -> "群系生成失败"
        -6 -> "JNI 数组访问失败"
        -7 -> "维度非法"
        else -> "未知错误 $rc"
    }

    // ── 状态 ──

    private val lock = Any()
    private val pool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "seed-tile").apply { isDaemon = true }
    }

    /** 已完成瓦片。用 LRU 是因为远景底图很少回头再看。 */
    private val cache: MutableMap<String, Bitmap> =
        Collections.synchronizedMap(object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
                size > CACHE_TILES
        })

    /** 正在生成中的键，避免同一块瓦片重复排队。 */
    private val inflight = HashSet<String>()

    /** 视图当前需要的瓦片。生成完发现不在集合里就直接丢弃。 */
    @Volatile
    private var wanted: Set<String> = emptySet()

    @Volatile
    private var invalidation: (() -> Unit)? = null

    @Volatile
    private var generation = 0

    @Volatile
    private var dimensionOverride: Int = dimension

    // ── TileSource ──

    override fun tileCells(): Int = CELLS

    /**
     * 把视图档位映射到 cubiomes 接受的值。
     *
     * ## 为什么需要映射
     *
     * 视图用 2 的幂（1/2/4/8/16/32/64/128/256），相邻只差 2 倍，
     * 缩放时采样点尺寸平滑。但 **cubiomes 只接受 1/4/16/64/256**
     * （`generator.c` 里按 4 的幂查层叠表），传 2/8/32/128 会算错甚至除零。
     *
     * ## 为什么向上取整
     *
     * `2 → 4`、`8 → 16`、`32 → 64`、`128 → 256`。
     * 取更粗的那一档意味着**采样点变稀、瓦片覆盖变大**：
     * 请求 2 档时按 4 档生成，画面比期望粗一点，但**内容完整**。
     *
     * 如果向下取整（`2 → 1`），覆盖范围会小一半 ——
     * 一块瓦片只画出应有面积的 1/4，边缘露白，看起来像缺图。
     * 宁可粗，不可缺。
     *
     * ## 为什么下限是 4 而不是 1
     *
     * 这层是远景底图。1 方块/采样点时点数会翻 16 倍，
     * 生成明显变慢，而这个缩放级别下存档层已经盖在上面，
     * 种子层根本看不见。存档层（[SaveTiles]）才需要 1 档。
     */
    override fun blocksPerCell(scale: Int): Int {
        val s = scale.coerceIn(1, 256)
        return when {
            s <= 4 -> 4      // 1、2 → 4
            s <= 16 -> 16    // 8 → 16
            s <= 64 -> 64    // 32 → 64
            else -> 256      // 128、256 → 256
        }
    }

    /**
     * 缓存键用**实际采样档位**而不是视图传来的 scale。
     *
     * [blocksPerCell] 会把 1 钳成 4，所以 scale=1 和 scale=4 生成的是
     * 完全相同的瓦片。若键里用 scale，同一块地形会被算两遍、占两份内存。
     */
    override fun key(scale: Int, tileX: Int, tileZ: Int): String =
        "$generation/$dimensionOverride/seed/${blocksPerCell(scale)}/$tileX/$tileZ"

    override fun get(scale: Int, tileX: Int, tileZ: Int): Bitmap? =
        cache[key(scale, tileX, tileZ)]

    override fun isReady(): Boolean = available

    override fun generationId(): Int = generation

    override fun setInvalidation(callback: (() -> Unit)?) {
        invalidation = callback
    }

    override fun setWanted(keys: Collection<String>) {
        wanted = if (keys.isEmpty()) emptySet() else HashSet(keys)
    }

    override fun hasAnyData(): Boolean = available

    override fun request(scale: Int, tileX: Int, tileZ: Int) {
        if (!available) return
        val k = key(scale, tileX, tileZ)
        if (cache.containsKey(k)) return
        synchronized(lock) {
            if (!inflight.add(k)) return
        }

        val gen = generation
        val dim = dimensionOverride
        val bpc = blocksPerCell(scale)

        pool.execute {
            /*
             * ⚠️ inflight 的移除必须在 finally 里。
             *
             * 原先是「成功分支删一次、catch 分支删一次」，但中间有个
             * `if (gen != generation) return@execute` —— 换世界/切维度时
             * 从那里返回，**两个分支都没走到**，这个键就永久留在 inflight
             * 里了。之后同名的瓦片再也不会被请求（`inflight.add` 返回 false），
             * 表现为「那块区域永远是空的，怎么滑都不出现」。
             */
            try {
                val bmp = render(scale, tileX, tileZ, bpc, dim)

                // 换世界了 → 这块钱作废（Bitmap 也要回收，否则泄漏 native 内存）
                if (gen != generation) {
                    bmp?.recycle()
                    return@execute
                }

                if (bmp != null) {
                    cache[k] = bmp
                }
                if (wanted.contains(k)) invalidation?.invoke()
            } catch (t: Throwable) {
                // 这里只能抓到 Java 异常；native 崩溃由信号处理器负责
                Log.w(TAG, "渲染失败 $k", t)
                AppLogger.warn(TAG, "渲染失败 $k: ${t.message}")
            } finally {
                synchronized(lock) { inflight.remove(k) }
            }
        }
    }

    // ── 渲染 ──

    /**
     * 真正干活的地方。返回 null 表示这块瓦片没有任何内容（全空）。
     */
    private fun render(
        scale: Int, tileX: Int, tileZ: Int, bpc: Int, dim: Int,
    ): Bitmap? {
        val pixels = IntArray(CELLS * CELLS)

        // 瓦片左上角的方块坐标
        val blockX = tileX * CELLS * bpc
        val blockZ = tileZ * CELLS * bpc

        val rc = nativeTile(
            seed, mcLevel, dim,
            blockX, blockZ, CELLS, bpc,
            pixels,
        )
        if (rc != 0) {
            /*
             * 每个码单独处理，不要把「版本不支持」和「内存不足」混在一起。
             * 版本不支持是环境问题（打一次就够），其余是异常（要能追）。
             */
            when (rc) {
                -2 -> if (!warnedVersion) {
                    warnedVersion = true
                    val msg = "cubiomes 不支持版本档位 $mcLevel（${mcName(mcLevel)}），种子层停用"
                    Log.w(TAG, msg)
                    AppLogger.warn(TAG, msg)
                }
                -3 -> Log.w(TAG, "瓦片内存不足（cells=$CELLS bpc=$bpc）")
                else -> Log.w(TAG, "nativeTile 失败 rc=$rc: ${describeRc(rc)}")
            }
            return null
        }

        val bmp = Bitmap.createBitmap(CELLS, CELLS, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, CELLS, 0, 0, CELLS, CELLS)
        return bmp
    }

    @Volatile
    private var warnedVersion = false

    // ── 生命周期 ──

    /** 切维度时调用：作废旧瓦片（下界和末地的群系完全不同）。 */
    fun setDimension(dim: Int) {
        if (dim == dimensionOverride) return
        dimensionOverride = dim
        bumpGeneration()
    }

    private fun bumpGeneration() {
        generation++
        cache.clear()
        synchronized(lock) { inflight.clear() }
    }

    /** 世界种子变化（换存档）—— 由调用方新建实例，这里只做收尾。 */
    fun shutdown() {
        pool.shutdownNow()
        try {
            pool.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        cache.clear()
        synchronized(lock) { inflight.clear() }
    }

    /** 世界出生点（主世界）。失败返回 null。 */
    fun spawnPoint(): Pair<Int, Int>? {
        if (!available) return null
        val arr = nativeSpawn(seed, mcLevel) ?: return null
        if (arr.size < 2) return null
        return Pair(arr[0], arr[1])
    }
}
