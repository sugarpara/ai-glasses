package com.rokid.glassesbaredevsample.provisioning

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rokid.glassesbaredevsample.BuildConfig

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = intent.action ?: "boot-trigger"
        Log.i(
            TAG,
            "Ensuring session service after ${intent.action} " +
                "version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",
        )
        GlassesSessionService.ensureStarted(context, reason)
        // The firmware may reject third-party background starts during early boot.
        // Keep the default-HOME activity as a service-start fallback.
        BootRecoveryScheduler.launchNow(context, reason)
        BootRecoveryScheduler.scheduleRetries(context)
    }

    private companion object {
        const val TAG = "GlassesRecoveryBoot"
    }
}
