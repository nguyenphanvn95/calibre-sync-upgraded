package net.mrphan.calibre;

import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

public class NavigationManager {
	public static FragmentActivity mActivity;
	private FragmentManager mFragmentManager;
	public NavigationManager(FragmentActivity activity) {
		mActivity = activity;
		mFragmentManager = mActivity.getSupportFragmentManager();
	}
}
