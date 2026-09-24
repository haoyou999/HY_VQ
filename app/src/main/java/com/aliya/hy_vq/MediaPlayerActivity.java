package com.aliya.hy_vq;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import com.aliya.hy_vq.module.ModuleUiKit;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 内置视频播放器（原生 VideoView，无需第三方依赖）。
 *
 * <p>支持**同目录左右切换**：打开时收到当前目录下的视频列表与起始索引，
 * 点「上一个 / 下一个」切换并自动播放（列表由文件管理收集同目录同类型文件）。</p>
 *
 * <p>注意：本页只处理**视频**。音频走文件管理里的弹窗播放器（原生 MediaPlayer）——
 * {@code VideoView} 依赖 Surface，把 View 隐藏后音频无输出，故音频不走本页。</p>
 */
public class MediaPlayerActivity extends Activity {

    public static final String EXTRA_PATH = "media_path";     // 兼容单文件调用
    public static final String EXTRA_URI = "media_uri";       // 兼容单文件调用
    public static final String EXTRA_TITLE = "media_title";
    public static final String EXTRA_LIST = "media_list";     // String[] 播放列表
    public static final String EXTRA_INDEX = "media_index";   // 起始索引
    public static final String EXTRA_IS_URI = "media_is_uri"; // 列表项是否为 content://

    private static final Set<String> VIDEO_EXTS = new HashSet<>(Arrays.asList(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "webm", "ts", "m4v",
            "mpg", "mpeg", "rm", "rmvb", "vob", "f4v", "m2ts", "mts", "ogv", "mxf"));

    private VideoView videoView;
    private View placeholder, controls, topBar;
    private ImageView btnPlay, btnPrev, btnNext;
    private SeekBar seekBar;
    private TextView tvPos, tvDur, tvTitle, tvCounter, tvAudioName;

