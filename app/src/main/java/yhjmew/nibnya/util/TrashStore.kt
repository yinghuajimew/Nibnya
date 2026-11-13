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
 * 回收站：删除存档时先把整个世界目录**移进来**，而不是直接抹掉。
 *
 * 目录结构：
 * ```
 * Nibnya/Trash/
 * └── 20261014_233045_我的生存世界/     ← 一次删除 = 一个文件夹
 *     ├── _trash_meta.json              ← 元数据（原始文件夹名/世界名/时间）
 *     └── world/                        ← 被移进来的整个世界目录
 * ```
 *
 * 这样 restore 时能放回原位；deleteForever 才真正删除。
 */
object TrashStore {

    const val DIR_NAME = "Trash"
    private const val META_NAME = "_trash_meta.json"
    private const val WORLD_SUB = "world"

    /** 默认自动清理天数（用户可在设置里改；<= 0 表示不自动清理） */
    const val DEFAULT_KEEP_DAYS = 30

    /** 公共下载目录下的回收站（用户可用文件管理器看到） */
    fun trashRoot(): File = sharedRoot()

    private fun sharedRoot(): File = AppDirs.trash()

    /** 公共目录不可写时的回退位置（App 私有外部目录，无需任何权限） */
    private fun privateRoot(context: Context): File = File(context.getExternalFilesDir(null), DIR_NAME)

    /**
     * 选一个可用的回收站根目录：
     * 优先公共 Download（用户能看到、可自己清理），公共目录建不出来时回退 App 私有目录。
     */
    private fun rootFor(context: Context): File {
        val shared = sharedRoot()
        if (shared.isDirectory) return shared
        if (shared.mkdirs()) return shared
        val priv = privateRoot(context)
        if (!priv.exists()) priv.mkdirs()
        return priv
    }

    /** 一次删除的记录 */
    data class TrashItem(
        val dir: File,                  // Trash 下的那层文件夹
        val originalFolder: String,     // 原存档文件夹名（base64 ID）
        val worldName: String?,         // 世界名（可能读不到）
        val deletedAt: Long,            // 删除时间
        val hasBackups: Boolean,        // 当时是否连带移除了备份
        val hasDrafts: Boolean,         // 当时是否连带移除了草稿
        val sizeBytes: Long,            // 占用体积
        /** 被删的存档原本在 SAF 位置（恢复时要写回 content:// 路径） */
        val safFolder: Boolean = false,
        /**
         * 被删的存档原本属于哪个根目录（带尾斜杠）。
         *
         * 「显示所有」模式下用户可能从旧版路径删除存档，恢复时必须回到那个根，
         * 否则会恢复进标准路径 —— 看起来「恢复了」，实际出现在别的地方。
         *
         * 空串 = 老记录没这个字段，退回当前路径（兼容升级前的回收站条目）。
         */
        val originalBase: String = ""
    ) {
        val label: String get() = worldName?.takeIf { it.isNotBlank() } ?: originalFolder

        fun timeText(): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(deletedAt))

