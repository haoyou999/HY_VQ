package com.aliya.hy_vq.service;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 极简内嵌 WebSocket 服务器（零依赖，纯 Java 实现 RFC 6455）
 * 集成了用户账号管理、签名存储、消息路由三大核心功能
 *
 * 用法（在 MainActivity 中启动）：
 * <pre>
 * ChatServer server = new ChatServer(3000);
 * server.start();
 * </pre>
 */
public class ChatServer {

    private static final String TAG = "HyVqServer";

    /** 最大接受帧大小（防恶意超大帧 OOM/DoS，RFC 6455 允许实现设上限） */
    private static final long MAX_FRAME_SIZE = 4L * 1024 * 1024; // 4MB

    // ── 配置 ──
    private final int port;
    private volatile boolean running = false;
    private Thread acceptThread;
    private ServerSocket serverSocket;

    // ── 线程池 ──
    /** 有界连接线程池：无界 CachedThreadPool 在长连接场景可无限增长（线程泄漏/资源耗尽） */
    private final ExecutorService clientPool = new java.util.concurrent.ThreadPoolExecutor(
            1, 16, 60L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<>());

    // ── 在线客户端管理 ──
    /**
     * uid -> WebSocket 连接封装，ConcurrentHashMap 保证线程安全
     */
    private final ConcurrentHashMap<String, ClientConnection> onlineClients = new ConcurrentHashMap<>();

    // ── 账号存储 ──
    /**
     * uid -> 账号对象（密码哈希、签名 JSON）
     * 内存存储 + 定期持久化到 SharedPreferences
     */
    private final ConcurrentHashMap<String, AccountRecord> accounts = new ConcurrentHashMap<>();

    // ── 外部回调 ──
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Callback callback;

    // ── 消息计数器 ──
    private long msgIdCounter = 0;

    // ═══════════════════════════════════════════════
    //              构造与启停
    // ═══════════════════════════════════════════════

    public ChatServer(int port) {
        this.port = port;
    }

    /**
     * 设置回调，用于向 UI 层传递事件
     */
    public void setCallback(Callback cb) {
        this.callback = cb;
    }

