package net.mrphan.calibre.reader.tts.edge

import net.mrphan.calibre.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Low-level client for Microsoft Edge's unofficial "Read Aloud" text-to-speech WebSocket
 * service -- the same reverse-engineered protocol used by rany2/edge-tts (Python), msedge-tts
 * (Node), and Readest's EdgeTTSClient.ts. No API key; see EdgeTtsDrm for the anti-abuse token.
 *
 * This class knows nothing about [net.mrphan.calibre.reader.tts.TtsEngine] -- it's a plain
 * "text in, MP3 bytes out" client, wrapped by [EdgeTtsEngine] to fit the app's TtsEngine plugin
 * interface (same separation SystemTtsEngine keeps from raw android.speech.tts.TextToSpeech).
 *
 * [synthesize] is a genuine `suspend fun` (not a blocking call wrapped in Dispatchers.IO) so that
 * cancelling the calling coroutine -- e.g. TtsController.pause()/skipToNextSentence() -- closes
 * the WebSocket immediately via `invokeOnCancellation`, instead of waiting out a fixed timeout.
 */
class EdgeTtsClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    /** Fetches the full voice catalog. Network call -- caller must invoke off the main thread
     * (this whole class is designed to be called from suspend functions on Dispatchers.IO). */
    fun fetchVoices(): List<EdgeVoiceInfo> {
        val url = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list" +
            "?trustedclienttoken=${EdgeTtsDrm.TRUSTED_CLIENT_TOKEN}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", EdgeTtsDrm.userAgent())
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val arr = JSONArray(body)
            val result = mutableListOf<EdgeVoiceInfo>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result += EdgeVoiceInfo(
                    shortName = obj.optString("ShortName"),
                    locale = obj.optString("Locale"),
                    gender = obj.optString("Gender"),
                    friendlyName = obj.optString("FriendlyName", obj.optString("ShortName")),
                )
            }
            return result
        }
    }

    /**
     * Synthesizes [text] with [voiceShortName] (e.g. "vi-VN-HoaiMyNeural") and [ratePercent]
     * (SSML prosody rate, e.g. "+20%", "-10%"), returning the raw MP3 bytes of the whole
     * utterance, or null if synthesis failed / was cancelled.
     *
     * Opens ONE short-lived WebSocket connection per call -- simpler and more robust than
     * pooling a long-lived connection across sentences (Readest's own issue tracker shows
     * connection-reuse causing choppy audio after ~a page of reading; see EdgeTTSClient.ts
     * issue readest/readest#1777). A fresh handshake per sentence costs ~100-200ms, which is
     * negligible next to network TTS latency.
     */
    suspend fun synthesize(
        text: String,
        voiceShortName: String,
        locale: String,
        ratePercent: Int,
    ): ByteArray? = suspendCancellableCoroutine { cont ->
        val secMsGec = EdgeTtsDrm.generateSecMsGec()
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=${EdgeTtsDrm.TRUSTED_CLIENT_TOKEN}" +
            "&Sec-MS-GEC=$secMsGec" +
            "&Sec-MS-GEC-Version=${EdgeTtsDrm.SEC_MS_GEC_VERSION}" +
            "&ConnectionId=$connectionId"

        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", EdgeTtsDrm.userAgent())
            .build()

        val audioBuffer = ByteArrayOutputStream()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(buildSpeechConfigMessage())
                webSocket.send(buildSsmlMessage(text, voiceShortName, locale, ratePercent))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    webSocket.close(1000, null)
                    if (cont.isActive) cont.resume(audioBuffer.toByteArray())
                } else if (text.contains("Path:response") && text.contains("\"Error\"")) {
                    webSocket.close(1000, null)
                    if (cont.isActive) cont.resume(null)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val data = bytes.toByteArray()
                    if (data.size < 2) return
                    val headerLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                    val audioStart = 2 + headerLength
                    if (audioStart <= data.size && binaryFramePath(data, headerLength) == "audio") {
                        audioBuffer.write(data, audioStart, data.size - audioStart)
                    }
                } catch (e: Exception) {
                    Logger.e(e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Logger.e(Exception("EdgeTTS websocket failure", t))
                if (cont.isActive) cont.resume(null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (cont.isActive) cont.resume(if (audioBuffer.size() > 0) audioBuffer.toByteArray() else null)
            }
        }

        val socket = httpClient.newWebSocket(request, listener)
        cont.invokeOnCancellation {
            try { socket.cancel() } catch (_: Exception) {}
        }
    }

    private fun buildSpeechConfigMessage(): String {
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val timestamp = edgeTimestamp()
        val config = "{\"context\":{\"synthesis\":{\"audio\":{" +
            "\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
        return "X-Timestamp:$timestamp\r\n" +
            "X-RequestId:$requestId\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            config
    }

    private fun buildSsmlMessage(text: String, voiceShortName: String, locale: String, ratePercent: Int): String {
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val timestamp = rfc1123Now()
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val rate = if (ratePercent >= 0) "+$ratePercent%" else "$ratePercent%"
        val voiceName = voiceForSsml(voiceShortName)
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$locale'>" +
            "<voice name='$voiceName'>" +
            "<prosody pitch='+0Hz' rate='$rate' volume='+0%'>$escaped</prosody>" +
            "</voice></speak>"
        return "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${timestamp}Z\r\n" +
            "Path:ssml\r\n\r\n" +
            ssml
    }

    private fun voiceForSsml(shortName: String): String {
        val parts = Regex("^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$").matchEntire(shortName) ?: return shortName
        val lang = "${parts.groupValues[1]}-${parts.groupValues[2]}"
        val name = parts.groupValues[3].substringAfterLast("-")
        return "Microsoft Server Speech Text to Speech Voice ($lang, $name)"
    }

    private fun binaryFramePath(data: ByteArray, headerLength: Int): String? {
        if (headerLength <= 0 || data.size < headerLength + 2) return null
        val headerText = String(data, 2, headerLength, Charsets.UTF_8)
        return headerText
            .lineSequence()
            .firstOrNull { it.startsWith("Path:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
    }

    private fun edgeTimestamp(): String =
        SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())

    private fun rfc1123Now(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date())
    }
}
