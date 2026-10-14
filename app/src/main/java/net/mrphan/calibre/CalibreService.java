package net.mrphan.calibre;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.*;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;
import androidx.preference.PreferenceManager;
import androidx.core.app.NotificationCompat;
import android.webkit.MimeTypeMap;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.ArrayList;


/**
 * @author Seng Jea Lee
 */
@SuppressLint("NewApi")
public class CalibreService extends Service {

    private static final int[] BROADCAST_PORTS = {54982, 48123, 39001, 44044, 59678};
    private static final int BASE_PACKET_LEN = 4096;
    //Some network protocol constants (Mirrored from Calibre)
    /* A few "random" port numbers to use for detecting clients using broadcast
    The clients are expected to broadcast a UDP 'hi there' on all of these
    ports when they attempt to connect. Calibre will respond with the port
    number the client should use. This scheme backs up mdns. And yes, we
    must hope that no other application on the machine is using one of these
    ports in datagram mode.
    If you change the ports here, all clients will also need to change. */

    private static final int PROTOCOL_VERSION = 1;
    private static final String ZEROCONF_CLIENT_STRING = "calibre wireless device client";
    private static final String BROADCAST_STRING = "hello calibre";
    //UDP Packet numbers Format: ZEROCONF_CLIENT_STRING (on HOSTNAME);CONTENT_SERVER_PORT,WIRELESS_DEVICE_PORT
    private static final int CONTENT_SERVER_PORT = 0;
    private static final int WIRELESS_DEVICE_PORT = 1;
    private static final int OPCODE_INDEX = 0;
    private static final int ARGS_INDEX = 1;
    private static final int OP_NOOP = 12;
    private static final int OP_OK = 0;
    //private static final int OP_BOOK_DATA            = 10; Unused because we stream
    //private static final int OP_BOOK_DONE            = 11; Unused because we stream
    private static final int OP_CALIBRE_BUSY = 18;
    private static final int OP_DELETE_BOOK = 13;
    private static final int OP_DISPLAY_MESSAGE = 17;
    private static final int OP_FREE_SPACE = 5;
    private static final int OP_GET_BOOK_FILE_SEGMENT = 14;
    //private static final int OP_GET_BOOK_METADATA    = 15; Unused because we stream
    private static final int OP_GET_BOOK_COUNT = 6;
    private static final int OP_GET_DEVICE_INFORMATION = 3;
    private static final int OP_GET_INITIALIZATION_INFO = 9;
    private static final int OP_SEND_BOOKLISTS = 7;
    private static final int OP_SEND_BOOK = 8;
    private static final int OP_SEND_BOOK_METADATA = 16;
    private static final int OP_SET_CALIBRE_DEVICE_INFO = 1;
    private static final int OP_SET_CALIBRE_DEVICE_NAME = 2;
    private static final int OP_TOTAL_SPACE = 4;
    // Added for Calibre driver.py opcodes 19/20 (client used to have no case for these at all).
    private static final int OP_SET_LIBRARY_INFO = 19;
    private static final int OP_ERROR = 20;
    private static final String NOTIFICATION_CHANNEL_ID = "calibre_sync_service";
    private static final int FOREGROUND_NOTIFICATION_ID = 1;
    private static int MAX_CLIENT_COMM_TIMEOUT = 6000; //Wait at most N mseconds for an answer
    private static String APP_VERSION = "beta";
    private static InetAddress InetAddressBroadcast;
    private static WifiManager mWifiManager;
    private static NotificationManager mNotificationManager;
    private static SharedPreferences mSettings;
    private NotificationCompat.Builder mForegroundNotificationBuilder;
    /** True ngay khi service dat ConnectionState.COMMUNICATING lan dau tien trong phien
     * nay (da bat tay xong voi 1 Calibre wireless device server that, co trao doi JSON)
     * -- dung de phan biet "chua tung ket noi" voi "vua ket noi xong roi rot" khi hien
     * notification nen cho ConnectionState.DISCONNECTED, xem textForConnectionState().
     * Reset ve false moi khi service bi he thong kill va khoi dong lai (onCreate() chay
     * lai) -- dung y nghia "phien service moi thi chua ket noi gi". */
    private volatile boolean mHasEverConnected = false;
    private final int NOTIFY_TAG = 42;
    private final IBinder mBinder = new CalibreBinder();
    private DatagramSocket discovery;
    private Socket connection = new Socket();
    private byte[] connBuf = new byte[BASE_PACKET_LEN];
    private byte[] fileBuf = new byte[BASE_PACKET_LEN];
    private BufferedInputStream inputStream;
    private BufferedOutputStream outputStream;
    private Thread discovery_thread, connection_thread;//, metadata_thread;
    //private ArrayList<JSONObject> newBooks = new ArrayList<JSONObject>();
    private CalibreListener mListener;
    private File rootDirectory = null;//, newsDirectory = null;
    private ArrayList<ConnectionInfo> listOfServers = new ArrayList<ConnectionInfo>();
    private ConnectionInfo currentServer;
    private MetadataDatabaseHelper mDB;
    private boolean restartDiscovery = false;
    private boolean enable_for_emulator = false;
    private boolean isInDebugMode = false;
    private BookCrawler mBookCrawler;
    private OnSharedPreferenceChangeListener mOSPCListener = new OnSharedPreferenceChangeListener() {

        @Override
        public void onSharedPreferenceChanged(
                SharedPreferences sp, String key) {
            Logger.d("OnSharedPreferenceChangeListener called for " + key);
            if (key.equals(StorageUtils.PREF_ROOT_DIR_PATH)) {
                try {
                    setDirectories();
                    if (mBookCrawler.scanFolder(rootDirectory)) {
                        restartConnectionThread();
                    }

                } catch (Exception e) {
                    logAndDie(e);
                }
            } else if (key.equals("pref_debug")) {
                if (rootDirectory != null && (sp.getBoolean(key, false) || isInDebugMode)) {
                    Logger.set(new File(rootDirectory, "calibre-debug.log"));
                } else {
                    Logger.set(null);
                }
            } else if (key.equals("pref_filetypes")) {
                // Was comparing against "pref_file_types" (typo), which meant toggling any
                // checkbox in Settings > Accepted Filetypes never actually took effect until the
                // service was killed and restarted.
                //
                // mBookCrawler can still be null here: getAcceptedExtensionsArray()'s
                // ClassCastException recovery path (stale non-Set "pref_filetypes" value, e.g.
                // restored via Android Auto Backup) calls mSettings.edit().remove(...).apply(),
                // and SharedPreferencesImpl$EditorImpl#apply() notifies this listener
                // *synchronously* on the calling thread. If that first getAcceptedExtensionsArray()
                // call happens while onCreate() is still evaluating the BookCrawler constructor
                // arguments, mBookCrawler hasn't been assigned yet, so calling it here would NPE
                // and crash the service on every launch. Skip in that case -- the constructor call
                // in onCreate() will pick up the (now-cleaned) extensions right after anyway.
                if (mBookCrawler != null) {
                    mBookCrawler.setAcceptedExtensions(getAcceptedExtensionsArray());
                }
            }
        }
    };

