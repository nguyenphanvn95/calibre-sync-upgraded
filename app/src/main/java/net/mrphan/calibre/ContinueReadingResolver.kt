package net.mrphan.calibre

import android.content.Context

/**
 * Xac dinh "sach/tien trinh nao vua duoc dong lai gan day nhat" tren toan bo thu vien, de phuc vu
 * nut FAB "Tiep tuc doc" (xem MainActivity#openLastActivity) va tinh nang tu dong mo sach khi khoi
 * dong app (xem MainActivity#onCreate, pref_auto_resume_last_book).
 *
 * So sanh 2 nguon "hoat dong gan day nhat" DOC LAP voi nhau, vi mot cuon co the vua duoc doc chu
 * (last_locator_updated_at trong bang metadata) va mot cuon KHAC co the vua duoc nghe TTS
 * (updated_at trong bang tts_chapter_progress) - ai co timestamp lon hon thi thang, khong gia dinh
 * san mot loai la uu tien hon loai kia.
 *
 * KHONG doi schema DB: ca 2 cot/bang dung o day (metadata.last_locator_updated_at,
 * tts_chapter_progress.updated_at) da ton tai san (xem MetadataDatabaseHelper DATABASE_VERSION 9
 * va 10), chi doc lai, khong ghi.
 */
object ContinueReadingResolver {

    enum class Mode { READING, LISTENING }

    data class LastActivity(
        val uuid: String,
        val lpath: String,
        val title: String,
        val mode: Mode,
        /** Chi khac null khi mode == LISTENING: href chuong EPUB dang nghe do, truyen thang cho
         * EpubReaderActivity.buildAudioSessionIntent() de resume dung cau da nghe. */
        val chapterHref: String?,
    )

    /**
     * Chay tren THREAD PHU (day la SQLite query dong bo, khong duoc goi tu main thread - xem noi
     * goi ham nay trong MainActivity, luon boc trong new Thread{}).
     *
     * @return null neu chua tung co sach nao duoc doc/nghe (app moi cai, hoac da xoa het du lieu).
     */
    @JvmStatic
    fun findMostRecent(context: Context): LastActivity? {
        val db = MetadataDatabaseHelper(context.applicationContext)
        try {
            val readable = db.readableDatabase

            // Ung vien 1: sach dang doc chu gan day nhat (lpath IS NOT NULL nghia la sach da co
            // file that su tren may, khong phai chi la metadata cache tu luot duyet OPDS).
            var readingUuid: String? = null
            var readingLpath: String? = null
            var readingTitle: String? = null
            var readingUpdatedAt = 0L
            readable.rawQuery(
                "SELECT uuid, lpath, title, last_locator_updated_at FROM metadata " +
                    "WHERE lpath IS NOT NULL AND last_locator_updated_at > 0 " +
                    "ORDER BY last_locator_updated_at DESC LIMIT 1",
                null,
            ).use { c ->
                if (c.moveToFirst()) {
                    readingUuid = c.getString(0)
                    readingLpath = c.getString(1)
                    readingTitle = c.getString(2)
                    readingUpdatedAt = c.getLong(3)
                }
            }

            // Ung vien 2: chuong TTS dang nghe gan day nhat, JOIN sang metadata de lay lpath/title
            // va loai bo nhung ban ghi tts_chapter_progress "mo coi" (sach da bi xoa khoi may
            // nhung ban ghi tien trinh nghe con sot lai).
            var listenUuid: String? = null
            var listenLpath: String? = null
            var listenTitle: String? = null
            var listenChapterHref: String? = null
            var listenUpdatedAt = 0L
            readable.rawQuery(
                "SELECT m.uuid, m.lpath, m.title, t.chapter_href, t.updated_at " +
                    "FROM tts_chapter_progress t JOIN metadata m ON m.uuid = t.uuid " +
                    "WHERE m.lpath IS NOT NULL ORDER BY t.updated_at DESC LIMIT 1",
                null,
            ).use { c ->
                if (c.moveToFirst()) {
                    listenUuid = c.getString(0)
                    listenLpath = c.getString(1)
                    listenTitle = c.getString(2)
                    listenChapterHref = c.getString(3)
                    listenUpdatedAt = c.getLong(4)
                }
            }

            val listenId = listenUuid
            val readId = readingUuid
            return when {
                listenId != null && listenUpdatedAt > readingUpdatedAt -> LastActivity(
                    uuid = listenId,
                    lpath = listenLpath ?: return null,
                    title = listenTitle ?: "",
                    mode = Mode.LISTENING,
                    chapterHref = listenChapterHref,
                )
                readId != null -> LastActivity(
                    uuid = readId,
                    lpath = readingLpath ?: return null,
                    title = readingTitle ?: "",
                    mode = Mode.READING,
                    chapterHref = null,
                )
                else -> null
            }
        } finally {
            db.close()
        }
    }
}
