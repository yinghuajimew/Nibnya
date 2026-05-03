package yhjmew.minecraft.nbteditor.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import yhjmew.minecraft.nbteditor.BedrockParser
import yhjmew.minecraft.nbteditor.MainActivity
import yhjmew.minecraft.nbteditor.NbtAdapter
import yhjmew.minecraft.nbteditor.NbtTreeAdapter
import java.io.File
import java.util.Stack

/**
 * 编辑器的核心 ViewModel
 * 持有 NBT 数据、导航栈、会话缓存、视图模式等全部编辑状态
 */
class EditorViewModel : ViewModel() {

    // ============================================
    // 状态（Activity 通过 collect 观察）
    // ============================================

    internal val _nbtData = MutableStateFlow<JsonObject?>(null)
    val nbtData: StateFlow<JsonObject?> = _nbtData.asStateFlow()

    private val _pathTitle = MutableStateFlow("")
    val pathTitle: StateFlow<String> = _pathTitle.asStateFlow()

    private val _isTreeMode = MutableStateFlow(false)
    val isTreeMode: StateFlow<Boolean> = _isTreeMode.asStateFlow()

    private val _isEditingPlayer = MutableStateFlow(false)
    val isEditingPlayer: StateFlow<Boolean> = _isEditingPlayer.asStateFlow()

    private val _currentTargetKey = MutableStateFlow<String?>("~local_player")
    val currentTargetKey: StateFlow<String?> = _currentTargetKey.asStateFlow()

    private val _viewMode = MutableStateFlow(0)
    val viewMode: StateFlow<Int> = _viewMode.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    // 一次性事件（如 toast）
    fun clearToast() { _toastMessage.value = null }

    // ============================================
    // 导航栈
    // ============================================
    val navigationStack = Stack<JsonObject?>()
    val pathStack = Stack<String?>()
    val scrollPositionStack = Stack<Int?>()

    var currentListData: JsonObject? = null
    var lastTreeClickPosition = -1

    // ============================================
    // 会话缓存（草稿恢复）
    // ============================================
    val sessionCacheMap: MutableMap<String?, EditorSession?> = HashMap()
    val nbtDataCache = HashMap<String?, JsonObject?>()

    // ============================================
    // Nav
    // ============================================
    fun enterFolder(key: String?, content: JsonObject?, isListMode: Boolean, currentPos: Int) {
        scrollPositionStack.push(currentPos)
        navigationStack.push(_nbtData.value)
        pathStack.push(key)
        _nbtData.value = content
        updatePathTitle()
    }

    fun goBack(): Boolean {
        if (navigationStack.isEmpty()) return false
        val parent = navigationStack.pop()
        pathStack.pop()
        _nbtData.value = parent
        updatePathTitle()
        return true
    }

    fun goBackScrollPosition(): Int? {
        return if (!scrollPositionStack.isEmpty()) scrollPositionStack.pop() else null
    }

    // ============================================
    // 路径标题
    // ============================================
    fun updatePathTitle() {
        val titleText: String
        if (_isTreeMode.value) {
            titleText = if (_isEditingPlayer.value) {
                if (_currentTargetKey.value != null && _currentTargetKey.value != "~local_player") {
                    "🌳 Tree / " + _currentTargetKey.value
                } else {
                    "🌳 Tree / Player Data"
                }
            } else {
                "🌳 Tree / World Data"
            }
        } else {
            if (pathStack.isEmpty()) {
                titleText = if (_isEditingPlayer.value) {
                    if (_currentTargetKey.value != null && _currentTargetKey.value != "~local_player") {
                        _currentTargetKey.value ?: ""
                    } else {
                        "Current: Player Data"
                    }
                } else {
                    "Current: Level.dat"
                }
            } else {
                val sb = StringBuilder("Path: ")
                for (p in pathStack) sb.append(p).append("/")
                titleText = sb.toString()
            }
        }
        _pathTitle.value = titleText
    }

    // ============================================
    // 会话管理
    // ============================================
    fun saveSession(dbPath: String?) {
        val data = _nbtData.value ?: return
        val sessionKey = if (_isEditingPlayer.value) _currentTargetKey.value else "level.dat"
            ?: return
        val session = EditorSession(
            data, navigationStack, pathStack, scrollPositionStack,
            _isEditingPlayer.value, dbPath, _currentTargetKey.value
        )
        sessionCacheMap[sessionKey] = session
    }

    fun tryRestoreSession(sessionKey: String?): Boolean {
        if (sessionCacheMap.containsKey(sessionKey)) {
            val session = sessionCacheMap[sessionKey] ?: return false
            _nbtData.value = session.data
            navigationStack.clear(); navigationStack.addAll(session.navStack)
            pathStack.clear(); pathStack.addAll(session.pathStack)
            scrollPositionStack.clear(); scrollPositionStack.addAll(session.scrollStack)
            _isEditingPlayer.value = session.isPlayerMode
            _currentTargetKey.value = session.targetKey
            updatePathTitle()
            return true
        }
        return false
    }

