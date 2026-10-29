package com.familyguard.core.network.session

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json

/**
 * Persists the Supabase session encrypted at rest.
 *
 * Behaviour worth knowing:
 *  * A missing session is the normal "logged out" state, so it raises
 *    [NoSessionFoundException] exactly like supabase-kt's own managers.
 *  * A payload we cannot decrypt or decode (corrupt file, restored backup from
 *    another device, key rotated) is also treated as "no session": the user
 *    simply signs in again. Fail-open here would mean a crash loop on startup.
 */
class DataStoreSessionManager(
    private val store: SessionStore,
    private val crypto: SessionCrypto,
    private val json: Json = defaultJson,
) : SessionManager {

    override suspend fun saveSession(session: UserSession) {
        val plain = json.encodeToString(UserSession.serializer(), session)
        store.write(crypto.encrypt(plain))
    }

    override suspend fun loadSession(): UserSession {
        val payload = store.read() ?: throw NoSessionFoundException()
        return try {
            json.decodeFromString(UserSession.serializer(), crypto.decrypt(payload))
        } catch (expected: NoSessionFoundException) {
            throw expected
        } catch (_: Exception) {
            throw NoSessionFoundException()
        }
    }

    override suspend fun deleteSession() {
        store.clear()
    }

    private companion object {
        val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
