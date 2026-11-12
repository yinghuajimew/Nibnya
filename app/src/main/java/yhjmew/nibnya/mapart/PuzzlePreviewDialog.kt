package yhjmew.nibnya.mapart

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import yhjmew.nibnya.MainActivity
import yhjmew.nibnya.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 拼图生成前的预览对话框。
 *
 * 两个标签页：
 *   A. 按行列切 —— 缩略图叠红色网格线，+/− 调行列，出 N 张地图
 *   B. 自由裁剪 —— 拖角点/边/内部选择区域，出 1 张地图
 *
 * 两个页面共用一套撤销栈（一步一撤、不限步数）。
 * 只在「操作结束」时记快照（手指抬起 / 点 +− / 切标签页），拖动过程绝不记。
 */
/**
 * +/− 步进的上限。
 *
 * 注意：这**不是**行列的硬限制。用户用「自定义尺寸」填了 300 行，
 * 预览里仍要显示 300 行 —— 之前写成 coerceIn(1, 20) 会把用户的选择悄悄压回 20，
 * 属于把用户输入吃掉，已修。这里只用于 +/− 防误触。
 */
private const val STEP_MAX = 999

/** 标签页编号 */
/** 补边颜色：0 黑 / 1 白 / 2 透明 */
private const val PAD_BLACK = 0
private const val PAD_WHITE = 1
private const val PAD_TRANSPARENT = 2

private const val TAB_GRID = 0
private const val TAB_CROP = 1

