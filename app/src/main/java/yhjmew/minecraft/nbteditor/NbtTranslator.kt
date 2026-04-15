package yhjmew.minecraft.nbteditor

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NbtTranslator {
    // === 多字典缓存 ===
    private val keyDesc: MutableMap<String?, String?> = HashMap<String?, String?>()
    private val itemDesc: MutableMap<String?, String?> = HashMap<String?, String?>()
    private val enchDesc: MutableMap<String?, String?> = HashMap<String?, String?>()
    private val potionDesc: MutableMap<String?, String?> = HashMap<String?, String?>()
    private val blockDesc: MutableMap<String?, String?> = HashMap<String?, String?>()

    private var isLoaded = false

    // 【核心修复】保存全局 Context，以便静态方法使用 getString
    @SuppressLint("StaticFieldLeak")
    private var mContext: Context? = null

    /** 【新增】重置翻译数据 (在切换语言时调用，清空内存，强制下次 init 重新读取文件)  */
    fun reset() {
        keyDesc.clear()
        itemDesc.clear()
        enchDesc.clear()
        potionDesc.clear()
        blockDesc.clear()
        isLoaded = false // 标记为未加载，下次 init 会重新读取
    }

    fun init(context: Context) {
        if (isLoaded) return
        mContext = context.getApplicationContext() // 保存 Context

        try {
            val gson = Gson()

            // 1. 获取当前语言代码 (zh, en, ja, etc...)
            val lang = context.getResources().getConfiguration().locale.getLanguage()

            // 简单判断：如果 assets 里没有对应的语言文件夹，默认回退到 en (或者你可以保留 zh)
            // 这里假设你只做了 zh 和 en，其他语言默认读 en
            // 如果你确信以后会加其他文件夹，可以直接用 lang
            val folder = if (lang == "zh") "zh/" else "en/"

            // 2. 加载文件 (注意：路径变成了 "zh/key_translation.json")
            loadKeyMap(context, gson, folder + "key_translation.json")

            loadStringMap(context, gson, folder + "item_translation.json", itemDesc)
            loadStringMap(context, gson, folder + "block_translation.json", itemDesc)
            loadStringMap(context, gson, folder + "blockID_translation.json", blockDesc)

            loadIntMap(context, gson, folder + "ench_translation.json", enchDesc)
            loadIntMap(context, gson, folder + "potion_translation.json", potionDesc)

            // 补丁：使用内部辅助方法 getString
            if (!keyDesc.containsKey("Time")) keyDesc.put("Time", getString(R.string.key_game_time))
            if (!keyDesc.containsKey("Pos")) keyDesc.put(
                "Pos",
                getString(R.string.key_coordinate)
            ) // 简单补一个


            isLoaded = true
        } catch (e: Exception) {
            Log.w("NbtTranslator", "Translation load failed", e)
        }
    }

    // 【新增】静态辅助方法，供本类和其他类调用资源字符串
    @JvmStatic
    fun getString(resId: Int, vararg formatArgs: Any?): String {
        if (mContext == null) return ""
        return mContext!!.getString(resId, *formatArgs)
    }

    private fun loadKeyMap(ctx: Context, gson: Gson, file: String) {
        try {
            val `is` = ctx.getAssets().open(file)
            val list: MutableList<JsonKeyItem> = gson.fromJson<MutableList<JsonKeyItem>>(
                InputStreamReader(`is`),
                object : TypeToken<MutableList<JsonKeyItem?>?>() {}.getType()
            )
            for (item in list) if (item.name != null) keyDesc.put(item.name, item.brief)
        } catch (e: Exception) {
            Log.w(ContentValues.TAG, "Failed to load translation file: " + file, e)
        }
    }

    private fun loadStringMap(
        ctx: Context,
        gson: Gson,
        file: String,
        map: MutableMap<String?, String?>
    ) {
        try {
            val `is` = ctx.getAssets().open(file)
            val list: MutableList<JsonItem> = gson.fromJson<MutableList<JsonItem>>(
                InputStreamReader(`is`),
                object : TypeToken<MutableList<JsonItem?>?>() {}.getType()
            )
            for (item in list) {
                if (item.namespace != null) map.put(item.namespace, item.name)
            }
        } catch (e: Exception) {
            Log.w(ContentValues.TAG, "Failed to load translation file: " + file, e)
        }
    }

    private fun loadIntMap(
        ctx: Context,
        gson: Gson,
        file: String,
        map: MutableMap<String?, String?>
    ) {
        try {
            val `is` = ctx.getAssets().open(file)
            val list: MutableList<JsonItem> = gson.fromJson<MutableList<JsonItem>>(
                InputStreamReader(`is`),
                object : TypeToken<MutableList<JsonItem?>?>() {}.getType()
            )
            for (item in list) {
                map.put(item.id.toString(), item.name)
                if (item.namespace != null) map.put(item.namespace, item.name)
            }
        } catch (e: Exception) {
            Log.w(ContentValues.TAG, "Failed to load translation file: " + file, e)
        }
    }

    // === 查询接口 ===
    @JvmStatic
    fun getTranslation(key: String?): String? {
        return if (isLoaded) keyDesc.get(key) else null
    }

    @JvmStatic
    fun getItemTranslation(rawId: String?): String? {
        if (!isLoaded || rawId == null) return null
        val cleanId = rawId.replace("\"", "").trim { it <= ' ' }
        val res = itemDesc.get(cleanId)
        if (res != null) return res
        if (cleanId.startsWith("minecraft:")) return itemDesc.get(cleanId.substring(10))
        return null
    }

    @JvmStatic
    fun getEnchantTranslation(idVal: String?): String? {
        if (!isLoaded) return null
        return enchDesc.get(idVal)
    }

    @JvmStatic
    fun getPotionTranslation(idVal: String?): String? {
        if (!isLoaded) return null
        return potionDesc.get(idVal)
    }

    /** 超强数值语义解析  */
    @JvmStatic
    fun parseValue(key: String?, `val`: String?): String? {
        if (key == null || `val` == null) return null
        val cleanVal = `val`.replace("[^0-9\\-.]".toRegex(), "")
        if (cleanVal.isEmpty()) return null

        // 【关键修复】所有 getString 调用改为调用本类的静态方法
        try {
            if (key == "Difficulty") {
                val v = toInt(cleanVal)
                when (v) {
                    0 -> return getString(R.string.key_difficulty_peace)
                    1 -> return getString(R.string.key_difficulty_simple)
                    2 -> return getString(R.string.key_difficulty_ordinary)
                    3 -> return getString(R.string.key_difficulty_difficulty)
                }
            }

            if (key == "GameType" || key == "ForceGameType" || key == "PlayerGameMode") {
                val v = toInt(cleanVal)
                when (v) {
                    0 -> return getString(R.string.key_game_mode_survive)
                    1 -> return getString(R.string.key_game_mode_create)
                    2 -> return getString(R.string.key_game_mode_adventure)
                    3 -> return getString(R.string.key_game_mode_watch)
                    5 -> return getString(R.string.key_game_mode_default)
                }
            }

            if (key == "Dimension" || key == "DimensionId" || key == "SpawnDimension") {
                val v = toInt(cleanVal)
                when (v) {
                    0 -> return getString(R.string.key_dimension_main_world)
                    1 -> return getString(R.string.key_dimension_nether)
                    2 -> return getString(R.string.key_dimension_end)
                }
            }

            if (key == "Platform") {
                val v = toInt(cleanVal)
                return if (v == 2) "2 (Android/Bedrock)" else `val`
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
            if (key == "itemType") {
                val v = toInt(cleanVal)
                when (v) {
                    1 -> return "1: Byte"
                    2 -> return "2: Short"
                    3 -> return "3: Int"
                    4 -> return "4: Long"
                    5 -> return "5: Float"
                    6 -> return "6: Double"
                    8 -> return "8: String"
                    9 -> return "9: List"
                    10 -> return "10: Compound"
                }
            }

            if (key == "Time" || key == "DayTime" || key == "TimeSinceRest") {
                val tick = cleanVal.toLong()
                val days = tick.toFloat() / 24000.0f
                // String.format 会自动使用 Locale
                return String.format(
                    Locale.getDefault(),
                    getString(R.string.key_time_tick_calculation),
                    days
                )
            }

            if (key == "LastPlayed") {
                var t = cleanVal.toLong()
                if (t > 1000000000000L) t = t / 1000
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                return sdf.format(Date(t * 1000L))
            }

            if (key == "rainTime" || key == "lightningTime") {
                val tick = cleanVal.toLong()
                return String.format(
                    Locale.getDefault(),
                    getString(R.string.key_thunderstorm_rainfall_mtimes),
                    tick / 20,
                    tick / 1200.0f
                )
            }

            if (key == "Generator" || key == "GeneratorType") {
                val v = toInt(cleanVal)
                when (v) {
                    0 -> return getString(R.string.key_world_generator_type_limited)
                    1 -> return getString(R.string.key_world_generator_type_unlimited)
                    2 -> return getString(R.string.key_world_generator_type_flat)
                    3 -> return getString(R.string.key_world_generator_type_nether)
                    4 -> return getString(R.string.key_world_generator_type_end)
                    5 -> return getString(R.string.key_world_generator_type_void)
                    else -> return v.toString() + getString(R.string.key_world_generator_type_unknown)
                }
            }

            if (isBooleanKey(key)) {
                if (cleanVal == "1") return getString(R.string.key_boolean_logic_turn_on)
                if (cleanVal == "0") return getString(R.string.key_boolean_logic_closure)
            }
        } catch (e: Exception) {
            Log.w(ContentValues.TAG, "Boolean parsing failed for key: " + key, e)
        }
        return null
    }

    @JvmStatic
    fun getEmojiIcon(key: String?): String? {
        if (key == null) return null
        when (key) {
            "Time", "DayTime" -> return "⏰"
            "rainTime", "rainLevel" -> return "🌧️"
            "lightningTime", "lightningLevel" -> return "⚡"
            "LastPlayed" -> return "📅"
            "Pos", "SpawnX", "SpawnY", "SpawnZ" -> return "📍"
            "DimensionId" -> return "🌌"
            "Rotation" -> return "🧭"
            "Motion" -> return "💨"
            "Health", "HealF" -> return "❤️"
            "Air" -> return "🫧"
            "Fire" -> return "🔥"
            "FoodLevel" -> return "🍗"
            "Score", "XpLevel", "PlayerLevel" -> return "✨"
            "Sleeping", "SleepTimer" -> return "🛏️"
            "EnderChestInventory" -> return "🟪"
            "Inventory" -> return "🎒"
            "Armor" -> return "🛡️"
            "LevelName" -> return "🏷️"
            "RandomSeed" -> return "🌱"
            "GameType", "Difficulty" -> return "🎮"
            "colors" -> return "🎨"
            else -> return null
        }
    }

    private fun isBooleanKey(key: String): Boolean {
        if (key.startsWith("is") || key.startsWith("Is")) return true
        if (key.startsWith("do") || key.startsWith("Do")) return true
        if (key.startsWith("has") || key.startsWith("Has")) return true
        if (key.startsWith("can") || key.startsWith("Can")) return true
        if (key.startsWith("allow") || key.startsWith("Allow")) return true
        if (key.contains("Enabled")) return true

        return key == "pvp" || key == "mobgriefing" || key == "keepinventory" ||
                key == "naturalregeneration" || key == "tntexplodes" || key == "respawnblocksexplode" ||
                key == "commandblockoutput" || key == "sendcommandfeedback" ||
                key == "recipesunlock" || key == "immutableWorld" ||
                key == "OnGround" || key == "Invulnerable" || key == "Sleeping" ||
                key == "Saddled" || key == "Sheared" || key == "Sitting" ||
                key == "Chested" || key == "ShowBottom" || key == "LootDropped" ||
                key == "WasPickedUp" || key == "Dead" || key == "MultiplayerGame" ||
                key == "LANBroadcast"
    }

    private fun toInt(`val`: String): Int {
        if (`val`.isEmpty()) return 0
        if (`val`.contains(".")) return `val`.toDouble().toInt()
        return `val`.toInt()
    }

    // 通用 JSON 结构
    private class JsonItem {
        var name: String? = null
        var namespace: String? = null
        var id: Int = 0
    }

    private class JsonKeyItem {
        var name: String? = null
        var brief: String? = null
    }
}