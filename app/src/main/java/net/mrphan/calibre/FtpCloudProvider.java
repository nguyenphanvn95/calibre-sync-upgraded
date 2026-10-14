package net.mrphan.calibre;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPSClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** CloudProvider backed by an FTP/FTPS server via Apache Commons Net. All networking here is
 * blocking -- MUST run off the main thread (see CloudProvider). FTPClient itself is not
 * thread-safe: never call two methods on the same instance concurrently. */
public class FtpCloudProvider implements CloudProvider {

    private final CloudRemote remote;
    private final String password;
    private FTPClient ftpClient;

    FtpCloudProvider(CloudRemote remote, String password) {
        this.remote = remote;
        this.password = password;
    }

    private FTPClient client() throws IOException {
        if (ftpClient != null && ftpClient.isConnected()) return ftpClient;
        FTPClient client = remote.isUseTls() ? new FTPSClient() : new FTPClient();
        client.connect(remote.getHost(), remote.getPort());
        if (!client.login(remote.getUsername(), password)) {
            client.disconnect();
            throw new IOException("FTP login failed");
        }
        // Missing this is a very common mistake -- default is ASCII mode, which corrupts
        // binary book files (epub/pdf/etc) on download.
        client.setFileType(FTP.BINARY_FILE_TYPE);
        if (client instanceof FTPSClient) {
            // Protect the data channel -- easy to forget when doing FTPS.
            FTPSClient ftps = (FTPSClient) client;
            ftps.execPBSZ(0);
            ftps.execPROT("P");
        }
        client.enterLocalPassiveMode();
        ftpClient = client;
        return ftpClient;
    }

    @Override
    public CloudFile getRoot() {
        String basePath = remote.getBasePath() != null ? remote.getBasePath() : "/";
        return new CloudFile(basePath, "Root", true, 0, 0, false);
    }

    @Override
    public List<CloudFile> listFiles(String refId) throws IOException {
        FTPClient client = client();
        if (!client.changeWorkingDirectory(refId)) {
            throw new IOException("Cannot open remote directory: " + refId);
        }
        FTPFile[] files = client.listFiles();
        List<CloudFile> result = new ArrayList<>();
        String base = refId.endsWith("/") ? refId : refId + "/";
        for (FTPFile f : files) {
            if (f.getName().equals(".") || f.getName().equals("..")) continue;
            result.add(new CloudFile(
                    base + f.getName(),
                    f.getName(),
                    f.isDirectory(),
                    f.getSize(),
                    f.getTimestamp() != null ? f.getTimestamp().getTimeInMillis() : 0,
                    false));
        }
        return result;
    }

    @Override
    public void downloadFile(CloudFile file, File localDestFile) throws IOException {
        FTPClient client = client();
        try (InputStream in = client.retrieveFileStream(file.getRefId());
             FileOutputStream out = new FileOutputStream(localDestFile)) {
            if (in == null) throw new IOException("Cannot open remote file: " + file.getRefId());
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        // MUST be called after closing the stream, or the FTPClient will hang and the next
        // command will fail with "500 Illegal PORT command".
        client.completePendingCommand();
    }

    @Override
    public String uploadFile(File localFile, String destFolderRefId, String remoteFileName) throws IOException {
        FTPClient client = client();
        // remoteFileName co the khac localFile.getName() (xem javadoc CloudProvider#uploadFile
        // 3-tham-so).
        String destPath = joinPath(destFolderRefId, remoteFileName);
        try (FileInputStream in = new FileInputStream(localFile);
             OutputStream out = client.storeFileStream(destPath)) {
            if (out == null) throw new IOException("Cannot open remote destination: " + destPath);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        client.completePendingCommand();
        // refId for FTP is always the full path (see CloudFile's Javadoc), not a server-side id.
        return destPath;
    }

    @Override
    public String createFolder(String parentRefId, String name) throws IOException {
        FTPClient client = client();
        String path = joinPath(parentRefId, name);
        // MKD on a directory that already exists just fails harmlessly -- resolveOrCreateLibraryFolder
        // / findOrCreateChildFolder callers already listFiles() first, but a stray race (or a
        // caller that skips that check) shouldn't blow up the whole sync.
        if (!client.makeDirectory(path) && client.getReplyCode() != 550) {
            throw new IOException("MKD failed for " + path + ": " + client.getReplyString());
        }
        return path;
    }

    @Override
    public String updateFileContent(String existingRefId, File localFile) throws IOException {
        // STOR to an existing path overwrites it -- same call as uploadFile, just without
        // appending the file name again since existingRefId already IS the full destination path.
        FTPClient client = client();
        try (FileInputStream in = new FileInputStream(localFile);
             OutputStream out = client.storeFileStream(existingRefId)) {
            if (out == null) throw new IOException("Cannot open remote destination: " + existingRefId);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        client.completePendingCommand();
        return existingRefId;
    }

    private static String joinPath(String parent, String name) {
        return (parent.endsWith("/") ? parent : parent + "/") + name;
    }

    @Override
    public void deleteFile(CloudFile file) throws IOException {
        // Chua can cho FTP -- nut "Check" don muc mo coi chi hien voi 4 loai remote co
        // supportsDeviceLibrarySync() == true (GOOGLE_DRIVE, ONEDRIVE, BOX, DROPBOX), khong bao
        // gio la FTP. Xem javadoc CloudProvider#deleteFile.
        throw new UnsupportedOperationException("deleteFile not implemented for FTP");
    }

    @Override
    public boolean testConnection() throws IOException {
        client();
        return true;
    }

    @Override
    public void close() {
        if (ftpClient != null) {
            try {
                ftpClient.logout();
            } catch (IOException ignored) {
            }
            try {
                ftpClient.disconnect();
            } catch (IOException ignored) {
            }
            ftpClient = null;
        }
    }
}
