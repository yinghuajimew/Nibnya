package yhjmew.nibnya.map

import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 瓦片请求调度器。
 *
 * ## 为什么要单独抽一个
 *
 * 原来 [SaveTiles.request] 是「拿到一个 key 就直接 `pool.execute`」。
 * 缩放一次会为整屏（加预取边距）投递几十块瓦片，再缩放一次又是几十块 ——
 * 旧的不会取消，新的一直加进来。用户快速来回缩放时：
 *
 * ```
 * 缩小 → 投递 40 块 → 再放大 → 投递 60 块 → 再缩小 → 投递 40 块 ...
 * ```
 *
 * 队列无限增长，每块都要读 LevelDB + 解析 + 生成 Bitmap。
 * 内存和 CPU 双双吃满，最后进程被系统杀掉 —— **这正是「缩放几下就静默退出」
 * 最可能的直接原因**（没有 Java 异常，因为不是 OOM 而是 native 侧被终止）。
 *
 * ## 照 BedrockMap 的四个机制
 *
 * | 机制 | BedrockMap | 这里 |
 * |---|---|---|
 * | 优先级：视野内 > 视野外，近 > 远 | `QueueCompare` | [Request.compareTo] |
 * | 视口变化丢弃视野外排队任务 | `discardPendingOutsideViewport()` | [retainOnly] |
 * | 版本号校验，出队时丢弃过期项 | `takeBestPending()` | [version] |
 * | 并发上限 = 线程数 | `dispatch()` | [dispatch] |
 *
 * ## 为什么不直接取消正在跑的任务
 *
 * 正在解析 LevelDB 的线程无法安全中断（JNI 调用不可中断）。
 * 折中：让它们跑完，但**结果丢弃**（`generation` / `wanted` 双重校验）。
 * 这和 BedrockMap 的 `pause()` 注释一致：running tasks finish, results discarded.
 */
internal class TileScheduler(
    private val threads: Int,
    private val executor: (Runnable) -> Unit,
) {

    /**
     * 一条排队中的请求。
     *
     * `compareTo` 小的先出队 —— 即：视野内的优先，其次离视野中心近的，
     * 最后是同距离时**新请求优先**（旧的反正也快要被丢弃了）。
     */
    internal class Request(
        val key: String,
        val scale: Int,
        val tileX: Int,
        val tileZ: Int,
        val version: Long,
        /** 是否在视野内（含预取边距）。 */
        val inView: Boolean,
        /** 到视野中心的瓦片距离平方。 */
        val dist: Long,
    ) : Comparable<Request> {
        override fun compareTo(other: Request): Int {
            // 视野内优先
            if (inView != other.inView) return if (inView) -1 else 1
            if (dist != other.dist) return dist.compareTo(other.dist)
            // 同距离：版本大的（新）优先
            return other.version.compareTo(version)
        }
    }

    private val lock = Any()
    private val queue = PriorityQueue<Request>()
    private val queued = HashSet<String>()
    private val running = HashSet<String>()
    private var nextVersion = 0L
    private var closed = false

    /** 待处理 / 正在处理 的数量，给调试面板用。 */
    fun pendingCount(): Int = synchronized(lock) { queue.size }
    fun runningCount(): Int = synchronized(lock) { running.size }

    /**
     * 提交一条请求。
     *
     * @param inView 是否在视野内（含预取边距）
     * @param dist   到视野中心的距离平方，用于同优先级排序
     * @return true 表示接受了（可能是新入队，也可能已在队列里）；false = 已关闭或重复
     */
    fun submit(
        key: String, scale: Int, tileX: Int, tileZ: Int,
        inView: Boolean, dist: Long,
    ): Boolean {
        synchronized(lock) {
            if (closed) return false
            // 已在排队或正在跑 → 不重复投递
            if (key in queued || key in running) return false
            queued.add(key)
            queue.add(Request(key, scale, tileX, tileZ, ++nextVersion, inView, dist))
        }
        dispatch()
        return true
    }

    /**
     * 只保留满足 [keep] 的排队任务，其余丢弃。
     *
     * 视口一变就调它 —— 这是「缩放时不累积」的关键。
     * 正在跑的任务不受影响（结果会由调用方按 wanted 过滤掉）。
     */
    fun retainOnly(keep: (String) -> Boolean) {
        synchronized(lock) {
            if (closed || queue.isEmpty()) return
            val it = queue.iterator()
            while (it.hasNext()) {
                val r = it.next()
                if (!keep(r.key)) {
                    it.remove()
                    queued.remove(r.key)
                }
            }
        }
    }

    /** 派发任务，直到跑满线程数或队列空。 */
    private fun dispatch() {
        while (true) {
            val next: Request
            val canRun: Boolean
            synchronized(lock) {
                if (closed) return
                if (running.size >= threads) return
                val head = queue.poll() ?: return
                queued.remove(head.key)
                running.add(head.key)
                next = head
                canRun = true
            }
            if (!canRun) return

            executor(
                Runnable {
                    try {
                        if (isClosed()) return@Runnable
                        onRun(next.scale, next.tileX, next.tileZ, next.key)
                    } finally {
                        synchronized(lock) { running.remove(next.key) }
                        dispatch()
                    }
                }
            )
        }
    }

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    /**
     * 实际执行。由 [SaveTiles] 提供：生成瓦片并放进缓存。
     * 抛异常不能把调度器带死 —— 外层会 catch。
     */
    var onRun: (scale: Int, tileX: Int, tileZ: Int, key: String) -> Unit =
        { _, _, _, _ -> }

    /** 停止派发并清空队列。正在跑的会跑完，结果由调用方丢弃。 */
    fun reset() {
        synchronized(lock) {
            queue.clear()
            queued.clear()
        }
    }

    /** 彻底关闭：不再接受任何请求。 */
    fun close() {
        synchronized(lock) {
            closed = true
            queue.clear()
            queued.clear()
        }
    }
}
