package me.wcy.music.source.quickjs

import android.util.Base64
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.Key
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object SourceScriptCrypto {
    fun strToBase64(value: String): String {
        return Base64.encodeToString(value.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    }

    fun base64ToByteArrayJson(value: String): String {
        val bytes = Base64.decode(value.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        return bytes.joinToString(prefix = "[", postfix = "]") { it.toInt().toString() }
    }

    fun md5(urlEncodedValue: String): String {
        val decoded = URLDecoder.decode(urlEncodedValue, "UTF-8")
        val bytes = MessageDigest.getInstance("MD5").digest(decoded.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun aesEncrypt(dataBase64: String, keyBase64: String, ivBase64: String, mode: String): String {
        return runCatching {
            val data = Base64.decode(dataBase64, Base64.DEFAULT)
            val key = Base64.decode(keyBase64, Base64.DEFAULT)
            val iv = Base64.decode(ivBase64, Base64.DEFAULT)
            val cipher = Cipher.getInstance(normalizeAesMode(mode))
            val keySpec = SecretKeySpec(key, "AES")
            if (iv.isEmpty()) {
                cipher.init(Cipher.ENCRYPT_MODE, keySpec)
            } else {
                val finalIv = ByteArray(16)
                System.arraycopy(iv, 0, finalIv, 0, minOf(iv.size, 16))
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(finalIv))
            }
            Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
        }.getOrDefault("")
    }

    fun rsaEncrypt(dataBase64: String, publicKey: String, padding: String): String {
        return runCatching {
            val keyFactory = KeyFactory.getInstance("RSA")
            val keySpec = X509EncodedKeySpec(Base64.decode(publicKey.trim(), Base64.DEFAULT))
            val key: Key = keyFactory.generatePublic(keySpec)
            val cipher = Cipher.getInstance(padding)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(Base64.decode(dataBase64, Base64.DEFAULT))
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        }.getOrDefault("")
    }

    private fun normalizeAesMode(mode: String): String {
        return when (mode) {
            "AES" -> "AES/ECB/NoPadding"
            "AES/CBC/PKCS7Padding" -> "AES/CBC/PKCS5Padding"
            else -> mode
        }
    }
}
