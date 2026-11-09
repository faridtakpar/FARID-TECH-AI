package com.faridtech.ai.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.Mac
import java.security.SecureRandom

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

    private fun hashPassword(password: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(getOrCreateKey())

        val result = mac.doFinal(password.toByteArray(Charsets.UTF_8))
        return result.joinToString("") { "%02x".format(it) }
    }

    fun isConfigured(): Boolean {
        return prefs.contains("owner_hash")
    }

    fun setupPassword(password: String): Boolean {
        if (isConfigured()) return false
        if (password.length < 12) return false

        val hash = hashPassword(password)

        return prefs.edit()
            .putString("owner_hash", hash)
            .putInt("failed_attempts", 0)
            .commit()
    }

    fun verifyPassword(password: String): Boolean {
        if (!isConfigured()) return false

        val attempts = prefs.getInt("failed_attempts", 0)
        if (attempts >= 5) return false

        val expected = prefs.getString("owner_hash", null) ?: return false
        val actual = hashPassword(password)

        val valid = java.security.MessageDigest.isEqual(
            expected.toByteArray(),
            actual.toByteArray()
        )

        if (valid) {
            prefs.edit().putInt("failed_attempts", 0).apply()
        } else {
            prefs.edit().putInt("failed_attempts", attempts + 1).apply()
        }

        return valid
    }
}
