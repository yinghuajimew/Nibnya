package yhjmew.nibnya.map

import com.litl.leveldb.DB
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 区块读取器（只读）。
 *
 * 只负责把 LevelDB 里的字节解成「某列的地表方块名」需要的原始结构，
 * 不做颜色、不做渲染 —— 那些在 [TerrainMapBuilder]。
 *
 * ## 键格式（来自 bedrock-level/docs/BedrockLevelFormat.md）
 * ```
 * [4 字节 X][4 字节 Z][1 字节 类型][1 字节 维度]  [+1 字节 Y 索引（仅 SubChunkTerrain）]
 * ```
 * 全部 **小端**。
 *
 * ## 子区块版本
 * | 版本 | 头部 |
 * |---|---|
 * | 1 | `[版本]` 只有一层，无层数、无 Y 索引 |
 * | 8 | `[版本][层数]` |
 * | 9 | `[版本][层数][Y 索引]` |
 *
 * ## 层数据
 * ```
 * [1 字节头]  bit0 = 类型(0=调色板), bit1..7 = 每方块位数 bits
 * bits == 0 : 均匀块，全层同一方块，调色板 1 项
 * bits >  0 : [ceil(4096 / (32/bits)) 个字 × 4 字节 位打包索引][4 字节 调色板长度][N 个 NBT Compound]
 * ```
 * 索引布局：`idx = y + z*16 + x*256`（不是直觉的 x 最快）。
 *
 * 参照 `bedrock-level/src/{sub_chunk,palette,data_3d}.cpp`。
 */
class ChunkReader(private val db: DB) {

    companion object {
        // 区块键类型
        const val KEY_DATA_3D = 43
        const val KEY_VERSION_NEW = 44
        const val KEY_DATA_2D = 45
        const val KEY_SUBCHUNK_TERRAIN = 47
        const val KEY_VERSION_OLD = 118

        /** 一个子区块 16³。 */
        const val SUB_CHUNK_VOLUME = 4096

        /**
         * 主世界 Y 范围（1.18+）。
         * 地狱 0..127、末地 0..255，所以取最大范围来扫不会漏。
         */
        const val MIN_Y = -64
        const val MAX_Y = 319
    }

    // ══════════════════════════════════════════════════════
    // 键构造
    // ══════════════════════════════════════════════════════

    /**
     * 区块键的**前缀**（不含尾部）。
     *
     * ## 为什么是前缀而不是完整键
     * 实测真实存档（`我的世界.mcworld`）发现，区块键在 `[x][z][type][dim]`
     * 之后**还跟着不定长的尾部**：
     *
     * ```
     * type=43/44/49/54/63…  长度 17  = 10 + 7 字节尾部
     * type=47 (子区块)      长度 18  = 10 + 1 字节 Y 索引 + 7 字节尾部
     * ```
     *
     * 尾部每个区块都不同（同一 (x,z) 相邻维度的值递增），
     * 无法预先算出，所以**不能拼定长键去 get**，只能按前缀扫。
     */
    private fun keyPrefix(x: Int, z: Int, type: Int, dim: Int, yIndex: Int? = null): ByteArray {
        val size = if (yIndex == null) 10 else 11
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(x)
        buf.putInt(z)
        buf.put(type.toByte())
        buf.put(dim.toByte())
        if (yIndex != null) buf.put(yIndex.toByte())
        return buf.array()
    }

    /**
     * 按前缀精确查找**一条**记录。
     *
     * 键 = 前缀 + 尾部，尾部长度不定，所以用 seek 定位后用 startsWith 匹配。
     * 同一前缀实测唯一（13084 个键无重复），所以取第一条即可。
     */
    private fun getByPrefix(prefix: ByteArray): ByteArray? {
        return try {
            db.iterator().use { it ->
                it.seek(prefix)
                if (!it.isValid()) return null
                val key = it.key ?: return null
                if (!startsWith(key, prefix)) return null
                it.value
            }
        } catch (_: Exception) {
            null
        }
    }

    // ══════════════════════════════════════════════════════
    // 读取
    // ══════════════════════════════════════════════════════

