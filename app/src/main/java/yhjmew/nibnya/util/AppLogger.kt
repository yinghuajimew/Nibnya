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

    /**
     * 默认保留天数，供调试页下拉回显。
     *
     * 用户可在「调试 → 日志 → 保留范围」里改；改后写 prefs 的
     * `debug_log_keep_days`，0 表示不自动清理。
     */
    const val KEEP_DAYS_DEFAULT: Int = 14

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

    /**
     * 清理超过保留天数的文件。
     *
     * 2026-11-08 起保留天数改由用户在「调试 → 日志」里决定
     * （prefs: `debug_log_keep_days`，0 = 不自动清理）。
     * 没设置过就沿用默认的 14 天，保持老行为。
     */
    private fun pruneOldFiles() {
        val dir = try {
            AppDirs.crashLogs()
        } catch (_: Throwable) {
            return
        }
        if (!dir.exists()) return

        val keep = try {
            appContext?.getSharedPreferences("nbt_config", Context.MODE_PRIVATE)
                ?.getInt("debug_log_keep_days", KEEP_DAYS) ?: KEEP_DAYS
        } catch (_: Throwable) {
            KEEP_DAYS
        }
        if (keep <= 0) return   // 用户选择「不自动清理」

        val cutoff = System.currentTimeMillis() - keep * 24L * 3600_000L
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
    /**
     * 日志级别门槛：只输出 >= 该级别的条目。
     *
     * 为什么需要它：
     *   补齐 catch 块之后，INFO 会非常多（正常流程也会记）。
     *   用户日常看日志只想看「哪里不对」，所以要能只看 WARN 以上。
     *
     * 顺序：DEBUG < INFO < WARN < ERROR
     */
    enum class Level(val rank: Int, val label: String) {
        DEBUG(0, "DEBUG"),
        INFO(1, "INFO"),
        WARN(2, "WARN"),
        ERROR(3, "ERROR"),
    }

    private fun rankOf(level: String): Int = when (level.uppercase(Locale.US)) {
        "DEBUG" -> 0
        "INFO" -> 1
        "WARN" -> 2
        "ERROR" -> 3
        else -> 1
    }

    /**
     * @param minLevel 只输出该级别及以上的条目；传 null 表示全部
     */
    fun getLogs(minLevel: Level? = null): String {
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

        val shown = entries.filter { e ->
            minLevel == null || rankOf(e.level) >= minLevel.rank
        }
        if (shown.isNotEmpty()) {
            sb.appendLine("${ctx?.getString(R.string.log_entries) ?: "Logs"}:")
            shown.forEach { e ->
                val body = e.raw ?: format(e.resId, e.args) ?: ""
                sb.appendLine("[${e.time}][${e.level}][${e.tag}] $body")
            }
        } else if (entries.isNotEmpty() && minLevel != null) {
            // 有日志但都被过滤掉了 —— 明确告诉用户，不然会以为没记
            sb.appendLine(
                (ctx?.getString(R.string.log_none_at_level) ?: "No entries at level %s or above")
                    .format(minLevel.label)
            )
        }
        return sb.toString()
    }

    /** 当前级别过滤下有没有内容（给按钮态用） */
    fun hasEntries(minLevel: Level? = null): Boolean =
        entries.any { minLevel == null || rankOf(it.level) >= minLevel.rank }

    /** 各级别条数，用于在筛选行上显示「WARN 3 / ERROR 1」 */
    fun counts(): Map<Level, Int> {
        val m = mutableMapOf<Level, Int>()
        entries.forEach { e ->
            val lv = Level.entries[rankOf(e.level).coerceIn(0, 3)]
            m[lv] = (m[lv] ?: 0) + 1
        }
        return m
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

    // ============================================
    // 按天读取 / 按范围清理（2026-11-08）
    // ============================================

    /** 磁盘上已有的日志日期，新 -> 旧。形如 2026-11-08。 */
    fun logDays(): List<String> {
        val dir = try {
            AppDirs.crashLogs()
        } catch (_: Throwable) {
            return emptyList()
        }
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f ->
            f.isFile && f.name.startsWith("nibnya-") && f.name.endsWith(".log")
        }?.mapNotNull { dayOfFile(it.name) }?.distinct()?.sortedDescending() ?: emptyList()
    }

    /** 从文件名 `nibnya-2026-11-08.log` / `nibnya-2026-11-08-2.log` 取出日期。 */
    private fun dayOfFile(name: String): String? {
        if (!name.startsWith("nibnya-")) return null
        val rest = name.removePrefix("nibnya-")
        if (rest.length < 10) return null
        val d = rest.substring(0, 10)
        return if (d.length == 10 && d[4] == '-' && d[7] == '-' &&
            d.filterIndexed { i, _ -> i != 4 && i != 7 }.all { it.isDigit() }
        ) d else null
    }

    /**
     * 读某一天的日志文件。
     *
     * 历史文件里已经是 `[HH:mm:ss][LEVEL][tag] body` 的成品行，
     * 所以这里可以直接按级别过滤，不需要重新格式化。
     */
    fun readDay(day: String, minLevel: Level? = null): String {
        val dir = try {
            AppDirs.crashLogs()
        } catch (_: Throwable) {
            return ""
        }
        val files = dir.listFiles { f ->
            f.isFile && f.name.startsWith("nibnya-$day") && f.name.endsWith(".log")
        }?.sortedBy { it.name } ?: return ""

        val sb = StringBuilder()
        files.forEach { f ->
            try {
                f.readLines().forEach { line ->
                    if (minLevel == null) {
                        sb.appendLine(line)
                    } else {
                        val lv = LEVEL_IN_LINE.find(line)?.groupValues?.get(1)
                        if (lv != null && rankOf(lv) >= minLevel.rank) sb.appendLine(line)
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return sb.toString()
    }

    /**
     * 删除若干天之前的日志文件。
     *
     * @param days 保留最近几天。0 = 全部删除（含今天）。
     * @return 删除的文件数
     */
    fun deleteOlderThan(days: Int): Int {
        val dir = try {
            AppDirs.crashLogs()
        } catch (_: Throwable) {
            return 0
        }
        if (!dir.exists()) return 0

        // 用「日期字符串比较」而不是 lastModified：
        // 文件被复制/恢复过之后 mtime 不可信，日期在文件名里是最准的。
        val cutoff = if (days <= 0) null else {
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(Date(System.currentTimeMillis() - days * 24L * 3600_000L))
        }
        var n = 0
        dir.listFiles { f ->
            f.isFile && f.name.startsWith("nibnya-") && f.name.endsWith(".log")
        }?.forEach { f ->
            val d = dayOfFile(f.name) ?: return@forEach
            // cutoff 当天不删：用户说「最近 7 天」时，今天属于其中
            if (cutoff == null || d < cutoff) {
                if (f.delete()) n++
            }
        }

        // 全删时内存里的也要清掉，否则界面还显示旧内容
        if (days <= 0) entries.clear()
        else if (cutoff != null && currentDay < cutoff) entries.clear()
        return n
    }

    /** 日志行里的级别：`][WARN][` */
    private val LEVEL_IN_LINE = Regex("""\]\[([A-Z]+)\]\[""")

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
