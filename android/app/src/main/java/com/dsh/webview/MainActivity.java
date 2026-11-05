package com.dsh.webview;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * One-screen WebView shell that loads the official DSH Web UI.
 *
 * <p>The shell deliberately contains no chat logic of its own: every DSH
 * feature (sessions, tools, approvals, files) stays in the official Web UI and
 * therefore follows official releases automatically. This activity only owns
 * the things a browser tab cannot do well on a phone: remembering the server
 * address, full-screen layout, hardware back navigation, file uploads,
 * authenticated downloads and a clear setup/error state.</p>
 */
public class MainActivity extends Activity {

    private static final String TAG = "DshWebShell";
    private static final String PREFS = "dsh_web_shell";
    private static final String KEY_URL = "server_url";
    private static final String EXTRA_URL = "url";
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_WRITE_STORAGE = 1002;

    private WebView webView;
    private ProgressBar progress;
    private View setupScreen;
    private View errorScreen;
    private ImageButton settingsButton;
    private EditText urlInput;
    private TextView setupMessage;
    private TextView errorDetail;

    private ValueCallback<Uri[]> filePathCallback;
    private String currentUrl;
    private String serverAuthority;
    private String pendingMainFrameUrl;
    private boolean mainFrameLoadFailed;
    private String[] pendingDownload;
    private String errorDetailText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        configureWebView();

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        // A launch extra is a one-shot deep link: only honour it on a cold
        // start, then drop it so a later recreation (for example a day/night
        // switch) resumes the server the user actually connected to.
        String incoming = savedInstanceState == null
                ? UrlNormalizer.normalize(intentUrl(getIntent()))
                : null;
        if (incoming != null) {
            getIntent().removeExtra(EXTRA_URL);
            saveUrl(incoming);
            openUrl(incoming);
        } else if (savedInstanceState != null) {
            // The activity is being recreated (for example on a day/night switch):
            // resume the last page instead of showing the setup screen again.
            String saved = storedUrl();
            if (saved != null) {
                openUrl(saved);
            } else {
                showSetup(null);
            }
        } else {
            String saved = storedUrl();
            if (saved != null) {
                openUrl(saved);
            } else {
                showSetup(null);
                prefillFromClipboard();
            }
        }
    }

    // ------------------------------------------------------------------
    // View wiring
    // ------------------------------------------------------------------

    private void bindViews() {
        webView = findViewById(R.id.web_view);
        progress = findViewById(R.id.progress);
        setupScreen = findViewById(R.id.setup_screen);
        errorScreen = findViewById(R.id.error_screen);
        settingsButton = findViewById(R.id.settings_button);
        urlInput = findViewById(R.id.url_input);
        setupMessage = findViewById(R.id.setup_message);
        errorDetail = findViewById(R.id.error_detail);

        findViewById(R.id.connect_button).setOnClickListener(view -> connectFromInput());
        findViewById(R.id.paste_button).setOnClickListener(view -> pasteFromClipboard());
        findViewById(R.id.retry_button).setOnClickListener(view -> retry());
        findViewById(R.id.edit_url_button).setOnClickListener(view -> showSetup(null));
        settingsButton.setOnClickListener(view -> showSettingsDialog());

        urlInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                connectFromInput();
                return true;
            }
            return false;
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new ShellWebViewClient());
        webView.setWebChromeClient(new ShellChromeClient());
        webView.setDownloadListener(new ShellDownloadListener());
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    private String storedUrl() {
        String value = prefs().getString(KEY_URL, null);
        return UrlNormalizer.normalize(value);
    }

    private void saveUrl(String url) {
        prefs().edit().putString(KEY_URL, url).apply();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /**
     * Persist the clean address of the configured DSH server, without the
     * one-time launch token.
     *
     * <p>Only called after a main-frame load that did not fail, so a failed
     * first visit keeps the token-bearing URL as the retry target. Navigation
     * to another site must not replace the configured server, so URLs on a
     * different authority are ignored.</p>
     */
    private void saveCleanCurrentUrl() {
        if (webView == null) {
            return;
        }
        String current = webView.getUrl();
        if (current == null) {
            return;
        }
        String normalized = UrlNormalizer.normalize(UrlNormalizer.stripLaunchToken(current));
        if (normalized == null) {
            return;
        }
        if (!UrlNormalizer.authorityOf(normalized).equals(serverAuthority)) {
            return;
        }
        currentUrl = normalized;
        saveUrl(normalized);
    }

    private void openUrl(String url) {
        currentUrl = url;
        serverAuthority = UrlNormalizer.authorityOf(url);
        setupScreen.setVisibility(View.GONE);
        errorScreen.setVisibility(View.GONE);
        settingsButton.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
    }

    private void connectFromInput() {
        String normalized = UrlNormalizer.normalize(urlInput.getText().toString());
        if (normalized == null) {
            showSetupMessage(getString(R.string.setup_invalid_url));
            return;
        }
        hideKeyboard();
        saveUrl(normalized);
        openUrl(normalized);
    }

    private void retry() {
        errorScreen.setVisibility(View.GONE);
        String target = currentUrl != null ? currentUrl : storedUrl();
        if (target != null) {
            openUrl(target);
        } else {
            showSetup(null);
        }
    }

    private void showSetup(String message) {
        errorScreen.setVisibility(View.GONE);
        settingsButton.setVisibility(View.GONE);
        setupScreen.setVisibility(View.VISIBLE);
        String saved = storedUrl();
        if (saved != null && urlInput.getText().length() == 0) {
            urlInput.setText(saved);
            urlInput.setSelection(urlInput.getText().length());
        }
        setupMessage.setVisibility(message == null ? View.GONE : View.VISIBLE);
        setupMessage.setText(message == null ? "" : message);
    }

    private void showSetupMessage(String message) {
        setupMessage.setVisibility(View.VISIBLE);
        setupMessage.setText(message);
    }

    private void prefillFromClipboard() {
        String clip = readClipboardUrl();
        if (clip != null && urlInput.getText().length() == 0) {
            urlInput.setText(clip);
            urlInput.setSelection(urlInput.getText().length());
        }
    }

    private void pasteFromClipboard() {
        String clip = readClipboardUrl();
        if (clip == null) {
            showSetupMessage(getString(R.string.setup_clipboard_empty));
            return;
        }
        urlInput.setText(clip);
        urlInput.setSelection(urlInput.getText().length());
        setupMessage.setVisibility(View.GONE);
    }

    private String readClipboardUrl() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            return null;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        return text == null ? null : UrlNormalizer.normalize(text.toString());
    }

    private void showLoadError(String detail) {
        errorDetailText = detail;
        errorDetail.setText(detail);
        errorScreen.setVisibility(View.VISIBLE);
        setupScreen.setVisibility(View.GONE);
        settingsButton.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
        if (webView != null) {
            webView.setVisibility(View.INVISIBLE);
        }
    }

    private void showSettingsDialog() {
        final CharSequence[] items = new CharSequence[]{
                getString(R.string.dialog_change_url),
                getString(R.string.dialog_reload),
                getString(R.string.dialog_open_browser),
                getString(R.string.dialog_clear_cookies),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_settings_title)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            showSetup(null);
                            break;
                        case 1:
                            if (webView != null) {
                                errorScreen.setVisibility(View.GONE);
                                webView.setVisibility(View.VISIBLE);
                                progress.setVisibility(View.VISIBLE);
                                webView.reload();
                            }
                            break;
                        case 2:
                            openCurrentInBrowser();
                            break;
                        case 3:
                            clearSession();
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    private void openCurrentInBrowser() {
        String url = webView != null && webView.getUrl() != null ? webView.getUrl() : currentUrl;
        if (url == null) {
            return;
        }
        openExternally(Uri.parse(url));
    }

    private void clearSession() {
        CookieManager cookies = CookieManager.getInstance();
        cookies.removeAllCookies(null);
        cookies.flush();
        if (webView != null) {
            webView.clearCache(true);
            webView.clearFormData();
            webView.loadUrl("about:blank");
        }
        showSetup(getString(R.string.dialog_clear_cookies_done));
    }

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show();
        }
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            }
        }
    }

    private static String intentUrl(Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_URL)) {
            return null;
        }
        return intent.getStringExtra(EXTRA_URL);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String incoming = UrlNormalizer.normalize(intentUrl(intent));
        if (incoming != null) {
            intent.removeExtra(EXTRA_URL);
            setIntent(intent);
            saveUrl(incoming);
            openUrl(incoming);
        }
    }

    @Override
    public void onBackPressed() {
        if (errorScreen.getVisibility() == View.VISIBLE) {
            super.onBackPressed();
            return;
        }
        if (setupScreen.getVisibility() == View.VISIBLE) {
            super.onBackPressed();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
            webView.pauseTimers();
        }
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
    }

    @Override
    protected void onDestroy() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (webView != null) {
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback == null) {
                return;
            }
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            return;
        }
        if (requestCode == REQ_WRITE_STORAGE) {
            if (pendingDownload != null) {
                String[] download = pendingDownload;
                pendingDownload = null;
                if (resultCode == RESULT_OK) {
                    enqueueDownload(download);
                } else {
                    Toast.makeText(this, R.string.download_permission_needed, Toast.LENGTH_SHORT).show();
                }
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WRITE_STORAGE && pendingDownload != null) {
            String[] download = pendingDownload;
            pendingDownload = null;
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                enqueueDownload(download);
            } else {
                Toast.makeText(this, R.string.download_permission_needed, Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ------------------------------------------------------------------
    // Downloads
    // ------------------------------------------------------------------

    private void startDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        if (url == null || url.startsWith("blob:") || url.startsWith("data:")) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] download = new String[]{url, userAgent, contentDisposition, mimeType};
        if (Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = download;
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE_STORAGE);
            return;
        }
        enqueueDownload(download);
    }

    @SuppressWarnings("deprecation")
    private void enqueueDownload(String[] download) {
        String url = download[0];
        String fileName = URLUtil.guessFileName(url, download[2], download[3]);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setMimeType(download[3]);
        request.addRequestHeader("User-Agent", download[1]);
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null) {
            // The DSH Web API authorizes downloads with the session cookie that
            // lives in the WebView, which the DownloadManager does not share.
            request.addRequestHeader("Cookie", cookie);
        }
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            manager.enqueue(request);
            Toast.makeText(this, getString(R.string.download_started, fileName), Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException | IllegalStateException e) {
            Log.w(TAG, "download failed", e);
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------
    // WebView clients
    // ------------------------------------------------------------------

    private final class ShellDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimeType, long contentLength) {
            startDownload(url, userAgent, contentDisposition, mimeType);
        }
    }

    private final class ShellWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(Uri.parse(url));
        }

        /** Keep http(s) navigation in the shell; hand every other scheme to the OS. */
        private boolean handleUrl(Uri uri) {
            if (uri == null) {
                return false;
            }
            String scheme = uri.getScheme();
            if (scheme == null) {
                return false;
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            if ("http".equals(scheme) || "https".equals(scheme)) {
                return false;
            }
            openExternally(uri);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            progress.setVisibility(View.VISIBLE);
            progress.setProgress(0);
            pendingMainFrameUrl = url;
            mainFrameLoadFailed = false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progress.setVisibility(View.GONE);
            if (errorScreen.getVisibility() != View.VISIBLE) {
                webView.setVisibility(View.VISIBLE);
            }
            pendingMainFrameUrl = null;
            if (!mainFrameLoadFailed) {
                saveCleanCurrentUrl();
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) {
                return;
            }
            mainFrameLoadFailed = true;
            String target = request.getUrl() != null ? request.getUrl().toString() : currentUrl;
            String detail = getString(R.string.error_network, target == null ? "" : target);
            if (!isNetworkAvailable()) {
                detail += "\n\n当前手机没有可用网络。";
            }
            showLoadError(detail);
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceResponse errorResponse) {
            if (!request.isForMainFrame()) {
                return;
            }
            int status = errorResponse.getStatusCode();
            if (status >= 400) {
                // A failed response means the launch token was not exchanged;
                // keep it as the retry target instead of persisting this URL.
                mainFrameLoadFailed = true;
            }
            if (status == 401 || status == 403) {
                showLoadError(getString(R.string.error_auth, status));
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler,
                                       android.net.http.SslError error) {
            // A broken certificate on an optional image or iframe must not hide
            // an otherwise usable page: cancel that request, and only replace
            // the page with the native error screen for the main frame.
            handler.cancel();
            if (!isMainFrameSslError(error)) {
                return;
            }
            mainFrameLoadFailed = true;
            String detail = error == null ? "" : String.valueOf(error.getPrimaryError());
            showLoadError(getString(R.string.error_ssl, detail));
        }

        /** Best-effort main-frame detection for SSL errors, which carry only a URL. */
        private boolean isMainFrameSslError(android.net.http.SslError error) {
            String failed = error == null ? null : error.getUrl();
            String page = webView == null ? null : webView.getUrl();
            if (failed == null) {
                return page == null;
            }
            if (failed.equals(pendingMainFrameUrl)) {
                return true;
            }
            return page == null || failed.equals(page);
        }
    }

    private final class ShellChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progress.setProgress(newProgress);
            progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
            }
            filePathCallback = callback;
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(resolveAcceptType(params));
            if (params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            try {
                startActivityForResult(Intent.createChooser(intent, getString(R.string.app_name)),
                        REQ_FILE_CHOOSER);
            } catch (ActivityNotFoundException e) {
                filePathCallback = null;
                return false;
            }
            return true;
        }

        private String resolveAcceptType(FileChooserParams params) {
            if (params == null) {
                return "*/*";
            }
            String[] acceptTypes = params.getAcceptTypes();
            if (acceptTypes != null && acceptTypes.length == 1
                    && acceptTypes[0] != null && !acceptTypes[0].trim().isEmpty()) {
                return acceptTypes[0].trim();
            }
            return "*/*";
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton(R.string.js_dialog_ok, (dialog, which) -> result.confirm())
                    .setOnCancelListener(dialog -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton(R.string.js_dialog_ok, (dialog, which) -> result.confirm())
                    .setNegativeButton(R.string.js_dialog_cancel, (dialog, which) -> result.cancel())
                    .setOnCancelListener(dialog -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                  JsPromptResult result) {
            final EditText input = new EditText(MainActivity.this);
            input.setText(defaultValue == null ? "" : defaultValue);
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton(R.string.js_dialog_ok,
                            (dialog, which) -> result.confirm(input.getText().toString()))
                    .setNegativeButton(R.string.js_dialog_cancel, (dialog, which) -> result.cancel())
                    .setOnCancelListener(dialog -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                                                       GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            request.deny();
        }

        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
            Log.d(TAG, consoleMessage.message() + " @" + consoleMessage.lineNumber());
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

    private boolean isNetworkAvailable() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return true;
        }
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
        return capabilities != null
                && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }
}
