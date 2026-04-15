package yhjmew.minecraft.nbteditor

import com.litl.leveldb.DB
import yhjmew.minecraft.nbteditor.NbtTranslator.getString
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Collections

// Litl LevelDB JNI
class PlayerDbManager(dbFolderPath: String) {
    private var db: DB?
    private val dbFolder: File

    init {
        this.dbFolder = File(dbFolderPath)
        if (!dbFolder.exists()) {
            dbFolder.mkdirs()
        }

        // 清理 LOCK 文件
        val lockFile = File(dbFolder, "LOCK")
        if (lockFile.exists()) {
            lockFile.delete()
        }

        // Litl 版本的打开方式
        this.db = DB(dbFolder)
        this.db!!.open()
    }

    @Throws(Exception::class)
    fun readLocalPlayer(): ByteArray? {
        if (db == null) throw Exception(getString(R.string.msg_database_is_not_open))

        val key: ByteArray? = "~local_player".toByteArray(StandardCharsets.UTF_8)
        val value = db!!.get(key)

        if (value == null) {
            // 兜底遍历查找
            db!!.iterator().use { iterator ->
                iterator.seekToFirst()
                while (iterator.isValid()) {
                    val keyStr = String(iterator.getKey(), StandardCharsets.UTF_8)
                    if (keyStr.contains("local_player") || keyStr.contains("player_server")) {
                        return iterator.getValue()
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
        if (db == null) throw Exception(getString(R.string.msg_dn_is_not_open))
        val keyBytes: ByteArray? = keyString.toByteArray(StandardCharsets.UTF_8)
        val `val` = db!!.get(keyBytes)
        if (`val` == null) throw Exception(getString(R.string.msg_data_is_empty_colon) + keyString)
        return `val`
    }

    @Throws(Exception::class)
    fun readRawKey(keyBytes: ByteArray?): ByteArray {
        if (db == null) throw Exception(getString(R.string.msg_dn_is_not_open))
        val `val` = db!!.get(keyBytes)
        if (`val` == null) throw Exception(getString(R.string.msg_the_data_corresponding_to_this_key_was_not_found))
        return `val`
    }

    @Throws(Exception::class)
    fun writeLocalPlayer(nbtData: ByteArray?) {
        if (db == null) throw Exception(getString(R.string.msg_database_is_not_open))
        val key: ByteArray? = "~local_player".toByteArray(StandardCharsets.UTF_8)
        db!!.put(key, nbtData)
    }

    @Throws(Exception::class)
    fun writeSpecificKey(keyStr: String, data: ByteArray?) {
        if (db == null) throw Exception(getString(R.string.msg_database_shutdown))
        val k: ByteArray? = keyStr.toByteArray(StandardCharsets.UTF_8)
        db!!.put(k, data)
    }

    @Throws(Exception::class)
    fun deleteKey(keyStr: String) {
        if (db == null) throw Exception(getString(R.string.msg_database_shutdown))
        val k: ByteArray? = keyStr.toByteArray(StandardCharsets.UTF_8)
        db!!.delete(k)
    }

    @Throws(Exception::class)
    fun listMapKeys(): MutableList<String?> {
        if (db == null) throw Exception(getString(R.string.msg_database_shutdown))
        val list: MutableList<String?> = ArrayList<String?>()

        db!!.iterator().use { iterator ->
            val prefix: ByteArray? = "map_".toByteArray(StandardCharsets.UTF_8)
            iterator.seek(prefix)
            while (iterator.isValid()) {
                val keyBytes = iterator.getKey()
                val keyStr = kotlin.text.String(keyBytes!!, StandardCharsets.UTF_8)
                if (!keyStr.startsWith("map_")) break
                list.add(keyStr)
                iterator.next()
            }
        }
        Collections.sort<String?>(list)
        return list
    }

    @Throws(Exception::class)
    fun listVillageKeys(): MutableList<String?> {
        if (db == null) throw Exception(getString(R.string.msg_database_shutdown))
        val list: MutableList<String?> = ArrayList<String?>()

        db!!.iterator().use { iterator ->
            val prefix: ByteArray? = "VILLAGE_".toByteArray(StandardCharsets.UTF_8)
            iterator.seek(prefix)
            while (iterator.isValid()) {
                val keyStr = String(iterator.getKey(), StandardCharsets.UTF_8)
                if (!keyStr.startsWith("VILLAGE_")) break
                list.add(keyStr)
                iterator.next()
            }
        }
        Collections.sort<String?>(list)
        return list
    }

    @Throws(Exception::class)
    fun listPlayerKeys(): MutableList<String?> {
        if (db == null) throw Exception(getString(R.string.msg_database_shutdown))
        val players: MutableList<String?> = ArrayList<String?>()
        players.add("~local_player")

        db!!.iterator().use { iterator ->
            val prefix: ByteArray? = "player".toByteArray(StandardCharsets.UTF_8)
            iterator.seek(prefix)
            while (iterator.isValid()) {
                val keyStr = String(iterator.getKey(), StandardCharsets.UTF_8)
                if (!keyStr.startsWith("player")) break

                if (keyStr.startsWith("player_") || keyStr == "player") {
                    players.add(keyStr)
                }
                iterator.next()
            }
        }
        Collections.sort<String?>(players)
        return players
    }

    fun close() {
        if (db != null) {
            db!!.close()
            db = null
        }
    }

    companion object {
        // 加载 so 库
        init {
            try {
                System.loadLibrary("leveldb")
            } catch (e: UnsatisfiedLinkError) {
                try {
                    System.loadLibrary("leveldbjni")
                } catch (ignored: Throwable) {
                }
            }
        }

        @Throws(Exception::class)
        fun tryRepair(dbFolderPath: String) {
            val folder = File(dbFolderPath)


            // 清理 LOCK
            val lockFile = File(folder, "LOCK")
            if (lockFile.exists()) {
                lockFile.delete()
            }


            // 清理 CURRENT
            val currentFile = File(folder, "CURRENT")
            if (currentFile.exists()) {
                currentFile.delete()
            }

            try {
                val tempDb = DB(folder)
                tempDb.open()
                tempDb.close()
            } catch (e: Exception) {
                throw Exception(getString(R.string.msg_the_repair_failed_and_the_data_was_completely_damaged) + e.message)
            }
        }
    }
}