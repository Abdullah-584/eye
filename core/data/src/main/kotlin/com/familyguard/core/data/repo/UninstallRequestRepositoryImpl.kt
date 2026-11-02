package com.familyguard.core.data.repo

import com.familyguard.core.common.AppResult
import com.familyguard.core.data.dto.UninstallRequestDto
import com.familyguard.core.data.internal.toDomain
import com.familyguard.core.database.FamilyGuardDatabase
import com.familyguard.core.domain.model.UninstallRequest
import com.familyguard.core.domain.model.UninstallRequestStatus
import com.familyguard.core.domain.repo.UninstallRequestRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.cancelable
import kotlinx.coroutines.launch
import java.util.concurrent.MissingScheduleException

@Singleton
class UninstallRequestRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
    private val db: FamilyGuardDatabase,
) : UninstallRequestRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override fun observeRequests(childId: String): Flow<List<UninstallRequest>> =
        db.uninstallRequestDao().observe(childId).map { list -> list.map { it.toDomain() } }

    override suspend fun requestMoreTime(
        childId: String,
        deviceId: String,
        requestedSeconds: Int,
        packageName: String?,
        reason: String?,
    ): AppResult<TimeRequest> = runApp {
        throw UnsupportedOperationException("Use requestUninstallRequest instead")
    }

    override suspend fun decide(requestId: String, approved: Boolean): AppResult<Unit> = runApp {
        val newStatus = if (approved) "confirmed" else "declined"
        val response = client.postgrest.from("uninstall_requests")
            .update(mapOf("status" to newStatus))
            .eq("id", requestId)
            .select()
            .head()
            .mapError { it.message }
        response.fold(
            { _ ->
                // Refresh the local cache so the UI sees the new status immediately
                db.uninstallRequestDao().refresh(requestId)
            },
            { error ->
                AppResult.Failure(RuntimeException(error))
            },
        )
    }

    /** M2c: called by the child when they confirm an uninstall request.
     *  Sets status to 'confirmed' and starts a 45-second timeout.
     *  If ACTION_PACKAGE_REMOVED fires within the timeout, the enforcement
     *  service will update status to 'done'. If the timeout expires first,
     *  status is updated to 'declined'.
     */
    suspend fun confirmUninstall(requestId: String, packageName: String, childId: String) = runApp {
        // Set status to 'confirmed' immediately
        val statusResult = decide(requestId, true)
        when (statusResult) {
            is AppResult.Success -> {
                // Start the 45-second timeout timer
                launch {
                    try {
                        delay(45_000) // 45 seconds
                        // Timeout expired without ACTION_PACKAGE_REMOVED
                        // Mark as declined
                        val timeoutResponse = client.postgrest.from("uninstall_requests")
                            .update(mapOf("status" to "declined", "result_message" to "Uninstall timed out - child did not confirm system dialog"))
                            .eq("id", requestId)
                            .select()
                            .head()
                            .mapError { it.message }
                        timeoutResponse.fold(
                            { _ -> db.uninstallRequestDao().refresh(requestId) },
                            { error -> /* ignore */ }
                        )
                    } catch (e: MissingScheduleException) {
                        // Delay was cancelled, ignore
                    } catch (e: Exception) {
                        // Ignore any other errors from the timer
                    }
                }
            }
            is AppResult.Failure -> statusResult.error.kind.name
        }
    }

    override suspend fun refresh(childId: String): AppResult<List<UninstallRequest>> = runApp {
        val rows = client.postgrest.from("uninstall_requests")
            .select { filter { eq("child_id", childId) } }
            .decodeList<UninstallRequestDto>()
        db.uninstallRequestDao().upsertAll(rows.map { it.toDomain() })
        rows.map { it.toDomain() }
    }
}