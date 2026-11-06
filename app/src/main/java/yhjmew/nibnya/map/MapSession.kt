package yhjmew.nibnya.map

import android.content.Context
import android.graphics.Bitmap
import yhjmew.nibnya.db.PlayerDbManager
import yhjmew.nibnya.util.AppLogger
import yhjmew.nibnya.world.WorldViewModel
import java.io.File

/**
 * 只读打开一个世界，渲染它的一块地形图。
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
 * ## 范围
 * 第一版只渲染固定区块矩形（默认半径 [DEFAULT_RADIUS_CHUNKS]），
 * 不做全图扫描。理由：全图要遍历整库求包围盒，大存档会慢到不可用。
 */
class MapSession private constructor(
    val workDir: File,
    val worldFolder: String,
    /** 原世界目录，用来读 level.dat 的出生点。 */
    private val worldDir: File,
) {
    private var db: PlayerDbManager? = null

    companion object {
        /** 默认渲染半径（区块）。8 → 128×128 方块。 */
        const val DEFAULT_RADIUS_CHUNKS = 8

        /** 单个方向的区块数上限，防止一次画太大 OOM。 */
        const val MAX_CHUNKS_PER_SIDE = 64

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
            // 方块坐标 → 区块坐标（向下取整，负数不能直接整除）
            Pair(Math.floorDiv(sx, 16), Math.floorDiv(sz, 16))
        } catch (_: Throwable) {
            null
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

            val readable = srcDb.exists() && srcDb.isDirectory && srcDb.listFiles() != null
            if (readable) {
                copyTree(srcDb, workDir)
            } else {
                copyViaShizuku(vm, srcDb, workDir)
            }

            if (workDir.listFiles().isNullOrEmpty()) {
                throw IllegalStateException("数据库目录为空，无法读取地形：${srcDb.absolutePath}")
            }
            return MapSession(workDir, worldFolder, worldDir)
        }

        /** 普通目录复制。保留子目录（LevelDB 可能有 .sst 分片）。 */
        private fun copyTree(src: File, dst: File) {
            if (!dst.exists()) dst.mkdirs()
            val children = src.listFiles() ?: return
            for (f in children) {
                val target = File(dst, f.name)
                if (f.isDirectory) {
                    copyTree(f, target)
                } else {
                    try {
                        f.inputStream().use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                    } catch (e: Exception) {
                        AppLogger.warn("MapSession", "复制失败 ${f.name}: ${e.message}")
                    }
                }
            }
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
        try {
            db?.close()
        } catch (_: Exception) {
        }
        db = null
    }

    /**
     * 渲染一块地形。
     *
     * 中心优先用**出生点**，读不到才退回包围盒中心 ——
     * 实测存档的原点周围常常是空的，用包围盒中心也会偏。
     *
     * @param radiusChunks 半径（区块）。null = 自动取存档包围盒的一半
     *                     （上限 [MAX_CHUNKS_PER_SIDE]/2）。
     */
    suspend fun render(
        ctx: Context,
        dim: Int = 0,
        radiusChunks: Int? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Bitmap {
        val dbRef = database().rawDb()

        // ① 出生点优先
        val spawn = spawnChunkOf(worldDir)
            ?: run {
                // 读不到 level.dat 时用包围盒中心
                val b = ChunkIndex(dbRef).scan(dim)
                if (b.isEmpty) null else Pair(b.centerCx, b.centerCz)
            }

        if (spawn == null) {
            throw IllegalStateException("这个维度没有任何区块数据")
        }

        // ② 半径
        val r = radiusChunks?.coerceIn(0, MAX_CHUNKS_PER_SIDE / 2)
            ?: DEFAULT_RADIUS_CHUNKS
        val colors = BlockColorTable.get(ctx)
        val reader = ChunkReader(dbRef)
        val builder = TerrainMapBuilder(reader, colors)

        return builder.render(
            minCx = spawn.first - r,
            minCz = spawn.second - r,
            chunkCountX = r * 2 + 1,
            chunkCountZ = r * 2 + 1,
            dim = dim,
            onProgress = onProgress,
        )
    }
}
