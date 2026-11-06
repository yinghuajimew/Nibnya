package yhjmew.nibnya.mapart

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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import yhjmew.nibnya.R
import yhjmew.nibnya.db.PlayerDbManager
import yhjmew.nibnya.nbt.BedrockParser
import yhjmew.nibnya.nbt.NbtTranslator.getString
import yhjmew.nibnya.util.AppLogger
import yhjmew.nibnya.world.WorldViewModel

/**
 * 地图画 & 巨型拼图生成 ViewModel
 */
class MapArtViewModel : ViewModel() {

    var worldVM: WorldViewModel? = null

    // ============================================
    // 拼图生成并发设置（独立于文件操作多线程）
    // ============================================
    /** 是否启用拼图生成多线程；关闭则串行（更省内存，但更慢） */
    private var puzzleMultiThread = true

    /** 并发线程数：0 = 自动 */
    private var puzzleThreadCount = 0

    fun setPuzzleMultiThread(use: Boolean) { puzzleMultiThread = use }

    fun setPuzzleThreadCount(n: Int) { puzzleThreadCount = if (n < 0) 0 else n }

    /**
     * 决定拼图生成使用的线程数。
     * - 关闭多线程 -> 1（串行）
     * - 用户指定 -> 用指定值
     * - 自动 -> 按图片数量自适应（每张地图约 520KB，需控制内存峰值）
     */
    private fun resolvePuzzleThreads(totalMaps: Int): Int {
        if (!puzzleMultiThread) return 1
        if (puzzleThreadCount > 0) return puzzleThreadCount
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            totalMaps <= 1 -> 1
            totalMaps <= 4 -> min(2, cores)
            totalMaps <= 16 -> min(3, cores)
            else -> max(2, min(cores, 4))   // 大量地图时限制并发，避免 Bitmap 堆积
        }
    }

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
                val original = decodeSampledBitmap(context, imageUri, 128, 128)
                if (original == null) throw Exception(getString(R.string.toast_image_too_large))

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
    /**
 * 生成拼图地图。
 *
 * @param crop 非空表示「自由裁剪」：先按此矩形（**原图像素坐标**）裁出那一块，
 *             调用方传 1×1，最终只出 1 张地图。
 */
