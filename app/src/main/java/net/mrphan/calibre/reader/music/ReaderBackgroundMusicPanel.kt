package net.mrphan.calibre.reader.music

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mrphan.calibre.R
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI

class ReaderBackgroundMusicPanel(
    private val root: View,
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: ReaderBackgroundMusicStore,
    private val controller: ReaderBackgroundMusicController,
) {
    private val scrim = root.findViewById<View>(R.id.reader_music_panel_scrim)
    private val container = root.findViewById<View>(R.id.reader_music_panel_container)
    private val libraryTab = root.findViewById<TextView>(R.id.reader_music_tab_library)
    private val linksTab = root.findViewById<TextView>(R.id.reader_music_tab_links)
    private val libraryPage = root.findViewById<View>(R.id.reader_music_library_page)
    private val linksPage = root.findViewById<View>(R.id.reader_music_links_page)
    private val searchField = root.findViewById<EditText>(R.id.reader_music_search_field)
    private val trackList = root.findViewById<LinearLayout>(R.id.reader_music_track_list)
    private val linkList = root.findViewById<LinearLayout>(R.id.reader_music_link_list)
    private val addLinkButton = root.findViewById<Button>(R.id.reader_music_add_link)
    private val httpClient = OkHttpClient()

    private var allTracks: List<ReaderMusicTrack> = emptyList()
    private var links: List<ReaderMusicRepositoryLink> = emptyList()
    private var selectedTab = Tab.LIBRARY
    private var hasLoadedThisSession = false

    init {
        scrim.setOnClickListener { hide() }
        libraryTab.setOnClickListener { selectTab(Tab.LIBRARY) }
        linksTab.setOnClickListener { selectTab(Tab.LINKS) }
        addLinkButton.setOnClickListener { showLinkDialog(null) }
        searchField.addTextChangedListener { renderTracks() }
        applyPanelWidth()
        renderCached()
    }

    fun isShown(): Boolean = root.visibility == View.VISIBLE

    fun show() {
        applyPanelWidth()
        root.visibility = View.VISIBLE
        container.translationX = -container.layoutParams.width.toFloat()
        container.animate().translationX(0f).setDuration(180L).start()
        selectTab(Tab.LIBRARY)
        if (!hasLoadedThisSession) {
            hasLoadedThisSession = true
            refreshAllLinks()
        }
    }

    fun hide() {
        container.animate()
            .translationX(-container.width.toFloat())
            .setDuration(160L)
            .withEndAction { root.visibility = View.GONE }
            .start()
        searchField.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(searchField.windowToken, 0)
    }

    private fun applyPanelWidth() {
        val width = (context.resources.displayMetrics.widthPixels * 0.88f).toInt()
            .coerceAtMost(dp(360))
            .coerceAtLeast(dp(300))
        container.updateLayoutParams<ViewGroup.LayoutParams> { this.width = width }
    }

    private fun renderCached() {
        links = store.getLinks()
        allTracks = (store.getCachedTracks() + store.loadBundledTracks()).distinctBy { it.url }
        renderTracks()
        renderLinks()
    }

    private fun refreshAllLinks() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                links.flatMap { link -> loadTracksFromLink(link) }
            }
            val bundled = store.loadBundledTracks()
            val merged = (bundled + loaded).distinctBy { it.url }
            if (merged.isNotEmpty()) {
                allTracks = merged
                store.saveCachedTracks(allTracks)
            } else if (allTracks.isEmpty()) {
                allTracks = bundled
                if (allTracks.isNotEmpty()) store.saveCachedTracks(allTracks)
            }
            renderTracks()
            renderLinks()
        }
    }

    private fun refreshOneLink(link: ReaderMusicRepositoryLink) {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { loadTracksFromLink(link) }
            if (loaded.isNotEmpty()) {
                allTracks = (
                    store.loadBundledTracks() +
                        allTracks.filterNot { it.sourceName == link.name } +
                        loaded
                    ).distinctBy { it.url }
                store.saveCachedTracks(allTracks)
                renderTracks()
            }
        }
    }

    private fun loadTracksFromLink(link: ReaderMusicRepositoryLink): List<ReaderMusicTrack> {
        if (YouTubeAudioResolver.isYouTubeUrl(link.url)) {
            return resolveYouTubeLink(link)?.let(::listOf).orEmpty()
        }
        val json = try {
            when {
                link.url.startsWith("http://", true) || link.url.startsWith("https://", true) -> {
                    val request = Request.Builder().url(link.url).build()
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return emptyList()
                        response.body?.string().orEmpty()
                    }
                }
                link.url.startsWith("file://", true) -> File(URI(link.url)).readText()
                else -> File(link.url).readText()
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return ReaderMusicJsonParser.parse(json, link.name).map { track ->
            resolveYouTubeTrackMetadata(track)
        }
    }

    private fun resolveYouTubeLink(link: ReaderMusicRepositoryLink): ReaderMusicTrack? {
        val resolved = YouTubeAudioResolver.resolve(link.url) ?: return null
        return ReaderMusicTrack(
            title = resolved.title.ifBlank { link.name },
            artist = resolved.artist,
            url = link.url,
            thumbUrl = resolved.thumbUrl,
            sourceName = link.name,
        )
    }

    private fun resolveYouTubeTrackMetadata(track: ReaderMusicTrack): ReaderMusicTrack {
        if (!YouTubeAudioResolver.isYouTubeUrl(track.url)) return track
        val needsMetadata = track.title.isBlank() ||
            track.title.equals("Unknown", ignoreCase = true) ||
            track.artist.isBlank() ||
            track.thumbUrl.isNullOrBlank()
        if (!needsMetadata) return track
        val resolved = YouTubeAudioResolver.resolve(track.url) ?: return track
        return track.copy(
            title = track.title.takeUnless { it.isBlank() || it.equals("Unknown", ignoreCase = true) }
                ?: resolved.title,
            artist = track.artist.ifBlank { resolved.artist },
            thumbUrl = track.thumbUrl ?: resolved.thumbUrl,
        )
    }

    private fun selectTab(tab: Tab) {
        selectedTab = tab
        val accent = ContextCompat.getColor(context, R.color.reader_accent_blue)
        val muted = 0xFFC5C6CC.toInt()
        libraryTab.setTextColor(if (tab == Tab.LIBRARY) accent else muted)
        linksTab.setTextColor(if (tab == Tab.LINKS) accent else muted)
        libraryPage.visibility = if (tab == Tab.LIBRARY) View.VISIBLE else View.GONE
        linksPage.visibility = if (tab == Tab.LINKS) View.VISIBLE else View.GONE
    }

    private fun renderTracks() {
        trackList.removeAllViews()
        val query = searchField.text?.toString()?.trim().orEmpty()
        val filtered = allTracks.filter {
            query.isBlank() ||
                it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true)
        }
        if (filtered.isEmpty()) {
            trackList.addView(emptyText(context.getString(R.string.reader_music_empty)))
            return
        }
        filtered.forEachIndexed { index, track ->
            trackList.addView(trackRow(index + 1, track))
        }
    }

    private fun trackRow(index: Int, track: ReaderMusicTrack): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            minimumHeight = dp(54)
            isClickable = true
            background = selectable()
            setOnClickListener {
                controller.play(track)
                hide()
            }
        }
        row.addView(TextView(context).apply {
            text = index.toString()
            textSize = 18f
            setTextColor(0xFFA9AAB0.toInt())
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = track.title
                textSize = 14f
                setTextColor(0xFFF1F1F2.toInt())
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                text = "\u266B ${track.artist}"
                textSize = 12f
                setTextColor(0xFFAEB0B6.toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_reader_music_play)
            setColorFilter(0xFFF4F4F5.toInt())
            background = selectableBorderless()
            contentDescription = context.getString(R.string.reader_music_play_pause)
            setOnClickListener {
                controller.play(track)
                hide()
            }
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        return row
    }

    private fun renderLinks() {
        linkList.removeAllViews()
        links.forEach { link -> linkList.addView(linkRow(link)) }
    }

    private fun linkRow(link: ReaderMusicRepositoryLink): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(TextView(context).apply {
            text = link.name
            textSize = 15f
            setTextColor(0xFFF1F1F2.toInt())
            typeface = Typeface.DEFAULT_BOLD
        })
        row.addView(TextView(context).apply {
            text = link.url
            textSize = 12f
            setTextColor(0xFFAEB0B6.toInt())
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(LinearLayout(context).apply {
            gravity = Gravity.END
            addView(actionButton(context.getString(R.string.reader_music_refresh_link)) { refreshOneLink(link) })
            addView(actionButton(context.getString(R.string.reader_music_edit_link)) { showLinkDialog(link) })
            addView(actionButton(context.getString(R.string.reader_music_delete_link)) {
                links = links.filterNot { it == link }
                allTracks = (store.loadBundledTracks() + allTracks.filterNot { it.sourceName == link.name })
                    .distinctBy { it.url }
                store.saveLinks(links)
                store.saveCachedTracks(allTracks)
                renderTracks()
                renderLinks()
            })
        })
        return row
    }

    private fun showLinkDialog(existing: ReaderMusicRepositoryLink?) {
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val nameInput = EditText(context).apply {
            hint = context.getString(R.string.reader_music_link_name_hint)
            setSingleLine(true)
            setText(existing?.name.orEmpty())
        }
        val urlInput = EditText(context).apply {
            hint = context.getString(R.string.reader_music_link_url_hint)
            setSingleLine(true)
            setText(existing?.url.orEmpty())
        }
        form.addView(nameInput)
        form.addView(urlInput)
        AlertDialog.Builder(context)
            .setTitle(R.string.reader_music_link_dialog_title)
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val url = urlInput.text.toString().trim()
                if (url.isBlank()) return@setPositiveButton
                val name = nameInput.text.toString().trim().ifBlank { url }
                val newLink = ReaderMusicRepositoryLink(name, url)
                links = if (existing == null) {
                    links + newLink
                } else {
                    links.map { if (it == existing) newLink else it }
                }
                store.saveLinks(links)
                renderLinks()
                refreshOneLink(newLink)
            }
            .show()
    }

    private fun emptyText(textValue: String): TextView = TextView(context).apply {
        text = textValue
        gravity = Gravity.CENTER
        setPadding(0, dp(48), 0, 0)
        setTextColor(0xFFC5C6CC.toInt())
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(context).apply {
        text = label
        textSize = 12f
        minWidth = 0
        minHeight = 0
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { action() }
    }

    private fun selectable() = android.util.TypedValue().let { out ->
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        ContextCompat.getDrawable(context, out.resourceId)
    }

    private fun selectableBorderless() = android.util.TypedValue().let { out ->
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)
        ContextCompat.getDrawable(context, out.resourceId)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private enum class Tab { LIBRARY, LINKS }
}
