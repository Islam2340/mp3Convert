package com.mp3master.app;

import android.app.Dialog;
import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

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
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MergeFragment extends Fragment {

    private static final int TRANS_DIRECT = 0;
    private static final int TRANS_PAUSE_1S = 1;
    private static final int TRANS_PAUSE_2S = 2;
    private static final int TRANS_CROSSFADE = 3;

    private final List<AudioUtils.AudioInfo> mergeItems = new ArrayList<>();
    private MergeAdapter adapter;
    private int selectedBitrate = 192;
    private int selectedTransition = TRANS_DIRECT;
    private Uri lastSavedUri;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService previewExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private volatile long activeSessionId = -1;

    private MediaPlayer transitionPlayer;
    private int playingTransitionIndex = -1;
    private int loadingTransitionIndex = -1;
    private File lastTransitionFile;

    private TextView tvEmpty, tvStatus, tvResultInfo;
    private MaterialButton btnClear, btnStartMerge, btnCancelMerge;
    private TextInputEditText etOutputName;
    private View layoutProgress;
    private LinearProgressIndicator progressBar;
    private MaterialCardView cardResult;

    private final ActivityResultLauncher<String[]> pickMultipleLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris != null && !uris.isEmpty() && getContext() != null) {
                    stopTransitionPreview();
                    for (Uri uri : uris) {
                        mergeItems.add(AudioUtils.getAudioInfo(getContext(), uri));
                    }
                    refreshListUi();
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_merge, container, false);

        MaterialButton btnAdd = view.findViewById(R.id.btnAddMergeFiles);
        btnClear = view.findViewById(R.id.btnClearMergeList);
        tvEmpty = view.findViewById(R.id.tvMergeEmpty);
        RecyclerView rv = view.findViewById(R.id.rvMergeFiles);
        ChipGroup chipGroupBitrate = view.findViewById(R.id.chipGroupMergeBitrate);
        ChipGroup chipGroupTransition = view.findViewById(R.id.chipGroupMergeTransition);
        etOutputName = view.findViewById(R.id.etMergeOutputName);
        btnStartMerge = view.findViewById(R.id.btnStartMerge);
        btnCancelMerge = view.findViewById(R.id.btnCancelMerge);
        layoutProgress = view.findViewById(R.id.layoutMergeProgress);
        progressBar = view.findViewById(R.id.progressMerge);
        tvStatus = view.findViewById(R.id.tvMergeStatus);
        cardResult = view.findViewById(R.id.cardMergeResult);
        tvResultInfo = view.findViewById(R.id.tvMergeResultInfo);

        adapter = new MergeAdapter(mergeItems, new MergeAdapter.OnItemActionListener() {
            @Override
            public void onTrim(int position) {
                if (position >= 0 && position < mergeItems.size()) {
                    stopTransitionPreview();
                    openTrimDialog(position);
                }
            }

            @Override
            public void onPreviewTransition(int position) {
                if (position >= 0 && position < mergeItems.size() - 1) {
                    toggleTransitionPreview(position);
                }
            }

            @Override
            public void onMoveUp(int position) {
                if (position > 0) {
                    stopTransitionPreview();
                    Collections.swap(mergeItems, position, position - 1);
                    adapter.notifyDataSetChanged();
                }
            }

            @Override
            public void onMoveDown(int position) {
                if (position >= 0 && position < mergeItems.size() - 1) {
                    stopTransitionPreview();
                    Collections.swap(mergeItems, position, position + 1);
                    adapter.notifyDataSetChanged();
                }
            }

            @Override
            public void onRemove(int position) {
                if (position >= 0 && position < mergeItems.size()) {
                    stopTransitionPreview();
                    mergeItems.remove(position);
                    refreshListUi();
                }
            }
        });

        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        rv.setAdapter(adapter);

        btnAdd.setOnClickListener(v -> pickMultipleLauncher.launch(new String[]{"audio/mpeg", "audio/*"}));
        btnClear.setOnClickListener(v -> {
            stopTransitionPreview();
            mergeItems.clear();
            refreshListUi();
        });

        chipGroupBitrate.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chipMerge96) selectedBitrate = 96;
            else if (id == R.id.chipMerge128) selectedBitrate = 128;
            else if (id == R.id.chipMerge192) selectedBitrate = 192;
            else if (id == R.id.chipMerge256) selectedBitrate = 256;
            else if (id == R.id.chipMerge320) selectedBitrate = 320;
        });

        chipGroupTransition.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            stopTransitionPreview();
            int id = checkedIds.get(0);
            if (id == R.id.chipTransDirect) selectedTransition = TRANS_DIRECT;
            else if (id == R.id.chipTransPause1) selectedTransition = TRANS_PAUSE_1S;
            else if (id == R.id.chipTransPause2) selectedTransition = TRANS_PAUSE_2S;
            else if (id == R.id.chipTransCrossfade) selectedTransition = TRANS_CROSSFADE;
        });

        btnStartMerge.setOnClickListener(v -> startMerge());
        btnCancelMerge.setOnClickListener(v -> {
            isCancelled.set(true);
            btnCancelMerge.setEnabled(false);
            tvStatus.setText("Отмена...");
            if (activeSessionId > 0) {
                FFmpegKit.cancel(activeSessionId);
            } else {
                FFmpegKit.cancel();
            }
        });

        view.findViewById(R.id.btnMergeOpen).setOnClickListener(v -> {
            if (lastSavedUri != null && getContext() != null) {
                AudioUtils.openAudio(getContext(), lastSavedUri);
            }
        });
        view.findViewById(R.id.btnMergeShare).setOnClickListener(v -> {
            if (lastSavedUri != null && getContext() != null) {
                AudioUtils.shareAudio(getContext(), lastSavedUri);
            }
        });

        return view;
    }

    private void toggleTransitionPreview(int position) {
        if (playingTransitionIndex == position) {
            stopTransitionPreview();
            return;
        }
        stopTransitionPreview();

        if (getContext() == null || position < 0 || position >= mergeItems.size() - 1) return;

        AudioUtils.AudioInfo first = mergeItems.get(position);
        AudioUtils.AudioInfo second = mergeItems.get(position + 1);
        final int transMode = selectedTransition;
        final Context appCtx = requireContext().getApplicationContext();

        loadingTransitionIndex = position;
        adapter.setTransitionState(loadingTransitionIndex, -1);

        long firstStartBound = Math.max(0L, first.trimStartMs);
        long firstEndBound = (first.trimEndMs > firstStartBound && first.trimEndMs <= first.durationMs)
                ? first.trimEndMs : first.durationMs;
        long clip1StartMs = Math.max(firstStartBound, firstEndBound - 4000L);
        long clip1DurMs = Math.max(400L, firstEndBound - clip1StartMs);

        long secondStartBound = Math.max(0L, second.trimStartMs);
        long secondEndBound = (second.trimEndMs > secondStartBound && second.trimEndMs <= second.durationMs)
                ? second.trimEndMs : second.durationMs;
        long clip2StartMs = secondStartBound;
        long clip2DurMs = Math.min(4000L, Math.max(400L, secondEndBound - secondStartBound));

        previewExecutor.execute(() -> {
            File tmp1 = null;
            File tmp2 = null;
            File outPreview = null;
            try {
                tmp1 = AudioUtils.copyUriToCache(appCtx, first.uri, "tr_a");
                tmp2 = AudioUtils.copyUriToCache(appCtx, second.uri, "tr_b");
                outPreview = new File(appCtx.getCacheDir(), "tr_preview_" + System.currentTimeMillis() + ".mp3");

                String ss1 = String.format(Locale.US, "%.3f", clip1StartMs / 1000.0);
                String t1 = String.format(Locale.US, "%.3f", clip1DurMs / 1000.0);
                String ss2 = String.format(Locale.US, "%.3f", clip2StartMs / 1000.0);
                String t2 = String.format(Locale.US, "%.3f", clip2DurMs / 1000.0);

                String filterComplex;
                if (transMode == TRANS_PAUSE_1S || transMode == TRANS_PAUSE_2S) {
                    String pauseDur = (transMode == TRANS_PAUSE_1S) ? "1.0" : "2.0";
                    filterComplex =
                            "[0:a]aformat=sample_rates=44100:channel_layouts=stereo[a0];" +
                            "anullsrc=r=44100:cl=stereo,atrim=0:" + pauseDur + ",asetpts=PTS-STARTPTS[s0];" +
                            "[1:a]aformat=sample_rates=44100:channel_layouts=stereo[a1];" +
                            "[a0][s0][a1]concat=n=3:v=0:a=1[out]";
                } else if (transMode == TRANS_CROSSFADE) {
                    double minSec = Math.min(clip1DurMs, clip2DurMs) / 1000.0;
                    double cfSec = Math.min(1.5, Math.max(0.2, minSec * 0.4));
                    String cfStr = String.format(Locale.US, "%.2f", cfSec);
                    filterComplex =
                            "[0:a]aformat=sample_rates=44100:channel_layouts=stereo[a0];" +
                            "[1:a]aformat=sample_rates=44100:channel_layouts=stereo[a1];" +
                            "[a0][a1]acrossfade=d=" + cfStr + ":c1=tri:c2=tri[out]";
                } else {
                    filterComplex =
                            "[0:a]aformat=sample_rates=44100:channel_layouts=stereo[a0];" +
                            "[1:a]aformat=sample_rates=44100:channel_layouts=stereo[a1];" +
                            "[a0][a1]concat=n=2:v=0:a=1[out]";
                }

                String[] args = new String[]{
                        "-y",
                        "-ss", ss1, "-t", t1, "-i", tmp1.getAbsolutePath(),
                        "-ss", ss2, "-t", t2, "-i", tmp2.getAbsolutePath(),
                        "-filter_complex", filterComplex,
                        "-map", "[out]",
                        "-c:a", "libmp3lame",
                        "-b:a", "128k",
                        outPreview.getAbsolutePath()
                };

                var session = FFmpegKit.executeWithArguments(args);
                if (tmp1 != null) //noinspection ResultOfMethodCallIgnored
                    tmp1.delete();
                if (tmp2 != null) //noinspection ResultOfMethodCallIgnored
                    tmp2.delete();

                final File finalOut = outPreview;
                if (ReturnCode.isSuccess(session.getReturnCode()) && finalOut.exists() && isAdded()) {
                    runUi(() -> {
                        if (loadingTransitionIndex != position) {
                            //noinspection ResultOfMethodCallIgnored
                            finalOut.delete();
                            return;
                        }
                        playRenderedTransition(position, finalOut);
                    });
                } else {
                    if (finalOut != null) //noinspection ResultOfMethodCallIgnored
                        finalOut.delete();
                    runUi(() -> {
                        loadingTransitionIndex = -1;
                        adapter.setTransitionState(-1, -1);
                        Toast.makeText(getContext(), "Не удалось собрать превью перехода", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                if (tmp1 != null) //noinspection ResultOfMethodCallIgnored
                    tmp1.delete();
                if (tmp2 != null) //noinspection ResultOfMethodCallIgnored
                    tmp2.delete();
                if (outPreview != null) //noinspection ResultOfMethodCallIgnored
                    outPreview.delete();
                runUi(() -> {
                    loadingTransitionIndex = -1;
                    adapter.setTransitionState(-1, -1);
                    Toast.makeText(getContext(), "Ошибка превью: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void playRenderedTransition(int position, File previewFile) {
        try {
            lastTransitionFile = previewFile;
            transitionPlayer = new MediaPlayer();
            transitionPlayer.setDataSource(previewFile.getAbsolutePath());
            transitionPlayer.setOnCompletionListener(mp -> stopTransitionPreview());
            transitionPlayer.prepare();
            transitionPlayer.start();
            loadingTransitionIndex = -1;
            playingTransitionIndex = position;
            adapter.setTransitionState(-1, playingTransitionIndex);
        } catch (Exception e) {
            stopTransitionPreview();
            Toast.makeText(getContext(), "Ошибка воспроизведения перехода", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopTransitionPreview() {
        loadingTransitionIndex = -1;
        playingTransitionIndex = -1;
        if (transitionPlayer != null) {
            try {
                if (transitionPlayer.isPlaying()) transitionPlayer.stop();
                transitionPlayer.release();
            } catch (Exception ignored) {
            }
            transitionPlayer = null;
        }
        if (lastTransitionFile != null) {
            //noinspection ResultOfMethodCallIgnored
            lastTransitionFile.delete();
            lastTransitionFile = null;
        }
        if (adapter != null) {
            adapter.setTransitionState(-1, -1);
        }
    }

    private void openTrimDialog(int position) {
        if (getContext() == null) return;
        AudioUtils.AudioInfo item = mergeItems.get(position);
        if (item.durationMs < 1000) {
            Toast.makeText(getContext(), "Файл слишком короткий для обрезки", Toast.LENGTH_SHORT).show();
            return;
        }

        Dialog dialog = new Dialog(requireContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_trim_item, null);
        dialog.setContentView(dialogView);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }

        TextView tvTitle = dialogView.findViewById(R.id.tvDialogTitle);
        TextView tvSub = dialogView.findViewById(R.id.tvDialogSubtitle);
        tvTitle.setText(item.displayName);
        tvSub.setText("Общая длина: " + AudioUtils.formatTimeMs(item.durationMs));

        TrimEditorController controller = new TrimEditorController(requireContext(), dialogView);
        long start = item.trimStartMs;
        long end = item.trimEndMs > start ? item.trimEndMs : item.durationMs;
        controller.bindAudio(item, start, end);

        dialogView.findViewById(R.id.btnDialogReset).setOnClickListener(v -> controller.resetRange());
        dialogView.findViewById(R.id.btnDialogCancel).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.btnDialogApply).setOnClickListener(v -> {
            item.trimStartMs = controller.getStartMs();
            item.trimEndMs = controller.getEndMs();
            adapter.notifyItemChanged(position);
            dialog.dismiss();
        });

        dialog.setOnDismissListener(d -> controller.stopPlayback());
        dialog.show();
    }

    private void refreshListUi() {
        adapter.notifyDataSetChanged();
        boolean hasItems = !mergeItems.isEmpty();
        tvEmpty.setVisibility(hasItems ? View.GONE : View.VISIBLE);
        btnClear.setVisibility(hasItems ? View.VISIBLE : View.GONE);
        btnStartMerge.setEnabled(mergeItems.size() >= 2);
        cardResult.setVisibility(View.GONE);
    }

    private void startMerge() {
        if (mergeItems.size() < 2 || getContext() == null) return;
        stopTransitionPreview();

        String outputName = etOutputName.getText() != null ? etOutputName.getText().toString().trim() : "";
        if (outputName.isEmpty()) {
            outputName = "merged_" + System.currentTimeMillis();
        }
        final String finalOutputName = outputName;
        final List<AudioUtils.AudioInfo> snapshot = new ArrayList<>(mergeItems);
        final int transMode = selectedTransition;
        final int targetBr = selectedBitrate;
        final Context appCtx = requireContext().getApplicationContext();

        long totalDurMs = 0;
        long minClipMs = Long.MAX_VALUE;
        for (AudioUtils.AudioInfo item : snapshot) {
            long d = item.getEffectiveDurationMs();
            totalDurMs += d;
            if (d > 0 && d < minClipMs) minClipMs = d;
        }
        int seams = snapshot.size() - 1;
        double cfSec = 1.5;
        if (transMode == TRANS_PAUSE_1S) {
            totalDurMs += seams * 1000L;
        } else if (transMode == TRANS_PAUSE_2S) {
            totalDurMs += seams * 2000L;
        } else if (transMode == TRANS_CROSSFADE) {
            if (minClipMs == Long.MAX_VALUE) minClipMs = 4000L;
            cfSec = Math.min(1.5, Math.max(0.2, (minClipMs / 1000.0) * 0.4));
            totalDurMs = Math.max(1000L, totalDurMs - Math.round(seams * cfSec * 1000.0));
        }
        final long expectedDurationMs = totalDurMs;
        final double finalCfSec = cfSec;

        isCancelled.set(false);
        activeSessionId = -1;
        btnStartMerge.setEnabled(false);
        btnCancelMerge.setEnabled(true);
        cardResult.setVisibility(View.GONE);
        layoutProgress.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        tvStatus.setText("Подготовка файлов (" + snapshot.size() + " шт.)...");
        AudioProcessingService.startTask(appCtx, "Соединение MP3", "Подготовка файлов...");

        executor.execute(() -> {
            List<File> tempInputs = new ArrayList<>();
            File outputTemp = null;
            try {
                for (int i = 0; i < snapshot.size(); i++) {
                    if (isCancelled.get()) {
                        for (File f : tempInputs) //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        AudioProcessingService.stopTask(appCtx);
                        showCancelled();
                        return;
                    }
                    tempInputs.add(AudioUtils.copyUriToCache(appCtx, snapshot.get(i).uri, "merge_in_" + i));
                }
                outputTemp = new File(appCtx.getCacheDir(), "merge_out_" + System.currentTimeMillis() + ".mp3");

                List<String> argsList = new ArrayList<>();
                argsList.add("-y");
                StringBuilder filter = new StringBuilder();
                int count = tempInputs.size();

                for (int i = 0; i < count; i++) {
                    AudioUtils.AudioInfo info = snapshot.get(i);
                    argsList.add("-i");
                    argsList.add(tempInputs.get(i).getAbsolutePath());

                    filter.append("[").append(i).append(":a]");
                    if (info.isTrimmed()) {
                        String s = String.format(Locale.US, "%.3f", info.trimStartMs / 1000.0);
                        String e = String.format(Locale.US, "%.3f", info.trimEndMs / 1000.0);
                        filter.append("atrim=start=").append(s).append(":end=").append(e).append(",asetpts=PTS-STARTPTS,");
                    }
                    filter.append("aformat=sample_rates=44100:channel_layouts=stereo[a").append(i).append("];");
                }

                if (transMode == TRANS_PAUSE_1S || transMode == TRANS_PAUSE_2S) {
                    String pauseStr = (transMode == TRANS_PAUSE_1S) ? "1.0" : "2.0";
                    StringBuilder concatInputs = new StringBuilder();
                    for (int i = 0; i < count; i++) {
                        concatInputs.append("[a").append(i).append("]");
                        if (i < count - 1) {
                            filter.append("anullsrc=r=44100:cl=stereo,atrim=0:")
                                  .append(pauseStr)
                                  .append(",asetpts=PTS-STARTPTS[s").append(i).append("];");
                            concatInputs.append("[s").append(i).append("]");
                        }
                    }
                    filter.append(concatInputs)
                          .append("concat=n=").append(count * 2 - 1).append(":v=0:a=1[out]");
                } else if (transMode == TRANS_CROSSFADE) {
                    String cfStr = String.format(Locale.US, "%.2f", finalCfSec);
                    String prevLabel = "a0";
                    for (int i = 1; i < count; i++) {
                        String nextLabel = (i == count - 1) ? "out" : ("cf" + i);
                        filter.append("[").append(prevLabel).append("][a").append(i).append("]")
                              .append("acrossfade=d=").append(cfStr).append(":c1=tri:c2=tri[")
                              .append(nextLabel).append("]");
                        if (i < count - 1) filter.append(";");
                        prevLabel = nextLabel;
                    }
                } else {
                    StringBuilder concatInputs = new StringBuilder();
                    for (int i = 0; i < count; i++) {
                        concatInputs.append("[a").append(i).append("]");
                    }
                    filter.append(concatInputs).append("concat=n=").append(count).append(":v=0:a=1[out]");
                }

                argsList.add("-filter_complex");
                argsList.add(filter.toString());
                argsList.add("-map");
                argsList.add("[out]");
                argsList.add("-c:a");
                argsList.add("libmp3lame");
                argsList.add("-b:a");
                argsList.add(targetBr + "k");
                argsList.add(outputTemp.getAbsolutePath());

                String[] args = argsList.toArray(new String[0]);
                final File finalOutput = outputTemp;
                final long[] lastNotifMs = {0L};

                runUi(() -> {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(0);
                    tvStatus.setText("Соединение: 0%");
                });

                var session = FFmpegKit.executeWithArgumentsAsync(args,
                        s -> {
                            activeSessionId = -1;
                            AudioProcessingService.stopTask(appCtx);
                            if (isCancelled.get() || ReturnCode.isCancel(s.getReturnCode())) {
                                showCancelled();
                            } else if (ReturnCode.isSuccess(s.getReturnCode())) {
                                try {
                                    long newSize = finalOutput.length();
                                    AudioUtils.SaveResult res = AudioUtils.saveMp3Output(appCtx, finalOutput, finalOutputName);
                                    lastSavedUri = res.uri;
                                    runUi(() -> {
                                        layoutProgress.setVisibility(View.GONE);
                                        btnStartMerge.setEnabled(mergeItems.size() >= 2);
                                        cardResult.setVisibility(View.VISIBLE);
                                        tvResultInfo.setText(
                                                "Готово! Соединено файлов: " + snapshot.size() + "\n" +
                                                "Сохранено в " + res.displayPath + "\n" +
                                                "Длительность: " + AudioUtils.formatTimeMs(expectedDurationMs) +
                                                "  •  Размер: " + AudioUtils.formatSize(newSize) +
                                                " (" + targetBr + " кбит/с)"
                                        );
                                    });
                                } catch (Exception e) {
                                    showError("Ошибка сохранения: " + e.getMessage());
                                }
                            } else {
                                showError("Ошибка FFmpeg при соединении файлов");
                            }
                            for (File f : tempInputs) //noinspection ResultOfMethodCallIgnored
                                f.delete();
                            //noinspection ResultOfMethodCallIgnored
                            finalOutput.delete();
                        },
                        log -> {},
                        statistics -> {
                            if (expectedDurationMs > 0 && !isCancelled.get()) {
                                int pct = (int) Math.min(99, Math.round((statistics.getTime() / expectedDurationMs) * 100.0));
                                runUi(() -> {
                                    progressBar.setProgress(pct);
                                    tvStatus.setText("Соединение: " + pct + "%");
                                });
                                long now = System.currentTimeMillis();
                                if (now - lastNotifMs[0] > 500) {
                                    lastNotifMs[0] = now;
                                    AudioProcessingService.updateProgress(appCtx, "Соединение MP3", "Соединение: " + pct + "%", pct, false);
                                }
                            }
                        }
                );
                activeSessionId = session.getSessionId();
            } catch (Exception e) {
                activeSessionId = -1;
                AudioProcessingService.stopTask(appCtx);
                for (File f : tempInputs) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                if (outputTemp != null) //noinspection ResultOfMethodCallIgnored
                    outputTemp.delete();
                showError("Ошибка подготовки файлов: " + e.getMessage());
            }
        });
    }

    private void showCancelled() {
        runUi(() -> {
            layoutProgress.setVisibility(View.GONE);
            btnStartMerge.setEnabled(mergeItems.size() >= 2);
            btnCancelMerge.setEnabled(true);
            Toast.makeText(getContext(), "Соединение отменено", Toast.LENGTH_SHORT).show();
        });
    }

    private void showError(String msg) {
        runUi(() -> {
            layoutProgress.setVisibility(View.GONE);
            btnStartMerge.setEnabled(mergeItems.size() >= 2);
            btnCancelMerge.setEnabled(true);
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
        stopTransitionPreview();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            stopTransitionPreview();
        }
    }
}
