package com.moodflow.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.WebView;
import android.webkit.WebSettings;

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
        initialPlugins.add(AppUpdatePlugin.class);
        initialPlugins.add(MediaControlsPlugin.class);
        super.onCreate(savedInstanceState);

        notifPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            result -> {}
        );

        injectBridge();
        requestNotifPermission();
        requestBatteryOpt();
    }

    @Override
    public void onPause() {
        isPausing = true;
        super.onPause();
        try {
            WebView wv = getBridge().getWebView();
            if (wv != null) {
                wv.onResume();
                wv.resumeTimers();
                // Force switch from YouTube iframe to streaming URL for background playback
                wv.evaluateJavascript("if(window.forceSwitchToStream)forceSwitchToStream();", null);
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
                WebSettings ws = wv.getSettings();
                ws.setMediaPlaybackRequiresUserGesture(false);
                ws.setJavaScriptEnabled(true);
                ws.setDomStorageEnabled(true);
                ws.setDatabaseEnabled(true);
                ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                ws.setAllowContentAccess(true);
                ws.setLoadWithOverviewMode(true);
                ws.setUseWideViewPort(true);
                wv.addJavascriptInterface(new MediaBridge(MainActivity.this, wv), "MediaBridge");
                MediaControlsPlugin.webViewRef = wv;
                return;
            }
        } catch (Exception ignored) {}
        if (bridgeInjectAttempts < 10) {
            bridgeInjectAttempts++;
            getWindow().getDecorView().postDelayed(this::injectBridge, 500);
        }
    }

    public void requestNotifPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        try {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        } catch (Exception ignored) {}
    }

    private void requestBatteryOpt() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        } catch (Exception ignored) {}
    }
}
