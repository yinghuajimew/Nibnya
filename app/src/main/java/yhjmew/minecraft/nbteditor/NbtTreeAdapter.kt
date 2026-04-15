package yhjmew.minecraft.nbteditor

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import yhjmew.minecraft.nbteditor.NbtTranslator.getEmojiIcon
import yhjmew.minecraft.nbteditor.NbtTranslator.getEnchantTranslation
import yhjmew.minecraft.nbteditor.NbtTranslator.getItemTranslation
import yhjmew.minecraft.nbteditor.NbtTranslator.getPotionTranslation
import yhjmew.minecraft.nbteditor.NbtTranslator.getTranslation
import yhjmew.minecraft.nbteditor.NbtTranslator.parseValue
import java.util.Collections
import java.util.Stack

class NbtTreeAdapter(private val context: Context, rootData: JsonObject?) : BaseAdapter() {
    private var currentMode: Int = MODE_RAW

    // 内部节点类
    class Node(
        var key: String, var value: JsonElement?, // 缩进层级
        var level: Int, var parent: Node?
    ) {
        var type: Int = 0
        var isExpanded: Boolean = false

        init {
            if (value != null && value!!.isJsonObject() && value!!.getAsJsonObject().has("t")) {
                type = value!!.getAsJsonObject().get("t").getAsInt()
            } else {
                type = 0
            }
        }
    }

    private val rootNode: Node
    private val visibleNodes: MutableList<Node>

    init {
        this.rootNode = Node("ROOT", null, -1, null)
        this.rootNode.isExpanded = true

        this.visibleNodes = ArrayList<Node>()
        if (rootData != null) {
            buildChildren(rootNode, rootData)
        }
    }

    private fun buildChildren(parent: Node, container: JsonObject) {
        val keys: MutableList<String> = ArrayList<String>(container.keySet())
        Collections.sort<String?>(keys)

        val children: MutableList<Node?> = ArrayList<Node?>()
        for (k in keys) {
            val v = container.get(k)
            val child = Node(k, v, parent.level + 1, parent)
            children.add(child)
        }
        visibleNodes.addAll(children)
    }

    // 展开/折叠核心
    fun toggleExpand(position: Int) {
        val node = visibleNodes.get(position)
        if (node.type != 9 && node.type != 10) return

        if (node.isExpanded) {
            // 折叠
            node.isExpanded = false
            val i = position + 1
            while (i < visibleNodes.size) {
                val next = visibleNodes.get(i)
                if (next.level > node.level) visibleNodes.removeAt(i)
                else break
            }
        } else {
            // 展开
            node.isExpanded = true
            val subNodes: MutableList<Node?> = ArrayList<Node?>()
            val innerV = node.value!!.getAsJsonObject().get("v")

            if (node.type == 10) { // Compound
                val jo = innerV.getAsJsonObject()
                val keys: MutableList<String> = ArrayList<String>(jo.keySet())
                Collections.sort<String?>(keys)
                for (k in keys) subNodes.add(Node(k, jo.get(k), node.level + 1, node))
            } else if (node.type == 9) { // List
                val ja = innerV.getAsJsonArray()
                val itemType = node.value!!.getAsJsonObject().get("itemType").getAsInt()
                for (i in 0..<ja.size()) {
                    val wrapper = JsonObject()
                    wrapper.addProperty("t", itemType)
                    wrapper.add("v", ja.get(i))
                    subNodes.add(Node(i.toString(), wrapper, node.level + 1, node))
                }
            }
            visibleNodes.addAll(position + 1, subNodes)
        }
        notifyDataSetChanged()
    }

    fun isContainer(position: Int): Boolean {
        val t = visibleNodes.get(position).type
        return t == 9 || t == 10
    }

    fun getNode(position: Int): Node? {
        return visibleNodes.get(position)
    }

    fun setViewMode(mode: Int) {
        this.currentMode = mode
        notifyDataSetChanged()
    }

