package com.familyguard.core.network.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Where the encrypted session blob lives. Kept as an interface so the session
 * manager's logic can be tested on the JVM without Android DataStore.
 */
interface SessionStore {

    suspend fun read(): String?

    suspend fun write(value: String)

    suspend fun clear()
}

/**
 * Preferences DataStore in the app's private files directory. The file is not
 * backed up to the cloud (`android:allowBackup="false"`), so a parent's
 * session cannot leak through a device restore.
 */
class DataStoreSessionStore(
    private val dataStore: DataStore<Preferences>,
) : SessionStore {

    private val key = stringPreferencesKey(KEY_NAME)

    override suspend fun read(): String? = dataStore.data.first()[key]

    override suspend fun write(value: String) {
        dataStore.edit { it[key] = value }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(key) }
    }

    companion object {
        const val KEY_NAME = "encrypted_session"
    }
}
