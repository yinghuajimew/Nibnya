package yhjmew.nibnya.editor.dialogs

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
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
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.core.content.edit
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import yhjmew.nibnya.MainActivity
import yhjmew.nibnya.R
import yhjmew.nibnya.editor.dialogs.ColorSettingsUi
import yhjmew.nibnya.util.ColorApplier
import yhjmew.nibnya.util.ColorScheme
import yhjmew.nibnya.nbt.adapter.NbtTreeAdapter
import yhjmew.nibnya.nbt.NbtTranslator
import yhjmew.nibnya.nbt.NbtTranslator.getString
import yhjmew.nibnya.util.AppLogger
import yhjmew.nibnya.util.BackupSettings
import yhjmew.nibnya.util.DraftAutoSaver
import yhjmew.nibnya.util.DraftStore
import yhjmew.nibnya.util.TrashStore
import yhjmew.nibnya.util.NbtClipboard
import yhjmew.nibnya.util.NbtExporter
import yhjmew.nibnya.util.PrivilegedExec

// ============================================
// 剪贴板（复制 / 粘贴）
// ============================================

/**
 * 复制 / 导出 NBT。两步选择：
 *
 * **第 1 步 选格式**：JSON（可再导入） / SNBT（游戏内格式） / 纯文本（方便阅读）
 *
 * **第 2 步 选目标**：
 * - 复制到剪贴板 —— 应用内保留结构化副本（存盘，进程被杀不丢）并写系统剪贴板
 * - 保存到下载目录 —— `Download/Nibnya/Exports/`，需所有文件访问权限
 * - 选择目录… —— 走 SAF，任何权限都不需要
 *
 * 之前只写应用内存却提示「已复制到剪贴板」，用户切到别处粘贴是空的。
 */
internal fun MainActivity.showCopyExportDialog(element: JsonElement, nameHint: String? = null) {
    val labels = arrayOf(
        getString(R.string.clip_format_json),
        getString(R.string.clip_format_snbt),
        getString(R.string.clip_format_text)
    )
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_copy_as))
        .setItems(labels) { _, w ->
            val fmt = when (w) {
                NbtClipboard.FORMAT_SNBT -> NbtClipboard.FORMAT_SNBT
                NbtClipboard.FORMAT_TEXT -> NbtClipboard.FORMAT_TEXT
                else -> NbtClipboard.FORMAT_JSON
            }
            showExportDestDialog(element, fmt, labels[w], nameHint)
        }
        .setNegativeButton(getString(R.string.btn_cancel), null)
        .show()
}

private fun MainActivity.showExportDestDialog(
    element: JsonElement,
    fmt: Int,
    fmtLabel: String,
    nameHint: String?
) {
    val dests = arrayOf(
        getString(R.string.export_dest_clipboard),
        getString(R.string.export_dest_download),
        getString(R.string.export_dest_choose)
    )
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_export_to))
        .setItems(dests) { _, d ->
            when (d) {
                0 -> {
                    // 应用内结构化副本（落盘）
                    NbtClipboard.set(this, element)
                    // 系统剪贴板（按格式转文本）
                    NbtClipboard.writeToSystemClipboard(this, element, fmt)
                    toast(getString(R.string.toast_copied_format, fmtLabel))
                }
                1 -> exportToDefaultDir(element, fmt, nameHint)
                2 -> startExportToChosenDir(element, fmt, nameHint)
            }
        }
        .setNegativeButton(getString(R.string.btn_cancel), null)
        .show()
}

/** 保存到公共下载目录（需所有文件访问权限；不可写时引导改用 SAF） */
private fun MainActivity.exportToDefaultDir(element: JsonElement, fmt: Int, nameHint: String?) {
    val content = NbtClipboard.formatAs(element, fmt)
    val dir = NbtExporter.defaultExportDir()
    val fileName = NbtExporter.buildFileName(nameHint, fmt)
    val saved = NbtExporter.saveToDir(dir, fileName, content)
    if (saved != null) {
        toast(getString(R.string.toast_exported_to, saved.absolutePath))
    } else {
        // 没权限：提示并引导选目录（SAF 不需要权限）
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.export_dest_download))
            .setMessage(getString(R.string.msg_export_need_permission))
            .setPositiveButton(getString(R.string.export_dest_choose)) { _, _ ->
                startExportToChosenDir(element, fmt, nameHint)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }
}

// ============================================
// 剪贴板（粘贴）
// ============================================

/**
 * 取粘贴来源：
 * 1. 优先用应用内剪贴板（保留类型信息，最准确）
 * 2. 应用内为空时，尝试从系统剪贴板读取（自动识别 JSON / SNBT），
 *    这样从别的来源复制的内容也能直接粘进来
 */
internal fun MainActivity.getPasteSource(): JsonElement? {
    NbtClipboard.get(this)?.let { return it }
    val fromSystem = NbtClipboard.readFromSystemClipboard(this)
    if (fromSystem != null) {
        NbtClipboard.set(this, fromSystem)
        return fromSystem
    }
    return null
}

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
                val clip = getPasteSource()
                if (clip == null) { toast(getString(R.string.toast_clipboard_empty)); return@setItems }
                val input = EditText(this).apply { hint = getString(R.string.hint_new_tag_name) }
                AlertDialog.Builder(this).setTitle(getString(R.string.title_paste_as)).setView(input)
                    .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                        val n = input.text.toString()
                        if (n.isNotEmpty() && !current.has(n)) {
                            current.add(n, clip.deepCopy())
                            refreshAfterEdit(); toast(getString(R.string.toast_pasted))
                        } else toast(getString(R.string.toast_name_empty_or_exists))
                    }.setNegativeButton(getString(R.string.btn_cancel), null).show()
            }
            2 -> {
                showCopyExportDialog(current, etWorldName?.text?.toString()?.trim())
            }
            3 -> {
                val clip = NbtClipboard.readFromSystemClipboard(this)
                if (clip == null || !clip.isJsonObject) {
                    toast(getString(R.string.toast_clipboard_empty)); return@setItems
                }
                val newObj = clip.asJsonObject
                // 若粘进来的是标签包装 {t,v}，取其内层 compound
                val incoming = if (newObj.has("t") && newObj.has("v") && newObj.get("v").isJsonObject)
                    newObj.getAsJsonObject("v") else newObj
                AlertDialog.Builder(this).setTitle(getString(R.string.msg_confirm_replace)).setMessage(getString(R.string.msg_replace_all_data))
                    .setPositiveButton(getString(R.string.btn_replace)) { _, _ ->
                        ArrayList(current.keySet()).forEach { current.remove(it) }
                        incoming.entrySet().forEach { current.add(it.key, it.value.deepCopy()) }
                        NbtClipboard.set(this, incoming)
                        refreshAfterEdit(); toast(getString(R.string.toast_replaced))
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
        if (editorVM.isTreeMode.value) {
            handleTreeLongPress(w, key ?: "", itemData)
        } else {
            handleListLongPress(w, key, itemData)
        }
    }.setNegativeButton(getString(R.string.btn_cancel), null).show()
}

/**
 * 列表模式的长按处理（保持原有行为）
 */
private fun MainActivity.handleListLongPress(w: Int, key: String?, itemData: JsonObject) {
    val parent = nbtAdapter?.data ?: return
    val nodeKey = key ?: return
    when (w) {
        0 -> showCopyExportDialog(itemData, nodeKey)
        1 -> {
            val clip = getPasteSource()
            if (clip == null) { toast(getString(R.string.toast_clipboard_empty)); return }
            val pasteKey = nodeKey + "_copy"
            if (parent.has(pasteKey)) { toast(getString(R.string.toast_name_exists)); return }
            parent.add(pasteKey, clip.deepCopy())
            refreshAfterEdit(); toast(getString(R.string.toast_pasted))
        }
        2 -> { parent.remove(nodeKey); refreshAfterEdit(); toast(getString(R.string.toast_deleted)) }
        3 -> showRenameDialog(nodeKey, itemData, parent)
        4 -> addChildToNode(itemData)
    }
}

/**
 * 树形模式的长按处理。
 * 关键修复：用 getParentRef 区分「父是 compound」和「父是 List」，
 * 之前统一按 compound 处理，导致 List 元素的删除/粘贴落到错误对象上。
 */
