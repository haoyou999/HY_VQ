package com.aliya.hy_vq.update;

import android.content.Context;
import android.view.View;

/**
 * HY_VQ 更新包入口契约（定义在壳中，更新包 classes.dex 必须实现本接口）。
 *
 * <p>更新包 = zip（.hyv 扩展名），内含 manifest.json + classes.dex。
 * 安装后由 {@link UpdateManager} 用独立 DexClassLoader 加载入口类，
 * 应用主体通过本接口获取更新包提供的 UI 扩展（首页横幅等），
 * 后续页面插件化扩展点也在此接口上迭代。</p>
 *
 * <p>接口方法全部可空实现：更新包可以只实现部分能力，
 * 壳对任何异常都有兜底（try/catch + null 检查）。</p>
 */
public interface HyVqAppEntry {

    /** 更新包挂载（context 为 application context） */
    void onAttach(Context context);

    /** 更新包名称（如"HY_VQ 1.3.0 正式包"） */
    String getName();

    /** 更新包版本名（如 "1.3.0"） */
    String getVersion();

    /**
     * 首页顶部横幅（可选，可返回 null）。
     * 用于验证"更新包代码注入应用 UI"链路的最小扩展点：
     * 更新包返回一个 View，壳将其插入首页最顶部。
     */
    View createHomeBanner(Context context);

    /**
     * 设置页扩展（可选，可返回 null）。
     * 更新包可以在设置页末尾追加自定义配置区块。
     */
    default View createSettingsExtension(Context context) {
        return null;
    }
}
