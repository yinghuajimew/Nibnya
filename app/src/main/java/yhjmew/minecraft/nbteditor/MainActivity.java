package yhjmew.minecraft.nbteditor;

import static android.content.ContentValues.TAG;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Stack;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    // 控件
    private EditText etWorldName;
    private Button btnCopy,
            btnParse,
            btnSave,
            btnCheck,
            btnHistory,
            btnEditPlayer,
            btnSwitchPath,
            btnCleanCache;
    private ListView nbtListView;
    private TextView tvCurrentPath;
    private boolean isNightMode = false;
    private android.content.SharedPreferences prefs;

    // 数据与状态
    private NbtAdapter nbtAdapter;
    private JsonObject rootNbtData;
    private boolean isEditingPlayer = false;

    private String currentWorkingDbPath = null;
    private String currentWorkingFileOrDir = null;

    private final Stack<JsonObject> navigationStack = new Stack<>();
    private final Stack<String> pathStack = new Stack<>();
    private final Stack<Integer> scrollPositionStack = new Stack<>();
    private static JsonElement clipboard = null;

    private String currentLang;

    private boolean isFullScreen = false; // 全屏标志位
    private View layoutNormalUi;
    private View layoutEditorHeader;
    private View layoutFullscreenBar;
// 在类顶部变量区加入
    private TextView tvFullscreenPath;
    private int currentViewMode = 0;
    private boolean isTreeMode = false; // 当前是否为 Blocktopograph 模式
    private NbtTreeAdapter nbtTreeAdapter; // 专用的树适配器
private View viewMainContent;  // 主界面的容器
private View viewSidebar;      // 侧边栏的容器
private String currentTargetKey = "~local_player";
private static final int TYPE_PLAYER = 0;
private static final int TYPE_MAP = 1;
private static final int TYPE_VILLAGE = 2;
// 在类顶部添加
    private static final int REQUEST_PICK_IMAGE_FOR_MAP = 1001;
    private JsonArray currentTargetMapArray; // 暂存当前正在操作的 colors 数组
// 新增请求码
    private static final int REQUEST_PICK_IMAGE_FOR_PUZZLE = 1002;
// 类顶部常量
private static final int REQUEST_SAF_PATH = 1003;
private Uri safTreeUri = null;  // 保存 SAF 授权的 URI
    // 暂存拼图尺寸
    private int puzzleRows = 1;
    private int puzzleCols = 1;
// 【新增】数据缓存：Key -> JsonObject
    // 用于在不同 NBT 之间切换时实现“秒开”且保留状态
    private final java.util.HashMap<String, JsonObject> nbtDataCache = new java.util.HashMap<>();
// 【新增】列表缓存 (避免每次点侧边栏都重新扫描数据库)
    private List<String> cacheMapList = null;
    private List<String> cacheVillageList = null;
    private List<String> cachePlayerList = null;
// 记录上一次加载的存档名，用于防止重复重置
    private String lastLoadedWorldFolder = null;
// 【新增】专门记录列表模式当前停留在哪一层数据
    private JsonObject currentListData = null;
private int lastTreeClickPosition = -1; // 记录树状图最后点击的位置
// 在类顶部
    private boolean useMultiThreading = true; // 默认开启
// 【新增】静态暂存，用于 Activity 重建时保留未保存的数据
    private static JsonObject tempNbtData = null;
    private static String tempTargetKey = null;
// 会话缓存：Key(比如 "~local_player" 或 "level.dat") -> Session
    private final java.util.Map<String, EditorSession> sessionCacheMap = new java.util.HashMap<>();
private boolean useShizuku = true; // 默认启用 Shizuku
    View sidebarContainer = null;   //之后决定
    View sidebar = null;   //之后决定

    // --- 路径常量 ---
    private static final String PATH_STANDARD = "/storage/emulated/0/Android/data/com.mojang.minecraftpe/files/games/com.mojang/minecraftWorlds/";
    private static final String PATH_LEGACY = "/storage/emulated/0/games/com.mojang/minecraftWorlds/";
    private String currentWorldsPath = PATH_STANDARD;
    private static final String PUBLIC_ROOT = "/storage/emulated/0/Download/NbtEditor_Data/";
    private static final String BRIDGE_ROOT = PUBLIC_ROOT + "Bridge/";
    private static final String LEVEL_DAT_NAME = "level_working.dat";

    private final Shizuku.OnRequestPermissionResultListener REQUEST_PERMISSION_RESULT_LISTENER = (requestCode, grantResult) -> {
        if (grantResult == PackageManager.PERMISSION_GRANTED)
            toast(getString(R.string.toast_shizuku_granted));
        else toast(getString(R.string.toast_shizuku_denied));
    };

// ============================================
// 【新增】核心修复：Android 7.0+ 语言切换
// ============================================
@Override
protected void attachBaseContext(android.content.Context newBase) {
    android.content.SharedPreferences prefs = newBase.getSharedPreferences("nbt_config", android.content.Context.MODE_PRIVATE);
    String defaultLang = java.util.Locale.getDefault().getLanguage().contains("zh") ? "zh" : "en";
    String lang = prefs.getString("app_language", defaultLang);

    // 直接设置，无需中间变量
    android.content.res.Configuration config = new android.content.res.Configuration();
    config.setLocale("en".equals(lang) ? java.util.Locale.ENGLISH : java.util.Locale.CHINESE);

    super.attachBaseContext(newBase.createConfigurationContext(config));
}

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CrashHandler.getInstance().init(this);
        System.setProperty("sun.arch.data.model", "64");
        super.onCreate(savedInstanceState);
        // 预先创建好中转站和屏蔽文件
        createNoMedia();
        NbtTranslator.init(this);
        // 【新增】保护工作目录和备份目录 (无论是在私有还是公共存储)
        ensureNoMedia(getWorksDir());
        ensureNoMedia(getBackupsDir());
        // 2. 【核心修复】强制保护公共下载目录 (不管当前选什么模式，这里都要保护)
        File publicRoot = new File("/storage/emulated/0/Download/NbtEditor_Data/");
        ensureNoMedia(new File(publicRoot, "Works"));
        ensureNoMedia(new File(publicRoot, "Backups"));
        // 如果有 Bridge 目录，其实 createNoMedia() 已经处理了，但为了保险也可以加
        ensureNoMedia(new File(publicRoot, "Bridge"));
        // 1. 初始化偏好 & 读取语言设置
        prefs = getSharedPreferences("nbt_config", MODE_PRIVATE);
        isNightMode = prefs.getBoolean("night_mode", false);
        currentLang = prefs.getString("app_language",
                java.util.Locale.getDefault().getLanguage().contains("zh") ? "zh" : "en");
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        setContentView(R.layout.main);
viewMainContent = findViewById(R.id.main_content);
viewSidebar = findViewById(R.id.sidebar_content);
// 在 onCreate 初始化 prefs 之后
    useMultiThreading = prefs.getBoolean("use_multithread", true);
// 在 useMultiThreading 初始化之后添加
useShizuku = prefs.getBoolean("use_shizuku", true);
// === 逻辑：中文默认 Smart(2)，英文默认 Raw(0) ===
        boolean isChineseEnv = java.util.Locale.getDefault().getLanguage().contains("zh");
// 优先读存盘，没有存档则根据语言自动判定
        int defaultMode = isChineseEnv ? 2 : 0;
        currentViewMode = prefs.getInt("view_mode", defaultMode);
View btnOpen = findViewById(R.id.btn_open_drawer);
        final View sidebarContainer = findViewById(R.id.custom_sidebar_container);
// 在 onCreate 初始化部分添加
String savedSafUri = prefs.getString("saf_tree_uri", null);
if (savedSafUri != null) {
    safTreeUri = Uri.parse(savedSafUri);
    try {
        // 检查权限是否仍然有效
        getContentResolver().takePersistableUriPermission(safTreeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION 
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    } catch (Exception e) {
        // 权限已失效，清除
        safTreeUri = null;
        prefs.edit().remove("saf_tree_uri").apply();
    }
}

        // 初始化控件
        etWorldName = findViewById(R.id.et_world_name);
        btnCopy = findViewById(R.id.btn_copy);
        btnParse = findViewById(R.id.btn_parse);
        btnCheck = findViewById(R.id.btn_check_permission);
        btnSave = findViewById(R.id.btn_save);
        nbtListView = findViewById(R.id.lv_nbt_list);
        btnHistory = findViewById(R.id.btn_backup_history);
        btnEditPlayer = findViewById(R.id.btn_edit_player);
        btnSwitchPath = findViewById(R.id.btn_switch_path);
        btnCleanCache = findViewById(R.id.btn_clear_cache); // 确保XML有此ID
        applyTheme();

tvCurrentPath = findViewById(R.id.tv_current_path);
if (tvCurrentPath != null) {
    tvCurrentPath.setOnClickListener(v -> toast(getString(R.string.long_press_here_to_open_the_root_menu)));
    tvCurrentPath.setOnLongClickListener(v -> {
        showRootMenu();
        return true;
    });
} else {
    toast("错误：找不到路径显示控件 tv_current_path");
}

        try {
            registerListener();
        } catch (Exception e) {
            Log.e("MainActivity", "Shizuku registerListener failed", e);
            // 可选：toast提示
            toast("Shizuku 初始化异常: " + e.getMessage());
        }
Shizuku.addRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER);
checkStoragePermission();

if (savedInstanceState == null) {
    cleanUpOldSessions();
}

View btnGoFull = findViewById(R.id.btn_go_fullscreen);
if (btnGoFull != null) {
    btnGoFull.setOnClickListener(v -> toggleFullScreen(true));
}

View btnExitFull = findViewById(R.id.btn_exit_fullscreen);
if (btnExitFull != null) {
    btnExitFull.setOnClickListener(v -> {
        if (!navigationStack.isEmpty()) {
            onBackPressed();
        } else {
            toggleFullScreen(false);
        }
    });
}

View.OnClickListener selectListener = v -> showWorldSelector();   //之后决定

btnCopy.setOnClickListener(v -> {
    String currentFolder = etWorldName.getText().toString().trim();
    String hint = getString(R.string.hint_select_world);
    if (currentFolder.isEmpty() || currentFolder.equals(hint) || currentFolder.contains(getString(R.string.hint_select_world))) {
        showWorldSelector();
    } else {
        toast(getString(R.string.toast_loading) + currentFolder);
        copyLevelDatOut(currentFolder);
    }
});

etWorldName.setOnClickListener(v -> showWorldSelector());

btnEditPlayer.setOnClickListener(v -> {
    String folder = etWorldName.getText().toString();
    if (checkFolder(folder)) loadPlayerData(folder, null);
});

btnParse.setOnClickListener(v -> parseLevelDat());

btnCheck.setOnClickListener(v -> {
    if (checkShizukuReady()) toast(getString(R.string.toast_shizuku_normal));
    checkStoragePermission();
});

if (btnSwitchPath != null) {
    btnSwitchPath.setOnClickListener(v -> showPathSelector());
}
if (btnCleanCache != null) {
    btnCleanCache.setOnClickListener(v -> showCacheList());
}

btnHistory.setOnClickListener(v -> {
    String f = etWorldName.getText().toString();
    if (checkFolder(f)) showBackupList(f);
});

nbtListView.setOnItemClickListener((parent, view, position, id) -> {
    if (isTreeMode) {
        lastTreeClickPosition = position;
        if (nbtTreeAdapter.isContainer(position)) {
            nbtTreeAdapter.toggleExpand(position);
        } else {
            NbtTreeAdapter.Node node = nbtTreeAdapter.getNode(position);
            JsonElement v = node.value.getAsJsonObject().get("v");
            int type = node.type;
            if ((type == 7 || type == 11 || type == 12) && v.isJsonArray() && v.getAsJsonArray().size() > 500) {
                showArrayPaginationDialog(node.key, v.getAsJsonArray());
            } else {
                showEditValueDialog(node.key, node.value.getAsJsonObject());
            }
        }
    } else {
        String key = nbtAdapter.getKey(position);
        JsonObject itemData = nbtAdapter.getData().getAsJsonObject(key);
        int type = itemData.get("t").getAsInt();
        if (type == 10) enterFolder(key, itemData.getAsJsonObject("v"), false);
        else if (type == 9) enterFolder(key, convertListToMap(itemData), true);
        else {
            JsonElement v = itemData.get("v");
            if ((type == 7 || type == 11 || type == 12) && v.isJsonArray() && v.getAsJsonArray().size() > 500) {
                showArrayPaginationDialog(key, v.getAsJsonArray());
            } else {
                showEditValueDialog(key, itemData);
            }
        }
    }
});

nbtListView.setOnItemLongClickListener((parent, view, position, id) -> {
    lastTreeClickPosition = position;
    if (isTreeMode) {
        NbtTreeAdapter.Node node = nbtTreeAdapter.getNode(position);
        String key = node.key;
        JsonObject itemData;
        if (node.parent == null) {
            key = "ROOT";
            itemData = new JsonObject();
            itemData.addProperty("t", 10);
            itemData.add("v", rootNbtData);
        } else if (node.value != null && node.value.isJsonObject()) {
            itemData = node.value.getAsJsonObject();
        } else {
            return false;
        }
        showLongPressMenu(key, itemData);
        return true;
    } else {
        String key = nbtAdapter.getKey(position);
        JsonObject itemData = nbtAdapter.getData().getAsJsonObject(key);
        showLongPressMenu(key, itemData);
        return true;
    }
});

if (!checkShizukuAvailable()) {
    toast(getString(R.string.toast_shizuku_needed));
    try {
        Class<?> c = Class.forName("rikka.shizuku.Shizuku");
        c.getMethod("requestPermission", int.class).invoke(null, 0);
    } catch (Exception e) {
        toast(getString(R.string.toast_install_shizuku));
    }
}

View btnMenuFull = findViewById(R.id.btn_fullscreen_menu);
if (btnMenuFull != null) {
    btnMenuFull.setOnClickListener(v -> showRootMenu());
}

View btnOpts = findViewById(R.id.btn_view_options);
if (btnOpts != null) {
    btnOpts.setOnClickListener(v -> showViewOptionsDialog());
}

View btnFullViewOpts = findViewById(R.id.btn_fullscreen_view);
if (btnFullViewOpts != null) {
    btnFullViewOpts.setOnClickListener(v -> showViewOptionsDialog());
}

btnSave.setOnClickListener(v -> {
    if (isTreeMode) {
        toast(getString(R.string.toast_tree_switch_mode));
        return;
    }
    if (nbtAdapter == null) {
        toast(getString(R.string.toast_no_data));
        return;
    }
    if (nbtAdapter.getData() == null) {
        toast("Data is null!");
        return;
    }
    String folder = etWorldName.getText().toString().trim();
    String hintText = getString(R.string.hint_select_world);
    if (folder.isEmpty() || folder.equals(hintText) || folder.contains("...")) {
        toast(getString(R.string.toast_select_world));
        return;
    }
    saveAndPushBack(rootNbtData, folder);
});

View btnTree = findViewById(R.id.btn_tree_mode);
if (btnTree != null) {
    btnTree.setOnClickListener(v -> {
        isTreeMode = !isTreeMode;
        String toastMsg = isTreeMode ? getString(R.string.toast_tree_switch_mode_blocktopograph) : getString(R.string.toast_tree_switch_mode_default);
        toast(toastMsg);
        if (isTreeMode) {
            nbtTreeAdapter = new NbtTreeAdapter(MainActivity.this, rootNbtData);
            nbtTreeAdapter.setViewMode(currentViewMode);
            int targetPos = 0;
            if (!pathStack.isEmpty()) {
                targetPos = nbtTreeAdapter.expandPath(pathStack);
            }
            nbtListView.setAdapter(nbtTreeAdapter);
            nbtListView.setSelection(targetPos);
        } else {
            if (nbtTreeAdapter != null && lastTreeClickPosition != -1) {
                List<String> treePath = nbtTreeAdapter.getNodePath(lastTreeClickPosition);
                syncListModeFromPath(treePath);
            }
            JsonObject dataToShow = (currentListData != null) ? currentListData : rootNbtData;
            updateAdapter(dataToShow);
        }
        updatePathTitle();
    });
}

        final View[] sidebar = {findViewById(R.id.sidebar_content)};
if (sidebar[0] != null) {
    android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
    getWindowManager().getDefaultDisplay().getMetrics(metrics);
    int screenWidth = metrics.widthPixels;
    ViewGroup.LayoutParams params = sidebar[0].getLayoutParams();
    params.width = screenWidth * 2 / 3;
    sidebar[0].setLayoutParams(params);
}

View btnMultiPlayer = findViewById(R.id.btn_online_players);
if (btnMultiPlayer != null) {
    btnMultiPlayer.setOnClickListener(v -> {
        if (sidebarContainer != null) sidebarContainer.setVisibility(View.GONE);
        ensureDbLoaded(this::showMultiPlayerDialog);
    });
}

View btnMap = findViewById(R.id.btn_map_nbt);
if (btnMap != null) {
    btnMap.setOnClickListener(v -> {
        if (sidebarContainer != null) sidebarContainer.setVisibility(View.GONE);
        ensureDbLoaded(this::showMapListDialog);
    });
}

View btnVillage = findViewById(R.id.btn_village_nbt);
if (btnVillage != null) {
    btnVillage.setOnClickListener(v -> {
        if (sidebarContainer != null) sidebarContainer.setVisibility(View.GONE);
        ensureDbLoaded(this::showVillageListDialog);
    });
}

        final View btnSearchNbt = findViewById(R.id.btn_search_nbt);
        if (btnSearchNbt != null) {
            btnSearchNbt.setOnClickListener(v -> {
                if (isTreeMode) {
                    toast(getString(R.string.toast_the_search_function_currently_only_supports_list_mode));
                    return;
                }
                if (nbtAdapter == null || nbtAdapter.getCount() == 0) {
                    toast(getString(R.string.toast_there_is_currently_no_data_to_search));
                    return;
                }
                showEditorSearchDialog();
            });
        }

        View btnFullTree = findViewById(R.id.btn_fullscreen_tree);
        if (btnFullTree != null && btnTree != null) {
            btnFullTree.setOnClickListener(v -> btnTree.performClick());
        }

        View btnFullSearch = findViewById(R.id.btn_fullscreen_search);
        if (btnFullSearch != null && btnSearchNbt != null) {
            btnFullSearch.setOnClickListener(v -> btnSearchNbt.performClick());
        }

View btnOpenKey = findViewById(R.id.btn_open_any_key);
if (btnOpenKey != null) {
    btnOpenKey.setOnClickListener(v -> {
        if (currentWorkingDbPath == null) { 
            toast(getString(R.string.toast_please_initialize_the_database_first)); 
            return; 
        }
        if (sidebarContainer != null) sidebarContainer.setVisibility(View.GONE);
        showOpenByKeyDialog();
    });
}

if (btnOpen != null) {
    btnOpen.setOnClickListener(v -> {
        if (sidebarContainer != null) {
            sidebarContainer.setVisibility(View.VISIBLE);
            sidebarContainer.bringToFront();
        } else {
            toast(getString(R.string.toast_error_sidebar_layout_not_found));
        }
    });
} else {
    toast(getString(R.string.toast_error_menu_button_not_found));
}

View viewMask = findViewById(R.id.view_mask);
if (viewMask != null) {
    viewMask.setOnClickListener(v -> {
        if (sidebarContainer != null) sidebarContainer.setVisibility(View.GONE);
    });
}

View btnGlobal = findViewById(R.id.btn_global_nbt);
if (btnGlobal != null) {
    btnGlobal.setOnClickListener(v ->  {
        if (sidebarContainer != null) sidebarContainer.setVisibility(View.GONE);
        ensureDbLoaded(this::showGlobalDataDialog);
    });
}

if (savedInstanceState != null) {
    isEditingPlayer = savedInstanceState.getBoolean("isEditingPlayer");
    currentTargetKey = savedInstanceState.getString("currentTargetKey");
    currentWorkingDbPath = savedInstanceState.getString("currentWorkingDbPath");
    currentWorkingFileOrDir = savedInstanceState.getString("currentWorkingFileOrDir");
    isTreeMode = savedInstanceState.getBoolean("isTreeMode");
    String savedWorldName = savedInstanceState.getString("worldName");
    if (savedWorldName != null && etWorldName != null) {
        etWorldName.setText(savedWorldName);
        lastLoadedWorldFolder = savedWorldName;
    }
    if (currentWorkingDbPath != null || currentWorkingFileOrDir != null) {
        new android.os.Handler().postDelayed(() -> {
            if (tempNbtData != null) {
                rootNbtData = tempNbtData;
                currentTargetKey = tempTargetKey;
                updateAdapter(rootNbtData);
                updatePathTitle();
                if (!isEditingPlayer && rootNbtData != null) {
                    updateWorldInfoCard(rootNbtData);
                } else if (etWorldName != null) {
                    String worldName = etWorldName.getText().toString();
                    String hint = getString(R.string.hint_select_world);
                    if (!worldName.isEmpty() && !worldName.equals(hint) && !worldName.contains("...")) {
                        TextView tvName = findViewById(R.id.tv_app_title);
                        if (tvName != null) tvName.setText(worldName);
                    }
                }
                tempNbtData = null;
                tempTargetKey = null;
                toast(getString(R.string.toast_unsaved_changes_restored));
            } else {
                parseLevelDat();
            }
        }, 100);
    }
}

View sidebarHeader = findViewById(R.id.sidebar_header);
if (sidebarHeader != null) {
    sidebarHeader.setClickable(true);
    sidebarHeader.setFocusable(true);
    sidebarHeader.setOnClickListener(v -> showAppInfoDialog());
    View.OnClickListener clickListener = v -> showAppInfoDialog();
    View ivIcon = findViewById(R.id.iv_app_icon);
    if (ivIcon != null) ivIcon.setOnClickListener(clickListener);
    View tvName = findViewById(R.id.tv_sidebar_app_name);
    if (tvName != null) tvName.setOnClickListener(clickListener);
    View tvAuthor = findViewById(R.id.tv_sidebar_author);
    if (tvAuthor != null) tvAuthor.setOnClickListener(clickListener);
} else {
    toast("错误：找不到 sidebar_header");
}

