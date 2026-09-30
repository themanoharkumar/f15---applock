package com.f15.applock.domain.model

import android.graphics.drawable.Drawable

/**
 * Domain representation of an installed application.
 *
 * @property packageName Unique application package identifier (e.g. "com.whatsapp").
 * @property appName User-facing label (e.g. "WhatsApp").
 * @property icon Application icon drawable (null if unavailable; fallback used in UI).
 * @property isLocked Whether the application is marked as protected.
 * @property isSystemApp Indicates whether the package is pre-installed/system-level.
 */
data class InstalledApp(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null,
    val isLocked: Boolean = false,
    val isSystemApp: Boolean = false
)
