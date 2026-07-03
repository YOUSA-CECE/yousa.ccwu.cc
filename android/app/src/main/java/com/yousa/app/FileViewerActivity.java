package com.yousa.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lightweight in-app viewer for downloaded images, PDFs, text/HTML and DOCX files.
 * Unsupported formats can still be handed to another installed application.
 */
public class FileViewerActivity extends Activity {
    public static final String EXTRA_FILE_NAME = "file_name";

    private Uri fileUri;
    private String mimeType;
    private String fileName;
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fileUri = getIntent().getData();
        mimeType = getIntent().getType();
        fileName = getIntent().getStringExtra(EXTRA_FILE_NAME);
        if (fileName == null || fileName.trim().isEmpty()) fileName = "下载的文件";
        if (mimeType == null || mimeType.isEmpty()) mimeType = guessMime(fileName);
        buildScreen();
        showFile();
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

    private void showUnsupported() {
        showMessage("此格式暂不支持软件内预览，可点击“其他应用”打开。\n\n"
            + "软件内支持：图片、PDF、DOCX、文本、Markdown、CSV、JSON、XML 和 HTML。");
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
