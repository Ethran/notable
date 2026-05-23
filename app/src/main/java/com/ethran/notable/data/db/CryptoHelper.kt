package com.ethran.notable.data.db

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class CryptoHelper @Inject constructor() {

    /**
     * Encrypts a plaintext string.
     * Returns Success with Base64-encoded string, or Error on cryptographic failure.
     */
    fun encrypt(plainText: String): AppResult<String, DomainError> {
        if (plainText.isBlank()) return AppResult.Success(plainText)

        return try {
            ensureKeyExists()
            val secret = getSecretKey() ?: return AppResult.Error(
                DomainError.UnexpectedState("Encryption key is not available in AndroidKeyStore.")
            )

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secret)

            val iv = cipher.iv
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            // Combine IV and CipherText, then encode to Base64
            val combined = iv + cipherText
            AppResult.Success(Base64.encodeToString(combined, Base64.DEFAULT))
        } catch (e: Exception) {
            AppResult.Error(DomainError.UnexpectedState("Encryption failed: ${e.localizedMessage}"))
        }
    }

    /**
     * Decrypts a Base64-encoded string back into plaintext.
     * Returns Success with plaintext, or Error on cryptographic failure.
     */
    fun decrypt(encryptedBase64: String): AppResult<String, DomainError> {
        if (encryptedBase64.isBlank()) return AppResult.Success(encryptedBase64)

        return try {
            ensureKeyExists()
            val data = Base64.decode(encryptedBase64, Base64.DEFAULT)
            val secret = getSecretKey() ?: return AppResult.Error(
                DomainError.UnexpectedState("Decryption key is not available in AndroidKeyStore.")
            )

            // GCM IV is always 12 bytes
            val iv = data.copyOfRange(0, 12)
            val cipherText = data.copyOfRange(12, data.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secret, spec)

            val decryptedBytes = cipher.doFinal(cipherText)
            AppResult.Success(String(decryptedBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            AppResult.Error(DomainError.UnexpectedState("Decryption failed: ${e.localizedMessage}"))
        }
    }

    @Throws(Exception::class)
    private fun ensureKeyExists() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) return

        val keyGenerator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }

    @Throws(Exception::class)
    private fun getSecretKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    companion object {
        private const val KEY_ALIAS = "com.ethran.notable.sync_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}