if (viewMask != null) {
    viewMask.setOnClickListener(v -> {
        sidebar[0] = findViewById(R.id.custom_sidebar_container);
        if (sidebar[0] != null) sidebar[0].setVisibility(View.GONE);
    });
}
// onCreate 应该在这里结束
}
@Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        // 统一检查结果有效性
        if (resultCode != RESULT_OK || data == null) return;

        // === 分支 1：处理拼图 (Puzzle) ===
        if (requestCode == REQUEST_PICK_IMAGE_FOR_PUZZLE) {
            Uri puzzleUri = data.getData();
            if (puzzleUri != null) {
                // 直接调用处理方法，然后结束
                processPuzzleMap(puzzleUri);
            }
            return; // 【关键】处理完拼图直接返回，不执行下面的代码
        }

        // === 分支 2：处理单张地图画 (Map Art) ===
        if (requestCode == REQUEST_PICK_IMAGE_FOR_MAP) {
            final Uri imageUri = data.getData(); // 在这里定义 imageUri
            if (imageUri == null) return;
            
            // 下面是你原有的单张图片处理逻辑
            final ProgressDialog processing = ProgressDialog.show(this, getString(R.string.msg_processing), getString(R.string.toast_generate_bedrock_edition_map), true);
            
            new Thread(() -> {
                try {
                    // 1. 读取原图
                    java.io.InputStream is = getContentResolver().openInputStream(imageUri);
                    android.graphics.Bitmap original = android.graphics.BitmapFactory.decodeStream(is);
                    if (is != null) {
                        is.close();
                    }

                    // 2. 创建 128x128 居中画布
                    int targetW = 128;
                    int targetH = 128;
                    android.graphics.Bitmap finalBitmap = android.graphics.Bitmap.createBitmap(targetW, targetH, android.graphics.Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas canvas = new android.graphics.Canvas(finalBitmap);
                    canvas.drawColor(Color.WHITE);

                    // 缩放逻辑
                    int origW = original.getWidth();
                    int origH = original.getHeight();
                    float scale = Math.min((float)targetW / origW, (float)targetH / origH);
                    int newW = Math.round(origW * scale);
                    int newH = Math.round(origH * scale);
                    int left = (targetW - newW) / 2;
                    int top = (targetH - newH) / 2;

                    android.graphics.Rect destRect = new android.graphics.Rect(left, top, left + newW, top + newH);
                    canvas.drawBitmap(original, null, destRect, null);

                    // 3. 准备数据 (65536)
                    int targetSize = 65536;
                    if (currentTargetMapArray == null) currentTargetMapArray = new JsonArray();

                    while (currentTargetMapArray.size() < targetSize) currentTargetMapArray.add((byte)0);
                    while (currentTargetMapArray.size() > targetSize) currentTargetMapArray.remove(currentTargetMapArray.size() - 1);

                    // 4. 像素转换
                    int index = 0;
                    int[] pixels = new int[128 * 128];
                    finalBitmap.getPixels(pixels, 0, 128, 0, 0, 128, 128);

                    for (int i = 0; i < pixels.length; i++) {   //无需更改
                        int color = pixels[i];
                        byte r = (byte) Color.red(color);
                        byte g = (byte) Color.green(color);
                        byte b = (byte) Color.blue(color);
                        byte a = (byte) Color.alpha(color);

                        currentTargetMapArray.set(index++, new com.google.gson.JsonPrimitive(r));
                        currentTargetMapArray.set(index++, new com.google.gson.JsonPrimitive(g));
                        currentTargetMapArray.set(index++, new com.google.gson.JsonPrimitive(b));
                        currentTargetMapArray.set(index++, new com.google.gson.JsonPrimitive(a));
                    }

                    // 5. 修正 NBT
                    if (nbtAdapter != null) {
                        JsonObject mapRoot = nbtAdapter.getData();
                        if (mapRoot != null) {
                            JsonObject lockedTag = new JsonObject();
                            lockedTag.addProperty("t", 1); lockedTag.addProperty("v", (byte)1);
                            mapRoot.add("mapLocked", lockedTag);
                        }
                    }

                    runOnUiThread(() -> {
                        processing.dismiss();
                        toast(getString(R.string.toast_the_map_is_generated));
                        if(nbtAdapter!=null) nbtAdapter.notifyDataSetChanged();
                        if(nbtTreeAdapter!=null) nbtTreeAdapter.notifyDataSetChanged();
                    });

                    original.recycle();
                    finalBitmap.recycle();

                } catch (final Exception e) {
                    runOnUiThread(() -> { processing.dismiss(); toast(getString(R.string.toast_build_failed) + e); });
                }
            }).start();
        }

// 【新增】处理 SAF 路径选择
    if (requestCode == REQUEST_SAF_PATH && resultCode == RESULT_OK && data != null) {   //无需更改
        Uri treeUri = data.getData();
        if (treeUri != null) {
            // 持久化授权
            getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION 
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            
            safTreeUri = treeUri;
            
            // 转换为路径显示
            String path = treeUri.toString();
            currentWorldsPath = path + "/";  // SAF 路径特殊标记
            
            // 保存到 SharedPreferences
            prefs.edit().putString("saf_tree_uri", path).apply();
            
            toast(String.format(getString(R.string.msg_saf_selected), path));
            showWorldSelector();
        }
    }

    }

// 【新增】保存当前状态（防止切换语言或旋转屏幕后数据丢失）
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // 保存关键变量
        outState.putBoolean("isEditingPlayer", isEditingPlayer);
        outState.putString("currentTargetKey", currentTargetKey);
        outState.putString("currentWorkingDbPath", currentWorkingDbPath);
        outState.putString("currentWorkingFileOrDir", currentWorkingFileOrDir);
        // 保存输入框里的存档名
        if (etWorldName != null) {
            outState.putString("worldName", etWorldName.getText().toString());
        }
        // 保存当前的视图模式（是不是树状图）
        outState.putBoolean("isTreeMode", isTreeMode);
    }

    private boolean checkFolder(String folder) {
        // 动态匹配 strings.xml 里的提示语
        String hint = getString(R.string.hint_select_world);
        // 为了防止匹配出错，取提示语的前几个字或者关键词（例如去掉末尾的省略号）
        // 这里简化逻辑：如果文件夹名包含提示语的一部分，或者为空
        if (folder.isEmpty() || folder.contains("...") || folder.length() > 50) {
            // 这里用 contains("...") 是一种模糊匹配，或者直接判断是否等于 hint
            if (folder.equals(hint) || folder.isEmpty()) {
                toast(getString(R.string.toast_select_world));
                return false;
            }
        }
        // 为了确保安全，最简单的修改是：
        if (folder.isEmpty() || folder.equals(getString(R.string.hint_select_world))) {
            toast(getString(R.string.toast_select_world));
            return false;
        }
        return true;
    }

// 基础目录分流：新路径->私有Data，旧路径->Download/NbtEditor_Data
    private File getBaseDir() {
        if (currentWorldsPath.contains("Android/data")) {
            return getExternalFilesDir(null);
        } else {
            File publicDir = new File("/storage/emulated/0/Download/NbtEditor_Data/");
            if (!publicDir.exists()) publicDir.mkdirs();
            return publicDir;
        }
    }

    private File getWorksDir() {
        File w = new File(getBaseDir(), "Works");
        if (!w.exists()) w.mkdirs(); // 加固：确保目录存在
        return w;
    }

    private File getBackupsDir() {
        File b = new File(getBaseDir(), "Backups");
        if (!b.exists()) b.mkdirs(); // 加固：确保目录存在
        return b;
    }

// 启动时清理旧Session文件 (优化版：保留 Bridge 根目录和 .nomedia)
    private void cleanUpOldSessions() {
        // 1. 清理私有目录的 Works (Android/data 模式)
        File privateWorks = new File(getExternalFilesDir(null), "Works");
        cleanDirectoryContent(privateWorks);
        
        // 2. 清理公共目录的 Works (旧路径模式)
        File publicWorks = new File("/storage/emulated/0/Download/NbtEditor_Data/Works");
        cleanDirectoryContent(publicWorks);
        
        // 3. 清理 Bridge 中转站 (保留根目录和 .nomedia)
        File bridgeDir = new File(BRIDGE_ROOT);
        if (bridgeDir.exists()) {
            // 确保隐身衣存在
            createNoMedia(); 
            
            // 只删除里面的子文件夹 (load_db_xxx, save_db_xxx)
            File[] files = bridgeDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    // 【关键】不要删除 .nomedia 文件
                    if (f.getName().equals(".nomedia")) continue;
                    
                    deleteRecursive(f);
                }
            }
        } else {
            // 如果不存在，创建它并加上隐身衣
            bridgeDir.mkdirs();
            createNoMedia();
        }
    }
    
    // 辅助：清理文件夹内容但不删除文件夹本身
    private void cleanDirectoryContent(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                // 如果是工作中的数据库目录或临时文件，删掉
                if (f.isDirectory() && f.getName().startsWith("working_db_")) deleteRecursive(f);
                if (f.isDirectory() && f.getName().startsWith("db_restore_")) deleteRecursive(f);
                if (f.isFile() && f.getName().equals(LEVEL_DAT_NAME)) f.delete();
            }
        }
    }

    // ==========================================
    // 【修改】管理缓存：使用 Adapter 实现原地刷新
    // ==========================================
    private void showCacheList() {
        final File worksDir = getWorksDir();
        // 【加固】如果目录被删了，这里自动创建一下
        if (!worksDir.exists()) worksDir.mkdirs();

        File[] filesArr = worksDir.listFiles();

        // 【加固】判空逻辑
        if (filesArr == null || filesArr.length == 0) {
            toast(getString(R.string.toast_cache_empty));
            return;
        }

        Arrays.sort(filesArr, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

        final List<File> fileList = new ArrayList<>(Arrays.asList(filesArr));
        final List<String> nameList = new ArrayList<>();

        for (File f : fileList) {
            if (f.isDirectory()) nameList.add("📁 " + f.getName());
            else nameList.add("📄 " + f.getName());
        }

        final ArrayAdapter<
                String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, nameList);
        ListView lv = new ListView(this);
        lv.setAdapter(adapter);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_manage_cache))
                .setView(lv)
                .setNeutralButton(getString(R.string.btn_close), null)
                .setPositiveButton(getString(R.string.btn_clear_all), (d, w) -> new AlertDialog.Builder(MainActivity.this)
                        .setTitle(getString(R.string.title_clear_confirm))
                        .setMessage(getString(R.string.msg_clear_all_cache))
                        .setPositiveButton(getString(R.string.btn_delete), (dd, ww) -> {
                            for (File f : fileList) deleteRecursive(f);
                            fileList.clear();
                            nameList.clear();
                            adapter.notifyDataSetChanged();
                            // 【加固】重置路径变量
                            currentWorkingDbPath = null;
                            currentWorkingFileOrDir = null;
                            lastLoadedWorldFolder = null; // 【新增】强制下次加载时重置
                            toast(getString(R.string.toast_cleared_short));
                        })
                        .setNegativeButton(getString(R.string.btn_cancel), null)
                        .show())
                .create();

        lv.setOnItemClickListener((p, v, pos, id) -> toast(getString(R.string.toast_hold_delete)));

        lv.setOnItemLongClickListener((p, v, pos, id) -> {
            final File target = fileList.get(pos);
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(getString(R.string.btn_delete))
                    .setMessage(String.format(getString(R.string.msg_delete_confirm), target.getName()))
                    .setPositiveButton(getString(R.string.btn_delete), (d, w) -> {
                        deleteRecursive(target);
                        fileList.remove(pos);
                        nameList.remove(pos);
                        adapter.notifyDataSetChanged();
                        toast(getString(R.string.toast_deleted));
                    })
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show();
            return true;
        });
        dialog.show();
    }

// ==========================================
// 【修改】备份管理：使用 Adapter 原地刷新
// ==========================================
private void showBackupList(final String folderName) {
    File backupDir = new File(getBackupsDir(), folderName);
    if (!backupDir.exists() || backupDir.listFiles() == null) {
        toast(getString(R.string.toast_no_backups));
        return;
    }

    final File[] backups = backupDir.listFiles();

    // 【修复】确保 backups 不为 null 且不为空
    if (backups == null || backups.length == 0) {
        toast(getString(R.string.toast_no_backups));
        return;
    }

    Arrays.sort(backups, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

    final List<File> fileList = new ArrayList<>(Arrays.asList(backups));
    final List<String> nameList = new ArrayList<>();
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    for (File f : fileList) {
        String time = sdf.format(new Date(f.lastModified()));
        if (f.isDirectory()) nameList.add("📁 " + f.getName() + "\n" + time);
        else nameList.add("📄 " + f.getName() + "\n" + time);
    }

        final ArrayAdapter<
                String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, nameList);
        ListView lv = new ListView(this);
        lv.setAdapter(adapter);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_backup_title))
                .setView(lv)
                .setNeutralButton(getString(R.string.btn_close), null)
                .create();

        // 点击恢复
        lv.setOnItemClickListener((p, v, pos, id) -> {
            restoreBackup(fileList.get(pos));
            dialog.dismiss();
        });

        // 【修复部分】长按菜单：包含删除和重命名
        lv.setOnItemLongClickListener((p, v, pos, id) -> {
            final File target = fileList.get(pos);
            String
                    [] ops = {getString(R.string.menu_del_backup), getString(R.string.menu_rename)};

            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(getString(R.string.title_manage_prefix) + target.getName())
                    .setItems(ops, (d, w) -> {
                        if (w == 0) { // 删除
                            deleteRecursive(target);
                            fileList.remove(pos);
                            nameList.remove(pos);
                            adapter.notifyDataSetChanged();
                            toast(getString(R.string.toast_deleted));
                        } else { // 重命名
                            final EditText input = new EditText(MainActivity.this);
                            input.setText(target.getName());
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle(getString(R.string.title_rename))
                                    .setView(input)
                                    .setPositiveButton(getString(R.string.btn_confirm), (dd, ww) -> {
                                        String newName = input.getText().toString().trim();
                                        if (!newName.isEmpty()) {
                                            File newFile = new File(target.getParent(), newName);
                                            if (target.renameTo(newFile)) {
                                                fileList.set(pos, newFile);
                                                // 重新格式化显示名
                                                String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(newFile.lastModified()));
                                                String prefix = newFile.isDirectory() ? "📁 " : "📄 ";
                                                nameList.set(pos, prefix + newName + "\n" + time);

                                                adapter.notifyDataSetChanged();
                                                toast(getString(R.string.toast_renamed));
                                            } else {
                                                toast(getString(R.string.toast_rename_failed));
                                            }
                                        }
                                    }).show();
                        }
                    }).show();
            return true;
        });

        dialog.show();
    }

    // 【插入/替换在这里】
// 替换原本的 restoreBackup 方法
    private void restoreBackup(File backupFile) {
        try {
            // 分支 A: 恢复玩家数据 (文件夹)
            if (backupFile.isDirectory()) {
                String uniqueId = String.valueOf(System.currentTimeMillis());

                // 恢复的目标目录
                File restoreWorkDir = new File(getWorksDir(), "working_db_restore_" + uniqueId);

                deleteRecursive(restoreWorkDir);
                // [修改前] copyDirectory(backupFile, restoreWorkDir);
                // [修改后] 加速
                smartCopy(backupFile, restoreWorkDir);

                // 净化锁文件
                new File(restoreWorkDir, "LOCK").delete();
                new File(restoreWorkDir, "LOG").delete();
                new File(restoreWorkDir, "LOG.old").delete();

                currentWorkingDbPath = restoreWorkDir.getAbsolutePath();

                // 读取 LevelDB
                PlayerDbManager db = new PlayerDbManager(currentWorkingDbPath);
                byte[] data = db.readLocalPlayer();
                db.close();

                // === 【核心修复】 ===
                // 旧代码: String json = BedrockParser.parseBytes(data);
                // 新代码: 直接接收 JsonObject，不再经过 String 转换，防OOM
                rootNbtData = BedrockParser.parseBytes(data);

                isEditingPlayer = true;
                // 注意：旧代码还有一行 JsonParser.parseString... 现在不需要了

                updateAdapter(rootNbtData);

                if (tvCurrentPath != null)
                    tvCurrentPath.setText(getString(R.string.path_restored_player));
                toast(getString(R.string.toast_player_restored));
            }
            // 分支 B: 恢复世界数据 (Level.dat 文件)
            else {
                File targetFile = new File(getWorksDir(), LEVEL_DAT_NAME);
                copyFileNative(backupFile, targetFile);
                currentWorkingFileOrDir = targetFile.getAbsolutePath();

                // parseLevelDat 我们之前已经修过了，直接调用即可
                parseLevelDat();

                toast(getString(R.string.toast_level_restored));
            }
        } catch (Exception e) {
            toast(getString(R.string.err_restore_failed_prefix) + e.getMessage());
        }
    }

    // ==========================================
    // 业务逻辑 (保持不变)
    // ==========================================

private void copyLevelDatOut(final String folder) {
        // 1. 【保存现场】不管之前在编辑谁，先存个档到内存
        saveCurrentSessionToMemory();

        // 2. 检查是不是切换了存档文件夹
        boolean isNewWorld = (lastLoadedWorldFolder == null || !lastLoadedWorldFolder.equals(folder));

        if (isNewWorld) {
            // 如果是新存档，必须彻底重置所有缓存
            resetCurrentSession();
            lastLoadedWorldFolder = folder;
        } else {
            // 如果还是当前存档，尝试直接恢复 level.dat 的会话
            // 这样你未保存的修改就能找回来
            if (tryRestoreSession("level.dat")) {
                return; // 恢复成功，直接结束，不用读文件！
            }
        }

        final ProgressDialog loading = ProgressDialog.show(this, getString(R.string.msg_loading), getString(R.string.msg_moving), true);

        new Thread(() -> {
            try {
                String src = currentWorldsPath + folder + "/level.dat";
                File destFile = new File(getWorksDir(), LEVEL_DAT_NAME);

                if (destFile.exists()) destFile.delete();

                boolean success = copyFileNative(new File(src), destFile);

                if (!success && checkShizukuAvailable()) {
                    File bridgeDir = new File(BRIDGE_ROOT);
                    if (!bridgeDir.exists()) bridgeDir.mkdirs();
                    String bridgeFile = BRIDGE_ROOT + "level.dat";

                    runShizukuCmd(new String[]{"sh", "-c", "mkdir -p \"" + BRIDGE_ROOT + "\""}).waitFor();
                    runShizukuCmd(new String[]{"sh", "-c", "cp \"" + src + "\" \"" + bridgeFile + "\""}).waitFor();
                    runShizukuCmd(new String[]{"sh", "-c", "chmod 777 \"" + bridgeFile + "\""}).waitFor();

                    File bF = new File(bridgeFile);
                    if (bF.exists()) {
                        copyFile(bF, destFile);
                        success = true;
                    }
                }

                if (success) {
                    currentWorkingFileOrDir = destFile.getAbsolutePath();

                    runOnUiThread(() -> {
                        loading.dismiss();

                        // 【核心修复】强制切换到世界模式
                        isEditingPlayer = false;
                        currentTargetKey = null; // level.dat 没有 Key

                        // 此时 isEditingPlayer 已经是 false 了，parseLevelDat 会正确读取文件
                        parseLevelDat();

                        toast(getString(R.string.toast_loaded) + "level.dat");
                    });
                } else {
                    throw new Exception(getString(R.string.err_read_file_not_generated));
                }
            } catch (final Exception e) {
                runOnUiThread(() -> { loading.dismiss(); toast(e.toString()); });
            }
        }).start();
    }

private void parseLevelDat() {
        // 1. 保存现场
        saveCurrentSessionToMemory();
        
        final ProgressDialog loading = ProgressDialog.show(this, getString(R.string.msg_reading), getString(R.string.msg_reading_details), true);
        new Thread(() -> {
            try {
                final JsonObject newJson;

                if (isEditingPlayer) {
                    // === 读数据库 Key ===
                    if (currentWorkingDbPath == null) throw new Exception(getString(R.string.err_internal_player_path));
                    File lockFile = new File(currentWorkingDbPath, "LOCK");
                    if (lockFile.exists()) lockFile.delete();

                    PlayerDbManager dbManager = new PlayerDbManager(currentWorkingDbPath);
                    byte[] data;
                    if (currentTargetKey != null) {
                        data = dbManager.readSpecificKey(currentTargetKey);
                    } else {
                        data = dbManager.readLocalPlayer();
                        currentTargetKey = "~local_player";
                    }
                    dbManager.close();
                    newJson = BedrockParser.parseBytes(data);

                } else {
                    // === 读 Level.dat ===
                    String path = currentWorkingFileOrDir;
                    if (path == null) path = new File(getWorksDir(), LEVEL_DAT_NAME).getAbsolutePath();
                    if (!new File(path).exists()) throw new Exception(getString(R.string.err_work_file_missing));

                    newJson = BedrockParser.parse(path);
                }

                runOnUiThread(() -> {
                    loading.dismiss();
                    try {
                        rootNbtData = newJson;

                        // 更新缓存
                        if (isEditingPlayer) nbtDataCache.put(currentTargetKey, rootNbtData);
                        else nbtDataCache.put("level.dat", rootNbtData);

                        // 重置视图
                        navigationStack.clear();
                        pathStack.clear();
                        scrollPositionStack.clear();
                        currentListData = rootNbtData;

                        updateAdapter(rootNbtData);
                        updatePathTitle();

                        // 【核心修复】补回这行代码！
                        // 如果当前加载的是 level.dat，就尝试提取名字和种子显示在标题栏
                        if (!isEditingPlayer) {
                            updateWorldInfoCard(rootNbtData);
                        }

                        String targetName = (isEditingPlayer && currentTargetKey != null) ? currentTargetKey : "Level.dat";
                        toast(getString(R.string.toast_refreshed_prefix) + targetName);

                    } catch (Exception uiEx) {
                        toast(getString(R.string.err_display_data) + uiEx);
                    }
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    loading.dismiss();
                    toast("Refresh Failed: " + e.getMessage());
                });
            }
        }).start();
    }



