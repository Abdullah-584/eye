package com.familyguard.core.domain.auth

/** The field a validation problem belongs to. */
enum class AuthField { DISPLAY_NAME, EMAIL, PASSWORD }

/** Why a field was rejected. Mapped to localized strings by the UI layer. */
enum class AuthFieldError {
    REQUIRED,
    TOO_LONG,
    INVALID_FORMAT,
    TOO_SHORT,
    MISSING_LETTER,
    MISSING_DIGIT,
}

data class FieldProblem(val field: AuthField, val error: AuthFieldError)

/**
 * Credential inputs. Validation is pure so it runs on the device before any
 * network call: an invalid form never reaches Supabase.
 */
data class SignUpInput(
    val displayName: String,
    val email: String,
    val password: String,
) {
    fun validate(): List<FieldProblem> = buildList {
        val name = displayName.trim()
        when {
            name.isEmpty() -> add(FieldProblem(AuthField.DISPLAY_NAME, AuthFieldError.REQUIRED))
            name.length > MAX_DISPLAY_NAME -> add(FieldProblem(AuthField.DISPLAY_NAME, AuthFieldError.TOO_LONG))
        }
        addAll(EmailValidator.validate(email))
        addAll(PasswordValidator.validate(password))
    }

    fun cleaned(): SignUpInput = SignUpInput(displayName.trim(), email.trim(), password)

    companion object {
        /** Matches `public.profiles.display_name`'s CHECK constraint. */
        const val MAX_DISPLAY_NAME = 60
    }
}

data class SignInInput(
    val email: String,
    val password: String,
) {
    fun validate(): List<FieldProblem> = buildList {
        addAll(EmailValidator.validate(email))
        addAll(PasswordValidator.validate(password))
    }

    fun cleaned(): SignInInput = SignInInput(email.trim(), password)
}

object EmailValidator {

    /** RFC 5321 practical limit, and the usual provider limit. */
    const val MAX_LENGTH = 254

    // Deliberately conservative: one @, a local part, a dotted domain, and a
    // TLD of at least two letters. Anything more permissive accepts junk.
    private val PATTERN = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    fun validate(raw: String): List<FieldProblem> {
        val email = raw.trim()
        return when {
            email.isEmpty() -> listOf(FieldProblem(AuthField.EMAIL, AuthFieldError.REQUIRED))
            email.length > MAX_LENGTH -> listOf(FieldProblem(AuthField.EMAIL, AuthFieldError.TOO_LONG))
            !PATTERN.matches(email) -> listOf(FieldProblem(AuthField.EMAIL, AuthFieldError.INVALID_FORMAT))
            else -> emptyList()
        }
    }
}

object PasswordValidator {

    /** Longer than Supabase's default minimum of 6. */
    const val MIN_LENGTH = 8

    fun validate(password: String): List<FieldProblem> = when {
        password.isEmpty() -> listOf(FieldProblem(AuthField.PASSWORD, AuthFieldError.REQUIRED))
        password.length < MIN_LENGTH -> listOf(FieldProblem(AuthField.PASSWORD, AuthFieldError.TOO_SHORT))
        password.none { it.isLetter() } -> listOf(FieldProblem(AuthField.PASSWORD, AuthFieldError.MISSING_LETTER))
        password.none { it.isDigit() } -> listOf(FieldProblem(AuthField.PASSWORD, AuthFieldError.MISSING_DIGIT))
        else -> emptyList()
    }
}
