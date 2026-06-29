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

public class ApkDownloadReceiver extends BroadcastReceiver {
    private static final String PREFS = "yousa_downloads";
    private static final String UPDATE_DOWNLOAD_ID = "update_download_id";
    private static final String READY_DOWNLOAD_ID = "ready_download_id";
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
        if (completedId != updateId) return;

        DownloadManager manager =
            (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
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
}
