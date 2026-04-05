package yhjmew.minecraft.nbteditor;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NbtTreeAdapter extends BaseAdapter {

    // 四种显示模式常量
    public static final int MODE_RAW = 0;
    public static final int MODE_TRANS = 1;
    public static final int MODE_SMART = 2;
    public static final int MODE_SIMPLE = 3;

    private int currentMode = MODE_RAW;

    // 内部节点类
    public static class Node {
        String key;
        JsonElement value;
        int type;
        int level; // 缩进层级
        boolean isExpanded;
        Node parent;

        public Node(String k, JsonElement v, int l, Node p) {
            key = k;
            value = v;
            level = l;
            parent = p;
            isExpanded = false;

            if (v != null && v.isJsonObject() && v.getAsJsonObject().has("t")) {
                type = v.getAsJsonObject().get("t").getAsInt();
            } else {
                type = 0;
            }
        }
    }

    private Context context;
    private Node rootNode;
    private List<Node> visibleNodes;

    public NbtTreeAdapter(Context context, JsonObject rootData) {
        this.context = context;
        this.rootNode = new Node("ROOT", null, -1, null);
        this.rootNode.isExpanded = true;

        this.visibleNodes = new ArrayList<>();
        if (rootData != null) {
            buildChildren(rootNode, rootData);
        }
    }

    private void buildChildren(Node parent, JsonObject container) {
        List<String> keys = new ArrayList<>(container.keySet());
        Collections.sort(keys);

        List<Node> children = new ArrayList<>();
        for (String k : keys) {
            JsonElement v = container.get(k);
            Node child = new Node(k, v, parent.level + 1, parent);
            children.add(child);
        }
        visibleNodes.addAll(children);
    }

    // 展开/折叠核心
    public void toggleExpand(int position) {
        Node node = visibleNodes.get(position);
        if (node.type != 9 && node.type != 10) return;

        if (node.isExpanded) {
            // 折叠
            node.isExpanded = false;
            int i = position + 1;
            while (i < visibleNodes.size()) {
                Node next = visibleNodes.get(i);
                if (next.level > node.level) visibleNodes.remove(i);
                else break;
            }
        } else {
            // 展开
            node.isExpanded = true;
            List<Node> subNodes = new ArrayList<>();
            JsonElement innerV = node.value.getAsJsonObject().get("v");

            if (node.type == 10) { // Compound
                JsonObject jo = innerV.getAsJsonObject();
                List<String> keys = new ArrayList<>(jo.keySet());
                Collections.sort(keys);
                for (String k : keys) subNodes.add(new Node(k, jo.get(k), node.level + 1, node));
            } else if (node.type == 9) { // List
                JsonArray ja = innerV.getAsJsonArray();
                int itemType = node.value.getAsJsonObject().get("itemType").getAsInt();
                for (int i = 0; i < ja.size(); i++) {
                    JsonObject wrapper = new JsonObject();
                    wrapper.addProperty("t", itemType);
                    wrapper.add("v", ja.get(i));
                    subNodes.add(new Node(String.valueOf(i), wrapper, node.level + 1, node));
                }
            }
            visibleNodes.addAll(position + 1, subNodes);
        }
        notifyDataSetChanged();
    }

    public boolean isContainer(int position) {
        int t = visibleNodes.get(position).type;
        return t == 9 || t == 10;
    }

    public Node getNode(int position) {
        return visibleNodes.get(position);
    }

    public void setViewMode(int mode) {
        this.currentMode = mode;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return visibleNodes.size();
    }

    @Override
    public Object getItem(int position) {
        return visibleNodes.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_nbt_tree, parent, false);
        }

        Node node = visibleNodes.get(position);

        View vIndent = convertView.findViewById(R.id.view_indentation);
        TextView tvExpand = convertView.findViewById(R.id.tv_expand_icon);
        TextView tvIcon = convertView.findViewById(R.id.tv_type_icon);
        TextView tvKey = convertView.findViewById(R.id.tv_key);
        TextView tvValue = convertView.findViewById(R.id.tv_value);
        TextView tvDesc = convertView.findViewById(R.id.tv_key_desc);

        // 设置缩进
        ViewGroup.LayoutParams lp = vIndent.getLayoutParams();
        lp.width = (int) (node.level * 24 * context.getResources().getDisplayMetrics().density);
        vIndent.setLayoutParams(lp);

        // 展开箭头
        if (node.type == 9 || node.type == 10) {
            tvExpand.setVisibility(View.VISIBLE);
            tvExpand.setText(node.isExpanded ? "▼" : "▶");
        } else {
            tvExpand.setVisibility(View.INVISIBLE);
        }

        // Emoji 图标
        String emoji = NbtTranslator.getEmojiIcon(node.key);
        if (emoji != null) {
            tvIcon.setText(emoji);
            tvIcon.setBackgroundColor(Color.TRANSPARENT);
            tvIcon.setTextSize(18);
        } else {
            setupNormalIcon(tvIcon, node.type);
            tvIcon.setTextSize(14);
        }

        // 1. 准备原始数据
        String key = node.key;
        String valRaw = "";
        JsonElement vInner = node.value != null && node.value.isJsonObject() ? node.value.getAsJsonObject().get("v") : null;
        boolean isLargeArray = (node.type == 7 || node.type == 11 || node.type == 12);

        if (vInner != null) {
            if (node.type == 9)
                valRaw = "[List: " + (vInner.isJsonArray() ? vInner.getAsJsonArray().size() : 0) + " items]";
            else if (node.type == 10)
                valRaw = "{Compound: " + (vInner.isJsonObject() ? vInner.getAsJsonObject().size() : 0) + " entries}";
            else if (isLargeArray)
                valRaw = "[Array: " + (vInner.isJsonArray() ? vInner.getAsJsonArray().size() : 0) + " values]";
            else if (node.type == 8) valRaw = vInner.getAsString();
            else valRaw = vInner.toString();
        }

        // 2. 翻译与智能上下文识别 (复用逻辑)
        String trans = NbtTranslator.getTranslation(key); // 这里叫 trans
        String specialTrans = null;

        // (B) 上下文识别
        if ((key.equals("Name") || key.equals("id") || key.equals("name")) && valRaw.contains("minecraft:")) {
            specialTrans = NbtTranslator.getItemTranslation(valRaw);
        } else if (key.equals("id") && node.type == 2) {
            if (node.parent != null) {
                String pKey = node.parent.key;
                String gpKey = (node.parent.parent != null) ? node.parent.parent.key : "";
                if ("ench".equals(pKey) || "Enchantments".equals(pKey) || "ench".equals(gpKey) || "Enchantments".equals(gpKey)) {
                    String name = NbtTranslator.getEnchantTranslation(valRaw);
                    if (name != null) specialTrans = context.getString(R.string.msg_enchant) + name;
                }
            }
        } else if (key.equals("Id") && node.type == 1) {
            if (node.parent != null) {
                String pKey = node.parent.key;
                String gpKey = (node.parent.parent != null) ? node.parent.parent.key : "";
                if ("ActiveEffects".equals(pKey) || "ActiveEffects".equals(gpKey)) {
                    String name = NbtTranslator.getPotionTranslation(valRaw);
                    if (name != null) specialTrans = context.getString(R.string.msg_potion) + name;
                }
            }
        }

        if (specialTrans != null) {
            trans = specialTrans;
        }

        // (D) 数值智能解析
        String smartVal = null;
        if (node.type != 9 && node.type != 10 && vInner != null && (currentMode == MODE_SMART || currentMode == MODE_SIMPLE)) {
            smartVal = NbtTranslator.parseValue(key, vInner.toString());
        }

        // === 3. UI 渲染 (已修复变量名) ===

        // Key 显示
        // 这里删掉了长标题判定，极简模式直接显示 trans
        if (currentMode == MODE_SIMPLE && trans != null) {
            tvKey.setText(trans); // 使用 trans
            tvKey.setTextColor(Color.parseColor("#009688"));
        } else {
            tvKey.setText(key);
            tvKey.setTextColor(Color.parseColor("#333333"));
        }

        // Value 显示
        if (smartVal != null) {
            tvValue.setText(smartVal);
            tvValue.setTextColor(Color.parseColor("#E91E63"));
        } else {
            tvValue.setText(valRaw);
            tvValue.setTextColor(Color.parseColor("#666666"));
        }

        // Desc 显示
        boolean showDesc = false; // 【修复】这里定义了 showDesc
        StringBuilder sbDesc = new StringBuilder();

        if ((currentMode == MODE_TRANS || currentMode == MODE_SMART) && trans != null) {
            sbDesc.append(trans); // 使用 trans
            showDesc = true;
        }
        // 极简模式下不再重复显示 Desc，除非你想特殊处理

        // 智能值追加 (根据你刚才的要求，这里已经删掉了重复显示 smartVal 的逻辑)

        if (showDesc) {
            tvDesc.setVisibility(View.VISIBLE);
            tvDesc.setText(sbDesc.toString());
        } else {
            tvDesc.setVisibility(View.GONE);
        }

        return convertView;
    }

    private void setupNormalIcon(TextView icon, int type) {
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

// === 【新增】根据路径自动展开树并定位 ===
    public int expandPath(java.util.Stack<String> pathStack) {
        if (pathStack == null || pathStack.isEmpty()) return 0;

        // 1. 复制路径 (以免影响原栈)
        List<String> targetPath = new ArrayList<>(pathStack);

        int currentSearchIndex = 0; // 当前在 visibleNodes 里的搜索起始位置
        int finalPosition = 0; // 最终目标位置

        // 2. 逐层寻找并展开
        for (String targetKey : targetPath) {
            boolean found = false;

            // 在当前可见列表中寻找匹配的 Key
            // 注意：因为列表是动态增长的，我们只需要从上一次找到的位置往下找
            for (int i = currentSearchIndex; i < visibleNodes.size(); i++) {
                Node node = visibleNodes.get(i);

                // 找到匹配的 Key
                if (node.key.equals(targetKey)) {
                    // 如果它是个容器且还没展开，就展开它
                    if ((node.type == 9 || node.type == 10) && !node.isExpanded) {
                        toggleExpand(i); // 这会改变 visibleNodes 的大小
                    }

                    // 记录位置，准备找下一层
                    currentSearchIndex = i + 1; // 下一层肯定在当前节点下面
                    finalPosition = i;
                    found = true;
                    break;
                }

                // 优化：如果缩进层级已经小于目标层级，说明找过头了（跑到别的分支了）
                // 但由于 visibleNodes 是扁平的，这里简单处理，遍历即可
            }

            // 如果某一层没找到（比如数据变了），就停在这一层
            if (!found) break;
        }

        return finalPosition;
    }

// === 【新增】获取指定位置节点的完整路径链 ===
    public List<String> getNodePath(int position) {
        if (position < 0 || position >= visibleNodes.size()) return new ArrayList<>();

        Node node = visibleNodes.get(position);
        List<String> path = new ArrayList<>();

        // 如果当前点击的是“值”（不是容器），我们应该定位到它的【父容器】
        // 这样切回列表时，能看到这个值
        if (node.type != 9 && node.type != 10) {
            node = node.parent;
        }

        // 向上回溯直到根节点
        while (node != null && node.parent != null) { // parent != null 排除虚拟ROOT
            path.add(node.key);
            node = node.parent;
        }

        // 回溯出来是反的（tag, 0, Inventory），需要反转回（Inventory, 0, tag）
        Collections.reverse(path);
        return path;
    }

// === 【新增】获取指定位置节点的父容器（用于编辑操作）===
public JsonObject getParentContainer(int position) {
    if (position < 0 || position >= visibleNodes.size()) return null;
    
    Node node = visibleNodes.get(position);
    
    // 如果是根节点，没有父容器
    if (node.parent == null || node.parent.value == null) {
        return null;
    }
    
    // 获取父节点的 value（应该是 Compound 类型）
    JsonElement parentValue = node.parent.value;
    if (parentValue != null && parentValue.isJsonObject()) {
        JsonObject parentObj = parentValue.getAsJsonObject();
        // 解包装获取实际的 Compound 内容
        if (parentObj.has("v") && parentObj.get("v").isJsonObject()) {
            return parentObj.getAsJsonObject("v");
        }
    }
    return null;
}

// === 【新增】获取节点自身的 key（用于重命名/删除）===
public String getNodeKey(int position) {
    if (position < 0 || position >= visibleNodes.size()) return null;
    return visibleNodes.get(position).key;
}

// === 【新增】获取节点自身的完整数据（用于复制）===
public JsonObject getNodeData(int position) {
    if (position < 0 || position >= visibleNodes.size()) return null;
    Node node = visibleNodes.get(position);
    if (node.value != null && node.value.isJsonObject()) {
        return node.value.getAsJsonObject();
    }
    return null;
}

}