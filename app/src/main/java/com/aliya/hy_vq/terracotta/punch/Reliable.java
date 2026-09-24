package com.aliya.hy_vq.terracotta.punch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 可靠传输层（KCP 简化版，纯 Java，零依赖）。
 *
 * <p>职责：消息序号 + ACK 回执 + 指数退避重传 + 接收端去重。
 * 行协议（明文层，经 AES-GCM 加密后进入传输通道）：
 * <pre>
 *   R|M|{seq}|{data}   数据帧（业务消息）
 *   R|P|{seq}          心跳帧（带序号，对方回 ACK，可确认双向活性）
 *   R|A|{seq}          ACK 回执（不追踪、不回 ACK，避免回声风暴）
 * </pre>
 *
 * <p>两种模式：
 * <ul>
 *   <li>retransmit=true（UDP 打洞 / MQTT 中继）：完整重传。发送方指数退避
 *       300ms→600ms→1.2s→2.4s（最多 4 次重传），全部失败后若待确认队列已空
 *       触发一次 {@link Callback#onConfirmLost()}（通道单向/死亡的信号）。</li>
 *   <li>retransmit=false（TCP 直连）：TCP 内核已可靠，只统一序号/回 ACK/去重，
 *       防止对端（UDP/中继侧）因本端不回 ACK 而无限重传。</li>
 * </ul>
 *
 * <p>线程模型：sendMessage/sendPing 可被任意线程调用（发送线程/心跳线程），
 * handle 在 reader 线程调用，tick 在心跳线程调用；内部状态全部加锁保护，
 * 底层的 {@link Callback#sendPacket} 一律在锁外执行，避免 IO 卡锁。
 */
public final class Reliable {

    /** 桥接回调（由传输实现提供 IO 原语） */
    public interface Callback {
        /** 发送一个明文帧（实现方负责加密与落盘 IO；在锁外被调用） */
        void sendPacket(String plain);

        /** 收到去重后的明文消息（工作线程回调） */
        void onMessage(String text);

        /** 重传全部失败：通道单向或死亡（仅 retransmit=true 触发，至多一次） */
        void onConfirmLost();
    }

    // ── 协议常量 ──
    private static final String PREFIX = "R|";
    private static final char TYPE_MSG = 'M';
    private static final char TYPE_PING = 'P';
    private static final char TYPE_ACK = 'A';

    // ── 重传参数 ──
    private static final int MAX_RETRIES = 6;      // 初始发送 + 最多 6 次重传（共 7 次机会）
    private static final long BASE_RETRY_MS = 300; // 指数退避基数：300/600/1200/2400/4800/9600ms
    /** 接收端乱序窗口上限：超过视为通道异常，清空防内存膨胀 */
    private static final int MAX_GAP = 2048;

    private final boolean retransmit;
    private final Callback cb;

    // ── 发送侧状态 ──
    private long nextSeq;                              // 全局递增序号（消息与心跳共用）
    private final Map<Long, Pending> pending = new HashMap<>();

    // ── 接收侧状态 ──
    private long lastSeq = -1;                         // 已连续确认到的最大序号
    private final Set<Long> gapSeqs = new HashSet<>(); // 缺失序号（乱序/迟到，重传会补齐）

    private volatile boolean confirmLostFired;

    private static final class Pending {
        final String frame;
        int retries;
        long nextRetryAt;

        Pending(String frame, long nextRetryAt) {
            this.frame = frame;
            this.nextRetryAt = nextRetryAt;
        }
    }

    public Reliable(boolean retransmit, Callback cb) {
        this.retransmit = retransmit;
        this.cb = cb;
    }

    /** 发送业务消息（线程安全；底层 IO 在锁外执行） */
    public void sendMessage(String line) {
        sendFrame(TYPE_MSG, line);
    }

    /** 发送带序号心跳（对方回 ACK；可确认双向活性与通道存活） */
    public void sendPing() {
        sendFrame(TYPE_PING, "");
    }

    private void sendFrame(char type, String data) {
        final String frame;
        synchronized (this) {
            long seq = nextSeq++;
            frame = PREFIX + type + "|" + seq + (data.isEmpty() ? "" : "|" + data);
            if (retransmit) {
                pending.put(seq, new Pending(frame, System.currentTimeMillis()));
            }
        }
        cb.sendPacket(frame); // 锁外发送，避免 IO 卡住锁
    }

    /**
     * 定时驱动：重传超时未确认的包（心跳线程每 500ms 调用）。
     * 指数退避 300/600/1200/2400ms；耗尽重传次数后放弃该包，
     * 若待确认队列因此清空则触发一次 onConfirmLost（通道单向判定依据）。
     */
    public void tick() {
        if (!retransmit) return;
        List<String> resend = new ArrayList<>(4);
        boolean anyLost = false;
        long now = System.currentTimeMillis();
        synchronized (this) {
            Iterator<Map.Entry<Long, Pending>> it = pending.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, Pending> e = it.next();
                Pending p = e.getValue();
                if (now < p.nextRetryAt) continue;
                if (p.retries >= MAX_RETRIES) {
                    it.remove();          // 该包重传耗尽：放弃
                    anyLost = true;
                } else {
                    long delay = BASE_RETRY_MS << p.retries; // 300 → 600 → 1200 → 2400
                    p.retries++;
                    p.nextRetryAt = now + delay;
                    resend.add(p.frame);
                }
            }
        }
        for (String f : resend) cb.sendPacket(f); // 锁外重发
        if (anyLost) checkConfirmLost();
    }

    /**
     * 处理收到的明文包（reader 线程调用）。
     *
     * @return 需要上抛的明文消息；null 表示无需上抛（ACK/心跳/重复包）
     */
    public String handle(String plain) {
        if (plain == null) return null;
        if (!plain.startsWith(PREFIX)) return plain; // 兼容旧版裸明文（直接透传）

        // 解析 R|T|seq[|data]：P(心跳)/A(ACK) 帧没有 data 段，p3 为 -1 合法
        int p2 = plain.indexOf('|', 2);
        if (p2 < 0) return null;
        int p3 = plain.indexOf('|', p2 + 1);
        char type = plain.charAt(2);
        final long seq;
        try {
            seq = Long.parseLong(p3 < 0
                    ? plain.substring(p2 + 1) : plain.substring(p2 + 1, p3));
        } catch (NumberFormatException e) {
            return null;
        }

        switch (type) {
            case TYPE_ACK: { // 对方确认收到：移除待确认
                synchronized (this) {
                    pending.remove(seq);
                }
                return null;
            }
            case TYPE_PING: { // 心跳：回 ACK（不追踪，防回声风暴）
                sendAck(seq);
                return null;
            }
            case TYPE_MSG: { // 数据帧：回 ACK + 去重后上抛
                if (p3 < 0) return null; // M 帧必须带 data 段
                boolean isNew = isNewSeq(seq);
                sendAck(seq);
                if (!isNew) return null;
                String data = plain.substring(p3 + 1);
                return data.isEmpty() ? null : data;
            }
            default:
                return null;
        }
    }

    /** 回 ACK（ACK 本身不加密入队列、不重传：丢了会由对端数据重传触发再次回执） */
    private void sendAck(long seq) {
        cb.sendPacket(PREFIX + TYPE_ACK + "|" + seq);
    }

    /** 序号去重：返回 true 表示该序号是首次收到（新消息） */
    private boolean isNewSeq(long seq) {
        synchronized (this) {
            if (seq <= lastSeq) {
                if (gapSeqs.contains(seq)) { // 乱序窗口中迟到的缺失包：是新的
                    gapSeqs.remove(seq);
                    return true;
                }
                return false; // 重复包（ACK 已回，直接丢弃）
            }
            // 序号跳变过大（异常/对端状态异常）：重置窗口，避免巨量 gap 与误判
            if (seq - lastSeq > MAX_GAP) {
                gapSeqs.clear();
                lastSeq = seq;
                return true;
            }
            // 中间缺失的序号记入 gap（乱序/迟到，重传会补齐）
            for (long s = lastSeq + 1; s < seq; s++) {
                gapSeqs.add(s);
            }
            if (gapSeqs.size() > MAX_GAP) gapSeqs.clear(); // 异常保护：防内存膨胀
            lastSeq = seq;
            return true;
        }
    }

    /** 待确认队列已空且有过包被放弃：判定"重传全失败"，触发降级信号（至多一次） */
    private void checkConfirmLost() {
        if (!retransmit || confirmLostFired) return;
        synchronized (this) {
            if (!pending.isEmpty()) return;
        }
        confirmLostFired = true;
        cb.onConfirmLost();
    }
}