package com.mp3master.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UpdateManager {

    private static final String REPO_API_LATEST =
            "https://api.github.com/repos/Islam2340/mp3Convert/releases/latest";

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void checkForUpdate(Activity activity) {
        Toast.makeText(activity, "Проверка обновлений...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(REPO_API_LATEST);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "MP3Master-Android");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                int code = conn.getResponseCode();
                if (code == 404) {
                    postToast(activity, "Релиз не найден. Проверьте: 1) репозиторий Public, 2) обновлён .github/workflows/build.yml");
                    return;
                }
                if (code != 200) {
                    postToast(activity, "Ошибка проверки: код " + code);
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }

                JSONObject json = new JSONObject(sb.toString());
                String tagName = json.optString("tag_name", "").trim();
                String latestVer = tagName.startsWith("v") || tagName.startsWith("V")
                        ? tagName.substring(1) : tagName;
                String currentVer = BuildConfig.VERSION_NAME;

                String apkUrl = null;
                JSONArray assets = json.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String name = asset.optString("name", "");
                        if (name.toLowerCase().endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", null);
                            break;
                        }
                    }
                }

                if (apkUrl == null || apkUrl.isEmpty()) {
                    postToast(activity, "В последнем релизе не найден APK");
                    return;
                }

                if (!isNewerVersion(latestVer, currentVer)) {
                    postToast(activity, "У вас уже последняя версия (" + currentVer + ")");
                    return;
                }

                final String finalApkUrl = apkUrl;
                final String finalLatestVer = latestVer;
                mainHandler.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    new AlertDialog.Builder(activity)
                            .setTitle("Доступно обновление " + finalLatestVer)
                            .setMessage("Текущая версия: " + currentVer + "\nНовая версия: " + finalLatestVer + "\n\nСкачать и установить поверх?")
                            .setPositiveButton("Скачать", (d, w) -> downloadAndInstall(activity, finalApkUrl, finalLatestVer))
                            .setNegativeButton("Позже", null)
                            .show();
                });
            } catch (Exception e) {
                postToast(activity, "Не удалось проверить обновления: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static void downloadAndInstall(Activity activity, String apkUrl, String version) {
        float d = activity.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * d);
        box.setPadding(pad, pad, pad, pad);

        TextView tv = new TextView(activity);
        tv.setText("Загрузка v" + version + "...");
        box.addView(tv);

        ProgressBar pb = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setIndeterminate(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = Math.round(12 * d);
        pb.setLayoutParams(lp);
        box.addView(pb);

        AlertDialog progressDialog = new AlertDialog.Builder(activity)
                .setTitle("Обновление")
                .setView(box)
                .setCancelable(false)
                .show();

        executor.execute(() -> {
            HttpURLConnection conn = null;
            File outFile = new File(activity.getCacheDir(), "update_" + version + ".apk");
            try {
                URL url = new URL(apkUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);

                int total = conn.getContentLength();
                try (InputStream in = new BufferedInputStream(conn.getInputStream());
                     FileOutputStream out = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[32768];
                    int read;
                    long downloaded = 0;
                    int lastPct = -1;
                    while ((read = in.read(buf)) > 0) {
                        out.write(buf, 0, read);
                        downloaded += read;
                        if (total > 0) {
                            int pct = (int) Math.min(100, (downloaded * 100L) / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                int finalPct = pct;
                                mainHandler.post(() -> {
                                    pb.setIndeterminate(false);
                                    pb.setProgress(finalPct);
                                    tv.setText("Загрузка v" + version + ": " + finalPct + "%");
                                });
                            }
                        }
                    }
                }

                mainHandler.post(() -> {
                    progressDialog.dismiss();
                    launchInstaller(activity, outFile);
                });
            } catch (Exception e) {
                if (outFile.exists()) //noinspection ResultOfMethodCallIgnored
                    outFile.delete();
                mainHandler.post(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(activity, "Ошибка загрузки: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static void launchInstaller(Activity activity, File apkFile) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    apkFile
            );
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "Не удалось запустить установщик: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static boolean isNewerVersion(String latest, String current) {
        if (latest == null || latest.isEmpty()) return false;
        String[] lp = latest.split("\\.");
        String[] cp = current.split("\\.");
        int len = Math.max(lp.length, cp.length);
        for (int i = 0; i < len; i++) {
            int lv = i < lp.length ? parseIntSafe(lp[i]) : 0;
            int cv = i < cp.length ? parseIntSafe(cp[i]) : 0;
            if (lv != cv) return lv > cv;
        }
        return false;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private static void postToast(Activity activity, String msg) {
        mainHandler.post(() -> Toast.makeText(activity, msg, Toast.LENGTH_LONG).show());
    }
}
