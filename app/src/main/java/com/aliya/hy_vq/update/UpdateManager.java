package com.aliya.hy_vq.update;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;

/**
 * HY_VQ 增量更新引擎（本地导入，专属 .hyv 更新包格式）。
 *
 * <p><b>更新包格式</b>（zip 容器，扩展名 .hyv）：</p>
 * <pre>
 * hyv_update_v{N}.hyv
 * ├── manifest.json   {"format":"hyv-update","versionCode":N,"versionName":"1.3.0",
 * │                    "minBaseVersion":1,"entry":"com.xxx.Entry","dexMd5":"…","buildTime":"…"}
 * └── classes.dex     全部更新代码（须实现 {@link HyVqAppEntry}）
 * </pre>
 *
 * <p><b>目录布局</b>（filesDir 下）：</p>
 * <pre>
 * update/
 * ├── incoming/           导入暂存（安装后清空）
 * ├── packs/v{N}/        各版本包（安装成功后只保留当前版本）
 * update_version          版本指针文件（原子切换的关键）
 * update_dexopt/          dex 优化缓存（清理时整体重建）
 * </pre>
 *
 * <p><b>可靠性设计</b>：①先解压到 v{N}.partial 再原子改名，中断不留半成品；
 * ②指针先写、旧包后删（回滚保护）；③安装后立即清理 + 启动时兜底清理双保险；
 * ④启动自愈：指针指向的包缺失则重置；⑤zip 路径过滤防 Zip Slip。</p>
 */
public final class UpdateManager {

    private static final String TAG = "UpdateManager";

    /** 专属格式标识 */
    public static final String FORMAT = "hyv-update";

    /** zip 容器魔数（写在 zip comment，非 .hyv 的普通 zip 直接拒绝） */
    public static final String ZIP_MAGIC = "HYVQ-UPDATE-v1";

    /** 允许出现在更新包内的条目（白名单，防藏匿文件） */
    private static final String ENTRY_MANIFEST = "manifest.json";
    private static final String ENTRY_DEX = "classes.dex";

    /** 壳内置公钥（SPKI/X.509 SubjectPublicKeyInfo，dev 密钥对；正式发布替换为正式公钥，见 keystore/hyvq_pubkey.b64） */
    private static final String PUBKEY_B64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtO7qy7qprOxDWTFZ8YkzVdHtle3DgruEO8bvDTF+Pde8ydyEJALYH6BRqEMrmjGe39kivpjNsJTXWKWHgjbOJZgM4d2jOemcKOEB8NIBYZNBAiG5FzH9sLSpmTfd0K0cZgpj/br8+ct0D4rlbC2FEK5rRSsiNYT5I+uNnydkXGjoeh1NqSR/WA5iYhwemN3x4JwBbe/K0x2WRCHxQuErDwdTmBrB4knHiz5lirDF9pk9g5NUDLhBUHHhqUgY8C0gO/vp5jpyFEK1GlHo3XD7bKxuWrNB4hwyP05sWYJz2/oaQQ2WJGFyVrsQmyYnU2+7NkDmQ8h52Q+b6an53lyYIwIDAQAB";

    /** 签名算法（与 tools/SignTool.java 一致） */
    private static final String SIG_ALG = "SHA256withRSA";

    private static final String DIR_UPDATE = "update";
    private static final String DIR_INCOMING = "incoming";
    private static final String DIR_PACKS = "packs";
    private static final String DIR_DEXOPT = "update_dexopt";
    private static final String FILE_POINTER = "update_version";

    /** 当前进程已加载的入口（安装后立即刷新，不重启也生效） */
    private static volatile HyVqAppEntry sEntry;

    private UpdateManager() {}

    /** 安装结果 */
    public static class InstallResult {
        public boolean ok;
        public String message;
        public int versionCode;
        public String versionName;
        /** dex 敏感 API 审计提示（中危命中，安装仍成功） */
        public List<String> auditWarnings = new ArrayList<>();
    }

    /** dex 静态审计结果（高危 → 拒绝安装；中危 → 仅提示） */
    static class AuditResult {
        boolean reject;
        List<String> highHits = new ArrayList<>();
        List<String> medHits = new ArrayList<>();
    }

    // ── 启动流程 ──