private fun MainActivity.handleTreeLongPress(w: Int, fallbackKey: String, fallbackData: JsonObject) {
    val adapter = nbtTreeAdapter ?: return
    val pos = editorVM.lastTreeClickPosition
    // 防止 rebuild 后位置越界（getNode 有边界检查，越界返回 null）
    val node = if (pos in 0 until adapter.count) adapter.getNode(pos) else null
    val nodeData = node?.value as? JsonObject ?: fallbackData
    val nodeKey = node?.key ?: fallbackKey
    // 注意：根级字段的 parent 是虚拟 ROOT（不是 null），它们是普通字段，
    // 可以正常删除/重命名；真正的虚拟 ROOT 节点不会出现在可见列表里。
    val isRoot = node == null

    when (w) {
        0 -> showCopyExportDialog(nodeData, nodeKey)

        1 -> {  // 粘贴
            val clip = getPasteSource()
            if (clip == null) { toast(getString(R.string.toast_clipboard_empty)); return }
            if (isRoot) {
                // 根：作为新字段加入根数据，让用户输入字段名
                val rootObj = editorVM.nbtData.value ?: return
                val input = EditText(this).apply { hint = getString(R.string.hint_new_tag_name) }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.title_paste_as))
                    .setView(input)
                    .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                        val n = input.text.toString().trim()
                        if (n.isEmpty() || rootObj.has(n)) {
                            toast(getString(R.string.toast_name_empty_or_exists))
                        } else {
                            rootObj.add(n, clip.deepCopy())
                            adapter.rebuild()
                            toast(getString(R.string.toast_pasted))
                        }
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
                return
            } else {
                val ref = adapter.getParentRef(pos) ?: run {
                    toast(getString(R.string.toast_operation_failed)); return
                }
                when (ref) {
                    is NbtTreeAdapter.ParentRef.Compound -> {
                        val pasteKey = ref.key + "_copy"
                        if (ref.obj.has(pasteKey)) { toast(getString(R.string.toast_name_exists)); return }
                        ref.obj.add(pasteKey, clip.deepCopy())
                    }
                    is NbtTreeAdapter.ParentRef.ListArr -> {
                        // List 元素：粘贴即覆盖该元素（clip 可能是 {t,v} 包装，取内层值）
                        val newVal = if (clip.isJsonObject && clip.asJsonObject.has("v"))
                            clip.asJsonObject.get("v").deepCopy()
                        else clip.deepCopy()
                        if (ref.index in 0 until ref.arr.size()) ref.arr.set(ref.index, newVal)
                    }
                }
            }
            adapter.rebuild()
            toast(getString(R.string.toast_pasted))
        }

        2 -> {  // 删除
            if (isRoot) { toast(getString(R.string.toast_root_cannot_delete)); return }
            val ref = adapter.getParentRef(pos) ?: run {
                toast(getString(R.string.toast_operation_failed)); return
            }
            when (ref) {
                is NbtTreeAdapter.ParentRef.Compound -> ref.obj.remove(ref.key)
                is NbtTreeAdapter.ParentRef.ListArr -> {
                    if (ref.index in 0 until ref.arr.size()) ref.arr.remove(ref.index)
                }
            }
            adapter.rebuild()
            toast(getString(R.string.toast_deleted))
        }

        3 -> {  // 重命名
            if (isRoot) { toast(getString(R.string.toast_root_cannot_rename)); return }
            val ref = adapter.getParentRef(pos)
            if (ref is NbtTreeAdapter.ParentRef.ListArr) {
                // List 元素由下标决定，没有独立名字
                toast(getString(R.string.toast_list_element_no_name))
                return
            }
            val compound = (ref as? NbtTreeAdapter.ParentRef.Compound)?.obj ?: run {
                toast(getString(R.string.toast_operation_failed)); return
            }
            showTreeRenameDialog(nodeKey, nodeData, compound, adapter)
        }

        4 -> addChildToNode(nodeData)
    }
}

/** 树形模式的重命名（完成后重建树） */
private fun MainActivity.showTreeRenameDialog(
    oldKey: String,
    itemData: JsonObject?,
    parent: JsonObject,
    adapter: NbtTreeAdapter
) {
    val input = EditText(this).apply { setText(oldKey) }
    AlertDialog.Builder(this).setTitle(getString(R.string.title_rename)).setView(input)
        .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
            val nk = input.text.toString().trim()
            if (nk.isEmpty() || parent.has(nk)) {
                toast(getString(R.string.toast_Invalid_name)); return@setPositiveButton
            }
            parent.remove(oldKey)
            parent.add(nk, itemData)
            adapter.rebuild()
            toast(getString(R.string.toast_renamed))
        }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
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
        }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
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
        AppLogger.info("ListDebug", "addChildToNode: List itemType=$itemType, isTreeMode=${editorVM.isTreeMode.value}, 当前List v size=${data.get("v")?.asJsonArray?.size() ?: -1}")
        val newVal: JsonElement = when (itemType) {
            1 -> JsonPrimitive(0.toByte()); 2 -> JsonPrimitive(0.toShort())
            3 -> JsonPrimitive(0); 4 -> JsonPrimitive(0L)
            5 -> JsonPrimitive(0.0f); 6 -> JsonPrimitive(0.0)
            8 -> JsonPrimitive(""); 10 -> JsonObject(); else -> JsonPrimitive(0)
        }
        if (editorVM.isTreeMode.value) {
            data.getAsJsonArray("v").add(newVal)
            AppLogger.info("ListDebug", "addChildToNode(树形): 直接往 data.v 添加")
            // 树形模式：新增元素后必须重建树，否则新元素不显示
            nbtTreeAdapter?.rebuild()
        } else {
            val target = editorVM.findOriginalListData()
            if (target != null) {
                target.add(newVal)
                AppLogger.info("ListDebug", "addChildToNode(列表): findOriginalListData 找到数组，添加后 size=${target.size()}")
            } else {
                data.getAsJsonArray("v").add(newVal)
                AppLogger.warn("ListDebug", "addChildToNode(列表): findOriginalListData 返回 null，fallback 到 data.v 添加")
            }
            // 从源数组重建假 Map，再刷新列表
            editorVM.rebuildFakeMapFromSource()
            nbtAdapter?.refreshKeys()
        }
        toast(getString(R.string.toast_added_to_list))
        return  // 不走到末尾的 refreshAfterEdit
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
    }.setNegativeButton(getString(R.string.btn_cancel), null).show()
}

private fun MainActivity.showNameInputDialog(parent: JsonObject, type: Int) {
    val input = EditText(this).apply { hint = getString(R.string.text_name) }
    AlertDialog.Builder(this).setTitle(getString(R.string.hint_new_tag_name)).setView(input)
        .setPositiveButton(getString(R.string.btn_create)) { _, _ ->
            val name = input.text.toString()
            if (name.isEmpty() || parent.has(name)) { toast(getString(R.string.toast_Invalid_name)); return@setPositiveButton }
            AppLogger.info("ListDebug", "showNameInputDialog: 新增标签 name=$name, type=$type, parent.keys=${parent.keySet().joinToString(",") { it }}")
            val t = JsonObject().apply { addProperty("t", type) }
            when (type) {
                1 -> t.addProperty("v", 0.toByte()); 2 -> t.addProperty("v", 0.toShort())
                3 -> t.addProperty("v", 0); 4 -> t.addProperty("v", 0L)
                5 -> t.addProperty("v", 0.0f); 6 -> t.addProperty("v", 0.0)
                7 -> t.add("v", JsonArray()); 8 -> t.addProperty("v", "")
                9 -> { t.add("v", JsonArray()); t.addProperty("itemType", 10); AppLogger.info("ListDebug", "新增的是 List(9) 标签, itemType=10, v=[]") }
                10 -> t.add("v", JsonObject()); 11 -> t.add("v", JsonArray())
                12 -> t.add("v", JsonArray())
            }
            parent.add(name, t)
            AppLogger.info("ListDebug", "新增后 parent.keys=${parent.keySet().joinToString(",") { it }}, 是否已添加=$name=${parent.has(name)}")
            if (editorVM.isTreeMode.value) {
                // 树形模式：重建树，新增的字段才会出现
                nbtTreeAdapter?.rebuild()
                AppLogger.info("ListDebug", "树形模式：已 rebuild")
            } else if (nbtAdapter?.data === parent) {
                nbtAdapter?.refreshKeys()
            } else {
                AppLogger.warn("ListDebug", "nbtAdapter.data !== parent，不刷新（parent 可能不是当前显示层）")
            }
            toast(getString(R.string.toast_created_item, name))
        }
        .setNegativeButton(getString(R.string.btn_cancel), null)
        .show()
}

