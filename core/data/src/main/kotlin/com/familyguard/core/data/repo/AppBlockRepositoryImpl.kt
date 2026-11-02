package com.familyguard.core.data.repo

import com.familyguard.core.common.AppResult
import com.familyguard.core.data.dto.BlockedAppDto
import com.familyguard.core.data.internal.runApp
import com.familyguard.core.data.internal.toDomain
import com.familyguard.core.data.internal.toEntity
import com.familyguard.core.database.FamilyGuardDatabase
import com.familyguard.core.domain.model.AppBlock
import com.familyguard.core.domain.repo.AppBlockRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M2b — `blocked_apps`: the parent's block intent. The parent writes, the
 * device reads (RLS has no device write policy, so a child can never set or
 * clear `blocked` — the "no self-unblock" guarantee is structural).
 *
 * The write goes to Postgrest FIRST so a failed toggle is reported honestly
 * instead of diverging from the server; the returned row carries the bumped
 * `version` (touch trigger) which lands in Room so the realtime stream and
 * the local cache agree.
 */
@Singleton
class AppBlockRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
    private val db: FamilyGuardDatabase,
) : AppBlockRepository {

    override fun observe(childId: String): Flow<List<AppBlock>> =
        db.blockedAppDao().observe(childId).map { list -> list.map { it.toDomain() } }

    override suspend fun refresh(childId: String): AppResult<Int> = runApp {
        val rows = client.postgrest.from("blocked_apps")
            .select { filter { eq("child_id", childId) } }
            .decodeList<BlockedAppDto>()
        db.blockedAppDao().upsertAll(rows.map { it.toEntity() })
        // A block row another parent deleted must disappear locally too.
        db.blockedAppDao().deleteExcept(childId, rows.map { it.packageName })
        rows.size
    }

    /** Parent toggle: upsert on (child_id, package_name); server bumps version. */
    override suspend fun setBlocked(
        childId: String,
        packageName: String,
        blocked: Boolean,
    ): AppResult<Unit> = runApp {
        val row = BlockedAppDto(childId = childId, packageName = packageName, blocked = blocked)
        val saved = client.postgrest.from("blocked_apps")
            .upsert(row) { onConflict = "child_id,package_name"; select() }
            .decodeSingleOrNull<BlockedAppDto>() ?: row
        db.blockedAppDao().upsertAll(listOf(saved.toEntity()))
        Unit
    }
}
