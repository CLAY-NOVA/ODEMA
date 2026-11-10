package ma.odema.core.farm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import ma.odema.core.GeoPoint
import ma.odema.core.HttpClient
import ma.odema.core.OdemaJson
import ma.odema.core.ProviderException
import ma.odema.core.buildUrl
import ma.odema.core.fetch
import ma.odema.core.providers.asDouble
import ma.odema.core.providers.asString
import java.time.LocalDate
import java.util.Locale

/**
 * Daily rainfall (mm, local calendar days) for one location, from [firstDay] onwards.
 * Days from [provisionalFrom] on come from the forecast model's recent analysis and
 * are replaced by the reanalysis archive once it catches up (~5 days later).
 */
@Serializable
data class RainSeries(
    val firstDay: Long,
    val values: List<Double?>,
    val provisionalFrom: Long? = null,
    val updatedAt: Long = 0,
) {
    val lastDay: Long get() = firstDay + values.size - 1

    operator fun get(day: Long): Double? {
        val i = day - firstDay
        return if (i in values.indices) values[i.toInt()] else null
    }

    /** First day with data. */
    val firstDataDay: Long? get() = values.indexOfFirst { it != null }.takeIf { it >= 0 }?.let { firstDay + it }

    /**
     * Merges new values. Archive data ([provisional] = false) always wins; provisional
     * data only fills days the archive hasn't confirmed yet, and marks them provisional.
     */
    fun merge(start: Long, incoming: List<Double?>, provisional: Boolean, now: Long): RainSeries {
        if (incoming.isEmpty()) return this
        val newFirst = if (values.isEmpty()) start else minOf(firstDay, start)
        val newLast = if (values.isEmpty()) start + incoming.size - 1 else maxOf(lastDay, start + incoming.size - 1)
        val out = MutableList<Double?>((newLast - newFirst + 1).toInt()) { null }
        values.forEachIndexed { i, v -> out[(firstDay - newFirst + i).toInt()] = v }
        var prov = provisionalFrom
        if (provisional) {
            var firstWritten: Long? = null
            incoming.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val day = start + i
                val idx = (day - newFirst).toInt()
                val confirmed = out[idx] != null && (prov == null || day < prov)
                if (!confirmed) {
                    out[idx] = v
                    if (firstWritten == null) firstWritten = day
                }
            }
            firstWritten?.let { prov = if (prov == null) it else minOf(prov!!, it) }
        } else {
            var lastWritten: Long? = null
            incoming.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val day = start + i
                out[(day - newFirst).toInt()] = v
                lastWritten = day
            }
            val p = prov
            val lw = lastWritten
            if (p != null && lw != null && lw >= p) prov = lw + 1
        }
        if (prov != null && prov!! > newLast) prov = null
        return RainSeries(newFirst, out, prov, now)
    }

    /** Days in [from, to] without data. */
    fun missing(from: Long, to: Long): Int = (from..to).count { this[it] == null }

    companion object {
        val EMPTY = RainSeries(LocalDate.of(1940, 1, 1).toEpochDay(), emptyList())
    }
}

/** Rain history plus the farm's own gauge readings, with the statistics agronomists use. */
class RainHistory(private val series: RainSeries?, gauges: List<GaugeReading> = emptyList()) {
    private val dailyGauge: Map<Long, Double> = gauges.filter { it.isDaily }.associate { it.date to it.mm }

    /** Gauge reading when there is one for that day, else the model value. */
    fun day(d: Long): Double? = dailyGauge[d] ?: series?.get(d)

    val hasData: Boolean get() = series?.firstDataDay != null || dailyGauge.isNotEmpty()

    val firstDay: Long? get() = series?.firstDataDay
    val firstYear: Int? get() = firstDay?.let { LocalDate.ofEpochDay(it).year }

    data class Total(val mm: Double, val days: Int, val covered: Int) {
        val complete: Boolean get() = days > 0 && covered >= days * 0.95
    }

    fun sum(from: Long, to: Long): Total {
        var mm = 0.0
        var covered = 0
        for (d in from..to) day(d)?.let { mm += it; covered++ }
        return Total(mm, (to - from + 1).toInt().coerceAtLeast(0), covered)
    }

    /** Totals of every campaign with ≥95 % of days present. */
    fun campaignTotals(today: Long): Map<Int, Double> {
        val first = firstDay ?: return emptyMap()
        val firstCampaign = Campaigns.ofDay(first)
        val lastComplete = Campaigns.ofDay(today) - 1
        return (firstCampaign..lastComplete).mapNotNull { c ->
            val t = sum(Campaigns.start(c).toEpochDay(), Campaigns.end(c).toEpochDay())
            if (t.complete) c to t.mm else null
        }.toMap()
    }

    /** Campaign so far (1 September → [today]). */
    fun campaignToDate(today: Long): Total {
        val c = Campaigns.ofDay(today)
        return sum(Campaigns.start(c).toEpochDay(), today)
    }

