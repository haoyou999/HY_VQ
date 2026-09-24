package com.aliya.hy_vq;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.UUID;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 统一身份管理器 — 所有身份数据（uid / uuid / username / password）的唯一入口。
 * <p>
 * 登录时由 LoginActivity 调用 initializeIdentity() 完成初始化；
 * 后续 MainActivity 通过 getPasswordHash() 获取认证密码，
 * 通过 getUid() / getUsername() 获取公开身份。
 */
public class SignatureManager {
    private static final String PREF_NAME = "app_settings";
    private static final String KEY_SIGNATURE = "user_signature";
    private static final String KEY_PASSWORD = "login_password";
    private static final String KEY_SALT = "password_salt";
    /** PBKDF2 迭代次数（与 P2pCrypto 一致，OWASP 移动端推荐平衡值） */
    private static final int PBKDF2_ITERATIONS = 120000;
    private static final int PBKDF2_KEY_BITS = 256;
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    /** 自描述哈希格式前缀：pbkdf2$iterations$saltHex$hashHex（便于未来升级迭代次数） */
    private static final String HASH_PREFIX = "pbkdf2$";
    /** 旧版单轮 SHA-256（仅用于兼容旧数据，登录后会自动迁移到 PBKDF2） */
    private static final String LEGACY_ALGORITHM = "SHA-256";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SharedPreferences prefs;
    private String signatureJson;
    private String userId;  // uuid

