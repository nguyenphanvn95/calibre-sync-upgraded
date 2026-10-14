package net.mrphan.calibre.reader.epub

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import net.mrphan.calibre.R

object ReaderEmptyStateView {
    fun inflate(
        context: Context,
        parent: ViewGroup,
        @DrawableRes iconRes: Int,
        @StringRes titleRes: Int,
        @StringRes bodyRes: Int,
    ): View {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.view_reader_empty_state, parent, false)
        view.findViewById<ImageView>(R.id.reader_empty_state_icon).setImageResource(iconRes)
        view.findViewById<TextView>(R.id.reader_empty_state_title).setText(titleRes)
        view.findViewById<TextView>(R.id.reader_empty_state_body).setText(bodyRes)
        return view
    }
}
