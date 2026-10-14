package net.mrphan.calibre;

import java.net.*;

public class ConnectionInfo {
	private String host;
	private int wd_port;
	private int cs_port;
	// Content Server scheme. Calibre's Content Server defaults to plain http, but can be
	// configured with --ssl-certfile/--ssl-keyfile, in which case it should be "https".
	// The wireless-device TCP socket itself is unaffected by this and stays plain TCP either way.
	private String cs_scheme = "http";
	private SocketAddress wd_socket;
	private URL cs_url;
	private String password;
	private String libraryName;
	// Content Server Basic Auth credentials (Calibre "Preferences -> Sharing -> Content server ->
	// Require username and password"). Deliberately named cs* / distinct from `password` above,
	// which is the *Smart Device App* wireless-device password (SHA1 challenge-response) -- the
	// two are unrelated Calibre features that happen to live on the same ConnectionInfo object.
	private String csUsername;
	private String csPassword;
    private boolean resolved;
	
	public ConnectionInfo(String host, int wd_port, int cs_port) {
		this.host = host;
		this.wd_port = wd_port;
		this.cs_port = cs_port;
	}
    public ConnectionInfo(String host, int wd_port, int cs_port, String password) {
        this.host = host;
        this.wd_port = wd_port;
        this.cs_port = cs_port;
        this.password = password;
    }

	public void setContentServerScheme(String scheme) {
		if ("https".equalsIgnoreCase(scheme)) {
			this.cs_scheme = "https";
		} else {
			this.cs_scheme = "http";
		}
	}

	public void resolveAddresses() throws MalformedURLException, UnknownHostException {
        if (cs_url == null || wd_socket == null) {
			wd_socket = new InetSocketAddress(InetAddress.getByName(host), wd_port);
			cs_url = new URL(cs_scheme, host, cs_port, "/opds");
        }
	}

	public String toString() {
		return getHost() + ":" + getWirelessDevicePort();
	}
	public String getWirelessDevicePort() {
		return Integer.toString(wd_port);
	}
	public String getContentServerPort() {
		return Integer.toString(cs_port);
	}
	public String getHost() {
		return host;
	}
	public SocketAddress getWirelessDeviceSocket() {
		return wd_socket;
	}
	public URL getContentServerURL() {
		return cs_url;
	}

	/** Cloud Libraries upgrade: root URL of the Content Server's own web UI (no "/opds" path),
	 * used by ContentServerBrowserActivity's embedded WebView browser. Added alongside
	 * getContentServerURL() rather than changing it -- OPDSLoader/OPDSBrowserFragment and every
	 * other existing call site still depend on the "/opds" path exactly as before.
	 * <p>
	 * NOTE: ConnectionInfo is a runtime object tied to the current connection session and can be
	 * null after the app process is killed and relaunched (only pref_cs_url survives in
	 * SharedPreferences). ContentServerBrowserActivity therefore does NOT rely on this method --
	 * it re-derives the same root URL directly from the saved pref_cs_url string instead, which
	 * works even before CalibreService has bound. This method is kept here for any other
	 * in-session caller that already has a ConnectionInfo in hand. */
	public URL getContentServerRootURL() throws MalformedURLException {
		return new URL(cs_scheme, host, cs_port, "/");
	}
	
	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getLibraryName() {
		return libraryName;
	}

	public void setLibraryName(String libraryName) {
		this.libraryName = libraryName;
	}

	public String getCsUsername() {
		return csUsername;
	}

	public void setCsUsername(String csUsername) {
		this.csUsername = csUsername;
	}

	public String getCsPassword() {
		return csPassword;
	}

	public void setCsPassword(String csPassword) {
		this.csPassword = csPassword;
	}

	/** @return true if this Content Server has Basic Auth credentials configured. */
	public boolean hasCsCredentials() {
		return csUsername != null && !csUsername.isEmpty();
	}

	/**
	 * @return the ready-to-use "Basic &lt;base64&gt;" HTTP Authorization header value, or null if
	 * no Content Server credentials are configured for this connection.
	 */
	public String getCsBasicAuthHeader() {
		if (!hasCsCredentials()) return null;
		String pw = csPassword != null ? csPassword : "";
		try {
			String creds = csUsername + ":" + pw;
			return "Basic " + android.util.Base64.encodeToString(
					creds.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
		} catch (java.io.UnsupportedEncodingException e) {
			return null;
		}
	}

	public String repr() {
		return String.format("{ Socket=%s, URL=%s, Password=%s, Name=%s }",
				wd_socket.toString(),
				cs_url.toString(),
				password,libraryName);
	}
}
