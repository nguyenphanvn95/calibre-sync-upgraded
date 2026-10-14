package net.mrphan.calibre.reader.chrome.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.widget.SwitchCompat
import net.mrphan.calibre.R
import net.mrphan.calibre.reader.chrome.onProgressChanged

/**
 * Ham dung chung de dung cac hang cai dat (group header / switch row / stepper row / link row)
 * ben trong the card (`shape_reader_settings_group_bg`) cua tung [ReaderSettingsPage] -- tranh lap
 * lai cung mot doan code inflate+findViewById o ca 5 trang. Khong tu ghi preference: moi ham chi
 * goi lai callback do Page truyen vao, Page van la noi duy nhat biet cach doc/ghi trang thai cua
 * no (xem kdoc [ReaderSettingsPage]).
 */
object ReaderSettingsRowBinder {

    /** Nhom cac hang lien quan lai voi nhau (vi du "Phong chu", "Gian dong") ben trong 1 the. */
    fun addGroupHeader(container: ViewGroup, inflater: LayoutInflater, @StringRes titleRes: Int) {
        val row = inflater.inflate(R.layout.item_reader_settings_group_header, container, false)
        row.findViewById<TextView>(R.id.reader_settings_group_header_text).setText(titleRes)
        container.addView(row)
    }

    /** Hang bat/tat. Tra ve chinh view cua hang de goi noi con muon `isEnabled = false` khi hang do
     * phu thuoc mot cong tac khac (vi du cac hang Layout nang cao chi bat duoc khi da "Ghi de kieu
     * cua sach", xem ReaderSettingsLayoutPage). */
    fun addSwitchRow(
        container: ViewGroup,
        inflater: LayoutInflater,
        @StringRes titleRes: Int,
        initialValue: Boolean,
        onChanged: (Boolean) -> Unit,
    ): View {
        val row = inflater.inflate(R.layout.item_reader_settings_switch_row, container, false)
        row.findViewById<TextView>(R.id.reader_settings_row_title).setText(titleRes)
        val switchView = row.findViewById<SwitchCompat>(R.id.reader_settings_row_switch)
        switchView.isChecked = initialValue
        switchView.setOnCheckedChangeListener { _, checked -> onChanged(checked) }
        container.addView(row)
        return row
    }

    /** Hang thang do (SeekBar). [progress]/[max] la don vi hien thi tren UI (int, 0..max); Page
     * chiu trach nhiem quy doi qua lai gia tri Double thuc su cua Readium (xem cac ham
     * `progressToXxx`/`xxxToProgress` trong EpubReaderActivity ma cac trang nay tai dung). */
    fun addStepperRow(
        container: ViewGroup,
        inflater: LayoutInflater,
        @StringRes titleRes: Int,
        progress: Int,
        max: Int,
        formatValue: (Int) -> String,
        onChanged: (Int) -> Unit,
    ): View {
        val row = inflater.inflate(R.layout.item_reader_settings_stepper_row, container, false)
        row.findViewById<TextView>(R.id.reader_settings_row_title).setText(titleRes)
        val valueLabel = row.findViewById<TextView>(R.id.reader_settings_row_value)
        val seekBar = row.findViewById<SeekBar>(R.id.reader_settings_row_seekbar)
        seekBar.max = max
        seekBar.progress = progress
        valueLabel.text = formatValue(progress)
        seekBar.setOnSeekBarChangeListener(onProgressChanged { newProgress ->
            valueLabel.text = formatValue(newProgress)
            onChanged(newProgress)
        })
        container.addView(row)
        return row
    }

    /** Hang co the bam (vi du "Giong doc" trong tab TTS mo danh sach chon giong) -- tai dung layout
     * cua stepper row (title o tren, "value" o duoi dang mot dong text) nhung an SeekBar va gan
     * click listener cho ca hang thay vi lang nghe SeekBar. */
    fun addLinkRow(
        container: ViewGroup,
        inflater: LayoutInflater,
        @StringRes titleRes: Int,
        value: String,
        onClick: () -> Unit,
    ): View {
        val row = inflater.inflate(R.layout.item_reader_settings_stepper_row, container, false)
        row.findViewById<TextView>(R.id.reader_settings_row_title).setText(titleRes)
        row.findViewById<TextView>(R.id.reader_settings_row_value).text = value
        row.findViewById<SeekBar>(R.id.reader_settings_row_seekbar).visibility = View.GONE
        row.setOnClickListener { onClick() }
        row.isClickable = true
        row.isFocusable = true
        container.addView(row)
        return row
    }
}
