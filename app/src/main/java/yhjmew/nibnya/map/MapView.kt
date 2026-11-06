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
    }

    private var bitmap: Bitmap? = null

    /** 缩放倍率（相对 Bitmap 的原始像素）。 */
    private var scale = DEFAULT_SCALE

    /** 平移量：Bitmap 左上角相对 View 左上角的偏移。 */
    private var offsetX = 0f
    private var offsetY = 0f

    private val paint = Paint().apply {
        isFilterBitmap = false   // 像素画风格，放大不要糊
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
            offsetX = (width - bmp.width * scale) / 2f
            offsetY = (height - bmp.height * scale) / 2f
        }
        clampOffset()
        invalidate()
    }

    /** 当前缩放倍率，UI 上显示用。 */
    val currentScale: Float get() = scale

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
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
