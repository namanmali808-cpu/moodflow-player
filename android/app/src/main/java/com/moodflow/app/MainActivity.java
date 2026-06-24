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
    private int bridgeInjectAttempts = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        notifPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            result -> {}
        );

        registerPlugin(AppUpdatePlugin.class);
        registerPlugin(MediaControlsPlugin.class);

        injectBridge();

        requestNotifPermission();
    }

    private void injectBridge() {
        try {
            WebView wv = getBridge().getWebView();
            if (wv != null) {
                wv.addJavascriptInterface(new MediaBridge(MainActivity.this, wv), "MediaBridge");
                return;
            }
        } catch (Exception ignored) {}
        // Retry if WebView not ready
        if (bridgeInjectAttempts < 10) {
            bridgeInjectAttempts++;
            getWindow().getDecorView().postDelayed(this::injectBridge, 500);
        }
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
