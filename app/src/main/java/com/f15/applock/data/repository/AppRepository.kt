package com.f15.applock.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import com.f15.applock.domain.model.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository responsible for querying PackageManager, mapping ApplicationInfo/ResolveInfo
 * into [InstalledApp] domain models, filtering launchable applications, and caching the
 * discovered apps in memory during the session.
 */
class AppRepository(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager

    // Session cache to prevent redundant PackageManager queries during recompositions
    private var cachedApps: List<InstalledApp>? = null

    /**
     * Loads installed launchable applications off the main thread.
     *
     * @param forceRefresh Whether to invalidate the in-memory session cache.
     * @param includeNonLaunchableSystemApps Reserved for future phases to support headless system services.
     * @return List of [InstalledApp] sorted alphabetically by app name.
     */
    suspend fun getInstalledApps(
        forceRefresh: Boolean = false,
        includeNonLaunchableSystemApps: Boolean = false
    ): List<InstalledApp> = withContext(Dispatchers.IO) {
        if (!forceRefresh && cachedApps != null) {
            return@withContext cachedApps!!
        }

        val myPackageName = context.packageName
        val apps = mutableListOf<InstalledApp>()
        val seenPackages = mutableSetOf<String>()

        try {
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolveInfoList: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    mainIntent,
                    PackageManager.ResolveInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(mainIntent, 0)
            }

            for (resolveInfo in resolveInfoList) {
                val activityInfo = resolveInfo.activityInfo ?: continue
                val pkgName = activityInfo.packageName ?: continue

                // Exclude this app lock itself and avoid duplicates
                if (pkgName == myPackageName || seenPackages.contains(pkgName)) {
                    continue
                }

                seenPackages.add(pkgName)

                val appName = try {
                    val label = resolveInfo.loadLabel(packageManager)?.toString()?.trim()
                    if (!label.isNullOrEmpty()) {
                        label
                    } else {
                        activityInfo.applicationInfo?.loadLabel(packageManager)?.toString()?.trim() ?: pkgName
                    }
                } catch (e: Exception) {
                    pkgName
                }

                val icon = try {
                    resolveInfo.loadIcon(packageManager)
                        ?: activityInfo.applicationInfo?.loadIcon(packageManager)
                } catch (e: Exception) {
                    null
                }

                val isSystem = try {
                    activityInfo.applicationInfo?.let { appInfo ->
                        (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                        (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    } ?: false
                } catch (e: Exception) {
                    false
                }

                apps.add(
                    InstalledApp(
                        packageName = pkgName,
                        appName = appName,
                        icon = icon,
                        isLocked = false,
                        isSystemApp = isSystem
                    )
                )
            }

            if (includeNonLaunchableSystemApps) {
                // Extensible hook for future system applications support
            }

        } catch (e: Exception) {
            // Guard against unexpected PackageManager failures
        }

        val sortedList = apps.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName })
        cachedApps = sortedList
        sortedList
    }

    /**
     * Clears the in-memory cache to force an updated query on next request.
     */
    fun clearCache() {
        cachedApps = null
    }
}
