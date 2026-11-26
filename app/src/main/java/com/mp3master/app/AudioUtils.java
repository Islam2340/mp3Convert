package com.mp3master.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AudioUtils {

    private static final ExecutorService waveformExecutor = Executors.newSingleThreadExecutor();

    public static class AudioInfo {
        public Uri uri;
        public String displayName;
        public long durationMs;
        public long sizeBytes;
        public int bitrateKbps;
        public long trimStartMs;
        public long trimEndMs;

        public boolean isTrimmed() {
            return durationMs > 0 && (trimStartMs > 100 || (trimEndMs > 0 && trimEndMs < durationMs - 100));
        }

        public long getEffectiveDurationMs() {
            long end = (trimEndMs > 0 && trimEndMs <= durationMs) ? trimEndMs : durationMs;
            long start = Math.max(0, Math.min(trimStartMs, end));
            return Math.max(0, end - start);
        }
    }

    public interface WaveformCallback {
        void onWaveformReady(float[] peaks);
    }

    public static AudioInfo getAudioInfo(Context context, Uri uri) {
        AudioInfo info = new AudioInfo();
        info.uri = uri;
        info.displayName = getFileName(context, uri);
        info.sizeBytes = getFileSize(context, uri);

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String br = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (dur != null) {
                info.durationMs = Long.parseLong(dur);
            }
            if (br != null) {
                info.bitrateKbps = Math.round(Long.parseLong(br) / 1000f);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        info.trimStartMs = 0;
        info.trimEndMs = info.durationMs;
        return info;
    }

    public static void extractWaveformAsync(Context context, Uri uri, long durationMs, WaveformCallback callback) {
        Context appCtx = context.getApplicationContext();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        waveformExecutor.execute(() -> {
            File inTemp = null;
            File rawTemp = null;
            try {
                inTemp = copyUriToCache(appCtx, uri, "wf_in");
                rawTemp = new File(appCtx.getCacheDir(), "wf_pcm_" + System.currentTimeMillis() + ".raw");

                String[] args = new String[]{
                        "-y",
                        "-i", inTemp.getAbsolutePath(),
                        "-vn",
                        "-ac", "1",
                        "-ar", "2000",
                        "-f", "u8",
                        rawTemp.getAbsolutePath()
                };

                var session = FFmpegKit.executeWithArguments(args);
                if (ReturnCode.isSuccess(session.getReturnCode()) && rawTemp.exists() && rawTemp.length() > 0) {
                    long totalBytes = rawTemp.length();
                    int barCount = (int) Math.max(200, Math.min(2400, (durationMs / 250L)));
                    float[] peaks = new float[barCount];
                    long bytesPerBar = Math.max(1, totalBytes / barCount);

                    byte[] buffer = new byte[16384];
                    long byteIndex = 0;
                    float maxGlobal = 0.01f;

                    try (InputStream is = new FileInputStream(rawTemp)) {
                        int read;
                        while ((read = is.read(buffer)) > 0) {
                            for (int i = 0; i < read; i++) {
                                int barIdx = (int) Math.min(barCount - 1, byteIndex / bytesPerBar);
                                int sample = (buffer[i] & 0xFF) - 128;
                                float amp = Math.abs(sample) / 128f;
                                if (amp > peaks[barIdx]) {
                                    peaks[barIdx] = amp;
                                }
                                if (amp > maxGlobal) {
                                    maxGlobal = amp;
                                }
                                byteIndex++;
                            }
                        }
                    }

                    for (int i = 0; i < barCount; i++) {
                        peaks[i] = Math.min(1f, peaks[i] / maxGlobal);
                    }

                    mainHandler.post(() -> callback.onWaveformReady(peaks));
                }
            } catch (Exception ignored) {
            } finally {
                if (inTemp != null) //noinspection ResultOfMethodCallIgnored
                    inTemp.delete();
                if (rawTemp != null) //noinspection ResultOfMethodCallIgnored
                    rawTemp.delete();
            }
        });
    }

    public static String getFileName(Context context, Uri uri) {
        String result = "audio.mp3";
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = cursor.getString(idx);
                    if (name != null && !name.isEmpty()) {
                        result = name;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public static long getFileSize(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0) {
                    return cursor.getLong(idx);
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    public static String stripExtension(String name) {
        if (name == null) return "audio";
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

    public static String sanitizeFileName(String name, String fallback) {
        if (name == null) return fallback;
        String clean = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (clean.isEmpty()) return fallback;
        return clean;
    }

    public static String formatTimeMs(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    public static String formatTimePreciseMs(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000;
        long tenths = (ms % 1000) / 100;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d.%d", hours, minutes, seconds, tenths);
        }
        return String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, tenths);
    }

    public static String formatSize(long bytes) {
        if (bytes <= 0) return "0 КБ";
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f КБ", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.2f МБ", bytes / (1024.0 * 1024.0));
    }

    public static File copyUriToCache(Context context, Uri uri, String prefix) throws IOException {
        File outFile = new File(context.getCacheDir(), prefix + "_" + System.currentTimeMillis() + ".mp3");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(outFile)) {
            if (in == null) throw new IOException("Не удалось открыть файл");
            byte[] buf = new byte[16384];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
        return outFile;
    }

    public static Uri saveMp3ToMusic(Context context, File sourceFile, String outputBaseName) throws IOException {
        String safeName = sanitizeFileName(outputBaseName, "output_" + System.currentTimeMillis());
        if (!safeName.toLowerCase(Locale.US).endsWith(".mp3")) {
            safeName = safeName + ".mp3";
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Audio.Media.DISPLAY_NAME, safeName);
            values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg");
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/MP3Master");
            values.put(MediaStore.Audio.Media.IS_PENDING, 1);

            Uri uri = context.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("Не удалось создать файл в Music/MP3Master");
            }

            try (InputStream in = new FileInputStream(sourceFile);
                 OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Ошибка записи в MediaStore");
                byte[] buf = new byte[16384];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }

            values.clear();
            values.put(MediaStore.Audio.Media.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
            return uri;
        } else {
            File musicDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    "MP3Master"
            );
            if (!musicDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                musicDir.mkdirs();
            }
            File dest = new File(musicDir, safeName);
            try (InputStream in = new FileInputStream(sourceFile);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[16384];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
            return Uri.fromFile(dest);
        }
    }

    public static void openAudio(Context context, Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "audio/mpeg");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(Intent.createChooser(intent, "Открыть в плеере"));
    }

    public static void shareAudio(Context context, Uri uri) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("audio/mpeg");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(Intent.createChooser(intent, "Поделиться MP3"));
    }
}
