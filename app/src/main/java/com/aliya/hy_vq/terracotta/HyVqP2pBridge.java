package com.aliya.hy_vq.terracotta;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.aliya.hy_vq.terracotta.punch.P2pCrypto;
import com.aliya.hy_vq.terracotta.punch.SigChannel;
import com.aliya.hy_vq.terracotta.punch.StunProbe;
import com.aliya.hy_vq.terracotta.punch.Transports;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Random;

/**
 * HY_VQ 房间联机桥接层 V2：跨网 P2P 打洞组网（"使用各种打洞方案异地组网"）。
 *
 * <p>传输路径按可用性自动选择（先到先得），四种方案全部内置：</p>
 * <ol>
 *   <li>局域网 TCP 直连——UDP 组播发现（同一 WiFi/热点，零外部流量）</li>
 *   <li>UDP 打洞直连——STUN 公网映射 + MQTT 公共信令交换地址（异地首选）</li>
 *   <li>TCP 同时打开——NAT 锥型时的备选打洞（双方同时 connect）</li>
 *   <li>MQTT 中继兜底——公共 broker 转发加密行（对称 NAT 保底可达）</li>
 * </ol>
 *
 * <p>信令：公共 MQTT broker（零自建服务器），Topic = hyvq/r/{房间码}/h|g|d；
 * 加密：AES-256-GCM（密钥由房间码经 PBKDF2 派生，见 P2pCrypto），所有数据通道统一加密；
 * 线程模型：广播/接受/查找/跨网各守护线程，状态回调主线程，FCL 式日志流。</p>
 */
public class HyVqP2pBridge {

    private static final String TAG = "HyVqP2pBridge";

    // ── 局域网发现协议 ──
    private static final String MULTICAST_IP = "239.255.77.77";
    private static final int DISCOVERY_PORT = 47777;
    private static final String PKT_HOST = "HYVQH";
    private static final String PKT_FIND = "HYVQF";
    private static final String PKT_HELLO = "HYVQJ";
    private static final String PKT_OK = "HYVQO";
    private static final long FIND_TIMEOUT_MS = 8000L;

    // ── 跨网打洞参数 ──
    private static final String PUNCH_PREFIX = "HYVQP";
    private static final long STUN_TIMEOUT_MS = 4000L;
    private static final long PUNCH_ATTEMPT_MS = 12000L;
    private static final long TCP_SIMUL_ATTEMPT_MS = 10000L;
    private static final long WAN_TOTAL_TIMEOUT_MS = 45000L;
    /** 同网优先直连的 TCP 连接超时（同网失败快速回退打洞，不拖慢整体流程） */
    private static final int LAN_CONNECT_TIMEOUT_MS = 3000;
    /** IPv6 直连快速尝试窗口：IPv6 无 NAT，同时打开几乎必成，优先且短超时 */
    private static final long IPV6_ATTEMPT_MS = 3000L;
    private static final int ROLE_NONE = 0;
    private static final int ROLE_HOST = 1;
    private static final int ROLE_GUEST = 2;
    /** 同 CGNAT 出口快速失败打洞窗口：hairpin 大多不支持，快速转中继（不浪费 15s） */
    private static final long SAME_NAT_PUNCH_MS = 3000L;
    /** 房主主信令实际连上的 broker 索引：会话/中继通道必须用同一 broker（MQTT 不互通） */
    private static volatile int hostBrokerIndex = 0;
    /** 客人主信令实际连上的 broker 索引：断线降级中继必须沿用同一 broker——host 主循环
     * 断线重连保留当前 broker 不换，降级时若从 0 重新轮询很可能连到别的 broker，
     * relay_req 发过去 host 收不到（MQTT broker 互不相通），中继兜底必然失败 */
    private static volatile int guestBrokerIndex = 0;

    // ── 多人房间：消息帧与会话 ──
    /** 数据消息帧：M|昵称|内容（昵称已过滤 | 与换行，host 转发时跳过来源会话防回声） */
    private static final String MSG_PREFIX = "M|";
    /** guest 端固定 peerKey（host 端 key = guestId） */
    private static final String PEER_HOST = "host";
    /** 客人会话 ID：6 位 hex（24bit 熵，房间内碰撞概率可忽略） */
    private static final java.security.SecureRandom GID_RAND = new java.security.SecureRandom();

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final StringBuilder logTail = new StringBuilder();

    /** 状态监听（UI 控制器注册，主线程回调） */
    public interface StateListener {
        void onStateChanged(TerracottaState state);
        default void onLogsUpdated(String tail) {}
        /** 收到对端消息（异地聊天数据通道，主线程回调） */
        default void onMessage(String line) {}
    }

    /** 传输层回调桥（工作线程 → 桥接层） */
    private static final Transports.Hook HOOK = new Transports.Hook() {
        @Override
        public void onMessage(String line) {
            deliverMessage(line);
        }

        @Override
        public void onLost(Transports.Transport t) {
            onTransportLost(t);
        }

        @Override
        public void onDowngrade() {
            handleDowngrade();
        }

        @Override
        public void onLog(String s) {
            log(s);
        }
    };

    private static volatile StateListener listener;
    private static volatile Context appContext;
    private static volatile WifiManager.MulticastLock multicastLock;
    /** 最近一次状态（供 UI 重新打开对话框时重放恢复，关闭弹窗不丢状态） */
    private static volatile TerracottaState lastState;

    private static volatile boolean running;
    private static volatile int role = ROLE_NONE;
    private static volatile String roomCode;
    private static volatile String playerName = "HY_VQ用户";

    // 局域网
    private static volatile ServerSocket server;
    private static volatile Thread broadcastThread;
    private static volatile Thread acceptThread;
    private static volatile Thread findThread;

    // 跨网
    private static volatile Thread wanThread;
    private static volatile SigChannel sig;
    private static volatile DatagramSocket punchSock;
    private static volatile StunProbe.Result stunResult;
    private static volatile boolean announceLogged;
    /** 客人端本会话 ID（断线中继降级时沿用，保持 topic 一致） */
    private static volatile String guestGid;

    // 当前生效传输表（host 端 key=guestId，guest 端 key="host"）+ 加密器
    private static final java.util.concurrent.ConcurrentHashMap<String, Transports.Transport> transports =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile P2pCrypto crypto;

    /** 房主侧客人会话表（多人房间：每客人一个独立会话：信令/打洞/传输/中继全隔离） */
    private static final java.util.concurrent.ConcurrentHashMap<String, GuestSession> guests =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 房主侧一个客人的完整会话（含独立 Hook 闭包：消息带来源，转发时跳过来源防回声） */
    static final class GuestSession {
        final String gid;
        final String name;
        volatile Transports.Transport transport;
        /** 会话线程是否已退出：线程存活期间 relay_req 由会话线程处理（防主循环双响应重复建中继）；
         * 线程退出（直连成功/会话结束）后由主循环接管断线降级的中继请求 */
        volatile boolean sessionDone;

        GuestSession(String gid, String name) {
            this.gid = gid;
            this.name = name;
        }
    }

    private HyVqP2pBridge() {}

    // ── 对外 API（UI 控制器直接调用本类） ──

    /** 无需初始化原生库（纯 Java 实现）；保存上下文供组播锁使用 */
    public static boolean initialize(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
        return true;
    }

    public static void setListener(StateListener l) {
        listener = l;
        if (l != null && logTail.length() > 0) {
            postLogs();
        }
    }

    /** 最近一次状态（UI 重新打开对话框时重放恢复；从未流转过返回 null） */
    public static TerracottaState getLastState() {
        return lastState;
    }

    public static String getLatestLogTail() {
        return logTail.toString();
    }

    /** 房主开房：局域网广播 + 跨网信令双通道 */
    public static boolean hostRoom(String ignoredRoom, String player) {
        playerName = normalize(player);
        cleanup();
        acquireMulticastLock();
        try {
            server = new ServerSocket(0);
            roomCode = randomRoom();
            running = true;
            role = ROLE_HOST;
            crypto = new P2pCrypto(roomCode);

            // 局域网组播广播线程
            broadcastThread = new Thread(() -> {
                MulticastSocket ms = null;
                try {
                    ms = new MulticastSocket();
                    while (running) {
                        String pkt = PKT_HOST + "|" + roomCode + "|" + server.getLocalPort() + "|" + playerName;
                        byte[] data = pkt.getBytes(StandardCharsets.UTF_8);
                        ms.send(new DatagramPacket(data, data.length,
                                InetAddress.getByName(MULTICAST_IP), DISCOVERY_PORT));
                        Thread.sleep(1000);
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (ms != null) ms.close();
                }
            }, "HyVqP2p-Bcast");
            broadcastThread.setDaemon(true);
            broadcastThread.start();

            // 局域网接受连接线程
            acceptThread = new Thread(() -> {
                try {
                    while (running) {
                        Socket s = server.accept();
                        handleIncoming(s);
                    }
                } catch (Throwable ignored) {
                }
            }, "HyVqP2p-Accept");
            acceptThread.setDaemon(true);
            acceptThread.start();

            // 跨网打洞线程
            wanThread = new Thread(HyVqP2pBridge::hostWanFlow, "HyVqP2p-HostWan");
            wanThread.setDaemon(true);
            wanThread.start();

            log("[房间] 已创建房间 " + roomCode + "（局域网 TCP 端口 " + server.getLocalPort() + "）");
            log("[组网] 双通道就绪：同网组播直连 + 异地打洞组网");
            fire(TerracottaState.of(TerracottaState.Kind.HOST_SCANNING, roomCode, null));
            return true;
        } catch (Throwable t) {
            log("[房间] 创建失败: " + t.getMessage());
            fire(TerracottaState.of(TerracottaState.Kind.EXCEPTION, null, null,
                    "开房失败：" + t.getMessage()));
            return false;
        }
    }

    /** 客人加入：局域网查找与跨网打洞并行 */
    public static boolean joinRoom(String room, String player) {
        playerName = normalize(player);
        cleanup();
        acquireMulticastLock();
        roomCode = room;
        running = true;
        role = ROLE_GUEST;
        crypto = new P2pCrypto(room);

        // 局域网组播查找线程
        findThread = new Thread(() -> {
            MulticastSocket ms = null;
            try {
                ms = new MulticastSocket();
                ms.joinGroup(InetAddress.getByName(MULTICAST_IP));
                long deadline = System.currentTimeMillis() + FIND_TIMEOUT_MS;
                log("[加入] 正在局域网内查找房间 " + room + "…");
                while (running && transports.isEmpty() && System.currentTimeMillis() < deadline) {
                    byte[] find = (PKT_FIND + "|" + room).getBytes(StandardCharsets.UTF_8);
                    ms.send(new DatagramPacket(find, find.length,
                            InetAddress.getByName(MULTICAST_IP), DISCOVERY_PORT));
                    ms.setSoTimeout(1000);
                    byte[] buf = new byte[512];
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        ms.receive(p);
                        String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                        if (!msg.startsWith(PKT_HOST)) continue;
                        String[] parts = msg.split("\\|");
                        String hostRoom = parts.length > 1 ? parts[1] : "";
                        int port = parts.length > 2 ? Integer.parseInt(parts[2]) : -1;
                        String hostName = parts.length > 3 ? parts[3] : "房主";
                        if (!hostRoom.equals(room) || port <= 0) continue;
                        log("[加入] 发现主机「" + hostName + "」 " + p.getAddress().getHostAddress() + ":" + port);
                        Socket s = new Socket();
                        s.connect(new InetSocketAddress(p.getAddress().getHostAddress(), port), 5000);
                        Transports.TcpTransport tt = handshakeAsGuest(s,
                                p.getAddress().getHostAddress(), false);
                        if (tt != null && establish(PEER_HOST, tt)) return;
                    } catch (SocketTimeoutException ignored) {
                    } catch (Throwable t) {
                        log("[加入] 局域网连接失败: " + t.getMessage());
                    }
                }
                log("[加入] 局域网未发现房间（等待异地打洞通道…）");
            } catch (Throwable t) {
                log("[加入] 局域网查找异常: " + t.getMessage());
            } finally {
                if (ms != null) ms.close();
            }
        }, "HyVqP2p-LanFind");
        findThread.setDaemon(true);
        findThread.start();

        // 跨网打洞线程
        wanThread = new Thread(HyVqP2pBridge::guestWanFlow, "HyVqP2p-GuestWan");
        wanThread.setDaemon(true);
        wanThread.start();

        fire(TerracottaState.of(TerracottaState.Kind.GUEST_CONNECTING, room, null));
        return true;
    }

