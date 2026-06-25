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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MediaBridge {

    private static final String TAG = "MoodFlowBridge";
    private Context ctx;
    private WebView webView;

    private static final String[] INVIDIOUS = {
        "https://invidious.snopyta.org", "https://yewtu.be", "https://inv.riverside.rocks",
        "https://invidious.nerdvpn.de", "https://inv.vern.cc", "https://invidious.projectsegfau.lt",
        "https://invidious.privacydev.net", "https://inv.nadeko.net", "https://inv.odyssey346.dev",
        "https://yt.artemislena.eu", "https://invidious.fliegendewurst.eu", "https://invidious.weho.st",
        "https://invidious.jae.su", "https://invidious.001101.lu"
    };
    private static final String[] PIPED = {
        "https://pipedapi.kavin.rocks", "https://piped-api.garudalinux.org", "https://api.piped.privacydev.net",
        "https://pipedapi.syncpundit.io", "https://pipedapi.astrid.tech", "https://piped.moomoo.me",
        "https://pipedapi.r4fo.com", "https://pipedapi.adminforge.de"
    };
    private static final ExecutorService pool = Executors.newCachedThreadPool();

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
    public String getStreamUrl(String videoId) {
        List<Future<String>> futures = new ArrayList<>();
        for (String base : INVIDIOUS) {
            final String url = base + "/api/v1/videos/" + videoId;
            futures.add(pool.submit(() -> fetchInvidious(url)));
        }
        for (String base : PIPED) {
            final String url = base + "/streams/" + videoId;
            futures.add(pool.submit(() -> fetchPiped(url)));
        }
        long deadline = System.currentTimeMillis() + 10000;
        String result = null;
        try {
            while (System.currentTimeMillis() < deadline) {
                for (Future<String> f : futures) {
                    if (f.isDone()) {
                        String val = f.get();
                        if (val != null && !val.isEmpty()) { result = val; break; }
                    }
                }
                if (result != null) break;
                Thread.sleep(50);
            }
        } catch (Exception ignored) {}
        for (Future<String> f : futures) f.cancel(true);
        return result;
    }

    private String fetchInvidious(String apiUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(apiUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.connect();
            if (conn.getResponseCode() != 200) return null;
            // Prefer m4a (mp4) audio - most compatible with WebView
            String json = new BufferedReader(new InputStreamReader(conn.getInputStream()))
                .lines().collect(Collectors.joining("\n"));
            int afIdx = json.indexOf("\"adaptiveFormats\"");
            if (afIdx < 0) return null;
            int arrStart = json.indexOf("[", afIdx);
            if (arrStart < 0) return null;
            int depth = 0, arrEnd = -1;
            for (int i = arrStart; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '[') depth++;
                else if (c == ']') { depth--; if (depth == 0) { arrEnd = i + 1; break; } }
            }
            if (arrEnd < 0) return null;
            String arrContent = json.substring(arrStart, arrEnd);
            Pattern urlP = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
            Pattern typeP = Pattern.compile("\"type\"\\s*:\\s*\"([^\"]+)\"");
            Pattern bitrateP = Pattern.compile("\"bitrate\"\\s*:\\s*(\\d+)");
            Matcher um = urlP.matcher(arrContent);
            String bestUrl = null;
            int bestScore = -1;
            while (um.find()) {
                String u = decodeJsonString(um.group(1));
                int start = Math.max(0, um.start() - 300);
                String ctxStr = arrContent.substring(start, um.start());
                Matcher tm = typeP.matcher(ctxStr);
                String mime = tm.find() ? tm.group(1) : "";
                Matcher bm = bitrateP.matcher(ctxStr);
                int bitrate = bm.find() ? Integer.parseInt(bm.group(1)) : 0;
                int score = 0;
                if (mime.contains("audio")) score += 100;
                if (mime.contains("mp4") || mime.contains("m4a")) score += 200;
                if (mime.contains("webm")) score += 50;
                score += Math.min(bitrate / 1000, 100);
                if (score > bestScore) { bestScore = score; bestUrl = u; }
            }
            return bestUrl;
        } catch (Exception e) { return null; }
        finally { if (conn != null) conn.disconnect(); }
    }

    private String fetchPiped(String apiUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(apiUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.connect();
            if (conn.getResponseCode() != 200) return null;
            // Prefer m4a (mp4) audio - most compatible with WebView
            String json = new BufferedReader(new InputStreamReader(conn.getInputStream()))
                .lines().collect(Collectors.joining("\n"));
            int audioIdx = json.indexOf("\"audioStreams\"");
            if (audioIdx < 0) return null;
            int arrStart = json.indexOf("[", audioIdx);
            if (arrStart < 0) return null;
            int depth = 0, arrEnd = -1;
            for (int i = arrStart; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '[') depth++;
                else if (c == ']') { depth--; if (depth == 0) { arrEnd = i + 1; break; } }
            }
            if (arrEnd < 0) return null;
            String arrContent = json.substring(arrStart, arrEnd);
            Pattern urlP = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
            Pattern bitrateP = Pattern.compile("\"bitrate\"\\s*:\\s*(\\d+)");
            Pattern mimeP = Pattern.compile("\"mimeType\"\\s*:\\s*\"([^\"]+)\"");
            Matcher um = urlP.matcher(arrContent);
            String bestUrl = null;
            int bestRate = -1;
            while (um.find()) {
                String u = decodeJsonString(um.group(1));
                int start = Math.max(0, um.start() - 250);
                String ctxStr = arrContent.substring(start, um.start());
                Matcher bm = bitrateP.matcher(ctxStr);
                int rate = bm.find() ? Integer.parseInt(bm.group(1)) : 0;
                Matcher mm = mimeP.matcher(ctxStr);
                String mime = mm.find() ? mm.group(1) : "";
                // Prefer m4a over webm
                if (mime.contains("mp4") || mime.contains("m4a")) rate += 100000;
                if (rate > bestRate) { bestRate = rate; bestUrl = u; }
            }
            return bestUrl;
        } catch (Exception e) { return null; }
        finally { if (conn != null) conn.disconnect(); }
    }

    private String decodeJsonString(String s) {
        return s.replace("\\u0026", "&").replace("\\/", "/").replace("\\\\", "\\");
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
                        if (pct != lastPct) { lastPct = pct; showDownloadNotification(pct); }
                    }
                }
                in.close(); out.close(); conn.disconnect();
                dismissDownloadNotification();
                installApk(apkFile);
            } catch (Exception e) { Log.e(TAG, "downloadApk failed", e); }
        }).start();
    }

    private void showDownloadNotification(int pct) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel("download", "Downloads", NotificationManager.IMPORTANCE_LOW));
        }
        Notification notif = new NotificationCompat.Builder(ctx, "download")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("MoodFlow Update").setContentText("Downloading... " + pct + "%")
            .setProgress(100, pct, false).setOngoing(true).build();
        nm.notify(1001, notif);
    }

    private void dismissDownloadNotification() {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(1001);
        Notification notif = new NotificationCompat.Builder(ctx, "download")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("MoodFlow Update").setContentText("Download complete")
            .setAutoCancel(true).setProgress(0, 0, false).build();
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
    public void startBgService(String videoId) {
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
