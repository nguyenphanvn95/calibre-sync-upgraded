package net.mrphan.calibre;

public class GDriveFileException extends Exception {
    public final GDriveFileErrorType type;

    public GDriveFileException(GDriveFileErrorType type, String message) {
        super(message);
        this.type = type;
    }
}
