package ma.odema.core.ops

import ma.odema.core.farm.Campaigns
import ma.odema.core.farm.FarmData

data class YieldRow(
    val parcelId: String,
    val block: String,
    val crop: String,
    val tonnes: Double,
    val areaHa: Double?,
    val revenue: Double?,
) {
    val tPerHa: Double? get() = areaHa?.takeIf { it > 0 }?.let { tonnes / it }
}

data class ParcelCost(val inputs: Double = 0.0, val other: Double = 0.0, val labourDays: Double = 0.0) {
    val total: Double get() = inputs + other
}

data class LabourSummary(
    /** Days present per staff id. */
    val daysByStaff: Map<String, Int>,
    val casualDays: Int,
    /** MAD, from daily rates (staff) and casual rates. */
    val cost: Double,
    val sheets: Int,
)

data class ActivityItem(val col: Col, val id: String, val at: Long, val by: String, val label: String, val emoji: String)

data class WeekReport(
    val from: Long,
    val to: Long,
    val rainMm: List<Pair<String, Double?>>,
    val worksDone: List<Work>,
    val worksNext: List<Work>,
    val worksOverdue: List<Work>,
    val incidentsOpened: List<Incident>,
    val incidentsResolved: List<Incident>,
    val incidentsStillOpen: List<Incident>,
    val moves: List<StockMove>,
    val lowStock: List<Pair<Product, Double>>,
    val harvests: List<Harvest>,
    val labour: LabourSummary,
)

object OpsAnalytics {
    private const val DAY_MS = 86_400_000L

    fun stockLevels(d: OpsData): Map<String, Double> =
        d.moves.groupBy { it.productId }.mapValues { (_, m) -> m.sumOf { it.qty } }

    fun stockOf(d: OpsData, productId: String): Double = d.moves.filter { it.productId == productId }.sumOf { it.qty }

    fun lowStock(d: OpsData): List<Pair<Product, Double>> {
        val levels = stockLevels(d)
        return d.products.mapNotNull { p ->
            val level = levels[p.id] ?: 0.0
            if (p.minStock != null && level <= p.minStock) p to level else null
        }
    }

    /** Weighted average purchase price, used to value stock and the products put on a parcel. */
    fun avgPrice(d: OpsData, productId: String): Double? {
        val buys = d.moves.filter { it.productId == productId && it.qty > 0 && it.unitPrice != null }
        val qty = buys.sumOf { it.qty }
        return if (qty > 0) buys.sumOf { it.qty * it.unitPrice!! } / qty else null
    }

    fun stockValue(d: OpsData): Double = stockLevels(d).entries.sumOf { (id, qty) -> (avgPrice(d, id) ?: 0.0) * qty.coerceAtLeast(0.0) }

    /** The stock moves a done field work implies (one per product, with stable ids so edits replace them). */
    fun movesForWork(work: Work, now: Long, by: String): List<StockMove> =
        if (!work.done) emptyList() else work.inputs.filter { it.qty > 0 }.map {
            StockMove(
                id = "${work.id}-${it.productId}",
                productId = it.productId,
                date = work.endDate ?: work.date,
                qty = -it.qty,
                workId = work.id,
                parcelId = work.parcelId,
                updatedAt = now,
                updatedBy = by,
            )
        }

    fun overdue(d: OpsData, today: Long): List<Work> = d.works.filter { it.overdue(today) }.sortedBy { it.date }

    fun worksBetween(d: OpsData, from: Long, to: Long): List<Work> =
        d.works.filter { (it.endDate ?: it.date) >= from && it.date <= to }.sortedBy { it.date }

    fun openIncidents(d: OpsData): List<Incident> =
        d.incidents.filter { it.open }.sortedWith(compareByDescending<Incident> { it.urgent }.thenByDescending { it.reportedAt })

    fun openIncidentsByAsset(d: OpsData): Map<String, List<Incident>> =
        openIncidents(d).filter { it.assetId != null }.groupBy { it.assetId!! }

    fun costsByParcel(d: OpsData, campaign: Int): Map<String, ParcelCost> {
        val inCampaign = d.works.filter { it.parcelId != null && Campaigns.ofDay(it.date) == campaign }
        return inCampaign.groupBy { it.parcelId!! }.mapValues { (_, works) ->
            val done = works.filter { it.done }
            ParcelCost(
                inputs = done.sumOf { w -> w.inputs.sumOf { (avgPrice(d, it.productId) ?: 0.0) * it.qty } },
                other = done.sumOf { it.cost ?: 0.0 },
                labourDays = done.sumOf { (it.workers ?: 0) * ((it.hours ?: 8.0) / 8.0) },
            )
        }
    }

    /** Product totals put on each parcel in a campaign (from done works). */
    fun inputsByParcel(d: OpsData, campaign: Int): Map<String, Map<String, Double>> =
        d.works.filter { it.done && it.parcelId != null && Campaigns.ofDay(it.date) == campaign }
            .groupBy { it.parcelId!! }
            .mapValues { (_, works) -> works.flatMap { it.inputs }.groupBy { it.productId }.mapValues { (_, u) -> u.sumOf { it.qty } } }

