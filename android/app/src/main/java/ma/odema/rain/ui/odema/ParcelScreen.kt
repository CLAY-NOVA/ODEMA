package ma.odema.rain.ui.odema

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ma.odema.core.farm.Campaigns
import ma.odema.core.farm.FarmAnalytics
import ma.odema.core.farm.GaugeReading
import ma.odema.core.farm.RainHistory
import ma.odema.rain.Fmt
import ma.odema.rain.R
import ma.odema.rain.StatusText
import ma.odema.rain.ui.MainViewModel
import ma.odema.rain.ui.components.BackTopBar
import ma.odema.rain.ui.components.BarChart
import ma.odema.rain.ui.components.ChoiceRow
import ma.odema.rain.ui.components.LegendItem
import ma.odema.rain.ui.components.Line
import ma.odema.rain.ui.components.LineChart
import ma.odema.rain.ui.components.ScrollingBarChart
import ma.odema.rain.ui.components.SectionHeader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ParcelScreen(
    vm: MainViewModel,
    parcelId: String,
    onBack: () -> Unit,
    onLocate: () -> Unit,
    onForecast: () -> Unit,
    onEditCrop: (String?, Int) -> Unit,
) {
    val farm by vm.farmState.collectAsStateWithLifecycle()
    val app by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val parcel = farm.parcel(parcelId)
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var addingGauge by remember { mutableStateOf(false) }
    val today = LocalDate.now().toEpochDay()
    val current = Campaigns.ofDay(today)
    var campaign by remember { mutableStateOf(current) }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = vm.farm.csv(parcelId) ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
    }

    if (parcel == null) {
        Scaffold(topBar = { BackTopBar("", onBack) }) { p -> Text(stringResource(R.string.detail_removed), Modifier.padding(p).padding(24.dp)) }
        return
    }
    val series = farm.seriesFor(parcel)
    val history = remember(series, farm.data.gauges) { RainHistory(series, farm.data.gauges.filter { it.parcelId == parcel.id }) }
    val summary = remember(parcel, series, farm.data, farm.outlooks, campaign) {
        FarmAnalytics.parcel(parcel, series, farm.data, farm.outlookFor(parcel), today, current)
    }

    Scaffold(
        topBar = {
            BackTopBar(parcel.name, onBack) {
                IconButton(onClick = onLocate) { Icon(Icons.Default.LocationOn, stringResource(R.string.od_place_on_map)) }
                IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, stringResource(R.string.action_rename)) }
                IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, stringResource(R.string.action_delete)) }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            // Identity + rain soon
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        listOfNotNull(parcel.group, OdFmt.ha(context, parcel.areaHa),
                            parcel.computedAreaHa?.takeIf { parcel.declaredAreaHa != null }?.let { stringResource(R.string.od_drawn_area, OdFmt.ha(context, it)) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (parcel.location == null) {
                        OutlinedButton(onClick = onLocate, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.od_place_on_map)) }
                    } else {
                        val status = StatusText.of(context, app.reports[parcel.id], app.alertSettings, parcel.id in app.refreshing)
                        Card(Modifier.fillMaxWidth().padding(top = 8.dp).clickable(onClick = onForecast), colors = CardDefaults.cardColors(containerColor = if (status.isRain) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(status.emoji, style = MaterialTheme.typography.headlineSmall)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(status.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(R.string.od_forecast_details), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                summary.next7?.let { Text(stringResource(R.string.od_next7_short, OdFmt.mm(context, it)), style = MaterialTheme.typography.labelLarge) }
                            }
                        }
                    }
                }
            }

            // Key figures
            item {
                SectionHeader(stringResource(R.string.od_campaign_figures, OdFmt.campaign(current)))
                if (!summary.hasHistory) {
                    Text(
                        stringResource(if (parcel.location == null) R.string.od_history_needs_location else R.string.od_history_loading),
                        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    TileRow { m ->
                        StatTile(stringResource(R.string.od_since_sept), OdFmt.mm(context, summary.campaignToDate.mm), m,
                            sub = summary.ratioToNormal?.let { stringResource(R.string.od_pct_normal, OdFmt.percent(it)) }, accent = ratioColor(summary.ratioToNormal))
                        StatTile(stringResource(R.string.od_normal_short), OdFmt.mm(context, summary.normalToDate), m,
                            sub = summary.normalCampaign?.let { stringResource(R.string.od_normal_full, OdFmt.mm(context, it)) })
                    }
                    TileRow { m ->
                        StatTile(stringResource(R.string.od_last_year_short), OdFmt.mm(context, summary.lastCampaignToDate.mm.takeIf { summary.lastCampaignToDate.covered > 0 }), m)
                        StatTile(stringResource(R.string.od_rainy_days), "${summary.rainyDaysCampaign}", m, sub = stringResource(R.string.od_rainy_days_sub))
                    }
                    TileRow { m ->
                        StatTile(stringResource(R.string.od_last7), OdFmt.mm(context, summary.last7), m)
                        StatTile(stringResource(R.string.od_last30), OdFmt.mm(context, summary.last30), m)
                    }
                    TileRow { m ->
                        StatTile(
                            stringResource(R.string.od_last_rain),
                            summary.lastRain?.let { OdFmt.shortDate(context, it.first) } ?: "–", m,
                            sub = summary.lastRain?.let { stringResource(R.string.od_last_rain_sub, OdFmt.mm(context, it.second), summary.daysSinceRain ?: 0) },
                        )
                        StatTile(
                            stringResource(R.string.od_wettest_day),
                            summary.wettestDayCampaign?.let { OdFmt.mm(context, it.second) } ?: "–", m,
                            sub = summary.wettestDayCampaign?.let { OdFmt.shortDate(context, it.first) },
                        )
                    }
                    summary.sinceSowing?.let { (sown, mm) ->
                        TileRow { m ->
                            StatTile(stringResource(R.string.od_since_sowing), OdFmt.mm(context, mm), m, sub = OdFmt.date(context, sown))
                            StatTile(stringResource(R.string.od_next7), OdFmt.mm(context, summary.next7), m, accent = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // Charts
            if (summary.hasHistory) {
                item {
                    val firstCampaign = history.firstDay?.let { Campaigns.ofDay(it) } ?: current
                    SectionHeader(stringResource(R.string.od_cumulative))
                    ChoiceRow(
                        stringResource(R.string.od_campaign_pick),
                        (current downTo firstCampaign).map { it to OdFmt.campaign(it) },
                        campaign,
                    ) { campaign = it }
                    LineChart(
                        lines = listOf(
                            Line(history.cumulativeNormal(today), MaterialTheme.colorScheme.tertiary, dashed = true, width = 3f),
                            Line(history.cumulative(campaign - 1), MaterialTheme.colorScheme.outline, width = 3f),
                            Line(history.cumulative(campaign, today), MaterialTheme.colorScheme.primary, width = 6f),
                        ),
                        xLabels = monthTicks(context, campaign),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        LegendItem(MaterialTheme.colorScheme.primary, OdFmt.campaign(campaign))
                        LegendItem(MaterialTheme.colorScheme.outline, OdFmt.campaign(campaign - 1))
                        LegendItem(MaterialTheme.colorScheme.tertiary, stringResource(R.string.od_normal), dashed = true)
                    }
                    SectionHeader(stringResource(R.string.od_monthly, OdFmt.campaign(campaign)))
                    BarChart(
                        values = history.monthly(campaign),
                        labels = OdFmt.campaignMonths(context),
                        reference = history.monthlyNormal(today),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        LegendItem(MaterialTheme.colorScheme.primary, OdFmt.campaign(campaign))
                        LegendItem(MaterialTheme.colorScheme.tertiary, stringResource(R.string.od_normal))
                    }
                }
                item {
                    val totals = history.campaignTotals(today)
                    if (totals.isNotEmpty()) {
                        val years = totals.keys.sorted()
                        SectionHeader(stringResource(R.string.od_yearly, years.first(), years.last() + 1))
                        ScrollingBarChart(
                            values = years.map { totals[it] },
                            labels = years.map { if (it % 10 == 0) "$it" else "" },
                            average = history.normalCampaign(today),
                            color = MaterialTheme.colorScheme.primary,
                            highlight = years.indexOf(campaign).takeIf { it >= 0 },
                        )
                        history.extremes(today)?.let { (wet, dry) ->
                            Text(
                                stringResource(R.string.od_extremes, OdFmt.campaign(wet.first), OdFmt.mm(context, wet.second), OdFmt.campaign(dry.first), OdFmt.mm(context, dry.second)),
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        Text(stringResource(R.string.od_yearly_caption), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }

            // Assolement of this parcel
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { SectionHeader(stringResource(R.string.od_crops_title)) }
                    IconButton(onClick = { onEditCrop(null, current) }) { Icon(Icons.Default.Add, stringResource(R.string.od_add_crop)) }
                }
            }
            val crops = farm.data.crops.filter { it.parcelId == parcel.id }.sortedWith(compareByDescending<ma.odema.core.farm.CropEntry> { it.campaign }.thenBy { it.block })
            if (crops.isEmpty()) item { Text(stringResource(R.string.od_no_crops), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium) }
            items(crops, key = { it.id }) { e ->
                Row(Modifier.fillMaxWidth().clickable { onEditCrop(e.id, e.campaign) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(OdFmt.campaign(e.campaign), Modifier.padding(end = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text("${e.cropKind.emoji} ${listOf(e.block).filter(String::isNotBlank).joinToString()}${if (e.block.isNotBlank()) " — " else ""}${OdFmt.cropLabel(context, e)}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOfNotNull(
                                e.areaHa?.let { OdFmt.ha(context, it) },
                                e.sowingDate?.let { stringResource(R.string.od_sown, OdFmt.shortDate(context, it)) },
                                e.yieldTPerHa?.let { stringResource(R.string.od_yield_value, Fmt.number(context, it, 1)) },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Rain gauge
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { SectionHeader(stringResource(R.string.od_gauge_title)) }
                    IconButton(onClick = { addingGauge = true }) { Icon(Icons.Default.Add, stringResource(R.string.od_add_gauge)) }
                }
                Text(stringResource(R.string.od_gauge_help), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(FarmAnalytics.gaugeComparison(parcel, farm.data, series), key = { it.first.id }) { (g, model) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (g.isDaily) OdFmt.date(context, g.date) else "${OdFmt.shortDate(context, g.fromDate)} → ${OdFmt.date(context, g.date)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            listOfNotNull(model?.let { stringResource(R.string.od_gauge_vs_model, OdFmt.mm(context, it)) }, g.note.takeIf { it.isNotBlank() }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("🌧️ " + OdFmt.mm(context, g.mm), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { vm.removeGauge(g.id) }) { Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.outline) }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            }

            // Settings & export
            item {
                SectionHeader(stringResource(R.string.detail_settings))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.detail_alerts_toggle), Modifier.weight(1f))
                    Switch(parcel.alertsEnabled, { on -> vm.updateParcel(parcel.id) { it.copy(alertsEnabled = on) } })
                }
                OutlinedButton(
                    onClick = { csvLauncher.launch("ODEMA-${parcel.name}-pluie.csv") },
                    enabled = summary.hasHistory,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text(stringResource(R.string.od_export_csv)) }
                parcel.location?.let {
                    Text("%.5f, %.5f".format(java.util.Locale.ROOT, it.lat, it.lon), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                if (parcel.notes.isNotBlank()) Text(parcel.notes, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (editing) {
        var name by remember { mutableStateOf(parcel.name) }
        var area by remember { mutableStateOf(parcel.declaredAreaHa?.toString() ?: "") }
        var group by remember { mutableStateOf(parcel.group.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.od_edit_parcel)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.od_name)) }, singleLine = true)
                    OutlinedTextField(group, { group = it }, label = { Text(stringResource(R.string.od_group)) }, singleLine = true)
                    OutlinedTextField(area, { area = it }, label = { Text(stringResource(R.string.od_area_ha)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        supportingText = { parcel.computedAreaHa?.let { Text(stringResource(R.string.od_drawn_area, OdFmt.ha(context, it))) } })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateParcel(parcel.id) { it.copy(name = name.trim().ifEmpty { it.name }, group = group.trim().ifEmpty { null }, declaredAreaHa = area.replace(',', '.').toDoubleOrNull()) }
                    editing = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.detail_remove_title, parcel.name)) },
            text = { Text(stringResource(R.string.od_delete_warning)) },
            confirmButton = { TextButton(onClick = { deleting = false; vm.removeParcel(parcel.id); onBack() }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (addingGauge) GaugeDialog(onDismiss = { addingGauge = false }) { from, to, mm, note ->
        vm.saveGauge(GaugeReading(vm.farm.newId(), parcel.id, to, from, mm, note))
        addingGauge = false
    }
}

@Composable
private fun GaugeDialog(onDismiss: () -> Unit, onSave: (Long, Long, Double, String) -> Unit) {
    val today = LocalDate.now().toEpochDay()
    var to by remember { mutableStateOf(today) }
    var from by remember { mutableStateOf(today) }
    var period by remember { mutableStateOf(false) }
    var mm by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.od_add_gauge)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.od_gauge_period), Modifier.weight(1f))
                    Switch(period, { period = it })
                }
                if (period) DateField(stringResource(R.string.od_from), from) { from = it }
                DateField(stringResource(if (period) R.string.od_to else R.string.od_date), to) { to = it }
                OutlinedTextField(mm, { mm = it }, label = { Text(stringResource(R.string.od_rain_mm)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.od_note)) }, singleLine = true)
            }
        },
        confirmButton = {
            val value = mm.replace(',', '.').toDoubleOrNull()
            TextButton(enabled = value != null && value >= 0, onClick = {
                onSave(if (period) minOf(from, to) else to, if (period) maxOf(from, to) else to, value!!, note.trim())
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** A date row that opens a calendar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, epochDay: Long?, onChange: (Long) -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text("$label: " + (epochDay?.let { OdFmt.date(context, it) } ?: "—"))
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (epochDay ?: LocalDate.now().toEpochDay()) * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay()) }
                    open = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state, Modifier.height(470.dp)) }
    }
}
