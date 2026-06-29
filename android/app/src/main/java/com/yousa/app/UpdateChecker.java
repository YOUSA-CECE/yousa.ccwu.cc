package com.yousa.app;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.IntentFilter;
import android.os.Build;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks for app updates on a background thread.
 */
public class UpdateChecker {

    public interface Callback {
        void onResult(boolean hasUpdate, String versionName, String apkUrl, String changelog);
        void onError(String error);
    }

    private static final String VERSION_URL = "https://yousa.ccwu.cc/static/version.json";

    /**
     * Check for updates in a background thread.
     */
    public static void check(final Context ctx, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(VERSION_URL);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.setRequestMethod("GET");

                    int code = conn.getResponseCode();
                    if (code != 200) {
                        cb.onError("Server returned " + code);
                        return;
                    }

                    BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();

                    JSONObject json = new JSONObject(sb.toString());
                    int remoteCode = json.getInt("versionCode");
                    String remoteName = json.getString("versionName");
                    String apkUrl = json.getString("apkUrl");
                    String changelog = json.optString("changelog", "");

                    // Get local version code (works on all API levels)
                    int localCode;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        localCode = (int) ctx.getPackageManager()
                            .getPackageInfo(ctx.getPackageName(), 0)
                            .getLongVersionCode();
                    } else {
                        localCode = ctx.getPackageManager()
                            .getPackageInfo(ctx.getPackageName(), 0)
                            .versionCode;
                    }

                    if (remoteCode > localCode) {
                        cb.onResult(true, remoteName, apkUrl, changelog);
                    } else {
                        cb.onResult(false, remoteName, apkUrl, changelog);
                    }
                } catch (Exception e) {
                    cb.onError(e.getMessage());
                }
            }
        }).start();
    }

    /**
     * Show an update dialog. If user agrees, start downloading.
     */
    public static void showUpdateDialog(final Context ctx,
                                         final String versionName,
                                         final String apkUrl,
                                         final String changelog) {
        new AlertDialog.Builder(ctx)
            .setTitle("发现新版本 v" + versionName)
            .setMessage("更新内容：\n" + changelog)
            .setPositiveButton("立即更新", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String fileName = "yousa-v" + versionName + ".apk";

                    // Register receiver for this download
                    ApkDownloadReceiver receiver = new ApkDownloadReceiver();
                    ctx.registerReceiver(receiver,
                        new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

                    ApkDownloadReceiver.enqueueDownload(ctx, apkUrl, fileName);
                }
            })
            .setNegativeButton("稍后再说", null)
            .show();
    }
}
