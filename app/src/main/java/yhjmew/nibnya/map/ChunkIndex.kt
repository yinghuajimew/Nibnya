package yhjmew.nibnya.map

import com.litl.leveldb.DB
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 扫描存档里实际有哪些区块，算出范围与中心。
 *
 * ## 为什么需要它
 * 2026-11-02 实测「我的世界.mcworld」：主世界区块 X 从 -316 到 592、
 * Z 从 -276 到 206，而第一版固定画以 (0,0) 为中心 5 区块，
 * 那里**根本没有区块数据** —— 所以整张图全黑。
 *
 * 中心必须跟着存档走，不能写死原点点。
 *
 * ## 只读
 * 全程只 seek/next，不写。
 */
class ChunkIndex(private val db: DB) {

    companion object {
        /** 过滤明显不合理坐标：解析错位时会得到天文数字。 */
        private const val SANE_LIMIT = 1_000_000
    }

    /** 区块覆盖范围（含端点）。 */
    data class Bounds(
        val minCx: Int, val maxCx: Int,
        val minCz: Int, val maxCz: Int,
        val chunkCount: Int,
    ) {
        val centerCx: Int get() = (minCx + maxCx) / 2
        val centerCz: Int get() = (minCz + maxCz) / 2
        val widthChunks: Int get() = maxCx - minCx + 1
        val depthChunks: Int get() = maxCz - minCz + 1
        val isEmpty: Boolean get() = chunkCount == 0
    }

    /**
     * 扫描指定维度所有子区块，求包围盒。
     *
     * 用前缀 `[00 00 00 00][00 00 00 00][2f][dim]` 不够 —— 坐标不固定。
     * 所以直接全库遍历，只挑 `key[8] == 47 && key[9] == dim`。
     *
     * @param dim 0 主世界 / 1 地狱 / 2 末地
     */
    fun scan(dim: Int = 0): Bounds {
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minZ = Int.MAX_VALUE
        var maxZ = Int.MIN_VALUE
        var count = 0

        try {
            db.iterator().use { it ->
                it.seekToFirst()
                while (it.isValid()) {
                    val key = it.key
                    if (key != null) {
                        // 键格式见 ChunkReader.chunkKey：
                        //   dim == 0 : [x][z][type][y]     10 字节
                        //   dim != 0 : [x][z][dim:i32][type][y]  14 字节
                        val typeOffset = if (dim == 0) 8 else 12
                        val subChunkLen = if (dim == 0) 10 else 14
                        if (key.size == subChunkLen &&
                            (key[typeOffset].toInt() and 0xFF) == 47
                        ) {
                            val buf = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN)
                            val x = buf.getInt(0)
                            val z = buf.getInt(4)
                            val keyDim = if (dim == 0) 0 else buf.getInt(8)
                            if (keyDim == dim &&
                                x in -SANE_LIMIT..SANE_LIMIT &&
                                z in -SANE_LIMIT..SANE_LIMIT
                            ) {
                                if (x < minX) minX = x
                                if (x > maxX) maxX = x
                                if (z < minZ) minZ = z
                                if (z > maxZ) maxZ = z
                                count++
                            }
                        }
                    }
                    it.next()
                }
            }
        } catch (_: Exception) {
        }

        return if (count == 0) {
            Bounds(0, 0, 0, 0, 0)
        } else {
            Bounds(minX, maxX, minZ, maxZ, count)
        }
    }
}
