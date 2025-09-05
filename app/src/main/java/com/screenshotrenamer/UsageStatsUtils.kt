package com.screenshotrenamer

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log

object UsageStatsUtils {
    private const val TAG = "UsageStatsUtils"

    /**
     * 获取当前前台应用的包名
     * @param context 上下文
     * @return 前台应用包名，如果获取失败返回null
     */
    @SuppressLint("WrongConstant")
    fun getTopPackageName(context: Context): String? {
        try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null

            val end = System.currentTimeMillis()
            val begin = end - 60_000 // 最近 1 分钟

            val events = usageStatsManager.queryEvents(begin, end)
            val event = UsageEvents.Event()
            var topPackage: String? = null
            var latestTime = 0L

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    // 记录最新的前台事件
                    if (event.timeStamp > latestTime) {
                        latestTime = event.timeStamp
                        topPackage = event.packageName
                    }
                }
            }

            if (topPackage != null) {
                Log.d(TAG, "Found top package: $topPackage at time: $latestTime")
            } else {
                Log.w(TAG, "No foreground app found in recent usage events")
            }

            return topPackage
        } catch (e: Exception) {
            Log.e(TAG, "Error getting top package name", e)
            return null
        }
    }

    /**
     * 检查是否有使用统计权限
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return false

            val end = System.currentTimeMillis()
            val begin = end - 60_000

            // 尝试查询使用统计，如果没有权限会返回空
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                begin,
                end
            )

            return stats.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking usage stats permission", e)
            return false
        }
    }
}
