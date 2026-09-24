import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HY_VQ 更新包验签工具（JDK 独立运行，逻辑与壳 UpdateManager.verifySignature 完全一致）。
 *
 * <p>用法：
 * <pre>
 *   java VerifyTool <manifest.json> <classes.dex> [公钥b64文件]
 * </pre>
 * 公钥默认读 keystore/hyvq_pubkey.b64；验签通过打印 OK，否则打印 FAIL 并退出码 1。
 * 用于开发者发布前自检：确认 .hyv 包在真机上会被壳接受。
 */
public class VerifyTool {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: java VerifyTool <manifest.json> <classes.dex> [公钥b64文件]");
            System.exit(2);
        }
        String pubFile = args.length >= 3 ? args[2]
                : "/storage/emulated/0/AndroidIDEProjects/HY_VQ/keystore/hyvq_pubkey.b64";

        String json = new String(readAll(new File(args[0])), StandardCharsets.UTF_8);
        byte[] dex = readAll(new File(args[1]));
        String pubB64 = new String(readAll(new File(pubFile)), StandardCharsets.UTF_8).trim();

        // 与 UpdateManager.canonical 一致
        String canonical = field(json, "format") + "|" + intField(json, "versionCode")
                + "|" + field(json, "versionName") + "|" + intField(json, "minBaseVersion")
                + "|" + field(json, "entry") + "|" + field(json, "dexMd5");

        String sigB64 = field(json, "signature");
        if (sigB64.isEmpty()) {
            System.out.println("FAIL: 无 signature 字段");
            System.exit(1);
        }
        byte[] sigBytes = Base64.getDecoder().decode(sigB64);
        byte[] keyBytes = Base64.getDecoder().decode(pubB64);
        PublicKey pub = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(keyBytes));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(pub);
        sig.update(canonical.getBytes(StandardCharsets.UTF_8));
        boolean ok = sig.verify(sigBytes);
        System.out.println((ok ? "OK: 签名验证通过" : "FAIL: 签名验证失败（包被篡改或来源不可信）")
                + "\n  canonical: " + canonical);
        System.exit(ok ? 0 : 1);
    }

    private static String field(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static int intField(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        try {
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}