fun generatePuzzleMap(
    context: Context,
    imageUri: Uri,
    rows: Int,
    cols: Int,
    targetSlot: Int = -1,
    crop: android.graphics.RectF? = null,
    /** 行列模式：-1 = 不补边（居中裁剪）；0 黑 / 1 白 / 2 透明 = 补边 */
    padColor: Int = -1
) {
        viewModelScope.launch(Dispatchers.IO) {
            _isGenerating.value = true
            // 命名前缀与盒子颜色由设置决定
            val cfg = context.getSharedPreferences("nbt_config", Context.MODE_PRIVATE)
            val prefixRaw = cfg.getString("puzzle_prefix", "")?.trim() ?: ""
            // 留空时分开回退：地图用「拼图」、盒子用「拼图画」（保持原有命名习惯）
            val mapPrefix = if (prefixRaw.isBlank()) getString(R.string.default_puzzle_map_prefix)
                            else prefixRaw
            val boxPrefix = if (prefixRaw.isBlank()) getString(R.string.default_puzzle_box_prefix)
                            else prefixRaw
            _progressMessage.value = getString(R.string.status_analyzing_backpack)
            var db: PlayerDbManager? = null
            try {
                val wm = worldVM ?: throw Exception(getString(R.string.msg_worldvm_missing))
                val dbPath = wm.currentWorkingDbPath ?: throw Exception(getString(R.string.label_sub_db_not_loaded))

                val dbManager = PlayerDbManager(dbPath)
                db = dbManager
                val playerData = dbManager.readLocalPlayer()
                val mapKeys = dbManager.listMapKeys().map { it }.toMutableList()

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
                        } catch (e: Exception) { AppLogger.warn("MapArt", "扫描背包占用槽位失败: ${e.message}") }
                    }
                }

                val useSlot = if (targetSlot in 0..35) targetSlot else {
                    var found = -1
                    for (i in 0..35) if (!occupiedSlots[i]) { found = i; break }
                    if (found < 0) {
                        dbManager.close(); db = null
                        throw Exception(getString(R.string.msg_backpack_full))
                    }
                    found
                }

                // 计算 ID
                //会和地图里预设的地图ID撞上
                var maxMapId: Long = -1
                for (key in mapKeys) {
                    try { val id = key.replace("map_", "").toLong(); if (id > maxMapId) maxMapId = id }
                    catch (e: Exception) { AppLogger.warn("MapArt", "读取已有地图 ID 失败: ${e.message}") }
                }
                val startMapId = if (maxMapId < 0) 0 else (maxMapId + 1)

                // 图片处理（降采样解码，避免大图 OOM）
                var rawSrc = decodeSampledBitmap(context, imageUri, cols * 128, rows * 128)
                if (rawSrc == null) throw Exception(getString(R.string.toast_image_too_large))

                // 自由裁剪：crop 是**原图像素坐标**，rawSrc 可能已被降采样，按比例换算后裁
                if (crop != null) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(imageUri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    val ow = bounds.outWidth
                    val oh = bounds.outHeight
                    if (ow > 0 && oh > 0) {
                        val sx = rawSrc.width.toFloat() / ow
                        val sy = rawSrc.height.toFloat() / oh
                        val l = (crop.left * sx).toInt().coerceIn(0, rawSrc.width - 1)
                        val tp = (crop.top * sy).toInt().coerceIn(0, rawSrc.height - 1)
                        val cw = (crop.width() * sx).toInt().coerceAtLeast(1)
                            .coerceAtMost(rawSrc.width - l)
                        val ch = (crop.height() * sy).toInt().coerceAtLeast(1)
                            .coerceAtMost(rawSrc.height - tp)
                        if (cw > 0 && ch > 0) {
                            val cropped = Bitmap.createBitmap(rawSrc, l, tp, cw, ch)
                            if (cropped !== rawSrc) rawSrc.recycle()
                            rawSrc = cropped
                        }
                    }
                }

                // ⚠️ 每张地图都是 128x128 正方形，而这里是「整图铺满 rows x cols」。
                //    若原图长宽比 != 列:行，每块会被拉成正方形 -> 画面变形。
                //    因此先按 列:行 的比例**居中裁一刀**，每块天然是正方形。
                //    （自由裁剪模式已指定区域，不再二次裁剪）
                if (crop == null) {
                    val targetRatio = cols.toDouble() / rows.toDouble()
                    val curRatio = rawSrc.width.toDouble() / rawSrc.height.toDouble()
                    if (kotlin.math.abs(curRatio - targetRatio) > 0.005) {
                        if (padColor >= 0) {
                            // 补边：保留整张原图，等比放进目标比例的画布，四周围填色
                            val fill = when (padColor) {
                                1 -> android.graphics.Color.WHITE
                                2 -> android.graphics.Color.TRANSPARENT
                                else -> android.graphics.Color.BLACK
                            }
                            val tw: Int
                            val th: Int
                            if (curRatio > targetRatio) {
                                tw = rawSrc.width
                                th = (rawSrc.width / targetRatio).toInt().coerceAtLeast(1)
                            } else {
                                th = rawSrc.height
                                tw = (rawSrc.height * targetRatio).toInt().coerceAtLeast(1)
                            }
                            val padded = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                            val c = android.graphics.Canvas(padded)
                            if (fill != android.graphics.Color.TRANSPARENT) c.drawColor(fill)
                            c.drawBitmap(
                                rawSrc,
                                (tw - rawSrc.width) / 2f, (th - rawSrc.height) / 2f,
                                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                            )
                            rawSrc.recycle()
                            rawSrc = padded
                        } else {
                        val cropped = if (curRatio > targetRatio) {
                            val w = (rawSrc.height * targetRatio).toInt().coerceAtLeast(1)
                            val l = (rawSrc.width - w) / 2
                            Bitmap.createBitmap(rawSrc, l, 0, w, rawSrc.height)
                        } else {
                            val h = (rawSrc.width / targetRatio).toInt().coerceAtLeast(1)
                            val tp = (rawSrc.height - h) / 2
                            Bitmap.createBitmap(rawSrc, 0, tp, rawSrc.width, h)
                        }
                        if (cropped !== rawSrc) rawSrc.recycle()
                        rawSrc = cropped
                        }
                    }
                }

                if (rawSrc.width < cols || rawSrc.height < rows) {
                    rawSrc = Bitmap.createScaledBitmap(rawSrc, max(rawSrc.width, cols), max(rawSrc.height, rows), true)
                }
                val src = rawSrc
                val cellW = src.width / cols
                val cellH = src.height / rows
                val totalMaps = rows * cols

                _progressMessage.value = getString(R.string.msg_generating_maps, totalMaps)

                val itemsMap = ConcurrentHashMap<Int, JsonObject>()
                // 拼图生成并发设置（独立于文件拷贝开关）
                // puzzleMultiThread=false 时串行（省内存）；puzzleThreadCount=0 表示自动
                val threadCount = resolvePuzzleThreads(totalMaps)
                AppLogger.info("MapArt", R.string.log_puzzle_concurrency,
                if (threadCount <= 1) getString(R.string.log_puzzle_serial)
                else getString(R.string.log_puzzle_threads, threadCount),
                totalMaps)
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
                                chunk.recycle()
                                val pixels = IntArray(128 * 128)
                                scaled.getPixels(pixels, 0, 128, 0, 0, 128, 128)
                                scaled.recycle()
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
                                synchronized(dbManager) { dbManager.writeSpecificKey("map_$fMapId", mapBytes) }

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
                                        // 地图名走字符串资源，跟随应用语言
                                        add("v", JsonObject().apply {
                                            add("Name", wrapTag(8, getString(
                                                R.string.msg_puzzle_map_name,
                                                mapPrefix, r + 1, c + 1, rows, cols
                                            )))
                                        })
                                    }
                                    tc.add("display", dt)
                                    add("v", tc)
                                }
                                itemContent.add("tag", tagTag)
                                itemsMap[fIndex] = itemContent
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

                    // 全部使用粉色潜影盒（含嵌套的内层）。
                    // 实测可行：基岩版允许着色潜影盒嵌套，不会丢物品。
                    //   Name 与 Block.name 必须一致，否则图标/颜色会对不上。
                    // 颜色由设置决定（Name 与 Block.name 必须一致，否则图标/颜色对不上）
                    val colorId = cfg.getString("shulker_color", "pink_shulker_box")
                        ?: "pink_shulker_box"
                    val boxId = "minecraft:$colorId"

                    val boxItem = JsonObject()
                    boxItem.add("Name", wrapTag(8, boxId))
                    boxItem.add("Count", wrapTag(1, 1.toByte()))
                    boxItem.add("Damage", wrapTag(2, 0.toShort()))
                    boxItem.add("WasPickedUp", wrapTag(1, 0.toByte()))
                    val blockTag = JsonObject().apply {
                        addProperty("t", 10)
                        val bc = JsonObject()
                        // ⚠️ Block.name 必须与 Name 一致，否则游戏里图标/颜色会对不上
                        bc.add("name", wrapTag(8, boxId))
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
                            // 每一层都写名字：单层时带行列与数量，多层时额外标出第几层，
                            // 否则外层盒子是空名，玩家分不清哪盒是哪一个。
                            val boxName = if (totalLayers == 1) {
                                getString(R.string.msg_puzzle_box_name,
                                    boxPrefix, rows, cols, totalMaps)
                            } else {
                                getString(
                                    R.string.msg_puzzle_box_name_layer,
                                    boxPrefix, rows, cols, totalMaps, layer + 1, totalLayers
                                )
                            }
                            add("v", JsonObject().apply { add("Name", wrapTag(8, boxName)) })
                        }
                        tc.add("display", dt)
                        add("v", tc)
                    }
                    boxItem.add("tag", tagTag)
                    currentContainer = boxItem
                }

                val finalBox = currentContainer ?: throw Exception(getString(R.string.label_sub_packing_failed))
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
                dbManager.writeLocalPlayer(newPlayerData)
                //不要再把 写回 DB 这俩行忘了

// 【核心修复】回填缓存：把地图和玩家数据写入 EditorViewModel
                val evm = worldVM?.editorVM ?: return@launch
                evm.nbtDataCache["~local_player"] = playerRoot
                evm.setRawNbtData(playerRoot)

                dbManager.close()
                db = null

                _isGenerating.value = false
                _resultMessage.value = "Puzzle:SUCCESS:$totalMaps:$totalLayers:$startMapId:$useSlot"
            } catch (e: Exception) {
                try { db?.close() } catch (_: Exception) {}
                AppLogger.error("MapArt", "Map generation failed", e)
                _isGenerating.value = false
                _resultMessage.value = getString(R.string.msg_puzzle_error, e.message)
            }
        }
    }

    // ============================================
    // 辅助
    // ============================================
    private fun decodeSampledBitmap(context: Context, uri: Uri, reqW: Int, reqH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        var inSampleSize = 1
        while (width / inSampleSize > reqW * 2 || height / inSampleSize > reqH * 2) {
            inSampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        return try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) { null }
    }

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