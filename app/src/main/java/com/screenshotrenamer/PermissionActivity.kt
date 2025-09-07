package com.screenshotrenamer

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * 权限请求Activity 这个暂时没用了
 * 
 * 这是一个透明的Activity，专门用于处理Android 10+的媒体文件写入权限。
 * 当需要重命名截图文件时，如果遇到RecoverableSecurityException，
 * 系统会要求用户授权。由于Service无法直接处理这种需要用户交互的权限请求，
 * 所以创建这个Activity来承担权限请求的职责。
 * 
 * 工作流程：
 * 1. 接收来自Service的权限请求参数（URI、文件名等）
 * 2. 调用MediaStore.createWriteRequest()创建系统权限对话框
 * 3. 用户授权后，直接在Activity中执行重命名操作
 * 4. 重命名成功后，通知Service显示成功消息
 * 5. 完成后自动关闭，对用户透明
 * 
 * 注意：使用自定义的AppCompat兼容透明主题
 */
class PermissionActivity : AppCompatActivity() {
    
    companion object {
        // Intent传递参数的key
        const val EXTRA_URI = "extra_uri"                    // 截图文件的URI
        const val EXTRA_ORIGINAL_NAME = "extra_original_name" // 原始文件名
        const val EXTRA_NEW_NAME = "extra_new_name"          // 新文件名
        const val EXTRA_PACKAGE_NAME = "extra_package_name"  // 应用包名
        private const val TAG = "PermissionActivity"         // 日志标签
    }
    
    /**
     * 权限请求结果处理器
     * 使用现代的ActivityResultContracts API来处理系统权限对话框的返回结果
     */
    private val writeRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // 用户授权成功，现在尝试重命名
            val uri = intent.getParcelableExtra<Uri>(EXTRA_URI)
            val originalName = intent.getStringExtra(EXTRA_ORIGINAL_NAME)
            val newName = intent.getStringExtra(EXTRA_NEW_NAME)
            val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
            
            if (uri != null && originalName != null && newName != null && packageName != null) {
                performRename(uri, originalName, newName, packageName)
            }
        } else {
            Log.d(TAG, "User denied write permission")
        }
        // 无论成功还是失败，都要关闭Activity
        finish()
    }
    
    /**
     * Activity创建时的入口方法
     * 检查传入的参数并启动权限请求流程
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 从Intent中获取Service传递过来的参数
        val uri = intent.getParcelableExtra<Uri>(EXTRA_URI)
        val originalName = intent.getStringExtra(EXTRA_ORIGINAL_NAME)
        val newName = intent.getStringExtra(EXTRA_NEW_NAME)
        
        // 验证参数完整性
        if (uri != null && originalName != null && newName != null) {
            // 参数正确，开始请求权限
            requestWritePermission(uri)
        } else {
            Log.e(TAG, "Missing required parameters")
            finish()
        }
    }
    
    /**
     * 请求写入权限
     * @param uri 需要修改的媒体文件URI
     */
    private fun requestWritePermission(uri: Uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+：需要通过MediaStore.createWriteRequest()获取用户授权
                val writeRequest = MediaStore.createWriteRequest(contentResolver, listOf(uri))
                // 将IntentSender包装成IntentSenderRequest
                val intentSenderRequest = IntentSenderRequest.Builder(writeRequest.intentSender).build()
                // 启动系统权限对话框
                writeRequestLauncher.launch(intentSenderRequest)
            } else {
                // Android 9及以下：不需要特殊权限，直接重命名
                val originalName = intent.getStringExtra(EXTRA_ORIGINAL_NAME)
                val newName = intent.getStringExtra(EXTRA_NEW_NAME)
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                
                if (originalName != null && newName != null && packageName != null) {
                    performRename(uri, originalName, newName, packageName)
                }
                finish()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting write permission", e)
            finish()
        }
    }
    
    /**
     * 执行实际的重命名操作
     * 在用户授权后调用，直接更新MediaStore中的文件名
     * 
     * @param uri 文件URI
     * @param originalName 原始文件名
     * @param newName 新文件名
     * @param packageName 应用包名
     */
    private fun performRename(uri: Uri, originalName: String, newName: String, packageName: String) {
        try {
            // 创建ContentValues，设置新的文件名
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
            }
            
            // 执行更新操作
            val rowsUpdated = contentResolver.update(uri, cv, null, null)
            if (rowsUpdated > 0) {
                Log.d(TAG, "139Screenshot renamed successfully: $originalName -> $newName")
                
                // 通知Service显示成功消息
                val intent = Intent(this, ScreenshotWatcherService::class.java).apply {
                    action = "RENAME_SUCCESS"
                    putExtra("original_name", originalName)
                    putExtra("new_name", newName)
                    putExtra("package_name", packageName)
                }
                startService(intent)
            } else {
                Log.w(TAG, "Failed to rename screenshot: no rows updated")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error performing rename", e)
        }
    }
}
