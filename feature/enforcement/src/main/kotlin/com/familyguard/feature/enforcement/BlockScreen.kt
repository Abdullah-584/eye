package com.familyguard.feature.enforcement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.familyguard.core.domain.enforcement.BlockReason
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * C6 — full screen shown when enforcement blocks the foreground app:
 * app name, time used, next free time and the two actions
 * "Ask for more time" / "Back to home" (PLAN M5).
 *
 * The countdown ticks with a plain coroutine delay — not a ValueAnimator —
 * so it still works when `animator_duration_scale = 0`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockScreen(
    reason: BlockReason,
    appLabel: String?,
    usedSeconds: Long,
    limitSeconds: Int,
    scheduleName: String?,
    nextFreeAt: Instant?,
    onRequestTime: () -> Unit,
    onBackHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val headline = when (reason) {
        BlockReason.SCHEDULE -> stringResource(R.string.blocked_schedule)
        BlockReason.LIMIT_REACHED -> stringResource(R.string.blocked_limit)
        BlockReason.APP_BLOCKED -> stringResource(R.string.blocked_app)
        BlockReason.NONE -> stringResource(R.string.blocked_title)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.blocked_title)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = appLabel ?: stringResource(R.string.blocked_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = headline,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (reason == BlockReason.LIMIT_REACHED && limitSeconds > 0) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(
                        R.string.blocked_limit_label,
                        formatUsed(usedSeconds, limitSeconds.toLong()),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (reason == BlockReason.SCHEDULE && scheduleName != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.blocked_schedule_label, scheduleName),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (nextFreeAt != null) {
                Spacer(Modifier.height(16.dp))
                NextFreeCountdown(nextFreeAt = nextFreeAt)
            }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = onRequestTime,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.blocked_ask))
            }
            TextButton(
                onClick = onBackHome,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.blocked_back_home))
            }
        }
    }
}

/**
 * Ticking "unlocks in 12:34" line. A coroutine delay ticker is immune to
 * `animator_duration_scale = 0` (PLAN C6 accessibility requirement).
 */
@Composable
private fun NextFreeCountdown(nextFreeAt: Instant) {
    var remainingSeconds by remember {
        mutableLongStateOf(java.time.Duration.between(Instant.now(), nextFreeAt).seconds.coerceAtLeast(0L))
    }
    LaunchedEffect(nextFreeAt) {
        while (true) {
            remainingSeconds = java.time.Duration.between(Instant.now(), nextFreeAt).seconds.coerceAtLeast(0L)
            delay(1_000)
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = nextFreeAt.atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm")),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = formatCountdown(remainingSeconds),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

internal fun formatCountdown(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun formatUsed(used: Long, limit: Long): String {
    fun hm(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
    return "${hm(used)} / ${hm(limit)}"
}