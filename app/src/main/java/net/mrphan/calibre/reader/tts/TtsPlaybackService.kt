package net.mrphan.calibre.reader.tts

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import net.mrphan.calibre.CloudRemote
import net.mrphan.calibre.CloudRemoteStore
import net.mrphan.calibre.DeviceLibrarySyncScheduler
import net.mrphan.calibre.EpubReaderActivity
import net.mrphan.calibre.MetadataDatabaseHelper
import net.mrphan.calibre.R
import net.mrphan.calibre.reader.chrome.ReaderProgressAutoSyncLoop
import net.mrphan.calibre.reader.chrome.ReaderSyncSettingsStore
import net.mrphan.calibre.reader.epub.EpubReadingStatsSource
import net.mrphan.calibre.reader.stats.DailyReadingStatsStore
import net.mrphan.calibre.reader.stats.ReadingTimeTracker
import net.mrphan.calibre.reader.tts.dictionary.TtsDictionaryStore
import net.mrphan.calibre.reader.tts.music.BackgroundMusicController
import net.mrphan.calibre.reader.tts.music.BackgroundMusicSettingsStore

/**
 * Owns [TtsController] and [BackgroundMusicController] for the lifetime of a read-aloud
 * session, independent of any Activity. This is what makes "Đọc cho nghe" survive the user
 * backing out of [EpubReaderActivity]: as long as this Service is running in the foreground,
 * the read-aloud session keeps going, and only stops when the user taps the mini player's close
 * button or the notification's stop action -- never merely because the reader screen closed.
 *
 * Extends [LifecycleService] (not plain [android.app.Service]) so it can use `lifecycleScope`
 * the same way [EpubReaderActivity] already does elsewhere in this codebase, keeping the
 * coroutine style consistent instead of hand-rolling a SupervisorJob scope.
 *
 * Deliberately uses `androidx.media:media` (MediaSessionCompat) rather than androidx.media3 --
 * media3 1.10.0 requires compileSdk 36 / AGP 8.9.1+, which this project intentionally avoids
 * right now (see the Readium 3.1.2 pin comment in app/build.gradle.kts).
 */
class TtsPlaybackService : LifecycleService() {

    inner class LocalBinder : Binder() {
        val service: TtsPlaybackService get() = this@TtsPlaybackService
    }

    private val binder = LocalBinder()

    lateinit var ttsController: TtsController
        private set
    lateinit var musicController: BackgroundMusicController
        private set

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManagerCompat

    /** Set by [EpubReaderActivity] right after binding, used only to build the notification's
     * "return to book" tap target and to feed cover art -- the Service has no other reason to
     * know which book is open. */
    var nowPlayingBookTitle: String = ""
    var nowPlayingUuid: String? = null
    var nowPlayingBookPath: String? = null
    var nowPlayingCover: Bitmap? = null

    /** Set by the Activity while bound. This Service simply treats "no provider" as "no next
     * chapter" (stop gracefully) rather than crashing, since a lambda capturing a possibly-gone
     * Activity would leak if the Service tried to hold onto it directly. */
    var nextChapterProvider: (suspend () -> TtsController.ChapterText?)? = null

    private val progressStore by lazy { TtsChapterProgressStore(applicationContext) }
    /** href cua chuong dang duoc theo doi tien trinh (khac null nghia la da luu it nhat 1 lan
     * cho phien nghe hien tai) -- dung de phat hien khi da chuyen sang chuong khac, xem
     * onCreate()'s ttsController.state.onEach{} below. */
    private var lastTrackedHref: String? = null
    private var lastTrackedSentenceCount: Int = 0

    /** True tu luc [TtsPlaybackState] lan dau co sentenceCount > 0 (tuc la mot phien doc THAT SU
     * da bat dau). Bat buoc phai co co nay vi ttsController.state la MutableStateFlow duoc khoi
     * tao san bang TtsPlaybackState.idle() (isPlaying=false, sentenceCount=0) -- .onEach{} ben
     * duoi nhan ngay gia tri nay ngay khi Service vua duoc tao (truoc khi nguoi dung bam "Doc cho
     * nghe"), va no trung khop y het voi dieu kien "phien doc da ket thuc" o duoi. Neu khong co
     * co nay, stopSelfSafely() se bi goi ngay lap tuc, giai phong mediaSession truoc ca khi phat
     * am thanh dau tien -> day chinh la nguyen nhan Service bi he thong don dep ngay khi nguoi
     * dung chuyen sang app khac (mediaSession da "chet" tu dau nen Android khong con ly do gi de
     * giu song Foreground Service loai mediaPlayback nua). */
    private var hasEverStartedPlaying: Boolean = false

