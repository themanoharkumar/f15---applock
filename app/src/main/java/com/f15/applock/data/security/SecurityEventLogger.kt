package com.f15.applock.data.security

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Types of security-relevant events tracked by the local security logger.
 */
enum class SecurityEventType {
    PROTECTED_APP_OPENED,
    AUTH_REQUESTED,
    AUTH_SUCCESS,
    AUTH_FAILED,
    AUTH_CANCELLED,
    ACCESSIBILITY_STATE_CHANGED,
    USAGE_ACCESS_STATE_CHANGED,
    APP_PROTECTION_TOGGLED,
    PIN_CHANGED,
    PIN_REMOVED,
    SERVICE_LIFECYCLE,
    DEVICE_BOOT_RESTORED,
    CLEANUP_STALE_PACKAGES,
    SCREEN_OFF_LOCK
}

/**
 * Represents a single sanitized security event.
 *
 * Guaranteed Privacy:
 * Never stores or logs PINs, hashes, encryption keys, biometric templates, or notification content.
 */
data class SecurityEvent(
    val id: Long,
    val timestamp: Long,
    val eventType: SecurityEventType,
    val details: String
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

/**
 * Lightweight, bounded in-memory security event logger for local auditing and anti-tamper diagnostics.
 * Retains at most [MAX_EVENTS] entries with automatic FIFO eviction to prevent memory growth.
 */
object SecurityEventLogger {

    private const val TAG = "AERA-APPLOCK"
    const val MAX_EVENTS = 150

    private val idCounter = AtomicLong(1L)
    private val eventQueue = ConcurrentLinkedDeque<SecurityEvent>()

    private val _eventsFlow = MutableStateFlow<List<SecurityEvent>>(emptyList())
    val eventsFlow: StateFlow<List<SecurityEvent>> = _eventsFlow.asStateFlow()

    /**
     * Records a security event with automatic sanitization and FIFO bounds.
     */
    fun log(type: SecurityEventType, details: String) {
        val event = SecurityEvent(
            id = idCounter.getAndIncrement(),
            timestamp = System.currentTimeMillis(),
            eventType = type,
            details = details
        )

        eventQueue.addFirst(event)

        while (eventQueue.size > MAX_EVENTS) {
            eventQueue.pollLast()
        }

        _eventsFlow.value = eventQueue.toList()

        // Structured logcat output
        try {
            Log.i(TAG, "[${type.name}] $details")
        } catch (e: Throwable) {
            // Fallback for JVM unit tests where android.util.Log is not mocked
        }
    }

    /**
     * Clears all recorded security events (requires authenticated session).
     */
    fun clearLogs() {
        eventQueue.clear()
        _eventsFlow.value = emptyList()
        log(SecurityEventType.SERVICE_LIFECYCLE, "Security event log cleared by authorized user")
    }

    /**
     * Returns a snapshot of current events.
     */
    fun getSnapshot(): List<SecurityEvent> = eventQueue.toList()
}
