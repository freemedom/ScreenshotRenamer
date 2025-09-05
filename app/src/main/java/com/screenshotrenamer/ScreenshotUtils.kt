package com.screenshotrenamer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

object ScreenshotUtils {
    private const val TAG = "ScreenshotUtils"

    /**
     * 查询最近的截图
     * @param context 上下文
     * @param hintUri 提示的URI，可为null
     * @return Pair<Uri, String>? 截图URI和文件名，如果没有找到则返回null
     */
    fun queryRecentScreenshot(context: Context, hintUri: Uri?): Pair<Uri, String>? {
        val now = System.currentTimeMillis() / 1000 // MediaStore 的 DATE_ADDED 是秒
        
        // 构建查询条件：最近10秒内添加的截图
        val selection = buildString {
            append("${MediaStore.Images.Media.DATE_ADDED} >= ? AND ")
            // 兼容不同厂商目录：优先 RELATIVE_PATH，其次 BUCKET_DISPLAY_NAME
            append("(")
            append("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ")
            append(" OR ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?")
            append(")")
        }
        
        val args = arrayOf(
            (now - 10).toString(), // 最近10秒
            "%/Screenshots/%",     // RELATIVE_PATH 包含 Screenshots
            "Screenshots"          // BUCKET_DISPLAY_NAME 等于 Screenshots
        )
        
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, 
                selection, 
                args, 
                "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 1"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                    val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                    val relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)) ?: ""
                    val bucketName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)) ?: ""
                    
                    Log.d(TAG, "Found screenshot candidate: name=$name, dateAdded=$dateAdded, relativePath=$relativePath, bucketName=$bucketName")
                    
                    // 验证是否真的是截图
                    if (isScreenshot(name, relativePath, bucketName)) {
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                        )
                        Log.d(TAG, "Confirmed screenshot: $name")
                        return uri to name
                    } else {
                        Log.d(TAG, "Not a screenshot: $name")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying recent screenshots", e)
        }
        
        return null
    }

    /**
     * 判断文件是否为截图
     */
    private fun isScreenshot(fileName: String, relativePath: String, bucketName: String): Boolean {
        // 1. 检查文件名是否包含常见的截图前缀
        val screenshotPrefixes = listOf(
            "screenshot", "screen_shot", "screen", "capture",
            "截图", "屏幕截图", "Screenshot"
        )
        
        val fileNameLower = fileName.lowercase()
        val hasScreenshotPrefix = screenshotPrefixes.any { prefix ->
            fileNameLower.startsWith(prefix.lowercase())
        }
        
        // 2. 检查路径是否包含 Screenshots
        val pathContainsScreenshots = relativePath.contains("Screenshots", ignoreCase = true)
        
        // 3. 检查 bucket 名称
        val bucketIsScreenshots = bucketName.equals("Screenshots", ignoreCase = true)
        
        // 4. 检查文件扩展名
        val validExtensions = listOf(".jpg", ".jpeg", ".png", ".webp")
        val hasValidExtension = validExtensions.any { ext ->
            fileName.endsWith(ext, ignoreCase = true)
        }
        
        Log.d(TAG, "Screenshot check for $fileName: prefix=$hasScreenshotPrefix, path=$pathContainsScreenshots, bucket=$bucketIsScreenshots, ext=$hasValidExtension")
        
        // 必须有有效的图片扩展名，并且满足以下任一条件：
        // - 文件名以截图相关前缀开头
        // - 路径包含 Screenshots
        // - Bucket 名称是 Screenshots
        return hasValidExtension && (hasScreenshotPrefix || pathContainsScreenshots || bucketIsScreenshots)
    }
}
