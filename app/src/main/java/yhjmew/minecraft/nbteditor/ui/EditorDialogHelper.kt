package yhjmew.minecraft.nbteditor.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Html
import android.text.InputType
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.core.content.edit
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import yhjmew.minecraft.nbteditor.MainActivity
import yhjmew.minecraft.nbteditor.NbtTranslator
import yhjmew.minecraft.nbteditor.R
import yhjmew.minecraft.nbteditor.viewmodel.EditorViewModel

// ============================================
// 根菜单
// ============================================
fun MainActivity.showRootMenu() {
    val current = editorVM.nbtData.value ?: run { toast("No data"); return }
    val ops = arrayOf("Add Tag", "Paste Child", "Copy All", "Paste/Replace", "Clear All")
    AlertDialog.Builder(this).setTitle("Root Menu").setItems(ops) { _, w ->
        when (w) {
            0 -> showAddChildDialog(current)
            1 -> {
                if (MainActivity.clipboard == null) { toast("Clipboard empty"); return@setItems }
                val input = EditText(this).apply { hint = "New tag name" }
                AlertDialog.Builder(this).setTitle("Paste as").setView(input)
                    .setPositiveButton("Confirm") { _, _ ->
                        val n = input.text.toString()
                        if (n.isNotEmpty() && !current.has(n)) {
                            current.add(n, MainActivity.clipboard!!.deepCopy())
                            refreshAfterEdit(); toast("Pasted")
                        } else toast("Name empty or exists")
                    }.show()
            }
            2 -> {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("NBT", current.toString()))
                toast("Copied")
            }
            3 -> {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                if (!cm.hasPrimaryClip()) { toast("Clipboard empty"); return@setItems }
                AlertDialog.Builder(this).setTitle("Replace?").setMessage("Replace all data?")
                    .setPositiveButton("Replace") { _, _ ->
                        try {
                            val text = cm.primaryClip!!.getItemAt(0).text ?: return@setPositiveButton
                            val newObj = JsonParser.parseString(text.toString()).asJsonObject
                            ArrayList(current.keySet()).forEach { current.remove(it) }
                            newObj.entrySet().forEach { current.add(it.key, it.value) }
                            refreshAfterEdit(); toast("Replaced")
                        } catch (_: Exception) { toast("JSON parse error") }
                    }.setNegativeButton("Cancel", null).show()
            }
            4 -> AlertDialog.Builder(this).setTitle("⚠️ Danger").setMessage("Clear all?")
                .setPositiveButton("Clear") { _, _ ->
                    ArrayList(current.keySet()).forEach { current.remove(it) }
                    refreshAfterEdit(); toast("Cleared")
                }.setNegativeButton("Cancel", null).show()
        }
    }.show()
}

// ============================================
// 长按菜单
// ============================================
fun MainActivity.showLongPressMenu(key: String?, itemData: JsonObject) {
    val ops = arrayOf("Copy", "Paste", "Delete", "Rename", "Add Child")
    AlertDialog.Builder(this).setTitle("Manage: $key").setItems(ops) { _, w ->
        var parent: JsonObject?
        var nodeKey = key
        var nodeData: JsonObject? = itemData

        if (editorVM.isTreeMode.value && editorVM.lastTreeClickPosition >= 0) {
            parent = nbtTreeAdapter?.getParentContainer(editorVM.lastTreeClickPosition)
                ?: editorVM.nbtData.value
            nbtTreeAdapter?.getNodeKey(editorVM.lastTreeClickPosition)?.let { nodeKey = it }
            nbtTreeAdapter?.getNodeData(editorVM.lastTreeClickPosition)?.let { nodeData = it }
        } else {
            parent = nbtAdapter?.data
        }
        if (parent == null || nodeKey == null) return@setItems

        when (w) {
            0 -> {
                MainActivity.clipboard = nodeData!!.deepCopy()
                toast("Copied")
            }
            1 -> {
                if (MainActivity.clipboard == null) { toast("Clipboard empty"); return@setItems }
                val pasteKey = nodeKey + "_copy"
                if (parent!!.has(pasteKey)) { toast("Name exists"); return@setItems }
                parent.add(pasteKey, MainActivity.clipboard!!.deepCopy())
                refreshAfterEdit(); toast("Pasted")
            }
            2 -> { parent!!.remove(nodeKey); refreshAfterEdit(); toast("Deleted") }
            3 -> showRenameDialog(nodeKey, nodeData, parent!!)
            4 -> addChildToNode(nodeData, parent!!)
        }
    }.show()
}

