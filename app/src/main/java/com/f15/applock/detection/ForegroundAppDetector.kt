package com.f15.applock.detection

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * Reusable detector querying Android's [UsageStatsManager] to determine the current
 * foreground application package without exposing platform details to the caller.
 */
class ForegroundAppDetector(private val context: Context) {

    private val usageStatsManager: UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    /**
     * Checks whether the user has granted Usage Access (PACKAGE_USAGE_STATS).
     */
    fun hasUsageAccessPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Returns an intent targeting Android's Usage Access Settings screen.
     */
    fun getUsageAccessSettingsIntent(): Intent {
        return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * Queries [UsageEvents] over the last 15 seconds to locate the most recently resumed package.
     *
     * @return Package name of the active foreground application, or null if unavailable.
     */
    fun getForegroundPackage(): String? {
        if (!hasUsageAccessPermission() || usageStatsManager == null) {
            return null
        }

        return try {
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 15_000L

            val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()

            var latestForegroundPackage: String? = null
            var latestTimestamp = 0L

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                val type = event.eventType
                if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (event.timeStamp >= latestTimestamp) {
                        latestForegroundPackage = event.packageName
                        latestTimestamp = event.timeStamp
                    }
                }
            }

            latestForegroundPackage
        } catch (e: Exception) {
            null
        }
    }
}
