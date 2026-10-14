package net.mrphan.calibre.reader.print

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import net.mrphan.calibre.Logger

/**
 * Render 1 chuoi HTML tu chua (xem ChapterHtmlBuilder) trong 1 WebView offscreen, roi dua cho
 * PrintManager cua he thong -- tan dung "Save as PDF" co san lam printer ao (thay vi tu viet
 * PdfDocument thu cong), vua xuat duoc file PDF vao bo nho may vua in duoc len may in that
 * bang CUNG mot code path.
 *
 * QUAN TRONG VE VONG DOI: WebView phai duoc GIU THAM CHIEU MANH cho den khi PrintManager
 * xong viec (qua trinh layout/write cua PrintDocumentAdapter chay bat dong bo, co the sau khi
 * ham print() nay da return) -- neu de WebView bi garbage-collected giua chung, PDF se rong/loi.
 * Ham nay nhan vao 1 `onPrinterStarted: (WebView) -> Unit` de noi goi giu WebView do lam field
 * cua activity (vi du EpubReaderActivity.chapterPrintWebView) cho toi khi Activity bi huy.
 */
object ChapterPdfPrinter {

    fun print(context: Context, html: String, jobName: String, onWebViewCreated: (WebView) -> Unit) {
        val webView = WebView(context)
        webView.setBackgroundColor(Color.WHITE)
        webView.settings.defaultTextEncodingName = "utf-8"
        attachForStablePrintLayout(context, webView)
        onWebViewCreated(webView)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                view.postVisualStateCallback(
                    System.nanoTime(),
                    object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            view.postDelayed({
                                logRenderedDomSize(view)
                                startSystemPrint(context, view, jobName)
                            }, PRINT_RENDER_DELAY_MS)
                        }
                    },
                )
            }
        }
        webView.loadDataWithBaseURL(PRINT_BASE_URL, html, "text/html", "UTF-8", null)
    }

    private fun attachForStablePrintLayout(context: Context, webView: WebView) {
        val decor = (context as? Activity)?.window?.decorView as? ViewGroup ?: return
        webView.visibility = View.VISIBLE
        webView.translationX = OFFSCREEN_TRANSLATION_X
        decor.addView(
            webView,
            ViewGroup.LayoutParams(PRINT_VIEW_WIDTH_PX, PRINT_VIEW_MIN_HEIGHT_PX),
        )
    }

    private fun measureForPrint(webView: WebView) {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(PRINT_VIEW_WIDTH_PX, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        webView.measure(widthSpec, heightSpec)
        val height = webView.measuredHeight.coerceAtLeast(PRINT_VIEW_MIN_HEIGHT_PX)
        webView.layout(0, 0, PRINT_VIEW_WIDTH_PX, height)
    }

    private fun logRenderedDomSize(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function() {
              var body = document.body;
              var root = document.documentElement;
              if (!body || !root) return 'no-body';
              return [
                body.innerText ? body.innerText.length : 0,
                body.innerHTML ? body.innerHTML.length : 0,
                Math.max(body.scrollHeight || 0, root.scrollHeight || 0)
              ].join(':');
            })();
            """.trimIndent(),
        ) { result ->
            Logger.d("ChapterPdfPrinter DOM text:html:height = $result")
        }
    }

    private fun startSystemPrint(context: Context, webView: WebView, jobName: String) {
        measureForPrint(webView)
        val printManager = ContextCompat.getSystemService(context, PrintManager::class.java) ?: return
        val adapter = webView.createPrintDocumentAdapter(jobName)
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("chapter_pdf", "Chapter PDF", 300, 300))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        printManager.print(jobName, adapter, attributes)
    }

    private const val PRINT_BASE_URL = "https://calibre-sync.local/print/"
    private const val PRINT_VIEW_WIDTH_PX = 1080
    private const val PRINT_VIEW_MIN_HEIGHT_PX = 1600
    private const val PRINT_RENDER_DELAY_MS = 900L
    private const val OFFSCREEN_TRANSLATION_X = -20000f
}
