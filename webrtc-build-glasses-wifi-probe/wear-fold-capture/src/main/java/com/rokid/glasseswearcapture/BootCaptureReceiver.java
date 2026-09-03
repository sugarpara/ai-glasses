package com.rokid.glasseswearcapture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts raw broadcast capture early enough to observe boot-time vendor events. */
public final class BootCaptureReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "null" : intent.getAction();
        Log.i("GlassesWearFold", "boot-received action=" + action);
        context.startForegroundService(new Intent(context, WearFoldLoggingService.class));
    }
}
