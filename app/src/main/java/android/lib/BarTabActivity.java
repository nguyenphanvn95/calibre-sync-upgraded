package android.lib;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import net.mrphan.calibre.R;

/**
 * Modern replacement for the 2013 BarTabActivity (which picked between an
 * Eclair TabWidget implementation and a Honeycomb ActionBar.Tab
 * implementation). Both code paths collapse into one Material TabLayout
 * implementation on modern Android, so there is no version branching left.
 *
 * Subclasses (MainActivity) are unchanged from the original: call
 * setContentView(...) first, then getBarTabManager().
 *
 * IMPORTANT: must extend AppCompatActivity (not plain FragmentActivity).
 * The app theme is Theme.MaterialComponents.DayNight.DarkActionBar, which
 * only renders an ActionBar (and therefore the options-menu overflow button
 * that Fragments' onCreateOptionsMenu/setHasOptionsMenu rely on -- Settings,
 * Rediscover, Manual Connection, Disconnect) when the hosting Activity is an
 * AppCompatActivity. On plain FragmentActivity no ActionBar is created at
 * all, so those menu items exist in code but have nowhere to be shown.
 */
public abstract class BarTabActivity extends AppCompatActivity {
    protected BarTabManager mBarTabManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBarTabManager = BarTabManager.createInstance(this);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mBarTabManager.onSaveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mBarTabManager.onRestoreInstanceState(savedInstanceState);
    }

    /**
     * Must be called after setContentView() of a layout that contains a
     * BottomNavigationView with id R.id.bartab_bottomnav and a FrameLayout
     * content container with id R.id.bartab_content (see
     * res/layout/tabs_main.xml). Previously this looked for a top TabLayout
     * instead -- switched to a bottom nav bar because the TabLayout-in-plain-
     * LinearLayout combination was unreliable to lay out/tap (see
     * tabs_main.xml comment for details).
     */
    protected BarTabManager getBarTabManager() {
        BottomNavigationView bottomNav = findViewById(R.id.bartab_bottomnav);
        mBarTabManager.setup(bottomNav, R.id.bartab_content);
        return mBarTabManager;
    }
}
