package net.mrphan.calibre;

/** Lý do một request tới Google Drive public API thất bại — dùng để hiện thông báo cụ thể thay
 * vì "network error" chung chung. Đối xứng với GDriveFileErrorType bên bản Flutter
 * (gdrive_public_file_service.dart) để giữ đúng ngữ nghĩa lỗi giữa 2 app. */
public enum GDriveFileErrorType {
    INVALID_FILE_ID,
    NOT_FOUND,
    NOT_PUBLIC,
    QUOTA_EXCEEDED,
    NETWORK,
    UNKNOWN
}
