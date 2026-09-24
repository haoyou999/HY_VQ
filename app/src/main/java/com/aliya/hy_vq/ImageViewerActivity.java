package com.aliya.hy_vq;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.aliya.hy_vq.module.ModuleUiKit;

import java.io.InputStream;

/**
 * 内置图片查看器（原生 BitmapFactory + Matrix，无第三方依赖）。
 *
 * <p>配合文件管理的「打开方式」弹窗使用：图片类文件优先由本页打开。
 * 支持：双指缩放、拖动查看、双击还原、点击画面切换信息栏显隐、
 * 大图按目标尺寸采样解码（避免 OOM），兼容本地路径与 SAF(content://)。</p>
 */
public class ImageViewerActivity extends Activity {

    public static final String EXTRA_PATH = "image_path";
    public static final String EXTRA_URI = "image_uri";
    public static final String EXTRA_TITLE = "image_title";

    private ImageView imageView;
    private View topBar, hintBar;
    private final Matrix matrix = new Matrix();
    private android.view.ScaleGestureDetector scaleDetector;
    private android.view.GestureDetector gestureDetector;
    private boolean barsVisible = true;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_image_viewer);

        imageView = findViewById(R.id.iv_image);
        topBar = findViewById(R.id.iv_top);
        hintBar = findViewById(R.id.tv_iv_hint);
        TextView tvTitle = findViewById(R.id.tv_iv_title);
        TextView tvInfo = findViewById(R.id.tv_iv_info);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        String uriStr = getIntent().getStringExtra(EXTRA_URI);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        boolean isUri = uriStr != null && !uriStr.isEmpty();
        if (title == null || title.isEmpty()) {
            title = isUri ? "图片" : new java.io.File(path == null ? "" : path).getName();
        }
        tvTitle.setText(title);
        tvInfo.setText("双指缩放");

        findViewById(R.id.btn_iv_back).setOnClickListener(v -> finish());

        // 手势：ScaleGestureDetector 管双指缩放，GestureDetector 管拖动/双击/单击
        scaleDetector = new android.view.ScaleGestureDetector(this,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(android.view.ScaleGestureDetector d) {
                        float f = d.getScaleFactor();
                        matrix.postScale(f, f, d.getFocusX(), d.getFocusY());
                        imageView.setImageMatrix(matrix);
                        return true;
                    }
                });

        gestureDetector = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDoubleTap(MotionEvent e) {
                        resetMatrix();
                        return true;
                    }

                    @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                        if (e2.getPointerCount() > 1) return false;   // 双指时交给缩放
                        matrix.postTranslate(-dx, -dy);
                        imageView.setImageMatrix(matrix);
                        return true;
                    }

                    @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                        toggleBars();
                        return true;
                    }
                });

        imageView.setOnTouchListener((v, ev) -> {
            scaleDetector.onTouchEvent(ev);
            gestureDetector.onTouchEvent(ev);
            return true;
        });

        new Thread(() -> {
            final Bitmap bmp;
            try {
                bmp = decodeSampled(isUri ? uriStr : path, isUri);
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    ModuleUiKit.toast(this, "图片解码失败：" + t.getMessage());
                    finish();
                });
                return;
            }
            runOnUiThread(() -> {
                if (bmp == null) {
                    ModuleUiKit.toast(this, "无法解码该图片");
                    finish();
                    return;
                }
                imageView.setImageBitmap(bmp);
                tvInfo.setText(bmp.getWidth() + " × " + bmp.getHeight());
                resetMatrix();
            });
        }).start();
    }

    /** 中央居中显示（初始与双击还原共用） */
    private void resetMatrix() {
        int vw = imageView.getWidth(), vh = imageView.getHeight();
        android.graphics.drawable.Drawable d = imageView.getDrawable();
        if (d == null || vw == 0 || vh == 0) {
            matrix.reset();
            imageView.setImageMatrix(matrix);
            return;
        }
        float sx = (float) vw / d.getIntrinsicWidth();
        float sy = (float) vh / d.getIntrinsicHeight();
        float s = Math.min(sx, sy);           // 完整显示，不裁切
        float dx = (vw - d.getIntrinsicWidth() * s) / 2f;
        float dy = (vh - d.getIntrinsicHeight() * s) / 2f;
        matrix.reset();
        matrix.postScale(s, s);
        matrix.postTranslate(dx, dy);
        imageView.setImageMatrix(matrix);
    }

    private void toggleBars() {
        barsVisible = !barsVisible;
        topBar.setVisibility(barsVisible ? View.VISIBLE : View.GONE);
        hintBar.setVisibility(barsVisible ? View.VISIBLE : View.GONE);
    }

    /** 按屏幕尺寸采样解码，避免大图 OOM */
    private Bitmap decodeSampled(String src, boolean isUri) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        if (isUri) {
            try (InputStream in = getContentResolver().openInputStream(Uri.parse(src))) {
                BitmapFactory.decodeStream(in, null, o);
            }
        } else {
            BitmapFactory.decodeFile(src, o);
        }
        o.inSampleSize = calcSample(o.outWidth, o.outHeight);
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565;   // 省内存
        if (isUri) {
            try (InputStream in = getContentResolver().openInputStream(Uri.parse(src))) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        }
        return BitmapFactory.decodeFile(src, o);
    }

    private int calcSample(int w, int h) {
        int reqW = getResources().getDisplayMetrics().widthPixels;
        int reqH = getResources().getDisplayMetrics().heightPixels;
        int sample = 1;
        while (w / sample > reqW * 2 && h / sample > reqH * 2) sample *= 2;
        return Math.max(1, sample);
    }
}
