package com.moodflow.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

public class MediaPlaybackService extends Service {

    private static final String CHANNEL_ID = "moodflow_media";
    private static final int NOTIF_ID = 1001;
    public static final String ACTION_PLAY = "com.moodflow.app.media.PLAY";
    public static final String ACTION_PAUSE = "com.moodflow.app.media.PAUSE";
    public static final String ACTION_PLAY_AUDIO = "com.moodflow.app.media.PLAY_AUDIO";
    public static final String EXTRA_AUDIO_URL = "audioUrl";
    public static final String EXTRA_VIDEO_ID = "videoId";

    public static boolean nativeAudioActive = false;

    private ExoPlayer exoPlayer;
    private boolean isPlaying = false;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "MoodFlow Player",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        try {
            startForeground(NOTIF_ID, buildNotification());
        } catch (Exception ignored) {}
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PLAY);
        filter.addAction(ACTION_PAUSE);
        filter.addAction(ACTION_PLAY_AUDIO);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? Context.RECEIVER_NOT_EXPORTED : 0;
        registerReceiver(actionReceiver, filter, flags);
    }

    private final BroadcastReceiver actionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_PLAY.equals(action)) {
                if (exoPlayer != null) { exoPlayer.setPlayWhenReady(true); isPlaying = true; }
            } else if (ACTION_PAUSE.equals(action)) {
                if (exoPlayer != null) { exoPlayer.setPlayWhenReady(false); isPlaying = false; }
            } else if (ACTION_PLAY_AUDIO.equals(action)) {
                String url = intent.getStringExtra(EXTRA_AUDIO_URL);
                String vid = intent.getStringExtra(EXTRA_VIDEO_ID);
                if (url != null && !url.isEmpty()) playAudioUrl(url, vid);
            }
        }
    };

    private void playAudioUrl(String url, String videoId) {
        try {
            if (exoPlayer == null) {
                exoPlayer = new ExoPlayer.Builder(this).build();
                exoPlayer.addListener(new Player.Listener() {
                    @Override public void onPlaybackStateChanged(int state) {
                        if (state == Player.STATE_ENDED) {
                            sendBroadcast(new Intent("com.moodflow.app.media.SONG_ENDED").setPackage(getPackageName()));
                        } else if (state == Player.STATE_READY) {
                            nativeAudioActive = true;
                        }
                    }
                    @Override public void onIsPlayingChanged(boolean playing) {
                        isPlaying = playing;
                        nativeAudioActive = playing;
                    }
                });
            } else {
                exoPlayer.stop();
            }
            MediaItem mediaItem = MediaItem.fromUri(url);
            exoPlayer.setMediaItem(mediaItem);
            exoPlayer.prepare();
            exoPlayer.setPlayWhenReady(true);
            isPlaying = true;
            nativeAudioActive = true;
        } catch (Exception e) {
            Log.e("MoodFlow", "playAudioUrl failed", e);
            nativeAudioActive = false;
        }
    }

    private Notification buildNotification() {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("MoodFlow")
                .setContentText(isPlaying ? "Playing" : "Paused")
                .setContentIntent(contentIntent).setOngoing(true).setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setSilent(true).build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        nativeAudioActive = false;
        if (exoPlayer != null) { try { exoPlayer.release(); } catch (Exception ignored) {} exoPlayer = null; }
        try { unregisterReceiver(actionReceiver); } catch (Exception ignored) {}
        try { stopForeground(true); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
