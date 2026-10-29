package com.familyguard.core.domain.security

/**
 * Crockford base32 pairing codes (PLAN §7.3): 6 characters, ~30 bits.
 * Normalisation folds the ambiguous characters *before* hashing so a parent
 * reading "O1IIL" off a screen can never be locked out by typography.
 */
object PairingCode {

    const val LENGTH = 6

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Fold o/O → 0, i/I/l/L → 1, strip separators, uppercase. */
    fun normalize(raw: String): String = buildString(raw.length) {
        for (ch in raw.trim().uppercase()) {
            when (ch) {
                'O' -> append('0')
                'I', 'L' -> append('1')
                'U' -> append('V') // Crockford excludes U to avoid accidental obscenity
                in ALPHABET -> append(ch)
                ' ', '-', '.' -> Unit
                else -> Unit
            }
        }
    }

    fun isValid(raw: String): Boolean {
        val n = normalize(raw)
        return n.length == LENGTH && n.all { it in ALPHABET }
    }

    /** A random normalised code — [random] is injectable for tests. */
    fun generate(random: java.util.Random = java.util.Random()): String =
        buildString(LENGTH) { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
}
