package yhjmew.minecraft.nbteditor

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.google.gson.JsonObject
import java.util.Collections
import java.util.Locale

class NbtAdapter(private val context: Context, val data: JsonObject?) : BaseAdapter() {
    // 【核心】我们需要两个列表：一个存原始所有Key，一个存当前显示的Key
    private var originalKeys: MutableList<String>? = null // 原始全量数据
    private var displayKeys: MutableList<String>? = null // 过滤后显示的数据

    private var currentMode: Int = MODE_RAW

    // 当前所在的父文件夹名
    private var currentGrandParentKey: String? = "" // 爷爷文件夹名 (用于判断 list -> index -> content)

    init {
        refreshKeys()
    }

    fun setPathContext(grandParent: String?) {
        this.currentGrandParentKey = grandParent
        notifyDataSetChanged()
    }

    fun refreshKeys() {
        if (this.data != null) {
            // 1. 获取所有 Key
            this.originalKeys = ArrayList<String>(data.keySet())
            Collections.sort<String?>(originalKeys)
        } else {
            this.originalKeys = ArrayList<String>()
        }
        // 2. 默认显示所有
        this.displayKeys = ArrayList<String>(originalKeys)
        notifyDataSetChanged()
    }

    // 【新增】搜索过滤功能
    fun filter(query: String?) {
        if (query == null || query.trim { it <= ' ' }.isEmpty()) {
            // 如果搜索词为空，恢复显示所有
            this.displayKeys = ArrayList<String>(originalKeys)
        } else {
            // 开始过滤
            val lowerQuery = query.lowercase(Locale.getDefault()).trim { it <= ' ' }
            val filtered: MutableList<String> = ArrayList<String>()

            for (key in originalKeys!!) {
                // 1. 匹配 Key (英文)
                val matchKey = key.lowercase(Locale.getDefault()).contains(lowerQuery)

                // 2. 匹配 翻译 (中文)
                val trans = NbtTranslator.getTranslation(key)
                val matchTrans =
                    (trans != null && trans.lowercase(Locale.getDefault()).contains(lowerQuery))

                // 只要有一个匹配，就加入列表
                if (matchKey || matchTrans) {
                    filtered.add(key)
                }
            }
            this.displayKeys = filtered
        }
        notifyDataSetChanged()
    }

    fun setViewMode(mode: Int) {
        this.currentMode = mode
        notifyDataSetChanged()
    }

    override fun getCount(): Int {
        return if (displayKeys == null) 0 else displayKeys!!.size
    }

    override fun getItem(position: Int): Any? {
        return data!!.get(displayKeys!!.get(position))
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    fun getKey(position: Int): String? {
        return displayKeys!!.get(position)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        var convertView = convertView
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_nbt, parent, false)
        }

        val icon = convertView.findViewById<TextView>(R.id.tv_type_icon)
        val tvKey = convertView.findViewById<TextView>(R.id.tv_key)
        val tvKeyDesc = convertView.findViewById<TextView>(R.id.tv_key_desc)
        val tvValue = convertView.findViewById<TextView>(R.id.tv_value)

        val key = displayKeys!!.get(position)
        val rawItem = data!!.get(key)

        // 安全转换：确保 itemData 是 JsonObject，否则跳过
        val itemData: JsonObject
        if (rawItem != null && rawItem.isJsonObject) {
            itemData = rawItem.asJsonObject
        } else {
            // 数据格式异常，创建一个空占位
            itemData = JsonObject()
            itemData.addProperty("t", 8)
            itemData.addProperty("v", rawItem?.toString() ?: "null")
        }

        var type = 0
        if (itemData.has("t")) type = itemData.get("t").getAsInt()

        setupIcon(icon, type)

        // === 1. 准备原始值 (Raw Value) ===
        // 必须最先做这一步，因为后面判断物品ID需要用到它
        var rawVal = ""
        val v = if (itemData.has("v")) itemData.get("v") else null

