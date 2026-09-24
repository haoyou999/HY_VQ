package com.aliya.hy_vq.terracotta.punch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * P2P 传输实现集合（由 HyVqP2pBridge 组装并注入加密器与回调）。
 *
 * <p>四种传输路径对应"各种打洞方案"：
 * ① 局域网 TCP 直连（UDP 组播发现后握手）
 * ② UDP 打洞直连（STUN + 信令交换公网地址后复用同一 socket）
 * ③ TCP 同时打开（NAT 锥型时双方同时 connect 打洞）
 * ④ MQTT 中继兜底（公共 broker 转发加密行）</p>
 *
 * <p>行协议：握手阶段明文（HYVQJ|room|name / HYVQO|room|name / HYVQX / PING）；
 * 连接建立后所有数据行统一 "E|" + Base64(AES-GCM)。</p>
 */
public final class Transports {

    private Transports() {}

    /** 桥接层回调 */
    public interface Hook {
        /** 收到对端明文消息（工作线程回调） */
        void onMessage(String line);
        /** 传输断开（工作线程回调） */
        void onLost(Transport t);
        /** 日志 */
        void onLog(String s);
        /** 对端请求一起降级中继（单向检测到后通知对方，保持会话） */
        default void onDowngrade() {}
    }

    public interface Transport {
        String name();
        String peerLabel();
        void send(String line);
        void sendRaw(String line);
        void close();
    }

    // ── ① 局域网 TCP / ③ TCP 同时打开（共用一个实现，行协议 + 心跳） ──

    public static final class TcpTransport implements Transport {
        private static final String PKT_BYE = "HYVQX";
        /** 单行最大长度：超限视为恶意/异常对端，直接断开（防 readLine 无限扩容 OOM） */
        private static final int MAX_LINE = 65536;

        private final Socket sock;
        private final PrintWriter out;
        private final String label;
        private final boolean wan;
        private final Hook hook;
        private final P2pCrypto crypto;
        private volatile boolean closed;
        /** 可靠层（retransmit=false：TCP 内核已可靠，只统一序号/去重/回 ACK） */
        private final Reliable reliable;

        /** @param in 握手阶段已读过的 reader（避免缓冲数据丢失） */
        public TcpTransport(Socket s, BufferedReader in, PrintWriter out, String label,
                            boolean wan, Hook hook, P2pCrypto crypto) {
            this.sock = s;
            this.out = out;
            this.label = label;
            this.wan = wan;
            this.hook = hook;
            this.crypto = crypto;
            // 对端可能是 UDP/中继（需要 ACK 去重），TCP 端必须回 ACK 防对端重传风暴；
            // 本端不追踪重传（TCP 内核保证送达，丢失即断连由 reader 发现）。
            this.reliable = new Reliable(false, new Reliable.Callback() {
                @Override public void sendPacket(String plain) {
                    String enc = crypto.encrypt(plain);
                    if (enc != null) out.println("E|" + enc);
                }
                @Override public void onMessage(String text) { hook.onMessage(text); }
                @Override public void onConfirmLost() { /* TCP 不启用重传 */ }
            });
            startReader(in);
            startHeartbeat();
        }

        @Override public String name() { return wan ? "TCP同时打开（异地打洞）" : "局域网TCP直连"; }
        @Override public String peerLabel() { return label; }

        @Override public void send(String line) {
            if (closed) return;
            reliable.sendMessage(line);
        }

        @Override public void sendRaw(String line) {
            if (!closed) out.println(line);
        }

        private void startReader(BufferedReader in) {
            Thread t = new Thread(() -> {
                try {
                    String line;
                    while (!closed && (line = in.readLine()) != null) {
                        if (line.length() > MAX_LINE) { // 超长行：恶意/异常，断开
                            hook.onLog("[连接] 收到超长数据行，断开连接");
                            break;
                        }
                        if (line.equals(PKT_BYE)) {
                            hook.onLog("[连接] 对方主动断开");
                            break;
                        }
                        if (line.equals("PING")) continue;
                        if (line.equals("RELAY_DOWN")) { // 对端检测到单向，请求一起降级中继
                            hook.onLog("[连接] 对端请求降级中继");
                            hook.onDowngrade();
                            continue;
                        }
                        if (line.startsWith("E|")) {
                            String plain = crypto.decrypt(line.substring(2));
                            if (plain == null) continue;
                            if (plain.equals("PING")) continue; // 兼容旧版保活
                            String msg = reliable.handle(plain);
                            if (msg != null) hook.onMessage(msg);
                        }
                    }
                } catch (Throwable ignored) {
                }
                boolean wasClosed = closed;
                close();
                if (!wasClosed) hook.onLog("[连接] TCP 连接已断开");
                hook.onLost(TcpTransport.this);
            }, "HyVqP2p-TcpReader");
            t.setDaemon(true);
            t.start();
        }