    // --- Ghi nhan thoi gian "nghe" nhu thoi gian doc sach (BUG FIX: truoc day
    // net.mrphan.calibre.reader.stats.ReadingTimeTracker chi duoc dieu khien boi
    // onResume()/onPause() cua *Activity*, nen tat man hinh hoac chuyen sang app khac trong khi
    // TTS van dang phat am thanh o nen lam dong ho dung han, du nguoi dung van dang "doc" (nghe)
    // sach binh thuong. Vi Service nay song doc lap voi Activity (xem kdoc dau file), no la noi
    // dung nhat de giu mot dong ho RIENG, chay theo dung trang thai phat/dung THAT SU cua TTS
    // (state.isPlaying) thay vi vong doi Activity. Dung MetadataDatabaseHelper + uuid + unit key
    // (chapterHref, bare - khong fragment, giong het bareHref ma EpubReaderActivity dung cho
    // readingTimeTracker cua no) nen 2 nguon cong don vao CUNG 1 "gau" thoi gian cho tung chuong,
    // hien thi dung trong man hinh "Thong ke". ---
    private val ttsDb by lazy { MetadataDatabaseHelper(applicationContext) }
    private var ttsReadingTimeTracker: ReadingTimeTracker? = null
    private var ttsReadingTimeTrackerUuid: String? = null

    /** Set boi EpubReaderActivity.onResume()/onPause() (va onServiceConnected() de dong bo trang
     * thai ngay luc bind) -- true nghia la Activity dang o foreground, luc do readingTimeTracker
     * CUA ACTIVITY da tu tinh thoi gian roi (nguoi dung dang thuc su nhin man hinh), nen dong ho
     * TTS o day phai TAM DUNG de tranh cong don 2 lan cho cung 1 khoang thoi gian khi nguoi dung
     * vua nhin sach vua nghe doc to. Mac dinh false (chua Activity nao bind) la dung, vi luc do
     * khong co gi de "nhuong" ca. */
    var readerActivityForeground: Boolean = false
        set(value) {
            field = value
            updateTtsReadingTimeTrackerRunState(ttsController.state.value.isPlaying)
        }

    /** Tao (hoac tao lai neu doi sang uuid khac) dong ho tinh gio nghe cho dung cuon dang phat,
     * roi cap nhat unit key hien tai theo chuong TTS dang doc. */
    private fun ensureTtsReadingTimeTracker(uuid: String): ReadingTimeTracker {
        val existing = ttsReadingTimeTracker
        if (existing != null && ttsReadingTimeTrackerUuid == uuid) return existing
        existing?.flush()
        val tracker = ReadingTimeTracker(
            db = ttsDb,
            uuid = uuid,
            dailyStats = DailyReadingStatsStore(ttsDb),
            // Cung mot toc do mac dinh "tu/ms" ma EpubReaderActivity dung cho readingTimeTracker
            // cua no (xem kdoc DEFAULT_MS_PER_WORD) -- giu nhat quan giua 2 nguon cong vao cung
            // mot so lieu "Toc do doc".
            wordsPerMsProvider = { 1.0 / EpubReadingStatsSource.DEFAULT_MS_PER_WORD },
        )
        ttsReadingTimeTracker = tracker
        ttsReadingTimeTrackerUuid = uuid
        return tracker
    }

    /** Bat/tat dong ho TTS dung theo dung 2 dieu kien: dang thuc su phat am thanh (khong phai chi
     * dang o man hinh full player nhung bam pause) VA khong co Activity nao dang foreground de
     * nhuong viec tinh gio lai cho readingTimeTracker cua Activity do. */
    private fun updateTtsReadingTimeTrackerRunState(isPlaying: Boolean) {
        val tracker = ttsReadingTimeTracker ?: return
        if (isPlaying && !readerActivityForeground) tracker.onResume() else tracker.onPause()
    }

    // --- Auto-sync tien trinh doc len cloud trong luc phien "Doc cho nghe" dang chay o nen -----
    // BUG DA SUA: truoc day auto-sync CHI song trong lifecycleScope cua EpubReaderActivity, nen
    // bi huy ngay khi Activity do onPause()/onStop() (vi du nguoi dung thoat ra Device Library
    // trong luc van dang nghe) va khong bao gio chay lai cho toi khi quay lai dung man hinh doc.
    // Vi Service nay song doc lap voi Activity (dung nhu kdoc o dau file), day moi la noi dung
    // de giu auto-sync chay dung chu ky da dat -- xem ReaderProgressAutoSyncLoop va prompt-codex-
    // fix-fab-va-autosync.md muc "Viec 2".

