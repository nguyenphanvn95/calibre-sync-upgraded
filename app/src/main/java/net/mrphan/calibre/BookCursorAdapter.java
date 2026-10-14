/**
 * 
 */
package net.mrphan.calibre;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.util.Map;

/**
 * @author sengjea
 *
 */
public class BookCursorAdapter extends CursorAdapter {

	/** Cover thumbnails are decoded downsampled to roughly this box (see
	 * CoverManager#decodeSampledBitmapFromFile) instead of at full resolution -- the row/grid
	 * cell they're shown in is always much smaller than a typical cover image, and decoding full
	 * size for every bound row would stutter scrolling on long lists. */
	private static final int THUMB_MAX_WIDTH = 300;
	private static final int THUMB_MAX_HEIGHT = 450;

	/** Whether to inflate the compact grid item (book_item_grid.xml, cover + title/author
	 * stacked vertically) instead of the classic horizontal list row (book_item.xml). Only the
	 * Calibre Library tab (OPDSBrowserFragment) currently offers a grid display mode; the
	 * Device Library tab (CollectionFragment) and search results (BookSearchActivity) keep
	 * using the 3-arg constructor below, which defaults to the list layout. */
	private final boolean mGridMode;
	/** OPDSPage backing the current cursor, used to look up OPDSEntry.summary by uuid for the
	 * comment/synopsis line (List mode only). Null when this adapter has no page context (e.g.
	 * the old 3/4-arg constructors), in which case the comment row is simply left hidden. */
	private final OPDSPage mPage;
	/** uuid -> reading progress/comments extras, batch-loaded once by the caller (see
	 * MetadataDatabaseHelper#getAllBookExtras). Null if not supplied -- progress row stays
	 * hidden. */
	private final Map<String, MetadataDatabaseHelper.BookExtras> mProgressMap;
	/** Style Grid dang ap dung (chi anh huong khi mGridMode=true) -- xem GridDisplayStyle.java.
	 * Constructor cu (khong truyen tham so nay) mac dinh DEFAULT, khong doi hanh vi hien tai o
	 * BookSearchActivity va cac cho goi khac chua duoc cap nhat. */
	private final GridDisplayStyle mGridStyle;

	public BookCursorAdapter(Context context, Cursor cursor, boolean autoRequery) {
		this(context, cursor, autoRequery, false, null, null, GridDisplayStyle.DEFAULT);
	}

	public BookCursorAdapter(Context context, Cursor cursor, boolean autoRequery, boolean gridMode) {
		this(context, cursor, autoRequery, gridMode, null, null, GridDisplayStyle.DEFAULT);
	}

	/** Dung o OPDSBrowserFragment khi can hien thi comment/progress kieu Readest, style Default. */
	public BookCursorAdapter(Context context, Cursor cursor, boolean autoRequery, boolean gridMode,
							  OPDSPage page, Map<String, MetadataDatabaseHelper.BookExtras> progressMap) {
		this(context, cursor, autoRequery, gridMode, page, progressMap, GridDisplayStyle.DEFAULT);
	}

	/** Constructor day du, cho phep chon Style Grid -- dung o OPDSBrowserFragment tu ban nang cap
	 * "Style Grid view" nay tro di. */
	public BookCursorAdapter(Context context, Cursor cursor, boolean autoRequery, boolean gridMode,
							  OPDSPage page, Map<String, MetadataDatabaseHelper.BookExtras> progressMap,
							  GridDisplayStyle gridStyle) {
		super(context, cursor, autoRequery);
		mGridMode = gridMode;
		mPage = page;
		mProgressMap = progressMap;
		mGridStyle = (gridStyle != null) ? gridStyle : GridDisplayStyle.DEFAULT;
	}

