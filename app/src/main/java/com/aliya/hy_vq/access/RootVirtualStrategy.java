package com.aliya.hy_vq.access;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 根目录虚拟层策略——无 root 权限时根目录 "/" 与 "/data" 的白名单视图。
 *
 * <p><b>机制（真机实测 2026-08-16）</b>：/ 真实 755 可读（能 listFiles 出 40 个
 * 顶层条目），但 MT 管理器只显示 10 个"用户相关"目录（data/etc/mnt/proc/product/
 * sdcard/storage/system/system_ext/vendor）——白名单裁剪；/data（771 无 r）真实
 * 读取失败，但 PackageManager 元数据可证明 /data/app 存在 → 虚拟显示 app。</p>
 *
 * <p>本实现：根目录优先真实 listFiles() + 白名单过滤（无 MANAGE 时对系统目录
 * 不可读的也一并隐藏，保持无感）；/data 虚拟显示 app（app 内由 PkgStrategy 接管）。</p>
 */
public final class RootVirtualStrategy {

    /** 根目录白名单（对齐 MT 实测的 10 个顶层目录） */
    public static final Set<String> ROOT_WHITELIST = new LinkedHashSet<>();
    static {
        ROOT_WHITELIST.add("data");
        ROOT_WHITELIST.add("etc");
        ROOT_WHITELIST.add("mnt");
        ROOT_WHITELIST.add("proc");
        ROOT_WHITELIST.add("product");
        ROOT_WHITELIST.add("sdcard");
        ROOT_WHITELIST.add("storage");
        ROOT_WHITELIST.add("system");
        ROOT_WHITELIST.add("system_ext");
        ROOT_WHITELIST.add("vendor");
    }

    private RootVirtualStrategy() {}

    /** 根目录视图：真实 listFiles + 白名单过滤；真实失败 → 纯虚拟白名单（对齐 MT） */
    public static AccessResult listRoot() {
        List<FileEntry> out = new ArrayList<>();
        File root = new File("/");
        File[] real = root.listFiles();
        if (real != null) {
            // 真实成功：白名单裁剪（保留链接标记与真实元数据）
            for (File f : real) {
                if (!ROOT_WHITELIST.contains(f.getName())) continue;
                boolean link = java.nio.file.Files.isSymbolicLink(f.toPath());
                if (link) {
                    String target = readLink(f);
                    out.add(FileEntry.link(f.getAbsolutePath(), target, f.lastModified()));
                } else if (f.isDirectory()) {
                    out.add(FileEntry.dir(f.getAbsolutePath(), f.lastModified()));
                } else {
                    out.add(FileEntry.file(f.getAbsolutePath(), f.length(), f.lastModified()));
                }
            }
        } else {
            // 真实读取失败（真机实测：untrusted_app 对 / 的 File.listFiles() 返回 null，
            // 即使授权 MANAGE 也一样，ColorOS SELinux 限制）→ 纯虚拟白名单 10 项。
            // MT 管理器（无任何权限）根目录显示的 10 项同为虚拟层，行为对齐。
            for (String name : ROOT_WHITELIST) {
                out.add(FileEntry.virtualDir("/" + name, null));
            }
        }
        return AccessResult.ok(out, "RootView");
    }

    /** /data 虚拟视图：仅 app（PkgStrategy 接管内部） */
    public static AccessResult listData() {
        List<FileEntry> out = new ArrayList<>();
        out.add(FileEntry.virtualDir("/data/app", null));
        return AccessResult.ok(out, "RootView");
    }

    private static String readLink(File f) {
        try {
            return java.nio.file.Files.readSymbolicLink(f.toPath()).toString();
        } catch (Throwable t) {
            return null;
        }
    }
}