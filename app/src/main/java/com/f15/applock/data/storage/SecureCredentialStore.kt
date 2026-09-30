package com.f15.applock.data.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Hardware-backed secure credential storage for PIN verification.
 *
 * Implements PBKDF2WithHmacSHA256 key stretching (100,000 iterations) with a 256-bit cryptographically
 * secure salt, protected at rest using Android Keystore AES-256-GCM hardware encryption.
 * The plaintext PIN is never stored anywhere on the device.
 */
class SecureCredentialStore(private val context: Context) {

    private companion object {
        const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "F15_APPLOCK_KEY_MASTER"
        const val CREDENTIALS_FILE = "auth_credentials.bin"
        const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val ITERATION_COUNT = 100_000
        const val HASH_KEY_LENGTH = 256
        const val SALT_LENGTH_BYTES = 32
        const val GCM_TAG_LENGTH = 128
    }

    private val credentialsFile: File
        get() = File(context.noBackupFilesDir, CREDENTIALS_FILE)

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply {
            load(null)
        }
    }

    private fun getOrCreateMasterKey(): SecretKey {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE_PROVIDER
            )
            val parameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()

            keyGenerator.init(parameterSpec)
            return keyGenerator.generateKey()
        }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATION_COUNT, HASH_KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        return factory.generateSecret(spec).encoded
    }

    /**
     * Checks if a PIN has been securely configured.
     */
    fun isPinConfigured(): Boolean {
        return try {
            credentialsFile.exists() && credentialsFile.length() > 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Salts, hashes, and encrypts the new PIN using Android Keystore.
     *
     * @param pin The raw PIN (must be 4-8 digits).
     * @return True if saved successfully, false on error.
     */
    @Synchronized
    fun savePin(pin: String): Boolean {
        return try {
            val random = SecureRandom()
            val salt = ByteArray(SALT_LENGTH_BYTES)
            random.nextBytes(salt)

            val rawHash = hashPin(pin, salt)

            val masterKey = getOrCreateMasterKey()
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, masterKey)
            val iv = cipher.iv
            val encryptedHash = cipher.doFinal(rawHash)

            val data = "${Base64.encodeToString(salt, Base64.NO_WRAP)}:${Base64.encodeToString(iv, Base64.NO_WRAP)}:${Base64.encodeToString(encryptedHash, Base64.NO_WRAP)}"
            credentialsFile.writeText(data, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Verifies an input PIN candidate against the encrypted PBKDF2 hash using constant-time comparison.
     *
     * @param candidatePin The PIN to verify.
     * @return True if the PIN matches, false otherwise.
     */
    @Synchronized
    fun verifyPin(candidatePin: String): Boolean {
        return try {
            if (!credentialsFile.exists()) return false
            val content = credentialsFile.readText(Charsets.UTF_8)
            val parts = content.split(":")
            if (parts.size != 3) return false

            val salt = Base64.decode(parts[0], Base64.NO_WRAP)
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val encryptedHash = Base64.decode(parts[2], Base64.NO_WRAP)

            val masterKey = getOrCreateMasterKey()
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, masterKey, gcmSpec)
            val storedHash = cipher.doFinal(encryptedHash)

            val candidateHash = hashPin(candidatePin, salt)

            MessageDigest.isEqual(candidateHash, storedHash)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Deletes the stored credential token and the Keystore master key entry.
     */
    @Synchronized
    fun clearPin(): Boolean {
        return try {
            if (credentialsFile.exists()) {
                credentialsFile.delete()
            }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
