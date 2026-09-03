package com.rokid.glassesbaredevsample.provisioning

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class WearFoldRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (WearFoldContract.expectedExtra(action) == null) return
        if (GlassesSessionService.state.value.serviceRunning) return

        Log.i(TAG, "Recovering session service from physical event action=$action")
        GlassesSessionService.notifyPhysicalState(context, intent)
    }

    private companion object {
        const val TAG = "GlassesWearRecovery"
    }
}
