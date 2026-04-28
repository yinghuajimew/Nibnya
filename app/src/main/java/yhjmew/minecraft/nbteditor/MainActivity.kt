package yhjmew.minecraft.nbteditor

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.view.isVisible
import androidx.core.graphics.toColorInt
import androidx.core.graphics.scale
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.Html
import android.text.InputType
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Filter
import android.widget.Filterable
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnRequestPermissionResultListener
import yhjmew.minecraft.nbteditor.BedrockParser.parseBytes
import yhjmew.minecraft.nbteditor.BedrockParser.write
import yhjmew.minecraft.nbteditor.BedrockParser.writeToBytes
import yhjmew.minecraft.nbteditor.NbtTranslator.init
import yhjmew.minecraft.nbteditor.NbtTranslator.reset
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileReader
import java.io.FileWriter
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import java.util.Locale
import java.util.Stack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.Any
import kotlin.Array
import kotlin.Boolean
import kotlin.BooleanArray
import kotlin.Byte
import kotlin.ByteArray
import kotlin.CharSequence
import kotlin.Exception
import kotlin.Int
import kotlin.IntArray
import kotlin.Number
import kotlin.NumberFormatException
import kotlin.RuntimeException
import kotlin.String
import kotlin.Throwable
import kotlin.Throws
import kotlin.also
import kotlin.arrayOf
import kotlin.intArrayOf
import kotlin.longArrayOf
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.plus
import kotlin.require
import kotlin.synchronized
import kotlin.toString
import androidx.core.graphics.drawable.toDrawable

// 必需
class MainActivity : Activity() {
    // 控件
    private var etWorldName: EditText? = null
    private var btnCopy: Button? = null
    private var btnParse: Button? = null
    private var btnSave: Button? = null
    private var btnCheck: Button? = null
    private var btnHistory: Button? = null
    private var btnEditPlayer: Button? = null
    private var btnSwitchPath: Button? = null
    private var btnCleanCache: Button? = null
    private var nbtListView: ListView? = null
    private var tvCurrentPath: TextView? = null
    private var isNightMode = false
    private var prefs: SharedPreferences? = null

    // 数据与状态
    private var nbtAdapter: NbtAdapter? = null
    private var rootNbtData: JsonObject? = null
    private var isEditingPlayer = false

    private var currentWorkingDbPath: String? = null
    private var currentWorkingFileOrDir: String? = null

    private val navigationStack = Stack<JsonObject?>()
    private val pathStack = Stack<String?>()
    private val scrollPositionStack = Stack<Int?>()
    private var currentLang: String? = null

    private var isFullScreen = false // 全屏标志位
    private var layoutNormalUi: View? = null
    private var layoutEditorHeader: View? = null
    private var layoutFullscreenBar: View? = null

    // 在类顶部变量区加入
    private var tvFullscreenPath: TextView? = null
    private var currentViewMode = 0
    private var isTreeMode = false // 当前是否为 Blocktopograph 模式
    private var nbtTreeAdapter: NbtTreeAdapter? = null // 专用的树适配器
    private var viewMainContent: View? = null // 主界面的容器
    private var viewSidebar: View? = null // 侧边栏的容器
    private var currentTargetKey: String? = "~local_player"
    private var currentTargetMapArray: JsonArray? = null // 暂存当前正在操作的 colors 数组
    private var safTreeUri: Uri? = null // 保存 SAF 授权的 URI

    // 暂存拼图尺寸
    private var puzzleRows = 1
    private var puzzleCols = 1

    // 【新增】数据缓存：Key -> JsonObject
    // 用于在不同 NBT 之间切换时实现“秒开”且保留状态
    private val nbtDataCache = HashMap<String?, JsonObject?>()

    // 【新增】列表缓存 (避免每次点侧边栏都重新扫描数据库)
    private var cacheMapList: MutableList<String>? = null
    private var cacheVillageList: MutableList<String>? = null
    private var cachePlayerList: MutableList<String>? = null

    // 记录上一次加载的存档名，用于防止重复重置
    private var lastLoadedWorldFolder: String? = null

    // 【新增】专门记录列表模式当前停留在哪一层数据
    private var currentListData: JsonObject? = null
    private var lastTreeClickPosition = -1 // 记录树状图最后点击的位置

    // 在类顶部
    private var useMultiThreading = true // 默认开启

    // 会话缓存：Key(比如 "~local_player" 或 "level.dat") -> Session
    private val sessionCacheMap: MutableMap<String?, EditorSession?> =
        HashMap<String?, EditorSession?>()
    private var useShizuku = true // 默认启用 Shizuku

    // 【新增】自定义进度对话框（替代已弃用的 ProgressDialog）
    private var progressDialog: AlertDialog? = null
    private var tvProgressMessage: TextView? = null

