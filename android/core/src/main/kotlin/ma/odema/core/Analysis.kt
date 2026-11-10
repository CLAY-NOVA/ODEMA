package ma.odema.core

/** What one source says about a time window. */
data class SourceVerdict(
    val source: SourceId,
    val predictsRain: Boolean,
    /** When this source expects rain to begin (clamped to the window start). */
    val firstRain: Long?,
    /** Highest intensity in the window, mm/h. */
    val peakIntensity: Double?,
    /** Highest probability in the window, %. */
    val peakProbability: Double?,
    /** Rain expected in the window, mm. */
    val total: Double,
    /** Share of the window (0–1) this source has data for. */
    val coverage: Double,
)

/** Combined answer to "will it rain here soon?". */
data class RainOutlook(
    val windowStart: Long,
    val windowEnd: Long,
    /** Sources with data for the window: rainy first, earliest first. */
    val verdicts: List<SourceVerdict>,
    /** Weighted share of sources predicting rain, 0–1. */
    val confidence: Double,
    val willRain: Boolean,
    /** Weighted median of the agreeing sources' start times. */
    val expectedStart: Long?,
    /** Median of the agreeing sources' peak intensities, mm/h. */
    val peakIntensity: Double?,
    /** Median expected amount in the window across agreeing sources, mm. */
    val expectedTotal: Double?,
) {
    val agreeing: Int get() = verdicts.count { it.predictsRain }
    val available: Int get() = verdicts.size
    fun isRainingNow(now: Long): Boolean = willRain && expectedStart != null && expectedStart <= now + 10 * 60
}

/** A hazard check (heavy rain, storm, snow, multi-day rain): who agrees and how much. */
data class HazardOutlook(
    val kind: AlertKind,
    val triggered: Boolean,
    val confidence: Double,
    val agreeing: Int,
    val available: Int,
    /** When the hazard starts, if known. */
    val start: Long?,
    /** Median amount: mm for rain kinds, cm for snow, peak mm/h for heavy rain. */
    val amount: Double?,
    /** Secondary figure: 24 h total for heavy rain. */
    val amount2: Double? = null,
)

/** One bar in the "next hours" chart. */
data class TimelineSlot(
    val start: Long,
    val duration: Long,
    /** Weighted share of sources predicting rain, 0–1. */
    val chance: Double,
    /** Median intensity across sources that publish amounts, mm/h. */
    val intensity: Double?,
    val sourceCount: Int,
    val temp: Double? = null,
    val code: Int? = null,
)

@kotlinx.serialization.Serializable
enum class AlertKind(val severe: Boolean) {
    RAIN_SOON(false),
    RAIN_STOPPING(false),
    HEAVY_RAIN(true),
    STORM(true),
    SNOW(false),
    RAIN_AHEAD(false),
}

object RainAnalyzer {
    fun isRainy(s: PrecipSample, settings: AlertSettings): Boolean =
        (s.intensity != null && s.intensity >= settings.minIntensity) ||
            (s.probability != null && s.probability >= settings.minProbability)

    fun isStorm(code: Int?): Boolean = code != null && code in 95..99

    private fun usable(report: ForecastReport, settings: AlertSettings): List<Pair<SourceId, List<PrecipSample>>> =
        report.outcomes
            .filterKeys { it in settings.enabledSources }
            .mapNotNull { (source, outcome) -> outcome.samples?.let { source to it } }

    fun verdict(source: SourceId, samples: List<PrecipSample>, from: Long, to: Long, settings: AlertSettings): SourceVerdict? {
        val inWindow = samples.filter { it.end > from && it.start < to }.sortedBy { it.start }
        val coverage = coveredFraction(from, to, inWindow)
        if (coverage <= 0) return null
        val rainy = inWindow.filter { isRainy(it, settings) }
        return SourceVerdict(
            source = source,
            predictsRain = rainy.isNotEmpty(),
            firstRain = rainy.firstOrNull()?.let { maxOf(it.start, from) },
            peakIntensity = inWindow.mapNotNull { it.intensity }.maxOrNull(),
            peakProbability = inWindow.mapNotNull { it.probability }.maxOrNull(),
            total = inWindow.sumOf { it.amountBetween(from, to) },
            coverage = coverage,
        )
    }

