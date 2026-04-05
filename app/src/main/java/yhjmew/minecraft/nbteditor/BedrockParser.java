package yhjmew.minecraft.nbteditor;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;

public class BedrockParser {

    // === 读取 (直接返回对象，不转String，省内存) ===
    public static JsonObject parse(String filePath) throws Exception {
        File file = new File(filePath);
        if (!file.exists()) return new JsonObject();
        FileInputStream fis = new FileInputStream(file);
        DataInputStream dis = new DataInputStream(fis);
        if (dis.available() > 8) dis.skipBytes(8); // 跳过头

        int rootTagId = dis.readByte();
        if (rootTagId != 10) {
            fis.close();
            return new JsonObject();
        }
        readString(dis); // 跳过根名

        JsonObject rootWrapper = (JsonObject) readTagPayload(dis, 10);
        fis.close();

        if (rootWrapper.has("v")) {
            return rootWrapper.getAsJsonObject("v");
        }
        return new JsonObject();
    }

    public static JsonObject parseBytes(byte[] data) throws Exception {
        if (data == null) return new JsonObject();
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream dis = new DataInputStream(bais);
        int rootTagId = dis.readByte();
        if (rootTagId != 10) return new JsonObject();
        readString(dis);
        JsonObject rootWrapper = (JsonObject) readTagPayload(dis, 10);
        bais.close();

        if (rootWrapper.has("v")) {
            return rootWrapper.getAsJsonObject("v");
        }
        return new JsonObject();
    }

    // === 写入 ===

    // 支持直接传入 JsonObject 写入，防止大字符串 OOM
    public static void write(JsonObject rootJson, String destPath) throws Exception {
        byte[] nbtBytes = writeToBytes(rootJson);
        FileOutputStream fos = new FileOutputStream(destPath);
        DataOutputStream fileDos = new DataOutputStream(fos);
        writeIntLE(fileDos, 8);
        writeIntLE(fileDos, nbtBytes.length);
        fileDos.write(nbtBytes);
        fos.close();
    }

    // 兼容旧接口：如果你一定要传字符串 (建议少用)
    public static void write(String json, String destPath) throws Exception {
        write(new Gson().fromJson(json, JsonObject.class), destPath);
    }

    public static byte[] writeToBytes(String json) throws Exception {
        return writeToBytes(new Gson().fromJson(json, JsonObject.class));
    }

    // 核心写入逻辑
    public static byte[] writeToBytes(JsonObject rootContent) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // 重新包装回 Root Compound
        JsonObject rootWrapper = new JsonObject();
        rootWrapper.addProperty("t", 10);
        rootWrapper.add("v", rootContent);

