package com.familyguard.core.data.mode

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class AppMode { NONE, PARENT, CHILD }

/**
 * Which mode this install runs in and (in child mode) which child it is
 * paired to. These are identifiers, not secrets — the *session* for whichever
 * mode is active lives in the encrypted session store (PLAN §7.2).
 *
 * PLAN A1: child mode is entered once during pairing and can only be left
 * with the parent PIN (or by wiping app data).
 */
class AppModeStore(private val context: Context) {

    private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "app_mode")

    private val KEY_MODE = stringPreferencesKey("mode")
    private val KEY_CHILD_ID = stringPreferencesKey("child_id")
    private val KEY_DEVICE_ID = stringPreferencesKey("device_id")

    val mode: Flow<AppMode> = context.store.data.map { p ->
        runCatching { AppMode.valueOf(p[KEY_MODE] ?: "NONE") }.getOrDefault(AppMode.NONE)
    }

    /** Set after a successful pairing-verify; null while unpaired. */
    val childId: Flow<String?> = context.store.data.map { it[KEY_CHILD_ID] }
    val deviceId: Flow<String?> = context.store.data.map { it[DEVICE_KEY] }

    suspend fun currentMode(): AppMode = mode.first()

    suspend fun childIdOrNull(): String? = childId.first()

    suspend fun deviceIdOrNull(): String? = deviceId.first()

    suspend fun setParentMode(): Unit {
        context.store.edit {
            it[KEY_MODE] = AppMode.PARENT.name
            it.remove(KEY_CHILD_ID)
            it.remove(KEY_DEVICE_ID)
        }
    }

    suspend fun setChildMode(childId: String, deviceId: String): Unit {
        context.store.edit {
            it[KEY_MODE] = AppMode.CHILD.name
            it[this.KEY_CHILD_ID] = childId
            it[KEY_DEVICE_ID] = deviceId
        }
    }

    suspend fun clear(): Unit {
        context.store.edit { it.clear() }
    }

    private companion object {
        val DEVICE_KEY = stringPreferencesKey("device_id")
    }
}

@Singleton
class ModeRepository @Inject constructor(private val store: AppModeStore) {
    val mode: Flow<AppMode> = store.mode
    val childId: Flow<String?> = store.childId
    suspend fun currentMode(): AppMode = store.currentMode()
    suspend fun childIdOrNull(): String? = store.childIdOrNull()
    suspend fun deviceIdOrNull(): String? = store.deviceIdOrNull()
    suspend fun becomeParent(): Unit = store.setParentMode()
    suspend fun becomeChild(childId: String, deviceId: String): Unit = store.setChildMode(childId, deviceId)
    suspend fun reset(): Unit = store.clear()
}
