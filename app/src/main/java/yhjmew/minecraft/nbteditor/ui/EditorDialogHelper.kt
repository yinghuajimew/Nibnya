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
import android.view.View
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
import yhjmew.minecraft.nbteditor.AppLogger
import yhjmew.minecraft.nbteditor.MainActivity
import yhjmew.minecraft.nbteditor.NbtTranslator
import yhjmew.minecraft.nbteditor.R

// ============================================
// 根菜单
// ============================================
fun MainActivity.showRootMenu() {
    val current = editorVM.nbtData.value ?: run { toast(getString(R.string.toast_no_data)); return }
    val ops = arrayOf(getString(R.string.action_add_tag), getString(R.string.action_paste_tag), getString(R.string.action_copy_root), getString(R.string.action_paste_root), getString(R.string.action_clear_all))
    AlertDialog.Builder(this).setTitle(getString(R.string.menu_root_title)).setItems(ops) { _, w ->
        when (w) {
            0 -> showAddChildDialog(current)
            1 -> {
                if (MainActivity.clipboard == null) { toast(getString(R.string.toast_clipboard_empty)); return@setItems }
                val input = EditText(this).apply { hint = getString(R.string.hint_new_tag_name) }
                AlertDialog.Builder(this).setTitle(getString(R.string.title_paste_as)).setView(input)
                    .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                        val n = input.text.toString()
                        if (n.isNotEmpty() && !current.has(n)) {
                            current.add(n, MainActivity.clipboard!!.deepCopy())
                            refreshAfterEdit(); toast(getString(R.string.toast_pasted))
                        } else toast(getString(R.string.toast_name_empty_or_exists))
                    }.show()
            }
            2 -> {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("NBT", current.toString()))
                toast(getString(R.string.toast_copy_success))
            }
            3 -> {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                if (!cm.hasPrimaryClip()) { toast(getString(R.string.toast_clipboard_empty)); return@setItems }
                AlertDialog.Builder(this).setTitle(getString(R.string.msg_confirm_replace)).setMessage(getString(R.string.msg_replace_all_data))
                    .setPositiveButton(getString(R.string.btn_replace)) { _, _ ->
                        try {
                            val text = cm.primaryClip!!.getItemAt(0).text ?: return@setPositiveButton
                            val newObj = JsonParser.parseString(text.toString()).asJsonObject
                            ArrayList(current.keySet()).forEach { current.remove(it) }
                            newObj.entrySet().forEach { current.add(it.key, it.value) }
                            refreshAfterEdit(); toast(getString(R.string.toast_replaced))
                        } catch (_: Exception) { toast(getString(R.string.err_json_parse)) }
                    }.setNegativeButton(getString(R.string.btn_cancel), null).show()
            }
            4 -> AlertDialog.Builder(this).setTitle(getString(R.string.dialog_danger_title)).setMessage(getString(R.string.btn_confirm_clear))
                .setPositiveButton(getString(R.string.btn_clear)) { _, _ ->
                    ArrayList(current.keySet()).forEach { current.remove(it) }
                    refreshAfterEdit(); toast(getString(R.string.toast_cleared))
                }.setNegativeButton(getString(R.string.btn_cancel), null).show()
        }
    }.show()
}

// ============================================
// 长按菜单
// ============================================
fun MainActivity.showLongPressMenu(key: String?, itemData: JsonObject) {
    val ops = arrayOf(getString(R.string.menu_copy), getString(R.string.menu_paste), getString(R.string.menu_delete), getString(R.string.menu_rename), getString(R.string.menu_add_child))
    AlertDialog.Builder(this).setTitle(getString(R.string.title_manage, key)).setItems(ops) { _, w ->
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
                toast(getString(R.string.toast_copied))
            }
            1 -> {
                if (MainActivity.clipboard == null) { toast(getString(R.string.toast_clipboard_empty)); return@setItems }
                val pasteKey = nodeKey + "_copy"
                if (parent.has(pasteKey)) { toast(getString(R.string.toast_name_exists)); return@setItems }
                parent.add(pasteKey, MainActivity.clipboard!!.deepCopy())
                refreshAfterEdit(); toast(getString(R.string.toast_pasted))
            }
            2 -> { parent.remove(nodeKey); refreshAfterEdit(); toast(getString(R.string.toast_deleted)) }
            3 -> showRenameDialog(nodeKey, nodeData, parent)
            4 -> addChildToNode(nodeData)
        }
    }.show()
}

// ============================================
// 重命名
// ============================================
private fun MainActivity.showRenameDialog(oldKey: String?, itemData: JsonObject?, parent: JsonObject) {
    val input = EditText(this).apply { setText(oldKey) }
    AlertDialog.Builder(this).setTitle(getString(R.string.title_rename)).setView(input)
        .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
            val nk = input.text.toString().trim()
            if (nk.isEmpty() || parent.has(nk)) { toast(getString(R.string.toast_Invalid_name)); return@setPositiveButton }
            parent.remove(oldKey); parent.add(nk, itemData)
            refreshAfterEdit(); toast(getString(R.string.toast_renamed))
        }.show()
}