	@Override
	public void bindView(View view, Context context, Cursor cs) {
		try {
			ImageView thumb = (ImageView) view.findViewById(R.id.bi_thumbnail);
			bindCoverThumbnail(thumb, context, cs);
			TextView title = (TextView) view.findViewById(R.id.bi_title);
			TextView author = (TextView) view.findViewById(R.id.bi_author);
			TextView uuid = (TextView) view.findViewById(R.id.bi_uuid);
			title.setText(cs.getString(Book.TITLE));
			// pref_grid_show_title_progress: doc 1 lan o day, dung lai cho ca title lan progress
			// ben duoi -- chi anh huong mGridMode, List/Simple list/Carousel luon hien title.
			boolean showGridTitleProgress = true;
			if (mGridMode) {
				SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
				// Style Bookshelf khong bao gio hien tua/tien trinh, bat ke gia tri pref da luu.
				// Style Simple 2 thi nguoc lai: LUON hien tua/tien trinh, bat ke gia tri pref da
				// luu -- nguoi dung khong duoc tu tat o style nay (xem
				// SettingsFragment#applyGridShowTitleProgressLockState).
				showGridTitleProgress = (settings.getBoolean(StorageUtils.PREF_GRID_SHOW_TITLE_PROGRESS, true)
						|| mGridStyle == GridDisplayStyle.SIMPLE2)
						&& mGridStyle != GridDisplayStyle.BOOKSHELF;
				title.setVisibility(showGridTitleProgress ? View.VISIBLE : View.GONE);
			} else {
				title.setVisibility(View.VISIBLE);
			}
			// Grid layout no longer has bi_author (dropped to match Readest); findViewById
			// returns null there, so guard before setText -- keeps this safe for both layouts.
			if (author != null) {
				author.setText(cs.getString(Book.AUTHOR));
			}
			uuid.setText(cs.getString(Book.UUID));

			String bookUuid = cs.getString(Book.UUID);

			// Badge dinh dang (EPUB/PDF/CBZ...), chi book_item_grid_simple2.xml co id
			// bi_format_badge; cac layout khac khong co nen findViewById tra null.
			TextView formatBadge = (TextView) view.findViewById(R.id.bi_format_badge);
			if (formatBadge != null) {
				String label = BookFormatUtils.formatLabelFromPath(cs.getString(Book.LPATH));
				if (label != null) {
					formatBadge.setText(label);
					formatBadge.setVisibility(View.VISIBLE);
				} else {
					formatBadge.setVisibility(View.GONE);
				}
			}

			// Comment/synopsis, chi book_item.xml (List) co id bi_comment; grid khong co nen
			// findViewById tra null, phai check truoc khi dung.
			TextView comment = (TextView) view.findViewById(R.id.bi_comment);
			if (comment != null) {
				String rawSummary = (mPage != null) ? mPage.getSummaryForUuid(bookUuid) : null;
				// entry.summary trong OPDSEntry co the chua the HTML (p, br...) do Calibre sinh feed,
				// phai strip truoc khi hien thi, giong Readest, khong duoc hien nguyen the tho.
				String summary = HtmlTextUtils.stripHtml(rawSummary);
				if (summary != null) {
					comment.setText(summary);
					comment.setVisibility(View.VISIBLE);
				} else {
					comment.setVisibility(View.GONE);
				}
			}

			// % tien trinh -- co ca o list lan grid.
			TextView progress = (TextView) view.findViewById(R.id.bi_progress);
			if (progress != null) {
				Integer percent = (mProgressMap != null && mProgressMap.containsKey(bookUuid))
						? mProgressMap.get(bookUuid).progressPercent : null;
				boolean progressAllowedHere = !mGridMode || showGridTitleProgress;
				if (percent != null && progressAllowedHere) {
					progress.setText(percent + "%");
					progress.setVisibility(View.VISIBLE);
				} else {
					progress.setVisibility(View.GONE);
				}
			}

		} catch (Exception e) {
			Logger.e(e);
		}
	}

	/** Cache-key on dinh cho 1 lan bind: uu tien cover_path tren dia (giong thu tu uu tien trong
	 * decodeCoverOffThread ben duoi); fallback ve "blob:<uuid>" khi khong co file tren dia, de van
	 * co the cache duoc cover lay tu BLOB SQLite (usbms tu Calibre Desktop). */
	private static String coverCacheKey(Context context, Cursor cs) {
		try {
			int coverIdx = Book.COVER_PATH;
			if (coverIdx < cs.getColumnCount() && !cs.isNull(coverIdx)) {
				String coverPath = cs.getString(coverIdx);
				if (coverPath != null && !coverPath.isEmpty()) return "file:" + coverPath;
			}
		} catch (Exception ignored) { }
		String uuid = cs.getString(Book.UUID);
		return uuid != null ? "blob:" + uuid : null;
	}

