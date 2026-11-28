package yhjmew.nibnya.map

import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yhjmew.nibnya.MainActivity
import yhjmew.nibnya.R
import yhjmew.nibnya.util.AppLogger
import yhjmew.nibnya.world.WorldViewModel

/**
 * 「地图」页控制器。
 *
 * ## 交互
 * 1. **不单独选世界**：跟随主页的 `currentWorldFolder`。
 * 2. **没有「生成地形」按钮**：切到地图页就自动加载。
 * 3. **范围 = 预加载半径**（2026-11-19 改）：
 *    - 旧语义「渲染多大一张图」已经不成立了 —— 图是瓦片，没有边界
 *    - 现在选的是「打开世界后先预热多大范围」，滑出这个范围只是首次慢一拍
 *    - `自动` = 完全不预取，只渲染视野内
 *
 * ## 从「一次性渲染」到「瓦片」的迁移
 * 旧流程：`session.render()` → 一张 Bitmap → `mapView.setMap(bmp)`
 * 新流程：`session.createTiles()` → `SaveTiles` → `mapView.setTileSource(tiles)`
 *         然后视图自己按视野请求瓦片，控制器只负责 `centerOn` 和预取。
 *
 * 预取用**空闲时逐块 request** 的方式做，而不是一次性 `request` 全范围：
 * `SaveTiles` 只有 2 个线程，一次性投几千个任务会让首屏都排到后面去。
 */
