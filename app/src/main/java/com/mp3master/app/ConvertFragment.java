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
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ConvertFragment extends Fragment {

    private AudioUtils.AudioInfo selectedAudio;
    private int selectedBitrate = 128;
    private Uri lastSavedUri;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView tvFileInfo, tvBitrateHint, tvStatus, tvResultInfo;
    private TextInputEditText etOutputName;
    private MaterialButton btnStartConvert;
    private View layoutProgress;
    private LinearProgressIndicator progressBar;
    private MaterialCardView cardResult;

    private final ActivityResultLauncher<String[]> pickAudioLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null && getContext() != null) {
                    selectedAudio = AudioUtils.getAudioInfo(getContext(), uri);
                    updateFileUi();
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_convert, container, false);

        MaterialButton btnPick = view.findViewById(R.id.btnPickConvertFile);
        tvFileInfo = view.findViewById(R.id.tvConvertFileInfo);
        ChipGroup chipGroup = view.findViewById(R.id.chipGroupConvertBitrate);
        tvBitrateHint = view.findViewById(R.id.tvBitrateHint);
        etOutputName = view.findViewById(R.id.etConvertOutputName);
        btnStartConvert = view.findViewById(R.id.btnStartConvert);
        layoutProgress = view.findViewById(R.id.layoutConvertProgress);
        progressBar = view.findViewById(R.id.progressConvert);
        tvStatus = view.findViewById(R.id.tvConvertStatus);
        cardResult = view.findViewById(R.id.cardConvertResult);
        tvResultInfo = view.findViewById(R.id.tvConvertResultInfo);
        MaterialButton btnOpen = view.findViewById(R.id.btnConvertOpen);
        MaterialButton btnShare = view.findViewById(R.id.btnConvertShare);

        btnPick.setOnClickListener(v -> pickAudioLauncher.launch(new String[]{"audio/mpeg", "audio/*"}));

        chipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chip64) {
                selectedBitrate = 64;
                tvBitrateHint.setText("64 кбит/с — минимальный размер (подходит для речи и аудиокниг)");
            } else if (id == R.id.chip96) {
                selectedBitrate = 96;
                tvBitrateHint.setText("96 кбит/с — компактный размер с хорошим качеством речи и музыки");
            } else if (id == R.id.chip128) {
                selectedBitrate = 128;
                tvBitrateHint.setText("128 кбит/с — оптимальный баланс размера и качества");
            } else if (id == R.id.chip192) {
                selectedBitrate = 192;
                tvBitrateHint.setText("192 кбит/с — хорошее качество для музыки");
            } else if (id == R.id.chip256) {
                selectedBitrate = 256;
                tvBitrateHint.setText("256 кбит/с — высокое качество звука");
            } else if (id == R.id.chip320) {
                selectedBitrate = 320;
                tvBitrateHint.setText("320 кбит/с — максимальное качество MP3");
            }
            if (selectedAudio != null) {
                etOutputName.setText(AudioUtils.stripExtension(selectedAudio.displayName) + "_" + selectedBitrate + "k");
            }
        });

        btnStartConvert.setOnClickListener(v -> startConversion());
        btnOpen.setOnClickListener(v -> {
            if (lastSavedUri != null && getContext() != null) {
                AudioUtils.openAudio(getContext(), lastSavedUri);
            }
        });
        btnShare.setOnClickListener(v -> {
            if (lastSavedUri != null && getContext() != null) {
                AudioUtils.shareAudio(getContext(), lastSavedUri);
            }
        });

        return view;
    }

    private void updateFileUi() {
        if (selectedAudio == null) return;
        String brText = selectedAudio.bitrateKbps > 0 ? (selectedAudio.bitrateKbps + " кбит/с") : "неизвестно";
        tvFileInfo.setText(
                selectedAudio.displayName + "\n" +
                "Длительность: " + AudioUtils.formatTimeMs(selectedAudio.durationMs) +
                "  •  Битрейт: " + brText +
                "  •  Размер: " + AudioUtils.formatSize(selectedAudio.sizeBytes)
        );
        etOutputName.setText(AudioUtils.stripExtension(selectedAudio.displayName) + "_" + selectedBitrate + "k");
        btnStartConvert.setEnabled(true);
        cardResult.setVisibility(View.GONE);
    }

    private void startConversion() {
        if (selectedAudio == null || getContext() == null) return;

        String outputName = etOutputName.getText() != null ? etOutputName.getText().toString().trim() : "";
        if (outputName.isEmpty()) {
            outputName = AudioUtils.stripExtension(selectedAudio.displayName) + "_" + selectedBitrate + "k";
        }
        final String finalOutputName = outputName;

        btnStartConvert.setEnabled(false);
        cardResult.setVisibility(View.GONE);
        layoutProgress.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        tvStatus.setText("Подготовка файла...");

        executor.execute(() -> {
            File inputTemp = null;
            File outputTemp = null;
            try {
                inputTemp = AudioUtils.copyUriToCache(requireContext(), selectedAudio.uri, "conv_in");
                outputTemp = new File(requireContext().getCacheDir(), "conv_out_" + System.currentTimeMillis() + ".mp3");

                String[] args = new String[]{
                        "-y",
                        "-i", inputTemp.getAbsolutePath(),
                        "-vn",
                        "-c:a", "libmp3lame",
                        "-b:a", selectedBitrate + "k",
                        "-map_metadata", "0",
                        outputTemp.getAbsolutePath()
                };

                final File finalInput = inputTemp;
                final File finalOutput = outputTemp;

                requireActivity().runOnUiThread(() -> {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(0);
                    tvStatus.setText("Конвертация: 0%");
                });

                FFmpegKit.executeWithArgumentsAsync(args,
                        session -> {
                            if (ReturnCode.isSuccess(session.getReturnCode())) {
                                try {
                                    long newSize = finalOutput.length();
                                    AudioUtils.SaveResult res = AudioUtils.saveMp3Output(requireContext(), finalOutput, finalOutputName);
                                    lastSavedUri = res.uri;
                                    requireActivity().runOnUiThread(() -> {
                                        layoutProgress.setVisibility(View.GONE);
                                        btnStartConvert.setEnabled(true);
                                        cardResult.setVisibility(View.VISIBLE);
                                        tvResultInfo.setText(
                                                "Готово! Сохранено в " + res.displayPath + "\n" +
                                                "Размер: " + AudioUtils.formatSize(selectedAudio.sizeBytes) +
                                                " → " + AudioUtils.formatSize(newSize) +
                                                " (" + selectedBitrate + " кбит/с)"
                                        );
                                    });
                                } catch (Exception e) {
                                    showError("Ошибка сохранения: " + e.getMessage());
                                }
                            } else {
                                showError("Ошибка FFmpeg при конвертации");
                            }
                            //noinspection ResultOfMethodCallIgnored
                            finalInput.delete();
                            //noinspection ResultOfMethodCallIgnored
                            finalOutput.delete();
                        },
                        log -> {},
                        statistics -> {
                            if (selectedAudio.durationMs > 0) {
                                int pct = (int) Math.min(99, Math.round((statistics.getTime() / selectedAudio.durationMs) * 100.0));
                                if (isAdded()) {
                                    requireActivity().runOnUiThread(() -> {
                                        progressBar.setProgress(pct);
                                        tvStatus.setText("Конвертация: " + pct + "%");
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
            btnStartConvert.setEnabled(true);
            Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
        });
    }
}