    fun outlook(report: ForecastReport, settings: AlertSettings, now: Long, lookahead: Long = settings.lookaheadSeconds): RainOutlook {
        val end = now + lookahead
        val verdicts = usable(report, settings)
            .mapNotNull { (source, samples) -> verdict(source, samples, now, end, settings) }
            .sortedWith(verdictOrder)
        // A dry source only vouches for the part of the window it covers.
        val total = verdicts.sumOf { if (it.predictsRain) it.source.weight else it.source.weight * it.coverage }
        val agreeing = verdicts.filter { it.predictsRain }
        val agreeingWeight = agreeing.sumOf { it.source.weight }
        val confidence = if (total > 0) minOf(1.0, agreeingWeight / total) else 0.0
        return RainOutlook(
            windowStart = now,
            windowEnd = end,
            verdicts = verdicts,
            confidence = confidence,
            willRain = agreeing.isNotEmpty() && confidence + 1e-9 >= settings.requiredAgreement,
            expectedStart = weightedMedian(agreeing.mapNotNull { v -> v.firstRain?.let { it to v.source.weight } }),
            peakIntensity = median(agreeing.mapNotNull { it.peakIntensity }),
            expectedTotal = median(agreeing.filter { it.source.kind != SourceKind.ENSEMBLE || it.total > 0 }.map { it.total }),
        )
    }

    /**
     * Rain ending: it is raining now (by consensus) and most sources that see rain now
     * expect at least 30 dry minutes to begin within the next hour.
     */
    fun stopping(report: ForecastReport, settings: AlertSettings, now: Long): HazardOutlook {
        val sources = usable(report, settings)
        val rainingNow = sources.filter { (_, samples) ->
            samples.any { it.start <= now && it.end > now && isRainy(it, settings) }
        }
        val nowOutlook = outlook(report, settings, now, 15 * 60)
        val stops = rainingNow.mapNotNull { (source, samples) ->
            val ordered = samples.filter { it.end > now && it.start < now + 3 * 3600 }.sortedBy { it.start }
            val stop = ordered.firstOrNull { s ->
                s.start > now && !isRainy(s, settings) &&
                    ordered.filter { it.start >= s.start && it.start < s.start + 1800 }.none { isRainy(it, settings) }
            }?.start
            if (stop != null && stop <= now + 3600) source to stop else null
        }
        val totalWeight = rainingNow.sumOf { it.first.weight }
        val stopWeight = stops.sumOf { it.first.weight }
        val confidence = if (totalWeight > 0) stopWeight / totalWeight else 0.0
        return HazardOutlook(
            kind = AlertKind.RAIN_STOPPING,
            triggered = nowOutlook.isRainingNow(now) && stops.isNotEmpty() && confidence + 1e-9 >= settings.requiredAgreement,
            confidence = confidence,
            agreeing = stops.size,
            available = rainingNow.size,
            start = weightedMedian(stops.map { it.second to it.first.weight }),
            amount = null,
        )
    }

    /** Heavy rain / flash-flood risk in the next 24 h: a peak hour or a 24 h total above the thresholds. */
    fun heavyRain(report: ForecastReport, settings: AlertSettings, now: Long): HazardOutlook {
        val from = now
        val to = now + 24 * 3600
        val votes = usable(report, settings).mapNotNull { (source, samples) ->
            val window = samples.filter { it.end > from && it.start < to }
            if (coveredFraction(from, to, window) < 0.5) return@mapNotNull null
            // Use hourly amounts: average 15-minute steps up to whole hours so sources compare fairly.
            val hourly = hourlyPeak(window)
            val total = window.sumOf { it.amountBetween(from, to) }
            val firstHeavyHour = window.firstOrNull { it.intensity != null && hourlyIntensityAt(window, it.start) >= settings.heavyHourly }?.start
            val start = firstHeavyHour ?: window.firstOrNull { isRainy(it, settings) }?.start
            Vote(source, hourly >= settings.heavyHourly || total >= settings.heavyDaily, start, hourly, total)
        }
        return combine(AlertKind.HEAVY_RAIN, votes, settings.severeAgreement, now) { it.peak }
            .let { o -> o.copy(amount2 = median(votes.filter { it.yes }.map { it.total })) }
    }