// ============================================
// 重命名
// ============================================
private fun MainActivity.showRenameDialog(oldKey: String?, itemData: JsonObject?, parent: JsonObject) {
    val input = EditText(this).apply { setText(oldKey) }
    AlertDialog.Builder(this).setTitle("Rename").setView(input)
        .setPositiveButton("Confirm") { _, _ ->
            val nk = input.text.toString().trim()
            if (nk.isEmpty() || parent.has(nk)) { toast("Invalid name"); return@setPositiveButton }
            parent.remove(oldKey); parent.add(nk, itemData)
            refreshAfterEdit(); toast("Renamed")
        }.show()
}

// ============================================
// 添加子项到节点
// ============================================
private fun MainActivity.addChildToNode(nodeData: JsonObject?, parent: JsonObject) {
    val data = nodeData ?: return
    val type = data.get("t").asInt
    if (type == 10) {
        showAddChildDialog(data.getAsJsonObject("v"))
        if (editorVM.isTreeMode.value) {
            nbtTreeAdapter?.getNode(editorVM.lastTreeClickPosition)?.let {
                if (!it.isExpanded) nbtTreeAdapter?.toggleExpand(editorVM.lastTreeClickPosition)
            }
        }
    } else if (type == 9) {
        val itemType = data.get("itemType").asInt
        val newVal: JsonElement = when (itemType) {
            1 -> JsonPrimitive(0.toByte()); 2 -> JsonPrimitive(0.toShort())
            3 -> JsonPrimitive(0); 4 -> JsonPrimitive(0L)
            5 -> JsonPrimitive(0.0f); 6 -> JsonPrimitive(0.0)
            8 -> JsonPrimitive(""); 10 -> JsonObject(); else -> JsonPrimitive(0)
        }
        if (editorVM.isTreeMode.value) data.getAsJsonArray("v").add(newVal)
        else editorVM.findOriginalListData()?.add(newVal) ?: data.getAsJsonArray("v").add(newVal)
        refreshAfterEdit(); toast("Added to list")
    }
}

// ============================================
// 添加子项对话框
// ============================================
fun MainActivity.showAddChildDialog(parent: JsonObject) {
    val types = arrayOf("Byte(1)","Short(2)","Int(3)","Long(4)","Float(5)","Double(6)",
        "ByteArray(7)","String(8)","List(9)","Compound(10)","IntArray(11)","LongArray(12)")
    val ids = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
    AlertDialog.Builder(this).setTitle("Add Child").setItems(types) { _, w ->
        showNameInputDialog(parent, ids[w])
    }.show()
}

private fun MainActivity.showNameInputDialog(parent: JsonObject, type: Int) {
    val input = EditText(this).apply { hint = "Name" }
    AlertDialog.Builder(this).setTitle("New Tag").setView(input)
        .setPositiveButton("Create") { _, _ ->
            val name = input.text.toString()
            if (name.isEmpty() || parent.has(name)) { toast("Invalid name"); return@setPositiveButton }
            val t = JsonObject().apply { addProperty("t", type) }
            when (type) {
                1 -> t.addProperty("v", 0.toByte()); 2 -> t.addProperty("v", 0.toShort())
                3 -> t.addProperty("v", 0); 4 -> t.addProperty("v", 0L)
                5 -> t.addProperty("v", 0.0f); 6 -> t.addProperty("v", 0.0)
                7 -> t.add("v", JsonArray()); 8 -> t.addProperty("v", "")
                9 -> { t.add("v", JsonArray()); t.addProperty("itemType", 10) }
                10 -> t.add("v", JsonObject()); 11 -> t.add("v", JsonArray())
                12 -> t.add("v", JsonArray())
            }
            parent.add(name, t)
            if (nbtAdapter?.data === parent) nbtAdapter?.refreshKeys()
            toast("Created: $name")
        }.show()
}

