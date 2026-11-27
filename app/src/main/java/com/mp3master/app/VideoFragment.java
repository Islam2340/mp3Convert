package com.mp3master.app;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.slider.RangeSlider;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class VideoFragment extends Fragment {

    private static final int ASPECT_ORIG = 0;
    private static final int ASPECT_16_9 = 1;
    private static final int ASPECT_9_16 = 2;
    private static final int ASPECT_1_1 = 3;
    private static final int ASPECT_4_5 = 4;

    private static final int FILL_BLUR = 0;
    private static final int FILL_CROP = 1;
    private static final int FILL_PAD = 2;

    private static final int AUDIO_ORIG = 0;
    private static final int AUDIO_MUTE = 1;
    private static final int AUDIO_CUSTOM = 2;

    private final List<AudioUtils.VideoInfo> clips = new ArrayList<>();
    private VideoClipAdapter clipAdapter;
    private int activeClipIndex = 0;

    private Uri customAudioUri;
    private int aspectMode = ASPECT_ORIG;
    private int fillMode = FILL_BLUR;
    private int audioMode = AUDIO_ORIG;
    private double speedFactor = 1.0;
    private int targetRes = 0; // 0=orig, 1080, 720, 480

    private boolean isPlayingPreview = false;
    private boolean isUpdatingUi = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private volatile long activeSessionId = -1;

    private Uri lastResultUri;
    private boolean lastResultIsVideo = true;

    // Views
    private MaterialCardView cardEmpty, cardResult;
    private View layoutWorkspace, layoutFillMode, layoutCustomMp3Box, layoutProgress;
    private View panelTrim, panelRotate, panelFormat, panelAudio, panelSpeed, panelQuality;
    private ImageView iconToolTrim, iconToolRotate, iconToolFormat, iconToolAudio, iconToolSpeed, iconToolQuality;
    private VideoView videoView;
    private ImageButton btnCenterPlayPause;
    private TextView tvActiveClipName, tvTotalTimelineDur, tvVideoPreviewBadge, tvActiveTrimRange;
    private TextView tvVideoStart, tvVideoDuration, tvVideoEnd, tvCustomAudioName, tvStatus, tvResultInfo;
    private RangeSlider rangeSlider;
    private ChipGroup chipGroupRotate;
    private MaterialSwitch switchFlipH, switchFlipV;
    private TextInputEditText etOutputName;
    private MaterialButton btnQuickPlay, btnStartExport, btnExtractMp3, btnCancel;
    private LinearProgressIndicator progressBar;

    private final Runnable previewUpdater = new Runnable() {
        @Override
        public void run() {
            if (videoView != null && isPlayingPreview) {
                try {
                    AudioUtils.VideoInfo clip = getActiveClip();
                    if (clip == null) {
                        pauseVideoPreview();
                        return;
                    }
                    int pos = videoView.getCurrentPosition();
                    if (pos >= clip.trimEndMs || !videoView.isPlaying()) {
                        pauseVideoPreview();
                    } else {
                        handler.postDelayed(this, 150);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    };

    private final ActivityResultLauncher<String[]> pickVideosLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris != null && !uris.isEmpty() && getContext() != null) {
                    pauseVideoPreview();
                    boolean wasEmpty = clips.isEmpty();
                    for (Uri uri : uris) {
                        AudioUtils.VideoInfo vi = AudioUtils.getVideoInfo(getContext(), uri);
                        if (vi.durationMs >= 300) {
                            clips.add(vi);
                        }
                    }
                    if (!clips.isEmpty()) {
                        if (wasEmpty) {
                            activeClipIndex = 0;
                            etOutputName.setText(AudioUtils.stripExtension(clips.get(0).displayName) + "_edit");
                        } else {
                            activeClipIndex = clips.size() - 1;
                        }
                        refreshWorkspace();
                    }
                }
            });

    private final ActivityResultLauncher<String[]> pickCustomAudioLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null && getContext() != null) {
                    customAudioUri = uri;
                    tvCustomAudioName.setText("Выбран: " + AudioUtils.getFileName(getContext(), uri));
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_video, container, false);

        cardEmpty = view.findViewById(R.id.cardVideoEmpty);
        layoutWorkspace = view.findViewById(R.id.layoutVideoWorkspace);
        videoView = view.findViewById(R.id.videoViewPreview);
        btnCenterPlayPause = view.findViewById(R.id.btnCenterPlayPause);
        tvActiveClipName = view.findViewById(R.id.tvActiveClipName);
        tvTotalTimelineDur = view.findViewById(R.id.tvTotalTimelineDur);
        tvVideoPreviewBadge = view.findViewById(R.id.tvVideoPreviewBadge);
        tvActiveTrimRange = view.findViewById(R.id.tvActiveTrimRange);

        panelTrim = view.findViewById(R.id.panelTrim);
        panelRotate = view.findViewById(R.id.panelRotate);
        panelFormat = view.findViewById(R.id.panelFormat);
        panelAudio = view.findViewById(R.id.panelAudio);
        panelSpeed = view.findViewById(R.id.panelSpeed);
        panelQuality = view.findViewById(R.id.panelQuality);

        iconToolTrim = view.findViewById(R.id.iconToolTrim);
        iconToolRotate = view.findViewById(R.id.iconToolRotate);
        iconToolFormat = view.findViewById(R.id.iconToolFormat);
        iconToolAudio = view.findViewById(R.id.iconToolAudio);
        iconToolSpeed = view.findViewById(R.id.iconToolSpeed);
        iconToolQuality = view.findViewById(R.id.iconToolQuality);

        rangeSlider = view.findViewById(R.id.rangeSliderVideo);
        tvVideoStart = view.findViewById(R.id.tvVideoStart);
        tvVideoDuration = view.findViewById(R.id.tvVideoDuration);
        tvVideoEnd = view.findViewById(R.id.tvVideoEnd);
        chipGroupRotate = view.findViewById(R.id.chipGroupVideoRotate);
        switchFlipH = view.findViewById(R.id.switchFlipH);
        switchFlipV = view.findViewById(R.id.switchFlipV);
        layoutFillMode = view.findViewById(R.id.layoutVideoFillMode);
        layoutCustomMp3Box = view.findViewById(R.id.layoutCustomMp3Box);
        tvCustomAudioName = view.findViewById(R.id.tvVideoCustomAudioName);
        etOutputName = view.findViewById(R.id.etVideoOutputName);

        btnQuickPlay = view.findViewById(R.id.btnQuickPlay);
        btnStartExport = view.findViewById(R.id.btnStartVideoExport);
        btnExtractMp3 = view.findViewById(R.id.btnExtractMp3FromVideo);
        layoutProgress = view.findViewById(R.id.layoutVideoProgress);
        progressBar = view.findViewById(R.id.progressVideo);
        tvStatus = view.findViewById(R.id.tvVideoStatus);
        btnCancel = view.findViewById(R.id.btnCancelVideo);
        cardResult = view.findViewById(R.id.cardVideoResult);
        tvResultInfo = view.findViewById(R.id.tvVideoResultInfo);

        // Horizontal timeline RecyclerView
        RecyclerView rvTimeline = view.findViewById(R.id.rvVideoTimeline);
        clipAdapter = new VideoClipAdapter(clips, pos -> {
            pauseVideoPreview();
            activeClipIndex = pos;
            bindActiveClip();
        });
        rvTimeline.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
        rvTimeline.setAdapter(clipAdapter);

        // Pickers
        view.findViewById(R.id.btnPickInitialVideos).setOnClickListener(v ->
                pickVideosLauncher.launch(new String[]{"video/*"}));
        view.findViewById(R.id.btnAddMoreVideos).setOnClickListener(v ->
                pickVideosLauncher.launch(new String[]{"video/*"}));

        // Transport
        btnCenterPlayPause.setOnClickListener(v -> toggleVideoPreview());
        btnQuickPlay.setOnClickListener(v -> toggleVideoPreview());
        view.findViewById(R.id.btnPrevClip).setOnClickListener(v -> {
            if (activeClipIndex > 0) {
                pauseVideoPreview();
                activeClipIndex--;
                clipAdapter.setSelectedIndex(activeClipIndex);
                bindActiveClip();
            }
        });
        view.findViewById(R.id.btnNextClip).setOnClickListener(v -> {
            if (activeClipIndex < clips.size() - 1) {
                pauseVideoPreview();
                activeClipIndex++;
                clipAdapter.setSelectedIndex(activeClipIndex);
                bindActiveClip();
            }
        });

        // Quick pills
        view.findViewById(R.id.btnQuickMinus1).setOnClickListener(v -> nudgePlayhead(-1000));
        view.findViewById(R.id.btnQuickPlus1).setOnClickListener(v -> nudgePlayhead(1000));
        view.findViewById(R.id.btnQuickEnding).setOnClickListener(v -> playClipEnding());

        // Clip order / delete
        view.findViewById(R.id.btnClipMoveLeft).setOnClickListener(v -> {
            if (activeClipIndex > 0) {
                pauseVideoPreview();
                Collections.swap(clips, activeClipIndex, activeClipIndex - 1);
                activeClipIndex--;
                refreshWorkspace();
            }
        });
        view.findViewById(R.id.btnClipMoveRight).setOnClickListener(v -> {
            if (activeClipIndex < clips.size() - 1) {
                pauseVideoPreview();
                Collections.swap(clips, activeClipIndex, activeClipIndex + 1);
                activeClipIndex++;
                refreshWorkspace();
            }
        });
        view.findViewById(R.id.btnClipRemove).setOnClickListener(v -> {
            if (!clips.isEmpty() && activeClipIndex >= 0 && activeClipIndex < clips.size()) {
                pauseVideoPreview();
                clips.remove(activeClipIndex);
                if (activeClipIndex >= clips.size()) activeClipIndex = Math.max(0, clips.size() - 1);
                refreshWorkspace();
            }
        });

        // Circular tool buttons
        view.findViewById(R.id.toolTrim).setOnClickListener(v -> selectTool(0));
        view.findViewById(R.id.toolRotate).setOnClickListener(v -> selectTool(1));
        view.findViewById(R.id.toolFormat).setOnClickListener(v -> selectTool(2));
        view.findViewById(R.id.toolAudio).setOnClickListener(v -> selectTool(3));
        view.findViewById(R.id.toolSpeed).setOnClickListener(v -> selectTool(4));
        view.findViewById(R.id.toolQuality).setOnClickListener(v -> selectTool(5));

        // Trim slider
        rangeSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (isUpdatingUi) return;
            AudioUtils.VideoInfo clip = getActiveClip();
            if (clip == null) return;
            List<Float> vals = slider.getValues();
            clip.trimStartMs = Math.round(vals.get(0) * 1000f);
            clip.trimEndMs = Math.round(vals.get(1) * 1000f);
            updateTrimLabels();
            clipAdapter.notifyItemChanged(activeClipIndex);
            updateTotalDurationHeader();
            if (fromUser) {
                try {
                    videoView.seekTo(Math.round(value * 1000f));
                } catch (Exception ignored) {
                }
            }
        });

        // Rotate / Flip
        chipGroupRotate.setOnCheckedStateChangeListener((g, ids) -> {
            if (isUpdatingUi || ids.isEmpty()) return;
            AudioUtils.VideoInfo clip = getActiveClip();
            if (clip == null) return;
            int id = ids.get(0);
            if (id == R.id.chipRot0) clip.extraRotateDeg = 0;
            else if (id == R.id.chipRot90) clip.extraRotateDeg = 90;
            else if (id == R.id.chipRot180) clip.extraRotateDeg = 180;
            else if (id == R.id.chipRot270) clip.extraRotateDeg = 270;
            applyVisualPreviewTransform();
        });
        switchFlipH.setOnCheckedChangeListener((b, checked) -> {
            if (isUpdatingUi) return;
            AudioUtils.VideoInfo clip = getActiveClip();
            if (clip != null) {
                clip.flipH = checked;
                applyVisualPreviewTransform();
            }
        });
        switchFlipV.setOnCheckedChangeListener((b, checked) -> {
            if (isUpdatingUi) return;
            AudioUtils.VideoInfo clip = getActiveClip();
            if (clip != null) {
                clip.flipV = checked;
                applyVisualPreviewTransform();
            }
        });

        // Format & Fill
        ChipGroup aspectGroup = view.findViewById(R.id.chipGroupVideoAspect);
        aspectGroup.setOnCheckedStateChangeListener((g, ids) -> {
            if (ids.isEmpty()) return;
            int id = ids.get(0);
            if (id == R.id.chipAspectOrig) aspectMode = ASPECT_ORIG;
            else if (id == R.id.chipAspect169) aspectMode = ASPECT_16_9;
            else if (id == R.id.chipAspect916) aspectMode = ASPECT_9_16;
            else if (id == R.id.chipAspect11) aspectMode = ASPECT_1_1;
            else if (id == R.id.chipAspect45) aspectMode = ASPECT_4_5;
            layoutFillMode.setVisibility(aspectMode == ASPECT_ORIG ? View.GONE : View.VISIBLE);
        });

        ChipGroup fillGroup = view.findViewById(R.id.chipGroupVideoFill);
        fillGroup.setOnCheckedStateChangeListener((g, ids) -> {
            if (ids.isEmpty()) return;
            int id = ids.get(0);
            if (id == R.id.chipFillBlur) fillMode = FILL_BLUR;
            else if (id == R.id.chipFillCrop) fillMode = FILL_CROP;
            else if (id == R.id.chipFillPad) fillMode = FILL_PAD;
        });

        // Audio
        ChipGroup audioGroup = view.findViewById(R.id.chipGroupVideoAudio);
        audioGroup.setOnCheckedStateChangeListener((g, ids) -> {
            if (ids.isEmpty()) return;
            int id = ids.get(0);
            if (id == R.id.chipVidAudioOrig) audioMode = AUDIO_ORIG;
            else if (id == R.id.chipVidAudioMute) audioMode = AUDIO_MUTE;
            else if (id == R.id.chipVidAudioCustom) audioMode = AUDIO_CUSTOM;
            layoutCustomMp3Box.setVisibility(audioMode == AUDIO_CUSTOM ? View.VISIBLE : View.GONE);
        });
        view.findViewById(R.id.btnPickVideoCustomAudio).setOnClickListener(v ->
                pickCustomAudioLauncher.launch(new String[]{"audio/mpeg", "audio/*"}));
        btnExtractMp3.setOnClickListener(v -> extractMp3FromActiveClip());

        // Speed
        ChipGroup speedGroup = view.findViewById(R.id.chipGroupVideoSpeed);
        speedGroup.setOnCheckedStateChangeListener((g, ids) -> {
            if (ids.isEmpty()) return;
            int id = ids.get(0);
            if (id == R.id.chipSpeed05) speedFactor = 0.5;
            else if (id == R.id.chipSpeed075) speedFactor = 0.75;
            else if (id == R.id.chipSpeed10) speedFactor = 1.0;
            else if (id == R.id.chipSpeed125) speedFactor = 1.25;
            else if (id == R.id.chipSpeed15) speedFactor = 1.5;
            else if (id == R.id.chipSpeed20) speedFactor = 2.0;
        });

        // Quality
        ChipGroup resGroup = view.findViewById(R.id.chipGroupVideoRes);
        resGroup.setOnCheckedStateChangeListener((g, ids) -> {
            if (ids.isEmpty()) return;
            int id = ids.get(0);
            if (id == R.id.chipResOrig) targetRes = 0;
            else if (id == R.id.chipRes1080) targetRes = 1080;
            else if (id == R.id.chipRes720) targetRes = 720;
            else if (id == R.id.chipRes480) targetRes = 480;
        });

        // Export & Cancel & Result
        btnStartExport.setOnClickListener(v -> startUnifiedExport());
        btnCancel.setOnClickListener(v -> {
            isCancelled.set(true);
            btnCancel.setEnabled(false);
            tvStatus.setText("Отмена...");
            if (activeSessionId > 0) FFmpegKit.cancel(activeSessionId);
            else FFmpegKit.cancel();
        });
        view.findViewById(R.id.btnVideoOpenResult).setOnClickListener(v -> {
            if (lastResultUri != null && getContext() != null) {
                if (lastResultIsVideo) AudioUtils.openVideo(getContext(), lastResultUri);
                else AudioUtils.openAudio(getContext(), lastResultUri);
            }
        });
        view.findViewById(R.id.btnVideoShareResult).setOnClickListener(v -> {
            if (lastResultUri != null && getContext() != null) {
                if (lastResultIsVideo) AudioUtils.shareVideo(getContext(), lastResultUri);
                else AudioUtils.shareAudio(getContext(), lastResultUri);
            }
        });

        return view;
    }

    private void selectTool(int idx) {
        panelTrim.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        panelRotate.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        panelFormat.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        panelAudio.setVisibility(idx == 3 ? View.VISIBLE : View.GONE);
        panelSpeed.setVisibility(idx == 4 ? View.VISIBLE : View.GONE);
        panelQuality.setVisibility(idx == 5 ? View.VISIBLE : View.GONE);

        iconToolTrim.setBackgroundResource(idx == 0 ? R.drawable.bg_tool_circle_active : R.drawable.bg_tool_circle);
        iconToolRotate.setBackgroundResource(idx == 1 ? R.drawable.bg_tool_circle_active : R.drawable.bg_tool_circle);
        iconToolFormat.setBackgroundResource(idx == 2 ? R.drawable.bg_tool_circle_active : R.drawable.bg_tool_circle);
        iconToolAudio.setBackgroundResource(idx == 3 ? R.drawable.bg_tool_circle_active : R.drawable.bg_tool_circle);
        iconToolSpeed.setBackgroundResource(idx == 4 ? R.drawable.bg_tool_circle_active : R.drawable.bg_tool_circle);
        iconToolQuality.setBackgroundResource(idx == 5 ? R.drawable.bg_tool_circle_active : R.drawable.bg_tool_circle);
    }

    private AudioUtils.VideoInfo getActiveClip() {
        if (clips.isEmpty() || activeClipIndex < 0 || activeClipIndex >= clips.size()) return null;
        return clips.get(activeClipIndex);
    }

    private void refreshWorkspace() {
        boolean empty = clips.isEmpty();
        cardEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        layoutWorkspace.setVisibility(empty ? View.GONE : View.VISIBLE);
        cardResult.setVisibility(View.GONE);
        if (empty) return;

        clipAdapter.setSelectedIndex(activeClipIndex);
        btnStartExport.setText(clips.size() > 1
                ? ("Сохранить видео (" + clips.size() + " клипов)")
                : "Сохранить видео MP4");
        updateTotalDurationHeader();
        bindActiveClip();
    }

    private void updateTotalDurationHeader() {
        long total = 0;
        for (AudioUtils.VideoInfo c : clips) total += c.getEffectiveDurationMs();
        tvTotalTimelineDur.setText("Всего: " + AudioUtils.formatTimeMs(total));
    }

    private void bindActiveClip() {
        AudioUtils.VideoInfo clip = getActiveClip();
        if (clip == null) return;

        isUpdatingUi = true;
        tvActiveClipName.setText((activeClipIndex + 1) + ". " + clip.displayName);

        float totalSec = Math.max(1f, clip.durationMs / 1000f);
        float sSec = Math.max(0f, Math.min(clip.trimStartMs / 1000f, totalSec - 0.5f));
        float eSec = Math.max(sSec + 0.5f, Math.min(clip.trimEndMs / 1000f, totalSec));
        rangeSlider.setValueFrom(0f);
        rangeSlider.setValueTo(totalSec);
        rangeSlider.setValues(sSec, eSec);

        if (clip.extraRotateDeg == 90) chipGroupRotate.check(R.id.chipRot90);
        else if (clip.extraRotateDeg == 180) chipGroupRotate.check(R.id.chipRot180);
        else if (clip.extraRotateDeg == 270) chipGroupRotate.check(R.id.chipRot270);
        else chipGroupRotate.check(R.id.chipRot0);

        switchFlipH.setChecked(clip.flipH);
        switchFlipV.setChecked(clip.flipV);
        isUpdatingUi = false;

        updateTrimLabels();
        applyVisualPreviewTransform();

        try {
            videoView.setVideoURI(clip.uri);
            videoView.setOnPreparedListener(mp -> {
                videoView.seekTo((int) clip.trimStartMs);
                applyVisualPreviewTransform();
            });
            videoView.setOnCompletionListener(mp -> pauseVideoPreview());
        } catch (Exception ignored) {
        }
    }

    private void updateTrimLabels() {
        AudioUtils.VideoInfo clip = getActiveClip();
        if (clip == null) return;
        String s = AudioUtils.formatTimeMs(clip.trimStartMs);
        String e = AudioUtils.formatTimeMs(clip.trimEndMs);
        tvActiveTrimRange.setText(s + " — " + e);
        tvVideoStart.setText("Начало: " + s);
        tvVideoEnd.setText("Конец: " + e);
        tvVideoDuration.setText("Длина: " + AudioUtils.formatTimeMs(clip.getEffectiveDurationMs()));
    }

    private void applyVisualPreviewTransform() {
        AudioUtils.VideoInfo clip = getActiveClip();
        if (videoView == null || clip == null) return;
        videoView.setRotation(clip.extraRotateDeg);
        videoView.setScaleX(clip.flipH ? -1f : 1f);
        videoView.setScaleY(clip.flipV ? -1f : 1f);
        if (clip.extraRotateDeg != 0 || clip.flipH || clip.flipV) {
            StringBuilder b = new StringBuilder();
            if (clip.extraRotateDeg != 0) b.append(clip.extraRotateDeg).append("°");
            if (clip.flipH) { if (b.length() > 0) b.append(" "); b.append("↔"); }
            if (clip.flipV) { if (b.length() > 0) b.append(" "); b.append("↕"); }
            tvVideoPreviewBadge.setText(b.toString());
            tvVideoPreviewBadge.setVisibility(View.VISIBLE);
        } else {
            tvVideoPreviewBadge.setVisibility(View.GONE);
        }
    }

    private void nudgePlayhead(int deltaMs) {
        AudioUtils.VideoInfo clip = getActiveClip();
        if (clip == null || videoView == null) return;
        try {
            int cur = videoView.getCurrentPosition();
            int next = (int) Math.max(clip.trimStartMs, Math.min(clip.trimEndMs, cur + deltaMs));
            videoView.seekTo(next);
        } catch (Exception ignored) {
        }
    }

    private void playClipEnding() {
        AudioUtils.VideoInfo clip = getActiveClip();
        if (clip == null || videoView == null) return;
        int jump = (int) Math.max(clip.trimStartMs, clip.trimEndMs - 5000L);
        try {
            videoView.seekTo(jump);
            videoView.start();
            isPlayingPreview = true;
            btnCenterPlayPause.setImageResource(R.drawable.ic_pause);
            btnQuickPlay.setText("Пауза");
            handler.post(previewUpdater);
        } catch (Exception ignored) {
        }
    }

    private void toggleVideoPreview() {
        if (isPlayingPreview) {
            pauseVideoPreview();
            return;
        }
        AudioUtils.VideoInfo clip = getActiveClip();
        if (clip == null) return;
        try {
            int cur = videoView.getCurrentPosition();
            if (cur < clip.trimStartMs || cur >= clip.trimEndMs - 200) {
                videoView.seekTo((int) clip.trimStartMs);
            }
            videoView.start();
            isPlayingPreview = true;
            btnCenterPlayPause.setImageResource(R.drawable.ic_pause);
            btnQuickPlay.setText("Пауза");
            handler.post(previewUpdater);
        } catch (Exception e) {
            Toast.makeText(getContext(), "Не удалось воспроизвести", Toast.LENGTH_SHORT).show();
        }
    }

    private void pauseVideoPreview() {
        isPlayingPreview = false;
        handler.removeCallbacks(previewUpdater);
        if (videoView != null) {
            try {
                if (videoView.isPlaying()) videoView.pause();
            } catch (Exception ignored) {
            }
        }
        if (btnCenterPlayPause != null) btnCenterPlayPause.setImageResource(R.drawable.ic_play);
        if (btnQuickPlay != null) btnQuickPlay.setText("Играть");
    }

    private int[] computeTargetSize(int aspect, int resPref, AudioUtils.VideoInfo refClip) {
        int r = (resPref == 0) ? 720 : resPref;
        if (aspect == ASPECT_ORIG && refClip != null && refClip.getDisplayWidth() > 0 && refClip.getDisplayHeight() > 0) {
            int rw = refClip.getDisplayWidth();
            int rh = refClip.getDisplayHeight();
            if (refClip.extraRotateDeg == 90 || refClip.extraRotateDeg == 270) {
                int t = rw; rw = rh; rh = t;
            }
            if (resPref > 0) {
                if (rw >= rh) {
                    int w = Math.round(rw * (resPref / (float) rh)) / 2 * 2;
                    return new int[]{Math.max(2, w), resPref / 2 * 2};
                } else {
                    int h = Math.round(rh * (resPref / (float) rw)) / 2 * 2;
                    return new int[]{resPref / 2 * 2, Math.max(2, h)};
                }
            }
            return new int[]{(rw / 2) * 2, (rh / 2) * 2};
        }
        if (aspect == ASPECT_9_16) {
            if (r == 1080) return new int[]{1080, 1920};
            if (r == 480) return new int[]{480, 854};
            return new int[]{720, 1280};
        } else if (aspect == ASPECT_1_1) {
            if (r == 1080) return new int[]{1080, 1080};
            if (r == 480) return new int[]{480, 480};
            return new int[]{720, 720};
        } else if (aspect == ASPECT_4_5) {
            if (r == 1080) return new int[]{1080, 1350};
            if (r == 480) return new int[]{480, 600};
            return new int[]{720, 900};
        } else { // 16:9 default
            if (r == 1080) return new int[]{1920, 1080};
            if (r == 480) return new int[]{854, 480};
            return new int[]{1280, 720};
        }
    }

    private void extractMp3FromActiveClip() {
        AudioUtils.VideoInfo clip = getActiveClip();
        if (clip == null || getContext() == null) return;
        pauseVideoPreview();

        long clipDurMs = clip.getEffectiveDurationMs();
        String baseName = AudioUtils.stripExtension(clip.displayName) + "_audio";
        final Context appCtx = requireContext().getApplicationContext();
        beginTaskUi("Извлечение MP3", "Подготовка видео...");

        executor.execute(() -> {
            File inTemp = null;
            File outTemp = null;
            try {
                inTemp = AudioUtils.copyUriToCacheWithExt(appCtx, clip.uri, "vid_ext", ".mp4");
                if (isCancelled.get()) {
                    //noinspection ResultOfMethodCallIgnored
                    inTemp.delete();
                    finishCancelled(appCtx, "Извлечение отменено");
                    return;
                }
                outTemp = new File(appCtx.getCacheDir(), "vid_ext_" + System.currentTimeMillis() + ".mp3");

                String[] args = new String[]{
                        "-y",
                        "-ss", String.format(Locale.US, "%.2f", clip.trimStartMs / 1000.0),
                        "-t", String.format(Locale.US, "%.2f", clipDurMs / 1000.0),
                        "-i", inTemp.getAbsolutePath(),
                        "-vn",
                        "-c:a", "libmp3lame",
                        "-b:a", "192k",
                        outTemp.getAbsolutePath()
                };

                final File fIn = inTemp;
                final File fOut = outTemp;
                final long[] lastNotif = {0L};

                var session = FFmpegKit.executeWithArgumentsAsync(args,
                        s -> {
                            activeSessionId = -1;
                            AudioProcessingService.stopTask(appCtx);
                            if (isCancelled.get() || ReturnCode.isCancel(s.getReturnCode())) {
                                finishCancelled(appCtx, "Извлечение отменено");
                            } else if (ReturnCode.isSuccess(s.getReturnCode()) && fOut.exists()) {
                                try {
                                    long sz = fOut.length();
                                    AudioUtils.SaveResult res = AudioUtils.saveMp3Output(appCtx, fOut, baseName);
                                    lastResultUri = res.uri;
                                    lastResultIsVideo = false;
                                    runUi(() -> {
                                        finishUiButtons();
                                        cardResult.setVisibility(View.VISIBLE);
                                        tvResultInfo.setText("MP3 извлечён! Сохранено в " + res.displayPath + "\nРазмер: " + AudioUtils.formatSize(sz));
                                    });
                                } catch (Exception e) {
                                    finishError("Ошибка сохранения: " + e.getMessage());
                                }
                            } else {
                                finishError("В клипе нет звука или ошибка FFmpeg");
                            }
                            if (fIn != null) //noinspection ResultOfMethodCallIgnored
                                fIn.delete();
                            if (fOut != null) //noinspection ResultOfMethodCallIgnored
                                fOut.delete();
                        },
                        log -> {},
                        stats -> updateTaskProgress(appCtx, "Извлечение MP3", stats.getTime(), clipDurMs, lastNotif)
                );
                activeSessionId = session.getSessionId();
            } catch (Exception e) {
                activeSessionId = -1;
                AudioProcessingService.stopTask(appCtx);
                if (inTemp != null) //noinspection ResultOfMethodCallIgnored
                    inTemp.delete();
                if (outTemp != null) //noinspection ResultOfMethodCallIgnored
                    outTemp.delete();
                finishError("Ошибка: " + e.getMessage());
            }
        });
    }

    private void startUnifiedExport() {
        if (clips.isEmpty() || getContext() == null) return;
        pauseVideoPreview();

        if (audioMode == AUDIO_CUSTOM && customAudioUri == null) {
            Toast.makeText(getContext(), "Выберите MP3 во вкладке «Звук»", Toast.LENGTH_SHORT).show();
            selectTool(3);
            return;
        }

        String outName = etOutputName.getText() != null ? etOutputName.getText().toString().trim() : "";
        if (outName.isEmpty()) outName = AudioUtils.stripExtension(clips.get(0).displayName) + "_edit";

        final String finalOutName = outName;
        final List<AudioUtils.VideoInfo> snapshot = new ArrayList<>(clips);
        final int fAspect = aspectMode;
        final int fFill = fillMode;
        final int fAudio = audioMode;
        final double fSpeed = speedFactor;
        final int fRes = targetRes;
        final Uri fCustomAudio = customAudioUri;
        final Context appCtx = requireContext().getApplicationContext();

        long totalRawMs = 0;
        boolean anyHasAudio = false;
        for (AudioUtils.VideoInfo c : snapshot) {
            totalRawMs += c.getEffectiveDurationMs();
            if (c.hasAudio) anyHasAudio = true;
        }
        final long expectedOutMs = Math.round(totalRawMs / fSpeed);
        final boolean includeOrigAudio = (fAudio == AUDIO_ORIG && anyHasAudio);

        boolean singleFastCopy = (snapshot.size() == 1
                && snapshot.get(0).extraRotateDeg == 0
                && !snapshot.get(0).flipH
                && !snapshot.get(0).flipV
                && fAspect == ASPECT_ORIG
                && fAudio == AUDIO_ORIG
                && Math.abs(fSpeed - 1.0) < 0.01
                && fRes == 0);

        beginTaskUi("Сохранение видео", "Подготовка (" + snapshot.size() + " клип.)...");

        executor.execute(() -> {
            List<File> tempVids = new ArrayList<>();
            File inAud = null;
            File outVid = null;
            try {
                for (int i = 0; i < snapshot.size(); i++) {
                    if (isCancelled.get()) {
                        for (File f : tempVids) //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        finishCancelled(appCtx, "Обработка отменена");
                        return;
                    }
                    tempVids.add(AudioUtils.copyUriToCacheWithExt(appCtx, snapshot.get(i).uri, "vclip_" + i, ".mp4"));
                }
                if (fAudio == AUDIO_CUSTOM && fCustomAudio != null) {
                    inAud = AudioUtils.copyUriToCacheWithExt(appCtx, fCustomAudio, "vaud", ".mp3");
                }
                outVid = new File(appCtx.getCacheDir(), "vout_" + System.currentTimeMillis() + ".mp4");

                List<String> args = new ArrayList<>();
                args.add("-y");

                if (singleFastCopy) {
                    AudioUtils.VideoInfo c0 = snapshot.get(0);
                    args.add("-ss");
                    args.add(String.format(Locale.US, "%.2f", c0.trimStartMs / 1000.0));
                    args.add("-t");
                    args.add(String.format(Locale.US, "%.2f", c0.getEffectiveDurationMs() / 1000.0));
                    args.add("-i");
                    args.add(tempVids.get(0).getAbsolutePath());
                    args.add("-c");
                    args.add("copy");
                    args.add("-movflags");
                    args.add("+faststart");
                    args.add(outVid.getAbsolutePath());
                } else {
                    for (int i = 0; i < snapshot.size(); i++) {
                        AudioUtils.VideoInfo c = snapshot.get(i);
                        args.add("-ss");
                        args.add(String.format(Locale.US, "%.2f", c.trimStartMs / 1000.0));
                        args.add("-t");
                        args.add(String.format(Locale.US, "%.2f", c.getEffectiveDurationMs() / 1000.0));
                        args.add("-i");
                        args.add(tempVids.get(i).getAbsolutePath());
                    }
                    int customAudioInputIdx = -1;
                    if (fAudio == AUDIO_CUSTOM && inAud != null) {
                        customAudioInputIdx = snapshot.size();
                        args.add("-i");
                        args.add(inAud.getAbsolutePath());
                    }

                    int[] wh = computeTargetSize(fAspect, fRes, snapshot.get(0));
                    int W = wh[0], H = wh[1];

                    StringBuilder fc = new StringBuilder();
                    StringBuilder concatIn = new StringBuilder();

                    for (int i = 0; i < snapshot.size(); i++) {
                        AudioUtils.VideoInfo c = snapshot.get(i);
                        List<String> baseV = new ArrayList<>();
                        if (c.extraRotateDeg == 90) baseV.add("transpose=1");
                        else if (c.extraRotateDeg == 180) {
                            baseV.add("transpose=1");
                            baseV.add("transpose=1");
                        } else if (c.extraRotateDeg == 270) baseV.add("transpose=2");
                        if (c.flipH) baseV.add("hflip");
                        if (c.flipV) baseV.add("vflip");
                        if (Math.abs(fSpeed - 1.0) > 0.01) {
                            baseV.add(String.format(Locale.US, "setpts=%.4f*PTS", 1.0 / fSpeed));
                        }
                        String prefix = baseV.isEmpty() ? ("[" + i + ":v]") : ("[" + i + ":v]" + String.join(",", baseV) + ",");

                        if (fFill == FILL_BLUR && fAspect != ASPECT_ORIG) {
                            fc.append(prefix)
                              .append("split=2[bg").append(i).append("][fg").append(i).append("];")
                              .append("[bg").append(i).append("]scale=").append(W).append(":").append(H)
                              .append(":force_original_aspect_ratio=increase,crop=").append(W).append(":").append(H)
                              .append(",boxblur=20:5[bgb").append(i).append("];")
                              .append("[fg").append(i).append("]scale=").append(W).append(":").append(H)
                              .append(":force_original_aspect_ratio=decrease[fgf").append(i).append("];")
                              .append("[bgb").append(i).append("][fgf").append(i).append("]overlay=(W-w)/2:(H-h)/2,setsar=1,fps=30,format=yuv420p[v").append(i).append("];");
                        } else if (fFill == FILL_CROP && fAspect != ASPECT_ORIG) {
                            fc.append(prefix)
                              .append("scale=").append(W).append(":").append(H)
                              .append(":force_original_aspect_ratio=increase,crop=").append(W).append(":").append(H)
                              .append(",setsar=1,fps=30,format=yuv420p[v").append(i).append("];");
                        } else {
                            fc.append(prefix)
                              .append("scale=").append(W).append(":").append(H)
                              .append(":force_original_aspect_ratio=decrease,pad=").append(W).append(":").append(H)
                              .append(":(ow-iw)/2:(oh-ih)/2:black,setsar=1,fps=30,format=yuv420p[v").append(i).append("];");
                        }

                        if (includeOrigAudio) {
                            if (c.hasAudio) {
                                fc.append("[").append(i).append(":a]");
                                if (Math.abs(fSpeed - 1.0) > 0.01) {
                                    fc.append("atempo=").append(String.format(Locale.US, "%.2f", fSpeed)).append(",");
                                }
                                fc.append("aformat=sample_rates=44100:channel_layouts=stereo[a").append(i).append("];");
                            } else {
                                double dSec = Math.max(0.3, (c.getEffectiveDurationMs() / 1000.0) / fSpeed);
                                fc.append("anullsrc=r=44100:cl=stereo,atrim=0:")
                                  .append(String.format(Locale.US, "%.2f", dSec))
                                  .append(",asetpts=PTS-STARTPTS[a").append(i).append("];");
                            }
                            concatIn.append("[v").append(i).append("][a").append(i).append("]");
                        } else {
                            concatIn.append("[v").append(i).append("]");
                        }
                    }

                    if (snapshot.size() == 1) {
                        fc.append("[v0]null[vout]");
                        if (includeOrigAudio) fc.append(";[a0]anull[aout]");
                    } else {
                        if (includeOrigAudio) {
                            fc.append(concatIn).append("concat=n=").append(snapshot.size()).append(":v=1:a=1[vout][aout]");
                        } else {
                            fc.append(concatIn).append("concat=n=").append(snapshot.size()).append(":v=1:a=0[vout]");
                        }
                    }

                    args.add("-filter_complex");
                    args.add(fc.toString());
                    args.add("-map");
                    args.add("[vout]");

                    if (customAudioInputIdx >= 0) {
                        args.add("-map");
                        args.add(customAudioInputIdx + ":a:0");
                        args.add("-c:a");
                        args.add("aac");
                        args.add("-b:a");
                        args.add("128k");
                        args.add("-shortest");
                    } else if (includeOrigAudio) {
                        args.add("-map");
                        args.add("[aout]");
                        args.add("-c:a");
                        args.add("aac");
                        args.add("-b:a");
                        args.add("128k");
                    } else {
                        args.add("-an");
                    }

                    args.add("-c:v");
                    args.add("libx264");
                    args.add("-preset");
                    args.add("ultrafast");
                    args.add("-crf");
                    args.add("23");
                    args.add("-movflags");
                    args.add("+faststart");
                    args.add(outVid.getAbsolutePath());
                }

                final File fInAud = inAud;
                final File fOutVid = outVid;
                final long[] lastNotif = {0L};

                runUi(() -> {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(0);
                    tvStatus.setText("Сохранение видео: 0%");
                });

                var session = FFmpegKit.executeWithArgumentsAsync(args.toArray(new String[0]),
                        s -> {
                            activeSessionId = -1;
                            AudioProcessingService.stopTask(appCtx);
                            if (isCancelled.get() || ReturnCode.isCancel(s.getReturnCode())) {
                                finishCancelled(appCtx, "Сохранение видео отменено");
                            } else if (ReturnCode.isSuccess(s.getReturnCode()) && fOutVid.exists() && fOutVid.length() > 0) {
                                try {
                                    long sz = fOutVid.length();
                                    AudioUtils.SaveResult res = AudioUtils.saveMp4Output(appCtx, fOutVid, finalOutName);
                                    lastResultUri = res.uri;
                                    lastResultIsVideo = true;
                                    runUi(() -> {
                                        finishUiButtons();
                                        cardResult.setVisibility(View.VISIBLE);
                                        tvResultInfo.setText(
                                                "Готово! Сохранено в " + res.displayPath + "\n" +
                                                "Размер: " + AudioUtils.formatSize(sz)
                                        );
                                    });
                                } catch (Exception e) {
                                    finishError("Ошибка сохранения: " + e.getMessage());
                                }
                            } else {
                                finishError("Ошибка FFmpeg при обработке видео");
                            }
                            for (File f : tempVids) //noinspection ResultOfMethodCallIgnored
                                f.delete();
                            if (fInAud != null) //noinspection ResultOfMethodCallIgnored
                                fInAud.delete();
                            if (fOutVid != null) //noinspection ResultOfMethodCallIgnored
                                fOutVid.delete();
                        },
                        log -> {},
                        stats -> updateTaskProgress(appCtx, "Сохранение видео", stats.getTime(), expectedOutMs, lastNotif)
                );
                activeSessionId = session.getSessionId();
            } catch (Exception e) {
                activeSessionId = -1;
                AudioProcessingService.stopTask(appCtx);
                for (File f : tempVids) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                if (inAud != null) //noinspection ResultOfMethodCallIgnored
                    inAud.delete();
                if (outVid != null) //noinspection ResultOfMethodCallIgnored
                    outVid.delete();
                finishError("Ошибка подготовки: " + e.getMessage());
            }
        });
    }

    private void beginTaskUi(String notifTitle, String status) {
        isCancelled.set(false);
        activeSessionId = -1;
        btnStartExport.setEnabled(false);
        btnExtractMp3.setEnabled(false);
        btnCancel.setEnabled(true);
        cardResult.setVisibility(View.GONE);
        layoutProgress.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        tvStatus.setText(status);
        if (getContext() != null) {
            AudioProcessingService.startTask(getContext().getApplicationContext(), notifTitle, status);
        }
    }

    private void updateTaskProgress(Context appCtx, String title, double timeMs, long expectedMs, long[] lastNotif) {
        if (expectedMs > 0 && !isCancelled.get()) {
            int pct = (int) Math.min(99, Math.round((timeMs / expectedMs) * 100.0));
            String st = title + ": " + pct + "%";
            runUi(() -> {
                progressBar.setIndeterminate(false);
                progressBar.setProgress(pct);
                tvStatus.setText(st);
            });
            long now = System.currentTimeMillis();
            if (now - lastNotif[0] > 500) {
                lastNotif[0] = now;
                AudioProcessingService.updateProgress(appCtx, title, st, pct, false);
            }
        }
    }

    private void finishUiButtons() {
        layoutProgress.setVisibility(View.GONE);
        btnStartExport.setEnabled(!clips.isEmpty());
        btnExtractMp3.setEnabled(!clips.isEmpty());
        btnCancel.setEnabled(true);
    }

    private void finishCancelled(Context appCtx, String msg) {
        AudioProcessingService.stopTask(appCtx);
        runUi(() -> {
            finishUiButtons();
            Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
        });
    }

    private void finishError(String msg) {
        runUi(() -> {
            finishUiButtons();
            Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
        });
    }

    private void runUi(Runnable r) {
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(r);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        pauseVideoPreview();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) pauseVideoPreview();
    }
}
