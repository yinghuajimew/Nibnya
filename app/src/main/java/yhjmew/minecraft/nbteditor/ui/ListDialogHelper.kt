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
fun MainActivity.showMultiPlayerDialog() = showDataListDialog(MainActivity.TYPE_PLAYER, "Player")
fun MainActivity.showMapListDialog()     = showDataListDialog(MainActivity.TYPE_MAP, "Map")
fun MainActivity.showVillageListDialog() = showDataListDialog(MainActivity.TYPE_VILLAGE, "Village")
fun MainActivity.showGlobalDataDialog()  = showGlobalDataListDialog()

private fun MainActivity.showDataListDialog(type: Int, label: String) {
    val dbPath = worldVM.currentWorkingDbPath
    if (dbPath == null || !File(dbPath).exists()) {
        toast("Load database first"); return
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
    showProgressDialog("Scanning", "Searching $label data...")
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
        arrayOf("LevelChunkMetaDataDictionary", "Metadata"),
        arrayOf("BiomeData", "Biome"),
        arrayOf("Overworld", "Overworld"),
        arrayOf("Nether", "Nether"),
        arrayOf("TheEnd", "The End"),
        arrayOf("Villages", "Villages"),
        arrayOf("Portals", "Portals"),
        arrayOf("AutonomousEntities", "Entities"),
        arrayOf("schedulerWT", "Scheduler"),
        arrayOf("Scoreboard", "Scoreboard"),
        arrayOf("Mobevents", "Mob Events"),
        arrayOf("PositionTrackDBLastID", "Track Last ID"),
        arrayOf("dimension0", "Dim 0"),
        arrayOf("dimension1", "Dim 1"),
        arrayOf("dimension2", "Dim 2"),
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
        .setTitle("Global System Data (${displayList.size})")
        .setView(lv)
        .setNegativeButton("Close", null)
        .create()
    lv.setOnItemClickListener { _, _, pos, _ -> worldVM.loadSpecificKey(this, realKeys[pos]); dialog.dismiss() }
    lv.setOnItemLongClickListener { _, _, pos, _ ->
        val key = realKeys[pos]
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Key", key))
        toast("Key copied: $key")
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
    val baseTitle = "$label Data"
    if (dataList.isEmpty()) { toast("No $label data"); return }

    val adapter = FuzzyListAdapter(this, dataList)
    val listView = ListView(this).apply { this.adapter = adapter }
    val emptyView = TextView(this).apply {
        text = "No data or no search results"; gravity = Gravity.CENTER
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
            toast("Check items to delete")
            return@setOnClickListener
        }
        val toDelete = adapter.selectedList
        if (toDelete.isEmpty()) { adapter.setSelectionMode(false); toast("Exited batch mode"); return@setOnClickListener }
        AlertDialog.Builder(this)
            .setTitle("Batch Delete")
            .setMessage("Delete ${toDelete.size} item(s)? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                showProgressDialog("Deleting", "Processing...")
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
                            toast("Deleted ${toDelete.size}")
                        }
                    } catch (e: Exception) {
                        runOnUiThread { dismissProgressDialog(); toast("Delete failed: $e") }
                    }
                }.start()
            }.setNegativeButton("Cancel", null).show()
    }

    val dialog = AlertDialog.Builder(this)
        .setCustomTitle(customTitle)
        .setNeutralButton("More") { _, _ -> showPlayerRootMenu(dataList, type) }
        .setNegativeButton("Cancel", null)
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
        val ops = arrayOf("Copy JSON", "Paste/Overwrite", "Rename", "Delete")
        AlertDialog.Builder(this)
            .setTitle("Manage: $targetKey")
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
                toast("Copied")
            }
        } catch (e: Exception) { runOnUiThread { toast("Copy failed: $e") } }
    }.start()
}

private fun MainActivity.pastePlayerJson(targetKey: String, onSuccess: Runnable?) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    if (!cm.hasPrimaryClip()) { toast("Clipboard empty"); return }
    val clip = cm.primaryClip ?: return

    AlertDialog.Builder(this)
        .setTitle("⚠️ Overwrite Warning")
        .setMessage("Full overwrite $targetKey? This is irreversible.")
        .setPositiveButton("Overwrite") { _, _ ->
            val jsonStr = cm.primaryClip!!.getItemAt(0).text.toString()
            Thread {
                try {
                    val jo = JsonParser.parseString(jsonStr).asJsonObject
                    val bytes = BedrockParser.writeToBytes(jo)
                    val db = PlayerDbManager(worldVM.currentWorkingDbPath!!)
                    db.writeSpecificKey(targetKey, bytes); db.close()
                    runOnUiThread { toast("Overwritten"); onSuccess?.run() }
                } catch (e: Exception) { runOnUiThread { toast("Paste failed: $e") } }
            }.start()
        }.setNegativeButton("Cancel", null).show()
}

