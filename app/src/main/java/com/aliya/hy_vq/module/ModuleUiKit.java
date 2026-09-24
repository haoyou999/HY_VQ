package com.aliya.hy_vq.module;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.WindowManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * HY_VQ 模块 UI 适配工具包（Module UI Kit）。
 *
 * <p>目的：保证软件对模块 UI 的适配性。任何模块（内置或外置）都可以调用本工具包
 * 构建对话框、卡片行、标题等组件，自动获得与主框架一致的玻璃拟态 / Material3
 * 主题外观，并随深色模式、用户自定义主题（模块贡献的 HyVqTheme）自动变化。</p>
 *
 * <p>所有方法都是纯 Java 构建（基于主题 attr 解析），不依赖模块自带资源，
 * 因此外置模块无需打包任何 drawable/layout 也能获得统一视觉。</p>
 */
public final class ModuleUiKit {

    private ModuleUiKit() {}

    // ── 主题工具 ──

    /** 解析当前主题下某个 attr 的颜色值（模块 UI 与主框架配色保持一致的关键） */
    public static int color(Context context, int attr) {
        TypedValue tv = new TypedValue();
        context.getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    /** 按名称解析模块图标（如 "ic_chat"），找不到时用兜底图标 */
    public static int iconRes(Context context, String iconName, int fallbackRes) {
        if (iconName == null || iconName.isEmpty()) return fallbackRes;
        int id = context.getResources().getIdentifier(iconName, "drawable", context.getPackageName());
        return id == 0 ? fallbackRes : id;
    }

    public static void toast(Context context, String msg) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
    }

    // ── 玻璃对话框 ──

