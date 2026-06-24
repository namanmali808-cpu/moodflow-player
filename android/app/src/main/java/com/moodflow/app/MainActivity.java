package com.moodflow.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private ActivityResultLauncher<String> notifPermissionLauncher;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        notifPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            result -> {
                // Permission granted or denied - either way, bridge initialization continues
            }
        );

        registerPlugin(AppUpdatePlugin.class);
        registerPlugin(MediaControlsPlugin.class);

        getBridge().getWebView().post(() -> {
            try {
                WebView wv = getBridge().getWebView();
                wv.addJavascriptInterface(new MediaBridge(MainActivity.this, wv), "MediaBridge");
            } catch (Exception ignored) {}
        });

        // Request notification permission for Android 13+
        requestNotifPermission();
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        try {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        } catch (Exception ignored) {}
    }
}
