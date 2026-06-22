package com.moodflow.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.ResultReceiver;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "MediaControls")
public class MediaControlsPlugin extends Plugin {

    private MediaSession mediaSession;
    private NotificationManager notificationManager;
    private String channelId = "moodflow_media";
    private int notifId = 1001;
    private boolean isPlaying = false;

    private static final String ACTION_PLAY = "com.moodflow.app.PLAY";
    private static final String ACTION_PAUSE = "com.moodflow.app.PAUSE";
    private static final String ACTION_NEXT = "com.moodflow.app.NEXT";
    private static final String ACTION_PREV = "com.moodflow.app.PREV";

    @Override
    public void load() {
        super.load();
        Context ctx = getContext();
        notificationManager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(channelId, "MoodFlow Player",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Music playback controls");
            ch.setShowBadge(false);
            notificationManager.createNotificationChannel(ch);
        }

        mediaSession = new MediaSession(ctx, "MoodFlowSession");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() { notifyJS("mediaPlay", null); }
            @Override
            public void onPause() { notifyJS("mediaPause", null); }
            @Override
            public void onSkipToNext() { notifyJS("mediaNext", null); }
            @Override
            public void onSkipToPrevious() { notifyJS("mediaPrev", null); }
            @Override
            public void onStop() { notifyJS("mediaPause", null); }
        });
        mediaSession.setActive(true);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PLAY);
        filter.addAction(ACTION_PAUSE);
        filter.addAction(ACTION_NEXT);
        filter.addAction(ACTION_PREV);
        ctx.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_PLAY.equals(action)) notifyJS("mediaPlay", null);
                else if (ACTION_PAUSE.equals(action)) notifyJS("mediaPause", null);
                else if (ACTION_NEXT.equals(action)) notifyJS("mediaNext", null);
                else if (ACTION_PREV.equals(action)) notifyJS("mediaPrev", null);
            }
        }, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    private void notifyJS(String event, JSObject data) {
        if (data == null) data = new JSObject();
        notifyListeners(event, data);
    }

    @PluginMethod
    public void updateMedia(PluginCall call) {
        String title = call.getString("title", "MoodFlow");
        String artist = call.getString("artist", "");
        String thumbUrl = call.getString("thumbUrl", "");

        mediaSession.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .build());

        int playState = isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setState(playState, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                        PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS |
                        PlaybackState.ACTION_STOP)
                .build());

        showNotification(title, artist, thumbUrl);
        call.resolve();
    }

    @PluginMethod
    public void setPlaying(PluginCall call) {
        isPlaying = call.getBoolean("playing", false);
        int state = isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                        PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS |
                        PlaybackState.ACTION_STOP)
                .build());

        JSObject meta = new JSObject();
        meta.put("playing", isPlaying);
        notifyJS("mediaState", meta);
        call.resolve();
    }

    @PluginMethod
    public void hideMedia(PluginCall call) {
        isPlaying = false;
        mediaSession.setActive(false);
        notificationManager.cancel(notifId);
        call.resolve();
    }

    private void showNotification(String title, String artist, String thumbUrl) {
        Context ctx = getContext();
        Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        PendingIntent contentIntent = PendingIntent.getActivity(ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, channelId)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(artist)
                .setContentIntent(contentIntent)
                .setOngoing(isPlaying)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2)
                        .setShowCancelButton(true));

        builder.addAction(android.R.drawable.ic_media_previous, "Previous",
                pendingBroadcast(ACTION_PREV));
        if (isPlaying) {
            builder.addAction(android.R.drawable.ic_media_pause, "Pause",
                    pendingBroadcast(ACTION_PAUSE));
        } else {
            builder.addAction(android.R.drawable.ic_media_play, "Play",
                    pendingBroadcast(ACTION_PLAY));
        }
        builder.addAction(android.R.drawable.ic_media_next, "Next",
                pendingBroadcast(ACTION_NEXT));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                NotificationManagerCompat.from(ctx).notify(notifId, builder.build());
            } catch (Exception ignored) {}
        } else {
            notificationManager.notify(notifId, builder.build());
        }
    }

    private PendingIntent pendingBroadcast(String action) {
        Intent intent = new Intent(action);
        intent.setPackage(getContext().getPackageName());
        return PendingIntent.getBroadcast(getContext(), action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
