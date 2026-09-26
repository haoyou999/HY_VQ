package com.aliya.hy_vq;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.aliya.hy_vq.module.ModuleUiKit;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 鸣潮表情包（v2.9.1 内嵌化）。
 *
 * <p>与文件管理模块同款「内嵌视图」：由 {@code MainActivity} inflate 后经
 * {@code switchContent()} 放进 {@code contentFrame}，<b>不再 startActivity 跳独立页面</b>。</p>
 *
 * <p>数据来源：呜哇小站 · 表情包仓鼠库（https://emoji.wuwa.games/api），
 * 感谢其为社区提供的免费 API 服务。</p>
 *
 * <p><b>每日调用限流</b>：站方对原图下载有频率限制（登录后约 60 分钟 50 次）。
 * 本应用主动限制在每日 DAILY_LIMIT 次，远低于官方额度 —— 免费服务需要被善待。</p>
 */
public class WuwaEmojiView {

    private static final String API_RANDOM =
            "https://emoji.wuwa.games/apis/api.random-emoji.wuwa.games/v1alpha1/random";

    private static final int TIMEOUT_MS = 15000;

    private static final int DAILY_LIMIT = 60;
    private static final String PREF_NAME = "app_settings";
    private static final String PREF_DAY = "wuwa_day";
    private static final String PREF_COUNT = "wuwa_count";

    private final Activity host;
    private final View root;

    private ImageView ivEmoji;
    private ProgressBar pbEmoji;
    private TextView tvHint, tvInfo, tvQuota;
    private EditText etCharacter;
    private View btnFetch, btnSave, btnOpenSource;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private String curUrl, curSourceUrl, curName, curSlug, curFormat;
    private byte[] curBytes;

