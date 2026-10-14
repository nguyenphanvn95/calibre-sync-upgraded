package net.mrphan.calibre.reader.tts.dictionary

/**
 * Ap dung danh sach [TtsDictionaryEntry] vao 1 chuoi text TRUOC KHI dua vao TtsEngine, KHONG
 * dung de hien thi len man hinh (xem TtsController#resumePlaybackLoop -- chi `sentences[i]`,
 * ban goc, duoc dua vao currentSentenceText, con `speechSentences[i]`, ban da thay the, moi
 * duoc dua vao engine.prefetch()/engine.speak()).
 *
 * Khop nguyen tu (whole-word), khong phan biet hoa/thuong, dung \b Unicode-aware don gian: quy
 * uoc \b cua Java/Kotlin Regex dua tren \w = [a-zA-Z0-9_], khong bao gom ky tu co dau tieng
 * Viet, nen \b khong nhan dung ranh gioi tu khi ca 2 phia deu la tu tieng Viet co dau. Voi
 * dung use-case chinh cua tinh nang nay - ten rieng goc Latin/English (David, Emma, Sherlock,
 * ...) xen giua doan van tieng Viet co dau - \b van hoat dong dung, vi ban than "David" chi
 * gom ky tu ASCII, con dau cau/khoang trang xung quanh (rat co kha nang co, vi ten rieng
 * thuong duoc tach roi trong cau) da du de \b nhan dung ranh gioi. Truong hop ca 2 ben
 * "source_text" deu la tu tieng Viet co dau (it xay ra voi tinh nang nay, muc dich chinh la
 * ten rieng) se KHONG duoc \b nhan dung - gioi han da biet, chua xu ly trong ban nay.
 */
object TtsDictionaryReplacer {

    fun apply(text: String, entries: List<TtsDictionaryEntry>): String {
        if (entries.isEmpty()) return text
        var result = text
        for (entry in entries) {
            if (entry.sourceText.isBlank()) continue
            val pattern = Regex("\\b${Regex.escape(entry.sourceText)}\\b", RegexOption.IGNORE_CASE)
            result = pattern.replace(result, Regex.escapeReplacement(entry.replacementText))
        }
        return result
    }
}
