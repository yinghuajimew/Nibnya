package yhjmew.minecraft.nbteditor;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NbtAdapter extends BaseAdapter {

    public static final int MODE_RAW = 0;
    public static final int MODE_TRANS = 1;
    public static final int MODE_SMART = 2;
    public static final int MODE_SIMPLE = 3;

    private Context context;
    private JsonObject rootJson;

    // 【核心】我们需要两个列表：一个存原始所有Key，一个存当前显示的Key
    private List<String> originalKeys; // 原始全量数据
    private List<String> displayKeys; // 过滤后显示的数据

    private int currentMode = MODE_RAW;

    public NbtAdapter(Context context, JsonObject json) {
        this.context = context;
        this.rootJson = json;
        refreshKeys();
    }

    private String currentParentKey = ""; // 当前所在的父文件夹名
    private String currentGrandParentKey = ""; // 爷爷文件夹名 (用于判断 list -> index -> content)

    public void setPathContext(String parent, String grandParent) {
        this.currentParentKey = parent;
        this.currentGrandParentKey = grandParent;
        notifyDataSetChanged();
    }

    public void refreshKeys() {
        if (rootJson != null) {
            // 1. 获取所有 Key
            this.originalKeys = new ArrayList<>(rootJson.keySet());
            Collections.sort(originalKeys);
        } else {
            this.originalKeys = new ArrayList<>();
        }
        // 2. 默认显示所有
        this.displayKeys = new ArrayList<>(originalKeys);
        notifyDataSetChanged();
    }

    // 【新增】搜索过滤功能
    public void filter(String query) {
        if (query == null || query.trim().isEmpty()) {
            // 如果搜索词为空，恢复显示所有
            this.displayKeys = new ArrayList<>(originalKeys);
        } else {
            // 开始过滤
            String lowerQuery = query.toLowerCase().trim();
            List<String> filtered = new ArrayList<>();

            for (String key : originalKeys) {
                // 1. 匹配 Key (英文)
                boolean matchKey = key.toLowerCase().contains(lowerQuery);

                // 2. 匹配 翻译 (中文)
                String trans = NbtTranslator.getTranslation(key);
                boolean matchTrans = (trans != null && trans.toLowerCase().contains(lowerQuery));

                // 只要有一个匹配，就加入列表
                if (matchKey || matchTrans) {
                    filtered.add(key);
                }
            }
            this.displayKeys = filtered;
        }
        notifyDataSetChanged();
    }

    public void setViewMode(int mode) {
        this.currentMode = mode;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return (displayKeys == null) ? 0 : displayKeys.size();
    }

    @Override
    public Object getItem(int position) {
        return rootJson.get(displayKeys.get(position));
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    public String getKey(int position) {
        return displayKeys.get(position);
    }

    public JsonObject getData() {
        return rootJson;
    }

    public String getJsonString() {
        return rootJson != null ? rootJson.toString() : "{}";
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_nbt, parent, false);
        }

        TextView icon = convertView.findViewById(R.id.tv_type_icon);
        TextView tvKey = convertView.findViewById(R.id.tv_key);
        TextView tvKeyDesc = convertView.findViewById(R.id.tv_key_desc);
        TextView tvValue = convertView.findViewById(R.id.tv_value);

        String key = displayKeys.get(position);
        JsonObject itemData = rootJson.getAsJsonObject(key);

        int type = 0;
        if (itemData.has("t")) type = itemData.get("t").getAsInt();

        setupIcon(icon, type);

        // === 1. 准备原始值 (Raw Value) ===
        // 必须最先做这一步，因为后面判断物品ID需要用到它
        String rawVal = "";
        JsonElement v = itemData.has("v") ? itemData.get("v") : null;

        if (v != null) {
            if (type == 9)
                rawVal = "[List: " + (v.isJsonArray() ? v.getAsJsonArray().size() : 0) + "]";
            else if (type == 10)
                rawVal = "{Compound: " + (v.isJsonObject() ? v.getAsJsonObject().size() : 0) + "}";
            else if (type == 7 || type == 11 || type == 12)
                rawVal = "[Array: " + (v.isJsonArray() ? v.getAsJsonArray().size() : 0) + "]";
            else if (type == 8) {
                String s = v.getAsString();
                rawVal = "\"" + (s.length() > 50 ? s.substring(0, 50) + "..." : s) + "\"";
            } else rawVal = v.toString();
        }

        // === 2. 翻译与智能上下文识别 (统一逻辑) ===

        // (A) 基础翻译
        String translation = NbtTranslator.getTranslation(key);
        String specialTrans = null;

        // (B) 上下文识别 (依靠 currentGrandParentKey)
        // 1. 物品 ID (Key=Name, Value="minecraft:...")
        if ((key.equals("Name") || key.equals("id") || key.equals("name")) && rawVal.contains("minecraft:")) {
            specialTrans = NbtTranslator.getItemTranslation(rawVal);
        }

        // 2. 附魔 ID (Key=id, 父级=ench)
        else if (key.equals("id") && type == 2) { // Short
            // 列表模式下，结构通常是 ench -> 0 -> id，所以检查爷爷节点
            if ("ench".equals(currentGrandParentKey) || "Enchantments".equals(currentGrandParentKey)) {
                String name = NbtTranslator.getEnchantTranslation(rawVal);
                if (name != null) specialTrans = context.getString(R.string.msg_enchant) + name;
            }
        }

        // 3. 药水 ID (Key=Id, 父级=ActiveEffects)
        else if (key.equals("Id") && type == 1) { // Byte
            if ("ActiveEffects".equals(currentGrandParentKey)) {
                String name = NbtTranslator.getPotionTranslation(rawVal);
                if (name != null) specialTrans = context.getString(R.string.msg_potion) + name;
            }
        }

        // (C) 应用特殊翻译 (覆盖基础翻译)
        if (specialTrans != null) {
            translation = specialTrans;
        }

        // (D) 数值智能解析 (Smart Value)
        String parsedVal = null;
        if (currentMode == MODE_SMART || currentMode == MODE_SIMPLE) {
            // 只有非容器类型才解析
            if (type != 9 && type != 10 && type != 7 && type != 11 && type != 12) {
                parsedVal = NbtTranslator.parseValue(key, v != null ? v.toString() : "");
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
            tvKey.setText(translation);
            tvKey.setTextColor(Color.parseColor("#009688"));

            // 如果你觉得英文太长显示不全，可以去 XML 里把 tvKey 的 singleLine="true" 改成 false
            // 或者 maxLines="2"

        } else {
            tvKey.setText(key);
            tvKey.setTextColor(Color.parseColor("#212121"));
        }

        // Value 显示
        if (parsedVal != null) {
            tvValue.setText(parsedVal);
            tvValue.setTextColor(Color.parseColor("#E91E63"));
        } else {
            tvValue.setText(rawVal);
            tvValue.setTextColor(Color.parseColor("#757575"));
        }

        // Desc (第二行) 显示
        StringBuilder sbDesc = new StringBuilder();
        if ((currentMode == MODE_TRANS || currentMode == MODE_SMART) && translation != null) {
            sbDesc.append(translation);
        }
        // if (currentMode == MODE_SIMPLE && simpleModeLongTitle && translation != null) {
        // sbDesc.append(translation);
        // }
        if (sbDesc.length() > 0) {
            tvKeyDesc.setVisibility(View.VISIBLE);
            tvKeyDesc.setText(sbDesc.toString());
        } else {
            tvKeyDesc.setVisibility(View.GONE);
        }

        return convertView;
    }

    private void setupIcon(TextView icon, int type) {
        icon.setTypeface(null, Typeface.BOLD);
        icon.setTextColor(Color.WHITE);
        switch (type) {
            case 1:
                icon.setText("B");
                icon.setBackgroundColor(Color.parseColor("#B0BEC5"));
                break;
            case 2:
                icon.setText("S");
                icon.setBackgroundColor(Color.parseColor("#90A4AE"));
                break;
            case 3:
                icon.setText("I");
                icon.setBackgroundColor(Color.parseColor("#03A9F4"));
                break;
            case 4:
                icon.setText("L");
                icon.setBackgroundColor(Color.parseColor("#3F51B5"));
                break;
            case 5:
                icon.setText("F");
                icon.setBackgroundColor(Color.parseColor("#E91E63"));
                break;
            case 6:
                icon.setText("D");
                icon.setBackgroundColor(Color.parseColor("#9C27B0"));
                break;
            case 7:
                icon.setText("[B]");
                icon.setBackgroundColor(Color.parseColor("#607D8B"));
                break;
            case 8:
                icon.setText("T");
                icon.setBackgroundColor(Color.parseColor("#4CAF50"));
                break;
            case 9:
                icon.setText("[]");
                icon.setBackgroundColor(Color.parseColor("#FF9800"));
                break;
            case 10:
                icon.setText("{}");
                icon.setBackgroundColor(Color.parseColor("#795548"));
                break;
            case 11:
                icon.setText("[I]");
                icon.setBackgroundColor(Color.parseColor("#00BCD4"));
                break;
            case 12:
                icon.setText("[L]");
                icon.setBackgroundColor(Color.parseColor("#673AB7"));
                break;
            default:
                icon.setText("?");
                icon.setBackgroundColor(Color.GRAY);
        }
    }
}
