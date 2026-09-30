package com.f15.applock.security

import com.f15.applock.data.storage.SecureCredentialStore
import com.f15.applock.domain.security.AuthenticationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Concrete implementation of [AuthenticationManager] utilizing [SecureCredentialStore].
 * Enforces PIN length policies (4 to 8 digits) and ensures cryptographic operations
 * run on [Dispatchers.Default].
 */
class PinAuthenticator(
    private val credentialStore: SecureCredentialStore
) : AuthenticationManager {

    companion object {
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 8
    }

    private fun isValidPinFormat(pin: String): Boolean {
        return pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it.isDigit() }
    }

    override suspend fun isPinConfigured(): Boolean = withContext(Dispatchers.IO) {
        credentialStore.isPinConfigured()
    }

    override suspend fun createPin(pin: String): Result<Unit> = withContext(Dispatchers.Default) {
        if (!isValidPinFormat(pin)) {
            return@withContext Result.failure(
                IllegalArgumentException("PIN must be between $MIN_PIN_LENGTH and $MAX_PIN_LENGTH digits")
            )
        }
        val success = credentialStore.savePin(pin)
        if (success) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Failed to securely store PIN"))
        }
    }

    override suspend fun verifyPin(pin: String): Boolean = withContext(Dispatchers.Default) {
        if (!isValidPinFormat(pin)) {
            return@withContext false
        }
        credentialStore.verifyPin(pin)
    }

    override suspend fun changePin(currentPin: String, newPin: String): Result<Unit> = withContext(Dispatchers.Default) {
        if (!verifyPin(currentPin)) {
            return@withContext Result.failure(SecurityException("Current PIN is incorrect"))
        }
        if (!isValidPinFormat(newPin)) {
            return@withContext Result.failure(
                IllegalArgumentException("New PIN must be between $MIN_PIN_LENGTH and $MAX_PIN_LENGTH digits")
            )
        }
        val success = credentialStore.savePin(newPin)
        if (success) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Failed to securely store new PIN"))
        }
    }

    override suspend fun removePin(currentPin: String): Result<Unit> = withContext(Dispatchers.Default) {
        if (!verifyPin(currentPin)) {
            return@withContext Result.failure(SecurityException("Current PIN is incorrect"))
        }
        val success = credentialStore.clearPin()
        if (success) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Failed to remove PIN"))
        }
    }
}
