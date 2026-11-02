package yhjmew.nibnya.mapart

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.toColorInt
import yhjmew.nibnya.MainActivity
import yhjmew.nibnya.R
import yhjmew.nibnya.nbt.NbtTranslator

/**
 * 拼图成品的「放入背包」槽位选择器。
 *
 * ## 为什么改成网格
 * 原来是 36 行纯文本列表（`[0] minecraft:netherite_sword x1` / `[1] --空--` …），
 * 选个格子要滚半天，也看不出「哪格空、哪格满」的空间关系。
 *
 * ## 布局取舍
 * 游戏里背包是 9 列 × 4 行，但手机 9 列每格只有 ~35dp，
 * 中文物品名根本放不下。所以用 **4 列 × 9 行**：
 * 格子够大能放下名字与数量，每格左上角标注原始槽位号。
 *
 * ## 分区（按用户要求）
 * 基岩版背包 36 格分两块：
 *   · 槽位 0..8   = 快捷栏（游戏底部那一行）
 *   · 槽位 9..35  = 背包
 * 界面上分成两个区，各自带标题与可用空位数。
 *
 * ## 交互
 * - 空格：绿色，直接点选
 * - 已占用：灰色，仍可点 —— 弹二次确认「这里已有 xxx ×N，确定覆盖？」
 * - 长按：显示完整信息（槽位号 / 本地化名 / 原始 ID / 数量）
 */
/**
 * 当前打开着的槽位网格对话框。
 *
 * 重写时曾经漏掉 dismiss：选中槽位后弹窗不关，Toast 就盖在
 * 「生成前的旧背包」上，看起来像「没有实时刷新」。这里显式持有引用，
 * 选中/取消时立刻关闭。
 */
private var slotDialog: AlertDialog? = null

internal fun MainActivity.showSlotGrid(
    info: MapArtViewModel.InventorySlotInfo,
    onPick: (Int) -> Unit,
    onCancel: () -> Unit
) {
    // 防连点：先关掉可能还开着的旧弹窗
    slotDialog?.dismiss()
    slotDialog = null

    val density = resources.displayMetrics.density
    val emptyCount = info.mainSlots.count { !it.isOccupied }

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), 0)
    }

    root.addView(TextView(this).apply {
        text = getString(R.string.msg_puzzle_slots_summary, emptyCount, info.mainSlots.size, 1)
        textSize = 12f
        setTextColor(getColor(R.color.hint_text))
        setPadding((4 * density).toInt(), 0, (4 * density).toInt(), (8 * density).toInt())
    })

    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    // 快捷栏 0..8（游戏底部那 9 格）
    addSlotSection(
        body, info, 0, 8, R.string.title_slot_hotbar, density, onPick
    )
    // 背包 9..35
    addSlotSection(
        body, info, 9, 35, R.string.title_slot_inventory, density, onPick
    )

    // 网格区占满剩余空间（weight=1），图例才能真的「固定」在底部。
    root.addView(
        ScrollView(this).apply { addView(body) },
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
    )

    // 图例固定在底部，不随网格滚动。
    // 原来它和内容一起放在 AlertDialog 里，宽度没约束 → 文字右侧被裁掉，
    // 表现为「长按」后面看不见，且不能上下滑动看到。
    root.addView(TextView(this).apply {
        text = getString(R.string.msg_puzzle_slot_legend)
        textSize = 11f
        setTextColor(getColor(R.color.muted_text))
        setPadding((4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt())
    }, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ))

    val dialog = AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_puzzle_slot_select))
        .setView(root)
        .setNegativeButton(R.string.btn_cancel) { _, _ ->
            slotDialog = null
            onCancel()
        }
        .setCancelable(false)
        .create()

    dialog.show()

    // 明确指定窗口尺寸：宽度 92% 屏宽、高度最多 82% 屏高。
    // AlertDialog 默认按内容测量，长文本会被裁在右边、底部留白也失控。
    dialog.window?.setLayout(
        (resources.displayMetrics.widthPixels * 0.92f).toInt(),
        (resources.displayMetrics.heightPixels * 0.82f).toInt()
    )
    slotDialog = dialog
}

