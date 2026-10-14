package net.mrphan.calibre.reader.lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Mot OkHttpClient dung chung cho toan bo tinh nang Tu dien/Dich (khong dung chung voi client
 * cua GDrive/WebDAV de tranh anh huong timeout/interceptor rieng cua cac luong tai file). Timeout
 * ngan (8s) vi day la thao tac tuong tac truc tiep -- nguoi dung dang mo bottom sheet cho ket qua
 * hien ngay -- khac voi tai sach ve co the cho lau hon.
 */
class LookupHttpClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * Chay tren [Dispatchers.IO], tra ve body dang String neu response 2xx, nem [IOException]
     * (kem ma HTTP trong message) neu khong. Cac provider trong package nay goi ham nay thay vi
     * tu quan ly OkHttpClient/luong rieng.
     */
    suspend fun executeForBody(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for ${request.url}")
            }
            response.body?.string() ?: throw IOException("empty body for ${request.url}")
        }
    }
}
