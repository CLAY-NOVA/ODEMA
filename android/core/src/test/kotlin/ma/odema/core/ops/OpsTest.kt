package ma.odema.core.ops

import ma.odema.core.farm.CropEntry
import ma.odema.core.farm.FarmData
import ma.odema.core.farm.Parcel
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun day(iso: String) = LocalDate.parse(iso).toEpochDay()

class OpsCollectionsTest {
    private val work = Work("w1", type = WorkType.FERTILIZER.name, date = day("2026-11-02"), title = "Fond", updatedAt = 100, updatedBy = "Ali")

    @Test
    fun applyInsertsReplacesAndKeepsTheNewest() {
        var d = OpsData()
        d = OpsCollections.apply(d, Col.WORKS, "w1", OpsCollections.encode(Col.WORKS, work))
        assertEquals(listOf(work), d.works)

        val newer = work.copy(status = WorkStatus.DONE.name, updatedAt = 200, updatedBy = "Sara")
        d = OpsCollections.apply(d, Col.WORKS, "w1", OpsCollections.encode(Col.WORKS, newer))
        assertEquals(WorkStatus.DONE, d.works.single().workStatus)

        // An older copy arriving late (offline phone) does not undo the newer change.
        d = OpsCollections.apply(d, Col.WORKS, "w1", OpsCollections.encode(Col.WORKS, work))
        assertEquals("Sara", d.works.single().updatedBy)

        d = OpsCollections.apply(d, Col.WORKS, "w1", null)
        assertTrue(d.works.isEmpty())
    }

    @Test
    fun unknownFieldsAndBadJsonAreTolerated() {
        val json = """{"id":"p1","name":"Urée 46%","unit":"kg","futureField":42}"""
        val d = OpsCollections.apply(OpsData(), Col.PRODUCTS, "p1", json)
        assertEquals("Urée 46%", d.products.single().name)
        assertEquals(d, OpsCollections.apply(d, Col.PRODUCTS, "p2", "{not json"))
    }

    @Test
    fun diffFindsAddedChangedAndRemovedRecords() {
        val a = OpsData(works = listOf(work), products = listOf(Product("p1", "Urée")))
        val b = a.copy(works = listOf(work.copy(title = "Couverture")), products = emptyList(), staff = listOf(Staff("s1", "Hassan")))
        val changes = OpsCollections.diff(a, b).map { Triple(it.first, it.second, it.third != null) }.toSet()
        assertEquals(setOf(Triple(Col.WORKS, "w1", true), Triple(Col.PRODUCTS, "p1", false), Triple(Col.STAFF, "s1", true)), changes)
        assertTrue(OpsCollections.diff(a, a).isEmpty())
    }
}

class OpsAnalyticsTest {
    private val urea = Product("urea", "Urée 46%", ProductCategory.FERTILIZER.name, "kg", minStock = 500.0)
    private val gasoil = Product("gasoil", "Gasoil", ProductCategory.FUEL.name, "L")
    private val data = OpsData(
        products = listOf(urea, gasoil),
        moves = listOf(
            StockMove("m1", "urea", day("2026-10-01"), 1000.0, unitPrice = 5.0),
            StockMove("m2", "urea", day("2026-10-15"), 1000.0, unitPrice = 6.0),
            StockMove("m3", "urea", day("2026-11-01"), -1300.0),
            StockMove("m4", "gasoil", day("2026-11-01"), 200.0, unitPrice = 12.0),
        ),
    )

    @Test
    fun stockLevelsPricesAndAlerts() {
        assertEquals(700.0, OpsAnalytics.stockOf(data, "urea"))
        assertEquals(5.5, OpsAnalytics.avgPrice(data, "urea"))
        assertNull(OpsAnalytics.avgPrice(data, "nothing"))
        assertTrue(OpsAnalytics.lowStock(data).isEmpty())
        val after = data.copy(moves = data.moves + StockMove("m5", "urea", day("2026-11-03"), -250.0))
        assertEquals(listOf(urea to 450.0), OpsAnalytics.lowStock(after))
        assertEquals(700 * 5.5 + 200 * 12.0, OpsAnalytics.stockValue(data), 1e-9)
    }