    /** Bat len moi khi progressStore.saveProgress() ghi 1 vi tri nghe MOI (xem onEach ben duoi)
     * -- y het vai tro cua hasUnsyncedProgress trong EpubReaderActivity, tranh enqueue job rong
     * moi tick khi chua co gi thay doi. */
    private var hasUnsyncedProgress = false
    private var progressSyncInProgress = false

    private fun firstSyncableRemoteOrNull(): CloudRemote? =
        CloudRemoteStore.getAll(applicationContext).firstOrNull { it.type.supportsDeviceLibrarySync() }

    /** Cung 1 co che voi EpubReaderActivity.syncProgressNow(): enqueue qua
     * DeviceLibrarySyncScheduler.enqueueProgressSync (hang doi rieng, khong bi xep sau full-sync
     * lon), roi cap nhat lastProgressSyncedAt/hasUnsyncedProgress khi WorkRequest hoan tat thanh
     * cong. Dung `this` (Service) lam LifecycleOwner cho observe() -- LifecycleService tu phat
     * cac su kien lifecycle tuong ung, nen observer tu go bo dung luc onDestroy(), khong leak. */
    private fun trySyncProgressNow() {
        if (progressSyncInProgress) return
        val uuid = nowPlayingUuid ?: return
        val remote = firstSyncableRemoteOrNull() ?: return
        progressSyncInProgress = true
        val workId = DeviceLibrarySyncScheduler.enqueueProgressSync(applicationContext, remote.id)
        androidx.work.WorkManager.getInstance(applicationContext)
            .getWorkInfoByIdLiveData(workId)
            .observe(this) { info ->
                if (info != null && info.state.isFinished) {
                    progressSyncInProgress = false
                    if (info.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                        hasUnsyncedProgress = false
                        ReaderSyncSettingsStore.setLastProgressSyncedAt(
                            applicationContext, uuid, System.currentTimeMillis(),
                        )
                    }
                }
            }
    }

    /** Dung chung logic dinh thoi gian voi EpubReaderActivity (xem ReaderProgressAutoSyncLoop) --
     * chi khac noi song: lifecycleScope cua Service thay vi cua Activity, nen khong bi huy khi
     * Activity roi foreground. */
    private val autoSyncLoop by lazy {
        ReaderProgressAutoSyncLoop(
            scope = lifecycleScope,
            getIntervalMinutes = { ReaderSyncSettingsStore.getAutoSyncIntervalMinutes(applicationContext) },
            isAvailable = { firstSyncableRemoteOrNull() != null },
            hasUnsyncedProgress = { hasUnsyncedProgress },
            isSyncInProgress = { progressSyncInProgress },
            trySync = { trySyncProgressNow() },
        )
    }

