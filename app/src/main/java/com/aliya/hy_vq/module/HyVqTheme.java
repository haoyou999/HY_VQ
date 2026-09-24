package com.aliya.hy_vq.module;

import java.util.HashMap;
import java.util.Map;

/**
 * UI 风格/主题定义。
 * 模块可通过 getThemes() 贡献自定义配色方案。
 */
public class HyVqTheme {

    /** 主题唯一标识 */
    public String id;

    /** 主题显示名称 */
    public String name;

    /** 来源模块ID */
    public String authorModuleId;

    /** 颜色覆盖表 (color resource name -> @ColorInt) */
    public Map<Integer, Integer> colorOverrides = new HashMap<>();

    public HyVqTheme() {}

    public HyVqTheme(String id, String name, String authorModuleId) {
        this.id = id;
        this.name = name;
        this.authorModuleId = authorModuleId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HyVqTheme)) return false;
        return id != null && id.equals(((HyVqTheme) o).id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : super.hashCode();
    }
}
