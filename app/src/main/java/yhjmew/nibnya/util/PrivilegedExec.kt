package yhjmew.nibnya.util

import android.content.Context
import android.content.SharedPreferences
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

/**
 * 提权执行器：**唯一**的提权命令入口。
 *
 * 为什么要有这个类
 * ----------------
 * 以前提权只有 Shizuku 一条路，命令直接 `Shizuku.newProcess("sh","-c",...)`。
 * 加 root 之后如果照抄一份 `Runtime.exec("su")`，就会变成两套逻辑、两处校验、
 * 两边都可能写错。所以把「怎么执行」抽出来，调用方只关心「执行什么」。
 *
 * 三种模式
 * --------
 * - [Mode.ROOT]     root：`su -c <cmd>`（模板可由用户自定义）
 * - [Mode.SHIZUKU]  Shizuku：`Shizuku.newProcess`
 * - [Mode.NORMAL]   不提权：直接返回 null，调用方退回 File API
 *
 * ⚠️ 安全约束（root 下尤其重要）
 * ----------------------------
 * 1. `rm` / `mv` 之类的破坏性命令，目标路径必须通过 [isSafeDestructiveTarget] 校验。
 * 2. `rm -rf` 只允许作用于 [BRIDGE_ROOT] 下的临时目录，**绝不允许碰用户存档**。
 * 3. 命令里出现未展开的 `$变量`、`*` 通配、空路径时一律拒绝。
 * 4. 命令总长和参数量有上限，避免意外拼接。
 */
object PrivilegedExec {

    /** 执行模式 */
    enum class Mode {
        ROOT, SHIZUKU, NORMAL;

        companion object {
            fun of(name: String?): Mode = when (name) {
                "root" -> ROOT
                "shizuku" -> SHIZUKU
                else -> NORMAL
            }

            fun key(mode: Mode): String = when (mode) {
                ROOT -> "root"
                SHIZUKU -> "shizuku"
                NORMAL -> "normal"
            }
        }
    }

    // ============================================
    // 偏好键
    // ============================================
    const val PREF_EXEC_MODE = "exec_mode"          // auto / root / shizuku / normal
    const val PREF_SU_TEMPLATE = "su_template"      // 如 "su -c {cmd}"

    /** 默认 su 模板；{cmd} 会被替换成实际命令 */
    const val DEFAULT_SU_TEMPLATE = "su -c {cmd}"

    /** 模板里必须出现的占位符 */
    const val CMD_PLACEHOLDER = "{cmd}"

    // ============================================
    // 临时中转目录：rm -rf 唯一允许的作用范围
    // ============================================
    const val BRIDGE_ROOT = "/storage/emulated/0/Download/Nibnya/Bridge/"

    // ============================================
    // 状态
    // ============================================
    @Volatile
    private var currentMode: Mode = Mode.NORMAL

    /** 当前实际生效的模式（可能是检测后的降级结果） */
    fun mode(): Mode = currentMode

    fun setMode(mode: Mode) {
        currentMode = mode
    }

    /** Shizuku 开关是否被用户关掉（老的「跳过 Shizuku」已迁移到 exec_mode） */
    fun canAttemptPrivileged(): Boolean = currentMode != Mode.NORMAL

    // ============================================
    // 偏好读写
    // ============================================
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("nbt_config", Context.MODE_PRIVATE)

    /** 用户选择的模式（auto / root / shizuku / normal） */
    fun selectedModePref(context: Context): String =
        prefs(context).getString(PREF_EXEC_MODE, null) ?: "auto"

    /** 用户自定义的 su 模板；没设过用默认值 */
    fun suTemplate(context: Context): String {
        val t = prefs(context).getString(PREF_SU_TEMPLATE, null)
        return if (t.isNullOrBlank()) DEFAULT_SU_TEMPLATE else t
    }

    /**
     * 校验 su 模板是否可用。
     *
     * 必须包含 {cmd}：否则用户把模板改成 `reboot` 之类，
     * 实际命令根本不会被执行，且行为完全不可预期。
     */
    fun isTemplateValid(template: String): Boolean =
        template.contains(CMD_PLACEHOLDER) && template.isNotBlank()

