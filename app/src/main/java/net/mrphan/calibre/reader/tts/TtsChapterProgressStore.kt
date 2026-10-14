package net.mrphan.calibre.reader.tts

import android.content.ContentValues
import android.content.Context
import net.mrphan.calibre.MetadataDatabaseHelper

/**
 * Doc/ghi tien trinh nghe TTS theo tung chuong (bang tts_chapter_progress trong
 * MetadataDatabaseHelper) - dung boi TtsChapterListActivity de hien % tien trinh moi chuong
 * (giong cot "Tien do: NN%" trong danh sach chuong), va boi TtsPlaybackService de tu luu vi tri
 * dang nghe sau moi cau + danh dau hoan thanh khi chuyen sang chuong khac.
 */
class TtsChapterProgressStore(context: Context) {
    private val appContext = context.applicationContext

    data class ChapterProgress(val sentenceIndex: Int, val sentenceCount: Int) {
        /** 0..100. Coi la 100% khi sentenceIndex da vuot qua cau cuoi (chuong da doc xong/da
         * chuyen sang chuong khac), tranh truong hop chi hien toi da ~99% du nguoi dung da nghe
         * het chuong (xem TtsPlaybackService#markChapterCompletedIfNeeded). */
        val percent: Int
            get() {
                if (sentenceCount <= 0) return 0
                if (sentenceIndex >= sentenceCount - 1) return 100
                return (((sentenceIndex + 1).toDouble() / sentenceCount) * 100).toInt().coerceIn(0, 99)
            }
    }

    fun getProgress(uuid: String, chapterHref: String): ChapterProgress? {
        val db = MetadataDatabaseHelper(appContext)
        try {
            val readable = db.readableDatabase
            readable.query(
                "tts_chapter_progress", arrayOf("sentence_index", "sentence_count"),
                "uuid = ? AND chapter_href = ?", arrayOf(uuid, chapterHref),
                null, null, null,
            ).use { c ->
                if (!c.moveToFirst()) return null
                return ChapterProgress(c.getInt(0), c.getInt(1))
            }
        } finally {
            db.close()
        }
    }

    /** Tra ve tat ca tien trinh da luu cho 1 cuon sach, key theo chapter_href - dung de nap 1
     * lan cho ca man hinh danh sach chuong thay vi query tung dong (tranh N+1 query khi sach co
     * hang chuc chuong, xem TtsChapterListActivity#loadChapters). */
    fun getAllProgress(uuid: String): Map<String, ChapterProgress> {
        val db = MetadataDatabaseHelper(appContext)
        try {
            val readable = db.readableDatabase
            val result = mutableMapOf<String, ChapterProgress>()
            readable.query(
                "tts_chapter_progress", arrayOf("chapter_href", "sentence_index", "sentence_count"),
                "uuid = ?", arrayOf(uuid), null, null, null,
            ).use { c ->
                while (c.moveToNext()) {
                    result[c.getString(0)] = ChapterProgress(c.getInt(1), c.getInt(2))
                }
            }
            return result
        } finally {
            db.close()
        }
    }

    fun saveProgress(uuid: String, chapterHref: String, sentenceIndex: Int, sentenceCount: Int) {
        if (sentenceCount <= 0) return
        val db = MetadataDatabaseHelper(appContext)
        try {
            val values = ContentValues().apply {
                put("uuid", uuid)
                put("chapter_href", chapterHref)
                put("sentence_index", sentenceIndex)
                put("sentence_count", sentenceCount)
                put("updated_at", System.currentTimeMillis())
            }
            db.writableDatabase.insertWithOnConflict(
                "tts_chapter_progress", null, values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
            )
        } finally {
            db.close()
        }
    }

    /** Danh dau 1 chuong la da nghe xong hoan toan - goi khi TtsController tu chuyen sang chuong
     * ke tiep, hoac khi het sach. */
    fun markCompleted(uuid: String, chapterHref: String, sentenceCount: Int) {
        if (sentenceCount <= 0) return
        saveProgress(uuid, chapterHref, sentenceCount - 1, sentenceCount)
    }
}