    private var currentWorldsPath: String = PATH_STANDARD
    private val REQUEST_PERMISSION_RESULT_LISTENER =
        OnRequestPermissionResultListener { _, grantResult: Int ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) toast(getString(R.string.toast_shizuku_granted))
            else toast(getString(R.string.toast_shizuku_denied))
        }

    // ============================================
    // 【新增】核心修复：Android 7.0+ 语言切换
    // ============================================
    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("nbt_config", MODE_PRIVATE)
        val defaultLang = if (Locale.getDefault().getLanguage().contains("zh")) "zh" else "en"
        val lang: String = prefs.getString("app_language", defaultLang)!!

        // 直接设置，无需中间变量
        val config = Configuration()
        config.setLocale(if ("en" == lang) Locale.ENGLISH else Locale.CHINESE)

        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashHandler.instance!!.init(this)
        System.setProperty("sun.arch.data.model", "64")
        super.onCreate(savedInstanceState)
        // 预先创建好中转站和屏蔽文件
        createNoMedia()
        init(this)
        // 【新增】保护工作目录和备份目录 (无论是在私有还是公共存储)
        ensureNoMedia(this.worksDir)
        ensureNoMedia(this.backupsDir)
        // 2. 【核心修复】强制保护公共下载目录 (不管当前选什么模式，这里都要保护)
        val publicRoot = File("/storage/emulated/0/Download/NbtEditor_Data/")
        ensureNoMedia(File(publicRoot, "Works"))
        ensureNoMedia(File(publicRoot, "Backups"))
        // 如果有 Bridge 目录，其实 createNoMedia() 已经处理了，但为了保险也可以加
        ensureNoMedia(File(publicRoot, "Bridge"))
        // 1. 初始化偏好 & 读取语言设置
        prefs = getSharedPreferences("nbt_config", MODE_PRIVATE)
        isNightMode = prefs!!.getBoolean("night_mode", false)
        currentLang = prefs!!.getString(
            "app_language",
            if (Locale.getDefault().language.contains("zh")) "zh" else "en"
        )
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.main)
        viewMainContent = findViewById(R.id.main_content)
        viewSidebar = findViewById(R.id.sidebar_content)
        // 在 onCreate 初始化 prefs 之后
        useMultiThreading = prefs!!.getBoolean("use_multithread", true)
        // 在 useMultiThreading 初始化之后添加
        useShizuku = prefs!!.getBoolean("use_shizuku", true)
        // === 逻辑：中文默认 Smart(2)，英文默认 Raw(0) ===
        val isChineseEnv = Locale.getDefault().language.contains("zh")
        // 优先读存盘，没有存档则根据语言自动判定
        val defaultMode = if (isChineseEnv) 2 else 0
        currentViewMode = prefs!!.getInt("view_mode", defaultMode)
        val btnOpen = findViewById<View?>(R.id.btn_open_drawer)
        val sidebarContainer = findViewById<View?>(R.id.custom_sidebar_container)
        // 在 onCreate 初始化部分添加
        val savedSafUri = prefs!!.getString("saf_tree_uri", null)
        if (savedSafUri != null) {
            safTreeUri = savedSafUri.toUri()
            try {
                contentResolver.takePersistableUriPermission(
                    safTreeUri!!,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
                safTreeUri = null
                prefs!!.edit { remove("saf_tree_uri") }
            }
        }

        // 初始化控件
        etWorldName = findViewById(R.id.et_world_name)
        btnCopy = findViewById(R.id.btn_copy)
        btnParse = findViewById(R.id.btn_parse)
        btnCheck = findViewById(R.id.btn_check_permission)
        btnSave = findViewById(R.id.btn_save)
        nbtListView = findViewById(R.id.lv_nbt_list)
        btnHistory = findViewById(R.id.btn_backup_history)
        btnEditPlayer = findViewById(R.id.btn_edit_player)
        btnSwitchPath = findViewById(R.id.btn_switch_path)
        btnCleanCache = findViewById(R.id.btn_clear_cache) // 确保XML有此ID
        applyTheme()

        tvCurrentPath = findViewById(R.id.tv_current_path)
        if (tvCurrentPath != null) {
            tvCurrentPath!!.setOnClickListener { toast(getString(R.string.long_press_here_to_open_the_root_menu)) }
            tvCurrentPath!!.setOnLongClickListener {
                showRootMenu()
                true
            }
        } else {
            toast("错误：找不到路径显示控件 tv_current_path")
        }

        try {
            registerListener()
        } catch (e: Exception) {
            Log.e("MainActivity", "Shizuku registerListener failed", e)
            // 可选：toast提示
            toast("Shizuku 初始化异常: ${e.message}")
        }
        Shizuku.addRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)
        checkStoragePermission()

        if (savedInstanceState == null) {
            cleanUpOldSessions()
        }

        val btnGoFull = findViewById<View?>(R.id.btn_go_fullscreen)
        if (btnGoFull != null) {
            btnGoFull.setOnClickListener { toggleFullScreen(true) }
        }

        val btnExitFull = findViewById<View?>(R.id.btn_exit_fullscreen)
        if (btnExitFull != null) {
            btnExitFull.setOnClickListener {
                if (navigationStack.isNotEmpty()) {
                    onBackPressed()
                } else {
                    toggleFullScreen(false)
                }
            }
        }

        btnCopy!!.setOnClickListener {
            val currentFolder = etWorldName!!.text.toString().trim()
            val hint = getString(R.string.hint_select_world)
            if (currentFolder.isEmpty() || currentFolder == hint || currentFolder.contains(
                    getString(R.string.hint_select_world)
                )
            ) {
                showWorldSelector()
            } else {
                toast(getString(R.string.toast_loading) + currentFolder)
                copyLevelDatOut(currentFolder)
            }
        }

        etWorldName!!.setOnClickListener { showWorldSelector() }

        btnEditPlayer!!.setOnClickListener {
            val folder = etWorldName!!.text.toString()
            if (checkFolder(folder)) loadPlayerData(folder, null)
        }

        btnParse!!.setOnClickListener { parseLevelDat() }

        btnCheck!!.setOnClickListener {
            if (checkShizukuReady()) toast(getString(R.string.toast_shizuku_normal))
            checkStoragePermission()
        }

        if (btnSwitchPath != null) {
            btnSwitchPath!!.setOnClickListener { showPathSelector() }
        }
        if (btnCleanCache != null) {
            btnCleanCache!!.setOnClickListener { showCacheList() }
        }

        btnHistory!!.setOnClickListener {
            val f = etWorldName!!.text.toString()
            if (checkFolder(f)) showBackupList(f)
        }

        nbtListView!!.setOnItemClickListener { _, _, position, _ ->
            if (isTreeMode) {
                lastTreeClickPosition = position
                if (nbtTreeAdapter!!.isContainer(position)) {
                    nbtTreeAdapter!!.toggleExpand(position)
                } else {
                    val node = nbtTreeAdapter!!.getNode(position)
                    val v = node!!.value!!.asJsonObject.get("v")
                    val type = node.type
                    if ((type == 7 || type == 11 || type == 12) && v.isJsonArray && v.asJsonArray.size() > 500) {
                        showArrayPaginationDialog(node.key, v.asJsonArray)
                    } else {
                        showEditValueDialog(node.key, node.value!!.asJsonObject)
                    }
                }
            } else {
                val key = nbtAdapter!!.getKey(position)
                val rawItem = nbtAdapter!!.data!!.get(key)
                val itemData = wrapRawItem(rawItem)
                val type = itemData.get("t").asInt
                if (type == 10) enterFolder(key, itemData.getAsJsonObject("v"), false)
                else if (type == 9) enterFolder(key, convertListToMap(itemData), true)
                else {
                    val v = itemData.get("v")
                    if ((type == 7 || type == 11 || type == 12) && v.isJsonArray && v.asJsonArray.size() > 500) {
                        showArrayPaginationDialog(key!!, v.asJsonArray)
                    } else {
                        showEditValueDialog(key!!, itemData)
                    }
                }
            }
        }

        nbtListView!!.setOnItemLongClickListener { _, _, position, _ ->
            lastTreeClickPosition = position
            if (isTreeMode) {
                val node = nbtTreeAdapter!!.getNode(position)
                var key = node!!.key
                val itemData: JsonObject
                if (node.parent == null) {
                    key = "ROOT"
                    itemData = JsonObject()
                    itemData.addProperty("t", 10)
                    itemData.add("v", rootNbtData)
                } else if (node.value != null && node.value!!.isJsonObject) {
                    itemData = node.value!!.asJsonObject
                } else {
                    return@setOnItemLongClickListener false
                }
                showLongPressMenu(key, itemData)
                true
            } else {
                val key = nbtAdapter!!.getKey(position)
                val rawItem = nbtAdapter!!.data!!.get(key)
                val itemData = wrapRawItem(rawItem)
                showLongPressMenu(key, itemData)
                true
            }
        }

        if (!checkShizukuAvailable()) {
            toast(getString(R.string.toast_shizuku_needed))
            try {
                val c = Class.forName("rikka.shizuku.Shizuku")
                c.getMethod("requestPermission", Int::class.javaPrimitiveType).invoke(null, 0)
            } catch (_: Exception) {
                toast(getString(R.string.toast_install_shizuku))
            }
        }

        val btnMenuFull = findViewById<View?>(R.id.btn_fullscreen_menu)
        if (btnMenuFull != null) {
            btnMenuFull.setOnClickListener { showRootMenu() }
        }

        val btnOpts = findViewById<View?>(R.id.btn_view_options)
        if (btnOpts != null) {
            btnOpts.setOnClickListener { showViewOptionsDialog() }
        }

        val btnFullViewOpts = findViewById<View?>(R.id.btn_fullscreen_view)
        if (btnFullViewOpts != null) {
            btnFullViewOpts.setOnClickListener { showViewOptionsDialog() }
        }

        btnSave!!.setOnClickListener {
            if (isTreeMode) {
                toast(getString(R.string.toast_tree_switch_mode))
                return@setOnClickListener
            }
            if (nbtAdapter == null) {
                toast(getString(R.string.toast_no_data))
                return@setOnClickListener
            }
            if (nbtAdapter!!.data == null) {
                toast("Data is null!")
                return@setOnClickListener
            }
            val folder = etWorldName!!.text.toString().trim()
            val hintText = getString(R.string.hint_select_world)
            if (folder.isEmpty() || folder == hintText || folder.contains("...")) {
                toast(getString(R.string.toast_select_world))
                return@setOnClickListener
            }
            saveAndPushBack(rootNbtData, folder)
        }

        val btnTree = findViewById<View?>(R.id.btn_tree_mode)
        if (btnTree != null) {
            btnTree.setOnClickListener {
                isTreeMode = !isTreeMode
                val toastMsg =
                    if (isTreeMode) getString(R.string.toast_tree_switch_mode_blocktopograph) else getString(
                        R.string.toast_tree_switch_mode_default
                    )
                toast(toastMsg)
                if (isTreeMode) {
                    nbtTreeAdapter = NbtTreeAdapter(this@MainActivity, rootNbtData)
                    nbtTreeAdapter!!.setViewMode(currentViewMode)
                    var targetPos = 0
                    if (pathStack.isNotEmpty()) {
                        targetPos = nbtTreeAdapter!!.expandPath(pathStack)
                    }
                    nbtListView!!.adapter = nbtTreeAdapter
                    nbtListView!!.setSelection(targetPos)
                } else {
                    if (nbtTreeAdapter != null && lastTreeClickPosition != -1) {
                        val treePath = nbtTreeAdapter!!.getNodePath(lastTreeClickPosition)
                        syncListModeFromPath(treePath)
                    }
                    val dataToShow = if (currentListData != null) currentListData else rootNbtData
                    updateAdapter(dataToShow)
                }
                updatePathTitle()
            }
        }

        val sidebarArr = arrayOf<View?>(findViewById(R.id.sidebar_content))
        if (sidebarArr[0] != null) {
            val screenWidth = resources.displayMetrics.widthPixels
            val params = sidebarArr[0]!!.layoutParams
            params.width = screenWidth * 2 / 3
            sidebarArr[0]!!.layoutParams = params
        }

        val btnMultiPlayer = findViewById<View?>(R.id.btn_online_players)
        btnMultiPlayer?.setOnClickListener {
            sidebarContainer?.visibility = View.GONE
            ensureDbLoaded { showMultiPlayerDialog() }
        }


        val btnMap = findViewById<View?>(R.id.btn_map_nbt)
        btnMap?.setOnClickListener {
            sidebarContainer?.visibility = View.GONE
            ensureDbLoaded { showMapListDialog() }
        }

        val btnVillage = findViewById<View?>(R.id.btn_village_nbt)
        btnVillage?.setOnClickListener {
            sidebarContainer?.visibility = View.GONE
            ensureDbLoaded { showVillageListDialog() }
        }

        val btnSearchNbt = findViewById<View?>(R.id.btn_search_nbt)
        if (btnSearchNbt != null) {
            btnSearchNbt.setOnClickListener {
                if (isTreeMode) {
                    toast(getString(R.string.toast_the_search_function_currently_only_supports_list_mode))
                    return@setOnClickListener
                }
                if (nbtAdapter == null || nbtAdapter!!.count == 0) {
                    toast(getString(R.string.toast_there_is_currently_no_data_to_search))
                    return@setOnClickListener
                }
                showEditorSearchDialog()
            }
        }

        val btnFullTree = findViewById<View?>(R.id.btn_fullscreen_tree)
        if (btnFullTree != null && btnTree != null) {
            btnFullTree.setOnClickListener { btnTree.performClick() }
        }

        val btnFullSearch = findViewById<View?>(R.id.btn_fullscreen_search)
        if (btnFullSearch != null && btnSearchNbt != null) {
            btnFullSearch.setOnClickListener { btnSearchNbt.performClick() }
        }

        val btnOpenKey = findViewById<View?>(R.id.btn_open_any_key)
        btnOpenKey?.setOnClickListener {
            if (currentWorkingDbPath == null) {
                toast(getString(R.string.toast_please_initialize_the_database_first))
                return@setOnClickListener
            }
            sidebarContainer?.visibility = View.GONE
            showOpenByKeyDialog()
        }


        btnOpen?.setOnClickListener {
            sidebarContainer?.let {
                it.visibility = View.VISIBLE
                it.bringToFront()
            } ?: toast(getString(R.string.toast_error_sidebar_layout_not_found))
        } ?: toast(getString(R.string.toast_error_menu_button_not_found))

        val viewMask = findViewById<View?>(R.id.view_mask)
        viewMask?.setOnClickListener {
            sidebarContainer?.visibility = View.GONE
        }

        val btnGlobal = findViewById<View?>(R.id.btn_global_nbt)
        btnGlobal?.setOnClickListener {
            sidebarContainer?.visibility = View.GONE
            ensureDbLoaded { showGlobalDataDialog() }
        }

        if (savedInstanceState != null) {
            isEditingPlayer = savedInstanceState.getBoolean("isEditingPlayer")
            currentTargetKey = savedInstanceState.getString("currentTargetKey")
            currentWorkingDbPath = savedInstanceState.getString("currentWorkingDbPath")
            currentWorkingFileOrDir = savedInstanceState.getString("currentWorkingFileOrDir")
            isTreeMode = savedInstanceState.getBoolean("isTreeMode")
            val savedWorldName = savedInstanceState.getString("worldName")
            if (savedWorldName != null && etWorldName != null) {
                etWorldName!!.setText(savedWorldName)
                lastLoadedWorldFolder = savedWorldName
            }
            if (currentWorkingDbPath != null || currentWorkingFileOrDir != null) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (tempNbtData != null) {
                        rootNbtData = tempNbtData
                        currentTargetKey = tempTargetKey
                        updateAdapter(rootNbtData)
                        updatePathTitle()
                        if (!isEditingPlayer && rootNbtData != null) {
                            updateWorldInfoCard(rootNbtData)
                        } else if (etWorldName != null) {
                            val worldName = etWorldName!!.text.toString()
                            val hint = getString(R.string.hint_select_world)
                            if (worldName.isNotEmpty() && worldName != hint && !worldName.contains("...")) {
                                val tvName = findViewById<TextView?>(R.id.tv_app_title)
                                if (tvName != null) tvName.text = worldName
                            }
                        }
                        tempNbtData = null
                        tempTargetKey = null
                        toast(getString(R.string.toast_unsaved_changes_restored))
                    } else {
                        parseLevelDat()
                    }
                }, 100)
            }
        }

        val sidebarHeader = findViewById<View?>(R.id.sidebar_header)
        if (sidebarHeader != null) {
            sidebarHeader.isClickable = true
            sidebarHeader.isFocusable = true
            sidebarHeader.setOnClickListener { showAppInfoDialog() }
            val clickListener = View.OnClickListener { showAppInfoDialog() }
            val ivIcon = findViewById<View?>(R.id.iv_app_icon)
            if (ivIcon != null) ivIcon.setOnClickListener(clickListener)
            val tvName = findViewById<View?>(R.id.tv_sidebar_app_name)
            if (tvName != null) tvName.setOnClickListener(clickListener)
            val tvAuthor = findViewById<View?>(R.id.tv_sidebar_author)
            if (tvAuthor != null) tvAuthor.setOnClickListener(clickListener)
        } else {
            toast("错误：找不到 sidebar_header")
        }

        if (viewMask != null) {
            viewMask.setOnClickListener {
                sidebarArr[0] = findViewById(R.id.custom_sidebar_container)
                if (sidebarArr[0] != null) sidebarArr[0]!!.visibility = View.GONE
            }
        }
        // onCreate 应该在这里结束
    }

    @SuppressLint("UseKtx")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)


        // 统一检查结果有效性
        if (resultCode != RESULT_OK || data == null) return

        // === 分支 1：处理拼图 (Puzzle) ===
        if (requestCode == REQUEST_PICK_IMAGE_FOR_PUZZLE) {
            val puzzleUri = data.getData()
            if (puzzleUri != null) {
                // 直接调用处理方法，然后结束
                processPuzzleMap(puzzleUri)
            }
            return  // 【关键】处理完拼图直接返回，不执行下面的代码
        }

        // === 分支 2：处理单张地图画 (Map Art) ===
        if (requestCode == REQUEST_PICK_IMAGE_FOR_MAP) {
            val imageUri = data.getData() // 在这里定义 imageUri
            if (imageUri == null) return


            // 下面是你原有的单张图片处理逻辑
            showProgressDialog(getString(R.string.msg_processing), getString(R.string.toast_generate_bedrock_edition_map))

            Thread {
                try {
                    // 1. 读取原图
                    val `is` = getContentResolver().openInputStream(imageUri)
                    val original = BitmapFactory.decodeStream(`is`)
                    if (`is` != null) {
                        `is`.close()
                    }

                    // 2. 创建 128x128 居中画布
                    val targetW = 128
                    val targetH = 128
                    val finalBitmap = createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(finalBitmap)
                    canvas.drawColor(Color.WHITE)

                    // 缩放逻辑
                    val origW = original.getWidth()
                    val origH = original.getHeight()
                    val scale = min(targetW.toFloat() / origW, targetH.toFloat() / origH)
                    val newW = Math.round(origW * scale)
                    val newH = Math.round(origH * scale)
                    val left = (targetW - newW) / 2
                    val top = (targetH - newH) / 2

                    val destRect = Rect(left, top, left + newW, top + newH)
                    canvas.drawBitmap(original, null, destRect, null)

                    // 3. 准备数据 (65536)
                    val targetSize = 65536
                    if (currentTargetMapArray == null) currentTargetMapArray = JsonArray()

                    while (currentTargetMapArray!!.size() < targetSize) currentTargetMapArray!!.add(
                        0.toByte()
                    )
                    while (currentTargetMapArray!!.size() > targetSize) currentTargetMapArray!!.remove(
                        currentTargetMapArray!!.size() - 1
                    )

                    // 4. 像素转换
                    var index = 0
                    val pixels = IntArray(128 * 128)
                    finalBitmap.getPixels(pixels, 0, 128, 0, 0, 128, 128)

                    for (i in pixels.indices) {
                        val color = pixels[i]
                        val r = Color.red(color).toByte()
                        val g = Color.green(color).toByte()
                        val b = Color.blue(color).toByte()
                        val a = Color.alpha(color).toByte()

                        currentTargetMapArray!!.set(index++, JsonPrimitive(r))
                        currentTargetMapArray!!.set(index++, JsonPrimitive(g))
                        currentTargetMapArray!!.set(index++, JsonPrimitive(b))
                        currentTargetMapArray!!.set(index++, JsonPrimitive(a))
                    }

                    // 5. 修正 NBT
                    if (nbtAdapter != null) {
                        val mapRoot = nbtAdapter!!.data
                        if (mapRoot != null) {
                            val lockedTag = JsonObject()
                            lockedTag.addProperty("t", 1)
                            lockedTag.addProperty("v", 1.toByte())
                            mapRoot.add("mapLocked", lockedTag)
                        }
                    }

                    runOnUiThread {
                        dismissProgressDialog()
                        toast(getString(R.string.toast_the_map_is_generated))
                        if (nbtAdapter != null) nbtAdapter!!.notifyDataSetChanged()
                        if (nbtTreeAdapter != null) nbtTreeAdapter!!.notifyDataSetChanged()
                    }

                    original.recycle()
                    finalBitmap.recycle()
                } catch (e: Exception) {
                    runOnUiThread {
                        dismissProgressDialog()
                        toast(getString(R.string.toast_build_failed) + e)
                    }
                }
            }.start()
        }

        // 【新增】处理 SAF 路径选择
        if (requestCode == REQUEST_SAF_PATH) {
            val treeUri = data.getData()
            if (treeUri != null) {
                // 持久化授权
                getContentResolver().takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                safTreeUri = treeUri


                // 转换为路径显示
                val path = treeUri.toString()
                currentWorldsPath = path + "/" // SAF 路径特殊标记


                // 保存到 SharedPreferences
                prefs!!.edit { putString("saf_tree_uri", path) }

                toast(String.format(getString(R.string.msg_saf_selected), path))
                showWorldSelector()
            }
        }
    }

    // 【新增】保存当前状态（防止切换语言或旋转屏幕后数据丢失）
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 保存关键变量
        outState.putBoolean("isEditingPlayer", isEditingPlayer)
        outState.putString("currentTargetKey", currentTargetKey)
        outState.putString("currentWorkingDbPath", currentWorkingDbPath)
        outState.putString("currentWorkingFileOrDir", currentWorkingFileOrDir)
        // 保存输入框里的存档名
        if (etWorldName != null) {
            outState.putString("worldName", etWorldName!!.getText().toString())
        }
        // 保存当前的视图模式（是不是树状图）
        outState.putBoolean("isTreeMode", isTreeMode)
    }

    private fun checkFolder(folder: String): Boolean {
        // 动态匹配 strings.xml 里的提示语
        val hint = getString(R.string.hint_select_world)
        // 为了防止匹配出错，取提示语的前几个字或者关键词（例如去掉末尾的省略号）
        // 这里简化逻辑：如果文件夹名包含提示语的一部分，或者为空
        if (folder.isEmpty() || folder.contains("...") || folder.length > 50) {
            // 这里用 contains("...") 是一种模糊匹配，或者直接判断是否等于 hint
            if (folder == hint || folder.isEmpty()) {
                toast(getString(R.string.toast_select_world))
                return false
            }
        }
        // 为了确保安全，最简单的修改是：
        if (folder.isEmpty() || folder == getString(R.string.hint_select_world)) {
            toast(getString(R.string.toast_select_world))
            return false
        }
        return true
    }

    private val baseDir: File?
        // 基础目录分流：新路径->私有Data，旧路径->Download/NbtEditor_Data
        get() {
            if (currentWorldsPath.contains("Android/data")) {
                return getExternalFilesDir(null)
            } else {
                val publicDir =
                    File("/storage/emulated/0/Download/NbtEditor_Data/")
                if (!publicDir.exists()) publicDir.mkdirs()
                return publicDir
            }
        }

    private val worksDir: File
        get() {
            val w = File(this.baseDir, "Works")
            if (!w.exists()) w.mkdirs() // 加固：确保目录存在

            return w
        }

    private val backupsDir: File
        get() {
            val b = File(this.baseDir, "Backups")
            if (!b.exists()) b.mkdirs() // 加固：确保目录存在

            return b
        }

    // 启动时清理旧Session文件 (优化版：保留 Bridge 根目录和 .nomedia)
    private fun cleanUpOldSessions() {
        // 1. 清理私有目录的 Works (Android/data 模式)
        val privateWorks = File(getExternalFilesDir(null), "Works")
        cleanDirectoryContent(privateWorks)


        // 2. 清理公共目录的 Works (旧路径模式)
        val publicWorks = File("/storage/emulated/0/Download/NbtEditor_Data/Works")
        cleanDirectoryContent(publicWorks)


        // 3. 清理 Bridge 中转站 (保留根目录和 .nomedia)
        val bridgeDir = File(BRIDGE_ROOT)
        if (bridgeDir.exists()) {
            // 确保隐身衣存在
            createNoMedia()


            // 只删除里面的子文件夹 (load_db_xxx, save_db_xxx)
            val files = bridgeDir.listFiles()
            if (files != null) {
                for (f in files) {
                    // 【关键】不要删除 .nomedia 文件
                    if (f.getName() == ".nomedia") continue

                    deleteRecursive(f)
                }
            }
        } else {
            // 如果不存在，创建它并加上隐身衣
            bridgeDir.mkdirs()
            createNoMedia()
        }
    }

    // 辅助：清理文件夹内容但不删除文件夹本身
    private fun cleanDirectoryContent(dir: File) {
        if (!dir.exists()) return
        val files = dir.listFiles()
        if (files != null) {
            for (f in files) {
                // 如果是工作中的数据库目录或临时文件，删掉
                if (f.isDirectory() && f.getName().startsWith("working_db_")) deleteRecursive(f)
                if (f.isDirectory() && f.getName().startsWith("db_restore_")) deleteRecursive(f)
                if (f.isFile() && f.getName() == LEVEL_DAT_NAME) f.delete()
            }
        }
    }

    // ==========================================
    // 【修改】管理缓存：使用 Adapter 实现原地刷新
    // ==========================================
    private fun showCacheList() {
        val worksDir = this.worksDir
        // 【加固】如果目录被删了，这里自动创建一下
        if (!worksDir.exists()) worksDir.mkdirs()

        val filesArr = worksDir.listFiles()

        // 【加固】判空逻辑
        if (filesArr == null || filesArr.size == 0) {
            toast(getString(R.string.toast_cache_empty))
            return
        }

        filesArr.sortByDescending { it.lastModified() }

        val fileList: MutableList<File> = ArrayList<File>(Arrays.asList<File?>(*filesArr))
        val nameList: MutableList<String?> = ArrayList<String?>()

        for (f in fileList) {
            if (f.isDirectory()) nameList.add("📁 " + f.getName())
            else nameList.add("📄 " + f.getName())
        }

        val adapter = ArrayAdapter<String?>(this, android.R.layout.simple_list_item_1, nameList)
        val lv = ListView(this)
        lv.setAdapter(adapter)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_manage_cache))
            .setView(lv)
            .setNeutralButton(getString(R.string.btn_close), null)
            .setPositiveButton(
                getString(R.string.btn_clear_all)
            ) { _: DialogInterface?, _: Int ->
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.title_clear_confirm))
                    .setMessage(getString(R.string.msg_clear_all_cache))
                    .setPositiveButton(
                        getString(R.string.btn_delete)
                    ) { _: DialogInterface?, _: Int ->
                        for (f in fileList) deleteRecursive(f)
                        fileList.clear()
                        nameList.clear()
                        adapter.notifyDataSetChanged()
                        // 【加固】重置路径变量
                        currentWorkingDbPath = null
                        currentWorkingFileOrDir = null
                        lastLoadedWorldFolder = null // 【新增】强制下次加载时重置
                        toast(getString(R.string.toast_cleared_short))
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            }
            .create()

        lv.setOnItemClickListener { _: AdapterView<*>?, _: View?, _: Int, _: Long ->
            toast(
                getString(R.string.toast_hold_delete)
            )
        }

        lv.setOnItemLongClickListener { _: AdapterView<*>?, _: View?, pos: Int, _: Long ->
            val target = fileList.get(pos)
            AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.btn_delete))
                .setMessage(String.format(getString(R.string.msg_delete_confirm), target.getName()))
                .setPositiveButton(
                    getString(R.string.btn_delete)
                ) { _: DialogInterface?, _: Int ->
                    deleteRecursive(target)
                    fileList.removeAt(pos)
                    nameList.removeAt(pos)
                    adapter.notifyDataSetChanged()
                    toast(getString(R.string.toast_deleted))
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
            true
        }
        dialog.show()
    }

    // ==========================================
    // 【修改】备份管理：使用 Adapter 原地刷新
    // ==========================================
    private fun showBackupList(folderName: String) {
        val backupDir = File(this.backupsDir, folderName)
        if (!backupDir.exists() || backupDir.listFiles() == null) {
            toast(getString(R.string.toast_no_backups))
            return
        }

        val backups = backupDir.listFiles()

        // 【修复】确保 backups 不为 null 且不为空
        if (backups == null || backups.size == 0) {
            toast(getString(R.string.toast_no_backups))
            return
        }

        backups.sortByDescending { it.lastModified() }

        val fileList: MutableList<File> = ArrayList<File>(Arrays.asList<File?>(*backups))
        val nameList: MutableList<String?> = ArrayList<String?>()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        for (f in fileList) {
            val time = sdf.format(Date(f.lastModified()))
            if (f.isDirectory()) nameList.add("📁 " + f.getName() + "\n" + time)
            else nameList.add("📄 " + f.getName() + "\n" + time)
        }

        val adapter = ArrayAdapter<String?>(this, android.R.layout.simple_list_item_1, nameList)
        val lv = ListView(this)
        lv.setAdapter(adapter)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_backup_title))
            .setView(lv)
            .setNeutralButton(getString(R.string.btn_close), null)
            .create()

        // 点击恢复
        lv.setOnItemClickListener { _: AdapterView<*>?, _: View?, pos: Int, _: Long ->
            restoreBackup(fileList.get(pos))
            dialog.dismiss()
        }

        // 【修复部分】长按菜单：包含删除和重命名
        lv.setOnItemLongClickListener { _: AdapterView<*>?, _: View?, pos: Int, _: Long ->
            val target = fileList.get(pos)
            val ops = arrayOf<String?>(
                getString(R.string.menu_del_backup),
                getString(R.string.menu_rename)
            )

            AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.title_manage_prefix) + target.getName())
                .setItems(ops) { _: DialogInterface?, w: Int ->
                    if (w == 0) { // 删除
                        deleteRecursive(target)
                        fileList.removeAt(pos)
                        nameList.removeAt(pos)
                        adapter.notifyDataSetChanged()
                        toast(getString(R.string.toast_deleted))
                    } else { // 重命名
                        val input = EditText(this@MainActivity)
                        input.setText(target.getName())
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(getString(R.string.title_rename))
                            .setView(input)
                            .setPositiveButton(
                                getString(R.string.btn_confirm)
                            ) { _: DialogInterface?, _: Int ->
                                val newName = input.getText().toString().trim { it <= ' ' }
                                if (!newName.isEmpty()) {
                                    val newFile = File(target.getParent(), newName)
                                    if (target.renameTo(newFile)) {
                                        fileList.set(pos, newFile)
                                        // 重新格式化显示名
                                        val time = SimpleDateFormat(
                                            "yyyy-MM-dd HH:mm:ss",
                                            Locale.getDefault()
                                        ).format(
                                            Date(newFile.lastModified())
                                        )
                                        val prefix = if (newFile.isDirectory()) "📁 " else "📄 "
                                        nameList.set(pos, prefix + newName + "\n" + time)

                                        adapter.notifyDataSetChanged()
                                        toast(getString(R.string.toast_renamed))
                                    } else {
                                        toast(getString(R.string.toast_rename_failed))
                                    }
                                }
                            }.show()
                    }
                }.show()
            true
        }

        dialog.show()
    }

    // 【插入/替换在这里】
    // 替换原本的 restoreBackup 方法
    private fun restoreBackup(backupFile: File) {
        try {
            // 分支 A: 恢复玩家数据 (文件夹)
            if (backupFile.isDirectory()) {
                val uniqueId = System.currentTimeMillis().toString()

                // 恢复的目标目录
                val restoreWorkDir = File(this.worksDir, "working_db_restore_" + uniqueId)

                deleteRecursive(restoreWorkDir)
                // [修改前] copyDirectory(backupFile, restoreWorkDir);
                // [修改后] 加速
                smartCopy(backupFile, restoreWorkDir)

                // 净化锁文件
                File(restoreWorkDir, "LOCK").delete()
                File(restoreWorkDir, "LOG").delete()
                File(restoreWorkDir, "LOG.old").delete()

                currentWorkingDbPath = restoreWorkDir.getAbsolutePath()

                // 读取 LevelDB
                val db = PlayerDbManager(currentWorkingDbPath!!)
                val data = db.readLocalPlayer()
                db.close()

                // === 【核心修复】 ===
                // 旧代码: String json = BedrockParser.parseBytes(data);
                // 新代码: 直接接收 JsonObject，不再经过 String 转换，防OOM
                rootNbtData = parseBytes(data)

                isEditingPlayer = true

                // 注意：旧代码还有一行 JsonParser.parseString... 现在不需要了
                updateAdapter(rootNbtData)

                if (tvCurrentPath != null) tvCurrentPath!!.setText(getString(R.string.path_restored_player))
                toast(getString(R.string.toast_player_restored))
            } else {
                val targetFile = File(this.worksDir, LEVEL_DAT_NAME)
                copyFileNative(backupFile, targetFile)
                currentWorkingFileOrDir = targetFile.getAbsolutePath()

                // parseLevelDat 我们之前已经修过了，直接调用即可
                parseLevelDat()

                toast(getString(R.string.toast_level_restored))
            }
        } catch (e: Exception) {
            toast(getString(R.string.err_restore_failed_prefix) + e.message)
        }
    }

    // ==========================================
    // 业务逻辑 (保持不变)
    // ==========================================
    private fun copyLevelDatOut(folder: String) {
        // 1. 【保存现场】不管之前在编辑谁，先存个档到内存
        saveCurrentSessionToMemory()

        // 2. 检查是不是切换了存档文件夹
        val isNewWorld = (lastLoadedWorldFolder == null || lastLoadedWorldFolder != folder)

        if (isNewWorld) {
            // 如果是新存档，必须彻底重置所有缓存
            resetCurrentSession()
            lastLoadedWorldFolder = folder
        } else {
            // 如果还是当前存档，尝试直接恢复 level.dat 的会话
            // 这样你未保存的修改就能找回来
            if (tryRestoreSession("level.dat")) {
                return  // 恢复成功，直接结束，不用读文件！
            }
        }

        showProgressDialog(getString(R.string.msg_loading), getString(R.string.msg_moving))

        Thread {
            try {
                val src = currentWorldsPath + folder + "/level.dat"
                val destFile = File(this.worksDir, LEVEL_DAT_NAME)

                if (destFile.exists()) destFile.delete()

                var success = copyFileNative(File(src), destFile)

                if (!success && checkShizukuAvailable()) {
                    val bridgeDir = File(BRIDGE_ROOT)
                    if (!bridgeDir.exists()) bridgeDir.mkdirs()
                    val bridgeFile: String = BRIDGE_ROOT + "level.dat"

                    runShizukuCmd(
                        arrayOf("sh", "-c", "mkdir -p \"$BRIDGE_ROOT\"")
                    ).waitFor()
                    runShizukuCmd(
                        arrayOf("sh", "-c", "cp \"$src\" \"$bridgeFile\"")
                    ).waitFor()
                    runShizukuCmd(
                        arrayOf("sh", "-c", "chmod 777 \"$bridgeFile\"")
                    ).waitFor()

                    val bF = File(bridgeFile)
                    if (bF.exists()) {
                        copyFile(bF, destFile)
                        success = true
                    }
                }

                if (success) {
                    currentWorkingFileOrDir = destFile.getAbsolutePath()

                    runOnUiThread {
                        dismissProgressDialog()
                        // 【核心修复】强制切换到世界模式
                        isEditingPlayer = false
                        currentTargetKey = null // level.dat 没有 Key

                        // 此时 isEditingPlayer 已经是 false 了，parseLevelDat 会正确读取文件
                        parseLevelDat()
                        toast(getString(R.string.toast_loaded) + "level.dat")
                    }
                } else {
                    throw Exception(getString(R.string.err_read_file_not_generated))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dismissProgressDialog()
                    toast(e.toString())
                }
            }
        }.start()
    }

    private fun parseLevelDat() {
        // 1. 保存现场
        saveCurrentSessionToMemory()

        showProgressDialog(getString(R.string.msg_reading), getString(R.string.msg_reading_details))
        Thread {
            try {
                val newJson: JsonObject?

                if (isEditingPlayer) {
                    // === 读数据库 Key ===
                    if (currentWorkingDbPath == null) throw Exception(getString(R.string.err_internal_player_path))
                    val lockFile = File(currentWorkingDbPath, "LOCK")
                    if (lockFile.exists()) lockFile.delete()

                    val dbManager = PlayerDbManager(currentWorkingDbPath!!)
                    val data: ByteArray?
                    if (currentTargetKey != null) {
                        data = dbManager.readSpecificKey(currentTargetKey!!)
                    } else {
                        data = dbManager.readLocalPlayer()
                        currentTargetKey = "~local_player"
                    }
                    dbManager.close()
                    newJson = parseBytes(data)
                } else {
                    // === 读 Level.dat ===
                    var path = currentWorkingFileOrDir
                    if (path == null) path = File(this.worksDir, LEVEL_DAT_NAME).getAbsolutePath()
                    if (!File(path).exists()) throw Exception(getString(R.string.err_work_file_missing))

                    newJson = BedrockParser.parse(path!!)
                }

                runOnUiThread {
                    dismissProgressDialog()
                    try {
                        rootNbtData = newJson

                        // 更新缓存
                        if (isEditingPlayer) nbtDataCache.put(currentTargetKey, rootNbtData)
                        else nbtDataCache.put("level.dat", rootNbtData)

                        // 重置视图
                        navigationStack.clear()
                        pathStack.clear()
                        scrollPositionStack.clear()
                        currentListData = rootNbtData

                        updateAdapter(rootNbtData)
                        updatePathTitle()

                        // 【核心修复】补回这行代码！
                        // 如果当前加载的是 level.dat，就尝试提取名字和种子显示在标题栏
                        if (!isEditingPlayer) {
                            updateWorldInfoCard(rootNbtData)
                        }

                        val targetName =
                            (if (isEditingPlayer && currentTargetKey != null) currentTargetKey else "Level.dat")!!
                        toast(getString(R.string.toast_refreshed_prefix) + targetName)
                    } catch (uiEx: Exception) {
                        toast(getString(R.string.err_display_data) + uiEx)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dismissProgressDialog()
                    toast("Refresh Failed: " + e.message)
                }
            }
        }.start()
    }


    // 1. 加载玩家数据 (带 Blocktopograph 拦截 + 路径合并)
    // 1. 加载玩家数据 (加强版：自动清理僵尸锁)
    private fun loadPlayerData(folderName: String?, onSuccess: Runnable?) {
        // === 1. 参数校验 ===
        if (folderName == null || folderName.trim { it <= ' ' }
                .isEmpty() || folderName == getString(R.string.hint_select_world)) {
            toast(getString(R.string.toast_error_archive_name_is_empty))
            return
        }

        // === 2. 【新增】会话保存与快速恢复 ===
        // 在加载新数据前，保存当前工作现场
        saveCurrentSessionToMemory()

        // 如果没有指定成功回调（说明是单纯的切换/重新进入），且内存里有缓存，直接恢复，避免 IO 操作
        if (onSuccess == null && tryRestoreSession("~local_player")) {
            return
        }

        // === 3. 初始化加载状态 ===
        val canUseShizuku = checkShizukuAvailable()
        showProgressDialog(getString(R.string.title_read_player), getString(R.string.msg_init_player))

        Thread {
            try {
                // === 4. 路径构建 ===
                val base = File(currentWorldsPath)
                val worldDir = File(base, folderName)
                // 这里的路径构建更安全，避免多余的斜杠
                val srcPath = File(worldDir, "db").getAbsolutePath()
                val srcDirFile = File(srcPath)

                // === 5. 判断模式 (Native vs Shizuku) ===
                var useNative = false
                // 如果文件夹存在、可读且能列出文件，优先使用原生方式（速度快、无Hack）
                if (srcDirFile.exists() && srcDirFile.canRead() && srcDirFile.listFiles() != null) {
                    useNative = true
                } else {
                    // 如果原生不可用，必须依赖 Shizuku
                    if (!canUseShizuku) {
                        throw Exception(getString(R.string.toast_err_permission_denied_shizuku))
                    }
                }

                // === 6. 主动清除源头僵尸锁 ===
                try {
                    if (useNative) {
                        val lock = File(srcDirFile, "LOCK")
                        if (lock.exists()) lock.delete()
                    } else {
                        val pCheck = runShizukuCmd(
                            arrayOf("sh", "-c", "ls \"$srcPath/LOCK\"")
                        )
                        if (pCheck.waitFor() == 0) {
                            runShizukuCmd(
                                arrayOf("sh", "-c", "rm -f \"$srcPath/LOCK\"")
                            ).waitFor()
                            runOnUiThread { toast(getString(R.string.toast_lock_delete)) }
                        }
                    }
                } catch (_: Exception) {
                }

                // === 7. 准备工作目录 ===
                val uniqueId = System.currentTimeMillis().toString()
                val workDir = File(this.worksDir, "working_db_" + uniqueId)
                val appPrivatePath = workDir.getAbsolutePath()

                // === 8. 搬运流程 ===
                if (useNative) {
                    // 【分支A】原生 Java 复制
                    if (!workDir.exists()) workDir.mkdirs()
                    // 使用 smartCopy (假设是优化后的复制方法)
                    smartCopy(srcDirFile, workDir)
                } else {
                    // 【分支B】Shizuku 复制
                    val bridgePath: String = BRIDGE_ROOT + "load_db_" + uniqueId

                    // 准备中转站
                    runShizukuCmd(
                        arrayOf("sh", "-c", "mkdir -p \"$BRIDGE_ROOT\"")
                    ).waitFor()
                    createNoMedia()

                    runShizukuCmd(
                        arrayOf("sh", "-c", "rm -rf \"$bridgePath\"")
                    ).waitFor()
                    runShizukuCmd(
                        arrayOf("sh", "-c", "mkdir -p \"$bridgePath\"")
                    ).waitFor()

                    // 带错误检查的复制命令
                    val cmd = "cp -rf \"$srcPath/.\" \"$bridgePath/\""
                    val pCopy = runShizukuCmd(arrayOf("sh", "-c", cmd))
                    val exitCode = pCopy.waitFor()

                    if (exitCode != 0) {
                        val reader = BufferedReader(InputStreamReader(pCopy.getErrorStream()))
                        val errSb = StringBuilder()
                        var line: String?
                        while ((reader.readLine().also { line = it }) != null) errSb.append(line)
                            .append("\n")
                        throw Exception("Shizuku Copy Error (" + exitCode + "):\n" + errSb + "\nSource: " + srcPath)
                    }

                    runShizukuCmd(
                        arrayOf("sh", "-c", "chmod -R 777 \"$bridgePath\"")
                    ).waitFor()
                    val bridgeDir = File(bridgePath)

                    if (!bridgeDir.exists()) {
                        throw Exception(getString(R.string.msg_failed_to_create_staging_directory) + bridgePath)
                    }
                    val files = bridgeDir.list()
                    if (files == null || files.size == 0) {
                        throw Exception(getString(R.string.msg_the_source_directory_appears_to_be_empty) + srcPath)
                    }

                    if (!workDir.exists()) workDir.mkdirs()
                    smartCopy(bridgeDir, workDir) // 从中转站复制到私有目录

                    // 清理中转站
                    runShizukuCmd(
                        arrayOf("sh", "-c", "rm -rf \"$bridgePath\"")
                    ).waitFor()
                }

                // === 9. 本地净化与读取 ===
                File(workDir, "LOCK").delete()
                File(workDir, "LOG").delete()
                File(workDir, "LOG.old").delete()

                currentWorkingDbPath = appPrivatePath

                val dbManager = PlayerDbManager(appPrivatePath)
                val data = dbManager.readLocalPlayer()
                dbManager.close()

                val playerDataObj = parseBytes(data)

                // === 10. UI 更新 (成功) ===
                runOnUiThread {
                    dismissProgressDialog()
                    // 状态重置
                    isEditingPlayer = true
                    currentTargetKey = "~local_player" // 核心修复：重置 Key

                    // 数据加载
                    rootNbtData = playerDataObj
                    // 更新缓存 (Snippet 1 特性)
                    nbtDataCache.put("~local_player", rootNbtData)

                    // 界面刷新
                    navigationStack.clear()
                    pathStack.clear()
                    scrollPositionStack.clear()

                    updateAdapter(rootNbtData)
                    updatePathTitle() // 刷新标题 (Snippet 1 特性)

                    toast(getString(R.string.toast_player_loaded_success))

                    // 执行回调
                    if (onSuccess != null) {
                        onSuccess.run()
                    }

                    // 更新路径显示
                    if (tvCurrentPath != null) tvCurrentPath!!.text = getString(
                        R.string.path_editing_player,
                        folderName
                    )
                }
            } catch (e: Exception) {
// === 11. 异常处理 ===
                runOnUiThread {
                    dismissProgressDialog()
                    // 特殊处理：数据库损坏
                    if (e.message != null && e.message!!.contains("DB_CORRUPT")) {
                        showDbRepairConfirmDialog(currentWorkingDbPath!!, folderName)
                    } else if (!useShizuku) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(getString(R.string.title_load_failed))
                            .setMessage(
                                getString(R.string.err_direct_access_failed) + "\n\n" + getFullStackTrace(
                                    e
                                )
                            )
                            .setPositiveButton(
                                getString(R.string.btn_open_settings)
                            ) { _: DialogInterface?, _: Int ->
                                // 检查版本
                                if (Build.VERSION.SDK_INT >= 30) {
                                    val intent =
                                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    intent.data = "package:$packageName".toUri()
                                    startActivity(intent)
                                } else {
                                    // Android 10 及以下使用传统存储设置
                                    val intent =
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    intent.data = "package:$packageName".toUri()
                                    startActivity(intent)
                                }
                            }
                            .setNegativeButton(getString(R.string.btn_understood), null)
                            .show()
                    } else {
                        dismissProgressDialog()
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(getString(R.string.title_load_failed))
                            .setMessage(
                                getString(R.string.msg_load_failed_advice) + getFullStackTrace(
                                    e
                                )
                            )
                            .setPositiveButton(
                                getString(R.string.btn_copy_error)
                            ) { _: DialogInterface?, _: Int ->
                                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Error", getFullStackTrace(e))
                                cm.setPrimaryClip(clip)
                                toast(getString(R.string.toast_copied))
                            }
                            .setNegativeButton(getString(R.string.btn_understood), null)
                            .show()
                    }
                }
            }
        }.start()
    }

    // 2. 保存回写 (修复版：带更详细的错误反馈)
    private fun saveAndPushBack(dataToSave: JsonObject?, folder: String) {
        showProgressDialog(getString(R.string.msg_saving), getString(R.string.msg_processing))
        Thread {
            try {
                // 1. 创建备份
                createBackup(folder)

                // 2. 判断当前模式
                if (isEditingPlayer) {
                    // === 保存玩家数据 ===
                    val dbPath = currentWorkingDbPath ?: throw Exception("Error: DbPath is NULL")
                    val oldWorkDir = File(dbPath)
                    if (!oldWorkDir.exists()) throw Exception("Error: WorkDir lost ($dbPath)")
                    if (!oldWorkDir.exists()) throw Exception("Error: WorkDir lost (" + currentWorkingDbPath + ")")
                    if (currentTargetKey == null) {
                        // 如果万一为空，兜底设为本地玩家，防止写飞
                        currentTargetKey = "~local_player"
                    }

                    // 创建新的唯一目录
                    val uniqueId = System.currentTimeMillis().toString()
                    val newWorkDir = File(this.worksDir, "working_db_" + uniqueId)

                    // 复制旧环境
                    // [修改前] copyDirectory(oldWorkDir, newWorkDir);
                    // [修改后] 加速
                    smartCopy(oldWorkDir, newWorkDir)
                    // 删除旧锁
                    File(newWorkDir, "LOCK").delete()
                    File(newWorkDir, "LOG").delete()
                    File(newWorkDir, "LOG.old").delete()

                    // 【核心改动1】JsonObject -> Bytes -> LevelDB (在后台线程序列化)
                    val bytes = writeToBytes(dataToSave)
                    val db = PlayerDbManager(newWorkDir.getAbsolutePath())
                    db.writeSpecificKey(currentTargetKey!!, bytes)
                    db.close()

                    // 回写到游戏目录 (Bridge -> Shizuku -> Data)
                    val bridgeSave: String = BRIDGE_ROOT + "save_db_" + uniqueId
                    runShizukuCmd(
                        arrayOf("sh", "-c", "mkdir -p \"$BRIDGE_ROOT\"")
                    ).waitFor()
                    createNoMedia()
                    runShizukuCmd(
                        arrayOf("sh", "-c", "rm -rf \"$bridgeSave\"")
                    ).waitFor()
                    // [修改前] copyDirectory(newWorkDir, new File(bridgeSave));
                    // [修改后] 加速
                    smartCopy(newWorkDir, File(bridgeSave))

                    val mcDbPath = currentWorldsPath + folder + "/db/"
                    runShizukuCmd(
                        arrayOf("sh", "-c", "cp -rf \"$bridgeSave/.\" \"$mcDbPath\"")
                    ).waitFor()
                    runShizukuCmd(
                        arrayOf("sh", "-c", "rm -rf \"$bridgeSave\"")
                    ).waitFor()

                    // 清理
                    deleteRecursive(oldWorkDir)
                    currentWorkingDbPath = newWorkDir.getAbsolutePath()

                    runOnUiThread {
                        dismissProgressDialog()
                        toast(getString(R.string.toast_player_saved))
                    }
                } else {
                    // === 保存 Level.dat ===

                    // 1. 确认路径

                    if (currentWorkingFileOrDir == null) {
                        val f = File(this.worksDir, LEVEL_DAT_NAME)
                        currentWorkingFileOrDir = f.getAbsolutePath()
                    }

                    // 【核心改动2】JsonObject -> 文件 (在后台线程序列化)
                    write(dataToSave, currentWorkingFileOrDir)

                    // 3. 准备回写到游戏
                    val filePath = currentWorkingFileOrDir ?: throw Exception("Error: File path is NULL")
                    val workingFile = File(filePath)
                    val bridgeFile: String = BRIDGE_ROOT + "level.dat"
                    File(BRIDGE_ROOT).mkdirs()
                    copyFile(workingFile, File(bridgeFile))

                    val targetPath = currentWorldsPath + folder + "/level.dat"

                    // 4. 尝试原生覆盖
                    var success = copyFileNative(workingFile, File(targetPath))

                    // 5. 原生失败则用 Shizuku
                    if (!success && checkShizukuAvailable()) {
                        runShizukuCmd(
                            arrayOf("sh", "-c", "cp \"$bridgeFile\" \"$targetPath\"")
                        ).waitFor()
                        success = true
                    }

                    if (success) {
                        runOnUiThread {
                            dismissProgressDialog()
                            toast(getString(R.string.toast_level_dat_saved))
                        }
                    } else {
                        throw Exception(getString(R.string.msg_write_to_game_dir_failed))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dismissProgressDialog()
                    val msg = e.message

                    // 【新增】区分 Shizuku 禁用状态和实际错误
                    if (!useShizuku) {
                        // 用户禁用了 Shizuku，提示直接访问失败
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(getString(R.string.err_save_failed))
                            .setMessage(getString(R.string.err_direct_access_failed) + "\n\n" + msg)
                            .setPositiveButton(
                                getString(R.string.btn_open_settings)
                            ) { _: DialogInterface?, _: Int ->
                                if (Build.VERSION.SDK_INT >= 30) {
                                    val intent =
                                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    intent.data = "package:$packageName".toUri()
                                    startActivity(intent)
                                } else {
                                    // Android 10 及以下使用应用详情设置页
                                    val intent =
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    intent.data = "package:$packageName".toUri()
                                    startActivity(intent)
                                }
                            }
                            .setNegativeButton(getString(R.string.btn_cancel), null)
                            .show()
                    } else {
                        // 原有 Shizuku 错误处理
                        if (msg != null && msg.contains("Shizuku")) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle(getString(R.string.title_shizuku_error))
                                .setMessage(msg)
                                .setPositiveButton(
                                    getString(R.string.btn_open_shizuku)
                                ) { _: DialogInterface?, _: Int ->
                                    try {
                                        val intent =
                                            getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                        if (intent != null) {
                                            startActivity(intent)
                                        }
                                    } catch (_: Exception) {
                                        toast(getString(R.string.toast_shizuku_not_installed))
                                    }
                                }
                                .setNegativeButton(getString(R.string.btn_cancel), null)
                                .show()
                        } else {
                            // 普通错误
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle(getString(R.string.err_save_failed))
                                .setMessage(getFullStackTrace(e))
                                .setPositiveButton(getString(R.string.btn_confirm), null)
                                .show()
                        }
                    }
                }
            }
        }.start()
    }

    private fun createBackup(folderName: String) {
        try {
            // 1. 确保备份目录存在
            val backupRoot = File(this.backupsDir, folderName)
            if (!backupRoot.exists()) backupRoot.mkdirs()

            val timeStamp =
                SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())

            if (isEditingPlayer) {
                // 玩家模式：检查源目录
                currentWorkingDbPath?.let { dbPath ->
                    val srcDb = File(dbPath)
                    // 【加固】如果源目录还存在，才备份
                    if (srcDb.exists() && srcDb.isDirectory) {
                        val dstDb = File(backupRoot, "db_$timeStamp")
                        copyDirectory(srcDb, dstDb)
                    }
                }
            } else {
                // 世界模式：检查源文件
                // 【加固】使用 getWorksDir() 确保父目录存在
                val src = File(this.worksDir, LEVEL_DAT_NAME)
                if (src.exists() && src.isFile()) {
                    val dst = File(backupRoot, "level_" + timeStamp + ".dat")
                    copyFileNative(src, dst)
                }
            }
        } catch (e: Exception) {
            // 备份是辅助功能，失败了也不要打断主流程，打印日志即可
            Log.e("MainActivity", "操作失败", e)
        }
    }

    // ==========================================
    // 列表显示与路径选择
    // ==========================================
    // 列表显示与路径选择 (支持显示真实地图名)
    private fun showCustomPathDialog() {
        val input = EditText(this)
        input.setText(currentWorldsPath)
        input.hint = "/storage/emulated/0/..."

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_custom_path_title))
            .setMessage(getString(R.string.msg_custom_path))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                var p = input.text.toString().trim()
                if (p.isEmpty()) return@setPositiveButton
                // 去除末尾可能多余的斜杠，为了下面获取 getName() 准确
                if (p.endsWith("/")) p = p.substring(0, p.length - 1)

                val target = File(p)
                val checkLevel = File(target, "level.dat")
                val checkDb = File(target, "db")

                var hasLevel = checkLevel.exists()
                var hasDb = checkDb.exists() && checkDb.isDirectory

                // Shizuku 二次检查
                if (checkShizukuAvailable() && (!hasLevel || !hasDb)) {
                    try {
                        if (!hasLevel && runShizukuCmd(
                                arrayOf("sh", "-c", "ls \"${checkLevel.absolutePath}\"")
                            ).waitFor() == 0
                        ) hasLevel = true
                        if (!hasDb && runShizukuCmd(
                                arrayOf("sh", "-c", "ls -d \"${checkDb.absolutePath}\"")
                            ).waitFor() == 0
                        ) hasDb = true
                    } catch (e: Exception) {
                        Log.w(ContentValues.TAG, "Shizuku db check failed: ${e.message}")
                    }
                }
                if (hasLevel || hasDb) {
                    // --- 命中具体存档逻辑 ---
                    if (hasLevel && hasDb) {
                        // 1. 设置 Base 路径为该存档的【上一级目录】
                        // 这样 copyLevelDatOut 拼接路径时才正确
                        val parentDir = target.parentFile
                        if (parentDir != null) {
                            currentWorldsPath = "${parentDir.absolutePath}/"
                        }

                        // 2. 获取存档文件夹名
                        val folderName = target.name

                        // 3. 更新 UI
                        etWorldName!!.setText(folderName)
                        toast(getString(R.string.toast_direct_load) + folderName)

                        // 4. 【核心改动】直接调用加载逻辑，不弹列表！
                        copyLevelDatOut(folderName)
                    } else {
                        // 存档残缺报错
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(getString(R.string.title_archive_damaged))
                            .setMessage(
                                String.format(
                                    getString(R.string.msg_archive_damaged),
                                    if (hasLevel) "✅" else "❌",
                                    if (hasDb) "✅" else "❌"
                                )
                            )
                            .setPositiveButton(getString(R.string.btn_confirm), null)
                            .show()
                    }
                } else {
                    // --- 未命中具体存档，视为父目录列表模式 ---
                    if (!p.endsWith("/")) p += "/" // 补回斜杠

                    currentWorldsPath = p
                    toast(getString(R.string.toast_path_updated))
                    showWorldSelector()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showWorldSelector() {
        showProgressDialog(getString(R.string.msg_scanning), getString(R.string.msg_parsing_archive_information))

        Thread {
            try {
                // 1. 获取文件夹列表
                val rawFolders: MutableList<String> = ArrayList()

                // 原生获取
                val dir = File(currentWorldsPath)
                if (dir.exists() && dir.canRead()) {
                    val files = dir.listFiles()
                    if (files != null) for (f in files) if (f.isDirectory) rawFolders.add(f.name)
                }

                // Shizuku 获取
                if (rawFolders.isEmpty() && checkShizukuAvailable()) {
                    val p = runShizukuCmd(arrayOf("sh", "-c", "ls \"$currentWorldsPath\""))
                    val r = BufferedReader(InputStreamReader(p.inputStream))
                    var line: String?
                    while (r.readLine().also { line = it } != null) {
                        line?.trim()?.let { trimmed ->
                            if (trimmed.isNotEmpty()) rawFolders.add(trimmed)
                        }
                    }
                    p.waitFor()
                }

                // 2. 过滤有效存档并读取名字
                val displayList: MutableList<String> = ArrayList() // 显示用的 (名字+ID)
                val folderList: MutableList<String> = ArrayList() // 逻辑用的 (ID)

                for (name in rawFolders) {
                    val fullPath = currentWorldsPath + name
                    val checkLevel = File(fullPath, "level.dat")
                    val checkDb = File(fullPath, "db")

                    var isGood = false

                    // 检查有效性
                    if (checkLevel.exists() && checkDb.exists()) isGood = true
                    else if (checkShizukuAvailable()) {
                        try {
                            val code1 = runShizukuCmd(arrayOf("sh", "-c", "ls \"${checkLevel.absolutePath}\"")).waitFor()
                            // ls -d 检查文件夹
                            val code2 = runShizukuCmd(arrayOf("sh", "-c", "ls -d \"${checkDb.absolutePath}\"")).waitFor()
                            if (code1 == 0 && code2 == 0) isGood = true
                        } catch (e: Exception) {
                            Log.w(ContentValues.TAG, "Shizuku check failed: ${e.message}")
                        }
                    }

                    if (isGood) {
                        // 【核心修改】读取真实名字
                        var realName = getWorldRealName(name)
                        if (realName == null) realName = getString(R.string.msg_unknown_world)

                        // 格式： "我的世界\n-w14OU6m5Gk="
                        displayList.add("$realName\n$name")
                        folderList.add(name)
                    }
                }

                // 转换为数组
                val displayArr = displayList.toTypedArray()

                runOnUiThread {
                    dismissProgressDialog()
                    if (displayArr.isEmpty()) {
                        toast(getString(R.string.err_no_saves_detail))
                        return@runOnUiThread
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(
                            String.format(
                                getString(R.string.dialog_select_archive_title),
                                displayArr.size
                            )
                        )
                        .setItems(
                            displayArr
                        ) { _: DialogInterface?, i: Int ->
                            // 点击时，从 folderList 里取纯净的文件夹名
                            val realFolder = folderList.get(i)

                            // 更新输入框，只显示文件夹名 (或者你想显示中文名也可以，但逻辑要改)
                            // 这里建议输入框里还是显示名字+ID，或者只显示ID
                            // 为了兼容之前的逻辑，这里暂时填入 ID，或者你可以把 UI 改成显示中文
                            etWorldName!!.setText(realFolder)

                            // 触发加载
                            copyLevelDatOut(realFolder)
                        }.show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dismissProgressDialog()
                    toast(e.toString())
                }
            }
        }.start()
    }

    private fun showPathSelector() {
        val options = arrayOf<String?>(
            getString(R.string.path_standard),
            getString(R.string.path_legacy),
            getString(R.string.path_custom),
            getString(R.string.path_saf) // 【新增】SAF 选项
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_path_title))
            .setItems(options) { _: DialogInterface?, w: Int ->
                if (w == 0) {
                    currentWorldsPath = PATH_STANDARD
                    toast(getString(R.string.toast_switched_std))
                    showWorldSelector()
                } else if (w == 1) {
                    currentWorldsPath = PATH_LEGACY
                    toast(getString(R.string.toast_switched_legacy))
                    showWorldSelector()
                } else if (w == 2) {
                    showCustomPathDialog()
                } else if (w == 3) {
                    // 【新增】SAF 路径选择
                    openSafPathSelector()
                }
            }
            .show()
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                // 【新增】跳转前先提示用户
                toast(getString(R.string.toast_storage_perm))

                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = "package:$packageName".toUri()
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(ContentValues.TAG, "Failed to open settings: ${e.message}")
                    toast("无法打开系统设置")
                }
            }
        }
    }

    @Throws(Exception::class)
    private fun registerListener() {
        Class.forName("rikka.shizuku.Shizuku")
    }

    @Throws(Exception::class)
    private fun runShizukuCmd(cmd: Array<String?>): Process {
        // 【新增】执行前检查 Shizuku 是否可用
        if (!checkShizukuAvailable()) {
            throw Exception(getString(R.string.err_shizuku_not_running))
        }

        val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
        var m: Method? = null
        for (mm in shizukuClass.getDeclaredMethods()) {
            if (mm.getName() == "newProcess" && mm.getParameterTypes().size == 3) {
                m = mm
                break
            }
        }
        if (m == null) for (mm in shizukuClass.getMethods()) if (mm.getName() == "newProcess" && mm.getParameterTypes().size == 3) {
            m = mm
            break
        }
        if (m == null) throw Exception("Shizuku API Error")
        m.setAccessible(true)

        try {
            return m.invoke(null, cmd, null, null) as Process
        } catch (e: Exception) {
            val msg = e.message
            if (msg != null && msg.contains("binder haven't been received")) {
                throw Exception(getString(R.string.err_shizuku_binder_not_ready))
            }
            throw e
        }
    }

    private fun checkShizukuAvailable(): Boolean {
        if (!useShizuku) {
            return false
        }

        try {
            val c = Class.forName("rikka.shizuku.Shizuku")

            // 安全转换 Boolean
            val p = c.getMethod("pingBinder")
            val pingResult = p.invoke(null) as Boolean?
            if (pingResult == null || !pingResult) return false

            // 安全转换 Integer
            val ch = c.getMethod("checkSelfPermission")
            val permission = ch.invoke(null) as Int?
            return permission != null && permission == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            return false
        }
    }

    private fun checkShizukuReady(): Boolean {
        // 【新增】禁用 Shizuku 时直接返回 true（表示"已准备好"走系统路径）
        if (!useShizuku) {
            return true
        }

        if (checkShizukuAvailable()) return true
        try {
            val c = Class.forName("rikka.shizuku.Shizuku")
            c.getMethod("requestPermission", Int::class.javaPrimitiveType).invoke(null, 0)
        } catch (e: Exception) {
            Log.w(ContentValues.TAG, "Shizuku request permission failed: " + e.message)
        }
        return false
    }

    @Throws(Exception::class)
    private fun copyFile(src: File?, dst: File?) {
        val `in` = FileInputStream(src)
        val out = FileOutputStream(dst)
        val b = ByteArray(1024)
        var l: Int
        while ((`in`.read(b).also { l = it }) > 0) out.write(b, 0, l)
        `in`.close()
        out.close()
    }

    private fun copyFileNative(src: File?, dst: File?): Boolean {
        try {
            copyFile(src, dst)
            return true
        } catch (_: Exception) {
            return false
        }
    }

    // 通用递归复制 (排除 LOCK)
    @Throws(Exception::class)
    private fun copyDirectory(source: File, target: File) {
        if (source.isDirectory()) {
            if (!target.exists()) target.mkdirs()
            val children = source.list()
            if (children != null) {
                for (child in children) {
                    // 【仅过滤锁文件】其他所有文件(.sst, .ldb, .log)通通带走！
                    if (child == "LOCK") continue
                    copyDirectory(File(source, child), File(target, child))
                }
            }
        } else {
            copyFile(source, target)
        }
    }

    // 递归删除 (加强健壮性版)
    private fun deleteRecursive(f: File?) {
        if (f == null || !f.exists()) return

        if (f.isDirectory()) {
            val children = f.listFiles()
            // 【修复】必须要判断 children 是否为 null
            if (children != null) {
                for (c in children) {
                    deleteRecursive(c)
                }
            }
        }
        // 最后删除文件本身或空目录
        f.delete()
    }

    private fun enterFolder(key: String?, content: JsonObject?, isListMode: Boolean) {
        // 1. 在进入下一级之前，记录当前列表滚动到了第几行
        val currentPos = nbtListView!!.getFirstVisiblePosition()
        scrollPositionStack.push(currentPos) // 压入栈

        // 2. 原有逻辑：压入数据、压入路径
        navigationStack.push(nbtAdapter!!.data)
        pathStack.push(key)

        // 3. 刷新列表显示下一级内容
        updateAdapter(content)
        updatePathTitle()
        if (isListMode) toast(getString(R.string.toast_enter_list) + key)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val sidebarContainer = findViewById<View?>(R.id.custom_sidebar_container)
        if (sidebarContainer != null && sidebarContainer.isVisible) {
            sidebarContainer.visibility = View.GONE
            return
        }
        // 1. 先检查有没有子文件夹可以退
        if (!navigationStack.isEmpty()) {
            val parent = navigationStack.pop()
            pathStack.pop()

            // 2. 刷新回上一级的数据
            updateAdapter(parent)
            updatePathTitle()

            // 3. 【核心新增】恢复之前的滚动位置
            if (!scrollPositionStack.isEmpty()) {
                val lastPos = scrollPositionStack.pop()!!
                // 必须要 post 执行，因为 updateAdapter 刚刚才 reset 了列表
                nbtListView!!.post {
                    nbtListView!!.setSelection(lastPos) // 瞬间跳回原来的位置
                }
            }

            return  // 结束方法，不退出应用
        }

        // 4. 如果已经是根目录了，但还在全屏模式，则退出全屏
        if (isFullScreen) {
            toggleFullScreen(false)
            return  // 处理完毕，结束
        }

        // 5. 既在根目录，又不是全屏，那就真的退出了
        super.onBackPressed()
    }

    private fun updateAdapter(data: JsonObject?) {
        if (isTreeMode) {
            // === 树状图模式 ===
            nbtTreeAdapter = NbtTreeAdapter(this, data)
            nbtTreeAdapter!!.setViewMode(currentViewMode)
            nbtListView!!.adapter = nbtTreeAdapter
        } else {
            // === 列表模式 ===
            currentListData = data

            nbtAdapter = NbtAdapter(this, data)
            nbtAdapter!!.setViewMode(currentViewMode)

            // 【核心新增】计算上下文路径
            var grandParent: String? = ""
            if (pathStack.size >= 2) {
                grandParent = pathStack[pathStack.size - 2] // 倒数第二个是爷爷节点
            }
            // 传入上下文
            nbtAdapter!!.setPathContext(grandParent)

            nbtListView!!.adapter = nbtAdapter
        }
    }

    // 更新顶部路径标题 (修复树状图标题显示错误的 Bug)
    private fun updatePathTitle() {
        var titleText: String

        if (isTreeMode) {
            // === 树状图模式 ===
            if (isEditingPlayer) {
                // 如果是玩家模式
                if (currentTargetKey != null && currentTargetKey != "~local_player") {
                    titleText =
                        getString(R.string.title_dendrogram) + currentTargetKey // 联机玩家/地图/村庄
                } else {
                    titleText = getString(R.string.title_treemap_player_data) // 本地玩家
                }
            } else {
                // 如果不是玩家模式，那就是 level.dat
                titleText = getString(R.string.title_treemap_world_data)
            }
        } else {
            // === 列表模式 ===
            if (pathStack.isEmpty()) {
                if (isEditingPlayer) {
                    if (currentTargetKey != null && currentTargetKey != "~local_player") {
                        titleText = getString(R.string.title_current) + currentTargetKey
                    } else {
                        titleText = getString(R.string.path_current_player)
                    }
                } else {
                    titleText = getString(R.string.path_current_level)
                }
            } else {
                // 子目录
                val sb = StringBuilder(getString(R.string.path_prefix))
                for (p in pathStack) {
                    sb.append(p).append("/")
                }
                titleText = sb.toString()
            }
        }

        // 更新 UI
        if (tvCurrentPath != null) tvCurrentPath!!.setText(titleText)
        if (tvFullscreenPath != null) tvFullscreenPath!!.setText(titleText)
    }

    private fun showEditValueDialog(key: String, item: JsonObject) {
        val type = item.get("t").asInt

        if (type == 9 || type == 10) {
            toast(getString(R.string.toast_click_detail))
            return
        }

        val input = EditText(this)

        if (item.has("v")) {
            val v = item.get("v")
            if (v.isJsonArray) {
                input.setText(v.toString())
            } else {
                input.setText(v.asString)
            }
        }

        // ========== 判断是否显示自动填充 ==========
        var showAutocomplete = false
        var detectedType = "all"

        val parent = if (pathStack.isNotEmpty()) pathStack.peek() else ""
        val grandParent = if (pathStack.size >= 2) pathStack[pathStack.size - 2] else ""

        if (key == "Name" || key == "id" || key == "name" ||
            key == "Id" || key == "AttributeName"
        ) {
            detectedType = detectAutocompleteType(parent, grandParent)
            showAutocomplete = true
        }

        if ((type == 1 || type == 2) && key == "Id") {
            val dt = detectAutocompleteType(parent, grandParent)
            if (dt != "all") {
                detectedType = dt
                showAutocomplete = true
            }
        }

        // ========== 构建对话框 ==========
        val builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.title_edit_type_key, getTypeName(type), key))

        val finalDataType = detectedType

        if (showAutocomplete) {
            // ===== 带自动填充按钮的布局 =====
            val layout = LinearLayout(this)
            layout.orientation = LinearLayout.VERTICAL
            layout.addView(input)

            val btnAutocomplete = Button(this)
            btnAutocomplete.text = getString(R.string.btn_choose, getTypeLabel(finalDataType))
            btnAutocomplete.setTextColor("#2196F3".toColorInt())
            btnAutocomplete.setBackgroundColor(Color.TRANSPARENT)
            btnAutocomplete.setOnClickListener {
                showAutocompleteDialog(input, finalDataType)
            }
            layout.addView(btnAutocomplete)

            builder.setView(layout)
        } else {
            // ===== 普通字段，只有输入框 =====
            builder.setView(input)
        }

        // ========== 保存按钮 ==========
        builder.setPositiveButton(
            getString(R.string.btn_save_short)
        ) { _: DialogInterface?, _: Int ->
            try {
                val value = input.text.toString().trim()

                if (type in 1..3) {
                    item.addProperty("v", value.toInt())
                } else if (type == 4) {
                    item.addProperty("v", value.toLong())
                } else if (type in 5..6) {
                    item.addProperty("v", value.toDouble())
                } else if (type == 8) {
                    item.addProperty("v", value)
                } else if (type == 7 || type == 11 || type == 12) {
                    val parsed = JsonParser.parseString(value)
                    item.add("v", parsed.asJsonArray)
                }

                if (!isTreeMode) {
                    nbtAdapter?.refreshKeys()
                } else {
                    nbtTreeAdapter?.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                toast(getString(R.string.err_save_failed) + e.message.orEmpty())
            }
        }

        builder.show()
    }

    // 辅助：获取类型名称显示在标题里，方便识别
    private fun getTypeName(type: Int): String {
        when (type) {
            1 -> return "Byte"
            2 -> return "Short"
            3 -> return "Int"
            4 -> return "Long"
            5 -> return "Float"
            6 -> return "Double"
            7 -> return "ByteArray"
            8 -> return "String"
            11 -> return "IntArray"
            12 -> return "LongArray"
            else -> return "Unknown"
        }
    }

    // 长按菜单逻辑 (完整修复版：列表和树状图都支持)
    private fun showLongPressMenu(key: String?, itemData: JsonObject) {
        val ops = arrayOf<String?>(
            getString(R.string.menu_copy),
            getString(R.string.menu_paste),
            getString(R.string.menu_delete),
            getString(R.string.menu_rename),
            getString(R.string.menu_add_child)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_manage_prefix) + key)
            .setItems(ops) { _, w ->
                // 根据模式获取正确的容器和 key
                var parentContainer: JsonObject?
                var nodeKey = key
                var nodeData: JsonObject? = itemData

                if (isTreeMode) {
                    // 树状图模式
                    if (lastTreeClickPosition < 0) return@setItems

                    parentContainer = nbtTreeAdapter!!.getParentContainer(lastTreeClickPosition)
                    if (parentContainer == null) {
                        parentContainer = rootNbtData
                    }

                    val actualKey = nbtTreeAdapter!!.getNodeKey(lastTreeClickPosition)
                    if (actualKey != null) nodeKey = actualKey

                    val actualData = nbtTreeAdapter!!.getNodeData(lastTreeClickPosition)
                    if (actualData != null) nodeData = actualData
                } else {
                    // 列表模式
                    parentContainer = nbtAdapter!!.data
                }
                when (w) {
                    0 -> {
                        clipboard = nodeData!!.deepCopy()
                        toast(getString(R.string.toast_copy_success))
                    }

                    1 -> {
                        if (clipboard == null) {
                            toast(getString(R.string.toast_clipboard_empty))
                            return@setItems
                        }
                        val container = parentContainer ?: return@setItems
                        val pasteKey = nodeKey + "_copy"
                        if (container.has(pasteKey)) {
                            toast(getString(R.string.toast_name_exists))
                            return@setItems
                        }
                        val clip = clipboard ?: return@setItems
                        container.add(pasteKey, clip.deepCopy())
                        refreshAfterTreeEdit()
                        toast(getString(R.string.toast_pasted))
                    }

                    2 -> {
                        parentContainer!!.remove(nodeKey)
                        refreshAfterTreeEdit()
                        toast(getString(R.string.toast_deleted))
                    }

                    3 -> showTreeRenameDialog(nodeKey, nodeData, parentContainer!!)
                    4 -> {
                        val data = nodeData ?: return@setItems
                        val type = data.get("t").asInt
                        if (type == 10) {
                            val childContainer = data.asJsonObject.get("v").asJsonObject
                            showAddChildDialog(childContainer)
                            // 树状图模式需要特殊处理展开
                            if (isTreeMode) {
                                nbtTreeAdapter?.getNode(lastTreeClickPosition)?.let { node ->
                                    if (!node.isExpanded) {
                                        nbtTreeAdapter?.toggleExpand(lastTreeClickPosition)
                                    }
                                }
                            }
                        } else if (type == 9) {
                            val itemType = data.get("itemType").asInt

                            // 创建新元素的值（裸值，List 不存包装格式）
                            val newValue: JsonElement = when (itemType) {
                                1 -> JsonPrimitive(0.toByte())
                                2 -> JsonPrimitive(0.toShort())
                                3 -> JsonPrimitive(0)
                                4 -> JsonPrimitive(0L)
                                5 -> JsonPrimitive(0.0f)
                                6 -> JsonPrimitive(0.0)
                                8 -> JsonPrimitive("")
                                10 -> JsonObject()
                                else -> JsonPrimitive(0)
                            }

                            if (isTreeMode) {
                                // 树状图模式：直接修改节点数据中的数组
                                val listArray = data.getAsJsonArray("v")
                                listArray.add(newValue)
                            } else {
                                // 列表模式：需要找到 rootNbtData 中的原始 List 数组
                                // 通过 pathStack 回溯定位到原始数据
                                val originalList = findOriginalListData()
                                if (originalList != null) {
                                    originalList.add(newValue)
                                } else {
                                    // 回退：直接修改 data 中的数组
                                    val listArray = data.getAsJsonArray("v")
                                    listArray.add(newValue)
                                }
                            }

                            refreshAfterTreeEdit()

                            // 自动展开
                            if (isTreeMode) {
                                nbtTreeAdapter?.getNode(lastTreeClickPosition)?.let { node ->
                                    if (!node.isExpanded) {
                                        nbtTreeAdapter?.toggleExpand(lastTreeClickPosition)
                                    }
                                }
                            }
                            toast(getString(R.string.toast_item_added_to_list))
                        }
                    }
                }
            }.show()
    }

    // 树状图专用重命名对话框
    private fun showTreeRenameDialog(
        oldKey: String?,
        itemData: JsonObject?,
        parentContainer: JsonObject
    ) {
        val input = EditText(this)
        input.setText(oldKey)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_rename))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                val newKey = input.text.toString().trim()
                if (newKey.isEmpty() || newKey == oldKey) return@setPositiveButton
                if (parentContainer.has(newKey)) {
                    toast(getString(R.string.toast_name_exists))
                    return@setPositiveButton
                }
                parentContainer.remove(oldKey)
                parentContainer.add(newKey, itemData)
                refreshAfterTreeEdit()
                toast(getString(R.string.toast_renamed))
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // 树状图编辑后刷新（保持视图状态）
    private fun refreshAfterTreeEdit() {
        if (isTreeMode) {
            // 树状图：通知数据变化，保持展开状态
            nbtTreeAdapter!!.notifyDataSetChanged()
        } else {
            // 列表：刷新 keys
            nbtAdapter!!.refreshKeys()
        }
    }

    // 弹出类型选择框 (完整版)
    private fun showAddChildDialog(parent: JsonObject) {
        // NBT 类型全家桶 (和你的截图一样)
        val types = arrayOf<String?>(
            "Byte (1)",
            "Short (2)",
            "Int (3)",
            "Long (4)",
            "Float (5)",
            "Double (6)",
            "Byte Array (7)",
            "String (8)",
            "List (9)",
            "Compound (10)",
            "Int Array (11)",
            "Long Array (12)"
        )
        // 对应的 ID
        val ids = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_add_child))
            .setItems(types) { _: DialogInterface?, w: Int ->
                val selectedType = ids[w]
                // 弹出命名对话框
                showNameInputDialog(parent, selectedType)
            }
            .show()
    }

    // (对应的命名输入框逻辑，需要补全 Array 支持)
    private fun showNameInputDialog(parentCompound: JsonObject, type: Int) {
        val input = EditText(this@MainActivity)
        input.hint = getString(R.string.hint_input_name)
        AlertDialog.Builder(this@MainActivity)
            .setTitle(getString(R.string.menu_rename))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_create)) { _, _ ->
                val name = input.text.toString()
                if (name.isNotEmpty()) {
                    if (parentCompound.has(name)) {
                        toast(getString(R.string.toast_name_exists))
                        return@setPositiveButton
                    }

                    val t = JsonObject()
                    t.addProperty("t", type)

                    // 根据不同类型设置初始值 (全类型支持)
                    when (type) {
                        1 -> t.addProperty("v", 0.toByte())
                        2 -> t.addProperty("v", 0.toShort())
                        3 -> t.addProperty("v", 0)
                        4 -> t.addProperty("v", 0L)
                        5 -> t.addProperty("v", 0.0f)
                        6 -> t.addProperty("v", 0.0)
                        7 -> t.add("v", JsonArray())
                        8 -> t.addProperty("v", "")
                        9 -> {
                            t.add("v", JsonArray())
                            t.addProperty("itemType", 10) // 默认为 End
                        }
                        10 -> t.add("v", JsonObject())
                        11 -> t.add("v", JsonArray())
                        12 -> t.add("v", JsonArray())
                    }

                    parentCompound.add(name, t)

                    // 刷新界面
                    if (nbtAdapter!!.data === parentCompound) nbtAdapter!!.refreshKeys()
                    toast(getString(R.string.toast_created) + name)
                }
            }.show()
    }

    // 根菜单 (长按顶部标题或点击全屏右上角触发)
    private fun showRootMenu() {
        // 【修复】兼容树状图模式，使用 final 声明
        val current: JsonObject?
        if (isTreeMode) {
            if (rootNbtData == null) {
                toast(getString(R.string.toast_no_data))
                return
            }
            current = rootNbtData
        } else {
            if (nbtAdapter == null || nbtAdapter!!.data == null) {
                toast(getString(R.string.toast_no_data))
                return
            }
            current = nbtAdapter!!.data
        }

        // 0: 添加, 1: 粘贴子项, 2: 复制整层, 3: 粘贴/导入(替换), 4: 清空
        val ops = arrayOf<String?>(
            getString(R.string.action_add_tag),
            getString(R.string.action_paste_tag),
            getString(R.string.action_copy_root),
            getString(R.string.action_paste_root),
            getString(R.string.action_clear_all)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_root_title))
            .setItems(ops) { _, w ->
                val container = current ?: return@setItems

                if (w == 0) {
                    // === 1. 添加子项 ===
                    showAddChildDialog(container)
                } else if (w == 1) {
                    // === 2. 粘贴 (作为子节点插入) ===
                    if (clipboard == null) {
                        toast(getString(R.string.toast_clipboard_empty))
                        return@setItems
                    }
                    val clip = clipboard ?: return@setItems
                    val input = EditText(this@MainActivity)
                    input.hint = getString(R.string.hint_new_tag_name)
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.title_paste_as))
                        .setView(input)
                        .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                            val n = input.text.toString()
                            if (n.isNotEmpty() && !container.has(n)) {
                                container.add(n, clip.deepCopy())
                                refreshAfterTreeEdit()
                                toast(getString(R.string.toast_pasted))
                            } else {
                                toast(getString(R.string.toast_name_empty_or_exists))
                            }
                        }.show()
                } else if (w == 2) {
                    // === 3. 复制当前完整数据 (JSON 导出) ===
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    val clipData = ClipData.newPlainText("NBT_JSON", container.toString())
                    cm.setPrimaryClip(clipData)
                    toast(getString(R.string.toast_copy_success))
                } else if (w == 3) {
                    // === 4. 粘贴并替换当前数据 (导入) ===
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    if (!cm.hasPrimaryClip()) {
                        toast(getString(R.string.toast_clipboard_empty))
                        return@setItems
                    }
                    val clip = cm.primaryClip
                    if (clip == null || clip.itemCount == 0) {
                        toast(getString(R.string.toast_clipboard_empty))
                        return@setItems
                    }

                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.msg_confirm_replace))
                        .setMessage(getString(R.string.msg_replace_warning))
                        .setPositiveButton(getString(R.string.btn_replace)) { _, _ ->
                            try {
                                val text = cm.primaryClip?.getItemAt(0)?.text
                                if (text == null) return@setPositiveButton

                                val newObj = JsonParser.parseString(text.toString()).asJsonObject

                                // 清空当前界面并填入新数据
                                val oldKeys = ArrayList(container.keySet())
                                for (k in oldKeys) container.remove(k)

                                for (entry in newObj.entrySet()) {
                                    container.add(entry.key, entry.value)
                                }

                                refreshAfterTreeEdit()
                                toast(getString(R.string.toast_pasted))
                            } catch (_: Exception) {
                                toast(getString(R.string.err_json_parse))
                            }
                        }
                        .setNegativeButton(getString(R.string.btn_cancel), null)
                        .show()
                } else if (w == 4) {
                    // === 5. 清空所有 ===
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.dialog_danger_title))
                        .setMessage(getString(R.string.dialog_clear_msg))
                        .setPositiveButton(getString(R.string.btn_confirm_clear)) { _, _ ->
                            val keys = ArrayList(container.keySet())
                            for (key in keys) {
                                container.remove(key)
                            }
                            refreshAfterTreeEdit()
                            toast(getString(R.string.toast_cleared))
                        }
                        .setNegativeButton(getString(R.string.btn_cancel), null)
                        .show()
                }
            }.show()
    }

    private fun toast(s: String?) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("DiscouragedApi")
    private fun applyTheme() {
        // 直接使用全局变量 viewMainContent，不再临时 findViewById
        val mainContent = viewMainContent ?: return

        // 重新获取文字控件 (这些还是动态获取比较好，防止视图树变化)
        val tvTitle = findViewById<TextView?>(R.id.tv_app_title)
        val tvLabelEdit = findViewById<TextView?>(R.id.tv_current_path)

        if (isNightMode) {
            // === 夜间模式 ===
            // 给主容器变色
            mainContent.setBackgroundColor(-0xededee) // 黑灰

            // 如果全局变量 viewSidebar 不为空，也给它变色
            viewSidebar?.setBackgroundColor(-0xe1e1e2) // 稍浅的黑

            // 文字变白
            tvTitle?.setTextColor(-0x1)
            tvLabelEdit?.setTextColor(-0x333334)
            etWorldName?.let {
                it.setBackgroundColor(-0xcccccd)
                it.setTextColor(-0x1)
            }
            nbtListView?.setBackgroundColor(-0xe1e1e2)
        } else {
            // === 日间模式 ===
            var isDynamic = false

            // 尝试 Android 12 动态色
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    val colorId = resources.getIdentifier("system_neutral1_100", "color", "android")
                    if (colorId != 0) {
                        val dynamicColor = getColor(colorId)
                        mainContent.setBackgroundColor(dynamicColor)
                        viewSidebar?.setBackgroundColor(-0x1) // 侧边栏保持白或动态色都行
                        isDynamic = true
                    }
                } catch (e: Exception) {
                    Log.w(ContentValues.TAG, "Dynamic color load failed: ${e.message}")
                }
            }

            if (!isDynamic) {
                mainContent.setBackgroundColor(-0xa0a0b) // 默认灰白
                viewSidebar?.setBackgroundColor(-0x1)
            }

            tvTitle?.setTextColor(-0xcccccd)
            tvLabelEdit?.setTextColor(-0x8a8a8b)
            etWorldName?.let {
                it.setBackgroundColor(-0x1)
                it.setTextColor(-0x1000000)
            }
            nbtListView?.setBackgroundColor(-0x1)
        }
    }

    // 找到你刚刚写的 toggleFullScreen，更新成这样：
    private fun toggleFullScreen(enable: Boolean) {
        isFullScreen = enable

        if (layoutNormalUi == null) layoutNormalUi = findViewById<View?>(R.id.layout_normal_ui)
        if (layoutEditorHeader == null) layoutEditorHeader =
            findViewById<View?>(R.id.layout_editor_header)
        if (layoutFullscreenBar == null) layoutFullscreenBar =
            findViewById<View?>(R.id.layout_fullscreen_bar)

        // 获取新加入的路径 TextView
        if (tvFullscreenPath == null) tvFullscreenPath =
            findViewById<TextView?>(R.id.tv_fullscreen_path)

        if (enable) {
            layoutNormalUi!!.setVisibility(View.GONE)
            layoutEditorHeader!!.setVisibility(View.GONE)
            layoutFullscreenBar!!.setVisibility(View.VISIBLE)

            // 【关键】全屏时，同步一次路径显示
            updatePathTitle()
        } else {
            layoutNormalUi!!.setVisibility(View.VISIBLE)
            layoutEditorHeader!!.setVisibility(View.VISIBLE)
            layoutFullscreenBar!!.setVisibility(View.GONE)
        }
    }

    private fun showViewOptionsDialog() {
        val items = arrayOf<String?>(
            getString(R.string.mode_raw),  // 0: 原始
            getString(R.string.mode_raw_trans),  // 1: 原始+翻译
            getString(R.string.mode_smart),  // 2: 智能解析 (Key+中+值解析)
            getString(R.string.mode_simple) // 3: 极简 (只显中文Key)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_view_mode))
            .setSingleChoiceItems(
                items,
                currentViewMode
            ) { d: DialogInterface?, which: Int ->
                // 更新模式
                currentViewMode = which
                prefs?.edit { putInt("view_mode", currentViewMode) }

                // 【核心修改】安全刷新Adapter
                nbtAdapter?.setViewMode(currentViewMode)
                nbtTreeAdapter?.setViewMode(currentViewMode)

                d?.dismiss()
                toast(getString(R.string.toast_mode_changed))
            }
            .show()
    }

    // === [新增] 多线程并行目录复制引擎 (加速核心) ===
    // === [修复] 多线程并行复制 (加强防死锁) ===
    @Throws(Exception::class)
    private fun copyDirectoryParallel(source: File, target: File) {
        if (!source.exists()) return
        if (!target.exists()) target.mkdirs()

        val files = source.listFiles()
        if (files == null || files.isEmpty()) return  // 判空处理

        // 1. 过滤文件列表
        val fileList: MutableList<File> = ArrayList()
        for (f in files) {
            // 不复制 LOCK，且只复制文件 (目录递归稍微麻烦，建议这里遇到子目录走单线程 copyDirectory)
            if (f.name != "LOCK") {
                fileList.add(f)
            }
        }

        if (fileList.isEmpty()) return  // 没有需要复制的文件

        // 2. 创建线程池
        val cores = Runtime.getRuntime().availableProcessors()

        // IO 任务通常大部分时间在等读写，CPU 是空闲的，所以可以开多点线程压榨 IO 带宽
        // 经验公式：核心数 * 2，且保底 4 线程，最大不超过 12 (避免打开过多 FileHandle 崩溃)
        val threadCount = max(4, min(cores * 2, 12))

        val executor = Executors.newFixedThreadPool(threadCount)

        val latch = CountDownLatch(fileList.size)
        val errorRef = AtomicReference<Throwable?>()

        for (srcFile in fileList) {
            executor.submit {
                try {
                    // 快速失败机制
                    if (errorRef.get() != null) return@submit

                    val destFile = File(target, srcFile.name)

                    if (srcFile.isDirectory) {
                        // 子文件夹：走单线程递归（避免线程池爆炸）
                        // 且 db 文件夹下一般都是平铺文件，很少有子文件夹
                        copyDirectory(srcFile, destFile)
                    } else {
                        copyFile(srcFile, destFile)
                    }
                } catch (e: Exception) {
                    errorRef.set(e)
                } finally {
                    // 【生死关键】 无论复制成功与否，必须减计数器！
                    latch.countDown()
                }
            }
        }

        // 4. 等待完成
        try {
            latch.await()
        } catch (_: InterruptedException) {
            throw Exception(getString(R.string.msg_copy_process_interrupted))
        } finally {
            // 确保线程池关闭
            executor.shutdown()
        }

        // 5. 向上抛出异常
        if (errorRef.get() != null) {
            throw errorRef.get()!!
        }
    }

    // 1. 联机玩家列表 (修复 Final 问题)
    private fun showMultiPlayerDialog() {
        if (cachePlayerList != null) {
            showListDialogInternal(cachePlayerList!!, TYPE_PLAYER)
            return
        }
        showProgressDialog(getString(R.string.msg_scanning), getString(R.string.msg_search_for_players))

        Thread {
            try {
                val db = PlayerDbManager(currentWorkingDbPath!!)
                // 1. 获取并转存
                val rawList: MutableList<String> = db.listPlayerKeys().map { it }.toMutableList()
                db.close()

                cachePlayerList = rawList

                runOnUiThread {
                    dismissProgressDialog()
                    // 2. 这里的 rawList 已经是 final 的了
                    showListDialogInternal(rawList, TYPE_PLAYER)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dismissProgressDialog()
                    toast(e.toString())
                }
            }
        }.start()
    }

    // 加载指定 Key 的数据 (修复：优先恢复未保存的草稿，其次才是缓存)
    private fun loadSpecificPlayer(keyName: String) {
        val finalKey = keyName

        // 1. 【核心新增】切走前，先把当前正在编辑的界面保存为"草稿"
        saveCurrentSessionToMemory()

        // 2. 【核心新增】尝试恢复目标 Key 的"草稿"
        // 如果之前编辑过这个 Key 且没保存，这里会直接恢复现场，包括滚动位置
        if (tryRestoreSession(finalKey)) {
            return
        }

        // 3. 如果没有草稿，再检查静态缓存 (这是没修改过的原始数据)
        if (nbtDataCache.containsKey(finalKey)) {
            currentTargetKey = finalKey
            isEditingPlayer = true

            rootNbtData = nbtDataCache[finalKey]

            // 如果是读缓存（说明是第一次打开或重置过），重置视图到顶部
            navigationStack.clear()
            pathStack.clear()
            scrollPositionStack.clear()

            updateAdapter(rootNbtData)

            tvCurrentPath?.text = getString(R.string.title_current, finalKey)
            return
        }

        // 4. 既没草稿也没缓存，只能读硬盘
        showProgressDialog(getString(R.string.msg_read), getString(R.string.msg_load) + finalKey)

        Thread {
            try {
                // 强制解锁
                currentWorkingDbPath?.let { dbPath ->
                    val lockFile = File(dbPath, "LOCK")
                    if (lockFile.exists()) lockFile.delete()
                }

                val dbPath = currentWorkingDbPath ?: throw Exception("DB path is null")
                val db = PlayerDbManager(dbPath)
                val data = db.readSpecificKey(finalKey)
                db.close()

                val jsonData = parseBytes(data)

                runOnUiThread {
                    dismissProgressDialog()
                    currentTargetKey = finalKey
                    isEditingPlayer = true

                    navigationStack.clear()
                    pathStack.clear()
                    scrollPositionStack.clear()

                    rootNbtData = jsonData
                    // 存入静态缓存
                    nbtDataCache[finalKey] = rootNbtData

                    updateAdapter(rootNbtData)

                    tvCurrentPath?.text = getString(R.string.title_current, finalKey)
                    toast(getString(R.string.toast_loaded) + finalKey)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dismissProgressDialog()
                    // 捕获空数据，询问创建
                    if (e.message?.contains(getString(R.string.msg_data_is_empty)) == true) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(getString(R.string.title_data_does_not_exist))
                            .setMessage(
                                getString(
                                    R.string.msg_this_archive_has_not_generated_data_yet,
                                    finalKey
                                )
                            )
                            .setPositiveButton(
                                getString(R.string.btn_create_and_open)
                            ) { _: DialogInterface?, _: Int ->
                                createNewGlobalData(finalKey)
                            }
                            .setNegativeButton(getString(R.string.btn_cancel), null)
                            .show()
                    } else {
                        dismissProgressDialog()
                        toast(getString(R.string.err_load_failed) + e.message.orEmpty())
                    }
                }
            }
        }.start()
    }

    // 1. [复制玩家数据]
    private fun copyPlayerJson(key: String) {
        Thread {
            try {
                val db = PlayerDbManager(currentWorkingDbPath!!)
                val data = db.readSpecificKey(key)
                db.close()

                val json = parseBytes(data).toString()
                runOnUiThread {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("PLAYER_DATA", json)
                    cm.setPrimaryClip(clip)
                    toast(getString(R.string.toast_player_data_copied_to_clipboard))
                }
            } catch (e: Exception) {
                runOnUiThread { toast(getString(R.string.toast_copy_failed) + e) }
            }
        }.start()
    }

    // 2. [粘贴并覆盖]
    // 2. [粘贴并覆盖] (修正版)
    private fun pastePlayerJson(targetKey: String, onSuccess: Runnable?) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        if (!cm.hasPrimaryClip()) {
            toast(getString(R.string.toast_clipboard_empty))
            return
        }
        val clip = cm.getPrimaryClip()
        if (clip == null || clip.getItemCount() == 0) {
            toast(getString(R.string.toast_clipboard_empty))
            return
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_override_warning))
            .setMessage(getString(R.string.msg_full_coverage) + targetKey + getString(R.string.msg_this_action_is_irreversible))
            .setPositiveButton(
                getString(R.string.btn_cover)
            ) { _: DialogInterface?, _: Int ->
                val jsonStr = cm.getPrimaryClip()!!.getItemAt(0).getText().toString()
                Thread {
                    try {
                        val jo = JsonParser.parseString(jsonStr).getAsJsonObject()
                        val bytes = writeToBytes(jo)

                        // 【注意这里】重新创建一个新的 db 对象来操作，避免使用 final 的冲突
                        val localDb = PlayerDbManager(currentWorkingDbPath!!)
                        localDb.writeSpecificKey(targetKey, bytes)
                        localDb.close()

                        runOnUiThread {
                            toast(getString(R.string.toast_data_covered))
                            if (onSuccess != null) onSuccess.run()
                        }
                    } catch (e: Exception) {
                        runOnUiThread { toast(getString(R.string.toast_paste_failed) + e) }
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    // 3. [删除玩家] (需要去DbManager加一个 deleteKey)
    // 修复后的删除逻辑
    private fun deletePlayerKey(key: String, onSuccess: Runnable?) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_remove_warning))
            .setMessage(getString(R.string.msg_confirm_deletion) + key + "？")
            .setPositiveButton(
                getString(R.string.btn_delete)
            ) { _: DialogInterface?, _: Int ->

                // 【技巧】把外部变量转为 final 本地变量，传给线程
                val dbPath = currentWorkingDbPath
                Thread {
                    try {
                        val db = PlayerDbManager(dbPath!!) // 用 dbPath
                        db.deleteKey(key)
                        db.close()

                        runOnUiThread {
                            toast(getString(R.string.toast_deleted) + key)
                            if (onSuccess != null) onSuccess.run()
                        }
                    } catch (e: Exception) {
                        runOnUiThread { toast(getString(R.string.toast_delete_failed) + e) }
                    }
                }.start()
            }.setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    // 修复后的新建逻辑 (支持新建、粘贴、批量删除、拼图、国际化、防闪退)
    private fun showPlayerRootMenu(
        currentList: MutableList<String>,
        dataType: Int
    ) {
        val typeName: String
        val hintName: String

        // 1. 动态构建菜单列表
        val menuList: MutableList<String?> = ArrayList()
        // Index 0: 新建
        menuList.add(
            getString(R.string.msg_create_new_blank) + (if (dataType == TYPE_MAP) getString(R.string.msg_map) else "") + " (Key)"
        )

        // Index 1: 粘贴
        menuList.add(getString(R.string.msg_paste_as_new) + (if (dataType == TYPE_MAP) getString(R.string.msg_map) else "") + " (New Key)")

        // Index 2: 删除所有
        menuList.add(getString(R.string.msg_delete_all_data_in_the_list))

        if (dataType == TYPE_MAP) {
            typeName = getString(R.string.msg_map_data)
            hintName = getString(R.string.msg_for_example_map)
            // Index 3: [新增] 只有地图模式才有拼图功能
            menuList.add(getString(R.string.msg_create_a_giant_jigsaw_puzzle))
        } else if (dataType == TYPE_VILLAGE) {
            typeName = getString(R.string.mag_village_data)
            hintName = getString(R.string.msg_for_example_village)
        } else {
            typeName = getString(R.string.msg_player_data)
            hintName = getString(R.string.msg_for_example_player)
        }

        val ops = menuList.toTypedArray<String?>()

        AlertDialog.Builder(this)
            .setItems(ops) { _, w ->

                // === 情况 A: 删除所有 (Index 2) ===
                if (w == 2) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.title_high_energy_early_warning))
                        .setMessage(
                            getString(R.string.msg_delete_existing_ones_in_the_list) + currentList.size + getString(
                                R.string.msg_indivual
                            ) + typeName + getString(R.string.msg_the_operation_cannot_be_undone)
                        )
                        .setPositiveButton(getString(R.string.btn_delete_all)) { _, _ ->
                            // 批量删除逻辑
                            showProgressDialog(getString(R.string.msg_deleting), getString(R.string.msg_cleaning_up))
                            val dbPath = currentWorkingDbPath
                            Thread {
                                try {
                                    val db = PlayerDbManager(dbPath!!)
                                    // 遍历删除所有显示的 Key
                                    for (key in currentList) {
                                        db.deleteKey(key)
                                    }
                                    db.close()

                                    runOnUiThread {
                                        dismissProgressDialog()
                                        currentList.clear() // 清空列表

                                            invalidateListCache(dataType)
                                        toast(getString(R.string.toast_cleared_all) + typeName)
                                    }
                                } catch (e: Exception) {
                                    runOnUiThread {
                                        dismissProgressDialog()
                                        toast(getString(R.string.toast_delete_failed) + e)
                                    }
                                }
                            }.start()
                        }
                        .setNegativeButton(getString(R.string.btn_cancel), null)
                        .show()
                    return@setItems
                }

                // === 情况 B: 巨型拼图画 (Index 3, 仅地图模式) ===
                if (dataType == TYPE_MAP && w == 3) {
                    showPuzzleWarningDialog() // 调用拼图配置方法
                    return@setItems
                }

                // === 情况 C: 新建或粘贴 (Index 0 or 1) ===
                val finalTypeName = typeName
                val finalHintName = hintName
                val mode = w // 0=新建, 1=粘贴

                val input = EditText(this@MainActivity)
                input.hint = finalHintName
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.title_new) + finalTypeName + " Key")
                    .setView(input)
                    .setPositiveButton(getString(R.string.btn_create)) { _, _ ->
                        val newKey = input.text.toString()
                        if (newKey.isEmpty()) {
                            toast(getString(R.string.toast_key_cannot_be_empty))
                            return@setPositiveButton
                        }

                        val jsonContent: String
                        if (mode == 0) jsonContent = "{}"
                        else {
                            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            if (!cm.hasPrimaryClip()) {
                                toast(getString(R.string.toast_clipboard_empty))
                                return@setPositiveButton
                            }
                            jsonContent = cm.primaryClip!!.getItemAt(0).text.toString()
                        }

                        val dbPath = currentWorkingDbPath
                        Thread {
                            try {
                                val jo = JsonParser.parseString(jsonContent).asJsonObject
                                val bytes = writeToBytes(jo)

                                val localDb = PlayerDbManager(dbPath!!)
                                localDb.writeSpecificKey(newKey, bytes)
                                localDb.close()

                                runOnUiThread {
                                    // 如果列表中没有这个key才添加，防止显示重复
                                    if (!currentList.contains(newKey)) {
                                        currentList.add(newKey)

                                            invalidateListCache(dataType)
                                    }
                                    toast(finalTypeName + getString(R.string.toast_yes_created))
                                }
                            } catch (e: Exception) {
                                runOnUiThread { toast(getString(R.string.toast_creation_failed) + e) }
                            }
                        }.start()
                    }.show()
            }.show()
    }

    // [重命名玩家 Key] 逻辑：读取旧 -> 写入新 -> 删旧
    private fun renamePlayerKey(oldKey: String, onSuccess: Runnable?) {
        val input = EditText(this)
        input.setText(oldKey)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_rename_key))
            .setMessage(getString(R.string.msg_move_data_to_new_key_and_delete_old_key))
            .setView(input)
            .setPositiveButton(getString(R.string.title_rename)) { _, _ ->
                val newKey = input.text.toString().trim()
                if (newKey.isEmpty() || newKey == oldKey) return@setPositiveButton

                val dbPath = currentWorkingDbPath // 传参 final
                Thread {
                    try {
                        val db = PlayerDbManager(dbPath!!)
                        // 1. 读旧
                        val data = db.readSpecificKey(oldKey)
                        // 2. 写新
                        db.writeSpecificKey(newKey, data)
                        // 3. 删旧
                        db.deleteKey(oldKey)
                        db.close()

                        runOnUiThread(onSuccess) // 回调刷新
                    } catch (e: Exception) {
                        runOnUiThread { toast(getString(R.string.toast_rename_failed_colon) + e) }
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // 升级版地图列表 (带缓存极速版)
    private fun showMapListDialog() {
        // 1. 【缓存检查】如果内存里已经有列表了，直接显示，不读盘！
        if (cacheMapList != null) {
            showListDialogInternal(cacheMapList!!, TYPE_MAP)
            return
        }

        showProgressDialog(getString(R.string.msg_scanning), getString(R.string.msg_search_map_data))

        Thread {
            try {
                val db = PlayerDbManager(currentWorkingDbPath!!)
                val rawList: MutableList<String> = db.listMapKeys().map { it }.toMutableList()
                db.close()

                // 存入缓存
                cacheMapList = rawList

                runOnUiThread {
                    dismissProgressDialog()
                    showListDialogInternal(cacheMapList!!, TYPE_MAP)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dismissProgressDialog()
                    toast(e.toString())
                }
            }
        }.start()
    }

    // 新增：巨型数组专用管理对话框
    // 新增：巨型数组分页查看/编辑对话框
    @SuppressLint("SetTextI18n")
    private fun showArrayPaginationDialog(key: String, jsonArray: JsonArray) {
        val size = jsonArray.size()

        // 1. 创建布局容器
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 30, 50, 0)

        val tvInfo = TextView(this)
        tvInfo.text = getString(R.string.text_huge_array_detected, size)
        tvInfo.textSize = 16f
        layout.addView(tvInfo)

        // 范围选择框
        val etStart = EditText(this)
        etStart.hint = getString(R.string.text_starting_position_eg_0)
        etStart.inputType = InputType.TYPE_CLASS_NUMBER
        etStart.setText("0")
        layout.addView(etStart)

        val etCount = EditText(this)
        etCount.hint = getString(R.string.text_number_of_views_recommended_200)
        etCount.inputType = InputType.TYPE_CLASS_NUMBER
        etCount.setText("200")
        layout.addView(etCount)

        // 【核心修改】在此处添加图片导入按钮
        if (key == "colors" && size >= 16384) {
            val btnImport = Button(this)
            btnImport.text = getString(R.string.text_import_images_to_generate_map_images)
            btnImport.setOnClickListener {
                // 暂存目标数组
                currentTargetMapArray = jsonArray
                // 启动 SAF 选择器
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "image/*"
                startActivityForResult(intent, REQUEST_PICK_IMAGE_FOR_MAP)
            }
            layout.addView(btnImport)
        }

        // 2. 构建并显示弹窗
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_manage_array, key))
            .setView(layout)
            .setPositiveButton(
                getString(R.string.btn_view_and_edit_clips)
            ) { _: DialogInterface?, _: Int ->
                try {
                    var start = etStart.text.toString().toInt()
                    var count = etCount.text.toString().toInt()
                    if (start < 0) start = 0
                    if (count > 2000) count = 2000
                    if (start + count > size) count = size - start

                    showSubArrayEditDialog(jsonArray, start, count)
                } catch (_: NumberFormatException) {
                    toast(getString(R.string.toast_please_enter_valid_numbers))
                }
            }
            .setNeutralButton(
                getString(R.string.btn_full_export)
            ) { _: DialogInterface?, _: Int ->
                Thread {
                    val content = jsonArray.toString()
                    runOnUiThread {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Array", content))
                        toast(
                            getString(R.string.toast_exported, content.length)
                        )
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.btn_close), null)
            .show()
    }

    // 分页后的实际编辑窗口
    private fun showSubArrayEditDialog(
        originalArray: JsonArray,
        start: Int,
        count: Int
    ) {
        // 截取数据构建显示的字符串
        val sb = StringBuilder()
        sb.append("[")
        for (i in 0..<count) {
            sb.append(originalArray[start + i].toString())
            if (i < count - 1) sb.append(", ")
        }
        sb.append("]")

        val input = EditText(this)
        input.setText(sb.toString())

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_edit_left_bracket) + start + " ~ " + (start + count - 1) + ")")
            .setView(input)
            .setMessage(getString(R.string.msg_comma_separated_format_click_save_after_modification))
            .setPositiveButton(getString(R.string.btn_save_clip)) { _, _ ->
                try {
                    val parsed = JsonParser.parseString(input.text.toString())
                    if (parsed.isJsonArray) {
                        val newPart = parsed.asJsonArray
                        if (newPart.size() != count) {
                            toast(
                                getString(R.string.toast_quantities_are_inconsistent_must_be_retained) + count + getString(
                                    R.string.toast_element_which_is_currently
                                ) + newPart.size()
                            )
                            return@setPositiveButton
                        }
                        // 替换原数组中的内容
                        for (i in 0..<count) {
                            originalArray[start + i] = newPart[i]
                        }

                        // 刷新
                        nbtAdapter?.notifyDataSetChanged()
                        nbtTreeAdapter?.notifyDataSetChanged()
                        toast(getString(R.string.toast_clip_saved))
                    }
                } catch (e: Exception) {
                    toast(getString(R.string.err_format) + e.message)
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // 3. 村庄数据列表 (修复 Final 问题)
    private fun showVillageListDialog() {
        if (cacheVillageList != null) {
            showListDialogInternal(cacheVillageList!!, TYPE_VILLAGE)
            return
        }
        showProgressDialog(getString(R.string.msg_scanning), getString(R.string.msg_search_village_data))

        Thread {
            try {
                val db = PlayerDbManager(currentWorkingDbPath!!)
                val rawList: MutableList<String> = db.listVillageKeys().map { it }.toMutableList()
                db.close()

                cacheVillageList = rawList

                runOnUiThread {
                    dismissProgressDialog()
                    // 2. 这里的 rawList 已经是 final 的了
                    showListDialogInternal(rawList, TYPE_VILLAGE)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dismissProgressDialog()
                    toast(e.toString())
                }
            }
        }.start()
    }

    // 【简化版】拼图警告弹窗（无倒计时，直接确认）
    private fun showPuzzleWarningDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_warning_inventory))
            .setMessage(getString(R.string.msg_warning_puzzle))
            .setPositiveButton(
                getString(R.string.btn_i_understand_continue)
            ) { _: DialogInterface?, _: Int -> showPuzzleConfigDialog() }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // 拼图尺寸选择弹窗 (国际化修复版)
    private fun showPuzzleConfigDialog() {
        // 动态构建选项数组
        val options = arrayOf<String?>(
            getString(R.string.puzzle_opt_default),
            getString(R.string.puzzle_opt_2x2),
            getString(R.string.puzzle_opt_3x3),
            getString(R.string.puzzle_opt_custom)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_choose_puzzle_size))
            .setItems(options) { _, w ->
                if (w == 0) {
                    puzzleRows = 1
                    puzzleCols = 1
                    pickPuzzleImage()
                } else if (w == 1) {
                    puzzleRows = 2
                    puzzleCols = 2
                    pickPuzzleImage()
                } else if (w == 2) {
                    puzzleRows = 3
                    puzzleCols = 3
                    pickPuzzleImage()
                } else if (w == 3) {
                    // 自定义输入布局
                    val layout = LinearLayout(this@MainActivity)
                    layout.orientation = LinearLayout.HORIZONTAL
                    layout.setPadding(30, 20, 30, 0)

                    val etW = EditText(this@MainActivity)
                    etW.hint = getString(R.string.hint_width_col)
                    etW.inputType = InputType.TYPE_CLASS_NUMBER
                    etW.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)

                    val etH = EditText(this@MainActivity)
                    etH.hint = getString(R.string.hint_height_row)
                    etH.inputType = InputType.TYPE_CLASS_NUMBER
                    etH.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)

                    layout.addView(etW)
                    layout.addView(etH)

                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.title_input_dimensions))
                        .setView(layout)
                        .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                            try {
                                val strW = etW.text.toString()
                                val strH = etH.text.toString()

                                if (strW.isEmpty() || strH.isEmpty()) {
                                    toast(getString(R.string.toast_please_enter_size))
                                    return@setPositiveButton
                                }

                                puzzleCols = strW.toInt()
                                puzzleRows = strH.toInt()

                                // 提示大尺寸
                                if (puzzleCols * puzzleRows > 100) {
                                    val warning = getString(
                                        R.string.toast_huge_size_warning,
                                        puzzleCols * puzzleRows
                                    )
                                    toast(warning)
                                }

                                pickPuzzleImage()
                            } catch (e: Exception) {
                                toast(getString(R.string.toast_input_error) + e.message)
                            }
                        }.show()
                }
            }.show()
    }

    private fun pickPuzzleImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.setType("image/*")
        startActivityForResult(intent, REQUEST_PICK_IMAGE_FOR_PUZZLE)
    }

    // 核心引擎：生成拼图（禁人塔式单链嵌套 - Slot 0递归放盒子）
    private fun processPuzzleMap(imageUri: Uri) {
        showProgressDialog(getString(R.string.msg_in_preparation), getString(R.string.msg_analyzing_backpack))

        Thread {
            try {
                // 1. 准备 DB 对象
                val db = PlayerDbManager(currentWorkingDbPath!!)
                val playerData = db.readLocalPlayer()
                val mapKeys: MutableList<String> =
                    db.listMapKeys().map { it }.toMutableList()

                // 2. 解析背包
                var playerRoot = parseBytes(playerData)
                if (playerRoot == null) playerRoot = JsonObject()
                var invWrapper = playerRoot.getAsJsonObject("Inventory")
                val inventory: JsonArray?
                if (invWrapper == null) {
                    invWrapper = JsonObject()
                    invWrapper.addProperty("t", 9)
                    invWrapper.addProperty("itemType", 10)
                    inventory = JsonArray()
                    invWrapper.add("v", inventory)
                    playerRoot.add("Inventory", invWrapper)
                } else {
                    inventory = invWrapper.getAsJsonArray("v")
                }

                // 3. 【修复】正确处理空槽位检测（识别空气/空物品）
                val occupiedSlots = BooleanArray(36) // 0-35

                if (inventory != null) {
                    for (item in inventory) {
                        try {
                            if (!item.isJsonObject()) continue
                            val itemObj = item.getAsJsonObject()

                            // 解包装获取实际内容
                            var itemContent = itemObj
                            if (itemObj.has("v") && itemObj.get("v").isJsonObject()) {
                                itemContent = itemObj.getAsJsonObject("v")
                            }

                            // 读取Slot位置
                            var slot: Byte = -1
                            if (itemContent.has("Slot")) {
                                val slotEl = itemContent.get("Slot")
                                if (slotEl.isJsonObject()) {
                                    slot = slotEl.getAsJsonObject().get("v").getAsByte()
                                } else if (slotEl.isJsonPrimitive()) {
                                    slot = slotEl.getAsByte()
                                }
                            }
                            if (slot < 0 || slot >= 36) continue

                            // 【关键】判断这个槽位是否真的被占用了（不是空气）
                            var isRealItem = true

                            // 检查Name是否为空或空气
                            if (itemContent.has("Name")) {
                                val nameEl = itemContent.get("Name")
                                var name = ""
                                if (nameEl.isJsonObject() && nameEl.getAsJsonObject().has("v")) {
                                    name = nameEl.getAsJsonObject().get("v").getAsString()
                                } else if (nameEl.isJsonPrimitive()) {
                                    name = nameEl.getAsString()
                                }
                                if (name.isEmpty() || name == "minecraft:air") {
                                    isRealItem = false
                                }
                            }

                            // 检查Count是否为0
                            if (isRealItem && itemContent.has("Count")) {
                                val countEl = itemContent.get("Count")
                                var count = 1 // 默认至少1个
                                if (countEl.isJsonObject() && countEl.getAsJsonObject().has("v")) {
                                    count = countEl.getAsJsonObject().get("v").getAsInt()
                                } else if (countEl.isJsonPrimitive()) {
                                    count = countEl.getAsInt()
                                }
                                if (count <= 0) {
                                    isRealItem = false
                                }
                            }

                            // 只有真正的物品才标记为占用
                            if (isRealItem) {
                                occupiedSlots[slot.toInt()] = true
                            }
                        } catch (_: Exception) {
                        }
                    }
                }

                // 寻找第一个空位
                var freeSlot: Byte = -1
                for (i in 0..35) {
                    if (!occupiedSlots[i]) {
                        freeSlot = i.toByte()
                        break
                    }
                }

                if (freeSlot.toInt() == -1) {
                    db.close()
                    throw Exception(getString(R.string.msg_backpack_is_full_requires_at_least_1_open_space))
                }

                val finalFreeSlot = freeSlot

                // 4. 计算 ID
                var maxMapId: Long = -1
                for (key in mapKeys) {
                    val idStr = key.replace("map_", "")
                    try {
                        val id = idStr.toLong()
                        if (id > maxMapId) maxMapId = id
                    } catch (_: Exception) {
                    }
                }
                val startMapId = if (maxMapId < 0) 0 else (maxMapId + 1)

                // 5. 图片处理
                val `is` = getContentResolver().openInputStream(imageUri)
                var rawSrc = BitmapFactory.decodeStream(`is`)
                `is`!!.close() //暂时忽略

                if (rawSrc.getWidth() < puzzleCols || rawSrc.getHeight() < puzzleRows) {
                    val newW = max(rawSrc.getWidth(), puzzleCols)
                    val newH = max(rawSrc.getHeight(), puzzleRows)
                    val scaledSrc = rawSrc.scale(newW, newH)
                    rawSrc.recycle()
                    rawSrc = scaledSrc
                }
                val src = rawSrc

                val cellW = src.getWidth() / puzzleCols
                val cellH = src.getHeight() / puzzleRows
                val totalMaps = puzzleRows * puzzleCols

                val itemsMap = ConcurrentHashMap<Int, JsonObject>()

                runOnUiThread {
                    updateProgressDialog(
                        getString(R.string.msg_high_performance_mode_starts) + totalMaps + getString(
                            R.string.msg_map_write_to_database
                        )
                    )
                }

                val cores = Runtime.getRuntime().availableProcessors()
                val threadCount = if (useMultiThreading) min(cores + 1, 8) else 1
                val executor = Executors.newFixedThreadPool(threadCount)
                val latch = CountDownLatch(totalMaps)
                val errorRef = AtomicReference<Throwable?>()

                var globalIndex = 0
                for (r in 0..<puzzleRows) {
                    for (c in 0..<puzzleCols) {
                        val fr = r
                        val fc = c
                        val fIndex = globalIndex
                        val fMapId = startMapId + fIndex

                        executor.submit {
                            try {
                                if (errorRef.get() != null) return@submit

                                val chunk = Bitmap.createBitmap(
                                    src, fc * cellW, fr * cellH, cellW, cellH
                                )
                                val scaled = chunk.scale(128, 128)

                                val pixels = IntArray(128 * 128)
                                scaled.getPixels(pixels, 0, 128, 0, 0, 128, 128)

                                // RGBA 直写
                                val colorsArr = JsonArray()
                                for (p in pixels) {
                                    colorsArr.add(Color.red(p).toByte())
                                    colorsArr.add(Color.green(p).toByte())
                                    colorsArr.add(Color.blue(p).toByte())
                                    colorsArr.add(255.toByte()) // Alpha
                                }

                                val mapContent = JsonObject()
                                val colorsTag = JsonObject()
                                colorsTag.addProperty("t", 7)
                                colorsTag.add("v", colorsArr)
                                mapContent.add("colors", colorsTag)

                                val decTag = JsonObject()
                                decTag.addProperty("t", 9)
                                decTag.addProperty("itemType", 10)
                                decTag.add("v", JsonArray())
                                mapContent.add("decorations", decTag)

                                mapContent.add("dimension", wrapTag(1, 0.toByte()))
                                mapContent.add("fullyExplored", wrapTag(1, 1.toByte()))
                                mapContent.add("mapLocked", wrapTag(1, 1.toByte()))
                                mapContent.add("locked", wrapTag(1, 1.toByte()))
                                mapContent.add("unlimitedTracking", wrapTag(1, 0.toByte()))
                                mapContent.add("scale", wrapTag(1, 0.toByte()))
                                mapContent.add("width", wrapTag(2, 128.toShort()))
                                mapContent.add("height", wrapTag(2, 128.toShort()))
                                mapContent.add("xCenter", wrapTag(3, 0))
                                mapContent.add("zCenter", wrapTag(3, 0))
                                mapContent.add("mapId", wrapTag(4, fMapId))
                                mapContent.add("parentMapId", wrapTag(4, -1L))

                                val mapBytes = writeToBytes(mapContent)

                                synchronized(db) {
                                    db.writeSpecificKey("map_" + fMapId, mapBytes)
                                }

                                val itemContent = JsonObject()
                                itemContent.add("Name", wrapTag(8, "minecraft:filled_map"))
                                itemContent.add("Count", wrapTag(1, 1.toByte()))
                                itemContent.add("Damage", wrapTag(2, 0.toShort()))
                                itemContent.add("WasPickedUp", wrapTag(1, 0.toByte()))

                                val tagTag = JsonObject()
                                tagTag.addProperty("t", 10)
                                val tagContent = JsonObject()
                                tagContent.add("map_uuid", wrapTag(4, fMapId))
                                tagContent.add("map_name_index", wrapTag(3, fMapId.toInt()))

                                val displayTag = JsonObject()
                                displayTag.addProperty("t", 10)
                                val displayContent = JsonObject()
                                displayContent.add(
                                    "Name",
                                    wrapTag(8, "Puzzle " + (fr + 1) + "-" + (fc + 1))
                                )
                                displayTag.add("v", displayContent)
                                tagContent.add("display", displayTag)
                                tagTag.add("v", tagContent)
                                itemContent.add("tag", tagTag)

                                itemsMap[fIndex] = itemContent

                                chunk.recycle()
                                scaled.recycle()
                            } catch (e: Throwable) {
                                errorRef.set(e)
                            } finally {
                                latch.countDown()
                            }
                        }
                        globalIndex++
                    }
                }

                try {
                    latch.await()
                } catch (_: InterruptedException) {
                    throw Exception(getString(R.string.msg_interrupted))
                }
                executor.shutdown()

                if (errorRef.get() != null) {
                    throw Exception(getString(R.string.msg_processing_error_maybe_the_image_is_too_large) + ": " + errorRef.get()!!.message)
                }

                src.recycle()

                // 【核心修改】禁人塔式单链嵌套装箱
                val mapsPerLayer = 26
                val totalLayers = ceil(totalMaps.toDouble() / mapsPerLayer).toInt()

                // 从最底层开始构建（反向）
                var currentContainer: JsonObject? = null

                for (layer in totalLayers - 1 downTo 0) {
                    val startIdx = layer * mapsPerLayer
                    val endIdx = min(startIdx + mapsPerLayer, totalMaps)
                    val mapsInThisLayer = endIdx - startIdx

                    // 创建这一层的盒子
                    val boxItem = JsonObject()
                    boxItem.add("Name", wrapTag(8, "minecraft:undyed_shulker_box"))
                    boxItem.add("Count", wrapTag(1, 1.toByte()))
                    boxItem.add("Damage", wrapTag(2, 0.toShort()))
                    boxItem.add("WasPickedUp", wrapTag(1, 0.toByte()))

                    // Block标签
                    val blockTag = JsonObject()
                    blockTag.addProperty("t", 10)
                    val blockContent = JsonObject()
                    blockContent.add("name", wrapTag(8, "minecraft:undyed_shulker_box"))
                    val statesTag = JsonObject()
                    statesTag.addProperty("t", 10)
                    statesTag.add("v", JsonObject())
                    blockContent.add("states", statesTag)
                    blockContent.add("version", wrapTag(3, 18168865))
                    blockTag.add("v", blockContent)
                    boxItem.add("Block", blockTag)

                    // tag标签（包含Items）
                    val tagTag = JsonObject()
                    tagTag.addProperty("t", 10)
                    val tagContent = JsonObject()

                    val itemsListTag = JsonObject()
                    itemsListTag.addProperty("t", 9)
                    itemsListTag.addProperty("itemType", 10)
                    val itemsArr = JsonArray()

                    // Slot 0: 如果有下一层，放入子盒子
                    if (currentContainer != null) {
                        val childBox = currentContainer.deepCopy()
                        childBox.add("Slot", wrapTag(1, 0.toByte()))
                        itemsArr.add(childBox)
                    }

                    // Slot 1-26: 放入地图
                    for (i in 0..<mapsInThisLayer) {
                        val mapIdx = startIdx + i
                        var mapItem = itemsMap[mapIdx]
                        if (mapItem == null) {
                            throw Exception(getString(R.string.msg_slice_missing) + mapIdx)
                        }
                        mapItem = mapItem.deepCopy()
                        mapItem.add("Slot", wrapTag(1, (i + 1).toByte()))
                        itemsArr.add(mapItem)
                    }

                    itemsListTag.add("v", itemsArr)
                    tagContent.add("Items", itemsListTag)

                    // 显示名称
                    val displayTag = JsonObject()
                    displayTag.addProperty("t", 10)
                    val displayContent = JsonObject()
                    if (layer == 0) {
                        displayContent.add(
                            "Name",
                            wrapTag(
                                8,
                                "Puzzle Set (" + totalMaps + " maps, " + totalLayers + " layers)"
                            )
                        )
                    } else {
                        displayContent.add("Name", wrapTag(8, "Layer " + layer + " →"))
                    }
                    displayTag.add("v", displayContent)
                    tagContent.add("display", displayTag)

                    tagTag.add("v", tagContent)
                    boxItem.add("tag", tagTag)

                    currentContainer = boxItem
                }

                // 最终的顶层盒子放入找到的空位（不再是硬编码的Slot 0）
                if (currentContainer == null) {
                    throw Exception("装箱失败：容器为空")
                }
                val finalBox = currentContainer.deepCopy()
                finalBox.add("Slot", wrapTag(1, finalFreeSlot)) // 使用找到的空位
                inventory?.add(finalBox)

                // 保存玩家数据
                val newPlayerData = writeToBytes(playerRoot)
                db.writeLocalPlayer(newPlayerData)
                db.close()

                // 【添加这行】声明为 final，供内部类使用
                val finalPlayerRoot: JsonObject = playerRoot

                val finalStartId = startMapId
                val finalTotalLayers = totalLayers

                runOnUiThread {
                    dismissProgressDialog()
                    val msg =
                        getString(R.string.msg_success) + totalMaps + getString(R.string.msg_maps_have_been_packed) +
                                getString(R.string.msg_nesting_depth) + finalTotalLayers + getString(
                            R.string.msg_layer
                        ) +
                                getString(R.string.msg_backpack_only_1_slot) +
                                getString(R.string.msg_start_id) + finalStartId + "+\n\n" +
                                getString(R.string.open_the_box_in_the_game_slot_0_is_the_next_layer_of_boxes)
                    toast(msg)

                    cacheMapList = null
                    isEditingPlayer = true
                    currentTargetKey = "~local_player"
                    rootNbtData = finalPlayerRoot
                    nbtDataCache["~local_player"] = finalPlayerRoot
                    navigationStack.clear()
                    pathStack.clear()
                    scrollPositionStack.clear()
                    updateAdapter(rootNbtData)
                    if (tvCurrentPath != null) tvCurrentPath!!.setText(getString(R.string.text_player_data_has_been_modified))
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Puzzle map generation failed", e)
                val fullStack: String = getFullStackTrace(e)
                runOnUiThread {
                    dismissProgressDialog()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.title_operation_failed))
                        .setMessage(fullStack)
                        .setPositiveButton(getString(R.string.btn_close), null)
                        .show()
                }
            }
        }.start()
    }

    // 递归打包引擎 (修复版：去除内部 List 的双重包装)
    private fun recursivePackItems(
        items: MutableList<JsonObject>,
        limit: Int,
        layer: Int
    ): MutableList<JsonObject> {
        // 递归终止条件
        if (items.size <= limit) {
            return items
        }

        val packedContainers: MutableList<JsonObject> = ArrayList<JsonObject>()
        val containerCount = ceil(items.size.toDouble() / 27.0).toInt()

        for (i in 0..<containerCount) {
            // 1. 创建容器物品 (箱子)
            val chestItem = JsonObject()
            chestItem.add("Name", wrapTag(8, "minecraft:chest"))
            chestItem.add("Count", wrapTag(1, 1.toByte()))
            chestItem.add("Damage", wrapTag(2, 0.toShort()))
            chestItem.add("WasPickedUp", wrapTag(1, 0.toByte()))

            // 2. 补全 Block 标签
            val blockTag = JsonObject()
            blockTag.addProperty("t", 10)
            val blockContent = JsonObject()
            blockContent.add("name", wrapTag(8, "minecraft:chest"))
            val statesTag = JsonObject()
            statesTag.addProperty("t", 10)
            statesTag.add("v", JsonObject())
            blockContent.add("states", statesTag)
            blockContent.add("version", wrapTag(3, 18168865))
            blockTag.add("v", blockContent)
            chestItem.add("Block", blockTag)

            // 3. 填充容器内容
            val tagTag = JsonObject()
            tagTag.addProperty("t", 10)
            val tagContent = JsonObject()

            val itemsListTag = JsonObject()
            itemsListTag.addProperty("t", 9)
            itemsListTag.addProperty("itemType", 10)
            val itemsArr = JsonArray()

            val start = i * 27
            val end = min(start + 27, items.size)

            for (k in start..<end) {
                // 取出物品 (注意：items 里的已经是 Content 对象了)
                val innerItem = items.get(k)


                // 修改 Slot 为箱子内的位置 (0-26)
                // 注意：这里需要确保 innerItem 是独立的引用，防止多层引用问题
                // 但由于我们是层层新建的，这里直接改 Slot 没问题
                innerItem.add("Slot", wrapTag(1, (k - start).toByte()))


                // 【核心修复】直接添加 innerItem，不要加 wrapper！
                // BedrockParser 写 List 时会自动处理
                itemsArr.add(innerItem)
            }

            itemsListTag.add("v", itemsArr)
            tagContent.add("Items", itemsListTag)

            // 4. 命名
            val displayTag = JsonObject()
            displayTag.addProperty("t", 10)
            val displayContent = JsonObject()
            // 命名格式：Storage Layer 1 - Box 1
            displayContent.add("Name", wrapTag(8, "Storage L" + layer + " - Box " + (i + 1)))
            displayTag.add("v", displayContent)
            tagContent.add("display", displayTag)

            tagTag.add("v", tagContent)
            chestItem.add("tag", tagTag)

            packedContainers.add(chestItem)
        }


        // 继续递归，看看打包后的箱子是否能放进背包
        return recursivePackItems(packedContainers, limit, layer + 1)
    }

    // 辅助方法：快速创建 {t:?, v:?} 对象
    private fun wrapTag(type: Int, value: Any?): JsonObject {
        val tag = JsonObject()
        tag.addProperty("t", type)
        if (value is Number) tag.addProperty("v", value)
        else if (value is String) tag.addProperty("v", value)
        else if (value is Boolean) tag.addProperty("v", value)
        return tag
    }

    // 【新增】智能检查数据库状态，如果未加载则自动加载，然后执行任务
    private fun ensureDbLoaded(nextTask: Runnable) {
        // 1. 如果已经加载好了，直接执行
        val dbPath = currentWorkingDbPath
        if (dbPath != null && File(dbPath).exists()) {
            nextTask.run()
            return
        }

        // 2. 如果还没加载，检查有没有选存档
        val folder = etWorldName?.getText().toString()
        val hint = getString(R.string.hint_select_world)
        if (folder.isEmpty() || folder == hint || folder.contains("...")) {
            toast(getString(R.string.toast_please_click_the_blue_button_to_select_an_archive_first))
            return
        }

        // 3. 自动触发加载，加载完后执行 task
        // 注意：这里我们需要修改一下 loadPlayerData 让它支持回调
        // 由于不想大改 loadPlayerData，我们这里用一种稍微“脏”一点但有效的方法：
        // 我们魔改一下 loadPlayerData 的结束部分？

        // 不行，最稳妥的还是重载 loadPlayerData。
        // 请按下面的指示修改 loadPlayerData 的结尾。
        loadPlayerData(folder, nextTask)
    }

    // 【修改】重置当前会话状态
    private fun resetCurrentSession() {
        currentWorkingDbPath = null
        currentWorkingFileOrDir = null
        isEditingPlayer = false
        currentTargetKey = "~local_player"
        rootNbtData = null

        navigationStack.clear()
        pathStack.clear()
        scrollPositionStack.clear()
        nbtDataCache.clear()
        // 【新增】清空多任务会话缓存
        sessionCacheMap.clear()


        // 【新增】清空列表缓存
        cacheMapList = null
        cacheVillageList = null
        cachePlayerList = null

        if (nbtAdapter != null) {
            nbtAdapter = NbtAdapter(this, JsonObject())
            nbtListView!!.setAdapter(nbtAdapter)
        }
        if (nbtTreeAdapter != null) {
            // 如果用了树状图，也清空
            nbtTreeAdapter = NbtTreeAdapter(this, null)
            nbtListView!!.setAdapter(nbtTreeAdapter)
        }

        if (tvCurrentPath != null) {
            tvCurrentPath!!.setText(getString(R.string.text_loading_new_save))
        }


        // 5. (可选) 关闭侧边栏，防止误触
        val sidebar = findViewById<View?>(R.id.custom_sidebar_container)
        if (sidebar != null) sidebar.setVisibility(View.GONE)

        val tvName = findViewById<TextView?>(R.id.tv_app_title)
        val tvSeed = findViewById<TextView?>(R.id.tv_info_seed)
        if (tvName != null) tvName.setText(getString(R.string.app_name))
        if (tvSeed != null) tvSeed.setVisibility(View.GONE)
    }

    // 通用列表弹窗构建器 (完美交互版：点击垃圾桶显隐复选框)
    private fun showListDialogInternal(dataList: MutableList<String>, type: Int) {
        val baseTitle: String
        if (type == TYPE_MAP) baseTitle = getString(R.string.msg_map_data)
        else if (type == TYPE_VILLAGE) baseTitle = getString(R.string.mag_village_data)
        else baseTitle = getString(R.string.label_player_data)

        if (dataList.isEmpty()) {
            toast(getString(R.string.toast_currently_none) + baseTitle + getString(R.string.title_you_can_click_more_pperations_to_create_a_new))
        }

        val adapter = FuzzyArrayAdapter(this@MainActivity, dataList)
        val listView = ListView(this@MainActivity)
        listView.adapter = adapter

        val emptyView = TextView(this@MainActivity)
        emptyView.text = getString(R.string.text_no_data_or_no_search_results_found)
        emptyView.gravity = Gravity.CENTER

        val container = FrameLayout(this@MainActivity)
        container.addView(listView)
        container.addView(emptyView)
        listView.emptyView = emptyView

        val inflater = LayoutInflater.from(this)
        @SuppressLint("InflateParams")
        val customTitleView = inflater.inflate(R.layout.dialog_title_with_search, null, false)

        val tvTitle = customTitleView.findViewById<TextView>(R.id.tv_dialog_title)
        val btnSearch = customTitleView.findViewById<Button>(R.id.btn_search_toggle)
        val searchContainer = customTitleView.findViewById<View>(R.id.search_container)
        val etSearch = customTitleView.findViewById<EditText>(R.id.et_search_input)
        val btnClear = customTitleView.findViewById<View>(R.id.btn_clear_search)
        val btnBatchDelete = customTitleView.findViewById<Button>(R.id.btn_batch_delete)

        tvTitle.text = getString(R.string.title_with_count, baseTitle, dataList.size)

        // === [修改] 批量删除按钮点击事件 ===
        btnBatchDelete.setOnClickListener {
            // 1. 如果当前不是选择模式 -> 开启选择模式
            if (!adapter.isSelectionMode()) {
                adapter.setSelectionMode(true)
                toast(getString(R.string.toast_please_check_the_items_you_want_to_delete))
                return@setOnClickListener
            }

            // 2. 如果已经是选择模式 -> 检查是否有选中项
            val toDelete = adapter.selectedList

            // 如果没选任何东西，再次点击垃圾桶视为"取消/退出模式"
            if (toDelete.isEmpty()) {
                adapter.setSelectionMode(false)
                toast(getString(R.string.toast_exited_from_batch_management))
                return@setOnClickListener
            }

            // 3. 有选中项 -> 弹出确认删除
            AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.title_batch_delete))
                .setMessage(
                    getString(R.string.msg_are_you_sure_you_want_to_delete_the_selected) + toDelete.size + getString(
                        R.string.msg_item_this_action_is_irreversible
                    )
                )
                .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                    showProgressDialog(getString(R.string.msg_processing_ing), getString(R.string.msg_are_deleting))
                    val dbPath = currentWorkingDbPath
                    Thread {
                        try {
                            val db = PlayerDbManager(dbPath!!)
                            for (key in toDelete) {
                                db.deleteKey(key)
                            }
                            db.close()

                            runOnUiThread {
                                dismissProgressDialog()
                                for (key in toDelete) {
                                    adapter.remove(key)
                                    dataList.remove(key)
                                }
                                // 删除完成后，自动退出选择模式
                                adapter.setSelectionMode(false)

                                tvTitle.text = getString(R.string.title_with_count, baseTitle, adapter.count)
                                toast(
                                    getString(R.string.toast_successfully_deleted) + toDelete.size + getString(
                                        R.string.toast_deleted_item
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            val errorMsg = e.toString()
                            runOnUiThread {
                                dismissProgressDialog()
                                toast(getString(R.string.toast_delete_failed) + errorMsg)
                            }
                        }
                    }.start()
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }

        // 搜索按钮
        btnSearch.setOnClickListener {
            if (searchContainer.isVisible) {
                searchContainer.isVisible = false
                etSearch.text.clear()
                adapter.filter.filter(null)
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
            } else {
                searchContainer.isVisible = true
                etSearch.isFocusable = true
                etSearch.isFocusableInTouchMode = true
                etSearch.requestFocus()
                etSearch.postDelayed({
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager?
                    etSearch.requestFocus()
                    imm?.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
                }, 200)
            }
        }

        btnClear.setOnClickListener { etSearch.text.clear() }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                adapter.filter.filter(s)
                btnClear.isVisible = s.isNotEmpty()
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        val dialog = AlertDialog.Builder(this@MainActivity)
            .setCustomTitle(customTitleView)
            .setNeutralButton(getString(R.string.btn_more_actions)) { _, _ ->
                showPlayerRootMenu(dataList, type)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .setView(container)
            .create()

        dialog.setOnShowListener {
            val window = dialog.window
            if (window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            }
        }

// 列表点击事件 (为了更好的体验，在选择模式下，点击文字也相当于勾选)
        listView.setOnItemClickListener { _, view, pos, _ ->
            val selectedKey = adapter.getItem(pos)
            // 【核心优化】如果是选择模式，点击行 = 勾选/取消勾选
            if (adapter.isSelectionMode()) {
                view?.findViewById<CheckBox>(R.id.cb_item_select)?.performClick()
                return@setOnItemClickListener
            }

            // 正常模式：加载编辑
            loadSpecificPlayer(selectedKey)
            dialog.dismiss()
            findViewById<View?>(R.id.custom_sidebar_container)?.isVisible = false
        }

// 列表长按 (保留)
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            // 如果在选择模式，长按不触发菜单，避免冲突
            if (adapter.isSelectionMode()) return@setOnItemLongClickListener false

            val targetKey = adapter.getItem(pos)
            val ops = arrayOf<String?>(
                getString(R.string.msg_list_long_press_copy_data_json),
                getString(R.string.msg_list_long_press_paste_overlay),
                getString(R.string.msg_list_long_press_rename),
                getString(R.string.msg_list_long_press_delete)
            )

            AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.title_manage_prefix) + targetKey)
                .setItems(ops) { _, w ->
                    if (w == 0) copyPlayerJson(targetKey)
                    else if (w == 1) pastePlayerJson(targetKey) {
                        loadSpecificPlayer(targetKey)
                        dialog.dismiss()
                    }
                    else if (w == 2) renamePlayerKey(targetKey) {
                        invalidateListCache(type)
                        dialog.dismiss()
                        if (type == TYPE_MAP) showMapListDialog()
                        else if (type == TYPE_VILLAGE) showVillageListDialog()
                        else showMultiPlayerDialog()
                        toast(getString(R.string.toast_rename_successful))
                    }
                    else if (w == 3) deletePlayerKey(targetKey) {
                        adapter.remove(targetKey)
                        dataList.remove(targetKey)
                        tvTitle.text = getString(R.string.title_with_count, baseTitle, adapter.count)
                    }
                }.show()
            true
        }

        dialog.show()
    }

    // === 自定义模糊搜索适配器 (支持多选+模式切换版) ===
    private inner class FuzzyArrayAdapter(context: Context?, data: MutableList<String>) :
        BaseAdapter(), Filterable {
        private val originalList: MutableList<String>
        private var displayedList: MutableList<String>
        private val inflater: LayoutInflater

        var selectedItems: HashSet<String> = HashSet()

        // 【新增】是否处于选择模式
        private var isSelectionMode = false

        init {
            this.originalList = ArrayList(data)
            this.displayedList = ArrayList(data)
            this.inflater = LayoutInflater.from(context)
        }

        // 【新增】切换模式方法
        fun setSelectionMode(enabled: Boolean) {
            this.isSelectionMode = enabled
            if (!enabled) selectedItems.clear() // 退出模式时清空已选

            notifyDataSetChanged()
        }

        fun isSelectionMode(): Boolean {
            return isSelectionMode
        }

        override fun getCount(): Int {
            return displayedList.size
        }

        override fun getItem(position: Int): String {
            return displayedList[position]
        }

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: inflater.inflate(R.layout.item_checkbox, parent, false)

            val currentKey = displayedList[position]

            val tv = view.findViewById<TextView>(R.id.tv_item_name)
            val cb = view.findViewById<CheckBox>(R.id.cb_item_select)

            tv.text = currentKey

            // 【核心修改】根据模式决定复选框是否显示
            if (isSelectionMode) {
                cb.isVisible = true
                // 绑定状态
                cb.setOnCheckedChangeListener(null)
                cb.isChecked = selectedItems.contains(currentKey)
            } else {
                cb.isVisible = false
            }

            // 复选框点击事件
            cb.setOnClickListener {
                if (cb.isChecked) selectedItems.add(currentKey)
                else selectedItems.remove(currentKey)
            }

            return view
        }

        fun remove(item: String?) {
            originalList.remove(item)
            displayedList.remove(item)
            selectedItems.remove(item)
            notifyDataSetChanged()
        }

        val selectedList: MutableList<String>
            get() = ArrayList(selectedItems)

        @Suppress("UNCHECKED_CAST")
        override fun getFilter(): Filter {
            return object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    val results = FilterResults()
                    val filtered: MutableList<String> = ArrayList()
                    if (constraint.isNullOrEmpty()) {
                        filtered.addAll(originalList)
                    } else {
                        val pattern = constraint.toString().lowercase(Locale.getDefault()).trim()
                        for (item in originalList) {
                            if (item.lowercase(Locale.getDefault()).contains(pattern)) filtered.add(item)
                        }
                    }
                    results.values = filtered
                    results.count = filtered.size
                    return results
                }

                override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                    @Suppress("UNCHECKED_CAST")
                    displayedList = results.values as MutableList<String>
                    notifyDataSetChanged()
                }
            }
        }
    }

    // NBT 编辑器内的即时搜索弹窗
    private fun showEditorSearchDialog() {
        val etSearch = EditText(this)
        etSearch.setHint(getString(R.string.text_search_key_or_translate))
        etSearch.setSingleLine(true)


        // 创建一个包含 EditText 的容器，设置边距
        val container = FrameLayout(this)
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(50, 20, 50, 0)
        etSearch.setLayoutParams(params)
        container.addView(etSearch)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_search_nbt_data))
            .setView(container)
            .setPositiveButton(getString(R.string.btn_close), null)
            .setNeutralButton(
                getString(R.string.btn_clear_filter)
            ) { _: DialogInterface?, _: Int ->
                if (nbtAdapter != null) nbtAdapter!!.filter(null)
            }
            .create()

        // 实时监听输入
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (nbtAdapter != null) {
                    nbtAdapter!!.filter(s.toString())
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })


        // 自动弹键盘
        dialog.setOnShowListener { _: DialogInterface? ->
            etSearch.isFocusable = true
            etSearch.isFocusableInTouchMode = true
            etSearch.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager?
            imm?.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
        }

        dialog.show()
    }

    // 【补全缺失的方法】将 NBT List 转换为 Map 格式以便展示
    private fun convertListToMap(listData: JsonObject): JsonObject {
        val fakeMap = JsonObject()
        val arr = listData.get("v").getAsJsonArray()
        val itemType = listData.get("itemType").getAsInt()

        for (i in 0..<arr.size()) {
            val wrapper = JsonObject()
            wrapper.addProperty("t", itemType)
            wrapper.add("v", arr.get(i))
            // 使用数字索引 "0", "1", "2" 作为 Key
            fakeMap.add(i.toString(), wrapper)
        }
        return fakeMap
    }

    // 【核心新增】根据路径列表，重建列表模式的导航栈 (树 -> 列表 同步)
    private fun syncListModeFromPath(path: MutableList<String?>?) {
        // 1. 重置到根
        navigationStack.clear()
        pathStack.clear()
        scrollPositionStack.clear()
        currentListData = rootNbtData // 从根开始

        if (path == null || path.isEmpty()) return

        // 2. 模拟逐层进入
        // currentListData 始终持有当前层级的内容（Map结构）
        var currentPtr = rootNbtData

        try {
            for (key in path) {
                // 确保当前层有这个 key
                if (!currentPtr!!.has(key)) break

                // 获取下一层的数据包装 {t, v}
                val itemWrapper = currentPtr.getAsJsonObject(key)
                val type = itemWrapper.get("t").getAsInt()

                // 准备进入下一层：先把当前层压栈
                navigationStack.push(currentPtr)
                pathStack.push(key)
                scrollPositionStack.push(0) // 默认滚到顶部

                // 解析下一层的内容 (剥壳)
                val v = itemWrapper.get("v")

                if (type == 10) {
                    // Compound: 直接取 v (它是 JsonObject)
                    currentPtr = v.getAsJsonObject()
                } else if (type == 9) {
                    // List: 需要转成 FakeMap ("0":{}, "1":{}...)
                    // 复用我们之前写的 convertListToMap
                    currentPtr = convertListToMap(itemWrapper)
                } else {
                    // 如果路径里混入了非容器（理论上不可能），停止
                    break
                }
            }
            // 循环结束，currentPtr 指向的就是目标层级的数据
            currentListData = currentPtr
        } catch (e: Exception) {
            // 如果同步过程中出错（比如结构变了），就停在出错的那一层，不至于崩
            toast(getString(R.string.toast_view_sync_partially_interrupted) + e.message)
        }
    }

    // 【新增】按名称打开 NBT 的弹窗逻辑
    private fun showOpenByKeyDialog() {
        // 1. 构建布局 (代码构建，复刻截图样式)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 20)

        // 提示文本
        val tvTip = TextView(this)
        tvTip.text = getString(R.string.text_open_nbt_by_name_hint)
        tvTip.textSize = 14f
        tvTip.setTextColor(Color.DKGRAY)
        layout.addView(tvTip)

        // 标题 "数据名"
        val tvLabel = TextView(this)
        tvLabel.text = getString(R.string.text_data_name)
        tvLabel.setTextColor(getColor(android.R.color.holo_blue_light))
        tvLabel.setPadding(0, 30, 0, 10)
        layout.addView(tvLabel)

        // 输入框
        val etKey = EditText(this)
        layout.addView(etKey)

        // Hex 复选框
        val cbHex = CheckBox(this)
        cbHex.text = getString(R.string.title_hex_16_hexadecimal_mode)
        layout.addView(cbHex)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_open_nbt_by_name))
            .setView(layout)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                val input = etKey.text.toString()
                if (input.isEmpty()) {
                    toast(getString(R.string.toast_input_cannot_be_empty))
                    return@setPositiveButton
                }

                val isHex = cbHex.isChecked
                loadCustomKey(input, isHex)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // 加载自定义 Key 的核心逻辑
    private fun loadCustomKey(inputStr: String, isHex: Boolean) {
        showProgressDialog(getString(R.string.msg_read), getString(R.string.msg_querying))

        Thread {
            try {
                val dbPath = currentWorkingDbPath ?: throw Exception("DB path is null")
                val db = PlayerDbManager(dbPath)

                val keyBytes: ByteArray = if (isHex) {
                    // Hex 模式：将字符串转为 byte[]
                    try {
                        hexStringToByteArray(inputStr)
                    } catch (_: Exception) {
                        throw Exception(getString(R.string.msg_hex_format_error))
                    }
                } else {
                    // 普通文本模式：UTF-8
                    inputStr.toByteArray(Charsets.UTF_8)
                }

                // 读取数据
                val data = db.readRawKey(keyBytes)
                db.close()

                // 解析 NBT
                val json = parseBytes(data)

                runOnUiThread {
                    dismissProgressDialog()
                    // 更新界面
                    isEditingPlayer = true
                    currentTargetKey = inputStr

                    rootNbtData = json
                    navigationStack.clear()
                    pathStack.clear()
                    scrollPositionStack.clear()
                    updateAdapter(rootNbtData)

                    val displayText = if (isHex) {
                        getString(R.string.text_hex_pre, inputStr)
                    } else {
                        inputStr
                    }
                    tvCurrentPath?.text = getString(R.string.title_current, displayText)
                    toast(getString(R.string.toast_loading_successfully))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dismissProgressDialog()
                    toast(getString(R.string.err_load_failed_with_msg, e.message.orEmpty()))
                }
            }
        }.start()
    }

    // 【新增】数据库修复询问弹窗
    private fun showDbRepairConfirmDialog(
        dbPath: String,
        folderName: String?
    ) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_database_corruption))
            .setMessage(
                getString(R.string.msg_corrupt_or_missing_leveldb_data_file_detected) +
                        getString(R.string.msg_have_you_tried_a_brute_force_repair) +
                        getString(R.string.msg_warning_repair_process_may_discard_unreadable_data_blocks)
            )
            .setPositiveButton(
                getString(R.string.btn_try_to_fix)
            ) { _: DialogInterface?, _: Int ->
                performDbRepair(
                    dbPath,
                    folderName
                )
            }
            .setNegativeButton(
                getString(R.string.btn_cancel)
            ) { _: DialogInterface?, _: Int ->
                toast(getString(R.string.toast_operation_canceled_please_check_archive_integrity))
            }
            .setCancelable(false) // 禁止点击外部关闭，必须选一个
            .show()
    }

    // 【新增】执行修复并自动重试
    private fun performDbRepair(
        dbPath: String,
        folderName: String?
    ) {
        showProgressDialog(getString(R.string.msg_under_repair), getString(R.string.msg_trying_to_rebuild_data_index))

        Thread {
            try {
                // 调用静态修复方法
                PlayerDbManager.tryRepair(dbPath)

                runOnUiThread {
                    dismissProgressDialog()
                    toast(getString(R.string.toast_repair_completed_trying_to_load_again))

                    // 修复成功后，递归调用 loadPlayerData 重新走一遍流程
                    // 注意：这里我们不需要重新复制文件了，直接用修好的 dbPath
                    // 但为了逻辑简单，重新调用 loadPlayerData 是最稳妥的（虽然会再次触发复制逻辑，但无伤大雅）
                    // 更好的方式是直接跳到读取步骤，但鉴于 loadPlayerData 结构复杂，
                    // 我们这里选择让用户手动重试或者自动重试

                    // 这里的自动重试有点复杂，因为 loadPlayerData 会生成新的 working_db
                    // 所以这里我们其实应该直接 再次尝试打开 DB 并显示

                    // 简便方案：直接重新执行 loadPlayerData
                    // 这里的风险是：重新复制会不会把刚才修好的文件覆盖了？
                    // 答案是：会的！因为 loadPlayerData 会重新从源目录 cp。
                    // 所以，修复必须是在 working_db 上修复，然后立即读取，不能重走 loadPlayerData。

                    // === 修正方案：手动触发读取流程 ===
                    retryLoadAfterRepair(dbPath, folderName)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dismissProgressDialog()
                    toast(getString(R.string.toast_repair_failed) + e.message)
                }
            }
        }.start()
    }

    // 【新增】修复成功后的重试逻辑 (复用 loadPlayerData 的后半部分)
    private fun retryLoadAfterRepair(dbPath: String, folderName: String?) {
        showProgressDialog(getString(R.string.msg_retrying), getString(R.string.msg_reading_repaired_data))
        Thread {
            try {
                // 此时 dbPath 已经是修好的了，直接读
                val dbManager = PlayerDbManager(dbPath)
                val data = dbManager.readLocalPlayer()
                dbManager.close()

                val playerDataObj = parseBytes(data)

                runOnUiThread {
                    dismissProgressDialog()
                    isEditingPlayer = true
                    navigationStack.clear()
                    pathStack.clear()
                    scrollPositionStack.clear()
                    rootNbtData = playerDataObj

                    // 存入缓存
                    nbtDataCache.put("~local_player", rootNbtData)

                    updateAdapter(rootNbtData)
                    toast(getString(R.string.toast_player_loaded_success))
                    tvCurrentPath?.text = getString(R.string.path_editing_player, folderName)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dismissProgressDialog()
                    toast(getString(R.string.toast_retry_failed) + e)
                }
            }
        }.start()
    }

    // 【新增】创建 .nomedia 文件，防止系统媒体服务扫描导致崩溃
    private fun createNoMedia() {
        try {
            val bridgeDir = File(BRIDGE_ROOT)
            if (!bridgeDir.exists()) bridgeDir.mkdirs()

            val noMedia = File(bridgeDir, ".nomedia")
            if (!noMedia.exists()) {
                noMedia.createNewFile()
            }
        } catch (_: Exception) {
            // 忽略错误，这个文件不关键，只是为了防崩
        }
    }

    // 【新增】给指定目录贴上“隐身符”，防止媒体扫描
    private fun ensureNoMedia(dir: File) {
        try {
            if (!dir.exists()) dir.mkdirs()
            val noMedia = File(dir, ".nomedia")
            if (!noMedia.exists()) {
                noMedia.createNewFile()
            }
        } catch (_: Exception) {
            // 忽略错误，不影响主流程
        }
    }

    // 工具：Hex 字符串转 Byte 数组
    private fun hexStringToByteArray(s: String): ByteArray {
        // 去除空格
        val hex = s.replace(" ", "")
        val len = hex.length
        require(len % 2 == 0) { getString(R.string.msg_the_length_must_be_an_even_number) }

        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            val high = hex[i].digitToIntOrNull(16)
                ?: throw IllegalArgumentException("Invalid hex character: ${hex[i]}")
            val low = hex[i + 1].digitToIntOrNull(16)
                ?: throw IllegalArgumentException("Invalid hex character: ${hex[i + 1]}")
            data[i / 2] = ((high shl 4) + low).toByte()
        }
        return data
    }

    // 4. 全局系统数据列表 (硬编码的特殊 Key)
    private fun showGlobalDataDialog() {
        // 定义 Key 和 描述的映射关系
        val globalKeys = arrayOf<Array<String?>?>(
            arrayOf<String?>(
                "LevelChunkMetaDataDictionary",
                getString(R.string.msg_global_system_data_list_metadata)
            ),
            arrayOf<String?>(
                "BiomeData",
                getString(R.string.msg_global_system_data_list_community)
            ),
            arrayOf<String?>(
                "Overworld",
                getString(R.string.msg_global_system_data_list_main_world_data)
            ),
            arrayOf<String?>(
                "Nether",
                getString(R.string.msg_global_system_data_list_iower_bound_data)
            ),
            arrayOf<String?>("TheEnd", getString(R.string.msg_global_system_data_list_end_data)),
            arrayOf<String?>("Villages", getString(R.string.msg_global_system_data_list_village)),
            arrayOf<String?>("Portals", getString(R.string.msg_global_system_data_list_portal)),
            arrayOf<String?>(
                "AutonomousEntities",
                getString(R.string.msg_global_system_data_list_autonoous_entity)
            ),
            arrayOf<String?>(
                "schedulerWT",
                getString(R.string.msg_global_system_data_list_scheduler)
            ),
            arrayOf<String?>(
                "Scoreboard",
                getString(R.string.msg_global_system_data_list_scoreboard_data)
            ),
            arrayOf<String?>(
                "Mobevents",
                getString(R.string.msg_global_system_data_list_biological_events)
            ),
            arrayOf<String?>(
                "PositionTrackDBLastID",
                getString(R.string.msg_global_system_data_list_last_targeting_id)
            ),
            arrayOf<String?>(
                "dimension0",
                getString(R.string.msg_global_system_data_list_main_world_0)
            ),
            arrayOf<String?>(
                "dimension1",
                getString(R.string.msg_global_system_data_list_nether_1)
            ),
            arrayOf<String?>("dimension2", getString(R.string.msg_global_system_data_list_end_2))
        )

        // 构建显示列表 (Key + 描述)
        val displayList: MutableList<String?> = ArrayList<String?>()
        val realKeys: MutableList<String> = ArrayList<String>()

        for (pair in globalKeys) {
            // 格式：Scoreboard (计分板数据)
            displayList.add(pair!![0] + "\n" + pair[1])
            realKeys.add(pair[0]!!)
        }

        // 构建 UI
        val listView = ListView(this)
        // 使用 simple_list_item_2 可以显示两行文字 (Title + Subtitle) 但需要自定义 adapter
        // 这里为了简单，我们用 simple_list_item_1 配合换行符
        val adapter: ArrayAdapter<String?> =
            object : ArrayAdapter<String?>(this, android.R.layout.simple_list_item_1, displayList) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    // 小优化：让显示更好看一点，把字体改小
                    val view = super.getView(position, convertView, parent) as TextView
                    view.setTextSize(14f) // 稍微小一点
                    view.setPadding(30, 20, 30, 20) // 增加间距
                    return view
                }
            }
        listView.setAdapter(adapter)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.msg_global_system_data) + displayList.size + ")")
            .setView(listView)
            .setNegativeButton(getString(R.string.btn_close), null)
            .create()

        listView.setOnItemClickListener { _: AdapterView<*>?, _: View?, position: Int, _: Long ->
            val key = realKeys.get(position)
            // 尝试加载
            // 这里的 loadSpecificPlayer 其实是通用的 loadByKey，直接复用
            loadSpecificPlayer(key)
            dialog.dismiss()
        }


        // 可选：长按复制 Key
        listView.setOnItemLongClickListener { _: AdapterView<*>?, _: View?, pos: Int, _: Long ->
            val key: String = realKeys[pos]
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Key", key))
            toast(getString(R.string.toast_key_copied, key))
            true
        }

        dialog.show()
    }

    // 创建新的全局数据
    private fun createNewGlobalData(key: String?) {
        // 创建一个空的 Compound NBT
        val emptyNbt = JsonObject()

        currentTargetKey = key
        isEditingPlayer = true
        rootNbtData = emptyNbt

        navigationStack.clear()
        pathStack.clear()
        scrollPositionStack.clear()

        updateAdapter(rootNbtData)

        tvCurrentPath?.text = getString(R.string.title_current_new, key.orEmpty())

        // 只有当你点击保存时，才会真正写入数据库
        toast(getString(R.string.toast_empty_data_has_been_created_please_edit_and_save))
    }

    // 【修改版】更新标题栏显示世界信息
    private fun updateWorldInfoCard(rootJson: JsonObject?) {
        val tvName = findViewById<TextView?>(R.id.tv_app_title)
        val tvSeed = findViewById<TextView?>(R.id.tv_info_seed)

        if (tvName == null || tvSeed == null) return

        if (rootJson == null) {
            // 如果数据为空，恢复默认
            tvName.setText(getString(R.string.app_name))
            tvSeed.setVisibility(View.GONE)
            return
        }

        try {
            // 尝试提取 LevelName
            var nameStr: String? = null
            if (rootJson.has("LevelName")) {
                val obj = rootJson.getAsJsonObject("LevelName")
                if (obj.has("v")) nameStr = obj.get("v").getAsString()
            }

            // 尝试提取 RandomSeed
            var seedStr: String? = null
            if (rootJson.has("RandomSeed")) {
                val obj = rootJson.getAsJsonObject("RandomSeed")
                if (obj.has("v")) seedStr = obj.get("v").getAsString()
            }

            // 如果读到了名字，就更新标题
            if (nameStr != null && !nameStr.isEmpty()) {
                tvName.setText(nameStr)
            } else {
                tvName.setText(getString(R.string.app_name))
            }

            // 如果读到了种子，显示小字
            if (seedStr != null && !seedStr.isEmpty()) {
                tvSeed.setText(getString(R.string.key_seed_display))
                tvSeed.setVisibility(View.VISIBLE)
            } else {
                tvSeed.setVisibility(View.GONE)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 出错时恢复默认
            tvName.setText(getString(R.string.app_name))
            tvSeed.setVisibility(View.GONE)
        }
    }

    // 【新增】读取存档真实名称 (从 levelname.txt)
    private fun getWorldRealName(folderName: String): String? {
        val fullPath = currentWorldsPath + folderName + "/levelname.txt"
        val file = File(fullPath)

        // 1. 尝试原生读取
        if (file.exists() && file.canRead()) {
            try {
                val fis = FileInputStream(file)
                val reader = BufferedReader(InputStreamReader(fis))
                val name = reader.readLine()
                fis.close()
                if (name != null && !name.trim { it <= ' ' }.isEmpty()) return name
            } catch (e: Exception) {
                Log.w(ContentValues.TAG, "Failed to read levelname.txt: " + e.message)
            }
        }

        // 2. 尝试 Shizuku 读取 (针对 Android/data)
        if (checkShizukuAvailable()) {
            try {
                // 使用 cat 命令读取内容
                val p = runShizukuCmd(arrayOf("sh", "-c", "cat \"$fullPath\""))
                val reader = BufferedReader(InputStreamReader(p.getInputStream()))
                val name = reader.readLine()
                p.waitFor()
                if (name != null && !name.trim { it <= ' ' }.isEmpty()) return name
            } catch (e: Exception) {
                Log.w(ContentValues.TAG, "Shizuku read file failed: " + e.message)
            }
        }

        // 3. 读不到就返回 null
        return null
    }

    // 【核心】智能复制引擎
    @Throws(Exception::class)
    private fun smartCopy(src: File, dst: File) {
        if (useMultiThreading) {
            // 开启模式：使用多线程分片复制
            copyDirectoryParallel(src, dst)
        } else {
            // 关闭模式：使用传统单线程递归
            copyDirectory(src, dst)
        }
    }

    // 辅助：当数据变动时清空对应缓存
    private fun invalidateListCache(type: Int) {
        if (type == TYPE_MAP) cacheMapList = null
        else if (type == TYPE_VILLAGE) cacheVillageList = null
        else cachePlayerList = null
    }

    // 1. 切走前：把当前编辑状态存入内存
    private fun saveCurrentSessionToMemory() {
        if (rootNbtData == null) return


        // 决定 Session 的 Key
        val sessionKey: String?
        if (isEditingPlayer) {
            sessionKey = currentTargetKey // 例如 "~local_player", "map_123"
        } else {
            sessionKey = "level.dat" // 世界文件固定 Key
        }

        if (sessionKey == null) return

        // 打包状态
        val session = EditorSession(
            rootNbtData,
            navigationStack,
            pathStack,
            scrollPositionStack,
            isEditingPlayer,
            currentWorkingDbPath,
            currentTargetKey
        )


        // 存入 Map
        sessionCacheMap.put(sessionKey, session)
        // System.out.println("Session saved: " + sessionKey);
    }

    // 2. 切回来：尝试从内存恢复状态
    // 返回 true 表示恢复成功，不需要读盘了
    private fun tryRestoreSession(sessionKey: String?): Boolean {
        if (sessionCacheMap.containsKey(sessionKey)) {
            val session = sessionCacheMap.get(sessionKey)
            if (session == null) {
                return false
            }
            rootNbtData = session.data
            // 恢复栈 (拷贝回来)
            navigationStack.clear()
            navigationStack.addAll(session.navStack)
            pathStack.clear()
            pathStack.addAll(session.pathStack)
            scrollPositionStack.clear()
            scrollPositionStack.addAll(session.scrollStack)

            isEditingPlayer = session.isPlayerMode
            currentWorkingDbPath = session.dbPath
            currentTargetKey = session.targetKey


            // 刷新 UI
            // 如果栈里有东西，说明之前在子文件夹里，需要恢复到栈顶视图
            val viewData = if (!navigationStack.isEmpty()) navigationStack.peek() else rootNbtData

            updateAdapter(viewData)
            updatePathTitle()


            // 恢复列表滚动位置
            if (!scrollPositionStack.isEmpty()) {
                val pos = scrollPositionStack.peek()!!
                nbtListView!!.post { nbtListView!!.setSelection(pos) }
            }

            toast(getString(R.string.toast_unsaved_editing_session_restored))
            return true
        }
        return false
    }

    // 【新增】编辑会话状态类 (相当于一个后台标签页)
    private class EditorSession(// NBT 数据 (包含未保存的修改)
        var data: JsonObject?,
        n: Stack<JsonObject?>,
        p: Stack<String?>,
        s: Stack<Int?>,
        isP: Boolean,
        db: String?,
        k: String?
    ) {
        var navStack: Stack<JsonObject?> // 导航历史
        var pathStack: Stack<String?> // 路径历史
        var scrollStack: Stack<Int?> // 滚动位置
        var isPlayerMode: Boolean // 是不是 DB 模式
        var dbPath: String? // DB 路径
        var targetKey: String? // 编辑的 Key

        // 构造函数：保存当前现场
        init {
            // 必须深拷贝栈，因为主界面的栈会被 clear
            this.navStack = Stack<JsonObject?>()
            this.navStack.addAll(n)
            this.pathStack = Stack<String?>()
            this.pathStack.addAll(p)
            this.scrollStack = Stack<Int?>()
            this.scrollStack.addAll(s)
            this.isPlayerMode = isP
            this.dbPath = db
            this.targetKey = k
        }
    }

    // 【简化】打开 SAF 目录选择器（AIDE+ 兼容）
    private fun openSafPathSelector() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            startActivityForResult(intent, REQUEST_SAF_PATH)
        } catch (e: Exception) {
            toast(getString(R.string.msg_saf_unavailable, e.message.orEmpty()))
        }
    }

    private fun showAppInfoDialog() {
        val builder = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_app_info, null, false)
        builder.setView(dialogView)

        val dialog = builder.create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialog.show()

        dialog.window?.let {
            val params = it.attributes
            params.width = (resources.displayMetrics.widthPixels * 0.85).toInt()
            it.attributes = params
        }

        // ===== 关于展开逻辑 =====
        val aboutHeader = dialogView.findViewById<LinearLayout?>(R.id.layout_about_header)
        val aboutContent = dialogView.findViewById<TextView?>(R.id.tv_about_content)
        val aboutExpand = dialogView.findViewById<TextView?>(R.id.tv_about_expand)

        if (aboutContent != null) {
            val html = getString(R.string.msg_about_content_full)
            @Suppress("DEPRECATION")
            val message: CharSequence = Html.fromHtml(html.replace("\n", "<br>"))
            aboutContent.text = message
            aboutContent.movementMethod = LinkMovementMethod.getInstance()
        }

        aboutHeader?.setOnClickListener {
            if (aboutContent != null) {
                val isExpanded = aboutContent.isVisible
                aboutContent.isVisible = !isExpanded
                aboutExpand?.text = if (!isExpanded) getString(R.string.text_expand_up) else getString(R.string.text_expand_down)
            }
        }

        // ===== 设置展开逻辑 =====
        val settingsHeader = dialogView.findViewById<LinearLayout?>(R.id.layout_settings_header)
        val settingsContent = dialogView.findViewById<LinearLayout?>(R.id.layout_settings_content)
        val settingsExpand = dialogView.findViewById<TextView?>(R.id.tv_settings_expand)

        settingsHeader?.setOnClickListener {
            if (settingsContent != null) {
                val isExpanded = settingsContent.isVisible
                settingsContent.isVisible = !isExpanded
                settingsExpand?.text = if (!isExpanded) getString(R.string.text_expand_up) else getString(R.string.text_expand_down)
            }
        }

        // ===== 四个设置项绑定 =====

        // 1. 多线程
        val cbMulti = dialogView.findViewById<CheckBox?>(R.id.cb_dialog_multithread)
        cbMulti?.isChecked = useMultiThreading
        cbMulti?.setOnCheckedChangeListener { _, isChecked: Boolean ->
            useMultiThreading = isChecked
            prefs?.edit { putBoolean("use_multithread", isChecked) }
            toast(
                if (isChecked)
                    getString(R.string.toast_multi_thread_acceleration_enabled)
                else
                    getString(R.string.toast_switched_to_single_threaded_stable_mode)
            )
        }

        // 2. Shizuku
        val cbShizuku = dialogView.findViewById<CheckBox?>(R.id.cb_dialog_shizuku)
        cbShizuku?.isChecked = useShizuku
        cbShizuku?.setOnCheckedChangeListener { _, isChecked: Boolean ->
            useShizuku = isChecked
            prefs?.edit { putBoolean("use_shizuku", isChecked) }
            toast(if (isChecked) getString(R.string.toast_shizuku_enabled) else getString(R.string.toast_shizuku_disabled))
        }

        // 3. 日夜模式
        val btnTheme = dialogView.findViewById<Button?>(R.id.btn_dialog_theme)
        if (btnTheme != null) {
            btnTheme.text = if (isNightMode) getString(R.string.action_switch_day) else getString(R.string.action_switch_night)
            btnTheme.setOnClickListener {
                isNightMode = !isNightMode
                prefs?.edit { putBoolean("night_mode", isNightMode) }
                applyTheme()
                dialog.dismiss()
                recreate()
            }
        }

        // 4. 语言切换
        val btnLang = dialogView.findViewById<Button?>(R.id.btn_dialog_lang)
        if (btnLang != null) {
            btnLang.text = if ("zh" == currentLang) getString(R.string.switch_to_english) else getString(R.string.switch_to_chinese)
            btnLang.setOnClickListener {
                currentLang = if ("zh" == currentLang) "en" else "zh"
                prefs?.edit { putString("app_language", currentLang) }

                // 暂存数据
                tempNbtData = rootNbtData
                tempTargetKey = currentTargetKey

                reset()
                dialog.dismiss()
                recreate()
            }
        }

        // 【新增】调试菜单触发器（点击图标10次）
        val clickCount = intArrayOf(0)
        val lastClickTime = longArrayOf(0)
        val headerLayout = dialogView.findViewById<View?>(R.id.in_app_icon)

        headerLayout?.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            // 重置计数（如果超过5秒未点击）
            if (currentTime - lastClickTime[0] > 5000) {
                clickCount[0] = 0
            }
            lastClickTime[0] = currentTime

            clickCount[0]++

            // 显示点击进度（第5次后显示提示）
            if (clickCount[0] == 5) {
                toast(getString(R.string.toast_dev_mode_hint))
            }

            // 达到10次，显示调试菜单
            if (clickCount[0] >= 10) {
                clickCount[0] = 0
                showDebugMenu()
            }
        }
    }

    // 【新增】统一自动填充数据模型
    private class AutocompleteEntry(
        var name: String, var namespace: String, var category: String, // 【新增】纯数字ID（用于效果和附魔）
        var id: String?
    ) {
        var searchKey: String?

        init {
            // 【搜索键】包含中文、namespace、数字ID
            this.searchKey =
                (name + " " + namespace + " " + (if (id != null) id else "")).lowercase(
                    Locale.getDefault()
                )
        }

        val fillValue: String?
            // 【获取填充值】效果和附魔返回数字ID，其他返回minecraft:namespace
            get() {
                if (id != null && !id!!.isEmpty()) {
                    return id // 纯数字ID
                }
                return namespace // 带minecraft:的namespace
            }

        override fun toString(): String {
            return name
        }
    }

    // 【新增】统一加载四类数据
    private fun loadAutocompleteData(dataType: String): MutableList<AutocompleteEntry> {
        val list: MutableList<AutocompleteEntry> = ArrayList<AutocompleteEntry>()
        val langFolder = if ("zh" == currentLang) "zh" else "en"

        try {
            when (dataType) {
                "item" -> loadJsonToList(list, langFolder + "/item_translation.json", "item")
                "block" -> loadJsonToList(list, langFolder + "/block_translation.json", "block")
                "effect" -> loadJsonToList(list, langFolder + "/potion_translation.json", "effect")
                "enchant" -> loadJsonToList(list, langFolder + "/ench_translation.json", "enchant")
                "all" -> {
                    // 加载所有（用于物品+方块混合场景）
                    loadJsonToList(list, langFolder + "/item_translation.json", "item")
                    loadJsonToList(list, langFolder + "/block_translation.json", "block")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return list
    }

    @Throws(Exception::class)
    private fun loadJsonToList(
        list: MutableList<AutocompleteEntry>,
        assetPath: String,
        category: String
    ) {
        val inputStream = assets.open(assetPath)
        val json = BufferedReader(InputStreamReader(inputStream)).use { reader ->
            reader.readText()
        }

        val array = JsonParser.parseString(json).asJsonArray
        for (i in 0 until array.size()) {
            val obj = array[i].asJsonObject
            val name = if (obj.has("name")) obj.get("name").asString else ""
            var namespace = if (obj.has("namespace")) obj.get("namespace").asString else ""
            val id = if (obj.has("id")) obj.get("id").asString else null

            // 效果和附魔：不添加minecraft:前缀，保留原始namespace（如protection）
            // 其他：添加minecraft:前缀
            if (category != "effect" && category != "enchant") {
                if (!namespace.contains(":")) {
                    namespace = "minecraft:$namespace"
                }
            }

            if (namespace.isNotEmpty()) {
                list.add(AutocompleteEntry(name, namespace, category, id))
            }
        }
    }

    // 【新增】根据上下文智能判断数据类型
    private fun detectAutocompleteType(
        parent: String?,
        grandParent: String?
    ): String {
        // 药水效果
        if (parent == "ActiveEffects" || grandParent == "ActiveEffects" ||
            parent == "MobEffects" || parent == "Effects"
        ) {
            return "effect"
        }

        // 附魔（支持多种键名）
        if (parent == "ench" || parent == "Enchantments" ||
            parent == "StoredEnchantments" ||
            grandParent == "ench" || grandParent == "Enchantments"
        ) {
            return "enchant"
        }

        // 默认：物品+方块混合
        return "all"
    }

    // 获取类型显示标签
    private fun getTypeLabel(type: String): String {
        when (type) {
            "item" -> return getString(R.string.title_item)
            "block" -> return getString(R.string.title_block)
            "effect" -> return getString(R.string.title_effect)
            "enchant" -> return getString(R.string.title_enchant)
            "all" -> return getString(R.string.title_all)
            else -> return getString(R.string.title_data)
        }
    }

    // 【新增】统一的自动填充对话框
    private fun showAutocompleteDialog(
        targetInput: EditText,
        dataType: String
    ) {
        // 创建布局
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(24, 16, 24, 16)

        // 搜索框
        val etSearch = EditText(this)
        etSearch.hint = getString(R.string.title_search) + getTypeLabel(dataType) + "..."
        etSearch.textSize = 14f
        layout.addView(etSearch)

        // 清空按钮
        val btnClear = Button(this)
        btnClear.text = getString(R.string.btn_clear)
        btnClear.setTextColor("#2196F3".toColorInt())
        btnClear.setBackgroundColor(Color.TRANSPARENT)
        layout.addView(btnClear)

        // 列表
        val lvSuggestions = ListView(this)
        @Suppress("DEPRECATION")
        lvSuggestions.divider = "#EEEEEE".toColorInt().toDrawable()
        lvSuggestions.dividerHeight = 1
        layout.addView(
            lvSuggestions, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 400
            )
        )

        // 加载数据 (这是不可变的完整数据源)
        val allItems = loadAutocompleteData(dataType)

        // 【修复1】：传入 adapter 的 List 必须是 allItems 的副本！
        // 这样 adapter 在 clear() 和 addAll() 时，操作的是副本，不会破坏原有的 allItems
        val displayList: MutableList<AutocompleteEntry?> = ArrayList(allItems)

        // 适配器
        val adapter: ArrayAdapter<AutocompleteEntry?> = object : ArrayAdapter<AutocompleteEntry?>(
            this,
            android.R.layout.simple_list_item_2, displayList
        ) {
            // 【修复2】：缓存 Filter 实例，避免每次 getFilter 都创建新对象导致异步线程冲突
            private var mFilter: Filter? = null

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                var convertView = convertView
                if (convertView == null) {
                    convertView = getLayoutInflater().inflate(
                        android.R.layout.simple_list_item_2,
                        parent,
                        false
                    )
                }
                val item = getItem(position)
                val tv1 = convertView!!.findViewById<TextView>(android.R.id.text1)
                val tv2 = convertView.findViewById<TextView>(android.R.id.text2)

                // 主标题
                var label = ""
                if (item == null) return convertView
                when (item.category) {
                    "block" -> label = getString(R.string.title_block_label)
                    "item" -> label = getString(R.string.title_item_label)
                    "effect" -> label = getString(R.string.title_effect_label)
                    "enchant" -> label = getString(R.string.title_enchant_label)
                }
                tv1.text = getString(R.string.text_autocomplete_label_name, label, item.name)
                @Suppress("DEPRECATION")
                tv1.setTextColor("#333333".toColorInt())
                tv1.textSize = 15f

                // 副标题
                val subtitle: String?
                if ("effect" == item.category || "enchant" == item.category) {
                    subtitle = "ID:" + item.id + " | " + item.namespace
                } else {
                    subtitle = item.namespace
                }
                tv2.text = subtitle
                @Suppress("DEPRECATION")
                tv2.setTextColor("#666666".toColorInt())
                tv2.textSize = 12f

                return convertView
            }

            override fun getFilter(): Filter {
                // 【修复2】：单例模式返回 Filter
                if (mFilter == null) {
                    mFilter = object : Filter() {
                        override fun performFiltering(constraint: CharSequence?): FilterResults {
                            val results = FilterResults()
                            val filtered: MutableList<AutocompleteEntry?> = ArrayList()

                            // constraint 为空时，返回完整的 allItems (这里 allItems 始终是完整的，因为没被破坏)
                            if (constraint == null || constraint.length == 0) {
                                filtered.addAll(allItems)
                            } else {
                                val pattern = constraint.toString().lowercase(Locale.getDefault()).trim()

                                // 永远在完整的 allItems 里查找
                                for (item in allItems) {
                                    var match = false

                                    if (item.namespace.lowercase(Locale.getDefault()) == pattern ||
                                        (item.id != null && item.id == pattern)
                                    ) {
                                        match = true
                                    } else if (item.name.lowercase(Locale.getDefault()).contains(pattern)) {
                                        match = true
                                    } else if (item.namespace.lowercase(Locale.getDefault()).contains(pattern)) {
                                        match = true
                                    } else if ((item.category == "effect" || item.category == "enchant")
                                        && item.id != null && item.id!!.contains(pattern)
                                    ) {
                                        match = true
                                    }

                                    if (match) filtered.add(item)
                                }
                            }

                            results.values = filtered
                            results.count = filtered.size
                            return results
                        }

                        override fun publishResults(
                            constraint: CharSequence?,
                            results: FilterResults
                        ) {
                            clear() // 这里清空的是 displayList 副本
                            if (results.values != null) {
                                @Suppress("UNCHECKED_CAST")
                                addAll(results.values as MutableList<AutocompleteEntry?>)
                            }
                            notifyDataSetChanged()
                        }
                    }
                }
                return mFilter!!
            }
        }

        lvSuggestions.adapter = adapter

        btnClear.setOnClickListener { etSearch.setText("") }

        // 创建对话框
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_choose) + getTypeLabel(dataType))
            .setView(layout)
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .create()

        if (dialog.window != null) {
            dialog.window!!.setLayout(
                (resources.displayMetrics.widthPixels * 0.9).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }

        dialog.show()

        // 搜索监听
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                adapter.filter.filter(s.toString())
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        // 列表点击
        lvSuggestions.setOnItemClickListener { _, _, position, _ ->
            val selected = adapter.getItem(position)
            if (selected != null) {
                targetInput.setText(selected.fillValue)
                targetInput.setSelection(targetInput.text.length)
            }
            dialog.dismiss()
        }
    }

    // 【新增】显示调试菜单
    private fun showDebugMenu() {
        val options = arrayOf(
            getString(R.string.debug_crash_test),
            getString(R.string.debug_show_logs),
            getString(R.string.debug_copy_logs),
            getString(R.string.debug_export_logs)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_debug_menu))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> performCrashTest()
                    1 -> showLogViewer()
                    2 -> copyLogsToClipboard()
                    3 -> exportLogsToFile()
                }
            }
            .setNegativeButton(getString(R.string.debug_close), null)
            .show()
    }

    // 1. 崩溃测试
    private fun performCrashTest() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ 确认")
            .setMessage("即将模拟一次应用崩溃，用于测试崩溃处理机制。\n\n是否继续？")
            .setPositiveButton(
                "立即崩溃"
            ) { _, _ ->
                // 延迟1秒后抛出异常，让对话框先关闭
                Handler(Looper.getMainLooper()).postDelayed({
                    throw RuntimeException("【测试】手动触发的崩溃 - 用于验证 CrashHandler 是否正常工作")
                }, 1000)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 2. 显示日志
    private fun showLogViewer() {
        val logs = StringBuilder()
        logs.append("===== 应用日志 =====\n\n")

        try {
            // 读取崩溃日志目录
            val crashDir = File(getExternalFilesDir(null), "CrashLogs")
            if (crashDir.exists() && crashDir.listFiles() != null) {
                val files = crashDir.listFiles()
                if (files != null && files.size > 0) {
                    // 读取最新的日志
                    files.sortByDescending { it.lastModified() }

                    val latest = files[0]
                    logs.append("最新崩溃日志: ").append(latest.getName()).append("\n\n")

                    val reader = BufferedReader(
                        FileReader(latest)
                    )
                    var lineCount = 0
                    var line: String
                    while ((reader.readLine()
                            .also { line = it }) != null && lineCount < 50
                    ) { // 只显示前50行
                        logs.append(line).append("\n")
                        lineCount++
                    }
                    reader.close()

                    if (lineCount >= 50) {
                        logs.append("\n... (日志过长，只显示前50行)")
                    }
                } else {
                    logs.append("暂无崩溃日志")
                }
            } else {
                logs.append("日志目录不存在")
            }
        } catch (e: Exception) {
            logs.append("读取日志失败: ").append(e.message)
        }


        // 显示在可滚动的对话框中
        val scrollView = ScrollView(this)
        val tvLog = TextView(this)
        tvLog.setText(logs.toString())
        tvLog.setTextSize(12f)
        tvLog.setPadding(20, 20, 20, 20)
        tvLog.setTypeface(Typeface.MONOSPACE) // 等宽字体


        // 设置布局参数
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        tvLog.setLayoutParams(params)

        scrollView.addView(tvLog)


        // 设置 ScrollView 的高度限制
        scrollView.setLayoutParams(
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                800 // 最大高度800像素
            )
        )

        AlertDialog.Builder(this)
            .setTitle("📋 日志查看器")
            .setView(scrollView)
            .setPositiveButton(
                "复制"
            ) { _: DialogInterface?, _: Int -> copyLogsToClipboard() }
            .setNegativeButton("关闭", null)
            .show()
    }

    // 3. 复制日志到剪贴板
    private fun copyLogsToClipboard() {
        try {
            val allLogs = StringBuilder()

            val crashDir = File(getExternalFilesDir(null), "CrashLogs")
            val files = crashDir.listFiles()
            if (files != null && files.isNotEmpty()) {
                files.sortByDescending { it.lastModified() }

                for (file in files) {
                    allLogs.append("===== ").append(file.name).append(" =====\n")
                    BufferedReader(FileReader(file)).use { reader ->
                        reader.forEachLine { line ->
                            allLogs.append(line).append("\n")
                        }
                    }
                    allLogs.append("\n\n")
                }
            }

            if (allLogs.isEmpty()) {
                toast(getString(R.string.msg_no_logs_to_copy))
                return
            }

            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Logs", allLogs.toString())
            cm.setPrimaryClip(clip)
            toast(getString(R.string.msg_logs_copied))
        } catch (e: Exception) {
            toast(getString(R.string.msg_copy_failed, e.message.orEmpty()))
        }
    }

    // 4. 导出日志到文件
    private fun exportLogsToFile() {
        Thread {
            try {
                val crashDir = File(getExternalFilesDir(null), "CrashLogs")
                val files = crashDir.listFiles()
                if (!crashDir.exists() || files == null || files.isEmpty()) {
                    runOnUiThread { toast(getString(R.string.msg_no_logs_to_export)) }
                    return@Thread
                }

                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(Date())
                val exportFile = File("/storage/emulated/0/Download/NbtEditor_Logs_$timeStamp.txt")

                FileWriter(exportFile).use { writer ->
                    writer.write("===== NBT Editor 日志导出 =====\n")
                    writer.write("导出时间: $timeStamp\n\n")

                    files.sortByDescending { it.lastModified() }

                    for (file in files) {
                        writer.write("===== ${file.name} =====\n")
                        BufferedReader(FileReader(file)).use { reader ->
                            reader.forEachLine { line ->
                                writer.write("$line\n")
                            }
                        }
                        writer.write("\n\n")
                    }
                }

                val path = exportFile.absolutePath
                runOnUiThread { toast(getString(R.string.msg_logs_exported_to, path)) }
            } catch (e: Exception) {
                val error = e.message.orEmpty()
                runOnUiThread { toast(getString(R.string.msg_export_failed, error)) }
            }
        }.start()
    }

    companion object {
        private var clipboard: JsonElement? = null

        private const val TYPE_PLAYER = 0
        private const val TYPE_MAP = 1
        private const val TYPE_VILLAGE = 2

        // 在类顶部添加
        private const val REQUEST_PICK_IMAGE_FOR_MAP = 1001

        // 新增请求码
        private const val REQUEST_PICK_IMAGE_FOR_PUZZLE = 1002

        // 类顶部常量
        private const val REQUEST_SAF_PATH = 1003

        // 【新增】静态暂存，用于 Activity 重建时保留未保存的数据
        private var tempNbtData: JsonObject? = null
        private var tempTargetKey: String? = null

        // --- 路径常量 ---
        private const val PATH_STANDARD =
            "/storage/emulated/0/Android/data/com.mojang.minecraftpe/files/games/com.mojang/minecraftWorlds/"
        private const val PATH_LEGACY = "/storage/emulated/0/games/com.mojang/minecraftWorlds/"
        private const val PUBLIC_ROOT = "/storage/emulated/0/Download/NbtEditor_Data/"
        private val BRIDGE_ROOT: String = PUBLIC_ROOT + "Bridge/"
        private const val LEVEL_DAT_NAME = "level_working.dat"

        fun getFullStackTrace(e: Throwable): String {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            e.printStackTrace(pw)
            return sw.toString()
        }
    }

    // ==========================================
