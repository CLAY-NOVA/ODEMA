package ma.odema.rain

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import ma.odema.core.ForecastService
import ma.odema.core.MoroccoCities
import ma.odema.core.Place
import ma.odema.rain.notify.Notifier
import ma.odema.rain.work.RainCheckWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Runs the real background worker against a fake Atlas storm and checks the notifications. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundCheckTest {
    private val app: OdemaApplication get() = ApplicationProvider.getApplicationContext()
    private val now = System.currentTimeMillis() / 1000

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { app.repository.ensureLoaded() }
        app.repository.service = ForecastService(FakeApis.client(now))
        val ifrane = MoroccoCities.byId("ifrane")!!
        app.repository.replaceStateForTests {
            it.copy(
                places = listOf(Place("ifrane", ifrane.fr, ifrane.point, ifrane.id, ifrane.region, ifrane.elevation)),
                reports = emptyMap(),
                memories = emptyMap(),
            )
        }
    }

    private fun runWorker(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<RainCheckWorker>(app as Context).build().doWork()
    }

    private fun notifications() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications

    @Test
    fun stormProducesRainHeavyStormAndSnowAlertsOnce() {
        Notifier.createChannels(app)
        assertEquals(ListenableWorker.Result.success(), runWorker())
        val titles = notifications().map { it.extras.getString("android.title").orEmpty() }
        println("[test] notifications: $titles")
        assertTrue(titles.toString(), titles.any { it.startsWith("⚠️ Heavy rain in Ifrane") })
        assertTrue(titles.toString(), titles.any { it.startsWith("⛈️ Thunderstorms possible in Ifrane") })
        assertTrue(titles.toString(), titles.any { it.startsWith("🌧️ Rain soon in Ifrane") })
        assertTrue(titles.toString(), titles.any { it.startsWith("❄️ Snow expected in Ifrane") })
        val bodies = notifications().map { it.extras.getCharSequence("android.text").toString() }
        println("[test] bodies: $bodies")
        assertTrue(bodies.any { "Flooding possible in oueds" in it })
        // Every other API was down: the app still worked from the one that answered.
        assertTrue(app.repository.state.value.reports.getValue("ifrane").hasData)

        // A second check with the same forecast must not alert again.
        val before = notifications().size
        runWorker()
        assertEquals(before, notifications().size)
    }

    @Test
    fun alertsAreWrittenInArabic() {
        runBlocking { app.repository.updateAppSettings { it.copy(language = "ar") } }
        runWorker()
        val titles = notifications().map { it.extras.getString("android.title").orEmpty() }
        println("[test] arabic: $titles")
        assertTrue(titles.toString(), titles.any { it == "⚠️ أمطار غزيرة في إفران" })
        assertTrue(titles.toString(), titles.any { it == "❄️ ثلوج متوقعة في إفران" })
    }

    @Test
    fun alertsAreWrittenInFrench() {
        runBlocking { app.repository.updateAppSettings { it.copy(language = "fr") } }
        runWorker()
        val titles = notifications().map { it.extras.getString("android.title").orEmpty() }
        assertTrue(titles.toString(), titles.any { it == "⛈️ Orages possibles à Ifrane" })
    }

    @Test
    fun noAlertsWhenDryOrSwitchedOff() {
        app.repository.service = ForecastService(FakeApis.client(now, storm = false))
        runWorker()
        assertTrue(notifications().isEmpty())
        app.repository.service = ForecastService(FakeApis.client(now))
        runBlocking { app.repository.updatePlace("ifrane") { it.copy(alertsEnabled = false) } }
        runWorker()
        assertTrue(notifications().isEmpty())
    }
}
