package ma.odema.rain.ui.odema

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ma.odema.core.farm.Campaigns
import ma.odema.core.ops.Attendance
import ma.odema.core.ops.Harvest
import ma.odema.core.ops.OpsAnalytics
import ma.odema.core.ops.Staff
import ma.odema.core.ops.StaffRole
import ma.odema.rain.R
import ma.odema.rain.coreLang
import ma.odema.rain.ui.MainViewModel
import ma.odema.rain.ui.components.BackTopBar
import ma.odema.rain.ui.components.SectionHeader
import java.time.LocalDate

/** Staff list and the daily attendance sheet (pointage), with this month's days and labour cost. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TeamScreen(vm: MainViewModel, onBack: () -> Unit, onStaff: (String?) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    Scaffold(topBar = { BackTopBar(stringResource(R.string.team_title), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.team_attendance)) })
                Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.team_staff)) })
            }
            if (tab == 0) AttendanceTab(vm) else StaffTab(vm, onStaff)
        }
    }
}

@Composable
private fun StaffTab(vm: MainViewModel, onStaff: (String?) -> Unit) {
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val cloud by vm.cloudState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val today = LocalDate.now()
    val month = OpsAnalytics.labour(ops.data, today.withDayOfMonth(1).toEpochDay(), today.toEpochDay())
    LazyColumn(Modifier.fillMaxSize()) {
        if (cloud.canEdit) item { Button(onClick = { onStaff(null) }, modifier = Modifier.padding(16.dp, 12.dp)) { Text("+ " + stringResource(R.string.team_new_staff)) } }
        if (ops.data.staff.isEmpty()) item { EmptyState("👷", stringResource(R.string.team_empty)) }
        items(ops.data.staff.sortedWith(compareBy({ !it.active }, { it.staffRole.ordinal }, { it.name.lowercase() })), key = { it.id }) { s ->
            val days = month.daysByStaff[s.id] ?: 0
            OpsRow(
                s.staffRole.emoji, s.name,
                listOfNotNull(Labels.role(context, s.staffRole), s.phone.takeIf { it.isNotBlank() }, stringResource(R.string.team_days_month, days),
                    stringResource(R.string.team_inactive).takeIf { !s.active }).joinToString(" · "),
                onClick = { onStaff(s.id) },
                trailing = {
                    if (s.phone.isNotBlank()) TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${s.phone}"))) }) { Text("📞") }
                },
            )
            HorizontalDivider(Modifier.padding(start = 64.dp))
        }
        item { Spacer(Modifier.padding(32.dp)) }
    }
}

@Composable
private fun AttendanceTab(vm: MainViewModel) {
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val cloud by vm.cloudState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var day by remember { mutableStateOf(LocalDate.now().toEpochDay()) }
    val sheet = ops.data.attendance.firstOrNull { it.date == day }
    val present = remember(day, sheet?.updatedAt) { mutableStateListOf<String>().apply { addAll(sheet?.present.orEmpty()) } }
    var casuals by remember(day, sheet?.updatedAt) { mutableStateOf(sheet?.casuals?.toDouble()) }
    var rate by remember(day, sheet?.updatedAt) { mutableStateOf(sheet?.casualRate ?: ops.data.attendance.maxByOrNull { it.date }?.casualRate) }
    var guardDay by remember(day, sheet?.updatedAt) { mutableStateOf(sheet?.guardDay) }
    var guardNight by remember(day, sheet?.updatedAt) { mutableStateOf(sheet?.guardNight) }
    var notes by remember(day, sheet?.updatedAt) { mutableStateOf(sheet?.notes.orEmpty()) }
    val active = ops.data.staff.filter { it.active }.sortedWith(compareBy({ it.staffRole.ordinal }, { it.name.lowercase() }))
    val guards = ops.data.staff.filter { it.active }.map { it.id to it.name }
    val first = LocalDate.ofEpochDay(day).withDayOfMonth(1).toEpochDay()
    val month = OpsAnalytics.labour(ops.data, first, day)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 48.dp)) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormDate(stringResource(R.string.team_day), day, { it?.let { d -> day = d } })
                Text(
                    stringResource(R.string.team_month_summary, month.sheets, month.daysByStaff.values.sum(), month.casualDays, madText(context, month.cost)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sheet != null) Text(stringResource(R.string.ops_updated_by, sheet.updatedBy.ifBlank { "—" }, OdFmt.date(context, sheet.updatedAt / 86_400_000L)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (active.isEmpty()) item { Text(stringResource(R.string.team_add_staff_first), Modifier.padding(horizontal = 16.dp)) }
        else item {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.team_present, present.size, active.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { present.clear(); present.addAll(active.map { it.id }) }) { Text(stringResource(R.string.team_all_present)) }
            }
        }
        items(active, key = { it.id }) { s ->
            Row(Modifier.fillMaxWidth().clickable { if (s.id in present) present.remove(s.id) else present.add(s.id) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(s.id in present, { on -> if (on) present.add(s.id) else present.remove(s.id) })
                Text("${s.staffRole.emoji} ${s.name}", Modifier.weight(1f))
                Text(Labels.role(context, s.staffRole), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormNumber(stringResource(R.string.team_casuals), casuals, { casuals = it }, Modifier.weight(1f), integer = true)
                    FormNumber(stringResource(R.string.team_casual_rate), rate, { rate = it }, Modifier.weight(1f), suffix = "DH")
                }
                FormPicker(stringResource(R.string.team_guard_day), guards, guardDay, { guardDay = it }, noneLabel = "—")
                FormPicker(stringResource(R.string.team_guard_night), guards, guardNight, { guardNight = it }, noneLabel = "—")
                FormText(stringResource(R.string.ops_notes), notes, { notes = it }, singleLine = false)
                if (cloud.canEdit) Button(onClick = {
                    vm.ops {
                        saveAttendance(Attendance(Attendance.idFor(day), day, present.toList(), casuals?.toInt() ?: 0, rate, guardDay, guardNight, notes.trim()))
                    }
                }) { Text(stringResource(R.string.team_save_sheet)) }
            }
        }
    }
}

@Composable
fun StaffEditScreen(vm: MainViewModel, staffId: String?, onDone: () -> Unit) {
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val existing = remember { ops.data.staffMember(staffId) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var role by remember { mutableStateOf(existing?.staffRole ?: StaffRole.WORKER) }
    var phone by remember { mutableStateOf(existing?.phone.orEmpty()) }
    var rate by remember { mutableStateOf(existing?.dailyRate) }
    var active by remember { mutableStateOf(existing?.active ?: true) }
    var notes by remember { mutableStateOf(existing?.notes.orEmpty()) }
    EditScaffold(
        title = stringResource(if (existing == null) R.string.team_new_staff else R.string.team_edit_staff),
        onBack = onDone,
        saveEnabled = name.isNotBlank(),
        onSave = {
            vm.ops { saveStaff((existing ?: Staff(vm.newId(), name)).copy(name = name.trim(), role = role.name, phone = phone.trim(), dailyRate = rate, active = active, notes = notes.trim())) }
            onDone()
        },
        onDelete = existing?.let { { vm.ops { deleteStaff(it.id) }; onDone() } },
    ) {
        FormText(stringResource(R.string.team_name), name, { name = it })
        FormChips(stringResource(R.string.team_role), StaffRole.entries.map { it to "${it.emoji} ${Labels.role(context, it)}" }, role) { role = it }
        FormText(stringResource(R.string.team_phone), phone, { phone = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Phone)
        FormNumber(stringResource(R.string.team_daily_rate), rate, { rate = it }, suffix = "DH")
        FormChips(null, listOf(true to stringResource(R.string.team_active), false to stringResource(R.string.team_inactive)), active) { active = it }
        FormText(stringResource(R.string.ops_notes), notes, { notes = it }, singleLine = false)
    }
}

/** Harvest deliveries and yields per block (t/ha) for a campaign. */
@Composable
fun HarvestsScreen(vm: MainViewModel, onBack: () -> Unit, onOpen: (String?) -> Unit) {
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val farm by vm.farmState.collectAsStateWithLifecycle()
    val cloud by vm.cloudState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val campaigns = (ops.data.harvests.map { it.campaign } + Campaigns.current()).distinct().sortedDescending()
    var campaign by remember { mutableStateOf(campaigns.first()) }
    val yields = OpsAnalytics.yields(ops.data, farm.data, campaign)
    Scaffold(topBar = { BackTopBar(stringResource(R.string.harvest_title), onBack) }, floatingActionButton = { if (cloud.canEdit) AddFab(stringResource(R.string.harvest_new)) { onOpen(null) } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { Column(Modifier.padding(16.dp, 8.dp)) { FormChips(null, campaigns.map { it to OdFmt.campaign(it) }, campaign) { campaign = it } } }
            if (yields.isEmpty()) item { EmptyState("🚛", stringResource(R.string.harvest_empty)) }
            else item {
                SectionHeader(stringResource(R.string.harvest_yields))
                yields.forEach { y ->
                    val crop = ma.odema.core.farm.Crop.of(y.crop)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text("${crop.emoji} ${y.block.ifBlank { farm.parcel(y.parcelId)?.name.orEmpty() }}", Modifier.weight(1f))
                        Text(qtyText(context, y.tonnes, "t") + (y.tPerHa?.let { " · " + qtyText(context, it, "t/ha") } ?: ""), fontWeight = FontWeight.SemiBold)
                    }
                    y.revenue?.let { Text(madText(context, it), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                SectionHeader(stringResource(R.string.harvest_deliveries))
            }
            items(ops.data.harvests.filter { it.campaign == campaign }.sortedByDescending { it.date }, key = { it.id }) { h ->
                val crop = ma.odema.core.farm.Crop.of(h.crop)
                OpsRow(crop.emoji, "${h.block.ifBlank { farm.parcel(h.parcelId)?.name.orEmpty() }} · ${qtyText(context, h.quantityT, "t")}",
                    listOfNotNull(OdFmt.date(context, h.date), crop.name(context.coreLang), h.buyer.takeIf { it.isNotBlank() }, h.quality.takeIf { it.isNotBlank() }).joinToString(" · "),
                    onClick = { onOpen(h.id) })
                HorizontalDivider(Modifier.padding(start = 64.dp))
            }
            item { Spacer(Modifier.padding(40.dp)) }
        }
    }
}

@Composable
fun HarvestEditScreen(vm: MainViewModel, harvestId: String?, onDone: () -> Unit) {
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val farm by vm.farmState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val existing = remember { ops.data.harvests.firstOrNull { it.id == harvestId } }
    var parcel by remember { mutableStateOf(existing?.parcelId ?: farm.data.parcels.firstOrNull()?.id) }
    var block by remember { mutableStateOf(existing?.block.orEmpty()) }
    var date by remember { mutableStateOf(existing?.date ?: LocalDate.now().toEpochDay()) }
    var qty by remember { mutableStateOf(existing?.quantityT) }
    var quality by remember { mutableStateOf(existing?.quality.orEmpty()) }
    var buyer by remember { mutableStateOf(existing?.buyer.orEmpty()) }
    var price by remember { mutableStateOf(existing?.pricePerT) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    val campaign = Campaigns.ofDay(date)
    // Harvests in early summer belong to the campaign that started the previous September.
    val entry = farm.data.crops.firstOrNull { it.parcelId == parcel && it.block == block && it.campaign == campaign }
        ?: farm.data.crops.firstOrNull { it.parcelId == parcel && it.campaign == campaign }
    val blocks = rememberBlocks(vm, parcel)
    LaunchedEffect(parcel) { if (block.isNotBlank() && block !in blocks) block = "" }
    EditScaffold(
        title = stringResource(if (existing == null) R.string.harvest_new else R.string.harvest_edit),
        onBack = onDone,
        saveEnabled = parcel != null && (qty ?: 0.0) > 0,
        onSave = {
            vm.ops {
                saveHarvest(Harvest(existing?.id ?: vm.newId(), parcel!!, block, campaign, entry?.crop.orEmpty(), date, qty ?: 0.0, quality.trim(), buyer.trim(), price, note.trim()))
            }
            onDone()
        },
        onDelete = existing?.let { { vm.ops { deleteHarvest(it.id) }; onDone() } },
    ) {
        FormPicker(stringResource(R.string.ops_parcel), farm.data.parcels.map { it.id to it.name }, parcel, { parcel = it })
        if (blocks.isNotEmpty()) FormChips(stringResource(R.string.ops_block), listOf("" to stringResource(R.string.ops_all_blocks)) + blocks.map { it to it }, block) { block = it }
        entry?.let { Text("${it.cropKind.emoji} ${OdFmt.cropLabel(context, it)} · ${OdFmt.campaign(campaign)}", style = MaterialTheme.typography.bodyMedium) }
        FormDate(stringResource(R.string.works_date), date, { it?.let { d -> date = d } })
        FormNumber(stringResource(R.string.harvest_qty), qty, { qty = it }, suffix = "t")
        FormText(stringResource(R.string.harvest_quality), quality, { quality = it })
        FormText(stringResource(R.string.harvest_buyer), buyer, { buyer = it })
        FormNumber(stringResource(R.string.harvest_price), price, { price = it }, suffix = "DH/t")
        FormText(stringResource(R.string.ops_notes), note, { note = it })
    }
}