    public SignatureManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        loadOrGenerate();
    }

    /** 从已有存储加载，无数据时为空状态（等待 initializeIdentity） */
    private void loadOrGenerate() {
        signatureJson = prefs.getString(KEY_SIGNATURE, "");
        if (signatureJson.isEmpty()) {
            userId = "";
        } else {
            try {
                userId = new JSONObject(signatureJson).getString("uuid");
            } catch (JSONException e) {
                userId = UUID.randomUUID().toString();
            }
        }
    }

    // ═══════════════════════════════════════════════
    //  初始化（仅登录时调用一次）
    // ═══════════════════════════════════════════════

    /**
     * 生成 uid + uuid，哈希密码，统一存入 SharedPreferences。
     *
     * @param username    显示名（为空则用默认名）
     * @param rawPassword 明文密码（可为空，表示无密码登录）
     */
    public void initializeIdentity(String username, String rawPassword) {
        String displayName = username != null && !username.isEmpty() ? username : "HY_VQ用户";
        try {
            JSONObject sigObj;
            if (!signatureJson.isEmpty()) {
                // 已有身份（退出登录后重新登录）：保留 uid/uuid，仅更新显示名
                sigObj = new JSONObject(signatureJson);
                sigObj.put("username", displayName);
            } else {
                // 首次登录：生成全新身份
                String uid = String.format("%09d", SECURE_RANDOM.nextInt(1_000_000_000));
                String uuid = UUID.randomUUID().toString();
                sigObj = new JSONObject();
                sigObj.put("username", displayName);
                sigObj.put("uid", uid);
                sigObj.put("uuid", uuid);
                userId = uuid;
            }
            signatureJson = sigObj.toString();
            // 身份数据为关键数据：用 commit() 同步写盘，避免 apply() 异步落盘时进程被杀丢数据
            prefs.edit()
                    .putString(KEY_SIGNATURE, signatureJson)
                    .putBoolean("has_logged_in", true)
                    .commit();
        } catch (JSONException e) {
            e.printStackTrace();
        }
        // 密码处理
        if (rawPassword != null && !rawPassword.isEmpty()) {
            prefs.edit().putString(KEY_PASSWORD, hashPassword(rawPassword)).apply();
        } else {
            prefs.edit().remove(KEY_PASSWORD).apply();
        }
        // 冗余存储用户名，兼容已有读路径
        prefs.edit().putString("username", getUsername()).apply();
    }

    // ═══════════════════════════════════════════════
    //  密码
    // ═══════════════════════════════════════════════

    /** 获取已存储的密码哈希（PBKDF2 自描述格式）；无密码时返回 "" */
    public String getPasswordHash() {
        return prefs.getString(KEY_PASSWORD, "");
    }

    /** 是否设置了密码 */
    public boolean hasPassword() {
        return !getPasswordHash().isEmpty();
    }

    /** 校验明文密码：兼容 PBKDF2 新格式与旧 SHA-256 格式，恒定时间比较防时序攻击 */
    public boolean verifyPassword(String rawPassword) {
        String stored = getPasswordHash();
        if (stored.isEmpty()) return true; // 无密码 = 永远通过

        if (stored.startsWith(HASH_PREFIX)) {
            // 新格式 pbkdf2$iter$salt$hash
            try {
                String[] parts = stored.split("\\$");
                if (parts.length != 4) return false;
                int iter = Integer.parseInt(parts[1]);
                String salt = parts[2];
                byte[] expected = hexToBytes(parts[3]);
                byte[] actual = pbkdf2(rawPassword, salt, iter);
                return MessageDigest.isEqual(expected, actual);
            } catch (Exception e) {
                return false;
            }
        }
        // 旧格式：单轮 SHA-256（老数据兼容；登录时 initializeIdentity 会重写为新格式）
        String legacy = legacySha256(rawPassword);
        return legacy.equals(stored);
    }

    /** PBKDF2 慢哈希（12 万轮迭代 + 设备级 salt），返回自描述格式 pbkdf2$iter$salt$hash */
    private String hashPassword(String password) {
        try {
            String salt = prefs.getString(KEY_SALT, "");
            if (salt.isEmpty()) {
                byte[] saltBytes = new byte[16];
                new SecureRandom().nextBytes(saltBytes);
                salt = bytesToHex(saltBytes);
                prefs.edit().putString(KEY_SALT, salt).apply();
            }
            byte[] hash = pbkdf2(password, salt, PBKDF2_ITERATIONS);
            return HASH_PREFIX + PBKDF2_ITERATIONS + "$" + salt + "$" + bytesToHex(hash);
        } catch (Exception e) {
            // 极端情况（算法不可用）：回退旧 SHA-256，绝不用 hashCode()
            return legacySha256(password);
        }
    }

    /** PBKDF2WithHmacSHA256 核心计算 */
    private static byte[] pbkdf2(String password, String saltHex, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(
                password.toCharArray(), hexToBytes(saltHex), iterations, PBKDF2_KEY_BITS);
        SecretKeyFactory f = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
        return f.generateSecret(spec).getEncoded();
    }

    /** 旧版单轮 SHA-256（兼容已存储的旧哈希；新哈希一律走 PBKDF2） */
    private String legacySha256(String password) {
        try {
            String salt = prefs.getString(KEY_SALT, "default");
            MessageDigest digest = MessageDigest.getInstance(LEGACY_ALGORITHM);
            digest.update(salt.getBytes());
            return bytesToHex(digest.digest(password.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ═══════════════════════════════════════════════
    //  公开身份字段
    // ═══════════════════════════════════════════════

    /** 9 位数字 uid */
    public String getUid() {
        try {
            return new JSONObject(signatureJson).getString("uid");
        } catch (JSONException e) {
            return "";
        }
    }

    /** UUID（设备唯一标识，对用户透明） */
    public String getUuid() {
        return userId;
    }

    /** 显示用户名 */
    public String getUsername() {
        try {
            return new JSONObject(signatureJson).getString("username");
        } catch (JSONException e) {
            return prefs.getString("username", "HY_VQ用户");
        }
    }

    /** 用于更新用户名（设置页编辑后调用） */
    public void updateUsername(String newUsername) {
        if (!signatureJson.isEmpty()) {
            try {
                JSONObject sigObj = new JSONObject(signatureJson);
                sigObj.put("username", newUsername);
                signatureJson = sigObj.toString();
                prefs.edit().putString(KEY_SIGNATURE, signatureJson).apply();
                prefs.edit().putString("username", newUsername).apply();
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    // ═══════════════════════════════════════════════
    //  序列化输出
    // ═══════════════════════════════════════════════

    /** 不含密码的签名字符串（日常消息使用） */
    public String getSignatureJson() {
        return signatureJson;
    }

    /** 含密码哈希的签名（仅用于服务器认证） */
    public String getAuthSignature() {
        try {
            JSONObject sigObj = new JSONObject(signatureJson);
            sigObj.put("password", getPasswordHash());
            return sigObj.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    // ═══════════════════════════════════════════════
    //  注销
    // ═══════════════════════════════════════════════

    /**
     * 退出登录：仅清除会话标记，保留身份数据（uid/uuid/用户名/密码哈希）。
     * <p>
     * 重新登录时走密码校验（未设密码则直接通过），
     * {@link #initializeIdentity} 会保留原 uid/uuid，仅更新显示名。
     */
    public void clearSession() {
        prefs.edit().remove("has_logged_in").apply();
    }

    /**
     * 清除所有身份数据（仅用于彻底换号/重置场景）。
     * <p>
     * 注意：uid/uuid 不可恢复，调用后下次登录将生成全新身份，慎用！
     */
    public void clear() {
        prefs.edit()
                .remove(KEY_SIGNATURE)
                .remove(KEY_PASSWORD)
                .remove("has_logged_in")
                .remove("username")
                .apply();
        signatureJson = "";
        userId = "";
    }
}