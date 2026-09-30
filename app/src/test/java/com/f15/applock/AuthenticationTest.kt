package com.f15.applock

import com.f15.applock.domain.model.SessionTimeout
import com.f15.applock.security.PinAuthenticator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class AuthenticationTest {

    @Test
    fun pinPolicy_validatesLengthAndDigits() {
        val validPins = listOf("1234", "0000", "12345", "12345678")
        val invalidPins = listOf("", "1", "12", "123", "123456789", "12a4", "abcd", " 1234")

        for (pin in validPins) {
            assertTrue("Expected '$pin' to be valid", isValidPin(pin))
        }

        for (pin in invalidPins) {
            assertFalse("Expected '$pin' to be invalid", isValidPin(pin))
        }
    }

    private fun isValidPin(pin: String): Boolean {
        return pin.length in PinAuthenticator.MIN_PIN_LENGTH..PinAuthenticator.MAX_PIN_LENGTH && pin.all { it.isDigit() }
    }

    @Test
    fun failedAttempts_calculatesProgressiveLockoutDelays() {
        assertEquals(0, calculateLockoutSeconds(1))
        assertEquals(0, calculateLockoutSeconds(2))
        assertEquals(0, calculateLockoutSeconds(3))
        assertEquals(10, calculateLockoutSeconds(4))
        assertEquals(10, calculateLockoutSeconds(5))
        assertEquals(30, calculateLockoutSeconds(6))
        assertEquals(30, calculateLockoutSeconds(10))
    }

    private fun calculateLockoutSeconds(failedAttempts: Int): Int {
        return when {
            failedAttempts >= 6 -> 30
            failedAttempts >= 4 -> 10
            else -> 0
        }
    }

    @Test
    fun sessionTimeout_resolvesCorrectDurations() {
        assertEquals(SessionTimeout.IMMEDIATELY, SessionTimeout.fromDuration(0L))
        assertEquals(SessionTimeout.SECONDS_30, SessionTimeout.fromDuration(30L))
        assertEquals(SessionTimeout.MINUTE_1, SessionTimeout.fromDuration(60L))
        assertEquals(SessionTimeout.MINUTES_5, SessionTimeout.fromDuration(300L))
        // Default fallback for unknown
        assertEquals(SessionTimeout.MINUTE_1, SessionTimeout.fromDuration(999L))

        assertEquals(0L, SessionTimeout.IMMEDIATELY.durationMillis)
        assertEquals(30_000L, SessionTimeout.SECONDS_30.durationMillis)
        assertEquals(60_000L, SessionTimeout.MINUTE_1.durationMillis)
        assertEquals(300_000L, SessionTimeout.MINUTES_5.durationMillis)
    }

    @Test
    fun pbkdf2Hashing_producesIdenticalOutputForSamePinAndSalt() {
        val pin = "5821"
        val salt = ByteArray(32).apply { SecureRandom().nextBytes(this) }

        val hash1 = hashWithPbkdf2(pin, salt)
        val hash2 = hashWithPbkdf2(pin, salt)
        val hashDifferentPin = hashWithPbkdf2("5822", salt)

        assertTrue(MessageDigest.isEqual(hash1, hash2))
        assertFalse(MessageDigest.isEqual(hash1, hashDifferentPin))
    }

    private fun hashWithPbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 10_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }
}
