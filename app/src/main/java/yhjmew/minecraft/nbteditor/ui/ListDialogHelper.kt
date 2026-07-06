package yhjmew.minecraft.nbteditor.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.view.isVisible
import com.google.gson.JsonParser
import yhjmew.minecraft.nbteditor.BedrockParser
import yhjmew.minecraft.nbteditor.MainActivity
import yhjmew.minecraft.nbteditor.PlayerDbManager
import yhjmew.minecraft.nbteditor.R
import java.io.File
import java.util.Locale

// ============================================
// 玩家 / 地图 / 村庄 / 全局 数据列表入口
// ============================================
fun MainActivity.showMultiPlayerDialog() = showDataListDialog(MainActivity.TYPE_PLAYER, getString(R.string.msg_player))
fun MainActivity.showMapListDialog()     = showDataListDialog(MainActivity.TYPE_MAP, getString(R.string.msg_map))
fun MainActivity.showVillageListDialog() = showDataListDialog(MainActivity.TYPE_VILLAGE, getString(R.string.msg_village))
fun MainActivity.showGlobalDataDialog()  = showGlobalDataListDialog()

private fun MainActivity.showDataListDialog(type: Int, label: String) {
    val dbPath = worldVM.currentWorkingDbPath
    if (dbPath == null || !File(dbPath).exists()) {
        toast(getString(R.string.toast_please_initialize_the_database_first)); return
    }

    // 如果是地图或村庄，先重扫缓存（确保拼图生成后的数据可见）
    if (type == MainActivity.TYPE_MAP) {
        cacheMapList = null  // ← 强制下次重扫
    } else if (type == MainActivity.TYPE_VILLAGE) {
        cacheVillageList = null
    } else if (type == MainActivity.TYPE_PLAYER) {
        cachePlayerList = null
    }

    val cachedList: MutableList<String>? = when (type) {
        MainActivity.TYPE_PLAYER -> cachePlayerList
        MainActivity.TYPE_MAP -> cacheMapList
        MainActivity.TYPE_VILLAGE -> cacheVillageList
        else -> null
    }
    if (cachedList != null) {
        showListDialogUI(cachedList, type, label)
        return
    }
    showProgressDialog(getString(R.string.msg_scanning), getString(R.string.searching_label_data, label))
    Thread {
        try {
            val db = PlayerDbManager(dbPath)
            val rawList: List<String> = when (type) {
                MainActivity.TYPE_PLAYER -> db.listPlayerKeys()
                MainActivity.TYPE_MAP -> db.listMapKeys()
                MainActivity.TYPE_VILLAGE -> db.listVillageKeys()
                else -> emptyList()
            }
            db.close()
            runOnUiThread {
                dismissProgressDialog()
                val list = rawList.toMutableList()
                when (type) {
                    MainActivity.TYPE_PLAYER -> cachePlayerList = list
                    MainActivity.TYPE_MAP -> cacheMapList = list
                    MainActivity.TYPE_VILLAGE -> cacheVillageList = list
                }
                showListDialogUI(list, type, label)
            }
        } catch (e: Exception) {
            runOnUiThread { dismissProgressDialog(); toast(e.toString()) }
        }
    }.start()
}

