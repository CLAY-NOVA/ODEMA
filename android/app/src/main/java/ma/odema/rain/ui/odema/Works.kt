package ma.odema.rain.ui.odema

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ma.odema.core.farm.Campaigns
import ma.odema.core.ops.InputUse
import ma.odema.core.ops.OpsAnalytics
import ma.odema.core.ops.Work
import ma.odema.core.ops.WorkStatus
import ma.odema.core.ops.WorkType
import ma.odema.rain.R
import ma.odema.rain.ui.MainViewModel
import ma.odema.rain.ui.components.Pill
import java.time.LocalDate

private enum class WorkFilter { TODO, DONE, ALL }

/** Field work: what is late, what is planned this week, what was done. */
@Composable
fun WorksScreen(vm: MainViewModel, onOpen: (String) -> Unit, onNew: () -> Unit) {
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val farm by vm.farmState.collectAsStateWithLifecycle()
    val cloud by vm.cloudState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val today = LocalDate.now().toEpochDay()
    var filter by remember { mutableStateOf(WorkFilter.TODO) }
    val works = ops.data.works
    val shown = when (filter) {
        WorkFilter.TODO -> works.filter { !it.done }.sortedBy { it.date }
        WorkFilter.DONE -> works.filter { it.done }.sortedByDescending { it.endDate ?: it.date }
        WorkFilter.ALL -> works.sortedByDescending { it.date }
    }
    Scaffold(floatingActionButton = { if (cloud.canEdit) AddFab(stringResource(R.string.works_new), onNew) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            item {
                Text(stringResource(R.string.works_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val late = works.count { it.overdue(today) }
                    FilterChip(filter == WorkFilter.TODO, { filter = WorkFilter.TODO }, { Text(stringResource(R.string.works_todo) + " (${works.count { !it.done }})") })
                    FilterChip(filter == WorkFilter.DONE, { filter = WorkFilter.DONE }, { Text(stringResource(R.string.works_done)) })
                    FilterChip(filter == WorkFilter.ALL, { filter = WorkFilter.ALL }, { Text(stringResource(R.string.works_all)) })
                    if (late > 0) Pill(stringResource(R.string.works_late_count, late), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, Modifier.align(Alignment.CenterVertically))
                }
            }
            if (shown.isEmpty()) item { EmptyState("🚜", stringResource(R.string.works_empty)) }
            items(shown, key = { it.id }) { w ->
                val parcel = farm.parcel(w.parcelId)?.name
                val where = listOfNotNull(w.block.ifBlank { parcel }, OdFmt.shortDate(context, w.date) + (w.endDate?.let { " → " + OdFmt.shortDate(context, it) } ?: ""))
                val products = w.inputs.mapNotNull { u -> ops.data.product(u.productId)?.let { "${it.name} ${qtyText(context, u.qty, it.unit)}" } }
                OpsRow(
                    emoji = w.workType.emoji,
                    title = w.title.ifBlank { Labels.work(context, w.workType) },
                    subtitle = (where + products.take(2) + listOfNotNull(w.responsible.takeIf { it.isNotBlank() })).joinToString(" · "),
                    onClick = { onOpen(w.id) },
                    trailing = { WorkStatusPill(w, today) },
                )
                HorizontalDivider(Modifier.padding(start = 64.dp))
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(40.dp)) }
        }
    }
}

@Composable
fun WorkStatusPill(w: Work, today: Long) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    when {
        w.overdue(today) -> Pill(stringResource(R.string.works_late), cs.errorContainer, cs.onErrorContainer)
        w.done -> Pill(Labels.status(context, w.workStatus), Color(0xFFDCEDC8), Color(0xFF33691E))
        w.workStatus == WorkStatus.IN_PROGRESS -> Pill(Labels.status(context, w.workStatus), cs.tertiaryContainer, cs.onTertiaryContainer)
        else -> Pill(Labels.status(context, w.workStatus), cs.secondaryContainer, cs.onSecondaryContainer)
    }
}

/** Parcels and their blocks (from the assolement and the plan) for pickers. */
@Composable
fun rememberBlocks(vm: MainViewModel, parcelId: String?): List<String> {
    val farm by vm.farmState.collectAsStateWithLifecycle()
    val ops by vm.opsState.collectAsStateWithLifecycle()
    return remember(farm.data, ops.plan, parcelId) {
        if (parcelId == null) emptyList() else (ops.plan?.blocks.orEmpty().filter { it.parcelId == parcelId }.map { it.name } +
            farm.data.crops.filter { it.parcelId == parcelId && it.block.isNotBlank() && it.campaign >= Campaigns.current() - 1 }.map { it.block }).distinct().sorted()
    }
}

