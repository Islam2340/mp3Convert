package com.mp3master.app;

import android.content.Context;
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
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConvertFragment extends Fragment {

    private final List<AudioUtils.AudioInfo> selectedAudios = new ArrayList<>();
    private int selectedBitrate = 128;
    private Uri lastSavedUri;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private volatile long activeSessionId = -1;

    private TextView tvFileInfo, tvBitrateHint, tvBatchNameHint, tvStatus, tvResultInfo;
    private TextInputLayout tilOutputName;
    private TextInputEditText etOutputName;
    private MaterialButton btnClear, btnStartConvert, btnCancelConvert;
    private View layoutProgress, layoutResultActions;
    private LinearProgressIndicator progressBar;
    private MaterialCardView cardResult;

    private final ActivityResultLauncher<String[]> pickAudioLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris != null && !uris.isEmpty() && getContext() != null) {
                    for (Uri uri : uris) {
                        boolean exists = false;
                        for (AudioUtils.AudioInfo existing : selectedAudios) {
                            if (existing.uri.equals(uri)) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            selectedAudios.add(AudioUtils.getAudioInfo(getContext(), uri));
                        }
                    }
                    updateFileUi();
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_convert, container, false);

        MaterialButton btnPick = view.findViewById(R.id.btnPickConvertFile);
        btnClear = view.findViewById(R.id.btnClearConvertFiles);
        tvFileInfo = view.findViewById(R.id.tvConvertFileInfo);
        DrumPickerView drumBitrate = view.findViewById(R.id.drumConvertBitrate);
        final int[] bitrates = new int[]{64, 96, 128, 192, 256, 320};
        final String[] bitrateLabels = new String[]{"64 кбит/с", "96 кбит/с", "128 кбит/с", "192 кбит/с", "256 кбит/с", "320 кбит/с"};
        final String[] bitrateHints = new String[]{
                "64 кбит/с — минимальный размер (для речи и аудиокниг)",
                "96 кбит/с — компактный размер с хорошим качеством речи",
                "128 кбит/с — оптимальный баланс размера и качества",
                "192 кбит/с — хорошее качество для музыки",
                "256 кбит/с — высокое качество звука",
                "320 кбит/с — максимальное качество MP3"
        };
        drumBitrate.setItems(bitrateLabels, 2);
        tvBitrateHint = view.findViewById(R.id.tvBitrateHint);
        tilOutputName = view.findViewById(R.id.tilConvertOutputName);
        etOutputName = view.findViewById(R.id.etConvertOutputName);
        tvBatchNameHint = view.findViewById(R.id.tvBatchNameHint);
        btnStartConvert = view.findViewById(R.id.btnStartConvert);
        btnCancelConvert = view.findViewById(R.id.btnCancelConvert);
        layoutProgress = view.findViewById(R.id.layoutConvertProgress);
        progressBar = view.findViewById(R.id.progressConvert);
        tvStatus = view.findViewById(R.id.tvConvertStatus);
        cardResult = view.findViewById(R.id.cardConvertResult);
        tvResultInfo = view.findViewById(R.id.tvConvertResultInfo);
        layoutResultActions = view.findViewById(R.id.layoutConvertResultActions);
        MaterialButton btnOpen = view.findViewById(R.id.btnConvertOpen);
        MaterialButton btnShare = view.findViewById(R.id.btnConvertShare);

        btnPick.setOnClickListener(v -> pickAudioLauncher.launch(new String[]{"audio/mpeg", "audio/*"}));
        btnClear.setOnClickListener(v -> {
            selectedAudios.clear();
            updateFileUi();
        });

        drumBitrate.setOnValueChangedListener((idx, label) -> {
            selectedBitrate = bitrates[idx];
            tvBitrateHint.setText(bitrateHints[idx]);
            updateOutputNameHints();
        });

        btnStartConvert.setOnClickListener(v -> startConversion());
        btnCancelConvert.setOnClickListener(v -> {
            isCancelled.set(true);
            btnCancelConvert.setEnabled(false);
            tvStatus.setText("Отмена...");
            if (activeSessionId > 0) {
                FFmpegKit.cancel(activeSessionId);
            } else {
                FFmpegKit.cancel();
            }
        });

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
        int count = selectedAudios.size();
        if (count == 0) {
            tvFileInfo.setText("Файлы не выбраны");
            btnClear.setVisibility(View.GONE);
            btnStartConvert.setEnabled(false);
            btnStartConvert.setText("Конвертировать в MP3");
            tilOutputName.setVisibility(View.VISIBLE);
            tvBatchNameHint.setVisibility(View.GONE);
            etOutputName.setText("");
            cardResult.setVisibility(View.GONE);
            return;
        }

        btnClear.setVisibility(View.VISIBLE);
        btnStartConvert.setEnabled(true);
        cardResult.setVisibility(View.GONE);

        if (count == 1) {
            AudioUtils.AudioInfo info = selectedAudios.get(0);
            String brText = info.bitrateKbps > 0 ? (info.bitrateKbps + " кбит/с") : "неизвестно";
            tvFileInfo.setText(
                    info.displayName + "\n" +
                    "Длительность: " + AudioUtils.formatTimeMs(info.durationMs) +
                    "  •  Битрейт: " + brText +
                    "  •  Размер: " + AudioUtils.formatSize(info.sizeBytes)
            );
            btnStartConvert.setText("Конвертировать в MP3");
            tilOutputName.setVisibility(View.VISIBLE);
            tvBatchNameHint.setVisibility(View.GONE);
        } else {
            long totalSize = 0;
            StringBuilder sb = new StringBuilder();
            for (AudioUtils.AudioInfo info : selectedAudios) {
                totalSize += info.sizeBytes;
            }
            sb.append("Выбрано файлов: ").append(count)
              .append("  •  Общий размер: ").append(AudioUtils.formatSize(totalSize));

            int showLimit = Math.min(count, 6);
            for (int i = 0; i < showLimit; i++) {
                sb.append("\n").append(i + 1).append(". ").append(selectedAudios.get(i).displayName);
            }
            if (count > showLimit) {
                sb.append("\n… и ещё ").append(count - showLimit);
            }
            tvFileInfo.setText(sb.toString());
            btnStartConvert.setText("Конвертировать (" + count + " файлов)");
            tilOutputName.setVisibility(View.GONE);
            tvBatchNameHint.setVisibility(View.VISIBLE);
        }

        updateOutputNameHints();
    }

    private void updateOutputNameHints() {
        if (selectedAudios.size() == 1) {
            etOutputName.setText(AudioUtils.stripExtension(selectedAudios.get(0).displayName) + "_" + selectedBitrate + "k");
        } else if (selectedAudios.size() > 1) {
            tvBatchNameHint.setText("Имена файлов будут: исходное_имя_" + selectedBitrate + "k.mp3");
        }
    }

    private void startConversion() {
        if (selectedAudios.isEmpty() || getContext() == null) return;

        final Context appCtx = requireContext().getApplicationContext();
        final List<AudioUtils.AudioInfo> snapshot = new ArrayList<>(selectedAudios);
        final int totalCount = snapshot.size();
        final int targetBitrate = selectedBitrate;
        final String singleCustomName = (totalCount == 1 && etOutputName.getText() != null)
                ? etOutputName.getText().toString().trim()
                : "";

        isCancelled.set(false);
        activeSessionId = -1;
        btnStartConvert.setEnabled(false);
        btnClear.setEnabled(false);
        btnCancelConvert.setEnabled(true);
        cardResult.setVisibility(View.GONE);
        layoutProgress.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        tvStatus.setText("Подготовка...");

        final String notifTitle = totalCount == 1 ? "Конвертация MP3" : ("Конвертация (" + totalCount + " файлов)");
        AudioProcessingService.startTask(appCtx, notifTitle, "Подготовка...");

        executor.execute(() -> {
            int successCount = 0;
            int failCount = 0;
            long totalOrigSize = 0;
            long totalNewSize = 0;
            AudioUtils.SaveResult lastResult = null;

            try {
                for (int i = 0; i < totalCount; i++) {
                    if (isCancelled.get()) break;

                    AudioUtils.AudioInfo audio = snapshot.get(i);
                    totalOrigSize += audio.sizeBytes;
                    final int fileIndex = i;

                    String outName;
                    if (totalCount == 1 && !singleCustomName.isEmpty()) {
                        outName = singleCustomName;
                    } else {
                        outName = AudioUtils.stripExtension(audio.displayName) + "_" + targetBitrate + "k";
                    }

                    String prepMsg = totalCount == 1
                            ? "Подготовка файла..."
                            : "Файл " + (fileIndex + 1) + " из " + totalCount + ": " + audio.displayName;
                    runUi(() -> tvStatus.setText(prepMsg));
                    AudioProcessingService.updateProgress(appCtx, notifTitle, prepMsg, (int) Math.round((fileIndex * 100.0) / totalCount), false);

                    File inputTemp = null;
                    File outputTemp = null;
                    try {
                        inputTemp = AudioUtils.copyUriToCache(appCtx, audio.uri, "conv_in_" + i);
                        if (isCancelled.get()) break;
                        outputTemp = new File(appCtx.getCacheDir(), "conv_out_" + i + "_" + System.currentTimeMillis() + ".mp3");

                        String[] args = new String[]{
                                "-y",
                                "-i", inputTemp.getAbsolutePath(),
                                "-vn",
                                "-c:a", "libmp3lame",
                                "-b:a", targetBitrate + "k",
                                "-map_metadata", "0",
                                outputTemp.getAbsolutePath()
                        };

                        CountDownLatch latch = new CountDownLatch(1);
                        AtomicBoolean ok = new AtomicBoolean(false);
                        final long[] lastNotifMs = {0L};

                        var session = FFmpegKit.executeWithArgumentsAsync(args,
                                s -> {
                                    ok.set(!isCancelled.get() && ReturnCode.isSuccess(s.getReturnCode()));
                                    latch.countDown();
                                },
                                log -> {},
                                statistics -> {
                                    if (audio.durationMs > 0 && !isCancelled.get()) {
                                        double fileFrac = Math.min(0.99, statistics.getTime() / audio.durationMs);
                                        int filePct = (int) Math.round(fileFrac * 100.0);
                                        int overallPct = (int) Math.min(99, Math.round(((fileIndex + fileFrac) / totalCount) * 100.0));
                                        String stText = totalCount == 1
                                                ? ("Конвертация: " + filePct + "%")
                                                : ("Файл " + (fileIndex + 1) + " из " + totalCount + ": " + filePct + "%");
                                        runUi(() -> {
                                            progressBar.setProgress(overallPct);
                                            tvStatus.setText(stText);
                                        });
                                        long now = System.currentTimeMillis();
                                        if (now - lastNotifMs[0] > 500) {
                                            lastNotifMs[0] = now;
                                            AudioProcessingService.updateProgress(appCtx, notifTitle, stText, overallPct, false);
                                        }
                                    }
                                }
                        );
                        activeSessionId = session.getSessionId();
                        latch.await();
                        activeSessionId = -1;

                        if (isCancelled.get()) break;

                        if (ok.get() && outputTemp.exists() && outputTemp.length() > 0) {
                            totalNewSize += outputTemp.length();
                            lastResult = AudioUtils.saveMp3Output(appCtx, outputTemp, outName);
                            successCount++;
                        } else {
                            failCount++;
                        }
                    } catch (Exception e) {
                        if (!isCancelled.get()) failCount++;
                    } finally {
                        if (inputTemp != null) //noinspection ResultOfMethodCallIgnored
                            inputTemp.delete();
                        if (outputTemp != null) //noinspection ResultOfMethodCallIgnored
                            outputTemp.delete();
                    }

                    final int donePct = (int) Math.round(((i + 1.0) / totalCount) * 100.0);
                    runUi(() -> progressBar.setProgress(donePct));
                }
            } finally {
                activeSessionId = -1;
                AudioProcessingService.stopTask(appCtx);
            }

            final boolean wasCancelled = isCancelled.get();
            final int finalSuccess = successCount;
            final int finalFail = failCount;
            final long finalOrigSize = totalOrigSize;
            final long finalNewSize = totalNewSize;
            final AudioUtils.SaveResult finalLastRes = lastResult;

            runUi(() -> {
                layoutProgress.setVisibility(View.GONE);
                btnStartConvert.setEnabled(!selectedAudios.isEmpty());
                btnClear.setEnabled(true);
                btnCancelConvert.setEnabled(true);

                if (wasCancelled) {
                    String msg = finalSuccess > 0
                            ? ("Отменено (успело сохраниться: " + finalSuccess + " из " + totalCount + ")")
                            : "Конвертация отменена";
                    Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
                    return;
                }

                if (finalSuccess == 0) {
                    Toast.makeText(getContext(), "Не удалось конвертировать файлы", Toast.LENGTH_LONG).show();
                    return;
                }

                cardResult.setVisibility(View.VISIBLE);
                if (totalCount == 1 && finalLastRes != null) {
                    lastSavedUri = finalLastRes.uri;
                    layoutResultActions.setVisibility(View.VISIBLE);
                    tvResultInfo.setText(
                            "Готово! Сохранено в " + finalLastRes.displayPath + "\n" +
                            "Размер: " + AudioUtils.formatSize(finalOrigSize) +
                            " → " + AudioUtils.formatSize(finalNewSize) +
                            " (" + targetBitrate + " кбит/с)"
                    );
                } else {
                    lastSavedUri = finalLastRes != null ? finalLastRes.uri : null;
                    layoutResultActions.setVisibility(View.GONE);
                    String folderName = AudioUtils.getSaveFolderDisplayName(appCtx);
                    String failNote = finalFail > 0 ? (" (ошибок: " + finalFail + ")") : "";
                    tvResultInfo.setText(
                            "Готово! Конвертировано: " + finalSuccess + " из " + totalCount + failNote + "\n" +
                            "Папка: " + folderName + "\n" +
                            "Общий размер: " + AudioUtils.formatSize(finalOrigSize) +
                            " → " + AudioUtils.formatSize(finalNewSize) +
                            " (" + targetBitrate + " кбит/с)"
                    );
                }
            });
        });
    }

    private void runUi(Runnable r) {
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(r);
        }
    }
}
