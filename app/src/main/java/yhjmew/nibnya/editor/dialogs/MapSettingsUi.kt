package yhjmew.nibnya.editor.dialogs

import android.content.Context
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.TextView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import yhjmew.nibnya.R
import yhjmew.nibnya.map.MapSettings

/**
 * 设置页「编辑器」标签里的地图渲染设置。
 *
 * ## 为什么用 Spinner 而不是滑杆
 *
 * 滑杆要拖动才准，手指一偏就滑过头，区间大时尤其难停。
 * Spinner 点一下选一档，快且不会选错；实在要精确值再点旁边的「自定义」。
 * 与「并发线程数」「裁剪框最小尺寸」保持同一套交互。
 *
 * ## 为什么改完要提示「重新打开地图页」
 *
 * 缓存、线程池、种子层都是 `MapSession` 构造时创建的。
 * 想立刻生效必须重建整个 session（关库、停线程、清缓存），
 * 代价远大于「回地图页重新打开一次」。
 *
 * 描边开关是例外 —— 它只是 [yhjmew.nibnya.map.MapView] 里的两个布尔值，
 * 下一帧就生效，但这里仍统一按「重开生效」说明，免得用户以为设置坏了。
 */
object MapSettingsUi {

    /** 三档数值设置的描述。 */
    private class NumberSpec(
        val spinnerId: Int,
        val customBtnId: Int,
        val prefKey: String,
        val presets: List<Int>,
        val min: Int,
        val max: Int,
        val unitRes: Int,
        val titleRes: Int,
        val invalidMsgRes: Int,
        val read: (Context) -> Int,
        val write: (Context, Int) -> Unit,
    )

