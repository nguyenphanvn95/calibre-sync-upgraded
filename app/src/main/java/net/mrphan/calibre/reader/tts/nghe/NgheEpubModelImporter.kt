package net.mrphan.calibre.reader.tts.nghe

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import net.mrphan.calibre.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class NgheEpubSpeaker(
    val id: Int,
    val name: String,
    val description: String,
)

data class NgheEpubImportedModel(
    val id: String,
    val displayName: String,
    val encPath: String,
    val lang: String,
    val speakers: List<NgheEpubSpeaker>,
)

data class NgheEpubVoiceRef(
    val model: NgheEpubImportedModel,
    val speaker: NgheEpubSpeaker,
) {
    val voiceId: String get() = "${model.id}#${speaker.id}"
    val displayName: String get() =
        if (model.speakers.size <= 1) model.displayName else "${model.displayName} - ${speaker.name}"
}

private data class ScannedModel(
    val id: String,
    val displayName: String,
    val encFileName: String,
    val encFile: DocumentFile,
    val jsonFile: DocumentFile,
    val lang: String,
    val speakers: List<NgheEpubSpeaker>,
)

object NgheEpubModelImporter {
    private const val MODELS_DIR = "ngheepubtts_models"
    private const val MANIFEST_FILE = "manifest.json"
    private const val SAMPLE_RATE = 24000

    fun modelsRootDir(context: Context): File =
        File(context.filesDir, MODELS_DIR).apply { mkdirs() }

    fun scanAndImport(context: Context, treeUri: Uri): List<NgheEpubImportedModel> {
        val scanned = scan(context, treeUri)
        val manifest = loadManifest(context).toMutableMap()

        for (item in scanned) {
            val sourceModified = maxOf(item.encFile.lastModified(), item.jsonFile.lastModified())
            val existing = manifest[item.id]
            if (existing != null && existing.second == sourceModified) continue
            try {
                val imported = importOne(context, item)
                manifest[item.id] = imported to sourceModified
            } catch (e: Exception) {
                Logger.e(e)
            }
        }

        saveManifest(context, manifest)
        return manifest.values.map { it.first }
    }

    fun loadImportedModels(context: Context): List<NgheEpubImportedModel> =
        loadManifest(context).values.map { it.first }

    fun allVoiceRefs(context: Context): List<NgheEpubVoiceRef> =
        loadImportedModels(context).flatMap { model ->
            model.speakers.map { speaker -> NgheEpubVoiceRef(model, speaker) }
        }

    fun sampleRate(): Int = SAMPLE_RATE

    private fun scan(context: Context, treeUri: Uri): List<ScannedModel> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val children = root.listFiles()
        val byName = children.associateBy { it.name ?: "" }
        val jsonFile = byName["model-tts.json"] ?: byName["tts-models.json"] ?: return emptyList()
        val json = context.contentResolver.openInputStream(jsonFile.uri)?.bufferedReader()?.use { it.readText() }
            ?: return emptyList()

        val voices = JSONObject(json).optJSONObject("data")?.optJSONArray("voices")
            ?: JSONObject(json).optJSONArray("voices")
            ?: JSONArray()
        val result = mutableListOf<ScannedModel>()
        for (i in 0 until voices.length()) {
            val obj = voices.optJSONObject(i) ?: continue
            val encFileName = obj.optString("download_url")
                .substringAfterLast('/')
                .takeIf { it.endsWith(".enc", ignoreCase = true) }
                ?: continue
            val encFile = byName[encFileName]?.takeIf { it.isFile } ?: continue
            val speakersJson = obj.optJSONArray("speakers") ?: JSONArray()
            val speakers = mutableListOf<NgheEpubSpeaker>()
            for (s in 0 until speakersJson.length()) {
                val speakerObj = speakersJson.optJSONObject(s) ?: continue
                speakers += NgheEpubSpeaker(
                    id = speakerObj.optInt("id", s),
                    name = speakerObj.optString("name", "Speaker $s"),
                    description = speakerObj.optString("description", ""),
                )
            }
            if (speakers.isEmpty()) speakers += NgheEpubSpeaker(0, obj.optString("name", encFileName), "")
            result += ScannedModel(
                id = encFileName.removeSuffix(".enc"),
                displayName = obj.optString("name", encFileName.removeSuffix(".enc")),
                encFileName = encFileName,
                encFile = encFile,
                jsonFile = jsonFile,
                lang = obj.optString("lang", "vi-north"),
                speakers = speakers,
            )
        }
        return result
    }

    private fun importOne(context: Context, item: ScannedModel): NgheEpubImportedModel {
        val modelDir = File(modelsRootDir(context), item.id).apply { mkdirs() }
        val encDest = File(modelDir, item.encFileName)
        context.contentResolver.openInputStream(item.encFile.uri).use { input ->
            requireNotNull(input) { "Cannot open ${item.encFile.uri}" }
            encDest.outputStream().use { output -> input.copyTo(output) }
        }
        return NgheEpubImportedModel(
            id = item.id,
            displayName = item.displayName,
            encPath = encDest.absolutePath,
            lang = item.lang,
            speakers = item.speakers,
        )
    }

    private fun loadManifest(context: Context): Map<String, Pair<NgheEpubImportedModel, Long>> {
        val file = File(modelsRootDir(context), MANIFEST_FILE)
        if (!file.exists()) return emptyMap()
        return try {
            val arr = JSONArray(file.readText())
            val result = LinkedHashMap<String, Pair<NgheEpubImportedModel, Long>>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val speakersArr = obj.optJSONArray("speakers") ?: JSONArray()
                val speakers = mutableListOf<NgheEpubSpeaker>()
                for (s in 0 until speakersArr.length()) {
                    val speakerObj = speakersArr.getJSONObject(s)
                    speakers += NgheEpubSpeaker(
                        id = speakerObj.getInt("id"),
                        name = speakerObj.getString("name"),
                        description = speakerObj.optString("description", ""),
                    )
                }
                val model = NgheEpubImportedModel(
                    id = obj.getString("id"),
                    displayName = obj.getString("displayName"),
                    encPath = obj.getString("encPath"),
                    lang = obj.getString("lang"),
                    speakers = speakers,
                )
                result[model.id] = model to obj.getLong("sourceModified")
            }
            result
        } catch (e: Exception) {
            Logger.e(e)
            emptyMap()
        }
    }

    private fun saveManifest(context: Context, manifest: Map<String, Pair<NgheEpubImportedModel, Long>>) {
        val arr = JSONArray()
        for ((model, sourceModified) in manifest.values) {
            val obj = JSONObject()
            obj.put("id", model.id)
            obj.put("displayName", model.displayName)
            obj.put("encPath", model.encPath)
            obj.put("lang", model.lang)
            obj.put("sourceModified", sourceModified)
            val speakers = JSONArray()
            model.speakers.forEach { speaker ->
                speakers.put(JSONObject().apply {
                    put("id", speaker.id)
                    put("name", speaker.name)
                    put("description", speaker.description)
                })
            }
            obj.put("speakers", speakers)
            arr.put(obj)
        }
        File(modelsRootDir(context), MANIFEST_FILE).writeText(arr.toString())
    }
}