// 1. 加载玩家数据 (带 Blocktopograph 拦截 + 路径合并)
// 1. 加载玩家数据 (加强版：自动清理僵尸锁)
private void loadPlayerData(final String folderName, final Runnable onSuccess) {
        // === 1. 参数校验 ===
        if (folderName == null || folderName.trim().isEmpty() || folderName.equals(getString(R.string.hint_select_world))) {
            toast(getString(R.string.toast_error_archive_name_is_empty));
            return; 
        }

        // === 2. 【新增】会话保存与快速恢复 ===
        // 在加载新数据前，保存当前工作现场
        saveCurrentSessionToMemory();

        // 如果没有指定成功回调（说明是单纯的切换/重新进入），且内存里有缓存，直接恢复，避免 IO 操作
        if (onSuccess == null && tryRestoreSession("~local_player")) {
            return;
        }

        // === 3. 初始化加载状态 ===
        final boolean canUseShizuku = checkShizukuAvailable(); 
        final ProgressDialog loading = ProgressDialog.show(this, getString(R.string.title_read_player), getString(R.string.msg_init_player), true);

        new Thread(() -> {
            try {
                // === 4. 路径构建 ===
                File base = new File(currentWorldsPath);
                File worldDir = new File(base, folderName);
                // 这里的路径构建更安全，避免多余的斜杠
                String srcPath = new File(worldDir, "db").getAbsolutePath();
                File srcDirFile = new File(srcPath);

                // === 5. 判断模式 (Native vs Shizuku) ===
                boolean useNative = false;
                // 如果文件夹存在、可读且能列出文件，优先使用原生方式（速度快、无Hack）
                if (srcDirFile.exists() && srcDirFile.canRead() && srcDirFile.listFiles() != null) {
                    useNative = true;
                } else {
                    // 如果原生不可用，必须依赖 Shizuku
                    if (!canUseShizuku) {
                        throw new Exception(getString(R.string.toast_err_permission_denied_shizuku));
                    }
                }

                // === 6. 主动清除源头僵尸锁 ===
                try {
                    if (useNative) {
                        File lock = new File(srcDirFile, "LOCK");
                        if (lock.exists()) lock.delete();
                    } else {
                        Process pCheck = runShizukuCmd(new String[]{"sh", "-c", "ls \"" + srcPath + "/LOCK\""});
                        if (pCheck.waitFor() == 0) {
                            runShizukuCmd(new String[]{"sh", "-c", "rm -f \"" + srcPath + "/LOCK\""}).waitFor();
                            runOnUiThread(() -> toast(getString(R.string.toast_lock_delete)));
                        }
                    }
                } catch (Exception ignored) { }

                // === 7. 准备工作目录 ===
                String uniqueId = String.valueOf(System.currentTimeMillis());
                File workDir = new File(getWorksDir(), "working_db_" + uniqueId);
                String appPrivatePath = workDir.getAbsolutePath();

                // === 8. 搬运流程 ===
                if (useNative) {
                    // 【分支A】原生 Java 复制
                    if (!workDir.exists()) workDir.mkdirs();
                    // 使用 smartCopy (假设是优化后的复制方法)
                    smartCopy(srcDirFile, workDir);

                } else {
                    // 【分支B】Shizuku 复制
                    String bridgePath = BRIDGE_ROOT + "load_db_" + uniqueId;

                    // 准备中转站
                    runShizukuCmd(new String[]{"sh", "-c", "mkdir -p \"" + BRIDGE_ROOT + "\""}).waitFor();
                    createNoMedia(); // 防止相册扫描碎片文件

                    runShizukuCmd(new String[]{"sh", "-c", "rm -rf \"" + bridgePath + "\""}).waitFor();
                    runShizukuCmd(new String[]{"sh", "-c", "mkdir -p \"" + bridgePath + "\""}).waitFor();

                    // 带错误检查的复制命令
                    String cmd = "cp -rf \"" + srcPath + "/.\" \"" + bridgePath + "/\"";
                    Process pCopy = runShizukuCmd(new String[]{"sh", "-c", cmd});
                    int exitCode = pCopy.waitFor();

                    if (exitCode != 0) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(pCopy.getErrorStream()));
                        StringBuilder errSb = new StringBuilder();
                        String line;
                        while((line = reader.readLine()) != null) errSb.append(line).append("\n");
                        throw new Exception("Shizuku Copy Error (" + exitCode + "):\n" + errSb + "\nSource: " + srcPath);
                    }

                    // 提权与检查
                    runShizukuCmd(new String[]{"sh", "-c", "chmod -R 777 \"" + bridgePath + "\""}).waitFor();
                    File bridgeDir = new File(bridgePath);

                    if (!bridgeDir.exists()) {
                        throw new Exception(getString(R.string.msg_failed_to_create_staging_directory) + bridgePath);
                    }
                    String[] files = bridgeDir.list();
                    if (files == null || files.length == 0) {
                         throw new Exception(getString(R.string.msg_the_source_directory_appears_to_be_empty) + srcPath);
                    }

                    if (!workDir.exists()) workDir.mkdirs();
                    smartCopy(bridgeDir, workDir); // 从中转站复制到私有目录

                    // 清理中转站
                    runShizukuCmd(new String[]{"sh", "-c", "rm -rf \"" + bridgePath + "\""}).waitFor();
                }

                // === 9. 本地净化与读取 ===
                new File(workDir, "LOCK").delete();
                new File(workDir, "LOG").delete();
                new File(workDir, "LOG.old").delete();

                currentWorkingDbPath = appPrivatePath;

                PlayerDbManager dbManager = new PlayerDbManager(appPrivatePath);
                byte[] data = dbManager.readLocalPlayer();
                dbManager.close();

                final JsonObject playerDataObj = BedrockParser.parseBytes(data);

                // === 10. UI 更新 (成功) ===
                runOnUiThread(() -> {
                    loading.dismiss();

                    // 状态重置
                    isEditingPlayer = true;
                    currentTargetKey = "~local_player"; // 核心修复：重置 Key

                    // 数据加载
                    rootNbtData = playerDataObj;
                    // 更新缓存 (Snippet 1 特性)
                    nbtDataCache.put("~local_player", rootNbtData);

                    // 界面刷新
                    navigationStack.clear();
                    pathStack.clear();
                    scrollPositionStack.clear();

                    updateAdapter(rootNbtData);
                    updatePathTitle(); // 刷新标题 (Snippet 1 特性)

                    toast(getString(R.string.toast_player_loaded_success));

                    // 执行回调
                    if (onSuccess != null) {
                        onSuccess.run();
                    }

                    // 更新路径显示
                    if (tvCurrentPath != null)
                        tvCurrentPath.setText(getString(R.string.path_editing_player, folderName));
                });

} catch (final Exception e) {
// === 11. 异常处理 ===
runOnUiThread(() -> {
    loading.dismiss();

    // 特殊处理：数据库损坏
    if (e.getMessage() != null && e.getMessage().contains("DB_CORRUPT")) {
        showDbRepairConfirmDialog(currentWorkingDbPath, folderName, onSuccess);
    }
    // 【新增】Shizuku 禁用时的直接访问失败
    else if (!useShizuku) {
        new AlertDialog.Builder(MainActivity.this)
                .setTitle(getString(R.string.title_load_failed))
                .setMessage(getString(R.string.err_direct_access_failed) + "\n\n" + getFullStackTrace(e))
                .setPositiveButton(getString(R.string.btn_open_settings), (d, w) -> {
                    // 检查版本
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } else {
                        // Android 10 及以下使用传统存储设置
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    }
                })
                .setNegativeButton(getString(R.string.btn_understood), null)
                .show();
    }
    // 原有通用处理
    else {
        new AlertDialog.Builder(MainActivity.this)
            .setTitle(getString(R.string.title_load_failed))
            .setMessage(getString(R.string.msg_load_failed_advice) + getFullStackTrace(e))
            .setPositiveButton(getString(R.string.btn_copy_error), (d, w) -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("Error", getFullStackTrace(e));
                cm.setPrimaryClip(clip);
                toast(getString(R.string.toast_copied));
            })
            .setNegativeButton(getString(R.string.btn_understood), null)
            .show();
    }
});
}
        }).start();
    }

// 2. 保存回写 (修复版：带更详细的错误反馈)
    private void saveAndPushBack(final JsonObject dataToSave, final String folder) {
        final ProgressDialog loading = ProgressDialog.show(this, getString(R.string.msg_saving), getString(R.string.msg_processing), true);
        new Thread(() -> {
            try {
                // 1. 创建备份
                createBackup(folder);

                // 2. 判断当前模式
                if (isEditingPlayer) {
                    // === 保存玩家数据 ===
                    if (currentWorkingDbPath == null)
                        throw new Exception("Error: DbPath is NULL");
                    File oldWorkDir = new File(currentWorkingDbPath);
                    if (!oldWorkDir.exists())
                        throw new Exception("Error: WorkDir lost (" + currentWorkingDbPath + ")");
                    if (currentTargetKey == null) {
                         // 如果万一为空，兜底设为本地玩家，防止写飞
                         currentTargetKey = "~local_player";
                    }

                    // 创建新的唯一目录
                    String uniqueId = String.valueOf(System.currentTimeMillis());
                    File newWorkDir = new File(getWorksDir(), "working_db_" + uniqueId);

                    // 复制旧环境
                                            // [修改前] copyDirectory(oldWorkDir, newWorkDir);
                    // [修改后] 加速
                    smartCopy(oldWorkDir, newWorkDir);
                    // 删除旧锁
                    new File(newWorkDir, "LOCK").delete();
                    new File(newWorkDir, "LOG").delete();
                    new File(newWorkDir, "LOG.old").delete();

                    // 【核心改动1】JsonObject -> Bytes -> LevelDB (在后台线程序列化)
                    byte[] bytes = BedrockParser.writeToBytes(dataToSave);
                    PlayerDbManager db = new PlayerDbManager(newWorkDir.getAbsolutePath());
                    db.writeSpecificKey(currentTargetKey, bytes);
                    db.close();

                    // 回写到游戏目录 (Bridge -> Shizuku -> Data)
                    String bridgeSave = BRIDGE_ROOT + "save_db_" + uniqueId;
                    runShizukuCmd(new String
                            []{"sh", "-c", "mkdir -p \"" + BRIDGE_ROOT + "\""}).waitFor();
                            createNoMedia();
                    runShizukuCmd(new String
                            []{"sh", "-c", "rm -rf \"" + bridgeSave + "\""}).waitFor();
                    // [修改前] copyDirectory(newWorkDir, new File(bridgeSave));
                    // [修改后] 加速
                    smartCopy(newWorkDir, new File(bridgeSave));

                    String mcDbPath = currentWorldsPath + folder + "/db/";
                    runShizukuCmd(new String
                            []{"sh", "-c", "cp -rf \"" + bridgeSave + "/.\" \"" + mcDbPath + "\""}).waitFor();
                    runShizukuCmd(new String
                            []{"sh", "-c", "rm -rf \"" + bridgeSave + "\""}).waitFor();

                    // 清理
                    deleteRecursive(oldWorkDir);
                    currentWorkingDbPath = newWorkDir.getAbsolutePath();

                    runOnUiThread(() -> {
                        loading.dismiss();
                        toast(getString(R.string.toast_player_saved));
                    });

                } else {
                    // === 保存 Level.dat ===

                    // 1. 确认路径
                    if (currentWorkingFileOrDir == null) {
                        File f = new File(getWorksDir(), LEVEL_DAT_NAME);
                        currentWorkingFileOrDir = f.getAbsolutePath();
                    }

                    // 【核心改动2】JsonObject -> 文件 (在后台线程序列化)
                    BedrockParser.write(dataToSave, currentWorkingFileOrDir);

                    // 3. 准备回写到游戏
                    File workingFile = new File(currentWorkingFileOrDir);
                    String bridgeFile = BRIDGE_ROOT + "level.dat";
                    new File(BRIDGE_ROOT).mkdirs();
                    copyFile(workingFile, new File(bridgeFile));

                    String targetPath = currentWorldsPath + folder + "/level.dat";

                    // 4. 尝试原生覆盖
                    boolean success = copyFileNative(workingFile, new File(targetPath));

                    // 5. 原生失败则用 Shizuku
                    if (!success && checkShizukuAvailable()) {
                        runShizukuCmd(new String
                                []{"sh", "-c", "cp \"" + bridgeFile + "\" \"" + targetPath + "\""}).waitFor();
                        success = true;
                    }

                    if (success) {
                        runOnUiThread(() -> {
                            loading.dismiss();
                            toast(getString(R.string.toast_level_dat_saved));
                        });
                    } else {
                        throw new Exception(getString(R.string.msg_write_to_game_dir_failed));
                    }
                }
} catch (final Exception e) {
runOnUiThread(() -> {
    loading.dismiss();
    String msg = e.getMessage();

    // 【新增】区分 Shizuku 禁用状态和实际错误
    if (!useShizuku) {
        // 用户禁用了 Shizuku，提示直接访问失败
        new AlertDialog.Builder(MainActivity.this)
            .setTitle(getString(R.string.err_save_failed))
            .setMessage(getString(R.string.err_direct_access_failed) + "\n\n" + msg)
                .setPositiveButton(getString(R.string.btn_open_settings), (d, w) -> {
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        // Android 11+ 使用 MANAGE_ALL_FILES_ACCESS_PERMISSION
                        Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } else {
                        // Android 10 及以下使用应用详情设置页
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    }
                })
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show();
    } else {
        // 原有 Shizuku 错误处理
        if (msg != null && msg.contains("Shizuku")) {
            new AlertDialog.Builder(MainActivity.this)
                .setTitle(getString(R.string.title_shizuku_error))
                .setMessage(msg)
                .setPositiveButton(getString(R.string.btn_open_shizuku), (d, w) -> {
                    try {
                        Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                        if (intent != null) {
                            startActivity(intent);
                        }
                    } catch (Exception ex) {
                        toast(getString(R.string.toast_shizuku_not_installed));
                    }
                })
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show();
        } else {
            // 普通错误
            new AlertDialog.Builder(MainActivity.this)
                .setTitle(getString(R.string.err_save_failed))
                .setMessage(getFullStackTrace(e))
                .setPositiveButton(getString(R.string.btn_confirm), null)
                .show();
        }
    }
});
}
        }).start();
    }

    private void createBackup(String folderName) {
        try {
            // 1. 确保备份目录存在
            File backupRoot = new File(getBackupsDir(), folderName);
            if (!backupRoot.exists()) backupRoot.mkdirs();

            String timeStamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(new Date());

            if (isEditingPlayer) {
                // 玩家模式：检查源目录
                if (currentWorkingDbPath != null) {
                    File srcDb = new File(currentWorkingDbPath);
                    // 【加固】如果源目录还存在，才备份
                    if (srcDb.exists() && srcDb.isDirectory()) {
                        File dstDb = new File(backupRoot, "db_" + timeStamp);
                        copyDirectory(srcDb, dstDb);
                    }
                }
            } else {
                // 世界模式：检查源文件
                // 【加固】使用 getWorksDir() 确保父目录存在
                File src = new File(getWorksDir(), LEVEL_DAT_NAME);
                if (src.exists() && src.isFile()) {
                    File dst = new File(backupRoot, "level_" + timeStamp + ".dat");
                    copyFileNative(src, dst);
                }
            }
        } catch (Exception e) {
            // 备份是辅助功能，失败了也不要打断主流程，打印日志即可
            Log.e("MainActivity", "操作失败", e);
        }
    }

    // ==========================================
    // 列表显示与路径选择
    // ==========================================
// 列表显示与路径选择 (支持显示真实地图名)
    private void showWorldSelector() {
        final ProgressDialog loading = ProgressDialog.show(this, getString(R.string.msg_scanning), getString(R.string.msg_parsing_archive_information), true);
        
        new Thread(() -> {
            try {
                // 1. 获取文件夹列表
                List<String> rawFolders = new ArrayList<>();

                // 原生获取
                File dir = new File(currentWorldsPath);
                if (dir.exists() && dir.canRead()) {
                     File[] files = dir.listFiles();
                     if(files!=null) for(File f:files) if(f.isDirectory()) rawFolders.add(f.getName());
                }

                // Shizuku 获取
                if (rawFolders.isEmpty() && checkShizukuAvailable()) {
                    Process p = runShizukuCmd(new String[]{"sh", "-c", "ls \"" + currentWorldsPath + "\""});
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String line;
                    while((line=r.readLine())!=null) if(!line.trim().isEmpty()) rawFolders.add(line.trim());
                    p.waitFor();
                }

                // 2. 过滤有效存档并读取名字
                final List<String> displayList = new ArrayList<>(); // 显示用的 (名字+ID)
                final List<String> folderList = new ArrayList<>();  // 逻辑用的 (ID)

                for (String name : rawFolders) {
                    String fullPath = currentWorldsPath + name;
                    File checkLevel = new File(fullPath, "level.dat");
                    File checkDb = new File(fullPath, "db");

                    boolean isGood = false;

                    // 检查有效性
                    if (checkLevel.exists() && checkDb.exists()) isGood = true;
                    else if (checkShizukuAvailable()) {
                         try {
                             int code1 = runShizukuCmd(new String[]{"sh", "-c", "ls \"" + checkLevel.getAbsolutePath() + "\""}).waitFor();
                             // ls -d 检查文件夹
                             int code2 = runShizukuCmd(new String[]{"sh", "-c", "ls -d \"" + checkDb.getAbsolutePath() + "\""}).waitFor();
                             if (code1 == 0 && code2 == 0) isGood = true;
                         } catch(Exception e){
                             Log.w(TAG, "Shizuku check failed: " + e.getMessage());
                         }
                    }

                    if (isGood) {
                        // 【核心修改】读取真实名字
                        String realName = getWorldRealName(name);
                        if (realName == null) realName = getString(R.string.msg_unknown_world);

                        // 格式： "我的世界\n-w14OU6m5Gk="
                        displayList.add(realName + "\n" + name);
                        folderList.add(name);
                    }
                }

                // 转换为数组
                final String[] displayArr = displayList.toArray(new String[0]);

                runOnUiThread(() -> {
                    loading.dismiss();
                    if(displayArr.length == 0) {
                        toast(getString(R.string.err_no_saves_detail));
                        return;
                    }

                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle(String.format(getString(R.string.dialog_select_archive_title), displayArr.length))
                        .setItems(displayArr, (d, i) -> {
                            // 点击时，从 folderList 里取纯净的文件夹名
                            String realFolder = folderList.get(i);

                            // 更新输入框，只显示文件夹名 (或者你想显示中文名也可以，但逻辑要改)
                            // 这里建议输入框里还是显示名字+ID，或者只显示ID
                            // 为了兼容之前的逻辑，这里暂时填入 ID，或者你可以把 UI 改成显示中文
                            etWorldName.setText(realFolder);

                            // 触发加载
                            copyLevelDatOut(realFolder);
                        }).show();
                });
            } catch(final Exception e) {
                runOnUiThread(() -> { loading.dismiss(); toast(e.toString()); });
            }
        }).start();
    }

private void showPathSelector() {
    final String[] options = {
            getString(R.string.path_standard),
            getString(R.string.path_legacy),
            getString(R.string.path_custom),
            getString(R.string.path_saf)  // 【新增】SAF 选项
    };

    new AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_path_title))
            .setItems(options, (d, w) -> {
                if (w == 0) {
                    currentWorldsPath = PATH_STANDARD;
                    toast(getString(R.string.toast_switched_std));
                    showWorldSelector();
                } else if (w == 1) {
                    currentWorldsPath = PATH_LEGACY;
                    toast(getString(R.string.toast_switched_legacy));
                    showWorldSelector();
                } else if (w == 2) {
                    showCustomPathDialog();
                } else if (w == 3) {
                    // 【新增】SAF 路径选择
                    openSafPathSelector();
                }
            })
            .show();
}

    private void showCustomPathDialog() {
        final EditText input = new EditText(this);
        input.setText(currentWorldsPath);
        input.setHint("/storage/emulated/0/...");

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_custom_path_title))
                .setMessage(getString(R.string.msg_custom_path))
                .setView(input)
                .setPositiveButton(getString(R.string.btn_confirm), (d, w) -> {
                    String p = input.getText().toString().trim();
                    if (p.isEmpty()) return;
                    // 去除末尾可能多余的斜杠，为了下面获取 getName() 准确
                    if (p.endsWith("/")) p = p.substring(0, p.length() - 1);

                    File target = new File(p);
                    File checkLevel = new File(target, "level.dat");
                    File checkDb = new File(target, "db");

                    boolean hasLevel = checkLevel.exists();
                    boolean hasDb = checkDb.exists() && checkDb.isDirectory();

                    // Shizuku 二次检查
                    if (checkShizukuAvailable() && (!hasLevel || !hasDb)) {
                        try {
                            if (!hasLevel && runShizukuCmd(new String
                                                    []{"sh", "-c", "ls \"" + checkLevel.getAbsolutePath() + "\""}).waitFor() == 0)
                                hasLevel = true;
                            if (!hasDb && runShizukuCmd(new String
                                                    []{"sh", "-c", "ls -d \"" + checkDb.getAbsolutePath() + "\""}).waitFor() == 0)
                                hasDb = true;
                        } catch (Exception e) {
                            Log.w(TAG, "Shizuku db check failed: " + e.getMessage());
                        }
                    }

                    if (hasLevel || hasDb) {
                        // --- 命中具体存档逻辑 ---
                        if (hasLevel && hasDb) {
                            // 1. 设置 Base 路径为该存档的【上一级目录】
                            // 这样 copyLevelDatOut 拼接路径时才正确
                            File parentDir = target.getParentFile();
                            if (parentDir != null) {
                                currentWorldsPath = parentDir.getAbsolutePath() + "/";
                            }

                            // 2. 获取存档文件夹名
                            String folderName = target.getName();

                            // 3. 更新 UI
                            etWorldName.setText(folderName);
                            toast(getString(R.string.toast_direct_load) + folderName);

                            // 4. 【核心改动】直接调用加载逻辑，不弹列表！
                            copyLevelDatOut(folderName);
                        } else {
                            // 存档残缺报错
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle(getString(R.string.title_archive_damaged))
                                    .setMessage(String.format(getString(R.string.msg_archive_damaged), (hasLevel ? "✅" : "❌"), (hasDb ? "✅" : "❌")))
                                    .setPositiveButton(getString(R.string.btn_confirm), null)
                                    .show();
                        }
                    } else {
                        // --- 未命中具体存档，视为父目录列表模式 ---
                        if (!p.endsWith("/")) p += "/"; // 补回斜杠
                        currentWorldsPath = p;
                        toast(getString(R.string.toast_path_updated));
                        showWorldSelector();
                    }
                })
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show();
    }

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                // 【新增】跳转前先提示用户
                toast(getString(R.string.toast_storage_perm));

                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to open settings: " + e.getMessage());
                    toast("无法打开系统设置");
                }
            }
        }
    }

    private void registerListener() throws Exception {
        Class.forName("rikka.shizuku.Shizuku");
    }

private Process runShizukuCmd(String[] cmd) throws Exception {
    // 【新增】执行前检查 Shizuku 是否可用
    if (!checkShizukuAvailable()) {
        throw new Exception(getString(R.string.err_shizuku_not_running));
    }
    
    final Class<?> shizukuClass = Class.forName("rikka.shizuku.Shizuku");
    Method m = null;
    for (Method mm : shizukuClass.getDeclaredMethods()) {
        if (mm.getName().equals("newProcess") && mm.getParameterTypes().length == 3) {
            m = mm;
            break;
        }
    }
    if (m == null)
        for (Method mm : shizukuClass.getMethods())
            if (mm.getName().equals("newProcess") && mm.getParameterTypes().length == 3) {
                m = mm;
                break;
            }
    if (m == null) throw new Exception("Shizuku API Error");
    m.setAccessible(true);
    
    try {
        return (Process) m.invoke(null, cmd, null, null);
} catch (Exception e) {
    String msg = e.getMessage();
    if (msg != null && msg.contains("binder haven't been received")) {
        throw new Exception(getString(R.string.err_shizuku_binder_not_ready));
    }
    throw e;
}
}

    private boolean checkShizukuAvailable() {
        if (!useShizuku) {
            return false;
        }

        try {
            final Class<?> c = Class.forName("rikka.shizuku.Shizuku");

            // 安全转换 Boolean
            Method p = c.getMethod("pingBinder");
            Boolean pingResult = (Boolean) p.invoke(null);
            if (pingResult == null || !pingResult) return false;

            // 安全转换 Integer
            Method ch = c.getMethod("checkSelfPermission");
            Integer permission = (Integer) ch.invoke(null);
            return permission != null && permission == PackageManager.PERMISSION_GRANTED;

        } catch (Exception e) {
            return false;
        }
    }

