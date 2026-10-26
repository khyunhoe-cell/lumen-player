package com.lumen.player.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 서버 비밀번호와 API 키를 Android Keystore의 AES-GCM 키로 암호화한다.
 * 저장 형식: "v1:" + base64(iv) + ":" + base64(ciphertext)
 * Keystore를 쓸 수 없는 기기에서는 "p1:" + base64(평문)으로 남긴다(앱 전용 저장소라 다른 앱은 읽지 못한다).
 */
object SecretBox {
    private const val ALIAS = "lumen_secrets"
    private const val PREFIX = "v1:"
    private const val PLAIN_PREFIX = "p1:"
    private const val TAG = "LumenSecretBox"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val sealed = PREFIX + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(out, Base64.NO_WRAP)
            // 일부 기기는 암호화는 되는데 복호화가 실패한다. 바로 풀어 보고 안 되면 평문 방식으로 둔다
            if (open(sealed) == plain) sealed else fallback(plain, null)
        } catch (e: Exception) {
            fallback(plain, e)
        }
    }

    private fun fallback(plain: String, e: Exception?): String {
        Log.w(TAG, "Keystore 암호화를 쓸 수 없어 앱 전용 저장소에 평문으로 보관합니다", e)
        return PLAIN_PREFIX + Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    fun open(sealed: String): String {
        if (sealed.startsWith(PLAIN_PREFIX)) {
            return String(Base64.decode(sealed.removePrefix(PLAIN_PREFIX), Base64.NO_WRAP), Charsets.UTF_8)
        }
        if (!sealed.startsWith(PREFIX)) return sealed
        return try {
            val (iv, body) = sealed.removePrefix(PREFIX).split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(body, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "저장된 비밀값을 풀지 못했습니다", e)
            ""
        }
    }
}
