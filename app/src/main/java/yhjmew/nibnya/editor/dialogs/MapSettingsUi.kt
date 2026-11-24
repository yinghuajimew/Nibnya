package yhjmew.nibnya.editor.dialogs

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import yhjmew.nibnya.R
import yhjmew.nibnya.map.MapSettings

/**
 * 设置页「编辑器」标签里的地图渲染设置。
 *
 * ## 为什么用代码构建而不是写 XML
 *
 * 这几项都是「标签 + 滑杆 + 当前值」，结构一致。写成 XML 要为每一项
 * 复制一段 30 行的布局，改一处数值范围就得改五处。这里用循环生成，
 * 范围和默认值全部来自 [MapSettings]，**只有一处真相**。
 *
 * ## 为什么改完要提示「重启地图页」
 *
 * 缓存、线程池、种子层都是 `MapSession` 构造时创建的。
 * 想立刻生效必须重建整个 session（关库、停线程、清缓存），
 * 代价远大于「回地图页重新打开一次」。所以这里如实告诉用户，
 * 而不是假装即时生效然后让人以为设置坏了。
 */
object MapSettingsUi {

    /**
     * 构建整块设置。
     *
     * @param onChange 任意值改变后回调（用于重绘当前值）
     */
    fun build(
        ctx: Context,
        prefs: SharedPreferences,
        container: LinearLayout,
        onChange: () -> Unit,
    ) {
        container.removeAllViews()

        val primary = ContextCompat.getColor(ctx, R.color.app_text_primary)
        val dim = ContextCompat.getColor(ctx, R.color.app_text_secondary)
        val accent = ContextCompat.getColor(ctx, R.color.nbt_key)

        container.addView(title(ctx, "地图渲染", primary))
        container.addView(hint(ctx, "改动在重新打开地图页后生效。", dim))

        // ── 数值项 ──

        numberRow(
            ctx, container,
            label = "瓦片缓存",
            desc = "越大越不容易反复重算，占内存也越多",
            value = MapSettings.cacheMb(ctx),
            min = MapSettings.CACHE_MB_MIN,
            max = MapSettings.CACHE_MB_MAX,
            step = 4,
            unit = " MB",
            primary = primary, dim = dim, accent = accent,
        ) {
            MapSettings.setCacheMb(ctx, it)
            onChange()
        }

        numberRow(
            ctx, container,
            label = "区块缓存",
            desc = "每个区块约 1.5 KB；滑回看过的区域时不用重新解析",
            value = MapSettings.chunkEntries(ctx),
            min = MapSettings.CHUNK_ENTRIES_MIN,
            max = MapSettings.CHUNK_ENTRIES_MAX,
            step = 512,
            unit = " 个",
            primary = primary, dim = dim, accent = accent,
        ) {
            MapSettings.setChunkEntries(ctx, it)
            onChange()
        }

        numberRow(
            ctx, container,
            label = "渲染线程",
            desc = "低端机设 1 可减少卡顿；设 3~4 在大存档上更快",
            value = MapSettings.threads(ctx),
            min = MapSettings.THREADS_MIN,
            max = MapSettings.THREADS_MAX,
            step = 1,
            unit = " 线程",
            primary = primary, dim = dim, accent = accent,
        ) {
            MapSettings.setThreads(ctx, it)
            onChange()
        }

        // ── 开关项 ──

        checkRow(
            ctx, container,
            label = "种子地形层",
            desc = "没探索过的地方用世界种子推演群系。关掉能省一半 CPU",
            checked = MapSettings.seedLayerEnabled(ctx),
            primary = primary, dim = dim, accent = accent,
        ) {
            MapSettings.setSeedLayerEnabled(ctx, it)
            onChange()
        }

        checkRow(
            ctx, container,
            label = "区块描边",
            desc = "沿已加载区域的边界画粉色线",
            checked = MapSettings.boundaryEnabled(ctx),
            primary = primary, dim = dim, accent = accent,
        ) {
            MapSettings.setBoundaryEnabled(ctx, it)
            onChange()
        }

        // ── native 统计（只读）──

        run {
            val stats = yhjmew.nibnya.map.SeedTerrainSource.stats()
            if (stats != null) {
                container.addView(
                    hint(
                        ctx,
                        "种子层已生成 ${stats[0]} 块瓦片，失败 ${stats[1]} 块，累计 ${stats[2]} ms",
                        dim,
                    )
                )
            }
        }
    }

    // ── 构建辅助 ──

    private fun title(ctx: Context, text: String, color: Int) =
        TextView(ctx).apply {
            this.text = text
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color)
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 4))
        }

    private fun hint(ctx: Context, text: String, color: Int) =
        TextView(ctx).apply {
            this.text = text
            textSize = 11f
            setTextColor(color)
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
        }

    /**
     * 一行「标签 + 当前值 + 滑杆」。
     *
     * 用 SeekBar 而不是输入框：这四项的取值范围都不大（8~64、1~4），
     * 拖动比敲数字快，也不可能输入非法值。
     */
    private fun numberRow(
        ctx: Context,
        container: LinearLayout,
        label: String,
        desc: String,
        value: Int,
        min: Int,
        max: Int,
        step: Int,
        unit: String,
        primary: Int,
        dim: Int,
        accent: Int,
        onChange: (Int) -> Unit,
    ) {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
        }

        val valueTv = TextView(ctx).apply {
            text = "$value$unit"
            textSize = 13f
            setTextColor(accent)
            setTypeface(null, Typeface.BOLD)
        }

        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            TextView(ctx).apply {
                text = label
                textSize = 14f
                setTextColor(primary)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(valueTv)
        box.addView(head)

        box.addView(
            TextView(ctx).apply {
                text = desc
                textSize = 11f
                setTextColor(dim)
                setPadding(0, dp(ctx, 2), 0, dp(ctx, 4))
            }
        )

        // SeekBar 只有整数刻度，所以按 step 换算成「第几格」
        val steps = ((max - min) / step).coerceAtLeast(1)

        val bar = SeekBar(ctx).apply {
            this.max = steps
            progress = ((value - min) / step).coerceIn(0, steps)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = (min + p * step).coerceIn(min, max)
                    valueTv.text = "$v$unit"
                    if (fromUser) onChange(v)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        box.addView(bar)

        container.addView(box)
    }

    private fun checkRow(
        ctx: Context,
        container: LinearLayout,
        label: String,
        desc: String,
        checked: Boolean,
        primary: Int,
        dim: Int,
        accent: Int,
        onToggle: (Boolean) -> Unit,
    ) {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
        }
        box.addView(
            CheckBox(ctx).apply {
                text = label
                textSize = 14f
                setTextColor(primary)
                isChecked = checked
                setOnCheckedChangeListener { _, v -> onToggle(v) }
            }
        )
        box.addView(
            TextView(ctx).apply {
                text = desc
                textSize = 11f
                setTextColor(dim)
                setPadding(dp(ctx, 32), 0, 0, 0)
            }
        )
        container.addView(box)
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