        dos.writeByte(10);
        writeString(dos, "");
        writeTagPayload(dos, rootWrapper, 10);
        return baos.toByteArray();
    }

    // --- 递归解析逻辑 ---
    private static Object readTagPayload(DataInputStream dis, int typeId) throws IOException {
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("t", typeId);
        switch (typeId) {
            case 1:
                wrapper.addProperty("v", dis.readByte());
                return wrapper;
            case 2:
                wrapper.addProperty("v", readShortLE(dis));
                return wrapper;
            case 3:
                wrapper.addProperty("v", readIntLE(dis));
                return wrapper;
            case 4:
                wrapper.addProperty("v", readLongLE(dis));
                return wrapper;
            case 5:
                wrapper.addProperty("v", readFloatLE(dis));
                return wrapper;
            case 6:
                wrapper.addProperty("v", readDoubleLE(dis));
                return wrapper;
            case 7:
                {
                    int len = readIntLE(dis);
                    JsonArray arr = new JsonArray();
                    for (int i = 0; i < len; i++) arr.add(dis.readByte());
                    wrapper.add("v", arr);
                    return wrapper;
                }
            case 11:
                {
                    int len = readIntLE(dis);
                    JsonArray arr = new JsonArray();
                    for (int i = 0; i < len; i++) arr.add(readIntLE(dis));
                    wrapper.add("v", arr);
                    return wrapper;
                }
            case 12:
                {
                    int len = readIntLE(dis);
                    JsonArray arr = new JsonArray();
                    for (int i = 0; i < len; i++) arr.add(readLongLE(dis));
                    wrapper.add("v", arr);
                    return wrapper;
                }

            case 8:
                wrapper.addProperty("v", readString(dis));
                return wrapper;

            case 10: // Compound
                JsonObject compound = new JsonObject();
                while (true) {
                    int nextId = dis.readByte();
                    if (nextId == 0) break;
                    String name = readString(dis);
                    compound.add(name, (JsonElement) readTagPayload(dis, nextId));
                }
                wrapper.add("v", compound);
                return wrapper;

            case 9: // List
                int itemType = dis.readByte();
                int listSize = readIntLE(dis);
                JsonArray list = new JsonArray();
                wrapper.addProperty("itemType", itemType);
                for (int i = 0; i < listSize; i++)
                    list.add(extractValue(readTagPayload(dis, itemType)));
                wrapper.add("v", list);
                return wrapper;
            default:
                return null;
        }
    }

    private static JsonElement extractValue(Object obj) {
        if (obj instanceof JsonObject) {
            JsonObject jo = (JsonObject) obj;
            if (jo.has("v")) return jo.get("v");
            return jo;
        }
        return null;
    }

    private static void writeTagPayload(DataOutputStream dos, JsonElement element, int typeId)
            throws IOException {
        JsonElement value = getRealValue(element);
        switch (typeId) {
            case 1:
                dos.writeByte(getSafeByte(value));
                break;
            case 2:
                writeShortLE(dos, getSafeShort(value));
                break;
            case 3:
                writeIntLE(dos, getSafeInt(value));
                break;
            case 4:
                writeLongLE(dos, getSafeLong(value));
                break;
            case 5:
                writeFloatLE(dos, getSafeFloat(value));
                break;
            case 6:
                writeDoubleLE(dos, getSafeDouble(value));
                break;
            case 7:
                {
                    JsonArray a = value.getAsJsonArray();
                    writeIntLE(dos, a.size());
                    for (JsonElement e : a) dos.writeByte(e.getAsByte());
                    break;
                }
            case 11:
                {
                    JsonArray a = value.getAsJsonArray();
                    writeIntLE(dos, a.size());
                    for (JsonElement e : a) writeIntLE(dos, e.getAsInt());
                    break;
                }
            case 12:
                {
                    JsonArray a = value.getAsJsonArray();
                    writeIntLE(dos, a.size());
                    for (JsonElement e : a) writeLongLE(dos, e.getAsLong());
                    break;
                }
            case 8:
                writeString(dos, value.isJsonPrimitive() ? value.getAsString() : "");
                break;

            case 10:
                JsonObject obj = new JsonObject();
                if (element.isJsonObject()) {
                    JsonObject temp = element.getAsJsonObject();
                    if (temp.has("v") && temp.get("v").isJsonObject())
                        obj = temp.getAsJsonObject("v");
                    else obj = temp;
                }

                for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                    String name = entry.getKey();
                    JsonElement valWrapper = entry.getValue();
                    if (name.equals("t") || name.equals("v") || name.equals("itemType")) continue;

                    int t = 10;
                    if (valWrapper.isJsonObject() && valWrapper.getAsJsonObject().has("t"))
                        t = valWrapper.getAsJsonObject().get("t").getAsInt();
                    else if (valWrapper.isJsonArray()) t = 9;

                    dos.writeByte(t);
                    writeString(dos, name);
                    writeTagPayload(dos, valWrapper, t);
                }
                dos.writeByte(0);
                break;

            case 9:
                JsonArray arr = new JsonArray();
                int it = 1;
                if (element.isJsonObject()) {
                    JsonObject wrap = element.getAsJsonObject();
                    if (wrap.has("itemType")) it = wrap.get("itemType").getAsInt();
                    if (wrap.has("v") && wrap.get("v").isJsonArray())
                        arr = wrap.getAsJsonArray("v");
                } else if (element.isJsonArray()) arr = element.getAsJsonArray();

                dos.writeByte(it);
                writeIntLE(dos, arr.size());
                for (JsonElement e : arr) {
                    JsonObject temp = new JsonObject();
                    temp.add("v", e);
                    temp.addProperty("t", it);
                    writeTagPayload(dos, temp, it);
                }
                break;
        }
    }

    private static JsonElement getRealValue(JsonElement el) {
        if (el.isJsonObject() && el.getAsJsonObject().has("v") && el.getAsJsonObject().has("t"))
            return el.getAsJsonObject().get("v");
        return el;
    }

    private static byte getSafeByte(JsonElement el) {
        try {
            return el.getAsByte();
        } catch (Exception e) {
            return 0;
        }
    }

    private static short getSafeShort(JsonElement el) {
        try {
            return el.getAsShort();
        } catch (Exception e) {
            return 0;
        }
    }

    private static int getSafeInt(JsonElement el) {
        try {
            return el.getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }

    private static long getSafeLong(JsonElement el) {
        try {
            return el.getAsLong();
        } catch (Exception e) {
            return 0;
        }
    }

    private static float getSafeFloat(JsonElement el) {
        try {
            return el.getAsFloat();
        } catch (Exception e) {
            return 0;
        }
    }

    private static double getSafeDouble(JsonElement el) {
        try {
            return el.getAsDouble();
        } catch (Exception e) {
            return 0;
        }
    }

    private static short readShortLE(DataInputStream s) throws IOException {
        return Short.reverseBytes(s.readShort());
    }

    private static int readIntLE(DataInputStream s) throws IOException {
        return Integer.reverseBytes(s.readInt());
    }

    private static long readLongLE(DataInputStream s) throws IOException {
        return Long.reverseBytes(s.readLong());
    }

    private static float readFloatLE(DataInputStream s) throws IOException {
        return Float.intBitsToFloat(readIntLE(s));
    }

    private static double readDoubleLE(DataInputStream s) throws IOException {
        return Double.longBitsToDouble(readLongLE(s));
    }

    private static String readString(DataInputStream s) throws IOException {
        int len = readShortLE(s) & 0xFFFF;
        byte[] b = new byte[len];
        s.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void writeShortLE(DataOutputStream s, short v) throws IOException {
        s.writeShort(Short.reverseBytes(v));
    }

    private static void writeIntLE(DataOutputStream s, int v) throws IOException {
        s.writeInt(Integer.reverseBytes(v));
    }

    private static void writeLongLE(DataOutputStream s, long v) throws IOException {
        s.writeLong(Long.reverseBytes(v));
    }

    private static void writeFloatLE(DataOutputStream s, float v) throws IOException {
        writeIntLE(s, Float.floatToIntBits(v));
    }

    private static void writeDoubleLE(DataOutputStream s, double v) throws IOException {
        writeLongLE(s, Double.doubleToLongBits(v));
    }

    private static void writeString(DataOutputStream s, String v) throws IOException {
        byte[] b = v.getBytes(StandardCharsets.UTF_8);
        writeShortLE(s, (short) b.length);
        s.write(b);
    }
}