        private void startHeartbeat() {
            Thread hb = new Thread(() -> {
                try {
                    while (!closed) {
                        Thread.sleep(20000);
                        out.println("PING");
                    }
                } catch (Throwable ignored) {
                }
            }, "HyVqP2p-TcpHb");
            hb.setDaemon(true);
            hb.start();
        }

        @Override public void close() {
            closed = true;
            try { sock.close(); } catch (Throwable ignored) {}
        }
    }

    // ── ② UDP 打洞直连（复用打洞 socket，AES-GCM 行协议 + 心跳保活） ──

    public static final class UdpTransport implements Transport {
        private static final String PUNCH_PREFIX = "HYVQP";

        private final DatagramSocket sock;
        private final InetSocketAddress peer;
        private final String label;
        private final Hook hook;
        private final P2pCrypto crypto;
        private volatile boolean closed;
        private volatile long lastRecv = System.currentTimeMillis();
        private volatile boolean confirmLost;
        /** 可靠层：序号 + ACK + 指数退避重传 + 去重（KCP 简化版，纯 Java） */
        private final Reliable reliable;

        public UdpTransport(DatagramSocket s, InetSocketAddress p, String label,
                            Hook hook, P2pCrypto crypto) {
            this.sock = s;
            this.peer = p;
            this.label = label;
            this.hook = hook;
            this.crypto = crypto;
            this.reliable = new Reliable(true, new Reliable.Callback() {
                @Override public void sendPacket(String plain) {
                    String enc = crypto.encrypt(plain);
                    if (enc != null) sendPacketRaw("E|" + enc);
                }
                @Override public void onMessage(String text) { hook.onMessage(text); }
                @Override public void onConfirmLost() {
                    // 发送方向确认全部丢失（重传 3 次仍无 ACK）= 单向通道，
                    // 先通知对端一起降级中继，再断开（保持会话）。
                    if (confirmLost) return;
                    confirmLost = true;
                    hook.onLog("[连接] 发送确认丢失（重传全失败），请求对端降级中继");
                    sendRaw("RELAY_DOWN");
                }
            });
            startReader();
            startHeartbeat();
        }

        @Override public String name() { return "UDP打洞直连（异地）"; }
        @Override public String peerLabel() { return label; }

        @Override public void send(String line) {
            if (closed) return;
            reliable.sendMessage(line);
        }

        @Override public void sendRaw(String line) {
            sendPacketRaw(line);
        }

        private void sendPacketRaw(String data) {
            try {
                byte[] d = data.getBytes(StandardCharsets.UTF_8);
                sock.send(new DatagramPacket(d, d.length, peer));
            } catch (Throwable ignored) {
            }
        }

        private void startReader() {
            Thread t = new Thread(() -> {
                try {
                    byte[] buf = new byte[2048];
                    while (!closed) {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        try {
                            sock.setSoTimeout(1000);
                            sock.receive(p);
                        } catch (SocketTimeoutException e) {
                            continue;
                        }
                        if (!p.getSocketAddress().equals(peer)) continue; // 过滤杂包
                        String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                        lastRecv = System.currentTimeMillis();
                        if (msg.startsWith(PUNCH_PREFIX)) continue; // 迟到打洞包
                        if (msg.equals("HYVQX")) { // 对方 BYE
                            hook.onLog("[连接] 对方主动断开");
                            break;
                        }
                        if (msg.equals("RELAY_DOWN")) { // 对端检测到单向，请求一起降级中继
                            hook.onLog("[连接] 对端请求降级中继");
                            hook.onDowngrade();
                            continue;
                        }
                        if (!msg.startsWith("E|")) continue;
                        String plain = crypto.decrypt(msg.substring(2));
                        if (plain == null) continue;
                        if (plain.equals("PING")) continue; // 兼容旧版保活
                        String m = reliable.handle(plain);
                        if (m != null) hook.onMessage(m);
                    }
                } catch (Throwable ignored) {
                }
                boolean wasClosed = closed;
                close();
                if (!wasClosed) hook.onLog("[连接] UDP 通道已断开");
                hook.onLost(UdpTransport.this);
            }, "HyVqP2p-UdpReader");
            t.setDaemon(true);
            t.start();
        }

