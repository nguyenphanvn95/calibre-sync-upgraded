package net.mrphan.calibre.reader.tts

/**
 * Observable playback state for the "Đọc cho nghe" feature, consumed by [TtsMiniPlayerView] and
 * [TtsPlayerBottomSheet]. There is no fixed audio file, only live per-sentence synthesis, so
 * in-app progress is expressed as "sentence X / Y" ([sentenceIndex]/[sentenceCount]). The system
 * media notification still wants a real seek bar though -- [sentenceDurationsMs] provides an
 * estimated "virtual timeline" for that (see [virtualPositionMs]/[virtualDurationMs]).
 */
data class TtsPlaybackState(
    val isPlaying: Boolean,
    val chapterTitle: String,
    /** href (readingOrder key) của chương đang phát -- dùng bởi TtsPlaybackService để biết đang
     * lưu tiến trình cho chương nào (xem TtsChapterProgressStore/markChapterCompletedIfNeeded). */
    val chapterHref: String = "",
    val sentenceIndex: Int,
    val sentenceCount: Int,
    val speechRate: Float,
    val sleepTimerMinutesRemaining: Int?,
    // --- Mới cho full player (trình phát chuyên nghiệp) ---
    /** Nội dung câu đang được đọc (hoặc câu sắp đọc khi paused), rỗng khi idle. Full player
     * hiển thị đoạn này dạng trích dẫn nhiều dòng, giống Readest. */
    val currentSentenceText: String = "",
    /** 0f..1f, tỉ lệ sentenceIndex/sentenceCount -- dùng cho progress bar dạng liên tục thay vì
     * chỉ hiện "câu X/Y" khô khan. Tính trong TtsController, không tính lại trong UI. */
    val progressFraction: Float = 0f,
    /* MOI: thoi luong uoc luong (ms) cua tung cau trong chuong hien tai, dung de dung
       MediaSession lam mot "virtual timeline" cho thanh tien trinh keo duoc trong notification.
       Uoc luong theo so ky tu / toc do doc, khong phai thoi gian phat thuc te. */
    val sentenceDurationsMs: List<Long> = emptyList(),
    /* MOI: vi tri cua currentSentenceText trong van ban goc cua chuong (chapter.text truyen vao
       TtsController#startChapter, TRUOC khi qua bat ky buoc thay the nao), dung de
       TtsSentenceHighlighter tinh Locator.progression cho tinh nang to sang cau dang doc ngay
       trong noi dung sach. -1 nghia la "chua co du lieu" (vd luc idle) -- caller phai kiem tra
       truoc khi dung. */
    val currentSentenceCharOffset: Int = -1,
    val currentSentenceCharLength: Int = 0,
    val chapterTotalTextLength: Int = 0,
    // MOI: 40 ky tu ngay truoc/sau cau dang doc, cat tu chinh chapter.text goc (KHONG qua tu
    // dien phat am/chuan hoa) -- dung lam Locator.Text.before/after de Readium khop DOM chinh
    // xac hon thay vi chi dua vao "highlight" don le, giam rui ro applyDecorations() im lang
    // khong hien gi khi cau trung lap o nhieu cho trong chuong. Rong neu dang o dau/cuoi chuong.
    val currentSentenceContextBefore: String = "",
    val currentSentenceContextAfter: String = "",
) {
    /** Vi tri "ao" (ms) cua cau dang doc, dung lam PlaybackStateCompat position. */
    val virtualPositionMs: Long
        get() = sentenceDurationsMs.take(sentenceIndex).sum()

    /** Tong thoi luong "ao" (ms) cua ca chuong, dung lam MediaMetadata duration. */
    val virtualDurationMs: Long
        get() = sentenceDurationsMs.sum().coerceAtLeast(1L)

    companion object {
        fun idle() = TtsPlaybackState(
            isPlaying = false,
            chapterTitle = "",
            chapterHref = "",
            sentenceIndex = 0,
            sentenceCount = 0,
            speechRate = 1.0f,
            sleepTimerMinutesRemaining = null,
            currentSentenceText = "",
            progressFraction = 0f,
            sentenceDurationsMs = emptyList(),
            currentSentenceCharOffset = -1,
            currentSentenceCharLength = 0,
            chapterTotalTextLength = 0,
            currentSentenceContextBefore = "",
            currentSentenceContextAfter = "",
        )
    }
}
