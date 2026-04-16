package yhjmew.minecraft.nbteditor

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Double
import java.lang.Float
import java.lang.Long
import java.lang.Short
import java.nio.charset.StandardCharsets
import kotlin.Any
import kotlin.Byte
import kotlin.ByteArray
import kotlin.Exception
import kotlin.Int
import kotlin.String
import kotlin.Throws
import kotlin.text.String
import kotlin.text.toByteArray

object BedrockParser {
    // === 读取 (直接返回对象，不转String，省内存) ===
    @Throws(Exception::class)
    fun parse(filePath: String): JsonObject? {
        val file = File(filePath)
        if (!file.exists()) return JsonObject()
        val fis = FileInputStream(file)
        val dis = DataInputStream(fis)
        if (dis.available() > 8) dis.skipBytes(8) // 跳过头


        val rootTagId = dis.readByte().toInt()
        if (rootTagId != 10) {
            fis.close()
            return JsonObject()
        }
        readString(dis) // 跳过根名

        val rootWrapper = readTagPayload(dis, 10) as JsonObject?
        fis.close()

        if (rootWrapper != null && rootWrapper.has("v")) {
            return rootWrapper.getAsJsonObject("v")
        }
        return JsonObject()
    }

    @Throws(Exception::class)
    fun parseBytes(data: ByteArray?): JsonObject? {
        if (data == null) return JsonObject()
        val bais = ByteArrayInputStream(data)
        val dis = DataInputStream(bais)
        val rootTagId = dis.readByte().toInt()
        if (rootTagId != 10) return JsonObject()
        readString(dis)
        val rootWrapper = readTagPayload(dis, 10) as JsonObject?
        bais.close()

        if (rootWrapper != null && rootWrapper.has("v")) {
            return rootWrapper.getAsJsonObject("v")
        }
        return JsonObject()
    }

    // === 写入 ===
    // 支持直接传入 JsonObject 写入，防止大字符串 OOM
    @Throws(Exception::class)
    fun write(rootJson: JsonObject?, destPath: String?) {
        val nbtBytes = writeToBytes(rootJson)
        val fos = FileOutputStream(destPath)
        val fileDos = DataOutputStream(fos)
        writeIntLE(fileDos, 8)
        writeIntLE(fileDos, nbtBytes.size)
        fileDos.write(nbtBytes)
        fos.close()
    }

    // 兼容旧接口：如果你一定要传字符串 (建议少用)
    @Throws(Exception::class)
    fun write(json: String?, destPath: String?) {   //存在
        write(Gson().fromJson<JsonObject?>(json, JsonObject::class.java), destPath)
    }

    @Throws(Exception::class)
    fun writeToBytes(json: String?): ByteArray {   //存在
        return writeToBytes(Gson().fromJson<JsonObject?>(json, JsonObject::class.java))
    }

    // 核心写入逻辑
    @Throws(Exception::class)
    fun writeToBytes(rootContent: JsonObject?): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // 重新包装回 Root Compound
        val rootWrapper = JsonObject()
        rootWrapper.addProperty("t", 10)
        rootWrapper.add("v", rootContent)

