package com.mp3master.app;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

    private AudioUtils.VideoInfo selectedVideo;
    private Uri customAudioUri;
    private String customAudioName;

    private int rotateDeg = 0;
    private boolean flipH = false;
    private boolean flipV = false;
    private int aspectMode = ASPECT_ORIG;
    private int fillMode = FILL_BLUR;
    private int audioMode = AUDIO_ORIG;
    private double speedFactor = 1.0;
    private int targetRes = 0; // 0=orig, 1080, 720, 480

    // Merge mode state
    private final List<AudioUtils.VideoInfo> mergeVideos = new ArrayList<>();
    private VideoMergeAdapter mergeAdapter;
    private int mergeAspect = ASPECT_16_9;
    private int mergeRes = 720;

    // Playback & execution state
    private boolean isPlayingPreview = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private volatile long activeSessionId = -1;

    private Uri lastResultUri;
    private boolean lastResultIsVideo = true;

    // Views
    private View layoutEditorMode, layoutMergeMode, layoutPreviewBox, layoutFillMode, layoutCustomMp3Box, layoutProgress;
    private MaterialCardView cardTransform, cardAudioSpeed, cardResult;
    private VideoView videoView;
    private TextView tvVideoInfo, tvPreviewBadge, tvCurrentPos, tvStart, tvDuration, tvEnd;
    private TextView tvCustomAudioName, tvStatus, tvResultInfo, tvMergeEmpty;
    private MaterialButton btnPlayPause, btnStartExport, btnExtractMp3, btnClearMerge, btnStartMerge, btnCancel;
    private RangeSlider rangeSlider;
    private MaterialSwitch switchFlipH, switchFlipV;
    private TextInputEditText etOutputName, etMergeOutputName;
    private LinearProgressIndicator progressBar;

    private final Runnable previewUpdater = new Runnable() {
        @Override
        public void run() {
            if (videoView != null && isPlayingPreview) {
                try {
                    int pos = videoView.getCurrentPosition();
                    tvCurrentPos.setText(AudioUtils.formatTimeMs(pos));
                    List<Float> vals = rangeSlider.getValues();
                    long endMs = Math.round(vals.get(1) * 1000f);
                    if (pos >= endMs || !videoView.isPlaying()) {
                        pauseVideoPreview();
                    } else {
                        handler.postDelayed(this, 150);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    };

    private final ActivityResultLauncher<String[]> pickVideoLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null && getContext() != null) {
                    pauseVideoPreview();
                    selectedVideo = AudioUtils.getVideoInfo(getContext(), uri);
                    setupEditorVideoUi();
                }
            });

    private final ActivityResultLauncher<String[]> pickCustomAudioLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null && getContext() != null) {
                    customAudioUri = uri;
                    customAudioName = AudioUtils.getFileName(getContext(), uri);
                    tvCustomAudioName.setText("Выбран: " + customAudioName);
                }
            });

    private final ActivityResultLauncher<String[]> pickMergeVideosLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris != null && !uris.isEmpty() && getContext() != null) {
                    for (Uri uri : uris) {
                        mergeVideos.add(AudioUtils.getVideoInfo(getContext(), uri));
                    }
                    refreshMergeUi();
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_video, container, false);

        layoutEditorMode = view.findViewById(R.id.layoutVideoEditorMode);
        layoutMergeMode = view.findViewById(R.id.layoutVideoMergeMode);
        layoutPreviewBox = view.findViewById(R.id.layoutVideoPreviewBox);
        layoutFillMode = view.findViewById(R.id.layoutVideoFillMode);
        layoutCustomMp3Box = view.findViewById(R.id.layoutCustomMp3Box);
        layoutProgress = view.findViewById(R.id.layoutVideoProgress);

        cardTransform = view.findViewById(R.id.cardVideoTransform);
        cardAudioSpeed = view.findViewById(R.id.cardVideoAudioSpeed);
        cardResult = view.findViewById(R.id.cardVideoResult);

        videoView = view.findViewById(R.id.videoViewPreview);
        tvVideoInfo = view.findViewById(R.id.tvVideoInfo);
        tvPreviewBadge = view.findViewById(R.id.tvVideoPreviewBadge);
        tvCurrentPos = view.findViewById(R.id.tvVideoCurrentPos);
        tvStart = view.findViewById(R.id.tvVideoStart);
        tvDuration = view.findViewById(R.id.tvVideoDuration);
        tvEnd = view.findViewById(R.id.tvVideoEnd);
        tvCustomAudioName = view.findViewById(R.id.tvVideoCustomAudioName);
        tvStatus = view.findViewById(R.id.tvVideoStatus);
        tvResultInfo = view.findViewById(R.id.tvVideoResultInfo);
        tvMergeEmpty = view.findViewById(R.id.tvVideoMergeEmpty);

        btnPlayPause = view.findViewById(R.id.btnVideoPlayPause);
        btnStartExport = view.findViewById(R.id.btnStartVideoExport);
        btnExtractMp3 = view.findViewById(R.id.btnExtractMp3FromVideo);
        btnClearMerge = view.findViewById(R.id.btnClearVideoMerge);
        btnStartMerge = view.findViewById(R.id.btnStartVideoMerge);
        btnCancel = view.findViewById(R.id.btnCancelVideo);

        rangeSlider = view.findViewById(R.id.rangeSliderVideo);
        switchFlipH = view.findViewById(R.id.switchFlipH);
        switchFlipV = view.findViewById(R.id.switchFlipV);
        etOutputName = view.findViewById(R.id.etVideoOutputName);
        etMergeOutputName = view.findViewById(R.id.etVideoMergeOutputName);
        progressBar = view.findViewById(R.id.progressVideo);

        setupModeSwitch(view);
        setupEditorControls(view);
        setupMergeControls(view);
        setupSharedControls(view);

        return view;
    }

    private void setupModeSwitch(View view) {
        ChipGroup modeGroup = view.findViewById(R.id.chipGroupVideoMode);
        modeGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            pauseVideoPreview();
            int id = checkedIds.get(0);
            boolean isEdit = (id == R.id.chipVideoModeEdit);
            layoutEditorMode.setVisibility(isEdit ? View.VISIBLE : View.GONE);
            layoutMergeMode.setVisibility(isEdit ? View.GONE : View.VISIBLE);
            cardResult.setVisibility(View.GONE);
        });
    }

    private void setupEditorControls(View view) {
        view.findViewById(R.id.btnPickVideo).setOnClickListener(v ->
                pickVideoLauncher.launch(new String[]{"video/*"}));

        btnPlayPause.setOnClickListener(v -> toggleVideoPreview());
        view.findViewById(R.id.btnVideoPlayEnd).setOnClickListener(v -> playVideoEnding());

        rangeSlider.addOnChangeListener((slider, value, fromUser) -> {
            updateTrimLabels();
            if (fromUser && selectedVideo != null) {
                int seekMs = Math.round(value * 1000f);
                try {
                    videoView.seekTo(seekMs);
                    tvCurrentPos.setText(AudioUtils.formatTimeMs(seekMs));
                } catch (Exception ignored) {
                }
            }
        });

        view.findViewById(R.id.btnVidStartMinus).setOnClickListener(v -> adjustTrim(-1f, 0f));
        view.findViewById(R.id.btnVidStartPlus).setOnClickListener(v -> adjustTrim(1f, 0f));
        view.findViewById(R.id.btnVidEndMinus).setOnClickListener(v -> adjustTrim(0f, -1f));
        view.findViewById(R.id.btnVidEndPlus).setOnClickListener(v -> adjustTrim(0f, 1f));

        ChipGroup rotGroup = view.findViewById(R.id.chipGroupVideoRotate);
        rotGroup.setOnCheckedStateChangeListener((g, ids) -> {
            if (ids.isEmpty()) return;
            int id = ids.get(0);
            if (id == R.id.chipRot0) rotateDeg = 0;
            else if (id == R.id.chipRot90) rotateDeg = 90;
            else if (id == R.id.chipRot180) rotateDeg = 180;
            else if (id == R.id.chipRot270) rotateDeg = 270;
            applyVisualPreviewTransform();
        });

        switchFlipH.setOnCheckedChangeListener((b, checked) -> {
            flipH = checked;
            applyVisualPreviewTransform();
        });
        switchFlipV.setOnCheckedChangeListener((b, checked) -> {
            flipV = checked;
            applyVisualPreviewTransform();
        });

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

        btnExtractMp3.setOnClickListener(v -> extractMp3FromSelectedVideo());

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

        ChipGroup resGroup = view.findViewById(R.id.chipGroupVideoRes);
        resGroup.setOnCheckedStateChangeListener((g, ids) -> {
            if (ids.isEmpty()) return;
            int id = ids.get(0);
            if (id == R.id.chipResOrig) targetRes = 0;
            else if (id == R.id.chipRes1080) targetRes = 1080;
            else if (id == R.id.chipRes720) targetRes = 720;
            else if (id == R.id.chipRes480) targetRes = 480;
        });

        btnStartExport.setOnClickListener(v -> startVideoExport());
    }

    private void setupMergeControls(View view) {
        RecyclerView rv = view.findViewById(R.id.rvVideoMerge);
        mergeAdapter = new VideoMergeAdapter(mergeVideos, new VideoMergeAdapter.OnVideoActionListener() {
            @Override
            public void onMoveUp(int position) {
                if (position > 0) {
                    Collections.swap(mergeVideos, position, position - 1);
                    mergeAdapter.notifyDataSetChanged();
                }
            }

            @Override
            public void onMoveDown(int position) {
                if (position >= 0 && position < mergeVideos.size() - 1) {
                    Collections.swap(mergeVideos, position, position + 1);
                    mergeAdapter.notifyDataSetChanged();
                }
            }

            @Override
            public void onRemove(int position) {
                if (position >= 0 && position < mergeVideos.size()) {
                    mergeVideos.remove(position);
                    refreshMergeUi();
                }
            }
        });
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        rv.setAdapter(mergeAdapter);

        view.findViewById(R.id.btnAddVideosMerge).setOnClickListener(v ->
                pickMergeVideosLauncher.launch(new String[]{"video/*"}));

        btnClearMerge.setOnClickListener(v -> {
            mergeVideos.clear();
            refreshMergeUi();
        });

        ChipGroup mergeAspectGroup = view.findViewById(R.id.chipGroupVidMergeAspect);
        mergeAspectGroup.setOnCheckedStateChangeListener((g, ids) -> {
            if (ids.isEmpty()) return;
            int id = ids.get(0);
            if (id == R.id.chipMergeVid169) mergeAspect = ASPECT_16_9;
            else if (id == R.id.chipMergeVid916) mergeAspect = ASPECT_9_16;
            else if (id == R.id.chipMergeVid11) mergeAspect = ASPECT_1_1;
        });

        ChipGroup mergeResGroup = view.findViewById(R.id.chipGroupVidMergeRes);
        mergeResGroup.setOnCheckedStateChangeListener((g, ids) -> {
            if (ids.isEmpty()) return;
            int id = ids.get(0);
            if (id == R.id.chipMergeVid1080) mergeRes = 1080;
            else if (id == R.id.chipMergeVid720) mergeRes = 720;
            else if (id == R.id.chipMergeVid480) mergeRes = 480;
        });

        btnStartMerge.setOnClickListener(v -> startVideoMerge());
    }

    private void setupSharedControls(View view) {
        btnCancel.setOnClickListener(v -> {
            isCancelled.set(true);
            btnCancel.setEnabled(false);
            tvStatus.setText("Отмена...");
            if (activeSessionId > 0) {
                FFmpegKit.cancel(activeSessionId);
            } else {
                FFmpegKit.cancel();
            }
        });

        view.findViewById(R.id.btnVideoOpenResult).setOnClickListener(v -> {
            if (lastResultUri != null && getContext() != null) {
                if (lastResultIsVideo) {
                    AudioUtils.openVideo(getContext(), lastResultUri);
                } else {
                    AudioUtils.openAudio(getContext(), lastResultUri);
                }
            }
        });

        view.findViewById(R.id.btnVideoShareResult).setOnClickListener(v -> {
            if (lastResultUri != null && getContext() != null) {
                if (lastResultIsVideo) {
                    AudioUtils.shareVideo(getContext(), lastResultUri);
                } else {
                    AudioUtils.shareAudio(getContext(), lastResultUri);
                }
            }
        });
    }

    private void setupEditorVideoUi() {
        if (selectedVideo == null || selectedVideo.durationMs < 500) {
            Toast.makeText(getContext(), "Видео слишком короткое или не поддерживается", Toast.LENGTH_SHORT).show();
            return;
        }
        String resStr = (selectedVideo.getDisplayWidth() > 0 && selectedVideo.getDisplayHeight() > 0)
                ? (selectedVideo.getDisplayWidth() + "×" + selectedVideo.getDisplayHeight() + "  •  ") : "";
        tvVideoInfo.setText(
                selectedVideo.displayName + "\n" +
                resStr + AudioUtils.formatTimeMs(selectedVideo.durationMs) +
                "  •  " + AudioUtils.formatSize(selectedVideo.sizeBytes)
        );

        float totalSec = Math.max(1f, selectedVideo.durationMs / 1000f);
        rangeSlider.setValueFrom(0f);
        rangeSlider.setValueTo(totalSec);
        rangeSlider.setValues(0f, totalSec);
        updateTrimLabels();

        etOutputName.setText(AudioUtils.stripExtension(selectedVideo.displayName) + "_edit");

        layoutPreviewBox.setVisibility(View.VISIBLE);
        cardTransform.setVisibility(View.VISIBLE);
        cardAudioSpeed.setVisibility(View.VISIBLE);
        btnStartExport.setEnabled(true);
        cardResult.setVisibility(View.GONE);

        try {
            videoView.setVideoURI(selectedVideo.uri);
            videoView.setOnPreparedListener(mp -> {
                videoView.seekTo(100);
                applyVisualPreviewTransform();
            });
            videoView.setOnCompletionListener(mp -> pauseVideoPreview());
        } catch (Exception ignored) {
        }
    }

    private void applyVisualPreviewTransform() {
        if (videoView == null) return;
        videoView.setRotation(rotateDeg);
        videoView.setScaleX(flipH ? -1f : 1f);
        videoView.setScaleY(flipV ? -1f : 1f);
        if (rotateDeg != 0 || flipH || flipV) {
            StringBuilder badge = new StringBuilder();
            if (rotateDeg != 0) badge.append(rotateDeg).append("°");
            if (flipH) {
                if (badge.length() > 0) badge.append(" ");
                badge.append("↔");
            }
            if (flipV) {
                if (badge.length() > 0) badge.append(" ");
                badge.append("↕");
            }
            tvPreviewBadge.setText(badge.toString());
            tvPreviewBadge.setVisibility(View.VISIBLE);
        } else {
            tvPreviewBadge.setVisibility(View.GONE);
        }
    }

    private void adjustTrim(float deltaStart, float deltaEnd) {
        if (selectedVideo == null) return;
        List<Float> vals = rangeSlider.getValues();
        float minV = rangeSlider.getValueFrom();
        float maxV = rangeSlider.getValueTo();
        float start = Math.max(minV, Math.min(vals.get(0) + deltaStart, vals.get(1) - 0.5f));
        float end = Math.min(maxV, Math.max(vals.get(1) + deltaEnd, start + 0.5f));
        rangeSlider.setValues(start, end);
        updateTrimLabels();
        try {
            videoView.seekTo(Math.round((deltaStart != 0 ? start : end) * 1000f));
        } catch (Exception ignored) {
        }
    }

    private void updateTrimLabels() {
        List<Float> vals = rangeSlider.getValues();
        if (vals.size() < 2) return;
        long sMs = Math.round(vals.get(0) * 1000f);
        long eMs = Math.round(vals.get(1) * 1000f);
        tvStart.setText("Начало: " + AudioUtils.formatTimeMs(sMs));
        tvEnd.setText("Конец: " + AudioUtils.formatTimeMs(eMs));
        tvDuration.setText("Длина: " + AudioUtils.formatTimeMs(Math.max(0, eMs - sMs)));
    }

    private void toggleVideoPreview() {
        if (isPlayingPreview) {
            pauseVideoPreview();
            return;
        }
        if (selectedVideo == null) return;
        List<Float> vals = rangeSlider.getValues();
        int startMs = Math.round(vals.get(0) * 1000f);
        int endMs = Math.round(vals.get(1) * 1000f);
        try {
            int cur = videoView.getCurrentPosition();
            if (cur < startMs || cur >= endMs - 200) {
                videoView.seekTo(startMs);
            }
            videoView.start();
            isPlayingPreview = true;
            btnPlayPause.setText("Пауза");
            btnPlayPause.setIconResource(R.drawable.ic_pause);
            handler.post(previewUpdater);
        } catch (Exception e) {
            Toast.makeText(getContext(), "Не удалось воспроизвести видео", Toast.LENGTH_SHORT).show();
        }
    }

    private void playVideoEnding() {
        if (selectedVideo == null) return;
        List<Float> vals = rangeSlider.getValues();
        int startMs = Math.round(vals.get(0) * 1000f);
        int endMs = Math.round(vals.get(1) * 1000f);
        int jumpMs = Math.max(startMs, endMs - 5000);
        try {
            videoView.seekTo(jumpMs);
            videoView.start();
            isPlayingPreview = true;
            btnPlayPause.setText("Пауза");
            btnPlayPause.setIconResource(R.drawable.ic_pause);
            handler.post(previewUpdater);
        } catch (Exception ignored) {
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
        if (btnPlayPause != null) {
            btnPlayPause.setText("Играть");
            btnPlayPause.setIconResource(R.drawable.ic_play);
        }
    }

    private void refreshMergeUi() {
        mergeAdapter.notifyDataSetChanged();
        boolean hasItems = !mergeVideos.isEmpty();
        tvMergeEmpty.setVisibility(hasItems ? View.GONE : View.VISIBLE);
        btnClearMerge.setVisibility(hasItems ? View.VISIBLE : View.GONE);
        btnStartMerge.setEnabled(mergeVideos.size() >= 2);
        cardResult.setVisibility(View.GONE);
    }

    private int[] computeTargetSize(int aspect, int resPref) {
        int r = (resPref == 0) ? 720 : resPref;
        if (aspect == ASPECT_16_9) {
            if (r == 1080) return new int[]{1920, 1080};
            if (r == 480) return new int[]{854, 480};
            return new int[]{1280, 720};
        } else if (aspect == ASPECT_9_16) {
            if (r == 1080) return new int[]{1080, 1920};
            if (r == 480) return new int[]{480, 854};
            return new int[]{720, 1280};
        } else if (aspect == ASPECT_1_1) {
            if (r == 1080) return new int[]{1080, 1080};
            if (r == 480) return new int[]{480, 480};
            return new int[]{720, 720};
        } else { // 4:5
            if (r == 1080) return new int[]{1080, 1350};
            if (r == 480) return new int[]{480, 600};
            return new int[]{720, 900};
        }
    }

    private void extractMp3FromSelectedVideo() {
        if (selectedVideo == null || getContext() == null) return;
        pauseVideoPreview();

        List<Float> vals = rangeSlider.getValues();
        float startSec = vals.get(0);
        float endSec = vals.get(1);
        long clipDurMs = Math.round((endSec - startSec) * 1000f);
        String baseName = AudioUtils.stripExtension(selectedVideo.displayName) + "_audio";
        final Context appCtx = requireContext().getApplicationContext();

        beginTaskUi("Извлечение MP3", "Подготовка видео...");

        executor.execute(() -> {
            File inTemp = null;
            File outTemp = null;
            try {
                inTemp = AudioUtils.copyUriToCacheWithExt(appCtx, selectedVideo.uri, "vid_ext", ".mp4");
                if (isCancelled.get()) {
                    //noinspection ResultOfMethodCallIgnored
                    inTemp.delete();
                    finishCancelled(appCtx, "Извлечение отменено");
                    return;
                }
                outTemp = new File(appCtx.getCacheDir(), "vid_ext_" + System.currentTimeMillis() + ".mp3");

                String[] args = new String[]{
                        "-y",
                        "-ss", String.format(Locale.US, "%.2f", startSec),
                        "-to", String.format(Locale.US, "%.2f", endSec),
                        "-i", inTemp.getAbsolutePath(),
                        "-vn",
                        "-c:a", "libmp3lame",
                        "-b:a", "192k",
                        outTemp.getAbsolutePath()
                };

                final File finalIn = inTemp;
                final File finalOut = outTemp;
                final long[] lastNotif = {0L};

                var session = FFmpegKit.executeWithArgumentsAsync(args,
                        s -> {
                            activeSessionId = -1;
                            AudioProcessingService.stopTask(appCtx);
                            if (isCancelled.get() || ReturnCode.isCancel(s.getReturnCode())) {
                                finishCancelled(appCtx, "Извлечение отменено");
                            } else if (ReturnCode.isSuccess(s.getReturnCode()) && finalOut.exists()) {
                                try {
                                    long sz = finalOut.length();
                                    AudioUtils.SaveResult res = AudioUtils.saveMp3Output(appCtx, finalOut, baseName);
                                    lastResultUri = res.uri;
                                    lastResultIsVideo = false;
                                    runUi(() -> {
                                        finishUiButtons();
                                        cardResult.setVisibility(View.VISIBLE);
                                        tvResultInfo.setText(
                                                "MP3 извлечён! Сохранено в " + res.displayPath + "\n" +
                                                "Размер: " + AudioUtils.formatSize(sz) + " (192 кбит/с)"
                                        );
                                    });
                                } catch (Exception e) {
                                    finishError("Ошибка сохранения MP3: " + e.getMessage());
                                }
                            } else {
                                finishError("В этом видео нет звуковой дорожки или ошибка FFmpeg");
                            }
                            //noinspection ResultOfMethodCallIgnored
                            finalIn.delete();
                            //noinspection ResultOfMethodCallIgnored
                            finalOut.delete();
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

    private void startVideoExport() {
        if (selectedVideo == null || getContext() == null) return;
        pauseVideoPreview();

        if (audioMode == AUDIO_CUSTOM && customAudioUri == null) {
            Toast.makeText(getContext(), "Сначала выберите MP3 для наложения", Toast.LENGTH_SHORT).show();
            return;
        }

        List<Float> vals = rangeSlider.getValues();
        float startSec = vals.get(0);
        float endSec = vals.get(1);
        long rawClipMs = Math.round((endSec - startSec) * 1000f);
        if (rawClipMs < 300) {
            Toast.makeText(getContext(), "Выберите отрезок длиннее 0.3 сек", Toast.LENGTH_SHORT).show();
            return;
        }

        String outName = etOutputName.getText() != null ? etOutputName.getText().toString().trim() : "";
        if (outName.isEmpty()) outName = AudioUtils.stripExtension(selectedVideo.displayName) + "_edit";

        final String finalOutName = outName;
        final int fRot = rotateDeg;
        final boolean fFlipH = flipH;
        final boolean fFlipV = flipV;
        final int fAspect = aspectMode;
        final int fFill = fillMode;
        final int fAudio = audioMode;
        final double fSpeed = speedFactor;
        final int fRes = targetRes;
        final Uri fCustomAudio = customAudioUri;
        final boolean fHasOrigAudio = selectedVideo.hasAudio;
        final long expectedOutMs = Math.round(rawClipMs / fSpeed);
        final Context appCtx = requireContext().getApplicationContext();

        boolean fastCopyOnly = (fRot == 0 && !fFlipH && !fFlipV && fAspect == ASPECT_ORIG
                && fAudio == AUDIO_ORIG && Math.abs(fSpeed - 1.0) < 0.01 && fRes == 0);

        beginTaskUi("Обработка видео", "Подготовка видео...");

        executor.execute(() -> {
            File inVid = null;
            File inAud = null;
            File outVid = null;
            try {
                inVid = AudioUtils.copyUriToCacheWithExt(appCtx, selectedVideo.uri, "vid_in", ".mp4");
                if (fAudio == AUDIO_CUSTOM && fCustomAudio != null) {
                    inAud = AudioUtils.copyUriToCacheWithExt(appCtx, fCustomAudio, "vid_aud", ".mp3");
                }
                if (isCancelled.get()) {
                    if (inVid != null) //noinspection ResultOfMethodCallIgnored
                        inVid.delete();
                    if (inAud != null) //noinspection ResultOfMethodCallIgnored
                        inAud.delete();
                    finishCancelled(appCtx, "Обработка отменена");
                    return;
                }
                outVid = new File(appCtx.getCacheDir(), "vid_out_" + System.currentTimeMillis() + ".mp4");

                String ssStr = String.format(Locale.US, "%.2f", startSec);
                String tStr = String.format(Locale.US, "%.2f", rawClipMs / 1000.0);

                List<String> args = new ArrayList<>();
                args.add("-y");
                args.add("-ss");
                args.add(ssStr);
                args.add("-t");
                args.add(tStr);
                args.add("-i");
                args.add(inVid.getAbsolutePath());

                if (fastCopyOnly) {
                    args.add("-c");
                    args.add("copy");
                    args.add("-movflags");
                    args.add("+faststart");
                    args.add(outVid.getAbsolutePath());
                } else {
                    if (fAudio == AUDIO_CUSTOM && inAud != null) {
                        args.add("-i");
                        args.add(inAud.getAbsolutePath());
                    }

                    // Build base video chain (rotate, flip, speed)
                    List<String> baseV = new ArrayList<>();
                    if (fRot == 90) baseV.add("transpose=1");
                    else if (fRot == 180) {
                        baseV.add("transpose=1");
                        baseV.add("transpose=1");
                    } else if (fRot == 270) baseV.add("transpose=2");

                    if (fFlipH) baseV.add("hflip");
                    if (fFlipV) baseV.add("vflip");
                    if (Math.abs(fSpeed - 1.0) > 0.01) {
                        baseV.add(String.format(Locale.US, "setpts=%.4f*PTS", 1.0 / fSpeed));
                    }

                    String baseChain = String.join(",", baseV);
                    StringBuilder fc = new StringBuilder();

                    if (fAspect != ASPECT_ORIG) {
                        int[] wh = computeTargetSize(fAspect, fRes);
                        int W = wh[0], H = wh[1];
                        String prefix = baseChain.isEmpty() ? "[0:v]" : ("[0:v]" + baseChain + ",");
                        if (fFill == FILL_BLUR) {
                            fc.append(prefix)
                              .append("split=2[bg][fg];")
                              .append("[bg]scale=").append(W).append(":").append(H)
                              .append(":force_original_aspect_ratio=increase,crop=").append(W).append(":").append(H)
                              .append(",boxblur=20:5[bgb];")
                              .append("[fg]scale=").append(W).append(":").append(H)
                              .append(":force_original_aspect_ratio=decrease[fgf];")
                              .append("[bgb][fgf]overlay=(W-w)/2:(H-h)/2,setsar=1,format=yuv420p[vout]");
                        } else if (fFill == FILL_CROP) {
                            fc.append(prefix)
                              .append("scale=").append(W).append(":").append(H)
                              .append(":force_original_aspect_ratio=increase,crop=").append(W).append(":").append(H)
                              .append(",setsar=1,format=yuv420p[vout]");
                        } else {
                            fc.append(prefix)
                              .append("scale=").append(W).append(":").append(H)
                              .append(":force_original_aspect_ratio=decrease,pad=").append(W).append(":").append(H)
                              .append(":(ow-iw)/2:(oh-ih)/2:black,setsar=1,format=yuv420p[vout]");
                        }
                    } else {
                        String prefix = baseChain.isEmpty() ? "[0:v]" : ("[0:v]" + baseChain + ",");
                        if (fRes > 0) {
                            fc.append(prefix)
                              .append("scale='if(gte(iw,ih),-2,").append(fRes)
                              .append(")':'if(gte(iw,ih),").append(fRes)
                              .append(",-2)',format=yuv420p[vout]");
                        } else {
                            fc.append(prefix).append("scale=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p[vout]");
                        }
                    }

                    boolean mapCustomAudio = (fAudio == AUDIO_CUSTOM && inAud != null);
                    boolean mapOrigAudio = (fAudio == AUDIO_ORIG && fHasOrigAudio);

                    if (mapOrigAudio && Math.abs(fSpeed - 1.0) > 0.01) {
                        fc.append(";[0:a]atempo=").append(String.format(Locale.US, "%.2f", fSpeed)).append("[aout]");
                    }

                    args.add("-filter_complex");
                    args.add(fc.toString());
                    args.add("-map");
                    args.add("[vout]");

                    if (mapCustomAudio) {
                        args.add("-map");
                        args.add("1:a:0");
                        args.add("-c:a");
                        args.add("aac");
                        args.add("-b:a");
                        args.add("128k");
                        args.add("-shortest");
                    } else if (mapOrigAudio) {
                        if (Math.abs(fSpeed - 1.0) > 0.01) {
                            args.add("-map");
                            args.add("[aout]");
                        } else {
                            args.add("-map");
                            args.add("0:a:0");
                        }
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

                final File fInVid = inVid;
                final File fInAud = inAud;
                final File fOutVid = outVid;
                final long[] lastNotif = {0L};

                runUi(() -> {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(0);
                    tvStatus.setText("Обработка видео: 0%");
                });

                var session = FFmpegKit.executeWithArgumentsAsync(args.toArray(new String[0]),
                        s -> {
                            activeSessionId = -1;
                            AudioProcessingService.stopTask(appCtx);
                            if (isCancelled.get() || ReturnCode.isCancel(s.getReturnCode())) {
                                finishCancelled(appCtx, "Обработка видео отменена");
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
                                                "Видео готово! Сохранено в " + res.displayPath + "\n" +
                                                "Размер: " + AudioUtils.formatSize(sz)
                                        );
                                    });
                                } catch (Exception e) {
                                    finishError("Ошибка сохранения: " + e.getMessage());
                                }
                            } else {
                                finishError("Ошибка FFmpeg при обработке видео");
                            }
                            if (fInVid != null) //noinspection ResultOfMethodCallIgnored
                                fInVid.delete();
                            if (fInAud != null) //noinspection ResultOfMethodCallIgnored
                                fInAud.delete();
                            if (fOutVid != null) //noinspection ResultOfMethodCallIgnored
                                fOutVid.delete();
                        },
                        log -> {},
                        stats -> updateTaskProgress(appCtx, "Обработка видео", stats.getTime(), expectedOutMs, lastNotif)
                );
                activeSessionId = session.getSessionId();
            } catch (Exception e) {
                activeSessionId = -1;
                AudioProcessingService.stopTask(appCtx);
                if (inVid != null) //noinspection ResultOfMethodCallIgnored
                    inVid.delete();
                if (inAud != null) //noinspection ResultOfMethodCallIgnored
                    inAud.delete();
                if (outVid != null) //noinspection ResultOfMethodCallIgnored
                    outVid.delete();
                finishError("Ошибка подготовки видео: " + e.getMessage());
            }
        });
    }

    private void startVideoMerge() {
        if (mergeVideos.size() < 2 || getContext() == null) return;
        pauseVideoPreview();

        String outName = etMergeOutputName.getText() != null ? etMergeOutputName.getText().toString().trim() : "";
        if (outName.isEmpty()) outName = "merged_video_" + System.currentTimeMillis();

        final String finalOutName = outName;
        final List<AudioUtils.VideoInfo> snapshot = new ArrayList<>(mergeVideos);
        final int[] wh = computeTargetSize(mergeAspect, mergeRes);
        final int W = wh[0], H = wh[1];
        final Context appCtx = requireContext().getApplicationContext();

        long totalMs = 0;
        boolean anyHasAudio = false;
        for (AudioUtils.VideoInfo v : snapshot) {
            totalMs += v.durationMs;
            if (v.hasAudio) anyHasAudio = true;
        }
        final long expectedMs = totalMs;
        final boolean includeAudio = anyHasAudio;

        beginTaskUi("Склейка видео", "Подготовка клипов (" + snapshot.size() + " шт.)...");

        executor.execute(() -> {
            List<File> tempFiles = new ArrayList<>();
            File outVid = null;
            try {
                for (int i = 0; i < snapshot.size(); i++) {
                    if (isCancelled.get()) {
                        for (File f : tempFiles) //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        finishCancelled(appCtx, "Склейка видео отменена");
                        return;
                    }
                    tempFiles.add(AudioUtils.copyUriToCacheWithExt(appCtx, snapshot.get(i).uri, "vmerge_" + i, ".mp4"));
                }
                outVid = new File(appCtx.getCacheDir(), "vmerge_out_" + System.currentTimeMillis() + ".mp4");

                List<String> args = new ArrayList<>();
                args.add("-y");
                StringBuilder fc = new StringBuilder();
                StringBuilder concatIn = new StringBuilder();

                for (int i = 0; i < tempFiles.size(); i++) {
                    AudioUtils.VideoInfo vi = snapshot.get(i);
                    args.add("-i");
                    args.add(tempFiles.get(i).getAbsolutePath());

                    fc.append("[").append(i).append(":v]")
                      .append("scale=").append(W).append(":").append(H)
                      .append(":force_original_aspect_ratio=decrease,pad=").append(W).append(":").append(H)
                      .append(":(ow-iw)/2:(oh-ih)/2:black,setsar=1,fps=30,format=yuv420p[v").append(i).append("];");

                    if (includeAudio) {
                        if (vi.hasAudio) {
                            fc.append("[").append(i).append(":a]")
                              .append("aformat=sample_rates=44100:channel_layouts=stereo[a").append(i).append("];");
                        } else {
                            double durSec = Math.max(0.5, vi.durationMs / 1000.0);
                            fc.append("anullsrc=r=44100:cl=stereo,atrim=0:")
                              .append(String.format(Locale.US, "%.2f", durSec))
                              .append(",asetpts=PTS-STARTPTS[a").append(i).append("];");
                        }
                        concatIn.append("[v").append(i).append("][a").append(i).append("]");
                    } else {
                        concatIn.append("[v").append(i).append("]");
                    }
                }

                if (includeAudio) {
                    fc.append(concatIn).append("concat=n=").append(tempFiles.size()).append(":v=1:a=1[vout][aout]");
                } else {
                    fc.append(concatIn).append("concat=n=").append(tempFiles.size()).append(":v=1:a=0[vout]");
                }

                args.add("-filter_complex");
                args.add(fc.toString());
                args.add("-map");
                args.add("[vout]");
                if (includeAudio) {
                    args.add("-map");
                    args.add("[aout]");
                    args.add("-c:a");
                    args.add("aac");
                    args.add("-b:a");
                    args.add("128k");
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

                final File fOutVid = outVid;
                final long[] lastNotif = {0L};

                runUi(() -> {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(0);
                    tvStatus.setText("Склейка видео: 0%");
                });

                var session = FFmpegKit.executeWithArgumentsAsync(args.toArray(new String[0]),
                        s -> {
                            activeSessionId = -1;
                            AudioProcessingService.stopTask(appCtx);
                            if (isCancelled.get() || ReturnCode.isCancel(s.getReturnCode())) {
                                finishCancelled(appCtx, "Склейка видео отменена");
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
                                                "Готово! Склеено видео: " + snapshot.size() + "\n" +
                                                "Сохранено в " + res.displayPath + "\n" +
                                                "Размер: " + AudioUtils.formatSize(sz)
                                        );
                                    });
                                } catch (Exception e) {
                                    finishError("Ошибка сохранения: " + e.getMessage());
                                }
                            } else {
                                finishError("Ошибка FFmpeg при склейке видео");
                            }
                            for (File f : tempFiles) //noinspection ResultOfMethodCallIgnored
                                f.delete();
                            if (fOutVid != null) //noinspection ResultOfMethodCallIgnored
                                fOutVid.delete();
                        },
                        log -> {},
                        stats -> updateTaskProgress(appCtx, "Склейка видео", stats.getTime(), expectedMs, lastNotif)
                );
                activeSessionId = session.getSessionId();
            } catch (Exception e) {
                activeSessionId = -1;
                AudioProcessingService.stopTask(appCtx);
                for (File f : tempFiles) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                if (outVid != null) //noinspection ResultOfMethodCallIgnored
                    outVid.delete();
                finishError("Ошибка подготовки клипов: " + e.getMessage());
            }
        });
    }

    private void beginTaskUi(String notifTitle, String status) {
        isCancelled.set(false);
        activeSessionId = -1;
        btnStartExport.setEnabled(false);
        btnExtractMp3.setEnabled(false);
        btnStartMerge.setEnabled(false);
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
        btnStartExport.setEnabled(selectedVideo != null);
        btnExtractMp3.setEnabled(selectedVideo != null);
        btnStartMerge.setEnabled(mergeVideos.size() >= 2);
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