    override fun getCount(): Int {
        return visibleNodes.size
    }

    override fun getItem(position: Int): Any? {
        return visibleNodes.get(position)
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        var convertView = convertView
        if (convertView == null) {
            convertView =
                LayoutInflater.from(context).inflate(R.layout.item_nbt_tree, parent, false)
        }

        val node = visibleNodes.get(position)

        val vIndent = convertView.findViewById<View>(R.id.view_indentation)
        val tvExpand = convertView.findViewById<TextView>(R.id.tv_expand_icon)
        val tvIcon = convertView.findViewById<TextView>(R.id.tv_type_icon)
        val tvKey = convertView.findViewById<TextView>(R.id.tv_key)
        val tvValue = convertView.findViewById<TextView>(R.id.tv_value)
        val tvDesc = convertView.findViewById<TextView>(R.id.tv_key_desc)

        // 设置缩进
        val lp = vIndent.getLayoutParams()
        lp.width = (node.level * 24 * context.getResources().getDisplayMetrics().density).toInt()
        vIndent.setLayoutParams(lp)

        // 展开箭头
        if (node.type == 9 || node.type == 10) {
            tvExpand.setVisibility(View.VISIBLE)
            tvExpand.setText(if (node.isExpanded) "▼" else "▶")
        } else {
            tvExpand.setVisibility(View.INVISIBLE)
        }

        // Emoji 图标
        val emoji = getEmojiIcon(node.key)
        if (emoji != null) {
            tvIcon.setText(emoji)
            tvIcon.setBackgroundColor(Color.TRANSPARENT)
            tvIcon.setTextSize(18f)
        } else {
            setupNormalIcon(tvIcon, node.type)
            tvIcon.setTextSize(14f)
        }

        // 1. 准备原始数据
        val key = node.key
        var valRaw = ""
        val vInner =
            if (node.value != null && node.value!!.isJsonObject()) node.value!!.getAsJsonObject()
                .get("v") else null
        val isLargeArray = (node.type == 7 || node.type == 11 || node.type == 12)

        if (vInner != null) {
            if (node.type == 9) valRaw =
                "[List: " + (if (vInner.isJsonArray()) vInner.getAsJsonArray()
                    .size() else 0) + " items]"
            else if (node.type == 10) valRaw =
                "{Compound: " + (if (vInner.isJsonObject()) vInner.getAsJsonObject()
                    .size() else 0) + " entries}"
            else if (isLargeArray) valRaw =
                "[Array: " + (if (vInner.isJsonArray()) vInner.getAsJsonArray()
                    .size() else 0) + " values]"
            else if (node.type == 8) valRaw = vInner.getAsString()
            else valRaw = vInner.toString()
        }

        // 2. 翻译与智能上下文识别 (复用逻辑)
        var trans = getTranslation(key) // 这里叫 trans
        var specialTrans: String? = null

        // (B) 上下文识别
        if ((key == "Name" || key == "id" || key == "name") && valRaw.contains("minecraft:")) {
            specialTrans = getItemTranslation(valRaw)
        } else if (key == "id" && node.type == 2) {
            if (node.parent != null) {
                val pKey = node.parent!!.key
                val gpKey = if (node.parent!!.parent != null) node.parent!!.parent!!.key else ""
                if ("ench" == pKey || "Enchantments" == pKey || "ench" == gpKey || "Enchantments" == gpKey) {
                    val name = getEnchantTranslation(valRaw)
                    if (name != null) specialTrans = context.getString(R.string.msg_enchant) + name
                }
            }
        } else if (key == "Id" && node.type == 1) {
            if (node.parent != null) {
                val pKey = node.parent!!.key
                val gpKey = if (node.parent!!.parent != null) node.parent!!.parent!!.key else ""
                if ("ActiveEffects" == pKey || "ActiveEffects" == gpKey) {
                    val name = getPotionTranslation(valRaw)
                    if (name != null) specialTrans = context.getString(R.string.msg_potion) + name
                }
            }
        }

        if (specialTrans != null) {
            trans = specialTrans
        }

        // (D) 数值智能解析
        var smartVal: String? = null
        if (node.type != 9 && node.type != 10 && vInner != null && (currentMode == MODE_SMART || currentMode == MODE_SIMPLE)) {
            smartVal = parseValue(key, vInner.toString())
        }

        // === 3. UI 渲染 (已修复变量名) ===

        // Key 显示
        // 这里删掉了长标题判定，极简模式直接显示 trans
        if (currentMode == MODE_SIMPLE && trans != null) {
            tvKey.setText(trans) // 使用 trans
            tvKey.setTextColor(Color.parseColor("#009688"))
        } else {
            tvKey.setText(key)
            tvKey.setTextColor(Color.parseColor("#333333"))
        }

        // Value 显示
        if (smartVal != null) {
            tvValue.setText(smartVal)
            tvValue.setTextColor(Color.parseColor("#E91E63"))
        } else {
            tvValue.setText(valRaw)
            tvValue.setTextColor(Color.parseColor("#666666"))
        }

        // Desc 显示
        var showDesc = false // 【修复】这里定义了 showDesc
        val sbDesc = StringBuilder()

        if ((currentMode == MODE_TRANS || currentMode == MODE_SMART) && trans != null) {
            sbDesc.append(trans) // 使用 trans
            showDesc = true
        }

        // 极简模式下不再重复显示 Desc，除非你想特殊处理

        // 智能值追加 (根据你刚才的要求，这里已经删掉了重复显示 smartVal 的逻辑)
        if (showDesc) {
            tvDesc.setVisibility(View.VISIBLE)
            tvDesc.setText(sbDesc.toString())
        } else {
            tvDesc.setVisibility(View.GONE)
        }

        return convertView
    }