    fun yields(d: OpsData, farm: FarmData, campaign: Int): List<YieldRow> =
        d.harvests.filter { it.campaign == campaign }.groupBy { it.parcelId to it.block }.map { (key, list) ->
            val entry = farm.crops.firstOrNull { it.parcelId == key.first && it.campaign == campaign && it.block == key.second }
            val revenue = list.mapNotNull { h -> h.pricePerT?.let { it * h.quantityT } }.takeIf { it.isNotEmpty() }?.sum()
            YieldRow(key.first, key.second, list.first().crop.ifBlank { entry?.crop.orEmpty() }, list.sumOf { it.quantityT }, entry?.areaHa, revenue)
        }.sortedByDescending { it.tonnes }

    /** Electricity use between consecutive meter readings: (day of the later reading, kWh per day). */
    fun meterUsage(d: OpsData, assetId: String, kind: ReadingKind = ReadingKind.METER_KWH): List<Pair<Long, Double>> =
        d.readings.filter { it.assetId == assetId && it.readingKind == kind }.sortedBy { it.date }.zipWithNext()
            .filter { (a, b) -> b.date > a.date && b.value >= a.value }
            .map { (a, b) -> b.date to (b.value - a.value) / (b.date - a.date) }

    fun labour(d: OpsData, from: Long, to: Long): LabourSummary {
        val sheets = d.attendance.filter { it.date in from..to }
        val days = sheets.flatMap { it.present }.groupingBy { it }.eachCount()
        val staffCost = days.entries.sumOf { (id, n) -> (d.staffMember(id)?.dailyRate ?: 0.0) * n }
        val casualCost = sheets.sumOf { it.casuals * (it.casualRate ?: 0.0) }
        return LabourSummary(days, sheets.sumOf { it.casuals }, staffCost + casualCost, sheets.size)
    }

    /** Latest changes across the team, newest first. */
    fun activity(d: OpsData, limit: Int = 30): List<ActivityItem> {
        val items = mutableListOf<ActivityItem>()
        d.works.forEach { items += ActivityItem(Col.WORKS, it.id, it.updatedAt, it.updatedBy, it.title.ifBlank { it.workType.name }, it.workType.emoji) }
        d.incidents.forEach { items += ActivityItem(Col.INCIDENTS, it.id, it.updatedAt, it.updatedBy, it.title, if (it.open) "⚠️" else "✅") }
        d.moves.filter { it.workId == null }.forEach { items += ActivityItem(Col.MOVES, it.id, it.updatedAt, it.updatedBy, d.product(it.productId)?.name.orEmpty(), if (it.qty >= 0) "📥" else "📤") }
        d.harvests.forEach { items += ActivityItem(Col.HARVESTS, it.id, it.updatedAt, it.updatedBy, "%.1f t".format(it.quantityT), "🚛") }
        d.readings.forEach { items += ActivityItem(Col.READINGS, it.id, it.updatedAt, it.updatedBy, d.asset(it.assetId)?.name.orEmpty(), "📟") }
        d.attendance.forEach { items += ActivityItem(Col.ATTENDANCE, it.id, it.updatedAt, it.updatedBy, "${it.present.size + it.casuals}", "🗓️") }
        return items.filter { it.at > 0 }.sortedByDescending { it.at }.take(limit)
    }

    /**
     * The weekly C-R: what was done, what is planned, breakdowns, stock and staff for [from]..[to]
     * (epoch days). [rain] gives each parcel's rainfall over the week.
     */
    fun weekReport(d: OpsData, farm: FarmData, from: Long, to: Long, today: Long = to + 1, rain: (String) -> Double?): WeekReport {
        val fromMs = from * DAY_MS
        val toMs = (to + 1) * DAY_MS
        // For the week in progress, "late" means late today and "upcoming" starts today.
        val asOf = minOf(today, to + 1)
        val done = d.works.filter { it.done && (it.endDate ?: it.date) in from..to }.sortedBy { it.date }
        val next = d.works.filter { !it.done && !it.overdue(asOf) && it.date in asOf..(to + 7) }.sortedBy { it.date }
        return WeekReport(
            from = from,
            to = to,
            rainMm = farm.parcels.map { it.name to rain(it.id) },
            worksDone = done,
            worksNext = next,
            worksOverdue = d.works.filter { it.overdue(asOf) }.sortedBy { it.date },
            incidentsOpened = d.incidents.filter { it.reportedAt in fromMs until toMs }.sortedBy { it.reportedAt },
            incidentsResolved = d.incidents.filter { it.resolvedAt != null && it.resolvedAt in fromMs until toMs && !it.open },
            incidentsStillOpen = openIncidents(d),
            moves = d.moves.filter { it.date in from..to }.sortedBy { it.date },
            lowStock = lowStock(d),
            harvests = d.harvests.filter { it.date in from..to },
            labour = labour(d, from, to),
        )
    }
}