    /**
     * 启动服务器（非阻塞，在子线程中 accept）
     */
    public boolean start() {
        if (running) return true;
        try {
            serverSocket = new ServerSocket(port);
            running = true;
            acceptThread = new Thread(this::acceptLoop, "HyVqServer-Accept");
            acceptThread.start();
            notifyCallback("started", "端口 " + port + " 已监听");
            Log.i(TAG, "✅ 服务器启动成功 -> 0.0.0.0:" + port);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "❌ 端口 " + port + " 已被占用: " + e.getMessage());
            notifyCallback("error", "端口占用");
            return false;
        }
    }

    /**
     * 停止服务器，释放所有资源
     */
    public void stop() {
        running = false;
        // 1. 关闭 ServerSocket
        try {
            if (serverSocket != null && !serverSocket.isClosed())
                serverSocket.close();
        } catch (IOException ignored) {}
        // 2. 关闭所有客户端连接
        for (ClientConnection c : onlineClients.values()) {
            closeSilently(c);
        }
        onlineClients.clear();
        // 3. 关闭线程池
        clientPool.shutdown();
        try { clientPool.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        notifyCallback("stopped", "服务器已关闭");
        Log.i(TAG, "🛑 服务器已停止");
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    /**
     * 获取当前在线 UID 列表
     */
    public Set<String> getOnlineUids() {
        return Collections.unmodifiableSet(onlineClients.keySet());
    }

    /**
     * 获取账号列表（用于管理面板）
     */
    public List<AccountRecord> getAccounts() {
        return new ArrayList<>(accounts.values());
    }

    /**
     * 从 SharedPreferences 加载已有账号
     */
    public void loadAccountsFromMap(Map<String, String> data) {
        for (Map.Entry<String, String> entry : data.entrySet()) {
            try {
                JSONObject obj = new JSONObject(entry.getValue());
                AccountRecord rec = new AccountRecord(
                        entry.getKey(),
                        obj.optString("hash", ""),
                        obj.optString("salt", ""),
                        obj.optString("signature", "{}"),
                        obj.optLong("createdAt", System.currentTimeMillis())
                );
                accounts.put(entry.getKey(), rec);
            } catch (Exception ignored) {}
        }
    }

    /**
     * 导出账号到 Map，供主进程持久化
     */
    public Map<String, String> dumpAccounts() {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, AccountRecord> e : accounts.entrySet()) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("hash", e.getValue().hash);
                obj.put("salt", e.getValue().salt);
                obj.put("signature", e.getValue().signatureJson);
                obj.put("createdAt", e.getValue().createdAt);
                out.put(e.getKey(), obj.toString());
            } catch (Exception ignored) {}
        }
        return out;
    }

    // ═══════════════════════════════════════════════
    //              Accept 循环
    // ═══════════════════════════════════════════════

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                // 设置读取超时（心跳探测用）
                socket.setSoTimeout(120_000);
                socket.setTcpNoDelay(true);
                clientPool.execute(() -> handleClient(socket));
            } catch (SocketException e) {
                // 正常关闭时退出
                if (!running) break;
            } catch (IOException e) {
                Log.w(TAG, "accept 异常: " + e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════════════
    //            WebSocket 握手 + 协议处理
    // ═══════════════════════════════════════════════

    private void handleClient(Socket socket) {
        try {
            // ── 1. HTTP 升级握手 ──
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();

            String line = reader.readLine();
            if (line == null || !line.startsWith("GET")) {
                socket.close();
                return;
            }

            Map<String, String> headers = new HashMap<>();
            String key;
            while ((key = reader.readLine()) != null && !key.isEmpty()) {
                int idx = key.indexOf(": ");
                if (idx > 0) {
                    headers.put(key.substring(0, idx).toLowerCase(),
                            key.substring(idx + 2));
                }
            }

            // ── 2. WebSocket 握手响应 ──
            String wsKey = headers.get("sec-websocket-key");
            if (wsKey == null) {
                socket.close();
                return;
            }
            String acceptKey = generateAcceptKey(wsKey);

            StringBuilder resp = new StringBuilder();
            resp.append("HTTP/1.1 101 Switching Protocols\r\n");
            resp.append("Upgrade: websocket\r\n");
            resp.append("Connection: Upgrade\r\n");
            resp.append("Sec-WebSocket-Accept: ").append(acceptKey).append("\r\n");
            resp.append("\r\n");
            out.write(resp.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            // ── 3. 等待认证帧 ──
            String uid = null;
            // 先收一个帧，必须为 LOGIN 或 REGISTER
            WsFrame initFrame = readFrame(socket, reader);
            if (initFrame == null || initFrame.type != WsFrame.TEXT) {
                sendClose(out, 1008, "需要认证");
                socket.close();
                return;
            }

            JSONObject initMsg = new JSONObject(initFrame.payload);
            String action = initMsg.optString("action", "");

            if ("register".equals(action)) {
                uid = handleRegister(initMsg);
                if (uid == null) {
                    sendJsonFrame(out, errorJson("register_failed", "注册失败，用户名可能已存在"));
                    sendClose(out, 1000, "注册失败");
                    socket.close();
                    return;
                }
            } else if ("login".equals(action)) {
                uid = handleLogin(initMsg);
                if (uid == null) {
                    sendJsonFrame(out, errorJson("login_failed", "密码错误或用户不存在"));
                    sendClose(out, 1000, "登录失败");
                    socket.close();
                    return;
                }
            } else {
                sendJsonFrame(out, errorJson("unauthorized", "请先登录(login)或注册(register)"));
                sendClose(out, 1000, "未认证");
                socket.close();
                return;
            }

            // ── 4. 注册到在线列表 ──
            // 如果同一 uid 已有旧连接，先关闭旧连接
            ClientConnection old = onlineClients.get(uid);
            if (old != null) {
                closeSilently(old);
                onlineClients.remove(uid);
            }

            ClientConnection client = new ClientConnection(uid, socket, out, reader);
            onlineClients.put(uid, client);

            // ── 5. 发送认证成功 ──
            JSONObject loginOk = new JSONObject();
            loginOk.put("action", "login_ok");
            loginOk.put("uid", uid);
            loginOk.put("message", "认证成功");
            sendJsonFrame(out, loginOk);

            // ── 6. 向所有在线用户广播上线消息 ──
            broadcastSystemMsg(uid + " 上线了");

            notifyCallback("client_connected", uid);
            Log.i(TAG, "🔵 客户端已连接: " + uid);

            // ── 7. 消息循环 ──
            while (running) {
                WsFrame frame = readFrame(socket, reader);
                if (frame == null) break;   // 连接断开

                if (frame.type == WsFrame.TEXT) {
                    try {
                        JSONObject msg = new JSONObject(frame.payload);
                        handleMessage(uid, msg, client);
                    } catch (Exception e) {
                        Log.w(TAG, "消息解析失败: " + e.getMessage());
                    }
                } else if (frame.type == WsFrame.CLOSE) {
                    break;
                }
                // PING → PONG
                else if (frame.type == WsFrame.PING) {
                    sendPong(out, frame.payload);
                }
            }

        } catch (Exception e) {
            Log.w(TAG, "客户端处理异常: " + e.getMessage());
        } finally {
            closeSilently(socket);
        }

        // 清理
        for (Map.Entry<String, ClientConnection> entry : onlineClients.entrySet()) {
            if (entry.getValue().socket == socket) {
                String uid = entry.getKey();
                onlineClients.remove(uid);
                broadcastSystemMsg(uid + " 下线了");
                notifyCallback("client_disconnected", uid);
                Log.i(TAG, "🔴 客户端断开: " + uid);
                break;
            }
        }
    }

    // ═══════════════════════════════════════════════
    //              消息路由
    // ═══════════════════════════════════════════════

    private void handleMessage(String fromUid, JSONObject msg, ClientConnection sender) {
        try {
            String action = msg.optString("action", "");
            switch (action) {
            case "get_signature": {
                // 查询签名：可以查自己的也可以查别人的
                String targetUid = msg.optString("uid", fromUid);
                AccountRecord rec = accounts.get(targetUid);
                JSONObject resp = new JSONObject();
                resp.put("action", "signature");
                resp.put("uid", targetUid);
                resp.put("signature", rec != null ? rec.signatureJson : "{}");
                try { sender.sendJson(resp); } catch (Exception ignored) {}
                break;
            }

            case "update_signature": {
                String newSig = msg.optString("signature", "{}");
                updateSignature(fromUid, newSig);
                JSONObject resp = new JSONObject();
                resp.put("action", "signature_updated");
                resp.put("status", "ok");
                try { sender.sendJson(resp); } catch (Exception ignored) {}
                notifyCallback("signature_changed", fromUid);
                break;
            }

            case "chat": {
                // 聊天消息转发
                String to = msg.optString("to", "");
                String text = msg.optString("text", "");
                String msgId = msg.optString("msgId", "m_" + (++msgIdCounter));

                JSONObject forward = new JSONObject();
                forward.put("action", "chat");
                forward.put("from", fromUid);
                forward.put("to", to);
                forward.put("text", text);
                forward.put("msgId", msgId);
                forward.put("timestamp", System.currentTimeMillis());

                if ("broadcast".equals(to) || to.isEmpty()) {
                    // 群发
                    broadcast(forward);
                } else {
                    // 单发
                    ClientConnection target = onlineClients.get(to);
                    if (target != null) {
                        try { target.sendJson(forward); } catch (Exception ignored) {}
                    } else {
                        // 目标不在线，返回错误
                        JSONObject err = new JSONObject();
                        err.put("action", "chat_error");
                        err.put("msgId", msgId);
                        err.put("error", "用户不在线");
                        try { sender.sendJson(err); } catch (Exception ignored) {}
                    }
                }
                notifyCallback("message", fromUid + " → " + to);
                break;
            }

            case "online_list": {
                JSONObject resp = new JSONObject();
                resp.put("action", "online_list");
                JSONArray arr = new JSONArray();
                for (String u : onlineClients.keySet()) {
                    arr.put(u);
                }
                resp.put("users", arr);
                try { sender.sendJson(resp); } catch (Exception ignored) {}
                break;
            }

            case "ping": {
                JSONObject pong = new JSONObject();
                pong.put("action", "pong");
                try { sender.sendJson(pong); } catch (Exception ignored) {}
                break;
            }

            default:
                Log.d(TAG, "未知 action: " + action);
        }
        } catch (JSONException e) {
            Log.w(TAG, "消息处理异常 (from=" + fromUid + "): " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════
    //              账号管理
    // ═══════════════════════════════════════════════

    private String handleRegister(JSONObject msg) {
        String uid = msg.optString("uid", "");
        String password = msg.optString("password", "");

        if (uid.isEmpty() || password.isEmpty()) return null;

        // 检查 uid 是否已存在
        if (accounts.containsKey(uid)) return null;

        String salt = generateSalt();
        String hash = pbkdf2(password, salt);

        AccountRecord rec = new AccountRecord(
                uid, hash, salt,
                msg.optString("signature", "{}"),
                System.currentTimeMillis()
        );
        accounts.put(uid, rec);
        notifyCallback("account_created", uid);
        Log.i(TAG, "📝 新账号注册: " + uid);
        return uid;
    }

    private String handleLogin(JSONObject msg) {
        String uid = msg.optString("uid", "");
        String password = msg.optString("password", "");

        if (uid.isEmpty() || password.isEmpty()) return null;

        AccountRecord rec = accounts.get(uid);
        if (rec == null) return null;

        String testHash = pbkdf2(password, rec.salt);
        if (!testHash.equals(rec.hash)) return null;

        return uid;
    }

    /**
     * 更新用户签名 JSON
     */
    public void updateSignature(String uid, String signatureJson) {
        AccountRecord rec = accounts.get(uid);
        if (rec != null) {
            rec.signatureJson = signatureJson;
        } else {
            // 如果账号还不存在，创建一个仅签名的记录（后续登录时会补全）
            AccountRecord newRec = new AccountRecord(
                    uid, "", "", signatureJson, System.currentTimeMillis()
            );
            accounts.put(uid, newRec);
        }
    }

    // ═══════════════════════════════════════════════
    //              广播
    // ═══════════════════════════════════════════════

    private void broadcast(JSONObject msg) {
        for (ClientConnection c : onlineClients.values()) {
            try { c.sendJson(msg); } catch (Exception ignored) {}
        }
    }

    private void broadcastSystemMsg(String text) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("action", "system");
            msg.put("text", text);
            msg.put("timestamp", System.currentTimeMillis());
            broadcast(msg);
        } catch (JSONException ignored) {}
    }

    // ═══════════════════════════════════════════════
    //              WebSocket 帧编解码
    // ═══════════════════════════════════════════════

    private WsFrame readFrame(Socket socket, BufferedReader reader) {
        try {
            // 使用 InputStream 二进制读取
            java.io.InputStream in = socket.getInputStream();

            int b0 = in.read();
            if (b0 < 0) return null;

            // 注：未处理 FIN/分片（控制帧 FIN 恒 1，数据帧本项目均为单帧发送）
            int opcode = b0 & 0x0F;

            int b1 = in.read();
            if (b1 < 0) return null;
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

            // RFC 6455 §5.3：客户端→服务器帧必须掩码，否则协议违规，直接拒绝
            if (!masked) {
                Log.w(TAG, "拒绝未掩码帧（协议违规）");
                return null;
            }

            // 防 DoS：拒绝超过上限的帧（含 64 位长度溢出为负数的场景）
            if (payloadLen < 0 || payloadLen > MAX_FRAME_SIZE) {
                Log.w(TAG, "帧长度超限: " + payloadLen + "，断开连接");
                return null;
            }

            // 读取掩码密钥
            byte[] mask = new byte[4];
            readFully(in, mask, 0, 4);

            // 读取 payload
            byte[] payload = new byte[(int) payloadLen];
            readFully(in, payload, 0, (int) payloadLen);

            // 去掩码（服务器收到的客户端帧必然已掩码）
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i % 4];
            }

            if (opcode == 0x08) {
                return new WsFrame(WsFrame.CLOSE, new String(payload, StandardCharsets.UTF_8));
            } else if (opcode == 0x09) {
                return new WsFrame(WsFrame.PING, new String(payload, StandardCharsets.UTF_8));
            } else {
                return new WsFrame(WsFrame.TEXT, new String(payload, StandardCharsets.UTF_8));
            }

        } catch (IOException e) {
            return null;  // 连接断开
        }
    }

    private void readFully(java.io.InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, off + total, len - total);
            if (n < 0) throw new IOException("EOF");
            total += n;
        }
    }

    private void sendJsonFrame(OutputStream out, JSONObject json) throws IOException {
        sendFrame(out, WsFrame.TEXT, json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void sendFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        synchronized (out) {
            out.write(0x80 | opcode);
            if (payload.length < 126) {
                out.write(payload.length & 0x7F);
            } else if (payload.length < 65536) {
                out.write(126);
                out.write((payload.length >> 8) & 0xFF);
                out.write(payload.length & 0xFF);
            } else {
                out.write(127);
                for (int i = 7; i >= 0; i--) {
                    out.write((int) (payload.length >> (i * 8)) & 0xFF);
                }
            }
            out.write(payload);
            out.flush();
        }
    }

    private void sendClose(OutputStream out, int code, String reason) throws IOException {
        byte[] payload;
        if (reason != null && !reason.isEmpty()) {
            byte[] rBytes = reason.getBytes(StandardCharsets.UTF_8);
            payload = new byte[2 + rBytes.length];
            payload[0] = (byte) ((code >> 8) & 0xFF);
            payload[1] = (byte) (code & 0xFF);
            System.arraycopy(rBytes, 0, payload, 2, rBytes.length);
        } else {
            payload = new byte[]{(byte) ((code >> 8) & 0xFF), (byte) (code & 0xFF)};
        }
        sendFrame(out, WsFrame.CLOSE, payload);
    }

    private void sendPong(OutputStream out, String data) throws IOException {
        sendFrame(out, WsFrame.PONG, data.getBytes(StandardCharsets.UTF_8));
    }

    // ═══════════════════════════════════════════════
    //              工具方法
    // ═══════════════════════════════════════════════

    private String generateAcceptKey(String wsKey) {
        String combined = wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        byte[] hash;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            hash = md.digest(combined.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            hash = combined.getBytes(StandardCharsets.UTF_8);
        }
        return Base64.getEncoder().encodeToString(hash);
    }

    /** PBKDF2-HMAC-SHA256 慢哈希（12 万轮迭代，与客户端 SignatureManager 一致），返回 hex */
    private String pbkdf2(String password, String saltHex) {
        try {
            javax.crypto.SecretKeyFactory f =
                    javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                    password.toCharArray(), hexToBytes(saltHex), 120000, 256);
            byte[] hash = f.generateSecret(spec).getEncoded();
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            Log.e(TAG, "PBKDF2 计算失败", e);
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

    private String generateSalt() {
        SecureRandom sr = new SecureRandom();
        byte[] salt = new byte[16];
        sr.nextBytes(salt);
        StringBuilder hex = new StringBuilder();
        for (byte b : salt) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private JSONObject errorJson(String code, String message) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("action", "error");
            obj.put("code", code);
            obj.put("message", message);
        } catch (JSONException ignored) {}
        return obj;
    }

    private void closeSilently(ClientConnection client) {
        try { client.socket.close(); } catch (Exception ignored) {}
    }

    private void closeSilently(Socket s) {
        try { s.close(); } catch (Exception ignored) {}
    }

    private void notifyCallback(String event, String data) {
        if (callback != null) {
            uiHandler.post(() -> callback.onEvent(event, data));
        }
    }

    // ═══════════════════════════════════════════════
    //              内部类
    // ═══════════════════════════════════════════════

    static class WsFrame {
        static final int TEXT = 0x01;
        static final int CLOSE = 0x08;
        static final int PING = 0x09;
        static final int PONG = 0x0A;

        int type;
        String payload;

        WsFrame(int type, String payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    static class ClientConnection {
        final String uid;
        final Socket socket;
        final OutputStream out;
        final BufferedReader reader;

        ClientConnection(String uid, Socket socket, OutputStream out, BufferedReader reader) {
            this.uid = uid;
            this.socket = socket;
            this.out = out;
            this.reader = reader;
        }

        void sendJson(JSONObject json) throws IOException {
            byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
            synchronized (out) {
                out.write(0x81);  // FIN + TEXT
                if (data.length < 126) {
                    out.write(data.length & 0x7F);
                } else if (data.length < 65536) {
                    out.write(126);
                    out.write((data.length >> 8) & 0xFF);
                    out.write(data.length & 0xFF);
                } else {
                    out.write(127);
                    for (int i = 7; i >= 0; i--) {
                        out.write((int) (data.length >> (i * 8)) & 0xFF);
                    }
                }
                out.write(data);
                out.flush();
            }
        }
    }

    /**
     * 账号记录（存储在内存中，可序列化到 SharedPreferences）
     */
    public static class AccountRecord {
        public final String uid;
        public String hash;
        public String salt;
        public String signatureJson;
        public long createdAt;

        public AccountRecord(String uid, String hash, String salt, String signatureJson, long createdAt) {
            this.uid = uid;
            this.hash = hash;
            this.salt = salt;
            this.signatureJson = signatureJson;
            this.createdAt = createdAt;
        }
    }

    /**
     * 回调接口，将事件传递给 UI 层
     */
    public interface Callback {
        void onEvent(String event, String data);
    }
}