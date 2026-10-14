package net.mrphan.calibre.reader.chrome

import android.content.Context

/**
 * Preferences rieng cho tinh nang Sync trong Reader (khac SharedPreferences
 * mac dinh cua app de tranh dam key voi cac man hinh khac) -- xem
 * prompt-nang-cap-reader-sync.md. Khong dung DB (khong can migration schema
 * cho 1 tinh nang co the thay doi nhanh): [lastProgressSyncedAt] khoa theo
 * UUID sach (moi sach 1 moc thoi gian rieng), [autoSyncIntervalMinutes] la
 * cai dat toan cuc (ap dung cho moi sach).
 */
object ReaderSyncSettingsStore {
    private const val PREFS_NAME = "reader_sync_settings"
    private const val KEY_AUTO_SYNC_INTERVAL_MINUTES = "auto_sync_interval_minutes"
    private const val LAST_SYNCED_PREFIX = "last_synced_"

    /** Mac dinh yeu cau: 3 phut. 0 = tat auto-sync. */
    const val DEFAULT_INTERVAL_MINUTES = 3

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAutoSyncIntervalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_AUTO_SYNC_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)

    fun setAutoSyncIntervalMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_AUTO_SYNC_INTERVAL_MINUTES, minutes).apply()
    }

    fun getLastProgressSyncedAt(context: Context, uuid: String): Long? {
        val v = prefs(context).getLong(LAST_SYNCED_PREFIX + uuid, -1L)
        return if (v < 0) null else v
    }

    fun setLastProgressSyncedAt(context: Context, uuid: String, atMillis: Long) {
        prefs(context).edit().putLong(LAST_SYNCED_PREFIX + uuid, atMillis).apply()
    }
}
