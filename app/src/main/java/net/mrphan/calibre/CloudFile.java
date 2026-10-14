package net.mrphan.calibre;

/**
 * One file or folder entry as returned by CloudProvider#listFiles/getRoot. refId's meaning
 * depends on the provider: for WEBDAV/FTP it's the full path string on the remote; for
 * GOOGLE_DRIVE it's the Drive-assigned file/folder id.
 */
public class CloudFile {
    private final String refId;
    private final String name;
    private final boolean isDirectory;
    private final long sizeBytes;
    /** Epoch millis, 0 if unknown. */
    private final long lastModified;
    /** True if this is a Google Docs/Sheets/Slides file (mimeType starts with
     * "application/vnd.google-apps."), which can't be downloaded directly via downloadFile()
     * -- only exported, which is out of scope for an ebook reader app. Browsing UIs should hide
     * entries where this is true AND isDirectory is false, but still show (and let the user
     * open) directories with this flag set, since it only ever applies to files. */
    private final boolean isGoogleWorkspaceDoc;

    public CloudFile(String refId, String name, boolean isDirectory, long sizeBytes,
                      long lastModified, boolean isGoogleWorkspaceDoc) {
        this.refId = refId;
        this.name = name;
        this.isDirectory = isDirectory;
        this.sizeBytes = sizeBytes;
        this.lastModified = lastModified;
        this.isGoogleWorkspaceDoc = isGoogleWorkspaceDoc;
    }

    public String getRefId() {
        return refId;
    }

    public String getName() {
        return name;
    }

    public boolean isDirectory() {
        return isDirectory;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public long getLastModified() {
        return lastModified;
    }

    public boolean isGoogleWorkspaceDoc() {
        return isGoogleWorkspaceDoc;
    }
}
