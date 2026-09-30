package com.f15.applock.knox

/**
 * Structured outcome of a Samsung Knox or Android Enterprise management policy operation.
 */
sealed class KnoxResult {
    data class Success(val message: String) : KnoxResult()
    data object NotDeviceOwner : KnoxResult()
    data object Unsupported : KnoxResult()
    data class LicenseRequired(val details: String) : KnoxResult()
    data class SecurityError(val message: String) : KnoxResult()
    data class Failed(val reason: String, val throwable: Throwable? = null) : KnoxResult()

    val isSuccess: Boolean get() = this is Success
}