    @Test
    fun doneWorkTakesProductsOutOfStock() {
        val w = Work("w9", type = WorkType.FERTILIZER.name, date = day("2026-11-05"), parcelId = "karima",
            inputs = listOf(InputUse("urea", 300.0), InputUse("gasoil", 0.0)))
        assertTrue(OpsAnalytics.movesForWork(w, 1, "Ali").isEmpty(), "planned work uses nothing yet")
        val moves = OpsAnalytics.movesForWork(w.copy(status = WorkStatus.DONE.name), 1, "Ali")
        assertEquals(1, moves.size)
        assertEquals("w9-urea", moves[0].id)
        assertEquals(-300.0, moves[0].qty)
        assertEquals("karima", moves[0].parcelId)
    }

    @Test
    fun overdueCostsAndLabour() {
        val today = day("2026-11-10")
        val d = data.copy(
            works = listOf(
                Work("a", date = day("2026-11-01"), parcelId = "karima", status = WorkStatus.DONE.name, inputs = listOf(InputUse("urea", 100.0)), cost = 400.0, workers = 4, hours = 4.0),
                Work("b", date = day("2026-11-08"), parcelId = "karima"),
                Work("c", date = day("2026-11-12"), parcelId = "alia"),
            ),
            staff = listOf(Staff("s1", "Hassan", dailyRate = 100.0), Staff("s2", "Omar", dailyRate = 120.0)),
            attendance = listOf(
                Attendance("day-1", day("2026-11-09"), present = listOf("s1", "s2"), casuals = 5, casualRate = 90.0),
                Attendance("day-2", day("2026-11-10"), present = listOf("s1")),
            ),
        )
        assertEquals(listOf("b"), OpsAnalytics.overdue(d, today).map { it.id })
        val cost = OpsAnalytics.costsByParcel(d, 2026)["karima"]!!
        assertEquals(550.0, cost.inputs, 1e-9)
        assertEquals(950.0, cost.total, 1e-9)
        assertEquals(2.0, cost.labourDays, 1e-9)
        val labour = OpsAnalytics.labour(d, day("2026-11-09"), day("2026-11-10"))
        assertEquals(mapOf("s1" to 2, "s2" to 1), labour.daysByStaff)
        assertEquals(5, labour.casualDays)
        assertEquals(200.0 + 120.0 + 450.0, labour.cost, 1e-9)
    }

    @Test
    fun yieldsMeterUsageAndWeekReport() {
        val farm = FarmData(
            parcels = listOf(Parcel("karima", "Karima")),
            crops = listOf(CropEntry("c", "karima", 2026, "Karima 1", "BLE_TENDRE", areaHa = 36.0)),
        )
        val d = OpsData(
            harvests = listOf(
                Harvest("h1", "karima", "Karima 1", 2026, "BLE_TENDRE", day("2027-06-10"), 90.0, pricePerT = 4000.0),
                Harvest("h2", "karima", "Karima 1", 2026, "BLE_TENDRE", day("2027-06-12"), 54.0),
            ),
            readings = listOf(
                Reading("r1", "poste-5", day("2026-10-01"), ReadingKind.METER_KWH.name, 10_000.0),
                Reading("r2", "poste-5", day("2026-10-11"), ReadingKind.METER_KWH.name, 12_000.0),
            ),
            incidents = listOf(
                Incident("i1", "Pompe F7 arrêtée", assetId = "well-f7", priority = Priority.URGENT.name, reportedAt = day("2027-06-08") * 86_400_000L + 3_600_000),
                Incident("i2", "Fuite", status = IncidentStatus.RESOLVED.name, reportedAt = 0, resolvedAt = day("2027-06-09") * 86_400_000L),
            ),
        )
        val y = OpsAnalytics.yields(d, farm, 2026).single()
        assertEquals(144.0, y.tonnes)
        assertEquals(4.0, y.tPerHa)
        assertEquals(360_000.0, y.revenue)
        assertEquals(listOf(day("2026-10-11") to 200.0), OpsAnalytics.meterUsage(d, "poste-5"))
        assertEquals(listOf("i1"), OpsAnalytics.openIncidentsByAsset(d)["well-f7"]?.map { it.id })

        val r = OpsAnalytics.weekReport(d, farm, day("2027-06-07"), day("2027-06-13")) { 12.5 }
        assertEquals(listOf("Karima" to 12.5), r.rainMm)
        assertEquals(2, r.harvests.size)
        assertEquals(listOf("i1"), r.incidentsOpened.map { it.id })
        assertEquals(listOf("i2"), r.incidentsResolved.map { it.id })
    }
}

