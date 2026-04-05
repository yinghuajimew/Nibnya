package yhjmew.minecraft.nbteditor;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/** 全局异常捕获器 当程序发生未捕获异常时，由该类接管程序，并记录发送错误报告 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";
    private static CrashHandler instance;
    private Context mContext;
    private Thread.UncaughtExceptionHandler mDefaultHandler;

    private CrashHandler() {}

    public static CrashHandler getInstance() {
        if (instance == null) {
            instance = new CrashHandler();
        }
        return instance;
    }

    public void init(Context context) {
        mContext = context;
        // 获取系统默认的 UncaughtException 处理器
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        // 设置该 CrashHandler 为程序的默认处理器
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        if (!handleException(ex) && mDefaultHandler != null) {
            // 如果用户没有处理则让系统默认的异常处理器来处理
            mDefaultHandler.uncaughtException(thread, ex);
        } else {
            try {
                // 给 Toast 留出显示时间
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Log.e(TAG, "error : ", e);
            }
            // 退出程序
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(1);
        }
    }

    /**
     * 自定义错误处理,收集错误信息 发送错误报告等操作均在此完成.
     *
     * @return true:如果处理了该异常信息;否则返回false.
     */
    private boolean handleException(final Throwable ex) {
        if (ex == null) return false;

// 使用 Toast 来显示异常信息
        new Thread() {
            @Override
            public void run() {
                Looper.prepare();
                String text = mContext.getString(R.string.toast_crash_collapse);

                Toast.makeText(mContext, text, Toast.LENGTH_LONG).show();
                Looper.loop();
            }
        }.start();

        // 收集设备参数信息
        String deviceInfo = collectDeviceInfo(mContext);

        // 保存日志文件
        saveCrashInfo2File(ex, deviceInfo);

        return true;
    }

    // 收集设备信息
    private String collectDeviceInfo(Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_ACTIVITIES);
            if (pi != null) {
                String versionName = pi.versionName == null ? "null" : pi.versionName;
                String versionCode = pi.versionCode + "";
                sb.append("App Version: ").append(versionName).append(" (").append(versionCode).append(")\n");
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Error collecting info", e);
        }

        sb.append("OS Version: ").append(Build.VERSION.RELEASE).append("_").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("Vendor: ").append(Build.MANUFACTURER).append("\n");
        sb.append("Model: ").append(Build.MODEL).append("\n");
        sb.append("CPU ABI: ").append(Build.CPU_ABI).append("\n");

        return sb.toString();
    }

    // 保存错误信息到文件中
    // 保存错误信息到文件中 (双重备份版：同时写入私有目录和公共目录)
    private void saveCrashInfo2File(Throwable ex, String deviceInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("====== CRASH LOG ======\n");
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String time = format.format(new Date());
        sb.append("Time: ").append(time).append("\n");
        sb.append(deviceInfo);
        sb.append("\n====== STACK TRACE ======\n");

        Writer writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);
        ex.printStackTrace(printWriter);
        Throwable cause = ex.getCause();
        while (cause != null) {
            cause.printStackTrace(printWriter);
            cause = cause.getCause();
        }
        printWriter.close();
        String result = writer.toString();
        sb.append(result);
        sb.append("\n=======================\n");

        // 准备文件名和内容
        String logContent = sb.toString();
        String fileName = "crash-" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()) + ".log";

        // === 1. 写入 App 私有目录 (Android/data/.../files/CrashLogs) ===
        // 这是保底方案，几乎总是能成功的
        try {
            File privateDir = new File(mContext.getExternalFilesDir(null), "CrashLogs");
            if (!privateDir.exists()) privateDir.mkdirs();

            File privateFile = new File(privateDir, fileName);
            FileOutputStream fos = new FileOutputStream(privateFile);
            fos.write(logContent.getBytes());
            fos.close();
            Log.i(TAG, "Private Log saved: " + privateFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save private log", e);
        }

        // === 2. 写入公共下载目录 (Download/NbtEditor_Data/Crash_Logs) ===
        // 方便用户直接查看，但可能会因为权限问题失败
        try {
            File publicDir = new File("/storage/emulated/0/Download/NbtEditor_Data/Crash_Logs/");
            if (!publicDir.exists()) publicDir.mkdirs();

            File publicFile = new File(publicDir, fileName);
            FileOutputStream fos = new FileOutputStream(publicFile);
            fos.write(logContent.getBytes());
            fos.close();
            Log.i(TAG, "Public Log saved: " + publicFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save public log (Permission denied?)", e);
        }
    }
}