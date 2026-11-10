package ma.odema.rain.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ma.odema.core.MoroccoCities
import ma.odema.core.MoroccoRegions
import ma.odema.rain.Fmt
import ma.odema.rain.R
import ma.odema.rain.Weather
import ma.odema.rain.coreLang
import ma.odema.rain.data.OverviewEntry
import ma.odema.rain.ui.MainViewModel

/** All 12 regions at a glance: today, tomorrow and the day after for each regional capital. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(vm: MainViewModel) {
    val overview by vm.overview.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var wettestFirst by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadOverview() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.overview_title))
                        Text(stringResource(R.string.overview_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.loadOverview(force = true) }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val o = overview) {
                MainViewModel.OverviewState.Idle, MainViewModel.OverviewState.Loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                is MainViewModel.OverviewState.Failed -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.overview_failed, o.error))
                    TextButton(onClick = { vm.loadOverview(force = true) }) { Text(stringResource(R.string.action_retry)) }
                }
                is MainViewModel.OverviewState.Ready -> {
                    val entries = o.data.entries.let { list ->
                        if (wettestFirst) list.sortedByDescending { e -> e.daily.take(3).sumOf { it.precipitationSum ?: 0.0 } } else list
                    }
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(selected = !wettestFirst, onClick = { wettestFirst = false }, label = { Text(stringResource(R.string.overview_by_region)) })
                                FilterChip(selected = wettestFirst, onClick = { wettestFirst = true }, label = { Text(stringResource(R.string.overview_wettest)) }, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                        items(entries, key = { it.cityId }) { entry -> RegionCard(vm, entry) }
                        item {
                            Text(
                                stringResource(R.string.overview_updated, Fmt.ago(context, o.data.fetchedAt)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            TextButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.marocmeteo.ma")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }) { Text(stringResource(R.string.overview_official)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegionCard(vm: MainViewModel, entry: OverviewEntry) {
    val context = LocalContext.current
    val lang = context.coreLang
    val city = MoroccoCities.byId(entry.cityId) ?: return
    val region = MoroccoRegions.byNumber(city.region)
    val wet = entry.daily.take(3).any { (it.precipitationSum ?: 0.0) >= 1.0 }
    var asked by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().clickable(enabled = !asked) {
            asked = true
            vm.addCity(city, city.name(lang))
        },
        colors = CardDefaults.cardColors(containerColor = if (wet) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(region?.name(lang).orEmpty(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(city.name(lang), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                entry.current?.let {
                    Text(Weather.emoji(it.code, it.isDay ?: true), fontSize = 22.sp)
                    Text(Fmt.temp(context, it.temperature), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 6.dp))
                }
            }
            Row(Modifier.padding(top = 8.dp)) {
                entry.daily.take(3).forEach { day ->
                    Column(Modifier.width(0.dp).weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(Fmt.day(context, day.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Weather.emoji(day.code), fontSize = 20.sp)
                        val rain = day.precipitationSum ?: 0.0
                        Text(
                            if (rain >= 0.1) Fmt.mmText(context, rain) else "–",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (rain >= 1) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (rain >= 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("${(day.probabilityMax ?: 0.0).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            Text(
                stringResource(if (asked) R.string.overview_added else R.string.overview_tap_to_add),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
