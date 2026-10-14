package net.mrphan.calibre;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Màn hình "Hiệu đính metadata" cho 1 sách trong Device Library -- sửa đầy đủ metadata (không
 * chỉ Local tags như TagEditorDialog cũ) và (tùy chọn) ảnh bìa, mở từ DeviceBookDetailActivity's
 * nút "Hiệu đính" (trước đây là nút "Local tags"). Xem prompt-hieu-dinh-metadata-editor.md.
 * <p>
 * Hai nút Lưu / Lưu vào file ebook:
 * - "Lưu": chỉ ghi metadata.calibre (qua DeviceBookMetadataWriter, patch đúng 1 entry) + đồng
 *   bộ cột rút gọn trong SQLite. Không đụng file ebook gốc.
 * - "Lưu vào file ebook": làm mọi việc của "Lưu", cộng thêm nhúng metadata (+ bìa mới nếu có)
 *   trực tiếp vào file ebook trên đĩa (hiện chỉ EPUB có implementation thật, xem
 *   EbookMetadataEmbedderFactory). Nút này bị ẩn hoàn toàn với PDF.
 */
public class MetadataEditorActivity extends AppCompatActivity {

    public static final String EXTRA_UUID = "uuid";
    public static final String RESULT_EXTRA_CHANGED = "changed";

    private MetadataDatabaseHelper mDB;
    private File rootDir;
    private String uuid;
    private BookMetadataForm form;

    private ImageView coverPreview;
    private ChipGroup tagsChipGroup;
    private AutoCompleteTextView tagsInput;
    private LinearLayout identifiersContainer;
    private LinearLayout customColumnsContainer;
    private View customColumnsTitle;
    private View customColumnsHint;
    private Button btnSaveToFile;
    private ProgressBar progressBar;
    /** Tag hiện có trên form (khác với form.tags -- được đọc lại từ UI ngay trước khi lưu, xem
     * readFormFromViews()). Giữ ở đây để addTagChip()/xóa chip có nơi đồng bộ ngay khi người
     * dùng thao tác, giống TagEditorDialog. */
    private final List<String> currentTags = new ArrayList<>();

