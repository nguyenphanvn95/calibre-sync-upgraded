package android.lib;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Renders tabs with a Material BottomNavigationView and swaps Fragments into a FrameLayout
 * content container, same overall behaviour as before -- just backed by a bottom nav bar
 * instead of a top TabLayout. The TabLayout-in-plain-LinearLayout version was unreliable: it
 * would fail to lay out (invisible/zero-height) in portrait, and in landscape rendered content
 * confined to roughly half the screen with the rest dead space that didn't respond to taps.
 * BottomNavigationView is simpler (no custom measuring inside a LinearLayout to get wrong) and
 * is the same well-tested widget every mainstream Android app already uses for exactly this
 * "2-4 top-level destinations" navigation shape.
 */
public class BarTabManager implements BottomNavigationView.OnItemSelectedListener {

    private static final String STATE_SELECTED_TAG = "bartabmanager_selected_tag";

    protected final FragmentActivity activity;
    protected final HashMap<String, BarTab> mTabs = new HashMap<>();
    private final List<BarTab> mOrderedTabs = new ArrayList<>();
    private final HashMap<String, Integer> tagToItemId = new HashMap<>();
    private final HashMap<Integer, String> itemIdToTag = new HashMap<>();
    private BottomNavigationView bottomNav;
    private int contentContainerId;
    private BarTab selectedTab;
    private int nextItemId = 1;

    protected BarTabManager(FragmentActivity activity) {
        this.activity = activity;
    }

    public static BarTabManager createInstance(FragmentActivity activity) {
        return new BarTabManager(activity);
    }

    public BarTab newTab(String tag) {
        return new BarTab(activity, tag);
    }

    public void addTab(BarTab tab) {
        mTabs.put(tab.getTag(), tab);
        mOrderedTabs.add(tab);
        if (bottomNav != null) {
            int itemId = nextItemId++;
            Menu menu = bottomNav.getMenu();
            MenuItem item = menu.add(Menu.NONE, itemId, mOrderedTabs.size(), tab.getTextResId());
            item.setIcon(tab.getIconResId());
            tagToItemId.put(tab.getTag(), itemId);
            itemIdToTag.put(itemId, tab.getTag());
        }
    }

    public void switchTab(BarTab tab) {
        if (tab == null) return;
        FragmentManager fm = activity.getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();

        // Reset the Activity title to this tab's own label *before* the fragment transaction
        // commits below. Some tabs (OPDSBrowserFragment) set a more specific title later of
        // their own accord (current folder/library name, offline banner title...), but that
        // only happens from async callbacks (root feed load success/failure) -- nothing ever
        // set it back when leaving that tab. Without this reset, whatever title
        // OPDSBrowserFragment last set (e.g. "Thư viện online" while offline) stayed stuck on
        // screen forever, including after switching to Connection or Device Library, which
        // never touch the title at all. Setting the tab's own default label here first means:
        // (a) tabs that never customize the title (Connection, Device Library) always show
        // their own name instead of a leftover from whichever tab was open before, and (b)
        // tabs that do customize it further (Calibre Library) can still override this default
        // synchronously during their onResume/onStart, since that runs as part of the
        // commitNowAllowingStateLoss() call below, which happens after this line.
        activity.setTitle(tab.getTextResId());

        for (BarTab t : mOrderedTabs) {
            Fragment f = fm.findFragmentByTag(t.getTag());
            if (f != null) {
                if (t == tab) {
                    ft.show(f);
                } else {
                    ft.hide(f);
                    // show()/hide() only toggle View visibility -- they do NOT stop a hidden
                    // fragment from still contributing to the options menu. Without this, every
                    // tab ever opened keeps adding its onCreateOptionsMenu() items to the shared
                    // toolbar menu (fragments are only hidden here, never destroyed), so opening
                    // a second tab crowds the action bar past capacity. When that happens,
                    // "ifRoom" action items (like Device Library / Calibre Library's search
                    // SearchView) get silently demoted to the overflow menu, where no action
                    // view is inflated -- and both fragments call
                    // menu.findItem(R.id.menu_search).getActionView() unconditionally, which
                    // NPEs the instant that demotion happens. That is the crash on tapping either
                    // of those two tabs.
                    f.setMenuVisibility(false);
                }
            }
        }

        Fragment current = fm.findFragmentByTag(tab.getTag());
        if (current == null) {
            try {
                current = tab.getFragmentClass().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Unable to instantiate fragment for tab " + tab.getTag(), e);
            }
            ft.add(contentContainerId, current, tab.getTag());
        }
        current.setMenuVisibility(true);
        tab.setFragment(current);
        ft.commitNowAllowingStateLoss();

        // Force the toolbar to throw away the previous tab's menu items and re-run
        // onCreateOptionsMenu() for only the now-visible fragment.
        activity.invalidateOptionsMenu();

        selectedTab = tab;

        // Reflect programmatic tab switches (e.g. switchTabByTag from an Intent extra, or
        // restoring saved state) in the bottom nav bar's selected icon/label.
        Integer itemId = tagToItemId.get(tab.getTag());
        if (itemId != null && bottomNav.getSelectedItemId() != itemId) {
            bottomNav.setSelectedItemId(itemId);
        }
    }

    public void switchTabByTag(String tag) {
        BarTab tab = mTabs.get(tag);
        if (tab != null) {
            switchTab(tab);
        }
    }

    /**
     * Call once after setContentView, passing the BottomNavigationView and the id of the
     * FrameLayout that should host the swapped-in tab fragments.
     */
    public BarTabManager setup(BottomNavigationView bottomNav, int contentContainerId) {
        this.bottomNav = bottomNav;
        this.contentContainerId = contentContainerId;
        bottomNav.setOnItemSelectedListener(this);
        return this;
    }

    public void onSaveInstanceState(Bundle outState) {
        if (selectedTab != null) {
            outState.putString(STATE_SELECTED_TAG, selectedTab.getTag());
        }
    }

    public void onRestoreInstanceState(Bundle savedInstanceState) {
        String tag = savedInstanceState.getString(STATE_SELECTED_TAG);
        if (tag != null) {
            switchTabByTag(tag);
        }
    }

    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        String tag = itemIdToTag.get(item.getItemId());
        if (tag != null) {
            BarTab barTab = mTabs.get(tag);
            if (barTab != null && barTab != selectedTab) {
                switchTab(barTab);
            }
        }
        return true;
    }
}
