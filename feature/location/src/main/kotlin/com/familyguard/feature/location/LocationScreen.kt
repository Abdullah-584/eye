package com.familyguard.feature.location

import com.familyguard.core.ui.bannerMessage
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familyguard.core.domain.model.LocationFix
import java.time.Duration
import java.time.Instant

/**
 * P13 — child detail location tab: last fix with a "located X ago" honesty
 * label, accuracy circle, battery and manual refresh (no tile map dependency;
 * the plot is drawn locally so it works offline).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationScreen(
    childId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LocationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(childId) {
        viewModel.selectChild(childId)
        viewModel.refresh()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_location)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.banner?.let {
                Text(bannerMessage(it), color = MaterialTheme.colorScheme.error)
            }

            val fix = state.fix
            if (fix == null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            stringResource(R.string.location_none),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.location_none_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                FixPlot(fix)

                Card(Modifier.fillMaxWidth()) {
                    Column {
                        ListItem(
                            headlineContent = { Text(locationAgoLabel(fix.recordedAt)) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        R.string.location_coords,
                                        fix.lat,
                                        fix.lng,
                                    ),
                                )
                            },
                        )
                        fix.accuracyM?.let {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        stringResource(
                                            R.string.location_accuracy,
                                            it.toInt(),
                                        ),
                                    )
                                },
                            )
                        }
                        fix.batteryPct?.let {
                            ListItem(
                                headlineContent = {
                                    Text(stringResource(R.string.location_battery, it.toInt()))
                                },
                            )
                        }
                    }
                }
            }

            Button(
                onClick = { viewModel.refresh() },
                enabled = !state.refreshing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.refreshing) {
                    CircularProgressIndicator(
                        Modifier
                            .height(18.dp)
                            .padding(end = 8.dp),
                    )
                }
                Text(stringResource(R.string.location_refresh))
            }
        }
    }
}

/** Local position plot: marker + accuracy ring on a lat/lng graticule. */
@Composable
private fun FixPlot(fix: LocationFix) {
    val accuracy = (fix.accuracyM ?: 30f).coerceAtLeast(5f)
    // Span across the plot: the ring should occupy ~1/3 of the canvas.
    val spanMeters = accuracy * 6f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val metersPerPixel = spanMeters / size.minDimension

            // Graticule every 50 m.
            val gridPx = 50f / metersPerPixel
            val gridColor = Color(0x22000000)
            var x = center.x % gridPx
            while (x < size.width) {
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                x += gridPx
            }
            var y = center.y % gridPx
            while (y < size.height) {
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += gridPx
            }

            // Accuracy ring.
            val ringRadius = accuracy / metersPerPixel
            drawCircle(
                color = Color(0x332196F3),
                radius = ringRadius,
                center = center,
            )
            drawCircle(
                color = Color(0xFF2196F3),
                radius = ringRadius,
                center = center,
                style = Stroke(width = 3f),
            )

            // Marker.
            drawCircle(color = Color(0xFFD32F2F), radius = 12f, center = center)
            drawCircle(color = Color.White, radius = 5f, center = center)
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
private fun locationAgoLabel(iso: String): String {
    val seconds = remember(iso) {
        runCatching {
            Duration.between(Instant.parse(iso), Instant.now()).seconds
        }.getOrDefault(0L).coerceAtLeast(0L)
    }
    return when {
        seconds < 60 -> stringResource(R.string.location_just_now)
        seconds < 3600 -> stringResource(R.string.location_minutes, seconds / 60)
        else -> stringResource(R.string.location_hours, seconds / 3600)
    }.let { stringResource(R.string.location_ago, it) }
}