    private String[] playlist;
    private int idx = 0;
    private boolean isUri = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean dragging = false;
    private boolean controlsVisible = true;
    private boolean prepared = false;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            syncProgress();
            handler.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_media_player);

        videoView = findViewById(R.id.video_view);
        placeholder = findViewById(R.id.audio_placeholder);
        controls = findViewById(R.id.player_controls);
        topBar = findViewById(R.id.player_top);
        btnPlay = findViewById(R.id.btn_player_play);
        btnPrev = findViewById(R.id.btn_player_prev);
        btnNext = findViewById(R.id.btn_player_next);
        ImageView btnBack = findViewById(R.id.btn_player_back);
        seekBar = findViewById(R.id.seek_player);
        tvPos = findViewById(R.id.tv_player_pos);
        tvDur = findViewById(R.id.tv_player_dur);
        tvTitle = findViewById(R.id.tv_player_title);
        tvCounter = findViewById(R.id.tv_player_counter);
        tvAudioName = findViewById(R.id.tv_audio_name);
        View root = findViewById(R.id.player_root);

        // ── 组装播放列表（优先取列表，兼容旧的单文件传参）──
        String[] list = getIntent().getStringArrayExtra(EXTRA_LIST);
        int startIdx = getIntent().getIntExtra(EXTRA_INDEX, 0);
        isUri = getIntent().getBooleanExtra(EXTRA_IS_URI, false);
        if (list == null || list.length == 0) {
            String path = getIntent().getStringExtra(EXTRA_PATH);
            String uriStr = getIntent().getStringExtra(EXTRA_URI);
            String single = (path != null && !path.isEmpty()) ? path : uriStr;
            if (single == null || single.isEmpty()) {
                ModuleUiKit.toast(this, "缺少播放地址");
                finish();
                return;
            }
            isUri = (uriStr != null && !uriStr.isEmpty());
            list = new String[]{single};
            startIdx = 0;
        }
        playlist = list;
        idx = Math.max(0, Math.min(startIdx, playlist.length - 1));

        root.setOnClickListener(v -> toggleControls());
        btnBack.setOnClickListener(v -> finish());
        btnPlay.setOnClickListener(v -> togglePlay());
        btnPrev.setOnClickListener(v -> step(-1));
        btnNext.setOnClickListener(v -> step(1));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) tvPos.setText(fmt(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {
                dragging = true;
            }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                if (prepared) videoView.seekTo(sb.getProgress());
            }
        });

        videoView.setOnPreparedListener(mp -> {
            prepared = true;
            int dur = videoView.getDuration();
            seekBar.setMax(dur > 0 ? dur : 0);
            tvDur.setText(fmt(dur > 0 ? dur : 0));
            videoView.start();
            updatePlayIcon(true);
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        });
        videoView.setOnCompletionListener(mp -> {
            updatePlayIcon(false);
            // 播完自动切下一个；已是最后一个则停在结尾
            if (idx < playlist.length - 1) step(1);
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            tvDur.setText("无法播放");
            ModuleUiKit.toast(this, "该格式可能不被系统解码器支持");
            return true;
        });

        playAt(idx);
    }

    /** 切到第 i 个并自动播放 */
    private void playAt(int i) {
        if (playlist == null || i < 0 || i >= playlist.length) return;
        idx = i;
        final String path = playlist[i];
        String name = baseName(path);
        tvTitle.setText(name);
        tvCounter.setText((i + 1) + " / " + playlist.length);

        boolean isVideo = isVideoFile(name);
        placeholder.setVisibility(isVideo ? View.GONE : View.VISIBLE);
        videoView.setVisibility(isVideo ? View.VISIBLE : View.INVISIBLE);
        if (!isVideo) tvAudioName.setText(name);

        prepared = false;
        updatePlayIcon(false);
        seekBar.setProgress(0);
        seekBar.setMax(0);
        tvPos.setText("00:00");
        tvDur.setText("00:00");
        btnPrev.setAlpha(i > 0 ? 1f : 0.35f);
        btnNext.setAlpha(i < playlist.length - 1 ? 1f : 0.35f);

        try {
            videoView.stopPlayback();
        } catch (Throwable ignored) {
        }
        try {
            if (isUri) {
                videoView.setVideoURI(Uri.parse(path));
            } else {
                videoView.setVideoPath(path);
            }
            videoView.requestFocus();
            videoView.start();      // 触发 prepare
        } catch (Throwable t) {
            ModuleUiKit.toast(this, "打开失败：" + t.getMessage());
        }
    }

    private void step(int delta) {
        int n = idx + delta;
        if (n < 0) {
            ModuleUiKit.toast(this, "已经是第一个");
            return;
        }
        if (n >= playlist.length) {
            ModuleUiKit.toast(this, "已经是最后一个");
            return;
        }
        playAt(n);
    }

    private static String baseName(String p) {
        if (p == null) return "";
        int s = p.lastIndexOf('/');
        return (s >= 0 && s < p.length() - 1) ? p.substring(s + 1) : p;
    }

    private boolean isVideoFile(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return true;
        return VIDEO_EXTS.contains(name.substring(dot + 1).toLowerCase());
    }

    private void togglePlay() {
        if (!prepared) {
            playAt(idx);
            return;
        }
        if (videoView.isPlaying()) {
            videoView.pause();
            updatePlayIcon(false);
        } else {
            videoView.start();
            updatePlayIcon(true);
        }
    }

    private void updatePlayIcon(boolean playing) {
        btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void toggleControls() {
        controlsVisible = !controlsVisible;
        controls.setVisibility(controlsVisible ? View.VISIBLE : View.GONE);
        topBar.setVisibility(controlsVisible ? View.VISIBLE : View.GONE);
    }

    private void syncProgress() {
        if (!prepared || dragging) return;
        try {
            int pos = videoView.getCurrentPosition();
            seekBar.setProgress(pos);
            tvPos.setText(fmt(pos));
            updatePlayIcon(videoView.isPlaying());
        } catch (Throwable ignored) {
        }
    }

    private String fmt(int ms) {
        if (ms < 0) ms = 0;
        int total = ms / 1000;
        return String.format(java.util.Locale.CHINA, "%02d:%02d", total / 60, total % 60);
    }

    @Override protected void onPause() {
        super.onPause();
        try {
            if (videoView.isPlaying()) videoView.pause();
        } catch (Throwable ignored) {
        }
        updatePlayIcon(false);
        handler.removeCallbacks(ticker);
    }

    @Override protected void onResume() {
        super.onResume();
        if (prepared) handler.post(ticker);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(ticker);
        try {
            videoView.stopPlayback();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
