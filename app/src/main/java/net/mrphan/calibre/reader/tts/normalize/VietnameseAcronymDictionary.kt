package net.mrphan.calibre.reader.tts.normalize

import android.content.Context

/**
 * Nap acronyms.csv / non_vietnamese_words.csv tu assets/vietnamese_normalize/, cung dinh dang
 * CSV (header "acronym,transliteration" va "original,transliteration") ma vietnormalizer dung,
 * de sau nay co the dong bo file CSV goc tu repo do ma khong can doi code.
 */
class VietnameseAcronymDictionary private constructor(
    private val acronyms: Map<String, String>,
    private val nonVietnameseWords: Map<String, String>,
) {
    fun expandAcronym(word: String): String? = acronyms[word.uppercase()]

    fun replaceNonVietnameseWord(word: String): String? = nonVietnameseWords[word.lowercase()]

    companion object {
        private const val ASSET_DIR = "vietnamese_normalize"

        /** Tu dien rong -- dung khi khong the nap tu assets (fallback an toan) hoac cho test. */
        fun empty(): VietnameseAcronymDictionary = VietnameseAcronymDictionary(emptyMap(), emptyMap())

        fun load(context: Context): VietnameseAcronymDictionary {
            val am = context.applicationContext.assets
            val acronyms = readCsvMap(am, "$ASSET_DIR/acronyms.csv", keyUpper = true)
            val words = readCsvMap(am, "$ASSET_DIR/non_vietnamese_words.csv", keyUpper = false)
            return VietnameseAcronymDictionary(acronyms, words)
        }

        /** Constructor test-only, nhan truc tiep map thay vi doc tu AssetManager -- dung cho
         * unit test JVM khong co Context that (xem VietnameseTextNormalizer.forTesting). */
        internal fun forTesting(
            acronyms: Map<String, String> = emptyMap(),
            nonVietnameseWords: Map<String, String> = emptyMap(),
        ): VietnameseAcronymDictionary = VietnameseAcronymDictionary(acronyms, nonVietnameseWords)

        private fun readCsvMap(
            am: android.content.res.AssetManager,
            path: String,
            keyUpper: Boolean,
        ): Map<String, String> {
            val map = mutableMapOf<String, String>()
            try {
                am.open(path).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.drop(1) // bo header
                        .forEach { line ->
                            if (line.isBlank()) return@forEach
                            val parts = line.split(",", limit = 2)
                            if (parts.size != 2) return@forEach
                            val key = parts[0].trim().let { if (keyUpper) it.uppercase() else it.lowercase() }
                            val value = parts[1].trim()
                            if (key.isNotEmpty() && value.isNotEmpty()) map[key] = value
                        }
                }
            } catch (e: java.io.IOException) {
                net.mrphan.calibre.Logger.e(e)
            }
            return map
        }
    }
}
