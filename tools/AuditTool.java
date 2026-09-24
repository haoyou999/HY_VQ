import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * HY_VQ dex 敏感 API 审计工具（JDK 独立运行，逻辑与壳 UpdateManager.auditDex 一致）。
 * 用法: java AuditTool <classes.dex>
 * 输出: 高危命中（reject）/ 中危命中（warn）
 */
public class AuditTool {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("用法: java AuditTool <classes.dex>");
            System.exit(2);
        }
        byte[] dex;
        try (FileInputStream in = new FileInputStream(new File(args[0]))) {
            dex = in.readAllBytes();
        }
        List<String> strings = dexStrings(dex);
        System.out.println("strings extracted: " + strings.size());

        String[][] high = {
                {"java/lang/Runtime", "进程命令执行"}, {"getRuntime", "进程命令执行"},
                {"ProcessBuilder", "进程命令执行"}, {"DexClassLoader", "二次加载 dex 逃逸"},
                {"PathClassLoader", "二次加载 dex 逃逸"}, {"dalvik/system", "dalvik 底层操作"},
                {"java/lang/reflect", "反射逃逸"}, {"android/app/admin", "设备管理器控制"},
                {"DevicePolicyManager", "设备管理器控制"}, {"MediaProjection", "屏幕录制"},
                {"AccessibilityService", "无障碍服务"}, {"android/accounts", "账户系统"},
                {"wipeData", "设备擦除"}, {"resetPassword", "重置锁屏密码"},
                {"lockNow", "立即锁屏"}, {"SmsManager", "短信发送"},
                {"android/provider/Telephony", "短信数据库"},
        };
        String[][] med = {
                {"http://", "网络外联"}, {"https://", "网络外联"}, {"OkHttp", "网络外联"},
                {"java/net/Socket", "网络外联"}, {"TelephonyManager", "读取电话/设备信息"},
                {"getDeviceId", "读取设备标识"}, {"getImei", "读取 IMEI"},
                {"getSubscriberId", "读取 SIM 信息"}, {"android/location", "定位信息"},
                {"LocationManager", "定位信息"}, {"Settings$Secure", "读取安全设置"},
                {"ANDROID_ID", "读取设备标识"}, {"getInstalledPackages", "枚举已安装应用"},
                {"ContactsContract", "读取通讯录"}, {"content://", "读取内容提供者数据"},
                {"startActivity", "启动外部组件"}, {"startService", "启动外部服务"},
                {"getLine1Number", "读取手机号"}, {"getSimSerialNumber", "读取 SIM 序列号"},
        };
        List<String> highHits = new ArrayList<>(), medHits = new ArrayList<>();
        for (String s : strings) {
            for (String[] p : high) if (s.contains(p[0]) && !highHits.contains(p[1])) highHits.add(p[1]);
            for (String[] p : med) if (s.contains(p[0]) && !medHits.contains(p[1])) medHits.add(p[1]);
        }
        System.out.println("HIGH(拒绝): " + (highHits.isEmpty() ? "无" : highHits));
        System.out.println("MED(警告): " + (medHits.isEmpty() ? "无" : medHits));
        System.out.println(highHits.isEmpty() ? "AUDIT: PASS" : "AUDIT: REJECT");
        System.exit(highHits.isEmpty() ? 0 : 1);
    }

    static List<String> dexStrings(byte[] d) {
        List<String> out = new ArrayList<>();
        try {
            if (d.length < 64 || d[0] != 'd' || d[1] != 'e' || d[2] != 'x') return out;
            int size = le32(d, 56), off = le32(d, 60);
            if (size > 20000) size = 20000;
            if (off < 0 || off > d.length) return out;
            for (int i = 0; i < size; i++) {
                int p = off + i * 4;
                if (p + 4 > d.length) break;
                int sOff = le32(d, p);
                if (sOff < 0 || sOff >= d.length) continue;
                int pos = sOff, shift = 0;
                while (pos < d.length && shift < 35) {
                    int b = d[pos++] & 0xff;
                    if ((b & 0x80) == 0) break;
                    shift += 7;
                }
                int start = pos, end = pos;
                while (end < d.length && d[end] != 0) end++;
                if (end > start && end - start < 4096) out.add(new String(d, start, end - start, "UTF-8"));
            }
        } catch (Exception e) { /* ignore */ }
        return out;
    }

    static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }
}