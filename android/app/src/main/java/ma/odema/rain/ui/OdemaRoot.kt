package ma.odema.rain.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ma.odema.rain.R
import ma.odema.rain.ui.odema.AssolementScreen
import ma.odema.rain.ui.odema.CropEditScreen
import ma.odema.rain.ui.odema.LocateParcelScreen
import ma.odema.rain.ui.odema.OdemaDashboardScreen
import ma.odema.rain.ui.odema.ParcelScreen
import ma.odema.rain.ui.odema.ParcelsScreen
import ma.odema.rain.ui.screens.AboutScreen
import ma.odema.rain.ui.screens.AddPlaceScreen
import ma.odema.rain.ui.screens.MapPickerScreen
import ma.odema.rain.ui.screens.OverviewScreen
import ma.odema.rain.ui.screens.PlaceDetailScreen
import ma.odema.rain.ui.screens.PlacesScreen
import ma.odema.rain.ui.screens.SettingsScreen
import ma.odema.rain.ui.screens.SourcesScreen

object Routes {
    const val PLACES = "places"
    const val MOROCCO = "morocco"
    const val SETTINGS = "settings"
    const val ADD = "add"
    const val MAP = "map"
    const val SOURCES = "sources"
    const val ABOUT = "about"
    const val PLACE = "place/{id}"
    fun place(id: String) = "place/$id"

    // ODEMA mode
    const val OD_DASHBOARD = "odema"
    const val OD_PARCELS = "odema/parcels"
    const val OD_ASSOLEMENT = "odema/assolement"
    const val OD_PARCEL = "odema/parcel/{id}"
    const val OD_LOCATE = "odema/locate/{id}"
    const val OD_CROP = "odema/crop?id={id}&parcel={parcel}&campaign={campaign}"
    fun parcel(id: String) = "odema/parcel/$id"
    fun locate(id: String) = "odema/locate/$id"
    fun crop(id: String?, parcel: String?, campaign: Int) = "odema/crop?id=${id.orEmpty()}&parcel=${parcel.orEmpty()}&campaign=$campaign"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

@Composable
fun OdemaRoot(vm: MainViewModel, startRoute: String? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val odema = state.appSettings.odema
    // Rebuild navigation when the mode changes: each mode has its own tabs.
    key(odema) { RootFor(vm, odema, startRoute ?: if (odema) Routes.OD_DASHBOARD else Routes.PLACES) }
}

@Composable
private fun RootFor(vm: MainViewModel, odema: Boolean, startRoute: String) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val message by vm.message.collectAsStateWithLifecycle()
    val farmMessage by vm.farmMessage.collectAsStateWithLifecycle()
    val openRequest by vm.openPlaceRequest.collectAsStateWithLifecycle()