// ============================================
// 全局数据（硬编码特殊 Key）
// ============================================
private fun MainActivity.showGlobalDataListDialog() {
    val globalKeys = arrayOf(
        arrayOf("LevelChunkMetaDataDictionary", getString(R.string.msg_global_system_data_list_metadata)),
        arrayOf("BiomeData", getString(R.string.msg_global_system_data_list_community)),
        arrayOf("Overworld", getString(R.string.msg_global_system_data_list_main_world_data)),
        arrayOf("Nether", getString(R.string.msg_global_system_data_list_iower_bound_data)),
        arrayOf("TheEnd", getString(R.string.msg_global_system_data_list_end_data)),
        arrayOf("Villages", getString(R.string.msg_global_system_data_list_village)),
        arrayOf("Portals", getString(R.string.msg_global_system_data_list_portal)),
        arrayOf("AutonomousEntities", getString(R.string.msg_global_system_data_list_autonoous_entity)),
        arrayOf("schedulerWT", getString(R.string.msg_global_system_data_list_scheduler)),
        arrayOf("Scoreboard", getString(R.string.msg_global_system_data_list_scoreboard_data)),
        arrayOf("Mobevents", getString(R.string.msg_global_system_data_list_biological_events)),
        arrayOf("PositionTrackDBLastID", getString(R.string.msg_global_system_data_list_last_targeting_id)),
        arrayOf("dimension0", getString(R.string.msg_global_system_data_list_main_world_0)),
        arrayOf("dimension1", getString(R.string.msg_global_system_data_list_nether_1)),
        arrayOf("dimension2", getString(R.string.msg_global_system_data_list_end_2)),
    )
    val displayList = mutableListOf<String>()
    val realKeys = mutableListOf<String>()
    for (pair in globalKeys) {
        displayList.add("${pair[0]}\n${pair[1]}")
        realKeys.add(pair[0])
    }

    val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, displayList) {
        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val v = super.getView(pos, cv, parent) as TextView
            v.textSize = 14f; v.setPadding(30, 20, 30, 20)
            return v
        }
    }
    val lv = ListView(this).apply { this.adapter = adapter }
    val dialog = AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_global_system_data_count, displayList.size))
        .setView(lv)
        .setNegativeButton(getString(R.string.btn_close), null)
        .create()
    lv.setOnItemClickListener { _, _, pos, _ -> worldVM.loadSpecificKey(this, realKeys[pos]); dialog.dismiss() }
    lv.setOnItemLongClickListener { _, _, pos, _ ->
        val key = realKeys[pos]
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Key", key))
        toast(getString(R.string.toast_key_copied, key))
        true
    }
    dialog.show()
}

