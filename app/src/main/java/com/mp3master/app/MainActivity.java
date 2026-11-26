package com.mp3master.app;

import android.os.Bundle;
import android.view.View;

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
    private Fragment activeFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        View root = findViewById(R.id.rootLayout);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(toolbar.getPaddingLeft(), bars.top, toolbar.getPaddingRight(), toolbar.getPaddingBottom());
            bottomNav.setPadding(bottomNav.getPaddingLeft(), bottomNav.getPaddingTop(), bottomNav.getPaddingRight(), bars.bottom);
            return insets;
        });

        FragmentManager fm = getSupportFragmentManager();
        if (savedInstanceState == null) {
            convertFragment = new ConvertFragment();
            trimFragment = new TrimFragment();
            mergeFragment = new MergeFragment();
            activeFragment = convertFragment;

            fm.beginTransaction()
                    .add(R.id.fragmentContainer, mergeFragment, "merge").hide(mergeFragment)
                    .add(R.id.fragmentContainer, trimFragment, "trim").hide(trimFragment)
                    .add(R.id.fragmentContainer, convertFragment, "convert")
                    .commit();
            toolbar.setTitle("Изменение качества MP3");
        } else {
            convertFragment = fm.findFragmentByTag("convert");
            trimFragment = fm.findFragmentByTag("trim");
            mergeFragment = fm.findFragmentByTag("merge");
            if (convertFragment == null) convertFragment = new ConvertFragment();
            if (trimFragment == null) trimFragment = new TrimFragment();
            if (mergeFragment == null) mergeFragment = new MergeFragment();

            if (!trimFragment.isHidden()) {
                activeFragment = trimFragment;
                toolbar.setTitle("Обрезка MP3");
            } else if (!mergeFragment.isHidden()) {
                activeFragment = mergeFragment;
                toolbar.setTitle("Соединение MP3");
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
            }
            return false;
        });
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
