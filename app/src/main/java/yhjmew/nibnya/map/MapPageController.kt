package yhjmew.nibnya.map

import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yhjmew.nibnya.MainActivity
import yhjmew.nibnya.R
import yhjmew.nibnya.util.AppLogger
import yhjmew.nibnya.world.WorldViewModel

/**
 * 「地图」页的控制器。
 *
 * 职责边界：只做 UI 编排（选世界、点渲染、显示进度），
 * 解析与渲染都在 [TerrainMapBuilder]，文件与权限在 [MapSession]。
 *
 * ## 状态
 * - [mapView] 持有位图，切到别的页再切回来不会丢（View 没被销毁）
 * - [session] 持有打开的数据库，切页也不关，避免重复拷贝 db
 * - 退出 Activity 时才 [release]
 */
class MapPageController(
    private val activity: MainActivity,
    private val vm: WorldViewModel,
) {

    private val mapView: MapView? = activity.findViewById(R.id.map_view)
    private val tvWorld: TextView? = activity.findViewById(R.id.tv_map_world)
    private val tvStatus: TextView? = activity.findViewById(R.id.tv_map_status)
    private val btnPick: Button? = activity.findViewById(R.id.btn_map_pick_world)
    private val btnRender: Button? = activity.findViewById(R.id.btn_map_render)
    private val btnRadius: Button? = activity.findViewById(R.id.btn_map_radius)

    /** 当前选中的世界目录名。null = 还没选。 */
    private var selectedFolder: String? = null

    /** 渲染半径（区块）。0 / 1 / 2 / 4 / 8 / 16 循环切换。 */
    private var radiusChunks: Int = MapSession.DEFAULT_RADIUS_CHUNKS

    private var session: MapSession? = null
    private var busy = false

    init {
        btnPick?.setOnClickListener { pickWorld() }
        btnRender?.setOnClickListener { render() }
        btnRadius?.setOnClickListener { cycleRadius() }
        updateRadiusLabel()
        updateWorldLabel()

        // 世界列表加载完后，如果还没选过，自动选第一个，省一次点击
        activity.lifecycleScope.launch {
            vm.worldList.collect { list ->
                if (selectedFolder == null) {
                    list.firstOrNull()?.let {
                        selectedFolder = it.folderName
                        updateWorldLabel()
                    }
                }
            }
        }
    }

    // ── 世界选择 ──

    private fun pickWorld() {
        val list = vm.worldList.value
        if (list.isEmpty()) {
            toast(activity.getString(R.string.map_no_worlds_yet))
            return
        }
        val names = list.map { it.displayName }.toTypedArray()
        val checked = list.indexOfFirst { it.folderName == selectedFolder }.coerceAtLeast(0)

        android.app.AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.map_pick_world))
            .setSingleChoiceItems(names, checked) { dialog, which ->
                selectedFolder = list[which].folderName
                updateWorldLabel()
                // 换世界要关掉旧会话，否则还连着上一个 db
                releaseSession()
                mapView?.setMap(null)
                setStatus(activity.getString(R.string.map_status_idle))
                dialog.dismiss()
            }
            .setNegativeButton(activity.getString(R.string.btn_cancel), null)
            .show()
    }

    private fun updateWorldLabel() {
        val folder = selectedFolder
        if (folder == null) {
            tvWorld?.text = activity.getString(R.string.map_no_world)
            return
        }
        val name = vm.worldList.value.firstOrNull { it.folderName == folder }?.displayName ?: folder
        tvWorld?.text = name
    }

    // ── 半径 ──

    private fun cycleRadius() {
        val steps = intArrayOf(1, 2, 4, 8, 16, 32)
        val i = steps.indexOf(radiusChunks)
        radiusChunks = steps[(i + 1).mod(steps.size)]
        updateRadiusLabel()
    }

    private fun updateRadiusLabel() {
        val side = radiusChunks * 2 + 1
        btnRadius?.text = activity.getString(R.string.map_radius_fmt, side)
    }

    // ── 渲染 ──

    private fun render() {
        if (busy) return
        val folder = selectedFolder
        if (folder == null) {
            toast(activity.getString(R.string.map_no_worlds_yet))
            return
        }

        busy = true
        setButtonsEnabled(false)
        activity.lifecycleScope.launch {
            try {
                setStatus(activity.getString(R.string.map_status_preparing))
                val s = session ?: MapSession.open(activity, vm, folder).also { session = it }

                setStatus(activity.getString(R.string.map_status_rendering, 0))
                val bmp = s.render(
                    ctx = activity,
                    centerCx = 0,
                    centerCz = 0,
                    radiusChunks = radiusChunks,
                    dim = 0,
                ) { done, total ->
                    // 回调在后台线程，切回主线程更新文字
                    activity.runOnUiThread {
                        setStatus(activity.getString(R.string.map_status_rendering, done * 100 / total))
                    }
                }

                withContext(Dispatchers.Main) {
                    mapView?.setMap(bmp)
                    setStatus(
                        activity.getString(
                            R.string.map_status_done,
                            bmp.width, bmp.height,
                        )
                    )
                }
            } catch (e: Exception) {
                AppLogger.error("MapPage", "渲染失败", e)
                setStatus(
                    activity.getString(R.string.map_status_failed, e.message ?: "unknown")
                )
                toast(e.message ?: activity.getString(R.string.map_status_failed, "unknown"))
            } finally {
                busy = false
                setButtonsEnabled(true)
            }
        }
    }

    // ── 杂项 ──

    private fun setButtonsEnabled(on: Boolean) {
        btnPick?.isEnabled = on
        btnRender?.isEnabled = on
        btnRadius?.isEnabled = on
    }

    private fun setStatus(text: String) {
        tvStatus?.text = text
    }

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }

    private fun releaseSession() {
        session?.close()
        session = null
        // 清掉这次复制出来的工作目录，避免 Works 里越堆越多
    }

    /** Activity 销毁时调用：关库并删除临时工作目录。 */
    fun release() {
        session?.let { s ->
            s.close()
            try {
                s.workDir.deleteRecursively()
            } catch (_: Exception) {
            }
        }
        session = null
    }
}
