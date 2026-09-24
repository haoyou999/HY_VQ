package com.aliya.hy_vq.terracotta.punch;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLSocketFactory;

/**
 * MQTT 3.1.1 最小客户端（纯 Java Socket + SSLSocket 手写报文，零外部依赖）。
 *
 * <p>用途：跨网打洞的信令交换（房间 = Topic）+ 打洞失败时的消息中继。</p>
 * <p>公共 broker 轮询连接（TLS 优先、明文兜底），断线自动切换并重订阅。</p>
 * <p>报文实现范围：CONNECT/CONNACK、SUBSCRIBE(QoS0)、PUBLISH(QoS0/1)、
 * PUBACK 应答、PINGREQ/PINGRESP 保活。</p>
 */
public final class SigChannel {

    /** 公共 broker 列表：host, port, tls */
    private static final String[][] BROKERS = {
            {"broker.emqx.io", "8883", "true"},
            {"broker.emqx.io", "1883", "false"},
            {"broker.hivemq.com", "8883", "true"},
            {"broker.hivemq.com", "1883", "false"},
            {"test.mosquitto.org", "8883", "true"},
            {"test.mosquitto.org", "1883", "false"},
    };

    public static final class Msg {
        public final String topic;
        public final byte[] payload;

        Msg(String t, byte[] p) {
            topic = t;
            payload = p;
        }
    }

    /** 单帧最大字节数（MQTT 报文长度上限，防恶意超大帧导致 OOM） */
    private static final int MAX_FRAME = 65536;

    private final LinkedBlockingQueue<Msg> queue = new LinkedBlockingQueue<>();
    private final Map<String, Boolean> subscribed = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger nextPacketId = new AtomicInteger(1);

    private volatile Socket socket;
    private volatile DataInputStream in;
    private volatile DataOutputStream out;
    private volatile String brokerName = "";
    private volatile long lastPong;
    private volatile Thread readerThread;
    private volatile Thread pingThread;
    private volatile int brokerIndex = 0;
    /** broker 轮询起点偏移（多路并行时各实例错开，避免全连同一台） */
    private final int brokerStart;

    public SigChannel() {
        this(0);
    }

    public SigChannel(int brokerStart) {
        this.brokerStart = brokerStart % BROKERS.length;
        this.brokerIndex = this.brokerStart;
    }

    /** 连接任一公共 broker（轮询），成功返回 true */
    public synchronized boolean connect() {
        for (int i = 0; i < BROKERS.length; i++) {
            if (closed.get()) return false;
            int idx = (brokerIndex + i) % BROKERS.length;
            String host = BROKERS[idx][0];
            int port = Integer.parseInt(BROKERS[idx][1]);
            boolean tls = "true".equals(BROKERS[idx][2]);
            if (open(host, port, tls)) {
                brokerIndex = idx;
                brokerName = host + ":" + port + (tls ? "(TLS)" : "");
                lastPong = System.currentTimeMillis();
                startReader();
                startPing();
                for (String topic : subscribed.keySet()) {
                    sendSubscribe(topic);
                }
                return true;
            }
        }
        return false;
    }

    public boolean isConnected() {
        return socket != null && !socket.isClosed();
    }

    public String brokerName() {
        return brokerName;
    }

    /** 当前实际连上的 broker 索引（connect 成功后有效）。
     * 同一房间的所有信令/中继通道必须连同一个 broker——MQTT broker 之间互不相通，
     * 跨 broker 的 topic 订阅永远收不到消息（#8 真机「中继未建立」根因之一）。 */
    public int currentIndex() {
        return brokerIndex;
    }

    /** 订阅 topic（QoS0） */
    public synchronized boolean subscribe(String topic) {
        subscribed.put(topic, Boolean.TRUE);
        return sendSubscribe(topic);
    }

    /** 发布消息：qos 0 或 1（信令建议 1，靠上层周期重发兜底） */
    public synchronized boolean publish(String topic, byte[] payload, int qos) {
        DataOutputStream d = out;
        if (d == null) return false;
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] t = topic.getBytes(StandardCharsets.UTF_8);
            body.write((t.length >> 8) & 0xFF);
            body.write(t.length & 0xFF);
            body.write(t, 0, t.length);
            if (qos > 0) {
                int pid = nextPacketId.getAndIncrement() & 0xFFFF;
                body.write((pid >> 8) & 0xFF);
                body.write(pid & 0xFF);
            }
            body.write(payload, 0, payload.length);
            ByteArrayOutputStream pkt = new ByteArrayOutputStream();
            pkt.write(0x30 | (qos << 1));
            writeRemainingLength(pkt, body.size());
            pkt.write(body.toByteArray());
            d.write(pkt.toByteArray());
            d.flush();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 阻塞取一条消息；超时返回 null */
    public Msg pollMessage(long timeoutMs) {
        try {
            return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return null;
        }
    }

