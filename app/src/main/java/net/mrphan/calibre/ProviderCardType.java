package net.mrphan.calibre;

public enum ProviderCardType {
    GOOGLE_DRIVE,        // real functionality -- PHẦN 3
    ONEDRIVE,            // real functionality -- Microsoft Graph OAuth
    BOX,                 // real functionality -- Box Content API v2.0 OAuth
    DROPBOX,             // real functionality -- Dropbox API v2 OAuth (was PLACEHOLDER_NEXTCLOUD's slot)
    GDRIVE_OPDS_LIBRARY, // real functionality -- public Calibre library on GDrive, no OAuth
    CONTENT_SERVER,      // real functionality -- opens ContentServerBrowserActivity
    WEBDAV_FTP,          // real functionality -- opens AddCloudRemoteDialogFragment / remote list
    PLACEHOLDER_PCLOUD,
    PLACEHOLDER_OWNCLOUD,
    PLACEHOLDER_LOCAL_STORAGE
}
