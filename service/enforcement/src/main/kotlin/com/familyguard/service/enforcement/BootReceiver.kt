package com.familyguard.service.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.familyguard.core.data.mode.AppMode
import com.familyguard.core.data.mode.ModeRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * PLAN §6.1: services may only restart from a user-visible event or
 * BOOT_COMPLETED. After a reboot, child mode comes back automatically —
 * parent mode never starts the enforcement loop by itself.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            BootEntryPoint::class.java,
        )
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (entryPoint.modeRepository().currentMode() == AppMode.CHILD) {
                    EnforcementService.start(context.applicationContext)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BootEntryPoint {
    fun modeRepository(): ModeRepository
}
