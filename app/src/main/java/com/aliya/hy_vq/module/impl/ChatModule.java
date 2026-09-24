package com.aliya.hy_vq.module.impl;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.aliya.hy_vq.R;
import com.aliya.hy_vq.module.HyVqModule;
import com.aliya.hy_vq.module.HyVqTheme;
import com.aliya.hy_vq.module.ModuleServices;
import com.aliya.hy_vq.service.ChatServer;
import com.aliya.hy_vq.service.HyVqWebSocketClient;
import com.aliya.hy_vq.service.StunClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天模块实现 —— HyVqModule 的具体子类。
 * 打包为外置模块 zip 时，此实现和 ChatServer / HyVqWebSocketClient / StunClient
 * 一起放入 classes.dex。
 */
public class ChatModule extends HyVqModule {

    private ChatServer chatServer;
    private HyVqWebSocketClient wsClient;
    private StunClient stunClient;

    public ChatModule() {
        this.id = "chat_server";
        this.name = "聊天服务";
        this.version = "1.0.0";
        this.icon = "ic_chat";
        this.showInDrawer = true;
    }

    @Override
    public void onAttach(Context context, ModuleServices services) {
        // 模块加载后可在此初始化，也可由 MainActivity 的 setupChatView 逻辑触发
    }

    @Override
    public View createMainView(LayoutInflater inflater, ViewGroup parent) {
        View view = inflater.inflate(R.layout.fragment_chat, parent, false);
        // 初始化好友列表（数据接入前先保证列表可滚动、页面不空白）
        RecyclerView list = view.findViewById(R.id.recycler_friends_list);
        if (list != null) {
            list.setLayoutManager(new LinearLayoutManager(view.getContext()));
        }
        // 暂无好友数据时展示空态提示，避免空白页
        TextView emptyHint = view.findViewById(R.id.chat_empty_hint);
        if (emptyHint != null) {
            emptyHint.setVisibility(View.VISIBLE);
        }
        return view;
    }

    @Override
    public View createSettingsView(LayoutInflater inflater, ViewGroup parent) {
        return inflater.inflate(R.layout.fragment_mine, parent, false);
    }

    @Override
    public List<HyVqTheme> getThemes() {
        // 聊天模块可贡献自己的主题
        List<HyVqTheme> themes = new ArrayList<>();
        themes.add(new HyVqTheme("chat_dark", "聊天暗色主题", "chat_server"));
        themes.add(new HyVqTheme("chat_light", "聊天浅色主题", "chat_server"));
        return themes;
    }

    @Override
    public void onPause() {
        if (chatServer != null) chatServer.stop();
    }

    @Override
    public void onResume() {
        // 恢复时不做自动重连，由用户操作
    }

    @Override
    public void onDetach() {
        if (chatServer != null) {
            chatServer.stop();
            chatServer = null;
        }
        wsClient = null;
        stunClient = null;
    }

    @Override
    public String getSummary() {
        return id + " v" + version + " — 包含聊天服务 + WebSocket 客户端 + STUN 客户端";
    }
}