package yhjmew.nibnya.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.os.Process
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess
import yhjmew.nibnya.R

/** 全局异常捕获器 当程序发生未捕获异常时，由该类接管程序，并记录发送错误报告  */
class CrashHandler private constructor() : Thread.UncaughtExceptionHandler {
    private var mContext: Context? = null
    private var mDefaultHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        mContext = context.applicationContext
        // 获取系统默认的 UncaughtException 处理器
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        // 设置该 CrashHandler 为程序的默认处理器
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        // 崩溃前抢救未保存的编辑：写入草稿箱，用户下次可从「草稿箱」找回。
        // 必须放在最前面（进程随时可能被终止），并全程容错 ——
        // 崩溃时状态可能已不稳定，落盘失败也不能影响后续的错误日志。
        try {
            DraftAutoSaver.persistNow()
        } catch (_: Throwable) {
        }

        if (!handleException(ex) && mDefaultHandler != null) {
            // 如果用户没有处理则让系统默认的异常处理器来处理
            mDefaultHandler!!.uncaughtException(thread, ex)
        } else {
            try {
                // 给 Toast 留出显示时间
                Thread.sleep(3000)
            } catch (e: InterruptedException) {
                Log.e(TAG, "error : ", e)
            }
            // 退出程序
            Process.killProcess(Process.myPid())
            exitProcess(1)
        }
    }

    /**
     * 自定义错误处理,收集错误信息 发送错误报告等操作均在此完成.
     * 
     * @return true:如果处理了该异常信息;否则返回false.
     */
    private fun handleException(ex: Throwable?): Boolean {
        if (ex == null) return false

        // 使用 Toast 来显示异常信息
        object : Thread() {
            override fun run() {
                Looper.prepare()
                val text = mContext!!.getString(R.string.toast_crash_collapse)

                Toast.makeText(mContext, text, Toast.LENGTH_LONG).show()
                Looper.loop()
            }
        }.start()

        // 收集设备参数信息
        val deviceInfo = collectDeviceInfo(mContext!!)

        // 保存日志文件
        saveCrashInfo2File(ex, deviceInfo)

        return true
    }

    // 收集设备信息
    // 与 AppLogger 共用 DeviceInfo，避免两处字段/格式不一致
    private fun collectDeviceInfo(ctx: Context): String {
        val localized = DeviceInfo.localizedContext(ctx)
        val sb = StringBuilder()
        sb.append(
            "${localized.getString(R.string.log_app_version)}: " +
                (appVersion(ctx) ?: "unknown") + "\n"
        )
        sb.append(DeviceInfo.collect(localized))
        return sb.toString()
    }

    /** 应用版本，形如 `1.0.4b (104)` */
    private fun appVersion(ctx: Context): String? = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_ACTIVITIES)
        val code = if (Build.VERSION.SDK_INT >= 28) {
            pi.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toString()
        }
        "${pi.versionName} ($code)"
    } catch (_: Throwable) {
        null
    }

    // 保存错误信息到文件中
    // 保存错误信息到文件中 (双重备份版：同时写入私有目录和公共目录)
    private fun saveCrashInfo2File(ex: Throwable, deviceInfo: String?) {
        val loc = mContext?.let { DeviceInfo.localizedContext(it) }
        val sb = StringBuilder()
        // 崩溃时 state 可能已不稳定，取 string 资源也容错，拿不到就退回英文
        val header = runCatching { loc?.getString(R.string.log_crash_header) }.getOrNull()
            ?: "Nibnya Crash Log"
        sb.append("====== $header ======\n")
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val time = format.format(Date())
        val timeLabel = runCatching { loc?.getString(R.string.log_time) }.getOrNull() ?: "Time"
        sb.append("$timeLabel: ").append(time).append("\n")
        sb.append(deviceInfo)
        sb.append("\n====== STACK TRACE ======\n")

        val writer: Writer = StringWriter()
        val printWriter = PrintWriter(writer)
        ex.printStackTrace(printWriter)
        var cause = ex.cause
        while (cause != null) {
            cause.printStackTrace(printWriter)
            cause = cause.cause
        }
        printWriter.close()
        val result = writer.toString()
        sb.append(result)
        sb.append("\n=======================\n")

        // 同时并入 AppLogger —— 崩溃后重开 App，在「调试 → 显示日志」里
        // 就能直接看到，不用跑去文件夹翻 crash-*.log。
        // 只放堆栈前几行，避免把整份日志再抄一遍（文件里已经是完整的）。
        try {
            val brief = result.lineSequence()
                .filter { it.isNotBlank() }
                .take(6)
                .joinToString(" | ")
            AppLogger.error("Crash", "未捕获异常: $brief")
        } catch (_: Throwable) {
            // 崩溃现场不能再出岔子
        }

        // 准备文件名和内容
        val logContent = sb.toString()
        val fileName =
            "crash-" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".log"

        // === 1. 写入 App 私有目录 (Android/data/.../files/CrashLogs) ===
        // 这是保底方案，几乎总是能成功的
        try {
            val privateDir = File(mContext!!.getExternalFilesDir(null), "CrashLogs")
            if (!privateDir.exists()) privateDir.mkdirs()

            val privateFile = File(privateDir, fileName)
            val fos = FileOutputStream(privateFile)
            fos.write(logContent.toByteArray())
            fos.close()
            Log.i(TAG, "Private Log saved: " + privateFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save private log", e)
        }

        // === 2. 写入公共下载目录 (Download/Nibnya/Crash_Logs) ===
        // 方便用户直接查看，但可能会因为权限问题失败
        try {
            val publicDir = AppDirs.crashLogs()
            if (!publicDir.exists()) publicDir.mkdirs()

            val publicFile = File(publicDir, fileName)
            val fos = FileOutputStream(publicFile)
            fos.write(logContent.toByteArray())
            fos.close()
            Log.i(TAG, "Public Log saved: " + publicFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save public log (Permission denied?)", e)
        }
    }

    companion object {
        private const val TAG = "CrashHandler"

        @SuppressLint("StaticFieldLeak")
        var instance: CrashHandler? = null
            get() {
                if (field == null) {
                    field = CrashHandler()
                }
                return field
            }
            private set
    }
}