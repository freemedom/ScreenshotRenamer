package com.screenshotrenamer

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import android.widget.TextView

class MainActivity : AppCompatActivity() {
    private var isMonitoring = false
    private lateinit var toggleButton: MaterialButton
    private lateinit var statusText: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted && checkUsageStatsPermission()) {
            startMonitoring()
        } else if (!checkUsageStatsPermission()) {
            showUsageStatsPermissionDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        updateUI()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        // 用户可能从设置页面返回，重新检查监控状态
        updateUI()
    }

    private fun initViews() {
        toggleButton = findViewById(R.id.btn_toggle_monitoring)
        statusText = findViewById(R.id.tv_status)
    }

    private fun setupClickListeners() {
        toggleButton.setOnClickListener {
            if (isMonitoring) {
                stopMonitoring()
            } else {
                if (checkAllPermissions()) {
                    startMonitoring()
                } else {
                    requestPermissions()
                }
            }
        }
    }

    private fun startMonitoring() {
        // 最终检查所有权限
        if (!checkAllPermissions()) {
            requestPermissions()
            return
        }

        // 启动前台服务
        val intent = Intent(this, ScreenshotWatcherService::class.java)
        intent.action = ScreenshotWatcherService.ACTION_START_MONITORING
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        isMonitoring = true
        updateUI()
        Toast.makeText(this, R.string.toast_monitoring_started, Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoring() {
        val intent = Intent(this, ScreenshotWatcherService::class.java)
        intent.action = ScreenshotWatcherService.ACTION_STOP_MONITORING
        stopService(intent)

        isMonitoring = false
        updateUI()
        Toast.makeText(this, R.string.toast_monitoring_stopped, Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        if (isMonitoring) {
            toggleButton.text = getString(R.string.btn_stop_monitoring)
            toggleButton.setIconResource(R.drawable.ic_stop)
            statusText.text = getString(R.string.status_running)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.status_running))
        } else {
            toggleButton.text = getString(R.string.btn_start_monitoring)
            toggleButton.setIconResource(R.drawable.ic_screenshot)
            statusText.text = getString(R.string.status_stopped)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.status_stopped))
        }
    }

    private fun checkAllPermissions(): Boolean {
        // 检查媒体权限
        val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        // 检查通知权限（Android 13+）
        val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            PackageManager.PERMISSION_GRANTED // 旧版本默认有通知权限
        }

        // 检查媒体位置权限（Android 10+）
        val mediaLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION)
        } else {
            PackageManager.PERMISSION_GRANTED // 旧版本不需要此权限
        }

        // 检查媒体管理权限（Android 13+）
        val manageMediaPermission = checkManageMediaPermission()

        // 检查使用情况访问权限
        val usagePermission = checkUsageStatsPermission()

        return mediaPermission == PackageManager.PERMISSION_GRANTED &&
                notificationPermission == PackageManager.PERMISSION_GRANTED &&
                mediaLocationPermission == PackageManager.PERMISSION_GRANTED &&
                manageMediaPermission &&
                usagePermission
    }

    private fun checkUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun checkManageMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+：检查MANAGE_MEDIA权限
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                "android:manage_media", // 使用字符串常量代替OPSTR_MANAGE_MEDIA
                Process.myUid(), packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } else {
            // Android 13以下不需要此权限检查
            true
        }
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // 检查媒体权限
        val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, mediaPermission) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(mediaPermission)
        }

        // 检查通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 检查媒体位置权限（Android 10+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else if (!checkManageMediaPermission()) {
            showManageMediaPermissionDialog()
        } else if (!checkUsageStatsPermission()) {
            showUsageStatsPermissionDialog()
        }
    }

    private fun showManageMediaPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要媒体管理权限")
            .setMessage("为了直接重命名截图文件而无需每次授权，需要在设置中开启\"媒体管理\"权限")
            .setPositiveButton(R.string.permission_go_settings) { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    // 如果无法直接跳转，则跳转到应用信息页面
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                }
            }
            .setNegativeButton(R.string.permission_cancel, null)
            .show()
    }

    private fun showUsageStatsPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_usage_stats_title)
            .setMessage(R.string.permission_usage_stats_message)
            .setPositiveButton(R.string.permission_go_settings) { _, _ ->
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                startActivity(intent)
            }
            .setNegativeButton(R.string.permission_cancel, null)
            .show()
    }
}
