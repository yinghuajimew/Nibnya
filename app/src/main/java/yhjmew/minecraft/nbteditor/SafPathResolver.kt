package yhjmew.minecraft.nbteditor

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * SAF Tree URI → 真实路径解析器
 */
object SafPathResolver {

    /**
     * 尝试将 SAF Tree URI 解析为文件系统路径
     * 返回 null 表示无法解析（需要改用 DocumentFile API）
     */
    fun resolveTreeUriToPath(context: Context, treeUri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            // 格式: primary:games/com.mojang/...
            if (docId.startsWith("primary:")) {
                "/storage/emulated/0/" + docId.removePrefix("primary:")
            } else if (docId.contains(":")) {
                val parts = docId.split(":")
                if (parts.size == 2) {
                    "/storage/${parts[0]}/${parts[1]}"
                } else null
            } else null
        } catch (_: Exception) { null }
    }

    /**
     * 判断路径是否可以通过普通 File API 访问
     */
    fun isProbablyUsablePath(path: String): Boolean {
        // Android 11+ 对于 /Android/data/ 路径即使有 SAF 授权也无法直接 File API 访问
        if (Build.VERSION.SDK_INT >= 30 && path.contains("/Android/data/")) return false
        if (Build.VERSION.SDK_INT >= 30 && path.contains("/Android/obb/")) return false
        val file = File(path)
        return file.exists() && file.canRead()
    }

    /**
     * 从 SAF URI 猜测 Minecraft 世界路径
     */
    fun guessWorldRootFromUri(treeUri: Uri): String? {
        val uriStr = treeUri.toString()
        return when {
            uriStr.contains("com.mojang.minecraftpe") -> {
                val base = uriStr.substringAfter("com.mojang.minecraftpe")
                val path = base.substringBefore("tree")
                "/storage/emulated/0/Android/data/com.mojang.minecraftpe/files/games/com.mojang/minecraftWorlds/"
            }
            uriStr.contains("primary:") -> {
                // 从 URI 中提取路径段
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                if (docId.startsWith("primary:")) {
                    "/storage/emulated/0/" + docId.removePrefix("primary:")
                } else null
            }
            else -> null
        }
    }
}