// ============================================
// 编辑值对话框（带自动填充）
// ============================================
fun MainActivity.showEditValueDialog(key: String, item: JsonObject) {
    val type = item.get("t").asInt
    if (type == 9 || type == 10) { toast("Click to enter"); return }

    val input = EditText(this)
    if (item.has("v")) {
        val v = item.get("v")
        input.setText(if (v.isJsonArray) v.toString() else v.asString)
    }

    var showAutocomplete = false
    var detectedType = "all"
    val parent = if (editorVM.pathStack.isNotEmpty()) editorVM.pathStack.peek() else ""
    val grandParent = if (editorVM.pathStack.size >= 2) editorVM.pathStack[editorVM.pathStack.size - 2] else ""

    if (key in listOf("Name", "id", "name", "Id", "AttributeName")) {
        detectedType = detectAutocompleteType(parent, grandParent)
        showAutocomplete = true
    }
    if ((type == 1 || type == 2) && key == "Id") {
        val dt = detectAutocompleteType(parent, grandParent)
        if (dt != "all") { detectedType = dt; showAutocomplete = true }
    }

    val builder = AlertDialog.Builder(this)
    builder.setTitle("Edit [${getTypeName(type)}] $key")

    if (showAutocomplete) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(input)
        layout.addView(Button(this).apply {
            text = "Choose ${getTypeLabel(detectedType)}"
            setTextColor("#2196F3".toColorInt())
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showAutocompleteDialog(input, detectedType) }
        })
        builder.setView(layout)
    } else {
        builder.setView(input)
    }

    builder.setPositiveButton("Save") { _, _ ->
        try {
            val value = input.text.toString().trim()
            when (type) {
                in 1..3 -> item.addProperty("v", value.toInt())
                4 -> item.addProperty("v", value.toLong())
                in 5..6 -> item.addProperty("v", value.toDouble())
                8 -> item.addProperty("v", value)
                7, 11, 12 -> item.add("v", JsonParser.parseString(value).asJsonArray)
            }
            refreshAfterEdit()
        } catch (e: Exception) { toast("Save failed: ${e.message}") }
    }.show()
}

fun getTypeName(t: Int): String = when (t) {
    1 -> "Byte"; 2 -> "Short"; 3 -> "Int"; 4 -> "Long"; 5 -> "Float"; 6 -> "Double"
    7 -> "ByteArray"; 8 -> "String"; 11 -> "IntArray"; 12 -> "LongArray"; else -> "?"
}

// ============================================
// 数组分页
// ============================================
fun MainActivity.showArrayPaginationDialog(key: String, arr: JsonArray) {
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(50, 30, 50, 0)
    }
    layout.addView(TextView(this).apply {
        text = "Huge array: ${arr.size()} elements"; textSize = 16f
    })
    val etStart = EditText(this).apply {
        hint = "Start (0)"; inputType = InputType.TYPE_CLASS_NUMBER; setText("0")
    }
    val etCount = EditText(this).apply {
        hint = "Count (~200)"; inputType = InputType.TYPE_CLASS_NUMBER; setText("200")
    }
    layout.addView(etStart); layout.addView(etCount)

    if (key == "colors" && arr.size() >= 16384) {
        layout.addView(Button(this).apply {
            text = "Import Image → Map"
            setOnClickListener {
                currentTargetMapArray = arr
                startActivityForResult(
                    android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(android.content.Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    },
                    MainActivity.REQUEST_PICK_IMAGE_FOR_MAP
                )
            }
        })
    }
    AlertDialog.Builder(this).setTitle("Manage [$key]").setView(layout)
        .setPositiveButton("View/Edit") { _, _ ->
            try {
                var s = etStart.text.toString().toInt()
                var c = etCount.text.toString().toInt()
                if (s < 0) s = 0; if (c > 2000) c = 2000
                if (s + c > arr.size()) c = arr.size() - s
                showSubArrayEditDialog(arr, s, c)
            } catch (_: NumberFormatException) { toast("Enter valid numbers") }
        }.setNegativeButton("Close", null).show()
}

