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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private Fragment convertFragment;
    private Fragment trimFragment;
    private Fragment mergeFragment;
    private Fragment replaceFragment;
    private Fragment activeFragment;

    private TextView tvSaveFolderPath;
    private ImageButton btnResetSaveFolder;

    private final ActivityResultLauncher<String> requestNotificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

    private final ActivityResultLauncher<android.net.Uri> pickFolderLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) {
                    AudioUtils.setSaveFolderUri(this, uri);
                    updateSaveFolderBar();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
                    Toast.makeText(this, "Папка сохранения изменена", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        View root = findViewById(R.id.rootLayout);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
        tvSaveFolderPath = findViewById(R.id.tvSaveFolderPath);
        btnResetSaveFolder = findViewById(R.id.btnResetSaveFolder);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(toolbar.getPaddingLeft(), bars.top, toolbar.getPaddingRight(), toolbar.getPaddingBottom());
            bottomNav.setPadding(bottomNav.getPaddingLeft(), bottomNav.getPaddingTop(), bottomNav.getPaddingRight(), bars.bottom);
            return insets;
        });

        findViewById(R.id.btnPickSaveFolder).setOnClickListener(v -> pickFolderLauncher.launch(null));
        btnResetSaveFolder.setOnClickListener(v -> {
            AudioUtils.clearSaveFolderUri(this);
            updateSaveFolderBar();
            Toast.makeText(this, "Возвращена папка по умолчанию", Toast.LENGTH_SHORT).show();
        });
        updateSaveFolderBar();

        FragmentManager fm = getSupportFragmentManager();
        if (savedInstanceState == null) {
            convertFragment = new ConvertFragment();
            trimFragment = new TrimFragment();
            mergeFragment = new MergeFragment();
            replaceFragment = new ReplaceFragment();
            activeFragment = convertFragment;

            fm.beginTransaction()
                    .add(R.id.fragmentContainer, replaceFragment, "replace").hide(replaceFragment)
                    .add(R.id.fragmentContainer, mergeFragment, "merge").hide(mergeFragment)
                    .add(R.id.fragmentContainer, trimFragment, "trim").hide(trimFragment)
                    .add(R.id.fragmentContainer, convertFragment, "convert")
                    .commit();
            toolbar.setTitle("Изменение качества MP3");
        } else {
            convertFragment = fm.findFragmentByTag("convert");
            trimFragment = fm.findFragmentByTag("trim");
            mergeFragment = fm.findFragmentByTag("merge");
            replaceFragment = fm.findFragmentByTag("replace");
            if (convertFragment == null) convertFragment = new ConvertFragment();
            if (trimFragment == null) trimFragment = new TrimFragment();
            if (mergeFragment == null) mergeFragment = new MergeFragment();
            if (replaceFragment == null) replaceFragment = new ReplaceFragment();

            if (!trimFragment.isHidden()) {
                activeFragment = trimFragment;
                toolbar.setTitle("Обрезка MP3");
            } else if (!mergeFragment.isHidden()) {
                activeFragment = mergeFragment;
                toolbar.setTitle("Соединение MP3");
            } else if (!replaceFragment.isHidden()) {
                activeFragment = replaceFragment;
                toolbar.setTitle("Замена фрагмента");
            } else {
                activeFragment = convertFragment;
                toolbar.setTitle("Изменение качества MP3");
            }
        }

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_convert) {
                switchFragment(convertFragment, "Изменение качества MP3");
                return true;
            } else if (id == R.id.nav_trim) {
                switchFragment(trimFragment, "Обрезка MP3");
                return true;
            } else if (id == R.id.nav_merge) {
                switchFragment(mergeFragment, "Соединение MP3");
                return true;
            } else if (id == R.id.nav_replace) {
                switchFragment(replaceFragment, "Замена фрагмента");
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
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(title);
    }
}