    override fun onCreate() {
        super.onCreate()
        val settingsStore = TtsSettingsStore(applicationContext)
        ttsController = TtsController(
            scope = lifecycleScope,
            settingsStore = settingsStore,
            onRequestNextChapter = { nextChapterProvider?.invoke() },
            dictionaryStore = TtsDictionaryStore(applicationContext),
            context = applicationContext,
        )
        musicController = BackgroundMusicController(
            context = applicationContext,
            settingsStore = BackgroundMusicSettingsStore(applicationContext),
        )

        mediaSession = MediaSessionCompat(this, "TtsPlaybackService")
        // Callback duy nhat noi MediaSession (khoa man hinh, tai nghe, Wear OS...) voi
        // TtsController -- truoc day mediaSession chi duoc tao va isActive = true, khong co
        // Callback/PlaybackState/Metadata nao duoc set nen he thong chi ve duoc 1 nut pause + 1
        // nut X (khong seekbar, khong next/prev) du buildNotification() co addAction gi di nua.
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = ttsController.resume()
            override fun onPause() = ttsController.pause()
            override fun onStop() = stopReadAloud()
            override fun onSkipToNext() = ttsController.skipToNextSentence()
            override fun onSkipToPrevious() = ttsController.skipToPreviousSentence()
            override fun onSeekTo(pos: Long) {
                // Anh xa vi tri "ao" (ms) nguoi dung vua keo toi ve dung chi so cau gan nhat.
                val durations = ttsController.state.value.sentenceDurationsMs
                var acc = 0L
                var targetIndex = durations.lastIndex.coerceAtLeast(0)
                for (i in durations.indices) {
                    acc += durations[i]
                    if (pos < acc) {
                        targetIndex = i
                        break
                    }
                }
                ttsController.seekToSentence(targetIndex)
            }
        })
        mediaSession.isActive = true
        notificationManager = NotificationManagerCompat.from(this)
        createNotificationChannelIfNeeded()

        ttsController.state
            .onEach { state ->
                // Luu tien trinh nghe theo tung chuong (TtsChapterProgressStore) -- doc lap voi
                // notification/mediaSession o duoi. Chay truoc de danh dau chuong truoc do "da
                // hoan thanh" ngay khi phat hien chuyen chuong (tu dong hoac nguoi dung chon
                // chuong khac), truoc khi ghi tien trinh cua chuong moi.
                if (state.sentenceCount > 0) {
                    if (!hasEverStartedPlaying) {
                        // Phien nghe THAT SU vua bat dau -- khoi dong vong lap auto-sync ngay tu
                        // day, khong doi Activity goi (Activity co the da bi onPause()/onStop()
                        // ngay sau khi nguoi dung bam "Doc cho nghe" roi thoat man hinh doc).
                        autoSyncLoop.start()
                    }
                    hasEverStartedPlaying = true
                    val uuid = nowPlayingUuid
                    if (uuid != null) {
                        val previousHref = lastTrackedHref
                        if (previousHref != null && previousHref != state.chapterHref) {
                            progressStore.markCompleted(uuid, previousHref, lastTrackedSentenceCount)
                        }
                        progressStore.saveProgress(uuid, state.chapterHref, state.sentenceIndex, state.sentenceCount)
                        lastTrackedHref = state.chapterHref
                        lastTrackedSentenceCount = state.sentenceCount
                        hasUnsyncedProgress = true
                        // Ghi thoi gian nghe la thoi gian doc (xem kdoc ttsReadingTimeTracker) --
                        // chuyen unit key TRUOC khi bat/tat dong ho, giong dung thu tu
                        // onUnitChanged()-roi-onResume() ma ReadingTimeTracker mong doi.
                        if (state.chapterHref.isNotEmpty()) {
                            ensureTtsReadingTimeTracker(uuid).onUnitChanged(state.chapterHref)
                        }
                    }
                }
                updateTtsReadingTimeTrackerRunState(state.isPlaying)
                updateNotification(state)
                // Duck/resume nhạc nền theo trạng thái phát -- xem BackgroundMusicController.
                musicController.onTtsPlayingChanged(state.isPlaying)
                // Chi coi la "phien doc da ket thuc" (va tu dong stopSelfSafely()) neu truoc do
                // da thuc su co it nhat 1 lan sentenceCount > 0 -- neu khong, gia tri idle() ban
                // dau cua StateFlow (isPlaying=false, sentenceCount=0) se bi hieu nham la "vua
                // ket thuc" ngay khi Service vua duoc tao, giai phong mediaSession truoc ca khi
                // nguoi dung bam phat (xem giai thich o khai bao hasEverStartedPlaying).
                if (hasEverStartedPlaying && !state.isPlaying && state.sentenceCount == 0) {
                    // Phiên đọc đã kết thúc hẳn (stop() được gọi) -- đánh dấu chương cuối cùng
                    // đang nghe là đã hoàn thành trước khi tự dừng service.
                    val uuid = nowPlayingUuid
                    val finishedHref = lastTrackedHref
                    if (uuid != null && finishedHref != null) {
                        progressStore.markCompleted(uuid, finishedHref, lastTrackedSentenceCount)
                    }
                    lastTrackedHref = null
                    hasEverStartedPlaying = false
                    ttsReadingTimeTracker?.flush()
                    stopSelfSafely()
                }
            }
            .launchIn(lifecycleScope)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopReadAloud()
            ACTION_TOGGLE_PLAY_PAUSE -> {
                if (ttsController.state.value.isPlaying) ttsController.pause() else ttsController.resume()
            }
            ACTION_SKIP_NEXT -> ttsController.skipToNextSentence()
            ACTION_SKIP_PREVIOUS -> ttsController.skipToPreviousSentence()
        }
        return START_NOT_STICKY
    }

    fun stopReadAloud() {
        ttsController.stop()
        musicController.stop()
        stopSelfSafely()
    }

    private fun stopSelfSafely() {
        // Dung han phien nghe -- khong con gi de auto-sync nua, tranh vong lap tiep tuc chay
        // (va leak coroutine) sau khi Service sap bi huy.
        autoSyncLoop.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        mediaSession.isActive = false
        // Go callback truoc khi release, tranh MediaSessionCompat goi vao callback cua mot
        // session da huy (vi du mot su kien onPlay/onSeekTo den tre tu he thong).
        mediaSession.setCallback(null)
        mediaSession.release()
        stopSelf()
    }

    override fun onDestroy() {
        // Safety net -- da goi tu stopSelfSafely() trong tinh huong binh thuong, nhung goi lai o
        // day de chac chan khong leak coroutine neu Service bi he thong huy truc tiep (bi kill vi
        // thieu bo nho...) ma khong qua stopSelfSafely().
        autoSyncLoop.stop()
        ttsReadingTimeTracker?.flush()
        ttsController.release()
        musicController.release()
        super.onDestroy()
    }

    /** Called once, right after `startChapter`/`resume` actually begins producing audio, to move
     * the service into the foreground -- must happen within a few seconds of
     * `startForegroundService` per Android 8+ requirements. Safe to call again on every state
     * update; NotificationManager simply updates the existing notification. */
    fun ensureForeground(state: TtsPlaybackState) {
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: TtsPlaybackState) {
        if (state.sentenceCount == 0 && !state.isPlaying) return // idle -- nothing to show
        updateMediaSession(state)
        ensureForeground(state)
    }

    /** Day PlaybackStateCompat + MediaMetadataCompat len [mediaSession] moi lan [TtsPlaybackState]
     * doi -- day la phan con thieu truoc day khien khoa man hinh chi ve duoc 1 nut pause + 1 nut
     * X: Android chi ve seekbar/next/prev day du khi MediaSession co PlaybackState voi day du
     * actions + position VA MediaMetadata co duration, du NotificationCompat.Builder co addAction
     * gi di nua. */
    private fun updateMediaSession(state: TtsPlaybackState) {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.chapterTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, nowPlayingBookTitle)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, nowPlayingCover)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.virtualDurationMs)
            .build()
        mediaSession.setMetadata(metadata)

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY
                    or PlaybackStateCompat.ACTION_PAUSE
                    or PlaybackStateCompat.ACTION_PLAY_PAUSE
                    or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    or PlaybackStateCompat.ACTION_SEEK_TO
                    or PlaybackStateCompat.ACTION_STOP,
            )
            .setState(
                if (state.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                state.virtualPositionMs,
                state.speechRate,
            )
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun buildNotification(state: TtsPlaybackState): Notification {
        val contentIntent = nowPlayingUuid?.let { uuid ->
            val openIntent = EpubReaderActivity.buildReopenIntent(
                this, uuid, nowPlayingBookPath.orEmpty(), nowPlayingBookTitle,
            )
            PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        fun serviceIntent(action: String, requestCode: Int) = PendingIntent.getService(
            this, requestCode,
            Intent(this, TtsPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val prevIntent = serviceIntent(ACTION_SKIP_PREVIOUS, 2)
        val playPauseIntent = serviceIntent(ACTION_TOGGLE_PLAY_PAUSE, 1)
        val nextIntent = serviceIntent(ACTION_SKIP_NEXT, 3)
        val stopIntent = serviceIntent(ACTION_STOP, 0)

        val playPauseIcon = if (state.isPlaying) android.R.drawable.ic_media_pause
        else android.R.drawable.ic_media_play

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reader_read_aloud)
            .setLargeIcon(nowPlayingCover)
            .setContentTitle(nowPlayingBookTitle)
            .setContentText(state.chapterTitle)
            .setContentIntent(contentIntent)
            .setOngoing(state.isPlaying)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_previous, getString(R.string.reader_tts_previous_sentence), prevIntent)
            .addAction(playPauseIcon, getString(R.string.reader_tts_play_pause), playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, getString(R.string.reader_tts_next_sentence), nextIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.reader_chrome_close), stopIntent)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    // 3 nut hien trong che do thu gon: lui - play/pause - next (giong anh mau).
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = android.app.NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.reader_tts_notification_channel_name),
                android.app.NotificationManager.IMPORTANCE_LOW,
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_STOP = "net.mrphan.calibre.tts.ACTION_STOP"
        const val ACTION_TOGGLE_PLAY_PAUSE = "net.mrphan.calibre.tts.ACTION_TOGGLE_PLAY_PAUSE"
        const val ACTION_SKIP_NEXT = "net.mrphan.calibre.tts.ACTION_SKIP_NEXT"
        const val ACTION_SKIP_PREVIOUS = "net.mrphan.calibre.tts.ACTION_SKIP_PREVIOUS"
        private const val NOTIFICATION_CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 42
    }
}