    /**
     * 读一个区块的地形。
     *
     * @return 解析失败或区块不存在时返回 null（调用方视为空区块）
     */
    fun readChunk(cx: Int, cz: Int, dim: Int = 0): ParsedChunk? {
        val subChunks = HashMap<Int, SubChunk>(24)

        // 一次前缀扫描拿全部子区块：前缀只到 dim，
        // 后面每个 yIdx 各自的尾部都在同一条连续区间里。
        scanSubChunks(cx, cz, dim, subChunks)

        val biomes = readBiomes(cx, cz, dim)

        if (subChunks.isEmpty() && biomes == null) return null
        return ParsedChunk(cx, cz, dim, subChunks, biomes)
    }

    /**
     * 扫出该区块所有子区块。
     *
     * 前缀 = `[x][z][47][dim]`（10 字节）。真实键在这之后是
     * `[yIdx 1 字节][7 字节尾部]`，所以按前缀连续读，
     * 从第 11 字节取 yIdx。
     */
    private fun scanSubChunks(cx: Int, cz: Int, dim: Int, out: HashMap<Int, SubChunk>) {
        val prefix = keyPrefix(cx, cz, KEY_SUBCHUNK_TERRAIN, dim)
        try {
            db.iterator().use { cursor ->
                cursor.seek(prefix)
                while (cursor.isValid()) {
                    val key = cursor.key ?: break
                    if (!startsWith(key, prefix)) break
                    val value = cursor.value
                    if (value != null) {
                        // ⚠️ 键里 prefix 之后的那个字节**不是** Y 索引。
                        // 实测 2632 个维度 0 子区块，键第 11 字节恒为 1，
                        // 而数据头里的 yIdx 才是真实值（0..19）。
                        // Y 索引只从数据头取，键只用来定位。
                        parseSubChunk(value)?.let { (y, sub) ->
                            out[y] = sub
                        }
                    }
                    cursor.next()
                }
            }
        } catch (_: Exception) {
        }
    }

