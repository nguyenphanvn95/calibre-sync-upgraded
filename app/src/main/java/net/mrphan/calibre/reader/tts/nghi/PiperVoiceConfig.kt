package net.mrphan.calibre.reader.tts.nghi

import org.json.JSONObject

/** Parsed content of a Piper voice's `{name}.onnx.json` config file -- this is the exact same
 * config format nghitts (nghimestudio/nghitts) distributes alongside every `.onnx` model
 * (Piper trains/exports both together; nghitts just runs them in the browser via ONNX Runtime
 * Web instead of natively). */
data class PiperVoiceConfig(
    val sampleRate: Int,
    val espeakVoice: String,
    val languageNameEnglish: String,
    val numSpeakers: Int,
    /** symbol -> [id] (Piper stores a 1-element list per symbol; some experimental configs use
     * more than one id per symbol, we only need the first per the reference conversion script). */
    val phonemeIdMap: Map<String, List<Int>>,
    /** speaker display name -> speaker id, null/empty for single-speaker models. */
    val speakerIdMap: Map<String, Int>,
) {
    companion object {
        fun parse(json: String): PiperVoiceConfig {
            val root = JSONObject(json)
            val audio = root.getJSONObject("audio")
            val sampleRate = audio.getInt("sample_rate")

            val espeakVoice = root.optJSONObject("espeak")?.optString("voice") ?: "vi"
            val languageName = root.optJSONObject("language")?.optString("name_english") ?: "Vietnamese"
            val numSpeakers = root.optInt("num_speakers", 1)

            val phonemeIdMapObj = root.getJSONObject("phoneme_id_map")
            val phonemeIdMap = LinkedHashMap<String, List<Int>>()
            val keys = phonemeIdMapObj.keys()
            while (keys.hasNext()) {
                val symbol = keys.next()
                val idsArray = phonemeIdMapObj.getJSONArray(symbol)
                phonemeIdMap[symbol] = (0 until idsArray.length()).map { idsArray.getInt(it) }
            }

            val speakerIdMap = LinkedHashMap<String, Int>()
            root.optJSONObject("speaker_id_map")?.let { obj ->
                val speakerKeys = obj.keys()
                while (speakerKeys.hasNext()) {
                    val name = speakerKeys.next()
                    speakerIdMap[name] = obj.getInt(name)
                }
            }

            return PiperVoiceConfig(
                sampleRate = sampleRate,
                espeakVoice = espeakVoice,
                languageNameEnglish = languageName,
                numSpeakers = numSpeakers,
                phonemeIdMap = phonemeIdMap,
                speakerIdMap = speakerIdMap,
            )
        }
    }
}
