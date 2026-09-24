package com.aliya.hy_vq.access;

import android.content.Context;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * StorageManager 卷枚举策略——/storage（711 无 r）无权限时的虚拟数据源。
 *
 * <p><b>机制（真机实测 2026-08-16）</b>：/storage 真实权限 711（other 无 r），
 * listFiles() 失败；但 {@link StorageManager#getStorageVolumes()} 系统服务可无感
 * 返回所有存储卷：卷条目（如 BA73-022B，副文本=卷标签）与 emulated 目录。
 * MT 管理器把卷条目当"文件"（假权限串 -rw-rw----，点击弹打开方式）；本实现
 * 更友好：卷条目标记为 VIRTUAL_FILE（不可进入），emulated 虚拟补全 0 主用户卷。</p>
 */
public final class StorageStrategy {

    public static final String STORAGE_DIR = "/storage";

    private StorageStrategy() {}

    /** /storage 卷枚举 */
    public static AccessResult listVolumes(Context ctx) {
        List<FileEntry> out = new ArrayList<>();
        try {
            StorageManager sm = ctx.getSystemService(StorageManager.class);
            if (sm != null) {
                for (StorageVolume vol : sm.getStorageVolumes()) {
                    File dir = vol.getDirectory();
                    String label = vol.getDescription(ctx);
                    if (dir == null) continue;
                    String p = dir.getAbsolutePath();
                    // /storage/emulated/0 归属 emulated 卷 → 补 emulated 虚拟目录；
                    // 其他独立卷（UUID 路径）→ 虚拟文件（不可进入，与 MT 一致）
                    if (p.startsWith(STORAGE_DIR + "/emulated/")) {
                        if (!contains(out, "emulated")) {
                            out.add(FileEntry.virtualDir(STORAGE_DIR + "/emulated",
                                    vol.isPrimary() ? "主存储" : label));
                        }
                    } else if (p.startsWith(STORAGE_DIR + "/")) {
                        String name = p.substring(STORAGE_DIR.length() + 1);
                        if (!contains(out, name)) {
                            out.add(FileEntry.virtualFile(STORAGE_DIR + "/" + name, label));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 兜底：主卷 emulated 永远可见（几乎所有设备存在）
        if (!contains(out, "emulated")) {
            out.add(FileEntry.virtualDir(STORAGE_DIR + "/emulated", "主存储"));
        }
        return AccessResult.ok(out, "StorageManager");
    }

    /** /storage/emulated 虚拟补全：0 = 主用户卷（MANAGE 下真实可读） */
    public static AccessResult listEmulated(Context ctx) {
        List<FileEntry> out = new ArrayList<>();
        // 主用户卷 0（Android 多用户：0/999…；仅列出存在的主卷）
        File primary = Environment.getExternalStorageDirectory();
        if (primary != null && primary.getAbsolutePath().startsWith(STORAGE_DIR + "/emulated/")) {
            String name = primary.getAbsolutePath()
                    .substring(primary.getAbsolutePath().lastIndexOf('/') + 1);
            out.add(FileEntry.virtualDir(STORAGE_DIR + "/emulated/" + name, "主存储"));
        }
        return AccessResult.ok(out, "StorageManager");
    }

    private static boolean contains(List<FileEntry> list, String name) {
        for (FileEntry e : list) {
            if (e.name.equals(name)) return true;
        }
        return false;
    }
}