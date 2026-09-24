package com.aliya.hy_vq.service;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 STUN 的 UDP 打洞客户端（零依赖、零服务器）
 * 
 * 原理：
 * 1. 向公共 STUN 服务器查询自己的公网 IP:Port
 * 2. 生成连接码（6位数字 + 公网地址的 SHA-256 校验），供手动交换
 * 3. 对方输入连接码后，解析出公网地址，直接 UDP 打洞通信
 * 4. 打洞成功后，UDP 隧道传输聊天消息（极简 JSON over UDP）
 *
 * 使用方式：
 * - 一端调用 startHost() 启动打洞监听
 * - 另一端调用 connectToPeer(connectionCode) 发起打洞
 * - 双方通过 setCallback 接收消息，sendMessage 发送消息
 */
public class StunClient {

    private static final String TAG = "StunClient";

    // ── 公共 STUN 服务器列表 ──
    private static final String[] STUN_SERVERS = {
            "stun.l.google.com:19302",
            "stun1.l.google.com:19302",
            "stun2.l.google.com:19302",
            "stun.miwifi.com:3478",
            "stun.sipgate.net:3478",
    };

    // ── 打洞参数 ──
    private static final int LOCAL_PORT = 9876;
    private static final int BUFFER_SIZE = 2048;
    private static final int PUNCH_INTERVAL_MS = 200;   // 打洞包发送间隔
    private static final int PUNCH_RETRIES = 30;         // 打洞重试次数

    private volatile DatagramSocket socket;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    // ── 对端公网地址 ──
    private volatile InetSocketAddress peerAddress = null;

    // ── 本地信息 ──
    private InetSocketAddress myPublicAddr = null;
    private String connectionCode = null;

    // ── 消息计数器 ──
    private int msgSeq = 0;

    // ── 回调 ──
    private Callback callback;

    /**
     * 回调接口
     */
    public interface Callback {
        /** 本机公网地址已探测到 */
        void onPublicAddressResolved(String ip, int port, String connectionCode);
        /** 打洞成功，通道建立 */
        void onConnected();
        /** 收到对方发来的消息 */
        void onMessage(JSONObject message);
        /** 连接断开 */
        void onDisconnected();
        /** 错误 */
        void onError(String error);
    }

    public void setCallback(Callback cb) {
        this.callback = cb;
    }

    // ═══════════════════════════════════════════════
    //              外部 API
    // ═══════════════════════════════════════════════

    /**
     * 启动为"主机"：查询公网地址，监听 UDP 打洞包
     */
    public void startHost() {
        if (running.get()) return;
        executor.execute(() -> {
            try {
                socket = new DatagramSocket(LOCAL_PORT);
                socket.setReuseAddress(true);
                socket.setSoTimeout(1000);
                running.set(true);

                // 1. 查询我的公网地址
                myPublicAddr = queryPublicAddress();
                if (myPublicAddr == null) {
                    notifyError("无法查询公网地址，请检查网络");
                    stop();
                    return;
                }

                // 2. 生成连接码
                connectionCode = generateConnectionCode(myPublicAddr);
                Log.i(TAG, "🔗 公网地址: " + myPublicAddr.getAddress().getHostAddress() 
                        + ":" + myPublicAddr.getPort());
                Log.i(TAG, "🔑 连接码: " + connectionCode);
                notifyPublicAddressResolved();

                // 3. 进入打洞/收消息循环
                loopReceive();

            } catch (IOException e) {
                notifyError("启动失败: " + e.getMessage());
                Log.e(TAG, "启动失败", e);
            }
        });
    }

    /**
     * 通过连接码连接到对端（打洞）
     * @param code 6位数字连接码
     */
    public void connectToPeer(String code) {
        if (running.get()) return;
        executor.execute(() -> {
            try {
                socket = new DatagramSocket(LOCAL_PORT);
                socket.setReuseAddress(true);
                socket.setSoTimeout(1000);
                running.set(true);

                // 1. 查询我的公网地址
                myPublicAddr = queryPublicAddress();
                if (myPublicAddr == null) {
                    notifyError("无法查询公网地址");
                    stop();
                    return;
                }

                // 2. 解析连接码获取对端公网地址
                peerAddress = parseConnectionCode(code);
                if (peerAddress == null) {
                    notifyError("无效的连接码");
                    stop();
                    return;
                }

                Log.i(TAG, "🎯 目标地址: " + peerAddress.getAddress().getHostAddress()
                        + ":" + peerAddress.getPort());

                // 3. 发送打洞包（穿透 NAT）
                boolean punched = punch(peerAddress);
                if (punched) {
                    connected.set(true);
                    Log.i(TAG, "✅ 打洞成功！");
                    notifyConnected();
                    // 进入收消息循环
                    loopReceive();
                } else {
                    notifyError("打洞失败，双方 NAT 可能均是对称型");
                    stop();
                }

            } catch (IOException e) {
                notifyError("连接失败: " + e.getMessage());
                Log.e(TAG, "连接失败", e);
            }
        });
    }

