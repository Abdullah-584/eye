package com.familyguard.core.data.repo

import com.familyguard.core.common.AppResult
import com.familyguard.core.data.internal.runApp
import com.familyguard.core.domain.repo.JoinResult
import com.familyguard.core.domain.repo.PairingCode
import com.familyguard.core.domain.repo.PairingRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class CreateResponse(
    val code: String,
    @kotlinx.serialization.SerialName("expires_at") val expiresAt: String = "",
)

@Serializable
private data class VerifyResponse(
    @kotlinx.serialization.SerialName("child_id") val childId: String,
    @kotlinx.serialization.SerialName("device_id") val deviceId: String,
    @kotlinx.serialization.SerialName("access_token") val accessToken: String,
    @kotlinx.serialization.SerialName("refresh_token") val refreshToken: String = "",
    @kotlinx.serialization.SerialName("expires_in") val expiresIn: Long = 0,
    @kotlinx.serialization.SerialName("token_type") val tokenType: String = "bearer",
)

@Serializable
private data class HeartbeatResponse(val ok: Boolean = true)

/**
 * All three calls hit Edge Functions (PLAN §4.4) — pairing codes are hashed
 * server-side and never readable by clients, and the child session is minted
 * by `pairing-verify`, not by the app.
 */
@Singleton
class PairingRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
) : PairingRepository {

    override suspend fun createCode(childId: String): AppResult<PairingCode> = runApp {
        val response = client.functions.invoke(
            "pairing-create",
            body = buildJsonObject { put("child_id", childId) },
        )
        val parsed = json.decodeFromString<CreateResponse>(response.bodyAsText())
        PairingCode(parsed.code, parsed.expiresAt)
    }

    /**
     * C1 join: exchanges the 6-digit code for a **device session** scoped to
     * one child, then imports it as this install's session — this is the
     * moment the app becomes a child-mode device (PLAN A5, §7.3).
     */
    override suspend fun joinFamily(
        code: String,
        deviceName: String,
        model: String,
        sdk: Int,
        appVersion: String,
    ): AppResult<JoinResult> = runApp {
        val response = client.functions.invoke(
            "pairing-verify",
            body = buildJsonObject {
                put("code", code)
                put("device", buildJsonObject {
                    put("name", deviceName)
                    put("model", model)
                    put("sdk", sdk)
                    put("app_version", appVersion)
                })
            },
        )
        val verified = json.decodeFromString<VerifyResponse>(response.bodyAsText())
        client.auth.importSession(
            UserSession(
                accessToken = verified.accessToken,
                refreshToken = verified.refreshToken,
                expiresIn = verified.expiresIn,
                tokenType = verified.tokenType,
            ),
        )
        JoinResult(verified.childId, verified.deviceId)
    }

    override suspend fun heartbeat(fcmToken: String?, appVersion: String, sdk: Int): AppResult<Unit> =
        runApp {
            client.functions.invoke(
                "device-heartbeat",
                body = buildJsonObject {
                    fcmToken?.let { put("fcm_token", it) }
                    put("app_version", appVersion)
                    put("sdk", sdk)
                },
            )
            Unit
        }
}
