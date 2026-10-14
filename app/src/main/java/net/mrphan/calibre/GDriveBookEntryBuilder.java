package net.mrphan.calibre;

import android.content.Context;
import android.database.Cursor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.Locale;

/**
 * Dựng 1 {@link OPDSEntry} "tổng hợp" từ 1 dòng của {@link GDriveLibraryDb#getBookFullById}, y
 * hệt logic từng nằm trong {@code GDriveLibraryJsBridge#doOpenBookDetail} -- tách ra thành class
 * riêng, không phụ thuộc {@code Activity}/{@code WebView} nào, để dùng được ở 2 nơi:
 * <p>
 * 1. {@code GDriveLibraryJsBridge#doOpenBookDetail} -- mở preview lần đầu khi người dùng bấm vào
 *    1 thẻ sách trong WebView "Thư viện mini" (GDrive).
 * <p>
 * 2. {@code BookDetailActivity#resolveGdriveAdjacentEntry} -- vuốt trái/phải sang cuốn kế tiếp/
 *    trước đó trong CÙNG trang đang xem (xem BookDetailActivity#EXTRA_BOOK_LIST_GDRIVE_IDS), lúc
 *    này không có sẵn WebView/JS bridge nào để gọi lại, nên phải tự dựng entry ở đây.
 */
final class GDriveBookEntryBuilder {
    private GDriveBookEntryBuilder() {}

    /** @return entry đầy đủ (kèm bìa đã tải, nếu có) cho [bookId], hoặc null nếu bookId không
     * còn tồn tại trong DB (vd sách vừa bị xoá khỏi GDrive giữa lúc đang duyệt). */
    static OPDSEntry build(Context context, GDriveLibraryDb db, int port, int bookId) throws JSONException {
        Cursor c = db.getBookFullById(bookId);
        try {
            if (!c.moveToFirst()) return null;
            ensureBareMetadataRegistered(context, c);
            OPDSEntry entry = new OPDSEntry();
            entry.id = bookId;
            entry.uuid = c.isNull(c.getColumnIndexOrThrow("uuid")) ? null : c.getString(c.getColumnIndexOrThrow("uuid"));
            entry.title = c.getString(c.getColumnIndexOrThrow("title"));
            entry.author = c.getString(c.getColumnIndexOrThrow("authors"));
            entry.summary = c.getString(c.getColumnIndexOrThrow("comments"));
            entry.publisher = c.getString(c.getColumnIndexOrThrow("publisher"));
            // BUG FIX: neither of these was ever set before, so the preview's "Cập nhật lần
            // cuối" / "Ngày xuất bản" rows stayed hidden for every GDrive book regardless of
            // whether metadata_public.json actually had the data -- see GDriveLibraryDb#insertBook.
            entry.updated = c.isNull(c.getColumnIndexOrThrow("last_modified")) ? null
                    : c.getString(c.getColumnIndexOrThrow("last_modified"));
            entry.published = c.isNull(c.getColumnIndexOrThrow("pubdate")) ? null
                    : c.getString(c.getColumnIndexOrThrow("pubdate"));
            entry.series = c.isNull(c.getColumnIndexOrThrow("series")) ? null : c.getString(c.getColumnIndexOrThrow("series"));
            if (!c.isNull(c.getColumnIndexOrThrow("series_index"))) {
                entry.seriesIndex = c.getDouble(c.getColumnIndexOrThrow("series_index"));
            }
            if (!c.isNull(c.getColumnIndexOrThrow("rating"))) {
                int rawRating = c.getInt(c.getColumnIndexOrThrow("rating"));
                // OPDSEntry#rating ky vong thang do 0-5 (xem Javadoc cua no) -- metadata_public.json
                // thuong xuat thang "rating" tho cua Calibre (0-10, 2 don vi/sao), giong cach
                // OPDSLoader chuan hoa feed OPDS that su, nen chia 2 neu gia tri > 5.
                entry.rating = rawRating > 5 ? rawRating / 2.0 : rawRating;
            }
            try {
                JSONArray langs = new JSONArray(c.getString(c.getColumnIndexOrThrow("languages_json")));
                if (langs.length() > 0) entry.language = langs.optString(0, null);
            } catch (JSONException ignored) { }

            try {
                JSONArray tags = new JSONArray(c.getString(c.getColumnIndexOrThrow("tags_json")));
                String[] tagArr = new String[tags.length()];
                for (int i = 0; i < tags.length(); i++) tagArr[i] = tags.optString(i, "");
                entry.tags = tagArr;
            } catch (JSONException ignored) { }

            String isbn = c.getString(c.getColumnIndexOrThrow("isbn"));
            if (isbn != null && !isbn.isEmpty()) entry.identifiers.put("isbn", isbn);

            String baseUrl = "http://127.0.0.1:" + port + "/";
            boolean hasCover = c.getInt(c.getColumnIndexOrThrow("has_cover")) == 1;
            if (hasCover) {
                String coverUrl = baseUrl + "opds/cover/" + bookId;
                entry.href = coverUrl; // khong dung truc tiep de mo sach, chi de tham chieu
                entry.thumb = fetchCoverBytes(coverUrl);
            }

            JSONObject formats = new JSONObject(c.getString(c.getColumnIndexOrThrow("formats_json")));
            java.util.Iterator<String> keys = formats.keys();
            while (keys.hasNext()) {
                String code = keys.next();
                OPDSEntry.Format fmt = new OPDSEntry.Format();
                fmt.mimeType = mimeForFormatPublic(code);
                fmt.href = baseUrl + bookId + "/download/" + code.toLowerCase(Locale.US);
                entry.formats.add(fmt);
                entry.href = fmt.href; // href giu ban ghi cuoi cung, dung quy uoc cua OPDSEntry
            }
            return entry;
        } finally {
            c.close();
        }
    }

