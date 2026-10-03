package com.f15.applock.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Metadata cache for protected application labels and icons.
 *
 * Prevents synchronous main-thread APK decompression and package manager IPC
 * during [com.f15.applock.ui.activity.LockScreenActivity.onCreate].
 */
data class TargetAppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val activityClasses: Set<String> = emptySet()
)

object AppTargetCache {
    private const val TAG = "AppTargetCache"

    private val cache = ConcurrentHashMap<String, TargetAppInfo>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Resolves app metadata synchronously if cached, or returns a lightweight fallback
     * while scheduling an asynchronous background prefetch. Never blocks the main thread.
     */
    fun getTargetInfo(context: Context, packageName: String): TargetAppInfo {
        if (packageName.isBlank()) {
            return TargetAppInfo("", "", null)
        }

        val cached = cache[packageName]
        if (cached != null) {
            return cached
        }

        // Fast fallback for Frame 0 (unblocked UI)
        val fallbackLabel = try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        val fallback = TargetAppInfo(packageName, fallbackLabel, null)

        // Asynchronously load the icon and activity classes in background without stalling onCreate
        scope.launch {
            loadAndCache(context, packageName)
        }

        return fallback
    }

    /**
     * Pre-warms the cache in background for all user-locked applications.
     */
    fun prewarm(context: Context, packages: Set<String>) {
        scope.launch {
            for (pkg in packages) {
                if (!cache.containsKey(pkg)) {
                    loadAndCache(context, pkg)
                }
            }
        }
    }

    /**
     * Checks if a given class name represents an Activity within the target package.
     * Uses authoritative PackageInfo activities cache with fallback to standard Android naming patterns.
     */
    fun isActivity(packageName: String, className: String?): Boolean {
        if (className.isNullOrBlank()) return false

        // Common layout views, popups, and notification frames are NEVER activities
        if (isCommonView(className)) return false

        val cached = cache[packageName]
        if (cached != null && cached.activityClasses.isNotEmpty()) {
            if (cached.activityClasses.contains(className)) return true
            if (className.startsWith(".") && cached.activityClasses.contains("$packageName$className")) return true
        }

        // Standard Activity heuristics
        if (className.contains("Activity") || className.endsWith(".Main") || className.endsWith(".MainActivity")) {
            return true
        }

        // If it belongs to the package namespace and is not a common widget view
        return className.startsWith(packageName) && !isCommonView(className)
    }

    /**
     * Identifies common Android widget layouts, DecorViews, popups, and notification wrappers
     * that are never user activities.
     */
    fun isCommonView(className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        return className.startsWith("android.widget.") ||
                className.startsWith("android.view.") ||
                className.startsWith("com.android.internal.") ||
                className.contains("DecorView") ||
                className.contains("PopupWindow") ||
                className.contains("Toast") ||
                className.contains("ListPopupWindow") ||
                className.contains("MenuPopupWindow") ||
                className.contains("SoftInputWindow") ||
                className.contains("Notification") ||
                className.contains("Floating") ||
                className.contains("Popup")
    }

    private fun loadAndCache(context: Context, packageName: String) {
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            val icon = pm.getApplicationIcon(packageName)

            val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                try {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
                } catch (_: Exception) {
                    null
                }
            } else {
                @Suppress("DEPRECATION")
                try {
                    pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
                } catch (_: Exception) {
                    null
                }
            }

            val activities = packageInfo?.activities?.mapNotNull { it.name }?.toSet() ?: emptySet()
            cache[packageName] = TargetAppInfo(packageName, label, icon, activities)
            Log.d(TAG, "Prewarmed metadata and ${activities.size} activities for '$packageName'")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve metadata for '$packageName'", e)
        }
    }
}
