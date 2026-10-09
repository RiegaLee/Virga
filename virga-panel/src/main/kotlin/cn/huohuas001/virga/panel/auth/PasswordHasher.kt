package cn.huohuas001.virga.panel.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HMAC-SHA256 password digests encoded as `pbkdf2-sha256$<iterations>$<salt>$<hash>`.
 * Only digests are stored; comparison is constant time.
 */
class PasswordHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom()
) {
    fun hash(password: CharArray): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val derived = derive(password, salt, iterations)
        val encoder = Base64.getEncoder()
        return listOf(SCHEME, iterations.toString(), encoder.encodeToString(salt), encoder.encodeToString(derived))
            .joinToString(SEPARATOR)
    }

    fun verify(password: CharArray, encoded: String): Boolean {
        val parts = encoded.split(SEPARATOR)
        if (parts.size != 4 || parts[0] != SCHEME) return false
        val rounds = parts[1].toIntOrNull()?.takeIf { it in MIN_ITERATIONS..MAX_ITERATIONS } ?: return false
        val decoder = Base64.getDecoder()
        val salt = runCatching { decoder.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { decoder.decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(password, salt, rounds), expected)
    }

    private fun derive(password: CharArray, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, rounds, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val SCHEME = "pbkdf2-sha256"
        private const val SEPARATOR = "$"
        const val DEFAULT_ITERATIONS = 210_000
        private const val MIN_ITERATIONS = 1_000
        private const val MAX_ITERATIONS = 10_000_000
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
    }
}
