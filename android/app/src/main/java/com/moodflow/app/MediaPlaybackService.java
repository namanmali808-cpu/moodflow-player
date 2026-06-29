package com.moodflow.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public class MediaPlaybackService extends Service {

    private static final String CHANNEL_ID = "moodflow_media";
    private static final int NOTIF_ID = 1001;

    static final String ACTION_START = "START";
    static final String ACTION_STOP = "STOP";
    static final String ACTION_UPDATE_META = "UPDATE_META";
    static final String ACTION_PLAY = "com.moodflow.app.media.PLAY";
    static final String ACTION_PAUSE = "com.moodflow.app.media.PAUSE";
    static final String ACTION_NEXT = "com.moodflow.app.media.NEXT";
    static final String ACTION_PREV = "com.moodflow.app.media.PREV";

    private MediaSession mediaSession;
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
            mediaSession = new MediaSession(this, "MoodFlow");
            mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            mediaSession.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { execJs("if(window.mediaOnPlay)mediaOnPlay();"); }
                @Override public void onPause() { execJs("if(window.mediaOnPause)mediaOnPause();"); }
                @Override public void onSkipToNext() { execJs("if(window.mediaOnNext)mediaOnNext();"); }
                @Override public void onSkipToPrevious() { execJs("if(window.mediaOnPrev)mediaOnPrev();"); }
            });
            mediaSession.setActive(true);
            updatePlaybackState();
        } catch (Exception e) {
            Log.w("MoodFlow", "MediaSession failed", e);
        }
    }

    private void execJs(String js) {
        try {
            if (MediaControlsPlugin.webViewRef != null) {
                MediaControlsPlugin.webViewRef.post(() -> {
                    try { MediaControlsPlugin.webViewRef.evaluateJavascript(js, null); } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopSelf();
            } else if (ACTION_PLAY.equals(action)) {
                execJs("if(window.mediaOnPlay)mediaOnPlay();");
            } else if (ACTION_PAUSE.equals(action)) {
                execJs("if(window.mediaOnPause)mediaOnPause();");
            } else if (ACTION_NEXT.equals(action)) {
                execJs("if(window.mediaOnNext)mediaOnNext();");
            } else if (ACTION_PREV.equals(action)) {
                execJs("if(window.mediaOnPrev)mediaOnPrev();");
            } else if (ACTION_UPDATE_META.equals(action)) {
                String t = intent.getStringExtra("title");
                String a = intent.getStringExtra("artist");
                boolean p = intent.getBooleanExtra("playing", false);
                if (t != null) currentTitle = t;
                if (a != null) currentArtist = a;
                isPlaying = p;
                updateMetadata();
                updatePlaybackState();
            }
        }
        try { NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotif()); } catch (Exception ignored) {}
        return START_STICKY;
    }

    private void updateMetadata() {
        if (mediaSession == null) return;
        try {
            mediaSession.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
                .build());
        } catch (Exception ignored) {}
    }

    private void updatePlaybackState() {
        if (mediaSession == null) return;
        try {
            int state = isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                           PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS |
                           PlaybackState.ACTION_STOP;
            mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions).setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f).build());
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
                .addAction(notifAction(2, android.R.drawable.ic_media_previous, "Previous", ACTION_PREV))
                .addAction(notifAction(3,
                    isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                    isPlaying ? "Pause" : "Play",
                    isPlaying ? ACTION_PAUSE : ACTION_PLAY))
                .addAction(notifAction(4, android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
                .build();
    }

    private NotificationCompat.Action notifAction(int reqCode, int icon, String title, String action) {
        Intent i = new Intent(this, MediaPlaybackService.class);
        i.setAction(action);
        PendingIntent pi = PendingIntent.getService(this, reqCode, i,
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