    /** Same period of earlier campaigns: day [k] of the campaign. */
    private fun sameWindow(c: Int, today: Long): Total {
        val k = today - Campaigns.start(Campaigns.ofDay(today)).toEpochDay()
        val start = Campaigns.start(c).toEpochDay()
        return sum(start, minOf(start + k, Campaigns.end(c).toEpochDay()))
    }

    /** Campaigns used for "normal": WMO 1991–2020 when available, otherwise every complete one. */
    fun normalCampaigns(today: Long): List<Int> {
        val all = campaignTotals(today).keys.sorted()
        val wmo = all.filter { it in 1991..2019 }
        return if (wmo.size >= 20) wmo else all
    }

    /** Average rain from 1 September to the same date over [normalCampaigns]. */
    fun normalToDate(today: Long): Double? {
        val years = normalCampaigns(today)
        if (years.isEmpty()) return null
        val sums = years.map { sameWindow(it, today) }.filter { it.complete }.map { it.mm }
        return sums.takeIf { it.isNotEmpty() }?.average()
    }

    fun lastCampaignToDate(today: Long): Total = sameWindow(Campaigns.ofDay(today) - 1, today)

    fun normalCampaign(today: Long): Double? {
        val totals = campaignTotals(today)
        return normalCampaigns(today).mapNotNull { totals[it] }.takeIf { it.isNotEmpty() }?.average()
    }

    /** Twelve monthly totals, September → August, for campaign [c] (null where no data). */
    fun monthly(c: Int): List<Double?> = (0 until 12).map { m ->
        val start = Campaigns.start(c).plusMonths(m.toLong())
        val end = start.plusMonths(1).minusDays(1)
        val t = sum(start.toEpochDay(), end.toEpochDay())
        if (t.covered == 0) null else t.mm
    }

    fun monthlyNormal(today: Long): List<Double?> {
        val years = normalCampaigns(today)
        if (years.isEmpty()) return List(12) { null }
        val months = years.map { monthly(it) }
        return (0 until 12).map { m -> months.mapNotNull { it[m] }.takeIf { it.isNotEmpty() }?.average() }
    }

    /** Running total from 1 September, one value per day up to [until] (or the campaign end). */
    fun cumulative(c: Int, until: Long? = null): List<Double> {
        val start = Campaigns.start(c).toEpochDay()
        val end = minOf(Campaigns.end(c).toEpochDay(), until ?: Long.MAX_VALUE)
        var acc = 0.0
        return (start..end).map { d -> acc += day(d) ?: 0.0; acc }
    }

    /** Average running total over the normal campaigns (366 values). */
    fun cumulativeNormal(today: Long): List<Double> {
        val years = normalCampaigns(today)
        if (years.isEmpty()) return emptyList()
        val curves = years.map { cumulative(it) }
        val len = curves.maxOf { it.size }
        return (0 until len).map { i -> curves.mapNotNull { it.getOrNull(i) ?: it.lastOrNull() }.average() }
    }

    /** Last day with at least [threshold] mm, looking back up to a year. */
    fun lastRain(today: Long, threshold: Double = 1.0): Pair<Long, Double>? {
        for (d in today downTo today - 366) {
            val v = day(d) ?: continue
            if (v >= threshold) return d to v
        }
        return null
    }

    fun rainyDays(from: Long, to: Long, threshold: Double = 1.0): Int = (from..to).count { (day(it) ?: 0.0) >= threshold }

    fun wettestDay(from: Long, to: Long): Pair<Long, Double>? =
        (from..to).mapNotNull { d -> day(d)?.let { d to it } }.maxByOrNull { it.second }?.takeIf { it.second > 0 }

    /** Wettest and driest complete campaigns on record. */
    fun extremes(today: Long): Pair<Pair<Int, Double>, Pair<Int, Double>>? {
        val totals = campaignTotals(today)
        if (totals.isEmpty()) return null
        return totals.maxBy { it.value }.toPair() to totals.minBy { it.value }.toPair()
    }

    /** Daily values for CSV export. */
    fun rows(from: Long, to: Long): List<Pair<Long, Double?>> = (from..to).map { it to day(it) }
}

/** One chunk of history to download. */
data class HistoryChunk(val key: String, val point: GeoPoint, val from: Long, val to: Long)

/**
 * Open-Meteo's Historical Weather API: ERA5 (from 1940, 25 km), ERA5-Land (from 1950,
 * 9 km) and ECMWF IFS (from 2017, 9 km), blended into the best series per date.
 * https://open-meteo.com/en/docs/historical-weather-api — free, CC BY 4.0.
 */
object RainArchive {
    val EARLIEST: LocalDate = LocalDate.of(1940, 1, 1)
    /** The archive lags reality by about five days. */
    const val LAG_DAYS = 5L
    /** Years per request: Open-Meteo counts ~1 call per 2 weeks of data, so 10 years ≈ 260 calls. */
    const val CHUNK_YEARS = 10