private fun MainActivity.deletePlayerKey(key: String, onSuccess: Runnable?) {
    AlertDialog.Builder(this)
        .setTitle("⚠️ Delete")
        .setMessage("Delete $key?")
        .setPositiveButton("Delete") { _, _ ->
            Thread {
                try {
                    val db = PlayerDbManager(worldVM.currentWorkingDbPath!!)
                    db.deleteKey(key); db.close()
                    runOnUiThread { toast("Deleted: $key"); onSuccess?.run() }
                } catch (e: Exception) { runOnUiThread { toast("Delete failed: $e") } }
            }.start()
        }.setNegativeButton("Cancel", null).show()
}

private fun MainActivity.renamePlayerKey(
    oldKey: String, type: Int, onSuccess: Runnable?
) {
    val input = EditText(this).apply { setText(oldKey) }
    AlertDialog.Builder(this)
        .setTitle("Rename Key")
        .setMessage("Move data to new key, delete old.")
        .setView(input)
        .setPositiveButton("Rename") { _, _ ->
            val newKey = input.text.toString().trim()
            if (newKey.isEmpty() || newKey == oldKey) return@setPositiveButton
            Thread {
                try {
                    val db = PlayerDbManager(worldVM.currentWorkingDbPath!!)
                    val data = db.readSpecificKey(oldKey)
                    db.writeSpecificKey(newKey, data)
                    db.deleteKey(oldKey); db.close()
                    runOnUiThread { onSuccess?.run() }
                } catch (e: Exception) { runOnUiThread { toast("Rename failed: $e") } }
            }.start()
        }.setNegativeButton("Cancel", null).show()
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
        MainActivity.TYPE_MAP -> "e.g. map_0"
        MainActivity.TYPE_VILLAGE -> "e.g. village_xxx"
        else -> "e.g. ~local_player"
    }
    val menuList = mutableListOf<String>(
        "Create New Blank $typeName",
        "Paste as New $typeName",
        "Delete ALL in list"
    )
    if (dataType == MainActivity.TYPE_MAP) menuList.add("Create Giant Puzzle")

    AlertDialog.Builder(this)
        .setTitle("More Operations")
        .setItems(menuList.toTypedArray()) { _, w ->
            when {
                // 删除全部
                w == 2 -> AlertDialog.Builder(this)
                    .setTitle("⚠️ High Energy Warning")
                    .setMessage("Delete all ${currentList.size} $typeName data? Cannot undo!")
                    .setPositiveButton("Delete All") { _, _ ->
                        showProgressDialog("Deleting", "Cleaning up...")
                        Thread {
                            try {
                                val db = PlayerDbManager(worldVM.currentWorkingDbPath!!)
                                for (k in currentList) db.deleteKey(k)
                                db.close()
                                runOnUiThread {
                                    dismissProgressDialog()
                                    currentList.clear(); invalidateCache(dataType)
                                    toast("All $typeName deleted")
                                }
                            } catch (e: Exception) {
                                runOnUiThread { dismissProgressDialog(); toast("Failed: $e") }
                            }
                        }.start()
                    }.setNegativeButton("Cancel", null).show()

                // 拼图入口（仅地图模式）
                dataType == MainActivity.TYPE_MAP && w == 3 -> {
                    showPuzzleWarningDialog()
                }

                // 新建 / 粘贴
                else -> {
                    val mode = w
                    val input = EditText(this).apply { hint = hintName }
                    AlertDialog.Builder(this)
                        .setTitle("New $typeName Key")
                        .setView(input)
                        .setPositiveButton("Create") { _, _ ->
                            val newKey = input.text.toString()
                            if (newKey.isEmpty()) { toast("Key cannot be empty"); return@setPositiveButton }
                            val jsonContent: String = when (mode) {
                                0 -> "{}"
                                else -> {
                                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    if (!cm.hasPrimaryClip()) {
                                        toast("Clipboard empty"); return@setPositiveButton
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
                                        toast("$typeName created")
                                    }
                                } catch (e: Exception) {
                                    runOnUiThread { toast("Creation failed: $e") }
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