package ma.odema.rain.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ma.odema.rain.R
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
}

@Composable
fun OdemaRoot(vm: MainViewModel, startRoute: String = Routes.PLACES) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val message by vm.message.collectAsStateWithLifecycle()
    val openRequest by vm.openPlaceRequest.collectAsStateWithLifecycle()

    LaunchedEffect(openRequest) {
        openRequest?.let {
            nav.navigate(Routes.place(it)) { launchSingleTop = true }
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

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val topLevel = route in setOf(Routes.PLACES, Routes.MOROCCO, Routes.SETTINGS, null)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (topLevel) {
                NavigationBar {
                    listOf(
                        Triple(Routes.PLACES, R.string.nav_places, Icons.Default.Home),
                        Triple(Routes.MOROCCO, R.string.nav_morocco, Icons.Default.Place),
                        Triple(Routes.SETTINGS, R.string.nav_settings, Icons.Default.Settings),
                    ).forEach { (r, label, icon) ->
                        NavigationBarItem(
                            selected = route == r || (route == null && r == startRoute),
                            onClick = {
                                nav.navigate(r) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = startRoute, modifier = Modifier.padding(bottom = if (topLevel) padding.calculateBottomPadding() else androidx.compose.ui.unit.Dp(0f))) {
            composable(Routes.PLACES) {
                PlacesScreen(vm, onOpen = { nav.navigate(Routes.place(it)) }, onAdd = { nav.navigate(Routes.ADD) })
            }
            composable(Routes.MOROCCO) {
                OverviewScreen(vm)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(vm, onSources = { nav.navigate(Routes.SOURCES) }, onAbout = { nav.navigate(Routes.ABOUT) })
            }
            composable(Routes.PLACE) { entry ->
                PlaceDetailScreen(vm, entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
            }
            composable(Routes.ADD) {
                AddPlaceScreen(vm, onBack = { nav.popBackStack() }, onMap = { nav.navigate(Routes.MAP) })
            }
            composable(Routes.MAP) {
                MapPickerScreen(vm, onBack = { nav.popBackStack() }, onAdded = { nav.popBackStack(Routes.PLACES, inclusive = false) })
            }
            composable(Routes.SOURCES) { SourcesScreen(vm, onBack = { nav.popBackStack() }) }
            composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
