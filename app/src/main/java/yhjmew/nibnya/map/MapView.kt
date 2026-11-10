package yhjmew.nibnya.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 可拖动、可缩放的地形图视图。
 *
 * 第一版刻意**不做瓦片缓存**：整张图就是一块 Bitmap，直接在 Canvas 上缩放绘制。
 * 128×128 只有 64 KB，放大到 64×64 区块也才 1 MB，没必要为此引入视口裁剪。
 * 等以后支持全图再考虑分块。
 *
 * 交互：
 * - 单指拖动 → 平移
 * - 双指 → 缩放（以两指中点为锚）
 * - 双击 → 在 1x 与 2x 之间切换
 */
class MapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        private const val MIN_SCALE = 0.5f
        private const val MAX_SCALE = 24f

        /** 方块→屏幕像素的默认倍率。1px 一个方块在手机上看太小，默认放大 3 倍。 */
        private const val DEFAULT_SCALE = 3f

        /** 1 区块 = 16 方块 = Bitmap 上的 16 像素。 */
        private const val CHUNK_PX = 16

        /**
         * 细网格（每区块一条）可读的最小屏幕间距。
         * 低于这个值时线条会挤成一片白雾，反而看不清地形 —— 这时只画粗线。
         */
        private const val MIN_MINOR_GAP_PX = 10f

        /**
         * 粗网格的间隔（区块数）。
         *
         * 取自 BedrockMap：它每 32 区块画一条 3px 粗线、每区块一条 1px 细线
         * （`mapoverlayquickitem.cpp:updateGridNode`）。我们一屏通常只画
         * 17×17 区块，32 太大，缩到 4 —— 正好 4×4 个「大格」，
         * 数格子时不容易数花眼。
         */
        private const val MAJOR_CHUNKS = 4

        /** 细线在屏幕上的宽度（px）。 */
        private const val MINOR_STROKE_PX = 1f

        /** 粗线在屏幕上的宽度（px）。 */
        private const val MAJOR_STROKE_PX = 2f
    }

    private var bitmap: Bitmap? = null

    /** 是否显示区块网格。 */
    var showChunkGrid: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 缩放倍率（相对 Bitmap 的原始像素）。 */
    private var scale = DEFAULT_SCALE

    /** 平移量：Bitmap 左上角相对 View 左上角的偏移。 */
    private var offsetX = 0f
    private var offsetY = 0f

    private val paint = Paint().apply {
        isFilterBitmap = false   // 像素画风格，放大不要糊
        isAntiAlias = false
    }

    /**
     * 区块网格画笔。
     *
     * ⚠️ 线宽按**屏幕像素**给，所以画之前要把画布缩回去
     * （见 [drawChunkGrid]）—— 否则线宽会被 scale 放大，
     * 放大到 8 倍时 1px 的线会变成 8px 的白带子。
     */
    private val minorGridPaint = Paint().apply {
        color = 0x66FFFFFF.toInt()   // 白，40% 透明
        style = Paint.Style.STROKE
        strokeWidth = MINOR_STROKE_PX
        isAntiAlias = false
    }

    /** 粗网格（每 [MAJOR_CHUNKS] 区块一条），用不透明的白，方便数格子。 */
    private val majorGridPaint = Paint().apply {
        color = 0xCCFFFFFF.toInt()   // 白，80% 透明
        style = Paint.Style.STROKE
        strokeWidth = MAJOR_STROKE_PX
        isAntiAlias = false
    }

    private val emptyPaint = Paint().apply {
        color = context.getColor(android.R.color.darker_gray)
        isAntiAlias = true
    }

    private val gesture by lazy {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float,
            ): Boolean {
                offsetX -= distanceX
                offsetY -= distanceY
                clampOffset()
                invalidate()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 在默认倍率和 2 倍之间切换，以双击点为中心
                val target = if (abs(scale - DEFAULT_SCALE) < 0.01f) DEFAULT_SCALE * 2f else DEFAULT_SCALE
                zoomAround(e.x, e.y, target / scale)
                invalidate()
                return true
            }
        })
    }

    private val scaleGesture by lazy {
        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomAround(detector.focusX, detector.focusY, detector.scaleFactor)
                invalidate()
                return true
            }
        })
    }

    /**
     * 以 ([px], [py]) 为锚点缩放 [factor] 倍。
     * 锚点在屏幕上的位置保持不变，符合直觉。
     */
    private fun zoomAround(px: Float, py: Float, factor: Float) {
        val old = scale
        val target = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        if (target == old) return
        val ratio = target / old
        offsetX = px - (px - offsetX) * ratio
        offsetY = py - (py - offsetY) * ratio
        scale = target
        clampOffset()
    }

    /** 图比视图小的时候居中，比视图大时不让它完全滑出屏幕。 */
    private fun clampOffset() {
        val bmp = bitmap ?: return
        val scaledW = bmp.width * scale
        val scaledH = bmp.height * scale

        offsetX = if (scaledW <= width) {
            (width - scaledW) / 2f
        } else {
            offsetX.coerceIn(width - scaledW, 0f)
        }
        offsetY = if (scaledH <= height) {
            (height - scaledH) / 2f
        } else {
            offsetY.coerceIn(height - scaledH, 0f)
        }
    }

    /** 设置要显示的图，并重置到默认缩放、居中。 */
    fun setMap(bmp: Bitmap?) {
        bitmap = bmp
        scale = DEFAULT_SCALE
        if (bmp != null) {
            /*
             * ⚠️ 只有 View 已经测量过才能算居中偏移。
             *
             * 渲染是异步的，结果回来时 View 通常已经有尺寸；
             * 但如果 width/height 还是 0（页面刚切换、还没 layout），
             * 算出来的 offsetX 会是 -bmp.width*scale/2，图片就贴到左上角外。
             * 这种情况先标脏，等 onSizeChanged 再居中。
             */
            if (width > 0 && height > 0) {
                offsetX = (width - bmp.width * scale) / 2f
                offsetY = (height - bmp.height * scale) / 2f
                clampOffset()
            } else {
                needsCenter = true
            }
        }
        invalidate()
    }

    /** View 尺寸还没准备好时，等 layout 完成再居中。 */
    private var needsCenter = false

    /** 当前缩放倍率，UI 上显示用。 */
    val currentScale: Float get() = scale

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (needsCenter && w > 0 && h > 0) {
            bitmap?.let { bmp ->
                offsetX = (w - bmp.width * scale) / 2f
                offsetY = (h - bmp.height * scale) / 2f
            }
            needsCenter = false
        }
        clampOffset()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap
        if (bmp == null || bmp.isRecycled) {
            canvas.drawText(
                "暂无地形数据", width / 2f, height / 2f, emptyPaint.apply {
                    textSize = 32f
                    textAlign = Paint.Align.CENTER
                }
            )
            return
        }
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bmp, 0f, 0f, paint)
        canvas.restore()

        if (showChunkGrid) drawChunkGrid(canvas, bmp)
    }

    /**
     * 画区块网格。
     *
     * 1 区块 = 16 方块 = Bitmap 上 16px，所以每 16px 一条线。
     *
     * 分两级（参考 BedrockMap 的 `updateGridNode`）：
     * - **细线**：每区块一条，白色 40%。缩得太小时（屏幕间距 < [MIN_MINOR_GAP_PX]）
     *   直接不画，否则整块图会糊成一片白。
     * - **粗线**：每 [MAJOR_CHUNKS] 区块一条，白色 80%、2px。缩得再小也画，
     *   保证任何时候都还能看出「大格」。
     *
     * ## ⚠️ 坐标系必须和 onDraw 里画地形图的那段一致
     *
     * onDraw 是这么画的：
     * ```
     * canvas.translate(offsetX, offsetY)
     * canvas.scale(scale, scale)      ← 关键
     * canvas.drawBitmap(bmp, 0f, 0f)
     * ```
     * 地形图因此被放大了 scale 倍。
     *
     * 第一版这里只 translate 没 scale，于是网格按 1:1 的 Bitmap 像素画，
     * 而地形按 scale 放大 —— 网格只覆盖了图片左上角 1/scale 的一块，
     * 屏幕上表现为「网格只画了左上角一小片」。
     *
     * 现在两种做法里选了更简单的一种：
     * **线的坐标自己乘 scale，全部用屏幕坐标画**。
     * 这样线宽就是屏幕像素，不用再除以 scale。
     */
    private fun drawChunkGrid(canvas: Canvas, bmp: Bitmap) {
        // 可见区域换算成 **Bitmap 像素**坐标，用来决定要画哪些线
        val leftBmp = (-offsetX / scale).coerceIn(0f, bmp.width.toFloat())
        val topBmp = (-offsetY / scale).coerceIn(0f, bmp.height.toFloat())
        val rightBmp = ((width - offsetX) / scale).coerceIn(0f, bmp.width.toFloat())
        val bottomBmp = ((height - offsetY) / scale).coerceIn(0f, bmp.height.toFloat())
        if (rightBmp <= leftBmp || bottomBmp <= topBmp) return

        // 块数向上取整：1950px 的图有 122 个区块（最后一个是半截），
        // 用整数除法会算成 121，少画一条右边的边界线。
        val chunkW = (bmp.width + CHUNK_PX - 1) / CHUNK_PX
        val chunkH = (bmp.height + CHUNK_PX - 1) / CHUNK_PX

        // 只画可见范围内的线：一屏几十条，没必要遍历整张图
        val firstCol = max(0, (leftBmp / CHUNK_PX).toInt())
        val lastCol = min(chunkW, (rightBmp / CHUNK_PX).toInt() + 1)
        val firstRow = max(0, (topBmp / CHUNK_PX).toInt())
        val lastRow = min(chunkH, (bottomBmp / CHUNK_PX).toInt() + 1)
        if (firstCol > lastCol || firstRow > lastRow) return

        // 屏幕上的区块间距 = 16 * scale
        val gapPx = CHUNK_PX * scale
        val drawMinor = gapPx >= MIN_MINOR_GAP_PX

        // 线的端点用**屏幕坐标**，和地形图的绘制范围一致
        // 图片可能比 View 小（居中显示），所以还要和 View 边界取交集，
        // 否则线会画到图片外面、落到灰底上。
        val clipLeft = max(leftBmp * scale + offsetX, 0f)
        val clipRight = min(rightBmp * scale + offsetX, width.toFloat())
        val clipTop = max(topBmp * scale + offsetY, 0f)
        val clipBottom = min(bottomBmp * scale + offsetY, height.toFloat())

        // 线宽就是屏幕像素（不缩放画布，所以不用除 scale）
        minorGridPaint.strokeWidth = MINOR_STROKE_PX
        majorGridPaint.strokeWidth = MAJOR_STROKE_PX

        // ① 细线（每区块）
        if (drawMinor) {
            for (c in firstCol..lastCol) {
                if (c % MAJOR_CHUNKS == 0) continue   // 粗线位置跳过，避免重叠发灰
                val x = c * CHUNK_PX * scale + offsetX
                canvas.drawLine(x, clipTop, x, clipBottom, minorGridPaint)
            }
            for (r in firstRow..lastRow) {
                if (r % MAJOR_CHUNKS == 0) continue
                val y = r * CHUNK_PX * scale + offsetY
                canvas.drawLine(clipLeft, y, clipRight, y, minorGridPaint)
            }
        }

        // ② 粗线（每 MAJOR_CHUNKS 区块）。缩得再小也画。
        for (c in firstCol..lastCol) {
            if (c % MAJOR_CHUNKS != 0) continue
            val x = c * CHUNK_PX * scale + offsetX
            canvas.drawLine(x, clipTop, x, clipBottom, majorGridPaint)
        }
        for (r in firstRow..lastRow) {
            if (r % MAJOR_CHUNKS != 0) continue
            val y = r * CHUNK_PX * scale + offsetY
            canvas.drawLine(clipLeft, y, clipRight, y, majorGridPaint)
        }

        // 图边缘补一条线：最后一个区块可能不满 16px，上面的循环到不了图的右/下边缘
        if (chunkW * CHUNK_PX > bmp.width) {
            val x = bmp.width * scale + offsetX
            canvas.drawLine(x, clipTop, x, clipBottom, majorGridPaint)
        }
        if (chunkH * CHUNK_PX > bmp.height) {
            val y = bmp.height * scale + offsetY
            canvas.drawLine(clipLeft, y, clipRight, y, majorGridPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGesture.onTouchEvent(event)
        // 缩放中不要同时平移，否则两手会互相打架
        if (!scaleGesture.isInProgress) gesture.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return true
    }
}
