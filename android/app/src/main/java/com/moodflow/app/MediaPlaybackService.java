package com.moodflow.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaSessionCompat;
import androidx.media.session.PlaybackStateCompat;
import androidx.media.MediaMetadataCompat;
import android.util.Log;

public class MediaPlaybackService extends Service {

    private static final String CHANNEL_ID = "moodflow_media";
    private static final int NOTIF_ID = 1001;

    static final String ACTION_START = "START";
    static final String ACTION_STOP = "STOP";
    static final String ACTION_UPDATE_META = "UPDATE_META";

    private MediaSessionCompat mediaSession;
    private String currentTitle = "MoodFlow";
    private String currentArtist = "";
    private boolean isPlaying = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        setupMediaSession();
        try { startForeground(NOTIF_ID, buildNotif()); } catch (Exception ignored) {}
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "MoodFlow",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private void setupMediaSession() {
        try {
            mediaSession = new MediaSessionCompat(this, "MoodFlow");
            mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                                  MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
            mediaSession.setCallback(new MediaSessionCompat.Callback() {
                @Override public void onPlay() { broadcastAction("com.moodflow.app.media.PLAY"); }
                @Override public void onPause() { broadcastAction("com.moodflow.app.media.PAUSE"); }
                @Override public void onSkipToNext() { broadcastAction("com.moodflow.app.media.NEXT"); }
                @Override public void onSkipToPrevious() { broadcastAction("com.moodflow.app.media.PREV"); }
            });
            mediaSession.setActive(true);
            updatePlaybackState();
        } catch (Exception e) {
            Log.w("MoodFlow", "MediaSession setup failed", e);
        }
    }

    private void broadcastAction(String action) {
        try { sendBroadcast(new Intent(action)); } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopSelf();
            } else if (ACTION_UPDATE_META.equals(action)) {
                String t = intent.getStringExtra("title");
                String a = intent.getStringExtra("artist");
                boolean p = intent.getBooleanExtra("playing", false);
                if (t != null) currentTitle = t;
                if (a != null) currentArtist = a;
                isPlaying = p;
                updatePlaybackState();
            }
        }
        try { NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotif()); } catch (Exception ignored) {}
        return START_STICKY;
    }

    private void updatePlaybackState() {
        if (mediaSession == null) return;
        try {
            int state = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
            long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE |
                           PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                           PlaybackStateCompat.ACTION_STOP;
            mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions).setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f).build());
            mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
                .build());
        } catch (Exception ignored) {}
    }

    private Notification buildNotif() {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(currentTitle)
                .setContentText(currentArtist)
                .setContentIntent(contentIntent)
                .setOngoing(true).setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSilent(true)
                .setStyle(new MediaStyle()
                    .setMediaSession(mediaSession != null ? mediaSession.getSessionToken() : null)
                    .setShowActionsInCompactView(0, 1, 2))
                .addAction(notifAction(2, android.R.drawable.ic_media_previous, "Previous", "com.moodflow.app.media.PREV"))
                .addAction(notifAction(3,
                    isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                    isPlaying ? "Pause" : "Play",
                    isPlaying ? "com.moodflow.app.media.PAUSE" : "com.moodflow.app.media.PLAY"))
                .addAction(notifAction(4, android.R.drawable.ic_media_next, "Next", "com.moodflow.app.media.NEXT"))
                .build();
    }

    private NotificationCompat.Action notifAction(int reqCode, int icon, String title, String action) {
        Intent i = new Intent(action);
        PendingIntent pi = PendingIntent.getBroadcast(this, reqCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Action(icon, title, pi);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        try {
            if (mediaSession != null) {
                mediaSession.setActive(false);
                mediaSession.release();
                mediaSession = null;
            }
        } catch (Exception ignored) {}
        try { stopForeground(true); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
