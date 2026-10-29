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

/**
 * Which child this install is paired to.
 *
 * PLAN-SPLIT §2.2: before the split this file also held a `mode` flag that
 * flipped a single install between parent and child. That flag is gone — the
 * role is now the applicationId (`com.familyguard` vs
 * `com.familyguard.child`), so only the child app ever writes or reads this
 * store. The DataStore *file* keeps its historic `app_mode` name so a device
 * that paired before the split is still paired after it updates.
 *
 * These are identifiers, not secrets: the device's session lives in the
 * encrypted session store (PLAN §7.2).
 */
class ChildDeviceStore(private val context: Context) {

    private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "app_mode")

    private val KEY_CHILD_ID = stringPreferencesKey("child_id")
    private val KEY_DEVICE_ID = stringPreferencesKey("device_id")

    /** Set after a successful pairing-verify; null while unpaired. */
    val childId: Flow<String?> = context.store.data.map { it[KEY_CHILD_ID] }
    val deviceId: Flow<String?> = context.store.data.map { it[KEY_DEVICE_ID] }

    suspend fun childIdOrNull(): String? = childId.first()

    suspend fun deviceIdOrNull(): String? = deviceId.first()

    suspend fun pair(childId: String, deviceId: String) {
        context.store.edit {
            it[KEY_CHILD_ID] = childId
            it[KEY_DEVICE_ID] = deviceId
        }
    }

    suspend fun clear() {
        context.store.edit { it.clear() }
    }
}

/** Hilt-facing wrapper; one instance per install. */
@Singleton
class ChildDeviceRepository @Inject constructor(
    private val store: ChildDeviceStore,
) {
    val childId: Flow<String?> = store.childId

    suspend fun childIdOrNull(): String? = store.childIdOrNull()

    suspend fun deviceIdOrNull(): String? = store.deviceIdOrNull()

    suspend fun pair(childId: String, deviceId: String): Unit = store.pair(childId, deviceId)

    suspend fun clear(): Unit = store.clear()
}
