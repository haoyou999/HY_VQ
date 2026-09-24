package com.aliya.hy_vq.access;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * SAF（Storage Access Framework）uri 浏览策略——授权目录在 app 内直接浏览。
 *
 * <p><b>背景（真机实测 2026-08-16）</b>：点击授权目录此前甩给系统文件管理器
 * （ACTION_VIEW + directory MIME），本设备弹"打开方式"Chooser 且无应用处理；
 * 而 Termux 类授权目录（/data/data/...）因 SELinux canRead()=false 无法用真实
 * 路径进入。正解：授权目录本身就是 DocumentsProvider 树，直接用 SAF uri 在
 * app 内浏览（MT 管理器同款行为）。</p>
 *
 * <p>路由：窗格 path 为 content:// uri 时进入本策略。子文档通过 childDocuments
 * 查询（手动拼接单编码 uri，与第十轮 DISPLAY_NAME 修复同一原则，避免
 * buildChildDocumentsUriUsingTree 的 appendPath 二次编码 %2F → %252F）。</p>
 */
public final class SafStrategy {

    private static final String[] PROJ = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };

    private SafStrategy() {}

    /** 判断路径是否为 SAF content:// uri（进入本策略路由） */
    public static boolean isSafUri(String path) {
        return path != null && path.startsWith("content://");
    }

    /** 列出 uri 目录的子文档；结果携带 parentUri 供".."返回上级 */
    public static AccessResult list(Context ctx, String uriStr) {
        try {
            Uri uri = Uri.parse(uriStr);
            Uri childrenUri = childrenUri(uri);
            if (childrenUri == null) return AccessResult.denied("SAF");
            Cursor c = queryChildrenCursor(ctx, childrenUri);
            if (c == null) return AccessResult.denied("SAF");
            List<FileEntry> out = new ArrayList<>();
            try {
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    long size = c.getLong(3);
                    long modified = c.getLong(4);
                    if (id == null || id.isEmpty()) continue;
                    // ZeroTermux 等 provider 不返回 DISPLAY_NAME（实测 NULL）→ 从 docId 末段回退
                    if (name == null || name.isEmpty()) {
                        String dec = Uri.decode(id);
                        if (dec != null) {
                            int slash = dec.lastIndexOf('/');
                            name = slash >= 0 ? dec.substring(slash + 1) : dec;
                        }
                        if (name == null || name.isEmpty()) name = id;
                    }
                    boolean dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                    String docUri = documentUri(uri, id);
                    // 文件条目副文本 = 大小；目录无副文本（角标「虚」标记 SAF 来源）
                    String sub = dir ? null : (size > 0 ? AccessUtil.formatSize(size) : null);
                    out.add(new FileEntry(docUri, name,
                            dir ? FileEntry.Type.VIRTUAL_DIR : FileEntry.Type.VIRTUAL_FILE,
                            size, modified, true, sub));
                }
            } finally {
                c.close();
            }
            return new AccessResult(out, null, "SAF", parentOf(uri));
        } catch (Throwable t) {
            // 保留真实异常信息（UI 在空列表时提示，便于定位权限/格式问题），不再静默吞掉
            return new AccessResult(new ArrayList<>(), "SAF: " + t, "SAF", null);
        }
    }

    /**
     * 子文档 children uri（手动拼接）：
     * tree 授权 → content://auth/tree/<encTreeId>/document/<encDocId>/children
     * document 授权 → content://auth/document/<encDocId>/children
     *
     * <p><b>关键（真机实测 2026-08-16）</b>：docId 可能是含 '/' 的绝对路径
     * （Termux/ZeroTermux：/data/data/com.termux/files/home 或卷:路径），
     * DocumentsContract.getTreeDocumentId/getDocumentId 返回的是<b>解码后</b>
     * 的明文（%2F → /），直接拼接会让 uri path 分段错乱（/tree/home:/data/... 被
     * 拆成多段）导致 provider 解析失败。必须从原始 uri 字符串提取<b>编码态</b>
     * 段（%2F 原样），子 docId 用 Uri.encode 重新编码后再拼接。</p>
     */
    private static Uri childrenUri(Uri uri) {
        String s = uri.toString();
        String auth = uri.getAuthority();
        if (auth == null) return null;
        if (DocumentsContract.isTreeUri(uri)) {
            // tree uri：tree/<encTreeId>（无 /document/），根子文档 = 自身
            String enc = encLast(s);
            if (enc == null || enc.isEmpty()) return null;
            return Uri.parse("content://" + auth + "/tree/" + enc
                    + "/document/" + enc + "/children");
        }
        String encDoc = encLast(s);
        if (encDoc == null || encDoc.isEmpty()) return null;
        String encTree = encTreeId(s);
        if (encTree != null) {
            // tree 前缀的 document uri：保持 tree 形式（可访问整棵子树）
            return Uri.parse("content://" + auth + "/tree/" + encTree
                    + "/document/" + encDoc + "/children");
        }
        return Uri.parse("content://" + auth + "/document/" + encDoc + "/children");
    }

    /** 子文档 document uri（保持与父相同的 tree/document 前缀形式；子 docId 重新编码） */
    private static String documentUri(Uri parent, String childId) {
        String s = parent.toString();
        String auth = parent.getAuthority();
        if (auth == null) return childId;
        // provider 返回的 DOCUMENT_ID 为解码态明文（如 primary:DCIM 或 /data/.../home），
        // 拼进 uri path 必须 Uri.encode（/ → %2F、: → %3A），否则分段错乱
        String encChild = Uri.encode(childId);
        String encTree = encTreeId(s);
        if (encTree != null) {
            return "content://" + auth + "/tree/" + encTree + "/document/" + encChild;
        }
        return "content://" + auth + "/document/" + encChild;
    }

    /** 从 uri 字符串提取编码态 treeId（/tree/ 之后到 /document/ 或末尾；保持 %2F 原样） */
    private static String encTreeId(String s) {
        int ti = s.indexOf("/tree/");
        if (ti < 0) return null;
        int di = s.indexOf("/document/", ti);
        return di > 0
                ? s.substring(ti + "/tree/".length(), di)
                : s.substring(ti + "/tree/".length());
    }

    /** uri 最后一段（编码态原样，不解码） */
    private static String encLast(String s) {
        int last = s.lastIndexOf('/');
        return last >= 0 ? s.substring(last + 1) : s;
    }

    /**
     * 父 uri：
     * - tree uri（授权根，无 /document/）→ null（".."不显示）
     * - 根 document uri（docId == treeId）→ null（已是根）
     * - 一级子目录（docId 无嵌套，如 primary:DCIM）→ tree 根 document uri
     * - 深层子目录（docId 含嵌套 /）→ 去掉最后一级
     *
     * <p><b>关键（真机实测 2026-08-16）</b>：Termux/ZeroTermux 的 docId 是<b>绝对路径</b>
     * （/data/data/com.termux/files/home，恒含 '/'），根 document uri 的 docId 与 treeId
     * 相同（如 /data/.../home）。必须先比较 docPart == treeId（编码态比较，等价于解码态）
     * 判定"根 document uri"，否则绝对路径 docId 永远走"深层"分支，把根误判为子目录、
     * 返回错误父（…/document//data/data/com.termux/files）→ 授权根出现"返回上级"、
     * 点进去是空目录（实测 bug）。</p>
     */
    private static String parentOf(Uri uri) {
        String s = uri.toString();
        int di = s.indexOf("/document/");
        if (di < 0) return null; // tree uri → 授权根，无父
        int last = s.lastIndexOf('/');
        if (last <= 0) return null;
        String docPart = s.substring(last + 1);
        int ti = s.indexOf("/tree/");
        if (ti >= 0) {
            String treeId = s.substring(ti + "/tree/".length(), di);
            if (docPart.equals(treeId)) return null; // 根 document uri（docId == treeId）
        }
        String dec = Uri.decode(docPart);
        if (dec == null) return null;
        int slash = dec.lastIndexOf('/');
        if (slash < 0) {
            // docId 无嵌套：一级子目录的父 = tree 根 document uri；单文档授权 → 已到根
            if (ti >= 0) {
                String treeId = s.substring(ti + "/tree/".length(), di);
                return s.substring(0, di + "/document/".length()) + treeId;
            }
            return null;
        }
        String parentDoc = Uri.encode(dec.substring(0, slash));
        return s.substring(0, last + 1) + parentDoc;
    }

    // ── SAF 写操作（对齐 MT：授权目录可完整读写，无需 root） ──

    /** 名称净化：去路径分隔符/控制字符，防路径穿越（与 FileOps.sanitize 同规则） */
    private static String sanitizeName(String name) {
        if (name == null) return "";
        String n = name.replace('/', ' ').replace('\\', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        if (n.equals(".") || n.equals("..")) return "";
        return n.length() > 120 ? n.substring(0, 120) : n;
    }

    /**
     * 可写父 uri：tree uri → 根 document uri（DocumentsContract.createDocument 要求
     * parent 必须是 document uri，tree uri 会抛 IllegalArgumentException）；document uri → 自身。
     */
    public static String writableParentUri(String uriStr) {
        if (uriStr == null) return null;
        String s = uriStr;
        if (s.indexOf("/document/") >= 0) return s; // 已是 document uri
        int ti = s.indexOf("/tree/");
        if (ti < 0) return null;
        String head = s.substring(0, ti + "/tree/".length());
        String encTree = s.substring(ti + "/tree/".length());
        if (encTree.isEmpty()) return null;
        return head + encTree + "/document/" + encTree;
    }

    /** 新建文件/文件夹（SAF）：parentUriStr 可为 tree uri 或 document uri；返回新 document uri 或 null */
    public static String createDocument(Context ctx, String parentUriStr, String name, boolean dir) {
        String parent = writableParentUri(parentUriStr);
        if (parent == null) return null;
        String n = sanitizeName(name);
        if (n.isEmpty()) return null;
        String mime = dir ? DocumentsContract.Document.MIME_TYPE_DIR : mimeForName(n);
        try {
            Uri created = DocumentsContract.createDocument(
                    ctx.getContentResolver(), Uri.parse(parent), mime, n);
            return created == null ? null : created.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 重命名（SAF）：docUriStr 为 document uri；返回新 uri 或 null */
    public static String renameDocument(Context ctx, String docUriStr, String newName) {
        String n = sanitizeName(newName);
        if (n.isEmpty()) return null;
        try {
            Uri renamed = DocumentsContract.renameDocument(
                    ctx.getContentResolver(), Uri.parse(docUriStr), n);
            return renamed == null ? docUriStr : renamed.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 删除（SAF）：docUriStr 为 document uri；成功返回 true */
    public static boolean deleteDocument(Context ctx, String docUriStr) {
        try {
            return DocumentsContract.deleteDocument(ctx.getContentResolver(), Uri.parse(docUriStr));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 复制：源（真实路径或 content:// uri）→ 目标目录（真实路径或 content:// uri），递归。
     * 真实→真实请走 FileOps.copy（本方法不处理该组合）。须在后台线程调用。
     */
    public static boolean copyInto(Context ctx, String src, String destDir, String name, StringBuilder err) {
        try {
            boolean srcSaf = isSafUri(src);
            boolean dstSaf = isSafUri(destDir);
            if (srcSaf && dstSaf) return copySafToSaf(ctx, src, destDir, name, err);
            if (srcSaf) return copySafToFile(ctx, src, new File(destDir, name), err);
            if (dstSaf) return copyFileToSaf(ctx, new File(src), destDir, name, err);
            return false;
        } catch (Throwable t) {
            if (err != null) err.append(t.getMessage()).append(' ');
            return false;
        }
    }

    /** 统计 document 直接子条目数（属性对话框用） */
    public static int childCount(Context ctx, String docUriStr) {
        return listChildren(ctx, docUriStr).size();
    }

    // ── SAF 复制内部实现 ──

    private static boolean copySafToSaf(Context ctx, String srcDoc, String destDirUri, String name, StringBuilder err) {
        // ⭐2 同 provider 且 API24+：DocumentsContract.copyDocument 零拷贝（provider 内部复制），
        // 不支持（UnsupportedOperationException）或失败则降级流式递归复制（MaterialFiles 同款策略）
        String destParent = writableParentUri(destDirUri);
        if (destParent != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && sameAuthority(srcDoc, destDirUri)) {
            try {
                Uri copied = DocumentsContract.copyDocument(
                        ctx.getContentResolver(), Uri.parse(srcDoc), Uri.parse(destParent));
                // ⭐4 复制后验证目标有效性（参考 Ghost Commander SAFEngines："returned ok is not
                // reliable"——零拷贝接口返回非 null 不代表目标真可读，queryMime 二次确认防假成功）
                if (copied != null && queryMime(ctx, copied.toString()) != null) return true;
            } catch (Throwable ignored) {
                // 降级流式复制
            }
        }
        String mime = queryMime(ctx, srcDoc);
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
            String newDir = createDocument(ctx, destDirUri, name, true);
            if (newDir == null) {
                if (err != null) err.append("创建目录失败 ").append(name).append(' ');
                return false;
            }
            boolean ok = true;
            for (String[] c : listChildren(ctx, srcDoc)) {
                if (!copySafToSaf(ctx, c[0], newDir, c[1], err)) ok = false;
            }
            if (!ok) {
                // ⭐1 复制失败清理已创建的目标目录，防残留垃圾
                try { deleteDocument(ctx, newDir); } catch (Throwable ignored) {}
            }
            return ok;
        }
        String newUri = createDocument(ctx, destDirUri, name, false);
        if (newUri == null) {
            if (err != null) err.append("创建文件失败 ").append(name).append(' ');
            return false;
        }
        try (InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(srcDoc));
             OutputStream out = ctx.getContentResolver().openOutputStream(Uri.parse(newUri))) {
            if (in == null || out == null) throw new IOException("open stream failed");
            copyStream(in, out);
            // ⭐4 复制后验证（参考 Ghost Commander SAFEngines："returned ok is not reliable"——
            // 流复制写完不代表 provider 真落盘，queryMime 为空视为假成功，走下方失败清理）
            if (queryMime(ctx, newUri) == null) throw new IOException("verify failed: " + name);
            return true;
        } catch (Throwable t) {
            // ⭐1 流复制失败：清理已创建的目标文件，防残留
            try { deleteDocument(ctx, newUri); } catch (Throwable ignored) {}
            if (err != null) err.append(name).append(':').append(t.getMessage()).append(' ');
            return false;
        }
    }

    private static boolean copySafToFile(Context ctx, String srcDoc, File dest, StringBuilder err) {
        try {
            String mime = queryMime(ctx, srcDoc);
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                if (!dest.exists() && !dest.mkdirs()) throw new IOException("mkdir " + dest);
                boolean ok = true;
                for (String[] c : listChildren(ctx, srcDoc)) {
                    if (!copySafToFile(ctx, c[0], new File(dest, c[1]), err)) ok = false;
                }
                return ok;
            }
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("mkdir " + parent);
            try (InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(srcDoc));
                 OutputStream out = new FileOutputStream(dest)) {
                if (in == null) throw new IOException("open stream failed");
                copyStream(in, out);
                return true;
            }
        } catch (Throwable t) {
            if (err != null) err.append(t.getMessage()).append(' ');
            return false;
        }
    }

    private static boolean copyFileToSaf(Context ctx, File src, String destDirUri, String name, StringBuilder err) {
        try {
            if (src.isDirectory()) {
                String newDir = createDocument(ctx, destDirUri, name, true);
                if (newDir == null) {
                    if (err != null) err.append("创建目录失败 ").append(name).append(' ');
                    return false;
                }
                boolean ok = true;
                File[] children = src.listFiles();
                if (children != null) {
                    for (File c : children) {
                        if (!copyFileToSaf(ctx, c, newDir, c.getName(), err)) ok = false;
                    }
                }
                if (!ok) {
                    // ⭐1 复制失败清理已创建的目标目录，防残留垃圾
                    try { deleteDocument(ctx, newDir); } catch (Throwable ignored) {}
                }
                return ok;
            }
            String newUri = createDocument(ctx, destDirUri, name, false);
            if (newUri == null) {
                if (err != null) err.append("创建文件失败 ").append(name).append(' ');
                return false;
            }
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = ctx.getContentResolver().openOutputStream(Uri.parse(newUri))) {
                if (out == null) throw new IOException("open stream failed");
                copyStream(in, out);
                // ⭐4 复制后验证（Ghost Commander 借鉴）：真实文件→SAF 写完 queryMime 二次确认
                if (queryMime(ctx, newUri) == null) throw new IOException("verify failed: " + name);
                return true;
            } catch (Throwable t) {
                // ⭐1 流复制失败：清理已创建的目标文件，防残留
                try { deleteDocument(ctx, newUri); } catch (Throwable ignored) {}
                if (err != null) err.append(t.getMessage()).append(' ');
                return false;
            }
        } catch (Throwable t) {
            if (err != null) err.append(t.getMessage()).append(' ');
            return false;
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        // 512KB 缓冲（参考 Ghost Commander SAFEngines BUFSZ=524288）：大文件复制显著提速，
        // 减少 read/write 系统调用次数；64KB 在小文件场景差异不大，大文件收益明显
        byte[] buf = new byte[512 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    /** 查询 document 的 MIME（失败返回 null） */
    private static String queryMime(Context ctx, String docUri) {
        try {
            Cursor c = ctx.getContentResolver().query(Uri.parse(docUri),
                    new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) return c.getString(0);
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 解码 docId 路径（防自嵌套比较用）：content://auth/tree/xxx/document/primary:DCIM/sub → primary:DCIM/sub */
    public static String docPathOf(String uriStr) {
        if (uriStr == null) return null;
        String s = uriStr;
        int di = s.indexOf("/document/");
        if (di < 0) return null;
        String docPart = s.substring(di + "/document/".length());
        String dec = Uri.decode(docPart);
        return dec == null ? docPart : dec;
    }

    /** 列出 document 直接子条目（{document uri, 显示名, mime}），供复制递归/计数/重名检查 */
    public static List<String[]> listChildren(Context ctx, String docUriStr) {
        List<String[]> out = new ArrayList<>();
        try {
            Uri children = childrenUri(Uri.parse(docUriStr));
            if (children == null) return out;
            Cursor c = queryChildrenCursor(ctx, children);
            if (c == null) return out;
            try {
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    if (id == null || id.isEmpty()) continue;
                    if (name == null || name.isEmpty()) {
                        String dec = Uri.decode(id);
                        if (dec != null) {
                            int slash = dec.lastIndexOf('/');
                            name = slash >= 0 ? dec.substring(slash + 1) : dec;
                        }
                        if (name == null || name.isEmpty()) name = id;
                    }
                    out.add(new String[]{documentUri(Uri.parse(docUriStr), id), name, mime});
                }
            } finally {
                c.close();
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 按文件名取 MIME（无扩展名 → application/octet-stream） */
    private static String mimeForName(String name) {
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0) ext = name.substring(dot + 1).toLowerCase();
        String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime == null ? "application/octet-stream" : mime;
    }

    // ── MaterialFiles 借鉴优化（2026-08-16 合入） ──

    /**
     * ⭐4 查询子文档 cursor，处理 EXTRA_LOADING（provider 仍在加载时返回空列表的假象）：
     * extras 标记 EXTRA_LOADING=true → 注册 ContentObserver 等待数据变更（最多 5 秒）
     * → 重新查询。MaterialFiles DocumentResolver.queryChildren 同款策略。
     */
    private static Cursor queryChildrenCursor(Context ctx, Uri childrenUri) {
        Cursor c = ctx.getContentResolver().query(childrenUri, PROJ, null, null, null);
        if (c == null || c.getExtras() == null
                || !c.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
            return c;
        }
        // 主线程不阻塞（reload 主线程同步调用，等待会 ANR）→ 直接返回首次结果（可能空）
        if (Looper.myLooper() == Looper.getMainLooper()) return c;
        final CountDownLatch latch = new CountDownLatch(1);
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) { latch.countDown(); }
        };
        try {
            c.registerContentObserver(observer);
            try {
                latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            c.unregisterContentObserver(observer);
            c.close();
            // 等待后重新查询（此时 provider 应已完成加载）
            return ctx.getContentResolver().query(childrenUri, PROJ, null, null, null);
        } catch (Throwable t) {
            return c;
        }
    }

    /** 两个 uri 是否同一 provider（authority 相同，copyDocument/moveDocument 的前提） */
    private static boolean sameAuthority(String a, String b) {
        try {
            String aa = Uri.parse(a).getAuthority();
            String bb = Uri.parse(b).getAuthority();
            return aa != null && aa.equals(bb);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ⭐3 SAF→SAF 移动（剪切粘贴专用，MaterialFiles DocumentResolver.move 三策略）：
     * ①同父目录 → renameDocument（原地改名，零 IO）；
     * ②同 provider 且 API24+ → DocumentsContract.moveDocument（provider 内部移动）；
     * ③否则 → copyInto 复制 + 成功后 deleteDocument 删源（复制失败不删源，防丢数据）。
     */
    public static boolean moveInto(Context ctx, String srcDoc, String destDirUri, String name, StringBuilder err) {
        try {
            String srcParent = parentUriOf(srcDoc);
            String destParent = writableParentUri(destDirUri);
            if (srcParent == null || destParent == null) {
                if (err != null) err.append("uri 格式无效 ").append(srcDoc).append(' ');
                return false;
            }
            // ①同父：直接重命名（等价于移动，零 IO）
            if (srcParent.equals(destParent)) {
                if (renameDocument(ctx, srcDoc, name) != null) return true;
                if (err != null) err.append("重命名失败 ").append(name).append(' ');
                return false;
            }
            // ②同 provider 且 API24+：moveDocument（provider 内部移动，零拷贝）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && sameAuthority(srcDoc, destDirUri)) {
                try {
                    Uri moved = DocumentsContract.moveDocument(
                            ctx.getContentResolver(), Uri.parse(srcDoc),
                            Uri.parse(srcParent), Uri.parse(destParent));
                    if (moved != null) return true;
                } catch (Throwable ignored) {
                    // 降级复制+删源
                }
            }
            // ③复制 + 删源（复制失败不删源，防丢数据）
            if (!copyInto(ctx, srcDoc, destDirUri, name, err)) return false;
            if (!deleteDocument(ctx, srcDoc)) {
                if (err != null) err.append("复制成功但删源失败 ").append(name).append(' ');
                return false;
            }
            return true;
        } catch (Throwable t) {
            if (err != null) err.append(t.getMessage()).append(' ');
            return false;
        }
    }

    /** 公开入口：document uri 的父 uri（tree uri 无父返回 null） */
    public static String parentUriOf(String uriStr) {
        try {
            return parentOf(Uri.parse(uriStr));
        } catch (Throwable t) {
            return null;
        }
    }
}