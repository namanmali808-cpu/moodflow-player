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

import androidx.core.content.FileProvider;

import java.io.File;

public class MediaBridge {

    private Context ctx;
    private long lastDownloadId = -1;

    public MediaBridge(Context context) {
        this.ctx = context;
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
                            } else {
                                if (c != null) c.close();
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
    public void setPlaying(boolean playing) {
        Intent intent = new Intent(ctx, MediaPlaybackService.class);
        intent.setAction("UPDATE_META");
        intent.putExtra("playing", playing);
        startService(intent);
    }

    @JavascriptInterface
    public void hideMedia() {
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
