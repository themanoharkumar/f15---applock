package com.f15.applock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.f15.applock.data.security.SecurityEventLogger
import com.f15.applock.data.security.SecurityEventType
import com.f15.applock.data.storage.AppLockPreferences
import com.f15.applock.detection.ForegroundAppDetector
import com.f15.applock.device.DeviceOwnerManager
import com.f15.applock.service.AppMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manifest-registered BroadcastReceiver triggered on system reboot [Intent.ACTION_BOOT_COMPLETED]
 * and app update [Intent.ACTION_MY_PACKAGE_REPLACED].
 *
 * Restores App Lock monitoring configuration and writes a secure boot recovery entry to the audit log.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.i(TAG, "[AERA-APPLOCK] System boot / update event received: $action")
            SecurityEventLogger.log(
                SecurityEventType.DEVICE_BOOT_RESTORED,
                "Device reboot completed. Restoring App Lock configuration and operational states."
            )

            // Verify if Usage Access is granted and resume background watchdog if appropriate
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    // Phase 6: Reconcile device management state with platform
                    DeviceOwnerManager.getInstance(context.applicationContext).reconcileOnStartup()

                    // Phase 7: Reconcile Samsung Knox & Device Owner application protection
                    com.f15.applock.knox.KnoxManagerImpl.getInstance(context.applicationContext)
                        .reconcilePolicies(context.packageName)

                    // Phase 8: Reconcile system-wide security posture and audit state
                    com.f15.applock.security.SecurityStateManager.getInstance(context.applicationContext)
                        .reconcileOnStartup()

                    val detector = ForegroundAppDetector(context.applicationContext)
                    val prefs = AppLockPreferences(context.applicationContext)
                    val lockedCount = prefs.getLockedPackages().size

                    if (detector.hasUsageAccessPermission() && lockedCount > 0) {
                        AppMonitorService.start(context.applicationContext)
                        Log.i(TAG, "Restored AppMonitorService after boot ($lockedCount protected apps)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore monitoring service after boot", e)
                }
            }
        }
    }
}
