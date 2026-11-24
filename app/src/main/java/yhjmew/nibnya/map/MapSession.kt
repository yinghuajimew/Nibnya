package yhjmew.nibnya.map

import android.content.Context
import yhjmew.nibnya.db.PlayerDbManager
import yhjmew.nibnya.util.AppLogger
import yhjmew.nibnya.world.WorldViewModel
import java.io.File

/**
 * 只读打开一个世界，提供它的地形瓦片。
 *
 * ## 为什么还是要拷贝
 * [PlayerDbManager] 打开前会删 `LOCK` / `LOG` / `LOG.old`。
 * 直接对着**原存档目录**打开会动到 Minecraft 自己的文件，
 * 所以只读查看也走「复制到 Works/」——
 * 与 [WorldViewModel.loadPlayerData] 现有的编辑流程完全一致。
 *
 * 好处是将来要做地形编辑时，读写用的是同一个工作副本，
 * 不需要换一套 API。
 *
 * ## 范围（2026-11-19 改）
 * 以前 [render] 一次性画固定半径的一张图，超出就滑不出去。
 * 现在改由 [createTiles] 返回一个 [SaveTiles]，**滑到哪渲染到哪**，
 * 没有边界。[render] 保留给「导出整图」之类的场景用。
 */
class MapSession private constructor(
    val workDir: File,
    val worldFolder: String,
    /** 原世界目录，用来读 level.dat 的出生点。 */
    private val worldDir: File,
) {
    private var db: PlayerDbManager? = null
    private var seedSource: SeedTerrainSource? = null

    companion object {
        /**
         * 读世界出生点对应的区块坐标。
         *
         * 实测「我的世界.mcworld」：出生点方块 (-113,-99) → 区块 (-8,-7)，
         * 它周围 5×5 区块是满的；而原点 (0,0) 周围 5×5 只有 8 个区块有数据。
         * 所以中心必须跟出生点，不能写死原点 —— 否则大半是黑块。
         *
         * @return 区块坐标；读不到返回 null
         */
        private fun spawnChunkOf(worldDir: File): Pair<Int, Int>? = try {
            val json = yhjmew.nibnya.nbt.BedrockParser.parse(File(worldDir, "level.dat").absolutePath)
                ?: return null
            // BedrockParser 返回的结构里，数值字段是 {"t":3,"v":值}
            fun intOf(key: String): Int? = json.get(key)?.let { el ->
                if (!el.isJsonObject) return@let null
                val v = el.asJsonObject.get("v") ?: return@let null
                if (v.isJsonPrimitive) runCatching { v.asInt }.getOrNull() else null
            }
            val sx = intOf("SpawnX") ?: return null
            val sz = intOf("SpawnZ") ?: return null
            /*
             * ⚠️ SpawnX/SpawnZ 可能是 Int.MIN_VALUE（-2147483648），
             * 表示「未设置」。不挡掉的话 floorDiv 会算出
             * 区块坐标 -134217728 —— 那里什么都没有，地图一片空白，
             * 而且不会报错，非常难查。
             * 这里直接当作「读不到出生点」，交给包围盒兜底。
             */
            if (sx == Int.MIN_VALUE || sz == Int.MIN_VALUE) return null
            // 同理挡掉明显不合理的坐标（超出世界边界几个数量级）
            if (sx !in -30_000_000..30_000_000 || sz !in -30_000_000..30_000_000) return null
            // 方块坐标 → 区块坐标（向下取整，负数不能直接整除）
            Pair(floorDiv(sx, 16), floorDiv(sz, 16))
        } catch (_: Throwable) {
            null
        }

        /**
         * 向下取整除法。
         *
         * ⚠️ 不用 `Math.floorDiv` —— 它是 **API 24** 才有的，
         * 而本项目 minSdk = 23。出生点坐标多半是负数，
         * 直接用 `/` 会向零取整得到错误的区块（-113/16 = -7，而正确答案是 -8），
         * 地图中心会偏一格区块，且只在特定存档复现。
         */
        fun floorDiv(a: Int, b: Int): Int {
            val q = a / b
            return if (a % b != 0 && (a xor b) < 0) q - 1 else q
        }

        /**
         * 复制世界 db 到工作目录。
         *
         * 复用 [WorldViewModel] 的目录与权限判定：
         * - 能直接读就普通复制
         * - 读不了就用 Shizuku 走桥接目录
         */
        suspend fun open(
            ctx: Context,
            vm: WorldViewModel,
            worldFolder: String,
        ): MapSession {
            val worldDir = File(vm.resolveWorldPathPublic(worldFolder))
            val srcDb = File(worldDir, "db")

            // 打开前修一次归属：老版本留下的 root:root 坏档靠这个救
            try {
                vm.repairWorldOwnership(srcDb.absolutePath)
            } catch (_: Throwable) {
            }

            val uniqueId = System.currentTimeMillis().toString()
            val workDir = File(vm.worksDir(ctx), "map_db_$uniqueId")
            if (!workDir.exists()) workDir.mkdirs()

            /*
             * ⚠️ 「能列出文件」不等于「能读文件」。
             *
             * Android 11+ 的分区存储下，`Android/data/<包名>/...` 里的目录
             * 可以 listFiles()，但里面文件的内容读不了。以前这里只判断
             * `listFiles() != null` 就走普通复制，结果复制出空壳 db，
             * 一路"成功"到最后才报「没有任何区块数据」。
             *
             * 现在按**实际复制成功的文件数**判定：一个都没读到，
             * 就当作读不了，转 Shizuku。
             */
            val copied = if (srcDb.exists() && srcDb.isDirectory) {
                copyTree(srcDb, workDir)
            } else {
                0
            }

            val empty = workDir.listFiles().isNullOrEmpty() ||
                (workDir.listFiles()?.none { it.isFile } ?: true)

            if (copied == 0 || empty) {
                AppLogger.warn(
                    "MapSession",
                    "普通复制得到空库（copied=$copied），改走 Shizuku：${srcDb.absolutePath}"
                )
                // 清掉半成品，避免和 Shizuku 的结果混在一起
                try {
                    workDir.deleteRecursively()
                    workDir.mkdirs()
                } catch (_: Exception) {
                }
                copyViaShizuku(vm, srcDb, workDir)
            }

            val fileCount = workDir.listFiles()?.count { it.isFile } ?: 0
            AppLogger.info(
                "MapSession",
                "打开世界 $worldFolder：src=$srcDb copied=$copied 工作副本=$fileCount 个文件"
            )
            if (fileCount == 0) {
                throw IllegalStateException("数据库目录为空，无法读取地形：${srcDb.absolutePath}")
            }
            return MapSession(workDir, worldFolder, worldDir)
        }

        /**
         * 普通目录复制。保留子目录（LevelDB 可能有 .sst 分片）。
         *
         * @return 实际复制成功的文件数；0 表示一个都没读到
         */
        private fun copyTree(src: File, dst: File): Int {
            if (!dst.exists()) dst.mkdirs()
            val children = src.listFiles() ?: return 0
            var ok = 0
            for (f in children) {
                val target = File(dst, f.name)
                if (f.isDirectory) {
                    ok += copyTree(f, target)
                } else {
                    try {
                        f.inputStream().use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        ok++
                    } catch (e: Exception) {
                        /*
                         * ⚠️ 这里以前只 warn 一句就继续，导致一个很阴的 bug：
                         *
                         * Android 11+ 分区存储下，`Android/data/<包名>/.../db`
                         * 这个**目录**可以 listFiles()，但里面每个**文件**都读不了
                         * （EACCES）。于是每个文件都在这里失败、被跳过，
                         * 复制出来的是一个"结构完整、内容全空"的 db 目录。
                         *
                         * 后续全都能"成功"：workDir.listFiles() 非空（有条目）、
                         * PlayerDbManager 能打开空库、ChunkIndex.scan 扫到 0 个区块
                         * -> 界面报「这个维度没有任何区块数据」。
                         *
                         * 表现为「不管换哪个世界地图都是空的」，且日志里
                         * 只有一行不起眼的 warn —— 极难定位。
                         * 所以复制失败必须计数上报，不能吞。
                         */
                        AppLogger.warn("MapSession", "复制失败 ${f.name}: ${e.message}")
                    }
                }
            }
            return ok
        }

        /**
         * Shizuku 兜底：先 cp 到桥接目录，再读回私有目录。
         *
         * 与 [WorldViewModel.loadPlayerData] 同一套路 ——
         * 它已经处理了 mkdir / chmod / 清空 这些细节。
         */
        private fun copyViaShizuku(vm: WorldViewModel, srcDb: File, workDir: File) {
            // 直接用 MainActivity 的常量，避免两处硬编码路径漂移
            val bridgeRoot = yhjmew.nibnya.MainActivity.BRIDGE_ROOT
            val bridge = File(bridgeRoot, "map_load_${System.currentTimeMillis()}")
            try {
                vm.runShizukuCmd(arrayOf("sh", "-c", "mkdir -p \"$bridgeRoot\"")).waitFor()
                vm.runShizukuCmd(arrayOf("sh", "-c", "rm -rf \"${bridge.absolutePath}\"")).waitFor()
                vm.runShizukuCmd(arrayOf("sh", "-c", "mkdir -p \"${bridge.absolutePath}\"")).waitFor()

                val cp = vm.runShizukuCmd(
                    arrayOf("sh", "-c", "cp -rf \"${srcDb.absolutePath}/.\" \"${bridge.absolutePath}/\"")
                )
                if (cp.waitFor() != 0) {
                    throw IllegalStateException("Shizuku 复制数据库失败")
                }
                vm.runShizukuCmd(arrayOf("sh", "-c", "chmod -R 777 \"${bridge.absolutePath}\"")).waitFor()

                copyTree(bridge, workDir)
            } finally {
                try {
                    vm.runShizukuCmd(arrayOf("sh", "-c", "rm -rf \"${bridge.absolutePath}\"")).waitFor()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** 瓦片图层。第一次 [createTiles] 时建立，[close] 时销毁。 */
    private var tiles: SaveTiles? = null

    /**
     * 取本会话的瓦片图层（单例）。
     *
     * 同一个世界只应该有一套瓦片缓存 —— 每次切页面都新建的话
     * 20 MB 的位图缓存会反复分配/回收，明显掉帧。
     */
    @Synchronized
    fun createTiles(ctx: Context): SaveTiles {
        tiles?.let { return it }
        val reader = ChunkReader(database().rawDb())
        val created = SaveTiles(
            reader = reader,
            colors = BlockColorTable.get(ctx),
            cacheBytes = MapSettings.cacheMb(ctx) * 1024 * 1024,
            threadCount = MapSettings.threads(ctx),
            chunkEntries = MapSettings.chunkEntries(ctx),
        )
        tiles = created
        return created
    }

    /**
     * 读世界种子和存档版本，建出种子地形层。
     *
     * 读不到种子（老存档、损坏的 level.dat）时返回 null，
     * 调用方应当把底图留空 —— 而不是画一张随机地形骗人。
     */
    @Synchronized
    fun createSeedSource(ctx: Context): SeedTerrainSource? {
        seedSource?.let { return it }
        if (!SeedTerrainSource.available) return null
        // 用户可以在设置里关掉（低端机省 CPU）
        if (!MapSettings.seedLayerEnabled(ctx)) return null

        val meta = readLevelMeta() ?: return null
        val created = SeedTerrainSource(meta.seed, meta.mcLevel, 0)
        seedSource = created
        return created
    }

    /**
     * 切维度：存档层和种子层要一起切，否则一个还画着主世界、
     * 一个已经画下界，两层叠在一起完全是错位的地形。
     */
    @Synchronized
    fun setDimension(dim: Int) {
        tiles?.dimension = dim
        seedSource?.setDimension(dim)
    }

    /** level.dat 里地图需要的两个字段。 */
    private class LevelMeta(val seed: Long, val mcLevel: Int)

    private fun readLevelMeta(): LevelMeta? = try {
        val json = yhjmew.nibnya.nbt.BedrockParser.parse(File(worldDir, "level.dat").absolutePath)
            ?: return null

        fun primitive(key: String) = json.get(key)?.takeIf { it.isJsonObject }
            ?.asJsonObject?.get("v")?.takeIf { it.isJsonPrimitive }

        /*
         * ⚠️ RandomSeed 是 Long，但 BedrockParser 把 t=4 解成 JSON number，
         * 超出 2^53 时双击精度会丢。Minecraft 的世界种子是任意 64 位值，
         * 所以这里直接用 asString 再转 Long，避免经过 double。
         */
        val seedEl = primitive("RandomSeed") ?: return null
        val seed = runCatching { seedEl.asString.toLong() }.getOrNull() ?: return null

        // InventoryVersion 是实际存档版本（"1.26.52"）；
        // 老存档可能只有 lastOpenedWithVersion（int[5]），那种情况退回最新档。
        val verEl = json.get("InventoryVersion")?.takeIf { it.isJsonObject }
            ?.asJsonObject?.get("v")?.takeIf { it.isJsonPrimitive }
        val version = verEl?.asString

        LevelMeta(seed, SeedTerrainSource.fromVersionString(version))
    } catch (_: Throwable) {
        null
    }

    /** 该维度有没有任何区块数据（用于「这个维度是空的」提示）。 */
    fun hasChunkData(dim: Int): Boolean {
        val dbRef = database().rawDb()
        return !ChunkIndex(dbRef).scan(dim).isEmpty
    }

    /**
     * 地图打开时应该居中到哪。
     *
     * 顺序与 [render] 一致：**出生点 → 包围盒中心**。
     * 实测存档的原点周围常常是空的，用包围盒中心也会偏，
     * 所以出生点优先。
     *
     * @return 区块坐标；这个维度完全没有数据时返回 null
     */
    fun anchorChunk(dim: Int = 0): Pair<Int, Int>? {
        spawnChunkOf(worldDir)?.let { return it }
        val bounds = ChunkIndex(database().rawDb()).scan(dim)
        if (bounds.isEmpty) return null
        return Pair(bounds.centerCx, bounds.centerCz)
    }

    /** 打开数据库。已有连接时直接返回。 */
    @Synchronized
    fun database(): PlayerDbManager {
        db?.let { return it }
        val opened = PlayerDbManager(workDir.absolutePath)
        db = opened
        return opened
    }

    @Synchronized
    fun close() {
        // 先停瓦片线程（它会读库 / 调 JNI），再停种子线程，最后关库
        tiles?.shutdown()
        tiles = null
        seedSource?.shutdown()
        seedSource = null
        try {
            db?.close()
        } catch (_: Exception) {
        }
        db = null
    }
}
