package net.mrphan.calibre.reader.tts.nghe

import android.content.Context

class NgheEpubModelStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getModelDirUri(): String? = prefs.getString(KEY_MODEL_DIR_URI, null)

    fun saveModelDirUri(uriString: String) {
        prefs.edit().putString(KEY_MODEL_DIR_URI, uriString).apply()
    }

    fun getDefaultVoiceId(): String? = prefs.getString(KEY_DEFAULT_VOICE_ID, null)

    fun saveDefaultVoiceId(voiceId: String) {
        prefs.edit().putString(KEY_DEFAULT_VOICE_ID, voiceId).apply()
    }

    companion object {
        private const val PREFS_NAME = "ngheepubtts_settings"
        private const val KEY_MODEL_DIR_URI = "model_dir_uri"
        private const val KEY_DEFAULT_VOICE_ID = "default_voice_id"
    }
}
