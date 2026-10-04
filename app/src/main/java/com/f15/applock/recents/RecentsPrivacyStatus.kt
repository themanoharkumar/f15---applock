package com.f15.applock.recents

/**
 * High-level status of Recents privacy and task preview protection on the active device.
 *
 * - [SUPPORTED]: The platform officially provides per-package snapshot protection.
 * - [PLATFORM_LIMITED]: The platform architecture isolates third-party window flags (FLAG_SECURE)
 *   and does not provide a per-package task snapshot API for arbitrary applications without
 *   imposing global device-wide restrictions. AppLock's own authentication screens and dashboard
 *   are fully protected.
 * - [DEGRADED]: A policy mechanism is partially active or degraded.
 * - [UNSUPPORTED]: Device Owner and Knox management are absent.
 */
enum class RecentsPrivacyStatus {
    SUPPORTED,
    PLATFORM_LIMITED,
    DEGRADED,
    UNSUPPORTED;

    val displayLabel: String
        get() = when (this) {
            SUPPORTED -> "Supported"
            PLATFORM_LIMITED -> "Platform Limited"
            DEGRADED -> "Degraded"
            UNSUPPORTED -> "Unsupported"
        }
}
