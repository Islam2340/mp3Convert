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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MergeFragment extends Fragment {

    private final List<AudioUtils.AudioInfo> mergeItems = new ArrayList<>();
    private MergeAdapter adapter;
    private int selectedBitrate = 192;
    private Uri lastSavedUri;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView tvEmpty, tvStatus, tvResultInfo;
    private MaterialButton btnClear, btnStartMerge;
    private TextInputEditText etOutputName;
    private View layoutProgress;
    private LinearProgressIndicator progressBar;
    private MaterialCardView cardResult;

    private final ActivityResultLauncher<String[]> pickMultipleLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris != null && !uris.isEmpty() && getContext() != null) {
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
        ChipGroup chipGroup = view.findViewById(R.id.chipGroupMergeBitrate);
        etOutputName = view.findViewById(R.id.etMergeOutputName);
        btnStartMerge = view.findViewById(R.id.btnStartMerge);
        layoutProgress = view.findViewById(R.id.layoutMergeProgress);
        progressBar = view.findViewById(R.id.progressMerge);
        tvStatus = view.findViewById(R.id.tvMergeStatus);
        cardResult = view.findViewById(R.id.cardMergeResult);
        tvResultInfo = view.findViewById(R.id.tvMergeResultInfo);

        adapter = new MergeAdapter(mergeItems, new MergeAdapter.OnItemActionListener() {
            @Override
            public void onMoveUp(int position) {
                if (position > 0) {
                    Collections.swap(mergeItems, position, position - 1);
                    adapter.notifyDataSetChanged();
                }
            }

            @Override
            public void onMoveDown(int position) {
                if (position >= 0 && position < mergeItems.size() - 1) {
                    Collections.swap(mergeItems, position, position + 1);
                    adapter.notifyDataSetChanged();
                }
            }

            @Override
            public void onRemove(int position) {
                if (position >= 0 && position < mergeItems.size()) {
                    mergeItems.remove(position);
                    refreshListUi();
                }
            }
        });

        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        rv.setAdapter(adapter);

        btnAdd.setOnClickListener(v -> pickMultipleLauncher.launch(new String[]{"audio/mpeg", "audio/*"}));
        btnClear.setOnClickListener(v -> {
            mergeItems.clear();
            refreshListUi();
        });

        chipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chipMerge128) selectedBitrate = 128;
            else if (id == R.id.chipMerge192) selectedBitrate = 192;
            else if (id == R.id.chipMerge256) selectedBitrate = 256;
            else if (id == R.id.chipMerge320) selectedBitrate = 320;
        });

        btnStartMerge.setOnClickListener(v -> startMerge());

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

        String outputName = etOutputName.getText() != null ? etOutputName.getText().toString().trim() : "";
        if (outputName.isEmpty()) {
            outputName = "merged_" + System.currentTimeMillis();
        }
        final String finalOutputName = outputName;
        final List<AudioUtils.AudioInfo> snapshot = new ArrayList<>(mergeItems);

        long totalDurMs = 0;
        for (AudioUtils.AudioInfo item : snapshot) {
            totalDurMs += item.durationMs;
        }
        final long expectedDurationMs = totalDurMs;

        btnStartMerge.setEnabled(false);
        cardResult.setVisibility(View.GONE);
        layoutProgress.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        tvStatus.setText("Подготовка файлов (" + snapshot.size() + " шт.)...");

        executor.execute(() -> {
            List<File> tempInputs = new ArrayList<>();
            File outputTemp = null;
            try {
                for (int i = 0; i < snapshot.size(); i++) {
                    tempInputs.add(AudioUtils.copyUriToCache(requireContext(), snapshot.get(i).uri, "merge_in_" + i));
                }
                outputTemp = new File(requireContext().getCacheDir(), "merge_out_" + System.currentTimeMillis() + ".mp3");

                List<String> argsList = new ArrayList<>();
                argsList.add("-y");
                StringBuilder filter = new StringBuilder();
                StringBuilder concatInputs = new StringBuilder();
                for (int i = 0; i < tempInputs.size(); i++) {
                    argsList.add("-i");
                    argsList.add(tempInputs.get(i).getAbsolutePath());
                    filter.append("[").append(i).append(":a]aformat=sample_rates=44100:channel_layouts=stereo[a").append(i).append("];");
                    concatInputs.append("[a").append(i).append("]");
                }
                filter.append(concatInputs).append("concat=n=").append(tempInputs.size()).append(":v=0:a=1[out]");

                argsList.add("-filter_complex");
                argsList.add(filter.toString());
                argsList.add("-map");
                argsList.add("[out]");
                argsList.add("-c:a");
                argsList.add("libmp3lame");
                argsList.add("-b:a");
                argsList.add(selectedBitrate + "k");
                argsList.add(outputTemp.getAbsolutePath());

                String[] args = argsList.toArray(new String[0]);
                final File finalOutput = outputTemp;

                requireActivity().runOnUiThread(() -> {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(0);
                    tvStatus.setText("Соединение: 0%");
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
                                        btnStartMerge.setEnabled(mergeItems.size() >= 2);
                                        cardResult.setVisibility(View.VISIBLE);
                                        tvResultInfo.setText(
                                                "Готово! Соединено файлов: " + snapshot.size() + "
" +
                                                "Сохранено в Music/MP3Master/" + finalOutputName + ".mp3
" +
                                                "Размер: " + AudioUtils.formatSize(newSize) +
                                                " (" + selectedBitrate + " кбит/с)"
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
                            if (expectedDurationMs > 0) {
                                int pct = (int) Math.min(99, Math.round((statistics.getTime() / expectedDurationMs) * 100.0));
                                if (isAdded()) {
                                    requireActivity().runOnUiThread(() -> {
                                        progressBar.setProgress(pct);
                                        tvStatus.setText("Соединение: " + pct + "%");
                                    });
                                }
                            }
                        }
                );
            } catch (Exception e) {
                for (File f : tempInputs) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                if (outputTemp != null) //noinspection ResultOfMethodCallIgnored
                    outputTemp.delete();
                showError("Ошибка подготовки файлов: " + e.getMessage());
            }
        });
    }

    private void showError(String msg) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            layoutProgress.setVisibility(View.GONE);
            btnStartMerge.setEnabled(mergeItems.size() >= 2);
            Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
        });
    }
}
