package net.mrphan.calibre.reader.lookup

/**
 * Ket qua tra tu, du lieu toi thieu de ve UI: tu goc, phien am (neu co), danh sach nghia
 * theo tu loai, va nguon (de hien "Nguon: Wiktionary" duoi cung, giong cach Readest hien thi
 * nguon tra cuu -- xem KDoc dau [DictionaryProvider]).
 */
data class DictionaryResult(
    val word: String,
    val phonetic: String?,
    val meanings: List<DictionaryMeaning>,
    val sourceName: String,
    val sourceUrl: String?,
)

data class DictionaryMeaning(
    val partOfSpeech: String?,
    val definitions: List<String>,
)

data class TranslationResult(
    val sourceText: String,
    val translatedText: String,
    val detectedSourceLanguage: String?,
    val targetLanguage: String,
    val providerName: String,
)

/**
 * Trang thai chung cho [LookupBottomSheetFragment], tranh moi mode (Dictionary/Translate) tu
 * ve loading/error UI rieng -- ca hai deu chi can Loading -> Success(data) | Error.
 */
sealed class LookupUiState<out T> {
    data object Loading : LookupUiState<Nothing>()
    data class Success<T>(val data: T) : LookupUiState<T>()
    data class Error(val message: String? = null) : LookupUiState<Nothing>()
}
