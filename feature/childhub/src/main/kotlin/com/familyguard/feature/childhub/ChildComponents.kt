package com.familyguard.feature.childhub

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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Error/info banner — same contract as the family/onboarding banners: the
 * state carries a message, the screen reports dismissal.
 */
@Composable
internal fun ChildBanner(
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
                Text(stringResource(R.string.child_banner_dismiss))
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

/** "com.example.youtube" → "Youtube" — display only, enforcement uses the package. */
internal fun prettyAppName(packageName: String): String {
    val raw = packageName.substringAfterLast('.')
    return raw.replaceFirstChar { it.uppercaseChar() }
}

/** daysMask (bit 0 = Sunday) → "Mon Wed Fri" style label. */
internal fun formatDays(daysMask: Int): String {
    val names = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    val picked = (0..6).filter { ((daysMask shr it) and 1) == 1 }.map { names[it] }
    return when {
        picked.isEmpty() -> "No days"
        picked.size == 7 -> "Every day"
        else -> picked.joinToString(" ")
    }
}

/** Next lock instant → "21:00" in the device zone, or null. */
internal fun formatInstantTime(instant: Instant?): String? =
    instant?.atZone(ZoneId.systemDefault())
        ?.format(DateTimeFormatter.ofPattern("HH:mm"))