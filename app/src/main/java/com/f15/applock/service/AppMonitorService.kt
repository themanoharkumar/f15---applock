package com.f15.applock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.f15.applock.MainActivity
import com.f15.applock.R
import com.f15.applock.data.storage.AppLockPreferences
import com.f15.applock.detection.ForegroundAppDetector
import com.f15.applock.detection.ForegroundAppMonitor
import com.f15.applock.security.LockDecisionManager
import com.f15.applock.security.SessionManager
import com.f15.applock.ui.activity.LockScreenActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service running [ForegroundAppMonitor] continuously in the background
 * with structured lifecycle handling and specialUse foreground service declaration.
 */
class AppMonitorService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "app_lock_monitor_channel"

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        var activeMonitor: ForegroundAppMonitor? = null
            private set

        var activeLockDecisionManager: LockDecisionManager? = null
            private set

        /**
         * Starts the foreground monitoring service.
         */
        fun start(context: Context) {
            val intent = Intent(context, AppMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stops the foreground monitoring service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, AppMonitorService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val lockController = com.f15.applock.security.LockController.getInstance(applicationContext)
        val lockDecisionManager = lockController.lockDecisionManager
        val detector = ForegroundAppDetector(applicationContext)
        val monitor = ForegroundAppMonitor(applicationContext, detector, lockDecisionManager)

        activeLockDecisionManager = lockDecisionManager
        activeMonitor = monitor

        monitor.startMonitoring()
        _isRunning.value = true

        serviceScope.launch {
            monitor.lockRequests.collect { lockRequest ->
                // Only launch if AccessibilityService is not actively running
                if (!com.f15.applock.accessibility.AppLockAccessibilityService.isServiceRunning) {
                    val intent = Intent(this@AppMonitorService, LockScreenActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(LockScreenActivity.EXTRA_PACKAGE_NAME, lockRequest.packageName)
                    }
                    startActivity(intent)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeMonitor?.stopMonitoring()
        activeMonitor = null
        activeLockDecisionManager = null
        _isRunning.value = false
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.monitoring_notification_title))
            .setContentText(getString(R.string.monitoring_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }
}