        private void startHeartbeat() {
            Thread hb = new Thread(() -> {
                try {
                    // FCL 式：建立后立即发第一个带序号的 PING（对方回 ACK，
                    // punch 阶段即可双向确认 + 建立后持续确认通道活性）
                    reliable.sendPing();
                    int beat = 0;
                    while (!closed && !confirmLost) {
                        Thread.sleep(500);
                        beat++;
                        reliable.tick(); // 重传超时未确认的包（含消息与心跳）
                        // 单向判定：10s 无对端任何包 且 发送确认全部丢失（重传 4 次无 ACK）
                        // = 通道确实已死。单纯 10s 无包不算（瞬时丢包可由重传恢复，
                        // confirmLost 为 false 时不降级，避免误触发中继切换）。
                        if (System.currentTimeMillis() - lastRecv > 10000 && confirmLost) {
                            hook.onLog("[连接] UDP 心跳超时且确认全部丢失，请求对端降级中继");
                            sendRaw("RELAY_DOWN");
                            break;
                        }
                        if (beat % 6 == 0) reliable.sendPing(); // 每 3s 一次带序号保活
                    }
                } catch (Throwable ignored) {
                }
                boolean wasClosed = closed;
                close();
                if (!wasClosed) hook.onLost(UdpTransport.this);
            }, "HyVqP2p-UdpHb");
            hb.setDaemon(true);
            hb.start();
        }

        @Override public void close() {
            closed = true;
            try { sock.close(); } catch (Throwable ignored) {}
        }
    }

    // ── ④ MQTT 中继兜底（加密行经公共 broker 的 data Topic 转发） ──
    //
    // 多路并行（可靠性增强）：主通道 + 后台补路（最多 2 条冗余链路，各连不同
    // broker）。发送时向所有链路同时发布（双发/三发），任一 broker 可达即送达；
    // 接收端按 Reliable 序号去重——同一加密行从多条路到达只上抛一次，多路择优
    // 天然成立，单 broker 故障无感切换（补路线程还会继续补新链路）。

    public static final class RelayTransport implements Transport {
        /** 冗余链路数（主通道之外最多再补 2 条） */
        private static final int MAX_EXTRA_LINKS = 2;

        private final String dataTopic;
        private final String myDir;
        private final String peerDir;
        private final String label;
        private final Hook hook;
        private final P2pCrypto crypto;
        private volatile boolean closed;
        private volatile long lastRecv = System.currentTimeMillis();
        private volatile boolean confirmLost;
        /** 可靠层：中继（MQTT）同样会丢包/乱序，需完整序号+ACK+重传 */
        private final Reliable reliable;
        /** 全部数据链路（主通道 + 冗余路），写时复制保证遍历安全 */
        private final java.util.concurrent.CopyOnWriteArrayList<SigChannel> links =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        /** @param myDir 本端方向前缀："H"(房主) / "G"(客人) */
        public RelayTransport(SigChannel c, String dataTopic, String myDir, String label,
                              Hook hook, P2pCrypto crypto) {
            this.dataTopic = dataTopic;
            this.myDir = myDir;
            this.peerDir = myDir.equals("H") ? "G" : "H";
            this.label = label;
            this.hook = hook;
            this.crypto = crypto;
            this.reliable = new Reliable(true, new Reliable.Callback() {
                @Override public void sendPacket(String plain) {
                    String enc = crypto.encrypt(plain);
                    if (enc != null) {
                        byte[] data = (myDir + "|" + enc).getBytes(StandardCharsets.UTF_8);
                        // 多路并行双发：任一 broker 可达即送达（接收端按序号去重）。
                        // QoS1（PUBACK 确认 broker 收到）：公共免费 broker 限流/过载时
                        // QoS0 会被静默丢弃（#9 真机 40s 后中继断开 + 消息丢失的根因之一）
                        for (SigChannel link : links) {
                            link.publish(dataTopic, data, 1);
                        }
                    }
                }
                @Override public void onMessage(String text) { hook.onMessage(text); }
                @Override public void onConfirmLost() {
                    // 全部链路（含冗余路）发送确认均丢失 = MQTT 通道实际已断，
                    // 断开让上层走"中继心跳超时"同一条恢复路径（重连/回等待页）。
                    if (confirmLost) return;
                    confirmLost = true;
                    hook.onLog("[连接] 中继发送确认丢失（全部链路重传失败）");
                }
            });
            c.subscribe(dataTopic);
            links.add(c);
            startLinkReader(c);
            startExtraLinks(); // 后台异步补冗余链路，不阻塞主通道立即可用
            startHeartbeat();
        }

        @Override public String name() { return "MQTT中继（打洞失败兜底，多路并行）"; }
        @Override public String peerLabel() { return label; }

