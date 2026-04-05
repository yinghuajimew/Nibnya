package yhjmew.minecraft.nbteditor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Litl LevelDB JNI
import com.litl.leveldb.DB;
import com.litl.leveldb.Iterator;

public class PlayerDbManager {

    private DB db;
    private File dbFolder;

    // 加载 so 库
    static {
        try {
            System.loadLibrary("leveldb");
        } catch (UnsatisfiedLinkError e) {
            try { 
                System.loadLibrary("leveldbjni"); 
            } catch (Throwable ignored) {}
        }
    }

    public PlayerDbManager(String dbFolderPath) throws Exception {
        this.dbFolder = new File(dbFolderPath);
        if (!dbFolder.exists()) {
            dbFolder.mkdirs();
        }

        // 清理 LOCK 文件
        File lockFile = new File(dbFolder, "LOCK");
        if (lockFile.exists()) {
            lockFile.delete();
        }

        // Litl 版本的打开方式
        this.db = new DB(dbFolder);
        this.db.open();
    }

    public static void tryRepair(String dbFolderPath) throws Exception {
        File folder = new File(dbFolderPath);
        
        // 清理 LOCK
        File lockFile = new File(folder, "LOCK");
        if (lockFile.exists()) {
            lockFile.delete();
        }
        
        // 清理 CURRENT
        File currentFile = new File(folder, "CURRENT");
        if (currentFile.exists()) {
            currentFile.delete();
        }

        try {
            DB tempDb = new DB(folder);
            tempDb.open();
            tempDb.close();
        } catch (Exception e) {
            throw new Exception(NbtTranslator.getString(R.string.msg_the_repair_failed_and_the_data_was_completely_damaged) + e.getMessage());
        }
    }

    public byte[] readLocalPlayer() throws Exception {
        if (db == null) 
            throw new Exception(NbtTranslator.getString(R.string.msg_database_is_not_open));
        
        byte[] key = "~local_player".getBytes(StandardCharsets.UTF_8);
        byte[] value = db.get(key);
        
        if (value == null) {
            // 兜底遍历查找
            Iterator iterator = db.iterator();
            try {
                iterator.seekToFirst();
                while (iterator.isValid()) {
                    String keyStr = new String(iterator.getKey(), StandardCharsets.UTF_8);
                    if (keyStr.contains("local_player") || keyStr.contains("player_server")) {
                        return iterator.getValue();
                    }
                    iterator.next();
                }
            } finally {
                iterator.close();
            }
            throw new Exception(NbtTranslator.getString(R.string.msg_player_data_not_found));
        }
        return value;
    }

    public byte[] readSpecificKey(String keyString) throws Exception {
        if (db == null) 
            throw new Exception(NbtTranslator.getString(R.string.msg_dn_is_not_open));
        byte[] keyBytes = keyString.getBytes(StandardCharsets.UTF_8);
        byte[] val = db.get(keyBytes);
        if (val == null) 
            throw new Exception(NbtTranslator.getString(R.string.msg_data_is_empty_colon) + keyString);
        return val;
    }

    public byte[] readRawKey(byte[] keyBytes) throws Exception {
        if (db == null) 
            throw new Exception(NbtTranslator.getString(R.string.msg_dn_is_not_open));
        byte[] val = db.get(keyBytes);
        if (val == null) 
            throw new Exception(NbtTranslator.getString(R.string.msg_the_data_corresponding_to_this_key_was_not_found));
        return val;
    }

    public void writeLocalPlayer(byte[] nbtData) throws Exception {
        if (db == null) 
            throw new Exception(NbtTranslator.getString(R.string.msg_database_is_not_open));
        byte[] key = "~local_player".getBytes(StandardCharsets.UTF_8);
        db.put(key, nbtData);
    }

    public void writeSpecificKey(String keyStr, byte[] data) throws Exception {
        if (db == null) 
            throw new Exception(NbtTranslator.getString(R.string.msg_database_shutdown));
        byte[] k = keyStr.getBytes(StandardCharsets.UTF_8);
        db.put(k, data);
    }

    public void deleteKey(String keyStr) throws Exception {
        if (db == null) 
            throw new Exception(NbtTranslator.getString(R.string.msg_database_shutdown));
        byte[] k = keyStr.getBytes(StandardCharsets.UTF_8);
        db.delete(k);
    }

    public List<String> listMapKeys() throws Exception {
        if (db == null) 
            throw new Exception(NbtTranslator.getString(R.string.msg_database_shutdown));
        List<String> list = new ArrayList<>();
        
        Iterator iterator = db.iterator();
        try {
            byte[] prefix = "map_".getBytes(StandardCharsets.UTF_8);
            iterator.seek(prefix);
            
            while(iterator.isValid()) {
                byte[] keyBytes = iterator.getKey();
                String keyStr = new String(keyBytes, StandardCharsets.UTF_8);
                if (!keyStr.startsWith("map_")) break;
                list.add(keyStr);
                iterator.next();
            }
        } finally {
            iterator.close();
        }
        Collections.sort(list); 
        return list;
    }

    public List<String> listVillageKeys() throws Exception {
        if (db == null) 
            throw new Exception(NbtTranslator.getString(R.string.msg_database_shutdown));
        List<String> list = new ArrayList<>();
        
        Iterator iterator = db.iterator();
        try {
            byte[] prefix = "VILLAGE_".getBytes(StandardCharsets.UTF_8);
            iterator.seek(prefix);
            
            while(iterator.isValid()) {
                String keyStr = new String(iterator.getKey(), StandardCharsets.UTF_8);
                if (!keyStr.startsWith("VILLAGE_")) break;
                list.add(keyStr);
                iterator.next();
            }
        } finally {
            iterator.close();
        }
        Collections.sort(list);
        return list;
    }

    public List<String> listPlayerKeys() throws Exception {
        if (db == null) 
            throw new Exception(NbtTranslator.getString(R.string.msg_database_shutdown));
        List<String> players = new ArrayList<>();
        players.add("~local_player");
        
        Iterator iterator = db.iterator();
        try {
            byte[] prefix = "player".getBytes(StandardCharsets.UTF_8);
            iterator.seek(prefix);
            
            while(iterator.isValid()) {
                String keyStr = new String(iterator.getKey(), StandardCharsets.UTF_8);
                if (!keyStr.startsWith("player")) break;
                
                if (keyStr.startsWith("player_") || keyStr.equals("player")) {
                    players.add(keyStr);
                }
                iterator.next();
            }
        } finally {
            iterator.close();
        }
        Collections.sort(players);
        return players;
    }

    public void close() {
        if (db != null) {
            db.close();
            db = null;
        }
    }
}