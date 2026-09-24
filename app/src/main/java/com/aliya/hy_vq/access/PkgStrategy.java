package com.aliya.hy_vq.access;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PackageManager 虚拟枚举策略——破解 MT 管理器无权限列出 /data/app 的核心机制。
 *
 * <p><b>机制（真机实测 2026-08-16）</b>：/data/app 权限为 771（other 无 r），
 * 普通应用 listFiles() 失败，但 {@link PackageManager#getInstalledPackages(int)}
 * 在 QUERY_ALL_PACKAGES 权限下由系统服务返回每个包的 sourceDir
 * （形如 {@code /data/app/~~xxx/包名-随机后缀/base.apk}），从中可无感还原：</p>
 * <pre>
 *   /data/app            → 69 个 ~~xxx 目录          （sourceDir 父目录名，虚拟）
 *   /data/app/~~xxx      → 1 个 包名-随机后缀 目录    （sourceDir 路径解析，虚拟）
 *   /data/app/~~xxx/包名 → base.apk/lib/oat          （775 真实可读，走真实策略）
 * </pre>
 * 虚拟深度 = PackageManager 元数据覆盖深度；oat 层（751）无元数据 → 静默空列表。
 */
public final class PkgStrategy {

    public static final String APP_DIR = "/data/app";

    /** 包目录虚拟树缓存：~~xxx → [包名-后缀, ...]（可能多个：split/多用户） */
    private static Map<String, List<String>> pkgTree = new HashMap<>();
    private static long cachedAt = 0;
    private static final long CACHE_TTL = 30_000;

    private PkgStrategy() {}

    /** 刷新包目录虚拟树（线程安全；30s 缓存） */
    private static synchronized void ensureCache(Context ctx) {
        long now = System.currentTimeMillis();
        if (!pkgTree.isEmpty() && now - cachedAt < CACHE_TTL) return;
        Map<String, List<String>> fresh = new HashMap<>();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<PackageInfo> pkgs = pm.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES);
            for (PackageInfo pi : pkgs) {
                ApplicationInfo ai = pi.applicationInfo;
                if (ai == null) continue;
                String src = ai.sourceDir;
                if (src == null || !src.startsWith(APP_DIR + "/")) continue;
                String rel = src.substring(APP_DIR.length() + 1); // ~~xxx/包名-xxx/base.apk
                int s1 = rel.indexOf('/');
                if (s1 <= 0) continue;
                String top = rel.substring(0, s1);        // ~~xxx
                String rest = rel.substring(s1 + 1);      // 包名-xxx/base.apk
                int s2 = rest.indexOf('/');
                String pkg = s2 > 0 ? rest.substring(0, s2) : rest; // 包名-xxx
                List<String> list = fresh.get(top);
                if (list == null) {
                    list = new ArrayList<>();
                    fresh.put(top, list);
                }
                if (!list.contains(pkg)) list.add(pkg);
            }
            // 稳定排序（对齐 MT 按名排序观感）
            for (List<String> list : fresh.values()) {
                Collections.sort(list, String.CASE_INSENSITIVE_ORDER);
            }
            pkgTree = fresh;
            cachedAt = now;
        } catch (Throwable ignored) {
            // 无权限/异常时保留旧缓存或空树
        }
    }

    /** /data/app 虚拟枚举：69 个 ~~xxx 目录 */
    public static AccessResult listAppDir(Context ctx) {
        ensureCache(ctx);
        List<FileEntry> out = new ArrayList<>();
        List<String> keys = new ArrayList<>(pkgTree.keySet());
        Collections.sort(keys, String.CASE_INSENSITIVE_ORDER);
        for (String k : keys) {
            out.add(FileEntry.virtualDir(APP_DIR + "/" + k, null));
        }
        return AccessResult.ok(out, "PackageManager");
    }

    /** /data/app/~~xxx 虚拟补全：sourceDir 路径解析出唯一子目录 */
    public static AccessResult listPackageTop(Context ctx, String path) {
        ensureCache(ctx);
        String top = path.substring(APP_DIR.length() + 1);
        List<String> subs = pkgTree.get(top);
        if (subs == null || subs.isEmpty()) return AccessResult.denied("PackageManager");
        List<FileEntry> out = new ArrayList<>();
        for (String s : subs) {
            out.add(FileEntry.virtualDir(path + "/" + s, null));
        }
        return AccessResult.ok(out, "PackageManager");
    }

    /** 判断路径是否为 /data/app/~~xxx 一级包目录（下钻层，真实 771 不可读） */
    public static boolean isPackageTop(String path) {
        if (!path.startsWith(APP_DIR + "/") || path.length() <= APP_DIR.length() + 1) return false;
        String rel = path.substring(APP_DIR.length() + 1);
        if (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
        return rel.indexOf('/') < 0;
    }

    /** 判断路径是否为 /data/app/~~xxx/包名-xxx（775 真实可读层） */
    public static boolean isPackageInner(String path) {
        if (!path.startsWith(APP_DIR + "/") || path.length() <= APP_DIR.length() + 1) return false;
        String rel = path.substring(APP_DIR.length() + 1);
        if (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
        int c1 = rel.indexOf('/');
        if (c1 <= 0) return false;
        String rest = rel.substring(c1 + 1);
        return rest.indexOf('/') < 0;
    }
}