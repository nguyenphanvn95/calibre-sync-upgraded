package net.mrphan.calibre.reader.tts.cache

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.mrphan.calibre.Logger
import net.mrphan.calibre.reader.tts.TtsSettingsStore
import java.io.File
import java.security.MessageDigest

/**
 * Cache audio TTS da tong hop, luu ben vung tren dia (song qua lan mo app tiep theo), khac
 * voi `prefetchCache` trong tung TtsEngine (chi la RAM, mat khi shutdown()/app bi kill).
 *
 * Muc dich: tranh tong hop lai (round-trip mang cho Edge TTS, suy luan ONNX cho NghiTTS) khi
 * nguoi dung tua lui, mo lai chuong da nghe, hoac mo lai sach o phien sau -- xem Muc 2 cua
 * prompt-nang-cap-tts-audio-cache.md de biet boi canh day du.
 *
 * KHONG phai mot "Readest feature" duoc port sang -- day la thiet ke moi rieng cho app nay
 * (xem ghi chu dau file prompt ve viec Readest thuc ra khong co cache dia ben vung).
 *
 * Thread-safety: moi thao tac ghi (put*, evictIfNeeded, clear) deu qua [writeMutex]. Doc (get*)
 * khong can khoa file/manifest chinh, nhung viec cap nhat last_access_ms sau khi doc van di
 * qua duong rieng (touchLastAccess) de tranh ghi chong khi nhieu engine truy cap gan nhu dong
 * thoi -- xem ghi chu trong ham do ve vi sao khong can chinh writeMutex cho truong hop nay.
 */
