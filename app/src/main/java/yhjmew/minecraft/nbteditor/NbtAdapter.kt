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
import java.util.Locale

class NbtAdapter(private val context: Context, val data: JsonObject?) : BaseAdapter() {
    private var originalKeys = mutableListOf<String>()
    private var displayKeys = mutableListOf<String>()

    private var currentMode: Int = MODE_RAW

    private var currentGrandParentKey: String? = ""

    init {
        refreshKeys()
    }

    fun setPathContext(grandParent: String?) {
        this.currentGrandParentKey = grandParent
        notifyDataSetChanged()
    }

    fun refreshKeys() {
        originalKeys = if (data != null) {
            data.keySet().sorted().toMutableList()
        } else {
            mutableListOf()
        }
        displayKeys = ArrayList(originalKeys)
        notifyDataSetChanged()
    }

    fun filter(query: String?) {
        if (query.isNullOrBlank()) {
            displayKeys = ArrayList(originalKeys)
        } else {
            val lowerQuery = query.lowercase(Locale.getDefault()).trim()
            val filtered = mutableListOf<String>()

            for (key in originalKeys) {
                val matchKey = key.lowercase(Locale.getDefault()).contains(lowerQuery)
                val trans = NbtTranslator.getTranslation(key)
                val matchTrans = trans?.lowercase(Locale.getDefault())?.contains(lowerQuery) == true

                if (matchKey || matchTrans) {
                    filtered.add(key)
                }
            }
            displayKeys = filtered
        }
        notifyDataSetChanged()
    }

    fun setViewMode(mode: Int) {
        currentMode = mode
        notifyDataSetChanged()
    }

    override fun getCount(): Int = displayKeys.size

    override fun getItem(position: Int): Any? {
        return data?.get(displayKeys[position])
    }

    override fun getItemId(position: Int): Long = position.toLong()

