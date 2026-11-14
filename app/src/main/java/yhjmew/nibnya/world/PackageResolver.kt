package yhjmew.nibnya.world

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/**
 * 按「应用包名」定位 Minecraft 存档。
 *
 * 为什么需要
 * ----------
 * 原来路径写死 `com.mojang.minecraftpe`，但现实里存在大量变体：
 *   · 共存版 / 修改版（包名被改，但存档结构完全一致）
 *   · 某些渠道版
 *   · 用户的整合包把数据放在别的包名目录下
 * 用户只能手工去「自定义路径」里敲一长串包名，很容易敲错。
 *
 * 这个类只做一件事：**列出带存档目录的应用**，让用户从列表里点。
 *
 * ⚠️ 关于「权限」
 * --------------
 * 枚举应用列表**不需要任何权限**：包可见性限制（Android 11）只约束
 * targetSdk >= 30 的应用，本项目是 28，getInstalledApplications() 直接可用。
 * （这也是 Hail / 冰箱这类应用的做法。）
 *
 * 但「这个应用的存档目录里有没有东西」是另一回事：
 * `/Android/data/<pkg>/...` 在 Android 11+ 上，普通应用连 stat 都不允许，
 * 必须靠 Shizuku 或 root。所以 [scan] 把两者分开：
 *   · 应用列表   —— 永远可靠，无权限
 *   · 存档是否存在 —— 能验证就验证，不能验证就标成「未验证」仍然列出
 * 绝不能因为验证不了，就把用户的存档藏起来。
 *
 * ⚠️ 只读本机 PackageManager，不联网、不上传任何东西。
 */
object PackageResolver {

    /**
     * 一个候选应用。
     *
     * [worldState] 说明我们对「它是否真有存档」的把握程度 ——
     * 这三态是本次改动最核心的部分，UI 必须如实呈现。
     */
    data class Candidate(
        val packageName: String,
        val label: String,
        /** 安装时间（毫秒）；拿不到为 0 */
        val installedAt: Long,
        /** 最近修改时间（毫秒）；无法验证时为 0 */
        val lastModified: Long,
        /** 外部路径（带尾斜杠） */
        val externalBase: String,
        /** 内部路径（带尾斜杠），仅 root 有意义 */
        val internalBase: String,
        /** 是否为当前正在使用的包名（打「当前」标签） */
        val isCurrent: Boolean = false,
        /** 对「是否真有存档」的把握 */
        val worldState: WorldState = WorldState.UNKNOWN
    ) {
        val preferredBase: String get() = externalBase
    }

    enum class WorldState {
        /** 已确认：目录里确实有 level.dat + db/ */
        VERIFIED,
        /** 没法验证：无提权时 Android 11+ 看不到 Android/data，不代表没有 */
        UNKNOWN,
        /** 能验证但确实没有（目录不存在 / 空目录） */
        ABSENT
    }

    /** 外部私有目录（Android/data 下，Android 11+ 需要提权才能读内容） */
    fun externalBase(pkg: String): String =
        "/storage/emulated/0/Android/data/$pkg/files/games/com.mojang/minecraftWorlds/"

    /** 应用自己的私有目录（只有 root 能进） */
    fun internalBase(pkg: String): String =
        "/data/user/0/$pkg/games/com.mojang/minecraftWorlds/"

    /** 个别变体把目录直接放在 files/ 下 */
    private fun externalVariantBase(pkg: String): String =
        "/storage/emulated/0/Android/data/$pkg/files/minecraftWorlds/"

