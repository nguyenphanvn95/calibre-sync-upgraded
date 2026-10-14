package net.mrphan.calibre;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * A single user-configured cloud remote (a Google Drive account, or one WebDAV/FTP server the
 * user typed in). Persisted via CloudRemoteStore, which keeps the password (if any) in a
 * separate EncryptedSharedPreferences key rather than inline here -- see toJson()/fromJson(),
 * which deliberately never touch a password field.
 */
public class CloudRemote {

    private final String id;
    private String displayName;
    private CloudRemoteType type;
    /** null for GOOGLE_DRIVE. */
    private String host;
    private int port;
    private boolean useTls;
    /** null for GOOGLE_DRIVE. */
    private String username;
    /** Not used for GOOGLE_DRIVE (Drive addresses folders by folderId via CloudFile.refId
     * instead). Defaults to "/". */
    private String basePath = "/";

    public CloudRemote(String id, String displayName, CloudRemoteType type) {
        this.id = id;
        this.displayName = displayName;
        this.type = type;
    }

    public static CloudRemote newWithRandomId(String displayName, CloudRemoteType type) {
        return new CloudRemote(UUID.randomUUID().toString(), displayName, type);
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public CloudRemoteType getType() {
        return type;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isUseTls() {
        return useTls;
    }

    public void setUseTls(boolean useTls) {
        this.useTls = useTls;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = (basePath == null || basePath.isEmpty()) ? "/" : basePath;
    }

    /** Never writes a password -- see CloudRemoteStore, which keeps that in its own
     * "cloud_password_"+id key instead. */
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("displayName", displayName);
        o.put("type", type.name());
        o.put("host", host);
        o.put("port", port);
        o.put("useTls", useTls);
        o.put("username", username);
        o.put("basePath", basePath);
        return o;
    }

    public static CloudRemote fromJson(JSONObject o) throws JSONException {
        CloudRemote r = new CloudRemote(o.getString("id"), o.getString("displayName"),
                CloudRemoteType.valueOf(o.getString("type")));
        r.host = o.isNull("host") ? null : o.optString("host", null);
        r.port = o.optInt("port", 0);
        r.useTls = o.optBoolean("useTls", false);
        r.username = o.isNull("username") ? null : o.optString("username", null);
        r.basePath = o.optString("basePath", "/");
        return r;
    }
}
