package net.mrphan.calibre.reader.stats

import net.mrphan.calibre.MetadataDatabaseHelper
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import kotlin.math.max

/** Mot ngay + so lieu doc duoc trong ngay do, dung cho bieu do va chuoi ngay cua man hinh
 * "Tien trinh". */
data class DailyPoint(
    val date: LocalDate,
    val readingMs: Long,
    val wordCount: Long,
    val translationCount: Long,
) {
    val minutes: Int get() = (readingMs / 60_000L).toInt()
}

enum class StatsPeriod { WEEK, MONTH, YEAR, ALL_TIME }

data class PeriodSummary(
    val points: List<DailyPoint>,
    /** Tong so phut doc trong ky dang xem. */
    val totalMinutes: Int,
    val averageMinutesPerWeek: Int,
    val previousPeriodMinutes: Int,
    val currentPeriodMinutes: Int,
    val totalWordCount: Long,
    val totalTranslationCount: Long,
    val previousPeriodWordCount: Long = 0,
    val previousPeriodTranslationCount: Long = 0,
) {
    /** Toc do doc trung binh (tu/phut) trong ky, hoac 0 neu chua co du lieu tu. */
    val wordsPerMinute: Int
        get() {
            val minutes = totalMinutes.takeIf { it > 0 } ?: return 0
            return (totalWordCount / minutes).toInt()
        }

    /** Cung cong thuc voi [wordsPerMinute] nhung cho ky truoc do -- dung de so sanh "Toc do doc"
     * voi ky truoc. */
    val previousWordsPerMinute: Int
        get() {
            val minutes = previousPeriodMinutes.takeIf { it > 0 } ?: return 0
            return (previousPeriodWordCount / minutes).toInt()
        }

    /** Tan suat dich: so ban dich tren moi 100 tu da doc. */
    val translationsPer100Words: Double
        get() {
            if (totalWordCount <= 0) return 0.0
            return totalTranslationCount * 100.0 / totalWordCount
        }

    /** Cung cong thuc voi [translationsPer100Words] nhung cho ky truoc do. */
    val previousTranslationsPer100Words: Double
        get() {
            if (previousPeriodWordCount <= 0) return 0.0
            return previousPeriodTranslationCount * 100.0 / previousPeriodWordCount
        }
}

data class StreakInfo(val current: Int, val record: Int, val activeDaysThisWeek: Set<DayOfWeek>)

/**
 * API tong hop cho man hinh "Tien trinh" (ProgressActivity), xay tren
 * [MetadataDatabaseHelper.getDailyStatsRange]/[MetadataDatabaseHelper.addDailyReadingStats].
 * Khong giu state trong bo nho -- moi lan goi doc lai tu SQLite (bang nho, du nhanh; man hinh
 * Tien trinh khong nam trong hot path nhu reader). Du lieu o day CONG DON theo ngay, KHONG phu
 * thuoc sach nao con trong thu vien -- sach bi xoa/sua metadata sau nay khong lam mat lich su
 * doc da ghi nhan.
 */
class DailyReadingStatsStore(private val db: MetadataDatabaseHelper) {

    // T7 (thu Bay) la ngay dau tuan, khop dung UI mau (Tuan truoc/Tuan hien tai, cot T7->T6).
    private val weekFields = WeekFields.of(DayOfWeek.SATURDAY, 1)

    /** Goi tu ReadingTimeTracker.flush() moi khi co segment thoi gian doc moi duoc chot.
     * [wordsRead] uoc luong tu weight/tien do cua StatUnit dang doc, co the la 0 neu nguon
     * khong cung cap so tu (PDF/CBZ khong co word count -> chi cong ms). */
    fun recordReadingSession(deltaMs: Long, wordsRead: Long = 0) {
        if (deltaMs <= 0 && wordsRead <= 0) return
        db.addDailyReadingStats(LocalDate.now().toEpochDay(), deltaMs, wordsRead)
    }

    /** Goi tu LookupBottomSheetFragment moi lan dich thanh cong. */
    fun recordTranslation() {
        db.incrementDailyTranslationCount(LocalDate.now().toEpochDay())
    }

    /** Tong so phut da doc TRONG TOAN BO LICH SU (khong gioi han theo ky) -- dung cho o "Tong" o
     * dau man hinh Tien trinh. */
    fun getAllTimeTotalMinutes(): Int {
        val today = LocalDate.now()
        // Gioi han quet toi da 10 nam de tranh doc toan bang neu du lieu qua lon; du cho hau het
        // nguoi dung thuc te.
        return (getPointsForRange(today.minusYears(10), today).sumOf { it.readingMs } / 60_000L).toInt()
    }

    /** Tong so phut da doc HOM NAY -- dung cho o "Hom nay" o dau man hinh Tien trinh. */
    fun getTodayMinutes(): Int {
        val today = LocalDate.now()
        return (getPointsForRange(today, today).sumOf { it.readingMs } / 60_000L).toInt()
    }

