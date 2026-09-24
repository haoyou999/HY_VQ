package com.aliya.hy_vq.access;

/**
 * 文件管理统一条目——真实/虚拟/卷/链接 四类来源的归一化模型。
 *
 * <p>对齐 MT 管理器实测：真实 listFiles() 成功的条目携带真实元数据；
 * PackageManager/StorageManager 虚拟枚举的条目标记 {@link #virtual}，
 * 点击进入时可能为空列表（无真实读取权限）或真实可读。</p>
 */
public class FileEntry {

    /** 条目类型 */
    public enum Type {
        /** 真实文件夹 */
        DIR,
        /** 真实文件 */
        FILE,
        /** 虚拟文件夹（PackageManager/白名单等，可能无真实读取权限） */
        VIRTUAL_DIR,
        /** 虚拟文件（如 /storage 的卷条目伪装） */
        VIRTUAL_FILE,
        /** 符号链接 */
        LINK
    }

    public final String path;
    public final String name;
    public final Type type;
    public final long length;
    public final long lastModified;
    public final boolean virtual;
    /** 副文本补充（卷标签等），可为 null */
    public final String subText;
    /** 是否为强制置顶的".."返回上级条目（不算在列出的文件/统计中） */
    public final boolean parent;

    public FileEntry(String path, String name, Type type, long length, long lastModified,
                     boolean virtual, String subText) {
        this(path, name, type, length, lastModified, virtual, subText, false);
    }

    public FileEntry(String path, String name, Type type, long length, long lastModified,
                     boolean virtual, String subText, boolean parent) {
        this.path = path;
        this.name = name;
        this.type = type;
        this.length = length;
        this.lastModified = lastModified;
        this.virtual = virtual;
        this.subText = subText;
        this.parent = parent;
    }

    public boolean isDir() {
        return type == Type.DIR || type == Type.VIRTUAL_DIR;
    }

    /** 构建".."返回上级条目（强制置顶，不参与统计/多选） */
    public static FileEntry parent(String path) {
        return new FileEntry(path, "..", Type.DIR, 0, 0, false, "返回上级", true);
    }

    /** 构建真实目录条目 */
    public static FileEntry dir(String path, long lastModified) {
        return new FileEntry(path, baseName(path), Type.DIR, 0, lastModified, false, null);
    }

    /** 构建真实文件条目 */
    public static FileEntry file(String path, long length, long lastModified) {
        return new FileEntry(path, baseName(path), Type.FILE, length, lastModified, false, null);
    }

    /** 构建虚拟目录条目 */
    public static FileEntry virtualDir(String path, String subText) {
        return new FileEntry(path, baseName(path), Type.VIRTUAL_DIR, 0, 0, true, subText);
    }

    /** 构建虚拟文件条目 */
    public static FileEntry virtualFile(String path, String subText) {
        return new FileEntry(path, baseName(path), Type.VIRTUAL_FILE, 0, 0, true, subText);
    }

    /** 构建符号链接条目（真实目录下发现） */
    public static FileEntry link(String path, String linkTarget, long lastModified) {
        return new FileEntry(path, baseName(path), Type.LINK, 0, lastModified, false,
                linkTarget == null ? null : "-> " + linkTarget);
    }

    private static String baseName(String path) {
        if (path == null || path.isEmpty()) return "";
        String p = path;
        while (p.endsWith("/") && p.length() > 1) p = p.substring(0, p.length() - 1);
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }
}