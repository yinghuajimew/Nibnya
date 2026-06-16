package yhjmew.minecraft.nbteditor.viewmodel

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
import yhjmew.minecraft.nbteditor.R
import yhjmew.minecraft.nbteditor.BedrockParser
import yhjmew.minecraft.nbteditor.MainActivity
import yhjmew.minecraft.nbteditor.NbtTranslator.getString
import yhjmew.minecraft.nbteditor.PlayerDbManager
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
import yhjmew.minecraft.nbteditor.AppLogger
import yhjmew.minecraft.nbteditor.NbtTranslator
import yhjmew.minecraft.nbteditor.SafPathResolver

/**
 * 世界/存档/路径/文件 I/O 的 ViewModel
 * 加载完成后通过 editorVM 写入数据
 */
class WorldViewModel : ViewModel() {

    /** Activity 创建后注入 */
    var editorVM: EditorViewModel? = null

    // ============================================
    // 数据类
    // ============================================
    data class WorldItem(
        val folderName: String,
        val displayName: String
    )

    data class CacheItem(
        val file: File,
        val name: String,
        val isDirectory: Boolean
    )

    data class BackupItem(
        val file: File,
        val name: String,
        val time: String,
        val isDirectory: Boolean
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

    private val _backupList = MutableStateFlow<List<BackupItem>>(emptyList())
    val backupList: StateFlow<List<BackupItem>> = _backupList.asStateFlow()

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
        return if (_currentPath.value.contains("Android/data")) {
            context.getExternalFilesDir(null)!!
        } else {
            val publicDir = File("/storage/emulated/0/Download/NbtEditor_Data/")
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
            _loadingMessage.value = getString(R.string.msg_scanning)
            try {
                val dir = File(_currentPath.value)
                val rawFolders = mutableListOf<String>()

                // === 新增：检查当前路径本身是否就是一个世界文件夹 ===
                val checkLevel = File(dir, "level.dat")
                val checkDb = File(dir, "db")
                if (checkLevel.exists() && checkDb.exists()) {
                    // 当前路径本身就是世界文件夹，直接加载
                    val realName = getWorldRealNameByDir(dir) ?: "Unknown"
                    _worldList.value = listOf(WorldItem(folderName = dir.name, displayName = realName))
                    _scanResultChannel.trySend(ScanResult(listOf(WorldItem(folderName = dir.name, displayName = realName))))
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
                        result.add(WorldItem(folderName = name, displayName = realName))
                    }
                }

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
            _loadingMessage.value = NbtTranslator.getString(R.string.msg_scanning)

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

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = NbtTranslator.getString(R.string.msg_loading_level_dat)

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

                updateWorldInfoFromNbt(json)
                _currentWorldFolder.value = folderName
                _isLoading.value = false
                _toastMessage.value = NbtTranslator.getString(R.string.msg_loaded_level_dat)

            } catch (e: Exception) {
                _isLoading.value = false
                _errorMessage.value = e.message ?: NbtTranslator.getString(R.string.msg_load_failed)
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
    // 加载 level.dat
    // ============================================
    fun loadLevelDat(context: Context, folder: String) {
        val evm = editorVM ?: return
        evm.saveSession(currentWorkingDbPath)

        val isNewWorld = (lastLoadedWorldFolder == null || lastLoadedWorldFolder != folder)
        if (isNewWorld) {
            evm.reset()
            lastLoadedWorldFolder = folder
        } else if (evm.tryRestoreSession("level.dat")) return

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = getString(R.string.msg_loading_level_dat)
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

                // 更新世界信息
                updateWorldInfoFromNbt(json)

                _currentWorldFolder.value = folder
                _isLoading.value = false
                _toastMessage.value = getString(R.string.msg_loaded_level_dat)
            } catch (e: Exception) {
                AppLogger.error("LoadLevelDat", "Load level.dat failed", e)
                _isLoading.value = false
                _errorMessage.value = e.message ?: getString(R.string.msg_load_failed)
            }
        }
    }

