package tv.home.hometv;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/** Full-screen WebView showing the Home TV interface served by {@link LocalServer}. */
public class MainActivity extends Activity {
    private static final int PORT = 8765;
    private static LocalServer server;
    private WebView web;
    private long lastBack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (server == null) {
            server = new LocalServer(getApplicationContext(), PORT);
            server.start();
        }

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(12, 16, 22));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        setContentView(web);
        web.requestFocus();
        web.loadUrl("http://127.0.0.1:" + PORT + "/");
    }

    private void js(String code, ValueCallback<String> cb) {
        if (web != null) web.evaluateJavascript(code, cb);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean up = e.getAction() == KeyEvent.ACTION_UP;
        switch (k) {
            case KeyEvent.KEYCODE_BACK:
                if (up) handleBack();
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (up) js("window.hometvMenu&&hometvMenu()", null);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (up) js("window.hometvZap&&hometvZap(-1)", null);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (up) js("window.hometvZap&&hometvZap(1)", null);
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void handleBack() {
        js("(window.hometvBack?hometvBack():false)", new ValueCallback<String>() {
            @Override public void onReceiveValue(String v) {
                if ("true".equals(v)) return;
                if (web != null && web.canGoBack() && !String.valueOf(web.getUrl()).endsWith(":" + PORT + "/")) {
                    web.goBack();
                    return;
                }
                long now = System.currentTimeMillis();
                if (now - lastBack < 2500) {
                    finish();
                } else {
                    lastBack = now;
                    Toast.makeText(MainActivity.this, "Press Back again to exit", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop playback when the user presses Home or switches apps.
        js("window.hometvPause&&hometvPause()", null);
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
