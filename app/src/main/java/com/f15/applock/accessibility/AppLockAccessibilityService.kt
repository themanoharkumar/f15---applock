package com.f15.applock.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.f15.applock.domain.model.DetectionSource
import com.f15.applock.security.LockController

/**
 * Dedicated Accessibility Service providing real-time, event-driven foreground application
 * detection for the Samsung Galaxy F15 5G on One UI 8.5.
 *
 * Privacy & Security Guarantees:
 * - Only listens to [AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED].
 * - Does NOT read screen text, passwords, or interact with any views (`canRetrieveWindowContent = false`).
 * - Never performs gestures, touches, or automated inputs on arbitrary apps.
 * - Forwards foreground package changes to [LockController] with zero polling battery drain.
 */
class AppLockAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AppLockA11yService"

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        @Volatile
        var instance: AppLockAccessibilityService? = null
            private set

        /**
         * Checks whether this AccessibilityService is enabled in Android Settings.
         */
        fun isAccessibilityPermissionGranted(context: Context): Boolean {
            val expectedComponent = ComponentName(context, AppLockAccessibilityService::class.java).flattenToString()
            val expectedShortComponent = ComponentName(context, AppLockAccessibilityService::class.java).flattenToShortString()

            // 1. Check via AccessibilityManager
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            if (am != null) {
                val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
                for (service in enabledServices) {
                    val id = service.id
                    if (id.equals(expectedComponent, ignoreCase = true) || id.equals(expectedShortComponent, ignoreCase = true)) {
                        return true
                    }
                }
            }

            // 2. Direct Settings.Secure fallback
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedComponent, ignoreCase = true) ||
                    componentName.equals(expectedShortComponent, ignoreCase = true)
                ) {
                    return true
                }
            }

            return false
        }

        /**
         * Intent navigating to the Android Accessibility settings screen.
         */
        fun getAccessibilitySettingsIntent(): Intent {
            return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }



    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 0
        info.flags = 0
        serviceInfo = info

        isServiceRunning = true
        LockController.getInstance(applicationContext).setAccessibilityEnabled(true)
        Log.i(TAG, "[AERA-APPLOCK] Accessibility Service connected (lean, zero-latency event dispatcher)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()?.trim()
            if (!pkg.isNullOrEmpty()) {
                val className = event.className?.toString()?.trim()
                val isFullScreen = event.isFullScreen
                val eventTime = event.eventTime
                val receivedTime = SystemClock.uptimeMillis()
                LockController.getInstance(applicationContext)
                    .onWindowStateChanged(pkg, className, isFullScreen, DetectionSource.ACCESSIBILITY, eventTime, receivedTime)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "[AERA-APPLOCK] Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isServiceRunning = false
        LockController.getInstance(applicationContext).setAccessibilityEnabled(false)
        Log.i(TAG, "[AERA-APPLOCK] Accessibility Service destroyed")
    }

    /**
     * Executes standard Android Home action to safely exit a protected app if needed.
     */
    fun performGoHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Executes standard Android Back action.
     */
    fun performGoBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }
}
