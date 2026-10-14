package net.mrphan.calibre;

import com.thegrizzlylabs.sardineandroid.DavResource;
import com.thegrizzlylabs.sardineandroid.Sardine;
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** CloudProvider backed by a WebDAV server (Nextcloud/ownCloud/generic NAS) via sardine-android.
 * All methods are blocking -- caller must run these off the main thread. */
public class WebDavCloudProvider implements CloudProvider {

    private final String baseUrl;
    private final Sardine sardine;
    private final String basePath;

    WebDavCloudProvider(CloudRemote remote, String password) {
        this.basePath = remote.getBasePath() != null ? remote.getBasePath() : "/";
        this.baseUrl = (remote.isUseTls() ? "https://" : "http://") + remote.getHost() + ":" + remote.getPort();
        this.sardine = new OkHttpSardine();
        sardine.setCredentials(remote.getUsername(), password);
    }

    /** Builds the full URL for a remote path, percent-encoding each path segment individually
     * so slashes stay as path separators instead of being encoded into "%2F". */
    private String buildUrl(String path) {
        StringBuilder sb = new StringBuilder(baseUrl);
        String[] segments = path.split("/");
        for (String segment : segments) {
            if (segment.isEmpty()) continue;
            sb.append('/');
            try {
                sb.append(URLEncoder.encode(segment, "UTF-8").replace("+", "%20"));
            } catch (UnsupportedEncodingException e) {
                sb.append(segment);
            }
        }
        // Directories need a trailing slash for PROPFIND to list their contents rather than
        // their own metadata.
        if (!sb.toString().endsWith("/")) sb.append('/');
        return sb.toString();
    }

    @Override
    public CloudFile getRoot() {
        return new CloudFile(basePath, "Root", true, 0, 0, false);
    }

    @Override
    public List<CloudFile> listFiles(String refId) throws IOException {
        List<DavResource> resources = sardine.list(buildUrl(refId));
        List<CloudFile> result = new ArrayList<>();
        String selfHref = normalizeHref(refId);
        for (DavResource res : resources) {
            String href = normalizeHref(res.getHref().getPath());
            // sardine.list() includes the queried folder itself as the first entry -- skip it.
            if (href.equals(selfHref)) continue;
            String decodedPath;
            try {
                decodedPath = URLDecoder.decode(res.getHref().getPath(), "UTF-8");
            } catch (UnsupportedEncodingException e) {
                decodedPath = res.getHref().getPath();
            }
            result.add(new CloudFile(
                    decodedPath,
                    res.getName(),
                    res.isDirectory(),
                    res.getContentLength() != null ? res.getContentLength() : 0,
                    res.getModified() != null ? res.getModified().getTime() : 0,
                    false));
        }
        return result;
    }

    private static String normalizeHref(String path) {
        String p = path.endsWith("/") ? path : path + "/";
        return p;
    }

    @Override
    public void downloadFile(CloudFile file, File localDestFile) throws IOException {
        try (InputStream in = sardine.get(buildUrl(file.getRefId()));
             FileOutputStream out = new FileOutputStream(localDestFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    @Override
    public String uploadFile(File localFile, String destFolderRefId, String remoteFileName) throws IOException {
        // remoteFileName co the khac localFile.getName() (xem javadoc CloudProvider#uploadFile
        // 3-tham-so).
        String destPath = joinPath(destFolderRefId, remoteFileName);
        String destUrl = buildUrl(destPath);
        // sardine-android only exposes put(url, byte[], contentType) and put(url, File, contentType)
        // -- there's no InputStream overload in this library. Use the File overload so Sardine
        // still streams from disk internally instead of buffering the whole book into RAM.
        sardine.put(destUrl, localFile, "application/octet-stream");
        // refId for WebDAV is always the full path (see CloudFile's Javadoc), not a server-side
        // id -- no second call needed to know it.
        return destPath;
    }

    @Override
    public String createFolder(String parentRefId, String name) throws IOException {
        String path = joinPath(parentRefId, name);
        sardine.createDirectory(buildUrl(path));
        return path;
    }

    @Override
    public String updateFileContent(String existingRefId, File localFile) throws IOException {
        // WebDAV PUT to an existing path simply overwrites its content -- no separate "update"
        // verb needed, and no 404 special-case: PUT to a missing path just creates it, which is
        // exactly the desired fallback behaviour too.
        sardine.put(buildUrl(existingRefId), localFile, "application/octet-stream");
        return existingRefId;
    }

    private static String joinPath(String parent, String name) {
        return (parent.endsWith("/") ? parent : parent + "/") + name;
    }

    @Override
    public void deleteFile(CloudFile file) throws IOException {
        // Chua can cho WebDAV -- nut "Check" don muc mo coi chi hien voi 4 loai remote co
        // supportsDeviceLibrarySync() == true (GOOGLE_DRIVE, ONEDRIVE, BOX, DROPBOX), khong bao
        // gio la WebDAV. Xem javadoc CloudProvider#deleteFile.
        throw new UnsupportedOperationException("deleteFile not implemented for WebDAV");
    }

    @Override
    public boolean testConnection() throws IOException {
        listFiles(basePath);
        return true;
    }

    @Override
    public void close() {
        // Sardine/OkHttpSardine has no explicit close(); nothing to release.
    }
}