// ============================================
// 添加子项到节点
// ============================================
private fun MainActivity.addChildToNode(nodeData: JsonObject?) {
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
        refreshAfterEdit(); toast(getString(R.string.toast_added_to_list))
    }
}

// ============================================
// 添加子项对话框
// ============================================
fun MainActivity.showAddChildDialog(parent: JsonObject) {
    val types = arrayOf("Byte(1)","Short(2)","Int(3)","Long(4)","Float(5)","Double(6)",
        "ByteArray(7)","String(8)","List(9)","Compound(10)","IntArray(11)","LongArray(12)")
    val ids = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
    AlertDialog.Builder(this).setTitle(getString(R.string.menu_add_child)).setItems(types) { _, w ->
        showNameInputDialog(parent, ids[w])
    }.show()
}

private fun MainActivity.showNameInputDialog(parent: JsonObject, type: Int) {
    val input = EditText(this).apply { hint = getString(R.string.text_name) }
    AlertDialog.Builder(this).setTitle(getString(R.string.hint_new_tag_name)).setView(input)
        .setPositiveButton(getString(R.string.btn_create)) { _, _ ->
            val name = input.text.toString()
            if (name.isEmpty() || parent.has(name)) { toast(getString(R.string.toast_Invalid_name)); return@setPositiveButton }
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
            toast(getString(R.string.toast_created_item, name))
        }.show()
}

// ============================================
// 编辑值对话框（带自动填充）
// ============================================
fun MainActivity.showEditValueDialog(key: String, item: JsonObject) {
    val type = item.get("t").asInt
    if (type == 9 || type == 10) { toast(getString(R.string.toast_click_detail)); return }

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
    builder.setTitle(getString(R.string.title_edit_type_key, getTypeName(type), key))

    if (showAutocomplete) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(input)
        layout.addView(Button(this).apply {
            text = getString(R.string.title_choose)
            setTextColor("#2196F3".toColorInt())
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showAutocompleteDialog(input, detectedType) }
        })
        builder.setView(layout)
    } else {
        builder.setView(input)
    }

    builder.setPositiveButton(getString(R.string.btn_save)) { _, _ ->
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
        } catch (e: Exception) { toast(getString(R.string.err_save_failed, e.message)) }
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
        text = getString(R.string.text_huge_array_detected, arr.size()); textSize = 16f
    })
    val etStart = EditText(this).apply {
        hint = getString(R.string.hint_start_0); inputType = InputType.TYPE_CLASS_NUMBER; setText("0")
    }
    val etCount = EditText(this).apply {
        hint = getString(R.string.hint_count_200); inputType = InputType.TYPE_CLASS_NUMBER; setText("200")
    }
    layout.addView(etStart); layout.addView(etCount)

    if (key == "colors" && arr.size() >= 16384) {
        layout.addView(Button(this).apply {
            text = getString(R.string.text_import_images_to_generate_map_images)
            setOnClickListener {
                currentTargetMapArray = arr

                mapImageLauncher.launch(arrayOf("image/*"))
            }
        })
    }
    AlertDialog.Builder(this).setTitle(getString(R.string.title_manage, key)).setView(layout)
        .setPositiveButton(getString(R.string.btn_view_and_edit_clips)) { _, _ ->
            try {
                var s = etStart.text.toString().toInt()
                var c = etCount.text.toString().toInt()
                if (s < 0) s = 0; if (c > 2000) c = 2000
                if (s + c > arr.size()) c = arr.size() - s
                showSubArrayEditDialog(arr, s, c)
            } catch (_: NumberFormatException) { toast(getString(R.string.toast_please_enter_valid_numbers)) }
        }.setNegativeButton(getString(R.string.btn_close), null).show()
}

private fun MainActivity.showSubArrayEditDialog(original: JsonArray, start: Int, count: Int) {
    val sb = StringBuilder("[")
    for (i in 0..<count) { sb.append(original[start + i]); if (i < count - 1) sb.append(",") }
    sb.append("]")
    val input = EditText(this).apply { setText(sb.toString()) }
    AlertDialog.Builder(this).setTitle(getString(R.string.title_edit_range, start, start + count - 1)).setView(input)
        .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
            try {
                val parsed = JsonParser.parseString(input.text.toString())
                if (!parsed.isJsonArray || parsed.asJsonArray.size() != count) {
                    toast(getString(R.string.toast_count_mismatch)); return@setPositiveButton
                }
                for (i in 0..<count) original[start + i] = parsed.asJsonArray[i]
                refreshAfterEdit(); toast(getString(R.string.toast_saved))
            } catch (e: Exception) { toast(getString(R.string.err_format, e.message)) }
        }.setNegativeButton(getString(R.string.btn_cancel), null).show()
}

