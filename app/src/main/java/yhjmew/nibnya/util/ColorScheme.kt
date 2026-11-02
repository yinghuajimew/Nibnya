package yhjmew.nibnya.util

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt

/**
 * 可自定义配色（2026-11-02）。
 *
 * ## 两种模式
 *
 * - **[Mode.DEFAULT]**「默认颜色」：完全忽略用户设置，直接读 `colors.xml` 里的原值。
 *   选它 = 一键回到「改造前」的样子，包括日间/夜间各自的原值（资源有 night 变体）。
 * - **[Mode.CUSTOM]**「自定义」：用户逐项调过的颜色存在 SharedPreferences。
 *
 * ## 夜间「单独配色」开关
 *
 * 默认关闭 —— 时 **一套颜色日夜通用**，用户只调一次。
 * 打开后才会出现第二套（夜间）可编辑，两套可以分别微调、也能一键互换。
 *
 * ⚠️ 打开开关的那一刻，夜间那套会**自动复制日间当前值**作为起点
 * （不是复制代码默认值），否则用户调完日间再打开开关，夜间又变回原始色了。
 *
 * ## 没被覆盖的项怎么办
 *
 * 只调了 3 项就退出？其余项继续走 `R.color.<key>` 的默认值。
 * 也就是「部分自定义」是合法状态，不需要用户把所有项都调一遍。
 */
object ColorScheme {

    // ── 存储键 ──────────────────────────────────────────────
    private const val PREF_MODE = "color_mode"
    private const val PREF_NIGHT_SEPARATE = "color_night_separate"
    private const val PREFIX_DAY = "color_day_"
    private const val PREFIX_NIGHT = "color_night_"
    private const val PREF_THEME = "color_theme_id"

    const val MODE_DEFAULT = "default"
    const val MODE_CUSTOM = "custom"

    /**
     * 一个色槽 = 主页上某处可以被用户改颜色的地方。
     *
     * @param key        存储键前缀，也对应 `R.color.<key>` 的默认值
     * @param groupRes   分组标题（设置页的小标题），由调用方按当前语言取
     * @param labelRes   这一项的名字
     */
    data class Slot(
        val key: String,
        val defaultResId: Int,
        val groupRes: Int,
        val labelRes: Int,
    )

    /** 简单模式：只暴露最常调的几个主色 */
    val SIMPLE_KEYS = setOf(
        "c_btn_player", "c_btn_map", "c_btn_save", "c_bar_sidebar",
        "c_icon_tree", "c_icon_view",
    )

    // ── 模式 ────────────────────────────────────────────────

    fun mode(prefs: SharedPreferences): String =
        prefs.getString(PREF_MODE, MODE_DEFAULT) ?: MODE_DEFAULT

    fun setMode(prefs: SharedPreferences, mode: String) {
        prefs.edit().putString(PREF_MODE, mode).apply()
    }

    fun isDefault(prefs: SharedPreferences) = mode(prefs) == MODE_DEFAULT

    // ── 夜间单独配色开关 ────────────────────────────────────

