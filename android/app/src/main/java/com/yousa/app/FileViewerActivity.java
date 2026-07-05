package com.yousa.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.graphics.pdf.PdfRenderer;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.webkit.MimeTypeMap;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lightweight in-app viewer for downloaded images, PDFs, text/HTML, DOCX files
 * and common video formats (MP4, WebM, 3GP, MKV, AVI).
 * Unsupported formats can still be handed to another installed application.
 */
public class FileViewerActivity extends Activity {
    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String EXTRA_DOWNLOAD_ID = "download_id";

    private Uri fileUri;
    private String mimeType;
    private String fileName;
    private LinearLayout content;
    private boolean isVideoMode;

    // ── Video player state ────────────────────────────────────────
    private static final int CONTROLS_AUTO_HIDE_MS = 3000;
    private static final int SEEK_STEP_MS = 10_000;  // 10 seconds
    private static final float[] SPEEDS = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};

    private VideoView videoView;
    private FrameLayout videoRoot;
    private FrameLayout controlOverlay;
    private View topBar;
    private View bottomBar;
    private View centerPlayBtn;
    private SeekBar seekBar;
    private TextView currentTimeText;
    private TextView totalTimeText;
    private TextView speedText;
    private TextView brightnessVolumeLabel;
    private View brightnessVolumePanel;
    private View lockOverlay;

    private boolean controlsVisible = true;
    private boolean controlsLocked;
    private boolean isFullscreen;
    private boolean videoStarted;
    private boolean videoEnded;
    private boolean seeking;
    private float currentSpeed = 1.0f;
    private int videoDuration;
    private int videoNaturalWidth;
    private int videoNaturalHeight;
    private AudioManager audioManager;
    private int audioMaxVolume;
    private float displayBrightness = -1f;
    private long lastGestureTime;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideControlsTask = this::fadeOutControls;

    // ── Lifecycle ─────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fileUri = getIntent().getData();
        mimeType = getIntent().getType();
        fileName = getIntent().getStringExtra(EXTRA_FILE_NAME);
        ApkDownloadReceiver.markWebDownloadOpened(
            this, getIntent().getLongExtra(EXTRA_DOWNLOAD_ID, -1));
        if (fileName == null || fileName.trim().isEmpty()) fileName = "下载的文件";
        if (mimeType == null || mimeType.isEmpty()) mimeType = guessMime(fileName);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioMaxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

        buildScreen();
        showFile();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isVideoMode && videoView != null && !videoEnded && videoView.isPlaying()) {
            videoView.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isVideoMode && videoView != null && !videoEnded && videoStarted
            && !videoView.isPlaying()) {
            videoView.start();
            refreshPlayPauseState();
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (isVideoMode && videoView != null) {
            videoView.stopPlayback();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (isFullscreen) {
            exitFullscreen();
            return;
        }
        super.onBackPressed();
    }

    // ── Screen building ───────────────────────────────────────────

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
        toolbar.addView(back, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText(fileName);
        title.setTextSize(17);
        title.setTextColor(Color.rgb(25, 42, 58));
        title.setSingleLine(true);
        title.setPadding(dp(10), 0, dp(10), 0);
        toolbar.addView(title, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button external = new Button(this);
        external.setText("其他应用");
        external.setOnClickListener(v -> openExternally());
        toolbar.addView(external, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(12), dp(12), dp(12), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    // ── File dispatch ─────────────────────────────────────────────

    private void showFile() {
        if (fileUri == null) {
            showMessage("找不到下载的文件");
            return;
        }
        try {
            String lower = fileName.toLowerCase(Locale.ROOT);
            if ((mimeType != null && mimeType.startsWith("image/"))
                || lower.matches(".*\\.(png|jpe?g|gif|webp|bmp)$")) {
                showImage();
            } else if ("application/pdf".equals(mimeType) || lower.endsWith(".pdf")) {
                showPdf();
            } else if (lower.endsWith(".docx")) {
                showDocx();
            } else if ((mimeType != null && mimeType.startsWith("video/"))
                || lower.matches(".*\\.(mp4|webm|3gp|mkv|avi)$")) {
                showVideo();
            } else if ("text/html".equals(mimeType)
                || lower.endsWith(".html") || lower.endsWith(".htm")) {
                showHtml();
            } else if ((mimeType != null && (mimeType.startsWith("text/")
                || mimeType.contains("json") || mimeType.contains("xml")))
                || lower.matches(".*\\.(txt|md|csv|json|xml|log)$")) {
                showText(readText(getContentResolver().openInputStream(fileUri)));
            } else {
                showUnsupported();
            }
        } catch (Exception error) {
            showMessage("无法预览此文件：" + error.getMessage());
        }
    }

    private void showImage() throws Exception {
        try (InputStream input = getContentResolver().openInputStream(fileUri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            if (bitmap == null) throw new IllegalArgumentException("图片格式无法识别");
            ImageView image = new ImageView(this);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageBitmap(bitmap);
            content.addView(image, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void showPdf() throws Exception {
        ParcelFileDescriptor descriptor =
            getContentResolver().openFileDescriptor(fileUri, "r");
        if (descriptor == null) throw new IllegalArgumentException("PDF 文件无法读取");
        try (ParcelFileDescriptor ignored = descriptor;
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            int targetWidth = Math.max(720,
                getResources().getDisplayMetrics().widthPixels - dp(24));
            for (int index = 0; index < renderer.getPageCount(); index++) {
                try (PdfRenderer.Page page = renderer.openPage(index)) {
                    int height = Math.max(1,
                        Math.round(targetWidth * (page.getHeight() / (float) page.getWidth())));
                    Bitmap bitmap = Bitmap.createBitmap(
                        targetWidth, height, Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(Color.WHITE);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    ImageView image = new ImageView(this);
                    image.setAdjustViewBounds(true);
                    image.setImageBitmap(bitmap);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    params.bottomMargin = dp(12);
                    content.addView(image, params);
                }
            }
        }
    }

    private void showDocx() throws Exception {
        String xml = null;
        try (InputStream input = getContentResolver().openInputStream(fileUri);
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = zip.read(buffer)) >= 0) output.write(buffer, 0, count);
                    xml = output.toString(StandardCharsets.UTF_8.name());
                    break;
                }
            }
        }
        if (xml == null) throw new IllegalArgumentException("DOCX 正文不存在");
        String text = xml
            .replaceAll("</w:p>", "\n")
            .replaceAll("</w:tab>", "\t")
            .replaceAll("<[^>]+>", "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'");
        showText(text.trim());
    }

    private void showHtml() {
        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(false);
        web.loadUrl(fileUri.toString());
        content.addView(web, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(700)));
    }

    private void showText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(16);
        view.setTextColor(Color.rgb(30, 45, 55));
        view.setTextIsSelectable(true);
        view.setLineSpacing(0, 1.25f);
        content.addView(view, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ════════════════════════════════════════════════════════════════
    //  ENHANCED VIDEO PLAYER — Full-featured in-app player with
    //  gesture controls, fullscreen toggle, speed selection,
    //  brightness/volume gestures, and auto-hiding overlays.
    //  Inspired by YouTube, Bilibili, and VLC mobile players.
    // ════════════════════════════════════════════════════════════════

    private void showVideo() {
        isVideoMode = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // ── Root: fills the whole screen in fullscreen, or content area in normal mode ──
        videoRoot = new FrameLayout(this);
        videoRoot.setBackgroundColor(Color.BLACK);

        videoView = new VideoView(this);
        videoView.setVideoURI(fileUri);
        videoRoot.addView(videoView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ── Control overlay (sits on top of video) ──
        controlOverlay = buildControlOverlay();
        videoRoot.addView(controlOverlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ── Top bar: back + title + speed ──
        topBar = buildTopBar();
        controlOverlay.addView(topBar, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── Bottom bar: time + seekbar + time + fullscreen ──
        bottomBar = buildBottomBar();
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomParams.gravity = Gravity.BOTTOM;
        controlOverlay.addView(bottomBar, bottomParams);

        // ── Center play/pause ──
        centerPlayBtn = buildCenterPlayButton();
        FrameLayout.LayoutParams centerParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        centerParams.gravity = Gravity.CENTER;
        controlOverlay.addView(centerPlayBtn, centerParams);

        // ── Brightness/volume indicator ──
        brightnessVolumePanel = buildBrightnessVolumeIndicator();
        FrameLayout.LayoutParams bvParams = new FrameLayout.LayoutParams(
            dp(140), dp(100));
        bvParams.gravity = Gravity.CENTER;
        controlOverlay.addView(brightnessVolumePanel, bvParams);
        brightnessVolumePanel.setVisibility(View.GONE);

        // ── Lock overlay ──
        lockOverlay = new View(this);
        lockOverlay.setBackgroundColor(Color.argb(100, 0, 0, 0));
        lockOverlay.setVisibility(View.GONE);
        lockOverlay.setOnClickListener(v -> {
            controlsLocked = false;
            lockOverlay.setVisibility(View.GONE);
            showControls();
        });
        videoRoot.addView(lockOverlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ── Gesture detector for double-tap ──
        GestureDetector gestureDetector = new GestureDetector(this,
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (controlsLocked) return true;
                    int width = videoRoot.getWidth();
                    if (e.getX() < width / 2f) {
                        seekRelative(-SEEK_STEP_MS);
                        showSeekFeedback(-SEEK_STEP_MS);
                    } else {
                        seekRelative(SEEK_STEP_MS);
                        showSeekFeedback(SEEK_STEP_MS);
                    }
                    return true;
                }

                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    if (controlsLocked) return true;
                    if (controlsVisible) {
                        if (controlsLocked) {
                            controlsLocked = false;
                            lockOverlay.setVisibility(View.GONE);
                        }
                        fadeOutControls();
                    } else {
                        showControls();
                    }
                    return true;
                }
            });

        // ── Touch handler for gestures + double-tap ──
        controlOverlay.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private boolean gestureConsumed;
            private boolean gestureIsVolumeSide;
            private float gestureStartBrightness;
            private int gestureStartVolume;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (controlsLocked) {
                    gestureDetector.onTouchEvent(event);
                    return true;
                }

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        downX = event.getX();
                        downY = event.getY();
                        gestureConsumed = false;
                        gestureIsVolumeSide = event.getX() > videoRoot.getWidth() / 2f;
                        gestureStartBrightness = getScreenBrightness();
                        gestureStartVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                        lastGestureTime = System.currentTimeMillis();
                        gestureDetector.onTouchEvent(event);
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        if (gestureConsumed) {
                            gestureDetector.onTouchEvent(event);
                            return true;
                        }
                        float dx = event.getX() - downX;
                        float dy = event.getY() - downY;
                        float absDx = Math.abs(dx);
                        float absDy = Math.abs(dy);

                        // Threshold to lock gesture type
                        if (absDx < dp(10) && absDy < dp(10)) {
                            gestureDetector.onTouchEvent(event);
                            return true;
                        }

                        // Lock gesture: horizontal = seek, vertical = brightness/volume
                        if (absDx > absDy && absDx > dp(15)) {
                            // Horizontal seek
                            gestureConsumed = true;
                            int totalMs = videoDuration;
                            if (totalMs > 0 && videoView != null) {
                                int cur = videoView.getCurrentPosition();
                                float ratio = dx / (float) videoRoot.getWidth();
                                int delta = (int) (ratio * totalMs);
                                int target = Math.max(0, Math.min(totalMs, cur + delta));
                                videoView.seekTo(target);
                                updateTimeDisplay();
                                // Show mini seek indicator
                                if (brightnessVolumePanel.getVisibility() != View.VISIBLE) {
                                    brightnessVolumePanel.setVisibility(View.VISIBLE);
                                }
                                String label = (delta > 0 ? "+" : "") + (delta / 1000) + "s";
                                showGestureLabel(label, Color.rgb(0, 180, 255));
                                downX = event.getX();
                            }
                            gestureDetector.onTouchEvent(event);
                            return true;
                        } else if (absDy > dp(15)) {
                            // Vertical brightness/volume
                            gestureConsumed = true;
                            float change = -dy / (float) videoRoot.getHeight();
                            if (gestureIsVolumeSide) {
                                // Volume
                                int target = Math.round(gestureStartVolume + change * audioMaxVolume);
                                target = Math.max(0, Math.min(audioMaxVolume, target));
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,
                                    target, 0);
                                int pct = audioMaxVolume > 0 ? target * 100 / audioMaxVolume : 0;
                                showGestureLabel("🔊 " + pct + "%", Color.WHITE);
                            } else {
                                // Brightness
                                float target = Math.max(0f, Math.min(1f,
                                    gestureStartBrightness + change));
                                setScreenBrightness(target);
                                int pct = Math.round(target * 100);
                                showGestureLabel("☀ " + pct + "%", Color.WHITE);
                            }
                            gestureDetector.onTouchEvent(event);
                            return true;
                        }
                        gestureDetector.onTouchEvent(event);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        if (!gestureConsumed) {
                            gestureDetector.onTouchEvent(event);
                        }
                        if (brightnessVolumePanel.getVisibility() == View.VISIBLE) {
                            brightnessVolumePanel.animate()
                                .alpha(0f).setDuration(200)
                                .withEndAction(() -> {
                                    brightnessVolumePanel.setVisibility(View.GONE);
                                    brightnessVolumePanel.setAlpha(1f);
                                }).start();
                        }
                        return true;
                    }
                }
                return false;
            }
        });

        // ── MediaPlayer events ──
        videoView.setOnPreparedListener(mp -> {
            videoDuration = mp.getDuration();
            mp.setLooping(false);
            seekBar.setMax(Math.max(1, videoDuration));
            updateTimeDisplay();
            updateSpeedUi();

            videoNaturalWidth = mp.getVideoWidth();
            videoNaturalHeight = mp.getVideoHeight();
            adjustVideoSize();

            videoStarted = true;
            mp.start();
            refreshPlayPauseState();
            showControls();
        });

        videoView.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "无法播放此视频（错误 " + what + "）",
                Toast.LENGTH_LONG).show();
            return true;
        });

        videoView.setOnCompletionListener(mp -> {
            videoEnded = true;
            refreshPlayPauseState();
            showControls();
            mainHandler.removeCallbacks(hideControlsTask);
        });

        // ── Progress updater ──
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed() || videoView == null) return;
                if (!seeking && videoView.isPlaying() && videoDuration > 0) {
                    int pos = videoView.getCurrentPosition();
                    seekBar.setProgress(pos);
                    currentTimeText.setText(formatTime(pos));
                }
                mainHandler.postDelayed(this, 250);
            }
        });

        // ── Add to screen ──
        setContentView(videoRoot);
        enterFullscreen();

        // Start
        controlsVisible = true;
        controlsLocked = false;
        scheduleHideControls();
    }

    // ── Control overlay building ──────────────────────────────────

    private FrameLayout buildControlOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.argb(100, 0, 0, 0));
        return overlay;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(8), dp(24), dp(8), dp(8));
        bar.setGravity(Gravity.CENTER_VERTICAL);

        Button back = iconButton("‹", dp(40), v -> finish());
        bar.addView(back);

        TextView titleView = new TextView(this);
        titleView.setText(fileName);
        titleView.setTextSize(15);
        titleView.setTextColor(Color.WHITE);
        titleView.setSingleLine(true);
        titleView.setPadding(dp(8), 0, dp(8), 0);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button speedBtn = iconButton("1×", dp(40), v -> cycleSpeed());
        speedBtn.setTextSize(13);
        speedText = speedBtn;
        bar.addView(speedBtn);

        Button lockBtn = iconButton("🔒", dp(40), v -> {
            controlsLocked = true;
            lockOverlay.setVisibility(View.VISIBLE);
            fadeOutControls();
        });
        bar.addView(lockBtn);

        return bar;
    }

    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(8), dp(4), dp(8), dp(16));
        bar.setGravity(Gravity.CENTER_VERTICAL);

        // Seek row
        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);

        currentTimeText = new TextView(this);
        currentTimeText.setText("0:00");
        currentTimeText.setTextSize(12);
        currentTimeText.setTextColor(Color.WHITE);
        currentTimeText.setPadding(dp(6), 0, dp(6), 0);
        seekRow.addView(currentTimeText);

        seekBar = new SeekBar(this);
        seekBar.setMax(1);
        seekBar.setProgress(0);
        seekBar.setProgressDrawable(buildSeekBarDrawable());
        seekBar.setThumb(buildThumbDrawable());
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && videoView != null) {
                    currentTimeText.setText(formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                seeking = true;
                mainHandler.removeCallbacks(hideControlsTask);
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                if (videoView != null) {
                    videoView.seekTo(sb.getProgress());
                }
                seeking = false;
                scheduleHideControls();
            }
        });
        seekRow.addView(seekBar, new LinearLayout.LayoutParams(0, dp(30), 1));

        totalTimeText = new TextView(this);
        totalTimeText.setText("0:00");
        totalTimeText.setTextSize(12);
        totalTimeText.setTextColor(Color.WHITE);
        totalTimeText.setPadding(dp(6), 0, dp(6), 0);
        seekRow.addView(totalTimeText);

        Button fullscreenBtn = iconButton("⛶", dp(40), v -> toggleFullscreen());
        seekRow.addView(fullscreenBtn);

        bar.addView(seekRow);

        // Action row: skip back - play/pause - skip forward
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setGravity(Gravity.CENTER);
        actionRow.setPadding(0, dp(4), 0, 0);

        Button rewindBtn = iconButton("↩10", dp(40), v -> seekRelative(-SEEK_STEP_MS));
        rewindBtn.setTextSize(12);
        actionRow.addView(rewindBtn);

        // Spacer
        actionRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));

        // Center play/pause in bottom bar too
        Button bottomPlayBtn = iconButton("▶", dp(48), v -> togglePlayPause());
        bottomPlayBtn.setTextSize(22);
        bottomPlayBtn.setTag("bottom_play");
        actionRow.addView(bottomPlayBtn);

        // Spacer
        actionRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));

        Button forwardBtn = iconButton("10↪", dp(40), v -> seekRelative(SEEK_STEP_MS));
        forwardBtn.setTextSize(12);
        actionRow.addView(forwardBtn);

        bar.addView(actionRow);

        return bar;
    }

    private View buildCenterPlayButton() {
        Button btn = new Button(this);
        btn.setText("▶");
        btn.setTextSize(48);
        btn.setTextColor(Color.WHITE);
        btn.setBackground(dpRoundBg(Color.argb(100, 0, 0, 0), 50));
        btn.setVisibility(View.GONE);
        btn.setOnClickListener(v -> togglePlayPause());
        // Pulse animation reference
        btn.setTag("center_play");
        return btn;
    }

    private View buildBrightnessVolumeIndicator() {
        FrameLayout panel = new FrameLayout(this);
        panel.setBackground(dpRoundBg(Color.argb(160, 0, 0, 0), 12));

        brightnessVolumeLabel = new TextView(this);
        brightnessVolumeLabel.setText("");
        brightnessVolumeLabel.setTextSize(20);
        brightnessVolumeLabel.setTextColor(Color.WHITE);
        brightnessVolumeLabel.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        panel.addView(brightnessVolumeLabel, lp);

        return panel;
    }

    // ── Playback controls ─────────────────────────────────────────

    private void togglePlayPause() {
        if (videoView == null) return;
        if (videoEnded) {
            videoView.seekTo(0);
            videoEnded = false;
            videoView.start();
        } else if (videoView.isPlaying()) {
            videoView.pause();
        } else {
            videoView.start();
        }
        refreshPlayPauseState();
        scheduleHideControls();
    }

    private void seekRelative(int deltaMs) {
        if (videoView == null) return;
        int target = Math.max(0, Math.min(videoDuration, videoView.getCurrentPosition() + deltaMs));
        videoView.seekTo(target);
        videoEnded = false;
        updateTimeDisplay();
    }

    private void showSeekFeedback(int deltaMs) {
        String text = (deltaMs > 0 ? "→ " : "← ") + Math.abs(deltaMs / 1000) + "s";
        showGestureLabel(text, Color.rgb(0, 180, 255));
    }

    private void showGestureLabel(String text, int color) {
        if (brightnessVolumePanel.getVisibility() != View.VISIBLE) {
            brightnessVolumePanel.setVisibility(View.VISIBLE);
            brightnessVolumePanel.setAlpha(1f);
        }
        brightnessVolumeLabel.setText(text);
        brightnessVolumeLabel.setTextColor(color);

        mainHandler.removeCallbacks(this::hideGestureLabel);
        mainHandler.postDelayed(this::hideGestureLabel, 1200);
    }

    private void hideGestureLabel() {
        if (brightnessVolumePanel.getVisibility() == View.VISIBLE) {
            brightnessVolumePanel.animate()
                .alpha(0f).setDuration(200)
                .withEndAction(() -> {
                    brightnessVolumePanel.setVisibility(View.GONE);
                    brightnessVolumePanel.setAlpha(1f);
                }).start();
        }
    }

    private void cycleSpeed() {
        int idx = 0;
        for (int i = 0; i < SPEEDS.length; i++) {
            if (Math.abs(SPEEDS[i] - currentSpeed) < 0.01f) {
                idx = (i + 1) % SPEEDS.length;
                break;
            }
        }
        currentSpeed = SPEEDS[idx];
        setPlaybackSpeed(currentSpeed);
        updateSpeedUi();
        showGestureLabel(SPEEDS[idx] + "×", Color.WHITE);
    }

    private void setPlaybackSpeed(float speed) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Field mpField = VideoView.class.getDeclaredField("mMediaPlayer");
            mpField.setAccessible(true);
            MediaPlayer mp = (MediaPlayer) mpField.get(videoView);
            if (mp != null) {
                mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(speed));
            }
        } catch (Exception ignored) {
        }
    }

    private void updateSpeedUi() {
        if (speedText != null) {
            String label = currentSpeed == 1.0f ? "1×" : String.format(Locale.CHINA, "%.2g×", currentSpeed);
            speedText.setText(label);
        }
    }

    private void refreshPlayPauseState() {
        boolean playing = videoView != null && videoView.isPlaying();
        String icon = playing ? "⏸" : videoEnded ? "↻" : "▶";
        // Update center button
        if (centerPlayBtn instanceof Button) {
            ((Button) centerPlayBtn).setText(icon);
        }
        // Update bottom play button
        if (bottomBar != null) {
            View bottomPlay = bottomBar.findViewWithTag("bottom_play");
            if (bottomPlay instanceof Button) {
                ((Button) bottomPlay).setText(playing ? "⏸" : videoEnded ? "↻" : "▶");
            }
        }
        if (playing) hideGestureLabel();
    }

    private void updateTimeDisplay() {
        if (videoView == null) return;
        int pos = videoView.getCurrentPosition();
        currentTimeText.setText(formatTime(pos));
        totalTimeText.setText(formatTime(videoDuration));
        if (!seeking) seekBar.setProgress(pos);
    }

    // ── Controls visibility ───────────────────────────────────────

    private void showControls() {
        controlsVisible = true;
        controlOverlay.setVisibility(View.VISIBLE);
        controlOverlay.setAlpha(1f);
        if (topBar != null) topBar.setVisibility(View.VISIBLE);
        if (bottomBar != null) bottomBar.setVisibility(View.VISIBLE);
        if (centerPlayBtn != null) centerPlayBtn.setVisibility(View.VISIBLE);
        scheduleHideControls();
    }

    private void fadeOutControls() {
        if (controlsLocked) return;
        controlOverlay.animate()
            .alpha(0f).setDuration(300)
            .withEndAction(() -> {
                controlsVisible = false;
                controlOverlay.setVisibility(View.GONE);
            }).start();
    }

    private void scheduleHideControls() {
        mainHandler.removeCallbacks(hideControlsTask);
        mainHandler.postDelayed(hideControlsTask, CONTROLS_AUTO_HIDE_MS);
    }

    // ── Fullscreen ────────────────────────────────────────────────

    private void enterFullscreen() {
        isFullscreen = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        adjustVideoSize();
    }

    private void exitFullscreen() {
        isFullscreen = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        adjustVideoSize();
    }

    private void toggleFullscreen() {
        if (isFullscreen) exitFullscreen();
        else enterFullscreen();
        showControls();
    }

    private void adjustVideoSize() {
        if (videoNaturalWidth <= 0 || videoNaturalHeight <= 0) return;
        ViewGroup.LayoutParams params = videoView.getLayoutParams();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;

        if (isFullscreen) {
            // Fill the screen maintaining aspect ratio — centers automatically in FrameLayout
            float ratio = Math.min(
                (float) screenW / videoNaturalWidth,
                (float) screenH / videoNaturalHeight);
            int w = Math.round(videoNaturalWidth * ratio);
            int h = Math.round(videoNaturalHeight * ratio);
            params.width = w;
            params.height = h;
        } else {
            // Fit to width, cap height to 60% of screen height
            int maxH = Math.round(screenH * 0.6f);
            int w = screenW;
            int h = Math.round(w * videoNaturalHeight / (float) videoNaturalWidth);
            params.width = w;
            params.height = Math.min(h, maxH);
        }
        videoView.requestLayout();
    }

    // ── Brightness ────────────────────────────────────────────────

    private float getScreenBrightness() {
        try {
            return Settings.System.getInt(getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (Exception e) {
            return 0.5f;
        }
    }

    private void setScreenBrightness(float value) {
        displayBrightness = value;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = value;
        getWindow().setAttributes(lp);
    }

    // ── Helpers ───────────────────────────────────────────────────

    private void showUnsupported() {
        showMessage("此格式暂不支持软件内预览，可点击「其他应用」打开。\n\n"
            + "软件内支持：图片、PDF、DOCX、文本、Markdown、CSV、JSON、XML、HTML 和常见视频格式。");
    }

    private void showMessage(String message) {
        TextView view = new TextView(this);
        view.setText(message);
        view.setTextSize(16);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(20), dp(80), dp(20), dp(20));
        content.addView(view, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void openExternally() {
        try {
            Intent open = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(fileUri, mimeType == null ? "*/*" : mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(open, "选择打开方式"));
        } catch (Exception error) {
            Toast.makeText(this, "没有找到可打开此文件的应用", Toast.LENGTH_LONG).show();
        }
    }

    // ── UI primitives ─────────────────────────────────────────────

    private Button iconButton(String text, int sizeDp, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(14);
        btn.setTextColor(Color.WHITE);
        btn.setAllCaps(false);
        btn.setMinHeight(0);
        btn.setMinWidth(0);
        btn.setMinimumHeight(0);
        btn.setMinimumWidth(0);
        btn.setPadding(dp(4), dp(4), dp(4), dp(4));
        btn.setBackground(dpRoundBg(Color.argb(50, 255, 255, 255), (int) (sizeDp * 0.3f)));
        btn.setOnClickListener(listener);
        btn.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return btn;
    }

    private Drawable dpRoundBg(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private Drawable buildSeekBarDrawable() {
        // Track: gray bg + accent progress
        ShapeDrawable bg = new ShapeDrawable(new RoundRectShape(
            new float[]{dp(2), dp(2), dp(2), dp(2), dp(2), dp(2), dp(2), dp(2)}, null, null));
        bg.getPaint().setColor(Color.argb(80, 255, 255, 255));
        bg.setIntrinsicHeight(dp(3));

        ShapeDrawable progress = new ShapeDrawable(new RoundRectShape(
            new float[]{dp(2), dp(2), dp(2), dp(2), dp(2), dp(2), dp(2), dp(2)}, null, null));
        progress.getPaint().setColor(Color.rgb(215, 102, 146));
        progress.setIntrinsicHeight(dp(3));

        LayerDrawable layers = new LayerDrawable(new Drawable[]{bg, progress});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        return layers;
    }

    private Drawable buildThumbDrawable() {
        ShapeDrawable thumb = new ShapeDrawable(new RoundRectShape(
            new float[]{dp(7), dp(7), dp(7), dp(7), dp(7), dp(7), dp(7), dp(7)}, null, null));
        thumb.getPaint().setColor(Color.rgb(215, 102, 146));
        thumb.setIntrinsicWidth(dp(14));
        thumb.setIntrinsicHeight(dp(14));
        return thumb;
    }

    private static String formatTime(int ms) {
        if (ms < 0) ms = 0;
        int totalSec = ms / 1000;
        int min = totalSec / 60;
        int sec = totalSec % 60;
        if (min >= 60) {
            int h = min / 60;
            min = min % 60;
            return h + ":" + (min < 10 ? "0" : "") + min + ":" + (sec < 10 ? "0" : "") + sec;
        }
        return min + ":" + (sec < 10 ? "0" : "") + sec;
    }

    private static String readText(InputStream input) throws Exception {
        if (input == null) throw new IllegalArgumentException("文件无法读取");
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) >= 0) result.append(buffer, 0, count);
        }
        return result.toString();
    }

    private static String guessMime(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "*/*";
        String found = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        return found == null ? "*/*" : found;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