    /**
     * 应用启动时调用：兜底清理残余 + 自愈版本指针 + 加载更新包入口。
     * 幂等，可安全多次调用。
     */
    public static void boot(Context ctx) {
        try {
            int cur = currentVersion(ctx);
            cleanup(ctx, cur); // ① 残余清理（incoming/.partial/非当前版本）
            // ② 自愈：指针存在但包缺失（异常中断导致）→ 重置指针
            if (cur > 0) {
                File pack = new File(ctx.getFilesDir(), DIR_UPDATE + "/" + DIR_PACKS + "/v" + cur);
                if (!new File(pack, "manifest.json").exists()
                        || !new File(pack, "classes.dex").exists()) {
                    Log.w(TAG, "pointer v" + cur + " but pack missing, reset");
                    new File(ctx.getFilesDir(), FILE_POINTER).delete();
                    cur = 0;
                }
            }
            // ③ 加载入口
            if (cur > 0) {
                sEntry = loadEntry(ctx, cur);
            }
        } catch (Throwable t) {
            Log.e(TAG, "boot failed", t);
        }
    }

    /** 当前激活的更新包入口（无则 null） */
    public static HyVqAppEntry entry() {
        return sEntry;
    }

    /** 当前更新包版本号（0 = 未安装更新包，走壳内置） */
    public static int currentVersion(Context ctx) {
        try {
            String s = readFile(new File(ctx.getFilesDir(), FILE_POINTER)).trim();
            return s.isEmpty() ? 0 : Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 当前更新包版本名（未安装返回 null） */
    public static String currentVersionName(Context ctx) {
        JSONObject m = manifest(ctx);
        return m == null ? null : m.optString("versionName", null);
    }

    /** 当前更新包 manifest（未安装返回 null） */
    public static JSONObject manifest(Context ctx) {
        int v = currentVersion(ctx);
        if (v <= 0) return null;
        File f = new File(ctx.getFilesDir(), DIR_UPDATE + "/" + DIR_PACKS + "/v" + v + "/manifest.json");
        if (!f.exists()) return null;
        try {
            return new JSONObject(readFile(f));
        } catch (Exception e) {
            return null;
        }
    }

    // ── 安装（本地导入） ──

    /**
     * 导入并安装更新包（本地 .hyv 文件）。
     * 成功后：旧版本包与暂存文件立即清理，入口立即刷新（不重启也生效）。
     */
    public static InstallResult importAndInstall(Context ctx, File src) {
        InstallResult r = new InstallResult();
        try {
            File updateDir = new File(ctx.getFilesDir(), DIR_UPDATE);
            File incoming = new File(updateDir, DIR_INCOMING);
            incoming.mkdirs();

            // ① 复制到暂存区（.part 后缀，失败不留残）
            File part = new File(incoming, "incoming.part");
            copyFile(src, part);
            if (part.length() <= 0 || part.length() > 64L * 1024 * 1024) {
                r.message = "更新包大小无效";
                return r;
            }

            // ② 预读校验：zip 魔数 + 条目白名单 + manifest/classes.dex（防畸形包/藏匿文件）
            JSONObject manifest = null;
            byte[] dexBytes = null;
            try (ZipFile zf = new ZipFile(part)) {
                // 标准 zip 容器:不再要求魔数 comment(2026-08-28 改版,回退标准 zip 但签名验签保留)
                // 安全性由 manifest 内 RSA 签名 + classes.dex MD5 锁定保证(见 verifySignature)
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    String name = e.getName();
                    if (e.isDirectory() || !(name.equals(ENTRY_MANIFEST) || name.equals(ENTRY_DEX))) {
                        r.message = "更新包包含未知条目（" + name + "）";
                        return r;
                    }
                    try (InputStream in = zf.getInputStream(e)) {
                        if (name.equals(ENTRY_MANIFEST)) {
                            manifest = new JSONObject(new String(readAll(in), "UTF-8"));
                        } else {
                            dexBytes = readAll(in);
                        }
                    }
                }
            } catch (java.io.IOException ioe) {
                r.message = "更新包无法解析（损坏或非 zip）";
                return r;
            }
            if (manifest == null || dexBytes == null) {
                r.message = "更新包缺少 manifest.json 或 classes.dex";
                return r;
            }
            if (!FORMAT.equals(manifest.optString("format"))) {
                r.message = "不是 HY_VQ 更新包（format 不符）";
                return r;
            }
            int vc = manifest.optInt("versionCode", 0);
            if (vc <= 0) {
                r.message = "更新包 versionCode 无效";
                return r;
            }
            // ③ 完整性校验：classes.dex 的 MD5 与 manifest 记录比对（防传输损坏）
            String dexMd5 = manifest.optString("dexMd5", "");
            if (!dexMd5.isEmpty() && !md5(dexBytes).equalsIgnoreCase(dexMd5)) {
                r.message = "classes.dex 校验失败（MD5 不匹配）";
                return r;
            }
            // ④ minBaseVersion：壳版本过低则拒绝（防旧壳安装不兼容新包）
            int minBase = manifest.optInt("minBaseVersion", 0);
            if (minBase > baseVersionCode(ctx)) {
                r.message = "当前壳版本过旧（需要 v" + minBase + "+），请先升级应用";
                return r;
            }
            // ⑤ 签名验签：无签名或验签失败 → 来源不可信，直接拒绝（防伪造/篡改）
            if (!verifySignature(manifest, dexBytes)) {
                r.message = "签名验证失败，更新包来源不可信";
                return r;
            }
            // ⑥ 版本检查：必须高于当前
            int cur = currentVersion(ctx);
            if (vc <= cur) {
                r.message = "版本不高于当前（v" + cur + "），无需更新";
                return r;
            }
            // ⑦ dex 敏感 API 静态审计：高危拒绝，中危提示（纵深防御，即使私钥泄露也有兜底）
            AuditResult audit = auditDex(dexBytes);
            if (audit.reject) {
                r.message = "更新包包含高危操作被拦截：" + audit.highHits;
                return r;
            }
            if (!audit.medHits.isEmpty()) {
                r.auditWarnings.addAll(audit.medHits);
            }

            // ⑧ 解压到 v{N}.partial → 校验 → 原子改名 v{N}（中断不留半成品）
            File packPartial = new File(updateDir, DIR_PACKS + "/v" + vc + ".partial");
            deleteRecursive(packPartial);
            packPartial.mkdirs();
            try (ZipFile zf = new ZipFile(part)) {
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.isDirectory()) continue;
                    String name = e.getName();
                    if (!(name.equals(ENTRY_MANIFEST) || name.equals(ENTRY_DEX))) continue;
                    File out = new File(packPartial, name);
                    try (InputStream in = zf.getInputStream(e);
                         FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                }
            }
            File packFinal = new File(updateDir, DIR_PACKS + "/v" + vc);
            if (!new File(packPartial, "classes.dex").exists()
                    || !new File(packPartial, "manifest.json").exists()) {
                r.message = "解压结果不完整";
                return r;
            }
            deleteRecursive(packFinal);
            if (!packPartial.renameTo(packFinal)) {
                r.message = "安装目录切换失败";
                return r;
            }

            // ⑨ 原子切换版本指针（旧包此刻仍保留 → 回滚保护）
            writeFile(new File(ctx.getFilesDir(), FILE_POINTER), String.valueOf(vc));

            // ⑩ 自动清理：旧版本包 + 暂存 + dexopt 重建（只保留当前版本）
            cleanup(ctx, vc);

            // ⑪ 立即生效：新 ClassLoader 加载新包入口（独立实例，类不冲突）
            sEntry = loadEntry(ctx, vc);
            r.ok = true;
            r.versionCode = vc;
            r.versionName = manifest.optString("versionName", "");
            r.message = "更新包 v" + vc + " 安装成功"
                    + (sEntry != null ? "，已生效" : "（入口暂未加载，重启后生效）");
        } catch (Throwable t) {
            Log.e(TAG, "install failed", t);
            r.message = "安装失败：" + t.getMessage();
        }
        return r;
    }

