package com.aliya.hy_vq.filemgr;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 文件管理模块：文件操作工具 + 后台执行器。
 *
 * <p>复制/移动/删除是 IO 密集操作，必须在工作线程执行（否则 ANR）；
 * 结果通过 {@link Callback} 回到主线程。删除/复制目录时递归处理，
 * 移动同分区走 rename（原子、零拷贝），跨分区退化为复制+删除。</p>
 */
public final class FileOps {

    /** 操作结果回调（主线程） */
    public interface Callback {
        void onDone(boolean success, String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private FileOps() {}

    // ── 格式化 ──

    /** 人类可读文件大小（B/KB/MB/GB） */
    public static String formatSize(long bytes) {
        if (bytes < 0) return "-";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format(Locale.US, "%.2f GB", gb);
    }

    /** 格式化修改时间：今年显示 MM-dd HH:mm，跨年显示 yyyy-MM-dd */
    public static String formatDate(long millis) {
        if (millis <= 0) return "-";
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(millis);
        java.util.Calendar now = java.util.Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat(
                cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
                        ? "MM-dd HH:mm" : "yyyy-MM-dd",
                Locale.getDefault());
        return sdf.format(new Date(millis));
    }

    /** 目录总大小（递归求和，文件/目录通用；失败返回 -1） */
    public static long dirSize(File f) {
        try {
            if (f == null || !f.exists()) return 0;
            if (f.isFile()) return f.length();
            long total = 0;
            File[] list = f.listFiles();
            if (list == null) return 0;
            for (File c : list) {
                if (c.isDirectory()) {
                    total += dirSize(c);
                } else {
                    total += c.length();
                }
            }
            return total;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 存储空间信息："已用/总量" */
    public static String storageInfo(File dir) {
        try {
            android.os.StatFs sf = new android.os.StatFs(dir.getAbsolutePath());
            long total = sf.getTotalBytes();
            long free = sf.getAvailableBytes();
            return formatSize(total - free) + " / " + formatSize(total);
        } catch (Throwable t) {
            return "-";
        }
    }

    // ── 后台操作 ──

    /** 后台复制（文件或目录递归） */
    public static void copyAsync(File src, File destDir, Callback cb) {
        runAsync(() -> {
            try {
                copyRecursive(src, new File(destDir, src.getName()));
                return new Object[]{true, "已复制：" + src.getName()};
            } catch (Throwable t) {
                return new Object[]{false, "复制失败：" + t.getMessage()};
            }
        }, cb);
    }

    /** 同步复制（文件或目录递归；已在后台线程时使用，失败抛 IOException） */
    public static void copy(File src, File dest) throws IOException {
        copyRecursive(src, dest);
    }

    /** 后台移动：同分区 rename 原子完成，跨分区复制+删除 */
    public static void moveAsync(File src, File destDir, Callback cb) {
        runAsync(() -> {
            try {
                File dest = new File(destDir, src.getName());
                if (src.renameTo(dest)) {
                    return new Object[]{true, "已移动：" + src.getName()};
                }
                copyRecursive(src, dest);
                deleteRecursive(src);
                return new Object[]{true, "已移动（跨分区）：" + src.getName()};
            } catch (Throwable t) {
                return new Object[]{false, "移动失败：" + t.getMessage()};
            }
        }, cb);
    }

    /** 后台删除（文件或目录递归），返回删除的条目数 */
    public static void deleteAsync(File src, Callback cb) {
        runAsync(() -> {
            try {
                int n = deleteRecursive(src);
                return new Object[]{true, "已删除 " + n + " 项"};
            } catch (Throwable t) {
                return new Object[]{false, "删除失败：" + t.getMessage()};
            }
        }, cb);
    }

    /** 后台新建文件夹 */
    public static void mkdirAsync(File parent, String name, Callback cb) {
        runAsync(() -> {
            try {
                File dir = new File(parent, sanitize(name));
                if (dir.exists()) return new Object[]{false, "已存在同名文件/文件夹"};
                if (!dir.mkdirs()) return new Object[]{false, "创建失败（无写入权限？）"};
                return new Object[]{true, "已创建文件夹：" + dir.getName()};
            } catch (Throwable t) {
                return new Object[]{false, "创建失败：" + t.getMessage()};
            }
        }, cb);
    }

    /** 后台新建文件（支持带后缀/无后缀文件名） */
    public static void createFileAsync(File parent, String name, Callback cb) {
        runAsync(() -> {
            try {
                String n = sanitize(name);
                if (n.isEmpty()) return new Object[]{false, "文件名不能为空"};
                File f = new File(parent, n);
                if (f.exists()) return new Object[]{false, "已存在同名文件/文件夹"};
                if (!f.createNewFile()) return new Object[]{false, "创建失败（无写入权限？）"};
                return new Object[]{true, "已创建文件：" + f.getName()};
            } catch (Throwable t) {
                return new Object[]{false, "创建失败：" + t.getMessage()};
            }
        }, cb);
    }

    /** 后台重命名 */
    public static void renameAsync(File src, String newName, Callback cb) {
        runAsync(() -> {
            try {
                String n = sanitize(newName);
                if (n.isEmpty()) return new Object[]{false, "名称不能为空"};
                File dest = new File(src.getParentFile(), n);
                if (dest.exists()) return new Object[]{false, "已存在同名文件/文件夹"};
                if (!src.renameTo(dest)) return new Object[]{false, "重命名失败"};
                return new Object[]{true, "已重命名为：" + n};
            } catch (Throwable t) {
                return new Object[]{false, "重命名失败：" + t.getMessage()};
            }
        }, cb);
    }

    // ── 内部 ──

    private static void runAsync(final java.util.function.Supplier<Object[]> task, final Callback cb) {
        new Thread(() -> {
            Object[] result = task.get();
            MAIN.post(() -> {
                if (cb != null) cb.onDone((Boolean) result[0], (String) result[1]);
            });
        }, "HyVqFileOps").start();
    }

    /** 递归复制（含目录结构与文件），目标已存在时覆盖 */
    private static void copyRecursive(File src, File dest) throws IOException {
        if (src.isDirectory()) {
            if (!dest.exists() && !dest.mkdirs()) throw new IOException("无法创建目录 " + dest);
            File[] children = src.listFiles();
            if (children != null) {
                for (File c : children) {
                    copyRecursive(c, new File(dest, c.getName()));
                }
            }
        } else {
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("无法创建目录 " + parent);
            }
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
        }
    }

    /** 递归删除，返回删除条目数（文件+目录） */
    private static int deleteRecursive(File f) {
        if (!f.exists()) return 0;
        int count = 0;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    count += deleteRecursive(c);
                }
            }
        }
        f.delete();
        return count + 1;
    }

    /** 文件名净化：去路径分隔符/控制字符，防路径穿越 */
    private static String sanitize(String name) {
        if (name == null) return "";
        String n = name.replace('/', ' ').replace('\\', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        if (n.equals(".") || n.equals("..")) return "";
        return n.length() > 120 ? n.substring(0, 120) : n;
    }
}