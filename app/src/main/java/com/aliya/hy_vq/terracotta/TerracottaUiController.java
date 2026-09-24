package com.aliya.hy_vq.terracotta;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.aliya.hy_vq.R;
import com.aliya.hy_vq.module.ModuleUiKit;
import com.google.android.material.textfield.TextInputEditText;

/**
 * HY_VQ 自绘的联机 UI 控制器（交互流参考公开功能设计，实现全部自研，非复制任何 GPL 代码）。
 *
 * 状态流：
 * WAITING(创建/加入) → SCANNING/CONNECTING/STARTING(进度) → OK(房间码/主机地址) | EXCEPTION(错误)
 * 所有状态在同一个玻璃风格 Dialog 内切换。
 */
public class TerracottaUiController implements HyVqP2pBridge.StateListener {

    private final Activity activity;
    private final String playerName;

    private Dialog dialog;
    private FrameLayout stateContainer;
    private View currentStateView;
    private View waitingView;
    private View progressView;
    private View okView;
    private View exceptionView;
    /** 标题行状态指示灯（等待灰 / 进度橙 / 成功绿 / 异常红） */
    private View statusDot;

    /** 上一次联机状态（用于检测进度态回退，透明展示"查找超时→自动返回"过程） */
    private TerracottaState.Kind lastKind = null;
    /** 进度视图中的日志区（FCL 式日志流） */
    private TextView logView;
    /** 等待视图中的回退提示条（如"未找到房间，已自动返回"） */
    private TextView waitingNotice;

    public TerracottaUiController(Activity activity, String playerName) {
        this.activity = activity;
        this.playerName = playerName == null || playerName.isEmpty() ? "HY_VQ用户" : playerName;
    }

