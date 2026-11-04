package yhjmew.nibnya.util

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import yhjmew.nibnya.R

/**
 * 应用内日志。
 *
 * ## 为什么日志要存「资源 ID + 参数」而不是成品字符串
 * 日志正文需要跟随**应用内设定的语言**（中文用户看中文、英文用户看英文）。
 * 如果在写入时就 `getString()` 定型，那么用户切到英文后，之前记录的那批日志
 * 仍是旧语言 —— 导出的文件会是中英混杂。所以这里把资源 ID 与参数**存起来**，
 * 等到真正渲染（显示 / 导出 / 复制）时才用**当时的语言**格式化。
 */
object AppLogger {
    private const val MAX_ENTRIES = 500

    /** 应用 Context，由 `MainActivity.onCreate` 注入；用于渲染时取资源 */
    @Volatile
    private var appContext: Context? = null

    /**
     * 一条日志。
     * @param resId 资源 ID（本地化文案）；为 0 表示用 [raw]
     * @param args  资源占位符参数（渲染时才代入）
     * @param raw   已经成型的字符串（调用方本就有成品文本时用，如异常 message）
     */
    private class Entry(
        val time: String,
        val level: String,
        val tag: String,
        val resId: Int,
        val args: Array<out Any?>,
        val raw: String?
    )

    private val entries = ArrayDeque<Entry>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // ── 持久化（2026-11-02）─────────────────────────────────
    // 日志不再只活在内存里：每次写入都追加到当天的文件，
    // 重启后自动读回。按天分文件，第二天打开就是新的一页。
    //
    // 为什么不做成「一个大文件」：
    //   用户明确要求「这一天记录完了，第二天打开就记第二天，不接着第一天的」。
    // 为什么不做成 JSON/二进制：
    //   导出的就是给人看的文本，直接写文本最省事，也方便用户自己翻。

    /** 当前正在写的日志日期，形如 2026-11-02。跨天时换文件。 */
    @Volatile
    private var currentDay: String = dayOf(Date())

    /** 单文件大小上限。超过就滚动到 -2、-3… 避免一天写爆。 */
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    /** 保留最近多少天。超出的老文件在启动时清掉。 */
    private const val KEEP_DAYS = 14

