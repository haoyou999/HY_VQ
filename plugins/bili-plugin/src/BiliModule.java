package com.aliya.hyvq.bili;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.aliya.hy_vq.module.HyVqModule;
import com.aliya.hy_vq.module.ModuleServices;

/**
 * BiliModule —— B 站接入插件(骨架版,测试版本号)
 *
 * <p>功能规划:接入 B 站 OAuth 2.0 授权登录(见 B 站开放平台文档)。
 * 当前骨架提供:
 * <ul>
 *   <li>插件入口(B站)与设置页</li>
 *   <li>接入状态占位(client_id/secret 待开放平台注册后填写)</li>
 *   <li>持久化:client_id / secret / 登录态(存插件私有 prefs)</li>
 * </ul>
 * 版本:0.1.0-beta(测试版)
 *
 * <p>打包:make_bili_plugin.sh → bili-plugin.zip(module.json + classes.dex)
 * 安装:HY_VQ → 插件 → 安装 → 选 zip
 */
public class BiliModule extends HyVqModule {

    // ── B 站开放平台(注册移动应用后填写;骨架阶段为空) ──
    public static final String OAUTH_URL = "https://open.bilibili.com/";
    private Context ctx;
    private ModuleServices services;

    public BiliModule() {
        this.id = "bili_oauth";
        this.name = "B站";
        this.version = "0.1.0-beta";
        this.icon = "ic_chat";          // 宿主内置图标,替换为 ic_bili 需宿主侧添加
        this.showInDrawer = true;
        this.declaredApis = "prefs,username";
        this.declaredSurfaces = "mine";
    }

    @Override
    public void onAttach(Context context, ModuleServices services) {
        this.ctx = context.getApplicationContext();
        this.services = services;
    }

    @Override
    public String getSummary() {
        return "B站接入插件(测试版 0.1.0-beta)";
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences("module_bili_oauth", Context.MODE_PRIVATE);
    }

    @Override
    public View createMainView(LayoutInflater inflater, ViewGroup parent) {
        Context c = ctx == null ? parent.getContext() : ctx;
        ScrollView sv = new ScrollView(c);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(c, 20);
        box.setPadding(pad, pad, pad, pad);
        sv.addView(box);

        TextView title = new TextView(c);
        title.setText("B站接入");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF1F1F1F);
        box.addView(title);

        TextView status = new TextView(c);
        String cid = prefs().getString("client_id", "");
        status.setText(cid.isEmpty()
                ? "状态:未配置\n\n请先在 B 站开放平台注册移动应用,\n获取 client_id / secret 后填写。"
                : "状态:已配置 client_id = " + cid);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setTextColor(0xFF555555);
        status.setPadding(0, dp(c, 16), 0, dp(c, 8));
        status.setLineSpacing(0, 1.4f);
        box.addView(status);

        TextView hint = new TextView(c);
        hint.setText("测试版本号 0.1.0-beta · 骨架占位\n后续更新通过网盘 zip 推送");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setTextColor(0xFF888888);
        box.addView(hint);
        return sv;
    }

    @Override
    public View createSettingsView(LayoutInflater inflater, ViewGroup parent) {
        Context c = ctx == null ? parent.getContext() : ctx;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(c, 16);
        box.setPadding(pad, pad, pad, pad);

        TextView t = new TextView(c);
        t.setText("B站插件设置(测试版)");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(null, Typeface.BOLD);
        box.addView(t);

        TextView btn = new TextView(c);
        btn.setText("  清除 B站登录配置");
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btn.setTextColor(0xFFFFFFFF);
        btn.setGravity(Gravity.CENTER_VERTICAL);
        btn.setBackgroundColor(0xFFE53935);
        int bpad = dp(c, 12);
        btn.setPadding(bpad, bpad, bpad, bpad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 16);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> {
            prefs().edit().clear().apply();
            Toast.makeText(c, "已清除 B站配置", Toast.LENGTH_SHORT).show();
        });
        box.addView(btn);
        return box;
    }

    private static int dp(Context c, int v) {
        return Math.round(c.getResources().getDisplayMetrics().density * v);
    }
}
