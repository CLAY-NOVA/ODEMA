package ma.odema.core.farm

import kotlinx.coroutines.test.runTest
import ma.odema.core.Fixtures
import ma.odema.core.GeoPoint
import ma.odema.core.HttpResponse
import ma.odema.core.MockHttp
import ma.odema.core.OdemaJson
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun day(iso: String) = LocalDate.parse(iso).toEpochDay()

class KmlTest {
    private val kml = Fixtures.text("sample_parcels.kml").toByteArray()

    @Test
    fun readsGoogleEarthPlacemarks() {
        val pms = Kml.parse(kml)
        assertEquals(listOf("Alpha", "Beta", "Puits"), pms.map { it.name }, "the open LineString is not a parcel")
        val alpha = pms[0]
        assertEquals("Domaine Nord", alpha.folder)
        assertEquals(5, alpha.polygon.size)
        // 0.01° × 0.01° at 34.2°N ≈ 1.113 km × 0.920 km ≈ 102.4 ha.
        assertEquals(102.4, alpha.areaHa!!, 0.6)
        assertEquals(34.205, alpha.location!!.lat, 1e-6)
        assertEquals(-6.595, alpha.location!!.lon, 1e-6)
        assertEquals("<b>Pivot</b> n°1", alpha.description)
        // MultiGeometry keeps the largest polygon.
        assertTrue(pms[1].areaHa!! > 40)
        assertEquals(GeoPoint(34.22, -6.58), pms[2].point)
        assertNull(pms[2].areaHa)
    }

    @Test
    fun readsKmz() {
        val zipped = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { z ->
                z.putNextEntry(ZipEntry("files/icon.png")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
                z.putNextEntry(ZipEntry("doc.kml")); z.write(kml); z.closeEntry()
            }
        }.toByteArray()
        assertEquals(3, Kml.parse(zipped).size)
    }

    @Test
    fun mergeUpdatesByNameAndAddsTheRest() {
        var n = 0
        val existing = listOf(Parcel("p1", "ALPHA", declaredAreaHa = 101.0, notes = "keep"))
        val merge = KmlImport.merge(existing, Kml.parse(kml)) { "new${++n}" }
        assertEquals(1, merge.updated)
        assertEquals(2, merge.added)
        val alpha = merge.parcels.first { it.id == "p1" }
        assertEquals(5, alpha.polygon.size)
        assertEquals(101.0, alpha.areaHa, "the official surface wins over the drawn one")
        assertEquals("keep", alpha.notes)
        assertEquals("Pivot n°1", merge.parcels.first { it.name == "Beta" }.notes.ifEmpty { "Pivot n°1" }.let { if (it == "Pivot n°1") it else it })
        assertEquals(GeoPoint(34.22, -6.58), merge.parcels.first { it.name == "Puits" }.location)
    }

    @Test
    fun rejectsExternalEntities() {
        val evil = """<?xml version="1.0"?><!DOCTYPE kml [<!ENTITY x SYSTEM "file:///etc/passwd">]><kml><Placemark><name>&x;</name><Point><coordinates>1,2</coordinates></Point></Placemark></kml>"""
        val result = runCatching { Kml.parse(evil.toByteArray()) }
        assertTrue(result.isFailure || result.getOrThrow().none { "root" in it.name })
    }
}

class GeoTest {
    @Test
    fun areaCentroidAndContains() {
        val square = listOf(GeoPoint(34.0, -6.0), GeoPoint(34.0, -5.99), GeoPoint(34.01, -5.99), GeoPoint(34.01, -6.0))
        assertEquals(102.6, Geo.areaHa(square), 0.6)
        assertEquals(34.005, Geo.centroid(square).lat, 1e-9)
        assertTrue(Geo.contains(square, GeoPoint(34.005, -5.995)))
        assertFalse(Geo.contains(square, GeoPoint(34.02, -5.995)))
        assertEquals(Geo.areaHa(square), Geo.areaHa(square + square.first()), 1e-9, "closed and open rings agree")
    }

    @Test
    fun campaignsRunSeptemberToAugust() {
        assertEquals(2025, Campaigns.of(LocalDate.of(2025, 9, 1)))
        assertEquals(2025, Campaigns.of(LocalDate.of(2026, 8, 31)))
        assertEquals(2024, Campaigns.of(LocalDate.of(2025, 8, 31)))
        assertEquals("2025-2026", Campaigns.label(2025))
    }

