package com.aliya.hy_vq;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AnticipateOvershootInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;

import com.aliya.hy_vq.databinding.ActivityMainBinding;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import androidx.drawerlayout.widget.DrawerLayout;
import android.os.Build;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.aliya.hy_vq.module.HyVqModule;
import com.aliya.hy_vq.module.ModuleRegistry;
import com.aliya.hy_vq.module.ModuleServices;
import com.aliya.hy_vq.module.impl.FileManagerModule;
import com.aliya.hy_vq.module.ModuleUiKit;
import com.aliya.hy_vq.terracotta.HyVqP2pBridge;
import com.aliya.hy_vq.terracotta.TerracottaUiController;
import com.aliya.hy_vq.update.HyVqAppEntry;
import androidx.core.content.FileProvider;
import com.aliya.hy_vq.update.UpdateManager;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import dalvik.system.DexClassLoader;
import org.json.JSONObject;
import android.content.res.Resources;
import com.aliya.hy_vq.module.HyVqTheme;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_PICK_AVATAR = 1001;
    private static final int REQ_INSTALL_MODULE = 1002;
    private static final int REQ_NOTIFICATION = 1003;
    private static final int REQ_SAF_MOUNT = 1005;

    // ── 页面索引常量 ──
    // 固定页使用静态索引；模块页使用 PAGE_MODULE_BASE 起的动态索引，
    // 避免多个页面共用同一页码导致返回逻辑/动画方向判断混乱。
    private static final int PAGE_HOME = 0;
    private static final int PAGE_FILEMGR = 1;
    private static final int PAGE_ABOUT = 2;
    private static final int PAGE_UPDATE = 8;
    /** 远程检查状态：0 未检查 / 1 检查中 / 2 检查完成无新版 / 4 检查失败 */
    private int updState = 0;
    private String updError = "";
    /** 扫描到的全部可用版本（按版本号倒序，第 0 个为最新） */
    private java.util.List<ReleaseInfo> updReleases = new java.util.ArrayList<>();
    /** 本机缓存中可安装的更新包（比当前版本新） */
    private File updCachedApk;
    /** 已弹过更新提示的版本号：同一版本不在本次会话内重复打扰 */
    private String autoPromptedVer = "";
    /** 开源仓库地址（与 README / LICENSE 一致） */
    private static final String OPEN_SOURCE_URL = "https://github.com/haoyou999/HY_VQ";
    private static final int PAGE_SETTINGS = 3;
    private static final int PAGE_ACCOUNT = 4;
    private static final int PAGE_MODULE_SETTINGS = 6;
    private static final int PAGE_PERMISSIONS = 7;
    private static final int PAGE_MODULE_BASE = 100;

    private ActivityMainBinding binding;
    private ActionBarDrawerToggle toggle;

    private View homeView, settingsView, accountView, moduleSettingsView, permissionsView, aboutView, updateView;
    private ViewGroup contentFrame;
    private SharedPreferences prefs;
    private SignatureManager signatureManager;

    private int currentPageIndex = PAGE_HOME;
    private int nextModulePageIndex = PAGE_MODULE_BASE;
    /** 当前正在展示的模块 id（非模块页时为 null），用于统一返回逻辑 */
    private String currentModuleId = null;
    /** 文件管理：内置一级功能，2026-09-06 起从模块体系剥离为非模块 */
    private FileManagerModule fileManager;

    // ── 模块系统 ──
    private ModuleRegistry moduleRegistry = new ModuleRegistry();
    private ModuleServices moduleServices;
    private LinearLayout drawerModuleSlot;

    private ImageView drawerAvatar, editAvatar;
    private View themeOverlay;

    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private TextView drawerUsername;
    private String avatarPath;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable hideHintRunnable;

    private final List<Dialog> activeDialogs = new ArrayList<>();

    // ── 房间联机（局域网 P2P 直连：UDP 组播发现 + TCP 直连，纯 Java Socket，无账号体系）──
    private TerracottaUiController terracottaUi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 应用主题色叠加（仿 ZhuFiler：7 套 ThemeOverlay + Material You 动态色）
        ThemeHelper.applyThemeColor(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 2026-09-06: 主题模式固定为浅色（个性化功能已整体移除，待后续版本恢复）
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);

        prefs = getSharedPreferences("app_settings", MODE_PRIVATE);
        signatureManager = new SignatureManager(this);
        avatarPath = prefs.getString("avatar_path", "");

        // 首次启动检查
        if (!prefs.getBoolean("has_logged_in", false)) {
            Intent intent = new Intent(this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }

        // 规范：新功能先检查所需运行时权限，缺少则向系统申请
        requestRuntimePermissions();

        contentFrame = binding.contentFrame;
        drawerModuleSlot = binding.navView.findViewById(R.id.drawer_module_slot);

        setupToolbar();
        setupDrawer();
        setupDrawerNavigation();
        // 房间联机开关：按设置显示/隐藏抽屉入口
        applyTerracottaFeature();
        setupBackNavigation();
        initModuleSystem();
        // 增量更新引擎：启动兜底清理 + 加载更新包入口（幂等）
        UpdateManager.boot(this);

        // 初始加载
        handler.postDelayed(this::switchToHome, 50);
        updateDrawerUsername();
    }

    // ==================== 运行时权限（规范：新功能先检查权限，缺则向系统申请） ====================

    /** 通知权限：targetSdk 33+ 不申请，前台服务通知会被系统静默隐藏 */
    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 规范：申请成功后不弹窗；仅同步权限页状态展示
        if (requestCode == REQ_NOTIFICATION) {
            if (permissionsView != null && permissionsView.getParent() != null) {
                refreshPermissionsView();
            }
        }
    }

    // ==================== 模块系统 ====================
    private void initModuleSystem() {
        moduleServices = new ModuleServices() {
            @Override public String getUid() { return signatureManager.getUid(); }
            @Override public String getUsername() { return signatureManager.getUsername(); }
            @Override public String getUserSignature() { return prefs.getString("signature", "未设置签名"); }
            @Override public SharedPreferences getModulePrefs(String moduleId) {
                return getSharedPreferences("module_" + moduleId, MODE_PRIVATE);
            }
            @Override public void navigateTo(String page) {
                switch (page) {
                    case "home": switchToHome(); break;
                    case "settings": switchToSettings(); break;
                    case "account": switchToAccount(); break;
                    default: Toast.makeText(MainActivity.this, "未知页面: " + page, Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void requestDrawerRebuild() {
                handler.post(() -> rebuildDrawerModuleSlot());
            }
        };
        moduleRegistry.setDrawerCallback(modules -> handler.post(() -> rebuildDrawerModuleSlot()));
        // 2026-09-06: 聊天模块暂时下线（只保留「文件管理」插件，待后续版本恢复）
        // 文件管理：内置一级功能（非模块）。从模块体系剥离后不再注册到 ModuleRegistry，
        // 因此不出现在模块列表、不受模块启停控制，由侧边栏/首页入口直接打开。
        fileManager = new FileManagerModule();
        fileManager.onAttach(this, moduleServices);
        // 加载已持久化的外置模块
        loadInstalledModules();
        // 首次构建侧边栏模块区
        handler.postDelayed(this::rebuildDrawerModuleSlot, 100);
        // 启动时自动检查更新（可在「设置 → 软件更新」关闭）
        autoCheckUpdateOnLaunch();
    }

    private void rebuildDrawerModuleSlot() {
        if (drawerModuleSlot == null) return;
        // 2026-09-06: 侧边栏模块入口整体隐藏（只保留文件管理，由首页卡片直达）
        drawerModuleSlot.removeAllViews();
        drawerModuleSlot.setVisibility(View.GONE);
        if (true) return;
        List<HyVqModule> drawerMods = moduleRegistry.getDrawerModules();
        if (drawerMods.isEmpty()) {
            drawerModuleSlot.setVisibility(View.GONE);
        } else {
            drawerModuleSlot.setVisibility(View.VISIBLE);
            for (HyVqModule m : drawerMods) {
                View item = LayoutInflater.from(this).inflate(R.layout.item_drawer_module, drawerModuleSlot, false);
                item.setTag(m); // 供 updateDrawerSelection 匹配选中态
                ImageView icon = item.findViewById(R.id.drawer_item_icon);
                TextView text = item.findViewById(R.id.drawer_item_text);
                View dot = item.findViewById(R.id.drawer_item_dot);
                text.setText(m.name);
                // 模块图标按名称解析（模块 UI 适配：外置模块无需自带图标资源）
                icon.setImageResource(ModuleUiKit.iconRes(this, m.icon, R.drawable.ic_chat));
                dot.setVisibility(m.enabled ? View.VISIBLE : View.GONE);
                item.setOnClickListener(v -> {
                    binding.drawerLayout.closeDrawers();
                    binding.drawerLayout.postDelayed(() -> switchToModule(m), 160);
                });
                drawerModuleSlot.addView(item);
            }
        }
        updateDrawerSelection();
    }

    /**
     * 根据当前页面更新侧边栏选中态：
     * 首页高亮仅当 currentPageIndex == PAGE_HOME；模块项高亮仅当 currentModuleId 匹配。
     * 所有页面切换后都会调用，保证边栏状态与实际页面同步。
     */
    private void updateDrawerSelection() {
        if (binding == null) return;
        boolean homeSelected = currentPageIndex == PAGE_HOME && currentModuleId == null;
        View navHome = binding.navView.findViewById(R.id.nav_home);
        if (navHome != null) {
            navHome.setBackgroundResource(homeSelected ? R.drawable.bg_nav_item_selected : android.R.color.transparent);
            ImageView icon = navHome.findViewById(R.id.nav_home_icon);
            TextView text = navHome.findViewById(R.id.nav_home_text);
            int tint = resolveAttr(homeSelected
                    ? com.google.android.material.R.attr.colorOnPrimaryContainer
                    : com.google.android.material.R.attr.colorOnSurfaceVariant);
            if (icon != null) icon.setColorFilter(tint);
            if (text != null) text.setTextColor(tint);
        }
        // 文件管理一级入口选中态
        View navFm = binding.navView.findViewById(R.id.nav_filemgr);
        if (navFm != null) {
            boolean fmSelected = currentPageIndex == PAGE_FILEMGR;
            navFm.setBackgroundResource(fmSelected
                    ? R.drawable.bg_nav_item_selected : R.drawable.bg_nav_item_default);
            ImageView fmIcon = navFm.findViewById(R.id.nav_filemgr_icon);
            TextView fmText = navFm.findViewById(R.id.nav_filemgr_text);
            int fmTint = resolveAttr(fmSelected
                    ? com.google.android.material.R.attr.colorOnPrimaryContainer
                    : com.google.android.material.R.attr.colorOnSurfaceVariant);
            if (fmIcon != null) fmIcon.setColorFilter(fmTint);
            if (fmText != null) fmText.setTextColor(fmTint);
        }
        if (drawerModuleSlot != null) {
            for (int i = 0; i < drawerModuleSlot.getChildCount(); i++) {
                View item = drawerModuleSlot.getChildAt(i);
                Object tag = item.getTag();
                boolean selected = tag instanceof HyVqModule
                        && currentModuleId != null
                        && currentModuleId.equals(((HyVqModule) tag).id);
                item.setBackgroundResource(selected ? R.drawable.bg_nav_item_selected : android.R.color.transparent);
            }
        }
    }

    private void switchToModule(HyVqModule m) {
        if (m == null) return;
        View moduleView = m.createMainView(LayoutInflater.from(this), contentFrame);
        if (moduleView == null) return;
        currentModuleId = m.id;
        // 每个模块分配唯一动态页码，与个性化页(5)不再冲突
        switchContent(moduleView, nextModulePageIndex++);
        resetToolbar();
        binding.toolbarTitle.setText(m.name);
    }

    // ==================== 关于页 ====================

    private void switchToAbout() {
        if (aboutView == null) {
            aboutView = LayoutInflater.from(this).inflate(R.layout.fragment_about, contentFrame, false);
            setupAboutView();
        }
        switchContent(aboutView, PAGE_ABOUT);
        setSubpageToolbar("关于");
    }

    private void setupAboutView() {
        if (aboutView == null) return;
        int code = 0;
        try {
            code = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception ignored) {
        }
        TextView tvVer = aboutView.findViewById(R.id.tv_about_version);
        if (tvVer != null) tvVer.setText("v" + baseVersionName() + " (code " + code + ")");
        TextView tvRepo = aboutView.findViewById(R.id.tv_about_repo);
        if (tvRepo != null) tvRepo.setText(OPEN_SOURCE_URL.replace("https://", ""));

        TextView tvCredits = aboutView.findViewById(R.id.tv_about_credits);
        if (tvCredits != null) {
            tvCredits.setText(String.join(System.lineSeparator(), new String[]{
                    "Material Files —— 目录滚动位置记忆的设计思路",
                    "Terracotta —— 局域网 P2P 直连运行库",
                    "Material Components for Android —— Material 3 组件",
                    "AndroidX —— 基础支持库"
            }));
        }
        TextView tvDisc = aboutView.findViewById(R.id.tv_about_disclaimer);
        if (tvDisc != null) {
            tvDisc.setText(String.join(System.lineSeparator(), new String[]{
                    "本软件全部代码由 AI 智能体编写，人类作者仅提出需求与验收结果。",
                    "",
                    "AI 生成的代码不保证正确性、安全性、稳定性与适用性，可能存在未发现的缺陷、性能问题或安全隐患。",
                    "",
                    "涉及文件操作（含 Root 权限）与外部模块动态加载，使用前请自行审阅代码并备份重要数据。",
                    "",
                    "本软件按「现状」提供，不提供任何形式担保；因使用造成的任何损失由使用者自行承担。"
            }));
        }
        TextView tvCr = aboutView.findViewById(R.id.tv_about_copyright);
        if (tvCr != null) {
            tvCr.setText(String.join(System.lineSeparator(), new String[]{
                    "© 2026 HY_VQ · AI 编写",
                    "以 GNU GPL v3.0 协议开源，不提供任何担保",
                    "可自由使用、修改与再分发，衍生作品须采用同一协议"
            }));
        }
        aboutView.findViewById(R.id.item_about_repo).setOnClickListener(v -> openUrl(OPEN_SOURCE_URL));
        aboutView.findViewById(R.id.item_about_license).setOnClickListener(v -> showLicenseDialog());
        aboutView.findViewById(R.id.item_about_changelog).setOnClickListener(v -> switchToUpdate());
    }

    /** 用系统浏览器打开链接 */
    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开链接：" + url, Toast.LENGTH_SHORT).show();
        }
    }

    /** 开源许可证说明（GPL-3.0 要点 + 跳转全文） */
    private void showLicenseDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(this, "📜 开源许可证"));
        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setLineSpacing(0, 1.45f);
        tv.setTextColor(ModuleUiKit.color(this, com.google.android.material.R.attr.colorOnSurface));
        int pad = dp2(4);
        tv.setPadding(pad, pad, pad, pad);
        tv.setText(String.join(System.lineSeparator(), new String[]{
                "GNU General Public License v3.0",
                "",
                "你可以自由地：",
                "· 将本软件用于任何目的",
                "· 研究并修改源代码",
                "· 再分发副本",
                "",
                "但必须遵守：",
                "· 公开发布修改后的源码",
                "· 衍生作品同样采用 GPL-3.0",
                "· 保留版权与许可声明",
                "· 作者不提供任何担保"
        }));
        box.addView(tv);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        android.app.Dialog d = ModuleUiKit.glassDialog(this, box);
        btns.addView(updateTextButton("查看全文", v -> {
            d.dismiss();
            openUrl(OPEN_SOURCE_URL + "/blob/main/LICENSE");
        }));
        btns.addView(updateTextButton("知道了", v -> d.dismiss()));
        box.addView(btns);
        d.show();
    }

    /** 打开文件管理：内置一级功能（非模块） */
    private void openFileManager() {
        if (fileManager == null) {
            fileManager = new FileManagerModule();
            fileManager.onAttach(this, moduleServices);
        }
        View v = fileManager.createMainView(LayoutInflater.from(this), contentFrame);
        if (v == null) return;
        currentModuleId = null;      // 非模块：不参与模块选中态
        switchContent(v, PAGE_FILEMGR);
        resetToolbar();
        binding.toolbarTitle.setText("文件管理");
        updateDrawerSelection();
    }

    private void switchToModuleSettings() {
        if (moduleSettingsView == null) {
            moduleSettingsView = LayoutInflater.from(this).inflate(R.layout.fragment_module_settings, contentFrame, false);
            setupModuleSettingsView();
        }
        switchContent(moduleSettingsView, PAGE_MODULE_SETTINGS);
        setSubpageToolbar("模块设置");
        refreshModuleList();
    }

    private RecyclerView recyclerModules;
    private TextView tvModuleCount, tvEmptyHint;

    private void setupModuleSettingsView() {
        if (moduleSettingsView == null) return;
        moduleSettingsView.findViewById(R.id.btn_install_module).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("application/zip");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQ_INSTALL_MODULE);
        });

        recyclerModules = moduleSettingsView.findViewById(R.id.recycler_modules);
        tvModuleCount = moduleSettingsView.findViewById(R.id.tv_module_count);
        tvEmptyHint = moduleSettingsView.findViewById(R.id.tv_empty_hint);
        recyclerModules.setLayoutManager(new LinearLayoutManager(this));
        refreshModuleList();
    }

    private void refreshModuleList() {
        if (recyclerModules == null) return;
        Map<String, HyVqModule> all = moduleRegistry.getAllModules();
        List<HyVqModule> list = new ArrayList<>(all.values());
        recyclerModules.setAdapter(new ModuleAdapter(list));
        int count = list.size();
        tvModuleCount.setText(count + " 个");
        tvEmptyHint.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
    }

    private class ModuleAdapter extends RecyclerView.Adapter<ModuleAdapter.VH> {
        private final List<HyVqModule> data;
        ModuleAdapter(List<HyVqModule> data) { this.data = data; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_module_card, parent, false);
            return new VH(v);
        }
        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            HyVqModule m = data.get(pos);
            h.name.setText(m.name);
            h.summary.setText(m.getSummary());
            h.badge.setVisibility(m.isBuiltIn ? View.VISIBLE : View.GONE);
            h.btnUninstall.setVisibility(View.VISIBLE); // 内置也可卸载(需确认)
            // 启用开关：初始状态不触发监听，避免误写持久化
            h.switchEnable.setOnCheckedChangeListener(null);
            h.switchEnable.setChecked(m.enabled);
            h.switchEnable.setOnCheckedChangeListener((btn, checked) -> {
                m.enabled = checked;
                if (checked) moduleRegistry.enable(m.id);
                else moduleRegistry.disable(m.id);
                // 持久化启用状态（内置模块同样记录，重启后恢复）
                prefs.edit().putBoolean("module_enabled_" + m.id, checked).apply();
                refreshModuleList();
                rebuildDrawerModuleSlot();
                Toast.makeText(MainActivity.this,
                        (checked ? "已启用 " : "已禁用 ") + m.name, Toast.LENGTH_SHORT).show();
            });
            h.btnUninstall.setOnClickListener(v -> {
                // 内置也可卸载;卸载流程清理全部残余(记录/prefs/模块目录)
                removeModuleRecord(m.id);
                getSharedPreferences("module_" + m.id, MODE_PRIVATE).edit().clear().apply();
                getSharedPreferences("module_enabled_" + m.id, MODE_PRIVATE).edit().clear().apply();
                deleteRecursive(new File(getDir("modules", MODE_PRIVATE), m.id));
                moduleRegistry.uninstall(m.id);
                refreshModuleList();
                rebuildDrawerModuleSlot();
                Toast.makeText(MainActivity.this, "已卸载 " + m.name, Toast.LENGTH_SHORT).show();
            });
        }
        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView name, summary;
            View badge, btnUninstall;
            com.google.android.material.switchmaterial.SwitchMaterial switchEnable;
            VH(View v) {
                super(v);
                name = v.findViewById(R.id.item_name);
                summary = v.findViewById(R.id.item_summary);
                badge = v.findViewById(R.id.tv_builtin_badge);
                btnUninstall = v.findViewById(R.id.btn_uninstall);
                switchEnable = v.findViewById(R.id.switch_enable);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SAF_MOUNT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            // 文件管理器"挂载"：持久化 SAF 授权（MT 管理器同款"允许访问"），并通知模块刷新侧边栏
            // 关键（AOSP 硬性限制，真机证实 2026-08-16）：takePersistableUriPermission 只接受
            // READ|WRITE 两个标志！带 FLAG_GRANT_PREFIX_URI_PERMISSION(0x40) 必抛
            // IllegalArgumentException（ActivityManagerService 源码检查
            // "Can only take persistable grants for read or write permission"）。
            // 授权 intent 里的 PREFIX 是临时 grant 标志（覆盖整棵子树的运行时权限），不能持久化；
            // 持久化的 tree grant 本身就是整棵子树授权（DocumentsProvider 按 tree 模型检查，
            // 子 document 天然覆盖，不依赖 PREFIX）。此前"缺 PREFIX 子目录被拒"实为误判：
            // 真实原因是带 PREFIX 持久化必失败 → 异常被静默吞掉 → 整个授权未持久化。
            try {
                int flags = data.getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(data.getData(), flags);
                Toast.makeText(this, "授权已持久化，目录已挂载", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(this, "授权持久化失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
            FileManagerModule.onSafGrantedStatic();
        } else if (requestCode == REQ_PICK_AVATAR && resultCode == RESULT_OK && data != null && data.getData() != null) {
            saveAvatar(data.getData());
        } else if (requestCode == REQ_INSTALL_MODULE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            installModuleFromUri(data.getData());
        }
    }

    // ==================== 房间联机（局域网 P2P 直连，FCL 式房间号连接）====================

    /** 房间联机开关：按设置控制抽屉入口显隐（默认关闭：网络服务器暂时搁置） */
    private void applyTerracottaFeature() {
        if (binding == null) return;
        boolean enabled = prefs.getBoolean("feature_terracotta_enabled", false);
        View tcBtn = binding.navView.findViewById(R.id.nav_terracotta);
        if (tcBtn != null) tcBtn.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    /** 打开房间联机入口：标准 P2P 流程——直接打开对话框（房间号开房/加入），
     *  局域网 UDP 组播发现 + TCP 直连，纯 Java Socket，无需 VPN/任何系统 API */
    private void showTerracotta() {
        if (!prefs.getBoolean("feature_terracotta_enabled", false)) return;
        openTerracottaDialog();
    }

    /** 真正打开房间联机对话框（状态机驱动：等待→进度→成功/异常） */
    private void openTerracottaDialog() {
        if (terracottaUi == null) {
            terracottaUi = new TerracottaUiController(this,
                    signatureManager != null ? signatureManager.getUsername() : null);
        }
        terracottaUi.show();
    }

    private void installModuleFromUri(Uri uri) {
        File tempDir = new File(getFilesDir(), "temp_module");
        if (tempDir.exists()) deleteRecursive(tempDir);
        tempDir.mkdirs();

        // 解压 zip 到临时目录
        try (InputStream is = getContentResolver().openInputStream(uri);
             ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                File outFile = new File(tempDir, entry.getName());
                outFile.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = zis.read(buf)) > 0) {
                        fos.write(buf, 0, len);
                    }
                }
                zis.closeEntry();
            }
        } catch (Exception e) {
            Toast.makeText(this, "解压失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        // 读取 module.json
        File jsonFile = new File(tempDir, "module.json");
        if (!jsonFile.exists()) {
            Toast.makeText(this, "模块包中未找到 module.json", Toast.LENGTH_LONG).show();
            return;
        }
        String moduleId, moduleName, mainClass;
        boolean showInDrawer = true;
        try (java.io.FileInputStream fis = new java.io.FileInputStream(jsonFile)) {
            byte[] jsonBytes = new byte[(int) jsonFile.length()];
            int off = 0;
            while (off < jsonBytes.length) {   // read 不保证一次读满，需循环
                int n = fis.read(jsonBytes, off, jsonBytes.length - off);
                if (n < 0) throw new java.io.IOException("文件提前结束");
                off += n;
            }
            String jsonStr = new String(jsonBytes, "UTF-8");
            JSONObject json = new JSONObject(jsonStr);
            moduleId = json.optString("id", "");
            moduleName = json.optString("name", "未知模块");
            mainClass = json.optString("mainClass", "");
            showInDrawer = json.optBoolean("showInDrawer", true);
            if (moduleId.isEmpty() || mainClass.isEmpty()) {
                Toast.makeText(this, "module.json 中 id/mainClass 不能为空", Toast.LENGTH_LONG).show();
                return;
            }
        } catch (Exception e) {
            Toast.makeText(this, "读取 module.json 失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        // 检查是否已安装
        if (moduleRegistry.get(moduleId) != null) {
            Toast.makeText(this, "模块 " + moduleId + " 已安装", Toast.LENGTH_SHORT).show();
            return;
        }

        // 安装到永久目录
        File installDir = new File(getDir("modules", MODE_PRIVATE), moduleId);
        if (installDir.exists()) deleteRecursive(installDir);
        installDir.mkdirs();
        File[] tempFiles = tempDir.listFiles();
        if (tempFiles != null) for (File f : tempFiles) {
            f.renameTo(new File(installDir, f.getName()));
        }

        // 用 DexClassLoader 加载模块
        File dexFile = new File(installDir, "classes.dex");
        if (!dexFile.exists()) {
            Toast.makeText(this, "未找到 classes.dex", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            DexClassLoader loader = new DexClassLoader(
                dexFile.getAbsolutePath(),
                getDir("dexopt", MODE_PRIVATE).getAbsolutePath(),
                null,
                getClassLoader()
            );
            Class<?> clz = loader.loadClass(mainClass);
            HyVqModule module = (HyVqModule) clz.newInstance();
            module.id = moduleId;
            module.name = moduleName;
            module.showInDrawer = showInDrawer;
            module.onAttach(this, moduleServices);
            moduleRegistry.install(module);
            saveModuleRecord(moduleId, moduleName, mainClass, showInDrawer, installDir.getAbsolutePath());
            refreshModuleList();
            rebuildDrawerModuleSlot();
            Toast.makeText(this, "模块 " + moduleName + " 安装成功", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "加载模块失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ── 模块持久化 ──

    private void saveModuleRecord(String id, String name, String mainClass, boolean showInDrawer, String installPath) {
        try {
            JSONArray arr = getModuleRecords();
            // 去重
            for (int i = arr.length() - 1; i >= 0; i--) {
                if (arr.getJSONObject(i).optString("id", "").equals(id)) {
                    arr.remove(i);
                }
            }
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("name", name);
            obj.put("mainClass", mainClass);
            obj.put("showInDrawer", showInDrawer);
            obj.put("installPath", installPath);
            arr.put(obj);
            prefs.edit().putString("installed_modules", arr.toString()).apply();
        } catch (JSONException e) {
            Log.w("HyVqMain", "保存模块记录失败", e);
        }
    }

    private void removeModuleRecord(String id) {
        try {
            JSONArray arr = getModuleRecords();
            for (int i = arr.length() - 1; i >= 0; i--) {
                if (arr.getJSONObject(i).optString("id", "").equals(id)) {
                    arr.remove(i);
                }
            }
            prefs.edit().putString("installed_modules", arr.toString()).apply();
        } catch (JSONException e) {
            Log.w("HyVqMain", "移除模块记录失败", e);
        }
    }

    private JSONArray getModuleRecords() {
        String raw = prefs.getString("installed_modules", "[]");
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private void loadInstalledModules() {
        JSONArray arr = getModuleRecords();
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject obj = arr.getJSONObject(i);
                String id = obj.optString("id", "");
                String name = obj.optString("name", "未知模块");
                String mainClass = obj.optString("mainClass", "");
                boolean showInDrawer = obj.optBoolean("showInDrawer", true);
                String installPath = obj.optString("installPath", "");
                if (id.isEmpty() || mainClass.isEmpty() || installPath.isEmpty()) continue;
                // 检查是否已在内存中
                if (moduleRegistry.get(id) != null) continue;
                File dexFile = new File(installPath, "classes.dex");
                if (!dexFile.exists()) {
                    // 文件已丢失，清理记录
                    continue;
                }
                try {
                    DexClassLoader loader = new DexClassLoader(
                        dexFile.getAbsolutePath(),
                        getDir("dexopt", MODE_PRIVATE).getAbsolutePath(),
                        null,
                        getClassLoader()
                    );
                    Class<?> clz = loader.loadClass(mainClass);
                    HyVqModule module = (HyVqModule) clz.newInstance();
                    module.id = id;
                    module.name = name;
                    module.showInDrawer = showInDrawer;
                    // 恢复持久化的启用状态（默认启用）
                    module.enabled = prefs.getBoolean("module_enabled_" + id, true);
                    module.onAttach(this, moduleServices);
                    moduleRegistry.install(module);
                } catch (Exception e) {
                    // 加载失败不影响其他模块
                }
            } catch (JSONException ignored) {}
        }
    }


    private void deleteRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDir.delete();
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        if (toggle != null) toggle.syncState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 广播模块生命周期（如聊天模块停止服务）
        moduleRegistry.notifyPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        moduleRegistry.notifyResume();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 关闭联机对话框，防止窗口泄漏（重新打开会由状态机恢复视图）
        if (terracottaUi != null && terracottaUi.isShowing()) {
            terracottaUi.dismiss();
        }
        // 销毁时关闭所有活跃 Dialog，防止窗口泄漏
        for (Dialog d : activeDialogs) {
            if (d != null && d.isShowing()) {
                try { d.dismiss(); } catch (Exception ignored) {}
            }
        }
        activeDialogs.clear();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // P2P 联机会话彻底收尾：断开连接 + 释放组播锁（防止退出后后台线程存活）
        HyVqP2pBridge.stop();
        handler.removeCallbacksAndMessages(null);
        if (contentFrame != null) {
            for (int i = 0; i < contentFrame.getChildCount(); i++) {
                View child = contentFrame.getChildAt(i);
                if (child != null) child.animate().cancel();
            }
        }
        if (cachedAvatarBmp != null && !cachedAvatarBmp.isRecycled()) {
            cachedAvatarBmp.recycle();
            cachedAvatarBmp = null;
        }
        homeView = null;
        settingsView = null;
        accountView = null;
        aboutView = null;
        updateView = null;
        moduleSettingsView = null;
        permissionsView = null;
        hideHintRunnable = null;
        if (themeOverlay != null) {
            themeOverlay.animate().cancel();
            ViewGroup decorView = (ViewGroup) getWindow().getDecorView();
            decorView.removeView(themeOverlay);
            themeOverlay = null;
        }
        // 通知所有模块销毁
        moduleRegistry.detachAll();
        binding = null;
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (toggle != null) toggle.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull android.view.MenuItem item) {
        if (toggle != null && toggle.onOptionsItemSelected(item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ==================== Toolbar ====================
    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        // 自定义返回按钮：所有返回入口统一走 goBack()
        binding.btnBackCustom.setVisibility(View.GONE);
        binding.btnBackCustom.setOnClickListener(v -> goBack());
    }



    private void setToolbarTitle(String title) {
        binding.toolbarTitle.setText(title);
    }

    private void resetToolbar() {
        binding.btnBackCustom.setVisibility(View.GONE);
        binding.btnBackCustom.setOnClickListener(null);
        binding.btnMenuHome.setVisibility(View.GONE);
        binding.toolbarTitle.setText("HY_VQ");
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
        if (toggle != null) toggle.setDrawerIndicatorEnabled(true);
    }

    private void setSubpageToolbar(String title) {
        if (toggle != null) toggle.setDrawerIndicatorEnabled(false);
        binding.btnBackCustom.setVisibility(View.VISIBLE);
        binding.btnBackCustom.setOnClickListener(v -> goBack());
        binding.btnMenuHome.setVisibility(View.GONE);
        binding.toolbarTitle.setText(title);
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
    }

    /**
     * 统一返回逻辑：抽屉打开时先关抽屉；
     * 子页（账号/个性化/模块设置）→ 设置；模块页 → 首页；
     * 设置页 → 首页；其余交给系统默认返回。
     *
     * @return true 表示已消费返回事件
     */
    private boolean goBack() {
        if (binding == null) return false;
        if (binding.drawerLayout.isDrawerOpen(Gravity.START)) {
            binding.drawerLayout.closeDrawers();
            return true;
        }
        if (accountView != null && accountView.getParent() != null) {
            switchToSettings();
            return true;
        }
        if (aboutView != null && aboutView.getParent() != null) {
            switchToSettings();
            return true;
        }
        if (updateView != null && updateView.getParent() != null) {
            switchToSettings();
            return true;
        }
        if (moduleSettingsView != null && moduleSettingsView.getParent() != null) {
            switchToSettings();
            return true;
        }
        if (permissionsView != null && permissionsView.getParent() != null) {
            switchToSettings();
            return true;
        }
        if (currentModuleId != null) {
            switchToHome();
            return true;
        }
        if (currentPageIndex == PAGE_SETTINGS) {
            switchToHome();
            return true;
        }
        return false;
    }

    // ==================== Drawer ====================
    private void setupDrawer() {
        toggle = new ActionBarDrawerToggle(
                this, binding.drawerLayout, binding.toolbar,
                R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        binding.drawerLayout.addDrawerListener(toggle);
        binding.drawerLayout.setScrimColor(0x33000000);

        // 自定义侧边栏宽度（屏幕宽度的一半）
        DrawerLayout.LayoutParams params = (DrawerLayout.LayoutParams) binding.navView.getLayoutParams();
        params.width = getResources().getDisplayMetrics().widthPixels / 2;
        binding.navView.setLayoutParams(params);

        binding.drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerSlide(View drawerView, float offset) {
                // 侧边栏滑出时对主内容区动态模糊（Android 12+）
                ViewGroup blurTarget = contentFrame != null ? (ViewGroup) contentFrame.getParent() : null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurTarget != null) {
                    float radius = offset * 25f;
                    if (radius > 0.5f) {
                        blurTarget.setRenderEffect(
                            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
                    } else {
                        blurTarget.setRenderEffect(null);
                    }
                }
            }
            @Override
            public void onDrawerClosed(View drawerView) {
                ViewGroup blurTarget = contentFrame != null ? (ViewGroup) contentFrame.getParent() : null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurTarget != null) {
                    blurTarget.setRenderEffect(null);
                }
            }
        });
        // 底部全面屏手势条适配：侧边栏底部内收避免被手势条遮挡
        ViewCompat.setOnApplyWindowInsetsListener(binding.navView, (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottom);
            return insets;
        });
        // 头部引用
        View headerView = binding.navView.findViewWithTag("header");
        if (headerView == null) {
            // include 标签内的视图直接从 navView 中查找
            drawerUsername = binding.navView.findViewById(R.id.tv_username);
            drawerAvatar = binding.navView.findViewById(R.id.iv_avatar);
            TextView uidView = binding.navView.findViewById(R.id.tv_uid);
            if (uidView != null && signatureManager != null) {
                uidView.setText("UID: " + signatureManager.getUid());
            }
            if (drawerAvatar != null) {
                drawerAvatar.setOnClickListener(v -> {
                    binding.drawerLayout.closeDrawers();
                    binding.drawerLayout.postDelayed(this::showEditProfileDialog, 160);
                });
            }
        }
        loadAvatar(drawerAvatar);
    }

    private void updateDrawerUsername() {
        if (drawerUsername != null) {
            drawerUsername.setText(signatureManager.getUsername());
        }
    }

    private void setupDrawerNavigation() {
        // 首页
        binding.navView.findViewById(R.id.nav_home).setOnClickListener(v -> {
            binding.drawerLayout.closeDrawers();
            binding.drawerLayout.postDelayed(this::switchToHome, 160);
        });
        // 文件管理（内置一级功能，非模块）
        View fmBtn = binding.navView.findViewById(R.id.nav_filemgr);
        if (fmBtn != null) {
            fmBtn.setOnClickListener(v -> {
                binding.drawerLayout.closeDrawers();
                binding.drawerLayout.postDelayed(this::openFileManager, 160);
            });
        }
        // 房间联机（局域网直连固定入口）
        View tcBtn = binding.navView.findViewById(R.id.nav_terracotta);
        if (tcBtn != null) {
            tcBtn.setOnClickListener(v -> {
                binding.drawerLayout.closeDrawers();
                binding.drawerLayout.postDelayed(this::showTerracotta, 160);
            });
        }
        // 设置（底部按钮）
        binding.navView.findViewById(R.id.nav_settings).setOnClickListener(v -> {
            binding.drawerLayout.closeDrawers();
            binding.drawerLayout.postDelayed(this::switchToSettings, 160);
        });
        // 模块设置（底部按钮）
        View moduleSettingsBtn = binding.navView.findViewById(R.id.nav_module_settings);
        if (moduleSettingsBtn != null) {
            moduleSettingsBtn.setOnClickListener(v -> {
                binding.drawerLayout.closeDrawers();
                binding.drawerLayout.postDelayed(this::switchToModuleSettings, 160);
            });
        }
    }

    // ==================== Avatar ====================
    private Bitmap getCircleBitmap(Bitmap src) {
        if (src == null) return null;
        int size = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - size) / 2;
        int y = (src.getHeight() - size) / 2;
        Bitmap squared = Bitmap.createBitmap(src, x, y, size, size);
        if (squared != src) src.recycle();

        Bitmap output = null;
        try {
            output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Rect rect = new Rect(0, 0, size, size);
            RectF rectF = new RectF(rect);
            canvas.drawOval(rectF, paint);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(squared, rect, rect, paint);
        } catch (Exception e) {
            if (output != null && !output.isRecycled()) {
                output.recycle();
            }
            output = null;
        } finally {
            // 确保 squared 在异常路径下也能被回收
            if (squared != null && !squared.isRecycled()) {
                squared.recycle();
            }
        }
        return output;
    }

    /** 缓存已解码的头像 Bitmap，避免重复解码和内存泄漏 */
    private Bitmap cachedAvatarBmp;
    private final Object avatarLock = new Object();

    private void loadAvatar(ImageView target) {
        if (target == null) return;
        target.clearColorFilter();
        target.setImageTintList(null);
        if (avatarPath != null && !avatarPath.isEmpty() && new File(avatarPath).exists()) {
            synchronized (avatarLock) {
                if (cachedAvatarBmp == null || cachedAvatarBmp.isRecycled()) {
                    cachedAvatarBmp = BitmapFactory.decodeFile(avatarPath);
                }
                if (cachedAvatarBmp != null) {
                    target.setImageBitmap(cachedAvatarBmp);
                    return;
                }
            }
        }
        target.setImageResource(R.drawable.ic_mine);
        target.setColorFilter(resolveAttr(com.google.android.material.R.attr.colorPrimary));
    }

    private void refreshAllAvatars() {
        loadAvatar(drawerAvatar);
        loadAvatar(editAvatar);
    }

    private void pickAvatar() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQ_PICK_AVATAR);
    }

    private void saveAvatar(Uri uri) {
        try {
            Bitmap raw;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                raw = BitmapFactory.decodeStream(in);
            }
            if (raw == null) {
                Toast.makeText(this, "无法读取图片", Toast.LENGTH_SHORT).show();
                return;
            }
            Bitmap circle = getCircleBitmap(raw);

            File destFile = new File(getFilesDir(), "avatar.png");
            try (FileOutputStream out = new FileOutputStream(destFile)) {
                circle.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            circle.recycle();
            avatarPath = destFile.getAbsolutePath();
            prefs.edit().putString("avatar_path", avatarPath).apply();
            // 刷新头像缓存：释放旧 Bitmap，让下次 loadAvatar 重新解码新图片
            synchronized (avatarLock) {
                if (cachedAvatarBmp != null && !cachedAvatarBmp.isRecycled()) {
                    cachedAvatarBmp.recycle();
                }
                cachedAvatarBmp = null;
            }
            refreshAllAvatars();
            Toast.makeText(this, "头像已更新", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存头像失败", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== Edit Profile Dialog ====================
    private void showEditProfileDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_edit_profile, null);
        dialog.setContentView(v);
        if (dialog.getWindow() != null) {
            Window w = dialog.getWindow();
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
            // 规范：所有浮窗统一走 ModuleUiKit.applyBlur（FLAG_BLUR_BEHIND + 半径）；
            // 只调 setBackgroundBlurRadius 不加 FLAG_BLUR_BEHIND 不会生效
            ModuleUiKit.applyBlur(w, this);
        }

        ImageView dialogAvatar = v.findViewById(R.id.dialog_avatar);
        EditText dialogName = v.findViewById(R.id.dialog_name);
        EditText dialogSignature = v.findViewById(R.id.dialog_signature);
        TextView dialogSaveHint = v.findViewById(R.id.dialog_save_hint);

        // 预填数据
        dialogName.setText(signatureManager.getUsername());
        dialogSignature.setText(prefs.getString("signature", ""));
        dialogSaveHint.setVisibility(View.GONE);
        loadAvatar(dialogAvatar);

        // 头像点击
        dialogAvatar.setOnClickListener(w -> {
            editAvatar = dialogAvatar; // 暂存引用供 onActivityResult 刷新
            pickAvatar();
        });

        // 取消
        v.findViewById(R.id.dialog_btn_cancel).setOnClickListener(w -> dialog.dismiss());

        // 保存
        v.findViewById(R.id.dialog_btn_save).setOnClickListener(w -> {
            String newName = dialogName.getText().toString().trim();
            String newSig = dialogSignature.getText().toString().trim();
            boolean changed = false;
            if (!newName.isEmpty()) {
                if (drawerUsername != null) drawerUsername.setText(newName);
                prefs.edit().putString("username", newName).apply();
                if (signatureManager != null) signatureManager.updateUsername(newName);
                changed = true;
            }
            if (!newSig.isEmpty()) {
                prefs.edit().putString("signature", newSig).apply();
                changed = true;
            }
            if (changed) {
                dialogSaveHint.setVisibility(View.VISIBLE);
                if (hideHintRunnable != null) handler.removeCallbacks(hideHintRunnable);
                hideHintRunnable = () -> dialogSaveHint.setVisibility(View.GONE);
                handler.postDelayed(hideHintRunnable, 3000);
            }
        });

        dialog.setOnDismissListener(d -> {
            activeDialogs.remove(dialog);
            editAvatar = null; // 释放弹窗头像引用
            if (toggle != null) toggle.setDrawerIndicatorEnabled(true);
        });
        activeDialogs.add(dialog);
        dialog.show();

        if (toggle != null) toggle.setDrawerIndicatorEnabled(false);
    }

    private void showSignatureDialog() {
        if (signatureManager == null) return;
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_signature, null);
        dialog.setContentView(v);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setGravity(Gravity.CENTER);
            // 规范：所有浮窗统一走 ModuleUiKit.applyBlur（FLAG_BLUR_BEHIND + 半径）；
            // 只调 setBackgroundBlurRadius 不加 FLAG_BLUR_BEHIND 不会生效
            ModuleUiKit.applyBlur(dialog.getWindow(), this);
        }
        TextView tvSig = v.findViewById(R.id.tv_signature);
        try {
            JSONObject sigObj = new JSONObject(signatureManager.getSignatureJson());
            tvSig.setText("用户名：" + sigObj.getString("username") + "\n"
                    + "UID：" + sigObj.getString("uid") + "\n"
                    + "UUID：" + sigObj.getString("uuid"));
        } catch (JSONException e) {
            tvSig.setText("签名数据异常");
        }
        v.findViewById(R.id.btn_copy).setOnClickListener(w -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("uid", signatureManager.getUid()));
            Toast.makeText(this, "UID 已复制", Toast.LENGTH_SHORT).show();
        });
        v.findViewById(R.id.btn_copy_uuid).setOnClickListener(w -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("uuid", signatureManager.getUuid()));
            Toast.makeText(this, "UUID 已复制", Toast.LENGTH_SHORT).show();
        });
        dialog.setOnDismissListener(d -> activeDialogs.remove(dialog));
        activeDialogs.add(dialog);
        dialog.show();
    }

    private int resolveAttr(int attrRes) {
        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(attrRes, typedValue, true);
        return typedValue.data;
    }

    // ==================== Page Switching ====================
    private void switchContent(View newView, int newPageIndex) {
        View oldView = contentFrame.getChildCount() > 0 ? contentFrame.getChildAt(0) : null;
        if (oldView == newView) return;

        if (oldView != null) oldView.animate().cancel();
        newView.animate().cancel();

        if (newView.getParent() instanceof ViewGroup) {
            ((ViewGroup) newView.getParent()).removeView(newView);
        }

        int width = contentFrame.getWidth();
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;

        // 同页跳转不播放平移动画
        boolean samePage = newPageIndex == currentPageIndex;
        float fromX = samePage ? 0 : (newPageIndex < currentPageIndex ? -width * 0.25f : width * 0.25f);

        contentFrame.removeAllViews();
        contentFrame.addView(newView);

        if (!samePage) {
            newView.setTranslationX(fromX);
            newView.setAlpha(0.4f);
            newView.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(260)
                    .setInterpolator(SI_EXP_EASE)
                    .start();
        }

        currentPageIndex = newPageIndex;
        // 切回固定页时清除模块页状态，保证返回逻辑正确
        if (newPageIndex < PAGE_MODULE_BASE) {
            currentModuleId = null;
        }
        // 同步侧边栏选中态
        updateDrawerSelection();
    }

    private void switchToHome() {
        if (homeView == null) {
            homeView = LayoutInflater.from(this).inflate(R.layout.fragment_home, contentFrame, false);
            setupHomeView();
        }
        switchContent(homeView, PAGE_HOME);
        resetToolbar();
    }

    private void setupHomeView() {
        homeView.findViewById(R.id.card_explore).setOnClickListener(v ->
                Toast.makeText(this, "探索功能开发中", Toast.LENGTH_SHORT).show());
        homeView.findViewById(R.id.card_moments).setOnClickListener(v ->
                Toast.makeText(this, "朋友圈功能即将上线", Toast.LENGTH_SHORT).show());
        homeView.findViewById(R.id.card_mine).setOnClickListener(v -> openFileManager());
        homeView.findViewById(R.id.btn_start).setOnClickListener(v -> openFileManager());
        homeView.findViewById(R.id.btn_about).setOnClickListener(v ->
                Toast.makeText(this, "HY_VQ - 极简高效连接", Toast.LENGTH_SHORT).show());
        // 更新包入口横幅注入（验证"更新包代码注入应用 UI"链路）
        try {
            HyVqAppEntry entry = UpdateManager.entry();
            if (entry != null && homeView instanceof android.widget.ScrollView) {
                View banner = entry.createHomeBanner(this);
                if (banner != null) {
                    android.widget.ScrollView scroll = (android.widget.ScrollView) homeView;
                    if (scroll.getChildCount() > 0 && scroll.getChildAt(0) instanceof LinearLayout) {
                        LinearLayout inner = (LinearLayout) scroll.getChildAt(0);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        inner.addView(banner, 0, lp);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w("HYVQ", "home banner inject failed", t);
        }
    }

    private void switchToSettings() {
        if (settingsView == null) {
            settingsView = LayoutInflater.from(this).inflate(R.layout.fragment_settings, contentFrame, false);
            setupSettingsView();
        }
        // 每次进入时刷新动态字段
        TextView tvDefault = settingsView.findViewById(R.id.tv_default_page);
        if (tvDefault != null) {
            String currentDefault = prefs.getString("default_page", "home");
            tvDefault.setText("home".equals(currentDefault) ? "首页" : "聊天");
        }
        // 模块数量可能在设置页存活期间因安装/卸载而变化，进入时同步刷新
        TextView tvModuleCountSetting = settingsView.findViewById(R.id.tv_module_count_setting);
        if (tvModuleCountSetting != null) {
            tvModuleCountSetting.setText(moduleRegistry.getAllModules().size() + "个");
        }
        // 软件更新状态行（更新包版本随时可能变化，进入时刷新）
        updateUpdateStateLabel();
        switchContent(settingsView, PAGE_SETTINGS);
        resetToolbar();
        binding.toolbarTitle.setText("设置");
    }

    private void setupSettingsView() {
        // 刷新默认启动页显示
        TextView tvDefault = settingsView.findViewById(R.id.tv_default_page);
        String currentDefault = prefs.getString("default_page", "home");
        tvDefault.setText("home".equals(currentDefault) ? "首页" : "聊天");

        // 账号管理行标签：显示当前用户名（该页已上线，不再标"开发中"）
        TextView tvAccountLabel = settingsView.findViewById(R.id.tv_account_label);
        if (tvAccountLabel != null && signatureManager != null) {
            tvAccountLabel.setText(signatureManager.getUsername());
        }

        settingsView.findViewById(R.id.item_account).setOnClickListener(v -> switchToAccount());
        settingsView.findViewById(R.id.item_general).setOnClickListener(v -> showDefaultPageDialog());
        View itemAbout = settingsView.findViewById(R.id.item_about);
        if (itemAbout != null) itemAbout.setOnClickListener(v -> switchToAbout());
        TextView tvAboutLabel = settingsView.findViewById(R.id.tv_about_label);
        if (tvAboutLabel != null) tvAboutLabel.setText("v" + baseVersionName());
        settingsView.findViewById(R.id.item_storage).setOnClickListener(v ->
                Toast.makeText(this, "存储管理开发中", Toast.LENGTH_SHORT).show());
        // 模块设置入口
        View itemModule = settingsView.findViewById(R.id.item_module);
        if (itemModule != null) itemModule.setOnClickListener(v -> switchToModuleSettings());
        // 更新设置页模块计数
        TextView tvModuleCountSetting = settingsView.findViewById(R.id.tv_module_count_setting);
        if (tvModuleCountSetting != null) {
            tvModuleCountSetting.setText(moduleRegistry.getAllModules().size() + "个");
        }
        settingsView.findViewById(R.id.item_notification).setOnClickListener(v ->
                Toast.makeText(this, "通知管理待开发", Toast.LENGTH_SHORT).show());
        // 权限管理入口（检查各项权限申请情况）
        settingsView.findViewById(R.id.item_permission).setOnClickListener(v -> switchToPermissions());
        // 软件更新入口（增量更新包导入中心）
        View itemUpdate = settingsView.findViewById(R.id.item_update);
        if (itemUpdate != null) itemUpdate.setOnClickListener(v -> switchToUpdate());
        // 房间联机功能开关（紧凑行：整行可点切换；关闭时隐藏抽屉入口并关闭对话框）
        View itemTc = settingsView.findViewById(R.id.item_terracotta);
        com.google.android.material.switchmaterial.SwitchMaterial swTc =
                settingsView.findViewById(R.id.switch_terracotta);
        TextView tvTcState = settingsView.findViewById(R.id.tv_terracotta_state);
        if (itemTc != null && swTc != null) {
            boolean tcOn = prefs.getBoolean("feature_terracotta_enabled", false);
            swTc.setOnCheckedChangeListener(null);
            swTc.setChecked(tcOn);
            if (tvTcState != null) tvTcState.setText(tcOn ? "开启" : "关闭");
            swTc.setOnCheckedChangeListener((btn, checked) -> {
                prefs.edit().putBoolean("feature_terracotta_enabled", checked).apply();
                if (tvTcState != null) tvTcState.setText(checked ? "开启" : "关闭");
                applyTerracottaFeature();
                if (!checked && terracottaUi != null && terracottaUi.isShowing()) {
                    terracottaUi.dismiss();
                }
            });
            itemTc.setOnClickListener(v -> swTc.setChecked(!swTc.isChecked()));
        }
        settingsView.findViewById(R.id.item_logout).setOnClickListener(v -> {
            // 仅清除会话标记，保留身份数据（uid/uuid），重新登录可恢复原身份
            signatureManager.clearSession();
            // 跳回登录页
            Intent intent = new Intent(this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }

    // ==================== 软件更新（增量更新包本地导入） ====================

    /** 壳版本名（运行时读取，避免依赖 BuildConfig 生成开关） */
    private String baseVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    /** 刷新设置页"软件更新"行状态文本 */
    /** 更新中心按钮（ModuleUiKit 风格纯代码构建） */
    private TextView updateTextButton(String text, View.OnClickListener onClick) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(ModuleUiKit.color(this, com.google.android.material.R.attr.colorPrimary));
        tv.setGravity(Gravity.CENTER);
        int pad = dp2(12);
        tv.setPadding(pad, pad, pad, pad);
        tv.setOnClickListener(onClick);
        return tv;
    }

    // ── 远程更新（GitHub 公开仓库只读直链）──────────────────────
    // 2026-09-06 变更：更新源从自建 WebDAV 迁到 GitHub —— 公开仓库的 raw 文件与
    // release 资产都是**无鉴权直链**，天然只读，APK 内不再内置任何账号密码。
    private static final String GH_OWNER = "haoyou999";
    /** 更新源与源码同仓：latest.json 在仓库根，APK 走该仓的 Release 资产 */
    private static final String GH_REPO = "HY_VQ";
    /** 启动时自动检查更新的偏好键（默认开启） */
    private static final String PREF_AUTO_CHECK_UPDATE = "auto_check_update";
    /** 版本列表来源：GitHub Releases API —— 一次请求拿到全部版本，
     *  每个版本自带<b>精确</b>下载直链（browser_download_url）。
     *  不再依赖 latest.json，也不会出现「latest 前缀 + 旧文件名」导致的 404。 */
    private static final String REMOTE_RELEASES =
            "https://api.github.com/repos/" + GH_OWNER + "/" + GH_REPO + "/releases?per_page=30";

    /** 打开远程只读连接（无鉴权）。
     *  某些 CDN 会 302 跳转，故手动跟随（最多 5 跳），不依赖 HttpURLConnection 自动跟随。
     *  返回的连接已带最终响应码，调用方负责读流与 disconnect()。 */
    private java.net.HttpURLConnection openRemote(String url, String method) throws Exception {
        return openRemote(url, method, null);
    }

    private java.net.HttpURLConnection openRemote(String url, String method, String accept) throws Exception {
        String cur = url;
        for (int hop = 0; hop < 5; hop++) {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(cur).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept", accept != null ? accept
                    : "application/json, application/octet-stream, */*");
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) throw new Exception("重定向缺少 Location 头");
                if (!loc.startsWith("http")) loc = new java.net.URL(new java.net.URL(cur), loc).toString();
                cur = loc;
                continue;
            }
            return conn;
        }
        throw new Exception("重定向次数过多");
    }

    // ==================== 软件更新页 ====================

    /** 一个可用版本（来自 GitHub Releases API） */
    private static class ReleaseInfo {
        String tag = "";
        String ver = "";
        String body = "";
        String publishedAt = "";
        String apkName = "";
        String apkUrl = "";     // 精确下载直链，永不 404
        long apkSize = 0L;

        String date() {
            return publishedAt.length() >= 10 ? publishedAt.substring(0, 10) : publishedAt;
        }
    }

    /** 语义化版本比较：a > b 返回正数 */
    private static int compareVersion(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        String[] x = a.split("[.]");
        String[] y = b.split("[.]");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length ? parseIntSafe(x[i]) : 0;
            int yi = i < y.length ? parseIntSafe(y[i]) : 0;
            if (xi != yi) return xi - yi;
        }
        return 0;
    }

    private static int parseIntSafe(String v) {
        try {
            return Integer.parseInt(v.trim().replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private void switchToUpdate() {
        if (updateView == null) {
            updateView = LayoutInflater.from(this).inflate(R.layout.fragment_update, contentFrame, false);
            setupUpdateView();
        }
        renderUpdateView();
        switchContent(updateView, PAGE_UPDATE);
        setSubpageToolbar("软件更新");
    }

    private void setupUpdateView() {
        if (updateView == null) return;
        updateView.findViewById(R.id.btn_upd_check).setOnClickListener(v -> checkRemoteUpdate());
        updateView.findViewById(R.id.btn_upd_action).setOnClickListener(v -> onUpdateAction());
        updateView.findViewById(R.id.btn_upd_install_cached).setOnClickListener(v -> {
            if (updCachedApk != null) installApk(updCachedApk);
        });
        com.google.android.material.switchmaterial.SwitchMaterial swAuto =
                updateView.findViewById(R.id.switch_upd_auto);
        TextView tvAutoDesc = updateView.findViewById(R.id.tv_upd_auto_desc);
        if (swAuto != null) {
            boolean on = prefs.getBoolean(PREF_AUTO_CHECK_UPDATE, true);
            swAuto.setOnCheckedChangeListener(null);
            swAuto.setChecked(on);
            if (tvAutoDesc != null) {
                tvAutoDesc.setText(on
                        ? "打开应用时在后台静默检查，发现新版本会提示"
                        : "已关闭，需手动点击「检查更新」");
            }
            swAuto.setOnCheckedChangeListener((btn, checked) -> {
                prefs.edit().putBoolean(PREF_AUTO_CHECK_UPDATE, checked).apply();
                if (tvAutoDesc != null) {
                    tvAutoDesc.setText(checked
                            ? "打开应用时在后台静默检查，发现新版本会提示"
                            : "已关闭，需手动点击「检查更新」");
                }
            });
        }
        TextView help = updateView.findViewById(R.id.tv_upd_help);
        if (help != null) {
            help.setText(String.join(System.lineSeparator(), new String[]{
                    "· 从公开更新源获取版本清单，在线下载安装包",
                    "· 下载完成自动校验 MD5，通过后交由系统安装器安装",
                    "· 安装包会缓存在本机，再次进入本页可直接安装，无需重复下载",
                    "· 更新不会影响书签、设置与浏览偏好"
            }));
        }
    }

    private int currentPkgCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 统一渲染更新页内容：状态卡 / 更新日志卡 / 缓存卡 / 操作按钮 */
    /** 统一渲染更新页：当前版本 / 状态卡 / 可用版本列表 / 缓存卡 / 操作按钮 */
    private void renderUpdateView() {
        if (updateView == null) return;
        final int cur = currentPkgCode();
        final String curVer = baseVersionName();

        TextView tvCur = updateView.findViewById(R.id.tv_upd_current);
        if (tvCur != null) tvCur.setText("v" + curVer);
        TextView tvChan = updateView.findViewById(R.id.tv_upd_channel);
        if (tvChan != null) tvChan.setText("版本号 " + cur + " · 稳定版");

        // 缓存检测：本机是否已存有更新的安装包（原生安装器不会清理缓存包）
        File[] newer = findCachedNewerApks(cur);
        updCachedApk = null;
        if (newer.length > 0) {
            File best = newer[0];
            for (File f : newer) if (f.lastModified() > best.lastModified()) best = f;
            updCachedApk = best;
        }

        final ReleaseInfo newest = updReleases.isEmpty() ? null : updReleases.get(0);
        final boolean hasNew = newest != null && compareVersion(newest.ver, curVer) > 0;
        final boolean canInstall = hasNew || updCachedApk != null;

        // ── 状态卡 ──
        TextView title = updateView.findViewById(R.id.tv_upd_status_title);
        TextView desc = updateView.findViewById(R.id.tv_upd_status_desc);
        ImageView icon = updateView.findViewById(R.id.iv_upd_status_icon);
        if (updState == 1) {
            if (title != null) title.setText("正在扫描版本…");
            if (desc != null) desc.setText("正在获取全部可用版本，请稍候");
            if (icon != null) icon.setImageResource(R.drawable.ic_refresh);
        } else if (updState == 4) {
            if (title != null) title.setText("获取版本列表失败");
            if (desc != null) desc.setText(updError.isEmpty() ? "无法连接更新源，请检查网络后重试" : updError);
            if (icon != null) icon.setImageResource(R.drawable.ic_network);
        } else if (hasNew) {
            if (title != null) title.setText("发现新版本 v" + newest.ver);
            if (desc != null) desc.setText("已扫描到 " + updReleases.size() + " 个版本，可在下方选择任意版本安装");
            if (icon != null) icon.setImageResource(R.drawable.ic_download);
        } else if (updCachedApk != null) {
            if (title != null) title.setText("可安装 " + cachedApkLabel(updCachedApk));
            if (desc != null) desc.setText("安装包已在本机缓存，无需重新下载");
            if (icon != null) icon.setImageResource(R.drawable.ic_download);
        } else if (updState == 2) {
            if (title != null) title.setText("已是最新版本");
            if (desc != null) desc.setText("已扫描 " + updReleases.size() + " 个版本，下方可查看或重装任意版本");
            if (icon != null) icon.setImageResource(R.drawable.ic_star);
        } else {
            if (title != null) title.setText("检查更新");
            if (desc != null) desc.setText("点「检查更新」扫描全部可用版本");
            if (icon != null) icon.setImageResource(R.drawable.ic_refresh);
        }

        renderReleaseList();

        // ── 缓存卡 ──
        View cacheCard = updateView.findViewById(R.id.card_upd_cache);
        if (cacheCard != null) {
            if (updCachedApk != null) {
                cacheCard.setVisibility(View.VISIBLE);
                TextView ci = updateView.findViewById(R.id.tv_upd_cache_info);
                if (ci != null) {
                    ci.setText(cachedApkLabel(updCachedApk) + System.lineSeparator()
                            + "大小 " + fmtSize(updCachedApk.length()) + " · 已通过完整性校验");
                }
            } else {
                cacheCard.setVisibility(View.GONE);
            }
        }

        // ── 操作按钮 ──
        View btnCheck = updateView.findViewById(R.id.btn_upd_check);
        if (btnCheck != null) btnCheck.setEnabled(updState != 1);
        View btnAction = updateView.findViewById(R.id.btn_upd_action);
        if (btnAction != null) {
            if (canInstall) {
                btnAction.setVisibility(View.VISIBLE);
                ((com.google.android.material.button.MaterialButton) btnAction).setText(
                        hasNew ? "更新到 v" + newest.ver : "立即安装（使用缓存）");
            } else {
                btnAction.setVisibility(View.GONE);
            }
        }
        updateUpdateStateLabel();
    }

    /** 渲染「可用版本」列表：每行可点，下载直链取自 API 返回的精确地址 */
    private void renderReleaseList() {
        if (updateView == null) return;
        LinearLayout box = updateView.findViewById(R.id.box_release_list);
        if (box == null) return;
        box.removeAllViews();
        TextView empty = updateView.findViewById(R.id.tv_release_empty);
        if (updReleases.isEmpty()) {
            if (empty != null) empty.setVisibility(View.VISIBLE);
            return;
        }
        if (empty != null) empty.setVisibility(View.GONE);

        final String curVer = baseVersionName();
        boolean newestMarked = false;
        for (final ReleaseInfo ri : updReleases) {
            int cmp = compareVersion(ri.ver, curVer);
            boolean isCurrent = cmp == 0;
            boolean isNewer = cmp > 0;

            View row = LayoutInflater.from(this).inflate(R.layout.item_release_version, box, false);
            ((TextView) row.findViewById(R.id.tv_rel_version)).setText("v" + ri.ver);

            TextView badge = row.findViewById(R.id.tv_rel_badge);
            if (isCurrent) {
                badge.setText("当前版本");
                badge.setVisibility(View.VISIBLE);
            } else if (isNewer && !newestMarked) {
                badge.setText("最新");
                badge.setVisibility(View.VISIBLE);
                newestMarked = true;
            } else if (isNewer) {
                badge.setText("新版");
                badge.setVisibility(View.VISIBLE);
            } else {
                badge.setVisibility(View.GONE);
            }

            File cached = updateCacheFileByVer(ri.ver);
            final boolean hasCache = cached.exists() && cached.length() > 0
                    && (ri.apkSize <= 0 || cached.length() == ri.apkSize);

            StringBuilder meta = new StringBuilder();
            if (!ri.date().isEmpty()) meta.append(ri.date());
            if (ri.apkSize > 0) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(fmtSize(ri.apkSize));
            }
            if (hasCache) meta.append(" · 已下载");
            if (!isCurrent) meta.append(" · 点按查看更新内容");
            ((TextView) row.findViewById(R.id.tv_rel_meta)).setText(meta.toString());

            ImageView act = row.findViewById(R.id.iv_rel_action);
            if (act != null) {
                act.setImageResource(isCurrent ? R.drawable.ic_refresh
                        : hasCache ? R.drawable.ic_save : R.drawable.ic_download);
            }
            final boolean fc = isCurrent, fh = hasCache;
            row.setOnClickListener(v -> showReleaseDetail(ri, fc, fh));
            box.addView(row);
        }
    }

    /** 版本详情弹窗：完整更新日志 + 下载/安装/重装 */
    private void showReleaseDetail(final ReleaseInfo ri, boolean isCurrent, boolean hasCache) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(this, "v" + ri.ver));

        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setLineSpacing(0, 1.45f);
        tv.setTextColor(ModuleUiKit.color(this, com.google.android.material.R.attr.colorOnSurface));
        int pad = dp2(4);
        tv.setPadding(pad, pad, pad, pad);

        StringBuilder sb = new StringBuilder();
        if (!ri.date().isEmpty()) sb.append("发布于 ").append(ri.date());
        if (ri.apkSize > 0) {
            if (sb.length() > 0) sb.append("    ");
            sb.append(fmtSize(ri.apkSize));
        }
        if (isCurrent) sb.append("    当前已安装");
        sb.append(System.lineSeparator()).append(System.lineSeparator());
        String body = ri.body == null ? "" : ri.body.trim();
        sb.append(body.isEmpty() ? "· 该版本无更新说明" : body);
        tv.setText(sb.toString());
        box.addView(tv);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        box.addView(btns);

        final android.app.Dialog d = ModuleUiKit.glassDialog(this, box);
        btns.addView(updateTextButton("关闭", v -> d.dismiss()));
        btns.addView(updateTextButton(
                isCurrent ? "重新安装" : (hasCache ? "直接安装" : "下载并安装"), v -> {
                    d.dismiss();
                    File c = updateCacheFileByVer(ri.ver);
                    boolean hit = c.exists() && c.length() > 0
                            && (ri.apkSize <= 0 || c.length() == ri.apkSize);
                    if (hit) {
                        verifyAndInstall(c, null, null);
                    } else {
                        startUpdate(ri.apkUrl, null, ri.apkSize, ri.ver, null);
                    }
                }));
        d.show();
    }


    /** 同步设置页上的更新状态标签 */
    /** 同步设置页上的更新状态标签 */
    private void updateUpdateStateLabel() {
        if (settingsView == null) return;
        TextView tv = settingsView.findViewById(R.id.tv_update_state);
        if (tv == null) return;
        String curVer = baseVersionName();
        if (!updReleases.isEmpty() && compareVersion(updReleases.get(0).ver, curVer) > 0) {
            tv.setText("有新版 " + updReleases.get(0).ver);
        } else if (updCachedApk != null) {
            tv.setText("待安装");
        } else {
            tv.setText("v" + curVer);
        }
    }


    /** 主操作按钮：按当前状态选择「下载」或「直接安装」 */
    /** 主操作按钮：更新到最新版，或安装本机缓存包 */
    private void onUpdateAction() {
        if (!updReleases.isEmpty()) {
            ReleaseInfo newest = updReleases.get(0);
            if (compareVersion(newest.ver, baseVersionName()) > 0) {
                File c = updateCacheFileByVer(newest.ver);
                if (c.exists() && c.length() > 0
                        && (newest.apkSize <= 0 || c.length() == newest.apkSize)) {
                    verifyAndInstall(c, null, null);
                } else {
                    startUpdate(newest.apkUrl, null, newest.apkSize, newest.ver, null);
                }
                return;
            }
        }
        if (updCachedApk != null) {
            installApk(updCachedApk);
            return;
        }
        Toast.makeText(this, "暂无可安装的更新，请先检查更新", Toast.LENGTH_SHORT).show();
    }


    /** 扫描全部可用版本（GitHub Releases API），返回列表按版本号倒序 */
    private java.util.List<ReleaseInfo> fetchReleases() {
        java.util.List<ReleaseInfo> out = new java.util.ArrayList<>();
        try {
            java.net.HttpURLConnection conn = openRemote(
                    REMOTE_RELEASES, "GET", "application/vnd.github+json");
            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                return out;
            }
            org.json.JSONArray arr = new org.json.JSONArray(readAll(conn.getInputStream()));
            conn.disconnect();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject r = arr.getJSONObject(i);
                if (r.optBoolean("draft", false) || r.optBoolean("prerelease", false)) continue;
                ReleaseInfo ri = new ReleaseInfo();
                ri.tag = r.optString("tag_name", "");
                ri.ver = ri.tag.startsWith("v") ? ri.tag.substring(1) : ri.tag;
                if (ri.ver.isEmpty()) continue;
                ri.body = r.optString("body", "");
                ri.publishedAt = r.optString("published_at", "");
                org.json.JSONArray assets = r.optJSONArray("assets");
                if (assets != null) {
                    for (int k = 0; k < assets.length(); k++) {
                        org.json.JSONObject a = assets.getJSONObject(k);
                        String nm = a.optString("name", "");
                        String url = a.optString("browser_download_url", "");
                        if (!nm.endsWith(".apk") || url.isEmpty()) continue;
                        // 优先取文件名含本版本号的资产，避免误选其它 apk
                        boolean better = ri.apkUrl.isEmpty()
                                || (nm.contains(ri.ver) && !ri.apkName.contains(ri.ver));
                        if (better) {
                            ri.apkName = nm;
                            ri.apkUrl = url;
                            ri.apkSize = a.optLong("size", 0L);
                        }
                    }
                }
                if (!ri.apkUrl.isEmpty()) out.add(ri);
            }
        } catch (Exception ignored) {
        }
        java.util.Collections.sort(out, (a, b) -> compareVersion(b.ver, a.ver));
        return out;
    }

    /** 启动时自动检查更新：受开关控制，静默进行，只在发现新版本时提示 */
    private void autoCheckUpdateOnLaunch() {
        if (prefs == null || !prefs.getBoolean(PREF_AUTO_CHECK_UPDATE, true)) return;
        // 延后执行，避免与首屏渲染争抢资源
        handler.postDelayed(this::silentCheckUpdate, 2500);
    }

    /** 后台静默检查：不显示「检查中」，发现新版本才提示 */
    /** 后台静默扫描版本列表：不显示「扫描中」，发现新版本才弹窗 */
    private void silentCheckUpdate() {
        new Thread(() -> {
            final java.util.List<ReleaseInfo> list = fetchReleases();
            if (list.isEmpty()) return;
            runOnUiThread(() -> {
                if (updState == 1) return;   // 用户手动扫描中，不覆盖
                updReleases = list;
                updState = 2;
                if (updateView != null) renderUpdateView();
                else updateUpdateStateLabel();
                ReleaseInfo newest = list.get(0);
                if (compareVersion(newest.ver, baseVersionName()) > 0) showAutoUpdatePrompt(newest);
            });
        }).start();
    }


    /** 自动检查发现新版本时弹出提示，由用户决定是否更新 */
    /** 自动扫描发现新版本时弹出提示，由用户决定是否更新 */
    private void showAutoUpdatePrompt(final ReleaseInfo ri) {
        if (ri == null || ri.ver.isEmpty() || autoPromptedVer.equals(ri.ver)) return;
        autoPromptedVer = ri.ver;

        final File cached = updateCacheFileByVer(ri.ver);
        final boolean hasCache = cached.exists() && cached.length() > 0
                && (ri.apkSize <= 0 || cached.length() == ri.apkSize);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(this, "🎉 发现新版本"));

        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setLineSpacing(0, 1.4f);
        tv.setTextColor(ModuleUiKit.color(this, com.google.android.material.R.attr.colorOnSurface));
        int pad = dp2(4);
        tv.setPadding(pad, pad, pad, pad);

        StringBuilder sb = new StringBuilder();
        sb.append("v").append(ri.ver);
        if (ri.apkSize > 0) sb.append("    ").append(fmtSize(ri.apkSize));
        if (!ri.date().isEmpty()) sb.append("    ").append(ri.date());
        sb.append(System.lineSeparator()).append(System.lineSeparator());

        final int MAX_LINES = 6;
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String ln : ri.body.split(System.lineSeparator())) {
            if (!ln.trim().isEmpty()) lines.add(ln);
        }
        if (lines.isEmpty()) {
            sb.append("· 细节优化与问题修复");
        } else {
            for (int i = 0; i < lines.size() && i < MAX_LINES; i++) {
                sb.append(lines.get(i)).append(System.lineSeparator());
            }
            if (lines.size() > MAX_LINES) {
                sb.append("… 共 ").append(lines.size()).append(" 项，点「详情」查看全部");
            }
        }
        if (hasCache) {
            sb.append(System.lineSeparator()).append(System.lineSeparator())
                    .append("📦 安装包已下载（").append(fmtSize(cached.length())).append("），可直接安装");
        }
        tv.setText(sb.toString());
        box.addView(tv);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        box.addView(btns);

        final android.app.Dialog dialog = ModuleUiKit.glassDialog(this, box);
        btns.addView(updateTextButton("稍后", v -> dialog.dismiss()));
        btns.addView(updateTextButton("详情", v -> {
            dialog.dismiss();
            switchToUpdate();
        }));
        btns.addView(updateTextButton(hasCache ? "立即安装" : "立即更新", v -> {
            dialog.dismiss();
            if (hasCache) verifyAndInstall(cached, null, null);
            else startUpdate(ri.apkUrl, null, ri.apkSize, ri.ver, null);
        }));
        dialog.show();
    }


    /** 检查更新：拉取远程清单后刷新页面（结果全部体现在页面内容里） */
    /** 检查更新：扫描全部可用版本后刷新页面（结果全部体现在页面内容里） */
    private void checkRemoteUpdate() {
        updState = 1;
        updError = "";
        renderUpdateView();
        new Thread(() -> {
            final java.util.List<ReleaseInfo> list = fetchReleases();
            runOnUiThread(() -> {
                if (list.isEmpty()) {
                    updState = 4;
                    updError = "无法获取版本列表，请检查网络后重试";
                } else {
                    updReleases = list;
                    updState = 2;
                    autoPromptedVer = "";   // 手动扫描后允许重新提示
                }
                renderUpdateView();
            });
        }).start();
    }


    // ── 更新包缓存（原生安装器不会自动删除缓存 APK，故自行管理）──

    private File updateCacheDir() {
        return getExternalCacheDir() != null ? getExternalCacheDir() : getCacheDir();
    }

    /** 指定版本的缓存安装包路径（按版本名命名，便于复用与甄别） */
    private File updateCacheFileByVer(String ver) {
        return new File(updateCacheDir(), "hyvq_update_v" + ver + ".apk");
    }

    /** 清理缓存安装包：keep 为 null 时全部清除，否则只保留 keep */
    private void cleanUpdateCache(File keep) {
        File[] fs = updateCacheDir().listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            boolean isPkg = (n.startsWith("hyvq_update_") && n.endsWith(".apk")) || n.endsWith(".apk.part");
            if (isPkg && (keep == null || !f.getAbsolutePath().equals(keep.getAbsolutePath()))) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** 扫描缓存中比当前版本更新的安装包 —— 不依赖网络即可发现「已下载待安装」 */
    private File[] findCachedNewerApks(int currentVersionCode) {
        java.util.List<File> out = new java.util.ArrayList<>();
        File[] fs = updateCacheDir().listFiles();
        if (fs != null) {
            for (File f : fs) {
                String n = f.getName();
                if (!n.startsWith("hyvq_update_") || !n.endsWith(".apk") || f.length() <= 0) continue;
                android.content.pm.PackageInfo pi =
                        getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
                if (pi != null && pi.versionCode > currentVersionCode) out.add(f);
            }
        }
        return out.toArray(new File[0]);
    }

    private String cachedApkLabel(File apk) {
        android.content.pm.PackageInfo pi =
                getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        return pi == null ? apk.getName() : "v" + pi.versionName + " (code " + pi.versionCode + ")";
    }

    private String fmtSize(long b) {
        if (b >= 1048576L) return String.format(java.util.Locale.CHINA, "%.1f MB", b / 1048576.0);
        if (b >= 1024L) return String.format(java.util.Locale.CHINA, "%.0f KB", b / 1024.0);
        return b + " B";
    }

    /** 更新入口：缓存命中则直接安装，否则带进度下载 */
    private void startUpdate(final String apkUrl, final String expectMd5, final long expectSize,
                             final String verName, final android.app.Dialog dialog) {
        final File target = updateCacheFileByVer(verName);
        boolean hit = target.exists() && target.length() > 0
                && (expectSize <= 0 || target.length() == expectSize);
        if (hit) {
            verifyAndInstall(target, expectMd5, dialog);
        } else {
            downloadWithProgress(apkUrl, expectMd5, expectSize, target, dialog);
        }
    }

    /** 校验已有缓存包后安装（MD5 不符即清除，避免反复安装坏包） */
    private void verifyAndInstall(final File apk, final String expectMd5, final android.app.Dialog dialog) {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        Toast.makeText(this, "安装包已在缓存中，正在校验…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String err = null;
            try {
                if (expectMd5 != null && !expectMd5.isEmpty()) {
                    String actual = md5Of(apk);
                    if (!expectMd5.equalsIgnoreCase(actual)) {
                        //noinspection ResultOfMethodCallIgnored
                        apk.delete();
                        throw new Exception("缓存安装包已损坏（MD5 不符），已清除，请重新下载");
                    }
                }
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String ferr = err;
            runOnUiThread(() -> {
                if (ferr != null) {
                    Toast.makeText(this, ferr, Toast.LENGTH_LONG).show();
                } else {
                    cleanUpdateCache(apk);
                    renderUpdateView();
                    installApk(apk);
                }
            });
        }).start();
    }

    /** 带进度的下载：进度条实时刷新，完成后校验 MD5 再拉起安装器 */
    private void downloadWithProgress(final String apkUrl, final String expectMd5,
                                      final long expectSize, final File target,
                                      final android.app.Dialog dialog) {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        cleanUpdateCache(null);   // 下载前清掉旧版本残留

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(this, "⬇ 正在下载更新"));

        final TextView tvPct = new TextView(this);
        tvPct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvPct.setTextColor(ModuleUiKit.color(this, com.google.android.material.R.attr.colorOnSurface));
        int pad = dp2(4);
        tvPct.setPadding(pad, pad, pad, pad);
        tvPct.setText("准备中…");
        box.addView(tvPct);

        final android.widget.ProgressBar pb = new android.widget.ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(0);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp2(8));
        plp.topMargin = dp2(6);
        box.addView(pb, plp);

        final android.app.Dialog pd = ModuleUiKit.glassDialog(this, box);
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            String err = null;
            final File tmp = new File(target.getParentFile(), target.getName() + ".part");
            try {
                java.net.HttpURLConnection conn = openRemote(apkUrl, "GET");
                int code = conn.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                long total = expectSize > 0 ? expectSize : conn.getContentLength();
                long done = 0;
                int lastPct = -1;
                try (java.io.InputStream in = conn.getInputStream();
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) Math.min(100, done * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                final int fp = pct;
                                final long fd = done, ft = total;
                                runOnUiThread(() -> {
                                    pb.setProgress(fp);
                                    tvPct.setText(fp + "%    " + fmtSize(fd) + " / " + fmtSize(ft));
                                });
                            }
                        }
                    }
                }
                conn.disconnect();
                if (tmp.length() <= 0) throw new Exception("下载内容为空");
                if (expectMd5 != null && !expectMd5.isEmpty()) {
                    String actual = md5Of(tmp);
                    if (!expectMd5.equalsIgnoreCase(actual)) {
                        throw new Exception("MD5 校验不通过，安装包可能被篡改或下载不完整");
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                if (target.exists()) target.delete();
                if (!tmp.renameTo(target)) throw new Exception("无法写入缓存目录");
            } catch (Exception e) {
                err = e.getMessage();
                //noinspection ResultOfMethodCallIgnored
                if (tmp.exists()) tmp.delete();
            }
            final String ferr = err;
            final File ftarget = target;
            runOnUiThread(() -> {
                if (pd.isShowing()) pd.dismiss();
                if (ferr != null) {
                    Toast.makeText(this, "下载失败：" + ferr, Toast.LENGTH_LONG).show();
                } else {
                    cleanUpdateCache(ftarget);   // 只保留刚下载的这版
                    renderUpdateView();
                    installApk(ftarget);
                }
            });
        }).start();
    }

    /** 计算文件 MD5（更新包完整性校验） */
    private String md5Of(File f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** 拉起系统安装器（FileProvider 授权）；未授予「安装未知应用」时先引导授权 */
    private void installApk(File apk) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(this, "请先允许「安装未知应用」后重试", Toast.LENGTH_LONG).show();
                Intent s = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivity(s);
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


    private String readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), "UTF-8");
    }

    private int dp2(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ==================== 权限管理页（规范：各种权限申请备齐；成功不弹窗，状态就地展示） ====================

    private void switchToPermissions() {
        if (permissionsView == null) {
            permissionsView = LayoutInflater.from(this).inflate(R.layout.fragment_permissions, contentFrame, false);
            // 通知行：未授权时点击申请（已授权/低版本点击无动作）
            permissionsView.findViewById(R.id.item_perm_notification).setOnClickListener(v -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
                }
            });
            // 联机行：局域网 P2P 直连（纯 Java Socket），无需 VPN 授权，仅状态展示
            permissionsView.findViewById(R.id.item_perm_vpn).setOnClickListener(v ->
                    Toast.makeText(this, "房间联机走 P2P 组网（同网直连 / 异地打洞），无需 VPN 授权", Toast.LENGTH_SHORT).show());
        }
        switchContent(permissionsView, PAGE_PERMISSIONS);
        setSubpageToolbar("权限管理");
        refreshPermissionsView();
        refreshVpnStatusText();
    }

    /** 同步联机行状态文本：P2P 组网无需 VPN 授权，固定展示说明 */
    private void refreshVpnStatusText() {
        setVpnStatusText("无需授权（P2P 组网）");
    }

    /** 刷新权限页状态文本（纯状态展示，不弹窗） */
    private void refreshPermissionsView() {
        if (permissionsView == null) return;
        TextView tvNotif = permissionsView.findViewById(R.id.tv_notification_status);
        if (tvNotif != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                tvNotif.setText("无需申请");
            } else if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                tvNotif.setText("已授权");
            } else {
                tvNotif.setText("未授权·点此申请");
            }
        }
    }

    /** 联机行状态展示（局域网直连固定文案） */
    private void setVpnStatusText(String text) {
        if (permissionsView == null) return;
        TextView tvVpn = permissionsView.findViewById(R.id.tv_vpn_status);
        if (tvVpn != null) tvVpn.setText(text);
    }

    private void showDefaultPageDialog() {
        String current = prefs.getString("default_page", "home");
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setBackgroundResource(R.drawable.bg_dialog_add_friend);
        root.setElevation(8f);

        TextView title = new TextView(this);
        title.setText("默认启动页");
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        title.setTextColor(resolveAttr(com.google.android.material.R.attr.colorOnSurface));
        title.setPadding(0, 0, 0, 20);
        root.addView(title);

        String[] items = {"首页", "聊天"};
        String[] values = {"home", "chat"};
        int[] icons = {R.drawable.ic_home, R.drawable.ic_chat};
        int selectedIdx = "chat".equals(current) ? 1 : 0;

        // 横向双卡片，16:9 比例（宽=高*16/9）
        LinearLayout cardsRow = new LinearLayout(this);
        cardsRow.setOrientation(LinearLayout.HORIZONTAL);
        cardsRow.setGravity(Gravity.CENTER);

        int cardW = (int) (screenW() * 0.30f); // 每张卡片占屏幕 30%
        int cardH = (int) (cardW * 9f / 16f);   // 16:9 反比 → 宽屏卡片

        final int[] picked = {selectedIdx};
        final com.google.android.material.card.MaterialCardView[] cardRefs =
                new com.google.android.material.card.MaterialCardView[2];

        for (int i = 0; i < items.length; i++) {
            com.google.android.material.card.MaterialCardView card =
                    new com.google.android.material.card.MaterialCardView(this);
            card.setRadius(16);
            card.setCardElevation(2f);
            card.setCardBackgroundColor(resolveAttr(com.google.android.material.R.attr.colorSurfaceContainerLow));
            // 模糊背景上卡片轮廓：默认 1dp 中性描边，选中 2dp 主题色描边
            card.setStrokeWidth(i == selectedIdx ? 2 : 1);
            card.setStrokeColor(i == selectedIdx
                    ? resolveAttr(com.google.android.material.R.attr.colorPrimary)
                    : resolveAttr(com.google.android.material.R.attr.colorOutlineVariant));
            card.setClickable(true);
            card.setFocusable(true);

            // 内嵌垂直布局：图标 + 名称
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            inner.setPadding(24, 20, 24, 20);

            ImageView iv = new ImageView(this);
            iv.setImageResource(icons[i]);
            iv.setColorFilter(resolveAttr(com.google.android.material.R.attr.colorPrimary));
            LinearLayout.LayoutParams ivp = new LinearLayout.LayoutParams(56, 56);
            iv.setLayoutParams(ivp);
            inner.addView(iv);

            TextView tv = new TextView(this);
            tv.setText(items[i]);
            tv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
            tv.setTextColor(resolveAttr(com.google.android.material.R.attr.colorOnSurface));
            tv.setPadding(0, 12, 0, 0);
            tv.setGravity(Gravity.CENTER);
            inner.addView(tv);

            card.addView(inner);
            cardRefs[i] = card;

            final int idx = i;
            card.setOnClickListener(v2 -> {
                picked[0] = idx;
                for (int j = 0; j < cardRefs.length; j++) {
                    cardRefs[j].setStrokeWidth(j == idx ? 2 : 1);
                    cardRefs[j].setStrokeColor(j == idx
                            ? resolveAttr(com.google.android.material.R.attr.colorPrimary)
                            : resolveAttr(com.google.android.material.R.attr.colorOutlineVariant));
                    cardRefs[j].setCardBackgroundColor(
                            j == idx ? resolveAttr(com.google.android.material.R.attr.colorPrimaryContainer)
                                     : resolveAttr(com.google.android.material.R.attr.colorSurfaceContainerLow));
                }
            });

            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(cardW, cardH);
            cp.leftMargin = i == 0 ? 0 : 16;
            card.setLayoutParams(cp);
            cardsRow.addView(card);
        }
        root.addView(cardsRow);

        // 确定按钮
        Button btnOk = new Button(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = 20;
        btnOk.setLayoutParams(bp);
        btnOk.setText("确定");
        btnOk.setOnClickListener(v2 -> {
            prefs.edit().putString("default_page", values[picked[0]]).apply();
            Toast.makeText(this, "已设置默认启动页为：" + items[picked[0]], Toast.LENGTH_SHORT).show();
            // 同步设置页显示的默认页文案
            if (settingsView != null) {
                TextView tvDefault = settingsView.findViewById(R.id.tv_default_page);
                if (tvDefault != null) tvDefault.setText(items[picked[0]]);
            }
            dialog.dismiss();
        });
        root.addView(btnOk);

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            // 对话框宽度 = 2张卡片 + 间距 + padding = 30%*2 + 1个间距 ≈ 65%屏幕宽
            dialog.getWindow().setLayout((int) (screenW() * 0.78f), ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setGravity(Gravity.CENTER);
            // 规范：所有浮窗统一走 ModuleUiKit.applyBlur（FLAG_BLUR_BEHIND + 半径）；
            // 只调 setBackgroundBlurRadius 不加 FLAG_BLUR_BEHIND 不会生效
            ModuleUiKit.applyBlur(dialog.getWindow(), this);
        }
        dialog.setOnDismissListener(d -> activeDialogs.remove(dialog));
        activeDialogs.add(dialog);
        dialog.show();
    }

    private void switchToAccount() {
        if (accountView == null) {
            accountView = LayoutInflater.from(this).inflate(R.layout.fragment_account, contentFrame, false);
            setupAccountView();
        }
        switchContent(accountView, PAGE_ACCOUNT);
        setSubpageToolbar("账号管理");
    }

    private void setupAccountView() {
        // 账号签名管理
        accountView.findViewById(R.id.item_signature).setOnClickListener(v -> showSignatureDialog());
        // 连接分组：房间联机开关（与设置页同一 prefs key）
        View tcRow = accountView.findViewById(R.id.item_account_terracotta);
        com.google.android.material.switchmaterial.SwitchMaterial swAc =
                accountView.findViewById(R.id.switch_account_terracotta);
        TextView tvAcState = accountView.findViewById(R.id.tv_account_tc_state);
        if (tcRow != null && swAc != null) {
            boolean tcOn = prefs.getBoolean("feature_terracotta_enabled", false);
            swAc.setChecked(tcOn);
            if (tvAcState != null) tvAcState.setText(tcOn ? "开启" : "关闭");
            swAc.setOnCheckedChangeListener((btn, checked) -> {
                prefs.edit().putBoolean("feature_terracotta_enabled", checked).apply();
                if (tvAcState != null) tvAcState.setText(checked ? "开启" : "关闭");
                applyTerracottaFeature();
                if (!checked && terracottaUi != null && terracottaUi.isShowing()) {
                    terracottaUi.dismiss();
                }
            });
            tcRow.setOnClickListener(v -> swAc.setChecked(!swAc.isChecked()));
        }
    }

    // 屏幕宽度缓存（用于动画，避免 getWidth() 首次返回 0）
    private int cachedScreenW = -1;
    private int screenW() {
        if (cachedScreenW <= 0) cachedScreenW = getResources().getDisplayMetrics().widthPixels;
        return cachedScreenW;
    }
    // SiliconUI-inspired: exponential decay easing (fast start, gentle settle)
    private static final PathInterpolator SI_EXP_EASE = new PathInterpolator(0.12f, 0f, 0f, 1f);
    // SiliconUI-inspired: spring overshoot for list/panel transitions
    private static final OvershootInterpolator SI_SPRING = new OvershootInterpolator(1.2f);

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!goBack()) {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });
    }
}