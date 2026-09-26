package com.aliya.hy_vq;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 实用软件分享（v2.9.0）。
 *
 * <p><b>数据源</b>：123 云盘 WebDAV 的「HY_VQ软件分享」目录，用<b>只读</b>凭据访问
 * （与更新源同一账号的不同密码，泄漏也无法写入）。分类与文件都由用户在 123 网盘侧
 * 自行上传整理，App 不做任何写操作。</p>
 *
 * <p><b>为什么用 OkHttp</b>：WebDAV 列目录要发 {@code PROPFIND}，而
 * {@code HttpURLConnection.setRequestMethod()} 只放行 GET/POST/HEAD/OPTIONS/PUT/
 * DELETE/TRACE，自定义方法会抛 {@code ProtocolException}。项目已声明 okhttp 4.12，
 * 这里首次使用（{@code Request.Builder.method()} 支持任意方法）。</p>
 *
 * <p><b>实测基线</b>（2026-09-26）：只读凭据对该目录 {@code PROPFIND Depth:1} 返回
 * <b>207 Multistatus</b>，10 个中文分类全部可列；中文用 URL 编码传输，展示前需解码。</p>
 */
public class ShareAppsActivity extends Activity {

    /** 123 云盘 WebDAV 根 + 分享目录（中文路径由 HttpUrl 自动编码） */
    private static final String BASE = "https://webdav.123pan.cn/webdav/HY_VQ软件分享/";
    /** 只读账密（与 MainActivity.CN_CRED_SEED 一致：同一账号的只读密码） */
    private static final String CN_CRED_SEED = "15823710155:b18aqw8r";

    private static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<d:propfind xmlns:d=\"DAV:\"><d:prop>"
                    + "<d:displayname/><d:resourcetype/><d:getcontentlength/>"
                    + "</d:prop></d:propfind>";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private OkHttpClient client;
    private String cred;

    private LinearLayout listBox;
    private TextView titleView;
    private TextView stateView;
    private TextView crumbView;
    private ProgressBar bar;

    /** 当前所在分类（null = 分类根） */
    private String curCategory = null;
    private boolean loading = false;

    /** 一个 WebDAV 条目 */
    private static final class Item {
        String name;
        String href;
        long size;
        boolean dir;

        String display() {
            if (name != null && !name.isEmpty()) return name;
            if (href == null) return "";
            String h = href;
            while (h.endsWith("/") && h.length() > 1) h = h.substring(0, h.length() - 1);
            int i = h.lastIndexOf('/');
            String seg = i < 0 ? h : h.substring(i + 1);
            try {
                return URLDecoder.decode(seg, "UTF-8");
            } catch (Throwable t) {
                return seg;
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share_apps);

        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        cred = Credentials.basic("15823710155", "b18aqw8r");

        listBox = findViewById(R.id.share_list);
        titleView = findViewById(R.id.share_title);
        stateView = findViewById(R.id.share_state);
        crumbView = findViewById(R.id.share_crumb);
        bar = findViewById(R.id.share_bar);

        findViewById(R.id.share_back).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.share_refresh).setOnClickListener(v -> load());
        // 点空白处也刷新一次（无网络时方便重试）
        stateView.setOnClickListener(v -> load());

        load();
    }

    @Override
    public void onBackPressed() {
        // 分类内 → 先回分类根；分类根 → 退出
        if (curCategory != null) {
            curCategory = null;
            load();
            return;
        }
        super.onBackPressed();
    }

    // ── 列目录 ───────────────────────────────────────────────

    private void load() {
        if (loading) return;
        loading = true;
        bar.setVisibility(View.VISIBLE);
        stateView.setVisibility(View.VISIBLE);
        stateView.setText("正在读取分享列表…");
        listBox.removeAllViews();
        updateHeader();

        new Thread(() -> {
            List<Item> items = null;
            String err = null;
            try {
                items = list(curCategory);
            } catch (Throwable t) {
                err = t.getMessage() == null ? t.toString() : t.getMessage();
            }
            final List<Item> fitems = items;
            final String ferr = err;
            ui.post(() -> {
                loading = false;
                bar.setVisibility(View.GONE);
                if (fitems == null) {
                    stateView.setVisibility(View.VISIBLE);
                    stateView.setText("读取失败：" + ferr + "\n\n点此重试");
                    listBox.removeAllViews();
                    return;
                }
                render(fitems);
            });
        }, "HyVqShareList").start();
    }