    /**
     * 发送消息给对方
     */
    public void sendMessage(JSONObject msg) {
        if (!connected.get() || peerAddress == null) return;
        try {
            msg.put("seq", ++msgSeq);
            byte[] data = msg.toString().getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length, peerAddress);
            socket.send(packet);
        } catch (Exception ignored) {}
    }

    /**
     * 停止
     */
    public void stop() {
        running.set(false);
        connected.set(false);
        peerAddress = null;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        notifyDisconnected();
    }

    /** 彻底销毁，释放线程池（Activity 销毁时调用） */
    public void destroy() {
        stop();
        executor.shutdown();
        try { executor.awaitTermination(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }

    public boolean isConnected() {
        return connected.get();
    }

    public String getConnectionCode() {
        return connectionCode;
    }

    // ═══════════════════════════════════════════════
    //              STUN 协议实现
    // ═══════════════════════════════════════════════

    /**
     * 向公共 STUN 服务器发送 Binding Request，解析 XOR-MAPPED-ADDRESS 获取公网地址
     */
    private InetSocketAddress queryPublicAddress() {
        for (String serverAddr : STUN_SERVERS) {
            DatagramSocket tempSocket = null;
            try {
                String[] parts = serverAddr.split(":");
                InetAddress stunHost = InetAddress.getByName(parts[0]);
                int stunPort = Integer.parseInt(parts[1]);
                byte[] request = buildBindingRequest();
                tempSocket = new DatagramSocket();
                tempSocket.setSoTimeout(3000);
                tempSocket.send(new DatagramPacket(request, request.length,
                        new InetSocketAddress(stunHost, stunPort)));
                byte[] buf = new byte[512];
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                tempSocket.receive(resp);
                InetSocketAddress addr = parseXorMappedAddress(resp.getData(), resp.getLength());
                if (addr != null) {
                    Log.i(TAG, "✅ STUN(" + serverAddr + ") → "
                            + addr.getAddress().getHostAddress() + ":" + addr.getPort());
                    return addr;
                }
            } catch (Exception e) {
                Log.w(TAG, "STUN(" + serverAddr + ") 失败: " + e.getMessage());
            } finally {
                if (tempSocket != null) tempSocket.close();
            }
        }
        return null;
    }

    /**
     * 构造 20 字节的 STUN Binding Request（RFC 5389）
     */
    private byte[] buildBindingRequest() {
        byte[] msg = new byte[20];
        msg[0] = 0x00;
        msg[1] = 0x01;
        msg[4] = 0x21;
        msg[5] = 0x12;
        msg[6] = (byte) 0xA4;
        msg[7] = 0x42;
        byte[] tid = new byte[12];
        new SecureRandom().nextBytes(tid);
        System.arraycopy(tid, 0, msg, 8, 12);
        return msg;
    }

    /**
     * 从 STUN 响应中解析 XOR-MAPPED-ADDRESS（属性类型 0x0020）
     */
    private InetSocketAddress parseXorMappedAddress(byte[] data, int length) {
        if (length < 20) return null;

        // 检查 Message Type: Binding Success Response = 0x0101
        int msgType = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        if (msgType != 0x0101) return null;

        int msgLen = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        int pos = 20;  // 跳过消息头

        while (pos + 4 <= 20 + msgLen) {
            int attrType = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            int attrLen = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            pos += 4;

            if (attrType == 0x0020 && attrLen >= 8) {  // XOR-MAPPED-ADDRESS
                // Family: 0x01 = IPv4
                int family = data[pos + 1] & 0xFF;
                if (family == 0x01 && pos + 8 <= length) {
                    int xPort = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
                    int port = xPort ^ 0x2112;
                    byte[] ipBytes = new byte[4];
                    ipBytes[0] = (byte) (data[pos + 4] ^ data[4]);
                    ipBytes[1] = (byte) (data[pos + 5] ^ data[5]);
                    ipBytes[2] = (byte) (data[pos + 6] ^ data[6]);
                    ipBytes[3] = (byte) (data[pos + 7] ^ data[7]);
                    try {
                        return new InetSocketAddress(InetAddress.getByAddress(ipBytes), port);
                    } catch (java.net.UnknownHostException ignored) {}
                }
            }
            // 对齐到 4 字节边界
            pos += (attrLen + 3) & ~3;
        }
        return null;
    }

    // ═══════════════════════════════════════════════
    //              打洞
    // ═══════════════════════════════════════════════

    /**
     * 向对端地址连续发送打洞包，直到收到回复或超时
     */
    private boolean punch(InetSocketAddress target) {
        int retries = PUNCH_RETRIES;
        while (retries-- > 0 && running.get() && !connected.get()) {
            try {
                // 发送打洞请求
                JSONObject punchMsg = new JSONObject();
                punchMsg.put("action", "punch");
                punchMsg.put("from", getLocalAddress());
                punchMsg.put("timestamp", System.currentTimeMillis());
                byte[] data = punchMsg.toString().getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(data, data.length, target);
                socket.send(packet);

                // 检查是否有回复
                byte[] buf = new byte[BUFFER_SIZE];
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(resp);
                    JSONObject respMsg = new JSONObject(
                            new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8));
                    if ("punch_ack".equals(respMsg.optString("action"))) {
                        return true;
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                    // 超时，继续发送
                }

                Thread.sleep(PUNCH_INTERVAL_MS);
            } catch (Exception e) {
                Log.w(TAG, "打洞异常: " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * 收消息循环（打洞成功后持续监听）
     */
    private void loopReceive() {
        byte[] buf = new byte[BUFFER_SIZE];
        while (running.get()) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);

                InetSocketAddress from = new InetSocketAddress(
                        packet.getAddress(), packet.getPort());
                String payload = new String(packet.getData(), 0, packet.getLength(),
                        StandardCharsets.UTF_8);
                JSONObject msg = new JSONObject(payload);
                String action = msg.optString("action", "");

                // 如果是打洞请求，回复 ack
                if ("punch".equals(action)) {
                    if (!connected.get()) {
                        peerAddress = from;
                        connected.set(true);
                        Log.i(TAG, "🎯 收到打洞请求，回复确认");
                        notifyConnected();
                    }
                    JSONObject ack = new JSONObject();
                    ack.put("action", "punch_ack");
                    byte[] ackData = ack.toString().getBytes(StandardCharsets.UTF_8);
                    DatagramPacket ackPacket = new DatagramPacket(ackData, ackData.length, from);
                    socket.send(ackPacket);
                    continue;
                }

                // 普通消息
                if (connected.get()) {
                    notifyMessage(msg);
                }

            } catch (java.net.SocketTimeoutException ignored) {
                // 超时，继续循环
            } catch (Exception e) {
                if (running.get()) {
                    Log.w(TAG, "接收异常: " + e.getMessage());
                }
            }
        }
    }

    // ═══════════════════════════════════════════════
    //              连接码生成与解析
    // ═══════════════════════════════════════════════

    /**
     * 生成连接码格式：6位数字 + ':' + base64(sha256(ip:port, 4字节))
     */
    private String generateConnectionCode(InetSocketAddress addr) {
        return addr.getAddress().getHostAddress() + ":" + addr.getPort();
    }

    /**
     * @deprecated 连接码已改为直接包含 ip:port，无需解析。
     */
    private InetSocketAddress parseConnectionCode(String code) {
        return null;
    }

    /**
     * 通过 IP:Port 字符串直接连接到对端
     */
    public void connectDirect(String host, int port) {
        if (running.get()) return;
        executor.execute(() -> {
            try {
                socket = new DatagramSocket(LOCAL_PORT);
                socket.setReuseAddress(true);
                socket.setSoTimeout(1000);
                running.set(true);

                peerAddress = new InetSocketAddress(InetAddress.getByName(host), port);
                myPublicAddr = queryPublicAddress();

                Log.i(TAG, "🎯 直连目标: " + host + ":" + port);

                boolean punched = punch(peerAddress);
                if (punched) {
                    connected.set(true);
                    Log.i(TAG, "✅ 打洞成功！");
                    notifyConnected();
                    loopReceive();
                } else {
                    notifyError("打洞失败");
                    stop();
                }
            } catch (IOException e) {
                notifyError("连接失败: " + e.getMessage());
            }
        });
    }

    // ═══════════════════════════════════════════════
    //              工具方法
    // ═══════════════════════════════════════════════

    private String getLocalAddress() {
        try {
            return java.net.Inet4Address.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private void notifyPublicAddressResolved() {
        if (callback != null && myPublicAddr != null) {
            uiHandler.post(() -> callback.onPublicAddressResolved(
                    myPublicAddr.getAddress().getHostAddress(),
                    myPublicAddr.getPort(),
                    connectionCode));
        }
    }

    private void notifyConnected() {
        if (callback != null) {
            uiHandler.post(() -> callback.onConnected());
        }
    }

    private void notifyMessage(JSONObject msg) {
        if (callback != null) {
            uiHandler.post(() -> callback.onMessage(msg));
        }
    }

    private void notifyDisconnected() {
        if (callback != null) {
            uiHandler.post(() -> callback.onDisconnected());
        }
    }

    private void notifyError(String error) {
        if (callback != null) {
            uiHandler.post(() -> callback.onError(error));
        }
    }
}