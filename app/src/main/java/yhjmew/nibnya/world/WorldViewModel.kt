package yhjmew.nibnya.world

import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.*
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import yhjmew.nibnya.MainActivity
import yhjmew.nibnya.R
import yhjmew.nibnya.db.PlayerDbManager
import yhjmew.nibnya.editor.EditorViewModel
import yhjmew.nibnya.editor.dialogs.*
import yhjmew.nibnya.nbt.BedrockParser
import yhjmew.nibnya.nbt.NbtTranslator
import yhjmew.nibnya.nbt.NbtTranslator.getString
import yhjmew.nibnya.util.AppDirs
import yhjmew.nibnya.util.PrivilegedExec
import yhjmew.nibnya.util.AppLogger
import yhjmew.nibnya.util.BackupSettings
import yhjmew.nibnya.util.DraftAutoSaver
import yhjmew.nibnya.util.DraftStore
import yhjmew.nibnya.util.TrashStore

/**
 * 世界/存档/路径/文件 I/O 的 ViewModel
 * 加载完成后通过 editorVM 写入数据
 */
class WorldViewModel : ViewModel(), DraftAutoSaver.Provider {

    /** Activity 创建后注入 */
    var editorVM: EditorViewModel? = null

    // ============================================
    // 数据类
    // ============================================
    data class WorldItem(
        val folderName: String,
        val displayName: String,
        /** level.dat 的最后修改时间（毫秒）；0 = 未知 */
        val lastModified: Long = 0L,
        /**
         * 存档缩略图（世界目录下的 world_icon.jpeg）的绝对路径。
         * 这是游戏退出时保存的「最后一刻」画面，用来帮用户分辨同名存档。
         * null / 文件不存在时列表显示占位图标。
         */
        val iconPath: String? = null
    )

    data class CacheItem(
        val file: File,
        val name: String,
        val isDirectory: Boolean
    )

    data class BackupItem(
        val file: File,
        val name: String,
        /** 展示用时间（从文件名解析，格式 yyyy-MM-dd HH:mm:ss） */
        val time: String,
        val isDirectory: Boolean,
        /** 备份类型：level_ = 世界数据，db_ = 玩家数据 */
        val isPlayerData: Boolean = false,
        /** 从文件名解析出的时间戳（毫秒）；0 = 解析失败 */
        val timestamp: Long = 0L
    )

    // ============================================
    // 状态
    // ============================================
    data class ScanResult(val worlds: List<WorldItem>, val error: String? = null)

    private val _scanResultChannel = Channel<ScanResult>(Channel.BUFFERED)
    val scanResultChannel = _scanResultChannel

    private val _offerCreateKey = MutableStateFlow<String?>(null)
    val offerCreateKey: StateFlow<String?> = _offerCreateKey.asStateFlow()

    fun clearOfferCreateKey() { _offerCreateKey.value = null }

    private val _currentPath = MutableStateFlow(MainActivity.PATH_STANDARD)
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()
    var pendingSidebarTask: Runnable? = null

    private var useMultiThread = true

    fun setUseMultiThread(use: Boolean) {
        useMultiThread = use
    }

    /**
     * 并发线程数设置：0 = 自动（按文件规模自适应），>0 = 用户指定
     */
    private var ioThreadCount = 0

    fun setIoThreadCount(n: Int) {
        ioThreadCount = if (n < 0) 0 else n
    }

