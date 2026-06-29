package com.yousa.app;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public final class UpdateChecker {
    public interface Callback {
        void onResult(boolean hasUpdate, String versionName, String apkUrl,
                      String changelog, long apkSizeBytes);
        void onError(String error);
    }

    private static final String VERSION_URL =
        "https://yousa.ccwu.cc/static/version.json";

    private UpdateChecker() {}

    public static void check(final Context context, final Callback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = openConnection(VERSION_URL, "GET");
                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    callback.onError("服务器返回 " + code);
                    return;
                }
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8"));
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
                reader.close();

                JSONObject json = new JSONObject(body.toString());
                int remoteCode = json.getInt("versionCode");
                String remoteName = json.getString("versionName");
                String apkUrl = json.getString("apkUrl");
                String changelog = json.optString("changelog", "性能与稳定性改进");
                long apkSizeBytes = json.optLong("apkSizeBytes", -1);
                if (apkSizeBytes <= 0) apkSizeBytes = queryContentLength(apkUrl);

                long localCode;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    localCode = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0)
                        .getLongVersionCode();
                } else {
                    localCode = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionCode;
                }
                callback.onResult(remoteCode > localCode, remoteName, apkUrl,
                    changelog, apkSizeBytes);
            } catch (Exception e) {
                callback.onError(e.getMessage() == null ? "更新检查失败" : e.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private static HttpURLConnection openConnection(String address, String method)
        throws Exception {
        HttpURLConnection connection =
            (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestMethod(method);
        connection.setUseCaches(false);
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("User-Agent", "YousaAndroid UpdateChecker");
        return connection;
    }

    private static long queryContentLength(String apkUrl) {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(apkUrl, "HEAD");
            int code = connection.getResponseCode();
            if (code >= 200 && code < 400) return connection.getContentLengthLong();
        } catch (Exception ignored) {
        } finally {
            if (connection != null) connection.disconnect();
        }
        return -1;
    }

    public static void showUpdateDialog(final Context context,
                                        final String versionName,
                                        final String apkUrl,
                                        final String changelog,
                                        final long apkSizeBytes) {
        String size = apkSizeBytes > 0 ? formatSize(apkSizeBytes) : "未知";
        String notes = changelog == null || changelog.trim().isEmpty()
            ? "性能与稳定性改进" : changelog.trim();
        new AlertDialog.Builder(context)
            .setTitle("发现新版本 v" + versionName)
            .setMessage("安装包大小：" + size + "\n\n更新日志：\n" + notes)
            .setPositiveButton("立即更新", (dialog, which) ->
                ApkDownloadReceiver.enqueueApkDownload(
                    context, apkUrl, "yousa-v" + versionName + ".apk"))
            .setNegativeButton("稍后再说", null)
            .show();
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024d);
        }
        return String.format(Locale.getDefault(), "%.1f MB",
            bytes / (1024d * 1024d));
    }
}
