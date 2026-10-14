package net.mrphan.calibre.reader.lookup

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import net.mrphan.calibre.Logger
import net.mrphan.calibre.MetadataDatabaseHelper
import net.mrphan.calibre.R
import net.mrphan.calibre.reader.stats.DailyReadingStatsStore
import java.net.URLEncoder

/**
 * Bottom sheet dung chung cho ca Tu dien va Dich -- hien loading -> ket qua hoac loi, co nut
 * "Thu lai" va nut "Mo trinh duyet" (fallback mo trang Wiktionary/Google Translate tren web khi
 * ca provider chinh lan fallback deu that bai, thay vi de nguoi dung bi tac hoan toan).
 *
 * Dung `newInstance`/arguments Bundle (khong phai field thuong nhu ban nhap dau tien cua prompt
 * nay) vi Fragment co the bi he thong tai tao lai sau khi process bi kill (vd xoay man hinh nang,
 * ve bo sau lau) qua constructor khong tham so -- luc do moi field thuong duoc gan bang code o
 * `forDictionary`/`forTranslate` se mat, chi con lai arguments Bundle (duoc framework tu luu/khoi
 * phuc) la dang tin cay.
 *
 * **Chua build/run-verified** (khong co Android SDK/mang toi maven.google.com trong sandbox viet
 * file nay, cung han che nhu cac file khac cua project) -- can kiem tra tren thiet bi that: chieu
 * cao bottom sheet voi ket qua tu dien dai nhieu nghia (da dat `maxHeight` + `NestedScrollView`
 * trong `sheet_reader_lookup.xml` nhung chua thay tan mat tren man hinh nho).
 */
class LookupBottomSheetFragment : BottomSheetDialogFragment() {

    private lateinit var titleView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var contentContainer: android.widget.LinearLayout
    private lateinit var errorActionsRow: View

    private val mode: Mode by lazy {
        when (requireArguments().getString(ARG_MODE)) {
            MODE_TRANSLATE -> Mode.Translate(requireArguments().getString(ARG_PAYLOAD).orEmpty())
            else -> Mode.Dictionary(requireArguments().getString(ARG_PAYLOAD).orEmpty())
        }
    }

    private sealed class Mode {
        data class Dictionary(val word: String) : Mode()
        data class Translate(val text: String) : Mode()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.sheet_reader_lookup, container, false)
        titleView = root.findViewById(R.id.lookup_title)
        progressBar = root.findViewById(R.id.lookup_progress)
        contentContainer = root.findViewById(R.id.lookup_content_container)
        errorActionsRow = root.findViewById(R.id.lookup_error_actions)
        root.findViewById<View>(R.id.lookup_retry_button).setOnClickListener { runLookup() }
        root.findViewById<View>(R.id.lookup_open_browser_button).setOnClickListener { openInBrowser() }

