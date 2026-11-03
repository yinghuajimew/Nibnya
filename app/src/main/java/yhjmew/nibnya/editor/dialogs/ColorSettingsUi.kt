package yhjmew.nibnya.editor.dialogs

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import yhjmew.nibnya.R
import yhjmew.nibnya.util.ColorScheme
import yhjmew.nibnya.util.ColorSlots
import yhjmew.nibnya.util.ColorThemes

/**
 * 「外观 › 配色」区域的构建与交互（2026-11-02）。
 *
 * 挂在 `R.id.layout_color_settings` 下，整块由代码生成 ——
 * 因为色槽清单来自 [ColorSlots] 注册表，加一项颜色不需要改布局。
 *
 * ## 结构
 *
 * ```
 * 配色
 *   配色模式        [默认颜色] [自定义]      ← 单选
 *   简单模式        [✓]                     ← 只显示常用几项
 *   夜间单独配色    [ ]                     ← 打开后出现第二套
 *   日间 ⇄ 夜间 互换                        ← 仅两套模式显示
 *   ── 主页 · 功能按钮 ──
 *     编辑联机玩家      [■] #2196F3       ← 点色块调色
 *     ...
 *   全部恢复默认
 * ```
 */
object ColorSettingsUi {

    private const val PREF_SIMPLE = "color_simple_mode"

