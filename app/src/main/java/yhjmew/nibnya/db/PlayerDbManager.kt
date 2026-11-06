package yhjmew.nibnya.db

import com.litl.leveldb.DB
import java.io.File
import java.nio.charset.StandardCharsets
import yhjmew.nibnya.R
import yhjmew.nibnya.nbt.NbtTranslator.getString

// Litl LevelDB JNI
class PlayerDbManager(dbFolderPath: String) {
    private var db: DB?
    private val dbFolder = File(dbFolderPath)

    init {
        if (!dbFolder.exists()) {
            dbFolder.mkdirs()
        }

        // 清理 LOCK 和 LOG（临时文件，打开时会重建；CURRENT/MANIFEST 是索引不能删）
        File(dbFolder, "LOCK").takeIf { it.exists() }?.delete()
        File(dbFolder, "LOG").takeIf { it.exists() }?.delete()
        File(dbFolder, "LOG.old").takeIf { it.exists() }?.delete()

        db = openWithRetry()
    }

    private fun openWithRetry(): DB {
        var lastErr: Exception? = null
        for (attempt in 0 until 3) {
            try {
                return DB(dbFolder).also { it.open() }
            } catch (e: Exception) {
                lastErr = e
                if (attempt < 2) {
                    // 再清一次 LOCK/LOG
                    File(dbFolder, "LOCK").takeIf { it.exists() }?.delete()
                    File(dbFolder, "LOG").takeIf { it.exists() }?.delete()
                    File(dbFolder, "LOG.old").takeIf { it.exists() }?.delete()
                    Thread.sleep(150)
                }
            }
        }
        throw lastErr ?: Exception(getString(R.string.label_sub_database_is_not_open))
    }

    @Throws(Exception::class)
    fun readLocalPlayer(): ByteArray? {
        val d = db ?: throw Exception(getString(R.string.label_sub_database_is_not_open))

        val key = "~local_player".toByteArray(StandardCharsets.UTF_8)
        val value = d.get(key)

        if (value == null) {
            // 兜底遍历查找
            d.iterator().use { iterator ->
                iterator.seekToFirst()
                while (iterator.isValid) {
                    val keyStr = String(iterator.key, StandardCharsets.UTF_8)
                    if (keyStr.contains("local_player") || keyStr.contains("player_server")) {
                        return iterator.value
                    }
                    iterator.next()
                }
            }
            throw Exception(getString(R.string.msg_player_data_not_found))
        }
        return value
    }

    @Throws(Exception::class)
    fun readSpecificKey(keyString: String): ByteArray {
        val d = db ?: throw Exception(getString(R.string.label_sub_db_not_open))
        val keyBytes = keyString.toByteArray(StandardCharsets.UTF_8)
        return d.get(keyBytes) ?: throw Exception(getString(R.string.msg_data_is_empty_colon) + keyString)
    }

    @Throws(Exception::class)
    fun readRawKey(keyBytes: ByteArray?): ByteArray {
        val d = db ?: throw Exception(getString(R.string.label_sub_db_not_open))
        return d.get(keyBytes) ?: throw Exception(getString(R.string.msg_key_not_found))
    }

    @Throws(Exception::class)
    fun writeLocalPlayer(nbtData: ByteArray?) {
        val d = db ?: throw Exception(getString(R.string.label_sub_database_is_not_open))
        val key = "~local_player".toByteArray(StandardCharsets.UTF_8)
        d.put(key, nbtData)
    }

    @Throws(Exception::class)
    fun writeSpecificKey(keyStr: String, data: ByteArray?) {
        val d = db ?: throw Exception(getString(R.string.label_sub_database_shutdown))
        val k = keyStr.toByteArray(StandardCharsets.UTF_8)
        d.put(k, data)
    }

    @Throws(Exception::class)
    fun deleteKey(keyStr: String) {
        val d = db ?: throw Exception(getString(R.string.label_sub_database_shutdown))
        val k = keyStr.toByteArray(StandardCharsets.UTF_8)
        d.delete(k)
    }

    @Throws(Exception::class)
    fun listMapKeys(): MutableList<String> {
        val d = db ?: throw Exception(getString(R.string.label_sub_database_shutdown))
        val list = mutableListOf<String>()

        d.iterator().use { iterator ->
            val prefix = "map_".toByteArray(StandardCharsets.UTF_8)
            iterator.seek(prefix)
            while (iterator.isValid) {
                val keyStr = String(iterator.key, StandardCharsets.UTF_8)
                if (!keyStr.startsWith("map_")) break
                list.add(keyStr)
                iterator.next()
            }
        }
        list.sort()
        return list
    }

    @Throws(Exception::class)
    fun listVillageKeys(): MutableList<String> {
        val d = db ?: throw Exception(getString(R.string.label_sub_database_shutdown))
        val list = mutableListOf<String>()

        d.iterator().use { iterator ->
            val prefix = "VILLAGE_".toByteArray(StandardCharsets.UTF_8)
            iterator.seek(prefix)
            while (iterator.isValid) {
                val keyStr = String(iterator.key, StandardCharsets.UTF_8)
                if (!keyStr.startsWith("VILLAGE_")) break
                list.add(keyStr)
                iterator.next()
            }
        }
        list.sort()
        return list
    }

    @Throws(Exception::class)
    fun listPlayerKeys(): MutableList<String> {
        val d = db ?: throw Exception(getString(R.string.label_sub_database_shutdown))
        val players = mutableListOf("~local_player")

        d.iterator().use { iterator ->
            val prefix = "player".toByteArray(StandardCharsets.UTF_8)
            iterator.seek(prefix)
            while (iterator.isValid) {
                val keyStr = String(iterator.key, StandardCharsets.UTF_8)
                if (!keyStr.startsWith("player")) break

                if (keyStr.startsWith("player_") || keyStr == "player") {
                    players.add(keyStr)
                }
                iterator.next()
            }
        }
        players.sort()
        return players
    }

    /**
     * 底层 DB 句柄，供只读扫描使用（地形图逐区块读取）。
     *
     * **不要**拿它做写操作 —— 写请走本类的方法，
     * 这样以后加事务或前置校验时不用满仓库找调用点。
     */
    fun rawDb(): DB =
        db ?: throw Exception(getString(R.string.label_sub_database_is_not_open))

    fun close() {
        db?.close()
        db = null
    }

    companion object {
        // 加载 so 库
        init {
            try {
                System.loadLibrary("leveldb")
            } catch (_: UnsatisfiedLinkError) {
                try {
                    System.loadLibrary("leveldbjni")
                } catch (_: Throwable) {
                }
            }
        }

        @Throws(Exception::class)
        fun tryRepair(dbFolderPath: String) {
            val folder = File(dbFolderPath)

            // 清理 LOCK
            File(folder, "LOCK").takeIf { it.exists() }?.delete()
            // 清理 CURRENT
            File(folder, "CURRENT").takeIf { it.exists() }?.delete()

            try {
                DB(folder).also { it.open(); it.close() }
            } catch (e: Exception) {
                throw Exception("${getString(R.string.msg_repair_failed_corrupted)}${e.message}")
            }
        }
    }
}