package ao.clube.leitura;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final String START = "https://appassets.androidplatform.net/assets/index.html";
    private static final int PICK_FILE = 41;
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Escolher ficheiro"), PICK_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_FILE && fileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) result = new Uri[]{data.getData()};
            fileCallback.onReceiveValue(result);
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript(
                "(function(){var m=document.querySelectorAll('.modal');if(m.length){m[m.length-1].remove();return 'x'}return ''})()",
                value -> {
                    if (value == null || !value.contains("x")) {
                        if (web.canGoBack()) web.goBack(); else finish();
                    }
                });
    }

    private class Bridge {
        @JavascriptInterface
        public boolean hasUsageAccess() {
            try {
                AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
                int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
                return mode == AppOpsManager.MODE_ALLOWED;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public void openUsageSettings() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
                catch (Exception e) { Toast.makeText(MainActivity.this, "Abra Definições › Acesso ao uso", Toast.LENGTH_LONG).show(); }
            });
        }

        @JavascriptInterface
        public String screenTime(int days) {
            JSONArray out = new JSONArray();
            try {
                UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
                PackageManager pm = getPackageManager();
                String self = getPackageName();
                Intent home = new Intent(Intent.ACTION_MAIN); home.addCategory(Intent.CATEGORY_HOME);
                String launcher = "";
                try { launcher = pm.resolveActivity(home, 0).activityInfo.packageName; } catch (Exception ignored) { }
                Calendar c = Calendar.getInstance();
                c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
                for (int i = days - 1; i >= 0; i--) {
                    Calendar d = (Calendar) c.clone(); d.add(Calendar.DAY_OF_MONTH, -i);
                    long start = d.getTimeInMillis(), end = start + 86400000L;
                    if (end > System.currentTimeMillis()) end = System.currentTimeMillis();
                    Map<String, UsageStats> map = usm.queryAndAggregateUsageStats(start, end);
                    long total = 0; JSONArray apps = new JSONArray();
                    java.util.ArrayList<long[]> list = new java.util.ArrayList<>();
                    java.util.ArrayList<String> names = new java.util.ArrayList<>();
                    for (Map.Entry<String, UsageStats> e : map.entrySet()) {
                        String p = e.getKey();
                        long t = e.getValue().getTotalTimeInForeground();
                        if (t < 60000 || p.equals(launcher) || p.startsWith("com.android.systemui")) continue;
                        total += t;
                        String label = p;
                        try { ApplicationInfo ai = pm.getApplicationInfo(p, 0); label = pm.getApplicationLabel(ai).toString(); } catch (Exception ignored) { }
                        names.add(p.equals(self) ? "Clube de Leitura" : label); list.add(new long[]{t});
                    }
                    Integer[] idx = new Integer[list.size()];
                    for (int k = 0; k < idx.length; k++) idx[k] = k;
                    java.util.Arrays.sort(idx, (a, b) -> Long.compare(list.get(b)[0], list.get(a)[0]));
                    for (int k = 0; k < Math.min(8, idx.length); k++) {
                        JSONObject o = new JSONObject();
                        o.put("n", names.get(idx[k])); o.put("min", Math.round(list.get(idx[k])[0] / 60000.0));
                        apps.put(o);
                    }
                    JSONObject day = new JSONObject();
                    day.put("date", String.format(java.util.Locale.US, "%04d-%02d-%02d", d.get(Calendar.YEAR), d.get(Calendar.MONTH) + 1, d.get(Calendar.DAY_OF_MONTH)));
                    day.put("min", Math.round(total / 60000.0));
                    day.put("apps", apps);
                    out.put(day);
                }
            } catch (Exception ignored) { }
            return out.toString();
        }


        @JavascriptInterface
        public void setAlarm(int hour, int minutes, String label, String days) {
            Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
            i.putExtra(AlarmClock.EXTRA_HOUR, hour);
            i.putExtra(AlarmClock.EXTRA_MINUTES, minutes);
            i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
            i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
            if (days != null && !days.trim().isEmpty()) {
                ArrayList<Integer> list = new ArrayList<>();
                for (String d : days.split(",")) {
                    try { list.add(Integer.parseInt(d.trim())); } catch (Exception ignored) { }
                }
                if (!list.isEmpty()) i.putExtra(AlarmClock.EXTRA_DAYS, list);
            }
            runOnUiThread(() -> {
                try {
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Não encontrei a aplicação de relógio", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void save(String name, String base64, String mime) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                // 1) cópia nas Transferências (Android 10 ou mais recente)
                boolean savedPublic = false;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Clube de Leitura");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri != null) {
                        try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(bytes); }
                        savedPublic = true;
                    }
                }
                // 2) cópia interna para partilhar (WhatsApp, e-mail, etc.)
                File dir = new File(getCacheDir(), "partilha");
                dir.mkdirs();
                File f = new File(dir, name);
                try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(bytes); }
                Uri share = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                final boolean pub = savedPublic;
                runOnUiThread(() -> {
                    if (pub) Toast.makeText(MainActivity.this, "Guardado em Transferências › Clube de Leitura", Toast.LENGTH_LONG).show();
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType(mime);
                    send.putExtra(Intent.EXTRA_STREAM, share);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Partilhar " + name));
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Não foi possível guardar o ficheiro", Toast.LENGTH_LONG).show());
            }
        }
    }
}
