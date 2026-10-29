package com.familyguard.core.domain.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {

    @Test
    fun `ambiguous characters fold to their Crockford digits`() {
        assertEquals("000111", PairingCode.normalize("OOOIIl"))
        assertEquals("01", PairingCode.normalize("o l"))
    }

    @Test
    fun `separators and case are stripped`() {
        assertEquals("AB12CD", PairingCode.normalize("ab-12 cd"))
        assertEquals("AB12CD", PairingCode.normalize("  AB12CD  "))
    }

    @Test
    fun `unknown characters are dropped, not rejected, during normalization`() {
        assertEquals("1234", PairingCode.normalize("12!3@4"))
    }

    @Test
    fun `validity requires exactly 6 normalised chars`() {
        assertTrue(PairingCode.isValid("AB12CD"))
        assertTrue(PairingCode.isValid("ab-12 cd"))
        assertFalse(PairingCode.isValid("AB12C"))
        assertFalse(PairingCode.isValid("AB12CDE"))
        assertFalse(PairingCode.isValid(""))
    }

    @Test
    fun `generation always yields a valid normalised code`() {
        val random = java.util.Random(42)
        repeat(200) {
            val code = PairingCode.generate(random)
            assertEquals(PairingCode.LENGTH, code.length)
            assertTrue(code.all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" })
        }
    }
}