@Composable
fun WorkEditScreen(vm: MainViewModel, workId: String?, parcelId: String?, block: String?, onDone: () -> Unit) {
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val farm by vm.farmState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val existing = remember { ops.data.works.firstOrNull { it.id == workId } }
    val id = remember { existing?.id ?: vm.newId() }
    var type by remember { mutableStateOf(existing?.workType ?: WorkType.FERTILIZER) }
    var status by remember { mutableStateOf(existing?.workStatus ?: WorkStatus.PLANNED) }
    var date by remember { mutableStateOf(existing?.date ?: LocalDate.now().toEpochDay()) }
    var endDate by remember { mutableStateOf(existing?.endDate) }
    var parcel by remember { mutableStateOf(existing?.parcelId ?: parcelId) }
    var blk by remember { mutableStateOf(existing?.block ?: block.orEmpty()) }
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    val inputs = remember { mutableStateListOf<InputUse>().apply { addAll(existing?.inputs.orEmpty()) } }
    var workers by remember { mutableStateOf(existing?.workers?.toDouble()) }
    var hours by remember { mutableStateOf(existing?.hours) }
    var area by remember { mutableStateOf(existing?.areaHa) }
    var machine by remember { mutableStateOf(existing?.machine.orEmpty()) }
    var responsible by remember { mutableStateOf(existing?.responsible.orEmpty()) }
    var cost by remember { mutableStateOf(existing?.cost) }
    var notes by remember { mutableStateOf(existing?.notes.orEmpty()) }
    val blocks = rememberBlocks(vm, parcel)

    EditScaffold(
        title = stringResource(if (existing == null) R.string.works_new else R.string.works_edit),
        onBack = onDone,
        onSave = {
            vm.ops {
                saveWork(
                    Work(
                        id = id, type = type.name, status = status.name, date = date, endDate = endDate?.takeIf { it >= date },
                        parcelId = parcel, block = blk, title = title.trim(), inputs = inputs.filter { it.qty > 0 }.toList(), areaHa = area,
                        workers = workers?.toInt(), hours = hours, machine = machine.trim(), responsible = responsible.trim(), cost = cost,
                        notes = notes.trim(), createdBy = existing?.createdBy.orEmpty(),
                    ),
                )
            }
            onDone()
        },
        onDelete = existing?.let { { vm.ops { deleteWork(it.id) }; onDone() } },
    ) {
        FormChips(stringResource(R.string.works_type), WorkType.entries.map { it to "${it.emoji} ${Labels.work(context, it)}" }, type) { type = it }
        FormChips(stringResource(R.string.works_status), WorkStatus.entries.map { it to Labels.status(context, it) }, status) { status = it }
        FormText(stringResource(R.string.works_title_field), title, { title = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormDate(stringResource(R.string.works_date), date, { it?.let { d -> date = d } }, Modifier.weight(1f))
            FormDate(stringResource(R.string.works_end), endDate, { endDate = it }, Modifier.weight(1f), clearable = true)
        }
        FormPicker(stringResource(R.string.ops_parcel), farm.data.parcels.map { it.id to it.name }, parcel, { parcel = it; blk = "" }, noneLabel = stringResource(R.string.ops_whole_farm))
        if (blocks.isNotEmpty()) FormChips(stringResource(R.string.ops_block), listOf("" to stringResource(R.string.ops_all_blocks)) + blocks.map { it to it }, blk) { blk = it }
        FormNumber(stringResource(R.string.works_area), area, { area = it }, suffix = "ha")

        Text(stringResource(R.string.works_products), style = MaterialTheme.typography.titleSmall)
        inputs.forEachIndexed { i, use ->
            val product = ops.data.product(use.productId)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FormPicker(stringResource(R.string.stock_product), ops.data.products.sortedBy { it.name }.map { it.id to "${it.productCategory.emoji} ${it.name}" }, use.productId,
                    { pid -> if (pid != null) inputs[i] = use.copy(productId = pid) }, Modifier.weight(1.6f))
                FormNumber(product?.unit ?: stringResource(R.string.stock_qty), use.qty.takeIf { it > 0 }, { q -> inputs[i] = inputs[i].copy(qty = q ?: 0.0) }, Modifier.weight(1f))
                IconButton(onClick = { inputs.removeAt(i) }) { Icon(Icons.Default.Close, stringResource(R.string.action_delete)) }
            }
            if (product != null) {
                val left = OpsAnalytics.stockOf(ops.data, product.id) + (existing?.takeIf { it.done }?.inputs?.filter { it.productId == product.id }?.sumOf { it.qty } ?: 0.0)
                Text(stringResource(R.string.works_in_stock, qtyText(context, left, product.unit)), style = MaterialTheme.typography.bodySmall,
                    color = if (left < use.qty) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (ops.data.products.isEmpty()) Text(stringResource(R.string.works_no_products), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else OutlinedButton(onClick = { inputs.add(InputUse(ops.data.products.minBy { it.name }.id, 0.0)) }) { Text(stringResource(R.string.works_add_product)) }
        if (inputs.isNotEmpty()) Text(stringResource(R.string.works_stock_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormNumber(stringResource(R.string.works_workers), workers, { workers = it }, Modifier.weight(1f), integer = true)
            FormNumber(stringResource(R.string.works_hours), hours, { hours = it }, Modifier.weight(1f), suffix = "h")
        }
        FormText(stringResource(R.string.works_machine), machine, { machine = it })
        FormText(stringResource(R.string.works_responsible), responsible, { responsible = it })
        FormNumber(stringResource(R.string.works_cost), cost, { cost = it }, suffix = "DH")
        FormText(stringResource(R.string.ops_notes), notes, { notes = it }, singleLine = false)
        PhotoStrip(vm, id)
        existing?.let { Text(stringResource(R.string.ops_updated_by, it.updatedBy.ifBlank { "—" }, OdFmt.date(context, it.updatedAt / 86_400_000L)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
