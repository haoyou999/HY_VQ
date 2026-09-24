package com.aliya.hy_vq.terracotta.punch;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * P2P 数据通道端到端加密（AES-256-GCM，Android 自带 JCE，零依赖）。
 *
 * <p>密钥由房间码经 PBKDF2-HMAC-SHA256 派生（12 万轮迭代 + 固定盐，256 位）：</p>
 * <ul>
 *   <li>可防公共 MQTT 中继/公共网络上的被动嗅探；</li>
 *   <li>PBKDF2 慢哈希将暴力破解成本从毫秒级提升到秒级/分钟级
 *       （房间码仅 6 位数字，快速哈希可被瞬间枚举，慢 KDF 是必备防线）；</li>
 *   <li>局限：信令 Topic 含房间码，知道房间码者可派生密钥（防君子不防熟人）。</li>
 * </ul>
 *
 * <p>格式：Base64( 12 字节随机 IV + 密文(含 GCM 认证标签) )。每次加密使用
 * 新的随机 IV（SecureRandom），杜绝 GCM 密钥/IV 复用灾难。</p>
 *
 * <p>⚠ 兼容性：密钥派生算法变更后，新旧版本数据不互通（两端需同步升级）。</p>
 */
public final class P2pCrypto {

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    /** 密文(含 IV)最大字节数：防恶意超大密文导致解密时 OOM */
    private static final int MAX_RAW = 65536;
    /** PBKDF2 迭代次数：OWASP 推荐 60 万+（SHA-256），移动端在 12 万轮取得安全/性能平衡 */
    private static final int KDF_ITERATIONS = 120000;
    private static final int KEY_BITS = 256;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public P2pCrypto(String roomCode) {
        try {
            // PBKDF2WithHmacSHA256 要求 API 26+（项目 minSdk 28 ✓）
            javax.crypto.SecretKeyFactory f =
                    javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                    roomCode.toCharArray(),
                    "hyvq-p2p-v1|".getBytes(StandardCharsets.UTF_8),
                    KDF_ITERATIONS, KEY_BITS);
            key = new SecretKeySpec(f.generateSecret(spec).getEncoded(), "AES");
        } catch (Exception e) {
            throw new RuntimeException("AES 密钥派生失败", e);
        }
    }

    /** 加密明文 → Base64(IV + 密文+Tag)；失败返回 null */
    public synchronized String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解密 Base64(IV + 密文+Tag) → 明文；失败返回 null */
    public synchronized String decrypt(String data) {
        try {
            if (data == null || data.length() > MAX_RAW * 2) return null; // Base64 约膨胀 4/3，先粗筛
            byte[] raw = Base64.decode(data, Base64.NO_WRAP);
            if (raw.length <= IV_LEN || raw.length > MAX_RAW) return null; // 长度校验防 OOM
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(raw, 0, iv, 0, IV_LEN);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(raw, IV_LEN, raw.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}