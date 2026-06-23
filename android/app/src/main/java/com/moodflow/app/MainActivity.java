package com.moodflow.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerPlugin(AppUpdatePlugin.class);
        registerPlugin(MediaControlsPlugin.class);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Keep WebView alive for background YouTube playback
        if (getBridge() != null && getBridge().getWebView() != null) {
            getBridge().getWebView().postDelayed(() -> {
                try {
                    getBridge().getWebView().onResume();
                    getBridge().getWebView().requestFocus();
                } catch (Exception ignored) {}
            }, 300);
        }
    }
}
