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
        tvRadius?.setOnClickListener { showRadiusMenu() }
        updateRadiusLabel()

        // 跟随主页世界
        activity.lifecycleScope.launch {
            vm.currentWorldFolder.collect { f ->
                folder = f
                updateWorldLabel()
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

    /** 换世界时丢掉旧会话（连同临时目录）。 */
    private fun dropSession() {
        session?.let { s ->
            s.close()
            try {
                s.workDir.deleteRecursively()
            } catch (_: Exception) {
            }
        }
        session = null
    }

    /** Activity 销毁：关库、删临时目录、取消任务。 */
    fun release() {
        renderJob?.cancel()
        renderJob = null
        dropSession()
    }
}
