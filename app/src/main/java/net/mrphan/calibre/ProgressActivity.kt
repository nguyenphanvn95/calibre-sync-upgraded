package net.mrphan.calibre

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.android.material.tabs.TabLayout
import net.mrphan.calibre.reader.stats.DailyReadingStatsStore
import net.mrphan.calibre.reader.stats.DailyStatsBarChartView
import net.mrphan.calibre.reader.stats.PeriodSummary
import net.mrphan.calibre.reader.stats.StatsPeriod
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Man hinh "Tien trinh" (Progress) -- thong ke thoi gian doc CONG DON TOAN THU VIEN theo ngay,
 * doc lap voi sach nao con trong thu vien (sach bi xoa/sua metadata sau nay KHONG lam mat lich su
 * doc da ghi nhan, vi du lieu duoc ReadingTimeTracker ghi vao [DailyReadingStatsStore] -- mot bang
 * rieng theo ngay, khong tham chieu bang metadata nao ca). Mo tu Navigation Drawer
 * (R.id.nav_progress, xem MainActivity#setupNavigationDrawer()).
 *
 * KHAC voi [net.mrphan.calibre.reader.stats.ReadingStatsDialog], la thong ke CHI-TIET-1-CUON-SACH
 * (ETA con bao lau het chuong/sach) mo tu trong reader -- hai man hinh nay hoan toan doc lap,
 * dung 2 bang SQLite khac nhau.
 */
class ProgressActivity : AppCompatActivity() {

    private lateinit var store: DailyReadingStatsStore
    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var textTotalMinutes: TextView
    private lateinit var textTodayMinutes: TextView
    private lateinit var textDailyGoalMinutes: TextView
    private lateinit var cardDailyGoal: View
    private lateinit var textStreakCurrent: TextView
    private lateinit var textStreakRecord: TextView
    private lateinit var rowStreakDots: LinearLayout
    private lateinit var tabPeriod: TabLayout
    private lateinit var textAveragePerWeek: TextView
    private lateinit var chart: DailyStatsBarChartView
    private lateinit var rowChartLabels: LinearLayout
    private lateinit var textPreviousPeriodMinutes: TextView
    private lateinit var textCurrentPeriodMinutes: TextView
    private lateinit var badgeTrend: TextView
    private lateinit var textNotEnoughData: TextView
    private lateinit var groupPercentBar: View
    private lateinit var textPercentValue: TextView
    private lateinit var progressPercentBar: ProgressBar
    private lateinit var textReadingSpeed: TextView
    private lateinit var badgeReadingSpeed: TextView

    private var currentPeriod = StatsPeriod.WEEK

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Calibre_BookDetail)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_progress)

        store = DailyReadingStatsStore(MetadataDatabaseHelper(applicationContext))
        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        textTotalMinutes = findViewById(R.id.text_total_minutes)
        textTodayMinutes = findViewById(R.id.text_today_minutes)
        textDailyGoalMinutes = findViewById(R.id.text_daily_goal_minutes)
        cardDailyGoal = findViewById(R.id.card_daily_goal)
        textStreakCurrent = findViewById(R.id.text_streak_current)
        textStreakRecord = findViewById(R.id.text_streak_record)
        rowStreakDots = findViewById(R.id.row_streak_dots)
        tabPeriod = findViewById(R.id.tab_period)
        textAveragePerWeek = findViewById(R.id.text_average_per_week)
        chart = findViewById(R.id.chart_daily_stats)
        rowChartLabels = findViewById(R.id.row_chart_labels)
        textPreviousPeriodMinutes = findViewById(R.id.text_previous_period_minutes)
        textCurrentPeriodMinutes = findViewById(R.id.text_current_period_minutes)
        badgeTrend = findViewById(R.id.badge_trend)
        textNotEnoughData = findViewById(R.id.text_not_enough_data)
        groupPercentBar = findViewById(R.id.group_percent_bar)
        textPercentValue = findViewById(R.id.text_percent_value)
        progressPercentBar = findViewById(R.id.progress_percent_bar)
        textReadingSpeed = findViewById(R.id.text_reading_speed)
        badgeReadingSpeed = findViewById(R.id.badge_reading_speed)

        cardDailyGoal.setOnClickListener { openGoalPicker() }

        listOf(
            R.string.progress_tab_week to StatsPeriod.WEEK,
            R.string.progress_tab_month to StatsPeriod.MONTH,
            R.string.progress_tab_year to StatsPeriod.YEAR,
            R.string.progress_tab_all_time to StatsPeriod.ALL_TIME,
        ).forEach { (labelRes, period) ->
            tabPeriod.addTab(tabPeriod.newTab().setText(labelRes).setTag(period))
        }
        tabPeriod.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentPeriod = tab.tag as StatsPeriod
                renderForPeriod(currentPeriod)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    override fun onResume() {
        super.onResume()
        // Lam moi toan bo moi lan quay lai man hinh nay (vd sau khi doc sach xong roi back ra) --
        // man hinh nay khong nam trong hot path nen khong can toi uu, doc lai SQLite la du re.
        renderTopSummary()
        renderStreak()
        renderForPeriod(currentPeriod)
    }

    private fun renderTopSummary() {
        val goalMinutes = prefs.getInt(PREF_DAILY_GOAL_MINUTES, DEFAULT_GOAL_MINUTES)
        textTotalMinutes.text = getString(R.string.progress_goal_minutes_format, store.getAllTimeTotalMinutes())
        textTodayMinutes.text = getString(R.string.progress_goal_minutes_format, store.getTodayMinutes())
        textDailyGoalMinutes.text = getString(R.string.progress_goal_minutes_format, goalMinutes)
    }

    private fun renderStreak() {
        val streak = store.getStreak()
        textStreakCurrent.text = streak.current.toString()
        textStreakRecord.text = streak.record.toString()

        rowStreakDots.removeAllViews()
        val labels = resources.getStringArray(R.array.progress_weekday_short)
        val today = java.time.LocalDate.now()
        val weekFields = WeekFields.of(DayOfWeek.SATURDAY, 1)
        val weekStart = today.with(weekFields.dayOfWeek(), 1)
        val dotSizePx = (28 * resources.displayMetrics.density).toInt()
        for (i in 0..6) {
            val date = weekStart.plusDays(i.toLong())
            val active = date.dayOfWeek in streak.activeDaysThisWeek
            val isToday = date == today
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val label = TextView(this).apply {
                text = labels.getOrElse(i) { "" }
                textSize = 11f
                setTextColor(ContextCompat.getColor(context, R.color.color_progress_bar_muted))
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dotSizePx, dotSizePx).apply {
                    topMargin = (4 * resources.displayMetrics.density).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (active) {
                        setColor(ContextCompat.getColor(context, R.color.color_progress_accent))
                    } else {
                        setColor(android.graphics.Color.TRANSPARENT)
                        setStroke((1.5f * resources.displayMetrics.density).toInt(),
                            ContextCompat.getColor(context, R.color.color_progress_bar_muted))
                    }
                    if (isToday && !active) {
                        setStroke((2f * resources.displayMetrics.density).toInt(),
                            ContextCompat.getColor(context, R.color.color_progress_accent))
                    }
                }
            }
            column.addView(label)
            column.addView(dot)
            rowStreakDots.addView(column)
        }
    }

    private fun renderForPeriod(period: StatsPeriod) {
        val summary = store.getSummary(period)

        textAveragePerWeek.text = getString(R.string.progress_goal_minutes_format, summary.averageMinutesPerWeek)

        val buckets = buildChartBuckets(period, summary)
        chart.setData(buckets.map { it.minutes })
        rowChartLabels.removeAllViews()
        // Chi tao TextView cho cac bucket THUC SU co nhan (vd thang chi hien nhan moi 5 ngay --
        // xem buildChartBuckets). Truoc day tao 1 TextView/bucket (ca bucket rong) chia deu weight
        // nen moi cot qua hep, khien so 2 chu so ("11", "16"...) bi xuong dong giua chung (xem anh
        // mau vs anh loi). Chi giu bucket co nhan giong truc ngang trong anh mau (vd "3 9 15 21 27").
        buckets.filter { it.label.isNotEmpty() }.forEach { bucket ->
            val tv = TextView(this).apply {
                text = bucket.label
                textSize = 10f
                isSingleLine = true
                gravity = Gravity.CENTER_HORIZONTAL
                setTextColor(ContextCompat.getColor(context, R.color.color_progress_bar_muted))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            rowChartLabels.addView(tv)
        }

        textPreviousPeriodMinutes.text = getString(R.string.progress_goal_minutes_format, summary.previousPeriodMinutes)
        textCurrentPeriodMinutes.text = getString(R.string.progress_goal_minutes_format, summary.currentPeriodMinutes)
        renderTrendBadge(badgeTrend, summary.currentPeriodMinutes - summary.previousPeriodMinutes, isMinutes = true)

        // % hoan thanh chi tinh khi co du du lieu (it nhat 3 ngay co hoat dong trong ky) -- giong
        // "Không đủ dữ liệu để tính phần trăm đọc" trong anh mau khi tuan moi bat dau.
        val activeDaysInPeriod = summary.points.count { it.readingMs > 0 }
        if (activeDaysInPeriod >= MIN_ACTIVE_DAYS_FOR_PERCENT) {
            val goalMinutes = prefs.getInt(PREF_DAILY_GOAL_MINUTES, DEFAULT_GOAL_MINUTES)
            val targetMinutes = (goalMinutes * summary.points.size).coerceAtLeast(1)
            val percent = ((summary.totalMinutes * 100.0) / targetMinutes).roundToInt().coerceIn(0, 100)
            textNotEnoughData.visibility = View.GONE
            groupPercentBar.visibility = View.VISIBLE
            textPercentValue.text = "$percent%"
            progressPercentBar.progress = percent
        } else {
            textNotEnoughData.visibility = View.VISIBLE
            groupPercentBar.visibility = View.GONE
        }

        textReadingSpeed.text = summary.wordsPerMinute.toString()
        renderReadingSpeedBadge(summary)
    }

    private fun renderTrendBadge(badge: TextView, deltaMinutes: Int, isMinutes: Boolean) {
        val positive = deltaMinutes >= 0
        badge.setTextColor(ContextCompat.getColor(this,
            if (positive) R.color.color_progress_trend_up else R.color.color_progress_trend_down))
        val arrow = if (positive) "\u2191" else "\u2193"
        badge.text = "$arrow ${formatMinutesShort(abs(deltaMinutes))}"
    }

    private fun renderReadingSpeedBadge(summary: PeriodSummary) {
        val delta = summary.wordsPerMinute - summary.previousWordsPerMinute
        val positive = delta >= 0
        badgeReadingSpeed.setTextColor(ContextCompat.getColor(this,
            if (positive) R.color.color_progress_trend_up else R.color.color_progress_trend_down))
        val arrow = if (positive) "\u2191" else "\u2193"
        badgeReadingSpeed.text = getString(R.string.progress_reading_speed_today_format, abs(delta))
            .let { "$arrow $it" }
    }

    private fun formatMinutesShort(minutes: Int): String {
        if (minutes < 60) return getString(R.string.progress_goal_minutes_format, minutes)
        val hours = minutes / 60
        val rem = minutes % 60
        return "${hours}h ${rem}m"
    }

    private data class ChartBucket(val label: String, val minutes: Float)

    /** Gom [PeriodSummary.points] (luon la du lieu theo TUNG NGAY) thanh cac cot bieu do phu hop
     * voi tab dang chon: TUAN/THANG ve theo ngay, NAM/TAT_CA_THOI_GIAN gom theo thang de khoi qua
     * nhieu cot chen chuc. */
    private fun buildChartBuckets(period: StatsPeriod, summary: PeriodSummary): List<ChartBucket> {
        return when (period) {
            StatsPeriod.WEEK -> {
                val labels = resources.getStringArray(R.array.progress_weekday_short)
                summary.points.mapIndexed { i, p ->
                    ChartBucket(labels.getOrElse(i) { "" }, p.minutes.toFloat())
                }
            }
            StatsPeriod.MONTH -> {
                val showEvery = if (summary.points.size > 15) 5 else 1
                summary.points.mapIndexed { i, p ->
                    val label = if (i % showEvery == 0) p.date.dayOfMonth.toString() else ""
                    ChartBucket(label, p.minutes.toFloat())
                }
            }
            StatsPeriod.YEAR -> {
                val byMonth = LinkedHashMap<String, Float>()
                summary.points.forEach { p ->
                    val key = "${p.date.year}-${p.date.monthValue}"
                    byMonth[key] = (byMonth[key] ?: 0f) + p.minutes.toFloat()
                }
                byMonth.entries.map { (key, minutes) ->
                    val month = key.substringAfter('-')
                    ChartBucket("T$month", minutes)
                }
            }
            // "TAT CA THOI GIAN" gom theo TUNG NAM (giong anh mau: truc ngang la 2022, 2023,
            // 2024...), khac voi tab NAM o tren gom theo thang -- vi khoang thoi gian cua ALL_TIME
            // co the trai dai nhieu nam nen gom theo thang se qua nhieu cot chen chuc.
            StatsPeriod.ALL_TIME -> {
                val byYear = LinkedHashMap<Int, Float>()
                summary.points.forEach { p ->
                    byYear[p.date.year] = (byYear[p.date.year] ?: 0f) + p.minutes.toFloat()
                }
                byYear.entries.map { (year, minutes) ->
                    ChartBucket(year.toString(), minutes)
                }
            }
        }
    }

    private fun openGoalPicker() {
        val currentGoal = prefs.getInt(PREF_DAILY_GOAL_MINUTES, DEFAULT_GOAL_MINUTES)
        DailyGoalPickerBottomSheet.newInstance(currentGoal) { newGoal ->
            prefs.edit().putInt(PREF_DAILY_GOAL_MINUTES, newGoal).apply()
            renderTopSummary()
            renderForPeriod(currentPeriod)
        }.show(supportFragmentManager, "daily_goal_picker")
    }

    companion object {
        const val PREF_DAILY_GOAL_MINUTES = "progress_daily_goal_minutes"
        const val DEFAULT_GOAL_MINUTES = 10
        private const val MIN_ACTIVE_DAYS_FOR_PERCENT = 3

        @JvmStatic
        fun buildIntent(context: Context): Intent = Intent(context, ProgressActivity::class.java)
    }
}
