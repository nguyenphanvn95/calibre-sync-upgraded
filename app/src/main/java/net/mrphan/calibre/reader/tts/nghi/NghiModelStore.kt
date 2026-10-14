package net.mrphan.calibre.reader.tts.nghi

import android.content.Context

/** Persists the NghiTTS-specific settings: which SAF folder holds the user's models, and which
 * imported model is the default. Same "own SharedPreferences file" precedent as
 * [net.mrphan.calibre.reader.tts.TtsSettingsStore]. */
class NghiModelStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getModelDirUri(): String? = prefs.getString(KEY_MODEL_DIR_URI, null)

    fun saveModelDirUri(uriString: String) {
        prefs.edit().putString(KEY_MODEL_DIR_URI, uriString).apply()
    }

    fun getDefaultModelId(): String? = prefs.getString(KEY_DEFAULT_MODEL_ID, null)

    fun saveDefaultModelId(modelId: String) {
        prefs.edit().putString(KEY_DEFAULT_MODEL_ID, modelId).apply()
    }

    companion object {
        private const val PREFS_NAME = "nghitts_settings"
        private const val KEY_MODEL_DIR_URI = "model_dir_uri"
        private const val KEY_DEFAULT_MODEL_ID = "default_model_id"
    }
}
