package com.familyguard.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.delay

/**
 * The 6-digit keypad gate (P6/P18). The buffer lives in the ViewModel so a
 * configuration change never keeps a half-typed PIN on screen… nor loses it
 * — actually it resets, which is the safe direction.
 */
@Composable
fun PinGateScreen(
    state: PinGateUiState,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onBiometric: ((FragmentActivity) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    // Ticks the lockout countdown once a second while it is active.
    LaunchedEffect(state.lockedUntilMs) {
        while (state.lockedUntilMs > now) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
        now = System.currentTimeMillis()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.gate_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.gate_protected),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        // Dots
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(PinPolicy.LENGTH) { index ->
                Surface(
                    modifier = Modifier.size(16.dp),
                    shape = CircleShape,
                    color = if (index < state.entered.length) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {}
            }
        }

        Spacer(Modifier.height(16.dp))

        val errorText = when (state.error) {
            GateError.WRONG -> stringResource(
                R.string.gate_wrong_pin,
                PinPolicy.attemptsLeft(state.failedAttempts, state.lockedUntilMs, now),
            )
            GateError.LOCKED -> stringResource(
                R.string.gate_locked,
                ((state.lockedUntilMs - now) / 1000).coerceAtLeast(1).toInt(),
            )
            GateError.INVALID -> stringResource(R.string.gate_pin_invalid)
            null -> null
        }
        Text(
            text = errorText.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))

        Keypad(onDigit = onDigit, onBackspace = onBackspace)

        Spacer(Modifier.height(16.dp))

        if (onBiometric != null && activity != null) {
            TextButton(onClick = { onBiometric(activity) }) {
                Text(stringResource(R.string.gate_use_biometric))
            }
        }
    }
}

@Composable
internal fun Keypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { ch ->
                    KeyButton(label = ch.toString()) { onDigit(ch) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.size(72.dp))
            KeyButton(label = "0") { onDigit('0') }
            KeyButton(label = "⌫") { onBackspace() }
        }
    }
}

@Composable
internal fun KeyButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(72.dp),
        shape = CircleShape,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * Wraps parent-mode content: shows the PIN gate on entry and re-locks it when
 * the app was backgrounded for more than 2 minutes (PLAN P18). With no PIN
 * set up the content simply shows — the gate is opt-in until the parent
 * chooses a PIN in Settings.
 */
@Composable
fun ParentGate(
    modifier: Modifier = Modifier,
    gateViewModel: PinGateViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val state by gateViewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var backgroundedAt by rememberSaveable { mutableStateOf(0L) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> backgroundedAt = System.currentTimeMillis()
                Lifecycle.Event.ON_START -> {
                    val at = backgroundedAt
                    backgroundedAt = 0L
                    if (PinPolicy.shouldRelock(at.takeIf { it != 0L }, System.currentTimeMillis())) {
                        gateViewModel.lock()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (state.pinSetUp && !state.unlocked) {
        val biometricNegative = stringResource(R.string.gate_biometric_negative)

        PinGateScreen(
            state = state,
            onDigit = gateViewModel::onDigit,
            onBackspace = gateViewModel::onBackspace,
            onBiometric = if (state.biometricAvailable) {
                { activity ->
                    Biometrics.authenticate(
                        activity = activity,
                        title = "FamilyGuard",
                        negativeButtonText = biometricNegative,
                        onSuccess = gateViewModel::onBiometricSuccess,
                        onError = gateViewModel::onBiometricError,
                    )
                }
            } else {
                null
            },
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            content()
        }
    }
}
