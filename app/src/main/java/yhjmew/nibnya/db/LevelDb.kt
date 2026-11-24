package yhjmew.nibnya.db

import com.litl.leveldb.DB
import yhjmew.nibnya.util.AppLogger
import java.io.File

/**
 * 存档 db 的最小接口。
 *
 * ## 为什么要有这一层
 * 原来 `PlayerDbManager` / `ChunkReader` / `ChunkIndex` 直接吃
 * `com.litl.leveldb.DB`。那个类是 JNI 实现，必须先 `System.loadLibrary`
 * 加载对应 ABI 的 `libleveldbjni.so`。so 缺失或不匹配时，
 * **类初始化就抛 UnsatisfiedLinkError**，连 try/catch 都救不回来。
 *
 * 抽成接口之后，后端可以换：[LitlLevelDb]（C++，快）/
 * [JavaLevelDb]（纯 Java，慢但不挑架构）。
 *
 * ## 迭代器语义
 * 刻意对齐 litl 的用法，因为调用点已经这么写了：
 * ```
 * it.seekToFirst()
 * while (it.isValid()) { it.key; it.value; it.next() }
 * ```
 */
interface LevelDb : java.io.Closeable {

    /** 读一个键；不存在返回 null。 */
    fun get(key: ByteArray): ByteArray?

    /** 写一个键。只读后端会抛异常。 */
    fun put(key: ByteArray, value: ByteArray?)

    fun delete(key: ByteArray)

    /** 新建一个遍历器，用完必须 close（或 use）。 */
    fun iterator(): DbIterator

    /** 后端名，给设置页显示用。 */
    val engineName: String
}

/** 遍历器。语义与 litl 的 `com.litl.leveldb.Iterator` 一致。 */
private val EMPTY = ByteArray(0)

interface DbIterator : java.io.Closeable {
    fun seekToFirst()
    fun seekToLast()
    fun seek(target: ByteArray)
    fun isValid(): Boolean
    fun next()
    fun prev()

    /**
     * 当前行的键 / 值。
     *
     * ⚠️ 只在 [isValid] 为 true 时有效；无效时返回空数组而不是 null ——
     * 原调用点（PlayerDbManager 的几个 listXxx）都是先把 isValid 判过
     * 再直接拿去用，返回 null 会让它们统统编译不过、还得每处加空判断。
     */
    val key: ByteArray
    val value: ByteArray
}

// ============================================
// 后端一：C++（litl JNI，默认）
// ============================================

/**
 * JNI 后端。
 *
 * ⚠️ 构造它就会触发 `com.litl.leveldb.DB` 的类加载 → `System.loadLibrary`。
 * 如果 so 不存在，这里会抛 [UnsatisfiedLinkError]，调用方要兜住并
 * 回退到 [JavaLevelDb]。
 */
class LitlLevelDb(private val db: DB, override val engineName: String = "C++") : LevelDb {


    /*
     * ⚠️ 这里的每个方法都可能触发 native 崩溃。
     *
     * libleveldbjni.so 是预编译的第三方 .so，我们改不了它的内部。
     * 它能保证的错误只有 Java 异常（IOError 等），而**内存损坏 / 空指针
     * 这类问题会直接 SIGSEGV**，进程瞬间消失、日志里什么都没有。
     *
     * 所以这里能做的是：
     *   ① 把每个 Java 异常都记下来（带 op 名和 key 长度，不记内容）
     *   ② 记下出错时的操作类型，配合 native-crash.log 能定位到是哪一步
     *   ③ close() 不再静默吞异常，至少留一行 warn
     *
     * 真正的 SIGSEGV 由 nibnya_seed.c 里的信号处理器兜底 —— 它写的是
     * 全局崩溃文件，两边的日志拼起来才能还原现场。
     */

    override fun get(key: ByteArray): ByteArray? =
        try {
            db.get(key)
        } catch (t: Throwable) {
            logDbError("get", key, t)
            throw t
        }

    override fun put(key: ByteArray, value: ByteArray?) {
        if (value == null) return
        try {
            db.put(key, value)
        } catch (t: Throwable) {
            logDbError("put", key, t)
            throw t
        }
    }

    override fun delete(key: ByteArray) =
        try {
            db.delete(key)
        } catch (t: Throwable) {
            logDbError("delete", key, t)
            throw t
        }

    override fun iterator(): DbIterator =
        try {
            LitlIterator(db.iterator())
        } catch (t: Throwable) {
            AppLogger.error(TAG, "iterator() 失败", t)
            throw t
        }