// ============================================
// NBT 搜索
// ============================================
fun MainActivity.showEditorSearchDialog() {
    val et = EditText(this).apply { hint = getString(R.string.text_search_key_or_translate); isSingleLine = true }
    val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.title_search_nbt_data)).setView(et)
        .setPositiveButton(getString(R.string.btn_close), null)
        .setNeutralButton(getString(R.string.btn_clear)) { _, _ -> nbtAdapter?.filter(null) }
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
    val items = arrayOf(getString(R.string.mode_raw), getString(R.string.mode_raw_trans), getString(R.string.mode_smart), getString(R.string.mode_simple))
    AlertDialog.Builder(this).setTitle(getString(R.string.title_view_mode))
        .setSingleChoiceItems(items, editorVM.viewMode.value) { d, which ->
            editorVM.setViewMode(which)
            prefs?.edit { putInt("view_mode", which) }
            nbtAdapter?.setViewMode(which); nbtTreeAdapter?.setViewMode(which)
            d.dismiss(); toast(getString(R.string.toast_mode_changed))
        }.show()
}

// ============================================
// 按名称打开 Key
// ============================================
fun MainActivity.showOpenByKeyDialog() {
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 20)
    }
    layout.addView(TextView(this).apply { text = getString(R.string.text_open_nbt_by_name_hint) })
    val etKey = EditText(this); layout.addView(etKey)
    val cbHex = CheckBox(this).apply { text = getString(R.string.title_hex_16_hexadecimal_mode) }; layout.addView(cbHex)
    AlertDialog.Builder(this).setTitle(getString(R.string.title_open_nbt_by_name)).setView(layout)
        .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
            val input = etKey.text.toString()
            if (input.isEmpty()) { toast(getString(R.string.toast_input_cannot_be_empty)); return@setPositiveButton }
            worldVM.loadCustomKey(input, cbHex.isChecked)
        }.setNegativeButton(getString(R.string.btn_cancel), null).show()
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
        it.setOnCheckedChangeListener { _, c ->
            worldVM.setUseMultiThread(c)
            prefs?.edit()?.putBoolean("use_multithread", c)?.apply()
        }
    }
    dialogView.findViewById<CheckBox?>(R.id.cb_skip_shizuku)?.let {
        it.isChecked = prefs?.getBoolean("skip_shizuku", false) ?: false
        it.setOnCheckedChangeListener { _, c ->
            worldVM.setUseShizuku(!c)
            prefs?.edit()?.putBoolean("skip_shizuku", c)?.apply()
        }
    }
    dialogView.findViewById<Button?>(R.id.btn_dialog_theme)?.let {
        it.text = if (isNightMode) getString(R.string.action_switch_day) else getString(R.string.action_switch_night)
        it.setOnClickListener {
            isNightMode = !isNightMode; prefs?.edit { putBoolean("night_mode", isNightMode) }
            applyTheme(); dialog.dismiss(); recreate()
        }
    }
    dialogView.findViewById<Button?>(R.id.btn_dialog_lang)?.let {
        it.text = if ("zh" == currentLang) getString(R.string.switch_to_english) else getString(R.string.switch_to_chinese)
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
    AlertDialog.Builder(this).setTitle(getString(R.string.title_debug_menu))
        .setItems(arrayOf(getString(R.string.debug_crash_test), getString(R.string.debug_show_logs), getString(R.string.debug_export_logs), getString(R.string.debug_copy_logs))) { _, w ->
            when (w) {
                0 -> Handler(Looper.getMainLooper()).postDelayed(
                    { throw RuntimeException("Manual crash test") }, 1000
                )
                1 -> {
                    val logs = AppLogger.getLogs()
                    val scrollView = ScrollView(this)
                    val logTv = TextView(this).apply {
                        text = logs.ifEmpty { getString(R.string.msg_no_logs) }
                        typeface = Typeface.MONOSPACE
                        textSize = 12f
                        setPadding(20, 20, 20, 20)
                        setTextIsSelectable(true)
                    }
                    scrollView.addView(logTv)
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.title_logs))
                        .setView(scrollView)
                        .setPositiveButton(getString(R.string.btn_close), null)
                        .setNeutralButton(getString(R.string.menu_copy)) { _, _ ->
                            if (logs.isEmpty()) {
                                toast(getString(R.string.msg_no_logs_to_copy))
                            } else {
                                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText(getString(R.string.title_logs), logs))
                                toast(getString(R.string.toast_logs_copied))
                            }
                        }
                        .setNegativeButton(getString(R.string.btn_export)) { _, _ ->
                            val file = AppLogger.exportToFile()
                            if (file != null) {
                                toast(getString(R.string.toast_logs_exported, file.absolutePath))
                            } else {
                                toast(getString(R.string.msg_no_logs_to_export))
                            }
                        }
                        .show()
                }
                2 -> {
                    val logs = AppLogger.getLogs()
                    if (logs.isEmpty()) {
                        toast(getString(R.string.msg_no_logs_to_copy))
                    } else {
                        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Logs", logs))
                        toast(getString(R.string.toast_logs_copied))
                    }
                }
                3 -> {
                    val file = AppLogger.exportToFile()
                    if (file != null) {
                        toast(getString(R.string.toast_logs_exported, file.absolutePath))
                    } else {
                        toast(getString(R.string.msg_no_logs_to_export))
                    }
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