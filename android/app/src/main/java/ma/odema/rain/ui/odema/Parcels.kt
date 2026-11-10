package ma.odema.rain.ui.odema

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ma.odema.core.GeoPoint
import ma.odema.core.MoroccoBounds
import ma.odema.core.farm.Campaigns
import ma.odema.core.farm.FarmAnalytics
import ma.odema.rain.R
import ma.odema.rain.StatusText
import ma.odema.rain.ui.MainViewModel
import ma.odema.rain.ui.components.BackTopBar
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.time.LocalDate
import org.osmdroid.util.GeoPoint as OsmPoint

/** MIME types Google Earth, Drive and file managers use for KML/KMZ. */
val KmlMimeTypes = arrayOf(
    "application/vnd.google-earth.kml+xml", "application/vnd.google-earth.kmz",
    "application/xml", "text/xml", "application/zip", "application/octet-stream", "*/*",
)

@Composable
fun rememberKmlPicker(vm: MainViewModel): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes != null) vm.importKml(bytes)
    }
    return { launcher.launch(KmlMimeTypes) }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ParcelsScreen(vm: MainViewModel, onParcel: (String) -> Unit, onLocate: (String) -> Unit) {
    val farm by vm.farmState.collectAsStateWithLifecycle()
    val app by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showMap by rememberSaveable { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val pickKml = rememberKmlPicker(vm)
    val today = LocalDate.now().toEpochDay()
    val campaign = Campaigns.ofDay(today)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.od_parcels_title)) },
                actions = {
                    IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, stringResource(R.string.od_add_parcel)) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = pickKml, icon = { Text("🗺️") }, text = { Text(stringResource(R.string.od_import_kml)) })
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                FilterChip(!showMap, { showMap = false }, { Text(stringResource(R.string.od_view_list)) })
                FilterChip(showMap, { showMap = true }, { Text(stringResource(R.string.od_view_map)) }, Modifier.padding(start = 8.dp))
            }
            if (showMap) {
                ParcelMap(vm, onParcel, Modifier.weight(1f))
            } else {
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (farm.data.parcels.isEmpty()) item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.od_import_title), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.od_import_steps), style = MaterialTheme.typography.bodyMedium)
                                Button(onClick = pickKml) { Text(stringResource(R.string.od_import_kml)) }
                            }
                        }
                    }
                    items(farm.data.parcels, key = { it.id }) { p ->
                        val s = remember(p, farm.series, farm.outlooks, farm.data.crops) {
                            FarmAnalytics.parcel(p, farm.seriesFor(p), farm.data, farm.outlookFor(p), today, campaign)
                        }
                        val status = StatusText.of(context, app.reports[p.id], app.alertSettings, p.id in app.refreshing)
                        Card(Modifier.fillMaxWidth().clickable { onParcel(p.id) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text(listOfNotNull(p.group, OdFmt.ha(context, p.areaHa)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (s.hasHistory) Column(horizontalAlignment = Alignment.End) {
                                        Text(OdFmt.mm(context, s.campaignToDate.mm), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text(stringResource(R.string.od_since_sept), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (s.crops.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    s.crops.forEach { CropChip(it.cropKind, listOf(it.block, it.cropKind.code).filter(String::isNotBlank).joinToString(" ")) }
                                }
                                if (p.location == null) {
                                    OutlinedButton(onClick = { onLocate(p.id) }) {
                                        Icon(Icons.Default.LocationOn, null)
                                        Text(stringResource(R.string.od_place_on_map), Modifier.padding(start = 6.dp))
                                    }
                                } else if (app.reports[p.id] != null) {
                                    Text("${status.emoji} ${status.text}", style = MaterialTheme.typography.bodySmall, color = if (status.isRain) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var area by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.od_add_parcel)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.od_name)) }, singleLine = true)
                    OutlinedTextField(area, { area = it }, label = { Text(stringResource(R.string.od_area_ha)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    adding = false
                    vm.addParcel(name, area.replace(',', '.').toDoubleOrNull(), null) { id -> onLocate(id) }
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** OpenStreetMap with every parcel's outline, coloured by its main crop this campaign. */
@Composable
fun ParcelMap(vm: MainViewModel, onParcel: (String) -> Unit, modifier: Modifier = Modifier, focus: String? = null) {
    val farm by vm.farmState.collectAsStateWithLifecycle()
    val campaign = Campaigns.current()
    val fallback = MaterialTheme.colorScheme.primary.toArgb()
    Box(modifier.fillMaxWidth()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
                    minZoomLevel = 5.0
                    maxZoomLevel = 19.0
                    controller.setZoom(6.0)
                    controller.setCenter(OsmPoint(MoroccoBounds.center.lat, MoroccoBounds.center.lon))
                }
            },
            update = { map ->
                map.overlays.clear()
                val points = mutableListOf<OsmPoint>()
                farm.data.parcels.forEach { p ->
                    val crop = farm.data.crops.filter { it.parcelId == p.id && it.campaign == campaign }.maxByOrNull { it.areaHa ?: 0.0 }?.cropKind
                    val color = crop?.color?.toInt() ?: fallback
                    if (p.polygon.size >= 3) {
                        val ring = p.polygon.map { OsmPoint(it.lat, it.lon) }
                        points += ring
                        map.overlays.add(Polygon(map).apply {
                            this.points = ring
                            fillPaint.color = (color and 0x00FFFFFF) or 0x55000000
                            outlinePaint.color = color
                            outlinePaint.strokeWidth = if (p.id == focus) 8f else 4f
                            title = p.name
                            setOnClickListener { _, _, _ -> onParcel(p.id); true }
                        })
                    }
                    p.location?.let { loc ->
                        points += OsmPoint(loc.lat, loc.lon)
                        map.overlays.add(Marker(map).apply {
                            position = OsmPoint(loc.lat, loc.lon)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = listOfNotNull(p.name, crop?.let { "${it.emoji} ${it.fr}" }).joinToString(" · ")
                            setOnMarkerClickListener { _, _ -> onParcel(p.id); true }
                        })
                    }
                }
                val focusPoints = farm.parcel(focus)?.let { f -> (f.polygon.ifEmpty { listOfNotNull(f.location) }).map { OsmPoint(it.lat, it.lon) } }
                val target = focusPoints?.takeIf { it.isNotEmpty() } ?: points
                if (target.isNotEmpty()) {
                    map.post {
                        if (target.size == 1) {
                            map.controller.setZoom(15.0)
                            map.controller.setCenter(target.first())
                        } else {
                            runCatching { map.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(target).increaseByScale(1.3f), false) }
                        }
                    }
                }
                map.invalidate()
            },
            onRelease = { it.onDetach() },
        )
        Text(stringResource(R.string.map_attribution), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
    }
}

/** Tap the map to set where a parcel is (when it has no outline from the KML). */
@Composable
fun LocateParcelScreen(vm: MainViewModel, parcelId: String, onDone: () -> Unit) {
    val farm by vm.farmState.collectAsStateWithLifecycle()
    val parcel = farm.parcel(parcelId)
    var pin by remember { mutableStateOf(parcel?.location) }
    Scaffold(topBar = { BackTopBar(stringResource(R.string.od_place_title, parcel?.name.orEmpty()), onDone) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        MapView(ctx).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            minZoomLevel = 5.0
                            maxZoomLevel = 19.0
                            setScrollableAreaLimitDouble(BoundingBox(MoroccoBounds.NORTH, MoroccoBounds.EAST, MoroccoBounds.SOUTH, MoroccoBounds.WEST))
                            val others = farm.data.parcels.mapNotNull { it.location }
                            val start = pin ?: others.firstOrNull()
                            controller.setZoom(if (start != null) 13.0 else 5.6)
                            controller.setCenter(start?.let { OsmPoint(it.lat, it.lon) } ?: OsmPoint(MoroccoBounds.center.lat, MoroccoBounds.center.lon))
                            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                                override fun singleTapConfirmedHelper(p: OsmPoint): Boolean {
                                    val point = GeoPoint(p.latitude, p.longitude)
                                    if (MoroccoBounds.contains(point)) pin = point
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
                                title = parcel?.name
                            })
                        }
                        map.invalidate()
                    },
                    onRelease = { it.onDetach() },
                )
            }
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    pin?.let { "%.5f, %.5f".format(java.util.Locale.ROOT, it.lat, it.lon) } ?: stringResource(R.string.od_place_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    enabled = pin != null,
                    onClick = {
                        vm.updateParcel(parcelId) { it.copy(center = pin) }
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Icon(Icons.Default.Place, null)
                    Text(stringResource(R.string.action_save), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
