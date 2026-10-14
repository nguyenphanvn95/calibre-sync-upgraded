package net.mrphan.calibre.reader.tts.nghi

import java.io.File

/** Generates sherpa-onnx's `tokens.txt` symbol table from a Piper voice config's
 * `phoneme_id_map` -- exactly mirrors the official conversion script documented at
 * k2-fsa.github.io/sherpa/onnx/tts/piper.html (`generate_tokens()`): one line per symbol,
 * `"<symbol> <id>"`, using the first id in each symbol's id list. Line order does not matter
 * to sherpa-onnx's parser (it just builds a symbol->id lookup table), so we simply preserve
 * the JSON's own key order. */
object NghiTokensWriter {
    fun write(config: PiperVoiceConfig, outFile: File) {
        outFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            for ((symbol, ids) in config.phonemeIdMap) {
                val id = ids.firstOrNull() ?: continue
                writer.write(symbol)
                writer.write(" ")
                writer.write(id.toString())
                writer.write("\n")
            }
        }
    }
}
