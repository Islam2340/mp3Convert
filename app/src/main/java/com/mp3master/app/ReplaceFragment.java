package com.mp3master.app;

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

public class ReplaceFragment extends Fragment {

    private static final int SR = AudioReplaceEngine.SAMPLE_RATE;

    private short[] surahPcm = null;
    private short[] undoPcm = null;
    private short[] fragPcm = null;
    private AudioReplaceEngine.FeatureSet surahFeat = null;
    private String surahBaseName = "ispravleno";
    private boolean wordMode = false;
    private int selectedBitrate = 128;
    private Uri lastSavedUri = null;

    private AudioTrack audioTrack = null;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView tvSurahInfo, tvFragmentInfo, tvStatus, tvSizeHint, tvResultInfo;
    private MaterialButton btnWordMode, btnFindPlace, btnUndo, btnSave;
    private View layoutWordModeBox;
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
        layoutCandidates = view.findViewById(R.id.layoutCandidates);
        etStart = view.findViewById(R.id.etReplaceStart);
        etEnd = view.findViewById(R.id.etReplaceEnd);
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

    private void loadSurah(Uri uri) {
        stopAudio();
        String name = AudioUtils.getFileName(requireContext(), uri);
        surahBaseName = AudioUtils.stripExtension(name);
        tvSurahInfo.setText("Загрузка: " + name + "…");
        setStatus("Декодирую суру…", 10);
        btnFindPlace.setEnabled(false);
        btnSave.setEnabled(false);
        cardResult.setVisibility(View.GONE);

        executor.execute(() -> {
            try {
                short[] pcm = decodeToMonoPcm(uri, "rep_surah");
                surahPcm = pcm;
                undoPcm = null;
                surahFeat = null;
                double sec = (double) pcm.length / SR;
                runUi(() -> {
                    tvSurahInfo.setText(name + "  •  " + AudioReplaceEngine.fmtSec(sec));
                    setStatus("Сура загружена.", 0);
                    refreshButtons();
                });
            } catch (Exception e) {
                surahPcm = null;
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

        executor.execute(() -> {
            try {
                short[] raw = decodeToMonoPcm(uri, "rep_frag");
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

    private short[] decodeToMonoPcm(Uri uri, String prefix) throws IOException {
        File inFile = AudioUtils.copyUriToCache(requireContext(), uri, prefix);
        File rawFile = new File(requireContext().getCacheDir(), prefix + "_" + System.currentTimeMillis() + ".pcm");
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
        if (surahPcm == null || fragPcm == null) return;
        stopAudio();
        btnFindPlace.setEnabled(false);
        layoutCandidates.removeAllViews();

        final boolean isWord = wordMode;
        final String wordTimeStr = etWordTime.getText() != null ? etWordTime.getText().toString() : "";
        final String wordWinStr = etWordWindow.getText() != null ? etWordWindow.getText().toString() : "15";

        executor.execute(() -> {
            try {
                if (surahFeat == null) {
                    runUi(() -> setStatus("Анализирую суру…", 5));
                    surahFeat = AudioReplaceEngine.extractFeatures(surahPcm, SR);
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

                AudioReplaceEngine.DtwResult dtwRes = AudioReplaceEngine.subsequenceDtw(
                        featA.F, featA.n, surahFeat.F, j0, nB,
                        frac -> runUi(() -> setStatus(
                                "Ищу место: " + Math.round(frac * 100) + "%",
                                (int) (10 + frac * 88)
                        ))
                );

                List<AudioReplaceEngine.Candidate> cands = AudioReplaceEngine.pickTopCandidates(
                        dtwRes, j0, surahFeat, featA.n, surahPcm, SR, isWord
                );

                boolean sure = cands.size() < 2 || (cands.get(1).score / Math.max(0.0001f, cands.get(0).score) > 1.1f);
                String msg = sure
                        ? "Нашёл. Проверьте на слух."
                        : "Нашёл, но фраза похожа на другие места. Проверьте варианты.";

                runUi(() -> {
                    setStatus(msg, 100);
                    renderCandidates(cands);
                    if (!cands.isEmpty()) {
                        etStart.setText(String.format(Locale.US, "%.2f", cands.get(0).startSec));
                        etEnd.setText(String.format(Locale.US, "%.2f", cands.get(0).endSec));
                    }
                    refreshButtons();
                });
            } catch (Exception e) {
                runUi(() -> {
                    setStatus("Ошибка: " + e.getMessage(), 0);
                    refreshButtons();
                });
            }
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
        surahFeat = null;
        cardResult.setVisibility(View.GONE);
        layoutCandidates.removeAllViews();
        setStatus("Заменено! Можно искать следующий отрывок или сохранить результат.", 0);
        refreshButtons();
    }

    private void undoReplacement() {
        if (undoPcm == null) return;
        stopAudio();
        surahPcm = undoPcm;
        undoPcm = null;
        surahFeat = null;
        cardResult.setVisibility(View.GONE);
        setStatus("Последняя замена отменена.", 0);
        refreshButtons();
    }

    private void saveResultMp3() {
        if (surahPcm == null || getContext() == null) return;
        stopAudio();
        btnSave.setEnabled(false);
        cardResult.setVisibility(View.GONE);
        setStatus("Кодирую MP3…", 10);

        final short[] snapshot = surahPcm;
        final int br = selectedBitrate;
        final String outName = surahBaseName;
        final long totalDurMs = (long) ((snapshot.length * 1000.0) / SR);

        executor.execute(() -> {
            File rawTemp = null;
            File mp3Temp = null;
            try {
                rawTemp = new File(requireContext().getCacheDir(), "rep_save_" + System.currentTimeMillis() + ".pcm");
                mp3Temp = new File(requireContext().getCacheDir(), "rep_out_" + System.currentTimeMillis() + ".mp3");

                try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(rawTemp))) {
                    byte[] buf = new byte[16384];
                    int bi = 0;
                    for (short s : snapshot) {
                        buf[bi++] = (byte) (s & 0xFF);
                        buf[bi++] = (byte) ((s >> 8) & 0xFF);
                        if (bi >= buf.length) {
                            bos.write(buf, 0, bi);
                            bi = 0;
                        }
                    }
                    if (bi > 0) bos.write(buf, 0, bi);
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

                FFmpegKit.executeWithArgumentsAsync(args,
                        session -> {
                            if (ReturnCode.isSuccess(session.getReturnCode())) {
                                try {
                                    long sz = finalMp3.length();
                                    AudioUtils.SaveResult res = AudioUtils.saveMp3Output(requireContext(), finalMp3, outName);
                                    lastSavedUri = res.uri;
                                    runUi(() -> {
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
                                        setStatus("Ошибка сохранения: " + e.getMessage(), 0);
                                        refreshButtons();
                                    });
                                }
                            } else {
                                runUi(() -> {
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
                            if (totalDurMs > 0) {
                                int pct = (int) Math.min(99, Math.round((statistics.getTime() / totalDurMs) * 100.0));
                                runUi(() -> setStatus("Кодирую MP3: " + pct + "%", pct));
                            }
                        }
                );
            } catch (Exception e) {
                if (rawTemp != null) //noinspection ResultOfMethodCallIgnored
                    rawTemp.delete();
                if (mp3Temp != null) //noinspection ResultOfMethodCallIgnored
                    mp3Temp.delete();
                runUi(() -> {
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
