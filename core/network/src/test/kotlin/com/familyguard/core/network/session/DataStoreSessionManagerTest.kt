package com.familyguard.core.network.session

import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The session manager is tested against an in-memory [SessionStore] so the
 * logic (encrypt before persist, decrypt on load, delete clears) is proven on
 * the JVM. `DataStoreSessionStore` and `KeystoreSessionCrypto` are thin
 * platform adapters and get instrumented coverage later (see README).
 */
class DataStoreSessionManagerTest {

    private class RecordingCrypto : SessionCrypto {
        val encryptedPayloads = mutableListOf<String>()

        override fun encrypt(plain: String): String {
            encryptedPayloads += plain
            return "sealed:" + plain.reversed()
        }

        override fun decrypt(payload: String): String = payload.removePrefix("sealed:").reversed()
    }

    private class InMemorySessionStore : SessionStore {
        var payload: String? = null
        var writes = 0

        override suspend fun read(): String? = payload

        override suspend fun write(value: String) {
            payload = value
            writes++
        }

        override suspend fun clear() {
            payload = null
        }
    }

    private lateinit var store: InMemorySessionStore
    private lateinit var crypto: RecordingCrypto
    private lateinit var manager: DataStoreSessionManager

    @Before
    fun setUp() {
        store = InMemorySessionStore()
        crypto = RecordingCrypto()
        manager = DataStoreSessionManager(store, crypto)
    }

    private fun sampleSession() = UserSession(
        accessToken = "test-access-token",
        refreshToken = "test-refresh-token",
        expiresIn = 3600,
        tokenType = "bearer",
    )

    @Test
    fun `a saved session round trips through loadSession`() =
        runTest {
            manager.saveSession(sampleSession())

            val loaded = manager.loadSession()

            assertEquals("test-access-token", loaded.accessToken)
            assertEquals("test-refresh-token", loaded.refreshToken)
            assertEquals(3600, loaded.expiresIn)
            assertEquals("bearer", loaded.tokenType)
        }

    @Test
    fun `loadSession throws NoSessionFoundException when nothing was ever saved`() =
        runTest {
            var thrown: Throwable? = null
            try {
                manager.loadSession()
            } catch (t: Throwable) {
                thrown = t
            }
            assertTrue("expected NoSessionFoundException, got $thrown", thrown is NoSessionFoundException)
        }

    @Test
    fun `the session is encrypted before it is persisted`() =
        runTest {
            manager.saveSession(sampleSession())

            assertEquals(1, crypto.encryptedPayloads.size)
            assertTrue(
                "the plain access token must be what gets encrypted",
                crypto.encryptedPayloads.single().contains("test-access-token"),
            )
            assertTrue("payload must not be stored in the clear", store.payload.orEmpty().startsWith("sealed:"))
        }

    @Test
    fun `deleteSession removes the stored session`() =
        runTest {
            manager.saveSession(sampleSession())
            manager.deleteSession()

            var thrown: Throwable? = null
            try {
                manager.loadSession()
            } catch (t: Throwable) {
                thrown = t
            }
            assertTrue("expected NoSessionFoundException, got $thrown", thrown is NoSessionFoundException)
        }

    @Test
    fun `a second save overwrites the first session`() =
        runTest {
            manager.saveSession(sampleSession())
            manager.saveSession(sampleSession().copy(accessToken = "rotated-token", refreshToken = "rotated-refresh"))

            val loaded = manager.loadSession()

            assertEquals("rotated-token", loaded.accessToken)
            assertEquals("rotated-refresh", loaded.refreshToken)
            // one write per save: the second save replaces the stored payload
            // instead of appending a second entry.
            assertEquals(2, store.writes)
        }
}