private fun MainActivity.showSubArrayEditDialog(original: JsonArray, start: Int, count: Int) {
    val sb = StringBuilder("[")
    for (i in 0..<count) { sb.append(original[start + i]); if (i < count - 1) sb.append(",") }
    sb.append("]")
    val input = EditText(this).apply { setText(sb.toString()) }
    AlertDialog.Builder(this).setTitle("Edit [$start ~ ${start + count - 1}]").setView(input)
        .setPositiveButton("Save") { _, _ ->
            try {
                val parsed = JsonParser.parseString(input.text.toString())
                if (!parsed.isJsonArray || parsed.asJsonArray.size() != count) {
                    toast("Count mismatch"); return@setPositiveButton
                }
                for (i in 0..<count) original[start + i] = parsed.asJsonArray[i]
                refreshAfterEdit(); toast("Saved")
            } catch (e: Exception) { toast("Format error: ${e.message}") }
        }.setNegativeButton("Cancel", null).show()
}

// ============================================
// NBT 搜索
// ============================================
fun MainActivity.showEditorSearchDialog() {
    val et = EditText(this).apply { hint = "Search key or translation"; isSingleLine = true }
    val dialog = AlertDialog.Builder(this).setTitle("Search NBT").setView(et)
        .setPositiveButton("Close", null)
        .setNeutralButton("Clear") { _, _ -> nbtAdapter?.filter(null) }
        .create()
    et.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {}
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {
            nbtAdapter?.filter(s.toString())
        }
    })
    dialog.setOnShowListener {
        et.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?)
            ?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
    }
    dialog.show()
}

// ============================================
// 视图选项
// ============================================
fun MainActivity.showViewOptionsDialog() {
    val items = arrayOf("Raw", "Raw+Trans", "Smart", "Simple")
    AlertDialog.Builder(this).setTitle("View Mode")
        .setSingleChoiceItems(items, editorVM.viewMode.value) { d, which ->
            editorVM.setViewMode(which)
            prefs?.edit { putInt("view_mode", which) }
            nbtAdapter?.setViewMode(which); nbtTreeAdapter?.setViewMode(which)
            d.dismiss(); toast("Mode changed")
        }.show()
}

// ============================================
// 按名称打开 Key
// ============================================
fun MainActivity.showOpenByKeyDialog() {
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 20)
    }
    layout.addView(TextView(this).apply { text = "Open NBT by name/hex key" })
    val etKey = EditText(this); layout.addView(etKey)
    val cbHex = CheckBox(this).apply { text = "Hex mode" }; layout.addView(cbHex)
    AlertDialog.Builder(this).setTitle("Open by Key").setView(layout)
        .setPositiveButton("Confirm") { _, _ ->
            val input = etKey.text.toString()
            if (input.isEmpty()) { toast("Input cannot be empty"); return@setPositiveButton }
            worldVM.loadCustomKey(input, cbHex.isChecked)
        }.setNegativeButton("Cancel", null).show()
}