    fun build(ctx: Context, prefs: SharedPreferences, container: LinearLayout, onChange: () -> Unit) {
        container.removeAllViews()

        val slots = ColorSlots.build(ctx)
        val night = ColorScheme.isNight(ctx)
        val isDefault = ColorScheme.isDefault(prefs)
        val nightSep = ColorScheme.isNightSeparate(prefs)
        val simple = prefs.getBoolean(PREF_SIMPLE, false)

        val accent = ContextCompat.getColor(ctx, R.color.nbt_key)
        val dim = ContextCompat.getColor(ctx, R.color.app_text_secondary)
        val primary = ContextCompat.getColor(ctx, R.color.app_text_primary)

        // ── 小标题「配色」 ──
        container.addView(sectionTitle(ctx, ctx.getString(R.string.color_section_title), primary))

        // ── 配色模式：两个单选行 ──
        container.addView(subLabel(ctx, ctx.getString(R.string.color_mode_label), dim))

        fun modeRow(label: String, desc: String, value: String) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
                isClickable = true
                setOnClickListener {
                    ColorScheme.setMode(prefs, value)
                    onChange()
                }
            }
            // ⚠️ 用真正的 RadioButton，而不是用 ◉ / ○ 这两个字符画：
            //    字符在不同字体下宽度不一致，配 fixed width 会被裁切/错位，
            //    看起来「大小不对、有东西影响了」（2026-11-02 反馈）。
            // ⚠️ RadioButton 在 Theme.Material 下需要显式给尺寸和 tintMode，
            //    否则圆钮可能完全不绘制（2026-11-02 用户反馈「圆钮丢失」）。
            //    这里最小化依赖：直接自己画一个圆，不依赖控件主题。
            // 自绘单选圆，不依赖 RadioButton 的控件主题
            // （RadioButton 在 Theme.Material 下可能完全不绘制，2026-11-02 踩过）。
            //   选中 = 实心圆 + 中间小白点（同心圆，白底上也清楚）
            //   未选 = 空心圆 + 灰色描边
            val selected = ColorScheme.mode(prefs) == value
            val size = dp(ctx, 18)
            val radio = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accent)
                    if (selected) {
                        // 实心：外圈 accent，中间留白点 —— 用 LayerDrawable 更准，
                        // 这里用描边宽度模拟「环 + 心」，简单且在任意底色上都可见
                        setStroke(dp(ctx, 1), accent)
                    } else {
                        setColor(android.graphics.Color.TRANSPARENT)
                        setStroke(dp(ctx, 2), dim)
                    }
                }
                isClickable = false
            }
            // 选中的中间小白点
            val dotWrap = android.widget.FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    topMargin = dp(ctx, 3)
                }
                addView(radio, android.widget.FrameLayout.LayoutParams(size, size))
                if (selected) {
                    addView(View(ctx).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(0xFFFFFFFF.toInt())
                        }
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            dp(ctx, 6), dp(ctx, 6), Gravity.CENTER
                        )
                    })
                }
            }
            // 注意：radio 有自己的 layoutParams，放进 dotWrap 前要重置，
            // 否则 FrameLayout 会用 LinearLayout 的参数导致尺寸为 0
            radio.layoutParams = android.widget.FrameLayout.LayoutParams(size, size)

            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(ctx).apply {
                text = label
                textSize = 14f
                setTextColor(primary)
                setTypeface(null, if (ColorScheme.mode(prefs) == value) Typeface.BOLD else Typeface.NORMAL)
            })
            col.addView(TextView(ctx).apply {
                text = desc
                textSize = 11f
                setTextColor(dim)
                setPadding(0, dp(ctx, 2), 0, 0)
            })
            row.addView(dotWrap)
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            container.addView(row)
        }

        modeRow(
            ctx.getString(R.string.color_mode_default),
            ctx.getString(R.string.color_mode_default_desc),
            ColorScheme.MODE_DEFAULT,
        )
        modeRow(
            ctx.getString(R.string.color_mode_custom),
            ctx.getString(R.string.color_mode_custom_desc),
            ColorScheme.MODE_CUSTOM,
        )

        if (isDefault) {
            // 默认模式下不展示调色列表，避免用户以为调了没反应
            container.addView(hint(ctx, ctx.getString(R.string.color_mode_default_desc), dim))
            container.addView(resetButton(ctx, prefs, slots, onChange))
            return
        }

        // ── 主题配色 ──
        // 一套主题 = 24 个色槽一次性填好，用户不用逐项调。
        // 套完仍可往下逐项微调，主题不是「锁定」状态。
        addThemeSection(ctx, prefs, slots, container, primary, dim, accent, night, onChange)

        // ── 简单模式 ──
        container.addView(checkRow(
            ctx,
            ctx.getString(R.string.color_simple_mode),
            ctx.getString(R.string.color_simple_mode_desc),
            simple,
            primary, dim,
        ) { on ->
            prefs.edit().putBoolean(PREF_SIMPLE, on).apply()
            onChange()
        })

        // ── 夜间单独配色 ──
        container.addView(checkRow(
            ctx,
            ctx.getString(R.string.color_night_separate),
            ctx.getString(R.string.color_night_separate_desc),
            nightSep,
            primary, dim,
        ) { on ->
            // 打开时把日间当前值复制到夜间作为起点（见 ColorScheme.setNightSeparate）
            ColorScheme.setNightSeparate(prefs, on, slots)
            onChange()
        })

        // ── 互换（仅两套模式）──
        if (nightSep) {
            container.addView(actionRow(
                ctx,
                ctx.getString(R.string.color_swap_day_night),
                ctx.getString(R.string.color_swap_day_night_desc),
                accent, dim,
            ) {
                val changed = ColorScheme.swapDayNight(prefs, slots)
                if (changed) {
                    onChange()
                } else {
                    // 两套内容一样时换完看不出区别，必须给反馈，
                    // 否则用户会以为按钮坏了（2026-11-02）
                    android.widget.Toast.makeText(
                        ctx,
                        ctx.getString(R.string.color_swap_same_hint),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            })
        }

        // ── 逐项微调（可折叠）──
        // 24 个色槽竖直排下来要滑好几屏，折叠起来避免设置页被撑得老长。
        // 折叠状态存在 prefs 里，下次打开还是上次的样子。
        val expandedKey = "color_slots_expanded"
        var expanded = prefs.getBoolean(expandedKey, false)

        // 折叠头（整行可点，右侧箭头指示状态）
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 12), 0, dp(ctx, 6))
            isClickable = true
        }
        val headerTitle = TextView(ctx).apply {
            text = ctx.getString(R.string.color_manual_tune)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primary)
        }
        val headerArrow = TextView(ctx).apply {
            text = if (expanded) "▲" else "▼"
            textSize = 12f
            setTextColor(dim)
            setPadding(dp(ctx, 8), 0, 0, 0)
        }
        headerRow.addView(headerTitle)
        headerRow.addView(headerArrow)

        val bodyWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
        }

        headerRow.setOnClickListener {
            expanded = !expanded
            prefs.edit().putBoolean(expandedKey, expanded).apply()
            headerArrow.text = if (expanded) "▲" else "▼"
            bodyWrap.visibility = if (expanded) View.VISIBLE else View.GONE
            onChange()
        }
        container.addView(headerRow)
        container.addView(bodyWrap)

        // 折叠体内部的内容
        val visible = slots.filter { if (simple) it.key in ColorScheme.SIMPLE_KEYS else true }

        fun addSection(isNightSection: Boolean) {
            var lastGroup = -1
            visible.forEach { slot ->
                val cur = ColorScheme.value(prefs, slot, isNightSection)
                if (slot.groupRes != lastGroup) {
                    lastGroup = slot.groupRes
                    bodyWrap.addView(subLabel(ctx, ctx.getString(slot.groupRes), dim, top = dp(ctx, 12)))
                }
                bodyWrap.addView(colorRow(ctx, prefs, slot, isNightSection, cur, primary, dim, onChange))
            }
        }

        if (nightSep) {
            bodyWrap.addView(subLabel(ctx, ctx.getString(R.string.color_day_palette), accent, top = dp(ctx, 10)))
            addSection(false)
            bodyWrap.addView(subLabel(ctx, ctx.getString(R.string.color_night_palette), accent, top = dp(ctx, 18)))
            addSection(true)
        } else {
            addSection(night)
        }

        container.addView(resetButton(ctx, prefs, slots, onChange))
    }

    // ══════════════════════════════════════════════════════
    // 主题配色
    // ══════════════════════════════════════════════════════

    /**
     * 主题色卡：一行一个色卡，显示「五个色块 + 名字」。
     *
     * 点一下 = 把 24 个色槽全部填成这套主题的颜色，并切到「自定义」模式。
     * 正在使用的主题右侧标一个勾。
     */
    private fun addThemeSection(
        ctx: Context,
        prefs: SharedPreferences,
        slots: List<ColorScheme.Slot>,
        container: LinearLayout,
        primary: Int,
        dim: Int,
        accent: Int,
        isNight: Boolean,
        onChange: () -> Unit,
    ) {
        container.addView(subLabel(ctx, ctx.getString(R.string.color_theme_label), dim, top = dp(ctx, 10)))

        // 主题列表折叠状态（23 套全展开太长）
        val themeExpandedKey = "color_theme_expanded"
        var themeExpanded = prefs.getBoolean(themeExpandedKey, false)

        val themeBody = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val toggleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
            isClickable = true
        }
        val currentId = ColorScheme.currentTheme(prefs)
        val currentName = ColorThemes.THEMES.firstOrNull { it.id == currentId }
            ?.name(ctx.resources.configuration.locales[0].language)
            ?: ctx.getString(R.string.color_theme_none)
        val toggleLabel = TextView(ctx).apply {
            text = ctx.getString(R.string.color_theme_current, currentName)
            textSize = 13f
            setTextColor(primary)
        }
        val toggleArrow = TextView(ctx).apply {
            text = if (themeExpanded) "▲" else "▼"
            textSize = 12f
            setTextColor(dim)
            setPadding(dp(ctx, 8), 0, 0, 0)
        }
        toggleRow.addView(toggleLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toggleRow.addView(toggleArrow)

        // 当前主题的色条预览
        ColorThemes.THEMES.firstOrNull { it.id == currentId }?.let { th ->
            toggleRow.addView(themeDots(ctx, th, sizeDp = 10))
        }

        container.addView(toggleRow)
        container.addView(themeBody)
        themeBody.visibility = if (themeExpanded) View.VISIBLE else View.GONE

        fun rebuildThemes() {
            themeBody.removeAllViews()

            // 两列网格：每个格子是「色块在上、名字在下」
            //    颜色      颜色二
            //    颜色名    颜色名二
            // 比一行一个省一半高度，一屏能看到更多主题（2026-11-02 用户要求）
            val grid = android.widget.GridLayout(ctx).apply {
                columnCount = 2
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            ColorThemes.THEMES.forEachIndexed { idx, theme ->
                grid.addView(themeCell(ctx, prefs, slots, theme, isNight, accent, dim, idx) {
                    onChange()
                })
            }
            themeBody.addView(grid)
        }
        rebuildThemes()

        toggleRow.setOnClickListener {
            themeExpanded = !themeExpanded
            prefs.edit().putBoolean(themeExpandedKey, themeExpanded).apply()
            themeBody.visibility = if (themeExpanded) View.VISIBLE else View.GONE
            themeArrow(toggleArrow, themeExpanded)
            onChange()
        }
    }

    private fun themeArrow(tv: TextView, expanded: Boolean) {
        tv.text = if (expanded) "▲" else "▼"
    }

    /**
     * 一个主题格子：上面五个预览色块，下面主题名。
     *
     * 两列布局下这样排最紧凑，也符合用户给的示意：
     * ```
     * 颜色      颜色二
     * 颜色名    颜色名二
     * ```
     * 正在使用的主题：名字加粗变色，右侧补一个小勾。
     */
    private fun themeCell(
        ctx: Context,
        prefs: SharedPreferences,
        slots: List<ColorScheme.Slot>,
        theme: ColorThemes.Theme,
        isNight: Boolean,
        accent: Int,
        dim: Int,
        index: Int,
        onApplied: () -> Unit,
    ): View {
        val isCurrent = ColorScheme.currentTheme(prefs) == theme.id

        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(ctx, 4), dp(ctx, 9), dp(ctx, 4), dp(ctx, 9))
            isClickable = true

            // 两列平分宽度
            layoutParams = android.widget.GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = android.widget.GridLayout.spec(index % 2, 1f)
                rowSpec = android.widget.GridLayout.spec(index / 2)
            }
        }

        // 上：五个色块（themeDots 返回 View，转成 LinearLayout 才能设 gravity）
        val dots = themeDots(ctx, theme, sizeDp = 15)
        (dots as? LinearLayout)?.gravity = Gravity.CENTER
        cell.addView(dots)

        // 下：名字（+ 选中勾）
        val nameRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 5), 0, 0)
        }
        nameRow.addView(TextView(ctx).apply {
            text = theme.name(ctx.resources.configuration.locales[0].language)
            textSize = 12f
            setTextColor(
                if (isCurrent) accent
                else ContextCompat.getColor(ctx, R.color.app_text_primary)
            )
            setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
        })
        if (isCurrent) {
            nameRow.addView(TextView(ctx).apply {
                text = "✓"
                textSize = 12f
                setTextColor(accent)
                setPadding(dp(ctx, 3), 0, 0, 0)
            })
        }
        cell.addView(nameRow)

        cell.setOnClickListener {
            val colors = ColorThemes.build(theme)
            ColorScheme.applyTheme(prefs, slots, colors, night = isNight)
            ColorScheme.setCurrentTheme(prefs, theme.id)
            onApplied()
        }
        return cell
    }

    /** 五个小色块，横向排开作为主题预览 */
    private fun themeDots(ctx: Context, theme: ColorThemes.Theme, sizeDp: Int): View {
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(theme.primaryInt, theme.secondaryInt, theme.accentInt, theme.successInt
        ).forEach { c ->
            wrap.addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    cornerRadius = sizeDp / 3f * ctx.resources.displayMetrics.density
                    setColor(c)
                    setStroke(dp(ctx, 1), 0x33000000)
                }
                layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
                    .apply { marginEnd = dp(ctx, 3) }
            })
        }
        return wrap
    }

    // ══════════════════════════════════════════════════════
    // 组件
    // ══════════════════════════════════════════════════════

    private fun sectionTitle(ctx: Context, text: String, color: Int) =
        TextView(ctx).apply {
            this.text = text
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color)
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 4))
        }

    private fun subLabel(ctx: Context, text: String, color: Int, top: Int = dp(ctx, 8)) =
        TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(color)
            setPadding(0, top, 0, dp(ctx, 2))
        }

    private fun hint(ctx: Context, text: String, color: Int) =
        TextView(ctx).apply {
            this.text = text
            textSize = 11f
            setTextColor(color)
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 4))
        }

    private fun checkRow(
        ctx: Context,
        label: String,
        desc: String,
        checked: Boolean,
        primary: Int,
        dim: Int,
        onToggle: (Boolean) -> Unit,
    ): View {
        // ⚠️ 用 TOP 而不是 CENTER_VERTICAL：
        //    说明文字换行时整块变高，CENTER_VERTICAL 会把勾选框拉到中间偏下，
        //    看起来像压在文字上（2026-11-02 截图反馈）。
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
        }
        val cb = CheckBox(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onToggle(v) }
        }
        row.addView(cb, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 2) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx).apply {
            text = label
            textSize = 14f
            setTextColor(primary)
        })
        col.addView(TextView(ctx).apply {
            text = desc
            textSize = 11f
            setTextColor(dim)
        })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun actionRow(
        ctx: Context,
        label: String,
        desc: String,
        color: Int,
        dim: Int,
        onClick: () -> Unit,
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 4))
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(TextView(ctx).apply {
            text = label
            textSize = 14f
            setTextColor(color)
            setTypeface(null, Typeface.BOLD)
        })
        row.addView(TextView(ctx).apply {
            text = desc
            textSize = 11f
            setTextColor(dim)
        })
        return row
    }

    private fun resetButton(
        ctx: Context,
        prefs: SharedPreferences,
        slots: List<ColorScheme.Slot>,
        onChange: () -> Unit,
    ): View {
        val b = Button(ctx).apply {
            text = ctx.getString(R.string.color_reset_all)
            isAllCaps = false
            textSize = 13f
            setTextColor(0xFFE53935.toInt())
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))
            setOnClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.color_reset_confirm_title)
                    .setMessage(R.string.color_reset_confirm_msg)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        ColorScheme.resetAll(prefs, slots)
                        onChange()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        b.background = null
        return b
    }

    private fun colorRow(
        ctx: Context,
        prefs: SharedPreferences,
        slot: ColorScheme.Slot,
        isNight: Boolean,
        current: Int,
        primary: Int,
        dim: Int,
        onChange: () -> Unit,
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 5), 0, dp(ctx, 5))
            isClickable = true
        }

        val name = TextView(ctx).apply {
            text = ctx.getString(slot.labelRes)
            textSize = 13f
            setTextColor(primary)
        }
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 已修改标记
        if (ColorScheme.isModified(prefs, slot, isNight)) {
            row.addView(TextView(ctx).apply {
                text = "·"
                textSize = 18f
                setTextColor(dim)
                setPadding(0, 0, dp(ctx, 6), 0)
            })
        }

        // 色值文字
        row.addView(TextView(ctx).apply {
            text = ColorScheme.toHex(current)
            textSize = 11f
            setTextColor(dim)
            setPadding(0, 0, dp(ctx, 8), 0)
        })

        // 色块
        val swatch = View(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 5).toFloat()
                setColor(current)
                setStroke(dp(ctx, 1), 0x33000000)
            }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 22))
        }
        row.addView(swatch)

        row.setOnClickListener {
            ColorPickerDialog.show(
                ctx,
                ctx.getString(slot.labelRes),
                current,
            ) { picked ->
                ColorScheme.setValue(prefs, slot, isNight, picked)
                if (ColorScheme.mode(prefs) == ColorScheme.MODE_DEFAULT) {
                    ColorScheme.setMode(prefs, ColorScheme.MODE_CUSTOM)
                }
                onChange()
            }
        }
        return row
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}