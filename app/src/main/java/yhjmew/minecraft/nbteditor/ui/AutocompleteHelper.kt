package yhjmew.minecraft.nbteditor.ui

import android.app.AlertDialog
import android.graphics.Color
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.core.graphics.toColorInt
import androidx.core.graphics.drawable.toDrawable
import com.google.gson.JsonParser
import yhjmew.minecraft.nbteditor.MainActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

/**
 * 自动填充数据模型
 */
data class AutocompleteEntry(
    val name: String,
    val namespace: String,
    val category: String,
    val id: String? = null
) {
    /** 效果/附魔返回数字ID，其他返回 minecraft:namespace */
    val fillValue: String
        get() = id?.takeIf { it.isNotEmpty() } ?: namespace

    override fun toString(): String = name
}

// ============================================
// 自动填充对话框（MainActivity 扩展函数）
// ============================================
fun MainActivity.showAutocompleteDialog(targetInput: EditText, dataType: String) {
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 16, 24, 16)
    }

    val etSearch = EditText(this).apply {
        hint = "Search ${getTypeLabel(dataType)}..."
        textSize = 14f
    }
    layout.addView(etSearch)

    val btnClear = Button(this).apply {
        text = "Clear"
        setTextColor("#2196F3".toColorInt())
        setBackgroundColor(Color.TRANSPARENT)
    }
    layout.addView(btnClear)

    val lvSuggestions = ListView(this).apply {
        @Suppress("DEPRECATION")
        divider = "#EEEEEE".toColorInt().toDrawable()
        dividerHeight = 1
    }
    layout.addView(lvSuggestions, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 400
    ))

    val allItems = loadAutocompleteData(dataType)
    val displayList = ArrayList<AutocompleteEntry?>(allItems)

    val adapter = object : ArrayAdapter<AutocompleteEntry?>(
        this, android.R.layout.simple_list_item_2, displayList
    ) {
        private var mFilter: Filter? = null

        override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
            val view = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val item = getItem(position)
            val tv1 = view.findViewById<TextView>(android.R.id.text1)
            val tv2 = view.findViewById<TextView>(android.R.id.text2)

            if (item == null) return view
            val label = when (item.category) {
                "block" -> "[Block] "; "item" -> "[Item] "
                "effect" -> "[Effect] "; "enchant" -> "[Enchant] "; else -> ""
            }
            tv1.text = "$label${item.name}"
            @Suppress("DEPRECATION")
            tv1.setTextColor("#333333".toColorInt())
            tv1.textSize = 15f

            val subtitle = if (item.category == "effect" || item.category == "enchant")
                "ID:${item.id} | ${item.namespace}"
            else item.namespace
            tv2.text = subtitle
            @Suppress("DEPRECATION")
            tv2.setTextColor("#666666".toColorInt())
            tv2.textSize = 12f
            return view
        }

        override fun getFilter(): Filter {
            if (mFilter == null) {
                mFilter = object : Filter() {
                    override fun performFiltering(constraint: CharSequence?): FilterResults {
                        val results = FilterResults()
                        val filtered = ArrayList<AutocompleteEntry?>()
                        if (constraint.isNullOrEmpty()) {
                            filtered.addAll(allItems)
                        } else {
                            val pattern = constraint.toString().lowercase(Locale.getDefault()).trim()
                            for (item in allItems) {
                                val match = item.namespace.lowercase(Locale.getDefault()) == pattern
                                        || (item.id != null && item.id == pattern)
                                        || item.name.lowercase(Locale.getDefault()).contains(pattern)
                                        || item.namespace.lowercase(Locale.getDefault()).contains(pattern)
                                        || ((item.category == "effect" || item.category == "enchant")
                                        && item.id != null && item.id.contains(pattern))
                                if (match) filtered.add(item)
                            }
                        }
                        results.values = filtered; results.count = filtered.size
                        return results
                    }

                    override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                        clear()
                        @Suppress("UNCHECKED_CAST")
                        if (results.values != null) addAll(results.values as MutableList<AutocompleteEntry?>)
                        notifyDataSetChanged()
                    }
                }
            }
            return mFilter!!
        }
    }

    lvSuggestions.adapter = adapter
    btnClear.setOnClickListener { etSearch.setText("") }

    val dialog = AlertDialog.Builder(this)
        .setTitle("Choose ${getTypeLabel(dataType)}")
        .setView(layout)
        .setNegativeButton("Cancel", null)
        .create()

    dialog.window?.setLayout(
        (resources.displayMetrics.widthPixels * 0.9).toInt(),
        WindowManager.LayoutParams.WRAP_CONTENT
    )
    dialog.show()

    etSearch.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            adapter.filter.filter(s.toString())
        }
        override fun afterTextChanged(s: android.text.Editable?) {}
    })

    lvSuggestions.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
        adapter.getItem(position)?.let {
            targetInput.setText(it.fillValue)
            targetInput.setSelection(targetInput.text.length)
        }
        dialog.dismiss()
    }
}

// ============================================
// 自动填充数据加载
// ============================================
fun MainActivity.loadAutocompleteData(dataType: String): MutableList<AutocompleteEntry> {
    val list = mutableListOf<AutocompleteEntry>()
    val langFolder = if ("zh" == currentLang) "zh" else "en"
    try {
        when (dataType) {
            "item" -> loadJsonToList(list, "$langFolder/item_translation.json", "item")
            "block" -> loadJsonToList(list, "$langFolder/block_translation.json", "block")
            "effect" -> loadJsonToList(list, "$langFolder/potion_translation.json", "effect")
            "enchant" -> loadJsonToList(list, "$langFolder/ench_translation.json", "enchant")
            "all" -> {
                loadJsonToList(list, "$langFolder/item_translation.json", "item")
                loadJsonToList(list, "$langFolder/block_translation.json", "block")
            }
        }
    } catch (_: Exception) {}
    return list
}

private fun MainActivity.loadJsonToList(
    list: MutableList<AutocompleteEntry>,
    assetPath: String,
    category: String
) {
    try {
        val inputStream = assets.open(assetPath)
        val json = BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
        val array = JsonParser.parseString(json).asJsonArray
        for (i in 0 until array.size()) {
            val obj = array[i].asJsonObject
            val name = obj.get("name")?.asString ?: ""
            var namespace = obj.get("namespace")?.asString ?: ""
            val id = obj.get("id")?.asString
            if (category != "effect" && category != "enchant") {
                if (!namespace.contains(":")) namespace = "minecraft:$namespace"
            }
            if (namespace.isNotEmpty()) list.add(AutocompleteEntry(name, namespace, category, id))
        }
    } catch (_: Exception) {}
}

fun detectAutocompleteType(parent: String?, grandParent: String?): String {
    if (parent == "ActiveEffects" || grandParent == "ActiveEffects"
        || parent == "MobEffects" || parent == "Effects") return "effect"
    if (parent == "ench" || parent == "Enchantments" || parent == "StoredEnchantments"
        || grandParent == "ench" || grandParent == "Enchantments") return "enchant"
    return "all"
}

fun getTypeLabel(type: String): String = when (type) {
    "item" -> "Item"; "block" -> "Block"; "effect" -> "Effect"
    "enchant" -> "Enchant"; "all" -> "All"; else -> "Data"
}