    /**
     * 枚举本机应用，挑出「可能有存档」的那些。
     *
     * 返回规则（无提权时最容易出错的地方）：
     *   · 提权可用 → 只返回 VERIFIED 的（精确，列表短）
     *   · 提权不可用 → 返回 VERIFIED + 所有疑似游戏应用（标 UNKNOWN）
     *     因为此时**根本没法验证**，若按「验证不了就丢掉」处理，
     *     列表会永远是空的 —— 那正是上一版的 bug。
     *
     * @param includeInternal 是否也检查 /data/user/0 下的私有目录（需要 root）
     * @param prober         单点探测（走提权）；null 表示无提权
     * @param bulkProber     批量探测（一条命令）；null 表示无提权
     */
    fun scan(
        context: Context,
        includeInternal: Boolean,
        excludeSelf: Boolean = true,
        prober: ((String) -> Boolean)? = null,
        bulkProber: (() -> Set<String>?)? = null
    ): List<Candidate> {
        val pm = context.packageManager
        val self = context.packageName
        val privileged = prober != null && bulkProber != null

        val packages = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (_: Exception) {
            emptyList()
        }

        /*
         * 性能：不能对每个应用都跑一次提权命令。
         * 手机上通常 200+ 应用，逐个查就是几百次进程创建。
         * 先跑一条 ls 把「哪些包名目录下确实有 minecraftWorlds」拿回来。
         */
        var bulkHint: Set<String>? = if (privileged) bulkProber?.invoke() else null

        // 批量结果为空时先别急着信：可能是命令在某台机器上不生效
        if (bulkHint != null && bulkHint.isEmpty()) {
            val probePkg = PackagePrefs.defaultPackage(context)
                ?: packageOfPath(PackagePrefs.effectiveBase(context))
            if (probePkg != null && prober?.invoke(externalBase(probePkg)) == true) {
                bulkHint = null   // 批量不可信 → 退回逐条探测
            }
        }

        /** 判定某个目录：VERIFIED / ABSENT / UNKNOWN */
        fun stateOf(path: String): WorldState {
            // ① 有批量结果：查表即可（快且准）
            if (bulkHint != null) {
                val pkg = packageOfPath(path)
                return if (pkg != null && bulkHint!!.contains(pkg)) WorldState.VERIFIED
                       else WorldState.ABSENT
            }
            // ② 单点提权探测
            if (prober != null) {
                return if (prober.invoke(path)) WorldState.VERIFIED else WorldState.ABSENT
            }
            // ③ 无提权：File API 能看见就用它，看不见就是「不知道」
            return when (checkByFile(path)) {
                true -> WorldState.VERIFIED
                false -> WorldState.ABSENT
                null -> WorldState.UNKNOWN
            }
        }

        val out = mutableListOf<Candidate>()
        for (info in packages) {
            val pkg = info.packageName ?: continue
            if (excludeSelf && pkg == self) continue

            val ext = externalBase(pkg)
            val variant = externalVariantBase(pkg)

            val sExt = stateOf(ext)
            val sVariant = stateOf(variant)
            val sInt = if (includeInternal) stateOf(internalBase(pkg)) else WorldState.ABSENT

            val best = listOf(sExt, sVariant, sInt).let {
                when {
                    it.any { s -> s == WorldState.VERIFIED } -> WorldState.VERIFIED
                    // 只有「全都确认不存在」才算 ABSENT；有一个不知道就是 UNKNOWN
                    it.all { s -> s == WorldState.ABSENT } -> WorldState.ABSENT
                    else -> WorldState.UNKNOWN
                }
            }

            /*
             * 无提权时怎么筛？
             *
             * 不能只留 VERIFIED —— 那样在 Android 11+ 上恒为空。
             * 也不该把所有 200+ 应用都列出来 —— 用户找不到重点。
             *
             * 折中：按「是不是游戏类应用」做粗筛（见 looksLikeGame），
             * 把可能是游戏的标成 UNKNOWN 列出来，让用户自己点。
             * 点错了也只是路径不对，不会损坏任何东西。
             */
            val keep = when {
                best == WorldState.VERIFIED -> true
                best == WorldState.ABSENT -> false
                privileged -> false          // 提权可用却没验证到 → 确实没有
                else -> looksLikeGame(info)  // 无提权 → 疑似游戏就列出来
            }
            if (!keep) continue

            val label = try {
                pm.getApplicationLabel(info).toString()
            } catch (_: Exception) {
                pkg
            }

            var installedAt = 0L
            try {
                installedAt = pm.getPackageInfo(pkg, 0).firstInstallTime
            } catch (_: Exception) {}

            // 只有验证过的才读得出时间；UNKNOWN 保持 0，UI 会显示「未验证」
            val newest = if (best == WorldState.VERIFIED) {
                listOf(ext, variant, internalBase(pkg))
                    .maxOfOrNull { newestWorldTime(it) } ?: 0L
            } else 0L

            out.add(
                Candidate(
                    packageName = pkg,
                    label = label,
                    installedAt = installedAt,
                    lastModified = newest,
                    externalBase = ext,
                    internalBase = internalBase(pkg),
                    isCurrent = pkg == currentPackageName(PackagePrefs.effectiveBase(context)),
                    worldState = best
                )
            )
        }

        // 确认过的排前面，其余按名称
        out.sortWith(
            compareBy(
                { if (it.worldState == WorldState.VERIFIED) 0 else 1 },
                { it.label.lowercase() },
                { it.packageName }
            )
        )
        return out
    }

