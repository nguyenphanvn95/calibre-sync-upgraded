package net.mrphan.calibre;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import androidx.preference.PreferenceManager;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.text.method.ScrollingMovementMethod;
import android.view.*;
import android.widget.*;
import android.widget.AdapterView.OnItemClickListener;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class ConnectionFragment extends Fragment implements ProviderCardAdapter.SubtitleProvider,
        ProviderCardAdapter.OnProviderCardClickListener, ProviderCardAdapter.OnProviderCardLongClickListener,
        AddCloudRemoteDialogFragment.OnCloudRemoteSavedListener,
        AddGDriveOpdsLibraryDialogFragment.OnLibrarySavedListener,
        CloudRemoteInfoBottomSheetFragment.OnCloudRemoteChangedListener,
        HeaderContentProvider {




    public interface ConnectionFragmentListener {
			public void onManualConnectionClick();
			public void onServerListSelect(Bundle bundle);

		}
    public static String TAB_TAG ="tab_connection";
		private static TextView tvDump;
		private static ImageView ivIcon;
		private static ImageView conStatusIcon;
		private static TextView tvWirelessSubtitle; // id constatus -- trạng thái động, KHÁC contitle (giờ là tên tĩnh)
		private static ProgressBar pbProgress;
		private static ListView lvServers;
		private CalibreService mCalibreService;
		private boolean isBoundToService = false;
		private Intent service_intent;
		private ConnectionFragmentListener mListener;
		/** Trạng thái kết nối hiện tại, cache lại để renderWirelessCard() và onClick của row cùng
		 * dùng chung một nguồn sự thật thay vì đọc lại UI. */
		private CalibreService.ConnectionState mConnectionState = CalibreService.ConnectionState.DISCONNECTED;
		/** Lỗi liên quan tới kết nối gần nhất (xem isConnectionRelatedError) -- null nếu không có
		 * lỗi đang hiển thị. Bị xoá khi bắt đầu một lần thử kết nối mới (CONNECTING), KHÔNG bị xoá
		 * tự động khi ConnectionState rơi về DISCONNECTED ngay sau lỗi (xem connectionRunnable
		 * trong CalibreService: ERROR luôn được broadcast trước DISCONNECTED). */
		private CalibreService.ErrorType lastError = null;
		/** True trong khoảng BEGIN_DISCOVERY..DONE_DISCOVERY khi CHƯA tìm thấy server nào -- dùng
		 * để hiện "Đang tìm server..." trước khi có kết quả đầu tiên. */
		private boolean isSearching = false;

		private static ArrayAdapter<ConnectionInfo> connectionInfoArrayAdapter;

		// --- Cloud Libraries (PHẦN 5/6) ---
		// Bố cục mới: 2 danh sách/2 header riêng thay vì 1 danh sách "Cloud Libraries" duy nhất.
		// Xem buildCalibreLibraryItems() / buildCloudSyncItems().
		private RecyclerView rvCalibreLibraryCards;
		private RecyclerView rvCloudSyncCards;
		/** Dung boi applyDisplayMode() de cuon ve dau trang moi khi doi mode. */
		private androidx.core.widget.NestedScrollView nsvRoot;

		// --- Che do hien thi theo tung muc Drawer (Calibre wireless device / Cloud Sync) -----
		// Truoc day trang nay luon hien ca 3 section cung luc (wireless + Calibre Libraries +
		// Cloud sync). Gio moi muc Drawer chi can dung 1 section rieng cua no, cac section con
		// lai bi an di hoan toan -- xem setDisplayMode()/applyDisplayMode().
		public static final int MODE_WIRELESS_ONLY = 0;
		public static final int MODE_CLOUD_SYNC_ONLY = 1;
		private int mDisplayMode = MODE_WIRELESS_ONLY;
		private View cardWirelessDevices;
		private View sectionCalibreLibraries;
		private View sectionCloudSync;
		private ProviderCardAdapter calibreLibraryCardAdapter;
		private ProviderCardAdapter cloudSyncCardAdapter;
		private List<ProviderCardItem> calibreLibraryCardItems;
		private List<ProviderCardItem> cloudSyncCardItems;
		/** Cached result of CloudTokenManager#isSignedIn() for every OAuth-based cloud provider
		 * (Google Drive, OneDrive, Box, Dropbox), refreshed (asynchronously) by refreshProviderCards() and read
		 * synchronously from getSubtitle()/onProviderCardClick() -- isSignedIn() itself is
		 * async/blocking-ish and must never be called directly from RecyclerView bind. */
		private final Map<CloudRemoteType, Boolean> signedInCache = new EnumMap<>(CloudRemoteType.class);

		private ServiceConnection mServiceConnection = new ServiceConnection() {

			@Override
			public void onServiceConnected(ComponentName arg0, IBinder binder) {
				Logger.d("MainActivity: Bound to Calibre Service");
				mCalibreService = ((CalibreService.CalibreBinder) binder).getService();
            mCalibreService.enquireConnectionState();
            connectionInfoArrayAdapter = new ArrayAdapter<ConnectionInfo>(getActivity(),
                    android.R.layout.simple_list_item_1,android.R.id.text1, mCalibreService.getListOfServers());
				lvServers.setAdapter(connectionInfoArrayAdapter);
			}

			@Override
			public void onServiceDisconnected(ComponentName arg0) {
				Logger.d("MainActivity: unBound from Calibre Service");
				mCalibreService = null;
			}

		};

		@Override
		public void onAttach(Activity activity) {
			super.onAttach(activity);
			// Verify that the host activity implements the callback interface
			try {
				mListener = (ConnectionFragmentListener) activity;
			} catch (ClassCastException e) {
				// The activity doesn't implement the interface, throw exception
				throw new ClassCastException(activity.toString()
						+ " must implement ConnectionFragmentListener");
			}
		}

		@Override
		public void onCreate(Bundle savedInstanceState) {
			super.onCreate(savedInstanceState);

			setHasOptionsMenu(true);

			//Service related set up
			service_intent = new Intent(getActivity(),CalibreService.class);
		}

		@Override
		public View onCreateView (LayoutInflater inflater, ViewGroup container,
				Bundle savedInstanceState) {
			View v = inflater.inflate(R.layout.activity_connection, container,false);

			nsvRoot = v.findViewById(R.id.nsvConnectionRoot);

			//Instantiate Connection UI Components
			tvDump = (TextView) v.findViewById(R.id.dump);
			tvDump.setMovementMethod(new ScrollingMovementMethod());
			pbProgress = (ProgressBar) v.findViewById(R.id.progress);
			ivIcon = (ImageView) v.findViewById(R.id.conicon);
			conStatusIcon = (ImageView) v.findViewById(R.id.conStatusIcon);
			tvWirelessSubtitle = (TextView) v.findViewById(R.id.constatus);
			// contitle giờ là tên tĩnh, đặt 1 lần ở đây thay vì set động trong setConnectionState().
			((TextView) v.findViewById(R.id.contitle)).setText(R.string.cloud_card_wireless_devices);

			View wirelessDeviceRow = v.findViewById(R.id.wirelessDeviceRow);
			wirelessDeviceRow.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View view) {
					onWirelessRowClick();
				}
			});

			final View wirelessAdvancedSection = v.findViewById(R.id.wirelessAdvancedSection);
			final TextView toggleWirelessAdvanced = (TextView) v.findViewById(R.id.toggleWirelessAdvanced);
			toggleWirelessAdvanced.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View view) {
					boolean nowVisible = wirelessAdvancedSection.getVisibility() != View.VISIBLE;
					wirelessAdvancedSection.setVisibility(nowVisible ? View.VISIBLE : View.GONE);
					toggleWirelessAdvanced.setText(nowVisible
							? R.string.wireless_hide_details
							: R.string.wireless_show_details);
				}
			});

			lvServers = (ListView) v.findViewById(R.id.serverlist);
			lvServers.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos,
                                    long id) {
                ConnectionInfo selected_cinfo = connectionInfoArrayAdapter.getItem(pos);
                //if (mCalibreService != null && mCalibreService.attemptAutoconnect(selected_cinfo) == false) {
                    Bundle args = new Bundle();
                    args.putString("host", selected_cinfo.getHost());
                    args.putString("wd_port", selected_cinfo.getWirelessDevicePort());
                    args.putString("cs_port", selected_cinfo.getContentServerPort());
                    mListener.onServerListSelect(args);
                //}
            }
        });

			//Set Connection UI Components to default state
			ivIcon.setImageResource(R.drawable.csync2);
			tvWirelessSubtitle.setText(R.string.wireless_status_tap_to_connect);
			pbProgress.setVisibility(View.GONE);
			conStatusIcon.setVisibility(View.GONE);

			// --- Calibre Libraries card list: Calibre Content Server + Libraries on Gdrive ---
			rvCalibreLibraryCards = v.findViewById(R.id.rvCalibreLibraryCards);
			rvCalibreLibraryCards.setLayoutManager(new LinearLayoutManager(getActivity()));
			rvCalibreLibraryCards.setNestedScrollingEnabled(false);
			rvCalibreLibraryCards.addItemDecoration(new DividerItemDecoration(getActivity(), DividerItemDecoration.VERTICAL));
			calibreLibraryCardItems = buildCalibreLibraryItems();
			calibreLibraryCardAdapter = new ProviderCardAdapter(calibreLibraryCardItems, this, this, this);
			rvCalibreLibraryCards.setAdapter(calibreLibraryCardAdapter);

			// --- Cloud sync card list: Google Drive, OneDrive, Box, Nextcloud, WebDAV/FTP/NAS, + rest ---
			rvCloudSyncCards = v.findViewById(R.id.rvCloudSyncCards);
			rvCloudSyncCards.setLayoutManager(new LinearLayoutManager(getActivity()));
			rvCloudSyncCards.setNestedScrollingEnabled(false);
			rvCloudSyncCards.addItemDecoration(new DividerItemDecoration(getActivity(), DividerItemDecoration.VERTICAL));
			cloudSyncCardItems = buildCloudSyncItems();
			cloudSyncCardAdapter = new ProviderCardAdapter(cloudSyncCardItems, this, this, this);
			rvCloudSyncCards.setAdapter(cloudSyncCardAdapter);

			cardWirelessDevices = v.findViewById(R.id.cardWirelessDevices);
			sectionCalibreLibraries = v.findViewById(R.id.sectionCalibreLibraries);
			sectionCloudSync = v.findViewById(R.id.sectionCloudSync);
			applyDisplayMode();

			refreshProviderCards();

			return v;
		}

		/** "Calibre Libraries" section, in order: Calibre Content Server, Libraries on Gdrive. */
		private List<ProviderCardItem> buildCalibreLibraryItems() {
			List<ProviderCardItem> items = new ArrayList<>();
			items.add(new ProviderCardItem(ProviderCardType.CONTENT_SERVER, R.drawable.ic_provider_content_server, R.string.cloud_card_content_server, false));
			items.add(new ProviderCardItem(ProviderCardType.GDRIVE_OPDS_LIBRARY, R.drawable.ic_provider_gdrive_opds_library, R.string.cloud_card_gdrive_opds_library, false));
			return items;
		}

		/** "Cloud sync" section, in order: Google Drive, OneDrive, Box, Dropbox, WebDAV/FTP/NAS,
		 * then the remaining placeholders (pCloud, ownCloud, Local storage) not called out
		 * explicitly by the requested order but kept so no functionality is dropped. Dropbox
		 * takes the slot that used to be occupied by the "coming soon" Nextcloud placeholder --
		 * it is now a fully working OAuth card, same tier as Google Drive/OneDrive/Box. */
		private List<ProviderCardItem> buildCloudSyncItems() {
			List<ProviderCardItem> items = new ArrayList<>();
			items.add(new ProviderCardItem(ProviderCardType.GOOGLE_DRIVE, R.drawable.ic_provider_google_drive, R.string.cloud_type_google_drive, false));
			items.add(new ProviderCardItem(ProviderCardType.ONEDRIVE, R.drawable.ic_provider_onedrive, R.string.cloud_card_onedrive, false));
			items.add(new ProviderCardItem(ProviderCardType.BOX, R.drawable.ic_provider_box, R.string.cloud_card_box, false));
			items.add(new ProviderCardItem(ProviderCardType.DROPBOX, R.drawable.ic_provider_dropbox, R.string.cloud_card_dropbox, false));
			items.add(new ProviderCardItem(ProviderCardType.WEBDAV_FTP, R.drawable.ic_provider_webdav, R.string.cloud_card_webdav_ftp, false));
			items.add(new ProviderCardItem(ProviderCardType.PLACEHOLDER_PCLOUD, R.drawable.ic_provider_pcloud, R.string.cloud_card_pcloud, true));
			items.add(new ProviderCardItem(ProviderCardType.PLACEHOLDER_OWNCLOUD, R.drawable.ic_provider_owncloud, R.string.cloud_card_owncloud, true));
			items.add(new ProviderCardItem(ProviderCardType.PLACEHOLDER_LOCAL_STORAGE, R.drawable.ic_provider_local_storage, R.string.cloud_card_local_storage, true));
			return items;
		}

		// --- ProviderCardAdapter.SubtitleProvider ---
		@Override
		public String getSubtitle(ProviderCardType type) {
			if (getActivity() == null) return null;
			switch (type) {
				case GOOGLE_DRIVE:
					return Boolean.TRUE.equals(signedInCache.get(CloudRemoteType.GOOGLE_DRIVE))
							? getString(R.string.cloud_signed_in) : getString(R.string.cloud_not_signed_in);
				case ONEDRIVE:
					return Boolean.TRUE.equals(signedInCache.get(CloudRemoteType.ONEDRIVE))
							? getString(R.string.cloud_signed_in) : getString(R.string.cloud_not_signed_in);
				case BOX:
					return Boolean.TRUE.equals(signedInCache.get(CloudRemoteType.BOX))
							? getString(R.string.cloud_signed_in) : getString(R.string.cloud_not_signed_in);
				case DROPBOX:
					return Boolean.TRUE.equals(signedInCache.get(CloudRemoteType.DROPBOX))
							? getString(R.string.cloud_signed_in) : getString(R.string.cloud_not_signed_in);
				case GDRIVE_OPDS_LIBRARY: {
					int n = GDriveOpdsLibraryStore.getAll(getActivity()).size();
					return n == 0 ? getString(R.string.cloud_add_remote) : getString(R.string.gdrive_opds_libraries_added, n);
				}
				case CONTENT_SERVER: {
					String savedCsUrl = PreferenceManager.getDefaultSharedPreferences(getActivity()).getString("pref_cs_url", null);
					if (savedCsUrl == null || savedCsUrl.isEmpty()) return getString(R.string.cloud_not_connected);
					try {
						URL url = new URL(savedCsUrl);
						return url.getHost() + ":" + url.getPort();
					} catch (MalformedURLException e) {
						return getString(R.string.cloud_not_connected);
					}
				}
				case WEBDAV_FTP: {
					int n = 0;
					for (CloudRemote r : CloudRemoteStore.getAll(getActivity())) {
						if (r.getType() == CloudRemoteType.WEBDAV || r.getType() == CloudRemoteType.FTP) n++;
					}
					return n == 0 ? getString(R.string.cloud_add_remote) : getString(R.string.cloud_remotes_added, n);
				}
				default:
					return null;
			}
		}

		// --- ProviderCardAdapter.OnProviderCardClickListener ---
		@Override
		public void onProviderCardClick(ProviderCardItem item) {
			if (getActivity() == null) return;
			switch (item.type) {
				case GOOGLE_DRIVE:
					onOAuthCloudCardClick(CloudRemoteType.GOOGLE_DRIVE);
					break;
				case ONEDRIVE:
					onOAuthCloudCardClick(CloudRemoteType.ONEDRIVE);
					break;
				case BOX:
					onOAuthCloudCardClick(CloudRemoteType.BOX);
					break;
				case DROPBOX:
					onOAuthCloudCardClick(CloudRemoteType.DROPBOX);
					break;
				case GDRIVE_OPDS_LIBRARY:
					onGdriveOpdsLibraryCardClick();
					break;
				case CONTENT_SERVER:
					onContentServerCardClick();
					break;
				case WEBDAV_FTP:
					onWebDavFtpCardClick();
					break;
				default:
					showComingSoonDialog(item);
					break;
			}
		}

		// --- ProviderCardAdapter.OnProviderCardLongClickListener ---
		@Override
		public boolean onProviderCardLongClick(ProviderCardItem item) {
			if (getActivity() == null) return false;
			if (item.type != ProviderCardType.GOOGLE_DRIVE && item.type != ProviderCardType.ONEDRIVE
					&& item.type != ProviderCardType.BOX && item.type != ProviderCardType.DROPBOX) {
				return false;
			}
			CloudRemoteType type;
			if (item.type == ProviderCardType.GOOGLE_DRIVE) type = CloudRemoteType.GOOGLE_DRIVE;
			else if (item.type == ProviderCardType.ONEDRIVE) type = CloudRemoteType.ONEDRIVE;
			else if (item.type == ProviderCardType.BOX) type = CloudRemoteType.BOX;
			else type = CloudRemoteType.DROPBOX;
			if (!Boolean.TRUE.equals(signedInCache.get(type))) {
				return false;
			}
			showCloudDisconnectDialog(type);
			return true;
		}

		private void showCloudDisconnectDialog(CloudRemoteType type) {
			int titleRes, messageRes, confirmRes;
			if (type == CloudRemoteType.GOOGLE_DRIVE) {
				titleRes = R.string.gdrive_disconnect_title;
				messageRes = R.string.gdrive_disconnect_message;
				confirmRes = R.string.gdrive_disconnect_confirm;
			} else if (type == CloudRemoteType.ONEDRIVE) {
				titleRes = R.string.onedrive_disconnect_title;
				messageRes = R.string.onedrive_disconnect_message;
				confirmRes = R.string.onedrive_disconnect_confirm;
			} else if (type == CloudRemoteType.BOX) {
				titleRes = R.string.box_disconnect_title;
				messageRes = R.string.box_disconnect_message;
				confirmRes = R.string.box_disconnect_confirm;
			} else {
				titleRes = R.string.dropbox_disconnect_title;
				messageRes = R.string.dropbox_disconnect_message;
				confirmRes = R.string.dropbox_disconnect_confirm;
			}
			new AlertDialog.Builder(getActivity())
					.setTitle(titleRes)
					.setMessage(messageRes)
					.setPositiveButton(confirmRes, (dialog, which) -> {
						CloudTokenManager.getInstance(getActivity(), type).clear();
						if (type == CloudRemoteType.GOOGLE_DRIVE) {
							new GDriveCoverDiskCache(getActivity().getApplicationContext()).clearAll();
						}
						for (CloudRemote r : CloudRemoteStore.getAll(getActivity())) {
							if (r.getType() == type) CloudRemoteSyncPrefs.clear(getActivity(), r.getId());
						}
						refreshProviderCards();
					})
					.setNegativeButton(android.R.string.cancel, null)
					.show();
		}

		private void onOAuthCloudCardClick(CloudRemoteType type) {
			boolean signedIn = Boolean.TRUE.equals(signedInCache.get(type));
			if (!signedIn) {
				AddCloudRemoteDialogFragment.newInstancePreselected(type)
						.show(getChildFragmentManager(), "add_cloud_remote");
				return;
			}
			for (CloudRemote r : CloudRemoteStore.getAll(getActivity())) {
				if (r.getType() == type) {
					// Nâng cấp: trước đây mở THẲNG CloudBrowserActivity. Giờ mở dialog "Thông tin
					// thư viện cloud" trước -- vẫn có nút "Duyệt file" bên trong dialog đó để không
					// mất đường vào CloudBrowserActivity.
					CloudRemoteInfoBottomSheetFragment.newInstance(r.getId())
							.show(getChildFragmentManager(), "cloud_remote_info");
					return;
				}
			}
			// Signed in but no CloudRemote saved yet (shouldn't normally happen) -- fall back to
			// the sign-in dialog so the user can (re)create the saved remote entry.
			AddCloudRemoteDialogFragment.newInstancePreselected(type)
					.show(getChildFragmentManager(), "add_cloud_remote");
		}

		// --- CloudRemoteInfoBottomSheetFragment.OnCloudRemoteChangedListener ---
		@Override
		public void onCloudRemoteChanged() {
			refreshProviderCards();
		}

		private void onGdriveOpdsLibraryCardClick() {
			List<GDriveOpdsLibrary> libraries = GDriveOpdsLibraryStore.getAll(getActivity());
			if (libraries.isEmpty()) {
				AddGDriveOpdsLibraryDialogFragment.newInstance()
						.show(getChildFragmentManager(), "add_gdrive_opds_library");
			} else {
				GDriveOpdsLibraryListBottomSheet.newInstance()
						.show(getChildFragmentManager(), "gdrive_opds_library_list");
			}
		}

		private void onContentServerCardClick() {
			String savedCsUrl = PreferenceManager.getDefaultSharedPreferences(getActivity()).getString("pref_cs_url", null);
			if (savedCsUrl == null || savedCsUrl.isEmpty()) {
				mListener.onManualConnectionClick();
				Toast.makeText(getActivity(), R.string.content_server_not_connected_hint, Toast.LENGTH_LONG).show();
				return;
			}
			startActivity(new Intent(getActivity(), ContentServerBrowserActivity.class));
		}

		private void onWebDavFtpCardClick() {
			List<CloudRemote> remotes = new ArrayList<>();
			for (CloudRemote r : CloudRemoteStore.getAll(getActivity())) {
				if (r.getType() == CloudRemoteType.WEBDAV || r.getType() == CloudRemoteType.FTP) remotes.add(r);
			}
			if (remotes.isEmpty()) {
				AddCloudRemoteDialogFragment.newInstance().show(getChildFragmentManager(), "add_cloud_remote");
			} else {
				CloudRemoteListBottomSheet.newInstance()
						.show(getChildFragmentManager(), "cloud_remote_list");
			}
		}

		private void showComingSoonDialog(ProviderCardItem item) {
			new AlertDialog.Builder(getActivity())
					.setTitle(item.titleRes)
					.setMessage(R.string.cloud_coming_soon_message)
					.setPositiveButton(R.string.cloud_coming_soon_ack, null)
					.show();
		}

		// --- AddCloudRemoteDialogFragment.OnCloudRemoteSavedListener ---
		@Override
		public void onCloudRemoteSaved() {
			refreshProviderCards();
		}

		// --- AddGDriveOpdsLibraryDialogFragment.OnLibrarySavedListener ---
		@Override
		public void onLibrarySaved() {
			refreshProviderCards();
		}

		/** Refreshes the signedInCache for every OAuth-based cloud provider (a fast local check --
		 * see CloudTokenManager#isSignedIn, no network call), then re-binds every card's subtitle
		 * in both the "Calibre Libraries" and "Cloud sync" lists. */
		public void refreshProviderCards() {
			if (getActivity() == null || calibreLibraryCardAdapter == null || cloudSyncCardAdapter == null) return;
			signedInCache.put(CloudRemoteType.GOOGLE_DRIVE,
					CloudTokenManager.getInstance(getActivity(), CloudRemoteType.GOOGLE_DRIVE).isSignedIn());
			signedInCache.put(CloudRemoteType.ONEDRIVE,
					CloudTokenManager.getInstance(getActivity(), CloudRemoteType.ONEDRIVE).isSignedIn());
			signedInCache.put(CloudRemoteType.BOX,
					CloudTokenManager.getInstance(getActivity(), CloudRemoteType.BOX).isSignedIn());
			signedInCache.put(CloudRemoteType.DROPBOX,
					CloudTokenManager.getInstance(getActivity(), CloudRemoteType.DROPBOX).isSignedIn());
			calibreLibraryCardAdapter.notifyDataSetChanged();
			cloudSyncCardAdapter.notifyDataSetChanged();
		}

    /** Nơi duy nhất quyết định icon trái, cột trạng thái phải, và subtitle của card wireless
     * device, dựa trên mConnectionState/lastError/isSearching đã cache. Mọi callback
     * (setConnectionState/setErrorMessage/setDiscoveryState) chỉ cập nhật state rồi gọi hàm
     * này, tránh 3 nơi tự ý sửa UI làm lệch nhau. */
    private void renderWirelessCard() {
        if (ivIcon == null || tvWirelessSubtitle == null || conStatusIcon == null || pbProgress == null) return;

        boolean busyOrConnected = mConnectionState == CalibreService.ConnectionState.CONNECTING
                || mConnectionState == CalibreService.ConnectionState.WAITING_FOR_FOLDERSCAN
                || mConnectionState == CalibreService.ConnectionState.COMMUNICATING
                || mConnectionState == CalibreService.ConnectionState.IDLING;
        ivIcon.setImageResource(busyOrConnected ? R.drawable.csync2_connected : R.drawable.csync2);

        switch (mConnectionState) {
            case CONNECTING:
            case WAITING_FOR_FOLDERSCAN:
                showSpinner();
                tvWirelessSubtitle.setText(R.string.wireless_status_connecting);
                break;
            case COMMUNICATING:
                showSpinner();
                tvWirelessSubtitle.setText(mCalibreService.getCurrentServer().toString());
                break;
            case IDLING:
                showStatusIcon(R.drawable.ic_status_connected, R.string.wireless_status_icon_connected_desc);
                tvWirelessSubtitle.setText(mCalibreService.getCurrentServer().toString());
                break;
            case DISCONNECTED:
            default:
                if (lastError != null) {
                    showStatusIcon(R.drawable.ic_status_error, R.string.wireless_status_icon_error_desc);
                    tvWirelessSubtitle.setText(shortErrorStringRes(lastError));
                } else {
                    hideStatusColumn();
                    ConnectionInfo discovered = latestDiscoveredServer();
                    if (discovered != null) {
                        tvWirelessSubtitle.setText(discovered.toString());
                    } else if (isSearching) {
                        tvWirelessSubtitle.setText(R.string.wireless_status_searching);
                    } else {
                        tvWirelessSubtitle.setText(R.string.wireless_status_tap_to_connect);
                    }
                }
                break;
        }
    }

    private void showSpinner() {
        pbProgress.setVisibility(View.VISIBLE);
        conStatusIcon.setVisibility(View.GONE);
    }

    private void showStatusIcon(int drawableRes, int contentDescRes) {
        pbProgress.setVisibility(View.GONE);
        conStatusIcon.setImageResource(drawableRes);
        conStatusIcon.setContentDescription(getString(contentDescRes));
        conStatusIcon.setVisibility(View.VISIBLE);
    }

    private void hideStatusColumn() {
        pbProgress.setVisibility(View.GONE);
        conStatusIcon.setVisibility(View.GONE);
    }

    /** Server mới nhất tìm thấy qua LAN discovery, dùng để hiện IP ở subtitle khi CHƯA kết nối.
     * null nếu chưa tìm thấy gì (service null hoặc danh sách rỗng). */
    private ConnectionInfo latestDiscoveredServer() {
        if (mCalibreService == null) return null;
        List<ConnectionInfo> servers = mCalibreService.getListOfServers();
        if (servers == null || servers.isEmpty()) return null;
        return servers.get(servers.size() - 1);
    }

    private boolean isConnectionRelatedError(CalibreService.ErrorType e) {
        switch (e) {
            case CANT_CONNECT:
            case WRONG_PASSWORD:
            case CALIBRE_BUSY:
            case SERVER_ERROR:
                return true;
            default:
                // CANT_DOWNLOAD_FILE: lỗi tải sách, xảy ra SAU KHI đã kết nối thành công.
                // ROOT_NO_ACCESS: lỗi thư mục gốc, không liên quan trạng thái kết nối.
                // Cả 2 lỗi này chỉ nên tiếp tục ghi vào log debug (tvDump) như code cũ,
                // TUYỆT ĐỐI không được đổi icon xanh→X đỏ của card khi đang connected.
                return false;
        }
    }

    private int shortErrorStringRes(CalibreService.ErrorType e) {
        switch (e) {
            case CANT_CONNECT: return R.string.wireless_error_short_cant_connect;
            case WRONG_PASSWORD: return R.string.wireless_error_short_wrong_password;
            case CALIBRE_BUSY: return R.string.wireless_error_short_busy;
            case SERVER_ERROR: return R.string.wireless_error_short_server_error;
            default: return R.string.wireless_status_tap_to_connect;
        }
    }

    private void onWirelessRowClick() {
        boolean isBusyOrConnected = mConnectionState == CalibreService.ConnectionState.CONNECTING
                || mConnectionState == CalibreService.ConnectionState.WAITING_FOR_FOLDERSCAN
                || mConnectionState == CalibreService.ConnectionState.COMMUNICATING
                || mConnectionState == CalibreService.ConnectionState.IDLING;
        if (isBusyOrConnected) {
            if (mCalibreService != null) mCalibreService.disconnectFromServer();
        } else if (mListener != null) {
            mListener.onManualConnectionClick();
        }
    }

    public void setConnectionState(CalibreService.ConnectionState connectionState) {
        if (ivIcon == null || tvWirelessSubtitle == null || mCalibreService == null || pbProgress == null) return;
        mConnectionState = connectionState;
        if (connectionState == CalibreService.ConnectionState.CONNECTING) {
            // Lần thử mới -- ngưng hiện lỗi/IP cũ, quay lại spinner sạch.
            lastError = null;
        }
        if (connectionState == CalibreService.ConnectionState.WAITING_FOR_FOLDERSCAN) {
            appendDebugText(R.string.log_wait_for_drive);
        }
        renderWirelessCard();
    }

    public void setErrorMessage(CalibreService.ErrorType e) {
        switch (e) {
            case CALIBRE_BUSY:
                appendDebugText(R.string.connection_calibre_busy);
                break;
            case CANT_CONNECT:
                appendDebugText(R.string.log_unable_to_connect);
                break;
            case CANT_DOWNLOAD_FILE:
                appendDebugText(R.string.log_error_dlfile);
                break;
            case WRONG_PASSWORD:
                appendDebugText(R.string.log_err_wrong_password);
                break;
            case ROOT_NO_ACCESS:
                appendDebugText(R.string.log_error_root_folder_noread);
                break;
            case SERVER_ERROR:
                appendDebugText(R.string.log_error_server_reported);
                break;
        }
        // Xem mục 2.1: CANT_DOWNLOAD_FILE / ROOT_NO_ACCESS không liên quan tới trạng thái card.
        if (isConnectionRelatedError(e)) {
            lastError = e;
            renderWirelessCard();
        }
    }

    public void setDiscoveryState(CalibreService.DiscoveryState discoveryState) {
        switch (discoveryState) {
            case BEGIN_DISCOVERY:
                appendDebugText(R.string.log_disc_working);
                if (pbProgress != null) pbProgress.setVisibility(View.VISIBLE);
                isSearching = true;
                break;
            case DONE_DISCOVERY:
                isSearching = false;
                break;
            case NEW_SERVER:
                if (connectionInfoArrayAdapter != null) {
                    connectionInfoArrayAdapter.notifyDataSetChanged();
                } else if (lvServers != null && mCalibreService != null) {
                    connectionInfoArrayAdapter = new ArrayAdapter<ConnectionInfo>(getActivity(),
                            android.R.layout.simple_list_item_1,android.R.id.text1, mCalibreService.getListOfServers());
                    lvServers.setAdapter(connectionInfoArrayAdapter);
                }
                break;
            case NO_SERVER:
                break;
        }
        // Việc tìm server trên LAN chỉ ảnh hưởng subtitle của card khi CHƯA kết nối -- một
        // NEW_SERVER broadcast trong lúc đang IDLING/COMMUNICATING với 1 server khác không được
        // đè lên dòng "đang kết nối tới X".
        if (mConnectionState == CalibreService.ConnectionState.DISCONNECTED) {
            renderWirelessCard();
        }
    }

    public void appendDebugText(int i) {
        appendDebugText(getString(i));
    }

    public void appendDebugText(String s) {
        if (tvDump != null) tvDump.append(s+"\n");
    }

		@Override
		public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
			super.onCreateOptionsMenu(menu, inflater);
			inflater.inflate(R.menu.connection_fragment, menu);
		}

		@Override
		public boolean onOptionsItemSelected(MenuItem mitem) {
			switch (mitem.getItemId()) {
			case R.id.menu_disconnect:
				if (mCalibreService == null) break;
				mCalibreService.disconnectFromServer();
				break;
			case R.id.menu_rediscover:
				if (mCalibreService == null) break;
            mCalibreService.discoverServers();
				break;
			case R.id.menu_manual_connection:
				mListener.onManualConnectionClick();
				break;
			}	
			return super.onOptionsItemSelected(mitem);
		}

		public void onStart() {
			super.onStart();
			this.getActivity().getApplicationContext().bindService(service_intent, mServiceConnection, Context.BIND_AUTO_CREATE);
			isBoundToService = true;
			// Cloud Libraries: subtitles (Google Drive signed-in state, WebDAV/FTP remote count,
			// Content Server host) can have changed while this tab was backgrounded (e.g. user
			// finished signing in to Google Drive, or added a WebDAV server) -- re-query on every
			// resume, same as CollectionFragment.onStart() re-queries the book DB.
			refreshProviderCards();

		}

		public void onStop() {
			super.onStop();
			if (isBoundToService && mCalibreService != null) {
				this.getActivity().getApplicationContext().unbindService(mServiceConnection);
				isBoundToService = false;
			}
		}

	// ---- HeaderContentProvider (nang cap header gon + Drawer duy nhat) --------------------

	@Override
	public String getHeaderTitle() {
		return getString(mDisplayMode == MODE_CLOUD_SYNC_ONLY
				? R.string.nav_cloud_sync
				: R.string.tab_connection);
	}

	@Override
	public String getHeaderCountLabel() {
		return null; // Trang nay khong co "so sach" de hien o header, giong hanh vi cu.
	}

	@Override
	public boolean supportsHeaderSearch() {
		return false; // connection_fragment.xml chua tung co menu_search.
	}

	@Override
	public void onHeaderSearchQuery(String query) {
		// Khong lam gi -- supportsHeaderSearch() == false nen nut search bi an, ham nay khong
		// bao gio duoc goi tren trang nay.
	}

	@Override
	public void onHeaderRefresh() {
		// Tuong duong menu_rediscover cu: tim lai server tren LAN, kem lam moi luon subtitle
		// cua cac card cloud/Calibre Library (giong onStart() da lam).
		if (mCalibreService != null) mCalibreService.discoverServers();
		refreshProviderCards();
	}

	@Override
	public void onHeaderOverflowClick(View anchorView) {
		androidx.appcompat.widget.PopupMenu popup =
				new androidx.appcompat.widget.PopupMenu(requireContext(), anchorView);
		popup.inflate(R.menu.connection_fragment);
		// PopupMenu chi dung de inflate/giu Menu (icon + 2 group da khai bao trong XML) --
		// khong goi popup.show() nua, vi PopupMenu he thong an icon va khong ve vach chia
		// nhom. HeaderOverflowMenu doc lai dung Menu nay de tu ve popup rieng (xem class do).
		HeaderOverflowMenu overflowMenu = new HeaderOverflowMenu(requireContext(), popup.getMenu());
		overflowMenu.setDestructiveItemIds(R.id.menu_disconnect);
		overflowMenu.setOnMenuItemClickListener(this::onOptionsItemSelected);
		overflowMenu.show(anchorView);
	}

	/** Goi tu MainActivity#setupNavigationDrawer khi nguoi dung bam "Calibre wireless device"
	 * hoac "Cloud Sync" trong Drawer: chi hien dung 1 section tuong ung, an het cac section con
	 * lai (truoc day trang nay luon hien ca 3 section cung luc bat ke mo tu muc Drawer nao). An
	 * toan de goi bat ky luc nao, ke ca truoc khi onCreateView chay xong -- applyDisplayMode() tu
	 * kiem tra view da san sang chua. */
	public void setDisplayMode(int mode) {
		mDisplayMode = mode;
		applyDisplayMode();
	}

	private void applyDisplayMode() {
		if (cardWirelessDevices == null || sectionCalibreLibraries == null || sectionCloudSync == null)
			return; // onCreateView chua chay xong, se duoc goi lai o cuoi onCreateView
		boolean wirelessOnly = mDisplayMode == MODE_WIRELESS_ONLY;
		cardWirelessDevices.setVisibility(wirelessOnly ? View.VISIBLE : View.GONE);
		sectionCalibreLibraries.setVisibility(View.GONE);
		sectionCloudSync.setVisibility(wirelessOnly ? View.GONE : View.VISIBLE);
		if (nsvRoot != null) nsvRoot.post(() -> nsvRoot.scrollTo(0, 0));
	}
}
