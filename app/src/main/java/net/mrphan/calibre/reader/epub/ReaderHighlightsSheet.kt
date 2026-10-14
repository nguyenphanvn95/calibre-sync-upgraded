package net.mrphan.calibre.reader.epub

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import net.mrphan.calibre.MetadataDatabaseHelper
import net.mrphan.calibre.R
import org.readium.r2.shared.publication.Locator

/**
 * Highlight panel opened from [net.mrphan.calibre.reader.chrome.HighlightsAction] (see prompt
 * section 5.4). Lists every "highlight"-type row of `reader_annotations` for the current book:
 * a color dot, the highlighted excerpt (italic, matching how [net.mrphan.calibre.EpubReaderActivity]'s
 * search results bold the matched text), and the note if one was added. This doubles as the
 * "Note" browser the person asked for -- per the prompt, a note is always attached to a
 * highlight/location rather than being its own separate kind of annotation, matching how Readest
 * treats highlights/bookmarks/notes as one system.
 */
class ReaderHighlightsSheet(
    private val mDB: MetadataDatabaseHelper,
    private val uuid: String,
    private val onJumpTo: (Locator) -> Unit,
    /** Called after a highlight's note is edited or a highlight is deleted, so the host Activity
     * can re-apply Readium decorations to match (see EpubReaderActivity#loadAndApplyHighlights). */
    private val onHighlightsChanged: () -> Unit,
) : BottomSheetDialogFragment() {

    private var listContainer: LinearLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 24)
        }
        val header = inflater.inflate(R.layout.item_reader_annotation_sheet_header, root, false)
        header.findViewById<TextView>(R.id.reader_annotation_sheet_title).setText(R.string.reader_highlights_title)
        // Highlights can only be created from the text-selection toolbar (see
        // net.mrphan.calibre.reader.epub.ReaderSelectionToolbar), not from this list -- hide the "+".
        header.findViewById<ImageButton>(R.id.reader_annotation_sheet_add).visibility = View.GONE
        root.addView(header)

        val list = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        listContainer = list
        root.addView(list)
        refreshList()
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        listContainer = null
    }

    private fun refreshList() {
        val list = listContainer ?: return
        list.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        val cursor = mDB.getAnnotations(uuid, "highlight")
        val annotations = try {
            ReaderAnnotation.readAll(cursor)
        } finally {
            cursor.close()
        }
        if (annotations.isEmpty()) {
            list.addView(
                ReaderEmptyStateView.inflate(
                    requireContext(),
                    list,
                    R.drawable.ic_empty_note,
                    R.string.reader_notes_empty_title,
                    R.string.reader_notes_empty_body,
                ),
            )
            return
        }
        for (annotation in annotations) {
            val locator = decodeLocator(annotation.locatorJson) ?: continue
            val row = inflater.inflate(R.layout.item_reader_highlight_entry, list, false)
            val colorDot = row.findViewById<View>(R.id.reader_highlight_entry_color_dot)
            val (color, _) = decodeColorStyle(annotation.color)
            ViewCompat.setBackgroundTintList(colorDot, ColorStateList.valueOf(color.argb))

            val excerptView = row.findViewById<TextView>(R.id.reader_highlight_entry_excerpt)
            excerptView.text = annotation.excerpt?.let { SpannableStringBuilder(it) }
                ?: getString(R.string.reader_highlights_empty)

            val noteView = row.findViewById<TextView>(R.id.reader_highlight_entry_note)
            if (!annotation.note.isNullOrBlank()) {
                noteView.text = annotation.note
                noteView.visibility = View.VISIBLE
            } else {
                noteView.visibility = View.GONE
            }

            row.setOnClickListener {
                onJumpTo(locator)
                dismiss()
            }
            row.setOnLongClickListener {
                promptEditNote(annotation)
                true
            }
            row.findViewById<ImageButton>(R.id.reader_highlight_entry_delete).setOnClickListener {
                mDB.removeAnnotation(annotation.id)
                onHighlightsChanged()
                refreshList()
            }
            list.addView(row)
        }
    }

    /** Long-press a row to add/edit its note -- same "Thêm ghi chú" flow as the selection
     * toolbar's Note icon (see [net.mrphan.calibre.reader.epub.ReaderSelectionToolbar]'s
     * `promptAddNote`), reused here for editing an existing highlight's note after the fact. */
    private fun promptEditNote(annotation: ReaderAnnotation) {
        val input = EditText(requireContext()).apply { setText(annotation.note) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reader_highlight_add_note)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                mDB.updateAnnotationNote(annotation.id, input.text?.toString()?.trim().orEmpty())
                onHighlightsChanged()
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val TAG = "ReaderHighlightsSheet"
    }
}
