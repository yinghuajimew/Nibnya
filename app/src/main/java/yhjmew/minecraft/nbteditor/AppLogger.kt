package yhjmew.minecraft.nbteditor

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private val logEntries = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private const val MAX_ENTRIES = 500

    fun info(tag: String, message: String) {
        append("INFO", tag, message)
    }

    fun warn(tag: String, message: String) {
        append("WARN", tag, message)
    }

    fun error(tag: String, message: String) {
        append("ERROR", tag, message)
    }

    private fun append(level: String, tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        logEntries.add("[$timestamp][$level][$tag] $message")
        if (logEntries.size > MAX_ENTRIES) {
            logEntries.removeAt(0)
        }
    }

    fun getLogs(): String {
        if (logEntries.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("====== NBT Editor Logs ======")
        sb.appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        sb.appendLine("==============================")
        logEntries.forEach { sb.appendLine(it) }
        return sb.toString()
    }

    fun clear() {
        logEntries.clear()
    }

    fun exportToFile(): File? {
        val logs = getLogs()
        if (logs.isEmpty()) return null
        return try {
            val dir = File("/storage/emulated/0/Download/NbtEditor_Data/Crash_Logs/")
            if (!dir.exists()) dir.mkdirs()
            val fileName = "debug-${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.log"
            val file = File(dir, fileName)
            FileOutputStream(file).use { it.write(logs.toByteArray()) }
            file
        } catch (_: Exception) {
            null
        }
    }
    fun error(tag: String, message: String, throwable: Throwable) {
        val detail = if (throwable.message != null) "$message: ${throwable.message}" else message
        append("ERROR", tag, detail)
    }
}