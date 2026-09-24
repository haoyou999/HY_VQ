package com.aliya.hy_vq.terracotta.punch;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 纯 Java STUN 客户端（RFC5389 Binding Request，零依赖）。
 *
 * <p>关键设计：查询公网映射必须与打洞共用同一个 DatagramSocket，
 * 否则查到的公网端口与打洞端口不一致（旧 service/StunClient 的致命缺陷）。
 * 本类要求调用方传入打洞共用的 socket。</p>
 *
 * <p>探测架构（v3，修复并发 receive 丢响应 bug）：
 * 每个源一个发送线程（DNS 解析+send，并行不互拖），
 * <b>单接收线程</b>统一 receive 并按 transaction id 分发到对应源——
 * 旧版 10 线程并发抢同一 socket 的 receive，响应包常被错误线程拿走丢弃
 * （transaction 不匹配），导致全部超时"公网地址探测失败"。
 * 另加 UDP 丢包重发补偿（每 1.5s 对未响应源重发），
 * 收到第一个结果+第二个端口后提前结束，总预算可控。</p>
 *
 * <p>源列表：国内优先（小米/腾讯/B站/12VoIP/Sipnet 等 2025-07 国内实测可达）
 * + Google 多数据中心 + Cloudflare/StunProtocol 国际兜底，多源并发大幅提高探测成功率。</p>
 */
public final class StunProbe {

    public static final class Result {
        public final String publicIp;
        public final int publicPort;
        /** 映射行为中文描述（初步判断） */
        public final String mapping;
        public final String server;

        Result(String ip, int port, String mapping, String server) {
            this.publicIp = ip;
            this.publicPort = port;
            this.mapping = mapping;
            this.server = server;
        }

        @Override
        public String toString() {
            return publicIp + ":" + publicPort + "（" + mapping + "）";
        }
    }

    private static final byte[] MAGIC = {0x21, 0x12, (byte) 0xA4, 0x42};

    /** STUN 服务器（国内优先 + 国际兜底，2025-07 国内实测可达性验证过）：
     *  国内源对国内网络（尤其运营商 CGNAT）映射更稳定；源多但"先到先得+提前结束"，
     *  收到 2 个响应即返回，不会因源多而拖慢。 */
    private static final String[][] SERVERS = {
            // ── 国内/亚太（优先，实测可达）──
            {"stun.miwifi.com", "3478"},
            {"stun.qq.com", "3478"},
            {"stun.chat.bilibili.com", "3478"},
            {"stun.12voip.com", "3478"},
            {"stun.sipnet.ru", "3478"},
            {"stun.freeswitch.org", "3478"},
            {"stun.ekiga.net", "3478"},
            {"stun.nextcloud.com", "3478"},
            // ── 国际大厂（多数据中心，稳定）──
            {"stun.l.google.com", "19302"},
            {"stun1.l.google.com", "19302"},
            {"stun2.l.google.com", "19302"},
            {"stun3.l.google.com", "19302"},
            {"stun4.l.google.com", "19302"},
            {"stun.cloudflare.com", "3478"},
            {"stun.stunprotocol.org", "3478"},
            // ── 扩展源（持续在线验证，覆盖更多运营商路由）──
            {"stun.t-online.de", "3478"},
            {"stun.gmx.net", "3478"},
            {"stun.1und1.de", "3478"},
            {"stun.zadarma.com", "3478"},
            {"stun.voip.blackberry.com", "3478"},
            {"stun.voipgate.com", "3478"},
            {"stun.fitauto.ru", "3478"},
            {"stun.epygi.com", "3478"},
            {"stun.sipnet.net", "3478"},
    };

    /** 映射保活目标（国内优先 5 台 + Google 国际兜底）：
     *  国内部分网络对境外 STUN 不通，单源保活会失效导致映射被回收——多源同时发，
     *  任一可达即保住映射（UDP 包极轻，5s 周期内发 6 个可忽略）。 */
    private static final String[][] KEEPALIVE_SERVERS = {
            {"stun.miwifi.com", "3478"},
            {"stun.qq.com", "3478"},
            {"stun.chat.bilibili.com", "3478"},
            {"stun.12voip.com", "3478"},
            {"stun.sipnet.ru", "3478"},
            {"stun.l.google.com", "19302"},
    };

