package net.mrphan.calibre

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import kotlin.coroutines.coroutineContext

/**
 * Chuyen tu AsyncTask sang suspend function o lan refactor bo AsyncTask (xem upgrade_1.md muc 3
 * -- danh gia "khong gan lifecycle nen de leak Activity khi xoay man hinh giua luc dang chay, va
 * AsyncTask dung chung 1 thread pool tuan tu"). Logic tai file/parse header giu nguyen 1-1 so
 * voi ban Java cu; chi doi co che chay nen tu AsyncTask sang mot coroutine do caller tu quan ly
 * (thuong la `lifecycleScope`), va them ensureActive() moi chunk doc de cancel() thuc su dung
 * viec tai giua chung thay vi chi bo qua callback hoan tat nhu ban cu.
 */
object AsyncDownloader {

    // RFC 6266 "filename*=<charset>'<lang>'<percent-encoded-name>" (RFC 5987 encoded-word).
    // Calibre's content server uses this form whenever the book's title/author contains
    // non-ASCII characters (e.g. Vietnamese diacritics), alongside a plain ASCII "filename="
    // fallback. It takes priority over the plain form when present.
    private val FILENAME_STAR: Pattern = Pattern.compile(
        "filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", Pattern.CASE_INSENSITIVE
    )

    // Plain RFC 6266 "filename=..." (optionally quoted), used as a fallback.
    private val FILENAME_PLAIN: Pattern = Pattern.compile(
        "filename\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE
    )

    /**
     * Extracts and decodes the target filename from a Content-Disposition header value, e.g.
     * "attachment; filename=\"fallback.epub\"; filename*=UTF-8''The%20Second%20World%20War%20-%20Antony%20Beevor_132h.epub"
     * Prefers the filename* (percent-encoded, correctly represents Unicode titles) over the
     * plain filename parameter, and percent-decodes it using the charset it declares (falling
     * back to UTF-8). Returns null if the header has no filename parameter at all.
     */
    fun parseContentDispositionFilename(contentDisposition: String?): String? {
        if (contentDisposition == null) return null
        val star = FILENAME_STAR.matcher(contentDisposition)
        if (star.find()) {
            val charset = star.group(1)
            val encoded = star.group(2).trim()
            try {
                return java.net.URLDecoder.decode(encoded, if (charset.isNullOrEmpty()) "UTF-8" else charset)
            } catch (e: Exception) {
                // Unsupported/garbled charset name -- fall through to the plain filename below
                // rather than surfacing the raw percent-encoded string as the on-disk name.
            }
        }
        val plain = FILENAME_PLAIN.matcher(contentDisposition)
        if (plain.find()) {
            return plain.group(1).trim()
        }
        return null
    }

    /** Strips any directory components and filesystem-illegal characters from a server-supplied
     * filename before it's used to build a local File -- the value comes straight out of a
     * response header, so it shouldn't be trusted as a safe path fragment as-is. */
    fun sanitizeFilename(name: String?): String {
        if (name == null) return "tmp"
        var base = name.replace('\\', '/')
        val slash = base.lastIndexOf('/')
        if (slash >= 0) base = base.substring(slash + 1)
        base = base.replace(Regex("[\\x00-\\x1f\"*/:<>?\\\\|]"), "_").trim()
        return base.ifEmpty { "tmp" }
    }

    /** Outcome of a [download] call once it completes normally (i.e. wasn't cancelled --
     * cancellation propagates as [CancellationException] instead, same as any other suspend
     * function, rather than being reported as a [Result] value). */
    sealed class Result {
        data class Success(val outputPath: String) : Result()
        /** Covers every non-cancellation failure the old AsyncTask reported as a null
         * doInBackground() return -- bad/expired URL, network error, non-2xx/401 HTTP status. */
        object Failure : Result()
    }

    /**
     * Downloads [url] into [outputDir], returning [Result.Success] with the sanitized local
     * filename, or [Result.Failure]. [authorizationHeader] is the optional "Basic <base64>"
     * value used for a Content Server with Basic Auth enabled (see README "OPDS Basic Auth");
     * null/empty for the plain Smart Device App download path.
     *
     * Meant to be called from a coroutine tied to caller lifecycle (e.g.
     * `lifecycleScope.launch { ... }`) -- cancelling that job stops the transfer (checked via
     * ensureActive() after every chunk) and deletes the partial file, instead of requiring the
     * caller to poll `AsyncTask.Status`/call `cancel(true)` and hope the background thread
     * notices.
     *
     * [onProgress] fires on [Dispatchers.Main] with 0..100 whenever the server reports a
     * Content-Length (never, if it doesn't -- see the bug-fix note inline below), matching
     * AsyncTask's publishProgress()/onProgressUpdate() contract exactly so callers can keep
     * driving a ProgressDialog directly from it.
     */
    suspend fun download(
        url: String,
        outputDir: String,
        authorizationHeader: String?,
        onProgress: (Int) -> Unit
    ): Result {
        var oFile: File? = null
        return try {
            withContext(Dispatchers.IO) {
                val connection = URL(url).openConnection()
                if (!authorizationHeader.isNullOrEmpty()) {
                    connection.setRequestProperty("Authorization", authorizationHeader)
                }
                connection.connect()
                if (connection is HttpURLConnection && connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    // Content Server requires (or was given wrong) credentials. Fail cleanly
                    // instead of trying to parse an HTML/error body as a book file.
                    return@withContext Result.Failure
                }
                // this will be useful so that you can show a typical 0-100% progress bar
                val fileLength = connection.contentLength
                val raw = connection.getHeaderField("Content-Disposition")
                // raw = "attachment; filename=abc.jpg" or, for a title/author with non-ASCII
                // characters, "attachment; filename=\"...\"; filename*=UTF-8''...%20...\""
                val parsedName = parseContentDispositionFilename(raw)
                val outputPath = sanitizeFilename(parsedName)
                val file = File(outputDir, outputPath)
                oFile = file

                BufferedInputStream(connection.getInputStream()).use { input ->
                    FileOutputStream(file).use { output ->
                        val data = ByteArray(1024)
                        var total = 0L
                        while (true) {
                            // AsyncTask ban cu KHONG check isCancelled() trong vong lap nay --
                            // cancel(true) tu OpenBookActivity.onStop() chi ngan onPostExecute()
                            // chay, con viec tai vAn tiep tuc am tham cho toi khi xong/loi.
                            // ensureActive() o day khien viec huy job dung tai that su giua
                            // chung thay vi chi bo qua callback hoan tat.
                            coroutineContext.ensureActive()
                            val count = input.read(data)
                            if (count == -1) break
                            total += count
                            // BUG FIX: fileLength co the la 0 hoac am (server khong tra
                            // Content-Length -- vi du GDriveOpdsHttpServer chuyen sang chunked
                            // response khi Google Drive khong cho biet truoc dung luong file).
                            // "total * 100 / fileLength" khi do nem ArithmeticException (chia
                            // cho 0) hoac ra so phan tram vo nghia. Chi bao tien do khi biet
                            // chac dung luong that.
                            if (fileLength > 0) {
                                val percent = (total * 100 / fileLength).toInt()
                                withContext(Dispatchers.Main) { onProgress(percent) }
                            }
                            output.write(data, 0, count)
                        }
                    }
                }
                Result.Success(outputPath)
            }
        } catch (e: CancellationException) {
            oFile?.delete()
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            oFile?.delete()
            Result.Failure
        }
    }
}
