package net.mrphan.calibre.reader.tts.nghi

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** One valid, not-yet-imported voice pair found while scanning the user-chosen NghiTTS models
 * folder. */
data class NghiScannedModel(
    val baseName: String,
    val onnxFile: DocumentFile,
    val jsonFile: DocumentFile,
)

/**
 * Scans a SAF tree (the folder the user picked in Settings > "TTS Models" > "Thư mục models")
 * for valid `{name}.onnx` + config pairs. Supports both filename conventions seen in the wild:
 *  - `{name}.onnx` + `{name}.onnx.json` (nghitts's own distribution format, see
 *    github.com/nghimestudio/nghitts "Model File Structure")
 *  - `{name}.onnx` + `{name}.json` (generic Piper voices as distributed on HuggingFace)
 */
object NghiModelScanner {
    fun scan(context: Context, treeUri: Uri): List<NghiScannedModel> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val children = root.listFiles()
        val byName = children.associateBy { it.name ?: "" }

        val result = mutableListOf<NghiScannedModel>()
        for (file in children) {
            val name = file.name ?: continue
            if (!name.endsWith(".onnx", ignoreCase = true)) continue
            val baseName = name.removeSuffix(".onnx")

            val jsonFile = byName["$baseName.onnx.json"] ?: byName["$baseName.json"]
            if (jsonFile != null && jsonFile.isFile) {
                result += NghiScannedModel(baseName, file, jsonFile)
            }
        }
        return result
    }
}
