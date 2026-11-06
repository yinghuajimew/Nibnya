package yhjmew.nibnya

import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.*
import android.widget.*
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.activity.viewModels
import com.google.gson.*
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.io.*
import java.util.*
import yhjmew.nibnya.editor.dialogs.*
import yhjmew.nibnya.editor.EditorViewModel
import yhjmew.nibnya.world.WorldViewModel
import yhjmew.nibnya.world.WorldViewModel.BackupItem
import yhjmew.nibnya.world.WorldViewModel.CacheItem
import yhjmew.nibnya.world.WorldViewModel.WorldItem
import yhjmew.nibnya.mapart.MapArtViewModel
import yhjmew.nibnya.mapart.MapArtViewModel.InventorySlotInfo
import yhjmew.nibnya.mapart.showSlotGrid
import yhjmew.nibnya.mapart.showPuzzlePreview
import yhjmew.nibnya.nbt.BedrockParser
import yhjmew.nibnya.nbt.NbtTranslator
import yhjmew.nibnya.nbt.adapter.NbtAdapter
import yhjmew.nibnya.nbt.adapter.NbtTreeAdapter
import yhjmew.nibnya.util.AppDirs
import yhjmew.nibnya.util.PrivilegedExec
import yhjmew.nibnya.util.AppLogger
import yhjmew.nibnya.util.CrashHandler
import yhjmew.nibnya.util.DraftAutoSaver
import yhjmew.nibnya.util.DraftStore
import yhjmew.nibnya.util.NbtClipboard
import yhjmew.nibnya.util.NbtExporter
import yhjmew.nibnya.util.WorldExporter
import yhjmew.nibnya.util.TrashStore
import yhjmew.nibnya.util.ColorApplier
import yhjmew.nibnya.util.ColorScheme

class MainActivity : ComponentActivity() {

    // ============================================
    // ViewModels
    // ============================================
    internal val editorVM: EditorViewModel by viewModels()
    internal val worldVM: WorldViewModel by viewModels()
    internal val mapVM: MapArtViewModel by viewModels()

    // ============================================
    // UI 控件
    // ============================================
    internal var etWorldName: EditText? = null
    internal var nbtListView: ListView? = null
    internal var tvCurrentPath: TextView? = null
    internal var tvFullscreenPath: TextView? = null
    internal var layoutNormalUi: View? = null
    internal var layoutEditorHeader: View? = null
    internal var layoutFullscreenBar: View? = null
    internal var viewSidebar: View? = null

    // ============================================
    // 适配器
    // ============================================
    internal var nbtAdapter: NbtAdapter? = null
    internal var nbtTreeAdapter: NbtTreeAdapter? = null

    // ============================================
    // 缓存
    // ============================================
    internal var cacheMapList: MutableList<String>? = null
    internal var cacheVillageList: MutableList<String>? = null
    internal var cachePlayerList: MutableList<String>? = null

    // ============================================
    // UI 状态
    // ============================================
    internal var isNightMode = false
    internal var isFullScreen = false

    /** 底部三格导航（主页 / 地图 / 其他）。在 initViews() 里创建。 */
    internal var bottomNav: BottomNavController? = null

    /** 地图页控制器（只读地形预览）。在 initViews() 里创建，onDestroy 释放。 */
    internal var mapPage: yhjmew.nibnya.map.MapPageController? = null
    internal var currentLang: String? = null
    internal var prefs: SharedPreferences? = null

    // ============================================
    // 拼图状态
    // ============================================
    var puzzleRows = 1
    var puzzleCols = 1
    var currentTargetMapArray: JsonArray? = null
    private var puzzleImageUri: Uri? = null

    /**
     * 自由裁剪模式下待用的裁剪矩形（**原图像素坐标**）。
     * null 表示走「按行列切」。选完槽位后连同图片一起交给生成器。
     */
    private var pendingPuzzleCrop: android.graphics.RectF? = null

    /**
     * 行列模式的补边颜色：-1 = 不补边（居中裁剪，默认）；
     * 0 = 黑 / 1 = 白 / 2 = 透明。与 [pendingPuzzleCrop] 互斥。
     */
    private var pendingPuzzlePad: Int = -1




