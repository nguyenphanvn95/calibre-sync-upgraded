package net.mrphan.calibre.reader.tts

/**
 * The list of TtsEngines the app knows about -- plays the same role as
 * [net.mrphan.calibre.reader.chrome.ReaderActionRegistry] for the toolbar: the ONE place to edit
 * when adding/removing a TTS model.
 */
object TtsEngineRegistry {
    val engines: List<TtsEngine> = listOf(
        SystemTtsEngine(),
        net.mrphan.calibre.reader.tts.edge.EdgeTtsEngine(),
        NghiTtsEngine(),
        NgheEpubTtsEngine(),
    )

    fun default(): TtsEngine = engines.first { it.isAvailable }

    fun byId(id: String): TtsEngine? = engines.firstOrNull { it.id == id }

    /** Tao MOI 1 instance rieng cua engine ung voi [id] -- KHAC voi [byId], vi [byId] tra ve
     * instance DUNG CHUNG trong [engines] (co the dang phuc vu phien doc chinh). Dung cho cac
     * truong hop can 1 engine TAM THOI, doc lap voi phien dang active (vd
     * [TtsVoicePickerDialog] preview giong doc -- goi setVoice()/speak() truc tiep len instance
     * dung chung se doi state cua engine dang phuc vu phien doc chinh neu nguoi dung dang doc
     * do). Tra ve null neu [id] khong khop engine nao. */
    fun newInstance(id: String): TtsEngine? = when (id) {
        SystemTtsEngine.ENGINE_ID -> SystemTtsEngine()
        net.mrphan.calibre.reader.tts.edge.EdgeTtsEngine.ENGINE_ID ->
            net.mrphan.calibre.reader.tts.edge.EdgeTtsEngine()
        NghiTtsEngine.ENGINE_ID -> NghiTtsEngine()
        NgheEpubTtsEngine.ENGINE_ID -> NgheEpubTtsEngine()
        else -> null
    }

    /** Builds the voice-group list for the "Select Voice" dialog -- one group per engine in
     * [engines], in declaration order.
     *
     * IMPORTANT: each [TtsEngine] only populates its voice list / `isAvailable` state inside
     * [TtsEngine.initialize], which is otherwise only called for whichever engine is currently
     * the *active* one ([TtsController.setEngine]). Without calling it here too, any engine the
     * user hasn't already switched to -- Edge TTS (voices come from a network fetch) and NghiTTS
     * (voices come from scanning imported models) -- would always show up empty/disabled in this
     * picker, even though voices/models genuinely exist. Each engine is expected to make repeat
     * [TtsEngine.initialize] calls cheap/idempotent once it already has data loaded. */
    suspend fun buildVoiceGroups(context: android.content.Context): List<TtsVoiceGroup> {
        val appContext = context.applicationContext
        engines.forEach { engine ->
            try {
                engine.initialize(appContext)
            } catch (e: Exception) {
                net.mrphan.calibre.Logger.e(e)
            }
        }
        return engines.map { engine ->
            TtsVoiceGroup(
                engineId = engine.id,
                engineDisplayName = engine.displayName,
                voices = if (engine.isAvailable) engine.listVoices() else emptyList(),
                disabled = !engine.isAvailable,
            )
        }
    }
}
