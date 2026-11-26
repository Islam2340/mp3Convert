package com.mp3master.app;

import android.net.Uri;
import android.os.Bundle;
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
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TrimFragment extends Fragment {

    private AudioUtils.AudioInfo selectedAudio;
    private Uri lastSavedUri;
    private TrimEditorController editorController;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView tvFileInfo, tvStatus, tvResultInfo;
    private MaterialCardView cardControls, cardResult;
    private MaterialButton btnStartTrim;
    private MaterialSwitch switchFast;
    private TextInputEditText etOutputName;
    private View layoutProgress;
    private LinearProgressIndicator progressBar;

    private final ActivityResultLauncher<String[]> pickAudioLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null && getContext() != null) {
                    if (editorController != null) editorController.stopPlayback();
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
        switchFast = view.findViewById(R.id.switchTrimFast);
        etOutputName = view.findViewById(R.id.etTrimOutputName);
        btnStartTrim = view.findViewById(R.id.btnStartTrim);
        layoutProgress = view.findViewById(R.id.layoutTrimProgress);
        progressBar = view.findViewById(R.id.progressTrim);
        tvStatus = view.findViewById(R.id.tvTrimStatus);
        cardResult = view.findViewById(R.id.cardTrimResult);
        tvResultInfo = view.findViewById(R.id.tvTrimResultInfo);

        editorController = new TrimEditorController(requireContext(), view);

        btnPick.setOnClickListener(v -> pickAudioLauncher.launch(new String[]{"audio/mpeg", "audio/*"}));
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

        tvFileInfo.setText(
                selectedAudio.displayName + "\n" +
                "Длительность: " + AudioUtils.formatTimeMs(selectedAudio.durationMs) +
                "  •  Размер: " + AudioUtils.formatSize(selectedAudio.sizeBytes)
        );

        editorController.bindAudio(selectedAudio, 0L, selectedAudio.durationMs);
        etOutputName.setText(AudioUtils.stripExtension(selectedAudio.displayName) + "_cut");
        cardControls.setVisibility(View.VISIBLE);
        btnStartTrim.setEnabled(true);
        cardResult.setVisibility(View.GONE);
    }

    private void startTrim() {
        if (selectedAudio == null || getContext() == null) return;
        editorController.stopPlayback();

        long startMs = editorController.getStartMs();
        long endMs = editorController.getEndMs();
        long clipDurationMs = endMs - startMs;
        if (clipDurationMs < 300) {
            Toast.makeText(getContext(), "Выберите отрезок длиннее 0.3 сек", Toast.LENGTH_SHORT).show();
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

                String startStr = String.format(Locale.US, "%.3f", startMs / 1000.0);
                String endStr = String.format(Locale.US, "%.3f", endMs / 1000.0);

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
                                                "Готово! Сохранено в Music/MP3Master/" + finalOutputName + ".mp3\n" +
                                                "Длительность: " + AudioUtils.formatTimePreciseMs(clipDurationMs) +
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
        if (editorController != null) editorController.stopPlayback();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden && editorController != null) {
            editorController.stopPlayback();
        }
    }
}
