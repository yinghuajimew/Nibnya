package yhjmew.minecraft.nbteditor.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import yhjmew.minecraft.nbteditor.AppLogger
import yhjmew.minecraft.nbteditor.R
import yhjmew.minecraft.nbteditor.BedrockParser
import yhjmew.minecraft.nbteditor.NbtTranslator
import yhjmew.minecraft.nbteditor.NbtTranslator.getString
import yhjmew.minecraft.nbteditor.PlayerDbManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 地图画 & 巨型拼图生成 ViewModel
 */
class MapArtViewModel : ViewModel() {

    var worldVM: WorldViewModel? = null

    // ============================================
    // 状态
    // ============================================
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    data class SlotInfo(
        val slot: Int,
        val name: String,
        val count: Int,
        val isOccupied: Boolean
    )

    data class InventorySlotInfo(
        val mainSlots: List<SlotInfo>,
        val offhandSlot: SlotInfo?
    )

    private val _inventorySlotInfo = MutableStateFlow<InventorySlotInfo?>(null)
    val inventorySlotInfo: StateFlow<InventorySlotInfo?> = _inventorySlotInfo.asStateFlow()

    fun clearInventorySlotInfo() { _inventorySlotInfo.value = null }

    private val _progressMessage = MutableStateFlow("")
    val progressMessage: StateFlow<String> = _progressMessage.asStateFlow()

    private val _resultMessage = MutableStateFlow<String?>(null)
    val resultMessage: StateFlow<String?> = _resultMessage.asStateFlow()

    fun clearResult() { _resultMessage.value = null }

    // ============================================
    // 单张地图画生成（来自 REQUEST_PICK_IMAGE_FOR_MAP）
    // ============================================
    fun generateMapFromImage(context: Context, imageUri: Uri, targetArray: JsonArray?) {
        viewModelScope.launch(Dispatchers.IO) {
            _isGenerating.value = true
            _progressMessage.value = getString(R.string.msg_processing_ing)
            try {
                val `is` = context.contentResolver.openInputStream(imageUri)
                val original = BitmapFactory.decodeStream(`is`)
                `is`?.close()

                val targetW = 128
                val targetH = 128
                val finalBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(finalBitmap)
                canvas.drawColor(Color.WHITE)

                val origW = original.width
                val origH = original.height
                val scale = min(targetW.toFloat() / origW, targetH.toFloat() / origH)
                val newW = (origW * scale).roundToInt()
                val newH = (origH * scale).roundToInt()
                val left = (targetW - newW) / 2
                val top = (targetH - newH) / 2
                canvas.drawBitmap(original, null, Rect(left, top, left + newW, top + newH), null)

                val targetSize = 65536
                val arr = targetArray ?: JsonArray()
                while (arr.size() < targetSize) arr.add(0.toByte())
                while (arr.size() > targetSize) arr.remove(arr.size() - 1)

                var index = 0
                val pixels = IntArray(128 * 128)
                finalBitmap.getPixels(pixels, 0, 128, 0, 0, 128, 128)

                for (color in pixels) {
                    arr.set(index++, JsonPrimitive(Color.red(color).toByte()))
                    arr.set(index++, JsonPrimitive(Color.green(color).toByte()))
                    arr.set(index++, JsonPrimitive(Color.blue(color).toByte()))
                    arr.set(index++, JsonPrimitive(255.toByte()))
                }

                original.recycle()
                finalBitmap.recycle()

                _isGenerating.value = false
                _resultMessage.value = getString(R.string.toast_the_map_is_generated)
            } catch (e: Exception) {
                AppLogger.error("MapArt", "Map generation failed", e)
                _isGenerating.value = false
                _resultMessage.value = getString(R.string.msg_generation_failed, e.message)
            }
        }
    }

