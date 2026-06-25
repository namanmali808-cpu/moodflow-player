package com.moodflow.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
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
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MediaBridge {

    private static final String TAG = "MoodFlowBridge";
    private static final String YT_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";

    private Context ctx;
    private WebView webView;

    public MediaBridge(Context context, WebView wv) {
        this.ctx = context;
        this.webView = wv;
    }

    @JavascriptInterface
    public void downloadApk(final String url) {
        if (url == null || url.isEmpty()) return;
        new Thread(() -> {
            try {
                // Download APK directly with HttpURLConnection (more reliable than DownloadManager)
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
                // Method 1: YouTube InnerTube API (most reliable)
                String audioUrl = fetchFromInnerTube(videoId);
                
                // Method 2: Fallback to HTML parsing
                if (audioUrl == null || audioUrl.isEmpty()) {
                    Log.d(TAG, "InnerTube failed, trying HTML parsing for " + videoId);
                    String html = fetchUrl("https://www.youtube.com/watch?v=" + videoId);
                    audioUrl = extractAudioUrl(html);
                }
                
                // Method 3: Fallback to get_video_info
                if (audioUrl == null || audioUrl.isEmpty()) {
                    Log.d(TAG, "HTML parsing failed, trying get_video_info for " + videoId);
                    String info = fetchUrl("https://www.youtube.com/get_video_info?video_id=" + videoId);
                    audioUrl = parseAudioUrlFromInfo(info);
                }

                if (audioUrl != null && !audioUrl.isEmpty()) {
                    Log.d(TAG, "Found audio URL, sending to service for " + videoId);
                    Intent startIntent = new Intent(ctx, MediaPlaybackService.class);
                    startIntent.setAction("UPDATE_META");
                    startIntent.putExtra("videoId", videoId);
                    startIntent.putExtra("playing", true);
                    startService(startIntent);

                    Intent playIntent = new Intent(MediaPlaybackService.ACTION_PLAY_AUDIO);
                    playIntent.setPackage(ctx.getPackageName());
                    playIntent.putExtra(MediaPlaybackService.EXTRA_AUDIO_URL, audioUrl);
                    playIntent.putExtra(MediaPlaybackService.EXTRA_VIDEO_ID, videoId);
                    ctx.sendBroadcast(playIntent);
                } else {
                    Log.e(TAG, "All methods failed to get audio URL for " + videoId);
                }
            } catch (Exception e) {
                Log.e(TAG, "playNativeAudio error for " + videoId, e);
            }
        }).start();
    }

    // YouTube InnerTube API - same API YouTube Android app uses
    private String fetchFromInnerTube(String videoId) throws Exception {
        URL url = new URL("https://www.youtube.com/youtubei/v1/player?key=" + YT_API_KEY);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "com.google.android.youtube/19.09.37 (Linux; U; Android 14)");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        String body = "{\"videoId\":\"" + videoId + "\",\"context\":{\"client\":{\"clientName\":\"ANDROID\",\"clientVersion\":\"19.09.37\"}}}";
        OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream());
        writer.write(body);
        writer.flush();
        writer.close();

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            Log.e(TAG, "InnerTube returned " + responseCode);
            return null;
        }

        String json = new BufferedReader(new InputStreamReader(conn.getInputStream()))
                .lines().collect(Collectors.joining("\n"));

        // Parse streamingData -> adaptiveFormats -> find audio/mp4 with highest bitrate
        Pattern fmtP = Pattern.compile("\"adaptiveFormats\"\\s*:\\s*\\[(.+?)\\]", Pattern.DOTALL);
        Matcher fmtM = fmtP.matcher(json);
        if (!fmtM.find()) return null;

        String formats = fmtM.group(1);
        String[] parts = formats.split("\\},\\{");
        String bestUrl = "";
        int bestBitrate = -1;

        for (String part : parts) {
            boolean isAudio = part.contains("\"mimeType\"") && 
                (part.contains("audio/mp4") || part.contains("audio/webm"));
            if (!isAudio) continue;

            Matcher brM = Pattern.compile("\"bitrate\"\\s*:\\s*(\\d+)").matcher(part);
            int bitrate = 0;
            if (brM.find()) bitrate = Integer.parseInt(brM.group(1));

            Matcher urlM = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(part);
            if (urlM.find() && bitrate > bestBitrate) {
                bestBitrate = bitrate;
                bestUrl = urlM.group(1).replace("\\u0026", "&").replace("\\/", "/");
            }
        }

        return bestUrl.isEmpty() ? null : bestUrl;
    }

    private String fetchUrl(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36");
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        return new BufferedReader(new InputStreamReader(conn.getInputStream()))
                .lines().collect(Collectors.joining("\n"));
    }

    private String extractAudioUrl(String html) {
        Pattern p = Pattern.compile("ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\});\\s*", Pattern.DOTALL);
        Matcher m = p.matcher(html);
        if (!m.find()) return null;
        String json = m.group(1);
        String[] parts = json.split("\"mimeType\"");
        for (int i = 0; i < parts.length; i++) {
            if ((parts[i].contains("audio/mp4") || parts[i].contains("audio/webm")) && parts[i].contains("\"url\"")) {
                Matcher um = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(parts[i]);
                if (um.find()) {
                    return um.group(1).replace("\\u0026", "&").replace("\\/", "/");
                }
            }
        }
        Pattern ap = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
        Matcher am = ap.matcher(json);
        if (am.find()) {
            return am.group(1).replace("\\u0026", "&").replace("\\/", "/");
        }
        return null;
    }

    private String parseAudioUrlFromInfo(String info) {
        try {
            String[] params = java.net.URLDecoder.decode(info, "UTF-8").split("&");
            for (String param : params) {
                if (param.startsWith("player_response=")) {
                    String prJson = param.substring("player_response=".length());
                    String url = extractAudioUrl(prJson);
                    if (url != null && !url.isEmpty()) return url;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void startService(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }
}
