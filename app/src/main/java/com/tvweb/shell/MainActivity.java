package com.tvweb.shell;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Android TV 网页壳：全屏 WebView + 遥控器虚拟鼠标。
 *
 * 遥控器映射：
 *   方向键  -> 移动光标（按住加速），光标贴屏幕边缘时页面自动翻页滚动
 *   OK 键   -> 在光标位置模拟一次点击（可点到网页和右上角悬浮按钮）
 *   返回键  -> 网页可后退则后退，否则连按两次退出
 */
public class MainActivity extends Activity {

    private static final String PREFS = "tvweb";
    private static final String KEY_URL = "url";
    private static final String KEY_HISTORY = "history";
    private static final String DEFAULT_URL = "https://www.baidu.com";
    private static final int HISTORY_LIMIT = 12;

    private FrameLayout root;
    private WebView web;
    private CursorView cursor;

    private ScrollView panelView;
    private LinearLayout panelBox;
    private EditText urlEdit;
    private LinearLayout historyBox;
    private TextView diagView;
    private TextView updateStatus;
    private Button updateBtn;
    private Button clearBtn;

    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    private String diag = "尚未检测";
    private String kernel = "检测中";
    private long lastBackAt = 0L;

    // 更新相关状态
    private final UpdateManager updater = new UpdateManager();
    private UpdateManager.Release foundRelease;
    private File pendingApk;
    private boolean busy;
    private long clearConfirmAt = 0L;

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
            showFatal("系统缺少 WebView 组件：" + t.getClass().getSimpleName()
                    + "\n请安装 Android System WebView 后重试");
            return;
        }
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cursor = new CursorView(this);
        root.addView(cursor, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        addToolbarButton("设置", 0, this::showSettings);
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
            String u = normalize(url);
            addHistory(u);
            load(u);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 只在这里恢复定时器：onPause 在弹音量条、系统面板时会触发，
        // 那时冻结 JS 会让播放器停止续填 buffer，回来就卡一下。
        if (web != null) {
            web.resumeTimers();
        }
    }

    @Override
    protected void onStop() {
        if (web != null) {
            web.pauseTimers();
        }
        super.onStop();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) {
            web.onPause(); // 仅暂停动画/定位，不冻结 JS
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
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

        // UA 伪装成普通 Chrome：去掉 "; wv" 和 "Version/4.0" 标记。
        // 很多直播站会嗅探 WebView 标记，给一份保守的播放策略（小 buffer / 降级播放器），
        // 表现就是同样的网络下比 Chrome 更容易缓冲。
        String ua = s.getUserAgentString();
        s.setUserAgentString(ua.replace("; wv)", ")").replace("Version/4.0 ", ""));

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        // 提高渲染进程优先级：默认策略在宿主失去焦点时会降级，TV 上很容易触发
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
        // 注意：这里刻意不调 setLayerType(LAYER_TYPE_HARDWARE)，那会给 WebView 加一层
        // 离屏缓冲，视频要多合成一次，弱芯片上反而更卡。硬件加速由 manifest 的
        // android:hardwareAccelerated="true" 保证。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            s.setOffscreenPreRaster(true);
        }

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

    /** 读取 WebView 的播放能力与内核版本，结果在「设置」面板里展示。 */
    private void probeCapability() {
        String js = "(function(){try{var v=document.createElement('video');"
                + "function t(x){return v.canPlayType(x)||'-';}"
                + "return [(!!window.MediaSource)?'Y':'N',t('application/vnd.apple.mpegurl'),"
                + "t('video/mp4; codecs=\"avc1.42E01E\"'),t('video/mp4; codecs=\"hvc1.1.6.L93.B0\"'),"
                + "navigator.userAgent].join('|');}catch(e){return 'ERR|'+e}})()";
        web.evaluateJavascript(js, value -> {
            if (value == null) {
                return;
            }
            String raw = value.trim();
            if (raw.length() > 1 && raw.startsWith("\"") && raw.endsWith("\"")) {
                raw = raw.substring(1, raw.length() - 1);
            }
            raw = raw.replace("\\\"", "\"");
            String[] p = raw.split("\\|", -1);
            if (p.length < 5) {
                diag = "检测失败";
                return;
            }
            Matcher m = Pattern.compile("Chrome/([0-9]+)").matcher(p[4]);
            kernel = m.find() ? "Chrome/" + m.group(1) : "未知";

            StringBuilder sb = new StringBuilder();
            sb.append("MSE ").append("Y".equals(p[0]) ? "有" : "无");
            sb.append(" · H264 ").append(shortType(p[2]));
            sb.append(" · HEVC ").append(shortType(p[3]));
            sb.append(" · HLS ").append(shortType(p[1]));
            diag = sb.toString();

            if (diagView != null) {
                diagView.setText("内核 " + kernel + " · " + diag);
            }
        });
    }

    private static String shortType(String v) {
        if (v == null || v.isEmpty() || "-".equals(v)) {
            return "×";
        }
        if (v.startsWith("probably")) {
            return "✓";
        }
        if (v.startsWith("maybe")) {
            return "~";
        }
        return v;
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

        // 设置面板打开时，方向键/OK 交给系统焦点处理（编辑框、历史列表需要常规导航）
        if (panelView != null) {
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
        if (panelView != null) {
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(20), dp(24), dp(20));
        box.setBackground(rounded(0xF0181A1F, dp(12)));

        // 标题 + 版本
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView ver = new TextView(this);
        ver.setText("v" + versionName());
        ver.setTextColor(0xFF6B7280);
        ver.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        head.addView(ver);
        box.addView(head);

        box.addView(label("网址（留空则用默认地址）"));

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
        elp.topMargin = dp(8);
        box.addView(urlEdit, elp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(12);
        box.addView(row, rlp);

        row.addView(button("输入网址", () -> {
            urlEdit.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(urlEdit, InputMethodManager.SHOW_FORCED);
            }
        }), buttonLp());

        row.addView(button("保存并加载", () -> {
            String u = normalize(urlEdit.getText().toString().trim());
            prefs().edit().putString(KEY_URL, u).apply();
            addHistory(u);
            hideSettings();
            load(u);
        }), buttonLp());

        row.addView(button("取消", this::hideSettings), buttonLp());

        // ---- 历史记录
        box.addView(label("历史网址（选中直接打开）"));
        historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(historyBox);

        clearBtn = button("清空历史", () -> {
            long now = SystemClock.uptimeMillis();
            if (now - clearConfirmAt < 4000) {
                clearConfirmAt = 0;
                saveHistory(new ArrayList<>());
                refreshHistory();
                clearBtn.setText("清空历史");
            } else {
                clearConfirmAt = now;
                clearBtn.setText("再点一次确认清空");
                clearBtn.postDelayed(() -> {
                    if (clearConfirmAt != 0 && SystemClock.uptimeMillis() - clearConfirmAt >= 4000) {
                        clearConfirmAt = 0;
                        if (clearBtn != null) {
                            clearBtn.setText("清空历史");
                        }
                    }
                }, 4100);
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        box.addView(clearBtn, clp);
        refreshHistory(); // 放在 clearBtn 创建之后，空列表时才能把按钮隐藏掉

        box.addView(divider());

        // ---- 检查更新
        LinearLayout updateRow = new LinearLayout(this);
        updateRow.setOrientation(LinearLayout.HORIZONTAL);
        updateRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.topMargin = dp(12);
        box.addView(updateRow, ulp);

        updateBtn = button("检查更新", this::onUpdateButton);
        updateRow.addView(updateBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        updateStatus = new TextView(this);
        updateStatus.setText("当前版本 v" + versionName());
        updateStatus.setTextColor(0xFF9AA0A6);
        updateStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = dp(12);
        updateRow.addView(updateStatus, slp);

        diagView = new TextView(this);
        diagView.setText("内核 " + kernel + " · " + diag);
        diagView.setTextColor(0xFF6B7280);
        diagView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(12);
        box.addView(diagView, dlp);

        // 包一层 ScrollView，内容多了也不会超出屏幕
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int panelWidth = dp(720);
        box.measure(View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.88f);
        int panelHeight = Math.min(box.getMeasuredHeight(), maxHeight);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                panelWidth, panelHeight, Gravity.CENTER);
        root.addView(sv, plp);
        cursor.bringToFront();
        panelView = sv;
        panelBox = box;

        box.post(() -> {
            if (urlEdit != null) {
                urlEdit.requestFocus();
            }
        });
    }

    private void hideSettings() {
        if (panelView != null) {
            root.removeView(panelView);
            panelView = null;
            panelBox = null;
            urlEdit = null;
            diagView = null;
            updateStatus = null;
            updateBtn = null;
            clearBtn = null;
            historyBox = null;
            web.requestFocus();
        }
    }

    // ---------------------------------------------------------------- 历史记录

    private List<String> getHistory() {
        String raw = prefs().getString(KEY_HISTORY, "");
        List<String> out = new ArrayList<>();
        if (raw == null || raw.length() == 0) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (s.length() > 0) {
                out.add(s);
            }
        }
        return out;
    }

    private void saveHistory(List<String> list) {
        prefs().edit().putString(KEY_HISTORY, TextUtils.join("\n", list)).apply();
    }

    private void addHistory(String url) {
        if (url == null || url.length() == 0) {
            return;
        }
        List<String> list = getHistory();
        list.remove(url);
        list.add(0, url);
        while (list.size() > HISTORY_LIMIT) {
            list.remove(list.size() - 1);
        }
        saveHistory(list);
        if (historyBox != null) {
            refreshHistory();
        }
    }

    private void removeHistory(String url) {
        List<String> list = getHistory();
        list.remove(url);
        saveHistory(list);
        refreshHistory();
    }

    private void refreshHistory() {
        if (historyBox == null) {
            return;
        }
        historyBox.removeAllViews();
        List<String> list = getHistory();
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有历史记录，保存并加载一个网址后会出现在这里");
            empty.setTextColor(0xFF6B7280);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            empty.setPadding(dp(4), dp(10), dp(4), dp(10));
            historyBox.addView(empty);
            if (clearBtn != null) {
                clearBtn.setVisibility(View.GONE);
            }
            return;
        }
        if (clearBtn != null) {
            clearBtn.setVisibility(View.VISIBLE);
        }

        for (String url : list) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);

            TextView link = new TextView(this);
            link.setText(url);
            link.setSingleLine(true);
            link.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            link.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            link.setTextColor(buttonTextColor());
            link.setBackground(focusableBg());
            link.setPadding(dp(12), dp(10), dp(12), dp(10));
            link.setFocusable(true);
            link.setClickable(true);
            link.setOnClickListener(v -> {
                String u = normalize(url);
                prefs().edit().putString(KEY_URL, u).apply();
                addHistory(u);
                hideSettings();
                load(u);
            });
            item.addView(link, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView del = new TextView(this);
            del.setText("×");
            del.setGravity(Gravity.CENTER);
            del.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            del.setTextColor(buttonTextColor());
            del.setBackground(focusableBg());
            del.setFocusable(true);
            del.setClickable(true);
            del.setOnClickListener(v -> removeHistory(url));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(40), dp(40));
            dlp.leftMargin = dp(6);
            item.addView(del, dlp);

            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ilp.topMargin = dp(6);
            historyBox.addView(item, ilp);
        }
    }

    // ---------------------------------------------------------------- 检查更新

    private void onUpdateButton() {
        if (busy) {
            return;
        }
        if (pendingApk != null) {
            installApk(pendingApk);
            return;
        }
        if (foundRelease != null) {
            startDownload();
            return;
        }
        startCheck();
    }

    private void startCheck() {
        busy = true;
        setUpdateButton("检查中…", false);
        setStatus("正在检查 GitHub 上的最新版本…");

        updater.check(new UpdateManager.Callback() {
            @Override
            public void onCheck(UpdateManager.Release release, String error) {
                busy = false;
                if (error != null) {
                    setUpdateButton("重试", true);
                    setStatus("检查失败：" + error + "（电视可能无法访问 GitHub）");
                    return;
                }
                if (release == null) {
                    setUpdateButton("检查更新", true);
                    setStatus("没有找到可用的发布版本");
                    return;
                }
                if (!release.isNewerThan(versionName())) {
                    setUpdateButton("已是最新", false);
                    setStatus("当前 v" + versionName() + " 已是最新版本");
                    return;
                }
                foundRelease = release;
                setUpdateButton("下载并安装", true);
                setStatus("发现 " + release.tag + "，点「下载并安装」升级");
            }

            @Override
            public void onProgress(int percent) {
                setStatus("正在下载 " + percent + "%");
            }

            @Override
            public void onDownload(File apk, String error) {
                busy = false;
                if (error != null) {
                    setUpdateButton("重试下载", true);
                    setStatus("下载失败：" + error);
                    return;
                }
                pendingApk = apk;
                setUpdateButton("立即安装", true);
                setStatus("下载完成，正在调起系统安装程序…");
                installApk(apk);
            }
        });
    }

    private void startDownload() {
        busy = true;
        setUpdateButton("下载中…", false);
        setStatus("开始下载…");
        File dest = new File(getCacheDir(), UpdateFileProvider.NAME);
        updater.download(foundRelease.apkUrl, dest, new UpdateManager.Callback() {
            @Override
            public void onCheck(UpdateManager.Release release, String error) {
                // 不使用
            }

            @Override
            public void onProgress(int percent) {
                setStatus("正在下载 " + percent + "%");
            }

            @Override
            public void onDownload(File apk, String error) {
                busy = false;
                if (error != null) {
                    setUpdateButton("重试下载", true);
                    setStatus("下载失败：" + error);
                    return;
                }
                pendingApk = apk;
                setUpdateButton("立即安装", true);
                setStatus("下载完成，正在调起系统安装程序…");
                installApk(apk);
            }
        });
    }

    private void installApk(File apk) {
        if (apk == null || !apk.exists()) {
            setStatus("安装包不存在，请重新下载");
            return;
        }
        if (!looksLikeZip(apk)) {
            // 常见情况：下载到的是登录页/错误页而不是 APK
            setStatus("文件不是有效的安装包，请重试下载");
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
            pendingApk = null;
            setUpdateButton("重试下载", true);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            setStatus("需要先允许「安装未知应用」，已打开设置页");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                setStatus("请到系统设置里允许本应用安装未知应用");
            }
            return;
        }
        Uri uri = UpdateFileProvider.uriFor(this);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // 部分国产 ROM 的系统安装器只认 ClipData 里的 URI 授权
        intent.setClipData(android.content.ClipData.newRawUri("apk", uri));
        try {
            startActivity(intent);
        } catch (Exception e) {
            setStatus("无法启动安装程序：" + e.getClass().getSimpleName());
        }
    }

    /** APK 本质是 zip，头两个字节是 PK */
    private boolean looksLikeZip(File f) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            int a = in.read();
            int b = in.read();
            return a == 'P' && b == 'K';
        } catch (Exception e) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }

    private void setStatus(String text) {
        if (updateStatus != null) {
            updateStatus.setText(text);
        }
    }

    private void setUpdateButton(String text, boolean enabled) {
        if (updateBtn != null) {
            updateBtn.setText(text);
            updateBtn.setEnabled(enabled);
            updateBtn.setAlpha(enabled ? 1f : 0.6f);
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    // ---------------------------------------------------------------- 控件工厂

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF9AA0A6);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(0, dp(14), 0, 0);
        return tv;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0x1AFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(16);
        v.setLayoutParams(lp);
        return v;
    }

    private Button button(String text, final Runnable onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(buttonTextColor());
        b.setBackground(focusableBg());
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setMinHeight(dp(44));
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
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

    private ColorStateList buttonTextColor() {
        return new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_focused},
                        new int[]{}
                },
                new int[]{0xFF111111, 0xFFEDEFF2});
    }
}
