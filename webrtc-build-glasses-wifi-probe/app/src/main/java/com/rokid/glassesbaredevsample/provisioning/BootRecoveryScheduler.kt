package com.rokid.glassesbaredevsample.provisioning

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.rokid.glassesbaredevsample.activities.main.MainActivity

/** Retries recovery after boot while Android is still bringing Wi-Fi up. */
object BootRecoveryScheduler {
    private val retryDelaysMs = longArrayOf(5_000L, 20_000L, 60_000L)

    fun launchNow(context: Context, reason: String) {
        runCatching {
            context.startActivity(recoveryIntent(context, reason))
        }.onSuccess {
            Log.i(TAG, "Recovery activity launch requested reason=$reason")
        }.onFailure { error ->
            Log.e(TAG, "Recovery activity launch failed reason=$reason", error)
        }
    }

    fun scheduleRetries(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        retryDelaysMs.forEachIndexed { index, delayMs ->
            val pendingIntent = PendingIntent.getActivity(
                context,
                RETRY_REQUEST_CODE_BASE + index,
                recoveryIntent(context, "boot-retry-${index + 1}"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pendingIntent,
            )
        }
        Log.i(TAG, "Scheduled boot recovery retries at 5s, 20s and 60s")
    }

    fun cancelRetries(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        retryDelaysMs.indices.forEach { index ->
            val pendingIntent = PendingIntent.getActivity(
                context,
                RETRY_REQUEST_CODE_BASE + index,
                recoveryIntent(context, "boot-retry-${index + 1}"),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
        }
        Log.i(TAG, "Cancelled pending boot recovery retries")
    }

    private fun recoveryIntent(context: Context, reason: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_RECOVERY)
            .putExtra(EXTRA_RECOVERY_REASON, reason)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )

    const val EXTRA_RECOVERY_REASON = "recovery_reason"
    private const val ACTION_RECOVERY = "com.rokid.glassesbaredevsample.RECOVER_STREAM"
    private const val RETRY_REQUEST_CODE_BASE = 4100
    private const val TAG = "GlassesRecoveryBoot"
}
