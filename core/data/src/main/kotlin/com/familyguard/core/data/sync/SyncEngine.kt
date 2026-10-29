package com.familyguard.core.data.sync

import com.familyguard.core.data.dto.AppLimitDto
import com.familyguard.core.data.dto.ScheduleDto
import com.familyguard.core.data.dto.TimeRequestDto
import com.familyguard.core.data.dto.WebFilterDto
import com.familyguard.core.data.internal.toDomain
import com.familyguard.core.data.internal.toEntity
import com.familyguard.core.database.FamilyGuardDatabase
import com.familyguard.core.domain.repo.RulesRepository
import com.familyguard.core.domain.repo.TimeRequestRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.HasRecord
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import javax.inject.Inject
import javax.inject.Singleton

enum class SyncState { IDLE, CONNECTING, LIVE, DEGRADED }

private val syncJson = Json { ignoreUnknownKeys = true }

/**
 * The realtime fast path of the rule pipeline (PLAN §3.3):
 *
 *   parent edit → Postgres/RLS → postgres_changes → this engine → Room →
 *   enforcement observes Room and applies within seconds.
 *
 * Conflict rule: incoming rule rows carry `version` and are applied only
 * when `version >= local.version`, so an out-of-order or replayed event can
 * never roll a rule backwards (PLAN M4 reducer semantics). Deletes are never
 * applied from the event payload — they trigger a debounced [deltaSync],
 * which reconciles removals deterministically.
 *
 * This engine is only the *fast* path: [deltaSync] runs on start and again
 * whenever a delete or reconnect appears, so a dropped socket (Doze,
 * airplane mode) converges on reconnect (PLAN M4 acceptance).
 */
