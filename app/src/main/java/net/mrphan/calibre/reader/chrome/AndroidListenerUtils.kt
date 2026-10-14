package net.mrphan.calibre.reader.chrome

import android.view.View
import android.widget.AdapterView
import android.widget.SeekBar

/**
 * `SeekBar.OnSeekBarChangeListener` with only the `onProgressChanged` callback -- every dialog
 * that has a slider (font size, line height, ...) only ever cares about that one callback, so
 * this avoids re-implementing the two empty methods at every call site. See
 * EpubReaderActivity#openFontSizeControls().
 */
inline fun onProgressChanged(
    crossinline onChanged: (progress: Int) -> Unit,
): SeekBar.OnSeekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        if (fromUser) onChanged(progress)
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
}

/**
 * `AdapterView.OnItemSelectedListener` with only the `onItemSelected` callback -- see
 * [onProgressChanged] kdoc for the same reasoning, applied to the font-family `Spinner`.
 */
inline fun onItemSelected(
    crossinline onSelected: (position: Int) -> Unit,
): AdapterView.OnItemSelectedListener = object : AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        onSelected(position)
    }

    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
}
