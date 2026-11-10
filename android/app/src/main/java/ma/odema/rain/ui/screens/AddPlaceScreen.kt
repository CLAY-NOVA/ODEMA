package ma.odema.rain.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import ma.odema.core.GeoPoint
import ma.odema.core.GeocodingResult
import ma.odema.core.MoroccoBounds
import ma.odema.core.MoroccoCities
import ma.odema.core.MoroccoRegions
import ma.odema.rain.R
import ma.odema.rain.coreLang
import ma.odema.rain.locale
import ma.odema.rain.ui.MainViewModel
import ma.odema.rain.ui.components.BackTopBar
import ma.odema.rain.ui.components.SectionHeader
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.util.GeoPoint as OsmPoint

/** Add a place: the built-in towns by region, a search of all of Morocco, your location, or the map. */
@Composable
fun AddPlaceScreen(vm: MainViewModel, onBack: () -> Unit, onMap: () -> Unit) {
    val context = LocalContext.current
    val lang = context.coreLang
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeocodingResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateListOf<Int>() }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) {
            locating = true
            vm.addCurrentLocation(context.getString(R.string.current_location_name))
            onBack()
        } else {
            searchError = context.getString(R.string.add_location_denied)
        }
    }

    // Debounced search of the geocoder for places beyond the built-in towns.
    LaunchedEffect(query) {
        results = emptyList()
        searchError = null
        if (query.trim().length < 2) return@LaunchedEffect
        delay(400)
        searching = true
        try {
            val towns = MoroccoCities.search(query).map { it.id }.toSet()
            results = vm.search(query, context.locale.language).filter { r ->
                // Skip results that are just one of our towns again.
                val (city, km) = MoroccoCities.nearest(r.point)
                !(city.id in towns && km < 3)
            }
        } catch (e: Exception) {
            searchError = context.getString(R.string.add_search_failed, e.message ?: "")
        } finally {
            searching = false
        }
    }

    Scaffold(topBar = { BackTopBar(stringResource(R.string.add_title), onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, contentDescription = null) }
                    },
                    placeholder = { Text(stringResource(R.string.add_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            item {
                ActionRow("📍", stringResource(if (locating) R.string.add_locating else R.string.add_my_location)) {
                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                }
                ActionRow("🗺️", stringResource(R.string.add_pick_map), onMap)
                HorizontalDivider()
            }
            searchError?.let { item { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }

            if (query.isBlank()) {
                item { SectionHeader(stringResource(R.string.add_towns)) }
                MoroccoRegions.all.forEach { region ->
                    val open = region.number in expanded
                    item(key = "region-${region.number}") {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { if (open) expanded.remove(region.number) else expanded.add(region.number) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${region.number}. ${region.name(lang)}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text("${MoroccoCities.inRegion(region.number).size}", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(end = 8.dp))
                            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
                        }
                    }
                    if (open) {
                        items(MoroccoCities.inRegion(region.number), key = { it.id }) { city ->
                            CityRow(city.name(lang), context.getString(R.string.detail_elevation, city.elevation.toInt())) {
                                vm.addCity(city, city.name(lang))
                                onBack()
                            }
                        }
                    }
                }
            } else {
                val towns = MoroccoCities.search(query)
                if (towns.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.add_towns)) }
                    items(towns, key = { it.id }) { city ->
                        CityRow(city.name(lang), MoroccoRegions.byNumber(city.region)?.name(lang).orEmpty()) {
                            vm.addCity(city, city.name(lang))
                            onBack()
                        }
                    }
                }
                if (results.isNotEmpty() || searching) item { SectionHeader(stringResource(R.string.add_more_places)) }
                if (searching) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
                items(results, key = { "geo-${it.id}" }) { r ->
                    CityRow(r.name, listOfNotNull(r.region, r.elevation?.let { e -> context.getString(R.string.detail_elevation, e.toInt()) }).joinToString(" · ")) {
                        vm.addSearchResult(r)
                        onBack()
                    }
                }
                if (!searching && towns.isEmpty() && results.isEmpty() && query.trim().length >= 2 && searchError == null) {
                    item { Text(stringResource(R.string.add_no_results, query), Modifier.padding(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(emoji: String, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, Modifier.padding(end = 16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun CityRow(name: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 32.dp, end = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** OpenStreetMap limited to Morocco: tap to drop a pin, then add it. */
@Composable
fun MapPickerScreen(vm: MainViewModel, onBack: () -> Unit, onAdded: () -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf<GeoPoint?>(null) }
    var name by remember { mutableStateOf("") }

    Scaffold(topBar = { BackTopBar(stringResource(R.string.map_title), onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        MapView(ctx).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
                            minZoomLevel = 5.0
                            maxZoomLevel = 17.0
                            setScrollableAreaLimitDouble(BoundingBox(MoroccoBounds.NORTH, MoroccoBounds.EAST, MoroccoBounds.SOUTH, MoroccoBounds.WEST))
                            controller.setZoom(5.6)
                            controller.setCenter(OsmPoint(MoroccoBounds.center.lat, MoroccoBounds.center.lon))
                            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                                override fun singleTapConfirmedHelper(p: OsmPoint): Boolean {
                                    val point = GeoPoint(p.latitude, p.longitude)
                                    if (!MoroccoBounds.contains(point)) return true
                                    pin = point
                                    val (city, km) = MoroccoCities.nearest(point)
                                    name = if (km < 3) city.name(context.coreLang)
                                    else context.getString(R.string.map_near, city.name(context.coreLang), km.toInt())
                                    return true
                                }

                                override fun longPressHelper(p: OsmPoint): Boolean = false
                            }))
                        }
                    },
                    update = { map ->
                        map.overlays.removeAll { it is Marker }
                        pin?.let { p ->
                            map.overlays.add(Marker(map).apply {
                                position = OsmPoint(p.lat, p.lon)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                title = name
                            })
                        }
                        map.invalidate()
                    },
                    onRelease = { it.onDetach() },
                )
                Text(
                    stringResource(R.string.map_attribution),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                )
            }
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                val current = pin
                if (current == null) {
                    Text(stringResource(R.string.map_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.map_name_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(
                        "%.4f, %.4f".format(java.util.Locale.ROOT, current.lat, current.lon),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    Button(onClick = {
                        vm.addPoint(name.ifBlank { context.getString(R.string.map_pinned) }, current)
                        onAdded()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.LocationOn, contentDescription = null)
                        Text(stringResource(R.string.map_add), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}