    public void close() {
        closed.set(true);
        closeSocket();
    }

    // ── 连接 ──

    private boolean open(String host, int port, boolean tls) {
        Socket s = null;
        try {
            if (tls) {
                SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
                s = f.createSocket();
                // 安全规范：原始 SSLSocket 默认只验证证书链、不验证主机名，
                // 必须开启 endpoint identification（HTTPS 模式），否则存在
                // MITM 风险（攻击者用任意合法 CA 证书冒充 broker）。
                // 参考官方文档：To verify hostnames, pass "HTTPS" to
                // SSLParameters.setEndpointIdentificationAlgorithm(String).
                javax.net.ssl.SSLParameters params =
                        ((javax.net.ssl.SSLSocket) s).getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                ((javax.net.ssl.SSLSocket) s).setSSLParameters(params);
            } else {
                s = new Socket();
            }
            s.connect(new InetSocketAddress(host, port), 6000);
            s.setSoTimeout(15000);
            DataInputStream din = new DataInputStream(s.getInputStream());
            DataOutputStream dout = new DataOutputStream(s.getOutputStream());
            dout.write(buildConnect());
            dout.flush();
            s.setSoTimeout(8000);
            int type = din.readUnsignedByte();
            if (type != 0x20) { // 非 CONNACK
                s.close();
                return false;
            }
            int remLen = readRemainingLength(din);
            if (remLen < 0 || remLen > MAX_FRAME) { // 长度校验：防恶意超大帧导致 OOM
                s.close();
                return false;
            }
            byte[] body = new byte[remLen];
            din.readFully(body);
            if (body.length < 2 || body[1] != 0) { // 返回码非 0
                s.close();
                return false;
            }
            socket = s;
            in = din;
            out = dout;
            // 连接建立后读线程必须无限阻塞：SO_TIMEOUT 只用于 CONNACK 等待，若保留 8s，
            // broker 静默期（无 PUBLISH/PINGRESP）reader 每 8s 必超时 → reconnect 风暴
            // （重连窗口消息全丢、反复 CONNECT 触发公共 broker 限流）。断链检测交给：
            // ① reader read 异常（RST/FIN 立即唤醒）② ping 线程 45s 无 PINGRESP 判死重连。
            s.setSoTimeout(0);
            return true;
        } catch (Throwable t) {
            try {
                if (s != null) s.close();
            } catch (Throwable ignored) {
            }
            return false;
        }
    }

    private void closeSocket() {
        try {
            if (socket != null) socket.close();
        } catch (Throwable ignored) {
        }
        socket = null;
        in = null;
        out = null;
    }

