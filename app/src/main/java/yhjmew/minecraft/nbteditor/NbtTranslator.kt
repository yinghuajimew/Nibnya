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
    private val keyDesc = mutableMapOf<String, String>()
    private val itemDesc = mutableMapOf<String, String>()
    private val enchDesc = mutableMapOf<String, String>()
    private val potionDesc = mutableMapOf<String, String>()
    private val blockDesc = mutableMapOf<String, String>()

    private var isLoaded = false

    @SuppressLint("StaticFieldLeak")
    private var mContext: Context? = null

    fun reset() {
        keyDesc.clear()
        itemDesc.clear()
        enchDesc.clear()
        potionDesc.clear()
        blockDesc.clear()
        isLoaded = false
    }

    fun init(context: Context) {
        if (isLoaded) return
        mContext = context.applicationContext

        try {
            val gson = Gson()

            val lang = context.resources.configuration.let { config ->
                @Suppress("DEPRECATION")
                config.locale.language
            }

            val folder = if (lang == "zh") "zh/" else "en/"

            loadKeyMap(context, gson, "${folder}key_translation.json")
            loadStringMap(context, gson, "${folder}item_translation.json", itemDesc)
            loadStringMap(context, gson, "${folder}block_translation.json", itemDesc)
            loadStringMap(context, gson, "${folder}blockID_translation.json", blockDesc)
            loadIntMap(context, gson, "${folder}ench_translation.json", enchDesc)
            loadIntMap(context, gson, "${folder}potion_translation.json", potionDesc)

            if (!keyDesc.containsKey("Time")) keyDesc["Time"] = getString(R.string.key_game_time)
            if (!keyDesc.containsKey("Pos")) keyDesc["Pos"] = getString(R.string.key_coordinate)

            isLoaded = true
        } catch (e: Exception) {
            Log.w("NbtTranslator", "Translation load failed", e)
        }
    }

    @JvmStatic
    fun getString(resId: Int, vararg formatArgs: Any?): String {
        return mContext?.getString(resId, *formatArgs) ?: ""
    }

    private fun loadKeyMap(ctx: Context, gson: Gson, file: String) {
        try {
            val inputStream = ctx.assets.open(file)
            val list = gson.fromJson<List<JsonKeyItem>>(
                InputStreamReader(inputStream),
                object : TypeToken<List<JsonKeyItem>>() {}.type
            )
            for (item in list) {
                item.name?.let { name -> item.brief?.let { brief -> keyDesc[name] = brief } }
            }
        } catch (e: Exception) {
            Log.w(ContentValues.TAG, "Failed to load translation file: $file", e)
        }
    }

    private fun loadStringMap(ctx: Context, gson: Gson, file: String, map: MutableMap<String, String>) {
        try {
            val inputStream = ctx.assets.open(file)
            val list = gson.fromJson<List<JsonItem>>(
                InputStreamReader(inputStream),
                object : TypeToken<List<JsonItem>>() {}.type
            )
            for (item in list) {
                item.namespace?.let { namespace -> map[namespace] = item.name ?: "" }
            }
        } catch (e: Exception) {
            Log.w(ContentValues.TAG, "Failed to load translation file: $file", e)
        }
    }

    private fun loadIntMap(ctx: Context, gson: Gson, file: String, map: MutableMap<String, String>) {
        try {
            val inputStream = ctx.assets.open(file)
            val list = gson.fromJson<List<JsonItem>>(
                InputStreamReader(inputStream),
                object : TypeToken<List<JsonItem>>() {}.type
            )
            for (item in list) {
                map[item.id.toString()] = item.name ?: ""
                item.namespace?.let { namespace -> map[namespace] = item.name ?: "" }
            }
        } catch (e: Exception) {
            Log.w(ContentValues.TAG, "Failed to load translation file: $file", e)
        }
    }

    @JvmStatic
    fun getTranslation(key: String?): String? {
        return if (isLoaded) keyDesc[key] else null
    }

    @JvmStatic
    fun getItemTranslation(rawId: String?): String? {
        if (!isLoaded || rawId == null) return null
        val cleanId = rawId.replace("\"", "").trim()
        val res = itemDesc[cleanId]
        if (res != null) return res
        if (cleanId.startsWith("minecraft:")) return itemDesc[cleanId.substring(10)]
        return null
    }

    @JvmStatic
    fun getEnchantTranslation(idVal: String?): String? {
        if (!isLoaded) return null
        return enchDesc[idVal]
    }

    @JvmStatic
    fun getPotionTranslation(idVal: String?): String? {
        if (!isLoaded) return null
        return potionDesc[idVal]
    }

    @JvmStatic
    fun parseValue(key: String?, value: String?): String? {
        if (key == null || value == null) return null
        val cleanVal = value.replace("[^0-9\\-.]".toRegex(), "")
        if (cleanVal.isEmpty()) return null

        try {
            return when (key) {
                "Difficulty" -> {
                    when (toInt(cleanVal)) {
                        0 -> getString(R.string.key_difficulty_peace)
                        1 -> getString(R.string.key_difficulty_simple)
                        2 -> getString(R.string.key_difficulty_ordinary)
                        3 -> getString(R.string.key_difficulty_difficulty)
                        else -> null
                    }
                }

                "GameType", "ForceGameType", "PlayerGameMode" -> {
                    when (toInt(cleanVal)) {
                        0 -> getString(R.string.key_game_mode_survive)
                        1 -> getString(R.string.key_game_mode_create)
                        2 -> getString(R.string.key_game_mode_adventure)
                        3 -> getString(R.string.key_game_mode_watch)
                        5 -> getString(R.string.key_game_mode_default)
                        else -> null
                    }
                }

                "Dimension", "DimensionId", "SpawnDimension" -> {
                    when (toInt(cleanVal)) {
                        0 -> getString(R.string.key_dimension_main_world)
                        1 -> getString(R.string.key_dimension_nether)
                        2 -> getString(R.string.key_dimension_end)
                        else -> null
                    }
                }

                "Platform" -> {
                    val v = toInt(cleanVal)
                    if (v == 2) "2 (Android/Bedrock)" else value
                }

                "itemType" -> {
                    when (toInt(cleanVal)) {
                        1 -> "1: Byte"
                        2 -> "2: Short"
                        3 -> "3: Int"
                        4 -> "4: Long"
                        5 -> "5: Float"
                        6 -> "6: Double"
                        8 -> "8: String"
                        9 -> "9: List"
                        10 -> "10: Compound"
                        else -> null
                    }
                }

                "Time", "DayTime", "TimeSinceRest" -> {
                    val tick = cleanVal.toLong()
                    val days = tick.toFloat() / 24000.0f
                    String.format(Locale.getDefault(), getString(R.string.key_time_tick_calculation), days)
                }

                "LastPlayed" -> {
                    var t = cleanVal.toLong()
                    if (t > 1000000000000L) t /= 1000
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    sdf.format(Date(t * 1000L))
                }

                "rainTime", "lightningTime" -> {
                    val tick = cleanVal.toLong()
                    String.format(Locale.getDefault(), getString(R.string.key_thunderstorm_rainfall_mtimes), tick / 20, tick / 1200.0f)
                }

                "Generator", "GeneratorType" -> {
                    when (val v = toInt(cleanVal)) {
                        0 -> getString(R.string.key_world_generator_type_limited)
                        1 -> getString(R.string.key_world_generator_type_unlimited)
                        2 -> getString(R.string.key_world_generator_type_flat)
                        3 -> getString(R.string.key_world_generator_type_nether)
                        4 -> getString(R.string.key_world_generator_type_end)
                        5 -> getString(R.string.key_world_generator_type_void)
                        else -> "${v}${getString(R.string.key_world_generator_type_unknown)}"
                    }
                }

                else -> {
                    if (isBooleanKey(key)) {
                        when (cleanVal) {
                            "1" -> getString(R.string.key_boolean_logic_turn_on)
                            "0" -> getString(R.string.key_boolean_logic_closure)
                            else -> null
                        }
                    } else null
                }
            }
        } catch (e: Exception) {
            Log.w(ContentValues.TAG, "Boolean parsing failed for key: $key", e)
            return null
        }
    }

    @JvmStatic
    fun getEmojiIcon(key: String?): String? {
        if (key == null) return null
        return when (key) {
            "Time", "DayTime" -> "⏰"
            "rainTime", "rainLevel" -> "🌧️"
            "lightningTime", "lightningLevel" -> "⚡"
            "LastPlayed" -> "📅"
            "Pos", "SpawnX", "SpawnY", "SpawnZ" -> "📍"
            "DimensionId" -> "🌌"
            "Rotation" -> "🧭"
            "Motion" -> "💨"
            "Health", "HealF" -> "❤️"
            "Air" -> "🫧"
            "Fire" -> "🔥"
            "FoodLevel" -> "🍗"
            "Score", "XpLevel", "PlayerLevel" -> "✨"
            "Sleeping", "SleepTimer" -> "🛏️"
            "EnderChestInventory" -> "🟪"
            "Inventory" -> "🎒"
            "Armor" -> "🛡️"
            "LevelName" -> "🏷️"
            "RandomSeed" -> "🌱"
            "GameType", "Difficulty" -> "🎮"
            "colors" -> "🎨"
            else -> null
        }
    }

    private fun isBooleanKey(key: String): Boolean {
        return key.startsWith("is") || key.startsWith("Is") ||
                key.startsWith("do") || key.startsWith("Do") ||
                key.startsWith("has") || key.startsWith("Has") ||
                key.startsWith("can") || key.startsWith("Can") ||
                key.startsWith("allow") || key.startsWith("Allow") ||
                key.contains("Enabled") ||
                key == "pvp" || key == "mobgriefing" || key == "keepinventory" ||
                key == "naturalregeneration" || key == "tntexplodes" || key == "respawnblocksexplode" ||
                key == "commandblockoutput" || key == "sendcommandfeedback" ||
                key == "recipesunlock" || key == "immutableWorld" ||
                key == "OnGround" || key == "Invulnerable" || key == "Sleeping" ||
                key == "Saddled" || key == "Sheared" || key == "Sitting" ||
                key == "Chested" || key == "ShowBottom" || key == "LootDropped" ||
                key == "WasPickedUp" || key == "Dead" || key == "MultiplayerGame" ||
                key == "LANBroadcast"
    }

    private fun toInt(value: String): Int {
        if (value.isEmpty()) return 0
        return if (value.contains(".")) value.toDouble().toInt() else value.toInt()
    }

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