package net.mrphan.calibre.reader.tts.edge

import java.security.MessageDigest

/**
 * Generates the `Sec-MS-GEC` anti-abuse token required by Microsoft's (unofficial, reverse
 * engineered) Edge "Read Aloud" WebSocket endpoint. Same algorithm as rany2/edge-tts's
 * `drm.py` / msedge-tts's DRM module -- a SHA-256 hash of a 5-minute-rounded Windows FILETIME
 * tick count concatenated with the public trusted client token. Not a secret: this token, and
 * the algorithm to compute it, are widely published (see rany2/edge-tts on GitHub).
 */
object EdgeTtsDrm {
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    /** Chromium/Edge version string embedded in the User-Agent and Sec-MS-GEC-Version --
     * the exact version doesn't need to track a real current Edge release, it only needs to
     * look plausible to the server. */
    const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    const val CHROMIUM_MAJOR_VERSION = "144"
    const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"

    private const val WIN_EPOCH_SECONDS = 11_644_473_600L
    private const val ROUND_TO_SECONDS = 300L

    fun generateSecMsGec(): String {
        var ticks = (System.currentTimeMillis() / 1000L) + WIN_EPOCH_SECONDS
        ticks -= ticks % ROUND_TO_SECONDS
        ticks *= 10_000_000L // seconds -> 100-nanosecond intervals (Windows FILETIME ticks)

        val toHash = "$ticks$TRUSTED_CLIENT_TOKEN"
        val digest = MessageDigest.getInstance("SHA-256").digest(toHash.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    fun userAgent(): String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR_VERSION.0.0.0"
}
