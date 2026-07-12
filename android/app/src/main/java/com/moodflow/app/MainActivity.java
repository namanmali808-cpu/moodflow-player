package com.moodflow.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebSettings;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private ActivityResultLauncher<String> notifPermissionLauncher;
    private ActivityResultLauncher<String> audioPermissionLauncher;
    private int bridgeInjectAttempts = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        initialPlugins.add(AppUpdatePlugin.class);
        initialPlugins.add(MediaControlsPlugin.class);
        super.onCreate(savedInstanceState);

        notifPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            result -> {}
        );

        audioPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            result -> {
                if (result) {
                    try {
                        WebView wv = getBridge().getWebView();
                        if (wv != null) {
                            wv.post(() -> wv.evaluateJavascript(
                                "if(window.onVoiceReady)onVoiceReady();", null));
                        }
                    } catch (Exception ignored) {}
                }
            }
        );

        injectBridge();
        requestNotifPermission();
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            WebView wv = getBridge().getWebView();
            if (wv != null) {
                wv.onResume();
                wv.resumeTimers();
                wv.evaluateJavascript("if(window.forceSwitchToStream)forceSwitchToStream();", null);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onResume() {
        super.onResume();
        try {
            WebView wv = getBridge().getWebView();
            if (wv != null) {
                wv.evaluateJavascript("if(window.checkRelease)checkRelease();", null);
            }
        } catch (Exception ignored) {}
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

    public void requestRecordAudioPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) return;
        try {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        } catch (Exception ignored) {}
    }
}