    fun scanInventory(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val wm = worldVM ?: return@launch
                val dbPath = wm.currentWorkingDbPath ?: return@launch
                val db = PlayerDbManager(dbPath)
                val playerData = db.readLocalPlayer()
                db.close()

                val playerRoot = BedrockParser.parseBytes(playerData) ?: JsonObject()
                val mainSlots = mutableListOf<SlotInfo>()
                val invWrapper = playerRoot.getAsJsonObject("Inventory")

                // 解析背包
                val slotMap = mutableMapOf<Int, Pair<String, Int>>()
                if (invWrapper != null) {
                    val inventory = invWrapper.getAsJsonArray("v")
                    if (inventory != null) {
                        for (item in inventory) {
                            if (!item.isJsonObject) continue
                            val itemObj = item.asJsonObject
                            var itemContent = itemObj
                            if (itemObj.has("v") && itemObj.get("v").isJsonObject)
                                itemContent = itemObj.getAsJsonObject("v")

                            val slotEl = itemContent.get("Slot")
                            val slot = if (slotEl != null) {
                                if (slotEl.isJsonObject) slotEl.asJsonObject.get("v").asInt
                                else if (slotEl.isJsonPrimitive) slotEl.asInt
                                else -1
                            } else -1
                            if (slot !in 0..35) continue

                            val nameEl = itemContent.get("Name")
                            var name = ""
                            if (nameEl != null) {
                                if (nameEl.isJsonObject && nameEl.asJsonObject.has("v"))
                                    name = nameEl.asJsonObject.get("v").asString
                                else if (nameEl.isJsonPrimitive) name = nameEl.asString
                            }
                            val countEl = itemContent.get("Count")
                            var count = 1
                            if (countEl != null) {
                                if (countEl.isJsonObject && countEl.asJsonObject.has("v"))
                                    count = countEl.asJsonObject.get("v").asInt
                                else if (countEl.isJsonPrimitive) count = countEl.asInt
                            }
                            if (name.isNotEmpty() && name != "minecraft:air" && count > 0) {
                                slotMap[slot] = Pair(name, count)
                            }
                        }
                    }
                }

                for (i in 0..35) {
                    val info = slotMap[i]
                    mainSlots.add(SlotInfo(i, info?.first ?: getString(R.string.text_slot_empty), info?.second ?: 0, info != null))
                }

                _inventorySlotInfo.value = InventorySlotInfo(mainSlots, null)
            } catch (e: Exception) {
                AppLogger.error("MapArt", "Map generation failed", e)
                _resultMessage.value = "Puzzle:SCAN_ERROR:${e.message}"
            }
        }
    }

    // ============================================
    // 巨型拼图生成
    // ============================================
    fun generatePuzzleMap(context: Context, imageUri: Uri, rows: Int, cols: Int, targetSlot: Int = -1) {
        viewModelScope.launch(Dispatchers.IO) {
            _isGenerating.value = true
            _progressMessage.value = getString(R.string.msg_analyzing_backpack)
            try {
                val wm = worldVM ?: throw Exception(getString(R.string.msg_worldvm_missing))
                val dbPath = wm.currentWorkingDbPath ?: throw Exception(getString(R.string.msg_db_not_loaded))

                val db = PlayerDbManager(dbPath)
                val playerData = db.readLocalPlayer()
                val mapKeys = db.listMapKeys().map { it }.toMutableList()

                // 解析背包
                var playerRoot = BedrockParser.parseBytes(playerData) ?: JsonObject()
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

                // 空槽位检测
                val occupiedSlots = BooleanArray(36)
                if (inventory != null) {
                    for (item in inventory) {
                        try {
                            if (!item.isJsonObject) continue
                            val itemObj = item.asJsonObject
                            var itemContent = itemObj
                            if (itemObj.has("v") && itemObj.get("v").isJsonObject)
                                itemContent = itemObj.getAsJsonObject("v")

                            var slot: Byte = -1
                            if (itemContent.has("Slot")) {
                                val slotEl = itemContent.get("Slot")
                                slot = if (slotEl.isJsonObject) slotEl.asJsonObject.get("v").asByte
                                else if (slotEl.isJsonPrimitive) slotEl.asByte
                                else -1
                            }
                            if (slot !in 0..<36) continue

                            var isRealItem = true
                            if (itemContent.has("Name")) {
                                val nameEl = itemContent.get("Name")
                                var name = ""
                                if (nameEl.isJsonObject && nameEl.asJsonObject.has("v"))
                                    name = nameEl.asJsonObject.get("v").asString
                                else if (nameEl.isJsonPrimitive) name = nameEl.asString
                                if (name.isEmpty() || name == "minecraft:air") isRealItem = false
                            }
                            if (isRealItem && itemContent.has("Count")) {
                                val countEl = itemContent.get("Count")
                                var count = 1
                                if (countEl.isJsonObject && countEl.asJsonObject.has("v"))
                                    count = countEl.asJsonObject.get("v").asInt
                                else if (countEl.isJsonPrimitive) count = countEl.asInt
                                if (count <= 0) isRealItem = false
                            }
                            if (isRealItem) occupiedSlots[slot.toInt()] = true
                        } catch (_: Exception) {}
                    }
                }

                val useSlot = if (targetSlot in 0..35) targetSlot else {
                    var found = -1
                    for (i in 0..35) if (!occupiedSlots[i]) { found = i; break }
                    if (found < 0) {
                        db.close()
                        throw Exception(getString(R.string.msg_backpack_is_full_least_1_empty_slot))
                    }
                    found
                }

                // 计算 ID
                //会和地图里预设的地图ID撞上
                var maxMapId: Long = -1
                for (key in mapKeys) {
                    try { val id = key.replace("map_", "").toLong(); if (id > maxMapId) maxMapId = id }
                    catch (_: Exception) {}
                }
                val startMapId = if (maxMapId < 0) 0 else (maxMapId + 1)

                // 图片处理
                val `is` = context.contentResolver.openInputStream(imageUri)
                var rawSrc = BitmapFactory.decodeStream(`is`)
                `is`?.close()

                if (rawSrc.width < cols || rawSrc.height < rows) {
                    val scaled = Bitmap.createScaledBitmap(rawSrc, max(rawSrc.width, cols), max(rawSrc.height, rows), true)
                    rawSrc.recycle()
                    rawSrc = scaled
                }
                val src = rawSrc
                val cellW = src.width / cols
                val cellH = src.height / rows
                val totalMaps = rows * cols

                _progressMessage.value = getString(R.string.msg_generating_maps, totalMaps)

                val itemsMap = ConcurrentHashMap<Int, JsonObject>()
                val cores = Runtime.getRuntime().availableProcessors()
                val threadCount = min(cores + 1, 8)
                val executor = Executors.newFixedThreadPool(threadCount)
                val latch = CountDownLatch(totalMaps)
                val errorRef = AtomicReference<Throwable?>()

                var globalIndex = 0
                for (r in 0..<rows) {
                    for (c in 0..<cols) {
                        val fIndex = globalIndex
                        val fMapId = startMapId + fIndex
                        executor.submit {
                            try {
                                if (errorRef.get() != null) return@submit
                                val chunk = Bitmap.createBitmap(src, c * cellW, r * cellH, cellW, cellH)
                                val scaled = Bitmap.createScaledBitmap(chunk, 128, 128, true)
                                val pixels = IntArray(128 * 128)
                                scaled.getPixels(pixels, 0, 128, 0, 0, 128, 128)
                                val colorsArr = JsonArray()
                                for (p in pixels) {
                                    colorsArr.add(Color.red(p).toByte())
                                    colorsArr.add(Color.green(p).toByte())
                                    colorsArr.add(Color.blue(p).toByte())
                                    colorsArr.add(255.toByte())
                                }
                                val mapContent = JsonObject()
                                val colorsTag = JsonObject().apply { addProperty("t", 7); add("v", colorsArr) }
                                mapContent.add("colors", colorsTag)
                                mapContent.add("decorations", JsonObject().apply { addProperty("t", 9); addProperty("itemType", 10); add("v", JsonArray()) })
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

                                val mapBytes = BedrockParser.writeToBytes(mapContent)
                                synchronized(db) { db.writeSpecificKey("map_$fMapId", mapBytes) }

                                val itemContent = JsonObject()
                                itemContent.add("Name", wrapTag(8, "minecraft:filled_map"))
                                itemContent.add("Count", wrapTag(1, 1.toByte()))
                                itemContent.add("Damage", wrapTag(2, 0.toShort()))
                                itemContent.add("WasPickedUp", wrapTag(1, 0.toByte()))
                                val tagTag = JsonObject().apply {
                                    addProperty("t", 10)
                                    val tc = JsonObject()
                                    tc.add("map_uuid", wrapTag(4, fMapId))
                                    tc.add("map_name_index", wrapTag(3, fMapId.toInt()))
                                    val dt = JsonObject().apply {
                                        addProperty("t", 10)
                                        add("v", JsonObject().apply { add("Name", wrapTag(8, "Puzzle ${r + 1}-${c + 1}")) })
                                    }
                                    tc.add("display", dt)
                                    add("v", tc)
                                }
                                itemContent.add("tag", tagTag)
                                itemsMap[fIndex] = itemContent
                                chunk.recycle()
                                scaled.recycle()
                            } catch (e: Throwable) {
                                errorRef.set(e)
                            } finally { latch.countDown() }
                        }
                        globalIndex++
                    }
                }

                latch.await()
                executor.shutdown()
                if (errorRef.get() != null) throw Exception(getString(R.string.msg_processing_error, errorRef.get()!!.message))
                src.recycle()

                // 装箱
                val mapsPerLayer = 26
                val totalLayers = ceil(totalMaps.toDouble() / mapsPerLayer).toInt()
                var currentContainer: JsonObject? = null

                for (layer in totalLayers - 1 downTo 0) {
                    val startIdx = layer * mapsPerLayer
                    val endIdx = min(startIdx + mapsPerLayer, totalMaps)
                    val mapsInThisLayer = endIdx - startIdx
                    val boxItem = JsonObject()
                    boxItem.add("Name", wrapTag(8, "minecraft:undyed_shulker_box"))
                    boxItem.add("Count", wrapTag(1, 1.toByte()))
                    boxItem.add("Damage", wrapTag(2, 0.toShort()))
                    boxItem.add("WasPickedUp", wrapTag(1, 0.toByte()))
                    val blockTag = JsonObject().apply {
                        addProperty("t", 10)
                        val bc = JsonObject()
                        bc.add("name", wrapTag(8, "minecraft:undyed_shulker_box"))
                        bc.add("states", JsonObject().apply { addProperty("t", 10); add("v", JsonObject()) })
                        bc.add("version", wrapTag(3, 18168865))//"18168865" 需要重新按照百科重新填写
                        add("v", bc)
                    }
                    boxItem.add("Block", blockTag)
                    val tagTag = JsonObject().apply {
                        addProperty("t", 10)
                        val tc = JsonObject()
                        val itemsListTag = JsonObject().apply { addProperty("t", 9); addProperty("itemType", 10) }
                        val itemsArr = JsonArray()
                        if (currentContainer != null) {
                            val childBox = currentContainer.deepCopy()
                            childBox.add("Slot", wrapTag(1, 0.toByte()))
                            itemsArr.add(childBox)
                        }
                        for (i in 0..<mapsInThisLayer) {
                            val mapIdx = startIdx + i
                            var mapItem = itemsMap[mapIdx] ?: throw Exception(getString(R.string.msg_slice_missing, mapIdx))
                            mapItem = mapItem.deepCopy()
                            mapItem.add("Slot", wrapTag(1, (i + 1).toByte()))
                            itemsArr.add(mapItem)
                        }
                        itemsListTag.add("v", itemsArr)
                        tc.add("Items", itemsListTag)
                        val dt = JsonObject().apply {
                            addProperty("t", 10)
                            add("v", JsonObject().apply { add("Name", wrapTag(8, if (layer == 0) getString(R.string.msg_puzzle_set_count, totalMaps) else getString(R.string.msg_layer_arrow, layer))) })
                        }
                        tc.add("display", dt)
                        add("v", tc)
                    }
                    boxItem.add("tag", tagTag)
                    currentContainer = boxItem
                }

                val finalBox = currentContainer ?: throw Exception(getString(R.string.msg_packing_failed))
                // 背包：先删除旧条目，包装新盒子，再插入
                if (inventory != null) {
                    var removeIndex = -1
                    for (i in 0 until inventory.size()) {
                        val item = inventory.get(i)
                        if (item.isJsonObject) {
                            val itemObj = item.asJsonObject
                            val itemContent = if (itemObj.has("v") && itemObj.get("v").isJsonObject)
                                itemObj.getAsJsonObject("v") else itemObj

                            val slotEl = itemContent.get("Slot")
                            val slot = when {
                                slotEl != null && slotEl.isJsonObject && slotEl.asJsonObject.has("v") ->
                                    slotEl.asJsonObject.get("v").asInt
                                slotEl != null && slotEl.isJsonPrimitive -> slotEl.asInt
                                else -> -1
                            }
                            if (slot == useSlot) {
                                removeIndex = i
                                break
                            }
                        }
                    }
                    if (removeIndex >= 0) inventory.remove(removeIndex)

                    finalBox.add("Slot", wrapTag(1, useSlot.toByte()))
                    inventory.add(finalBox)
                }

                //不要再把 写回 DB 这俩行忘了
                val newPlayerData = BedrockParser.writeToBytes(playerRoot)
                db.writeLocalPlayer(newPlayerData)
                //不要再把 写回 DB 这俩行忘了

// 【核心修复】回填缓存：把地图和玩家数据写入 EditorViewModel
                val evm = worldVM?.editorVM ?: return@launch
                evm.nbtDataCache["~local_player"] = playerRoot
                evm.setRawNbtData(playerRoot)

                db.close()

                _isGenerating.value = false
                _resultMessage.value = "Puzzle:SUCCESS:$totalMaps:$totalLayers:$startMapId:$useSlot"
            } catch (e: Exception) {
                AppLogger.error("MapArt", "Map generation failed", e)
                _isGenerating.value = false
                _resultMessage.value = getString(R.string.msg_puzzle_error, e.message)
            }
        }
    }

    // ============================================
    // 辅助
    // ============================================
    companion object {
        fun wrapTag(type: Int, value: Any?): JsonObject {
            val tag = JsonObject()
            tag.addProperty("t", type)
            when (value) {
                is Number -> tag.addProperty("v", value)
                is String -> tag.addProperty("v", value)
                is Boolean -> tag.addProperty("v", value)
            }
            return tag
        }
    }
}