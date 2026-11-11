package com.tvweb.shell;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 检查 GitHub Releases 上的新版本，并把 APK 下载到缓存目录。
 * 只用 HttpURLConnection + org.json，不引入任何依赖。
 */
public class UpdateManager {

    /** 发布仓库，改成你自己的仓库时只动这一行 */
    private static final String LATEST_API =
            "https://api.github.com/repos/Hyde-BoA/tvweb-shell/releases/latest";

    private final Handler ui = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onCheck(Release release, String error);

        void onProgress(int percent);

        void onDownload(File apk, String error);
    }

    public static class Release {
        public String tag = "";
        public String notes = "";
        public String apkUrl = "";

        public boolean isNewerThan(String currentVersion) {
            return compare(tag, currentVersion) > 0;
        }
    }

    // ------------------------------------------------------------ 检查更新

    public void check(final Callback cb) {
        new Thread(() -> {
            Release release = null;
            String error = null;
            HttpURLConnection conn = null;
            try {
                conn = open(LATEST_API);
                int code = conn.getResponseCode();
                if (code == 403) {
                    error = "GitHub 接口限流（HTTP 403），稍后再试";
                } else if (code != 200) {
                    error = "HTTP " + code;
                } else {
                    String body = readAll(conn.getInputStream());
                    JSONObject root = new JSONObject(body);
                    Release r = new Release();
                    r.tag = root.optString("tag_name", "");
                    r.notes = root.optString("body", "");
                    JSONArray assets = root.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            String name = a.optString("name", "");
                            if (name.toLowerCase().endsWith(".apk")) {
                                r.apkUrl = a.optString("browser_download_url", "");
                                break;
                            }
                        }
                    }
                    if (r.tag.length() == 0) {
                        error = "返回内容缺少版本号";
                    } else if (r.apkUrl.length() == 0) {
                        error = "该版本没有上传 APK";
                    } else {
                        release = r;
                    }
                }
            } catch (Exception e) {
                error = e.getClass().getSimpleName();
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            final Release fr = release;
            final String fe = error;
            ui.post(() -> cb.onCheck(fr, fe));
        }, "update-check").start();
    }

    // ------------------------------------------------------------ 下载

    public void download(final String apkUrl, final File dest, final Callback cb) {
        new Thread(() -> {
            String error = null;
            HttpURLConnection conn = null;
            InputStream in = null;
            FileOutputStream out = null;
            try {
                conn = open(apkUrl);
                int code = conn.getResponseCode();
                if (code != 200) {
                    throw new IOException("HTTP " + code);
                }
                long total = conn.getContentLength();
                File parent = dest.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                in = conn.getInputStream();
                out = new FileOutputStream(dest);
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int lastPercent = -1;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        final int p = (int) (done * 100 / total);
                        if (p != lastPercent) {
                            lastPercent = p;
                            ui.post(() -> cb.onProgress(p));
                        }
                    }
                }
                out.flush();
                if (done == 0) {
                    error = "下载内容为空";
                }
            } catch (Exception e) {
                error = e.getClass().getSimpleName();
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
            } finally {
                close(out);
                close(in);
                if (conn != null) {
                    conn.disconnect();
                }
            }
            final String fe = error;
            ui.post(() -> cb.onDownload(fe == null ? dest : null, fe));
        }, "update-download").start();
    }

    // ------------------------------------------------------------ 工具

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "tvweb-shell-updater");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append('\n');
        }
        br.close();
        return sb.toString();
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    /** 比较 "v1.2.3" 与 "1.2"，前者大返回正数 */
    public static int compare(String a, String b) {
        int[] x = numbers(a);
        int[] y = numbers(b);
        int len = Math.max(x.length, y.length);
        for (int i = 0; i < len; i++) {
            int p = i < x.length ? x[i] : 0;
            int q = i < y.length ? y[i] : 0;
            if (p != q) {
                return p - q;
            }
        }
        return 0;
    }

    private static int[] numbers(String v) {
        if (v == null) {
            return new int[0];
        }
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        List<Integer> out = new ArrayList<>();
        String[] parts = s.split("\\.");
        for (String p : parts) {
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < p.length(); i++) {
                char ch = p.charAt(i);
                if (ch >= '0' && ch <= '9') {
                    digits.append(ch);
                } else {
                    break;
                }
            }
            out.add(digits.length() == 0 ? 0 : Integer.parseInt(digits.toString()));
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }
}
