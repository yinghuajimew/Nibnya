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
 * 这个类只做一件事：**列出本机上确实存在存档目录的应用**，
 * 让用户从列表里点，而不是背包名。
 *
 * 扫描范围（三条都试，命中任意一条即算「有存档」）
 *   /storage/emulated/0/Android/data/<pkg>/files/games/com.mojang/minecraftWorlds
 *   /data/user/0/<pkg>/games/com.mojang/minecraftWorlds
 *   /storage/emulated/0/Android/data/<pkg>/files/minecraftWorlds          （个别变体）
 *
 * ⚠️ 只读本机 PackageManager，不联网、不上传任何东西。
 */
object PackageResolver {

    /** 一个候选应用 */
    data class Candidate(
        val packageName: String,
        val label: String,
        /** 安装时间（毫秒）；拿不到为 0 */
        val installedAt: Long,
        /** 最近修改时间（毫秒）；用于判断「最近玩过」 */
        val lastModified: Long,
        /** 命中的外部路径（带尾斜杠）；没有则为 null */
        val externalBase: String?,
        /** 命中的内部路径（带尾斜杠）；没有则为 null */
        val internalBase: String?,
        /** 是否为当前正在使用的包名（打了「当前」标签） */
        val isCurrent: Boolean = false
    ) {
        /** 优先外部路径：不需要 root，能直接读写 */
        val preferredBase: String get() = externalBase ?: internalBase ?: ""
    }

    /** 外部私有目录（Android/data 下，Android 11+ 需要提权） */
    fun externalBase(pkg: String): String =
        "/storage/emulated/0/Android/data/$pkg/files/games/com.mojang/minecraftWorlds/"

    /** 应用自己的私有目录（只有 root 能进） */
    fun internalBase(pkg: String): String =
        "/data/user/0/$pkg/games/com.mojang/minecraftWorlds/"

    /** 个别变体把目录直接放在 files/ 下 */
    private fun externalVariantBase(pkg: String): String =
        "/storage/emulated/0/Android/data/$pkg/files/minecraftWorlds/"

    /**
     * 枚举「本机所有应用」，筛出确实有存档目录的那些。
     *
     * @param includeInternal 是否也检查 /data/user/0 下的私有目录（需要 root）
     * @return 按 A-Z（包名）排序
     */
    fun scan(
        context: Context,
        includeInternal: Boolean,
        excludeSelf: Boolean = true,
        /**
         * 「这个目录下有没有存档」的探测方式。
         *
         * 默认用 File API，但 Android 11+ 对 Android/data 会恒返回 false ——
         * 那种情况下要由调用方传入一个**走提权**的实现（root/Shizuku）。
         * 传 null 就退回 File API。
         */
        prober: ((String) -> Boolean)? = null,
        /**
         * 批量探测：一次性返回「确实有存档目录的包名集合」。
         *
         * 有它就**不再逐条调用 [prober]** —— 这是性能关键：
         * 一条命令 vs 几百条命令。
         */
        bulkProber: (() -> Set<String>?)? = null
    ): List<Candidate> {
        val pm = context.packageManager
        val self = context.packageName

        val packages = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (_: Exception) {
            emptyList()
        }

        /*
         * 性能：不能对每个应用都跑一次提权命令。
         * 普通手机装了 200+ 应用，每个查 2~3 次就是几百次进程创建，要几十秒。
         *
         * 做法：**先问一次**——把「哪些包名目录下确实有 minecraftWorlds」
         * 一次性拿回来（一条 ls 命令），再和包列表求交集。
         * 只有在批量探测不可用时，才逐条退回单点探测。
         */
        // Kotlin 没有三元运算符（C 风格的 a ? b : c 不合法），用 if 表达式
        var bulkHint: Set<String>? = if (prober == null) null else bulkProber?.invoke()

        /*
         * 自检：批量结果为空时先别急着信。
         *
         * 如果用户当前用的包名明明有存档，批量结果却一个都没给出来，
         * 说明那条 ls 在某台机器上不可靠（SELinux / 路径变体等），
         * 此时退回逐条探测——慢一点，但至少不会把列表变成空的。
         */
        if (bulkHint != null && bulkHint.isEmpty()) {
            val probePkg = PackagePrefs.defaultPackage(context)
                ?: packageOfPath(PackagePrefs.effectiveBase(context))
            if (probePkg != null) {
                val probePath = externalBase(probePkg)
                val credible = prober?.invoke(probePath) ?: false
                if (credible) {
                    // 批量结果不可信：丢掉它，走逐条探测
                    bulkHint = null
                }
            }
        }

        val exists = { path: String ->
            when {
                // 有批量结果：直接按包名查表，不再发命令
                bulkHint != null -> {
                    val pkg = packageOfPath(path)
                    pkg != null && bulkHint.contains(pkg)
                }
                prober != null -> prober.invoke(path)
                else -> existsByFile(path)
            }
        }

        val out = mutableListOf<Candidate>()
        for (info in packages) {
            val pkg = info.packageName ?: continue
            if (excludeSelf && pkg == self) continue

            val ext = externalBase(pkg)
            val variant = externalVariantBase(pkg)
            val hasExt = exists(ext) || exists(variant)
            val hasInt = includeInternal && exists(internalBase(pkg))
            if (!hasExt && !hasInt) continue

            val hitExt = when {
                exists(ext) -> ext
                exists(variant) -> variant
                else -> null
            }

            val label = try {
                pm.getApplicationLabel(info).toString()
            } catch (_: Exception) {
                pkg
            }

            var installedAt = 0L
            try {
                installedAt = pm.getPackageInfo(pkg, 0).firstInstallTime
            } catch (_: Exception) {}

            val newest = listOfNotNull(hitExt, if (hasInt) internalBase(pkg) else null)
                .maxOfOrNull { newestWorldTime(it) } ?: 0L

            out.add(
                Candidate(
                    packageName = pkg,
                    label = label,
                    installedAt = installedAt,
                    lastModified = newest,
                    externalBase = hitExt,
                    internalBase = if (hasInt) internalBase(pkg) else null,
                    isCurrent = pkg == currentPackageName(PackagePrefs.effectiveBase(context))
                )
            )
        }
        out.sortWith(compareBy({ it.label.lowercase() }, { it.packageName }))
        return out
    }

    /**
     * 该目录下是否真有存档。
     *
     * 只看目录在不在还不够 —— 有些应用会预建空目录。
     * 这里要求「目录存在 且 里面至少有一个带 level.dat 的子目录」，
     * 或者目录里至少有一个子目录（空目录不列出，免得列表里全是噪声）。
     *
     * 注意：受分区存储限制，File.exists() 对 Android/data 下的路径
     * 在 Android 11+ 会返回 false。此时退回「按路径约定推断」——
     * 有 Shizuku/root 时上层会用提权去实际读取，这里不阻塞用户。
     */
    /**
     * 判定标准与 [WorldViewModel.scanAllWorlds] 的 looksLikeWorld 保持一致：
     * 子目录里必须有 level.dat **和** db/ 才算存档。
     *
     * 只看 level.dat 会把「只剩 level.dat 的残档」也列出来，
     * 用户点进去才发现打不开 —— 不如不列。
     */
    private fun existsByFile(path: String): Boolean {
        return try {
            val f = File(path)
            if (!f.exists() || !f.isDirectory) return false
            f.listFiles()?.any { dir ->
                dir.isDirectory &&
                    File(dir, "level.dat").exists() &&
                    File(dir, "db").exists()
            } == true
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