    /**
     * 无提权时用 File API 判断目录内容。
     *
     * @return true=确认真有存档；false=确认没有；null=看不到（权限受限，无法判断）
     *
     * ⚠️ 关键在于区分「空」和「看不见」：
     *    Android 11+ 下 listFiles() 返回 null 可能是权限问题，
     *    这时绝不能当成「没有」，否则会把用户的存档藏起来。
     */
    private fun checkByFile(path: String): Boolean? {
        return try {
            val f = File(path)
            if (!f.exists()) {
                // 父目录是否可见？可见才算「真的不存在」，否则是「看不到」
                val parent = f.parentFile
                if (parent != null && parent.exists()) false else null
            } else if (!f.isDirectory) {
                false
            } else {
                val kids = f.listFiles()
                if (kids == null) null            // 列不出来 → 权限受限
                else kids.any { dir ->
                    dir.isDirectory &&
                        File(dir, "level.dat").exists() &&
                        File(dir, "db").exists()
                }
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 粗筛「可能是 Minecraft」的应用（仅无提权时使用）。
     *
     * 目的不是精确，而是把 200+ 应用压到几十个以内让用户好找。
     * 判据都用 PackageManager 能直接拿到的字段，不读任何文件。
     */
    private fun looksLikeGame(info: android.content.pm.ApplicationInfo): Boolean {
        // ① 包名里带明显的 Minecraft 痕迹
        val pkg = info.packageName.lowercase()
        if (pkg.contains("minecraft") || pkg.contains("mojang")) return true

        // ② 已知的存档相关包名
        if (pkg.startsWith("com.mojang.")) return true

        // ③ 这个应用自己是否声明了「游戏」类别
        return try {
            info.category == android.content.pm.ApplicationInfo.CATEGORY_GAME
        } catch (_: Exception) {
            false
        }
    }

    /** 该根目录下最新一次存档修改时间 */
    private fun newestWorldTime(base: String): Long {
        return try {
            File(base).listFiles()
                ?.filter { it.isDirectory }
                ?.maxOfOrNull { File(it, "level.dat").lastModified() } ?: 0L
        } catch (_: Exception) { 0L }
    }

    /**
     * 从任意路径里反解包名。
     *
     * 支持的两种形式：
     *   /storage/emulated/0/Android/data/<pkg>/...
     *   /data/user/0/<pkg>/...   或   /data/data/<pkg>/...
     *
     * 自定义路径可能不属于任何应用（例如 Download 下的导出目录），此时返回 null。
     */
    fun packageOfPath(path: String?): String? {
        val p = path ?: return null
        val markers = listOf("/Android/data/", "/data/user/0/", "/data/data/")
        for (marker in markers) {
            val idx = p.indexOf(marker)
            if (idx < 0) continue
            val rest = p.substring(idx + marker.length)
            val pkg = rest.split("/").firstOrNull()
            if (!pkg.isNullOrBlank()) return pkg
        }
        return null
    }

    /** 当前生效的包名（从当前路径反解） */
    fun currentPackageName(path: String?): String? = packageOfPath(path)
}
