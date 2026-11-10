package ma.odema.rain.ui.odema

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import ma.odema.core.farm.Crop
import ma.odema.core.farm.CropEntry
import ma.odema.core.farm.FarmAnalytics
import ma.odema.core.farm.SeedGenerations
import ma.odema.rain.Fmt
import ma.odema.rain.R
import ma.odema.rain.coreLang
import ma.odema.rain.ui.MainViewModel
import ma.odema.rain.ui.components.BackTopBar
import ma.odema.rain.ui.components.SectionHeader
import ma.odema.rain.ui.components.ShareBar

/** The assolement: what grows where, per campaign, plus the rotation over the years. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AssolementScreen(vm: MainViewModel, onEdit: (String?, String?, Int) -> Unit) {
    val farm by vm.farmState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val current = Campaigns.current()
    var campaign by rememberSaveable { mutableIntStateOf(current) }
    var rotation by rememberSaveable { mutableStateOf(false) }
    var confirmCopy by remember { mutableStateOf(false) }
    val entries = farm.data.crops.filter { it.campaign == campaign }
    val planted = entries.sumOf { it.areaHa ?: 0.0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.od_nav_assolement)) },
                actions = { IconButton(onClick = { onEdit(null, null, campaign) }) { Icon(Icons.Default.Add, stringResource(R.string.od_add_crop)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { campaign-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null) }
                Text(stringResource(R.string.od_campaign, OdFmt.campaign(campaign)), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { campaign++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }
            }
            Row(Modifier.padding(horizontal = 16.dp)) {
                FilterChip(!rotation, { rotation = false }, { Text(stringResource(R.string.od_view_campaign)) })
                FilterChip(rotation, { rotation = true }, { Text(stringResource(R.string.od_view_rotation)) }, Modifier.padding(start = 8.dp))
            }
            if (rotation) {
                RotationTable(vm, campaign, onEdit)
            } else LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
                // Surfaces by crop
                val byCrop = entries.groupBy { it.cropKind }.mapValues { (_, l) -> l.sumOf { it.areaHa ?: 0.0 } }.toList().sortedByDescending { it.second }
                if (byCrop.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.od_surfaces, OdFmt.ha(context, planted))) }
                    items(byCrop) { (crop, ha) ->
                        val share = if (planted > 0) (ha / planted).toFloat() else 0f
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Row {
                                Text("${crop.emoji} ${crop.name(context.coreLang)}", Modifier.weight(1f))
                                Text("${OdFmt.ha(context, ha)} · ${(share * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
                            }
                            ShareBar(share, crop.composeColor(), Modifier.fillMaxWidth().padding(top = 2.dp))
                        }
                    }
                } else item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.od_campaign_empty), style = MaterialTheme.typography.bodyMedium)
                        if (farm.data.crops.any { it.campaign == campaign - 1 }) {
                            Button(onClick = { confirmCopy = true }) { Text(stringResource(R.string.od_copy_blocks, OdFmt.campaign(campaign - 1))) }
                        }
                    }
                }
                // Per parcel
                items(farm.data.parcels, key = { it.id }) { p ->
                    val list = entries.filter { it.parcelId == p.id }.sortedBy { it.block }
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    val used = list.sumOf { it.areaHa ?: 0.0 }
                                    Text(
                                        stringResource(R.string.od_area_used, OdFmt.ha(context, used), OdFmt.ha(context, p.areaHa)),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { onEdit(null, p.id, campaign) }) { Icon(Icons.Default.Add, stringResource(R.string.od_add_crop)) }
                            }
                            list.forEach { e ->
                                Row(Modifier.fillMaxWidth().clickable { onEdit(e.id, null, campaign) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CropChip(e.cropKind, e.block.ifBlank { e.cropKind.code })
                                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                        Text(OdFmt.cropLabel(context, e), style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            listOfNotNull(
                                                e.areaHa?.let { OdFmt.ha(context, it) },
                                                e.sowingDate?.let { stringResource(R.string.od_sown, OdFmt.shortDate(context, it)) },
                                                e.harvestDate?.let { stringResource(R.string.od_harvested, OdFmt.shortDate(context, it)) },
                                                e.yieldTPerHa?.let { stringResource(R.string.od_yield_value, Fmt.number(context, it, 1)) },
                                            ).joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmCopy) AlertDialog(
        onDismissRequest = { confirmCopy = false },
        title = { Text(stringResource(R.string.od_copy_blocks, OdFmt.campaign(campaign - 1))) },
        text = { Text(stringResource(R.string.od_copy_blocks_help)) },
        confirmButton = { TextButton(onClick = { vm.copyBlocks(campaign - 1, campaign); confirmCopy = false }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = { confirmCopy = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Rotation: one row per parcel, one column per campaign, newest first (6 campaigns up to [campaign]). */
