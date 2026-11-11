package com.itmanager.app.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the API tokens (the main and the backup server's) encrypted in the app's storage: AES-GCM with a key that
 * lives in the Android Keystore and never leaves it, so a copy of the app's data is not a copy of the tokens.
 * Text that does not start with the marker is a token written by an older version: it is read as it is and encrypted
 * the next time it is saved.
 */
object TokenCipher {
    private const val ALIAS = "itmanager_token_key"
    private const val PREFIX = "enc1:"

    fun isEncrypted(stored: String) = stored.startsWith(PREFIX)

    /** The token as it is stored. Without a working Keystore the token is kept as it is rather than lost. */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP)
        }.getOrDefault(plain)
    }

    /** The token; empty when it cannot be read (the key is gone after a restore): the user enters it again. */
    fun decrypt(stored: String): String {
        if (!isEncrypted(stored)) return stored
        return runCatching {
            val (iv, sealed) = stored.removePrefix(PREFIX).split(':').map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(sealed), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