private boolean checkShizukuReady() {
    // 【新增】禁用 Shizuku 时直接返回 true（表示"已准备好"走系统路径）
    if (!useShizuku) {
        return true;
    }
    
    if (checkShizukuAvailable()) return true;
    try {
        Class<?> c = Class.forName("rikka.shizuku.Shizuku");
        c.getMethod("requestPermission", int.class).invoke(null, 0);
    } catch (Exception e) {
        Log.w(TAG, "Shizuku request permission failed: " + e.getMessage());
    }
    return false;
}

    private void copyFile(File src, File dst) throws Exception {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] b = new byte[1024];
        int l;
        while ((l = in.read(b)) > 0) out.write(b, 0, l);
        in.close();
        out.close();
    }

    private boolean copyFileNative(File src, File dst) {
        try {
            copyFile(src, dst);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

// 通用递归复制 (排除 LOCK)
    private void copyDirectory(File source, File target) throws Exception {
        if (source.isDirectory()) {
            if (!target.exists()) target.mkdirs();
            String[] children = source.list();
            if (children != null) {
                for (String child : children) {
                    // 【仅过滤锁文件】其他所有文件(.sst, .ldb, .log)通通带走！
                    if (child.equals("LOCK")) continue;
                    copyDirectory(new File(source, child), new File(target, child));
                }
            }
        } else {
            copyFile(source, target);
        }
    }

// 递归删除 (加强健壮性版)
    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            // 【修复】必须要判断 children 是否为 null
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        // 最后删除文件本身或空目录
        f.delete();
    }

    private void enterFolder(String key, JsonObject content, boolean isListMode) {
        // 1. 在进入下一级之前，记录当前列表滚动到了第几行
        int currentPos = nbtListView.getFirstVisiblePosition();
        scrollPositionStack.push(currentPos); // 压入栈

        // 2. 原有逻辑：压入数据、压入路径
        navigationStack.push(nbtAdapter.getData());
        pathStack.push(key);

        // 3. 刷新列表显示下一级内容
        updateAdapter(content);
        updatePathTitle();
        if (isListMode) toast(getString(R.string.toast_enter_list) + key);
    }

    @Override
    public void onBackPressed() {
View sidebarContainer = findViewById(R.id.custom_sidebar_container);
        if (sidebarContainer != null && sidebarContainer.getVisibility() == View.VISIBLE) {
            sidebarContainer.setVisibility(View.GONE);
            return;
        }
        // 1. 先检查有没有子文件夹可以退
        if (!navigationStack.isEmpty()) {
            JsonObject parent = navigationStack.pop();
            pathStack.pop();

            // 2. 刷新回上一级的数据
            updateAdapter(parent);
            updatePathTitle();

            // 3. 【核心新增】恢复之前的滚动位置
            if (!scrollPositionStack.isEmpty()) {
                final int lastPos = scrollPositionStack.pop();
                // 必须要 post 执行，因为 updateAdapter 刚刚才 reset 了列表
                nbtListView.post(() -> {
                    nbtListView.setSelection(lastPos); // 瞬间跳回原来的位置
                });
            }

            return; // 结束方法，不退出应用
        }

        // 4. 如果已经是根目录了，但还在全屏模式，则退出全屏
        if (isFullScreen) {
            toggleFullScreen(false);
            return; // 处理完毕，结束
        }

        // 5. 既在根目录，又不是全屏，那就真的退出了
        super.onBackPressed();
    }

private void updateAdapter(JsonObject data) {
        if (isTreeMode) {
            // === 树状图模式 ===
            nbtTreeAdapter = new NbtTreeAdapter(this, data);
            nbtTreeAdapter.setViewMode(currentViewMode);
            nbtListView.setAdapter(nbtTreeAdapter);
        } else {
            // === 列表模式 ===
            currentListData = data; 
            
            nbtAdapter = new NbtAdapter(this, data);
            nbtAdapter.setViewMode(currentViewMode);
            
            // 【核心新增】计算上下文路径
            String parent = "";
            String grandParent = "";
            
            if (!pathStack.isEmpty()) {
                parent = pathStack.peek(); // 栈顶就是当前父节点名
                if (pathStack.size() >= 2) {
                    grandParent = pathStack.get(pathStack.size() - 2); // 倒数第二个是爷爷节点
                }
            }
            // 传入上下文
            nbtAdapter.setPathContext(parent, grandParent);
            
            nbtListView.setAdapter(nbtAdapter);
        }
    }

// 更新顶部路径标题 (修复树状图标题显示错误的 Bug)
    private void updatePathTitle() {
        String titleText;
        
        if (isTreeMode) {
            // === 树状图模式 ===
            if (isEditingPlayer) {
                // 如果是玩家模式
                if (currentTargetKey != null && !currentTargetKey.equals("~local_player")) {
                    titleText = getString(R.string.title_dendrogram) + currentTargetKey; // 联机玩家/地图/村庄
                } else {
                    titleText = getString(R.string.title_treemap_player_data); // 本地玩家
                }
            } else {
                // 如果不是玩家模式，那就是 level.dat
                titleText = getString(R.string.title_treemap_world_data);
            }
        } else {
            // === 列表模式 ===
            if (pathStack.isEmpty()) {
                if (isEditingPlayer) {
                    if (currentTargetKey != null && !currentTargetKey.equals("~local_player")) {
                        titleText = getString(R.string.title_current) + currentTargetKey;
                    } else {
                        titleText = getString(R.string.path_current_player);
                    }
                } else {
                    titleText = getString(R.string.path_current_level);
                }
            } else {
                // 子目录
                StringBuilder sb = new StringBuilder(getString(R.string.path_prefix));
                for (String p : pathStack) {
                    sb.append(p).append("/");
                }
                titleText = sb.toString();
            }
        }

        // 更新 UI
        if (tvCurrentPath != null) tvCurrentPath.setText(titleText);
        if (tvFullscreenPath != null) tvFullscreenPath.setText(titleText);
    }

private void showEditValueDialog(final String key, final JsonObject item) {
    final int type = item.get("t").getAsInt();

    if (type == 9 || type == 10) {
        toast(getString(R.string.toast_click_detail));
        return;
    }

    final EditText input = new EditText(this);

    if (item.has("v")) {
        JsonElement v = item.get("v");
        if (v.isJsonArray()) {
            input.setText(v.toString());
        } else {
            input.setText(v.getAsString());
        }
    }

    // ========== 判断是否显示自动填充 ==========
    boolean showAutocomplete = false;
    String detectedType = "all";
    
    String parent = !pathStack.isEmpty() ? pathStack.peek() : "";
    String grandParent = pathStack.size() >= 2 ? pathStack.get(pathStack.size() - 2) : "";
    
    if (key.equals("Name") || key.equals("id") || key.equals("name") || 
        key.equals("Id") || key.equals("AttributeName")) {
        detectedType = detectAutocompleteType(key, parent, grandParent);
        showAutocomplete = true;
    }
    
    if ((type == 1 || type == 2) && "Id".equals(key)) {
        String dt = detectAutocompleteType(key, parent, grandParent);
        if (!"all".equals(dt)) {
            detectedType = dt;
        }
    }

    // ========== 构建对话框 ==========
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle(getString(R.string.title_edit) + " (" + getTypeName(type) + "): " + key);

    // 【关键】声明为 final，供内部类使用
    final String finalDataType = detectedType;

    if (showAutocomplete) {
        // ===== 带自动填充按钮的布局 =====
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(input);
        
        Button btnAutocomplete = new Button(this);
        btnAutocomplete.setText(getString(R.string.btn_choose, getTypeLabel(finalDataType)));
        btnAutocomplete.setTextColor(Color.parseColor("#2196F3"));
        btnAutocomplete.setBackgroundColor(Color.TRANSPARENT);
        btnAutocomplete.setOnClickListener(v -> showAutocompleteDialog(input, finalDataType, input.getText().toString()));
        layout.addView(btnAutocomplete);
        
        builder.setView(layout);
    } else {
        // ===== 普通字段，只有输入框 =====
        builder.setView(input);
    }

    // ========== 保存按钮 ==========
    builder.setPositiveButton(getString(R.string.btn_save_short), (d, w) -> {
        try {
            String val = input.getText().toString().trim();

            if (type >= 1 && type <= 3) {
                item.addProperty("v", Integer.parseInt(val));
            } else if (type == 4) {
                item.addProperty("v", Long.parseLong(val));
            } else if (type >= 5 && type <= 6) {
                item.addProperty("v", Double.parseDouble(val));
            } else if (type == 8) {
                item.addProperty("v", val);
            } else if (type == 7 || type == 11 || type == 12) {
                JsonElement parsed = new JsonParser().parse(val);
                item.add("v", parsed.getAsJsonArray());
            }

            if (!isTreeMode) {
                nbtAdapter.refreshKeys();
            } else {
                nbtTreeAdapter.notifyDataSetChanged();
            }
        } catch (Exception e) {
            toast(getString(R.string.err_save_failed) + e.getMessage());
        }
    });
    
    builder.show();
}

    // 辅助：获取类型名称显示在标题里，方便识别
    private String getTypeName(int type) {
        return switch (type) {
            case 1 -> "Byte";
            case 2 -> "Short";
            case 3 -> "Int";
            case 4 -> "Long";
            case 5 -> "Float";
            case 6 -> "Double";
            case 7 -> "ByteArray";
            case 8 -> "String";
            case 11 -> "IntArray";
            case 12 -> "LongArray";
            default -> "Unknown";
        };
    }

// 长按菜单逻辑 (完整修复版：列表和树状图都支持)
private void showLongPressMenu(final String key, final JsonObject itemData) {
    final String[] ops = {
            getString(R.string.menu_copy),
            getString(R.string.menu_paste),
            getString(R.string.menu_delete),
            getString(R.string.menu_rename),
            getString(R.string.menu_add_child)
    };

    new AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_manage_prefix) + key)
            .setItems(ops, (d, w) -> {
                // 根据模式获取正确的容器和 key
                JsonObject parentContainer;
                String nodeKey = key;
                JsonObject nodeData = itemData;

                if (isTreeMode) {
                    // 树状图模式
                    if (lastTreeClickPosition < 0) return;

                    parentContainer = nbtTreeAdapter.getParentContainer(lastTreeClickPosition);
                    if (parentContainer == null) {
                        parentContainer = rootNbtData;
                    }

                    String actualKey = nbtTreeAdapter.getNodeKey(lastTreeClickPosition);
                    if (actualKey != null) nodeKey = actualKey;

                    JsonObject actualData = nbtTreeAdapter.getNodeData(lastTreeClickPosition);
                    if (actualData != null) nodeData = actualData;
                } else {
                    // 列表模式
                    parentContainer = nbtAdapter.getData();
                }

                switch (w) {
                    case 0: // 复制
                        clipboard = nodeData.deepCopy();
                        toast(getString(R.string.toast_copy_success));
                        break;

                    case 1: // 粘贴
                        if (clipboard == null) {
                            toast(getString(R.string.toast_clipboard_empty));
                            return;
                        }
                        final String pasteKey = nodeKey + "_copy";
                        if (parentContainer.has(pasteKey)) {
                            toast(getString(R.string.toast_name_exists));
                            return;
                        }
                        parentContainer.add(pasteKey, clipboard.deepCopy());
                        refreshAfterTreeEdit();
                        toast(getString(R.string.toast_pasted));
                        break;

                    case 2: // 删除
                        parentContainer.remove(nodeKey);
                        refreshAfterTreeEdit();
                        toast(getString(R.string.toast_deleted));
                        break;

                    case 3: // 重命名
                        showTreeRenameDialog(nodeKey, nodeData, parentContainer);
                        break;

                    case 4: // 添加子项
                        int type = nodeData.get("t").getAsInt();
                        if (type == 10) {
                            JsonObject childContainer = nodeData.getAsJsonObject("v");
                            showAddChildDialog(childContainer);
                            // 树状图模式需要特殊处理展开
                            if (isTreeMode && !nbtTreeAdapter.getNode(lastTreeClickPosition).isExpanded) {
                                nbtTreeAdapter.toggleExpand(lastTreeClickPosition);
                            }
                        } else {
                            toast(getString(R.string.toast_compound_only));
                        }
                        break;
                }
            }).show();
}

// 树状图专用重命名对话框
private void showTreeRenameDialog(final String oldKey, final JsonObject itemData, final JsonObject parentContainer) {
    final EditText input = new EditText(this);
    input.setText(oldKey);
    new AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_rename))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_confirm), (d, w) -> {
                String newKey = input.getText().toString().trim();
                if (newKey.isEmpty() || newKey.equals(oldKey)) return;
                if (parentContainer.has(newKey)) {
                    toast(getString(R.string.toast_name_exists));
                    return;
                }
                parentContainer.remove(oldKey);
                parentContainer.add(newKey, itemData);
                refreshAfterTreeEdit();
                toast(getString(R.string.toast_renamed));
            })
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show();
}

// 树状图编辑后刷新（保持视图状态）
private void refreshAfterTreeEdit() {
    if (isTreeMode) {
        // 树状图：通知数据变化，保持展开状态
        nbtTreeAdapter.notifyDataSetChanged();
    } else {
        // 列表：刷新 keys
        nbtAdapter.refreshKeys();
    }
}

// 弹出类型选择框 (完整版)
    private void showAddChildDialog(final JsonObject parent) {
        // NBT 类型全家桶 (和你的截图一样)
        final String[] types = {
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
        };
        // 对应的 ID
        final int[] ids = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_add_child))
                .setItems(types, (d, w) -> {
                    final int selectedType = ids[w];
                    // 弹出命名对话框
                    showNameInputDialog(parent, selectedType);
                })
                .show();
    }

    // (对应的命名输入框逻辑，需要补全 Array 支持)
    private void showNameInputDialog(final JsonObject parentCompound, final int type) {
        final EditText input = new EditText(MainActivity.this);
        input.setHint(getString(R.string.hint_input_name));
        new AlertDialog.Builder(MainActivity.this)
                .setTitle(getString(R.string.menu_rename))
                .setView(input)
                .setPositiveButton(getString(R.string.btn_create), (dd, ww) -> {
                    String name = input.getText().toString();
                    if (!name.isEmpty()) {
                        if (parentCompound.has(name)) {
                            toast(getString(R.string.toast_name_exists));
                            return;
                        }

                        JsonObject t = new JsonObject();
                        t.addProperty("t", type);

                        // 根据不同类型设置初始值 (全类型支持)
                        switch (type) {
                            case 1:
                                t.addProperty("v", (byte) 0);
                                break;
                            case 2:
                                t.addProperty("v", (short) 0);
                                break;
                            case 3:
                                t.addProperty("v", 0);
                                break;
                            case 4:
                                t.addProperty("v", 0L);
                                break;
                            case 5:
                                t.addProperty("v", 0.0f);
                                break;
                            case 6:
                                t.addProperty("v", 0.0d);
                                break;
                            case 7:
                                t.add("v", new JsonArray());
                                break; // Byte Array
                            case 8:
                                t.addProperty("v", "");
                                break; // String
                            case 9:
                                t.add("v", new JsonArray());
                                t.addProperty("itemType", 0); // 默认为 End
                                break;
                            case 10:
                                t.add("v", new JsonObject());
                                break; // Compound
                            case 11:
                                t.add("v", new JsonArray());
                                break; // Int Array
                            case 12:
                                t.add("v", new JsonArray());
                                break; // Long Array
                        }

                        parentCompound.add(name, t);

                        // 刷新界面
                        if (nbtAdapter.getData() == parentCompound) nbtAdapter.refreshKeys();
                        toast(getString(R.string.toast_created) + name);
                    }
                }).show();
    }

private void showRootMenu() {
    // 【修复】兼容树状图模式，使用 final 声明
    final JsonObject current;
    if (isTreeMode) {
        if (rootNbtData == null) {
            toast(getString(R.string.toast_no_data));
            return;
        }
        current = rootNbtData;
    } else {
        if (nbtAdapter == null || nbtAdapter.getData() == null) {
            toast(getString(R.string.toast_no_data));
            return;
        }
        current = nbtAdapter.getData();
    }
    
    // 0: 添加, 1: 粘贴子项, 2: 复制整层, 3: 粘贴/导入(替换), 4: 清空
    String[] ops = {
            getString(R.string.action_add_tag),
            getString(R.string.action_paste_tag),
            getString(R.string.action_copy_root),
            getString(R.string.action_paste_root),
            getString(R.string.action_clear_all)
    };

    new AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_root_title))
            .setItems(ops, (d, w) -> {
                if (w == 0) {
                    // === 1. 添加子项 ===
                    showAddChildDialog(current);
                } else if (w == 1) {
                    // === 2. 粘贴 (作为子节点插入) ===
                    if (clipboard == null) {
                        toast(getString(R.string.toast_clipboard_empty));
                        return;
                    }
                    final EditText input = new EditText(MainActivity.this);
                    input.setHint(getString(R.string.hint_new_tag_name));
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle(getString(R.string.title_paste_as))
                            .setView(input)
                            .setPositiveButton(getString(R.string.btn_confirm), (dd, ww) -> {
                                String n = input.getText().toString();
                                if (!n.isEmpty() && !current.has(n)) {
                                    current.add(n, clipboard.deepCopy());
                                    refreshAfterTreeEdit();
                                    toast(getString(R.string.toast_pasted));
                                } else {
                                    toast(getString(R.string.toast_name_empty_or_exists));
                                }
                            }).show();
                } else if (w == 2) {
                    // === 3. 复制当前完整数据 (JSON 导出) ===
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("NBT_JSON", current.toString());
                    cm.setPrimaryClip(clip);
                    toast(getString(R.string.toast_copy_success));
                } else if (w == 3) {
                    // === 4. 粘贴并替换当前数据 (导入) ===
                    final android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (!cm.hasPrimaryClip()) {
                        toast(getString(R.string.toast_clipboard_empty));
                        return;
                    }
                    ClipData clip = cm.getPrimaryClip();
                    if (clip == null || clip.getItemCount() == 0) {
                        toast(getString(R.string.toast_clipboard_empty));
                        return;
                    }

                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle(getString(R.string.msg_confirm_replace))
                            .setMessage(getString(R.string.msg_replace_warning))
                            .setPositiveButton(getString(R.string.btn_replace), (dialog, which) -> {
                                try {
                                    CharSequence text = cm.getPrimaryClip().getItemAt(0).getText();
                                    if (text == null) return;

                                    JsonObject newObj = JsonParser.parseString(text.toString()).getAsJsonObject();

                                    // 清空当前界面并填入新数据
                                    List<String> oldKeys = new ArrayList<>(current.keySet());
                                    for (String k : oldKeys) current.remove(k);

                                    for (java.util.Map.Entry<String, JsonElement> entry : newObj.entrySet()) {
                                        current.add(entry.getKey(), entry.getValue());
                                    }

                                    refreshAfterTreeEdit();
                                    toast(getString(R.string.toast_pasted));

                                } catch (Exception e) {
                                    toast(getString(R.string.err_json_parse));
                                }
                            })
                            .setNegativeButton(getString(R.string.btn_cancel), null)
                            .show();
                } else if (w == 4) {
                    // === 5. 清空所有 ===
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle(getString(R.string.dialog_danger_title))
                            .setMessage(getString(R.string.dialog_clear_msg))
                            .setPositiveButton(getString(R.string.btn_confirm_clear), (dd, ww) -> {
                                List<String> keys = new ArrayList<>(current.keySet());
                                for (String key : keys) {
                                    current.remove(key);
                                }
                                refreshAfterTreeEdit();
                                toast(getString(R.string.toast_cleared));
                            })
                            .setNegativeButton(getString(R.string.btn_cancel), null)
                            .show();
                }
            }).show();
}

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // 放到 MainActivity 类的最下方
// 终极修复版 applyTheme (放在类末尾)
    private void applyTheme() {
        // 直接使用全局变量 viewMainContent，不再临时 findViewById
        if (viewMainContent == null) return; 

        // 重新获取文字控件 (这些还是动态获取比较好，防止视图树变化)
        TextView tvTitle = findViewById(R.id.tv_app_title);
        TextView tvLabelEdit = findViewById(R.id.tv_current_path);

        if (isNightMode) {
            // === 夜间模式 ===
            // 给主容器变色
            viewMainContent.setBackgroundColor(0xFF121212); // 黑灰
            
            // 如果全局变量 viewSidebar 不为空，也给它变色
            if (viewSidebar != null) {
                viewSidebar.setBackgroundColor(0xFF1E1E1E); // 稍浅的黑
            }

            // 文字变白
            if (tvTitle != null) tvTitle.setTextColor(0xFFFFFFFF);
            if (tvLabelEdit != null) tvLabelEdit.setTextColor(0xFFCCCCCC);
            if (etWorldName != null) {
                etWorldName.setBackgroundColor(0xFF333333);
                etWorldName.setTextColor(0xFFFFFFFF);
            }
            if (nbtListView != null) nbtListView.setBackgroundColor(0xFF1E1E1E);

        } else {
            // === 日间模式 ===
            boolean isDynamic = false;
            
            // 尝试 Android 12 动态色
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try {
                    @SuppressWarnings("DiscouragedApi")
                    int colorId = getResources().getIdentifier("system_neutral1_100", "color", "android");
                    if (colorId != 0) {
                        int dynamicColor = getResources().getColor(colorId);
                        viewMainContent.setBackgroundColor(dynamicColor);
                        if (viewSidebar != null) viewSidebar.setBackgroundColor(0xFFFFFFFF); // 侧边栏保持白或动态色都行
                        isDynamic = true;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Dynamic color load failed: " + e.getMessage());
                }
            }
            
            if (!isDynamic) {
                viewMainContent.setBackgroundColor(0xFFF5F5F5); // 默认灰白
                if (viewSidebar != null) viewSidebar.setBackgroundColor(0xFFFFFFFF);
            }

            if (tvTitle != null) tvTitle.setTextColor(0xFF333333);
            if (tvLabelEdit != null) tvLabelEdit.setTextColor(0xFF757575);
            if (etWorldName != null) {
                etWorldName.setBackgroundColor(0xFFFFFFFF);
                etWorldName.setTextColor(0xFF000000);
            }
            if (nbtListView != null) nbtListView.setBackgroundColor(0xFFFFFFFF);
        }
    }

// 找到你刚刚写的 toggleFullScreen，更新成这样：

    private void toggleFullScreen(boolean enable) {
        isFullScreen = enable;

        if (layoutNormalUi == null) layoutNormalUi = findViewById(R.id.layout_normal_ui);
        if (layoutEditorHeader == null)
            layoutEditorHeader = findViewById(R.id.layout_editor_header);
        if (layoutFullscreenBar == null)
            layoutFullscreenBar = findViewById(R.id.layout_fullscreen_bar);

        // 获取新加入的路径 TextView
        if (tvFullscreenPath == null) tvFullscreenPath = findViewById(R.id.tv_fullscreen_path);

        if (enable) {
            layoutNormalUi.setVisibility(View.GONE);
            layoutEditorHeader.setVisibility(View.GONE);
            layoutFullscreenBar.setVisibility(View.VISIBLE);

            // 【关键】全屏时，同步一次路径显示
            updatePathTitle();

        } else {
            layoutNormalUi.setVisibility(View.VISIBLE);
            layoutEditorHeader.setVisibility(View.VISIBLE);
            layoutFullscreenBar.setVisibility(View.GONE);
        }
    }

    private void showViewOptionsDialog() {
        String[] items = {
                getString(R.string.mode_raw), // 0: 原始
                getString(R.string.mode_raw_trans), // 1: 原始+翻译
                getString(R.string.mode_smart), // 2: 智能解析 (Key+中+值解析)
                getString(R.string.mode_simple) // 3: 极简 (只显中文Key)
        };

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_view_mode))
                .setSingleChoiceItems(items, currentViewMode, (d, which) -> {
// 更新模式
                    currentViewMode = which;
                    prefs.edit().putInt("view_mode", currentViewMode).apply(); // commit改apply更高效喵

// 【核心修改】安全刷新Adapter
                    if (nbtAdapter != null) {
                        nbtAdapter.setViewMode(currentViewMode);
                    }
                    if (nbtTreeAdapter != null) {
                        nbtTreeAdapter.setViewMode(currentViewMode);
                    }

                    d.dismiss();
                    toast(getString(R.string.toast_mode_changed));
                })
                .show();
    }

    // === [新增] 多线程并行目录复制引擎 (加速核心) ===
    // === [修复] 多线程并行复制 (加强防死锁) ===
    private void copyDirectoryParallel(File source, final File target) throws Exception {
        if (!source.exists()) return;
        if (!target.exists()) target.mkdirs();

        File[] files = source.listFiles();
        if (files == null || files.length == 0) return; // 判空处理

        // 1. 过滤文件列表
        final List<File> fileList = new ArrayList<>();
        for (File f : files) {
            // 不复制 LOCK，且只复制文件 (目录递归稍微麻烦，建议这里遇到子目录走单线程 copyDirectory)
            if (!f.getName().equals("LOCK")) {
                fileList.add(f);
            }
        }
        
        if (fileList.isEmpty()) return; // 没有需要复制的文件

        // 2. 创建线程池
        int cores = Runtime.getRuntime().availableProcessors();
        
        // IO 任务通常大部分时间在等读写，CPU 是空闲的，所以可以开多点线程压榨 IO 带宽
        // 经验公式：核心数 * 2，且保底 4 线程，最大不超过 12 (避免打开过多 FileHandle 崩溃)
        int threadCount = Math.max(4, Math.min(cores * 2, 12));
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        final CountDownLatch latch = new CountDownLatch(fileList.size());
        final AtomicReference<Exception> errorRef = new AtomicReference<>();

        for (final File srcFile : fileList) {
            executor.submit(() -> {
                try {
                    // 快速失败机制
                    if (errorRef.get() != null) return;

                    File destFile = new File(target, srcFile.getName());

                    if (srcFile.isDirectory()) {
                        // 子文件夹：走单线程递归（避免线程池爆炸）
                        // 且 db 文件夹下一般都是平铺文件，很少有子文件夹
                        copyDirectory(srcFile, destFile);
                    } else {
                        copyFile(srcFile, destFile);
                    }
                } catch (Exception e) {
                    errorRef.set(e);
                } finally {
                    // 【生死关键】 无论复制成功与否，必须减计数器！
                    latch.countDown();
                }
            });
        }

        // 4. 等待完成
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new Exception(getString(R.string.msg_copy_process_interrupted));
        } finally {
            // 确保线程池关闭
            executor.shutdown(); 
        }

        // 5. 向上抛出异常
        if (errorRef.get() != null) {
            throw errorRef.get();
        }
    }
    