internal fun MainActivity.showPuzzlePreview(
    uri: Uri,
    onConfirm: (rows: Int, cols: Int, padEnabled: Boolean, padColor: Int) -> Unit,
    onCropConfirm: (cropRect: RectF, rows: Int, cols: Int) -> Unit,
    onCancel: () -> Unit
) {
    val density = resources.displayMetrics.density
    fun px(v: Int) = (v * density).toInt()

    // 裁剪框最小尺寸（占图片短边比例），由设置决定
    val minFrac = getSharedPreferences("nbt_config", android.content.Context.MODE_PRIVATE)
        .getFloat("puzzle_crop_min", 0.25f)

    val decoded = decodePreviewBitmap(uri, 720)
    if (decoded == null) {
        toast(getString(R.string.toast_image_too_large))
        onCancel()
        return
    }
    val bitmap = if (decoded.width > 720 || decoded.height > 720) {
        val s = 720f / max(decoded.width, decoded.height)
        Bitmap.createScaledBitmap(
            decoded, (decoded.width * s).toInt(), (decoded.height * s).toInt(), true
        ).also { decoded.recycle() }
    } else decoded

    val originalSize = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        o.outWidth to o.outHeight
    }.getOrNull() ?: (bitmap.width to bitmap.height)

    // ---------------- 状态 ----------------
    var mode = TAB_GRID
    var rows = puzzleRows.coerceAtLeast(1)
    var cols = puzzleCols.coerceAtLeast(1)
    var lockRatio = true
    /** 行列模式：不裁剪而是补边（保留完整画面），以及补边颜色 */
    var padEnabled = false
    var padColor = PAD_BLACK
    /** 锁定比例 宽:高（默认 1:1）。行列模式可传 cols:rows。 */
    var ratioW = 1f
    var ratioH = 1f
    // 裁剪框：**原图像素坐标**（不是屏幕坐标），缩放/转屏都不会错位
    //
    // ⚠️ 默认要求「正方形」（lockRatio 初始为 true），
    //    所以初始框不能直接取整张图 —— 非正方形图会一上来就不满足锁定，
    //    必须手动拖一下才被纠正。这里直接取最大内接正方形，居中。
    val crop = RectF()
    run {
        val w = originalSize.first.toFloat()
        val h = originalSize.second.toFloat()
        val side = minOf(w, h)
        val l = (w - side) / 2f
        val t0 = (h - side) / 2f
        crop.set(l, t0, l + side, t0 + side)
    }


    data class Snap(
        val mode: Int, val rows: Int, val cols: Int,
        val cropL: Float, val cropT: Float, val cropR: Float, val cropB: Float
    )

    fun snapshot() = Snap(mode, rows, cols, crop.left, crop.top, crop.right, crop.bottom)

    val undoStack = ArrayDeque<Snap>()
    val redoStack = ArrayDeque<Snap>()

    // ---------------- 视图 ----------------
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(14), px(8), px(14), 0)
    }

    val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val tabGrid = makeTab(getString(R.string.tab_puzzle_grid), true, density)
    val tabCrop = makeTab(getString(R.string.tab_puzzle_crop), false, density)
    tabs.addView(tabGrid)
    tabs.addView(tabCrop)
    root.addView(tabs)

    val previewView = GridPreviewView<Snap>(this, bitmap).apply {
        // 裁剪框用的是原图像素坐标，视图必须知道原图有多大，
        // 否则降采样后的位图会把框画到视图外（右下角拖不到）
        imageW = originalSize.first.toFloat()
        imageH = originalSize.second.toFloat()
        cropImage = RectF(0f, 0f, imageW, imageH)
        this.minFrac = minFrac
    }

    // 撤销 / 重做 / 重置（底部左侧）—— 先建出来，回调里要用
    lateinit var btnUndo: Button
    lateinit var btnRedo: Button

    fun updateUndoButtons() {
        btnUndo.isEnabled = undoStack.isNotEmpty()
        btnRedo.isEnabled = redoStack.isNotEmpty()
        btnUndo.alpha = if (btnUndo.isEnabled) 1f else 0.35f
        btnRedo.alpha = if (btnRedo.isEnabled) 1f else 0.35f
    }

    fun applySnap(s: Snap) {
        mode = s.mode; rows = s.rows; cols = s.cols
        crop.set(s.cropL, s.cropT, s.cropR, s.cropB)
    }

    /** 记一步（调用方保证是"操作结束"时刻） */
    fun pushHistory(prev: Snap) {
        undoStack.addLast(prev)
        redoStack.clear()            // 新操作让重做分支失效
        updateUndoButtons()
    }

    // 行列步进 / 标签切换都要记历史，所以先声明 refresh 再赋值
    var refresh: () -> Unit = {}

    btnUndo = makeTextButton(getString(R.string.btn_undo), density) {
        // 拖动中不可点（按钮已置灰）；万一触发，先把手上的拖动落地
        if (previewView.isDragging) return@makeTextButton
        if (undoStack.isEmpty()) return@makeTextButton
        redoStack.addLast(snapshot())
        applySnap(undoStack.removeLast())
        refresh()
    }
    btnRedo = makeTextButton(getString(R.string.btn_redo), density) {
        if (previewView.isDragging) return@makeTextButton
        if (redoStack.isEmpty()) return@makeTextButton
        undoStack.addLast(snapshot())
        applySnap(redoStack.removeLast())
        refresh()
    }
    val btnReset = makeTextButton(getString(R.string.btn_reset), density) {
        if (previewView.isDragging) return@makeTextButton
        pushHistory(snapshot())
        mode = TAB_GRID
        rows = puzzleRows.coerceAtLeast(1)
        cols = puzzleCols.coerceAtLeast(1)
        crop.set(0f, 0f, originalSize.first.toFloat(), originalSize.second.toFloat())
        refresh()
    }

    val previewSize = (resources.displayMetrics.widthPixels * 0.62f).toInt()
    root.addView(previewView, LinearLayout.LayoutParams(previewSize, previewSize).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        topMargin = px(10)
    })

    val tvInfo = TextView(this).apply {
        textSize = 13f; setTextColor(getColor(R.color.nbt_label))
        setLineSpacing(px(2).toFloat(), 1f)
        setPadding(px(4), px(10), px(4), 0)
    }
    root.addView(tvInfo)

    val tvWarn = TextView(this).apply {
        textSize = 12f; setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.c_danger))
        setPadding(px(4), px(6), px(4), 0)
    }
    root.addView(tvWarn)

    // 行列步进行（仅标签页 A）
    val stepperRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(4), px(10), px(4), 0)
    }
    val tvRows = TextView(this).apply { textSize = 13f }
    val tvCols = TextView(this).apply { textSize = 13f }
    stepperRow.addView(tvRows)
    stepperRow.addView(makeSmallButton("−", density) {
        if (rows > 1) { val p = snapshot(); rows--; pushHistory(p); refresh() }
    })
    stepperRow.addView(makeSmallButton("+", density) {
        if (rows < STEP_MAX) { val p = snapshot(); rows++; pushHistory(p); refresh() }
    })
    stepperRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
    stepperRow.addView(tvCols)
    stepperRow.addView(makeSmallButton("−", density) {
        if (cols > 1) { val p = snapshot(); cols--; pushHistory(p); refresh() }
    })
    stepperRow.addView(makeSmallButton("+", density) {
        if (cols < STEP_MAX) { val p = snapshot(); cols++; pushHistory(p); refresh() }
    })
    root.addView(stepperRow)

    // 比例锁（仅标签页 B）
    val lockRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(4), px(8), px(4), 0)
    }
    val switchLock = android.widget.Switch(this).apply {
        isChecked = true
        text = getString(R.string.msg_puzzle_lock_ratio)
        textSize = 13f
    }
    switchLock.setOnCheckedChangeListener { _, checked ->
        lockRatio = checked
        previewView.lockRatio = checked
        // 打开锁定时立刻把现有框收敛成目标比例，
        // 否则用户要手动拖一下才会生效。以当前中心为基准放大到最大。
        if (checked) fitCropToRatio(
            crop, originalSize.first.toFloat(), originalSize.second.toFloat(),
            cols.toFloat(), rows.toFloat()
        )
        previewView.cropImage = RectF(crop)
        previewView.invalidate()
        refresh()          // 同步文案里的张数/尺寸
    }


    lockRow.addView(switchLock)
    root.addView(lockRow)

    // ---- 行列模式：补边（不裁剪，保留完整画面）----
    val padRow = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(4), px(8), px(4), 0)
    }
    val cbPad = android.widget.CheckBox(this).apply {
        isChecked = false
        text = getString(R.string.msg_puzzle_pad_edges)
        textSize = 13f
    }

    // 颜色三选（黑 / 白 / 透明）
    val colorRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(28), 0, 0, 0)
        visibility = View.GONE
    }
    val rgPad = android.widget.RadioGroup(this).apply {
        orientation = android.widget.RadioGroup.HORIZONTAL
    }
    val rbBlack = android.widget.RadioButton(this).apply {
        id = View.generateViewId(); text = getString(R.string.label_sub_puzzle_pad_black); textSize = 12f
    }
    val rbWhite = android.widget.RadioButton(this).apply {
        id = View.generateViewId(); text = getString(R.string.label_sub_puzzle_pad_white); textSize = 12f
    }
    val rbTrans = android.widget.RadioButton(this).apply {
        id = View.generateViewId(); text = getString(R.string.label_sub_pad_transparent); textSize = 12f
    }
    rgPad.addView(rbBlack); rgPad.addView(rbWhite); rgPad.addView(rbTrans)
    rbBlack.isChecked = true
    colorRow.addView(rgPad)

    fun syncPadToView() {
        previewView.padEnabled = padEnabled
        previewView.padColor = padColor
        previewView.invalidate()
    }

    cbPad.setOnCheckedChangeListener { _, checked ->
        padEnabled = checked
        colorRow.visibility = if (checked) View.VISIBLE else View.GONE
        syncPadToView()
        refresh()
    }
    rgPad.setOnCheckedChangeListener { _, id ->
        padColor = when (id) {
            rbWhite.id -> PAD_WHITE
            rbTrans.id -> PAD_TRANSPARENT
            else -> PAD_BLACK
        }
        syncPadToView()
        refresh()
    }

    padRow.addView(cbPad)
    padRow.addView(colorRow)
    root.addView(padRow)

    // 撤销/重做/重置行
    val undoRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(2), px(6), px(2), 0)
    }
    undoRow.addView(btnUndo)
    undoRow.addView(btnRedo)
    undoRow.addView(btnReset)
    root.addView(undoRow)

    // ---------------- 刷新 ----------------
    /**
     * 把「当前状态」推给视图并刷新界面。
     *
     * ⚠️ updateCrop=false 时不碰视图里的裁剪框 —— 拖动结束后调用 refresh()
     * 若又用旧状态回写，会把用户刚拖出来的结果冲掉（表现为「一拖就复位」）。
     */
    fun refreshInternal(updateCrop: Boolean) {
        previewView.mode = mode
        previewView.rows = rows
        previewView.cols = cols
        if (updateCrop) {
            // 兜底：若裁剪框小于可操作尺寸（例如从撤销历史恢复回来的极框），
            // 自动撑开，避免「太小 → 把手重叠 → 怎么拖都不动 → 只能重置」
            normalizeCrop(crop, originalSize.first.toFloat(), originalSize.second.toFloat(), minFrac)
            previewView.cropImage = RectF(crop)
        }
        previewView.lockRatio = lockRatio
        previewView.ratioW = cols.toFloat()
        previewView.ratioH = rows.toFloat()
        previewView.invalidate()

        val isGrid = mode == TAB_GRID
        // 行列步进两页都显示：rows/cols 是共用的同一组数字，
        // 在哪个标签页调都一样，不用先退回去改再切过来。
        stepperRow.visibility = View.VISIBLE
        lockRow.visibility = if (isGrid) View.GONE else View.VISIBLE
        padRow.visibility = if (isGrid) View.VISIBLE else View.GONE

        setTabStyle(tabGrid, isGrid, density)
        setTabStyle(tabCrop, !isGrid, density)
        tabGrid.setOnClickListener {
            if (mode == TAB_GRID) return@setOnClickListener
            pushHistory(snapshot()); mode = TAB_GRID; refresh()
        }
        tabCrop.setOnClickListener {
            if (mode == TAB_CROP) return@setOnClickListener
            pushHistory(snapshot()); mode = TAB_CROP; refresh()
        }

        // 自由裁剪同样是「裁出的区域 -> 再切成 rows x cols 张」，
        // 所以张数一样是 rows * cols（之前写死 1，选 5x5 也只出一张）
        val total = rows * cols
        val layers = (total + 25) / 26
        // 每张地图对应的原图像素尺寸
        val cellW = if (isGrid) originalSize.first / cols
                    else (cropW(crop) / cols).roundToInt()
        val cellH = if (isGrid) originalSize.second / rows
                    else (cropH(crop) / rows).roundToInt()


        tvRows.text = getString(R.string.msg_puzzle_rows, rows)
        tvCols.text = getString(R.string.msg_puzzle_cols, cols)

        tvInfo.text = if (isGrid) {
            getString(
                R.string.msg_puzzle_preview_info,
                rows, cols, total,
                if (layers <= 1) getString(R.string.msg_puzzle_boxes_one)
                else getString(R.string.msg_puzzle_boxes_many, layers),
                originalSize.first, originalSize.second, cellW, cellH
            )
        } else {
            getString(
                R.string.msg_puzzle_crop_info,
                cropW(crop).roundToInt(), cropH(crop).roundToInt(),
                rows, cols, total
            )

        }

        tvWarn.text = if (isGrid) {
            // 补边时不裁内容，只提示分辨率；不补边才提示会被裁掉多少
            val cropMsg = if (padEnabled) ""
            else cropCoverageNote(originalSize.first, originalSize.second, cols, rows)
            val lowMsg = if (cellW < 128 || cellH < 128)
                getString(R.string.msg_puzzle_low_res) else ""
            listOf(cropMsg, lowMsg).filter { it.isNotEmpty() }.joinToString("\n")
        } else {
            if (lockRatio && kotlin.math.abs(cropW(crop) - cropH(crop)) > 1f)
                getString(R.string.msg_puzzle_crop_not_square) else ""
        }
        updateUndoButtons()
    }

    /** 状态有变化时用（切标签、撤销、重置）—— 同时同步裁剪框 */
    refresh = { refreshInternal(true) }

    // 拖动结束才记历史
    previewView.onDragEnd = {
        // 把用户拖出来的结果写回共享状态，再刷新文字信息（不覆盖裁剪框）
        crop.set(previewView.cropImage)
        pushHistory(previewView.dragStartSnapshot ?: snapshot())
        refreshInternal(false)
    }
    previewView.onDragStart = {
        previewView.dragStartSnapshot = snapshot()
    }
    // 拖动中让撤销按钮不可用（用户要求）
    previewView.onDraggingChanged = { dragging ->
        btnUndo.isEnabled = !dragging && undoStack.isNotEmpty()
        btnRedo.isEnabled = !dragging && redoStack.isNotEmpty()
        btnUndo.alpha = if (btnUndo.isEnabled) 1f else 0.35f
        btnRedo.alpha = if (btnRedo.isEnabled) 1f else 0.35f
    }

    val dialog = AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_puzzle_preview))
        .setView(root)
        .setPositiveButton(R.string.btn_next_pick_slot) { _, _ ->
            if (mode == TAB_GRID) {
                puzzleRows = rows
                puzzleCols = cols
                onConfirm(rows, cols, padEnabled, padColor)
            } else {
                onCropConfirm(RectF(crop), rows, cols)
            }
        }
        .setNegativeButton(R.string.btn_cancel) { _, _ -> onCancel() }
        .setCancelable(false)
        .create()

    dialog.setOnDismissListener { if (!bitmap.isRecycled) bitmap.recycle() }

    dialog.show()
    dialog.window?.setLayout(
        (resources.displayMetrics.widthPixels * 0.92f).toInt(),
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
    refresh()
}