class TtsAudioCache private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.cacheDir, "tts_audio_cache").apply { mkdirs() }
    private val dbHelper = ManifestDbHelper(appContext)
    private val writeMutex = Mutex()

    /** Ket qua doc cache PCM: mau [samples] + [sampleRate] goc luc tong hop -- dung de boc lai
     * thanh GeneratedAudio-tuong-thich trong NghiTtsEngine. */
    data class PcmEntry(val samples: FloatArray, val sampleRate: Int)

    private fun hashKey(cacheKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cacheKey.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Doc mp3/audio-bytes tho da cache cho [cacheKey], hoac null neu chua co / da bi evict. */
    suspend fun get(cacheKey: String): ByteArray? = withContext(Dispatchers.IO) {
        val hash = hashKey(cacheKey)
        val file = File(cacheDir, "$hash.bin")
        if (!file.exists()) return@withContext null
        try {
            val bytes = file.readBytes()
            touchLastAccess(hash)
            bytes
        } catch (e: Exception) {
            Logger.e(e)
            null
        }
    }

    suspend fun put(cacheKey: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                val hash = hashKey(cacheKey)
                val file = File(cacheDir, "$hash.bin")
                file.writeBytes(bytes)
                upsertManifestEntry(hash, file.length(), sampleRate = -1)
                evictIfNeededLocked()
            } catch (e: Exception) {
                Logger.e(e)
            }
        }
    }

    /** Bien the cho PCM float (NghiTTS): luu [samples] duoi dang raw bytes (4 byte/sample,
     * little-endian) kem [sampleRate] rieng trong manifest de doc lai dung. */
    suspend fun putPcm(cacheKey: String, samples: FloatArray, sampleRate: Int) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                val hash = hashKey(cacheKey)
                val file = File(cacheDir, "$hash.bin")
                val buffer = java.nio.ByteBuffer.allocate(samples.size * 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                samples.forEach { buffer.putFloat(it) }
                file.writeBytes(buffer.array())
                upsertManifestEntry(hash, file.length(), sampleRate)
                evictIfNeededLocked()
            } catch (e: Exception) {
                Logger.e(e)
            }
        }
    }

    suspend fun getPcm(cacheKey: String): PcmEntry? = withContext(Dispatchers.IO) {
        val hash = hashKey(cacheKey)
        val file = File(cacheDir, "$hash.bin")
        if (!file.exists()) return@withContext null
        val sampleRate = readSampleRate(hash) ?: return@withContext null
        try {
            val bytes = file.readBytes()
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val samples = FloatArray(bytes.size / 4) { buffer.float }
            touchLastAccess(hash)
            PcmEntry(samples, sampleRate)
        } catch (e: Exception) {
            Logger.e(e)
            null
        }
    }

    /** Tong dung luong cache hien tai (byte) -- dung de hien thi trong UI Settings neu can. */
    suspend fun currentSizeBytes(): Long = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.rawQuery(
            "SELECT COALESCE(SUM($COL_SIZE), 0) FROM $TABLE_ENTRIES", null,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    /** Xoa toan bo cache -- dung cho nut "Xoá bộ nhớ đệm" trong Settings. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                cacheDir.listFiles()?.forEach { it.delete() }
                dbHelper.writableDatabase.delete(TABLE_ENTRIES, null, null)
            } catch (e: Exception) {
                Logger.e(e)
            }
        }
    }

    private fun upsertManifestEntry(hash: String, sizeBytes: Long, sampleRate: Int) {
        val values = ContentValues().apply {
            put(COL_KEY, hash)
            put(COL_SIZE, sizeBytes)
            put(COL_LAST_ACCESS, System.currentTimeMillis())
            put(COL_SAMPLE_RATE, sampleRate)
        }
        dbHelper.writableDatabase.insertWithOnConflict(
            TABLE_ENTRIES, null, values, SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun touchLastAccess(hash: String) {
        // Cap nhat last_access_ms ngay ca khi doc (khong qua writeMutex de khong chan cac
        // luot get() song song vi ly do hiem gap -- neu 2 lan touch chen nhau, ket qua toi da
        // la 1 timestamp bi ghi de boi 1 timestamp gan giong het, khong anh huong tinh dung
        // dan cua LRU vi ca 2 deu la "vua duoc truy cap").
        try {
            val values = ContentValues().apply { put(COL_LAST_ACCESS, System.currentTimeMillis()) }
            dbHelper.writableDatabase.update(TABLE_ENTRIES, values, "$COL_KEY = ?", arrayOf(hash))
        } catch (e: Exception) {
            Logger.e(e)
        }
    }

    private fun readSampleRate(hash: String): Int? {
        dbHelper.readableDatabase.query(
            TABLE_ENTRIES, arrayOf(COL_SAMPLE_RATE), "$COL_KEY = ?", arrayOf(hash),
            null, null, null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val rate = cursor.getInt(0)
            return if (rate > 0) rate else null
        }
    }

    /** Phai duoc goi ben trong [writeMutex] -- xoa dan cac entry co last_access_ms nho nhat
     * cho toi khi tong dung luong duoi han cau hinh (TtsSettingsStore.getCacheStorageLimitMb).
     * Doc han moi lan goi (khong cache gia tri) de phan anh dung ngay khi nguoi dung doi
     * Storage Limit trong Settings. */
    private fun evictIfNeededLocked() {
        val limitBytes = TtsSettingsStore(appContext).getCacheStorageLimitMb() * 1024L * 1024L
        val db = dbHelper.writableDatabase
        var totalBytes = db.rawQuery(
            "SELECT COALESCE(SUM($COL_SIZE), 0) FROM $TABLE_ENTRIES", null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        if (totalBytes <= limitBytes) return

        db.query(
            TABLE_ENTRIES, arrayOf(COL_KEY, COL_SIZE), null, null, null, null,
            "$COL_LAST_ACCESS ASC",
        ).use { cursor ->
            while (totalBytes > limitBytes && cursor.moveToNext()) {
                val hash = cursor.getString(0)
                val size = cursor.getLong(1)
                try { File(cacheDir, "$hash.bin").delete() } catch (_: Exception) {}
                db.delete(TABLE_ENTRIES, "$COL_KEY = ?", arrayOf(hash))
                totalBytes -= size
            }
        }
    }

    private class ManifestDbHelper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE_ENTRIES (" +
                    "$COL_KEY TEXT PRIMARY KEY, " +
                    "$COL_SIZE INTEGER NOT NULL, " +
                    "$COL_LAST_ACCESS INTEGER NOT NULL, " +
                    "$COL_SAMPLE_RATE INTEGER NOT NULL DEFAULT -1)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_ENTRIES")
            onCreate(db)
        }
    }

    companion object {
        private const val DB_NAME = "tts_audio_cache_manifest.db"
        private const val DB_VERSION = 1
        private const val TABLE_ENTRIES = "entries"
        private const val COL_KEY = "cache_key"
        private const val COL_SIZE = "size_bytes"
        private const val COL_LAST_ACCESS = "last_access_ms"
        private const val COL_SAMPLE_RATE = "sample_rate"

        @Volatile private var instance: TtsAudioCache? = null

        fun getInstance(context: Context): TtsAudioCache =
            instance ?: synchronized(this) {
                instance ?: TtsAudioCache(context.applicationContext).also { instance = it }
            }
    }
}
