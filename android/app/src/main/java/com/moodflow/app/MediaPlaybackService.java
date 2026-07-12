package com.moodflow.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.webkit.WebView;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import android.support.v4.media.session.MediaSessionCompat;

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
    static final String ACTION_PLAY_STREAM = "PLAY_STREAM";
    static final String ACTION_NATIVE_PAUSE = "com.moodflow.app.media.NATIVE_PAUSE";

    private MediaSession mediaSession;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private String currentTitle = "MoodFlow";
    private String currentArtist = "";
    private boolean isPlaying = false;

    private MediaPlayer mediaPlayer;
    private String streamUrl;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        setupAudioManager();
        setupMediaSession();
        try { startForeground(NOTIF_ID, buildNotif()); } catch (Exception ignored) {}
        acquireWakeLock();
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MoodFlow:audio");
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
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

    private void setupAudioManager() {
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .build();
        }
    }

    private void setupMediaSession() {
        try {
            mediaSession = new MediaSession(this, "MoodFlow");
            mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            mediaSession.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (mediaPlayer != null) { resumePlayback(); }
                        else {
                            execJs("if(window.mediaOnPlay)mediaOnPlay();");
                            syncStateWithWebView();
                        }
                    });
                }
                @Override public void onPause() {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (mediaPlayer != null) { pausePlayback(); }
                        else {
                            execJs("if(window.mediaOnPause)mediaOnPause();");
                            syncStateWithWebView();
                        }
                    });
                }
                @Override public void onSkipToNext() {
                    new Handler(Looper.getMainLooper()).post(() -> execJs("if(window.mediaOnNext)mediaOnNext();"));
                }
                @Override public void onSkipToPrevious() {
                    new Handler(Looper.getMainLooper()).post(() -> execJs("if(window.mediaOnPrev)mediaOnPrev();"));
                }
            });
            mediaSession.setActive(true);
            updatePlaybackState();
        } catch (Exception e) {
            Log.w("MoodFlow", "MediaSession failed", e);
        }
    }

    private void playStream(String url) {
        if (url == null || url.isEmpty()) return;
        streamUrl = url;
        stopPlayback();
        try {
            mediaPlayer = new MediaPlayer();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            }
            mediaPlayer.setDataSource(url);
            mediaPlayer.setOnPreparedListener(mp -> {
                requestAudioFocus();
                mp.start();
                isPlaying = true;
                acquireWakeLock();
                updatePlaybackState();
                updateNotification();
                sendBroadcast(new Intent("com.moodflow.app.media.AUDIO_STARTED"));
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                releaseWakeLock();
                sendBroadcast(new Intent("com.moodflow.app.media.SONG_ENDED"));
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.w("MoodFlow", "MediaPlayer error: " + what + " " + extra);
                stopPlayback();
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e("MoodFlow", "playStream failed", e);
            mediaPlayer = null;
        }
    }

    private void pausePlayback() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            isPlaying = false;
            releaseWakeLock();
            updatePlaybackState();
            updateNotification();
            execJs("if(window.syncPlaying)syncPlaying(false);");
        }
    }

    private void resumePlayback() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
            isPlaying = true;
            acquireWakeLock();
            updatePlaybackState();
            updateNotification();
            execJs("if(window.syncPlaying)syncPlaying(true);");
        }
    }

    private void stopPlayback() {
        try {
            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
                mediaPlayer = null;
            }
        } catch (Exception ignored) {}
        releaseWakeLock();
    }

    private void requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.requestAudioFocus(audioFocusRequest);
            } else {
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            }
        } catch (Exception ignored) {}
    }

    private void execJs(String js) {
        WebView wv = MediaControlsPlugin.webViewRef;
        if (wv == null) return;
        try { wv.loadUrl("javascript:" + js); return; } catch (Exception ignored) {}
        try { wv.evaluateJavascript(js, null); return; } catch (Exception ignored) {}
        try { wv.post(() -> { try { wv.evaluateJavascript(js, null); } catch (Exception ignored) {} }); } catch (Exception ignored) {}
    }

    private void syncStateWithWebView() {
        execJs("if(window.syncPlaying)syncPlaying(" + isPlaying + ");");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopPlayback();
                stopSelf();
            } else if (ACTION_UPDATE_META.equals(action)) {
                String t = intent.getStringExtra("title");
                String a = intent.getStringExtra("artist");
                boolean p = intent.getBooleanExtra("playing", false);
                if (t != null) currentTitle = t;
                if (a != null) currentArtist = a;
                isPlaying = p;
                if (isPlaying) { acquireWakeLock(); } else { releaseWakeLock(); }
                updateMetadata();
                updatePlaybackState();
            } else if (ACTION_PLAY.equals(action)) {
                if (mediaPlayer != null) { resumePlayback(); }
                else {
                    execJs("if(window.mediaOnPlay)mediaOnPlay();");
                    syncStateWithWebView();
                }
            } else if (ACTION_PAUSE.equals(action)) {
                if (mediaPlayer != null) { pausePlayback(); }
                else {
                    execJs("if(window.mediaOnPause)mediaOnPause();");
                    syncStateWithWebView();
                }
            } else if (ACTION_PLAY_STREAM.equals(action)) {
                String url = intent.getStringExtra("url");
                if (url != null) playStream(url);
            } else if (ACTION_NATIVE_PAUSE.equals(action)) {
                stopPlayback();
                isPlaying = false;
                updateNotification();
                updatePlaybackState();
            } else if (ACTION_NEXT.equals(action)) {
                execJs("if(window.mediaOnNext)mediaOnNext();");
            } else if (ACTION_PREV.equals(action)) {
                execJs("if(window.mediaOnPrev)mediaOnPrev();");
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

    private void updateNotification() {
        try { NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotif()); } catch (Exception ignored) {}
    }

    private Notification buildNotif() {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        MediaStyle style = new MediaStyle();
        if (mediaSession != null) {
            style.setMediaSession(MediaSessionCompat.Token.fromToken(mediaSession.getSessionToken()));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            style.setShowActionsInCompactView(0, 1, 2);
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(currentTitle)
                .setContentText(currentArtist)
                .setContentIntent(contentIntent)
                .setOngoing(true).setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSilent(true)
                .setStyle(style)
                .addAction(notifAction(2, android.R.drawable.ic_media_previous, "Previous", ACTION_PREV))
                .addAction(notifAction(3,
                    isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                    isPlaying ? "Pause" : "Play",
                    isPlaying ? ACTION_PAUSE : ACTION_PLAY))
                .addAction(notifAction(4, android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
                .build();
    }

    private NotificationCompat.Action notifAction(int reqCode, int icon, String title, String serviceAction) {
        Intent i = new Intent(this, MediaPlaybackService.class);
        i.setAction(serviceAction);
        int flags = PendingIntent.FLAG_CANCEL_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        } else {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pi = PendingIntent.getForegroundService(this, reqCode, i, flags);
        } else {
            pi = PendingIntent.getService(this, reqCode, i, flags);
        }
        return new NotificationCompat.Action(icon, title, pi);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        stopPlayback();
        releaseWakeLock();
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