    fun reset() {
        _nbtData.value = null
        _isEditingPlayer.value = false
        _currentTargetKey.value = "~local_player"
        _isTreeMode.value = false
        navigationStack.clear()
        pathStack.clear()
        scrollPositionStack.clear()
        nbtDataCache.clear()
        sessionCacheMap.clear()
        currentListData = null
        _pathTitle.value = ""
    }

    // ============================================
    // 视图模式
    // ============================================
    fun setViewMode(mode: Int) { _viewMode.value = mode }
    fun toggleTreeMode() { _isTreeMode.value = !_isTreeMode.value }
    fun setEditingPlayer(editing: Boolean) { _isEditingPlayer.value = editing }
    fun setTargetKey(key: String?) { _currentTargetKey.value = key }
    internal fun setRawNbtData(json: JsonObject?) {
        _nbtData.value = json
    }

    // ============================================
    // NBT 编辑操作
    // ============================================

    fun wrapRawItem(rawItem: JsonElement?): JsonObject {
        if (rawItem == null) {
            val empty = JsonObject()
            empty.addProperty("t", 10)
            empty.add("v", JsonObject())
            return empty
        }
        if (rawItem.isJsonObject) {
            val obj = rawItem.asJsonObject
            if (obj.has("t")) return obj
            val wrapped = JsonObject()
            wrapped.addProperty("t", 10)
            wrapped.add("v", obj)
            return wrapped
        }
        val wrapped = JsonObject()
        wrapped.addProperty("t", 8)
        wrapped.addProperty("v", rawItem.toString())
        return wrapped
    }

    fun findOriginalListData(): JsonArray? {
        if (pathStack.isEmpty()) return null
        try {
            var current: JsonObject = _nbtData.value ?: return null
            for (i in 0 until pathStack.size - 1) {
                val pathKey = pathStack[i] ?: continue
                val el = current.get(pathKey) ?: return null
                if (el.isJsonObject) {
                    val obj = el.asJsonObject
                    current = if (obj.has("v") && obj.get("v").isJsonObject)
                        obj.getAsJsonObject("v") else obj
                } else return null
            }
            val listKey = pathStack.peek() ?: return null
            val listEl = current.get(listKey) ?: return null
            if (listEl.isJsonObject && listEl.asJsonObject.has("v") &&
                listEl.asJsonObject.get("v").isJsonArray
            ) {
                return listEl.asJsonObject.getAsJsonArray("v")
            }
        } catch (_: Exception) {}
        return null
    }

    fun convertListToMap(listData: JsonObject): JsonObject {
        val fakeMap = JsonObject()
        val arr = listData.get("v").asJsonArray
        val itemType = listData.get("itemType").asInt
        for (i in 0..<arr.size()) {
            val wrapper = JsonObject()
            wrapper.addProperty("t", itemType)
            wrapper.add("v", arr.get(i))
            fakeMap.add(i.toString(), wrapper)
        }
        return fakeMap
    }

    fun syncListModeFromPath(path: MutableList<String?>?) {
        navigationStack.clear()
        pathStack.clear()
        scrollPositionStack.clear()
        currentListData = _nbtData.value
        if (path.isNullOrEmpty()) return

        var currentPtr = _nbtData.value
        try {
            for (key in path) {
                if (!currentPtr!!.has(key)) break
                val itemWrapper = currentPtr.getAsJsonObject(key)
                val type = itemWrapper.get("t").asInt
                navigationStack.push(currentPtr)
                pathStack.push(key)
                scrollPositionStack.push(0)
                val v = itemWrapper.get("v")
                currentPtr = if (type == 10) v.asJsonObject
                else if (type == 9) convertListToMap(itemWrapper)
                else break
            }
            currentListData = currentPtr
        } catch (_: Exception) {}
    }

    // ============================================
    // 内部类：编辑会话
    // ============================================
    inner class EditorSession(
        var data: JsonObject?,
        n: Stack<JsonObject?>,
        p: Stack<String?>,
        s: Stack<Int?>,
        var isPlayerMode: Boolean,
        var dbPath: String?,
        var targetKey: String?
    ) {
        var navStack: Stack<JsonObject?> = Stack()
        var pathStack: Stack<String?>
        var scrollStack: Stack<Int?>

        init {
            this.navStack.addAll(n)
            this.pathStack = Stack(); this.pathStack.addAll(p)
            this.scrollStack = Stack(); this.scrollStack.addAll(s)
        }
    }
}