// 1. 联机玩家列表 (修复 Final 问题)
    private void showMultiPlayerDialog() {
        if (cachePlayerList != null) {
            showListDialogInternal(cachePlayerList, TYPE_PLAYER);
            return;
        }
        final ProgressDialog loading = ProgressDialog.show(this, getString(R.string.msg_scanning), getString(R.string.msg_search_for_players), true);
        
        new Thread(() -> {
            try {
                PlayerDbManager db = new PlayerDbManager(currentWorkingDbPath);
                // 1. 获取并转存为 final
                final List<String> rawList = db.listPlayerKeys();
                db.close();

                cachePlayerList = rawList;

                runOnUiThread(() -> {
                    loading.dismiss();
                    // 2. 这里的 rawList 已经是 final 的了
                    showListDialogInternal(rawList, TYPE_PLAYER);
                });
            } catch(final Exception e){
                runOnUiThread(() -> {loading.dismiss(); toast(e.toString());});
            }
        }).start();
    }

// 加载指定 Key 的数据 (修复：优先恢复未保存的草稿，其次才是缓存)
    private void loadSpecificPlayer(String keyName) {
        final String finalKey = keyName;

        // 1. 【核心新增】切走前，先把当前正在编辑的界面保存为“草稿”
        saveCurrentSessionToMemory();

        // 2. 【核心新增】尝试恢复目标 Key 的“草稿”
        // 如果之前编辑过这个 Key 且没保存，这里会直接恢复现场，包括滚动位置
        if (tryRestoreSession(finalKey)) {
            return; 
        }

        // 3. 如果没有草稿，再检查静态缓存 (这是没修改过的原始数据)
        if (nbtDataCache.containsKey(finalKey)) {
            currentTargetKey = finalKey;
            isEditingPlayer = true;
            
            rootNbtData = nbtDataCache.get(finalKey);
            
            // 如果是读缓存（说明是第一次打开或重置过），重置视图到顶部
            navigationStack.clear(); 
            pathStack.clear();
            scrollPositionStack.clear();
            
            updateAdapter(rootNbtData);
            
            if(tvCurrentPath!=null) tvCurrentPath.setText(getString(R.string.title_current, finalKey));
            return;
        }

        // 4. 既没草稿也没缓存，只能读硬盘
        final ProgressDialog loading = ProgressDialog.show(this, getString(R.string.msg_read), getString(R.string.msg_load) + finalKey, true);
        
        new Thread(() -> {
            try {
                // 强制解锁
                if (currentWorkingDbPath != null) {
                    File lockFile = new File(currentWorkingDbPath, "LOCK");
                    if (lockFile.exists()) lockFile.delete();
                }

                PlayerDbManager db = new PlayerDbManager(currentWorkingDbPath);
                byte[] data = db.readSpecificKey(finalKey);
                db.close();

                final JsonObject jsonData = BedrockParser.parseBytes(data);

                runOnUiThread(() -> {
                    loading.dismiss();
                    currentTargetKey = finalKey;
                    isEditingPlayer = true;

                    navigationStack.clear();
                    pathStack.clear();
                    scrollPositionStack.clear();

                    rootNbtData = jsonData;
                    // 存入静态缓存
                    nbtDataCache.put(finalKey, rootNbtData);

                    updateAdapter(rootNbtData);

                    if(tvCurrentPath!=null) tvCurrentPath.setText(getString(R.string.title_current, finalKey));
                    toast(getString(R.string.toast_loaded) + finalKey);
                });
            } catch(final Exception e) {
                runOnUiThread(() -> {
                    loading.dismiss();
                    // 捕获空数据，询问创建
                    if (e.getMessage() != null && e.getMessage().contains(getString(R.string.msg_data_is_empty))) {
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle(getString(R.string.title_data_does_not_exist))
                                .setMessage(String.format(getString(R.string.msg_this_archive_has_not_generated_data_yet), finalKey))
                            .setPositiveButton(getString(R.string.btn_create_and_open), (d, w) -> createNewGlobalData(finalKey))
                            .setNegativeButton(getString(R.string.btn_cancel), null)
                            .show();
                    } else {
                        toast(getString(R.string.err_load_failed)+e.getMessage());
                    }
                });
            }
        }).start();
    }

// 1. [复制玩家数据]
    private void copyPlayerJson(final String key) {
        new Thread(() -> {
            try {
                PlayerDbManager db = new PlayerDbManager(currentWorkingDbPath);
                byte[] data = db.readSpecificKey(key);
                db.close();

                final String json = BedrockParser.parseBytes(data).toString();
                runOnUiThread(() -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("PLAYER_DATA", json);
                    cm.setPrimaryClip(clip);
                    toast(getString(R.string.toast_player_data_copied_to_clipboard));
                });
            } catch(final Exception e) {
                runOnUiThread(() -> toast(getString(R.string.toast_copy_failed)+e));
            }
        }).start();
    }

    // 2. [粘贴并覆盖]
// 2. [粘贴并覆盖] (修正版)
    private void pastePlayerJson(final String targetKey, final Runnable onSuccess) {
        final android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (!cm.hasPrimaryClip()) {
            toast(getString(R.string.toast_clipboard_empty));
            return;
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            toast(getString(R.string.toast_clipboard_empty));
            return;
        }
        
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_override_warning))
            .setMessage(getString(R.string.msg_full_coverage) + targetKey + getString(R.string.msg_this_action_is_irreversible))
            .setPositiveButton(getString(R.string.btn_cover), (d, w) -> {
                final String jsonStr = cm.getPrimaryClip().getItemAt(0).getText().toString();

                new Thread(() -> {
                    try {
                        JsonObject jo = JsonParser.parseString(jsonStr).getAsJsonObject();
                        byte[] bytes = BedrockParser.writeToBytes(jo);

                        // 【注意这里】重新创建一个新的 db 对象来操作，避免使用 final 的冲突
                        PlayerDbManager localDb = new PlayerDbManager(currentWorkingDbPath);
                        localDb.writeSpecificKey(targetKey, bytes);
                        localDb.close();

                        runOnUiThread(() -> {
                            toast(getString(R.string.toast_data_covered));
                            if(onSuccess!=null) onSuccess.run();
                        });
                    } catch(final Exception e) {
                        runOnUiThread(() -> toast(getString(R.string.toast_paste_failed)+e));
                    }
                }).start();
            })
            .setNegativeButton(getString(R.string.btn_cancel), null).show();
    }

    // 3. [删除玩家] (需要去DbManager加一个 deleteKey)
// 修复后的删除逻辑
    private void deletePlayerKey(final String key, final Runnable onSuccess) {
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_remove_warning))
            .setMessage(getString(R.string.msg_confirm_deletion) + key + "？")
            .setPositiveButton(getString(R.string.btn_delete), (d, w) -> {

                // 【技巧】把外部变量转为 final 本地变量，传给线程
                final String dbPath = currentWorkingDbPath;

                new Thread(() -> {
                    try {
                        PlayerDbManager db = new PlayerDbManager(dbPath); // 用 dbPath
                        db.deleteKey(key);
                        db.close();

                        runOnUiThread(() -> {
                            toast(getString(R.string.toast_deleted) + key);
                            if(onSuccess!=null) onSuccess.run();
                        });
                    } catch(final Exception e) {
                        runOnUiThread(() -> toast(getString(R.string.toast_delete_failed)+e));
                    }
                }).start();
            }).setNegativeButton(getString(R.string.btn_cancel), null).show();
    }

// 修复后的新建逻辑 (支持新建、粘贴、批量删除、拼图、国际化、防闪退)
private void showPlayerRootMenu(final List<String> currentList, final ArrayAdapter adapter, final int dataType) {  //???

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        
        final String typeName;
        final String hintName;
        
        // 1. 动态构建菜单列表
        final List<String> menuList = new ArrayList<>();
        // Index 0: 新建
        menuList.add(getString(R.string.msg_create_new_blank) + (dataType == TYPE_MAP ? getString(R.string.msg_map) : "") + " (Key)"); 
        // Index 1: 粘贴
        menuList.add(getString(R.string.msg_paste_as_new) + (dataType == TYPE_MAP ? getString(R.string.msg_map) : "") + " (New Key)"); 
        // Index 2: 删除所有
        menuList.add(getString(R.string.msg_delete_all_data_in_the_list)); 

        if (dataType == TYPE_MAP) {
            typeName = getString(R.string.msg_map_data);
            hintName = getString(R.string.msg_for_example_map);
            // Index 3: [新增] 只有地图模式才有拼图功能
            menuList.add(getString(R.string.msg_create_a_giant_jigsaw_puzzle)); 
        } else if (dataType == TYPE_VILLAGE) {
            typeName = getString(R.string.mag_village_data);
            hintName = getString(R.string.msg_for_example_village);
        } else {
            typeName = getString(R.string.msg_player_data);
            hintName = getString(R.string.msg_for_example_player);
        }
        
        final String[] ops = menuList.toArray(new String[0]);
        
        new AlertDialog.Builder(this).setItems(ops, (d, w) -> {

            // === 情况 A: 删除所有 (Index 2) ===
            if (w == 2) {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle(getString(R.string.title_high_energy_early_warning))
                    .setMessage(getString(R.string.msg_delete_existing_ones_in_the_list) + currentList.size() + getString(R.string.msg_indivual) + typeName + getString(R.string.msg_the_operation_cannot_be_undone))
                    .setPositiveButton(getString(R.string.btn_delete_all), (dd, ww) -> {
                        // 批量删除逻辑
                        final ProgressDialog deleting = ProgressDialog.show(MainActivity.this, getString(R.string.msg_deleting), getString(R.string.msg_cleaning_up), true);
                        final String dbPath = currentWorkingDbPath;

                        new Thread(() -> {
                            try {
                                PlayerDbManager db = new PlayerDbManager(dbPath);
                                // 遍历删除所有显示的 Key
                                for(String key : currentList) {
                                    db.deleteKey(key);
                                }
                                db.close();

                                runOnUiThread(() -> {
                                    deleting.dismiss();
                                    currentList.clear(); // 清空列表

                                    // 【核心修复】判空 adapter，防止搜索模式下闪退
                                    if (adapter != null) {
                                        adapter.notifyDataSetChanged();
                                    } else {
                                        // 如果是从搜索进入(adapter为null)，清空缓存以强制刷新
                                        invalidateListCache(dataType);
                                    }

                                    toast(getString(R.string.toast_cleared_all) + typeName);
                                });
                            } catch(final Exception e){
                                runOnUiThread(() -> { deleting.dismiss(); toast(getString(R.string.toast_delete_failed)+e);});
                            }
                        }).start();
                    })
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show();
                return;
            }

            // === 情况 B: 巨型拼图画 (Index 3, 仅地图模式) ===
            if (dataType == TYPE_MAP && w == 3) {
                showPuzzleWarningDialog(); // 调用拼图配置方法
                return;
            }

            // === 情况 C: 新建或粘贴 (Index 0 or 1) ===
            final String finalTypeName = typeName;   //忽略
            final String finalHintName = hintName;  //忽略
            final int mode = w; // 0=新建, 1=粘贴

            final EditText input = new EditText(MainActivity.this);
            input.setHint(finalHintName);

            new AlertDialog.Builder(MainActivity.this)
                .setTitle(getString(R.string.title_new) + finalTypeName + " Key")
                .setView(input)
                .setPositiveButton(getString(R.string.btn_create), (dd, ww) -> {
                    final String newKey = input.getText().toString();
                    if(newKey.isEmpty()){ toast(getString(R.string.toast_key_cannot_be_empty)); return;}

                    final String jsonContent;
                    if (mode == 0) jsonContent = "{}";
                    else {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (!cm.hasPrimaryClip()) {toast(getString(R.string.toast_clipboard_empty)); return;}
                        jsonContent = cm.getPrimaryClip().getItemAt(0).getText().toString(); //暂时忽略
                    }

                    final String dbPath = currentWorkingDbPath;

                    new Thread(() -> {
                        try {
                            JsonObject jo = JsonParser.parseString(jsonContent).getAsJsonObject();
                            byte[] bytes = BedrockParser.writeToBytes(jo);

                            PlayerDbManager localDb = new PlayerDbManager(dbPath);
                            localDb.writeSpecificKey(newKey, bytes);
                            localDb.close();

                            runOnUiThread(() -> {
                                // 如果列表中没有这个key才添加，防止显示重复
                                if (!currentList.contains(newKey)) {
                                    currentList.add(newKey);

                                    // 【核心修复】判空 adapter
                                    if (adapter != null) {
                                        adapter.notifyDataSetChanged();
                                    } else {
                                        invalidateListCache(dataType);
                                    }
                                }
                                toast(finalTypeName + getString(R.string.toast_yes_created));
                            });
                        } catch(final Exception e){
                            runOnUiThread(() -> toast(getString(R.string.toast_creation_failed)+e));
                        }
                    }).start();
                }).show();
        }).show();
    }
    
    // [重命名玩家 Key] 逻辑：读取旧 -> 写入新 -> 删旧
    private void renamePlayerKey(final String oldKey, final Runnable onSuccess) {
        final EditText input = new EditText(this);
        input.setText(oldKey);
        
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_rename_key))
            .setMessage(getString(R.string.msg_move_data_to_new_key_and_delete_old_key))
            .setView(input)
            .setPositiveButton(getString(R.string.title_rename), (d, w) -> {
                final String newKey = input.getText().toString().trim();
                if(newKey.isEmpty() || newKey.equals(oldKey)) return;

                final String dbPath = currentWorkingDbPath; // 传参 final

                new Thread(() -> {
                    try {
                        PlayerDbManager db = new PlayerDbManager(dbPath);
                        // 1. 读旧
                        byte[] data = db.readSpecificKey(oldKey);
                        // 2. 写新
                        db.writeSpecificKey(newKey, data);
                        // 3. 删旧
                        db.deleteKey(oldKey);
                        db.close();

                        runOnUiThread(onSuccess); // 回调刷新
                    } catch(final Exception e) {
                        runOnUiThread(() -> toast(getString(R.string.toast_rename_failed_colon)+e));
                    }
                }).start();
            })
            .setNegativeButton(getString(R.string.btn_cancel), null).show();
    }

// 升级版地图列表 (带缓存极速版)
    private void showMapListDialog() {
        // 1. 【缓存检查】如果内存里已经有列表了，直接显示，不读盘！
        if (cacheMapList != null) {
            showListDialogInternal(cacheMapList, TYPE_MAP);
            return;
        }

        final ProgressDialog loading = ProgressDialog.show(this, getString(R.string.msg_scanning), getString(R.string.msg_search_map_data), true);
        
        new Thread(() -> {
            try {
                PlayerDbManager db = new PlayerDbManager(currentWorkingDbPath);
                List<String> rawList = db.listMapKeys();
                db.close();

                // 存入缓存
                cacheMapList = rawList;

                runOnUiThread(() -> {
                    loading.dismiss();
                    showListDialogInternal(cacheMapList, TYPE_MAP);
                });
            } catch(final Exception e){
                runOnUiThread(() -> {loading.dismiss(); toast(e.toString());});
            }
        }).start();
    }

// 新增：巨型数组专用管理对话框
// 新增：巨型数组分页查看/编辑对话框
    private void showArrayPaginationDialog(final String key, final JsonArray jsonArray) {
        final int size = jsonArray.size();
        
        // 1. 创建布局容器
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 0);
        
        final TextView tvInfo = new TextView(this);
        tvInfo.setText(getString(R.string.text_huge_array_detected, size));
        tvInfo.setTextSize(16);
        layout.addView(tvInfo);
        
        // 范围选择框
        final EditText etStart = new EditText(this); 
        etStart.setHint(getString(R.string.text_starting_position_eg_0));
        etStart.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etStart.setText("0");
        layout.addView(etStart);
        
        final EditText etCount = new EditText(this); 
        etCount.setHint(getString(R.string.text_number_of_views_recommended_200));
        etCount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etCount.setText("200");   //忽略
        layout.addView(etCount);

        // 【核心修改】在此处添加图片导入按钮
        if (key.equals("colors") && size >= 16384) {
            Button btnImport = new Button(this);
            btnImport.setText(getString(R.string.text_import_images_to_generate_map_images));
            btnImport.setOnClickListener(v -> {
                // 暂存目标数组
                currentTargetMapArray = jsonArray;
                // 启动 SAF 选择器
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                startActivityForResult(intent, REQUEST_PICK_IMAGE_FOR_MAP);
            });
            layout.addView(btnImport);
        }

        // 2. 构建并显示弹窗
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_manage_array) + key)
            .setView(layout) // 把刚才加了一堆东西的 layout 塞进去
            .setPositiveButton(getString(R.string.btn_view_and_edit_clips), (d, w) -> {
                try {
                    int start = Integer.parseInt(etStart.getText().toString());
                    int count = Integer.parseInt(etCount.getText().toString());
                    if (start < 0) start = 0;
                    if (count > 2000) count = 2000;
                    if (start + count > size) count = size - start;

                    showSubArrayEditDialog(key, jsonArray, start, count);
                } catch (NumberFormatException e) {
                    toast(getString(R.string.toast_please_enter_valid_numbers));
                }
            })
            .setNeutralButton(getString(R.string.btn_full_export), (d, w) -> new Thread(() -> {
                final String content = jsonArray.toString();
                runOnUiThread(() -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Array", content));
                    toast(getString(R.string.toast_exported) + content.length() + getString(R.string.toast_characters_to_clipboard));
                });
            }).start())
            .setNegativeButton(getString(R.string.btn_close), null)
            .show();
    }

    // 分页后的实际编辑窗口
    private void showSubArrayEditDialog(final String key, final JsonArray originalArray, final int start, final int count) { //保留key
        // 截取数据构建显示的字符串
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for(int i = 0; i < count; i++) {
            sb.append(originalArray.get(start + i).toString());
            if(i < count - 1) sb.append(", ");
        }
        sb.append("]");
        
        final EditText input = new EditText(this);
        input.setText(sb.toString());
        // input.setHorizontallyScrolling(false); // 允许自动换行
        
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_edit_left_bracket) + start + " ~ " + (start + count - 1) + ")")
            .setView(input)
            .setMessage(getString(R.string.msg_comma_separated_format_click_save_after_modification))
            .setPositiveButton(getString(R.string.btn_save_clip), (d, w) -> {
                try {
                    JsonElement parsed = JsonParser.parseString(input.getText().toString());
                    if (parsed.isJsonArray()) {
                        JsonArray newPart = parsed.getAsJsonArray();
                        if (newPart.size() != count) {
                            toast(getString(R.string.toast_quantities_are_inconsistent_must_be_retained) + count + getString(R.string.toast_element_which_is_currently) + newPart.size());
                            return; // 禁止修改数组长度（否则索引会对不上）
                        }
                        // 替换原数组中的内容
                        for(int i = 0; i < count; i++) {
                            originalArray.set(start + i, newPart.get(i));
                        }

                        // 刷新
                        if(nbtAdapter!=null) nbtAdapter.notifyDataSetChanged();
                        if(nbtTreeAdapter!=null) nbtTreeAdapter.notifyDataSetChanged();
                        toast(getString(R.string.toast_clip_saved));
                    }
                } catch (Exception e) {
                    toast(getString(R.string.err_format) + e.getMessage());
                }
            })
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show();
    }

// 3. 村庄数据列表 (修复 Final 问题)
    private void showVillageListDialog() {
        if (cacheVillageList != null) {
            showListDialogInternal(cacheVillageList, TYPE_VILLAGE);
            return;
        }
        final ProgressDialog loading = ProgressDialog.show(this, getString(R.string.msg_scanning), getString(R.string.msg_search_village_data), true);
        
        new Thread(() -> {
            try {
                PlayerDbManager db = new PlayerDbManager(currentWorkingDbPath);
                // 1. 获取并转存为 final 变量
                final List<String> rawList = db.listVillageKeys();
                db.close();

                cacheVillageList = rawList;

                runOnUiThread(() -> {
                    loading.dismiss();
                    // 2. 这里的 rawList 已经是 final 的了
                    showListDialogInternal(rawList, TYPE_VILLAGE);
                });
            } catch(final Exception e){
                runOnUiThread(() -> {loading.dismiss(); toast(e.toString());});
            }
        }).start();
    }

// 【简化版】拼图警告弹窗（无倒计时，直接确认）
private void showPuzzleWarningDialog() {
    new AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_warning_inventory))
        .setMessage(getString(R.string.msg_warning_puzzle))
        .setPositiveButton(getString(R.string.btn_i_understand_continue), (d, w) -> showPuzzleConfigDialog())
        .setNegativeButton(getString(R.string.btn_cancel), null)
        .show();
}

// 拼图尺寸选择弹窗 (国际化修复版)
    private void showPuzzleConfigDialog() {
        // 动态构建选项数组
        final String[] options = {
            getString(R.string.puzzle_opt_default),
            getString(R.string.puzzle_opt_2x2),
            getString(R.string.puzzle_opt_3x3),
            getString(R.string.puzzle_opt_custom)
        };
        
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_choose_puzzle_size))
            .setItems(options, (d, w) -> {
                if (w == 0) { puzzleRows=1; puzzleCols=1; pickPuzzleImage(); }
                else if (w == 1) { puzzleRows=2; puzzleCols=2; pickPuzzleImage(); }
                else if (w == 2) { puzzleRows=3; puzzleCols=3; pickPuzzleImage(); }
                else if (w == 3) {
                    // 自定义输入布局
                    LinearLayout layout = new LinearLayout(MainActivity.this);
                    layout.setOrientation(LinearLayout.HORIZONTAL);
                    layout.setPadding(30, 20, 30, 0);

                    final EditText etW = new EditText(MainActivity.this);
                    etW.setHint(getString(R.string.hint_width_col));
                    etW.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                    etW.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

                    final EditText etH = new EditText(MainActivity.this);
                    etH.setHint(getString(R.string.hint_height_row));
                    etH.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                    etH.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

                    layout.addView(etW); layout.addView(etH);

                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle(getString(R.string.title_input_dimensions))
                        .setView(layout)
                        .setPositiveButton(getString(R.string.btn_confirm), (dd, ww) -> {
                            try {
                                String strW = etW.getText().toString();
                                String strH = etH.getText().toString();

                                if (strW.isEmpty() || strH.isEmpty()) {
                                    toast(getString(R.string.toast_please_enter_size)); return;
                                }

                                puzzleCols = Integer.parseInt(strW);
                                puzzleRows = Integer.parseInt(strH);

                                // 提示大尺寸
                                if (puzzleCols * puzzleRows > 100) {
                                    // 使用 String.format 格式化字符串
                                    String warning = getString(R.string.toast_huge_size_warning, (puzzleCols * puzzleRows));
                                    toast(warning);
                                }

                                pickPuzzleImage();

                            } catch(Exception e) {
                                toast(getString(R.string.toast_input_error) + e.getMessage());
                            }
                        }).show();
                }
            }).show();
    }

    private void pickPuzzleImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK_IMAGE_FOR_PUZZLE);
    }

