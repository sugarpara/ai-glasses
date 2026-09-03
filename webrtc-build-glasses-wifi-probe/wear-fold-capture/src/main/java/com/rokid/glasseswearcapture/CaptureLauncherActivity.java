package com.rokid.glasseswearcapture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Foreground entry point used by ADB to satisfy the device's service start restrictions. */
public final class CaptureLauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startForegroundService(new Intent(this, WearFoldLoggingService.class));
        finish();
    }
}
