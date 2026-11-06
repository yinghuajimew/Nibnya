package yhjmew.nibnya.map

import android.content.Context
import android.graphics.Color
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 方块与群系配色表。
 *
 * 数据来自 BedrockMap 的 bedrock-level data 目录下两个 JSON（2026-11-02 比对后选用：
 * 它比 Blocktopograph 的 `KnownBlockRepr` 多 170 个新方块，含 1.21.6 的
 * 朱砂 / 硫磺系列，而后者独有的 17 项基本是旧数字 ID 残留）。
 *
 * ## 两种表
 * - **单色**：一个方块名对应一种颜色，占绝大多数。
 * - **多态**：同一个方块名按状态（`tag`）分色，如羊毛 16 色、木头按树种。
 *
 * ## 为什么不能只靠名字猜染色
 * `bedrock-level/src/color.cpp` 用 `name.find("grass")` 判断是否需要群系染色，
 * 这会把 `grass_path`、`tallgrass`、`sea_grass`、`short_grass` 全算进去。
 * 它还特意排除了 `cherry`（樱花树叶不染色），说明作者已经踩过坑。
 *
 * 这里改成**显式名单优先 + 子串兜底**：
 * 先查 [Tint] 的精确表，查不到再走类型后缀判断。
 */
class BlockColorTable private constructor(
    private val single: Map<String, Int>,
    private val multi: Map<String, Map<String, Int>>,
    private val biomeById: Map<Int, BiomeColors>,
) {

    /** 群系染色档位。 */
    enum class Tint { NONE, WATER, LEAVES, GRASS }

    /** 一个群系的三档染色 + 代表色。 */
    class BiomeColors(val rgb: Int, val grass: Int, val leaves: Int, val water: Int)

    // ══════════════════════════════════════════════════════
    // 方块颜色
    // ══════════════════════════════════════════════════════

    /**
     * 取方块最终颜色（已完成群系染色）。
     *
     * ## 为什么返回 Int 而不是 RGB 三元组
     * 直接给 Canvas 用。染色后强制不透明，理由见 [parseColor]。
     *
     * ## 染色公式
     * 表里的 `grass_block` 是 `#939393`、`water` 是 `#a3a3a3` —— **灰度底图**，
     * 必须乘上群系色才有颜色。公式取自 `bedrock-level/src/color.cpp:blend_with_biome`：
     *
     * ```
     * out.c = gray.c / 255 * biomeTint.c
     * ```
     *
     * 不染色的方块直接用原色。
     *
     * @param name    完整方块名（含 `minecraft:`）
     * @param tags    状态候选串，用于多态表匹配
     * @param biomeId 该列的生物群系 id；-1 表示未知，用默认群系
     */
    fun colorOf(name: String, tags: List<String> = emptyList(), biomeId: Int = -1): Int {
        val base = baseColorOf(name, tags)
        val tint = tintOf(name, tags)
        if (tint == Tint.NONE) return base
        return blend(base, tintColor(biomeId, tint))
    }

    /** 未染色的原始色（灰度底图）。调试与单测用。 */
    fun baseColorOf(name: String, tags: List<String> = emptyList()): Int {
        single[name]?.let { return it }

        val variants = multi[name]
        if (!variants.isNullOrEmpty()) {
            for (tag in tags) {
                if (tag.isEmpty()) continue
                for ((key, color) in variants) {
                    if (tag.contains(key)) return color
                }
            }
            return variants.values.first()
        }
        return FALLBACK
    }

    /** 灰度 × 群系色，逐通道相乘后除以 255。 */
    private fun blend(gray: Int, tint: Int): Int {
        val r = ((gray shr 16 and 0xFF) * (tint shr 16 and 0xFF)) / 255
        val g = ((gray shr 8 and 0xFF) * (tint shr 8 and 0xFF)) / 255
        val b = ((gray and 0xFF) * (tint and 0xFF)) / 255
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * 该方块是否需要按生物群系染色。
     *
     * 判定顺序：精确名单 → 状态名兜底 → 名字后缀兜底。
     */
    fun tintOf(name: String, tags: List<String> = emptyList()): Tint {
        // ① 精确名单：这些名字里的子串会误伤，必须单列
        EXPLICIT_TINT[name]?.let { return it }

        // ② 多态表的状态名：`leaves` 的 `oak/spruce/...` 本身不带 leaf 字样，
        //    但方块名带；反过来 `grass_block` 的状态只有 `grass_top`。
        val lower = name.substringAfter(':', name).lowercase()

        // ③ 名字兜底。顺序按「越具体越先判」，
        //    并且先排掉已知不该染色的例外。
        if (lower in NO_TINT) return Tint.NONE

        return when {
            lower.contains("water") -> Tint.WATER
            lower.contains("leaves") || lower.contains("leaf_litter") -> Tint.LEAVES
            lower.contains("vine") -> Tint.LEAVES
            lower == "grass_block" || lower == "grass" -> Tint.GRASS
            // tallgrass / short_grass / seagrass 是植物，跟草方块一样吃群系草色
            lower == "tall_grass" || lower == "short_grass" -> Tint.GRASS
            lower == "seagrass" || lower == "sea_grass" -> Tint.GRASS
            else -> Tint.NONE
        }
    }

    /** 取群系染色色值；群系未知时返回默认群系。 */
    fun tintColor(biomeId: Int, tint: Tint): Int {
        val b = biomeById[biomeId] ?: biomeById[DEFAULT_BIOME] ?: return Color.WHITE
        return when (tint) {
            Tint.WATER -> b.water
            Tint.LEAVES -> b.leaves
            Tint.GRASS -> b.grass
            Tint.NONE -> Color.WHITE
        }
    }

    /** 群系代表色（群系图用）。 */
    fun biomeColor(biomeId: Int): Int =
        (biomeById[biomeId] ?: biomeById[DEFAULT_BIOME])?.rgb ?: FALLBACK

    val biomeCount: Int get() = biomeById.size
    val blockCount: Int get() = single.size + multi.size

    companion object {
        private const val DEFAULT_BIOME = 1  // plains

        /** 未知方块：中性灰，比纯黑好看，也更容易看出「这里没匹配上」。 */
        val FALLBACK = Color.rgb(0x88, 0x88, 0x88)

        /**
         * 精确名单。用完整方块名，不走子串。
         *
         * 只放「子串判断会出错」的项；常规的树叶/水靠后缀判断即可。
         */
        private val EXPLICIT_TINT = mapOf(
            // 草方块本体
            "minecraft:grass_block" to Tint.GRASS,
            "minecraft:grass" to Tint.GRASS,
            "minecraft:grass_path" to Tint.NONE,   // 子串含 grass 但不染色
            "minecraft:dirt_with_roots" to Tint.NONE,

            // 植物：这些虽然含 grass，但确实是植被，吃草色
            "minecraft:tall_grass" to Tint.GRASS,
            "minecraft:short_grass" to Tint.GRASS,
            "minecraft:double_plant" to Tint.GRASS,
            "minecraft:fern" to Tint.GRASS,
            "minecraft:large_fern" to Tint.GRASS,

            // 水下植物
            "minecraft:seagrass" to Tint.GRASS,
            "minecraft:kelp" to Tint.GRASS,

            // 樱花：bedrock-level 特意排除，樱花树叶是固定的粉色
            "minecraft:cherry_leaves" to Tint.NONE,
            "minecraft:pale_oak_leaves" to Tint.NONE,

            // 水
            "minecraft:water" to Tint.WATER,
            "minecraft:flowing_water" to Tint.WATER,
            "minecraft:water_cauldron" to Tint.WATER,
        )

        /** 明确不染色的（野兽先辈式兜底，避免后缀误判）。 */
        private val NO_TINT = setOf(
            "grass_path", "dirt_with_roots", "grass_block_side",
        )

        /**
         * 加载。首次调用约 150 ms（130 KB JSON），之后由 [Cache] 复用。
         */
        fun load(ctx: Context): BlockColorTable {
            val single = HashMap<String, Int>(2048)
            val multi = HashMap<String, HashMap<String, Int>>(256)

            readAsset(ctx, "colors/block_color.json")?.let { root ->
                for ((name, value) in root.entrySet()) {
                    val variants = value.asJsonObject
                    if (variants.size() == 1) {
                        single[name] = parseColor(variants.entrySet().first().value.asString)
                    } else if (variants.size() > 1) {
                        val m = HashMap<String, Int>(variants.size())
                        for ((tag, c) in variants.entrySet()) {
                            m[tag] = parseColor(c.asString)
                        }
                        multi[name] = m
                    }
                }
            }

            val biomes = HashMap<Int, BiomeColors>(128)
            readAsset(ctx, "colors/biome_color.json")?.let { root ->
                for ((_, value) in root.entrySet()) {
                    val o = value.asJsonObject
                    val id = o.get("id")?.asInt ?: continue
                    biomes[id] = BiomeColors(
                        rgb = parseColor(o.get("rgb")?.asString),
                        grass = parseColor(o.get("grass")?.asString),
                        leaves = parseColor(o.get("leaves")?.asString),
                        water = parseColor(o.get("water")?.asString),
                    )
                }
            }

            return BlockColorTable(single, multi, biomes)
        }

        private fun readAsset(ctx: Context, path: String): JsonObject? = try {
            ctx.assets.open(path).use { input ->
                val text = BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
                JsonParser.parseString(text).asJsonObject
            }
        } catch (_: Exception) {
            null
        }

        /**
         * 解析 `#rrggbb` 或 `#rrggbbaa`。
         *
         * 表里的 alpha 有的是 `ff`、有的是半透明（如水 `f0`），
         * 但地图是俯视不透明渲染，统一强制不透明，
         * 否则水下面的地形会透出来显得脏。
         */
        private fun parseColor(text: String?): Int {
            if (text.isNullOrBlank()) return FALLBACK
            val hex = text.removePrefix("#")
            if (hex.length < 6) return FALLBACK
            val rgb = hex.substring(0, 6).toIntOrNull(16) ?: return FALLBACK
            return (0xFF shl 24) or rgb
        }

        /** 进程内单例，避免每次开图都重新解析 130 KB JSON。 */
        private var cached: BlockColorTable? = null

        fun get(ctx: Context): BlockColorTable =
            cached ?: load(ctx.applicationContext).also { cached = it }
    }
}