/**
 * 行列模式下的「居中裁剪」会丢掉多少内容。
 *
 * 每块地图必须是 128x128 正方形，所以原图长宽比必须等于 列:行；
 * 否则生成端会居中裁一刀。返回一句给用户看的说明（不需要裁时返回空串）。
 */
private fun MainActivity.cropCoverageNote(
    imgW: Int, imgH: Int, cols: Int, rows: Int
): String {
    if (imgW <= 0 || imgH <= 0 || cols <= 0 || rows <= 0) return ""
    val target = cols.toDouble() / rows.toDouble()
    val cur = imgW.toDouble() / imgH.toDouble()
    if (kotlin.math.abs(cur - target) < 0.005) return ""

    return if (cur > target) {
        // 太宽：裁左右
        val keepRatio = target / cur              // 保留的宽度占比
        getString(R.string.msg_crop_sides, ((1 - keepRatio) * 100).roundToInt())
    } else {
        val keepRatio = cur / target
        getString(R.string.msg_crop_top_bottom, ((1 - keepRatio) * 100).roundToInt())
    }
}

private fun cropW(r: RectF) = r.right - r.left
private fun cropH(r: RectF) = r.bottom - r.top

/**
 * 把裁剪框收敛成「目标比例」的最大内接矩形，并以原框中心对齐。
 *
 * 为什么需要：lockRatio 原本只在拖动时生效，
 * 所以默认初始框（整张图）对非正方形图来说并不满足锁定，
 * 用户必须手动拖一下才会被纠正 —— 看起来像「不会自己调整」。
 *
 * 取「最大」而不是「保持面积」：用户开锁定通常是想尽量多裁内容。
 */
