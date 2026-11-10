package ma.odema.core.farm

import kotlinx.coroutines.CancellationException
import ma.odema.core.HttpClient
import ma.odema.core.ProviderException

/** Everything the parcel dashboard shows, computed once. */
data class ParcelSummary(
    val parcel: Parcel,
    val campaign: Int,
    val campaignToDate: RainHistory.Total,
    val normalToDate: Double?,
    val lastCampaignToDate: RainHistory.Total,
    val normalCampaign: Double?,
    val last7: Double,
    val last30: Double,
    val lastRain: Pair<Long, Double>?,
    val daysSinceRain: Long?,
    val rainyDaysCampaign: Int,
    val wettestDayCampaign: Pair<Long, Double>?,
    val next7: Double?,
    val crops: List<CropEntry>,
    val firstYear: Int?,
    val completeCampaigns: Int,
    /** Rain since the earliest sowing date of the current campaign's crops. */
    val sinceSowing: Pair<Long, Double>?,
    val gaugeCount: Int,
) {
    /** Campaign to date as a share of normal (1.0 = normal). */
    val ratioToNormal: Double? get() = normalToDate?.takeIf { it > 0.5 }?.let { campaignToDate.mm / it }
    val hasHistory: Boolean get() = campaignToDate.covered > 0
}

data class CropArea(val crop: Crop, val areaHa: Double, val parcels: Int)

/** The whole farm at a glance. */
data class FarmSummary(
    val campaign: Int,
    val parcels: List<ParcelSummary>,
    val totalAreaHa: Double,
    val plantedAreaHa: Double,
    val cropAreas: List<CropArea>,
    /** Area-weighted averages across parcels with data. */
    val campaignToDate: Double?,
    val normalToDate: Double?,
    val lastCampaignToDate: Double?,
    val last7: Double?,
    val next7: Double?,
) {
    val ratioToNormal: Double? get() = if (campaignToDate != null && normalToDate != null && normalToDate > 0.5) campaignToDate / normalToDate else null
    val wettest: ParcelSummary? get() = parcels.filter { it.hasHistory }.maxByOrNull { it.campaignToDate.mm }
    val driest: ParcelSummary? get() = parcels.filter { it.hasHistory }.minByOrNull { it.campaignToDate.mm }
}

object FarmAnalytics {
    fun parcel(
        parcel: Parcel,
        series: RainSeries?,
        data: FarmData,
        outlook: DailyOutlook?,
        today: Long,
        campaign: Int = Campaigns.ofDay(today),
    ): ParcelSummary {
        val gauges = data.gauges.filter { it.parcelId == parcel.id }
        val h = RainHistory(series, gauges)
        val crops = data.crops.filter { it.parcelId == parcel.id && it.campaign == campaign }
        val start = Campaigns.start(campaign).toEpochDay()
        val lastRain = h.lastRain(today)
        val sowing = crops.mapNotNull { it.sowingDate }.minOrNull()
        return ParcelSummary(
            parcel = parcel,
            campaign = campaign,
            campaignToDate = h.campaignToDate(today),
            normalToDate = h.normalToDate(today),
            lastCampaignToDate = h.lastCampaignToDate(today),
            normalCampaign = h.normalCampaign(today),
            last7 = h.sum(today - 6, today).mm,
            last30 = h.sum(today - 29, today).mm,
            lastRain = lastRain,
            daysSinceRain = lastRain?.let { today - it.first },
            rainyDaysCampaign = h.rainyDays(start, today),
            wettestDayCampaign = h.wettestDay(start, today),
            next7 = outlook?.total(today + 1, 7),
            crops = crops,
            firstYear = h.firstYear,
            completeCampaigns = h.campaignTotals(today).size,
            sinceSowing = sowing?.takeIf { it <= today }?.let { it to h.sum(it, today).mm },
            gaugeCount = gauges.size,
        )
    }

    fun farm(
        data: FarmData,
        series: Map<String, RainSeries>,
        outlooks: Map<String, DailyOutlook>,
        today: Long,
        campaign: Int = Campaigns.ofDay(today),
    ): FarmSummary {
        val summaries = data.parcels.map { p ->
            parcel(p, p.historyKey?.let(series::get), data, p.historyKey?.let(outlooks::get), today, campaign)
        }
        fun weighted(pick: (ParcelSummary) -> Double?): Double? {
            val pairs = summaries.mapNotNull { s -> pick(s)?.let { it to (s.parcel.areaHa ?: 1.0) } }
            val w = pairs.sumOf { it.second }
            return if (pairs.isEmpty() || w <= 0) null else pairs.sumOf { it.first * it.second } / w
        }
        val entries = data.crops.filter { it.campaign == campaign }
        val cropAreas = entries.groupBy { it.cropKind }.map { (crop, list) ->
            CropArea(crop, list.sumOf { it.areaHa ?: 0.0 }, list.map { it.parcelId }.distinct().size)
        }.sortedByDescending { it.areaHa }
        return FarmSummary(
            campaign = campaign,
            parcels = summaries,
            totalAreaHa = data.parcels.sumOf { it.areaHa ?: 0.0 },
            plantedAreaHa = entries.sumOf { it.areaHa ?: 0.0 },
            cropAreas = cropAreas,
            campaignToDate = weighted { it.takeIf { s -> s.hasHistory }?.campaignToDate?.mm },
            normalToDate = weighted { it.normalToDate },
            lastCampaignToDate = weighted { s -> s.lastCampaignToDate.takeIf { it.covered > 0 }?.mm },
            last7 = weighted { it.takeIf { s -> s.hasHistory }?.last7 },
            next7 = weighted { it.next7 },
        )
    }

