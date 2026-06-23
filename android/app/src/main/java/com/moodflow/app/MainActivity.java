package com.moodflow.app;

import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerPlugin(AppUpdatePlugin.class);
        registerPlugin(MediaControlsPlugin.class);

        // Inject MediaBridge directly into WebView (guaranteed to work)
        getBridge().getWebView().post(() -> {
            try {
                WebView wv = getBridge().getWebView();
                wv.addJavascriptInterface(new MediaBridge(MainActivity.this), "MediaBridge");
            } catch (Exception ignored) {}
        });
    }
}
