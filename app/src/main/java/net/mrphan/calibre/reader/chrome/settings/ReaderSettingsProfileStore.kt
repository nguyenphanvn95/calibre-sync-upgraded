package net.mrphan.calibre.reader.chrome.settings

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Doc/ghi danh sach "Cau hinh tao san" do NGUOI DUNG tu luu (khac voi
 * [ReaderSettingsProfilePresets.ALL] la hang so, khong luu trong SharedPreferences).
 * Dung chung SharedPreferences mac dinh voi EpubReaderActivity.settingsPrefs, nhung khoa rieng
 * ([KEY_CUSTOM_PROFILES]) de khong dam vao cac PREF_READER_* hien co.
 */
class ReaderSettingsProfileStore(context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    fun loadCustomProfiles(): List<ReaderSettingsProfile> {
        val raw = prefs.getString(KEY_CUSTOM_PROFILES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i -> parseProfile(array.getJSONObject(i)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveNewProfile(name: String, snapshot: ReaderSettingsProfile): ReaderSettingsProfile {
        val profile = snapshot.copy(id = "custom_${UUID.randomUUID()}", name = name, isBuiltIn = false)
        val updated = loadCustomProfiles() + profile
        persist(updated)
        return profile
    }

    fun deleteProfile(id: String) {
        persist(loadCustomProfiles().filterNot { it.id == id })
    }

    fun renameProfile(id: String, newName: String) {
        persist(loadCustomProfiles().map { if (it.id == id) it.copy(name = newName) else it })
    }

    private fun persist(profiles: List<ReaderSettingsProfile>) {
        val array = JSONArray()
        for (p in profiles) array.put(toJson(p))
        prefs.edit().putString(KEY_CUSTOM_PROFILES, array.toString()).apply()
    }

    private fun toJson(p: ReaderSettingsProfile): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("font", JSONObject().apply {
            put("fontSizeMultiplier", p.font.fontSizeMultiplier)
            put("fontWeight", p.font.fontWeight)
            put("fontFamilyName", p.font.fontFamilyName)
            put("publisherStylesOverrideEnabled", p.font.publisherStylesOverrideEnabled)
        })
        put("layout", JSONObject().apply {
            put("lineHeight", p.layout.lineHeight)
            put("pageMargins", p.layout.pageMargins)
            put("twoColumnEnabled", p.layout.twoColumnEnabled)
            put("paragraphSpacing", p.layout.paragraphSpacing)
            put("paragraphIndent", p.layout.paragraphIndent)
            put("wordSpacing", p.layout.wordSpacing)
            put("letterSpacing", p.layout.letterSpacing)
            put("justifyTextEnabled", p.layout.justifyTextEnabled)
            put("hyphensEnabled", p.layout.hyphensEnabled)
        })
        put("color", JSONObject().apply {
            put("colorThemeId", p.color.colorThemeId)
            put("themeMode", p.color.themeMode)
            put("screenBrightness", p.color.screenBrightness)
        })
        put("behavior", JSONObject().apply {
            put("keepScreenOnEnabled", p.behavior.keepScreenOnEnabled)
        })
        put("tts", JSONObject().apply {
            put("speechRateMultiplier", p.tts.speechRateMultiplier)
        })
    }

    private fun parseProfile(o: JSONObject): ReaderSettingsProfile? = try {
        val font = o.getJSONObject("font")
        val layout = o.getJSONObject("layout")
        val color = o.getJSONObject("color")
        val behavior = o.getJSONObject("behavior")
        val tts = o.getJSONObject("tts")
        ReaderSettingsProfile(
            id = o.getString("id"),
            name = o.getString("name"),
            isBuiltIn = false,
            font = FontSettings(
                fontSizeMultiplier = font.getDouble("fontSizeMultiplier"),
                fontWeight = font.getDouble("fontWeight"),
                fontFamilyName = if (font.isNull("fontFamilyName")) null else font.getString("fontFamilyName"),
                publisherStylesOverrideEnabled = font.getBoolean("publisherStylesOverrideEnabled"),
            ),
            layout = LayoutSettings(
                lineHeight = layout.getDouble("lineHeight"),
                pageMargins = layout.getDouble("pageMargins"),
                twoColumnEnabled = layout.getBoolean("twoColumnEnabled"),
                paragraphSpacing = layout.getDouble("paragraphSpacing"),
                paragraphIndent = layout.getDouble("paragraphIndent"),
                wordSpacing = layout.getDouble("wordSpacing"),
                letterSpacing = layout.getDouble("letterSpacing"),
                justifyTextEnabled = layout.getBoolean("justifyTextEnabled"),
                hyphensEnabled = layout.getBoolean("hyphensEnabled"),
            ),
            color = ColorSettings(
                colorThemeId = color.getString("colorThemeId"),
                themeMode = color.getString("themeMode"),
                screenBrightness = color.getInt("screenBrightness"),
            ),
            behavior = BehaviorSettings(keepScreenOnEnabled = behavior.getBoolean("keepScreenOnEnabled")),
            tts = TtsSettings(speechRateMultiplier = tts.getDouble("speechRateMultiplier").toFloat()),
        )
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val KEY_CUSTOM_PROFILES = "reader_settings_custom_profiles_json"
    }
}
