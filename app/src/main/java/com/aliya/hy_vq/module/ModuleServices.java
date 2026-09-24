package com.aliya.hy_vq.module;

import android.content.SharedPreferences;

/**
 * 框架向模块暴露的服务接口。
 * 模块通过此接口获取账号信息、导航能力、存储等。
 */
public interface ModuleServices {

    /** 获取当前用户 UID */
    String getUid();

    /** 获取当前用户名 */
    String getUsername();

    /** 获取当前用户签名 */
    String getUserSignature();

    /** 获取模块专属的 SharedPreferences */
    SharedPreferences getModulePrefs(String moduleId);

    /** 请求框架导航到指定页面（暂未实现，预留） */
    void navigateTo(String pageId);

    /** 请求框架重建侧边栏（模块启用/禁用后调用） */
    void requestDrawerRebuild();
}