    /** 回到等待状态（结束联机/断开） */
    public static void backToWaiting() {
        // sendRaw 是 Socket 写，不能在主线程执行（NetworkOnMainThreadException）
        final java.util.List<Transports.Transport> all =
                new java.util.ArrayList<>(transports.values());
        new Thread(() -> {
            for (Transports.Transport ft : all) {
                try { ft.sendRaw("HYVQX"); } catch (Throwable ignored) {}
                try { ft.close(); } catch (Throwable ignored) {}
            }
        }, "HyVqP2p-Bye").start();
        log("[连接] 已断开，回到等待态");
        cleanup();
        fire(TerracottaState.of(TerracottaState.Kind.WAITING, null, null));
    }

    /** 彻底停止 P2P：断开全部连接 + 释放组播锁 + 清空状态（供 Activity 销毁时调用）。
     * 与 backToWaiting 不同：不发 WAITING 状态回调（UI 已销毁），静默收尾。 */
    public static void stop() {
        final java.util.List<Transports.Transport> all =
                new java.util.ArrayList<>(transports.values());
        new Thread(() -> {
            for (Transports.Transport ft : all) {
                try { ft.sendRaw("HYVQX"); } catch (Throwable ignored) {}
                try { ft.close(); } catch (Throwable ignored) {}
            }
        }, "HyVqP2p-Bye").start();
        cleanup();
    }

    /** 房间码格式校验：6 位数字 */
    public static boolean isRoomCodeValid(String room) {
        return room != null && room.matches("\\d{6}");
    }

    /** 向房间内所有已连接成员广播一行文本（多人房间：host 转发给其他客人）。
     * 帧格式 M|昵称|内容（昵称已过滤分隔符），未连接返回 false */
    public static boolean sendLine(String line) {
        if (transports.isEmpty() || !running || line == null || line.isEmpty()) return false;
        String frame = MSG_PREFIX + sanitizeName(playerName) + "|" + line;
        // 网络写入必须在工作线程：UI 主线程直接 send 会触发
        // NetworkOnMainThreadException（TCP Socket 写必崩，UDP/中继同样风险）。
        // 串行队列 + 单发送线程：消息严格有序，无频繁建线程开销。
        ensureSendThread();
        boolean ok = sendQueue.offer(new SendItem(frame, null));
        if (!ok) log("[消息] 发送队列已满（" + SEND_QUEUE_CAPACITY + "），本条消息丢弃");
        return ok;
    }

    /** 待发送项：frame + 排除目标（host 转发时跳过来源会话，防回声） */
    static final class SendItem {
        final String frame;
        final String excludeKey;

        SendItem(String f, String ex) {
            frame = f;
            excludeKey = ex;
        }
    }

    /** 发送队列：主线程入队，发送线程串行写出（避免 NetworkOnMainThreadException）。
     * 容量上限 1024：防极端情况下无界堆积导致内存膨胀（满时丢弃并提示，聊天场景足够）。 */
    private static final int SEND_QUEUE_CAPACITY = 1024;
    private static final java.util.concurrent.LinkedBlockingQueue<SendItem> sendQueue =
            new java.util.concurrent.LinkedBlockingQueue<>(SEND_QUEUE_CAPACITY);
    private static volatile Thread sendThread;

