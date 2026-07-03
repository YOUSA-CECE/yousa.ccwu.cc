package com.yousa.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.database.Cursor;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
        root.setBackgroundColor(Color.rgb(244, 247, 250));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(16), dp(14), dp(16), dp(12));
        toolbar.setBackground(gradient(
            Color.rgb(247, 222, 232), Color.rgb(222, 233, 246), 0));
        Button back = new Button(this);
        back.setText("‹  返回");
        styleButton(back, Color.WHITE, Color.rgb(53, 63, 76));
        back.setOnClickListener(v -> finish());
        toolbar.addView(back);
        TextView title = new TextView(this);
        title.setText("下载中心");
        title.setTextSize(22);
        title.setTextColor(Color.rgb(25, 42, 58));
        title.setPadding(dp(16), 0, 0, 0);
        toolbar.addView(title);
        root.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(14), dp(16), dp(14), dp(28));
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
        card.setPadding(dp(16), dp(15), dp(16), dp(14));
        card.setElevation(dp(3));
        card.setBackground(rounded(Color.WHITE, 18));
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
        progress.setProgressTintList(ColorStateList.valueOf(Color.rgb(215, 102, 146)));
        progress.setProgressBackgroundTintList(
            ColorStateList.valueOf(Color.rgb(230, 235, 240)));
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
        boolean retryable = status == DownloadManager.STATUS_FAILED
            || status == DownloadManager.STATUS_PAUSED;
        action.setText(status == DownloadManager.STATUS_SUCCESSFUL
            ? "打开" : retryable ? "重新下载" : "取消");
        styleButton(action,
            status == DownloadManager.STATUS_SUCCESSFUL || retryable
                ? Color.rgb(215, 102, 146) : Color.rgb(236, 239, 243),
            status == DownloadManager.STATUS_SUCCESSFUL || retryable
                ? Color.WHITE : Color.rgb(72, 83, 96));
        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            action.setOnClickListener(v ->
                ApkDownloadReceiver.openWebDownloadById(this, id));
        } else if (retryable) {
            action.setOnClickListener(v -> {
                boolean started = ApkDownloadReceiver.retryWebDownload(this, id);
                Toast.makeText(this, started ? "已重新开始下载" : "无法重试，请重新点击文件",
                    Toast.LENGTH_SHORT).show();
                refreshDownloads();
            });
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
                return "下载失败 · " + failureReason(reason) + " · " + size;
            case DownloadManager.STATUS_PAUSED:
                return "已暂停 · " + pauseReason(reason) + " · " + percent + "% · " + size;
            case DownloadManager.STATUS_PENDING:
                return "等待下载 · " + size;
            default:
                return "下载中 · " + percent + "% · " + size;
        }
    }

    private String pauseReason(int reason) {
        switch (reason) {
            case DownloadManager.PAUSED_WAITING_TO_RETRY:
                return "连接中断，等待重试";
            case DownloadManager.PAUSED_WAITING_FOR_NETWORK:
                return "等待网络";
            case DownloadManager.PAUSED_QUEUED_FOR_WIFI:
                return "等待 Wi-Fi";
            default:
                return "系统暂缓下载";
        }
    }

    private String failureReason(int reason) {
        if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) return "存储空间不足";
        if (reason == DownloadManager.ERROR_FILE_ALREADY_EXISTS) return "文件已存在";
        if (reason == DownloadManager.ERROR_CANNOT_RESUME) return "无法继续下载";
        if (reason >= 400 && reason < 600) return "服务器错误 " + reason;
        return "错误 " + reason;
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

    private void styleButton(Button button, int background, int foreground) {
        button.setTextColor(foreground);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(dp(40));
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setBackground(rounded(background, 20));
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable gradient(int start, int end, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, new int[]{start, end});
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
