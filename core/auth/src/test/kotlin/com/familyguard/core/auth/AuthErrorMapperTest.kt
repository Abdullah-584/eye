package com.familyguard.core.auth

import com.familyguard.core.common.ErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The classifier is a pure function of the Supabase error code, so it is tested
 * without constructing HTTP responses. The thin `throwable -> code` glue is
 * covered by the UNKNOWN/NETWORK cases below.
 */
class AuthErrorMapperTest {

    @Test
    fun `invalid credentials code maps to INVALID_CREDENTIALS`() {
        assertEquals(ErrorKind.INVALID_CREDENTIALS, AuthErrorMapper.fromServerCode("invalid_credentials").kind)
    }

    @Test
    fun `an expired or revoked grant maps to INVALID_CREDENTIALS`() {
        assertEquals(ErrorKind.INVALID_CREDENTIALS, AuthErrorMapper.fromServerCode("invalid_grant").kind)
    }

    @Test
    fun `user not found maps to INVALID_CREDENTIALS instead of enumerating accounts`() {
        assertEquals(ErrorKind.INVALID_CREDENTIALS, AuthErrorMapper.fromServerCode("user_not_found").kind)
    }

    @Test
    fun `already registered email maps to EMAIL_TAKEN`() {
        assertEquals(ErrorKind.EMAIL_TAKEN, AuthErrorMapper.fromServerCode("user_already_exists").kind)
    }

    @Test
    fun `unconfirmed email maps to EMAIL_UNCONFIRMED`() {
        assertEquals(ErrorKind.EMAIL_UNCONFIRMED, AuthErrorMapper.fromServerCode("email_not_confirmed").kind)
    }

    @Test
    fun `weak password maps to WEAK_PASSWORD`() {
        assertEquals(ErrorKind.WEAK_PASSWORD, AuthErrorMapper.fromServerCode("weak_password").kind)
    }

    @Test
    fun `rate limit code maps to RATE_LIMITED`() {
        assertEquals(ErrorKind.RATE_LIMITED, AuthErrorMapper.fromServerCode("over_email_send_rate_limit").kind)
    }

    @Test
    fun `an unknown server code maps to UNKNOWN but keeps the raw code`() {
        val error = AuthErrorMapper.fromServerCode("some_future_code")
        assertEquals(ErrorKind.UNKNOWN, error.kind)
        assertEquals("some_future_code", error.serverCode)
    }

    @Test
    fun `a null server code maps to UNKNOWN`() {
        assertEquals(ErrorKind.UNKNOWN, AuthErrorMapper.fromServerCode(null).kind)
    }

    @Test
    fun `an unexpected throwable maps to UNKNOWN and keeps its message`() {
        val error = AuthErrorMapper.map(RuntimeException("boom"))
        assertEquals(ErrorKind.UNKNOWN, error.kind)
        assertEquals("boom", error.message)
    }

    @Test
    fun `an io failure while talking to the network maps to NETWORK`() {
        val error = AuthErrorMapper.map(IOException("connection reset"))
        assertEquals(ErrorKind.NETWORK, error.kind)
        assertTrue(error.isRetryable)
    }
}
