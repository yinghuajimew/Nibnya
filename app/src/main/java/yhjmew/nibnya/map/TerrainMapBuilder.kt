package yhjmew.nibnya.map

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import yhjmew.nibnya.util.AppLogger

/**
 * 把一批区块渲染成一张位图。
 *
 * ## 只读
 * 全程只调用 [ChunkReader.readChunk]，不写数据库。要改地形另开方法，
 * 不要在渲染路径里混入写操作 —— 渲染可能跑在多线程，写会互相踩。
 *
 * ## 算法
 * 逐列自上而下扫第一个非空气方块，作为「地表」。
 * 这与 `bedrock-level/src/chunk.cpp:get_top_y` 一致：
 *
 * ```cpp
 * for (int y = max_y; y >= min_y; y--) {
 *     if (name == "minecraft:unknown") continue;
 *     if (top_y < min_y && name != "minecraft:air") top_y = y;
 *     if (...) break;
 * }
 * ```
 *
 * 差别是我把 `water` 也算地表（否则海底地形会显示成海底的沙/石，
 * 海洋看起来就不像海了）—— 但颜色仍按水算，所以还是蓝的。
 */
class TerrainMapBuilder(
    private val reader: ChunkReader,
    private val colors: BlockColorTable,
) {

    companion object {
        /** 诊断用：几类情况各只记一次。 */
    private var dbgChunkLogged = false
    private var dbgEmptyLogged = false
    private var dbgOkLogged = false

    /** 找不到任何方块时的颜色（纯黑，与「没有区块」区分开）。 */
        private const val EMPTY = 0xFF000000.toInt()

        /** 未知群系回退。 */
        private const val NO_BIOME = -1
    }

    /**
     * 渲染一个区块矩形区域。
     *
     * @param minCx 起始区块 X（含）
     * @param minCz 起始区块 Z（含）
     * @param chunkCountX 横向区块数
     * @param chunkCountZ 纵向区块数
     * @param dim 维度：0 主世界 / 1 地狱 / 2 末地
     * @param onProgress 已完成的区块数 / 总区块数，会在线程池回调
     */
    suspend fun render(
        minCx: Int,
        minCz: Int,
        chunkCountX: Int,
        chunkCountZ: Int,
        dim: Int = 0,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Bitmap = withContext(Dispatchers.Default) {
        val wPx = chunkCountX * 16
        val hPx = chunkCountZ * 16
        val pixels = IntArray(wPx * hPx) { EMPTY }

        val total = chunkCountX * chunkCountZ
        val done = java.util.concurrent.atomic.AtomicInteger(0)

        // 逐区块并行。区块之间互不依赖，天然可并行；
        // 但并发太高会同时压 8 个 ~24 个子区块的解析，反而变慢，
        // 所以按 CPU 核数收敛（协程调度器本身也是这么多线程）。
        val rows = (0 until chunkCountZ).map { dz ->
            async {
                for (dx in 0 until chunkCountX) {
                    val cx = minCx + dx
                    val cz = minCz + dz
                    renderChunk(pixels, wPx, dx * 16, dz * 16, cx, cz, dim)
                    onProgress(done.incrementAndGet(), total)
                }
            }
        }
        rows.awaitAll()

        Bitmap.createBitmap(pixels, wPx, hPx, Bitmap.Config.ARGB_8888)
    }

    /** 渲染单个区块的 16×16 到 [pixels] 的对应位置。 */
    private fun renderChunk(
        pixels: IntArray,
        stride: Int,
        ox: Int,
        oz: Int,
        cx: Int,
        cz: Int,
        dim: Int,
    ) {
        val chunk = try {
            reader.readChunk(cx, cz, dim)
        } catch (e: Exception) {
            if (!dbgChunkLogged) {
                dbgChunkLogged = true
                AppLogger.warn("MapRender", "readChunk($cx,$cz) 抛异常: ${e.message}")
            }
            null
        } ?: return   // 空区块保持 EMPTY

        if (!chunk.hasTerrain) {
            if (!dbgEmptyLogged) {
                dbgEmptyLogged = true
                AppLogger.warn("MapRender", "区块($cx,$cz) 无地形数据（子区块 0 个）")
            }
            return
        }
        if (!dbgOkLogged) {
            dbgOkLogged = true
            AppLogger.info(
                "MapRender",
                "区块($cx,$cz) 子区块 ${chunk.subChunks.size} 个，Y 范围 ${chunk.yIndexRange}"
            )
        }

        val range = chunk.yIndexRange ?: return

        // 记录「有区块但整列全空」的比例：这类区块会画成黑色，
        // 与「根本没有区块」视觉上一样，但成因不同，值得记一笔。
        var emptyColumns = 0

        for (x in 0 until 16) {
            for (z in 0 until 16) {
                val biome = chunk.biomes?.at(x, z) ?: NO_BIOME
                val entry = topBlockOf(chunk, range, x, z)
                val color = if (entry == null) {
                    emptyColumns++
                    EMPTY
                } else {
                    colors.colorOf(entry.name, entry.tags, biome)
                }
                pixels[(oz + z) * stride + (ox + x)] = color
            }
        }

        // 整块 256 列全空才记日志 —— 单列空很常见（洞穴口），不值得刷屏
        if (emptyColumns == 256) {
            AppLogger.warn(
                "MapRender",
                "区块 ($cx,$cz) 有子区块但整列无实体方块，渲染为黑色"
            )
        }
    }

    /**
     * 从最高子区块往下找第一个非空气方块。
     *
     * 每层先看第 0 层（主体），是空气才回看第 1 层（水/植物覆盖层）。
     * 这样海洋会显示成蓝色水面而不是海底沙石 —— 与游戏里「看起来是水面」一致。
     *
     * @return 地表方块；整列为空返回 null
     */
    private fun topBlockOf(
        chunk: ParsedChunk,
        range: IntRange,
        x: Int,
        z: Int,
    ): PaletteEntry? {
        // 从最高子区块倒着找，命中就停 —— 比从 -64 开始扫快得多
        for (yIndex in range.reversed()) {
            val sub = chunk.subChunks[yIndex] ?: continue
            for (y in 15 downTo 0) {
                // ① 主体层
                val primary = sub.layer.entryAt(x, y, z)
                if (primary != null && !isTransparent(primary.name)) {
                    return primary
                }
                // ② 覆盖层：水 / 草 / 花等
                val overlay = sub.overlay?.entryAt(x, y, z)
                if (overlay != null && !isTransparent(overlay.name)) {
                    return overlay
                }
            }
        }
        return null
    }

    /** 视为「什么都没有」的方块。 */
    private fun isTransparent(name: String): Boolean = when (name) {
        "minecraft:air",
        "minecraft:unknown",
        "minecraft:structure_void",
        "minecraft:light_block",
        "minecraft:barrier" -> true
        else -> false
    }
}