    private fun setupNormalIcon(icon: TextView, type: Int) {
        icon.setTypeface(null, Typeface.BOLD)
        icon.setTextColor(Color.WHITE)
        when (type) {
            1 -> {
                icon.setText("B")
                icon.setBackgroundColor(Color.parseColor("#B0BEC5"))
            }

            2 -> {
                icon.setText("S")
                icon.setBackgroundColor(Color.parseColor("#90A4AE"))
            }

            3 -> {
                icon.setText("I")
                icon.setBackgroundColor(Color.parseColor("#03A9F4"))
            }

            4 -> {
                icon.setText("L")
                icon.setBackgroundColor(Color.parseColor("#3F51B5"))
            }

            5 -> {
                icon.setText("F")
                icon.setBackgroundColor(Color.parseColor("#E91E63"))
            }

            6 -> {
                icon.setText("D")
                icon.setBackgroundColor(Color.parseColor("#9C27B0"))
            }

            7 -> {
                icon.setText("[B]")
                icon.setBackgroundColor(Color.parseColor("#607D8B"))
            }

            8 -> {
                icon.setText("T")
                icon.setBackgroundColor(Color.parseColor("#4CAF50"))
            }

            9 -> {
                icon.setText("[]")
                icon.setBackgroundColor(Color.parseColor("#FF9800"))
            }

            10 -> {
                icon.setText("{}")
                icon.setBackgroundColor(Color.parseColor("#795548"))
            }

            11 -> {
                icon.setText("[I]")
                icon.setBackgroundColor(Color.parseColor("#00BCD4"))
            }

            12 -> {
                icon.setText("[L]")
                icon.setBackgroundColor(Color.parseColor("#673AB7"))
            }

            else -> {
                icon.setText("?")
                icon.setBackgroundColor(Color.GRAY)
            }
        }
    }

