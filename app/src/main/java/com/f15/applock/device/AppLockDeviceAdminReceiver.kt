package com.f15.applock.device

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Admin Receiver for the App Lock DPC.
 *
 * Handles the Android device-administration lifecycle callbacks.
 * Business logic is delegated to [DeviceOwnerManager] — this receiver
 * only manages lifecycle transitions and logging.
 *
 * Declared in AndroidManifest.xml with the required device-admin metadata.
 */
class AppLockDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "AppLockDeviceAdmin"
        const val ACTION_DEV_REMOVE_DEVICE_OWNER = "com.f15.applock.action.DEV_REMOVE_DEVICE_OWNER"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DEV_REMOVE_DEVICE_OWNER) {
            Log.w(TAG, "[Recovery] Developer recovery broadcast received: clearing policies and Device Owner")
            try {
                // 1. Remove all Knox and DPM protection policies
                com.f15.applock.knox.KnoxManagerImpl.getInstance(context).removeAppProtection(context.packageName)
                // 2. Clear Device Owner
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                dpm.clearDeviceOwnerApp(context.packageName)
                Log.w(TAG, "[Recovery] Device Owner successfully cleared via developer broadcast")
            } catch (e: Exception) {
                Log.e(TAG, "[Recovery] Error executing developer recovery", e)
            }
            return
        }
        super.onReceive(context, intent)
    }

    /**
     * Called when the user has approved the device admin.
     */
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "[AERA/DevicePolicy] Device Admin ENABLED")
        DeviceOwnerManager.getInstance(context).refreshState()
    }

    /**
     * Called when the user has revoked the device admin.
     */
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "[AERA/DevicePolicy] Device Admin DISABLED")
        DeviceOwnerManager.getInstance(context).refreshState()
    }

    /**
     * Called after the device owner provisioning process is complete.
     * This indicates the application is now the Device Owner / DPC.
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "[AERA/DevicePolicy] Profile provisioning COMPLETE — Device Owner active")
        DeviceOwnerManager.getInstance(context).refreshState()
    }

    /**
     * Called when the user is about to disable the admin.
     * Returns a warning message displayed by the system.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.w(TAG, "[AERA/DevicePolicy] Device Admin disable REQUESTED")
        return "Disabling App Lock device administration will remove management policies. " +
                "App Lock enforcement via Accessibility Service will continue to function."
    }
}
