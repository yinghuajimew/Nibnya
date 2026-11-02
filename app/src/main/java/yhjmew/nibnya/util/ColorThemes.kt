package yhjmew.nibnya.util

import android.graphics.Color
import androidx.core.graphics.ColorUtils

/**
 * 内置主题配色（2026-11-02）。
 *
 * 用户点一下就套用整套颜色，不需要逐项调 —— 解决「想改但懒得一项项配」的场景。
 * 套用后仍然可以在下面逐项微调（主题只是把 24 个色槽一次性填好）。
 *
 * ## 设计方式
 *
 * 每套主题只定义**一个主色 + 一个辅色**，其余由 [build] 按固定规则派生：
 *
 * | 位置 | 取色规则 |
 * |---|---|
 * | 主操作按钮（联机玩家/复制/搜索/顶栏/设置徽章） | 主色 |
 * | 次操作按钮（地图） | 辅色 |
 * | 危险操作（保存修改） | 固定红，不随主题变 —— 危险语义要稳定 |
 * | 中性操作（村庄/高级/解析/清理缓存/切换路径） | 主色降饱和的灰调 |
 * | 成功类（恢复备份/侧边栏/树形/关于徽章/头部渐变） | 主色偏绿或按主题给 |
 * | 装饰（编辑玩家/全局数据） | 主色偏紫，或主题指定 |
 *
 * 派生色一律用 HSL 调亮度/饱和度，保证同一主题内所有颜色和谐，
 * 而不是各挑各的导致「花里胡哨」。
 */
object ColorThemes {

    /**
     * 一套主题。
     *
     * @param primary   主色：最显眼的那一类按钮
     * @param secondary 辅色：和主色搭配的第二色
     * @param accent    强调色：用于「编辑玩家」「全局数据」这类装饰性按钮
     * @param success   成功/绿色语义（侧边栏、树形、备份、关于）
     * @param header    弹窗头部渐变起点；终点由它压暗得到
     */
    data class Theme(
        val id: String,
        val nameZh: String,
        val nameEn: String,
        val primary: String,
        val secondary: String,
        val accent: String,
        val success: String,
        val header: String,
    ) {
        /** 按当前系统语言取名字，不走字符串资源 —— 24 套 × 2 语言 = 48 条太啰嗦 */
        fun name(lang: String) = if (lang.startsWith("zh")) nameZh else nameEn

        /** 预览用：主色 */
        val primaryInt: Int get() = android.graphics.Color.parseColor(primary)
        val secondaryInt: Int get() = android.graphics.Color.parseColor(secondary)
        val accentInt: Int get() = android.graphics.Color.parseColor(accent)
        val successInt: Int get() = android.graphics.Color.parseColor(success)
    }

    // ── 主题清单 ────────────────────────────────────────────

    val THEMES = listOf(
        // ══ 经典 ══
        Theme("classic_blue",   "经典蓝",   "Classic Blue",
            "#2196F3", "#1976D2", "#673AB7", "#4CAF50", "#4CAF50"),
        Theme("deep_ocean",     "深海蓝",   "Deep Ocean",
            "#1565C0", "#1E88E5", "#4527A0", "#00897B", "#1565C0"),
        Theme("sky_blue",       "晴空蓝",   "Sky Blue",
            "#039BE5", "#4FC3F7", "#5C6BC0", "#4CAF50", "#039BE5"),

        // ══ 粉 / 红 ══
        Theme("sakura_pink",    "樱花粉",   "Sakura Pink",
            "#E91E63", "#F06292", "#AD1457", "#EC407A", "#F06292"),
        Theme("rose_red",       "玫瑰红",   "Rose Red",
            "#E53935", "#EF5350", "#C62828", "#8BC34A", "#E53935"),
        Theme("peach",          "蜜桃",     "Peach",
            "#FF7043", "#FFAB91", "#D84315", "#8BC34A", "#FF7043"),
        Theme("wine",           "酒红",     "Wine",
            "#8E24AA", "#BA68C8", "#6A1B9A", "#AB47BC", "#8E24AA"),

        // ══ 绿 ══
        Theme("mint_green",     "薄荷绿",   "Mint Green",
            "#26A69A", "#4DB6AC", "#00897B", "#66BB6A", "#26A69A"),
        Theme("forest_green",   "森林绿",   "Forest Green",
            "#2E7D32", "#43A047", "#6D4C41", "#7CB342", "#2E7D32"),
        Theme("matcha",         "抹茶",     "Matcha",
            "#7CB342", "#9CCC65", "#558B2F", "#8BC34A", "#7CB342"),
        Theme("emerald",        "翡翠",     "Emerald",
            "#00897B", "#26A69A", "#004D40", "#43A047", "#00897B"),

        // ══ 紫 ══
        Theme("royal_purple",   "皇家紫",   "Royal Purple",
            "#7E57C2", "#9575CD", "#5E35B1", "#26A69A", "#7E57C2"),
        Theme("lavender",       "薰衣草",   "Lavender",
            "#9575CD", "#B39DDB", "#673AB7", "#4DB6AC", "#9575CD"),

        // ══ 橙 / 黄 ══
        Theme("sunset_orange",  "落日橙",   "Sunset Orange",
            "#FB8C00", "#FFA726", "#E65100", "#8BC34A", "#FB8C00"),
        Theme("amber",          "琥珀",     "Amber",
            "#FFB300", "#FFCA28", "#FF8F00", "#4CAF50", "#FFB300"),
        Theme("choco",          "巧克力",   "Chocolate",
            "#6D4C41", "#8D6E63", "#4E342E", "#795548", "#6D4C41"),

        // ══ 中性 ══
        Theme("ink_gray",       "墨灰",     "Ink Gray",
            "#546E7A", "#607D8B", "#455A64", "#78909C", "#546E7A"),
        Theme("mono",           "极简黑白", "Mono",
            "#424242", "#616161", "#212121", "#757575", "#424242"),
        Theme("slate",          "石板灰",   "Slate",
            "#546E7A", "#78909C", "#37474F", "#607D8B", "#546E7A"),

        // ══ 特殊 ══
        Theme("cyber_neon",     "赛博霓虹", "Cyber Neon",
            "#00E5FF", "#D500F9", "#651FFF", "#00E676", "#00B8D4"),
        Theme("rainbow_soft",   "柔和彩虹", "Soft Rainbow",
            "#42A5F5", "#FFA726", "#AB47BC", "#66BB6A", "#26C6DA"),
        Theme("night_owl",      "夜猫子",   "Night Owl",
            "#3949AB", "#5C6BC0", "#283593", "#26A69A", "#3949AB"),
        Theme("paper",          "纸张",     "Paper",
            "#8D6E63", "#A1887F", "#6D4C41", "#9CCC65", "#8D6E63"),
    )

