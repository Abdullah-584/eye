package com.familyguard.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinPolicyTest {

    @Test
    fun `six digits is a valid pin`() {
        assertTrue(PinPolicy.isValidPin("123456"))
    }

    @Test
    fun `wrong length or non digits is invalid`() {
        assertFalse(PinPolicy.isValidPin("12345"))
        assertFalse(PinPolicy.isValidPin("1234567"))
        assertFalse(PinPolicy.isValidPin("12345a"))
        assertFalse(PinPolicy.isValidPin(""))
    }

    @Test
    fun `attempts count down to zero then lock`() {
        val now = 1_000_000L
        assertEquals(5, PinPolicy.attemptsLeft(0, 0, now))
        assertEquals(1, PinPolicy.attemptsLeft(4, 0, now))
        assertEquals(0, PinPolicy.attemptsLeft(5, 0, now))
    }

    @Test
    fun `lockout blocks until the deadline passes`() {
        val until = 2_000_000L
        assertTrue(PinPolicy.isLocked(until, until - 1))
        assertFalse(PinPolicy.isLocked(until, until))

        // While locked, zero attempts are offered.
        assertEquals(0, PinPolicy.attemptsLeft(0, until, until - 1))
        assertEquals(5, PinPolicy.attemptsLeft(0, until, until))
    }

    @Test
    fun `relocks only after two minutes in background`() {
        val stop = 10_000L
        assertFalse(PinPolicy.shouldRelock(null, stop + PinPolicy.RELOCK_AFTER_MS))
        assertFalse(PinPolicy.shouldRelock(stop, stop + PinPolicy.RELOCK_AFTER_MS - 1))
        assertTrue(PinPolicy.shouldRelock(stop, stop + PinPolicy.RELOCK_AFTER_MS))
    }
}
