package net.mrphan.calibre;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser dung chung cho custom column (dinh nghia + gia tri) cua Calibre, doc tu khoi
 * "user_metadata" trong JSON dong bo Wireless Device (xem
 * MetadataDatabaseHelper#insertJSON/CalibreService) va (sau nay) tu cac the
 * "calibre:user_metadata:#lookup" trong content.opf (xem EpubMetadataOpfReader). Ca hai nguon
 * deu dung chung 1 dinh dang JSON con (Calibre serialize user_metadata giong nhau o ca hai noi),
 * nen dat logic parse dung chung tai day thay vi lap lai o tung noi goi.
 *
 * Khong luu FOREIGN KEY / rang buoc gi voi SQLite o day -- class nay chi la mot parser thuan tuy,
 * khong dung tren DB. Xem MetadataDatabaseHelper.TABLE_CUSTOM_COLUMN_DEFS/VALUES cho phan luu tru.
 */
final class CustomColumnSync {

    /** Ky tu dung de noi cac gia tri cua 1 custom column "is_multiple" (vd nhieu the loai) khi
     * luu vao cot value_text dang 1 chuoi TEXT duy nhat -- giong quy uoc noi bo cua Calibre
     * (Unit Separator), tranh dung dau phay vi ban than gia tri co the da chua dau phay. */
    static final char MULTI_JOIN = '\u001F';

    private CustomColumnSync() {}

    static final class ColumnDef {
        final String lookupName;     // "#genre"
        final String label;          // "Genre"
        final String datatype;       // text/series/rating/bool/datetime/enumeration/comments/
                                      // composite/int/float
        final boolean isMultiple;
        final String rawDisplayJson; // toan bo object "user_metadata" goc cho cot nay, giu lai
                                      // de khong mat thong tin hien thi (enum_values,...)

        ColumnDef(String lookupName, String label, String datatype, boolean isMultiple,
                  String rawDisplayJson) {
            this.lookupName = lookupName;
            this.label = label;
            this.datatype = datatype;
            this.isMultiple = isMultiple;
            this.rawDisplayJson = rawDisplayJson;
        }
    }

    static final class ColumnValue {
        final String lookupName;
        /** Da "stringify": list -> noi bang MULTI_JOIN, so/bool/datetime -> chuoi thap phan/ISO. */
        final String valueText;
        /** null neu datatype khong so hoa duoc (text/comments/enumeration nhieu-gia-tri...). */
        final Double valueNum;

        ColumnValue(String lookupName, String valueText, Double valueNum) {
            this.lookupName = lookupName;
            this.valueText = valueText;
            this.valueNum = valueNum;
        }
    }

    static final class ParsedCustomColumns {
        final List<ColumnDef> defs = new ArrayList<>();
        final List<ColumnValue> values = new ArrayList<>();
    }

    /**
     * Doc khoi "user_metadata" cua 1 cuon sach (dinh dang wireless-device sync, xem vi du trong
     * ghi chu nang cap) va tra ve danh sach dinh nghia + gia tri custom column tim thay. Khong
     * nem exception ra ngoai -- 1 cot loi khong duoc lam hong toan bo qua trinh dong bo 1 sach.
     */
    static ParsedCustomColumns parseFromWireJson(JSONObject bookJson) {
        ParsedCustomColumns result = new ParsedCustomColumns();
        JSONObject userMetadata = bookJson == null ? null : bookJson.optJSONObject("user_metadata");
        if (userMetadata == null) return result;

        java.util.Iterator<String> keys = userMetadata.keys();
        while (keys.hasNext()) {
            String lookupName = keys.next();
            if (lookupName == null || !lookupName.startsWith("#")) continue; // bo qua field la
            JSONObject colMeta = userMetadata.optJSONObject(lookupName);
            if (colMeta == null) continue;

            String label = colMeta.optString("label", lookupName);
            String datatype = colMeta.optString("datatype", "text");
            JSONObject isMultipleObj = colMeta.optJSONObject("is_multiple");
            boolean isMultiple = isMultipleObj != null && isMultipleObj.length() > 0;

            result.defs.add(new ColumnDef(lookupName, label, datatype, isMultiple,
                    colMeta.toString()));

            Object rawValue = colMeta.opt("#value#");
            // Uu tien gia tri trong user_metadata["#value#"]; fallback ve top-level bookJson
            // theo dung ten lookup neu Calibre khong nhet vao day (mot so phien ban chi dat gia
            // tri o top-level, khong lap lai trong user_metadata).
            if (rawValue == null || rawValue == JSONObject.NULL) {
                rawValue = bookJson.opt(lookupName);
            }
            ColumnValue value = toColumnValue(lookupName, datatype, isMultiple, rawValue);
            if (value != null) result.values.add(value);
        }
        return result;
    }

    /** Computes value_num for an already-canonicalized value_text (MULTI_JOIN-joined for
     * is_multiple, per-datatype formatted string otherwise) -- used when a value comes from the
     * metadata editor UI (MetadataEditorActivity, already plain text) rather than parsed JSON
     * (see {@link #toColumnValue} above, which handles the sync-JSON case). Returns null for
     * is_multiple columns (no single number represents a list) and for any value that doesn't
     * parse under def.datatype (left for the caller to decide whether that's worth surfacing to
     * the user -- this never throws). */
    static Double computeValueNum(ColumnDef def, String valueText) {
        if (def.isMultiple || valueText == null || valueText.isEmpty()) return null;
        try {
            switch (def.datatype) {
                case "rating":
                case "int":
                case "float":
                    return Double.parseDouble(valueText);
                case "bool":
                    return Boolean.parseBoolean(valueText) ? 1.0 : 0.0;
                case "datetime": {
                    Long epoch = OPDSFormatUtils.parseIsoToEpochMillisOrNull(valueText);
                    return epoch == null ? null : epoch.doubleValue();
                }
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Reverse of {@link #parseFromWireJson} -- writes {@code defs}/{@code values} into
     * {@code book}'s "user_metadata" block (plus a top-level "#lookup" convenience field per
     * column, mirroring what parseFromWireJson() itself falls back to reading), the same shape
     * Calibre Desktop's own wireless-sync JSON uses. Shared by
     * MetadataDatabaseHelper#exportAllBooksAsJSON() (writing SQLite's custom_column_values back
     * out to metadata.calibre on a full library rebuild) and DeviceBookMetadataWriter (patching
     * just the one edited book's entry) so the two on-disk-JSON writers can't drift apart on how
     * a value gets serialized back out. {@code values} maps lookup_name -> value_text in the
     * same canonical form TABLE_CUSTOM_COLUMN_VALUES stores (MULTI_JOIN-joined for is_multiple);
     * entries missing from {@code values} (no value for that column) are simply skipped, not
     * written as null/empty. Never throws -- a single column's value failing to parse (e.g. a
     * hand-edited non-numeric string for a "float" column) is logged and skipped rather than
     * aborting the whole book's export/save. */
    static void applyValuesIntoWireJson(JSONObject book, List<ColumnDef> defs, java.util.Map<String, String> values) {
        if (defs == null || defs.isEmpty() || values == null || values.isEmpty()) return;
        try {
            JSONObject userMetadata = book.optJSONObject("user_metadata");
            if (userMetadata == null) userMetadata = new JSONObject();
            for (ColumnDef d : defs) {
                try {
                    String valueText = values.get(d.lookupName);
                    if (valueText == null || valueText.isEmpty()) continue;
                    Object jsonValue;
                    if (d.isMultiple) {
                        JSONArray arr = new JSONArray();
                        for (String part : valueText.split(String.valueOf(MULTI_JOIN))) {
                            if (!part.isEmpty()) arr.put(part);
                        }
                        jsonValue = arr;
                    } else {
                        switch (d.datatype) {
                            case "rating":
                                // Thang 0-5 (quy uoc noi bo cua app, xem MetadataEditorActivity)
                                // -> nhan lai 2 de dung quy uoc goc 0-10 cua Calibre khi ghi ra.
                                jsonValue = Double.parseDouble(valueText) * 2.0;
                                break;
                            case "int":
                            case "float":
                                jsonValue = Double.parseDouble(valueText);
                                break;
                            case "bool":
                                jsonValue = Boolean.parseBoolean(valueText);
                                break;
                            default:
                                jsonValue = valueText;
                                break;
                        }
                    }
                    book.put(d.lookupName, jsonValue);
                    JSONObject colMeta;
                    try {
                        colMeta = d.rawDisplayJson != null ? new JSONObject(d.rawDisplayJson) : new JSONObject();
                    } catch (JSONException je) {
                        colMeta = new JSONObject();
                    }
                    colMeta.put("label", d.label);
                    colMeta.put("datatype", d.datatype);
                    colMeta.put("#value#", jsonValue);
                    userMetadata.put(d.lookupName, colMeta);
                } catch (Exception perColumnError) {
                    Logger.e(perColumnError);
                }
            }
            if (userMetadata.length() > 0) book.put("user_metadata", userMetadata);
        } catch (Exception e) {
            Logger.e(e);
        }
    }

    private static ColumnValue toColumnValue(String lookupName, String datatype,
                                              boolean isMultiple, Object rawValue) {
        if (rawValue == null || rawValue == JSONObject.NULL) return null;
        try {
            if (isMultiple && rawValue instanceof JSONArray) {
                JSONArray arr = (JSONArray) rawValue;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < arr.length(); i++) {
                    if (i > 0) sb.append(MULTI_JOIN);
                    sb.append(arr.optString(i));
                }
                return new ColumnValue(lookupName, sb.toString(), null);
            }
            switch (datatype) {
                case "rating":
                case "int":
                case "float": {
                    double num = Double.parseDouble(String.valueOf(rawValue));
                    // Rating cua Calibre luu thang 0-10 (nua sao = 2 don vi noi bo) -- chuan hoa
                    // ve 0-5 giong OPDSEntry.rating/BookDetailActivity de nhat quan trong toan
                    // app. Chi ap dung cho datatype "rating", khong ap dung cho int/float thuong.
                    if ("rating".equals(datatype)) num = num / 2.0;
                    return new ColumnValue(lookupName, OPDSFormatUtils.formatNumber(num), num);
                }
                case "bool": {
                    boolean b = Boolean.parseBoolean(String.valueOf(rawValue));
                    return new ColumnValue(lookupName, String.valueOf(b), b ? 1.0 : 0.0);
                }
                case "datetime": {
                    String iso = String.valueOf(rawValue);
                    Long epoch = OPDSFormatUtils.parseIsoToEpochMillisOrNull(iso);
                    return new ColumnValue(lookupName, iso, epoch == null ? null : epoch.doubleValue());
                }
                default:
                    return new ColumnValue(lookupName, String.valueOf(rawValue), null);
            }
        } catch (Exception e) {
            Logger.e(e);
            return new ColumnValue(lookupName, String.valueOf(rawValue), null);
        }
    }
}