    /**
     * 把主题展开成「色槽 key -> 颜色」的完整映射。
     *
     * 24 个色槽一个不漏，套用后观感完整统一，不会出现「改了一半还是旧色」。
     */
    fun build(theme: Theme): Map<String, Int> {
        val p = theme.primary
        val s = theme.secondary
        val a = theme.accent
        val ok = theme.success
        val h = theme.header

        // 危险色固定红：保存、删除这类语义不能跟着主题乱变
        val danger = "#F44336"

        val hexMap = mapOf(
            // ── 主页功能按钮 ──
            "c_btn_player" to p,
            "c_btn_map" to s,
            "c_btn_village" to desaturate(p, 0.35f, -0.18f),   // 中性偏暗
            "c_btn_any_key" to desaturate(p, 0.45f, -0.12f),   // 中性
            "c_btn_global" to a,

            // ── 主页工具栏 ──
            "c_btn_copy" to p,
            "c_btn_save" to danger,
            "c_btn_edit_player" to a,
            "c_btn_backup" to ok,
            "c_btn_parse" to "#9E9E9E",                        // 中性，不随主题
            "c_btn_fullscreen" to darken(p, 0.12f),

            // ── 侧边栏 ──
            "c_bar_sidebar" to ok,
            "c_btn_clear_cache" to desaturate(p, 0.45f, -0.12f),
            "c_btn_switch_path" to desaturate(p, 0.45f, -0.12f),

            // ── 小图标按钮（主页）──
            "c_icon_tree" to ok,
            "c_icon_view" to s,
            "c_icon_search" to p,

            // ── 全屏 ──
            "c_bar_top" to p,
            "c_icon_tree_fs" to ok,
            "c_icon_view_fs" to s,
            "c_icon_search_fs" to darken(p, 0.10f),

            // ── 设置 / 关于弹窗 ──
            "c_header_green" to h,
            "c_header_green_dark" to darken(h, 0.18f),
            "c_badge_settings" to p,
            "c_badge_about" to ok,

            // ── 通用强调色 / 选中态 ──
            // 这些是「一类元素」的颜色，跟着主题走才能整体统一。
            // 标签选中格背景用主色压得很浅的版本，避免和文字撞色。
            "c_accent" to p,
            "c_tab_selected" to lighten(p, 0.82f),
            "c_tab_unselected" to "#666666",
            "c_nav_selected" to p,
            "c_danger" to danger,
        )
        // 统一转成 ARGB int，UI 直接用
        return hexMap.mapValues { (_, hex) -> android.graphics.Color.parseColor(hex) }
    }

    /** 十六进制 -> Int */
    fun parse(hex: String): Int = Color.parseColor(hex)

    // ── HSL 工具 ────────────────────────────────────────────
    // 用 HSL 而不是直接调 RGB，是因为亮度/饱和度调起来更符合直觉，
    // 同一主题内各色也更容易保持和谐。

    private fun desaturate(hex: String, satScale: Float, lumShift: Float): String =
        tune(hex) { h, s, l -> Triple(h, (s * satScale).coerceIn(0f, 1f), (l + lumShift).coerceIn(0f, 1f)) }

    private fun lighten(hex: String, amount: Float): String =
        tune(hex) { h, s, l -> Triple(h, s * 0.55f, (l + amount).coerceIn(0f, 1f)) }

    private fun darken(hex: String, amount: Float): String =
        tune(hex) { h, s, l -> Triple(h, s, (l - amount).coerceIn(0f, 1f)) }

    private fun tune(hex: String, f: (Float, Float, Float) -> Triple<Float, Float, Float>): String {
        val c = Color.parseColor(hex)
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(c, hsl)
        val (h, s, l) = f(hsl[0], hsl[1], hsl[2])
        return ColorScheme.toHex(ColorUtils.HSLToColor(floatArrayOf(h, s, l)))
    }
}
