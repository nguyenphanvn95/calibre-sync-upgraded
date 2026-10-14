package net.mrphan.calibre.reader.tts.nghi

import android.content.Context
import android.net.Uri
import net.mrphan.calibre.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One successfully-imported NghiTTS (Piper/VITS) voice, ready to be loaded by sherpa-onnx. */
data class NghiImportedModel(
    val id: String,               // stable id = baseName at import time
    val displayName: String,
    val onnxPath: String,         // patched .onnx, app-private storage
    val tokensPath: String,       // generated tokens.txt, app-private storage
    val sampleRate: Int,
    val numSpeakers: Int,
    val speakerIdMap: Map<String, Int>,
)

/**
 * Copies a scanned NghiTTS model pair from the user's SAF folder into app-private storage
 * (`filesDir/nghitts_models/<id>/`), patches the copy's ONNX metadata (see
 * [OnnxMetadataPatcher] -- NEVER touches the user's original files, only the copy), generates
 * `tokens.txt`, and records the result in a small JSON manifest so [net.mrphan.calibre.reader.tts.NghiTtsEngine]
 * doesn't need to re-scan/re-patch on every app start.
 *
 * Extracting `espeak-ng-data` from assets happens once, shared across all imported models --
 * see [ensureEspeakDataExtracted].
 */
object NghiModelImporter {
    private const val MODELS_DIR = "nghitts_models"
    private const val MANIFEST_FILE = "manifest.json"
    private const val ESPEAK_DATA_DIR = "espeak-ng-data"

    fun modelsRootDir(context: Context): File =
        File(context.filesDir, MODELS_DIR).apply { mkdirs() }

    fun espeakDataDir(context: Context): File =
        File(context.filesDir, ESPEAK_DATA_DIR)

    /** Extracts `assets/espeak-ng-data/` into app-private storage exactly once (checked via a
     * marker file, since it's ~2-4MB of many small files -- not worth re-copying every launch). */
    fun ensureEspeakDataExtracted(context: Context) {
        val dest = espeakDataDir(context)
        val marker = File(dest, ".extracted")
        if (marker.exists()) return
        try {
            dest.mkdirs()
            copyAssetDirRecursive(context, "espeak-ng-data", dest)
            marker.writeText("1")
        } catch (e: Exception) {
            Logger.e(e)
        }
    }