private fun MainActivity.fitCropToRatio(
    r: RectF, imageW: Float, imageH: Float, ratioW: Float, ratioH: Float
) {
    if (imageW <= 0f || imageH <= 0f) return
    val ratio = if (ratioH > 0f) ratioW / ratioH else 1f
    if (ratio <= 0f) return

    // 以图片能容纳的最大尺寸为准
    var w = imageW
    var h = w / ratio
    if (h > imageH) { h = imageH; w = h * ratio }

    // 以当前框中心对齐，再夹回图片内
    val cx = (r.left + r.right) / 2f
    val cy = (r.top + r.bottom) / 2f
    var l = cx - w / 2f
    var t0 = cy - h / 2f
    l = l.coerceIn(0f, (imageW - w).coerceAtLeast(0f))
    t0 = t0.coerceIn(0f, (imageH - h).coerceAtLeast(0f))
    r.set(l, t0, l + w, t0 + h)
}


/**
 * 把裁剪框修正到「可操作」的范围：
 *  · 不小于最小尺寸（图片短边的 8%，且至少约 88dp 屏幕尺寸）
 *  · 不超出图片边界
 *
 * 这样做是为了兜底：万一从撤销历史里恢复出一个极小的框，
 * 四个角把手会重叠在一起，hitTest 就无法区分，导致「怎么拖都拖不动」。
 */
