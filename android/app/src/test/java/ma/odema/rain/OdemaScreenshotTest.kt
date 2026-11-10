package ma.odema.rain

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onFirst
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import ma.odema.rain.data.FarmMeta
import ma.odema.rain.data.FarmState
import ma.odema.rain.ui.MainViewModel
import ma.odema.rain.ui.OdemaRoot
import ma.odema.rain.ui.OdemaTheme
import ma.odema.rain.ui.Routes
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** ODEMA mode screens with a made-up farm, in French (the farm's working language) and Arabic. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "fr-rMA-w400dp-h880dp-xxhdpi")
class OdemaScreenshotTest {
    @get:Rule
    val compose = createComposeRule()
    private val app: OdemaApplication get() = ApplicationProvider.getApplicationContext()
    private val out = File("build/screenshots/odema").apply { mkdirs() }

    @Before
    fun seed() {
        runBlocking {
            app.farm.ensureLoaded()
            app.repository.ensureLoaded()
        }
        app.farm.replaceStateForTests {
            FarmState(true, FarmFakes.data, FarmFakes.seriesByKey, FarmFakes.outlooks, FarmMeta(lastSync = System.currentTimeMillis() / 1000 - 3600, chunksRemaining = 0, seeded = true))
        }
        val now = System.currentTimeMillis() / 1000
        app.repository.replaceStateForTests {
            it.copy(
                appSettings = it.appSettings.copy(mode = "odema", askedNotifications = true),
                odemaPlaces = app.farm.alertPlaces(FarmFakes.data),
                reports = mapOf("p-nord" to FakeApis.report(now, rainIn = 40 * 60L, temp = 18.0), "p-sud" to FakeApis.report(now, rainIn = null)),
            )
        }
    }

    private fun show(route: String? = null) {
        val vm = MainViewModel(app)
        compose.setContent { OdemaTheme(false) { OdemaRoot(vm, startRoute = route) } }
        compose.waitForIdle()
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage(File(out, "$name.png").path)

    @Test
    fun dashboard() { show(Routes.OD_DASHBOARD); shot("01-dashboard") }

    @Test
    fun dashboardLower() {
        show(Routes.OD_DASHBOARD)
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Parcelles"))
        compose.waitForIdle()
        shot("02-dashboard-parcels")
    }

    @Test
    fun parcels() { show(Routes.OD_PARCELS); shot("03-parcels") }

    @Test
    fun parcel() { show(Routes.parcel("p-nord")); shot("04-parcel-top") }

    @Test
    fun parcelCharts() {
        show(Routes.parcel("p-nord"))
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Pluie par mois", substring = true))
        compose.waitForIdle()
        shot("05-parcel-charts")
    }

    @Test
    fun parcelHistory() {
        show(Routes.parcel("p-sud"))
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Pluviomètre"))
        compose.waitForIdle()
        shot("06-parcel-history")
    }

    @Test
    fun parcelYears() {
        show(Routes.parcel("p-sud"))
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Pluie par campagne", substring = true))
        compose.waitForIdle()
        shot("06b-parcel-years")
    }

    @Test
    fun assolement() { show(Routes.OD_ASSOLEMENT); shot("07-assolement") }

    @Test
    fun rotation() {
        show(Routes.OD_ASSOLEMENT)
        compose.onNodeWithText("Rotation").performClick()
        compose.waitForIdle()
        shot("08-rotation")
    }

    @Test
    fun cropEditor() { show(Routes.crop("c2", null, FarmFakes.campaign)); shot("09-crop-edit") }

    @Test
    fun settings() { show(Routes.SETTINGS); shot("10-settings") }

    @Test
    @Config(qualifiers = "ar-rMA-w400dp-h880dp-xxhdpi")
    fun dashboardArabic() { show(Routes.OD_DASHBOARD); shot("11-dashboard-arabic") }
}
