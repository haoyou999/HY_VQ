package com.aliya.hy_vq.access;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 访问引擎工具：格式化（与 FileOps 对齐的展示逻辑，避免引擎层依赖操作层）。
 */
public final class AccessUtil {

    private AccessUtil() {}

    /** 人类可读文件大小（B/KB/MB/GB） */
    public static String formatSize(long bytes) {
        if (bytes < 0) return "-";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format(Locale.US, "%.2f GB", gb);
    }

    /** 格式化修改时间：今年 MM-dd HH:mm，跨年 yyyy-MM-dd */
    public static String formatDate(long millis) {
        if (millis <= 0) return "-";
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(millis);
        Calendar now = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat(
                cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) ? "MM-dd HH:mm" : "yyyy-MM-dd",
                Locale.getDefault());
        return sdf.format(new Date(millis));
    }
}