class CloudConfigTest {
    private val googleServices = """
        {"project_info":{"project_number":"123","project_id":"odema-farm","storage_bucket":"odema-farm.firebasestorage.app"},
         "client":[
           {"client_info":{"mobilesdk_app_id":"1:123:android:other","android_client_info":{"package_name":"com.other"}},"api_key":[{"current_key":"KEY-OTHER"}]},
           {"client_info":{"mobilesdk_app_id":"1:123:android:abc","android_client_info":{"package_name":"ma.odema.rain"}},"api_key":[{"current_key":"AIzaTEST"}]}
         ],"configuration_version":"1"}
    """.trimIndent()

    @Test
    fun readsGoogleServicesForOurPackageAndRoundTripsTheShareCode() {
        val c = assertNotNull(CloudConfig.parse(googleServices, "ma.odema.rain"))
        assertEquals(CloudConfig("odema-farm", "AIzaTEST", "1:123:android:abc", "odema-farm.firebasestorage.app"), c)
        val code = c.shareCode()
        assertTrue(code.startsWith("ODEMA1:"))
        // Codes survive being pasted inside a chat message.
        assertEquals(c, CloudConfig.parse("Voici le code : $code merci", "ma.odema.rain"))
        assertNull(CloudConfig.parse("hello", "ma.odema.rain"))
        assertNull(CloudConfig.parse("ODEMA1:AAAA", "ma.odema.rain"))
    }

    @Test
    fun rolesMirrorTheRules() {
        assertTrue(TeamRole.of("saisie").canEdit)
        assertFalse(TeamRole.of("lecture").canEdit)
        assertTrue(TeamRole.of("lecture").canRead)
        assertEquals(TeamRole.PENDING, TeamRole.of(null))
        assertFalse(TeamRole.of("blocked").canRead)
    }
}

class PlanGeometryTest {
    // An L-shaped block: its centroid (and bounding-box centre) are outside it.
    private val l = PlanBlock("L", "p", rings = listOf(listOf(0.0, 0.0, 10.0, 0.0, 10.0, 2.0, 2.0, 2.0, 2.0, 10.0, 0.0, 10.0)))
    private val plan = FarmPlan(20.0, 20.0, blocks = listOf(l, PlanBlock("Sq", "q", rings = listOf(listOf(12.0, 12.0, 18.0, 12.0, 18.0, 18.0, 12.0, 18.0)))))

    @Test
    fun hitTestingAreaAndLabels() {
        assertEquals("L", PlanGeometry.blockAt(plan, 1.0, 8.0)?.name)
        assertNull(PlanGeometry.blockAt(plan, 6.0, 6.0))
        assertEquals("Sq", PlanGeometry.blockAt(plan, 15.0, 15.0)?.name)
        assertEquals(36.0, PlanGeometry.area(l.rings[0]))
        val (x, y) = PlanGeometry.labelPoint(l)
        assertTrue(PlanGeometry.contains(l.rings[0], x, y))
    }
}
