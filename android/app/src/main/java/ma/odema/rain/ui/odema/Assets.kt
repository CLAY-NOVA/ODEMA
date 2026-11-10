package ma.odema.rain.ui.odema

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ma.odema.core.ops.Asset
import ma.odema.core.ops.AssetKind
import ma.odema.core.ops.OpsAnalytics
import ma.odema.core.ops.Reading
import ma.odema.core.ops.ReadingKind
import ma.odema.rain.R
import ma.odema.rain.ui.MainViewModel
import ma.odema.rain.ui.components.BackTopBar
import ma.odema.rain.ui.components.Pill
import ma.odema.rain.ui.components.SectionHeader
import java.time.LocalDate

/** Wells, power stations, basins, pivots, tractors… with their state. */
@Composable
fun AssetsScreen(vm: MainViewModel, onBack: () -> Unit, onOpen: (String) -> Unit, onNew: () -> Unit) {
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val cloud by vm.cloudState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val broken = OpsAnalytics.openIncidentsByAsset(ops.data)
    Scaffold(topBar = { BackTopBar(stringResource(R.string.assets_title), onBack) }, floatingActionButton = { if (cloud.canEdit) AddFab(stringResource(R.string.assets_new), onNew) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (ops.data.assets.isEmpty()) item { EmptyState("🧰", stringResource(R.string.assets_empty)) }
            ops.data.assets.groupBy { it.assetKind }.toSortedMap().forEach { (kind, list) ->
                item { SectionHeader("${kind.emoji} ${Labels.asset(context, kind)} (${list.size})") }
                items(list.sortedWith(compareBy({ it.code.filter(Char::isDigit).toIntOrNull() ?: 999 }, { it.name })), key = { it.id }) { a ->
                    val open = broken[a.id].orEmpty()
                    OpsRow(a.assetKind.emoji, a.name, assetSubtitle(context, a), onClick = { onOpen(a.id) }, trailing = {
                        val cs = MaterialTheme.colorScheme
                        when {
                            open.isNotEmpty() -> Pill(stringResource(R.string.asset_broken), cs.error, cs.onError)
                            !a.active -> Pill(stringResource(R.string.asset_inactive), cs.surfaceVariant, cs.onSurfaceVariant)
                            else -> {}
                        }
                    })
                    HorizontalDivider(Modifier.padding(start = 64.dp))
                }
            }
            item { Spacer(Modifier.padding(40.dp)) }
        }
    }
}

private fun defaultReading(kind: AssetKind) = when (kind) {
    AssetKind.POWER -> ReadingKind.METER_KWH
    AssetKind.WELL -> ReadingKind.FLOW
    AssetKind.BASIN -> ReadingKind.LEVEL
    AssetKind.PIVOT, AssetKind.PUMP, AssetKind.TRACTOR -> ReadingKind.PUMP_HOURS
    else -> ReadingKind.OTHER
}

