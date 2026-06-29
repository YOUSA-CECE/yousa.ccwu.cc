package com.yousa.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

public class UpdatePromptActivity extends Activity {
    private static final String PREFS = "yousa_update_prompt";
    private static final String KEY_CODE = "version_code";
    private static final String KEY_NAME = "version_name";
    private static final String KEY_URL = "apk_url";
    private static final String KEY_LOG = "changelog";
    private static final String KEY_SIZE = "apk_size";

    public static void show(Context context, int versionCode, String versionName,
                            String apkUrl, String changelog, long apkSizeBytes) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_CODE, versionCode)
            .putString(KEY_NAME, versionName)
            .putString(KEY_URL, apkUrl)
            .putString(KEY_LOG, changelog)
            .putLong(KEY_SIZE, apkSizeBytes)
            .apply();

        Intent intent = new Intent(context, UpdatePromptActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    public static void clearObsoletePrompt(Context context, int localVersionCode) {
        SharedPreferences preferences =
            context.getSharedPreferences(PREFS, MODE_PRIVATE);
        int pendingCode = preferences.getInt(KEY_CODE, -1);
        if (pendingCode > 0 && pendingCode <= localVersionCode) {
            preferences.edit().clear().apply();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setFinishOnTouchOutside(false);
        setContentView(R.layout.activity_update_prompt);
        Window window = getWindow();
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT);

        findViewById(R.id.updateLaterButton).setOnClickListener(v -> dismissUpdate());
        findViewById(R.id.updateNowButton).setOnClickListener(v -> downloadUpdate());
        renderPendingUpdate();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        renderPendingUpdate();
    }

    private void renderPendingUpdate() {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        int remoteCode = preferences.getInt(KEY_CODE, -1);
        if (remoteCode <= getLocalVersionCode()) {
            preferences.edit().clear().apply();
            finish();
            return;
        }
        String versionName = preferences.getString(KEY_NAME, "");
        long size = preferences.getLong(KEY_SIZE, -1);
        String changelog = preferences.getString(KEY_LOG, "性能与稳定性改进");
        ((TextView) findViewById(R.id.updateVersion)).setText("v" + versionName);
        ((TextView) findViewById(R.id.updateSize)).setText(
            "安装包大小：" + UpdateChecker.formatSize(size));
        ((TextView) findViewById(R.id.updateChangelog)).setText(changelog);
    }

    private int getLocalVersionCode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) getPackageManager()
                    .getPackageInfo(getPackageName(), 0).getLongVersionCode();
            }
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private void dismissUpdate() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply();
        finish();
    }

    private void downloadUpdate() {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        String versionName = preferences.getString(KEY_NAME, "latest");
        String apkUrl = preferences.getString(KEY_URL, "");
        if (apkUrl == null || apkUrl.trim().isEmpty()) {
            Toast.makeText(this, "更新地址无效，请稍后重试", Toast.LENGTH_LONG).show();
            return;
        }
        preferences.edit().clear().apply();
        ApkDownloadReceiver.enqueueApkDownload(
            this, apkUrl, "yousa-v" + versionName + ".apk");
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;
        return super.onKeyDown(keyCode, event);
    }
}
