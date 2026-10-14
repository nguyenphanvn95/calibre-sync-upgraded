package net.mrphan.calibre.reader.lookup

import net.mrphan.calibre.R
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

interface TranslationProvider {
    val id: String
    val displayNameResId: Int
    val requiresApiKey: Boolean

    /** [targetLang]: ma ISO 2 chu (vi, en, ja, ...). Nem IOException/IllegalStateException khi loi. */
    suspend fun translate(text: String, targetLang: String): TranslationResult
}

/**
 * Endpoint cong khai **khong chinh thuc** cua Google Translate (`client=gtx`), khong can API key
 * -- day la endpoint duoc nhieu app FOSS (vd "Simple Translation" tren F-Droid) va extension ma
 * nguon mo dung de dich nhanh khong can dang ky. Duoc dat lam provider dich mac dinh vi nguoi
 * dung co the dich ngay khong can cau hinh gi, giong muc do don gian nhat Readest ho tro truoc
 * khi nguoi dung tu them DeepL key trong Settings.
 *
 * **Rui ro da biet**: day la endpoint khong chinh thuc, Google co the doi dinh dang response
 * hoac chan bat cu luc nao -- vi vay luon phai co xu ly loi va khong duoc coi day la nguon dich
 * duy nhat/dang tin cay lau dai.
 *
 * **Chua build/run-verified** -- can kiem tra tren thiet bi that: dinh dang JSON tra ve thuc te
 * (mang long nhau `[[[ "dich", "goc", null, ... ], ...], null, "en"]`) dung nhu tai lieu khong
 * chinh thuc mo ta, va truong hop van ban dai (nhieu cau) co the tra ve nhieu phan tu trong mang
 * segments[0] can noi lai dung thu tu.
 */
class GoogleTranslateFreeProvider(private val client: LookupHttpClient) : TranslationProvider {
    override val id = "google_free"
    override val displayNameResId = R.string.lookup_provider_google_translate
    override val requiresApiKey = false

    override suspend fun translate(text: String, targetLang: String): TranslationResult {
        val encodedText = URLEncoder.encode(text, "UTF-8")
        val url = "https://translate.googleapis.com/translate_a/single" +
            "?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encodedText"
        val request = Request.Builder().url(url).get().build()
        val body = client.executeForBody(request)
        val root = JSONArray(body)
        val segments = root.getJSONArray(0)
        val translated = StringBuilder()
        for (i in 0 until segments.length()) {
            translated.append(segments.getJSONArray(i).optString(0, ""))
        }
        if (translated.isEmpty()) throw IOException("empty translation")
        val detectedLang = root.optString(2).takeIf { it.isNotBlank() }
        return TranslationResult(
            sourceText = text,
            translatedText = translated.toString(),
            detectedSourceLanguage = detectedLang,
            targetLanguage = targetLang,
            providerName = "Google Translate",
        )
    }
}

/**
 * DeepL free tier -- can nguoi dung tu dang ky va nhap API key rieng trong Settings (KHONG duoc
 * hardcode key nao vao source code, xem [ReaderLookupSettingsStore]). Ma ngon ngu DeepL viet HOA
 * (VI, EN, ...), khac voi Google (vi, en...) nen phai `uppercase()` truoc khi goi.
 *
 * **Chua build/run-verified** -- can kiem tra: (1) `api-free.deepl.com` la dung host cho tai
 * khoan DeepL API Free (khac voi `api.deepl.com` cua tai khoan Pro tra phi, de nham la loi xac
 * thuc rat kho doan), va (2) response loi cua DeepL (vd sai key, het quota) tra ve ma HTTP nao --
 * hien tai chi dua vao `LookupHttpClient.executeForBody` nem IOException chung cho moi ma loi
 * khac 2xx, chua phan biet rieng "sai key" voi "het han muc mien phi" de hien thong bao ro hon.
 */
class DeepLProvider(
    private val client: LookupHttpClient,
    private val apiKeyProvider: () -> String?,
) : TranslationProvider {
    override val id = "deepl"
    override val displayNameResId = R.string.lookup_provider_deepl
    override val requiresApiKey = true

    override suspend fun translate(text: String, targetLang: String): TranslationResult {
        val apiKey = apiKeyProvider()?.takeUnless { it.isBlank() }
            ?: throw IllegalStateException("DeepL API key not configured")
        val formBody = FormBody.Builder()
            .add("auth_key", apiKey)
            .add("text", text)
            .add("target_lang", targetLang.uppercase())
            .build()
        val request = Request.Builder()
            .url("https://api-free.deepl.com/v2/translate")
            .post(formBody)
            .build()
        val body = client.executeForBody(request)
        val translations = JSONObject(body).getJSONArray("translations")
        val first = translations.getJSONObject(0)
        return TranslationResult(
            sourceText = text,
            translatedText = first.getString("text"),
            detectedSourceLanguage = first.optString("detected_source_language").takeIf { it.isNotBlank() },
            targetLanguage = targetLang,
            providerName = "DeepL",
        )
    }
}
