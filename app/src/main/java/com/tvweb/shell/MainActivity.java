package com.tvweb.shell;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Android TV 网页壳：全屏 WebView + 遥控器虚拟鼠标。
 *
 * 遥控器映射：
 *   方向键  -> 移动光标（按住加速），光标贴屏幕边缘时页面自动翻页滚动
 *   OK 键   -> 在光标位置模拟一次点击（可点到网页和右上角悬浮按钮）
 *   返回键  -> 网页可后退则后退，否则连按两次退出
 *   音量键  -> 交给系统
 */
public class MainActivity extends Activity {

    private static final String PREFS = "tvweb";
    private static final String KEY_URL = "url";
    /** 首次运行默认打开的网址，可在应用内「设置」里改，或用 adb 传 url 参数覆盖 */
    private static final String DEFAULT_URL = "https://www.baidu.com";

    private FrameLayout root;
    private WebView web;
    private CursorView cursor;

    private LinearLayout panel;
    private EditText urlEdit;
    private TextView diagView;

    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    private String diag = "尚未检测";
    private long lastBackAt = 0L;

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        try {
            web = new WebView(this);
        } catch (Throwable t) {
            // 少数精简 ROM 没有 WebView 组件，这里给出提示而不是直接崩
            showFatal("系统缺少 WebView 组件：" + t.getClass().getSimpleName()
                    + "\n请安装 Android System WebView 后重试");
            return;
        }
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cursor = new CursorView(this);
        root.addView(cursor, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        addToolbarButton("设置", 0, () -> showSettings());
        addToolbarButton("刷新", 1, () -> web.reload());

        setContentView(root);

        configureWebView();
        load(resolveStartUrl(getIntent()));

        Toast.makeText(this, "方向键移动光标 · OK 键点击 · 返回键后退", Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = intent.getStringExtra("url");
        if (url != null && url.length() > 0) {
            load(normalize(url));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) {
            web.onPause();
            web.pauseTimers();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.resumeTimers();
            web.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- WebView

    private void configureWebView() {
        WebView.setWebContentsDebuggingEnabled(true); // 可用 chrome://inspect 排查播放问题

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setAllowFileAccess(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false); // 直播自动起播
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " TVShell/1.0");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false; // 站内跳转全部留在壳里
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                probeCapability();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // 只处理主文档失败，子资源（广告、埋点）失败不能把页面顶掉
                if (request != null && request.isForMainFrame()) {
                    showOffline(error == null ? null : String.valueOf(error.getDescription()));
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                cursor.bringToFront();
                enterImmersive();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) {
                    return;
                }
                root.removeView(customView);
                customView = null;
                if (customCallback != null) {
                    customCallback.onCustomViewHidden();
                    customCallback = null;
                }
                web.setVisibility(View.VISIBLE);
            }
        });
    }

    private void showFatal(String msg) {
        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextColor(0xFFE8EAED);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(48), dp(48), dp(48), dp(48));
        root.addView(tv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    /** 读取 WebView 的播放能力，结果在「设置」面板里展示，方便判断直播为什么播不出来。 */
    private void probeCapability() {
        String js = "(function(){try{var v=document.createElement('video');"
                + "return JSON.stringify({mse:!!window.MediaSource,"
                + "hls:(v.canPlayType('application/vnd.apple.mpegurl')||'-'),"
                + "h264:(v.canPlayType('video/mp4; codecs=\"avc1.42E01E\"')||'-'),"
                + "hevc:(v.canPlayType('video/mp4; codecs=\"hvc1.1.6.L93.B0\"')||'-'),"
                + "flv:!!window.MediaSource});}catch(e){return '检测失败:'+e}})()";
        web.evaluateJavascript(js, value -> {
            if (value != null) {
                diag = value.replace("\"", "").replace("\\", "");
            }
        });
    }

    private void showOffline(String reason) {
        String html = "<html><head><meta name='viewport' content='width=device-width'>"
                + "<style>body{background:#111;color:#eee;font-family:sans-serif;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center}"
                + "</style></head><body><div><h2>网页加载失败</h2><p style='color:#999'>"
                + (reason == null ? "" : reason) + "</p><p style='color:#666'>检查网络后按「刷新」重试</p></div></body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    // ---------------------------------------------------------------- 遥控器

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        int action = e.getAction();

        // 设置面板打开时，方向键/OK 交给系统焦点处理（编辑框需要输入法）
        if (panel != null) {
            if (code == KeyEvent.KEYCODE_BACK && action == KeyEvent.ACTION_UP) {
                hideSettings();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        if (action == KeyEvent.ACTION_DOWN) {
            float base = dp(11);
            float gain = Math.min(e.getRepeatCount(), 10) * dp(9);
            float step = base + gain;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    cursor.moveBy(0, -step);
                    edgeScroll();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    cursor.moveBy(0, step);
                    edgeScroll();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    cursor.moveBy(-step, 0);
                    edgeScroll();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    cursor.moveBy(step, 0);
                    edgeScroll();
                    return true;
                default:
                    break;
            }
        }

        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (action == KeyEvent.ACTION_UP) {
                tapAtCursor();
            }
            return true;
        }

        if (code == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (web.canGoBack()) {
                    web.goBack();
                    return true;
                }
                long now = SystemClock.uptimeMillis();
                if (now - lastBackAt < 2000) {
                    finish();
                } else {
                    lastBackAt = now;
                    Toast.makeText(this, "再按一次返回键退出", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            return true;
        }

        if (code == KeyEvent.KEYCODE_MENU && action == KeyEvent.ACTION_UP) {
            showSettings();
            return true;
        }

        return super.dispatchKeyEvent(e);
    }

    /** 把光标位置翻译成一次真实触摸，派发给整棵树（网页 / 悬浮按钮都能命中）。 */
    private void tapAtCursor() {
        float x = cursor.cx();
        float y = cursor.cy();
        if (x < 0 || y < 0) {
            return;
        }
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        root.dispatchTouchEvent(down);
        down.recycle();

        MotionEvent up = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        root.dispatchTouchEvent(up);
        up.recycle();

        cursor.pulse();
    }

    /** 光标贴边时滚动页面：优先滚动光标下方的可滚动容器，其次滚动整个文档。 */
    private void edgeScroll() {
        int w = web.getWidth();
        int h = web.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float x = cursor.cx();
        float y = cursor.cy();
        int edge = dp(34);
        int step = dp(48);
        float dx = 0;
        float dy = 0;
        if (y > h - edge) {
            dy = step;
        } else if (y < edge) {
            dy = -step;
        }
        if (x > w - edge) {
            dx = step;
        } else if (x < edge) {
            dx = -step;
        }
        if (dx == 0 && dy == 0) {
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        String js = "(function(){var dx=" + (int) (dx / d) + ",dy=" + (int) (dy / d) + ";"
                + "var e=document.elementFromPoint(" + (int) (x / d) + "," + (int) (y / d) + ");"
                + "while(e&&e!==document.body&&e!==document.documentElement){"
                + "var sy=e.scrollHeight>e.clientHeight+4,sx=e.scrollWidth>e.clientWidth+4;"
                + "if((dy&&sy)||(dx&&sx)){e.scrollTop+=dy;e.scrollLeft+=dx;return;}"
                + "e=e.parentElement;}window.scrollBy(dx,dy);})()";
        web.evaluateJavascript(js, null);
    }

    // ---------------------------------------------------------------- 设置面板

    private void addToolbarButton(String label, int index, final Runnable onClick) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xCCFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setBackground(rounded(0x66000000, dp(6)));
        tv.setAlpha(0.55f);
        tv.setClickable(true);
        tv.setFocusable(false);
        tv.setOnClickListener(v -> onClick.run());

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        lp.topMargin = dp(10);
        lp.rightMargin = dp(10) + index * dp(64);
        root.addView(tv, lp);
    }

    private void showSettings() {
        if (panel != null) {
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(20), dp(24), dp(20));
        box.setBackground(rounded(0xF0181A1F, dp(12)));

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        box.addView(title);

        TextView hint = new TextView(this);
        hint.setText("网址（留空则使用默认地址）。改完点「保存并加载」。");
        hint.setTextColor(0xFF9AA0A6);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(8);
        box.addView(hint, hlp);

        urlEdit = new EditText(this);
        urlEdit.setSingleLine(true);
        urlEdit.setText(prefs().getString(KEY_URL, DEFAULT_URL));
        urlEdit.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlEdit.setTextColor(0xFFFFFFFF);
        urlEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        urlEdit.setHint("https://example.com/live");
        urlEdit.setBackground(rounded(0xFF2A2E35, dp(8)));
        urlEdit.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(10);
        box.addView(urlEdit, elp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(14);
        box.addView(row, rlp);

        row.addView(button("输入网址", 0, () -> {
            urlEdit.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(urlEdit, InputMethodManager.SHOW_FORCED);
            }
        }), buttonLp(1));

        row.addView(button("保存并加载", 1, () -> {
            String u = normalize(urlEdit.getText().toString().trim());
            prefs().edit().putString(KEY_URL, u).apply();
            hideSettings();
            load(u);
        }), buttonLp(1));

        row.addView(button("取消", 2, this::hideSettings), buttonLp(1));

        diagView = new TextView(this);
        diagView.setText("WebView 能力检测：" + diag);
        diagView.setTextColor(0xFF7F8792);
        diagView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(14);
        box.addView(diagView, dlp);

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                dp(680), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(box, blp);
        cursor.bringToFront();
        panel = box;

        box.post(() -> urlEdit.requestFocus());
    }

    private void hideSettings() {
        if (panel != null) {
            root.removeView(panel);
            panel = null;
            urlEdit = null;
            diagView = null;
            web.requestFocus();
        }
    }

    private Button button(String label, int index, final Runnable onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(buttonTextColor());
        b.setBackground(focusableBg());
        b.setPadding(dp(8), dp(10), dp(8), dp(10));
        b.setMinHeight(dp(44));
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private LinearLayout.LayoutParams buttonLp(int weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.rightMargin = dp(8);
        return lp;
    }

    // ---------------------------------------------------------------- 杂项

    private String resolveStartUrl(Intent intent) {
        String fromIntent = intent == null ? null : intent.getStringExtra("url");
        if (fromIntent != null && fromIntent.length() > 0) {
            return normalize(fromIntent);
        }
        String saved = prefs().getString(KEY_URL, "");
        return normalize(saved.length() > 0 ? saved : DEFAULT_URL);
    }

    private void load(String url) {
        if (url == null || url.length() == 0) {
            url = DEFAULT_URL;
        }
        web.loadUrl(url);
    }

    private String normalize(String raw) {
        if (raw == null) {
            return DEFAULT_URL;
        }
        String u = raw.trim();
        if (u.length() == 0) {
            return DEFAULT_URL;
        }
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://" + u;
        }
        return u;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private StateListDrawable focusableBg() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, rounded(0xFFFFFFFF, dp(8)));
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(0xFFD0D4DA, dp(8)));
        s.addState(new int[]{}, rounded(0xFF2A2E35, dp(8)));
        return s;
    }

    private android.content.res.ColorStateList buttonTextColor() {
        return new android.content.res.ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_focused},
                        new int[]{}
                },
                new int[]{0xFF111111, 0xFFEDEFF2});
    }
}
