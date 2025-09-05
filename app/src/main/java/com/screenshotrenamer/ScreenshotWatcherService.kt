package com.screenshotrenamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class ScreenshotWatcherService : LifecycleService() {
    companion object {
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_CHANNEL_ID = "screenshot_watcher"
        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_STOP_MONITORING = "STOP_MONITORING"
        private const val TAG = "ScreenshotWatcher"
    }

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            // 建议做节流与去抖：onChange 可能多次触发
            GlobalScope.launch(Dispatchers.IO) {
                handleMediaStoreChange(uri)
            }
        }
    }

    private var isObserverRegistered = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> {
                startMonitoring()
            }
            ACTION_STOP_MONITORING -> {
                stopMonitoring()
            }
            "RENAME_SUCCESS" -> {
                // 处理从PermissionActivity传来的重命名成功消息
                val originalName = intent.getStringExtra("original_name")
                val newName = intent.getStringExtra("new_name")
                val packageName = intent.getStringExtra("package_name")
                
                if (originalName != null && newName != null && packageName != null) {
                    showRenameSuccessNotification(originalName, newName, packageName)
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startMonitoring() {
        if (!isObserverRegistered) {
            try {
                contentResolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true, // 递归子项
                    observer
                )
                isObserverRegistered = true
                Log.d(TAG, "ContentObserver registered successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register ContentObserver", e)
            }
        }

        // 启动为前台服务，保证稳定运行
        startForegroundWithNotification()
    }

    private fun stopMonitoring() {
        if (isObserverRegistered) {
            try {
                contentResolver.unregisterContentObserver(observer)
                isObserverRegistered = false
                Log.d(TAG, "ContentObserver unregistered successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister ContentObserver", e)
            }
        }
        stopForeground(true)
        stopSelf()
    }

    private fun startForegroundWithNotification() {
        val notification = createForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundNotification(): Notification {
        val stopIntent = Intent(this, ScreenshotWatcherService::class.java).apply {
            action = ACTION_STOP_MONITORING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_service_title))
            .setContentText(getString(R.string.notification_service_content))
            .setSmallIcon(R.drawable.ic_screenshot)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(R.drawable.ic_stop, getString(R.string.notification_action_stop), stopPendingIntent)
            .build()
    }

    // 显示重命名成功通知
    private fun showRenameSuccessNotification(oldName: String, newName: String, packageName: String) {
        val appName = getAppNameFromPackage(packageName)

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_rename_success_title))
            .setContentText(getString(R.string.notification_rename_success_content, appName))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(getString(R.string.notification_rename_success_title))
                    .bigText(getString(R.string.notification_rename_success_big_text, appName, oldName, newName))
            )
            .setSmallIcon(R.drawable.ic_check)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val notificationManager = NotificationManagerCompat.from(this)
        try {
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                notificationManager.notify(System.currentTimeMillis().toInt(), notification)
            }
        } catch (e: SecurityException) {
            // 处理通知权限问题（Android 13+）
            Log.w(TAG, "通知权限未授予", e)
        }
    }

    private fun getAppNameFromPackage(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot get app name for package: $packageName", e)
            packageName // 如果获取不到应用名，返回包名
        }
    }

    private suspend fun handleMediaStoreChange(uri: Uri?) {
        try {
            // 查询最近的截图
            val screenshotInfo = ScreenshotUtils.queryRecentScreenshot(this, uri) ?: return
            val (screenshotUri, originalName) = screenshotInfo

            Log.d(TAG, "Found new screenshot: $originalName")

            // 获取当前前台应用包名
            val packageName = UsageStatsUtils.getTopPackageName(this) ?: run {
                Log.w(TAG, "Cannot get top package name")
                return
            }

            Log.d(TAG, "Top package: $packageName")

            // 执行重命名
            val success = renameWithPackageSuffix(this, screenshotUri, originalName, packageName)

            if (success) {
                // 生成新文件名用于通知显示
                val dot = originalName.lastIndexOf('.')
                val (base, ext) = if (dot > 0) originalName.substring(0, dot) to originalName.substring(dot)
                else originalName to ""
                val newName = "${base}_${packageName}${ext}"

                Log.d(TAG, "Screenshot renamed successfully: $originalName -> $newName")

                // 显示成功通知
                showRenameSuccessNotification(originalName, newName, packageName)
            } else {
                Log.w(TAG, "Failed to rename screenshot: $originalName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling media store change", e)
        }
    }

    private suspend fun renameWithPackageSuffix(
        context: Context,
        uri: Uri,
        originalName: String,
        pkg: String
    ): Boolean {
        // 已经改过名就跳过
        if (originalName.contains("_$pkg")) {
            Log.d(TAG, "Screenshot already renamed: $originalName")
            return true
        }

        val dot = originalName.lastIndexOf('.')
        val (base, ext) = if (dot > 0) originalName.substring(0, dot) to originalName.substring(dot)
        else originalName to ""

        val newName = "${base}_${pkg}${ext}"
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
        }

        return try {
            val rowsUpdated = context.contentResolver.update(uri, cv, null, null)
            rowsUpdated > 0
        } catch (e: RecoverableSecurityException) {
            Log.d(TAG, "RecoverableSecurityException occurred, requesting user permission")
            // 需要用户一次性授权后再执行
            requestWritePermission(context, uri, originalName, newName)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rename screenshot", e)
            false
        }
    }

    private fun requestWritePermission(context: Context, uri: Uri, originalName: String, newName: String) {
        try {
            // 获取包名（用于通知显示）
            val packageName = newName.substringAfterLast("_").substringBeforeLast(".")
            
            // 创建启动权限Activity的Intent
            val permissionIntent = Intent(context, PermissionActivity::class.java).apply {
                putExtra(PermissionActivity.EXTRA_URI, uri)
                putExtra(PermissionActivity.EXTRA_ORIGINAL_NAME, originalName)
                putExtra(PermissionActivity.EXTRA_NEW_NAME, newName)
                putExtra(PermissionActivity.EXTRA_PACKAGE_NAME, packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            
            val pendingIntent = PendingIntent.getActivity(
                context,
                System.currentTimeMillis().toInt(),
                permissionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // 创建通知，引导用户点击授权
            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.permission_write_title))
                .setContentText(getString(R.string.permission_write_content, originalName))
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .setBigContentTitle(getString(R.string.permission_write_title))
                        .bigText(getString(R.string.permission_write_big_text, originalName, newName))
                )
                .setSmallIcon(R.drawable.ic_screenshot)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            val notificationManager = NotificationManagerCompat.from(this)
            try {
                if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                    notificationManager.notify("write_permission_${System.currentTimeMillis()}".hashCode(), notification)
                    Log.d(TAG, "Write permission notification sent")
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "无法发送权限请求通知", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting write permission", e)
        }
    }

    override fun onDestroy() {
        if (isObserverRegistered) {
            try {
                contentResolver.unregisterContentObserver(observer)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering observer in onDestroy", e)
            }
        }
        super.onDestroy()
    }
}