        titleView.text = when (val m = mode) {
            is Mode.Dictionary -> m.word
            is Mode.Translate -> getString(R.string.lookup_translate_title)
        }
        runLookup()
        return root
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)
            ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.background = ColorDrawable(Color.TRANSPARENT)
    }

    private fun runLookup() {
        showLoading()
        val settings = ReaderLookupSettingsStore(requireContext())
        val httpClient = LookupHttpClient()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                when (val m = mode) {
                    is Mode.Dictionary -> showDictionaryResult(runDictionaryLookup(m.word, httpClient))
                    is Mode.Translate -> showTranslationResult(runTranslation(m.text, settings, httpClient))
                }
            } catch (e: Exception) {
                Logger.d("LookupBottomSheetFragment: lookup failed, showing error")
                Logger.e(e)
                showError()
            }
        }
    }

    private suspend fun runDictionaryLookup(word: String, httpClient: LookupHttpClient): DictionaryResult {
        val primary = FreeDictionaryProvider(httpClient)
        val fallback = WiktionaryProvider(httpClient)
        val uiLangHint = resources.configuration.locales[0].language
        return try {
            primary.lookup(word, uiLangHint)
        } catch (e: Exception) {
            Logger.d("LookupBottomSheetFragment: dictionary primary provider failed (${e.message}), trying fallback")
            fallback.lookup(word, uiLangHint)
        }
    }

    private suspend fun runTranslation(
        text: String,
        settings: ReaderLookupSettingsStore,
        httpClient: LookupHttpClient,
    ): TranslationResult {
        val target = settings.getTargetLanguage()
        val wantsDeepL = settings.getTranslationProviderId() == "deepl" && settings.getDeepLApiKey() != null
        val provider: TranslationProvider = if (wantsDeepL) {
            DeepLProvider(httpClient) { settings.getDeepLApiKey() }
        } else {
            GoogleTranslateFreeProvider(httpClient)
        }
        return try {
            provider.translate(text, target)
        } catch (e: Exception) {
            if (provider.id == "google_free") throw e
            Logger.d("LookupBottomSheetFragment: translation provider '${provider.id}' failed (${e.message}), trying free fallback")
            GoogleTranslateFreeProvider(httpClient).translate(text, target)
        }
    }

    private fun showLoading() {
        progressBar.visibility = View.VISIBLE
        errorActionsRow.visibility = View.GONE
        contentContainer.removeAllViews()
    }

    private fun showDictionaryResult(result: DictionaryResult) {
        progressBar.visibility = View.GONE
        errorActionsRow.visibility = View.GONE
        contentContainer.removeAllViews()
        result.phonetic?.let { addTextRow(it, sizeSp = 14f, italic = true) }
        for (meaning in result.meanings) {
            meaning.partOfSpeech?.let { addTextRow(it, sizeSp = 13f, bold = true, topMarginDp = 8) }
            for (def in meaning.definitions) addTextRow("• $def", sizeSp = 15f, topMarginDp = 4)
        }
        addTextRow(getString(R.string.lookup_source_label, result.sourceName), sizeSp = 12f, topMarginDp = 12, alpha = 0.6f)
    }

    private fun showTranslationResult(result: TranslationResult) {
        progressBar.visibility = View.GONE
        errorActionsRow.visibility = View.GONE
        contentContainer.removeAllViews()
        addTextRow(result.translatedText, sizeSp = 16f)
        addTextRow(getString(R.string.lookup_source_label, result.providerName), sizeSp = 12f, topMarginDp = 12, alpha = 0.6f)
        // Chi ghi nhan khi thanh cong (khong tinh loi mang/loi provider) -- ham nay chi duoc goi
        // tu nhanh runTranslation() thanh cong trong runLookup(). Dung cho "Tan suat dich" tren
        // man hinh "Tien trinh" (ProgressActivity), DATABASE_VERSION 17.
        DailyReadingStatsStore(MetadataDatabaseHelper(requireContext())).recordTranslation()
    }

    private fun showError() {
        progressBar.visibility = View.GONE
        contentContainer.removeAllViews()
        addTextRow(getString(R.string.lookup_error_generic), sizeSp = 15f)
        errorActionsRow.visibility = View.VISIBLE
    }

    private fun addTextRow(
        text: String,
        sizeSp: Float,
        bold: Boolean = false,
        italic: Boolean = false,
        alpha: Float = 1f,
        topMarginDp: Int = 0,
    ) {
        val tv = TextView(requireContext())
        tv.text = text
        tv.textSize = sizeSp
        tv.alpha = alpha
        tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.reader_selection_label_text))
        if (bold && italic) {
            tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD_ITALIC)
        } else if (bold) {
            tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        } else if (italic) {
            tv.setTypeface(tv.typeface, android.graphics.Typeface.ITALIC)
        }
        val density = resources.displayMetrics.density
        val params = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = (topMarginDp * density).toInt()
        tv.layoutParams = params
        contentContainer.addView(tv)
    }

    /**
     * Fallback khi ca hai provider deu that bai: mo trang web tuong ung (Wiktionary cho tu dien,
     * Google Translate cho dich) bang Intent.ACTION_VIEW, thay vi de nguoi dung khong lam gi
     * duoc tiep. Khong dua nguoi dung vao mot WebView rieng cua app -- mo bang trinh duyet/app
     * mac dinh cua he thong la du va don gian hon.
     */
    private fun openInBrowser() {
        val url = when (val m = mode) {
            is Mode.Dictionary -> {
                val encoded = URLEncoder.encode(m.word.trim(), "UTF-8")
                "https://en.wiktionary.org/wiki/$encoded"
            }
            is Mode.Translate -> {
                val target = ReaderLookupSettingsStore(requireContext()).getTargetLanguage()
                val encoded = URLEncoder.encode(m.text, "UTF-8")
                "https://translate.google.com/?sl=auto&tl=$target&text=$encoded&op=translate"
            }
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Logger.e(e)
        }
    }

    companion object {
        private const val ARG_MODE = "mode"
        private const val ARG_PAYLOAD = "payload"
        private const val MODE_DICTIONARY = "dictionary"
        private const val MODE_TRANSLATE = "translate"

        fun forDictionary(word: String): LookupBottomSheetFragment = LookupBottomSheetFragment().apply {
            arguments = bundleOf(ARG_MODE to MODE_DICTIONARY, ARG_PAYLOAD to word)
        }

        fun forTranslate(text: String): LookupBottomSheetFragment = LookupBottomSheetFragment().apply {
            arguments = bundleOf(ARG_MODE to MODE_TRANSLATE, ARG_PAYLOAD to text)
        }
    }
}