	/** Set cover cho 1 ImageView, uu tien cache trong bo nho (CoverCache), decode bat dong bo
	 * (CoverDecodeExecutor) khi cache miss. Dung ImageView#setTag() de danh dau "row nay dang cho
	 * cover uuid X" -- day la ky thuat chuan cho van de kinh dien cua ListView/GridView + async
	 * image loading: khi decode xong (co the mat vai chuc ms), row co the DA BI TAI SU DUNG cho
	 * item khac (user scroll nhanh). Neu khong check tag truoc khi setImageBitmap, anh cua item cu
	 * se bi ve nham len item moi trong 1 khoanh khac roi moi duoc ghi de dung -- hieu ung "nhay anh". */
	private static void bindCoverThumbnail(ImageView thumb, Context context, Cursor cs) {
		CoverCache.init(context);
		String cacheKey = coverCacheKey(context, cs);
		String bindToken = cs.getString(Book.UUID); // dung de so khop row khi callback ve
		thumb.setTag(bindToken);

		Bitmap cached = CoverCache.getBitmap(cacheKey);
		if (cached != null) {
			thumb.setImageBitmap(cached);
			return;
		}

		// Placeholder ngay lap tuc -- truoc day decode chay dong bo nen "khong co luc dang tai";
		// gio decode chay async nen PHAI set placeholder truoc, neu khong row vua duoc tai su dung
		// se hien tam anh cua item cu cho toi khi decode xong (xem upgrade_1.md muc 4.2).
		thumb.setImageResource(R.drawable.dumbbook);

		Context appContext = context.getApplicationContext();
		// Snapshot du lieu can cho decode NGAY BAY GIO (tren main thread) -- Cursor KHONG an toan
		// de doc tu background thread sau khi con tro cursor da di chuyen sang row khac.
		int coverIdx = Book.COVER_PATH;
		String coverPath = (coverIdx < cs.getColumnCount() && !cs.isNull(coverIdx))
				? cs.getString(coverIdx) : null;
		byte[] blobFallback;
		try {
			blobFallback = cs.getBlob(Book.THUMB);
		} catch (Exception e) {
			blobFallback = null;
		}
		byte[] finalBlobFallback = blobFallback;

		CoverDecodeExecutor.decodeAsync(() -> decodeCoverOffThread(appContext, coverPath, finalBlobFallback),
				bitmap -> {
					// Row co the da bi recycle sang item khac trong luc decode -- chi set anh neu
					// tag hien tai VAN con la bindToken luc enqueue.
					if (bitmap != null && bindToken != null && bindToken.equals(thumb.getTag())) {
						CoverCache.putBitmap(cacheKey, bitmap);
						thumb.setImageBitmap(bitmap);
					}
					// Bitmap null (khong tim thay cover o ca 2 nguon): giu nguyen placeholder da
					// set o tren, khong can lam gi them.
				});
	}

	/** Chay tren background thread (CoverDecodeExecutor) -- KHONG duoc dong Cursor o day, chi dung
	 * cac gia tri da snapshot san (coverPath/blobFallback), giu dung thu tu uu tien nhu truoc day:
	 * (1) file tren dia, (2) BLOB SQLite, (3) null. */
	private static Bitmap decodeCoverOffThread(Context appContext, String coverPath, byte[] blobFallback) {
		if (coverPath != null && !coverPath.isEmpty()) {
			try {
				SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(appContext);
				File rootDir = StorageUtils.getRootDirectory(appContext, settings);
				File coverFile = new File(rootDir, coverPath);
				if (coverFile.exists()) {
					Bitmap fromFile = CoverManager.decodeSampledBitmapFromFile(
							coverFile, THUMB_MAX_WIDTH, THUMB_MAX_HEIGHT);
					if (fromFile != null) return fromFile;
				}
			} catch (Exception e) {
				Logger.e(e);
			}
		}
		if (blobFallback != null) {
			try {
				Bitmap fromBlob = BitmapFactory.decodeByteArray(blobFallback, 0, blobFallback.length);
				if (fromBlob != null) return fromBlob;
			} catch (Exception ignored) { }
		}
		return null;
	}

	@Override
	public View newView(Context context, Cursor cs, ViewGroup parent) {
		LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		int layoutRes;
		if (!mGridMode) {
			layoutRes = R.layout.book_item;
		} else {
			switch (mGridStyle) {
				case BOOKSHELF: layoutRes = R.layout.book_item_grid_bookshelf; break;
				case SIMPLE2: layoutRes = R.layout.book_item_grid_simple2; break;
				default: layoutRes = R.layout.book_item_grid; break;
			}
		}
		// Fix: inflate(layoutRes, null) bo qua LayoutParams cua parent (ViewGroup truyen vao) --
		// khien cac thuoc tinh layout_width/layout_height dang dung match_parent/wrap_content
		// dinh nghia trong XML co the khong duoc ap dung dung khi view duoc them vao parent sau
		// do, gay ra kich thuoc row sai lech tren mot so thiet bi/API level. Truyen parent + false
		// (khong attach ngay) la cach lam dung chuan cua Android cho CursorAdapter#newView.
		return inflater.inflate(layoutRes, parent, false);
	}
	@Override
	protected void onContentChanged() {
		Logger.d("BookCursorAdapter.onContentChanged()");
	}

}
