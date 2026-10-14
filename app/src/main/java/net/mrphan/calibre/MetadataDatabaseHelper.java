package net.mrphan.calibre;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

@SuppressLint("NewApi")
public class MetadataDatabaseHelper extends SQLiteOpenHelper {

	private final static int DATABASE_VERSION 		=	17;
	private final static String DATABASE_NAME		= "calibre.metadata.db";
	private final static String TABLE_METADATA		= "metadata";
	private final static String TABLE_BOOK_TAG		= "book_tag";
	private final static String TABLE_TAGS			= "tags";
	private final static String TABLE_READING_TIME	= "reading_time";
	// Bookmarks and highlights/notes -- see the CREATE_TABLES entry below for the schema
	// reasoning. Introduced in DATABASE_VERSION 8 for the Bookmark/Highlight/Note reader feature.
	private final static String TABLE_ANNOTATIONS	= "reader_annotations";
	// Tien trinh nghe TTS theo tung chuong EPUB, doc lap voi last_locator (vi tri doc chu).
	// Introduced in DATABASE_VERSION 11 cho tinh nang "Nghe sach" tu danh sach chuong. Xem
	// TtsChapterProgressStore.
	private final static String TABLE_TTS_PROGRESS	= "tts_chapter_progress";
	// Danh sach "Tu dien phat am" cho TTS: cap (source_text -> replacement_text), ap dung
	// TOAN CUC cho moi sach (khong co cot uuid), vi ten rieng nhu "David" co the xuat hien
	// o nhieu sach khac nhau. Introduced in DATABASE_VERSION 12. Xem
	// TtsDictionaryStore/TtsDictionaryReplacer/TtsController#startChapter.
	private final static String TABLE_TTS_DICTIONARY	= "tts_pronunciation_dictionary";
	// Dinh nghia + gia tri Custom Column cua Calibre Desktop (vd "#genre", "#myrating"), dang EAV
	// vi so luong/kieu cot khac nhau giua tung thu vien cua tung nguoi dung, khong the ALTER TABLE
	// tinh truoc. Introduced in DATABASE_VERSION 13, cung luc voi cac cot field-chuan moi tren
	// TABLE_METADATA (publisher/pubdate/series/...) o duoi. Xem CustomColumnSync.
	private final static String TABLE_CUSTOM_COLUMN_DEFS	= "custom_column_defs";
	private final static String TABLE_CUSTOM_COLUMN_VALUES	= "custom_column_values";
	// Thoi gian doc THEO TUNG NGAY, gop tat ca sach (khac voi TABLE_READING_TIME o tren la theo
	// tung chuong/trang cua 1 cuon sach cu the, khong co moc ngay, khong the tra loi "hom qua doc
	// bao nhieu phut"). Phuc vu man hinh "Tien trinh" (ProgressActivity): bieu do theo ngay/tuan/
	// thang/nam, chuoi ngay lien tuc (streak), muc tieu hang ngay -- KHONG phu thuoc sach nao con
	// trong thu vien (sach co the bi xoa/sua ma khong lam mat lich su doc). Introduced in
	// DATABASE_VERSION 17. Xem DailyReadingStatsStore.
	private final static String TABLE_DAILY_STATS	= "daily_reading_stats";



	private final String[] CREATE_TABLES = { 
			"CREATE TABLE " + TABLE_METADATA + "(_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL UNIQUE, uuid TEXT UNIQUE, lpath TEXT UNIQUE, author TEXT, title TEXT, thumbnail BLOB, last_locator TEXT, cover_path TEXT, comments TEXT, last_locator_updated_at INTEGER DEFAULT 0, has_local_file INTEGER NOT NULL DEFAULT 0)",
			"CREATE TABLE " + TABLE_BOOK_TAG + "(metadata_id INTEGER, tag_id INTEGER, PRIMARY KEY (metadata_id, tag_id))",
			"CREATE TABLE " + TABLE_TAGS + "(_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL UNIQUE,tag TEXT UNIQUE)",
			// Real, per-book/per-chapter (EPUB) or per-page (PDF/CBZ) accumulated actual reading
			// time in milliseconds, tracked by net.mrphan.calibre.reader.stats.ReadingTimeTracker
			// and consumed by ReadingStatsDialog so "Thống kê" (reading stats) shows genuine
			// measured numbers instead of a permanent "Đang tính toán..." placeholder.
			"CREATE TABLE " + TABLE_READING_TIME + "(uuid TEXT NOT NULL, unit_key TEXT NOT NULL, elapsed_ms INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (uuid, unit_key))",
			// Bookmark va highlight/note cua nguoi doc, dung chung mot bang vi ca hai deu la mot
			// vi tri trong sach (locator_json, serialize giong het cach saveLastLocator dang
			// lam) kem metadata tuy loai. "type" phan biet 'bookmark' (khong co color/note) voi
			// 'highlight' (co color, co the co note kem theo). uuid tham chieu ve metadata.uuid
			// nhu moi bang khac trong file nay, khong dung FOREIGN KEY cung de giu nguyen phong
			// cach hien tai cua file (khong bang nao khac trong CREATE_TABLES dung FOREIGN KEY
			// ca).
			// deleted_at: NULL = annotation dang song. Khac NULL = "tombstone" -- annotation da
			// bi nguoi dung xoa (thoi diem xoa), nhung van GIU LAI hang (khong DELETE that su)
			// de dong bo con biet ma xoa o may khac / o cloud thay vi khi tai lai
			// calibre_sync_progress.json (van con annotation do vi may nay chua kip upload lai
			// sau khi xoa) lai VO TINH THEM LAI annotation da bi nguoi dung xoa chu dich.
			// Xem SyncProgress.AnnotationEntry#deleted + MetadataDatabaseHelper#removeAnnotation()
			// (DATABASE_VERSION 15).
			"CREATE TABLE " + TABLE_ANNOTATIONS + "(_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL UNIQUE, " +
				"uuid TEXT NOT NULL, type TEXT NOT NULL, locator_json TEXT NOT NULL, color TEXT, " +
				"note TEXT, excerpt TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, " +
				"deleted_at INTEGER)",
			// Tien trinh nghe TTS theo tung chuong EPUB, doc lap voi last_locator (vi tri doc
			// chu). sentence_index/sentence_count la vi tri cau dang/da nghe trong chuong do
			// (xem TtsPlaybackState.sentenceIndex/sentenceCount). uuid tham chieu metadata.uuid,
			// cung khong dung FOREIGN KEY de giu nguyen phong cach cac bang khac trong file nay.
			"CREATE TABLE " + TABLE_TTS_PROGRESS + "(uuid TEXT NOT NULL, chapter_href TEXT NOT NULL, " +
				"sentence_index INTEGER NOT NULL, sentence_count INTEGER NOT NULL, " +
				"updated_at INTEGER NOT NULL, PRIMARY KEY (uuid, chapter_href))",
			// Tu dien phat am TTS, toan cuc cho moi sach. COLLATE NOCASE tren source_text de
			// tranh nguoi dung vo tinh them ca "David" lan "david" thanh 2 dong khac nhau -
			// khong hoan hao voi dau tieng Viet nhung dung voi truong hop pho bien nhat la
			// ten rieng goc Latin/English (xem TtsDictionaryReplacer kdoc).
			"CREATE TABLE " + TABLE_TTS_DICTIONARY + "(_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL UNIQUE, " +
				"source_text TEXT NOT NULL UNIQUE COLLATE NOCASE, replacement_text TEXT NOT NULL, " +
				"updated_at INTEGER NOT NULL)",
			// Dinh nghia 1 custom column, hoc duoc tu khoi "user_metadata" ma Calibre Desktop gui
			// kem moi cuon sach dong bo (xem CustomColumnSync#parseFromWireJson). lookup_name
			// ("#genre") la khoa on dinh Calibre dat cho cot nay, KHONG doi ngay ca khi nguoi dung
			// doi ten hien thi (label). Introduced in DATABASE_VERSION 13.
			"CREATE TABLE " + TABLE_CUSTOM_COLUMN_DEFS + "(" +
					"lookup_name TEXT PRIMARY KEY NOT NULL, " +
					"label TEXT NOT NULL, " +
					"datatype TEXT NOT NULL, " +
					"is_multiple INTEGER NOT NULL DEFAULT 0, " +
					"display_json TEXT, " +
					"sort_order INTEGER NOT NULL DEFAULT 0)",
			// Gia tri thuc te cua 1 custom column cho 1 cuon sach. Khong dung FOREIGN KEY, giu
			// dung quy uoc hien tai cua file nay (khong bang nao trong CREATE_TABLES dung FOREIGN
			// KEY ca) -- don dep thu cong khi can (xem TABLE_BOOK_TAG cung khong dung FK). Xem
			// CustomColumnSync.ColumnValue cho y nghia value_text/value_num.
			"CREATE TABLE " + TABLE_CUSTOM_COLUMN_VALUES + "(" +
					"metadata_id INTEGER NOT NULL, " +
					"lookup_name TEXT NOT NULL, " +
					"value_text TEXT, " +
					"value_num REAL, " +
					"PRIMARY KEY (metadata_id, lookup_name))",
			// 4 index them cho ban cai moi (nguoi dung cai lan dau chay thang onCreate(), khong
			// di qua onUpgrade()) -- phai giu dung noi dung voi case 13 trong onUpgrade() o tren.
			// CHI duoc append vao CUOI mang nay, KHONG chen vao giua -- nhieu case migration cu
			// tham chieu index co dinh nhu CREATE_TABLES[5]/CREATE_TABLES[6].
			"CREATE INDEX idx_metadata_author ON " + TABLE_METADATA + "(author)",
			"CREATE INDEX idx_metadata_title ON " + TABLE_METADATA + "(title)",
			"CREATE INDEX idx_metadata_last_locator_updated ON " + TABLE_METADATA + "(last_locator_updated_at)",
			"CREATE INDEX idx_book_tag_tag_id ON " + TABLE_BOOK_TAG + "(tag_id)",

			// TABLE_DAILY_STATS (xem khai bao TABLE_DAILY_STATS o tren) -- them vao CUOI mang nay
			// (index 13), dung quy uoc "chi append vao cuoi, khong chen vao giua" da ghi o comment
			// ngay phia tren cac dong CREATE INDEX nay, de khong lam lech cac case migration cu
			// dang tham chieu index co dinh (vd CREATE_TABLES[5]/[6]).
			// day_epoch la so ngay ke tu 1970-01-01 theo GIO DIA PHUONG cua nguoi dung
			// (java.time.LocalDate.toEpochDay()), KHONG dung timestamp UTC truc tiep de tranh sai
			// lech "ngay" khi doi mui gio/qua nua dem theo gio dia phuong nguoi dung.
			// word_count: so tu uoc luong da doc duoc trong ngay do (tu StatUnit.weight cua chuong
			// dang doc EPUB x ty le tien do; PDF/CBZ khong co word count nen luon cong 0), dung de
			// tinh "Toc do doc" (tu/phut) = word_count / (reading_ms/60000).
			// translation_count: so lan nguoi dung bam "Dich" thanh cong trong LookupBottomSheetFragment
			// trong ngay do, dung de tinh "Tan suat dich" = translation_count / (word_count/100).
			"CREATE TABLE " + TABLE_DAILY_STATS + "(day_epoch INTEGER PRIMARY KEY NOT NULL, " +
				"reading_ms INTEGER NOT NULL DEFAULT 0, " +
				"word_count INTEGER NOT NULL DEFAULT 0, " +
				"translation_count INTEGER NOT NULL DEFAULT 0)",
			"CREATE INDEX idx_daily_stats_day_epoch ON " + TABLE_DAILY_STATS + "(day_epoch)",

	};

	public MetadataDatabaseHelper(Context context) {
		super(context, DATABASE_NAME, null, DATABASE_VERSION);

	}

