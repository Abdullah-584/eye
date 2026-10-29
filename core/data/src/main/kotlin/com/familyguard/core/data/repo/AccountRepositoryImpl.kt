package com.familyguard.core.data.repo

import com.familyguard.core.common.AppResult
import com.familyguard.core.database.FamilyGuardDatabase
import com.familyguard.core.data.internal.runApp
import com.familyguard.core.domain.repo.AccountRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Both operations are Edge Functions (PLAN §6.3): a client JWT cannot cascade
 * the family's rows, so deletion and export are decided server-side. A
 * function that is not deployed yet surfaces as a failure — we never pretend
 * data was deleted when it was not.
 */
@Singleton
class AccountRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
    private val db: FamilyGuardDatabase,
) : AccountRepository {

    override suspend fun exportData(): AppResult<String> = runApp {
        val response = client.functions.invoke("export-data")
        response.bodyAsText()
    }

    override suspend fun deleteAccount(): AppResult<Unit> = runApp {
        client.functions.invoke("delete-account")
        // Cascade succeeded server-side — drop the local mirror too.
        db.clearAllTables()
        Unit
    }
}
