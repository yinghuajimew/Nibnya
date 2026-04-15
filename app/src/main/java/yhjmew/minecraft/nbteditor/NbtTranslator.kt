package yhjmew.minecraft.nbteditor;

import static android.content.ContentValues.TAG;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

public class NbtTranslator {

    // === 多字典缓存 ===
    private static final Map<String, String> keyDesc = new HashMap<>();
    private static final Map<String, String> itemDesc = new HashMap<>();
    private static final Map<String, String> enchDesc = new HashMap<>();
    private static final Map<String, String> potionDesc = new HashMap<>();
    private static final Map<String, String> blockDesc = new HashMap<>();

    private static boolean isLoaded = false;

    // 【核心修复】保存全局 Context，以便静态方法使用 getString
    @SuppressLint("StaticFieldLeak")
    private static Context mContext;

    // 通用 JSON 结构
    private static class JsonItem {
        String name;
        String namespace;
        int id;
    }

    private static class JsonKeyItem {
        String name;
        String brief;
    }

/** 【新增】重置翻译数据 (在切换语言时调用，清空内存，强制下次 init 重新读取文件) */
    public static void reset() {
        keyDesc.clear();
        itemDesc.clear();
        enchDesc.clear();
        potionDesc.clear();
        blockDesc.clear();
        isLoaded = false; // 标记为未加载，下次 init 会重新读取
    }

    public static void init(Context context) {
        if (isLoaded) return;
        mContext = context.getApplicationContext(); // 保存 Context

        try {
            Gson gson = new Gson();

            // 1. 获取当前语言代码 (zh, en, ja, etc...)
            String lang = context.getResources().getConfiguration().locale.getLanguage();

            // 简单判断：如果 assets 里没有对应的语言文件夹，默认回退到 en (或者你可以保留 zh)
            // 这里假设你只做了 zh 和 en，其他语言默认读 en
            // 如果你确信以后会加其他文件夹，可以直接用 lang
            String folder = lang.equals("zh") ? "zh/" : "en/";

            // 2. 加载文件 (注意：路径变成了 "zh/key_translation.json")
            loadKeyMap(context, gson, folder + "key_translation.json");

            loadStringMap(context, gson, folder + "item_translation.json", itemDesc);
            loadStringMap(context, gson, folder + "block_translation.json", itemDesc);
            loadStringMap(context, gson, folder + "blockID_translation.json", blockDesc);

            loadIntMap(context, gson, folder + "ench_translation.json", enchDesc);
            loadIntMap(context, gson, folder + "potion_translation.json", potionDesc);

            // 补丁：使用内部辅助方法 getString
            if (!keyDesc.containsKey("Time"))
                keyDesc.put("Time", getString(R.string.key_game_time));
            if (!keyDesc.containsKey("Pos"))
                keyDesc.put("Pos", getString(R.string.key_coordinate)); // 简单补一个

            isLoaded = true;
        } catch (Exception e) {
            Log.w("NbtTranslator", "Translation load failed", e);
        }
    }

    // 【新增】静态辅助方法，供本类和其他类调用资源字符串
    public static String getString(int resId, Object... formatArgs) {
        if (mContext == null) return "";
        return mContext.getString(resId, formatArgs);
    }

    private static void loadKeyMap(Context ctx, Gson gson, String file) {
        try {
            InputStream is = ctx.getAssets().open(file);
            List<JsonKeyItem> list = gson.fromJson(new InputStreamReader(is), new TypeToken<
                    List<JsonKeyItem>>() {}.getType());
            for (JsonKeyItem item : list) if (item.name != null) NbtTranslator.keyDesc.put(item.name, item.brief);
        } catch (Exception e) {
            Log.w(TAG, "Failed to load translation file: " + file, e);
        }
    }

