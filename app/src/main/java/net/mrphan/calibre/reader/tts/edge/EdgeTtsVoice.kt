package net.mrphan.calibre.reader.tts.edge

/** One voice entry as returned by Edge TTS's `voices/list` endpoint. */
data class EdgeVoiceInfo(
    val shortName: String,   // e.g. "vi-VN-HoaiMyNeural"
    val locale: String,      // e.g. "vi-VN"
    val gender: String,      // "Female" / "Male"
    val friendlyName: String,
)
