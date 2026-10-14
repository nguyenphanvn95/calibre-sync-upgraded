package net.mrphan.calibre;

import android.content.Context;

import com.google.api.client.http.FileContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.FileList;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CloudProvider backed by Google Drive via the official Drive v3 REST client
 * (com.google.api.services.drive.Drive), authenticated through OAuth 2.0 Authorization Code
 * Flow tokens managed by CloudTokenManager (see GoogleDriveAuthHttpInitializer). Every
 * method here makes a real blocking network
 * call -- same contract as every other CloudProvider (see CloudProvider.java), caller runs this
 * off the main thread (CloudBrowserActivity's single-thread ExecutorService).
 * <p>
 * Google Drive's file metadata class is referred to by its fully-qualified name
 * (com.google.api.services.drive.model.File) throughout instead of being imported, since a plain
 * "File" import would collide with java.io.File -- also used constantly in this class for local
 * (on-device) files.
 */
public class GoogleDriveCloudProvider implements CloudProvider {

    /** "root" is Drive's own alias for the user's My Drive root folder -- no separate API call
     * needed to resolve it, unlike WebDavCloudProvider/FtpCloudProvider's basePath. */
    private static final String ROOT_FOLDER_ID = "root";
    private static final String LIST_FIELDS = "files(id,name,mimeType,size,modifiedTime),nextPageToken";
    private static final String WORKSPACE_MIME_PREFIX = "application/vnd.google-apps.";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";

    private final Drive drive;

    GoogleDriveCloudProvider(Context context) {
        if (!CloudTokenManager.getInstance(context, CloudRemoteType.GOOGLE_DRIVE).isSignedIn()) {
            throw new IllegalStateException(
                    "Not signed in to Google Drive (or Drive access was revoked)");
        }
        this.drive = new Drive.Builder(
                new com.google.api.client.http.javanet.NetHttpTransport(),
                com.google.api.client.json.gson.GsonFactory.getDefaultInstance(),
                new GoogleDriveAuthHttpInitializer(context))
                .setApplicationName("Calibre Sync")
                .build();
    }

    @Override
    public CloudFile getRoot() {
        return new CloudFile(ROOT_FOLDER_ID, "Google Drive", true, 0, 0, false);
    }

    @Override
    public List<CloudFile> listFiles(String refId) throws IOException {
        List<CloudFile> result = new ArrayList<>();
        String pageToken = null;
        // "'<id>' in parents" is Drive's standard query syntax for direct children of a folder;
        // trashed = false hides deleted-but-not-yet-purged files, which Drive otherwise still
        // returns. A literal single quote in refId (a Drive file id) can't actually occur --
        // Drive ids are alphanumeric/-/_ only -- but it's escaped anyway since this string is
        // interpolated directly into the query.
        String escapedId = refId.replace("'", "\\'");
        do {
            FileList response = drive.files().list()
                    .setQ("'" + escapedId + "' in parents and trashed = false")
                    .setSpaces("drive")
                    .setFields(LIST_FIELDS)
                    .setPageToken(pageToken)
                    .execute();
            if (response.getFiles() != null) {
                for (com.google.api.services.drive.model.File f : response.getFiles()) {
                    boolean isFolder = FOLDER_MIME.equals(f.getMimeType());
                    boolean isWorkspaceDoc = !isFolder && f.getMimeType() != null
                            && f.getMimeType().startsWith(WORKSPACE_MIME_PREFIX);
                    result.add(new CloudFile(
                            f.getId(),
                            f.getName(),
                            isFolder,
                            f.getSize() != null ? f.getSize() : 0,
                            f.getModifiedTime() != null ? f.getModifiedTime().getValue() : 0,
                            isWorkspaceDoc));
                }
            }
            pageToken = response.getNextPageToken();
        } while (pageToken != null);
        return result;
    }

    @Override
    public void downloadFile(CloudFile file, File localDestFile) throws IOException {
        try (FileOutputStream out = new FileOutputStream(localDestFile)) {
            drive.files().get(file.getRefId()).executeMediaAndDownloadTo(out);
        }
    }

    @Override
    public String uploadFile(File localFile, String destFolderRefId, String remoteFileName) throws IOException {
        com.google.api.services.drive.model.File metadata = new com.google.api.services.drive.model.File();
        // remoteFileName co the khac localFile.getName() (vd RemoteTreeResolver can ten co dinh
        // "book.epub" trong khi file tam local ten khac) -- xem javadoc CloudProvider#uploadFile
        // 3-tham-so.
        metadata.setName(remoteFileName);
        metadata.setParents(Collections.singletonList(destFolderRefId));
        FileContent content = new FileContent(guessMimeType(localFile), localFile);
        com.google.api.services.drive.model.File created =
                drive.files().create(metadata, content).setFields("id").execute();
        return created.getId();
    }

    @Override
    public String createFolder(String parentRefId, String name) throws IOException {
        com.google.api.services.drive.model.File metadata =
                new com.google.api.services.drive.model.File();
        metadata.setName(name);
        metadata.setMimeType(FOLDER_MIME);
        metadata.setParents(Collections.singletonList(parentRefId));
        com.google.api.services.drive.model.File created =
                drive.files().create(metadata).setFields("id").execute();
        return created.getId();
    }

    @Override
    public String updateFileContent(String existingRefId, File localFile) throws IOException {
        FileContent content = new FileContent(guessMimeType(localFile), localFile);
        try {
            drive.files().update(existingRefId, null, content).execute();
            return existingRefId;
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) {
                // File bi xoa tay tren Drive giua 2 lan sync -- tao lai nhu file moi. Khong dat
                // parents o day (updateFileContent khong biet destFolderRefId) nen file moi roi
                // vao My Drive root; DeviceLibrarySyncManager se ghi lai driveFileId moi trong
                // manifest nen lan sync sau van dung, day chi la nhanh fallback hiem gap.
                com.google.api.services.drive.model.File metadata =
                        new com.google.api.services.drive.model.File();
                metadata.setName(localFile.getName());
                com.google.api.services.drive.model.File created =
                        drive.files().create(metadata, content).setFields("id").execute();
                return created.getId();
            }
            throw e;
        }
    }

    @Override
    public void deleteFile(CloudFile file) throws IOException {
        try {
            // Xoa vinh vien (khong phai trash). Xoa 1 thu muc tu dong xoa de quy noi dung ben
            // trong, khong can tu lap listFiles() roi xoa tung file con.
            drive.files().delete(file.getRefId()).execute();
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) {
                // Da bi xoa thu cong tu truoc -- coi la thanh cong, khong throw (xem javadoc
                // CloudProvider#deleteFile).
                return;
            }
            throw e;
        }
    }

    private static String guessMimeType(File file) {
        String type = URLConnection.guessContentTypeFromName(file.getName());
        return type != null ? type : "application/octet-stream";
    }

    @Override
    public boolean testConnection() throws IOException {
        drive.files().list().setPageSize(1).setFields("files(id)").execute();
        return true;
    }

    @Override
    public void close() {
        // Drive (a plain REST client over HTTP) holds no persistent connection/socket of its
        // own that needs releasing here.
    }
}
