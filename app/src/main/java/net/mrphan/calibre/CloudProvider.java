package net.mrphan.calibre;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Common interface for every kind of cloud/remote book source (Google Drive, WebDAV, FTP).
 * All methods are blocking -- implementations must NOT spawn their own background thread; the
 * caller (CloudBrowserActivity / ContentServerBrowserActivity, both using a single-thread
 * ExecutorService) is responsible for that. This matters especially for FtpCloudProvider,
 * whose underlying FTPClient is not thread-safe: never call two of these concurrently on the
 * same CloudProvider instance.
 */
public interface CloudProvider {
    /** @return a CloudFile representing the root folder to start browsing from. */
    CloudFile getRoot();

    /** @param refId the refId of getRoot() or of a folder CloudFile returned earlier. */
    List<CloudFile> listFiles(String refId) throws IOException;

    void downloadFile(CloudFile file, File localDestFile) throws IOException;

    /**
     * @return the refId of the newly-created remote file. Added for DeviceLibrarySyncManager,
     * which needs the id right away to record it in calibre_sync_manifest.json without a second
     * listFiles() round-trip (slow, and ambiguous if two files share a name).
     * <p>
     * Default implementation delegates to {@link #uploadFile(File, String, String)} using
     * {@code localFile.getName()} as the remote name -- preserves the original 2-arg contract
     * for existing call sites (SyncManifest#uploadTo, SyncProgress#uploadTo) that don't care
     * about a fixed remote name.
     */
    default String uploadFile(File localFile, String destFolderRefId) throws IOException {
        return uploadFile(localFile, destFolderRefId, localFile.getName());
    }

    /**
     * Same as {@link #uploadFile(File, String)}, but the remote file is created under
     * {@code remoteFileName} instead of {@code localFile.getName()}. Added for
     * RemoteTreeResolver#writeFile, which needs fixed remote names ("book.epub", "cover.jpg")
     * inside the {@code books/<uuid>/} layout (xem muc 3.1 cua prompt nang cap Device Library
     * Sync) -- local files are named after a temp/original path, not the fixed remote name.
     * @return the refId of the newly-created remote file.
     */
    String uploadFile(File localFile, String destFolderRefId, String remoteFileName) throws IOException;

    boolean testConnection() throws IOException;

    void close();

    /**
     * Creates a subfolder named {@code name} inside the folder {@code parentRefId}, returning
     * the new folder's refId. Used by RemoteTreeResolver/DeviceLibrarySyncManager to
     * mirror the local book folder structure on the cloud remote. WebDAV/FTP implementations
     * create a real remote directory (MKCOL/MKD) and return its full path as refId, matching how
     * those providers already use full paths as refId elsewhere (see CloudFile's Javadoc).
     */
    String createFolder(String parentRefId, String name) throws IOException;

    /**
     * Overwrites the content of an already-existing remote file (known by {@code existingRefId},
     * e.g. from a previous sync's manifest) with {@code localFile}, WITHOUT creating a
     * duplicately-named file. If {@code existingRefId} no longer exists (deleted by hand on the
     * remote between two syncs), implementations must fall back to creating a new file and
     * return its new refId instead of throwing.
     * @return the refId the content now lives under (usually {@code existingRefId} unchanged).
     */
    String updateFileContent(String existingRefId, File localFile) throws IOException;

    /**
     * Xoa vinh vien 1 file hoac thu muc (va toan bo noi dung ben trong neu la thu muc) khoi
     * remote. Dung cho tinh nang "Check" don muc mo coi (xem RemoteOrphanScanner). Neu file/thu
     * muc khong con ton tai (da bi xoa thu cong tu truoc), implementation phai coi la thanh cong
     * (no-op), KHONG throw.
     */
    void deleteFile(CloudFile file) throws IOException;
}
