package net.mrphan.calibre

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.StrictMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.mrphan.calibre.reader.tts.nghi.NghiModelImporter
import net.mrphan.calibre.reader.tts.nghi.NghiModelStore
import net.mrphan.calibre.reader.tts.nghe.NgheEpubModelImporter
import net.mrphan.calibre.reader.tts.nghe.NgheEpubModelStore

/** App-wide entry point. Currently only used to auto re-scan the NghiTTS models folder (if the
 * user has configured one) on every app launch, per Settings > "TTS Models" -- so a voice
 * dropped into the folder while the app was closed is picked up next time it's opened, without
 * requiring the user to remember to tap "Scan" manually. */
class CalibreSyncApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Cai dat cang som cang tot, TRUOC ca applyPersistedTheme(), de bat duoc bat ky crash nao
        // xay ra ngay trong nhung buoc khoi tao dau tien -- xem CrashLogHandler.kdoc de biet cach
        // lay file log ra sau khi app crash.
        CrashLogHandler.install(this)
        // NGAY SAU CrashLogHandler (de neu sau nay co ai nang penaltyDeath() len, crash van duoc
        // CrashLogHandler bat lai) va TRUOC applyPersistedTheme()/rescanNghiModelsIfConfigured()/
        // AppLibraryAutoSyncScheduler o duoi -- muc dich la bat luon ca cac vi pham xay ra trong
        // chinh cac buoc khoi tao nay (vd. applyPersistedTheme() doc SharedPreferences dong bo).
        enableStrictModeForDebug()
        AppThemeManager.applyPersistedTheme(this)
        rescanNghiModelsIfConfigured()
        rescanNgheEpubModelsIfConfigured()
        // Khoi dong lai chuoi auto-sync toan bo thu vien neu nguoi dung da bat tu truoc
        // (WorkManager tu luu job trong DB noi bo nen ve ly thuyet khong can goi lai o day,
        // nhung goi lai cho chac chan idempotent va tu phuc hoi neu vi ly do nao do chuoi da bi
        // huy/mat -- xem AppLibraryAutoSyncScheduler#applySettingsChanged.
        AppLibraryAutoSyncScheduler.applySettingsChanged(this)
    }

    /** Chi bat trong debug build (BuildConfig.DEBUG) -- KHONG BAO GIO bat o release, StrictMode
     * ton hieu nang va chi de phuc vu phat trien. Dung penaltyLog() (ghi canh bao vao Logcat,
     * KHONG lam crash app) thay vi penaltyDeath(), vi codebase hien tai co nhieu cho goi thang
     * SQLiteOpenHelper/File I/O tu tang UI (xem MetadataDatabaseHelper) ma chua kip audit het --
     * bat penaltyDeath() ngay se lam app khong dung duoc de test cac luong khac. Loc theo tag
     * "StrictMode" trong Logcat de xem danh sach cac vi pham threadPolicy/vmPolicy hien co; sau
     * khi don dep dan cac vi pham nghiem trong, co the nang thanh penaltyDeath() cho rieng
     * threadPolicy() de bat buoc phat hien som trong luc phat trien tiep. */
    private fun enableStrictModeForDebug() {
        if (!BuildConfig.DEBUG) return

        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build(),
        )

        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .apply {
                    // detectFileUriExposure() chi ton tai tu API 24 (Build.VERSION_CODES.N) tro
                    // len, dung voi minSdk 24 cua project nay nen luc nao cung true, nhung van
                    // check tuong minh cho ro rang y dinh thay vi dua vao minSdk co the doi sau.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        detectFileUriExposure()
                    }
                }
                .penaltyLog()
                .build(),
        )
    }

    private fun rescanNghiModelsIfConfigured() {
        val store = NghiModelStore(this)
        val uriString = store.getModelDirUri() ?: return
        appScope.launch {
            try {
                NghiModelImporter.scanAndImport(applicationContext, Uri.parse(uriString))
            } catch (e: Exception) {
                Logger.e(e)
            }
        }
    }

    private fun rescanNgheEpubModelsIfConfigured() {
        val store = NgheEpubModelStore(this)
        val uriString = store.getModelDirUri() ?: return
        appScope.launch {
            try {
                NgheEpubModelImporter.scanAndImport(applicationContext, Uri.parse(uriString))
            } catch (e: Exception) {
                Logger.e(e)
            }
        }
    }
}