    /** 字节数组前缀比较。 */
    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) {
            if (data[i] != prefix[i]) return false
        }
        return true
    }

    /**
     * 读群系数据。
     *
     * 1.18+ 用 `Data3D`(0x2b)，每 16³ 子区块一包；
     * 更早用 `Data2D`(0x2d)，每区块 16×16 一包。
     * 这里统一转成「该列顶部群系」的 256 长数组，够 TerrainMapBuilder 用。
     */
    private fun readBiomes(cx: Int, cz: Int, dim: Int): BiomeColumn? {
        getByPrefix(keyPrefix(cx, cz, KEY_DATA_3D, dim))?.let { raw ->
            parseBiome3D(raw)?.let { return it }
        }
        getByPrefix(keyPrefix(cx, cz, KEY_DATA_2D, dim))?.let { raw ->
            parseBiome2D(raw)?.let { return it }
        }
        return null
    }

    // ══════════════════════════════════════════════════════
    // 子区块解析
    // ══════════════════════════════════════════════════════

    /**
     * @return (Y 索引, 子区块)；不支持的版本返回 null
     */
    private fun parseSubChunk(raw: ByteArray): Pair<Int, SubChunk>? {
        if (raw.isEmpty()) return null
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

        val version = buf.get().toInt() and 0xFF
        var layersNum: Int
        var yIndex: Int

        when (version) {
            1 -> {
                // v1：只有一层，没有层数字节，也没有 Y 索引
                layersNum = 1
                yIndex = 0
            }
            8 -> {
                if (buf.remaining() < 1) return null
                layersNum = buf.get().toInt() and 0xFF
                yIndex = 0
            }
            9 -> {
                if (buf.remaining() < 2) return null
                layersNum = buf.get().toInt() and 0xFF
                yIndex = buf.get().toInt() // 有符号，-4..19
            }
            else -> return null  // 不认识的版本，跳过
        }
        if (layersNum <= 0) return null

        // 读第 0 层；若存在第 1 层（水/植物覆盖层）也一并保留 ——
        // 地表扫描时第 0 层是空气才回看第 1 层，这样海洋才会显示成蓝色水面，
        // 而不是海底的沙石。
        val primary = parseLayer(buf) ?: return null
        var secondary: BlockLayer? = null
        if (layersNum >= 2 && buf.remaining() > 0) {
            secondary = parseLayer(buf)
        }
        return yIndex to SubChunk(yIndex, primary, secondary)
    }

    private fun parseLayer(buf: ByteBuffer): BlockLayer? {
        if (buf.remaining() < 1) return null
        val header = buf.get().toInt() and 0xFF
        val bits = header ushr 1

        val indices: IntArray
        val paletteLen: Int

        if (bits == 0) {
            // 均匀层：4096 个方块全是调色板第 0 项
            indices = IntArray(SUB_CHUNK_VOLUME)
            paletteLen = 1
        } else {
            if (bits > 32) return null
            // 异常位数会导致整数溢出，先挡掉
            if (bits > 16) return null

            val perWord = 32 / bits
            if (perWord <= 0) return null
            var wordCount = SUB_CHUNK_VOLUME / perWord
            if (SUB_CHUNK_VOLUME % perWord != 0) wordCount++

            val needBytes = wordCount * 4 + 4
            if (buf.remaining() < needBytes) return null

            indices = IntArray(SUB_CHUNK_VOLUME)
            val mask = if (bits == 32) -1 else (1 shl bits) - 1
            var pos = 0
            repeat(wordCount) {
                if (buf.remaining() < 4) return null
                val word = buf.getInt().toLong() and 0xFFFFFFFFL
                // 关键：槽位序号用 `pos % perWord` 而不是每个 word 内从 0 数。
                // bits=3/5/6 时 wordCount*perWord 会超出 4096（如 4100），
                // 多出的槽位也要吃掉 pos，否则后续 word 的错位会累积。
                var b = 0
                while (b < perWord) {
                    if (pos < SUB_CHUNK_VOLUME) {
                        indices[pos] = ((word ushr ((pos % perWord) * bits)) and mask.toLong()).toInt()
                    }
                    pos++
                    b++
                }
            }
            if (buf.remaining() < 4) return null
            paletteLen = buf.getInt()
            if (paletteLen <= 0 || paletteLen > 4096) return null
        }

        // 调色板：paletteLen 个连续 NBT Compound
        val palette = ArrayList<PaletteEntry>(paletteLen)
        for (i in 0 until paletteLen) {
            val entry = readPaletteEntry(buf) ?: return null
            palette.add(entry)
        }

        return BlockLayer(indices, palette)
    }

    /**
     * 解析一个调色板条目。
     *
     * 格式：`[0x0A][名字长度 u16][名字][compound 内容]`，
     * 与 `BedrockParser.parseBytes` 期望的根格式一致，
     * 所以直接把「从当前位置到本条结束」的字节切出来交给它。
     */
    private fun readPaletteEntry(buf: ByteBuffer): PaletteEntry? {
        if (buf.remaining() < 3) return null
        val start = buf.position()

        val type = buf.get().toInt()
        if (type != 10) return null  // 必须是 Compound
        val nameLen = buf.short.toInt() and 0xFFFF
        if (buf.remaining() < nameLen) return null
        buf.position(buf.position() + nameLen)

        // 扫描 compound 找结束位置：需要跳过所有嵌套值
        val end = scanCompoundEnd(buf, buf.position()) ?: return null
        val length = end - start

        val slice = ByteArray(length)
        val mark = buf.position()
        buf.position(start)
        buf.get(slice)
        buf.position(mark)

        val json = try {
            yhjmew.nibnya.nbt.BedrockParser.parseBytes(slice)
        } catch (_: Exception) {
            null
        } ?: return null

        val name = json.get("name")?.asString ?: return null

        // 状态值：取所有字符串/数字字段，供多态色表匹配
        val tags = ArrayList<String>(4)
        json.getAsJsonObject("states")?.let { states ->
            for ((k, v) in states.entrySet()) {
                val s = if (v.isJsonPrimitive) v.asString else ""
                if (s.isNotEmpty()) tags.add(s)
                tags.add(k)
            }
        }
        return PaletteEntry(name, tags)
    }

    /**
     * 从 `pos` 开始（compound 内容起点）扫描到匹配的 END(0x00)。
     * @return END 之后的位置；数据不足返回 null
     */
    private fun scanCompoundEnd(buf: ByteBuffer, pos: Int): Int? {
        var p = pos
        fun u8(at: Int): Int = buf.get(at).toInt() and 0xFF
        fun require(n: Int): Boolean = n <= buf.limit()

        try {
            while (true) {
                if (!require(p + 1)) return null
                val tagType = u8(p)
                p++
                if (tagType == 0) return p  // END

                if (!require(p + 2)) return null
                val nameLen = ((u8(p)) or (u8(p + 1) shl 8))
                p += 2 + nameLen

                val skip = skipValue(buf, p, tagType) ?: return null
                p = skip
            }
        } catch (_: IndexOutOfBoundsException) {
            return null
        }
    }

    /** 跳过一个 tag 的值，返回之后的位置。 */
    private fun skipValue(buf: ByteBuffer, start: Int, type: Int): Int? {
        fun u8(at: Int): Int = buf.get(at).toInt() and 0xFF
        fun i32(at: Int): Int =
            (u8(at)) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16) or (u8(at + 3) shl 24)

        return try {
            when (type) {
                1 -> start + 1                      // byte
                2 -> start + 2                      // short
                3, 5 -> start + 4                   // int / float
                4, 6 -> start + 8                   // long / double
                7 -> start + 4 + i32(start)         // byte array
                11 -> start + 4 + i32(start) * 4    // int array
                12 -> start + 4 + i32(start) * 8    // long array
                8 -> {                              // string
                    val len = (u8(start)) or (u8(start + 1) shl 8)
                    start + 2 + len
                }
                9 -> {                              // list
                    val elem = u8(start)
                    val len = i32(start + 1)
                    var p = start + 5
                    if (elem == 0 || len <= 0) {
                        p
                    } else {
                        repeat(len) {
                            p = skipValue(buf, p, elem) ?: return null
                        }
                        p
                    }
                }
                10 -> scanCompoundEnd(buf, start)   // compound
                else -> null
            }
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    // ══════════════════════════════════════════════════════
    // 群系解析
    // ══════════════════════════════════════════════════════

    /** Data3D：512 字节高度图 + 若干 16³ 群系子区块，逐个累积成整列。 */
    private fun parseBiome3D(raw: ByteArray): BiomeColumn? {
        if (raw.size < 512) return null

        // 512 字节高度图（256 × int16）之后才是群系子区块。
        var offset = 512

        // 整列群系栈：每个子区块 16 层。用「当前最高层」直接覆盖，
        // 因为子区块是按 Y 从低到高排列的，后写的自然更高。
        val top = IntArray(256) { -1 }

        while (offset < raw.size) {
            val consumed = parseBiomeSubChunk(raw, offset, top) ?: break
            if (consumed <= 0) break
            offset += consumed
        }
        return if (top.any { it >= 0 }) BiomeColumn(top) else null
    }

    /**
     * 解析一个 16³ 群系子区块，把该子区块内每列最高的群系写进 [top]。
     *
     * 子区块按 Y 从低到高排列，所以调用方按顺序累积即可，
     * 后写的自然覆盖先写的，最终得到整列最高层群系。
     *
     * @return 消耗的字节数；格式不对返回 null
     */
    private fun parseBiomeSubChunk(raw: ByteArray, start: Int, top: IntArray): Int? {
        var p = start
        val head = raw[p].toInt() and 0xFF
        p++

        // 0xFF = 该子区块全是 none
        if (head == 0xFF) return 1

        val bits = head ushr 1
        val indices = IntArray(SUB_CHUNK_VOLUME)
        var paletteLen = 1

        if (bits != 0) {
            if (bits > 32) return null
            val perWord = 32 / bits
            if (perWord <= 0) return null
            var wordCount = SUB_CHUNK_VOLUME / perWord
            if (SUB_CHUNK_VOLUME % perWord != 0) wordCount++
            if (p + wordCount * 4 + 4 > raw.size) return null

            val mask = (1L shl bits) - 1
            var pos = 0
            repeat(wordCount) {
                val word = (raw[p].toLong() and 0xFF) or
                    ((raw[p + 1].toLong() and 0xFF) shl 8) or
                    ((raw[p + 2].toLong() and 0xFF) shl 16) or
                    ((raw[p + 3].toLong() and 0xFF) shl 24)
                p += 4
                // 同方块子区块：用 pos % perWord，溢出槽位也要吃掉 pos
                var b = 0
                while (b < perWord) {
                    if (pos < SUB_CHUNK_VOLUME) {
                        indices[pos] = ((word ushr ((pos % perWord) * bits)) and mask).toInt()
                    }
                    pos++
                    b++
                }
            }
            paletteLen = (raw[p].toInt() and 0xFF) or
                ((raw[p + 1].toInt() and 0xFF) shl 8) or
                ((raw[p + 2].toInt() and 0xFF) shl 16) or
                ((raw[p + 3].toInt() and 0xFF) shl 24)
            p += 4
        }
        if (paletteLen <= 0 || paletteLen > 4096) return null

        val palette = IntArray(paletteLen)
        if (p + paletteLen * 4 > raw.size) return null
        for (i in 0 until paletteLen) {
            palette[i] = (raw[p].toInt() and 0xFF) or
                ((raw[p + 1].toInt() and 0xFF) shl 8) or
                ((raw[p + 2].toInt() and 0xFF) shl 16) or
                ((raw[p + 3].toInt() and 0xFF) shl 24)
            p += 4
        }

        // 取每列「该子区块内最高」的非 none 群系。
        // 子区块从低到高排列，所以后写的覆盖先写的，最终就是整列最高层。
        // 列顺序是 x*16+z，见 data_3d.cpp:134 `layer[x * 16 + z]`。
        for (x in 0 until 16) {
            for (z in 0 until 16) {
                var found = -1
                for (y in 15 downTo 0) {
                    val idx = y + z * 16 + x * 256
                    val bi = indices[idx]
                    if (bi in palette.indices) {
                        val id = palette[bi]
                        if (id > 0) {
                            found = id
                            break
                        }
                    }
                }
                if (found > 0) {
                    top[x * 16 + z] = found
                }
            }
        }
        return p - start
    }

    /** Data2D：`[512 字节高度图][256 字节群系，每列 1 字节]`，共 768 字节。 */
    private fun parseBiome2D(raw: ByteArray): BiomeColumn? {
        if (raw.size < 768) return null
        val top = IntArray(256)
        for (x in 0 until 16) {
            for (z in 0 until 16) {
                // data_3d.cpp:198 -> data[512 + x + 16 * z]
                top[x * 16 + z] = raw[512 + x + 16 * z].toInt() and 0xFF
            }
        }
        return BiomeColumn(top)
    }
}