@Singleton
class SyncEngine @Inject constructor(
    private val client: SupabaseClient,
    private val db: FamilyGuardDatabase,
    private val rulesRepository: RulesRepository,
    private val timeRequests: TimeRequestRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(SyncState.IDLE)
    val state: StateFlow<SyncState> = _state

    private var channel: RealtimeChannel? = null

    /** Base name of the topic currently served ("family:…" / "child:…"). */
    private var channelKey: String? = null
    private var channelSeq = 0
    private val jobs = mutableListOf<Job>()

    /** Serialises every [restart]/[stop] so two ViewModels cannot build
     *  handlers for the same topic at the same time. */
    private val mutex = Mutex()

    /** Child device: live rules + time-request decisions for one child. */
    fun startChildSync(childId: String) {
        restart("child:$childId") { ch ->
            listOf(
                ch.watch<PostgresAction.Insert>("app_limits", childId) { rec ->
                    applyLimit(syncJson.decodeFromJsonElement(rec))
                },
                ch.watch<PostgresAction.Update>("app_limits", childId) { rec ->
                    applyLimit(syncJson.decodeFromJsonElement(rec))
                },
                ch.watch<PostgresAction.Insert>("schedules", childId) { rec ->
                    applySchedule(syncJson.decodeFromJsonElement(rec))
                },
                ch.watch<PostgresAction.Update>("schedules", childId) { rec ->
                    applySchedule(syncJson.decodeFromJsonElement(rec))
                },
                ch.watch<PostgresAction.Insert>("web_filters", childId) { rec ->
                    applyWebFilter(syncJson.decodeFromJsonElement(rec))
                },
                ch.watch<PostgresAction.Update>("web_filters", childId) { rec ->
                    applyWebFilter(syncJson.decodeFromJsonElement(rec))
                },
                // The decision row arrives here (RLS lets the device read its
                // own request), extending the local deadline without FCM.
                ch.watch<PostgresAction.Insert>("time_requests", childId) { rec ->
                    db.timeRequestDao().upsert(
                        syncJson.decodeFromJsonElement<TimeRequestDto>(rec).toDomain().toEntity(),
                    )
                },
                ch.watch<PostgresAction.Update>("time_requests", childId) { rec ->
                    db.timeRequestDao().upsert(
                        syncJson.decodeFromJsonElement<TimeRequestDto>(rec).toDomain().toEntity(),
                    )
                },
            ) + listOf("app_limits", "schedules", "web_filters", "time_requests").map { table ->
                ch.watchDelete(table, childId)
            }
        }
        scope.launch { deltaSync(childId) }
    }

    /** Parent device: pending inbox + decisions live for a set of children. */
    fun startParentSync(childIds: List<String>) {
        if (childIds.isEmpty()) {
            scope.launch { mutex.withLock { stopLocked() } }
            return
        }
        restart(childIds.joinToString(",", prefix = "family:")) { ch ->
            childIds.flatMap { childId ->
                listOf(
                    ch.watch<PostgresAction.Insert>("time_requests", childId) { rec ->
                        db.timeRequestDao().upsert(
                            syncJson.decodeFromJsonElement<TimeRequestDto>(rec).toDomain().toEntity(),
                        )
                    },
                    ch.watch<PostgresAction.Update>("time_requests", childId) { rec ->
                        db.timeRequestDao().upsert(
                            syncJson.decodeFromJsonElement<TimeRequestDto>(rec).toDomain().toEntity(),
                        )
                    },
                    ch.watchDelete("time_requests", childId),
                )
            }
        }
        scope.launch { childIds.forEach { deltaSync(it) } }
    }

    fun stop() {
        scope.launch { mutex.withLock { stopLocked() } }
    }

    /** Caller must hold [mutex]. */
    private suspend fun stopLocked() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        val ch = channel
        channel = null
        channelKey = null
        _state.value = SyncState.IDLE
        // removeChannel() unsubscribes AND drops the topic from the client's
        // map, so the next start for this topic gets a brand-new channel.
        if (ch != null) runCatching { client.realtime.removeChannel(ch) }
    }

    /** Pull everything for [childId] — the reconciliation path. */
    suspend fun deltaSync(childId: String) {
        runCatching { rulesRepository.refresh(childId) }
        runCatching { timeRequests.refresh(childId) }
    }

    // ------------------------------------------------------------------ impl

    private fun restart(channelKey: String, build: (RealtimeChannel) -> List<Job>) {
        // realtime-kt caches channels by topic and returns the *existing*
        // instance for a name it already knows, while postgresChangeFlow()
        // throws if it is called after that channel joined. Rebuilding a
        // topic we already serve is therefore both useless (the handlers are
        // still live) and fatal — so it is a no-op.
        if (channel != null && this.channelKey == channelKey) return
        scope.launch {
            mutex.withLock {
                // Re-check: another caller may have set this topic up while we
                // waited for the lock.
                if (channel != null && this@SyncEngine.channelKey == channelKey) return@withLock
                stopLocked()
                _state.value = SyncState.CONNECTING
                // A unique suffix guarantees realtime-kt builds a fresh
                // channel instead of handing back one it has already joined.
                val ch = client.realtime.channel(channelKey + "_" + (++channelSeq))
                channel = ch
                this@SyncEngine.channelKey = channelKey
                // Handlers MUST be registered before subscribe(): registering
                // them from a launched coroutine can race past the join.
                jobs += build(ch)
                jobs += launch {
                    ch.status.collect { st ->
                        _state.value = when (st) {
                            RealtimeChannel.Status.SUBSCRIBED -> SyncState.LIVE
                            RealtimeChannel.Status.SUBSCRIBING -> SyncState.CONNECTING
                            RealtimeChannel.Status.UNSUBSCRIBING -> SyncState.DEGRADED
                            RealtimeChannel.Status.UNSUBSCRIBED ->
                                if (_state.value == SyncState.LIVE) SyncState.DEGRADED else SyncState.IDLE
                        }
                    }
                }
                delay(250) // let the collectors register before the join
                runCatching { ch.subscribe() }
            }
        }
    }

    private inline fun <reified A : PostgresAction> RealtimeChannel.watch(
        table: String,
        childId: String,
        crossinline onRecord: suspend (JsonObject) -> Unit,
    ): Job {
        // Register the change stream HERE, synchronously, not inside the
        // coroutine: realtime-kt throws "You cannot call postgresChangeFlow
        // after joining the channel", and a launched coroutine is not
        // guaranteed to have run before [restart] calls subscribe().
        //
        // The first argument is the *schema*, not the event — realtime-kt
        // derives INSERT/UPDATE/DELETE from the reified action type.
        val changes = postgresChangeFlow<A>("public") {
            this.table = table
            this.filter = "child_id=eq.$childId"
        }
        return scope.launch {
            changes.collect { action ->
                val record = (action as? HasRecord)?.record
                if (record != null) runCatching { onRecord(record) }
            }
        }
    }

    /** Deletes mean "reconcile", never blind-apply (see class docs). */
    private fun RealtimeChannel.watchDelete(table: String, childId: String): Job {
        // Same synchronous-registration rule as [watch].
        val changes = postgresChangeFlow<PostgresAction.Delete>("public") {
            this.table = table
            this.filter = "child_id=eq.$childId"
        }
        return scope.launch {
            changes.collect {
                delay(300) // debounce bursts
                deltaSync(childId)
            }
        }
    }

    private suspend fun applyLimit(dto: AppLimitDto) {
        val local = db.appLimitDao().getForChild(dto.childId).firstOrNull { it.id == dto.id }
        if (local == null || dto.version >= local.version) {
            db.appLimitDao().upsert(dto.toDomain().toEntity())
        }
    }

    private suspend fun applySchedule(dto: ScheduleDto) {
        val local = db.scheduleDao().getForChild(dto.childId).firstOrNull { it.id == dto.id }
        if (local == null || dto.version >= local.version) {
            db.scheduleDao().upsert(dto.toDomain().toEntity())
        }
    }

    private suspend fun applyWebFilter(dto: WebFilterDto) {
        val local = db.webFilterDao().getForChild(dto.childId).firstOrNull { it.id == dto.id }
        if (local == null || dto.version >= local.version) {
            db.webFilterDao().upsert(dto.toDomain().toEntity())
        }
    }
}
