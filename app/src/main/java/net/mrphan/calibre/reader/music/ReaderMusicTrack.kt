package net.mrphan.calibre.reader.music

data class ReaderMusicTrack(
    val title: String,
    val artist: String,
    val url: String,
    val thumbUrl: String? = null,
    val sourceName: String = "",
)

data class ReaderMusicRepositoryLink(
    val name: String,
    val url: String,
)
