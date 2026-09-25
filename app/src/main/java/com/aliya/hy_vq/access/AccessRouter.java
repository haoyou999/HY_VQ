package com.aliya.hy_vq.access;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 目录访问路由器——四类数据源的无感路由核心（对齐 MT 管理器实测行为）。
 *
 * <p><b>路由规则（真实优先，失败回退虚拟，都无则静默空）：</b></p>
 * <pre>
 *   /                      → 真实 listFiles + 白名单过滤（RootVirtualStrategy）
 *   /data                  → 虚拟：app（RootVirtualStrategy）
 *   /data/app              → PackageManager 虚拟枚举（PkgStrategy）
 *   /data/app/~~xxx        → sourceDir 路径解析虚拟补全（PkgStrategy）
 *   /data/app/~~xxx/包名    → 775 真实可读（默认真实策略）
 *   /storage               → StorageManager 卷枚举（StorageStrategy）
 *   /storage/emulated      → 虚拟：0 主用户卷（StorageStrategy）
 *   其他路径                → 真实 listFiles；失败 → 静默空列表
 * </pre>
 */
public final class AccessRouter {

    private AccessRouter() {}

    /** 列目录（主线程可调用；内部无 IO 阻塞以外的长任务） */
    public static AccessResult list(Context ctx, String path) {
        // SAF 授权 uri（content://...）优先路由：DocumentsProvider 子文档查询（app 内浏览）
        if (SafStrategy.isSafUri(path)) return SafStrategy.list(ctx, path);

        String p = normalize(path);

        // ── ⭐ v2.8.0 Root 优先路由 ──
        // 已授权 root 时，系统路径（/、/data/xx、/system…）直接用 root 真实列出：
        // 既绕过 scoped storage 限制，也跳过虚拟白名单层（看到真实内容，对齐
        // MT 管理器 root 模式）。App 自己读得到的 /data/media 仍走普通路径
        // （RootFs.preferRoot 已排除），日常目录性能不受影响。
        final boolean rootOn = RootShell.isGranted();
        if (rootOn && RootFs.preferRoot(p)) {
            List<FileEntry> rl = RootFs.list(p);
            if (rl != null) return AccessResult.ok(rl, "Root");
        }

        // ── 特殊路径优先路由（虚拟层）：仅在无 root 时生效 ──
        if (!rootOn) {
            if (p.equals("/")) return RootVirtualStrategy.listRoot();
            if (p.equals("/data")) return RootVirtualStrategy.listData();
            if (p.equals(PkgStrategy.APP_DIR)) return PkgStrategy.listAppDir(ctx);
            if (PkgStrategy.isPackageTop(p)) return PkgStrategy.listPackageTop(ctx, p);
        }
        if (p.equals(StorageStrategy.STORAGE_DIR)) return StorageStrategy.listVolumes(ctx);
        if (p.equals(StorageStrategy.STORAGE_DIR + "/emulated")) return StorageStrategy.listEmulated(ctx);

        // ── 默认真实策略：listFiles 成功 → 真实条目（含符号链接标记） ──
        File f = new File(p);
        File[] real = f.listFiles();
        if (real != null) {
            List<FileEntry> out = new ArrayList<>(real.length);
            for (File c : real) {
                out.add(toEntry(c));
            }
            return AccessResult.ok(out, "Real");
        }
        // ── Root 回退：App 读不了（listFiles 返回 null）→ 交给 root 再试一次 ──
        if (rootOn) {
            List<FileEntry> rl = RootFs.list(p);
            if (rl != null) return AccessResult.ok(rl, "Root");
        }
        // 无权限且无虚拟信息 → 静默空（对齐 MT 空列表行为，不报错）
        return AccessResult.denied("Real");
    }

    /** File → FileEntry（符号链接标记 LINK 并保留目标） */
    public static FileEntry toEntry(File f) {
        if (java.nio.file.Files.isSymbolicLink(f.toPath())) {
            String target = null;
            try {
                target = java.nio.file.Files.readSymbolicLink(f.toPath()).toString();
            } catch (Throwable ignored) {
            }
            return FileEntry.link(f.getAbsolutePath(), target, f.lastModified());
        }
        if (f.isDirectory()) {
            return FileEntry.dir(f.getAbsolutePath(), f.lastModified());
        }
        return FileEntry.file(f.getAbsolutePath(), f.length(), f.lastModified());
    }

    /** 规范化路径：去尾部斜杠（根除外） */
    public static String normalize(String path) {
        if (path == null || path.isEmpty()) return "/";
        String p = path;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}