package net.mrphan.calibre.reader.lookup

import net.mrphan.calibre.R
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Provider tra tu (interface nhieu-provider, lay cam hung tu kien truc "multiple dictionary
 * providers" cua Readest -- xem readest/readest#170 -- nhung goi mang o day la
 * OkHttp/coroutines thuan Kotlin, khong lien quan gi den code TS/React ben Readest).
 */
interface DictionaryProvider {
    val id: String
    val displayNameResId: Int

    /**
     * Nem IOException/org.json.JSONException khi that bai -- caller ([LookupBottomSheetFragment])
     * chiu trach nhiem bat va chuyen thanh thong bao loi, provider khong tu format message loi
     * de tranh phu thuoc Context/string res o day.
     */
    suspend fun lookup(word: String, uiLanguageHint: String): DictionaryResult
}

/**
 * https://dictionaryapi.dev -- mien phi, khong can API key, nhung chi ho tro tieng Anh. Day la
 * provider mac dinh (uu tien) vi khong can cau hinh gi ca.
 *
 * **Chua build/run-verified** (khong co mang toi api.dictionaryapi.dev trong sandbox viet file
 * nay) -- can kiem tra tren thiet bi/emulator that: (1) response JSON thuc te co dung dinh dang
 * nhu tai lieu cong khai cua dictionaryapi.dev hay khong (field "phonetic" co the trong, phien am
 * thuc su nam trong mang "phonetics[].text" o mot so tu -- hien tai chi doc field "phonetic" don,
 * neu trong thi bo qua phien am, khong crash), va (2) ma loi HTTP tra ve khi khong tim thay tu la
 * 404 (theo tai lieu) chu khong phai ma khac.
 */
class FreeDictionaryProvider(private val client: LookupHttpClient) : DictionaryProvider {
    override val id = "free_dictionary"
    override val displayNameResId = R.string.lookup_provider_free_dictionary

    override suspend fun lookup(word: String, uiLanguageHint: String): DictionaryResult {
        val encoded = URLEncoder.encode(word.trim(), "UTF-8")
        val request = Request.Builder()
            .url("https://api.dictionaryapi.dev/api/v2/entries/en/$encoded")
            .get()
            .build()
        // executeForBody nem IOException neu !isSuccessful (bao gom ca 404 = khong tim thay).
        val body = client.executeForBody(request)
        val root = JSONArray(body)
        if (root.length() == 0) throw IOException("empty response")
        val entry = root.getJSONObject(0)
        val phonetic = entry.optString("phonetic").takeIf { it.isNotBlank() }
        val meaningsArr = entry.optJSONArray("meanings") ?: JSONArray()
        val meanings = (0 until meaningsArr.length()).map { i ->
            val m = meaningsArr.getJSONObject(i)
            val defsArr = m.optJSONArray("definitions") ?: JSONArray()
            val defs = (0 until defsArr.length()).mapNotNull { j ->
                defsArr.getJSONObject(j).optString("definition").takeIf { it.isNotBlank() }
            }
            DictionaryMeaning(
                partOfSpeech = m.optString("partOfSpeech").takeIf { it.isNotBlank() },
                definitions = defs,
            )
        }
        return DictionaryResult(
            word = entry.optString("word", word),
            phonetic = phonetic,
            meanings = meanings,
            sourceName = "Free Dictionary API",
            sourceUrl = "https://www.dictionaryapi.dev",
        )
    }
}

/**
 * Fallback khi [FreeDictionaryProvider] loi (tu khong co trong Free Dictionary API, hoac loi
 * mang): dung MediaWiki API cua Wiktionary, lay doan extract dau tien (plain text). Ho tro nhieu
 * ngon ngu hon -- vd tra tu tieng Viet se goi vi.wiktionary.org thay vi en.wiktionary.org, chon
 * theo [uiLanguageHint] (ngon ngu hien thi cua app, xem cach truyen vao o
 * [LookupBottomSheetFragment.runDictionaryLookup]).
 *
 * **Chua build/run-verified** -- can kiem tra tren thiet bi that: MediaWiki API co the tra ve
 * "pages" voi key la pageid (so) khi tim thay, hoac key "-1" khi khong tim thay trang -- code
 * duoi day dua vao dung quy uoc do (tai lieu MediaWiki Action API), nhung chua chay thu voi tu
 * tieng Viet thuc te de xac nhan "extract" luon co noi dung huu ich (co the rong voi trang chi
 * co bang bieu, khong co doan van mo dau).
 */
class WiktionaryProvider(private val client: LookupHttpClient) : DictionaryProvider {
    override val id = "wiktionary"
    override val displayNameResId = R.string.lookup_provider_wiktionary

    override suspend fun lookup(word: String, uiLanguageHint: String): DictionaryResult {
        val lang = if (uiLanguageHint == "vi") "vi" else "en"
        val encoded = URLEncoder.encode(word.trim(), "UTF-8")
        val url = "https://$lang.wiktionary.org/w/api.php" +
            "?action=query&titles=$encoded&prop=extracts&exintro=1&explaintext=1&redirects=1&format=json"
        val request = Request.Builder().url(url).get().build()
        val body = client.executeForBody(request)
        val pages = JSONObject(body).getJSONObject("query").getJSONObject("pages")
        val firstKey = pages.keys().asSequence().firstOrNull() ?: throw IOException("no page")
        if (firstKey == "-1") throw IOException("page missing (word not found)")
        val extract = pages.getJSONObject(firstKey).optString("extract")
        if (extract.isBlank()) throw IOException("empty extract")
        return DictionaryResult(
            word = word,
            phonetic = null,
            meanings = listOf(DictionaryMeaning(partOfSpeech = null, definitions = listOf(extract.trim()))),
            sourceName = "Wiktionary ($lang)",
            sourceUrl = "https://$lang.wiktionary.org/wiki/$encoded",
        )
    }
}
