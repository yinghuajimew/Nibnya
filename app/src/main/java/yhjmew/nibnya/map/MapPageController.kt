package yhjmew.nibnya.map

import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yhjmew.nibnya.MainActivity
import yhjmew.nibnya.R
import yhjmew.nibnya.util.AppLogger
import yhjmew.nibnya.world.WorldViewModel

/**
 * 「地图」页控制器。
 *
 * ## 交互（2026-11-02 按用户反馈重做）
 * 1. **不再单独选世界**：跟随主页的 [WorldViewModel.currentWorldFolder]。
 *    主页换世界，地图页自动跟着换并重新渲染。
 * 2. **没有「生成地形」按钮**：切到地图页就自动加载。
 * 3. **范围用下拉**：点顶部「范围」弹出列表（自动 / 预设 / 自定义）。
 *
 * 三条反馈都来自同一个原则：用户已经在主页选过世界了，
 * 这里再选一次是重复劳动；渲染也该是自动的，不该再点一次。
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

    /** 当前渲染的世界目录。null = 主页还没打开世界。 */
    private var folder: String? = null

    /** 用户选的范围。null = 自动。 */
    private var radiusChunks: Int? = null

    private var session: MapSession? = null
    private var renderJob: Job? = null

    /** 最近一次渲染的世界，用于避免重复渲染同一个。 */
    private var renderedFolder: String? = null
    private var renderedRadius: Int? = null

    /** 页面当前是否可见。底部导航切走时不渲染。 */
    private var visible = false

    init {
        // 先扫一遍上次遗留的 map_db_*：进程被系统杀掉时 release() 跑不到，
        // 副本会一直留在 Works 里。这里补一刀，不必等到下次冷启动。
        sweepStaleCopies()

        tvRadius?.setOnClickListener { showRadiusMenu() }
        updateRadiusLabel()

        // 区块网格开关。状态存 prefs，下次进来保持。
        // 网格只影响 MapView 的绘制，不需要重新渲染地形。
        var gridOn = activity.prefs?.getBoolean("map_show_grid", true) ?: true
        fun applyGrid() {
            mapView?.showChunkGrid = gridOn
            tvGrid?.text = activity.getString(
                if (gridOn) R.string.map_grid_on else R.string.map_grid_off
            )
            // 关掉时用次要色，一看看出状态
            tvGrid?.setTextColor(
                activity.getColor(
                    if (gridOn) R.color.c_link_lang else R.color.app_text_secondary
                )
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
                // ⚠️ 换世界必须丢掉旧会话。
                // MapSession 在 open() 时就把 worldDir / workDir 固定下来了，
                // 换世界却复用同一个 session，等于拿旧世界的 db 去画新世界 ——
                // 表现为「换了世界地图还是空的 / 画的还是上一个世界」。
                //
                // dropSession() 是异步收尾（等旧渲染跑完才关库），
                // 所以这里等它把旧目录删干净、再开新会话 ——
                // 否则新会话可能刚建好就被旧的清理逻辑扫到。
                if (changed) {
                    activity.lifecycleScope.launch {
                        dropSessionAndWait()
                        if (visible && folder == f && f != null) renderNow()
                    }
                    return@collect
                }
                if (visible && f != null && (f != renderedFolder || radiusChunks != renderedRadius)) {
                    renderNow()
                }
            }
        }

        // 世界列表变化时更新显示名
        activity.lifecycleScope.launch {
            vm.worldList.collect { updateWorldLabel() }
        }
    }

    /** 底部导航切到地图页时调用。 */
    fun onShown() {
        visible = true
        // 只在没渲染过、或世界变了时渲染
        if (folder == null) {
            setStatus(activity.getString(R.string.map_status_waiting_world))
            return
        }
        if (folder != renderedFolder || radiusChunks != renderedRadius) {
            renderNow()
        }
    }

    /** 切走时调用。不断渲染，下次回来按需继续。 */
    fun onHidden() {
        visible = false
    }

    // ── 范围下拉 ──

    private fun showRadiusMenu() {
        // 第一项是自动，其余是固定半径
        val presets = intArrayOf(2, 4, 8, 16, 32, 64)
        val labels = ArrayList<String>(presets.size + 2)
        labels.add(activity.getString(R.string.map_radius_auto))

        val values = ArrayList<Int?>(presets.size + 1)
        values.add(null)
        presets.forEach {
            labels.add(activity.getString(R.string.map_radius_fmt, it * 2 + 1))
            values.add(it)
        }
        // 自定义
        labels.add(activity.getString(R.string.map_radius_custom))
        values.add(null)   // 占位，单独处理

        val cur = radiusChunks
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
                    radiusChunks = values[which]
                    updateRadiusLabel()
                    if (visible) renderNow()
                }
            }
            .setNegativeButton(activity.getString(R.string.btn_cancel), null)
            .show()
    }

    /** 自定义半径：弹输入框。 */
    private fun askCustomRadius() {
        val edit = android.widget.EditText(activity).apply {
            hint = activity.getString(R.string.map_radius_custom_hint)
            setText((radiusChunks ?: MapSession.DEFAULT_RADIUS_CHUNKS).toString())
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
                radiusChunks = v.coerceAtMost(MapSession.MAX_CHUNKS_PER_SIDE / 2)
                updateRadiusLabel()
                if (visible) renderNow()
            }
            .setNegativeButton(activity.getString(R.string.btn_cancel), null)
            .show()
    }

    private fun updateRadiusLabel() {
        val r = radiusChunks
        tvRadius?.text = if (r == null) {
            activity.getString(R.string.map_radius_auto)
        } else {
            activity.getString(R.string.map_radius_fmt, r * 2 + 1)
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

    // ── 渲染 ──

    /**
     * 开始渲染。同一时间只跑一个任务，新任务会取消旧的
     * （换世界或改范围时旧任务的结果已经没意义）。
     */
    private fun renderNow() {
        val f = folder ?: run {
            setStatus(activity.getString(R.string.map_status_waiting_world))
            return
        }

        renderJob?.cancel()
        renderJob = activity.lifecycleScope.launch {
            try {
                setStatus(activity.getString(R.string.map_status_preparing))
                AppLogger.info("MapPage", "开始在 $f 上渲染地形")

                val s = session ?: MapSession.open(activity, vm, f).also { session = it }

                val bmp = s.render(
                    ctx = activity,
                    dim = 0,
                    radiusChunks = radiusChunks,
                ) { done, total ->
                    activity.runOnUiThread {
                        setStatus(activity.getString(R.string.map_status_rendering, done * 100 / total))
                    }
                }

                withContext(Dispatchers.Main) {
                    mapView?.setMap(bmp)
                    renderedFolder = f
                    renderedRadius = radiusChunks
                    setStatus(activity.getString(R.string.map_status_done, bmp.width, bmp.height))
                    AppLogger.info("MapPage", "渲染完成 ${bmp.width}x${bmp.height}")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.error("MapPage", "渲染失败", e)
                setStatus(activity.getString(R.string.map_status_failed, e.message ?: "unknown"))
            }
        }
    }

    // ── 杂项 ──

    private fun setStatus(text: String) {
        tvStatus?.text = text
    }

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * 丢掉当前会话（连临时目录一起删）。
     *
     * ⚠️ 顺序很重要：渲染协程正拿着 rawDb() 逐区块读，
     * 直接 close() 会让底层 LevelDB 句柄失效。
     *
     * 而且 `cancel()` 是**协作式**的 —— 调用它不等于协程已经停下。
     * TerrainMapBuilder 里的分块渲染是同步循环，没有挂起点，
     * cancel 之后那段代码还会继续跑完。所以这里先 join 再关库。
     */
    private suspend fun dropSessionAndWait() {
        val old = session
        session = null
        renderedFolder = null

        val job = renderJob
        renderJob = null
        job?.cancel()
        // 等渲染真正退出后再关库；超时也继续往下走，不能卡住界面
        if (job != null) {
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(3000) { job.join() }
            }
        }

        old?.let { s ->
            s.close()
            try {
                s.workDir.deleteRecursively()
            } catch (_: Exception) {
            }
        }
    }

    /** 非挂起版本，给 release() 这种没有协程上下文的地方用。 */
    private fun dropSession() {
        val old = session
        session = null
        renderedFolder = null
        val job = renderJob
        renderJob = null
        job?.cancel()
        // release() 之后 Activity 就要没了，等不到 join；直接关
        old?.let { s ->
            s.close()
            try {
                s.workDir.deleteRecursively()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 扫一遍 Works，把**除当前会话以外**的 `map_db_*` 全删掉。
     *
     * 为什么需要这个：
     * 「启动时清一次」（[WorldViewModel.cleanUpOldSessions]）只能管到重启，
     * 一次运行里反复切世界 / 反复改范围，照样会遗留多份副本。
     * 每份是一个完整存档 db 的拷贝（几十 MB），攒十几个就很可观了。
     *
     * 只删 `map_db_` 前缀、且不是当前正在用的那个目录 ——
     * 不碰 `working_db_*`（编辑中的工作副本，可能有未保存改动）。
     */
    private fun sweepStaleCopies() {
        val keep = session?.workDir?.absolutePath
        val works = vm.worksDir(activity)
        var removed = 0
        works.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            if (!dir.name.startsWith("map_db_")) return@forEach
            if (keep != null && dir.absolutePath == keep) return@forEach
            try {
                if (dir.deleteRecursively()) removed++
            } catch (e: Exception) {
                // 公共目录删不掉通常是没有「所有文件访问」权限，
                // 记一笔，免得以后又变成「用户看得见但删不掉」的哑谜
                AppLogger.warn("MapPage", "清理地图副本失败 ${dir.name}: ${e.message}")
            }
        }
        if (removed > 0) {
            AppLogger.info("MapPage", "清理地图副本 $removed 个")
        }
    }

    /** Activity 销毁：关库、删临时目录、取消任务。 */
    fun release() {
        dropSession()
        // 关掉之后一个都不该留，连历史遗留的一起扫掉
        sweepStaleCopies()
    }
}