@Composable
private fun RotationTable(vm: MainViewModel, campaign: Int, onEdit: (String?, String?, Int) -> Unit) {
    val farm by vm.farmState.collectAsStateWithLifecycle()
    val years = (campaign downTo campaign - 5).toList()
    val table = remember(farm.data, campaign) { FarmAnalytics.rotation(farm.data, (campaign - 5)..campaign) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp)) {
        Row {
            Text("", Modifier.width(110.dp))
            years.forEach { Text(OdFmt.campaign(it), Modifier.width(120.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
        }
        farm.data.parcels.forEach { p ->
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                Text(p.name, Modifier.width(110.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                years.forEach { y ->
                    Column(Modifier.width(120.dp).clickable { onEdit(null, p.id, y) }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        val cell = table[p.id]?.get(y).orEmpty()
                        if (cell.isEmpty()) Text("·", color = MaterialTheme.colorScheme.outline)
                        cell.forEach { e -> CropChip(e.cropKind, listOf(e.cropKind.code, e.variety).filter(String::isNotBlank).joinToString(" "), Modifier.clickable { onEdit(e.id, null, y) }) }
                    }
                }
            }
        }
    }
}

/** Add or edit one assolement line. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CropEditScreen(vm: MainViewModel, entryId: String?, parcelId: String?, campaign: Int, onDone: () -> Unit) {
    val farm by vm.farmState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val existing = farm.data.crops.firstOrNull { it.id == entryId }
    var entry by remember(existing) {
        mutableStateOf(existing ?: CropEntry(vm.farm.newId(), parcelId ?: farm.data.parcels.firstOrNull()?.id.orEmpty(), campaign))
    }
    var area by remember(existing) { mutableStateOf(entry.areaHa?.toString() ?: "") }
    var yieldText by remember(existing) { mutableStateOf(entry.yieldTPerHa?.toString() ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    val varieties = farm.data.crops.filter { it.crop == entry.crop && it.variety.isNotBlank() }.map { it.variety }.distinct().sorted()

    Scaffold(topBar = { BackTopBar(stringResource(if (existing == null) R.string.od_add_crop else R.string.od_edit_crop), onDone) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Dropdown(stringResource(R.string.od_col_parcel), farm.data.parcels.map { it.id to it.name }, entry.parcelId) { entry = entry.copy(parcelId = it) }
            Dropdown(stringResource(R.string.od_campaign_pick), ((Campaigns.current() + 1) downTo 1990).map { it to OdFmt.campaign(it) }, entry.campaign) { entry = entry.copy(campaign = it) }
            OutlinedTextField(entry.block, { entry = entry.copy(block = it) }, label = { Text(stringResource(R.string.od_block)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(stringResource(R.string.od_block_help)) })
            Dropdown(stringResource(R.string.od_crop), Crop.entries.map { it.name to "${it.emoji} ${it.name(context.coreLang)}" }, entry.crop) { entry = entry.copy(crop = it) }
            OutlinedTextField(entry.variety, { entry = entry.copy(variety = it) }, label = { Text(stringResource(R.string.od_variety)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (varieties.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                varieties.take(8).forEach { v -> FilterChip(entry.variety == v, { entry = entry.copy(variety = v) }, { Text(v) }) }
            }
            Text(stringResource(R.string.od_generation), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SeedGenerations.forEach { g -> FilterChip(entry.generation == g, { entry = entry.copy(generation = g) }, { Text(g.ifEmpty { "—" }) }) }
            }
            OutlinedTextField(area, { area = it }, label = { Text(stringResource(R.string.od_area_ha)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            DateField(stringResource(R.string.od_sowing_date), entry.sowingDate) { entry = entry.copy(sowingDate = it) }
            DateField(stringResource(R.string.od_harvest_date), entry.harvestDate) { entry = entry.copy(harvestDate = it) }
            OutlinedTextField(yieldText, { yieldText = it }, label = { Text(stringResource(R.string.od_yield)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(entry.notes, { entry = entry.copy(notes = it) }, label = { Text(stringResource(R.string.od_note)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Button(
                enabled = entry.parcelId.isNotBlank(),
                onClick = {
                    vm.saveCrop(entry.copy(areaHa = area.replace(',', '.').toDoubleOrNull(), yieldTPerHa = yieldText.replace(',', '.').toDoubleOrNull()))
                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_save)) }
            if (existing != null) OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_delete)) }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.od_delete_crop)) },
        confirmButton = { TextButton(onClick = { vm.removeCrop(entry.id); confirmDelete = false; onDone() }) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Dropdown(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); open = false })
            }
        }
    }
}