    private void updateHeader() {
        titleView.setText(curCategory == null ? "实用软件分享" : curCategory);
        crumbView.setText(curCategory == null
                ? "分类目录 · 数据来自作者云盘，只读分享"
                : "← 点击左上角返回分类");
    }

    /** PROPFIND Depth:1 列出当前目录 */
    private List<Item> list(String category) throws Exception {
        HttpUrl.Builder b = HttpUrl.parse(BASE).newBuilder();
        if (category != null) b.addPathSegment(category);
        HttpUrl url = b.build();

        Request req = new Request.Builder()
                .url(url)
                .method("PROPFIND", RequestBody.create(
                        MediaType.parse("application/xml; charset=utf-8"), PROPFIND_BODY))
                .header("Depth", "1")
                .header("Authorization", cred)
                .header("Accept", "application/xml, text/xml, */*")
                .build();

        try (Response resp = client.newCall(req).execute()) {
            ResponseBody body = resp.body();
            String xml = body == null ? "" : body.string();
            if (!resp.isSuccessful()) {
                throw new IllegalStateException("HTTP " + resp.code()
                        + "（请检查网络）");
            }
            return parse(xml, url.encodedPath());
        }
    }

    /** 解析 multistatus（用 XmlPullParser，命名空间前缀无关；跳过目录自身） */
    private List<Item> parse(String xml, String selfPath) throws Exception {
        List<Item> out = new ArrayList<>();
        org.xmlpull.v1.XmlPullParser p = android.util.Xml.newPullParser();
        p.setInput(new java.io.StringReader(xml));
        Item cur = null;
        for (int e = p.getEventType(); e != org.xmlpull.v1.XmlPullParser.END_DOCUMENT; e = p.next()) {
            String name = p.getName();
            String ln = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (e == org.xmlpull.v1.XmlPullParser.START_TAG) {
                if (ln.equals("response")) {
                    cur = new Item();
                } else if (cur != null) {
                    if (ln.equals("href")) {
                        cur.href = p.nextText();
                    } else if (ln.equals("displayname")) {
                        cur.name = p.nextText();
                    } else if (ln.equals("getcontentlength")) {
                        cur.size = parseLong(p.nextText());
                    } else if (ln.equals("collection")) {
                        cur.dir = true;
                    }
                }
            } else if (e == org.xmlpull.v1.XmlPullParser.END_TAG && ln.equals("response")) {
                if (cur != null && cur.href != null
                        && !normalize(cur.href).equals(normalize(selfPath))) {
                    out.add(cur);
                }
                cur = null;
            }
        }
        // 文件夹在前，名称自然排序（中文用 Collator，拼音顺序更像人预期）
        final Collator col = Collator.getInstance(Locale.CHINA);
        Collections.sort(out, (a, b2) -> {
            if (a.dir != b2.dir) return a.dir ? -1 : 1;
            return col.compare(a.display(), b2.display());
        });
        return out;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        String r = s;
        while (r.endsWith("/") && r.length() > 1) r = r.substring(0, r.length() - 1);
        return r;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ── 渲染 ─────────────────────────────────────────────────

    private void render(List<Item> items) {
        listBox.removeAllViews();
        if (items.isEmpty()) {
            stateView.setVisibility(View.VISIBLE);
            stateView.setText(curCategory == null
                    ? "分享目录还是空的\n\n（作者会陆续上传，稍后再来看看）"
                    : "这个分类还没有软件\n\n点左上角返回");
            return;
        }
        stateView.setVisibility(View.GONE);
        for (Item it : items) {
            listBox.addView(row(it));
        }
    }

    private View row(final Item it) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setClickable(true);
        row.setBackground(rounded());

        ImageView ic = new ImageView(this);
        ic.setImageResource(it.dir ? R.drawable.ic_folder : R.drawable.ic_apk);
        ic.setColorFilter(attrColor(it.dir
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorOnSurfaceVariant));
        int sz = dp(22);
        row.addView(ic, new LinearLayout.LayoutParams(sz, sz));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText(it.display());
        t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t1.setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurface));
        t1.setSingleLine(true);
        t1.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText(it.dir ? "文件夹" : sizeText(it.size));
        t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t2.setAlpha(0.7f);
        t2.setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        col.addView(t2);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.setMarginStart(dp(12));
        row.addView(col, clp);

        if (!it.dir) {
            ImageView dl = new ImageView(this);
            dl.setImageResource(R.drawable.ic_download);
            dl.setColorFilter(attrColor(com.google.android.material.R.attr.colorPrimary));
            int ds = dp(20);
            row.addView(dl, new LinearLayout.LayoutParams(ds, ds));
        }

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(6);
        row.setLayoutParams(rlp);

        row.setOnClickListener(v -> {
            if (it.dir) {
                curCategory = it.display();
                load();
            } else {
                confirmDownload(it);
            }
        });
        return row;
    }

    private static String sizeText(long n) {
        if (n <= 0) return "未知大小";
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(Locale.US, "%.1f KB", n / 1024.0);
        return String.format(Locale.US, "%.1f MB", n / 1048576.0);
    }

    private int attrColor(int attr) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    private android.graphics.drawable.GradientDrawable rounded() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(attrColor(com.google.android.material.R.attr.colorSurfaceContainerLow));
        g.setStroke(dp(1), attrColor(com.google.android.material.R.attr.colorOutlineVariant));
        return g;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ── 下载并安装 ───────────────────────────────────────────

    private void confirmDownload(final Item it) {
        new AlertDialog.Builder(this)
                .setTitle(it.display())
                .setMessage("大小 " + sizeText(it.size)
                        + "\n\n将从作者云盘下载到本机并打开安装器。")
                .setPositiveButton("下载安装", (d, w) -> download(it))
                .setNegativeButton("取消", null)
                .show();
    }

    private void download(final Item it) {
        final ProgressBar pb = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        final TextView msg = new TextView(this);
        msg.setPadding(dp(20), dp(8), dp(20), dp(4));
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        msg.setText("准备中…");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(msg);
        box.addView(pb);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("正在下载")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("取消", null)
                .create();
        dlg.show();

        HttpUrl.Builder b = HttpUrl.parse(BASE).newBuilder();
        if (curCategory != null) b.addPathSegment(curCategory);
        b.addPathSegment(it.display());
        final HttpUrl url = b.build();

        new Thread(() -> {
            File base = getExternalCacheDir();
            if (base == null) base = getCacheDir();
            File dir = new File(base, "share_dl");
            if (!dir.exists() && !dir.mkdirs()) {
                ui.post(() -> {
                    dlg.dismiss();
                    toast("无法创建缓存目录");
                });
                return;
            }
            File apk = new File(dir, it.display());
            Request req = new Request.Builder().url(url)
                    .header("Authorization", cred).build();
            try (Response resp = client.newCall(req).execute()) {
                ResponseBody body = resp.body();
                if (!resp.isSuccessful() || body == null) {
                    final String m = "HTTP " + resp.code();
                    ui.post(() -> {
                        dlg.dismiss();
                        toast("下载失败：" + m);
                    });
                    return;
                }
                long total = body.contentLength();
                try (InputStream in = body.byteStream();
                     FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[64 * 1024];
                    long got = 0;
                    int n;
                    long lastUi = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        got += n;
                        long now = System.currentTimeMillis();
                        if (now - lastUi > 200) {
                            lastUi = now;
                            final int pct = total > 0 ? (int) (got * 100 / total) : -1;
                            final long fgot = got;
                            ui.post(() -> {
                                if (pct >= 0) pb.setProgress(pct);
                                msg.setText(pct >= 0
                                        ? (pct + "%  " + sizeText(fgot) + " / " + sizeText(total))
                                        : ("已下载 " + sizeText(fgot)));
                            });
                        }
                    }
                }
                final long size = apk.length();
                ui.post(() -> {
                    dlg.dismiss();
                    toast("下载完成（" + sizeText(size) + "），正在打开安装器…");
                    installApk(apk);
                });
            } catch (Throwable t) {
                final String m = t.getMessage() == null ? t.toString() : t.getMessage();
                ui.post(() -> {
                    dlg.dismiss();
                    toast("下载出错：" + m);
                });
            }
        }, "HyVqShareDownload").start();
    }

    /** 拉起系统安装器（与 MainActivity.installApk 同款：FileProvider 授权 + 未知来源引导） */
    private void installApk(File apk) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26
                    && !getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(this, "请先允许「安装未知应用」后重试", Toast.LENGTH_LONG).show();
                startActivity(new Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "拉起安装器失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
