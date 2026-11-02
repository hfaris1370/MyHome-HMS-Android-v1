package com.myhome.hms;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String PREFS = "myhome_hms_app";
    private static final String KEY_SERVER_URL = "server_url";
    private static final int FILE_CHOOSER_REQUEST = 7001;

    private WebView webView;
    private ProgressBar progressBar;
    private View errorPanel;
    private TextView errorTitle;
    private TextView errorText;
    private ValueCallback<Uri[]> filePathCallback;
    private SharedPreferences preferences;
    private String baseUrl = "";
    private boolean mainFrameFailed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        bindViews();
        configureWebView();
        bindActions();

        baseUrl = normalizeUrl(preferences.getString(KEY_SERVER_URL, ""));
        if (baseUrl.isEmpty()) {
            showServerDialog(true);
        } else {
            loadHome();
        }
    }

    private void bindViews() {
        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        errorPanel = findViewById(R.id.errorPanel);
        errorTitle = findViewById(R.id.errorTitle);
        errorText = findViewById(R.id.errorText);
    }

    private void bindActions() {
        findViewById(R.id.reloadButton).setOnClickListener(v -> {
            if (baseUrl.isEmpty()) showServerDialog(true);
            else webView.reload();
        });
        findViewById(R.id.settingsButton).setOnClickListener(v -> showSettingsMenu());
        findViewById(R.id.retryButton).setOnClickListener(v -> loadHome());
        findViewById(R.id.changeServerButton).setOnClickListener(v -> showServerDialog(false));
    }

    private void configureWebView() {
        android.webkit.WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(s.getUserAgentString() + " MyHomeHMS-Android/1.0");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;

                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

                String[] accept = params != null ? params.getAcceptTypes() : null;
                if (accept != null && accept.length == 1 && accept[0] != null && !accept[0].trim().isEmpty()) {
                    intent.setType(accept[0]);
                } else if (accept != null && accept.length > 1) {
                    List<String> clean = new ArrayList<>();
                    for (String type : accept) {
                        if (type != null && !type.trim().isEmpty()) clean.add(type.trim());
                    }
                    if (!clean.isEmpty()) intent.putExtra(Intent.EXTRA_MIME_TYPES, clean.toArray(new String[0]));
                }

                try {
                    startActivityForResult(Intent.createChooser(intent, "انتخاب فایل یا تصویر"), FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "امکان باز کردن انتخاب‌گر فایل وجود ندارد.", Toast.LENGTH_LONG).show();
                    return false;
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                mainFrameFailed = false;
                hideError();
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!mainFrameFailed) hideError();
                CookieManager.getInstance().flush();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    if (sameHostAsBase(uri)) return false;
                    return openExternal(uri);
                }
                return openExternal(uri);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    mainFrameFailed = true;
                    String msg = error != null ? String.valueOf(error.getDescription()) : "اینترنت و آدرس سرور را بررسی کنید.";
                    showError("اتصال به سامانه برقرار نشد", msg);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && response != null && response.getStatusCode() >= 500) {
                    mainFrameFailed = true;
                    showError("سرور در دسترس نیست", "کد پاسخ سرور: " + response.getStatusCode());
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                mainFrameFailed = true;
                showError("گواهی HTTPS معتبر نیست", "برای امنیت حساب کاربران، اپ اتصال دارای خطای SSL را قبول نمی‌کند.");
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, length) ->
                downloadFile(url, userAgent, contentDisposition, mimetype));
    }

    private boolean openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
        }
        return true;
    }

    private void downloadFile(String url, String userAgent, String contentDisposition, String mimetype) {
        if (url == null || url.startsWith("blob:")) {
            Toast.makeText(this, "این نوع دانلود باید از سمت سایت به لینک فایل معمولی تبدیل شود.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) request.addRequestHeader("Cookie", cookie);
            if (userAgent != null) request.addRequestHeader("User-Agent", userAgent);
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
            request.setTitle(fileName);
            request.setDescription("دانلود از سامانه خانه ما");
            if (mimetype != null) request.setMimeType(mimetype);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            } else {
                request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName);
            }
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.enqueue(request);
            Toast.makeText(this, "دانلود شروع شد.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "دانلود فایل انجام نشد.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean sameHostAsBase(Uri uri) {
        try {
            URI base = new URI(baseUrl);
            String a = base.getHost();
            String b = uri.getHost();
            return a != null && b != null && a.equalsIgnoreCase(b);
        } catch (Exception e) {
            return true;
        }
    }

    private void loadHome() {
        hideError();
        if (baseUrl.isEmpty()) {
            showServerDialog(true);
            return;
        }
        if (!isOnline()) {
            showError("اینترنت در دسترس نیست", "اتصال اینترنت را بررسی و دوباره تلاش کنید.");
            return;
        }
        webView.loadUrl(baseUrl);
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null || cm.getActiveNetwork() == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true;
        }
    }

    private void showServerDialog(boolean required) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https://hms.example.com");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(baseUrl);
        input.setSelectAllOnFocus(true);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("آدرس سامانه HMS")
                .setMessage("آدرس کامل سایت شرکت را وارد کنید.")
                .setView(input)
                .setPositiveButton("ذخیره", null);
        if (!required) builder.setNegativeButton("انصراف", null);

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = normalizeUrl(input.getText().toString());
            if (value.isEmpty()) {
                input.setError("آدرس سرور را وارد کنید");
                return;
            }
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                input.setError("آدرس معتبر نیست");
                return;
            }
            baseUrl = value;
            preferences.edit().putString(KEY_SERVER_URL, baseUrl).apply();
            dialog.dismiss();
            loadHome();
        }));
        dialog.setCanceledOnTouchOutside(!required);
        dialog.setCancelable(!required);
        dialog.show();
    }

    private void showSettingsMenu() {
        String[] items = {"تغییر آدرس سرور", "بارگذاری مجدد", "خروج و پاک‌کردن نشست"};
        new AlertDialog.Builder(this)
                .setTitle("تنظیمات اپ")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        showServerDialog(false);
                    } else if (which == 1) {
                        webView.reload();
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("خروج از حساب")
                                .setMessage("کوکی‌ها و نشست ورود این اپ پاک شوند؟")
                                .setPositiveButton("بله", (d, w) -> clearSession())
                                .setNegativeButton("خیر", null)
                                .show();
                    }
                })
                .show();
    }

    private void clearSession() {
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        WebStorage.getInstance().deleteAllData();
        webView.clearCache(true);
        webView.clearHistory();
        loadHome();
    }

    private String normalizeUrl(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.isEmpty()) return "";
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "https://" + value;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private void showError(String title, String message) {
        errorTitle.setText(title);
        errorText.setText(message);
        errorPanel.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
    }

    private void hideError() {
        errorPanel.setVisibility(View.GONE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || filePathCallback == null) return;

        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clipData = data.getClipData();
            if (clipData != null) {
                results = new Uri[clipData.getItemCount()];
                for (int i = 0; i < clipData.getItemCount(); i++) results[i] = clipData.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
