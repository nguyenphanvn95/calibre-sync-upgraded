package net.mrphan.calibre.reader.tts.dictionary

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import net.mrphan.calibre.R

/** Moi dong hien "sourceText -> replacementText" kem 2 nut Sua/Xoa - xem
 * TtsDictionaryManagerActivity. */
class TtsDictionaryEntryAdapter(
    private val onEditClick: (TtsDictionaryEntry) -> Unit,
    private val onDeleteClick: (TtsDictionaryEntry) -> Unit,
) : RecyclerView.Adapter<TtsDictionaryEntryAdapter.ViewHolder>() {

    private var items: List<TtsDictionaryEntry> = emptyList()

    fun submitList(newItems: List<TtsDictionaryEntry>) {
        items = newItems
        // Danh sach nho (tu dien phat am ca nguoi doc thuong chi vai chuc dong) - DiffUtil la
        // thua trong pham vi tinh nang nay, notifyDataSetChanged() du dung.
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val sourceText: TextView = view.findViewById(R.id.tts_dictionary_item_source)
        val replacementText: TextView = view.findViewById(R.id.tts_dictionary_item_replacement)
        val editButton: View = view.findViewById(R.id.tts_dictionary_item_edit)
        val deleteButton: View = view.findViewById(R.id.tts_dictionary_item_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tts_dictionary_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]
        holder.sourceText.text = entry.sourceText
        holder.replacementText.text = entry.replacementText
        holder.editButton.setOnClickListener { onEditClick(entry) }
        holder.deleteButton.setOnClickListener { onDeleteClick(entry) }
    }

    override fun getItemCount(): Int = items.size
}
