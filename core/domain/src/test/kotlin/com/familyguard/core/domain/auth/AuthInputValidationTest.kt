package com.familyguard.core.domain.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validation is pure Kotlin so it can be proven on the JVM before any Android
 * or network code exists. Contract: invalid input is rejected locally, with a
 * per-field problem, and never reaches the network.
 */
class AuthInputValidationTest {

    private fun SignUpInput.problems(): Set<FieldProblem> = validate().toSet()

    private fun SignInInput.problems(): Set<FieldProblem> = validate().toSet()

    @Test
    fun `empty sign up reports a required problem for every field`() {
        val problems = SignUpInput("", "   ", "").validate()
        assertTrue(
            "expected DISPLAY_NAME/REQUIRED, got $problems",
            FieldProblem(AuthField.DISPLAY_NAME, AuthFieldError.REQUIRED) in problems,
        )
        assertTrue(
            "expected EMAIL/REQUIRED, got $problems",
            FieldProblem(AuthField.EMAIL, AuthFieldError.REQUIRED) in problems,
        )
        assertTrue(
            "expected PASSWORD/REQUIRED, got $problems",
            FieldProblem(AuthField.PASSWORD, AuthFieldError.REQUIRED) in problems,
        )
    }

    @Test
    fun `a well formed sign up input reports no problems`() {
        assertEquals(emptySet<Pair<AuthField, AuthFieldError>>(), SignUpInput("Sam", "parent@example.com", "abc12345").problems())
    }

    @Test
    fun `an email without a domain is rejected`() {
        assertTrue(FieldProblem(AuthField.EMAIL, AuthFieldError.INVALID_FORMAT) in SignUpInput("Sam", "parent@example", "abc12345").problems())
    }

    @Test
    fun `an email without an at sign is rejected`() {
        assertTrue(FieldProblem(AuthField.EMAIL, AuthFieldError.INVALID_FORMAT) in SignUpInput("Sam", "parentexample.com", "abc12345").problems())
    }

    @Test
    fun `an email longer than 254 characters is too long`() {
        val email = "a".repeat(250) + "@example.com"
        assertTrue(FieldProblem(AuthField.EMAIL, AuthFieldError.TOO_LONG) in SignUpInput("Sam", email, "abc12345").problems())
    }

    @Test
    fun `surrounding whitespace is trimmed before validating`() {
        assertEquals(emptySet<FieldProblem>(), SignUpInput("  Sam  ", "  parent@example.com ", "abc12345").problems())
    }

    @Test
    fun `a display name longer than 60 characters is rejected`() {
        assertTrue(FieldProblem(AuthField.DISPLAY_NAME, AuthFieldError.TOO_LONG) in SignUpInput("x".repeat(61), "parent@example.com", "abc12345").problems())
    }

    @Test
    fun `a password shorter than 8 characters is too short`() {
        assertTrue(FieldProblem(AuthField.PASSWORD, AuthFieldError.TOO_SHORT) in SignUpInput("Sam", "parent@example.com", "abc123").problems())
    }

    @Test
    fun `a password without a digit is rejected`() {
        assertTrue(FieldProblem(AuthField.PASSWORD, AuthFieldError.MISSING_DIGIT) in SignUpInput("Sam", "parent@example.com", "abcdefghijkl").problems())
    }

    @Test
    fun `a password without a letter is rejected`() {
        assertTrue(FieldProblem(AuthField.PASSWORD, AuthFieldError.MISSING_LETTER) in SignUpInput("Sam", "parent@example.com", "12345678").problems())
    }

    @Test
    fun `sign in validates email and password but never asks about a display name`() {
        val problems = SignInInput("not-an-email", "abc12345").problems()
        assertTrue(FieldProblem(AuthField.EMAIL, AuthFieldError.INVALID_FORMAT) in problems)
        assertTrue(problems.none { it.field == AuthField.DISPLAY_NAME })
    }

    @Test
    fun `sign in accepts the same credentials shape as sign up`() {
        assertEquals(emptySet<FieldProblem>(), SignInInput("parent@example.com", "abc12345").problems())
    }
}
