package com.familyguard.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.familyguard.core.database.dao.AppLimitDao
import com.familyguard.core.database.dao.ChildDao
import com.familyguard.core.database.dao.DeviceDao
import com.familyguard.core.database.dao.FamilyDao
import com.familyguard.core.database.dao.LocationDao
import com.familyguard.core.database.dao.ScheduleDao
import com.familyguard.core.database.dao.SyncMetaDao
import com.familyguard.core.database.dao.TimeRequestDao
import com.familyguard.core.database.dao.UsageDao
import com.familyguard.core.database.dao.WebFilterDao
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

@Database(
    entities = [
        FamilyEntity::class,
        ChildEntity::class,
        DeviceEntity::class,
        AppLimitEntity::class,
        ScheduleEntity::class,
        WebFilterEntity::class,
        TimeRequestEntity::class,
        UsageDailyEntity::class,
        UsageEventEntity::class,
        LocationEntity::class,
        SyncMetaEntity::class,
    ],
    version = 1,
    // Schema export is turned off until the migration-test milestone wires a
    // schemaDirectory; v1 needs no migrations yet.
    exportSchema = false,
)
abstract class FamilyGuardDatabase : RoomDatabase() {
    abstract fun familyDao(): FamilyDao
    abstract fun childDao(): ChildDao
    abstract fun deviceDao(): DeviceDao
    abstract fun appLimitDao(): AppLimitDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun webFilterDao(): WebFilterDao
    abstract fun timeRequestDao(): TimeRequestDao
    abstract fun usageDao(): UsageDao
    abstract fun locationDao(): LocationDao
    abstract fun syncMetaDao(): SyncMetaDao

    companion object {
        const val NAME = "familyguard.db"
    }
}
