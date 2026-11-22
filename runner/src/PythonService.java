package dev.nativeagent.runner;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** Non-exported bound service. A new local HTTPS origin and worker are allocated for each call. */
public final class PythonService extends Service {
    private static final Map<String,String> TYPES = Map.of(
        "index.html", "text/html", "host.js", "application/javascript",
        "script-worker.js", "application/javascript", "shell-runtime.js", "application/javascript",
        "worker.js", "application/javascript", "pyodide.js", "application/javascript",
        "pyodide.asm.js", "application/javascript", "pyodide.asm.wasm", "application/wasm",
        "python_stdlib.zip", "application/zip", "pyodide-lock.json", "application/json");
    static final String POLICY = "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; "
        + "connect-src 'self'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; ";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Messenger mailbox = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private Messenger recipient;
    private WebView view;
    private boolean pending;
    private static boolean directorySelected;

    @Override public void onCreate() {
        super.onCreate();
        if (!directorySelected) {
            WebView.setDataDirectorySuffix("local-python");
            directorySelected = true;
        }
    }

    @Override public IBinder onBind(Intent intent) { return mailbox.getBinder(); }

    private boolean receive(Message message) {
        if (message.what != 1 || message.replyTo == null || message.sendingUid != android.os.Process.myUid()) return true;
        if (recipient != null) {
            send(message.replyTo, PythonRunner.failure("Python is already running", false));
            return true;
        }
        recipient = message.replyTo;
        String data = message.getData().getString("request");
        if (data == null || data.length() > PythonRunner.MAX_REQUEST) {
            finish(PythonRunner.failure("Invalid snapshot", false));
        } else if (checkSelfPermission(android.Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED) {
            finish(PythonRunner.failure("Runner requires a package without INTERNET permission", false));
        } else {
            try { start(data); }
            catch (RuntimeException | JSONException error) { finish(PythonRunner.failure("Local runtime initialization failed", false)); }
        }
        return true;
    }

    private void start(String data) throws JSONException {
        JSONObject files = new JSONObject(data).getJSONObject("files");
        Map<String,byte[]> scripts = new HashMap<>();
        for (var names = files.keys(); names.hasNext();) {
            String name = names.next();
            if (name.endsWith(".js") || name.endsWith(".mjs")) {
                scripts.put("/project/" + name, files.getString(name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        String host = "run-" + UUID.randomUUID() + ".invalid";
        String url = "https://" + host + "/index.html";
        WebView browser = new WebView(this);
        view = browser;
        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBlockNetworkLoads(true);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setGeolocationEnabled(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(false);
        browser.setWebViewClient(new WebViewClient() {
            private boolean launched;
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri uri = r.getUrl();
                String resource = uri.getEncodedPath();
                if (!"GET".equals(r.getMethod()) || !"https".equals(uri.getScheme())
                    || !host.equals(uri.getEncodedAuthority()) || resource == null || !resource.startsWith("/")) {
                    return denied();
                }
                String name = resource.substring(1);
                byte[] source = scripts.get(uri.getPath());
                if (source != null) return new WebResourceResponse("application/javascript", "UTF-8", 200, "OK",
                    Map.of("Cache-Control", "no-store", "X-Content-Type-Options", "nosniff",
                        "Content-Security-Policy", POLICY + "worker-src 'none'"), new ByteArrayInputStream(source));
                String type = TYPES.get(name);
                if (type == null) return denied();
                var headers = new HashMap<String,String>();
                headers.put("Cache-Control", "no-store");
                headers.put("X-Content-Type-Options", "nosniff");
                headers.put("Referrer-Policy", "no-referrer");
                // Only the trusted host page may start a worker. Worker code cannot spawn children.
                headers.put("Content-Security-Policy", POLICY + (name.equals("index.html")
                    ? "worker-src 'self'" : "worker-src 'none'"));
                try {
                    return new WebResourceResponse(type, type.startsWith("text") || type.contains("javascript") ||
                        type.contains("json") ? "UTF-8" : null, 200, "OK", headers, getAssets().open("python/" + name));
                } catch (IOException missing) {
                    return denied();
                }
            }
            @Override public void onPageFinished(WebView v, String finishedUrl) {
                if (view != v || launched || !url.equals(finishedUrl)) return;
                launched = true;
                // The data crosses into JS as a quoted string, then JSON.parse. It never becomes source.
                String literal = JSONObject.quote(data).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
                v.evaluateJavascript("typeof runProject==='function' && (runProject(JSON.parse(" + literal + ")),true)",
                    ready -> {
                        if (view != v) return;
                        if (!"true".equals(ready)) finish(PythonRunner.failure("Bundled runner page unavailable", false));
                        else poll(v);
                    });
            }
            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                if (view == v) finish(PythonRunner.failure("Python renderer terminated", false));
                return true;
            }
        });
        main.postDelayed(() -> {
            if (view == browser) finish(PythonRunner.failure("Python service timed out", true));
        }, 26_000);
        browser.loadUrl(url);
    }

    private void poll(WebView browser) {
        if (view != browser || pending) return;
        pending = true;
        browser.evaluateJavascript("typeof completed==='object' ? completed : null", raw -> {
            if (view != browser) return;
            pending = false;
            if (raw == null || "null".equals(raw)) {
                main.postDelayed(() -> poll(browser), 120);
                return;
            }
            try {
                JSONObject result = new JSONObject(raw);
                if (result.getString("stdout").length() > 2048 || result.getString("stderr").length() > 2048) {
                    throw new JSONException("oversized");
                }
                finish(new JSONObject().put("exitCode", result.getInt("exitCode")).put("stdout", result.getString("stdout"))
                    .put("stderr", result.getString("stderr")).put("timedOut", result.getBoolean("timedOut"))
                    .put("truncated", result.getBoolean("truncated")));
            } catch (JSONException invalid) { finish(PythonRunner.failure("Malformed renderer output", false)); }
        });
    }

    private static WebResourceResponse denied() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Denied", Map.of(),
            new ByteArrayInputStream(new byte[0]));
    }

    private static void send(Messenger target, JSONObject result) {
        var message = Message.obtain(null, 2);
        var args = new Bundle();
        args.putString("result", result.toString());
        message.setData(args);
        try { target.send(message); } catch (RemoteException unavailable) { /* Caller already stopped. */ }
    }

    private void finish(JSONObject result) {
        Messenger target = recipient;
        recipient = null;
        main.removeCallbacksAndMessages(null);
        if (view != null) { WebView old = view; view = null; old.destroy(); }
        pending = false;
        if (target != null) send(target, result);
    }

    @Override public boolean onUnbind(Intent intent) {
        finish(PythonRunner.failure("Python task cancelled", false));
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        finish(PythonRunner.failure("Python service stopped", false));
        super.onDestroy();
    }
}
