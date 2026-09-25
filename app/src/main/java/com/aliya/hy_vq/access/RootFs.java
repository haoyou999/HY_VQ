package com.aliya.hy_vq.access;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Root 文件系统操作（v2.8.0）。
 *
 * <p>所有方法<b>同步阻塞</b>，必须在工作线程调用（FileManagerModule 用
 * TASK_QUEUE / 具名线程）。命令统一走 {@link RootShell#exec}。</p>
 *
 * <p><b>一项实测</b>：本机 toybox 0.8.12 的 {@code stat} 支持 {@code %A %a %u %g %U %G
 * %s %Y %h %C}，{@code find -exec stat ... +} 批量列目录 494 项仅 36ms，比解析
 * {@code ls -l} 更可靠（文件名含空格/特殊字符不用猜字段），故列目录用
 * {@code find + stat}；只有符号链接目标额外走一次批量 {@code ls -l}。</p>
 */
public final class RootFs {

    private static final String TAG = "HyVqRootFs";
    /** 分段标记（正则安全字符，不会与路径/文件名冲突） */
    private static final String SEP = "@@@HYVQSEP@@@";
    /** 文本读取上限（编辑器防呆） */
    public static final int TEXT_LIMIT = 2 * 1024 * 1024;

    private RootFs() {}

    // ── 路径判定 ─────────────────────────────────────────────

    /**
     * 是否「应当走 root」的系统路径。
     *
     * <p>root 模式下这些路径直接由 root 列目录：既能绕过 scoped storage 限制，
     * 也能看到真实内容（而不是虚拟层的白名单枚举）。</p>
     *
     * <p>{@code /data/media}（= 内部存储真实位置）<b>排除在外</b>：App 自己有权限，
     * 走普通路径更快，也避免绕过 FUSE 语义。</p>
     */
    public static boolean preferRoot(String path) {
        String p = AccessRouter.normalize(path);
        if (p.equals("/") || p.equals("/data")) return true;
        if (p.startsWith("/data/media")) return false;
        String[] sys = {"/data/", "/system", "/vendor", "/product", "/odm", "/system_ext",
                "/vendor_dlkm", "/system_dlkm", "/metadata", "/sbin", "/root",
                "/debug_ramdisk", "/cache/", "/cust", "/my_", "/mnt/vendor", "/mnt/product",
                "/config", "/efs", "/persist", "/init"};
        for (String s : sys) {
            if (p.equals(s) || p.startsWith(s)) return true;
        }
        return false;
    }

    /** 是否需要（且可以用）root 来读这个路径 */
    public static boolean canRoot(String path) {
        return RootShell.isGranted() && path != null && !path.startsWith("content://");
    }

    // ── 列目录 ───────────────────────────────────────────────

    /**
     * 用 root 列出目录。
     *
     * @return 条目列表；目录不存在/无权限/超时返回 {@code null}（调用方据此回退）
     */
    public static List<FileEntry> list(String path) {
        if (!RootShell.isGranted()) return null;
        final String norm = AccessRouter.normalize(path);
        String cmd = "cd " + RootShell.q(norm) + " 2>/dev/null || exit 9; "
                + "echo " + SEP + "; "
                + "find . -maxdepth 1 -exec stat -c '%A|%s|%Y|%n' {} + 2>/dev/null; "
                + "echo " + SEP + "L; "
                + "find . -maxdepth 1 -type l -exec ls -l --full-time {} + 2>/dev/null; "
                + "exit 0";
        RootShell.Result r = RootShell.exec(cmd, 30000);
        if (r.code == 9 || (r.out.isEmpty() && !r.ok())) return null;

        String[] parts = r.out.split(SEP, -1);
        if (parts.length < 3) return null;

        Map<String, String> linkTargets = parseLinkTargets(parts[2]);
        List<FileEntry> out = new ArrayList<>();
        for (String line : parts[1].split("\n")) {
            FileEntry fe = parseEntryLine(line, norm, linkTargets);
            if (fe != null) out.add(fe);
        }
        return out;
    }

    /** 解析 {@code %A|%s|%Y|%n} 一行（name 用 split 限 4 段，含 | 与空格都安全） */
    private static FileEntry parseEntryLine(String line, String dir, Map<String, String> links) {
        if (line == null || line.isEmpty()) return null;
        int p1 = line.indexOf('|');
        if (p1 <= 0) return null;
        int p2 = line.indexOf('|', p1 + 1);
        if (p2 < 0) return null;
        int p3 = line.indexOf('|', p2 + 1);
        if (p3 < 0) return null;

        String perms = line.substring(0, p1);
        long size = parseLong(line.substring(p1 + 1, p2), 0L);
        long mtime = parseLong(line.substring(p2 + 1, p3), 0L) * 1000L;
        String name = line.substring(p3 + 1);
        if (name.startsWith("./")) name = name.substring(2);
        if (name.isEmpty() || ".".equals(name)) return null;

        String full = "/".equals(dir) ? "/" + name : dir + "/" + name;
        char t = perms.isEmpty() ? '-' : perms.charAt(0);
        if (t == 'd') return FileEntry.dir(full, mtime);
        if (t == 'l') return FileEntry.link(full, links.get(name), mtime);
        return FileEntry.file(full, size, mtime);
    }

    /** 解析 {@code ls -l --full-time} 的符号链接行，得到 名字 → 目标 */
    private static Map<String, String> parseLinkTargets(String block) {
        Map<String, String> map = new HashMap<>();
        if (block == null) return map;
        for (String line : block.split("\n")) {
            if (line.isEmpty() || line.startsWith("L")) continue;
            int arrow = line.lastIndexOf(" -> ");
            String left = arrow > 0 ? line.substring(0, arrow) : line;
            String target = arrow > 0 ? line.substring(arrow + 4) : null;
            // 跳过前 8 个字段：perms links owner group size date time tz
            int idx = 0;
            int fields = 0;
            while (fields < 8) {
                while (idx < left.length() && left.charAt(idx) == ' ') idx++;
                while (idx < left.length() && left.charAt(idx) != ' ') idx++;
                fields++;
            }
            while (idx < left.length() && left.charAt(idx) == ' ') idx++;
            if (idx >= left.length()) continue;
            String name = left.substring(idx);
            if (name.startsWith("./")) name = name.substring(2);
            if (!name.isEmpty() && target != null) map.put(name, target);
        }
        return map;
    }

    // ── 属性 ─────────────────────────────────────────────────

    /** 文件属性（root 视角，含 App 读不到的 uid/gid/SELinux） */
    public static final class Stat {
        public String path = "";
        public String perms = "";
        public String mode = "";
        public int uid = -1;
        public int gid = -1;
        public String owner = "";
        public String group = "";
        public long size = 0;
        public long mtime = 0;
        public int links = 0;
        public String selinux = "";
        public boolean isDir = false;
        public boolean isLink = false;

        /** 权限位（如 644）→ rwxr-xr-x */
        public String symbolic() {
            if (!mode.isEmpty()) return perms;
            return "";
        }
    }

    public static Stat stat(String path) {
        if (!RootShell.isGranted()) return null;
        String cmd = "stat -c '%A|%a|%u|%g|%U|%G|%s|%Y|%h|%C' " + RootShell.q(path) + " 2>&1";
        RootShell.Result r = RootShell.exec(cmd, 10000);
        if (!r.ok()) return null;
        String line = r.out.trim();
        int nl = line.indexOf('\n');
        if (nl > 0) line = line.substring(0, nl);
        String[] f = line.split("\\|", -1);
        if (f.length < 9) return null;
        Stat s = new Stat();
        s.path = path;
        s.perms = f[0];
        s.mode = f[1];
        s.uid = (int) parseLong(f[2], -1L);
        s.gid = (int) parseLong(f[3], -1L);
        s.owner = f[4];
        s.group = f[5];
        s.size = parseLong(f[6], 0L);
        s.mtime = parseLong(f[7], 0L) * 1000L;
        s.links = (int) parseLong(f[8], 0L);
        s.selinux = f.length > 9 ? f[9] : "";
        s.isDir = !s.perms.isEmpty() && s.perms.charAt(0) == 'd';
        s.isLink = !s.perms.isEmpty() && s.perms.charAt(0) == 'l';
        return s;
    }

    /** 是否存在（root 判定，绕过 App 权限） */
    public static boolean exists(String path) {
        if (!RootShell.isGranted()) return false;
        return RootShell.exec("test -e " + RootShell.q(path), 6000).ok();
    }

    // ── 读 ───────────────────────────────────────────────────

    /** 文本读取结果 */
    public static final class TextRead {
        public final String text;
        public final boolean truncated;
        public final String error;

        TextRead(String text, boolean truncated, String error) {
            this.text = text;
            this.truncated = truncated;
            this.error = error;
        }

        public boolean ok() {
            return error == null;
        }
    }

    /** 读取文本文件（root 权限）。截断时 truncated=true，UI 应禁止保存以免覆盖丢数据 */
    public static TextRead readText(String path, int limit) {
        if (!RootShell.isGranted()) return new TextRead(null, false, "未获得 root 权限");
        int lim = limit <= 0 ? TEXT_LIMIT : limit;
        RootShell.Result r = RootShell.exec(
                "head -c " + (lim + 1) + " " + RootShell.q(path) + " 2>&1", 20000, true);
        if (!r.ok()) return new TextRead(null, false, r.firstError());
        byte[] all = r.out.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        boolean truncated = all.length > lim;
        String text = truncated ? new String(all, 0, lim, java.nio.charset.StandardCharsets.UTF_8)
                : r.out;
        return new TextRead(text, truncated, null);
    }

    /** 文件大小（root） */
    public static long sizeOf(String path) {
        Stat s = stat(path);
        return s == null ? -1 : s.size;
    }

    /** 是否能被 root 读为文本（前 8KB 无 NUL 字节） */
    public static boolean looksText(String path) {
        if (!RootShell.isGranted()) return false;
        RootShell.Result r = RootShell.exec(
                "head -c 8192 " + RootShell.q(path) + " 2>/dev/null | od -An -c | grep -q '\\\\0'"
                        + " && echo BIN || echo TXT", 10000, true);
        return r.out.contains("TXT");
    }

    // ── 写 ───────────────────────────────────────────────────

    /**
     * 把 App 私有目录里的文件写入 root 目标路径。
     *
     * <p>覆盖已存在文件时用 {@code cp -f}（不 unlink，属主/权限/SELinux 上下文
     * 全部保留）；新建文件时属主为 root、权限 644。</p>
     */
    public static boolean writeLocalFile(File local, String dest) {
        if (!RootShell.isGranted() || local == null || !local.exists()) return false;
        String src = RootShell.q(local.getAbsolutePath());
        String d = RootShell.q(dest);
        String cmd = "if [ -e " + d + " ]; then cp -f " + src + " " + d
                + "; else cp " + src + " " + d + " && chmod 644 " + d + "; fi";
        RootShell.Result r = RootShell.exec(cmd, RootShell.TIMEOUT_LONG_MS);
        if (!r.ok()) Log.w(TAG, "写入失败 " + dest + "：" + r.firstError());
        return r.ok();
    }

    /** 新建文件（root，644） */
    public static boolean createFile(String path) {
        if (!RootShell.isGranted()) return false;
        return RootShell.exec(": > " + RootShell.q(path) + " && chmod 644 " + RootShell.q(path),
                10000).ok();
    }

    /** 新建目录（root，755，递归补父级） */
    public static boolean mkdirs(String path) {
        if (!RootShell.isGranted()) return false;
        return RootShell.exec("mkdir -p " + RootShell.q(path) + " && chmod 755 "
                + RootShell.q(path), 10000).ok();
    }

    /** 删除（递归，root）。不可恢复 */
    public static boolean delete(String path) {
        if (!RootShell.isGranted()) return false;
        RootShell.Result r = RootShell.exec("rm -rf " + RootShell.q(path),
                RootShell.TIMEOUT_LONG_MS);
        if (!r.ok()) Log.w(TAG, "删除失败 " + path + "：" + r.firstError());
        return r.ok();
    }

    /** 复制（root，保留属性） */
    public static boolean copy(String src, String dst) {
        if (!RootShell.isGranted()) return false;
        RootShell.Result r = RootShell.exec(
                "cp -a " + RootShell.q(src) + " " + RootShell.q(dst), RootShell.TIMEOUT_LONG_MS);
        if (!r.ok()) Log.w(TAG, "复制失败 " + src + "：" + r.firstError());
        return r.ok();
    }

    /** 移动/重命名（root；跨挂载点由 mv 自行 copy+delete） */
    public static boolean move(String src, String dst) {
        if (!RootShell.isGranted()) return false;
        RootShell.Result r = RootShell.exec(
                "mv " + RootShell.q(src) + " " + RootShell.q(dst), RootShell.TIMEOUT_LONG_MS);
        if (!r.ok()) Log.w(TAG, "移动失败 " + src + "：" + r.firstError());
        return r.ok();
    }

    /** 修改权限（root） */
    public static boolean chmod(String path, String mode, boolean recursive) {
        if (!RootShell.isGranted() || !RootShell.validMode(mode)) return false;
        String cmd = "chmod " + (recursive ? "-R " : "") + mode.trim() + " " + RootShell.q(path);
        RootShell.Result r = RootShell.exec(cmd, 60000);
        if (!r.ok()) Log.w(TAG, "chmod 失败 " + path + "：" + r.firstError());
        return r.ok();
    }

    /** 修改属主（root） */
    public static boolean chown(String path, String owner, boolean recursive) {
        if (!RootShell.isGranted() || !RootShell.validOwner(owner)) return false;
        String cmd = "chown " + (recursive ? "-R " : "") + owner.trim() + " " + RootShell.q(path);
        RootShell.Result r = RootShell.exec(cmd, 60000);
        if (!r.ok()) Log.w(TAG, "chown 失败 " + path + "：" + r.firstError());
        return r.ok();
    }

    // ── 挂载 ─────────────────────────────────────────────────

    /** 当前挂载读写状态：rw / ro / null（不是独立挂载点） */
    public static String mountState(String mountPoint) {
        if (!RootShell.isGranted()) return null;
        String cmd = "grep -E ' " + mountPoint + " ' /proc/mounts | head -1";
        RootShell.Result r = RootShell.exec(cmd, 8000);
        String line = r.out.trim();
        if (line.isEmpty()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(^|,)(rw|ro)(,|$)").matcher(line.split(" ")[3]);
        return m.find() ? m.group(2) : null;
    }

    /**
     * 尝试把分区重挂为可读写。
     *
     * <p>Android 10+ 是 system-as-root，{@code /system} 不是独立挂载点（
     * 实测 {@code mount -o remount,rw /system} 报 "not in /proc/mounts"），
     * 故对 /system 改为重挂 {@code /}。erofs 只读镜像分区即使 root 也无法
     * remount，这里如实返回结果，不假装成功。</p>
     */
    public static String remountRw(String mountPoint) {
        if (!RootShell.isGranted()) return "未获得 root 权限";
        final String target = "/system".equals(mountPoint) ? "/" : mountPoint;
        RootShell.Result r = RootShell.exec(
                "mount -o remount,rw " + RootShell.q(target) + " 2>&1", 20000);
        String st = mountState(mountPoint);
        if ("rw".equals(st)) return "已重挂为可读写：" + mountPoint;
        if (r.timeout) return "重挂超时（分区可能为只读镜像）";
        String err = r.firstError();
        return "重挂失败：" + err + "\n只读镜像分区（erofs）需用 Magisk 模块替换文件";
    }

    public static String remountRo(String mountPoint) {
        if (!RootShell.isGranted()) return "未获得 root 权限";
        final String target = "/system".equals(mountPoint) ? "/" : mountPoint;
        RootShell.Result r = RootShell.exec(
                "mount -o remount,ro " + RootShell.q(target) + " 2>&1", 20000);
        String st = mountState(mountPoint);
        if ("ro".equals(st)) return "已重挂为只读：" + mountPoint;
        return "重挂失败：" + r.firstError();
    }

    // ── App ↔ root 中转 ──────────────────────────────────────

    /**
     * 把 root 文件复制到 App 缓存目录，供内置查看器 / 编辑器打开。
     *
     * <p><b>关键</b>：root 在 App 私有目录新建的文件只有 {@code app_data_file:s0}，
     * 缺 App 的 SELinux categories，App 直接读会被拒绝——实测必须
     * {@code restorecon} 补类别，故这里固定带上。</p>
     *
     * @return 缓存文件；失败返回 null
     */
    public static File relayToCache(Context ctx, String rootPath) {
        if (!RootShell.isGranted() || ctx == null || rootPath == null) return null;
        File dir = new File(ctx.getCacheDir(), "root_relay");
        if (!dir.exists() && !dir.mkdirs()) return null;
        String name = new File(rootPath).getName();
        if (name.isEmpty()) name = "root_file";
        // 缓存名带路径哈希：不同目录的同名文件互不覆盖（否则两个 /build.gradle 会撞车）
        File dst = new File(dir, Integer.toHexString(rootPath.hashCode()) + "_" + name);
        String d = RootShell.q(dst.getAbsolutePath());
        String cmd = "rm -f " + d
                + "; cp -f " + RootShell.q(rootPath) + " " + d
                + " && chmod 644 " + d
                + "; restorecon " + d + " 2>/dev/null"
                + "; test -s " + d;
        RootShell.Result r = RootShell.exec(cmd, RootShell.TIMEOUT_LONG_MS);
        if (!r.ok()) {
            Log.w(TAG, "中转失败 " + rootPath + "：" + r.firstError());
            return null;
        }
        File f = new File(dst.getAbsolutePath());
        return f.exists() ? f : null;
    }

    /** 清理中转缓存（退出 root 模式 / 长时间不用时调用） */
    public static void clearRelayCache(Context ctx) {
        if (ctx == null) return;
        File dir = new File(ctx.getCacheDir(), "root_relay");
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            try {
                if (!k.delete()) Log.w(TAG, "中转缓存未删除：" + k.getName());
            } catch (Throwable ignored) {
            }
        }
    }

    // ── 辅助 ─────────────────────────────────────────────────

    private static long parseLong(String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