    private fun dayOf(d: Date): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)

    /** 当天的日志文件（不存在则创建目录） */
    private fun dayFile(): File? {
        val dir = try {
            AppDirs.crashLogs()
        } catch (_: Throwable) {
            return null
        }
        if (!dir.exists() && !dir.mkdirs()) return null

        var f = File(dir, "nibnya-$currentDay.log")
        var seq = 2
        while (f.exists() && f.length() > MAX_FILE_BYTES) {
            f = File(dir, "nibnya-$currentDay-$seq.log")
            seq++
            if (seq > 20) break
        }
        return f
    }

    /**
     * 把当天的日志文件读回内存。
     *
     * 文件里存的是**已经渲染好的成品行**（写的时候就格式化过了），
     * 因为磁盘上必须是人能直接看懂的文本。
     * 代价是：切语言后，历史行仍是旧语言 —— 这是刻意的取舍，
     * 用户要的是「日志不丢」，不是「历史行跟着语言变」。
     */
    private fun loadTodayFromDisk() {
        val dir = try {
            AppDirs.crashLogs()
        } catch (_: Throwable) {
            return
        }
        if (!dir.exists()) return

        currentDay = dayOf(Date())
        val files = dir.listFiles { f ->
            f.isFile && f.name.startsWith("nibnya-$currentDay") && f.name.endsWith(".log")
        }?.sortedBy { it.name } ?: return

        entries.clear()
        for (f in files) {
            try {
                f.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    if (entries.size >= MAX_ENTRIES) return@forEachLine
                    parseLine(line)?.let { entries.addLast(it) }
                }
            } catch (_: Throwable) {
                // 单个文件读不动就跳过，不影响其他
            }
        }
    }

    /**
     * 解析一行 `[HH:mm:ss][LEVEL][TAG] 正文`。
     * 解析不出来就整行塞进 raw，至少不丢内容。
     */
    private fun parseLine(line: String): Entry? {
        if (!line.startsWith("[")) {
            return Entry("", "INFO", "", 0, emptyArray(), line)
        }
        return try {
            val a = line.indexOf(']')
            val b = line.indexOf('[', a + 1)
            val c = line.indexOf(']', b)
            val d = line.indexOf('[', c + 1)
            val e = line.indexOf(']', d)
            if (a < 0 || b < 0 || c < 0 || d < 0 || e < 0) {
                return Entry("", "INFO", "", 0, emptyArray(), line)
            }
            val time = line.substring(1, a)
            val level = line.substring(b + 1, c)
            val tag = line.substring(d + 1, e)
            val body = line.substring(e + 1).trimStart()
            Entry(time, level, tag, 0, emptyArray(), body)
        } catch (_: Throwable) {
            Entry("", "INFO", "", 0, emptyArray(), line)
        }
    }

    /** 追加一行到当天文件。失败就静默放弃 —— 日志不能反过来把 App 搞崩。 */
    private fun persist(level: String, tag: String, body: String) {
        val f = dayFile() ?: return
        try {
            FileOutputStream(f, true).use { out ->
                out.write("[${timeFormat.format(Date())}][$level][$tag] $body\n".toByteArray())
            }
        } catch (_: Throwable) {
        }
    }

    /** 清理超过 KEEP_DAYS 天的文件 */
    private fun pruneOldFiles() {
        val dir = try {
            AppDirs.crashLogs()
        } catch (_: Throwable) {
            return
        }
        if (!dir.exists()) return
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600_000L
        dir.listFiles { f ->
            f.isFile && f.name.startsWith("nibnya-") && f.lastModified() < cutoff
        }?.forEach { it.delete() }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        // 读回当天已有的日志（重启后不丢）+ 清理过期文件
        loadTodayFromDisk()
        pruneOldFiles()
    }

    // ============================================
    // 记录：资源 ID 版（推荐，随语言本地化）
    // ============================================

    fun info(tag: String, resId: Int, vararg args: Any?) = append("INFO", tag, resId, args, null)
    fun warn(tag: String, resId: Int, vararg args: Any?) = append("WARN", tag, resId, args, null)
    fun error(tag: String, resId: Int, vararg args: Any?) = append("ERROR", tag, resId, args, null)

    // ============================================
    // 记录：成品字符串版（异常信息这类无法资源化的场景）
    // ============================================

    fun info(tag: String, message: String) = append("INFO", tag, 0, emptyArray(), message)
    fun warn(tag: String, message: String) = append("WARN", tag, 0, emptyArray(), message)
    fun error(tag: String, message: String) = append("ERROR", tag, 0, emptyArray(), message)

    fun error(tag: String, message: String, throwable: Throwable) {
        val detail = if (throwable.message != null) "$message: ${throwable.message}" else message
        append("ERROR", tag, 0, emptyArray(), detail)
    }

    /** 资源文案 + 异常详情 */
    fun error(tag: String, resId: Int, throwable: Throwable, vararg args: Any?) {
        val text = format(resId, args) ?: ""
        val detail = if (throwable.message != null) "$text: ${throwable.message}" else text
        append("ERROR", tag, 0, emptyArray(), detail)
    }

    private fun append(
        level: String,
        tag: String,
        resId: Int,
        args: Array<out Any?>,
        raw: String?
    ) {
        // 跨天：换文件、清空内存列表，新的一天从干净的一页开始
        val today = dayOf(Date())
        if (today != currentDay) {
            currentDay = today
            entries.clear()
        }

        val entry = Entry(timeFormat.format(Date()), level, tag, resId, args, raw)
        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()

        // 落盘。正文用**当前语言**定型 —— 磁盘上的文件必须是可直接阅读的。
        val body = raw ?: format(resId, args) ?: ""
        persist(level, tag, body)
    }

    /** 用当前语言格式化；拿不到 Context 时退回占位提示（不会崩） */
    private fun format(resId: Int, args: Array<out Any?>): String? {
        if (resId == 0) return null
        val ctx = appContext ?: return "[#$resId]"
        return try {
            ctx.getString(resId, *args)
        } catch (_: Throwable) {
            "[#$resId]"
        }
    }

    // ============================================
    // 渲染
    // ============================================

    /**
     * 组装完整日志：标题 + 时间 + **设备信息** + 全部日志条目。
     * 标题、设备信息标签、**以及每条日志正文**都按应用语言渲染。
     */
    fun getLogs(): String {
        val ctx = appContext?.let { DeviceInfo.localizedContext(it) }
        val sb = StringBuilder()

        sb.appendLine("====== ${ctx?.getString(R.string.log_header) ?: "Nibnya Logs"} ======")
        sb.appendLine("${ctx?.getString(R.string.log_time) ?: "Time"}: " +
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        sb.appendLine("==============================")

        if (ctx != null) {
            sb.appendLine("${ctx.getString(R.string.log_device_info)}:")
            sb.append(DeviceInfo.collect(ctx))
            sb.appendLine("==============================")
        }

        if (entries.isNotEmpty()) {
            sb.appendLine("${ctx?.getString(R.string.log_entries) ?: "Logs"}:")
            entries.forEach { e ->
                val body = e.raw ?: format(e.resId, e.args) ?: ""
                sb.appendLine("[${e.time}][${e.level}][${e.tag}] $body")
            }
        }
        return sb.toString()
    }

    /**
     * 清空日志：内存 + 磁盘上的当天文件。
     * 历史日期的文件不动 —— 用户要清的是「今天这一页」，
     * 顺手删掉前几天会让人觉得莫名其妙。
     */
    fun clear() {
        entries.clear()
        val dir = try {
            AppDirs.crashLogs()
        } catch (_: Throwable) {
            return
        }
        dir.listFiles { f ->
            f.isFile && f.name.startsWith("nibnya-$currentDay")
        }?.forEach { it.delete() }
    }

    fun exportToFile(): File? {
        val logs = getLogs()
        if (logs.isEmpty()) return null
        return try {
            val dir = AppDirs.crashLogs()
            if (!dir.exists()) dir.mkdirs()
            val fileName = "debug-${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.log"
            val file = File(dir, fileName)
            FileOutputStream(file).use { it.write(logs.toByteArray()) }
            file
        } catch (_: Exception) {
            null
        }
    }
}