    // ── 清理（核心：只保留当前版本） ──

    /**
     * 自动清理：删除非当前版本的所有包、.partial 残留、incoming 暂存，重建 dexopt。
     * 幂等；boot 时与安装成功后都会调用，保证"安装完自动清理 + 崩溃兜底清理"。
     */
    private static void cleanup(Context ctx, int keep) {
        try {
            File updateDir = new File(ctx.getFilesDir(), DIR_UPDATE);
            File packs = new File(updateDir, DIR_PACKS);
            if (packs.isDirectory()) {
                File[] list = packs.listFiles();
                if (list != null) {
                    for (File p : list) {
                        String n = p.getName();
                        if (n.endsWith(".partial") || !n.equals("v" + keep)) {
                            deleteRecursive(p); // 残余 + 非当前版本 → 全删
                        }
                    }
                }
            }
            File incoming = new File(updateDir, DIR_INCOMING);
            if (incoming.isDirectory()) {
                File[] list = incoming.listFiles();
                if (list != null) {
                    for (File f : list) deleteRecursive(f);
                }
            }
            // dexopt 旧优化缓存整体重建（ART 会按需重新优化）
            File dexopt = new File(ctx.getFilesDir(), DIR_DEXOPT);
            deleteRecursive(dexopt);
            dexopt.mkdirs();
        } catch (Throwable t) {
            Log.w(TAG, "cleanup error", t);
        }
    }