	@Override
	public void onCreate(SQLiteDatabase db) {
		for(String stmt : CREATE_TABLES) db.execSQL(stmt);			
	}


	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		for (int i=oldVersion; i<newVersion; i++) {
			switch (i) {
			//case 3 means upgrading from version 3 to version 4, and so on
			case 2:
				db.execSQL("DROP TABLE IF EXISTS " + TABLE_METADATA);
				db.execSQL(CREATE_TABLES[0]); //TABLE_METADATA
				break;
			case 3:
				db.execSQL(CREATE_TABLES[1]); //TABLE BOOK_TAG
				db.execSQL(CREATE_TABLES[2]); //TABLE TAGS
				break;
			case 4:
				// Added for the in-app EPUB/PDF/CBZ reader: stores the serialized Readium
				// Locator (EPUB) or a small JSON blob {"type":"pdf_page"/"cbz_page","page":N}
				// (PDF/CBZ) of the last-read position, so re-opening a book jumps back to
				// where the user left off. Additive column, does not touch existing rows.
				db.execSQL("ALTER TABLE " + TABLE_METADATA + " ADD COLUMN last_locator TEXT");
				break;
			case 5:
				// Real per-chapter/per-page actual reading time, backing the upgraded
				// "Thống kê" (reading stats) dialog. See TABLE_READING_TIME above.
				db.execSQL(CREATE_TABLES[3]); //TABLE_READING_TIME
				break;
			case 6:
				// Đường dẫn tương đối (so với rootDirectory) tới file cover đã trích xuất ra đĩa,
				// ví dụ "covers/Truyen/ABC/tap-1.jpg". NULL nghĩa là chưa có cover trên đĩa (sẽ
				// được BookCrawler tự động lấp đầy ở lần scan tiếp theo, hoặc sách này không có
				// cover trong file gốc). See CoverManager.
				db.execSQL("ALTER TABLE " + TABLE_METADATA + " ADD COLUMN cover_path TEXT");
				break;
			case 7:
				// Bookmark/highlight/note cho reader (xem CREATE_TABLES[4] o tren).
				db.execSQL(CREATE_TABLES[4]); //TABLE_ANNOTATIONS
				break;
			case 8:
				// Mo ta/comment cua sach (Calibre "comments" field), dung de hien thi
				// doan tom tat trong Device Library o che do List, giong Readest.
				// Additive column, khong dong den du lieu cu.
				db.execSQL("ALTER TABLE " + TABLE_METADATA + " ADD COLUMN comments TEXT");
				break;
			case 9:
				// Timestamp cua lan cap nhat last_locator gan nhat -- can cho
				// DeviceLibrarySyncManager de quyet dinh "moi nhat thang" khi merge tien trinh
				// doc giua nhieu thiet bi. Additive column, khong dong den du lieu cu (cac hang
				// cu se co gia tri 0).
				db.execSQL("ALTER TABLE " + TABLE_METADATA + " ADD COLUMN last_locator_updated_at INTEGER DEFAULT 0");
				break;
			case 10:
				// Tien trinh nghe TTS theo tung chuong (xem CREATE_TABLES[5]/TABLE_TTS_PROGRESS
				// o tren), doc lap voi last_locator.
				// FIX: truoc day dung CREATE_TABLES[CREATE_TABLES.length - 1] de lay bang nay,
				// dung tai thoi diem do vi TTS_PROGRESS la phan tu cuoi mang. Sau khi
				// TABLE_TTS_DICTIONARY duoc them vao cuoi mang (index 6) o case 11 ben duoi,
				// "length - 1" tro thanh index 6 (TTS_DICTIONARY) thay vi index 5
				// (TTS_PROGRESS) nhu y dinh ban dau. Hau qua: case 10 vo tinh tao TABLE_TTS_
				// DICTIONARY truoc, roi case 11 tao lai chinh bang do -> SQLiteException
				// "table tts_pronunciation_dictionary already exists" moi lan mo CollectionFragment
				// tren cac may dang o oldVersion < 10. Dung index co dinh (5) de tranh loi nay
				// tai dien trong tuong lai neu mang CREATE_TABLES tiep tuc duoc noi dai.
				db.execSQL(CREATE_TABLES[5]); //TABLE_TTS_PROGRESS
				break;
			case 11:
				// Tu dien phat am TTS (xem CREATE_TABLES[6]/TABLE_TTS_DICTIONARY o tren). Dung
				// index tuong minh (6), khong dung "length - 1" nhu case 10 o tren, de khong bi
				// lech neu sau nay co nguoi them bang khac vao giua mang CREATE_TABLES.
				db.execSQL(CREATE_TABLES[6]); //TABLE_TTS_DICTIONARY
				break;
			case 12:
				// Cac field chuan cua Calibre ma insertJSON() truoc day khong luu (Calibre Desktop
				// da gui san trong JSON dong bo, va BookMetadataForm/DeviceBookMetadataWriter da
				// doc/ghi dung tren metadata.calibre, nhung SQLite chua co cho luu). Additive,
				// khong dong toi du lieu cu (NULL cho moi hang da ton tai). Xem insertJSON()/
				// insertMetadata()/exportAllBooksAsJSON() cho phan doc/ghi.
				db.execSQL("ALTER TABLE " + TABLE_METADATA + " ADD COLUMN publisher TEXT");
				db.execSQL("ALTER TABLE " + TABLE_METADATA + " ADD COLUMN pubdate TEXT"); // ISO-8601
				db.execSQL("ALTER TABLE " + TABLE_METADATA + " ADD COLUMN series TEXT");
				db.execSQL("ALTER TABLE " + TABLE_METADATA + " ADD COLUMN series_index REAL");
				db.execSQL("ALTER TABLE " + TABLE_METADATA + " ADD COLUMN rating REAL"); // thang 0-5
				db.execSQL("ALTER TABLE " + TABLE_METADATA + " ADD COLUMN languages TEXT"); // CSV "vi,en"
				// JSON object {"isbn":"...","asin":"..."} -- giu nguyen dang JSON thay vi tach
				// bang rieng, vi identifiers hau nhu chi dung de hien thi/tim kiem, khong can
				// join/sort.
				db.execSQL("ALTER TABLE " + TABLE_METADATA + " ADD COLUMN identifiers_json TEXT");
				// Thoi diem sach duoc them vao thu vien Calibre Desktop (Calibre "timestamp"
				// field), tach rieng voi last_locator_updated_at (thoi diem cap nhat tien trinh
				// doc tren MAY NAY).
				db.execSQL("ALTER TABLE " + TABLE_METADATA + " ADD COLUMN date_added INTEGER");
				// Kich thuoc file (bytes), tu field "size" trong JSON dong bo -- truoc day bi bo
				// qua hoan toan.
				db.execSQL("ALTER TABLE " + TABLE_METADATA + " ADD COLUMN file_size INTEGER");
				db.execSQL(CREATE_TABLES[7]); //TABLE_CUSTOM_COLUMN_DEFS
				db.execSQL(CREATE_TABLES[8]); //TABLE_CUSTOM_COLUMN_VALUES
				break;
			case 13:
				// Index cho cac cot dung de sort/filter thuong xuyen nhat (xem upgrade_1.md muc
				// 1.4). Truoc day chi co index ngam dinh tu PRIMARY KEY/UNIQUE (_id, uuid, lpath,
				// tag) -- sort theo author/title va tra cuu "tat ca sach co tag X" (tag_id trong
				// book_tag, PRIMARY KEY hien tai la (metadata_id, tag_id) nen tra theo metadata_id
				// nhanh nhung tra nguoc theo tag_id phai full scan) deu khong co index rieng.
				// Additive, khong dong toi du lieu cu.
				db.execSQL("CREATE INDEX IF NOT EXISTS idx_metadata_author ON " + TABLE_METADATA + "(author)");
				db.execSQL("CREATE INDEX IF NOT EXISTS idx_metadata_title ON " + TABLE_METADATA + "(title)");
				db.execSQL("CREATE INDEX IF NOT EXISTS idx_metadata_last_locator_updated ON "
						+ TABLE_METADATA + "(last_locator_updated_at)");
				db.execSQL("CREATE INDEX IF NOT EXISTS idx_book_tag_tag_id ON " + TABLE_BOOK_TAG + "(tag_id)");
				break;
			case 14:
				// BUG FIX: bookmark/highlight/note bi NHAN DOI moi lan dong bo, va bookmark da
				// xoa cuc bo bi dong bo "keo lai" thay vi duoc cong nhan la da xoa. Nguyen nhan:
				// (1) SyncProgress#applyRemoteNewerIntoLocal() chi tra existingId cho type
				// "bookmark" (`"bookmark".equals(a.type) ? db.findBookmarkId(...) : -1`) -- moi
				// highlight/note tu cloud deu bi coi la "chua co", nen addAnnotation() moi duoc
				// goi lai moi lan dong bo du annotation do da ton tai roi; (2) removeAnnotation()
				// truoc day DELETE that su khoi SQLite -- khong con dau vet gi de
				// SyncProgress#mergeLocalInto() biet annotation nay VUA bi xoa chu dich (khac voi
				// "chua bao gio ton tai o may nay"), nen lan sync ke tiep merge lai annotation cu
				// tu cloud vao local nhu binh thuong. Them cot deleted_at (NULL = con song, khac
				// NULL = tombstone) de removeAnnotation() chuyen sang "xoa mem" (giu hang, chi
				// danh dau + cap nhat updated_at) thay vi DELETE that su -- deleted_at cung duoc
				// dong bo len cloud (SyncProgress.AnnotationEntry#deleted) de cac may khac ap
				// dung xoa theo, giong nguyen tac tombstone SyncManifest da dung cho sach bi xoa.
				// Additive, khong dong toi du lieu cu (annotation cu mac dinh deleted_at = NULL,
				// tuc con song).
				db.execSQL("ALTER TABLE " + TABLE_ANNOTATIONS + " ADD COLUMN deleted_at INTEGER");
				break;
			case 15:
				// BUG FIX: DeviceLibrarySyncManager#uploadBooks() coi MOI hang co lpath=NULL
				// trong TABLE_METADATA la "sach da bi xoa chu dich khoi thiet bi nay", roi
				// tombstone + XOA VAT LY thu muc books/<uuid>/ tren remote. Nhung lpath=NULL
				// cung la gia tri duoc GDriveBookEntryBuilder/OPDSLoader dung de cache metadata
				// "xem truoc" cho sach CHUA TUNG duoc tai ve may nay (duyet Thu vien GDrive mini
				// hoac tab OPDS) -- 2 tinh huong nay dung chung 1 dieu kien "lpath IS NULL" nen
				// bi nham lan: nguoi dung vua day sach len Device Library bang plugin desktop,
				// mo xem truoc sach do qua Thu vien GDrive mini tren dien thoai (chi de cache
				// metadata, KHONG tai file), roi bam dong bo Device Library -> sach bi tombstone
				// va thu muc cua no tren remote bi xoa mat, du chua he ton tai cuc bo.
				// Them cot has_local_file (1 = hang nay tung duoc gan lpath THAT qua BookCrawler/
				// insertJSON, tuc sach dung la mot phan cua Device Library tren may nay; 0 = chi
				// la cache "xem truoc" tu duyet thu vien khac, chua bao gio thuc su co file cuc
				// bo) de getAllUuidsWithNullLpathArray() phan biet duoc 2 truong hop, chi coi la
				// "da xoa" khi has_local_file=1. Backfill has_local_file=1 cho moi hang dang co
				// lpath (con song) de khong lam mat kha nang phat hien xoa that cua sach hien co.
				db.execSQL("ALTER TABLE " + TABLE_METADATA + " ADD COLUMN has_local_file INTEGER NOT NULL DEFAULT 0");
				db.execSQL("UPDATE " + TABLE_METADATA + " SET has_local_file=1 WHERE lpath IS NOT NULL");
				break;
			case 16:
				// Bang thong ke doc theo ngay cho man hinh "Tien trinh" (ProgressActivity) --
				// xem TABLE_DAILY_STATS o tren. Dung index co dinh 13/14 (vi tri thuc te cua
				// TABLE_DAILY_STATS va idx_daily_stats_day_epoch trong mang CREATE_TABLES sau
				// khi them vao CUOI mang: 0-8 la 9 CREATE TABLE, 9-12 la 4 CREATE INDEX cu, nen
				// TABLE_DAILY_STATS = 13 va idx_daily_stats_day_epoch = 14) -- da dem lai thu
				// cong theo dung canh bao trong prompt nang cap (xem case 10 o tren, day la loi
				// tung xay ra that trong file nay).
				db.execSQL(CREATE_TABLES[13]); //TABLE_DAILY_STATS
				db.execSQL(CREATE_TABLES[14]); //idx_daily_stats_day_epoch
				break;
			}
		}
	}

	/**
	 * FIX (lỗi "Lưu thất bại: no such column: rating" ở màn "Hiệu đính metadata" trên bản
	 * release): một số máy đã có file calibre.metadata.db với PRAGMA user_version >= 13 từ một
	 * bản build CŨ hơn, ở thời điểm case 12 (ALTER TABLE thêm publisher/pubdate/series/
	 * series_index/rating/languages/identifiers_json/date_added/file_size) trong onUpgrade()
	 * bên trên CHƯA tồn tại trong code lúc đó. SQLiteOpenHelper chỉ chạy các case của
	 * onUpgrade() nằm giữa oldVersion (đọc từ chính file .db, không phải từ APK) và newVersion
	 * -- vì oldVersion trên những máy này đã >= 13, case 12 không bao giờ được chạy lại dù
	 * DATABASE_VERSION hiện tại đã lên 15, nên "rating" (và các cột liên quan) vĩnh viễn bị
	 * thiếu, khiến mọi UPDATE metadata SET ...,rating=?,... (updateMetadataFields(), dùng bởi
	 * Hiệu đính) văng SQLiteException. Cùng cơ chế có thể khiến "deleted_at" (case 14) bị thiếu.
	 * <p>
	 * Thay vì chỉ tin tưởng oldVersion/newVersion, tự kiểm tra + tự vá schema mỗi lần mở CSDL
	 * (PRAGMA table_info, idempotent, gần như không tốn chi phí) để những máy đã "kẹt" ở migration
	 * cũ này tự phục hồi ngay từ lần mở app tiếp theo, không cần xoá cache/cài lại.
	 */
	@Override
	public void onOpen(SQLiteDatabase db) {
		super.onOpen(db);
		if (db.isReadOnly()) return;
		try {
			addColumnIfMissing(db, TABLE_METADATA, "last_locator", "TEXT");
			addColumnIfMissing(db, TABLE_METADATA, "cover_path", "TEXT");
			addColumnIfMissing(db, TABLE_METADATA, "comments", "TEXT");
			addColumnIfMissing(db, TABLE_METADATA, "last_locator_updated_at", "INTEGER DEFAULT 0");
			addColumnIfMissing(db, TABLE_METADATA, "publisher", "TEXT");
			addColumnIfMissing(db, TABLE_METADATA, "pubdate", "TEXT");
			addColumnIfMissing(db, TABLE_METADATA, "series", "TEXT");
			addColumnIfMissing(db, TABLE_METADATA, "series_index", "REAL");
			addColumnIfMissing(db, TABLE_METADATA, "rating", "REAL");
			addColumnIfMissing(db, TABLE_METADATA, "languages", "TEXT");
			addColumnIfMissing(db, TABLE_METADATA, "identifiers_json", "TEXT");
			addColumnIfMissing(db, TABLE_METADATA, "date_added", "INTEGER");
			addColumnIfMissing(db, TABLE_METADATA, "file_size", "INTEGER");
			addColumnIfMissing(db, TABLE_ANNOTATIONS, "deleted_at", "INTEGER");
			if (!columnExists(db, TABLE_METADATA, "has_local_file")) {
				db.execSQL("ALTER TABLE " + TABLE_METADATA + " ADD COLUMN has_local_file INTEGER NOT NULL DEFAULT 0");
				db.execSQL("UPDATE " + TABLE_METADATA + " SET has_local_file=1 WHERE lpath IS NOT NULL");
				Logger.d("MetadataDatabaseHelper: tu vac them cot con thieu " + TABLE_METADATA + ".has_local_file");
			}
		} catch (Exception e) {
			// Khong de loi tu-va schema lam sap ung dung -- neu that bai, hanh vi cu (co the
			// van con thieu cot) van giu nguyen nhu truoc khi co fix nay.
			Logger.e(e);
		}
	}

	/** @return true neu bang {@code table} da co san cot {@code column} (so sanh khong phan
	 * biet hoa/thuong, giong quy uoc cua chinh SQLite). Doc qua PRAGMA table_info thay vi bat
	 * SQLiteException tu mot cau lenh thu, de khong lam ban ban ghi log loi khi kiem tra binh
	 * thuong (truong hop pho bien nhat: cot da ton tai). */
	private boolean columnExists(SQLiteDatabase db, String table, String column) {
		Cursor c = null;
		try {
			c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
			int nameIdx = c.getColumnIndex("name");
			while (c.moveToNext()) {
				if (column.equalsIgnoreCase(c.getString(nameIdx))) return true;
			}
		} finally {
			if (c != null) c.close();
		}
		return false;
	}

	/** Them cot {@code column} kieu {@code typeDef} vao {@code table} neu chua co -- idempotent,
	 * an toan goi lai moi lan onOpen(). Khong dong toi du lieu cac hang da co (hang cu nhan gia
	 * tri NULL cho cot moi them, dung ngu nghia ALTER TABLE ADD COLUMN cua SQLite). */
	private void addColumnIfMissing(SQLiteDatabase db, String table, String column, String typeDef) {
		if (!columnExists(db, table, column)) {
			db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + typeDef);
			Logger.d("MetadataDatabaseHelper: tu vac them cot con thieu " + table + "." + column);
		}
	}

	/**
	 * @param uuid
	 * @param lpath
	 * @param author
	 * @param title
	 * @param tags 
	 * @param thumbnail
	 * @param coverPath đường dẫn tương đối (so với rootDirectory) tới cover đã cache ra đĩa
	 *                  (xem CoverManager), hoặc null nếu chưa có -- chỉ có ý nghĩa cho sách quét
	 *                  từ thư mục cục bộ (Device Library); sách tới từ Calibre Library (OPDS) hay
	 *                  đồng bộ qua desktop luôn truyền null ở đây.
	 * @return
	 */
	public int insertMetadata(String uuid, String lpath, String author, String title, String[] tags, byte[] thumbnail, String coverPath) {
		return insertMetadata(uuid, lpath, author, title, tags, thumbnail, coverPath,
				null, null, null, null, null, null, null, null, null);
	}

	/** Ban day du hon cua insertMetadata() o tren, nhan them cac field chuan cua Calibre truoc
	 * day bi insertJSON() danh roi (xem migration DATABASE_VERSION 13). Moi tham so tu
	 * publisher tro di deu OPTIONAL (truyen null neu Calibre khong gui/khong biet) va theo dung
	 * nguyen tac "khong ghi de" giong coverPath: chi cv.put(...) khi != null, de db.update() tren
	 * duong "da ton tai" khong xoa mat gia tri cu neu lan goi nay khong co du lieu moi. */
	public int insertMetadata(String uuid, String lpath, String author, String title, String[] tags, byte[] thumbnail, String coverPath,
			String publisher, String pubdate, String series, Double seriesIndex,
			Double rating, String languagesCsv, String identifiersJson,
			Long dateAdded, Long fileSize) {
		ContentValues cv = new ContentValues();
		SQLiteDatabase db = this.getWritableDatabase();
		int metadata_id = -1;
		int tag_id;
		try {
			if (thumbnail != null) {
				cv.put("thumbnail",thumbnail);
			} else {
				cv.putNull("thumbnail");
			}
			cv.put("uuid", uuid);
			if (lpath != null) {
				cv.put("lpath", lpath);
				// Hang nay dung la mot phan cua Device Library tren may nay (co file cuc bo
				// that su), khong phai cache "xem truoc" tu duyet thu vien GDrive/OPDS -- xem
				// javadoc cot has_local_file (migration DATABASE_VERSION 16) va
				// getAllUuidsWithNullLpathArray(). KHONG putNull khi lpath==null: giu nguyen gia
				// tri cu (0 hoac 1 tu truoc) thay vi lai coi mot cache-only insert la "day lui"
				// trang thai da tung co file that.
				cv.put("has_local_file", 1);
			} else {
				cv.putNull("lpath");
			}
			cv.put("author", author);
			cv.put("title", title);
			// Only set cover_path when the caller actually has one -- omitting the key (instead
			// of cv.putNull) means db.update() on the "already exists" path below leaves any
			// cover_path already cached for this uuid untouched, exactly like updateThumbnail()
			// does for the thumbnail blob. A fresh insertOrThrow() row simply defaults to NULL.
			if (coverPath != null) {
				cv.put("cover_path", coverPath);
			}
			// Cac field chuan moi (DATABASE_VERSION 13) -- cung nguyen tac "khong ghi de" nhu
			// coverPath o tren.
			if (publisher != null) cv.put("publisher", publisher);
			if (pubdate != null) cv.put("pubdate", pubdate);
			if (series != null) cv.put("series", series);
			if (seriesIndex != null) cv.put("series_index", seriesIndex);
			if (rating != null) cv.put("rating", rating);
			if (languagesCsv != null) cv.put("languages", languagesCsv);
			if (identifiersJson != null) cv.put("identifiers_json", identifiersJson);
			if (dateAdded != null) cv.put("date_added", dateAdded);
			if (fileSize != null) cv.put("file_size", fileSize);
			try {
			db.insertOrThrow(TABLE_METADATA, null, cv);
			} catch (SQLiteConstraintException e)  {
				db.update(TABLE_METADATA, cv, "uuid=?", new String[] {uuid});
			} catch (Exception e) {
				throw e;
			}
			Cursor cs = getByUuid(new String[] {"_id"}, uuid);
            if (cs.moveToFirst()) {
                metadata_id = cs.getInt(0);
            }
			if (metadata_id >= 0 && tags != null) {
				for (String tag : tags) {
					if ((tag_id = insertTag(tag)) >= 0) {
					try {
						ContentValues cvt = new ContentValues();
						cvt.put("metadata_id", metadata_id);
						cvt.put("tag_id", tag_id);
						db.insertOrThrow(TABLE_BOOK_TAG, null, cvt);
					} catch (SQLiteConstraintException e) {
						Logger.d("Unable to add ( " + metadata_id + ", " + tag_id + " ) to "+ TABLE_BOOK_TAG);
						continue;
					}
					}
				}
			}
            return metadata_id;
		} catch (Exception e) {
			Logger.e(e);
            return -1;
		}
	}
	/** Updates only the cached cover thumbnail blob for a book, leaving every other column
	 * (notably lpath, which insertMetadata() would otherwise clobber to NULL if left
	 * unspecified) untouched. Used to upgrade an already-cached low-resolution thumbnail to
	 * the full-resolution cover once it's been fetched. No-op if the book isn't in the DB yet. */
	public boolean updateThumbnail(String uuid, byte[] thumbnail) {
		if (thumbnail == null) return false;
		SQLiteDatabase db = this.getWritableDatabase();
		ContentValues cv = new ContentValues();
		cv.put("thumbnail", thumbnail);
		return db.update(TABLE_METADATA, cv, "uuid=?", new String[] {uuid}) > 0;
	}

	/** Cập nhật các cột rút gọn (title/author/comments) sau khi Hiệu đính lưu 1 quyển sách, đồng
	 * thời đồng bộ lại tập tag trong TABLE_TAGS/TABLE_BOOK_TAG cho đúng với newTags (thêm tag
	 * mới, gỡ tag không còn trong danh sách -- diff với getTagNamesForBook hiện tại thay vì
	 * xóa-hết-rồi-thêm-lại). KHÔNG đụng tới thumbnail (xem updateThumbnail riêng) hay cover_path.
	 * @param newTagsCsv danh sách tag mới, phân tách bởi ", " (như joinTags trong
	 *                   DeviceBookMetadataWriter) -- chỉ dùng để tính diff, không lưu trực tiếp
	 *                   thành cột CSV nào (bảng tags/book_tag mới là nguồn dữ liệu thật). */
	public boolean updateMetadataFields(String uuid, String title, String author,
	                                     String comments, String newTagsCsv) {
		return updateMetadataFields(uuid, title, author, comments, newTagsCsv,
				null, null, null, null, null, null, null, null, null);
	}

	/** Full-field version DeviceBookMetadataWriter#save() uses (DATABASE_VERSION 13). Besides
	 * the original title/author/comments/tags, this also pushes the newer standard fields +
	 * Custom Column values into SQLite -- without this, a later
	 * BookCrawler#writeMetadataFile() rebuild (which reads SQLite via exportAllBooksAsJSON(),
	 * NOT metadata.calibre itself) would silently drop a manual metadata edit the very next time
	 * any book on the library synced, since exportAllBooksAsJSON() only knows what's in SQLite.
	 * <p>
	 * Unlike insertMetadata()'s "null = don't touch" convention (appropriate there because a
	 * partial Wireless Device sync payload genuinely might not carry every field), every
	 * publisher..identifiersJson param here follows "empty/null = clear the field" instead,
	 * because the Hiệu đính form always shows -- and so always submits a real value for -- every
	 * one of these fields; there's no partial-payload case to preserve against. seriesIndex/
	 * rating stay nullable Doubles since an unparsable or blank form field naturally becomes
	 * "no value" rather than 0.
	 * @param customColumnDefs   defs to (re-)register (usually already known from a wireless
	 *                           sync, but also covers ones only ever seen via
	 *                           EpubMetadataOpfReader's OPF read, which has no separate sync
	 *                           step of its own) -- pass null/empty to skip.
	 * @param customColumnValues lookup_name -> value_text (same canonical form
	 *                           TABLE_CUSTOM_COLUMN_VALUES stores) for EVERY column the form
	 *                           displayed, including ones the user left blank (an empty/missing
	 *                           entry deletes that column's row for this book, via
	 *                           deleteCustomColumnValue() below) -- pass null to skip touching
	 *                           Custom Column values entirely. */
	public boolean updateMetadataFields(String uuid, String title, String author,
			String comments, String newTagsCsv,
			String publisher, String pubdate, String series, Double seriesIndex, Double rating,
			String languagesCsv, String identifiersJson,
			List<CustomColumnSync.ColumnDef> customColumnDefs,
			java.util.Map<String, String> customColumnValues) {
		SQLiteDatabase db = this.getWritableDatabase();
		ContentValues cv = new ContentValues();
		cv.put("title", title);
		cv.put("author", author);
		cv.put("comments", comments);
		putOrNullIfEmpty(cv, "publisher", publisher);
		putOrNullIfEmpty(cv, "pubdate", pubdate);
		putOrNullIfEmpty(cv, "series", series);
		if (seriesIndex != null) cv.put("series_index", seriesIndex); else cv.putNull("series_index");
		if (rating != null) cv.put("rating", rating); else cv.putNull("rating");
		putOrNullIfEmpty(cv, "languages", languagesCsv);
		putOrNullIfEmpty(cv, "identifiers_json", identifiersJson);
		boolean ok = db.update(TABLE_METADATA, cv, "uuid=?", new String[]{uuid}) > 0;

		Cursor idCs = getByUuid(new String[]{"_id"}, uuid);
		int metadataId = -1;
		try {
			if (idCs.moveToFirst()) metadataId = idCs.getInt(0);
		} finally {
			idCs.close();
		}
		if (metadataId >= 0) {
			java.util.Set<String> newTags = new java.util.LinkedHashSet<>();
			if (newTagsCsv != null) {
				for (String t : newTagsCsv.split(",\\s*")) {
					String trimmed = t.trim();
					if (!trimmed.isEmpty()) newTags.add(trimmed);
				}
			}
			java.util.Set<String> oldTags = new java.util.HashSet<>(getTagNamesForBook(metadataId));
			for (String tag : oldTags) {
				if (!newTags.contains(tag)) removeTagFromBook(metadataId, tag);
			}
			for (String tag : newTags) {
				if (!oldTags.contains(tag)) addTagToBook(metadataId, tag);
			}

			if (customColumnDefs != null && !customColumnDefs.isEmpty()) {
				upsertCustomColumnDefs(customColumnDefs);
			}
			if (customColumnValues != null) {
				List<CustomColumnSync.ColumnValue> toUpsert = new ArrayList<>();
				java.util.Map<String, CustomColumnSync.ColumnDef> defsByLookup = new java.util.HashMap<>();
				if (customColumnDefs != null) {
					for (CustomColumnSync.ColumnDef d : customColumnDefs) defsByLookup.put(d.lookupName, d);
				}
				for (java.util.Map.Entry<String, String> e : customColumnValues.entrySet()) {
					String lookupName = e.getKey();
					String valueText = e.getValue();
					if (valueText == null || valueText.isEmpty()) {
						deleteCustomColumnValue(metadataId, lookupName);
						continue;
					}
					CustomColumnSync.ColumnDef def = defsByLookup.get(lookupName);
					Double valueNum = def != null ? CustomColumnSync.computeValueNum(def, valueText) : null;
					toUpsert.add(new CustomColumnSync.ColumnValue(lookupName, valueText, valueNum));
				}
				if (!toUpsert.isEmpty()) upsertCustomColumnValues(metadataId, toUpsert);
			}
		}
		return ok;
	}

	/** cv.put(key, value) if value is non-empty, cv.putNull(key) otherwise -- the "editor form
	 * field left blank means clear it" convention updateMetadataFields() above uses (see its
	 * javadoc for why this differs from insertMetadata()'s "null = don't touch"). */
	private static void putOrNullIfEmpty(ContentValues cv, String key, String value) {
		if (value != null && !value.isEmpty()) {
			cv.put(key, value);
		} else {
			cv.putNull(key);
		}
	}

	/** Removes 1 Custom Column's value for 1 book (the form field for it was left blank) --
	 * without this, upsertCustomColumnValues() alone could only ever add/replace, never clear,
	 * leaving a stale value_text behind after the user deliberately emptied the field. */
	public void deleteCustomColumnValue(int metadataId, String lookupName) {
		SQLiteDatabase db = this.getWritableDatabase();
		db.delete(TABLE_CUSTOM_COLUMN_VALUES, "metadata_id=? AND lookup_name=?",
				new String[]{String.valueOf(metadataId), lookupName});
	}

	/** Ghi/cập nhật đường dẫn cover tương đối (so với rootDirectory) cho một cuốn sách theo uuid,
	 * không đụng tới cột nào khác. Truyền null để xóa (đánh dấu chưa có cover trên đĩa nữa, ví dụ
	 * nếu CoverManager#saveCover thất bại sau khi đã có cover cũ). */
	public boolean updateCoverPath(String uuid, String coverPath) {
		SQLiteDatabase db = this.getWritableDatabase();
		ContentValues cv = new ContentValues();
		if (coverPath != null) cv.put("cover_path", coverPath); else cv.putNull("cover_path");
		return db.update(TABLE_METADATA, cv, "uuid=?", new String[]{uuid}) > 0;
	}

	/** Tổng hợp số liệu Device Library hiển thị trong dialog "Thông tin thư viện cloud" (xem
	 * CloudRemoteInfoBottomSheetFragment) -- CHỈ cover các cột metadata SẴN CÓ trong DB cục bộ
	 * (bảng metadata/tags), KHÔNG có Publisher/Series vì schema bảng metadata không lưu 2 trường
	 * này cho Device Library (khác với GDriveLibraryDb.LibrarySummary, vốn đọc từ 1 file library.db
	 * calibre đầy đủ qua tính năng "Libraries on Gdrive"). Xem mục 3.1 của prompt nâng cấp. */
	public static final class LocalLibrarySummary {
		public final int bookCount;
		public final int authorCount;
		public final int tagCount;
		public LocalLibrarySummary(int bookCount, int authorCount, int tagCount) {
			this.bookCount = bookCount;
			this.authorCount = authorCount;
			this.tagCount = tagCount;
		}
	}

	public LocalLibrarySummary getLocalLibrarySummary() {
		SQLiteDatabase db = this.getReadableDatabase();
		int bookCount = 0;
		java.util.Set<String> distinctAuthors = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		// author có thể chứa nhiều tác giả nối bởi " & " (giống cách GDriveLibraryDb#getAuthorCount
		// tách chuỗi "authors" của calibre) -- tách trước khi đưa vào set để đếm đúng số tác giả
		// PHÂN BIỆT thay vì số cụm tác giả.
		Cursor cs = db.query(TABLE_METADATA, new String[]{"author"}, "lpath IS NOT NULL", null, null, null, null);
		try {
			while (cs.moveToNext()) {
				bookCount++;
				if (cs.isNull(0)) continue;
				String raw = cs.getString(0);
				if (raw == null || raw.trim().isEmpty()) continue;
				for (String name : raw.split("&")) {
					String trimmed = name.trim();
					if (!trimmed.isEmpty()) distinctAuthors.add(trimmed);
				}
			}
		} finally {
			cs.close();
		}
		int tagCount = 0;
		Cursor tagCursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_TAGS, null);
		try {
			if (tagCursor.moveToFirst()) tagCount = tagCursor.getInt(0);
		} finally {
			tagCursor.close();
		}
		return new LocalLibrarySummary(bookCount, distinctAuthors.size(), tagCount);
	}

	/** @return danh sách (uuid, lpath) của mọi sách hiện có lpath nhưng cover_path còn NULL --
	 * dùng để BookCrawler biết cần parse cover bổ sung cho sách nào trong lượt quét này. */
	public Cursor getBooksMissingCover() {
		SQLiteDatabase db = this.getReadableDatabase();
		return db.query(TABLE_METADATA, new String[]{"uuid", "lpath"},
				"lpath IS NOT NULL AND cover_path IS NULL", null, null, null, null);
	}

	/** @return mọi cover_path hiện đang được tham chiếu trong DB (dùng để dọn covers/ mồ côi mà
	 * không phải quét lại toàn bộ SQLite từng file một). */
	public java.util.Set<String> getAllCoverPaths() {
		java.util.Set<String> result = new java.util.HashSet<>();
		SQLiteDatabase db = this.getReadableDatabase();
		Cursor cs = db.query(TABLE_METADATA, new String[]{"cover_path"},
				"cover_path IS NOT NULL", null, null, null, null);
		try {
			while (cs.moveToNext()) result.add(cs.getString(0));
		} finally {
			cs.close();
		}
		return result;
	}

	public boolean insertBook(String uuid, String lpath) {
		SQLiteDatabase db = this.getWritableDatabase();
		ContentValues cv = new ContentValues();
		cv.put("lpath",lpath);
		// BUG FIX: db.update() trả về SỐ DÒNG bị ảnh hưởng (0, 1, 2...), không bao giờ âm --
		// SQLiteException mới là cách nó báo lỗi thật sự. So sánh "< 0" ở đây vì vậy luôn là
		// false, khiến hàm này báo "thành công" (return true) ngay cả khi không có dòng nào khớp
		// uuid (0 dòng được cập nhật) -- ví dụ khi metadata_id chưa từng được insertMetadata()
		// tạo trước đó. Phải kiểm tra "<= 0" mới đúng: 0 nghĩa là không cập nhật được gì.
		int rows = db.update(TABLE_METADATA, cv, "uuid=?", new String[] {uuid});
		if (rows <= 0) {
			Logger.d("unable to insertBook (uuid not found in metadata: " + uuid + ")");
			return false;
		} else {
			Logger.d("insertBook successful: "+ uuid + " " + lpath);
			return true;
		}
	}

	/**
	 * Persists the last-read location (a serialized Readium Locator for EPUB, or a small JSON
	 * blob for PDF/CBZ page position) for the book so ReaderActivity can jump back to it next
	 * time the book is opened. See MetadataDatabaseHelper DATABASE_VERSION 5 migration.
	 */
	public boolean saveLastLocator(String uuid, String lastLocatorJson) {
		SQLiteDatabase db = this.getWritableDatabase();
		ContentValues cv = new ContentValues();
		cv.put("last_locator", lastLocatorJson);
		cv.put("last_locator_updated_at", System.currentTimeMillis());
		return db.update(TABLE_METADATA, cv, "uuid=?", new String[] {uuid}) > 0;
	}

	/** Dung rieng cho DeviceLibrarySyncManager khi ghi lai locator TAI VE tu cloud -- cho phep
	 * truyen thang timestamp cua ban ghi tren cloud thay vi System.currentTimeMillis(), de lan
	 * sync tiep theo van so sanh dung "ai moi hon". */
	public boolean saveLastLocator(String uuid, String lastLocatorJson, long updatedAtMillis) {
		SQLiteDatabase db = this.getWritableDatabase();
		ContentValues cv = new ContentValues();
		cv.put("last_locator", lastLocatorJson);
		cv.put("last_locator_updated_at", updatedAtMillis);
		return db.update(TABLE_METADATA, cv, "uuid=?", new String[] {uuid}) > 0;
	}

	/** @return the saved last-read locator JSON for this book, or null if none is stored. */
	public String getLastLocator(String uuid) {
		Cursor cs = getByUuid(new String[] {"last_locator"}, uuid);
		try {
			if (cs.moveToFirst() && !cs.isNull(0)) {
				return cs.getString(0);
			}
			return null;
		} finally {
			cs.close();
		}
	}

	/** @return last_locator_updated_at hien co cua sach, hoac 0 neu chua tung luu. Dung de so
	 * sanh TRUOC/SAU 1 lan pull progress tu cloud (xem EpubReaderActivity#pullProgressOnOpen),
	 * KHONG dung cho hien thi UI. */
	public long getLastLocatorUpdatedAt(String uuid) {
		Cursor cs = getByUuid(new String[] {"last_locator_updated_at"}, uuid);
		try {
			if (cs.moveToFirst() && !cs.isNull(0)) {
				return cs.getLong(0);
			}
			return 0;
		} finally {
			cs.close();
		}
	}

	/** Cap nhat mo ta sach (Calibre "comments" field). Goi tu insertJSON khi Calibre desktop
	 * gui metadata, hoac tu BookMetadataSniffer neu mo rong sau nay. */
	public boolean saveComments(String uuid, String comments) {
		SQLiteDatabase db = this.getWritableDatabase();
		ContentValues cv = new ContentValues();
		cv.put("comments", comments);
		return db.update(TABLE_METADATA, cv, "uuid=?", new String[]{uuid}) > 0;
	}

	/** Batch load progress % (EPUB only, xem ReadingProgressUtil) va comments cho toan bo sach
	 * dang co lpath (Device Library). Goi 1 lan khi cursor duoc (re)load, KHONG goi trong
	 * onBindViewHolder tung dong -- tranh N truy van DB khi scroll list dai. */
	public java.util.Map<String, BookExtras> getAllBookExtras() {
		java.util.Map<String, BookExtras> result = new java.util.HashMap<>();
		SQLiteDatabase db = this.getReadableDatabase();
		Cursor cs = db.query(TABLE_METADATA,
				new String[]{"uuid", "last_locator", "comments"},
				"lpath IS NOT NULL", null, null, null, null);
		try {
			while (cs.moveToNext()) {
				String uuid = cs.getString(0);
				String lastLocator = cs.isNull(1) ? null : cs.getString(1);
				String comments = cs.isNull(2) ? null : cs.getString(2);
				Integer percent = ReadingProgressUtil.progressPercentFromLocator(lastLocator);
				result.put(uuid, new BookExtras(percent, comments));
			}
		} finally {
			cs.close();
		}
		return result;
	}

	/** Toan bo uuid hien co lpath (da nam trong Device Library, tuc da tai file that su len may) --
	 * dung de danh dau tren "/api/library.json" (xem GDriveLibraryDb#buildLibraryJson) sach nao
	 * KHONG can hien nut tai nua. Cung mau voi getAllBookExtras() ben tren nhung chi can 1 cot. */
	public java.util.Set<String> getAllUuidsWithLpath() {
		java.util.Set<String> result = new java.util.HashSet<>();
		SQLiteDatabase db = this.getReadableDatabase();
		Cursor cs = db.query(TABLE_METADATA, new String[]{"uuid"}, "lpath IS NOT NULL", null, null, null, null);
		try {
			while (cs.moveToNext()) {
				if (!cs.isNull(0)) result.add(cs.getString(0));
			}
		} finally {
			cs.close();
		}
		return result;
	}

	/** Ket qua goi Book/BookRow can them de ve UI: % tien trinh (null = an di) va comment
	 * (null/rong = an dong comment). */
	public static final class BookExtras {
		public final Integer progressPercent; // null neu chua doc / khong tinh duoc (PDF/CBZ)
		public final String comments;         // null neu khong co mo ta
		public BookExtras(Integer progressPercent, String comments) {
			this.progressPercent = progressPercent;
			this.comments = comments;
		}
	}

	// --- Bookmarks / Highlights / Notes (TABLE_ANNOTATIONS, DATABASE_VERSION 8) -------------
	// See CREATE_TABLES[4]'s comment above for why bookmark and highlight share one table.
	// "type" is "bookmark" or "highlight"; a highlight's "note" column is optional (a highlight
	// doesn't need a note, but a note is always attached to a highlight/location, matching how
	// Readest treats bookmarks/highlights/notes as one annotation system -- see prompt section
	// 5.4). PDF/CBZ bookmarks reuse this same table with locator_json = a small JSON blob like
	// {"type":"pdf_page","page":N}, same convention as MetadataDatabaseHelper's own last_locator
	// column (see DATABASE_VERSION 4 migration comment above).

	/** Adds a new annotation row and returns its `_id`, or -1 on failure. */
	public long addAnnotation(String uuid, String type, String locatorJson, String color, String note, String excerpt) {
		SQLiteDatabase db = this.getWritableDatabase();
		ContentValues cv = new ContentValues();
		cv.put("uuid", uuid);
		cv.put("type", type);
		cv.put("locator_json", locatorJson);
		if (color != null) cv.put("color", color);
		if (note != null) cv.put("note", note);
		if (excerpt != null) cv.put("excerpt", excerpt);
		long now = System.currentTimeMillis();
		cv.put("created_at", now);
		cv.put("updated_at", now);
		// deleted_at intentionally left unset (NULL) -- a freshly (re)added annotation is alive.
		return db.insert(TABLE_ANNOTATIONS, null, cv);
	}

	/** Updates the note text of an existing annotation (used to add/edit a note on a highlight). */
	public boolean updateAnnotationNote(long id, String note) {
		SQLiteDatabase db = this.getWritableDatabase();
		ContentValues cv = new ContentValues();
		cv.put("note", note);
		cv.put("updated_at", System.currentTimeMillis());
		return db.update(TABLE_ANNOTATIONS, cv, "_id = ?", new String[]{String.valueOf(id)}) > 0;
	}

	/**
	 * Deletes a bookmark or highlight/note -- as a SOFT delete (DATABASE_VERSION 15 BUG FIX):
	 * sets {@code deleted_at} (and bumps {@code updated_at}, same as any other edit) instead of
	 * actually removing the row. The row stays in SQLite as a tombstone so
	 * {@link SyncProgress#mergeLocalInto} can tell "the user deleted this on purpose" apart from
	 * "this device never had it", and so the deletion propagates to {@code
	 * calibre_sync_progress.json} / other devices instead of the next sync silently re-adding it
	 * from the cloud's still-existing copy. All read paths used by the UI ({@link #getAnnotations}
	 * and {@link #findBookmarkId}) filter {@code deleted_at IS NULL}, so this is NOT visible to
	 * the caller as a behavior change -- the row simply disappears from lists/bookmark-state
	 * checks exactly as before, it's just not physically gone from the table.
	 */
	public boolean removeAnnotation(long id) {
		SQLiteDatabase db = this.getWritableDatabase();
		ContentValues cv = new ContentValues();
		long now = System.currentTimeMillis();
		cv.put("deleted_at", now);
		cv.put("updated_at", now);
		return db.update(TABLE_ANNOTATIONS, cv, "_id = ? AND deleted_at IS NULL", new String[]{String.valueOf(id)}) > 0;
	}

	/**
	 * Applies a deletion TOMBSTONE that came from another device via {@code
	 * calibre_sync_progress.json} (see {@link SyncProgress#applyRemoteNewerIntoLocal}) -- same
	 * soft-delete as {@link #removeAnnotation}, but keyed by (uuid, type, locator_json) instead of
	 * a local {@code _id} (a remote tombstone has no local row id), and stamped with the remote
	 * {@code deletedAt} instead of "now", so the local tombstone's timestamp still reflects when
	 * the deletion actually happened rather than when this device happened to sync. No-op if
	 * there's no local row to delete (nothing to propagate) or it's already deleted.
	 */
	public boolean applyRemoteDeletion(String uuid, String type, String locatorJson, long deletedAt) {
		SQLiteDatabase db = this.getWritableDatabase();
		ContentValues cv = new ContentValues();
		cv.put("deleted_at", deletedAt);
		cv.put("updated_at", deletedAt);
		return db.update(TABLE_ANNOTATIONS, cv,
			"uuid = ? AND type = ? AND locator_json = ? AND deleted_at IS NULL",
			new String[]{uuid, type, locatorJson}) > 0;
	}

	/**
	 * @return every LIVE (not soft-deleted) annotation of the given {@code type} ("bookmark" or
	 * "highlight") for this book, newest first. Caller must close the returned Cursor. Columns, in
	 * order: _id, uuid, type, locator_json, color, note, excerpt, created_at, updated_at,
	 * deleted_at.
	 */
	public Cursor getAnnotations(String uuid, String type) {
		SQLiteDatabase db = this.getReadableDatabase();
		return db.query(TABLE_ANNOTATIONS, null,
			"uuid = ? AND type = ? AND deleted_at IS NULL", new String[]{uuid, type}, null, null, "created_at DESC");
	}

	// --- Tu dien phat am TTS (TABLE_TTS_DICTIONARY, DATABASE_VERSION 12) ---------------------
	// Danh sach toan cuc (khong theo tung sach) cap (source_text -> replacement_text) de
	// TtsController thay the truoc khi dua cau vao TtsEngine tong hop giong. Xem
	// TtsDictionaryStore/TtsDictionaryReplacer.

	/** Them 1 dong tu dien moi, tra ve _id moi hoac -1 neu that bai (VD source_text trung,
	 * vi UNIQUE COLLATE NOCASE). */
	public long addTtsDictionaryEntry(String sourceText, String replacementText) {
		SQLiteDatabase db = this.getWritableDatabase();
		ContentValues cv = new ContentValues();
		cv.put("source_text", sourceText);
		cv.put("replacement_text", replacementText);
		cv.put("updated_at", System.currentTimeMillis());
		return db.insert(TABLE_TTS_DICTIONARY, null, cv);
	}

	/** Sua 1 dong tu dien da co. */
	public boolean updateTtsDictionaryEntry(long id, String sourceText, String replacementText) {
		SQLiteDatabase db = this.getWritableDatabase();
		ContentValues cv = new ContentValues();
		cv.put("source_text", sourceText);
		cv.put("replacement_text", replacementText);
		cv.put("updated_at", System.currentTimeMillis());
		return db.update(TABLE_TTS_DICTIONARY, cv, "_id = ?", new String[]{String.valueOf(id)}) > 0;
	}

	/** Xoa 1 dong tu dien. */
	public boolean removeTtsDictionaryEntry(long id) {
		SQLiteDatabase db = this.getWritableDatabase();
		return db.delete(TABLE_TTS_DICTIONARY, "_id = ?", new String[]{String.valueOf(id)}) > 0;
	}

	/**
	 * @return toan bo tu dien phat am, moi cap nhat gan nhat truoc. Caller phai dong Cursor.
	 * Cac cot theo thu tu: _id, source_text, replacement_text, updated_at.
	 */
	public Cursor getAllTtsDictionaryEntries() {
		SQLiteDatabase db = this.getReadableDatabase();
		return db.query(TABLE_TTS_DICTIONARY, null, null, null, null, null, "updated_at DESC");
	}

	/**
	 * @return true if this exact position already has a LIVE (not soft-deleted) bookmark for this
	 * book. Used by ReaderChromeHost#isCurrentLocationBookmarked() implementations to decide
	 * add-vs-remove. Matching is by exact locator_json string equality -- fine for the "is the
	 * *current* position, saved verbatim moments ago, already bookmarked" check this method exists
	 * for.
	 */
	public long findBookmarkId(String uuid, String locatorJson) {
		return findAnnotationId(uuid, "bookmark", locatorJson);
	}

	/**
	 * @return the `_id` of the LIVE (not soft-deleted) annotation matching (uuid, type,
	 * locator_json), or -1 if none. Generalized version of {@link #findBookmarkId} that also
	 * covers "highlight" (BUG FIX, DATABASE_VERSION 15) -- previously
	 * {@link SyncProgress#applyRemoteNewerIntoLocal} only ever looked this up for bookmarks
	 * (`"bookmark".equals(a.type) ? db.findBookmarkId(...) : -1`), so every highlight/note coming
	 * back from {@code calibre_sync_progress.json} was always treated as brand new and
	 * re-inserted via {@link #addAnnotation} on every single sync, even when the exact same
	 * highlight already existed locally -- the visible symptom being the same highlight/note
	 * showing up duplicated (2x, 3x, ...) in "Đánh dấu chữ & ghi chú" after a few sync cycles.
	 */
	public long findAnnotationId(String uuid, String type, String locatorJson) {
		SQLiteDatabase db = this.getReadableDatabase();
		Cursor cs = db.query(TABLE_ANNOTATIONS, new String[]{"_id"},
			"uuid = ? AND type = ? AND locator_json = ? AND deleted_at IS NULL",
			new String[]{uuid, type, locatorJson}, null, null, null);
		try {
			if (cs.moveToFirst()) return cs.getLong(0);
			return -1;
		} finally {
			cs.close();
		}
	}

	/**
	 * Adds {@code deltaMs} to the actual accumulated reading time recorded for a single unit
	 * (an EPUB chapter href, or "page:N" for PDF/CBZ) of a book, creating the row on first use.
	 * This is what lets the "Thống kê" dialog show real measured numbers -- see
	 * net.mrphan.calibre.reader.stats.ReadingTimeTracker.
	 */
	public void addReadingTimeMs(String uuid, String unitKey, long deltaMs) {
		if (deltaMs <= 0 || uuid == null || unitKey == null) return;
		SQLiteDatabase db = this.getWritableDatabase();
		db.beginTransaction();
		try {
			Cursor cs = db.query(TABLE_READING_TIME, new String[] {"elapsed_ms"}, "uuid=? AND unit_key=?",
					new String[] {uuid, unitKey}, null, null, null);
			boolean hasRow = cs.moveToFirst();
			long existing = hasRow ? cs.getLong(0) : 0L;
			cs.close();
			ContentValues cv = new ContentValues();
			cv.put("uuid", uuid);
			cv.put("unit_key", unitKey);
			cv.put("elapsed_ms", existing + deltaMs);
			if (hasRow) {
				db.update(TABLE_READING_TIME, cv, "uuid=? AND unit_key=?", new String[] {uuid, unitKey});
			} else {
				db.insertOrThrow(TABLE_READING_TIME, null, cv);
			}
			db.setTransactionSuccessful();
		} catch (Exception e) {
			Logger.e(e);
		} finally {
			db.endTransaction();
		}
	}

	/** @return every recorded actual-reading-time entry for this book, keyed by unit key
	 * (chapter href for EPUB, "page:N" for PDF/CBZ). Empty map if nothing has been tracked yet. */
	public java.util.Map<String, Long> getReadingTimes(String uuid) {
		java.util.Map<String, Long> result = new java.util.HashMap<>();
		SQLiteDatabase db = this.getReadableDatabase();
		Cursor cs = db.query(TABLE_READING_TIME, new String[] {"unit_key", "elapsed_ms"}, "uuid=?",
				new String[] {uuid}, null, null, null);
		while (cs.moveToNext()) {
			result.put(cs.getString(0), cs.getLong(1));
		}
		cs.close();
		return result;
	}

	/** Deletes all recorded reading-time data for this book. Backs the stats dialog's
	 * "Reset statistics" button. */
	public void resetReadingTimes(String uuid) {
		SQLiteDatabase db = this.getWritableDatabase();
		db.delete(TABLE_READING_TIME, "uuid=?", new String[] {uuid});
	}

	// --- Thong ke doc theo ngay, gop toan bo thu vien (TABLE_DAILY_STATS, DATABASE_VERSION 17) --
	// Song song, KHONG lien quan gi den TABLE_READING_TIME/addReadingTimeMs o tren (do la "ETA
	// con bao lau het sach" cho 1 cuon cu the). Xem net.mrphan.calibre.reader.stats.
	// DailyReadingStatsStore -- man hinh "Tien trinh" (ProgressActivity) chi doc qua store do,
	// khong goi truc tiep cac ham nay.

	/** Cong don ms doc + so tu doc duoc vao ngay {@code dayEpoch}
	 * (java.time.LocalDate.toEpochDay(), tinh theo gio dia phuong). Goi tu DailyReadingStatsStore,
	 * KHONG goi truc tiep tu reader Activity. */
	public void addDailyReadingStats(long dayEpoch, long deltaMs, long deltaWordCount) {
		if (deltaMs <= 0 && deltaWordCount <= 0) return;
		SQLiteDatabase db = this.getWritableDatabase();
		db.beginTransaction();
		try {
			Cursor cs = db.query(TABLE_DAILY_STATS, new String[] {"reading_ms", "word_count"},
					"day_epoch=?", new String[] {String.valueOf(dayEpoch)}, null, null, null);
			boolean hasRow = cs.moveToFirst();
			long existingMs = hasRow ? cs.getLong(0) : 0L;
			long existingWords = hasRow ? cs.getLong(1) : 0L;
			cs.close();
			ContentValues cv = new ContentValues();
			cv.put("day_epoch", dayEpoch);
			cv.put("reading_ms", existingMs + Math.max(0, deltaMs));
			cv.put("word_count", existingWords + Math.max(0, deltaWordCount));
			if (hasRow) {
				db.update(TABLE_DAILY_STATS, cv, "day_epoch=?", new String[] {String.valueOf(dayEpoch)});
			} else {
				cv.put("translation_count", 0);
				db.insertOrThrow(TABLE_DAILY_STATS, null, cv);
			}
			db.setTransactionSuccessful();
		} catch (Exception e) {
			Logger.e(e);
		} finally {
			db.endTransaction();
		}
	}

	/** Tang so lan dich thanh cong len 1 cho ngay {@code dayEpoch}. Goi tu LookupBottomSheetFragment
	 * moi lan dich thanh cong (khong tinh loi mang/loi provider). */
	public void incrementDailyTranslationCount(long dayEpoch) {
		SQLiteDatabase db = this.getWritableDatabase();
		db.beginTransaction();
		try {
			Cursor cs = db.query(TABLE_DAILY_STATS, new String[] {"translation_count"},
					"day_epoch=?", new String[] {String.valueOf(dayEpoch)}, null, null, null);
			boolean hasRow = cs.moveToFirst();
			long existing = hasRow ? cs.getLong(0) : 0L;
			cs.close();
			ContentValues cv = new ContentValues();
			cv.put("day_epoch", dayEpoch);
			cv.put("translation_count", existing + 1);
			if (hasRow) {
				db.update(TABLE_DAILY_STATS, cv, "day_epoch=?", new String[] {String.valueOf(dayEpoch)});
			} else {
				cv.put("reading_ms", 0);
				cv.put("word_count", 0);
				db.insertOrThrow(TABLE_DAILY_STATS, null, cv);
			}
			db.setTransactionSuccessful();
		} catch (Exception e) {
			Logger.e(e);
		} finally {
			db.endTransaction();
		}
	}

	/** @return moi dong daily_reading_stats co day_epoch trong [fromEpochInclusive,
	 * toEpochInclusive], sap xep tang dan theo ngay. Moi phan tu la {day_epoch, reading_ms,
	 * word_count, translation_count}. Dung cho man hinh "Tien trinh". */
	public java.util.List<long[]> getDailyStatsRange(long fromEpochInclusive, long toEpochInclusive) {
		java.util.List<long[]> result = new java.util.ArrayList<>();
		SQLiteDatabase db = this.getReadableDatabase();
		Cursor cs = db.query(TABLE_DAILY_STATS,
				new String[] {"day_epoch", "reading_ms", "word_count", "translation_count"},
				"day_epoch>=? AND day_epoch<=?",
				new String[] {String.valueOf(fromEpochInclusive), String.valueOf(toEpochInclusive)},
				null, null, "day_epoch ASC");
		while (cs.moveToNext()) {
			result.add(new long[] {cs.getLong(0), cs.getLong(1), cs.getLong(2), cs.getLong(3)});
		}
		cs.close();
		return result;
	}

	public String deleteByPath(String lpath) {
		Cursor cs = getByPath(new String[] {"_id", "uuid"}, lpath);
		if (cs.moveToFirst() && deleteBook(cs.getInt(0))) {
			return cs.getString(1);
		} else {
			Logger.d("Did not delete book " + lpath );
			return null;
		}

	}
	public boolean deleteBook(int id) {
		SQLiteDatabase db = this.getWritableDatabase();
		ContentValues cv = new ContentValues();
		cv.putNull("lpath");
		// Also clear cover_path: the actual cover FILE is deleted by the caller (BookCrawler /
		// CollectionFragment, via CoverManager.deleteCoverFor()) before this runs, so leaving the
		// old cover_path in the row would make getAllCoverPaths() think that path is still in use
		// and permanently exempt it from cleanupOrphanCovers().
		cv.putNull("cover_path");
		if ( db.update(TABLE_METADATA, cv, String.format("_id=%d",id), null) > 0) {
			db.delete(TABLE_BOOK_TAG, String.format("metadata_id=%d",id) , null );
			return true;
		} else {
			return false;
		}
	}
	
	public Cursor getNotNull(String[] columns, String criteria) {
		SQLiteDatabase db = this.getReadableDatabase();
		return db.query(TABLE_METADATA, columns, criteria + " IS NOT NULL", null, null, null, "title");
	}

	/** Sort field ids for {@link #getBooksSorted}, shared with CollectionFragment's
	 * "Sort by..." dialog (SortByDialog). Rating/Series/Publisher/Published/File size were
	 * added in DATABASE_VERSION 13 alongside the matching TABLE_METADATA columns -- older rows
	 * synced before that point simply have NULL here (see getBooksSorted()'s NULL-last
	 * handling below), not a compile-time gap like before. */
	public static final int SORT_FIELD_TITLE = 0;
	public static final int SORT_FIELD_AUTHOR = 1;
	/** "Recent": kept as an _id-based proxy for insertion order (higher _id was
	 * scanned/imported more recently) rather than switching to the real date_added column now
	 * that one exists, since date_added is only populated for books synced from Calibre Desktop
	 * -- a locally-scanned book (Device Library folder scan, no Calibre Desktop involved) would
	 * have a NULL date_added and sort as if never added, which is wrong for "Recent". */
	public static final int SORT_FIELD_RECENT = 2;
	/** "Last Opened": backed by the real last_locator_updated_at column (bumped whenever the
	 * reader saves a locator -- see ReaderActivity), so this one is exact, not a proxy. Books
	 * never opened default to 0 and sort to the very end in DESC order. */
	public static final int SORT_FIELD_LAST_OPENED = 3;
	/** Sorts by series name, then by series_index within the same series -- e.g. "Harry Potter"
	 * book 2 before book 3, matching how a reader would expect a series to be ordered regardless
	 * of the chosen direction toggle (direction only affects the series-name grouping, not the
	 * in-series book order). Books with no series are always sorted after ones that do. */
	public static final int SORT_FIELD_SERIES = 4;
	public static final int SORT_FIELD_RATING = 5;
	public static final int SORT_FIELD_PUBLISHER = 6;
	/** "Published": Calibre's pubdate field (when the book was published), not to be confused
	 * with SORT_FIELD_RECENT (when this app first saw the book). */
	public static final int SORT_FIELD_PUBDATE = 7;
	public static final int SORT_FIELD_FILE_SIZE = 8;

	/** Backs CollectionFragment's "Sort by..." dialog for the Device Library tab (only books
	 * already on device, i.e. lpath IS NOT NULL -- mirrors getNotNull(..., "lpath") above).
	 * Fields that can legitimately be NULL for a book (every one added in DATABASE_VERSION 13,
	 * i.e. everything except author/title/_id/last_locator_updated_at) always sort such books to
	 * the end regardless of the chosen direction -- otherwise a book with no rating/series/etc.
	 * would jump to the very top every time the user picks DESC, which reads as broken rather
	 * than "no data". */
	public Cursor getBooksSorted(String[] columns, int sortField, boolean ascending) {
		SQLiteDatabase db = this.getReadableDatabase();
		String dir = ascending ? "ASC" : "DESC";
		String orderBy;
		switch (sortField) {
			case SORT_FIELD_AUTHOR:
				orderBy = "author COLLATE NOCASE " + dir + ", title COLLATE NOCASE ASC";
				break;
			case SORT_FIELD_RECENT:
				orderBy = "_id " + dir;
				break;
			case SORT_FIELD_LAST_OPENED:
				orderBy = "last_locator_updated_at " + dir;
				break;
			case SORT_FIELD_SERIES:
				orderBy = "(series IS NULL) ASC, series COLLATE NOCASE " + dir
						+ ", series_index ASC, title COLLATE NOCASE ASC";
				break;
			case SORT_FIELD_RATING:
				orderBy = "(rating IS NULL) ASC, rating " + dir + ", title COLLATE NOCASE ASC";
				break;
			case SORT_FIELD_PUBLISHER:
				orderBy = "(publisher IS NULL) ASC, publisher COLLATE NOCASE " + dir
						+ ", title COLLATE NOCASE ASC";
				break;
			case SORT_FIELD_PUBDATE:
				orderBy = "(pubdate IS NULL) ASC, pubdate " + dir + ", title COLLATE NOCASE ASC";
				break;
			case SORT_FIELD_FILE_SIZE:
				orderBy = "(file_size IS NULL) ASC, file_size " + dir + ", title COLLATE NOCASE ASC";
				break;
			case SORT_FIELD_TITLE:
			default:
				orderBy = "title COLLATE NOCASE " + dir;
				break;
		}
		return db.query(TABLE_METADATA, columns, "lpath IS NOT NULL", null, null, null, orderBy);
	}

	/** Sorts the Device Library book list by a Custom Column's value instead of a built-in
	 * field -- mirrors getBooksSorted() above but needs a LEFT JOIN onto
	 * TABLE_CUSTOM_COLUMN_VALUES filtered to this one lookup_name (a book with no value for this
	 * column simply has no matching join row, i.e. NULLs on both sides). Prefers value_num
	 * (numeric datatypes: rating/int/float/datetime) when present, falling back to value_text
	 * (text/enumeration/bool/is_multiple) -- exactly one of the two is ever populated for a
	 * given ColumnDef#datatype, see CustomColumnSync#toColumnValue. */
	public Cursor getBooksSortedByCustomColumn(String[] columns, String lookupName, boolean ascending) {
		SQLiteDatabase db = this.getReadableDatabase();
		String dir = ascending ? "ASC" : "DESC";
		String table = TABLE_METADATA + " LEFT JOIN " + TABLE_CUSTOM_COLUMN_VALUES + " cc_sort"
				+ " ON " + TABLE_METADATA + "._id = cc_sort.metadata_id"
				+ " AND cc_sort.lookup_name = " + sqlQuote(lookupName);
		String orderBy = "(cc_sort.value_num IS NULL AND cc_sort.value_text IS NULL) ASC, "
				+ "cc_sort.value_num " + dir + ", "
				+ "cc_sort.value_text COLLATE NOCASE " + dir + ", "
				+ TABLE_METADATA + ".title COLLATE NOCASE ASC";
		return db.query(table, columns, TABLE_METADATA + ".lpath IS NOT NULL", null, null, null, orderBy);
	}

	/** Single-quotes and escapes a value for safe inline use in a SQL string built by hand (as
	 * opposed to a "?" placeholder) -- needed for lookup_name above because it's baked into the
	 * ON clause of a query(table, ...) call, which SQLiteDatabase#query has no placeholder slot
	 * for (only the WHERE-clause `selectionArgs` does). lookup_name always comes from Calibre
	 * Desktop's own column names ("#genre"), never raw user input, but this is cheap insurance
	 * either way. */
	private static String sqlQuote(String s) {
		return "'" + s.replace("'", "''") + "'";
	}
	
	public Cursor getByPath(String[] columns, String lpath) {
		SQLiteDatabase db = this.getReadableDatabase();
		return db.query(TABLE_METADATA, columns, "lpath=?",new String[] {lpath}, null, null, null);
	}
	
	public Cursor getByUuid(String[] columns, String uuid) {
		SQLiteDatabase db = this.getReadableDatabase();
		return db.query(TABLE_METADATA, columns, "uuid=?",new String[] {uuid}, null, null, null);
	}
	
	public Cursor getById(String[] columns, int id) {
		SQLiteDatabase db = this.getReadableDatabase();
		return db.query(TABLE_METADATA, columns, String.format("_id=%d",id), null, null, null, null);
	}
    public Cursor search(String[] columns, String s) {
        SQLiteDatabase db = this.getReadableDatabase();
        // lpath IS NOT NULL: TABLE_METADATA còn chứa các dòng "ghost" do OPDSLoader cache khi
        // duyệt tab Calibre Library (OPDS) - insertMetadata(uuid, null, ...) cho MỌI sách thấy
        // trong feed, kể cả sách chưa tải về máy, để phục vụ cache tag/cover. BookSearchActivity
        // (nơi gọi search() này) theo thiết kế chỉ tìm trong sách ĐÃ TẢI VỀ MÁY (xem comment ở
        // OPDSBrowserFragment#onCreateOptionsMenu) - thiếu điều kiện này khiến search() trả về cả
        // sách chưa tải, và bấm vào 1 kết quả như vậy mở OpenBookActivity với lpath=null -> không
        // có gì để mở, màn hình đen đứng yên. getBooksWithTag() bên dưới đã lọc đúng, search() thì
        // chưa - đồng bộ lại cho nhất quán.
        return db.query(TABLE_METADATA, columns, "title LIKE ? AND lpath IS NOT NULL",
                new String[] {"%"+s+"%"}, null, null, null);
    }
	public Cursor getBooksWithTag(String[] columns, int tag_id) {
		SQLiteDatabase db = this.getReadableDatabase();
		// Join all three tables, metadata, book_tag, tags based on
		// the many-to-many relationship on book_tag and returns the desired metadata
		return db.query(TABLE_METADATA	+ " INNER JOIN "+ TABLE_BOOK_TAG
							+ " ON " + TABLE_METADATA + "._id = " + TABLE_BOOK_TAG + ".metadata_id "
		//								+ " INNER JOIN " + TABLE_TAGS
		//					+ " ON " + TABLE_BOOK_TAG + ".tag_id = " + TABLE_TAGS + "._id"
							, 
							columns ,
//							new String[] {	TABLE_METADATA +"._id AS _id", TABLE_METADATA +".uuid AS uuid"
//											,TABLE_METADATA +".lpath AS lpath",TABLE_METADATA +".author AS author"
//											,TABLE_METADATA +".title AS title",TABLE_METADATA +".thumbnail AS thumbnail"
//											},
								TABLE_BOOK_TAG + String.format(".tag_id=%d AND ",tag_id) + TABLE_METADATA + ".lpath IS NOT NULL", null, null, null, null);
	}

    public int insertTag(String tag) {
        SQLiteDatabase db = this.getWritableDatabase();
        try {
            ContentValues cv = new ContentValues();
            cv.put("tag", tag);
            db.insertOrThrow(TABLE_TAGS, null, cv);
        } catch (SQLiteConstraintException e) {
            Logger.e(e);
        }
        Cursor cs = db.query(TABLE_TAGS, new String[] {"_id"}, "tag=?", new String[] { tag }, null, null, null);
        if (cs.moveToFirst()) {
            return cs.getInt(0);
        } else {
            Logger.d("Tag " + tag + " not found");
            return -1;
        }
    }

    /* Lấy danh sách tên thẻ của 1 quyển sách, trả về List<String> (dùng cho UI, khác bản JSON
       của getTagsForBookAsJSON). */
    public List<String> getTagNamesForBook(int metadataId) {
        List<String> result = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cs = db.query(TABLE_TAGS + " INNER JOIN " + TABLE_BOOK_TAG
                        + " ON " + TABLE_TAGS + "._id = " + TABLE_BOOK_TAG + ".tag_id",
                new String[] {TABLE_TAGS + ".tag"},
                TABLE_BOOK_TAG + ".metadata_id=?", new String[] {String.valueOf(metadataId)},
                null, null, TABLE_TAGS + ".tag ASC");
        try {
            while (cs.moveToNext()) result.add(cs.getString(0));
        } finally {
            cs.close();
        }
        return result;
    }

    /* Lấy toàn bộ tên thẻ đang có trong thư viện (dùng làm gợi ý autocomplete), sắp xếp A-Z. */
    public List<String> getAllTagNames() {
        List<String> result = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cs = db.query(TABLE_TAGS, new String[] {"tag"}, null, null, null, null, "tag ASC");
        try {
            while (cs.moveToNext()) result.add(cs.getString(0));
        } finally {
            cs.close();
        }
        return result;
    }

    /* Gắn 1 thẻ vào 1 quyển sách (tạo tên thẻ nếu chưa có, bỏ qua nếu đã gắn rồi -- không tạo
       dòng trùng trong book_tag). */
    public void addTagToBook(int metadataId, String tagName) {
        if (tagName == null) return;
        String trimmed = tagName.trim();
        if (trimmed.isEmpty()) return;
        int tagId = insertTag(trimmed);
        if (tagId < 0) return;
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("metadata_id", metadataId);
        cv.put("tag_id", tagId);
        try {
            db.insertOrThrow(TABLE_BOOK_TAG, null, cv);
        } catch (SQLiteConstraintException e) {
            // Đã gắn thẻ này cho sách này từ trước -- coi như thành công, không báo lỗi.
            Logger.d("Tag \"" + trimmed + "\" already attached to metadata_id " + metadataId);
        }
    }

    /* Gỡ 1 thẻ khỏi 1 quyển sách (không xoá tên thẻ khỏi bảng tags -- thẻ đó có thể vẫn đang
       gắn cho sách khác, chỉ xoá đúng 1 dòng quan hệ trong book_tag). */
    public void removeTagFromBook(int metadataId, String tagName) {
        if (tagName == null) return;
        SQLiteDatabase db = this.getWritableDatabase();
        Cursor cs = db.query(TABLE_TAGS, new String[] {"_id"}, "tag=?",
                new String[] {tagName.trim()}, null, null, null);
        int tagId = -1;
        try {
            if (cs.moveToFirst()) tagId = cs.getInt(0);
        } finally {
            cs.close();
        }
        if (tagId < 0) return;
        db.delete(TABLE_BOOK_TAG, "metadata_id=? AND tag_id=?",
                new String[] {String.valueOf(metadataId), String.valueOf(tagId)});
    }

	public Cursor getTagsWithBooks() {
		SQLiteDatabase db = this.getReadableDatabase();
		return db.query(TABLE_METADATA	+ " INNER JOIN "+ TABLE_BOOK_TAG
				+ " ON " + TABLE_METADATA + "._id = " + TABLE_BOOK_TAG + ".metadata_id "
								+ " INNER JOIN " + TABLE_TAGS
					+ " ON " + TABLE_BOOK_TAG + ".tag_id = " + TABLE_TAGS + "._id"
				, 
				new String[] {	TABLE_TAGS +"._id AS _id", TABLE_TAGS +".tag AS tag"
//								,TABLE_METADATA +".lpath AS lpath",TABLE_METADATA +".author AS author"
//								,TABLE_METADATA +".title AS title",TABLE_METADATA +".thumbnail AS thumbnail"
								},
								TABLE_METADATA + ".lpath IS NOT NULL", null, null, null, null);
	}

    /** @return distinct author names (of books actually on device, lpath IS NOT NULL) sorted
     * A-Z case-insensitively -- feeds CollectionFragment's "Filter by... > Author" screen,
     * mirroring getTagsWithBooks() above. Unlike tags, author has no id column of its own
     * (it's a free-text field on TABLE_METADATA copied straight from Calibre's author_sort),
     * so the query is a plain SELECT DISTINCT rather than a join. */
    public Cursor getAuthorsWithBooks() {
        SQLiteDatabase db = this.getReadableDatabase();
        return db.query(true, TABLE_METADATA, new String[]{"author"},
                "lpath IS NOT NULL AND author IS NOT NULL AND author != ''",
                null, null, null, "author COLLATE NOCASE ASC", null);
    }

    /** @return books on device by a given author (exact match against the same "author" value
     * getAuthorsWithBooks() listed), sorted by title. Mirrors getBooksWithTag() above. */
    public Cursor getBooksByAuthor(String[] columns, String author) {
        SQLiteDatabase db = this.getReadableDatabase();
        return db.query(TABLE_METADATA, columns, "lpath IS NOT NULL AND author=?",
                new String[]{author}, null, null, "title");
    }

    // --- Series / Publisher / Rating / Custom Column filters (DATABASE_VERSION 13) ----------
    // Mirrors getAuthorsWithBooks()/getBooksByAuthor() above -- "Filter by..." screens for the
    // Device Library tab, backing CollectionFragment#showFilterByDialog()'s new options.

    /** @return distinct series names (of books actually on device) sorted A-Z case-
     * insensitively -- feeds "Filter by... > Series". */
    public Cursor getSeriesWithBooks() {
        SQLiteDatabase db = this.getReadableDatabase();
        return db.query(true, TABLE_METADATA, new String[]{"series"},
                "lpath IS NOT NULL AND series IS NOT NULL AND series != ''",
                null, null, null, "series COLLATE NOCASE ASC", null);
    }

    /** @return books on device in a given series (exact match), sorted by series_index then
     * title -- unlike getBooksByAuthor()/getBooksWithTag() above (title-only), a series filter
     * reads far better in reading order (book 1, 2, 3...) than alphabetically by title. */
    public Cursor getBooksBySeries(String[] columns, String series) {
        SQLiteDatabase db = this.getReadableDatabase();
        return db.query(TABLE_METADATA, columns, "lpath IS NOT NULL AND series=?",
                new String[]{series}, null, null, "series_index ASC, title COLLATE NOCASE ASC");
    }

    /** @return distinct publisher names (of books actually on device) sorted A-Z case-
     * insensitively -- feeds "Filter by... > Publisher". */
    public Cursor getPublishersWithBooks() {
        SQLiteDatabase db = this.getReadableDatabase();
        return db.query(true, TABLE_METADATA, new String[]{"publisher"},
                "lpath IS NOT NULL AND publisher IS NOT NULL AND publisher != ''",
                null, null, null, "publisher COLLATE NOCASE ASC", null);
    }

    /** @return books on device from a given publisher (exact match), sorted by title. */
    public Cursor getBooksByPublisher(String[] columns, String publisher) {
        SQLiteDatabase db = this.getReadableDatabase();
        return db.query(TABLE_METADATA, columns, "lpath IS NOT NULL AND publisher=?",
                new String[]{publisher}, null, null, "title");
    }

    /** @return books on device rated at/above minRating (0-5 scale, matching the "rating" column
     * -- see CustomColumnSync's rating normalization note), sorted by rating (best first) then
     * title. Feeds "Filter by... > Rating". */
    public Cursor getBooksWithMinRating(String[] columns, double minRating) {
        SQLiteDatabase db = this.getReadableDatabase();
        return db.query(TABLE_METADATA, columns,
                "lpath IS NOT NULL AND rating IS NOT NULL AND rating>=?",
                new String[]{String.valueOf(minRating)}, null, null, "rating DESC, title COLLATE NOCASE ASC");
    }

    /** @return distinct values currently in use for one Custom Column (across all books on
     * device), sorted A-Z. For an is_multiple column (e.g. several genres per book) the stored
     * value_text is a single MULTI_JOIN-separated string per book -- this splits and dedupes
     * those into individual values in Java (SQLite has no easy DISTINCT-after-split), so a
     * 3-genre book contributes 3 separate filter choices rather than 1 unreadable joined blob. */
    public List<String> getDistinctCustomColumnValues(String lookupName) {
        List<String> result = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cs = db.query(TABLE_CUSTOM_COLUMN_VALUES, new String[]{"value_text"},
                "lookup_name=? AND value_text IS NOT NULL AND value_text != ''",
                new String[]{lookupName}, null, null, "value_text COLLATE NOCASE ASC");
        try {
            java.util.TreeSet<String> distinct = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            while (cs.moveToNext()) {
                String raw = cs.getString(0);
                for (String part : raw.split(String.valueOf(CustomColumnSync.MULTI_JOIN))) {
                    if (!part.isEmpty()) distinct.add(part);
                }
            }
            result.addAll(distinct);
        } finally {
            cs.close();
        }
        return result;
    }

    /** @return books on device whose Custom Column {@code lookupName} contains {@code value} --
     * an exact single-value match for non-multiple columns, or a "one of the joined values"
     * match for is_multiple columns (matches value_text either equal to value, or containing it
     * as one MULTI_JOIN-separated segment). Sorted by title. */
    public Cursor getBooksByCustomColumnValue(String[] columns, String lookupName, String value) {
        SQLiteDatabase db = this.getReadableDatabase();
        String table = TABLE_METADATA + " INNER JOIN " + TABLE_CUSTOM_COLUMN_VALUES + " cc"
                + " ON " + TABLE_METADATA + "._id = cc.metadata_id";
        String join = String.valueOf(CustomColumnSync.MULTI_JOIN);
        return db.query(table, columns,
                "cc.lookup_name=? AND " + TABLE_METADATA + ".lpath IS NOT NULL AND ("
                        + "cc.value_text=? OR cc.value_text LIKE ? OR cc.value_text LIKE ? OR cc.value_text LIKE ?)",
                new String[]{lookupName, value,
                        value + join + "%",      // gia tri dau tien trong danh sach
                        "%" + join + value + join + "%", // gia tri o giua
                        "%" + join + value},      // gia tri cuoi cung
                null, null, TABLE_METADATA + ".title");
    }

    /** Three reading-status buckets shown in CollectionFragment's "Filter by... >
     * Reading status" screen. NEW is easy (no locator saved at all); READING vs FINISHED needs
     * the actual progress %, which -- for EPUBs -- only ReadingProgressUtil (Java-side JSON
     * parsing of last_locator) can compute, so those two can't be expressed as a plain SQL
     * WHERE clause. See getBooksByReadingStatus() below. */
    public enum ReadingStatus { NEW, READING, FINISHED }

    /** Progress % at/above which a book is considered FINISHED rather than still READING.
     * Not exactly 100 since Readium's totalProgression rarely lands on an exact 1.0 right at
     * the last page. */
    private static final int FINISHED_PROGRESS_THRESHOLD = 99;

    /** @return books on device (lpath IS NOT NULL) matching the given reading-status bucket,
     * sorted by title. NEW is a plain SQL filter on last_locator; READING/FINISHED first pull
     * every book that has a saved locator, bucket each one in Java via
     * ReadingProgressUtil#progressPercentFromLocator (PDF/CBZ books have a locator but no
     * computable %, and are counted as still READING -- there's no way to tell they're
     * finished without the total page count, which last_locator doesn't carry), then re-query
     * for just the matching uuids so the returned Cursor still has the requested `columns`. */
    public Cursor getBooksByReadingStatus(String[] columns, ReadingStatus status) {
        SQLiteDatabase db = this.getReadableDatabase();
        if (status == ReadingStatus.NEW) {
            return db.query(TABLE_METADATA, columns,
                    "lpath IS NOT NULL AND last_locator IS NULL", null, null, null, "title");
        }
        List<String> matchingUuids = new ArrayList<>();
        Cursor cs = db.query(TABLE_METADATA, new String[]{"uuid", "last_locator"},
                "lpath IS NOT NULL AND last_locator IS NOT NULL", null, null, null, null);
        try {
            while (cs.moveToNext()) {
                String uuid = cs.getString(0);
                String locator = cs.getString(1);
                Integer percent = ReadingProgressUtil.progressPercentFromLocator(locator);
                boolean finished = percent != null && percent >= FINISHED_PROGRESS_THRESHOLD;
                if ((status == ReadingStatus.FINISHED) == finished) matchingUuids.add(uuid);
            }
        } finally {
            cs.close();
        }
        if (matchingUuids.isEmpty()) {
            // Empty cursor, but still with the right column shape -- callers (showBooks() via
            // BookRecyclerAdapter) expect a valid Cursor, not null, even with zero rows.
            return db.query(TABLE_METADATA, columns, "0", null, null, null, null);
        }
        StringBuilder placeholders = new StringBuilder();
        String[] args = new String[matchingUuids.size()];
        for (int i = 0; i < matchingUuids.size(); i++) {
            placeholders.append(i > 0 ? ",?" : "?");
            args[i] = matchingUuids.get(i);
        }
        return db.query(TABLE_METADATA, columns, "uuid IN (" + placeholders + ")", args, null, null, "title");
    }

    public void printMetadataTable() {
       String output = "";
       SQLiteDatabase db = this.getReadableDatabase();
       Cursor cs = db.query(TABLE_METADATA, new String[] {"uuid", "lpath"}, null, null, null, null, "lpath");
       cs.moveToFirst();
       while (!cs.isAfterLast()) {
           Logger.d("Book in DB: " + cs.getString(0) + "   " +cs.getString(1));
           cs.moveToNext();
       }
    }

    public void emptyAll() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_BOOK_TAG, null, null );
        db.delete(TABLE_METADATA, null, null );
        db.delete(TABLE_TAGS, null, null);

    }

    public byte[] insertJSON(JSONObject book) throws JSONException {
        String author, title, lpath;
        String[] tags = null;
        byte[] raw_thumb;
        // NOTE: JSONObject#optString() returns "" (not null) when a key is absent, so the
        // original `optString(...) != null` checks below always evaluated to true and then
        // called getString() on a possibly-missing key, which throws JSONException instead of
        // falling back to the next candidate field. Using has()/isNull() fixes the fallback
        // logic to actually work as intended.
        if (!book.has("uuid") || book.isNull("uuid") || book.optString("uuid").isEmpty()) {
            //broadcastError(ErrorType.BOOK_PATH_NULL);
            return null;
        }
        if (book.has("author_sort") && !book.isNull("author_sort") && !book.optString("author_sort").isEmpty())
            author = book.getString("author_sort");
        else if (book.optJSONArray("authors") != null &&
                book.getJSONArray("authors").optString(0) != null && !book.getJSONArray("authors").optString(0).isEmpty())
            author = book.getJSONArray("authors").getString(0);
        else {
            //broadcastError(ErrorType.BOOK_UUID_NULL);
            return null;
        }
        if (book.has("title") && !book.isNull("title") && !book.optString("title").isEmpty())
            title = book.getString("title");
        else if (book.has("title_sort") && !book.isNull("title_sort") && !book.optString("title_sort").isEmpty())
            title = book.getString("title_sort");
        else {
            //broadcastError(ErrorType.BOOK_TITLE_NULL);
            return null;
        }
        if (book.has("lpath") && !book.isNull("lpath"))
            lpath = book.getString("lpath");
        else {
            lpath = null;
        }
        if (book.optJSONArray("thumbnail") != null
                && book.getJSONArray("thumbnail").length() > 2) {
            // A malformed/truncated base64 string here used to throw IllegalArgumentException
            // straight out of insertJSON(), which -- since parseExistingMetadataFile() calls
            // insertJSON() in a loop with no per-item catch, and metadataRunnable wraps the
            // *entire* scan (parse + findNewBooks + fillMissingCovers + writeMetadataFile) in a
            // single catch(Throwable) -- silently aborted the whole crawl the moment it hit one
            // bad thumbnail, well before any of the later steps ran. Treat a bad thumbnail as
            // "no thumbnail" for this one book instead of failing the entire scan.
            try {
                raw_thumb = Base64.decode(book.getJSONArray("thumbnail").getString(2), Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                Logger.d("insertJSON: bad base64 thumbnail for uuid=" + book.optString("uuid") + ", skipping thumbnail: " + e);
                raw_thumb = null;
            }
        } else
            raw_thumb = null;

        if (book.optJSONArray("tags") != null) {
            JSONArray json_tags = book.getJSONArray("tags");
            tags = new String[json_tags.length()];
            for (int i = 0; i < json_tags.length(); i++) {
                tags[i] = json_tags.optString(i);
            }
        }

        // Recovered from a pre-existing metadata.calibre (e.g. app reinstall, or DB upgrade
        // followed by a rescan of a folder that already has a covers/ dir from a prior version)
        // -- lets BookCrawler#fillMissingCovers() skip sniffing this book's cover all over again.
        String coverPath = (book.has("cover_path") && !book.isNull("cover_path")
                && !book.optString("cover_path").isEmpty()) ? book.getString("cover_path") : null;

        // Mo ta/tom tat sach (Calibre "comments" field), dung de hien thi doan tom tat rut gon
        // trong Device Library che do List, giong Readest. Xem MetadataDatabaseHelper#saveComments.
        String comments = (book.has("comments") && !book.isNull("comments"))
                ? book.optString("comments") : null;

        // --- Cac field chuan truoc day bi bo qua (xem migration DATABASE_VERSION 13) ---
        String publisher = (book.has("publisher") && !book.isNull("publisher"))
                ? book.optString("publisher") : null;
        String pubdate = (book.has("pubdate") && !book.isNull("pubdate"))
                ? book.optString("pubdate") : null;
        String series = (book.has("series") && !book.isNull("series") && !book.optString("series").isEmpty())
                ? book.optString("series") : null;
        Double seriesIndex = (book.has("series_index") && !book.isNull("series_index"))
                ? book.optDouble("series_index") : null;
        if (seriesIndex != null && seriesIndex.isNaN()) seriesIndex = null;
        // Rating chuan cua Calibre luu thang 0-10 (nua sao = 2 don vi noi bo) -- chuan hoa ve
        // 0-5 giong CustomColumnSync/OPDSEntry.rating de nhat quan trong toan app.
        Double rating = (book.has("rating") && !book.isNull("rating"))
                ? book.optDouble("rating") / 2.0 : null;
        if (rating != null && rating.isNaN()) rating = null;
        String languagesCsv = null;
        JSONArray langsArr = book.optJSONArray("languages");
        if (langsArr != null && langsArr.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < langsArr.length(); i++) {
                if (i > 0) sb.append(',');
                sb.append(langsArr.optString(i));
            }
            languagesCsv = sb.toString();
        }
        String identifiersJson = null;
        JSONObject idsObj = book.optJSONObject("identifiers");
        if (idsObj != null && idsObj.length() > 0) identifiersJson = idsObj.toString();
        Long dateAdded = (book.has("timestamp") && !book.isNull("timestamp"))
                ? OPDSFormatUtils.parseIsoToEpochMillisOrNull(book.optString("timestamp")) : null;
        Long fileSize = (book.has("size") && !book.isNull("size")) ? book.optLong("size") : null;

        CustomColumnSync.ParsedCustomColumns customCols = CustomColumnSync.parseFromWireJson(book);

        this.insertMetadata(book.getString("uuid"), lpath, author, title, tags, raw_thumb, coverPath,
                publisher, pubdate, series, seriesIndex, rating, languagesCsv, identifiersJson,
                dateAdded, fileSize);
        if (comments != null && !comments.isEmpty()) {
            saveComments(book.getString("uuid"), comments);
        }
        if (!customCols.defs.isEmpty() || !customCols.values.isEmpty()) {
            Cursor idCs = getByUuid(new String[]{"_id"}, book.getString("uuid"));
            int metadataId = -1;
            try {
                if (idCs.moveToFirst()) metadataId = idCs.getInt(0);
            } finally {
                idCs.close();
            }
            if (metadataId >= 0) {
                if (!customCols.defs.isEmpty()) upsertCustomColumnDefs(customCols.defs);
                if (!customCols.values.isEmpty()) upsertCustomColumnValues(metadataId, customCols.values);
            }
        }
        return raw_thumb;
    }

    /** @return every book that currently has an lpath (i.e. is present on-device) as a JSON
     * array in the on-disk metadata.calibre format used by Calibre desktop
     * (usbms/driver.py METADATA_CACHE), for BookCrawler to write to disk. Symmetric with
     * insertJSON() above -- whatever field insertJSON() reads back in must be written out here. */
    public JSONArray exportAllBooksAsJSON() {
        JSONArray result = new JSONArray();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cs = db.query(TABLE_METADATA,
                new String[] {"_id", "uuid", "lpath", "author", "title", "thumbnail", "cover_path", "comments",
                        "publisher", "pubdate", "series", "series_index", "rating", "languages",
                        "identifiers_json", "date_added", "file_size"},
                "lpath IS NOT NULL", null, null, null, "title");
        try {
            while (cs.moveToNext()) {
                int metadataId = cs.getInt(0);
                String uuid = cs.getString(1);
                String lpath = cs.getString(2);
                String author = cs.getString(3);
                String title = cs.getString(4);
                byte[] thumbnail = cs.getBlob(5);
                String coverPath = cs.getString(6);
                String comments = cs.getString(7);
                String publisher = cs.getString(8);
                String pubdate = cs.getString(9);
                String series = cs.getString(10);
                Double seriesIndex = cs.isNull(11) ? null : cs.getDouble(11);
                Double rating = cs.isNull(12) ? null : cs.getDouble(12);
                String languagesCsv = cs.getString(13);
                String identifiersJson = cs.getString(14);
                Long dateAdded = cs.isNull(15) ? null : cs.getLong(15);
                String fileSizeStr = cs.isNull(16) ? null : String.valueOf(cs.getLong(16));
                JSONObject book = new JSONObject();
                try {
                    book.put("uuid", uuid);
                    book.put("lpath", lpath);
                    book.put("title", title);
                    book.put("title_sort", title);
                    JSONArray authors = new JSONArray();
                    if (author != null) authors.put(author);
                    book.put("authors", authors);
                    book.put("author_sort", author);
                    book.put("tags", getTagsForBookAsJSON(metadataId));
                    if (coverPath != null) book.put("cover_path", coverPath);
                    if (comments != null) book.put("comments", comments);
                    if (publisher != null) book.put("publisher", publisher);
                    if (pubdate != null) book.put("pubdate", pubdate);
                    if (series != null) book.put("series", series);
                    if (seriesIndex != null) book.put("series_index", seriesIndex);
                    // Nhan lai 2 khi export -- dao nguoc dung chinh xac phep chia 2 luc insertJSON
                    // doc vao, de gia tri gui nguoc lai Calibre Desktop dung thang 0-10 goc.
                    if (rating != null) book.put("rating", rating * 2.0);
                    if (languagesCsv != null && !languagesCsv.isEmpty()) {
                        JSONArray langs = new JSONArray();
                        for (String lang : languagesCsv.split(",")) {
                            if (!lang.isEmpty()) langs.put(lang);
                        }
                        book.put("languages", langs);
                    }
                    if (identifiersJson != null) {
                        try {
                            book.put("identifiers", new JSONObject(identifiersJson));
                        } catch (JSONException je) {
                            Logger.e(je);
                        }
                    }
                    if (fileSizeStr != null) book.put("size", Long.parseLong(fileSizeStr));
                    if (dateAdded != null) {
                        book.put("timestamp", java.time.Instant.ofEpochMilli(dateAdded).toString());
                    }
                    exportCustomColumnsInto(book, metadataId);
                    if (thumbnail != null) {
                        JSONArray thumb = new JSONArray();
                        thumb.put("jpeg");
                        thumb.put(0); // width isn't reliably known here -- 0 is a harmless
                        thumb.put(0); // placeholder, only ever read back by this app's own cache.
                        thumb.put(Base64.encodeToString(thumbnail, Base64.NO_WRAP));
                        book.put("thumbnail", thumb);
                    }
                } catch (JSONException e) {
                    Logger.e(e);
                    continue;
                }
                result.put(book);
            }
        } finally {
            cs.close();
        }
        return result;
    }

    // --- Ho tro DeviceLibrarySyncManager (Google Drive 2-chieu sync, Device Library tab) -----

    /** @return uuid cua tat ca sach dang co lpath tren may -- dung cho "sync all" (uuidsOrNull
     * == null trong DeviceLibrarySyncManager#uploadBooks). */
    public String[] getAllUuidsWithLpathArray() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cs = db.query(TABLE_METADATA, new String[]{"uuid"}, "lpath IS NOT NULL",
                null, null, null, null);
        try {
            List<String> result = new ArrayList<>();
            while (cs.moveToNext()) {
                if (!cs.isNull(0)) result.add(cs.getString(0));
            }
            return result.toArray(new String[0]);
        } finally {
            cs.close();
        }
    }

    /** Danh sach uuid co row trong DB nhung lpath = NULL -- ung cu vien "da bi xoa chu dich khoi
     * thiet bi nay" (xem muc 3.3 va Phase 5 cua prompt nang cap Device Library Sync). Khac voi
     * getAllUuidsWithLpathArray() (lpath IS NOT NULL, dung cho upload), ham nay phuc vu buoc ghi
     * tombstone vao manifest truoc khi upload -- DeviceLibrarySyncManager chi tombstone entry ma
     * manifest remote DA TUNG biet toi, nen row rac cuc bo (chua bao gio len cloud) khong bi anh
     * huong. */
    public String[] getAllUuidsWithNullLpathArray() {
        SQLiteDatabase db = this.getReadableDatabase();
        // BUG FIX (xem migration DATABASE_VERSION 16): truoc day chi loc "lpath IS NULL" nen
        // nham lan ca nhung hang chi la cache "xem truoc" (GDriveBookEntryBuilder/OPDSLoader
        // insertMetadata(uuid, null, ...) cho sach CHUA TUNG tai ve may nay) voi sach THAT SU bi
        // xoa khoi Device Library cuc bo -- khien DeviceLibrarySyncManager tombstone + xoa nham
        // thu muc remote cua sach chua he ton tai cuc bo. Them dieu kien has_local_file=1: chi
        // hang nao tung duoc BookCrawler/insertJSON gan lpath THAT (tuc dung la mot phan cua
        // Device Library tren may nay) moi duoc coi la ung cu vien "da xoa".
        Cursor cs = db.query(TABLE_METADATA, new String[]{"uuid"},
                "lpath IS NULL AND has_local_file=1",
                null, null, null, null);
        try {
            List<String> result = new ArrayList<>();
            while (cs.moveToNext()) {
                if (!cs.isNull(0)) result.add(cs.getString(0));
            }
            return result.toArray(new String[0]);
        } finally {
            cs.close();
        }
    }

    /** @return uuid tuong ung voi tap _id da chon (swipe 1 sach hoac multi-select nhieu sach
     * trong CollectionFragment) -- uuid la khoa on dinh, khac voi _id co the doi sau lan
     * rescan/reinsert. */
    public String[] getUuidsForIds(int[] ids) {
        if (ids == null || ids.length == 0) return new String[0];
        StringBuilder placeholders = new StringBuilder();
        String[] args = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
            args[i] = String.valueOf(ids[i]);
        }
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cs = db.query(TABLE_METADATA, new String[]{"uuid"},
                "_id IN (" + placeholders + ")", args, null, null, null);
        try {
            List<String> result = new ArrayList<>();
            while (cs.moveToNext()) {
                if (!cs.isNull(0)) result.add(cs.getString(0));
            }
            return result.toArray(new String[0]);
        } finally {
            cs.close();
        }
    }

    /** @return {uuid -> [last_locator, last_locator_updated_at, comments]} cho moi sach co
     * lpath, dung de export sang calibre_sync_progress.json. Caller phai dong Cursor. */
    public Cursor getAllProgressForSync() {
        SQLiteDatabase db = this.getReadableDatabase();
        return db.query(TABLE_METADATA,
                new String[]{"uuid", "last_locator", "last_locator_updated_at", "comments"},
                "lpath IS NOT NULL", null, null, null, null);
    }

    /** @return toan bo bookmark/highlight/note cua 1 sach (moi cot cua TABLE_ANNOTATIONS, KE CA
     * cac hang da xoa mem -- deleted_at != NULL), dung de export sang calibre_sync_progress.json.
     * Co Y de LAI hang da xoa mem trong ket qua nay (khac voi getAnnotations() dung cho UI, von
     * loc deleted_at IS NULL) -- SyncProgress can biet ca annotation nao VUA bi xoa de ghi
     * tombstone (deleted:true) len cloud, khong chi annotation nao con song (xem
     * DATABASE_VERSION 15 bug fix). Caller phai dong Cursor. */
    public Cursor getAnnotationsForSync(String uuid) {
        SQLiteDatabase db = this.getReadableDatabase();
        return db.query(TABLE_ANNOTATIONS, null, "uuid = ?", new String[]{uuid}, null, null, null);
    }

    /** @return {uuid -> {unit_key -> elapsed_ms}} cho toan bo sach, dung de export sang
     * calibre_sync_progress.json (readingTimeMs field). */
    public java.util.Map<String, java.util.Map<String, Long>> getAllReadingTimeForSync() {
        java.util.Map<String, java.util.Map<String, Long>> result = new java.util.HashMap<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cs = db.query(TABLE_READING_TIME, new String[]{"uuid", "unit_key", "elapsed_ms"},
                null, null, null, null, null);
        try {
            while (cs.moveToNext()) {
                String uuid = cs.getString(0);
                java.util.Map<String, Long> perUnit = result.get(uuid);
                if (perUnit == null) {
                    perUnit = new java.util.HashMap<>();
                    result.put(uuid, perUnit);
                }
                perUnit.put(cs.getString(1), cs.getLong(2));
            }
        } finally {
            cs.close();
        }
        return result;
    }

    /** Ghi de truc tiep gia tri elapsed_ms cho 1 unit_key -- khac voi addReadingTimeMs (cong
     * don) vi merge tu cloud can dung nguyen tac max(local, remote), khong duoc cong don (xem
     * nguyen tac merge trong DeviceLibrarySyncManager). */
    public void setReadingTimeMs(String uuid, String unitKey, long elapsedMs) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("uuid", uuid);
        cv.put("unit_key", unitKey);
        cv.put("elapsed_ms", elapsedMs);
        int rows = db.update(TABLE_READING_TIME, cv, "uuid=? AND unit_key=?", new String[]{uuid, unitKey});
        if (rows == 0) {
            db.insertOrThrow(TABLE_READING_TIME, null, cv);
        }
    }

    /** Ghi de comments chi khi local dang rong/null -- xem nguyen tac merge muc 3.4 cua prompt:
     * comments den tu Calibre desktop, khong co timestamp rieng nen khong the so sanh "moi hon". */
    public void saveCommentsIfEmpty(String uuid, String comments) {
        if (comments == null || comments.isEmpty()) return;
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("comments", comments);
        db.update(TABLE_METADATA, cv, "uuid=? AND (comments IS NULL OR comments = '')", new String[]{uuid});
    }

    /** Bo sung (khong ghi de) publisher/pubdate/series/series_index/rating/languages/
     * identifiers_json cho 1 dong DA CO SAN trong TABLE_METADATA, CHI KHI cot tuong ung dang
     * NULL/rong -- dung khi duyet lai OPDS mot cuon sach da tung duoc them vao DB tu truoc khi
     * ban vá cham nay (prompt-nang-cap-opds-metadata-day-du.md) ton tai, de sach do khong con
     * "mo côi" thieu Publication info vinh vien chi vi lan dau tai ve da bo qua cac field nay.
     * KHONG dung cho sach MOI (dung insertMetadata() ban day du thay the) -- va CO Y KHONG dung
     * insertMetadata() o day, vi insertMetadata() luon cv.putNull("lpath") khi lpath truyen vao
     * la null, se xoa mat trang thai "da tai ve" cua 1 sach da co file tren may. Moi tham so la
     * OPTIONAL (bo qua neu null); tung cot chi duoc cap nhat khi gia tri hien tai cua no dang
     * rong, giong nguyen tac cua saveCommentsIfEmpty o tren. */
    public void backfillExtendedMetadataIfMissing(String uuid, String publisher, String pubdate,
            String series, Double seriesIndex, Double rating, String languagesCsv, String identifiersJson) {
        SQLiteDatabase db = this.getWritableDatabase();
        if (publisher != null) {
            ContentValues cv = new ContentValues();
            cv.put("publisher", publisher);
            db.update(TABLE_METADATA, cv, "uuid=? AND (publisher IS NULL OR publisher = '')", new String[]{uuid});
        }
        if (pubdate != null) {
            ContentValues cv = new ContentValues();
            cv.put("pubdate", pubdate);
            db.update(TABLE_METADATA, cv, "uuid=? AND (pubdate IS NULL OR pubdate = '')", new String[]{uuid});
        }
        if (series != null) {
            ContentValues cv = new ContentValues();
            cv.put("series", series);
            if (seriesIndex != null) cv.put("series_index", seriesIndex);
            db.update(TABLE_METADATA, cv, "uuid=? AND (series IS NULL OR series = '')", new String[]{uuid});
        }
        if (rating != null) {
            ContentValues cv = new ContentValues();
            cv.put("rating", rating);
            db.update(TABLE_METADATA, cv, "uuid=? AND rating IS NULL", new String[]{uuid});
        }
        if (languagesCsv != null) {
            ContentValues cv = new ContentValues();
            cv.put("languages", languagesCsv);
            db.update(TABLE_METADATA, cv, "uuid=? AND (languages IS NULL OR languages = '')", new String[]{uuid});
        }
        if (identifiersJson != null) {
            ContentValues cv = new ContentValues();
            cv.put("identifiers_json", identifiersJson);
            db.update(TABLE_METADATA, cv, "uuid=? AND (identifiers_json IS NULL OR identifiers_json = '')", new String[]{uuid});
        }
    }

    // --- Custom Column (EAV) helpers, DATABASE_VERSION 13 -- xem CustomColumnSync ------------

    /** Ghi/cap nhat dinh nghia cot (khong dung REPLACE de khong reset sort_order ve 0 moi lan
     * dong bo lai -- nguoi dung co the da sap xep lai thu tu cot). An toan goi lap lai nhieu lan
     * (idempotent) cho cung 1 lookup_name. */
    public void upsertCustomColumnDefs(List<CustomColumnSync.ColumnDef> defs) {
        if (defs == null || defs.isEmpty()) return;
        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        try {
            for (CustomColumnSync.ColumnDef d : defs) {
                ContentValues cv = new ContentValues();
                cv.put("lookup_name", d.lookupName);
                cv.put("label", d.label);
                cv.put("datatype", d.datatype);
                cv.put("is_multiple", d.isMultiple ? 1 : 0);
                cv.put("display_json", d.rawDisplayJson);
                db.insertWithOnConflict(TABLE_CUSTOM_COLUMN_DEFS, null, cv,
                        SQLiteDatabase.CONFLICT_IGNORE);
                cv.remove("lookup_name");
                db.update(TABLE_CUSTOM_COLUMN_DEFS, cv, "lookup_name=?",
                        new String[]{d.lookupName});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Ghi/cap nhat gia tri cot cho 1 cuon sach (REPLACE toan bo, khac voi defs o tren, vi gia
     * tri thi luon lay ban moi nhat tu Calibre Desktop, khong co "sort_order" nao can giu lai). */
    public void upsertCustomColumnValues(int metadataId, List<CustomColumnSync.ColumnValue> values) {
        if (values == null || values.isEmpty()) return;
        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        try {
            for (CustomColumnSync.ColumnValue v : values) {
                ContentValues cv = new ContentValues();
                cv.put("metadata_id", metadataId);
                cv.put("lookup_name", v.lookupName);
                cv.put("value_text", v.valueText);
                if (v.valueNum != null) cv.put("value_num", v.valueNum); else cv.putNull("value_num");
                db.insertWithOnConflict(TABLE_CUSTOM_COLUMN_VALUES, null, cv,
                        SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** @return danh sach dinh nghia custom column da tung thay (hoc duoc tu it nhat 1 lan dong
     * bo), sap xep theo sort_order roi label -- dung lam nguon "danh sach cot dong" cho UI
     * sort/filter/hieu dinh sau nay (xem ColumnRegistry). */
    public List<CustomColumnSync.ColumnDef> getAllCustomColumnDefs() {
        List<CustomColumnSync.ColumnDef> result = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cs = db.query(TABLE_CUSTOM_COLUMN_DEFS,
                new String[]{"lookup_name", "label", "datatype", "is_multiple", "display_json"},
                null, null, null, null, "sort_order ASC, label COLLATE NOCASE ASC");
        try {
            while (cs.moveToNext()) {
                result.add(new CustomColumnSync.ColumnDef(
                        cs.getString(0), cs.getString(1), cs.getString(2),
                        cs.getInt(3) != 0, cs.getString(4)));
            }
        } finally {
            cs.close();
        }
        return result;
    }

    /** @return {lookup_name -> value_text} cho tat ca custom column da co gia tri cua 1 cuon
     * sach. value_text cho cot is_multiple da duoc noi bang CustomColumnSync.MULTI_JOIN, tach ra
     * o tang goi (giong getDistinctCustomColumnValues du dinh cho Phase 3) chu khong tach o day. */
    public java.util.Map<String, String> getCustomColumnValuesForBook(int metadataId) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cs = db.query(TABLE_CUSTOM_COLUMN_VALUES, new String[]{"lookup_name", "value_text"},
                "metadata_id=?", new String[]{String.valueOf(metadataId)}, null, null, null);
        try {
            while (cs.moveToNext()) {
                if (!cs.isNull(1)) result.put(cs.getString(0), cs.getString(1));
            }
        } finally {
            cs.close();
        }
        return result;
    }

    /** Xay lai khoi "user_metadata" cho 1 cuon sach vao trong {@code book} tu du lieu SQLite,
     * dung cho exportAllBooksAsJSON() o tren -- thin wrapper quanh
     * CustomColumnSync#applyValuesIntoWireJson (logic reverse-mapping dung chung voi
     * DeviceBookMetadataWriter, xem javadoc cua ham do). */
    private void exportCustomColumnsInto(JSONObject book, int metadataId) {
        List<CustomColumnSync.ColumnDef> defs = getAllCustomColumnDefs();
        if (defs.isEmpty()) return;
        java.util.Map<String, String> values = getCustomColumnValuesForBook(metadataId);
        if (values.isEmpty()) return;
        CustomColumnSync.applyValuesIntoWireJson(book, defs, values);
    }

    private JSONArray getTagsForBookAsJSON(int metadataId) {
        JSONArray tags = new JSONArray();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cs = db.query(TABLE_TAGS + " INNER JOIN " + TABLE_BOOK_TAG
                        + " ON " + TABLE_TAGS + "._id = " + TABLE_BOOK_TAG + ".tag_id",
                new String[] {TABLE_TAGS + ".tag"},
                TABLE_BOOK_TAG + ".metadata_id=?", new String[] {String.valueOf(metadataId)},
                null, null, null);
        try {
            while (cs.moveToNext()) tags.put(cs.getString(0));
        } finally {
            cs.close();
        }
        return tags;
    }
}
