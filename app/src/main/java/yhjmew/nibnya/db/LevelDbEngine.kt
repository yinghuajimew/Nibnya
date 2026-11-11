package yhjmew.nibnya.db

import android.content.Context
import yhjmew.nibnya.util.AppLogger

/**
 * 选择用哪个 LevelDB 后端。
 *
 * ## 两个后端
 * | | C++（litl JNI） | Java（iq80） |
 * |---|---|---|
 * | 速度 | 快 | 慢一个数量级 |
 * | 依赖 | 需要对应 ABI 的 .so | 纯 Java，无依赖 |
 * | 内存 | 低 | 高（索引在堆里） |
 * | 打开大存档 | 快 | 可能几十秒 |
 *
 * 默认 C++。用户可以在「设置 → 高级 → 数据库引擎」切到 Java。
 *
 * ## 什么时候会用到 Java
 * - 设备 ABI 不在打包的 .so 里（极老的 armv5、RISC-V、x86 模拟器等）
 * - so 被某些「精简版」安装器裁掉
 * - C++ 版在某个存档上崩溃 / 报错，但 Java 版能读
 *
 * 这类情况下用户连主界面都进不去，所以 Java 版本质上是**逃生通道**。
 */
object LevelDbEngine {

    const val ENGINE_AUTO = "auto"      // 优先 C++，失败自动回退
    const val ENGINE_CPP = "cpp"        // 强制 C++
    const val ENGINE_JAVA = "java"      // 强制 Java

    private const val PREF = "nbt_config"
    private const val KEY = "leveldb_engine"

    /**
     * 全局 application context。
     *
     * `PlayerDbManager` 在构造时拿不到 Context（它只收一个目录路径），
     * 而引擎选择又要读 prefs。所以在 Application/MainActivity 启动时
     * 调一次 [attach]，之后到处都能用。
     */
    @Volatile
    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun get(context: Context): String =
        try {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY, ENGINE_AUTO) ?: ENGINE_AUTO
        } catch (_: Throwable) {
            ENGINE_AUTO
        }

    fun set(context: Context, engine: String) {
        try {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, engine).apply()
            AppLogger.info("LevelDbEngine", "数据库引擎 -> $engine")
        } catch (_: Throwable) {
        }
    }

    /**
     * 某个存档该用哪个后端。
     *
     * 目前不区分存档（全局设置）。留这个参数是为了以后
     * 「某个存档在 C++ 版上打不开」时可以按存档记忆。
     */
    fun current(dbPath: String): String = try {
        val ctx = appContext
        if (ctx == null) ENGINE_AUTO else get(ctx)
    } catch (_: Throwable) {
        ENGINE_AUTO
    }

    /** 给设置页显示用。 */
    fun labelOf(engine: String): String = when (engine) {
        ENGINE_JAVA -> "Java"
        ENGINE_CPP -> "C++"
        else -> "自动"
    }
}
