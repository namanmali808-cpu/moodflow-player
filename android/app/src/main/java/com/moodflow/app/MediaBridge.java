package com.moodflow.app;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MediaBridge {

    private Context ctx;
    private WebView webView;
    private long lastDownloadId = -1;

    public MediaBridge(Context context, WebView wv) {
        this.ctx = context;
        this.webView = wv;
    }

    @JavascriptInterface
    public void downloadApk(String url) {
        if (url == null || url.isEmpty()) return;
        String fileName = "MoodFlow-" + System.currentTimeMillis() + ".apk";
        DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
        req.setTitle("MoodFlow Update");
        req.setDescription("Downloading update...");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setMimeType("application/vnd.android.package-archive");
        if (Build.VERSION.SDK_INT >= 29) {
            req.setDestinationInExternalFilesDir(ctx, null, fileName);
        } else {
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        }
        final long downloadId = dm.enqueue(req);
        lastDownloadId = downloadId;
        ctx.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) {
                    try {
                        DownloadManager.Query q = new DownloadManager.Query();
                        q.setFilterById(downloadId);
                        Cursor c = dm.query(q);
                        if (c != null && c.moveToFirst()) {
                            int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                String uriStr = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                                c.close();
                                Uri fileUri = Uri.parse(uriStr);
                                Intent install = new Intent(Intent.ACTION_VIEW);
                                if (Build.VERSION.SDK_INT >= 24) {
                                    File f = new File(fileUri.getPath());
                                    Uri contentUri = FileProvider.getUriForFile(ctx, "com.moodflow.app.fileprovider", f);
                                    install.setDataAndType(contentUri, "application/vnd.android.package-archive");
                                    install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                } else {
                                    install.setDataAndType(fileUri, "application/vnd.android.package-archive");
                                }
                                install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                ctx.startActivity(install);
                            }
                        }
                    } catch (Exception ignored) {}
                    try { context.unregisterReceiver(this); } catch (Exception ignored) {}
                }
            }
        }, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
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
                String html = fetchUrl("https://www.youtube.com/watch?v=" + videoId);
                String audioUrl = extractAudioUrl(html);
                if (audioUrl.isEmpty()) {
                    String info = fetchUrl("https://www.youtube.com/get_video_info?video_id=" + videoId);
                    audioUrl = parseAudioUrlFromInfo(info);
                }
                final String finalUrl = audioUrl;
                if (!finalUrl.isEmpty()) {
                    // Ensure service is running (sendBroadcast goes to registered BroadcastReceiver in service)
                    Intent startIntent = new Intent(ctx, MediaPlaybackService.class);
                    startIntent.setAction("UPDATE_META");
                    startIntent.putExtra("videoId", videoId);
                    startIntent.putExtra("playing", true);
                    startService(startIntent);
                    // Send broadcast with audio URL to service's BroadcastReceiver
                    Intent playIntent = new Intent(MediaPlaybackService.ACTION_PLAY_AUDIO);
                    playIntent.setPackage(ctx.getPackageName());
                    playIntent.putExtra(MediaPlaybackService.EXTRA_AUDIO_URL, finalUrl);
                    playIntent.putExtra(MediaPlaybackService.EXTRA_VIDEO_ID, videoId);
                    ctx.sendBroadcast(playIntent);
                }
            } catch (Exception ignored) {}
        }).start();
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
        if (!m.find()) return "";
        String json = m.group(1);
        String[] parts = json.split("\"mimeType\"");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].contains("audio/mp4") && parts[i].contains("\"url\"")) {
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
        return "";
    }

    private String parseAudioUrlFromInfo(String info) {
        try {
            String decoded = URLDecoder.decode(info, "UTF-8");
            String[] params = decoded.split("&");
            for (String param : params) {
                if (param.startsWith("player_response=")) {
                    String prJson = param.substring("player_response=".length());
                    return extractAudioUrl(prJson);
                }
            }
        } catch (UnsupportedEncodingException ignored) {}
        return "";
    }

    private void startService(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }
}
