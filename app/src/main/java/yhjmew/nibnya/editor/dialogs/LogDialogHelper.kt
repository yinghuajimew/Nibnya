package yhjmew.nibnya.editor.dialogs

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import yhjmew.nibnya.MainActivity
import yhjmew.nibnya.R
import yhjmew.nibnya.util.AppLogger

/**
 * 全局统一的「错误 / 日志」弹窗。
 *
 * ```
 * ┌────────────────────────────────┐
 * │  错误                           │
 * │  ┌──────────────────────────┐  │
 * │  │ 详细日志（等宽、可滚动、   │  │  ← 长按可选中复制
 * │  │ 长按可选中复制）           │  │
 * │  └──────────────────────────┘  │
 * │        [导出] [尝试修复] [复制] [取消]
 * └────────────────────────────────┘
 * ```
 *
 * ## 为什么自绘按钮栏，而不是用 AlertDialog 的三个按钮位
 * 1. **按钮顺序**：平台的左→右顺序是 `NEGATIVE / NEUTRAL / POSITIVE`
 *    （ICS 之后固定如此），想精确表达「导出永远在最左」很别扭；
 * 2. **数量**：存档损坏时需要「导出 + 尝试修复 + 复制 + 取消」共 4 个，
 *    平台只给 3 个位。
 * 自绘后顺序与数量都由我们说了算。
 *
 * ## 顺序约定（用户明确要求）
 * - `导出` **永远在最左**：日志可能很长、剪贴板塞不下，所以要有个稳当的落盘出口；
 * - `尝试修复`（仅存档损坏时出现）紧挨在导出**右边**；
 * - 然后是 `复制`、`取消`。
 *
 * @param title      标题（如「错误」「数据库损坏」）
 * @param detail     中间展示的详细日志；为空时显示「未知」
 * @param actionText 可选的动作按钮文案（如「尝试修复」）
 * @param onAction   动作按钮回调；[actionText] 与 [onAction] 都非空才显示该按钮
 * @param cancelText 取消按钮文案，默认「取消」
 * @param onCancel   取消按钮回调
 * @param cancelable 是否允许点外部/返回键关闭，默认 true
 *                   （数据库损坏这类必须让用户明确选择的场景传 false）
 */
fun MainActivity.showLogDialog(
    title: String,
    detail: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    cancelText: String? = null,
    onCancel: (() -> Unit)? = null,
    cancelable: Boolean = true,
    titleTrailing: View? = null,
    onContentTextView: ((TextView) -> Unit)? = null
) {
    val body = detail.ifBlank { getString(R.string.label_sub_unknown) }

    // 等宽字体 + 可选中：日志/堆栈对齐好看，且长按能选中复制
    val tv = TextView(this).apply {
        text = body
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setPadding(40, 32, 40, 32)
        setTextIsSelectable(true)
    }

    onContentTextView?.invoke(tv)

    // 高度最多占屏幕一半：日志再长也不会把底部按钮挤出屏幕
    val scroll = object : ScrollView(this) {
        override fun onMeasure(w: Int, h: Int) {
            val cap = (resources.displayMetrics.heightPixels * 0.5f).toInt()
            val ws = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
            val hs = MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
            super.onMeasure(ws, hs)
        }
    }.apply { addView(tv) }

    lateinit var dialog: AlertDialog

    // ---- 自绘按钮栏：从左到右 = 导出 / 尝试修复 / 复制 / 取消 ----
    val bar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(16, 0, 16, 12)
    }
    fun addButton(label: String, onClick: () -> Unit) {
        bar.addView(
            Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = label
                isAllCaps = false
                textSize = 14f
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            }
        )
    }

    // 最左：导出（走调试菜单同一条路径 / 同一个目录）
    addButton(getString(R.string.btn_export)) { exportLogsToFile() }
    // 右边：仅「存档损坏」这类需要引导用户动作时才出现
    if (actionText != null && onAction != null) {
        addButton(actionText) { onAction() }
    }
    addButton(getString(R.string.menu_copy)) { copyToClipboard(title, body) }
    addButton(cancelText ?: getString(R.string.btn_cancel)) { onCancel?.invoke() }

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // 标题栏右侧要挂下拉时，不能用 setTitle —— 平台的标题栏塞不下自定义 View，
        // 只能自己画一行：标题占满剩余宽度，下拉靠右。
        if (titleTrailing != null) {
            val density = resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            val row = LinearLayout(this@showLogDialog).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(20), dp(12), dp(2))
            }
            val titleTv = TextView(this@showLogDialog).apply {
                text = title
                textSize = 20f
            }
            row.addView(titleTv, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(titleTrailing, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    val builder = AlertDialog.Builder(this).setView(root)
    if (titleTrailing == null) builder.setTitle(title)
    dialog = builder.create()
    dialog.setCancelable(cancelable)
    dialog.show()
}

/**
 * 导出日志到文件。
 * 与调试菜单「💾 导出日志到文件」**完全同一条路径**（都走 [AppLogger.exportToFile]，
 * 落盘在 `Download/Nibnya/Crash_Logs/`），提示文案也一致。
 */
internal fun MainActivity.exportLogsToFile() {
    val file = AppLogger.exportToFile()
    if (file != null) {
        toast(getString(R.string.toast_logs_exported, file.absolutePath))
    } else {
        toast(getString(R.string.msg_no_logs_to_export))
    }
}

/** 复制文本到系统剪贴板（统一入口，顺带给出 toast 反馈） */
internal fun MainActivity.copyToClipboard(label: String, text: String) {
    if (text.isBlank()) {
        toast(getString(R.string.msg_no_logs_to_copy))
        return
    }
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText(label, text))
    toast(getString(R.string.toast_copied))
}
