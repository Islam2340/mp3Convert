package com.mp3master.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private Fragment convertFragment;
    private Fragment trimFragment;
    private Fragment mergeFragment;
    private Fragment replaceFragment;
    private Fragment videoFragment;
    private Fragment activeFragment;

    private TextView tvHeaderTitle;
    private TextView tvSaveFolderPath;
    private ImageButton btnResetSaveFolder;

    private final ActivityResultLauncher<String> requestNotificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

    private final ActivityResultLauncher<android.net.Uri> pickFolderLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) {
                    AudioUtils.setSaveFolderUri(this, uri);
                    updateSaveFolderBar();
                    Toast.makeText(this, "Папка сохранения изменена", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean isDark = AudioUtils.isDarkTheme(this);
        AppCompatDelegate.setDefaultNightMode(
                isDark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO
        );
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        View root = findViewById(R.id.rootLayout);
        View topHeaderBar = findViewById(R.id.topHeaderBar);
        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
        tvHeaderTitle = findViewById(R.id.tvHeaderTitle);
        tvSaveFolderPath = findViewById(R.id.tvSaveFolderPath);
        btnResetSaveFolder = findViewById(R.id.btnResetSaveFolder);

        final int origTopPad = topHeaderBar.getPaddingTop();
        final int origBottomPad = bottomNav.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            topHeaderBar.setPadding(
                    topHeaderBar.getPaddingLeft(),
                    origTopPad + bars.top,
                    topHeaderBar.getPaddingRight(),
                    topHeaderBar.getPaddingBottom()
            );
            bottomNav.setPadding(
                    bottomNav.getPaddingLeft(),
                    bottomNav.getPaddingTop(),
                    bottomNav.getPaddingRight(),
                    origBottomPad + bars.bottom
            );
            return insets;
        });

        findViewById(R.id.btnCheckUpdate).setOnClickListener(v -> UpdateManager.checkForUpdate(this));

        ImageButton btnToggleTheme = findViewById(R.id.btnToggleTheme);
        btnToggleTheme.setImageResource(isDark ? R.drawable.ic_theme_light : R.drawable.ic_theme_dark);
        btnToggleTheme.setOnClickListener(v -> {
            boolean nextDark = !AudioUtils.isDarkTheme(this);
            AudioUtils.setDarkTheme(this, nextDark);
            AppCompatDelegate.setDefaultNightMode(
                    nextDark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO
            );
        });

        findViewById(R.id.btnPickSaveFolder).setOnClickListener(v -> pickFolderLauncher.launch(null));
        btnResetSaveFolder.setOnClickListener(v -> {
            AudioUtils.clearSaveFolderUri(this);
            updateSaveFolderBar();
            Toast.makeText(this, "Возвращена папка по умолчанию", Toast.LENGTH_SHORT).show();
        });
        updateSaveFolderBar();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        FragmentManager fm = getSupportFragmentManager();
        if (savedInstanceState == null) {
            convertFragment = new ConvertFragment();
            trimFragment = new TrimFragment();
            mergeFragment = new MergeFragment();
            replaceFragment = new ReplaceFragment();
            videoFragment = new VideoFragment();
            activeFragment = videoFragment;

            fm.beginTransaction()
                    .add(R.id.fragmentContainer, convertFragment, "convert").hide(convertFragment)
                    .add(R.id.fragmentContainer, mergeFragment, "merge").hide(mergeFragment)
                    .add(R.id.fragmentContainer, trimFragment, "trim").hide(trimFragment)
                    .add(R.id.fragmentContainer, replaceFragment, "replace").hide(replaceFragment)
                    .add(R.id.fragmentContainer, videoFragment, "video")
                    .commit();
            tvHeaderTitle.setText("Видео MP4");
            bottomNav.setSelectedItemId(R.id.nav_video);
        } else {
            convertFragment = fm.findFragmentByTag("convert");
            trimFragment = fm.findFragmentByTag("trim");
            mergeFragment = fm.findFragmentByTag("merge");
            replaceFragment = fm.findFragmentByTag("replace");
            videoFragment = fm.findFragmentByTag("video");
            if (convertFragment == null) convertFragment = new ConvertFragment();
            if (trimFragment == null) trimFragment = new TrimFragment();
            if (mergeFragment == null) mergeFragment = new MergeFragment();
            if (replaceFragment == null) replaceFragment = new ReplaceFragment();
            if (videoFragment == null) videoFragment = new VideoFragment();

            if (!trimFragment.isHidden()) {
                activeFragment = trimFragment;
                tvHeaderTitle.setText("Обрезка MP3");
            } else if (!mergeFragment.isHidden()) {
                activeFragment = mergeFragment;
                tvHeaderTitle.setText("Соединить");
            } else if (!replaceFragment.isHidden()) {
                activeFragment = replaceFragment;
                tvHeaderTitle.setText("Замена");
            } else if (!videoFragment.isHidden()) {
                activeFragment = videoFragment;
                tvHeaderTitle.setText("Видео MP4");
            } else {
                activeFragment = convertFragment;
                tvHeaderTitle.setText("Конвертация");
            }
        }

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_convert) {
                switchFragment(convertFragment, "Конвертация");
                return true;
            } else if (id == R.id.nav_trim) {
                switchFragment(trimFragment, "Обрезка MP3");
                return true;
            } else if (id == R.id.nav_merge) {
                switchFragment(mergeFragment, "Соединить");
                return true;
            } else if (id == R.id.nav_replace) {
                switchFragment(replaceFragment, "Замена");
                return true;
            } else if (id == R.id.nav_video) {
                switchFragment(videoFragment, "Видео MP4");
                return true;
            }
            return false;
        });
    }

    private void updateSaveFolderBar() {
        boolean hasCustom = AudioUtils.getSaveFolderUri(this) != null;
        tvSaveFolderPath.setText("Папка: " + AudioUtils.getSaveFolderDisplayName(this));
        btnResetSaveFolder.setVisibility(hasCustom ? View.VISIBLE : View.GONE);
    }

    private void switchFragment(Fragment target, String title) {
        if (target == activeFragment) return;
        getSupportFragmentManager().beginTransaction()
                .hide(activeFragment)
                .show(target)
                .commit();
        activeFragment = target;
        tvHeaderTitle.setText(title);
    }
}
