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
    private boolean isPausing = false;

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

    @Override
    public void onPause() {
        // Don't call super.onPause() which pauses the WebView!
        // This keeps YouTube iframe alive in background
        isPausing = true;
        // Call only the Activity onPause, not BridgeActivity's (which pauses WebView)
        super.onPause();
        // Immediately resume the WebView
        try {
            WebView wv = getBridge().getWebView();
            if (wv != null) {
                wv.onResume();
                wv.resumeTimers();
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onResume() {
        super.onResume();
        isPausing = false;
    }

    private void injectBridge() {
        try {
            WebView wv = getBridge().getWebView();
            if (wv != null) {
                wv.addJavascriptInterface(new MediaBridge(MainActivity.this, wv), "MediaBridge");
                return;
            }
        } catch (Exception ignored) {}
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
