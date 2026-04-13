package com.liteplus.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    static final int VPN_REQUEST_CODE = 1001;

    WebView webView;
    String  injectJs         = "";
    String  activeId         = "";
    String  pendingShared    = "";
    String  pendingLoginUid  = "";
    String  pendingLoginPass = "";

    String  pendingVpnType = "";
    String  pendingVpnHost = "";
    int     pendingVpnPort = 0;
    String  pendingVpnUser = "";
    String  pendingVpnPass = "";

    volatile boolean proxyApplying = false;

    // File chooser for media upload in Messenger Web
    ValueCallback<Uri[]>        fileChooserCallback;
    ActivityResultLauncher<Intent> filePickerLauncher;

    // Target Facebook URL — m.facebook.com supports Messenger, notifications, full features
    static final String FB_HOME = "https://m.facebook.com/";

    // Standard mobile Chrome UA — Facebook serves proper HTML pages to WebView
    static final String MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 12; SM-A125F Build/SP1A.210812.016) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.6099.144 Mobile Safari/537.36";

    // FB In-App Browser UA — used on checkpoint/verification pages so Facebook
    // recognises the session as coming from FB Lite and sends OTP/codes reliably
    static final String FB_IAB_UA =
        "Mozilla/5.0 (Linux; Android 12; SM-A125F Build/SP1A.210812.016) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.6099.144 Mobile Safari/537.36 " +
        "[FB_IAB/FB4A;FBAV/386.0.0.26.107;FBRV/0]";

    // Desktop UA — used for messages/chat so Facebook shows Messenger Web
    // instead of the "Get the Messenger app" download promo screen
    static final String DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36";

    // ══════════════════════════════════════════════════════════════════════
    //  Java Bridge — window.LP
    // ══════════════════════════════════════════════════════════════════════
    class Bridge {

        // ── Navigation ──────────────────────────────────────────────────
        @JavascriptInterface
        public void goHome() {
            runOnUiThread(() -> {
                // Do NOT clearHistory — let user navigate back to Facebook naturally
                webView.loadUrl("file:///android_asset/www/index.html");
            });
        }

        // ── Active container ─────────────────────────────────────────────
        @JavascriptInterface public String getActiveId()          { return activeId; }
        @JavascriptInterface public void   setActiveId(String id) { activeId = id;   }

        // ── Session storage ──────────────────────────────────────────────
        @JavascriptInterface
        public void saveSession(String id, String cookies) {
            sp().edit().putString("s_" + id, cookies).apply();
        }

        @JavascriptInterface
        public String loadSession(String id) {
            return sp().getString("s_" + id, "");
        }

        @JavascriptInterface
        public void deleteSession(String id) {
            sp().edit().remove("s_" + id).apply();
        }

        // ── Container list ───────────────────────────────────────────────
        @JavascriptInterface
        public void saveContainerList(String json) {
            sp().edit().putString("container_list", json).apply();
        }

        @JavascriptInterface
        public String getContainerList() {
            return sp().getString("container_list", "[]");
        }

        // ── Cookie access ────────────────────────────────────────────────
        @JavascriptInterface
        public String getCookies(String url) {
            String c = CookieManager.getInstance().getCookie(url);
            return c != null ? c : "";
        }

        // ── Open Facebook (fresh — no saved session) ─────────────────────
        @JavascriptInterface
        public void openFacebook() {
            runOnUiThread(() -> {
                CookieManager cm = CookieManager.getInstance();
                cm.removeAllCookies(done -> {
                    cm.flush();
                    runOnUiThread(() ->
                        webView.postDelayed(() ->
                            webView.loadUrl(FB_HOME, fbLiteHeaders()), 80));
                });
            });
        }

        // ── Restore session cookies → open Facebook ──────────────────────
        @JavascriptInterface
        public void restoreSession(final String cookieStr) {
            runOnUiThread(() -> {
                CookieManager cm = CookieManager.getInstance();
                cm.removeAllCookies(done -> {
                    injectCookies(cm, cookieStr);
                    cm.flush();
                    // 120ms — lets CookieManager flush cookies before page load
                    runOnUiThread(() ->
                        webView.postDelayed(() ->
                            webView.loadUrl(FB_HOME, fbLiteHeaders()), 120));
                });
            });
        }

        // ── Import cookies to WebView (no navigation) ────────────────────
        @JavascriptInterface
        public void importCookies(final String cookieStr) {
            runOnUiThread(() -> {
                CookieManager cm = CookieManager.getInstance();
                injectCookies(cm, cookieStr);
                cm.flush();
            });
        }

        // ── Clipboard ────────────────────────────────────────────────────
        @JavascriptInterface
        public void copyText(String text) {
            ClipboardManager cm =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null)
                cm.setPrimaryClip(ClipData.newPlainText("mp_cookies", text));
        }

        // ── Clear WebView cache (keeps cookies / session intact) ─────────
        @JavascriptInterface
        public void clearCache() {
            runOnUiThread(() -> {
                webView.clearCache(true);
                webView.clearFormData();
            });
        }

        // ── Open URL in external browser (Telegram, etc.) ────────────────
        @JavascriptInterface
        public void openExternalUrl(String url) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) {}
        }

        // ══════════════════════════════════════════════════════════════════
        //  PROXY BRIDGE
        // ══════════════════════════════════════════════════════════════════

        @JavascriptInterface
        public void setProxy(String type, String host, String port,
                             String user, String pass) {
            int portInt = 0;
            try { portInt = Integer.parseInt(port.trim()); } catch (Exception e) {}

            // commit() is synchronous — JS can immediately read back correct value
            sp().edit()
                .putString("proxy_type", type)
                .putString("proxy_host", host.trim())
                .putString("proxy_port", port.trim())
                .putString("proxy_user", user.trim())
                .putString("proxy_pass", pass.trim())
                .commit();

            final int fp = portInt;
            runOnUiThread(() -> {
                doApplyProxy(type, host.trim(), fp, user.trim(), pass.trim());
                requestVpnAndStart(type, host.trim(), fp, user.trim(), pass.trim());
            });
        }

        @JavascriptInterface
        public void clearProxy() {
            // commit() synchronous so next getProxy() returns empty immediately
            sp().edit()
                .remove("proxy_type").remove("proxy_host")
                .remove("proxy_port").remove("proxy_user").remove("proxy_pass")
                .commit();
            runOnUiThread(() -> {
                doClearProxy();
                stopVpnService();
            });
        }

        @JavascriptInterface
        public String getProxy() {
            SharedPreferences p = sp();
            String host = p.getString("proxy_host", "");
            if (host.isEmpty()) return "";
            return p.getString("proxy_type", "HTTP") + "|" +
                   host + "|" +
                   p.getString("proxy_port", "") + "|" +
                   p.getString("proxy_user", "") + "|" +
                   p.getString("proxy_pass", "");
        }

        // ── VPN status ───────────────────────────────────────────────────
        @JavascriptInterface
        public boolean isVpnActive() {
            return ProxyVpnService.instance != null;
        }

        // ── Shared text (from intent) ────────────────────────────────────
        @JavascriptInterface
        public String getSharedText() {
            String t = pendingShared;
            pendingShared = "";
            return t;
        }

        // ── Pending Facebook login auto-fill ─────────────────────────────
        @JavascriptInterface
        public void setPendingLogin(String uid, String pass) {
            pendingLoginUid  = uid  != null ? uid.trim()  : "";
            pendingLoginPass = pass != null ? pass.trim() : "";
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  VPN management
    // ══════════════════════════════════════════════════════════════════════

    void requestVpnAndStart(String type, String host, int port,
                            String user, String pass) {
        pendingVpnType = type;
        pendingVpnHost = host;
        pendingVpnPort = port;
        pendingVpnUser = user;
        pendingVpnPass = pass;

        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            startActivityForResult(prepare, VPN_REQUEST_CODE);
        } else {
            startVpnService();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startVpnService();
        }
    }

    void startVpnService() {
        Intent i = new Intent(this, ProxyVpnService.class);
        i.setAction(ProxyVpnService.ACTION_START);
        startService(i);
    }

    void stopVpnService() {
        Intent i = new Intent(this, ProxyVpnService.class);
        i.setAction(ProxyVpnService.ACTION_STOP);
        startService(i);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Proxy application (WebView-level routing via ProxyController)
    // ══════════════════════════════════════════════════════════════════════
    void doApplyProxy(String type, String host, int port,
                      String user, String pass) {

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            System.setProperty("http.proxyHost",  host);
            System.setProperty("http.proxyPort",  String.valueOf(port));
            System.setProperty("https.proxyHost", host);
            System.setProperty("https.proxyPort", String.valueOf(port));
            reloadFacebookOnly();
            return;
        }

        String scheme;
        if ("SOCKS5".equalsIgnoreCase(type)) scheme = "socks5://";
        else if ("SOCKS4".equalsIgnoreCase(type)) scheme = "socks4://";
        else scheme = "http://";

        String proxyUrl = scheme + host + ":" + port;
        ProxyConfig config = new ProxyConfig.Builder()
            .addProxyRule(proxyUrl)
            .build();

        if (proxyApplying) return;
        proxyApplying = true;

        java.util.concurrent.Executor exec = Executors.newSingleThreadExecutor();
        ProxyController.getInstance().clearProxyOverride(exec, () ->
            ProxyController.getInstance().setProxyOverride(
                config, exec,
                () -> {
                    proxyApplying = false;
                    // Only reload if currently on Facebook (not on local app UI)
                    runOnUiThread(() -> reloadFacebookOnly());
                }
            )
        );
    }

    void doClearProxy() {
        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");
        System.clearProperty("https.proxyHost");
        System.clearProperty("https.proxyPort");
        proxyApplying = false;

        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            ProxyController.getInstance().clearProxyOverride(
                Executors.newSingleThreadExecutor(),
                () -> runOnUiThread(() -> reloadFacebookOnly())
            );
        } else {
            reloadFacebookOnly();
        }
    }

    // Only reload WebView if we're on a Facebook page — never reload local app UI
    void reloadFacebookOnly() {
        String url = webView.getUrl();
        if (url != null && url.contains("facebook.com") && !url.startsWith("file:///")) {
            webView.reload();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  onCreate
    // ══════════════════════════════════════════════════════════════════════
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Register file picker for Messenger Web media uploads (must be before setContentView)
        filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (fileChooserCallback == null) return;
                Uri[] uris = null;
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    if (data.getClipData() != null) {
                        ClipData clip = data.getClipData();
                        uris = new Uri[clip.getItemCount()];
                        for (int i = 0; i < clip.getItemCount(); i++) {
                            uris[i] = clip.getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        uris = new Uri[]{data.getData()};
                    }
                }
                fileChooserCallback.onReceiveValue(uris);
                fileChooserCallback = null;
            });

        setContentView(R.layout.activity_main);
        fullScreen();

        // Request microphone + camera at startup for Messenger Web voice/video
        String[] perms = {Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA};
        java.util.List<String> needed = new java.util.ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), 200);
        }

        // Handle share intent text before UI loads
        Intent intent = getIntent();
        if (Intent.ACTION_SEND.equals(intent.getAction()) &&
            "text/plain".equals(intent.getType())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) pendingShared = text.trim();
        }

        injectJs = readAsset("inject.js");
        webView  = findViewById(R.id.webView);

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setDatabaseEnabled(true);
        ws.setSaveFormData(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // LOAD_DEFAULT: balanced speed + freshness (better than CACHE_ELSE_NETWORK for m.facebook.com)
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(false);
        ws.setTextZoom(100);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        // Disable geolocation — no permission dialogs, less overhead
        ws.setGeolocationEnabled(false);
        // Allow content access for media
        ws.setAllowContentAccess(true);
        // Load images immediately
        ws.setLoadsImagesAutomatically(true);
        // Standard mobile Chrome UA — Facebook sends proper HTML pages to WebView
        ws.setUserAgentString(MOBILE_UA);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new Bridge(), "LP");

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String url = r.getUrl().toString();

                // ── Block dangerous non-http schemes ─────────────────────
                if (!url.startsWith("http://") &&
                    !url.startsWith("https://") &&
                    !url.startsWith("file:///")) {
                    return true; // block intent://, fb://, market://, etc.
                }

                // ── www.facebook.com/messages — load with Desktop UA ─────────
                // Must use v.loadUrl (not return false) so the UA takes effect
                // before the HTTP request is sent to Facebook's servers
                if ((url.contains("www.facebook.com/messages") ||
                     url.contains("www.facebook.com/message_request"))) {
                    v.getSettings().setUserAgentString(DESKTOP_UA);
                    v.loadUrl(url, fbLiteHeaders());
                    return true;
                }

                // ── messenger.com → Desktop Messenger Web on www.facebook.com ──
                if (url.contains("messenger.com")) {
                    v.getSettings().setUserAgentString(DESKTOP_UA);
                    v.loadUrl("https://www.facebook.com/messages/", fbLiteHeaders());
                    return true;
                }

                // ── m.facebook.com/messages → Desktop Messenger Web ──────────
                // Uses Desktop UA so Facebook shows Messenger Web instead of
                // the "Get the Messenger app" download promo
                if (url.contains("m.facebook.com/messages") ||
                    url.contains("m.facebook.com/message_request") ||
                    url.contains("touch.facebook.com/messages") ||
                    url.contains("mbasic.facebook.com/messages")) {
                    v.getSettings().setUserAgentString(DESKTOP_UA);
                    v.loadUrl("https://www.facebook.com/messages/", fbLiteHeaders());
                    return true;
                }

                // ── Allow non-Facebook https links ────────────────────────
                if (!url.contains("facebook.com")) return false;

                // ── CHECKPOINT CHECK — must happen BEFORE the m.facebook.com ──
                // early-return below, because checkpoint URLs contain m.facebook.com!
                // e.g. https://m.facebook.com/checkpoint/...
                boolean isCheckpoint =
                    url.contains("/checkpoint")      ||
                    url.contains("/two_step")        ||
                    url.contains("/identity_verify") ||
                    url.contains("/verification")    ||
                    url.contains("/phone_number")    ||
                    url.contains("/security_code")   ||
                    url.contains("/sms");

                if (isCheckpoint) {
                    // Set FB Lite IAB UA NOW — before the page request is made.
                    // This is the only correct moment; changing UA in onPageStarted
                    // is too late because the HTTP request already left the device.
                    v.getSettings().setUserAgentString(FB_IAB_UA);
                    return false; // WebView loads checkpoint with FB_IAB_UA
                }

                // ── Already on m.facebook.com or mbasic — allow ───────────
                if (url.contains("m.facebook.com") ||
                    url.contains("mbasic.facebook.com")) return false;

                // ── Other auth / login flows ──────────────────────────────
                boolean isAuthFlow =
                    url.contains("/login")       ||
                    url.contains("/recover")     ||
                    url.contains("/confirm")     ||
                    url.contains("/save_device") ||
                    url.contains("/save-device") ||
                    url.contains("/help");

                if (isAuthFlow) return false;

                // ── Redirect www / touch → m.facebook.com ────────────────
                v.getSettings().setUserAgentString(MOBILE_UA);
                final String mUrl = url
                    .replaceFirst("://touch\\.facebook\\.com", "://m.facebook.com")
                    .replaceFirst("://www\\.facebook\\.com",   "://m.facebook.com");
                CookieManager.getInstance().flush();
                v.postDelayed(() -> v.loadUrl(mUrl, fbLiteHeaders()), 200);
                return true;
            }

            // ── Restore normal UA when leaving checkpoint pages ──────────
            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                super.onPageStarted(v, url, favicon);
                if (url == null) return;

                // ── Catch SPA / JS-driven navigation to messages ─────────
                // Redirect any messages URL (m/mbasic/touch) to Desktop Messenger Web
                if ((url.contains("m.facebook.com/messages")     ||
                     url.contains("m.facebook.com/message_request") ||
                     url.contains("mbasic.facebook.com/messages") ||
                     url.contains("touch.facebook.com/messages")) &&
                    !url.contains("www.facebook.com")) {
                    v.getSettings().setUserAgentString(DESKTOP_UA);
                    v.loadUrl("https://www.facebook.com/messages/", fbLiteHeaders());
                    return;
                }

                // ── Preserve Desktop UA while on Messenger Web ────────────
                if (url.contains("www.facebook.com/messages") ||
                    url.contains("www.facebook.com/message_request")) {
                    v.getSettings().setUserAgentString(DESKTOP_UA);
                    return;
                }

                if (url.contains("facebook.com")) {
                    boolean isCheckpoint =
                        url.contains("/checkpoint") || url.contains("/two_step") ||
                        url.contains("/identity_verify") || url.contains("/verification") ||
                        url.contains("/phone_number")    || url.contains("/security_code") ||
                        url.contains("/sms");
                    if (!isCheckpoint) {
                        v.getSettings().setUserAgentString(MOBILE_UA);
                    }
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);

                // ── Fallback A: redirect any non-www messages URL → Desktop Messenger Web
                if (url != null &&
                    (url.contains("m.facebook.com/messages")      ||
                     url.contains("m.facebook.com/message_request") ||
                     url.contains("mbasic.facebook.com/messages")  ||
                     url.contains("touch.facebook.com/messages")) &&
                    !url.contains("www.facebook.com")) {
                    v.getSettings().setUserAgentString(DESKTOP_UA);
                    v.loadUrl("https://www.facebook.com/messages/", fbLiteHeaders());
                    return;
                }

                // ── Preserve Desktop UA on Messenger Web ──────────────────
                // Do NOT return — fall through so inject.js (bar) is also injected
                if (url != null &&
                    (url.contains("www.facebook.com/messages") ||
                     url.contains("www.facebook.com/message_request"))) {
                    v.getSettings().setUserAgentString(DESKTOP_UA);
                }

                // ── Fallback B: content-based detection injected every page load ──
                // Detects "Get the Messenger app" promo by text & messenger.com links
                // Runs independently of inject.js, covers any missed navigations
                if (url != null && url.contains("facebook.com") && !url.startsWith("file:///")) {
                    final String msgCheckJs =
                        "(function(){" +
                        "  if(window._mpMsgCheck) return;" +
                        "  window._mpMsgCheck = true;" +
                        "  var _redir = false;" +
                        "  function _go(){" +
                        "    if(_redir) return; _redir=true;" +
                        "    location.replace('https://www.facebook.com/messages/');" +
                        "  }" +
                        "  function _chk(){" +
                        "    if(_redir) return;" +
                        "    if(/mbasic\\.facebook\\.com/.test(location.hostname)) return;" +
                        "    if(!/m\\.facebook\\.com/.test(location.hostname)) return;" +
                        "    if(/\\/(messages|message_request)/i.test(location.pathname)){" +
                        "      _go(); return;" +
                        "    }" +
                        "    var t=(document.body&&(document.body.innerText||document.body.textContent))||'';" +
                        "    if(t.indexOf('Get the Messenger app')>-1||t.indexOf('Get Messenger')>-1){" +
                        "      _go(); return;" +
                        "    }" +
                        "    if(document.querySelector('a[href*=\"messenger.com\"]')){" +
                        "      _go(); return;" +
                        "    }" +
                        "  }" +
                        "  setInterval(_chk, 350);" +
                        "  window.addEventListener('popstate', _chk);" +
                        "  (new MutationObserver(_chk)).observe(document.body||document.documentElement," +
                        "    {childList:true,subtree:true});" +
                        "})();";
                    v.evaluateJavascript(msgCheckJs, null);
                }

                if (url != null && url.contains("facebook.com") && !url.startsWith("file:///")) {
                    // Inject floating 3-dot menu
                    if (!injectJs.isEmpty()) v.evaluateJavascript(injectJs, null);

                    // Auto-fill login form if UID+pass are pending
                    if (!pendingLoginUid.isEmpty()) {
                        final String safeUid  = pendingLoginUid.replace("'", "\\'");
                        final String safePass = pendingLoginPass.replace("'", "\\'");
                        final String fillJs =
                            "(function(){" +
                            "  var e=document.querySelector('input[name=\"email\"],input[name=\"m_login_email\"]');" +
                            "  var p=document.querySelector('input[name=\"pass\"]');" +
                            "  if(e&&p){" +
                            "    e.value='" + safeUid  + "';" +
                            "    p.value='" + safePass + "';" +
                            "    e.dispatchEvent(new Event('input',{bubbles:true}));" +
                            "    p.dispatchEvent(new Event('input',{bubbles:true}));" +
                            "  }" +
                            "})();";
                        v.evaluateJavascript(fillJs, null);
                        pendingLoginUid  = "";
                        pendingLoginPass = "";
                    }
                }

                // Deliver shared text to JS after home page loads
                if (url != null && url.contains("index.html") && !pendingShared.isEmpty()) {
                    final String txt = pendingShared.replace("'", "\\'");
                    pendingShared = "";
                    v.evaluateJavascript(
                        "if(typeof handleSharedText==='function')handleSharedText('" + txt + "');",
                        null);
                }

                CookieManager.getInstance().flush();
            }

            // Proxy authentication challenge
            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
                                                   String host, String realm) {
                String user = sp().getString("proxy_user", "");
                String pass = sp().getString("proxy_pass", "");
                if (!user.isEmpty()) handler.proceed(user, pass);
                else handler.cancel();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            // Grant mic / camera / audio-capture requests from Messenger Web
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }

            // Handle image / video / file picker for Messenger Web uploads
            @Override
            public boolean onShowFileChooser(WebView wv,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                    fileChooserCallback = null;
                }
                fileChooserCallback = filePathCallback;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    filePickerLauncher.launch(intent);
                } catch (Exception e) {
                    fileChooserCallback = null;
                    return false;
                }
                return true;
            }
        });

        // Restore saved proxy AFTER WebViewClient is set, BEFORE first page load
        restoreSavedProxy();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                String url = webView.getUrl();
                // On home screen — exit app
                if (url == null || url.startsWith("file:///")) {
                    finish();
                    return;
                }
                if (webView.canGoBack()) webView.goBack();
                else {
                    // Go home if no back history on Facebook
                    webView.loadUrl("file:///android_asset/www/index.html");
                }
            }
        });

        webView.loadUrl("file:///android_asset/www/index.html");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════

    void restoreSavedProxy() {
        String host = sp().getString("proxy_host", "");
        if (host.isEmpty()) return;
        String type = sp().getString("proxy_type", "HTTP");
        int port = 0;
        try { port = Integer.parseInt(sp().getString("proxy_port", "0")); }
        catch (Exception e) {}
        String user = sp().getString("proxy_user", "");
        String pass = sp().getString("proxy_pass", "");
        doApplyProxy(type, host, port, user, pass);
        startVpnService();
    }

    // Extra HTTP headers sent with every Facebook page load — makes the server
    // treat requests as coming from FB Lite, improving session stability & OTP delivery
    Map<String, String> fbLiteHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("X-Requested-With", "com.facebook.lite");
        h.put("Accept-Language",  "en-US,en;q=0.9");
        return h;
    }

    void injectCookies(CookieManager cm, String cookieStr) {
        if (cookieStr == null || cookieStr.isEmpty()) return;

        // Cookies that must have a long (2-year) expiry for persistent sessions
        Set<String> longLived = new HashSet<>(Arrays.asList(
            "datr", "sb", "fr", "c_user", "xs", "wd", "dpr",
            "locale", "presence", "usida", "act"
        ));

        // Build an expires string 2 years from now (RFC 1123)
        long two_years = System.currentTimeMillis() + (2L * 365 * 24 * 60 * 60 * 1000);
        String expires = new SimpleDateFormat(
            "EEE, dd MMM yyyy HH:mm:ss z", Locale.US).format(new Date(two_years));

        // All Facebook domain targets — root domain covers all subdomains
        String[] targets = {
            "https://facebook.com",
            "https://m.facebook.com",
            "https://www.facebook.com",
            "https://mbasic.facebook.com"
        };

        for (String pair : cookieStr.split(";")) {
            String p = pair.trim();
            if (p.isEmpty() || !p.contains("=")) continue;
            String name = p.split("=")[0].trim();

            String base = "domain=.facebook.com; path=/; SameSite=None; Secure";
            String cookie = longLived.contains(name)
                ? p + "; " + base + "; expires=" + expires
                : p + "; " + base;

            for (String target : targets) {
                cm.setCookie(target, cookie);
            }
        }
        cm.flush();
    }

    SharedPreferences sp() {
        return getSharedPreferences("lp_sessions", Context.MODE_PRIVATE);
    }

    String readAsset(String name) {
        try {
            InputStream is = getAssets().open(name);
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    void fullScreen() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN        |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION   |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY  |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );
    }

    @Override protected void onResume() { super.onResume(); fullScreen(); }
    @Override protected void onPause()  { super.onPause();  CookieManager.getInstance().flush(); }
}
