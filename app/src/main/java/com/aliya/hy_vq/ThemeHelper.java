package com.aliya.hy_vq;

import android.app.Activity;

/**
 * 主题色工具（仿 ZhuFiler ThemeHelper，2026-08-22 适配）。
 *
 * <p>2026-09-06 起配色方案已固定为「浅蓝」，不再提供切换：
 * 全局颜色由 res/values/colors.xml 的 core scheme + theme_blue_* 统一控制，
 * 本类只负责在 Activity 主题上叠加 {@code ThemeOverlay.App.Blue}。</p>
 *
 * <p>其余配色色阶（theme_purple_* / theme_pink_* 等）与
 * ThemeOverlay.App.* 仍保留在资源中作为预留，但没有任何切换入口。</p>
 */
public final class ThemeHelper {

    private ThemeHelper() {}

    /** 固定叠加浅蓝配色 overlay（本项目唯一配色方案） */
    public static void applyThemeColor(Activity activity) {
        activity.getTheme().applyStyle(R.style.ThemeOverlay_App_Blue, true);
    }
}