/** 调色板条目：方块名 + 状态候选串。 */
class PaletteEntry(val name: String, val tags: List<String>)

/** 一个子区块的第 0 层。 */
class BlockLayer(val indices: IntArray, val palette: List<PaletteEntry>) {
    /** 按 `x + z*16 + y*256` 之外的顺序取值：注意这里是 `y + z*16 + x*256`。 */
    fun entryAt(x: Int, y: Int, z: Int): PaletteEntry? {
        val idx = y + z * 16 + x * 256
        if (idx !in indices.indices) return null
        val pi = indices[idx]
        return palette.getOrNull(pi)
    }
}

class SubChunk(
    val yIndex: Int,
    /** 第 0 层：主体方块。 */
    val layer: BlockLayer,
    /** 第 1 层：水/植物等覆盖层，可能为 null。 */
    val overlay: BlockLayer? = null,
)

/** 每列顶部群系 id，索引 `x*16 + z`（与存档里 Layer[x*16+z] 一致）。 */
class BiomeColumn(val ids: IntArray) {
    fun at(x: Int, z: Int): Int = ids.getOrElse(x * 16 + z) { -1 }
}

/** 解析后的区块。 */
class ParsedChunk(
    val cx: Int,
    val cz: Int,
    val dim: Int,
    val subChunks: Map<Int, SubChunk>,
    val biomes: BiomeColumn?,
) {
    /** 该区块是否有地形数据。 */
    val hasTerrain: Boolean get() = subChunks.isNotEmpty()

    /** 子区块的 Y 索引范围（含端点）。 */
    val yIndexRange: IntRange?
        get() = if (subChunks.isEmpty()) null else {
            val keys = subChunks.keys
            keys.min()..keys.max()
        }
}
