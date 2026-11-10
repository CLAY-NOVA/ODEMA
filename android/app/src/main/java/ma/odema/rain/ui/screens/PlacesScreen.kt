package ma.odema.rain.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ma.odema.core.MoroccoRegions
import ma.odema.core.Place
import ma.odema.core.RainAnalyzer
import ma.odema.rain.Fmt
import ma.odema.rain.R
import ma.odema.rain.StatusText
import ma.odema.rain.coreLang
import ma.odema.rain.data.AppState
import ma.odema.rain.notify.Notifier
import ma.odema.rain.ui.MainViewModel
import ma.odema.rain.ui.components.Pill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(vm: MainViewModel, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notificationsAllowed by remember { mutableStateOf(Notifier.canPost(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = Notifier.canPost(context)
    }
    // Ask for notification permission once, as soon as there is something to alert about.
    LaunchedEffect(state.places.isNotEmpty(), state.appSettings.askedNotifications) {
        if (Build.VERSION.SDK_INT >= 33 && state.places.isNotEmpty() && !state.appSettings.askedNotifications &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            vm.updateApp { it.copy(askedNotifications = true) }
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.places_title)) },
                actions = {
                    IconButton(onClick = { vm.refresh() }, enabled = state.refreshing.isEmpty()) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.action_add_place)) },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing.isNotEmpty(),
            onRefresh = { vm.refresh() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (state.loaded && state.places.isEmpty()) {
                EmptyPlaces(onAdd)
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!notificationsAllowed && state.places.isNotEmpty()) {
                        item { NotificationsOffCard() }
                    }
                    items(state.places, key = { it.id }) { place ->
                        PlaceCard(place, state, onClick = { onOpen(place.id) })
                    }
                    item {
                        Text(
                            state.lastRefresh?.let {
                                stringResource(R.string.places_checked, Fmt.ago(context, it), Fmt.duration(context, state.alertSettings.lookaheadSeconds))
                            } ?: stringResource(R.string.places_never_checked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPlaces(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🌦️", fontSize = 64.sp)
        Spacer(Modifier.size(12.dp))
        Text(stringResource(R.string.places_empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.places_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(20.dp))
        Button(onClick = onAdd) { Text(stringResource(R.string.action_add_place)) }
    }
}

@Composable
private fun NotificationsOffCard() {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.notifications_off_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(stringResource(R.string.notifications_off_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(stringResource(R.string.action_open_settings)) }
        }
    }
}

@Composable
fun PlaceCard(place: Place, state: AppState, onClick: () -> Unit) {
    val context = LocalContext.current
    val report = state.reports[place.id]
    val refreshing = place.id in state.refreshing
    val status = StatusText.of(context, report, state.alertSettings, refreshing)
    val now = System.currentTimeMillis() / 1000
    val hazards = report?.let { r ->
        listOfNotNull(
            RainAnalyzer.heavyRain(r, state.alertSettings, now).takeIf { it.triggered }?.let { "⚠️" },
            RainAnalyzer.storm(r, state.alertSettings, now).takeIf { it.triggered }?.let { "⛈️" },
            RainAnalyzer.snow(r, state.alertSettings, now).takeIf { it.triggered && state.alertSettings.snowEnabled }?.let { "❄️" },
        )
    }.orEmpty()
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (status.isRain) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(status.emoji, fontSize = 34.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        place.displayName(context.coreLang),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (place.followsDevice) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp).padding(start = 2.dp))
                    }
                    if (!place.alertsEnabled) Text(" 🔕", fontSize = 13.sp)
                    hazards.forEach { Text(" $it", fontSize = 14.sp) }
                }
                Text(
                    MoroccoRegions.byNumber(place.region)?.name(context.coreLang).orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    status.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status.isRain) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (status.isRain) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                report?.current?.temperature?.let { Text(Fmt.temp(context, it), style = MaterialTheme.typography.headlineSmall) }
                when {
                    refreshing -> Box(Modifier.padding(top = 4.dp)) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
                    status.badge != null -> Pill(status.badge, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
