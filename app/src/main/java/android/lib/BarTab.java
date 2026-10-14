package android.lib;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

/**
 * Modern replacement for the original Eclair/Honeycomb-split BarTab classes.
 * Kept in the same package/API shape as the 2013 original (setText, setIcon,
 * setFragmentClass, getFragment) so the rest of the app (MainActivity,
 * ConnectionFragment, CollectionFragment, OPDSBrowserFragment) did not need
 * to change at all. Internally this is now backed by a single
 * com.google.android.material.bottomnavigation.BottomNavigationView menu
 * item instead of the old TabHost/ActionBar.Tab implementations.
 */
public class BarTab {

    protected final FragmentActivity activity;
    protected final String tag;
    private int textResId;
    private int iconResId;
    private Class<? extends Fragment> fragmentClass;
    private Fragment fragment;

    protected BarTab(FragmentActivity activity, String tag) {
        this.activity = activity;
        this.tag = tag;
    }

    public String getTag() {
        return tag;
    }

    public BarTab setText(int resId) {
        this.textResId = resId;
        return this;
    }

    public BarTab setIcon(int resId) {
        this.iconResId = resId;
        return this;
    }

    public BarTab setFragmentClass(Class<? extends Fragment> cls) {
        this.fragmentClass = cls;
        return this;
    }

    public int getTextResId() {
        return textResId;
    }

    public int getIconResId() {
        return iconResId;
    }

    public Class<? extends Fragment> getFragmentClass() {
        return fragmentClass;
    }

    /**
     * Returns the currently attached Fragment instance for this tab, or null
     * if it has not been created/attached yet (mirrors the original API,
     * callers throughout the app already null-check this).
     */
    public Fragment getFragment() {
        return fragment;
    }

    void setFragment(Fragment fragment) {
        this.fragment = fragment;
    }
}