    private final ActivityResultLauncher<String> pickImage =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) loadPickedCoverInto(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_metadata_editor);
        setTitle(R.string.metadata_editor_title);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        // On targetSdk 35, edge-to-edge is enforced by default and this ScrollView never
        // consumed the system bar insets, so its content (starting with the cover preview
        // image) was drawn full-screen behind the status bar/ActionBar instead of below them --
        // same root cause and fix as BookSearchActivity's list (see its onCreate for the longer
        // explanation): apply the insets as padding directly to the scrolling content.
        ScrollView scrollRoot = findViewById(R.id.me_scroll);
        scrollRoot.setClipToPadding(false);
        ViewCompat.setOnApplyWindowInsetsListener(scrollRoot, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), bars.top, v.getPaddingRight(), bars.bottom);
            return insets;
        });

        uuid = getIntent().getStringExtra(EXTRA_UUID);
        if (TextUtils.isEmpty(uuid)) {
            finish();
            return;
        }

        mDB = new MetadataDatabaseHelper(this);
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        rootDir = StorageUtils.getRootDirectory(getApplicationContext(), settings);

        Cursor cs = mDB.getByUuid(Book.COLUMNS, uuid);
        Cursor fallbackRow = cs.moveToFirst() ? cs : null;
        form = loadForm(rootDir, uuid, fallbackRow);
        // Custom Column defs/values (DATABASE_VERSION 13) sống ở EAV riêng, không nằm trong
        // metadata.calibre theo cùng field như title/author/... nên nạp riêng ở đây thay vì
        // trong loadForm() -- cần metadata_id (Book.ID), có sẵn ngay trên fallbackRow.
        form.customColumnDefs = mDB.getAllCustomColumnDefs();
        if (fallbackRow != null) {
            int metadataId = fallbackRow.getInt(Book.ID);
            form.customColumnValues = mDB.getCustomColumnValuesForBook(metadataId);
        }
        cs.close();

        coverPreview = findViewById(R.id.me_cover_preview);
        tagsChipGroup = findViewById(R.id.me_tags_chip_group);
        tagsInput = findViewById(R.id.me_tags_input);
        identifiersContainer = findViewById(R.id.me_identifiers_container);
        customColumnsContainer = findViewById(R.id.me_custom_columns_container);
        customColumnsTitle = findViewById(R.id.me_custom_columns_title);
        customColumnsHint = findViewById(R.id.me_custom_columns_hint);
        btnSaveToFile = findViewById(R.id.me_btn_save_to_file);
        progressBar = findViewById(R.id.me_progress);

        findViewById(R.id.me_btn_change_cover).setOnClickListener(v -> pickImage.launch("image/*"));
        findViewById(R.id.me_btn_save).setOnClickListener(v -> onSaveClicked(false));
        findViewById(R.id.me_btn_save_to_file).setOnClickListener(v -> onSaveClicked(true));
        findViewById(R.id.me_btn_add_identifier).setOnClickListener(v -> addIdentifierRow(null, null));

        String extension = extensionOf(form.lpath);
        btnSaveToFile.setVisibility(
                EbookMetadataEmbedderFactory.shouldShowSaveToFileButton(extension) ? View.VISIBLE : View.GONE);

        populateViewsFromForm();
        setupTagsInput();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        new MenuInflater(this).inflate(R.menu.metadata_editor, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_get_metadata_from_ebook) {
            onGetMetadataFromEbookClicked();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mDB != null) mDB.close();
    }

    // ---- Load hiện trạng -> form -----------------------------------------------------------

    /** Tái sử dụng DeviceBookMetadataReader.findByUuid() (đã có, dùng để hiển thị
     * DeviceBookDetailActivity) để lấy JSONObject đầy đủ từ metadata.calibre, rồi map sang
     * BookMetadataForm. Field nào không có giá trị thì để rỗng trên form (không bịa "null" hay
     * placeholder giả) -- fallback sang cột SQLite chỉ khi book chưa có entry trong
     * metadata.calibre (ví dụ sách mới quét, "_new_book_"). */
    private BookMetadataForm loadForm(File rootDir, String uuid, Cursor sqliteFallbackRow) {
        BookMetadataForm form = new BookMetadataForm();
        form.uuid = uuid;

        JSONObject json = DeviceBookMetadataReader.findByUuid(rootDir, uuid);
        if (json != null) {
            form.lpath = json.has("lpath") ? json.optString("lpath", null) : null;
            form.title = json.optString("title", "");
            form.titleSort = json.optString("title_sort", "");
            form.authors = jsonArrayToList(json.optJSONArray("authors"));
            form.authorSort = json.optString("author_sort", "");
            form.publisher = json.optString("publisher", "");
            form.pubDate = json.optString("pubdate", "");
            form.languages = jsonArrayToList(json.optJSONArray("languages"));
            form.series = json.optString("series", "");
            double idx = json.optDouble("series_index", -1);
            form.seriesIndex = idx > 0 ? OPDSFormatUtils.formatNumber(idx) : "";
            double rating = json.optDouble("rating", -1);
            form.rating = rating > 0 ? OPDSFormatUtils.formatNumber(rating) : "";
            form.tags = jsonArrayToList(json.optJSONArray("tags"));
            form.identifiers = jsonObjectToMap(json.optJSONObject("identifiers"));
            form.comments = json.optString("comments", "");
        }
        // Fallback: nếu book chưa có entry trong metadata.calibre (sách mới quét, "_new_book_"),
        // ít nhất lấy title/author/lpath từ SQLite (giống cách DeviceBookDetailActivity làm).
        if (form.title.isEmpty() && sqliteFallbackRow != null) {
            String fallbackTitle = sqliteFallbackRow.getString(Book.TITLE);
            String fallbackAuthor = sqliteFallbackRow.getString(Book.AUTHOR);
            String fallbackLpath = sqliteFallbackRow.getString(Book.LPATH);
            if (!TextUtils.isEmpty(fallbackTitle)) form.title = fallbackTitle;
            if (form.authors.isEmpty() && !TextUtils.isEmpty(fallbackAuthor)) {
                form.authors.add(fallbackAuthor);
            }
            if (TextUtils.isEmpty(form.lpath)) form.lpath = fallbackLpath;
        }
        return form;
    }

    private static List<String> jsonArrayToList(JSONArray arr) {
        List<String> result = new ArrayList<>();
        if (arr == null) return result;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, null);
            if (!TextUtils.isEmpty(s)) result.add(s);
        }
        return result;
    }

    private static LinkedHashMap<String, String> jsonObjectToMap(JSONObject obj) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (obj == null) return result;
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String value = obj.optString(key, null);
            if (!TextUtils.isEmpty(value)) result.put(key, value);
        }
        return result;
    }

    // ---- Form -> views --------------------------------------------------------------------

    private void populateViewsFromForm() {
        setText(R.id.me_title, form.title);
        setText(R.id.me_title_sort, form.titleSort);
        setText(R.id.me_authors, TextUtils.join(" & ", form.authors));
        setText(R.id.me_author_sort, form.authorSort);
        setText(R.id.me_publisher, form.publisher);
        setText(R.id.me_pubdate, form.pubDate);
        setText(R.id.me_language, TextUtils.join(", ", form.languages));
        setText(R.id.me_series, form.series);
        setText(R.id.me_series_index, form.seriesIndex);
        setText(R.id.me_rating, form.rating);
        setText(R.id.me_comments, form.comments);

        currentTags.clear();
        currentTags.addAll(form.tags);
        for (String tag : form.tags) addTagChip(tag);

        for (java.util.Map.Entry<String, String> e : form.identifiers.entrySet()) {
            addIdentifierRow(e.getKey(), e.getValue());
        }

        populateCustomColumnRows();

        // Bìa: ưu tiên đúng thứ tự DeviceBookDetailActivity đã dùng -- cover_path trên đĩa (qua
        // CoverManager), rồi "thumbnail" nhúng trong entry JSON, rồi thumbnail BLOB SQLite.
        byte[] coverBytes = loadExistingCoverBytes();
        if (coverBytes != null) {
            Bitmap bmp = BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.length);
            if (bmp != null) coverPreview.setImageBitmap(bmp);
        }
    }

    private byte[] loadExistingCoverBytes() {
        Cursor cs = mDB.getByUuid(Book.COLUMNS, uuid);
        try {
            if (!cs.moveToFirst()) return null;
            int coverIdx = Book.COVER_PATH;
            if (coverIdx < cs.getColumnCount() && !cs.isNull(coverIdx)) {
                String coverPath = cs.getString(coverIdx);
                if (!TextUtils.isEmpty(coverPath)) {
                    File coverFile = new File(rootDir, coverPath);
                    if (coverFile.exists()) {
                        byte[] fromFile = readAllBytes(coverFile);
                        if (fromFile != null) return fromFile;
                    }
                }
            }
            JSONObject json = DeviceBookMetadataReader.findByUuid(rootDir, uuid);
            if (json != null) {
                JSONArray thumb = json.optJSONArray("thumbnail");
                if (thumb != null && thumb.length() > 2) {
                    try {
                        return Base64.decode(thumb.optString(2), Base64.DEFAULT);
                    } catch (Exception ignored) {
                        // fall through
                    }
                }
            }
            try {
                byte[] blob = cs.getBlob(Book.THUMB);
                if (blob != null && blob.length > 0) return blob;
            } catch (Exception ignored) {
                // fall through, no cover available
            }
            return null;
        } finally {
            cs.close();
        }
    }

    private static byte[] readAllBytes(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int c;
            while ((c = in.read(buf)) > 0) out.write(buf, 0, c);
            return out.toByteArray();
        } catch (Exception e) {
            Logger.e(e);
            return null;
        }
    }

    private void setText(int viewId, String value) {
        EditText et = findViewById(viewId);
        et.setText(value == null ? "" : value);
    }

    // ---- Tags (ChipGroup, giống TagEditorDialog) ------------------------------------------

    private void setupTagsInput() {
        List<String> allTags = mDB.getAllTagNames();
        tagsInput.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, allTags));
        tagsInput.setOnEditorActionListener((v, actionId, event) -> {
            boolean isDone = actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                        && event.getAction() == android.view.KeyEvent.ACTION_DOWN);
            if (!isDone) return false;
            String typed = tagsInput.getText() == null ? "" : tagsInput.getText().toString().trim();
            if (!typed.isEmpty() && !currentTags.contains(typed)) {
                currentTags.add(typed);
                addTagChip(typed);
                tagsInput.setText("");
            }
            return true;
        });
    }

    private void addTagChip(String tagName) {
        Chip chip = new Chip(this);
        chip.setText(tagName);
        chip.setCloseIconVisible(true);
        chip.setOnCloseIconClickListener(v -> {
            currentTags.remove(tagName);
            tagsChipGroup.removeView(chip);
        });
        tagsChipGroup.addView(chip);
    }

    // ---- Identifiers (dòng động, thêm/xóa được) -------------------------------------------

    private void addIdentifierRow(String key, String value) {
        View row = LayoutInflater.from(this).inflate(R.layout.row_identifier_editor, identifiersContainer, false);
        EditText keyView = row.findViewById(R.id.ri_key);
        EditText valueView = row.findViewById(R.id.ri_value);
        if (key != null) keyView.setText(key);
        if (value != null) valueView.setText(value);
        row.findViewById(R.id.ri_btn_remove).setOnClickListener(v -> identifiersContainer.removeView(row));
        identifiersContainer.addView(row);
    }

    // ---- Custom Column (DATABASE_VERSION 13) ----------------------------------------------
    // Không thể TẠO Custom Column mới từ app này (chỉ Calibre Desktop mới định nghĩa cột) --
    // mỗi cột đã biết (form.customColumnDefs, nạp từ mDB.getAllCustomColumnDefs() trong
    // onCreate()) luôn có đúng 1 dòng cố định, không có nút "+ Thêm" hay "Xóa" như Identifiers.

    /** Vẽ lại toàn bộ container từ form.customColumnDefs/customColumnValues -- gọi 1 lần trong
     * populateViewsFromForm() (chưa từng gọi lại sau đó, khác applyCustomColumnsFromOpf() bên
     * dưới chỉ thêm dòng mới). Ẩn cả tiêu đề lẫn container nếu thư viện chưa có Custom Column
     * nào (phổ biến nếu chưa từng đồng bộ với Calibre Desktop). */
    private void populateCustomColumnRows() {
        customColumnsContainer.removeAllViews();
        boolean any = !form.customColumnDefs.isEmpty();
        customColumnsTitle.setVisibility(any ? View.VISIBLE : View.GONE);
        customColumnsHint.setVisibility(any ? View.VISIBLE : View.GONE);
        for (CustomColumnSync.ColumnDef def : form.customColumnDefs) {
            String canonicalValue = form.customColumnValues.get(def.lookupName);
            addCustomColumnRow(def, canonicalValue);
        }
    }

    /** @param canonicalValue value_text as TABLE_CUSTOM_COLUMN_VALUES/BookMetadataForm store it
     * (MULTI_JOIN-joined for is_multiple) -- converted to a comma-separated string for display/
     * editing here, and converted back in readCustomColumnRowsIntoForm() below. Comma was picked
     * over MULTI_JOIN itself since the latter isn't a character a person can type on a normal
     * keyboard. */
    private void addCustomColumnRow(CustomColumnSync.ColumnDef def, String canonicalValue) {
        View row = LayoutInflater.from(this).inflate(R.layout.row_custom_column_editor, customColumnsContainer, false);
        row.setTag(def);
        ((TextView) row.findViewById(R.id.cc_label)).setText(def.label);
        EditText valueView = row.findViewById(R.id.cc_value);
        if (!TextUtils.isEmpty(canonicalValue)) {
            String display = def.isMultiple
                    ? TextUtils.join(", ", canonicalValue.split(String.valueOf(CustomColumnSync.MULTI_JOIN)))
                    : canonicalValue;
            valueView.setText(display);
        }
        customColumnsContainer.addView(row);
    }

    /** Adds (or, if content.opf's #lookup somehow already has a row -- shouldn't normally
     * happen, since form.customColumnDefs comes from the DB and OPF-only columns are new by
     * definition -- fills in) rows for any Custom Column "Get metadata from ebook" found that
     * this form didn't already know about, mirroring addTagIfNew()'s "don't clobber an existing
     * value" behavior above.
     * @return true if at least one row was added/changed. */
    private boolean applyCustomColumnsFromOpf(EpubMetadataOpfReader.Result result) {
        boolean changed = false;
        for (CustomColumnSync.ColumnDef def : result.customColumnDefs) {
            if (findCustomColumnRow(def.lookupName) != null) continue; // đã có dòng, không tạo trùng
            form.customColumnDefs.add(def);
            addCustomColumnRow(def, result.customColumnValues.get(def.lookupName));
            changed = true;
        }
        return changed;
    }

    private View findCustomColumnRow(String lookupName) {
        for (int i = 0; i < customColumnsContainer.getChildCount(); i++) {
            View row = customColumnsContainer.getChildAt(i);
            CustomColumnSync.ColumnDef def = (CustomColumnSync.ColumnDef) row.getTag();
            if (def != null && def.lookupName.equals(lookupName)) return row;
        }
        return null;
    }

    /** Reads every Custom Column row back into form.customColumnValues, converting the display
     * comma-separated form back to MULTI_JOIN-joined canonical form for is_multiple columns
     * (reverse of addCustomColumnRow() above). An emptied field maps to an empty string rather
     * than being omitted entirely, so updateMetadataFields()/DeviceBookMetadataWriter can tell
     * "user cleared this" apart from "this column was never on the form at all". */
    private void readCustomColumnRowsIntoForm() {
        form.customColumnValues = new java.util.LinkedHashMap<>();
        for (int i = 0; i < customColumnsContainer.getChildCount(); i++) {
            View row = customColumnsContainer.getChildAt(i);
            CustomColumnSync.ColumnDef def = (CustomColumnSync.ColumnDef) row.getTag();
            if (def == null) continue;
            EditText valueView = row.findViewById(R.id.cc_value);
            String typed = valueView.getText() == null ? "" : valueView.getText().toString().trim();
            String canonical;
            if (typed.isEmpty()) {
                canonical = "";
            } else if (def.isMultiple) {
                List<String> parts = splitTrimmed(typed, ",");
                canonical = TextUtils.join(String.valueOf(CustomColumnSync.MULTI_JOIN), parts);
            } else {
                canonical = typed;
            }
            form.customColumnValues.put(def.lookupName, canonical);
        }
    }

    // ---- Get metadata from ebook (menu_get_metadata_from_ebook) ---------------------------

    /** true while a background read is in flight, guards against double-tapping the toolbar
     * action before its Toast/UI update lands. */
    private boolean isFetchingFromEbook = false;

    private void onGetMetadataFromEbookClicked() {
        if (isFetchingFromEbook) return;
        if (TextUtils.isEmpty(form.lpath)) {
            Toast.makeText(this, R.string.metadata_editor_get_from_ebook_no_file, Toast.LENGTH_SHORT).show();
            return;
        }
        String extension = extensionOf(form.lpath);
        // Only EPUB carries a Dublin Core content.opf the way this reads -- see
        // EpubMetadataOpfReader's class comment. PDF/CBZ don't have an equivalent standard
        // metadata file to read from, so there's nothing to parse for them yet.
        if (!"epub".equalsIgnoreCase(extension)) {
            Toast.makeText(this,
                    getString(R.string.metadata_editor_get_from_ebook_unsupported, extension),
                    Toast.LENGTH_LONG).show();
            return;
        }

        File bookFile = new File(rootDir, form.lpath);
        isFetchingFromEbook = true;
        setButtonsEnabled(false);
        new Thread(() -> {
            EpubMetadataOpfReader.Result result = EpubMetadataOpfReader.read(bookFile);
            runOnUiThread(() -> {
                isFetchingFromEbook = false;
                setButtonsEnabled(true);
                if (result == null) {
                    Toast.makeText(this, R.string.metadata_editor_get_from_ebook_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                boolean anyFieldFilled = applyOpfResultToViews(result);
                Toast.makeText(this, anyFieldFilled
                        ? R.string.metadata_editor_get_from_ebook_success
                        : R.string.metadata_editor_get_from_ebook_empty, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    /** Fills in only the views whose corresponding OPF field was actually present -- anything
     * left null/empty in result is left untouched on screen, so this never erases text the user
     * (or metadata.calibre) already had for a field content.opf simply doesn't cover.
     * @return true if at least one view was changed, so the caller can pick an accurate Toast. */
    private boolean applyOpfResultToViews(EpubMetadataOpfReader.Result result) {
        boolean changed = false;
        if (!TextUtils.isEmpty(result.title)) {
            setText(R.id.me_title, result.title);
            changed = true;
        }
        if (!TextUtils.isEmpty(result.titleSort)) {
            setText(R.id.me_title_sort, result.titleSort);
            changed = true;
        }
        if (!result.authors.isEmpty()) {
            setText(R.id.me_authors, TextUtils.join(" & ", result.authors));
            changed = true;
        }
        if (!TextUtils.isEmpty(result.authorSort)) {
            setText(R.id.me_author_sort, result.authorSort);
            changed = true;
        }
        if (!TextUtils.isEmpty(result.publisher)) {
            setText(R.id.me_publisher, result.publisher);
            changed = true;
        }
        if (!TextUtils.isEmpty(result.pubDate)) {
            setText(R.id.me_pubdate, result.pubDate);
            changed = true;
        }
        if (!result.languages.isEmpty()) {
            setText(R.id.me_language, TextUtils.join(", ", result.languages));
            changed = true;
        }
        if (!TextUtils.isEmpty(result.series)) {
            setText(R.id.me_series, result.series);
            changed = true;
        }
        if (!TextUtils.isEmpty(result.seriesIndex)) {
            setText(R.id.me_series_index, result.seriesIndex);
            changed = true;
        }
        if (!TextUtils.isEmpty(result.comments)) {
            setText(R.id.me_comments, result.comments);
            changed = true;
        }
        for (String tag : result.tags) {
            if (addTagIfNew(tag)) changed = true;
        }
        for (java.util.Map.Entry<String, String> e : result.identifiers.entrySet()) {
            addOrUpdateIdentifierRow(e.getKey(), e.getValue());
            changed = true;
        }
        if (applyCustomColumnsFromOpf(result)) changed = true;
        return changed;
    }

    /** @return true if tagName was actually new (and so a chip was added) -- tags already present
     * (e.g. re-running this action, or a tag the user typed in by hand that also happens to be
     * in the .opf) are left as the single existing chip instead of duplicated. */
    private boolean addTagIfNew(String tagName) {
        if (TextUtils.isEmpty(tagName) || currentTags.contains(tagName)) return false;
        currentTags.add(tagName);
        addTagChip(tagName);
        return true;
    }

    /** Updates the value of an existing identifier row with a matching key (case-insensitive --
     * content.opf may write "ISBN" while the form's existing row has "isbn"), or appends a new
     * row if this key isn't on the form yet. */
    private void addOrUpdateIdentifierRow(String key, String value) {
        for (int i = 0; i < identifiersContainer.getChildCount(); i++) {
            View row = identifiersContainer.getChildAt(i);
            EditText keyView = row.findViewById(R.id.ri_key);
            String existingKey = keyView.getText() == null ? "" : keyView.getText().toString().trim();
            if (existingKey.equalsIgnoreCase(key)) {
                EditText valueView = row.findViewById(R.id.ri_value);
                valueView.setText(value);
                return;
            }
        }
        addIdentifierRow(key, value);
    }

    // ---- Đọc lại UI -> form trước khi lưu --------------------------------------------------

    private void readFormFromViews(BookMetadataForm form) {
        form.title = getFieldText(R.id.me_title);
        form.titleSort = getFieldText(R.id.me_title_sort);
        form.authors = splitTrimmed(getFieldText(R.id.me_authors), "&");
        form.authorSort = getFieldText(R.id.me_author_sort);
        form.publisher = getFieldText(R.id.me_publisher);
        form.pubDate = getFieldText(R.id.me_pubdate);
        form.languages = splitTrimmed(getFieldText(R.id.me_language), ",");
        form.series = getFieldText(R.id.me_series);
        form.seriesIndex = getFieldText(R.id.me_series_index);
        form.rating = getFieldText(R.id.me_rating);
        form.comments = getFieldText(R.id.me_comments);

        form.tags = new ArrayList<>(currentTags);

        LinkedHashMap<String, String> identifiers = new LinkedHashMap<>();
        for (int i = 0; i < identifiersContainer.getChildCount(); i++) {
            View row = identifiersContainer.getChildAt(i);
            EditText keyView = row.findViewById(R.id.ri_key);
            EditText valueView = row.findViewById(R.id.ri_value);
            String key = keyView.getText() == null ? "" : keyView.getText().toString().trim();
            String value = valueView.getText() == null ? "" : valueView.getText().toString().trim();
            if (!key.isEmpty() && !value.isEmpty()) identifiers.put(key, value);
        }
        form.identifiers = identifiers;

        readCustomColumnRowsIntoForm();
    }

    private String getFieldText(int viewId) {
        EditText et = findViewById(viewId);
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private static List<String> splitTrimmed(String raw, String delimiter) {
        List<String> result = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) return result;
        for (String part : raw.split(java.util.regex.Pattern.quote(delimiter))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    // ---- Chọn ảnh bìa mới -------------------------------------------------------------------

    private void loadPickedCoverInto(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("openInputStream returned null");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            byte[] bytes = bos.toByteArray();
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp == null) {
                Toast.makeText(this, R.string.metadata_editor_cover_pick_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            form.newCoverBytes = bytes;
            coverPreview.setImageBitmap(bmp);
        } catch (Exception e) {
            Logger.e(e);
            Toast.makeText(this, R.string.metadata_editor_cover_pick_failed, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- Lưu ----------------------------------------------------------------------------

    /** @param alsoEmbedIntoFile false = chỉ lưu metadata.calibre + SQLite (nút "Lưu"); true =
     * lưu + nhúng vào file ebook gốc (nút "Lưu vào file ebook"). */
    private void onSaveClicked(boolean alsoEmbedIntoFile) {
        readFormFromViews(form);
        setButtonsEnabled(false);

        new Thread(() -> {
            boolean ok = true;
            String errorMessage = null;
            try {
                // Luôn ghi metadata.calibre + SQLite trước -- đây là nguồn dữ liệu app dùng để
                // hiển thị, phải đúng bất kể có nhúng vào file hay không.
                DeviceBookMetadataWriter.save(rootDir, mDB, form);

                if (alsoEmbedIntoFile) {
                    String extension = extensionOf(form.lpath);
                    EbookMetadataEmbedder embedder = EbookMetadataEmbedderFactory.embedderFor(extension);
                    if (embedder == null) {
                        errorMessage = getString(R.string.metadata_editor_embed_unsupported, extension);
                    } else {
                        File bookFile = new File(rootDir, form.lpath);
                        embedder.embed(bookFile, form);
                    }
                }
            } catch (Exception e) {
                Logger.e(e);
                ok = false;
                errorMessage = e.getMessage();
            }
            boolean finalOk = ok;
            String finalError = errorMessage;
            runOnUiThread(() -> {
                setButtonsEnabled(true);
                if (!finalOk) {
                    Toast.makeText(this, getString(R.string.metadata_editor_save_failed,
                            finalError == null ? "" : finalError), Toast.LENGTH_LONG).show();
                    return;
                }
                if (finalError != null) {
                    // Lưu metadata.calibre OK nhưng bỏ qua bước nhúng file (định dạng chưa hỗ trợ).
                    Toast.makeText(this, finalError, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, R.string.metadata_editor_save_success, Toast.LENGTH_SHORT).show();
                }
                setResult(RESULT_OK, new Intent().putExtra(RESULT_EXTRA_CHANGED, true));
                finish();
            });
        }).start();
    }

    private void setButtonsEnabled(boolean enabled) {
        findViewById(R.id.me_btn_save).setEnabled(enabled);
        findViewById(R.id.me_btn_save_to_file).setEnabled(enabled);
        progressBar.setVisibility(enabled ? View.GONE : View.VISIBLE);
    }

    private static String extensionOf(String lpath) {
        if (TextUtils.isEmpty(lpath) || !lpath.contains(".")) return "";
        return lpath.substring(lpath.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