    public WuwaEmojiView(Activity host, LayoutInflater inflater, ViewGroup parent) {
        this.host = host;
        this.root = inflater.inflate(R.layout.activity_wuwa_emoji, parent, false);

        ivEmoji = root.findViewById(R.id.iv_emoji);
        pbEmoji = root.findViewById(R.id.pb_emoji);
        tvHint = root.findViewById(R.id.tv_emoji_hint);
        tvInfo = root.findViewById(R.id.tv_emoji_info);
        tvQuota = root.findViewById(R.id.tv_emoji_quota);
        etCharacter = root.findViewById(R.id.et_emoji_character);
        btnFetch = root.findViewById(R.id.btn_emoji_fetch);
        btnSave = root.findViewById(R.id.btn_emoji_save);
        btnOpenSource = root.findViewById(R.id.btn_emoji_open_source);

        // 内嵌后由宿主工具栏负责返回，隐藏页面自带的返回按钮
        View back = root.findViewById(R.id.btn_emoji_back);
        if (back != null) back.setVisibility(View.GONE);

        btnFetch.setOnClickListener(v -> fetchRandom());
        btnSave.setOnClickListener(v -> saveCurrent());
        btnOpenSource.setOnClickListener(v -> {
            if (curSourceUrl != null && !curSourceUrl.isEmpty()) {
                try {
                    host.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(curSourceUrl)));
                } catch (Throwable t) {
                    ModuleUiKit.toast(host, "无法打开链接");
                }
            }
        });

        refreshQuota();
        fetchRandom();
    }

    public View getRoot() {
        return root;
    }

    // ---------------- 每日限流 ----------------

    private String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date());
    }

    private SharedPreferences prefs() {
        return host.getSharedPreferences(PREF_NAME, Activity.MODE_PRIVATE);
    }

    private int usedToday() {
        SharedPreferences sp = prefs();
        if (!today().equals(sp.getString(PREF_DAY, ""))) return 0;   // 跨天自动归零
        return sp.getInt(PREF_COUNT, 0);
    }

    private void markUsed() {
        int n = usedToday() + 1;
        prefs().edit().putString(PREF_DAY, today()).putInt(PREF_COUNT, n).apply();
    }

    private int remaining() {
        return Math.max(0, DAILY_LIMIT - usedToday());
    }

    private void refreshQuota() {
        int left = remaining();
        tvQuota.setText("今日剩余 " + left + " / " + DAILY_LIMIT);
        btnFetch.setEnabled(left > 0);
    }

    // ---------------- 获取 ----------------

    private void fetchRandom() {
        if (remaining() <= 0) {
            ModuleUiKit.toast(host, "今日调用已达上限（" + DAILY_LIMIT + " 次），明日自动恢复");
            return;
        }
        final String ch = etCharacter.getText() == null ? ""
                : etCharacter.getText().toString().trim();
        setLoading(true);
        tvInfo.setText("");

        new Thread(() -> {
            String err = null;
            JSONObject meta = null;
            byte[] bytes = null;
            try {
                StringBuilder sb = new StringBuilder(API_RANDOM);
                if (!ch.isEmpty()) {
                    sb.append("?character=").append(URLEncoder.encode(ch, "UTF-8"));
                }
                // 直接免鉴权调用：实测接口本身开放；官方令牌在当前环境下会被判为
                //「API Key 无效、已停用或已过期」而返回 401，故不携带任何鉴权头。
                HttpURLConnection c = (HttpURLConnection) new URL(sb.toString()).openConnection();
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                c.setRequestProperty("Accept", "application/json");
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("接口返回 HTTP " + code);
                String body = readAll(c.getInputStream());
                c.disconnect();

                meta = new JSONObject(body);
                String imgUrl = meta.optString("url", "");
                if (imgUrl.isEmpty()) throw new Exception("响应中没有图片地址");

                // ticket 直链有有效期，取到立刻下载
                HttpURLConnection ic = (HttpURLConnection) new URL(imgUrl).openConnection();
                ic.setConnectTimeout(TIMEOUT_MS);
                ic.setReadTimeout(TIMEOUT_MS);
                int icode = ic.getResponseCode();
                if (icode != 200) throw new Exception("图片下载失败 HTTP " + icode);
                bytes = readBytes(ic.getInputStream());
                ic.disconnect();
                if (bytes == null || bytes.length == 0) throw new Exception("图片内容为空");
            } catch (Throwable t) {
                err = t.getMessage();
            }

            final String ferr = err;
            final JSONObject fmeta = meta;
            final byte[] fbytes = bytes;
            handler.post(() -> {
                setLoading(false);
                if (ferr != null) {
                    tvHint.setVisibility(View.VISIBLE);
                    tvHint.setText("获取失败：" + ferr + "\n请检查网络后重试");
                    ModuleUiKit.toast(host, "获取失败：" + ferr);
                    refreshQuota();
                    return;
                }
                markUsed();           // 仅成功才计数
                showEmoji(fmeta, fbytes);
                refreshQuota();
            });
        }, "HyVqEmojiFetch").start();
    }

    private void showEmoji(JSONObject meta, byte[] bytes) {
        curBytes = bytes;
        curUrl = meta.optString("url", "");
        curSourceUrl = meta.optString("sourceUrl", "");
        curFormat = meta.optString("format", "");
        JSONObject c = meta.optJSONObject("character");
        curName = c != null ? c.optString("name", "") : "";
        curSlug = c != null ? c.optString("slug", "") : "";

        Drawable d = null;
        try {
            ImageDecoder.Source src = ImageDecoder.createSource(bytes);
            d = ImageDecoder.decodeDrawable(src);
        } catch (Throwable t) {
            try {
                d = Drawable.createFromStream(new java.io.ByteArrayInputStream(bytes), "emoji");
            } catch (Throwable ignored) {
            }
        }
        if (d == null) {
            tvHint.setVisibility(View.VISIBLE);
            tvHint.setText("图片解码失败（格式：" + curFormat + "）");
            return;
        }
        tvHint.setVisibility(View.GONE);
        ivEmoji.setImageDrawable(d);
        if (d instanceof android.graphics.drawable.AnimatedImageDrawable) {
            try {
                android.graphics.drawable.AnimatedImageDrawable a =
                        (android.graphics.drawable.AnimatedImageDrawable) d;
                a.setRepeatCount(android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE);
                a.start();
            } catch (Throwable ignored) {
            }
        }

        StringBuilder info = new StringBuilder();
        if (!curName.isEmpty()) info.append("角色：").append(curName);
        if (!curFormat.isEmpty()) {
            if (info.length() > 0) info.append("    ");
            info.append("格式：").append(curFormat.toUpperCase());
        }
        if (info.length() > 0) info.append("    ");
        info.append("大小：").append(bytes.length / 1024).append(" KB");
        tvInfo.setText(info.toString());

        btnSave.setEnabled(true);
        btnOpenSource.setEnabled(!curSourceUrl.isEmpty());
    }

    /** 保存到相册 Pictures/HY_VQ表情/ */
    private void saveCurrent() {
        if (curBytes == null || curBytes.length == 0) return;
        String ext = (curFormat == null || curFormat.isEmpty()) ? "gif" : curFormat;
        String base = curName == null || curName.isEmpty() ? "wuwa" : curName;
        final String fileName = "HY_VQ_" + base + "_" + System.currentTimeMillis() + "." + ext;

        new Thread(() -> {
            String err = null, savedPath = null;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                    cv.put(MediaStore.Images.Media.MIME_TYPE, "image/" + ext);
                    cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/HY_VQ表情");
                    Uri uri = host.getContentResolver().insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new Exception("无法创建媒体条目");
                    try (OutputStream os = host.getContentResolver().openOutputStream(uri)) {
                        if (os == null) throw new Exception("无法写入");
                        os.write(curBytes);
                    }
                    savedPath = "相册/Pictures/HY_VQ表情/" + fileName;
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_PICTURES), "HY_VQ表情");
                    if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建目录");
                    File f = new File(dir, fileName);
                    try (FileOutputStream fos = new FileOutputStream(f)) {
                        fos.write(curBytes);
                    }
                    savedPath = f.getAbsolutePath();
                }
            } catch (Throwable t) {
                err = t.getMessage();
            }
            final String ferr = err, fpath = savedPath;
            handler.post(() -> {
                if (ferr != null) ModuleUiKit.toast(host, "保存失败：" + ferr);
                else ModuleUiKit.toast(host, "已保存到 " + fpath);
            });
        }, "HyVqEmojiSave").start();
    }

    private void setLoading(boolean loading) {
        pbEmoji.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) {
            btnFetch.setEnabled(false);
            tvHint.setVisibility(View.VISIBLE);
            tvHint.setText("正在获取…");
        } else {
            btnFetch.setEnabled(remaining() > 0);
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), "UTF-8");
    }

    private static byte[] readBytes(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