    private Runnable discoveryRunnable = new Runnable() {
        public void run() {
            ConnectionInfo discoveredCI;

            if (enable_for_emulator) {
                try {
                    broadcastServer(new ConnectionInfo("10.0.2.2", 9095, 8080));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                for (int i = 0; i < BROADCAST_PORTS.length; i++) {
                    if (restartDiscovery || i == 0) {
                        i = 0;
                        restartDiscovery = false;
                        try {
                            InetAddressBroadcast = getBroadcastAddressForNetwork(mWifiNetwork);
                            if (InetAddressBroadcast == null) {
                                Logger.d("discoverServers: LinkProperties broadcast lookup unavailable, falling back to WifiManager DhcpInfo");
                                InetAddressBroadcast = getBroadcastAddress(mWifiManager.getDhcpInfo());
                            }
                        } catch (Exception e) {
                            logAndDie(e);
                        }
                        broadcastServer(null);
                        broadcastDiscoveryStateChange(DiscoveryState.BEGIN_DISCOVERY);
                    }
                    try {
                        Logger.d("InetAddressBroadcast " + InetAddressBroadcast.toString() + ":" + Integer.toString(BROADCAST_PORTS[i]));
                        discovery = new DatagramSocket();
                        if (mWifiNetwork != null) {
                            try {
                                mWifiNetwork.bindSocket(discovery);
                            } catch (IOException bindEx) {
                                Logger.d("discoverServers: could not bind socket to Wi-Fi network: " + bindEx);
                            }
                        }
                        discovery.setSoTimeout(MAX_CLIENT_COMM_TIMEOUT);
                        discovery.setBroadcast(true);
                        byte[] discBuf = new byte[BASE_PACKET_LEN];
                        DatagramPacket recvPacket = new DatagramPacket(discBuf, discBuf.length);
                        DatagramPacket sendPacket = new DatagramPacket(BROADCAST_STRING.getBytes(), BROADCAST_STRING.length(), InetAddressBroadcast, BROADCAST_PORTS[i]);
                        discovery.send(sendPacket);
                        while (true) {
                            if (restartDiscovery) break;
                            discovery.receive(recvPacket);
                            String tmp = new String(discBuf, 0, recvPacket.getLength());
                            if (tmp.startsWith(ZEROCONF_CLIENT_STRING)) {
                                String ports[] = tmp.split(";")[1].split(",");
                                discoveredCI = new ConnectionInfo(recvPacket.getAddress().getHostAddress(), Integer.parseInt(ports[WIRELESS_DEVICE_PORT]), Integer.parseInt(ports[CONTENT_SERVER_PORT]));
                                broadcastServer(discoveredCI);
                            }
                        }
                    } catch (SocketTimeoutException e) {
                        Logger.d("discoverServers: no reply on port " + BROADCAST_PORTS[i] + " (timeout, normal if nothing answered yet)");
                    } catch (SocketException e) {
                        Logger.d("discoverServers: socket closed/reset on port " + BROADCAST_PORTS[i] + ": " + e.getMessage());
                    } catch (Throwable e) {
                        Logger.d("discoverServers: unexpected error on port " + BROADCAST_PORTS[i] + ": " + e);
                        Logger.e(e);
                    } finally {
                        // discovery is only assigned once "new DatagramSocket()" above succeeds,
                        // so it can still be null here if that line itself threw. Closing a
                        // socket is best-effort cleanup, not something worth killing the whole
                        // Service over, so just null-check and swallow close failures.
                        if (discovery != null) {
                            try {
                                discovery.close();  //Try until there are no more servers replying then attempt to close socket.
                            } catch (Exception e) {
                                Logger.d("discoverServers: error closing socket: " + e);
                            }
                        }

                    }

                }
            }
            broadcastDiscoveryStateChange(DiscoveryState.DONE_DISCOVERY);
        }
    };
    private Runnable connectionRunnable = new Runnable() {
        public void run() {
            StringBuilder incoming;
            if (currentServer == null) {
                return;
            }
            if (rootDirectory == null || !rootDirectory.canRead()) {
                broadcastError(ErrorType.ROOT_NO_ACCESS);
                return;
            }
            if (mBookCrawler.isCrawling()) {
                broadcastConnectionStateChange(ConnectionState.WAITING_FOR_FOLDERSCAN);
                mBookCrawler.waitForScan();
            }
            try {
                broadcastConnectionStateChange(ConnectionState.CONNECTING);
                Logger.d("cRunnable: " + currentServer.toString());
                currentServer.resolveAddresses();
                connection = new Socket();
                connection.connect(currentServer.getWirelessDeviceSocket(), MAX_CLIENT_COMM_TIMEOUT);
                inputStream = new BufferedInputStream(connection.getInputStream());
                outputStream = new BufferedOutputStream(connection.getOutputStream());
                SharedPreferences.Editor editor = mSettings.edit();
                editor.putInt("pref_networkid", mWifiManager.getConnectionInfo().getNetworkId());
                editor.commit();

            } catch (Exception e) {
                broadcastError(ErrorType.CANT_CONNECT);
                Logger.e(e);
                disconnectFromServer();
                return;
            }
            broadcastConnectionStateChange(ConnectionState.COMMUNICATING);
            while (!connection.isClosed() && connection.isConnected()) {
                incoming = read_from_server();
                if (incoming != null) {
                    process_json_from_server(incoming);
                }
            }
            broadcastConnectionStateChange(ConnectionState.DISCONNECTED);
        }
    };
    private Handler serviceHandler = new Handler() {
        public void handleMessage(Message msg) {
            if (mListener == null) {
                Logger.d("No mListener Found!");
                return;
            }
            if (msg.what == HandlerMessageType.SERVER.id()) {
                if (msg.obj != null) {
                    listOfServers.add((ConnectionInfo) msg.obj);
                } else {
                    listOfServers.clear();
                }
                mListener.onDiscoveryStateChanged(DiscoveryState.NEW_SERVER);

            } else if (msg.what == HandlerMessageType.CONNECTION.id()) {
                mListener.onConnectionStateChanged((ConnectionState) msg.obj);

            } else if (msg.what == HandlerMessageType.DISCOVERY.id()) {
                mListener.onDiscoveryStateChanged((DiscoveryState) msg.obj);
                if ((DiscoveryState) msg.obj == DiscoveryState.DONE_DISCOVERY &&
                        listOfServers.isEmpty() && !connectionThreadIsRunning()) {
                    mListener.onDiscoveryStateChanged(DiscoveryState.NO_SERVER);
                }

            } else if (msg.what == HandlerMessageType.ERROR.id()) {
                mListener.onErrorReported((ErrorType) msg.obj);
            }
        }
    };

    private static String toHex(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length * 2);
        for (byte b : a) {
            sb.append(Character.forDigit((b & 0xf0) >> 4, 16));
            sb.append(Character.forDigit(b & 0x0f, 16));
        }
        return sb.toString();
    }

