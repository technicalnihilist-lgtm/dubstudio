package com.rajok.dubstudio;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1001;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private Uri picked;
    private String pickedName = "";
    private volatile boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(0xFF0B0F1A);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[]{"video/*", "audio/*"});
                try {
                    startActivityForResult(i, REQ_PICK);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        // targetSdk 35+ draws edge to edge, so keep the UI clear of the system bars.
        web.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(0, insets.getSystemWindowInsetTop(),
                        0, insets.getSystemWindowInsetBottom());
                return insets;
            }
        });

        web.addJavascriptInterface(new Bridge(), "DubBridge");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_PICK) {
            Uri uri = null;
            if (res == RESULT_OK && data != null && data.getData() != null) {
                uri = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                pickedName = queryName(uri);
            }
            picked = uri;
            if (fileCallback != null) {
                fileCallback.onReceiveValue(uri == null ? null : new Uri[]{uri});
                fileCallback = null;
            }
            if (uri != null) {
                final String n = pickedName;
                runOnUiThread(new Runnable() {
                    public void run() {
                        js("window.onFilePicked(" + JSONObject.quote(n) + ")");
                    }
                });
            }
        } else {
            super.onActivityResult(req, res, data);
        }
    }

    private String queryName(Uri uri) {
        String name = "";
        try (android.database.Cursor c = getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.isEmpty()) name = "video.mp4";
        return name;
    }

    private void js(String code) {
        if (web != null) web.evaluateJavascript(code, null);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("dubstudio", MODE_PRIVATE);
    }

    /** Everything the web UI can call. */
    public class Bridge {

        @JavascriptInterface
        public String settings() {
            return Settings.load(MainActivity.this).toJson();
        }

        @JavascriptInterface
        public void saveSettings(String json) {
            Settings.fromJson(json).save(MainActivity.this);
        }

        /** Every engine, its display name, and the voices it offers. */
        @JavascriptInterface
        public String engines() {
            try {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (java.util.Map.Entry<String, String> e : Providers.ENGINE_NAMES.entrySet()) {
                    String id = e.getKey();
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("id", id);
                    o.put("name", e.getValue());
                    o.put("clone", Providers.supportsCloning(id));
                    org.json.JSONArray vs = new org.json.JSONArray();
                    String[] ids = Providers.VOICES.get(id);
                    String[] labels = Providers.VOICE_LABELS.get(id);
                    for (int i = 0; i < ids.length; i++) {
                        org.json.JSONObject v = new org.json.JSONObject();
                        v.put("id", ids[i]);
                        v.put("label", labels != null && i < labels.length ? labels[i] : ids[i]);
                        vs.put(v);
                    }
                    o.put("voices", vs);
                    arr.put(o);
                }
                return arr.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        /** Which engine will actually run, once "auto" is resolved. */
        @JavascriptInterface
        public String activeEngines() {
            Settings s = Settings.load(MainActivity.this);
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("tts", s.ttsProvider());
                o.put("stt", s.sttProvider());
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String languages() {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < DubbingEngine.LANGS.length; i++) {
                if (i > 0) sb.append(",");
                sb.append("{\"code\":\"").append(DubbingEngine.LANGS[i].code)
                        .append("\",\"name\":\"").append(DubbingEngine.LANGS[i].name).append("\"}");
            }
            return sb.append("]").toString();
        }

        @JavascriptInterface
        public String voices() {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < DubbingEngine.VOICE_IDS.length; i++) {
                if (i > 0) sb.append(",");
                sb.append("{\"id\":\"").append(DubbingEngine.VOICE_IDS[i])
                        .append("\",\"label\":\"").append(DubbingEngine.VOICE_LABELS[i]).append("\"}");
            }
            return sb.append("]").toString();
        }

        @JavascriptInterface
        public void start(final String targetsJson, final String speaker, final String tone,
                          final double volume, final boolean cloneVoice) {
            if (busy) return;
            if (picked == null) {
                toastJs("Pick a video first.");
                return;
            }
            final Settings settings = Settings.load(MainActivity.this);
            if (settings.ttsProvider().isEmpty()) {
                toastJs("Add at least one API key in Settings first.");
                return;
            }
            if (settings.sttProvider().isEmpty()) {
                toastJs("Add a Sarvam, OpenAI or Gemini key so the speech can be understood.");
                return;
            }
            final List<String> targets = new ArrayList<>();
            try {
                JSONArray arr = new JSONArray(targetsJson);
                for (int i = 0; i < arr.length(); i++) targets.add(arr.getString(i));
            } catch (Exception e) {
                toastJs("Could not read the selected languages.");
                return;
            }
            if (targets.isEmpty()) {
                toastJs("Pick at least one language.");
                return;
            }

            busy = true;
            final Uri uri = picked;
            new Thread(new Runnable() {
                public void run() {
                    try {
                        final List<DubbingEngine.Result> results = DubbingEngine.run(
                                MainActivity.this, uri, targets, speaker, tone, volume,
                                cloneVoice, settings,
                                new DubbingEngine.Listener() {
                                    public void onProgress(final String stage, final int pct,
                                                           final String msg) {
                                        runOnUiThread(new Runnable() {
                                            public void run() {
                                                try {
                                                    JSONObject o = new JSONObject();
                                                    o.put("stage", stage);
                                                    o.put("pct", pct);
                                                    o.put("msg", msg);
                                                    js("window.onDubProgress(" + o.toString() + ")");
                                                } catch (Exception ignored) {
                                                }
                                            }
                                        });
                                    }
                                });

                        JSONArray arr = new JSONArray();
                        for (DubbingEngine.Result r : results) {
                            JSONObject o = new JSONObject();
                            o.put("lang", r.lang);
                            o.put("name", r.name);
                            o.put("displayName", r.displayName);
                            o.put("translated", r.translated);
                            arr.put(o);
                        }
                        final String payload = arr.toString();
                        runOnUiThread(new Runnable() {
                            public void run() {
                                js("window.onDubDone(" + JSONObject.quote(payload) + ")");
                            }
                        });
                    } catch (final Exception e) {
                        final String m = e.getMessage() == null
                                ? e.getClass().getSimpleName() : e.getMessage();
                        runOnUiThread(new Runnable() {
                            public void run() {
                                js("window.onDubError(" + JSONObject.quote(m) + ")");
                            }
                        });
                    } finally {
                        busy = false;
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public void play(final String fileName) {
            runOnUiThread(new Runnable() {
                public void run() {
                    File dir = new File(getExternalFilesDir(null), "out");
                    File f = new File(dir, fileName);
                    if (!f.exists()) return;
                    Uri u = FileProviderLite.uriFor(fileName);
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(u, "video/mp4");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(i);
                    } catch (Exception e) {
                        try {
                            Intent s = new Intent(Intent.ACTION_SEND);
                            s.setType("video/mp4");
                            s.putExtra(Intent.EXTRA_STREAM, u);
                            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(Intent.createChooser(s, "Open dubbed video"));
                        } catch (Exception ignored) {
                        }
                    }
                }
            });
        }

        @JavascriptInterface
        public void share(final String fileName) {
            runOnUiThread(new Runnable() {
                public void run() {
                    File dir = new File(getExternalFilesDir(null), "out");
                    File f = new File(dir, fileName);
                    if (!f.exists()) return;
                    Uri u = FileProviderLite.uriFor(fileName);
                    Intent s = new Intent(Intent.ACTION_SEND);
                    s.setType("video/mp4");
                    s.putExtra(Intent.EXTRA_STREAM, u);
                    s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(s, "Share dubbed video"));
                }
            });
        }

        private void toastJs(final String msg) {
            runOnUiThread(new Runnable() {
                public void run() {
                    js("window.onDubError(" + JSONObject.quote(msg) + ")");
                }
            });
        }
    }
}
