package com.aliya.hy_vq.filemgr;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.aliya.hy_vq.R;
import com.aliya.hy_vq.access.AccessUtil;
import com.aliya.hy_vq.access.FileEntry;
import com.aliya.hy_vq.module.ModuleUiKit;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文件管理单列混排适配器（文件夹+文件同列表，对齐 MT 管理器单列模式）。
 *
 * <p>条目类型驱动图标与副文本：真实目录=紫文件夹+时间；真实文件=青文件+大小·时间；
 * 虚拟目录=紫文件夹（名称旁「虚」角标）+副文本；虚拟文件=灰文件；
 * 符号链接=带 →目标的链接标记。多选模式显示左侧勾选框。</p>
 */
public class FileListAdapter extends RecyclerView.Adapter<FileListAdapter.VH> {

    // ── 格式支持：扩展名 → 图标分类（与 FileManagerModule 分类白名单同源扩充） ──
    private static final Set<String> IMG_EXTS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg",
            "ico", "tif", "tiff", "raw", "cr2", "nef", "arw", "dng", "psd", "ai"));
    private static final Set<String> VIDEO_EXTS = new HashSet<>(Arrays.asList(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "webm", "ts", "m4v",
            "mpg", "mpeg", "rm", "rmvb", "vob", "f4v", "m2ts", "mts", "ogv", "mxf"));
    private static final Set<String> AUDIO_EXTS = new HashSet<>(Arrays.asList(
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "amr",
            "aiff", "ape", "wv", "mid", "midi", "ac3", "dts", "mka", "ra", "au", "caf"));
    private static final Set<String> DOC_EXTS = new HashSet<>(Arrays.asList(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "epub",
            "csv", "rtf", "odt", "ods", "odp", "pages", "numbers", "key",
            "html", "htm", "xml", "json", "log", "chm", "mobi", "azw3", "tex"));
    private static final Set<String> ARCHIVE_EXTS = new HashSet<>(Arrays.asList(
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "iso", "jar"));

    /** 行交互回调 */
    public interface Listener {
        void onClick(FileEntry e);
        void onLongClick(FileEntry e, View anchor);
        /** 多选状态变化（勾选/取消） */
        void onSelectionChanged();
    }

    private final Context context;
    private List<FileEntry> entries;
    private Set<FileEntry> selected;
    private boolean multiMode;
    private final Listener listener;

    public FileListAdapter(Context context, List<FileEntry> entries,
                           Set<FileEntry> selected, Listener listener) {
        this.context = context;
        this.entries = entries;
        this.selected = selected;
        this.listener = listener;
    }

    /** 刷新数据源（主线程） */
    public void setData(List<FileEntry> entries) {
        this.entries = entries;
        notifyDataSetChanged();
    }

    public void setMultiMode(boolean multiMode) {
        this.multiMode = multiMode;
        notifyDataSetChanged();
    }

    public void setSelected(Set<FileEntry> selected) {
        this.selected = selected;
        notifyDataSetChanged();
    }

    @Override
    public VH onCreateViewHolder(ViewGroup parent, int viewType) {
        return new VH(buildRow());
    }

    @Override
    public void onBindViewHolder(VH h, int position) {
        FileEntry e = entries.get(position);
        h.name.setText(e.name);
        h.sub.setText(buildSub(e));
        boolean checked = selected != null && selected.contains(e);
        h.check.setVisibility(multiMode ? View.VISIBLE : View.GONE);
        h.check.setChecked(checked);
        int surface = checked
                ? ModuleUiKit.color(context, com.google.android.material.R.attr.colorPrimaryContainer)
                : ModuleUiKit.color(context, com.google.android.material.R.attr.colorSurfaceContainerLow);
        h.itemView.setBackground(makeBg(surface, checked));
        // 图标配色：文件夹=主色，文件=次要色，虚拟/链接降饱和度（灰色系）
        h.icon.setImageResource(iconRes(e));
        int iconColor;
        switch (e.type) {
            case VIRTUAL_DIR:
            case VIRTUAL_FILE:
                iconColor = ModuleUiKit.color(context, com.google.android.material.R.attr.colorOutline);
                break;
            case LINK:
                iconColor = ModuleUiKit.color(context, com.google.android.material.R.attr.colorTertiary);
                break;
            case DIR:
                iconColor = ModuleUiKit.color(context, com.google.android.material.R.attr.colorPrimary);
                break;
            default:
                // 多彩扁平化（Material 流行趋势）：文件图标按类型主题色
                iconColor = fileColor(e);
                break;
        }
        h.icon.setColorFilter(iconColor);
        // 虚拟条目标记
        h.badge.setVisibility(e.virtual ? View.VISIBLE : View.GONE);
        h.itemView.setOnClickListener(v -> {
            if (multiMode) {
                toggle(e, h.check);
            } else if (listener != null) {
                listener.onClick(e);
            }
        });
        h.itemView.setOnLongClickListener(v -> {
            // ".."强制条目不参与长按（多选模式也不勾选）
            if (e.parent) return true;
            if (listener != null) {
                if (!multiMode) listener.onLongClick(e, v);
                else toggle(e, h.check);
            }
            return true;
        });
        h.check.setOnClickListener(v -> toggle(e, h.check));
    }

    /** 图标按类型区分：文件夹=ic_folder；文件按扩展名归 6 类（图片/视频/音频/文档/APK/压缩包） */
    private int iconRes(FileEntry e) {
        if (e.type == FileEntry.Type.LINK) return R.drawable.ic_folder;
        if (e.isDir()) return R.drawable.ic_folder;
        String ext = extOf(e.name);
        if (IMG_EXTS.contains(ext)) return R.drawable.ic_img;
        if (VIDEO_EXTS.contains(ext)) return R.drawable.ic_video;
        if (AUDIO_EXTS.contains(ext)) return R.drawable.ic_audio;
        if (DOC_EXTS.contains(ext)) return R.drawable.ic_doc;
        if (ext.equals("apk")) return R.drawable.ic_apk;
        if (ARCHIVE_EXTS.contains(ext)) return R.drawable.ic_archive;
        return R.drawable.ic_file;
    }

    /** 取小写扩展名（无点）；无扩展名返回空串 */
    private static String extOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** 多彩扁平化文件图标配色（对齐 Material 流行趋势）：
     *  图片=绿 / 视频=紫 / 音频=橙 / 文档=蓝 / APK=青 / 压缩包=琥珀；未知=主题次要色 */
    private int fileColor(FileEntry e) {
        String ext = extOf(e.name);
        if (IMG_EXTS.contains(ext)) return 0xFF66BB6A;
        if (VIDEO_EXTS.contains(ext)) return 0xFFAB47BC;
        if (AUDIO_EXTS.contains(ext)) return 0xFFFFA726;
        if (DOC_EXTS.contains(ext)) return 0xFF42A5F5;
        if (ext.equals("apk")) return 0xFF26A69A;
        if (ARCHIVE_EXTS.contains(ext)) return 0xFFFFCA28;
        return ModuleUiKit.color(context, com.google.android.material.R.attr.colorSecondary);
    }

    /** 副文本：目录=时间（虚拟=标记），文件=大小·时间，链接=→目标，".."=返回上级 */
    private String buildSub(FileEntry e) {
        if (e.parent) return "返回上级";
        if (e.type == FileEntry.Type.LINK) {
            String base = e.subText == null ? "链接" : e.subText;
            if (e.lastModified > 0) return base + " · " + AccessUtil.formatDate(e.lastModified);
            return base;
        }
        if (e.virtual) {
            String base = e.subText == null ? "虚拟条目" : e.subText;
            if (e.lastModified > 0) return base + " · " + AccessUtil.formatDate(e.lastModified);
            return base;
        }
        if (e.isDir()) {
            return AccessUtil.formatDate(e.lastModified);
        }
        return AccessUtil.formatSize(e.length) + " · " + AccessUtil.formatDate(e.lastModified);
    }

    @Override
    public int getItemCount() {
        return entries == null ? 0 : entries.size();
    }

    private void toggle(FileEntry e, CheckBox check) {
        if (selected == null) return;
        // ".."强制条目不参与多选
        if (e.parent) return;
        if (selected.contains(e)) selected.remove(e);
        else selected.add(e);
        check.setChecked(selected.contains(e));
        if (listener != null) listener.onSelectionChanged();
        notifyItemRangeChanged(0, getItemCount());
    }

    /** 构建行视图：勾选框 + 图标 + 名称/角标/副文本 */
    private View buildRow() {
        int pad = dp(10);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, pad, pad, pad);
        row.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        CheckBox check = new CheckBox(context);
        LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        row.addView(check, checkLp);

        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.ic_folder);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        iconLp.setMarginStart(dp(4));
        row.addView(icon, iconLp);

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginStart(dp(10));
        row.addView(textCol, textLp);

        LinearLayout nameRow = new LinearLayout(context);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        textCol.addView(nameRow);

        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTextColor(ModuleUiKit.color(context, com.google.android.material.R.attr.colorOnSurface));
        name.setTypeface(null, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        nameRow.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = new TextView(context);
        badge.setText("虚");
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        badge.setTextColor(ModuleUiKit.color(context, com.google.android.material.R.attr.colorOnSecondaryContainer));
        badge.setBackground(ModuleUiKit.rounded(context, dp(6),
                ModuleUiKit.color(context, com.google.android.material.R.attr.colorSecondaryContainer), 0));
        int bp = dp(4);
        badge.setPadding(bp, dp(1), bp, dp(1));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.setMarginStart(dp(6));
        nameRow.addView(badge, badgeLp);

        TextView sub = new TextView(context);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setTextColor(ModuleUiKit.color(context, com.google.android.material.R.attr.colorOnSurfaceVariant));
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        textCol.addView(sub, subLp);

        row.setTag(new Object[]{check, icon, name, sub, badge});
        return row;
    }

    private GradientDrawable makeBg(int fill, boolean checked) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(12));
        gd.setColor(fill);
        if (checked) {
            gd.setStroke(dp(1), ModuleUiKit.color(context, com.google.android.material.R.attr.colorPrimary));
        }
        return gd;
    }

    private int dp(int v) {
        return Math.round(context.getResources().getDisplayMetrics().density * v);
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView name, sub, badge;
        final ImageView icon;
        final CheckBox check;

        VH(View v) {
            super(v);
            Object[] tags = (Object[]) v.getTag();
            check = (CheckBox) tags[0];
            icon = (ImageView) tags[1];
            name = (TextView) tags[2];
            sub = (TextView) tags[3];
            badge = (TextView) tags[4];
        }
    }
}