    /**
     * 自动模式下的自适应线程数：
     * 文件多且大 -> 多线程；文件少或小 -> 少线程（避免线程切换开销）。
     */
    private fun autoThreadCount(totalBytes: Long, fileCount: Int): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            // 太小：不值得并行
            totalBytes < 256 * 1024 || fileCount < 4 -> 1
            // 小规模：轻度并行
            totalBytes < 4L * 1024 * 1024 -> min(2, cores)
            // 中等：核心数
            totalBytes < 64L * 1024 * 1024 -> min(cores, 4)
            // 大存档：I/O 密集，线程数可略高于核心数
            else -> min(cores + 1, 8)
        }
    }

    /**
     * 解析最终使用的线程数。
     * @return 1 表示走单线程
     */
    private fun resolveThreadCount(totalBytes: Long, fileCount: Int): Int {
        if (!useMultiThread) return 1
        if (ioThreadCount > 0) return ioThreadCount
        return autoThreadCount(totalBytes, fileCount)
    }

    /** 递归统计目录总字节数与文件数（用于智能判断是否值得并行） */
    private fun measureTree(dir: File): Pair<Long, Int> {
        var bytes = 0L
        var count = 0
        try {
            dir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    bytes += f.length()
                    count++
                }
            }
        } catch (_: Exception) {}
        return Pair(bytes, count)
    }

    private val _worldList = MutableStateFlow<List<WorldItem>>(emptyList())
    val worldList: StateFlow<List<WorldItem>> = _worldList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadingMessage = MutableStateFlow("")
    val loadingMessage: StateFlow<String> = _loadingMessage.asStateFlow()

    private val _progressMessage = MutableStateFlow("")
    val progressMessage: StateFlow<String> = _progressMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _currentWorldFolder = MutableStateFlow<String?>(null)
    val currentWorldFolder: StateFlow<String?> = _currentWorldFolder.asStateFlow()

    private val _worldNameForTitle = MutableStateFlow<String?>(null)
    val worldNameForTitle: StateFlow<String?> = _worldNameForTitle.asStateFlow()

    private val _worldSeedForDisplay = MutableStateFlow<String?>(null)
    val worldSeedForDisplay: StateFlow<String?> = _worldSeedForDisplay.asStateFlow()

    private val _cacheList = MutableStateFlow<List<CacheItem>>(emptyList())
    val cacheList: StateFlow<List<CacheItem>> = _cacheList.asStateFlow()

    /**
     * 备份列表。
     * ⚠️ 除了本该有的 [backupList]（供 UI 取值），这里额外用一个 **Channel** 做「扫描完成」的事件通知。
     *
     * 为什么不能只用 StateFlow：`_backupList.value = parsed` 在**内容与上次相同**时不会发射
     * （`StateFlow` 用 `equals` 去重），于是 `backupList.drop(1).collect { ... }` 永远等不到事件，
     * 表现就是「点恢复备份没任何反应」。而扫同一存档两次得到的结果本来就是相等的。
     */
    private val _backupList = MutableStateFlow<List<BackupItem>>(emptyList())
    val backupList: StateFlow<List<BackupItem>> = _backupList.asStateFlow()

    private val _backupScanDone = Channel<List<BackupItem>>(Channel.BUFFERED)
    val backupScanDone = _backupScanDone

    /** 当前备份列表所属存档的世界名（读不到时为 null，UI 回退显示文件夹名） */
    private val _backupWorldName = MutableStateFlow<String?>(null)
    val backupWorldName: StateFlow<String?> = _backupWorldName.asStateFlow()

    /** 草稿箱当前筛选的存档名（null = 全部） */
    private val _draftFilterFolder = MutableStateFlow<String?>(null)
    val draftFilterFolder: StateFlow<String?> = _draftFilterFolder.asStateFlow()
    fun setDraftFilterFolder(folder: String?) { _draftFilterFolder.value = folder }

    fun clearToast() { _toastMessage.value = null }
    fun clearError() { _errorMessage.value = null }

    // ============================================
    // 工作目录
    // ============================================
    var currentWorkingDbPath: String? = null
    var currentWorkingFileOrDir: String? = null
    var lastLoadedWorldFolder: String? = null
    var safTreeUri: android.net.Uri? = null

    private fun baseDir(context: Context): File {
        val p = _currentPath.value
        // 受限路径（外部 Android/data）与「软件内部」（/data/user/0/常驻私有目录）
        // 都不适合把工作副本放在公共 Download —— 前者是权限问题，
        // 后者是路径本身就在 /data，放公共目录会跨分区，rename 不再原子。
        val restricted = p.contains("Android/data") || p.startsWith("/data/")
        return if (restricted) {
            context.getExternalFilesDir(null)!!
        } else {
            val publicDir = AppDirs.publicRoot()
            if (!publicDir.exists()) publicDir.mkdirs()
            publicDir
        }
    }


    fun worksDir(context: Context): File {
        val w = File(baseDir(context), "Works")
        if (!w.exists()) w.mkdirs()
        return w
    }

    fun backupsDir(context: Context): File {
        val b = File(baseDir(context), "Backups")
        if (!b.exists()) b.mkdirs()
        return b
    }

    // ============================================
    // 路径管理
    // ============================================
    fun switchPath(newPath: String) {
        _currentPath.value = newPath
    }

    /**
     * issue #6：启动时恢复上次使用的路径（由 MainActivity 从 prefs 读出后注入）
     */
    fun restorePath(savedPath: String?) {
        if (!savedPath.isNullOrBlank()) {
            _currentPath.value = savedPath
            AppLogger.info("Path", R.string.log_path_restored, savedPath)
        }
    }

    // ============================================
    // 路径可写性判断（issue #6）
    // ============================================

    /**
     * 该路径是否走 Android/data 受限目录（Android 11+ 需要 Shizuku 或 MANAGE_EXTERNAL_STORAGE）
     */
    fun isRestrictedPath(path: String = _currentPath.value): Boolean {
        // /data/... 是应用私有目录，连 shell 都进不去，唯一出路是 root
        return path.contains("Android/data") || path.contains("Android/obb") ||
            path.startsWith("/data/")
    }


    /**
     * 实际探测目标目录是否可直接写入（不靠猜测）。
     * 通过创建并删除一个临时文件来判断，比 canWrite() 可靠。
     */
    fun canWriteDirectly(targetDir: File): Boolean {
        return try {
            if (!targetDir.exists()) {
                // 不存在则尝试创建
                if (!targetDir.mkdirs()) return false
            }
            if (!targetDir.isDirectory) return false
            val probe = File(targetDir, ".nbt_write_test_${System.currentTimeMillis()}")
            val ok = probe.createNewFile()
            if (ok) probe.delete()
            ok
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 从路径里推断「这个目录属于哪个应用包名」。
     *
     * 支持两种形式：
     *   /data/user/0/<pkg>/...                        （应用私有目录）
     *   /storage/emulated/0/Android/data/<pkg>/...    （外部私有目录）
     *
     * 找不到就返回 null —— 比如 Download 下的普通目录，不属于任何 app。
     *
     * ⚠️ 不能写死 com.mojang.minecraftpe：
     *    用户可能装共存版（不同包名），也可能自定义路径指向别的 app。
     */
    private fun packageNameFromPath(path: String): String? {
        val p = path.trim()
        val markerA = "/data/user/"
        val markerB = "/Android/data/"
        for ((marker, skip) in listOf(markerA to 1, markerB to 1)) {
            val idx = p.indexOf(marker)
            if (idx < 0) continue
            val rest = p.substring(idx + marker.length)
            val parts = rest.split("/")
            // /data/user/0/<pkg>/...  -> 第一段是 user id，第二段才是包名
            val pkg = if (marker == markerA) parts.getOrNull(skip)
                      else parts.getOrNull(0)
            if (!pkg.isNullOrBlank()) return pkg
        }
        return null
    }

    /**
     * 自动修复存档归属。
     *
     * 为什么需要：历史版本用 root 写回时留下了 root:root 的 db 目录，
     * 那些存档游戏打不开（owner 不对 + 权限 750 让 other 进不去目录）。
     * 用户手上已经有这种坏档，光修「以后不再写坏」是不够的。
     *
     * 判定标准：目录/文件的 uid 必须是该 app 的 uid；不是就改回来。
     * 只在 root 模式下做，且失败不阻断（只是打日志）。
     */
    fun repairWorldOwnership(path: String) {
        if (PrivilegedExec.mode() != PrivilegedExec.Mode.ROOT) return
        if (!checkShizukuAvailable()) return
        try {
            val pkg = packageNameFromPath(path) ?: return
            val appOwner = statOwner("/data/user/0/$pkg")
            if (!appOwner.contains(":")) return
            val uid = appOwner.substringBefore(":")
            val gid = appOwner.substringAfter(":")
            if (uid == "0") return          // 拿不到真实 uid，别乱改

            // 只看最外层：正常时它已经是 app 的 uid，不用遍历整个目录
            val cur = statOwner(path)
            if (cur == "$uid:$gid") return

            AppLogger.warn("Repair", "存档归属异常 $cur -> $uid:$gid  path=$path")
            runShizukuCmd(arrayOf("sh", "-c",
                "chown -R \"$uid:$gid\" \"$path\"")).waitFor()
            runShizukuCmd(arrayOf("sh", "-c",
                "find \"$path\" -type d -exec chmod 770 {} +")).waitFor()
            runShizukuCmd(arrayOf("sh", "-c",
                "find \"$path\" -type f -exec chmod 660 {} +")).waitFor()
            AppLogger.info("Repair", "存档归属已修复: $path")
        } catch (_: Throwable) {
        }
    }

    /** 用提权方式读取路径的 owner:group（形如 "10460:10460"）；失败返回空串 */
    private fun statOwner(path: String): String = try {
        if (!checkShizukuAvailable()) ""
        else {
            val p = runShizukuCmd(
                arrayOf("sh", "-c", "stat -c %u:%g \"$path\" 2>/dev/null")
            )
            val s = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            s
        }
    } catch (_: Exception) {
        ""
    }

    /**
     * 用提权方式判断「这个路径存在吗」。
     *
     * 为什么需要：/data/user/0/... 只有 root 看得见，
     * File.exists() 会恒为 false，导致「存档明明在却提示找不到」。
     */
    fun dirExists(path: String): Boolean {
        if (!checkShizukuAvailable()) return false
        return try {
            runShizukuCmd(arrayOf("sh", "-c", "test -d \"$path\"")).waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    /** 同 [dirExists]，但判断的是文件 */
    fun fileExists(path: String): Boolean {
        if (!checkShizukuAvailable()) return false
        return try {
            runShizukuCmd(arrayOf("sh", "-c", "test -f \"$path\"")).waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 用 Shizuku 真实探测「能否往这个目录写」。
     *
     * 为什么要实测而不是只看 checkShizukuAvailable()：
     *  · 小米 HyperOS / MIUI 上，「USB 调试（安全设置）」没开时，
     *    Shizuku 会显示「已连接」，但 shell 命令实际被降权 ——
     *    表现就是 cp 一直失败、却看不出原因。
     *  · MediaProvider 是 Mainline 模块，策略可能随 Play 系统更新变化。
     *
     * @return true = 可写；false = 不可写（调用方应改用 SAF 或提示用户）
     */
    fun canShizukuWrite(targetDir: File): Boolean {
        if (!checkShizukuAvailable()) return false
        return try {
            if (!targetDir.exists()) {
                val mk = runShizukuCmd(arrayOf("sh", "-c", "mkdir -p '${targetDir.absolutePath}'"))
                if (mk.waitFor() != 0) return false
            }
            val probe = File(targetDir, ".nibnya_write_probe")
            // ⚠️ 重定向符号后必须是完整的「带引号路径」，
            //    写成 > '路径' 会被 shell 当成字面文件名的一部分。
            val p = runShizukuCmd(
                arrayOf("sh", "-c", "echo ok > \"${probe.absolutePath}\" && rm -f \"${probe.absolutePath}\"")
            )

            p.waitFor() == 0
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 判断保存目标是否需要 Shizuku（受限路径 且 无法直接写）
     */
    fun needsShizukuFor(targetPath: String): Boolean {
        val dir = File(targetPath).let { if (it.isDirectory) it else it.parentFile }
        if (dir == null) return false
        return !canWriteDirectly(dir)
    }

    fun setSafPath(uri: android.net.Uri) {
        safTreeUri = uri
        _currentPath.value = "$uri/"
    }

    // ============================================
    // 扫描存档
    // ============================================
    fun scanWorlds(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = getString(R.string.status_scanning)
            try {
                val dir = File(_currentPath.value)
                val rawFolders = mutableListOf<String>()

                // === 新增：检查当前路径本身是否就是一个世界文件夹 ===
                val checkLevel = File(dir, "level.dat")
                val checkDb = File(dir, "db")
                if (checkLevel.exists() && checkDb.exists()) {
                    // 当前路径本身就是世界文件夹，直接加载
                    val realName = getWorldRealNameByDir(dir) ?: "Unknown"
                    val item = WorldItem(dir.name, realName, levelDatTime(dir), worldIconPath(dir))
                    _worldList.value = listOf(item)
                    _scanResultChannel.trySend(ScanResult(listOf(item)))
                    _isLoading.value = false
                    return@launch
                }

                if (dir.exists() && dir.canRead()) {
                    dir.listFiles()?.forEach { if (it.isDirectory) rawFolders.add(it.name) }
                }

                if (rawFolders.isEmpty() && checkShizukuAvailable()) {
                    try {
                        val p = runShizukuCmd(arrayOf("sh", "-c", "ls \"${_currentPath.value}\""))
                        val r = BufferedReader(InputStreamReader(p.inputStream))
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            line?.trim()?.let { if (it.isNotEmpty()) rawFolders.add(it) }
                        }
                        p.waitFor()
                    } catch (_: Exception) {}
                }

                val result = mutableListOf<WorldItem>()
                for (name in rawFolders) {
                    val fullPath = _currentPath.value + name
                    val checkLevel = File(fullPath, "level.dat")
                    val checkDb = File(fullPath, "db")
                    var isValid = false

                    if (checkLevel.exists() && checkDb.exists()) isValid = true
                    else if (checkShizukuAvailable()) {
                        try {
                            val c1 = runShizukuCmd(arrayOf("sh", "-c", "ls \"${checkLevel.absolutePath}\"")).waitFor()
                            val c2 = runShizukuCmd(arrayOf("sh", "-c", "ls -d \"${checkDb.absolutePath}\"")).waitFor()
                            if (c1 == 0 && c2 == 0) isValid = true
                        } catch (_: Exception) {}
                    }

                    if (isValid) {
                        val realName = getWorldRealName(context, name) ?: "Unknown"
                        result.add(WorldItem(name, realName, levelDatTime(File(fullPath)), worldIconPath(File(fullPath))))
                    }
                }

                // 按最后游玩时间倒序：最近玩过的排前面
                result.sortByDescending { it.lastModified }
                _worldList.value = result
                _scanResultChannel.trySend(ScanResult(result))
            } catch (e: Exception) {
                AppLogger.error("ScanWorlds", "Scan failed", e)
                _errorMessage.value = e.toString()
                _scanResultChannel.trySend(ScanResult(emptyList(), e.toString()))
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun scanWorldsViaSaf(context: Context, treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = NbtTranslator.getString(R.string.status_scanning)

            try {
                val resolvedPath = SafPathResolver.resolveTreeUriToPath(context, treeUri)

                if (resolvedPath != null && SafPathResolver.isProbablyUsablePath(resolvedPath)) {
                    _currentPath.value = if (resolvedPath.endsWith("/")) resolvedPath else "$resolvedPath/"
                    scanWorlds(context)
                } else {
                    scanWorldsWithDocumentFile(context, treeUri)
                }
            } catch (e: Exception) {
                _errorMessage.value = NbtTranslator.getString(R.string.err_saf_scan_failed, e.message ?: "")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun scanWorldsWithDocumentFile(context: Context, treeUri: Uri) {
        val treeDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return

        val worldFolders = treeDoc.listFiles()
            .filter { it.isDirectory }
            .mapNotNull { folder ->
                val hasLevelDat = folder.findFile("level.dat") != null
                val hasDb = folder.findFile("db") != null
                if (hasLevelDat && hasDb) {
                    val displayName = readWorldNameFromSaf(context, folder) ?: folder.name ?: "Unknown"
                    WorldItem(folderName = folder.name ?: "", displayName = displayName)
                } else null
            }

        _worldList.value = worldFolders
        _scanResultChannel.trySend(ScanResult(worldFolders))
    }

    private fun readWorldNameFromSaf(context: Context, worldFolder: DocumentFile): String? {
        try {
            val levelnameFile = worldFolder.findFile("levelname.txt") ?: return null
            context.contentResolver.openInputStream(levelnameFile.uri)?.use { input ->
                return java.io.BufferedReader(java.io.InputStreamReader(input)).readLine()
            }
        } catch (e: Exception) {
            android.util.Log.w("WorldVM", "Failed to read levelname.txt via SAF", e)
        }
        return null
    }

    fun loadLevelDatViaSaf(context: Context, folderName: String) {
        val treeUri = safTreeUri ?: return
        lastLoadedWorldFolder = folderName

        // 与 loadLevelDat 一致的会话处理，避免这条路径丢失未保存编辑
        editorVM?.let { evm ->
            evm.saveSession(currentWorkingDbPath)
            persistSession(context)
            if (evm.tryRestoreSession("level.dat")) return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = NbtTranslator.getString(R.string.status_loading_level_dat)

            try {
                val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
                    ?: throw Exception("Invalid tree URI")
                val worldFolder = treeDoc.findFile(folderName)
                    ?: throw Exception("World folder not found")
                val levelDatDoc = worldFolder.findFile("level.dat")
                    ?: throw Exception("level.dat not found")

                val destFile = File(worksDir(context), MainActivity.LEVEL_DAT_NAME)
                if (destFile.exists()) destFile.delete()

                context.contentResolver.openInputStream(levelDatDoc.uri)?.use { input ->
                    java.io.FileOutputStream(destFile).use { output -> input.copyTo(output) }
                }

                currentWorkingFileOrDir = destFile.absolutePath
                val json = BedrockParser.parse(destFile.absolutePath)

                val evm = editorVM ?: return@launch
                evm.setEditingPlayer(false)
                evm.setTargetKey(null)
                evm.setRawNbtData(json)
                evm.nbtDataCache["level.dat"] = json
                evm.navigationStack.clear()
                evm.pathStack.clear()
                evm.scrollPositionStack.clear()
                evm.currentListData = json
                evm.updatePathTitle()

                // 进入编辑态：启动草稿自动保存并记录原始数据
                onEditStarted(context, "level.dat", json)

                updateWorldInfoFromNbt(json)
                _currentWorldFolder.value = folderName
                _isLoading.value = false
                _toastMessage.value = NbtTranslator.getString(R.string.msg_loaded_level_dat)

            } catch (e: Exception) {
                _isLoading.value = false
                _errorMessage.value = e.message ?: NbtTranslator.getString(R.string.label_sub_load_failed)
            }
        }
    }

    // ============================================
    // 读取真实世界名
    // ============================================
    private fun getWorldRealName(context: Context, folderName: String): String? {
        val fullPath = "${_currentPath.value}$folderName/levelname.txt"
        val file = File(fullPath)
        if (file.exists() && file.canRead()) {
            try {
                val name = BufferedReader(InputStreamReader(FileInputStream(file))).readLine()
                if (!name.isNullOrBlank()) return name
            } catch (_: Exception) {}
        }
        if (checkShizukuAvailable()) {
            try {
                val p = runShizukuCmd(arrayOf("sh", "-c", "cat \"$fullPath\""))
                val name = BufferedReader(InputStreamReader(p.inputStream)).readLine()
                p.waitFor()
                if (!name.isNullOrBlank()) return name
            } catch (_: Exception) {}
        }
        return null
    }

    private fun getWorldRealNameByDir(worldDir: File): String? {
        val file = File(worldDir, "levelname.txt")
        if (file.exists() && file.canRead()) {
            try {
                return java.io.BufferedReader(java.io.InputStreamReader(java.io.FileInputStream(file))).readLine()
            } catch (_: Exception) {}
        }
        if (checkShizukuAvailable()) {
            try {
                val p = runShizukuCmd(arrayOf("sh", "-c", "cat \"${file.absolutePath}\""))
                val name = java.io.BufferedReader(java.io.InputStreamReader(p.inputStream)).readLine()
                p.waitFor()
                if (!name.isNullOrBlank()) return name
            } catch (_: Exception) {}
        }
        return null
    }

    // ============================================
    // 草稿箱：自动落盘（防止进程被杀 / 崩溃丢失未保存编辑）
    // ============================================
    // 只负责「把未保存的编辑写进草稿箱」。找回方式是用户主动打开
    // 「草稿箱」（主页「恢复备份」→「草稿箱」），**不再自动弹窗打扰**。

    /** 把当前编辑内容落盘（供 Activity 在 onStop 时调用，防进程被杀） */
    fun persistSessionNow(context: Context) = persistSession(context)

    /**
     * 当前编辑内容的快照。供 DraftAutoSaver 定时落盘 / 崩溃时紧急落盘使用。
     * @return null 表示当前没有可保存的内容
     */
    override fun snapshotForDraft(): DraftAutoSaver.DraftSnapshot? {
        val evm = editorVM ?: return null
        val data = evm.nbtData.value ?: return null
        val isPlayer = evm.isEditingPlayer.value
        // level.dat 固定用 "level.dat"；只有玩家模式才记 targetKey，
        // 否则会把 _currentTargetKey 的默认值 "~local_player" 写进世界数据草稿，
        // 导致草稿箱里标签张冠李戴。
        val key = if (isPlayer) evm.currentTargetKey.value else "level.dat"
        if (key.isNullOrBlank()) return null
        return DraftAutoSaver.DraftSnapshot(
            sessionKey = key,
            data = data,
            isPlayerMode = isPlayer,
            targetKey = if (isPlayer) evm.currentTargetKey.value else null,
            dbPath = currentWorkingDbPath,
            folder = lastLoadedWorldFolder,
            worldName = _worldNameForTitle.value
        )
    }

    /** 立即落盘一份草稿 */
    private fun persistSession(context: Context) {
        val snap = snapshotForDraft() ?: return
        DraftStore.save(
            context = context,
            sessionKey = snap.sessionKey,
            data = snap.data,
            isPlayerMode = snap.isPlayerMode,
            targetKey = snap.targetKey,
            dbPath = snap.dbPath,
            folder = snap.folder,
            // 原始数据由 DraftAutoSaver 按开关携带，这里只写当前数据
            originalData = null,
            worldName = _worldNameForTitle.value,
            maxDrafts = DraftAutoSaver.maxDrafts
        )
    }

    /**
     * 从草稿箱恢复一份草稿到编辑器。
     *
     * 注意：**不自动保存** —— 只把数据套用到编辑器，用户确认无误后自己点「保存修改」。
     * 恢复前先把当前正在编辑的内容落盘，避免丢失当前进度。
     */
    fun restoreDraft(context: Context, draft: DraftStore.Draft) {
        val evm = editorVM ?: return

        // 1) 先落盘当前内容，避免被覆盖丢失
        persistSession(context)

        // 2) 套用草稿数据
        evm.setEditingPlayer(draft.isPlayerMode)
        // 世界数据不要设 targetKey（否则标题/标签会显示成 ~local_player）
        evm.setTargetKey(if (draft.isPlayerMode) draft.targetKey else null)
        evm.setRawNbtData(draft.data)
        evm.nbtDataCache[draft.sessionKey] = draft.data
        evm.navigationStack.clear()
        evm.pathStack.clear()
        evm.scrollPositionStack.clear()
        evm.updatePathTitle()

        // 3) 恢复保存目标：玩家数据需要 dbPath，level.dat 需要存档目录
        draft.dbPath?.takeIf { it.isNotBlank() }?.let { currentWorkingDbPath = it }
        draft.folder?.takeIf { it.isNotBlank() }?.let {
            lastLoadedWorldFolder = it
            _currentWorldFolder.value = it
        }

        // 4) 进入编辑状态，启动草稿自动保存
        DraftAutoSaver.start()

        // 5) 明确提示：还需要手动保存才会写入磁盘
        _toastMessage.value = getString(R.string.toast_draft_restored)
    }

    /**
     * 保存成功后调用：停掉自动保存，并清除对应的草稿。
     * @param sessionKey 与 snapshotForDraft 里使用的 key 一致
     */
    private fun onSavedSuccessfully(context: Context, sessionKey: String?) {
        DraftAutoSaver.stop()
        if (!sessionKey.isNullOrBlank()) DraftStore.remove(context, sessionKey)
    }

    /**
     * 加载存档成功后调用：进入编辑态并记录「改动前的原始数据」。
     * @param key      草稿 key（与 snapshotForDraft 一致）
     * @param original 刚加载进来的原始数据，供「对比改动」使用
     */
    private fun onEditStarted(context: Context, key: String, original: JsonObject?) {
        DraftAutoSaver.rememberOriginal(key, original)
        DraftAutoSaver.start()
    }

    // ============================================
    // 统一入口：自动区分 File / SAF
    // ============================================
    /**
     * 当前是否处于 SAF 模式（拿到了 tree uri **且** 路径是 `content://`）。
     *
     * ⚠️ 必须同时判断 `content://`：`scanWorldsViaSaf` 在「URI 能解析成可读真实路径」时
     * 会把 `_currentPath` 换成真实路径（那种情况 File API 可用），必须继续走 File 分支。
     */
    private fun isSafMode(): Boolean =
        safTreeUri != null && _currentPath.value.startsWith("content://")

    /** 打开世界数据（level.dat）：SAF 模式走 DocumentFile，否则走 File */
    fun openLevelDat(context: Context, folder: String) {
        if (isSafMode()) loadLevelDatViaSaf(context, folder)
        else loadLevelDat(context, folder)
    }

    /** 打开玩家数据（db/）：SAF 模式走 DocumentFile，否则走 File */
    fun openPlayerData(context: Context, folder: String, onSuccess: Runnable? = null) {
        when {
            isSafMode() -> loadPlayerDataViaSaf(context, folder, onSuccess)
            onSuccess == null -> loadPlayerData(context, folder)
            else -> {
                // 与 loadPlayerDataViaSaf 同样用 pendingSidebarTask 承载后续任务
                pendingSidebarTask = onSuccess
                loadPlayerData(context, folder)
            }
        }
    }

    // ============================================
    // 加载 level.dat
    // ============================================
    fun loadLevelDat(context: Context, folder: String) {
        val evm = editorVM ?: return
        evm.saveSession(currentWorkingDbPath)
        persistSession(context)

        val isNewWorld = (lastLoadedWorldFolder == null || lastLoadedWorldFolder != folder)
        if (isNewWorld) {
            evm.reset()
            lastLoadedWorldFolder = folder
        } else if (evm.tryRestoreSession("level.dat")) return

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = getString(R.string.status_loading_level_dat)
            try {
                val worldPath = resolveWorldPath(folder)
                val src = "${worldPath}level.dat"
                val destFile = File(worksDir(context), MainActivity.LEVEL_DAT_NAME)
                if (destFile.exists()) destFile.delete()

                var success = copyFileNative(File(src), destFile)
                if (!success && checkShizukuAvailable()) {
                    val bridgeDir = File(MainActivity.BRIDGE_ROOT)
                    if (!bridgeDir.exists()) bridgeDir.mkdirs()
                    val bridgeFile = MainActivity.BRIDGE_ROOT + "level.dat"
                    runShizukuCmd(arrayOf("sh", "-c", "mkdir -p \"${MainActivity.BRIDGE_ROOT}\"")).waitFor()
                    runShizukuCmd(arrayOf("sh", "-c", "cp \"$src\" \"$bridgeFile\"")).waitFor()
                    runShizukuCmd(arrayOf("sh", "-c", "chmod 777 \"$bridgeFile\"")).waitFor()
                    val bF = File(bridgeFile)
                    if (bF.exists()) { copyFile(bF, destFile); success = true }
                }

                if (!success) throw Exception("Failed to copy level.dat")

                currentWorkingFileOrDir = destFile.absolutePath

                val json = BedrockParser.parse(destFile.absolutePath)

                evm.setEditingPlayer(false)
                evm.setTargetKey(null)

                evm.setRawNbtData(json)  // 内部赋值
                evm.nbtDataCache["level.dat"] = json
                evm.navigationStack.clear()
                evm.pathStack.clear()
                evm.scrollPositionStack.clear()
                evm.currentListData = json
                evm.updatePathTitle()

                // 进入编辑态：启动草稿自动保存并记录原始数据
                onEditStarted(context, "level.dat", json)

                // 更新世界信息
                updateWorldInfoFromNbt(json)

                _currentWorldFolder.value = folder
                _isLoading.value = false
                _toastMessage.value = getString(R.string.msg_loaded_level_dat)
            } catch (e: Exception) {
                AppLogger.error("LoadLevelDat", "Load level.dat failed", e)
                _isLoading.value = false
                _errorMessage.value = e.message ?: getString(R.string.label_sub_load_failed)
            }
        }
    }

    // ============================================
    // 加载玩家数据
    // ============================================

    fun loadPlayerDataViaSaf(context: Context, folderName: String, onSuccess: Runnable? = null) {
        val evm = editorVM ?: return
        evm.saveSession(currentWorkingDbPath)
        persistSession(context)
        if (onSuccess == null && evm.tryRestoreSession("~local_player")) return

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = NbtTranslator.getString(R.string.msg_loading_player)
            try {
                val treeUri = safTreeUri ?: throw Exception("SAF URI is null")
                val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
                    ?: throw Exception("Invalid tree URI")
                val worldFolder = treeDoc.findFile(folderName)
                    ?: throw Exception("World folder not found: $folderName")
                val dbFolder = worldFolder.findFile("db")
                    ?: throw Exception("db folder not found")

                val uniqueId = System.currentTimeMillis().toString()
                val workDir = File(worksDir(context), "working_db_$uniqueId")
                if (!workDir.exists()) workDir.mkdirs()

                // 递归从 SAF 复制 db 文件夹
                safCopyDirectory(context, dbFolder, workDir)

                cleanLevelDbMeta(workDir)
                currentWorkingDbPath = workDir.absolutePath

                val dbManager = PlayerDbManager(workDir.absolutePath)
                val data = dbManager.readLocalPlayer()
                dbManager.close()

                val playerDataObj = BedrockParser.parseBytes(data)
                evm.setEditingPlayer(true)
                evm.setTargetKey("~local_player")
                evm.setRawNbtData(playerDataObj)
                evm.nbtDataCache["~local_player"] = playerDataObj
                evm.navigationStack.clear()
                evm.pathStack.clear()
                evm.scrollPositionStack.clear()
                evm.updatePathTitle()

                // 进入编辑态：启动草稿自动保存并记录原始数据
                onEditStarted(context, "~local_player", playerDataObj)

                _currentWorldFolder.value = folderName
                _isLoading.value = false
                _toastMessage.value = NbtTranslator.getString(R.string.toast_player_loaded_success)
                onSuccess?.let { pendingSidebarTask = it }

            } catch (e: Exception) {
                _isLoading.value = false
                _errorMessage.value = e.message
                if (e.message?.contains("DB_CORRUPT") == true) {
                    currentWorkingDbPath?.let { dbPath ->
                        _errorMessage.value = "DB_CORRUPT:$dbPath:$folderName"
                    }
                }
            }
        }
    }

    fun loadPlayerData(context: Context, folder: String) {
        val evm = editorVM ?: return
        evm.saveSession(currentWorkingDbPath)
        persistSession(context)
        if (evm.tryRestoreSession("~local_player")) return

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = getString(R.string.msg_loading_player)
            try {
                val worldDir = File(resolveWorldPath(folder))
                val srcPath = File(worldDir, "db").absolutePath
                val srcDirFile = File(srcPath)

                // 打开前先修一次归属：老版本留下的 root:root 坏档靠这个救回来
                repairWorldOwnership(srcPath)


                // 打开前先修一次归属：老版本留下的 root:root 坏档靠这个救回来
                repairWorldOwnership(srcPath)


                val canUseShizuku = checkShizukuAvailable()
                var useNative = false
                if (srcDirFile.exists() && srcDirFile.canRead() && srcDirFile.listFiles() != null) {
                    useNative = true
                } else if (!canUseShizuku) {
                    // issue #6：给出可操作的提示，而不是笼统的「需要 Shizuku」
                    throw Exception(getString(R.string.msg_read_denied, srcPath))
                }

                // 清理锁
                try {
                    if (useNative) File(srcDirFile, "LOCK").delete()
                    else {
                        val p = runShizukuCmd(arrayOf("sh", "-c", "ls \"$srcPath/LOCK\""))
                        if (p.waitFor() == 0) runShizukuCmd(arrayOf("sh", "-c", "rm -f \"$srcPath/LOCK\"")).waitFor()
                    }
                } catch (_: Exception) {}

                val uniqueId = System.currentTimeMillis().toString()
                val workDir = File(worksDir(context), "working_db_$uniqueId")
                val appPrivatePath = workDir.absolutePath

                if (useNative) {
                    if (!workDir.exists()) workDir.mkdirs()
                    smartCopy(srcDirFile, workDir)
                } else {
                    val bridgePath = MainActivity.BRIDGE_ROOT + "load_db_$uniqueId"
                    runShizukuCmd(arrayOf("sh", "-c", "mkdir -p \"${MainActivity.BRIDGE_ROOT}\"")).waitFor()
                    createNoMedia()
                    runShizukuCmd(arrayOf("sh", "-c", "rm -rf \"$bridgePath\"")).waitFor()
                    runShizukuCmd(arrayOf("sh", "-c", "mkdir -p \"$bridgePath\"")).waitFor()

                    val cmd = "cp -rf \"$srcPath/.\" \"$bridgePath/\""
                    val pCopy = runShizukuCmd(arrayOf("sh", "-c", cmd))
                    val exitCode = pCopy.waitFor()
                    if (exitCode != 0) {
                        val err = BufferedReader(InputStreamReader(pCopy.errorStream)).readText()
                        throw Exception(getString(R.string.text_shizuku_copy_error, exitCode, err))
                    }
                    runShizukuCmd(arrayOf("sh", "-c", "chmod -R 777 \"$bridgePath\"")).waitFor()
                    val bridgeDir = File(bridgePath)
                    if (!bridgeDir.exists() || bridgeDir.list().isNullOrEmpty())
                        throw Exception(getString(R.string.text_source_dir_empty, srcPath))
                    if (!workDir.exists()) workDir.mkdirs()
                    smartCopy(bridgeDir, workDir)
                    runShizukuCmd(arrayOf("sh", "-c", "rm -rf \"$bridgePath\"")).waitFor()
                }

                cleanLevelDbMeta(workDir)

                currentWorkingDbPath = appPrivatePath

                val dbManager = PlayerDbManager(appPrivatePath)
                val data = dbManager.readLocalPlayer()
                dbManager.close()

                val playerDataObj = BedrockParser.parseBytes(data)

                evm.setEditingPlayer(true)
                evm.setTargetKey("~local_player")
                evm.setRawNbtData(playerDataObj)
                evm.nbtDataCache["~local_player"] = playerDataObj
                evm.navigationStack.clear()
                evm.pathStack.clear()
                evm.scrollPositionStack.clear()
                evm.updatePathTitle()

                // 进入编辑态：启动草稿自动保存并记录原始数据
                onEditStarted(context, "~local_player", playerDataObj)

                _currentWorldFolder.value = folder
                _isLoading.value = false
                _toastMessage.value = getString(R.string.toast_player_loaded_success)
            } catch (e: Exception) {
                AppLogger.error("LoadPlayer", "Load player failed", e)
                _isLoading.value = false
                _errorMessage.value = e.message
                // 数据库损坏
                if (e.message?.contains("DB_CORRUPT") == true) {
                    currentWorkingDbPath?.let { dbPath ->
                        _errorMessage.value = "DB_CORRUPT:$dbPath:$folder"
                    }
                }
            }
        }
    }

    // ============================================
    // 加载指定 Key
    // ============================================
    fun loadSpecificKey(context: Context, keyName: String) {
        val evm = editorVM ?: return
        evm.saveSession(currentWorkingDbPath)
        persistSession(context)
        if (evm.tryRestoreSession(keyName)) return
        if (evm.nbtDataCache.containsKey(keyName)) {
            evm.setTargetKey(keyName)
            evm.setEditingPlayer(true)
            evm.setRawNbtData(evm.nbtDataCache[keyName])
            evm.navigationStack.clear()
            evm.pathStack.clear()
            evm.scrollPositionStack.clear()
            evm.updatePathTitle()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                currentWorkingDbPath?.let { File(it, "LOCK").delete() }
                val dbPath = currentWorkingDbPath ?: throw Exception(getString(R.string.text_db_path_is_null))
                val db = PlayerDbManager(dbPath)
                val data: ByteArray
                try {
                    data = db.readSpecificKey(keyName)
                } catch (e: Exception) {
                    db.close()
                    _isLoading.value = false
                    _offerCreateKey.value = keyName
                    return@launch
                }
                db.close()
                val jsonData = BedrockParser.parseBytes(data)

                evm.setTargetKey(keyName)
                evm.setEditingPlayer(true)
                evm.setRawNbtData(jsonData)
                evm.nbtDataCache[keyName] = jsonData
                evm.navigationStack.clear()
                evm.pathStack.clear()
                evm.scrollPositionStack.clear()
                evm.updatePathTitle()

                // 进入编辑态：启动草稿自动保存并记录原始数据
                onEditStarted(context, keyName, jsonData)
                _isLoading.value = false
                _toastMessage.value = getString(R.string.toast_loaded, keyName)
            } catch (e: Exception) {
                AppLogger.error("LoadKey", "Load key $keyName failed", e)
                _isLoading.value = false
                _errorMessage.value = getString(R.string.err_load_failed_with_msg, e.message)
            }
        }
    }

    fun createNewGlobalData(keyName: String?) {
        val evm = editorVM ?: return
        val emptyNbt = JsonObject()
        evm.setTargetKey(keyName)
        evm.setEditingPlayer(true)
        evm.setRawNbtData(emptyNbt)
        evm.navigationStack.clear()
        evm.pathStack.clear()
        evm.scrollPositionStack.clear()
        evm.updatePathTitle()
    }

    // ============================================
    // 保存
    // ============================================
    fun saveAndPushBack(context: Context, dataToSave: JsonObject?, folder: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = getString(R.string.status_saving)
            _progressMessage.value = getString(R.string.status_backup_save, 0)
            try {
                // 备份阶段占进度 0~50%；createBackup 内部会随拷贝字节往上推
                val backed = createBackup(context, folder)
                // 备份阶段结束（跳过节流时直接落到 50%），接着是写回存档 50~100%
                _progressMessage.value =
                    getString(R.string.status_backup_save, if (backed) 50 else 50)
                val evm = editorVM ?: throw Exception(getString(R.string.msg_editorvm_missing))

                if (evm.isEditingPlayer.value) {
                    val dbPath = currentWorkingDbPath ?: throw Exception(getString(R.string.text_db_path_is_null))
                    val oldWorkDir = File(dbPath)
                    if (!oldWorkDir.exists()) throw Exception(getString(R.string.label_sub_workdir_lost))

                    val uniqueId = System.currentTimeMillis().toString()
                    val newWorkDir = File(worksDir(context), "working_db_$uniqueId")
                    smartCopy(oldWorkDir, newWorkDir)
                    cleanLevelDbMeta(newWorkDir)

                    // 先把当前正在编辑的数据写入缓存，确保不遗漏
                    val currentKey = evm.currentTargetKey.value
                    AppLogger.info("ListDebug", "saveAndPushBack(玩家): currentKey=$currentKey, dataToSave keys=${dataToSave?.keySet()?.joinToString(",") { it } ?: "null"}")
                    if (currentKey != null && dataToSave != null) {
                        evm.nbtDataCache[currentKey] = dataToSave
                        AppLogger.info("ListDebug", "saveAndPushBack(玩家): nbtDataCache[$currentKey] 已更新为 dataToSave")
                    } else {
                        AppLogger.warn("ListDebug", "saveAndPushBack(玩家): currentKey=$currentKey 或 dataToSave=null，未更新缓存!")
                    }

                    // 遍历缓存，写入全部修改过的数据
                    val db = PlayerDbManager(newWorkDir.absolutePath)

                    // 确保把 DB 中所有已修改的数据都写回（包括拼图生成的地图）
                    val allKeys = mutableSetOf<String>()
                    allKeys.addAll(evm.nbtDataCache.keys.filterNotNull())
// 从 DB 中扫描所有已知 key
                    try {
                        allKeys.addAll(db.listMapKeys())
                        allKeys.addAll(db.listVillageKeys())
                        allKeys.addAll(db.listPlayerKeys())
                    } catch (e: Exception) { AppLogger.warn("World", "列举 DB 键失败，列表可能缺项: ${e.message}") }

// 遍历写入
                    for (key in allKeys) {
                        val data = evm.nbtDataCache[key]
                        if (data != null) {
                            val bytes = BedrockParser.writeToBytes(data)
                            db.writeSpecificKey(key, bytes)
                            AppLogger.info("ListDebug", "写DB(遍历allKeys): key=$key, bytes=${bytes.size}, keys=${data.keySet().joinToString(",") { it }}")
                        }
                    }

                    for ((key, data) in evm.nbtDataCache) {
                        if (key != null && data != null) {
                            val bytes = BedrockParser.writeToBytes(data)
                            db.writeSpecificKey(key, bytes)
                            AppLogger.info("ListDebug", "写DB(遍历缓存): key=$key, bytes=${bytes.size}, keys=${data.keySet().joinToString(",") { it }}")
                        }
                    }

                    db.close()

                    val mcDbPath = "${resolveWorldPath(folder)}db/"
                    val mcDbDir = File(mcDbPath)

                    // issue #6：按「可写性」决定写入方式，而不是单纯看有没有 Shizuku
                    // 1) 先尝试直接文件写入（Download/games 等公共目录可写）
                    var savedDirectly = false
                    // 2) 直接写：写回阶段占进度 50~100%
                    if (canWriteDirectly(mcDbDir)) {
                        try {
                            copyDirectoryMetered(newWorkDir, mcDbDir) { done, total ->
                                val half = if (total > 0) (done * 100 / total).toInt() / 2 else 50
                                _progressMessage.value =
                                    getString(R.string.status_backup_save, 50 + half.coerceIn(0, 50))
                            }
                            savedDirectly = true
                            AppLogger.info("Save", R.string.log_save_player_direct_ok, mcDbPath)
                        } catch (e: Exception) {
                            AppLogger.warn("Save", R.string.log_save_fail_try_shizuku, e.message)
                        }
                    }

                    // 2) SAF 模式：目标路径是 content://，File 与 Shizuku 都无效 -> 走 DocumentFile
                    //    ⚠️ 失败必须抛错。绝不能像原来那样「发个命令就当成功」，那是数据丢失。
                    if (!savedDirectly && isSafMode()) {
                        val worldDoc = SafIo.worldDirOf(context, safTreeUri!!, folder)
                            ?: throw Exception(getString(R.string.msg_write_denied, mcDbPath))
                        val (safOk, safErr) = SafIo.replaceDirInSaf(context, worldDoc, "db", newWorkDir)
                        if (!safOk) throw Exception(safErr ?: getString(R.string.msg_write_denied, mcDbPath))
                        savedDirectly = true
                        AppLogger.info("Save", R.string.log_save_player_saf_ok, mcDbPath)
                    }

                    // 3) 直接写与 SAF 都不行，且 Shizuku 可用 -> 走桥接
                    if (!savedDirectly) {
                        if (checkShizukuAvailable()) {
                            val bridgeSave = MainActivity.BRIDGE_ROOT + "save_db_$uniqueId"
                            runShizukuCmd(arrayOf("sh", "-c", "mkdir -p \"${MainActivity.BRIDGE_ROOT}\"")).waitFor()
                            createNoMedia()
                            runShizukuCmd(arrayOf("sh", "-c", "rm -rf \"$bridgeSave\"")).waitFor()
                            smartCopy(newWorkDir, File(bridgeSave))

                            // ── 原子替换写回 ────────────────────────────────
                            // 旧写法 `cp -rf "$bridgeSave/." "$dbPath"` 有两个问题：
                            //   1. 只覆盖不清理：新数据比旧数据少文件时旧文件会残留，
                            //      LevelDB 对残留文件敏感，可能导致存档读不出来；
                            //   2. 中途失败时原存档处于「半新半旧」状态，且没有退路。
                            // 现在：写新目录 -> 原目录改名让位 -> 新目录顶上 ->
                            //       成功删旧，失败回滚。任何时刻原存档都在。
                            // 临时目录与 db 同级（同一分区，rename 才保持原子性）
                            val dbParent = File(mcDbPath).parentFile!!.absolutePath
                            val tmpNew = "$dbParent/${PrivilegedExec.TMP_PREFIX}db_new_$uniqueId"
                            val tmpOld = "$dbParent/${PrivilegedExec.TMP_PREFIX}db_old_$uniqueId"



                            // ── 记录原目录的 owner / group ──────────────────
                            // ⚠️ root 用 cp/mv 写出来的文件属于 root:root。
                            //    而 Minecraft 以**它自己的 uid** 运行（实测 10460:10460），
                            //    文件归到 root 之后它连读都读不了 ——
                            //    表现就是「文件都在、大小正常，但进不去」，
                            //    有时还会显示成 4167549952 这种荒谬体积。
                            //
                            //    所以写回后必须把归属还给「该存档原本的拥有者」。
                            //    注意不能写死包名：用户可能装共存版 / 换包名，
                            //    也可能是自定义路径指向别的 app 的私有目录。
                            //    这里统一从路径本身推断。
                            // ⚠️ 顺序很重要，别搞反：
                            //    不能优先读「当前 db 目录」的归属 —— 一旦它已经被
                            //    写坏成 root:root，读回来的就是 0:0，于是每次保存都
                            //    继续 chown 成 0:0，形成自我循环、永远修不好。
                            //    权威来源是「该 app 自己的目录归属」。
                            val ownerPkg = packageNameFromPath(mcDbPath)
                            var targetUid = ""
                            var targetGid = ""

                            // ① 首选：应用自己的 uid/gid（最可靠，且不会被写坏）
                            if (ownerPkg != null) {
                                val appOwner = statOwner("/data/user/0/$ownerPkg")
                                if (appOwner.contains(":")) {
                                    targetUid = appOwner.substringBefore(":")
                                    targetGid = appOwner.substringAfter(":")
                                }
                            }

                            // ② 兜底：识别不出包名时，才参考原 db 目录的归属
                            val ownerInfo = statOwner(mcDbPath)
                            if (targetUid.isEmpty() || targetGid.isEmpty()) {
                                if (ownerInfo.contains(":")) {
                                    targetUid = ownerInfo.substringBefore(":")
                                    targetGid = ownerInfo.substringAfter(":")
                                }
                            }

                            // 兜底 1：读不到原目录归属 -> 用该包名自己的 uid/gid
                            if ((targetUid.isEmpty() || targetGid.isEmpty()) && ownerPkg != null) {
                                val fallback = statOwner("/data/user/0/$ownerPkg")
                                if (fallback.contains(":")) {
                                    targetUid = fallback.substringBefore(":")
                                    targetGid = fallback.substringAfter(":")
                                    AppLogger.warn("Save", "原 db 归属未知，回退到应用目录 $fallback")
                                }
                            }
                            // 兜底 2：路径里根本没有包名（例如 Download 下的导出目录）
                            //         -> 不属于任何 app，保持不动即可，不需要 chown
                            AppLogger.info(
                                "Save",
                                "归属来源 pkg=$ownerPkg  db=$ownerInfo  使用=$targetUid:$targetGid"
                            )


                            // ① 新数据先完整落到同一分区（rename 才能保持原子性）
                            // 用「源/.」+「目标/」的形式：内容直接落进目标，不会多套一层
                            runShizukuCmd(arrayOf("sh", "-c", "rm -rf \"$tmpNew\""))
                                .waitFor()
                            val cpNew = runShizukuCmd(
                                arrayOf("sh", "-c",
                                    "mkdir -p \"$tmpNew\" && cp -rf \"$bridgeSave/.\" \"$tmpNew/\"")
                            )

                            var exit = cpNew.waitFor()
                            var cpErr = cpNew.errorStream.bufferedReader().readText().trim()

                            // ①.5 先把归属修好，再顶上。
                            //     在 tmpNew 上 chown 比在最终目录上安全：
                            //     万一失败，归档动作还没发生，原目录完好无损。
                            //     权限位沿用游戏自己的写法（文件 660 / 目录 770）。
                            if (exit == 0 && targetUid.isNotEmpty() && targetGid.isNotEmpty()) {
                                runShizukuCmd(arrayOf("sh", "-c",
                                    "chown -R \"$targetUid:$targetGid\" \"$tmpNew\"")).waitFor()
                                runShizukuCmd(arrayOf("sh", "-c",
                                    "find \"$tmpNew\" -type d -exec chmod 770 {} +")).waitFor()
                                runShizukuCmd(arrayOf("sh", "-c",
                                    "find \"$tmpNew\" -type f -exec chmod 660 {} +")).waitFor()
                                AppLogger.info(
                                    "Save",
                                    "tmp 已归还归属: $targetUid:$targetGid"
                                )
                            } else if (exit == 0) {
                                // 读不到归属但路径确实属于某个 app：
                                // 这时候写下去会得到 root:root 的文件，
                                // 游戏一定读不了 —— 与其写坏，不如明确失败。
                                AppLogger.warn(
                                    "Save",
                                    "读不到归属信息（pkg=$ownerPkg），中止写回"
                                )
                                runShizukuCmd(arrayOf("sh", "-c", "rm -rf \"$tmpNew\"")).waitFor()
                                throw Exception(getString(R.string.msg_cannot_restore_owner, ownerPkg ?: mcDbPath))
                            }




                            if (exit == 0) {
                                // ② 原目录让位（改名而不是删除，随时可回滚）
                                runShizukuCmd(arrayOf("sh", "-c", "rm -rf \"$tmpOld\"")).waitFor()
                                val mvOld = runShizukuCmd(
                                    arrayOf("sh", "-c", "mv \"$mcDbPath\" \"$tmpOld\"")
                                )
                                if (mvOld.waitFor() != 0) {
                                    exit = 1
                                    cpErr = "mv old failed"
                                } else {
                                    // ③ 新目录顶上
                                    val mvNew = runShizukuCmd(
                                        arrayOf("sh", "-c", "mv \"$tmpNew\" \"$mcDbPath\"")
                                    )
                                    if (mvNew.waitFor() != 0) {
                                        // 回滚：把原目录改回来
                                        runShizukuCmd(
                                            arrayOf("sh", "-c", "mv \"$tmpOld\" \"$mcDbPath\"")
                                        ).waitFor()
                                        exit = 1
                                        cpErr = "mv new failed (rolled back)"
                                    } else {
                                        // ③.45 mv 之后目录名已经变了，
                                        //       再对最终目录本体补一次归属，
                                        //       确保 db/ 自己是 app 的 uid/gid，
                                        //       而不是从 tmpNew 继承来的 root。
                                        if (targetUid.isNotEmpty() && targetGid.isNotEmpty()) {
                                            runShizukuCmd(arrayOf("sh", "-c",
                                                "chown \"$targetUid:$targetGid\" \"${mcDbPath.trimEnd('/')}\"")).waitFor()
                                            runShizukuCmd(arrayOf("sh", "-c",
                                                "chmod 770 \"${mcDbPath.trimEnd('/')}\"")).waitFor()
                                        }

                                        // ③.5 自检：db/ 里必须直接有 CURRENT，

                                        //      否则说明结构错了（例如 db/db/），
                                        //      Minecraft 会读不出来 —— 立刻回滚。
                                        //      LevelDB 每个库都必须有 CURRENT。
                                        val hasCurrent = runShizukuCmd(
                                            arrayOf("sh", "-c", "test -f \"${mcDbPath}CURRENT\"")
                                        ).waitFor() == 0
                                        val hasNested = runShizukuCmd(
                                            arrayOf("sh", "-c", "test -d \"${mcDbPath}db\"")
                                        ).waitFor() == 0
                                        if (!hasCurrent || hasNested) {
                                            AppLogger.warn(
                                                "Save",
                                                "写回结构异常 current=$hasCurrent nested=$hasNested，" +
                                                "回滚到原目录"
                                            )
                                            // 把坏的新目录丢掉，原目录放回去
                                            runShizukuCmd(
                                                arrayOf("sh", "-c", "rm -rf \"$mcDbPath\"")
                                            ).waitFor()
                                            runShizukuCmd(
                                                arrayOf("sh", "-c", "mv \"$tmpOld\" \"$mcDbPath\"")
                                            ).waitFor()
                                            exit = 1
                                            cpErr = "bad db structure (rolled back)"
                                        } else {
                                            // ④ 确实成功了才清理旧副本
                                            runShizukuCmd(
                                                arrayOf("sh", "-c", "rm -rf \"$tmpOld\"")
                                            ).waitFor()
                                        }
                                    }

                                }
                            }
                            if (exit != 0) {
                                val tmpNewLeft = runShizukuCmd(
                                    arrayOf("sh", "-c", "test -d \"$tmpNew\"")
                                ).waitFor() == 0
                                val tmpOldLeft = runShizukuCmd(
                                    arrayOf("sh", "-c", "test -d \"$tmpOld\"")
                                ).waitFor() == 0
                                val dbExists = runShizukuCmd(
                                    arrayOf("sh", "-c", "test -d \"$mcDbPath\"")
                                ).waitFor() == 0
                                AppLogger.warn(
                                    "Save",
                                    "atomic write failed: $cpErr | uid=$targetUid gid=$targetGid" +
                                    " tmpNewLeft=$tmpNewLeft tmpOldLeft=$tmpOldLeft" +
                                    " dbExists=$dbExists path=$mcDbPath"
                                )
                                // 失败时把中间态清干净，避免下次被残留影响
                                runShizukuCmd(arrayOf("sh", "-c", "rm -rf \"$tmpNew\"")).waitFor()
                                // 若 db 不存在但 tmpOld 还在，说明在 mv 中途失败，先还原
                                if (!dbExists && tmpOldLeft) {
                                    runShizukuCmd(
                                        arrayOf("sh", "-c", "mv \"$tmpOld\" \"$mcDbPath\"")
                                    ).waitFor()
                                    AppLogger.info("Save", "已把原目录从 tmpOld 还原")
                                } else if (dbExists && tmpOldLeft) {
                                    runShizukuCmd(arrayOf("sh", "-c", "rm -rf \"$tmpOld\"")).waitFor()
                                }
                            }

                            runShizukuCmd(arrayOf("sh", "-c", "rm -rf \"$bridgeSave\"")).waitFor()

                            // 必须检查退出码：非 0 时数据其实没写进去，绝不能提示成功
                            if (exit != 0) {
                                AppLogger.warn("Save", "shizuku cp failed exit=$exit err=$cpErr")
                                // 区分「Shizuku 压根写不了」和「这条命令本身失败」，
                                // 因为前者在小米/HyperOS 上很常见（USB 调试安全设置没开），
                                // 提示要能直接指到解决办法。
                                // 文案要跟着当前模式走：root 模式下不该提 Shizuku
                                val canWrite = canShizukuWrite(mcDbDir)
                                throw Exception(
                                    when {
                                        PrivilegedExec.mode() == PrivilegedExec.Mode.ROOT && !canWrite ->
                                            getString(R.string.msg_root_cannot_write, mcDbPath)
                                        PrivilegedExec.mode() == PrivilegedExec.Mode.ROOT ->
                                            getString(R.string.msg_write_denied_root, mcDbPath)
                                        !canWrite ->
                                            getString(R.string.msg_shizuku_cannot_write, mcDbPath)
                                        else ->
                                            getString(R.string.msg_write_denied, mcDbPath)
                                    }
                                )

                            }
                            AppLogger.info("Save", R.string.log_save_player_shizuku_ok, mcDbPath)
                        } else {
                            // 4) 都不行 -> 明确报错，提示用户处理权限/换路径
                            throw Exception(getString(R.string.msg_write_denied, mcDbPath))
                        }
                    }

                    deleteRecursive(oldWorkDir)
                    currentWorkingDbPath = newWorkDir.absolutePath
                    // 已成功写回磁盘，会话不再需要
                    onSavedSuccessfully(context, evm.currentTargetKey.value)
                    _toastMessage.value = getString(R.string.toast_player_saved)
                } else {
                    if (currentWorkingFileOrDir == null) {
                        val f = File(worksDir(context), MainActivity.LEVEL_DAT_NAME)
                        currentWorkingFileOrDir = f.absolutePath
                    }
                    BedrockParser.write(dataToSave, currentWorkingFileOrDir)

                    val workingFile = File(currentWorkingFileOrDir!!)
                    val targetPath = "${resolveWorldPath(folder)}level.dat"
                    val targetFile = File(targetPath)

                    // issue #6：先试直接写，失败再用 Shizuku，都不行给明确提示
                    var success = copyFileNative(workingFile, targetFile)
                    if (success) {
                        AppLogger.info("Save", R.string.log_save_level_direct_ok, targetPath)
                    }

                    // SAF 模式：content:// 路径 File 写不了，改用 DocumentFile 覆盖
                    if (!success && isSafMode()) {
                        val worldDoc = SafIo.worldDirOf(context, safTreeUri!!, folder)
                            ?: throw Exception(getString(R.string.msg_write_denied, targetPath))
                        val (safOk, safErr) = SafIo.replaceFileInSaf(
                            context, worldDoc, MainActivity.LEVEL_DAT_NAME, workingFile
                        )
                        if (!safOk) throw Exception(safErr ?: getString(R.string.msg_write_denied, targetPath))
                        success = true
                        AppLogger.info("Save", R.string.log_save_level_saf_ok, targetPath)
                    }

                    if (!success && checkShizukuAvailable()) {
                        // Shizuku 降级
                        val bridgeFile = MainActivity.BRIDGE_ROOT + "level.dat"
                        File(MainActivity.BRIDGE_ROOT).mkdirs()
                        copyFile(workingFile, File(bridgeFile))
                        val cp = runShizukuCmd(arrayOf("sh", "-c", "cp \"$bridgeFile\" \"$targetPath\""))
                        // 必须检查退出码：原来无条件 success = true，cp 失败也会提示「已保存」
                        if (cp.waitFor() == 0) {
                            success = true
                            AppLogger.info("Save", R.string.log_save_level_shizuku_ok, targetPath)
                        } else {
                            AppLogger.warn("Save", R.string.log_save_level_shizuku_failed, targetPath)
                        }
                    }

                    if (!success) {
                        throw Exception(getString(R.string.msg_write_denied, targetPath))
                    }
                    // 已成功写回磁盘，会话不再需要
                    onSavedSuccessfully(context, "level.dat")
                    _toastMessage.value = getString(R.string.toast_level_dat_saved)
                }
                _isLoading.value = false
            } catch (e: Exception) {
                AppLogger.error("Save", "Save failed", e)
                _isLoading.value = false
                _errorMessage.value = getString(R.string.msg_save_failed_with_details, "${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}")
            }
        }
    }

    /** 备份进度回调：(已拷字节, 总字节)，用于在进度框里显示百分比 */
    private fun backupProgress(tag: String): (Long, Long) -> Unit = { done, total ->
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        // 备份阶段占总进度的一半（0~50%），写回阶段占另一半
        val overall = (pct / 2).coerceIn(0, 50)
        _progressMessage.value = getString(R.string.status_backup_save, overall)
    }

    /**
     * 保存前留一份快照。
     *
     * 是否真的备份由 [BackupSettings] 决定（对齐草稿箱的可配置策略）：
     * - 总开关关闭 → 跳过
     * - 同一存档 + 同一类型，距上次备份不足节流间隔 → 跳过
     * - 备份后按上限删最旧（按类型分别计数）
     *
     * @param force true = 忽略开关与节流，强制备份（用于「恢复备份前先保底」）
     * @return 是否真的产生了备份
     */
    private fun createBackup(context: Context, folderName: String, force: Boolean = false): Boolean {
        try {
            val evm = editorVM ?: return false
            val isPlayer = evm.isEditingPlayer.value

            if (!force) {
                if (!BackupSettings.isAutoEnabled(context)) return false
                if (!BackupSettings.isDueForBackup(context, folderName, isPlayer)) {
                    AppLogger.info("Backup", R.string.log_backup_throttled, folderName, isPlayer.toString())
                    return false
                }
            }

            val backupRoot = File(backupsDir(context), folderName)
            if (!backupRoot.exists()) backupRoot.mkdirs()
            val ts = BackupSettings.newTimestamp()

            if (isPlayer) {
                val dbPath = currentWorkingDbPath ?: return false
                val srcDb = File(dbPath)
                if (!srcDb.exists() || !srcDb.isDirectory) return false
                val dst = File(backupRoot, "db_$ts")
                if (useMultiThread) {
                    // 走多线程拷贝（带字节进度）
                    copyDirectoryParallel(srcDb, dst, resolveThreadCountFor(srcDb)) { done, total ->
                        backupProgress("db")(done, total)
                    }
                } else {
                    copyDirectoryMetered(srcDb, dst) { done, total -> backupProgress("db")(done, total) }
                }
            } else {
                val src = File(worksDir(context), MainActivity.LEVEL_DAT_NAME)
                if (!src.exists() || !src.isFile) return false
                val total = src.length()
                copyFileNative(src, File(backupRoot, "level_$ts.dat"))
                backupProgress("level")(total, total)
            }

            // 超出上限就删最旧（按类型分别计数）
            BackupSettings.prune(context, folderName, isPlayer)
            return true
        } catch (e: Exception) {
            Log.e("WorldVM", "Backup failed", e)
            return false
        }
    }

    /** 按目录规模算线程数（备份用，逻辑与 smartCopy 一致） */
    private fun resolveThreadCountFor(src: File): Int {
        val (bytes, count) = measureTree(src)
        return resolveThreadCount(bytes, count)
    }

    // ============================================
    // 缓存管理
    // ============================================
    fun scanCache(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = worksDir(context)
            if (!dir.exists()) dir.mkdirs()
            val files = dir.listFiles()
            val list = mutableListOf<CacheItem>()
            if (files != null) {
                files.sortByDescending { it.lastModified() }
                for (f in files) list.add(CacheItem(f, f.name, f.isDirectory))
            }
            _cacheList.value = list
        }
    }

    fun deleteCacheItem(item: CacheItem) {
        deleteRecursive(item.file)
    }

    fun clearAllCache(context: Context) {
        val dir = worksDir(context)
        dir.listFiles()?.forEach { deleteRecursive(it) }
        currentWorkingDbPath = null
        currentWorkingFileOrDir = null
        lastLoadedWorldFolder = null
        _cacheList.value = emptyList()
    }

    // ============================================
    // 备份管理
    // ============================================
    fun scanBackups(context: Context, folderName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // 世界名：读原存档的 levelname.txt（存档已删则保持 null，UI 显示文件夹名）
            _backupWorldName.value = try {
                getWorldRealName(context, folderName)
            } catch (_: Exception) {
                null
            }

            val dir = File(backupsDir(context), folderName)
            if (!dir.exists()) {
                _backupList.value = emptyList()
                _backupScanDone.trySend(emptyList())
                return@launch
            }
            val files = dir.listFiles() ?: emptyArray()
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            // 备份文件名由 createBackup 生成：level_<ts>.dat / db_<ts>
            // 时间从**文件名**解析 —— 目录的 lastModified 对备份目录毫无意义
            // （之前就因此显示成「文件夹被改动的时间」）
            val parsed = files.map { f ->
                val isPlayer = f.name.startsWith("db_")
                val ts = parseBackupTimestamp(f.name)
                BackupItem(
                    file = f,
                    name = f.name,
                    time = if (ts > 0) sdf.format(Date(ts)) else sdf.format(Date(f.lastModified())),
                    isDirectory = f.isDirectory,
                    isPlayerData = isPlayer,
                    timestamp = if (ts > 0) ts else f.lastModified()
                )
            }.sortedByDescending { it.timestamp }
            _backupList.value = parsed
            // 用事件通道通知 UI：内容与上次相同时 StateFlow 不会发射，走通道才能保证「点一次弹一次」
            _backupScanDone.trySend(parsed)
        }
    }

    /**
     * 从备份文件名解析时间戳（毫秒）。
     * 形如 level_2026-10-10_23-33-39.dat 或 db_2026-10-10_23-21-55
     * @return 0 表示解析失败
     */
    private fun parseBackupTimestamp(fileName: String): Long {
        return try {
            val body = fileName
                .removePrefix("level_").removePrefix("db_")
                .removeSuffix(".dat")
            val fmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
            fmt.isLenient = false
            fmt.parse(body)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun restoreBackup(context: Context, backupItem: BackupItem) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val evm = editorVM ?: return@launch
                // 恢复会覆盖当前存档 —— 先给当前状态强制留一份保底快照，
                // 免得「恢复错了就再也回不去」。（force=true：忽略开关与节流）
                runCatching {
                    _progressMessage.value = getString(R.string.status_backup_save, 0)
                    createBackup(context, lastLoadedWorldFolder ?: return@runCatching, force = true)
                }
                if (backupItem.isDirectory) {
                    val uniqueId = System.currentTimeMillis().toString()
                    val restoreDir = File(worksDir(context), "working_db_restore_$uniqueId")
                    deleteRecursive(restoreDir)
                    smartCopy(backupItem.file, restoreDir)
                    File(restoreDir, "LOCK").delete()
                    File(restoreDir, "LOG").delete()
                    File(restoreDir, "LOG.old").delete()
                    currentWorkingDbPath = restoreDir.absolutePath

                    val db = PlayerDbManager(restoreDir.absolutePath)
                    val data = db.readLocalPlayer()
                    db.close()

                    val json = BedrockParser.parseBytes(data)
                    evm.setEditingPlayer(true)
                    evm.setRawNbtData(json)
                    evm.navigationStack.clear()
                    evm.pathStack.clear()
                    evm.scrollPositionStack.clear()
                    evm.updatePathTitle()

                    // 进入编辑态：启动草稿自动保存并记录原始数据
                    onEditStarted(context, "~local_player", json)
                    _toastMessage.value = getString(R.string.toast_player_restored)
                } else {
                    val target = File(worksDir(context), MainActivity.LEVEL_DAT_NAME)
                    copyFileNative(backupItem.file, target)
                    currentWorkingFileOrDir = target.absolutePath

                    val json = BedrockParser.parse(target.absolutePath)
                    evm.setEditingPlayer(false)
                    evm.setTargetKey(null)
                    evm.setRawNbtData(json)
                    evm.nbtDataCache["level.dat"] = json
                    evm.navigationStack.clear()
                    evm.pathStack.clear()
                    evm.scrollPositionStack.clear()
                    evm.currentListData = json
                    evm.updatePathTitle()

                    // 进入编辑态：启动草稿自动保存并记录原始数据
                    onEditStarted(context, "level.dat", json)
                    updateWorldInfoFromNbt(json)
                    _toastMessage.value = getString(R.string.toast_level_restored)
                }
            } catch (e: Exception) {
                _errorMessage.value = getString(R.string.msg_restore_failed, e.message)
            }
        }
    }

    fun deleteBackup(item: BackupItem) {
        deleteRecursive(item.file)
    }

    fun renameBackup(item: BackupItem, newName: String): Boolean {
        val newFile = File(item.file.parent, newName)
        return item.file.renameTo(newFile)
    }
    // ============================================
    // 删除存档（移入回收站）
    // ============================================

    /**
     * 把整个世界目录移入回收站。
     *
     * 优先尝试原生命名（同分区时是原子操作、瞬间完成）；
     * 失败则退回复制 + 删除；再失败且 Shizuku 可用则用 mv。
     *
     * @return Pair(成功, 失败原因)；成功时 second 为回收站目录名
     */
    fun moveWorldToTrash(
        context: Context,
        folder: String,
        worldName: String?,
        alsoBackups: Boolean,
        alsoDrafts: Boolean
    ): Pair<Boolean, String?> {
        // SAF 模式：源是 content:// 路径，File.exists() 恒为 false，必须走 DocumentFile。
        // 顺序很重要：**先完整复制成功，才允许删源**；复制失败绝不动源目录。
        if (isSafMode()) {
            val treeUri = safTreeUri!!
            val worldDoc = SafIo.worldDirOf(context, treeUri, folder)
                ?: return Pair(false, getString(R.string.msg_world_dir_not_found))
            val slotName = TrashStore.newSlotName(worldName)
            val slot = TrashStore.slotDir(context, slotName)
            val dest = File(slot, "world")
            slot.mkdirs()
            val copyErr = SafIo.copyDocToFile(context, worldDoc, dest) { it in levelDbMetaFiles }
            if (copyErr != null) {
                deleteRecursive(dest)   // 清掉半成品，源目录原样保留
                return Pair(false, getString(R.string.label_sub_delete_failed) + "：$copyErr")
            }
            if (!SafIo.deleteDoc(worldDoc)) {
                // 已复制成功但删不掉源：回收站里有副本、源也还在，明确报告失败
                return Pair(false, getString(R.string.label_sub_delete_failed))
            }
            TrashStore.writeMeta(slot, folder, worldName, alsoBackups, alsoDrafts, safFolder = true)
            if (alsoBackups) moveBackupsIntoSlot(context, folder, slot)
            if (alsoDrafts) deleteDraftsOf(context, folder)
            if (lastLoadedWorldFolder == folder) {
                lastLoadedWorldFolder = null
                currentWorkingDbPath = null
                currentWorkingFileOrDir = null
                _currentWorldFolder.value = null
                editorVM?.reset()
            }
            AppLogger.info("World", R.string.log_world_moved_trash_saf, folder, slotName)
            return Pair(true, slotName)
        }

        val src = File(resolveWorldPath(folder))
        val parent = src.parentFile
        val canNative = parent != null && canWriteDirectly(parent)
        val canShizuku = checkShizukuAvailable()

        // ⚠️ 不能用 src.exists() 判断「档在不在」——
        //    /data/user/0/... 只有 root 能看见，File.exists() 恒为 false，
        //    结果是明明存档存在却提示「找不到存档目录」。
        //    改成「原生看得见」或「提权看得见」任一条成立即可。
        val existsNatively = try { src.exists() } catch (_: Exception) { false }
        val existsViaPrivilege = !existsNatively && canShizuku &&
            try { dirExists(src.absolutePath) } catch (_: Exception) { false }
        if (!existsNatively && !existsViaPrivilege) {
            return Pair(false, getString(R.string.msg_world_dir_not_found))
        }

        // 能不能动？不能写就别白忙（Android/data 需要 Shizuku 或所有文件权限）
        if (!canNative && !canShizuku) {
            return Pair(false, getString(R.string.msg_delete_need_permission))
        }


        val slotName = TrashStore.newSlotName(worldName)
        val slot = TrashStore.slotDir(context, slotName)
        val dest = File(slot, "world")

        // 1) 原生命名（同分区）
        var moved = false
        if (canNative) {
            try {
                slot.mkdirs()
                moved = src.renameTo(dest)
            } catch (_: Exception) {
            }
            // 2) 复制 + 删除
            if (!moved) {
                try {
                    dest.mkdirs()
                    smartCopy(src, dest)
                    deleteRecursive(src)
                    moved = true
                } catch (_: Exception) {
                }
            }
        }
        // 3) 提权 mv
        if (!moved && canShizuku) {
            try {
                runShizukuCmd(arrayOf("sh", "-c", "mkdir -p \"${slot.absolutePath}\"")).waitFor()
                // ⚠️ 目标 dest 可能已存在（上面 mkdirs / 复制用过），
                //    此时 `mv src dest` 会把 src 整个塞进 dest 里面，
                //    变成 dest/<存档名>，回收站结构就错了。先清干净再改名。
                runShizukuCmd(arrayOf("sh", "-c", "rm -rf \"${dest.absolutePath}\"")).waitFor()
                val p = runShizukuCmd(arrayOf("sh", "-c",
                    "mv \"${src.absolutePath}\" \"${dest.absolutePath}\""))
                moved = p.waitFor() == 0
            } catch (_: Exception) {
            }
        }


        if (!moved) return Pair(false, getString(R.string.label_sub_delete_failed))

        TrashStore.writeMeta(slot, folder, worldName, alsoBackups, alsoDrafts)

        // 连带处理：备份（移入回收站槽内，恢复时可一并找回）
        if (alsoBackups) moveBackupsIntoSlot(context, folder, slot)

        // 连带处理：草稿
        if (alsoDrafts) deleteDraftsOf(context, folder)

        // 若当前正在编辑该存档，清掉加载状态避免指向已消失的目录
        if (lastLoadedWorldFolder == folder) {
            lastLoadedWorldFolder = null
            currentWorkingDbPath = null
            currentWorkingFileOrDir = null
            _currentWorldFolder.value = null
            editorVM?.reset()
        }

        AppLogger.info("World", R.string.log_world_moved_trash, folder, slotName)
        return Pair(true, slotName)
    }

    /** 从回收站恢复一个存档 */
    fun restoreFromTrash(
        context: Context,
        item: TrashStore.TrashItem,
        overwrite: Boolean
    ): Pair<Boolean, String?> {
        val src = item.worldDir()
        if (!src.exists()) return Pair(false, getString(R.string.msg_trash_content_missing))

        val targetParent = File(_currentPath.value)
        val target = File(targetParent, item.originalFolder)

        // SAF 模式恢复：目标位置是 content://，只能走 DocumentFile
        if (isSafMode()) {
            val treeUri = safTreeUri!!
            val parentDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: return Pair(false, getString(R.string.msg_restore_failed))
            val existing = parentDoc.findFile(item.originalFolder)
            if (existing != null && !overwrite) {
                return Pair(false, getString(R.string.msg_restore_target_exists))
            }
            if (existing != null && overwrite) {
                // 旧存档也先挪进回收站，避免直接覆盖丢数据
                val oldSlot = TrashStore.slotDir(context, TrashStore.newSlotName("${item.originalFolder}_overwritten"))
                oldSlot.mkdirs()
                val oldDest = File(oldSlot, "world")
                val moveErr = SafIo.copyDocToFile(context, existing, oldDest) { it in levelDbMetaFiles }
                if (moveErr == null) SafIo.deleteDoc(existing)
            }
            val (okSaf, errSaf) = SafIo.replaceDirInSaf(context, parentDoc, item.originalFolder, src)
            if (!okSaf) return Pair(false, errSaf ?: getString(R.string.msg_restore_failed))
            deleteRecursive(src)
            restoreBackupsIfAny(context, item)
            try { deleteRecursive(item.dir) } catch (e: Exception) { AppLogger.warn("World", "删除存档目录失败: ${e.message}") }
            AppLogger.info("World", R.string.log_world_restored_saf, item.originalFolder)
            return Pair(true, null)
        }

        if (target.exists() && !overwrite) {
            return Pair(false, getString(R.string.msg_restore_target_exists))
        }
        if (target.exists() && overwrite) {
            // 目标已存在：把旧的也挪进回收站，避免直接覆盖丢失
            val oldSlot = TrashStore.slotDir(context, TrashStore.newSlotName("${item.originalFolder}_overwritten"))
            try {
                oldSlot.mkdirs()
                if (!target.renameTo(File(oldSlot, "world"))) {
                    val d = File(oldSlot, "world"); d.mkdirs()
                    smartCopy(target, d); deleteRecursive(target)
                }
            } catch (_: Exception) {
            }
        }

        var ok = false
        try {
            if (!targetParent.exists()) targetParent.mkdirs()
            ok = src.renameTo(target)
            if (!ok) {
                target.mkdirs(); smartCopy(src, target); deleteRecursive(src); ok = true
            }
        } catch (_: Exception) {
        }
        if (!ok && checkShizukuAvailable()) {
            try {
                val p = runShizukuCmd(arrayOf("sh", "-c",
                    "mv \"${src.absolutePath}\" \"${target.absolutePath}\""))
                ok = p.waitFor() == 0
            } catch (_: Exception) {
            }
        }
        if (!ok) return Pair(false, getString(R.string.msg_restore_failed))

        // 备份一并还原
        restoreBackupsIfAny(context, item)

        // 清掉回收站槽（内容已移走）
        try {
            deleteRecursive(item.dir)
        } catch (_: Exception) {
        }

        AppLogger.info("World", R.string.log_world_restored, item.originalFolder)
        return Pair(true, null)
    }

    /** 删除某存档的全部草稿 */
    private fun deleteDraftsOf(context: Context, folder: String) {
        try {
            DraftStore.list(context)
                .filter { it.folder == folder }
                .forEach { DraftStore.remove(context, it.sessionKey) }
        } catch (_: Exception) {
        }
    }

    /** 把某存档的备份移入回收站槽（恢复时可一并找回） */
    private fun moveBackupsIntoSlot(context: Context, folder: String, slot: File) {
        try {
            val bk = File(backupsDir(context), folder)
            if (bk.exists()) {
                val bkDest = File(slot, "backups")
                if (!bk.renameTo(bkDest)) {
                    bkDest.mkdirs(); smartCopy(bk, bkDest); deleteRecursive(bk)
                }
            }
        } catch (_: Exception) {
        }
    }

    /** 恢复存档时，把连带移入回收站槽的备份放回备份目录 */
    private fun restoreBackupsIfAny(context: Context, item: TrashStore.TrashItem) {
        if (!item.hasBackups) return
        try {
            val bkSrc = File(item.dir, "backups")
            if (bkSrc.exists()) {
                val bkDest = File(backupsDir(context), item.originalFolder)
                if (bkDest.exists()) deleteRecursive(bkDest)
                bkDest.parentFile?.mkdirs()
                if (!bkSrc.renameTo(bkDest)) { bkDest.mkdirs(); smartCopy(bkSrc, bkDest) }
            }
        } catch (_: Exception) {
        }
    }

    /** 自动清理回收站（设置里可配天数） */
    fun autoCleanTrash(context: Context, days: Int): Int = TrashStore.autoClean(context, days)



    // ============================================
    // 清理旧会话
    // ============================================
    fun cleanUpOldSessions(context: Context) {
        val privateWorks = File(context.getExternalFilesDir(null), "Works")
        cleanDirContent(privateWorks)
        val publicWorks = AppDirs.works()
        cleanDirContent(publicWorks)
        val bridgeDir = File(MainActivity.BRIDGE_ROOT)
        if (bridgeDir.exists()) {
            createNoMedia()
            bridgeDir.listFiles()?.forEach {
                if (it.name != ".nomedia") deleteRecursive(it)
            }
        } else {
            bridgeDir.mkdirs()
            createNoMedia()
        }
    }

    private fun cleanDirContent(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach {
            /*
             * ⚠️ `map_db_` 必须在这里清。
             *
             * 地图页每次 open() 都会把整个存档 db 复制进 Works/map_db_<时间戳>，
             * 一个存档动辄几十 MB。以前这里只清 working_db_ / db_restore_，
             * 地图副本就一直躺着，积累到十几个目录、占掉几百 MB —— 而且
             * 用户在文件管理器里看得见，观感很差。
             *
             * 地图是只读预览，没有「没保存的编辑」会丢，重启时直接删是安全的。
             */
            if ((it.isDirectory && (
                    it.name.startsWith("working_db_") ||
                        it.name.startsWith("db_restore_") ||
                        it.name.startsWith("map_db_")
                    ))
                || (it.isFile && it.name == MainActivity.LEVEL_DAT_NAME)
            ) deleteRecursive(it)
        }
    }

    // ============================================
    // 更新世界信息卡片
    // ============================================
    fun updateWorldInfoFromNbt(rootJson: JsonObject?) {
        if (rootJson == null) {
            _worldNameForTitle.value = null
            _worldSeedForDisplay.value = null
            return
        }
        try {
            var nameStr: String? = null
            if (rootJson.has("LevelName")) {
                val obj = rootJson.getAsJsonObject("LevelName")
                if (obj.has("v")) nameStr = obj.get("v").asString
            }
            var seedStr: String? = null
            if (rootJson.has("RandomSeed")) {
                val obj = rootJson.getAsJsonObject("RandomSeed")
                if (obj.has("v")) seedStr = obj.get("v").asString
            }
            _worldNameForTitle.value = nameStr
            _worldSeedForDisplay.value = seedStr
        } catch (_: Exception) {
            _worldNameForTitle.value = null
            _worldSeedForDisplay.value = null
        }
    }

    // ============================================
    // 加载自定义 Key（Hex/文本）
    // ============================================
    fun loadCustomKey(inputStr: String, isHex: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                val dbPath = currentWorkingDbPath ?: throw Exception(getString(R.string.text_db_path_is_null))
                val db = PlayerDbManager(dbPath)
                val keyBytes = if (isHex) hexStringToByteArray(inputStr)
                else inputStr.toByteArray(Charsets.UTF_8)
                val data = db.readRawKey(keyBytes)
                db.close()
                val json = BedrockParser.parseBytes(data)

                val evm = editorVM ?: return@launch
                evm.setEditingPlayer(true)
                evm.setTargetKey(inputStr)
                evm.setRawNbtData(json)
                evm.navigationStack.clear()
                evm.pathStack.clear()
                evm.scrollPositionStack.clear()
                evm.updatePathTitle()

                _isLoading.value = false
                _toastMessage.value = getString(R.string.toast_loading_successfully)
            } catch (e: Exception) {
                AppLogger.error("LoadCustomKey", "Custom key load failed", e)
                _isLoading.value = false
                _errorMessage.value = getString(R.string.err_load_failed_with_msg, e.message)
            }
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val hex = s.replace(" ", "")
        require(hex.length % 2 == 0) { getString(R.string.msg_hex_length_even) }
        val data = ByteArray(hex.length / 2)
        for (i in hex.indices step 2) {
            val high = hex[i].digitToIntOrNull(16) ?: throw IllegalArgumentException(getString(R.string.msg_invalid_hex, hex[i]))
            val low = hex[i + 1].digitToIntOrNull(16) ?: throw IllegalArgumentException(getString(R.string.msg_invalid_hex, hex[i + 1]))
            data[i / 2] = ((high shl 4) + low).toByte()
        }
        return data
    }

    // ============================================
    // Shizuku（照搬原代码）
    // ============================================
    private var useShizuku = true

    fun setUseShizuku(use: Boolean) { useShizuku = use }

    fun requestShizukuPermission() {
        try {
            Class.forName("rikka.shizuku.Shizuku")
                .getMethod("requestPermission", Int::class.javaPrimitiveType)
                .invoke(null, 0)
        } catch (_: Exception) {
            // 忽略，调用方自行处理
        }
    }

    sealed class ShizukuStatus {
        object Available : ShizukuStatus()
        object NotInstalled : ShizukuStatus()
        object NotRunning : ShizukuStatus()
        object PermissionDenied : ShizukuStatus()
    }

    /**
     * 当前提权能力是否可用。
     *
     * ⚠️ 设计原则：**各模式独立判断，互不影响**。
     *    · ROOT   只看 su 是否真的能执行（不看 Shizuku 有没有装/在跑）
     *    · SHIZUKU 只看 binder 通不通、权限有没有给
     *    · NORMAL 恒为不可提权
     *
     *    以前这里是「先看 canAttemptPrivileged 再一律去查 Shizuku」，
     *    结果用户明明有 root（超管里也能看到授权），
     *    却因为没启动 Shizuku 被判成「Shizuku 未运行」。
     *
     * 降级只发生在**启动解析模式**时（PrivilegedExec.resolveMode），
     * 不在这里做 —— 这里只回答「当前这个模式现在还能用吗」。
     */
    fun checkShizukuStatus(context: Context? = null): ShizukuStatus {
        if (!useShizuku) return ShizukuStatus.NotInstalled

        return when (PrivilegedExec.mode()) {
            // ① ROOT：只认 su，和 Shizuku 状态无关
            PrivilegedExec.Mode.ROOT ->
                if (PrivilegedExec.detectRoot(appContext ?: context ?: return ShizukuStatus.NotRunning))
                    ShizukuStatus.Available
                else ShizukuStatus.NotRunning

            // ② NORMAL：用户明确不要提权
            PrivilegedExec.Mode.NORMAL -> ShizukuStatus.NotInstalled

            // ③ SHIZUKU：只看 binder + 权限
            PrivilegedExec.Mode.SHIZUKU -> checkShizukuOnly(context)
        }
    }

    /** 只检测 Shizuku 自身状态，与 root 完全无关 */
    private fun checkShizukuOnly(context: Context?): ShizukuStatus {
        try {
            val c = Class.forName("rikka.shizuku.Shizuku")
            val ping = c.getMethod("pingBinder").invoke(null) as? Boolean ?: false
            if (!ping) {
                val isInstalled = context?.let { ctx ->
                    try {
                        ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
                        true
                    } catch (_: Exception) {
                        false
                    }
                } ?: true
                return if (isInstalled) ShizukuStatus.NotRunning else ShizukuStatus.NotInstalled
            }
            val perm = c.getMethod("checkSelfPermission").invoke(null) as? Int
            if (perm != PackageManager.PERMISSION_GRANTED) return ShizukuStatus.PermissionDenied
            return ShizukuStatus.Available
        } catch (_: ClassNotFoundException) {
            return ShizukuStatus.NotInstalled
        } catch (_: Exception) {
            return ShizukuStatus.NotRunning
        }
    }

    // 保留旧的 checkShizukuAvailable() 但改为调用新方法保持兼容
    /**
     * 当前是否有提权能力（root 或 Shizuku 任一可用）。
     *
     * 名字保留 checkShizukuAvailable 是因为有 50 处调用；语义已经泛化。
     */
    fun checkShizukuAvailable(): Boolean {
        return checkShizukuStatus(appContext) is ShizukuStatus.Available
    }


    fun checkShizukuReady(): Boolean {
        if (!useShizuku) return true
        if (checkShizukuAvailable()) return true
        try {
            val c = Class.forName("rikka.shizuku.Shizuku")
            c.getMethod("requestPermission", Int::class.javaPrimitiveType).invoke(null, 0)
        } catch (_: Exception) {}
        return false
    }

    fun checkStoragePermission(context: Context) {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                val intent = android.content.Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = "package:${context.packageName}".toUri()
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    /**
     * 执行一条提权命令（Shizuku 或 root，取决于当前模式）。
     *
     * ⚠️ 这里不自己实现，全部转给 PrivilegedExec —— 因为：
     *  · 加 root 之后命令有两个来源，安全校验必须只有一处
     *  · PrivilegedExec 会对 rm/mv 的目标路径做白名单校验，
     *    防止 root 下一条拼错的命令把用户存档删掉
     *
     * 调用方仍然传 arrayOf("sh", "-c", "…")，签名保持不变。
     */
    fun runShizukuCmd(cmd: Array<String?>): Process {
        val shell = cmd.getOrNull(2) ?: ""
        val ctx = appContext ?: throw Exception(getString(R.string.err_shizuku_not_running))
        try {
            return PrivilegedExec.exec(ctx, shell)
                ?: throw Exception(getString(R.string.err_privileged_unavailable))
        } catch (e: SecurityException) {
            // 命令被安全校验拦下：这是代码 bug，必须显式失败，不能静默跳过
            AppLogger.warn("PrivilegedExec", "命令被拒绝: ${e.message}")
            throw Exception(getString(R.string.err_command_refused, e.message ?: ""))
        } catch (e: Exception) {
            if (e.message?.contains(getString(R.string.msg_binder_missing)) == true)
                throw Exception(getString(R.string.err_shizuku_binder_not_ready))
            throw e
        }
    }

    /** 由 MainActivity 在 onCreate 里注入，供 PrivilegedExec 读取偏好/模板 */
    var appContext: Context? = null

    // ============================================
    // 文件操作
    // ============================================
    /** 拷贝缓冲区：64KB（原为 1KB，大文件时系统调用次数减少 64 倍） */
    private val copyBufferSize = 64 * 1024

    /** 零拷贝的阈值：大文件用 FileChannel.transferTo，小文件走普通流更划算 */
    private val zeroCopyThreshold = 512L * 1024

    private fun copyFile(src: File, dst: File) {
        // 大文件走零拷贝（免用户态↔内核态数据搬运），小文件用流即可
        if (src.length() >= zeroCopyThreshold) {
            try {
                copyFileZeroCopy(src, dst)
                return
            } catch (_: Exception) {
                // 零拷贝失败（某些 SAF/虚拟文件系统不支持），回退到流式
            }
        }
        copyFileStream(src, dst)
    }

    /**
     * FileChannel.transferTo 零拷贝。
     * 在 Linux/Android 上可直接由内核在 fd 间搬运数据，避免 64KB 缓冲区的往返。
     */
    private fun copyFileZeroCopy(src: File, dst: File) {
        FileInputStream(src).channel.use { inCh ->
            FileOutputStream(dst).channel.use { outCh ->
                var pos = 0L
                val size = inCh.size()
                while (pos < size) {
                    val moved = inCh.transferTo(pos, size - pos, outCh)
                    if (moved <= 0) break
                    pos += moved
                }
            }
        }
    }

    private fun copyFileStream(src: File, dst: File) {
        val `in` = FileInputStream(src)
        val out = FileOutputStream(dst)
        try {
            val buf = ByteArray(copyBufferSize)
            var len: Int
            while ((`in`.read(buf).also { len = it }) > 0) out.write(buf, 0, len)
            out.flush()
        } finally {
            try { `in`.close() } catch (_: Exception) {}
            try { out.close() } catch (_: Exception) {}
        }
    }

    private fun copyFileNative(src: File, dst: File): Boolean {
        return try { copyFile(src, dst); true } catch (_: Exception) { false }
    }

    /** LevelDB 临时/锁文件，拷贝时必须跳过；CURRENT/MANIFEST 是索引必须保留 */
    private val levelDbMetaFiles = setOf("LOCK", "LOG", "LOG.old")

    private fun shouldSkipFile(name: String): Boolean {
        return name in levelDbMetaFiles
    }

    private fun cleanLevelDbMeta(dir: File) {
        File(dir, "LOCK").takeIf { it.exists() }?.delete()
        File(dir, "LOG").takeIf { it.exists() }?.delete()
        File(dir, "LOG.old").takeIf { it.exists() }?.delete()
    }

    private fun copyDirectory(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.exists()) target.mkdirs()
            source.list()?.forEach {
                if (shouldSkipFile(it)) return@forEach
                copyDirectory(File(source, it), File(target, it))
            }
        } else copyFile(source, target)
    }

    /** 单线程拷贝 + 字节进度（备份用；[smartCopy] 的单线程分支没有进度回调） */
    private fun copyDirectoryMetered(
        source: File,
        target: File,
        onProgress: (Long, Long) -> Unit
    ) {
        val files = mutableListOf<Pair<File, File>>()
        val dirs = mutableListOf<File>()
        collectCopyPlan(source, target, files, dirs)
        dirs.forEach { if (!it.exists()) it.mkdirs() }
        val total = files.sumOf { it.first.length() }
        var done = 0L
        var lastTick = 0L
        files.forEach { (src, dst) ->
            dst.parentFile?.let { if (!it.exists()) it.mkdirs() }
            copyFile(src, dst)
            done += src.length()
            // 限流：每 100ms 或进度明显变化时才回调，避免刷 UI
            val now = System.currentTimeMillis()
            if (now - lastTick >= 100) {
                lastTick = now
                onProgress(done, total)
            }
        }
        onProgress(done, total)
    }

    private fun safCopyDirectory(context: Context, srcDoc: DocumentFile, dstDir: File) {
        if (!dstDir.exists()) dstDir.mkdirs()
        for (child in srcDoc.listFiles()) {
            val name = child.name ?: continue
            if (shouldSkipFile(name)) continue
            val dstFile = File(dstDir, name)
            if (child.isDirectory) {
                safCopyDirectory(context, child, dstFile)
            } else {
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    java.io.FileOutputStream(dstFile).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    /**
     * 取世界 level.dat 的最后修改时间（毫秒）。
     * 原生读不到时（Android/data 无权限）退回 Shizuku 的 stat 命令。
     * @return 0 表示未知
     */
    /**
     * 世界缩略图路径：`<world>/world_icon.jpeg`。
     * 这是基岩版退出存档时自动写的「最后一帧」截图，用作存档列表的预览图。
     *
     * ⚠️ 标准路径在 `Android/data` 下，Android 11+ 直读会因分区存储而失败
     * （exists() 恒为 false），但 level.dat 却能靠 Shizuku 判定有效 ——
     * 早期版本就是因此「图片一直加载不出来」。这里同样用 Shizuku 兜底探测。
     *
     * @return 文件绝对路径；不存在（或读不到）返回 null
     */
    private fun worldIconPath(dir: File): String? {
        val f = File(dir, "world_icon.jpeg")
        // ① 能直读（旧版路径 / 已授权所有文件访问）
        try {
            if (f.exists() && f.length() > 0) return f.absolutePath
        } catch (_: Exception) {}

        // ② 直读失败：用 Shizuku 探测（有权限就能读到，UI 层同样走 Shizuku 解码）
        if (checkShizukuAvailable()) {
            try {
                val p = runShizukuCmd(arrayOf("sh", "-c", "test -s '${f.absolutePath}'"))
                if (p.waitFor() == 0) return f.absolutePath
            } catch (_: Exception) {}
        }
        return null
    }

    private fun levelDatTime(worldDir: File): Long {
        val lf = File(worldDir, "level.dat")
        try {
            if (lf.exists() && lf.canRead()) return lf.lastModified()
        } catch (_: Exception) {
        }
        if (checkShizukuAvailable()) {
            try {
                val p = runShizukuCmd(arrayOf("sh", "-c", "stat -c %Y \"${lf.absolutePath}\" 2>/dev/null"))
                val out = BufferedReader(InputStreamReader(p.inputStream)).readLine()?.trim()
                p.waitFor()
                // stat 返回秒，转毫秒
                out?.toLongOrNull()?.takeIf { it > 0 }?.let { return it * 1000L }
            } catch (_: Exception) {
            }
        }
        return 0L
    }

    private fun deleteRecursive(f: File?) {
        if (f == null || !f.exists()) return
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
        f.delete()
    }

    /**
     * 统一拷贝入口：
     * - 目录：按「总大小 + 文件数」智能决定是否并行、用几个线程
     * - 文件：直接拷
     */
    private fun smartCopy(src: File, dst: File) {
        if (!src.isDirectory) {
            copyFile(src, dst)
            return
        }
        if (!useMultiThread) {
            copyDirectory(src, dst)
            return
        }

        // 统计规模，决定线程数（自动降级：小规模走单线程）
        val (totalBytes, fileCount) = measureTree(src)
        val threads = resolveThreadCount(totalBytes, fileCount)
        if (threads <= 1) {
            copyDirectory(src, dst)
        } else {
            copyDirectoryParallel(src, dst, threads)
        }
    }

    /** 递归收集待拷贝的 (源文件, 目标文件) 与需要创建的目录 */
    private fun collectCopyPlan(
        source: File, target: File,
        files: MutableList<Pair<File, File>>,
        dirs: MutableList<File>
    ) {
        if (source.isDirectory) {
            dirs.add(target)
            source.listFiles()?.forEach { child ->
                if (shouldSkipFile(child.name)) return@forEach
                collectCopyPlan(child, File(target, child.name), files, dirs)
            }
        } else if (source.isFile) {
            files.add(Pair(source, target))
        }
    }

    /**
     * 多线程拷贝：先收集完整清单（单线程、极快），再并行拷贝文件。
     * 这样只创建一个线程池，不会像递归实现那样为每个子目录新建线程池。
     */
    private fun copyDirectoryParallel(
        source: File,
        target: File,
        threads: Int,
        onProgress: ((Long, Long) -> Unit)? = null
    ) {
        AppLogger.info("Copy", R.string.log_copy_multithread, threads, source, target)
        val files = mutableListOf<Pair<File, File>>()
        val dirs = mutableListOf<File>()
        collectCopyPlan(source, target, files, dirs)

        // 建目录（必须先于文件）
        dirs.forEach { if (!it.exists()) it.mkdirs() }
        if (files.isEmpty()) return

        val totalFiles = files.size
        val totalSize = files.sumOf { it.first.length() }
        val executor = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(totalFiles)
        val errorRef = AtomicReference<Throwable?>()
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val copiedSize = java.util.concurrent.atomic.AtomicLong(0L)
        val lastTick = java.util.concurrent.atomic.AtomicLong(0L)

        files.forEach { (srcFile, dstFile) ->
            executor.submit {
                try {
                    if (errorRef.get() != null) return@submit
                    dstFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                    copyFile(srcFile, dstFile)

                    val done = completed.incrementAndGet()
                    val bytes = copiedSize.addAndGet(srcFile.length())
                    val progress = (bytes * 100 / maxOf(totalSize, 1)).toInt()
                    // 有进度回调时走回调（备份用），否则用默认的复制文案
                    if (onProgress != null) {
                        val now = System.currentTimeMillis()
                        if (now - lastTick.get() >= 100) {
                            lastTick.set(now)
                            onProgress(bytes, totalSize)
                        }
                    } else {
                        _progressMessage.value =
                            getString(R.string.copying_progress, done, totalFiles, progress)
                    }
                } catch (e: Throwable) {
                    errorRef.set(e)
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()
        errorRef.get()?.let { throw it }
    }

    fun createNoMedia() {
        try {
            val bridgeDir = File(MainActivity.BRIDGE_ROOT)
            if (!bridgeDir.exists()) bridgeDir.mkdirs()
            val noMedia = File(bridgeDir, ".nomedia")
            if (!noMedia.exists()) noMedia.createNewFile()
        } catch (_: Exception) {}
    }

    fun ensureNoMedia(dir: File) {
        try {
            if (!dir.exists()) dir.mkdirs()
            val noMedia = File(dir, ".nomedia")
            if (!noMedia.exists()) noMedia.createNewFile()
        } catch (_: Exception) {}
    }

    // ============================================
    // DB 修复
    // ============================================
    fun tryRepairDb(dbPath: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                PlayerDbManager.tryRepair(dbPath)
                withContext(Dispatchers.Main) { onResult(true) }
            } catch (e: Exception) {
                AppLogger.error("RepairDB", "DB repair failed", e)
                _errorMessage.value = getString(R.string.toast_repair_failed, e.message)
                withContext(Dispatchers.Main) { onResult(false) }
            }
        }
    }

    /** 供 UI 展示用：暴露世界目录路径 */
    fun resolveWorldPathPublic(folder: String): String = resolveWorldPath(folder)

    private fun resolveWorldPath(folder: String): String {
        val base = _currentPath.value
        val baseFile = File(base)
        // 如果当前路径的最后一段文件夹名 == folder，说明路径已经指向世界本身
        if (baseFile.name == folder) {
            return if (base.endsWith("/")) base else "$base/"
        }
        return if (base.endsWith("/")) "$base$folder/" else "$base/$folder/"
    }

    fun reloadPlayerFromCurrentDb() {
        val dbPath = currentWorkingDbPath ?: return
        val evm = editorVM ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = getString(R.string.status_reloading_player)
            try {
                val db = PlayerDbManager(dbPath)
                val data = db.readLocalPlayer()
                db.close()

                val playerDataObj = BedrockParser.parseBytes(data)
                evm.setEditingPlayer(true)
                evm.setTargetKey("~local_player")
                evm.setRawNbtData(playerDataObj)
                evm.nbtDataCache["~local_player"] = playerDataObj
                evm.navigationStack.clear()
                evm.pathStack.clear()
                evm.scrollPositionStack.clear()
                evm.updatePathTitle()
                _isLoading.value = false
                // 数据只写进了 LevelDB，玩家存档文件还没落地 —— 必须提醒手动保存
                _toastMessage.value = getString(R.string.status_player_reloaded)
            } catch (e: Exception) {
                _isLoading.value = false
                _errorMessage.value = e.message
            }
        }
    }
}