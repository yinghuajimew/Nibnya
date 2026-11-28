package yhjmew.nibnya.map

import android.content.Context
import android.content.SharedPreferences

/**
 * 地图相关设置的存取。
 *
 * ## 为什么集中一处
 *
 * 这几个值有 5 个使用点：设置页（读写）、[SaveTiles]（缓存与线程）、
 * [ChunkSurfaceCache]（缓存条数）、[MapView]（描边开关）、
 * [SeedTerrainSource]（种子层开关）。
 * 如果各写各的 `prefs.getXxx("map_xxx")`，键名拼错一次就是「设了不生效」，
 * 而且默认值容易三处不一致。全部收在这里，改默认值只动一处。
 *
 * ## 为什么修改后要重启地图页
 *
 * 缓存、线程池、种子层都是构造时创建的。改完立刻生效需要重建整个
 * `MapSession`，代价比「下次打开地图页生效」大得多。设置页因此显示
 * 「重启地图页后生效」，而不是假装即时生效。
 */
object MapSettings {

    private const val PREF = "nibnya_map"

    /** 瓦片缓存上限（MB）。 */
    const val CACHE_MB_MIN = 8
    const val CACHE_MB_MAX = 64
    const val CACHE_MB_DEFAULT = 20

    /** 区块缓存条数。每条约 1.5 KB。 */
    const val CHUNK_ENTRIES_MIN = 512
    const val CHUNK_ENTRIES_MAX = 16384
    const val CHUNK_ENTRIES_DEFAULT = 4096

    /** 渲染线程数。 */
    const val THREADS_MIN = 1
    const val THREADS_MAX = 4
    const val THREADS_DEFAULT = 2

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ── 瓦片缓存 ──

    fun cacheMb(ctx: Context): Int =
        prefs(ctx).getInt("cache_mb", CACHE_MB_DEFAULT)
            .coerceIn(CACHE_MB_MIN, CACHE_MB_MAX)

    fun setCacheMb(ctx: Context, mb: Int) {
        prefs(ctx).edit().putInt("cache_mb", mb.coerceIn(CACHE_MB_MIN, CACHE_MB_MAX)).apply()
    }

    // ── 区块缓存条数 ──

    fun chunkEntries(ctx: Context): Int =
        prefs(ctx).getInt("chunk_entries", CHUNK_ENTRIES_DEFAULT)
            .coerceIn(CHUNK_ENTRIES_MIN, CHUNK_ENTRIES_MAX)

    fun setChunkEntries(ctx: Context, n: Int) {
        prefs(ctx).edit()
            .putInt("chunk_entries", n.coerceIn(CHUNK_ENTRIES_MIN, CHUNK_ENTRIES_MAX))
            .apply()
    }

    // ── 线程数 ──

    fun threads(ctx: Context): Int =
        prefs(ctx).getInt("threads", THREADS_DEFAULT)
            .coerceIn(THREADS_MIN, THREADS_MAX)

    fun setThreads(ctx: Context, n: Int) {
        prefs(ctx).edit().putInt("threads", n.coerceIn(THREADS_MIN, THREADS_MAX)).apply()
    }

    // ── 种子层 ──

    /**
     * 是否显示种子地形层（底层群系推演）。
     *
     * 关掉能省一半 CPU —— 种子层是纯 C 计算，不吃 IO 但吃 CPU。
     * 低端机上关掉之后拖动会明显顺滑。
     */
    fun seedLayerEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean("seed_layer", true)

    fun setSeedLayerEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("seed_layer", on).apply()
    }

    // ── 维度 ──

    /** 主世界 */
    const val DIM_OVERWORLD = 0

    /** 下界 */
    const val DIM_NETHER = 1

    /** 末地 */
    const val DIM_END = 2

    /**
     * 上次查看的维度。
     *
     * 与「存档」无关：用户在 A 存档看下界，换到 B 存档仍然从下界开始，
     * 因为「我正在看哪个维度」是操作习惯，不是存档属性。
     */
    fun dimension(ctx: Context): Int =
        prefs(ctx).getInt("map_dim", DIM_OVERWORLD).coerceIn(0, 2)

    fun setDimension(ctx: Context, dim: Int) {
        prefs(ctx).edit().putInt("map_dim", dim).apply()
    }

    // ── 状态气泡 ──

    /** 气泡停留秒数的最小值（0 = 不自动消失，由下一次消息顶掉） */
    const val STATUS_SECONDS_MIN = 0

    /** 气泡停留秒数的最大值 */
    const val STATUS_SECONDS_MAX = 30

    /**
     * 状态气泡停留秒数。0 表示一直显示到被新消息替换。
     *
     * 默认 3 秒：状态行一次只有一条，3 秒足够读完又不挡地图。
     */
    fun statusSeconds(ctx: Context): Int =
        prefs(ctx).getInt("map_status_seconds", 3).coerceIn(STATUS_SECONDS_MIN, STATUS_SECONDS_MAX)

    fun setStatusSeconds(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt("map_status_seconds", v).apply()
    }

    /** 气泡位置：0 左上 / 1 右上 / 2 左下 / 3 右下。 */
    const val CORNER_TOP_START = 0
    const val CORNER_TOP_END = 1
    const val CORNER_BOTTOM_START = 2
    const val CORNER_BOTTOM_END = 3

    fun statusCorner(ctx: Context): Int =
        prefs(ctx).getInt("map_status_corner", CORNER_TOP_START).coerceIn(0, 3)

    fun setStatusCorner(ctx: Context, corner: Int) {
        prefs(ctx).edit().putInt("map_status_corner", corner).apply()
    }

    // ── 描边（两条线各自独立）──

    /**
     * 粉色线：玩家真正加载过的区块。
     *
     * 键名用 `boundary_loaded`。旧版本只有一个 `boundary` 开关，
     * 这里不迁移它 —— 旧值不分青红皂白，迁过来只会让「本来关着的人
     * 突然多出一条青色线」，不如都回到默认全开。
     */
    fun loadedBoundaryEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean("boundary_loaded", true)

    fun setLoadedBoundaryEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("boundary_loaded", on).apply()
    }

    /**
     * 青色线：玩家拖动浏览过的范围（含没加载的区块）。
     *
     * 默认**关闭**。理由：它严格对齐瓦片边界，看起来是大矩形，
     * 对多数人来说是噪声；想看「我扫到哪」时再打开。
     */
    fun visitedBoundaryEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean("boundary_visited", false)

    fun setVisitedBoundaryEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("boundary_visited", on).apply()
    }

    // ── 预加载半径（已有功能，原先未持久化）──

    const val PRELOAD_AUTO = -1

    fun preloadRadius(ctx: Context): Int =
        prefs(ctx).getInt("preload_radius", PRELOAD_AUTO)

    fun setPreloadRadius(ctx: Context, radius: Int) {
        prefs(ctx).edit().putInt("preload_radius", radius).apply()
    }
}
