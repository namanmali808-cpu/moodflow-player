package com.moodflow.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "MediaControls")
public class MediaControlsPlugin extends Plugin {

    private static final String ACTION_PLAY = "com.moodflow.app.media.PLAY";
    private static final String ACTION_PAUSE = "com.moodflow.app.media.PAUSE";
    private static final String ACTION_NEXT = "com.moodflow.app.media.NEXT";
    private static final String ACTION_PREV = "com.moodflow.app.media.PREV";
    private static final String SONG_ENDED = "com.moodflow.app.media.SONG_ENDED";
    private static final String AUDIO_STARTED = "com.moodflow.app.media.AUDIO_STARTED";

    private boolean permissionRequested = false;
    private WebView webView;
    
    public static WebView webViewRef = null;

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        if (webView != null) {
            webView.postDelayed(() -> {
                try { webView.onResume(); } catch (Exception ignored) {}
            }, 500);
        }
    }

    @Override
    public void load() {
        super.load();
        try { webView = getBridge().getWebView(); webViewRef = webView; } catch (Exception ignored) {}

        requestNotifPermission();

        // Re-register on reload to refresh webViewRef
        try { getContext().getApplicationContext().unregisterReceiver(forwardReceiver); } catch (Exception ignored) {}
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PLAY);
        filter.addAction(ACTION_PAUSE);
        filter.addAction(ACTION_NEXT);
        filter.addAction(ACTION_PREV);
        filter.addAction(SONG_ENDED);
        filter.addAction(AUDIO_STARTED);
        int flags = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) flags = Context.RECEIVER_NOT_EXPORTED;
        getContext().getApplicationContext().registerReceiver(forwardReceiver, filter, flags);
    }

    private void requestNotifPermission() {
        if (permissionRequested) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        permissionRequested = true;
        try {
            ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        } catch (Exception ignored) {}
    }

    private final BroadcastReceiver forwardReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            String js = null;
            if (ACTION_PLAY.equals(a)) js = "if(window.mediaOnPlay)mediaOnPlay();";
            else if (ACTION_PAUSE.equals(a)) js = "if(window.mediaOnPause)mediaOnPause();";
            else if (ACTION_NEXT.equals(a)) js = "if(window.mediaOnNext)mediaOnNext();";
            else if (ACTION_PREV.equals(a)) js = "if(window.mediaOnPrev)mediaOnPrev();";
            else if (SONG_ENDED.equals(a)) js = "if(window.sk)sk();";
            else if (AUDIO_STARTED.equals(a)) js = "if(window.onNativeAudioStart)onNativeAudioStart();";
            if (js == null) return;
            WebView wv = webViewRef != null ? webViewRef : webView;
            if (wv == null) return;
            final String fjs = js;
            // Try direct evaluateJavascript first
            try { wv.evaluateJavascript(fjs, null); return; } catch (Exception ignored) {}
            // Fallback: post to WebView thread
            try { wv.post(() -> { try { wv.evaluateJavascript(fjs, null); } catch (Exception ignored) {} }); return; } catch (Exception ignored) {}
            // Final fallback: loadUrl javascript:
            try { wv.post(() -> { try { wv.loadUrl("javascript:" + fjs); } catch (Exception ignored) {} }); } catch (Exception ignored) {}
        }
    };

    @PluginMethod
    public void updateMedia(PluginCall call) {
        String title = call.getString("title", "MoodFlow");
        String artist = call.getString("artist", "");
        boolean playing = call.getBoolean("playing", false);
        Context ctx = getContext();
        Intent intent = new Intent(ctx, MediaPlaybackService.class);
        intent.setAction("UPDATE_META");
        intent.putExtra("title", title);
        intent.putExtra("artist", artist);
        intent.putExtra("playing", playing);
        startForegroundService(ctx, intent);
        call.resolve();
    }
    @PluginMethod
    public void setPlaying(PluginCall call) {
        boolean playing = call.getBoolean("playing", false);
        Intent intent = new Intent(getContext(), MediaPlaybackService.class);
        intent.setAction("UPDATE_META");
        intent.putExtra("playing", playing);
        startForegroundService(getContext(), intent);
        call.resolve();
    }
    @PluginMethod
    public void hideMedia(PluginCall call) {
        Intent intent = new Intent(getContext(), MediaPlaybackService.class);
        intent.setAction("STOP");
        getContext().startService(intent);
        call.resolve();
    }

    private void startForegroundService(Context ctx, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent);
        else ctx.startService(intent);
    }
}