    /** 显示联机对话框并进入等待态 */
    public void show() {
        if (dialog != null && dialog.isShowing()) return;

        // P2P 桥接层为纯 Java 实现（同网直连 + 异地打洞），无需加载原生库
        if (!HyVqP2pBridge.initialize(activity)) {
            toast("联机模块初始化失败");
            return;
        }

        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View root = LayoutInflater.from(activity).inflate(R.layout.dialog_terracotta, null, false);

        stateContainer = root.findViewById(R.id.tc_state_container);
        statusDot = root.findViewById(R.id.tc_status_dot);
        TextView title = root.findViewById(R.id.tc_title);
        TextView subtitle = root.findViewById(R.id.tc_subtitle);
        title.setText("房间联机");
        subtitle.setText("P2P 打洞组网 · 同网直连 · 异地打洞 · 中继兜底");

        root.findViewById(R.id.tc_close).setOnClickListener(v -> dismiss());

        // 预构建四个状态视图
        waitingView = LayoutInflater.from(activity).inflate(R.layout.view_tc_waiting, stateContainer, false);
        progressView = LayoutInflater.from(activity).inflate(R.layout.view_tc_progress, stateContainer, false);
        okView = LayoutInflater.from(activity).inflate(R.layout.view_tc_ok, stateContainer, false);
        exceptionView = LayoutInflater.from(activity).inflate(R.layout.view_tc_exception, stateContainer, false);

        bindWaitingView();
        bindProgressView();
        bindOkView();
        bindExceptionView();

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            Window window = dialog.getWindow();
            window.setLayout(
                    (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            // 浮窗背景模糊（规范）
            ModuleUiKit.applyBlur(window, activity);
        }
        dialog.setCancelable(true);
        dialog.setOnDismissListener(d -> {
            HyVqP2pBridge.setListener(null);
        });

        // 注册状态监听
        HyVqP2pBridge.setListener(this);

        // 状态灯初始化为等待灰（后续由 onStateChanged 联动）
        if (statusDot != null) statusDot.setBackgroundResource(R.drawable.bg_dot_gray);
        showState(waitingView);
        dialog.show();

        // 状态重放：房间/连接在后台运行时重新打开弹窗，直接恢复对应视图（关闭弹窗不丢状态）
        TerracottaState last = HyVqP2pBridge.getLastState();
        if (last != null && last.kind != TerracottaState.Kind.WAITING) {
            onStateChanged(last);
        }
    }

    public void dismiss() {
        HyVqP2pBridge.setListener(null);
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        dialog = null;
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    /** 状态变化：切换视图；FCL 式透明化——进度态中途回退 WAITING 时给出明确提示 */
    @Override
    public void onStateChanged(TerracottaState state) {
        if (dialog == null || !dialog.isShowing()) return;
        TerracottaState.Kind prev = lastKind;
        lastKind = state.kind;
        // 状态指示灯联动（标题行圆点：等待灰/进度橙/成功绿/异常红）
        if (statusDot != null) {
            switch (state.kind) {
                case HOST_OK:
                case GUEST_OK:
                    statusDot.setBackgroundResource(R.drawable.bg_dot_green);
                    break;
                case EXCEPTION:
                    statusDot.setBackgroundResource(R.drawable.bg_dot_red);
                    break;
                case WAITING:
                    statusDot.setBackgroundResource(R.drawable.bg_dot_gray);
                    break;
                default:
                    statusDot.setBackgroundResource(R.drawable.bg_dot_orange);
                    break;
            }
        }
        // 进度态中途回退 WAITING（如查找房间超时/连接断开自动回退）：
        // 参考 FCL 启动流程透明展示该过程，避免用户误以为"卡住"。
        if (state.kind == TerracottaState.Kind.WAITING
                && prev != null
                && prev != TerracottaState.Kind.WAITING
                && prev != TerracottaState.Kind.HOST_OK
                && prev != TerracottaState.Kind.GUEST_OK
                && prev != TerracottaState.Kind.EXCEPTION) {
            showWaitingNotice(
                    prev == TerracottaState.Kind.GUEST_CONNECTING
                            || prev == TerracottaState.Kind.GUEST_STARTING
                    ? "连接已中断，已返回等待态（可检查房间码与网络后重试）"
                    : "联机已结束，已返回等待态");
        }
        switch (state.kind) {
            case WAITING:
                showState(waitingView);
                break;
            case HOST_SCANNING:
                // P2P 桥接：房间已创建成功，直接展示房间码成功视图（非进度态），
                // 明确告知用户关闭弹窗不影响后台等待，解决"创建成功却不敢关弹窗"的困惑。
                showOk("✅ 房间已创建", "房间码", state.room,
                        "把房间码分享给好友：同一 WiFi 自动直连，异地自动打洞组网",
                        "等待好友加入…（关闭弹窗不影响房间，重新打开联机对话框可查看状态）",
                        false);
                showState(okView);
                break;
            case HOST_STARTING:
                setProgress("房间创建成功", "正在等待好友加入…", null);
                showState(progressView);
                break;
            case HOST_OK:
                showOk("✅ 房间联机中", "房间码", state.room,
                        "P2P 连接已建立，多人房间：消息经房主转发，全员可见（AES-GCM 加密）",
                        state.message, true);
                showState(okView);
                break;
            case GUEST_CONNECTING:
                setProgress("正在查找房间…", "同网组播 + 跨网信令并行搜索，异地好友自动打洞", null);
                showState(progressView);
                break;
            case GUEST_STARTING:
                setProgress("正在建立连接…", "已发现房主，正在建立连接（同网直连 / 异地打洞）", null);
                showState(progressView);
                break;
            case GUEST_OK:
                showOk("✅ 已加入房间", "主机地址", state.url,
                        "P2P 连接建立，输入消息即可与房主异地聊天（AES-GCM 加密）", state.message,
                        true);
                showState(okView);
                break;
            case EXCEPTION:
                showException(state.message != null && !state.message.isEmpty()
                        ? state.message
                        : TerracottaState.describeError(state.errorType));
                showState(exceptionView);
                break;
            default:
                break;
        }
    }

    // ── 视图切换 ──

    private void showState(View v) {
        if (stateContainer == null || v == null) return;
        stateContainer.removeAllViews();
        stateContainer.addView(v);
        currentStateView = v;
    }

    // ── 等待态 ──

    private void bindWaitingView() {
        waitingNotice = waitingView.findViewById(R.id.tc_waiting_notice);
        waitingView.findViewById(R.id.tc_host_btn).setOnClickListener(v -> {
            // 新的联机尝试：清除上次回退提示
            if (waitingNotice != null) waitingNotice.setVisibility(View.GONE);
            if (!HyVqP2pBridge.hostRoom(null, playerName)) {
                toast("开房失败");
            }
        });
        waitingView.findViewById(R.id.tc_guest_btn).setOnClickListener(v -> showRoomInputDialog());
    }

    private void showRoomInputDialog() {
        Dialog input = new Dialog(activity);
        input.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View root = LayoutInflater.from(activity).inflate(R.layout.dialog_tc_room_input, null, false);
        final TextInputEditText code = root.findViewById(R.id.tc_input_code);
        final TextView validation = root.findViewById(R.id.tc_input_validation);

        root.findViewById(R.id.tc_input_cancel).setOnClickListener(v -> input.dismiss());
        root.findViewById(R.id.tc_input_join).setOnClickListener(v -> {
            String room = code.getText() == null ? "" : code.getText().toString().trim();
            if (HyVqP2pBridge.isRoomCodeValid(room)) {
                input.dismiss();
                // 新的联机尝试：清除上次回退提示
                if (waitingNotice != null) waitingNotice.setVisibility(View.GONE);
                if (!HyVqP2pBridge.joinRoom(room, playerName)) {
                    toast("加入房间失败");
                }
            } else {
                validation.setVisibility(View.VISIBLE);
            }
        });

        input.setContentView(root);
        if (input.getWindow() != null) {
            Window window = input.getWindow();
            window.setLayout(
                    (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.88),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            // 浮窗背景模糊（规范）
            ModuleUiKit.applyBlur(window, activity);
        }
        input.setCancelable(true);
        input.show();
    }

    // ── 进度态 ──

    private void setProgress(String title, String desc, String hint) {
        TextView t = progressView.findViewById(R.id.tc_progress_title);
        TextView d = progressView.findViewById(R.id.tc_progress_desc);
        TextView h = progressView.findViewById(R.id.tc_progress_hint);
        t.setText(title);
        d.setText(desc);
        if (hint == null) {
            h.setVisibility(View.GONE);
        } else {
            h.setVisibility(View.VISIBLE);
            h.setText("📶 " + hint);
        }
    }

    private void bindProgressView() {
        logView = progressView.findViewById(R.id.tc_progress_log);
        // 进入进度态时若已有日志尾部，立即展示（FCL 式日志流）
        String tail = HyVqP2pBridge.getLatestLogTail();
        if (logView != null && tail != null && !tail.isEmpty()) {
            logView.setVisibility(View.VISIBLE);
            logView.setText(tailLines(tail, 8));
        }
        progressView.findViewById(R.id.tc_progress_back).setOnClickListener(v -> {
            HyVqP2pBridge.backToWaiting();
        });
    }

    /** FCL 式日志流：把日志尾部实时刷到进度视图；房主等待期间（成功视图未连接）刷到消息区 */
    @Override
    public void onLogsUpdated(String tail) {
        if (dialog == null || !dialog.isShowing()) return;
        if (tail == null || tail.isEmpty()) return;
        if (logView != null && currentStateView == progressView) {
            logView.setVisibility(View.VISIBLE);
            logView.setText(tailLines(tail, 8));
        } else if (currentStateView == okView && HyVqP2pBridge.getTransportName().isEmpty()) {
            // 房主等待好友期间：把跨网准备日志流（STUN/信令）刷到消息区；
            // 连接建立后 getTransportName() 非空，消息区回归收到消息的展示用途。
            TextView msg = okView.findViewById(R.id.tc_ok_msg);
            if (msg != null) {
                msg.setVisibility(View.VISIBLE);
                msg.setText("📡 " + tailLines(tail, 5));
            }
        }
    }

    /** 收到对端消息（多人房间数据通道）：解析 M|昵称|内容 帧显示昵称，追加到消息区 */
    @Override
    public void onMessage(String line) {
        if (dialog == null || !dialog.isShowing()) return;
        appendChat(formatIncoming(line));
    }

    /** 解析 M|昵称|内容 帧 → "📩 昵称：内容"；旧格式/普通行原样显示 */
    private static String formatIncoming(String line) {
        if (line != null && line.startsWith("M|")) {
            int bar = line.indexOf('|', 2);
            if (bar > 2) {
                String name = line.substring(2, bar);
                String body = line.substring(bar + 1);
                return "📩 " + name + "： " + body;
            }
        }
        return "📩 " + (line == null ? "" : line);
    }

    /** 截取日志文本的最后 max 行（日志流展示只看尾部） */
    private static String tailLines(String text, int max) {
        if (text == null || text.isEmpty()) return "";
        String[] lines = text.split("\n");
        if (lines.length <= max) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = lines.length - max; i < lines.length; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /** 等待视图回退提示条（透明展示"查找超时→自动返回"等过程） */
    private void showWaitingNotice(String msg) {
        if (waitingNotice == null) return;
        waitingNotice.setVisibility(View.VISIBLE);
        waitingNotice.setText("ℹ️ " + msg);
    }

    // ── 成功态 ──

    private String lastCopied = "";
    private TextView chatMsgView;
    private ScrollView chatMsgScroll;
    private LinearLayout chatInputBar;
    private EditText chatInput;

    /** @param chatReady 连接已建立：显示聊天输入行；等待期隐藏 */
    private void showOk(String title, String label, String value, String desc, String extra,
                        boolean chatReady) {
        TextView t = okView.findViewById(R.id.tc_ok_title);
        TextView l = okView.findViewById(R.id.tc_ok_label);
        TextView c = okView.findViewById(R.id.tc_ok_code);
        TextView d = okView.findViewById(R.id.tc_ok_desc);
        TextView e = okView.findViewById(R.id.tc_ok_extra);
        // 消息区：连接建立后（chatReady=true）保持干净只展示聊天消息，
        // 不被日志覆盖（日志走 📋 按钮查看）；等待期（false）展示 📡 日志流。
        TextView msg = okView.findViewById(R.id.tc_ok_msg);
        if (msg != null) {
            if (chatReady) {
                // 仅首次进入/切换视图时清空；成员变化（fireMembers 刷新 HOST_OK）不清空聊天记录
                CharSequence cur = msg.getText();
                if (cur == null || cur.length() == 0) {
                    msg.setText("");
                }
            } else {
                String tail = HyVqP2pBridge.getLatestLogTail();
                if (tail != null && !tail.isEmpty()) {
                    msg.setVisibility(View.VISIBLE);
                    msg.setText("📡 " + tailLines(tail, 5));
                }
            }
        }
        if (chatMsgScroll != null) {
            chatMsgScroll.setVisibility(chatReady ? View.VISIBLE : View.GONE);
        }
        if (chatInputBar != null) {
            chatInputBar.setVisibility(chatReady ? View.VISIBLE : View.GONE);
        }
        t.setText(title);
        l.setText(label);
        c.setText(value == null || value.isEmpty() ? "——" : value);
        d.setText(desc);
        if (extra == null) {
            e.setVisibility(View.GONE);
        } else {
            e.setVisibility(View.VISIBLE);
            e.setText(extra);
        }
        lastCopied = value == null ? "" : value;
    }

    private void bindOkView() {
        chatMsgView = okView.findViewById(R.id.tc_ok_msg);
        chatMsgScroll = okView.findViewById(R.id.tc_ok_msg_scroll);
        chatInputBar = okView.findViewById(R.id.tc_ok_input_bar);
        chatInput = okView.findViewById(R.id.tc_ok_input);
        okView.findViewById(R.id.tc_ok_copy).setOnClickListener(v -> {
            if (lastCopied.isEmpty()) return;
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("hyvq_room", lastCopied));
                toast("已复制到剪贴板");
            }
        });
        // 📋 查看完整日志（FCL 式透明过程）
        okView.findViewById(R.id.tc_ok_logs).setOnClickListener(v -> showLogDialog());
        // 异地聊天：发送消息（AES-GCM 加密数据通道）
        okView.findViewById(R.id.tc_ok_send).setOnClickListener(v -> sendChat());
        if (chatInput != null) {
            chatInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    sendChat();
                    return true;
                }
                return false;
            });
        }
        okView.findViewById(R.id.tc_ok_back).setOnClickListener(v -> {
            HyVqP2pBridge.backToWaiting();
        });
    }

    /** 发送房间聊天消息；成功后本地回显，失败提示（好友尚未加入/通道未就绪） */
    private void sendChat() {
        if (chatInput == null) return;
        String text = chatInput.getText() == null ? "" : chatInput.getText().toString().trim();
        if (text.isEmpty()) {
            toast("输入内容不能为空");
            return;
        }
        if (HyVqP2pBridge.sendLine(text)) {
            appendChat("📤 " + playerName + "： " + text);
            chatInput.setText("");
            // 保持输入焦点与键盘（消息区滚动不要抢焦点，否则需重新点击输入栏）
            chatInput.requestFocus();
        } else {
            toast("好友尚未加入，暂无数据通道");
        }
    }

    /** 追加聊天消息到消息区并平滑滚动到底部（smoothScrollTo 不抢输入焦点） */
    private void appendChat(String line) {
        if (chatMsgView == null) return;
        if (chatMsgScroll != null) chatMsgScroll.setVisibility(View.VISIBLE);
        String old = chatMsgView.getText() == null ? "" : chatMsgView.getText().toString();
        chatMsgView.setText(old.isEmpty() ? line : old + "\n" + line);
        if (chatMsgScroll != null) {
            chatMsgScroll.post(() ->
                    chatMsgScroll.smoothScrollTo(0, chatMsgView.getHeight()));
        }
    }

    /** 📋 日志弹窗：等宽字体展示完整日志（FCL 式透明过程，玻璃风格与主弹窗一致） */
    private void showLogDialog() {
        Dialog logDialog = new Dialog(activity);
        logDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        // 风格一致：玻璃拟态背景（与 dialog_terracotta 相同的 drawable）
        root.setBackgroundResource(R.drawable.bg_glass_dialog);
        TextView title = new TextView(activity);
        title.setText("📋 联机日志（尾部 2000 字符）");
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        title.setTextColor(activity.getColor(android.R.color.black));
        root.addView(title);
        TextView body = new TextView(activity);
        body.setTextSize(11);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextColor(activity.getColor(android.R.color.black));
        body.setLineSpacing(0f, 1.1f);
        body.setTextIsSelectable(true);
        String tail = HyVqP2pBridge.getLatestLogTail();
        body.setText(tail == null || tail.isEmpty() ? "（暂无日志）" : tail);
        ScrollView sv = new ScrollView(activity);
        sv.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));
        logDialog.setContentView(root);
        if (logDialog.getWindow() != null) {
            Window w = logDialog.getWindow();
            // 宽度与输入房间弹窗一致（0.88 屏宽），透明背景由 drawable 呈现
            w.setLayout(
                    (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.88),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setBackgroundDrawableResource(android.R.color.transparent);
            // 浮窗背景模糊（规范）
            ModuleUiKit.applyBlur(w, activity);
        }
        logDialog.show();
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    // ── 异常态 ──

    private void showException(String desc) {
        TextView d = exceptionView.findViewById(R.id.tc_error_desc);
        d.setText(desc);
    }

    private void bindExceptionView() {
        exceptionView.findViewById(R.id.tc_error_retry).setOnClickListener(v -> {
            HyVqP2pBridge.backToWaiting();
        });
    }

    private void toast(String msg) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
    }
}