    fun getKey(position: Int): String = displayKeys[position]

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        var convertView = convertView
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_nbt, parent, false)
        }

        val icon = convertView!!.findViewById<TextView>(R.id.tv_type_icon)
        val tvKey = convertView.findViewById<TextView>(R.id.tv_key)
        val tvKeyDesc = convertView.findViewById<TextView>(R.id.tv_key_desc)
        val tvValue = convertView.findViewById<TextView>(R.id.tv_value)

        val key = displayKeys[position]
        val rawItem = data?.get(key)

        val itemData: JsonObject
        if (rawItem != null && rawItem.isJsonObject) {
            itemData = rawItem.asJsonObject
        } else {
            itemData = JsonObject()
            itemData.addProperty("t", 8)
            itemData.addProperty("v", rawItem?.toString() ?: "null")
        }

        var type = 0
        if (itemData.has("t")) type = itemData.get("t").asInt

        setupIcon(icon, type)

        var rawVal = ""
        val v = if (itemData.has("v")) itemData.get("v") else null

        if (v != null) {
            rawVal = when (type) {
                9 -> "[List: ${if (v.isJsonArray) v.asJsonArray.size() else 0}]"
                10 -> "{Compound: ${if (v.isJsonObject) v.asJsonObject.size() else 0}}"
                7, 11, 12 -> "[Array: ${if (v.isJsonArray) v.asJsonArray.size() else 0}]"
                8 -> {
                    val s = v.asString
                    "\"${if (s.length > 50) "${s.substring(0, 50)}..." else s}\""
                }
                else -> v.toString()
            }
        }

        var translation = NbtTranslator.getTranslation(key)
        var specialTrans: String? = null

        if ((key == "Name" || key == "id" || key == "name") && rawVal.contains("minecraft:")) {
            specialTrans = NbtTranslator.getItemTranslation(rawVal)
        } else if (key == "id" && type == 2) {
            if (currentGrandParentKey == "ench" || currentGrandParentKey == "Enchantments") {
                val name = NbtTranslator.getEnchantTranslation(rawVal)
                if (name != null) specialTrans = "${context.getString(R.string.msg_enchant)}$name"
            }
        } else if (key == "Id" && type == 1) {
            if (currentGrandParentKey == "ActiveEffects") {
                val name = NbtTranslator.getPotionTranslation(rawVal)
                if (name != null) specialTrans = "${context.getString(R.string.msg_potion)}$name"
            }
        }

        if (specialTrans != null) {
            translation = specialTrans
        }

        var parsedVal: String? = null
        if (currentMode == MODE_SMART || currentMode == MODE_SIMPLE) {
            if (type != 9 && type != 10 && type != 7 && type != 11 && type != 12) {
                parsedVal = NbtTranslator.parseValue(key, v?.toString() ?: "")
            }
        }

        @Suppress("DEPRECATION")
        if (currentMode == MODE_SIMPLE && translation != null) {
            tvKey.text = translation
            tvKey.setTextColor(Color.parseColor("#009688"))
        } else {
            tvKey.text = key
            @Suppress("DEPRECATION")
            tvKey.setTextColor(Color.parseColor("#212121"))
        }

        @Suppress("DEPRECATION")
        if (parsedVal != null) {
            tvValue.text = parsedVal
            tvValue.setTextColor(Color.parseColor("#E91E63"))
        } else {
            tvValue.text = rawVal
            @Suppress("DEPRECATION")
            tvValue.setTextColor(Color.parseColor("#757575"))
        }

        val sbDesc = StringBuilder()
        if ((currentMode == MODE_TRANS || currentMode == MODE_SMART) && translation != null) {
            sbDesc.append(translation)
        }

        if (sbDesc.isNotEmpty()) {
            tvKeyDesc.visibility = View.VISIBLE
            tvKeyDesc.text = sbDesc.toString()
        } else {
            tvKeyDesc.visibility = View.GONE
        }

        return convertView
    }

    @Suppress("DEPRECATION")
    private fun setupIcon(icon: TextView, type: Int) {
        icon.typeface = null
        icon.setTypeface(null, Typeface.BOLD)
        icon.setTextColor(Color.WHITE)
        when (type) {
            1 -> {
                icon.text = "B"
                icon.setBackgroundColor(Color.parseColor("#B0BEC5"))
            }
            2 -> {
                icon.text = "S"
                icon.setBackgroundColor(Color.parseColor("#90A4AE"))
            }
            3 -> {
                icon.text = "I"
                icon.setBackgroundColor(Color.parseColor("#03A9F4"))
            }
            4 -> {
                icon.text = "L"
                icon.setBackgroundColor(Color.parseColor("#3F51B5"))
            }
            5 -> {
                icon.text = "F"
                icon.setBackgroundColor(Color.parseColor("#E91E63"))
            }
            6 -> {
                icon.text = "D"
                icon.setBackgroundColor(Color.parseColor("#9C27B0"))
            }
            7 -> {
                icon.text = "[B]"
                icon.setBackgroundColor(Color.parseColor("#607D8B"))
            }
            8 -> {
                icon.text = "T"
                icon.setBackgroundColor(Color.parseColor("#4CAF50"))
            }
            9 -> {
                icon.text = "[]"
                icon.setBackgroundColor(Color.parseColor("#FF9800"))
            }
            10 -> {
                icon.text = "{}"
                icon.setBackgroundColor(Color.parseColor("#795548"))
            }
            11 -> {
                icon.text = "[I]"
                icon.setBackgroundColor(Color.parseColor("#00BCD4"))
            }
            12 -> {
                icon.text = "[L]"
                icon.setBackgroundColor(Color.parseColor("#673AB7"))
            }
            else -> {
                icon.text = "?"
                icon.setBackgroundColor(Color.GRAY)
            }
        }
    }

    companion object {
        const val MODE_RAW = 0
        const val MODE_TRANS = 1
        const val MODE_SMART = 2
        const val MODE_SIMPLE = 3
    }
}