    /** 单源发送状态（发送线程解析+send；接收线程按 transaction id 分发结果） */
    private static final class Pending {
        final String host;
        final int port;
        final byte[] req;
        volatile InetSocketAddress target; // DNS 解析成功后的目标
        volatile boolean sent;
        volatile boolean done; // 已收到有效响应

        Pending(String host, int port, byte[] req) {
            this.host = host;
            this.port = port;
            this.req = req;
        }
    }

    private StunProbe() {}

    /**
     * 用指定 socket 查询公网映射（该 socket 后续直接用于打洞，端口一致）。
     *
     * @param sock      打洞共用的 DatagramSocket（已绑定）
     * @param timeoutMs 单台服务器超时预算
     * @return 公网映射结果；全部失败返回 null
     */
    public static Result query(DatagramSocket sock, long timeoutMs) {
        final AtomicReference<Result> first = new AtomicReference<>();
        final AtomicInteger secondPort = new AtomicInteger(-1);
        // 预生成全部请求与发送线程（并行解析 DNS：慢源不拖快源）
        final Pending[] pendings = new Pending[SERVERS.length];
        for (int i = 0; i < SERVERS.length; i++) {
            final Pending p = new Pending(SERVERS[i][0],
                    Integer.parseInt(SERVERS[i][1]), buildRequest());
            pendings[i] = p;
            new Thread(() -> {
                try {
                    p.target = new InetSocketAddress(InetAddress.getByName(p.host), p.port);
                    sock.send(new DatagramPacket(p.req, p.req.length, p.target));
                    p.sent = true;
                } catch (Throwable ignored) {
                    // DNS 失败/发送失败：该源跳过
                }
            }, "StunTx-" + p.host).start();
        }
        // 单接收线程：统一 receive + 按 transaction id 分发（修复多线程并发 receive 丢包）
        final long deadline = System.currentTimeMillis() + timeoutMs + 3000L;
        final long resendEvery = Math.max(1200L, timeoutMs / 3);
        Thread rx = new Thread(() ->
                runReceiver(sock, deadline, resendEvery, pendings, first, secondPort), "StunRx");
        rx.setDaemon(true);
        rx.start();
        try {
            rx.join(timeoutMs + 3000L + 300L);
        } catch (InterruptedException ignored) {
        }
        Result f = first.get();
        if (f == null) return null;
        String mapping = "映射类型未知";
        int sp = secondPort.get();
        if (sp >= 0) {
            mapping = sp == f.publicPort ? "独立映射（可打洞）" : "依赖映射（打洞受限）";
        }
        return new Result(f.publicIp, f.publicPort, mapping, f.server);
    }