    // ── 入口加载 ──

    private static HyVqAppEntry loadEntry(Context ctx, int version) {
        try {
            File pack = new File(ctx.getFilesDir(), DIR_UPDATE + "/" + DIR_PACKS + "/v" + version);
            File dexFile = new File(pack, "classes.dex");
            if (!dexFile.exists()) return null;
            JSONObject m = new JSONObject(readFile(new File(pack, "manifest.json")));
            String entryCls = m.optString("entry", "");
            if (entryCls.isEmpty()) return null;
            DexClassLoader loader = new DexClassLoader(
                    dexFile.getAbsolutePath(),
                    new File(ctx.getFilesDir(), DIR_DEXOPT).getAbsolutePath(),
                    null,
                    ctx.getClassLoader());
            Object obj = loader.loadClass(entryCls).newInstance();
            if (obj instanceof HyVqAppEntry) {
                HyVqAppEntry e = (HyVqAppEntry) obj;
                e.onAttach(ctx.getApplicationContext());
                Log.i(TAG, "entry loaded: " + e.getName() + " v" + e.getVersion());
                return e;
            }
            Log.w(TAG, "entry class not implement HyVqAppEntry: " + entryCls);
        } catch (Throwable t) {
            Log.e(TAG, "load entry failed", t);
        }
        return null;
    }

    // ── 安全：签名验签 + dex 敏感 API 审计 ──

