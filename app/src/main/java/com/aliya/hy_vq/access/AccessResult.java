package com.aliya.hy_vq.access;

import java.util.ArrayList;
import java.util.List;

/**
 * 目录访问结果：条目列表 + 统计 + 可选提示。
 *
 * <p>{@link #error} 非空表示真实读取失败且无虚拟数据（如 /data/app/~~xxx 下
 * 的 oat 层）——UI 应显示空列表而非报错，与 MT 管理器静默空列表行为一致。</p>
 */
public class AccessResult {

    public final List<FileEntry> entries;
    /** 非空 = 该目录无权限且无虚拟信息（UI 静默空列表） */
    public final String error;
    /** 来源描述（调试/状态栏可显示，正常 UI 不展示） */
    public final String source;
    /** SAF uri 浏览时的父 uri（供".."返回上级）；非 SAF 路径为 null */
    public final String parentUri;

    public AccessResult(List<FileEntry> entries, String error, String source) {
        this(entries, error, source, null);
    }

    public AccessResult(List<FileEntry> entries, String error, String source, String parentUri) {
        this.entries = entries == null ? new ArrayList<>() : entries;
        this.error = error;
        this.source = source;
        this.parentUri = parentUri;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int dirCount() {
        int n = 0;
        for (FileEntry e : entries) {
            if (e.isDir()) n++;
        }
        return n;
    }

    public int fileCount() {
        int n = 0;
        for (FileEntry e : entries) {
            if (!e.isDir()) n++;
        }
        return n;
    }

    public static AccessResult ok(List<FileEntry> entries, String source) {
        return new AccessResult(entries, null, source);
    }

    public static AccessResult empty(String source) {
        return new AccessResult(new ArrayList<>(), null, source);
    }

    public static AccessResult denied(String source) {
        return new AccessResult(new ArrayList<>(), "无访问权限", source);
    }
}