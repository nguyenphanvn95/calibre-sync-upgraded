package net.mrphan.calibre.reader.epub

import android.database.Cursor
import net.mrphan.calibre.MetadataDatabaseHelper
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/**
 * Wraps a [Locator] the same way `EpubReaderActivity#saveLastLocator` already does for the
 * single "last locator" column -- {"type":"epub_locator","locator":{...}} -- rather than storing
 * the bare Locator JSON, so `reader_annotations.locator_json` follows the exact same convention
 * as `metadata.last_locator` instead of introducing a second, inconsistent format.
 */
fun encodeLocator(locator: Locator): String =
    JSONObject().put("type", "epub_locator").put("locator", locator.toJSON()).toString()

/** Inverse of [encodeLocator]. Returns null on any parse failure or unexpected envelope type,
 * same defensive style as `EpubReaderActivity#restoreLastLocator`. */
fun decodeLocator(json: String): Locator? = try {
    val obj = JSONObject(json)
    if (obj.optString("type") == "epub_locator") {
        Locator.fromJSON(JSONObject(obj.getString("locator")))
    } else {
        null
    }
} catch (e: Exception) {
    null
}

/**
 * In-memory representation of one row of MetadataDatabaseHelper's `reader_annotations` table
 * (DATABASE_VERSION 8). [type] is `"bookmark"` or `"highlight"`; [color]/[note] are only ever
 * set for `"highlight"` rows (see MetadataDatabaseHelper#addAnnotation kdoc).
 */
data class ReaderAnnotation(
    val id: Long,
    val uuid: String,
    val type: String,
    val locatorJson: String,
    val color: String?,
    val note: String?,
    val excerpt: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        /** Reads every row of a Cursor returned by [MetadataDatabaseHelper.getAnnotations] --
         * column order matches the `reader_annotations` CREATE TABLE statement, since that method
         * always queries with a null (i.e. "all columns") projection. Does NOT close [cursor];
         * the caller (which opened it) is responsible for that, matching this file's existing
         * "close the Cursor at the call site" convention. */
        fun readAll(cursor: Cursor): List<ReaderAnnotation> {
            val result = mutableListOf<ReaderAnnotation>()
            val idIdx = cursor.getColumnIndexOrThrow("_id")
            val uuidIdx = cursor.getColumnIndexOrThrow("uuid")
            val typeIdx = cursor.getColumnIndexOrThrow("type")
            val locatorIdx = cursor.getColumnIndexOrThrow("locator_json")
            val colorIdx = cursor.getColumnIndexOrThrow("color")
            val noteIdx = cursor.getColumnIndexOrThrow("note")
            val excerptIdx = cursor.getColumnIndexOrThrow("excerpt")
            val createdIdx = cursor.getColumnIndexOrThrow("created_at")
            val updatedIdx = cursor.getColumnIndexOrThrow("updated_at")
            while (cursor.moveToNext()) {
                result.add(
                    ReaderAnnotation(
                        id = cursor.getLong(idIdx),
                        uuid = cursor.getString(uuidIdx),
                        type = cursor.getString(typeIdx),
                        locatorJson = cursor.getString(locatorIdx),
                        color = if (cursor.isNull(colorIdx)) null else cursor.getString(colorIdx),
                        note = if (cursor.isNull(noteIdx)) null else cursor.getString(noteIdx),
                        excerpt = if (cursor.isNull(excerptIdx)) null else cursor.getString(excerptIdx),
                        createdAt = cursor.getLong(createdIdx),
                        updatedAt = cursor.getLong(updatedIdx),
                    )
                )
            }
            return result
        }
    }
}

/** The four highlight colors offered in the selection popup -- see prompt section 5.3, "bảng màu
 * highlight ... giống Readest". Stored in the DB as the lowercase English name (`"yellow"`, ...)
 * so it's stable across locales even though the label shown to the user is localized. */
enum class ReaderHighlightColor(val storageKey: String, val argb: Int) {
    YELLOW("yellow", 0xFFFFEB3B.toInt()),
    GREEN("green", 0xFF8BC34A.toInt()),
    BLUE("blue", 0xFF64B5F6.toInt()),
    PINK("pink", 0xFFF48FB1.toInt());

    companion object {
        fun fromStorageKey(key: String?): ReaderHighlightColor =
            entries.firstOrNull { it.storageKey == key } ?: YELLOW
    }
}

/**
 * Which Readium [org.readium.r2.navigator.Decoration.Style] a highlight renders with -- FILL is
 * the original/only behavior this app had before the reader-selection-menu redesign (solid
 * background tint, `Decoration.Style.Highlight`), UNDERLINE is the new option exposed by the two
 * style buttons at the top of [ReaderSelectionToolbar]'s pill (`Decoration.Style.Underline`,
 * colored line under the text instead of a filled background).
 */
enum class ReaderHighlightStyle(val storageSuffix: String) {
    FILL(""),
    UNDERLINE("_u");

    companion object {
        fun fromStorageSuffix(hasSuffix: Boolean): ReaderHighlightStyle = if (hasSuffix) UNDERLINE else FILL
    }
}

/**
 * Packs [color] and [style] into the single `reader_annotations.color` TEXT column so no
 * DATABASE_VERSION bump / migration / cloud-sync schema change is needed for the new
 * underline-vs-fill style option -- see [ReaderHighlightStyle]'s kdoc. FILL highlights (the only
 * kind that existed before) round-trip through this as the exact same bare storage key
 * ("yellow", "green", ...) they always did, so every highlight created before this change keeps
 * rendering identically; only new UNDERLINE highlights get the "_u" suffix ("yellow_u", ...).
 */
fun encodeColorStyle(color: ReaderHighlightColor, style: ReaderHighlightStyle): String =
    color.storageKey + style.storageSuffix

/** Inverse of [encodeColorStyle]. Also accepts a bare/legacy key (no suffix, or an unrecognized
 * one) by falling back to [ReaderHighlightStyle.FILL], so old rows and any unexpected DB content
 * never crash or silently pick UNDERLINE by mistake. */
fun decodeColorStyle(stored: String?): Pair<ReaderHighlightColor, ReaderHighlightStyle> {
    val suffix = ReaderHighlightStyle.UNDERLINE.storageSuffix
    val hasSuffix = stored != null && stored.endsWith(suffix)
    val colorKey = if (hasSuffix) stored!!.removeSuffix(suffix) else stored
    return ReaderHighlightColor.fromStorageKey(colorKey) to ReaderHighlightStyle.fromStorageSuffix(hasSuffix)
}
