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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MediaBridge {

    private static final String TAG = "MoodFlowBridge";

    private Context ctx;
    private WebView webView;

    public MediaBridge(Context context, WebView wv) {
        this.ctx = context;
        this.webView = wv;
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
    public void startBgService() {
        Intent intent = new Intent(ctx, MediaPlaybackService.class);
        intent.setAction("START");
        startService(intent);
    }

    @JavascriptInterface
    public void stopBgService() {
        Intent intent = new Intent(ctx, MediaPlaybackService.class);
        intent.setAction("STOP");
        ctx.startService(intent);
    }

    private void startService(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }
}
