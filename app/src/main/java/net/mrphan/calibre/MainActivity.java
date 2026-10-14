package net.mrphan.calibre;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.lib.BarTab;
import android.lib.BarTabActivity;
import android.lib.BarTabManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.content.pm.PackageManager;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends BarTabActivity
        implements ConnectServerDialogFragment.ConnectServerDialogListener,
        NoServerDialogFragment.NoServerDialogListener,
        ConnectionFragment.ConnectionFragmentListener,
        CollectionFragment.CollectionFragmentListener,
        BookInfoDialogFragment.BookInfoDialogListener,
        OPDSBrowserFragment.OPDSFragmentListener,
        CalibreService.CalibreListener {
    public static final String EXTRA_TAB = "switch_tab";
    private static final String UPDATE_MANIFEST_URL =
            "https://raw.githubusercontent.com/nguyenphanvn95/calibre-sync-upgraded/main/calibre-sync-verson.json";
    private FragmentManager mFragmentManager;
    private BarTabManager mBarTabManager;
    private static SharedPreferences mSettings;
    private static MetadataDatabaseHelper mDB;
    private BarTab tab_connection,tab_collection,tab_opds;

    // --- Floating "now playing" TTS panel (Device Library / Calibre Library browsing) ---
    // Shows the mini player over whichever tab is active while a "Đọc cho nghe" session started
    // from a book reader keeps playing in the background, mirroring Readest's floating audio bar.
    // See net.mrphan.calibre.reader.tts.GlobalTtsFloatingPanel's kdoc.
    private net.mrphan.calibre.reader.tts.GlobalTtsFloatingPanel mTtsFloatingPanel;

    // --- Header + Drawer navigation (nang cap header gon + Drawer duy nhat) ---
    private DrawerLayout mDrawerLayout;
    /** True khi accordion "Cloud Sync" trong Drawer dang xoe ra (xem toggleCloudSyncSubmenu()).
     * Luon bi ep ve false moi khi Drawer dong lai (xem addDrawerListener trong onCreate) de lan
     * mo Drawer tiep theo accordion luon bat dau o dang THU GON, dung yeu cau. */
    private boolean mCloudSyncExpanded = false;
    /** Cac id muc con trong group_cloud_sync_children (nav_drawer_menu.xml) -- an/hien dong loat
     * moi khi accordion "Cloud Sync" duoc bam, xem toggleCloudSyncSubmenu(). */
    private static final int[] CLOUD_SYNC_CHILD_IDS = {
            R.id.nav_cloud_google_drive, R.id.nav_cloud_onedrive, R.id.nav_cloud_box,
            R.id.nav_cloud_dropbox, R.id.nav_cloud_webdav_ftp
    };
    /** 4 ma ngon ngu quick-toggle bam-la-doi-ngay o header Drawer, PHAI dung thu tu voi
     * pref_app_language_values trong strings.xml (bo qua muc dau "" = System default - quick toggle
     * chi luot qua 4 ngon ngu cu the, muon chon "Theo he thong" thi vao Settings > Giao dien nhu cu). */
    private static final String[] QUICK_TOGGLE_LANGUAGE_TAGS = {"en", "vi", "de", "it"};
    private ImageButton btnHeaderMenu, btnHeaderSearch, btnHeaderRefresh, btnHeaderOverflow,
            btnHeaderSearchBack, btnHeaderSearchClear;
    private View headerNormal, headerSearch;
    private TextView tvHeaderTitle, tvHeaderCount;
    private EditText etHeaderSearch;

    // --- Nut "Tiep tuc doc/nghe" (nang cap tiep-tuc-doc-fab) ---------------------------------
    private static final String PREF_SHOW_CONTINUE_READING_FAB = "pref_show_continue_reading_fab";
    private static final String PREF_AUTO_RESUME_LAST_BOOK = "pref_auto_resume_last_book";
    private FloatingActionButton fabContinueReading;
    // true khi GlobalTtsFloatingPanel (mini player TTS noi) dang hien o goc man hinh -- dung de
    // an fabContinueReading trong luc do, tranh 2 control chong len nhau (xem
    // refreshContinueReadingFabVisibility()).
    private boolean ttsPanelVisible = false;
    // true khi CollectionFragment (tab "Device Library") dang o che do multi-select va thanh pill
    // noi (Select all / Sync / Share / Delete) dang hien o day man hinh -- dung de an
    // fabContinueReading trong luc do, tranh 2 control chong len nhau, giong het co che
    // ttsPanelVisible o tren. Cap nhat qua CollectionFragmentListener#onCollectionSelectionModeChanged().
    private boolean collectionSelectionModeActive = false;

    private CalibreService mCalibreService;
    private boolean isBoundToService;
    private final Activity mActivity = this;
    private Intent calibreServiceIntent;
    private ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mCalibreService = ((CalibreService.CalibreBinder) service).getService();
            mCalibreService.setListener((CalibreService.CalibreListener) mActivity);
            isBoundToService = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBoundToService = false;
            mCalibreService = null;
        }
    };

	/* Test Flow:
	 * 1. Condition: Options Erased, No Existing Folder
	 * 	  Open App
	 *    Connect to Server
	 *    Download books using Calibre
	 *    Download books using Content Server
	 *    Open Calibre-loaded book
	 *    Open Content server loaded book
	 *    Delete both books
	 *    Use Calibre to download more books
	 * 2. Condition: Existing Options, Existing Folder
	 * 	  Open App
	 *    Connect to Server
	 *    Change Root Folder to Non-Existing Folder
	 *    Open books from Root Folder
	 *    Delete books from Root Folder
	 *    Download books using Calibre 
	 *  
	 * 3. Condition: Existing Options, Existing Folder
	 * 	  Open App
	 * 	  Connect to Server
	 *    Change Root Folder to Existing Empty Folder
	 *    Download books using Calibre
	 *    Open books from Root Folder
	 * 4. Condition: Existing Options, No Existing Folder (Delete Root folder after initial setup)
	 * 	  Open App
	 *    Connect to Server
	 *    Download books using Calibre
	 *    Open books from Root Folder
	 * 5. Condition: Existing Options, Existing Folder, Close Calibre Server
	 * 	  Open App
	 */

    @Override
    public void onBackPressed() {
        if (mDrawerLayout != null && mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
            mDrawerLayout.closeDrawer(GravityCompat.START);
            return;
        }
        OPDSBrowserFragment frag = (OPDSBrowserFragment) tab_opds.getFragment();
        if (frag == null || !frag.isVisible() || !frag.loadPreviousPage()) {
            super.onBackPressed();
        }
    }

    // ---- Header dung chung (hamburger, tieu de + so sach, search, refresh, 3 cham) ----------

    private void setupAppHeader() {
        View header = findViewById(R.id.app_header);

        // BUG FIX: header tung dung android:fitsSystemWindows="true" trong app_header.xml de tu
        // cong padding tranh status bar, nhung tren targetSdk 35 (edge-to-edge bi ep buoc)
        // fitsSystemWindows khong con tu dong cong padding tin cay duoc nua - dung y het van de
        // da fix o BookSearchActivity/BookDetailActivity/DeviceBookDetailActivity. Header nhan
        // inset top = 0 nen bi ve de len ngay tu y=0, nam duoi status bar - tieu de + nut
        // hamburger nhin nhu bien mat hoan toan tren man hinh that (du van thay binh thuong tren
        // Layout Preview vi preview khong mo phong that inset nay). Fix: tu tay cong padding-top
        // = inset that su cua status bar, giong 100% pattern da chung minh hoat dong dung o cac
        // Activity tren, thay vi dua vao fitsSystemWindows.
        header.setPadding(header.getPaddingLeft(), 0, header.getPaddingRight(),
                header.getPaddingBottom());
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(header, (v, insets) -> {
            androidx.core.graphics.Insets bars =
                    insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), bars.top, v.getPaddingRight(),
                    v.getPaddingBottom());
            return insets;
        });

        headerNormal = header.findViewById(R.id.header_state_normal);
        headerSearch = header.findViewById(R.id.header_state_search);
        tvHeaderTitle = header.findViewById(R.id.tv_header_title);
        tvHeaderCount = header.findViewById(R.id.tv_header_count);
        btnHeaderMenu = header.findViewById(R.id.btn_header_menu);
        btnHeaderSearch = header.findViewById(R.id.btn_header_search);
        btnHeaderRefresh = header.findViewById(R.id.btn_header_refresh);
        btnHeaderOverflow = header.findViewById(R.id.btn_header_overflow);
        btnHeaderSearchBack = header.findViewById(R.id.btn_header_search_back);
        btnHeaderSearchClear = header.findViewById(R.id.btn_header_search_clear);
        etHeaderSearch = header.findViewById(R.id.et_header_search);

        btnHeaderMenu.setOnClickListener(v -> mDrawerLayout.openDrawer(GravityCompat.START));

        btnHeaderSearch.setOnClickListener(v -> {
            headerNormal.setVisibility(View.GONE);
            headerSearch.setVisibility(View.VISIBLE);
            etHeaderSearch.requestFocus();
        });
        btnHeaderSearchBack.setOnClickListener(v -> closeHeaderSearch());
        btnHeaderSearchClear.setOnClickListener(v -> etHeaderSearch.setText(""));
        etHeaderSearch.setOnEditorActionListener((v, actionId, event) -> {
            HeaderContentProvider provider = currentHeaderProvider();
            if (provider != null) provider.onHeaderSearchQuery(etHeaderSearch.getText().toString());
            return true;
        });

        btnHeaderRefresh.setOnClickListener(v -> {
            HeaderContentProvider provider = currentHeaderProvider();
            if (provider != null) provider.onHeaderRefresh();
        });
        btnHeaderOverflow.setOnClickListener(v -> {
            HeaderContentProvider provider = currentHeaderProvider();
            if (provider != null) provider.onHeaderOverflowClick(btnHeaderOverflow);
        });
    }

    private void closeHeaderSearch() {
        headerSearch.setVisibility(View.GONE);
        headerNormal.setVisibility(View.VISIBLE);
        etHeaderSearch.setText("");
    }

    /** Goi lai moi khi doi tab (tu onCreate/onStart, tu cac muc Drawer, tu
     * onBookListChanged()) de dong bo tieu de/so sach/nut search voi trang dang hien. */
    private void refreshHeaderForCurrentTab() {
        if (tvHeaderTitle == null) return; // header chua duoc setup xong (chi xay ra truoc onCreate)
        HeaderContentProvider provider = currentHeaderProvider();
        closeHeaderSearch();
        if (provider == null) return;
        tvHeaderTitle.setText(provider.getHeaderTitle());
        String count = provider.getHeaderCountLabel();
        if (count == null || count.isEmpty()) {
            tvHeaderCount.setVisibility(View.GONE);
        } else {
            tvHeaderCount.setVisibility(View.VISIBLE);
            tvHeaderCount.setText(count);
        }
        btnHeaderSearch.setVisibility(provider.supportsHeaderSearch() ? View.VISIBLE : View.GONE);
    }

    /** Tra ve fragment dang active trong 3 tab (tab_connection/tab_collection/tab_opds), ep kieu
     * sang HeaderContentProvider -- ca 3 fragment nay deu implement interface do (xem muc 2.4 cua
     * prompt nang cap). BarTab#getFragment() da co san trong code hien tai (dung o
     * onConnectionStateChanged, confirmDelete, v.v.) -- tai su dung dung cach do. */
    private HeaderContentProvider currentHeaderProvider() {
        if (tab_connection.getFragment() != null && tab_connection.getFragment().isVisible())
            return (HeaderContentProvider) tab_connection.getFragment();
        if (tab_collection.getFragment() != null && tab_collection.getFragment().isVisible())
            return (HeaderContentProvider) tab_collection.getFragment();
        if (tab_opds.getFragment() != null && tab_opds.getFragment().isVisible())
            return (HeaderContentProvider) tab_opds.getFragment();
        return null;
    }

    // ---- Drawer dieu huong duy nhat -----------------------------------------------------------

    private void setupNavigationDrawer() {
        NavigationView navView = findViewById(R.id.nav_view);
        setupDrawerQuickToggles(navView);
        navView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_device_library) {
                mBarTabManager.switchTab(tab_collection);
                refreshHeaderForCurrentTab();
            } else if (id == R.id.nav_calibre_device) {
                mBarTabManager.switchTab(tab_connection);
                ConnectionFragment devFrag = (ConnectionFragment) tab_connection.getFragment();
                if (devFrag != null) devFrag.setDisplayMode(ConnectionFragment.MODE_WIRELESS_ONLY);
                refreshHeaderForCurrentTab();
                // pref_auto_discover_server (Settings > Connection) defaults to off: when it's
                // off, CalibreService no longer starts discovery on its own (onStartCommand /
                // NetworkCallback), so this tap is the trigger instead. Harmless to call when the
                // pref is on too -- discoverServers() just restarts an already-running scan.
                if (mCalibreService != null) mCalibreService.discoverServers();
            } else if (id == R.id.nav_calibre_library) {
                showCalibreLibraryChooserDialog();
            } else if (id == R.id.nav_calibre_gdrive_libraries) {
                GDriveOpdsLibraryListBottomSheet.newInstance()
                        .show(mFragmentManager, "gdrive_opds_library_list");
            } else if (id == R.id.nav_cloud_sync) {
                // "Cloud Sync" gio la 1 muc accordion: mac dinh thu gon, bam vao XOE ra ngay
                // trong Drawer danh sach cac nut cloud provider (group_cloud_sync_children) thay
                // vi nhay sang tab Connection nhu truoc -- KHONG dong Drawer, KHONG chuyen tab o
                // buoc nay, chi doi trang thai xoe/thu gon.
                toggleCloudSyncSubmenu(navView, item);
                return true; // giu Drawer mo de nguoi dung thay danh sach vua xoe ra
            } else if (id == R.id.nav_cloud_google_drive) {
                openCloudProviderFromDrawer(ProviderCardType.GOOGLE_DRIVE,
                        R.drawable.ic_provider_google_drive, R.string.cloud_type_google_drive);
            } else if (id == R.id.nav_cloud_onedrive) {
                openCloudProviderFromDrawer(ProviderCardType.ONEDRIVE,
                        R.drawable.ic_provider_onedrive, R.string.cloud_card_onedrive);
            } else if (id == R.id.nav_cloud_box) {
                openCloudProviderFromDrawer(ProviderCardType.BOX,
                        R.drawable.ic_provider_box, R.string.cloud_card_box);
            } else if (id == R.id.nav_cloud_dropbox) {
                openCloudProviderFromDrawer(ProviderCardType.DROPBOX,
                        R.drawable.ic_provider_dropbox, R.string.cloud_card_dropbox);
            } else if (id == R.id.nav_cloud_webdav_ftp) {
                openCloudProviderFromDrawer(ProviderCardType.WEBDAV_FTP,
                        R.drawable.ic_provider_webdav, R.string.cloud_card_webdav_ftp);
            } else if (id == R.id.nav_progress) {
                startActivity(ProgressActivity.buildIntent(this));
            } else if (id == R.id.nav_settings) {
                new SettingsSheet().show(getSupportFragmentManager(), SettingsSheet.TAG);
            } else if (id == R.id.nav_about) {
                // Truoc day mo showAboutDialog() (AlertDialog chi co so version) -- gio mo man
                // hinh "What's New" day du, xem WhatsNewActivity.
                startActivity(WhatsNewActivity.buildIntent(this));
            } else if (id == R.id.nav_exit) {
                exitApp();
                return true; // khong can dong Drawer/refresh gi nua, process sap bi ket thuc
            }
            mDrawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });
        // Accordion "Cloud Sync" luon phai bat dau o dang thu gon moi lan mo lai Drawer -- kho
        // dong lai (du dong vi chon 1 muc con, bam nut back, hay vuot ra ngoai) deu reset ve thu
        // gon, doc lap voi viec Drawer dong bang nhanh code nao.
        mDrawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerClosed(View drawerView) {
                collapseCloudSyncSubmenu(navView);
            }
        });
    }

    /** Gan click listener cho 2 nut quick-toggle (co ngon ngu + mat troi/trang doi theme) o header
     * cua Drawer, va set icon dung voi trang thai hien tai ngay khi Drawer duoc dung len. Header nay
     * duoc NavigationView tu inflate tu app:headerLayout="@layout/nav_drawer_header" (xem
     * tabs_main.xml) nen phai lay qua navView.getHeaderView(0), khong the findViewById() thang tu
     * Activity. Xem prompt-nav-drawer-quick-toggle-language-theme.md. */
    private void setupDrawerQuickToggles(NavigationView navView) {
        View headerView = navView.getHeaderView(0);
        if (headerView == null) return;

        ImageButton btnQuickLanguage = headerView.findViewById(R.id.btn_drawer_quick_language);
        ImageButton btnQuickTheme = headerView.findViewById(R.id.btn_drawer_quick_theme);

        if (btnQuickLanguage != null) {
            btnQuickLanguage.setOnClickListener(v -> cycleAppLanguage());
        }
        if (btnQuickTheme != null) {
            btnQuickTheme.setOnClickListener(v -> cycleAppTheme());
        }
        refreshDrawerQuickToggleIcons(headerView);
    }

    /** Doc trang thai ngon ngu/theme HIEN TAI va cap nhat icon 2 nut quick-toggle cho khop - goi lai
     * moi khi Drawer header duoc setup (onCreate/setupNavigationDrawer), vi Activity se tu recreate()
     * sau khi doi ngon ngu (AppCompatDelegate.setApplicationLocales) hoac sau khi doi theme
     * (cycleAppTheme() tu goi recreate()), nen ham nay luon duoc goi lai voi trang thai moi nhat. */
    private void refreshDrawerQuickToggleIcons(View headerView) {
        ImageButton btnQuickLanguage = headerView.findViewById(R.id.btn_drawer_quick_language);
        ImageButton btnQuickTheme = headerView.findViewById(R.id.btn_drawer_quick_theme);

        if (btnQuickLanguage != null) {
            String currentTag = AppCompatDelegate.getApplicationLocales().toLanguageTags();
            btnQuickLanguage.setImageResource(flagIconForLanguageTag(currentTag));
        }
        if (btnQuickTheme != null) {
            btnQuickTheme.setImageResource(themeIconForNightMode(AppCompatDelegate.getDefaultNightMode()));
        }
    }

    /** Anh xa language tag hien tai (co the la "", "en", "vi-VN", "de", "it"...) sang icon co tuong
     * ung. Dung startsWith() thay vi equals() vi toLanguageTags() co the tra ve dang co vung mien
     * (vd "vi-VN") tuy thiet bi. Tag rong ("" = System default) hoac khong khop ngon ngu nao rieng
     * deu roi ve co Anh, vi English la ngon ngu build mac dinh cua app. */
    private int flagIconForLanguageTag(String tag) {
        if (tag == null) return R.drawable.ic_flag_gb;
        if (tag.startsWith("vi")) return R.drawable.ic_flag_vn;
        if (tag.startsWith("de")) return R.drawable.ic_flag_de;
        if (tag.startsWith("it")) return R.drawable.ic_flag_it;
        return R.drawable.ic_flag_gb;
    }

    /** Anh xa AppCompatDelegate.MODE_NIGHT_* hien tai sang icon tuong ung (mat troi/mat trang/mat
     * trang+sao). */
    private int themeIconForNightMode(int nightMode) {
        if (nightMode == AppCompatDelegate.MODE_NIGHT_NO) return R.drawable.ic_theme_light;
        if (nightMode == AppCompatDelegate.MODE_NIGHT_YES) return R.drawable.ic_theme_dark;
        return R.drawable.ic_theme_system; // MODE_NIGHT_FOLLOW_SYSTEM (hoac gia tri khac khong ro)
    }

    /** Bam nut co o header Drawer: chuyen sang ngon ngu KE TIEP trong QUICK_TOGGLE_LANGUAGE_TAGS
     * (vong tron, het "it" quay lai "en"). Tai su dung dung API AppCompatDelegate.setApplicationLocales()
     * ma SettingsFragment#prefAppLanguage da dung (xem SettingsFragment.java) - khong
     * viet lai co che doi locale moi. setApplicationLocales() TU DONG recreate() moi AppCompatActivity
     * dang song (bao gom chinh MainActivity nay) nen KHONG can tu goi recreate() hay tu cap nhat icon
     * o day - onCreate() chay lai se tu goi setupNavigationDrawer() -> refreshDrawerQuickToggleIcons()
     * voi ngon ngu moi. */
    private void cycleAppLanguage() {
        String currentTag = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        int currentIndex = -1;
        for (int i = 0; i < QUICK_TOGGLE_LANGUAGE_TAGS.length; i++) {
            if (QUICK_TOGGLE_LANGUAGE_TAGS[i].equals(currentTag)) {
                currentIndex = i;
                break;
            }
        }
        int nextIndex = (currentIndex + 1) % QUICK_TOGGLE_LANGUAGE_TAGS.length;
        AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(QUICK_TOGGLE_LANGUAGE_TAGS[nextIndex]));
    }

    /** Bam nut mat troi/mat trang o header Drawer: chuyen theme sang trang thai KE TIEP theo vong lap
     * Theo he thong -> Sang -> Toi -> Theo he thong... Ghi lai gia tri moi vao chinh SharedPreferences
     * key AppThemeManager.PREF_KEY ("pref_app_theme") de dong bo voi ListPreference "Chu de" trong
     * Settings > Giao dien (SettingsFragment.java) - lan sau mo Settings se thay dung
     * gia tri vua doi, va lan sau mo app AppThemeManager#applyPersistedTheme() cung doc dung gia tri
     * nay. AppCompatDelegate.setDefaultNightMode() KHONG tu recreate() Activity (khac voi
     * setApplicationLocales() o cycleAppLanguage()) nen phai tu goi recreate(). */
    private void cycleAppTheme() {
        int current = AppCompatDelegate.getDefaultNightMode();
        int next;
        if (current == AppCompatDelegate.MODE_NIGHT_NO) {
            next = AppCompatDelegate.MODE_NIGHT_YES;
        } else if (current == AppCompatDelegate.MODE_NIGHT_YES) {
            next = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        } else {
            next = AppCompatDelegate.MODE_NIGHT_NO;
        }
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString(AppThemeManager.PREF_KEY, String.valueOf(next))
                .apply();
        AppCompatDelegate.setDefaultNightMode(next);
        recreate();
    }

    /** Bam vao muc "Cloud Sync": doi trang thai xoe/thu gon, an/hien dong loat cac muc con trong
     * group_cloud_sync_children va xoay mui ten chevron (app:actionLayout cua nav_cloud_sync,
     * xem nav_drawer_expand_chevron.xml) 90 do de bao hieu huong ">" (thu gon) hay "v" (dang
     * xoe). */
    private void toggleCloudSyncSubmenu(NavigationView navView, MenuItem cloudSyncItem) {
        mCloudSyncExpanded = !mCloudSyncExpanded;
        applyCloudSyncSubmenuState(navView, cloudSyncItem);
    }

    /** Dua accordion "Cloud Sync" ve dang thu gon (khong doi gi neu da thu gon san). Goi khi
     * Drawer dong lai, xem addDrawerListener trong setupNavigationDrawer(). */
    private void collapseCloudSyncSubmenu(NavigationView navView) {
        if (!mCloudSyncExpanded) return;
        mCloudSyncExpanded = false;
        applyCloudSyncSubmenuState(navView, navView.getMenu().findItem(R.id.nav_cloud_sync));
    }

    private void applyCloudSyncSubmenuState(NavigationView navView, MenuItem cloudSyncItem) {
        Menu menu = navView.getMenu();
        for (int childId : CLOUD_SYNC_CHILD_IDS) {
            MenuItem child = menu.findItem(childId);
            if (child != null) child.setVisible(mCloudSyncExpanded);
        }
        View chevron = cloudSyncItem != null ? cloudSyncItem.getActionView() : null;
        if (chevron != null) {
            chevron.animate().rotation(mCloudSyncExpanded ? 90f : 0f).setDuration(150).start();
        }
    }

    /** Bam vao 1 nut cloud provider vua xoe ra tu Drawer ("Cloud Sync" > Google Drive/OneDrive/
     * Box/Dropbox/WebDAV-FTP): chuyen sang tab Connection (can fragment nay ton tai de mo dialog/
     * OAuth flow), roi tai su dung y het
     * ConnectionFragment#onProviderCardClick() -- dung logic, dung flow OAuth/WebDAV nhu khi bam
     * thang vao the tuong ung trong tab Connection, khong viet lai gi moi. */
    private void openCloudProviderFromDrawer(ProviderCardType type, int iconRes, int titleRes) {
        mBarTabManager.switchTab(tab_connection);
        ConnectionFragment cloudFrag = (ConnectionFragment) tab_connection.getFragment();
        if (cloudFrag != null) {
            cloudFrag.setDisplayMode(ConnectionFragment.MODE_CLOUD_SYNC_ONLY);
            cloudFrag.onProviderCardClick(new ProviderCardItem(type, iconRes, titleRes, false));
        }
        refreshHeaderForCurrentTab();
    }

    // ---- Nut "Tiep tuc doc/nghe" (giong Moon Reader) ------------------------------------------

    /** An/hien theo pref_show_continue_reading_fab, bam vao se tim va mo sach/tien trinh nghe gan
     * day nhat qua ContinueReadingResolver (xem resolveAndOpenLastActive()). */
    private void setupContinueReadingFab() {
        fabContinueReading = findViewById(R.id.fab_continue_reading);
        fabContinueReading.setOnClickListener(v -> resolveAndOpenLastActive());
        refreshContinueReadingFabVisibility();
    }

    /** Doc lai preference moi lan co the vua doi trong Settings (goi tu onStart(), vi nguoi dung
     * chi doi duoc setting nay sau khi roi MainActivity sang SettingsActivity roi quay lai). */
    private void refreshContinueReadingFabVisibility() {
        if (fabContinueReading == null) return;
        // FAB chi hien khi pref bat, panel mini player TTS khong dang hien, VA CollectionFragment
        // khong dang o che do multi-select (thanh pill noi cung chiem day man hinh) -- neu khong
        // se bi nhieu control chong len nhau o goc man hinh (xem
        // GlobalTtsFloatingPanel.onVisibilityChanged va CollectionFragment#updateSelectionUI()).
        boolean show = mSettings.getBoolean(PREF_SHOW_CONTINUE_READING_FAB, true)
                && !ttsPanelVisible && !collectionSelectionModeActive;
        fabContinueReading.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** Chay ContinueReadingResolver tren thread phu (truy van SQLite dong bo, KHONG duoc goi tren
     * main thread de tranh ANR), roi mo dung Reader/phien nghe TTS tren main thread. Dung chung
     * cho ca cu-click-FAB lan tu-dong-mo-khi-khoi-dong. */
    private void resolveAndOpenLastActive() {
        new Thread(() -> {
            ContinueReadingResolver.LastActivity last =
                    ContinueReadingResolver.findMostRecent(getApplicationContext());
            runOnUiThread(() -> openLastActivity(last));
        }).start();
    }

    /** Mo dung Intent cho sach/tien trinh gan day nhat ma ContinueReadingResolver tim duoc:
     * - Neu lan cuoi dang NGHE TTS (va la EPUB, dinh dang duy nhat ho tro nghe): mo thang
     *   EpubReaderActivity o che do "phien nghe" (buildAudioSessionIntent) - tu resume dung cau da
     *   nghe VA tu mo man hinh "Dang phat" (xem EpubReaderActivity#startAudioSessionIfNeeded), giong
     *   het cach TtsChapterListActivity dang mo 1 chuong.
     * - Nguoc lai (dang doc chu, hoac PDF/CBZ): mo ReaderActivity binh thuong - cac Activity con
     *   (EpubReaderActivity/PdfReaderActivity/CbzReaderActivity) TU DONG doc lai last_locator da luu,
     *   khong can truyen them tham so vi tri nao ca. */
    private void openLastActivity(ContinueReadingResolver.LastActivity last) {
        if (last == null) {
            Toast.makeText(this, R.string.toast_no_recent_book, Toast.LENGTH_SHORT).show();
            return;
        }
        File rootDir = StorageUtils.getRootDirectory(this, mSettings);
        File bookFile = new File(rootDir, last.getLpath());
        if (!bookFile.exists()) {
            // Sach vua duoc doc gan day nhat nhung file da bi xoa khoi may (vd. nguoi dung don
            // dep bo nho) - bao loi giong het OpenBookActivity#openBook dang lam voi truong hop
            // tuong tu.
            Toast.makeText(this, R.string.toast_book_not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        String ext = OpenBookActivity.getExtension(bookFile.getName());
        if (last.getMode() == ContinueReadingResolver.Mode.LISTENING
                && "epub".equals(ext) && last.getChapterHref() != null) {
            startActivity(EpubReaderActivity.buildAudioSessionIntent(
                    this, bookFile, last.getUuid(), last.getTitle(), last.getChapterHref()));
            return;
        }
        switch (ext) {
            case "epub":
            case "pdf":
            case "cbz":
                startActivity(ReaderActivity.buildIntent(this, bookFile, last.getUuid(), last.getTitle(), ext));
                break;
            default:
                // Khong the xay ra trong thuc te (chi EPUB/PDF/CBZ moi co last_locator/tts
                // progress duoc luu boi cac Reader Activity), nhung van bao loi thay vi im lang
                // neu du lieu cu/hong kieu nao do vo tinh khop.
                Toast.makeText(this, R.string.toast_book_not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void showCalibreLibraryChooserDialog() {
        String[] options = {
                getString(R.string.nav_calibre_library_opds),
                getString(R.string.nav_calibre_library_content_server)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.nav_calibre_library)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        mBarTabManager.switchTab(tab_opds);
                        refreshHeaderForCurrentTab();
                    } else {
                        // Tai su dung dung Intent/Activity dang mo Content Server qua WebView
                        // hien co (ContentServerBrowserActivity).
                        startActivity(new Intent(this, ContentServerBrowserActivity.class));
                    }
                })
                .show();
    }

    /** Muc "Thoat" moi trong Navigation Drawer (nav_exit) -- dung CalibreService (foreground
     * service dang lang nghe ket noi Wireless Device) roi ket thuc han process, thay vi chi
     * finish() 1 minh MainActivity (se de service/thong bao foreground o lai chay nen). Goi tu
     * setupNavigationDrawer() khi bam nav_exit. */
    private void exitApp() {
        if (calibreServiceIntent != null) {
            stopService(calibreServiceIntent);
        }
        finishAffinity();
        System.exit(0);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // API 33+ requires runtime consent to show notifications (e.g. the "book received"
        // notification from CalibreService#ui_notify). The persistent foreground-service
        // notification itself doesn't need this, so the app keeps working either way if the
        // user declines -- they just won't see book-received notifications.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }

        //Instantiate Main  UI Components
        setContentView(R.layout.tabs_main);
        // app_header.xml (include trong tabs_main.xml) thay the hoan toan ActionBar goc lam
        // thanh tieu de/menu cua tung trang -- an ActionBar de khong bi 2 thanh chong len nhau.
        // Cac fragment van goi setHasOptionsMenu(true)/onCreateOptionsMenu binh thuong (khong
        // sua doi gi ca), chi la ket qua cua no khong con hien thi that su qua ActionBar nay nua:
        // header dung chung tu inflate lai cung 1 file menu XML do vao 1 PopupMenu rieng khi
        // nguoi dung bam nut 3 cham (xem HeaderContentProvider#onHeaderOverflowClick trong tung
        // fragment), nen khong can ActionBar hien thi that su de lam viec do.
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        mBarTabManager = getBarTabManager();
        mTtsFloatingPanel = new net.mrphan.calibre.reader.tts.GlobalTtsFloatingPanel(
                this,
                visible -> {
                    ttsPanelVisible = visible;
                    refreshContinueReadingFabVisibility();
                    // Device Library's multi-select pill (CollectionFragment) floats at the
                    // bottom too and must not overlap this panel -- same pattern as
                    // refreshContinueReadingFabVisibility() above, just forwarded to the
                    // fragment since the pill lives inside its own view tree, not MainActivity's.
                    CollectionFragment collectionFrag = (CollectionFragment) tab_collection.getFragment();
                    if (collectionFrag != null) collectionFrag.onMusicPanelVisibilityChanged(visible);
                });

        //Diaglog Fragment Manager and their dialogs
        mFragmentManager = getSupportFragmentManager();

        mSettings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        tab_connection = mBarTabManager.newTab(ConnectionFragment.TAB_TAG);
        tab_connection.setText(R.string.tab_connection)
                .setIcon(R.drawable.ic_tab_connection)
                .setFragmentClass(ConnectionFragment.class);
        mBarTabManager.addTab(tab_connection);

        tab_collection = mBarTabManager.newTab(CollectionFragment.TAB_TAG);
        tab_collection.setText(R.string.tab_collection)
                .setIcon(R.drawable.ic_tab_collection)
                .setFragmentClass(CollectionFragment.class);
        mBarTabManager.addTab(tab_collection);

        tab_opds = mBarTabManager.newTab(OPDSBrowserFragment.TAB_TAG);
        tab_opds.setText(R.string.tab_library)
                .setIcon(R.drawable.ic_tab_csync)
                .setFragmentClass(OPDSBrowserFragment.class);
        mBarTabManager.addTab(tab_opds);

        mDrawerLayout = findViewById(R.id.drawer_layout);
        setupAppHeader();
        setupNavigationDrawer();
        setupContinueReadingFab();

        // Device Library ("tab_collection") is the default active tab on first launch -- the
        // bottom nav tab order stays Connection / Device Library / Calibre Library, only which
        // tab is active at startup changes. onRestoreInstanceState() (screen rotation) and
        // EXTRA_TAB in onStart() below will override this choice when needed, so calling
        // switchTab here only really matters for a cold start.
        if (savedInstanceState == null) {
            mBarTabManager.switchTab(tab_collection);
        }
        refreshHeaderForCurrentTab();

        //Kickstart Calibre Service
        calibreServiceIntent = new Intent(this, CalibreService.class);
        // FIX: previously plain startService(), but CalibreService.onCreate() calls
        // startForeground() immediately (see createNotificationChannelAndGoForeground()).
        // On Android 12+ a foreground-service start is only guaranteed to be allowed when
        // the service is launched via startForegroundService()/ContextCompat's compat
        // equivalent -- a plain startService() while the app happens to be in a
        // background-restricted state makes the system reject the subsequent
        // startForeground() call with ForegroundServiceStartNotAllowedException, crashing
        // the whole process (see crash log: CalibreService.onCreate -> createNotification
        // ChannelAndGoForeground -> Service.startForeground -> RemoteException). ContextCompat.
        // startForegroundService() calls Context.startForegroundService() on API 26+ (granting
        // the required background-start allowance) and transparently falls back to plain
        // startService() on older APIs where the restriction doesn't apply.
        ContextCompat.startForegroundService(this, calibreServiceIntent);

        mDB = new MetadataDatabaseHelper(getApplicationContext());

        // Tu dong mo sach/tien trinh nghe gan day nhat khi khoi dong nguoi (cold start), NEU
        // nguoi dung da bat pref_auto_resume_last_book. Dieu kien savedInstanceState == null loai
        // tru truong hop xoay man hinh/Activity bi he thong tao lai (khong phai "khoi dong app
        // that su"). Dieu kien !getIntent().hasExtra(EXTRA_TAB) de KHONG cuop lay dieu huong khi
        // MainActivity duoc mo tu 1 deep link co chu dich (vd. tu thong bao/EXTRA_TAB), tranh mau
        // thuan uu tien giua 2 y dinh mo app khac nhau trong cung 1 lan khoi dong.
        if (savedInstanceState == null
                && !getIntent().hasExtra(EXTRA_TAB)
                && mSettings.getBoolean(PREF_AUTO_RESUME_LAST_BOOK, false)) {
            resolveAndOpenLastActive();
        }

        checkForAppUpdate();
    }

    private void checkForAppUpdate() {
        if (!hasInternetConnection()) return;
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(UPDATE_MANIFEST_URL);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestMethod("GET");
                connection.setUseCaches(false);
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) return;
                String body;
                try (InputStream input = connection.getInputStream()) {
                    body = readUtf8(input);
                }
                JSONObject json = new JSONObject(body);
                int latestVersionCode = json.optInt("versionCode", 0);
                if (latestVersionCode <= BuildConfig.VERSION_CODE) return;

                String latestVersionName = json.optString("versionName", "");
                String releaseUrl = json.optString(
                        "downloadUrl",
                        json.optString(
                                "releaseUrl",
                                "https://github.com/nguyenphanvn95/calibre-sync-upgraded/releases/latest"
                        )
                );
                String message = json.optString(
                        "message",
                        getString(
                                R.string.update_available_message,
                                latestVersionName.isEmpty() ? String.valueOf(latestVersionCode) : latestVersionName,
                                BuildConfig.VERSION_NAME
                        )
                );
                runOnUiThread(() -> showUpdateDialog(latestVersionName, message, releaseUrl));
            } catch (Exception ignored) {
                // Update checks must never interrupt app startup.
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "calibre-update-check").start();
    }

    private boolean hasInternetConnection() {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = connectivityManager.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            return capabilities != null &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        //noinspection deprecation
        android.net.NetworkInfo info = connectivityManager.getActiveNetworkInfo();
        //noinspection deprecation
        return info != null && info.isConnected();
    }

    private String readUtf8(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private void showUpdateDialog(String latestVersionName, String message, String releaseUrl) {
        if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
            return;
        }
        String title = latestVersionName == null || latestVersionName.isEmpty()
                ? getString(R.string.update_available_title)
                : getString(R.string.update_available_title) + " " + latestVersionName;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.update_now, (dialog, which) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)));
                    } catch (Exception e) {
                        Toast.makeText(this, releaseUrl, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mDB.close();

    }

    @Override
    public void onStart() {
        super.onStart();
        mActivity.getApplicationContext().bindService(calibreServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
        isBoundToService = true;
        if (getIntent().hasExtra(EXTRA_TAB)) {
            mBarTabManager.switchTabByTag(getIntent().getStringExtra(EXTRA_TAB));
            refreshHeaderForCurrentTab();
        }
        refreshContinueReadingFabVisibility();
        mTtsFloatingPanel.attach();

    }

    @Override
    public void onStop() {
        super.onStop();
        if (isBoundToService && mCalibreService != null) {
            mCalibreService.setListener(null);
            mActivity.getApplicationContext().unbindService(mServiceConnection);
            isBoundToService = false;
        }
        mTtsFloatingPanel.detach();
    }

    @Override
    public void onConnectClick(ConnectionInfo ci) {
        OPDSBrowserFragment frag = ((OPDSBrowserFragment) tab_opds.getFragment());

        if (mCalibreService != null) {
            mCalibreService.connectToServer(ci);
        }
        if (frag != null) {
            frag.loadURL(ci.getContentServerURL(),false);
        }
    }

    @Override
    public void onRetryClick() {
        if (mCalibreService != null)
            mCalibreService.discoverServers();
    }

    @Override
    public void onServerListSelect(Bundle bundle) {
        ConnectServerDialogFragment instCSDF = new ConnectServerDialogFragment();
        instCSDF.setArguments(bundle);
        instCSDF.show(mFragmentManager, "dlg-connect");

    }

    @Override
    public void onManualConnectionClick() {
        showManualConnectionDialog();

    }

    @Override
    public void noServerInPreferences() {
        showManualConnectionDialog();
    }

    @Override
    public void onUnauthorized() {
        showManualConnectionDialog();
    }

    private void showManualConnectionDialog() {
        ConnectServerDialogFragment instCSDF = new ConnectServerDialogFragment();
        instCSDF.show(mFragmentManager, "dlg-connect");
    }

    @Override
    public void onBookLongClick(Bundle bundle) {
        BookInfoDialogFragment instBIDF = new BookInfoDialogFragment();
        instBIDF.setArguments(bundle);
        instBIDF.show(mFragmentManager, "dlg-book-info");

    }

    @Override
    public void confirmDelete(int id) {
        CollectionFragment frag = (CollectionFragment) tab_collection.getFragment();
        if (frag != null)
            frag.deleteBook(id);
    }

    public void onConnectionStateChanged(CalibreService.ConnectionState connectionState) {
        ConnectionFragment frag = (ConnectionFragment) tab_connection.getFragment();
        if (frag != null)
            frag.setConnectionState(connectionState);
    }

    public void onDiscoveryStateChanged(CalibreService.DiscoveryState discoveryState) {
        ConnectionFragment frag = (ConnectionFragment) tab_connection.getFragment();
        if (frag != null) {
            frag.setDiscoveryState(discoveryState);
            switch (discoveryState) {
                case NO_SERVER:
                    if (frag.isResumed() &&
                            mFragmentManager.findFragmentByTag("dlg-connect") == null) {
                        NoServerDialogFragment instNSDF = new NoServerDialogFragment();
                        instNSDF.show(mFragmentManager, "dlg-no-server");
                    }
                    break;
            }
        }
    }

    @Override
    public void onErrorReported(CalibreService.ErrorType e) {
        ConnectionFragment frag = (ConnectionFragment) tab_connection.getFragment();
        if (frag != null)
            frag.setErrorMessage(e);
    }

    public void onBookListChanged() {
        mBarTabManager.switchTab(tab_collection);
        CollectionFragment frag = (CollectionFragment) tab_collection.getFragment();
        if (frag != null)
            frag.refreshBookList();
        refreshHeaderForCurrentTab();

    }

    /** Goi tu CollectionFragment/OPDSBrowserFragment sau khi du lieu cua chinh no thay doi
     * (rescan xong, import sach, tai xong trang OPDS moi, v.v.) de dong "X books" tren header
     * cap nhat theo, ma khong can doi tab. An toan de goi ngay ca khi tab dang mo KHONG phai
     * tab cua fragment goi ham nay -- refreshHeaderForCurrentTab() tu doc lai
     * currentHeaderProvider() nen chi ap dung neu fragment do dang thuc su hien thi. */
    void refreshHeaderIfShowing(Fragment fragment) {
        if (fragment != null && fragment.isVisible()) {
            refreshHeaderForCurrentTab();
        }
    }

    /** Lets CollectionFragment read the floating TTS/music mini player's current
     * visible/height state (net.mrphan.calibre.reader.tts.GlobalTtsFloatingPanel#isPanelVisible /
     * #panelHeightPx) when repositioning its own multi-select pill above it -- see
     * CollectionFragment#repositionSelectionPill(). */
    public net.mrphan.calibre.reader.tts.GlobalTtsFloatingPanel getTtsFloatingPanel() {
        return mTtsFloatingPanel;
    }

    @Override
    public void requestRescan(CollectionFragment.RescanCallback callback) {
        if (mCalibreService == null) {
            callback.onRescanFinished(0);
            return;
        }
        boolean started = mCalibreService.rescanBookFolder(newBooksFound ->
                mActivity.runOnUiThread(() -> callback.onRescanFinished(newBooksFound)));
        if (!started) {
            // Another scan is already running (BookCrawler.isCrawling() == true) -- treat this
            // as a success, the running scan's own result will refresh the UI when it finishes.
            callback.onRescanFinished(0);
        }
    }

    @Override
    public void onBookDeleted() {
        if (mCalibreService != null) mCalibreService.rescanBookFolderMetadataFileOnly();
    }

    @Override
    public void onCollectionSelectionModeChanged(boolean active) {
        collectionSelectionModeActive = active;
        refreshContinueReadingFabVisibility();
    }

}
