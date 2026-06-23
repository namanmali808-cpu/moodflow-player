package com.moodflow.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "MediaControls")
public class MediaControlsPlugin extends Plugin {

    private static final String ACTION_PLAY = "com.moodflow.app.media.PLAY";
    private static final String ACTION_PAUSE = "com.moodflow.app.media.PAUSE";
    private static final String ACTION_NEXT = "com.moodflow.app.media.NEXT";
    private static final String ACTION_PREV = "com.moodflow.app.media.PREV";

    private boolean serviceRunning = false;
    private boolean permissionRequested = false;
    private WebView webView;

    // ---- JavaScriptInterface (direct bridge, bypasses Capacitor plugin system) ----
    public class MediaJSBridge {
        @JavascriptInterface
        public void updateMedia(String title, String artist, boolean playing) {
            Context ctx = getContext();
            Intent intent = new Intent(ctx, MediaPlaybackService.class);
            intent.setAction("UPDATE_META");
            intent.putExtra("title", title != null ? title : "MoodFlow");
            intent.putExtra("artist", artist != null ? artist : "");
            intent.putExtra("playing", playing);
            startService(ctx, intent);
            serviceRunning = true;
        }
        @JavascriptInterface
        public void setPlaying(boolean playing) {
            if (!serviceRunning) return;
            Intent intent = new Intent(getContext(), MediaPlaybackService.class);
            intent.setAction("UPDATE_META");
            intent.putExtra("playing", playing);
            startService(getContext(), intent);
        }
        @JavascriptInterface
        public void hideMedia() {
            serviceRunning = false;
            Intent intent = new Intent(getContext(), MediaPlaybackService.class);
            intent.setAction("STOP");
            getContext().startService(intent);
        }
        @JavascriptInterface
        public void downloadApk(final String url) {
            if (url == null || url.isEmpty()) return;
            final Context ctx = getContext();
            final String fileName = "MoodFlow-" + System.currentTimeMillis() + ".apk";
            final android.app.DownloadManager dm = (android.app.DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            android.app.DownloadManager.Request req = new android.app.DownloadManager.Request(android.net.Uri.parse(url));
            req.setTitle("MoodFlow Update");
            req.setDescription("Downloading update...");
            req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setMimeType("application/vnd.android.package-archive");
            if (Build.VERSION.SDK_INT >= 29) {
                req.setDestinationInExternalFilesDir(ctx, null, fileName);
            } else {
                req.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName);
            }
            final long downloadId = dm.enqueue(req);
            ctx.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, android.content.Intent intent) {
                    long id = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id == downloadId) {
                        try {
                            android.app.DownloadManager.Query q = new android.app.DownloadManager.Query();
                            q.setFilterById(downloadId);
                            android.database.Cursor c = dm.query(q);
                            if (c != null && c.moveToFirst()) {
                                int status = c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS));
                                if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                                    String uriStr = c.getString(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_LOCAL_URI));
                                    c.close();
                                    android.net.Uri fileUri = android.net.Uri.parse(uriStr);
                                    android.content.Intent install = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                                    if (Build.VERSION.SDK_INT >= 24) {
                                        java.io.File f = new java.io.File(fileUri.getPath());
                                        android.net.Uri contentUri = androidx.core.content.FileProvider.getUriForFile(ctx, "com.moodflow.app.fileprovider", f);
                                        install.setDataAndType(contentUri, "application/vnd.android.package-archive");
                                        install.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                    } else {
                                        install.setDataAndType(fileUri, "application/vnd.android.package-archive");
                                    }
                                    install.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                                    ctx.startActivity(install);
                                } else {
                                    if (c != null) c.close();
                                }
                            }
                        } catch (Exception ignored) {}
                        try { context.unregisterReceiver(this); } catch (Exception ignored) {}
                    }
                }
            }, new android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }
    }

    private void startService(Context ctx, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    @Override
    protected void handleOnStart() {
        super.handleOnStart();
        // Bridge is guaranteed ready here
        try {
            if (webView == null) {
                webView = getBridge().getWebView();
                webView.addJavascriptInterface(new MediaJSBridge(), "MediaBridge");
                // Re-register receiver on the right context (activity context)
                try { getContext().unregisterReceiver(forwardReceiver); } catch (Exception ignored) {}
                IntentFilter filter = new IntentFilter();
                filter.addAction(ACTION_PLAY);
                filter.addAction(ACTION_PAUSE);
                filter.addAction(ACTION_NEXT);
                filter.addAction(ACTION_PREV);
                int flags = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) flags = Context.RECEIVER_NOT_EXPORTED;
                getActivity().registerReceiver(forwardReceiver, filter, flags);
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        // Keep WebView alive when app goes to background (YouTube playback)
        if (webView != null) {
            webView.postDelayed(() -> {
                try { webView.onResume(); } catch (Exception ignored) {}
            }, 500);
        }
    }

    @Override
    public void load() {
        super.load();
        requestNotifPermission();

        // Initial setup - will be re-done in handleOnStart if bridge not ready
        try {
            if (getBridge() != null) {
                webView = getBridge().getWebView();
                if (webView != null) {
                    webView.addJavascriptInterface(new MediaJSBridge(), "MediaBridge");
                }
            }
        } catch (Exception ignored) {}

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PLAY);
        filter.addAction(ACTION_PAUSE);
        filter.addAction(ACTION_NEXT);
        filter.addAction(ACTION_PREV);
        int flags = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) flags = Context.RECEIVER_NOT_EXPORTED;
        getContext().registerReceiver(forwardReceiver, filter, flags);
    }

    private void requestNotifPermission() {
        if (permissionRequested) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        permissionRequested = true;
        try {
            ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        } catch (Exception ignored) {}
    }

    private final BroadcastReceiver forwardReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            String js = null;
            if (ACTION_PLAY.equals(a)) js = "if(window.mediaOnPlay)mediaOnPlay();";
            else if (ACTION_PAUSE.equals(a)) js = "if(window.mediaOnPause)mediaOnPause();";
            else if (ACTION_NEXT.equals(a)) js = "if(window.mediaOnNext)mediaOnNext();";
            else if (ACTION_PREV.equals(a)) js = "if(window.mediaOnPrev)mediaOnPrev();";
            if (js != null && webView != null) {
                final String fjs = js;
                webView.post(() -> webView.evaluateJavascript(fjs, null));
            }
        }
    };

    // ---- Capacitor plugin methods kept as fallback ----
    @PluginMethod
    public void updateMedia(PluginCall call) {
        String title = call.getString("title", "MoodFlow");
        String artist = call.getString("artist", "");
        boolean playing = call.getBoolean("playing", false);
        Context ctx = getContext();
        Intent intent = new Intent(ctx, MediaPlaybackService.class);
        intent.setAction("UPDATE_META");
        intent.putExtra("title", title);
        intent.putExtra("artist", artist);
        intent.putExtra("playing", playing);
        startService(ctx, intent);
        serviceRunning = true;
        call.resolve();
    }
    @PluginMethod
    public void setPlaying(PluginCall call) {
        boolean playing = call.getBoolean("playing", false);
        if (!serviceRunning) { call.resolve(); return; }
        Intent intent = new Intent(getContext(), MediaPlaybackService.class);
        intent.setAction("UPDATE_META");
        intent.putExtra("playing", playing);
        startService(getContext(), intent);
        call.resolve();
    }
    @PluginMethod
    public void hideMedia(PluginCall call) {
        serviceRunning = false;
        Intent intent = new Intent(getContext(), MediaPlaybackService.class);
        intent.setAction("STOP");
        getContext().startService(intent);
        call.resolve();
    }
}
