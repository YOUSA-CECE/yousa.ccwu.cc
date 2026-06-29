package com.yousa.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String APP_URL = "https://yousa.ccwu.cc";
    private static final String APP_HOST = "yousa.ccwu.cc";
    private static final float REFRESH_DISTANCE = 150f;

    private WebView webView;
    private ProgressBar pageProgress;
    private View splashPanel;
    private View errorPanel;
    private TextView errorMessage;
    private TextView pullHint;
    private View refreshIndicator;
    private View refreshLogo;
    private ProgressBar refreshSpinner;
    private float touchStartY;
    private boolean canPull;
    private boolean splashDismissed;
    private boolean blankPageRetryUsed;
    private boolean refreshing;
    private boolean authNavigationPending;
    private int authWatchdogToken;
    private long splashStartedAt;

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        splashStartedAt = System.currentTimeMillis();
        configureSystemBars();
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        pageProgress = findViewById(R.id.pageProgress);
        splashPanel = findViewById(R.id.splashPanel);
        errorPanel = findViewById(R.id.errorPanel);
        errorMessage = findViewById(R.id.errorMessage);
        pullHint = findViewById(R.id.pullHint);
        refreshIndicator = findViewById(R.id.refreshIndicator);
        refreshLogo = findViewById(R.id.refreshLogo);
        refreshSpinner = findViewById(R.id.refreshSpinner);
        refreshIndicator.setTranslationY(-100f);
        refreshIndicator.setAlpha(0f);
        findViewById(R.id.retryButton).setOnClickListener(v -> retryCurrentPage());

        configureWebView();
        checkForUpdate();

        // Always enter through the public home page. Restoring an old protected URL
        // can trigger duplicate login redirects after Android recreates the process.
        webView.loadUrl(APP_URL);
    }

    private void configureSystemBars() {
        getWindow().setStatusBarColor(Color.parseColor("#EEF3F8"));
        getWindow().setNavigationBarColor(Color.parseColor("#EEF3F8"));
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().getDecorView().setSystemUiVisibility(
                getWindow().getDecorView().getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setTextZoom(100);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(settings.getUserAgentString() + " YousaAndroid/1.0");
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
        webView.addJavascriptInterface(new AuthBridge(), "YousaApp");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                pageProgress.setProgress(progress);
                pageProgress.setVisibility(progress < 100 ? View.VISIBLE : View.GONE);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                errorPanel.setVisibility(View.GONE);
                view.animate().cancel();
                view.setAlpha(0.94f);
                view.setTranslationX(0f);
                if (isPath(url, "/logout")) beginAuthNavigation();
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                errorPanel.setVisibility(View.GONE);
                animatePageIn();
                dismissSplash();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                finishRefresh();
                animatePageIn();
                dismissSplash();
                injectAuthHooks(view, url);
                if (authNavigationPending && isHome(url)) {
                    authNavigationPending = false;
                    authWatchdogToken++;
                    view.clearHistory();
                }
                detectBlankMainFrame(view, url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    showLoadError(error == null ? null : error.getDescription().toString());
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return openExternalIfNeeded(request.getUrl());
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Toast.makeText(MainActivity.this,
                    "页面进程已恢复，正在重新连接…", Toast.LENGTH_SHORT).show();
                recreate();
                return true;
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimeType,
                                        long contentLength) {
                enqueueWebDownload(url, userAgent, contentDisposition, mimeType);
            }
        });

        webView.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchStartY = event.getY();
                    canPull = !webView.canScrollVertically(-1);
                    break;
                case MotionEvent.ACTION_MOVE:
                    float distance = event.getY() - touchStartY;
                    if (canPull && distance > 35f) {
                        pullHint.setText(distance >= REFRESH_DISTANCE
                            ? R.string.release_to_refresh : R.string.pull_to_refresh);
                        float progress = Math.min(1f, distance / REFRESH_DISTANCE);
                        refreshIndicator.setVisibility(View.VISIBLE);
                        refreshIndicator.setAlpha(progress);
                        refreshIndicator.setScaleX(0.82f + progress * 0.18f);
                        refreshIndicator.setScaleY(0.82f + progress * 0.18f);
                        refreshIndicator.setTranslationY(-80f + progress * 92f);
                        refreshLogo.setRotation(distance * 1.35f);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float releasedDistance = event.getY() - touchStartY;
                    if (canPull && releasedDistance >= REFRESH_DISTANCE) {
                        refreshing = true;
                        pullHint.setText(R.string.refreshing);
                        refreshLogo.setVisibility(View.GONE);
                        refreshSpinner.setVisibility(View.VISIBLE);
                        refreshIndicator.animate().alpha(1f).translationY(12f)
                            .scaleX(1f).scaleY(1f).setDuration(220).start();
                        webView.reload();
                    } else {
                        hideRefreshIndicator();
                    }
                    canPull = false;
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private boolean openExternalIfNeeded(Uri uri) {
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            if (APP_HOST.equalsIgnoreCase(uri.getHost())) {
                return false;
            }
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
            return true;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开该链接", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void detectBlankMainFrame(WebView view, String url) {
        if (url == null || url.startsWith("about:")) return;
        view.evaluateJavascript(
            "(function(){return document.body && (document.body.innerText.trim().length"
                + "+document.body.children.length)>0;})()",
            result -> {
                if ("false".equals(result) || "null".equals(result)) {
                    if (!blankPageRetryUsed) {
                        blankPageRetryUsed = true;
                        view.postDelayed(view::reload, 250);
                    } else {
                        showLoadError("页面返回了空白内容");
                    }
                } else {
                    blankPageRetryUsed = false;
                }
            });
    }

    private void animatePageIn() {
        webView.animate().cancel();
        webView.animate().alpha(1f).translationX(0f).setDuration(220).start();
    }

    private void finishRefresh() {
        if (!refreshing) return;
        refreshing = false;
        refreshIndicator.postDelayed(this::hideRefreshIndicator, 320);
    }

    private void hideRefreshIndicator() {
        refreshIndicator.animate().alpha(0f).translationY(-100f)
            .scaleX(0.86f).scaleY(0.86f).setDuration(240).withEndAction(() -> {
                refreshIndicator.setVisibility(View.INVISIBLE);
                refreshLogo.setVisibility(View.VISIBLE);
                refreshLogo.setRotation(0f);
                refreshSpinner.setVisibility(View.GONE);
                refreshIndicator.setScaleX(1f);
                refreshIndicator.setScaleY(1f);
            }).start();
    }

    private void injectAuthHooks(WebView view, String url) {
        if (!APP_HOST.equalsIgnoreCase(Uri.parse(url).getHost())) return;
        view.evaluateJavascript(
            "(function(){"
                + "if(window.__yousaAuthHooks)return;"
                + "window.__yousaAuthHooks=true;"
                + "document.addEventListener('submit',function(e){"
                + "if(location.pathname==='/login'&&window.YousaApp)"
                + "window.YousaApp.onAuthSubmit();},true);"
                + "document.addEventListener('click',function(e){"
                + "var a=e.target.closest&&e.target.closest('a');"
                + "if(a&&new URL(a.href,location.href).pathname==='/logout'&&window.YousaApp)"
                + "window.YousaApp.onAuthSubmit();},true);"
                + "})();", null);
    }

    private void beginAuthNavigation() {
        authNavigationPending = true;
        final int token = ++authWatchdogToken;
        webView.postDelayed(() -> {
            if (authNavigationPending && token == authWatchdogToken) {
                authNavigationPending = false;
                webView.stopLoading();
                errorPanel.setVisibility(View.GONE);
                webView.loadUrl(APP_URL + "?app_auth_recover=" + System.currentTimeMillis());
            }
        }, 12000);
    }

    private boolean isHome(String url) {
        try {
            Uri uri = Uri.parse(url);
            String path = uri.getPath();
            return APP_HOST.equalsIgnoreCase(uri.getHost())
                && (path == null || path.isEmpty() || "/".equals(path));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isPath(String url, String expectedPath) {
        try {
            Uri uri = Uri.parse(url);
            return APP_HOST.equalsIgnoreCase(uri.getHost())
                && expectedPath.equals(uri.getPath());
        } catch (Exception e) {
            return false;
        }
    }

    private final class AuthBridge {
        @JavascriptInterface
        public void onAuthSubmit() {
            runOnUiThread(MainActivity.this::beginAuthNavigation);
        }
    }

    private void dismissSplash() {
        if (splashDismissed) return;
        long wait = Math.max(0, 650 - (System.currentTimeMillis() - splashStartedAt));
        splashPanel.postDelayed(() -> {
            if (splashDismissed) return;
            splashDismissed = true;
            splashPanel.animate().alpha(0f).scaleX(1.04f).scaleY(1.04f)
                .setDuration(320)
                .withEndAction(() -> splashPanel.setVisibility(View.GONE))
                .start();
        }, wait);
    }

    private void showLoadError(String detail) {
        dismissSplash();
        pageProgress.setVisibility(View.GONE);
        errorMessage.setText(detail == null || detail.trim().isEmpty()
            ? getString(R.string.check_network)
            : detail + "\n\n" + getString(R.string.check_network));
        errorPanel.setAlpha(0f);
        errorPanel.setVisibility(View.VISIBLE);
        errorPanel.animate().alpha(1f).setDuration(180).start();
    }

    private void retryCurrentPage() {
        errorPanel.setVisibility(View.GONE);
        blankPageRetryUsed = false;
        String url = webView.getUrl();
        if (url == null || url.startsWith("about:")) {
            webView.loadUrl(APP_URL);
        } else {
            webView.reload();
        }
    }

    private void enqueueWebDownload(String url, String userAgent,
                                    String contentDisposition, String mimeType) {
        if (!URLUtil.isNetworkUrl(url)) {
            Toast.makeText(this, "暂不支持此下载类型", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(fileName);
            request.setDescription("正在下载…");
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            request.setMimeType(mimeType);
            request.addRequestHeader("User-Agent", userAgent);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) request.addRequestHeader("Cookie", cookies);
            DownloadManager manager =
                (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            manager.enqueue(request);
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void checkForUpdate() {
        UpdateChecker.check(this, new UpdateChecker.Callback() {
            @Override
            public void onResult(boolean hasUpdate, String versionName,
                                 String apkUrl, String changelog, long apkSizeBytes) {
                if (hasUpdate && !isFinishing()) {
                    runOnUiThread(() -> UpdateChecker.showUpdateDialog(
                        MainActivity.this, versionName, apkUrl, changelog, apkSizeBytes));
                }
            }

            @Override
            public void onError(String error) {
                // 更新检查失败不影响主页面使用。
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.animate().alpha(0.55f).translationX(45f).setDuration(130)
                .withEndAction(() -> webView.goBack()).start();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }
}
