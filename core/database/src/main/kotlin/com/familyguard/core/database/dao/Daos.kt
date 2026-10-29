package com.familyguard.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.familyguard.core.database.entity.AppLimitEntity
import com.familyguard.core.database.entity.ChildEntity
import com.familyguard.core.database.entity.DeviceEntity
import com.familyguard.core.database.entity.FamilyEntity
import com.familyguard.core.database.entity.LocationEntity
import com.familyguard.core.database.entity.ScheduleEntity
import com.familyguard.core.database.entity.SyncMetaEntity
import com.familyguard.core.database.entity.TimeRequestEntity
import com.familyguard.core.database.entity.UsageDailyEntity
import com.familyguard.core.database.entity.UsageEventEntity
import com.familyguard.core.database.entity.WebFilterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FamilyDao {
    @Query("select * from families limit 1")
    fun observeFamily(): Flow<FamilyEntity?>

    @Query("select * from families limit 1")
    suspend fun getFamily(): FamilyEntity?

    @Upsert
    suspend fun upsertFamily(family: FamilyEntity)

    @Query("delete from families")
    suspend fun clear()
}

@Dao
interface ChildDao {
    @Query("select * from children order by createdAt")
    fun observeChildren(): Flow<List<ChildEntity>>

    @Query("select * from children order by createdAt")
    suspend fun getChildren(): List<ChildEntity>

    @Query("select * from children where id = :id")
    suspend fun getById(id: String): ChildEntity?

    @Upsert
    suspend fun upsertAll(children: List<ChildEntity>)

    @Query("delete from children where id not in (:ids)")
    suspend fun deleteExcept(ids: List<String>)

    @Query("delete from children where id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface DeviceDao {
    @Query("select * from devices order by deviceName")
    fun observeDevices(): Flow<List<DeviceEntity>>

    @Upsert
    suspend fun upsertAll(devices: List<DeviceEntity>)

    @Query("select * from devices where id = :id")
    suspend fun getById(id: String): DeviceEntity?
}

@Dao
interface AppLimitDao {
    @Query("select * from app_limits where childId = :childId order by packageName")
    fun observe(childId: String): Flow<List<AppLimitEntity>>

    @Query("select * from app_limits where childId = :childId")
    suspend fun getForChild(childId: String): List<AppLimitEntity>

    @Upsert
    suspend fun upsert(limit: AppLimitEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(limit: AppLimitEntity): Long

    @Update
    suspend fun update(limit: AppLimitEntity)

    @Query("delete from app_limits where id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ScheduleDao {
    @Query("select * from schedules where childId = :childId order by startTime")
    fun observe(childId: String): Flow<List<ScheduleEntity>>

    @Query("select * from schedules where childId = :childId")
    suspend fun getForChild(childId: String): List<ScheduleEntity>

    @Upsert
    suspend fun upsert(schedule: ScheduleEntity)

    @Query("delete from schedules where id = :id")
    suspend fun delete(id: String)
}

@Dao
interface WebFilterDao {
    @Query("select * from web_filters where childId = :childId")
    fun observe(childId: String): Flow<List<WebFilterEntity>>

    @Query("select * from web_filters where childId = :childId")
    suspend fun getForChild(childId: String): List<WebFilterEntity>

    @Upsert
    suspend fun upsert(filter: WebFilterEntity)

    @Query("delete from web_filters where id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TimeRequestDao {
    @Query("select * from time_requests where childId = :childId order by createdAt desc")
    fun observe(childId: String): Flow<List<TimeRequestEntity>>

    @Query("select * from time_requests where childId = :childId and status = 'pending'")
    suspend fun getPending(childId: String): List<TimeRequestEntity>

    @Query("select * from time_requests where id = :id")
    suspend fun getById(id: String): TimeRequestEntity?

    @Upsert
    suspend fun upsertAll(requests: List<TimeRequestEntity>)

    @Upsert
    suspend fun upsert(request: TimeRequestEntity)
}

@Dao
interface UsageDao {
    @Query("select * from usage_daily where childId = :childId order by day desc")
    fun observeAll(childId: String): Flow<List<UsageDailyEntity>>

    @Query(
        "select * from usage_daily where childId = :childId and day between :fromDay and :toDay order by day",
    )
    fun observeRange(childId: String, fromDay: String, toDay: String): Flow<List<UsageDailyEntity>>

    @Query(
        "update usage_daily set seconds = seconds + :addSeconds, pickups = pickups + :addPickups " +
            "where childId = :childId and packageName = :packageName and day = :day",
    )
    suspend fun accumulate(childId: String, packageName: String, day: String, addSeconds: Long, addPickups: Int)

    @Query("select seconds from usage_daily where childId = :childId and day = :day")
    suspend fun totalsForDay(childId: String, day: String): List<Long>

    @Query(
        "insert or ignore into usage_daily (childId, packageName, day, seconds, pickups) " +
            "values (:childId, :packageName, :day, 0, 0)",
    )
    suspend fun ensureRow(childId: String, packageName: String, day: String)

    @Query("update usage_daily set seconds = seconds + :s where childId = :childId and packageName = :pkg and day = :day")
    suspend fun addSeconds(childId: String, pkg: String, day: String, s: Long)

    @Query("select * from usage_daily where childId = :childId and day = :day")
    suspend fun dayRows(childId: String, day: String): List<UsageDailyEntity>

    @androidx.room.Upsert
    suspend fun upsertDaily(rows: List<UsageDailyEntity>)

    @androidx.room.Upsert
    suspend fun upsertDailyRow(row: UsageDailyEntity)

    // ---- pending upload queue ----
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueueEvent(event: UsageEventEntity): Long

    @Query("select * from usage_events_pending where syncedAt is null limit :limit")
    suspend fun pendingEvents(limit: Int): List<UsageEventEntity>

    @Query("update usage_events_pending set syncedAt = :at where localId in (:ids)")
    suspend fun markEventsSynced(ids: List<Long>, at: String)

    @Query("select count(*) from usage_events_pending where syncedAt is null")
    fun observePendingCount(): Flow<Int>
}

@Dao
interface LocationDao {
    @Query("select * from locations_pending where childId = :childId order by recordedAt desc limit 1")
    fun observeLatest(childId: String): Flow<LocationEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(fix: LocationEntity): Long

    @Query("select * from locations_pending where syncedAt is null limit :limit")
    suspend fun pending(limit: Int): List<LocationEntity>

    @Query("update locations_pending set syncedAt = :at where localId in (:ids)")
    suspend fun markSynced(ids: List<Long>, at: String)

    @Query("select * from locations_pending where childId = :childId order by recordedAt desc limit 1")
    suspend fun latestSyncedOrNull(childId: String): LocationEntity?
}

@Dao
interface SyncMetaDao {
    @Query("select * from sync_meta where tableName = :table")
    suspend fun get(table: String): SyncMetaEntity?

    @Upsert
    suspend fun upsert(meta: SyncMetaEntity)
}
