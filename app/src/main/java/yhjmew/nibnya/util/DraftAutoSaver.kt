package yhjmew.nibnya.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import yhjmew.nibnya.R

/**
 * 草稿自动保存器：脏标记 + 定时检查。
 *
 * 工作方式：
 * 1. 编辑动作后调用 [markDirty] 打标记（很轻，只置一个布尔）
 * 2. 定时器每隔「间隔」醒来一次，**只有脏了才真正写盘**（避免无谓 I/O）
 * 3. 每写完一次清除标记，等待下一次编辑
 *
 * 定时器**只在编辑中运行**：加载存档时 [start]，保存完成/退出时 [stop]。
 *
 * [persistNow] 用于崩溃等紧急场景（同步写盘，必须在进程终止前完成）。
 */
object DraftAutoSaver {

    /** 数据提供者：由 MainActivity/WorldViewModel 实现，返回当前要落盘的内容 */
    interface Provider {
        /** 返回需要落盘的内容；返回 null 表示当前没有可保存的编辑 */
        fun snapshotForDraft(): DraftSnapshot?
    }

    data class DraftSnapshot(
        val sessionKey: String,
        val data: com.google.gson.JsonObject,
        val isPlayerMode: Boolean,
        val targetKey: String?,
        val dbPath: String?,
        val folder: String?,
        /** 世界名（用于草稿箱展示） */
        val worldName: String?,
        /** 存档所在根目录（「显示所有」模式下恢复草稿要靠它回到正确位置） */
        val basePath: String = ""
    )

    private var providerRef: WeakReference<Provider>? = null
    private val handler = Handler(Looper.getMainLooper())
    private val diskExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "draft-saver").apply { isDaemon = true }
    }

    /** 间隔（毫秒）；<= 0 表示关闭自动保存 */
    @Volatile private var intervalMs: Long = 30_000L

    /** 是否同时保存「改动前的原始数据」（用于对比） */
    @Volatile var keepOriginal: Boolean = false

    /** 草稿数量上限 */
    @Volatile var maxDrafts: Int = DraftStore.DEFAULT_MAX_DRAFTS

    @Volatile private var dirty = false
    @Volatile private var running = false

    /** 原始数据（进入编辑时的快照），用于 keepOriginal */
    private val originalByKey = HashMap<String, com.google.gson.JsonObject>()

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            if (dirty) flushAsync()
            val next = intervalMs
            if (next > 0) handler.postDelayed(this, next)
        }
    }

    // ============================================
    // 生命周期
    // ============================================

    fun attach(provider: Provider) {
        providerRef = WeakReference(provider)
    }

    /** 固定 Context（避免持有 Activity 造成泄漏） */
    private var appContext: Context? = null
    fun init(context: Context) {
        appContext = context.applicationContext
        DraftStore.pruneTo(context.applicationContext, maxDrafts)
    }

    fun setInterval(ms: Long) {
        intervalMs = if (ms < 0) 0 else ms
        if (running && intervalMs > 0) {
            handler.removeCallbacks(ticker)
            handler.postDelayed(ticker, intervalMs)
        }
    }

    /** 进入编辑：启动定时器 */
    fun start() {
        if (running) return
        running = true
        if (intervalMs > 0) handler.postDelayed(ticker, intervalMs)
    }

    /** 结束编辑（保存完成 / 关闭存档）：停止定时器并清理状态 */
    fun stop() {
        running = false
        dirty = false
        handler.removeCallbacks(ticker)
        originalByKey.clear()
    }

    // ============================================
    // 标记
    // ============================================

    /** 有编辑发生。极轻量，可高频调用 */
    fun markDirty() {
        dirty = true
        AppLogger.info("Draft", R.string.log_draft_mark_dirty)
    }

    /**
     * 记录「改动前的原始数据」。加载存档后调用一次，
     * 用于 keepOriginal 开启时的对比功能。
     */
    fun rememberOriginal(key: String, data: com.google.gson.JsonObject?) {
        if (data == null) {
            originalByKey.remove(key)
        } else {
            originalByKey[key] = data.deepCopy()
        }
    }

    // ============================================
    // 落盘
    // ============================================

    /** 异步落盘（定时器用，不阻塞 UI） */
    private fun flushAsync() {
        dirty = false
        val ctx = appContext ?: return
        val snap = providerRef?.get()?.snapshotForDraft() ?: return
        diskExecutor.execute { writeDraft(ctx, snap) }
    }

    /**
     * 立即同步落盘。用于崩溃处理 —— 必须在进程终止前完成，
     * 因此这里是同步写，且全程 try-catch（崩溃时状态可能已不稳定）。
     */
    fun persistNow() {
        try {
            val ctx = appContext ?: return
            val snap = providerRef?.get()?.snapshotForDraft() ?: return
            writeDraft(ctx, snap)
            dirty = false
        } catch (e: Throwable) {
            AppLogger.warn("Draft", R.string.log_draft_emergency_failed, e.message)
        }
    }

    private fun writeDraft(ctx: Context, snap: DraftSnapshot) {
        try {
            DraftStore.save(
                context = ctx,
                sessionKey = snap.sessionKey,
                data = snap.data,
                isPlayerMode = snap.isPlayerMode,
                targetKey = snap.targetKey,
                dbPath = snap.dbPath,
                folder = snap.folder,
                originalData = if (keepOriginal) originalByKey[snap.sessionKey] else null,
                worldName = snap.worldName,
                maxDrafts = maxDrafts,
                basePath = snap.basePath
            )
        } catch (e: Throwable) {
            AppLogger.warn("Draft", R.string.log_draft_write_failed, e.message)
        }
    }
}
