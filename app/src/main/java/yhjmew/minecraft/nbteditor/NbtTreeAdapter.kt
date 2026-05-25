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
import java.util.Stack

class NbtTreeAdapter(private val context: Context, rootData: JsonObject?) : BaseAdapter() {
    private var currentMode = MODE_RAW

    class Node(
        var key: String,
        var value: JsonElement?,
        var level: Int,
        var parent: Node?
    ) {
        var type = 0
        var isExpanded = false

        init {
            type = when (val v = value) {
                null -> 0
                else -> {
                    if (v.isJsonObject && v.asJsonObject.has("t")) v.asJsonObject.get("t").asInt
                    else 0
                }
            }
        }
    }

    private val rootNode = Node("ROOT", null, -1, null).also { it.isExpanded = true }
    private val visibleNodes = mutableListOf<Node>()

    init {
        if (rootData != null) {
            buildChildren(rootNode, rootData)
        }
    }

    private fun buildChildren(parent: Node, container: JsonObject) {
        val keys = container.keySet().sorted()
        val children = mutableListOf<Node>()
        for (k in keys) {
            val v = container.get(k)
            children.add(Node(k, v, parent.level + 1, parent))
        }
        visibleNodes.addAll(children)
    }

    fun toggleExpand(position: Int) {
        val node = visibleNodes[position]
        if (node.type != 9 && node.type != 10) return

        if (node.isExpanded) {
            node.isExpanded = false
            val startIndex = position + 1
            while (startIndex < visibleNodes.size) {
                val next = visibleNodes[startIndex]
                if (next.level > node.level) visibleNodes.removeAt(startIndex)
                else break
            }
        } else {
            node.isExpanded = true
            val subNodes = mutableListOf<Node>()
            val innerV = node.value!!.asJsonObject.get("v")

            if (node.type == 10) {
                val jo = innerV.asJsonObject
                val keys = jo.keySet().sorted()
                for (k in keys) subNodes.add(Node(k, jo.get(k), node.level + 1, node))
            } else if (node.type == 9) {
                val ja = innerV.asJsonArray
                val itemType = node.value!!.asJsonObject.get("itemType").asInt
                for (i in 0 until ja.size()) {
                    val wrapper = JsonObject()
                    wrapper.addProperty("t", itemType)
                    wrapper.add("v", ja[i])
                    subNodes.add(Node(i.toString(), wrapper, node.level + 1, node))
                }
            }
            visibleNodes.addAll(position + 1, subNodes)
        }
        notifyDataSetChanged()
    }

    fun isContainer(position: Int): Boolean {
        val t = visibleNodes[position].type
        return t == 9 || t == 10
    }

    fun getNode(position: Int): Node {
        return visibleNodes[position]
    }

    fun setViewMode(mode: Int) {
        currentMode = mode
        notifyDataSetChanged()
    }

    override fun getCount(): Int = visibleNodes.size

    override fun getItem(position: Int): Any = visibleNodes[position]

    override fun getItemId(position: Int): Long = position.toLong()

