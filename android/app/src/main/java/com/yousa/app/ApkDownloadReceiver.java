package com.yousa.app;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ApkDownloadReceiver extends BroadcastReceiver {
    private static final String PREFS = "yousa_downloads";
    private static final String UPDATE_DOWNLOAD_ID = "update_download_id";
    private static final String READY_DOWNLOAD_ID = "ready_download_id";
    private static final String WEB_MIME_PREFIX = "web_download_mime_";
    private static final String WEB_NAME_PREFIX = "web_download_name_";
    private static final String WEB_URL_ID_PREFIX = "web_download_url_id_";
    private static final String WEB_URL_PREFIX = "web_download_url_";
    private static final String WEB_AGENT_PREFIX = "web_download_agent_";
    private static final String WEB_COOKIE_PREFIX = "web_download_cookie_";
    private static final String WEB_REFERER_PREFIX = "web_download_referer_";
    private static final String WEB_PATH_PREFIX = "web_download_path_";
    private static final String READY_WEB_DOWNLOAD_ID = "ready_web_download_id";
    public static final String EXTRA_INSTALL_UPDATE = "install_downloaded_update";

    public static long enqueueApkDownload(Context context, String apkUrl, String fileName) {
        DownloadManager manager =
            (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
        request.setTitle("yousa 新版本");
        request.setDescription("正在下载更新，完成后将自动安装…");
        request.setNotificationVisibility(
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(true);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        request.setMimeType("application/vnd.android.package-archive");
        long id = manager.enqueue(request);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(UPDATE_DOWNLOAD_ID, id)
            .remove(READY_DOWNLOAD_ID)
            .apply();
        Toast.makeText(context, "新版已开始下载", Toast.LENGTH_SHORT).show();
        return id;
    }

    public static boolean hasPendingInstall(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(READY_DOWNLOAD_ID, -1) >= 0;
    }

    public static void registerWebDownload(Context context, long id,
                                           String url, String userAgent, String cookies,
                                           String referer, String mimeType,
                                           String fileName, String relativePath) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(WEB_MIME_PREFIX + id, mimeType == null ? "" : mimeType)
            .putString(WEB_NAME_PREFIX + id, fileName == null ? "" : fileName)
            .putString(WEB_URL_PREFIX + id, url)
            .putString(WEB_AGENT_PREFIX + id, userAgent == null ? "" : userAgent)
            .putString(WEB_COOKIE_PREFIX + id, cookies == null ? "" : cookies)
            .putString(WEB_REFERER_PREFIX + id, referer == null ? "" : referer)
            .putString(WEB_PATH_PREFIX + id, relativePath)
            .putLong(WEB_URL_ID_PREFIX + url.hashCode(), id)
            .apply();
    }

    public static boolean retryWebDownload(Context context, long oldId) {
        android.content.SharedPreferences prefs =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String url = prefs.getString(WEB_URL_PREFIX + oldId, "");
        String fileName = prefs.getString(WEB_NAME_PREFIX + oldId, "");
        String mimeType = prefs.getString(WEB_MIME_PREFIX + oldId, "");
        String path = prefs.getString(WEB_PATH_PREFIX + oldId, "");
        if (url == null || url.isEmpty() || path == null || path.isEmpty()) return false;

        DownloadManager manager =
            (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        manager.remove(oldId);
        try {
            DownloadManager.Request request =
                new DownloadManager.Request(Uri.parse(url));
            request.setTitle(fileName);
            request.setDescription("正在重新下载…");
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, path);
            if (mimeType != null && !mimeType.isEmpty()) request.setMimeType(mimeType);
            String agent = prefs.getString(WEB_AGENT_PREFIX + oldId, "");
            String cookies = prefs.getString(WEB_COOKIE_PREFIX + oldId, "");
            String referer = prefs.getString(WEB_REFERER_PREFIX + oldId, "");
            if (agent != null && !agent.isEmpty()) request.addRequestHeader("User-Agent", agent);
            if (cookies != null && !cookies.isEmpty()) request.addRequestHeader("Cookie", cookies);
            if (referer != null && !referer.isEmpty()) request.addRequestHeader("Referer", referer);
            request.addRequestHeader("Accept-Encoding", "identity");
            long newId = manager.enqueue(request);
            registerWebDownload(context, newId, url, agent, cookies, referer,
                mimeType, fileName, path);
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    public static boolean openOrReuseWebDownload(Context context, String url) {
        long id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(WEB_URL_ID_PREFIX + url.hashCode(), -1);
        if (id < 0) return false;

        DownloadManager manager =
            (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
        try (Cursor cursor = manager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) return false;
            int status = cursor.getInt(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL
                && manager.getUriForDownloadedFile(id) != null) {
                openCompletedWebDownload(context, manager, id);
                return true;
            }
            if (status == DownloadManager.STATUS_PENDING
                || status == DownloadManager.STATUS_RUNNING
                || status == DownloadManager.STATUS_PAUSED) {
                Toast.makeText(context, "文件正在下载，请稍候", Toast.LENGTH_SHORT).show();
                return true;
            }
        }
        return false;
    }

    public static long[] getTrackedWebDownloadIds(Context context) {
        Map<String, ?> values = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getAll();
        List<Long> ids = new ArrayList<>();
        for (String key : values.keySet()) {
            if (!key.startsWith(WEB_NAME_PREFIX)) continue;
            try {
                ids.add(Long.parseLong(key.substring(WEB_NAME_PREFIX.length())));
            } catch (NumberFormatException ignored) {
                // Ignore damaged legacy preference entries.
            }
        }
        long[] result = new long[ids.size()];
        for (int i = 0; i < ids.size(); i++) result[i] = ids.get(i);
        return result;
    }

    public static void openWebDownloadById(Context context, long id) {
        DownloadManager manager =
            (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        openCompletedWebDownload(context, manager, id);
    }

    public static boolean canInstallPackages(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
            || context.getPackageManager().canRequestPackageInstalls();
    }

    public static void requestInstallPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.getPackageName()));
        settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(settings);
    }

    public static boolean installPendingUpdate(Context context) {
        long id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(READY_DOWNLOAD_ID, -1);
        if (id < 0 || !canInstallPackages(context)) return false;

        DownloadManager manager =
            (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        Uri contentUri = manager.getUriForDownloadedFile(id);
        if (contentUri == null) return false;
        try {
            Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(contentUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(install);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(READY_DOWNLOAD_ID)
                .remove(UPDATE_DOWNLOAD_ID)
                .apply();
            return true;
        } catch (Exception e) {
            Toast.makeText(context, "无法打开安装程序，请从“下载”目录安装",
                Toast.LENGTH_LONG).show();
            return false;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
        long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        long updateId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(UPDATE_DOWNLOAD_ID, -2);

        DownloadManager manager =
            (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (completedId != updateId) {
            openCompletedWebDownload(context, manager, completedId);
            return;
        }
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(completedId);
        try (Cursor cursor = manager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) return;
            int status = cursor.getInt(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                Toast.makeText(context, "更新下载失败，请下次启动时重试",
                    Toast.LENGTH_LONG).show();
                return;
            }

            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(READY_DOWNLOAD_ID, completedId).apply();

            if (canInstallPackages(context) && installPendingUpdate(context)) return;

            Intent launch = context.getPackageManager()
                .getLaunchIntentForPackage(context.getPackageName());
            if (launch != null) {
                launch.putExtra(EXTRA_INSTALL_UPDATE, true);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                try {
                    context.startActivity(launch);
                } catch (Exception ignored) {
                    Toast.makeText(context,
                        "下载完成，请打开 yousa 继续安装", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    public static void openPendingWebDownload(Context context) {
        long id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(READY_WEB_DOWNLOAD_ID, -1);
        if (id < 0) return;
        DownloadManager manager =
            (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        openCompletedWebDownload(context, manager, id);
    }

    public static void markWebDownloadOpened(Context context, long id) {
        if (id < 0) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(READY_WEB_DOWNLOAD_ID)
            .apply();
    }

    private static void openCompletedWebDownload(Context context, DownloadManager manager,
                                                 long completedId) {
        String mimeKey = WEB_MIME_PREFIX + completedId;
        String nameKey = WEB_NAME_PREFIX + completedId;
        String savedMime = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(mimeKey, "");
        String fileName = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(nameKey, "");
        if ((savedMime == null || savedMime.isEmpty())
            && (fileName == null || fileName.isEmpty())) {
            return;
        }

        DownloadManager.Query query = new DownloadManager.Query().setFilterById(completedId);
        try (Cursor cursor = manager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) return;
            int status = cursor.getInt(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                Toast.makeText(context, "文件下载失败，请稍后重试",
                    Toast.LENGTH_LONG).show();
                return;
            }

            String mimeType = cursor.getString(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE));
            if (mimeType == null || mimeType.isEmpty()
                || "application/octet-stream".equals(mimeType)) {
                mimeType = savedMime;
            }
            if ((mimeType == null || mimeType.isEmpty()) && fileName != null) {
                mimeType = URLConnection.guessContentTypeFromName(fileName);
            }
            if (mimeType == null || mimeType.isEmpty()) mimeType = "*/*";

            Uri contentUri = manager.getUriForDownloadedFile(completedId);
            if (contentUri == null) return;
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(READY_WEB_DOWNLOAD_ID, completedId)
                .apply();
            Intent open = new Intent(context, FileViewerActivity.class)
                .setDataAndType(contentUri, mimeType)
                .putExtra(FileViewerActivity.EXTRA_FILE_NAME, fileName)
                .putExtra(FileViewerActivity.EXTRA_DOWNLOAD_ID, completedId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                context.startActivity(open);
            } catch (Exception error) {
                Toast.makeText(context,
                    "下载完成，文件已保存到 Download/yousa",
                    Toast.LENGTH_LONG).show();
            }
        }
    }
}
