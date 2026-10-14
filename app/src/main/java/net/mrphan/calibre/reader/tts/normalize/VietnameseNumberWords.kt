package net.mrphan.calibre.reader.tts.normalize

/**
 * Chuyen so nguyen/thap phan sang chu tieng Viet. Port tu logic doc so cua
 * github.com/nghimestudio/vietnormalizer (buoc so 15 "Standalone number conversion" trong
 * pipeline cua ho), viet lai bang Kotlin thuan, khong phu thuoc thu vien ngoai.
 */
object VietnameseNumberWords {

    private val DIGITS = arrayOf(
        "không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín",
    )

    // Cac bac 3-chu-so: don vi, nghin, trieu, ty, nghin ty, trieu ty, ty ty ...
    // Du dung cho so len toi 10^21 - qua du cho moi truong hop thuc te trong sach/tai lieu.
    private val SCALE_WORDS = arrayOf(
        "", "nghìn", "triệu", "tỷ", "nghìn tỷ", "triệu tỷ", "tỷ tỷ",
    )

    /** So am/duong bat ky, dang chuoi chi gom chu so (khong dau phan cach). */
    fun convertInteger(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val negative = trimmed.startsWith("-")
        val digitsOnly = trimmed.trimStart('-').trimStart('+')
        if (digitsOnly.isEmpty() || !digitsOnly.all { it.isDigit() }) return raw

        // Bo so 0 dau chuoi (giu lai it nhat 1 chu so) truoc khi nhom.
        val normalized = digitsOnly.trimStart('0').ifEmpty { "0" }
        if (normalized == "0") return "không"

        val groups = mutableListOf<Int>()
        var remaining = normalized
        while (remaining.isNotEmpty()) {
            val start = (remaining.length - 3).coerceAtLeast(0)
            groups.add(0, remaining.substring(start).toInt())
            remaining = remaining.substring(0, start)
        }
        if (groups.size > SCALE_WORDS.size) return raw // qua lon, khong ho tro -- fallback giu nguyen

        val parts = mutableListOf<String>()
        groups.forEachIndexed { indexFromLeft, group ->
            val scaleIndex = groups.size - 1 - indexFromLeft
            if (group == 0) {
                // Nhom = 0 o giua day (vd 1.000.005 -> nhom trieu=1, nhom nghin=0, nhom don vi=5)
                // van can "không trăm" khi day KHONG phai nhom dau tien VA con nhom khac dang sau
                // no khac 0 -- de don gian va dung voi da so truong hop thuc te, bo qua nhom = 0
                // hoan toan (khong doc "khong nghin") tru khi no o giua 2 nhom khac-0, luc do van
                // cong lai o buoc "ghep noi" ben duoi bang cach chen "không trăm linh" thu cong
                // neu can. Voi pham vi so o sach/tai lieu (thuong < ty), truong hop nay hiem gap.
                return@forEachIndexed
            }
            val isLeadingGroup = parts.isEmpty()
            val groupWords = readGroupOfThree(group, forceHundreds = !isLeadingGroup)
            val scaleWord = SCALE_WORDS[scaleIndex]
            parts.add(if (scaleWord.isEmpty()) groupWords else "$groupWords $scaleWord")
        }
        val result = parts.joinToString(" ")
        return if (negative) "âm $result" else result
    }

    /** So thap phan dang "123,45" hoac "123.45" (dau phay theo quy uoc Viet Nam: "phẩy"). */
    fun convertDecimalNumber(raw: String, decimalSeparator: Char = ','): String {
        val idx = raw.indexOf(decimalSeparator)
        if (idx < 0) return convertInteger(raw)
        val intPart = raw.substring(0, idx)
        val fracPart = raw.substring(idx + 1)
        val intWords = convertInteger(intPart.ifBlank { "0" })
        // Doc phan thap phan tung chu so mot (quy uoc pho bien cho TTS, tranh nhap nhang
        // "45" -> "bon muoi lam" khi thuc ra y nghia la ".4" ".5"): "6,5" -> "sáu phẩy năm".
        val fracWords = fracPart.map { c -> if (c.isDigit()) DIGITS[c - '0'] else "" }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return "$intWords phẩy $fracWords"
    }

    /** Doc 1 nhom <= 999. [forceHundreds] = true khi day KHONG phai nhom dau tien cua ca so
     * (vd 1.005.000 -> nhom trieu doc binh thuong, nhom nghin=5 phai doc "không trăm linh năm"
     * chu khong duoc rut gon thanh "năm" nhu khi no dung mot minh). */
    private fun readGroupOfThree(n: Int, forceHundreds: Boolean): String {
        require(n in 0..999)
        val hundreds = n / 100
        val remainder = n % 100
        val tens = remainder / 10
        val ones = remainder % 10
        val sb = StringBuilder()

        if (hundreds > 0) {
            sb.append(DIGITS[hundreds]).append(" trăm")
        } else if (forceHundreds) {
            sb.append("không trăm")
        }

        when {
            tens == 0 -> {
                if (ones == 0) {
                    // het nhom, khong them gi (hundreds > 0 hoac forceHundreds da xu ly xong)
                } else if (hundreds > 0 || forceHundreds) {
                    sb.append(if (sb.isEmpty()) "" else " ").append("linh ").append(DIGITS[ones])
                } else {
                    // nhom don le duoi 10, dung mot minh (vd "5" -> "năm")
                    sb.append(DIGITS[ones])
                }
            }
            tens == 1 -> {
                sb.append(if (sb.isEmpty()) "" else " ").append("mười")
                if (ones > 0) {
                    val onesWord = when (ones) {
                        1 -> "một" // "mười một", KHONG phai "mười mốt"
                        5 -> "lăm" // "mười lăm"
                        else -> DIGITS[ones]
                    }
                    sb.append(" ").append(onesWord)
                }
            }
            else -> {
                sb.append(if (sb.isEmpty()) "" else " ").append(DIGITS[tens]).append(" mươi")
                if (ones > 0) {
                    val onesWord = when (ones) {
                        1 -> "mốt"
                        4 -> "tư" // quy uoc doc thuong gap: "hai mươi tư" (co the doi thanh "bốn"
                                   // neu muon nhat quan tuyet doi voi cach doc so hoc thuan tuy)
                        5 -> "lăm"
                        else -> DIGITS[ones]
                    }
                    sb.append(" ").append(onesWord)
                }
            }
        }
        return sb.toString().trim()
    }

    /** Doc tung chu so rieng le, dung cho so dien thoai (buoc 12 trong pipeline vietnormalizer). */
    fun readDigitByDigit(raw: String): String =
        raw.filter { it.isDigit() }.map { DIGITS[it - '0'] }.joinToString(" ")
}