// ============================================
// 通用列表 UI（带搜索 & 批量删除）
// ============================================
private fun MainActivity.showListDialogUI(
    dataList: MutableList<String>, type: Int, label: String
) {
    val baseTitle = getString(R.string.title_label_data, label)
    // 列表为空时只弹 Toast 提示，但对话框仍然继续显示（保留"更多操作"按钮供用户新建）
    if (dataList.isEmpty()) { toast(getString(R.string.toast_no_label_data, label)) }

    val adapter = FuzzyListAdapter(this, dataList)
    val listView = ListView(this).apply { this.adapter = adapter }
    val emptyView = TextView(this).apply {
        text = getString(R.string.text_no_data_or_no_search_results_found); gravity = Gravity.CENTER
    }
    val container = FrameLayout(this).apply { addView(listView); addView(emptyView) }
    listView.emptyView = emptyView

    @SuppressLint("InflateParams")
    val customTitle = LayoutInflater.from(this)
        .inflate(R.layout.dialog_title_with_search, null, false)
    val tvTitle = customTitle.findViewById<TextView>(R.id.tv_dialog_title)
    val btnSearch = customTitle.findViewById<Button>(R.id.btn_search_toggle)
    val searchContainer = customTitle.findViewById<View>(R.id.search_container)
    val etSearch = customTitle.findViewById<EditText>(R.id.et_search_input)
    val btnClear = customTitle.findViewById<View>(R.id.btn_clear_search)
    val btnBatchDelete = customTitle.findViewById<Button>(R.id.btn_batch_delete)

    tvTitle.text = "$baseTitle (${dataList.size})"

    // 搜索
    btnSearch.setOnClickListener {
        if (searchContainer.isVisible) {
            searchContainer.isVisible = false; etSearch.text.clear()
            adapter.filter.filter(null)
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(etSearch.windowToken, 0)
        } else {
            searchContainer.isVisible = true
            etSearch.requestFocus()
            etSearch.postDelayed({
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?)
                    ?.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
            }, 200)
        }
    }
    btnClear.setOnClickListener { etSearch.text.clear() }
    etSearch.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {
            adapter.filter.filter(s); btnClear.isVisible = s.isNotEmpty()
        }
        override fun afterTextChanged(s: Editable?) {}
    })

    // 批量删除
    btnBatchDelete.setOnClickListener {
        if (!adapter.isSelectionMode()) {
            adapter.setSelectionMode(true)
            toast(getString(R.string.toast_please_check_the_items_you_want_to_delete))
            return@setOnClickListener
        }
        val toDelete = adapter.selectedList
        if (toDelete.isEmpty()) { adapter.setSelectionMode(false); toast(getString(R.string.toast_exited_from_batch_management)); return@setOnClickListener }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_batch_delete))
            .setMessage(getString(R.string.msg_delete_items_confirm, toDelete.size))
            .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                showProgressDialog(getString(R.string.msg_deleting), getString(R.string.msg_processing_ing))
                val dbPath = worldVM.currentWorkingDbPath
                Thread {
                    try {
                        val db = PlayerDbManager(dbPath!!)
                        for (key in toDelete) db.deleteKey(key)
                        db.close()
                        runOnUiThread {
                            dismissProgressDialog()
                            for (key in toDelete) { adapter.remove(key); dataList.remove(key) }
                            adapter.setSelectionMode(false)
                            tvTitle.text = "$baseTitle (${adapter.count})"
                            when (type) {
                                MainActivity.TYPE_PLAYER -> cachePlayerList = null
                                MainActivity.TYPE_MAP -> cacheMapList = null
                                MainActivity.TYPE_VILLAGE -> cacheVillageList = null
                            }
                            toast(getString(R.string.toast_deleted_count, toDelete.size))
                        }
                    } catch (e: Exception) {
                        runOnUiThread { dismissProgressDialog(); toast(getString(R.string.toast_delete_failed, e)) }
                    }
                }.start()
            }.setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    val dialog = AlertDialog.Builder(this)
        .setCustomTitle(customTitle)
        .setNeutralButton(getString(R.string.btn_more_actions)) { _, _ -> showPlayerRootMenu(dataList, type) }
        .setNegativeButton(getString(R.string.btn_cancel), null)
        .setView(container)
        .create()

    dialog.setOnShowListener {
        dialog.window?.let {
            it.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
    }

    listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
        if (adapter.isSelectionMode()) return@OnItemClickListener
        val key = adapter.getItem(pos)
        worldVM.loadSpecificKey(this, key)
        dialog.dismiss()
        findViewById<View?>(R.id.custom_sidebar_container)?.isVisible = false
    }

    listView.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, pos, _ ->
        if (adapter.isSelectionMode()) return@OnItemLongClickListener false
        val targetKey = adapter.getItem(pos)
        val ops = arrayOf(getString(R.string.msg_list_long_press_copy_data_json), getString(R.string.msg_list_long_press_paste_overlay), getString(R.string.msg_list_long_press_rename), getString(R.string.msg_list_long_press_delete))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_manage, targetKey))
            .setItems(ops) { _, w ->
                when (w) {
                    0 -> copyPlayerJson(targetKey)
                    1 -> pastePlayerJson(targetKey) {
                        worldVM.loadSpecificKey(this, targetKey); dialog.dismiss()
                    }
                    2 -> renamePlayerKey(targetKey, type) {
                        dialog.dismiss(); invalidateCache(type)
                        when (type) {
                            MainActivity.TYPE_PLAYER -> showMultiPlayerDialog()
                            MainActivity.TYPE_MAP -> showMapListDialog()
                            MainActivity.TYPE_VILLAGE -> showVillageListDialog()
                        }
                    }
                    3 -> deletePlayerKey(targetKey) {
                        adapter.remove(targetKey); dataList.remove(targetKey)
                        tvTitle.text = "$baseTitle (${adapter.count})"
                    }
                }
            }.show()
        true
    }
    dialog.show()
}

// ============================================
// Key CRUD（复制/粘贴/删除/重命名）
// ============================================
private fun MainActivity.copyPlayerJson(key: String) {
    Thread {
        try {
            val db = PlayerDbManager(worldVM.currentWorkingDbPath!!)
            val data = db.readSpecificKey(key); db.close()
            val json = BedrockParser.parseBytes(data).toString()
            runOnUiThread {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("PLAYER_DATA", json))
                toast(getString(R.string.toast_copied))
            }
        } catch (e: Exception) { runOnUiThread { toast(getString(R.string.msg_copy_failed, e)) } }
    }.start()
}

private fun MainActivity.pastePlayerJson(targetKey: String, onSuccess: Runnable?) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    if (!cm.hasPrimaryClip()) { toast(getString(R.string.toast_clipboard_empty)); return }
    val clip = cm.primaryClip ?: return

    AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_overwrite_warning))
        .setMessage(getString(R.string.msg_full_overwrite_confirm, targetKey))
        .setPositiveButton(getString(R.string.btn_cover)) { _, _ ->
            val jsonStr = cm.primaryClip!!.getItemAt(0).text.toString()
            Thread {
                try {
                    val jo = JsonParser.parseString(jsonStr).asJsonObject
                    val bytes = BedrockParser.writeToBytes(jo)
                    val db = PlayerDbManager(worldVM.currentWorkingDbPath!!)
                    db.writeSpecificKey(targetKey, bytes); db.close()
                    runOnUiThread { toast(getString(R.string.toast_data_covered)); onSuccess?.run() }
                } catch (e: Exception) { runOnUiThread { toast(getString(R.string.toast_paste_failed, e)) } }
            }.start()
        }
        .setNegativeButton(getString(R.string.btn_cancel), null).show()
}