        /** 实际存放世界内容的位置 */
        fun worldDir(): File = File(dir, WORLD_SUB)
    }

    /** 生成一个不冲突的回收站目录名 `<时间戳>_<世界名>` */
    fun newSlotName(worldName: String?): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safe = (worldName ?: "world")
            .map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("").trim('_').ifEmpty { "world" }.take(30)
        return "${ts}_$safe"
    }

    fun slotDir(context: Context, slotName: String): File = File(rootFor(context), slotName)

    /** 写入元数据 */
    fun writeMeta(
        slot: File,
        originalFolder: String,
        worldName: String?,
        hasBackups: Boolean,
        hasDrafts: Boolean,
        safFolder: Boolean = false,
        originalBase: String = ""
    ) {
        try {
            if (!slot.exists()) slot.mkdirs()
            val o = JsonObject()
            o.addProperty("originalFolder", originalFolder)
            o.addProperty("worldName", worldName)
            o.addProperty("deletedAt", System.currentTimeMillis())
            o.addProperty("hasBackups", hasBackups)
            o.addProperty("hasDrafts", hasDrafts)
            o.addProperty("safFolder", safFolder)
            if (originalBase.isNotBlank()) o.addProperty("originalBase", originalBase)
            File(slot, META_NAME).writeText(o.toString())
        } catch (e: Exception) {
            AppLogger.warn("Trash", R.string.log_trash_meta_write_failed, e.message)
        }
    }

    /** 列出回收站内容（按删除时间倒序） */
    fun list(context: Context): List<TrashItem> {
        return try {
            val roots = linkedSetOf(rootFor(context), sharedRoot(), privateRoot(context))
            roots.filter { it.isDirectory }
                .flatMap { root -> root.listFiles()?.toList() ?: emptyList() }
                .filter { it.isDirectory }
                .mapNotNull { parse(it) }
                .sortedByDescending { it.deletedAt }
        } catch (e: Exception) {
            AppLogger.warn("Trash", R.string.log_trash_list_failed, e.message)
            emptyList()
        }
    }

    fun count(context: Context): Int = try {
        val roots = linkedSetOf(rootFor(context), sharedRoot(), privateRoot(context))
        roots.filter { it.isDirectory }
            .sumOf { root -> root.listFiles()?.count { it.isDirectory } ?: 0 }
    } catch (_: Exception) {
        0
    }

    private fun parse(dir: File): TrashItem? {
        return try {
            val metaFile = File(dir, META_NAME)
            val o = if (metaFile.exists() && metaFile.length() > 0) {
                JsonParser.parseString(metaFile.readText()).asJsonObject
            } else JsonObject()

            val original = o.get("originalFolder")?.takeIf { !it.isJsonNull }?.asString
                ?: dir.name.substringAfter('_').ifEmpty { dir.name }

            TrashItem(
                dir = dir,
                originalFolder = original,
                worldName = o.get("worldName")?.takeIf { !it.isJsonNull }?.asString,
                deletedAt = o.get("deletedAt")?.asLong
                    ?: dir.lastModified(),
                hasBackups = o.get("hasBackups")?.asBoolean ?: false,
                hasDrafts = o.get("hasDrafts")?.asBoolean ?: false,
                sizeBytes = sizeOf(dir),
                safFolder = o.get("safFolder")?.asBoolean ?: false,
                originalBase = o.get("originalBase")?.takeIf { !it.isJsonNull }?.asString ?: ""
            )
        } catch (_: Exception) {
            null
        }
    }

    /** 递归求体积 */
    fun sizeOf(f: File?): Long {
        if (f == null || !f.exists()) return 0L
        if (f.isFile) return f.length()
        var total = 0L
        try {
            f.listFiles()?.forEach { total += sizeOf(it) }
        } catch (_: Exception) {
        }
        return total
    }

    fun deleteForever(item: TrashItem) {
        try {
            deleteRecursive(item.dir)
            AppLogger.info("Trash", R.string.log_trash_deleted, item.dir.name)
        } catch (e: Exception) {
            AppLogger.warn("Trash", R.string.log_trash_delete_failed, e.message)
        }
    }

    fun clearAll(context: Context) {
        try {
            val roots = linkedSetOf(rootFor(context), sharedRoot(), privateRoot(context))
            roots.forEach { root -> root.listFiles()?.forEach { deleteRecursive(it) } }
        } catch (_: Exception) {
        }
    }

    private fun deleteRecursive(f: File?) {
        if (f == null || !f.exists()) return
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
        f.delete()
    }

    /**
     * 自动清理超过 [days] 天的条目。
     * @param days <= 0 表示不自动清理
     * @return 清理掉的数量
     */
    fun autoClean(context: Context, days: Int): Int {
        if (days <= 0) return 0
        var n = 0
        try {
            val deadline = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000
            list(context).forEach {
                if (it.deletedAt > 0 && it.deletedAt < deadline) {
                    deleteForever(it); n++
                }
            }
            if (n > 0) AppLogger.info("Trash", R.string.log_trash_auto_cleaned, n, days)
        } catch (_: Exception) {
        }
        return n
    }
}