// ============================================
// 编辑值对话框（带自动填充）
// ============================================
fun MainActivity.showEditValueDialog(key: String, item: JsonObject, listElementInfo: Pair<JsonArray, Int>? = null) {
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
            // 用「🔍 从列表选择」这类明确说法，而不是光秃秃的「选择」——
            // 玩家才知道这个按钮是干什么的
            text = getString(R.string.title_choose)
            isAllCaps = false
            setTextColor(ContextCompat.getColor(context, R.color.c_accent))
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
                1 -> {
                    val b = value.toInt()
                    if (b < Byte.MIN_VALUE || b > Byte.MAX_VALUE)
                        throw IllegalArgumentException(getString(R.string.msg_value_out_of_range, "Byte", "-128", "127"))
                    item.addProperty("v", b.toByte())
                }
                2 -> {
                    val s = value.toInt()
                    if (s < Short.MIN_VALUE || s > Short.MAX_VALUE)
                        throw IllegalArgumentException(getString(R.string.msg_value_out_of_range, "Short", "-32768", "32767"))
                    item.addProperty("v", s.toShort())
                }
                3 -> item.addProperty("v", value.toInt())
                4 -> item.addProperty("v", value.toLong())
                5, 6 -> item.addProperty("v", value.toDouble())
                8 -> item.addProperty("v", value)
                7, 11, 12 -> item.add("v", JsonParser.parseString(value).asJsonArray)
            }
            // List 元素编辑：把新值写回原数组，避免树形模式下临时 wrapper 丢失数据
            listElementInfo?.let { (arr, idx) ->
                val newV = item.get("v")
                if (newV != null && idx >= 0 && idx < arr.size()) {
                    arr.set(idx, newV)
                }
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
            text = getString(R.string.text_import_map_images)
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
            } catch (_: NumberFormatException) { toast(getString(R.string.toast_invalid_number)) }
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

    // 树形 / 列表两种模式都支持搜索
    fun applyFilter(q: String?) {
        if (editorVM.isTreeMode.value) nbtTreeAdapter?.filter(q)
        else nbtAdapter?.filter(q)
    }

    val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.title_search_nbt_data)).setView(et)
        .setPositiveButton(getString(R.string.btn_close), null)
        .setNeutralButton(getString(R.string.btn_clear)) { _, _ -> applyFilter(null); et.setText("") }
        .create()
    et.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {}
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {
            applyFilter(s.toString())
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

    // 宽度固定 85%，高度按内容自适应但**不超过屏幕 85%** —— 否则内容一多
    // 就会超出屏幕，底部的选项既看不到也点不到（布局根节点是 ScrollView，
    // 有了高度上限才能真正滚动起来）。
    val metrics = resources.displayMetrics
    val winWidth = (metrics.widthPixels * 0.85).toInt()
    val maxHeight = (metrics.heightPixels * 0.85).toInt()
    dialog.window?.let { w ->
        // 先按不限高测量一次内容高度，再取「内容高」与「上限」的较小值
        dialogView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(winWidth, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        )
        val contentHeight = dialogView.measuredHeight
        val targetHeight = if (contentHeight in 1 until maxHeight) contentHeight else maxHeight
        w.setLayout(winWidth, targetHeight)
    }

    // 展开/收起时重新计算高度，保证内容变化后依然可滚动
    fun remeasure() {
        dialogView.post {
            dialog.window?.let { w ->
                dialogView.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(winWidth, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
                )
                val h = dialogView.measuredHeight
                w.setLayout(winWidth, if (h in 1 until maxHeight) h else maxHeight)
            }
        }
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
            remeasure()
        }
    }

    val settingsHeader = dialogView.findViewById<LinearLayout?>(R.id.layout_settings_header)
    val settingsContent = dialogView.findViewById<LinearLayout?>(R.id.layout_settings_content)
    val settingsExpand = dialogView.findViewById<TextView?>(R.id.tv_settings_expand)
    settingsHeader?.setOnClickListener {
        settingsContent?.let {
            it.isVisible = !it.isVisible
            settingsExpand?.text = if (it.isVisible) "▲" else "▼"
            remeasure()
        }
    }

    // ============================================
    // 设置分类标签栏：外观 / 编辑器 / 数据 / 高级
    // ============================================
    // 每个分类是一个独立的 LinearLayout 页面，切换只改可见性。
    // 所有控件仍在这棵 View 树里，findViewById 照常能取到，
    // 所以下面各段的绑定代码不用改，也不会因为切换标签而失效。
    // 供配色区回调：主题变更后重刷标签栏颜色（2026-11-02）
    var refreshSettingsTabs: (() -> Unit)? = null

    run {
        class Tab(val tabId: Int, val pageId: Int)

        val tabs = listOf(
            Tab(R.id.tab_settings_appearance, R.id.page_settings_appearance),
            Tab(R.id.tab_settings_editor, R.id.page_settings_editor),
            Tab(R.id.tab_settings_data, R.id.page_settings_data),
            Tab(R.id.tab_settings_advanced, R.id.page_settings_advanced),
            Tab(R.id.tab_settings_debug, R.id.page_settings_debug),
        )

        // 标签栏配色走色槽，跟随主题（2026-11-02）
        fun slotColor(key: String, fallback: Int): Int {
            val p = prefs ?: return fallback
            val ctx = dialogView.context
            val slot = yhjmew.nibnya.util.ColorSlots.build(ctx).firstOrNull { it.key == key }
                ?: return fallback
            return yhjmew.nibnya.util.ColorScheme.value(
                p, slot, yhjmew.nibnya.util.ColorScheme.isNight(ctx),
            )
        }
        fun accentNow() = slotColor("c_accent",
            ContextCompat.getColor(dialogView.context, R.color.nbt_key))
        fun dimNow() = slotColor("c_tab_unselected",
            ContextCompat.getColor(dialogView.context, R.color.tab_bar_text))
        fun selectedBgNow() = slotColor("c_tab_selected",
            ContextCompat.getColor(dialogView.context, R.color.tab_bar_selected_bg))

        fun selectTab(current: Tab) {
            tabs.forEach { tab ->
                val tv = dialogView.findViewById<TextView?>(tab.tabId)
                val page = dialogView.findViewById<View?>(tab.pageId)
                val on = tab == current
                page?.isVisible = on
                tv?.let {
                    it.setTextColor(if (on) accentNow() else dimNow())
                    it.setTypeface(
                        null,
                        if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                    )
                    it.setBackgroundColor(
                        if (on) selectedBgNow() else android.graphics.Color.TRANSPARENT
                    )
                }
            }
            // 高度按当前页重算：外观只有两项、编辑器很长，不重算会留一大块空白
            remeasure()
        }

        // 默认停在「外观」
        var currentTab = tabs.first()
        selectTab(currentTab)
        tabs.forEach { tab ->
            dialogView.findViewById<View?>(tab.tabId)?.setOnClickListener {
                currentTab = tab
                selectTab(tab)
            }
        }

        // 暴露给配色区：主题变更后重刷标签栏颜色
        refreshSettingsTabs = {
            selectTab(currentTab)
        }
    }

    // ============================================
    // 配色（外观页底部，整块由代码构建）
    // ============================================
    // 色槽清单来自 ColorSlots 注册表，加一项不需要改布局。
    // 每次切标签 / 调完颜色都重建一次，保证显示的是当前值。
    dialogView.findViewById<LinearLayout?>(R.id.layout_color_settings)?.let { box ->
        prefs?.let { p ->
            fun rebuild() {
                ColorSettingsUi.build(this, p, box) {
                    // ⚠️ 必须 post 到下一帧再重建：
                    //    rebuild() 会 removeAllViews() 把整片列表销毁重建，
                    //    在点击回调里同步做，等于把正在派发事件的 View 抽走，
                    //    第二次点击会落到已分离的旧 View 上 —— 表现就是「只能点一次」。
                    box.post { rebuild() }
                    // 对话框自身跟着变（徽章圆底 / 顶部渐变）
                    ColorApplier.applyToDialogView(
                        dialogView, p, ColorScheme.isNight(this),
                        R.id.badge_settings_circle, R.id.badge_about_circle,
                    )
                    // 主页立刻刷成新颜色 —— 不用关对话框就能看到效果
                    // ⚠️ 之前漏了这行：改完要关掉对话框重进才生效（2026-11-02）
                    box.post { ColorApplier.apply(this, p) }
                    // 设置页四个分类标签也要跟着换色（2026-11-02）
                    box.post { refreshSettingsTabs?.invoke() }
                    remeasure()
                }
            }
            rebuild()

            // 打开对话框时先按用户配色刷一遍（徽章圆底 / 顶部渐变）
            ColorApplier.applyToDialogView(
                dialogView, p, ColorScheme.isNight(this),
                R.id.badge_settings_circle, R.id.badge_about_circle,
            )
        }
    }

    // ① 文件操作多线程
    dialogView.findViewById<CheckBox?>(R.id.cb_dialog_multithread)?.let { cb ->
        cb.isChecked = prefs?.getBoolean("use_multithread", true) ?: true
        cb.setOnCheckedChangeListener { _, c ->
            worldVM.setUseMultiThread(c)
            prefs?.edit()?.putBoolean("use_multithread", c)?.apply()
            // 关闭多线程时，并发数选择失效（置灰）
            dialogView.findViewById<LinearLayout?>(R.id.layout_io_thread_count)?.alpha =
                if (c) 1f else 0.4f
        }
    }
    setupThreadCountSpinner(
        dialogView, R.id.sp_io_thread_count, R.id.layout_io_thread_count,
        prefKey = "io_thread_count",
        enabled = prefs?.getBoolean("use_multithread", true) ?: true,
        onChanged = { worldVM.setIoThreadCount(it) },
        customBtnId = R.id.btn_custom_io_thread_count
    )

    // ② 拼图生成多线程
    dialogView.findViewById<CheckBox?>(R.id.cb_dialog_puzzle_multithread)?.let { cb ->
        cb.isChecked = prefs?.getBoolean("puzzle_multithread", true) ?: true
        cb.setOnCheckedChangeListener { _, c ->
            mapVM.setPuzzleMultiThread(c)
            prefs?.edit()?.putBoolean("puzzle_multithread", c)?.apply()
            dialogView.findViewById<LinearLayout?>(R.id.layout_puzzle_thread_count)?.alpha =
                if (c) 1f else 0.4f
        }
    }
    setupThreadCountSpinner(
        dialogView, R.id.sp_puzzle_thread_count, R.id.layout_puzzle_thread_count,
        prefKey = "puzzle_thread_count",
        enabled = prefs?.getBoolean("puzzle_multithread", true) ?: true,
        onChanged = { mapVM.setPuzzleThreadCount(it) },
        customBtnId = R.id.btn_custom_puzzle_thread_count
    )

    // ③ 裁剪框最小尺寸（太小的框四个角把手会重叠，导致拖不动）
    bindPercentSpinner(
        sp = dialogView.findViewById(R.id.sp_puzzle_crop_min),
        presetPercent = listOf(8, 15, 25, 40),
        titleRes = R.string.title_crop_min_size,
        invalidMsgRes = R.string.msg_crop_min_invalid,
        currentPercent = (prefs?.getFloat("puzzle_crop_min", 0.25f) ?: 0.25f) * 100f,
        onApply = { pct ->
            prefs?.edit()?.putFloat("puzzle_crop_min", pct / 100f)?.apply()
        },
        customBtn = dialogView.findViewById(R.id.btn_custom_puzzle_crop_min)
    )

    // ⓪-1 运行模式选择器（行内展开，不弹窗）
    dialogView.findViewById<View?>(R.id.layout_exec_mode_row)?.let { row ->
        val valueTv = dialogView.findViewById<TextView?>(R.id.tv_exec_mode_value)
        val arrowTv = dialogView.findViewById<TextView?>(R.id.tv_exec_mode_arrow)
        val options = dialogView.findViewById<View?>(R.id.layout_exec_mode_options)
        val suBox = dialogView.findViewById<View?>(R.id.layout_su_template)

        fun modeLabel(key: String): String = when (key) {
            "root" -> getString(R.string.exec_mode_root)
            "shizuku" -> getString(R.string.exec_mode_shizuku)
            "normal" -> getString(R.string.exec_mode_normal)
            else -> getString(R.string.exec_mode_auto)
        }

        fun syncUi() {
            val selected = PrivilegedExec.selectedModePref(this)
            valueTv?.text = modeLabel(selected)
            // su 模板只在 ROOT 相关时显示（选了 root，或自动且真的检测到 root）
            val showSu = selected == "root" ||
                (selected == "auto" && PrivilegedExec.detectRoot(this))
            suBox?.isVisible = showSu
        }

        row.setOnClickListener {
            options?.let {
                it.isVisible = !it.isVisible
                arrowTv?.text = if (it.isVisible) "▲" else "▼"
                remeasure()
            }
        }

        fun pick(key: String) {
            prefs?.edit()?.putString(PrivilegedExec.PREF_EXEC_MODE, key)?.apply()
            // 立刻按新选择重新解析一次实际模式（可能降级）
            val resolved = PrivilegedExec.resolveMode(this, key)
            PrivilegedExec.setMode(resolved)
            worldVM.setUseShizuku(resolved != PrivilegedExec.Mode.NORMAL)
            options?.isVisible = false
            arrowTv?.text = "▼"
            syncUi()
            remeasure()
            toast(getString(R.string.status_root_ok).takeIf { resolved == PrivilegedExec.Mode.ROOT }
                ?: getString(R.string.status_shizuku_ok).takeIf { resolved == PrivilegedExec.Mode.SHIZUKU }
                ?: getString(R.string.status_no_privilege))
        }

        dialogView.findViewById<View?>(R.id.opt_exec_auto)?.setOnClickListener { pick("auto") }
        dialogView.findViewById<View?>(R.id.opt_exec_root)?.setOnClickListener { pick("root") }
        dialogView.findViewById<View?>(R.id.opt_exec_shizuku)?.setOnClickListener { pick("shizuku") }
        dialogView.findViewById<View?>(R.id.opt_exec_normal)?.setOnClickListener { pick("normal") }

        // su 预设：点一下直接把模板填进输入框并保存
        val suEt = dialogView.findViewById<android.widget.EditText?>(R.id.et_su_template)
        fun applyPreset(template: String) {
            suEt?.setText(template)
            prefs?.edit()?.putString(PrivilegedExec.PREF_SU_TEMPLATE, template)?.apply()
            // 立刻按新模板重新检测一次，让用户马上知道这个模板通不通
            val ok = PrivilegedExec.detectRoot(this)
            toast(getString(if (ok) R.string.toast_su_preset_ok else R.string.toast_su_preset_fail))
        }
        dialogView.findViewById<View?>(R.id.opt_su_plain)?.setOnClickListener {
            applyPreset("su -c {cmd}")
        }
        dialogView.findViewById<View?>(R.id.opt_su_zero)?.setOnClickListener {
            applyPreset("su -0 -c {cmd}")
        }
        dialogView.findViewById<View?>(R.id.opt_su_mount_master)?.setOnClickListener {
            applyPreset("su --mount-master -c {cmd}")
        }

        // su 模板输入
        dialogView.findViewById<android.widget.EditText?>(R.id.et_su_template)?.let { et ->
            et.setText(PrivilegedExec.suTemplate(this))

            et.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val v = et.text.toString().trim()
                    if (PrivilegedExec.isTemplateValid(v)) {
                        prefs?.edit()?.putString(PrivilegedExec.PREF_SU_TEMPLATE, v)?.apply()
                    } else if (v.isNotEmpty()) {
                        toast(getString(R.string.msg_su_template_invalid))
                        et.setText(PrivilegedExec.suTemplate(this))
                    }
                }
            }
        }
        dialogView.findViewById<Button?>(R.id.btn_su_reset)?.setOnClickListener {
            prefs?.edit()?.putString(PrivilegedExec.PREF_SU_TEMPLATE, PrivilegedExec.DEFAULT_SU_TEMPLATE)?.apply()
            dialogView.findViewById<android.widget.EditText?>(R.id.et_su_template)
                ?.setText(PrivilegedExec.DEFAULT_SU_TEMPLATE)
        }

        syncUi()
    }

    // ⓪ 权限检查：点击立即检测，并把结果直接告诉用户
    dialogView.findViewById<Button?>(R.id.btn_check_permission_in_settings)?.setOnClickListener {
        // ⚠️ 按当前模式给出对应结论，不要一律说 Shizuku。
        //    root 用户看到「Shizuku 未运行」会非常困惑。
        val mode = PrivilegedExec.mode()
        val allFilesOk = android.os.Build.VERSION.SDK_INT < 30 ||
            try { android.os.Environment.isExternalStorageManager() } catch (_: Throwable) { false }

        // 目标目录：优先当前工作副本，否则用当前路径
        val targetDir = worldVM.currentWorkingDbPath?.let { java.io.File(it) }
            ?: java.io.File(worldVM.currentPath.value)

        // 真的往目标目录写一次，而不是只看「是否连接」
        val canWrite = try { worldVM.canShizukuWrite(targetDir) } catch (_: Throwable) { false }

        when (mode) {
            PrivilegedExec.Mode.ROOT -> {
                if (canWrite) {
                    toast(getString(R.string.toast_perm_root_ok))
                } else {
                    toast(getString(R.string.toast_root_unavailable))
                }
            }

            PrivilegedExec.Mode.SHIZUKU -> {
                val shizukuOk = try { worldVM.checkShizukuReady() } catch (_: Throwable) { false }
                when {
                    shizukuOk && !canWrite ->
                        // 连上了但写不了 —— 最常见是 HyperOS 的「USB 调试（安全设置）」没开
                        toast(getString(R.string.toast_shizuku_no_write))
                    shizukuOk && allFilesOk ->
                        toast(getString(R.string.toast_perm_all_granted))
                    shizukuOk ->
                        toast(getString(R.string.toast_perm_shizuku_only))
                    allFilesOk ->
                        toast(getString(R.string.toast_perm_files_only))
                    else -> {
                        toast(getString(R.string.toast_perm_none))
                        askAllFilesPermission(reason = null)
                    }
                }
            }

            else -> {
                // 正常模式：只能靠「所有文件访问」写公共目录
                if (allFilesOk) toast(getString(R.string.toast_perm_files_only))
                else {
                    toast(getString(R.string.toast_perm_none))
                    askAllFilesPermission(reason = null)
                }
            }
        }
    }

    // ⑤ 拼图命名前缀
    dialogView.findViewById<android.widget.EditText?>(R.id.et_puzzle_prefix)?.let { et ->
        et.setText(prefs?.getString("puzzle_prefix", "") ?: "")
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                prefs?.edit()?.putString("puzzle_prefix", s?.toString() ?: "")?.apply()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    // ⑥ 潜影盒颜色（17 色，与游戏一致）
    dialogView.findViewById<Spinner?>(R.id.sp_shulker_color)?.let { sp ->
        val ids = SHULKER_COLORS
        val labels = listOf(
            getString(R.string.color_pink), getString(R.string.color_white),
            getString(R.string.color_orange), getString(R.string.color_magenta),
            getString(R.string.color_light_blue), getString(R.string.color_yellow),
            getString(R.string.color_lime), getString(R.string.color_gray),
            getString(R.string.color_light_gray), getString(R.string.color_cyan),
            getString(R.string.color_purple), getString(R.string.color_blue),
            getString(R.string.color_brown), getString(R.string.color_green),
            getString(R.string.color_red), getString(R.string.color_black),
            getString(R.string.color_undyed)
        )
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val saved = prefs?.getString("shulker_color", "pink_shulker_box") ?: "pink_shulker_box"
        val idx = ids.indexOfFirst { it == saved }
        sp.setSelection(if (idx >= 0) idx else 0, false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs?.edit()?.putString("shulker_color", ids[pos])?.apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ④ 每次生成前是否询问是否调整裁剪
    dialogView.findViewById<CheckBox?>(R.id.cb_puzzle_ask_crop)?.let { cb ->
        cb.isChecked = prefs?.getBoolean("puzzle_ask_crop", true) ?: true
        cb.setOnCheckedChangeListener { _, c ->
            prefs?.edit()?.putBoolean("puzzle_ask_crop", c)?.apply()
        }
    }

    // ---- 保存前自动备份（放在草稿箱上面）----
    setupBackupSettings(dialogView)

    // ---- 草稿箱设置 ----
    setupDraftSettings(dialogView)

    // ---- 回收站自动清理 ----
    setupTrashSettings(dialogView)

    // 「跳过 Shizuku」复选框已移除，改由上面的「运行模式」统一控制。
    // 旧配置（skip_shizuku = true）在 MainActivity 启动时已迁移为 exec_mode = normal。

    dialogView.findViewById<Button?>(R.id.btn_migrate_data)?.setOnClickListener {
        dialog.dismiss()
        startDataMigration(manual = true)
    }
    // ---- 调试页（2026-11-02 新增）----
    // 原本这些入口藏在「点应用图标 10 次」，用户找不到。
    // 现在独立成标签页，图标彩蛋保留（两种方式都能进）。
    // ---- 显示日志（2026-11-08）----
    // 交互定稿：点一次「显示日志」直接开日志弹窗，**不插任何中间弹窗**。
    // 切换都放在弹窗标题栏右侧的下拉里：
    //   「日志 · 全部 ▾」  ← 点开切级别
    //   「今天 ▾」         ← 点开切日期
    // 选完就地刷新正文，弹窗不关。
    val prefLevel = prefs?.getString("debug_log_level", "ALL") ?: "ALL"

    fun levelOf(sel: String): AppLogger.Level? = when (sel) {
        "WARN" -> AppLogger.Level.WARN
        "ERROR" -> AppLogger.Level.ERROR
        else -> null
    }

    fun levelLabel(sel: String): String = when (sel) {
        "WARN" -> getString(R.string.debug_level_warn)
        "ERROR" -> getString(R.string.debug_level_error)
        else -> getString(R.string.debug_level_all)
    }

    /** 日期显示：今天 / 昨天 / 2026-11-05 */
    fun dayLabel(day: String): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val today = fmt.format(java.util.Date())
        val yesterday = fmt.format(java.util.Date(System.currentTimeMillis() - 24L * 3600_000L))
        return when (day) {
            today -> getString(R.string.log_day_today)
            yesterday -> getString(R.string.log_day_yesterday)
            else -> day
        }
    }

    val todayStr: String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    /** 日期对应的正文。null = 还没选过日期，走内存（跟随语言与级别实时格式化）。 */
    fun bodyFor(levelSel: String, day: String?): String {
        val level = levelOf(levelSel)
        return if (day == null) {
            AppLogger.getLogs(level)
        } else {
            buildString {
                appendLine("====== ${getString(R.string.log_header)} ======")
                appendLine(day)
                appendLine("==============================")
                append(AppLogger.readDay(day, level))
            }
        }.ifBlank { getString(R.string.label_sub_no_logs) }
    }

    // 这个 lambda 是单独的接收者作用域，读不到 MainActivity 的 this，
    // 先存成局部变量，后面造 View / 弹子菜单都靠它。
    val act = this
    dialogView.findViewById<Button?>(R.id.btn_debug_show_logs)?.setOnClickListener {
        var curLevel = prefLevel
        var curDay: String? = null   // null = 看内存里今天的

        val density = act.resources.displayMetrics.density
        val pad = (4 * density).toInt()

        /**
         * 标题栏上的一个下拉：文字 + ▾，整块可点。
         *
         * 为什么不用 Spinner：Spinner 放进 AlertDialog 的标题区后，
         * 展开列表的定位会飘到屏幕别处，点起来很别扭。
         */
        fun makeChip(initial: String): Pair<LinearLayout, TextView> {
            val ctx: android.content.Context = act
            val label = TextView(ctx).apply {
                text = initial
                textSize = 14f
                setTextColor(ContextCompat.getColor(ctx, R.color.c_link_lang))
                setPadding(0, pad, 0, pad)
            }
            val chip = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(label)
                addView(TextView(ctx).apply {
                    text = " ▾"
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(ctx, R.color.c_link_lang))
                    setPadding(0, pad, 0, pad)
                })
            }
            return chip to label
        }

        val (levelChip, levelText) = makeChip(levelLabel(curLevel))
        val firstDay = AppLogger.logDays().firstOrNull()
        val (dayChip, dayText) = makeChip(if (firstDay == null) todayStr else dayLabel(firstDay))

        val trailing = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.END
            addView(levelChip)
            addView(dayChip)
        }

        showLogDialog(
            title = getString(R.string.title_logs),
            detail = bodyFor(curLevel, curDay),
            cancelText = getString(R.string.btn_close),
            titleTrailing = trailing,
            onContentTextView = { tv ->
                levelChip.setOnClickListener {
                    val all = arrayOf("ALL", "WARN", "ERROR")
                    val labels = all.map { levelLabel(it) }.toTypedArray()
                    AlertDialog.Builder(act)
                        .setItems(labels) { d, which ->
                            curLevel = all[which]
                            prefs?.edit()?.putString("debug_log_level", curLevel)?.apply()
                            levelText.text = levelLabel(curLevel)
                            tv.text = bodyFor(curLevel, curDay)
                            d.dismiss()
                        }
                        .show()
                }
                dayChip.setOnClickListener {
                    val days = AppLogger.logDays()
                    if (days.isNotEmpty()) {
                        val labels = days.map { dayLabel(it) }.toTypedArray()
                        AlertDialog.Builder(act)
                            .setItems(labels) { d, which ->
                                curDay = days[which]
                                dayText.text = dayLabel(curDay!!)
                                tv.text = bodyFor(curLevel, curDay)
                                d.dismiss()
                            }
                            .show()
                    }
                }
            },
        )
    }

    // ---- 日志保留范围（2026-11-08）----
    // 与「回收站自动清理」同一套交互：预设下拉 + 自定义按钮。
    // 0 = 不自动清理（程序完全不删，用户自己决定）。
    val logKeepSpinner = dialogView.findViewById<Spinner?>(R.id.sp_debug_log_keep)
    var keepDays = prefs?.getInt("debug_log_keep_days", AppLogger.KEEP_DAYS_DEFAULT)
        ?: AppLogger.KEEP_DAYS_DEFAULT

    // 描述文字跟随当前设置（0 时明确写「不自动清理」，
    // 免得用户以为还在偷偷删）
    fun refreshDesc() {
        dialogView.findViewById<TextView?>(R.id.tv_debug_logs_desc)?.text =
            if (keepDays <= 0) getString(R.string.debug_logs_desc_forever)
            else getString(R.string.debug_logs_desc_days, keepDays)
    }
    refreshDesc()

    if (logKeepSpinner != null) {
        val presets = intArrayOf(0, 3, 7, 14, 30)

        fun labelFor(d: Int): String = if (d <= 0) {
            getString(R.string.log_clear_days_none)
        } else {
            getString(R.string.log_clear_days_fmt, d)
        }

        val labels = presets.map { labelFor(it) }.toMutableList()
        if (keepDays > 0 && presets.indexOf(keepDays) < 0) labels.add(labelFor(keepDays))
        val adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        logKeepSpinner.adapter = adapter

        // 回显
        run {
            val idx = presets.indexOf(keepDays)
            logKeepSpinner.setSelection(if (idx >= 0) idx else labels.size - 1)
        }

        // 与回收站同样处理时序陷阱：只响应用户真的触摸过的选择
        var userPicked = false
        logKeepSpinner.setOnTouchListener { _, _ -> userPicked = true; false }
        logKeepSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                p: AdapterView<*>?, v: android.view.View?, position: Int, id: Long
            ) {
                if (!userPicked) return
                keepDays = presets.getOrNull(position) ?: keepDays
                prefs?.edit()?.putInt("debug_log_keep_days", keepDays)?.apply()
                refreshDesc()
                // 切换后立即按新范围清一次，让「保留几天」立刻生效
                val n = AppLogger.deleteOlderThan(keepDays)
                if (n > 0) toast(getString(R.string.debug_logs_cleared, n))
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // 自定义入口
        dialogView.findViewById<Button?>(R.id.btn_custom_debug_log_keep)?.setOnClickListener {
            showCustomNumberDialog(
                getString(R.string.title_log_clear_custom), 0, CUSTOM_VALUE_MAX,
                getString(R.string.msg_log_clear_custom_invalid),
                if (keepDays > 0) keepDays else 14
            ) { n ->
                keepDays = n
                prefs?.edit()?.putInt("debug_log_keep_days", n)?.apply()
                val lbl = labelFor(n)
                if (labels.none { it == lbl }) labels.add(lbl)
                adapter.notifyDataSetChanged()
                logKeepSpinner.setSelection(labels.size - 1, false)
                refreshDesc()
            }
        }
    }

    dialogView.findViewById<Button?>(R.id.btn_debug_export_logs)?.setOnClickListener {
        exportLogsToFile()
    }
    dialogView.findViewById<Button?>(R.id.btn_debug_copy_logs)?.setOnClickListener {
        copyToClipboard(getString(R.string.title_logs), AppLogger.getLogs(levelOf(prefLevel)))
    }
    dialogView.findViewById<Button?>(R.id.btn_debug_clear_logs)?.setOnClickListener {
        // 手动清理是破坏性操作，说清楚范围再确认一次
        val total = AppLogger.logDays().size
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.debug_clear_logs))
            .setMessage(
                if (total <= 0) getString(R.string.msg_log_clear_noop)
                else getString(R.string.msg_log_clear_none_confirm)
            )
            .setPositiveButton(getString(R.string.log_clear_days_all)) { _, _ ->
                val n = AppLogger.deleteOlderThan(0)
                toast(
                    if (n > 0) getString(R.string.debug_logs_cleared, n)
                    else getString(R.string.msg_log_clear_noop)
                )
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // 测试区折叠：默认收起，点标题行展开
    val debugTestHeader = dialogView.findViewById<LinearLayout?>(R.id.layout_debug_test_header)
    val debugTestContent = dialogView.findViewById<LinearLayout?>(R.id.layout_debug_test_content)
    val debugTestExpand = dialogView.findViewById<TextView?>(R.id.tv_debug_test_expand)
    debugTestHeader?.setOnClickListener {
        debugTestContent?.let { c ->
            c.isVisible = !c.isVisible
            debugTestExpand?.text = if (c.isVisible) "▲" else "▼"
            remeasure()
        }
    }

    dialogView.findViewById<Button?>(R.id.btn_debug_crash)?.setOnClickListener {
        // 崩溃测试是真的把进程弄崩，必须二次确认。
        // 文案要明确说清楚「点了会发生什么」，不能只写「确定吗」。
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.debug_crash_confirm_title))
            .setMessage(getString(R.string.debug_crash_confirm_msg))
            .setPositiveButton(getString(R.string.debug_crash_confirm_ok)) { _, _ ->
                AppLogger.warn("Debug", getString(R.string.debug_crash_log_marker))
                Handler(Looper.getMainLooper()).postDelayed(
                    { throw RuntimeException("Manual crash test") }, 1000
                )
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
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
            val target = if ("zh" == currentLang) "en" else "zh"
            val skip = prefs?.getBoolean(PREF_SKIP_LANG_PROMPT, false) == true
            if (skip) {
                applyLanguageAndRestart(target, dialog)
            } else {
                showLanguageSwitchDialog(target, dialog)
            }
        }
    }

    // 应用图标的彩蛋（2026-11-08 改）
    // 调试入口已经有独立的「调试」标签页，这里不再弹调试菜单，
    // 只回一句问候。连点窗口 5 秒，点满 5 次触发。
    var clicks = 0; var lastClick = 0L
    dialogView.findViewById<View?>(R.id.in_app_icon)?.setOnClickListener {
        val now = System.currentTimeMillis()
        if (now - lastClick > 5000) clicks = 0
        lastClick = now; clicks++
        if (clicks >= 5) {
            clicks = 0
            toast(getString(R.string.toast_debug_egg))
        }
    }
}