    @Test
    fun nearbyParcelsShareAHistoryKey() {
        val a = Parcel("a", "A", center = GeoPoint(34.2001, -6.5999))
        val b = Parcel("b", "B", center = GeoPoint(34.2049, -6.6049))
        val far = Parcel("c", "C", center = GeoPoint(34.30, -6.60))
        assertEquals(a.historyKey, b.historyKey)
        assertTrue(a.historyKey != far.historyKey)
    }
}

class RainSeriesTest {
    private val d0 = day("2026-01-01")

    @Test
    fun archiveWinsAndProvisionalFillsTheTail() {
        var s = RainSeries.EMPTY.merge(d0, listOf(1.0, 2.0, 3.0, 4.0), provisional = true, now = 1)
        assertEquals(d0, s.provisionalFrom)
        // The archive catches up for the first three days and corrects them.
        s = s.merge(d0, listOf(1.5, 2.5, 3.5), provisional = false, now = 2)
        assertEquals(listOf(1.5, 2.5, 3.5, 4.0), (0..3).map { s[d0 + it] })
        assertEquals(d0 + 3, s.provisionalFrom)
        // New provisional data must not overwrite confirmed days, but may update the tail.
        s = s.merge(d0, listOf(9.0, 9.0, 9.0, 4.4, 5.0), provisional = true, now = 3)
        assertEquals(listOf(1.5, 2.5, 3.5, 4.4, 5.0), (0..4).map { s[d0 + it] })
        // Older archive data extends the series backwards.
        s = s.merge(d0 - 2, listOf(7.0, 8.0), provisional = false, now = 4)
        assertEquals(d0 - 2, s.firstDay)
        assertEquals(7.0, s[d0 - 2])
        assertEquals(d0 + 3, s.provisionalFrom)
    }

    @Test
    fun planFetchesOnlyMissingRangesNewestFirst() {
        val today = day("2026-11-10")
        val point = GeoPoint(34.2, -6.6)
        val fresh = RainArchive.plan("k", point, null, today)
        assertEquals(today - 5, fresh.first().to)
        assertEquals(day("1940-01-01"), fresh.last().from)
        assertEquals(9, fresh.size, "1940 → 2026 in ≤10-year chunks")
        // With 2000–2026 archived and the last weeks provisional, only the provisional days and pre-2000 remain.
        val archived = RainSeries.EMPTY.merge(day("2000-01-01"), List((today - 30 - day("2000-01-01")).toInt()) { 0.0 }, false, 1)
            .merge(today - 30, List(30) { 1.0 }, true, 2)
        val plan = RainArchive.plan("k", point, archived, today)
        assertEquals(today - 30, plan.first().from)
        assertEquals(today - 5, plan.first().to)
        assertEquals(day("1999-12-31"), plan[1].to)
        assertEquals(day("1940-01-01"), plan.last().from)
    }
}

class RainHistoryTest {
    /** 1 mm every day plus 10 mm each 15 November, from 1990 to 2026-11-10. */
    private val today = day("2026-11-10")
    private val start = day("1990-01-01")
    private val series = RainSeries(start, (start..today).map { d -> if (LocalDate.ofEpochDay(d).let { it.monthValue == 11 && it.dayOfMonth == 15 }) 11.0 else 1.0 })

    @Test
    fun campaignStatistics() {
        val h = RainHistory(series)
        val totals = h.campaignTotals(today)
        assertEquals(1990, totals.keys.min())
        assertEquals(2025, totals.keys.max(), "2025-2026 ended on 31 August 2026")
        assertEquals(365.0 + 10, totals.getValue(2024), 1e-9) // 2024-25 has no 29 February
        assertEquals(366.0 + 10, totals.getValue(2023), 1e-9)
        // 1 Sept → 10 Nov = 71 days.
        assertEquals(71.0, h.campaignToDate(today).mm, 1e-9)
        assertEquals(71.0, h.normalToDate(today)!!, 1e-9)
        assertEquals((1991..2019).toList(), h.normalCampaigns(today))
        assertEquals(30.0 + 10, h.monthly(2024)[2]!!, 1e-9) // November
        assertEquals(today, h.lastRain(today)!!.first)
        assertEquals(day("2025-11-15") to 11.0, h.wettestDay(day("2025-09-01"), today))
        assertEquals(71, h.cumulative(2026, today).size)
        assertEquals(71.0, h.cumulative(2026, today).last(), 1e-9)
    }

