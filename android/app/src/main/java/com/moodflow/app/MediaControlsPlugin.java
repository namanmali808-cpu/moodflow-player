package com.moodflow.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;

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
    private static final String FORWARD_PLAY = "mediaPlay";
    private static final String FORWARD_PAUSE = "mediaPause";
    private static final String FORWARD_NEXT = "mediaNext";
    private static final String FORWARD_PREV = "mediaPrev";

    private boolean serviceRunning = false;
    private boolean permissionRequested = false;

    @Override
    public void load() {
        super.load();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PLAY);
        filter.addAction(ACTION_PAUSE);
        filter.addAction(ACTION_NEXT);
        filter.addAction(ACTION_PREV);
        int flags = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) flags = Context.RECEIVER_NOT_EXPORTED;
        getContext().registerReceiver(forwardReceiver, filter, flags);

        requestNotifPermission();
    }

    private void requestNotifPermission() {
        if (permissionRequested) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        permissionRequested = true;
        ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
    }

    private final BroadcastReceiver forwardReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            JSObject d = new JSObject();
            if (ACTION_PLAY.equals(a)) notifyListeners(FORWARD_PLAY, d);
            else if (ACTION_PAUSE.equals(a)) notifyListeners(FORWARD_PAUSE, d);
            else if (ACTION_NEXT.equals(a)) notifyListeners(FORWARD_NEXT, d);
            else if (ACTION_PREV.equals(a)) notifyListeners(FORWARD_PREV, d);
        }
    };

    @PluginMethod
    public void updateMedia(PluginCall call) {
        requestNotifPermission();

        String title = call.getString("title", "MoodFlow");
        String artist = call.getString("artist", "");
        boolean playing = call.getBoolean("playing", false);

        Intent intent = new Intent(getContext(), MediaPlaybackService.class);
        intent.setAction("UPDATE_META");
        intent.putExtra("title", title);
        intent.putExtra("artist", artist);
        intent.putExtra("playing", playing);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(intent);
        } else {
            getContext().startService(intent);
        }
        serviceRunning = true;
        call.resolve();
    }

    @PluginMethod
    public void setPlaying(PluginCall call) {
        boolean playing = call.getBoolean("playing", false);
        if (!serviceRunning) {
            call.resolve();
            return;
        }
        Intent intent = new Intent(getContext(), MediaPlaybackService.class);
        intent.setAction("UPDATE_META");
        intent.putExtra("playing", playing);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(intent);
        } else {
            getContext().startService(intent);
        }
        call.resolve();
    }

    @PluginMethod
    public void hideMedia(PluginCall call) {
        serviceRunning = false;
        Intent intent = new Intent(getContext(), MediaPlaybackService.class);
        intent.setAction("STOP");
        getContext().startService(intent);
        call.resolve();
    }
}
