package yhjmew.nibnya.editor.dialogs

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import yhjmew.nibnya.R
import yhjmew.nibnya.util.ColorScheme

/**
 * 单色编辑器：预设色板 + 十六进制 + RGB 三滑块。
 *
 * 三者**双向同步** —— 改哪个另外两个跟着变，所以用户可以
 * 「先点一个接近的预设，再微调 RGB」，也可以直接输 HEX。
 */
object ColorPickerDialog {

    fun show(
        ctx: Context,
        title: String,
        initial: Int,
        onPick: (Int) -> Unit,
    ) {
        var current = initial

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 20), dp(ctx, 8))
        }

        // ── 预览块 ──
        // 一个圆角色块，中间叠十六进制文字。
        // ⚠️ 早期版本把 preview 先 addView 到 root、又想再塞进 FrameLayout，
        //    触发 "The specified child already has a parent" 崩溃（2026-11-02）。
        //    现在直接构造好再一次性加入，不做「先加后移」。
        val previewWrap = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 56)
            ).apply { bottomMargin = dp(ctx, 12) }
        }
        val preview = View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 10).toFloat()
                setColor(current)
            }
        }
        val previewText = TextView(ctx).apply {
            text = ColorScheme.toHex(current)
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(ColorScheme.onColor(current))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        previewWrap.addView(preview)
        previewWrap.addView(previewText)
        root.addView(previewWrap)

        // ── 十六进制 ──
        val hexRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hexRow.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.color_edit_hex)
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.app_text_secondary))
            width = dp(ctx, 72)
        })
        val etHex = EditText(ctx).apply {
            hint = ctx.getString(R.string.color_edit_hex_hint)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(9))
            setSingleLine(true)
            setText(ColorScheme.toHex(current))
            setSelection(text.length)
        }
        hexRow.addView(etHex, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(hexRow)

        // ── RGB 三滑块 ──
        fun channelRow(label: String, max: Int): Pair<SeekBar, TextView> {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
            }
            row.addView(TextView(ctx).apply {
                text = label
                textSize = 13f
                width = dp(ctx, 28)
                setTextColor(ContextCompat.getColor(ctx, R.color.app_text_secondary))
            })
            val sb = SeekBar(ctx).apply { this.max = max }
            val tv = TextView(ctx).apply {
                textSize = 13f
                width = dp(ctx, 40)
                gravity = Gravity.END
            }
            row.addView(sb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(tv)
            root.addView(row)
            return sb to tv
        }

        root.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.color_edit_rgb)
            textSize = 13f
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 4))
            setTextColor(ContextCompat.getColor(ctx, R.color.app_text_secondary))
        })

        val (sbR, tvR) = channelRow(ctx.getString(R.string.color_edit_r), 255)
        val (sbG, tvG) = channelRow(ctx.getString(R.string.color_edit_g), 255)
        val (sbB, tvB) = channelRow(ctx.getString(R.string.color_edit_b), 255)

        // ── 预设色板 ──
        root.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.color_edit_presets)
            textSize = 13f
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 6))
            setTextColor(ContextCompat.getColor(ctx, R.color.app_text_secondary))
        })

        val grid = GridLayout(ctx).apply {
            columnCount = 8
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(grid)

        // ── 同步逻辑 ──
        var syncing = false

        fun refreshAll(updateHex: Boolean, updateRgb: Boolean, updatePreview: Boolean) {
            if (updatePreview) {
                (preview.background as GradientDrawable).setColor(current)
                previewText.setTextColor(ColorScheme.onColor(current))
            }
            if (updateHex) {
                syncing = true
                etHex.setText(ColorScheme.toHex(current))
                etHex.setSelection(etHex.text.length)
                syncing = false
            }
            if (updateRgb) {
                syncing = true
                sbR.progress = Color.red(current)
                sbG.progress = Color.green(current)
                sbB.progress = Color.blue(current)
                tvR.text = Color.red(current).toString()
                tvG.text = Color.green(current).toString()
                tvB.text = Color.blue(current).toString()
                syncing = false
            }
        }

        fun applyColor(c: Int, updateHex: Boolean = true, updateRgb: Boolean = true) {
            current = c
            refreshAll(updateHex, updateRgb, true)
        }

        sbR.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser || syncing) return
                tvR.text = p.toString()
                applyColor(Color.rgb(p, Color.green(current), Color.blue(current)))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        sbG.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser || syncing) return
                tvG.text = p.toString()
                applyColor(Color.rgb(Color.red(current), p, Color.blue(current)))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        sbB.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser || syncing) return
                tvB.text = p.toString()
                applyColor(Color.rgb(Color.red(current), Color.green(current), p))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        etHex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                val parsed = ColorScheme.parseHex(s?.toString() ?: return) ?: return
                current = parsed
                refreshAll(updateHex = false, updateRgb = true, updatePreview = true)
            }
        })

        // 预设色块
        val size = dp(ctx, 30)
        ColorScheme.PRESETS.forEach { hex ->
            val c = hex.toColorInt()
            val sw = View(ctx).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(ctx, 6).toFloat()
                    setColor(c)
                    setStroke(dp(ctx, 1), 0x33000000)
                }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = size
                    height = size
                    setMargins(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3))
                }
                isClickable = true
                setOnClickListener { applyColor(c) }
            }
            grid.addView(sw)
        }

        refreshAll(updateHex = true, updateRgb = true, updatePreview = true)

        val scroll = ScrollView(ctx).apply { addView(root) }

        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val parsed = ColorScheme.parseHex(etHex.text.toString())
                onPick(parsed ?: current)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