    /** Crop sequence per parcel over [campaigns] (the rotation table). */
    fun rotation(data: FarmData, campaigns: IntRange): Map<String, Map<Int, List<CropEntry>>> =
        data.parcels.associate { p ->
            p.id to campaigns.associateWith { c -> data.crops.filter { it.parcelId == p.id && it.campaign == c } }
        }

    /** Gauge vs model over each gauge reading's period: (reading, model mm or null). */
    fun gaugeComparison(parcel: Parcel, data: FarmData, series: RainSeries?): List<Pair<GaugeReading, Double?>> {
        val model = RainHistory(series)
        return data.gauges.filter { it.parcelId == parcel.id }.sortedByDescending { it.date }.map { g ->
            val t = model.sum(g.fromDate, g.date)
            g to t.takeIf { it.complete }?.mm
        }
    }

    /** CSV of daily rain for a parcel: date, mm, source. */
    fun csv(parcel: Parcel, series: RainSeries?, data: FarmData, from: Long, to: Long): String {
        val gauges = data.gauges.filter { it.parcelId == parcel.id && it.isDaily }.associateBy { it.date }
        val sb = StringBuilder("date,rain_mm,source\n")
        for (d in from..to) {
            val g = gauges[d]
            val v = g?.mm ?: series?.get(d)
            val source = when {
                g != null -> "gauge"
                v == null -> ""
                series?.provisionalFrom != null && d >= series.provisionalFrom -> "model-recent"
                else -> "reanalysis"
            }
            sb.append(d.isoDate()).append(',').append(v?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "").append(',').append(source).append('\n')
        }
        return sb.toString()
    }
}

/**
 * Brings every parcel's rain history up to date: recent days + forecast in one request,
 * then archive chunks newest-first until done, the [budget] is spent, or the API says
 * the daily limit is reached (we then continue tomorrow).
 */
class FarmSync(private val client: HttpClient) {
    data class Result(
        val series: Map<String, RainSeries>,
        val outlooks: Map<String, DailyOutlook>,
        val chunksFetched: Int,
        val chunksRemaining: Int,
        val rateLimited: Boolean,
        val error: String?,
    )

    suspend fun run(
        parcels: List<Parcel>,
        series: Map<String, RainSeries>,
        outlooks: Map<String, DailyOutlook>,
        today: Long,
        now: Long,
        budget: Int = 20,
        onProgress: (Map<String, RainSeries>) -> Unit = {},
    ): Result {
        val points = parcels.mapNotNull { p -> p.historyKey?.let { k -> k to p.location!! } }.distinctBy { it.first }
        val updated = series.toMutableMap()
        val newOutlooks = outlooks.toMutableMap()
        var error: String? = null
        var limited = false

        // 1. Recent 92 days + next 8 days, all parcels in one call.
        if (points.isNotEmpty()) {
            try {
                val results = RecentRain.fetch(points.map { it.second }, client)
                points.zip(results).forEach { (kp, r) ->
                    val (key, _) = kp
                    val pastCount = (today - r.firstDay).toInt().coerceIn(0, r.precipitation.size)
                    // Today is incomplete: keep it out of the history, it lives in the outlook.
                    val base = updated[key] ?: RainSeries.EMPTY
                    updated[key] = base.merge(r.firstDay, r.precipitation.take(pastCount), provisional = true, now = now)
                    newOutlooks[key] = DailyOutlook(r.firstDay, r.precipitation, r.probability, now)
                }
                onProgress(updated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException) {
                error = e.message
                limited = e.message?.contains("429") == true
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
        }

        // 2. Archive back to 1940, newest first, round-robin across parcels.
        var fetched = 0
        if (!limited) {
            val queue = points.map { (key, p) -> RainArchive.plan(key, p, updated[key], today) }
            val order = mutableListOf<HistoryChunk>()
            val longest = queue.maxOfOrNull { it.size } ?: 0
            for (i in 0 until longest) queue.forEach { list -> list.getOrNull(i)?.let(order::add) }
            for (chunk in order) {
                if (fetched >= budget) break
                try {
                    val (first, values) = RainArchive.fetch(chunk, client)
                    updated[chunk.key] = (updated[chunk.key] ?: RainSeries.EMPTY).merge(first, values, provisional = false, now = now)
                    fetched++
                    onProgress(updated)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ProviderException) {
                    error = e.message
                    if (e.message?.contains("429") == true) {
                        limited = true
                        break
                    }
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                    break
                }
            }
        }
        val remaining = points.sumOf { (key, p) -> RainArchive.plan(key, p, updated[key], today).size }
        return Result(updated, newOutlooks, fetched, remaining, limited, error)
    }
}
