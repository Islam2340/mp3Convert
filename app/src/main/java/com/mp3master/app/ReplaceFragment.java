package com.mp3master.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReplaceFragment extends Fragment {

    private static final int SR = AudioReplaceEngine.SAMPLE_RATE;

    private short[] surahPcm = null;
    private short[] undoPcm = null;
    private short[] fragPcm = null;
    private float[] surahWavePeaks = null;
    private AudioReplaceEngine.FeatureSet surahFeat = null;
    private String surahBaseName = "ispravleno";
    private boolean wordMode = false;
    private int selectedBitrate = 128;
    private Uri lastSavedUri = null;

    private AudioTrack audioTrack = null;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private volatile long activeSessionId = -1;

    private TextView tvSurahInfo, tvFragmentInfo, tvStatus, tvSizeHint, tvResultInfo;
    private TextView tvRepZoomLabel, tvRepWaveCursor;
    private MaterialButton btnWordMode, btnFindPlace, btnUndo, btnSave, btnCancelReplace;
    private View layoutWordModeBox, layoutReplaceWave;
    private MaterialSwitch switchShowWave;
    private AudioTimelineView replaceTimeline;
    private ChipGroup chipGroupRepZoom;
    private LinearLayout layoutCandidates;
    private LinearProgressIndicator progressBar;
    private TextInputEditText etWordTime, etWordWindow, etStart, etEnd;
    private MaterialCardView cardResult;

    private final ActivityResultLauncher<String[]> pickSurahLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null && getContext() != null) {
                    loadSurah(uri);
                }
            });

    private final ActivityResultLauncher<String[]> pickFragmentLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null && getContext() != null) {
                    loadFragment(uri);
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_replace, container, false);

        tvSurahInfo = view.findViewById(R.id.tvSurahInfo);
        tvFragmentInfo = view.findViewById(R.id.tvFragmentInfo);
        btnWordMode = view.findViewById(R.id.btnWordMode);
        layoutWordModeBox = view.findViewById(R.id.layoutWordModeBox);
        etWordTime = view.findViewById(R.id.etWordTime);
        etWordWindow = view.findViewById(R.id.etWordWindow);
        btnFindPlace = view.findViewById(R.id.btnFindPlace);
        progressBar = view.findViewById(R.id.progressReplace);
        tvStatus = view.findViewById(R.id.tvReplaceStatus);
        btnCancelReplace = view.findViewById(R.id.btnCancelReplace);
        layoutCandidates = view.findViewById(R.id.layoutCandidates);
        etStart = view.findViewById(R.id.etReplaceStart);
        etEnd = view.findViewById(R.id.etReplaceEnd);
        switchShowWave = view.findViewById(R.id.switchShowReplaceWave);
        layoutReplaceWave = view.findViewById(R.id.layoutReplaceWave);
        tvRepZoomLabel = view.findViewById(R.id.tvRepZoomLabel);
        tvRepWaveCursor = view.findViewById(R.id.tvRepWaveCursor);
        chipGroupRepZoom = view.findViewById(R.id.chipGroupRepZoom);
        replaceTimeline = view.findViewById(R.id.replaceTimeline);
        btnUndo = view.findViewById(R.id.btnUndoReplace);
        tvSizeHint = view.findViewById(R.id.tvReplaceSizeHint);
        btnSave = view.findViewById(R.id.btnSaveReplace);
        cardResult = view.findViewById(R.id.cardReplaceResult);
        tvResultInfo = view.findViewById(R.id.tvReplaceResultInfo);

        view.findViewById(R.id.btnPickSurah).setOnClickListener(v ->
                pickSurahLauncher.launch(new String[]{"audio/mpeg", "audio/*"}));
        view.findViewById(R.id.btnPickFragment).setOnClickListener(v ->
                pickFragmentLauncher.launch(new String[]{"audio/mpeg", "audio/*"}));

        btnWordMode.setOnClickListener(v -> {
            wordMode = !wordMode;
            btnWordMode.setText(wordMode ? "Режим: одно слово" : "Режим: отрывок");
            layoutWordModeBox.setVisibility(wordMode ? View.VISIBLE : View.GONE);
        });

        btnFindPlace.setOnClickListener(v -> startSearch());

        btnCancelReplace.setOnClickListener(v -> {
            isCancelled.set(true);
            btnCancelReplace.setEnabled(false);
            setStatus("Отмена...", null);
            if (activeSessionId > 0) {
                FFmpegKit.cancel(activeSessionId);
            } else {
                FFmpegKit.cancel();
            }
        });

        setupWaveformControls(view);

        view.findViewById(R.id.btnPlayOriginal).setOnClickListener(v -> playOriginal());
        view.findViewById(R.id.btnPlayReplaced).setOnClickListener(v -> playWithReplacement());
        view.findViewById(R.id.btnStopReplaceAudio).setOnClickListener(v -> stopAudio());
        view.findViewById(R.id.btnApplyReplace).setOnClickListener(v -> applyReplacement());
        btnUndo.setOnClickListener(v -> undoReplacement());

        ChipGroup chipGroup = view.findViewById(R.id.chipGroupReplaceBitrate);
        chipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chipRep48) selectedBitrate = 48;
            else if (id == R.id.chipRep64) selectedBitrate = 64;
            else if (id == R.id.chipRep96) selectedBitrate = 96;
            else if (id == R.id.chipRep128) selectedBitrate = 128;
            else if (id == R.id.chipRep192) selectedBitrate = 192;
            updateSizeHint();
        });

        btnSave.setOnClickListener(v -> saveResultMp3());

        view.findViewById(R.id.btnReplaceOpen).setOnClickListener(v -> {
            if (lastSavedUri != null && getContext() != null) {
                AudioUtils.openAudio(getContext(), lastSavedUri);
            }
        });
        view.findViewById(R.id.btnReplaceShare).setOnClickListener(v -> {
            if (lastSavedUri != null && getContext() != null) {
                AudioUtils.shareAudio(getContext(), lastSavedUri);
            }
        });

        return view;
    }

    private void setupWaveformControls(View view) {
        switchShowWave.setOnCheckedChangeListener((btn, checked) -> {
            if (checked && surahPcm == null) {
                Toast.makeText(getContext(), "Сначала загрузите файл суры", Toast.LENGTH_SHORT).show();
                switchShowWave.setChecked(false);
                return;
            }
            layoutReplaceWave.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (checked) {
                syncTimelineFromInputs(true);
            }
        });

        replaceTimeline.setListener(new AudioTimelineView.OnTimelineChangeListener() {
            @Override
            public void onTrimRangeChanged(long startMs, long endMs, boolean fromUser) {
                if (fromUser) {
                    etStart.setText(String.format(Locale.US, "%.2f", startMs / 1000.0));
                    etEnd.setText(String.format(Locale.US, "%.2f", endMs / 1000.0));
                }
            }

            @Override
            public void onPlayheadSeek(long positionMs, boolean fromUser) {
                tvRepWaveCursor.setText(AudioUtils.formatTimePreciseMs(positionMs));
                if (fromUser && surahPcm != null) {
                    int from = Math.max(0, Math.min(surahPcm.length - 1, (int) ((positionMs / 1000.0) * SR)));
                    int to = Math.min(surahPcm.length, from + SR * 4);
                    if (to > from) {
                        playPcm(Arrays.copyOfRange(surahPcm, from, to));
                    }
                }
            }

            @Override
            public void onScaleChanged(float secondsPerCm, boolean isFitAll) {
                if (isFitAll) {
                    tvRepZoomLabel.setText("Масштаб: весь трек");
                } else {
                    tvRepZoomLabel.setText(String.format(Locale.US, "Масштаб: %.1f с/см", secondsPerCm));
                }
            }
        });

        chipGroupRepZoom.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chipRepZoomFit) replaceTimeline.setFitToWidth();
            else if (id == R.id.chipRepZoom10s) replaceTimeline.setSecondsPerCm(10f);
            else if (id == R.id.chipRepZoom5s) replaceTimeline.setSecondsPerCm(5f);
            else if (id == R.id.chipRepZoom2s) replaceTimeline.setSecondsPerCm(2f);
            else if (id == R.id.chipRepZoom1s) replaceTimeline.setSecondsPerCm(1f);
        });

        view.findViewById(R.id.btnRepSetStartHere).setOnClickListener(v -> {
            if (surahPcm == null) return;
            long pos = replaceTimeline.getPlayheadMs();
            long end = replaceTimeline.getTrimEndMs();
            if (pos < end - 200L) {
                replaceTimeline.setTrimRange(pos, end);
                etStart.setText(String.format(Locale.US, "%.2f", pos / 1000.0));
            }
        });

        view.findViewById(R.id.btnRepSetEndHere).setOnClickListener(v -> {
            if (surahPcm == null) return;
            long pos = replaceTimeline.getPlayheadMs();
            long start = replaceTimeline.getTrimStartMs();
            if (pos > start + 200L) {
                replaceTimeline.setTrimRange(start, pos);
                etEnd.setText(String.format(Locale.US, "%.2f", pos / 1000.0));
            }
        });
    }

    private void syncTimelineFromInputs(boolean scrollToRange) {
        if (surahPcm == null) return;
        long totalMs = (long) ((surahPcm.length * 1000.0) / SR);
        long startMs = 0L;
        long endMs = totalMs;
        try {
            String sStr = etStart.getText() != null ? etStart.getText().toString().trim().replace(',', '.') : "";
            String eStr = etEnd.getText() != null ? etEnd.getText().toString().trim().replace(',', '.') : "";
            if (!sStr.isEmpty() && !eStr.isEmpty()) {
                long s = Math.round(Double.parseDouble(sStr) * 1000.0);
                long e = Math.round(Double.parseDouble(eStr) * 1000.0);
                if (s >= 0 && e > s && e <= totalMs) {
                    startMs = s;
                    endMs = e;
                }
            }
        } catch (Exception ignored) {
        }

        replaceTimeline.setAudioData(totalMs, startMs, endMs);
        if (surahWavePeaks != null) {
            replaceTimeline.setWaveformPeaks(surahWavePeaks);
        }
        tvRepWaveCursor.setText(AudioUtils.formatTimePreciseMs(startMs));
        if (scrollToRange && endMs < totalMs) {
            long finalStart = startMs;
            replaceTimeline.post(() -> {
                chipGroupRepZoom.check(R.id.chipRepZoom5s);
                replaceTimeline.setSecondsPerCm(5f);
                replaceTimeline.scrollToMs(finalStart);
            });
        } else {
            chipGroupRepZoom.check(R.id.chipRepZoomFit);
        }
    }

    private void loadSurah(Uri uri) {
        stopAudio();
        String name = AudioUtils.getFileName(requireContext(), uri);
        surahBaseName = AudioUtils.stripExtension(name);
        tvSurahInfo.setText("Загрузка: " + name + "…");
        setStatus("Декодирую суру…", 10);
        btnFindPlace.setEnabled(false);
        btnSave.setEnabled(false);
        cardResult.setVisibility(View.GONE);
        final Context appCtx = requireContext().getApplicationContext();

        executor.execute(() -> {
            try {
                short[] pcm = decodeToMonoPcm(appCtx, uri, "rep_surah");
                float[] peaks = AudioReplaceEngine.computeWaveformPeaks(pcm, 2400);
                surahPcm = pcm;
                surahWavePeaks = peaks;
                undoPcm = null;
                surahFeat = null;
                double sec = (double) pcm.length / SR;
                runUi(() -> {
                    tvSurahInfo.setText(name + "  •  " + AudioReplaceEngine.fmtSec(sec));
                    setStatus("Сура загружена.", 0);
                    if (switchShowWave.isChecked()) {
                        syncTimelineFromInputs(false);
                    }
                    refreshButtons();
                });
            } catch (Exception e) {
                surahPcm = null;
                surahWavePeaks = null;
                runUi(() -> {
                    tvSurahInfo.setText("Не удалось прочитать файл");
                    setStatus("Ошибка: " + e.getMessage(), 0);
                    refreshButtons();
                });
            }
        });
    }

    private void loadFragment(Uri uri) {
        stopAudio();
        String name = AudioUtils.getFileName(requireContext(), uri);
        tvFragmentInfo.setText("Загрузка: " + name + "…");
        btnFindPlace.setEnabled(false);
        final Context appCtx = requireContext().getApplicationContext();

        executor.execute(() -> {
            try {
                short[] raw = decodeToMonoPcm(appCtx, uri, "rep_frag");
                short[] trimmed = AudioReplaceEngine.trimSilence(raw, SR);
                fragPcm = trimmed;
                double sec = (double) trimmed.length / SR;
                runUi(() -> {
                    tvFragmentInfo.setText(name + "  •  " + AudioReplaceEngine.fmtSec(sec));
                    refreshButtons();
                });
            } catch (Exception e) {
                fragPcm = null;
                runUi(() -> {
                    tvFragmentInfo.setText("Не удалось прочитать файл");
                    refreshButtons();
                });
            }
        });
    }

    private short[] decodeToMonoPcm(Context appCtx, Uri uri, String prefix) throws IOException {
        File inFile = AudioUtils.copyUriToCache(appCtx, uri, prefix);
        File rawFile = new File(appCtx.getCacheDir(), prefix + "_" + System.currentTimeMillis() + ".pcm");
        try {
            String[] args = new String[]{
                    "-y",
                    "-i", inFile.getAbsolutePath(),
                    "-vn",
                    "-ac", "1",
                    "-ar", String.valueOf(SR),
                    "-f", "s16le",
                    rawFile.getAbsolutePath()
            };
            var session = FFmpegKit.executeWithArguments(args);
            if (!ReturnCode.isSuccess(session.getReturnCode()) || !rawFile.exists()) {
                throw new IOException("Ошибка декодирования FFmpeg");
            }
            int sampleCount = (int) (rawFile.length() / 2L);
            short[] pcm = new short[sampleCount];
            byte[] buf = new byte[16384];
            int sampleIdx = 0;
            try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(rawFile))) {
                int read;
                while ((read = bis.read(buf)) > 0) {
                    int pairs = read / 2;
                    for (int i = 0; i < pairs && sampleIdx < sampleCount; i++) {
                        int lo = buf[i * 2] & 0xFF;
                        int hi = buf[i * 2 + 1];
                        pcm[sampleIdx++] = (short) ((hi << 8) | lo);
                    }
                }
            }
            return pcm;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            inFile.delete();
            //noinspection ResultOfMethodCallIgnored
            rawFile.delete();
        }
    }

    private void startSearch() {
        if (surahPcm == null || fragPcm == null || getContext() == null) return;
        stopAudio();
        isCancelled.set(false);
        activeSessionId = -1;
        btnFindPlace.setEnabled(false);
        btnSave.setEnabled(false);
        btnCancelReplace.setVisibility(View.VISIBLE);
        btnCancelReplace.setEnabled(true);
        layoutCandidates.removeAllViews();

        final Context appCtx = requireContext().getApplicationContext();
        final boolean isWord = wordMode;
        final String wordTimeStr = etWordTime.getText() != null ? etWordTime.getText().toString() : "";
        final String wordWinStr = etWordWindow.getText() != null ? etWordWindow.getText().toString() : "15";

        AudioProcessingService.startTask(appCtx, "Поиск фрагмента", "Анализирую аудио...");

        executor.execute(() -> {
            try {
                if (surahFeat == null) {
                    runUi(() -> setStatus("Анализирую суру…", 5));
                    AudioProcessingService.updateProgress(appCtx, "Поиск фрагмента", "Анализирую суру...", 5, false);
                    surahFeat = AudioReplaceEngine.extractFeatures(surahPcm, SR);
                }
                if (isCancelled.get()) {
                    handleCancelledSearch(appCtx);
                    return;
                }
                runUi(() -> setStatus("Анализирую отрывок…", 10));
                AudioReplaceEngine.FeatureSet featA = AudioReplaceEngine.extractFeatures(fragPcm, SR);
                if (featA.n < 2) {
                    throw new IllegalStateException("Отрывок слишком короткий.");
                }

                int j0 = 0;
                int nB = surahFeat.n;
                if (isWord) {
                    Double ht = AudioReplaceEngine.parseTimeSec(wordTimeStr);
                    if (ht != null) {
                        double w = 15.0;
                        try {
                            w = Math.max(3.0, Double.parseDouble(wordWinStr.trim()));
                        } catch (Exception ignored) {
                        }
                        j0 = Math.max(0, (int) Math.floor((ht - w) / surahFeat.hopT));
                        int j1 = Math.min(surahFeat.n, (int) Math.ceil((ht + w) / surahFeat.hopT) + featA.n);
                        if (j1 - j0 <= featA.n + 2) {
                            throw new IllegalStateException("Указанное время вне суры.");
                        }
                        nB = j1 - j0;
                    }
                }

                final long[] lastNotifMs = {0L};
                AudioReplaceEngine.DtwResult dtwRes = AudioReplaceEngine.subsequenceDtw(
                        featA.F, featA.n, surahFeat.F, j0, nB,
                        frac -> {
                            int pct = (int) (10 + frac * 88);
                            String st = "Ищу место: " + Math.round(frac * 100) + "%";
                            runUi(() -> setStatus(st, pct));
                            long now = System.currentTimeMillis();
                            if (now - lastNotifMs[0] > 500) {
                                lastNotifMs[0] = now;
                                AudioProcessingService.updateProgress(appCtx, "Поиск фрагмента", st, pct, false);
                            }
                        },
                        isCancelled
                );

                if (dtwRes == null || isCancelled.get()) {
                    handleCancelledSearch(appCtx);
                    return;
                }

                List<AudioReplaceEngine.Candidate> cands = AudioReplaceEngine.pickTopCandidates(
                        dtwRes, j0, surahFeat, featA.n, surahPcm, SR, isWord
                );

                boolean sure = cands.size() < 2 || (cands.get(1).score / Math.max(0.0001f, cands.get(0).score) > 1.1f);
                String msg = sure
                        ? "Нашёл. Проверьте на слух (можно подправить вручную или по волне)."
                        : "Нашёл, но фраза похожа на другие места. Проверьте варианты.";

                AudioProcessingService.stopTask(appCtx);
                runUi(() -> {
                    btnCancelReplace.setVisibility(View.GONE);
                    setStatus(msg, 100);
                    renderCandidates(cands);
                    if (!cands.isEmpty()) {
                        etStart.setText(String.format(Locale.US, "%.2f", cands.get(0).startSec));
                        etEnd.setText(String.format(Locale.US, "%.2f", cands.get(0).endSec));
                        if (switchShowWave.isChecked()) {
                            syncTimelineFromInputs(true);
                        }
                    }
                    refreshButtons();
                });
            } catch (Exception e) {
                AudioProcessingService.stopTask(appCtx);
                runUi(() -> {
                    btnCancelReplace.setVisibility(View.GONE);
                    setStatus("Ошибка: " + e.getMessage(), 0);
                    refreshButtons();
                });
            }
        });
    }

    private void handleCancelledSearch(Context appCtx) {
        AudioProcessingService.stopTask(appCtx);
        runUi(() -> {
            btnCancelReplace.setVisibility(View.GONE);
            btnCancelReplace.setEnabled(true);
            setStatus("Поиск отменён.", 0);
            refreshButtons();
        });
    }

    private void renderCandidates(List<AudioReplaceEngine.Candidate> cands) {
        layoutCandidates.removeAllViews();
        if (getContext() == null) return;
        float density = getResources().getDisplayMetrics().density;

        for (int i = 0; i < cands.size(); i++) {
            AudioReplaceEngine.Candidate c = cands.get(i);
            LinearLayout box = new LinearLayout(requireContext());
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface_bg));
            int pad = (int) (10 * density);
            box.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lp.topMargin = (int) (6 * density);
            box.setLayoutParams(lp);

            TextView tv = new TextView(requireContext());
            tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary));
            tv.setTextSize(13f);
            tv.setText(String.format(
                    Locale.US,
                    "Вариант %d%s: %s – %s (точность: %.2f)",
                    i + 1,
                    (i == 0 ? " (лучший)" : ""),
                    AudioReplaceEngine.fmtSec(c.startSec),
                    AudioReplaceEngine.fmtSec(c.endSec),
                    c.score
            ));
            box.addView(tv);

            MaterialButton btnPick = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btnPick.setText("Выбрать и послушать");
            btnPick.setTextSize(12f);
            btnPick.setOnClickListener(v -> {
                etStart.setText(String.format(Locale.US, "%.2f", c.startSec));
                etEnd.setText(String.format(Locale.US, "%.2f", c.endSec));
                if (switchShowWave.isChecked()) {
                    syncTimelineFromInputs(true);
                }
                if (surahPcm != null) {
                    int a = Math.max(0, (int) ((c.startSec - 1.0) * SR));
                    int b = Math.min(surahPcm.length, (int) ((c.endSec + 1.0) * SR));
                    if (b > a) playPcm(Arrays.copyOfRange(surahPcm, a, b));
                }
            });
            box.addView(btnPick);
            layoutCandidates.addView(box);
        }
    }

    private int[] parseRange() {
        if (surahPcm == null) {
            setStatus("Сначала выберите файл суры.", null);
            return null;
        }
        try {
            String sStr = etStart.getText() != null ? etStart.getText().toString().trim().replace(',', '.') : "";
            String eStr = etEnd.getText() != null ? etEnd.getText().toString().trim().replace(',', '.') : "";
            int a = (int) Math.floor(Double.parseDouble(sStr) * SR);
            int b = (int) Math.floor(Double.parseDouble(eStr) * SR);
            if (a < 0 || b <= a || b > surahPcm.length) {
                setStatus("Укажите правильные «С» и «По».", null);
                return null;
            }
            return new int[]{a, b};
        } catch (Exception e) {
            setStatus("Укажите правильные «С» и «По».", null);
            return null;
        }
    }

    private void playOriginal() {
        int[] r = parseRange();
        if (r == null) return;
        int from = Math.max(0, r[0] - SR * 2);
        int to = Math.min(surahPcm.length, r[1] + SR * 2);
        playPcm(Arrays.copyOfRange(surahPcm, from, to));
    }

    private void playWithReplacement() {
        int[] r = parseRange();
        if (r == null) return;
        if (fragPcm == null) {
            setStatus("Выберите исправленный отрывок.", null);
            return;
        }
        int a = r[0], b = r[1];
        short[] before = Arrays.copyOfRange(surahPcm, Math.max(0, a - SR * 2), a);
        short[] matched = AudioReplaceEngine.matchVolume(surahPcm, a, b, fragPcm);
        short[] after = Arrays.copyOfRange(surahPcm, b, Math.min(surahPcm.length, b + SR * 2));
        short[] preview = AudioReplaceEngine.joinWithCrossfade(Arrays.asList(before, matched, after), SR);
        playPcm(preview);
    }

    private void applyReplacement() {
        int[] r = parseRange();
        if (r == null) return;
        if (fragPcm == null) {
            setStatus("Выберите исправленный отрывок.", null);
            return;
        }
        stopAudio();
        int a = r[0], b = r[1];
        undoPcm = surahPcm;
        short[] before = Arrays.copyOfRange(surahPcm, 0, a);
        short[] matched = AudioReplaceEngine.matchVolume(surahPcm, a, b, fragPcm);
        short[] after = Arrays.copyOfRange(surahPcm, b, surahPcm.length);
        surahPcm = AudioReplaceEngine.joinWithCrossfade(Arrays.asList(before, matched, after), SR);
        surahWavePeaks = AudioReplaceEngine.computeWaveformPeaks(surahPcm, 2400);
        surahFeat = null;
        cardResult.setVisibility(View.GONE);
        layoutCandidates.removeAllViews();
        if (switchShowWave.isChecked()) {
            syncTimelineFromInputs(false);
        }
        setStatus("Заменено! Можно искать следующий отрывок или сохранить результат.", 0);
        refreshButtons();
    }

    private void undoReplacement() {
        if (undoPcm == null) return;
        stopAudio();
        surahPcm = undoPcm;
        undoPcm = null;
        surahWavePeaks = AudioReplaceEngine.computeWaveformPeaks(surahPcm, 2400);
        surahFeat = null;
        cardResult.setVisibility(View.GONE);
        if (switchShowWave.isChecked()) {
            syncTimelineFromInputs(false);
        }
        setStatus("Последняя замена отменена.", 0);
        refreshButtons();
    }

    private void saveResultMp3() {
        if (surahPcm == null || getContext() == null) return;
        stopAudio();
        isCancelled.set(false);
        activeSessionId = -1;
        btnSave.setEnabled(false);
        btnFindPlace.setEnabled(false);
        btnCancelReplace.setVisibility(View.VISIBLE);
        btnCancelReplace.setEnabled(true);
        cardResult.setVisibility(View.GONE);
        setStatus("Кодирую MP3…", 10);

        final Context appCtx = requireContext().getApplicationContext();
        final short[] snapshot = surahPcm;
        final int br = selectedBitrate;
        final String outName = surahBaseName;
        final long totalDurMs = (long) ((snapshot.length * 1000.0) / SR);

        AudioProcessingService.startTask(appCtx, "Сохранение MP3", "Кодирую MP3...");

        executor.execute(() -> {
            File rawTemp = null;
            File mp3Temp = null;
            try {
                rawTemp = new File(appCtx.getCacheDir(), "rep_save_" + System.currentTimeMillis() + ".pcm");
                mp3Temp = new File(appCtx.getCacheDir(), "rep_out_" + System.currentTimeMillis() + ".mp3");

                try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(rawTemp))) {
                    byte[] buf = new byte[16384];
                    int bi = 0;
                    for (short s : snapshot) {
                        if (isCancelled.get()) break;
                        buf[bi++] = (byte) (s & 0xFF);
                        buf[bi++] = (byte) ((s >> 8) & 0xFF);
                        if (bi >= buf.length) {
                            bos.write(buf, 0, bi);
                            bi = 0;
                        }
                    }
                    if (bi > 0 && !isCancelled.get()) bos.write(buf, 0, bi);
                }

                if (isCancelled.get()) {
                    //noinspection ResultOfMethodCallIgnored
                    rawTemp.delete();
                    AudioProcessingService.stopTask(appCtx);
                    runUi(() -> {
                        btnCancelReplace.setVisibility(View.GONE);
                        setStatus("Сохранение отменено.", 0);
                        refreshButtons();
                    });
                    return;
                }

                String[] args = new String[]{
                        "-y",
                        "-f", "s16le",
                        "-ar", String.valueOf(SR),
                        "-ac", "1",
                        "-i", rawTemp.getAbsolutePath(),
                        "-c:a", "libmp3lame",
                        "-b:a", br + "k",
                        mp3Temp.getAbsolutePath()
                };

                final File finalRaw = rawTemp;
                final File finalMp3 = mp3Temp;
                final long[] lastNotifMs = {0L};

                var session = FFmpegKit.executeWithArgumentsAsync(args,
                        s -> {
                            activeSessionId = -1;
                            AudioProcessingService.stopTask(appCtx);
                            if (isCancelled.get() || ReturnCode.isCancel(s.getReturnCode())) {
                                runUi(() -> {
                                    btnCancelReplace.setVisibility(View.GONE);
                                    setStatus("Сохранение отменено.", 0);
                                    refreshButtons();
                                });
                            } else if (ReturnCode.isSuccess(s.getReturnCode())) {
                                try {
                                    long sz = finalMp3.length();
                                    AudioUtils.SaveResult res = AudioUtils.saveMp3Output(appCtx, finalMp3, outName);
                                    lastSavedUri = res.uri;
                                    runUi(() -> {
                                        btnCancelReplace.setVisibility(View.GONE);
                                        setStatus("Готово!", 100);
                                        cardResult.setVisibility(View.VISIBLE);
                                        tvResultInfo.setText(
                                                "Сохранено в " + res.displayPath + "\n" +
                                                "Размер: " + AudioUtils.formatSize(sz) + " (" + br + " кбит/с)"
                                        );
                                        refreshButtons();
                                    });
                                } catch (Exception e) {
                                    runUi(() -> {
                                        btnCancelReplace.setVisibility(View.GONE);
                                        setStatus("Ошибка сохранения: " + e.getMessage(), 0);
                                        refreshButtons();
                                    });
                                }
                            } else {
                                runUi(() -> {
                                    btnCancelReplace.setVisibility(View.GONE);
                                    setStatus("Ошибка FFmpeg при кодировании", 0);
                                    refreshButtons();
                                });
                            }
                            //noinspection ResultOfMethodCallIgnored
                            finalRaw.delete();
                            //noinspection ResultOfMethodCallIgnored
                            finalMp3.delete();
                        },
                        log -> {},
                        statistics -> {
                            if (totalDurMs > 0 && !isCancelled.get()) {
                                int pct = (int) Math.min(99, Math.round((statistics.getTime() / totalDurMs) * 100.0));
                                runUi(() -> setStatus("Кодирую MP3: " + pct + "%", pct));
                                long now = System.currentTimeMillis();
                                if (now - lastNotifMs[0] > 500) {
                                    lastNotifMs[0] = now;
                                    AudioProcessingService.updateProgress(appCtx, "Сохранение MP3", "Кодирую MP3: " + pct + "%", pct, false);
                                }
                            }
                        }
                );
                activeSessionId = session.getSessionId();
            } catch (Exception e) {
                activeSessionId = -1;
                AudioProcessingService.stopTask(appCtx);
                if (rawTemp != null) //noinspection ResultOfMethodCallIgnored
                    rawTemp.delete();
                if (mp3Temp != null) //noinspection ResultOfMethodCallIgnored
                    mp3Temp.delete();
                runUi(() -> {
                    btnCancelReplace.setVisibility(View.GONE);
                    setStatus("Ошибка: " + e.getMessage(), 0);
                    refreshButtons();
                });
            }
        });
    }

    private void playPcm(short[] samples) {
        stopAudio();
        if (samples == null || samples.length == 0) return;
        try {
            int byteLen = samples.length * 2;
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(byteLen)
                    .build();
            audioTrack.write(samples, 0, samples.length);
            audioTrack.play();
        } catch (Exception e) {
            Toast.makeText(getContext(), "Ошибка воспроизведения: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            stopAudio();
        }
    }

    private void stopAudio() {
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception ignored) {
            }
            audioTrack = null;
        }
    }

    private void refreshButtons() {
        btnFindPlace.setEnabled(surahPcm != null && fragPcm != null);
        btnSave.setEnabled(surahPcm != null);
        btnUndo.setEnabled(undoPcm != null);
        btnCancelReplace.setEnabled(true);
        updateSizeHint();
    }

    private void updateSizeHint() {
        if (surahPcm != null) {
            double sec = (double) surahPcm.length / SR;
            double mb = (sec * selectedBitrate * 1000.0 / 8.0) / 1048576.0;
            tvSizeHint.setText(String.format(Locale.US, "Примерный размер файла: %.1f МБ", mb));
        } else {
            tvSizeHint.setText("");
        }
    }

    private void setStatus(String text, Integer pct) {
        tvStatus.setText(text);
        if (pct != null) progressBar.setProgress(pct);
    }

    private void runUi(Runnable r) {
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(r);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stopAudio();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) stopAudio();
    }
}
