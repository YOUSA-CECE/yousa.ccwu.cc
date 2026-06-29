package com.yousa.app;

import android.content.Context;
import android.os.Build;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class UpdateChecker {
    public interface Callback {
        void onResult(boolean hasUpdate, int versionCode, String versionName,
                      String apkUrl, String changelog, long apkSizeBytes);
        void onError(String error);
    }

    private static final String[] VERSION_URLS = {
        "https://yousa.ccwu.cc/static/version.json",
        "https://cdn.jsdelivr.net/gh/YOUSA-CECE/yousa.ccwu.cc@master/static/version.json",
        "https://raw.githubusercontent.com/YOUSA-CECE/yousa.ccwu.cc/master/static/version.json"
    };

    private UpdateChecker() {}

    public static void check(final Context context, final Callback callback) {
        new Thread(() -> {
            ExecutorService executor = Executors.newFixedThreadPool(VERSION_URLS.length);
            try {
                CompletionService<VersionInfo> completion =
                    new ExecutorCompletionService<>(executor);
                List<Future<VersionInfo>> futures = new ArrayList<>();
                for (String address : VERSION_URLS) {
                    futures.add(completion.submit(() -> fetchVersion(address)));
                }

                VersionInfo newest = null;
                int completed = 0;
                int successful = 0;
                long overallDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                long resultDeadline = overallDeadline;

                while (completed < VERSION_URLS.length) {
                    long remaining = resultDeadline - System.nanoTime();
                    if (remaining <= 0) break;
                    Future<VersionInfo> future = completion.poll(remaining, TimeUnit.NANOSECONDS);
                    if (future == null) break;
                    completed++;
                    try {
                        VersionInfo candidate = future.get();
                        successful++;
                        if (newest == null || candidate.versionCode > newest.versionCode) {
                            newest = candidate;
                        }
                        if (successful == 1) {
                            resultDeadline = Math.min(overallDeadline,
                                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1400));
                        }
                    } catch (Exception ignored) {
                    }
                }
                for (Future<VersionInfo> future : futures) future.cancel(true);

                if (newest == null) {
                    callback.onError("无法连接更新服务器");
                    return;
                }

                long localCode;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    localCode = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0)
                        .getLongVersionCode();
                } else {
                    localCode = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionCode;
                }
                callback.onResult(newest.versionCode > localCode, newest.versionCode,
                    newest.versionName, newest.apkUrl, newest.changelog,
                    newest.apkSizeBytes);
            } catch (Exception e) {
                callback.onError(e.getMessage() == null ? "更新检查失败" : e.getMessage());
            } finally {
                executor.shutdownNow();
            }
        }).start();
    }

    private static VersionInfo fetchVersion(String address) throws Exception {
        HttpURLConnection connection = openConnection(
            address + (address.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis(),
            "GET");
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("更新服务器返回 " + code);
            }
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), "UTF-8"));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            reader.close();

            JSONObject json = new JSONObject(body.toString());
            VersionInfo info = new VersionInfo();
            info.versionCode = json.getInt("versionCode");
            info.versionName = json.getString("versionName");
            info.apkUrl = json.getString("apkUrl");
            info.changelog = json.optString("changelog", "性能与稳定性改进");
            info.apkSizeBytes = json.optLong("apkSizeBytes", -1);
            if (info.apkSizeBytes <= 0) {
                info.apkSizeBytes = queryContentLength(info.apkUrl);
            }
            return info;
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String address, String method)
        throws Exception {
        HttpURLConnection connection =
            (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod(method);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Cache-Control", "no-cache, no-store");
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

    public static String formatSize(long bytes) {
        if (bytes < 0) return "未知";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024d);
        }
        return String.format(Locale.getDefault(), "%.1f MB",
            bytes / (1024d * 1024d));
    }

    private static final class VersionInfo {
        int versionCode;
        String versionName;
        String apkUrl;
        String changelog;
        long apkSizeBytes;
    }
}