// 核心引擎：生成拼图（禁人塔式单链嵌套 - Slot 0递归放盒子）
private void processPuzzleMap(final Uri imageUri) {
    etWorldName.getText().toString();  //忽略
    final ProgressDialog pd = ProgressDialog.show(this, getString(R.string.msg_in_preparation), getString(R.string.msg_analyzing_backpack), true);

    new Thread(() -> {
        try {
            // 1. 准备 DB 对象
            final PlayerDbManager db = new PlayerDbManager(currentWorkingDbPath);
            byte[] playerData = db.readLocalPlayer();
            List<String> mapKeys = db.listMapKeys();

            // 2. 解析背包
            JsonObject playerRoot = BedrockParser.parseBytes(playerData);
            if (playerRoot == null) playerRoot = new JsonObject();
            JsonObject invWrapper = playerRoot.getAsJsonObject("Inventory");
            JsonArray inventory;
            if (invWrapper == null) {
                invWrapper = new JsonObject();
                invWrapper.addProperty("t", 9);
                invWrapper.addProperty("itemType", 10);
                inventory = new JsonArray();
                invWrapper.add("v", inventory);
                playerRoot.add("Inventory", invWrapper);
            } else {
                inventory = invWrapper.getAsJsonArray("v");
            }

// 3. 【修复】正确处理空槽位检测（识别空气/空物品）
boolean[] occupiedSlots = new boolean[36]; // 0-35

if (inventory != null) {
for (JsonElement item : inventory) {
    try {
        if (!item.isJsonObject()) continue;
        JsonObject itemObj = item.getAsJsonObject();

        // 解包装获取实际内容
        JsonObject itemContent = itemObj;
        if (itemObj.has("v") && itemObj.get("v").isJsonObject()) {
            itemContent = itemObj.getAsJsonObject("v");
        }

        // 读取Slot位置
        byte slot = -1;
        if (itemContent.has("Slot")) {
            JsonElement slotEl = itemContent.get("Slot");
            if (slotEl.isJsonObject()) {
                slot = slotEl.getAsJsonObject().get("v").getAsByte();
            } else if (slotEl.isJsonPrimitive()) {
                slot = slotEl.getAsByte();
            }
        }
        if (slot < 0 || slot >= 36) continue;

        // 【关键】判断这个槽位是否真的被占用了（不是空气）
        boolean isRealItem = true;

        // 检查Name是否为空或空气
        if (itemContent.has("Name")) {
            JsonElement nameEl = itemContent.get("Name");
            String name = "";
            if (nameEl.isJsonObject() && nameEl.getAsJsonObject().has("v")) {
                name = nameEl.getAsJsonObject().get("v").getAsString();
            } else if (nameEl.isJsonPrimitive()) {
                name = nameEl.getAsString();
            }
            if (name.isEmpty() || name.equals("minecraft:air")) {
                isRealItem = false;
            }
        }

        // 检查Count是否为0
        if (isRealItem && itemContent.has("Count")) {
            JsonElement countEl = itemContent.get("Count");
            int count = 1; // 默认至少1个
            if (countEl.isJsonObject() && countEl.getAsJsonObject().has("v")) {
                count = countEl.getAsJsonObject().get("v").getAsInt();
            } else if (countEl.isJsonPrimitive()) {
                count = countEl.getAsInt();
            }
            if (count <= 0) {
                isRealItem = false;
            }
        }

        // 只有真正的物品才标记为占用
        if (isRealItem) {
            occupiedSlots[slot] = true;
        }
    } catch (Exception ignored) {}
}
}

// 寻找第一个空位
byte freeSlot = -1;
for (byte i = 0; i < 36; i++) {
if (!occupiedSlots[i]) {
    freeSlot = i;
    break;
}
}

if (freeSlot == -1) {
db.close();
throw new Exception(getString(R.string.msg_backpack_is_full_requires_at_least_1_open_space));
}

final byte finalFreeSlot = freeSlot;

            // 4. 计算 ID
            long maxMapId = -1;
            for (String key : mapKeys) {
                String idStr = key.replace("map_", "");
                try {
                    long id = Long.parseLong(idStr);
                    if (id > maxMapId) maxMapId = id;
                } catch (Exception ignored) {}
            }
            final long startMapId = (maxMapId < 0) ? 0 : (maxMapId + 1);

            // 5. 图片处理
            java.io.InputStream is = getContentResolver().openInputStream(imageUri);
            android.graphics.Bitmap rawSrc = android.graphics.BitmapFactory.decodeStream(is);
            is.close(); //暂时忽略

            if (rawSrc.getWidth() < puzzleCols || rawSrc.getHeight() < puzzleRows) {
                int newW = Math.max(rawSrc.getWidth(), puzzleCols);
                int newH = Math.max(rawSrc.getHeight(), puzzleRows);
                android.graphics.Bitmap scaledSrc = android.graphics.Bitmap.createScaledBitmap(rawSrc, newW, newH, true);
                rawSrc.recycle();
                rawSrc = scaledSrc;
            }
            final android.graphics.Bitmap src = rawSrc;

            final int cellW = src.getWidth() / puzzleCols;
            final int cellH = src.getHeight() / puzzleRows;
            final int totalMaps = puzzleRows * puzzleCols;

            final java.util.concurrent.ConcurrentHashMap<Integer, JsonObject> itemsMap =
                    new java.util.concurrent.ConcurrentHashMap<>();

            runOnUiThread(() -> pd.setMessage(getString(R.string.msg_high_performance_mode_starts) + totalMaps + getString(R.string.msg_map_write_to_database)));

            int cores = Runtime.getRuntime().availableProcessors();
            int threadCount = useMultiThreading ? Math.min(cores + 1, 8) : 1;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            final CountDownLatch latch = new CountDownLatch(totalMaps);
            final AtomicReference<Throwable> errorRef =
                    new AtomicReference<>();

            int globalIndex = 0;
            for (int r = 0; r < puzzleRows; r++) {
                for (int c = 0; c < puzzleCols; c++) {
                    final int fr = r;
                    final int fc = c;
                    final int fIndex = globalIndex;
                    final long fMapId = startMapId + fIndex;

                    executor.submit(() -> {
                        try {
                            if (errorRef.get() != null) return;

                            android.graphics.Bitmap chunk = android.graphics.Bitmap.createBitmap(
                                src, fc * cellW, fr * cellH, cellW, cellH);
                            android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(
                                chunk, 128, 128, true);

                            int[] pixels = new int[128 * 128];
                            scaled.getPixels(pixels, 0, 128, 0, 0, 128, 128);

                            // RGBA 直写
                            JsonArray colorsArr = new JsonArray();
                            for (int p : pixels) {
                                colorsArr.add((byte) Color.red(p));
                                colorsArr.add((byte) Color.green(p));
                                colorsArr.add((byte) Color.blue(p));
                                colorsArr.add((byte) 255); // Alpha
                            }

                            JsonObject mapContent = new JsonObject();
                            JsonObject colorsTag = new JsonObject();
                            colorsTag.addProperty("t", 7);
                            colorsTag.add("v", colorsArr);
                            mapContent.add("colors", colorsTag);

                            JsonObject decTag = new JsonObject();
                            decTag.addProperty("t", 9);
                            decTag.addProperty("itemType", 10);
                            decTag.add("v", new JsonArray());
                            mapContent.add("decorations", decTag);

                            mapContent.add("dimension", wrapTag(1, (byte) 0));
                            mapContent.add("fullyExplored", wrapTag(1, (byte) 1));
                            mapContent.add("mapLocked", wrapTag(1, (byte) 1));
                            mapContent.add("locked", wrapTag(1, (byte) 1));
                            mapContent.add("unlimitedTracking", wrapTag(1, (byte) 0));
                            mapContent.add("scale", wrapTag(1, (byte) 0));
                            mapContent.add("width", wrapTag(2, (short) 128));
                            mapContent.add("height", wrapTag(2, (short) 128));
                            mapContent.add("xCenter", wrapTag(3, 0));
                            mapContent.add("zCenter", wrapTag(3, 0));
                            mapContent.add("mapId", wrapTag(4, fMapId));
                            mapContent.add("parentMapId", wrapTag(4, -1L));

                            byte[] mapBytes = BedrockParser.writeToBytes(mapContent);

                            synchronized (db) {
                                db.writeSpecificKey("map_" + fMapId, mapBytes);
                            }

                            JsonObject itemContent = new JsonObject();
                            itemContent.add("Name", wrapTag(8, "minecraft:filled_map"));
                            itemContent.add("Count", wrapTag(1, (byte) 1));
                            itemContent.add("Damage", wrapTag(2, (short) 0));
                            itemContent.add("WasPickedUp", wrapTag(1, (byte) 0));

                            JsonObject tagTag = new JsonObject();
                            tagTag.addProperty("t", 10);
                            JsonObject tagContent = new JsonObject();
                            tagContent.add("map_uuid", wrapTag(4, fMapId));
                            tagContent.add("map_name_index", wrapTag(3, (int) fMapId));

                            JsonObject displayTag = new JsonObject();
                            displayTag.addProperty("t", 10);
                            JsonObject displayContent = new JsonObject();
                            displayContent.add("Name", wrapTag(8, "Puzzle " + (fr + 1) + "-" + (fc + 1)));
                            displayTag.add("v", displayContent);
                            tagContent.add("display", displayTag);
                            tagTag.add("v", tagContent);
                            itemContent.add("tag", tagTag);

                            itemsMap.put(fIndex, itemContent);

                            chunk.recycle();
                            scaled.recycle();
                        } catch (Throwable e) {
                            errorRef.set(e);
                        } finally {
                            latch.countDown();
                        }
                    });
                    globalIndex++;
                }
            }

            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new Exception(getString(R.string.msg_interrupted));
            }
            executor.shutdown();

            if (errorRef.get() != null) {
                throw new Exception(getString(R.string.msg_processing_error_maybe_the_image_is_too_large) + ": " + errorRef.get().getMessage());
            }

            src.recycle();

            // 【核心修改】禁人塔式单链嵌套装箱
            final int mapsPerLayer = 26;
            final int totalLayers = (int) Math.ceil((double) totalMaps / mapsPerLayer);

            // 从最底层开始构建（反向）
            JsonObject currentContainer = null;

            for (int layer = totalLayers - 1; layer >= 0; layer--) {
                int startIdx = layer * mapsPerLayer;
                int endIdx = Math.min(startIdx + mapsPerLayer, totalMaps);
                int mapsInThisLayer = endIdx - startIdx;

                // 创建这一层的盒子
                JsonObject boxItem = new JsonObject();
                boxItem.add("Name", wrapTag(8, "minecraft:undyed_shulker_box"));
                boxItem.add("Count", wrapTag(1, (byte) 1));
                boxItem.add("Damage", wrapTag(2, (short) 0));
                boxItem.add("WasPickedUp", wrapTag(1, (byte) 0));

                // Block标签
                JsonObject blockTag = new JsonObject();
                blockTag.addProperty("t", 10);
                JsonObject blockContent = new JsonObject();
                blockContent.add("name", wrapTag(8, "minecraft:undyed_shulker_box"));
                JsonObject statesTag = new JsonObject();
                statesTag.addProperty("t", 10);
                statesTag.add("v", new JsonObject());
                blockContent.add("states", statesTag);
                blockContent.add("version", wrapTag(3, 18168865));
                blockTag.add("v", blockContent);
                boxItem.add("Block", blockTag);

                // tag标签（包含Items）
                JsonObject tagTag = new JsonObject();
                tagTag.addProperty("t", 10);
                JsonObject tagContent = new JsonObject();

                JsonObject itemsListTag = new JsonObject();
                itemsListTag.addProperty("t", 9);
                itemsListTag.addProperty("itemType", 10);
                JsonArray itemsArr = new JsonArray();

                // Slot 0: 如果有下一层，放入子盒子
                if (currentContainer != null) {
                    JsonObject childBox = currentContainer.deepCopy();
                    childBox.add("Slot", wrapTag(1, (byte) 0));
                    itemsArr.add(childBox);
                }

                // Slot 1-26: 放入地图
                for (int i = 0; i < mapsInThisLayer; i++) {
                    int mapIdx = startIdx + i;
                    JsonObject mapItem = itemsMap.get(mapIdx);
                    if (mapItem == null) {
                        throw new Exception(getString(R.string.msg_slice_missing) + mapIdx);
                    }
                    mapItem = mapItem.deepCopy();
                    mapItem.add("Slot", wrapTag(1, (byte) (i + 1)));
                    itemsArr.add(mapItem);
                }

                itemsListTag.add("v", itemsArr);
                tagContent.add("Items", itemsListTag);

                // 显示名称
                JsonObject displayTag = new JsonObject();
                displayTag.addProperty("t", 10);
                JsonObject displayContent = new JsonObject();
                if (layer == 0) {
                    displayContent.add("Name", wrapTag(8, "Puzzle Set (" + totalMaps + " maps, " + totalLayers + " layers)"));
                } else {
                    displayContent.add("Name", wrapTag(8, "Layer " + layer + " →"));
                }
                displayTag.add("v", displayContent);
                tagContent.add("display", displayTag);

                tagTag.add("v", tagContent);
                boxItem.add("tag", tagTag);

                currentContainer = boxItem;
            }

// 最终的顶层盒子放入找到的空位（不再是硬编码的Slot 0）
            if (currentContainer == null) {
                throw new Exception("装箱失败：容器为空");
            }
            JsonObject finalBox = currentContainer.deepCopy();
finalBox.add("Slot", wrapTag(1, finalFreeSlot)); // 使用找到的空位
inventory.add(finalBox); //暂时忽略


// 保存玩家数据
byte[] newPlayerData = BedrockParser.writeToBytes(playerRoot);
db.writeLocalPlayer(newPlayerData);
db.close();

// 【添加这行】声明为 final，供内部类使用
final JsonObject finalPlayerRoot = playerRoot;

final long finalStartId = startMapId;   //忽略
final int finalTotalLayers = totalLayers;   //忽略

runOnUiThread(() -> {
    pd.dismiss();
    String msg = getString(R.string.msg_success) + totalMaps + getString(R.string.msg_maps_have_been_packed) +
            getString(R.string.msg_nesting_depth) + finalTotalLayers + getString(R.string.msg_layer) +
            getString(R.string.msg_backpack_only_1_slot) +
            getString(R.string.msg_start_id)+ finalStartId + "+\n\n" +
            getString(R.string.open_the_box_in_the_game_slot_0_is_the_next_layer_of_boxes);
    toast(msg);

    cacheMapList = null;
    isEditingPlayer = true;
    currentTargetKey = "~local_player";
    // 【修改这里】使用 final 变量
    rootNbtData = finalPlayerRoot;
    nbtDataCache.put("~local_player", finalPlayerRoot);
    navigationStack.clear();
    pathStack.clear();
    scrollPositionStack.clear();
    updateAdapter(rootNbtData);
    if (tvCurrentPath != null)
        tvCurrentPath.setText(getString(R.string.text_player_data_has_been_modified));
});

        } catch (final Exception e) {
            Log.e(TAG, "Map generation failed", e);
            final String fullStack = getFullStackTrace(e);
            runOnUiThread(() -> {
                pd.dismiss();
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle(getString(R.string.title_operation_failed))
                    .setMessage(fullStack)
                    .setPositiveButton(getString(R.string.btn_close), null)
                    .show();
            });
        }
    }).start();
}

    // 辅助方法：快速创建 {t:?, v:?} 对象
    private JsonObject wrapTag(int type, Object value) {
        JsonObject tag = new JsonObject();
        tag.addProperty("t", type);
        if (value instanceof Number) tag.addProperty("v", (Number)value);
        else if (value instanceof String) tag.addProperty("v", (String)value);
        else if (value instanceof Boolean) tag.addProperty("v", (Boolean)value);
        return tag;
    }

// 【新增】智能检查数据库状态，如果未加载则自动加载，然后执行任务
    private void ensureDbLoaded(final Runnable nextTask) {
        // 1. 如果已经加载好了，直接执行
        if (currentWorkingDbPath != null && new File(currentWorkingDbPath).exists()) {
            nextTask.run();
            return;
        }

        // 2. 如果还没加载，检查有没有选存档
        final String folder = etWorldName.getText().toString();
        String hint = getString(R.string.hint_select_world);
        if (folder.isEmpty() || folder.equals(hint) || folder.contains("...")) {
            toast(getString(R.string.toast_please_click_the_blue_button_to_select_an_archive_first));
            return;
        }

        // 3. 自动触发加载，加载完后执行 task
        // 注意：这里我们需要修改一下 loadPlayerData 让它支持回调
        // 由于不想大改 loadPlayerData，我们这里用一种稍微“脏”一点但有效的方法：
        // 我们魔改一下 loadPlayerData 的结束部分？
        
        // 不行，最稳妥的还是重载 loadPlayerData。
        // 请按下面的指示修改 loadPlayerData 的结尾。
        
        loadPlayerData(folder, nextTask);
    }

// 【修改】重置当前会话状态
    private void resetCurrentSession() {
        currentWorkingDbPath = null;
        currentWorkingFileOrDir = null;
        isEditingPlayer = false; 
        currentTargetKey = "~local_player";
        rootNbtData = null;
        
        navigationStack.clear();
        pathStack.clear();
        scrollPositionStack.clear();
        nbtDataCache.clear();
        // 【新增】清空多任务会话缓存
        sessionCacheMap.clear();

        
        // 【新增】清空列表缓存
        cacheMapList = null;
        cacheVillageList = null;
        cachePlayerList = null;
        
        if (nbtAdapter != null) {
            nbtAdapter = new NbtAdapter(this, new JsonObject());
            nbtListView.setAdapter(nbtAdapter);
        }
        if (nbtTreeAdapter != null) {
            // 如果用了树状图，也清空
            nbtTreeAdapter = new NbtTreeAdapter(this, null);
            nbtListView.setAdapter(nbtTreeAdapter);
        }
        
        if (tvCurrentPath != null) {
            tvCurrentPath.setText(getString(R.string.text_loading_new_save));
        }
        
        // 5. (可选) 关闭侧边栏，防止误触
        View sidebar = findViewById(R.id.custom_sidebar_container);
        if (sidebar != null) sidebar.setVisibility(View.GONE);
        
        TextView tvName = findViewById(R.id.tv_app_title);
        TextView tvSeed = findViewById(R.id.tv_info_seed);
        if (tvName != null) tvName.setText(getString(R.string.app_name));
        if (tvSeed != null) tvSeed.setVisibility(View.GONE);
    }

// 通用列表弹窗构建器 (完美交互版：点击垃圾桶显隐复选框)
    private void showListDialogInternal(final List<String> dataList, final int type) {
        final String baseTitle;
        if (type == TYPE_MAP) baseTitle = getString(R.string.msg_map_data);
        else if (type == TYPE_VILLAGE) baseTitle = getString(R.string.mag_village_data);
        else baseTitle = getString(R.string.label_player_data);
        
        if (dataList.isEmpty()) {
            toast(getString(R.string.toast_currently_none) + baseTitle + getString(R.string.title_you_can_click_more_pperations_to_create_a_new));
        }

        final FuzzyArrayAdapter adapter = new FuzzyArrayAdapter(MainActivity.this, dataList);
        final ListView listView = new ListView(MainActivity.this);
        listView.setAdapter(adapter);

        TextView emptyView = new TextView(MainActivity.this);
        emptyView.setText(getString(R.string.text_no_data_or_no_search_results_found));
        emptyView.setGravity(android.view.Gravity.CENTER);
        
        android.widget.FrameLayout container = new android.widget.FrameLayout(MainActivity.this);
        container.addView(listView);
        container.addView(emptyView);
        listView.setEmptyView(emptyView);

        LayoutInflater inflater = LayoutInflater.from(this);
        ViewGroup parent = findViewById(android.R.id.content); // 获取当前窗口内容视图
        View customTitleView = inflater.inflate(R.layout.dialog_title_with_search, parent, false);
        
        final TextView tvTitle = customTitleView.findViewById(R.id.tv_dialog_title);
        final Button btnSearch = customTitleView.findViewById(R.id.btn_search_toggle);
        final View searchContainer = customTitleView.findViewById(R.id.search_container);
        final EditText etSearch = customTitleView.findViewById(R.id.et_search_input);
        final android.view.View btnClear = customTitleView.findViewById(R.id.btn_clear_search);
        final Button btnBatchDelete = customTitleView.findViewById(R.id.btn_batch_delete);

        tvTitle.setText(String.format(baseTitle, dataList.size()));
        
        // === [修改] 批量删除按钮点击事件 ===
        btnBatchDelete.setOnClickListener(v -> {
            // 1. 如果当前不是选择模式 -> 开启选择模式
            if (!adapter.isSelectionMode()) {
                adapter.setSelectionMode(true);
                toast(getString(R.string.toast_please_check_the_items_you_want_to_delete));
                return;
            }

            // 2. 如果已经是选择模式 -> 检查是否有选中项
            final List<String> toDelete = adapter.getSelectedList();

            // 如果没选任何东西，再次点击垃圾桶视为“取消/退出模式”
            if (toDelete.isEmpty()) {
                adapter.setSelectionMode(false);
                toast(getString(R.string.toast_exited_from_batch_management));
                return;
            }

            // 3. 有选中项 -> 弹出确认删除
            new AlertDialog.Builder(MainActivity.this)
                .setTitle(getString(R.string.title_batch_delete))
                .setMessage(getString(R.string.msg_are_you_sure_you_want_to_delete_the_selected) + toDelete.size() + getString(R.string.msg_item_this_action_is_irreversible))
                .setPositiveButton(getString(R.string.btn_delete), (d, w) -> {
                    final ProgressDialog pd = ProgressDialog.show(MainActivity.this, getString(R.string.msg_processing_ing), getString(R.string.msg_are_deleting), true);
                    final String dbPath = currentWorkingDbPath;

                    new Thread(() -> {
                        try {
                            PlayerDbManager db = new PlayerDbManager(dbPath);
                            for(String key : toDelete) {
                                db.deleteKey(key);
                            }
                            db.close();

                            runOnUiThread(() -> {
                                pd.dismiss();
                                for(String key : toDelete) {
                                    adapter.remove(key);
                                    dataList.remove(key);
                                }
                                // 删除完成后，自动退出选择模式
                                adapter.setSelectionMode(false);

                                tvTitle.setText(String.format(baseTitle, dataList.size()));
                                toast(getString(R.string.toast_successfully_deleted) + toDelete.size() + getString(R.string.toast_deleted_item));
                            });
                        } catch(Exception e) {
                            final String errorMsg = e.toString();
                            runOnUiThread(() -> { pd.dismiss(); toast(getString(R.string.toast_delete_failed)+errorMsg); });
                        }
                    }).start();
                })
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show();
        });

        // 搜索按钮
        btnSearch.setOnClickListener(v -> {
            if (searchContainer.getVisibility() == View.VISIBLE) {
                searchContainer.setVisibility(View.GONE);
                etSearch.setText("");
                adapter.getFilter().filter(null);
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
            } else {
                searchContainer.setVisibility(View.VISIBLE);
                etSearch.setFocusable(true);
                etSearch.setFocusableInTouchMode(true);
                etSearch.requestFocus();
                etSearch.postDelayed(() -> {
                    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.toggleSoftInput(android.view.inputmethod.InputMethodManager.SHOW_FORCED, 0);
                }, 200);
            }
        });
        
        btnClear.setOnClickListener(v -> etSearch.setText(""));
        
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.getFilter().filter(s);
                if (s.length() > 0) btnClear.setVisibility(View.VISIBLE);   //忽略
                else btnClear.setVisibility(View.GONE);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        final AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
            .setCustomTitle(customTitleView)
            .setNeutralButton(getString(R.string.btn_more_actions), (d, which) -> showPlayerRootMenu(dataList, null, type))
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .setView(container)
            .create();

        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
                window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
        });

        // 列表点击事件 (为了更好的体验，在选择模式下，点击文字也相当于勾选)
        listView.setOnItemClickListener((p, v, pos, id) -> {
            String selectedKey = adapter.getItem(pos);

            // 【核心优化】如果是选择模式，点击行 = 勾选/取消勾选
            if (adapter.isSelectionMode()) {
                CheckBox cb = v.findViewById(R.id.cb_item_select);
                cb.performClick(); // 模拟点击 CheckBox
                return; // 不进入编辑
            }

            // 正常模式：加载编辑
            loadSpecificPlayer(selectedKey);
            dialog.dismiss();
            View sidebar = findViewById(R.id.custom_sidebar_container);
            if (sidebar != null) sidebar.setVisibility(View.GONE);
        });

        // 列表长按 (保留)
        listView.setOnItemLongClickListener((p, v, pos, id) -> {
            // 如果在选择模式，长按不触发菜单，避免冲突
            if (adapter.isSelectionMode()) return false;

            final String targetKey = adapter.getItem(pos);
            String[] ops = {getString(R.string.msg_list_long_press_copy_data_json), getString(R.string.msg_list_long_press_paste_overlay), getString(R.string.msg_list_long_press_rename), getString(R.string.msg_list_long_press_delete)};

            new AlertDialog.Builder(MainActivity.this).setTitle(getString(R.string.title_manage_prefix) + targetKey)
                .setItems(ops, (d, w) -> {
                    if (w == 0) copyPlayerJson(targetKey);
                    else if (w == 1) pastePlayerJson(targetKey, () -> { loadSpecificPlayer(targetKey); dialog.dismiss(); });
                    else if (w == 2) renamePlayerKey(targetKey, () -> {
                        invalidateListCache(type);
                        dialog.dismiss();
                        if(type==TYPE_MAP) showMapListDialog();
                        else if(type==TYPE_VILLAGE) showVillageListDialog();
                        else showMultiPlayerDialog();
                        toast(getString(R.string.toast_rename_successful));
                    });
                    else if (w == 3) deletePlayerKey(targetKey, () -> {
                        adapter.remove(targetKey);
                        dataList.remove(targetKey);
                        tvTitle.setText(String.format(baseTitle, dataList.size()));
                    });
                }).show();
            return true;
        });
        
        dialog.show();
    }

