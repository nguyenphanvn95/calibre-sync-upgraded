package net.mrphan.calibre.reader.chrome

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Vong lap dinh ky day tien trinh doc len cloud, tach ra tu logic goc trong
 * EpubReaderActivity.startAutoSyncLoopIfEnabled() de dung chung duoc voi TtsPlaybackService --
 * xem prompt-codex-fix-fab-va-autosync.md muc "Viec 2". Ly do can dung chung: khi nguoi dung bam
 * "Doc cho nghe" roi thoat ra Device Library, EpubReaderActivity bi onPause()/onStop() va vong
 * lap cu (song trong lifecycleScope cua Activity) bi huy ngay, khong bao gio chay lai cho toi khi
 * nguoi dung quay lai dung man hinh doc do -- du TtsPlaybackService (song doc lap voi Activity)
 * van dang tiep tuc phat am thanh va cap nhat tien trinh nghe trong suot thoi gian do.
 *
 * Class nay CHI chua logic dinh thoi gian (KHONG tu quan ly bat/tat theo lifecycle Activity/
 * Service) -- ben goi (EpubReaderActivity hoac TtsPlaybackService) tu chiu trach nhiem goi
 * [start]/[stop] dung luc theo lifecycle cua chinh no. Giu nguyen ca 2 co che chong bug tung
 * duoc ghi chu ky trong ban goc:
 *  1) [nextDueAtMillis] la moc thoi gian TUYET DOI (SystemClock.elapsedRealtime), KHONG reset ve
 *     0 moi lan [start] duoc goi lai (tru khi resetSchedule=true) -- tranh bug dong ho bi "khoi
 *     dong lai tu dau" moi lan Activity pause/resume lam auto-sync khong bao gio du chu ky.
 *  2) khi 1 tick bi bo qua (chua co gi de dong bo, hoac dang co lan sync khac chay), chi doi them
 *     [recheckIntervalMs] ngan de kiem tra lai, KHONG doi nguyen 1 chu ky moi -- tranh khoang
 *     cach thuc te giua 2 lan dong bo THAT SU bi keo dai gap 2-3 lan chu ky da chon.
 *
 * @param scope CoroutineScope song cung voi noi goi (lifecycleScope cua Activity hoac Service).
 * @param getIntervalMinutes tra ve chu ky auto-sync hien tai (phut, doc lai moi lan [start] de
 * luon bam sat setting moi nhat); <= 0 nghia la tat auto-sync.
 * @param isAvailable co it nhat 1 cloud remote ho tro dong bo thu vien thiet bi hay khong.
 * @param hasUnsyncedProgress co tien trinh moi chua duoc day len cloud tu lan sync gan nhat.
 * @param isSyncInProgress dang co 1 lan sync khac chay (tranh enqueue WorkRequest chong nhau).
 * @param trySync goi 1 lan dong bo thuc su (enqueueProgressSync + cap nhat
 * lastProgressSyncedAt/hasUnsyncedProgress khi xong) -- y het syncProgressNow() cu.
 */
class ReaderProgressAutoSyncLoop(
    private val scope: CoroutineScope,
    private val getIntervalMinutes: () -> Int,
    private val isAvailable: () -> Boolean,
    private val hasUnsyncedProgress: () -> Boolean,
    private val isSyncInProgress: () -> Boolean,
    private val trySync: () -> Unit,
) {
    private var job: Job? = null
    private var nextDueAtMillis: Long = 0L

    /** Khoang thoi gian doi lai khi 1 tick bi bo qua -- 15s la du ngan de khong lam nguoi dung
     * cam nhan do tre, nhung du dai de khong busy-loop. */
    private val recheckIntervalMs = 15_000L

    /** Bat dau (hoac khoi dong lai) vong lap. [resetSchedule]=true khi nguoi dung chu dong doi
     * chu ky trong Settings (ap dung ngay lap tuc); false cho cac lan goi lai chi de "gan lai"
     * vong lap (vd Activity resume, Service bat dau phien nghe moi) -- giu nguyen moc da tinh
     * truoc do, khong reset dong ho ve dau. */
    fun start(resetSchedule: Boolean = false) {
        job?.cancel()
        val minutes = getIntervalMinutes()
        if (minutes <= 0 || !isAvailable()) {
            nextDueAtMillis = 0L
            return
        }
        val intervalMs = minutes * 60_000L
        val now = android.os.SystemClock.elapsedRealtime()
        if (resetSchedule || nextDueAtMillis <= now) {
            nextDueAtMillis = now + intervalMs
        }
        job = scope.launch {
            while (isActive) {
                val remainingMs = (nextDueAtMillis - android.os.SystemClock.elapsedRealtime())
                    .coerceAtLeast(0L)
                delay(remainingMs)
                if (hasUnsyncedProgress() && !isSyncInProgress()) {
                    trySync()
                    nextDueAtMillis = android.os.SystemClock.elapsedRealtime() + intervalMs
                } else {
                    nextDueAtMillis = android.os.SystemClock.elapsedRealtime() +
                        minOf(intervalMs, recheckIntervalMs)
                }
            }
        }
    }

    /** Huy vong lap. Goi tu Activity#onPause() / Service#onDestroy() / khi dung han phien nghe --
     * an toan goi nhieu lan. */
    fun stop() {
        job?.cancel()
        job = null
    }
}