    override fun close() {
        try {
            db.close()
        } catch (t: Throwable) {
            // 不再静默：关闭失败往往意味着之前的读写已经出问题
            AppLogger.warn(TAG, "close() 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 记录一次 LevelDB 操作失败。key 只记长度和首字节，避免刷屏。 */
    private fun logDbError(op: String, key: ByteArray, t: Throwable) {
        val preview = if (key.isEmpty()) "-"
        else key.take(4).joinToString("") { "%02x".format(it) } + "…(${key.size}B)"
        AppLogger.error(
            TAG,
            "LevelDB $op 失败 key=$preview: ${t.javaClass.simpleName}: ${t.message}",
            t,
        )
    }

    companion object {
        private const val TAG = "LitlLevelDb"

        /**
         * 打开（带重试）。
         *
         * 保持原来 `PlayerDbManager` 的行为：清掉 LOCK/LOG/LOG.old 再开，
         * 失败重试 3 次。CURRENT / MANIFEST 是索引，绝不能删。
         */
        fun open(folder: File): LitlLevelDb {
            var lastErr: Throwable? = null
            for (attempt in 0 until 3) {
                try {
                    return LitlLevelDb(DB(folder).also { it.open() })
                } catch (e: Throwable) {
                    lastErr = e
                    if (attempt < 2) {
                        File(folder, "LOCK").takeIf { it.exists() }?.delete()
                        File(folder, "LOG").takeIf { it.exists() }?.delete()
                        File(folder, "LOG.old").takeIf { it.exists() }?.delete()
                        Thread.sleep(150)
                    }
                }
            }
            throw lastErr ?: Exception("数据库无法打开")
        }

        /**
         * 自检：在临时目录建空库读一次。
         *
         * 主要用来验证 **so 是否能正常加载** —— 这是 C++ 版最常见的失败点，
         * 而且一旦失败抛的是 `UnsatisfiedLinkError`（Error 不是 Exception），
         * 界面层很难给出有用的提示。有自检至少能明确告诉用户「so 没加载上」。
         */
        fun selfTest(): Pair<Boolean, String> {
            val tmp = File(
                System.getProperty("java.io.tmpdir"),
                "nibnya_litl_test_${System.currentTimeMillis()}"
            )
            return try {
                tmp.mkdirs()
                val handle = LitlLevelDb(DB(tmp).also { it.open() })
                handle.put("k".toByteArray(), "v".toByteArray())
                val v = handle.get("k".toByteArray())
                handle.close()
                tmp.deleteRecursively()
                if (v != null && String(v) == "v") true to "OK"
                else false to "写入后读不回来"
            } catch (e: Throwable) {
                tmp.deleteRecursively()
                // UnsatisfiedLinkError 的信息通常只有一句 "no leveldb in java.library.path"，
                // 补上 so 的实际加载情况更有用
                val hint = if (e is UnsatisfiedLinkError) "（原生库未加载，设备架构可能不受支持）" else ""
                false to ((e.message ?: e.javaClass.simpleName) + hint)
            }
        }
    }
}

private class LitlIterator(private val it: com.litl.leveldb.Iterator) : DbIterator {
    override fun seekToFirst() = it.seekToFirst()
    override fun seekToLast() = it.seekToLast()
    override fun seek(target: ByteArray) = it.seek(target)
    override fun isValid(): Boolean = it.isValid
    override fun next() = it.next()
    override fun prev() = it.prev()
    override val key: ByteArray get() = if (it.isValid) it.key else EMPTY
    override val value: ByteArray get() = if (it.isValid) it.value else EMPTY
    override fun close() = it.close()
}

// ============================================
// 后端二：纯 Java（iq80，兜底）
// ============================================

/**
 * 纯 Java 后端，包装 [JavaLevelDbHandle]。
 *
 * 不依赖任何 .so，任何架构都能跑。代价是慢、内存高。
 * 已有实测确认能正确读 Minecraft 存档（含 raw deflate 压缩）。
 */
class JavaBackedLevelDb(
    private val handle: JavaLevelDbHandle,
    override val engineName: String = "Java",
) : LevelDb {

    override fun get(key: ByteArray): ByteArray? = handle.get(key)

    override fun put(key: ByteArray, value: ByteArray?) = handle.put(key, value)

    override fun delete(key: ByteArray) = handle.delete(key)

    override fun iterator(): DbIterator = JavaDbIteratorAdapter(handle.iterator())

    override fun close() = handle.close()

    companion object {
        fun open(folder: File, readOnly: Boolean = false): JavaBackedLevelDb =
            JavaBackedLevelDb(JavaLevelDb.open(folder, readOnly))
    }
}

private class JavaDbIteratorAdapter(
    private val it: JavaDbIterator,
) : DbIterator {
    override fun seekToFirst() = it.seekToFirst()
    override fun seekToLast() = it.seekToLast()
    override fun seek(target: ByteArray) = it.seek(target)
    override fun isValid(): Boolean = it.isValid()
    override fun next() = it.next()
    override fun prev() = it.prev()
    override val key: ByteArray get() = it.key ?: EMPTY
    override val value: ByteArray get() = it.value ?: EMPTY
    override fun close() = it.close()
}
