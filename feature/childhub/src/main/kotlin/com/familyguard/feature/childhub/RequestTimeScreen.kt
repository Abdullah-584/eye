package com.familyguard.feature.childhub

import com.familyguard.core.ui.bannerMessage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * C5 — the child asks for extra time. This is the *only* write a child
 * device performs: a pending `time_requests` row the parent decides on
 * (PLAN §7.2 — never any rule table).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestTimeScreen(
    state: ChildUiState,
    onSend: (minutes: Int, reason: String?) -> Unit,
    onAcknowledgeSent: () -> Unit,
    onDismissBanner: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var minutes by remember { mutableStateOf(15f) }
    var reason by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.request_title)) },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.banner?.let { ChildBanner(bannerMessage(it), onDismissBanner) }

            if (state.requestSent) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            stringResource(R.string.request_sent),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            stringResource(R.string.request_pending),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Button(
                            onClick = {
                                // "Done" means "back to home", not "show me
                                // the form again".
                                onAcknowledgeSent()
                                onBack()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        ) {
                            Text(stringResource(R.string.request_back_home))
                        }
                    }
                }
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.request_minutes), style = MaterialTheme.typography.titleMedium)
                Text(
                    "  ${minutes.toInt()} min",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = minutes,
                onValueChange = { minutes = it },
                valueRange = 5f..180f,
                steps = 34,
            )

            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it.take(200) },
                label = { Text(stringResource(R.string.request_reason)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            Button(
                onClick = { onSend(minutes.toInt(), reason) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(if (state.busy) R.string.request_sending else R.string.request_send),
                )
            }

            // Only shown once a parent has actually refused a request.
            if (state.requestDenied) {
                Text(
                    stringResource(R.string.request_denied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}