    @Test
    fun dailyGaugeReadingsOverrideTheModel() {
        val g = GaugeReading("g", "p", today, mm = 25.0)
        val h = RainHistory(series, listOf(g))
        assertEquals(25.0, h.day(today))
        assertEquals(71.0 - 1 + 25, h.campaignToDate(today).mm, 1e-9)
        val period = GaugeReading("g2", "p", today, fromDate = today - 9, mm = 12.0)
        assertEquals(1.0, RainHistory(series, listOf(period)).day(today), "a period total does not replace daily values")
    }
}

class FarmAnalyticsTest {
    private val today = day("2026-11-10")
    private val alia = Parcel("alia", "Alia", center = GeoPoint(34.2, -6.6), declaredAreaHa = 40.0)
    private val karima = Parcel("karima", "Karima", center = GeoPoint(34.4, -6.4), declaredAreaHa = 80.0)
    private val data = FarmData(
        parcels = listOf(alia, karima),
        crops = listOf(
            CropEntry("c1", "alia", 2026, "Alia 1", Crop.BETTERAVE.name, "FD Curling", areaHa = 17.0, sowingDate = day("2026-10-20")),
            CropEntry("c2", "alia", 2026, "Alia 2", Crop.BLE_TENDRE.name, "Guadalete", "G3", 20.0),
            CropEntry("c3", "karima", 2026, "Karima 2", Crop.BLE_TENDRE.name, "Faiza", "G4", 39.0),
            CropEntry("c4", "karima", 2025, "Karima 2", Crop.BETTERAVE.name, areaHa = 39.0),
        ),
        gauges = listOf(GaugeReading("g", "alia", day("2026-11-01"), day("2026-10-01"), 50.0)),
    )

    private fun constant(mm: Double): RainSeries {
        val s = day("2000-01-01")
        return RainSeries(s, List((today - s + 1).toInt()) { mm })
    }

    @Test
    fun parcelAndFarmDashboards() {
        val series = mapOf(alia.historyKey!! to constant(1.0), karima.historyKey!! to constant(2.0))
        val outlook = mapOf(alia.historyKey!! to DailyOutlook(today - 92, List(100) { 3.0 }, List(100) { 50.0 }, 0))
        val farm = FarmAnalytics.farm(data, series, outlook, today)
        assertEquals(2026, farm.campaign)
        val a = farm.parcels.first { it.parcel.id == "alia" }
        assertEquals(71.0, a.campaignToDate.mm, 1e-9)
        assertEquals(1.0, a.ratioToNormal!!, 1e-9)
        assertEquals(21.0, a.next7!!, 1e-9)
        assertEquals(2, a.crops.size)
        assertEquals(day("2026-10-20") to 22.0, a.sinceSowing)
        // Area-weighted: (71×40 + 142×80) / 120.
        assertEquals((71.0 * 40 + 142.0 * 80) / 120, farm.campaignToDate!!, 1e-9)
        assertEquals(120.0, farm.totalAreaHa)
        assertEquals(76.0, farm.plantedAreaHa)
        assertEquals(Crop.BLE_TENDRE, farm.cropAreas.first().crop)
        assertEquals(59.0, farm.cropAreas.first().areaHa)
        assertEquals("karima", farm.wettest!!.parcel.id)
        assertEquals(21.0, farm.next7!!, 1e-9, "only parcels with an outlook count")
        val rotation = FarmAnalytics.rotation(data, 2025..2026)
        assertEquals(Crop.BETTERAVE, rotation.getValue("karima").getValue(2025).single().cropKind)
        val cmp = FarmAnalytics.gaugeComparison(alia, data, constant(1.0)).single()
        assertEquals(50.0, cmp.first.mm)
        assertEquals(32.0, cmp.second!!, 1e-9)
    }