    fun url(point: GeoPoint, from: LocalDate, to: LocalDate): String = buildUrl(
        "https://archive-api.open-meteo.com/v1/archive",
        "latitude" to point.lat4,
        "longitude" to point.lon4,
        "start_date" to from.toString(),
        "end_date" to to.toString(),
        "daily" to "precipitation_sum",
        "timezone" to "Africa/Casablanca",
    )

    /**
     * Exact ranges still missing (or only provisional) for [key], split into requests of at
     * most [CHUNK_YEARS] years, newest first so recent years arrive before the 1940s.
     */
    fun plan(key: String, point: GeoPoint, series: RainSeries?, today: Long): List<HistoryChunk> {
        val end = today - LAG_DAYS
        val earliest = EARLIEST.toEpochDay()
        val prov = series?.provisionalFrom
        fun needs(d: Long) = series == null || series[d] == null || (prov != null && d >= prov)
        val ranges = mutableListOf<LongRange>()
        var d = end
        while (d >= earliest) {
            if (needs(d)) {
                val hi = d
                while (d >= earliest && needs(d)) d--
                ranges += (d + 1)..hi
            } else {
                d--
            }
        }
        val chunks = mutableListOf<HistoryChunk>()
        for (r in ranges) {
            var to = r.last
            while (to >= r.first) {
                val from = maxOf(r.first, LocalDate.ofEpochDay(to).minusYears(CHUNK_YEARS.toLong()).plusDays(1).toEpochDay())
                chunks += HistoryChunk(key, point, from, to)
                to = from - 1
            }
        }
        return chunks
    }

    suspend fun fetch(chunk: HistoryChunk, client: HttpClient): Pair<Long, List<Double?>> {
        val body = client.fetch(url(chunk.point, LocalDate.ofEpochDay(chunk.from), LocalDate.ofEpochDay(chunk.to)))
        return parseDaily(body)
    }

    /** Parses Open-Meteo `daily` with ISO dates → (first epoch day, values). */
    fun parseDaily(body: String, field: String = "precipitation_sum"): Pair<Long, List<Double?>> {
        val daily = OdemaJson.parseToJsonElement(body).jsonObject["daily"] as? JsonObject
            ?: throw ProviderException("no daily data")
        val times = (daily["time"] as? JsonArray)?.map { it.asString() } ?: throw ProviderException("no time axis")
        // Negative rain is never valid: treat it as missing rather than let it skew totals.
        val values = (daily[field] as? JsonArray)?.map { it.asDouble()?.takeIf { v -> v >= 0 } } ?: throw ProviderException("no $field")
        if (times.isEmpty()) return 0L to emptyList()
        val first = LocalDate.parse(times.first()).toEpochDay()
        return first to values
    }
}

/** Recent days (archive lag) and the next week for many parcels in one Open-Meteo request. */
object RecentRain {
    const val PAST_DAYS = 92
    const val FORECAST_DAYS = 8

    fun url(points: List<GeoPoint>): String = buildUrl(
        "https://api.open-meteo.com/v1/forecast",
        "latitude" to points.joinToString(",") { it.lat4 },
        "longitude" to points.joinToString(",") { it.lon4 },
        "daily" to "precipitation_sum,precipitation_probability_max",
        "past_days" to PAST_DAYS.toString(),
        "forecast_days" to FORECAST_DAYS.toString(),
        "timezone" to "Africa/Casablanca",
    )

    data class Result(val firstDay: Long, val precipitation: List<Double?>, val probability: List<Double?>)

    fun parse(body: String): List<Result> {
        val root = OdemaJson.parseToJsonElement(body)
        val objects = when (root) {
            is JsonArray -> root.map { it.jsonObject }
            is JsonObject -> listOf(root)
            else -> emptyList()
        }
        return objects.map { o ->
            val text = o.toString()
            val (first, rain) = RainArchive.parseDaily(text)
            val (_, prob) = RainArchive.parseDaily(text, "precipitation_probability_max")
            Result(first, rain, prob)
        }
    }

    suspend fun fetch(points: List<GeoPoint>, client: HttpClient): List<Result> = parse(client.fetch(url(points)))
}

/** The next days' forecast for a parcel (from [RecentRain]). */
@Serializable
data class DailyOutlook(val firstDay: Long, val mm: List<Double?>, val probability: List<Double?>, val fetchedAt: Long) {
    fun total(from: Long, days: Int): Double = (0 until days).sumOf { mm.getOrNull((from - firstDay + it).toInt()) ?: 0.0 }
}

fun String.toEpochDayOrNull(): Long? = runCatching { LocalDate.parse(this).toEpochDay() }.getOrNull()

fun Long.isoDate(): String = LocalDate.ofEpochDay(this).toString()

fun formatHa(v: Double): String = "%.2f".format(Locale.ROOT, v)