    /** Thunderstorms in the next 12 h, from sources that publish weather codes. */
    fun storm(report: ForecastReport, settings: AlertSettings, now: Long): HazardOutlook {
        val from = now
        val to = now + 12 * 3600
        val votes = usable(report, settings).mapNotNull { (source, samples) ->
            val window = samples.filter { it.end > from && it.start < to }
            if (window.none { it.code != null }) return@mapNotNull null
            val first = window.firstOrNull { isStorm(it.code) }
            Vote(source, first != null, first?.let { maxOf(it.start, from) }, 0.0, 0.0)
        }
        return combine(AlertKind.STORM, votes, settings.severeAgreement, now) { null }
    }

    /** Snow in the next 24 h (Atlas towns), from sources that publish snowfall. */
    fun snow(report: ForecastReport, settings: AlertSettings, now: Long): HazardOutlook {
        val from = now
        val to = now + 24 * 3600
        val votes = usable(report, settings).mapNotNull { (source, samples) ->
            val window = samples.filter { it.end > from && it.start < to }
            if (window.none { it.snow != null }) return@mapNotNull null
            val total = window.sumOf { it.snowBetween(from, to) }
            val first = window.firstOrNull { (it.snow ?: 0.0) > 0.05 }
            Vote(source, total >= settings.snowThresholdCm, first?.let { maxOf(it.start, from) }, 0.0, total)
        }
        return combine(AlertKind.SNOW, votes, settings.severeAgreement, now) { it.total }
    }

    /** Significant rain over the next few days — the farmer's question. */
    fun rainAhead(report: ForecastReport, settings: AlertSettings, now: Long): HazardOutlook {
        val from = now
        val to = now + settings.aheadDays * 86_400L
        val votes = usable(report, settings).mapNotNull { (source, samples) ->
            val window = samples.filter { it.end > from && it.start < to }
            if (coveredFraction(from, to, window) < 0.9) return@mapNotNull null
            val total = window.sumOf { it.amountBetween(from, to) }
            Vote(source, total >= settings.aheadMm, window.firstOrNull { isRainy(it, settings) }?.start, 0.0, total)
        }.toMutableList()
        // Best match's daily totals reach 7 days even when its hourly data was trimmed.
        if (votes.none { it.source == SourceId.BEST_MATCH } && SourceId.BEST_MATCH in settings.enabledSources) {
            val days = report.daily.filter { it.date + 86_400 > from && it.date < to }
            if (days.size >= settings.aheadDays) {
                val total = days.take(settings.aheadDays).sumOf { it.precipitationSum ?: 0.0 }
                votes += Vote(SourceId.BEST_MATCH, total >= settings.aheadMm, null, 0.0, total)
            }
        }
        return combine(AlertKind.RAIN_AHEAD, votes, settings.requiredAgreement, now) { it.total }
            .copy(amount2 = settings.aheadDays.toDouble())
    }

    fun hazards(report: ForecastReport, settings: AlertSettings, now: Long): List<HazardOutlook> = listOf(
        heavyRain(report, settings, now),
        storm(report, settings, now),
        snow(report, settings, now),
        rainAhead(report, settings, now),
        stopping(report, settings, now),
    )

    /** Per-slot consensus for charting the next [hours] hours. */
    fun timeline(report: ForecastReport, settings: AlertSettings, now: Long, hours: Int = 24, step: Long = 3600): List<TimelineSlot> {
        val first = (now / step) * step
        val series = usable(report, settings)
        val best = report.outcomes[SourceId.BEST_MATCH]?.samples.orEmpty()
        return (0 until (hours * 3600L / step).toInt()).map { i ->
            val start = first + i * step
            val end = start + step
            var total = 0.0
            var rainy = 0.0
            var count = 0
            val intensities = mutableListOf<Double>()
            for ((source, samples) in series) {
                val overlapping = samples.filter { it.end > start && it.start < end }
                if (overlapping.isEmpty()) continue
                count++
                total += source.weight
                if (overlapping.any { isRainy(it, settings) }) rainy += source.weight
                val amount = overlapping.sumOf { it.amountBetween(start, end) }
                if (overlapping.any { it.intensity != null }) intensities += amount * 3600.0 / step
            }
            val bestHere = best.filter { it.end > start && it.start < end }
            TimelineSlot(
                start = start,
                duration = step,
                chance = if (total > 0) rainy / total else 0.0,
                intensity = median(intensities),
                sourceCount = count,
                temp = bestHere.mapNotNull { it.temp }.average().takeUnless { it.isNaN() },
                code = bestHere.mapNotNull { it.code }.maxOrNull(),
            )
        }
    }

