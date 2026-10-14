package net.mrphan.calibre.reader.tts.dictionary

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mrphan.calibre.R

/**
 * Man hinh quan ly "Tu dien phat am" cho TTS - danh sach TOAN CUC (khong theo tung sach), CRUD
 * day du (Them/Sua/Xoa). Mo tu icon sach moi (`tts_player_dictionary`) tren
 * TtsPlayerBottomSheet, canh nut "Sherpa TTS" (`tts_player_bookmark`). Cung mot phong cach voi
 * TtsChapterListActivity: findViewById truc tiep, khong dung ViewBinding, tai lai du lieu bang
 * lifecycleScope + Dispatchers.IO thay vi giu TtsDictionaryStore o dang suspend/coroutine rieng.
 *
 * CHUA build/run-verified (sandbox khong co Android SDK) - xem ghi chu chung o dau prompt nang
 * cap tinh nang nay.
 */
class TtsDictionaryManagerActivity : AppCompatActivity() {

    private lateinit var store: TtsDictionaryStore
    private lateinit var adapter: TtsDictionaryEntryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tts_dictionary_manager)
        store = TtsDictionaryStore(applicationContext)

        findViewById<TextView>(R.id.tts_dictionary_title).setText(R.string.reader_tts_pronunciation_dictionary_title)
        findViewById<ImageButton>(R.id.tts_dictionary_back).setOnClickListener { finish() }

        val recyclerView = findViewById<RecyclerView>(R.id.tts_dictionary_recycler_view)
        adapter = TtsDictionaryEntryAdapter(
            onEditClick = { entry -> showEntryDialog(entry) },
            onDeleteClick = { entry -> confirmDelete(entry) },
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<View>(R.id.tts_dictionary_add_button).setOnClickListener {
            showEntryDialog(null)
        }

        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { store.getAll() }
            adapter.submitList(entries)
            findViewById<TextView>(R.id.tts_dictionary_empty_state).visibility =
                if (entries.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** [existing] null = them moi, khac null = sua dong da co. */
    private fun showEntryDialog(existing: TtsDictionaryEntry?) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val sourceInput = EditText(this).apply {
            hint = getString(R.string.tts_dictionary_source_hint)
            setText(existing?.sourceText.orEmpty())
        }
        val replacementInput = EditText(this).apply {
            hint = getString(R.string.tts_dictionary_replacement_hint)
            setText(existing?.replacementText.orEmpty())
        }
        container.addView(sourceInput)
        container.addView(replacementInput)

        AlertDialog.Builder(this)
            .setTitle(
                if (existing == null) R.string.tts_dictionary_add_title else R.string.tts_dictionary_edit_title,
            )
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val source = sourceInput.text?.toString()?.trim().orEmpty()
                val replacement = replacementInput.text?.toString()?.trim().orEmpty()
                if (source.isBlank() || replacement.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        if (existing == null) {
                            store.add(source, replacement)
                        } else {
                            store.update(existing.id, source, replacement)
                        }
                    }
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(entry: TtsDictionaryEntry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.tts_dictionary_delete_confirm_title)
            .setMessage(getString(R.string.tts_dictionary_delete_confirm_message, entry.sourceText))
            .setPositiveButton(R.string.tts_dictionary_delete_confirm_positive) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { store.remove(entry.id) }
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
