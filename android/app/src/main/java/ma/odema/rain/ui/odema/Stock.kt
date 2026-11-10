package ma.odema.rain.ui.odema

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ma.odema.core.ops.OpsAnalytics
import ma.odema.core.ops.Product
import ma.odema.core.ops.ProductCategory
import ma.odema.core.ops.StockMove
import ma.odema.rain.R
import ma.odema.rain.ui.MainViewModel
import ma.odema.rain.ui.components.BackTopBar
import ma.odema.rain.ui.components.Pill
import ma.odema.rain.ui.components.SectionHeader
import java.time.LocalDate

/** Inputs in store: level of each product, low-stock warnings and the value of the stock. */
@Composable
fun StockScreen(vm: MainViewModel, onBack: () -> Unit, onProduct: (String) -> Unit, onNewProduct: () -> Unit) {
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val cloud by vm.cloudState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val levels = OpsAnalytics.stockLevels(ops.data)
    val low = OpsAnalytics.lowStock(ops.data).map { it.first.id }.toSet()
    Scaffold(topBar = { BackTopBar(stringResource(R.string.stock_title), onBack) }, floatingActionButton = { if (cloud.canEdit) AddFab(stringResource(R.string.stock_new_product), onNewProduct) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                TileRow { m ->
                    StatTile(stringResource(R.string.stock_value), madText(context, OpsAnalytics.stockValue(ops.data)), m)
                    StatTile(stringResource(R.string.stock_low), "${low.size}", m, accent = if (low.isEmpty()) null else MaterialTheme.colorScheme.error)
                }
            }
            if (ops.data.products.isEmpty()) item { EmptyState("📦", stringResource(R.string.stock_empty)) }
            ops.data.products.groupBy { it.productCategory }.toSortedMap().forEach { (cat, list) ->
                item { SectionHeader("${cat.emoji} ${Labels.category(context, cat)}") }
                items(list.sortedBy { it.name.lowercase() }, key = { it.id }) { p ->
                    val level = levels[p.id] ?: 0.0
                    OpsRow(p.productCategory.emoji, p.name, p.minStock?.let { stringResource(R.string.stock_min, qtyText(context, it, p.unit)) }, { onProduct(p.id) }, trailing = {
                        val cs = MaterialTheme.colorScheme
                        if (p.id in low) Pill(qtyText(context, level, p.unit), cs.errorContainer, cs.onErrorContainer)
                        else Text(qtyText(context, level, p.unit), fontWeight = FontWeight.SemiBold)
                    })
                    HorizontalDivider(Modifier.padding(start = 64.dp))
                }
            }
            item { Spacer(Modifier.padding(40.dp)) }
        }
    }
}

@Composable
fun ProductScreen(vm: MainViewModel, productId: String, onBack: () -> Unit, onEdit: () -> Unit, onMove: (moveId: String?, incoming: Boolean) -> Unit, onWork: (String) -> Unit) {
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val farm by vm.farmState.collectAsStateWithLifecycle()
    val cloud by vm.cloudState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val p = ops.data.product(productId) ?: run { EmptyState("📦", stringResource(R.string.stock_empty)); return }
    val moves = ops.data.moves.filter { it.productId == p.id }.sortedByDescending { it.date }
    val level = moves.sumOf { it.qty }
    Scaffold(topBar = { BackTopBar("${p.productCategory.emoji} ${p.name}", onBack) { if (cloud.canEdit) IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, stringResource(R.string.stock_edit_product)) } } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                TileRow { m ->
                    StatTile(stringResource(R.string.stock_level), qtyText(context, level, p.unit), m,
                        accent = if (p.minStock?.let { level <= it } == true) MaterialTheme.colorScheme.error else null)
                    StatTile(stringResource(R.string.stock_avg_price), OpsAnalytics.avgPrice(ops.data, p.id)?.let { context.getString(R.string.ops_mad_per, Fmt2.price(context, it), p.unit) } ?: "–", m)
                }
                if (cloud.canEdit) Row(Modifier.padding(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onMove(null, true) }) { Text("📥 " + stringResource(R.string.stock_in)) }
                    OutlinedButton(onClick = { onMove(null, false) }) { Text("📤 " + stringResource(R.string.stock_out)) }
                }
                SectionHeader(stringResource(R.string.stock_moves))
            }
            if (moves.isEmpty()) item { Text(stringResource(R.string.stock_no_moves), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(moves, key = { it.id }) { m ->
                val work = ops.data.works.firstOrNull { it.id == m.workId }
                val sub = listOfNotNull(
                    OdFmt.date(context, m.date),
                    farm.parcel(m.parcelId)?.name,
                    work?.let { "${it.workType.emoji} ${it.title.ifBlank { Labels.work(context, it.workType) }}" },
                    m.partner.takeIf { it.isNotBlank() },
                    m.unitPrice?.let { context.getString(R.string.ops_mad_per, Fmt2.price(context, it), p.unit) },
                    m.note.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                OpsRow(if (m.qty >= 0) "📥" else "📤", (if (m.qty >= 0) "+" else "") + qtyText(context, m.qty, p.unit), sub,
                    onClick = { if (work != null) onWork(work.id) else onMove(m.id, m.qty >= 0) })
                HorizontalDivider(Modifier.padding(start = 64.dp))
            }
            item { Spacer(Modifier.padding(40.dp)) }
        }
    }
}