    // === 【新增】根据路径自动展开树并定位 ===
    fun expandPath(pathStack: Stack<String?>?): Int {
        if (pathStack == null || pathStack.isEmpty()) return 0

        // 1. 复制路径 (以免影响原栈)
        val targetPath: MutableList<String?> = ArrayList<String?>(pathStack)

        var currentSearchIndex = 0 // 当前在 visibleNodes 里的搜索起始位置
        var finalPosition = 0 // 最终目标位置

        // 2. 逐层寻找并展开
        for (targetKey in targetPath) {
            var found = false

            // 在当前可见列表中寻找匹配的 Key
            // 注意：因为列表是动态增长的，我们只需要从上一次找到的位置往下找
            for (i in currentSearchIndex..<visibleNodes.size) {
                val node = visibleNodes.get(i)

                // 找到匹配的 Key
                if (node.key == targetKey) {
                    // 如果它是个容器且还没展开，就展开它
                    if ((node.type == 9 || node.type == 10) && !node.isExpanded) {
                        toggleExpand(i) // 这会改变 visibleNodes 的大小
                    }

                    // 记录位置，准备找下一层
                    currentSearchIndex = i + 1 // 下一层肯定在当前节点下面
                    finalPosition = i
                    found = true
                    break
                }

                // 优化：如果缩进层级已经小于目标层级，说明找过头了（跑到别的分支了）
                // 但由于 visibleNodes 是扁平的，这里简单处理，遍历即可
            }

            // 如果某一层没找到（比如数据变了），就停在这一层
            if (!found) break
        }

        return finalPosition
    }

    // === 【新增】获取指定位置节点的完整路径链 ===
    fun getNodePath(position: Int): MutableList<String?> {
        if (position < 0 || position >= visibleNodes.size) return ArrayList<String?>()

        var node = visibleNodes.get(position)
        val path: MutableList<String?> = ArrayList<String?>()

        // 如果当前点击的是“值”（不是容器），我们应该定位到它的【父容器】
        // 这样切回列表时，能看到这个值
        if (node.type != 9 && node.type != 10) {
            node = node.parent
        }

        // 向上回溯直到根节点
        while (node != null && node.parent != null) { // parent != null 排除虚拟ROOT
            path.add(node.key)
            node = node.parent
        }

        // 回溯出来是反的（tag, 0, Inventory），需要反转回（Inventory, 0, tag）
        Collections.reverse(path)
        return path
    }

    // === 【新增】获取指定位置节点的父容器（用于编辑操作）===
    fun getParentContainer(position: Int): JsonObject? {
        if (position < 0 || position >= visibleNodes.size) return null

        val node = visibleNodes.get(position)


        // 如果是根节点，没有父容器
        if (node.parent == null || node.parent!!.value == null) {
            return null
        }


        // 获取父节点的 value（应该是 Compound 类型）
        val parentValue = node.parent!!.value
        if (parentValue!!.isJsonObject()) {
            val parentObj = parentValue.getAsJsonObject()
            // 解包装获取实际的 Compound 内容
            if (parentObj.has("v") && parentObj.get("v").isJsonObject()) {
                return parentObj.getAsJsonObject("v")
            }
        }
        return null
    }

    // === 【新增】获取节点自身的 key（用于重命名/删除）===
    fun getNodeKey(position: Int): String? {
        if (position < 0 || position >= visibleNodes.size) return null
        return visibleNodes.get(position).key
    }

    // === 【新增】获取节点自身的完整数据（用于复制）===
    fun getNodeData(position: Int): JsonObject? {
        if (position < 0 || position >= visibleNodes.size) return null
        val node = visibleNodes.get(position)
        if (node.value != null && node.value!!.isJsonObject()) {
            return node.value!!.getAsJsonObject()
        }
        return null
    }

    companion object {
        // 四种显示模式常量
        const val MODE_RAW: Int = 0
        const val MODE_TRANS: Int = 1
        const val MODE_SMART: Int = 2
        const val MODE_SIMPLE: Int = 3
    }
}