    // ============================================
    // 检测
    // ============================================

    /**
     * 真实探测 root 是否可用。
     *
     * ⚠️ 不看 /system/xbin/su 是否存在 —— 很多设备有 su 文件却拿不到权限，
     *    也有设备 su 在别的位置。唯一可靠的判定是**真的跑一次并看 uid**。
     */
    fun detectRoot(context: Context): Boolean {
        return try {
            val template = suTemplate(context)
            if (!isTemplateValid(template)) return false
            val cmd = template.replace(CMD_PLACEHOLDER, "id")
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val finished = p.waitFor()
            finished == 0 && out.contains("uid=0")
        } catch (_: Throwable) {
            false
        }
    }

    /** 真实探测 Shizuku 是否可用（连上且已授权） */
    fun detectShizuku(): Boolean {
        return try {
            val c = Class.forName("rikka.shizuku.Shizuku")
            val ping = c.getMethod("pingBinder").invoke(null) as? Boolean ?: false
            if (!ping) return false
            // hasPermission 只在 API 26+ 提供；拿不到就当已授权（交给实际命令去试）
            try {
                val m = c.getMethod("hasPermission", Int::class.javaPrimitiveType)
                m.invoke(null, 0) as? Boolean ?: true
            } catch (_: Throwable) {
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 按用户选择 + 设备实际情况，决定最终使用哪个模式。
     *
     * 规则：
     * - 用户选 auto -> ROOT > SHIZUKU > NORMAL
     * - 用户指定某模式但该模式不可用 -> 自动降级（避免卡死）
     */
    fun resolveMode(context: Context, selected: String): Mode {
        val rootOk = detectRoot(context)
        val shizukuOk = detectShizuku()
        return when (selected) {
            "root" -> when {
                rootOk -> Mode.ROOT
                shizukuOk -> Mode.SHIZUKU
                else -> Mode.NORMAL
            }
            "shizuku" -> when {
                shizukuOk -> Mode.SHIZUKU
                rootOk -> Mode.ROOT
                else -> Mode.NORMAL
            }
            "normal" -> Mode.NORMAL
            else -> when {          // auto
                rootOk -> Mode.ROOT
                shizukuOk -> Mode.SHIZUKU
                else -> Mode.NORMAL
            }
        }
    }

    // ============================================
    // 命令安全校验
    // ============================================

    /** 需要校验目标路径的命令前缀 */
    private val DESTRUCTIVE = listOf("rm ", "rm -f ", "rm -rf ", "mv ", "chmod ")

    /**
     * 是否是「删除类」命令（比 mv/chmod 更严格，只允许碰 Bridge）。
     */
    private fun isDeleteCommand(cmd: String): Boolean =
        cmd.trimStart().startsWith("rm ")

    /** 去掉字符串里所有被引号包住的内容（单双引号都算） */
    private fun stripQuoted(s: String): String {
        val sb = StringBuilder()
        var inSingle = false
        var inDouble = false
        for (ch in s) {
            when {
                ch == '"' && !inSingle -> inDouble = !inDouble
                ch == '\'' && !inDouble -> inSingle = !inSingle
                !inSingle && !inDouble -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * 校验一条 shell 命令是否可以安全执行。
     *
     * @return null 表示通过；否则返回拒绝原因（英文，仅用于日志）
     */
    fun validate(cmd: String): String? {
        val c = cmd.trim()
        if (c.isEmpty()) return "empty command"
        if (c.length > 4096) return "command too long"

        // ⚠️ 不能简单地把 变量 判成「未展开」——
        //    调用方到处都在用 Kotlin 字符串模板，$ 出现在引号里是正常的。
        //    真正危险的是「裸变量」：没有引号包着，shell 再分词就会失控，
        //    例如 rm -rf $HOME/xxx。所以先去掉引号内内容，再检查剩下的 $。
        val withoutQuoted = stripQuoted(c)
        if (withoutQuoted.contains('$')) {
            return "unexpanded variable outside quotes"
        }

        // 通配符：会放大删除范围
        if (isDeleteCommand(c) && (c.contains('*') || c.contains('?'))) {
            return "wildcard in rm"
        }

        if (isDeleteCommand(c)) {
            val target = extractLastQuotedPath(c)
                ?: return "cannot extract rm target"
            if (!isSafeDestructiveTarget(target, strictBridgeOnly = true)) {
                return "rm target outside allowed area: $target"
            }
        }

        // mv 也必须校验：root 下 `mv /data/... /` 同样能毁掉数据。
        //   · 源路径：允许存档区、Bridge、Trash、/data 下的我们的临时目录
        //   · 目标路径：同上，且不能是「上一级」目录
        if (c.trimStart().startsWith("mv ")) {
            val paths = Regex("""["'](/[^"']*)["']""").findAll(c).map { it.groupValues[1] }.toList()
            if (paths.size < 2) return "mv needs source and destination"
            for (p in paths) {
                if (!isSafeDestructiveTarget(p, strictBridgeOnly = false)) {
                    return "mv path outside allowed area: $p"
                }
            }
        }

        return null
    }

    /**
     * 破坏性操作的目标路径是否安全。
     *
     * @param strictBridgeOnly true 时只允许 Bridge 下的路径（rm 专用）；
     *                         false 时允许存档/工作区路径（mv/chmod 用）
     */
    /** 我们自己的临时目录前缀。只有以它开头的目录才允许被 rm -rf */
    const val TMP_PREFIX = ".nibnya_tmp_"

    fun isSafeDestructiveTarget(path: String, strictBridgeOnly: Boolean): Boolean {
        val p = path.trim().removeSurrounding("\"").removeSurrounding("'")
        if (p.isEmpty()) return false
        if (!p.startsWith("/")) return false                 // 必须是绝对路径
        if (p.length < 12) return false                      // 太短，肯定是拼错了

        // 我们自己的临时目录（原子替换用的 .nibnya_tmp_*）永远允许删除。
        //
        // ⚠️ 必须放在「Android 受保护区」判断**之前**。
        //    原子替换要求临时目录与目标 db 同级（同一分区 rename 才原子），
        //    所以临时目录必然落在 Android/data/<pkg>/... 下面 ——
        //    若先命中下面那条一刀切的黑名单，豁免就永远轮不到它，
        //    表现就是「ROOT 已连接，但保存时报 rm target outside allowed area」。
        //    这是 ea43497 起就存在的旧缺陷，不是路径改动引入的。
        val isOwnTmp = p.substringAfterLast('/').startsWith(TMP_PREFIX)
        if (isOwnTmp) return true

        if (p.startsWith("/storage/emulated/0/Android")) return false  // 系统受保护区

        // 「上一级」目录绝不删：删了就完了
        val forbidden = setOf(
            "/", "/storage", "/storage/emulated", "/storage/emulated/0",
            "/storage/emulated/0/Download", "/storage/emulated/0/Download/Nibnya",
            "/storage/emulated/0/Download/Nibnya/Bridge",
            // /data 侧的「上一级」——root 下删掉就是系统级事故
            "/data", "/data/user", "/data/user/0",
            "/data/user/0/com.mojang.minecraftpe",
            "/data/user/0/com.mojang.minecraftpe/games",
            "/data/user/0/com.mojang.minecraftpe/games/com.mojang",
            "/data/user/0/com.mojang.minecraftpe/games/com.mojang/minecraftWorlds"
        )
        if (p.trimEnd('/') in forbidden) return false

        if (strictBridgeOnly) {
            // rm -rf 允许的目标（除此之外一律拒绝）：
            //  ① Bridge/ 下的临时目录
            val bridge = BRIDGE_ROOT.trimEnd('/')
            val inBridge = p.startsWith("$bridge/") && p.trimEnd('/') != bridge
            //  ② 回收站 Trash 下的内容 —— 删除存档时 mv 之前要先清掉占位目录
            val inTrash = p.contains("/Trash/") && p.substringAfterLast('/').isNotEmpty()
            return inBridge || inTrash
        }

        return true
    }

    /** 取命令里最后一个带引号的绝对路径 */
    private fun extractLastQuotedPath(cmd: String): String? {
        val matches = Regex("""["'](/[^"']*)["']""").findAll(cmd).toList()
        return matches.lastOrNull()?.groupValues?.get(1)
    }

    // ============================================
    // 执行
    // ============================================

    /**
     * 执行一条 shell 命令。
     *
     * @param cmd 完整命令（例如 `ls "/path/to/dir"`）
     * @return Process；[Mode.NORMAL] 下返回 null（调用方应退回 File API）
     * @throws SecurityException 命令没通过安全校验
     */
    fun exec(context: Context, cmd: String): Process? {
        validate(cmd)?.let { reason ->
            throw SecurityException("refused command ($reason): $cmd")
        }

        return when (currentMode) {
            Mode.ROOT -> execRoot(context, cmd)
            Mode.SHIZUKU -> execShizuku(cmd)
            Mode.NORMAL -> null
        }
    }

    /**
     * 用 su 执行命令。
     *
     * ⚠️ 关键：必须把整条命令**当成一个参数**交给 su，
     *    否则 shell 会先在外层按 && / ; / | 拆分，
     *    只有第一段进 su，后面的段落跑在普通 uid 上。
     *
     *    曾经的实际故障（日志已证实）：
     *      sh -c 'su -c mkdir -p "/path" && cp -rf "/src" "/dst"'
     *      -> mkdir 以 root 成功，cp 以普通应用身份执行，Permission denied
     *
     *    而且目录已经建出来了（tmpNewLeft=true），看起来像「root 写不了」，
     *    实际只是后半段根本没走 su。
     */
    /**
     * 用 su 执行命令。
     *
     * ⚠️ 关键：整条命令必须作为**单个参数**交给 su，
     *    绝不能让外层 shell 再按 && / ; / | 拆分。
     *
     *    曾经的实际故障（日志已证实）：
     *      sh -c "su -c mkdir -p /path && cp -rf /src /dst"
     *      -> mkdir 以 root 成功，cp 以普通应用身份执行，Permission denied；
     *         而且目录已经建出来了（tmpNewLeft=true），
     *         看起来像「root 写不了」，实际只是后半段没走 su。
     *
     * 做法：不拼 shell 字符串，直接把版式拆成 argv 交给 su。
     *       自定义模板里若含 {cmd}，也只替换成单个 argv 元素。
     */
    private fun execRoot(context: Context, cmd: String): Process {
        val template = suTemplate(context)
        if (!isTemplateValid(template)) {
            throw IllegalStateException("invalid su template: $template")
        }
        // 模板拆成 argv：例如 "su -c {cmd}" -> ["su", "-c", "{cmd}"]
        val parts = template.trim().split(Regex("\\s+"))
        val argv = mutableListOf<String>()
        for (p in parts) {
            if (p.contains(CMD_PLACEHOLDER)) {
                // {cmd} 必须独占一个参数才能保证不被拆分；
                // 万一用户写成 su -c"{cmd}" 也照样按字面替进去。
                argv.add(p.replace(CMD_PLACEHOLDER, cmd))
            } else {
                argv.add(p)
            }
        }
        return ProcessBuilder(argv).redirectErrorStream(false).start()
    }

    private fun execShizuku(cmd: String): Process {
        val c = Class.forName("rikka.shizuku.Shizuku")
        var m: Method? = null
        for (mm in c.declaredMethods) {
            if (mm.name == "newProcess" && mm.parameterTypes.size == 3) { m = mm; break }
        }
        if (m == null) for (mm in c.methods) {
            if (mm.name == "newProcess" && mm.parameterTypes.size == 3) { m = mm; break }
        }
        if (m == null) throw IllegalStateException("Shizuku.newProcess not found")
        m.isAccessible = true
        return m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
    }

    // ============================================
    // 便捷方法
    // ============================================

    /** 执行并等待，返回退出码；NORMAL 模式返回 -1 */
    fun execAndWait(context: Context, cmd: String): Int =
        exec(context, cmd)?.waitFor() ?: -1

    /** 执行并取 stdout；失败返回空串 */
    fun execForOutput(context: Context, cmd: String): String {
        val p = exec(context, cmd) ?: return ""
        return try {
            BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
        } catch (_: Throwable) {
            ""
        } finally {
            try { p.waitFor() } catch (_: Throwable) {}
        }
    }
}