    /** CONNECT 报文（clean session + keepalive 60s + 随机 clientId） */
    private byte[] buildConnect() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0);
        body.write(4);
        body.write('M');
        body.write('Q');
        body.write('T');
        body.write('T');
        body.write(4); // protocol level
        body.write(2); // clean session
        body.write(0);
        body.write(60); // keepalive 60s
        String clientId = "hyvq-" + Long.toHexString(new SecureRandom().nextLong());
        byte[] cid = clientId.getBytes(StandardCharsets.UTF_8);
        body.write((cid.length >> 8) & 0xFF);
        body.write(cid.length & 0xFF);
        body.write(cid, 0, cid.length);
        ByteArrayOutputStream pkt = new ByteArrayOutputStream();
        pkt.write(0x10);
        writeRemainingLength(pkt, body.size());
        pkt.write(body.toByteArray());
        return pkt.toByteArray();
    }

    private boolean sendSubscribe(String topic) {
        DataOutputStream d = out;
        if (d == null) return false;
        try {
            int pid = nextPacketId.getAndIncrement() & 0xFFFF;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write((pid >> 8) & 0xFF);
            body.write(pid & 0xFF);
            byte[] t = topic.getBytes(StandardCharsets.UTF_8);
            body.write((t.length >> 8) & 0xFF);
            body.write(t.length & 0xFF);
            body.write(t, 0, t.length);
            body.write(0); // QoS 0
            ByteArrayOutputStream pkt = new ByteArrayOutputStream();
            pkt.write(0x82);
            writeRemainingLength(pkt, body.size());
            pkt.write(body.toByteArray());
            d.write(pkt.toByteArray());
            d.flush();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ── 读循环与保活 ──

    private void startReader() {
        // reconnect() 循环中 connect() 成功会再次调用：旧 reader 因旧 socket 关闭已退出，
        // 但旧 ping 线程可能仍存活（见 startPing）——reader 也需防重复启动，避免双线程竞态
        if (readerThread != null && readerThread.isAlive()) return;
        readerThread = new Thread(() -> {
            try {
                while (!closed.get()) {
                    DataInputStream din = in;
                    if (din == null) {
                        Thread.sleep(500);
                        continue;
                    }
                    int type = din.readUnsignedByte();
                    int remLen = readRemainingLength(din);
                    if (remLen < 0 || remLen > MAX_FRAME) { // 防恶意超大帧 OOM：断开重连
                        closeSocket();
                        if (!closed.get()) reconnect();
                        break;
                    }
                    byte[] body = new byte[remLen];
                    din.readFully(body);
                    handle(type, body);
                }
            } catch (Throwable t) {
                if (!closed.get()) reconnect();
            }
        }, "HyVqSig-Reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void handle(int type, byte[] body) {
        switch (type & 0xF0) {
            case 0x30: { // PUBLISH
                int qos = (type >> 1) & 0x03;
                if (body.length < 2) return;
                int tLen = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
                if (2 + tLen > body.length) return;
                String topic = new String(body, 2, tLen, StandardCharsets.UTF_8);
                int pos = 2 + tLen;
                if (qos > 0) pos += 2;
                byte[] payload = new byte[body.length - pos];
                System.arraycopy(body, pos, payload, 0, payload.length);
                if (qos == 1) sendPubAck(body, pos);
                queue.offer(new Msg(topic, payload));
                break;
            }
            case 0xD0: // PINGRESP
                lastPong = System.currentTimeMillis();
                break;
            default:
                break; // CONNACK/SUBACK/PUBACK 忽略
        }
    }

    private void sendPubAck(byte[] body, int payloadPos) {
        try {
            DataOutputStream d = out;
            if (d == null || payloadPos < 2) return;
            int pid = ((body[payloadPos - 2] & 0xFF) << 8) | (body[payloadPos - 1] & 0xFF);
            ByteArrayOutputStream pkt = new ByteArrayOutputStream();
            pkt.write(0x40);
            pkt.write(0x02);
            pkt.write((pid >> 8) & 0xFF);
            pkt.write(pid & 0xFF);
            d.write(pkt.toByteArray());
            d.flush();
        } catch (Throwable ignored) {
        }
    }

    private void startPing() {
        // reconnect() 每次 connect() 成功都会调用 startPing()：若旧 ping 线程仍存活
        // （closed=false 时它不会自行退出），会启动双 ping 线程并发发 PING/触发重连——
        // 必须防重复启动（旧线程与新线程共享 lastPong，双重检测会造成无谓的重连风暴）
        if (pingThread != null && pingThread.isAlive()) return;
        pingThread = new Thread(() -> {
            try {
                while (!closed.get()) {
                    Thread.sleep(15000);
                    DataOutputStream d = out;
                    if (d == null) continue;
                    d.write(new byte[]{(byte) 0xC0, 0x00});
                    d.flush();
                    if (System.currentTimeMillis() - lastPong > 45000) {
                        reconnect();
                    }
                }
            } catch (Throwable t) {
                if (!closed.get()) reconnect();
            }
        }, "HyVqSig-Ping");
        pingThread.setDaemon(true);
        pingThread.start();
    }

    private void reconnect() {
        closeSocket();
        if (closed.get()) return;
        // 保留当前 broker 优先重连（不再立即换 broker——换 broker 会破坏与对端的
        // broker 一致性，导致信令/中继消息跨 broker 丢失）。全部失败则 2s 后重试，
        // 直到成功或关闭：旧实现 connect() 一次失败线程就死亡，通道永久不可用
        // （#9 真机中继 40s 断开的根因之一）。
        while (!closed.get()) {
            if (connect()) return;
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // ── 剩余长度编解码（MQTT 变长整数） ──

    private static int readRemainingLength(DataInputStream in) throws IOException {
        int value = 0;
        int multiplier = 1;
        int count = 0;
        int b;
        do {
            b = in.readUnsignedByte();
            value += (b & 0x7F) * multiplier;
            multiplier *= 128;
            if (++count > 4) throw new IOException("invalid remaining length");
        } while ((b & 0x80) != 0);
        return value;
    }

    private static void writeRemainingLength(ByteArrayOutputStream bos, int length) {
        do {
            int digit = length % 128;
            length /= 128;
            if (length > 0) digit |= 0x80;
            bos.write(digit);
        } while (length > 0);
    }
}