    // ============================================
    // 加载玩家数据
    // ============================================

    fun loadPlayerDataViaSaf(context: Context, folderName: String, onSuccess: Runnable? = null) {
        val evm = editorVM ?: return
        evm.saveSession(currentWorkingDbPath)
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

                File(workDir, "LOCK").delete()
                File(workDir, "LOG").delete()
                File(workDir, "LOG.old").delete()
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
        if (evm.tryRestoreSession("~local_player")) return

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = getString(R.string.msg_loading_player)
            try {
                val worldDir = File(resolveWorldPath(folder))
                val srcPath = File(worldDir, "db").absolutePath
                val srcDirFile = File(srcPath)

                val canUseShizuku = checkShizukuAvailable()
                var useNative = false
                if (srcDirFile.exists() && srcDirFile.canRead() && srcDirFile.listFiles() != null) {
                    useNative = true
                } else if (!canUseShizuku) {
                    throw Exception(getString(R.string.toast_err_permission_denied_shizuku))
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

                File(workDir, "LOCK").delete()
                File(workDir, "LOG").delete()
                File(workDir, "LOG.old").delete()

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
            _loadingMessage.value = getString(R.string.msg_saving)
            try {
                createBackup(context, folder)
                val evm = editorVM ?: throw Exception(getString(R.string.msg_editorvm_missing))

                if (evm.isEditingPlayer.value) {
                    val dbPath = currentWorkingDbPath ?: throw Exception(getString(R.string.text_db_path_is_null))
                    val oldWorkDir = File(dbPath)
                    if (!oldWorkDir.exists()) throw Exception(getString(R.string.msg_workdir_lost))

                    val uniqueId = System.currentTimeMillis().toString()
                    val newWorkDir = File(worksDir(context), "working_db_$uniqueId")
                    smartCopy(oldWorkDir, newWorkDir)
                    File(newWorkDir, "LOCK").delete()
                    File(newWorkDir, "LOG").delete()
                    File(newWorkDir, "LOG.old").delete()

                    // 先把当前正在编辑的数据写入缓存，确保不遗漏
                    val currentKey = evm.currentTargetKey.value
                    if (currentKey != null && dataToSave != null) {
                        evm.nbtDataCache[currentKey] = dataToSave
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
                    } catch (_: Exception) {}

// 遍历写入
                    for (key in allKeys) {
                        val data = evm.nbtDataCache[key]
                        if (data != null) {
                            val bytes = BedrockParser.writeToBytes(data)
                            db.writeSpecificKey(key, bytes)
                        }
                    }

                    for ((key, data) in evm.nbtDataCache) {
                        if (key != null && data != null) {
                            val bytes = BedrockParser.writeToBytes(data)
                            db.writeSpecificKey(key, bytes)
                        }
                    }

                    db.close()

                    val mcDbPath = "${resolveWorldPath(folder)}db/"
                    if (checkShizukuAvailable()) {
                        // Shizuku 模式：通过桥接目录
                        val bridgeSave = MainActivity.BRIDGE_ROOT + "save_db_$uniqueId"
                        runShizukuCmd(arrayOf("sh", "-c", "mkdir -p \"${MainActivity.BRIDGE_ROOT}\"")).waitFor()
                        createNoMedia()
                        runShizukuCmd(arrayOf("sh", "-c", "rm -rf \"$bridgeSave\"")).waitFor()
                        smartCopy(newWorkDir, File(bridgeSave))
                        runShizukuCmd(arrayOf("sh", "-c", "cp -rf \"$bridgeSave/.\" \"$mcDbPath\"")).waitFor()
                        runShizukuCmd(arrayOf("sh", "-c", "rm -rf \"$bridgeSave\"")).waitFor()
                    } else {
                        // 直接文件访问
                        val mcDbDir = File(mcDbPath)
                        if (!mcDbDir.exists()) mcDbDir.mkdirs()
                        smartCopy(newWorkDir, mcDbDir)
                    }

                    deleteRecursive(oldWorkDir)
                    currentWorkingDbPath = newWorkDir.absolutePath
                    _toastMessage.value = getString(R.string.toast_player_saved)
                } else {
                    if (currentWorkingFileOrDir == null) {
                        val f = File(worksDir(context), MainActivity.LEVEL_DAT_NAME)
                        currentWorkingFileOrDir = f.absolutePath
                    }
                    BedrockParser.write(dataToSave, currentWorkingFileOrDir)

                    val workingFile = File(currentWorkingFileOrDir!!)
                    val targetPath = "${resolveWorldPath(folder)}level.dat"
                    var success = copyFileNative(workingFile, File(targetPath))

                    if (!success && checkShizukuAvailable()) {
                        // Shizuku 降级
                        val bridgeFile = MainActivity.BRIDGE_ROOT + "level.dat"
                        File(MainActivity.BRIDGE_ROOT).mkdirs()
                        copyFile(workingFile, File(bridgeFile))
                        runShizukuCmd(arrayOf("sh", "-c", "cp \"$bridgeFile\" \"$targetPath\"")).waitFor()
                        success = true
                    }

                    if (!success) throw Exception(getString(R.string.msg_write_to_game_dir_failed))
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

    private fun createBackup(context: Context, folderName: String) {
        try {
            val backupRoot = File(backupsDir(context), folderName)
            if (!backupRoot.exists()) backupRoot.mkdirs()
            val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val evm = editorVM ?: return
            if (evm.isEditingPlayer.value) {
                currentWorkingDbPath?.let { dbPath ->
                    val srcDb = File(dbPath)
                    if (srcDb.exists() && srcDb.isDirectory) {
                        copyDirectory(srcDb, File(backupRoot, "db_$ts"))
                    }
                }
            } else {
                val src = File(worksDir(context), MainActivity.LEVEL_DAT_NAME)
                if (src.exists() && src.isFile) {
                    copyFileNative(src, File(backupRoot, "level_$ts.dat"))
                }
            }
        } catch (e: Exception) {
            Log.e("WorldVM", "Backup failed", e)
        }
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
            val dir = File(backupsDir(context), folderName)
            if (!dir.exists()) {
                _backupList.value = emptyList()
                return@launch
            }
            val files = dir.listFiles() ?: emptyArray()
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            files.sortByDescending { it.lastModified() }
            _backupList.value = files.map {
                BackupItem(it, it.name, sdf.format(Date(it.lastModified())), it.isDirectory)
            }
        }
    }

    fun restoreBackup(context: Context, backupItem: BackupItem) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val evm = editorVM ?: return@launch
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
    // 清理旧会话
    // ============================================
    fun cleanUpOldSessions(context: Context) {
        val privateWorks = File(context.getExternalFilesDir(null), "Works")
        cleanDirContent(privateWorks)
        val publicWorks = File("/storage/emulated/0/Download/NbtEditor_Data/Works")
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
            if ((it.isDirectory && (it.name.startsWith("working_db_") || it.name.startsWith("db_restore_")))
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
        require(hex.length % 2 == 0) { getString(R.string.msg_the_length_must_be_an_even_number) }
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

    fun checkShizukuStatus(context: Context? = null): ShizukuStatus {
        if (!useShizuku) return ShizukuStatus.NotInstalled
        try {
            val c = Class.forName("rikka.shizuku.Shizuku")
            val ping = c.getMethod("pingBinder").invoke(null) as? Boolean ?: return ShizukuStatus.NotRunning
            if (!ping) {
                // ping 失败，检查 Shizuku App 是否安装
                val isInstalled = context?.let { ctx ->
                    try {
                        ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
                        true
                    } catch (_: Exception) {
                        false
                    }
                } ?: true // 没传 context 时默认 NotRunning
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
    fun checkShizukuAvailable(): Boolean {
        return checkShizukuStatus() is ShizukuStatus.Available
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

    fun runShizukuCmd(cmd: Array<String?>): Process {
        if (!checkShizukuAvailable()) throw Exception(getString(R.string.err_shizuku_not_running))
        val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
        var m: Method? = null
        for (mm in shizukuClass.declaredMethods) {
            if (mm.name == "newProcess" && mm.parameterTypes.size == 3) { m = mm; break }
        }
        if (m == null) for (mm in shizukuClass.methods) {
            if (mm.name == "newProcess" && mm.parameterTypes.size == 3) { m = mm; break }
        }
        if (m == null) throw Exception(getString(R.string.msg_shizuku_api_error))
        m.isAccessible = true
        try { return m.invoke(null, cmd, null, null) as Process }
        catch (e: Exception) {
            if (e.message?.contains(getString(R.string.msg_binder_haven_t_been_received)) == true)
                throw Exception(getString(R.string.err_shizuku_binder_not_ready))
            throw e
        }
    }

    // ============================================
    // 文件操作
    // ============================================
    private fun copyFile(src: File, dst: File) {
        val `in` = FileInputStream(src)
        val out = FileOutputStream(dst)
        val buf = ByteArray(1024)
        var len: Int
        while ((`in`.read(buf).also { len = it }) > 0) out.write(buf, 0, len)
        `in`.close(); out.close()
    }

    private fun copyFileNative(src: File, dst: File): Boolean {
        return try { copyFile(src, dst); true } catch (_: Exception) { false }
    }

    private fun copyDirectory(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.exists()) target.mkdirs()
            source.list()?.forEach {
                if (it != "LOCK") copyDirectory(File(source, it), File(target, it))
            }
        } else copyFile(source, target)
    }

    private fun safCopyDirectory(context: Context, srcDoc: DocumentFile, dstDir: File) {
        if (!dstDir.exists()) dstDir.mkdirs()
        for (child in srcDoc.listFiles()) {
            val dstFile = File(dstDir, child.name ?: continue)
            if (child.isDirectory) {
                safCopyDirectory(context, child, dstFile)
            } else {
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    java.io.FileOutputStream(dstFile).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun deleteRecursive(f: File?) {
        if (f == null || !f.exists()) return
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
        f.delete()
    }

    // 删掉旧的简化版 smartCopy，替换为：
    private fun smartCopy(src: File, dst: File) {
        if (useMultiThread && src.isDirectory) {
            copyDirectoryParallel(src, dst)
        } else {
            copyDirectory(src, dst)
        }
    }

    private fun copyDirectoryParallel(source: File, target: File) {
        if (!target.exists()) target.mkdirs()

        val files = source.listFiles() ?: return
        val totalFiles = files.size
        if (totalFiles == 0) return

        // 小文件用单线程
        if (totalFiles < 10) {
            files.forEach { copyDirectory(File(source, it.name), File(target, it.name)) }
            return
        }

        val cores = Runtime.getRuntime().availableProcessors()
        val threadCount = min(cores + 1, 8)
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(totalFiles)
        val errorRef = AtomicReference<Throwable?>()

        var completed = 0
        val totalSize = files.sumOf { it.length() }
        var copiedSize = 0L

        files.forEach { file ->
            executor.submit {
                try {
                    if (errorRef.get() != null) return@submit

                    val srcFile = File(source, file.name)
                    val dstFile = File(target, file.name)

                    if (srcFile.isDirectory) {
                        copyDirectoryParallel(srcFile, dstFile)
                    } else {
                        copyFile(srcFile, dstFile)
                    }

                    synchronized(this) {
                        completed++
                        copiedSize += file.length()
                        val progress = (copiedSize * 100 / maxOf(totalSize, 1)).toInt()
                        _progressMessage.value = getString(R.string.copying_progress, completed, totalFiles, progress)
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
            _loadingMessage.value = "Reloading player..."
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
                _toastMessage.value = "Player reloaded"
            } catch (e: Exception) {
                _isLoading.value = false
                _errorMessage.value = e.message
            }
        }
    }
}