    @Test
    fun csvExport() {
        val csv = FarmAnalytics.csv(alia, constant(1.25), data, today - 1, today)
        assertEquals("date,rain_mm,source\n2026-11-09,1.3,reanalysis\n2026-11-10,1.3,reanalysis\n", csv)
    }

    @Test
    fun farmDataRoundTrips() {
        val json = OdemaJson.encodeToString(FarmData.serializer(), data)
        assertEquals(data, OdemaJson.decodeFromString(FarmData.serializer(), json))
    }
}

class FarmSyncTest {
    private val today = day("2026-11-10")
    private val parcels = listOf(
        Parcel("a", "A", center = GeoPoint(34.2, -6.6)),
        Parcel("b", "B", center = GeoPoint(34.2005, -6.6005)), // same grid key as A
        Parcel("c", "C", center = GeoPoint(34.5, -6.2)),
    )

    private fun archiveBody(from: String, to: String): String {
        val days = (day(from)..day(to)).map { LocalDate.ofEpochDay(it).toString() }
        return """{"daily":{"time":[${days.joinToString(",") { "\"$it\"" }}],"precipitation_sum":[${days.joinToString(",") { "0.5" }}]}}"""
    }

    private fun recentBody(n: Int): String {
        val first = today - RecentRain.PAST_DAYS
        val days = (0 until RecentRain.PAST_DAYS + RecentRain.FORECAST_DAYS).map { LocalDate.ofEpochDay(first + it).toString() }
        val one = """{"daily":{"time":[${days.joinToString(",") { "\"$it\"" }}],"precipitation_sum":[${days.joinToString(",") { "2.0" }}],"precipitation_probability_max":[${days.joinToString(",") { "40" }}]}}"""
        return "[" + List(n) { one }.joinToString(",") + "]"
    }

    @Test
    fun fetchesRecentThenArchiveNewestFirstAndStopsAtTheLimit() = runTest {
        var archiveCalls = 0
        val http = MockHttp { url ->
            when {
                "archive-api" in url -> {
                    archiveCalls++
                    if (archiveCalls > 5) HttpResponse(429, """{"error":true,"reason":"Daily API request limit exceeded"}""")
                    else {
                        val from = Regex("start_date=([0-9-]+)").find(url)!!.groupValues[1]
                        val to = Regex("end_date=([0-9-]+)").find(url)!!.groupValues[1]
                        HttpResponse(200, archiveBody(from, to))
                    }
                }
                "api.open-meteo.com" in url -> HttpResponse(200, recentBody(2))
                else -> HttpResponse(500, "")
            }
        }
        val result = FarmSync(http).run(parcels, emptyMap(), emptyMap(), today, now = 1)
        assertEquals(2, result.series.size, "A and B share one series")
        assertTrue(http.requests.first().contains("latitude=34.2000,34.5000"), "one multi-location request for recent days")
        assertEquals(5, result.chunksFetched)
        assertTrue(result.rateLimited)
        assertEquals(18 - 5, result.chunksRemaining)
        val a = result.series.getValue(parcels[0].historyKey!!)
        assertEquals(0.5, a[today - 10], "archive overrides the provisional recent value")
        assertEquals(2.0, a[today - 1], "days the archive hasn't reached stay provisional")
        assertNull(a[today], "today is not history yet")
        assertEquals(14.0, result.outlooks.getValue(parcels[0].historyKey!!).total(today + 1, 7), 1e-9)
        // Newest first: the first archive request ends 5 days ago.
        assertTrue(http.requests.first { "archive-api" in it }.contains("end_date=${LocalDate.ofEpochDay(today - 5)}"))
        // Next day it continues where it stopped.
        val again = FarmSync(MockHttp { url ->
            if ("archive-api" in url) {
                val from = Regex("start_date=([0-9-]+)").find(url)!!.groupValues[1]
                val to = Regex("end_date=([0-9-]+)").find(url)!!.groupValues[1]
                HttpResponse(200, archiveBody(from, to))
            } else HttpResponse(200, recentBody(2))
        }).run(parcels, result.series, result.outlooks, today, now = 2, budget = 50)
        assertEquals(0, again.chunksRemaining)
        assertEquals(1940, RainHistory(again.series.getValue(parcels[2].historyKey!!)).firstYear)
        assertNotNull(again.series.values.first().provisionalFrom)
    }
}