private fun normalizeCrop(r: RectF, imageW: Float, imageH: Float, minFrac: Float) {
    if (imageW <= 0f || imageH <= 0f) return

    val minSize = min(imageW, imageH) * minFrac
    var w = cropW(r).coerceAtLeast(minSize)
    var h = cropH(r).coerceAtLeast(minSize)
    w = w.coerceAtMost(imageW)
    h = h.coerceAtMost(imageH)

    var l = r.left
    var t = r.top
    if (l + w > imageW) l = (imageW - w).coerceAtLeast(0f)
    if (t + h > imageH) t = (imageH - h).coerceAtLeast(0f)
    l = l.coerceAtLeast(0f)
    t = t.coerceAtLeast(0f)

    r.set(l, t, l + w, t + h)
}


private fun MainActivity.makeTab(text: String, selected: Boolean, density: Float): TextView =
    TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
        setTextColor(if (selected) getColor(R.color.select_text) else getColor(R.color.hint_text))
        setBackgroundColor(if (selected) getColor(R.color.select_bg) else Color.TRANSPARENT)
        typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

private fun MainActivity.setTabStyle(tv: TextView, selected: Boolean, density: Float) {
    tv.setTextColor(if (selected) getColor(R.color.select_text) else getColor(R.color.hint_text))
    tv.setBackgroundColor(if (selected) getColor(R.color.select_bg) else Color.TRANSPARENT)
    tv.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    tv.isClickable = true
}

private fun MainActivity.makeSmallButton(text: String, density: Float, onClick: () -> Unit): Button =
    Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        minWidth = 0
        minimumWidth = 0
        setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
        setOnClickListener { onClick() }
    }

private fun MainActivity.makeTextButton(text: String, density: Float, onClick: () -> Unit): Button =
    Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        minWidth = 0
        minimumWidth = 0
        setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
        setTextColor(getColor(R.color.select_text))
        setOnClickListener { onClick() }
    }

/**
 * 预览视图：网格线（标签页 A）/ 可拖动的裁剪框（标签页 B）。
 *
 * 裁剪框存的坐标是**原图像素**，绘制时转换到视图坐标，
 * 这样窗口尺寸变化不会影响裁剪结果。
 */
private class GridPreviewView<T>(ctx: Context, val bitmap: Bitmap) : View(ctx) {
    var mode = TAB_GRID
    var rows = 1
    var cols = 1
    var cropImage: RectF = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        set(v) { field = v; invalidate() }
    var lockRatio = true
    /** 行列模式：true = 补边保留完整画面；false = 居中裁剪（默认） */
    var padEnabled = false
    /** 补边颜色：0 黑 / 1 白 / 2 透明 */
    var padColor = PAD_BLACK
    /** 裁剪框最小尺寸占图片短边的比例（由设置决定） */
    var minFrac = 0.25f
    /** 锁定比例 宽:高（默认 1:1 正方形；行列模式传 列:行） */
    var ratioW = 1f
    var ratioH = 1f

    var isDragging = false
        private set