@Composable
fun AssetScreen(vm: MainViewModel, assetId: String, onBack: () -> Unit, onEdit: () -> Unit, onIncident: (String) -> Unit, onNewIncident: () -> Unit) {
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val farm by vm.farmState.collectAsStateWithLifecycle()
    val cloud by vm.cloudState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val a = ops.data.asset(assetId) ?: run { EmptyState("🧰", stringResource(R.string.assets_empty)); return }
    var addReading by remember { mutableStateOf(false) }
    val readings = ops.data.readings.filter { it.assetId == a.id }.sortedByDescending { it.date }
    val incidents = ops.data.incidents.filter { it.assetId == a.id }.sortedByDescending { it.reportedAt }
    Scaffold(topBar = {
        BackTopBar("${a.assetKind.emoji} ${a.name}", onBack) { if (cloud.canEdit) IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, stringResource(R.string.assets_edit)) } }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(assetSubtitle(context, a), style = MaterialTheme.typography.bodyLarge)
                    farm.parcel(a.parcelId)?.let { Text(stringResource(R.string.asset_on_parcel, it.name), style = MaterialTheme.typography.bodyMedium) }
                    listOfNotNull(
                        a.depthM?.let { stringResource(R.string.asset_depth) + " : " + qtyText(context, it, "m") },
                        a.pumpKw?.let { stringResource(R.string.asset_pump) + " : " + qtyText(context, it, "kW") },
                        a.capacityM3?.let { stringResource(R.string.asset_capacity) + " : " + qtyText(context, it, "m³") },
                    ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (a.notes.isNotBlank()) Text(a.notes, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        if (cloud.canEdit) Button(onClick = { addReading = true }) { Text(stringResource(R.string.asset_add_reading)) }
                        if (cloud.canEdit) OutlinedButton(onClick = onNewIncident) { Text(stringResource(R.string.plan_report)) }
                    }
                }
            }
            if (a.assetKind == AssetKind.POWER) {
                val usage = OpsAnalytics.meterUsage(ops.data, a.id)
                if (usage.isNotEmpty()) item {
                    SectionHeader(stringResource(R.string.asset_usage))
                    usage.takeLast(6).reversed().forEach { (day, perDay) ->
                        Text("${OdFmt.shortDate(context, day)} : ${qtyText(context, perDay, "kWh")} / ${stringResource(R.string.ops_per_day)}", Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.asset_incidents) + " (${incidents.size})") }
            if (incidents.isEmpty()) item { Text(stringResource(R.string.asset_no_incidents), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(incidents, key = { it.id }) { i ->
                OpsRow(if (i.open) "⚠️" else "✅", i.title, listOfNotNull(i.reportedBy.takeIf { it.isNotBlank() }, relativeTime(i.reportedAt)).joinToString(" · "), { onIncident(i.id) }, trailing = { IncidentPill(i) })
            }
            item { SectionHeader(stringResource(R.string.asset_readings) + " (${readings.size})") }
            if (readings.isEmpty()) item { Text(stringResource(R.string.asset_no_readings), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(readings, key = { it.id }) { r ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${Labels.reading(context, r.readingKind)} : ${qtyText(context, r.value, r.readingKind.unit)}")
                        Text(listOfNotNull(OdFmt.date(context, r.date), r.updatedBy.takeIf { it.isNotBlank() }, r.note.takeIf { it.isNotBlank() }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (cloud.canEdit) IconButton(onClick = { vm.ops { deleteReading(r.id) } }) { Icon(Icons.Default.Close, stringResource(R.string.action_delete)) }
                }
            }
            item { Spacer(Modifier.padding(40.dp)) }
        }
    }
    if (addReading) ReadingDialog(defaultReading(a.assetKind), onDismiss = { addReading = false }) { kind, value, day, note ->
        vm.ops { saveReading(Reading(vm.newId(), a.id, day, kind.name, value, note)) }
        addReading = false
    }
}

@Composable
private fun ReadingDialog(initial: ReadingKind, onDismiss: () -> Unit, onSave: (ReadingKind, Double, Long, String) -> Unit) {
    val context = LocalContext.current
    var kind by remember { mutableStateOf(initial) }
    var value by remember { mutableStateOf<Double?>(null) }
    var day by remember { mutableStateOf(LocalDate.now().toEpochDay()) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.asset_add_reading)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormChips(null, ReadingKind.entries.map { it to Labels.reading(context, it) }, kind) { kind = it }
                FormNumber(stringResource(R.string.asset_value), value, { value = it }, suffix = kind.unit.ifBlank { null })
                FormDate(stringResource(R.string.works_date), day, { it?.let { d -> day = d } })
                FormText(stringResource(R.string.ops_notes), note, { note = it })
            }
        },
        confirmButton = { TextButton(onClick = { value?.let { onSave(kind, it, day, note.trim()) } }, enabled = value != null) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun AssetEditScreen(vm: MainViewModel, assetId: String?, onDone: () -> Unit) {
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val farm by vm.farmState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val existing = remember { ops.data.asset(assetId) }
    var kind by remember { mutableStateOf(existing?.assetKind ?: AssetKind.WELL) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var code by remember { mutableStateOf(existing?.code.orEmpty()) }
    var active by remember { mutableStateOf(existing?.active ?: true) }
    var parcel by remember { mutableStateOf(existing?.parcelId) }
    var contract by remember { mutableStateOf(existing?.contract.orEmpty()) }
    var kva by remember { mutableStateOf(existing?.powerKva) }
    var depth by remember { mutableStateOf(existing?.depthM) }
    var flow by remember { mutableStateOf(existing?.flowM3h) }
    var pump by remember { mutableStateOf(existing?.pumpKw) }
    var capacity by remember { mutableStateOf(existing?.capacityM3) }
    var notes by remember { mutableStateOf(existing?.notes.orEmpty()) }
    EditScaffold(
        title = stringResource(if (existing == null) R.string.assets_new else R.string.assets_edit),
        onBack = onDone,
        saveEnabled = name.isNotBlank(),
        onSave = {
            val base = existing ?: Asset(vm.newId(), name = name)
            vm.ops {
                saveAsset(base.copy(kind = kind.name, name = name.trim(), code = code.trim(), active = active, parcelId = parcel, contract = contract.trim(),
                    powerKva = kva, depthM = depth, flowM3h = flow, pumpKw = pump, capacityM3 = capacity, notes = notes.trim()))
            }
            onDone()
        },
        onDelete = existing?.let { { vm.ops { deleteAsset(it.id) }; onDone() } },
    ) {
        FormChips(stringResource(R.string.assets_kind), AssetKind.entries.map { it to "${it.emoji} ${Labels.asset(context, it)}" }, kind) { kind = it }
        FormText(stringResource(R.string.assets_name), name, { name = it })
        FormText(stringResource(R.string.assets_code), code, { code = it })
        FormChips(null, listOf(true to stringResource(R.string.asset_ok), false to stringResource(R.string.asset_inactive)), active) { active = it }
        FormPicker(stringResource(R.string.ops_parcel), farm.data.parcels.map { it.id to it.name }, parcel, { parcel = it }, noneLabel = stringResource(R.string.ops_whole_farm))
        if (kind == AssetKind.POWER) {
            FormText(stringResource(R.string.asset_contract), contract, { contract = it })
            FormNumber(stringResource(R.string.asset_power), kva, { kva = it }, suffix = "kVA")
        }
        if (kind == AssetKind.WELL) {
            FormNumber(stringResource(R.string.asset_depth), depth, { depth = it }, suffix = "m")
            FormNumber(stringResource(R.string.asset_flow), flow, { flow = it }, suffix = "m³/h")
        }
        if (kind == AssetKind.WELL || kind == AssetKind.PUMP || kind == AssetKind.PIVOT) FormNumber(stringResource(R.string.asset_pump), pump, { pump = it }, suffix = "kW")
        if (kind == AssetKind.BASIN) FormNumber(stringResource(R.string.asset_capacity), capacity, { capacity = it }, suffix = "m³")
        FormText(stringResource(R.string.ops_notes), notes, { notes = it }, singleLine = false)
        if (existing?.planX != null) Text(stringResource(R.string.asset_on_plan), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