// === 自定义模糊搜索适配器 (支持多选+模式切换版) ===
    private static class FuzzyArrayAdapter extends BaseAdapter implements android.widget.Filterable {
        private final List<String> originalList;
        private List<String> displayedList;
        private final LayoutInflater inflater;
        
        public java.util.HashSet<String> selectedItems = new java.util.HashSet<>();
        
        // 【新增】是否处于选择模式
        private boolean isSelectionMode = false;

        public FuzzyArrayAdapter(Context context, List<String> data) {
            this.originalList = new ArrayList<>(data);
            this.displayedList = new ArrayList<>(data);
            this.inflater = LayoutInflater.from(context);
        }
        
        // 【新增】切换模式方法
        public void setSelectionMode(boolean enabled) {
            this.isSelectionMode = enabled;
            if (!enabled) selectedItems.clear(); // 退出模式时清空已选
            notifyDataSetChanged();
        }
        
        public boolean isSelectionMode() { return isSelectionMode; }

        @Override
        public int getCount() { return displayedList.size(); }

        @Override
        public String getItem(int position) { return displayedList.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_checkbox, parent, false);
            }
            
            final String currentKey = displayedList.get(position);
            
            TextView tv = convertView.findViewById(R.id.tv_item_name);
            final android.widget.CheckBox cb = convertView.findViewById(R.id.cb_item_select);
            
            tv.setText(currentKey);
            
            // 【核心修改】根据模式决定复选框是否显示
            if (isSelectionMode) {
                cb.setVisibility(View.VISIBLE);
                // 绑定状态
                cb.setOnCheckedChangeListener(null);
                cb.setChecked(selectedItems.contains(currentKey));
                
                // 整个 item 点击时也能切换勾选（提升体验）
                // 但这里需要注意和 ListView 的 onItemClick 冲突问题
                // 通常我们在 ListView onItemClick 里处理逻辑，这里只负责显示
            } else {
                cb.setVisibility(View.GONE);
            }
            
            // 复选框点击事件
            cb.setOnClickListener(v -> {
                if (cb.isChecked()) selectedItems.add(currentKey);
                else selectedItems.remove(currentKey);
            });

            return convertView;
        }

        public void remove(String item) {
            originalList.remove(item);
            displayedList.remove(item);
            selectedItems.remove(item);
            notifyDataSetChanged();
        }

        public List<String> getSelectedList() {
            return new ArrayList<>(selectedItems);
        }

    @Override
        public android.widget.Filter getFilter() {
            return new android.widget.Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    FilterResults results = new FilterResults();
                    List<String> filtered = new ArrayList<>();
                    if (constraint == null || constraint.length() == 0) {  //忽略
                        filtered.addAll(originalList);
                    } else {
                        String pattern = constraint.toString().toLowerCase().trim();
                        for (String item : originalList) {
                            if (item.toLowerCase().contains(pattern)) filtered.add(item);
                        }
                    }
                    results.values = filtered;
                    results.count = filtered.size();
                    return results;
                }

                @Override
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    displayedList = (List<String>) results.values;
                    notifyDataSetChanged();
                }
            };
        }
    }

// NBT 编辑器内的即时搜索弹窗
    private void showEditorSearchDialog() {
        final EditText etSearch = new EditText(this);
        etSearch.setHint(getString(R.string.text_search_key_or_translate));
        etSearch.setSingleLine(true);
        
        // 创建一个包含 EditText 的容器，设置边距
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(50, 20, 50, 0);
        etSearch.setLayoutParams(params);
        container.addView(etSearch);

        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_search_nbt_data))
            .setView(container)
            .setPositiveButton(getString(R.string.btn_close), null)
            .setNeutralButton(getString(R.string.btn_clear_filter), (d, w) -> {
                if (nbtAdapter != null) nbtAdapter.filter(null);
            })
            .create();

        // 实时监听输入
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (nbtAdapter != null) {
                    nbtAdapter.filter(s.toString());
                }
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        
        // 自动弹键盘
        dialog.setOnShowListener(d -> {
            etSearch.setFocusable(true);
            etSearch.setFocusableInTouchMode(true);
            etSearch.requestFocus();
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.toggleSoftInput(android.view.inputmethod.InputMethodManager.SHOW_FORCED, 0);
        });

        dialog.show();
    }

// 【补全缺失的方法】将 NBT List 转换为 Map 格式以便展示
    private JsonObject convertListToMap(JsonObject listData) {
        JsonObject fakeMap = new JsonObject();
        JsonArray arr = listData.get("v").getAsJsonArray();
        int itemType = listData.get("itemType").getAsInt();
        
        for (int i = 0; i < arr.size(); i++) {
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("t", itemType);
            wrapper.add("v", arr.get(i));
            // 使用数字索引 "0", "1", "2" 作为 Key
            fakeMap.add(String.valueOf(i), wrapper);
        }
        return fakeMap;
    }
// 【核心新增】根据路径列表，重建列表模式的导航栈 (树 -> 列表 同步)
    private void syncListModeFromPath(List<String> path) {
        // 1. 重置到根
        navigationStack.clear();
        pathStack.clear();
        scrollPositionStack.clear();
        currentListData = rootNbtData; // 从根开始

        if (path == null || path.isEmpty()) return;

        // 2. 模拟逐层进入
        // currentListData 始终持有当前层级的内容（Map结构）
        JsonObject currentPtr = rootNbtData;

        try {
            for (String key : path) {
                // 确保当前层有这个 key
                if (!currentPtr.has(key)) break;

                // 获取下一层的数据包装 {t, v}
                JsonObject itemWrapper = currentPtr.getAsJsonObject(key);
                int type = itemWrapper.get("t").getAsInt();

                // 准备进入下一层：先把当前层压栈
                navigationStack.push(currentPtr);
                pathStack.push(key);
                scrollPositionStack.push(0); // 默认滚到顶部

                // 解析下一层的内容 (剥壳)
                JsonElement v = itemWrapper.get("v");
                
                if (type == 10) { 
                    // Compound: 直接取 v (它是 JsonObject)
                    currentPtr = v.getAsJsonObject();
                } 
                else if (type == 9) {
                    // List: 需要转成 FakeMap ("0":{}, "1":{}...)
                    // 复用我们之前写的 convertListToMap
                    currentPtr = convertListToMap(itemWrapper);
                } 
                else {
                    // 如果路径里混入了非容器（理论上不可能），停止
                    break;
                }
            }
            // 循环结束，currentPtr 指向的就是目标层级的数据
            currentListData = currentPtr;
            
        } catch (Exception e) {
            // 如果同步过程中出错（比如结构变了），就停在出错的那一层，不至于崩
            toast(getString(R.string.toast_view_sync_partially_interrupted) + e.getMessage());
        }
    }

// 【新增】按名称打开 NBT 的弹窗逻辑
    private void showOpenByKeyDialog() {
        // 1. 构建布局 (代码构建，复刻截图样式)
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 20);

        // 提示文本
        TextView tvTip = new TextView(this);
        tvTip.setText(getString(R.string.text_open_nbt_by_name_hint));
        tvTip.setTextSize(14);
        tvTip.setTextColor(android.graphics.Color.DKGRAY);
        layout.addView(tvTip);

        // 标题 "数据名"
        TextView tvLabel = new TextView(this);
        tvLabel.setText(getString(R.string.text_data_name));
        tvLabel.setTextColor(getResources().getColor(android.R.color.holo_blue_light));
        tvLabel.setPadding(0, 30, 0, 10);
        layout.addView(tvLabel);

        // 输入框
        final EditText etKey = new EditText(this);
        // etKey.setHint("输入 Key...");
        layout.addView(etKey);

        // Hex 复选框
        final android.widget.CheckBox cbHex = new android.widget.CheckBox(this);
        cbHex.setText(getString(R.string.title_hex_16_hexadecimal_mode));
        layout.addView(cbHex);

        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_open_nbt_by_name))
            .setView(layout)
            .setPositiveButton(getString(R.string.btn_confirm), (d, w) -> {
                String input = etKey.getText().toString();
                if (input.isEmpty()) { toast(getString(R.string.toast_input_cannot_be_empty)); return; }

                boolean isHex = cbHex.isChecked();
                loadCustomKey(input, isHex);
            })
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show();
    }

    // 加载自定义 Key 的核心逻辑
    private void loadCustomKey(final String inputStr, final boolean isHex) {
        final ProgressDialog pd = ProgressDialog.show(this, getString(R.string.msg_read), getString(R.string.msg_querying), true);
        
        new Thread(() -> {
            try {
                PlayerDbManager db = new PlayerDbManager(currentWorkingDbPath);

                byte[] keyBytes;
                if (isHex) {
                    // Hex 模式：将字符串转为 byte[]
                    try {
                        keyBytes = hexStringToByteArray(inputStr);
                    } catch (Exception e) {
                        throw new Exception(getString(R.string.msg_hex_format_error));
                    }
                } else {
                    // 普通文本模式：UTF-8
                    keyBytes = inputStr.getBytes(StandardCharsets.UTF_8);
                }

                // 读取数据
                byte[] data = db.readRawKey(keyBytes);
                db.close();

                // 解析 NBT
                final JsonObject json = BedrockParser.parseBytes(data);

                runOnUiThread(() -> {
                    pd.dismiss();

                    // 更新界面
                    isEditingPlayer = true;
                    // 注意：如果是 Hex 模式，保存时可能需要特殊处理，
                    // 但为了简单，这里暂时把 Key 设为输入值。
                    // 如果是 Hex，保存逻辑可能会因为 writeSpecificKey 把 Hex 字符串当成普通字符串写回去而导致 Key 变动。
                    // 这是一个已知限制，普通文本 Key 不受影响。
                    currentTargetKey = inputStr;

                    rootNbtData = json;
                    navigationStack.clear();
                    pathStack.clear();
                    scrollPositionStack.clear();
                    updateAdapter(rootNbtData);

                    if(tvCurrentPath != null) {
                        String type = isHex ? "[Hex] " : "";
                        tvCurrentPath.setText(getString(R.string.title_current, type + inputStr));
                    }
                    toast(getString(R.string.toast_loading_successfully));
                });

            } catch (final Exception e) {
                runOnUiThread(() -> { pd.dismiss(); toast(getString(R.string.err_load_failed) + e.getMessage()); });
            }
        }).start();
    }

// 【新增】数据库修复询问弹窗
    private void showDbRepairConfirmDialog(final String dbPath, final String folderName, final Runnable originalSuccessTask) {
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_database_corruption))
            .setMessage(getString(R.string.msg_corrupt_or_missing_leveldb_data_file_detected) +
                        getString(R.string.msg_have_you_tried_a_brute_force_repair) +
                        getString(R.string.msg_warning_repair_process_may_discard_unreadable_data_blocks))
            .setPositiveButton(getString(R.string.btn_try_to_fix), (d, w) -> performDbRepair(dbPath, folderName, originalSuccessTask))
            .setNegativeButton(getString(R.string.btn_cancel), (d, w) -> {
                toast(getString(R.string.toast_operation_canceled_please_check_archive_integrity));
                // 可以在这里做一些清理工作，比如把那个坏的 working_db 删了
            })
            .setCancelable(false) // 禁止点击外部关闭，必须选一个
            .show();
    }

    // 【新增】执行修复并自动重试
    private void performDbRepair(final String dbPath, final String folderName, final Runnable originalSuccessTask) {  //保留originalSuccessTask
        final ProgressDialog pd = ProgressDialog.show(this, getString(R.string.msg_under_repair), getString(R.string.msg_trying_to_rebuild_data_index), true);
        
        new Thread(() -> {
            try {
                // 调用静态修复方法
                PlayerDbManager.tryRepair(dbPath);

                runOnUiThread(() -> {
                    pd.dismiss();
                    toast(getString(R.string.toast_repair_completed_trying_to_load_again));
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
                    retryLoadAfterRepair(dbPath, folderName);
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    toast(getString(R.string.toast_repair_failed) + e.getMessage());
                });
            }
        }).start();
    }
    
    // 【新增】修复成功后的重试逻辑 (复用 loadPlayerData 的后半部分)
    private void retryLoadAfterRepair(final String dbPath, final String folderName) {
        final ProgressDialog loading = ProgressDialog.show(this, getString(R.string.msg_retrying), getString(R.string.msg_reading_repaired_data), true);
        new Thread(() -> {
            try {
                // 此时 dbPath 已经是修好的了，直接读
                PlayerDbManager dbManager = new PlayerDbManager(dbPath);
                byte[] data = dbManager.readLocalPlayer();
                dbManager.close();

                final JsonObject playerDataObj = BedrockParser.parseBytes(data);

                runOnUiThread(() -> {
                    loading.dismiss();
                    isEditingPlayer = true;
                    navigationStack.clear(); pathStack.clear(); scrollPositionStack.clear();
                    rootNbtData = playerDataObj;

                    // 存入缓存
                    nbtDataCache.put("~local_player", rootNbtData);

                    updateAdapter(rootNbtData);
                    toast(getString(R.string.toast_player_loaded_success));
                    if (tvCurrentPath != null)
                        tvCurrentPath.setText(getString(R.string.path_editing_player, folderName));
                });
            } catch (final Exception e) {
                runOnUiThread(() -> { loading.dismiss(); toast(getString(R.string.toast_retry_failed)+ e);});
            }
        }).start();
    }

// 【新增】创建 .nomedia 文件，防止系统媒体服务扫描导致崩溃
    private void createNoMedia() {
        try {
            File bridgeDir = new File(BRIDGE_ROOT);
            if (!bridgeDir.exists()) bridgeDir.mkdirs();
            
            File noMedia = new File(bridgeDir, ".nomedia");
            if (!noMedia.exists()) {
                noMedia.createNewFile();
            }
        } catch (Exception e) {
            // 忽略错误，这个文件不关键，只是为了防崩
        }
    }

// 【新增】给指定目录贴上“隐身符”，防止媒体扫描
    private void ensureNoMedia(File dir) {
        try {
            if (!dir.exists()) dir.mkdirs();
            File noMedia = new File(dir, ".nomedia");
            if (!noMedia.exists()) {
                noMedia.createNewFile();
            }
        } catch (Exception e) {
            // 忽略错误，不影响主流程
        }
    }

    // 工具：Hex 字符串转 Byte 数组
    private byte[] hexStringToByteArray(String s) {
        // 去除空格
        s = s.replace(" ", "");
        int len = s.length();
        if (len % 2 != 0) throw new IllegalArgumentException(getString(R.string.msg_the_length_must_be_an_even_number));
        
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                 + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

// 4. 全局系统数据列表 (硬编码的特殊 Key)
    private void showGlobalDataDialog() {
        // 定义 Key 和 描述的映射关系
        final String[][] globalKeys = {
            {"LevelChunkMetaDataDictionary", getString(R.string.msg_global_system_data_list_metadata)},
            {"BiomeData", getString(R.string.msg_global_system_data_list_community)},
            {"Overworld", getString(R.string.msg_global_system_data_list_main_world_data)},
            {"Nether", getString(R.string.msg_global_system_data_list_iower_bound_data)},
            {"TheEnd", getString(R.string.msg_global_system_data_list_end_data)},
            {"Villages", getString(R.string.msg_global_system_data_list_village)},
            {"Portals", getString(R.string.msg_global_system_data_list_portal)},
            {"AutonomousEntities", getString(R.string.msg_global_system_data_list_autonoous_entity)},
            {"schedulerWT", getString(R.string.msg_global_system_data_list_scheduler)},
            {"Scoreboard", getString(R.string.msg_global_system_data_list_scoreboard_data)},
            {"Mobevents", getString(R.string.msg_global_system_data_list_biological_events)},
            {"PositionTrackDBLastID", getString(R.string.msg_global_system_data_list_last_targeting_id)},
            {"dimension0", getString(R.string.msg_global_system_data_list_main_world_0)},
            {"dimension1", getString(R.string.msg_global_system_data_list_nether_1)},
            {"dimension2", getString(R.string.msg_global_system_data_list_end_2)}
        };

        // 构建显示列表 (Key + 描述)
        final List<String> displayList = new ArrayList<>();
        final List<String> realKeys = new ArrayList<>();
        
        for (String[] pair : globalKeys) {
            // 格式：Scoreboard (计分板数据)
            displayList.add(pair[0] + "\n" + pair[1]);
            realKeys.add(pair[0]);
        }

        // 构建 UI
        final ListView listView = new ListView(this);
        // 使用 simple_list_item_2 可以显示两行文字 (Title + Subtitle) 但需要自定义 adapter
        // 这里为了简单，我们用 simple_list_item_1 配合换行符
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, displayList) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                // 小优化：让显示更好看一点，把字体改小
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextSize(14); // 稍微小一点
                view.setPadding(30, 20, 30, 20); // 增加间距
                return view;
            }
        };
        listView.setAdapter(adapter);

        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(getString(R.string.msg_global_system_data) + displayList.size() + ")")
            .setView(listView)
            .setNegativeButton(getString(R.string.btn_close), null)
            .create();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            String key = realKeys.get(position);

            // 尝试加载
            // 这里的 loadSpecificPlayer 其实是通用的 loadByKey，直接复用
            loadSpecificPlayer(key);

            dialog.dismiss();
        });
        
        // 可选：长按复制 Key
        listView.setOnItemLongClickListener((p, v, pos, id) -> {
            String key = realKeys.get(pos);
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Key", key));
            toast(getString(R.string.toast_key_copied) + key);
            return true;
        });

        dialog.show();
    }

// 创建新的全局数据
    private void createNewGlobalData(final String key) {
        // 创建一个空的 Compound NBT
        final JsonObject emptyNbt = new JsonObject();
        // 如果你需要针对特定 Key 初始化特定结构（比如 Scoreboard 需要特定的头），可以在这里 switch-case
        // 目前暂时初始化为空对象 {}
        
        // 伪造加载过程
        currentTargetKey = key;
        isEditingPlayer = true;
        rootNbtData = emptyNbt;
        
        navigationStack.clear(); 
        pathStack.clear(); 
        scrollPositionStack.clear();
        
        updateAdapter(rootNbtData);
        
        if(tvCurrentPath!=null) tvCurrentPath.setText(getString(R.string.title_current, key + getString(R.string.text_newly_created)));
        
        // 只有当你点击保存时，才会真正写入数据库
        toast(getString(R.string.toast_empty_data_has_been_created_please_edit_and_save));
    }