// ============================================
// 辅助：编辑后刷新
// ============================================
private fun MainActivity.refreshAfterEdit() {
    AppLogger.info("ListDebug", "refreshAfterEdit 调用: isTreeMode=${editorVM.isTreeMode.value}")
    editorVM.syncListFakeMapToSource()
    // 标记草稿待保存：由 DraftAutoSaver 按用户设定的间隔写盘
    DraftAutoSaver.markDirty()
    if (editorVM.isTreeMode.value) {
        // 树形模式：结构可能变化（新增/删除/重命名），必须重建节点树，
        // 仅 notifyDataSetChanged() 不会产生新节点，导致改动看不见。
        nbtTreeAdapter?.rebuild()
    } else {
        nbtAdapter?.refreshKeys()
    }
}

// ============================================
// 草稿箱设置（自动保存间隔 / 单位 / 数量上限 / 保留原始数据）
// ============================================

/** 单位换算：0=秒 1=分 2=时 */
private fun unitFactor(unit: Int): Long = when (unit) {
    1 -> 60_000L
    2 -> 3_600_000L
    else -> 1_000L
}

private fun MainActivity.setupDraftSettings(root: android.view.View) {
    val intervalSp = root.findViewById<Spinner?>(R.id.sp_draft_interval)
    val unitSp = root.findViewById<Spinner?>(R.id.sp_draft_interval_unit)
    val maxSp = root.findViewById<Spinner?>(R.id.sp_draft_max)
    val cbAuto = root.findViewById<CheckBox?>(R.id.cb_draft_autosave) ?: return
    val cbKeep = root.findViewById<CheckBox?>(R.id.cb_draft_keep_original)

    // 当前值（从 prefs 读，缺省：30 秒 / 上限 10）
    var interval = prefs?.getInt("draft_interval", 30) ?: 30
    var unit = prefs?.getInt("draft_interval_unit", 0) ?: 0      // 0=秒
    var maxDrafts = prefs?.getInt("draft_max", DraftStore.DEFAULT_MAX_DRAFTS)
        ?: DraftStore.DEFAULT_MAX_DRAFTS
    // 程序回显复选框时抑制回调（否则 setChecked 会反过来改 interval）
    var suppressCheck = false

    /** 把当前设置应用到自动保存器 */
    fun applyAll() {
        val ms = if (interval <= 0) 0L else interval.toLong() * unitFactor(unit)
        DraftAutoSaver.setInterval(ms)
        DraftAutoSaver.maxDrafts = maxDrafts
        DraftStore.pruneTo(this, maxDrafts)
        prefs?.edit()?.putInt("draft_interval", interval)?.apply()
        prefs?.edit()?.putInt("draft_interval_unit", unit)?.apply()
        prefs?.edit()?.putInt("draft_max", maxDrafts)?.apply()
        // 单位选择在「关闭」时无意义，置灰
        unitSp?.isEnabled = interval > 0
        unitSp?.alpha = if (interval > 0) 1f else 0.4f
        // 顶部的复选框跟着「间隔是否 > 0」走
        suppressCheck = true
        cbAuto.isChecked = interval > 0
        suppressCheck = false
    }

    // ① 自动保存开关：关 = interval 0
    cbAuto.isChecked = interval > 0
    cbAuto.setOnCheckedChangeListener { _, checked ->
        if (suppressCheck) return@setOnCheckedChangeListener
        if (!checked) {
            interval = 0
            intervalSp?.setSelection(0)   // 「关闭」
        } else {
            val lastCustom = prefs?.getInt("draft_interval_last", 30) ?: 30
            interval = if (lastCustom > 0) lastCustom else 30
            val idx = DRAFT_INTERVAL_VALUES.indexOf(interval)
            intervalSp?.setSelection(if (idx >= 0) idx else DRAFT_INTERVAL_VALUES.size - 1)
        }
        applyAll()
    }

    // ② 间隔：关闭 / 2 / 4 / 8 / 自定义
    // 自定义格改成短图标 + 数值（`✎ 30`），避免被截断成看不到数字
    bindPresetSpinner(
        sp = intervalSp,
        presetLabels = listOf(getString(R.string.draft_interval_off), "2", "4", "8"),
        presetValues = listOf(0, 2, 4, 8),
        titleRes = R.string.title_custom_interval,
        invalidMsgRes = R.string.msg_draft_interval_invalid,
        currentValue = { interval },
        applyValue = { n ->
            interval = n
            if (n > 0) prefs?.edit()?.putInt("draft_interval_last", n)?.apply()
            applyAll()
        },
        customBtn = root.findViewById(R.id.btn_custom_draft_interval)
    )

    // ③ 单位
    unitSp?.adapter = ArrayAdapter(
        this, android.R.layout.simple_spinner_item,
        mutableListOf(
            getString(R.string.unit_seconds),
            getString(R.string.unit_minutes),
            getString(R.string.unit_hours)
        )
    ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    unitSp?.setSelection(unit.coerceIn(0, 2))
    var userPickedUnit = false
    unitSp?.setOnTouchListener { _, _ -> userPickedUnit = true; false }
    unitSp?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, position: Int, id: Long) {
            if (!userPickedUnit) return
            unit = position
            applyAll()
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    // ④ 数量上限：5 / 10 / 20 / 自定义（同样用短图标 + 数值）
    bindPresetSpinner(
        sp = maxSp,
        presetLabels = listOf("5", "10", "20"),
        presetValues = listOf(5, 10, 20),
        titleRes = R.string.title_custom_draft_max,
        invalidMsgRes = R.string.msg_draft_interval_invalid,
        currentValue = { maxDrafts },
        applyValue = { n -> maxDrafts = n; applyAll() },
        customBtn = root.findViewById(R.id.btn_custom_draft_max)
    )

    cbKeep?.isChecked = prefs?.getBoolean("draft_keep_original", false) ?: false
    cbKeep?.setOnCheckedChangeListener { _, checked ->
        DraftAutoSaver.keepOriginal = checked
        prefs?.edit()?.putBoolean("draft_keep_original", checked)?.apply()
    }

    applyAll()
}

/** 「关闭 / 2 / 4 / 8 / 自定义」对应的秒数；-1 表示自定义 */
private val DRAFT_INTERVAL_VALUES = listOf(0, 2, 4, 8, -1)

// ============================================
// 「保存前自动备份」设置（结构完全对齐草稿箱那组）
// ============================================

/**
 * 开关 + 间隔（关闭/2/4/8/自定义 + 秒·分·时） + 上限（5/10/20/自定义）。
 *
 * ⚠️ 与草稿箱那组一样有两个时序/适配器陷阱，这里照同样的方式规避：
 * 1. `Spinner.setSelection()` 的回调由**布局阶段**派发，时机不可控 → 只响应**用户真的触摸过**的操作
 * 2. `ArrayAdapter` 必须拿 **MutableList**（`remove`/`insert` 会改动底层集合）
 */
/**
 * 绑定「预设值 + 自定义…」下拉框。**统一解决两个老毛病**：
 *
 * 1. **自定义值显示不全**：以前自定义格用「自定义…」当前缀，加上数字后太长，
 *    在窄 Spinner 里被截断成「自定义…」，用户看不到自己设了几 → 改用**短图标** `✎ N`
 *    （草稿箱的间隔早就这么干了，其它几处没跟上）。
 * 2. **再次点击「自定义」没反应**：若 Spinner 的选中索引没变，`onItemSelected` **不会回调**，
 *    于是用户第二次点同一格时什么都不发生。→ 触摸后补一次检查兜底，并用时间戳防重入。
 */
internal fun MainActivity.bindPresetSpinner(
    sp: Spinner?,
    presetLabels: List<String>,
    presetValues: List<Int>,
    titleRes: Int,
    invalidMsgRes: Int,
    currentValue: () -> Int,
    applyValue: (Int) -> Unit,
    /** 「自定义」按钮：移出下拉，放在 Spinner 旁边独立成一个按钮 */
    customBtn: android.widget.Button? = null
) {
    if (sp == null) return
    val pen = getString(R.string.draft_interval_custom)

    /** 当前值是否不在预设里（即用户设过自定义值） */
    fun hasCustomValue(): Boolean {
        val v = currentValue()
        return v > 0 && presetValues.indexOf(v) < 0
    }

    /**
     * 下拉里显示的文案。
     *
     * 「自定义」入口已**移出下拉**，改为旁边独立的按钮 —— 这样无论当前
     * 停在哪个预设上，都能随时点按钮改数值，不再受
     * 「Spinner 选中位置未变就不回调 onItemSelected」这个 Android 限制。
     *
     * 若当前值是自定义值（不在预设里），额外追加一行 `✎ 5` 用于展示。
     */
    fun displayLabels(): MutableList<String> {
        val list = presetLabels.toMutableList()
        if (hasCustomValue()) list.add("$pen ${currentValue()}")
        return list
    }

    var labels = displayLabels()

    val adapter = object : ArrayAdapter<String>(
        this, android.R.layout.simple_spinner_item, labels
    ) {
        override fun getDropDownView(
            position: Int, convertView: View?, parent: android.view.ViewGroup
        ): View {
            val tv = super.getDropDownView(position, convertView, parent) as TextView
            tv.text = labels[position]
            // ⚠️ 列表行不能挂 OnClickListener：会抢掉 AdapterView.onItemClick，
            //    导致其它行全部点不动（踩过这个坑）。
            tv.setOnClickListener(null)
            tv.isClickable = false
            return tv
        }
    }
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    sp.adapter = adapter

    /** 值变化后刷新文案与选中项 */
    fun syncSelection() {
        labels = displayLabels()
        adapter.clear()
        adapter.addAll(labels)
        adapter.notifyDataSetChanged()
        val hit = presetValues.indexOf(currentValue())
        sp.setSelection(if (hit >= 0) hit else labels.size - 1, false)
    }

    // 初始回显
    run {
        val hit = presetValues.indexOf(currentValue())
        sp.setSelection(if (hit >= 0) hit else labels.size - 1)
    }

    var userPicked = false
    sp.setOnTouchListener { _, _ -> userPicked = true; false }
    sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
            if (!userPicked) return              // 程序回显不处理
            // 末位可能是「✎ 自定义值」，只有真正的预设才写回
            presetValues.getOrNull(position)?.let { applyValue(it) }
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    // ---- 「自定义」独立按钮：随时可点 ----
    customBtn?.setOnClickListener {
        showCustomNumberDialog(
            getString(titleRes), 1, CUSTOM_VALUE_MAX, getString(invalidMsgRes),
            currentValue().takeIf { it > 0 } ?: 1
        ) { n ->
            applyValue(n)
            syncSelection()
        }
    }
}

/**
 * 百分比版的下拉框：预设几档 + 「自定义」。
 *
 * 与 [bindPresetSpinner] 同一套交互（✎ N（点此修改）），
 * 但值是百分比（Float），内部用**整数万分比**传参给实现，
 * 这样复用同一套 Spinner 逻辑，避免浮点在 indexOf 上比较失败。
 *
 * @param presetPercent 预设档位（百分数，如 8 / 15 / 25 / 40）
 * @param currentPercent 当前值（百分数）
 * @param onApply 用户选定后回调（百分数）
 */
private fun MainActivity.bindPercentSpinner(
    sp: Spinner?,
    presetPercent: List<Int>,
    titleRes: Int,
    invalidMsgRes: Int,
    currentPercent: Float,
    onApply: (Int) -> Unit,
    /** 「自定义」按钮：移出下拉，放在 Spinner 旁边 */
    customBtn: android.widget.Button? = null
) {
    if (sp == null) return
    val pen = getString(R.string.draft_interval_custom)

    var value = currentPercent.toInt()

    fun hasCustomValue(): Boolean = value > 0 && presetPercent.indexOf(value) < 0

    /** 下拉文案：预设档位 + （若当前是自定义值）追加一行 `✎ 37%` 用于展示 */
    fun displayLabels(): MutableList<String> {
        val list = presetPercent.map { "$it%" }.toMutableList()
        if (hasCustomValue()) list.add("$pen $value%")
        return list
    }

    var labels = displayLabels()

    val adapter = object : ArrayAdapter<String>(
        this, android.R.layout.simple_spinner_item, labels
    ) {
        override fun getDropDownView(
            position: Int, convertView: View?, parent: android.view.ViewGroup
        ): View {
            val tv = super.getDropDownView(position, convertView, parent) as TextView
            tv.text = labels[position]
            // 同 bindPresetSpinner：列表行不能挂监听，否则会抢掉其它行的点击
            tv.setOnClickListener(null)
            tv.isClickable = false
            return tv
        }
    }
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    sp.adapter = adapter

    fun syncSelection() {
        labels = displayLabels()
        adapter.clear()
        adapter.addAll(labels)
        adapter.notifyDataSetChanged()
        val hit = presetPercent.indexOf(value)
        sp.setSelection(if (hit >= 0) hit else labels.size - 1, false)
    }

    run {
        val hit = presetPercent.indexOf(value)
        sp.setSelection(if (hit >= 0) hit else labels.size - 1)
    }

    var userPicked = false
    sp.setOnTouchListener { _, _ -> userPicked = true; false }
    sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
            if (!userPicked) return          // 程序回显不处理
            val preset = presetPercent.getOrNull(position) ?: return
            value = preset
            onApply(preset)
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    // ---- 「自定义」独立按钮 ----
    customBtn?.setOnClickListener {
        showCustomNumberDialog(
            getString(titleRes), 1, 100, getString(invalidMsgRes),
            value.takeIf { it > 0 } ?: 25
        ) { n ->
            value = n
            onApply(n)
            syncSelection()
        }
    }
}

/** 潜影盒颜色 ID（顺序与设置里的下拉一致） */
private val SHULKER_COLORS = listOf(
    "pink_shulker_box", "white_shulker_box", "orange_shulker_box", "magenta_shulker_box",
    "light_blue_shulker_box", "yellow_shulker_box", "lime_shulker_box", "gray_shulker_box",
    "light_gray_shulker_box", "cyan_shulker_box", "purple_shulker_box", "blue_shulker_box",
    "brown_shulker_box", "green_shulker_box", "red_shulker_box", "black_shulker_box",
    "undyed_shulker_box"
)

private fun MainActivity.setupBackupSettings(root: android.view.View) {
    val cbAuto = root.findViewById<CheckBox?>(R.id.cb_backup_auto) ?: return
    val intervalSp = root.findViewById<Spinner?>(R.id.sp_backup_interval)
    val unitSp = root.findViewById<Spinner?>(R.id.sp_backup_interval_unit)
    val maxSp = root.findViewById<Spinner?>(R.id.sp_backup_max)
    val layoutInterval = root.findViewById<android.view.View?>(R.id.layout_backup_interval)
    val layoutMax = root.findViewById<android.view.View?>(R.id.layout_backup_max)

    var auto = prefs?.getBoolean(BackupSettings.PREF_AUTO, true) ?: true
    var interval = prefs?.getInt(BackupSettings.PREF_INTERVAL, BackupSettings.DEFAULT_INTERVAL)
        ?: BackupSettings.DEFAULT_INTERVAL
    var unit = prefs?.getInt(BackupSettings.PREF_INTERVAL_UNIT, BackupSettings.DEFAULT_UNIT)
        ?: BackupSettings.DEFAULT_UNIT
    var maxKeep = prefs?.getInt(BackupSettings.PREF_MAX, BackupSettings.DEFAULT_MAX)
        ?: BackupSettings.DEFAULT_MAX

    fun writePrefs() {
        prefs?.edit()
            ?.putBoolean(BackupSettings.PREF_AUTO, auto)
            ?.putInt(BackupSettings.PREF_INTERVAL, interval)
            ?.putInt(BackupSettings.PREF_INTERVAL_UNIT, unit)
            ?.putInt(BackupSettings.PREF_MAX, maxKeep)
            ?.apply()
        // 关掉总开关时整组置灰（和草稿箱「关闭时单位置灰」一个思路）
        layoutInterval?.alpha = if (auto) 1f else 0.4f
        layoutMax?.alpha = if (auto) 1f else 0.4f
        intervalSp?.isEnabled = auto
        unitSp?.isEnabled = auto && interval > 0
        maxSp?.isEnabled = auto
    }

    cbAuto.isChecked = auto
    cbAuto.setOnCheckedChangeListener { _, checked ->
        auto = checked
        writePrefs()
    }

    // ---- 间隔：关闭 / 2 / 4 / 8 / 自定义（+ 秒·分·时）----
    bindPresetSpinner(
        sp = intervalSp,
        presetLabels = listOf(getString(R.string.backup_interval_off), "2", "4", "8"),
        presetValues = listOf(0, 2, 4, 8),
        titleRes = R.string.title_custom_interval,
        invalidMsgRes = R.string.msg_draft_interval_invalid,
        currentValue = { interval },
        applyValue = { n ->
            interval = n
            if (n > 0) prefs?.edit()?.putInt(BackupSettings.PREF_INTERVAL_LAST, n)?.apply()
            writePrefs()
        },
        customBtn = root.findViewById(R.id.btn_custom_backup_interval)
    )

    unitSp?.adapter = ArrayAdapter(
        this, android.R.layout.simple_spinner_item,
        mutableListOf(
            getString(R.string.unit_seconds),
            getString(R.string.unit_minutes),
            getString(R.string.unit_hours)
        )
    ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    unitSp?.setSelection(unit.coerceIn(0, 2))
    var userPickedUnit = false
    unitSp?.setOnTouchListener { _, _ -> userPickedUnit = true; false }
    unitSp?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, position: Int, id: Long) {
            if (!userPickedUnit) return
            unit = position
            writePrefs()
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    // ---- 上限：5 / 10 / 20 / 自定义 ----
    bindPresetSpinner(
        sp = maxSp,
        presetLabels = listOf("5", "10", "20"),
        presetValues = listOf(5, 10, 20),
        titleRes = R.string.title_custom_backup_max,
        invalidMsgRes = R.string.msg_draft_interval_invalid,
        currentValue = { maxKeep },
        applyValue = { n ->
            maxKeep = n
            writePrefs()
        },
        customBtn = root.findViewById(R.id.btn_custom_backup_max)
    )

    writePrefs()
}

private fun MainActivity.showCustomNumberDialog(
    title: String, min: Int, max: Int, invalidMsg: String,
    current: Int, onOk: (Int) -> Unit
) {
    val input = EditText(this).apply {
        // TYPE_CLASS_NUMBER 让键盘只有数字；再加 InputFilter 挡住粘贴进来的
        // 小数点 / 科学记数法 / 负号（用户明确要求只能填整数）
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(digitsOnlyFilter())
        hint = getString(R.string.hint_number_range, min, max)
        setText(if (current in min..max) current.toString() else "")
    }
    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(input)
        .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
            // toIntOrNull 顺带挡住 "1.5" / "1e3" 这类非法输入
            val n = input.text.toString().trim().toIntOrNull()
            if (n == null || n < min || n > max) toast(invalidMsg) else onOk(n)
        }
        .setNegativeButton(getString(R.string.btn_cancel), null)
        .show()
}

// ============================================
// 回收站设置（自动清理天数）
// ============================================

/** 自定义数字框允许的最大值（用户要求：最大不超过 1 万） */
internal const val CUSTOM_VALUE_MAX = 10_000

/** 只允许纯数字（挡掉小数点、科学记数法、负号；粘贴同样被过滤） */
private fun digitsOnlyFilter(): android.text.InputFilter =
    android.text.InputFilter { source, _, _, _, _, _ ->
        if (source.isEmpty()) return@InputFilter source
        if (source.all { it in '0'..'9' }) source else ""
    }

/** 「不自动清 / 7 / 30 / 90 / 自定义」对应的天数；-1 表示自定义 */
private val TRASH_KEEP_VALUES = listOf(0, 7, 30, 90, -1)

private fun MainActivity.setupTrashSettings(root: android.view.View) {
    val sp = root.findViewById<Spinner?>(R.id.sp_trash_keep) ?: return
    var days = prefs?.getInt("trash_keep_days", TrashStore.DEFAULT_KEEP_DAYS)
        ?: TrashStore.DEFAULT_KEEP_DAYS

    fun labelFor(d: Int): String = when (d) {
        0 -> getString(R.string.trash_keep_never)
        else -> getString(R.string.trash_keep_days, d)
    }

    // 「自定义」格：一旦有自定义值，直接显示成「N 天」
    // （之前显示「自定义… 3650」，用户看不出到底保留多少天）
    fun customLabel(): String =
        if (days > 0 && TRASH_KEEP_VALUES.indexOf(days) < 0) labelFor(days)
        else getString(R.string.thread_count_custom)

    // 「自定义」入口已移到旁边的独立按钮，下拉里只放预设
    // （若当前是自定义值，额外追加一项用于展示）
    val labels = mutableListOf(
        labelFor(0), labelFor(7), labelFor(30), labelFor(90)
    )
    if (days > 0 && TRASH_KEEP_VALUES.indexOf(days) < 0) labels.add(labelFor(days))
    val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    sp.adapter = adapter

    fun apply() {
        prefs?.edit()?.putInt("trash_keep_days", days)?.apply()
    }

    // 回显
    run {
        val idx = TRASH_KEEP_VALUES.indexOf(days)
        sp.setSelection(if (idx >= 0) idx else labels.size - 1)
    }
    apply()

    // ⚠️ 时序陷阱：setSelection 的回调由**布局阶段**派发，时机不可控
    // （post{} / 延迟标志都不可靠）。改为只响应**用户真的触摸过**的操作，
    // 程序回显不产生触摸事件，因此天然被忽略。
    var userPicked = false
    sp.setOnTouchListener { _, _ -> userPicked = true; false }
    sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, position: Int, id: Long) {
            if (!userPicked) return
            val valAt = TRASH_KEEP_VALUES.getOrNull(position) ?: return
            days = valAt
            apply()
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    // ---- 「自定义」独立按钮：随时可点，不受下拉选中位置限制 ----
    root.findViewById<android.widget.Button?>(R.id.btn_custom_trash_keep)?.setOnClickListener {
        showCustomNumberDialog(
            getString(R.string.title_custom_trash_keep), 1, CUSTOM_VALUE_MAX,
            getString(R.string.msg_trash_keep_invalid),
            if (days > 0) days else 30
        ) { n ->
            days = n
            apply()
            // 自定义值不属于任何预设 -> 追加到末尾并选中
            val lbl = labelFor(n)
            if (labels.none { it == lbl }) labels.add(lbl)
            adapter.notifyDataSetChanged()
            sp.setSelection(labels.size - 1, false)
        }
    }
}

// ============================================
// 并发线程数选择器（自动 / 2 / 4 / 8 / 自定义…）
// ============================================

/**
 * 绑定一个线程数 Spinner。
 * @param onChanged 最终值回调：0 = 自动，>0 = 指定线程数
 * @param enabled   父开关是否开启（关闭时置灰禁用）
 */
private fun MainActivity.setupThreadCountSpinner(
    root: View,
    spinnerId: Int,
    containerId: Int,
    prefKey: String,
    enabled: Boolean,
    onChanged: (Int) -> Unit,
    customBtnId: Int = 0
) {
    val spinner = root.findViewById<Spinner?>(spinnerId) ?: return
    val container = root.findViewById<LinearLayout?>(containerId)

    var saved = prefs?.getInt(prefKey, 0) ?: 0

    // 选项：自动 / 2 / 4 / 8 / 自定义（与草稿·备份走同一套 bindPresetSpinner，
    // 共用「✎ N（点此修改）」文案与「自定义行可点击」的行为）
    bindPresetSpinner(
        sp = spinner,
        presetLabels = listOf(getString(R.string.thread_count_auto), "2", "4", "8"),
        presetValues = listOf(0, 2, 4, 8),
        titleRes = R.string.title_custom_thread_count,
        invalidMsgRes = R.string.msg_thread_count_invalid,
        currentValue = { saved },
        applyValue = { n ->
            saved = n
            prefs?.edit()?.putInt(prefKey, n)?.apply()
            onChanged(n)
        },
        customBtn = if (customBtnId != 0) root.findViewById(customBtnId) else null
    )

    spinner.isEnabled = enabled
    container?.alpha = if (enabled) 1f else 0.4f
}

