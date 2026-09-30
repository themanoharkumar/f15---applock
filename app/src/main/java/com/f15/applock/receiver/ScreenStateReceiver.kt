package com.f15.applock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.f15.applock.data.security.SecurityEventLogger
import com.f15.applock.data.security.SecurityEventType
import com.f15.applock.security.LockController

/**
 * Dynamically registered BroadcastReceiver listening for [Intent.ACTION_SCREEN_OFF].
 *
 * When the phone screen turns off (e.g. user taps the power button or screen times out),
 * this receiver immediately triggers [LockController.onScreenOff] to invalidate or expire
 * active authorizations according to session timeout policy, closing the "screen off/on" bypass.
 */
class ScreenStateReceiver(private val lockController: LockController) : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenStateReceiver"

        fun register(context: Context, lockController: LockController): ScreenStateReceiver {
            val receiver = ScreenStateReceiver(lockController)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            context.registerReceiver(receiver, filter)
            Log.d(TAG, "ScreenStateReceiver registered for ACTION_SCREEN_OFF")
            return receiver
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_SCREEN_OFF) {
            Log.i(TAG, "[AERA-APPLOCK] Screen turned off - re-evaluating app authorizations")
            SecurityEventLogger.log(
                SecurityEventType.SCREEN_OFF_LOCK,
                "Device screen locked/turned off - expiring active app sessions"
            )
            lockController.onScreenOff()
        }
    }
}
