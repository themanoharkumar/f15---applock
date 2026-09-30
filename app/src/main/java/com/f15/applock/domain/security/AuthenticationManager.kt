package com.f15.applock.domain.security

/**
 * Contract for core authentication and credential operations:
 * PIN setup, verification, modification, and removal.
 */
interface AuthenticationManager {

    suspend fun isPinConfigured(): Boolean

    suspend fun createPin(pin: String): Result<Unit>

    suspend fun verifyPin(pin: String): Boolean

    suspend fun changePin(currentPin: String, newPin: String): Result<Unit>

    suspend fun removePin(currentPin: String): Result<Unit>
}
