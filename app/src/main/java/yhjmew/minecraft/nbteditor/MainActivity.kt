package yhjmew.minecraft.nbteditor

import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.content.edit
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
import yhjmew.minecraft.nbteditor.ui.*
import yhjmew.minecraft.nbteditor.viewmodel.EditorViewModel
import yhjmew.minecraft.nbteditor.viewmodel.MapArtViewModel
import yhjmew.minecraft.nbteditor.viewmodel.WorldViewModel
import yhjmew.minecraft.nbteditor.viewmodel.WorldViewModel.BackupItem
import yhjmew.minecraft.nbteditor.viewmodel.WorldViewModel.CacheItem
import yhjmew.minecraft.nbteditor.viewmodel.WorldViewModel.WorldItem
import java.io.*
import java.util.*

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
    internal var viewMainContent: View? = null
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
    internal var currentLang: String? = null
    internal var prefs: SharedPreferences? = null

    // ============================================
    // 拼图状态
    // ============================================
    var puzzleRows = 1
    var puzzleCols = 1
    var currentTargetMapArray: JsonArray? = null




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
            toast(getString(R.string.msg_saf_selected))
            showWorldSelector()
        }
    }

    internal val puzzleImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { mapVM.generatePuzzleMap(this, it, puzzleRows, puzzleCols) }
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
    private var pendingBackupListFolder: String? = null

    // ============================================
    // 语言
    // ============================================
    override fun attachBaseContext(newBase: Context) {
        val p = newBase.getSharedPreferences("nbt_config", MODE_PRIVATE)
        val lang = p.getString("app_language",
            if (Locale.getDefault().language.contains("zh")) "zh" else "en")!!
        val config = Configuration()
        config.setLocale(if ("en" == lang) Locale.ENGLISH else Locale.CHINESE)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    // ============================================
    // onCreate
    // ============================================
    @SuppressLint("UseKtx")
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashHandler.instance!!.init(this)
        System.setProperty("sun.arch.data.model", "64")
        super.onCreate(savedInstanceState)

        NbtTranslator.init(this)
        createNoMedia()
        val pr = File("/storage/emulated/0/Download/NbtEditor_Data/")
        ensureNoMedia(worksDir); ensureNoMedia(backupsDir)
        ensureNoMedia(File(pr, "Works")); ensureNoMedia(File(pr, "Backups"))
        ensureNoMedia(File(pr, "Bridge"))

        prefs = getSharedPreferences("nbt_config", MODE_PRIVATE)
        isNightMode = prefs!!.getBoolean("night_mode", false)
        currentLang = prefs!!.getString("app_language",
            if (Locale.getDefault().language.contains("zh")) "zh" else "en")

        val isChinese = Locale.getDefault().language.contains("zh")
        editorVM.setViewMode(prefs!!.getInt("view_mode", if (isChinese) 2 else 0))
        worldVM.setUseShizuku(prefs!!.getBoolean("use_shizuku", true))

//        prefs!!.getString("saf_tree_uri", null)?.let { uriStr ->
//            try {
//                val uri = uriStr.toUri()
//                contentResolver.takePersistableUriPermission(uri,
//                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
//                worldVM.safTreeUri = uri; worldVM.switchPath("$uri/")
//            } catch (_: Exception) { prefs!!.edit { remove("saf_tree_uri") } }
//        }

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.main)
        initViews()
        applyTheme()

        worldVM.editorVM = editorVM
        mapVM.worldVM = worldVM
        setupObservers(); setupButtons()

        try { Class.forName("rikka.shizuku.Shizuku") }
        catch (e: Exception) { toast(getString(R.string.toast_shizuku_init, e.message)) }
        Shizuku.addRequestPermissionResultListener { _, grant ->
            toast(if (grant == PackageManager.PERMISSION_GRANTED)
                getString(R.string.toast_shizuku_granted)
            else getString(R.string.toast_shizuku_denied))
        }
        worldVM.checkStoragePermission(this)

        Shizuku.addRequestPermissionResultListener { _, grant ->
            toast(if (grant == PackageManager.PERMISSION_GRANTED)
                getString(R.string.toast_shizuku_granted)
            else getString(R.string.toast_shizuku_denied))
        }

        if (savedInstanceState == null) worldVM.cleanUpOldSessions(this)
        restoreSavedState(savedInstanceState)
    }

    private fun initViews() {
        viewMainContent = findViewById(R.id.main_content)
        viewSidebar = findViewById(R.id.sidebar_content)
        etWorldName = findViewById(R.id.et_world_name)
        nbtListView = findViewById(R.id.lv_nbt_list)
        tvCurrentPath = findViewById(R.id.tv_current_path)
        layoutNormalUi = findViewById(R.id.layout_normal_ui)
        layoutEditorHeader = findViewById(R.id.layout_editor_header)
        layoutFullscreenBar = findViewById(R.id.layout_fullscreen_bar)
        tvFullscreenPath = findViewById(R.id.tv_fullscreen_path)
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
                .collect { if (it) showProgressDialog(getString(R.string.log_please_wait),
                    worldVM.loadingMessage.value.ifEmpty { mapVM.progressMessage.value }.ifEmpty { "..." })
                else dismissProgressDialog() }
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
                findViewById<TextView?>(R.id.tv_info_seed)?.let {
                    if (seed != null) { it.text = getString(R.string.key_seed_display, seed); it.isVisible = true }
                    else it.isVisible = false
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
        lifecycleScope.launch {
            worldVM.backupList.drop(1).collect { list ->
                pendingBackupListFolder?.let { showBackupDialog(list); pendingBackupListFolder = null }
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
                        .setMessage(getString(R.string.msg_this_archive_has_not_generated_data_yet, it))
                        .setPositiveButton(getString(R.string.btn_create_and_open)) { _, _ ->
                            worldVM.createNewGlobalData(it)
                        }
                        .setNegativeButton(getString(R.string.btn_cancel), null)
                        .show()
                }
            }
        }
    }

    // ============================================
    // 按钮
    // ============================================
    @SuppressLint("ClickableViewAccessibility")
    private fun setupButtons() {
        val sc = findViewById<View?>(R.id.custom_sidebar_container)

        tvCurrentPath?.setOnClickListener { toast(getString(R.string.long_press_here_to_open_the_root_menu)) }
        tvCurrentPath?.setOnLongClickListener { showRootMenu(); true }

        findViewById<View?>(R.id.btn_go_fullscreen)?.setOnClickListener { toggleFullScreen(true) }
        findViewById<View?>(R.id.btn_exit_fullscreen)?.setOnClickListener {
            if (editorVM.navigationStack.isNotEmpty()) onBackPressed() else toggleFullScreen(false)
        }
        findViewById<View?>(R.id.btn_fullscreen_menu)?.setOnClickListener { showRootMenu() }

        findViewById<Button?>(R.id.btn_copy)?.setOnClickListener {
            val f = etWorldName!!.text.toString().trim()
            if (f.isEmpty() || f == getString(R.string.hint_select_world)) showWorldSelector()
            else { toast(getString(R.string.toast_loading, f)); worldVM.loadLevelDat(this, f) }
        }
        etWorldName?.setOnClickListener { showWorldSelector() }

        findViewById<Button?>(R.id.btn_edit_player)?.setOnClickListener {
            val f = etWorldName!!.text.toString()
            if (checkFolder(f)) worldVM.loadPlayerData(this, f)
        }
        findViewById<Button?>(R.id.btn_parse)?.setOnClickListener { reparseLevelDat() }
        findViewById<Button?>(R.id.btn_check_permission)?.setOnClickListener {
            if (worldVM.checkShizukuReady()) toast(getString(R.string.toast_shizuku_normal))
            worldVM.checkStoragePermission(this)
        }
        findViewById<Button?>(R.id.btn_switch_path)?.setOnClickListener { showPathSelector() }
        findViewById<Button?>(R.id.btn_clear_cache)?.setOnClickListener { worldVM.scanCache(this) }

        findViewById<Button?>(R.id.btn_backup_history)?.setOnClickListener {
            val f = etWorldName!!.text.toString()
            if (checkFolder(f)) { pendingBackupListFolder = f; worldVM.scanBackups(this, f) }
        }
        findViewById<Button?>(R.id.btn_save)?.setOnClickListener {
            if (editorVM.isTreeMode.value) { toast(getString(R.string.toast_tree_switch_mode)); return@setOnClickListener }
            val d = editorVM.getRootData() ?: run { toast(getString(R.string.toast_no_data)); return@setOnClickListener }
            val f = etWorldName!!.text.toString().trim()
            if (f.isEmpty() || f == getString(R.string.hint_select_world))
            { toast(getString(R.string.toast_select_world)); return@setOnClickListener }
            worldVM.saveAndPushBack(this, d, f)
        }

        val btnTree = findViewById<View?>(R.id.btn_tree_mode)
        btnTree?.setOnClickListener { toggleTreeMode() }
        findViewById<View?>(R.id.btn_fullscreen_tree)?.setOnClickListener { btnTree?.performClick() }

        findViewById<View?>(R.id.btn_view_options)?.setOnClickListener { showViewOptionsDialog() }
        findViewById<View?>(R.id.btn_fullscreen_view)?.setOnClickListener { showViewOptionsDialog() }

        findViewById<View?>(R.id.btn_open_drawer)?.setOnClickListener {
            sc?.let { it.isVisible = true; it.bringToFront() }
        }
        findViewById<View?>(R.id.view_mask)?.setOnClickListener { sc?.isVisible = false }

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
            if (editorVM.isTreeMode.value) toast(getString(R.string.toast_the_search_function_currently_only_supports_list_mode))
            else showEditorSearchDialog()
        }
        findViewById<View?>(R.id.btn_fullscreen_search)?.setOnClickListener {
            findViewById<View?>(R.id.btn_search_nbt)?.performClick()
        }
        findViewById<View?>(R.id.btn_open_any_key)?.setOnClickListener {
            if (worldVM.currentWorkingDbPath == null) toast(getString(R.string.toast_please_initialize_the_database_first))
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
        val node = nbtTreeAdapter!!.getNode(pos)
        val v = node.value!!.asJsonObject.get("v")
        val type = node.type
        if ((type == 7 || type == 11 || type == 12) && v.isJsonArray && v.asJsonArray.size() > 500)
            showArrayPaginationDialog(node.key, v.asJsonArray)
        else showEditValueDialog(node.key, node.value!!.asJsonObject)
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
            val node = nbtTreeAdapter!!.getNode(pos)
            var key = node.key
            val itemData: JsonObject
            if (node.parent == null) {
                key = "ROOT"
                itemData = JsonObject().apply { addProperty("t", 10); add("v", editorVM.nbtData.value) }
            } else if (node.value != null && node.value!!.isJsonObject) {
                itemData = node.value!!.asJsonObject
            } else return
            showLongPressMenu(key, itemData)
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
        toast(if (editorVM.isTreeMode.value) getString(R.string.toast_tree_switch_mode_blocktopograph)
        else getString(R.string.toast_tree_switch_mode_default))
        val data = editorVM.nbtData.value ?: return
        if (editorVM.isTreeMode.value) {
            nbtTreeAdapter = NbtTreeAdapter(this, data).also { it.setViewMode(editorVM.viewMode.value) }
            nbtListView?.adapter = nbtTreeAdapter
        } else {
            if (nbtTreeAdapter != null && editorVM.lastTreeClickPosition >= 0)
                editorVM.syncListModeFromPath(nbtTreeAdapter!!.getNodePath(editorVM.lastTreeClickPosition))
            refreshAdapter(editorVM.currentListData ?: data)
        }
        editorVM.updatePathTitle()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val sc = findViewById<View?>(R.id.custom_sidebar_container)
        if (sc != null && sc.isVisible) { sc.isVisible = false; return }
        if (editorVM.goBack()) {
            refreshAdapter(editorVM.nbtData.value)
            editorVM.goBackScrollPosition()?.let { nbtListView?.post { nbtListView?.setSelection(it) } }
            return
        }
        if (isFullScreen) { toggleFullScreen(false); return }
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
    }

    // ============================================
    // 世界选择 / 路径（轻量包装）
    // ============================================
    private fun showWorldSelector() {
        // 检查是否需要 Shizuku 但不可用
        if (worldVM.currentPath.value == PATH_STANDARD ||
            worldVM.currentPath.value == PATH_LEGACY) {
            when (val status = worldVM.checkShizukuStatus()) {
                is WorldViewModel.ShizukuStatus.NotInstalled -> {
                    showShizukuNotInstalledDialog()
                    return
                }
                is WorldViewModel.ShizukuStatus.NotRunning -> {
                    toast(getString(R.string.toast_shizuku_not_running))
                    return
                }
                is WorldViewModel.ShizukuStatus.PermissionDenied -> {
                    worldVM.requestShizukuPermission()
                    toast(getString(R.string.toast_shizuku_permission_failed))
                    return
                }
                is WorldViewModel.ShizukuStatus.Available -> {
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
            .setCancelable(false)
            .show()
    }

    private fun showPathSelector() {
        AlertDialog.Builder(this).setTitle(getString(R.string.dialog_path_title))
            .setItems(arrayOf(getString(R.string.path_standard), getString(R.string.path_legacy),
                getString(R.string.path_custom), getString(R.string.path_saf))) { _, w ->
                when (w) {
                    0 -> {
                    worldVM.safTreeUri = null
                    prefs?.edit()?.remove("saf_tree_uri")?.apply()
                    worldVM.switchPath(PATH_STANDARD)
                    showWorldSelector()
                }
                    1 -> {
                    worldVM.safTreeUri = null
                    prefs?.edit()?.remove("saf_tree_uri")?.apply()
                    worldVM.switchPath(PATH_LEGACY)
                    showWorldSelector()
                }
                    2 -> showCustomPathDialog()
                    3 -> safPathLauncher.launch(null)
                }
            }.show()
    }

    private fun showCustomPathDialog() {
        val input = EditText(this).apply { setText(worldVM.currentPath.value) }
        AlertDialog.Builder(this).setTitle(getString(R.string.path_custom)).setView(input)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                var p = input.text.toString().trim()
                if (p.isNotEmpty()) {
                    worldVM.safTreeUri = null
                    prefs?.edit()?.remove("saf_tree_uri")?.apply()
                    worldVM.switchPath(if (p.endsWith("/")) p else "$p/")
                    showWorldSelector()
                }
            }.show()
    }

    private fun showWorldSelectorDialog(list: List<WorldItem>) {
        if (list.isEmpty()) { toast(getString(R.string.toast_no_saves_found)); return }
        AlertDialog.Builder(this).setTitle(getString(R.string.select_world_count, list.size))
            .setItems(list.map { "${it.displayName}\n${it.folderName}" }.toTypedArray()) { _, i ->
                val f = list[i].folderName; etWorldName?.setText(f); worldVM.loadLevelDat(this, f)
            }.show()
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
                list.map { "${if (it.isDirectory) "📁" else "📄"} ${it.name}" })
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
        val lv = ListView(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1,
                list.map { "${if (it.isDirectory) "📁" else "📄"} ${it.name}\n${it.time}" })
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.dialog_backup_title)).setView(lv)
            .setNeutralButton(getString(R.string.btn_close), null).show()
        lv.setOnItemClickListener { _, _, p, _ -> worldVM.restoreBackup(this, list[p]) }
    }

    private fun showErrorDialog(msg: String) {
        if (msg.startsWith("DB_CORRUPT:")) {
            val parts = msg.removePrefix("DB_CORRUPT:").split(":")
            AlertDialog.Builder(this).setTitle(getString(R.string.title_database_corruption))
                .setMessage(getString(R.string.msg_have_you_tried_a_brute_force_repair))
                .setPositiveButton(getString(R.string.btn_try_to_fix)) { _, _ ->
                    worldVM.tryRepairDb(parts[0]) { ok ->
                        if (ok) { toast(getString(R.string.msg_under_repair)); parts.getOrNull(1)?.let { worldVM.loadPlayerData(this, it) } }
                    }
                }.setNegativeButton(getString(R.string.btn_cancel)) { _, _ -> toast(getString(R.string.toast_operation_canceled_please_check_archive_integrity)) }.setCancelable(false).show()
        } else AlertDialog.Builder(this).setTitle("Error").setMessage(msg).setPositiveButton(getString(R.string.btn_close), null).show()
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
                toast(getString(R.string.msg_puzzle_success_format,
                    totalMaps.toIntOrNull() ?: 0,
                    totalLayers.toIntOrNull() ?: 0,
                    startMapId.toIntOrNull() ?: 0,
                    freeSlot.toIntOrNull() ?: 0))
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

    @Suppress("DEPRECATION")
    @SuppressLint("DiscouragedApi")
    internal fun applyTheme() {
        val main = viewMainContent ?: return
        val tvT = findViewById<TextView?>(R.id.tv_app_title)
        if (isNightMode) {
            main.setBackgroundColor(-0xededee); viewSidebar?.setBackgroundColor(-0xe1e1e2)
            tvT?.setTextColor(-0x1); nbtListView?.setBackgroundColor(-0xe1e1e2)
        } else {
            main.setBackgroundColor(-0xa0a0b); viewSidebar?.setBackgroundColor(-0x1)
            tvT?.setTextColor(-0xcccccd); nbtListView?.setBackgroundColor(-0x1)
        }
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
            toast(getString(R.string.toast_please_click_the_blue_button_to_select_an_archive_first))
            return
        }
        worldVM.pendingSidebarTask = task
        worldVM.loadPlayerData(this, f)
    }
    private val worksDir: File
        get() { val d = File("${externalCacheDir?.parent}/Works"); if (!d.exists()) d.mkdirs(); return d }
    private val backupsDir: File
        get() { val d = File("${externalCacheDir?.parent}/Backups"); if (!d.exists()) d.mkdirs(); return d }

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

    // ============================================
    // Companion
    // ============================================
    companion object {
        var clipboard: JsonElement? = null
        const val TYPE_PLAYER = 0; const val TYPE_MAP = 1; const val TYPE_VILLAGE = 2
        const val PATH_STANDARD =
            "/storage/emulated/0/Android/data/com.mojang.minecraftpe/files/games/com.mojang/minecraftWorlds/"
        const val PATH_LEGACY = "/storage/emulated/0/games/com.mojang/minecraftWorlds/"
        const val BRIDGE_ROOT = "/storage/emulated/0/Download/NbtEditor_Data/Bridge/"
        const val LEVEL_DAT_NAME = "level.dat"
    }
}