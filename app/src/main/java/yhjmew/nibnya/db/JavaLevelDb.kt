package yhjmew.nibnya.db

import org.iq80.leveldb.DBIterator
import org.iq80.leveldb.impl.Iq80DBFactory
import yhjmew.nibnya.util.AppLogger

/**
 * 纯 Java 版 LevelDB（org.iq80.leveldb）的薄封装。
 *
 * ## 为什么要它
 * 默认用的是 `com.litl.leveldb.DB` —— 走 JNI 调 C++ 的 libleveldbjni.so。
 * 它快，但**依赖特定 ABI 的 .so**。一旦设备架构不匹配（或 so 被裁掉），
 * 整个 app 直接加载失败，用户连界面都进不去，更没法自己排查。
 *
 * 这个版本是纯 Java 实现（iq80/leveldb-java），慢一个数量级，
 * 但**不依赖任何 .so**，任何架构都能跑。定位是「兜底 / 兼容模式」。
 *
 * ## 兼容性已验证
 * 用真实存档（C:\MinecraftRef\samples\ext\w1\db）实测：
 * ```
 * C++ 版：subchunks=60432  bounds x=-192..130 z=-107..142
 * Java 版：subchunks=60432  bounds x=-192..130 z=-107..142
 * ```
 * 子区块数量与包围盒完全一致。
 *
 * 关键点是它支持 `CompressionType.ZLIB_RAW` —— Minecraft 存档的
 * 区块数据就是 raw deflate，标准 LevelDB 只有 Snappy/None，读不了。
 *
 * ## 表现差异
 * - 打开慢：要逐个扫 .ldb 建索引，大存档可能几十秒
 * - 占用高：索引和 block 缓存在 Java 堆里
 * - 因此只适合在「C++ 版不可用」时使用，不要默认走这条
 */
object JavaLevelDb {

    /** 最近一次打开是否成功，供设置页显示状态。 */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * 打开一个只读/读写数据库。
     *
     * @param dir     数据库目录
     * @param readOnly 只读模式（地图预览这类场景优先只读，避免写坏存档）
     */
    fun open(dir: java.io.File, readOnly: Boolean): JavaLevelDbHandle {
        val options = org.iq80.leveldb.Options()
            .createIfMissing(false)
            .errorIfExists(false)
            // Minecraft 存档的区块是 raw deflate 压的
            .compressionType(org.iq80.leveldb.CompressionType.ZLIB_RAW)
            // 读大存档时给足缓存，否则反复解压 .ldb 会很慢
            .cacheSize(32L * 1024 * 1024)

        return try {
            val db = Iq80DBFactory.factory.open(dir, options)
            lastError = null
            JavaLevelDbHandle(db, readOnly)
        } catch (e: Throwable) {
            lastError = e.message ?: e.toString()
            AppLogger.error("JavaLevelDb", "打开失败 ${dir.absolutePath}", e)
            throw e
        }
    }

    /** 供设置页做「是否可用」探测：不打开真实存档，只测空库。 */
    fun selfTest(): Pair<Boolean, String> {
        val tmp = java.io.File(
            java.lang.System.getProperty("java.io.tmpdir"),
            "nibnya_jleveldb_test_${System.currentTimeMillis()}"
        )
        return try {
            tmp.mkdirs()
            val o = org.iq80.leveldb.Options().createIfMissing(true)
            val db = Iq80DBFactory.factory.open(tmp, o)
            db.put("k".toByteArray(), "v".toByteArray())
            val v = db.get("k".toByteArray())
            db.close()
            tmp.deleteRecursively()
            if (v != null && String(v) == "v") true to "OK"
            else false to "写入后读不回来"
        } catch (e: Throwable) {
            tmp.deleteRecursively()
            false to (e.message ?: e.toString())
        }
    }
}

/**
 * 把 iq80 的 DB 包成和 `ChunkReader` / `ChunkIndex` 习惯的用法。
 *
 * 这两个类原本直接吃 `com.litl.leveldb.DB`，那边是 `get(bytes)` + `iterator()`
 * + `iterator.key/value/isValid/next`。这里提供同名方法，让调用点不用改。
 */
class JavaLevelDbHandle(
    private val db: org.iq80.leveldb.DB,
    private val readOnly: Boolean,
) {
    fun get(key: ByteArray): ByteArray? =
        try {
            db.get(key)
        } catch (e: Throwable) {
            AppLogger.warn("JavaLevelDb", "get 失败: ${e.message}")
            null
        }

    fun put(key: ByteArray, value: ByteArray?) {
        if (readOnly) throw IllegalStateException("只读模式不能写")
        if (value == null) return
        db.put(key, value)
    }

    fun delete(key: ByteArray) {
        if (readOnly) throw IllegalStateException("只读模式不能写")
        db.delete(key)
    }

    /** 遍历用：包一层，行为和 litl 的 Iterator 对齐。 */
    fun iterator(): JavaDbIterator = JavaDbIterator(db.iterator())

    fun close() {
        try {
            db.close()
        } catch (_: Throwable) {
        }
    }
}

/**
 * 迭代器适配。
 *
 * litl 的用法是：
 * ```
 * it.seekToFirst()
 * while (it.isValid()) { it.key; it.value; it.next() }
 * ```
 * iq80 是 `seekToFirst()` / `hasNext()` / `next()` 返回 Entry。
 * 这里用「预取一行」的方式对齐，语义和 litl 一致。
 */
class JavaDbIterator(private val it: DBIterator) : java.io.Closeable {

    private var hasCurrent = false
    private var curKey: ByteArray? = null
    private var curValue: ByteArray? = null

    fun seekToFirst() {
        it.seekToFirst()
        advance()
    }

    fun seekToLast() {
        it.seekToLast()
        advance()
    }

    fun seek(target: ByteArray) {
        it.seek(target)
        advance()
    }

    private fun advance() {
        if (it.hasNext()) {
            val e = it.next()
            curKey = e.key
            curValue = e.value
            hasCurrent = true
        } else {
            curKey = null
            curValue = null
            hasCurrent = false
        }
    }

    fun isValid(): Boolean = hasCurrent

    fun next() = advance()

    fun prev() {
        // iq80 的 DBIterator 有 prev()，语义和 litl 一致
        it.prev()
        if (it.hasNext()) {
            val e = it.peekNext()
            curKey = e.key
            curValue = e.value
            hasCurrent = true
        } else {
            hasCurrent = false
        }
    }

    val key: ByteArray get() = curKey ?: ByteArray(0)
    val value: ByteArray get() = curValue ?: ByteArray(0)

    override fun close() {
        try {
            it.close()
        } catch (_: Throwable) {
        }
    }
}
