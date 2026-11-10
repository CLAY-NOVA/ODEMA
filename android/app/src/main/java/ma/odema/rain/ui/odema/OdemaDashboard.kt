package ma.odema.rain.ui.odema

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ma.odema.core.farm.Campaigns
import ma.odema.core.farm.FarmAnalytics
import ma.odema.core.farm.RainHistory
import ma.odema.rain.Fmt
import ma.odema.rain.R
import ma.odema.rain.StatusText
import ma.odema.rain.coreLang
import ma.odema.rain.ui.MainViewModel
import ma.odema.rain.ui.components.LegendItem
import ma.odema.rain.ui.components.Line
import ma.odema.rain.ui.components.LineChart
import ma.odema.rain.ui.components.SectionHeader
import ma.odema.rain.ui.components.ShareBar
import java.time.LocalDate

/** Whole-farm dashboard: campaign rain vs normal, surfaces by crop, every parcel at a glance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OdemaDashboardScreen(vm: MainViewModel, onParcel: (String) -> Unit, onParcels: () -> Unit) {
    val farm by vm.farmState.collectAsStateWithLifecycle()
    val app by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val today = LocalDate.now().toEpochDay()
    val summary = remember(farm.data, farm.series, farm.outlooks, today) { FarmAnalytics.farm(farm.data, farm.series, farm.outlooks, today) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ODEMA", fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.od_campaign, OdFmt.campaign(summary.campaign)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (farm.syncing) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { vm.syncFarm(); vm.refresh() }) { Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh)) }
                },
            )
        },
    ) { padding ->
        if (farm.loaded && farm.data.parcels.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🗺️", fontSize = 56.sp)
                Text(stringResource(R.string.od_empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(stringResource(R.string.od_empty_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                Button(onClick = onParcels) { Text(stringResource(R.string.od_go_parcels)) }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            // Location warning
            val unplaced = farm.data.parcels.count { it.location == null }
            if (unplaced > 0) item {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp).clickable(onClick = onParcels),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(stringResource(R.string.od_unplaced_title, unplaced), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(stringResource(R.string.od_unplaced_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            // Campaign rain
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.od_campaign_rain), style = MaterialTheme.typography.titleSmall)
                        Row(verticalAlignment = Alignment.Bottom) {
                            BigNumber(summary.campaignToDate?.let { Fmt.mm(context, it) } ?: "–")
                            Text(" " + stringResource(R.string.od_mm), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                            Spacer(Modifier.weight(1f))
                            summary.ratioToNormal?.let {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(OdFmt.percent(it), style = MaterialTheme.typography.headlineSmall, color = ratioColor(it), fontWeight = FontWeight.Bold)
                                    Text(stringResource(R.string.od_of_normal), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Text(
                            listOfNotNull(
                                summary.normalToDate?.let { stringResource(R.string.od_normal_to_date, OdFmt.mm(context, it)) },
                                summary.lastCampaignToDate?.let { stringResource(R.string.od_last_year_to_date, OdFmt.mm(context, it)) },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(stringResource(R.string.od_area_weighted), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                TileRow { m ->
                    StatTile(stringResource(R.string.od_last7), OdFmt.mm(context, summary.last7), m)
                    StatTile(stringResource(R.string.od_next7), OdFmt.mm(context, summary.next7), m, accent = MaterialTheme.colorScheme.primary)
                }
                TileRow { m ->
                    StatTile(stringResource(R.string.od_total_area), OdFmt.ha(context, summary.totalAreaHa), m, sub = stringResource(R.string.od_parcels_count, summary.parcels.size))
                    StatTile(stringResource(R.string.od_planted_area), OdFmt.ha(context, summary.plantedAreaHa), m, sub = stringResource(R.string.od_crops_count, summary.cropAreas.size))
                }
            }

            // Cumulative campaign rain (area-weighted parcels → use the largest parcel's curves for shape)
            val reference = summary.parcels.filter { it.hasHistory }.maxByOrNull { it.parcel.areaHa ?: 0.0 }
            if (reference != null) item {
                val p = reference.parcel
                val h = RainHistory(farm.seriesFor(p), farm.data.gauges.filter { it.parcelId == p.id })
                val c = summary.campaign
                val current = h.cumulative(c, today)
                val normal = h.cumulativeNormal(today)
                val last = h.cumulative(c - 1)
                SectionHeader(stringResource(R.string.od_cumulative_title, p.name))
                LineChart(
                    lines = listOf(
                        Line(normal, MaterialTheme.colorScheme.tertiary, dashed = true, width = 3f),
                        Line(last, MaterialTheme.colorScheme.outline, width = 3f),
                        Line(current, MaterialTheme.colorScheme.primary, width = 6f),
                    ),
                    xLabels = monthTicks(context, c),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    LegendItem(MaterialTheme.colorScheme.primary, OdFmt.campaign(c))
                    LegendItem(MaterialTheme.colorScheme.outline, OdFmt.campaign(c - 1))
                    LegendItem(MaterialTheme.colorScheme.tertiary, stringResource(R.string.od_normal), dashed = true)
                }
            }

            // Surfaces by crop
            if (summary.cropAreas.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.od_assolement_title, OdFmt.campaign(summary.campaign))) }
                items(summary.cropAreas) { ca ->
                    val share = if (summary.plantedAreaHa > 0) (ca.areaHa / summary.plantedAreaHa).toFloat() else 0f
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${ca.crop.emoji} ${ca.crop.name(context.coreLang)}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text("${OdFmt.ha(context, ca.areaHa)} · ${(share * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                        ShareBar(share, ca.crop.composeColor(), Modifier.fillMaxWidth().padding(top = 3.dp))
                    }
                }
            }

            // Every parcel
            item {
                SectionHeader(stringResource(R.string.od_parcels_title))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    listOf(R.string.od_col_parcel to 2.2f, R.string.od_col_campaign to 1.3f, R.string.od_col_normal to 1f, R.string.od_col_7d to 1f, R.string.od_col_next to 1f).forEach { (t, w) ->
                        Text(stringResource(t), Modifier.weight(w), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            items(summary.parcels, key = { it.parcel.id }) { s ->
                val report = app.reports[s.parcel.id]
                val status = StatusText.of(context, report, app.alertSettings, s.parcel.id in app.refreshing)
                Column(Modifier.fillMaxWidth().clickable { onParcel(s.parcel.id) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(2.2f)) {
                            Text(s.parcel.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                s.crops.joinToString(" ") { it.cropKind.emoji }.ifEmpty { OdFmt.ha(context, s.parcel.areaHa) },
                                style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            )
                        }
                        Text(if (s.hasHistory) Fmt.mm(context, s.campaignToDate.mm) else "–", Modifier.weight(1.3f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(OdFmt.percent(s.ratioToNormal), Modifier.weight(1f), color = ratioColor(s.ratioToNormal), style = MaterialTheme.typography.bodyMedium)
                        Text(if (s.hasHistory) Fmt.mm(context, s.last7) else "–", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(s.next7?.let { Fmt.mm(context, it) } ?: "–", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    if (report != null && (status.isRain || status.badge != null)) {
                        Text("${status.emoji} ${status.text}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            }

            // History status
            item {
                SectionHeader(stringResource(R.string.od_history_title))
                HistoryStatus(vm)
            }
        }
    }
}

@Composable
fun HistoryStatus(vm: MainViewModel) {
    val farm by vm.farmState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val firstYear = farm.series.values.mapNotNull { RainHistory(it).firstYear }.minOrNull()
    val keys = farm.data.parcels.mapNotNull { it.historyKey }.distinct().size
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (firstYear != null) stringResource(R.string.od_history_range, firstYear, LocalDate.now().year, keys)
            else stringResource(R.string.od_history_none),
            style = MaterialTheme.typography.bodyMedium,
        )
        val remaining = farm.meta.chunksRemaining
        when {
            farm.syncing -> Text(stringResource(R.string.od_history_syncing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            remaining != null && remaining > 0 -> Text(
                stringResource(if (farm.meta.rateLimited) R.string.od_history_limit else R.string.od_history_remaining, remaining),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            farm.meta.lastSync != null -> Text(stringResource(R.string.od_history_uptodate, Fmt.ago(context, farm.meta.lastSync!!)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.od_history_source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        OutlinedButton(onClick = { vm.syncFarm() }, enabled = !farm.syncing) { Text(stringResource(R.string.od_sync_now)) }
        Spacer(Modifier.width(1.dp))
    }
}

/** X-axis ticks at the start of each month of the campaign (Sept → Aug). */
fun monthTicks(context: android.content.Context, campaign: Int): List<Pair<Int, String>> {
    val start = Campaigns.start(campaign)
    val names = OdFmt.campaignMonths(context)
    return (0 until 12 step 2).map { m -> (start.plusMonths(m.toLong()).toEpochDay() - start.toEpochDay()).toInt() to names[m] }
}