    private static InetAddress getBroadcastAddress(DhcpInfo ip) throws UnknownHostException {
        int broadcast = (ip.ipAddress & ip.netmask) | ~ip.netmask;
        byte[] quads = new byte[4];
        for (int k = 0; k < 4; k++)
            quads[k] = (byte) ((broadcast >> k * 8) & 0xFF);
        return InetAddress.getByAddress(quads);
    }

    /**
     * Preferred broadcast-address lookup: reads the real IPv4 prefix length for the given
     * Network out of LinkProperties. Unlike WifiManager.getDhcpInfo() (deprecated API 31+, and
     * observed in the field to return netmask 0 -- collapsing this calculation to the useless
     * 255.255.255.255 -- on at least one real device/Android build), this works off the network
     * the OS itself resolved, so it's correct even when the device isn't using classic DHCP.
     * Returns null if it can't be determined (no LinkProperties / no IPv4 address yet), in which
     * case the caller should fall back to getBroadcastAddress(DhcpInfo).
     */
    private InetAddress getBroadcastAddressForNetwork(Network network) {
        if (network == null || mConnectivityManager == null) return null;
        try {
            android.net.LinkProperties lp = mConnectivityManager.getLinkProperties(network);
            if (lp == null) return null;
            for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                InetAddress addr = la.getAddress();
                if (!(addr instanceof java.net.Inet4Address)) continue;
                int prefixLength = la.getPrefixLength();
                int mask = prefixLength == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefixLength));
                byte[] addrBytes = addr.getAddress();
                int ipInt = ((addrBytes[0] & 0xFF) << 24) | ((addrBytes[1] & 0xFF) << 16)
                        | ((addrBytes[2] & 0xFF) << 8) | (addrBytes[3] & 0xFF);
                int broadcast = (ipInt & mask) | ~mask;
                byte[] quads = new byte[4];
                for (int k = 0; k < 4; k++)
                    quads[k] = (byte) ((broadcast >> (24 - k * 8)) & 0xFF);
                return InetAddress.getByAddress(quads);
            }
        } catch (Exception e) {
            Logger.d("getBroadcastAddressForNetwork failed: " + e);
        }
        return null;
    }

    private double getTotalSpace() {
        return (double) rootDirectory.getTotalSpace();
    }

    private double getFreeSpace() {
        return (double) rootDirectory.getFreeSpace();
    }

    /**
     * Storage note (see README "Known issues"): books used to be confined to the app-specific
     * external files directory (Android/data/net.mrphan.calibre/files/eBooks), which needed no
     * runtime permission but wasn't user-browsable and was wiped on uninstall. The book folder
     * is now user-choosable (see Settings > Book Folder / SettingsActivity's SAF folder picker),
     * defaulting to eBooks/ next to Android/ on the device's primary storage -- see
     * StorageUtils#getRootDirectory. Reading/writing a folder outside the app-specific dir
     * requires MANAGE_EXTERNAL_STORAGE ("All files access", API 30+) or the legacy
     * WRITE_EXTERNAL_STORAGE permission below API 30 (see AndroidManifest.xml).
     */
    private synchronized void setDirectories() {
        File oldRoot = rootDirectory;
        rootDirectory = StorageUtils.getRootDirectory(this, mSettings);

        if (oldRoot != null && !oldRoot.equals(rootDirectory) && oldRoot.exists() && oldRoot.renameTo(rootDirectory)) {
            Toast.makeText(getApplicationContext(), R.string.toast_root_folder_renamed, Toast.LENGTH_SHORT).show();
        } else if (!rootDirectory.exists()) {
            rootDirectory.mkdirs();
            Toast.makeText(getApplicationContext(), R.string.toast_root_folder_changed, Toast.LENGTH_SHORT).show();
        }
        if (!rootDirectory.canRead()) {
            rootDirectory = null;
        }
        if (rootDirectory != null && (mSettings.getBoolean("pref_debug", false) || isInDebugMode)) {
            Logger.set(new File(rootDirectory, "calibre-debug.log"));
        }
    }

    /** @return the currently accepted sync/scan file extensions (no leading dot), from the
     * Settings > Accepted Filetypes checkboxes, falling back to the same default selection
     * shown there (see res/values/arrays.xml pref_filetypes_default) if nothing's been saved
     * yet. Instance-method wrapper kept for existing call sites inside this service; the actual
     * logic lives in the static overload below so Cloud Libraries code (CloudBrowserActivity,
     * ContentServerBrowserActivity) can reuse the exact same accepted-extensions list without
     * duplicating it or needing a bound CalibreService instance. */
    private String[] getAcceptedExtensionsArray() {
        return getAcceptedExtensionsArray(this);
    }

    /** Static, Context-only version of the above -- see its Javadoc. Safe to call from any
     * Activity/Fragment that isn't bound to CalibreService (e.g. CloudBrowserActivity). */
    static String[] getAcceptedExtensionsArray(Context context) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        java.util.Set<String> defaultSet = new java.util.HashSet<>(
                java.util.Arrays.asList(context.getResources().getStringArray(R.array.pref_filetypes_default)));
        java.util.Set<String> exts;
        try {
            exts = settings.getStringSet("pref_filetypes", defaultSet);
        } catch (ClassCastException e) {
            // "pref_filetypes" is somehow stored as something other than a Set<String> --
            // e.g. a pre-2.0 CSV-string value restored onto a fresh install via Android
            // Auto Backup (see AndroidManifest.xml android:allowBackup). Discard the stale
            // value so the MultiSelectListPreference can persist a proper Set the next time
            // Settings is saved, and fall back to the defaults for now instead of crashing
            // the service on every launch.
            Logger.e(e);
            settings.edit().remove("pref_filetypes").apply();
            exts = defaultSet;
        }
        return exts.toArray(new String[0]);
    }

    private ConnectivityManager mConnectivityManager;
    private ConnectivityManager.NetworkCallback mNetworkCallback;
    // The currently-connected Wi-Fi Network, if any. Kept so discoveryRunnable can (a) bind its
    // DatagramSocket to this specific network -- otherwise, on a phone with both Wi-Fi and mobile
    // data active at once, Android may route the broadcast out over cellular instead of Wi-Fi,
    // which was silently breaking discovery even though "InetAddressBroadcast" logged a value --
    // and (b) compute the real subnet broadcast address from LinkProperties instead of the
    // deprecated/unreliable WifiManager.getDhcpInfo() (which was returning netmask 0 -- i.e. a
    // degenerate 255.255.255.255 broadcast address -- on at least one real device, see README).
    private volatile Network mWifiNetwork;

    @Override
    public void onCreate() {
        super.onCreate();
        //get calibre metadata

        Logger.d("onCreate");
        mWifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mSettings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        mSettings.registerOnSharedPreferenceChangeListener(mOSPCListener);
        setDirectories();
        mDB = new MetadataDatabaseHelper(this);
        mBookCrawler = new BookCrawler(mDB, getAcceptedExtensionsArray());


        try {
            APP_VERSION = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            APP_VERSION = "error";
        }

        createNotificationChannelAndGoForeground();
        registerNetworkCallback();
    }

    /**
     * Android 8+ kills long-running background Services that hold a TCP connection + UDP
     * listener unless they run in the foreground with a persistent (low-importance)
     * notification. This replaces the original "plain Service, keep hoping the system doesn't
     * kill it" behaviour.
     */
    private void createNotificationChannelAndGoForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.service_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.service_name));
            mNotificationManager.createNotificationChannel(channel);
        }
        mForegroundNotificationBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.status_starting))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        Notification notification = mForegroundNotificationBuilder.build();
        // POST_NOTIFICATIONS (API 33+) is requested by MainActivity; if the user denies it the
        // service still runs, it just won't be able to show book-received notifications later
        // (startForeground itself does not require that runtime permission on API 33, only
        // NotificationManager.notify() for *other* notifications does).
        //
        // FIX: startForeground() can still throw ForegroundServiceStartNotAllowedException on
        // Android 12+ even after switching MainActivity to ContextCompat.startForegroundService()
        // (see that fix's comment) -- several other call sites (ContentServerBrowserActivity,
        // CloudBrowserActivity, ConnectionFragment, GDriveLibraryJsBridge) create this Service
        // via plain bindService(..., BIND_AUTO_CREATE), which does NOT grant the
        // background-foreground-service-start allowance, so onCreate()->this method can still
        // race with the app not yet being in a visible/exempt state. Previously this uncaught
        // RemoteServiceException crashed the whole process (see crash log). Catching it here and
        // stopping this Service cleanly instead means the user sees "sync unavailable right now"
        // behaviour at worst, rather than the app crashing outright; the next bind/start call
        // (e.g. reopening the screen) retries from a fresh, normally-valid state.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Logger.e(e);
            stopSelf();
        }
    }

    /**
     * Android 14+ (API 34) enforces a ~6-hour execution limit per rolling 24h window for
     * "dataSync"-type foreground services (see FOREGROUND_SERVICE_TYPE_DATA_SYNC used above).
     * When the limit is hit the system calls this callback to give the Service a chance to stop
     * itself gracefully; if it doesn't stop promptly the system kills the whole process with an
     * uncatchable ForegroundServiceDidNotStopInTimeException (see crash log). This Service is
     * designed to run indefinitely (Wi-Fi discovery + Content Server listener), so previously
     * nothing ever called stopForeground()/stopSelf() in response to this, which is exactly what
     * produced that crash. Stopping here trades a hard crash for a clean shutdown -- the
     * Service restarts (regaining a fresh 6h budget) next time the user opens a screen that
     * calls ContextCompat.startForegroundService()/bindService() for it.
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        Logger.d("onTimeout: dataSync foreground-service execution limit reached, stopping gracefully");
        stopForeground(true);
        stopSelf();
    }

    /**
     * Replaces the original manifest-declared ServiceReceiver, which listened for the implicit
     * broadcast android.net.wifi.STATE_CHANGE. Implicit broadcast registration in the manifest
     * for that action has been restricted since Android 7/8, so this is now a dynamically
     * registered ConnectivityManager.NetworkCallback, which is also the currently-recommended
     * way to detect Wi-Fi connectivity changes and is not deprecated like
     * WifiManager.getConnectionInfo()/getDhcpInfo() (deprecated API 31+).
     */
    private void registerNetworkCallback() {
        mConnectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (mConnectivityManager == null) return;
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        mNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                mWifiNetwork = network;
                // Same opt-in as onStartCommand() -- see pref_auto_discover_server.
                if (mSettings.getBoolean("pref_auto_discover_server", false)) {
                    Logger.d("NetworkCallback: Wi-Fi available, restarting discovery");
                    discoverServers();
                } else {
                    Logger.d("NetworkCallback: Wi-Fi available, auto-discover is off, skipping");
                }
                connectWithSavedPreferences();
            }

            @Override
            public void onLost(Network network) {
                Logger.d("NetworkCallback: Wi-Fi lost");
                if (network.equals(mWifiNetwork)) mWifiNetwork = null;
                disconnectFromServer();
            }
        };
        try {
            mConnectivityManager.registerNetworkCallback(request, mNetworkCallback);
        } catch (Exception e) {
            Logger.e(e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Logger.d("onBind");
        return mBinder;
    }

    public File getRootDirectory() {
        return rootDirectory;
    }

    public CalibreListener getListener() {
        return mListener;
    }

    /**
     * Sets the CalibreListener that will be used by the Service for callbacks
     *
     * @param listener
     */
    public void setListener(CalibreListener listener) {
        mListener = listener;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.d("onStartCommand at " + System.currentTimeMillis());
        // Auto-discovery on service start is now opt-in (pref_auto_discover_server, default off).
        // When off, discovery is only triggered manually -- see MainActivity#setupNavigationDrawer
        // (nav_calibre_device) and ConnectionFragment's existing menu_rediscover/onHeaderRefresh.
        if (mSettings.getBoolean("pref_auto_discover_server", false)) {
            discoverServers();
        }
        mBookCrawler.scanFolder(rootDirectory);
        connectWithSavedPreferences();
        return START_STICKY;
    }

    /** Triggers a manual full rescan of the book folder ("Show books" button in Device
     * Library). Returns false if a scan is already in progress -- BookCrawler.scanFolder()
     * already guards against that itself via BookCrawler.isCrawling(). */
    public boolean rescanBookFolder(BookCrawler.ScanListener listener) {
        if (mBookCrawler == null || rootDirectory == null) return false;
        mBookCrawler.setScanListener(listener);
        return mBookCrawler.scanFolder(rootDirectory);
    }

    /** Re-syncs metadata.calibre from the current SQLite data only, without rescanning the whole
     * disk -- used after a book is deleted in CollectionFragment (SQLite is already correct,
     * the on-disk file just needs to reflect it). */
    public void rescanBookFolderMetadataFileOnly() {
        if (mBookCrawler != null) mBookCrawler.writeMetadataFile();
    }

//    private boolean metadataThreadIsRunning() {
//        return (metadata_thread != null) && metadata_thread.isAlive();
//    }

    private boolean discoveryThreadIsRunning() {
        return (discovery_thread != null) && discovery_thread.isAlive();
    }

    private boolean connectionThreadIsRunning() {
        return (connection_thread != null) && connection_thread.isAlive();
    }

    private boolean isConnected() {
        return connectionThreadIsRunning() && !connection.isClosed();
    }

    public void discoverServers() {
        try {
            if (discoveryThreadIsRunning()) {
                Logger.d("discoverServers: reset");
                restartDiscovery = true;
                // discovery (the DatagramSocket) is only assigned inside discoveryRunnable.run(),
                // so it can still be null here if this call races with the thread just after it
                // started (e.g. NetworkCallback.onAvailable firing right after onStartCommand).
                // Closing is just a nudge to unblock the blocking receive() call below, so it's
                // safe to skip if the socket isn't up yet -- the restartDiscovery flag it will
                // still be picked up on the next loop iteration.
                if (discovery != null) {
                    discovery.close();
                }

            } else {
                Logger.d("discoverServers: new");
                discovery_thread = new Thread(discoveryRunnable);
                discovery_thread.start();
            }
        } catch (Exception e) {
            logAndDie(e);
        }
    }

    private boolean connectWithSavedPreferences() {
        String wdAddress = mSettings.getString("pref_wd_address", "");
        Logger.d("connectWithSavedPreferences");
        if (!mSettings.getBoolean("pref_autocon", false)) return false;
        else if (mSettings.getInt("pref_networkid", 0) != mWifiManager.getConnectionInfo().getNetworkId()) return false;
        else if (connectionThreadIsRunning()) return true;
        else {
            try {
                String[] wd_array = wdAddress.split(":");
                URL cs_url = new URL(mSettings.getString("pref_cs_url", ""));
                ConnectionInfo ci = new ConnectionInfo(wd_array[0],
                        Integer.parseInt(wd_array[1]),
                        cs_url.getPort(),
                        mSettings.getString("pref_password", ""));
                ci.setContentServerScheme(cs_url.getProtocol());
                connectToServer(ci);
            } catch (Exception e) {
                Logger.e(e);
                currentServer = null;
                return false;
            }
            return true;
        }
    }
    private void restartConnectionThread() {
        connectToServer(null);
    }
    public synchronized void connectToServer(ConnectionInfo ci) {
        Logger.d("connectToServer: " + (ci != null ? ci.toString() : "(restart)"));
        disconnectFromServer();
        try {
            if (isConnected()) {
                connection_thread.join();
            }
        } catch (Exception e) {
            logAndDie(e);
        }
        if (ci != null) currentServer = ci;
        connection_thread = new Thread(connectionRunnable);
        connection_thread.start();
    }

    public void onDestroy() {
        Logger.d("onDestroy:");
        if (mConnectivityManager != null && mNetworkCallback != null) {
            try {
                mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
            } catch (Exception e) {
                Logger.d("NetworkCallback already unregistered");
            }
        }
        disconnectFromServer();
        if (isInDebugMode) {
            Logger.d("Execution isInDebugMode");
            SharedPreferences.Editor editor = mSettings.edit();
            editor.clear();
            editor.commit();
            mDB.emptyAll();
        }
        try {
            mDB.close();
        } catch (Throwable e) {
            Logger.d("Unclean close!");
        }


    }

    public void disconnectFromServer() {
        try {
            Logger.d("disconnectFromServer");
            connection.close();
            broadcastConnectionStateChange(ConnectionState.DISCONNECTED);
        } catch (Exception e) {
            broadcastConnectionStateChange(ConnectionState.IDLING);
        }

    }

    /**
     * Parses an input JSON String and sends a corresponding JSONArray reply if need be.
     *
     * @param input
     */
    private synchronized void process_json_from_server(StringBuilder input) {
        JSONArray data;
        JSONObject args;
        try {
            data = new JSONArray(input.toString());
            int opcode = data.getInt(OPCODE_INDEX);
            args = data.getJSONObject(ARGS_INDEX);
            broadcastConnectionStateChange(ConnectionState.COMMUNICATING);
            switch (opcode) {
                case OP_CALIBRE_BUSY:
                    broadcastError(ErrorType.CALIBRE_BUSY);
                    disconnectFromServer();

                    break;
                case OP_GET_INITIALIZATION_INFO:
                    currentServer.setLibraryName(args.getString("currentLibraryName"));
                    send_to_server(OP_OK, initialisation_reply(args));

                    break;
                case OP_GET_DEVICE_INFORMATION:
                    send_to_server(OP_OK, device_information_reply());

                    break;
                case OP_SET_CALIBRE_DEVICE_INFO:
                    mBookCrawler.setDriveInfo(args);
                    send_to_server(OP_OK, null);

                    break;
                case OP_SET_CALIBRE_DEVICE_NAME:
                    mBookCrawler.getDriveInfo().put("device_name", args.getString("name"));

                    break;
                case OP_FREE_SPACE:
                    send_to_server(OP_OK, new JSONObject().put("free_space_on_device", getFreeSpace()));

                    break;
                case OP_TOTAL_SPACE:
                    send_to_server(OP_OK, new JSONObject().put("total_space_on_device", getTotalSpace()));

                    break;
                case OP_GET_BOOK_COUNT:
                    stream_meta_to_servers();

                    break;
                case OP_SEND_BOOKLISTS:

                    break;
                case OP_SEND_BOOK_METADATA:
                    mDB.insertJSON(args.getJSONObject("data"));
                    mBookCrawler.writeMetadataFile(); // sync the on-disk file right away

                    break;
                case OP_NOOP:
                    send_to_server(OP_OK, null);

                    break;
                case OP_SEND_BOOK:
                    File fn = new File(rootDirectory, args.getString("lpath"));
                    Boolean update = false;
                    Cursor cs = mDB.getByUuid(new String[]{"_id", "lpath"}, args.getJSONObject("metadata").getString("uuid"));
                    if (cs.moveToFirst() && !cs.isNull(1) && mDB.deleteBook(cs.getInt(0))) {
                        new File(rootDirectory, cs.getString(1)).delete();
                        update = true;
                    }
                    cs.close();
                    download_file(args.getInt("length"), fn);
                    byte[] thumbnail = mDB.insertJSON(args.getJSONObject("metadata"));
                    mBookCrawler.writeMetadataFile(); // sync the on-disk file right away
                    if (args.getInt("totalBooks") == 1 && args.getInt("thisBook") == 0) {
                        ui_notify(args.getString("lpath"), fn, generateBitmap(thumbnail), update,
                                args.getJSONObject("metadata").getString("uuid"));
                        //broadcastDiscoveryStateChange(UIStatus.BOOKS_CHANGED, null);
                    } else {
                        Logger.d(String.format("Downloading Book %d of %d", args.getInt("thisBook") + 1, args.getInt("totalBooks")));
                    }

                    break;
                case OP_GET_BOOK_FILE_SEGMENT:
                    File f = new File(rootDirectory, args.getString("lpath"));
                    boolean isValidFile = f.isFile() && f.exists();
                    args = new JSONObject();
                    args.put("willStreamBinary", true);
                    if (isValidFile) {
                        args.put("fileLength", f.length());
                    } else {
                        args.put("fileLength", 0);
                    }
                    send_to_server(OP_OK, args);
                    if (isValidFile) {
                        upload_file(f);
                    }

                    break;
                case OP_DELETE_BOOK:
                    delete_files(args.getJSONArray("lpaths"));
                    mNotificationManager.cancel(args.getJSONArray("lpaths").getString(0), NOTIFY_TAG);
                    break;

                case OP_DISPLAY_MESSAGE:
                    broadcastError(ErrorType.WRONG_PASSWORD);
                    SharedPreferences.Editor editor = mSettings.edit();
                    editor.remove("pref_wd_port");
                    editor.remove("pref_password");
                    editor.remove("pref_autocon");
                    editor.remove("pref_networkid");
                    editor.commit();
                    send_to_server(OP_OK, null);
                    break;
                case OP_SET_LIBRARY_INFO:
                    // Server tells us which library is now open. We don't need anything from
                    // this beyond acknowledging it (server blocks waiting for our reply), but we
                    // keep the library name around in case a future UI wants to show it.
                    if (args.has("currentLibraryName")) {
                        currentServer.setLibraryName(args.optString("currentLibraryName", ""));
                    }
                    send_to_server(OP_OK, null);
                    break;
                case OP_ERROR:
                    // Server-side error report. Log it and surface it instead of silently
                    // dropping the packet like the 2013 client did (it had no case for
                    // opcode 20 at all, so it fell through to the default no-op).
                    String errMessage = args.optString("message", "Unknown server error");
                    Logger.d("Calibre reported ERROR: " + errMessage);
                    broadcastError(ErrorType.SERVER_ERROR);
                    break;
            }
            broadcastConnectionStateChange(ConnectionState.IDLING);

        } catch (JSONException e) {
            logAndDie(e);
        }
    }

    private JSONObject initialisation_reply(JSONObject init_args) throws JSONException {
        JSONArray exts = new JSONArray();
        for (String e : getAcceptedExtensionsArray()) {
            exts.put(e);
        }
        JSONObject replyJSON = new JSONObject()
                .put("versionOK", (init_args.getInt("serverProtocolVersion") == PROTOCOL_VERSION))
                .put("maxBookContentPacketLen", BASE_PACKET_LEN)
                .put("ccVersionNumber", APP_VERSION)
                .put("acceptedExtensions", exts)
                .put("canStreamBooks", true)
                .put("canStreamMetadata", true)
                .put("canReceiveBookBinary", true)
                .put("canDeleteMultipleBooks", true)
                .put("deviceKind", Build.PRODUCT)
                // Height (px) Calibre should render book cover thumbnails at before sending
                // them over the wire. 80 produced visibly blurry covers in the Device Library
                // tab (especially in the new grid view); 400 is much sharper while still a
                // small enough JPEG to store per-book in the local metadata DB.
                .put("coverHeight", 400);
        try {
            if (init_args.has("passwordChallenge")) {
                MessageDigest md = MessageDigest.getInstance("SHA1");
                String c = init_args.getString("passwordChallenge");
                md.update(currentServer.getPassword().getBytes("utf-8"));
                md.update(c.getBytes("utf-8"));
                replyJSON.put("passwordHash", toHex(md.digest()));
            }
        } catch (Exception e) {
            logAndDie(e);
        }
        return replyJSON;
    }

    private JSONObject device_information_reply() throws JSONException {
        return new JSONObject()
                .put("device_version", Build.VERSION.RELEASE)
                .put("device_info", mBookCrawler.getDriveInfo())
                .put("version", APP_VERSION);

    }

    private void stream_meta_to_servers() {
        try {
            JSONObject replyJSON = new JSONObject();
            Cursor cs = mDB.getNotNull(Book.COLUMNS, "lpath");
            replyJSON.put("count", cs.getCount() + mBookCrawler.numNewBooks())
                    .put("willStream", true)
                    .put("willScan", true);
            send_to_server(OP_OK, replyJSON);
            cs.moveToFirst();
            Logger.d(String.format("stream_meta_to_servers Loop:%d and %d", cs.getCount(), mBookCrawler.numNewBooks()));
            while (!cs.isAfterLast()) {
                JSONObject tmp_book = new JSONObject()
                        .put("uuid", cs.getString(Book.UUID))
                        .put("lpath", cs.getString(Book.LPATH))
                        .put("authors", new JSONArray().put(cs.getString(Book.AUTHOR)))
                        .put("title", cs.getString(Book.TITLE));
                send_to_server(OP_OK, tmp_book);
                cs.moveToNext();

            }
            cs.close();
            for (JSONObject o : mBookCrawler.getNewBooks()) {
                send_to_server(OP_OK, o);
            }
            //TODO: Clear new books here?
        } catch (JSONException e) {
            logAndDie(e);
        }
    }

    /**
     * Reads from Socket Input Stream
     *
     * @return A 'likely' well-formed JSON StringBuilder
     */
    private StringBuilder read_from_server() {
        StringBuilder incomingString = new StringBuilder();
        int jsonLength = 0, jsonStringOffset = 0, bytesRead = 0, bytesToRead = 16;
        //Incoming string format <length of JSON in bytes>[JSONArray (incl. square brackets)]
        try {
            while (bytesToRead > 0) {
                bytesRead = inputStream.read(connBuf, 0, Math.min(connBuf.length, bytesToRead));
                if (bytesRead < 0) break;
                incomingString.append(new String(connBuf, 0, bytesRead, "utf-8"));
                if (jsonLength == 0) {
                    jsonStringOffset = incomingString.indexOf("[");
                    if (jsonStringOffset < 0) continue;
                    jsonLength = Integer.parseInt(incomingString.substring(0, jsonStringOffset));
                    bytesToRead = jsonLength + jsonStringOffset;
                }
                if (bytesToRead < bytesRead) {
                    throw (new Exception());
                } else {
                    bytesToRead -= bytesRead;
                }
            }
            if (jsonLength > 0 && jsonStringOffset > 0 &&
                    incomingString.length() == jsonLength + jsonStringOffset) {
                incomingString.delete(0, jsonStringOffset);
                Logger.d("<- " + incomingString);
                return incomingString;
            } else {
                throw (new Exception());
            }
        } catch (Exception e) {
            disconnectFromServer();
            logAndDie(e);
        }
        return null;
    }

    private void send_to_server(int op, JSONObject args) {
        try {
            JSONArray packet = new JSONArray();
            packet.put(op);
            if (args != null) {
                packet.put(args);
            } else {
                packet.put(new JSONObject());
            }
            Logger.d("-> " + packet.toString());
            byte[] raw_packet = packet.toString().getBytes();
            outputStream.write(String.format("%d", raw_packet.length).getBytes());
            outputStream.write(raw_packet);
            outputStream.flush();
        } catch (Exception e) {
            logAndDie(e);
        }
    }

    private synchronized void download_file(int remaining, File f) {
        int c = 0, last_c = 0;
        // Calibre's lpath frequently includes subfolders (e.g. "Author/Title (123)/Title -
        // Author.epub") that don't exist yet on the device. Previously FileOutputStream below
        // would throw FileNotFoundException in that case, which fell into logAndDie() ->
        // stopSelf() and killed the whole Service (and its socket) mid-transfer -- which is
        // exactly what shows up on the Calibre side as "[WinError 10054] An existing connection
        // was forcibly closed by the remote host". Create the parent dir(s) up front so the
        // common case never hits that path.
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Logger.d("download_file: could not create parent dir " + parent);
        }

        BufferedOutputStream fw;
        try {
            fw = new BufferedOutputStream(new FileOutputStream(f));
        } catch (IOException e) {
            // Still couldn't open the destination (storage full, permissions, etc). Calibre is
            // about to write exactly `remaining` bytes onto the socket regardless of whether we
            // can store them, so we must drain them to stay in sync with the protocol --
            // otherwise the next read_from_server() call misinterprets leftover book bytes as a
            // JSON packet. Report the failure but keep the connection/Service alive instead of
            // logAndDie(), so one bad book doesn't reset the connection for the rest of the batch.
            Logger.e(e);
            drain(remaining);
            broadcastError(ErrorType.CANT_DOWNLOAD_FILE);
            return;
        }

        try {
            while (remaining > 0 && (c = inputStream.read(connBuf, 0, Math.min(connBuf.length, remaining))) > 0) {
                fw.write(connBuf, 0, c);
                remaining -= c;
                last_c = c;
            }
            fw.flush();
            fw.close();
            if (remaining > 0) {
                byte[] last = new byte[32];
                System.arraycopy(connBuf, Math.max(0, last_c - 32), last, 0, Math.min(last_c, 32));
                Logger.d("Malformed file, Last hex: " + toHex(last));
                broadcastError(ErrorType.CANT_DOWNLOAD_FILE);
            }
        } catch (IOException e) {
            // A genuine socket failure while streaming. The outer connectionRunnable loop
            // already notices a closed/broken socket and moves to DISCONNECTED on its own --
            // no need to stopSelf() the whole Service over it.
            try {
                fw.close();
            } catch (IOException ignored) {
            }
            Logger.e(e);
            disconnectFromServer();
        }
    }

    /**
     * Reads and discards exactly `remaining` bytes from the server socket without writing them
     * anywhere. Used to keep the wire protocol in sync after a book download fails locally
     * (Calibre always sends the full byte count for OP_SEND_BOOK regardless of whether we can
     * store it).
     */
    private void drain(int remaining) {
        try {
            int c;
            while (remaining > 0 && (c = inputStream.read(connBuf, 0, Math.min(connBuf.length, remaining))) > 0) {
                remaining -= c;
            }
        } catch (IOException e) {
            Logger.e(e);
        }
    }

    private synchronized void upload_file(File f) {
        int c = 0;
        try {
            BufferedInputStream fr = new BufferedInputStream(new FileInputStream(f));
            while ((c = fr.read(fileBuf, 0, fileBuf.length)) > 0) {
                outputStream.write(fileBuf, 0, c);
            }
            outputStream.flush();
        } catch (IOException e) {
            logAndDie(e);
        }
    }



    private synchronized void delete_files(JSONArray f) {
        JSONObject rep = new JSONObject();
        try {
            send_to_server(OP_OK, null);
            for (int i = 0; i < f.length(); i++) {
                rep.put("uuid", delete_file(f.getString(i)));
                send_to_server(OP_OK, rep);

            }
        } catch (Exception e) {
            logAndDie(e);

        }
    }

    private String delete_file(String lpath) {
        File f = new File(rootDirectory, lpath);
        if (f.delete())
            return mDB.deleteByPath(lpath);
        return null;
    }

    private Bitmap generateBitmap(byte[] raw_thumb) {
        try {
            return BitmapFactory.decodeByteArray(raw_thumb, 0, raw_thumb.length);
        } catch (Exception e) {
            Logger.e(e);
            return null;
        }
    }

    private void ui_notify(String lpath, File fn, Bitmap pic, boolean update, String uuid) {
        Notification mNotification;
        // See OpenBookActivity#getExtension: MimeTypeMap.getFileExtensionFromUrl() returns ""
        // for filenames with non-ASCII characters (e.g. Vietnamese diacritics), which used to
        // make this notification always fall back to an external-app intent with a null MIME
        // type for such books.
        String ext = OpenBookActivity.getExtension(fn.getName());
        if (ext != null && !ext.isEmpty()) {
            String lowerExt = ext.toLowerCase(java.util.Locale.ROOT);
            Intent tapIntent;
            // epub/pdf/cbz have an in-app reader (see OpenBookActivity#openBook /
            // ReaderActivity) -- every other "open book" entry point in the app (Device
            // Library tab, Calibre Library/OPDS tab, search results) already routes those
            // formats there instead of handing the file to an external app. This notification
            // was the one place still hard-coded to ACTION_VIEW for every format, so tapping
            // "book received" for an epub/pdf/cbz always opened it externally regardless of
            // the in-app reader. Routing through OpenBookActivity (which itself decides
            // in-app vs. external per format, and falls back to external for anything the
            // reader doesn't support) makes this consistent with the rest of the app.
            if (uuid != null && (lowerExt.equals("epub") || lowerExt.equals("pdf") || lowerExt.equals("cbz"))) {
                tapIntent = new Intent(this, OpenBookActivity.class)
                        .putExtra(OpenBookActivity.EXTRA_UUID, uuid)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            } else {
                tapIntent = new Intent(Intent.ACTION_VIEW);
                // file:// Uris passed to another app via an Intent throw FileUriExposedException
                // on API 24+, so we hand out a content:// Uri via FileProvider instead (see
                // res/xml/file_paths.xml and the <provider> entry in AndroidManifest.xml).
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", fn);
                tapIntent.setDataAndType(uri, MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext));
                tapIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            PendingIntent pendingViewIntent = PendingIntent.getActivity(this, 0, tapIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
            builder.setContentIntent(pendingViewIntent)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setAutoCancel(true)
                    .setContentText(lpath)
                    .setWhen(System.currentTimeMillis());
            if (update) {
                builder.setContentTitle(getText(R.string.note_updated));
            } else {
                builder.setContentTitle(getText(R.string.note_received));
            }
            if (pic != null) builder.setLargeIcon(pic);
            mNotification = builder.build();
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                mNotificationManager.notify(NOTIFY_TAG, mNotification);
            }
        }
    }

    private void broadcastServer(ConnectionInfo ci) {
        if (serviceHandler != null) {
            serviceHandler.sendMessage(serviceHandler.obtainMessage(HandlerMessageType.SERVER.id(), ci));
            Logger.d("broadcastServer:" + (ci != null ? ci.toString() : "null (clear)"));
        } else {
            Logger.d("serviceLog: SerivceHandler null");
        }
    }

    /**
     * Updates the foreground service notification's contentText in place, reflecting the
     * service's current activity. Safe to call from any thread and at any point in the
     * service lifecycle (no-ops if the notification hasn't been created yet).
     */
    private void updateForegroundNotification(String statusText) {
        if (mForegroundNotificationBuilder == null || mNotificationManager == null) return;
        mForegroundNotificationBuilder.setContentText(statusText);
        mNotificationManager.notify(FOREGROUND_NOTIFICATION_ID, mForegroundNotificationBuilder.build());
    }

    private String textForDiscoveryState(DiscoveryState d) {
        switch (d) {
            case BEGIN_DISCOVERY: return getString(R.string.status_discovering);
            case NO_SERVER: return getString(R.string.status_no_server_found);
            default: return null; // NEW_SERVER / DONE_DISCOVERY: intermediate states, nothing to show
        }
    }

    /** True neu nguoi dung dang thuc su dung tinh nang Calibre wireless device (tu dong do tim
     * may chu, hoac tu dong ket noi lai theo dia chi da luu) -- xem preference_connection.xml.
     * Dung de biet co nen hien trang thai "Chua ket noi" trong notification foreground hay
     * khong: neu ca 2 tinh nang nay deu tat, service khong he co gang ket noi wireless device
     * nao ca (xem connectWithSavedPreferences() tra ve false ngay khi pref_autocon == false),
     * nen "Chua ket noi" chi gay hoang mang chu khong phan anh dung trang thai/loi gi. */
    private boolean wirelessFeatureInUse() {
        return mSettings.getBoolean("pref_auto_discover_server", false)
                || mSettings.getBoolean("pref_autocon", false);
    }

    private String textForConnectionState(ConnectionState s) {
        switch (s) {
            case CONNECTING: return getString(R.string.status_connecting);
            case COMMUNICATING: return getString(R.string.status_syncing);
            case WAITING_FOR_FOLDERSCAN: return getString(R.string.status_scanning_library);
            case IDLING: return getString(R.string.status_connected_idle);
            case DISCONNECTED:
                if (mHasEverConnected) return getString(R.string.status_disconnected);
                // FIX: truoc day luon hien "Chua ket noi" (status_not_connected) ngay khi
                // ConnectionFragment enquireConnectionState() lan dau, bat ke nguoi dung co bat
                // tinh nang wireless device hay khong -- vi ConnectionFragment binds den
                // CalibreService som (tab duoc tao san trong BarTabManager) va goi
                // enquireConnectionState() ngay trong onServiceConnected(), nen thong bao nay
                // xuat hien ngay ca khi nguoi dung da tat "Tu dong do tim" + "Tu dong ket noi"
                // va chua bao gio mo tab Connection. Khi ca 2 tinh nang do dang tat, service
                // khong chu dong thu ket noi gi ca (connectWithSavedPreferences() no-op ngay),
                // nen "Chua ket noi" o day khong phai la 1 trang thai/loi can nguoi dung chu y --
                // chi hien 1 dong trang thai trung tinh thay vi vay.
                return wirelessFeatureInUse()
                        ? getString(R.string.status_not_connected)
                        : getString(R.string.status_wireless_off);
            default: return null;
        }
    }

    private String textForError(ErrorType e) {
        switch (e) {
            case CALIBRE_BUSY: return getString(R.string.status_error_calibre_busy);
            case CANT_CONNECT: return getString(R.string.status_error_cant_connect);
            case CANT_DOWNLOAD_FILE: return getString(R.string.status_error_cant_download);
            case WRONG_PASSWORD: return getString(R.string.status_error_wrong_password);
            case ROOT_NO_ACCESS: return getString(R.string.status_error_root_no_access);
            case SERVER_ERROR: return getString(R.string.status_error_server);
            default: return null;
        }
    }

    private void broadcastDiscoveryStateChange(DiscoveryState d) {
        String statusText = textForDiscoveryState(d);
        if (statusText != null) updateForegroundNotification(statusText);
        if (serviceHandler != null) {
            serviceHandler.sendMessage(serviceHandler.obtainMessage(HandlerMessageType.DISCOVERY.id(), d));
            Logger.d("broadcastDiscoveryStateChange: " + d.toString());
        } else {
            Logger.d("serviceLog: SerivceHandler null");
        }
    }

    private void broadcastConnectionStateChange(ConnectionState s) {
        if (s == ConnectionState.COMMUNICATING) mHasEverConnected = true;
        String statusText = textForConnectionState(s);
        if (statusText != null) updateForegroundNotification(statusText);
        if (serviceHandler != null) {
            serviceHandler.sendMessage(serviceHandler.obtainMessage(HandlerMessageType.CONNECTION.id(), s));
            Logger.d("broadcastConnectionStateChange: " + s.toString());
        } else {
            Logger.d("broadcastConnectionStateChange: SerivceHandler null");
        }
    }

    private void broadcastError(ErrorType e) {
        String statusText = textForError(e);
        if (statusText != null) updateForegroundNotification(statusText);
        if (serviceHandler != null) {
            serviceHandler.sendMessage(serviceHandler.obtainMessage(HandlerMessageType.ERROR.id(), e));
            Logger.d("broadcastError: " + e.toString());
        } else {
            Logger.d("broadcastError: SerivceHandler null");
        }
    }

    public void enquireConnectionState() {
        if (isConnected()) {
            broadcastConnectionStateChange(ConnectionState.COMMUNICATING);
        } else if (connectionThreadIsRunning()) {
            broadcastConnectionStateChange(ConnectionState.CONNECTING);
        } else {
            broadcastConnectionStateChange(ConnectionState.DISCONNECTED);
        }
        if (discoveryThreadIsRunning()) {
            broadcastDiscoveryStateChange(DiscoveryState.BEGIN_DISCOVERY);
        }
    }

    public ArrayList<ConnectionInfo> getListOfServers() {
        return listOfServers;
    }

    public ConnectionInfo getCurrentServer() {
        return currentServer;
    }

    private void logAndDie(Throwable e) {
        Logger.e(e);
        stopSelf();
    }

    public enum ConnectionState {
        CONNECTING, COMMUNICATING, IDLING, DISCONNECTED, WAITING_FOR_FOLDERSCAN
    }

    public enum ErrorType {
        CALIBRE_BUSY, CANT_CONNECT, CANT_DOWNLOAD_FILE, WRONG_PASSWORD, ROOT_NO_ACCESS, SERVER_ERROR
    }

    public enum DiscoveryState {
        BEGIN_DISCOVERY, DONE_DISCOVERY, NEW_SERVER, NO_SERVER
    }

    public enum HandlerMessageType {
        CONNECTION(0), DISCOVERY(1), ERROR(2), SERVER(3);
        int code;

        HandlerMessageType(int code) {
            this.code = code;
        }

        public int id() {
            return code;
        }

    }

    public static interface CalibreListener {
        public void onConnectionStateChanged(ConnectionState connectionState);

        public void onDiscoveryStateChanged(DiscoveryState discoveryState);

        public void onErrorReported(ErrorType e);
    }

    public class CalibreBinder extends Binder {

        public CalibreService getService() {
            return CalibreService.this;
        }
    }
}