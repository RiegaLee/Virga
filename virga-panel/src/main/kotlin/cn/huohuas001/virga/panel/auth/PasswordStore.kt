package cn.huohuas001.virga.panel.auth

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Properties

/**
 * Stores the single panel password digest in `panel-credentials.properties`.
 * The plaintext is only ever returned when it is generated, so it can be shown once.
 */
class PasswordStore(
    private val file: Path,
    private val hasher: PasswordHasher = PasswordHasher(),
    private val random: SecureRandom = SecureRandom()
) {
    @Volatile
    private var digest: String? = null

    /** Loads the stored digest, generating a password when none exists; returns the new plaintext. */
    @Synchronized
    fun initialize(): String? {
        if (Files.isRegularFile(file)) {
            val properties = Properties()
            Files.newBufferedReader(file).use(properties::load)
            val stored = properties.getProperty(DIGEST_KEY)?.trim().orEmpty()
            if (stored.startsWith(PasswordHasher.SCHEME)) {
                digest = stored
                return null
            }
        }
        val generated = generatePassword()
        write(hasher.hash(generated.toCharArray()))
        return generated
    }

    fun verify(password: CharArray): Boolean {
        val current = digest ?: return false
        return hasher.verify(password, current)
    }

    @Synchronized
    fun change(current: CharArray, next: CharArray): ChangeResult {
        if (!verify(current)) return ChangeResult.WRONG_PASSWORD
        return set(next)
    }

    /** Sets a chosen password without the current one (console only, the console is already trusted). */
    @Synchronized
    fun set(next: CharArray): ChangeResult {
        if (next.size < MIN_LENGTH || next.size > MAX_LENGTH) return ChangeResult.INVALID_NEW_PASSWORD
        write(hasher.hash(next))
        return ChangeResult.CHANGED
    }

    /** Replaces the password with a fresh random one (console recovery) and returns it. */
    @Synchronized
    fun reset(): String {
        val generated = generatePassword()
        write(hasher.hash(generated.toCharArray()))
        return generated
    }

    private fun write(encoded: String) {
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, "panel-credentials-", ".tmp")
        try {
            restrictToOwner(temporary)
            val properties = Properties().apply { setProperty(DIGEST_KEY, encoded) }
            Files.newBufferedWriter(temporary).use { properties.store(it, "Virga panel password digest (PBKDF2). Never share this file.") }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
        restrictToOwner(file)
        digest = encoded
    }

    private fun generatePassword(): String =
        (1..GENERATED_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    private fun restrictToOwner(path: Path) {
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
    }

    enum class ChangeResult { CHANGED, WRONG_PASSWORD, INVALID_NEW_PASSWORD }

    companion object {
        const val MIN_LENGTH = 12
        const val MAX_LENGTH = 128
        private const val GENERATED_LENGTH = 24
        private const val DIGEST_KEY = "digest"
        // No look-alike characters (0/O, 1/l/I) so the console password is easy to copy by eye.
        private const val ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
