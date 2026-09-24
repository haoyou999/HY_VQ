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
 * 内置音视频播放器（原生 VideoView / MediaPlayer，无需第三方依赖）。
 *
 * <p>文件管理点击音视频文件时直接进入本页播放，不再走 {@code Intent.ACTION_VIEW}
 * 交给系统选择外部应用。支持：播放/暂停、拖动进度、时间显示、
 * 点击画面切换控制条、屏幕常亮、音频文件显示专属占位界面。</p>
 */
public class MediaPlayerActivity extends Activity {

    public static final String EXTRA_PATH = "media_path";    // 本地绝对路径
    public static final String EXTRA_URI = "media_uri";      // content:// (SAF)
    public static final String EXTRA_TITLE = "media_title";

    private static final Set<String> VIDEO_EXTS = new HashSet<>(Arrays.asList(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "webm", "ts", "m4v",
            "mpg", "mpeg", "rm", "rmvb", "vob", "f4v", "m2ts", "mts", "ogv", "mxf"));

    private VideoView videoView;
    private View placeholder, controls, topBar;
    private ImageView btnPlay;
    private SeekBar seekBar;
    private TextView tvPos, tvDur;

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
        ImageView btnBack = findViewById(R.id.btn_player_back);
        seekBar = findViewById(R.id.seek_player);
        tvPos = findViewById(R.id.tv_player_pos);
        tvDur = findViewById(R.id.tv_player_dur);
        TextView tvTitle = findViewById(R.id.tv_player_title);
        TextView tvAudioName = findViewById(R.id.tv_audio_name);
        View root = findViewById(R.id.player_root);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        String uriStr = getIntent().getStringExtra(EXTRA_URI);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title == null || title.isEmpty()) {
            title = path != null ? new java.io.File(path).getName() : "媒体播放";
        }
        tvTitle.setText(title);
        tvAudioName.setText(title);

        boolean isVideo = isVideoFile(title);
        if (!isVideo) {
            // 音频：隐藏画面层，显示专属占位
            placeholder.setVisibility(View.VISIBLE);
            videoView.setVisibility(View.INVISIBLE);
        }

        // 点击画面切换控制条显隐
        root.setOnClickListener(v -> toggleControls());
        btnBack.setOnClickListener(v -> finish());
        btnPlay.setOnClickListener(v -> togglePlay());
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
            handler.post(ticker);
        });
        videoView.setOnCompletionListener(mp -> updatePlayIcon(false));
        videoView.setOnErrorListener((mp, what, extra) -> {
            tvDur.setText("无法播放");
            ModuleUiKit.toast(this, "该格式可能不被系统解码器支持");
            return true;
        });

        try {
            if (uriStr != null && !uriStr.isEmpty()) {
                videoView.setVideoURI(Uri.parse(uriStr));
            } else if (path != null && !path.isEmpty()) {
                videoView.setVideoPath(path);
            } else {
                ModuleUiKit.toast(this, "缺少播放地址");
                finish();
                return;
            }
            videoView.requestFocus();
            videoView.start();      // 触发 prepare
        } catch (Throwable t) {
            ModuleUiKit.toast(this, "打开失败：" + t.getMessage());
            finish();
        }
    }

    private boolean isVideoFile(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return true;   // 未知类型按视频处理（有画面层）
        return VIDEO_EXTS.contains(name.substring(dot + 1).toLowerCase());
    }

    private void togglePlay() {
        if (!prepared) return;
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
