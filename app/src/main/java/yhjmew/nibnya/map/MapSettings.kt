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

    // ── 描边 ──

    fun boundaryEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean("boundary", true)

    fun setBoundaryEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("boundary", on).apply()
    }

    // ── 预加载半径（已有功能，原先未持久化）──

    const val PRELOAD_AUTO = -1

    fun preloadRadius(ctx: Context): Int =
        prefs(ctx).getInt("preload_radius", PRELOAD_AUTO)

    fun setPreloadRadius(ctx: Context, radius: Int) {
        prefs(ctx).edit().putInt("preload_radius", radius).apply()
    }
}
