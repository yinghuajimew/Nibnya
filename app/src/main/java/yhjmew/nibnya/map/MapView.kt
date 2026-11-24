package yhjmew.nibnya.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 可拖动、可缩放的地形图视图（瓦片版）。
 *
 * ## 与旧版（单 Bitmap）的区别
 * 旧版：`setMap(bitmap)` → 整张图画一次，`clampOffset()` 不让它滑出边界。
 * 新版：`setTileSource(src)` → 视图自己算「视野内需要哪几块瓦片」，
 * 缺的异步补，**没有任何边界** —— 滑到哪渲染到哪。
 *
 * ## 坐标模型（照 SeedCraft）
 * 不存 offset，存 **中心方块坐标** `centerX / centerZ` + **缩放 `bpp`**
 * （blocks per pixel，每屏幕像素覆盖多少方块）。
 *
 * 这样比 offset 模型好在：缩放时只要改 bpp，中心不动就自然对准；
 * 用 offset 的话每次缩放都要反算锚点，很容易累积误差。
 *
 * ```
 * 屏幕 x → 方块 x :  centerX + (px - width/2) * bpp
 * 方块 x → 屏幕 x :  width/2 + (blockX - centerX) / bpp
 * ```
 *
 * ## 缩放层级（T3）
 * `bpp` 是连续的，但瓦片按 [SCALES] 量化，避免每个像素都生成一套图。
 * 切换策略**不对称**，理由见 [scaleFor]。
 */
class MapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        /*
         * bpp 范围：0.2 ~ 64。
         *   0.2 → 一个方块占 5 像素，能看清单方块
         *   64  → 一个屏幕像素 64 方块，一屏约 2 万方块（1250 区块）
         */
        private const val MIN_BPP = 0.2f
        private const val MAX_BPP = 64f

        /** 默认 bpp：一屏约 500 方块（30 区块），比旧版 3px/方块差不多。 */
        private const val DEFAULT_BPP = 1.6f

        /**
         * 采样档位（blocks per cell），**2 的幂**。
         *
         * ## 为什么从 4 的幂改成 2 的幂
         *
         * 原来是 `{1, 4, 16, 64, 256}`（相邻差 4 倍）。问题不是档位本身，
         * 而是**一个采样点占多少屏幕像素**会随档位突跳：
         *
         * | bpp  | 选中档 | 采样点屏幕尺寸 |
         * |------|--------|----------------|
         * | 1.4  | 1      | 1.4 px         |
         * | 1.5  | **4**  | **6.0 px**  ← 突跳 |
         * | 5.9  | 4      | 23.6 px（马赛克）|
         * | 6.0  | **16** | **24 px**   ← 突跳 |
         *
         * 2 的幂后每次只变 2 倍，不会有「6 px 突然变 24 px」的断层。
         *
         * ## 为什么最小档是 1
         * SeedCraft 的表是 `{4, 16, 64, 256}`，放大到 `bpp = 0.2`
         * （一方块占 5 屏幕像素）时，一个采样点仍覆盖 4 方块 = 20 px，
         * 是 4×4 的大色块。加 `1` 档之后才能看清单方块。
         *
         * ## 上限为什么是 256
         * BedrockMap 只到 32 是因为它有 overview 图兜底；
         * 我们缩到底没有替代图，所以要能显示 32768 方块/瓦片的稀疏数据。
         *
         * ⚠️ **种子层只支持 1/4/16/64/256**（cubiomes 的硬限制）。
         * 2/8/32/128 这几个中间档由 [SeedTerrainSource.blocksPerCell]
         * 向上钳到最近的合法档，见那里的注释。
         */
        private val SCALES = intArrayOf(1, 2, 4, 8, 16, 32, 64, 128, 256)

        /**
         * 细网格可读的最小屏幕间距（px）。
         *
         * 原来是 10px，意味着 `bpp <= 1.6` 就整级不画细线 ——
         * 而 1:3 这个**最常用的浏览区间** `chunkPx ≈ 5.3`，
         * 细网格正好被排除，用户只能看到每 8 区块一条的粗线。
         *
         * 4px 是「1px 线 + 3px 空隙」，仍然能分辨，不会糊成一片。
         */
        private const val MIN_MINOR_GAP_PX = 4f

        /** 粗网格间隔（区块）。 */
        private const val MAJOR_CHUNKS = 8

        /**
         * 粗网格可读的最小屏幕间距（px）。
         *
         * 粗线间距 = 8 区块 × chunkPx。缩得很远时这个值会小于 1px，
         * 那时画出来的只是一条糊掉的实心线，不如不画。
         */
        private const val MIN_MAJOR_GAP_PX = 4f

        /** 区块描边可读的最小屏幕尺寸（px）。独立阈值，不与网格混用。 */
        private const val MIN_BOUNDARY_CHUNK_PX = 2f

        private const val MINOR_STROKE_PX = 1f
        private const val MAJOR_STROKE_PX = 2f

        /** 块边缘超出视野多少像素时仍算「需要」——预取一圈，滑动时不露白。 */
        private const val PREFETCH_MARGIN_PX = 96f
    }

    // ── 数据源 ──

    /** 主图层（存档地表）。 */
    private var source: TileSource? = null

    /**
     * 底图层（种子地形）。
     *
     * T4 之前一直是 null；接口先留着，实况模式接上时只需要
     * `setBaseSource(seedTiles)`，视图不用改。
     */
    private var baseSource: TileSource? = null

    // ── 视口 ──

    /** 屏幕中心对应的方块坐标。 */
    private var centerX = 0.0
    private var centerZ = 0.0

    /** 每屏幕像素多少方块。 */
    private var bpp = DEFAULT_BPP

    /** 上一次算出的采样档位，用来触发重新请求。 */
    private var activeScale = SCALES[0]

    /** 上一次请求的瓦片签名，避免每帧都重建 wanted 集合。 */
    private var wantedSigSource = ""
    private var wantedSigBase = ""

    /** 没画出来的瓦片数，>0 时显示加载提示。 */
    private var missingTiles = 0
    private var loadTotal = 0
    private var loadDone = 0

    /** 是否显示区块网格。 */
    var showChunkGrid: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 视口变化回调（用于顶部坐标显示）。 */
    var onViewportChanged: ((centerX: Int, centerZ: Int, bpp: Float) -> Unit)? = null

    /**
     * 哪些区块是真实存在的。
     *
     * 用它给「已探索区域」描一圈边 —— 只画线，不参与取色，
     * 所以即使 mask 不全（比如只扫了包围盒）也不会画错颜色。
     */
    interface ChunkMask {
        /**
         * 这个区块是否**查过**（不管有没有地形）。描边用它。
         *
         * 与 [hasChunk] 的区别很重要：
         *   · isKnown   = 扫描过这个区块（纯海面、空区块也算）
         *   · hasChunk  = 这个区块有可显示的地形
         *
         * 用 hasChunk 判断边界的后果是「一格有、一格没有」的棋盘状描边，
         * 因为纯水区块会被当成「没数据」，和周围有数据的区块相邻处
         * 四条边全部显形。
         */
        fun isKnown(cx: Int, cz: Int): Boolean

        /** 这个区块是否**有地形**。 */
        fun hasChunk(cx: Int, cz: Int): Boolean
    }

    /** 区块边界描边用的数据源；null = 不描边。 */
    private var chunkMask: ChunkMask? = null

    private val boundaryPaint = Paint().apply {
        color = 0xE6FF7AB8.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        isAntiAlias = false
    }

    fun setChunkMask(mask: ChunkMask?) {
        chunkMask = mask
        invalidate()
    }

    // ── 画笔 ──

    private val mapPaint = Paint().apply {
        isFilterBitmap = false   // 像素画风格，放大不要糊
        isAntiAlias = false
    }

    private val minorGridPaint = Paint().apply {
        color = 0x66FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = MINOR_STROKE_PX
        isAntiAlias = false
    }

    private val majorGridPaint = Paint().apply {
        color = 0xCCFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = MAJOR_STROKE_PX
        isAntiAlias = false
    }

    private val emptyPaint = Paint().apply {
        color = 0xFF1A1A1A.toInt()   // 未探索区域：深灰，和「有数据」区分
        style = Paint.Style.FILL
        isAntiAlias = false
    }

    private val textPaint = Paint().apply {
        color = 0xCCFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val pillPaint = Paint().apply {
        color = 0xE6141C26.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /** 复用的矩形，避免每帧 new。 */
    private val dst = RectF()
    private val srcRect = Rect()

    // ── 手势 ──

    private val gesture by lazy {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float,
            ): Boolean {
                // 手指往左移（distanceX>0）→ 视野往右走
                centerX += distanceX * bpp
                centerZ += distanceY * bpp
                notifyViewport()
                invalidate()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (abs(bpp - DEFAULT_BPP) < 0.05f) DEFAULT_BPP / 4f else DEFAULT_BPP
                zoomAround(e.x, e.y, target / bpp)
                invalidate()
                return true
            }
        })
    }

    private val scaleGesture by lazy {
        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // detector.scaleFactor > 1 表示两指分开（放大），bpp 要变小
                zoomAround(detector.focusX, detector.focusY, 1f / detector.scaleFactor)
                invalidate()
                return true
            }
        })
    }

    /** 以屏幕点 ([px], [py]) 为锚点缩放：该点下方的方块保持不动。 */
    private fun zoomAround(px: Float, py: Float, bppFactor: Float) {
        val old = bpp
        val target = (old * bppFactor).coerceIn(MIN_BPP, MAX_BPP)
        if (target == old) return

        // 锚点当前对应的方块坐标
        val anchorBlockX = centerX + (px - width / 2f) * old
        val anchorBlockZ = centerZ + (py - height / 2f) * old

        bpp = target

        // 反解新的中心，让同一个方块仍落在 (px,py)
        centerX = anchorBlockX - (px - width / 2f) * target
        centerZ = anchorBlockZ - (py - height / 2f) * target
        notifyViewport()
    }

    // ── 数据接口 ──

    /** 设置主图层。会自动订阅它的完成回调。 */
    fun setTileSource(src: TileSource?) {
        source?.setInvalidation(null)
        source = src
        src?.setInvalidation { post { invalidate() } }
        wantedSigSource = ""
        invalidate()
    }

    /** 设置底图层（种子地形）。可为 null。 */
    fun setBaseSource(src: TileSource?) {
        baseSource?.setInvalidation(null)
        baseSource = src
        src?.setInvalidation { post { invalidate() } }
        wantedSigBase = ""
        invalidate()
    }

    /** 把视口移到指定方块。 */
    fun centerOn(blockX: Int, blockZ: Int) {
        centerX = blockX.toDouble()
        centerZ = blockZ.toDouble()
        notifyViewport()
        invalidate()
    }

    /** 手动设缩放（blocks per pixel）。 */
    fun setBpp(value: Float) {
        bpp = value.coerceIn(MIN_BPP, MAX_BPP)
        notifyViewport()
        invalidate()
    }

    val currentBpp: Float get() = bpp
    val centerBlockX: Int get() = centerX.toInt()
    val centerBlockZ: Int get() = centerZ.toInt()

    private fun notifyViewport() {
        onViewportChanged?.invoke(centerX.toInt(), centerZ.toInt(), bpp)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        notifyViewport()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGesture.onTouchEvent(event)
        if (!scaleGesture.isInProgress) gesture.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return true
    }

    // ── 绘制 ──

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF0E141B.toInt())

        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val src = source
        if (src == null || !src.isReady()) {
            drawCenteredText(canvas, "等待数据…", w / 2f, h / 2f)
            return
        }

        val px = w / 2f
        val py = h / 2f
        activeScale = scaleFor(bpp)

        // 底图层先画，主图层盖上去
        baseSource?.let { if (it.isReady()) drawLayer(canvas, px, py, it, activeScale, isBase = true) }
        drawLayer(canvas, px, py, src, activeScale, isBase = false)

        if (showChunkGrid) drawChunkGrid(canvas, px, py)
        // 边界描边独立于网格开关：网格关掉时更需要它来分辨「探索到哪」
        drawChunkBoundary(canvas, px, py)

        if (missingTiles > 0) drawLoading(canvas, w, h)
    }

    /**
     * 画一层瓦片。
     *
     * 与 SeedCraft `MapView.drawLayer` 对齐，但去掉了「向父级回退」那段：
     * 父级回退靠 `scale / 4` 命中更粗的缓存，只有在缩放切换瞬间有用，
     * 而我们的 [scaleFor] 已经保证不会出现空档（见那里的注释）。
     * 少一段代码就少一处内存泄漏点。
     */
    private fun drawLayer(
        canvas: Canvas,
        px: Float,
        py: Float,
        layer: TileSource,
        scale: Int,
        isBase: Boolean,
    ) {
        val cells = layer.tileCells()
        val cellScale = max(1, layer.blocksPerCell(scale))
        val tileBlocks = cells.toDouble() * cellScale

        // 视野对应的方块范围（含预取边距）
        val halfW = (width / 2f + PREFETCH_MARGIN_PX) * bpp
        val halfH = (height / 2f + PREFETCH_MARGIN_PX) * bpp
        val left = centerX - halfW
        val right = centerX + halfW
        val top = centerZ - halfH
        val bottom = centerZ + halfH

        val tx0 = floor(left / tileBlocks).toInt()
        val tx1 = floor(right / tileBlocks).toInt()
        val tz0 = floor(top / tileBlocks).toInt()
        val tz1 = floor(bottom / tileBlocks).toInt()

        /*
         * 告诉数据源视野中心，供它给请求排优先级（近的先出）。
         *
         * 只对存档层有意义 —— 种子层是 CPU 推演、每块耗时相近，
         * 且都在主线程之外排队，排序收益不明显。
         * 这里统一调用，种子层忽略即可（它没实现 setViewCenter）。
         */
        if (!isBase) {
            (layer as? SaveTiles)?.setViewCenter(
                (tx0 + tx1) / 2,
                (tz0 + tz1) / 2,
            )
        }

        // 更新 wanted（签名没变就不动，避免每帧分配）
        val sig = "${layer.generationId()}:$scale:$cellScale:$tx0:$tx1:$tz0:$tz1"
        if (isBase) {
            if (sig != wantedSigBase) {
                wantedSigBase = sig
                layer.setWanted(buildKeys(layer, scale, tx0, tx1, tz0, tz1))
            }
        } else {
            if (sig != wantedSigSource) {
                wantedSigSource = sig
                layer.setWanted(buildKeys(layer, scale, tx0, tx1, tz0, tz1))
            }
        }

        var missing = 0
        var total = 0
        val oldAlpha = mapPaint.alpha
        if (isBase) mapPaint.alpha = 204   // 推演地形当背景，略透明

        for (tz in tz0..tz1) {
            for (tx in tx0..tx1) {
                val blockX = tx * tileBlocks
                val blockZ = tz * tileBlocks
                val sx = px + ((blockX - centerX) / bpp).toFloat()
                val sy = py + ((blockZ - centerZ) / bpp).toFloat()
                val size = (tileBlocks / bpp).toFloat()
                dst.set(sx, sy, sx + size, sy + size)

                /*
                 * 先判断是否在屏幕内，再决定「画不画」和「算不算进度」。
                 *
                 * ⚠️ 注意顺序：**预取边距内的瓦片必须照常 request**，
                 * 只是不画。
                 * 早期版本在这里写 `if (屏幕外) continue`，结果预取区永远
                 * 不会发起请求 —— 边距形同虚设，一滑动就整屏空白再等半天。
                 */
                val onScreen = dst.right >= 0f && dst.bottom >= 0f &&
                        dst.left <= width && dst.top <= height

                val bmp = layer.get(scale, tx, tz)
                if (bmp != null) {
                    if (onScreen) canvas.drawBitmap(bmp, null, dst, mapPaint)
                    if (onScreen && !isBase) total++
                    continue
                }

                // 没缓存 → 投递。屏内屏外都要投，屏外那群正是预取
                layer.request(scale, tx, tz)
                if (isBase || !onScreen) continue

                total++
                // 没画出来：统一刷成未探索色，避免闪烁的空白
                canvas.drawRect(dst, emptyPaint)
                missing++
            }
        }

        if (isBase) mapPaint.alpha = oldAlpha
        if (!isBase) {
            missingTiles = missing
            loadTotal = total
            loadDone = total - missing
        }
    }

    private fun buildKeys(
        layer: TileSource,
        scale: Int,
        tx0: Int, tx1: Int,
        tz0: Int, tz1: Int,
    ): List<String> {
        val out = ArrayList<String>((tx1 - tx0 + 1) * (tz1 - tz0 + 1))
        for (tz in tz0..tz1) {
            for (tx in tx0..tx1) out.add(layer.key(scale, tx, tz))
        }
        return out
    }

    /**
     * 当前应使用的采样档位。
     *
     * ## 为什么是 4 的幂
     * 相邻档位正好 4 倍，一块 128 采样的瓦片在粗档覆盖 4 倍边长，
     * 于是粗档的 1 块 = 细档的 4×4 块。缩放时视觉上是「逐渐变粗」，
     * 不会有 2 倍那种半粗不细的中间态。
     *
     * ## 阈值为什么是 bpp × 1.5
     * 目标：**一个采样点落在 1.5~6 个屏幕像素上**。
     *   · 小于 1.5px → 采样点比像素还密，白算
     *   · 大于 6px  → 能看出明显的方块感，像马赛克
     * 取 1.5 而不再往上，是为了让「放大」尽早上更细的档（更清晰）；
     * 代价是瓦片生成更频繁，由 LRU + wanted 过滤兜住。
     *
     * ## 与 BedrockMap 的关系
     * BedrockMap 的档位是 2 的幂，且**缩小立即切、放大等 120ms**。
     * 这里只有同步的状态判断（`onDraw` 用了新档位就立刻请求），
     * 即「两个方向都立即」——
     * 因为我们的瓦片来自 LevelDB 而不是 GPU 图集，
     * 没有「旧图集露出灰块」的问题：没画出来的那块直接刷未探索色，
     * 反而比留着旧档更清楚。
     *
     * 如果以后接了 T4 的种子层（CPU 推演，较慢），
     * 再把「放大延迟切换」加进 [SaveTiles.request] 侧即可。
     */
    private fun scaleFor(bpp: Float): Int {
        var s = SCALES[0]
        while (s < SCALES[SCALES.size - 1] && s < bpp * 1.5f) {
            s *= 2
        }
        return s
    }

    /**
     * 区块边界：只描「已存在区块 与 不存在区块 之间」那条线。
     *
     * ## 为什么不用网格代替
     * 网格是均匀的（每 16 方块一条），看不出存档的**形状**；
     * 描边是沿着数据的轮廓走的，一眼就能看出探索到哪、
     * 哪一块是断开的飞地。
     *
     * ## 为什么要有 60000 上限
     * 缩得很远时，一屏可能盖住几十万个区块，
     * 每个都要做 4 次 mask 查询 —— 那是几百万次调用，必然卡顿。
     * 超过上限就直接不画：那个尺度下线条也糊成一片了。
     */
    private fun drawChunkBoundary(canvas: Canvas, px: Float, py: Float) {
        val mask = chunkMask ?: return
        val chunkPx = 16f / bpp
        if (chunkPx < MIN_BOUNDARY_CHUNK_PX) return

        val halfW = width / 2f * bpp
        val halfH = height / 2f * bpp
        val cx0 = floor((centerX - halfW) / 16.0).toInt() - 1
        val cx1 = floor((centerX + halfW) / 16.0).toInt() + 1
        val cz0 = floor((centerZ - halfH) / 16.0).toInt() - 1
        val cz1 = floor((centerZ + halfH) / 16.0).toInt() + 1

        val count = (cx1 - cx0 + 1).toLong() * (cz1 - cz0 + 1)
        if (count > 60_000L) return

        val size = 16f / bpp
        for (cz in cz0..cz1) {
            for (cx in cx0..cx1) {
                if (!mask.isKnown(cx, cz)) continue
                val l = px + ((cx * 16.0 - centerX) / bpp).toFloat()
                val tp = py + ((cz * 16.0 - centerZ) / bpp).toFloat()
                val r = l + size
                val b = tp + size

                /*
                 * 只在「自己在外、邻居不在」的那一侧画线。
                 *
                 * ⚠️ 用 isKnown 而不是 hasChunk。
                 *
                 * hasChunk 问的是「有没有地形」。如果拿它判边界，那么
                 * 「查过但没地形」（纯海面 / 空区块）和「根本没查过」
                 * 表现一样 —— 相邻区块一个在一个不在，四条边全画，
                 * 画出来就是一格一格的**棋盘状 X**。
                 *
                 * isKnown 问的是「查过没有」，边界因此跟着**扫描范围**走，
                 * 连续探索的区域内部不会出现任何线。
                 */
                if (!mask.isKnown(cx - 1, cz)) canvas.drawLine(l, tp, l, b, boundaryPaint)
                if (!mask.isKnown(cx + 1, cz)) canvas.drawLine(r, tp, r, b, boundaryPaint)
                if (!mask.isKnown(cx, cz - 1)) canvas.drawLine(l, tp, r, tp, boundaryPaint)
                if (!mask.isKnown(cx, cz + 1)) canvas.drawLine(l, b, r, b, boundaryPaint)
            }
        }
    }

    /**
     * 区块网格。
     *
     * 与旧版的三点区别：
     * 1. **不再依赖 Bitmap**——线只画在视野内，与瓦片无关
     * 2. **分级降噪**——细线间距 < [MIN_MINOR_GAP_PX] 时整级跳过
     * 3. **粗线每 [MAJOR_CHUNKS] 区块一条**，缩得再小也保留
     *
     * 与 SeedCraft `MapView.drawGrid` 的差别：它把「区块边界」和「网格」
     * 分成两个方法（前者按 mask 描边，后者画线），
     * 这里暂时只做网格；边界描边等 T2 接 ChunkMask 时再加。
     */
    private fun drawChunkGrid(canvas: Canvas, px: Float, py: Float) {
        val chunkPx = 16f / bpp
        if (chunkPx < 1f) return   // 一个区块不到 1px，画了也没信息

        val drawMinor = chunkPx >= MIN_MINOR_GAP_PX

        // 视野内的区块范围
        val halfW = width / 2f * bpp
        val halfH = height / 2f * bpp
        val cx0 = floor((centerX - halfW) / 16.0).toInt()
        val cx1 = floor((centerX + halfW) / 16.0).toInt() + 1
        val cz0 = floor((centerZ - halfH) / 16.0).toInt()
        val cz1 = floor((centerZ + halfH) / 16.0).toInt() + 1

        // 线越多越慢，给个上限。一屏最多约 200×200 区块，够用
        if ((cx1 - cx0).toLong() * (cz1 - cz0) > 250_000L) return

        // ① 细线
        if (drawMinor) {
            for (c in cx0..cx1) {
                if (c % MAJOR_CHUNKS == 0) continue
                val x = px + ((c * 16.0 - centerX) / bpp).toFloat()
                if (x < 0 || x > width) continue
                canvas.drawLine(x, 0f, x, height.toFloat(), minorGridPaint)
            }
            for (r in cz0..cz1) {
                if (r % MAJOR_CHUNKS == 0) continue
                val y = py + ((r * 16.0 - centerZ) / bpp).toFloat()
                if (y < 0 || y > height) continue
                canvas.drawLine(0f, y, width.toFloat(), y, minorGridPaint)
            }
        }

        // ② 粗线 —— 同样过密就不画
        if (chunkPx * MAJOR_CHUNKS < MIN_MAJOR_GAP_PX) return
        for (c in cx0..cx1) {
            if (c % MAJOR_CHUNKS != 0) continue
            val x = px + ((c * 16.0 - centerX) / bpp).toFloat()
            if (x < 0 || x > width) continue
            canvas.drawLine(x, 0f, x, height.toFloat(), majorGridPaint)
        }
        for (r in cz0..cz1) {
            if (r % MAJOR_CHUNKS != 0) continue
            val y = py + ((r * 16.0 - centerZ) / bpp).toFloat()
            if (y < 0 || y > height) continue
            canvas.drawLine(0f, y, width.toFloat(), y, majorGridPaint)
        }
    }

    /** 右上角加载提示。 */
    private fun drawLoading(canvas: Canvas, w: Int, h: Int) {
        val label = if (loadTotal > 0) "加载 $loadDone/$loadTotal" else "加载中"
        textPaint.textSize = 26f
        val tw = textPaint.measureText(label)
        val padH = 22f
        val padV = 12f
        val bw = tw + padH * 2
        val bh = textPaint.textSize + padV * 2
        val left = w - bw - 28f
        val top = 28f
        dst.set(left, top, left + bw, top + bh)
        canvas.drawRoundRect(dst, bh / 2f, bh / 2f, pillPaint)
        val baseline = top + bh / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, left + bw / 2f, baseline, textPaint)
    }

    private fun drawCenteredText(canvas: Canvas, text: String, cx: Float, cy: Float) {
        textPaint.textSize = 32f
        canvas.drawText(text, cx, cy, textPaint)
    }
}
