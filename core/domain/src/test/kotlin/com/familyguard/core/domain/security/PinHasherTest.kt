package com.familyguard.core.domain.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {

    @Test
    fun `only 6-digit pins are valid`() {
        assertTrue(PinHasher.isValid("123456"))
        assertFalse(PinHasher.isValid("12345"))
        assertFalse(PinHasher.isValid("1234567"))
        assertFalse(PinHasher.isValid("12345a"))
        assertFalse(PinHasher.isValid("1234 6"))
    }

    @Test
    fun `same pin with different salts hashes differently`() {
        val a = PinHasher.hash("123456", PinHasher.newSalt())
        val b = PinHasher.hash("123456", PinHasher.newSalt())
        assertNotEquals(a, b)
    }

    @Test
    fun `verify accepts the right pin and rejects the wrong one`() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash("481902", salt)
        assertTrue(PinHasher.verify("481902", salt, hash))
        assertFalse(PinHasher.verify("481903", salt, hash))
        assertFalse(PinHasher.verify("000000", salt, hash))
    }

    @Test
    fun `verify rejects malformed pins without hashing`() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash("123456", salt)
        assertFalse(PinHasher.verify("", salt, hash))
        assertFalse(PinHasher.verify("abcdef", salt, hash))
    }

    @Test
    fun `verify rejects a mismatched hash shape safely`() {
        val salt = PinHasher.newSalt()
        assertFalse(PinHasher.verify("123456", salt, "deadbeef"))
    }
}