    fun getPointsForRange(from: LocalDate, to: LocalDate): List<DailyPoint> {
        if (to.isBefore(from)) return emptyList()
        val rows = db.getDailyStatsRange(from.toEpochDay(), to.toEpochDay())
        val byEpoch = rows.associateBy { it[0] }
        // Dien day du cac ngay trong khoang, ke ca ngay khong co du lieu (0 phut), de bieu do
        // khong bi "nhay coc" thieu cot.
        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .map { date ->
                val row = byEpoch[date.toEpochDay()]
                DailyPoint(
                    date = date,
                    readingMs = row?.get(1) ?: 0L,
                    wordCount = row?.get(2) ?: 0L,
                    translationCount = row?.get(3) ?: 0L,
                )
            }.toList()
    }

    /** Tuan/thang/nam hien tai (hoac toan bo lich su) tuy [period], keo theo ky truoc do de tinh
     * "Xu huong" (so voi tuan/thang/nam truoc). */
    fun getSummary(period: StatsPeriod): PeriodSummary {
        val today = LocalDate.now()
        val range = rangeFor(period, today)
        val points = getPointsForRange(range.from, range.to)
        val totalMs = points.sumOf { it.readingMs }
        val currentPeriodMinutes = (totalMs / 60_000L).toInt()
        val previousPoints = if (range.prevFrom != null && range.prevTo != null) {
            getPointsForRange(range.prevFrom, range.prevTo)
        } else emptyList()
        val previousPeriodMinutes = (previousPoints.sumOf { it.readingMs } / 60_000L).toInt()
        val weeksSpanned = max(1, ChronoUnit.DAYS.between(range.from, range.to).toInt() / 7 + 1)
        return PeriodSummary(
            points = points,
            totalMinutes = currentPeriodMinutes,
            averageMinutesPerWeek = currentPeriodMinutes / weeksSpanned,
            previousPeriodMinutes = previousPeriodMinutes,
            currentPeriodMinutes = currentPeriodMinutes,
            totalWordCount = points.sumOf { it.wordCount },
            totalTranslationCount = points.sumOf { it.translationCount },
            previousPeriodWordCount = previousPoints.sumOf { it.wordCount },
            previousPeriodTranslationCount = previousPoints.sumOf { it.translationCount },
        )
    }

    /** Chuoi ngay lien tuc: dem nguoc tu hom nay, ngay nao co reading_ms > 0 thi tinh, dung lai o
     * ngay dau tien khong co du lieu -- tru truong hop hom nay chua doc gi, luc do van cho phep
     * hom qua la ngay cuoi chuoi (giong Duolingo, khong bi mat streak ngay khi vua qua nua dem
     * ma chua kip mo sach). Ky luc = chuoi dai nhat tung dat duoc trong lich su da quet. */
    fun getStreak(): StreakInfo {
        val today = LocalDate.now()
        // Doc toi da 3 nam gan nhat de tinh ky luc -- du cho hau het nguoi dung, tranh quet toan
        // bo lich su neu du lieu rat lon.
        val history = getPointsForRange(today.minusYears(3), today)
        val activeDays = history.filter { it.readingMs > 0 }.map { it.date }.toSet()

        var current = 0
        var cursor = today
        if (cursor !in activeDays) cursor = cursor.minusDays(1)
        while (cursor in activeDays) {
            current++
            cursor = cursor.minusDays(1)
        }

        var record = 0
        var running = 0
        var prevDate: LocalDate? = null
        for (date in activeDays.sorted()) {
            running = if (prevDate != null && prevDate.plusDays(1) == date) running + 1 else 1
            record = max(record, running)
            prevDate = date
        }
        record = max(record, current)

        val weekStart = today.with(weekFields.dayOfWeek(), 1)
        val activeThisWeek = (0..6).map { weekStart.plusDays(it.toLong()) }
            .filter { it in activeDays }
            .map { it.dayOfWeek }
            .toSet()

        return StreakInfo(current = current, record = record, activeDaysThisWeek = activeThisWeek)
    }

    private data class RangeQuad(
        val from: LocalDate, val to: LocalDate,
        val prevFrom: LocalDate?, val prevTo: LocalDate?,
    )

    private fun rangeFor(period: StatsPeriod, today: LocalDate): RangeQuad = when (period) {
        StatsPeriod.WEEK -> {
            val start = today.with(weekFields.dayOfWeek(), 1)
            val end = start.plusDays(6)
            RangeQuad(start, end, start.minusWeeks(1), end.minusWeeks(1))
        }
        StatsPeriod.MONTH -> {
            val start = today.withDayOfMonth(1)
            val end = start.plusMonths(1).minusDays(1)
            val prevStart = start.minusMonths(1)
            RangeQuad(start, end, prevStart, prevStart.plusMonths(1).minusDays(1))
        }
        StatsPeriod.YEAR -> {
            val start = today.withDayOfYear(1)
            val end = start.plusYears(1).minusDays(1)
            val prevStart = start.minusYears(1)
            RangeQuad(start, end, prevStart, prevStart.plusYears(1).minusDays(1))
        }
        StatsPeriod.ALL_TIME -> RangeQuad(LocalDate.of(2020, 1, 1), today, null, null)
    }
}