    private static void ensureSendThread() {
        synchronized (HyVqP2pBridge.class) {
            if (sendThread != null && sendThread.isAlive()) return;
            sendThread = new Thread(() -> {
                try {
                    while (running) {
                        SendItem item = sendQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (item == null) continue;
                        // 广播：遍历当前全部连接（转发项跳过来源）
                        for (java.util.Map.Entry<String, Transports.Transport> e
                                : transports.entrySet()) {
                            if (item.excludeKey != null && item.excludeKey.equals(e.getKey())) continue;
                            try {
                                e.getValue().send(item.frame);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                } catch (InterruptedException ignored) {
                }
            }, "HyVqP2p-Send");
            sendThread.setDaemon(true);
            sendThread.start();
        }
    }

    /** 当前传输方式名（未连接返回空串） */
    public static String getTransportName() {
        if (transports.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Transports.Transport t : transports.values()) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(t.name());
        }
        return sb.toString();
    }

    /** 房间成员摘要（UI 成员栏展示）：房主 + N 名客人 */
    public static String getMembersText() {
        if (role == ROLE_HOST) {
            StringBuilder sb = new StringBuilder("👥 在线成员（").append(guests.size() + 1)
                    .append("）：").append(playerName);
            for (GuestSession g : guests.values()) {
                sb.append("、").append(g.name);
            }
            return sb.toString();
        }
        // 客人视角：房主 + 自己（其他成员通过消息昵称可见）
        return "👥 在线成员：我、" + playerName + "（房间 " + (roomCode == null ? "" : roomCode) + "）";
    }

    // ── 局域网：连接处理 ──

    private static void handleIncoming(Socket s) {
        HostHandshake hs = handshakeAsHost(s, s.getInetAddress().getHostAddress(), false);
        if (hs != null) {
            // 局域网客人：以随机 gid 登记会话（消息帧带昵称，身份不依赖 key）
            String gid = newGuestId();
            GuestSession gs = new GuestSession(gid, hs.guestName);
            guests.put(gid, gs);
            if (establish(gid, hs.t)) {
                log("[房间] 好友「" + hs.guestName + "」已加入（局域网直连）");
                fireMembers();
            } else {
                guests.remove(gid);
            }
        }
    }

    /** 主机侧握手结果：TCP 传输 + 客人昵称（多人房间成员登记用） */
    static final class HostHandshake {
        final Transports.TcpTransport t;
        final String guestName;

        HostHandshake(Transports.TcpTransport t, String guestName) {
            this.t = t;
            this.guestName = guestName;
        }
    }

    /** 主机侧握手：读 HELLO 校验房间码 → 回 OK → 建立加密 TCP 传输 */
    private static HostHandshake handshakeAsHost(Socket s, String label, boolean wan) {
        try {
            s.setSoTimeout(8000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null || !line.startsWith(PKT_HELLO)) {
                s.close();
                return null;
            }
            String[] parts = line.split("\\|");
            String guestRoom = parts.length > 1 ? parts[1] : "";
            String guestName = parts.length > 2 ? sanitizeName(parts[2]) : "未知玩家";
            if (!guestRoom.equals(roomCode)) {
                log("[房间] 拒绝了房间码不匹配的连接（" + guestRoom + "）");
                s.close();
                return null;
            }
            PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            out.println(PKT_OK + "|" + roomCode + "|" + playerName);
            s.setSoTimeout(35000);
            log("[房间] 好友「" + guestName + "」请求加入，握手完成");
            return new HostHandshake(new Transports.TcpTransport(s, in, out, label, wan, HOOK, crypto),
                    guestName);
        } catch (Throwable t) {
            try { s.close(); } catch (Throwable ignored) {}
            return null;
        }
    }

    /** 客人侧握手：发 HELLO → 等 OK → 建立加密 TCP 传输 */
    private static Transports.TcpTransport handshakeAsGuest(Socket s, String label, boolean wan) {
        try {
            s.setSoTimeout(8000);
            PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            out.println(PKT_HELLO + "|" + roomCode + "|" + playerName);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null || !line.startsWith(PKT_OK)) {
                log("[加入] 主机拒绝连接（房间码不匹配）");
                s.close();
                return null;
            }
            String[] parts = line.split("\\|");
            String hostName = parts.length > 2 ? parts[2] : "房主";
            log("[连接] 房主「" + hostName + "」确认握手");
            s.setSoTimeout(35000);
            return new Transports.TcpTransport(s, in, out, label, wan, HOOK, crypto);
        } catch (Throwable t) {
            try { s.close(); } catch (Throwable ignored) {}
            return null;
        }
    }

    // ── 跨网：房主流（多人房间主循环：收 hello 开独立会话线程，收 relay_req 建中继恢复） ──

    private static void hostWanFlow() {
        SigChannel ch = new SigChannel();
        sig = ch;
        try {
            if (!ch.connect()) {
                log("[信令] 公共信令服务器全部不可达，异地组网不可用");
                return;
            }
            log("[信令] 已连接公共信令（" + ch.brokerName() + "）");
            // 记录实际 broker：会话线程/中继通道必须与主循环同一 broker（MQTT 互不相通，
            // 跨 broker 订阅永远收不到消息——#8 真机「中继未建立」的致命根因）
            hostBrokerIndex = ch.currentIndex();
            String hostTopic = topicH();
            String guestPattern = topicGP(); // hyvq/r/{房间}/+/g
            ch.subscribe(guestPattern);
            ch.subscribe(hostTopic);
            publishAnnounce(ch, hostTopic, randomTcpPort());
            // 主循环持续运行：房间生命周期内随时响应新客人加入 / 老客人中继恢复
            String prefix = "hyvq/r/" + roomCode + "/";
            while (running) {
                SigChannel.Msg m = ch.pollMessage(800);
                if (m == null) continue;
                if (hostTopic.equals(m.topic)) continue; // 自己的 announce 回显忽略
                if (!m.topic.startsWith(prefix) || !m.topic.endsWith("/g")) continue;
                String gid = m.topic.substring(prefix.length(), m.topic.length() - 2);
                if (gid.isEmpty()) continue;
                JSONObject j = safeJson(m.payload);
                if (j == null) continue;
                String type = j.optString("type");
                if ("hello".equals(type)) {
                    // 新客人：独立会话线程（信令/打洞/传输全隔离，互不阻塞）
                    final String fGid = gid;
                    final JSONObject fJ = j;
                    if (guests.containsKey(fGid)) continue;
                    Thread st = new Thread(() -> handleGuestSession(fGid, fJ),
                            "HyVqP2p-Guest" + fGid);
                    st.setDaemon(true);
                    st.start();
                } else if ("relay_req".equals(type)) {
                    // 打洞失败兜底 / 断线降级恢复：独立中继通道（不占用主信令循环）
                    GuestSession gs = guests.get(gid);
                    if (gs != null && gs.transport != null) continue; // 已有连接，忽略重复请求
                    if (gs != null && !gs.sessionDone) continue; // 会话线程仍存活（打洞/等中继中），由它响应，防双响应重复建中继
                    handleRelayReq(gid, j);
                }
            }
        } catch (Throwable t) {
            log("[组网] 跨网通道异常: " + t.getMessage());
        } finally {
            finishWanFlow(ch, null, false);
        }
    }

    /** 房主侧单个客人会话：独立信令 + STUN + 保活 + 打洞（IPv6 优先）→ 中继等待。
     * 与其他客人会话完全隔离：一个客人卡住不影响其他成员。 */
    private static void handleGuestSession(String gid, JSONObject hello) {
        if (guests.containsKey(gid)) return;
        String gName = sanitizeName(hello.optString("name", "好友"));
        GuestSession gs = new GuestSession(gid, gName);
        guests.put(gid, gs);
        // ⚠ 必须与主循环连同一个 broker：MQTT broker 互不相通，跨 broker 的 topic
        // 订阅永远收不到消息（曾用 SigChannel(2) 错峰连 hivemq → addr 发到 hivemq、
        // guest 在 emqx 订阅收不到 → 中继预热不启动 →「打洞与中继均未成功」）
        SigChannel ch = new SigChannel(hostBrokerIndex);
        DatagramSocket sock = null;
        ServerSocket punchSs = null;
        try {
            String gTopic = topicGid(gid);
            String gData = topicD(gid);
            if (!ch.connect()) {
                log("[会话] 信令服务器不可达，会话「" + gName + "」放弃");
                return;
            }
            log("[会话] 会话信令已连接（" + ch.brokerName() + "）");
            ch.subscribe(gTopic);
            sock = new DatagramSocket(0);
            StunProbe.Result stun = StunProbe.query(sock, STUN_TIMEOUT_MS);
            if (stun != null) {
                log("[会话] 会话「" + gName + "」公网映射 " + stun);
            }
            // FCL 式映射保活（多源轮换：国内源优先）
            final DatagramSocket kaSock = sock;
            Thread ka = new Thread(() -> {
                try {
                    while (running && gs.transport == null && !kaSock.isClosed()) {
                        StunProbe.sendKeepaliveAll(kaSock);
                        Thread.sleep(5000);
                    }
                } catch (Throwable ignored) {
                }
            }, "HyVqP2p-Ka" + gid);
            ka.setDaemon(true);
            ka.start();
            int myTcp = randomTcpPort();
            // 打洞 TCP 监听（固定端口预热）：接受客人的普通 connect（同网/IPv6/公网直连），
            // 不依赖时序敏感的"同时打开"——NAT 后 IPv6 直达与同网场景下成功率大增。
            // 端口固定为公告 tcpPort，客人连入即走标准握手建立，先到先得。
            try {
                punchSs = new ServerSocket(myTcp);
                punchSs.setReuseAddress(true);
                final ServerSocket fSs = punchSs;
                Thread pa = new Thread(() -> handlePunchAccept(fSs, gs),
                        "HyVqP2p-PunchAccept" + gid);
                pa.setDaemon(true);
                pa.start();
            } catch (Throwable t) {
                log("[会话] 打洞监听端口绑定失败（" + myTcp + "），仅依赖同时打开: " + t.getMessage());
            }
            JSONObject reply = new JSONObject();
            try {
                reply.put("type", "addr");
                reply.put("name", playerName);
                if (stun != null) {
                    reply.put("ip", stun.publicIp);
                    reply.put("port", stun.publicPort);
                }
                reply.put("lanIp", localLanIp());
                reply.put("lanPort", myTcp); // 打洞监听端口（同网直连/普通 connect 目标）
                reply.put("udpPort", sock.getLocalPort()); // UDP 打洞内网候选端口
                reply.put("tcpPort", myTcp);
                reply.put("ipv6", localIpv6());
                reply.put("ts", System.currentTimeMillis());
            } catch (Exception ignored) {
            }
            ch.publish(gTopic, reply.toString().getBytes(StandardCharsets.UTF_8), 1);
            log("[会话] 已应答「" + gName + "」地址，开始打洞…");
            // addr 周期重发（可靠性）：guest 的 hello 每 2s 重发，但主循环对重复 hello
            // 直接忽略（guests.containsKey continue），addr 只发这一次——MQTT QoS1 不保证
            // 送达，addr 丢包则 guest 永远等不到地址，打洞/中继预热全不启动，只能干等
            // 超时。独立线程每 2s 重发 addr，直至会话建立或结束；guest 侧 rounds<3
            // 限制最多 3 次 punchAttempt，重复 addr 不会造成打洞风暴。
            final JSONObject fReply = reply;
            Thread addrRepub = new Thread(() -> {
                try {
                    while (running && gs.transport == null && ch.isConnected()) {
                        ch.publish(gTopic, fReply.toString().getBytes(StandardCharsets.UTF_8), 1);
                        Thread.sleep(2000);
                    }
                } catch (Throwable ignored) {
                }
            }, "HyVqP2p-AddrRepub" + gid);
            addrRepub.setDaemon(true);
            addrRepub.start();
            int gTcp = hello.optInt("tcpPort", 0);
            String gIpv6 = hello.optString("ipv6", "");
            int gUdpPort = hello.optInt("udpPort", 0);
            InetSocketAddress peerUdp = null;
            String gIp = hello.optString("ip");
            int gPort = hello.optInt("port", 0);
            if (stun != null && !gIp.isEmpty() && gPort > 0) {
                try {
                    peerUdp = new InetSocketAddress(InetAddress.getByName(gIp), gPort);
                } catch (Exception ignored) {
                }
            }
            InetSocketAddress peerLan = null;
            String gLanIp = hello.optString("lanIp", "");
            int gLanPort = hello.optInt("lanPort", 0);
            if (!gLanIp.isEmpty() && gLanPort > 0) {
                try {
                    peerLan = new InetSocketAddress(InetAddress.getByName(gLanIp), gLanPort);
                } catch (Exception ignored) {
                }
            }
            InetSocketAddress peerLanUdp = null;
            if (!gLanIp.isEmpty() && gUdpPort > 0) {
                try {
                    peerLanUdp = new InetSocketAddress(InetAddress.getByName(gLanIp), gUdpPort);
                } catch (Exception ignored) {
                }
            }
            // 同 CGNAT 出口检测：对端公网 IP 与本机相同且不同网 → hairpin 大多不支持，
            // IPv4 打洞物理失败（用户网络 IPv6 亦不可达），快速失败把时间让给中继
            boolean sameNat = stun != null && !gIp.isEmpty()
                    && stun.publicIp.equals(gIp) && !isSameLan(gLanIp);
            if (sameNat) {
                log("[会话] 检测到与「" + gName + "」同一运营商 NAT 出口（" + gIp
                        + "），快速打洞后转中继…");
            }
            if (punchAttempt(ch, sock, gid, myTcp, gIpv6, gTcp, peerUdp, peerLan,
                    peerLanUdp, gName, sessionHook(gs), sameNat)) {
                return;
            }
            if (gs.transport != null) return; // 打洞监听 accept 已建立（客人普通 connect 到达），正常成功路径
            // 打洞失败：等客人中继请求（客人侧收到 addr 即启动中继预热线程，
            // 打洞进行中已开始周期重发 relay_req；本窗口 30s 覆盖客人 punchAttempt
            // 最长耗时 + 首发延迟 + broker 投递，彻底消除窗口错位）
            long dl = System.currentTimeMillis() + 30000;
            while (running && gs.transport == null && System.currentTimeMillis() < dl) {
                SigChannel.Msg m = ch.pollMessage(500);
                if (m == null || !gTopic.equals(m.topic)) continue;
                JSONObject j = safeJson(m.payload);
                if (j != null && "relay_req".equals(j.optString("type"))) {
                    JSONObject ack = new JSONObject();
                    try {
                        ack.put("type", "relay_ack");
                        ack.put("name", playerName);
                        ack.put("ts", System.currentTimeMillis());
                    } catch (Exception ignored) {
                    }
                    ch.publish(gTopic, ack.toString().getBytes(StandardCharsets.UTF_8), 1);
                    log("[中继] ✅ 中继通道建立（会话「" + gName + "」）");
                    establish(gid, new Transports.RelayTransport(ch, gData, "H",
                            gName, sessionHook(gs), crypto));
                    return;
                }
            }
            if (gs.transport != null) return; // TCP 打洞线程异步建立成功（同时打开/普通 connect 晚到）
            log("[会话] 「" + gName + "」加入失败（打洞与中继均未成功），会话关闭");
        } catch (Throwable t) {
            log("[会话] 会话异常: " + t.getMessage());
        } finally {
            gs.sessionDone = true; // 会话线程结束：主循环接管后续 relay_req（断线降级恢复）
            if (punchSs != null) {
                try { punchSs.close(); } catch (Throwable ignored) {}
            }
            if (gs.transport == null) {
                // 会话彻底失败：清理会话登记与资源
                guests.remove(gid);
                if (sock != null) {
                    try { sock.close(); } catch (Throwable ignored) {}
                }
                ch.close();
            } else if (gs.transport instanceof Transports.UdpTransport) {
                // UDP 传输持有打洞 socket（不能关）；信令通道不再需要
                ch.close();
            } else if (gs.transport instanceof Transports.RelayTransport) {
                // 中继传输持有信令通道（不能关）；打洞 socket 不再需要
                if (sock != null) {
                    try { sock.close(); } catch (Throwable ignored) {}
                }
            } else {
                // TCP 直连：sock 与信令通道都不再需要
                if (sock != null) {
                    try { sock.close(); } catch (Throwable ignored) {}
                }
                ch.close();
            }
        }
    }

    /** 打洞 TCP 监听接受线程（host 端）：客人普通 connect 打洞监听端口 → 标准握手 → 登记建立。
     * 与 punchAttempt 的"同时打开"并行竞争，establish 的 putIfAbsent 保证只保留一条连接。 */
    private static void handlePunchAccept(ServerSocket ss, GuestSession gs) {
        try {
            while (running && gs.transport == null) {
                Socket s = ss.accept();
                if (!running) {
                    try { s.close(); } catch (Throwable ignored) {}
                    break;
                }
                HostHandshake hs = handshakeAsHost(s, s.getInetAddress().getHostAddress(), true);
                if (hs != null && establish(gs.gid, hs.t)) {
                    log("[打洞] ✅ 打洞监听通道建立（客人普通 connect 连入）");
                    fireMembers();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 打洞 TCP 监听接受线程（guest 端，与 host 对称）：房主普通 connect / IPv6 直连到达
     * → 标准握手 → 登记建立。guest 是 accept 方（服务端角色），握手用 handshakeAsHost；
     * 与 punchAttempt 的 TCP 主动连接并行竞争，establish 的 putIfAbsent 保证只保留一条。 */
    private static void handlePunchAcceptGuest(ServerSocket ss) {
        try {
            while (running && transports.isEmpty()) {
                Socket s = ss.accept();
                if (!running) {
                    try { s.close(); } catch (Throwable ignored) {}
                    break;
                }
                HostHandshake hs = handshakeAsHost(s, s.getInetAddress().getHostAddress(), true);
                if (hs != null && establish(PEER_HOST, hs.t)) {
                    log("[打洞] ✅ 房主直连通道建立（打洞监听 accept，"
                            + s.getInetAddress().getHostAddress() + "）");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 房主侧中继恢复（打洞失败兜底 / 客人断线降级）：独立信令通道建立 RelayTransport */
    private static void handleRelayReq(String gid, JSONObject j) {
        String gName = sanitizeName(j.optString("name", "好友"));
        GuestSession gs = guests.get(gid);
        if (gs == null) {
            gs = new GuestSession(gid, gName);
            guests.put(gid, gs);
        }
        // 与 handleGuestSession 等待循环可能同时收到 relay_req：加锁防重复建中继（浪费通道/误关）
        synchronized (gs) {
            if (gs.transport != null) return;
            // ⚠ 必须与主循环同一 broker（曾用 SigChannel(4) 错峰连 mosquitto → relay_ack
            // 发到 mosquitto、guest 在 emqx 收不到 → 断线降级中继永远建立不了）
            SigChannel rc = new SigChannel(hostBrokerIndex);
            try {
                if (!rc.connect()) {
                    log("[中继] 信令不可达，会话「" + gName + "」中继恢复失败");
                    return;
                }
                rc.subscribe(topicGid(gid));
                JSONObject ack = new JSONObject();
                try {
                    ack.put("type", "relay_ack");
                    ack.put("name", playerName);
                    ack.put("ts", System.currentTimeMillis());
                } catch (Exception ignored) {
                }
                rc.publish(topicGid(gid), ack.toString().getBytes(StandardCharsets.UTF_8), 1);
                log("[中继] ✅ 中继通道恢复（会话「" + gName + "」）");
                establish(gid, new Transports.RelayTransport(rc, topicD(gid), "H",
                        gName, sessionHook(gs), crypto));
            } catch (Throwable t) {
                log("[中继] 中继恢复异常: " + t.getMessage());
                rc.close();
            }
        }
    }

    /** 会话级 Hook（闭包带来源会话：host 消息转发跳过来源，防回声） */
    private static Transports.Hook sessionHook(GuestSession gs) {
        return new Transports.Hook() {
            @Override
            public void onMessage(String line) {
                deliverMessage(line, gs);
            }

            @Override
            public void onLost(Transports.Transport t) {
                onTransportLost(t);
            }

            @Override
            public void onDowngrade() {
                // 直连降级：断开本会话连接，客人侧会发 relay_req 走中继恢复（主循环处理）
                Transports.Transport cur = gs.transport;
                if (cur != null) onTransportLost(cur);
            }

            @Override
            public void onLog(String s) {
                log(s);
            }
        };
    }

    /** 成员变化（加入/离开）时刷新房主侧状态与成员栏 */
    private static void fireMembers() {
        if (role == ROLE_HOST && running) {
            fire(TerracottaState.of(TerracottaState.Kind.HOST_OK, roomCode, null,
                    getMembersText()));
        }
    }

    // ── 跨网：客人流 ──

    private static void guestWanFlow() {
        String gid = newGuestId(); // 本会话 ID：专属信令/数据 topic，多人房间互不干扰
        guestGid = gid; // 断线中继降级沿用同 gid，host 端话题一致
        SigChannel ch = new SigChannel();
        sig = ch;
        DatagramSocket sock = null;
        ServerSocket gPunchSs = null;
        try {
            sock = new DatagramSocket(0);
            punchSock = sock;
            stunResult = StunProbe.query(sock, STUN_TIMEOUT_MS);
            if (stunResult != null) {
                log("[STUN] 公网映射 " + stunResult);
            } else {
                log("[STUN] 公网地址探测失败（打洞不可用，依赖中继）");
            }
            if (!ch.connect()) {
                log("[信令] 公共信令服务器全部不可达，异地组网不可用");
                return;
            }
            log("[信令] 已连接公共信令（" + ch.brokerName() + "）");
            // 记录实际 broker：断线降级（guestRelayFlow）沿用同一 broker 重发 relay_req——
            // host 主循环不换 broker，降级通道从 0 重新轮询会连到别的 broker 收不到 relay_ack
            guestBrokerIndex = ch.currentIndex();
            // FCL 式映射保活（多源：国内源优先，防境外 STUN 不通导致映射回收）
            final DatagramSocket kaSock = sock;
            Thread keepAlive = new Thread(() -> {
                try {
                    while (running && transports.isEmpty() && !kaSock.isClosed()) {
                        StunProbe.sendKeepaliveAll(kaSock);
                        Thread.sleep(5000);
                    }
                } catch (Throwable ignored) {
                }
            }, "HyVqP2p-StunKeepalive");
            keepAlive.setDaemon(true);
            keepAlive.start();
            String gTopic = topicGid(gid);
            String gData = topicD(gid);
            ch.subscribe(gTopic);
            int tcpPort = randomTcpPort();
            // 打洞 TCP 监听（与 host 对称）：接受房主普通 connect / IPv6 直连 / 同时打开。
            // CGNAT 下 IPv4 打洞物理受限（hairpin 不支持），IPv6 无 NAT 是唯一可靠直连
            // 路径——必须双端监听，谁先 connect 谁赢（本端 accept 即按服务端角色握手）。
            try {
                gPunchSs = new ServerSocket(tcpPort);
                gPunchSs.setReuseAddress(true);
                final ServerSocket fSs = gPunchSs;
                Thread gpa = new Thread(() -> handlePunchAcceptGuest(fSs),
                        "HyVqP2p-GuestPunchAccept");
                gpa.setDaemon(true);
                gpa.start();
                log("[打洞] 打洞监听端口已就绪（" + tcpPort + "），等待房主直连…");
            } catch (Throwable t) {
                log("[打洞] 打洞监听端口绑定失败（" + tcpPort + "），仅依赖主动连接: "
                        + t.getMessage());
            }
            long deadline = System.currentTimeMillis() + WAN_TOTAL_TIMEOUT_MS;
            long nextHello = 0;
            int rounds = 0;
            boolean punched = false; // 打洞已失败：进入持续中继阶段（由预热线程重发直至超时）
            java.util.concurrent.atomic.AtomicBoolean relayPreheated =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            JSONObject req = null;
            String hName = "房主"; // addr 解析出的房主名（relay_ack 分支复用）
            while (running && transports.isEmpty() && System.currentTimeMillis() < deadline) {
                if (System.currentTimeMillis() >= nextHello) {
                    JSONObject hello = new JSONObject();
                    try {
                        hello.put("type", "hello");
                        hello.put("gid", gid);
                        hello.put("name", playerName);
                        if (stunResult != null) {
                            hello.put("ip", stunResult.publicIp);
                            hello.put("port", stunResult.publicPort);
                        }
                        hello.put("tcpPort", tcpPort);
                        hello.put("lanIp", localLanIp());
                        hello.put("lanPort", tcpPort); // 打洞端口（host 端构造内网 TCP 候选）
                        hello.put("udpPort", sock.getLocalPort()); // UDP 打洞内网候选端口
                        hello.put("ipv6", localIpv6());
                        hello.put("ts", System.currentTimeMillis());
                    } catch (Exception ignored) {}
                    ch.publish(gTopic, hello.toString().getBytes(StandardCharsets.UTF_8), 1);
                    nextHello = System.currentTimeMillis() + 2000;
                }
                SigChannel.Msg m = ch.pollMessage(500);
                if (m != null && gTopic.equals(m.topic)) {
                    JSONObject j = safeJson(m.payload);
                    if (j != null) {
                        String type = j.optString("type");
                        if ("addr".equals(type) && !punched && rounds < 3) {
                            rounds++;
                            String hIp = j.optString("ip");
                            int hPort = j.optInt("port", 0);
                            int hTcp = j.optInt("tcpPort", 0);
                            String hLanIp = j.optString("lanIp", "");
                            int hLanPort = j.optInt("lanPort", 0);
                            int hUdpPort = j.optInt("udpPort", 0);
                            String hIpv6 = j.optString("ipv6", "");
                            hName = j.optString("name", "房主");
                            // 同网优先直连（RFC 5128 标准实践）：同一 NAT/局域网后的设备
                            // 应直连内网地址，避免走不稳定/常被禁用的 NAT hairpin 回环打洞
                            if (!hLanIp.isEmpty() && hLanPort > 0 && isSameLan(hLanIp)) {
                                log("[连接] 检测到与房主同网（" + hLanIp + ":" + hLanPort
                                        + "），优先局域网直连…");
                                Socket ls = lanConnect(hLanIp, hLanPort, LAN_CONNECT_TIMEOUT_MS);
                                if (ls != null) {
                                    Transports.TcpTransport lt = handshakeAsGuest(
                                            ls, hLanIp + ":" + hLanPort, false);
                                    if (lt != null && establish(PEER_HOST, lt)) {
                                        log("[房间] ✅ 已加入房间（同网局域网直连，稳定可靠）");
                                        return;
                                    }
                                }
                                log("[连接] 局域网直连未成功，回退跨网打洞…");
                            }
                            log("[信令] 房主「" + hName + "」已确认，开始打洞…");
                            InetSocketAddress peerUdp = null;
                            if (stunResult != null && !hIp.isEmpty() && hPort > 0) {
                                try {
                                    peerUdp = new InetSocketAddress(InetAddress.getByName(hIp), hPort);
                                } catch (Exception ignored) {}
                            }
                            InetSocketAddress peerLan = null;
                            if (!hLanIp.isEmpty() && hLanPort > 0) {
                                try {
                                    peerLan = new InetSocketAddress(InetAddress.getByName(hLanIp), hLanPort);
                                } catch (Exception ignored) {}
                            }
                            InetSocketAddress peerLanUdp = null;
                            if (!hLanIp.isEmpty() && hUdpPort > 0) {
                                try {
                                    peerLanUdp = new InetSocketAddress(InetAddress.getByName(hLanIp), hUdpPort);
                                } catch (Exception ignored) {}
                            }
                            // 中继预热（消除窗口错位的关键）：不等打洞失败——收到 addr 即并行
                            // 周期重发 relay_req（独立线程）。host 会话线程的 pollMessage 队列会
                            // 缓存请求，其 punchAttempt 一结束立即取到建中继；若打洞成功则
                            // establish 已生效、transports 非空，预热线程自动退出。彻底解决
                            // 「客人 15s 后才发、房主 15s 窗口已过」的错位。
                            if (req == null) {
                                req = new JSONObject();
                                try {
                                    req.put("type", "relay_req");
                                    req.put("gid", gid);
                                    req.put("name", playerName);
                                    req.put("ts", System.currentTimeMillis());
                                } catch (Exception ignored) {}
                            }
                            final JSONObject fReq = req;
                            if (!relayPreheated.getAndSet(true)) {
                                Thread rw = new Thread(() -> {
                                    try {
                                        while (running && transports.isEmpty()
                                                && System.currentTimeMillis() < deadline) {
                                            ch.publish(gTopic, fReq.toString()
                                                    .getBytes(StandardCharsets.UTF_8), 1);
                                            Thread.sleep(2000);
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                }, "HyVqP2p-RelayPreheat");
                                rw.setDaemon(true);
                                rw.start();
                                log("[中继] 中继预热已启动（每 2s 重发，直至直连成功或超时）");
                            }
                            // 同 CGNAT 出口检测：对端公网 IP 与本机相同且不同网 → hairpin
                            // 大多不支持，IPv4 打洞物理失败，快速失败把时间让给中继
                            boolean sameNat = stunResult != null && !hIp.isEmpty()
                                    && stunResult.publicIp.equals(hIp) && !isSameLan(hLanIp);
                            if (sameNat) {
                                log("[打洞] 检测到与房主同一运营商 NAT 出口（" + hIp
                                        + "），快速打洞后转中继…");
                            }
                            if (punchAttempt(ch, sock, PEER_HOST, tcpPort, hIpv6, hTcp,
                                    peerUdp, peerLan, peerLanUdp, hName, HOOK, sameNat)) {
                                return;
                            }
                            // 打洞失败：进入持续中继阶段（预热线程继续重发，不重复启线程）
                            punched = true;
                        } else if ("relay_ack".equals(type)) {
                            log("[中继] ✅ 中继通道建立（公共 broker 转发）");
                            establish(PEER_HOST, new Transports.RelayTransport(ch, gData, "G",
                                    hName, HOOK, crypto));
                            return;
                        }
                    }
                }
                // 中继预热线程持续重发中（transports 非空时自动退出）
            }
            if (!transports.isEmpty()) return; // TCP 打洞线程异步建立成功（同时打开/普通 connect 晚到）
            if (punched) log("[中继] ❌ 中继请求无响应（已持续重发至超时）");
        } catch (Throwable t) {
            log("[组网] 跨网通道异常: " + t.getMessage());
        } finally {
            if (gPunchSs != null) {
                try { gPunchSs.close(); } catch (Throwable ignored) {}
            }
            finishWanFlow(ch, sock, true);
        }
    }

    /** 跨网流程收尾：按最终传输类型决定资源去留；客人全部失败时给出异常状态。
     * 房主主循环（ch 仅信令）与客人流共用。 */
    private static void finishWanFlow(SigChannel ch, DatagramSocket sock, boolean isGuest) {
        if (!transports.isEmpty()) {
            // 已有连接：非 UDP/中继类型则关闭打洞 socket 与信令通道
            boolean hasUdp = false;
            boolean hasRelay = false;
            for (Transports.Transport t : transports.values()) {
                if (t instanceof Transports.UdpTransport) hasUdp = true;
                if (t instanceof Transports.RelayTransport) hasRelay = true;
            }
            if (!hasUdp && sock != null) {
                try { sock.close(); } catch (Throwable ignored) {}
                if (punchSock == sock) punchSock = null;
            }
            // 信令通道只在无中继时关闭（中继由 RelayTransport 独立通道管理）
            if (!hasRelay) {
                ch.close();
                if (sig == ch) sig = null;
            }
        } else {
            if (sock != null) {
                try { sock.close(); } catch (Throwable ignored) {}
                if (punchSock == sock) punchSock = null;
            }
            ch.close();
            if (sig == ch) sig = null;
            if (running) {
                if (isGuest) {
                    fire(TerracottaState.of(TerracottaState.Kind.EXCEPTION, roomCode, null,
                            "异地组网失败：打洞与中继均未成功，请检查双方网络后重试"));
                } else {
                    log("[组网] 跨网等待超时（仍在局域网内等待好友加入）");
                }
            }
        }
    }

    // ── 打洞 ──

    /** 一次完整跨网连接尝试（多人版·增强）：①UDP 打洞（公网映射+内网 udpPort 双路并行）
     * → ②TCP 路径（客人先普通 connect 对方打洞监听端口——IPv6/公网/内网多地址并行，
     * 再 TCP 同时打开偏移 1..3）；任一成功即 establish(key, …)。
     * 真机日志驱动修复：guest hello 的 lanPort 曾写死 0，导致 host 端 TCP 候选仅剩 1 个
     * 地址且无人监听；现双方公告打洞监听端口（lanPort=tcpPort）与 UDP 内网端口（udpPort），
     * host 端开启打洞监听 accept——客人普通 connect 即达，不依赖时序敏感的"同时打开"。 */
    private static boolean punchAttempt(SigChannel ch, DatagramSocket sock, String key,
                                        int myTcpPort, String peerIpv6, int peerTcpPort,
                                        InetSocketAddress peerUdp, InetSocketAddress peerLan,
                                        InetSocketAddress peerLanUdp,
                                        String peerName, Transports.Hook hook,
                                        boolean fastFail) {
        if (transports.containsKey(key) || !running) return false;
        // UDP 候选：公网 STUN 映射 + 内网 udpPort（同一 NAT/运营商网络下内网路常通）
        final java.util.List<InetSocketAddress> udpPeers = new java.util.ArrayList<>();
        if (peerUdp != null) udpPeers.add(peerUdp);
        if (peerLanUdp != null && !samePeer(peerUdp, peerLanUdp)) udpPeers.add(peerLanUdp);
        // TCP 候选地址集：IPv6（优先）+ 公网映射 + 内网 lanIp
        final java.util.List<String> tcpIps = new java.util.ArrayList<>();
        if (peerIpv6 != null && !peerIpv6.isEmpty()) tcpIps.add(peerIpv6);
        if (peerUdp != null) tcpIps.add(peerUdp.getAddress().getHostAddress());
        if (peerLan != null) {
            String lanIp = peerLan.getAddress().getHostAddress();
            if (!tcpIps.contains(lanIp)) tcpIps.add(lanIp);
        }
        // 后台并行 TCP 线程（与 UDP 打洞同时进行，先到先得；TCP 线程是 tcpWinner 唯一消费者，
        // 自主完成握手，消除主/子线程重复握手竞态）：
        // 阶段1：客人普通 connect 对方打洞监听端口（IPv6/公网/内网并行，任一连通即握手建立）；
        //        host 侧等打洞监听 accept 线程建立（sleep 窗口内检查，避免与同时打开重复）。
        // 阶段2：TCP 同时打开（端口偏移 1..3，避开已绑定的打洞监听端口）。
        final java.util.concurrent.atomic.AtomicReference<Socket> tcpWinner =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean tcpClientRole =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread tcpThread = null;
        if (peerTcpPort > 0 && !tcpIps.isEmpty()) {
            final int myPort = myTcpPort;
            final int peerPort = peerTcpPort;
            tcpThread = new Thread(() -> {
                if (role == ROLE_GUEST) {
                    Socket s = plainConnectAny(tcpIps, peerPort, 2500);
                    if (s != null) {
                        // 普通 connect 到 host 打洞监听：我方客户端角色发 HELLO
                        if (tcpWinner.compareAndSet(null, s)) finishTcp(s, key, true);
                        else try { s.close(); } catch (Throwable ignored) {}
                        return;
                    }
                } else {
                    // host：给客人普通 connect 一点时间（accept 线程会建立），避免重复连接
                    try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
                    if (transports.containsKey(key)) return;
                }
                Socket ts = tcpSimultaneousOpen(myPort, tcpIps, peerPort, key, tcpClientRole,
                        fastFail);
                if (ts != null) {
                    if (tcpWinner.compareAndSet(null, ts)) finishTcp(ts, key, tcpClientRole.get());
                    else try { ts.close(); } catch (Throwable ignored) {}
                }
            }, "HyVqP2p-TcpPunch");
            tcpThread.setDaemon(true);
            tcpThread.start();
        }
        // ① UDP 打洞（多地址并行：任一地址双向打通即成功）
        if (sock != null && !udpPeers.isEmpty()) {
            final java.util.concurrent.atomic.AtomicReference<InetSocketAddress> udpWinner =
                    new java.util.concurrent.atomic.AtomicReference<>();
            if (udpPunch(sock, udpPeers, peerName, key, udpWinner, fastFail)) {
                // UDP 先成功：关掉 TCP 残留（若有），用实际打通的地址建立 UDP 传输
                Socket leftover = tcpWinner.get();
                if (leftover != null) try { leftover.close(); } catch (Throwable ignored) {}
                InetSocketAddress peer = udpWinner.get();
                if (peer != null) {
                    establish(key, new Transports.UdpTransport(sock, peer,
                            peer.getAddress().getHostAddress() + ":" + peer.getPort(),
                            hook, crypto));
                    return transports.containsKey(key);
                }
            }
        }
        // ② 等并行 TCP 线程收尾（TCP 线程内已自主完成握手建立；此处只检查结果）
        if (tcpThread != null) {
            try { tcpThread.join(3000); } catch (InterruptedException ignored) {}
            return transports.containsKey(key);
        }
        return false;
    }

    /** TCP 打洞成功连接的收尾：按成功路径决定握手角色并登记建立（tcpWinner 消费者调用）。
     * @param asClient true=我方是普通 connect 方（对端 accept 服务端等 HELLO，我方发 HELLO）；
     *                 false=同时打开成功（按既有角色分工：host 服务端等 HELLO / guest 客户端发 HELLO）。 */
    private static boolean finishTcp(Socket s, String key, boolean asClient) {
        Transports.Transport t;
        if (role == ROLE_HOST && !asClient) {
            // 同时打开：guest 发 HELLO，host 等 HELLO（服务端角色）
            HostHandshake hs = handshakeAsHost(s, String.valueOf(s.getRemoteSocketAddress()), true);
            t = hs != null ? hs.t : null;
        } else {
            // 普通 connect（host 连 guest 打洞监听 / guest 连 host 打洞监听 / guest 同时打开）：
            // 我方一律客户端角色发 HELLO——对端 accept 时是服务端角色（等 HELLO），
            // 若我方也等 HELLO 则双方互等死锁（8s 双双失败，真机「IPv6 直连尝试…→失败」之一）
            t = handshakeAsGuest(s, String.valueOf(s.getRemoteSocketAddress()), true);
        }
        if (t != null && establish(key, t)) return true;
        try { s.close(); } catch (Throwable ignored) {}
        return false;
    }

    /** 普通 TCP connect（对方打洞监听端口）：多 IP（IPv6/公网/内网）并行，任一成功即返回 */
    private static Socket plainConnectAny(java.util.List<String> ips, int port, int windowMs) {
        final java.util.concurrent.atomic.AtomicReference<Socket> winner =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.List<Thread> ws = new java.util.ArrayList<>();
        for (String ip : ips) {
            final String fIp = ip;
            Thread w = new Thread(() -> {
                if (winner.get() != null) return;
                Socket s = tryPlainConnect(fIp, port, windowMs);
                if (s == null) return;
                if (!winner.compareAndSet(null, s)) {
                    try { s.close(); } catch (Throwable ignored) {}
                }
            }, "HyVqP2p-Plain" + Math.abs(fIp.hashCode()));
            w.setDaemon(true);
            w.start();
            ws.add(w);
        }
        for (Thread w : ws) {
            try { w.join(windowMs); } catch (InterruptedException ignored) { break; }
        }
        return winner.get();
    }

    /** 单次普通 TCP connect（不 bind 固定端口，出站随机端口即可；目标需有监听） */
    private static Socket tryPlainConnect(String ip, int port, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return s;
        } catch (Throwable t) {
            try { s.close(); } catch (Throwable ignored) {}
            return null;
        }
    }

    /** UDP 打洞（多人版·多地址并行）：向全部候选地址（公网映射/内网 lanIp）循环发包，
     * 任一地址收到对方 punch 包即进入双向确认窗口；打通的地址经 winner 返回调用方建传输。
     * 相比单地址打洞：CGNAT 下公网映射常因 hairpin 不稳，内网 lanIp 路可直连成功。 */
    private static boolean udpPunch(DatagramSocket sock, java.util.List<InetSocketAddress> peers,
                                    String peerName, String key,
                                    java.util.concurrent.atomic.AtomicReference<InetSocketAddress> winner,
                                    boolean fastFail) {
        StringBuilder sb = new StringBuilder();
        for (InetSocketAddress p : peers) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.getAddress().getHostAddress()).append(':').append(p.getPort());
        }
        log("[打洞] 向「" + peerName + "」发起 UDP 打洞（" + peers.size() + " 路并行: " + sb
                + (fastFail ? "，同 NAT 快速模式" : "") + "）…");
        // 同 CGNAT 出口（hairpin 大多不支持）：缩短窗口快速失败，把时间让给中继
        long deadline = System.currentTimeMillis() + (fastFail ? SAME_NAT_PUNCH_MS : PUNCH_ATTEMPT_MS);
        Thread sender = new Thread(() -> {
            try {
                byte[] d = (PUNCH_PREFIX + "|" + roomCode + "|" + playerName)
                        .getBytes(StandardCharsets.UTF_8);
                while (running && !transports.containsKey(key)
                        && System.currentTimeMillis() < deadline) {
                    for (InetSocketAddress p : peers) {
                        sock.send(new DatagramPacket(d, d.length, p));
                    }
                    Thread.sleep(100);
                }
            } catch (Throwable ignored) {
            }
        }, "HyVqP2p-PunchTx");
        sender.setDaemon(true);
        sender.start();
        try {
            sock.setSoTimeout(300);
            byte[] buf = new byte[1024];
            while (running && !transports.containsKey(key)
                    && System.currentTimeMillis() < deadline) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (!matchesPeer(peers, p.getSocketAddress())) continue;
                String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                if (msg.startsWith(PUNCH_PREFIX)) {
                    // FCL 式双向确认：收到 punch 包后立即回发 E| 加密行，并每 500ms 续发——
                    // 对方可能稍晚进入确认窗口，必须持续应答；否则双方都在等对方先发，
                    // 死锁双双超时（实测表现：双方都收到 punch 包但都报"单向打通未获确认"）。
                    log("[打洞] ✅ 收到对方打洞包（" + p.getAddress().getHostAddress() + ":"
                            + p.getPort() + "），立即回发加密确认…");
                    long confirm = System.currentTimeMillis() + 3000;
                    long nextAck = 0;
                    while (running && !transports.containsKey(key)
                            && System.currentTimeMillis() < confirm) {
                        if (crypto != null && System.currentTimeMillis() >= nextAck) {
                            String enc = crypto.encrypt("PING");
                            if (enc != null) {
                                byte[] ack = ("E|" + enc).getBytes(StandardCharsets.UTF_8);
                                sock.send(new DatagramPacket(ack, ack.length, p.getSocketAddress()));
                            }
                            nextAck = System.currentTimeMillis() + 500;
                        }
                        DatagramPacket cp = new DatagramPacket(new byte[2048], 2048);
                        try {
                            sock.setSoTimeout(500);
                            sock.receive(cp);
                        } catch (SocketTimeoutException e) {
                            continue;
                        }
                        if (!matchesPeer(peers, cp.getSocketAddress())) continue;
                        String cm = new String(cp.getData(), 0, cp.getLength(), StandardCharsets.UTF_8);
                        if (cm.startsWith("E|")) {
                            log("[打洞] ✅ UDP 打洞双向确认成功（" + cp.getAddress().getHostAddress()
                                    + ":" + cp.getPort() + "）");
                            if (winner != null) winner.set((InetSocketAddress) cp.getSocketAddress());
                            return true;
                        }
                    }
                    log("[打洞] ⚠️ 单向打通未获对方确认，放弃 UDP 打洞");
                    return false;
                }
            }
        } catch (Throwable ignored) {
        }
        log("[打洞] ❌ UDP 打洞失败");
        return false;
    }

    /** TCP 同时打开（多人版）：①IPv6 快速窗口（无 NAT 几乎必成）→ ②全部候选地址（公网/
     * 内网/IPv6）× 3 端口偏移并行。多地址覆盖任一可达即通；端口偏移提高 NAT 端口预测
     * 命中率（运营商 NAT 常对连续端口做映射）；任一成功即返回。
     * @param clientRole 输出：成功路径的握手角色（true=普通 connect 成功→我方发 HELLO；
     *                   false=同时打开成功→按既有角色握手）。普通 connect 到对端打洞监听
     *                   端口时对端是 accept 服务端角色（等 HELLO），我方必须发 HELLO，
     *                   否则双方互等 HELLO 死锁（8s 双双失败）。 */
    private static Socket tcpSimultaneousOpen(int localPort, java.util.List<String> peerIps,
                                              int peerPort, String key,
                                              java.util.concurrent.atomic.AtomicBoolean clientRole,
                                              boolean fastFail) {
        // ① IPv6 快速窗口：无 NAT 且双端打洞监听已就绪（#7 对称监听）——普通 connect
        //    对端监听端口即成功，无需"同时打开"。⚠ 不能 bind 本地端口：打洞监听
        //    ServerSocket 已占用 localPort，重复 bind 必然 EADDRINUSE（真机日志
        //    「IPv6 快速直连尝试…→失败」的根因之一，与对端是否监听无关）。
        for (String ip : peerIps) {
            if (ip.indexOf(':') < 0) continue;
            log("[打洞] IPv6 快速直连尝试 " + ip + ":" + peerPort + " …");
            Socket s6 = tryPlainConnect(ip, peerPort, (int) IPV6_ATTEMPT_MS);
            if (s6 != null) {
                log("[打洞] ✅ IPv6 直连成功（" + ip + ":" + peerPort + "）");
                if (clientRole != null) clientRole.set(true); // 对端 accept：我方客户端角色
                return s6;
            }
            break; // 只试第一个 IPv6 候选
        }
        log("[打洞] UDP 未通，尝试 TCP 同时打开（本地 " + localPort + "，候选 "
                + peerIps.size() + " 个地址 ×3 路端口偏移并行: " + peerIps
                + (fastFail ? "，同 NAT 快速模式" : "") + "）…");
        // 同 CGNAT 出口：缩短窗口快速失败，把时间让给中继
        long deadline = System.currentTimeMillis()
                + (fastFail ? SAME_NAT_PUNCH_MS : TCP_SIMUL_ATTEMPT_MS);
        int round = 0;
        while (running && !transports.containsKey(key)
                && System.currentTimeMillis() < deadline && round++ < 5) {
            final java.util.concurrent.atomic.AtomicReference<Socket> winner =
                    new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.List<Thread> workers = new java.util.ArrayList<>();
            // 偏移 1..3：避开已绑定的打洞监听端口（myTcp），同时保留 NAT 端口预测增量覆盖
            for (int off = 1; off <= 3; off++) {
                final int o = off;
                for (String ip : peerIps) {
                    final String fIp = ip;
                    Thread w = new Thread(() -> {
                        if (winner.get() != null) return;
                        Socket s = tryTcpConnect(localPort + o, fIp, peerPort + o, 1800);
                        if (s == null) return;
                        if (winner.compareAndSet(null, s)) {
                            // 赢家保留；失败路径不在此处关闭（由调用方处理）
                        } else {
                            try { s.close(); } catch (Throwable ignored) {}
                        }
                    }, "HyVqP2p-TcpSim" + o + "-" + fIp.hashCode());
                    w.setDaemon(true);
                    w.start();
                    workers.add(w);
                }
            }
            for (Thread w : workers) {
                try { w.join(2500); } catch (InterruptedException e) { break; }
            }
            Socket w = winner.get();
            if (w != null) {
                log("[打洞] ✅ TCP 同时打开成功（第 " + round + " 轮，端口偏移 "
                        + (w.getLocalPort() - localPort) + "）");
                // 同时打开：对端也是 connect 客户端角色（guest 发 HELLO / host 等 HELLO），
                // 按既有角色分工握手（host=服务端等 HELLO，guest=客户端发 HELLO）
                if (clientRole != null) clientRole.set(role == ROLE_GUEST);
                return w;
            }
        }
        log("[打洞] ❌ TCP 同时打开失败");
        return null;
    }

    /** 单次 TCP 打洞尝试：绑本地端口后 connect 对端（短超时）；IPv6 目标绑 IPv6 通配端口 */
    private static Socket tryTcpConnect(int localPort, String ip, int port, int timeoutMs) {
        Socket s = null;
        try {
            s = new Socket();
            s.setReuseAddress(true);
            boolean v6 = ip != null && ip.indexOf(':') >= 0;
            InetSocketAddress bindAddr = v6
                    ? new InetSocketAddress(InetAddress.getByName("::"), localPort)
                    : new InetSocketAddress(localPort);
            s.bind(bindAddr);
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return s;
        } catch (Throwable t) {
            try { if (s != null) s.close(); } catch (Throwable ignored) {}
            return null;
        }
    }

    // ── 信令与状态 ──

    private static void publishAnnounce(SigChannel ch, String hostTopic, int tcpPort) {
        try {
            JSONObject ann = new JSONObject();
            ann.put("type", "host");
            ann.put("name", playerName);
            if (stunResult != null) {
                ann.put("ip", stunResult.publicIp);
                ann.put("port", stunResult.publicPort);
            }
            ann.put("tcpPort", tcpPort);
            ann.put("lanIp", localLanIp());
            ann.put("lanPort", server != null ? server.getLocalPort() : 0);
            ann.put("ts", System.currentTimeMillis());
            ch.publish(hostTopic, ann.toString().getBytes(StandardCharsets.UTF_8), 1);
            if (!announceLogged) {
                announceLogged = true;
                log("[信令] 已发布跨网房间公告，等待异地好友…");
            }
        } catch (Exception ignored) {
        }
    }

    /** 传输注册（多人版）：key=guestId（host 端）/ "host"（guest 端），每连接独立生效；
     * 同一 key 已有连接则关闭新传输（防重复注册）。 */
    private static synchronized boolean establish(String key, Transports.Transport t) {
        if (!running || key == null) {
            t.close();
            return false;
        }
        Transports.Transport old = transports.putIfAbsent(key, t);
        if (old != null) {
            t.close();
            return false;
        }
        String name = t.name();
        log("[组网] ✅ " + name + " 建立成功（" + t.peerLabel() + "）");
        if (role == ROLE_HOST) {
            GuestSession gs = guests.get(key);
            if (gs != null) gs.transport = t;
            fireMembers();
        } else {
            fire(TerracottaState.of(TerracottaState.Kind.GUEST_OK, roomCode, t.peerLabel(),
                    "🚀 传输方式：" + name));
        }
        return true;
    }

    /** 传输断开（多人版）：房主移除该成员会话（房间/主信令循环保留，客人可重连或中继恢复）；
     * 客人自动降级 MQTT 中继兜底（不回等待页） */
    private static void onTransportLost(Transports.Transport t) {
        String key = null;
        for (java.util.Map.Entry<String, Transports.Transport> e : transports.entrySet()) {
            if (e.getValue() == t) {
                key = e.getKey();
                break;
            }
        }
        if (key == null) return;
        transports.remove(key);
        if (role == ROLE_HOST) {
            GuestSession gs = guests.get(key);
            if (gs != null) {
                gs.transport = null; // 会话线程 finally 会收尾 sock/ch；客人可发 relay_req 中继恢复
                log("[连接] 成员「" + gs.name + "」已断开（房间保留，可重新加入）");
            }
            fireMembers();
        } else if (running && role == ROLE_GUEST && roomCode != null) {
            // 客人：数据通道中断（如 CGNAT 映射漂移）不直接回等待页——
            // 自动切换 MQTT 中继兜底，保持会话持续。
            log("[连接] 与房主的连接已断开，自动切换 MQTT 中继兜底…");
            // UI 反馈：切到「降级中」进度视图（GUEST_CONNECTING），避免用户以为卡死
            // 手动点返回——此前真机「中继降级 1s 失败」就是用户看到断开日志后立刻
            // backToWaiting → cleanup 置 running=false → 降级线程循环立即退出
            fire(TerracottaState.of(TerracottaState.Kind.GUEST_CONNECTING, roomCode, null,
                    "直连中断，正在自动切换中继兜底…"));
            punchSock = null;
            wanThread = new Thread(HyVqP2pBridge::guestRelayFlow, "HyVqP2p-GuestRelay");
            wanThread.setDaemon(true);
            wanThread.start();
        } else {
            cleanup();
            fire(TerracottaState.of(TerracottaState.Kind.WAITING, null, null));
        }
    }

    /** 客人端断线自动降级：gid 专属 topic 直接走 MQTT 中继（不再 STUN/打洞），保持会话不中断 */
    private static void guestRelayFlow() {
        final String gid = guestGid != null ? guestGid : newGuestId();
        guestGid = gid;
        // ⚠ 必须与 host 主循环同一 broker：host 端 handleRelayReq 用 hostBrokerIndex，
        // guest 端沿用首次连接成功的 broker（=host 的 broker，能收到 addr 即证明同 broker）。
        // 从 0 重新轮询在 host 非 emqx broker 时会连到别的 broker → relay_ack 收不到 → 降级失败
        SigChannel ch = new SigChannel(guestBrokerIndex);
        sig = ch;
        try {
            if (!ch.connect()) {
                log("[中继] 公共信令服务器不可达，自动降级失败");
                fire(TerracottaState.of(TerracottaState.Kind.EXCEPTION, roomCode, null,
                        "异地连接中断，且中继兜底不可达，请返回重试"));
                return;
            }
            log("[信令] 已连接公共信令（" + ch.brokerName() + "），请求中继通道…");
            String gTopic = topicGid(gid);
            String dataTopic = topicD(gid);
            ch.subscribe(gTopic);
            JSONObject req = new JSONObject();
            try {
                req.put("type", "relay_req");
                req.put("gid", gid);
                req.put("name", playerName);
                req.put("ts", System.currentTimeMillis());
            } catch (Exception ignored) {}
            // 周期重发（与预热线程一致）：单发一次 + 等 8s 太脆弱——host 主循环
            // pollMessage 800ms + handleRelayReq connect（最坏轮询 6 broker 数十秒），
            // 加上 broker 投递延迟；每 2s 重发直到 30s 窗口结束，容错大幅提高
            final JSONObject fReq = req;
            long dl = System.currentTimeMillis() + 30000;
            long nextReq = 0;
            while (running && !transports.containsKey(PEER_HOST)
                    && System.currentTimeMillis() < dl) {
                if (System.currentTimeMillis() >= nextReq) {
                    ch.publish(gTopic, fReq.toString().getBytes(StandardCharsets.UTF_8), 1);
                    nextReq = System.currentTimeMillis() + 2000;
                }
                SigChannel.Msg m = ch.pollMessage(500);
                if (m == null || !gTopic.equals(m.topic)) continue;
                JSONObject j = safeJson(m.payload);
                if (j != null && "relay_ack".equals(j.optString("type"))) {
                    log("[中继] ✅ 中继通道建立（打洞断开自动兜底）");
                    establish(PEER_HOST, new Transports.RelayTransport(ch, dataTopic, "G",
                            "房主", HOOK, crypto));
                    return;
                }
            }
            log("[中继] ❌ 中继请求无响应，自动降级失败");
            fire(TerracottaState.of(TerracottaState.Kind.EXCEPTION, roomCode, null,
                    "异地连接中断，中继兜底失败，请返回重试"));
        } catch (Throwable t) {
            log("[组网] 中继降级异常: " + t.getMessage());
            fire(TerracottaState.of(TerracottaState.Kind.EXCEPTION, roomCode, null,
                    "异地连接中断，中继兜底异常，请返回重试"));
        } finally {
            if (!(transports.get(PEER_HOST) instanceof Transports.RelayTransport)) {
                ch.close();
                if (sig == ch) sig = null;
            }
        }
    }

    /** 对端请求一起降级中继（guest 端触发；host 端每会话 hook 已按会话处理，不走这里）：
     * 关闭当前直连通道，切换 MQTT 中继兜底，保持会话 */
    private static void handleDowngrade() {
        Transports.Transport t = transports.get(PEER_HOST);
        if (t == null) return;
        transports.remove(PEER_HOST);
        t.close();
        log("[连接] 收到降级请求，切换 MQTT 中继兜底…");
        if (running && role == ROLE_GUEST && roomCode != null) {
            guestRelayFlow();
        }
    }

    private static void deliverMessage(String line) {
        String show = line.length() > 120 ? line.substring(0, 120) + "…" : line;
        log("[消息] 收到: " + show);
        main.post(() -> {
            StateListener l = listener;
            if (l != null) l.onMessage(line);
        });
    }

    /** host 端收到客人消息（带来源会话）：转发给其他客人（跳过来源防回声），并显示给房主 */
    private static void deliverMessage(String line, GuestSession gs) {
        String show = line.length() > 120 ? line.substring(0, 120) + "…" : line;
        log("[消息] 来自「" + gs.name + "」: " + show);
        // 帧已带昵称（M|昵称|内容）原样转发；旧格式补昵称后转发
        final String frame = line.startsWith(MSG_PREFIX)
                ? line : MSG_PREFIX + sanitizeName(gs.name) + "|" + line;
        ensureSendThread();
        sendQueue.offer(new SendItem(frame, gs.gid));
        main.post(() -> {
            StateListener l = listener;
            if (l != null) l.onMessage(frame);
        });
    }

    // ── 工具 ──

    private static String topicH() { return "hyvq/r/" + roomCode + "/h"; }
    /** 房主订阅通配符：hyvq/r/{房间}/+/g（MQTT + 匹配一层 gid，收全部客人的信令消息） */
    private static String topicGP() { return "hyvq/r/" + roomCode + "/+/g"; }
    /** 客人专属信令 topic：hyvq/r/{房间}/{gid}/g（多人房间互不干扰） */
    private static String topicGid(String gid) { return "hyvq/r/" + roomCode + "/" + gid + "/g"; }
    /** 客人专属数据 topic：hyvq/r/{房间}/{gid}/d */
    private static String topicD(String gid) { return "hyvq/r/" + roomCode + "/" + gid + "/d"; }

    /** 候选地址匹配（IP 字节 + 端口比较，规避 Inet6Address scope 差异导致的 equals 失败） */
    private static boolean matchesPeer(java.util.List<InetSocketAddress> peers,
                                       java.net.SocketAddress a) {
        if (!(a instanceof InetSocketAddress)) return false;
        InetSocketAddress b = (InetSocketAddress) a;
        for (InetSocketAddress p : peers) {
            if (samePeer(p, b)) return true;
        }
        return false;
    }

    private static boolean samePeer(InetSocketAddress a, InetSocketAddress b) {
        if (a == null || b == null || a.getAddress() == null || b.getAddress() == null) return false;
        if (a.getPort() != b.getPort()) return false;
        return java.util.Arrays.equals(a.getAddress().getAddress(), b.getAddress().getAddress());
    }

    /** 昵称净化：过滤分隔符/换行（防 M| 帧注入），截断 20 字符 */
    private static String sanitizeName(String name) {
        if (name == null) return "好友";
        String n = name.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        if (n.isEmpty()) return "好友";
        return n.length() > 20 ? n.substring(0, 20) : n;
    }

    /** 客人会话 ID：6 位 hex（24bit 熵，同房间内碰撞概率可忽略） */
    private static String newGuestId() {
        return String.format(Locale.US, "%06x", GID_RAND.nextInt(0x1000000));
    }

    /** 本机全局 IPv6（无 NAT 直连路径）：排除回环/链路本地/未指定（链路本地跨设备
     * 需 %zone 且接口名不同不可用）；保留 ULA 与全局单播 */
    private static String localIpv6() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet6Address && !a.isLoopbackAddress()
                            && !a.isLinkLocalAddress() && !a.isAnyLocalAddress()) {
                        String ip = a.getHostAddress();
                        int pct = ip == null ? -1 : ip.indexOf('%');
                        return pct > 0 ? ip.substring(0, pct) : ip;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static JSONObject safeJson(byte[] payload) {
        try {
            return new JSONObject(new String(payload, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static int randomTcpPort() {
        return 40000 + new Random().nextInt(20000);
    }

    /** 房间码即密钥源（经 PBKDF2 派生 AES 密钥）：必须用密码学安全随机，
     *  java.util.Random 线性同余可预测，会被攻击者枚举出房间码 */
    private static final java.security.SecureRandom ROOM_RAND = new java.security.SecureRandom();

    private static String randomRoom() {
        return String.format(Locale.US, "%06d", ROOM_RAND.nextInt(1000000));
    }

    private static String localLanIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        if (ip != null && (ip.startsWith("192.168.") || ip.startsWith("10.")
                                || ip.startsWith("172."))) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 判断对方内网 IP 是否与本机同一局域网网段（RFC 5128：同网直连优先于 hairpin 打洞） */
    private static boolean isSameLan(String peerLanIp) {
        if (peerLanIp == null || peerLanIp.isEmpty()) return false;
        String local = localLanIp();
        if (local == null || local.isEmpty() || local.equals(peerLanIp)) return false;
        String[] a = local.split("\\.");
        String[] b = peerLanIp.split("\\.");
        if (a.length != 4 || b.length != 4) return false;
        // C 类 192.168.x.x：比较前三段（/24 子网）
        if (local.startsWith("192.168.") && peerLanIp.startsWith("192.168.")) {
            return a[0].equals(b[0]) && a[1].equals(b[1]) && a[2].equals(b[2]);
        }
        // A/B 类 10.x / 172.16-31.x：比较前两段（/16 子网）
        if (local.startsWith("10.") && peerLanIp.startsWith("10.")) {
            return a[0].equals(b[0]) && a[1].equals(b[1]);
        }
        if (local.startsWith("172.") && peerLanIp.startsWith("172.")) {
            return a[0].equals(b[0]) && a[1].equals(b[1]);
        }
        return false;
    }

    /** 局域网直连：短超时 TCP 连接（同网优先路径，失败快速回退打洞） */
    private static Socket lanConnect(String ip, int port, int timeoutMs) {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String normalize(String player) {
        return player == null || player.trim().isEmpty() ? "HY_VQ用户" : player.trim();
    }

    /** 清理全部资源（幂等）：多连接表/会话表/信令/打洞 socket 全关 */
    private static synchronized void cleanup() {
        running = false;
        role = ROLE_NONE;
        for (Transports.Transport t : transports.values()) {
            try { t.close(); } catch (Throwable ignored) {}
        }
        transports.clear();
        guests.clear();
        try { if (server != null) server.close(); } catch (Throwable ignored) {}
        server = null;
        try { if (punchSock != null) punchSock.close(); } catch (Throwable ignored) {}
        punchSock = null;
        stunResult = null;
        announceLogged = false;
        SigChannel c = sig;
        sig = null;
        if (c != null) c.close();
        crypto = null;
        guestGid = null;
        roomCode = null;
        releaseMulticastLock();
    }

    /** 获取 WiFi 组播锁（Android 默认过滤组播，必须持有才能收/发 UDP 组播发现包） */
    private static void acquireMulticastLock() {
        try {
            Context ctx = appContext;
            if (ctx == null) return;
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            WifiManager.MulticastLock lock = wm.createMulticastLock("HyVqP2pDiscovery");
            lock.setReferenceCounted(false);
            lock.acquire();
            multicastLock = lock;
        } catch (Throwable ignored) {
        }
    }

    private static void releaseMulticastLock() {
        try {
            WifiManager.MulticastLock lock = multicastLock;
            multicastLock = null;
            if (lock != null && lock.isHeld()) lock.release();
        } catch (Throwable ignored) {}
    }

    /** 状态回调（主线程）；同时缓存最近状态供 UI 重放 */
    private static void fire(TerracottaState s) {
        lastState = s;
        main.post(() -> {
            StateListener l = listener;
            if (l != null) l.onStateChanged(s);
        });
    }

    /** 追加日志并推送尾部（FCL 式日志流，带时间戳） */
    private static void log(String s) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date());
        logTail.append('[').append(ts).append("] ").append(s).append('\n');
        Log.i(TAG, s);
        postLogs();
    }

    private static void postLogs() {
        String tail = logTail.toString();
        String t = tail.length() > 2000 ? tail.substring(tail.length() - 2000) : tail;
        main.post(() -> {
            StateListener l = listener;
            if (l != null) l.onLogsUpdated(t);
        });
    }
}