    /**
     * 创建玻璃拟态对话框骨架：透明窗口 + 圆角玻璃背景 + 92% 屏宽。
     * 模块把自己的内容 View 传入即可。
     */
    public static Dialog glassDialog(Context context, View content) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 20);
        root.setPadding(pad, pad, pad, pad);
        // 玻璃质感：面板 88% 不透明度，让窗口背景模糊透出来
        int surface = color(context, com.google.android.material.R.attr.colorSurfaceContainerHigh);
        // 边框描边（用户反馈：弹窗与背景同色无法分辨边界 → 统一加 1dp outline 描边）
        int stroke = color(context, com.google.android.material.R.attr.colorOutlineVariant);
        GradientDrawable gd = rounded(context, 24, (surface & 0x00FFFFFF) | 0xE0000000, stroke);
        gd.setStroke(dp(context, 1), stroke);
        root.setBackground(gd);
        if (content != null) {
            root.addView(content, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            Window window = dialog.getWindow();
            window.setLayout(
                    (int) (context.getResources().getDisplayMetrics().widthPixels * 0.92),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            // 浮窗背景模糊（规范：浮窗必须带背景模糊）
            applyBlur(window, context);
        }
        dialog.setCancelable(true);
        return dialog;
    }

    /**
     * 浮窗背景模糊（规范：所有浮窗/对话框统一走本方法）。
     * <p>采用网络通用方案（Blurry/BlurView 同原理，全设备可靠）：
     * <ul>
     *   <li>主方案：截取当前界面 → 高斯模糊（box blur 三趟近似）→ 设为窗口背景。
     *       窗口铺满屏幕、原弹窗内容居中悬浮在模糊背景上，任何 Android 版本均有效，
     *       不依赖系统 {@code FLAG_BLUR_BEHIND}（部分 OEM 设备声明支持但实际无效）。</li>
     *   <li>兜底（截图失败，如 context 非 Activity）：API 31+ 系统窗口模糊；
     *       API 28~30 暗化遮罩 {@code FLAG_DIM_BEHIND}。</li>
     * </ul>
     * 注意：面板自身需半透明（如 {@link #glassDialog} 的 88% 不透明度），模糊效果才可见。
     */
    public static void applyBlur(Window window, Context context) {
        if (window == null) return;
        // 主方案：截图模糊背景（弹窗内容悬浮其上，全版本可靠）
        Drawable bg = captureBlurredBackground(context);
        if (bg != null) {
            // 记录原窗口宽度，保持各弹窗既定宽度不变（如 92%/78% 屏宽）
            int prevWidth = window.getAttributes().width;
            window.setBackgroundDrawable(bg);
            // 窗口铺满屏幕，背景模糊图才能完整覆盖弹窗后区域
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setGravity(Gravity.CENTER);
            // 原弹窗内容改为垂直居中悬浮（宽度保持原窗口宽度）
            View decor = window.getDecorView();
            View content = decor.findViewById(android.R.id.content);
            if (content instanceof FrameLayout && ((FrameLayout) content).getChildCount() > 0) {
                View child = ((FrameLayout) content).getChildAt(0);
                child.setLayoutParams(new FrameLayout.LayoutParams(
                        prevWidth > 0 ? prevWidth : ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER));
            }
            return;
        }
        // 兜底：系统窗口模糊 / 暗化遮罩
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            float radiusPx = context.getResources().getDisplayMetrics().density * 15f;
            window.setBackgroundBlurRadius((int) radiusPx);
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.45f);
        }
    }

    /**
     * 截取当前界面内容并高斯模糊，作为弹窗背景（主线程调用）。
     * 截图失败（非 Activity 上下文/尺寸无效）返回 null，由调用方走兜底方案。
     */
    private static Drawable captureBlurredBackground(Context context) {
        if (!(context instanceof android.app.Activity)) return null;
        try {
            android.app.Activity activity = (android.app.Activity) context;
            View decor = activity.getWindow().getDecorView();
            View content = decor.findViewById(android.R.id.content);
            if (content == null || content.getWidth() <= 0 || content.getHeight() <= 0) return null;
            int[] loc = new int[2];
            content.getLocationInWindow(loc);
            Bitmap shot = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(shot);
            canvas.translate(-loc[0], -loc[1]);
            decor.draw(canvas);
            // 模糊半径 3：三趟 box blur（水平+垂直）已足够柔和；过高的半径（如 6）
            // 会让背景糊成一团，文字/内容观感发虚（实测反馈"模糊度过高"）。
            Bitmap blurred = boxBlur(shot, 3);
            if (blurred != shot) shot.recycle();
            return new BitmapDrawable(context.getResources(), blurred);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 高斯模糊近似：降采样 1/4 + 三趟 box blur（水平+垂直），弹窗场景性能足够 */
    private static Bitmap boxBlur(Bitmap src, int radius) {
        float scale = 0.25f;
        int w = Math.max(1, (int) (src.getWidth() * scale));
        int h = Math.max(1, (int) (src.getHeight() * scale));
        Bitmap bmp = Bitmap.createScaledBitmap(src, w, h, true);
        int r = Math.max(1, radius);
        for (int pass = 0; pass < 3; pass++) {
            bmp = boxBlurPass(bmp, r, true);
            bmp = boxBlurPass(bmp, r, false);
        }
        return bmp;
    }

    /** 单方向 box blur 一趟（滑动窗口，O(n)）；返回新位图并回收原图 */
    private static Bitmap boxBlurPass(Bitmap src, int radius, boolean horizontal) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] in = new int[w * h];
        src.getPixels(in, 0, w, 0, 0, w, h);
        int[] out = new int[w * h];
        int span = radius * 2 + 1;
        if (horizontal) {
            for (int y = 0; y < h; y++) {
                int ra = 0, ga = 0, ba = 0;
                int row = y * w;
                for (int i = -radius; i <= radius; i++) {
                    int c = in[row + clamp(i, 0, w - 1)];
                    ra += (c >> 16) & 0xFF;
                    ga += (c >> 8) & 0xFF;
                    ba += c & 0xFF;
                }
                for (int x = 0; x < w; x++) {
                    out[row + x] = 0xFF000000 | ((ra / span) << 16) | ((ga / span) << 8) | (ba / span);
                    int add = clamp(x + radius + 1, 0, w - 1);
                    int rem = clamp(x - radius, 0, w - 1);
                    int ca = in[row + add];
                    int cr = in[row + rem];
                    ra += ((ca >> 16) & 0xFF) - ((cr >> 16) & 0xFF);
                    ga += ((ca >> 8) & 0xFF) - ((cr >> 8) & 0xFF);
                    ba += (ca & 0xFF) - (cr & 0xFF);
                }
            }
        } else {
            for (int x = 0; x < w; x++) {
                int ra = 0, ga = 0, ba = 0;
                for (int i = -radius; i <= radius; i++) {
                    int c = in[clamp(i, 0, h - 1) * w + x];
                    ra += (c >> 16) & 0xFF;
                    ga += (c >> 8) & 0xFF;
                    ba += c & 0xFF;
                }
                for (int y = 0; y < h; y++) {
                    out[y * w + x] = 0xFF000000 | ((ra / span) << 16) | ((ga / span) << 8) | (ba / span);
                    int add = clamp(y + radius + 1, 0, h - 1);
                    int rem = clamp(y - radius, 0, h - 1);
                    int ca = in[add * w + x];
                    int cr = in[rem * w + x];
                    ra += ((ca >> 16) & 0xFF) - ((cr >> 16) & 0xFF);
                    ga += ((ca >> 8) & 0xFF) - ((cr >> 8) & 0xFF);
                    ba += (ca & 0xFF) - (cr & 0xFF);
                }
            }
        }
        Bitmap res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        res.setPixels(out, 0, w, 0, 0, w, h);
        src.recycle();
        return res;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ── 卡片行（图标 + 标题 + 描述 + 点击） ──

    /**
     * 构建与主框架侧边栏/对话框一致的"卡片行"列表项：
     * 圆角图标容器 + 主标题 + 描述文字，整行可点击。
     * 模块的列表、菜单、设置项应统一走本方法，保证交互视觉一致。
     */
    public static LinearLayout cardRow(Context context, int iconRes,
                                       String title, String desc,
                                       View.OnClickListener onClick) {
        int surface = color(context, com.google.android.material.R.attr.colorSurfaceContainerLow);
        int onSurface = color(context, com.google.android.material.R.attr.colorOnSurface);
        int onSurfaceVariant = color(context, com.google.android.material.R.attr.colorOnSurfaceVariant);
        int primaryContainer = color(context, com.google.android.material.R.attr.colorPrimaryContainer);
        int onPrimaryContainer = color(context, com.google.android.material.R.attr.colorOnPrimaryContainer);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(context, 14);
        row.setPadding(pad, pad, pad, pad);
        row.setBackground(rippleBg(context, rounded(context, 16, surface, 0)));
        row.setClickable(true);
        row.setFocusable(true);
        if (onClick != null) row.setOnClickListener(onClick);

        // 图标容器
        LinearLayout iconBox = new LinearLayout(context);
        int boxSize = dp(context, 40);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(boxSize, boxSize);
        iconBox.setLayoutParams(boxLp);
        iconBox.setGravity(Gravity.CENTER);
        iconBox.setBackground(rounded(context, 12, primaryContainer, 0));
        ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        icon.setColorFilter(onPrimaryContainer);
        icon.setPadding(dp(context, 9), dp(context, 9), dp(context, 9), dp(context, 9));
        iconBox.addView(icon, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(iconBox);

        // 文案列
        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginStart(dp(context, 12));
        row.addView(textCol, textLp);

        TextView titleView = new TextView(context);
        titleView.setText(title == null ? "" : title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleView.setTextColor(onSurface);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        textCol.addView(titleView);

        if (desc != null && !desc.isEmpty()) {
            TextView descView = new TextView(context);
            descView.setText(desc);
            descView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            descView.setTextColor(onSurfaceVariant);
            LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            descLp.topMargin = dp(context, 2);
            textCol.addView(descView, descLp);
        }
        return row;
    }

    // ── 小节标题 ──

    /** 构建模块设置页/内容页统一的"小节标题" */
    public static TextView sectionHeader(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text == null ? "" : text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(color(context, com.google.android.material.R.attr.colorOnSurfaceVariant));
        tv.setPadding(dp(context, 4), dp(context, 12), dp(context, 4), dp(context, 6));
        return tv;
    }

    // ── 内部工具 ──

    private static int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    /** 圆角填充背景（公开：模块构建行/栏背景时直接调用，保证圆角风格统一） */
    public static GradientDrawable rounded(Context context, int radiusDp, int fillColor, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(context, radiusDp));
        if (fillColor != 0) gd.setColor(fillColor);
        if (strokeColor != 0) gd.setStroke(dp(context, 1), strokeColor);
        return gd;
    }

    /** 快捷：圆角背景 + 水波纹 */
    private static android.graphics.drawable.RippleDrawable rippleBg(Context context, GradientDrawable base) {
        int ripple = color(context, com.google.android.material.R.attr.colorOnSurface);
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(ripple & 0xFFFFFF | 0x1A000000),
                base, null);
    }
}