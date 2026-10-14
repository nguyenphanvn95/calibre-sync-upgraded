package net.mrphan.calibre.reader.tts.dictionary

/** Mot dong trong "Tu dien phat am" TTS: [sourceText] la tu/cum tu goc xuat hien trong sach
 * (VD "David"), [replacementText] la cach doc thay the ma engine TTS se phat am thay vao (VD
 * "Đa vít"). Danh sach nay la TOAN CUC, ap dung cho moi sach - xem
 * TtsDictionaryManagerActivity/TtsController#startChapter. [id] la -1 khi entry chua duoc luu
 * vao DB (dang tao moi, chua co trong TtsDictionaryStore). */
data class TtsDictionaryEntry(
    val id: Long,
    val sourceText: String,
    val replacementText: String,
    val updatedAt: Long,
)
