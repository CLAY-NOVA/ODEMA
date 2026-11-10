package ma.odema.rain

import ma.odema.core.GeoPoint
import ma.odema.core.HttpClient
import ma.odema.core.HttpResponse
import ma.odema.core.farm.Campaigns
import ma.odema.core.farm.CropEntry
import ma.odema.core.farm.DailyOutlook
import ma.odema.core.farm.FarmData
import ma.odema.core.farm.GaugeReading
import ma.odema.core.farm.Parcel
import ma.odema.core.farm.RainSeries
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Made-up farm (not real parcels) with a realistic Moroccan rain climate since 1940. */
object FarmFakes {
    val today: Long = LocalDate.now().toEpochDay()
    val campaign: Int = Campaigns.ofDay(today)

    private fun square(lat: Double, lon: Double, size: Double) = listOf(
        GeoPoint(lat, lon), GeoPoint(lat, lon + size), GeoPoint(lat + size * 0.8, lon + size), GeoPoint(lat + size * 0.8, lon),
    )

    val parcels = listOf(
        Parcel("p-nord", "Nord", "Domaine test", square(34.30, -6.40, 0.006), declaredAreaHa = 42.0),
        Parcel("p-sud", "Sud", "Domaine test", square(34.22, -6.37, 0.008), declaredAreaHa = 75.0),
        Parcel("p-est", "Est", "Domaine test", square(34.27, -6.20, 0.005), declaredAreaHa = 30.0),
        Parcel("p-pivot", "Pivot 3", "Domaine test", center = GeoPoint(34.25, -6.31), declaredAreaHa = 45.0),
        Parcel("p-new", "Nouvelle", "Domaine test", declaredAreaHa = 12.0),
    )

    val data = FarmData(
        parcels = parcels,
        crops = listOf(
            CropEntry("c1", "p-nord", campaign, "Nord 1", "BETTERAVE", "FD Curling", "", 17.0, sowingDate = today - 20),
            CropEntry("c2", "p-nord", campaign, "Nord 2", "BLE_TENDRE", "Guadalete", "G3", 20.0, sowingDate = today - 12),
            CropEntry("c3", "p-sud", campaign, "Sud 1", "BETTERAVE", "FD Olympique", "", 22.0),
            CropEntry("c4", "p-sud", campaign, "Sud 2", "BLE_TENDRE", "Faiza", "G4", 39.0),
            CropEntry("c5", "p-est", campaign, "Est", "AGRUMES", "Navel", "", 30.0),
            CropEntry("c6", "p-pivot", campaign, "Pivot 3", "MAIS", "Précoce", "", 30.0),
            CropEntry("c7", "p-nord", campaign - 1, "Nord 1", "BLE_TENDRE", "Adjou", "G3", 17.0, yieldTPerHa = 5.4),
            CropEntry("c8", "p-nord", campaign - 1, "Nord 2", "FEVEROLE", "", "", 20.0),
            CropEntry("c9", "p-sud", campaign - 1, "Sud 1", "BLE_TENDRE", "Remax", "G4", 22.0),
            CropEntry("c10", "p-sud", campaign - 2, "Sud 1", "BETTERAVE", "LP Jobane", "", 22.0),
            CropEntry("c11", "p-pivot", campaign - 1, "Pivot 3", "TOMATE", "", "", 30.0),
        ),
        gauges = listOf(GaugeReading("g1", "p-sud", today - 30, today - 66, 141.0, "C-R hebdomadaire")),
    )

    /** Deterministic daily rain: wet Oct–Apr, dry summers, year-to-year variability. */
    fun series(seed: Int): RainSeries {
        val first = LocalDate.of(1940, 1, 1).toEpochDay()
        val values = (first until today - 1).map { d ->
            val date = LocalDate.ofEpochDay(d)
            val season = 0.5 + 0.5 * sin(2 * PI * (date.dayOfYear - 300) / 365.0 + PI / 2)
            val yearFactor = 0.6 + 0.8 * abs(sin(date.year * 1.7 + seed))
            val pulse = Math.floorMod(d * 2654435761L + seed * 97L, 100L).toInt()
            if (pulse < (season * 22).toInt()) (season * yearFactor * (4 + pulse % 18)).let { (it * 10).toInt() / 10.0 } else 0.0
        }
        return RainSeries(first, values, provisionalFrom = today - 6)
    }

    val seriesByKey: Map<String, RainSeries> =
        parcels.mapNotNull { it.historyKey }.distinct().mapIndexed { i, k -> k to series(i + 3) }.toMap()

    val outlooks: Map<String, DailyOutlook> = seriesByKey.keys.associateWith {
        DailyOutlook(today - 92, List(100) { i -> if (i in 93..95) 6.5 else 0.0 }, List(100) { 30.0 }, System.currentTimeMillis() / 1000)
    }

    /** Fake Open-Meteo: archive returns 0.5 mm/day, recent returns 2 mm/day, for any number of points. */
    fun client() = HttpClient { url, _ ->
        fun days(from: Long, count: Int) = (0 until count).map { LocalDate.ofEpochDay(from + it).toString() }
        when {
            "archive-api" in url -> {
                val from = LocalDate.parse(Regex("start_date=([0-9-]+)").find(url)!!.groupValues[1]).toEpochDay()
                val to = LocalDate.parse(Regex("end_date=([0-9-]+)").find(url)!!.groupValues[1]).toEpochDay()
                val d = days(from, (to - from + 1).toInt())
                HttpResponse(200, """{"daily":{"time":[${d.joinToString(",") { "\"$it\"" }}],"precipitation_sum":[${d.joinToString(",") { "0.5" }}]}}""")
            }
            "api.open-meteo.com" in url && "past_days" in url -> {
                val n = Regex("latitude=([^&]+)").find(url)!!.groupValues[1].split(",").size
                val d = days(today - 92, 100)
                val one = """{"daily":{"time":[${d.joinToString(",") { "\"$it\"" }}],"precipitation_sum":[${d.joinToString(",") { "2.0" }}],"precipitation_probability_max":[${d.joinToString(",") { "50" }}]}}"""
                HttpResponse(200, if (n == 1) one else "[" + List(n) { one }.joinToString(",") + "]")
            }
            else -> HttpResponse(503, "")
        }
    }
}
