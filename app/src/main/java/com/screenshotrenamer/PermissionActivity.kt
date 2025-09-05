package com.screenshotrenamer

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class PermissionActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_ORIGINAL_NAME = "extra_original_name"
        const val EXTRA_NEW_NAME = "extra_new_name"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        private const val TAG = "PermissionActivity"
    }
    
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
        finish()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val uri = intent.getParcelableExtra<Uri>(EXTRA_URI)
        val originalName = intent.getStringExtra(EXTRA_ORIGINAL_NAME)
        val newName = intent.getStringExtra(EXTRA_NEW_NAME)
        
        if (uri != null && originalName != null && newName != null) {
            requestWritePermission(uri)
        } else {
            Log.e(TAG, "Missing required parameters")
            finish()
        }
    }
    
    private fun requestWritePermission(uri: Uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val writeRequest = MediaStore.createWriteRequest(contentResolver, listOf(uri))
                writeRequestLauncher.launch(writeRequest.intentSender)
            } else {
                // Android 9及以下不需要这个权限
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
    
    private fun performRename(uri: Uri, originalName: String, newName: String, packageName: String) {
        try {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
            }
            
            val rowsUpdated = contentResolver.update(uri, cv, null, null)
            if (rowsUpdated > 0) {
                Log.d(TAG, "Screenshot renamed successfully: $originalName -> $newName")
                
                // 发送成功通知
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
