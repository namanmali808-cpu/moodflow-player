package com.moodflow.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.core.content.FileProvider;
import androidx.core.app.NotificationCompat;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public class MediaBridge {

    private static final String TAG = "MoodFlowBridge";
    private static boolean newPipeInit = false;

    private Context ctx;
    private WebView webView;

    public MediaBridge(Context context, WebView wv) {
        this.ctx = context;
        this.webView = wv;
        if (!newPipeInit) {
            try {
                NewPipe.init(new Downloader() {
                    @Override
                    public Response execute(final Request request) {
                        try {
                            HttpURLConnection conn = (HttpURLConnection) new URL(request.url()).openConnection();
                            conn.setRequestMethod(request.httpMethod());
                            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36");
                            for (java.util.Map.Entry<String, java.util.List<String>> h : request.headers().entrySet()) {
                                if (h.getValue() != null && !h.getValue().isEmpty()) {
                                    conn.setRequestProperty(h.getKey(), h.getValue().get(0));
                                }
                            }
                            conn.setConnectTimeout(10000);
                            conn.setReadTimeout(10000);
                            conn.setInstanceFollowRedirects(true);
                            int code = conn.getResponseCode();
                            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                            String body = new BufferedReader(new InputStreamReader(is))
                                .lines().collect(java.util.stream.Collectors.joining("\n"));
                            java.util.Map<String, java.util.List<String>> respHeaders = conn.getHeaderFields();
                            return new Response(code, body, respHeaders, request.url(), null);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
                newPipeInit = true;
            } catch (Exception e) {
                Log.e(TAG, "NewPipe init failed", e);
            }
        }
    }

    @JavascriptInterface
    public int getVersionCode() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    @JavascriptInterface
    public void downloadApk(final String url) {
        if (url == null || url.isEmpty()) return;
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36");
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.connect();

                int len = conn.getContentLength();
                String fileName = "MoodFlow-" + System.currentTimeMillis() + ".apk";

                File dir;
                if (Build.VERSION.SDK_INT >= 29) {
                    dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                } else {
                    dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                }
                if (dir != null && !dir.exists()) dir.mkdirs();
                File apkFile = new File(dir, fileName);

                InputStream in = conn.getInputStream();
                FileOutputStream out = new FileOutputStream(apkFile);
                byte[] buf = new byte[8192];
                int read, total = 0;
                int lastPct = -1;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    total += read;
                    if (len > 0) {
                        int pct = total * 100 / len;
                        if (pct != lastPct) {
                            lastPct = pct;
                            showDownloadNotification(pct);
                        }
                    }
                }
                in.close();
                out.close();
                conn.disconnect();

                dismissDownloadNotification();
                installApk(apkFile);
            } catch (Exception e) {
                Log.e(TAG, "downloadApk failed", e);
            }
        }).start();
    }

    private void showDownloadNotification(int pct) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel("download", "Downloads", NotificationManager.IMPORTANCE_LOW));
        }
        Notification notif = new NotificationCompat.Builder(ctx, "download")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("MoodFlow Update")
            .setContentText("Downloading... " + pct + "%")
            .setProgress(100, pct, false)
            .setOngoing(true)
            .build();
        nm.notify(1001, notif);
    }

    private void dismissDownloadNotification() {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(1001);
        Notification notif = new NotificationCompat.Builder(ctx, "download")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("MoodFlow Update")
            .setContentText("Download complete")
            .setAutoCancel(true)
            .setProgress(0, 0, false)
            .build();
        nm.notify(1001, notif);
    }

    private void installApk(File apkFile) {
        Intent install = new Intent(Intent.ACTION_VIEW);
        Uri uri = FileProvider.getUriForFile(ctx, "com.moodflow.app.fileprovider", apkFile);
        install.setDataAndType(uri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(install);
    }

    @JavascriptInterface
    public void updateMedia(String title, String artist, boolean playing) {
        Intent intent = new Intent(ctx, MediaPlaybackService.class);
        intent.setAction("UPDATE_META");
        intent.putExtra("title", title != null ? title : "MoodFlow");
        intent.putExtra("artist", artist != null ? artist : "");
        intent.putExtra("playing", playing);
        startService(intent);
    }

    @JavascriptInterface
    public void updateMediaWithVid(String title, String artist, String videoId, boolean playing) {
        Intent intent = new Intent(ctx, MediaPlaybackService.class);
        intent.setAction("UPDATE_META");
        intent.putExtra("title", title != null ? title : "MoodFlow");
        intent.putExtra("artist", artist != null ? artist : "");
        intent.putExtra("videoId", videoId != null ? videoId : "");
        intent.putExtra("playing", playing);
        startService(intent);
    }

    @JavascriptInterface
    public void setPlaying(boolean playing) {
        Intent intent = new Intent(ctx, MediaPlaybackService.class);
        intent.setAction("UPDATE_META");
        intent.putExtra("playing", playing);
        startService(intent);
    }

    @JavascriptInterface
    public void play() {
        Intent intent = new Intent(MediaPlaybackService.ACTION_PLAY);
        intent.setPackage(ctx.getPackageName());
        ctx.sendBroadcast(intent);
    }

    @JavascriptInterface
    public void pause() {
        Intent intent = new Intent(MediaPlaybackService.ACTION_PAUSE);
        intent.setPackage(ctx.getPackageName());
        ctx.sendBroadcast(intent);
    }

    @JavascriptInterface
    public void hideMedia() {
        Intent intent = new Intent(ctx, MediaPlaybackService.class);
        intent.setAction("STOP");
        ctx.startService(intent);
    }

    @JavascriptInterface
    public void playNativeAudio(final String videoId) {
        new Thread(() -> {
            try {
                StreamInfo info = StreamInfo.getInfo("https://www.youtube.com/watch?v=" + videoId);
                List<AudioStream> audioStreams = info.getAudioStreams();
                String audioUrl = null;
                int bestQuality = -1;
                for (AudioStream as : audioStreams) {
                    if (as.getAverageBitrate() > bestQuality) {
                        bestQuality = as.getAverageBitrate();
                        audioUrl = as.getUrl();
                    }
                }
                if (audioUrl == null && info.getVideoStreams() != null && !info.getVideoStreams().isEmpty()) {
                    audioUrl = info.getVideoStreams().get(0).getUrl();
                }
                if (audioUrl != null && !audioUrl.isEmpty()) {
                    Log.d(TAG, "NewPipe audio URL OK for " + videoId);
                    Intent metaIntent = new Intent(ctx, MediaPlaybackService.class);
                    metaIntent.setAction("UPDATE_META");
                    metaIntent.putExtra("videoId", videoId);
                    metaIntent.putExtra("playing", true);
                    startService(metaIntent);
                    Intent playIntent = new Intent(MediaPlaybackService.ACTION_PLAY_AUDIO);
                    playIntent.setPackage(ctx.getPackageName());
                    playIntent.putExtra(MediaPlaybackService.EXTRA_AUDIO_URL, audioUrl);
                    playIntent.putExtra(MediaPlaybackService.EXTRA_VIDEO_ID, videoId);
                    ctx.sendBroadcast(playIntent);
                } else {
                    Log.e(TAG, "NewPipe: no audio URL for " + videoId);
                }
            } catch (Exception e) {
                Log.e(TAG, "playNativeAudio error for " + videoId, e);
            }
        }).start();
    }

    private void startService(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }
}