    fun isNightSeparate(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(PREF_NIGHT_SEPARATE, false)

    /**
     * 打开开关时把日间当前值复制到夜间，作为编辑起点。
     * 关闭开关时不清空夜间值（用户可能只是暂时不用，下次打开还在）。
     */
    fun setNightSeparate(prefs: SharedPreferences, on: Boolean, slots: List<Slot>) {
        if (on && !isNightSeparate(prefs)) {
            val ed = prefs.edit()
            slots.forEach { slot ->
                ed.putInt(PREFIX_NIGHT + slot.key, dayValue(prefs, slot))
            }
            ed.putBoolean(PREF_NIGHT_SEPARATE, true).apply()
        } else {
            prefs.edit().putBoolean(PREF_NIGHT_SEPARATE, on).apply()
        }
    }

    // ── 取值 ────────────────────────────────────────────────

    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** 日间那套当前生效的颜色（含「默认模式」与「未自定义项回退」两种情况） */
    fun dayValue(prefs: SharedPreferences, slot: Slot): Int =
        prefs.getInt(PREFIX_DAY + slot.key, slot.defaultResId)

    /** 夜间那套当前生效的颜色 */
    fun nightValue(prefs: SharedPreferences, slot: Slot): Int =
        prefs.getInt(PREFIX_NIGHT + slot.key, slot.defaultResId)

    /**
     * 当前该用哪个颜色。
     *
     * 注意 [slot.defaultResId] 存的是**已解析的 ARGB int**（调用方用 ContextCompat.getColor 取），
     * 不是资源 id —— 这样 obj 不依赖 Context，取值时少一次查表。
     */
    fun value(prefs: SharedPreferences, slot: Slot, night: Boolean): Int {
        if (isDefault(prefs)) return slot.defaultResId
        return if (night && isNightSeparate(prefs)) nightValue(prefs, slot)
        else dayValue(prefs, slot)
    }

    /** 当前正在编辑哪一套（用于设置页显示） */
    fun editingNight(prefs: SharedPreferences, night: Boolean): Boolean =
        night && !isDefault(prefs) && isNightSeparate(prefs)

    fun setValue(prefs: SharedPreferences, slot: Slot, night: Boolean, color: Int) {
        val key = if (night && isNightSeparate(prefs)) PREFIX_NIGHT else PREFIX_DAY
        prefs.edit().putInt(key + slot.key, color).apply()
    }

    fun clearValue(prefs: SharedPreferences, slot: Slot, night: Boolean) {
        val key = if (night && isNightSeparate(prefs)) PREFIX_NIGHT else PREFIX_DAY
        prefs.edit().remove(key + slot.key).apply()
    }

    /** 这一项是否被用户改过（用于在列表里标一个「已修改」的小圆点） */
    fun isModified(prefs: SharedPreferences, slot: Slot, night: Boolean): Boolean {
        if (isDefault(prefs)) return false
        val key = if (night && isNightSeparate(prefs)) PREFIX_NIGHT else PREFIX_DAY
        return prefs.contains(key + slot.key)
    }

    // ── 互换 / 重置 ─────────────────────────────────────────

    /**
     * 日间 ⇄ 夜间 整套互换。只在「夜间单独配色」打开时有意义。
     *
     * ⚠️ 返回 true 表示两套**内容确实不同、换完有变化**；false 表示两套一样，
     *    换完看不出区别 —— UI 据此给用户一句提示，否则点下去「毫无反应」
     *    会让人以为按钮坏了（2026-11-02）。
     *
     * 可以反复点击：每次都把两套完整对调，来回点就是来回换。
     */
    fun swapDayNight(prefs: SharedPreferences, slots: List<Slot>): Boolean {
        if (!isNightSeparate(prefs)) return false

        // 先判断两套是否真的有差异（没有就没得换）
        val differs = slots.any { dayValue(prefs, it) != nightValue(prefs, it) }
        if (!differs) return false

        val ed = prefs.edit()
        slots.forEach { slot ->
            // 两个值必须在写入前都读出来，否则第二行会读到刚写进去的新值
            val d = dayValue(prefs, slot)
            val n = nightValue(prefs, slot)
            ed.putInt(PREFIX_DAY + slot.key, n)
            ed.putInt(PREFIX_NIGHT + slot.key, d)
        }
        ed.apply()
        return true
    }

    /**
     * 套用一整套主题配色。
     *
     * 会把主题展开出来的所有色槽一次性写进去，然后切到「自定义」模式。
     * 用户套完之后仍然可以逐项微调 —— 主题只是把 24 个槽一次填好，
     * 不存在「主题模式下不能改」这回事。
     *
     * @param night 是否写到夜间那套（只在「夜间单独配色」打开时有意义）
     */
    fun applyTheme(
        prefs: SharedPreferences,
        slots: List<Slot>,
        themeColors: Map<String, Int>,
        night: Boolean = false,
    ) {
        val useNight = night && isNightSeparate(prefs)
        val prefix = if (useNight) PREFIX_NIGHT else PREFIX_DAY
        val ed = prefs.edit()
        slots.forEach { slot ->
            val c = themeColors[slot.key] ?: return@forEach
            ed.putInt(prefix + slot.key, c)
        }
        ed.putString(PREF_MODE, MODE_CUSTOM)
        ed.apply()
    }

    /** 当前记录的主题 id（仅用于设置页高亮「正在用哪个主题」） */
    fun currentTheme(prefs: SharedPreferences): String? =
        prefs.getString(PREF_THEME, null)

    fun setCurrentTheme(prefs: SharedPreferences, id: String?) {
        prefs.edit().putString(PREF_THEME, id).apply()
    }

    /** 清空所有自定义项，回到「默认颜色」模式 */
    fun resetAll(prefs: SharedPreferences, slots: List<Slot>) {
        val ed = prefs.edit()
        slots.forEach { slot ->
            ed.remove(PREFIX_DAY + slot.key)
            ed.remove(PREFIX_NIGHT + slot.key)
        }
        ed.putBoolean(PREF_NIGHT_SEPARATE, false)
        ed.putString(PREF_MODE, MODE_DEFAULT)
        ed.apply()
    }

    // ── 颜色工具 ────────────────────────────────────────────

    /**
     * 宽容地解析用户输入的颜色。接受的写法：
     *
     * ```
     * #4CAF50        4CAF50          ← 有没有 # 都行
     * #FF4CAF50      FF4CAF50        ← 带 alpha 的 8 位
     * #4C5           #4CF            ← 3 位简写，自动展开成 44CC55 / 44CCFF
     * 0x4CAF50       4caf50          ← 0x 前缀、小写都行
     * rgb(76,175,80) 76,175,80       ← RGB 逗号写法
     * 4C AF 50                       ← 中间有空格也认
     * ```
     *
     * 为什么这么宽容：用户可能从别处复制一个色值过来，
     * 带着 `#`、`0x`、大小写、空格、逗号各种变体。
     * 与其让人自己删干净，不如都认。
     */
    fun parseHex(input: String): Int? {
        val raw = input.trim()
        if (raw.isEmpty()) return null

        // rgb(76,175,80) / rgba(76,175,80)
        Regex("""rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})""", RegexOption.IGNORE_CASE)
            .find(raw)?.let { m ->
                val r = m.groupValues[1].toIntOrNull() ?: return null
                val g = m.groupValues[2].toIntOrNull() ?: return null
                val b = m.groupValues[3].toIntOrNull() ?: return null
                if (r !in 0..255 || g !in 0..255 || b !in 0..255) return null
                return android.graphics.Color.rgb(r, g, b)
            }

        // 76,175,80 这种「裸 RGB」写法
        if (!raw.contains('#') && raw.contains(',')) {
            val parts = raw.split(',').map { it.trim().toIntOrNull() }
            if (parts.size == 3) {
                val r = parts[0]
                val g = parts[1]
                val b = parts[2]
                if (r != null && g != null && b != null &&
                    r in 0..255 && g in 0..255 && b in 0..255
                ) return android.graphics.Color.rgb(r, g, b)
            }
        }

        // 去掉前缀与分隔符：# / 0x / 空白 / 逗号 / 分号
        var s = raw
        if (s.startsWith("#")) s = s.substring(1)
        if (s.startsWith("0x", ignoreCase = true)) s = s.substring(2)
        s = s.replace(Regex("""[\s,;]"""), "")

        if (s.isEmpty() || !s.all { it.isDigit() || it in "abcdefABCDEF" }) return null

        return when (s.length) {
            3 -> {   // #4C5 -> #44CC55
                val r = s[0].toString().repeat(2)
                val g = s[1].toString().repeat(2)
                val b = s[2].toString().repeat(2)
                runCatching { ("#" + r + g + b).toColorInt() }.getOrNull()
            }
            6, 8 -> runCatching { ("#" + s).toColorInt() }.getOrNull()
            else -> null
        }
    }

    fun toHex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

    /** 深色背景配白字、浅色背景配黑字 —— 避免用户选了浅色后白字看不见 */
    fun onColor(background: Int): Int =
        if (ColorUtils.calculateLuminance(background) > 0.5) 0xFF222222.toInt()
        else 0xFFFFFFFF.toInt()

    /**
     * 预设色板。Material 500 系 —— 和现有配色同一套语言，
     * 用户随手挑一个也不会跳出风格。
     */
    val PRESETS = listOf(
        "#2196F3", // 蓝
        "#1976D2", // 深蓝
        "#03A9F4", // 浅蓝
        "#009688", // 青
        "#4CAF50", // 绿
        "#43A047", // 深绿
        "#8BC34A", // 浅绿
        "#CDDC39", // 黄绿
        "#FFEB3B", // 黄
        "#FFC107", // 琥珀
        "#FF9800", // 橙
        "#FF5722", // 深橙
        "#F44336", // 红
        "#E53935", // 深红
        "#E91E63", // 粉
        "#D81B60", // 深粉
        "#9C27B0", // 紫
        "#673AB7", // 深紫
        "#3F51B5", // 靛蓝
        "#607D8B", // 蓝灰
        "#795548", // 棕
        "#9E9E9E", // 灰
        "#616161", // 深灰
        "#000000", // 黑
    )
}
