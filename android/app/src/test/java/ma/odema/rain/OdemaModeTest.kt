package ma.odema.rain

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import ma.odema.core.ForecastService
import ma.odema.core.farm.FarmData
import ma.odema.core.farm.RainHistory
import ma.odema.rain.data.FarmMeta
import ma.odema.rain.data.FarmState
import ma.odema.rain.work.FarmSyncWorker
import ma.odema.rain.work.RainCheckWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OdemaModeTest {
    private val app: OdemaApplication get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        runBlocking { app.farm.ensureLoaded() }
        app.farm.replaceStateForTests { FarmState(loaded = true, data = FarmData(), meta = FarmMeta(seeded = true)) }
        app.farm.client = FarmFakes.client()
    }

    @Test
    fun importKmlThenDownloadHistoryInTheBackground() = runBlocking {
        val kml = javaClass.getResource("/sample_parcels.kml")!!.readBytes()
        val merge = app.farm.importKml(kml)
        assertEquals(3, merge.added)
        assertEquals(listOf("Alpha", "Beta", "Puits"), app.farm.state.value.data.parcels.map { it.name })

        // 3 locations × 9 decade blocks exceed one run's budget: the second run finishes.
        assertEquals(ListenableWorker.Result.success(), TestListenableWorkerBuilder<FarmSyncWorker>(app as Context).build().doWork())
        assertTrue(app.farm.state.value.meta.chunksRemaining!! > 0)
        TestListenableWorkerBuilder<FarmSyncWorker>(app as Context).build().doWork()
        val s = app.farm.state.value
        val alpha = s.data.parcels.first()
        val history = RainHistory(s.seriesFor(alpha))
        assertEquals(1940, history.firstYear)
        assertTrue("several campaigns complete", history.campaignTotals(FarmFakes.today).size > 80)
        assertEquals(0, s.meta.chunksRemaining)
        assertTrue(File(app.filesDir, "odema/rain-history.json").length() > 100_000)
        // CSV export covers every day since 1940.
        val csv = app.farm.csv(alpha.id)!!
        assertTrue(csv.lines().size > 31_000)
        assertTrue(csv.startsWith("date,rain_mm,source\n1940-01-01,0.5,reanalysis"))
    }

    @Test
    fun odemaModeAlertsOnParcels() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.farm.replaceStateForTests { it.copy(data = FarmFakes.data) }
        app.repository.ensureLoaded()
        app.repository.replaceStateForTests { it.copy(places = emptyList(), reports = emptyMap(), memories = emptyMap()) }
        app.repository.updateAppSettings { it.copy(mode = "odema", language = "fr") }
        app.repository.service = ForecastService(FakeApis.client(System.currentTimeMillis() / 1000))
        TestListenableWorkerBuilder<RainCheckWorker>(app as Context).build().doWork()
        val state = app.repository.state.value
        assertEquals("parcels with a location become alert places", 4, state.odemaPlaces.size)
        val titles = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.map { it.extras.getString("android.title").orEmpty() }
        println("[test] odema alerts: $titles")
        // One notification per alert type, naming the parcels — not one per parcel.
        val heavy = titles.single { it.startsWith("⚠️ Fortes pluies à") }
        assertTrue(heavy, listOf("Nord", "Sud", "Est", "Pivot 3").all { it in heavy })
        assertTrue(titles.toString(), titles.size <= 6)
        assertTrue(titles.toString(), titles.none { "Nouvelle" in it })
    }

    @Test
    fun backupRoundTrip() = runBlocking {
        app.farm.replaceStateForTests { it.copy(data = FarmFakes.data) }
        val json = app.farm.exportBackup()
        app.farm.replaceStateForTests { it.copy(data = FarmData()) }
        app.farm.importBackup(json)
        assertEquals(FarmFakes.data, app.farm.state.value.data)
        assertEquals(1, app.farm.copyBlocks(FarmFakes.campaign - 1, FarmFakes.campaign + 1).let { if (it > 0) 1 else 0 })
    }
}
