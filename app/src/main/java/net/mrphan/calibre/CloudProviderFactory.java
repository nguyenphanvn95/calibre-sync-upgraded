package net.mrphan.calibre;

import android.content.Context;

public class CloudProviderFactory {
    public static CloudProvider create(Context context, CloudRemote remote, String password) {
        switch (remote.getType()) {
            case GOOGLE_DRIVE:
                return new GoogleDriveCloudProvider(context);
            case ONEDRIVE:
                return new OneDriveCloudProvider(context);
            case BOX:
                return new BoxCloudProvider(context);
            case DROPBOX:
                return new DropboxCloudProvider(context);
            case WEBDAV:
                return new WebDavCloudProvider(remote, password);
            case FTP:
                return new FtpCloudProvider(remote, password);
            default:
                throw new IllegalArgumentException("Unsupported: " + remote.getType());
        }
    }
}
