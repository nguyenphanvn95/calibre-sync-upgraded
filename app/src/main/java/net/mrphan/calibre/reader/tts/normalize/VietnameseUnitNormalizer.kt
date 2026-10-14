package net.mrphan.calibre.reader.tts.normalize

/** Tien te, phan tram, don vi do, thu tu, so dien thoai -- port tu buoc 8-14 cua pipeline
 * vietnormalizer. */
object VietnameseUnitNormalizer {

    private val PERCENT_RANGE = Regex("""\b(\d+(?:[.,]\d+)?)-(\d+(?:[.,]\d+)?)%""")
    private val PERCENT_SINGLE = Regex("""\b(\d+(?:[.,]\d+)?)%""")
    private val CURRENCY_VND = Regex("""\b(\d{1,3}(?:\.\d{3})+|\d+)\s?đ\b""", RegexOption.IGNORE_CASE)
    private val ORDINAL = Regex("""\bthứ\s?(\d+)\b""", RegexOption.IGNORE_CASE)
    private val PHONE = Regex("""\b(0\d{9,10})\b""") // so VN 10-11 so, bat dau bang 0

    private val UNIT_SUFFIX = linkedMapOf(
        // Thu tu quan trong: pattern dai/cu the hon phai dung TRUOC pattern ngan hon (vd
        // "km/h" phai match truoc "km" don le), giong dung linkedMapOf de giu thu tu duyet.
        "km/h" to "ki-lô-mét trên giờ",
        "m/s" to "mét trên giây",
        "km2" to "ki-lô-mét vuông",
        "m2" to "mét vuông",
        "km" to "ki-lô-mét",
        "kg" to "ki-lô-gam",
        "cm" to "xăng-ti-mét",
        "mm" to "mi-li-mét",
        "m" to "mét",
        "g" to "gam",
        "%" to "phần trăm", // fallback, PERCENT_* o tren xu ly truoc nen it khi roi toi day
    )

    fun normalizePercent(text: String): String {
        var result = PERCENT_RANGE.replace(text) { m ->
            val (a, b) = m.destructured
            "${readNumberMaybeDecimal(a)} đến ${readNumberMaybeDecimal(b)} phần trăm"
        }
        result = PERCENT_SINGLE.replace(result) { m ->
            "${readNumberMaybeDecimal(m.groupValues[1])} phần trăm"
        }
        return result
    }

    fun normalizeCurrency(text: String): String = CURRENCY_VND.replace(text) { m ->
        val digitsOnly = m.groupValues[1].replace(".", "")
        "${VietnameseNumberWords.convertInteger(digitsOnly)} đồng"
    }

    fun normalizeOrdinal(text: String): String = ORDINAL.replace(text) { m ->
        val n = m.groupValues[1].toIntOrNull() ?: return@replace m.value
        val word = when (n) {
            1 -> "nhất"
            4 -> "tư"
            else -> VietnameseNumberWords.convertInteger(n.toString())
        }
        "thứ $word"
    }

    fun normalizePhone(text: String): String = PHONE.replace(text) { m ->
        VietnameseNumberWords.readDigitByDigit(m.groupValues[1])
    }

    /** Doc "120km/h" -> so + ten don vi. Regex don gian: [so][don vi da biet], khop dai nhat
     * truoc (xem thu tu trong UNIT_SUFFIX). */
    fun normalizeUnits(text: String): String {
        var result = text
        for ((suffix, spoken) in UNIT_SUFFIX) {
            val pattern = Regex("""\b(\d+(?:[.,]\d+)?)${Regex.escape(suffix)}\b""", RegexOption.IGNORE_CASE)
            result = pattern.replace(result) { m -> "${readNumberMaybeDecimal(m.groupValues[1])} $spoken" }
        }
        return result
    }

    private fun readNumberMaybeDecimal(raw: String): String =
        if (raw.contains(',') || raw.contains('.')) {
            VietnameseNumberWords.convertDecimalNumber(raw.replace('.', ','))
        } else {
            VietnameseNumberWords.convertInteger(raw)
        }
}
