package com.aliya.hy_vq;

import android.app.Activity;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
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
 * 鸣潮表情包获取页。
 *
 * <p>数据来源：呜哇小站 · 表情包仓鼠库（https://emoji.wuwa.games/api），
 * 感谢其为社区提供的免费 API 服务。</p>
 *
 * <p><b>每日调用限流（重要）</b>：站方对原图下载有频率限制（登录后约 60 分钟 50 次）。
 * 本应用主动把调用限制在每日 DAILY_LIMIT 次，远低于官方额度，
 * 目的是减轻对方服务器压力 —— 免费服务需要被善待。</p>
 *
 * <p>动图支持：minSdk 28 起可用 ImageDecoder 解码为 AnimatedImageDrawable，
 * GIF 直接播放，无需第三方库。</p>
 */
public class WuwaEmojiActivity extends Activity {

    private static final String API_RANDOM =
            "https://emoji.wuwa.games/apis/api.random-emoji.wuwa.games/v1alpha1/random";

    /** 服务方提供的长期稳定调用令牌（用户转交） */
    private static final String API_TOKEN =
            "re_75dc8a2b66824532aa45d7c04f7831a7.Xw-jYTPMfTO5sNCkIXA5umjkPWT8EaakfjqFYrVp8JA";

    private static final int TIMEOUT_MS = 15000;

    // 每日限流：远低于官方额度，主动减轻对方服务器压力
    private static final int DAILY_LIMIT = 60;
    private static final String PREF_NAME = "app_settings";
    private static final String PREF_DAY = "wuwa_day";
    private static final String PREF_COUNT = "wuwa_count";

    private ImageView ivEmoji;
    private ProgressBar pbEmoji;
    private TextView tvHint, tvInfo, tvQuota;
    private EditText etCharacter;
    private View btnFetch, btnSave, btnOpenSource;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private String curUrl, curSourceUrl, curName, curSlug, curFormat;
    private byte[] curBytes;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wuwa_emoji);

        ivEmoji = findViewById(R.id.iv_emoji);
        pbEmoji = findViewById(R.id.pb_emoji);
        tvHint = findViewById(R.id.tv_emoji_hint);
        tvInfo = findViewById(R.id.tv_emoji_info);
        tvQuota = findViewById(R.id.tv_emoji_quota);
        etCharacter = findViewById(R.id.et_emoji_character);
        btnFetch = findViewById(R.id.btn_emoji_fetch);
        btnSave = findViewById(R.id.btn_emoji_save);
        btnOpenSource = findViewById(R.id.btn_emoji_open_source);

        findViewById(R.id.btn_emoji_back).setOnClickListener(v -> finish());
        btnFetch.setOnClickListener(v -> fetchRandom());
        btnSave.setOnClickListener(v -> saveCurrent());
        btnOpenSource.setOnClickListener(v -> {
            if (curSourceUrl != null && !curSourceUrl.isEmpty()) {
                try {
                    startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                            Uri.parse(curSourceUrl)));
                } catch (Throwable t) {
                    ModuleUiKit.toast(this, "无法打开链接");
                }
            }
        });

        refreshQuota();
        fetchRandom();
    }

    // ---------------- 每日限流 ----------------

    private String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date());
    }

    private int usedToday() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        if (!today().equals(sp.getString(PREF_DAY, ""))) return 0;   // 跨天自动归零
        return sp.getInt(PREF_COUNT, 0);
    }

    private void markUsed() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        int n = usedToday() + 1;
        sp.edit().putString(PREF_DAY, today()).putInt(PREF_COUNT, n).apply();
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
            ModuleUiKit.toast(this, "今日调用已达上限（" + DAILY_LIMIT + " 次），明日自动恢复");
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
                HttpURLConnection c = (HttpURLConnection) new URL(sb.toString()).openConnection();
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("Authorization", "Bearer " + API_TOKEN);
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
                ic.setRequestProperty("Authorization", "Bearer " + API_TOKEN);
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
                    ModuleUiKit.toast(this, "获取失败：" + ferr);
                    refreshQuota();
                    return;
                }
                markUsed();           // 仅成功才计数
                showEmoji(fmeta, fbytes);
                refreshQuota();
            });
        }).start();
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
                    Uri uri = getContentResolver().insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new Exception("无法创建媒体条目");
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
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
                if (ferr != null) ModuleUiKit.toast(this, "保存失败：" + ferr);
                else ModuleUiKit.toast(this, "已保存到 " + fpath);
            });
        }).start();
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