        if (v != null) {
            if (type == 9) rawVal =
                "[List: " + (if (v.isJsonArray()) v.getAsJsonArray().size() else 0) + "]"
            else if (type == 10) rawVal =
                "{Compound: " + (if (v.isJsonObject()) v.getAsJsonObject().size() else 0) + "}"
            else if (type == 7 || type == 11 || type == 12) rawVal =
                "[Array: " + (if (v.isJsonArray()) v.getAsJsonArray().size() else 0) + "]"
            else if (type == 8) {
                val s = v.getAsString()
                rawVal = "\"" + (if (s.length > 50) s.substring(0, 50) + "..." else s) + "\""
            } else rawVal = v.toString()
        }

        // === 2. 翻译与智能上下文识别 (统一逻辑) ===

        // (A) 基础翻译
        var translation = NbtTranslator.getTranslation(key)
        var specialTrans: String? = null

        // (B) 上下文识别 (依靠 currentGrandParentKey)
        // 1. 物品 ID (Key=Name, Value="minecraft:...")
        if ((key == "Name" || key == "id" || key == "name") && rawVal.contains("minecraft:")) {
            specialTrans = NbtTranslator.getItemTranslation(rawVal)
        } else if (key == "id" && type == 2) { // Short
            // 列表模式下，结构通常是 ench -> 0 -> id，所以检查爷爷节点
            if ("ench" == currentGrandParentKey || "Enchantments" == currentGrandParentKey) {
                val name = NbtTranslator.getEnchantTranslation(rawVal)
                if (name != null) specialTrans = context.getString(R.string.msg_enchant) + name
            }
        } else if (key == "Id" && type == 1) { // Byte
            if ("ActiveEffects" == currentGrandParentKey) {
                val name = NbtTranslator.getPotionTranslation(rawVal)
                if (name != null) specialTrans = context.getString(R.string.msg_potion) + name
            }
        }

        // (C) 应用特殊翻译 (覆盖基础翻译)
        if (specialTrans != null) {
            translation = specialTrans
        }

        // (D) 数值智能解析 (Smart Value)
        var parsedVal: String? = null
        if (currentMode == MODE_SMART || currentMode == MODE_SIMPLE) {
            // 只有非容器类型才解析
            if (type != 9 && type != 10 && type != 7 && type != 11 && type != 12) {
                parsedVal = NbtTranslator.parseValue(key, if (v != null) v.toString() else "")
            }
        }

        // === 3. UI 渲染 (逻辑与 Tree 保持一致) ===

        // Key 显示
        if (currentMode == MODE_SIMPLE && translation != null) {
            // 【修改前】
            // if (translation.length() > 12) {
            //     simpleModeLongTitle = true;
            //     tvKey.setText(key);
            // } else { ... }

            // 【修改后】不管多长，极简模式强制只显示翻译！
            // 这样英文长句就会作为标题显示，虽然长，但也比双行重复好

            tvKey.setText(translation)
            tvKey.setTextColor(Color.parseColor("#009688"))

            // 如果你觉得英文太长显示不全，可以去 XML 里把 tvKey 的 singleLine="true" 改成 false
            // 或者 maxLines="2"
        } else {
            tvKey.setText(key)
            tvKey.setTextColor(Color.parseColor("#212121"))
        }

        // Value 显示
        if (parsedVal != null) {
            tvValue.setText(parsedVal)
            tvValue.setTextColor(Color.parseColor("#E91E63"))
        } else {
            tvValue.setText(rawVal)
            tvValue.setTextColor(Color.parseColor("#757575"))
        }

        // Desc (第二行) 显示
        val sbDesc = StringBuilder()
        if ((currentMode == MODE_TRANS || currentMode == MODE_SMART) && translation != null) {
            sbDesc.append(translation)
        }
        // if (currentMode == MODE_SIMPLE && simpleModeLongTitle && translation != null) {
        // sbDesc.append(translation);
        // }
        if (sbDesc.length > 0) {
            tvKeyDesc.setVisibility(View.VISIBLE)
            tvKeyDesc.setText(sbDesc.toString())
        } else {
            tvKeyDesc.setVisibility(View.GONE)
        }

        return convertView
    }

    private fun setupIcon(icon: TextView, type: Int) {
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

    companion object {
        const val MODE_RAW: Int = 0
        const val MODE_TRANS: Int = 1
        const val MODE_SMART: Int = 2
        const val MODE_SIMPLE: Int = 3
    }
}
