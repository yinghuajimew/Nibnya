package yhjmew.nibnya.world

import android.content.Context

/**
 * 包名选择的持久化。
 *
 * 两套数据，别混：
 *   · **默认包名**（default_pkg）—— 用户「设过」的那个。
 *     之后每次启动、每次点「按应用选择」，都直接用它的路径，不再问。
 *   · **历史记录**（pkg_history）—— 用过的包名，按最近使用倒序。
 *     只用来给列表排序 / 提供候选，**不代表用户的选择**。
 *
 * 为什么分开：用户可能只是点开看过别人的存档，不代表想把它设成默认。
 * 如果只有一个字段，看一眼就等于改了默认，很容易误伤。
 *
 * 清除时只清默认、保留历史 —— 用户明确要求过。
 */
object PackagePrefs {

    private const val PREF = "nbt_config"
    private const val KEY_DEFAULT = "default_pkg"
    private const val KEY_HISTORY = "pkg_history"
    private const val KEY_USE_INTERNAL = "pkg_use_internal"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /**
     * 供 [MainActivity.PATH_STANDARD]/[PATH_INTERNAL] 这类「静态常量」使用。
     *
     * 那两个路径原本是 const val，但换成可变包名后必须有 Context 才能读偏好。
     * 这里缓存一份 —— 由 [MainActivity] 在 onCreate 里调 [attach] 注入。
     * 没注入时返回 null，调用方回退到默认包名。
     */
    @Volatile
    private var cachedDefault: String? = null

    val defaultPackageForStatic: String?
        get() = cachedDefault

    /** 由 MainActivity 在启动 / 切换包名时调用，刷新静态缓存 */
    fun attach(context: Context) {
        cachedDefault = defaultPackage(context)
    }

    /** 用户设定的默认包名；null 表示没设过 */
    fun defaultPackage(context: Context): String? =
        prefs(context).getString(KEY_DEFAULT, null)?.takeIf { it.isNotBlank() }

    fun setDefaultPackage(context: Context, pkg: String?) {
        prefs(context).edit().apply {
            if (pkg.isNullOrBlank()) remove(KEY_DEFAULT) else putString(KEY_DEFAULT, pkg)
        }.apply()
        cachedDefault = pkg?.takeIf { it.isNotBlank() }
    }

    fun clearDefaultPackage(context: Context) = setDefaultPackage(context, null)

    /** 历史记录（最近使用在前），最多保留 20 条 */
    fun history(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return raw.split('').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 记一笔历史（不改变默认） */
    fun rememberHistory(context: Context, pkg: String) {
        if (pkg.isBlank()) return
        val cur = history(context).toMutableList()
        cur.remove(pkg)
        cur.add(0, pkg)
        val kept = cur.take(20)
        prefs(context).edit().putString(KEY_HISTORY, kept.joinToString("")).apply()
    }

    /** 从历史里删掉一个（比如应用被卸了） */
    fun forgetHistory(context: Context, pkg: String) {
        val cur = history(context).toMutableList()
        if (!cur.remove(pkg)) return
        prefs(context).edit().putString(KEY_HISTORY, cur.joinToString("")).apply()
    }

    /**
     * 是否把「内部存储」（/data/user/0/<pkg>）也纳入路径候选。
     * 只有 root 时才有意义，由 UI 层决定是否显示这个开关。
     */
    fun useInternal(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_INTERNAL, false)

    fun setUseInternal(context: Context, use: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_INTERNAL, use).apply()
    }

    /**
     * 当前应该用哪个路径 —— 供启动时恢复用。
     *
     * 有默认包名就用它的外部路径；没有则返回 null，
     * 由调用方回退到常规的 last_path。
     */
    fun effectiveBase(context: Context): String? {
        val pkg = defaultPackage(context) ?: return null
        // ⚠️ 这里不能靠 File.exists() 判断「外部能不能读」——
        //    Android 11+ 对 Android/data 下的路径恒为 false，
        //    会导致每次都错判成「只能用内部存储」。
        //    直接按用户的选择走：勾了内部才用内部。
        return if (useInternal(context)) {
            PackageResolver.internalBase(pkg)
        } else {
            PackageResolver.externalBase(pkg)
        }
    }
}
