package ma.odema.rain.ui.screens

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ma.odema.core.ForecastReport
import ma.odema.core.MoroccoRegions
import ma.odema.core.RainAnalyzer
import ma.odema.core.SourceId
import ma.odema.core.SourceKind
import ma.odema.core.SourceOutcome
import ma.odema.rain.Fmt
import ma.odema.rain.R
import ma.odema.rain.StatusText
import ma.odema.rain.Weather
import ma.odema.rain.coreLang
import ma.odema.rain.ui.MainViewModel
import ma.odema.rain.ui.components.BackTopBar
import ma.odema.rain.ui.components.RainChart
import ma.odema.rain.ui.components.SectionHeader
import ma.odema.rain.ui.components.SwitchRow

@Composable
fun PlaceDetailScreen(vm: MainViewModel, placeId: String, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val place = state.places.firstOrNull { it.id == placeId }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (place == null) {
        Scaffold(topBar = { BackTopBar("", onBack) }) { padding ->
            Text(stringResource(R.string.detail_removed), Modifier.padding(padding).padding(24.dp))
        }
        return
    }
    val name = place.displayName(context.coreLang)
    val report = state.reports[place.id]
    val settings = state.alertSettings
    val now = System.currentTimeMillis() / 1000
    val outlook = report?.let { RainAnalyzer.outlook(it, settings, now) }
    val status = StatusText.of(context, report, settings, place.id in state.refreshing, now)

    Scaffold(
        topBar = {
            BackTopBar(name, onBack) {
                IconButton(onClick = { vm.refresh(place.id) }, enabled = place.id !in state.refreshing) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                }
                IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_rename)) }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete)) }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            // Header
            item {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val current = report?.current
                    Text(if (current?.code != null) Weather.emoji(current.code, current.isDay ?: true) else status.emoji, fontSize = 56.sp)
                    current?.temperature?.let {
                        Text(Fmt.temp(context, it), style = MaterialTheme.typography.displaySmall)
                        Text(Weather.description(context, current.code), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.size(10.dp))
                    Text(
                        status.text,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (status.isRain) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    if (outlook != null && outlook.available > 0) {
                        val parts = mutableListOf(stringResource(R.string.sources_see_rain, outlook.agreeing, outlook.available))
                        if (outlook.agreeing > 0) outlook.peakIntensity?.let { parts += context.getString(R.string.up_to_intensity, Fmt.intensity(context, it), Fmt.mm(context, it)) }
                        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                    val region = MoroccoRegions.byNumber(place.region)?.name(context.coreLang)
                    val elevation = place.elevation?.let { context.getString(R.string.detail_elevation, it.toInt()) }
                    Text(listOfNotNull(region, elevation).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
                }
            }

            if (report == null) {
                item { Text(stringResource(R.string.status_no_forecast), Modifier.padding(24.dp)) }
                return@LazyColumn
            }

            // Active warnings
            val warnings = RainAnalyzer.hazards(report, settings, now).mapNotNull { StatusText.hazard(context, it, now) }
            if (warnings.isNotEmpty()) {
                item {
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.detail_warnings), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            warnings.forEach { Text("• $it", color = MaterialTheme.colorScheme.onErrorContainer) }
                        }
                    }
                }
            }

            // Chart
            item {
                SectionHeader(stringResource(R.string.detail_next_hours))
                RainChart(
                    slots = RainAnalyzer.timeline(report, settings, now, hours = 24),
                    threshold = settings.requiredAgreement,
                    windowEnd = now + settings.lookaheadSeconds,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    stringResource(R.string.detail_chart_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            // Hour by hour
            item {
                SectionHeader(stringResource(R.string.detail_hourly))
                HourlyStrip(report, now)
            }

            // 7 days
            if (report.daily.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.detail_days)) }
                items(report.daily) { day ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(Fmt.day(context, day.date), Modifier.width(92.dp), style = MaterialTheme.typography.bodyLarge)
                        Text(Weather.emoji(day.code), fontSize = 22.sp, modifier = Modifier.width(40.dp))
                        Column(Modifier.weight(1f)) {
                            val rain = day.precipitationSum ?: 0.0
                            Text(
                                if (rain >= 0.1) "💧 ${Fmt.mmText(context, rain)} · ${(day.probabilityMax ?: 0.0).toInt()}%"
                                else stringResource(R.string.detail_dry_day, (day.probabilityMax ?: 0.0).toInt()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (rain >= 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            day.snowfallSum?.takeIf { it >= 0.1 }?.let {
                                Text("❄️ ${Fmt.cmText(context, it)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("${Fmt.temp(context, day.tempMin)} / ${Fmt.temp(context, day.tempMax)}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Per source
            item { SectionHeader(stringResource(R.string.detail_sources, Fmt.duration(context, settings.lookaheadSeconds))) }
            items(SourceId.entries.filter { it in settings.enabledSources }) { source ->
                SourceRow(source, report, outlook?.verdicts?.firstOrNull { it.source == source }, now)
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            }

            // Place settings
            item {
                SectionHeader(stringResource(R.string.detail_settings))
                SwitchRow(stringResource(R.string.detail_alerts_toggle), place.alertsEnabled, { vm.setAlerts(place.id, it) })
                SwitchRow(
                    stringResource(R.string.detail_widget_place),
                    state.widgetPlace?.id == place.id,
                    { if (it) vm.setWidgetPlace(place.id) },
                    subtitle = stringResource(R.string.detail_widget_place_desc),
                )
                if (place.followsDevice) {
                    Text(stringResource(R.string.follows_device), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    stringResource(R.string.detail_fetched, Fmt.ago(context, report.fetchedAt)) + " · " +
                        "%.4f, %.4f".format(java.util.Locale.ROOT, place.point.lat, place.point.lon),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.action_rename)) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(place.id, text); renaming = false }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.detail_remove_title, name)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.remove(place.id); onBack() }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun HourlyStrip(report: ForecastReport, now: Long) {
    val context = LocalContext.current
    val hours = (report.outcomes[SourceId.BEST_MATCH] as? SourceOutcome.Success)?.samples.orEmpty()
        .filter { it.end > now }
        .groupBy { (it.start / 3600) * 3600 }
        .entries.take(24)
    if (hours.isEmpty()) {
        Text(stringResource(R.string.detail_hourly_unavailable), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        return
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        hours.forEach { (hour, samples) ->
            val amount = samples.sumOf { it.amountBetween(hour, hour + 3600) }
            val probability = samples.mapNotNull { it.probability }.maxOrNull()
            val code = samples.mapNotNull { it.code }.maxOrNull()
            val temp = samples.firstNotNullOfOrNull { it.temp }
            Column(Modifier.widthIn(min = 56.dp).padding(horizontal = 4.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Fmt.time(hour), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Weather.emoji(code), fontSize = 22.sp)
                Text(Fmt.temp(context, temp), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (amount >= 0.05) Fmt.mm(context, amount) else "–",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (amount >= 0.1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
                Text(probability?.let { "${it.toInt()}%" } ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun SourceRow(source: SourceId, report: ForecastReport, verdict: ma.odema.core.SourceVerdict?, now: Long) {
    val context = LocalContext.current
    val outcome = report.outcomes[source]
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(source.displayName, style = MaterialTheme.typography.bodyLarge)
            val kind = stringResource(
                when (source.kind) {
                    SourceKind.BLEND -> R.string.kind_blend
                    SourceKind.ENSEMBLE -> R.string.kind_ensemble
                    SourceKind.MODEL -> R.string.kind_model
                    SourceKind.STATION -> R.string.kind_station
                },
            )
            val note = (outcome as? SourceOutcome.Success)?.note
            val age = report.sourceTimes[source]?.takeIf { now - it > 15 * 60 }?.let { context.getString(R.string.source_age, Fmt.ago(context, it, now)) }
            Text(listOfNotNull(kind, note, age).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
            when (outcome) {
                null -> Text("—", color = MaterialTheme.colorScheme.outline)
                SourceOutcome.NotCovered -> Text(stringResource(R.string.source_not_covered), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                is SourceOutcome.Failure -> {
                    Text("⚠️ " + stringResource(R.string.source_error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Text(outcome.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 2, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 160.dp))
                }
                is SourceOutcome.Success -> if (verdict == null) {
                    Text(stringResource(R.string.source_no_window), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                } else {
                    if (verdict.predictsRain && verdict.firstRain != null) {
                        Text(stringResource(R.string.source_rain_at, Fmt.time(verdict.firstRain!!)), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text(stringResource(R.string.source_dry), color = MaterialTheme.colorScheme.secondary)
                    }
                    val numbers = listOfNotNull(
                        verdict.peakIntensity?.let { Fmt.mmhText(context, it) },
                        verdict.peakProbability?.let { "${it.toInt()}%" },
                    ).joinToString(" · ")
                    if (numbers.isNotEmpty()) Text(numbers, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