    fun build(ctx: Context, root: android.view.View, onChange: () -> Unit) {
        val specs = listOf(
            NumberSpec(
                spinnerId = R.id.sp_map_tile_cache,
                customBtnId = R.id.btn_custom_map_tile_cache,
                prefKey = "map_cache_mb",
                presets = listOf(8, 16, 24, 32, 48, 64),
                min = MapSettings.CACHE_MB_MIN,
                max = MapSettings.CACHE_MB_MAX,
                unitRes = R.string.unit_mb,
                titleRes = R.string.map_title_tile_cache,
                invalidMsgRes = R.string.map_msg_tile_cache_invalid,
                read = { MapSettings.cacheMb(it) },
                write = { c, v -> MapSettings.setCacheMb(c, v) },
            ),
            NumberSpec(
                spinnerId = R.id.sp_map_chunk_cache,
                customBtnId = R.id.btn_custom_map_chunk_cache,
                prefKey = "map_chunk_entries",
                presets = listOf(1024, 2048, 4096, 8192, 16384),
                min = MapSettings.CHUNK_ENTRIES_MIN,
                max = MapSettings.CHUNK_ENTRIES_MAX,
                unitRes = R.string.unit_chunks,
                titleRes = R.string.map_title_chunk_cache,
                invalidMsgRes = R.string.map_msg_chunk_cache_invalid,
                read = { MapSettings.chunkEntries(it) },
                write = { c, v -> MapSettings.setChunkEntries(c, v) },
            ),
            NumberSpec(
                spinnerId = R.id.sp_map_threads,
                customBtnId = R.id.btn_custom_map_threads,
                prefKey = "map_threads",
                presets = listOf(1, 2, 3, 4),
                min = MapSettings.THREADS_MIN,
                max = MapSettings.THREADS_MAX,
                unitRes = R.string.unit_threads,
                titleRes = R.string.map_title_threads,
                invalidMsgRes = R.string.map_msg_threads_invalid,
                read = { MapSettings.threads(it) },
                write = { c, v -> MapSettings.setThreads(c, v) },
            ),
        )

        // bindPresetSpinner 是 MainActivity 的扩展函数，receiver 必须是它
        val act = ctx as? yhjmew.nibnya.MainActivity ?: return

        for (spec in specs) {
            val sp = root.findViewById<Spinner?>(spec.spinnerId) ?: continue
            val btn = root.findViewById<Button?>(spec.customBtnId)

            act.bindPresetSpinner(
                sp = sp,
                presetLabels = spec.presets.map { "$it" + ctx.getString(spec.unitRes) },
                presetValues = spec.presets,
                titleRes = spec.titleRes,
                invalidMsgRes = spec.invalidMsgRes,
                currentValue = { spec.read(ctx) },
                applyValue = { v ->
                    spec.write(ctx, v)
                    onChange()
                },
                customBtn = btn,
                customLabel = { v -> "✎ $v" + ctx.getString(spec.unitRes) },
                rememberCustom = { v ->
                    ctx.getSharedPreferences("nibnya_map", Context.MODE_PRIVATE)
                        .edit().putInt("${spec.prefKey}_custom", v).apply()
                },
                loadCustom = {
                    ctx.getSharedPreferences("nibnya_map", Context.MODE_PRIVATE)
                        .getInt("${spec.prefKey}_custom", 0)
                },
            )
        }

        // ── 提示停留（秒）──
        // 0 表示不自动消失；用 bindPresetSpinner 会额外生成「✎ 7」这类
        // 自定义行，正好满足「玩家想要改多长就去编辑器改」的要求。
        run {
            val sp = root.findViewById<Spinner?>(R.id.sp_map_status_seconds) ?: return@run
            act.bindPresetSpinner(
                sp = sp,
                presetLabels = listOf(
                    ctx.getString(R.string.map_status_seconds_never),
                    ctx.getString(R.string.map_status_seconds_fmt, 2),
                    ctx.getString(R.string.map_status_seconds_fmt, 3),
                    ctx.getString(R.string.map_status_seconds_fmt, 5),
                    ctx.getString(R.string.map_status_seconds_fmt, 10),
                ),
                presetValues = listOf(0, 2, 3, 5, 10),
                titleRes = R.string.map_title_status_seconds,
                invalidMsgRes = R.string.map_msg_status_seconds_invalid,
                currentValue = { MapSettings.statusSeconds(ctx) },
                applyValue = { v ->
                    MapSettings.setStatusSeconds(ctx, v)
                    onChange()
                },
                customLabel = { v -> "✎ " + ctx.getString(R.string.map_status_seconds_fmt, v) },
            )
        }

        // ── 提示位置（四角）──
        run {
            val sp = root.findViewById<Spinner?>(R.id.sp_map_status_corner) ?: return@run
            val labels = listOf(
                ctx.getString(R.string.map_corner_top_start),
                ctx.getString(R.string.map_corner_top_end),
                ctx.getString(R.string.map_corner_bottom_start),
                ctx.getString(R.string.map_corner_bottom_end),
            )
            sp.adapter = ArrayAdapter(
                ctx, android.R.layout.simple_spinner_item, labels
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            sp.setSelection(MapSettings.statusCorner(ctx), false)

            var picked = false
            sp.setOnTouchListener { _, _ -> picked = true; false }
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long,
                ) {
                    if (!picked) return
                    picked = false
                    MapSettings.setStatusCorner(ctx, pos)
                    onChange()
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        // ── 三个开关 ──

        bindCheck(root, R.id.cb_map_seed_layer, MapSettings.seedLayerEnabled(ctx)) { on ->
            MapSettings.setSeedLayerEnabled(ctx, on)
            onChange()
        }

        bindCheck(
            root, R.id.cb_map_boundary_loaded,
            MapSettings.loadedBoundaryEnabled(ctx)
        ) { on ->
            MapSettings.setLoadedBoundaryEnabled(ctx, on)
            onChange()
        }

        bindCheck(
            root, R.id.cb_map_boundary_visited,
            MapSettings.visitedBoundaryEnabled(ctx)
        ) { on ->
            MapSettings.setVisitedBoundaryEnabled(ctx, on)
            onChange()
        }

        // ── native 统计（只读，没有就不显示）──

        root.findViewById<TextView?>(R.id.tv_map_native_stats)?.let { tv ->
            val s = yhjmew.nibnya.map.SeedTerrainSource.stats()
            if (s != null) {
                tv.text = ctx.getString(R.string.map_stats_native, s[0], s[1], s[2])
                tv.visibility = android.view.View.VISIBLE
            } else {
                tv.visibility = android.view.View.GONE
            }
        }
    }

    private fun bindCheck(
        root: android.view.View,
        id: Int,
        checked: Boolean,
        onToggle: (Boolean) -> Unit,
    ) {
        root.findViewById<CheckBox?>(id)?.let { cb ->
            cb.isChecked = checked
            cb.setOnCheckedChangeListener { _, v -> onToggle(v) }
        }
    }
}
