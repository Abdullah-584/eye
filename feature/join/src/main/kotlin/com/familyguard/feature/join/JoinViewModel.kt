package com.familyguard.feature.join

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familyguard.core.common.AppResult
import com.familyguard.core.data.mode.ChildDeviceRepository
import com.familyguard.core.domain.repo.PairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class JoinUiState(
    val code: String = "",
    val deviceName: String = "",
    val connecting: Boolean = false,
    val joined: Boolean = false,
    val banner: String? = null,
)

/**
 * C1 — join flow: exchange the 6-digit code for a device session scoped to
 * one child, then record that pairing on this device (PLAN A5/§7.3). The
 * install's role is fixed by its applicationId: this is the child app.
 */
@HiltViewModel
class JoinViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pairingRepository: PairingRepository,
    private val childDevice: ChildDeviceRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        JoinUiState(deviceName = Build.MODEL ?: "Android device"),
    )
    val state: StateFlow<JoinUiState> = _state

    fun onCodeChange(value: String) = _state.update {
        it.copy(code = value.filter { c -> c.isDigit() }.take(6), banner = null)
    }

    fun onDeviceNameChange(value: String) = _state.update {
        it.copy(deviceName = value.take(40))
    }

    fun submit() = viewModelScope.launch {
        val current = _state.value
        if (current.code.length != 6 || current.connecting) return@launch
        _state.update { it.copy(connecting = true, banner = null) }
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        }.getOrDefault("0.0.0")

        when (
            val result = pairingRepository.joinFamily(
                code = current.code,
                deviceName = current.deviceName.ifBlank { Build.MODEL ?: "Android device" },
                model = Build.MODEL ?: "Android",
                sdk = Build.VERSION.SDK_INT,
                appVersion = appVersion,
            )
        ) {
            is AppResult.Success -> {
                childDevice.pair(result.value.childId, result.value.deviceId)
                _state.update { it.copy(connecting = false, joined = true) }
            }
            is AppResult.Failure -> _state.update {
                it.copy(
                    connecting = false,
                    banner = it.banner ?: "join_invalid_code",
                )
            }
        }
    }

    fun dismissBanner() = _state.update { it.copy(banner = null) }
}