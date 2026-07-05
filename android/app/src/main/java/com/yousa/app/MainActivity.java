package com.yousa.app;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final String APP_URL = "https://yousa.ccwu.cc";
    private static final String APP_HOST = "yousa.ccwu.cc";
    private static final float REFRESH_DISTANCE_DP = 64f;
    private static final float PULL_START_DISTANCE_DP = 12f;
    private static final int REQUEST_STORAGE_PERMISSION = 701;
    private static final int REQUEST_FILE_CHOOSER = 702;

    private WebView webView;
    private View splashPanel;
    private View errorPanel;
    private TextView errorMessage;
    private TextView pullHint;
    private View refreshIndicator;
    private View refreshLogo;
    private ProgressBar refreshSpinner;
    private float touchStartY;
    private boolean canPull;
    private boolean pullGestureActive;
    private boolean splashDismissed;
    private boolean blankPageRetryUsed;
    private boolean refreshing;
    private boolean authNavigationPending;
    private boolean installPermissionRequested;
    private boolean updateCheckScheduled;
    private int authWatchdogToken;
    private long splashStartedAt;
    private float refreshDistance;
    private float pullStartDistance;
    private PendingDownload pendingDownload;
    private ValueCallback<Uri[]> fileChooserCallback;

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        splashStartedAt = System.currentTimeMillis();
        refreshDistance = REFRESH_DISTANCE_DP
            * getResources().getDisplayMetrics().density;
        pullStartDistance = PULL_START_DISTANCE_DP
            * getResources().getDisplayMetrics().density;
        configureSystemBars();
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
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

        // Always enter through the public home page. Restoring an old protected URL
        // can trigger duplicate login redirects after Android recreates the process.
        webView.loadUrl(APP_URL);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        installPermissionRequested = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        ApkDownloadReceiver.openPendingWebDownload(this);
        if (!ApkDownloadReceiver.hasPendingInstall(this)) return;
        if (ApkDownloadReceiver.canInstallPackages(this)) {
            installPermissionRequested = false;
            ApkDownloadReceiver.installPendingUpdate(this);
        } else if (!installPermissionRequested) {
            installPermissionRequested = true;
            Toast.makeText(this, "请允许安装未知应用，授权后将继续安装更新",
                Toast.LENGTH_LONG).show();
            ApkDownloadReceiver.requestInstallPermission(this);
        }
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
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
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
        settings.setOffscreenPreRaster(true);
        settings.setUserAgentString(settings.getUserAgentString() + " YousaAndroid/1.0");
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
        webView.addJavascriptInterface(new AuthBridge(), "YousaApp");
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = callback;
                try {
                    // Check if the request is image-only → open the system photo picker / gallery
                    String[] acceptedTypes = params.getAcceptTypes();
                    boolean imageOnly = false;
                    boolean multiImage = false;
                    if (acceptedTypes != null) {
                        int imageCount = 0;
                        for (String t : acceptedTypes) {
                            if (t != null && (t.equals("image/*") || t.startsWith("image/"))) {
                                imageCount++;
                            }
                        }
                        imageOnly = imageCount > 0 && imageCount == acceptedTypes.length;
                    }
                    multiImage = imageOnly
                        && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;

                    Intent picker;
                    if (imageOnly) {
                        // Use the photo picker (API 33+) or gallery intent (older)
                        if (Build.VERSION.SDK_INT >= 33 && multiImage) {
                            picker = new Intent(MediaStore.ACTION_PICK_IMAGES);
                            picker.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 50);
                        } else if (Build.VERSION.SDK_INT >= 33) {
                            picker = new Intent(MediaStore.ACTION_PICK_IMAGES);
                        } else {
                            picker = new Intent(Intent.ACTION_PICK,
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                            picker.setType("image/*");
                        }
                    } else {
                        picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        picker.addCategory(Intent.CATEGORY_OPENABLE);
                        String mimeType = "*/*";
                        if (acceptedTypes != null && acceptedTypes.length == 1
                            && acceptedTypes[0] != null && !acceptedTypes[0].isEmpty()) {
                            mimeType = acceptedTypes[0];
                        } else if (acceptedTypes != null && acceptedTypes.length > 1) {
                            picker.putExtra(Intent.EXTRA_MIME_TYPES, acceptedTypes);
                        }
                        picker.setType(mimeType);
                    }
                    picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiImage);
                    startActivityForResult(picker, REQUEST_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    fileChooserCallback = null;
                    Toast.makeText(MainActivity.this,
                        "无法打开文件选择器", Toast.LENGTH_LONG).show();
                    return false;
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (upgradeAppUrlToHttps(view, Uri.parse(url))) return;
                errorPanel.setVisibility(View.GONE);
                view.animate().cancel();
                view.setAlpha(0.98f);
                view.setTranslationX(0f);
                if (isPath(url, "/logout")) beginAuthNavigation();
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                errorPanel.setVisibility(View.GONE);
                animatePageIn();
                dismissSplash();
                scheduleUpdateCheck();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                finishRefresh();
                animatePageIn();
                dismissSplash();
                injectAuthHooks(view, url);
                schedulePagePrefetch(view, url);
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
                    if (upgradeAppUrlToHttps(view, request.getUrl())) return;
                    showLoadError(error == null ? null : error.getDescription().toString());
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (upgradeAppUrlToHttps(view, request.getUrl())) return true;
                if (isDownloadableUri(request.getUrl())) return false;
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
                    pullGestureActive = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float distance = event.getY() - touchStartY;
                    if (canPull && distance > pullStartDistance) {
                        pullGestureActive = true;
                        pullHint.setText(distance >= refreshDistance
                            ? R.string.release_to_refresh : R.string.pull_to_refresh);
                        float progress = Math.min(1f, distance / refreshDistance);
                        refreshIndicator.setVisibility(View.VISIBLE);
                        refreshIndicator.setAlpha(progress);
                        refreshIndicator.setScaleX(0.82f + progress * 0.18f);
                        refreshIndicator.setScaleY(0.82f + progress * 0.18f);
                        refreshIndicator.setTranslationY(-80f + progress * 92f);
                        refreshLogo.setRotation(distance * 1.35f);
                    }
                    return pullGestureActive;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float releasedDistance = event.getY() - touchStartY;
                    boolean handledPull = pullGestureActive;
                    if (canPull && releasedDistance >= refreshDistance) {
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
                    pullGestureActive = false;
                    return handledPull;
                default:
                    break;
            }
            return false;
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILE_CHOOSER || fileChooserCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clipData = data.getClipData();
            if (clipData != null) {
                result = new Uri[clipData.getItemCount()];
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    result[i] = clipData.getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileChooserCallback.onReceiveValue(result);
        fileChooserCallback = null;
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

    private boolean isDownloadableUri(Uri uri) {
        if (uri == null) return false;
        String path = uri.getPath();
        if (path == null) return false;
        String lowerPath = path.toLowerCase();
        String[] extensions = {
            ".apk", ".zip", ".rar", ".7z", ".tar", ".gz",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".csv",
            ".mp3", ".mp4", ".txt", ".md", ".json"
        };
        for (String extension : extensions) {
            if (lowerPath.endsWith(extension)) return true;
        }
        return false;
    }

    private boolean upgradeAppUrlToHttps(WebView view, Uri uri) {
        if (uri == null
            || !"http".equalsIgnoreCase(uri.getScheme())
            || !APP_HOST.equalsIgnoreCase(uri.getHost())) {
            return false;
        }
        Uri secureUri = uri.buildUpon()
            .scheme("https")
            .authority(APP_HOST)
            .build();
        view.stopLoading();
        view.post(() -> view.loadUrl(secureUri.toString()));
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
        webView.animate().alpha(1f).translationX(0f).setDuration(90).start();
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

    private void schedulePagePrefetch(WebView view, String url) {
        Uri pageUri = Uri.parse(url);
        if (!APP_HOST.equalsIgnoreCase(pageUri.getHost())) return;
        view.postDelayed(() -> {
            if (view != webView || !url.equals(view.getUrl())) return;
            view.evaluateJavascript(
                "(function(){"
                    + "if(window.__yousaPrefetched)return;"
                    + "window.__yousaPrefetched=true;"
                    + "var blocked=/\\/(?:logout|admin|download)(?:\\/|$)/;"
                    + "var seen={};var count=0;"
                    + "Array.prototype.forEach.call(document.querySelectorAll('a[href]'),"
                    + "function(a){"
                    + "if(count>=10)return;"
                    + "var u;try{u=new URL(a.href,location.href);}catch(e){return;}"
                    + "if(u.origin!==location.origin||u.href===location.href"
                    + "||u.hash||blocked.test(u.pathname))return;"
                    + "var key=u.pathname+u.search;if(seen[key])return;seen[key]=1;"
                    + "var link=document.createElement('link');"
                    + "link.rel='prefetch';link.href=u.href;link.as='document';"
                    + "document.head.appendChild(link);count++;"
                    + "});"
                    + "})();", null);
        }, 700);
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

        @JavascriptInterface
        public void openDownloadCenter() {
            runOnUiThread(() ->
                startActivity(new Intent(MainActivity.this, DownloadCenterActivity.class)));
        }
    }

    private void dismissSplash() {
        if (splashDismissed) return;
        long wait = Math.max(0, 260 - (System.currentTimeMillis() - splashStartedAt));
        splashPanel.postDelayed(() -> {
            if (splashDismissed) return;
            splashDismissed = true;
            splashPanel.animate().alpha(0f).scaleX(1.04f).scaleY(1.04f)
                .setDuration(170)
                .withEndAction(() -> splashPanel.setVisibility(View.GONE))
                .start();
        }, wait);
    }

    private void showLoadError(String detail) {
        dismissSplash();
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
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
            && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = new PendingDownload(
                url, userAgent, contentDisposition, mimeType);
            requestPermissions(
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQUEST_STORAGE_PERMISSION);
            return;
        }
        if (!URLUtil.isNetworkUrl(url)) {
            Toast.makeText(this, "暂不支持此下载类型", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ApkDownloadReceiver.openOrReuseWebDownload(this, url)) return;
        try {
            String fileName = resolveDownloadFileName(
                url, contentDisposition, mimeType);
            String currentUrl = webView.getUrl();
            boolean cloudDownload = false;
            if (currentUrl != null) {
                String currentPath = Uri.parse(currentUrl).getPath();
                cloudDownload = currentPath != null
                    && (currentPath.equals("/cloud") || currentPath.startsWith("/cloud/"));
            }
            String relativePath = cloudDownload
                ? "yousa/云盘/" + fileName
                : "yousa/" + fileName;
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(fileName);
            request.setDescription("正在下载…");
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, relativePath);
            if (mimeType != null && !mimeType.trim().isEmpty()) {
                request.setMimeType(mimeType);
            }
            if (userAgent != null && !userAgent.trim().isEmpty()) {
                request.addRequestHeader("User-Agent", userAgent);
            }
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) request.addRequestHeader("Cookie", cookies);
            request.addRequestHeader("Referer",
                webView.getUrl() == null ? APP_URL : webView.getUrl());
            request.addRequestHeader("Accept-Encoding", "identity");
            DownloadManager manager =
                (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            long downloadId = manager.enqueue(request);
            ApkDownloadReceiver.registerWebDownload(
                this, downloadId, url, userAgent, cookies,
                webView.getUrl() == null ? APP_URL : webView.getUrl(),
                mimeType, fileName, relativePath);
            Toast.makeText(this,
                "已加入系统下载（任务 " + downloadId + "）",
                Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private String resolveDownloadFileName(String url, String disposition,
                                           String mimeType) {
        if (disposition != null) {
            java.util.regex.Matcher encoded = java.util.regex.Pattern.compile(
                "filename\\*\\s*=\\s*UTF-8''([^;]+)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(disposition);
            if (encoded.find()) {
                try {
                    return URLDecoder.decode(encoded.group(1), StandardCharsets.UTF_8.name())
                        .replaceAll("[\\\\/:*?\"<>|]", "_");
                } catch (Exception ignored) {
                    // Fall through to the regular filename parameter.
                }
            }
            java.util.regex.Matcher regular = java.util.regex.Pattern.compile(
                "filename\\s*=\\s*\"?([^\";]+)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(disposition);
            if (regular.find()) {
                return regular.group(1).trim().replaceAll("[\\\\/:*?\"<>|]", "_");
            }
        }
        return URLUtil.guessFileName(url, disposition, mimeType)
            .replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE_PERMISSION || pendingDownload == null) return;
        PendingDownload download = pendingDownload;
        pendingDownload = null;
        if (grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            enqueueWebDownload(download.url, download.userAgent,
                download.contentDisposition, download.mimeType);
        } else {
            Toast.makeText(this, "没有存储权限，无法保存文件",
                Toast.LENGTH_LONG).show();
        }
    }

    private static final class PendingDownload {
        final String url;
        final String userAgent;
        final String contentDisposition;
        final String mimeType;

        PendingDownload(String url, String userAgent,
                        String contentDisposition, String mimeType) {
            this.url = url;
            this.userAgent = userAgent;
            this.contentDisposition = contentDisposition;
            this.mimeType = mimeType;
        }
    }

    private void checkForUpdate() {
        UpdateChecker.check(this, new UpdateChecker.Callback() {
            @Override
            public void onResult(boolean hasUpdate, int versionCode, String versionName,
                                 String apkUrl, String changelog, long apkSizeBytes) {
                if (hasUpdate) {
                    if (!isFinishing()) {
                        runOnUiThread(() -> UpdatePromptActivity.show(
                            MainActivity.this, versionCode, versionName,
                            apkUrl, changelog, apkSizeBytes));
                    }
                } else {
                    UpdatePromptActivity.clearObsoletePrompt(
                        MainActivity.this, getLocalVersionCode());
                }
            }

            @Override
            public void onError(String error) {
                // 更新检查失败不影响主页面使用。
            }
        });
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

    private void scheduleUpdateCheck() {
        if (updateCheckScheduled) return;
        updateCheckScheduled = true;
        // Let the home page finish using the network and renderer first.
        webView.postDelayed(this::checkForUpdate, 1400);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.animate().alpha(0.72f).translationX(28f).setDuration(80)
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
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }
}
