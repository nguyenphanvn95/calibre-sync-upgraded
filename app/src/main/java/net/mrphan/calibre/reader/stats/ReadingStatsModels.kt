package net.mrphan.calibre.reader.stats

import kotlin.math.max

/**
 * One "unit" of a book for reading-stats purposes: an EPUB chapter (readingOrder entry) or a
 * PDF/CBZ page. [weight] is whatever comparable size measure the source uses to make the
 * remaining-time math proportional across units of very different length -- word count for EPUB
 * chapters, or a flat `1.0` per page for PDF/CBZ (see EpubReadingStatsSource / PageReadingStatsSource).
 */
data class StatUnit(
    val index: Int,
    val title: String,
    val key: String,
    val weight: Double,
)

/**
 * Everything [ReadingStatsDialog] needs to render one "Thống kê" screen. Built by a
 * [ReadingStatsSource]; nothing in here is fabricated -- every number is either read back from
 * [ReadingTimeTracker]'s persisted actual measurements, or derived from them (plus [StatUnit]
 * weights) via [computeEta] below.
 */
data class ReadingStatsSnapshot(
    val unitLabel: String,
    val units: List<StatUnit>,
    val currentIndex: Int,
    val currentUnitProgress: Double,
    val actualMsByUnit: Map<String, Long>,
    val defaultMsPerWeightUnit: Double,
    val isStillMeasuring: Boolean,
) {
    val isLastUnit: Boolean get() = units.isEmpty() || currentIndex >= units.lastIndex
    val currentUnit: StatUnit? get() = units.getOrNull(currentIndex)
    val currentPercent: Int get() = (currentUnitProgress.coerceIn(0.0, 1.0) * 100).toInt()
}

/** Result of [computeEta]: how long is left in the current unit, and in the whole book, in ms. */
data class EtaResult(
    val etaCurrentUnitMs: Long,
    val etaBookMs: Long,
    val measuredMsPerWeightUnit: Double,
    val usedFallbackSpeed: Boolean,
)

/**
 * Estimates time remaining from real measured reading speed wherever possible, following the
 * classic "words per minute you've actually demonstrated" approach used by calibre desktop's own
 * viewer stats: the more of the book the user has actually read, the more the estimate is driven
 * by their own measured pace rather than a generic default.
 *
 * Speed is computed as (sum of actual ms spent on units) / (sum of weight "covered" by that
 * actual time), where a unit's covered weight is its full [StatUnit.weight] if it lies before
 * [ReadingStatsSnapshot.currentIndex], or `weight * currentUnitProgress` if it *is* the current
 * unit (a unit strictly after the current one contributing actual time -- e.g. the user jumped
 * ahead via the table of contents and came back -- is still counted at full weight, since that
 * time really was spent reading it). Falls back to [ReadingStatsSnapshot.defaultMsPerWeightUnit]
 * (a sensible generic reading-speed default, format-specific) only when there isn't yet enough
 * measured data to trust.
 */
fun computeEta(snapshot: ReadingStatsSnapshot): EtaResult {
    val units = snapshot.units
    val currentIndex = snapshot.currentIndex
    val progress = snapshot.currentUnitProgress.coerceIn(0.0, 1.0)

    var coveredWeight = 0.0
    var coveredMs = 0.0
    for (unit in units) {
        val actualMs = snapshot.actualMsByUnit[unit.key] ?: continue
        if (actualMs <= 0) continue
        val effectiveWeight = if (unit.index == currentIndex) unit.weight * progress else unit.weight
        if (effectiveWeight <= 0) continue
        coveredWeight += effectiveWeight
        coveredMs += actualMs
    }

    // Require a small minimum of measured coverage before trusting the user's own pace, so the
    // very first few seconds of a session (before enough signal has accumulated) don't produce a
    // wildly noisy estimate -- fall back to the format's generic default speed until then.
    val minTrustedWeight = max(units.sumOf { it.weight } * 0.02, 1.0)
    val usedFallback = coveredWeight < minTrustedWeight
    val msPerWeightUnit = if (usedFallback) {
        snapshot.defaultMsPerWeightUnit
    } else {
        coveredMs / coveredWeight
    }

    val currentUnit = snapshot.currentUnit
    val remainingInCurrentUnitWeight = currentUnit?.let { it.weight * (1 - progress) } ?: 0.0
    val etaCurrentUnitMs = (remainingInCurrentUnitWeight * msPerWeightUnit).toLong().coerceAtLeast(0)

    val remainingFutureWeight = units.filter { it.index > currentIndex }.sumOf { it.weight }
    val etaBookMs = etaCurrentUnitMs + (remainingFutureWeight * msPerWeightUnit).toLong().coerceAtLeast(0)

    return EtaResult(
        etaCurrentUnitMs = etaCurrentUnitMs,
        etaBookMs = etaBookMs,
        measuredMsPerWeightUnit = msPerWeightUnit,
        usedFallbackSpeed = usedFallback,
    )
}

/** How a given [StatUnit] should be drawn/labelled in the timeline + list, derived purely from
 * [ReadingStatsSnapshot] data -- no separate bookkeeping needed. */
enum class UnitStatus { ACTUAL_PAST, ESTIMATED_PAST, CURRENT, ESTIMATED_FUTURE }

fun ReadingStatsSnapshot.statusFor(unit: StatUnit): UnitStatus = when {
    unit.index == currentIndex -> UnitStatus.CURRENT
    unit.index < currentIndex -> {
        if ((actualMsByUnit[unit.key] ?: 0L) > 0L) UnitStatus.ACTUAL_PAST else UnitStatus.ESTIMATED_PAST
    }
    else -> UnitStatus.ESTIMATED_FUTURE
}
