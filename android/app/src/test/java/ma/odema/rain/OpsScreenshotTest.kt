package ma.odema.rain

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import ma.odema.core.OdemaJson
import ma.odema.core.ops.FarmPlan
import ma.odema.rain.data.FarmMeta
import ma.odema.rain.data.FarmState
import ma.odema.rain.data.OpsState
import ma.odema.rain.ui.MainViewModel
import ma.odema.rain.ui.OdemaRoot
import ma.odema.rain.ui.OdemaTheme
import ma.odema.rain.ui.Routes
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The management screens (plan, work, breakdowns, stock, staff, report, sync) with a made-up week. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "fr-rMA-w400dp-h880dp-xxhdpi")
class OpsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()
    private val app: OdemaApplication get() = ApplicationProvider.getApplicationContext()
    private val out = File("build/screenshots/ops").apply { mkdirs() }

    @Before
    fun seed() {
        runBlocking {
            app.farm.ensureLoaded()
            app.ops.ensureLoaded()
            app.repository.ensureLoaded()
        }
        app.farm.replaceStateForTests {
            FarmState(true, FarmFakes.data, FarmFakes.seriesByKey, FarmFakes.outlooks, FarmMeta(lastSync = System.currentTimeMillis() / 1000 - 3600, chunksRemaining = 0, seeded = true))
        }
        app.ops.replaceStateForTests { OpsState(true, OpsFakes.data, OpsFakes.plan) }
        app.repository.replaceStateForTests { it.copy(appSettings = it.appSettings.copy(mode = "odema", askedNotifications = true)) }
    }

    private fun show(route: String? = null) {
        val vm = MainViewModel(app)
        compose.setContent { OdemaTheme(false) { OdemaRoot(vm, startRoute = route) } }
        compose.waitForIdle()
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage(File(out, "$name.png").path)

    @Test
    fun plan() { show(); shot("01-plan") }

    @Test
    fun planRain() {
        show()
        compose.onNodeWithText("Pluie 7 j").performClick()
        compose.waitForIdle()
        shot("02-plan-rain")
    }

    /** The real ODEMA plan, when the private asset is present (not in the public repo). */
    @Test
    fun realPlan() {
        val plan = runCatching { app.assets.open("odema_plan.json").bufferedReader().use { OdemaJson.decodeFromString(FarmPlan.serializer(), it.readText()) } }.getOrNull()
        Assume.assumeTrue(plan != null)
        val seed = app.assets.open("odema_seed.json").bufferedReader().use { OdemaJson.decodeFromString(ma.odema.core.farm.FarmData.serializer(), it.readText()) }
        app.farm.replaceStateForTests { FarmState(true, seed, meta = FarmMeta(seeded = true)) }
        app.ops.replaceStateForTests { OpsState(true, OpsFakes.data.copy(assets = plan!!.assets, incidents = OpsFakes.data.incidents.take(1).map { it.copy(assetId = "well-f7") }), plan) }
        show()
        shot("00-real-plan")
    }

    @Test
    fun works() { show(Routes.OD_WORKS); shot("03-works") }

    @Test
    fun workEdit() { show(Routes.work(id = "w1")); shot("04-work-edit") }

    @Test
    fun workEditProducts() {
        show(Routes.work(id = "w1"))
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Ouvriers"))
        compose.waitForIdle()
        shot("05-work-edit-products")
    }

    @Test
    fun incidents() { show(Routes.OD_INCIDENTS); shot("06-incidents") }

    @Test
    fun incidentNew() { show(Routes.incident(asset = "well-f2")); shot("07-incident-new") }

    @Test
    fun dashboard() { show(Routes.OD_DASHBOARD); shot("08-dashboard-today") }

    @Test
    fun more() { show(Routes.OD_MORE); shot("09-more") }

    @Test
    fun assets() { show(Routes.OD_ASSETS); shot("10-assets") }

    @Test
    fun asset() { show(Routes.asset("poste-2")); shot("11-asset-poste") }

    @Test
    fun stock() { show(Routes.OD_STOCK); shot("12-stock") }

    @Test
    fun product() { show(Routes.product("urea")); shot("13-product") }

    @Test
    fun attendance() { show(Routes.OD_TEAM); shot("14-attendance") }

    @Test
    fun report() { show(Routes.OD_REPORT); shot("15-report") }

    @Test
    fun sync() { show(Routes.OD_SYNC); shot("16-sync") }

    @Test
    fun activity() { show(Routes.OD_ACTIVITY); shot("17-activity") }

    @Test
    @Config(qualifiers = "ar-rMA-w400dp-h880dp-xxhdpi")
    fun planArabic() { show(); shot("18-plan-arabic") }

    @Test
    @Config(qualifiers = "ar-rMA-w400dp-h880dp-xxhdpi")
    fun worksArabic() { show(Routes.OD_WORKS); shot("19-works-arabic") }
}
