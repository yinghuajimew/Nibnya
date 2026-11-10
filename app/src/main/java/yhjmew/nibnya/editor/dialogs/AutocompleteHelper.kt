package yhjmew.nibnya.editor.dialogs

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import yhjmew.nibnya.MainActivity
import yhjmew.nibnya.R

/**
 * 一条可选项（物品 / 方块 / 效果 / 附魔）。
 *
 * @param category 粗分类（item / block / effect / enchant）
 * @param sub      细分类（tool / armor / food / slab / stairs …），见 [subLabelRes]
 * @param id       仅效果与附魔有数字 ID
 */
data class AutocompleteEntry(
    val name: String,
    val namespace: String,
    val category: String,
    val id: String? = null,
    val sub: String = "other"
) {
    /** 效果/附魔填数字 ID，其余填 `命名空间:标识符` */
    val fillValue: String
        get() = id?.takeIf { it.isNotEmpty() } ?: namespace
}

/** 「使用自定义 ID」那一行的标记 */
private const val SUB_CUSTOM = "custom"
private const val SUB_ALL = "all"

// ============================================
// 数据加载（带缓存；读词表里的 category 字段）
// ============================================

/**
 * 词表缓存。按 `语言|粗分类` 缓存，只解析一次。
 *
 * 细分类来自词表自带的 `category` 字段（见 assets 下各 `*_translation.json`），
 * 不再靠代码里猜命名规则。
 */
private object AutocompleteData {
    private val cache = HashMap<String, List<AutocompleteEntry>>()

    fun load(ctx: Context, lang: String, category: String): List<AutocompleteEntry> {
        val key = "$lang|$category"
        cache[key]?.let { return it }

        val folder = if (lang == "zh") "zh" else "en"
        val out = ArrayList<AutocompleteEntry>()
        when (category) {
            "all" -> {
                read(ctx, out, "$folder/item_translation.json", "item")
                read(ctx, out, "$folder/block_translation.json", "block")
            }
            "item" -> read(ctx, out, "$folder/item_translation.json", "item")
            "block" -> read(ctx, out, "$folder/block_translation.json", "block")
            "effect" -> read(ctx, out, "$folder/potion_translation.json", "effect")
            "enchant" -> read(ctx, out, "$folder/ench_translation.json", "enchant")
            "everything" -> {
                read(ctx, out, "$folder/item_translation.json", "item")
                read(ctx, out, "$folder/block_translation.json", "block")
                read(ctx, out, "$folder/potion_translation.json", "effect")
                read(ctx, out, "$folder/ench_translation.json", "enchant")
            }
        }
        cache[key] = out
        return out
    }

    private fun read(
        ctx: Context, out: MutableList<AutocompleteEntry>, path: String, category: String
    ) {
        try {
            val json = ctx.assets.open(path).use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }
            val arr = JsonParser.parseString(json).asJsonArray
            for (i in 0 until arr.size()) {
                val obj = arr[i].asJsonObject
                var ns = obj.get("namespace")?.asString ?: ""
                val name = obj.get("name")?.asString ?: ""
                val id = obj.get("id")?.asString
                val sub = obj.get("category")?.asString ?: "other"
                if (ns.isEmpty()) continue
                if (category != "effect" && category != "enchant" && !ns.contains(":")) {
                    ns = "minecraft:$ns"
                }
                out.add(AutocompleteEntry(name, ns, category, id, sub))
            }
        } catch (_: Exception) {
            // 资源缺失不该让选择框整体不可用
        }
    }
}

// ============================================
// 分类标签
// ============================================

/**
 * 粗分类。首项「全部」= 物品 + 方块 + 药水效果 + 附魔 的合集，
 * 便于「知道名字、只想快速搜一下」的场景（用户要求）。
 */
private val CATEGORIES = listOf("everything", "item", "block", "effect", "enchant")

/** 粗分类每类可选的细分类（顺序即下拉顺序）；首项恒为「全部」 */
private fun subCategoriesOf(category: String): List<String> = when (category) {
    "item" -> listOf(
        SUB_ALL, "tool", "armor", "food", "material", "spawn_egg",
        "blocklike", "vehicle", "bucket", "plant", "disc", "dye", "banner", "navig", "misc"
    )
    "block" -> listOf(
        SUB_ALL, "stone", "stairs", "slab", "wall", "fence", "door", "glass",
        "wool", "carpet", "concrete", "terracotta", "ore", "wood", "leaves",
        "sign", "candle", "coral", "light", "shulker", "copper", "head",
        "plant", "redstone", "element", "misc"
    )
    "everything" -> listOf(SUB_ALL)   // 全部：不细分（混在一起搜最快）
    else -> listOf(SUB_ALL)           // 效果 / 附魔 不细分
}