/** 一个分区：标题（含该区空位数）+ 4 列网格 */
private fun MainActivity.addSlotSection(
    parent: LinearLayout,
    info: MapArtViewModel.InventorySlotInfo,
    from: Int,
    to: Int,
    titleRes: Int,
    density: Float,
    onPick: (Int) -> Unit
) {
    val emptyInSection = (from..to).count { !info.mainSlots[it].isOccupied }

    parent.addView(TextView(this).apply {
        text = getString(R.string.msg_slot_section_header, getString(titleRes), from, to, emptyInSection)
        textSize = 12f
        setTextColor(getColor(R.color.nbt_label))
        setPadding((4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt(), (6 * density).toInt())
    })

    val cols = 4
    for (rowStart in from..to step cols) {
        val rowEnd = minOf(rowStart + cols - 1, to)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        for (i in rowStart..rowEnd) {
            val s = info.mainSlots[i]
            val cell = buildCell(s, density)
            cell.setOnClickListener {
                slotDialog?.dismiss()
                slotDialog = null
                onPick(s.slot)
            }
            cell.setOnLongClickListener {
                showSlotDetail(s)
                true
            }
            row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        // 补齐末行，保证列宽一致
        for (k in (rowEnd - rowStart + 1) until cols) {
            row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        }
        parent.addView(row)
    }
}

/** 一个格子：槽位号 + 本地化物品名 + 数量；空位显示「空」 */
private fun MainActivity.buildCell(
    s: MapArtViewModel.SlotInfo,
    density: Float
): View {
    val occupied = s.isOccupied
    val cell = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding((4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt())
        background = GradientDrawable().apply {
            cornerRadius = (6 * density).toInt().toFloat()
            setColor(if (occupied) getColor(R.color.slot_used_bg) else getColor(R.color.slot_empty_bg))
            setStroke((1 * density).toInt(), if (occupied) getColor(R.color.slot_used_stroke) else getColor(R.color.slot_empty_stroke))
        }
        isClickable = true
    }

    cell.addView(TextView(this).apply {
        text = s.slot.toString()
        textSize = 10f
        setTextColor(getColor(R.color.muted_text))
    })

    if (occupied) {
        cell.addView(TextView(this).apply {
            // 显示玩家语言的物品名，而不是命名空间 ID
            text = localizedItemName(s.name)
            textSize = 11f
            maxLines = 2
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.nbt_label))
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        cell.addView(TextView(this).apply {
            text = getString(R.string.msg_puzzle_slot_count, s.count)
            textSize = 10f
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.c_danger))
        })
    } else {
        cell.addView(TextView(this).apply {
            text = getString(R.string.text_slot_empty)
            textSize = 11f
            setTextColor("#9E9E9E".toColorInt())
        })
    }
    return cell
}

/** 长按：完整信息（含原始 ID，便于排查） */
private fun MainActivity.showSlotDetail(s: MapArtViewModel.SlotInfo) {
    val label = getString(R.string.label_puzzle_slot, s.slot)
    if (!s.isOccupied) {
        toast(getString(R.string.msg_puzzle_slot_is_empty, label))
        return
    }
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_puzzle_slot_occupied))
        .setMessage(
            getString(
                R.string.msg_puzzle_slot_detail,
                label, localizedItemName(s.name), s.name, s.count
            )
        )
        .setPositiveButton(R.string.btn_close, null)
        .show()
}

/**
 * 物品 ID → 玩家语言的名称。
 * 词表由 [NbtTranslator] 按「应用设置的语言」加载（物品 + 方块）。
 * 查不到时退回去掉命名空间的原始 ID。
 */
internal fun localizedItemName(raw: String): String {
    NbtTranslator.getItemTranslation(raw)?.takeIf { it.isNotBlank() }?.let { return it }
    return raw.substringAfter(':')
}

