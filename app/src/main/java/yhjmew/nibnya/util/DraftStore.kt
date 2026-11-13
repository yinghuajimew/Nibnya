package yhjmew.nibnya.util

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import yhjmew.nibnya.R

/**
 * 草稿箱存储：保存「未保存的编辑」，供用户在草稿箱里主动找回。
 *
 * 与**备份**（已保存状态的快照，存在 `Nibnya/Backups/`）完全分离：
 * - 存储位置：`filesDir/drafts/`（App 私有）
 * - 生命周期：编辑时自动落盘，保存成功后自动清除
 * - 找回方式：用户主动打开「草稿箱」，**不再自动弹窗打扰**
 *
 * 支持多份草稿，上限由用户配置，超限删除最旧的。
 */
object DraftStore {

    private const val DIR_NAME = "drafts"

    /** 草稿数量上限的默认值（用户可在设置里改） */
    const val DEFAULT_MAX_DRAFTS = 10

    private fun dirOf(context: Context): File {
        val d = File(context.filesDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** 草稿 key 里可能有 '~' 等字符，安全化为文件名 */
    private fun fileNameOf(sessionKey: String): String {
        val safe = sessionKey.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("")
        return "$safe.json"
    }

    private fun fileOf(context: Context, sessionKey: String): File =
        File(dirOf(context), fileNameOf(sessionKey))

    // ============================================
    // 写入
    // ============================================

    /**
     * 保存一份草稿（覆盖同 key 的旧草稿）。
     *
     * @param data          当前编辑中的数据
     * @param originalData  可选：进入编辑时的原始数据，用于「对比改动」
     * @param folder        所属存档目录名，用于草稿箱展示与防串档
     * @param maxDrafts     上限；超过则删除最旧的
     */
    fun save(
        context: Context,
        sessionKey: String,
        data: JsonObject,
        isPlayerMode: Boolean,
        targetKey: String?,
        dbPath: String?,
        folder: String?,
        originalData: JsonObject?,
        worldName: String?,
        maxDrafts: Int,
        basePath: String = ""
    ) {
        try {
            val obj = JsonObject()
            obj.addProperty("key", sessionKey)
            obj.addProperty("time", System.currentTimeMillis())
            obj.addProperty("isPlayerMode", isPlayerMode)
            obj.addProperty("targetKey", targetKey)
            obj.addProperty("dbPath", dbPath)
            obj.addProperty("folder", folder)
            obj.addProperty("worldName", worldName)
            if (basePath.isNotBlank()) obj.addProperty("basePath", basePath)
            obj.add("data", data)
            originalData?.let { obj.add("originalData", it) }

            fileOf(context, sessionKey).writeText(obj.toString())
            AppLogger.info("Draft", R.string.log_draft_saved, sessionKey)
            pruneTo(context, maxDrafts)
        } catch (e: Exception) {
            AppLogger.warn("Draft", R.string.log_draft_save_failed, e.message)
        }
    }

    // ============================================
    // 读取
    // ============================================

    /** 读取单份草稿 */
    fun load(context: Context, sessionKey: String): Draft? {
        return try {
            val f = fileOf(context, sessionKey)
            if (!f.exists() || f.length() == 0L) return null
            parse(f)
        } catch (e: Exception) {
            AppLogger.warn("Draft", R.string.log_draft_read_failed, e.message)
            null
        }
    }

    /** 列出全部草稿，按时间倒序（最新在前） */
    fun list(context: Context): List<Draft> {
        return try {
            dirOf(context).listFiles()
                ?.filter { it.isFile && it.name.endsWith(".json") && it.length() > 0 }
                ?.mapNotNull { parse(it) }
                ?.sortedByDescending { it.savedAt }
                ?: emptyList()
        } catch (e: Exception) {
            AppLogger.warn("Draft", R.string.log_draft_list_failed, e.message)
            emptyList()
        }
    }

    /** 草稿文件的字节数（用于详情展示）；0 = 不存在 */
    fun sizeOf(context: Context, sessionKey: String): Long = try {
        fileOf(context, sessionKey).takeIf { it.exists() }?.length() ?: 0L
    } catch (_: Exception) {
        0L
    }

    fun count(context: Context): Int = try {
        dirOf(context).listFiles()?.count { it.isFile && it.name.endsWith(".json") } ?: 0
    } catch (_: Exception) {
        0
    }

    private fun parse(f: File): Draft? {
        return try {
            val obj = JsonParser.parseString(f.readText()).asJsonObject
            val data = obj.getAsJsonObject("data") ?: return null
            Draft(
                sessionKey = obj.get("key")?.asString ?: f.nameWithoutExtension,
                savedAt = obj.get("time")?.asLong ?: f.lastModified(),
                isPlayerMode = obj.get("isPlayerMode")?.asBoolean ?: false,
                targetKey = obj.str("targetKey"),
                dbPath = obj.str("dbPath"),
                folder = obj.str("folder"),
                worldName = obj.str("worldName"),
                data = data,
                originalData = obj.getAsJsonObject("originalData"),
                basePath = obj.str("basePath") ?: ""
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString

    // ============================================
    // 删除 / 清理
    // ============================================

    fun remove(context: Context, sessionKey: String) {
        try {
            fileOf(context, sessionKey).takeIf { it.exists() }?.delete()
        } catch (_: Exception) {
        }
    }

    fun clearAll(context: Context) {
        try {
            dirOf(context).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    /** 超过上限时删除最旧的几份 */
    fun pruneTo(context: Context, maxDrafts: Int) {
        if (maxDrafts <= 0) return
        try {
            val all = list(context)
            if (all.size <= maxDrafts) return
            all.drop(maxDrafts).forEach {
                remove(context, it.sessionKey)
                AppLogger.info("Draft", R.string.log_draft_prune, maxDrafts, it.sessionKey)
            }
        } catch (_: Exception) {
        }
    }

    // ============================================
    // 数据模型
    // ============================================

    data class Draft(
        val sessionKey: String,
        val savedAt: Long,
        val isPlayerMode: Boolean,
        val targetKey: String?,
        val dbPath: String?,
        val folder: String?,
        /** 世界名（保存时记录，用于列表展示；旧草稿为 null） */
        val worldName: String?,
        val data: JsonObject,
        /** 可选：进入编辑时的原始数据，用于对比改动 */
        val originalData: JsonObject?,
        /**
         * 这份草稿对应的存档原本在哪个根目录（带尾斜杠）。
         *
         * 「显示所有」模式下用户可能从旧版路径编辑，草稿恢复后若按
         * 「当前路径」保存，就会写到别处。旧草稿没有这个字段，退回当前路径。
         */
        val basePath: String = ""
    ) {
        /**
         * 列表第一行冒号前的「名称」：
         * 优先世界名，其次玩家 key / 文件夹名。
         */
        fun label(): String = when {
            !worldName.isNullOrBlank() -> worldName
            isPlayerMode -> targetKey ?: folder ?: "~local_player"
            else -> folder?.takeIf { it.isNotBlank() } ?: "level.dat"
        }

        /** 列表第一行的「文件夹名 / base64 ID」部分 */
        fun folderId(): String = when {
            isPlayerMode -> folder ?: targetKey ?: "~local_player"
            else -> folder ?: "level.dat"
        }

        fun hasOriginal(): Boolean = originalData != null

        /** 格式化保存时间（纯数字，无语言差异） */
        fun timeText(): String =
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(savedAt))

        /**
         * 距今多久（分钟数）。由 UI 层负责翻译成文字，
         * 避免在 util 层硬编码中文。
         * @return 分钟数；负数按 0 处理
         */
        fun minutesAgo(): Long {
            val diff = System.currentTimeMillis() - savedAt
            return if (diff <= 0) 0 else diff / 60000
        }
    }
}