        dos.writeByte(10)
        writeString(dos, "")
        writeTagPayload(dos, rootWrapper, 10)
        return baos.toByteArray()
    }

    // --- 递归解析逻辑 ---
    @Throws(IOException::class)
    private fun readTagPayload(dis: DataInputStream, typeId: Int): Any? {
        val wrapper = JsonObject()
        wrapper.addProperty("t", typeId)
        when (typeId) {
            1 -> {
                wrapper.addProperty("v", dis.readByte())
                return wrapper
            }

            2 -> {
                wrapper.addProperty("v", readShortLE(dis))
                return wrapper
            }

            3 -> {
                wrapper.addProperty("v", readIntLE(dis))
                return wrapper
            }

            4 -> {
                wrapper.addProperty("v", readLongLE(dis))
                return wrapper
            }

            5 -> {
                wrapper.addProperty("v", readFloatLE(dis))
                return wrapper
            }

            6 -> {
                wrapper.addProperty("v", readDoubleLE(dis))
                return wrapper
            }

            7 -> {
                val len = readIntLE(dis)
                val arr = JsonArray()
                var i = 0
                while (i < len) {
                    arr.add(dis.readByte())
                    i++
                }
                wrapper.add("v", arr)
                return wrapper
            }

            11 -> {
                val len = readIntLE(dis)
                val arr = JsonArray()
                var i = 0
                while (i < len) {
                    arr.add(readIntLE(dis))
                    i++
                }
                wrapper.add("v", arr)
                return wrapper
            }

            12 -> {
                val len = readIntLE(dis)
                val arr = JsonArray()
                var i = 0
                while (i < len) {
                    arr.add(readLongLE(dis))
                    i++
                }
                wrapper.add("v", arr)
                return wrapper
            }

            8 -> {
                wrapper.addProperty("v", readString(dis))
                return wrapper
            }

            10 -> {
                val compound = JsonObject()
                while (true) {
                    val nextId = dis.readByte().toInt()
                    if (nextId == 0) break
                    val name = readString(dis)
                    compound.add(name, readTagPayload(dis, nextId) as JsonElement?)
                }
                wrapper.add("v", compound)
                return wrapper
            }

            9 -> {
                val itemType = dis.readByte().toInt()
                val listSize = readIntLE(dis)
                val list = JsonArray()
                wrapper.addProperty("itemType", itemType)
                var i = 0
                while (i < listSize) {
                    list.add(extractValue(readTagPayload(dis, itemType)))
                    i++
                }
                wrapper.add("v", list)
                return wrapper
            }

            else -> return null
        }
    }

    private fun extractValue(obj: Any?): JsonElement? {
        if (obj is JsonObject) {
            if (obj.has("v")) return obj.get("v")
            return obj
        }
        return null
    }

    @Throws(IOException::class)
    private fun writeTagPayload(dos: DataOutputStream, element: JsonElement, typeId: Int) {
        val value = getRealValue(element)
        when (typeId) {
            1 -> dos.writeByte(getSafeByte(value).toInt())
            2 -> writeShortLE(dos, getSafeShort(value).toShort())
            3 -> writeIntLE(dos, getSafeInt(value))
            4 -> writeLongLE(dos, getSafeLong(value).toLong())
            5 -> writeFloatLE(dos, getSafeFloat(value).toFloat())
            6 -> writeDoubleLE(dos, getSafeDouble(value).toDouble())
            7 -> {
                val a = value.asJsonArray
                writeIntLE(dos, a.size())
                for (e in a) dos.writeByte(e.asByte.toInt())
            }

            11 -> {
                val a = value.asJsonArray
                writeIntLE(dos, a.size())
                for (e in a) writeIntLE(dos, e.asInt)
            }

            12 -> {
                val a = value.asJsonArray
                writeIntLE(dos, a.size())
                for (e in a) writeLongLE(dos, e.asLong)
            }

            8 -> writeString(dos, if (value.isJsonPrimitive) value.asString else "")
            10 -> {
                val obj = JsonObject()
                if (element.isJsonObject) {
                    val temp = element.asJsonObject
                    if (temp.has("v") && temp.get("v").isJsonObject) {
                        temp.getAsJsonObject("v").also { obj.add("v", it.get("v")) }
                        // 直接使用 temp 的 v
                        for (entry in temp.getAsJsonObject("v").entrySet()) {
                            val name = entry.key
                            val valWrapper = entry.value
                            var t = 10
                            if (valWrapper.isJsonObject && valWrapper.asJsonObject.has("t")) {
                                t = valWrapper.asJsonObject.get("t").asInt
                            } else if (valWrapper.isJsonArray) {
                                t = 9
                            }

                            dos.writeByte(t)
                            writeString(dos, name)
                            writeTagPayload(dos, valWrapper, t)
                        }
                        dos.writeByte(0)
                        return
                    } else {
                        for (entry in temp.entrySet()) {
                            val name = entry.key
                            val valWrapper = entry.value
                            if (name == "t" || name == "v" || name == "itemType") continue

                            var t = 10
                            if (valWrapper.isJsonObject && valWrapper.asJsonObject.has("t")) {
                                t = valWrapper.asJsonObject.get("t").asInt
                            } else if (valWrapper.isJsonArray) {
                                t = 9
                            }

                            dos.writeByte(t)
                            writeString(dos, name)
                            writeTagPayload(dos, valWrapper, t)
                        }
                        dos.writeByte(0)
                        return
                    }
                }

                for (entry in obj.entrySet()) {
                    val name = entry.key
                    val valWrapper = entry.value
                    if (name == "t" || name == "v" || name == "itemType") continue

                    var t = 10
                    if (valWrapper.isJsonObject && valWrapper.asJsonObject.has("t")) {
                        t = valWrapper.asJsonObject.get("t").asInt
                    } else if (valWrapper.isJsonArray) {
                        t = 9
                    }

                    dos.writeByte(t)
                    writeString(dos, name)
                    writeTagPayload(dos, valWrapper, t)
                }
                dos.writeByte(0)
            }

            9 -> {
                var arr = JsonArray()
                var it = 1
                if (element.isJsonObject) {
                    val wrap = element.asJsonObject
                    if (wrap.has("itemType")) it = wrap.get("itemType").asInt
                    if (wrap.has("v") && wrap.get("v").isJsonArray) arr = wrap.getAsJsonArray("v")
                } else if (element.isJsonArray) arr = element.asJsonArray

                dos.writeByte(it)
                writeIntLE(dos, arr.size())
                for (e in arr) {
                    val temp = JsonObject()
                    temp.add("v", e)
                    temp.addProperty("t", it)
                    writeTagPayload(dos, temp, it)
                }
            }
        }
    }

    private fun getRealValue(el: JsonElement): JsonElement {
        if (el.isJsonObject() && el.getAsJsonObject().has("v") && el.getAsJsonObject()
                .has("t")
        ) return el.getAsJsonObject().get("v")
        return el
    }

    private fun getSafeByte(el: JsonElement): Byte {
        return try {
            el.asByte
        } catch (e: Exception) {
            0.toByte()
        }
    }

    private fun getSafeShort(el: JsonElement): kotlin.Short {
        return try {
            el.asShort
        } catch (e: Exception) {
            0.toShort()
        }
    }

    private fun getSafeInt(el: JsonElement): Int {
        return try {
            el.asInt
        } catch (e: Exception) {
            0
        }
    }

    private fun getSafeLong(el: JsonElement): kotlin.Long {
        return try {
            el.asLong
        } catch (e: Exception) {
            0L
        }
    }

    private fun getSafeFloat(el: JsonElement): kotlin.Float {
        return try {
            el.asFloat
        } catch (e: Exception) {
            0.0f
        }
    }

    private fun getSafeDouble(el: JsonElement): kotlin.Double {
        return try {
            el.asDouble
        } catch (e: Exception) {
            0.0
        }
    }

    @Throws(IOException::class)
    private fun readShortLE(s: DataInputStream): kotlin.Short {
        return Short.reverseBytes(s.readShort())
    }

    @Throws(IOException::class)
    private fun readIntLE(s: DataInputStream): Int {
        return Integer.reverseBytes(s.readInt())
    }

    @Throws(IOException::class)
    private fun readLongLE(s: DataInputStream): kotlin.Long {
        return java.lang.Long.reverseBytes(s.readLong())
    }

    @Throws(IOException::class)
    private fun readFloatLE(s: DataInputStream): kotlin.Float {
        return Float.intBitsToFloat(readIntLE(s))
    }

    @Throws(IOException::class)
    private fun readDoubleLE(s: DataInputStream): kotlin.Double {
        return java.lang.Double.longBitsToDouble(readLongLE(s))
    }

    @Throws(IOException::class)
    private fun readString(s: DataInputStream): String {
        val len = readShortLE(s).toInt() and 0xFFFF
        val b = ByteArray(len)
        s.readFully(b)
        return String(b, StandardCharsets.UTF_8)
    }

    @Throws(IOException::class)
    private fun writeShortLE(s: DataOutputStream, v: kotlin.Short) {
        s.writeShort(Short.reverseBytes(v).toInt())
    }

    @Throws(IOException::class)
    private fun writeIntLE(s: DataOutputStream, v: Int) {
        s.writeInt(Integer.reverseBytes(v))
    }

    @Throws(IOException::class)
    private fun writeLongLE(s: DataOutputStream, v: kotlin.Long) {
        s.writeLong(Long.reverseBytes(v))
    }

    @Throws(IOException::class)
    private fun writeFloatLE(s: DataOutputStream, v: kotlin.Float) {
        writeIntLE(s, Float.floatToIntBits(v))
    }

    @Throws(IOException::class)
    private fun writeDoubleLE(s: DataOutputStream, v: kotlin.Double) {
        writeLongLE(s, Double.doubleToLongBits(v))
    }

    @Throws(IOException::class)
    private fun writeString(s: DataOutputStream, v: String) {
        val b = v.toByteArray(StandardCharsets.UTF_8)
        writeShortLE(s, b.size.toShort())
        s.write(b)
    }
}