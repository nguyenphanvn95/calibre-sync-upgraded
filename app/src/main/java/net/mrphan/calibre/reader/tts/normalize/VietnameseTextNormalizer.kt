package net.mrphan.calibre.reader.tts.normalize

import android.content.Context

/**
 * Buoc chuan hoa van ban tieng Viet truoc khi dua vao TtsEngine -- port lai (khong nhung
 * runtime Python) tu github.com/nghimestudio/vietnormalizer, giu dung THU TU cac buoc ma README
 * cua ho cong bo, de hanh vi tuong thich khi ca hai cung phat trien theo thoi gian:
 *   1. Unicode NFC normalize
 *   2. Ky tu dac biet (&, @, URL/email) -- MVP: bo qua URL/email (sach EPUB hiem khi co)
 *   3-4. Chuan hoa dau cau / don rac -- da co mot phan trong EpubTextExtractor, khong lam lai
 *   5. Khoang nam (vd "1873-1907")
 *   6. Khoang phan tram
 *   7. Ngay/gio (ke ca khoang ngay)
 *   8. So thu tu ("thu 2")
 *   9. Bo dau phan cach nghin (xu ly ngay trong buoc tien te/so o duoi, khong tach rieng)
 *   10. Tien te
 *   11. Phan tram con lai
 *   12. So dien thoai
 *   13. So thap phan
 *   14. Don vi do
 *   15. So doc lap con lai
 *   16. Viet thuong hoa (KHONG ap dung -- TTS tieng Viet khong phan biet hoa/thuong, bo qua de
 *       khong pha huy tinh trang hoa dau cau ma cac engine khac (System TTS) co the dung de nhan
 *       biet cau)
 *   17. Thay the tu viet tat (CSV)
 *   18. Thay the tu khong-phai-tieng-Viet (CSV)
 *   19. Phien am theo quy tac (transliteration) -- CHUA lam trong MVP nay, xem ghi chu cuoi file
 *       tich hop.
 */
class VietnameseTextNormalizer internal constructor(
    private val dictionary: VietnameseAcronymDictionary,
) {
    private val yearRange = Regex("""\b(\d{4})-(\d{4})\b""")
    private val standaloneNumber = Regex("""\b\d+(?:,\d+)?\b""")
    private val wordToken = Regex("""[\p{L}\p{M}]+""")

    fun normalize(text: String): String {
        if (text.isBlank()) return text
        var result = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC) // (1)
        result = result.replace("&", " và ").replace("@", " a còng ") // (2)

        result = yearRange.replace(result) { m -> // (5)
            val (y1, y2) = m.destructured
            "${VietnameseNumberWords.convertInteger(y1)} đến ${VietnameseNumberWords.convertInteger(y2)}"
        }
        result = VietnameseUnitNormalizer.normalizePercent(result) // (6, 11)
        result = VietnameseDateTimeNormalizer.normalize(result) // (7)
        result = VietnameseUnitNormalizer.normalizeOrdinal(result) // (8)
        result = VietnameseUnitNormalizer.normalizeCurrency(result) // (9, 10)
        result = VietnameseUnitNormalizer.normalizePhone(result) // (12)
        result = VietnameseUnitNormalizer.normalizeUnits(result) // (14, bao gom doc so thap phan ben trong)
        result = standaloneNumber.replace(result) { m -> // (13, 15)
            VietnameseUnitNormalizerReadHelper.read(m.value)
        }

        result = wordToken.replace(result) { m -> // (17, 18)
            val w = m.value
            dictionary.expandAcronym(w) ?: dictionary.replaceNonVietnameseWord(w) ?: w
        }
        return result
    }

    companion object {
        @Volatile private var instance: VietnameseTextNormalizer? = null

        fun getInstance(context: Context): VietnameseTextNormalizer =
            instance ?: synchronized(this) {
                instance ?: VietnameseTextNormalizer(
                    VietnameseAcronymDictionary.load(context.applicationContext),
                ).also { instance = it }
            }

        /** Factory test-only: nhan truc tiep [VietnameseAcronymDictionary] thay vi Context, de
         * unit test JVM khong can mock Android AssetManager (xem VietnameseAcronymDictionary
         * .forTesting/.empty). KHONG dung trong code san pham -- luon di qua [getInstance]. */
        internal fun forTesting(
            dictionary: VietnameseAcronymDictionary = VietnameseAcronymDictionary.empty(),
        ): VietnameseTextNormalizer = VietnameseTextNormalizer(dictionary)
    }
}

/** Nho, tach rieng chi de tranh vong lap import giua so thap phan/nguyen trong bien standalone. */
private object VietnameseUnitNormalizerReadHelper {
    fun read(raw: String): String =
        if (raw.contains(',')) VietnameseNumberWords.convertDecimalNumber(raw) else VietnameseNumberWords.convertInteger(raw)
}
