package com.yousa.app;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.widget.Toast;

public class ApkDownloadReceiver extends BroadcastReceiver {
    private static final String PREFS = "yousa_downloads";
    private static final String UPDATE_DOWNLOAD_ID = "update_download_id";

    public static long enqueueApkDownload(Context context, String apkUrl, String fileName) {
        DownloadManager manager =
            (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
        request.setTitle("yousa 更新");
        request.setDescription("正在下载新版本…");
        request.setNotificationVisibility(
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        request.setMimeType("application/vnd.android.package-archive");
        long id = manager.enqueue(request);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(UPDATE_DOWNLOAD_ID, id).apply();
        Toast.makeText(context, "更新包已开始下载", Toast.LENGTH_SHORT).show();
        return id;
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
                Toast.makeText(context, "更新包下载失败，请稍后重试",
                    Toast.LENGTH_LONG).show();
                return;
            }
            Uri contentUri = manager.getUriForDownloadedFile(completedId);
            if (contentUri == null) return;
            Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(contentUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(install);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(UPDATE_DOWNLOAD_ID).apply();
        } catch (Exception e) {
            Toast.makeText(context, "下载完成，请从“下载”目录安装更新包",
                Toast.LENGTH_LONG).show();
        }
    }
}
