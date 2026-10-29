package com.familyguard.core.domain.security

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Parent PIN handling (PLAN §7.5): the PIN exists only as a salted SHA-256
 * hash. Verification is constant-time so the gate cannot be probed by timing.
 *
 * A per-install salt (16 random bytes, hex) is generated once and stored
 * alongside the hash; neither ever leaves the device.
 */
object PinHasher {

    const val PIN_LENGTH = 6

    private val HEX = "0123456789abcdef".toCharArray()

    /** True when [pin] is a well-formed 6-digit PIN. */
    fun isValid(pin: String): Boolean = pin.length == PIN_LENGTH && pin.all { it.isDigit() }

    fun newSalt(byteCount: Int = 16): String {
        val bytes = ByteArray(byteCount).also { SecureRandom().nextBytes(it) }
        return bytes.toHex()
    }

    /** Salted SHA-256 of [pin]; returns lowercase hex. */
    fun hash(pin: String, saltHex: String): String {
        require(isValid(pin)) { "PIN must be $PIN_LENGTH digits" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(saltHex.encodeToByteArray())
        return digest.digest(pin.encodeToByteArray()).toHex()
    }

    /** Constant-time verification of [pin] against [hashHex]. */
    fun verify(pin: String, saltHex: String, hashHex: String): Boolean {
        if (!isValid(pin)) return false
        val expected = hashHex.encodeToByteArray()
        val actual = hash(pin, saltHex).encodeToByteArray()
        if (expected.size != actual.size) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].toInt() xor actual[i].toInt())
        return diff == 0
    }

    private fun ByteArray.toHex(): String {
        val out = CharArray(size * 2)
        forEachIndexed { i, b ->
            val v = b.toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }
}