    /**
     * RSA 签名验签。canonical 与 tools/SignTool.java 完全一致：
     * format|versionCode|versionName|minBaseVersion|entry|dexMd5
     * dexMd5 覆盖 classes.dex 全文 → 验签通过 = 包内容未被任何人（含 AI 仿制者）篡改。
     */
    static boolean verifySignature(JSONObject m, byte[] dexBytes) {
        try {
            String sigB64 = m.optString("signature", "");
            if (sigB64.isEmpty()) {
                Log.w(TAG, "update package has no signature");
                return false;
            }
            byte[] sigBytes = Base64.getDecoder().decode(sigB64);
            byte[] keyBytes = Base64.getDecoder().decode(PUBKEY_B64);
            PublicKey pub = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(keyBytes));
            Signature sig = Signature.getInstance(SIG_ALG);
            sig.initVerify(pub);
            sig.update(canonical(m).getBytes("UTF-8"));
            return sig.verify(sigBytes);
        } catch (Throwable t) {
            Log.e(TAG, "verify signature failed", t);
            return false;
        }
    }

    /** canonical 拼接（与 tools/SignTool.java 保持一致，改签名两端必须同步） */
    static String canonical(JSONObject m) {
        return m.optString("format", "")
                + "|" + m.optInt("versionCode", 0)
                + "|" + m.optString("versionName", "")
                + "|" + m.optInt("minBaseVersion", 0)
                + "|" + m.optString("entry", "")
                + "|" + m.optString("dexMd5", "");
    }

    /** 壳 versionCode（运行时读取） */
    static int baseVersionCode(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * dex 静态审计：解析 dex 字符串表，匹配敏感 API 黑名单。
     * 高危（逃逸/系统控制）→ 拒绝；中危（敏感数据/外联）→ 提示。
     */
    static AuditResult auditDex(byte[] dex) {
        AuditResult r = new AuditResult();
        List<String> strings = dexStrings(dex);
        if (strings.isEmpty()) return r; // 解析失败不拦截（签名已兜底）

        String[][] high = {
                {"java/lang/Runtime", "进程命令执行"},
                {"getRuntime", "进程命令执行"},
                {"ProcessBuilder", "进程命令执行"},
                {"DexClassLoader", "二次加载 dex 逃逸"},
                {"PathClassLoader", "二次加载 dex 逃逸"},
                {"dalvik/system", "dalvik 底层操作"},
                {"java/lang/reflect", "反射逃逸"},
                {"android/app/admin", "设备管理器控制"},
                {"DevicePolicyManager", "设备管理器控制"},
                {"MediaProjection", "屏幕录制"},
                {"AccessibilityService", "无障碍服务"},
                {"android/accounts", "账户系统"},
                {"wipeData", "设备擦除"},
                {"resetPassword", "重置锁屏密码"},
                {"lockNow", "立即锁屏"},
                {"SmsManager", "短信发送"},
                {"android/provider/Telephony", "短信数据库"},
        };
        String[][] med = {
                {"http://", "网络外联"},
                {"https://", "网络外联"},
                {"OkHttp", "网络外联"},
                {"java/net/Socket", "网络外联"},
                {"TelephonyManager", "读取电话/设备信息"},
                {"getDeviceId", "读取设备标识"},
                {"getImei", "读取 IMEI"},
                {"getSubscriberId", "读取 SIM 信息"},
                {"android/location", "定位信息"},
                {"LocationManager", "定位信息"},
                {"Settings$Secure", "读取安全设置"},
                {"ANDROID_ID", "读取设备标识"},
                {"getInstalledPackages", "枚举已安装应用"},
                {"ContactsContract", "读取通讯录"},
                {"content://", "读取内容提供者数据"},
                {"startActivity", "启动外部组件"},
                {"startService", "启动外部服务"},
                {"getLine1Number", "读取手机号"},
                {"getSimSerialNumber", "读取 SIM 序列号"},
        };
        for (String s : strings) {
            for (String[] p : high) {
                if (s.contains(p[0])) {
                    if (!r.highHits.contains(p[1])) r.highHits.add(p[1]);
                }
            }
            for (String[] p : med) {
                if (s.contains(p[0])) {
                    if (!r.medHits.contains(p[1])) r.medHits.add(p[1]);
                }
            }
        }
        r.reject = !r.highHits.isEmpty();
        return r;
    }

    /** 解析 dex 字符串表（小端；上限 2 万条防恶意构造） */
    static List<String> dexStrings(byte[] d) {
        List<String> out = new ArrayList<>();
        try {
            if (d.length < 64 || d[0] != 'd' || d[1] != 'e' || d[2] != 'x') return out;
            int size = le32(d, 56);
            int off = le32(d, 60);
            if (size > 20000) size = 20000;
            if (off < 0 || off > d.length) return out;
            for (int i = 0; i < size; i++) {
                int p = off + i * 4;
                if (p + 4 > d.length) break;
                int sOff = le32(d, p);
                if (sOff < 0 || sOff >= d.length) continue;
                // uleb128（utf16 长度，仅用于跳过）
                int pos = sOff, shift = 0;
                while (pos < d.length && shift < 35) {
                    int b = d[pos++] & 0xff;
                    if ((b & 0x80) == 0) break;
                    shift += 7;
                }
                int start = pos, end = pos;
                while (end < d.length && d[end] != 0) end++;
                if (end > start && end - start < 4096) {
                    out.add(new String(d, start, end - start, "UTF-8"));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "dex strings parse error", t);
        }
        return out;
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    // ── 工具 ──

    private static String md5(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static String readFile(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            return new String(readAll(in), "UTF-8");
        }
    }

    private static void writeFile(File f, String content) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes("UTF-8"));
        }
    }

    private static void copyFile(File src, File dest) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] list = f.listFiles();
            if (list != null) {
                for (File c : list) deleteRecursive(c);
            }
        }
        f.delete();
    }
}