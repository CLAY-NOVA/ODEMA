package ma.odema.rain

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import ma.odema.core.OdemaJson
import ma.odema.core.farm.CropEntry
import ma.odema.core.ops.Col
import ma.odema.core.ops.InputUse
import ma.odema.core.ops.OpsAnalytics
import ma.odema.core.ops.OpsCollections
import ma.odema.core.ops.Product
import ma.odema.core.ops.StockMove
import ma.odema.core.ops.Work
import ma.odema.core.ops.WorkStatus
import ma.odema.core.ops.WorkType
import ma.odema.rain.data.OpsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Records saved on the phone, the stock a field work uses, and what is handed to the team sync. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsRepositoryTest {
    private val app: OdemaApplication get() = ApplicationProvider.getApplicationContext()
    private val pushed = mutableListOf<Triple<Col, String, String?>>()

    @Before
    fun setUp() {
        runBlocking { app.ops.ensureLoaded(); app.farm.ensureLoaded() }
        app.ops.replaceStateForTests { OpsState(true) }
        app.ops.userName = { "Sara" }
        app.ops.sink = { c, id, json -> pushed += Triple(c, id, json) }
        app.farm.sink = { c, id, json -> pushed += Triple(c, id, json) }
    }

    @Test
    fun doneWorkTakesItsProductsOutOfStockAndIsShared() = runBlocking {
        val ops = app.ops
        ops.saveProduct(Product("urea", "Urée", unit = "kg"))
        ops.saveMove(StockMove("in", "urea", 1, 1000.0, unitPrice = 5.0))
        pushed.clear()

        val work = Work("w", WorkType.FERTILIZER.name, WorkStatus.PLANNED.name, 10, parcelId = "p", inputs = listOf(InputUse("urea", 300.0)))
        ops.saveWork(work)
        assertEquals(1000.0, OpsAnalytics.stockOf(ops.state.value.data, "urea"), 0.0)
        assertEquals(listOf(Col.WORKS to "w"), pushed.map { it.first to it.second })
        assertEquals("Sara", ops.state.value.data.works.single().updatedBy)
        assertEquals("Sara", ops.state.value.data.works.single().createdBy)

        pushed.clear()
        ops.saveWork(work.copy(status = WorkStatus.DONE.name))
        assertEquals(700.0, OpsAnalytics.stockOf(ops.state.value.data, "urea"), 0.0)
        assertEquals(setOf(Col.WORKS to "w", Col.MOVES to "w-urea"), pushed.map { it.first to it.second }.toSet())

        // Saved on disk: a fresh read finds the same data.
        val onDisk = OdemaJson.decodeFromString(ma.odema.core.ops.OpsData.serializer(), File(app.filesDir, "odema/ops.json").readText())
        assertEquals(2, onDisk.moves.size)

        pushed.clear()
        ops.deleteWork("w")
        assertEquals(1000.0, OpsAnalytics.stockOf(ops.state.value.data, "urea"), 0.0)
        assertEquals(setOf(Triple(Col.WORKS, "w", null), Triple(Col.MOVES, "w-urea", null)), pushed.toSet())
    }

    @Test
    fun coworkersChangesAreAppliedWithoutEcho() = runBlocking {
        val remote = Work("r1", WorkType.SOWING.name, date = 5, title = "Semis", updatedAt = 50, updatedBy = "Omar")
        app.ops.applyRemote(Col.WORKS, "r1", OpsCollections.encode(Col.WORKS, remote))
        assertEquals("Omar", app.ops.state.value.data.works.single().updatedBy)
        app.ops.applyRemote(Col.WORKS, "r1", null)
        assertTrue(app.ops.state.value.data.works.isEmpty())

        val crop = CropEntry("remote-crop", "odema-x", 2026, "X 1", "MAIS")
        app.farm.applyRemote(Col.CROPS, crop.id, OdemaJson.encodeToString(CropEntry.serializer(), crop))
        assertEquals(crop, app.farm.state.value.data.crops.firstOrNull { it.id == crop.id })
        assertTrue(pushed.isEmpty())
    }

    @Test
    fun assolementEditsAreShared() = runBlocking {
        pushed.clear()
        val crop = CropEntry("c-new", "p", 2026, "Bloc", "BLE_TENDRE")
        app.farm.saveCrop(crop)
        assertEquals(Col.CROPS to "c-new", pushed.single().let { it.first to it.second })
        app.farm.removeCrop("c-new")
        assertNull(pushed.last().third)
    }

    @Test
    fun weeklyReportPdfAndText() {
        app.ops.replaceStateForTests { OpsState(true, OpsFakes.data, OpsFakes.plan) }
        val farm = ma.odema.rain.data.FarmState(true, FarmFakes.data, FarmFakes.seriesByKey)
        val (from, to) = ma.odema.rain.report.WeeklyReport.weekOf(java.time.LocalDate.now())
        val report = ma.odema.rain.report.WeeklyReport.build(farm, OpsFakes.data, from, to)
        val text = ma.odema.rain.report.WeeklyReport.text(app, farm, OpsFakes.data, report)
        assertTrue(text, text.contains("Pompe arrêtée"))
        // Robolectric has no native PDF engine ("document is closed"); on a phone this writes the file.
        val pdf = runCatching { ma.odema.rain.report.WeeklyReport.pdf(app, farm, OpsFakes.data, report, "Sara") }.getOrNull()
        org.junit.Assume.assumeTrue(pdf != null)
        assertTrue(pdf!!.length() > 1000)
        assertTrue(pdf.readBytes().copyOfRange(0, 4).decodeToString() == "%PDF")
    }

    @Test
    fun cloudStaysOffWithoutConfig() {
        assertEquals(ma.odema.rain.sync.CloudStatus.OFF, app.cloud.state.value.status)
        assertTrue(app.cloud.state.value.canEdit)
        assertTrue(!app.cloud.configure("not a code"))
    }
}