        @Override public void send(String line) {
            if (closed) return;
            reliable.sendMessage(line);
        }

        @Override public void sendRaw(String line) {
            if (!closed) {
                byte[] data = (myDir + "|" + line).getBytes(StandardCharsets.UTF_8);
                for (SigChannel link : links) {
                    link.publish(dataTopic, data, 1); // QoS1：与 sendPacket 一致，防静默丢弃
                }
            }
        }

        /** 收到 data Topic 消息（各链路 reader 线程回调）：过滤方向、解密、分发 */
        public void onRelayData(String payload) {
            if (closed || payload == null || payload.length() < 2) return;
            char dir = payload.charAt(0);
            if (dir != peerDir.charAt(0)) return;
            String body = payload.substring(2);
            lastRecv = System.currentTimeMillis();
            if (body.equals("HYVQX")) {
                hook.onLog("[连接] 对方主动断开");
                close();
                hook.onLost(RelayTransport.this);
                return;
            }
            if (!body.startsWith("E|")) return;
            String plain = crypto.decrypt(body.substring(2));
            if (plain == null) return;
            if (plain.equals("PING")) return; // 兼容旧版保活
            String m = reliable.handle(plain); // 序号去重 + 回 ACK（多路重复帧自动丢弃）
            if (m != null) hook.onMessage(m);
        }

        /** 每条链路一个独立读线程：持续 poll，把 data Topic 消息喂给 onRelayData。
         * 关键修复：此前中继数据依赖外部主循环 poll，客人端 establish 后主循环退出，
         * 无人 poll 导致 15s 假心跳超时 → 双方级联断开"莫名回到等待态"。 */
        private void startLinkReader(SigChannel link) {
            Thread t = new Thread(() -> {
                try {
                    while (!closed) {
                        SigChannel.Msg m = link.pollMessage(1000);
                        if (m == null) continue;
                        if (m.topic.equals(dataTopic)) {
                            onRelayData(new String(m.payload, StandardCharsets.UTF_8));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }, "HyVqP2p-RelayReader");
            t.setDaemon(true);
            t.start();
        }

        /** 后台补路：周期尝试连接冗余 broker（错开索引），连上即加入多路双发 */
        private void startExtraLinks() {
            Thread t = new Thread(() -> {
                int extra = 0;
                int waitMs = 15000;
                while (!closed && extra < MAX_EXTRA_LINKS) {
                    try {
                        // 主通道从索引 0 轮询，冗余路从索引 2/4 起步，错开 broker
                        SigChannel extraCh = new SigChannel(2 + extra * 2);
                        if (extraCh.connect()) {
                            extraCh.subscribe(dataTopic);
                            links.add(extraCh);
                            startLinkReader(extraCh);
                            extra++;
                            hook.onLog("[中继] 冗余链路 +1（" + extraCh.brokerName()
                                    + "），当前 " + links.size() + " 条并行");
                            waitMs = 30000; // 成功后再等久一点（避免频繁重连）
                        }
                    } catch (Throwable ignored) {
                    }
                    try { Thread.sleep(waitMs); } catch (InterruptedException ignored) { break; }
                }
            }, "HyVqP2p-RelayExtra");
            t.setDaemon(true);
            t.start();
        }

        private void startHeartbeat() {
            Thread hb = new Thread(() -> {
                try {
                    // 中继也是不可靠通道：立即发首个带序号 PING（对方回 ACK 即双向确认）
                    reliable.sendPing();
                    int beat = 0;
                    while (!closed && !confirmLost) {
                        Thread.sleep(500);
                        beat++;
                        reliable.tick(); // 重传超时未确认的包（消息与心跳）
                        if (confirmLost) break; // 全部链路确认丢失 = 中继已断
                        // 30s 无任何包才判死：公共免费 broker 瞬时抖动/限流常见，
                        // 15s 太紧容易误判断开（#9 真机中继 40s 断开与此相关）
                        if (System.currentTimeMillis() - lastRecv > 30000) {
                            hook.onLog("[连接] 中继心跳超时");
                            break;
                        }
                        if (beat % 10 == 0) reliable.sendPing(); // 每 5s 带序号保活
                    }
                } catch (Throwable ignored) {
                }
                boolean wasClosed = closed;
                close();
                if (!wasClosed) hook.onLost(RelayTransport.this);
            }, "HyVqP2p-RelayHb");
            hb.setDaemon(true);
            hb.start();
        }

        @Override public void close() {
            closed = true;
            for (SigChannel link : links) {
                try { link.close(); } catch (Throwable ignored) {}
            }
            links.clear();
        }
    }
}