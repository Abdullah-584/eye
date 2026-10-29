package com.familyguard.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familyguard.core.common.AppResult
import com.familyguard.core.data.mode.ModeRepository
import com.familyguard.core.domain.auth.AuthRepository
import com.familyguard.core.domain.model.AuditEntry
import com.familyguard.core.domain.repo.AccountRepository
import com.familyguard.core.domain.repo.FamilyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class SettingsUiState(
    val loading: Boolean = true,
    val consent: List<AuditEntry> = emptyList(),
    val pinSet: Boolean = false,
    val biometricEnabled: Boolean = false,
    val biometricAvailable: Boolean = false,
    val busy: Boolean = false,
    /** String-resource id of a transient banner, or null. */
    val banner: Int? = null,
    /** Absolute path of the freshly written export, ready to share. */
    val exportPath: String? = null,
    val showPinEditor: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    val showPrivacy: Boolean = false,
)

/**
 * P17 — the parent's Settings: PIN/biometric, retention & deletion, export,
 * privacy policy, the consent record, sign out and account deletion.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val familyRepository: FamilyRepository,
    private val accountRepository: AccountRepository,
    private val pinStore: PinStore,
    private val modeRepository: ModeRepository,
) : ViewModel() {

    private val mutable = MutableStateFlow(SettingsUiState())
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            familyRepository.refreshAudit()
            mutable.update { it.copy(loading = false) }
        }
        viewModelScope.launch {
            combine(
                familyRepository.observeAudit(),
                pinStore.isSetUp,
                pinStore.biometricEnabled,
            ) { audit, pinSet, biometric ->
                Triple(audit, pinSet, biometric)
            }.collect { (audit, pinSet, biometric) ->
                mutable.update {
                    it.copy(
                        consent = audit.filter { entry -> entry.action == "consent.granted" },
                        pinSet = pinSet,
                        biometricEnabled = biometric,
                        biometricAvailable = pinSet && biometric && Biometrics.isAvailable(context),
                    )
                }
            }
        }
    }

    fun showPinEditor() = mutable.update { it.copy(showPinEditor = true) }

    fun hidePinEditor() = mutable.update { it.copy(showPinEditor = false) }

    /** P6 — set or change the 6-digit parent PIN. */
    fun savePin(pin: String) {
        if (!PinPolicy.isValidPin(pin)) {
            mutable.update { it.copy(banner = R.string.gate_pin_invalid) }
            return
        }
        viewModelScope.launch {
            pinStore.setPin(pin)
            mutable.update {
                it.copy(
                    showPinEditor = false,
                    banner = R.string.settings_pin_saved,
                    pinSet = true,
                )
            }
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            pinStore.setBiometricEnabled(enabled)
            mutable.update { it.copy(biometricEnabled = enabled) }
        }
    }

    fun showDeleteConfirm(show: Boolean) = mutable.update { it.copy(showDeleteConfirm = show) }

    fun showPrivacy(show: Boolean) = mutable.update { it.copy(showPrivacy = show) }

    fun exportData() = viewModelScope.launch {
        mutable.update { it.copy(busy = true, banner = null, exportPath = null) }
        when (val result = accountRepository.exportData()) {
            is AppResult.Success -> {
                val path = writeExportFile(result.value)
                mutable.update {
                    it.copy(busy = false, exportPath = path, banner = R.string.settings_export_ready)
                }
            }
            is AppResult.Failure ->
                mutable.update { it.copy(busy = false, banner = R.string.settings_export_failed) }
        }
    }

    fun clearExportPath() = mutable.update { it.copy(exportPath = null) }

    /** Destructive and irreversible — the confirm dialog gates this call. */
    fun deleteAccount() = viewModelScope.launch {
        mutable.update { it.copy(busy = true, banner = null) }
        when (val result = accountRepository.deleteAccount()) {
            is AppResult.Success -> {
                pinStore.clear()
                modeRepository.reset()
                authRepository.signOut()
                // The root NavHost reacts to SIGNED_OUT and drops back to
                // sign-in; nothing left to update here.
                mutable.update { it.copy(busy = false) }
            }
            is AppResult.Failure ->
                mutable.update { it.copy(busy = false, banner = R.string.settings_delete_failed) }
        }
    }

    fun signOut() = viewModelScope.launch {
        authRepository.signOut()
    }

    fun dismissBanner() = mutable.update { it.copy(banner = null) }

    private fun writeExportFile(json: String): String? = runCatching {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "familyguard-export-${System.currentTimeMillis()}.json")
        file.writeText(json)
        file.absolutePath
    }.getOrNull()
}