    // Helpers

    private data class Vote(val source: SourceId, val yes: Boolean, val start: Long?, val peak: Double, val total: Double)

    private fun combine(kind: AlertKind, votes: List<Vote>, needed: Double, now: Long, amount: (Vote) -> Double?): HazardOutlook {
        val total = votes.sumOf { it.source.weight }
        val yes = votes.filter { it.yes }
        val confidence = if (total > 0) yes.sumOf { it.source.weight } / total else 0.0
        return HazardOutlook(
            kind = kind,
            triggered = yes.isNotEmpty() && confidence + 1e-9 >= needed,
            confidence = confidence,
            agreeing = yes.size,
            available = votes.size,
            start = weightedMedian(yes.mapNotNull { v -> v.start?.let { maxOf(it, now) to v.source.weight } }),
            amount = median(yes.mapNotNull(amount)),
        )
    }

    /**
     * Heaviest 60 minutes of rain (mm), sliding from every step start. Clock hours would
     * split a downpour that straddles :00 and under-read it; this compares 15-minute
     * and hourly sources fairly.
     */
    private fun hourlyPeak(samples: List<PrecipSample>): Double =
        samples.maxOfOrNull { s -> hourlyIntensityAt(samples, s.start) } ?: 0.0

    /** Rain (mm) in the 60 minutes from [t]. */
    private fun hourlyIntensityAt(samples: List<PrecipSample>, t: Long): Double =
        samples.sumOf { it.amountBetween(t, t + 3600) }

    private val verdictOrder = Comparator<SourceVerdict> { a, b ->
        when {
            a.predictsRain != b.predictsRain -> if (a.predictsRain) -1 else 1
            a.firstRain != null && b.firstRain != null && a.firstRain != b.firstRain -> a.firstRain.compareTo(b.firstRain)
            a.source.weight != b.source.weight -> b.source.weight.compareTo(a.source.weight)
            else -> a.source.ordinal.compareTo(b.source.ordinal)
        }
    }

    fun coveredFraction(from: Long, to: Long, samples: List<PrecipSample>): Double {
        if (to <= from) return 0.0
        var covered = 0L
        var cursor = from
        for (s in samples.sortedBy { it.start }) {
            val start = maxOf(s.start, cursor)
            val end = minOf(s.end, to)
            if (end > start) {
                covered += end - start
                cursor = end
            }
        }
        return minOf(1.0, covered.toDouble() / (to - from))
    }

    fun weightedMedian(points: List<Pair<Long, Double>>): Long? {
        if (points.isEmpty()) return null
        val sorted = points.sortedBy { it.first }
        val half = sorted.sumOf { it.second } / 2
        var running = 0.0
        for ((t, w) in sorted) {
            running += w
            if (running >= half) return t
        }
        return sorted.last().first
    }

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 0) (s[mid - 1] + s[mid]) / 2 else s[mid]
    }
}

/** Wording for precipitation rates (mm/h), roughly following the AMS glossary. */
enum class RainIntensity {
    VERY_LIGHT, LIGHT, MODERATE, HEAVY, VIOLENT;

    companion object {
        fun of(mmPerHour: Double): RainIntensity = when {
            mmPerHour < 0.5 -> VERY_LIGHT
            mmPerHour < 2.5 -> LIGHT
            mmPerHour < 7.6 -> MODERATE
            mmPerHour < 50 -> HEAVY
            else -> VIOLENT
        }
    }
}