private fun MainActivity.deletePlayerKey(key: String, onSuccess: Runnable?) {
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_remove_warning))
        .setMessage(getString(R.string.msg_delete_confirm, key))
        .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
            Thread {
                try {
                    val db = PlayerDbManager(worldVM.currentWorkingDbPath!!)
                    db.deleteKey(key); db.close()
                    runOnUiThread { toast(getString(R.string.toast_delete_key_confirm, key)); onSuccess?.run() }
                } catch (e: Exception) { runOnUiThread { toast(getString(R.string.toast_delete_failed, e)) } }
            }.start()
        }.setNegativeButton(getString(R.string.btn_cancel), null).show()
}

private fun MainActivity.renamePlayerKey(
    oldKey: String, type: Int, onSuccess: Runnable?
) {
    val input = EditText(this).apply { setText(oldKey) }
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_rename_key))
        .setMessage(getString(R.string.msg_move_data_to_new_key_and_delete_old_key))
        .setView(input)
        .setPositiveButton(getString(R.string.menu_rename)) { _, _ ->
            val newKey = input.text.toString().trim()
            if (newKey.isEmpty() || newKey == oldKey) return@setPositiveButton
            Thread {
                try {
                    val db = PlayerDbManager(worldVM.currentWorkingDbPath!!)
                    val data = db.readSpecificKey(oldKey)
                    db.writeSpecificKey(newKey, data)
                    db.deleteKey(oldKey); db.close()
                    runOnUiThread { onSuccess?.run() }
                } catch (e: Exception) { runOnUiThread { toast(getString(R.string.toast_rename_failed, e)) } }
            }.start()
        }.setNegativeButton(getString(R.string.btn_cancel), null).show()
}

private fun MainActivity.invalidateCache(type: Int) {
    when (type) {
        MainActivity.TYPE_PLAYER -> cachePlayerList = null
        MainActivity.TYPE_MAP -> cacheMapList = null
        MainActivity.TYPE_VILLAGE -> cacheVillageList = null
    }
}