    private static void loadStringMap(Context ctx, Gson gson, String file, Map<
                    String, String> map) {
        try {
            InputStream is = ctx.getAssets().open(file);
            List<JsonItem> list = gson.fromJson(new InputStreamReader(is), new TypeToken<
                    List<JsonItem>>() {}.getType());
            for (JsonItem item : list) {
                if (item.namespace != null) map.put(item.namespace, item.name);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load translation file: " + file, e);
        }
    }

    private static void loadIntMap(Context ctx, Gson gson, String file, Map<String, String> map) {
        try {
            InputStream is = ctx.getAssets().open(file);
            List<JsonItem> list = gson.fromJson(new InputStreamReader(is), new TypeToken<
                    List<JsonItem>>() {}.getType());
            for (JsonItem item : list) {
                map.put(String.valueOf(item.id), item.name);
                if (item.namespace != null) map.put(item.namespace, item.name);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load translation file: " + file, e);
        }
    }

    // === 查询接口 ===

    public static String getTranslation(String key) {
        return isLoaded ? keyDesc.get(key) : null;
    }

    public static String getItemTranslation(String rawId) {
        if (!isLoaded || rawId == null) return null;
        String cleanId = rawId.replace("\"", "").trim();
        String res = itemDesc.get(cleanId);
        if (res != null) return res;
        if (cleanId.startsWith("minecraft:")) return itemDesc.get(cleanId.substring(10));
        return null;
    }

    public static String getEnchantTranslation(String idVal) {
        if (!isLoaded) return null;
        return enchDesc.get(idVal);
    }

    public static String getPotionTranslation(String idVal) {
        if (!isLoaded) return null;
        return potionDesc.get(idVal);
    }

    /** 超强数值语义解析 */
    public static String parseValue(String key, String val) {
        if (key == null || val == null) return null;
        String cleanVal = val.replaceAll("[^0-9\\-.]", "");
        if (cleanVal.isEmpty()) return null;

        // 【关键修复】所有 getString 调用改为调用本类的静态方法
        try {
            if (key.equals("Difficulty")) {
                int v = toInt(cleanVal);
                switch (v) {
                    case 0:
                        return getString(R.string.key_difficulty_peace);
                    case 1:
                        return getString(R.string.key_difficulty_simple);
                    case 2:
                        return getString(R.string.key_difficulty_ordinary);
                    case 3:
                        return getString(R.string.key_difficulty_difficulty);
                }
            }

            if (key.equals("GameType") || key.equals("ForceGameType") || key.equals("PlayerGameMode")) {
                int v = toInt(cleanVal);
                switch (v) {
                    case 0:
                        return getString(R.string.key_game_mode_survive);
                    case 1:
                        return getString(R.string.key_game_mode_create);
                    case 2:
                        return getString(R.string.key_game_mode_adventure);
                    case 3:
                        return getString(R.string.key_game_mode_watch);
                    case 5:
                        return getString(R.string.key_game_mode_default);
                }
            }

            if (key.equals("Dimension") || key.equals("DimensionId") || key.equals("SpawnDimension")) {
                int v = toInt(cleanVal);
                switch (v) {
                    case 0:
                        return getString(R.string.key_dimension_main_world);
                    case 1:
                        return getString(R.string.key_dimension_nether);
                    case 2:
                        return getString(R.string.key_dimension_end);
                }
            }

            if (key.equals("Platform")) {
                int v = toInt(cleanVal);
                return (v == 2) ? "2 (Android/Bedrock)" : val;
            }

// 为何注释掉？在百科与这份key ID当中并不存在这东西，之后会进行修补测试
            // 权限等级 (permissionsLevel)
            // if (key.equals("permissionsLevel") || key.equals("playerPermissionsLevel")) {
            // int v = toInt(cleanVal);
            // switch (v) {
            // case 0:
            // return "0: 游客 (Visitor)";
            // case 1:
            // return "1: 成员 (Member)";
            // case 2:
            // return "2: 管理员 (Operator)";
            // case 3:
            // return "3: 自定义";
            // }
            // }

            if (key.equals("itemType")) {
                int v = toInt(cleanVal);
                switch (v) {
                    case 1:
                        return "1: Byte";
                    case 2:
                        return "2: Short";
                    case 3:
                        return "3: Int";
                    case 4:
                        return "4: Long";
                    case 5:
                        return "5: Float";
                    case 6:
                        return "6: Double";
                    case 8:
                        return "8: String";
                    case 9:
                        return "9: List";
                    case 10:
                        return "10: Compound";
                }
            }

            if (key.equals("Time") || key.equals("DayTime") || key.equals("TimeSinceRest")) {
                long tick = Long.parseLong(cleanVal);
                float days = (float) tick / 24000.0f;
                // String.format 会自动使用 Locale
                return String.format(Locale.getDefault(), getString(R.string.key_time_tick_calculation), days);
            }

            if (key.equals("LastPlayed")) {
                long t = Long.parseLong(cleanVal);
                if (t > 1000000000000L) t = t / 1000;
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
                return sdf.format(new java.util.Date(t * 1000L));
            }

            if (key.equals("rainTime") || key.equals("lightningTime")) {
                long tick = Long.parseLong(cleanVal);
                return String.format(Locale.getDefault(), getString(R.string.key_thunderstorm_rainfall_mtimes), tick / 20, tick / 1200.0f);
            }

            if (key.equals("Generator") || key.equals("GeneratorType")) {
                int v = toInt(cleanVal);
                switch (v) {
                    case 0:
                        return getString(R.string.key_world_generator_type_limited);
                    case 1:
                        return getString(R.string.key_world_generator_type_unlimited);
                    case 2:
                        return getString(R.string.key_world_generator_type_flat);
                    case 3:
                        return getString(R.string.key_world_generator_type_nether);
                    case 4:
                        return getString(R.string.key_world_generator_type_end);
                    case 5:
                        return getString(R.string.key_world_generator_type_void);
                    default:
                        return v + getString(R.string.key_world_generator_type_unknown);
                }
            }

            if (isBooleanKey(key)) {
                if (cleanVal.equals("1")) return getString(R.string.key_boolean_logic_turn_on);
                if (cleanVal.equals("0")) return getString(R.string.key_boolean_logic_closure);
            }

        } catch (Exception e) {
            Log.w(TAG, "Boolean parsing failed for key: " + key, e);
        }
        return null;
    }

    public static String getEmojiIcon(String key) {
        if (key == null) return null;
        switch (key) {
            case "Time":
            case "DayTime":
                return "⏰";
            case "rainTime":
            case "rainLevel":
                return "🌧️";
            case "lightningTime":
            case "lightningLevel":
                return "⚡";
            case "LastPlayed":
                return "📅";
            case "Pos":
            case "SpawnX":
            case "SpawnY":
            case "SpawnZ":
                return "📍";
            case "DimensionId":
                return "🌌";
            case "Rotation":
                return "🧭";
            case "Motion":
                return "💨";
            case "Health":
            case "HealF":
                return "❤️";
            case "Air":
                return "🫧";
            case "Fire":
                return "🔥";
            case "FoodLevel":
                return "🍗";
            case "Score":
            case "XpLevel":
            case "PlayerLevel":
                return "✨";
            case "Sleeping":
            case "SleepTimer":
                return "🛏️";
            case "EnderChestInventory":
                return "🟪";
            case "Inventory":
                return "🎒";
            case "Armor":
                return "🛡️";
            case "LevelName":
                return "🏷️";
            case "RandomSeed":
                return "🌱";
            case "GameType":
            case "Difficulty":
                return "🎮";
            case "colors":
                return "🎨";
            default:
                return null;
        }
    }

    private static boolean isBooleanKey(String key) {
        if (key.startsWith("is") || key.startsWith("Is")) return true;
        if (key.startsWith("do") || key.startsWith("Do")) return true;
        if (key.startsWith("has") || key.startsWith("Has")) return true;
        if (key.startsWith("can") || key.startsWith("Can")) return true;
        if (key.startsWith("allow") || key.startsWith("Allow")) return true;
        if (key.contains("Enabled")) return true;

        return key.equals("pvp") || key.equals("mobgriefing") || key.equals("keepinventory") ||
                key.equals("naturalregeneration") || key.equals("tntexplodes") || key.equals("respawnblocksexplode") ||
                key.equals("commandblockoutput") || key.equals("sendcommandfeedback") ||
                key.equals("recipesunlock") || key.equals("immutableWorld") ||
                key.equals("OnGround") || key.equals("Invulnerable") || key.equals("Sleeping") ||
                key.equals("Saddled") || key.equals("Sheared") || key.equals("Sitting") ||
                key.equals("Chested") || key.equals("ShowBottom") || key.equals("LootDropped") ||
                key.equals("WasPickedUp") || key.equals("Dead") || key.equals("MultiplayerGame") ||
                key.equals("LANBroadcast");
    }

    private static int toInt(String val) {
        if (val.isEmpty()) return 0;
        if (val.contains(".")) return (int) Double.parseDouble(val);
        return Integer.parseInt(val);
    }
}