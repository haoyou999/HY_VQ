package com.aliya.hy_vq.module;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.util.Collections;
import java.util.List;

/**
 * HY_VQ 模块抽象基类。
 * 所有功能模块（聊天、P2P、UI主题等）均继承此类。
 */
public abstract class HyVqModule {

    /** 模块唯一标识，如 "chat_server" */
    public String id;

    /** 模块显示名称，如 "聊天服务" */
    public String name;

    /** 模块版本号 */
    public String version;

    /** 图标名称（使用内置 drawable 资源名，如 "ic_chat"） */
    public String icon;

    /** 是否在侧边栏显示入口 */
    public boolean showInDrawer = true;

    /** 是否内置模块（内置模块不可卸载） */
    public boolean isBuiltIn = false;

    /** 是否已启用 */
    public boolean enabled = true;

    /** 插件声明调用的框架 API 清单(逗号分隔,如 "uid,username,prefs,navigate");空=声明未使用 */
    public String declaredApis = "";

    /** 插件声明功能出现的界面(逗号分隔,如 "home,file_manager,chat");空=不限定/自行决定 */
    public String declaredSurfaces = "";

    // ── 生命周期 ──

    /** 模块被安装/加载时调用 */
    public void onAttach(Context context, ModuleServices services) {}

    /** 模块被卸载时调用 */
    public void onDetach() {}

    /** Activity onPause 时广播 */
    public void onPause() {}

    /** Activity onResume 时广播 */
    public void onResume() {}

    // ── 视图 ──

    /** 返回模块的主功能视图（侧边栏点击后显示的内容） */
    public abstract View createMainView(LayoutInflater inflater, ViewGroup parent);

    /** 返回模块自己的设置页视图（可选），在模块设置页点击模块时展示 */
    public View createSettingsView(LayoutInflater inflater, ViewGroup parent) {
        return null;
    }

    // ── UI 风格 ──

    /** 返回模块贡献的 UI 风格列表（可选） */
    public List<HyVqTheme> getThemes() {
        return Collections.emptyList();
    }

    /** 模块在设置页的摘要信息 */
    public String getSummary() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HyVqModule)) return false;
        return id != null && id.equals(((HyVqModule) o).id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : super.hashCode();
    }
}
