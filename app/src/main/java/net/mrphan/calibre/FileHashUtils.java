package net.mrphan.calibre;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Tinh SHA-256 cua 1 file local theo kieu streaming (buffer 64KB, khong doc het file vao RAM)
 * -- dung de so sanh voi contentHash da luu trong manifest (Phase 1) va bo qua upload khi noi
 * dung that su khong doi, du sourceMtime co the da doi (vd sau khi restore backup / copy file
 * bang cong cu khac Calibre-sync). Xem muc 3.4 cua prompt nang cap. */
public final class FileHashUtils {

    private static final int BUFFER_SIZE = 64 * 1024;

    private FileHashUtils() {}

    public static String sha256Hex(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 luon co san tren moi JVM/Android theo dac ta -- khong bao gio xay ra thuc
            // te, nhung boc lai thanh IOException de khong buoc moi caller phai bat checked
            // exception rieng cho truong hop khong the xay ra nay.
            throw new IOException("SHA-256 not available", e);
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hashBytes = digest.digest();
        StringBuilder sb = new StringBuilder(hashBytes.length * 2);
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
