package ma.odema.rain

import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import ma.odema.core.CurrentConditions
import ma.odema.core.DailyForecast
import ma.odema.core.MoroccoCities
import ma.odema.core.MoroccoRegions
import ma.odema.core.OdemaJson
import ma.odema.core.Place
import ma.odema.rain.data.OverviewData
import ma.odema.rain.data.OverviewEntry
import ma.odema.rain.ui.MainViewModel
import ma.odema.rain.ui.OdemaRoot
import ma.odema.rain.ui.OdemaTheme
import ma.odema.rain.ui.Routes
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real screens with realistic forecasts and saves PNGs to
 * app/build/screenshots. Also fails if a screen crashes while composing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: OdemaApplication get() = ApplicationProvider.getApplicationContext()
    private val now = System.currentTimeMillis() / 1000
    private val out = File("build/screenshots").apply { mkdirs() }

    private fun place(id: String, alerts: Boolean = true): Place {
        val c = MoroccoCities.byId(id)!!
        return Place(id, c.fr, c.point, c.id, c.region, c.elevation, alertsEnabled = alerts)
    }

    @Before
    fun seed() {
        runBlocking { app.repository.ensureLoaded() }
        app.repository.replaceStateForTests {
            it.copy(
                places = listOf(place("casablanca"), place("ifrane"), place("marrakesh"), place("agadir", alerts = false), place("tangier")),
                reports = mapOf(
                    "casablanca" to FakeApis.report(now, rainIn = 35 * 60L, temp = 19.0),
                    "ifrane" to FakeApis.report(now, rainIn = 50 * 60L, storm = true, temp = 9.0),
                    "marrakesh" to FakeApis.report(now, rainIn = null, temp = 24.0),
                    "agadir" to FakeApis.report(now, rainIn = null, temp = 23.0),
                    "tangier" to FakeApis.report(now, rainIn = 0, temp = 17.0),
                ),
                lastRefresh = now - 240,
                appSettings = it.appSettings.copy(askedNotifications = true),
            )
        }
        // A fresh cached overview so the Morocco tab renders without the network.
        val entries = MoroccoRegions.all.mapIndexed { i, r ->
            OverviewEntry(
                r.capitalId,
                CurrentConditions(now, 12.0 + i, 0.0, if (i % 3 == 0) 61 else 2, true),
                (0..2).map { d -> DailyForecast((now / 86400 + d) * 86400 - 3600, precipitationSum = ((i * 7 + d * 3) % 11) * 1.3, probabilityMax = ((i * 13 + d * 29) % 90 + 5).toDouble(), code = if ((i + d) % 3 == 0) 63 else 2) },
            )
        }
        File(app.cacheDir, "overview.json").writeText(OdemaJson.encodeToString(OverviewData.serializer(), OverviewData(now, entries)))
    }

    private lateinit var vm: MainViewModel

    private fun show(route: String = Routes.PLACES, dark: Boolean = false) {
        vm = MainViewModel(app)
        compose.setContent { OdemaTheme(dark) { OdemaRoot(vm, startRoute = route) } }
        compose.waitForIdle()
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage(File(out, "$name.png").path)

    @Test
    fun places() { show(); shot("01-places") }

    @Test
    fun placeDetail() {
        show()
        compose.onNodeWithText("Ifrane").performClick()
        compose.waitForIdle()
        shot("02-detail-ifrane")
    }

    @Test
    fun morocco() {
        show(Routes.MOROCCO)
        // The cached overview is read on a background thread; let it land on the main looper.
        repeat(50) {
            if (runCatching { compose.onNodeWithText("Tangier").assertExists() }.isSuccess) return@repeat
            Thread.sleep(100)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
        println("[test] overview state: ${vm.overview.value}")
        shot("03-morocco")
        compose.onNodeWithText("Tangier").assertExists()
    }

    @Test
    fun settings() { show(Routes.SETTINGS); shot("04-settings") }

    @Test
    fun addPlace() { show(Routes.ADD); compose.onNodeWithText("6. Casablanca-Settat").performClick(); compose.waitForIdle(); shot("05-add-place") }

    @Test
    fun sources() { show(Routes.SOURCES); shot("06-sources") }

    @Test
    @Config(qualifiers = "ar-rMA-w400dp-h860dp-xxhdpi")
    fun placesArabic() { show(); shot("07-places-arabic") }

    @Test
    @Config(qualifiers = "ar-rMA-w400dp-h860dp-xxhdpi")
    fun detailArabic() {
        show()
        compose.onNodeWithText("إفران").performClick()
        compose.waitForIdle()
        shot("08-detail-arabic")
    }

    @Test
    @Config(qualifiers = "fr-rMA-w400dp-h860dp-xxhdpi")
    fun placesFrenchDark() { show(dark = true); shot("09-places-french-dark") }
}
