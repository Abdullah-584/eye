package com.familyguard.feature.family

import com.familyguard.core.ui.bannerMessage
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.ZoneId

/**
 * P5 — add the first (or another) child. Time zone defaults to the parent's
 * device zone so bedtime schedules mean the family's local evening, not UTC.
 */
@Composable
fun AddChildScreen(
    state: FamilyUiState,
    onAddChild: (name: String, birthYear: Int?, timezone: String) -> Unit,
    onDismissBanner: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var birthYear by rememberSaveable { mutableStateOf("") }
    var timezone by rememberSaveable { mutableStateOf(ZoneId.systemDefault().id) }
    val canSubmit = name.isNotBlank() && !state.busy
    val yearInvalid = birthYear.isNotBlank() &&
        (birthYear.length != 4 || birthYear.toIntOrNull() == null)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Text(
            text = stringResource(R.string.add_child_title),
            style = MaterialTheme.typography.headlineMedium,
        )

        Spacer(Modifier.height(24.dp))

        state.banner?.let { banner ->
            FamilyBanner(message = bannerMessage(banner), onDismiss = onDismissBanner)
            Spacer(Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.add_child_name_label)) },
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = birthYear,
            onValueChange = { input -> birthYear = input.filter(Char::isDigit).take(4) },
            label = { Text(stringResource(R.string.add_child_birth_year_label)) },
            isError = yearInvalid,
            supportingText = if (yearInvalid) {
                { Text(stringResource(R.string.add_child_birth_year_error)) }
            } else {
                null
            },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = timezone,
            onValueChange = { timezone = it },
            label = { Text(stringResource(R.string.add_child_timezone_label)) },
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                val year = birthYear.toIntOrNull()
                onAddChild(name.trim(), year, timezone.ifBlank { "UTC" })
            },
            enabled = canSubmit && !yearInvalid,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            if (state.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.add_child_submit))
            }
        }
    }
}