    /** 单接收线程主体：统一 receive + 按 transaction id 分发 + UDP 丢包重发补偿。
     *  提取为独立方法（lambda 内不允许修改捕获的局部变量，nextResend 需为方法内局部）。 */
    private static void runReceiver(DatagramSocket sock, long deadline, long resendEvery,
                                    Pending[] pendings, AtomicReference<Result> first,
                                    AtomicInteger secondPort) {
        long nextResend = System.currentTimeMillis() + resendEvery;
        try {
            sock.setSoTimeout(300);
            byte[] buf = new byte[1024];
            while (System.currentTimeMillis() < deadline) {
                // 提前结束：拿到第一个结果 + 第二个端口（NAT 行为判断完成）
                if (first.get() != null && secondPort.get() >= 0) break;
                long now = System.currentTimeMillis();
                // UDP 丢包补偿：对已发送但未响应的源重发（UDP 丢包常见，重发大幅提高命中）
                if (now >= nextResend) {
                    nextResend = now + resendEvery;
                    if (first.get() == null) {
                        for (Pending p : pendings) {
                            if (p.sent && !p.done && p.target != null) {
                                try {
                                    sock.send(new DatagramPacket(p.req, p.req.length, p.target));
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                }
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(resp);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (resp.getLength() < 20) continue;
                // 按 transaction id 找到对应源
                Pending hit = null;
                for (Pending p : pendings) {
                    if (matchesTransaction(resp.getData(), p.req)) {
                        hit = p;
                        break;
                    }
                }
                if (hit == null) continue;
                InetSocketAddress addr = parseMapped(resp.getData(), resp.getLength());
                if (addr == null || isPrivate(addr.getAddress().getHostAddress())) continue;
                hit.done = true;
                synchronized (StunProbe.class) {
                    if (first.get() == null) {
                        first.set(new Result(addr.getAddress().getHostAddress(),
                                addr.getPort(), "", hit.host));
                    } else if (secondPort.get() < 0
                            && !hit.host.equals(first.get().server)) {
                        secondPort.set(addr.getPort());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 构造 20 字节 Binding Request（RFC5389 消息头） */
    private static byte[] buildRequest() {
        byte[] msg = new byte[20];
        msg[0] = 0x00;
        msg[1] = 0x01; // Binding Request
        System.arraycopy(MAGIC, 0, msg, 4, 4);
        byte[] tid = new byte[12];
        new SecureRandom().nextBytes(tid);
        System.arraycopy(tid, 0, msg, 8, 12);
        return msg;
    }

    private static boolean matchesTransaction(byte[] resp, byte[] req) {
        for (int i = 0; i < 12; i++) {
            if (resp[8 + i] != req[8 + i]) return false;
        }
        return true;
    }

    /** 解析 XOR-MAPPED-ADDRESS（0x0020）优先，MAPPED-ADDRESS（0x0001）兜底 */
    private static InetSocketAddress parseMapped(byte[] data, int length) {
        int msgType = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        if (msgType != 0x0101) return null; // 仅处理 Binding Success Response
        int msgLen = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        int end = Math.min(length, 20 + msgLen);
        int pos = 20;
        InetSocketAddress fallback = null;
        while (pos + 4 <= end) {
            int attrType = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            int attrLen = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            pos += 4;
            if (pos + attrLen > end) break;
            if (attrType == 0x0020 && attrLen >= 8) { // XOR-MAPPED-ADDRESS
                if ((data[pos + 1] & 0xFF) == 0x01) { // IPv4
                    int xPort = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
                    int port = xPort ^ (((MAGIC[0] & 0xFF) << 8) | (MAGIC[1] & 0xFF));
                    byte[] ip = new byte[4];
                    for (int i = 0; i < 4; i++) ip[i] = (byte) (data[pos + 4 + i] ^ MAGIC[i]);
                    try {
                        return new InetSocketAddress(InetAddress.getByAddress(ip), port);
                    } catch (Exception ignored) {
                    }
                }
            } else if (attrType == 0x0001 && attrLen >= 8) { // MAPPED-ADDRESS
                if ((data[pos + 1] & 0xFF) == 0x01) {
                    int port = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
                    byte[] ip = new byte[4];
                    System.arraycopy(data, pos + 4, ip, 0, 4);
                    try {
                        fallback = new InetSocketAddress(InetAddress.getByAddress(ip), port);
                    } catch (Exception ignored) {
                    }
                }
            }
            pos += (attrLen + 3) & ~3;
        }
        return fallback;
    }

    /** 过滤局域网/回环地址（部分国内 STUN 返回内网映射） */
    private static boolean isPrivate(String ip) {
        if (ip == null) return true;
        if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("127.")) return true;
        if (ip.startsWith("172.")) {
            try {
                int second = Integer.parseInt(ip.split("\\.")[1]);
                if (second >= 16 && second <= 31) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
     * FCL 式映射保活：向多台 STUN 服务器发 Binding Request（只发不收）。
     * 打洞前/等待信令期间周期性调用，保持 CGNAT 的 UDP 映射新鲜，
     * 防止"STUN 查到的端口在打洞前被回收/漂移"导致打洞失败。
     * 多源同时发：国内网络对境外 STUN 常不通，单源失效即保活失败。
     */
    public static void sendKeepaliveAll(DatagramSocket sock) {
        for (String[] srv : KEEPALIVE_SERVERS) {
            try {
                byte[] req = buildRequest();
                sock.send(new DatagramPacket(req, req.length,
                        new InetSocketAddress(InetAddress.getByName(srv[0]),
                                Integer.parseInt(srv[1]))));
            } catch (Throwable ignored) {
            }
        }
    }
}