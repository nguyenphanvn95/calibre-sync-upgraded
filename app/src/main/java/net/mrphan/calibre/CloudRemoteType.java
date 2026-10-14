package net.mrphan.calibre;

/** The kinds of user-configured cloud remotes the "WebDAV / FTP / NAS", "Google Drive",
 * "OneDrive", "Box", and "Dropbox" cards in the Connection tab's Cloud Libraries section can
 * create. See CloudRemote / CloudRemoteStore / CloudProviderFactory. */
public enum CloudRemoteType {
    GOOGLE_DRIVE, ONEDRIVE, BOX, DROPBOX, WEBDAV, FTP;

    /** @return true if this remote type can run background Device Library Sync
     * (DeviceLibrarySyncWorker) -- currently Google Drive, OneDrive, Box, and Dropbox; WebDAV/FTP
     * only support browsing via CloudBrowserActivity and have never been wired to the sync
     * worker. */
    public boolean supportsDeviceLibrarySync() {
        return this == GOOGLE_DRIVE || this == ONEDRIVE || this == BOX || this == DROPBOX;
    }
}
