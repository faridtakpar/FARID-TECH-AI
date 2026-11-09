
package com.faridtech.ai.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

class OwnerSecurity(context: Context) {

    private val prefs = context.getSharedPreferences(
        "owner_security",
        Context.MODE_PRIVATE
    )

    private val alias = "FaridTechOwnerKey"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        val existing = keyStore.getKey(alias, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            "AndroidKeyStore"
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )

        return generator.generateKey()
    }

    private fun calculateVerifier(
        password: String,
        salt: ByteArray
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(getOrCreateKey())
        mac.update(salt)
        return mac.doFinal(password.toByteArray(Charsets.UTF_8))
    }

    fun isConfigured(): Boolean {
        return prefs.contains("owner_hash") &&
            prefs.contains("owner_salt")
    }

    fun setupPassword(password: String): Boolean {
        if (isConfigured() || password.length < 12) return false

        return try {
            val salt = ByteArray(16)
            SecureRandom().nextBytes(salt)

            val verifier = calculateVerifier(password, salt)

            prefs.edit()
                .putString(
                    "owner_salt",
                    Base64.encodeToString(salt, Base64.NO_WRAP)
                )
                .putString(
                    "owner_hash",
                    Base64.encodeToString(verifier, Base64.NO_WRAP)
                )
                .putInt("failed_attempts", 0)
                .putLong("lock_until", 0L)
                .commit()
        } catch (e: Exception) {
            false
        }
    }

    fun verifyPassword(password: String): Boolean {
        if (!isConfigured()) return false

        val now = System.currentTimeMillis()
        val lockUntil = prefs.getLong("lock_until", 0L)

        if (now < lockUntil) return false

        return try {
            val salt = Base64.decode(
                prefs.getString("owner_salt", null),
                Base64.NO_WRAP
            )

            val expected = Base64.decode(
                prefs.getString("owner_hash", null),
                Base64.NO_WRAP
            )

            val actual = calculateVerifier(password, salt)

            val valid = MessageDigest.isEqual(expected, actual)

            if (valid) {
                prefs.edit()
                    .putInt("failed_attempts", 0)
                    .putLong("lock_until", 0L)
                    .apply()
            } else {
                val attempts = prefs.getInt("failed_attempts", 0) + 1

                val editor = prefs.edit()
                    .putInt("failed_attempts", attempts)

                if (attempts >= 5) {
                    editor.putLong(
                        "lock_until",
                        now + 15 * 60 * 1000L
                    )
                    editor.putInt("failed_attempts", 0)
                }

                editor.apply()
            }

            valid
        } catch (e: Exception) {
            false
        }
    }

    fun remainingLockMillis(): Long {
        return (
            prefs.getLong("lock_until", 0L) -
                System.currentTimeMillis()
            ).coerceAtLeast(0L)
    }
}