object Fmt2 {
    fun price(context: android.content.Context, v: Double) = ma.odema.rain.Fmt.number(context, v, if (v < 100) 2 else 0)
}

@Composable
fun ProductEditScreen(vm: MainViewModel, productId: String?, onDone: () -> Unit) {
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val existing = remember { ops.data.product(productId) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var category by remember { mutableStateOf(existing?.productCategory ?: ProductCategory.FERTILIZER) }
    var unit by remember { mutableStateOf(existing?.unit ?: "kg") }
    var min by remember { mutableStateOf(existing?.minStock) }
    var notes by remember { mutableStateOf(existing?.notes.orEmpty()) }
    EditScaffold(
        title = stringResource(if (existing == null) R.string.stock_new_product else R.string.stock_edit_product),
        onBack = onDone,
        saveEnabled = name.isNotBlank(),
        onSave = {
            vm.ops { saveProduct((existing ?: Product(vm.newId(), name)).copy(name = name.trim(), category = category.name, unit = unit.trim(), minStock = min, notes = notes.trim())) }
            onDone()
        },
        onDelete = existing?.let { { vm.ops { deleteProduct(it.id) }; onDone() } },
    ) {
        FormText(stringResource(R.string.stock_product), name, { name = it })
        FormChips(stringResource(R.string.stock_category), ProductCategory.entries.map { it to "${it.emoji} ${Labels.category(context, it)}" }, category) { category = it }
        FormText(stringResource(R.string.stock_unit), unit, { unit = it })
        FormChips(null, listOf("kg", "L", "t", "q", stringResource(R.string.stock_unit_bag), stringResource(R.string.stock_unit_piece)).map { it to it }, unit) { unit = it }
        FormNumber(stringResource(R.string.stock_min_field), min, { min = it }, suffix = unit)
        FormText(stringResource(R.string.ops_notes), notes, { notes = it }, singleLine = false)
    }
}

@Composable
fun MoveEditScreen(vm: MainViewModel, productId: String, moveId: String?, incoming: Boolean, onDone: () -> Unit) {
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val farm by vm.farmState.collectAsStateWithLifecycle()
    val existing = remember { ops.data.moves.firstOrNull { it.id == moveId } }
    val product = ops.data.product(productId)
    val isIn = existing?.let { it.qty >= 0 } ?: incoming
    var qty by remember { mutableStateOf(existing?.qty?.let { kotlin.math.abs(it) }) }
    var date by remember { mutableStateOf(existing?.date ?: LocalDate.now().toEpochDay()) }
    var price by remember { mutableStateOf(existing?.unitPrice) }
    var partner by remember { mutableStateOf(existing?.partner.orEmpty()) }
    var parcel by remember { mutableStateOf(existing?.parcelId) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    EditScaffold(
        title = (product?.name ?: "") + " · " + stringResource(if (isIn) R.string.stock_in else R.string.stock_out),
        onBack = onDone,
        saveEnabled = (qty ?: 0.0) > 0,
        onSave = {
            val q = qty ?: 0.0
            vm.ops {
                saveMove(StockMove(existing?.id ?: vm.newId(), productId, date, if (isIn) q else -q, unitPrice = if (isIn) price else null,
                    partner = partner.trim(), parcelId = if (isIn) null else parcel, note = note.trim()))
            }
            onDone()
        },
        onDelete = existing?.let { { vm.ops { deleteMove(it.id) }; onDone() } },
    ) {
        FormNumber(stringResource(R.string.stock_qty), qty, { qty = it }, suffix = product?.unit)
        FormDate(stringResource(R.string.works_date), date, { it?.let { d -> date = d } })
        if (isIn) {
            FormNumber(stringResource(R.string.stock_unit_price), price, { price = it }, suffix = "DH/${product?.unit.orEmpty()}")
            FormText(stringResource(R.string.stock_supplier), partner, { partner = it })
        } else {
            FormPicker(stringResource(R.string.ops_parcel), farm.data.parcels.map { it.id to it.name }, parcel, { parcel = it }, noneLabel = stringResource(R.string.ops_whole_farm))
            FormText(stringResource(R.string.stock_recipient), partner, { partner = it })
            Text(stringResource(R.string.stock_out_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FormText(stringResource(R.string.ops_notes), note, { note = it })
        Column {}
    }
}
