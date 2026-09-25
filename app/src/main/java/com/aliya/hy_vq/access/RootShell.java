package com.aliya.hy_vq.access;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Root 执行核心（v2.8.0）。
 *
 * <p><b>实测基线</b>（本机 Android 16 / Magisk + susfs4ksu，2026-09）：单次
 * {@code su -c true} 约 19ms（Magisk 已记住授权），故采用「一条命令一个 su 进程」
 * 模型——比常驻 root shell 简单得多，且没有管道粘包、进程僵死、超时后遗留
 * 脏状态等问题。</p>
 *
 * <p><b>SELinux</b>：本机 Enforcing，su 会话域为 {@code u:r:magisk:s0}（可读
 * /data/data）。注意 root 在 App 私有目录里新建的文件，上下文只有
 * {@code u:object_r:app_data_file:s0}，<b>缺少 App 的 categories</b>，App 读不了，
 * 必须 {@code restorecon} 补类别——相关处理见 {@link RootFs#relayToCache}。</p>
 */
public final class RootShell {

    private static final String TAG = "HyVqRoot";

    /** 单条命令输出上限（防 /proc、/sys 之类把内存吃爆） */
    private static final int OUT_LIMIT = 8 * 1024 * 1024;
    /** 常规命令超时 */
    public static final int TIMEOUT_MS = 15000;
    /** 大文件操作超时（复制/解压等） */
    public static final int TIMEOUT_LONG_MS = 120000;
    /** 授权等待：用户要在 Magisk 弹窗上点「允许」 */
    private static final int GRANT_TIMEOUT_MS = 60000;
    /** 静默探测超时：不打扰用户，3 秒内没结果就当作未授权 */
    private static final int PROBE_TIMEOUT_MS = 3000;
    /** 被拒绝后的冷却：用户可能去 Magisk 里改设置，20 秒后可重试 */
    private static final long DENIED_COOLDOWN_MS = 20000L;

    public static final int STATE_UNKNOWN = -1;
    /** 系统里根本没有 su */
    public static final int STATE_NONE = 0;
    /** 有 su 但未授权（被拒 / 未响应） */
    public static final int STATE_DENIED = 1;
    /** 已获得 root */
    public static final int STATE_GRANTED = 2;

    /** 命令执行结果 */
    public static final class Result {
        public final int code;
        public final String out;
        public final String err;
        public final boolean timeout;

        Result(int code, String out, String err, boolean timeout) {
            this.code = code;
            this.out = out == null ? "" : out;
            this.err = err == null ? "" : err;
            this.timeout = timeout;
        }

        public boolean ok() {
            return !timeout && code == 0;
        }

        /** 首行错误信息（用于 Toast，不刷屏） */
        public String firstError() {
            if (timeout) return "执行超时";
            String e = err.trim();
            if (e.isEmpty()) e = out.trim();
            int nl = e.indexOf('\n');
            if (nl > 0) e = e.substring(0, nl);
            return e.isEmpty() ? ("退出码 " + code) : e;
        }
    }

    private static volatile int state = STATE_UNKNOWN;
    private static volatile long deniedAt = 0L;
    private static volatile boolean suLocated = false;
    private static volatile String suBin = null;

    private RootShell() {}

    // ── su 定位 ──────────────────────────────────────────────

    /** 定位 su 可执行文件；找不到返回 null（结果缓存） */
    public static String suBinary() {
        if (suLocated) return suBin;
        synchronized (RootShell.class) {
            if (suLocated) return suBin;
            String[] cands = {
                    "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
                    "/debug_ramdisk/su", "/system/sbin/su", "/vendor/bin/su",
                    "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su"
            };
            for (String c : cands) {
                try {
                    if (new File(c).exists()) {
                        suBin = c;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (suBin == null) {
                String path = System.getenv("PATH");
                if (path != null) {
                    for (String dir : path.split(":")) {
                        if (dir.isEmpty()) continue;
                        File f = new File(dir, "su");
                        try {
                            if (f.exists() && f.canExecute()) {
                                suBin = f.getAbsolutePath();
                                break;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            if (suBin == null) {
                // ⭐ 关键实测（本机 ColorOS / Android 16）：/system/bin/su 的 SELinux
                // 标签是 u:object_r:shell_exec:s0，App(untrusted_app) 对它的 getattr
                // 会被拒绝 → new File("/system/bin/su").exists() 恒为 false（logcat 实测
                // 打出 "su 路径：null"）。但**执行** su 是另一条权限（execute），
                // 这才是可用性的真正判据。故退回裸命令名，交给内核/PATH 解析。
                suBin = "su";
            }
            suLocated = true;
            Log.i(TAG, "su 路径：" + suBin);
            return suBin;
        }
    }

    // ── 授权状态 ─────────────────────────────────────────────

    public static int state() {
        return state;
    }

    public static boolean isGranted() {
        return state == STATE_GRANTED;
    }

    /** 中文状态标签（UI 展示） */
    public static String stateLabel() {
        switch (state) {
            case STATE_GRANTED: return "已授权";
            case STATE_DENIED: return "未授权";
            case STATE_NONE: return "无 su";
            default: return "未检测";
        }
    }

    /**
     * 静默检测（可反复调用）：已授权 / 无 su 直接返回缓存；
     * 被拒绝的 20 秒冷却期内不重复探测，避免每进一个目录就弹一次授权窗。
     */
    public static int detect() {
        if (state == STATE_GRANTED || state == STATE_NONE) return state;
        if (state == STATE_DENIED
                && System.currentTimeMillis() - deniedAt < DENIED_COOLDOWN_MS) return state;
        return probe(false);
    }

    /**
     * 纯静默检测：<b>不执行 su</b>，只判断系统里有没有 su 文件。
     *
     * <p>首次启动用这个：从未授权过的设备上执行 {@code su -c id} 会让 Magisk 弹授权窗，
     * App 一启动就弹窗很打扰。授权过的设备（记住过授权）走 {@link #detect()}，
     * Magisk 会静默放行、约 20ms 返回，用户无感。</p>
     */
    public static int silentDetect() {
        if (state == STATE_GRANTED) return state;
        state = suBinary() == null ? STATE_NONE : STATE_DENIED;
        deniedAt = System.currentTimeMillis();
        return state;
    }

    /** 主动申请授权（触发 Magisk 授权弹窗，超时 60 秒） */
    public static int request() {
        return probe(true);
    }

    /** 重置为未检测（供「重试」按钮使用） */
    public static void reset() {
        state = STATE_UNKNOWN;
        deniedAt = 0L;
    }

    private static synchronized int probe(boolean prompt) {
        if (suBinary() == null) {
            state = STATE_NONE;
            return state;
        }
        Result r = exec("id", prompt ? GRANT_TIMEOUT_MS : PROBE_TIMEOUT_MS);
        if (!r.timeout && r.ok() && r.out.contains("uid=0")) {
            state = STATE_GRANTED;
            Log.i(TAG, "root 已授权");
        } else {
            state = STATE_DENIED;
            deniedAt = System.currentTimeMillis();
            Log.i(TAG, "root 未授权：" + r.firstError());
        }
        return state;
    }

    // ── 命令执行 ─────────────────────────────────────────────

    public static Result exec(String cmd) {
        return exec(cmd, TIMEOUT_MS, false);
    }

    public static Result exec(String cmd, int timeoutMs) {
        return exec(cmd, timeoutMs, false);
    }

    /**
     * 以 root 执行 shell 命令。命令原样交给 {@code su -c}，可含管道/重定向等
     * shell 语法；路径请统一用 {@link #q} 转义。
     *
     * @param quiet 为 true 时输出不进日志（用于可能含敏感内容的读取）
     */
    public static Result exec(String cmd, int timeoutMs, boolean quiet) {
        String su = suBinary();
        if (su == null) return new Result(127, "", "未找到 su", false);
        if (!quiet) Log.d(TAG, "exec: " + cmd);
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(su, "-c", cmd);
            pb.redirectErrorStream(false);
            proc = pb.start();
            ByteArrayOutputStream ob = new ByteArrayOutputStream();
            ByteArrayOutputStream eb = new ByteArrayOutputStream();
            Thread t1 = drain(proc.getInputStream(), ob);
            Thread t2 = drain(proc.getErrorStream(), eb);
            boolean done;
            try {
                done = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                done = false;
                Thread.currentThread().interrupt();
            }
            if (!done) {
                proc.destroy();
                try {
                    proc.waitFor(2, TimeUnit.SECONDS);
                } catch (Throwable ignored) {
                }
            }
            try {
                t1.join(1500);
                t2.join(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            int code = -1;
            if (done) {
                try {
                    code = proc.exitValue();
                } catch (Throwable ignored) {
                }
            }
            return new Result(code, utf8(ob), utf8(eb), !done);
        } catch (Throwable t) {
            Log.w(TAG, "su 执行异常: " + t);
            if (proc != null) {
                try {
                    proc.destroy();
                } catch (Throwable ignored) {
                }
            }
            String msg = t.getMessage();
            return new Result(-1, "", msg == null ? t.toString() : msg, false);
        }
    }

    private static String utf8(ByteArrayOutputStream b) {
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Thread drain(final InputStream in, final ByteArrayOutputStream sink) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    int room = OUT_LIMIT - sink.size();
                    if (room <= 0) continue;
                    sink.write(buf, 0, Math.min(n, room));
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }, "hyvq-root-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ── 工具 ─────────────────────────────────────────────────

    /** shell 单引号转义（路径安全入参） */
    public static String q(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** chmod 模式校验：2~4 位八进制 */
    public static boolean validMode(String mode) {
        if (mode == null) return false;
        String m = mode.trim();
        if (m.length() < 2 || m.length() > 4) return false;
        for (int i = 0; i < m.length(); i++) {
            char c = m.charAt(i);
            if (c < '0' || c > '7') return false;
        }
        return true;
    }

    /** chown 属主校验：user 或 user:group，允许字母数字与 _.- */
    public static boolean validOwner(String owner) {
        if (owner == null) return false;
        String o = owner.trim();
        if (o.isEmpty()) return false;
        String[] parts = o.split(":", -1);
        if (parts.length > 2) return false;
        for (String p : parts) {
            if (p.isEmpty()) continue;
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                boolean okc = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-';
                if (!okc) return false;
            }
        }
        return true;
    }
}
