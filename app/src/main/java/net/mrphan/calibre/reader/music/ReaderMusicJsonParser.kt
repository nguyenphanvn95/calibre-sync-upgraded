package net.mrphan.calibre.reader.music

import org.json.JSONArray
import org.json.JSONObject

object ReaderMusicJsonParser {
    fun parse(json: String, sourceName: String): List<ReaderMusicTrack> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return emptyList()
        val rootArray = when (trimmed.first()) {
            '[' -> JSONArray(trimmed)
            '{' -> findArray(JSONObject(trimmed))
            else -> JSONArray()
        }
        val tracks = mutableListOf<ReaderMusicTrack>()
        for (i in 0 until rootArray.length()) {
            val item = rootArray.optJSONObject(i) ?: continue
            val url = firstString(
                item,
                "streaming_link",
                "streamingLink",
                "audioUrl",
                "audio_url",
                "youtubeUrl",
                "youtube_url",
                "youtube",
                "youtubeLink",
                "youtube_link",
                "url",
                "audio",
                "src",
                "source",
                "link",
                "file",
                "path",
                "mp3",
            )
            if (url.isBlank()) continue
            val title = firstString(item, "name", "title", "song", "trackName", "audioName", "filename").ifBlank {
                url.substringAfterLast('/').substringBefore('?').ifBlank { "Unknown" }
            }
            val artist = firstString(item, "artist_name", "artistName", "artist", "author", "singer", "composer", "creator", "by")
            val thumb = firstString(item, "thumb", "thumbnail", "thumbnailUrl", "image", "cover", "coverUrl").ifBlank { null }
            tracks += ReaderMusicTrack(
                title = title,
                artist = artist,
                url = url,
                thumbUrl = thumb,
                sourceName = sourceName,
            )
        }
        return tracks.distinctBy { it.url }
    }

    private fun findArray(obj: JSONObject): JSONArray {
        val directKeys = listOf("data", "tracks", "items", "songs", "audios", "list", "music")
        for (key in directKeys) {
            obj.optJSONArray(key)?.let { return it }
        }
        if (obj.has("url") || obj.has("streaming_link")) {
            return JSONArray().put(obj)
        }
        return JSONArray()
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.optString(key, "").trim()
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }
}
