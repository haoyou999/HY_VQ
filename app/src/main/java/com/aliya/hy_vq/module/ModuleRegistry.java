package com.aliya.hy_vq.module;

import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模块注册中心：管理所有已安装模块的生命周期。
 */
public class ModuleRegistry {

    private static final String TAG = "ModuleRegistry";

    private final Map<String, HyVqModule> modules = new LinkedHashMap<>();

    /** 侧边栏重建回调 */
    public interface DrawerCallback {
        void onDrawerChanged(List<HyVqModule> orderedModules);
    }

    private DrawerCallback drawerCallback;

    public void setDrawerCallback(DrawerCallback callback) {
        this.drawerCallback = callback;
    }

    /** 注册模块 */
    public void install(HyVqModule module) {
        if (module == null || module.id == null) return;
        modules.put(module.id, module);
        Log.i(TAG, "Module installed: " + module.id + " (" + module.name + ")");
        notifyDrawerChanged();
    }

    /** 卸载模块（内置模块不可卸） */
    public boolean uninstall(String moduleId) {
        HyVqModule m = modules.get(moduleId);
        if (m == null) return false;
        m.onDetach();                    // 卸载生命周期(停服务/释放资源)
        m.enabled = false;
        modules.remove(moduleId);
        Log.i(TAG, "Module uninstalled: " + moduleId + " (was built-in: " + m.isBuiltIn + ")");
        notifyDrawerChanged();
        return true;
    }

    /** 禁用模块 */
    public void disable(String moduleId) {
        HyVqModule m = modules.get(moduleId);
        if (m != null) {
            m.enabled = false;
            notifyDrawerChanged();
        }
    }

    /** 启用模块 */
    public void enable(String moduleId) {
        HyVqModule m = modules.get(moduleId);
        if (m != null) {
            m.enabled = true;
            notifyDrawerChanged();
        }
    }

    /** 获取需要在侧边栏显示的模块列表（有序） */
    public List<HyVqModule> getDrawerModules() {
        List<HyVqModule> result = new ArrayList<>();
        for (HyVqModule m : modules.values()) {
            if (m.showInDrawer && m.enabled) {
                result.add(m);
            }
        }
        return result;
    }

    /** 获取所有模块 */
    public Map<String, HyVqModule> getAllModules() {
        return new LinkedHashMap<>(modules);
    }

    /** 根据 ID 获取模块 */
    public HyVqModule get(String moduleId) {
        return modules.get(moduleId);
    }

    /** 通知侧边栏更新 */
    public void notifyDrawerChanged() {
        if (drawerCallback != null) {
            drawerCallback.onDrawerChanged(getDrawerModules());
        }
    }

    /** 通知所有模块暂停 */
    public void notifyPause() {
        for (HyVqModule m : modules.values()) {
            m.onPause();
        }
    }

    /** 通知所有模块恢复 */
    public void notifyResume() {
        for (HyVqModule m : modules.values()) {
            m.onResume();
        }
    }

    /** 卸载所有模块 */
    public void detachAll() {
        for (HyVqModule m : modules.values()) {
            try { m.onDetach(); } catch (Exception ignored) {}
        }
        modules.clear();
    }
}
