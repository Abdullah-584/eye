package com.familyguard.service.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.familyguard.core.data.mode.ChildDeviceRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * PLAN §6.1: services may only restart from a user-visible event or
 * BOOT_COMPLETED. After a reboot the enforcement loop comes back on its own
 * — but only if this install is actually paired to a child (PLAN-SPLIT §2.2:
 * role is the applicationId, so there is no mode flag left to check).
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
                if (entryPoint.childDeviceRepository().childIdOrNull() != null) {
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
    fun childDeviceRepository(): ChildDeviceRepository
}
