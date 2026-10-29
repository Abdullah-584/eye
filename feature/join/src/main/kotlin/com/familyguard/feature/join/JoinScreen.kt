package com.familyguard.feature.join

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * C1 — child join: type the 6-digit code, see what the device will share,
 * connect, then the app becomes child mode and the permission walkthrough
 * runs on-device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinScreen(
    onJoined: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: JoinViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.joined) {
        LaunchedJoinedEffect(onJoined)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.title_join)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.join_intro),
                style = MaterialTheme.typography.bodyLarge,
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.join_privacy_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.join_privacy_body),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            state.banner?.let {
                Text(
                    stringResource(R.string.join_invalid_code),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedTextField(
                value = state.code,
                onValueChange = viewModel::onCodeChange,
                label = { Text(stringResource(R.string.join_code)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.deviceName,
                onValueChange = viewModel::onDeviceNameChange,
                label = { Text(stringResource(R.string.join_device_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = viewModel::submit,
                enabled = state.code.length == 6 && !state.connecting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.connecting) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.join_connecting))
                } else {
                    Text(stringResource(R.string.join_submit))
                }
            }
        }
    }
}

@Composable
private fun LaunchedJoinedEffect(onJoined: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { onJoined() }
}