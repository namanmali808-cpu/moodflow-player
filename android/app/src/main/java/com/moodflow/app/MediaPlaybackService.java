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
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
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
    public static final String ACTION_NEXT = "com.moodflow.app.media.NEXT";
    public static final String ACTION_PREV = "com.moodflow.app.media.PREV";
    public static final String ACTION_PLAY_AUDIO = "com.moodflow.app.media.PLAY_AUDIO";
    public static final String ACTION_STOP_AUDIO = "com.moodflow.app.media.STOP_AUDIO";
    public static final String EXTRA_AUDIO_URL = "audioUrl";
    public static final String EXTRA_VIDEO_ID = "videoId";

    public static boolean nativeAudioActive = false;

    private MediaSession mediaSession;
    private ExoPlayer exoPlayer;
    private String currentTitle = "MoodFlow";
    private String currentArtist = "";
    private boolean isPlaying = false;
    private String currentVideoId = "";

    @Override
    public void onCreate() {
        super.onCreate();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "MoodFlow Player",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Music playback controls");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }

        mediaSession = new MediaSession(this, "MoodFlowSession");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() { sendBroadcast(new Intent(ACTION_PLAY).setPackage(getPackageName())); }
            @Override
            public void onPause() { sendBroadcast(new Intent(ACTION_PAUSE).setPackage(getPackageName())); }
            @Override
            public void onSkipToNext() { sendBroadcast(new Intent(ACTION_NEXT).setPackage(getPackageName())); }
            @Override
            public void onSkipToPrevious() { sendBroadcast(new Intent(ACTION_PREV).setPackage(getPackageName())); }
            @Override
            public void onStop() { stopSelf(); }
        });
        mediaSession.setActive(true);

        exoPlayer = new ExoPlayer.Builder(this).build();
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED) {
                    sendBroadcast(new Intent("com.moodflow.app.media.SONG_ENDED").setPackage(getPackageName()));
                } else if (playbackState == Player.STATE_READY) {
                    nativeAudioActive = true;
                }
            }
            @Override
            public void onIsPlayingChanged(boolean playing) {
                isPlaying = playing;
                nativeAudioActive = playing;
                updateNotification();
            }
        });

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PLAY);
        filter.addAction(ACTION_PAUSE);
        filter.addAction(ACTION_NEXT);
        filter.addAction(ACTION_PREV);
        filter.addAction(ACTION_PLAY_AUDIO);
        filter.addAction(ACTION_STOP_AUDIO);
        int flags = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) flags = Context.RECEIVER_NOT_EXPORTED;
        registerReceiver(actionReceiver, filter, flags);

        try {
            startForeground(NOTIF_ID, buildNotification());
        } catch (Exception e) {
            Log.e("MoodFlow", "startForeground failed", e);
        }
    }

    private final BroadcastReceiver actionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_PLAY.equals(action)) {
                if (exoPlayer != null) { exoPlayer.setPlayWhenReady(true); isPlaying = true; updateNotification(); }
            } else if (ACTION_PAUSE.equals(action)) {
                if (exoPlayer != null) { exoPlayer.setPlayWhenReady(false); isPlaying = false; updateNotification(); }
            } else if (ACTION_PLAY_AUDIO.equals(action)) {
                String url = intent.getStringExtra(EXTRA_AUDIO_URL);
                String vid = intent.getStringExtra(EXTRA_VIDEO_ID);
                if (url != null && !url.isEmpty()) playAudioUrl(url, vid);
            } else if (ACTION_STOP_AUDIO.equals(action)) {
                stopAudio();
            } else if (ACTION_NEXT.equals(action)) {
                sendBroadcast(new Intent("com.moodflow.app.media.NEXT_SONG").setPackage(getPackageName()));
            } else if (ACTION_PREV.equals(action)) {
                sendBroadcast(new Intent("com.moodflow.app.media.PREV_SONG").setPackage(getPackageName()));
            }
        }
    };

    private void playAudioUrl(String url, String videoId) {
        try {
            stopAudio();
            currentVideoId = videoId != null ? videoId : "";
            MediaItem mediaItem = MediaItem.fromUri(url);
            exoPlayer.setMediaItem(mediaItem);
            exoPlayer.prepare();
            exoPlayer.setPlayWhenReady(true);
            isPlaying = true;
            nativeAudioActive = true;
            updateMetadata();
            updateNotification();
        } catch (Exception e) {
            Log.e("MoodFlow", "playAudioUrl failed", e);
            nativeAudioActive = false;
        }
    }

    private void stopAudio() {
        if (exoPlayer != null) { try { exoPlayer.stop(); } catch (Exception ignored) {} }
        isPlaying = false;
        nativeAudioActive = false;
        updateNotification();
    }

    private void updateMetadata() {
        try {
            mediaSession.setMetadata(
                new android.media.MediaMetadata.Builder()
                    .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                    .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
                    .putString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID, currentVideoId)
                    .build()
            );
        } catch (Exception ignored) {}
    }

    private Notification buildNotification() {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int playIcon = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(currentTitle).setContentText(currentArtist)
                .setContentIntent(contentIntent).setOngoing(true).setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, "Previous", pendingBroadcast(ACTION_PREV))
                .addAction(playIcon, isPlaying ? "Pause" : "Play", pendingBroadcast(isPlaying ? ACTION_PAUSE : ACTION_PLAY))
                .addAction(0, "Next", pendingBroadcast(ACTION_NEXT))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setSilent(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }

    private void updateNotification() {
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, buildNotification()); } catch (Exception ignored) {}
        try {
            int state = isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
            mediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                            PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
                    .build());
        } catch (Exception ignored) {}
    }

    private PendingIntent pendingBroadcast(String action) {
        Intent intent = new Intent(action); intent.setPackage(getPackageName());
        return PendingIntent.getBroadcast(this, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("START".equals(action)) {
                try { startForeground(NOTIF_ID, buildNotification()); } catch (Exception ignored) {}
            } else if ("UPDATE_META".equals(action)) {
                if (intent.hasExtra("title")) currentTitle = intent.getStringExtra("title");
                if (intent.hasExtra("artist")) currentArtist = intent.getStringExtra("artist");
                if (intent.hasExtra("videoId")) currentVideoId = intent.getStringExtra("videoId");
                if (intent.hasExtra("playing")) isPlaying = intent.getBooleanExtra("playing", false);
                updateMetadata(); updateNotification();
            }
            if ("STOP".equals(action)) { stopAudio(); stopSelf(); }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (exoPlayer != null) { try { exoPlayer.release(); } catch (Exception ignored) {} exoPlayer = null; }
        nativeAudioActive = false;
        mediaSession.setActive(false); mediaSession.release();
        try { unregisterReceiver(actionReceiver); } catch (Exception ignored) {}
        try { stopForeground(true); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
