package net.mrphan.calibre;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Lock + tiện ích đọc/ghi metadata.calibre dùng CHUNG giữa BookCrawler (rebuild toàn bộ file
 * từ SQLite, xem writeMetadataFile()) và DeviceBookMetadataWriter (patch đúng 1 entry, xem
 * Hiệu đính metadata) -- tránh 2 nơi tự ý ghi đè cùng lúc làm hỏng file hoặc mất dữ liệu của
 * nhau. Trước khi class này tồn tại, BookCrawler tự giữ một lock riêng (metadataFileLock,
 * private); giờ cả hai class dùng chung đúng 1 lock (LOCK bên dưới). */
final class MetadataFileIo {

    static final String METADATA_FILE_NAME = "metadata.calibre";

    /** Lock dùng chung cho MỌI thao tác đọc-sửa-ghi metadata.calibre trong cả app. */
    static final Object LOCK = new Object();

    private MetadataFileIo() {}

    /** Đọc toàn bộ mảng JSON hiện có trong rootDirectory/metadata.calibre. Trả về mảng rỗng
     * (không phải null) nếu file chưa tồn tại, để caller không cần tự kiểm tra null riêng. */
    static JSONArray readAll(File rootDirectory) throws IOException, JSONException {
        File f = new File(rootDirectory, METADATA_FILE_NAME);
        if (!f.exists()) return new JSONArray();
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[8192];
        int c;
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(f))) {
            while ((c = in.read(buf)) > 0) sb.append(new String(buf, 0, c, StandardCharsets.UTF_8));
        }
        return new JSONArray(sb.toString());
    }

    /** Ghi ATOMIC: ghi ra file tạm cùng thư mục rồi rename đè lên file gốc, không ghi đè trực
     * tiếp -- một crash/hết dung lượng giữa chừng sẽ không làm hỏng metadata.calibre gốc, vì
     * file gốc chỉ bị thay thế khi file tạm đã ghi xong hoàn chỉnh. Caller PHẢI giữ MetadataFileIo.LOCK
     * trong suốt chu kỳ đọc-sửa-ghi (readAll -> sửa in-memory -> writeAll) để 2 luồng không bao
     * giờ ghi đồng thời. */
    static void writeAll(File rootDirectory, JSONArray all) throws IOException {
        File dest = new File(rootDirectory, METADATA_FILE_NAME);
        File tmp = new File(rootDirectory, METADATA_FILE_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(all.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(dest)) {
            // renameTo có thể fail chéo filesystem trên vài thiết bị -- fallback copy+delete.
            copyFile(tmp, dest);
            tmp.delete();
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