    private fun copyAssetDirRecursive(context: Context, assetPath: String, destDir: File) {
        val assetManager = context.assets
        val entries = assetManager.list(assetPath) ?: return
        if (entries.isEmpty()) {
            // Leaf file, not a directory.
            File(destDir.parentFile, destDir.name).parentFile?.mkdirs()
            assetManager.open(assetPath).use { input ->
                destDir.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        destDir.mkdirs()
        for (entry in entries) {
            val childAssetPath = "$assetPath/$entry"
            val childDest = File(destDir, entry)
            val subEntries = assetManager.list(childAssetPath)
            if (subEntries.isNullOrEmpty()) {
                assetManager.open(childAssetPath).use { input ->
                    childDest.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                copyAssetDirRecursive(context, childAssetPath, childDest)
            }
        }
    }

    /** Scans [treeUri] and imports every new/changed model pair found. Safe to call repeatedly
     * (app startup, or the manual "Scan" button) -- already-imported, unchanged models are
     * skipped by comparing SAF `lastModified()` against the manifest entry. Returns the full,
     * up-to-date list of imported models. */
    fun scanAndImport(context: Context, treeUri: Uri): List<NghiImportedModel> {
        ensureEspeakDataExtracted(context)

        val scanned = NghiModelScanner.scan(context, treeUri)
        val manifest = loadManifest(context).toMutableMap()

        for (item in scanned) {
            val existing = manifest[item.baseName]
            val sourceModified = item.onnxFile.lastModified()
            if (existing != null && existing.second == sourceModified) continue // unchanged

            try {
                val imported = importOne(context, item)
                manifest[item.baseName] = imported to sourceModified
            } catch (e: Exception) {
                Logger.e(e)
            }
        }

        saveManifest(context, manifest)
        return manifest.values.map { it.first }
    }

    private fun importOne(context: Context, item: NghiScannedModel): NghiImportedModel {
        val modelDir = File(modelsRootDir(context), item.baseName).apply { mkdirs() }
        val onnxDest = File(modelDir, "model.onnx")
        val tokensDest = File(modelDir, "tokens.txt")

        context.contentResolver.openInputStream(item.onnxFile.uri).use { input ->
            requireNotNull(input) { "Cannot open ${item.onnxFile.uri}" }
            onnxDest.outputStream().use { output -> input.copyTo(output) }
        }

        val json = context.contentResolver.openInputStream(item.jsonFile.uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("Cannot read config for ${item.baseName}")
        val config = PiperVoiceConfig.parse(json)

        NghiTokensWriter.write(config, tokensDest)

        OnnxMetadataPatcher.appendMetadata(
            onnxDest,
            mapOf(
                "model_type" to "vits",
                "comment" to "piper",
                "language" to config.languageNameEnglish,
                "voice" to config.espeakVoice,
                "has_espeak" to "1",
                "n_speakers" to config.numSpeakers.toString(),
                "sample_rate" to config.sampleRate.toString(),
            ),
        )

        return NghiImportedModel(
            id = item.baseName,
            displayName = item.baseName,
            onnxPath = onnxDest.absolutePath,
            tokensPath = tokensDest.absolutePath,
            sampleRate = config.sampleRate,
            numSpeakers = config.numSpeakers,
            speakerIdMap = config.speakerIdMap,
        )
    }

    fun loadImportedModels(context: Context): List<NghiImportedModel> =
        loadManifest(context).values.map { it.first }

    private fun loadManifest(context: Context): Map<String, Pair<NghiImportedModel, Long>> {
        val file = File(modelsRootDir(context), MANIFEST_FILE)
        if (!file.exists()) return emptyMap()
        return try {
            val arr = JSONArray(file.readText())
            val result = LinkedHashMap<String, Pair<NghiImportedModel, Long>>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val speakerMap = LinkedHashMap<String, Int>()
                obj.optJSONObject("speakerIdMap")?.let { sm ->
                    val keys = sm.keys()
                    while (keys.hasNext()) { val k = keys.next(); speakerMap[k] = sm.getInt(k) }
                }
                val model = NghiImportedModel(
                    id = obj.getString("id"),
                    displayName = obj.getString("displayName"),
                    onnxPath = obj.getString("onnxPath"),
                    tokensPath = obj.getString("tokensPath"),
                    sampleRate = obj.getInt("sampleRate"),
                    numSpeakers = obj.getInt("numSpeakers"),
                    speakerIdMap = speakerMap,
                )
                result[model.id] = model to obj.getLong("sourceModified")
            }
            result
        } catch (e: Exception) {
            Logger.e(e)
            emptyMap()
        }
    }

    private fun saveManifest(context: Context, manifest: Map<String, Pair<NghiImportedModel, Long>>) {
        val arr = JSONArray()
        for ((model, sourceModified) in manifest.values) {
            val obj = JSONObject()
            obj.put("id", model.id)
            obj.put("displayName", model.displayName)
            obj.put("onnxPath", model.onnxPath)
            obj.put("tokensPath", model.tokensPath)
            obj.put("sampleRate", model.sampleRate)
            obj.put("numSpeakers", model.numSpeakers)
            obj.put("sourceModified", sourceModified)
            val speakerObj = JSONObject()
            model.speakerIdMap.forEach { (k, v) -> speakerObj.put(k, v) }
            obj.put("speakerIdMap", speakerObj)
            arr.put(obj)
        }
        File(modelsRootDir(context), MANIFEST_FILE).writeText(arr.toString())
    }
}
