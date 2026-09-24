package com.aliya.hy_vq.terracotta;

import org.json.JSONObject;

/**
 * HY_VQ 自写的 Terracotta 状态封装（参考公开 API 的 JSON 契约重写，非复制任何 GPL 代码）。
 *
 * getState() 返回 JSON 形如：
 * { "state": "waiting"|"host-scanning"|"host-starting"|"host-ok"|
 *            "guest-connecting"|"guest-starting"|"guest-ok"|"exception",
 *   "index": 自增序号, "port": MC 端口,
 *   // host-ok:
 *   "room": 房间码, "profile_index": N, "profiles": [...],
 *   // guest-starting:
 *   "difficulty": "UNKNOWN"|"EASIEST"|"SIMPLE"|"MEDIUM"|"TOUGH",
 *   // guest-ok:
 *   "url": 主机地址, "profile_index": N, "profiles": [...],
 *   // exception:
 *   "type": 0..5 }
 */
public class TerracottaState {

    public enum Kind {
        WAITING("waiting"),
        HOST_SCANNING("host-scanning"),
        HOST_STARTING("host-starting"),
        HOST_OK("host-ok"),
        GUEST_CONNECTING("guest-connecting"),
        GUEST_STARTING("guest-starting"),
        GUEST_OK("guest-ok"),
        EXCEPTION("exception"),
        UNKNOWN("unknown");

        public final String wire;

        Kind(String wire) { this.wire = wire; }

        static Kind from(String s) {
            if (s == null) return UNKNOWN;
            for (Kind k : values()) {
                if (k.wire.equals(s)) return k;
            }
            return UNKNOWN;
        }
    }

    /** 客人连接难度（guest-starting 阶段返回，用于 UI 提示） */
    public enum Difficulty {
        UNKNOWN, EASIEST, SIMPLE, MEDIUM, TOUGH;

        static Difficulty from(String s) {
            if (s == null) return UNKNOWN;
            try { return valueOf(s); } catch (IllegalArgumentException e) { return UNKNOWN; }
        }
    }

    public final Kind kind;
    public final int index;
    public final int port;
    /** host-ok 时的房间码 */
    public final String room;
    /** guest-ok 时的主机地址（虚拟网内可达） */
    public final String url;
    /** guest-starting 时的预估难度 */
    public final Difficulty difficulty;
    /** exception 时的错误类型序号 */
    public final int errorType;
    /** exception 时的人类可读描述（局域网桥接直接提供，引擎状态为 null） */
    public final String message;

    private TerracottaState(Kind kind, int index, int port, String room, String url,
                            Difficulty difficulty, int errorType, String message) {
        this.kind = kind;
        this.index = index;
        this.port = port;
        this.room = room;
        this.url = url;
        this.difficulty = difficulty;
        this.errorType = errorType;
        this.message = message;
    }

    /** 初始假状态（后端尚未产生任何状态时的占位） */
    public static TerracottaState initial() {
        return new TerracottaState(Kind.WAITING, -1, 0, null, null, Difficulty.UNKNOWN, -1, null);
    }

    /** 局域网桥接用工厂：直接构造指定状态（kind + 房间码/对端地址），index 用递增序号 */
    public static TerracottaState of(Kind kind, String room, String url) {
        return of(kind, room, url, null);
    }

    /** 局域网桥接用工厂（可带人类可读消息，exception 时展示给用户） */
    public static TerracottaState of(Kind kind, String room, String url, String message) {
        return new TerracottaState(kind, (int) (System.currentTimeMillis() / 1000),
                0, room, url, Difficulty.UNKNOWN, -1, message);
    }

    /** 解析 getState() 返回的 JSON；解析失败返回 WAITING/index=-1（UI 假状态） */
    public static TerracottaState parse(String json) {
        int index = -1;
        int port = 0;
        String room = null;
        String url = null;
        Difficulty difficulty = Difficulty.UNKNOWN;
        int errorType = -1;
        Kind kind = Kind.UNKNOWN;
        try {
            JSONObject o = new JSONObject(json);
            index = o.optInt("index", -1);
            port = o.optInt("port", 0);
            kind = Kind.from(o.optString("state"));
            if (o.has("room")) room = o.optString("room");
            if (o.has("url")) url = o.optString("url");
            if (o.has("difficulty")) difficulty = Difficulty.from(o.optString("difficulty"));
            if (o.has("type")) errorType = o.optInt("type", -1);
        } catch (Exception ignored) {
            kind = Kind.UNKNOWN;
        }
        return new TerracottaState(kind, index, port, room, url, difficulty, errorType, null);
    }

    /** 错误码对应的中文描述（与官方 API 的 exception type 序号一致） */
    public static String describeError(int errorType) {
        switch (errorType) {
            case 0: return "无法 ping 通主机";
            case 1: return "主机连接被重置";
            case 2: return "客人端 Terracotta 异常退出";
            case 3: return "主机端 Terracotta 异常退出";
            case 4: return "无法 ping 通服务器";
            case 5: return "房间服务响应无效";
            default: return "未知错误";
        }
    }

    public static String describeDifficulty(Difficulty d) {
        switch (d) {
            case EASIEST: return "预计可直连（最简单）";
            case SIMPLE: return "预计可直连";
            case MEDIUM: return "可能需要中继";
            case TOUGH: return "预计只能走中继";
            default: return "评估中…";
        }
    }
}