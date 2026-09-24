package com.aliya.hy_vq.service;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 简易 WebSocket 客户端（纯 Java 实现，零依赖）
 * 用于连接到 ChatServer，发送/接收 JSON 消息
 *
 * 用法：
 * <pre>
 * HyVqWebSocketClient client = new HyVqWebSocketClient("192.168.1.100", 3000);
 * client.setListener(new HyVqWebSocketClient.Listener() { ... });
 * client.connect();
 * client.send("{\"action\":\"login\",\"uid\":\"alice\",\"password\":\"123\"}");
 * </pre>
 */
public class HyVqWebSocketClient {

    private static final String TAG = "HyVqWSClient";

    /** 最大接受帧大小（RFC 6455 允许实现设置上限，防恶意超大帧导致 OOM/DoS） */
    private static final long MAX_FRAME_SIZE = 4L * 1024 * 1024; // 4MB

    private final String host;
    private final int port;
    private Socket socket;
    private OutputStream out;
    private InputStream in;
    private volatile boolean connected = false;

    private final ExecutorService readExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private Listener listener;

    public interface Listener {
        void onConnected();
        void onMessage(JSONObject message);
        void onDisconnected(String reason);
        void onError(String error);
    }

    public HyVqWebSocketClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /**
     * 建立 WebSocket 连接（阻塞直到握手完成，约 2-3 秒）
     * 建议在子线程调用
     */
    public boolean connect() {
        try {
            socket = new Socket(host, port);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(0); // 无超时，长连接

            out = socket.getOutputStream();
            in = socket.getInputStream();

            // ── 1. 发送 HTTP 升级请求 ──
            String wsKey = generateWsKey();
            StringBuilder req = new StringBuilder();
            req.append("GET / HTTP/1.1\r\n");
            req.append("Host: ").append(host).append(":").append(port).append("\r\n");
            req.append("Upgrade: websocket\r\n");
            req.append("Connection: Upgrade\r\n");
            req.append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n");
            req.append("Sec-WebSocket-Version: 13\r\n");
            req.append("\r\n");

            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            // ── 2. 读取握手响应 ──
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            String line = reader.readLine();
            if (line == null || !line.contains("101")) {
                Log.e(TAG, "握手失败: " + line);
                notifyError("握手失败: " + line);
                socket.close();
                return false;
            }
            // 跳过响应头
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                /* noop */
            }

            connected = true;
            notifyConnected();
            Log.i(TAG, "✅ 已连接到 ws://" + host + ":" + port);

            // ── 3. 启动读取线程 ──
            readExecutor.execute(this::readLoop);

            return true;

        } catch (IOException e) {
            Log.e(TAG, "连接失败: " + e.getMessage());
            notifyError("连接失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 发送 JSON 对象（自动编码为 WebSocket 帧并加掩码）
     */
    public boolean send(JSONObject json) {
        return send(json.toString());
    }

    /**
     * 发送 JSON 字符串
     */
    public boolean send(String text) {
        if (!connected || out == null) return false;
        try {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            sendFrame(0x01, data);  // TEXT 帧
            return true;
        } catch (IOException e) {
            handleDisconnect("发送失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 发送原始 JSON 帧（对外公开，方便快速调用）
     */
    public boolean sendJson(String jsonString) {
        return send(jsonString);
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        connected = false;
        try {
            if (out != null) {
                sendFrame(0x08, new byte[]{(byte) 0x03, (byte) 0xE8}); // CLOSE frame
            }
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
        readExecutor.shutdownNow();
        notifyDisconnected("主动断开");
    }

    public boolean isConnected() {
        return connected;
    }

    // ═══════════════════════════════════════════════
    //              读取循环
    // ═══════════════════════════════════════════════

    private void readLoop() {
        try {
            while (connected && socket != null && !socket.isClosed()) {
                int b0 = in.read();
                if (b0 < 0) break;

                int opcode = b0 & 0x0F;
                int b1 = in.read();
                if (b1 < 0) break;
                boolean masked = (b1 & 0x80) != 0;
                long payloadLen = b1 & 0x7F;

                if (payloadLen == 126) {
                    payloadLen = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
                } else if (payloadLen == 127) {
                    payloadLen = 0;
                    for (int i = 0; i < 8; i++) {
                        payloadLen = (payloadLen << 8) | (in.read() & 0xFF);
                    }
                }

                // 跳过掩码（服务器到客户端的帧通常无掩码）
                byte[] mask = null;
                if (masked) {
                    mask = new byte[4];
                    readFully(in, mask, 0, 4);
                }

                // 防 DoS：拒绝超过上限的帧（含 64 位长度溢出为负数的场景）
                if (payloadLen < 0 || payloadLen > MAX_FRAME_SIZE) {
                    Log.w(TAG, "帧长度超限: " + payloadLen + "，断开连接");
                    handleDisconnect("帧长度超限");
                    break;
                }

                // 读取 payload
                byte[] payload = new byte[(int) payloadLen];
                readFully(in, payload, 0, (int) payloadLen);

                if (masked && mask != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i % 4];
                    }
                }

                if (opcode == 0x08) {
                    // CLOSE 帧
                    int code = 1005;
                    if (payload.length >= 2) {
                        code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                    }
                    handleDisconnect("服务器关闭: code=" + code);
                    break;
                } else if (opcode == 0x09) {
                    // PING → 回复 PONG
                    sendPong(payload);
                } else if (opcode == 0x01) {
                    // TEXT 帧
                    String text = new String(payload, StandardCharsets.UTF_8);
                    try {
                        JSONObject msg = new JSONObject(text);
                        notifyMessage(msg);
                    } catch (Exception e) {
                        Log.w(TAG, "消息解析失败: " + text);
                    }
                }
                // 忽略其他帧类型（BINARY, PONG 等）
            }
        } catch (IOException e) {
            if (connected) {
                handleDisconnect("连接断开: " + e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════════════
    //              WebSocket 帧发送
    // ═══════════════════════════════════════════════

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        synchronized (out) {
            out.write(0x80 | opcode);  // FIN + opcode
            byte[] mask = generateMask();
            int len = payload.length;
            // 客户端→服务器帧必须掩码（RFC 6455 §5.3）；长度字段按 7/16/64 位编码
            if (len < 126) {
                out.write(0x80 | len);            // MASKED + 7-bit length
            } else if (len < 65536) {
                out.write(0x80 | 126);            // MASKED + 16-bit length
                out.write((len >> 8) & 0xFF);
                out.write(len & 0xFF);
            } else {
                out.write(0x80 | 127);            // MASKED + 64-bit length
                for (int i = 7; i >= 0; i--) {
                    out.write((int) ((long) len >> (i * 8)) & 0xFF);
                }
            }
            out.write(mask);
            for (int i = 0; i < payload.length; i++) {
                out.write(payload[i] ^ mask[i % 4]);
            }
            out.flush();
        }
    }

    private void sendPong(byte[] data) throws IOException {
        sendFrame(0x0A, data);
    }

    // ═══════════════════════════════════════════════
    //              工具方法
    // ═══════════════════════════════════════════════

    private String generateWsKey() {
        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private byte[] generateMask() {
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        return mask;
    }

    private void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, off + total, len - total);
            if (n < 0) throw new IOException("EOF");
            total += n;
        }
    }

    private void handleDisconnect(String reason) {
        connected = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        notifyDisconnected(reason);
    }

    // ═══════════════════════════════════════════════
    //              UI 线程回调
    // ═══════════════════════════════════════════════

    private void notifyConnected() {
        if (listener != null) {
            uiHandler.post(() -> listener.onConnected());
        }
    }

    private void notifyMessage(JSONObject msg) {
        if (listener != null) {
            uiHandler.post(() -> listener.onMessage(msg));
        }
    }

    private void notifyDisconnected(String reason) {
        if (listener != null) {
            uiHandler.post(() -> listener.onDisconnected(reason));
        }
    }

    private void notifyError(String error) {
        if (listener != null) {
            uiHandler.post(() -> listener.onError(error));
        }
    }
}