    /** Đăng ký (nếu chưa có) HOẶC bổ sung (nếu đã có nhưng còn thiếu field) 1 dòng trong
     * MetadataDatabaseHelper cho sách GDrive này, để "Tải"/"Đọc" sau đó có sẵn ĐẦY ĐỦ metadata --
     * không chỉ uuid/author/title/tags như trước (xem BUG FIX bên dưới).
     * <p>
     * context truyền vào thay vì field activity của GDriveLibraryJsBridge, để dùng được từ cả
     * BookDetailActivity (không có Activity của WebView).
     * <p>
     * BUG FIX: trước đây hàm này chỉ ghi "bare" uuid/author/title/tags/comments -- nút "Tải" trên
     * BookDetailActivity (OpenBookActivity#insertBook(uuid, lpath)) CHỈ update cột lpath, không
     * đụng field nào khác, nên 1 sách tải qua nút này vĩnh viễn thiếu cover/series/publisher/
     * pubdate/rating/languages/isbn dù metadata_public.json trên Drive có đủ. Giờ đăng ký ĐẦY ĐỦ
     * ngay từ bước này (trước khi người dùng bấm Tải), y hệt field mà
     * GDriveLibraryJsBridge#buildInsertJsonFor() đã đọc cho luồng WebView -- xem hàm đó để đối
     * chiếu tên cột/quy ước chuyển đổi trước khi sửa thêm. */
    private static void ensureBareMetadataRegistered(Context context, Cursor c) {
        try {
            String uuid = c.isNull(c.getColumnIndexOrThrow("uuid")) ? null : c.getString(c.getColumnIndexOrThrow("uuid"));
            if (uuid == null || uuid.isEmpty()) return;

            MetadataDatabaseHelper metaDb = new MetadataDatabaseHelper(context.getApplicationContext());
            try {
                Cursor existing = metaDb.getByUuid(new String[]{"_id", "cover_path", "thumbnail"}, uuid);
                boolean alreadyRegistered;
                boolean hasCoverLocally;
                try {
                    alreadyRegistered = existing.moveToFirst();
                    hasCoverLocally = alreadyRegistered
                            && (!existing.isNull(existing.getColumnIndexOrThrow("cover_path"))
                                || !existing.isNull(existing.getColumnIndexOrThrow("thumbnail")));
                } finally {
                    existing.close();
                }

                String title = c.getString(c.getColumnIndexOrThrow("title"));
                String authorSort = c.getString(c.getColumnIndexOrThrow("author_sort"));
                String authorsRaw = c.getString(c.getColumnIndexOrThrow("authors"));
                String author = (authorSort != null && !authorSort.isEmpty()) ? authorSort
                        : (authorsRaw != null ? authorsRaw : "");

                JSONArray tagsArr = new JSONArray(c.getString(c.getColumnIndexOrThrow("tags_json")));
                String[] tags = new String[tagsArr.length()];
                for (int i = 0; i < tagsArr.length(); i++) tags[i] = tagsArr.optString(i, "");

                String comments = c.getString(c.getColumnIndexOrThrow("comments"));

                // --- Các field mở rộng (trước đây bị bỏ qua hoàn toàn) ---
                String publisher = c.getString(c.getColumnIndexOrThrow("publisher"));
                if (publisher != null && publisher.isEmpty()) publisher = null;

                String pubdate = c.isNull(c.getColumnIndexOrThrow("pubdate")) ? null
                        : c.getString(c.getColumnIndexOrThrow("pubdate"));

                String series = c.isNull(c.getColumnIndexOrThrow("series")) ? null
                        : c.getString(c.getColumnIndexOrThrow("series"));
                Double seriesIndex = null;
                if (series != null && !c.isNull(c.getColumnIndexOrThrow("series_index"))) {
                    seriesIndex = c.getDouble(c.getColumnIndexOrThrow("series_index"));
                }

                // insertMetadata()/backfillExtendedMetadataIfMissing() chờ thang 0-5 (giống
                // insertJSON()) -- GDriveLibraryDb lưu nguyên thang 0-10 thô của Calibre, PHẢI
                // chia 2 ở đây (khác với buildInsertJsonFor(), nơi insertJSON() tự chia).
                Double rating = null;
                if (!c.isNull(c.getColumnIndexOrThrow("rating"))) {
                    double raw = c.getInt(c.getColumnIndexOrThrow("rating"));
                    rating = raw / 2.0;
                }

                String languagesCsv = null;
                JSONArray langs = new JSONArray(c.getString(c.getColumnIndexOrThrow("languages_json")));
                if (langs.length() > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < langs.length(); i++) {
                        if (i > 0) sb.append(',');
                        sb.append(langs.optString(i, ""));
                    }
                    languagesCsv = sb.toString();
                }

                String identifiersJson = null;
                String isbn = c.getString(c.getColumnIndexOrThrow("isbn"));
                if (isbn != null && !isbn.isEmpty()) {
                    JSONObject identifiers = new JSONObject();
                    identifiers.put("isbn", isbn);
                    identifiersJson = identifiers.toString();
                }

                Long dateAdded = null;
                if (!c.isNull(c.getColumnIndexOrThrow("timestamp"))) {
                    dateAdded = OPDSFormatUtils.parseIsoToEpochMillisOrNull(
                            c.getString(c.getColumnIndexOrThrow("timestamp")));
                }

                // Cover: lpath chưa tồn tại lúc này (sách có thể chưa từng tải) nên không thể ghi
                // ra covers/<lpath>.jpg qua CoverManager -- dùng đúng quy ước GDriveLibraryJsBridge
                // đã dùng cho tình huống "chưa có coverPath": tải cover thành BLOB rồi lưu vào cột
                // "thumbnail". Best-effort: lỗi mạng khi tải cover không được chặn việc đăng ký
                // metadata còn lại.
                byte[] coverThumbnail = null;
                boolean hasCover = c.getInt(c.getColumnIndexOrThrow("has_cover")) == 1;
                String coverFileId = c.getString(c.getColumnIndexOrThrow("cover_file_id"));
                if (hasCover && coverFileId != null && !hasCoverLocally) {
                    coverThumbnail = fetchDriveCoverBytes(coverFileId);
                }

                if (!alreadyRegistered) {
                    metaDb.insertMetadata(uuid, null, author, title, tags, coverThumbnail, null,
                            publisher, pubdate, series, seriesIndex, rating, languagesCsv,
                            identifiersJson, dateAdded, null);
                    if (comments != null && !comments.isEmpty()) {
                        metaDb.saveComments(uuid, comments);
                    }
                } else {
                    // Dòng đã tồn tại (vd sách đã từng tải, hoặc đã từng mở preview trước khi có
                    // fix này) -- CHỈ bổ sung field còn thiếu, không đụng lpath/trạng thái đã tải.
                    metaDb.backfillExtendedMetadataIfMissing(uuid, publisher, pubdate, series,
                            seriesIndex, rating, languagesCsv, identifiersJson);
                    if (comments != null && !comments.isEmpty()) {
                        metaDb.saveCommentsIfEmpty(uuid, comments);
                    }
                    if (coverThumbnail != null) {
                        metaDb.updateThumbnail(uuid, coverThumbnail);
                    }
                }
            } finally {
                metaDb.close();
            }
        } catch (Exception e) {
            Logger.e(e);
        }
    }

    /** Tải cover từ Google Drive (public API key, không cần OAuth) thành byte[], dùng để lưu tạm
     * vào cột "thumbnail" khi chưa có lpath để ghi ra file thật qua CoverManager. Trả về null
     * (không ném lỗi) nếu thất bại -- lỗi tải cover không được chặn việc đăng ký metadata còn lại. */
    private static byte[] fetchDriveCoverBytes(String coverFileId) {
        try {
            GDrivePublicFileClient fileClient = new GDrivePublicFileClient(new okhttp3.OkHttpClient());
            okhttp3.Response response = fileClient.openStream(coverFileId, null);
            try {
                return response.body() != null ? response.body().bytes() : null;
            } finally {
                response.close();
            }
        } catch (Exception e) {
            Logger.e(e);
            return null;
        }
    }

    /** Xem javadoc goc o GDriveLibraryJsBridge#fetchCoverBytes -- tra ve null (khong nem loi)
     * neu that bai, de sach van mo duoc voi placeholder thay vi crash. */
    private static byte[] fetchCoverBytes(String coverUrl) {
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection) new java.net.URL(coverUrl).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.connect();
            if (connection.getResponseCode() != java.net.HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = connection.getInputStream();
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                return out.toByteArray();
            }
        } catch (Exception e) {
            Logger.e(e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String mimeForFormatPublic(String code) {
        switch (code.toUpperCase(Locale.US)) {
            case "EPUB": return "application/epub+zip";
            case "PDF": return "application/pdf";
            case "MOBI": return "application/x-mobipocket-ebook";
            case "AZW3": return "application/vnd.amazon.mobi8-ebook";
            case "AZW": return "application/vnd.amazon.ebook";
            case "FB2": return "application/fb2";
            case "CBZ": return "application/vnd.comicbook+zip";
            case "CBR": return "application/vnd.comicbook-rar";
            case "TXT": return "text/plain";
            case "RTF": return "application/rtf";
            case "DOC": return "application/msword";
            case "HTML": case "HTM": return "text/html";
            case "MP3": return "audio/mpeg";
            case "M4B": return "audio/x-m4b";
            case "M4A": return "audio/mp4";
            case "OGG": return "audio/ogg";
            case "FLAC": return "audio/flac";
            case "WAV": return "audio/wav";
            default: return "application/octet-stream";
        }
    }
}