    @Suppress("DEPRECATION")
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_nbt_tree, parent, false)

        val node = visibleNodes[position]

        val vIndent = view.findViewById<View>(R.id.view_indentation)
        val tvExpand = view.findViewById<TextView>(R.id.tv_expand_icon)
        val tvIcon = view.findViewById<TextView>(R.id.tv_type_icon)
        val tvKey = view.findViewById<TextView>(R.id.tv_key)
        val tvValue = view.findViewById<TextView>(R.id.tv_value)
        val tvDesc = view.findViewById<TextView>(R.id.tv_key_desc)

        val lp = vIndent.layoutParams
        lp.width = (node.level * 24 * context.resources.displayMetrics.density).toInt()
        vIndent.layoutParams = lp

        if (node.type == 9 || node.type == 10) {
            tvExpand.visibility = View.VISIBLE
            tvExpand.text = if (node.isExpanded) "▼" else "▶"
        } else {
            tvExpand.visibility = View.INVISIBLE
        }

        val emoji = getEmojiIcon(node.key)
        if (emoji != null) {
            tvIcon.text = emoji
            tvIcon.setBackgroundColor(Color.TRANSPARENT)
            tvIcon.textSize = 18f
        } else {
            setupNormalIcon(tvIcon, node.type)
            tvIcon.textSize = 14f
        }

        val key = node.key
        var valRaw = ""
        val vInner = (node.value as? JsonObject)?.let { obj ->
            if (obj.has("v")) obj.get("v") else null
        }

        if (vInner != null) {
            valRaw = when (node.type) {
                9 -> "[List: ${if (vInner.isJsonArray) vInner.asJsonArray.size() else 0} items]"
                10 -> "{Compound: ${if (vInner.isJsonObject) vInner.asJsonObject.size() else 0} entries}"
                7, 11, 12 -> "[Array: ${if (vInner.isJsonArray) vInner.asJsonArray.size() else 0} values]"
                8 -> vInner.asString
                else -> vInner.toString()
            }
        }

        var trans = getTranslation(key)
        var specialTrans: String? = null

        if ((key == "Name" || key == "id" || key == "name") && valRaw.contains("minecraft:")) {
            specialTrans = getItemTranslation(valRaw)
        } else if (key == "id" && node.type == 2) {
            val pKey = node.parent?.key
            val gpKey = node.parent?.parent?.key
            if (pKey == "ench" || pKey == "Enchantments" || gpKey == "ench" || gpKey == "Enchantments") {
                val name = getEnchantTranslation(valRaw)
                if (name != null) specialTrans = "${context.getString(R.string.msg_enchant)}$name"
            }
        } else if (key == "Id" && node.type == 1) {
            val pKey = node.parent?.key
            val gpKey = node.parent?.parent?.key
            if (pKey == "ActiveEffects" || gpKey == "ActiveEffects") {
                val name = getPotionTranslation(valRaw)
                if (name != null) specialTrans = "${context.getString(R.string.msg_potion)}$name"
            }
        }

        if (specialTrans != null) {
            trans = specialTrans
        }

        var smartVal: String? = null
        if (node.type != 9 && node.type != 10 && vInner != null &&
            (currentMode == MODE_SMART || currentMode == MODE_SIMPLE)) {
            smartVal = parseValue(key, vInner.toString())
        }

        if (currentMode == MODE_SIMPLE && trans != null) {
            tvKey.text = trans
            tvKey.setTextColor(Color.parseColor("#009688"))
        } else {
            tvKey.text = key
            tvKey.setTextColor(Color.parseColor("#333333"))
        }

        if (smartVal != null) {
            tvValue.text = smartVal
            tvValue.setTextColor(Color.parseColor("#E91E63"))
        } else {
            tvValue.text = valRaw
            tvValue.setTextColor(Color.parseColor("#666666"))
        }

        val sbDesc = StringBuilder()
        var showDesc = false

        if ((currentMode == MODE_TRANS || currentMode == MODE_SMART) && trans != null) {
            sbDesc.append(trans)
            showDesc = true
        }

        if (showDesc) {
            tvDesc.visibility = View.VISIBLE
            tvDesc.text = sbDesc.toString()
        } else {
            tvDesc.visibility = View.GONE
        }

        return view
    }

    @Suppress("DEPRECATION")
    private fun setupNormalIcon(icon: TextView, type: Int) {
        icon.typeface = null
        icon.setTypeface(null, Typeface.BOLD)
        icon.setTextColor(Color.WHITE)
        when (type) {
            1 -> { icon.text = "B"; icon.setBackgroundColor(Color.parseColor("#B0BEC5")) }
            2 -> { icon.text = "S"; icon.setBackgroundColor(Color.parseColor("#90A4AE")) }
            3 -> { icon.text = "I"; icon.setBackgroundColor(Color.parseColor("#03A9F4")) }
            4 -> { icon.text = "L"; icon.setBackgroundColor(Color.parseColor("#3F51B5")) }
            5 -> { icon.text = "F"; icon.setBackgroundColor(Color.parseColor("#E91E63")) }
            6 -> { icon.text = "D"; icon.setBackgroundColor(Color.parseColor("#9C27B0")) }
            7 -> { icon.text = "[B]"; icon.setBackgroundColor(Color.parseColor("#607D8B")) }
            8 -> { icon.text = "T"; icon.setBackgroundColor(Color.parseColor("#4CAF50")) }
            9 -> { icon.text = "[]"; icon.setBackgroundColor(Color.parseColor("#FF9800")) }
            10 -> { icon.text = "{}"; icon.setBackgroundColor(Color.parseColor("#795548")) }
            11 -> { icon.text = "[I]"; icon.setBackgroundColor(Color.parseColor("#00BCD4")) }
            12 -> { icon.text = "[L]"; icon.setBackgroundColor(Color.parseColor("#673AB7")) }
            else -> { icon.text = "?"; icon.setBackgroundColor(Color.GRAY) }
        }
    }

    fun expandPath(pathStack: Stack<String?>?): Int {
        if (pathStack.isNullOrEmpty()) return 0

        val targetPath = ArrayList(pathStack)

        var currentSearchIndex = 0
        var finalPosition = 0

        for (targetKey in targetPath) {
            var found = false

            for (i in currentSearchIndex until visibleNodes.size) {
                val node = visibleNodes[i]

                if (node.key == targetKey) {
                    if ((node.type == 9 || node.type == 10) && !node.isExpanded) {
                        toggleExpand(i)
                    }
                    currentSearchIndex = i + 1
                    finalPosition = i
                    found = true
                    break
                }
            }

            if (!found) break
        }

        return finalPosition
    }

    fun getNodePath(position: Int): MutableList<String?> {
        if (position < 0 || position >= visibleNodes.size) return ArrayList()

        var node: Node? = visibleNodes[position]
        val path = mutableListOf<String?>()

        if (node != null && node.type != 9 && node.type != 10) {
            node = node.parent
        }

        while (node != null && node.parent != null) {
            path.add(node.key)
            node = node.parent
        }

        path.reverse()
        return path
    }

    fun getParentContainer(position: Int): JsonObject? {
        if (position < 0 || position >= visibleNodes.size) return null

        val node = visibleNodes[position]

        if (node.parent == null || node.parent!!.value == null) return null

        val parentValue = node.parent!!.value
        if (parentValue != null && parentValue.isJsonObject) {
            val parentObj = parentValue.asJsonObject
            if (parentObj.has("v") && parentObj.get("v").isJsonObject) {
                return parentObj.getAsJsonObject("v")
            }
        }
        return null
    }

    fun getNodeKey(position: Int): String? {
        if (position < 0 || position >= visibleNodes.size) return null
        return visibleNodes[position].key
    }

    fun getNodeData(position: Int): JsonObject? {
        if (position < 0 || position >= visibleNodes.size) return null
        val node = visibleNodes[position]
        return node.value as? JsonObject
    }

    companion object {
        const val MODE_RAW = 0
        const val MODE_TRANS = 1
        const val MODE_SMART = 2
        const val MODE_SIMPLE = 3
    }
}