    // 回调（由对话框设置）
    var onDragStart: (() -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    var onDraggingChanged: ((Boolean) -> Unit)? = null
    var dragStartSnapshot: T? = null

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#FF3B30"); style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val dimPaint = Paint().apply {
        color = Color.parseColor("#99000000"); style = Paint.Style.FILL
    }
    /** 补边填充色（跟随 padColor；透明时画浅灰格纹感的半透明色以便辨认） */
    private val padFillPaint = Paint().apply { style = Paint.Style.FILL }
    private val handlePaint = Paint().apply {
        color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true
    }
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#FFD60A"); style = Paint.Style.STROKE; isAntiAlias = true
    }

    private val dst = RectF()          // 图片在视图里的位置
    private val cropView = RectF()     // 裁剪框在视图里的位置

    /**
     * 原图尺寸（像素）。
     *
     * ⚠️ 裁剪框存的是**原图坐标**，而 [bitmap] 只是为省内存降采样出来的
     * （可能只有 720px）。若用 bitmap.width 当坐标基准，裁剪框会被放大
     * 约 原图宽/720 倍画到视图外（右下角看不见、拖不到），
     * 拖动还会算出负数的取值范围 → coerceIn 抛
     * 「Cannot coerce value to an empty range」崩溃。
     * 所以一切换算都基于这两个值。
     */
    var imageW: Float = bitmap.width.toFloat()
    var imageH: Float = bitmap.height.toFloat()

    /** 原图坐标 → 视图坐标 */
    private fun toView(r: RectF): RectF {
        val sx = dst.width() / imageW
        val sy = dst.height() / imageH
        return RectF(
            dst.left + r.left * sx, dst.top + r.top * sy,
            dst.left + r.right * sx, dst.top + r.bottom * sy
        )
    }

    /** 视图坐标 → 原图坐标 */
    private fun toImage(x: Float, y: Float): Pair<Float, Float> {
        val sx = dst.width() / imageW
        val sy = dst.height() / imageH
        return Pair(
            ((x - dst.left) / sx).coerceIn(0f, imageW),
            ((y - dst.top) / sy).coerceIn(0f, imageH)
        )
    }

    /**
     * 计算 dst（图片在视图里的位置）与 cropView（裁剪框在视图里的位置）。
     *
     * ⚠️ 必须在触摸时也调用一次。之前 cropView 只在 onDraw 里算，
     * 首次触摸时它还是空的 → hitTest 永远返回 0 → 拖动毫无反应。
     */
    private fun computeGeom() {
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val scale = min(vw / bitmap.width, vh / bitmap.height)
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        dst.set((vw - dw) / 2f, (vh - dh) / 2f, (vw + dw) / 2f, (vh + dh) / 2f)
        cropView.set(toView(cropImage))
    }

    /**
     * 行列切割下的「有效区域」（视图坐标）。
     *
     * 生成端会按 列:行 的比例居中裁剪原图，这里算出对应的屏幕矩形，
     * 用于（a）只在有效区内画网格线，（b）把会被裁掉的部分压暗。
     */
    private fun effectiveRect(): RectF {
        if (cols <= 0 || rows <= 0) return RectF(dst)
        val targetRatio = cols.toDouble() / rows.toDouble()
        val curRatio = dst.width().toDouble() / dst.height().toDouble()
        return if (kotlin.math.abs(curRatio - targetRatio) < 0.005) {
            RectF(dst)
        } else if (curRatio > targetRatio) {
            // 视图里图片偏宽 → 裁左右
            val w = (dst.height() * targetRatio).toFloat()
            val l = dst.left + (dst.width() - w) / 2f
            RectF(l, dst.top, l + w, dst.bottom)
        } else {
            // 视图里图片偏高 → 裁上下
            val h = (dst.width() / targetRatio).toFloat()
            val tp = dst.top + (dst.height() - h) / 2f
            RectF(dst.left, tp, dst.right, tp + h)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        computeGeom()
        padFillPaint.color = when (padColor) {
            PAD_WHITE -> Color.WHITE
            PAD_TRANSPARENT -> Color.TRANSPARENT
            else -> Color.BLACK
        }
        canvas.drawBitmap(bitmap, null, dst, bmpPaint)

        if (mode == TAB_GRID) {
            // 每块地图都是 128x128 正方形，所以整图必须先满足 列:行 的比例，
            // 否则每块会被拉成正方形 → 画面变形。
            // 生成时会「居中裁一刀」，这里把**有效区域**画出来：区域内画网格，
            // 区域外压暗（让用户一眼看到会丢掉哪部分）。
            val eff = effectiveRect()
            // 补边：被裁掉的那部分用**真实颜色**填充（黑/白/透明），
            //       让用户直接看到成品效果；不补边：压暗表示「这里会被裁掉」。
            val barPaint = if (padEnabled) padFillPaint else dimPaint
            if (eff.left > dst.left + 0.5f) {
                canvas.drawRect(dst.left, dst.top, eff.left, dst.bottom, barPaint)
            }
            if (eff.right < dst.right - 0.5f) {
                canvas.drawRect(eff.right, dst.top, dst.right, dst.bottom, barPaint)
            }
            if (eff.top > dst.top + 0.5f) {
                canvas.drawRect(eff.left, dst.top, eff.right, eff.top, barPaint)
            }
            if (eff.bottom < dst.bottom - 0.5f) {
                canvas.drawRect(eff.left, eff.bottom, eff.right, dst.bottom, barPaint)
            }

            gridPaint.strokeWidth = max(1.5f, resources.displayMetrics.density * 1.2f)
            for (c in 1 until cols) {
                val x = eff.left + eff.width() * c / cols
                canvas.drawLine(x, eff.top, x, eff.bottom, gridPaint)
            }
            for (r in 1 until rows) {
                val y = eff.top + eff.height() * r / rows
                canvas.drawLine(eff.left, y, eff.right, y, gridPaint)
            }
            gridPaint.strokeWidth = resources.displayMetrics.density * 1.6f
            canvas.drawRect(eff, gridPaint)
        } else {
            // 框外压暗
            canvas.drawRect(dst.left, dst.top, dst.right, cropView.top, dimPaint)
            canvas.drawRect(dst.left, cropView.bottom, dst.right, dst.bottom, dimPaint)
            canvas.drawRect(dst.left, cropView.top, cropView.left, cropView.bottom, dimPaint)
            canvas.drawRect(cropView.right, cropView.top, dst.right, cropView.bottom, dimPaint)

            borderPaint.strokeWidth = resources.displayMetrics.density * 2f
            canvas.drawRect(cropView, borderPaint)

            // 三分线（辅助构图）
            gridPaint.strokeWidth = resources.displayMetrics.density
            gridPaint.alpha = 110
            for (i in 1..2) {
                val x = cropView.left + cropView.width() * i / 3f
                val y = cropView.top + cropView.height() * i / 3f
                canvas.drawLine(x, cropView.top, x, cropView.bottom, gridPaint)
                canvas.drawLine(cropView.left, y, cropView.right, y, gridPaint)
            }
            gridPaint.alpha = 255

            // 四角把手
            val hs = resources.displayMetrics.density * 7f
            for ((hx, hy) in listOf(
                cropView.left to cropView.top, cropView.right to cropView.top,
                cropView.left to cropView.bottom, cropView.right to cropView.bottom
            )) {
                canvas.drawCircle(hx, hy, hs, handlePaint)
                canvas.drawCircle(hx, hy, hs, borderPaint.apply {
                    strokeWidth = resources.displayMetrics.density * 1.2f
                })
            }
        }
    }

    // ---------------- 触摸 ----------------
    private var dragCorner = 0   // 0=none 1=TL 2=TR 3=BL 4=BR 5=move
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragStartView = RectF()

    /**
     * 命中判定（视图坐标）。
     *
     * 注意顺序：**先判内部，再判角点**。
     * 之前先判角点，导致框很小或在框内按下时被误判成拖角点，
     * 表现为「拖框没反应 / 框自己乱缩」。
     */
    private fun hitTest(x: Float, y: Float): Int {
        val hs = resources.displayMetrics.density * 28f
        val dl = kotlin.math.abs(x - cropView.left)
        val dr = kotlin.math.abs(x - cropView.right)
        val dt = kotlin.math.abs(y - cropView.top)
        val db = kotlin.math.abs(y - cropView.bottom)

        // 角点优先（明确要调大小），但必须同时靠近两条边
        if (dl < hs && dt < hs) return 1
        if (dr < hs && dt < hs) return 2
        if (dl < hs && db < hs) return 3
        if (dr < hs && db < hs) return 4

        // 边（只改一个方向）：编号 6=左 7=上 8=右 9=下
        if (dl < hs) return 6
        if (dt < hs) return 7
        if (dr < hs) return 8
        if (db < hs) return 9

        // 框内 → 整体平移
        if (x > cropView.left && x < cropView.right && y > cropView.top && y < cropView.bottom) return 5

        return 0
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (mode != TAB_CROP) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 先算一次几何：cropView 以往只在 onDraw 里更新，
                // 首次触摸时为空，导致 hitTest 永远返回 0（拖不动）
                computeGeom()
                lastX = e.x; lastY = e.y
                downX = e.x; downY = e.y
                dragStartView.set(cropView)
                dragStartImage.set(cropImage)
                dragCorner = hitTest(e.x, e.y)
                // 点框外：把最近的一个角拉过来（更符合直觉）
                if (dragCorner == 0 && dst.contains(e.x, e.y)) {
                    dragCorner = 5
                }
                if (dragCorner != 0) {
                    isDragging = true
                    onDraggingChanged?.invoke(true)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragCorner == 0) return true
                // 传「相对按下点的总位移」，由 applyDrag 从 dragStartImage 推算绝对位置。
                // 之前用增量累加，一旦 cropImage 被外部覆盖就会看起来完全不动。
                val dxTotal = e.x - downX
                val dyTotal = e.y - downY
                lastX = e.x; lastY = e.y
                // 手势运算绝不该把 App 弄崩：算不出来就当作没动
                runCatching { applyDragFromStart(dxTotal, dyTotal) }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    onDraggingChanged?.invoke(false)
                    // 位移太小就当没动（防手抖塞满历史）
                    val moved = kotlin.math.abs(cropImage.left - dragStartImage.left) > 1f ||
                            kotlin.math.abs(cropImage.top - dragStartImage.top) > 1f ||
                            kotlin.math.abs(cropImage.right - dragStartImage.right) > 1f ||
                            kotlin.math.abs(cropImage.bottom - dragStartImage.bottom) > 1f
                    if (moved) onDragEnd?.invoke()
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private val dragStartImage = RectF()

    /**
     * 按「相对按下点的总位移」重新计算裁剪框。
     *
     * 每次都用 dragStartImage（按下那一刻的矩形）做基准，
     * 而不是在 cropImage 上累加增量 —— 累加式一旦基准被外部改掉就会失效。
     */
    private fun applyDragFromStart(dxView: Float, dyView: Float) {
        if (dragStartView.isEmpty || dst.width() <= 0f || dst.height() <= 0f) return
        val sx = imageW / dst.width()
        val sy = imageH / dst.height()
        val dx = dxView * sx
        val dy = dyView * sy

        // 最小尺寸：按**屏幕可抓取尺寸**换算成图片像素。
        // 之前写的是 max(16f, 短边*2%) —— 1024 宽的图上只有 20px，
        // 缩到那么小之后四个角把手互相重叠，hitTest 全部命中同一处，
        // 于是「怎么拖都拖不动，只能撤销/重置」。
        // 这里保证至少约 88dp 见方（手指能稳定抓住），且不小于短边的 8%。
        val minDp = 88f * resources.displayMetrics.density
        val scaleView = if (dst.width() > 0f) imageW / dst.width() else 1f
        val minSize = max(
            minDp * scaleView,
            min(imageW, imageH) * minFrac
        ).coerceAtMost(min(imageW, imageH))

        var l = dragStartImage.left; var t0 = dragStartImage.top
        var r = dragStartImage.right; var b = dragStartImage.bottom

        when (dragCorner) {
            1 -> { l += dx; t0 += dy }   // 左上
            2 -> { r += dx; t0 += dy }   // 右上
            3 -> { l += dx; b += dy }    // 左下
            4 -> { r += dx; b += dy }    // 右下
            6 -> { l += dx }             // 左边
            7 -> { t0 += dy }            // 上边
            8 -> { r += dx }             // 右边
            9 -> { b += dy }             // 下边
            else -> {                    // 整体平移
                val w = (r - l).coerceAtMost(imageW)
                val h = (b - t0).coerceAtMost(imageH)
                l = (l + dx).coerceIn(0f, max(0f, imageW - w))
                t0 = (t0 + dy).coerceIn(0f, max(0f, imageH - h))
                r = l + w; b = t0 + h
            }
        }

        // 夹紧到图内
        l = l.coerceIn(0f, imageW)
        t0 = t0.coerceIn(0f, imageH)
        r = r.coerceIn(0f, imageW)
        b = b.coerceIn(0f, imageH)

        // 最小尺寸（按被拖动的那条边推回去）
        if (r - l < minSize) {
            if (dragCorner == 1 || dragCorner == 3) l = max(0f, r - minSize)
            else r = min(imageW, l + minSize)
        }
        if (b - t0 < minSize) {
            if (dragCorner == 1 || dragCorner == 2) t0 = max(0f, b - minSize)
            else b = min(imageH, t0 + minSize)
        }

        if (lockRatio) {
            val ratio = if (ratioH > 0f) ratioW / ratioH else 1f
            var w = r - l
            var h = b - t0
            if (h <= 0f) h = 1f
            if (w / h > ratio) w = h * ratio else h = w / ratio

            when (dragCorner) {
                1 -> { l = r - w; t0 = b - h }
                2 -> { r = l + w; t0 = b - h }
                3 -> { l = r - w; b = t0 + h }
                else -> { r = l + w; b = t0 + h }
            }
            // 先推回左上，再限右下，避免产生负范围
            if (l < 0f) { r -= l; l = 0f }
            if (t0 < 0f) { b -= t0; t0 = 0f }
            if (r > imageW) { l -= (r - imageW); r = imageW }
            if (b > imageH) { t0 -= (b - imageH); b = imageH }
            l = l.coerceAtLeast(0f); t0 = t0.coerceAtLeast(0f)
        }

        cropImage.set(l, t0, r, b)
    }
}

/** 解码预览用缩略图（限制最大边，避免大图 OOM） */
private fun MainActivity.decodePreviewBitmap(uri: Uri, maxSize: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSize * 2 || bounds.outHeight / sample > maxSize * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (_: Exception) {
        null
    }
}

