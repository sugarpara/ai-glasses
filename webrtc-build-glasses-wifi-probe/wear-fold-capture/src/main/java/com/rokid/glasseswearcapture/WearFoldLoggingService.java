package com.rokid.glasseswearcapture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.atomic.AtomicLong;

/** Captures vendor wear/fold broadcasts without assigning semantics to raw values. */
public final class WearFoldLoggingService extends Service {
    private static final String TAG = "GlassesWearFold";
    private static final String CHANNEL_ID = "glasses_wear_fold_capture";
    private static final int NOTIFICATION_ID = 301;

    private static final String ACTION_TAKE_STATUS_CHANGED =
            "com.rokid.sprite.ACTION_TAKE_STATUS_CHANGED";
    private static final String ACTION_LEG_STATUS_CHANGED =
            "com.rokid.sprite.ACTION_LEG_STATUS_CHANGED";
    private static final String EXTRA_TAKE_STATE = "glasses_take_state";
    private static final String EXTRA_LEG_STATE = "glasses_leg_state";

    private final AtomicLong eventSequence = new AtomicLong();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;

            String action = intent.getAction();
            String extraKey;
            if (ACTION_TAKE_STATUS_CHANGED.equals(action)) {
                extraKey = EXTRA_TAKE_STATE;
            } else if (ACTION_LEG_STATUS_CHANGED.equals(action)) {
                extraKey = EXTRA_LEG_STATE;
            } else {
                return;
            }

            Bundle extras = intent.getExtras();
            Object rawValue = extras == null ? null : extras.get(extraKey);
            boolean present = extras != null && extras.containsKey(extraKey);
            String rawType = rawValue == null ? "null" : rawValue.getClass().getName();
            Log.i(
                    TAG,
                    "event seq=" + eventSequence.incrementAndGet()
                            + " elapsedRealtimeMs=" + SystemClock.elapsedRealtime()
                            + " wallTimeMs=" + System.currentTimeMillis()
                            + " action=" + action
                            + " extra=" + extraKey
                            + " present=" + present
                            + " rawType=" + rawType
                            + " rawValue=" + rawValue);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Glasses wear/fold capture")
                .setContentText("Recording raw vendor state broadcasts")
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_TAKE_STATUS_CHANGED);
        filter.addAction(ACTION_LEG_STATUS_CHANGED);
        registerReceiver(receiver, filter);
        Log.i(TAG, "capture-started elapsedRealtimeMs=" + SystemClock.elapsedRealtime());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "sticky-restart" : intent.getAction();
        Log.i(TAG, "capture-request action=" + action + " startId=" + startId);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        Log.i(TAG, "capture-stopped elapsedRealtimeMs=" + SystemClock.elapsedRealtime());
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID,
                "Glasses wear/fold capture",
                NotificationManager.IMPORTANCE_LOW));
    }
}
