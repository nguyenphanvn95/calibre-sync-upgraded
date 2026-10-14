package net.mrphan.calibre.reader.tts.dictionary

import android.content.Context
import net.mrphan.calibre.MetadataDatabaseHelper

/**
 * Doc/ghi "Tu dien phat am" TTS (bang tts_pronunciation_dictionary trong
 * MetadataDatabaseHelper) - danh sach TOAN CUC (khong theo tung sach), dung boi
 * TtsDictionaryManagerActivity cho man hinh Them/Sua/Xoa va boi TtsController de nap tu dien
 * truoc khi tong hop giong noi.
 *
 * Cac method o day la ham blocking (khong phai suspend), mo/dong 1 MetadataDatabaseHelper rieng
 * cho moi loi goi - cung 1 phong cach voi TtsChapterProgressStore da co san trong project, thay
 * vi giu 1 SQLiteOpenHelper dung chung suot vong doi Store nhu ban nhap prompt ban dau de xuat.
 * Caller (TtsDictionaryManagerActivity, TtsController) tu chiu trach nhiem goi cac ham nay tu
 * Dispatchers.IO, giong het cach TtsChapterListActivity#loadChapters dang goi
 * TtsChapterProgressStore.
 */
class TtsDictionaryStore(context: Context) {
    private val appContext = context.applicationContext

    fun getAll(): List<TtsDictionaryEntry> {
        val db = MetadataDatabaseHelper(appContext)
        try {
            val entries = mutableListOf<TtsDictionaryEntry>()
            db.getAllTtsDictionaryEntries().use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow("_id")
                val sourceIdx = cursor.getColumnIndexOrThrow("source_text")
                val replacementIdx = cursor.getColumnIndexOrThrow("replacement_text")
                val updatedIdx = cursor.getColumnIndexOrThrow("updated_at")
                while (cursor.moveToNext()) {
                    entries += TtsDictionaryEntry(
                        id = cursor.getLong(idIdx),
                        sourceText = cursor.getString(sourceIdx),
                        replacementText = cursor.getString(replacementIdx),
                        updatedAt = cursor.getLong(updatedIdx),
                    )
                }
            }
            return entries
        } finally {
            db.close()
        }
    }

    fun add(sourceText: String, replacementText: String): Long {
        val db = MetadataDatabaseHelper(appContext)
        try {
            return db.addTtsDictionaryEntry(sourceText.trim(), replacementText.trim())
        } finally {
            db.close()
        }
    }

    fun update(id: Long, sourceText: String, replacementText: String): Boolean {
        val db = MetadataDatabaseHelper(appContext)
        try {
            return db.updateTtsDictionaryEntry(id, sourceText.trim(), replacementText.trim())
        } finally {
            db.close()
        }
    }

    fun remove(id: Long): Boolean {
        val db = MetadataDatabaseHelper(appContext)
        try {
            return db.removeTtsDictionaryEntry(id)
        } finally {
            db.close()
        }
    }
}
