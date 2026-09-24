package com.aliya.hy_vq;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 凭据加密存储：Android Keystore 生成 AES-256-GCM 密钥加密后落盘，
 * 不把账号/密码明文写进 APK 或 prefs。首次调用生成并加密;可随时更新。
 */
public final class CredentialStore {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "hyvq_webdav_cred";
    private static final String PREFS = "encrypted_creds";
    private static final String PREF_ENC = "webdav_enc";   // base64(iv|cipher)
    private static final int IV_LEN = 12;                  // GCM 推荐 12 字节

    private CredentialStore() {}

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        SecretKey key = (SecretKey) ks.getKey(KEY_ALIAS, null);
        if (key != null) return key;
        KeyGenerator kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return kg.generateKey();
    }

    /** 保存凭据(明文 → Keystore 加密 → prefs)。 */
    public static void save(Context ctx, String plain) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] cipherBytes = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] iv = c.getIV();
            byte[] merged = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, merged, 0, iv.length);
            System.arraycopy(cipherBytes, 0, merged, iv.length, cipherBytes.length);
            prefs(ctx).edit().putString(PREF_ENC, Base64.encodeToString(merged, Base64.NO_WRAP)).apply();
        } catch (Exception ignored) {}
    }

    /** 读取凭据(解密);未设置返回 null。 */
    public static String load(Context ctx) {
        try {
            String b64 = prefs(ctx).getString(PREF_ENC, "");
            if (b64.isEmpty()) return null;
            byte[] merged = Base64.decode(b64, Base64.NO_WRAP);
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(merged, 0, iv, 0, IV_LEN);
            byte[] cipherBytes = new byte[merged.length - IV_LEN];
            System.arraycopy(merged, IV_LEN, cipherBytes, 0, cipherBytes.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(cipherBytes), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
