package net.mrphan.calibre.reader.tts.normalize

/** Ngay/gio/khoang ngay -- port tu buoc 5-7 cua pipeline vietnormalizer. */
object VietnameseDateTimeNormalizer {

    // 25-26/12/2023 hoac 25/12/2023 (nam co the vang mat)
    private val DATE_RANGE = Regex("""\b(\d{1,2})-(\d{1,2})/(\d{1,2})(?:/(\d{4}))?\b""")
    private val DATE_SINGLE = Regex("""\b(\d{1,2})/(\d{1,2})(?:/(\d{4}))?\b""")
    private val TIME_HM = Regex("""\b(\d{1,2}):(\d{2})\b""")
    private val TIME_H_SUFFIX = Regex("""\b(\d{1,2})h(\d{2})?\b""") // 15h30, 15h

    fun normalize(text: String): String {
        var result = text
        result = DATE_RANGE.replace(result) { m ->
            val (d1, d2, month, year) = m.destructured
            val monthWords = VietnameseNumberWords.convertInteger(month)
            val d1Words = VietnameseNumberWords.convertInteger(d1)
            val d2Words = VietnameseNumberWords.convertInteger(d2)
            val yearPart = if (year.isNotEmpty()) " năm ${VietnameseNumberWords.convertInteger(year)}" else ""
            "ngày $d1Words đến $d2Words tháng $monthWords$yearPart"
        }
        result = DATE_SINGLE.replace(result) { m ->
            val (day, month, year) = m.destructured
            val dayInt = day.toIntOrNull() ?: return@replace m.value
            val monthInt = month.toIntOrNull() ?: return@replace m.value
            if (dayInt !in 1..31 || monthInt !in 1..12) return@replace m.value
            val dayWords = VietnameseNumberWords.convertInteger(day)
            val monthWords = VietnameseNumberWords.convertInteger(month)
            val yearPart = if (year.isNotEmpty()) " năm ${VietnameseNumberWords.convertInteger(year)}" else ""
            "ngày $dayWords tháng $monthWords$yearPart"
        }
        result = TIME_HM.replace(result) { m ->
            val (h, min) = m.destructured
            val hWords = VietnameseNumberWords.convertInteger(h)
            val minWords = VietnameseNumberWords.convertInteger(min)
            "$hWords giờ $minWords phút"
        }
        result = TIME_H_SUFFIX.replace(result) { m ->
            val h = m.groupValues[1]
            val min = m.groupValues[2]
            val hWords = VietnameseNumberWords.convertInteger(h)
            if (min.isEmpty()) "$hWords giờ" else "$hWords giờ ${VietnameseNumberWords.convertInteger(min)} phút"
        }
        return result
    }
}