class MapPageController(
    private val activity: MainActivity,
    private val vm: WorldViewModel,
) {

    private val mapView: MapView? = activity.findViewById(R.id.map_view)
    private val tvWorld: TextView? = activity.findViewById(R.id.tv_map_world)
    private val tvStatus: TextView? = activity.findViewById(R.id.tv_map_status)
    private val tvRadius: TextView? = activity.findViewById(R.id.tv_map_radius)
    private val tvGrid: TextView? = activity.findViewById(R.id.tv_map_grid)
    private val tvReadout: TextView? = activity.findViewById(R.id.tv_map_readout)
    private val tvDim: TextView? = activity.findViewById(R.id.tv_map_dim)
    private val emptyOverlay: android.view.View? = activity.findViewById(R.id.layout_map_empty)

    /** 当前维度（0 主世界 / 1 下界 / 2 末地）。 */
    private var currentDim: Int = MapSettings.DIM_OVERWORLD

    /** 当前世界目录。null = 主页还没打开世界。 */
    private var folder: String? = null

    /** 预加载半径（区块）。null = 自动（不预取）。 */
    private var preloadRadius: Int? = null

    private var session: MapSession? = null

    /** 地图准备任务（开库、定位、接瓦片）。 */
    private var loadJob: Job? = null

    /** 预取任务。滑动 / 换世界时要取消。 */
    private var preloadJob: Job? = null

    /** 最近一次成功打开的世界，避免重复打开同一个。 */
    private var loadedFolder: String? = null

    /** 页面当前是否可见。底部导航切走时不加载。 */
    private var visible = false

    init {
        sweepStaleCopies()

        tvRadius?.setOnClickListener { showRadiusMenu() }
        updateRadiusLabel()

        currentDim = MapSettings.dimension(activity)
        updateDimLabel()
        tvDim?.setOnClickListener { showDimMenu() }

        // 视口读数：中心坐标 + 缩放
        mapView?.onViewportChanged = { cx, cz, bpp ->
            tvReadout?.text = "x $cx  z $cz  ·  ${formatBpp(bpp)}"
        }
        // 没有世界时不显示读数 —— 空页面上显示「x 0 z 0」只会让人困惑
        setReadoutVisible(false)

        // 区块网格开关。状态存 prefs，下次进来保持。
        // ⚠️ 外观必须区分开/关两种状态：旧版只改文字颜色，
        // 用户反馈「看不出现在是开还是关」。
        var gridOn = activity.prefs?.getBoolean("map_show_grid", false) ?: false
        fun applyGrid() {
            mapView?.showChunkGrid = gridOn
            tvGrid?.text = activity.getString(
                if (gridOn) R.string.map_grid_on else R.string.map_grid_off
            )
            tvGrid?.setTextColor(
                activity.getColor(
                    if (gridOn) R.color.c_link_lang else R.color.app_text_secondary
                )
            )
            tvGrid?.setBackgroundResource(
                if (gridOn) R.drawable.bg_map_chip_on else 0
            )
        }
        applyGrid()
        tvGrid?.setOnClickListener {
            gridOn = !gridOn
            activity.prefs?.edit()?.putBoolean("map_show_grid", gridOn)?.apply()
            applyGrid()
        }

        // 跟随主页世界
        activity.lifecycleScope.launch {
            vm.currentWorldFolder.collect { f ->
                val changed = f != folder
                folder = f
                updateWorldLabel()
                if (changed) {
                    activity.lifecycleScope.launch {
                        dropSessionAndWait()
                        if (visible && folder == f && f != null) openWorld()
                    }
                    return@collect
                }
                if (visible && f != null && f != loadedFolder) openWorld()
            }
        }

        activity.lifecycleScope.launch {
            vm.worldList.collect { updateWorldLabel() }
        }
    }

    /**
     * 重新读取两个描边开关并应用。
     *
     * 设置页改完直接调它 —— 描边只是 MapView 里的两个布尔值，
     * 下一帧就生效，不必重开地图页（与缓存/线程数不同）。
     */
    fun applyBoundary() {
        mapView?.setBoundaryVisible(
            loaded = MapSettings.loadedBoundaryEnabled(activity),
            visited = MapSettings.visitedBoundaryEnabled(activity),
        )
    }

    /** 底部导航切到地图页时调用。 */
    fun onShown() {
        visible = true

        /*
         * 没有世界时，视口回到原点并且**禁用拖动/缩放**。
         *
         * 以前这里只改状态栏文字就 return，但 MapView 仍然吃触摸事件 ——
         * 用户在一片黑的空页面上随手一滑，坐标读数就开始跑（截图里
         * x -68 z -287 → x -114 z -309），看起来像地图在动，
         * 实际上什么数据都没有。
         *
         * 锁住手势比「允许滑但什么都不画」诚实：页面本来就是空的。
         */
        if (folder == null) {
            mapView?.setGesturesEnabled(false)
            mapView?.resetViewport()
            setReadoutVisible(false)
            showEmptyOverlay(true)
            /*
             * 不调用 setStatus：空态层本身已经写着「点击选择存档」，
             * 再冒一个「请先在主页打开一个世界」会自相矛盾 ——
             * 现在根本不需要回主页。
             */
            return
        }
        showEmptyOverlay(false)
        mapView?.setGesturesEnabled(true)
        if (folder != loadedFolder) openWorld()
    }

    fun onHidden() {
        visible = false
        // 页面不可见时停掉预取，省电省 IO；瓦片缓存保留，回来还是热的
        preloadJob?.cancel()
        preloadJob = null
    }

    // ── 预加载半径菜单 ──

    /**
     * 让用户选一个存档并打开地图。
     *
     * 由 [MainActivity] 在「地图页黑色区域被点击」时调用 ——
     * 走的是和侧边栏统一的那套 ensureDbLoaded 流程，
     * 所以选完档不需要再回主页点一次。
     */
    fun pickWorld() {
        activity.pickWorldForMap()
    }

    private fun showEmptyOverlay(on: Boolean) {
        emptyOverlay?.visibility =
            if (on) android.view.View.VISIBLE else android.view.View.GONE
    }

    // ── 维度菜单 ──

    private fun updateDimLabel() {
        tvDim?.text = activity.getString(
            when (currentDim) {
                MapSettings.DIM_NETHER -> R.string.map_dim_nether
                MapSettings.DIM_END -> R.string.map_dim_end
                else -> R.string.map_dim_overworld
            }
        )
    }

    private fun showDimMenu() {
        val labels = arrayOf(
            activity.getString(R.string.map_dim_overworld),
            activity.getString(R.string.map_dim_nether),
            activity.getString(R.string.map_dim_end),
        )
        android.app.AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.map_dim_title))
            .setSingleChoiceItems(labels, currentDim) { dialog, which ->
                dialog.dismiss()
                if (which == currentDim) return@setSingleChoiceItems
                currentDim = which
                MapSettings.setDimension(activity, which)
                updateDimLabel()
                /*
                 * 换维度只需改数据源的维度，**不用重新 openWorld()**：
                 *
                 *   · 各维度的瓦片键本来就不同，共存于 LRU 里
                 *   · 重开要走 MapSession.open()，那里面有一次 Shizuku
                 *     拷贝 + 全库扫描，日志里实测阻塞主线程 2016ms → ANR
                 *
                 * 但锚点还是得重算：优先用这个维度存档里的第一个区块，
                 * 没有就用种子层出生点。
                 */
                switchDimension(which)
            }
            .setNegativeButton(activity.getString(R.string.btn_cancel), null)
            .show()
    }

    /**
     * 只切维度，不重开数据库。
     *
     * 这是「来回切维度会 OOM / ANR」的正面修复：以前直接 openWorld()，
     * 每次都要重走 Shizuku 拷贝 + 全库扫描，还会把其他维度的瓦片全丢掉。
     */
    private fun switchDimension(dim: Int) {
        val s = session ?: run { openWorld(); return }
        val tiles = s.tilesOrNull() ?: run { openWorld(); return }

        loadJob?.cancel()
        loadJob = activity.lifecycleScope.launch {
            try {
                setStatus(activity.getString(R.string.map_status_preparing))
                val anchor = withContext(Dispatchers.IO) {
                    s.setDimension(dim)
                    s.anchorChunk(dim)
                        ?: s.seedSourceOrNull()?.spawnPoint()?.let {
                            Pair(floorDiv(it.first, 16), floorDiv(it.second, 16))
                        }
                }
                if (anchor == null) {
                    setStatus(activity.getString(R.string.map_status_no_data))
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    // 维度变了，旧视图的 wanted/缓存签名都要重算
                    mapView?.setTileSource(null)
                    mapView?.setBaseSource(null)
                    mapView?.setTileSource(tiles)
                    mapView?.setBaseSource(s.seedSourceOrNull())
                    mapView?.centerOn(anchor.first * 16 + 8, anchor.second * 16 + 8)
                    mapView?.setGesturesEnabled(true)
                    setReadoutVisible(true)
                }
                schedulePreload()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.error("MapPage", "切换维度失败", e)
                setStatus(activity.getString(R.string.map_status_failed, e.message ?: "unknown"))
            }
        }
    }

    private fun showRadiusMenu() {
        // 第一项是自动，其余是固定半径（区块）
        val presets = intArrayOf(8, 16, 32, 64, 128)
        val labels = ArrayList<String>(presets.size + 2)
        labels.add(activity.getString(R.string.map_radius_auto))

        val values = ArrayList<Int?>(presets.size + 1)
        values.add(null)
        presets.forEach {
            labels.add(activity.getString(R.string.map_radius_fmt, it))
            values.add(it)
        }
        labels.add(activity.getString(R.string.map_radius_custom))
        values.add(null)   // 占位，单独处理

        val cur = preloadRadius
        val checked = if (cur == null) 0
        else presets.indexOf(cur).let { if (it < 0) 0 else it + 1 }

        val items = labels.toTypedArray()
        android.app.AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.map_radius_title))
            .setSingleChoiceItems(items, checked) { dialog, which ->
                dialog.dismiss()
                if (which == items.size - 1) {
                    askCustomRadius()
                } else {
                    preloadRadius = values[which]
                    updateRadiusLabel()
                    schedulePreload()
                }
            }
            .setNegativeButton(activity.getString(R.string.btn_cancel), null)
            .show()
    }

    /** 自定义半径：弹输入框。 */
    private fun askCustomRadius() {
        val edit = android.widget.EditText(activity).apply {
            hint = activity.getString(R.string.map_radius_custom_hint)
            setText((preloadRadius ?: PRELOAD_DEFAULT).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(48, 32, 48, 32)
        }
        android.app.AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.map_radius_custom_title))
            .setView(edit)
            .setPositiveButton(activity.getString(R.string.btn_confirm)) { _, _ ->
                val v = edit.text.toString().toIntOrNull()
                if (v == null || v <= 0) {
                    toast(activity.getString(R.string.map_radius_custom_invalid))
                    return@setPositiveButton
                }
                preloadRadius = v.coerceAtMost(PRELOAD_MAX)
                updateRadiusLabel()
                schedulePreload()
            }
            .setNegativeButton(activity.getString(R.string.btn_cancel), null)
            .show()
    }

    private fun updateRadiusLabel() {
        val r = preloadRadius
        tvRadius?.text = if (r == null) {
            activity.getString(R.string.map_radius_auto)
        } else {
            activity.getString(R.string.map_radius_fmt, r)
        }
    }

    private fun updateWorldLabel() {
        val f = folder
        if (f == null) {
            tvWorld?.text = activity.getString(R.string.map_no_world)
        } else {
            val name = vm.worldList.value.firstOrNull { it.folderName == f }?.displayName ?: f
            tvWorld?.text = name
        }
    }

    // ── 打开世界 ──

    /**
     * 打开世界并接上瓦片。
     *
     * 这里只做「开库 + 定位 + 挂数据 source」，
     * **不画任何东西** —— 画由 MapView 按视野自己发起。
     */
    private fun openWorld() {
        val f = folder ?: run {
            setStatus(activity.getString(R.string.map_status_waiting_world))
            return
        }

        loadJob?.cancel()
        loadJob = activity.lifecycleScope.launch {
            try {
                setStatus(activity.getString(R.string.map_status_preparing))
                AppLogger.info("MapPage", "打开世界 $f 的地图")

                /*
                 * ⚠️ open() 必须在 IO 线程。
                 *
                 * 它内部要复制整个 db 目录，Android/data 下走 Shizuku 时
                 * 还要等一次远程进程退出。lifecycleScope.launch 默认是主线程，
                 * 日志里实测阻塞 2016ms → 直接 ANR（用户 2026-11-28 提供）。
                 */
                val s = session ?: withContext(Dispatchers.IO) {
                    MapSession.open(activity, vm, f)
                }.also { session = it }

                /*
                 * ⚠️ 这三件事都必须离开主线程，否则首帧卡住 → ANR：
                 *   · anchorChunk  → 全库扫一遍求包围盒（可能是几十万键）
                 *   · createTiles  → 打开 LevelDB + 解析两个配色 JSON
                 *   · hasChunkData → 又一次全库扫描
                 * 之前只把 anchorChunk 放进了 IO，另外两个漏了。
                 */
                val prepared = withContext(Dispatchers.IO) {
                    // 种子层：先建出来（读 level.dat），失败就算了，不影响存档层
                    val seedSrc = runCatching { s.createSeedSource(activity) }.getOrNull()
                    /*
                     * 维度必须在建瓦片**之前**设好：
                     *   · SaveTiles 的 key 里带 dimension，先建后设会缓存一堆主世界的图
                     *   · 种子层同理，setDimension 会 bumpGeneration 作废旧瓦片
                     */
                    val tiles = s.createTiles(activity)
                    s.setDimension(currentDim)

                    val anchor = s.anchorChunk(currentDim)
                        // 这个维度一个区块都没有时，用种子层的出生点兜底 ——
                        // 否则切到下界/末地会直接「没有数据」
                        ?: seedSrc?.spawnPoint()?.let {
                            Pair(floorDiv(it.first, 16), floorDiv(it.second, 16))
                        }
                    if (anchor == null) null else Triple(anchor, tiles, seedSrc)
                }
                if (prepared == null) {
                    setStatus(activity.getString(R.string.map_status_no_data))
                    // 这个维度没有任何数据：把空态层放回来，用户能换一个存档
                    showEmptyOverlay(true)
                    // 空态层本身可点，但万一用户先划一下也不该卡死手势
                    mapView?.setGesturesEnabled(true)
                    return@launch
                }
                val (anchor, tiles, seedSrc) = prepared

                withContext(Dispatchers.Main) {
                    // 底层先设，上层后设：MapView 每帧都是先画底层再画上层，
                    // 顺序反了会让已探索区域在第一帧闪一下群系色
                    mapView?.setBaseSource(seedSrc)
                    mapView?.setTileSource(tiles)
                    /*
                     * 描边：两条线各自独立开关。
                     *
                     *   · 粉色 hasChunk → isChunkPopulated（玩家真加载过）
                     *   · 青色 isKnown   → isChunkKnown（查过，含没加载的）
                     *
                     * mask 始终挂上，是否绘制由 MapView 的开关决定 ——
                     * 这样改设置后不必重开地图页，下一帧就生效。
                     */
                    mapView?.setChunkMask(object : MapView.ChunkMask {
                        override fun hasChunk(cx: Int, cz: Int): Boolean =
                            tiles.isChunkPopulated(cx, cz)

                        override fun isKnown(cx: Int, cz: Int): Boolean =
                            tiles.isChunkKnown(cx, cz)
                    })
                    mapView?.setBoundaryVisible(
                        loaded = MapSettings.loadedBoundaryEnabled(activity),
                        visited = MapSettings.visitedBoundaryEnabled(activity),
                    )
                    // 居中到出生点。区块中心 +8 让落点在该区块正中间
                    mapView?.centerOn(anchor.first * 16 + 8, anchor.second * 16 + 8)
                    setReadoutVisible(true)
                    applyStatusCorner()
                    showEmptyOverlay(false)
                    /*
                     * ⚠️ 必须在这里解锁手势。
                     *
                     * onShown() 在「没有世界」时会 setGesturesEnabled(false)，
                     * 而选完存档后走的是 openWorld()，不经过 onShown——
                     * 于是手势一直是禁用的，用户选完世界发现滑不动，
                     * 必须先切回主页再切回来才恢复（用户 2026-11-28 反馈）。
                     */
                    mapView?.setGesturesEnabled(true)
                    loadedFolder = f
                    setStatus(
                        activity.getString(
                            R.string.map_status_tiles_ready,
                            anchor.first,
                            anchor.second,
                        )
                    )
                    AppLogger.info("MapPage", "地图就绪 中心区块=${anchor.first},${anchor.second}")
                }
                schedulePreload()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.error("MapPage", "打开地图失败", e)
                setStatus(activity.getString(R.string.map_status_failed, e.message ?: "unknown"))
            }
        }
    }

    // ── 预加载 ──

    /**
     * 按 [preloadRadius] 预热出生点周围的瓦片。
     *
     * ## 为什么不用 `request` 一次性投
     * `SaveTiles` 只有 2 个线程。半径 128 区块 → 257×257 区块 → 
     * 按 4 方块/采样点算约 16×16 块瓦片 = 256 块。看起来不多，
     * 但每块要解析 32×32=1024 个区块，一次性排队会让**首屏**排在最后。
     *
     * 所以这里只做「标记 wanting」，真正的生成仍然由视图按视野触发；
     * 预取只在**视图已经画完当前屏**之后，空闲时补一点点。
     *
     * 实现上：以 0.5 秒一批、每批最多 [PRELOAD_BATCH] 块的速度，
     * 从中心向外一圈圈投递，页面切走或滑动就取消。
     */
    private fun schedulePreload() {
        preloadJob?.cancel()
        val radius = preloadRadius ?: return   // 自动 = 不预取
        val s = session ?: return
        val view = mapView ?: return

        preloadJob = activity.lifecycleScope.launch {
            try {
                // createTiles 是幂等的；这里再取一次是为了拿到现成实例，
                // 若已建立则不碰磁盘。仍未建立时才真去开库 —— 挪到 IO。
                val tiles = withContext(Dispatchers.IO) { s.createTiles(activity) }
                val scale = scaleForView(view.currentBpp)
                val cells = tiles.tileCells()
                val cellBlocks = tiles.blocksPerCell(scale)
                val tileBlocks = cells * cellBlocks

                // 出生点所在瓦片为中心，按半径算出需要哪些
                val centerTileX = floorDiv(view.centerBlockX, tileBlocks)
                val centerTileZ = floorDiv(view.centerBlockZ, tileBlocks)
                // 半径（区块）→ 半径（瓦片）
                val rTiles = (radius * 16) / tileBlocks + 1

                val batch = ArrayList<Pair<Int, Int>>()
                for (dz in -rTiles..rTiles) {
                    for (dx in -rTiles..rTiles) {
                        // 圆形，不是方块 —— 角落没必要预取
                        if (dx * dx + dz * dz > rTiles * rTiles) continue
                        batch.add(Pair(centerTileX + dx, centerTileZ + dz))
                    }
                }
                // 由内向外：按到中心的距离排序
                batch.sortBy { (it.first - centerTileX) * (it.first - centerTileX) + (it.second - centerTileZ) * (it.second - centerTileZ) }

                var sent = 0
                for ((tx, tz) in batch) {
                    if (!isActive) return@launch
                    if (!visible) return@launch
                    tiles.request(scale, tx, tz)
                    sent++
                    if (sent % PRELOAD_BATCH == 0) {
                        kotlinx.coroutines.delay(PRELOAD_INTERVAL_MS)
                    }
                }
                AppLogger.info("MapPage", "预加载完成 半径=${radius}区块 瓦片=$sent")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn("MapPage", "预加载中断: ${e.message}")
            }
        }
    }

    /**
     * 把 blocks-per-pixel 说成人话。
     *
     * `1.6` 这种数字对用户没意义，换算成「1 像素 = N 方块 / N 方块 = 1 像素」
     * 才直观。也顺便暴露当前档位，方便反馈问题时描述。
     */
    private fun formatBpp(bpp: Float): String =
        if (bpp >= 1f) {
            val v = if (bpp >= 10f) Math.round(bpp).toString() else "%.1f".format(bpp)
            "1px=$v"
        } else {
            "1:${Math.round(1f / bpp)}"
        }

    /**
     * 与 [MapView.scaleFor] 保持一致。
     *
     * ⚠️ 视图那边是 private，这里只能重算一遍。
     * 两处必须同步：档位表不同的话，预取会往 `SaveTiles` 缓存里塞
     * 一批视图根本不读的键 —— 白占 20 MB 里的名额，还会挤掉有用的瓦片。
     */
    private fun scaleForView(bpp: Float): Int {
        var s = 1
        while (s < 256 && s < bpp * 1.5f) s *= 4
        return s
    }

    // ── 杂项 ──

    /**
     * 状态提示。
     *
     * 现在是「气泡」而不是固定的一整条：宽度随文字自适应，停留
     * [MapSettings.statusSeconds] 秒后淡出。停留 0 秒表示不自动消失，
     * 直到下一条消息把它顶掉。
     */
    private fun setStatus(text: String) {
        val tv = tvStatus ?: return
        tv.text = text
        tv.animate().cancel()
        tv.visibility = android.view.View.VISIBLE
        tv.alpha = 0f
        tv.animate().alpha(1f).setDuration(140).start()

        val secs = MapSettings.statusSeconds(activity)
        tv.removeCallbacks(hideStatus)
        if (secs > 0) {
            tv.postDelayed(hideStatus, secs * 1000L)
        }
    }

    /** 气泡淡出。抽成字段是因为要 removeCallbacks，每次新建 lambda 删不掉。 */
    private val hideStatus = Runnable {
        tvStatus?.animate()?.alpha(0f)?.setDuration(220)?.withEndAction {
            tvStatus?.visibility = android.view.View.GONE
        }?.start()
    }

    /** 按设置把气泡挪到对应角落。 */
    private fun applyStatusCorner() {
        val tv = tvStatus ?: return
        val lp = tv.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        val corner = MapSettings.statusCorner(activity)
        lp.gravity = when (corner) {
            MapSettings.CORNER_TOP_END -> android.view.Gravity.TOP or android.view.Gravity.END
            MapSettings.CORNER_BOTTOM_START -> android.view.Gravity.BOTTOM or android.view.Gravity.START
            MapSettings.CORNER_BOTTOM_END -> android.view.Gravity.BOTTOM or android.view.Gravity.END
            else -> android.view.Gravity.TOP or android.view.Gravity.START
        }
        tv.layoutParams = lp
    }

    /** 坐标 / 缩放读数的可见性。 */
    private fun setReadoutVisible(on: Boolean) {
        tvReadout?.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * 丢掉当前会话（连临时目录一起删）。
     *
     * ⚠️ 顺序很重要：瓦片生成线程正拿着 rawDb() 逐区块读，
     * 直接 close() 会让底层 LevelDB 句柄失效。
     * `cancel()` 是**协作式**的，投递到线程池的任务不会立刻停，
     * 所以先 join 再关库。
     */
    private suspend fun dropSessionAndWait() {
        preloadJob?.cancel()
        preloadJob = null

        val old = session
        session = null
        loadedFolder = null

        val job = loadJob
        loadJob = null
        job?.cancel()
        if (job != null) {
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(3000) { job.join() }
            }
        }

        // 解绑视图，否则视图还会拿着已关闭的 SaveTiles 请求瓦片
        mapView?.setChunkMask(null)
        mapView?.setTileSource(null)
        mapView?.setBaseSource(null)

        // 关库 + 删目录都是阻塞 IO，不能占着主线程
        old?.let { s ->
            withContext(Dispatchers.IO) {
                s.close()
                try {
                    s.cleanupCopies()
                } catch (_: Exception) {
                }
                try {
                    s.workDir.deleteRecursively()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** 非挂起版本，给 release() 这种没有协程上下文的地方用。 */
    private fun dropSession() {
        preloadJob?.cancel()
        preloadJob = null

        val old = session
        session = null
        loadedFolder = null

        val job = loadJob
        loadJob = null
        job?.cancel()

        mapView?.setChunkMask(null)
        mapView?.setTileSource(null)
        mapView?.setBaseSource(null)

        /*
         * onDestroy 里调，**不能阻塞主线程**：
         * shutdown 要等瓦片线程退出（最多 2s），删目录再删几十 MB。
         * 用一个独立线程收尾 —— Activity 已经在销毁，不需要等它。
         *
         * 注意这里不能用 lifecycleScope：onDestroy 时它已经取消了。
         */
        old?.let { s ->
            Thread({
                s.close()
                try {
                    s.cleanupCopies()
                } catch (_: Exception) {
                }
                try {
                    s.workDir.deleteRecursively()
                } catch (_: Exception) {
                }
            }, "map-session-close").start()
        }
    }

    /**
     * 扫一遍 Works，把**除当前会话以外**的临时文件全删掉。
     *
     * 两类都要清：
     *   · `map_db_*`          db 副本目录
     *   · `map_level_*.dat`   Android/data 存档经 Shizuku 中转出来的 level.dat
     *
     * 只认这两个前缀，不碰 `working_db_*`（那是编辑器当前工作副本）。
     *
     * ⚠️ 以前只清目录、`isDirectory` 为 false 的直接 `return@forEach`，
     * 于是 `map_level_*.dat` 一路累积没人管 —— 每次打开一个
     * Android/data 下的存档就多留一个 3~4 KB 的文件。
     */
    private fun sweepStaleCopies() {
        val keepDir = session?.workDir?.absolutePath
        val keepDat = session?.currentLevelDatPath()
        val works = vm.worksDir(activity)
        var removedDirs = 0
        var removedFiles = 0

        works.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                if (!f.name.startsWith("map_db_")) return@forEach
                if (keepDir != null && f.absolutePath == keepDir) return@forEach
                try {
                    if (f.deleteRecursively()) removedDirs++
                } catch (e: Exception) {
                    AppLogger.warn("MapPage", "清理地图副本失败 ${f.name}: ${e.message}")
                }
            } else {
                if (!f.name.startsWith("map_level_") || !f.name.endsWith(".dat")) return@forEach
                if (keepDat != null && f.absolutePath == keepDat) return@forEach
                try {
                    if (f.delete()) removedFiles++
                } catch (e: Exception) {
                    AppLogger.warn("MapPage", "清理临时 level.dat 失败 ${f.name}: ${e.message}")
                }
            }
        }
        if (removedDirs > 0 || removedFiles > 0) {
            AppLogger.info("MapPage", "清理地图临时文件：目录 $removedDirs 个，level.dat $removedFiles 个")
        }
    }

    /** Activity 销毁：关库、删临时目录、取消任务。 */
    fun release() {
        dropSession()
        sweepStaleCopies()
    }

    companion object {
        /** 自定义预加载半径的默认值（区块）。 */
        private const val PRELOAD_DEFAULT = 32

        /** 预加载半径上限（区块）。再大 LevelDB 要扫很久。 */
        private const val PRELOAD_MAX = 512

        /** 每投递这么多块瓦片就让出一次线程，保证 UI 和首屏优先。 */
        private const val PRELOAD_BATCH = 8

        private const val PRELOAD_INTERVAL_MS = 120L

        /** 向下取整除法。不用 Math.floorDiv（API 24，本项目 minSdk 23）。 */
        private fun floorDiv(a: Int, b: Int): Int {
            val q = a / b
            return if (a % b != 0 && (a xor b) < 0) q - 1 else q
        }
    }
}