/** 细分类名（走字符串资源，中英各自翻译） */
fun subLabelRes(sub: String): Int = when (sub) {
    SUB_ALL -> R.string.sub_all
    "tool" -> R.string.sub_tool
    "armor" -> R.string.sub_armor
    "food" -> R.string.sub_food
    "material" -> R.string.sub_material
    "spawn_egg" -> R.string.sub_spawn_egg
    "blocklike" -> R.string.sub_blocklike
    "vehicle" -> R.string.sub_vehicle
    "bucket" -> R.string.sub_bucket
    "plant" -> R.string.sub_plant
    "disc" -> R.string.sub_disc
    "dye" -> R.string.sub_dye
    "banner" -> R.string.sub_banner
    "navig" -> R.string.sub_navig
    "misc" -> R.string.sub_misc
    "stone" -> R.string.sub_stone
    "stairs" -> R.string.sub_stairs
    "slab" -> R.string.sub_slab
    "wall" -> R.string.sub_wall
    "fence" -> R.string.sub_fence
    "door" -> R.string.sub_door
    "glass" -> R.string.sub_glass
    "wool" -> R.string.sub_wool
    "carpet" -> R.string.sub_carpet
    "concrete" -> R.string.sub_concrete
    "terracotta" -> R.string.sub_terracotta
    "ore" -> R.string.sub_ore
    "wood" -> R.string.sub_wood
    "leaves" -> R.string.sub_leaves
    "sign" -> R.string.sub_sign
    "candle" -> R.string.sub_candle
    "coral" -> R.string.sub_coral
    "light" -> R.string.sub_light
    "shulker" -> R.string.sub_shulker
    "copper" -> R.string.sub_copper
    "head" -> R.string.sub_head
    "redstone" -> R.string.sub_redstone
    "element" -> R.string.sub_element
    else -> R.string.sub_misc
}

/** 分类名对应的字符串资源 */
fun categoryLabelRes(type: String): Int = when (type) {
    "item" -> R.string.title_item
    "block" -> R.string.title_block
    "effect" -> R.string.title_effect
    "enchant" -> R.string.title_enchant
    "all" -> R.string.title_all
    "everything" -> R.string.title_all_types
    else -> R.string.title_data
}

// ============================================
// 选择对话框
// ============================================

