package com.skillions.deskpilot.net

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Mirrors server/deskpilot_server/crypto.py exactly:
 *   key  = PBKDF2-HMAC-SHA256("deskpilot:" + password, salt, 100_000, 256 bit)
 *   seal = iv(12) || AES-256-GCM(plaintext) (tag 128 bit appended)
 */
object Crypto {
    private const val ITERATIONS = 100_000
    private const val PREFIX = "deskpilot:"
    private val rng = SecureRandom()

    fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec((PREFIX + password).toCharArray(), salt, ITERATIONS, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES")
    }

    class Session(private val key: SecretKeySpec) {
        fun seal(plain: ByteArray): ByteArray {
            val iv = ByteArray(12).also(rng::nextBytes)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return iv + c.doFinal(plain)
        }

        fun open(blob: ByteArray): ByteArray {
            require(blob.size >= 28) { "frame too short" }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
            return c.doFinal(blob, 12, blob.size - 12)
        }
    }
}
