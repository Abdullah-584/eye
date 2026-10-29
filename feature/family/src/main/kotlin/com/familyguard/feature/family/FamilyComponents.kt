package com.familyguard.feature.family

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.familyguard.core.domain.model.Device
import com.familyguard.core.domain.model.DeviceStatus

/**
 * Error/info banner used by every family screen. Same contract as the
 * onboarding banner: the state carries a message, the screen reports dismissal.
 */
@Composable
internal fun FamilyBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.family_banner_dismiss))
            }
        }
    }
}

/** "1h 25m" / "45m" — display-only; enforcement math never uses this. */
internal fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

/** ISO timestamp → "2026-01-01 14:30", tolerating any malformed/absent value. */
internal fun shortTimestamp(iso: String?): String? =
    iso?.takeIf { it.length >= 16 }?.replace('T', ' ')

/**
 * Health dot colour for the dashboard strip — green/amber/red per PLAN P9.
 */
internal val DeviceStatus.healthColor: androidx.compose.ui.graphics.Color
    get() = when (this) {
        DeviceStatus.ACTIVE -> HealthColors.Green
        DeviceStatus.PENDING -> HealthColors.Amber
        DeviceStatus.REVOKED -> HealthColors.Red
    }

internal object HealthColors {
    val Green = androidx.compose.ui.graphics.Color(0xFF2E7D32)
    val Amber = androidx.compose.ui.graphics.Color(0xFFF9A825)
    val Red = androidx.compose.ui.graphics.Color(0xFFC62828)
}

/** Device rows relevant to the selected child. */
internal fun List<Device>.forChild(childId: String?) = filter { it.childId == childId }