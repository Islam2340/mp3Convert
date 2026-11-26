package com.mp3master.app;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;

import java.util.Locale;

public class TrimEditorController {

    private final Context context;
    private final AudioTimelineView timeline;
    private final TextView tvZoomLabel;
    private final TextView tvCurrentPos;
    private final TextView tvStart;
    private final TextView tvDuration;
    private final TextView tvEnd;
    private final MaterialButton btnPlayPause;
    private final ChipGroup chipGroupZoom;

    private AudioUtils.AudioInfo audioInfo;
    private MediaPlayer mediaPlayer;
    private boolean isPlaying = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable playbackUpdater = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && isPlaying) {
                int pos = mediaPlayer.getCurrentPosition();
                timeline.setPlayheadMs(pos, true);
                tvCurrentPos.setText(AudioUtils.formatTimePreciseMs(pos));
                if (pos >= timeline.getTrimEndMs()) {
                    stopPlayback();
                } else {
                    handler.postDelayed(this, 80);
                }
            }
        }
    };

    public TrimEditorController(Context context, View root) {
        this.context = context;
        this.timeline = root.findViewById(R.id.audioTimeline);
        this.tvZoomLabel = root.findViewById(R.id.tvZoomLabel);
        this.tvCurrentPos = root.findViewById(R.id.tvTrimCurrentPos);
        this.tvStart = root.findViewById(R.id.tvTrimStart);
        this.tvDuration = root.findViewById(R.id.tvTrimDuration);
        this.tvEnd = root.findViewById(R.id.tvTrimEnd);
        this.btnPlayPause = root.findViewById(R.id.btnTrimPlayPause);
        this.chipGroupZoom = root.findViewById(R.id.chipGroupZoom);

        timeline.setListener(new AudioTimelineView.OnTimelineChangeListener() {
            @Override
            public void onTrimRangeChanged(long startMs, long endMs, boolean fromUser) {
                updateLabels();
            }

            @Override
            public void onPlayheadSeek(long positionMs, boolean fromUser) {
                tvCurrentPos.setText(AudioUtils.formatTimePreciseMs(positionMs));
                if (mediaPlayer != null) {
                    try {
                        mediaPlayer.seekTo((int) positionMs);
                    } catch (Exception ignored) {
                    }
                }
            }

            @Override
            public void onScaleChanged(float secondsPerCm, boolean isFitAll) {
                if (isFitAll) {
                    tvZoomLabel.setText("Масштаб: весь трек");
                } else {
                    tvZoomLabel.setText(String.format(Locale.US, "Масштаб: %.1f с/см", secondsPerCm));
                }
            }
        });

        chipGroupZoom.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chipZoomFit) {
                timeline.setFitToWidth();
            } else if (id == R.id.chipZoom10s) {
                timeline.setSecondsPerCm(10f);
            } else if (id == R.id.chipZoom5s) {
                timeline.setSecondsPerCm(5f);
            } else if (id == R.id.chipZoom2s) {
                timeline.setSecondsPerCm(2f);
            } else if (id == R.id.chipZoom1s) {
                timeline.setSecondsPerCm(1f);
            }
        });

        btnPlayPause.setOnClickListener(v -> togglePlayback());

        root.findViewById(R.id.btnTrimPlayEnd).setOnClickListener(v -> playEnding());

        root.findViewById(R.id.btnTrimJumpStart).setOnClickListener(v -> {
            long start = timeline.getTrimStartMs();
            timeline.setPlayheadMs(start, true);
            timeline.scrollToMs(start);
            tvCurrentPos.setText(AudioUtils.formatTimePreciseMs(start));
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.seekTo((int) start);
                } catch (Exception ignored) {
                }
            }
        });

        root.findViewById(R.id.btnStartMinus).setOnClickListener(v -> adjustRange(-500L, 0L));
        root.findViewById(R.id.btnStartPlus).setOnClickListener(v -> adjustRange(500L, 0L));
        root.findViewById(R.id.btnEndMinus).setOnClickListener(v -> adjustRange(0L, -500L));
        root.findViewById(R.id.btnEndPlus).setOnClickListener(v -> adjustRange(0L, 500L));

        root.findViewById(R.id.btnSetStartHere).setOnClickListener(v -> {
            long pos = timeline.getPlayheadMs();
            long end = timeline.getTrimEndMs();
            if (pos < end - 300L) {
                timeline.setTrimRange(pos, end);
                updateLabels();
            }
        });

        root.findViewById(R.id.btnSetEndHere).setOnClickListener(v -> {
            long pos = timeline.getPlayheadMs();
            long start = timeline.getTrimStartMs();
            if (pos > start + 300L) {
                timeline.setTrimRange(start, pos);
                updateLabels();
            }
        });
    }

    public void bindAudio(AudioUtils.AudioInfo info, long startMs, long endMs) {
        stopPlayback();
        this.audioInfo = info;
        long validEnd = (endMs > startMs && endMs <= info.durationMs) ? endMs : info.durationMs;
        timeline.setAudioData(info.durationMs, startMs, validEnd);
        chipGroupZoom.check(R.id.chipZoomFit);
        tvCurrentPos.setText(AudioUtils.formatTimePreciseMs(startMs));
        updateLabels();

        AudioUtils.extractWaveformAsync(context, info.uri, info.durationMs, peaks -> {
            if (audioInfo == info) {
                timeline.setWaveformPeaks(peaks);
            }
        });
    }

    public void resetRange() {
        if (audioInfo == null) return;
        stopPlayback();
        timeline.setTrimRange(0L, audioInfo.durationMs);
        timeline.setPlayheadMs(0L, false);
        tvCurrentPos.setText(AudioUtils.formatTimePreciseMs(0L));
        updateLabels();
    }

    public long getStartMs() {
        return timeline.getTrimStartMs();
    }

    public long getEndMs() {
        return timeline.getTrimEndMs();
    }

    private void adjustRange(long deltaStartMs, long deltaEndMs) {
        if (audioInfo == null) return;
        long start = timeline.getTrimStartMs() + deltaStartMs;
        long end = timeline.getTrimEndMs() + deltaEndMs;
        start = Math.max(0L, Math.min(start, end - 300L));
        end = Math.min(audioInfo.durationMs, Math.max(end, start + 300L));
        timeline.setTrimRange(start, end);
        updateLabels();
    }

    private void updateLabels() {
        long start = timeline.getTrimStartMs();
        long end = timeline.getTrimEndMs();
        tvStart.setText("Начало: " + AudioUtils.formatTimePreciseMs(start));
        tvEnd.setText("Конец: " + AudioUtils.formatTimePreciseMs(end));
        tvDuration.setText("Длина: " + AudioUtils.formatTimePreciseMs(Math.max(0L, end - start)));
    }

    private void playEnding() {
        if (audioInfo == null) return;
        long end = timeline.getTrimEndMs();
        long start = timeline.getTrimStartMs();
        long jumpPos = Math.max(start, end - 5000L);
        timeline.setPlayheadMs(jumpPos, false);
        timeline.scrollToMs(jumpPos);
        tvCurrentPos.setText(AudioUtils.formatTimePreciseMs(jumpPos));
        startPlaybackAt(jumpPos);
    }

    private void togglePlayback() {
        if (isPlaying) {
            stopPlayback();
            return;
        }
        if (audioInfo == null) return;
        long pos = timeline.getPlayheadMs();
        if (pos < timeline.getTrimStartMs() || pos >= timeline.getTrimEndMs() - 150L) {
            pos = timeline.getTrimStartMs();
        }
        startPlaybackAt(pos);
    }

    private void startPlaybackAt(long positionMs) {
        if (audioInfo == null) return;
        stopPlayback();
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(context, audioInfo.uri);
            mediaPlayer.prepare();
            mediaPlayer.seekTo((int) positionMs);
            mediaPlayer.start();
            isPlaying = true;
            btnPlayPause.setText("Пауза");
            btnPlayPause.setIconResource(R.drawable.ic_pause);
            handler.post(playbackUpdater);
        } catch (Exception e) {
            Toast.makeText(context, "Не удалось воспроизвести: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            stopPlayback();
        }
    }

    public void stopPlayback() {
        isPlaying = false;
        handler.removeCallbacks(playbackUpdater);
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {
            }
            mediaPlayer = null;
        }
        btnPlayPause.setText("Играть");
        btnPlayPause.setIconResource(R.drawable.ic_play);
    }
}