    private val safPathLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            worldVM.setSafPath(uri)
            prefs?.edit()?.putString("saf_tree_uri", uri.toString())?.apply()
            prefs?.edit()?.putString("last_path", "$uri/")?.apply()
            toast(getString(R.string.msg_saf_selected))
            showWorldSelector()
        }
    }

    /**
     * issue #6：统一切换路径 + 持久化，避免重启后回退到 Android/data（需 Shizuku）
     */
    private fun applyPath(newPath: String) {
        worldVM.switchPath(newPath)
        prefs?.edit()?.putString("last_path", newPath)?.apply()
    }

    // ============================================
    // 导出到用户自选目录（SAF，无需存储权限）
    // ============================================
    /** 待导出的内容（用户选目录期间暂存） */
    internal var pendingExportContent: String? = null
    internal var pendingExportName: String? = null

    private val exportDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val content = pendingExportContent
        val name = pendingExportName
        val srcFilePath = pendingExportFilePath
        pendingExportContent = null
        pendingExportName = null
        pendingExportFilePath = null
        if (uri == null || name == null) return@registerForActivityResult

        // 情形 A：已打包好的文件（.mcworld）—— 直接复制
        if (srcFilePath != null) {
            val ok = try {
                val src = File(srcFilePath)
                val dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)
                val doc = dir?.createFile("application/octet-stream", name)
                if (doc != null) {
                    contentResolver.openOutputStream(doc.uri)?.use { out ->
                        FileInputStream(src).use { input -> input.copyTo(out, 64 * 1024) }
                    }
                    src.delete()
                    true
                } else false
            } catch (e: Exception) {
                AppLogger.warn("Export", R.string.log_export_mcworld_saf_failed, e.message)
                false
            }
            toast(getString(if (ok) R.string.toast_exported_ok else R.string.toast_export_failed))
            return@registerForActivityResult
        }

        // 情形 B：文本内容（JSON/SNBT/TXT）
        if (content == null) return@registerForActivityResult
        if (NbtExporter.saveViaSaf(this, uri, name, content)) {
            toast(getString(R.string.toast_exported_ok))
        } else {
            toast(getString(R.string.toast_export_failed))
        }
    }

    /** 让用户选一个目录来保存导出文件 */
    internal fun startExportToChosenDir(element: JsonElement, fmt: Int, nameHint: String?) {
        pendingExportContent = NbtClipboard.formatAs(element, fmt)
        pendingExportName = NbtExporter.buildFileName(nameHint, fmt)
        exportDirLauncher.launch(null)
    }

    internal val puzzleImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult

        // 是否每次都询问「要不要调整裁剪」
        //  · 设置里关掉 → 直接按当前行列装箱（等于「不调整」）
        //  · 询问时勾了「不再提示」→ 记住本次选择，以后照办
        val pref = getSharedPreferences("nbt_config", android.content.Context.MODE_PRIVATE)
        val askCrop = pref.getBoolean("puzzle_ask_crop", true)
        val skipCrop = pref.getBoolean("puzzle_skip_crop", false)

        // 直接装箱的分支
        fun packDirectly() {
            puzzleImageUri = uri
            pendingPuzzleCrop = null
            mapVM.scanInventory(this)
        }

        if (!askCrop) {
            if (skipCrop) packDirectly() else openPuzzlePreview(uri)
            return@registerForActivityResult
        }

        // 询问框（带「不再提示」）
        val chk = android.widget.CheckBox(this).apply {
            text = getString(R.string.chk_dont_ask_again)
            val pad = (10 * resources.displayMetrics.density).toInt()
            setPadding(pad * 2, pad, 0, pad)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_puzzle_ask_crop))
            .setMessage(getString(R.string.msg_puzzle_ask_crop))
            .setView(chk)
            .setPositiveButton(getString(R.string.btn_go_crop)) { _, _ ->
                if (chk.isChecked) {
                    pref.edit().putBoolean("puzzle_ask_crop", false)
                        .putBoolean("puzzle_skip_crop", false).apply()
                }
                openPuzzlePreview(uri)
            }
            .setNegativeButton(getString(R.string.btn_skip_crop)) { _, _ ->
                if (chk.isChecked) {
                    pref.edit().putBoolean("puzzle_ask_crop", false)
                        .putBoolean("puzzle_skip_crop", true).apply()
                }
                packDirectly()
            }
            .setCancelable(false)
            .show()
    }

    /** 打开拼图预览（按行列切 / 自由裁剪） */
    private fun openPuzzlePreview(uri: Uri) {
        showPuzzlePreview(
            uri = uri,
            onConfirm = { _, _, padOn, padClr ->
                // 按行列切：沿用现有流程（扫背包 -> 选槽位 -> 生成 N 张）
                puzzleImageUri = uri
                pendingPuzzlePad = if (padOn) padClr else -1
                pendingPuzzleCrop = null
                mapVM.scanInventory(this)
            },
            onCropConfirm = { rect, cRows, cCols ->
                // 自由裁剪：裁出的区域同样切成 rows x cols 张
                // （之前写死 1 张，选 5x5 也只出一张）
                puzzleImageUri = uri
                pendingPuzzleCrop = rect
                puzzleRows = cRows
                puzzleCols = cCols
                mapVM.scanInventory(this)
            },

            onCancel = { puzzleImageUri = null }
        )
    }

    internal val mapImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { mapVM.generateMapFromImage(this, it, currentTargetMapArray) }
    }


    // ============================================
    // 进度对话框
    // ============================================
    private var progressDialog: AlertDialog? = null
    private var tvProgressMessage: TextView? = null

    private var pendingWorldSelector = false

    /** 当前备份列表所属的存档目录（重命名/删除后据此刷新列表） */
    private var backupListFolder: String? = null

    // ============================================
    // 语言
    // ============================================
    override fun attachBaseContext(newBase: Context) {
        val p = newBase.getSharedPreferences("nbt_config", MODE_PRIVATE)
        val lang = p.getString("app_language",
            if (Locale.getDefault().language.contains("zh")) "zh" else "en")!!
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(if ("en" == lang) Locale.ENGLISH else Locale.CHINESE)

        // 夜间模式：在这里覆盖 uiMode，values-night 才会生效。
        // ⚠️ 这是「不换基类」的做法 —— 直接把 Configuration.uiMode 改成
        //    UI_MODE_NIGHT_YES/NO，系统在解析资源时就会去 values-night 找。
        //    比起把 MainActivity 改成 AppCompatActivity（会连带影响 12 个文件里的
        //    系统 AlertDialog 主题），这个代价几乎为零。
        val night = p.getBoolean("night_mode", false)
        config.uiMode = (config.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES
                 else android.content.res.Configuration.UI_MODE_NIGHT_NO)

        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    // ============================================
    // onCreate
    // ============================================
    @SuppressLint("UseKtx")
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashHandler.instance!!.init(this)
        super.onCreate(savedInstanceState)
        // 日志头部要带设备信息、且文案跟随应用语言，需要 Context
        AppLogger.init(this)

        NbtTranslator.init(this)
        createNoMedia()
        val pr = AppDirs.publicRoot()
        // 用 worldVM 的目录定义（它按当前路径选择公共目录或 App 私有目录），
        // 不能另起一套，否则会建到完全不同的位置去（见 Bug 1）
        ensureNoMedia(worldVM.worksDir(this)); ensureNoMedia(worldVM.backupsDir(this))
        ensureNoMedia(File(pr, AppDirs.WORKS)); ensureNoMedia(File(pr, AppDirs.BACKUPS))
        ensureNoMedia(File(pr, AppDirs.BRIDGE))

        prefs = getSharedPreferences("nbt_config", MODE_PRIVATE)
        isNightMode = prefs!!.getBoolean("night_mode", false)
        currentLang = prefs!!.getString("app_language",
            if (Locale.getDefault().language.contains("zh")) "zh" else "en")

        val isChinese = Locale.getDefault().language.contains("zh")
        editorVM.setViewMode(prefs!!.getInt("view_mode", if (isChinese) 2 else 0))
        // ── 运行模式：旧配置迁移 + 启动检测 ──────────────────────────
        // 老的「跳过 Shizuku」复选框已移除，改由 PrivilegedExec 的三种模式统一控制。
        // 迁移规则：
        //   · 用户之前勾了「跳过」  -> 运行模式 = normal
        //   · 否则按「自动」处理（启动时实测 root / Shizuku 再决定）
        if (!prefs!!.contains(PrivilegedExec.PREF_EXEC_MODE)) {
            val legacySkip = prefs!!.contains("skip_shizuku") &&
                prefs!!.getBoolean("skip_shizuku", false)
            prefs!!.edit()?.putString(
                PrivilegedExec.PREF_EXEC_MODE,
                if (legacySkip) "normal" else "auto"
            )?.apply()
        }
        // 清理旧键，避免以后又和新模式互相矛盾
        prefs!!.edit()?.remove("skip_shizuku")?.apply()

        // 启动时实测一次（毫秒级）：root > Shizuku > 正常
        val selectedMode = PrivilegedExec.selectedModePref(this)
        val resolvedMode = PrivilegedExec.resolveMode(this, selectedMode)
        PrivilegedExec.setMode(resolvedMode)
        worldVM.setUseShizuku(resolvedMode != PrivilegedExec.Mode.NORMAL)
        worldVM.appContext = applicationContext
        AppLogger.info("ExecMode", "selected=$selectedMode resolved=$resolvedMode")

        // 多线程设置：启动时从 prefs 恢复（修复「设置不生效、重启失效」）
        worldVM.setUseMultiThread(prefs!!.getBoolean("use_multithread", true))
        // 恢复上次的 NBT 剪贴板内容（进程被杀/重启后仍可用）
        NbtClipboard.init(this)

        // 草稿箱自动保存：恢复设置并初始化
        DraftAutoSaver.init(this)
        DraftAutoSaver.keepOriginal = prefs!!.getBoolean("draft_keep_original", false)
        DraftAutoSaver.maxDrafts = prefs!!.getInt("draft_max", DraftStore.DEFAULT_MAX_DRAFTS)
        run {
            val iv = prefs!!.getInt("draft_interval", 30)
            val unit = prefs!!.getInt("draft_interval_unit", 0)
            val factor = when (unit) { 1 -> 60_000L; 2 -> 3_600_000L; else -> 1_000L }
            DraftAutoSaver.setInterval(if (iv <= 0) 0L else iv.toLong() * factor)
        }
        worldVM.setIoThreadCount(prefs!!.getInt("io_thread_count", 0))
        mapVM.setPuzzleMultiThread(prefs!!.getBoolean("puzzle_multithread", true))
        mapVM.setPuzzleThreadCount(prefs!!.getInt("puzzle_thread_count", 0))

        // issue #6：恢复上次使用的路径（避免重启后回退到需 Shizuku 的 Android/data）
        prefs!!.getString("last_path", null)?.let { saved ->
            worldVM.restorePath(saved)
            // 若是 SAF 路径，同步恢复 tree uri 状态
            if (saved.startsWith("content://")) {
                try {
                    worldVM.safTreeUri = saved.trimEnd('/').toUri()
                } catch (e: Exception) { AppLogger.warn("Path", "恢复上次路径失败: ${e.message}") }
            }
        }

        // SAF 路径：正式恢复 tree uri（拿回持久权限），否则重启后会静默回退到 File 模式
        prefs!!.getString("saf_tree_uri", null)?.let { uriStr ->
            try {
                val uri = android.net.Uri.parse(uriStr)
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                worldVM.safTreeUri = uri
                worldVM.switchPath("$uri/")
            } catch (_: Exception) {
                // 授权已失效（换机/清理数据/用户撤销）→ 清掉记录，让用户重选
                prefs!!.edit().remove("saf_tree_uri").apply()
                worldVM.safTreeUri = null
            }
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.main)
        initViews()
        applyTheme()

        worldVM.editorVM = editorVM
        mapVM.worldVM = worldVM
        setupObservers(); setupButtons()
        // ViewModel 是跨 recreate 存活的：里面以 StateFlow 保存的文本（路径标题等）在重建后
        // **不会重新发射**（值与上次相等），只靠 collect 初始化就会留下**旧语言的残影**。
        // ⚠️ 但只有在真的加载了数据时才能重算：没加载数据时 updatePathTitle() 会算出
        //    「当前：世界数据 (level.dat)」这种假状态（用户还没选存档却显示已选）。
        if (editorVM.nbtData.value != null) editorVM.updatePathTitle()

        try { Class.forName("rikka.shizuku.Shizuku") }
        catch (e: Exception) { toast(getString(R.string.toast_shizuku_init, e.message)) }
        Shizuku.addRequestPermissionResultListener { _, grant ->
            toast(if (grant == PackageManager.PERMISSION_GRANTED)
                getString(R.string.toast_shizuku_granted)
            else getString(R.string.toast_shizuku_denied))
        }

        when {
            Build.VERSION.SDK_INT >= 30 -> {
                // Android 11+ 需要所有文件访问权限
                if (!Environment.isExternalStorageManager()) {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.title_need_all_files))
                        .setMessage(getString(R.string.msg_need_all_files))
                        .setPositiveButton(getString(R.string.btn_go_grant)) { _, _ ->
                            try {
                                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = "package:$packageName".toUri()
                                })
                            } catch (_: Exception) {
                                toast(getString(R.string.toast_open_settings_failed))
                            }
                        }
                        // issue #6：不再阻断，用户可稍后处理（自定义路径可能根本不需要此权限）
                        .setNegativeButton(getString(R.string.btn_later), null)
                        .setCancelable(true)
                        .show()
                }
            }
            Build.VERSION.SDK_INT >= 23 -> {
                // Android 6-10 请求存储权限
                if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                        arrayOf(
                            android.Manifest.permission.READ_EXTERNAL_STORAGE,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ),
                        REQUEST_STORAGE_PERMISSION
                    )
                }
            }
        }

        // 所有文件访问权限：**不再在这里直接跳系统设置**。
        // 旧版本这里调了 worldVM.checkStoragePermission()，它在没有弹窗的情况下直接 startActivity，
        // 结果是「说明弹窗刚出现就被系统设置页顶掉」。现在一律先让用户读完说明并主动确认；
        // 权限申请推迟到真正需要读写的时机（打开存档 / 保存 / 切换路径）。

        Shizuku.addRequestPermissionResultListener { _, grant ->
            toast(if (grant == PackageManager.PERMISSION_GRANTED)
                getString(R.string.toast_shizuku_granted)
            else getString(R.string.toast_shizuku_denied))
        }

        if (savedInstanceState == null) {
            worldVM.cleanUpOldSessions(this)
            // 回收站自动清理（天数可在设置里改；0 = 不自动清理）
            val keepDays = prefs!!.getInt("trash_keep_days", TrashStore.DEFAULT_KEEP_DAYS)
            Thread { runCatching { worldVM.autoCleanTrash(this, keepDays) } }.start()
            // 旧数据目录迁移（NbtEditor_Data → Nibnya）：检测到就提示一次
            maybePromptDataMigration()
        }
        restoreSavedState(savedInstanceState)
    }

    override fun onStop() {
        super.onStop()
        // 进程随时可能被系统回收，离开前台前把未保存的编辑落盘到「草稿箱」
        // （崩溃场景由 CrashHandler 负责，它会在进程终止前再抢救一次）
        worldVM.persistSessionNow(this)
    }

    override fun onDestroy() {
        // 地图页持有 LevelDB 连接和一份 db 副本，必须在这里关掉并删除，
        // 否则 Works 目录会随每次开图累积，且 db 句柄泄漏。
        mapPage?.release()
        mapPage = null
        super.onDestroy()
    }

    private fun initViews() {
        viewSidebar = findViewById(R.id.sidebar_content)
        etWorldName = findViewById<EditText>(R.id.et_world_name)?.also { et ->
            // 这个控件只是「点击打开存档选择器」的触发器，不参与文本编辑。
            // 保持 EditText 类型（代码里大量用 .text 读写），但禁掉一切文本交互：
            // 否则长按会弹出系统的「粘贴 / 全选」菜单，粘贴后内容被当成存档名。
            et.isLongClickable = false
            et.isCursorVisible = false
            et.setTextIsSelectable(false)
            et.setOnLongClickListener { true }   // 消费长按，不弹菜单
        }
        nbtListView = findViewById(R.id.lv_nbt_list)
        tvCurrentPath = findViewById(R.id.tv_current_path)
        layoutNormalUi = findViewById(R.id.layout_normal_ui)
        layoutEditorHeader = findViewById(R.id.layout_editor_header)
        layoutFullscreenBar = findViewById(R.id.layout_fullscreen_bar)
        tvFullscreenPath = findViewById(R.id.tv_fullscreen_path)

        bottomNav = BottomNavController(this)

        // 地图页（只读地形预览，2026-11-02）
        mapPage = yhjmew.nibnya.map.MapPageController(this, worldVM)

        // 按用户配色刷一遍主页（默认颜色模式时走 colors.xml 原值，等于没刷）
        prefs?.let { ColorApplier.apply(this, it) }
    }

    // ============================================
    // 观察者
    // ============================================
    private fun setupObservers() {
        lifecycleScope.launch {
            editorVM.nbtData.collect { if (it != null) refreshAdapter(it) }
        }
        lifecycleScope.launch {
            editorVM.pathTitle.collect { tvCurrentPath?.text = it; tvFullscreenPath?.text = it }
        }
        lifecycleScope.launch {
            combine(worldVM.isLoading, mapVM.isGenerating) { w, m -> w || m }
                .collect {
                    if (it) showProgressDialog(getString(R.string.log_please_wait),
                        worldVM.loadingMessage.value.ifEmpty { mapVM.progressMessage.value }.ifEmpty { "..." })
                    else dismissProgressDialog()
                    // 防狂点：忙碌期间禁用「保存修改」（含保存、导出、加载等所有耗时操作）
                    findViewById<Button?>(R.id.btn_save)?.isEnabled = !it
                }
        }
        lifecycleScope.launch {
            worldVM.progressMessage.collect { if (it.isNotEmpty()) updateProgress(it) }
        }
        lifecycleScope.launch {
            mapVM.progressMessage.collect { if (it.isNotEmpty()) updateProgress(it) }
        }
        lifecycleScope.launch {
            worldVM.toastMessage.collect { it?.let { toast(it); worldVM.clearToast() } }
        }
        lifecycleScope.launch {
            mapVM.resultMessage.collect { msg -> msg?.let { handleResult(it); mapVM.clearResult() } }
        }
        lifecycleScope.launch {
            worldVM.worldNameForTitle.collect { name ->
                findViewById<TextView?>(R.id.tv_app_title)?.text = name ?: getString(R.string.app_name)
            }
        }
        lifecycleScope.launch {
            worldVM.worldSeedForDisplay.collect { seed ->
                findViewById<TextView?>(R.id.tv_info_seed)?.let { tv ->
                    if (seed != null) {
                        tv.text = getString(R.string.key_seed_display, seed)
                        tv.isVisible = true
                        // 点一下就直接复制纯数字种子。
                        // 不用长按选中再复制：长按会弹系统菜单，还容易把
                        // 前面的 🌱 图标一起选进去。
                        tv.setOnClickListener {
                            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("seed", seed.toString())
                            )
                            toast(getString(R.string.toast_seed_copied))
                        }
                    } else {
                        tv.isVisible = false
                        tv.setOnClickListener(null)
                    }
                }
            }
        }
        lifecycleScope.launch {
            worldVM.scanResultChannel.receiveAsFlow().collect { result ->
                if (pendingWorldSelector) {
                    pendingWorldSelector = false
                    if (result.error != null) {
                        toast(getString(R.string.toast_scanning_failed, result.error))
                    } else {
                        showWorldSelectorDialog(result.worlds)
                    }
                }
            }
        }

        lifecycleScope.launch {
            worldVM.cacheList.drop(1).collect { showCacheDialog(it) }
        }
        // 备份列表：用事件通道而不是 StateFlow.drop(1)
        // （StateFlow 内容相同时不发射 → 扫同一存档两次会「点了没反应」）
        lifecycleScope.launch {
            worldVM.backupScanDone.receiveAsFlow().collect { list ->
                showBackupDialog(list)
            }
        }
        lifecycleScope.launch {
            worldVM.errorMessage.collect { err -> err?.let { showErrorDialog(it); worldVM.clearError() } }
        }
        lifecycleScope.launch {
            worldVM.isLoading.drop(1).collect { loading ->
                if (!loading) {
                    worldVM.pendingSidebarTask?.let { task ->
                        worldVM.pendingSidebarTask = null
                        task.run()
                    }
                }
            }
        }

        lifecycleScope.launch {
            worldVM.offerCreateKey.collect { keyName ->
                keyName?.let {
                    worldVM.clearOfferCreateKey()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.title_data_not_exist))
                        .setMessage(getString(R.string.msg_no_data_create_new, it))
                        .setPositiveButton(getString(R.string.btn_create_and_open)) { _, _ ->
                            worldVM.createNewGlobalData(it)
                        }
                        .setNegativeButton(getString(R.string.btn_cancel), null)
                        .show()
                }
            }
        }

        lifecycleScope.launch {
            mapVM.inventorySlotInfo.collect { info ->
                info?.let { showSlotPickerDialog(it) }
            }
        }
    }

    // ============================================
    // 按钮
    // ============================================
    @SuppressLint("ClickableViewAccessibility")
    private fun setupButtons() {
        val sc = findViewById<View?>(R.id.custom_sidebar_container)

        tvCurrentPath?.setOnClickListener { toast(getString(R.string.hint_long_press_root)) }
        tvCurrentPath?.setOnLongClickListener { showRootMenu(); true }

        findViewById<View?>(R.id.btn_go_fullscreen)?.setOnClickListener { toggleFullScreen(true) }
        findViewById<View?>(R.id.btn_exit_fullscreen)?.setOnClickListener {
            if (editorVM.navigationStack.isNotEmpty()) onBackPressed() else toggleFullScreen(false)
        }
        findViewById<View?>(R.id.btn_fullscreen_menu)?.setOnClickListener { showRootMenu() }

        findViewById<Button?>(R.id.btn_copy)?.setOnClickListener {
            val f = etWorldName!!.text.toString().trim()
            if (f.isEmpty() || f == getString(R.string.hint_select_world)) showWorldSelector()
            else { toast(getString(R.string.toast_loading, f)); worldVM.openLevelDat(this, f) }
        }
        etWorldName?.setOnClickListener { showWorldSelector() }

        findViewById<Button?>(R.id.btn_edit_player)?.setOnClickListener {
            val f = etWorldName!!.text.toString()
            if (checkFolder(f)) worldVM.openPlayerData(this, f)
        }
        findViewById<Button?>(R.id.btn_parse)?.setOnClickListener { reparseLevelDat() }
        findViewById<Button?>(R.id.btn_switch_path)?.setOnClickListener {
            // 换路径往往就是换到需要权限的位置，先说明再申请
            if (askAllFilesPermission(getString(R.string.msg_perm_needed_for_path))) showPathSelector()
        }
        findViewById<Button?>(R.id.btn_clear_cache)?.setOnClickListener { worldVM.scanCache(this) }

        findViewById<Button?>(R.id.btn_backup_history)?.setOnClickListener {
            // 备份与草稿箱拼一个入口，各自独立菜单
            val opts = arrayOf(
                getString(R.string.btn_restore),
                getString(R.string.btn_draft_box)
            )
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_backup_or_draft))
                .setItems(opts) { _, w ->
                    if (w == 0) {
                        // 备份按存档划分，需要先确定是哪个存档。
                        // 已经选了存档就直接列它的备份；没选才弹存档选择器（少一步操作）。
                        val f = etWorldName!!.text.toString()
                        if (f.isEmpty() || f == getString(R.string.hint_select_world)) {
                            toast(getString(R.string.toast_select_world))
                            showWorldSelector()
                        } else {
                            backupListFolder = f
                            worldVM.scanBackups(this, f)
                        }
                    } else {
                        showDraftBoxDialog()
                    }
                }.setNegativeButton(getString(R.string.btn_cancel), null).show()
        }
        findViewById<Button?>(R.id.btn_save)?.setOnClickListener {
            // 防狂点：保存期间按钮置灰，结束（成功或失败）后由 isLoading 观察者恢复
            it.isEnabled = false
            if (!saveAllChanges()) it.isEnabled = true
        }

        val btnTree = findViewById<View?>(R.id.btn_tree_mode)
        btnTree?.setOnClickListener { toggleTreeMode() }
        findViewById<View?>(R.id.btn_fullscreen_tree)?.setOnClickListener { btnTree?.performClick() }

        findViewById<View?>(R.id.btn_view_options)?.setOnClickListener { showViewOptionsDialog() }
        findViewById<View?>(R.id.btn_fullscreen_view)?.setOnClickListener { showViewOptionsDialog() }

        findViewById<View?>(R.id.btn_open_drawer)?.setOnClickListener {
            openSidebarWithAnimation()
        }
        findViewById<View?>(R.id.view_mask)?.setOnClickListener {
            closeSidebarWithAnimation()
        }

        // 边缘滑动在 dispatchTouchEvent 里统一处理（见 handleEdgeSwipe）

        // 侧边栏按钮
        findViewById<View?>(R.id.btn_online_players)?.setOnClickListener {
            sc?.isVisible = false; ensureDbLoaded { showMultiPlayerDialog() }
        }
        findViewById<View?>(R.id.btn_map_nbt)?.setOnClickListener {
            sc?.isVisible = false; ensureDbLoaded { showMapListDialog() }
        }
        findViewById<View?>(R.id.btn_village_nbt)?.setOnClickListener {
            sc?.isVisible = false; ensureDbLoaded { showVillageListDialog() }
        }
        findViewById<View?>(R.id.btn_global_nbt)?.setOnClickListener {
            sc?.isVisible = false; ensureDbLoaded { showGlobalDataDialog() }
        }
        findViewById<View?>(R.id.btn_search_nbt)?.setOnClickListener {
            // 树形与列表模式都支持搜索
            showEditorSearchDialog()
        }
        findViewById<View?>(R.id.btn_fullscreen_search)?.setOnClickListener {
            findViewById<View?>(R.id.btn_search_nbt)?.performClick()
        }
        findViewById<View?>(R.id.btn_open_any_key)?.setOnClickListener {
            if (worldVM.currentWorkingDbPath == null) toast(getString(R.string.toast_init_db_first))
            else { sc?.isVisible = false; showOpenByKeyDialog() }
        }

        nbtListView?.onItemClickListener = AdapterView.OnItemClickListener { _, _, p, _ -> onNbtClick(p) }
        nbtListView?.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, p, _ ->
            onNbtLongClick(p); true
        }

        findViewById<View?>(R.id.sidebar_content)?.let {
            it.layoutParams.width = resources.displayMetrics.widthPixels * 2 / 3
            it.layoutParams = it.layoutParams
        }

        findViewById<View?>(R.id.sidebar_header)?.setOnClickListener { showAppInfoDialog() }
        findViewById<View?>(R.id.iv_app_icon)?.setOnClickListener { showAppInfoDialog() }
    }

    // ============================================
    // NBT 列表交互
    // ============================================
    private fun onNbtClick(pos: Int) {
        if (editorVM.isTreeMode.value) {
            editorVM.lastTreeClickPosition = pos
            if (nbtTreeAdapter!!.isContainer(pos)) nbtTreeAdapter!!.toggleExpand(pos)
            else nbtTreeItemClick(pos)
        } else listItemClick(pos)
    }

    private fun nbtTreeItemClick(pos: Int) {
        val node = nbtTreeAdapter!!.getNode(pos) ?: return
        val valueObj = node.value as? JsonObject ?: return
        val v = valueObj.get("v") ?: return
        val type = node.type
        if ((type == 7 || type == 11 || type == 12) && v.isJsonArray && v.asJsonArray.size() > 500)
            showArrayPaginationDialog(node.key, v.asJsonArray)
        else showEditValueDialog(node.key, valueObj, nbtTreeAdapter!!.getListElementInfo(pos))
    }

    private fun listItemClick(pos: Int) {
        val key = nbtAdapter!!.getKey(pos)
        val raw = nbtAdapter!!.data!!.get(key)
        val item = editorVM.wrapRawItem(raw)
        val type = item.get("t").asInt
        if (type == 10) {
            editorVM.enterFolder(key, item.getAsJsonObject("v"), false, nbtListView!!.firstVisiblePosition)
        } else if (type == 9) {
            editorVM.enterFolder(key, editorVM.convertListToMap(item), true, nbtListView!!.firstVisiblePosition)
        } else {
            val v = item.get("v")
            if ((type == 7 || type == 11 || type == 12) && v.isJsonArray && v.asJsonArray.size() > 500)
                showArrayPaginationDialog(key, v.asJsonArray)
            else showEditValueDialog(key, item)
        }
    }

    private fun onNbtLongClick(pos: Int) {
        editorVM.lastTreeClickPosition = pos
        if (editorVM.isTreeMode.value) {
            val node = nbtTreeAdapter!!.getNode(pos) ?: return
            val value = node.value as? JsonObject ?: return
            showLongPressMenu(node.key, value)
        } else {
            val key = nbtAdapter!!.getKey(pos)
            val raw = nbtAdapter!!.data!!.get(key)
            showLongPressMenu(key, editorVM.wrapRawItem(raw))
        }
    }

    // ============================================
    // 适配器
    // ============================================
    internal fun refreshAdapter(data: JsonObject?) {
        if (editorVM.isTreeMode.value) {
            nbtTreeAdapter = NbtTreeAdapter(this, data).also { it.setViewMode(editorVM.viewMode.value) }
            nbtListView?.adapter = nbtTreeAdapter
        } else {
            editorVM.currentListData = data
            nbtAdapter = NbtAdapter(this, data).also { it.setViewMode(editorVM.viewMode.value) }
            nbtAdapter!!.setPathContext(
                if (editorVM.pathStack.size >= 2) editorVM.pathStack[editorVM.pathStack.size - 2] else ""
            )
            nbtListView?.adapter = nbtAdapter
        }
    }

    private fun toggleTreeMode() {
        editorVM.toggleTreeMode()
        toast(if (editorVM.isTreeMode.value) getString(R.string.toast_view_tree)
        else getString(R.string.toast_tree_switch_mode_default))

        if (editorVM.isTreeMode.value) {
            // 进入树形：始终以「真正的根数据」为根。
            // 否则从列表模式深层切过来时，树只显示子树，
            // 而保存写的却是整份数据，容易让用户困惑。
            val root = editorVM.getRootData() ?: return
            editorVM.setRawNbtData(root)
            editorVM.navigationStack.clear()
            editorVM.pathStack.clear()
            editorVM.scrollPositionStack.clear()
            editorVM.lastTreeClickPosition = -1
            editorVM.currentListData = root
            nbtTreeAdapter = NbtTreeAdapter(this, root).also { it.setViewMode(editorVM.viewMode.value) }
            nbtListView?.adapter = nbtTreeAdapter
        } else {
            val data = editorVM.nbtData.value ?: return
            if (nbtTreeAdapter != null && editorVM.lastTreeClickPosition >= 0)
                editorVM.syncListModeFromPath(nbtTreeAdapter!!.getNodePath(editorVM.lastTreeClickPosition))
            refreshAdapter(editorVM.currentListData ?: data)
        }
        editorVM.updatePathTitle()
    }

    // ============================================
    // 侧边栏：边缘滑出 + 跟手拖动
    // ============================================

    /** 边缘触发区宽度：只认屏幕最左侧这一窄条，避免误触 */
    private val edgeTriggerWidthDp = 18f

    /**
     * 侧边栏宽度 = 屏宽 × 0.78，与「以前」的观感一致（约占 3/4 屏）。
     * 右侧留出的部分放遮罩，点它可关闭。
     */
    private val sidebarWidthPercent = 0.66f

    private var drawerOpen = false

    // 手势状态
    private var edgeTracking = false
    private var edgeDragging = false
    private var edgeDownX = 0f
    private var edgeStartedOpen = false   // 本次手势是从「已打开」状态开始（右→左关闭）

    private fun sidebarView(): View? = findViewById(R.id.sidebar_content)
    private fun sidebarContainer(): View? = findViewById(R.id.custom_sidebar_container)
    private fun maskView(): View? = findViewById(R.id.view_mask)

    /** 侧边栏收起时的位移 = -自身宽度（宽度未知时按屏宽 × 比例估算） */
    private fun sidebarClosedX(): Float {
        val v = sidebarView() ?: return 0f
        val w = if (v.width > 0) v.width
                else (resources.displayMetrics.widthPixels * sidebarWidthPercent).toInt()
        return -w.toFloat().coerceAtLeast(1f)
    }

    /** 侧边栏宽度 = 屏宽 × 比例；遮罩宽度 = 剩余部分 */
    private fun applySidebarSize() {
        val w = resources.displayMetrics.widthPixels
        val sbW = (w * sidebarWidthPercent).toInt()
        sidebarView()?.let { v ->
            val lp = v.layoutParams ?: return@let
            if (lp.width != sbW) { lp.width = sbW; v.layoutParams = lp }
        }
        maskView()?.let { m ->
            val mw = (w - sbW).coerceAtLeast(0)
            val lp = (m.layoutParams as? android.widget.FrameLayout.LayoutParams)
                ?: android.widget.FrameLayout.LayoutParams(mw, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            if (lp.width != mw) {
                lp.width = mw
                lp.gravity = android.view.Gravity.END
                m.layoutParams = lp
            }
        }
    }

    internal fun openSidebarWithAnimation(animate: Boolean = true) {
        val sc = sidebarContainer() ?: return
        val v = sidebarView() ?: return
        sc.visibility = View.VISIBLE
        sc.bringToFront()
        applySidebarSize()
        drawerOpen = true

        if (!animate) {
            v.translationX = 0f
            return
        }
        // 先摆到收起位置，再滑入；必须等一次布局，否则 width 还是 0
        v.post {
            v.translationX = sidebarClosedX()
            v.animate().translationX(0f).setDuration(200L).start()
        }
    }

    internal fun closeSidebarWithAnimation(after: (() -> Unit)? = null) {
        val v = sidebarView() ?: return
        drawerOpen = false
        v.animate().translationX(sidebarClosedX()).setDuration(180L)
            .withEndAction {
                sidebarContainer()?.visibility = View.GONE
                after?.invoke()
            }
            .start()
    }

    /**
     * 边缘手势：左边缘右滑打开；**已打开时右→左滑关闭**。
     *
     * 关键修正：
     *  · 之前只在「未打开」时才记 edgeTracking，打开后完全无法施手势关闭
     *  · 之前松手判定用 v.width/3，全宽状态下要滑 1/3 屏才生效，
     *    所以「一直往右拉、又收回去」——阈值太苛刻
     *  · 现在改用「移动距离 > 屏宽 8%」判定，短滑也能生效
     */
    private fun handleEdgeSwipe(ev: android.view.MotionEvent): Boolean {
        val v = sidebarView() ?: return false
        val sc = sidebarContainer() ?: return false

        // 侧边栏只在「主页」响应边缘手势。
        // 在「地图」「其他」页滑不出来 —— 那两页以后会有自己的交互（例如地图拖动），
        // 从左边缘起手很容易和它们打架。已在打开状态时仍允许关闭，避免卡死。
        val onHome = bottomNav?.current?.let { it == BottomNavController.Page.HOME } ?: true
        val isOpen = drawerOpen && sc.isVisible
        if (!onHome && !isOpen) return false
        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels.toFloat()
        val edgePx = edgeTriggerWidthDp * density
        val threshold = screenW * 0.08f          // 8% 屏宽即生效，比 1/3 宽松得多

        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                val opened = drawerOpen && sc.isVisible
                edgeTracking = if (!opened) {
                    // 未打开：必须从最左侧边缘开始
                    ev.rawX <= edgePx
                } else {
                    // 已打开：整屏任意位置按下都算（用于右→左关闭）
                    true
                }
                edgeStartedOpen = opened
                edgeDownX = ev.rawX
                edgeDragging = false
                return false
            }

            android.view.MotionEvent.ACTION_MOVE -> {
                if (!edgeTracking) return false
                val dx = ev.rawX - edgeDownX
                val moved = kotlin.math.abs(dx)

                if (!edgeDragging && moved > 8f * density) {
                    edgeDragging = true
                }
                if (!edgeDragging) return false

                if (!edgeStartedOpen) {
                    // 打开方向：从左边缘往右拖
                    if (dx <= 0) return true
                    if (sc.visibility != View.VISIBLE) {
                        sc.visibility = View.VISIBLE
                        sc.bringToFront()
                        applySidebarSize()
                    }
                    val closedX = sidebarClosedX()
                    v.translationX = (closedX + dx).coerceIn(closedX, 0f)
                } else {
                    // 关闭方向：往左拖，侧边栏跟着走
                    if (dx >= 0) return true
                    v.translationX = dx.coerceIn(sidebarClosedX(), 0f)
                }
                return true
            }

            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                if (!edgeTracking) return false
                val was = edgeDragging
                edgeTracking = false
                edgeDragging = false
                if (!was) return false

                val dx = ev.rawX - edgeDownX
                if (!edgeStartedOpen) {
                    // 右滑够远 -> 打开；否则收回
                    if (dx > threshold) openSidebarWithAnimation(animate = false)
                    else closeSidebarWithAnimation()
                } else {
                    // 左滑够远 -> 关闭；否则弹回打开
                    if (-dx > threshold) closeSidebarWithAnimation()
                    else openSidebarWithAnimation(animate = false)
                }
                return true
            }
        }
        return false
    }

    /**
     * ⚠️ 必须在这一层拦截：边缘手势要能抢在列表/滚动视图之前拿到 ACTION_DOWN，
     *    挂到任何子 View 上都会被上层内容挡住（这就是之前「滑不动」的原因）。
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (handleEdgeSwipe(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val sc = findViewById<View?>(R.id.custom_sidebar_container)
        if (sc != null && sc.isVisible) { closeSidebarWithAnimation(); return }
        if (editorVM.goBack()) {
            refreshAdapter(editorVM.nbtData.value)
            editorVM.goBackScrollPosition()?.let { nbtListView?.post { nbtListView?.setSelection(it) } }
            return
        }
        if (isFullScreen) { toggleFullScreen(false); return }
        // 不在「主页」时，返回键先退回主页（符合三键导航的直觉）
        if (bottomNav?.handleBack() == true) return
        super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isEditingPlayer", editorVM.isEditingPlayer.value)
        outState.putString("currentTargetKey", editorVM.currentTargetKey.value)
        outState.putString("currentWorkingDbPath", worldVM.currentWorkingDbPath)
        outState.putString("currentWorkingFileOrDir", worldVM.currentWorkingFileOrDir)
        outState.putBoolean("isTreeMode", editorVM.isTreeMode.value)
        etWorldName?.text?.toString()?.let { outState.putString("worldName", it) }
        bottomNav?.saveState(outState)
    }

    private fun restoreSavedState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        editorVM.setEditingPlayer(savedInstanceState.getBoolean("isEditingPlayer"))
        editorVM.setTargetKey(savedInstanceState.getString("currentTargetKey"))
        worldVM.currentWorkingDbPath = savedInstanceState.getString("currentWorkingDbPath")
        worldVM.currentWorkingFileOrDir = savedInstanceState.getString("currentWorkingFileOrDir")
        val tm = savedInstanceState.getBoolean("isTreeMode")
        if (tm != editorVM.isTreeMode.value) toggleTreeMode()
        savedInstanceState.getString("worldName")?.let {
            etWorldName?.setText(it); worldVM.lastLoadedWorldFolder = it
        }
        bottomNav?.restoreState(savedInstanceState)
    }

    // ============================================
    // 世界选择 / 路径（轻量包装）
    // ============================================
    private fun showWorldSelector() {
        // issue #6：只有受限路径（Android/data 等）才需要 Shizuku；
        // 公共目录（Download、games/...）用 File API 即可，不打扰用户。
        val path = worldVM.currentPath.value
        val needsShizuku = worldVM.isRestrictedPath(path)
        // 只有「确实需要提权」且「当前不是正常模式」时才提示
        if (needsShizuku && PrivilegedExec.canAttemptPrivileged()) {
            // 提示文案要跟着当前模式走：ROOT 模式下不该再提 Shizuku，
            // 否则用户明明有 root 却被告知「Shizuku 未运行」，非常困惑。
            when (val status = worldVM.checkShizukuStatus(applicationContext)) {
                is WorldViewModel.ShizukuStatus.Available -> {
                    // 继续
                }
                is WorldViewModel.ShizukuStatus.NotInstalled -> {
                    if (PrivilegedExec.mode() == PrivilegedExec.Mode.ROOT) {
                        toast(getString(R.string.toast_root_unavailable))
                    } else {
                        showShizukuNotInstalledDialog()
                        return
                    }
                }
                is WorldViewModel.ShizukuStatus.NotRunning -> {
                    // 非阻断：提示后可继续（可能靠 MANAGE_EXTERNAL_STORAGE 直接访问）
                    if (PrivilegedExec.mode() == PrivilegedExec.Mode.ROOT) {
                        toast(getString(R.string.toast_root_unavailable))
                    } else {
                        toast(getString(R.string.toast_shizuku_not_running))
                    }
                }
                is WorldViewModel.ShizukuStatus.PermissionDenied -> {
                    worldVM.requestShizukuPermission()
                    toast(getString(R.string.toast_shizuku_perm_failed))
                }
                else -> {
                    // 继续
                }
            }
        }


        pendingWorldSelector = true
        if (worldVM.safTreeUri != null) {
            worldVM.scanWorldsViaSaf(this, worldVM.safTreeUri!!)
        } else {
            worldVM.scanWorlds(this)
        }
    }

    private fun showShizukuNotInstalledDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_shizuku_not_found))
            .setMessage(getString(R.string.msg_shizuku_recommend_install))
            .setPositiveButton(getString(R.string.btn_download)) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://github.com/RikkaApps/Shizuku/releases")
                    })
                } catch (_: Exception) {
                    toast(getString(R.string.toast_open_browser_failed))
                }
            }
            .setNegativeButton(getString(R.string.btn_continue_without)) { _, _ ->
                worldVM.setUseShizuku(false)
                prefs?.edit()?.putBoolean("use_shizuku", false)?.apply()
                // 允许继续扫描（走 File API）
                pendingWorldSelector = true
                worldVM.scanWorlds(this)
            }
            // issue #6：非阻断，用户可稍后再处理
            .setCancelable(true)
            .show()
    }

    private fun showPathSelector() {
        // 「软件内部」是 Minecraft 自己的 /data/user/0/... 私有目录，
        // 只有真实 root 才进得去 —— Shizuku 的 shell 用户没有 /data 权限。
        // 所以按实测结果决定是否展示这一项，而不是按用户选的模式。
        val rootAvailable = PrivilegedExec.detectRoot(this)

        val labels = mutableListOf(
            getString(R.string.path_standard),
            getString(R.string.path_legacy),
            getString(R.string.path_custom),
            getString(R.string.path_saf)
        )
        if (rootAvailable) labels.add(getString(R.string.path_internal))

        AlertDialog.Builder(this).setTitle(getString(R.string.dialog_path_title))
            .setItems(labels.toTypedArray()) { _, w ->
                when (w) {
                    0 -> {
                    worldVM.safTreeUri = null
                    prefs?.edit()?.remove("saf_tree_uri")?.apply()
                    applyPath(PATH_STANDARD)
                    showWorldSelector()
                }
                    1 -> {
                    worldVM.safTreeUri = null
                    prefs?.edit()?.remove("saf_tree_uri")?.apply()
                    applyPath(PATH_LEGACY)
                    showWorldSelector()
                }
                    2 -> showCustomPathDialog()
                    3 -> safPathLauncher.launch(null)
                    4 -> {
                    worldVM.safTreeUri = null
                    prefs?.edit()?.remove("saf_tree_uri")?.apply()
                    // 软件内部只能用 root 读写；顺手把运行模式切到 ROOT，
                    // 否则保存时又会去找 Shizuku，白白失败一次。
                    PrivilegedExec.setMode(PrivilegedExec.Mode.ROOT)
                    worldVM.setUseShizuku(true)
                    applyPath(PATH_INTERNAL)
                    showWorldSelector()
                }
                }
            }.setNegativeButton(getString(R.string.btn_cancel), null).show()
    }


    private fun showCustomPathDialog() {
        val input = EditText(this).apply { setText(worldVM.currentPath.value) }
        AlertDialog.Builder(this).setTitle(getString(R.string.path_custom)).setView(input)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                var p = input.text.toString().trim()
                if (p.isNotEmpty()) {
                    worldVM.safTreeUri = null
                    prefs?.edit()?.remove("saf_tree_uri")?.apply()
                    applyPath(if (p.endsWith("/")) p else "$p/")
                    showWorldSelector()
                }
            }
            // 取消 / 点空白：退回上一层（路径选择），而不是什么都不发生
            .setNegativeButton(getString(R.string.btn_cancel)) { _, _ -> showPathSelector() }
            .show()
    }

    private fun showWorldSelectorDialog(list: List<WorldItem>) {
        if (list.isEmpty()) { toast(getString(R.string.toast_no_saves_found)); return }
        val trashCount = TrashStore.count(this)
        showTabbedListDialog(
            title = getString(R.string.select_world_count, list.size),
            items = list,
            // 存档列表不做类型分类：全部归到「所有」格，因此标签栏只有 所有 / ⋮ / 关闭
            tabOf = { null },
            linesOf = { w ->
                val line1 = listTitleLine(w.displayName, w.folderName)
                val line2 = if (w.lastModified > 0) fullTimeOf(w.lastModified)
                            else getString(R.string.time_unknown)
                "$line1\n$line2"
            },
            onShortClick = { w ->
                etWorldName?.setText(w.folderName)
                worldVM.openLevelDat(this, w.folderName)
            },
            onLongClick = { showWorldManageMenu(it) },
            menuItems = listOf(
                getString(R.string.title_trash, trashCount) to { showTrashDialog() }
            ),
            // 每项左侧显示 world_icon.jpeg（游戏退出时的最后一帧），
            // 同名存档也能一眼分辨。
            customItemView = { _, w, convertView ->
                val v = convertView ?: layoutInflater.inflate(R.layout.item_world_list, null)
                val iv = v.findViewById<android.widget.ImageView?>(R.id.iv_world_icon)
                val tvNone = v.findViewById<android.widget.TextView?>(R.id.tv_world_icon_none)
                val tvTitle = v.findViewById<android.widget.TextView?>(R.id.tv_world_title)
                val tvFolder = v.findViewById<android.widget.TextView?>(R.id.tv_world_folder)
                val tvTime = v.findViewById<android.widget.TextView?>(R.id.tv_world_time)

                // 三行分开显示，各占一行、互不挤压：
                //   ① 世界名（长了换行，最多 3 行）
                //   ② 文件夹 ID（一行，中间省略）
                //   ③ 最后游玩时间（一行）—— 之前被挤掉的就是它
                tvTitle?.text = w.displayName?.takeIf { it.isNotBlank() } ?: w.folderName
                tvFolder?.text = w.folderName
                tvFolder?.visibility = if (w.displayName.isNullOrBlank() || w.displayName == w.folderName)
                    android.view.View.GONE else android.view.View.VISIBLE
                tvTime?.text = if (w.lastModified > 0) fullTimeOf(w.lastModified)
                               else getString(R.string.time_unknown)

                // 缩略图：后台解码 + 内存缓存
                val path = w.iconPath
                iv?.tag = path
                if (path == null) {
                    iv?.setImageDrawable(null)
                    iv?.visibility = android.view.View.GONE
                    tvNone?.visibility = android.view.View.VISIBLE
                } else {
                    iv?.visibility = android.view.View.VISIBLE
                    tvNone?.visibility = android.view.View.GONE
                    val cached = worldIconCache[path]
                    if (cached != null) {
                        iv?.setImageBitmap(cached)
                    } else {
                        iv?.setImageDrawable(null)
                        Thread {
                            val bmp = decodeWorldIcon(path)
                            runOnUiThread {
                                // ⚠️ 这里不能用 iv?.tag，视图已确认存在；
                                //    用可空比较会让条件恒成立，导致永远不贴图。
                                if (iv == null || iv.tag != path) return@runOnUiThread
                                if (bmp != null) {
                                    worldIconCache[path] = bmp
                                    iv.setImageBitmap(bmp)
                                } else {
                                    iv.visibility = android.view.View.GONE
                                    tvNone?.visibility = android.view.View.VISIBLE
                                }
                            }
                        }.start()
                    }
                }
                v
            }
        )
    }

    /** 存档缩略图内存缓存（路径 -> 位图），避免滚动时反复解码 */
    private val worldIconCache = HashMap<String, android.graphics.Bitmap>()

    /** 解码 world_icon.jpeg 并按列表项尺寸降采样，防止大图 OOM */
    /**
     * 解码 world_icon.jpeg。
     *
     * 标准路径在 Android/data 下，Android 11+ 直读会被分区存储挡住，
     * 直接 BitmapFactory.decodeFile 会返回 null（这就是「图片加载不出来」的原因）。
     * 所以：先试直读，失败再用 Shizuku 把文件 cp 到私有缓存目录后解码。
     */
    /**
     * 解码 world_icon.jpeg。
     *
     * ⚠️ 三个坑都踩过：
     *  1. 标准路径在 Android/data 下，Android 11+ 的分区存储让
     *     File.exists() / BitmapFactory.decodeFile() 直接失败（图片一直空白）
     *  2. 用 Shizuku `cp` 到应用私有 cache **也不行** —— shell(uid 2000)
     *     没有写 /data/user/0/<pkg>/ 的权限，cp 必然失败
     *  3. 所以正确做法是让 Shizuku 直接 `cat` 出字节流，在内存里解码，
     *     全程不落盘、不依赖任何写权限。
     */
    private fun decodeWorldIcon(path: String): android.graphics.Bitmap? {
        // ① 能直读（旧版路径 / 已授予所有文件访问权限）
        decodeBitmapFromFile(path)?.let { return it }

        // ② 直读失败 -> 用 Shizuku cat 到内存
        if (!worldVM.checkShizukuAvailable()) return null
        return try {
            val p = worldVM.runShizukuCmd(arrayOf("sh", "-c", "cat '${path}'"))
            val bytes = p.inputStream.use { it.readBytes() }
            p.waitFor()
            if (bytes.isEmpty()) return null
            decodeBitmapFromBytes(bytes)
        } catch (_: Throwable) { null }
    }

    /** 内存里解码并降采样（不落盘） */
    private fun decodeBitmapFromBytes(bytes: ByteArray): android.graphics.Bitmap? = try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val target = 112  // 56dp @2x
        var scale = 1
        while (bounds.outWidth / scale > target * 2 || bounds.outHeight / scale > target * 2) scale *= 2
        android.graphics.BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = scale }
        )
    } catch (_: Throwable) { null }

    /** 从本地可读文件解码并降采样 */
    private fun decodeBitmapFromFile(path: String): android.graphics.Bitmap? = try {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        val target = 112  // 56dp @2x
        var scale = 1
        while (opts.outWidth / scale > target * 2 || opts.outHeight / scale > target * 2) scale *= 2
        android.graphics.BitmapFactory.decodeFile(
            path, android.graphics.BitmapFactory.Options().apply { inSampleSize = scale }
        )
    } catch (_: Throwable) { null }

    // ============================================
    // 存档管理（长按）
    // ============================================

    /** 存档长按菜单：详情 / 导出 / 删除 */
    private fun showWorldManageMenu(w: WorldItem) {
        val opts = arrayOf(
            getString(R.string.btn_detail),
            getString(R.string.btn_export_mcworld),
            getString(R.string.btn_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_manage, w.displayName))
            .setItems(opts) { _, i ->
                when (i) {
                    0 -> showWorldDetailDialog(w)
                    1 -> exportWorldAsMcWorld(w)
                    2 -> startDeleteWorldFlow(w)
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    /**
     * 导出整个世界为 .mcworld。
     * 先试默认目录（下载目录下的 Worlds/），不可写则引导用户自选目录（SAF）。
     */
    private fun exportWorldAsMcWorld(w: WorldItem) {
        val srcDir = File(worldVM.resolveWorldPathPublic(w.folderName))
        // /data 路径只有 root 看得见，File.exists() 恒为 false —— 要允许提权探测
        val exists = srcDir.exists() || worldVM.dirExists(srcDir.absolutePath)
        if (!exists) { toast(getString(R.string.msg_world_dir_not_found)); return }
        // 世界名读不到时（levelname.txt 缺失/无权限）回退用文件夹名，
        // 避免导出成 "Unknown.mcworld"
        val nameHint = w.displayName.takeIf { it.isNotBlank() && it != "Unknown" }
            ?: w.folderName
        val fileName = WorldExporter.buildFileName(nameHint)
        val destDir = WorldExporter.defaultWorldDir()

        if (!worldVM.canWriteDirectly(destDir)) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.btn_export_mcworld))
                .setMessage(getString(R.string.msg_export_need_permission))
                .setPositiveButton(getString(R.string.export_dest_choose)) { _, _ ->
                    exportWorldViaSaf(srcDir, fileName)
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
            return
        }

        showProgressDialog(getString(R.string.btn_export_mcworld), getString(R.string.label_calculating))
        Thread {
            val out = WorldExporter.packWorld(srcDir, destDir, fileName) { done, total ->
                val pct = (done * 100 / total).toInt()
                runOnUiThread { updateProgress(getString(R.string.export_progress, pct)) }
            }
            runOnUiThread {
                dismissProgressDialog()
                if (out != null) toast(getString(R.string.toast_exported_to, out.absolutePath))
                else toast(getString(R.string.toast_export_failed))
            }
        }.start()
    }

    /** 无权限时走 SAF：先打包到缓存，再让用户选目录复制过去 */
    private fun exportWorldViaSaf(srcDir: File, fileName: String) {
        showProgressDialog(getString(R.string.btn_export_mcworld), getString(R.string.label_calculating))
        Thread {
            val tmpDir = File(cacheDir, "mcworld_tmp").apply { mkdirs() }
            tmpDir.listFiles()?.forEach { it.delete() }
            val packed = WorldExporter.packWorld(srcDir, tmpDir, fileName) { done, total ->
                val pct = (done * 100 / total).toInt()
                runOnUiThread { updateProgress(getString(R.string.export_progress, pct)) }
            }
            runOnUiThread {
                dismissProgressDialog()
                if (packed == null) { toast(getString(R.string.toast_export_failed)); return@runOnUiThread }
                pendingExportFilePath = packed.absolutePath
                pendingExportName = fileName
                exportDirLauncher.launch(null)
            }
        }.start()
    }

    /** 已打包好的待导出文件（SAF 选完目录后复制） */
    private var pendingExportFilePath: String? = null

    /** 存档详情：世界名 / 文件夹 / 路径 / 时间 / 体积 */
    private fun showWorldDetailDialog(w: WorldItem) {
        val sb = StringBuilder()
        fun row(label: String, value: String) { sb.append(label).append(value).append('\n') }
        val path = worldVM.resolveWorldPathPublic(w.folderName)
        row(getString(R.string.label_world), w.displayName)
        row(getString(R.string.label_folder), w.folderName)
        row(getString(R.string.label_time),
            if (w.lastModified > 0) fullTimeOf(w.lastModified) else getString(R.string.time_unknown))
        row(getString(R.string.label_size), getString(R.string.label_calculating))
        row(getString(R.string.label_path), '\n' + path)

        val tv = TextView(this).apply {
            text = sb.toString().trimEnd()
            setPadding(48, 32, 48, 32)
            setTextIsSelectable(true)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_world_detail))
            .setView(tv)
            .setPositiveButton(getString(R.string.btn_close), null)
            .create()
        dlg.show()

        Thread {
            val bytes = TrashStore.sizeOf(File(path))
            runOnUiThread {
                tv.text = tv.text.toString().replace(
                    getString(R.string.label_size) + getString(R.string.label_calculating),
                    getString(R.string.label_size) + formatSize(bytes)
                )
            }
        }.start()
    }

    // ============================================
    // 删除存档（两次确认 + 询问关联数据）
    // ============================================

    private fun startDeleteWorldFlow(w: WorldItem) {
        // 第 1 次确认
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_delete_world))
            .setMessage(getString(R.string.msg_delete_world_confirm1, w.displayName))
            .setPositiveButton(getString(R.string.btn_continue)) { _, _ -> confirmDeleteWorld2(w) }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun confirmDeleteWorld2(w: WorldItem) {
        // 第 2 次确认
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_danger_title))
            .setMessage(getString(R.string.msg_delete_world_confirm2, w.displayName))
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ -> askAlsoDeleteRelated(w) }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /** 询问是否连带删除该存档的备份与草稿 */
    private fun askAlsoDeleteRelated(w: WorldItem) {
        Thread {
            val backups = runCatching {
                File(worldVM.backupsDir(this), w.folderName).listFiles()?.size ?: 0
            }.getOrDefault(0)
            val drafts = runCatching {
                DraftStore.list(this).count { it.folder == w.folderName }
            }.getOrDefault(0)
            runOnUiThread { showAlsoDeleteDialog(w, backups, drafts) }
        }.start()
    }

    private fun showAlsoDeleteDialog(w: WorldItem, backups: Int, drafts: Int) {
        if (backups == 0 && drafts == 0) {
            doDeleteWorld(w, alsoBackups = false, alsoDrafts = false); return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_also_delete))
            .setMessage(getString(R.string.msg_also_delete, backups, drafts))
            .setPositiveButton(getString(R.string.btn_yes_delete_all)) { _, _ ->
                doDeleteWorld(w, alsoBackups = backups > 0, alsoDrafts = drafts > 0)
            }
            .setNegativeButton(getString(R.string.btn_keep_them)) { _, _ ->
                doDeleteWorld(w, alsoBackups = false, alsoDrafts = false)
            }
            .show()
    }

    private fun doDeleteWorld(w: WorldItem, alsoBackups: Boolean, alsoDrafts: Boolean) {
        showProgressDialog(getString(R.string.status_deleting), getString(R.string.msg_processing_ing))
        Thread {
            val (ok, msg) = worldVM.moveWorldToTrash(
                this, w.folderName, w.displayName, alsoBackups, alsoDrafts
            )
            runOnUiThread {
                dismissProgressDialog()
                if (ok) toast(getString(R.string.toast_world_deleted))
                else toast(msg ?: getString(R.string.label_sub_delete_failed))
                // 清空存档名（若删的正是当前选中的）
                if (etWorldName?.text?.toString() == w.folderName) etWorldName?.setText("")
                if (ok) {
                    // 重新扫描并刷新世界列表，让用户立刻看到结果
                    pendingWorldSelector = true
                    worldVM.scanWorlds(this)
                }
            }
        }.start()
    }

    // ============================================
    // 回收站
    // ============================================

    private fun showTrashDialog() {
        val items = TrashStore.list(this)
        if (items.isEmpty()) { toast(getString(R.string.label_sub_trash_empty)); return }
        val view = layoutInflater.inflate(R.layout.dialog_list_with_tabs, null, false)
        val lv = view.findViewById<ListView>(R.id.lv_tabbed_items)
        val bar = view.findViewById<LinearLayout>(R.id.layout_tab_bar)

        lv.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            items.map { it ->
                val line1 = listTitleLine(it.worldName, it.originalFolder)
                val extra = buildString {
                    append(getString(R.string.label_size)); append(formatSize(it.sizeBytes))
                    if (it.hasBackups) { append("  · "); append(getString(R.string.label_includes_backups)) }
                    if (it.hasDrafts) { append("  · "); append(getString(R.string.label_includes_drafts)) }
                }
                "$line1\n${it.timeText()}\n$extra"
            })

        val dlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_trash, items.size))
            .setView(view)
            .create()

        lv.setOnItemClickListener { _, _, p, _ -> restoreTrashItem(items[p], dlg) }
        lv.setOnItemLongClickListener { _, _, p, _ -> showTrashItemMenu(items[p], dlg); true }

        // 底部：全部恢复 / 清空回收站 / 关闭
        val density = resources.displayMetrics.density
        val vpad = (12 * density).toInt()
        // 强调色 / 危险色走色槽，跟随主题（2026-11-02）
        val accentHex = ColorScheme.toHex(
            ColorScheme.value(prefs!!, slotOf("c_accent"), ColorScheme.isNight(this))
        )
        val dangerHex = ColorScheme.toHex(
            ColorScheme.value(prefs!!, slotOf("c_danger"), ColorScheme.isNight(this))
        )
        fun tabBtn(text: String, weight: Float, color: String, onClick: () -> Unit) =
            TextView(this).apply {
                this.text = text
                gravity = android.view.Gravity.CENTER
                setPadding(0, vpad, 0, vpad)
                textSize = 13f
                setTextColor(color.toColorInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
                isClickable = true
                setOnClickListener { onClick() }
            }
        bar.addView(tabBtn(getString(R.string.btn_restore_all), 1f, accentHex) {
            dlg.dismiss(); confirmRestoreAll(items)
        })
        bar.addView(tabBtn(getString(R.string.btn_clear_trash), 1f, dangerHex) {
            dlg.dismiss(); confirmClearTrash()
        })
        bar.addView(tabBtn(getString(R.string.btn_close), 0.8f, accentHex) { dlg.dismiss() })

        dlg.setOnDismissListener { currentDialog = null }
        currentDialog = dlg
        dlg.show()
        val winW = (resources.displayMetrics.widthPixels * 0.9).toInt()
        val winH = (resources.displayMetrics.heightPixels * 0.65).toInt()
        view.minimumHeight = winH
        dlg.window?.setLayout(winW, winH)
    }

    private fun showTrashItemMenu(item: TrashStore.TrashItem, dlg: AlertDialog) {
        val detail = buildString {
            append(getString(R.string.label_world)).append(item.label).append('\n')
            append(getString(R.string.label_original_folder)).append(item.originalFolder).append('\n')
            append(getString(R.string.label_deleted_at)).append(item.timeText()).append('\n')
            append(getString(R.string.label_size)).append(formatSize(item.sizeBytes)).append('\n')
            append(getString(R.string.label_includes))
            val inc = mutableListOf<String>()
            if (item.hasBackups) inc.add(getString(R.string.label_includes_backups))
            if (item.hasDrafts) inc.add(getString(R.string.label_includes_drafts))
            append(if (inc.isEmpty()) getString(R.string.label_sub_none) else inc.joinToString("、"))
        }
        val opts = arrayOf(
            getString(R.string.btn_restore_action),
            getString(R.string.btn_detail),
            getString(R.string.btn_delete_forever)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_manage, item.label))
            .setItems(opts) { _, w ->
                when (w) {
                    0 -> { dlg.dismiss(); restoreTrashItem(item, null) }
                    1 -> AlertDialog.Builder(this)
                        .setTitle(getString(R.string.title_trash_detail))
                        .setMessage(detail)
                        .setPositiveButton(getString(R.string.btn_close), null)
                        .setNegativeButton(getString(R.string.btn_cancel), null).show()
                    2 -> confirmDeleteForever(item, dlg)
                }
            }
            .show()
    }

    private fun restoreTrashItem(item: TrashStore.TrashItem, dlg: AlertDialog?) {
        showProgressDialog(getString(R.string.status_restoring), getString(R.string.msg_processing_ing))
        Thread {
            val (ok, msg) = worldVM.restoreFromTrash(this, item, overwrite = false)
            runOnUiThread {
                dismissProgressDialog()
                when {
                    ok -> {
                        toast(getString(R.string.toast_world_restored))
                        dlg?.dismiss()
                        // 立刻回到世界列表，能看到刚恢复的存档
                        pendingWorldSelector = true
                        worldVM.scanWorlds(this)
                    }
                    // 目标已存在：问是否覆盖
                    msg == getString(R.string.msg_restore_target_exists) -> askOverwriteRestore(item, dlg)
                    else -> toast(msg ?: getString(R.string.msg_restore_failed))
                }
            }
        }.start()
    }

    private fun askOverwriteRestore(item: TrashStore.TrashItem, dlg: AlertDialog?) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_danger_title))
            .setMessage(getString(R.string.msg_restore_overwrite_confirm, item.originalFolder))
            .setPositiveButton(getString(R.string.btn_cover)) { _, _ ->
                showProgressDialog(getString(R.string.status_restoring), getString(R.string.msg_processing_ing))
                Thread {
                    val (ok, msg) = worldVM.restoreFromTrash(this, item, overwrite = true)
                    runOnUiThread {
                        dismissProgressDialog()
                        if (ok) {
                            toast(getString(R.string.toast_world_restored))
                            dlg?.dismiss()
                            pendingWorldSelector = true
                            worldVM.scanWorlds(this)
                        } else toast(msg ?: getString(R.string.msg_restore_failed))
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun confirmDeleteForever(item: TrashStore.TrashItem, dlg: AlertDialog) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_danger_title))
            .setMessage(getString(R.string.msg_delete_forever_confirm, item.label))
            .setPositiveButton(getString(R.string.btn_delete_forever)) { _, _ ->
                TrashStore.deleteForever(item)
                toast(getString(R.string.toast_trash_item_deleted))
                dlg.dismiss()
                showTrashDialog()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun confirmRestoreAll(items: List<TrashStore.TrashItem>) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.btn_restore_all))
            .setMessage(getString(R.string.msg_restore_all_confirm, items.size))
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                showProgressDialog(getString(R.string.status_restoring), getString(R.string.msg_processing_ing))
                Thread {
                    var okCount = 0
                    var failCount = 0
                    items.forEach { it2 ->
                        val (ok, _) = worldVM.restoreFromTrash(this, it2, overwrite = true)
                        if (ok) okCount++ else failCount++
                    }
                    runOnUiThread {
                        dismissProgressDialog()
                        toast(getString(R.string.toast_restore_all_result, okCount, failCount))
                        if (okCount > 0) {
                            pendingWorldSelector = true
                            worldVM.scanWorlds(this)
                        }
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun confirmClearTrash() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_danger_title))
            .setMessage(getString(R.string.msg_clear_trash_confirm))
            .setPositiveButton(getString(R.string.btn_clear)) { _, _ ->
                TrashStore.clearAll(this)
                toast(getString(R.string.toast_trash_cleared))
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun reparseLevelDat() {
        worldVM.currentWorkingFileOrDir?.let { path ->
            val json = BedrockParser.parse(path)
            editorVM.setRawNbtData(json); editorVM.nbtDataCache["level.dat"] = json
            editorVM.navigationStack.clear(); editorVM.pathStack.clear(); editorVM.scrollPositionStack.clear()
            editorVM.currentListData = json; editorVM.setEditingPlayer(false)
            editorVM.updatePathTitle(); worldVM.updateWorldInfoFromNbt(json)
        } ?: toast(getString(R.string.toast_no_data_to_parse))
    }

    // ============================================
    // 缓存 & 备份 & 错误（轻量包装）
    // ============================================
    private fun showCacheDialog(list: List<CacheItem>) {
        if (list.isEmpty()) { toast(getString(R.string.toast_cache_empty)); return }
        val lv = ListView(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1,
                list.map { (if (it.isDirectory) "[D] " else "[F] ") + it.name })
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.title_manage_cache)).setView(lv)
            .setNeutralButton(getString(R.string.btn_close), null)
            .setPositiveButton(getString(R.string.btn_clear_all)) { _, _ -> worldVM.clearAllCache(this); toast(getString(R.string.toast_cleared)) }.show()
        lv.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, p, _ ->
            AlertDialog.Builder(this).setTitle(getString(R.string.btn_delete)).setMessage(getString(R.string.msg_delete_confirm, list[p].name))
                .setPositiveButton(getString(R.string.btn_delete)) { _, _ -> worldVM.deleteCacheItem(list[p]); worldVM.scanCache(this) }.show()
            true
        }
    }

    private fun showBackupDialog(list: List<BackupItem>) {
        if (list.isEmpty()) { toast(getString(R.string.toast_no_backups)); return }
        val worldName = worldVM.backupWorldName.value
        val folder = backupListFolder
        val folderId = folder ?: ""

        // ⋮ 菜单：清空本存档的备份（备份只有「按存档」这一个维度，没有全部）
        val clearHere: (() -> Unit)? =
            if (folder.isNullOrBlank()) null else { { confirmClearBackupsHere(folder, list.size) } }

        showTabbedListDialog(
            title = getString(R.string.dialog_backup_title, list.size),
            items = list,
            tabOf = { backupTabOf(it) },
            linesOf = { b ->
                val line1 = listTitleLine(worldName, folderId)
                val type = if (b.isPlayerData) getString(R.string.label_sub_local_player)
                           else getString(R.string.label_sub_world_data)
                "$line1\n" + listSubLine(type, b.time, agoTextOfMillis(b.timestamp))
            },
            onShortClick = { worldVM.restoreBackup(this, it) },
            onLongClick = { showBackupManageMenu(it) },
            menuItems = listOf(
                getString(R.string.btn_clear_backups_here, list.size) to clearHere
            )
        )
    }

    /** 清空本存档的全部备份（二次确认） */
    private fun confirmClearBackupsHere(folder: String, count: Int) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_danger_title))
            .setMessage(getString(R.string.msg_clear_backups_here_confirm, count))
            .setPositiveButton(getString(R.string.btn_clear)) { _, _ ->
                val dir = File(worldVM.backupsDir(this), folder)
                dir.listFiles()?.forEach { worldVM.deleteBackup(BackupItem(it, it.name, "", it.isDirectory)) }
                toast(getString(R.string.toast_backups_cleared))
                currentDialog?.dismiss()
                refreshBackupList()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // ============================================
    // 备份管理（长按）
    // ============================================

    private fun showBackupManageMenu(item: BackupItem) {
        val opts = arrayOf(
            getString(R.string.btn_restore_action),
            getString(R.string.btn_detail),
            getString(R.string.btn_rename),
            getString(R.string.btn_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_manage, item.name))
            .setItems(opts) { _, w ->
                when (w) {
                    0 -> worldVM.restoreBackup(this, item)
                    1 -> showBackupDetailDialog(item)
                    2 -> showBackupRenameDialog(item)
                    3 -> confirmDeleteBackup(item)
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    /** 重命名备份（复用 WorldViewModel.renameBackup） */
    private fun showBackupRenameDialog(item: BackupItem) {
        val input = EditText(this).apply {
            setText(item.name)
            setSelection(item.name.length)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_rename))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                val newName = input.text.toString().trim()
                when {
                    newName.isEmpty() ->
                        toast(getString(R.string.label_sub_name_cannot_be_empty))
                    newName.contains('/') || newName.contains('\\') ->
                        toast(getString(R.string.toast_Invalid_name))
                    newName == item.name -> { /* 没改动，忽略 */ }
                    File(item.file.parentFile, newName).exists() ->
                        toast(getString(R.string.msg_backup_name_exists))
                    worldVM.renameBackup(item, newName) -> {
                        toast(getString(R.string.toast_backup_renamed))
                        currentDialog?.dismiss(); refreshBackupList()
                    }
                    else ->
                        toast(getString(R.string.toast_rename_failed, item.name))
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /** 删除备份（复用 WorldViewModel.deleteBackup） */
    private fun confirmDeleteBackup(item: BackupItem) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_danger_title))
            .setMessage(getString(R.string.msg_delete_backup_confirm, item.name))
            .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                worldVM.deleteBackup(item)
                toast(getString(R.string.toast_backup_deleted))
                currentDialog?.dismiss(); refreshBackupList()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /** 备份详情：类型 / 时间 / 大小 / 路径（目录大小在后台计算） */
    private fun showBackupDetailDialog(item: BackupItem) {
        val f = item.file
        val sb = StringBuilder()
        sb.append(getString(R.string.label_type))
            .append(if (item.isDirectory) getString(R.string.type_folder) else getString(R.string.type_file))
            .append('\n')
        sb.append(getString(R.string.label_time) + item.time).append('\n')
        sb.append(getString(R.string.label_size)).append(getString(R.string.label_calculating)).append('\n')
        sb.append(getString(R.string.label_path)).append('\n').append(f.absolutePath)

        val tv = TextView(this).apply {
            text = sb.toString()
            setPadding(48, 32, 48, 32)
            setTextIsSelectable(true)
        }
        val detailDlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_backup_detail))
            .setView(tv)
            .setPositiveButton(getString(R.string.btn_close), null)
            .create()
        detailDlg.show()

        // 大小可能很大，放后台算，算完再刷新这一行
        Thread {
            val bytes = sizeOf(f)
            runOnUiThread {
                tv.text = sb.toString().replace(
                    getString(R.string.label_size) + getString(R.string.label_calculating),
                    getString(R.string.label_size) + formatSize(bytes)
                )
            }
        }.start()
    }

    /** 递归求大小（文件直接取长度；目录累加） */
    private fun sizeOf(f: File): Long {
        if (f.isFile) return f.length()
        var total = 0L
        try {
            f.listFiles()?.forEach { total += sizeOf(it) }
        } catch (_: Exception) {
        }
        return total
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
        else -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }

    /** 重命名/删除后刷新列表（关掉旧弹窗，重新扫描会再次弹出） */
    private fun refreshBackupList() {
        val f = backupListFolder ?: return
        worldVM.scanBackups(this, f)
    }

    // ============================================
    // 草稿箱
    // ============================================

    // ============================================
    // 带底部标签的列表对话框（草稿箱 / 备份共用）
    // ============================================

    /** 列表项的分类格 */
    private enum class ListTab(val labelRes: Int) {
        ALL(R.string.tab_all),
        WORLD(R.string.tab_world),
        PLAYER(R.string.tab_player),
        OTHER(R.string.tab_other)
    }

    /**
     * 通用「带底部标签栏的列表」对话框。
     *
     * 标签**动态生成**：只显示实际有内容的分类，最后一格固定是「关闭」。
     *   所有 / 世界 / 玩家 / 其他 / 关闭
     *
     * @param tabOf   分类函数；返回 null 表示「未知」，统一归入「其他」格
     * @param linesOf 每项显示的两行文本
     */
    private fun <T> showTabbedListDialog(
        title: String,
        items: List<T>,
        tabOf: (T) -> ListTab?,
        linesOf: (T) -> String,
        onShortClick: (T) -> Unit,
        onLongClick: (T) -> Unit,
        /** 右上角 ⋮ 菜单项（空 = 不显示 ⋮）；lambda 为 null 表示不可用 */
        menuItems: List<Pair<String, (() -> Unit)?>> = emptyList(),
        /**
         * 可选的自定义列表项视图（返回 null 则用默认纯文本行）。
         * 世界列表用它显示 world_icon.jpeg 缩略图。
         */
        customItemView: ((position: Int, item: T, convertView: android.view.View?) -> android.view.View?)? = null
    ) {
        fun tabOfItem(item: T): ListTab = tabOf(item) ?: ListTab.OTHER

        val view = layoutInflater.inflate(R.layout.dialog_list_with_tabs, null, false)
        val lv = view.findViewById<ListView>(R.id.lv_tabbed_items)
        val tabBar = view.findViewById<LinearLayout>(R.id.layout_tab_bar)

        // 有内容的分类（顺序固定：所有 / 世界 / 玩家 / 其他）
        val present = items.map { tabOfItem(it) }.toSet()
        val tabs = listOf(ListTab.ALL, ListTab.WORLD, ListTab.PLAYER, ListTab.OTHER)
            .filter { it == ListTab.ALL || it in present }

        var shown: List<T> = items
        var selected: ListTab = ListTab.ALL

        val dlg = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .create()

        fun render() {
            shown = if (selected == ListTab.ALL) items
                    else items.filter { tabOfItem(it) == selected }
            lv.adapter = if (customItemView == null) {
                ArrayAdapter(
                    this, android.R.layout.simple_list_item_1, shown.map(linesOf)
                )
            } else {
                object : android.widget.BaseAdapter() {
                    override fun getCount() = shown.size
                    override fun getItem(position: Int) = shown[position]
                    override fun getItemId(position: Int) = position.toLong()
                    override fun getView(
                        position: Int, convertView: android.view.View?, parent: android.view.ViewGroup?
                    ): android.view.View {
                        val item = shown[position]
                        customItemView(position, item, convertView)
                            ?.let { return it }
                        // 兜底：自定义视图返回 null 时用纯文本
                        return android.widget.TextView(this@MainActivity).apply {
                            text = linesOf(item)
                            setPadding(32, 32, 32, 32)
                            textSize = 15f
                        }
                    }
                }
            }
            buildTabBar(tabBar, tabs, selected, menuItems) { sel ->
                selected = sel
                render()
            }
        }

        lv.setOnItemClickListener { _, _, p, _ ->
            shown.getOrNull(p)?.let(onShortClick)
        }
        lv.setOnItemLongClickListener { _, _, p, _ ->
            val it = shown.getOrNull(p)
            if (it != null) { onLongClick(it); true } else false
        }

        // 窗口尺寸：宽度 90%，高度固定为屏幕的 65%。
        // 之所以不按条数自适应：弹窗尺寸随内容忽大忽小不符合用户习惯。
        //
        // ⚠️ 关键：AlertDialog.setView() 会把自定义视图放进一个
        // wrap_content 的容器（customPanel）里，所以：
        //   - 只调 window.setLayout 改的是「窗口」尺寸，内容不会撑满
        //   - 根布局写 match_parent 也没用（它相对的是 wrap_content 的父容器）
        // 结果就是列表只占内容高度、标签栏紧跟其后，下方留一大块空白。
        // 解决办法：直接给视图本身设置最小高度，把它所在的容器撑开。
        val winW = (resources.displayMetrics.widthPixels * 0.9).toInt()
        val winH = (resources.displayMetrics.heightPixels * 0.65).toInt()

        dlg.setOnDismissListener { currentDialog = null }
        currentDialog = dlg
        dlg.show()
        view.minimumHeight = winH
        dlg.window?.setLayout(winW, winH)

        render()
    }

    /** 构建标签栏：若干分类格 + 末尾固定的「关闭」 */
    private fun buildTabBar(
        bar: LinearLayout,
        tabs: List<ListTab>,
        selected: ListTab,
        menuItems: List<Pair<String, (() -> Unit)?>>,
        onSelect: (ListTab) -> Unit
    ) {
        bar.removeAllViews()
        val density = resources.displayMetrics.density
        val vpad = (12 * density).toInt()
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        // 标签栏配色也走色槽，跟随主题（2026-11-02）
        val night = ColorScheme.isNight(this)
        val accentInt = ColorScheme.value(prefs!!, slotOf("c_accent"), night)
        val tabSelBg = ColorScheme.value(prefs!!, slotOf("c_tab_selected"), night)
        val tabUnselText = ColorScheme.value(prefs!!, slotOf("c_tab_unselected"), night)

        for (tab in tabs) {
            val isSel = tab == selected
            bar.addView(TextView(this).apply {
                text = getString(tab.labelRes)
                gravity = android.view.Gravity.CENTER
                setPadding(0, vpad, 0, vpad)
                textSize = 13f
                layoutParams = lp
                isClickable = true
                if (isSel) {
                    setTextColor(accentInt)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setBackgroundColor(tabSelBg)
                } else {
                    setTextColor(tabUnselText)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
                setOnClickListener { onSelect(tab) }
            })
        }
        // 倒数第二格：⋮ 菜单（有菜单项时才显示）
        if (menuItems.isNotEmpty()) {
            bar.addView(TextView(this).apply {
                text = "\u22ee"
                gravity = android.view.Gravity.CENTER
                setPadding(0, vpad, 0, vpad)
                textSize = 16f
                setTextColor(accentInt)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f
                )
                isClickable = true
                setOnClickListener {
                    val labels = menuItems.map { it.first }.toTypedArray()
                    val enabled = menuItems.map { it.second != null }.toBooleanArray()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.menu_more_actions_title)
                        .setItems(labels) { _, w ->
                            if (menuItems[w].second != null) menuItems[w].second!!.invoke()
                            else toast(getString(R.string.label_sub_action_unavailable))
                        }
                        .setNegativeButton(getString(R.string.btn_cancel), null).show()
                }
            })
        }

        // 末尾「关闭」
        bar.addView(TextView(this).apply {
            text = getString(R.string.btn_close)
            gravity = android.view.Gravity.CENTER
            setPadding(0, vpad, 0, vpad)
            textSize = 13f
            setTextColor(accentInt)
            layoutParams = lp
            isClickable = true
            setOnClickListener { currentDialog?.dismiss() }
        })
    }

    /** 取某个色槽的当前值（含主题 / 默认模式解析） */
    private fun slotOf(key: String): yhjmew.nibnya.util.ColorScheme.Slot {
        val ctx = applicationContext
        return yhjmew.nibnya.util.ColorSlots.build(ctx)
            .first { it.key == key }
    }

    /** 当前显示的带标签列表对话框（供标签栏的「关闭」使用） */
    private var currentDialog: AlertDialog? = null

    /** 草稿的分类格（null = 未知，归入「其他」） */
    private fun draftTabOf(d: DraftStore.Draft): ListTab? {
        // 存档信息缺失 -> 未知
        if (d.folder.isNullOrBlank()) return null
        val k = d.sessionKey
        return when {
            k == "level.dat" -> ListTab.WORLD
            k == "~local_player" || k.startsWith("player") -> ListTab.PLAYER
            k.startsWith("map_") || k.startsWith("VILLAGE_") -> ListTab.OTHER
            else -> null
        }
    }

    /** 备份的分类格（null = 未知，归入「其他」） */
    private fun backupTabOf(b: BackupItem): ListTab? {
        if (backupListFolder.isNullOrBlank()) return null
        return if (b.isPlayerData) ListTab.PLAYER else ListTab.WORLD
    }

    /** 完整时间：yyyy-MM-dd HH:mm:ss（年到秒）；0 显示「未知时间」 */
    private fun fullTimeOf(millis: Long): String =
        if (millis <= 0) getString(R.string.time_unknown)
        else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(millis))

    /** 列表第 1 行：世界名 + 空格 + 文件夹名(base64 ID) */
    private fun listTitleLine(worldName: String?, folderId: String): String {
        val name = worldName?.takeIf { it.isNotBlank() } ?: folderId
        return if (name == folderId) name else "$name  $folderId"
    }

    /** 列表第 2 行（草稿/备份）：[类型标签] 完整时间 相对时间 */
    private fun listSubLine(typeLabel: String, timeText: String, agoText: String): String =
        "[$typeLabel]  $timeText  $agoText"

    /** 从 timestamp 算出「多久之前」的文案 */
    private fun agoTextOfMillis(millis: Long): String {
        if (millis <= 0) return ""
        val minutes = (System.currentTimeMillis() - millis).coerceAtLeast(0) / 60000
        return agoTextOf(minutes)
    }

    /** 距今多久的本地化文案（避免在 util 层硬编码中文） */
    private fun agoTextOf(minutes: Long): String = when {
        minutes < 1 -> getString(R.string.draft_ago_just_now)
        minutes < 60 -> getString(R.string.draft_ago_minutes, minutes)
        minutes < 60 * 24 -> getString(R.string.draft_ago_hours, minutes / 60)
        else -> getString(R.string.draft_ago_days, minutes / (60 * 24))
    }

    /**
     * 草稿类型标签。
     * 注意 level.dat 是「世界数据」，不是「地图」—— msg_map 指的是 map_xxx 地图数据，
     * 之前这里一律用 msg_map，导致世界数据的草稿被标成 [地图]。
     */
    private fun draftTypeLabel(d: DraftStore.Draft): String {
        val key = d.sessionKey
        return when {
            key == "level.dat" -> getString(R.string.label_sub_world_data)
            key == "~local_player" -> getString(R.string.label_sub_local_player)
            key.startsWith("map_") -> getString(R.string.label_sub_map)
            key.startsWith("VILLAGE_") -> getString(R.string.label_sub_village)
            key.startsWith("player") -> getString(R.string.label_sub_player)
            d.isPlayerMode -> getString(R.string.label_sub_player)
            else -> getString(R.string.label_sub_world_data)
        }
    }

    /**
     * 草稿箱：列出未保存的编辑，由用户主动找回。
     * 与「备份」分离 —— 备份是已保存状态的快照，草稿是未保存的改动。
     */
    /**
     * 草稿箱。
     *
     * @param viewAll true = 忽略存档筛选，显示全部存档的草稿
     */
    private fun showDraftBoxDialog(viewAll: Boolean = false) {
        val folder = etWorldName?.text?.toString()?.trim().orEmpty()
        val hasFolder = folder.isNotEmpty() &&
            folder != getString(R.string.hint_select_world)

        // 未选存档：提示 + 提供「查看全部」兜底（草稿是救命用的，不能全堵死）
        if (!hasFolder && !viewAll) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_draft_box_no_folder))
                .setMessage(getString(R.string.msg_draft_select_world_first))
                .setPositiveButton(getString(R.string.btn_view_all_drafts)) { _, _ ->
                    showDraftBoxDialog(viewAll = true)
                }
                .setNegativeButton(getString(R.string.btn_close), null)
                .show()
            return
        }

        val all = DraftStore.list(this)
        // 选了存档 -> 只看该存档的草稿，但「未知存档」的草稿始终显示（避免找不回）
        val drafts = if (viewAll) all
                     else all.filter { it.folder.isNullOrBlank() || it.folder == folder }

        if (drafts.isEmpty()) { toast(getString(R.string.label_sub_no_drafts)); return }

        // 本存档的草稿数（用于「清空本存档」文案与是否可点）
        val hereCount = all.count { !it.folder.isNullOrBlank() && it.folder == folder }
        val clearHere: (() -> Unit)? =
            if (hereCount <= 0) null else { { confirmClearDraftsHere(folder, hereCount) } }

        showTabbedListDialog(
            title = getString(R.string.title_draft_box, drafts.size),
            items = drafts,
            tabOf = { draftTabOf(it) },
            linesOf = { d ->
                val line1 = listTitleLine(d.worldName, d.folderId())
                val type = draftTypeLabel(d)
                val orig = if (d.hasOriginal()) " · ${getString(R.string.draft_has_original)}" else ""
                val line2 = listSubLine(type, fullTimeOf(d.savedAt), agoTextOf(d.minutesAgo())) + orig
                "$line1\n$line2"
            },
            onShortClick = { worldVM.restoreDraft(this, it) },
            onLongClick = { showDraftManageMenu(it) },
            menuItems = listOf(
                getString(R.string.btn_clear_drafts_here, hereCount) to clearHere,
                getString(R.string.btn_clear_drafts) to { confirmClearAllDrafts() }
            )
        )
    }

    // ============================================
    // 草稿管理（长按）
    // ============================================

    /** 草稿长按菜单：恢复 / 详情 / 导出 / 删除 */
    private fun showDraftManageMenu(d: DraftStore.Draft) {
        val opts = arrayOf(
            getString(R.string.btn_restore_action),
            getString(R.string.btn_detail),
            getString(R.string.btn_export),
            getString(R.string.btn_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_manage, d.label()))
            .setItems(opts) { _, w ->
                when (w) {
                    0 -> worldVM.restoreDraft(this, d)
                    1 -> showDraftDetailDialog(d)
                    2 -> exportDraft(d)
                    3 -> confirmDeleteDraft(d)
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    /** 草稿详情：类型 / key / 所属存档 / 时间 / 顶层字段数 / 体积 / 是否含原始数据 */
    private fun showDraftDetailDialog(d: DraftStore.Draft) {
        val sb = StringBuilder()
        fun row(label: String, value: String) { sb.append(label).append(value).append('\n') }

        row(getString(R.string.label_type), draftTypeLabel(d))
        row(getString(R.string.label_key), d.sessionKey)
        row(getString(R.string.label_world),
            d.worldName?.takeIf { it.isNotBlank() } ?: getString(R.string.label_sub_unknown))
        row(getString(R.string.label_folder), d.folder ?: getString(R.string.label_sub_unknown))
        row(getString(R.string.label_time),
            fullTimeOf(d.savedAt) + "  (" + agoTextOf(d.minutesAgo()) + ")")
        row(getString(R.string.label_fields), d.data.size().toString())
        row(getString(R.string.label_size), formatSize(DraftStore.sizeOf(this, d.sessionKey)))
        row(getString(R.string.label_has_original),
            if (d.hasOriginal()) getString(R.string.yes) else getString(R.string.no))

        val tv = TextView(this).apply {
            text = sb.toString().trimEnd()
            setPadding(48, 32, 48, 32)
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_draft_detail))
            .setView(tv)
            .setPositiveButton(getString(R.string.btn_close), null)
            .show()
    }

    /** 导出草稿（复用 NbtExporter 的格式选择 + 目标选择流程） */
    private fun exportDraft(d: DraftStore.Draft) {
        showCopyExportDialog(d.data, d.label())
    }


    /** 删除单份草稿（二次确认） */
    private fun confirmDeleteDraft(d: DraftStore.Draft) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_delete_draft))
            .setMessage(getString(R.string.msg_delete_draft_confirm, d.label()))
            .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                DraftStore.remove(this, d.sessionKey)
                toast(getString(R.string.toast_draft_deleted))
                currentDialog?.dismiss()
                showDraftBoxDialog(false)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /** 清空当前存档的草稿 */
    private fun confirmClearDraftsHere(folder: String, count: Int) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_danger_title))
            .setMessage(getString(R.string.msg_clear_drafts_here_confirm, count))
            .setPositiveButton(getString(R.string.btn_clear)) { _, _ ->
                DraftStore.list(this)
                    .filter { !it.folder.isNullOrBlank() && it.folder == folder }
                    .forEach { DraftStore.remove(this, it.sessionKey) }
                toast(getString(R.string.toast_drafts_cleared))
                currentDialog?.dismiss()
                showDraftBoxDialog(false)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /** 清空全部草稿（所有存档） */
    private fun confirmClearAllDrafts() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_danger_title))
            .setMessage(getString(R.string.msg_clear_drafts_confirm))
            .setPositiveButton(getString(R.string.btn_clear)) { _, _ ->
                DraftStore.clearAll(this)
                toast(getString(R.string.toast_drafts_cleared))
                currentDialog?.dismiss()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showErrorDialog(msg: String) {
        if (msg.startsWith("DB_CORRUPT:")) {
            val parts = msg.removePrefix("DB_CORRUPT:").split(":")
            showLogDialog(
                title = getString(R.string.title_database_corruption),
                detail = getString(R.string.msg_try_force_repair),
                actionText = getString(R.string.btn_try_to_fix),
                onAction = {
                    worldVM.tryRepairDb(parts[0]) { ok ->
                        if (ok) {
                            toast(getString(R.string.status_under_repair))
                            parts.getOrNull(1)?.let { worldVM.openPlayerData(this, it) }
                        }
                    }
                },
                onCancel = { toast(getString(R.string.toast_operation_canceled)) },
                cancelable = false
            )
        } else {
            showLogDialog(title = getString(R.string.title_error), detail = msg)
        }
    }

    /**
     * 保存当前编辑内容到存档。
     *
     * 从「保存修改」按钮里抽出来，供拼图生成成功后的提醒弹窗复用 ——
     * 用户在那里点「立即保存」就走同一套逻辑，不会出现两份不一致。
     *
     * @return true 表示已发起保存（异步结果另行提示），false 表示前置条件不满足
     */
    private fun saveAllChanges(): Boolean {
        // 树形视图同样支持保存：树上的编辑直接作用于根数据对象图，
        // getRootData() 返回真正的根，所见即所存。
        val d = editorVM.getRootData() ?: run {
            toast(getString(R.string.toast_no_data)); return false
        }
        val f = etWorldName!!.text.toString().trim()
        if (f.isEmpty() || f == getString(R.string.hint_select_world)) {
            toast(getString(R.string.toast_select_world)); return false
        }
        // 保存前确认为什么要「所有文件访问」权限
        // （而不是在启动时不分青红皂白地跳设置）
        if (!askAllFilesPermission(getString(R.string.msg_perm_needed_for_save))) return false
        worldVM.saveAndPushBack(this, d, f)
        return true
    }

    // ============================================
    // 结果处理
    // ============================================
    private fun handleResult(msg: String) {
        when {
            msg.startsWith("Puzzle:SUCCESS:") -> {
                val parts = msg.removePrefix("Puzzle:SUCCESS:").split(":")
                val totalMaps = parts.getOrElse(0) { "0" }
                val totalLayers = parts.getOrElse(1) { "0" }
                val startMapId = parts.getOrElse(2) { "0" }
                val freeSlot = parts.getOrElse(3) { "0" }
                // 数据只写进了 LevelDB，存档文件还没落地 —— 用弹窗提醒手动保存，
                // 不用 Toast：一来 Toast 撑不下两行，二来容易被忽略。
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.title_puzzle_generated))
                    .setMessage(
                        getString(
                            R.string.msg_puzzle_success_format,
                            totalMaps.toIntOrNull() ?: 0,
                            totalLayers.toIntOrNull() ?: 0,
                            startMapId.toIntOrNull() ?: 0,
                            freeSlot.toIntOrNull() ?: 0
                        )
                    )
                    .setPositiveButton(R.string.btn_save_now) { _, _ -> saveAllChanges() }
                    .setNegativeButton(R.string.btn_later, null)
                    .show()
                worldVM.currentWorldFolder.value?.let { folder ->
                    worldVM.reloadPlayerFromCurrentDb()
                }
            }
            msg.startsWith("Puzzle:ERROR:") -> {
                val error = msg.removePrefix("Puzzle:ERROR:")
                toast(getString(R.string.msg_puzzle_error, error))
            }
            else -> toast(msg)
        }
    }

    // ============================================
    // 全屏 & 主题
    // ============================================
    internal fun toggleFullScreen(enable: Boolean) {
        isFullScreen = enable
        if (enable) {
            layoutNormalUi?.isVisible = false; layoutEditorHeader?.isVisible = false
            layoutFullscreenBar?.isVisible = true; tvFullscreenPath?.text = editorVM.pathTitle.value
        } else {
            layoutNormalUi?.isVisible = true; layoutEditorHeader?.isVisible = true
            layoutFullscreenBar?.isVisible = false
        }
    }

    /**
     * 应用主题。
     *
     * 以前是「手动刷 4 个控件的颜色」，所以只有主界面那几块变暗，
     * 对话框、列表项、设置面板全都还是白底 —— 这就是夜间模式看着
     * 一半亮一半暗的原因。
     *
     * 现在交给 AppCompatDelegate：它会切换到 values-night 资源，
     * 所有用 @color/app_* 的地方（背景/文字/分隔线）自动跟着变。
     * 布局里已经不再硬编码颜色，因此不需要再逐个 setBackgroundColor。
     */
    /**
     * 应用夜间 / 日间主题。
     *
     * 以前是「手动刷 4 个控件的颜色」，所以只有主界面那几块变暗，
     * 对话框、列表项、设置面板全都还是白底 —— 这就是夜间模式看着一半亮
     * 一半暗的原因。
     *
     * 现在：
     *   · 所有布局改用 @color/app_*（values / values-night 各一套）
     *   · 这里用系统 UiModeManager 切换 UI_MODE_NIGHT_YES/NO
     *   · 切换后 recreate() 让 Activity 重新取资源，values-night 生效
     *
     * 注意：本项目没有引入 appcompat（AppCompatDelegate 用不了），
     * 所以走系统 API —— targetSdk 28 允许这样做。
     */
    @Suppress("DEPRECATION")
    @SuppressLint("DiscouragedApi")
    internal fun applyTheme() {
        // 配色由 values / values-night 两套 @color 提供；
        // 具体用哪套由 attachBaseContext 里的 Configuration.uiMode 决定。
        // 这里不需要手动刷控件颜色 —— 切换后 recreate() 就会重新取资源。
    }

    // ============================================
    // 工具
    // ============================================
    internal fun toast(s: String?) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun checkFolder(f: String): Boolean {
        if (f.isEmpty() || f == getString(R.string.hint_select_world))
        { toast(getString(R.string.toast_select_world)); return false }
        return true
    }
    private fun ensureDbLoaded(task: Runnable) {
        if (worldVM.currentWorkingDbPath != null && File(worldVM.currentWorkingDbPath!!).exists()) {
            task.run()
            return
        }
        val f = etWorldName?.text.toString()
        if (f.isEmpty() || f == getString(R.string.hint_select_world)) {
            toast(getString(R.string.toast_select_save_first))
            return
        }
        worldVM.pendingSidebarTask = task
        worldVM.openPlayerData(this, f)
    }

    internal fun showProgressDialog(title: String, message: String) {
        if (progressDialog?.isShowing == true) progressDialog?.dismiss()
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null)
        tvProgressMessage = view.findViewById(R.id.tv_progress_message)
        tvProgressMessage!!.text = message
        progressDialog = AlertDialog.Builder(this).setTitle(title).setView(view).setCancelable(false).create()
        progressDialog?.show()
    }
    private fun updateProgress(msg: String) { runOnUiThread { tvProgressMessage?.text = msg } }
    internal fun dismissProgressDialog() { progressDialog?.dismiss(); progressDialog = null; tvProgressMessage = null }

    private fun createNoMedia() {
        try {
            val b = File(BRIDGE_ROOT); if (!b.exists()) b.mkdirs()
            File(b, ".nomedia").let { if (!it.exists()) it.createNewFile() }
        } catch (_: Exception) {}
    }
    private fun ensureNoMedia(dir: File) {
        try { if (!dir.exists()) dir.mkdirs(); File(dir, ".nomedia").let { if (!it.exists()) it.createNewFile() } }
        catch (_: Exception) {}
    }

    private fun showSlotPickerDialog(info: MapArtViewModel.InventorySlotInfo) {
        val uri = puzzleImageUri ?: run {
            mapVM.clearInventorySlotInfo()
            return
        }

        // 网格化选择（原来是一长串文本列表，看不出空格/满格的空间关系）
        showSlotGrid(
            info = info,
            onPick = { targetSlot ->
                mapVM.clearInventorySlotInfo()
                val slotInfo = info.mainSlots[targetSlot]

                if (slotInfo.isOccupied) {
                    // 已占用的格子：置灰但仍可点，这里做二次确认
                    val label = getString(R.string.label_puzzle_slot, targetSlot)
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.title_puzzle_slot_occupied))
                        .setMessage(
                            getString(
                                R.string.msg_puzzle_slot_has_items,
                                label, slotInfo.name, slotInfo.count
                            )
                        )
                        .setPositiveButton(getString(R.string.btn_puzzle_confirm_overwrite)) { _, _ ->
                            // 裁剪模式固定 1x1：只出 1 张地图
                        if (pendingPuzzleCrop != null) {
                            mapVM.generatePuzzleMap(this, uri, puzzleRows, puzzleCols, targetSlot, pendingPuzzleCrop, -1)
                        } else {
                            mapVM.generatePuzzleMap(this, uri, puzzleRows, puzzleCols, targetSlot, null, pendingPuzzlePad)
                        }
                            puzzleImageUri = null
                        }
                        .setNegativeButton(getString(R.string.btn_cancel)) { _, _ ->
                            puzzleImageUri = null
                        }
                        .show()
                } else {
                    // 裁剪模式固定 1x1：只出 1 张地图
                        if (pendingPuzzleCrop != null) {
                            mapVM.generatePuzzleMap(this, uri, puzzleRows, puzzleCols, targetSlot, pendingPuzzleCrop, -1)
                        } else {
                            mapVM.generatePuzzleMap(this, uri, puzzleRows, puzzleCols, targetSlot, null, pendingPuzzlePad)
                        }
                    puzzleImageUri = null
                }
            },
            onCancel = {
                mapVM.clearInventorySlotInfo()
                puzzleImageUri = null
            }
        )
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                toast(getString(R.string.toast_storage_granted))
            } else {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.title_permission_denied))
                    .setMessage(getString(R.string.msg_need_storage))
                    .setPositiveButton(getString(R.string.btn_go_settings)) { _, _ ->
                        try {
                            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = "package:$packageName".toUri()
                            })
                        } catch (_: Exception) {
                            toast(getString(R.string.toast_open_settings_failed))
                        }
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 如果是 Android 11+ 且之前没权限，用户授予后自动刷新
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            // 权限已授予，可以提示用户
        }
    }

    // ============================================
    // Companion
    // ============================================
    companion object {
        const val TYPE_PLAYER = 0; const val TYPE_MAP = 1; const val TYPE_VILLAGE = 2
        const val PATH_STANDARD =
            "/storage/emulated/0/Android/data/com.mojang.minecraftpe/files/games/com.mojang/minecraftWorlds/"
        const val PATH_LEGACY = "/storage/emulated/0/games/com.mojang/minecraftWorlds/"

        /**
         * 软件内部路径：Minecraft **自己的私有目录**。
         *
         * ⚠️ 这是 /data/user/0/... —— 应用私有存储，
         *    除了 root，任何应用（含 Shizuku 的 shell 用户）都进不去。
         *    所以这个选项只在「真实 root 可用」时才允许展示。
         */
        const val PATH_INTERNAL =
            "/data/user/0/com.mojang.minecraftpe/games/com.mojang/minecraftWorlds/"

        const val BRIDGE_ROOT = AppDirs.PUBLIC_ROOT + AppDirs.BRIDGE + "/"
        const val LEVEL_DAT_NAME = "level.dat"
        const val REQUEST_STORAGE_PERMISSION = 100
    }
}