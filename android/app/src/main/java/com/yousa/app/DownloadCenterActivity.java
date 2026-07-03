package com.yousa.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.database.Cursor;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class DownloadCenterActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private boolean active;

    private final Runnable updater = new Runnable() {
        @Override
        public void run() {
            if (!active) return;
            refreshDownloads();
            handler.postDelayed(this, 800);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        handler.removeCallbacks(updater);
        handler.post(updater);
    }

    @Override
    protected void onPause() {
        active = false;
        handler.removeCallbacks(updater);
        super.onPause();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(238, 243, 248));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(8), dp(12), dp(8));
        Button back = new Button(this);
        back.setText("返回");
        back.setOnClickListener(v -> finish());
        toolbar.addView(back);
        TextView title = new TextView(this);
        title.setText("下载中心");
        title.setTextSize(20);
        title.setTextColor(Color.rgb(25, 42, 58));
        title.setPadding(dp(14), 0, 0, 0);
        toolbar.addView(title);
        root.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(6), dp(12), dp(24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void refreshDownloads() {
        long[] ids = ApkDownloadReceiver.getTrackedWebDownloadIds(this);
        list.removeAllViews();
        if (ids.length == 0) {
            TextView empty = text("还没有下载任务", 16, Color.DKGRAY);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(80), 0, 0);
            list.addView(empty);
            return;
        }

        DownloadManager manager =
            (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(ids);
        try (Cursor cursor = manager.query(query)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) addDownloadRow(manager, cursor);
        } catch (Exception error) {
            Toast.makeText(this, "无法读取下载状态", Toast.LENGTH_SHORT).show();
        }
    }

    private void addDownloadRow(DownloadManager manager, Cursor cursor) {
        long id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
        String title = cursor.getString(
            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
        long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(
            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
        long total = cursor.getLong(cursor.getColumnIndexOrThrow(
            DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
        int status = cursor.getInt(cursor.getColumnIndexOrThrow(
            DownloadManager.COLUMN_STATUS));
        int reason = cursor.getInt(cursor.getColumnIndexOrThrow(
            DownloadManager.COLUMN_REASON));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(10);

        TextView name = text(title == null || title.isEmpty() ? "未命名文件" : title,
            16, Color.rgb(25, 42, 58));
        name.setSingleLine(true);
        card.addView(name);

        int percent = total > 0
            ? (int) Math.min(100, Math.max(0, downloaded * 100 / total)) : 0;
        ProgressBar progress = new ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(percent);
        progress.setIndeterminate(total <= 0
            && (status == DownloadManager.STATUS_PENDING
                || status == DownloadManager.STATUS_RUNNING));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        progressParams.topMargin = dp(10);
        card.addView(progress, progressParams);

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        TextView detail = text(statusText(status, reason, percent, downloaded, total),
            13, Color.rgb(90, 105, 117));
        footer.addView(detail, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button action = new Button(this);
        action.setText(status == DownloadManager.STATUS_SUCCESSFUL ? "打开" : "取消");
        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            action.setOnClickListener(v ->
                ApkDownloadReceiver.openWebDownloadById(this, id));
        } else {
            action.setOnClickListener(v -> {
                manager.remove(id);
                refreshDownloads();
                Toast.makeText(this, "下载任务已取消", Toast.LENGTH_SHORT).show();
            });
        }
        footer.addView(action);
        card.addView(footer);
        list.addView(card, cardParams);
    }

    private String statusText(int status, int reason, int percent,
                              long downloaded, long total) {
        String size = formatBytes(downloaded) + " / "
            + (total > 0 ? formatBytes(total) : "未知大小");
        switch (status) {
            case DownloadManager.STATUS_SUCCESSFUL:
                return "已完成 · " + formatBytes(downloaded);
            case DownloadManager.STATUS_FAILED:
                return "下载失败（错误 " + reason + "）· " + size;
            case DownloadManager.STATUS_PAUSED:
                return "已暂停（原因 " + reason + "）· " + percent + "% · " + size;
            case DownloadManager.STATUS_PENDING:
                return "等待下载 · " + size;
            default:
                return "下载中 · " + percent + "% · " + size;
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) {
            return String.format(Locale.CHINA, "%.1f KB", bytes / 1024f);
        }
        return String.format(Locale.CHINA, "%.1f MB", bytes / 1048576f);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