fun MainActivity.showAutocompleteDialog(targetInput: EditText, dataType: String) {
    var current = when {
        CATEGORIES.contains(dataType) -> dataType
        dataType == "all" -> "everything"   // 原来 "all" 只是物品+方块，现在并入「全部」
        else -> "item"
    }
    // 编辑物品的 Name 时默认停在「物品」而不是混在一起的「全部」
    var currentSub = SUB_ALL
    var query = ""

    val pad = (14 * resources.displayMetrics.density).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, 0)
    }

    // ---- 搜索行：[输入框] [清空] ----
    val searchRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }
    val etSearch = EditText(this).apply {
        hint = getString(R.string.search_type_hint, getString(categoryLabelRes(current)))
        textSize = 14f
        maxLines = 1
    }
    searchRow.addView(
        etSearch, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    )
    val btnClear = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        text = getString(R.string.btn_clear)
        isAllCaps = false
        textSize = 13f
        setTextColor(getColor(R.color.select_text))
    }
    searchRow.addView(btnClear)
    root.addView(searchRow)

    // ---- 粗分类标签 ----
    val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val tabs = HashMap<String, TextView>()

    fun styleTab(tv: TextView, selected: Boolean) {
        tv.setTextColor(if (selected) getColor(R.color.select_text) else getColor(R.color.hint_text))
        tv.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        tv.setBackgroundColor(if (selected) getColor(R.color.select_bg) else Color.TRANSPARENT)
    }

    for (cat in CATEGORIES) {
        val tv = TextView(this).apply {
            text = getString(categoryLabelRes(cat))
            textSize = 13f
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        styleTab(tv, cat == current)
        tabs[cat] = tv
        tabRow.addView(tv)
    }
    root.addView(
        HorizontalScrollView(this).apply { addView(tabRow) },
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )

    // ---- 细分类下拉框（用户要求：粗分类固定，细分用下拉框）----
    val subRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, pad / 2, 0, 0)
    }
    val tvSubLabel = TextView(this).apply {
        text = getString(R.string.label_sub_category)
        textSize = 13f
        setTextColor(getColor(R.color.hint_text))
    }
    val spSub = Spinner(this)
    subRow.addView(tvSubLabel)
    subRow.addView(
        spSub, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    )
    root.addView(subRow)

    // ---- 结果列表 ----
    val listHeight = (resources.displayMetrics.heightPixels * 0.42f).toInt()
    val lv = ListView(this)
    root.addView(
        lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight)
    )

    // ---- 底部计数 ----
    val tvCount = TextView(this).apply {
        textSize = 12f
        setTextColor(getColor(R.color.muted_text))
        setPadding(pad, pad / 3, pad, pad / 3)
    }
    root.addView(tvCount)

    var adapter: ArrayAdapter<AutocompleteEntry>? = null
    var subValues: List<String> = subCategoriesOf(current)
    var suppressSub = false

    fun rebuildSubSpinner() {
        subValues = subCategoriesOf(current)
        val labels = subValues.map { getString(subLabelRes(it)) }
        suppressSub = true
        spSub.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels
        ).also { it.setDropDownViewResource(R.layout.item_spinner_dropdown) }
        val idx = subValues.indexOf(currentSub).coerceAtLeast(0)
        spSub.setSelection(idx)
        spSub.isEnabled = subValues.size > 1
        suppressSub = false
    }

    fun rebuild() {
        val all = AutocompleteData.load(this, currentLang ?: "zh", current)
        val pool = if (currentSub == SUB_ALL) all else all.filter { it.sub == currentSub }

        val raw = query.trim()
        val q = raw.lowercase(Locale.getDefault())

        val rows: MutableList<AutocompleteEntry> = ArrayList()

        // 「使用自定义 ID」：输入了词表里没有的东西
        val customCandidate = raw.isNotEmpty() && !raw.contains(' ') &&
                all.none { it.namespace.equals(raw, true) || it.id == raw }
        if (customCandidate) rows.add(AutocompleteEntry(raw, raw, SUB_CUSTOM, null, SUB_CUSTOM))

        val matched = if (q.isEmpty()) pool
        else pool.mapNotNull { e -> rank(e, q)?.let { e to it } }
            .sortedBy { it.second }
            .map { it.first }
        rows.addAll(matched)

        adapter = object : ArrayAdapter<AutocompleteEntry>(
            this, android.R.layout.simple_list_item_2, rows
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView
                    ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
                val item = getItem(position) ?: return view
                val tv1 = view.findViewById<TextView>(android.R.id.text1)
                val tv2 = view.findViewById<TextView>(android.R.id.text2)
                if (item.category == SUB_CUSTOM) {
                    tv1.text = getString(R.string.btn_use_custom_id, item.namespace)
                    tv1.setTextColor(getColor(R.color.select_text))
                    tv2.text = getString(R.string.msg_use_custom_id_hint)
                } else {
                    val prefix = when (item.category) {
                        "block" -> "[Block] "; "item" -> "[Item] "
                        "effect" -> "[Effect] "; "enchant" -> "[Enchant] "; else -> ""
                    }
                    tv1.text = "$prefix${item.name}"
                    tv1.setTextColor(getColor(R.color.nbt_label))
                    tv2.text = if (item.id != null) "ID:${item.id} | ${item.namespace}"
                    else item.namespace
                }
                tv1.textSize = 15f
                tv2.setTextColor(getColor(R.color.hint_text))
                tv2.textSize = 12f
                return view
            }
        }
        lv.adapter = adapter

        tvCount.text = if (q.isEmpty()) {
            getString(R.string.msg_autocomplete_count, pool.size, pool.size)
        } else if (matched.isEmpty()) {
            getString(R.string.msg_autocomplete_no_match)
        } else {
            getString(R.string.msg_autocomplete_count, pool.size, matched.size)
        }
    }

    val dialog = AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_choose_type, getString(categoryLabelRes(current))))
        .setView(root)
        .setNegativeButton(R.string.btn_cancel, null)
        .create()

    lv.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
        adapter?.getItem(position)?.let {
            targetInput.setText(it.fillValue)
            targetInput.setSelection(targetInput.text.length)
        }
        dialog.dismiss()
    }

    btnClear.setOnClickListener { etSearch.setText("") }

    etSearch.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {
            query = s.toString()
            rebuild()
        }

        override fun afterTextChanged(s: Editable?) {}
    })

    // 粗分类切换
    tabs.forEach { (cat, tv) ->
        tv.setOnClickListener {
            if (current == cat) return@setOnClickListener
            current = cat
            currentSub = SUB_ALL
            tabs.forEach { (k, v) -> styleTab(v, k == cat) }
            etSearch.hint = getString(R.string.search_type_hint, getString(categoryLabelRes(cat)))
            dialog.setTitle(
                getString(R.string.title_choose_type, getString(categoryLabelRes(cat)))
            )
            rebuildSubSpinner()
            rebuild()
        }
    }

    // 细分类切换
    spSub.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
            if (suppressSub) return
            val sub = subValues.getOrNull(position) ?: return
            if (sub == currentSub) return
            currentSub = sub
            rebuild()
        }

        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    dialog.show()
    rebuildSubSpinner()
    rebuild()
}

/**
 * 匹配打分：越小越靠前；返回 null 表示不匹配。
 */
private fun rank(e: AutocompleteEntry, q: String): Int? {
    val ns = e.namespace.lowercase(Locale.getDefault())
    val raw = ns.substringAfter(':')
    val nm = e.name.lowercase(Locale.getDefault())
    val id = e.id ?: ""
    return when {
        ns == q || raw == q || (id.isNotEmpty() && id == q) -> 0
        raw.startsWith(q) || ns.startsWith(q) -> 1
        nm == q -> 2
        nm.startsWith(q) -> 3
        ns.contains(q) -> 4
        nm.contains(q) -> 5
        id.isNotEmpty() && id.startsWith(q) -> 6
        else -> null
    }
}

fun detectAutocompleteType(parent: String?, grandParent: String?): String {
    if (parent == "ActiveEffects" || grandParent == "ActiveEffects"
        || parent == "MobEffects" || parent == "Effects"
    ) return "effect"
    if (parent == "ench" || parent == "Enchantments" || parent == "StoredEnchantments"
        || grandParent == "ench" || grandParent == "Enchantments"
    ) return "enchant"
    return "all"
}