// ============================================
// 玩家根菜单（新建/粘贴/删除全部/拼图入口）
// ============================================
fun MainActivity.showPlayerRootMenu(currentList: MutableList<String>, dataType: Int) {
    val typeName = when (dataType) {
        MainActivity.TYPE_MAP -> "Map"
        MainActivity.TYPE_VILLAGE -> "Village"
        else -> "Player"
    }
    val hintName = when (dataType) {
        MainActivity.TYPE_MAP -> getString(R.string.msg_for_example_map)
        MainActivity.TYPE_VILLAGE -> getString(R.string.msg_for_example_village)
        else -> getString(R.string.msg_for_example_player)
    }
    val menuList = mutableListOf<String>(
        getString(R.string.msg_create_new_blank, typeName),
        getString(R.string.msg_paste_as_new, typeName),
        getString(R.string.btn_delete_all_in_list)
    )
    if (dataType == MainActivity.TYPE_MAP) menuList.add(getString(R.string.msg_create_a_giant_jigsaw_puzzle))

    AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_more_operations))
        .setItems(menuList.toTypedArray()) { _, w ->
            when {
                // 删除全部
                w == 2 -> AlertDialog.Builder(this)
                    .setTitle(getString(R.string.title_high_energy_early_warning))
                    .setMessage(getString(R.string.msg_delete_all_type_data, currentList.size, typeName))
                    .setPositiveButton(getString(R.string.btn_delete_all)) { _, _ ->
                        showProgressDialog(getString(R.string.msg_deleting), getString(R.string.msg_cleaning_up))
                        Thread {
                            try {
                                val db = PlayerDbManager(worldVM.currentWorkingDbPath!!)
                                for (k in currentList) db.deleteKey(k)
                                db.close()
                                runOnUiThread {
                                    dismissProgressDialog()
                                    currentList.clear(); invalidateCache(dataType)
                                    toast(getString(R.string.toast_all_type_deleted, typeName))
                                }
                            } catch (e: Exception) {
                                runOnUiThread { dismissProgressDialog(); toast(getString(R.string.toast_failed, e)) }
                            }
                        }.start()
                    }.setNegativeButton(getString(R.string.btn_cancel), null).show()

                // 拼图入口（仅地图模式）
                dataType == MainActivity.TYPE_MAP && w == 3 -> {
                    showPuzzleWarningDialog()
                }

                // 新建 / 粘贴
                else -> {
                    val mode = w
                    val input = EditText(this).apply { hint = hintName }
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.title_new_type_key, typeName))
                        .setView(input)
                        .setPositiveButton(getString(R.string.btn_create)) { _, _ ->
                            val newKey = input.text.toString()
                            if (newKey.isEmpty()) { toast(getString(R.string.toast_key_cannot_be_empty)); return@setPositiveButton }
                            val jsonContent: String = when (mode) {
                                0 -> "{}"
                                else -> {
                                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    if (!cm.hasPrimaryClip()) {
                                        toast(getString(R.string.toast_clipboard_empty)); return@setPositiveButton
                                    }
                                    cm.primaryClip!!.getItemAt(0).text.toString()
                                }
                            }
                            Thread {
                                try {
                                    val jo = JsonParser.parseString(jsonContent).asJsonObject
                                    val bytes = BedrockParser.writeToBytes(jo)
                                    val db = PlayerDbManager(worldVM.currentWorkingDbPath!!)
                                    db.writeSpecificKey(newKey, bytes); db.close()
                                    runOnUiThread {
                                        if (!currentList.contains(newKey)) currentList.add(newKey)
                                        invalidateCache(dataType)
                                        toast(getString(R.string.toast_type_created, typeName))
                                    }
                                } catch (e: Exception) {
                                    runOnUiThread { toast(getString(R.string.toast_creation_failed, e)) }
                                }
                            }.start()
                        }.show()
                }
            }
        }.show()
}

// ============================================
// 内部适配器：模糊搜索 + 选择模式
// ============================================
private class FuzzyListAdapter(
    private val context: Context,
    data: MutableList<String>
) : BaseAdapter(), Filterable {
    private val originalList = ArrayList(data)
    private var displayedList = ArrayList(data)
    private val inflater = LayoutInflater.from(context)

    var selectedItems = HashSet<String>()
    private var selectionMode = false

    fun isSelectionMode() = selectionMode
    fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        if (!enabled) selectedItems.clear()
        notifyDataSetChanged()
    }

    override fun getCount() = displayedList.size
    override fun getItem(pos: Int): String = displayedList[pos]
    override fun getItemId(pos: Int) = pos.toLong()

    @SuppressLint("ViewHolder")
    override fun getView(pos: Int, cv: View?, parent: ViewGroup?): View {
        val view = cv ?: inflater.inflate(R.layout.item_checkbox, parent, false)
        val currentKey = displayedList[pos]
        val tv = view.findViewById<TextView>(R.id.tv_item_name)
        val cb = view.findViewById<CheckBox>(R.id.cb_item_select)
        tv.text = currentKey

        if (selectionMode) {
            cb.isVisible = true
            cb.setOnCheckedChangeListener(null)
            cb.isChecked = selectedItems.contains(currentKey)
            cb.setOnClickListener {
                if (cb.isChecked) selectedItems.add(currentKey)
                else selectedItems.remove(currentKey)
            }
        } else {
            cb.isVisible = false
        }
        return view
    }

    fun remove(item: String?) {
        originalList.remove(item); displayedList.remove(item)
        selectedItems.remove(item); notifyDataSetChanged()
    }

    val selectedList: MutableList<String> get() = ArrayList(selectedItems)

    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val results = FilterResults()
                val filtered = ArrayList<String>()
                if (constraint.isNullOrEmpty()) filtered.addAll(originalList)
                else {
                    val pattern = constraint.toString().lowercase(Locale.getDefault()).trim()
                    originalList.filterTo(filtered) {
                        it.lowercase(Locale.getDefault()).contains(pattern)
                    }
                }
                results.values = filtered; results.count = filtered.size
                return results
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                displayedList = results.values as ArrayList<String>
                notifyDataSetChanged()
            }
        }
    }
}