// 【修改版】更新标题栏显示世界信息
    private void updateWorldInfoCard(JsonObject rootJson) {
        TextView tvName = findViewById(R.id.tv_app_title);
        TextView tvSeed = findViewById(R.id.tv_info_seed);
        
        if (tvName == null || tvSeed == null) return;
        
        if (rootJson == null) {
            // 如果数据为空，恢复默认
            tvName.setText(getString(R.string.app_name));
            tvSeed.setVisibility(View.GONE);
            return;
        }

        try {
            // 尝试提取 LevelName
            String nameStr = null;
            if (rootJson.has("LevelName")) {
                JsonObject obj = rootJson.getAsJsonObject("LevelName");
                if (obj.has("v")) nameStr = obj.get("v").getAsString();
            }

            // 尝试提取 RandomSeed
            String seedStr = null;
            if (rootJson.has("RandomSeed")) {
                JsonObject obj = rootJson.getAsJsonObject("RandomSeed");
                if (obj.has("v")) seedStr = obj.get("v").getAsString();
            }

            // 如果读到了名字，就更新标题
            if (nameStr != null && !nameStr.isEmpty()) {
                tvName.setText(nameStr);
            } else {
                tvName.setText(getString(R.string.app_name));
            }

            // 如果读到了种子，显示小字
            if (seedStr != null && !seedStr.isEmpty()) {
                tvSeed.setText(getString(R.string.key_seed_display, seedStr));
                tvSeed.setVisibility(View.VISIBLE);
            } else {
                tvSeed.setVisibility(View.GONE);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error loading world info", e);
            // 出错时恢复默认
            tvName.setText(getString(R.string.app_name));
            tvSeed.setVisibility(View.GONE);
        }
    }

// 【新增】读取存档真实名称 (从 levelname.txt)
    private String getWorldRealName(String folderName) {
        String fullPath = currentWorldsPath + folderName + "/levelname.txt";
        File file = new File(fullPath);

        // 1. 尝试原生读取
        if (file.exists() && file.canRead()) {
            try {
                FileInputStream fis = new FileInputStream(file);
                BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
                String name = reader.readLine();
                fis.close();
                if (name != null && !name.trim().isEmpty()) return name;
            } catch (Exception e) {
                Log.w(TAG, "Failed to read levelname.txt: " + e.getMessage());
            }
        }

        // 2. 尝试 Shizuku 读取 (针对 Android/data)
        if (checkShizukuAvailable()) {
            try {
                // 使用 cat 命令读取内容
                Process p = runShizukuCmd(new String[]{"sh", "-c", "cat \"" + fullPath + "\""});
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String name = reader.readLine();
                p.waitFor();
                if (name != null && !name.trim().isEmpty()) return name;
            } catch (Exception e) {
                Log.w(TAG, "Shizuku read file failed: " + e.getMessage());
            }
        }

        // 3. 读不到就返回 null
        return null;
    }

// 【核心】智能复制引擎
    private void smartCopy(File src, File dst) throws Exception {
        if (useMultiThreading) {
            // 开启模式：使用多线程分片复制
            copyDirectoryParallel(src, dst);
        } else {
            // 关闭模式：使用传统单线程递归
            copyDirectory(src, dst);
        }
    }

// 辅助：当数据变动时清空对应缓存
    private void invalidateListCache(int type) {
        if (type == TYPE_MAP) cacheMapList = null;
        else if (type == TYPE_VILLAGE) cacheVillageList = null;
        else cachePlayerList = null;
    }

    public static String getFullStackTrace(Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }

// 1. 切走前：把当前编辑状态存入内存
    private void saveCurrentSessionToMemory() {
        if (rootNbtData == null) return;
        
        // 决定 Session 的 Key
        String sessionKey;
        if (isEditingPlayer) {
            sessionKey = currentTargetKey; // 例如 "~local_player", "map_123"
        } else {
            sessionKey = "level.dat"; // 世界文件固定 Key
        }
        
        if (sessionKey == null) return;

        // 打包状态
        EditorSession session = new EditorSession(
            rootNbtData, 
            navigationStack, 
            pathStack, 
            scrollPositionStack, 
            isEditingPlayer, 
            currentWorkingDbPath,
            currentTargetKey
        );
        
        // 存入 Map
        sessionCacheMap.put(sessionKey, session);
        // System.out.println("Session saved: " + sessionKey);
    }

    // 2. 切回来：尝试从内存恢复状态
    // 返回 true 表示恢复成功，不需要读盘了
    private boolean tryRestoreSession(String sessionKey) {
        if (sessionCacheMap.containsKey(sessionKey)) {
            EditorSession session = sessionCacheMap.get(sessionKey);
            if (session == null) {
                return false;
            }
            rootNbtData = session.data;
            // 恢复栈 (拷贝回来)
            navigationStack.clear(); navigationStack.addAll(session.navStack);
            pathStack.clear(); pathStack.addAll(session.pathStack);
            scrollPositionStack.clear(); scrollPositionStack.addAll(session.scrollStack);
            
            isEditingPlayer = session.isPlayerMode;
            currentWorkingDbPath = session.dbPath;
            currentTargetKey = session.targetKey;
            
            // 刷新 UI
            // 如果栈里有东西，说明之前在子文件夹里，需要恢复到栈顶视图
            JsonObject viewData = (!navigationStack.isEmpty()) ? navigationStack.peek() : rootNbtData;
            
            updateAdapter(viewData);
            updatePathTitle();
            
            // 恢复列表滚动位置
            if (!scrollPositionStack.isEmpty()) {
                final int pos = scrollPositionStack.peek();
                nbtListView.post(() -> nbtListView.setSelection(pos));
            }

            toast(getString(R.string.toast_unsaved_editing_session_restored));
            return true;
        }
        return false;
    }

// 【新增】编辑会话状态类 (相当于一个后台标签页)
    private static class EditorSession {
        JsonObject data;                    // NBT 数据 (包含未保存的修改)
        Stack<JsonObject> navStack;         // 导航历史
        Stack<String> pathStack;            // 路径历史
        Stack<Integer> scrollStack;         // 滚动位置
        boolean isPlayerMode;               // 是不是 DB 模式
        String dbPath;                      // DB 路径
        String targetKey;                   // 编辑的 Key
        
        // 构造函数：保存当前现场
        public EditorSession(JsonObject d, Stack<JsonObject> n, Stack<String> p, Stack<Integer> s, boolean isP, String db, String k) {
            this.data = d;
            // 必须深拷贝栈，因为主界面的栈会被 clear
            this.navStack = new Stack<>(); this.navStack.addAll(n);
            this.pathStack = new Stack<>(); this.pathStack.addAll(p);
            this.scrollStack = new Stack<>(); this.scrollStack.addAll(s);
            this.isPlayerMode = isP;
            this.dbPath = db;
            this.targetKey = k;
        }
    }

// 【简化】打开 SAF 目录选择器（AIDE+ 兼容）
private void openSafPathSelector() {
    if (android.os.Build.VERSION.SDK_INT < 21) {
        toast("SAF 需要 Android 5.0+");
        return;
    }
    
    try {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION 
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        
        startActivityForResult(intent, REQUEST_SAF_PATH);
    } catch (Exception e) {
        toast("SAF 不可用: " + e.getMessage());
    }
}
private void showAppInfoDialog() {
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    final View dialogView = getLayoutInflater().inflate(R.layout.dialog_app_info, null);
    builder.setView(dialogView);
    
    final AlertDialog dialog = builder.create();
    
    if (dialog.getWindow() != null) {
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
    }
    
    dialog.show();
    
    if (dialog.getWindow() != null) {
        android.view.WindowManager.LayoutParams params = dialog.getWindow().getAttributes();
        params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.85);
        dialog.getWindow().setAttributes(params);
    }
    
    // ===== 关于展开逻辑 =====
    final LinearLayout aboutHeader = dialogView.findViewById(R.id.layout_about_header);
    final TextView aboutContent = dialogView.findViewById(R.id.tv_about_content);
    final TextView aboutExpand = dialogView.findViewById(R.id.tv_about_expand);
    
    if (aboutContent != null) {
        String html = getString(R.string.msg_about_content_full);
        CharSequence message = android.text.Html.fromHtml(html.replace("\n", "<br>"));
        aboutContent.setText(message);
        aboutContent.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
    }
    
    if (aboutHeader != null) {
        aboutHeader.setOnClickListener(v -> {
            if (aboutContent != null && aboutContent.getVisibility() == View.GONE) {
                aboutContent.setVisibility(View.VISIBLE);
                aboutExpand.setText("▲");
            } else {
                aboutExpand.setText("▼");
            }
        });
    }
    
    // ===== 设置展开逻辑 =====
    final LinearLayout settingsHeader = dialogView.findViewById(R.id.layout_settings_header);
    final LinearLayout settingsContent = dialogView.findViewById(R.id.layout_settings_content);
    final TextView settingsExpand = dialogView.findViewById(R.id.tv_settings_expand);
    
    if (settingsHeader != null) {
        settingsHeader.setOnClickListener(v -> {
            if (settingsContent.getVisibility() == View.GONE) {
                settingsContent.setVisibility(View.VISIBLE);
                settingsExpand.setText("▲");
            } else {
                settingsContent.setVisibility(View.GONE);
                settingsExpand.setText("▼");
            }
        });
    }
    
    // ===== 四个设置项绑定 =====
    
    // 1. 多线程
    CheckBox cbMulti = dialogView.findViewById(R.id.cb_dialog_multithread);
    if (cbMulti != null) {
        cbMulti.setChecked(useMultiThreading);
        cbMulti.setOnCheckedChangeListener((buttonView, isChecked) -> {
            useMultiThreading = isChecked;
            prefs.edit().putBoolean("use_multithread", isChecked).apply();
            toast(isChecked ? getString(R.string.toast_multi_thread_acceleration_enabled)
                          : getString(R.string.toast_switched_to_single_threaded_stable_mode));
        });
    }
    
    // 2. Shizuku
    CheckBox cbShizuku = dialogView.findViewById(R.id.cb_dialog_shizuku);
    if (cbShizuku != null) {
        cbShizuku.setChecked(useShizuku);
        cbShizuku.setOnCheckedChangeListener((buttonView, isChecked) -> {
            useShizuku = isChecked;
            prefs.edit().putBoolean("use_shizuku", isChecked).apply();
            toast(isChecked ? getString(R.string.toast_shizuku_enabled) : getString(R.string.toast_shizuku_disabled));
        });
    }
    
    // 3. 日夜模式
    Button btnTheme = dialogView.findViewById(R.id.btn_dialog_theme);
    if (btnTheme != null) {
        btnTheme.setText(isNightMode ? getString(R.string.action_switch_day) : getString(R.string.action_switch_night));
        btnTheme.setOnClickListener(v -> {
            isNightMode = !isNightMode;
            prefs.edit().putBoolean("night_mode", isNightMode).apply();
            applyTheme();
            dialog.dismiss(); // 关闭弹窗重建
            recreate();
        });
    }
    
    // 4. 语言切换
    Button btnLang = dialogView.findViewById(R.id.btn_dialog_lang);
    if (btnLang != null) {
        btnLang.setText("zh".equals(currentLang) ? getString(R.string.switch_to_english) : getString(R.string.switch_to_chinese));
        btnLang.setOnClickListener(v -> {
            if ("zh".equals(currentLang)) currentLang = "en";
            else currentLang = "zh";
            prefs.edit().putString("app_language", currentLang).apply();

            // 暂存数据
            tempNbtData = rootNbtData;
            tempTargetKey = currentTargetKey;

            NbtTranslator.reset();
            dialog.dismiss();
            recreate();
        });
    }

// 【新增】调试菜单触发器（点击图标10次）
final int[] clickCount = {0};
final long[] lastClickTime = {0};
final View headerLayout = dialogView.findViewById(R.id.in_app_icon); // 确保XML中有这个ID

if (headerLayout != null) {
    headerLayout.setOnClickListener(v -> {
        long currentTime = System.currentTimeMillis();

        // 重置计数（如果超过2秒未点击）
        if (currentTime - lastClickTime[0] >5000) {
            clickCount[0] = 0;
        }
        lastClickTime[0] = currentTime;

        clickCount[0]++;

        // 显示点击进度（可选，第5次后显示提示）
        if (clickCount[0] == 5) {
            toast("再点击5次开启开发者选项...");
        }

        // 达到10次，显示调试菜单
        if (clickCount[0] >= 10) {
            clickCount[0] = 0; // 重置
            showDebugMenu(); // 显示调试菜单
        }
    });
}
}

// 【新增】统一自动填充数据模型
private static class AutocompleteEntry {
    String name;
    String namespace;
    String category;
    String id; // 【新增】纯数字ID（用于效果和附魔）
    String searchKey;
    
    AutocompleteEntry(String name, String namespace, String category, String id) {
        this.name = name;
        this.namespace = namespace;
        this.category = category;
        this.id = id;
        // 【搜索键】包含中文、namespace、数字ID
        this.searchKey = (name + " " + namespace + " " + (id != null ? id : "")).toLowerCase();
    }
    
    // 【获取填充值】效果和附魔返回数字ID，其他返回minecraft:namespace
    String getFillValue() {
        if (id != null && !id.isEmpty()) {
            return id; // 纯数字ID
        }
        return namespace; // 带minecraft:的namespace
    }
    
    @NonNull
    @Override
    public String toString() {
        return name;
    }
}

// 【新增】统一加载四类数据
private List<AutocompleteEntry> loadAutocompleteData(String dataType) {
    List<AutocompleteEntry> list = new ArrayList<>();
    String langFolder = "zh".equals(currentLang) ? "zh" : "en";
    
    try {
        switch (dataType) {
            case "item":
                loadJsonToList(list, langFolder + "/item_translation.json", "item");
                break;
            case "block":
                loadJsonToList(list, langFolder + "/block_translation.json", "block");
                break;
            case "effect":
                loadJsonToList(list, langFolder + "/potion_translation.json", "effect");
                break;
            case "enchant":
                loadJsonToList(list, langFolder + "/ench_translation.json", "enchant");
                break;
            case "all":
                // 加载所有（用于物品+方块混合场景）
                loadJsonToList(list, langFolder + "/item_translation.json", "item");
                loadJsonToList(list, langFolder + "/block_translation.json", "block");
                break;
        }
    } catch (Exception e) {
        Log.e(TAG, "Failed to load autocomplete data: " + dataType, e);
    }
    
    return list;
}

private void loadJsonToList(List<AutocompleteEntry> list, String assetPath, String category) throws Exception {
    java.io.InputStream is = getAssets().open(assetPath);
    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is));
    StringBuilder sb = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) sb.append(line);
    reader.close();
    
    com.google.gson.JsonArray array = new com.google.gson.JsonParser().parse(sb.toString()).getAsJsonArray();
    for (int i = 0; i < array.size(); i++) {
        com.google.gson.JsonObject obj = array.get(i).getAsJsonObject();
        String name = obj.has("name") ? obj.get("name").getAsString() : "";
        String namespace = obj.has("namespace") ? obj.get("namespace").getAsString() : "";
        String id = obj.has("id") ? obj.get("id").getAsString() : null; // 【提取数字ID】
        
        // 效果和附魔：不添加minecraft:前缀，保留原始namespace（如protection）
        // 其他：添加minecraft:前缀
        if (!"effect".equals(category) && !"enchant".equals(category)) {
            if (!namespace.contains(":")) {
                namespace = "minecraft:" + namespace;
            }
        }
        
        if (!namespace.isEmpty()) {
            list.add(new AutocompleteEntry(name, namespace, category, id));
        }
    }
}

// 【新增】根据上下文智能判断数据类型
private String detectAutocompleteType(String key, String parent, String grandParent) {
    // 药水效果
    if ("ActiveEffects".equals(parent) || "ActiveEffects".equals(grandParent) ||
        "MobEffects".equals(parent) || "Effects".equals(parent)) {
        return "effect";
    }
    
    
    // 附魔（支持多种键名）
    if ("ench".equals(parent) || "Enchantments".equals(parent) || 
        "StoredEnchantments".equals(parent) ||
        "ench".equals(grandParent) || "Enchantments".equals(grandParent)) {
        return "enchant";
    }
    
    // 如果是 Id 字段且在特定父级下
    if ("Id".equals(key)) {
        if ("ActiveEffects".equals(parent)) return "effect";
        if ("ench".equals(parent) || "Enchantments".equals(parent)) return "enchant";
    }
    
    // 默认：物品+方块混合
    return "all";
}

// 获取类型显示标签
private String getTypeLabel(String type) {
    return switch (type) {
        case "item" -> getString(R.string.title_item);
        case "block" -> getString(R.string.title_block);
        case "effect" -> getString(R.string.title_effect);
        case "enchant" -> getString(R.string.title_enchant);
        case "all" -> getString(R.string.title_all);
        default -> getString(R.string.title_data);
    };
}

// 【新增】统一的自动填充对话框
private void showAutocompleteDialog(final EditText targetInput, final String dataType, final String currentValue) {   //?
    // 创建布局
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(24, 16, 24, 16);
    
    // 搜索框
    final EditText etSearch = new EditText(this);
    etSearch.setHint(getString(R.string.title_search) + getTypeLabel(dataType) + "...");
    etSearch.setTextSize(14);
    layout.addView(etSearch);
    
    // 清空按钮
    Button btnClear = new Button(this);
    btnClear.setText(getString(R.string.btn_clear));
    btnClear.setTextColor(Color.parseColor("#2196F3"));
    btnClear.setBackgroundColor(Color.TRANSPARENT);
    layout.addView(btnClear);
    
    // 列表
    final ListView lvSuggestions = new ListView(this);
    lvSuggestions.setDivider(new android.graphics.drawable.ColorDrawable(0xFFEEEEEE));
    lvSuggestions.setDividerHeight(1);
    layout.addView(lvSuggestions, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 400));
    
    // 加载数据 (这是不可变的完整数据源)
    final List<AutocompleteEntry> allItems = loadAutocompleteData(dataType);
    
    // 【修复1】：传入 adapter 的 List 必须是 allItems 的副本！
    // 这样 adapter 在 clear() 和 addAll() 时，操作的是副本，不会破坏原有的 allItems
    List<AutocompleteEntry> displayList = new ArrayList<>(allItems);
    
    // 适配器
    final ArrayAdapter<AutocompleteEntry> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_list_item_2, displayList) {

        // 【修复2】：缓存 Filter 实例，避免每次 getFilter 都创建新对象导致异步线程冲突
        private Filter mFilter;

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(android.R.layout.simple_list_item_2, parent, false);
            }
            AutocompleteEntry item = getItem(position);
            TextView tv1 = convertView.findViewById(android.R.id.text1);
            TextView tv2 = convertView.findViewById(android.R.id.text2);

            // 主标题
            String label = "";
            if (item == null) return convertView;
            label = switch (item.category) {
                case "block" -> getString(R.string.title_block_label);
                case "item" -> getString(R.string.title_item_label);
                case "effect" -> getString(R.string.title_effect_label);
                case "enchant" -> getString(R.string.title_enchant_label);
                default -> label;
            };
            tv1.setText(String.format("%s %s", label, item.name));
            tv1.setTextColor(Color.parseColor("#333333"));
            tv1.setTextSize(15);

            // 副标题
            String subtitle;
            if ("effect".equals(item.category) || "enchant".equals(item.category)) {
                subtitle = "ID:" + item.id + " | " + item.namespace;
            } else {
                subtitle = item.namespace;
            }
            tv2.setText(subtitle);
            tv2.setTextColor(Color.parseColor("#666666"));
            tv2.setTextSize(12);

            return convertView;
        }

        @NonNull
        @Override
        public Filter getFilter() {
            // 【修复2】：单例模式返回 Filter
            if (mFilter == null) {
                mFilter = new Filter() {
                    @Override
                    protected FilterResults performFiltering(CharSequence constraint) {
                        FilterResults results = new FilterResults();
                        List<AutocompleteEntry> filtered = new ArrayList<>();

                        // constraint 为空时，返回完整的 allItems (这里 allItems 始终是完整的，因为没被破坏)
                        if (constraint == null || constraint.length() == 0) {   //忽略
                            filtered.addAll(allItems);
                        } else {
                            String pattern = constraint.toString().toLowerCase().trim();

                            // 永远在完整的 allItems 里查找
                            for (AutocompleteEntry item : allItems) {
                                boolean match = false;

                                if (item.namespace.toLowerCase().equals(pattern) ||
                                        (item.id != null && item.id.equals(pattern))) {
                                    match = true;
                                } else if (item.name.toLowerCase().contains(pattern)) {
                                    match = true;
                                } else if (item.namespace.toLowerCase().contains(pattern)) {
                                    match = true;
                                } else if ((item.category.equals("effect") || item.category.equals("enchant"))
                                        && item.id != null && item.id.contains(pattern)) {
                                    match = true;
                                }

                                if (match) filtered.add(item);
                            }
                        }

                        results.values = filtered;
                        results.count = filtered.size();
                        return results;
                    }

                    @Override
                    protected void publishResults(CharSequence constraint, FilterResults results) {
                        clear(); // 这里清空的是 displayList 副本
                        if (results.values != null) {
                            addAll((List<AutocompleteEntry>) results.values); // 这里加回去的也是 displayList 副本
                        }
                        notifyDataSetChanged();
                    }
                };
            }
            return mFilter;
        }
    };
    
    lvSuggestions.setAdapter(adapter);
    
    btnClear.setOnClickListener(v -> {
        etSearch.setText("");
        // 只要把文本设空，TextWatcher 会自动触发过滤，Filter 就会把完整数据塞回来
    });
    
    // 创建对话框
    final AlertDialog dialog = new AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_choose) + getTypeLabel(dataType))
        .setView(layout)
        .setNegativeButton(getString(R.string.btn_cancel), null)
        .create();
    
    if (dialog.getWindow() != null) {
        dialog.getWindow().setLayout(
            (int)(getResources().getDisplayMetrics().widthPixels * 0.9),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        );
    }
    
    dialog.show();
    
    // 搜索监听
    etSearch.addTextChangedListener(new android.text.TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            adapter.getFilter().filter(s.toString());
        }
        
        @Override
        public void afterTextChanged(android.text.Editable s) {}
    });
    
    // 列表点击
    lvSuggestions.setOnItemClickListener((parent, view, position, id) -> {
        AutocompleteEntry selected = adapter.getItem(position);
        if (selected != null) {
            targetInput.setText(selected.getFillValue());
            targetInput.setSelection(targetInput.getText().length());
        }
        dialog.dismiss();
    });
}

// 【新增】显示调试菜单
private void showDebugMenu() {
    final String[] options = {
        "💥 崩溃测试（模拟应用崩溃）",
        "📋 显示日志",
        "📄 复制日志到剪贴板",
        "💾 导出日志到文件"
    };
    
    new AlertDialog.Builder(this)
        .setTitle("🔧 开发者调试菜单")
        .setItems(options, (dialog, which) -> {
            switch (which) {
                case 0: // 崩溃测试
                    performCrashTest();
                    break;
                case 1: // 显示日志
                    showLogViewer();
                    break;
                case 2: // 复制日志
                    copyLogsToClipboard();
                    break;
                case 3: // 导出日志
                    exportLogsToFile();
                    break;
            }
        })
        .setNegativeButton("关闭", null)
        .show();
}

// 1. 崩溃测试
private void performCrashTest() {
    new AlertDialog.Builder(this)
        .setTitle("⚠️ 确认")
        .setMessage("即将模拟一次应用崩溃，用于测试崩溃处理机制。\n\n是否继续？")
        .setPositiveButton("立即崩溃", (d, w) -> {
            // 延迟1秒后抛出异常，让对话框先关闭
            new android.os.Handler().postDelayed(() -> {
                throw new RuntimeException("【测试】手动触发的崩溃 - 用于验证 CrashHandler 是否正常工作");
            }, 1000);
        })
        .setNegativeButton("取消", null)
        .show();
}

// 2. 显示日志
private void showLogViewer() {
    StringBuilder logs = new StringBuilder();
    logs.append("===== 应用日志 =====\n\n");
    
    try {
        // 读取崩溃日志目录
        File crashDir = new File(getExternalFilesDir(null), "CrashLogs");
        if (crashDir.exists() && crashDir.listFiles() != null) {
            File[] files = crashDir.listFiles();
            if (files != null && files.length > 0) {
                // 读取最新的日志
                Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
                
                File latest = files[0];
                logs.append("最新崩溃日志: ").append(latest.getName()).append("\n\n");
                
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader(latest));
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null && lineCount < 50) { // 只显示前50行
                    logs.append(line).append("\n");
                    lineCount++;
                }
                reader.close();
                
                if (lineCount >= 50) {
                    logs.append("\n... (日志过长，只显示前50行)");
                }
            } else {
                logs.append("暂无崩溃日志");
            }
        } else {
            logs.append("日志目录不存在");
        }
    } catch (Exception e) {
        logs.append("读取日志失败: ").append(e.getMessage());
    }
    
    // 显示在可滚动的对话框中
    final ScrollView scrollView = new ScrollView(this);
    final TextView tvLog = new TextView(this);
    tvLog.setText(logs.toString());
    tvLog.setTextSize(12);
    tvLog.setPadding(20, 20, 20, 20);
    tvLog.setTypeface(android.graphics.Typeface.MONOSPACE); // 等宽字体
    
    // 设置布局参数
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT);
    tvLog.setLayoutParams(params);
    
    scrollView.addView(tvLog);
    
    // 设置 ScrollView 的高度限制
    scrollView.setLayoutParams(new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        800 // 最大高度800像素
    ));
    
    new AlertDialog.Builder(this)
        .setTitle("📋 日志查看器")
        .setView(scrollView)
        .setPositiveButton("复制", (d, w) -> copyLogsToClipboard())
        .setNegativeButton("关闭", null)
        .show();
}

// 3. 复制日志到剪贴板
private void copyLogsToClipboard() {
    try {
        StringBuilder allLogs = new StringBuilder();
        
        // 读取所有日志文件
        File crashDir = new File(getExternalFilesDir(null), "CrashLogs");
        if (crashDir.exists() && crashDir.listFiles() != null) {
            File[] files = crashDir.listFiles();
            if (files == null || files.length == 0) {
                return;
            }
            Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
            
            for (File file : files) {
                allLogs.append("===== ").append(file.getName()).append(" =====\n");
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader(file));
                String line;
                while ((line = reader.readLine()) != null) {
                    allLogs.append(line).append("\n");
                }
                reader.close();
                allLogs.append("\n\n");
            }
        }
        
        if (allLogs.length() == 0) {   //忽略
            toast("没有日志可复制");
            return;
        }
        
        android.content.ClipboardManager cm =
            (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("Logs", allLogs.toString());
        cm.setPrimaryClip(clip);
        toast("日志已复制到剪贴板");
        
    } catch (Exception e) {
        toast("复制失败: " + e.getMessage());
    }
}

// 4. 导出日志到文件
private void exportLogsToFile() {
    new Thread(() -> {
        try {
            // 源目录
            File crashDir = new File(getExternalFilesDir(null), "CrashLogs");
            File[] files = crashDir.listFiles();
            if (!crashDir.exists() || files == null || files.length == 0) {
                runOnUiThread(() -> toast("没有日志可导出"));
                return;
            }

            // 目标文件（导出到 Download）
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(new Date());
            File exportFile = new File("/storage/emulated/0/Download/NbtEditor_Logs_" + timeStamp + ".txt");

            java.io.FileWriter writer = new java.io.FileWriter(exportFile);

            // 【修正】使用字符串拼接，而不是链式 append
            writer.write("===== NBT Editor 日志导出 =====\n");
            writer.write("导出时间: " + timeStamp + "\n\n");

            files = crashDir.listFiles();
            if (files == null || files.length == 0) {
                return;
            }
            Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

            for (File file : files) {
                // 【修正】字符串拼接
                writer.write("===== " + file.getName() + " =====\n");

                BufferedReader reader = new BufferedReader(
                        new java.io.FileReader(file));
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.write(line + "\n"); // 【修正】使用 + 拼接
                }
                reader.close();
                writer.write("\n\n");
            }

            writer.close();

            final String path = exportFile.getAbsolutePath();
            runOnUiThread(() -> toast("日志已导出到: " + path));

        } catch (Exception e) {
            final String error = e.getMessage();
            runOnUiThread(() -> toast("导出失败: " + error));
        }
    }).start();
}
}