    LaunchedEffect(openRequest) {
        openRequest?.let {
            val isParcel = vm.farmState.value.parcel(it) != null
            nav.navigate(if (isParcel) Routes.parcel(it) else Routes.place(it)) { launchSingleTop = true }
            vm.openPlaceRequest.value = null
        }
    }
    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        val text = when (m) {
            is MainViewModel.Message.Added -> context.getString(R.string.add_added, m.name)
            is MainViewModel.Message.Duplicate -> context.getString(R.string.add_already, m.name)
            MainViewModel.Message.Outside -> context.getString(R.string.add_location_outside)
            MainViewModel.Message.LocationFailed -> context.getString(R.string.add_location_failed)
            MainViewModel.Message.TestSent -> context.getString(R.string.set_test_sent)
        }
        vm.message.value = null
        snackbar.showSnackbar(text)
    }
    LaunchedEffect(farmMessage) {
        val m = farmMessage ?: return@LaunchedEffect
        val text = when (m) {
            is MainViewModel.FarmMessage.Imported -> context.getString(R.string.od_imported, m.added, m.updated)
            is MainViewModel.FarmMessage.Failed -> context.getString(R.string.od_import_failed, m.error)
            is MainViewModel.FarmMessage.Saved -> m.what
            is MainViewModel.FarmMessage.Copied -> context.getString(R.string.od_copied, m.count)
        }
        vm.farmMessage.value = null
        snackbar.showSnackbar(text)
    }

    val tabs = if (odema) listOf(
        Tab(Routes.OD_DASHBOARD, R.string.od_nav_dashboard, Icons.Default.Home),
        Tab(Routes.OD_PARCELS, R.string.od_nav_parcels, Icons.Default.Place),
        Tab(Routes.OD_ASSOLEMENT, R.string.od_nav_assolement, Icons.AutoMirrored.Filled.List),
        Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Default.Settings),
    ) else listOf(
        Tab(Routes.PLACES, R.string.nav_places, Icons.Default.Home),
        Tab(Routes.MOROCCO, R.string.nav_morocco, Icons.Default.Place),
        Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Default.Settings),
    )
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val topLevel = route == null || tabs.any { it.route == route }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (topLevel) NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = route == tab.route || (route == null && tab.route == startRoute),
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label), maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = startRoute, modifier = Modifier.padding(bottom = if (topLevel) padding.calculateBottomPadding() else 0.dp)) {
            composable(Routes.PLACES) {
                PlacesScreen(vm, onOpen = { nav.navigate(Routes.place(it)) }, onAdd = { nav.navigate(Routes.ADD) })
            }
            composable(Routes.MOROCCO) { OverviewScreen(vm) }
            composable(Routes.SETTINGS) {
                SettingsScreen(vm, onSources = { nav.navigate(Routes.SOURCES) }, onAbout = { nav.navigate(Routes.ABOUT) })
            }
            composable(Routes.PLACE) { entry ->
                PlaceDetailScreen(vm, entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
            }
            composable(Routes.ADD) { AddPlaceScreen(vm, onBack = { nav.popBackStack() }, onMap = { nav.navigate(Routes.MAP) }) }
            composable(Routes.MAP) {
                MapPickerScreen(vm, onBack = { nav.popBackStack() }, onAdded = { nav.popBackStack(Routes.PLACES, inclusive = false) })
            }
            composable(Routes.SOURCES) { SourcesScreen(vm, onBack = { nav.popBackStack() }) }
            composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }

            // ODEMA
            composable(Routes.OD_DASHBOARD) {
                OdemaDashboardScreen(vm, onParcel = { nav.navigate(Routes.parcel(it)) }, onParcels = { nav.navigate(Routes.OD_PARCELS) })
            }
            composable(Routes.OD_PARCELS) {
                ParcelsScreen(vm, onParcel = { nav.navigate(Routes.parcel(it)) }, onLocate = { nav.navigate(Routes.locate(it)) })
            }
            composable(Routes.OD_ASSOLEMENT) {
                AssolementScreen(vm) { id, parcel, campaign -> nav.navigate(Routes.crop(id, parcel, campaign)) }
            }
            composable(Routes.OD_PARCEL) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                ParcelScreen(
                    vm, id,
                    onBack = { nav.popBackStack() },
                    onLocate = { nav.navigate(Routes.locate(id)) },
                    onForecast = { nav.navigate(Routes.place(id)) },
                    onEditCrop = { cropId, campaign -> nav.navigate(Routes.crop(cropId, id, campaign)) },
                )
            }
            composable(Routes.OD_LOCATE) { entry ->
                LocateParcelScreen(vm, entry.arguments?.getString("id").orEmpty(), onDone = { nav.popBackStack() })
            }
            composable(
                Routes.OD_CROP,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType; defaultValue = "" },
                    navArgument("parcel") { type = NavType.StringType; defaultValue = "" },
                    navArgument("campaign") { type = NavType.IntType; defaultValue = 0 },
                ),
            ) { entry ->
                val args = entry.arguments
                CropEditScreen(
                    vm,
                    entryId = args?.getString("id")?.ifEmpty { null },
                    parcelId = args?.getString("parcel")?.ifEmpty { null },
                    campaign = args?.getInt("campaign")?.takeIf { it > 0 } ?: ma.odema.core.farm.Campaigns.current(),
                    onDone = { nav.popBackStack() },
                )
            }
        }
    }
}
