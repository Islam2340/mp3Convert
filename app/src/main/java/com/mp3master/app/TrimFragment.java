package com.mp3master.app;

import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.slider.RangeSlider;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TrimFragment extends Fragment {

    private AudioUtils.AudioInfo selectedAudio;
    private Uri lastSavedUri;
    private MediaPlayer mediaPlayer;
    private boolean isPlaying = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView tvFileInfo, tvStart, tvEnd, tvDuration, tvCurrentPos, tvStatus, tvResultInfo;
    private MaterialCardView cardControls, cardResult;
    private RangeSlider rangeSlider;
    private MaterialButton btnPlayPause, btnStartTrim;
    private MaterialSwitch switchFast;
    private TextInputEditText etOutputName;
    private View layoutProgress;
    private LinearProgressIndicator progressBar;

    private final Runnable playbackUpdater = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && isPlaying) {
                int pos = mediaPlayer.getCurrentPosition();
                tvCurrentPos.setText(AudioUtils.formatTimeMs(pos));
                List<Float> vals = rangeSlider.getValues();
                long endMs = Math.round(vals.get(1) * 1000f);
                if (pos >= endMs) {
                    stopPreview();
                } else {
                    handler.postDelayed(this, 200);
                }
            }
        }
    };

    private final ActivityResultLauncher<String[]> pickAudioLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null && getContext() != null) {
                    stopPreview();
                    selectedAudio = AudioUtils.getAudioInfo(getContext(), uri);
                    setupTrimUi();
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_trim, container, false);

        MaterialButton btnPick = view.findViewById(R.id.btnPickTrimFile);
        tvFileInfo = view.findViewById(R.id.tvTrimFileInfo);
        cardControls = view.findViewById(R.id.cardTrimControls);
        btnPlayPause = view.findViewById(R.id.btnTrimPlayPause);
        tvCurrentPos = view.findViewById(R.id.tvTrimCurrentPos);
        rangeSlider = view.findViewById(R.id.rangeSliderTrim);
        tvStart = view.findViewById(R.id.tvTrimStart);
        tvDuration = view.findViewById(R.id.tvTrimDuration);
        tvEnd = view.findViewById(R.id.tvTrimEnd);
        switchFast = view.findViewById(R.id.switchTrimFast);
        etOutputName = view.findViewById(R.id.etTrimOutputName);
        btnStartTrim = view.findViewById(R.id.btnStartTrim);
        layoutProgress = view.findViewById(R.id.layoutTrimProgress);
        progressBar = view.findViewById(R.id.progressTrim);
        tvStatus = view.findViewById(R.id.tvTrimStatus);
        cardResult = view.findViewById(R.id.cardTrimResult);
        tvResultInfo = view.findViewById(R.id.tvTrimResultInfo);

        btnPick.setOnClickListener(v -> pickAudioLauncher.launch(new String[]{"audio/mpeg", "audio/*"}));
        btnPlayPause.setOnClickListener(v -> togglePreview());

        rangeSlider.addOnChangeListener((slider, value, fromUser) -> updateRangeLabels());

        view.findViewById(R.id.btnStartMinus).setOnClickListener(v -> adjustRange(-1f, 0f));
        view.findViewById(R.id.btnStartPlus).setOnClickListener(v -> adjustRange(1f, 0f));
        view.findViewById(R.id.btnEndMinus).setOnClickListener(v -> adjustRange(0f, -1f));
        view.findViewById(R.id.btnEndPlus).setOnClickListener(v -> adjustRange(0f, 1f));

        btnStartTrim.setOnClickListener(v -> startTrim());

        view.findViewById(R.id.btnTrimOpen).setOnClickListener(v -> {
            if (lastSavedUri != null && getContext() != null) {
                AudioUtils.openAudio(getContext(), lastSavedUri);
            }
        });
        view.findViewById(R.id.btnTrimShare).setOnClickListener(v -> {
            if (lastSavedUri != null && getContext() != null) {
                AudioUtils.shareAudio(getContext(), lastSavedUri);
            }
        });

        return view;
    }

    private void setupTrimUi() {
        if (selectedAudio == null || selectedAudio.durationMs < 1000) {
            Toast.makeText(getContext(), "Файл слишком короткий или повреждён", Toast.LENGTH_SHORT).show();
            return;
        }
        float totalSec = Math.max(2f, selectedAudio.durationMs / 1000f);

        tvFileInfo.setText(
                selectedAudio.displayName + "
" +
                "Длительность: " + AudioUtils.formatTimeMs(selectedAudio.durationMs) +
                "  •  Размер: " + AudioUtils.formatSize(selectedAudio.sizeBytes)
        );

        rangeSlider.setValueFrom(0f);
        rangeSlider.setValueTo(totalSec);
        rangeSlider.setValues(0f, totalSec);
        updateRangeLabels();

        etOutputName.setText(AudioUtils.stripExtension(selectedAudio.displayName) + "_cut");
        cardControls.setVisibility(View.VISIBLE);
        btnStartTrim.setEnabled(true);
        cardResult.setVisibility(View.GONE);
    }

    private void adjustRange(float deltaStart, float deltaEnd) {
        if (selectedAudio == null) return;
        List<Float> vals = rangeSlider.getValues();
        float minVal = rangeSlider.getValueFrom();
        float maxVal = rangeSlider.getValueTo();
        float start = Math.max(minVal, Math.min(vals.get(0) + deltaStart, vals.get(1) - 1f));
        float end = Math.min(maxVal, Math.max(vals.get(1) + deltaEnd, start + 1f));
        rangeSlider.setValues(start, end);
        updateRangeLabels();
    }

    private void updateRangeLabels() {
        List<Float> vals = rangeSlider.getValues();
        if (vals.size() < 2) return;
        long startMs = Math.round(vals.get(0) * 1000f);
        long endMs = Math.round(vals.get(1) * 1000f);
        tvStart.setText("Начало: " + AudioUtils.formatTimeMs(startMs));
        tvEnd.setText("Конец: " + AudioUtils.formatTimeMs(endMs));
        tvDuration.setText("Длина: " + AudioUtils.formatTimeMs(Math.max(0, endMs - startMs)));
    }

    private void togglePreview() {
        if (isPlaying) {
            stopPreview();
            return;
        }
        if (selectedAudio == null || getContext() == null) return;
        try {
            List<Float> vals = rangeSlider.getValues();
            int startMs = Math.round(vals.get(0) * 1000f);

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(getContext(), selectedAudio.uri);
            mediaPlayer.prepare();
            mediaPlayer.seekTo(startMs);
            mediaPlayer.start();
            isPlaying = true;
            btnPlayPause.setText("Пауза");
            btnPlayPause.setIconResource(R.drawable.ic_pause);
            handler.post(playbackUpdater);
        } catch (Exception e) {
            Toast.makeText(getContext(), "Не удалось воспроизвести: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            stopPreview();
        }
    }

    private void stopPreview() {
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
        if (btnPlayPause != null) {
            btnPlayPause.setText("Прослушать отрезок");
            btnPlayPause.setIconResource(R.drawable.ic_play);
        }
    }

    private void startTrim() {
        if (selectedAudio == null || getContext() == null) return;
        stopPreview();

        List<Float> vals = rangeSlider.getValues();
        float startSec = vals.get(0);
        float endSec = vals.get(1);
        long clipDurationMs = Math.round((endSec - startSec) * 1000f);
        if (clipDurationMs < 500) {
            Toast.makeText(getContext(), "Выберите отрезок длиннее 0.5 сек", Toast.LENGTH_SHORT).show();
            return;
        }

        String outputName = etOutputName.getText() != null ? etOutputName.getText().toString().trim() : "";
        if (outputName.isEmpty()) {
            outputName = AudioUtils.stripExtension(selectedAudio.displayName) + "_cut";
        }
        final String finalOutputName = outputName;
        final boolean fastCopy = switchFast.isChecked();

        btnStartTrim.setEnabled(false);
        cardResult.setVisibility(View.GONE);
        layoutProgress.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        tvStatus.setText("Подготовка файла...");

        executor.execute(() -> {
            File inputTemp = null;
            File outputTemp = null;
            try {
                inputTemp = AudioUtils.copyUriToCache(requireContext(), selectedAudio.uri, "trim_in");
                outputTemp = new File(requireContext().getCacheDir(), "trim_out_" + System.currentTimeMillis() + ".mp3");

                String startStr = String.format(Locale.US, "%.2f", startSec);
                String endStr = String.format(Locale.US, "%.2f", endSec);

                String[] args;
                if (fastCopy) {
                    args = new String[]{
                            "-y",
                            "-ss", startStr,
                            "-to", endStr,
                            "-i", inputTemp.getAbsolutePath(),
                            "-vn",
                            "-c:a", "copy",
                            "-map_metadata", "0",
                            outputTemp.getAbsolutePath()
                    };
                } else {
                    int targetBr = selectedAudio.bitrateKbps > 0 ? selectedAudio.bitrateKbps : 192;
                    args = new String[]{
                            "-y",
                            "-ss", startStr,
                            "-to", endStr,
                            "-i", inputTemp.getAbsolutePath(),
                            "-vn",
                            "-c:a", "libmp3lame",
                            "-b:a", targetBr + "k",
                            "-map_metadata", "0",
                            outputTemp.getAbsolutePath()
                    };
                }

                final File finalInput = inputTemp;
                final File finalOutput = outputTemp;

                requireActivity().runOnUiThread(() -> {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(0);
                    tvStatus.setText("Обрезка: 0%");
                });

                FFmpegKit.executeWithArgumentsAsync(args,
                        session -> {
                            if (ReturnCode.isSuccess(session.getReturnCode())) {
                                try {
                                    long newSize = finalOutput.length();
                                    Uri savedUri = AudioUtils.saveMp3ToMusic(requireContext(), finalOutput, finalOutputName);
                                    lastSavedUri = savedUri;
                                    requireActivity().runOnUiThread(() -> {
                                        layoutProgress.setVisibility(View.GONE);
                                        btnStartTrim.setEnabled(true);
                                        cardResult.setVisibility(View.VISIBLE);
                                        tvResultInfo.setText(
                                                "Готово! Сохранено в Music/MP3Master/" + finalOutputName + ".mp3
" +
                                                "Длительность: " + AudioUtils.formatTimeMs(clipDurationMs) +
                                                "  •  Размер: " + AudioUtils.formatSize(newSize)
                                        );
                                    });
                                } catch (Exception e) {
                                    showError("Ошибка сохранения: " + e.getMessage());
                                }
                            } else {
                                showError("Ошибка FFmpeg при обрезке");
                            }
                            //noinspection ResultOfMethodCallIgnored
                            finalInput.delete();
                            //noinspection ResultOfMethodCallIgnored
                            finalOutput.delete();
                        },
                        log -> {},
                        statistics -> {
                            if (clipDurationMs > 0) {
                                int pct = (int) Math.min(99, Math.round((statistics.getTime() / clipDurationMs) * 100.0));
                                if (isAdded()) {
                                    requireActivity().runOnUiThread(() -> {
                                        progressBar.setProgress(pct);
                                        tvStatus.setText("Обрезка: " + pct + "%");
                                    });
                                }
                            }
                        }
                );
            } catch (Exception e) {
                if (inputTemp != null) //noinspection ResultOfMethodCallIgnored
                    inputTemp.delete();
                if (outputTemp != null) //noinspection ResultOfMethodCallIgnored
                    outputTemp.delete();
                showError("Ошибка чтения файла: " + e.getMessage());
            }
        });
    }

    private void showError(String msg) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            layoutProgress.setVisibility(View.GONE);
            btnStartTrim.setEnabled(true);
            Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        stopPreview();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            stopPreview();
        }
    }
}