// ============================================
// 应用信息
// ============================================
@Suppress("DEPRECATION")
fun MainActivity.showAppInfoDialog() {
    val dialogView = layoutInflater.inflate(R.layout.dialog_app_info, null, false)
    val dialog = AlertDialog.Builder(this).setView(dialogView).create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.show()
    dialog.window?.let {
        it.attributes.width = (resources.displayMetrics.widthPixels * 0.85).toInt()
        it.attributes = it.attributes
    }

    val aboutHeader = dialogView.findViewById<LinearLayout?>(R.id.layout_about_header)
    val aboutContent = dialogView.findViewById<TextView?>(R.id.tv_about_content)
    val aboutExpand = dialogView.findViewById<TextView?>(R.id.tv_about_expand)
    aboutContent?.let {
        it.text = Html.fromHtml(getString(R.string.msg_about_content_full).replace("\n", "<br>"))
        it.movementMethod = LinkMovementMethod.getInstance()
    }
    aboutHeader?.setOnClickListener {
        aboutContent?.let {
            it.isVisible = !it.isVisible
            aboutExpand?.text = if (it.isVisible) "▲" else "▼"
        }
    }

    val settingsHeader = dialogView.findViewById<LinearLayout?>(R.id.layout_settings_header)
    val settingsContent = dialogView.findViewById<LinearLayout?>(R.id.layout_settings_content)
    val settingsExpand = dialogView.findViewById<TextView?>(R.id.tv_settings_expand)
    settingsHeader?.setOnClickListener {
        settingsContent?.let {
            it.isVisible = !it.isVisible
            settingsExpand?.text = if (it.isVisible) "▲" else "▼"
        }
    }

    dialogView.findViewById<CheckBox?>(R.id.cb_dialog_multithread)?.let {
        it.isChecked = prefs?.getBoolean("use_multithread", true) ?: true
        it.setOnCheckedChangeListener { _, c -> prefs?.edit { putBoolean("use_multithread", c) } }
    }
    dialogView.findViewById<CheckBox?>(R.id.cb_dialog_shizuku)?.let {
        it.isChecked = prefs?.getBoolean("use_shizuku", true) ?: true
        it.setOnCheckedChangeListener { _, c ->
            worldVM.setUseShizuku(c); prefs?.edit { putBoolean("use_shizuku", c) }
        }
    }
    dialogView.findViewById<Button?>(R.id.btn_dialog_theme)?.let {
        it.text = if (isNightMode) "Switch Day" else "Switch Night"
        it.setOnClickListener {
            isNightMode = !isNightMode; prefs?.edit { putBoolean("night_mode", isNightMode) }
            applyTheme(); dialog.dismiss(); recreate()
        }
    }
    dialogView.findViewById<Button?>(R.id.btn_dialog_lang)?.let {
        it.text = if ("zh" == currentLang) "Switch to English" else "切换到中文"
        it.setOnClickListener {
            currentLang = if ("zh" == currentLang) "en" else "zh"
            prefs?.edit { putString("app_language", currentLang) }
            NbtTranslator.reset(); dialog.dismiss(); recreate()
        }
    }

    var clicks = 0; var lastClick = 0L
    dialogView.findViewById<View?>(R.id.in_app_icon)?.setOnClickListener {
        val now = System.currentTimeMillis()
        if (now - lastClick > 5000) clicks = 0
        lastClick = now; clicks++
        if (clicks >= 10) { clicks = 0; showDebugMenu() }
    }
}

// ============================================
// 调试菜单
// ============================================
fun MainActivity.showDebugMenu() {
    AlertDialog.Builder(this).setTitle("Debug")
        .setItems(arrayOf("Crash Test", "Show Logs", "Copy Logs")) { _, w ->
            when (w) {
                0 -> Handler(Looper.getMainLooper()).postDelayed(
                    { throw RuntimeException("Manual crash test") }, 1000
                )
                1 -> {
                    val sv = ScrollView(this)
                    val tv = TextView(this).apply {
                        text = "Logs loaded"; typeface = Typeface.MONOSPACE
                        textSize = 12f; setPadding(20, 20, 20, 20)
                    }
                    sv.addView(tv)
                    AlertDialog.Builder(this).setTitle("Logs").setView(sv)
                        .setPositiveButton("Close", null).show()
                }
                2 -> {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Logs", "Debug logs"))
                    toast("Copied")
                }
            }
        }.show()
}

// ============================================
// 辅助：编辑后刷新
// ============================================
private fun MainActivity.refreshAfterEdit() {
    if (editorVM.isTreeMode.value) nbtTreeAdapter?.notifyDataSetChanged()
    else nbtAdapter?.refreshKeys()
}