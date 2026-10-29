package com.familyguard.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure PIN rules (PLAN P6: 6-digit PIN, lockout after 5 wrong attempts) and
 * the P18 background-lock window. No Android types so they are unit-testable
 * with a fake clock (PLAN §12.1).
 */
object PinPolicy {
    const val LENGTH = 6
    const val MAX_ATTEMPTS = 5
    const val LOCKOUT_MS = 60_000L

    /** P18: parent mode re-locks after the app has been backgrounded this long. */
    const val RELOCK_AFTER_MS = 120_000L

    /** PBKDF2 cost: ~60k iterations is a few hundred ms on a mid-range phone. */
    const val ITERATIONS = 60_000

    fun isValidPin(pin: String): Boolean =
        pin.length == LENGTH && pin.all { it.isDigit() }

    /** Remaining attempts at [nowMs]; 0 while the lockout is active. */
    fun attemptsLeft(failedAttempts: Int, lockedUntilMs: Long, nowMs: Long): Int =
        if (nowMs < lockedUntilMs) 0 else (MAX_ATTEMPTS - failedAttempts).coerceAtLeast(0)

    fun isLocked(lockedUntilMs: Long, nowMs: Long): Boolean = nowMs < lockedUntilMs

    /** P18: true when the gate must show again at [nowMs]. */
    fun shouldRelock(backgroundedAtMs: Long?, nowMs: Long): Boolean =
        backgroundedAtMs != null && nowMs - backgroundedAtMs >= RELOCK_AFTER_MS
}

/** Outcome of a PIN attempt. */
sealed interface PinCheck {
    data object Unlocked : PinCheck
    data class Wrong(val attemptsLeft: Int) : PinCheck
    data class Locked(val retryInMs: Long) : PinCheck
}

data class PinLockState(
    val failedAttempts: Int = 0,
    val lockedUntilMs: Long = 0L,
)

/**
 * The parent PIN, stored only as PBKDF2(salt, pin) in app-private DataStore
 * (PLAN §6: "PIN fallback stored only as a salted hash"). The plaintext never
 * leaves the keystrokes that typed it; 5 wrong attempts buy a 60 s lockout.
 */
@Singleton
class PinStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "parent_pin")

    private val keySalt = stringPreferencesKey("pin_salt")
    private val keyHash = stringPreferencesKey("pin_hash")
    private val keyAttempts = intPreferencesKey("pin_attempts")
    private val keyLockedUntil = longPreferencesKey("pin_locked_until")
    private val keyBiometric = booleanPreferencesKey("pin_biometric")

    val isSetUp: Flow<Boolean> = context.store.data.map { it[keyHash] != null }

    val biometricEnabled: Flow<Boolean> = context.store.data.map { it[keyBiometric] ?: false }

    val lockState: Flow<PinLockState> = context.store.data.map {
        PinLockState(
            failedAttempts = it[keyAttempts] ?: 0,
            lockedUntilMs = it[keyLockedUntil] ?: 0L,
        )
    }

    suspend fun setPin(pin: String): Boolean {
        if (!PinPolicy.isValidPin(pin)) return false
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = withContext(Dispatchers.Default) { hash(pin, salt) }
        context.store.edit {
            it[keySalt] = Base64.getEncoder().encodeToString(salt)
            it[keyHash] = Base64.getEncoder().encodeToString(hash)
            it[keyAttempts] = 0
            it.remove(keyLockedUntil)
        }
        return true
    }

    /** Constant-time comparison; lockout enforced before any hashing. */
    suspend fun verify(pin: String): PinCheck = withContext(Dispatchers.Default) {
        val prefs = context.store.data.first()
        val now = System.currentTimeMillis()
        val lockedUntil = prefs[keyLockedUntil] ?: 0L
        if (PinPolicy.isLocked(lockedUntil, now)) {
            return@withContext PinCheck.Locked(lockedUntil - now)
        }
        val expectedB64 = prefs[keyHash]
            ?: return@withContext PinCheck.Wrong(PinPolicy.MAX_ATTEMPTS)
        val salt = Base64.getDecoder().decode(prefs[keySalt] ?: "")
        val actual = hash(pin, salt)
        val expected = Base64.getDecoder().decode(expectedB64)

        if (MessageDigest.isEqual(actual, expected)) {
            context.store.edit {
                it[keyAttempts] = 0
                it.remove(keyLockedUntil)
            }
            return@withContext PinCheck.Unlocked
        }

        val failed = (prefs[keyAttempts] ?: 0) + 1
        val lockNow = failed >= PinPolicy.MAX_ATTEMPTS
        context.store.edit {
            it[keyAttempts] = if (lockNow) 0 else failed
            if (lockNow) it[keyLockedUntil] = now + PinPolicy.LOCKOUT_MS
        }
        if (lockNow) PinCheck.Locked(PinPolicy.LOCKOUT_MS)
        else PinCheck.Wrong(PinPolicy.MAX_ATTEMPTS - failed)
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.store.edit { it[keyBiometric] = enabled }
    }

    /** Used by account deletion; nothing PIN-related survives that. */
    suspend fun clear() {
        context.store.edit { it.clear() }
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PinPolicy.ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private companion object {
        const val SALT_BYTES = 16
    }
}
