package net.mrphan.calibre.reader.tts.music

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Một file nhạc nền do người dùng chọn qua Storage Access Framework (SAF). [uri] là
 * content:// URI với quyền đọc lâu dài (persistable) -- xem BackgroundMusicSettingsDialog cho
 * chỗ takePersistableUriPermission được gọi. */
data class BackgroundMusicTrack(val uri: Uri, val displayName: String)

/**
 * Persists nhạc nền: bật/tắt, volume (0..100), và danh sách file. Cùng pattern với
 * [net.mrphan.calibre.reader.tts.TtsSettingsStore] -- SharedPreferences file riêng, không đụng
 * MetadataDatabaseHelper vì đây là cấu hình app-wide, không phải theo từng sách.
 */
class BackgroundMusicSettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)
    fun setEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()

    /** 0..100, hiển thị dạng "%" giống ảnh 2. Được nhân với 0.01f khi set vào MediaPlayer. */
    fun getVolumePercent(): Int = prefs.getInt(KEY_VOLUME, 1)
    fun setVolumePercent(percent: Int) =
        prefs.edit().putInt(KEY_VOLUME, percent.coerceIn(0, 100)).apply()

    fun getTracks(): List<BackgroundMusicTrack> {
        val raw = prefs.getString(KEY_TRACKS, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            BackgroundMusicTrack(Uri.parse(obj.getString("uri")), obj.getString("name"))
        }
    }

    fun saveTracks(tracks: List<BackgroundMusicTrack>) {
        val array = JSONArray()
        tracks.forEach { track ->
            array.put(JSONObject().put("uri", track.uri.toString()).put("name", track.displayName))
        }
        prefs.edit().putString(KEY_TRACKS, array.toString()).apply()
    }

    fun addTrack(track: BackgroundMusicTrack) = saveTracks(getTracks() + track)
    fun removeTrack(uri: Uri) = saveTracks(getTracks().filterNot { it.uri == uri })

    companion object {
        private const val PREFS_NAME = "tts_background_music"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_VOLUME = "volume_percent"
        private const val KEY_TRACKS = "tracks_json"
    }
}
