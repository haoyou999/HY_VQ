import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HY_VQ 更新包签名工具（JDK 独立运行，零第三方依赖）。
 *
 * <p>签名内容 = canonical 字符串（与壳 UpdateManager.verifySignature 完全一致）：
 * <pre>
 *   format + "|" + versionCode + "|" + versionName + "|" + minBaseVersion + "|" + entry + "|" + dexMd5
 * </pre>
 * dexMd5 覆盖 classes.dex 全文 → 签名链式覆盖整个更新包：任何字段或 dex 被篡改 → 验签失败。
 *
 * <p>用法：
 * <pre>
 *   java SignTool <jks> <storepass> <alias> <manifest.json> <classes.dex>
 * </pre>
 * 直接原地修改 manifest.json，追加 "signature" 与 "sigAlg" 字段。
 */
public class SignTool {

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("用法: java SignTool <jks> <storepass> <alias> <manifest.json> <classes.dex>");
            System.exit(2);
        }
        String jks = args[0], pass = args[1], alias = args[2];
        File manifestFile = new File(args[3]);
        File dexFile = new File(args[4]);

        // 1) 读 manifest 字段（自有格式，字段可控；正则提取）
        String json = new String(readAll(manifestFile), StandardCharsets.UTF_8);
        String format = field(json, "format");
        int versionCode = intField(json, "versionCode", 0);
        String versionName = field(json, "versionName");
        int minBaseVersion = intField(json, "minBaseVersion", 0);
        String entry = field(json, "entry");
        String dexMd5 = field(json, "dexMd5");

        // 2) 校验 dexMd5 与 dex 实际内容一致（防签名工具被喂假数据）
        String realMd5 = md5Hex(readAll(dexFile));
        if (!dexMd5.isEmpty() && !dexMd5.equalsIgnoreCase(realMd5)) {
            System.err.println("错误: manifest.dexMd5(" + dexMd5 + ") 与 classes.dex 实际 MD5(" + realMd5 + ") 不一致");
            System.exit(1);
        }

        // 3) 拼 canonical 并签名
        String canonical = format + "|" + versionCode + "|" + versionName + "|" + minBaseVersion + "|" + entry + "|" + dexMd5;
        KeyStore ks = KeyStore.getInstance("JKS");
        try (FileInputStream in = new FileInputStream(jks)) {
            ks.load(in, pass.toCharArray());
        }
        PrivateKey pk = (PrivateKey) ks.getKey(alias, pass.toCharArray());
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(pk);
        sig.update(canonical.getBytes(StandardCharsets.UTF_8));
        String b64 = Base64.getEncoder().encodeToString(sig.sign());

        // 4) 重建 manifest（固定字段顺序 + signature + sigAlg），保持 JSON 可读
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"format\": \"").append(escape(format)).append("\",\n");
        sb.append("  \"versionCode\": ").append(versionCode).append(",\n");
        sb.append("  \"versionName\": \"").append(escape(versionName)).append("\",\n");
        sb.append("  \"minBaseVersion\": ").append(minBaseVersion).append(",\n");
        sb.append("  \"entry\": \"").append(escape(entry)).append("\",\n");
        sb.append("  \"dexMd5\": \"").append(escape(dexMd5)).append("\",\n");
        sb.append("  \"signature\": \"").append(b64).append("\",\n");
        sb.append("  \"sigAlg\": \"SHA256withRSA\"\n");
        sb.append("}\n");
        try (FileOutputStream out = new FileOutputStream(manifestFile)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        System.out.println("签名完成: " + canonical);
        System.out.println("signature: " + b64.substring(0, 40) + "... (" + b64.length() + " chars)");
    }

    private static String field(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static int intField(String json, String key, int def) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        try {
            return m.find() ? Integer.parseInt(m.group(1)) : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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

    private static String md5Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest(data)) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}