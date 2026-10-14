package net.mrphan.calibre

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * BottomSheet "CHỌN MỤC TIÊU ĐỌC HÀNG NGÀY CỦA BẠN", mo tu card_daily_goal trong
 * activity_progress.xml (ProgressActivity). 3 muc co dinh (De dang/Trung binh/Kho khan) + 1 muc
 * "Gia tri cua ban" voi stepper -/+ tuy chinh tu 1 den 180 phut. Ket qua duoc bao ve
 * [onSaved] khi nguoi dung bam Luu -- ProgressActivity ghi vao SharedPreferences
 * (PREF_DAILY_GOAL_MINUTES), sheet nay KHONG tu luu gi ca.
 *
 * [onSaved] la 1 lambda giu tam thoi trong instance Fragment (khong Parcelable) -- neu he thong
 * huy roi tao lai Fragment nay sau khi Activity bi kill do thieu bo nho (qua trinh nen), lambda se
 * mat va nut Luu se khong lam gi ca thay vi crash (xem onSaveClicked() - kiem tra null truoc khi
 * goi). Day la danh doi chap nhan duoc: nguoi dung se can mo lai sheet trong truong hop rat hiem
 * gap do, khong crash la du.
 *
 * FIX (chon-1-o-nhung-khong-bo-chon-o-cu): sheet_daily_goal_picker.xml boc moi RadioButton trong
 * 1 LinearLayout hang rieng (de ghep them TextView phut/stepper ben canh) -- nen ve mat cay View,
 * cac RadioButton do la CHAU (grandchild) cua RadioGroup, khong phai CON TRUC TIEP. RadioGroup chi
 * tu dong bo-chon-cai-cu cho cac RadioButton la con truc tiep cua no (qua PassThroughHierarchyChangeListener
 * gan trong addView() cua chinh no) -- voi cau truc long 1 cap nhu o day, RadioGroup KHONG HE BIET
 * den su ton tai cua 4 RadioButton nay, nen ca radioGroup.check(id) lan tinh nang tu-uncheck deu
 * khong hoat dong, gay ra hien tuong nhieu o cung duoc tick nhu anh chup man hinh. Cach sua: bo
 * hoan toan viec dua vao hanh vi tu dong cua RadioGroup, tu quan ly "dang chon o nao" bang code
 * qua selectGoalOption() ben duoi (goi tu OnClickListener cua tung RadioButton, thay vi dua vao
 * RadioGroup callback).
 */
class DailyGoalPickerBottomSheet : BottomSheetDialogFragment() {

    private var onSaved: ((Int) -> Unit)? = null
    private var customMinutes: Int = DEFAULT_CUSTOM_MINUTES

    /** ID cua RadioButton dang duoc chon -- nguon su that DUY NHAT cho lua chon hien tai, thay
     * cho radioGroup.checkedRadioButtonId (khong dung duoc, xem ghi chu FIX o class doc trên). */
    private var selectedGoalId: Int = R.id.radio_goal_easy

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_daily_goal_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val initialGoal = arguments?.getInt(ARG_CURRENT_GOAL, EASY_MINUTES) ?: EASY_MINUTES
        customMinutes = when (initialGoal) {
            EASY_MINUTES, MEDIUM_MINUTES, HARD_MINUTES -> DEFAULT_CUSTOM_MINUTES
            else -> initialGoal
        }

        val radioEasy = view.findViewById<RadioButton>(R.id.radio_goal_easy)
        val radioMedium = view.findViewById<RadioButton>(R.id.radio_goal_medium)
        val radioHard = view.findViewById<RadioButton>(R.id.radio_goal_hard)
        val radioCustom = view.findViewById<RadioButton>(R.id.radio_goal_custom)
        val allRadios = listOf(radioEasy, radioMedium, radioHard, radioCustom)
        val textCustomMinutes = view.findViewById<TextView>(R.id.text_custom_minutes)

        // Tu quan ly viec chon-1-bo-chon-cac-cai-con-lai thay vi dua vao RadioGroup (xem ghi chu
        // FIX o class doc trên) -- goi ham nay moi khi 1 lua chon thay doi, tu bat ky nguon nao
        // (bam thang vao RadioButton, hoac bam +/- o hang "Gia tri cua ban").
        fun selectGoalOption(radio: RadioButton) {
            selectedGoalId = radio.id
            allRadios.forEach { it.isChecked = (it.id == radio.id) }
        }

        view.findViewById<TextView>(R.id.text_goal_easy_minutes).text =
            getString(R.string.progress_goal_minutes_format, EASY_MINUTES)
        view.findViewById<TextView>(R.id.text_goal_medium_minutes).text =
            getString(R.string.progress_goal_minutes_format, MEDIUM_MINUTES)
        view.findViewById<TextView>(R.id.text_goal_hard_minutes).text =
            getString(R.string.progress_goal_minutes_format, HARD_MINUTES)
        textCustomMinutes.text = customMinutes.toString()

        when (initialGoal) {
            EASY_MINUTES -> selectGoalOption(radioEasy)
            MEDIUM_MINUTES -> selectGoalOption(radioMedium)
            HARD_MINUTES -> selectGoalOption(radioHard)
            else -> selectGoalOption(radioCustom)
        }

        allRadios.forEach { radio -> radio.setOnClickListener { selectGoalOption(radio) } }

        view.findViewById<View>(R.id.btn_custom_minus).setOnClickListener {
            customMinutes = (customMinutes - 1).coerceAtLeast(1)
            textCustomMinutes.text = customMinutes.toString()
            selectGoalOption(radioCustom)
        }
        view.findViewById<View>(R.id.btn_custom_plus).setOnClickListener {
            customMinutes = (customMinutes + 1).coerceAtMost(MAX_CUSTOM_MINUTES)
            textCustomMinutes.text = customMinutes.toString()
            selectGoalOption(radioCustom)
        }

        view.findViewById<View>(R.id.btn_goal_save).setOnClickListener {
            val chosen = when (selectedGoalId) {
                R.id.radio_goal_easy -> EASY_MINUTES
                R.id.radio_goal_medium -> MEDIUM_MINUTES
                R.id.radio_goal_hard -> HARD_MINUTES
                else -> customMinutes
            }
            onSaved?.invoke(chosen)
            dismiss()
        }
        view.findViewById<View>(R.id.btn_goal_cancel).setOnClickListener { dismiss() }
    }

    companion object {
        const val EASY_MINUTES = 10
        const val MEDIUM_MINUTES = 20
        const val HARD_MINUTES = 30
        private const val DEFAULT_CUSTOM_MINUTES = 10
        private const val MAX_CUSTOM_MINUTES = 180
        private const val ARG_CURRENT_GOAL = "current_goal"

        fun newInstance(currentGoalMinutes: Int, onSaved: (Int) -> Unit): DailyGoalPickerBottomSheet {
            return DailyGoalPickerBottomSheet().apply {
                arguments = Bundle().apply { putInt(ARG_CURRENT_GOAL, currentGoalMinutes) }
                this.onSaved = onSaved
            }
        }
    }
}