// 【新增】自定义进度对话框辅助方法（替代已弃用的 ProgressDialog）
// ==========================================

    /**
     * 显示进度对话框
     * @param title 标题
     * @param message 消息
     */
    private fun showProgressDialog(title: String, message: String) {
        if (progressDialog != null && progressDialog!!.isShowing) {
            progressDialog!!.dismiss()
        }

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null)
        tvProgressMessage = view.findViewById(R.id.tv_progress_message)
        tvProgressMessage!!.text = message

        progressDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setCancelable(false)
            .create()

        progressDialog!!.show()
    }

    /**
     * 更新进度对话框消息
     * @param message 新消息
     */
    private fun updateProgressDialog(message: String) {
        if (tvProgressMessage != null) {
            runOnUiThread { tvProgressMessage!!.text = message }
        }
    }

    /**
     * 关闭进度对话框
     */
    private fun dismissProgressDialog() {
        if (progressDialog != null && progressDialog!!.isShowing) {
            progressDialog!!.dismiss()
        }
        progressDialog = null
        tvProgressMessage = null
    }

    private fun wrapRawItem(rawItem: JsonElement?): JsonObject {
        if (rawItem == null) {
            val empty = JsonObject()
            empty.addProperty("t", 10)
            empty.add("v", JsonObject())
            return empty
        }
        if (rawItem.isJsonObject) {
            val obj = rawItem.asJsonObject
            if (obj.has("t")) {
                return obj
            }
            // 没有 "t" 字段，说明是裸 Compound 内容，包装为 type 10
            val wrapped = JsonObject()
            wrapped.addProperty("t", 10)
            wrapped.add("v", obj)
            return wrapped
        }
        // 裸值（JsonPrimitive / JsonArray），包装为 String 类型
        val wrapped = JsonObject()
        wrapped.addProperty("t", 8)
        wrapped.addProperty("v", rawItem.toString())
        return wrapped
    }

    /**
     * 根据 pathStack 定位 rootNbtData 中的原始 List 数组
     * 返回 null 表示定位失败
     */
    private fun findOriginalListData(): JsonArray? {
        if (pathStack.isEmpty()) return null

        try {
            var current: JsonObject = rootNbtData ?: return null

            // 遍历路径栈，逐层深入
            for (i in 0 until pathStack.size - 1) {
                val pathKey = pathStack[i] ?: continue
                val el = current.get(pathKey) ?: return null

                if (el.isJsonObject) {
                    val obj = el.asJsonObject
                    if (obj.has("v") && obj.get("v").isJsonObject) {
                        current = obj.getAsJsonObject("v")
                    } else {
                        current = obj
                    }
                } else {
                    return null
                }
            }

            // 最后一层是 List 的 key
            val listKey = pathStack.peek() ?: return null
            val listEl = current.get(listKey) ?: return null

            if (listEl.isJsonObject) {
                val listObj = listEl.asJsonObject
                if (listObj.has("v") && listObj.get("v").isJsonArray) {
                    return listObj.getAsJsonArray("v")
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "findOriginalListData failed", e)
        }

        return null
    }

}
