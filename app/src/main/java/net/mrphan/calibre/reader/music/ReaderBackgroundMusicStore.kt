package net.mrphan.calibre.reader.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ReaderBackgroundMusicStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLinks(): List<ReaderMusicRepositoryLink> {
        val saved = prefs.getString(KEY_LINKS, null)
        if (saved != null) return parseLinks(saved)
        return listOf(
            ReaderMusicRepositoryLink(
                name = "Kho nhac mau",
                url = DEFAULT_SAMPLE_PATH,
            ),
        )
    }

    fun saveLinks(links: List<ReaderMusicRepositoryLink>) {
        val array = JSONArray()
        links.forEach { link ->
            array.put(JSONObject().put("name", link.name).put("url", link.url))
        }
        prefs.edit().putString(KEY_LINKS, array.toString()).apply()
    }

    fun getCachedTracks(): List<ReaderMusicTrack> = parseTracks(prefs.getString(KEY_TRACKS, null))

    fun saveCachedTracks(tracks: List<ReaderMusicTrack>) {
        val array = JSONArray()
        tracks.forEach { track ->
            array.put(
                JSONObject()
                    .put("title", track.title)
                    .put("artist", track.artist)
                    .put("url", track.url)
                    .put("thumbUrl", track.thumbUrl)
                    .put("sourceName", track.sourceName),
            )
        }
        prefs.edit().putString(KEY_TRACKS, array.toString()).apply()
    }

    fun loadBundledTracks(): List<ReaderMusicTrack> {
        return try {
            context.assets.open(ASSET_SAMPLE).bufferedReader().use { reader ->
                ReaderMusicJsonParser.parse(reader.readText(), "Kho nhac mau")
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseLinks(json: String?): List<ReaderMusicRepositoryLink> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val name = item.optString("name", "").trim()
                    val url = item.optString("url", "").trim()
                    if (url.isNotBlank()) add(ReaderMusicRepositoryLink(name.ifBlank { url }, url))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseTracks(json: String?): List<ReaderMusicTrack> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val url = item.optString("url", "").trim()
                    if (url.isBlank()) continue
                    add(
                        ReaderMusicTrack(
                            title = item.optString("title", "Unknown"),
                            artist = item.optString("artist", ""),
                            url = url,
                            thumbUrl = item.optString("thumbUrl", "").ifBlank { null },
                            sourceName = item.optString("sourceName", ""),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val PREFS_NAME = "reader_background_music"
        private const val KEY_LINKS = "links_json"
        private const val KEY_TRACKS = "tracks_cache_json"
        private const val ASSET_SAMPLE = "reader_background_music/audio_list.json"
        const val DEFAULT_SAMPLE_PATH = "D:\\My Projects\\bdhvs_assistant\\audio_list.json"
    }
}
