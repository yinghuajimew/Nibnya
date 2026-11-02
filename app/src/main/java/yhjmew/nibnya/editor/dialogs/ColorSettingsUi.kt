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
            val radio = TextView(ctx).apply {
                text = if (ColorScheme.mode(prefs) == value) "◉" else "○"
                textSize = 16f
                setTextColor(if (ColorScheme.mode(prefs) == value) accent else dim)
                width = dp(ctx, 28)
            }
            row.addView(radio)

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

        // ── 色槽列表 ──
        // 两套模式下按「日间 / 夜间」分两大段；单套模式只显示一段（不标日间）
        val visible = slots.filter { if (simple) it.key in ColorScheme.SIMPLE_KEYS else true }

        fun addSection(isNightSection: Boolean) {
            var lastGroup = -1
            visible.forEach { slot ->
                val cur = ColorScheme.value(prefs, slot, isNightSection)
                if (slot.groupRes != lastGroup) {
                    lastGroup = slot.groupRes
                    container.addView(subLabel(ctx, ctx.getString(slot.groupRes), dim, top = dp(ctx, 12)))
                }
                container.addView(colorRow(ctx, prefs, slot, isNightSection, cur, primary, dim, onChange))
            }
        }

        if (nightSep) {
            container.addView(subLabel(ctx, ctx.getString(R.string.color_day_palette), accent, top = dp(ctx, 14)))
            addSection(false)
            container.addView(subLabel(ctx, ctx.getString(R.string.color_night_palette), accent, top = dp(ctx, 18)))
            addSection(true)
        } else {
            addSection(night)
        }

        container.addView(resetButton(ctx, prefs, slots, onChange))
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
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
        }
        val cb = CheckBox(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onToggle(v) }
        }
        row.addView(cb)
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
