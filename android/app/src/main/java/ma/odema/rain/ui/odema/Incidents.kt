package ma.odema.rain.ui.odema

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ma.odema.core.ops.Incident
import ma.odema.core.ops.IncidentStatus
import ma.odema.core.ops.Priority
import ma.odema.rain.R
import ma.odema.rain.ui.MainViewModel
import ma.odema.rain.ui.components.Pill

fun relativeTime(ms: Long): String = DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

/** Breakdowns: urgent first, then open, then the history. */
@Composable
fun IncidentsScreen(vm: MainViewModel, onOpen: (String) -> Unit, onNew: () -> Unit) {
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val cloud by vm.cloudState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showResolved by remember { mutableStateOf(false) }
    val open = ops.data.incidents.filter { it.open }.sortedWith(compareByDescending<Incident> { it.urgent }.thenByDescending { it.reportedAt })
    val resolved = ops.data.incidents.filter { !it.open }.sortedByDescending { it.resolvedAt ?: it.reportedAt }
    Scaffold(floatingActionButton = { if (cloud.canEdit) AddFab(stringResource(R.string.inc_new), onNew) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            item {
                Text(stringResource(R.string.inc_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(!showResolved, { showResolved = false }, { Text(stringResource(R.string.inc_open) + " (${open.size})") })
                    FilterChip(showResolved, { showResolved = true }, { Text(stringResource(R.string.inc_resolved) + " (${resolved.size})") })
                }
            }
            val list = if (showResolved) resolved else open
            if (list.isEmpty()) item { EmptyState(if (showResolved) "📋" else "✅", stringResource(if (showResolved) R.string.inc_empty_resolved else R.string.inc_empty)) }
            items(list, key = { it.id }) { i ->
                val asset = ops.data.asset(i.assetId)
                OpsRow(
                    emoji = if (!i.open) "✅" else if (i.urgent) "🔴" else asset?.assetKind?.emoji ?: "⚠️",
                    title = i.title,
                    subtitle = listOfNotNull(asset?.name, i.reportedBy.takeIf { it.isNotBlank() }, relativeTime(i.reportedAt), i.assignedTo.takeIf { it.isNotBlank() }?.let { "→ $it" }).joinToString(" · "),
                    onClick = { onOpen(i.id) },
                    trailing = { IncidentPill(i) },
                )
                HorizontalDivider(Modifier.padding(start = 64.dp))
            }
            item { Spacer(Modifier.padding(40.dp)) }
        }
    }
}

@Composable
fun IncidentPill(i: Incident) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    when {
        !i.open -> Pill(Labels.incident(context, i.incidentStatus), Color(0xFFDCEDC8), Color(0xFF33691E))
        i.urgent -> Pill(stringResource(R.string.pr_urgent), cs.error, cs.onError)
        i.incidentStatus == IncidentStatus.IN_PROGRESS -> Pill(Labels.incident(context, i.incidentStatus), cs.tertiaryContainer, cs.onTertiaryContainer)
        else -> Pill(Labels.incident(context, i.incidentStatus), cs.errorContainer, cs.onErrorContainer)
    }
}

@Composable
fun IncidentEditScreen(vm: MainViewModel, incidentId: String?, assetId: String?, parcelId: String?, onDone: () -> Unit) {
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val farm by vm.farmState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val existing = remember { ops.data.incidents.firstOrNull { it.id == incidentId } }
    val id = remember { existing?.id ?: vm.newId() }
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var asset by remember { mutableStateOf(existing?.assetId ?: assetId) }
    var parcel by remember { mutableStateOf(existing?.parcelId ?: parcelId ?: ops.data.asset(assetId)?.parcelId) }
    var priority by remember { mutableStateOf(existing?.let { Priority.of(it.priority) } ?: Priority.NORMAL) }
    var status by remember { mutableStateOf(existing?.incidentStatus ?: IncidentStatus.OPEN) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var assigned by remember { mutableStateOf(existing?.assignedTo.orEmpty()) }
    var resolution by remember { mutableStateOf(existing?.resolution.orEmpty()) }
    var cost by remember { mutableStateOf(existing?.cost) }
    val assetOptions = ops.data.assets.sortedWith(compareBy({ it.assetKind.ordinal }, { it.name })).map { it.id to "${it.assetKind.emoji} ${it.name}" }
    val quick = listOf(R.string.inc_q_pump, R.string.inc_q_power, R.string.inc_q_leak, R.string.inc_q_pivot, R.string.inc_q_tractor, R.string.inc_q_theft).map { stringResource(it) }

    fun build(st: IncidentStatus) = Incident(
        id = id, title = title.trim().ifBlank { ops.data.asset(asset)?.name ?: "?" }, assetId = asset, parcelId = parcel,
        description = description.trim(), priority = priority.name, status = st.name,
        reportedAt = existing?.reportedAt ?: System.currentTimeMillis(), reportedBy = existing?.reportedBy.orEmpty(),
        assignedTo = assigned.trim(), resolvedAt = if (st == IncidentStatus.RESOLVED) existing?.resolvedAt ?: System.currentTimeMillis() else null,
        resolution = resolution.trim(), cost = cost,
    )

    EditScaffold(
        title = stringResource(if (existing == null) R.string.inc_new else R.string.inc_edit),
        onBack = onDone,
        onSave = { vm.ops { saveIncident(build(status)) }; onDone() },
        saveEnabled = title.isNotBlank() || asset != null,
        onDelete = existing?.let { { vm.ops { deleteIncident(it.id) }; onDone() } },
    ) {
        FormText(stringResource(R.string.inc_what), title, { title = it })
        if (existing == null) FormChips(null, quick.map { it to it }, title) { title = it }
        FormPicker(stringResource(R.string.inc_asset), assetOptions, asset, { asset = it; ops.data.asset(it)?.parcelId?.let { p -> parcel = p } }, noneLabel = stringResource(R.string.inc_no_asset))
        FormPicker(stringResource(R.string.ops_parcel), farm.data.parcels.map { it.id to it.name }, parcel, { parcel = it }, noneLabel = stringResource(R.string.ops_whole_farm))
        FormChips(stringResource(R.string.inc_priority), Priority.entries.map { it to Labels.priority(context, it) }, priority) { priority = it }
        FormChips(stringResource(R.string.inc_status), IncidentStatus.entries.map { it to Labels.incident(context, it) }, status) { status = it }
        FormText(stringResource(R.string.inc_description), description, { description = it }, singleLine = false)
        FormText(stringResource(R.string.inc_assigned), assigned, { assigned = it })
        if (status == IncidentStatus.RESOLVED || existing?.open == false) {
            FormText(stringResource(R.string.inc_resolution), resolution, { resolution = it }, singleLine = false)
            FormNumber(stringResource(R.string.inc_cost), cost, { cost = it }, suffix = "DH")
        }
        if (existing?.open == true) Button(onClick = { vm.ops { saveIncident(build(IncidentStatus.RESOLVED)) }; onDone() }) { Text("✅ " + stringResource(R.string.inc_mark_resolved)) }
        PhotoStrip(vm, id)
        existing?.let {
            Text(
                stringResource(R.string.inc_reported, it.reportedBy.ifBlank { "—" }, relativeTime(it.reportedAt)) +
                    (it.resolvedAt?.let { r -> " · " + stringResource(R.string.inc_resolved_at, relativeTime(r)) } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (assetOptions.isEmpty()) Text(stringResource(R.string.inc_no_assets_hint), style = MaterialTheme.typography.bodySmall)
    }
}
