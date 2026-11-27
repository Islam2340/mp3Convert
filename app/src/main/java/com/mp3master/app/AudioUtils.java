package com.mp3master.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import androidx.documentfile.provider.DocumentFile;

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

    private static final String PREFS_NAME = "mp3master_prefs";
    private static final String KEY_SAVE_TREE_URI = "save_tree_uri";
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

    public static class SaveResult {
        public final Uri uri;
        public final String displayPath;

        public SaveResult(Uri uri, String displayPath) {
            this.uri = uri;
            this.displayPath = displayPath;
        }
    }

    public interface WaveformCallback {
        void onWaveformReady(float[] peaks);
    }

    // --- Custom Save Folder (SAF) ---

    public static void setSaveFolderUri(Context context, Uri treeUri) {
        if (treeUri == null) return;
        try {
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            context.getContentResolver().takePersistableUriPermission(treeUri, flags);
        } catch (Exception ignored) {
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_SAVE_TREE_URI, treeUri.toString()).apply();
    }

    public static void clearSaveFolderUri(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(KEY_SAVE_TREE_URI).apply();
    }

    public static Uri getSaveFolderUri(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_SAVE_TREE_URI, null);
        if (raw == null || raw.isEmpty()) return null;
        return Uri.parse(raw);
    }

    public static String getSaveFolderDisplayName(Context context) {
        Uri treeUri = getSaveFolderUri(context);
        if (treeUri == null) {
            return "Music/MP3Master";
        }
        try {
            DocumentFile doc = DocumentFile.fromTreeUri(context, treeUri);
            if (doc != null && doc.exists() && doc.canWrite()) {
                String path = treeUri.getPath();
                if (path != null && path.contains(":")) {
                    String sub = path.substring(path.lastIndexOf(':') + 1);
                    if (!sub.isEmpty()) {
                        return sub;
                    }
                }
                if (doc.getName() != null && !doc.getName().isEmpty()) {
                    return doc.getName();
                }
            }
        } catch (Exception ignored) {
        }
        return "Music/MP3Master";
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

    public static SaveResult saveMp3Output(Context context, File sourceFile, String outputBaseName) throws IOException {
        String safeBase = sanitizeFileName(stripExtension(outputBaseName), "output_" + System.currentTimeMillis());
        String safeName = safeBase + ".mp3";

        // 1. Try custom SAF folder if selected
        Uri customTreeUri = getSaveFolderUri(context);
        if (customTreeUri != null) {
            DocumentFile folder = DocumentFile.fromTreeUri(context, customTreeUri);
            if (folder != null && folder.exists() && folder.canWrite()) {
                DocumentFile existing = folder.findFile(safeName);
                String targetBase = safeBase;
                if (existing != null) {
                    targetBase = safeBase + "_" + (System.currentTimeMillis() % 10000);
                }
                DocumentFile created = folder.createFile("audio/mpeg", targetBase);
                if (created != null) {
                    try (InputStream in = new FileInputStream(sourceFile);
                         OutputStream out = context.getContentResolver().openOutputStream(created.getUri())) {
                        if (out == null) throw new IOException("Ошибка записи в выбранную папку");
                        byte[] buf = new byte[16384];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                    }
                    String folderDisplay = getSaveFolderDisplayName(context);
                    String actualName = created.getName() != null ? created.getName() : (targetBase + ".mp3");
                    return new SaveResult(created.getUri(), folderDisplay + "/" + actualName);
                }
            }
        }

        // 2. Fallback to Music/MP3Master
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
            return new SaveResult(uri, "Music/MP3Master/" + safeName);
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
            return new SaveResult(Uri.fromFile(dest), "Music/MP3Master/" + safeName);
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

    public static class VideoInfo {
        public Uri uri;
        public String displayName;
        public long durationMs;
        public long sizeBytes;
        public int width;
        public int height;
        public int rotation;
        public boolean hasAudio;

        public int getDisplayWidth() {
            return (rotation == 90 || rotation == 270) ? height : width;
        }

        public int getDisplayHeight() {
            return (rotation == 90 || rotation == 270) ? width : height;
        }
    }

    public static VideoInfo getVideoInfo(Context context, Uri uri) {
        VideoInfo info = new VideoInfo();
        info.uri = uri;
        info.displayName = getFileName(context, uri);
        info.sizeBytes = getFileSize(context, uri);
        info.hasAudio = true;

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            String hasAud = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            if (dur != null) info.durationMs = Long.parseLong(dur);
            if (w != null) info.width = Integer.parseInt(w);
            if (h != null) info.height = Integer.parseInt(h);
            if (rot != null) info.rotation = Integer.parseInt(rot);
            if (hasAud != null) info.hasAudio = "yes".equalsIgnoreCase(hasAud);
            else info.hasAudio = false;
        } catch (Exception ignored) {
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        return info;
    }

    public static File copyUriToCacheWithExt(Context context, Uri uri, String prefix, String ext) throws IOException {
        File outFile = new File(context.getCacheDir(), prefix + "_" + System.currentTimeMillis() + ext);
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(outFile)) {
            if (in == null) throw new IOException("Не удалось открыть файл");
            byte[] buf = new byte[32768];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
        return outFile;
    }

    public static SaveResult saveMp4Output(Context context, File sourceFile, String outputBaseName) throws IOException {
        String safeBase = sanitizeFileName(stripExtension(outputBaseName), "video_" + System.currentTimeMillis());
        String safeName = safeBase + ".mp4";

        Uri customTreeUri = getSaveFolderUri(context);
        if (customTreeUri != null) {
            DocumentFile folder = DocumentFile.fromTreeUri(context, customTreeUri);
            if (folder != null && folder.exists() && folder.canWrite()) {
                DocumentFile existing = folder.findFile(safeName);
                String targetBase = safeBase;
                if (existing != null) {
                    targetBase = safeBase + "_" + (System.currentTimeMillis() % 10000);
                }
                DocumentFile created = folder.createFile("video/mp4", targetBase);
                if (created != null) {
                    try (InputStream in = new FileInputStream(sourceFile);
                         OutputStream out = context.getContentResolver().openOutputStream(created.getUri())) {
                        if (out == null) throw new IOException("Ошибка записи в выбранную папку");
                        byte[] buf = new byte[32768];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                    }
                    String folderDisplay = getSaveFolderDisplayName(context);
                    String actualName = created.getName() != null ? created.getName() : (targetBase + ".mp4");
                    return new SaveResult(created.getUri(), folderDisplay + "/" + actualName);
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, safeName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MP3Master");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);

            Uri uri = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("Не удалось создать файл в Movies/MP3Master");
            }

            try (InputStream in = new FileInputStream(sourceFile);
                 OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Ошибка записи в MediaStore");
                byte[] buf = new byte[32768];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }

            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
            return new SaveResult(uri, "Movies/MP3Master/" + safeName);
        } else {
            File moviesDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "MP3Master"
            );
            if (!moviesDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                moviesDir.mkdirs();
            }
            File dest = new File(moviesDir, safeName);
            try (InputStream in = new FileInputStream(sourceFile);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[32768];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
            return new SaveResult(Uri.fromFile(dest), "Movies/MP3Master/" + safeName);
        }
    }

    public static void openVideo(Context context, Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "video/mp4");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(Intent.createChooser(intent, "Открыть видео"));
    }

    public static void shareVideo(Context context, Uri uri) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("video/mp4");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(Intent.createChooser(intent, "Поделиться видео"));
    }
}
