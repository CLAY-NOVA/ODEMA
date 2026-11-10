package ma.odema.rain.ui.odema

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ma.odema.core.farm.Campaigns
import ma.odema.core.farm.Crop
import ma.odema.core.farm.CropEntry
import ma.odema.core.farm.RainHistory
import ma.odema.core.ops.Asset
import ma.odema.core.ops.AssetKind
import ma.odema.core.ops.FarmPlan
import ma.odema.core.ops.OpsAnalytics
import ma.odema.core.ops.PlanBlock
import ma.odema.core.ops.PlanGeometry
import ma.odema.rain.R
import ma.odema.rain.coreLang
import ma.odema.rain.data.FarmState
import ma.odema.rain.ui.MainViewModel
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class PlanLayer { CROPS, RAIN, WORKS }

private sealed interface PlanSel {
    data class Block(val name: String) : PlanSel
    data class Equip(val id: String) : PlanSel
}

/** The crop line of the assolement for a plan block in a campaign. */
fun cropFor(farm: FarmState, block: PlanBlock, campaign: Int): CropEntry? =
    farm.data.crops.firstOrNull { it.campaign == campaign && it.parcelId == block.parcelId && it.block.equals(block.name, ignoreCase = true) }

private fun rainOver(farm: FarmState, parcelId: String, days: Int, today: Long): Double? {
    val p = farm.parcel(parcelId) ?: return null
    val h = RainHistory(farm.seriesFor(p), farm.data.gauges.filter { it.parcelId == parcelId })
    if (!h.hasData) return null
    return h.sum(today - days + 1, today).mm
}

/** Home page: the ODEMA plan, coloured by crop, rain or field work, with equipment and breakdowns. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanScreen(
    vm: MainViewModel,
    onParcel: (String) -> Unit,
    onAsset: (String) -> Unit,
    onNewIncident: (assetId: String?, parcelId: String?) -> Unit,
    onNewWork: (parcelId: String?, block: String?) -> Unit,
    onIncidents: () -> Unit,
    onGpsMap: () -> Unit,
) {
    val farm by vm.farmState.collectAsStateWithLifecycle()
    val opsState by vm.opsState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val plan = opsState.plan
    val ops = opsState.data
    val today = LocalDate.now().toEpochDay()
    val campaigns = (farm.data.crops.map { it.campaign } + Campaigns.current()).distinct().sortedDescending()
    var campaign by remember { mutableStateOf(Campaigns.current().let { c -> if (farm.data.crops.any { it.campaign == c }) c else campaigns.firstOrNull() ?: c }) }
    var layer by remember { mutableStateOf(PlanLayer.CROPS) }
    var selection by remember { mutableStateOf<PlanSel?>(null) }
    val openByAsset = remember(ops) { OpsAnalytics.openIncidentsByAsset(ops) }
    val urgent = ops.incidents.count { it.open && it.urgent }
    val open = ops.incidents.count { it.open }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Header: logo, campaign, GPS map
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(44.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.plan_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF3B6B05))
                CampaignPicker(campaign, campaigns) { campaign = it }
            }
            IconButton(onClick = onGpsMap) { Icon(Icons.Default.LocationOn, stringResource(R.string.plan_gps_map)) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(PlanLayer.CROPS to R.string.plan_layer_crops, PlanLayer.RAIN to R.string.plan_layer_rain, PlanLayer.WORKS to R.string.plan_layer_works).forEach { (l, label) ->
                FilterChip(selected = layer == l, onClick = { layer = l }, label = { Text(stringResource(label)) })
            }
            if (open > 0) AssistChip(
                onClick = onIncidents,
                label = { Text(context.resources.getQuantityString(R.plurals.plan_open_incidents, open, open) + if (urgent > 0) " · ${stringResource(R.string.pr_urgent)} $urgent" else "") },
                leadingIcon = { Text("⚠️") },
            )
        }

        if (plan == null) {
            EmptyState("🗺️", stringResource(R.string.plan_missing))
            Button(onClick = onGpsMap, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.plan_gps_map)) }
            return@Column
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            PlanCanvas(
                plan = plan,
                // Equipment comes from the synced list, so new wells or edited markers follow.
                opsAssets = ops.assets,
                fill = { block ->
                    when (layer) {
                        PlanLayer.CROPS -> cropFor(farm, block, campaign)?.cropKind?.let { Color(it.color) }
                        PlanLayer.RAIN -> rainOver(farm, block.parcelId, 7, today)?.let { rainColor(it) }
                        PlanLayer.WORKS -> ops.works.count { w -> w.parcelId == block.parcelId && (w.block.isBlank() || w.block == block.name) && w.date in (today - 14)..(today + 7) }
                            .takeIf { it > 0 }?.let { Color(0xFF00897B).copy(alpha = (0.25f + 0.15f * it).coerceAtMost(0.9f)) }
                    }
                },
                label = { block ->
                    val second = when (layer) {
                        PlanLayer.CROPS -> cropFor(farm, block, campaign)?.let { e -> "${e.cropKind.emoji} ${e.cropKind.code}${e.variety.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}" }
                        PlanLayer.RAIN -> rainOver(farm, block.parcelId, 7, today)?.let { OdFmt.mm(context, it) }
                        PlanLayer.WORKS -> ops.works.filter { w -> w.parcelId == block.parcelId && (w.block.isBlank() || w.block == block.name) && w.date in (today - 14)..(today + 7) }
                            .takeIf { it.isNotEmpty() }?.joinToString("") { it.workType.emoji }
                    }
                    listOfNotNull(block.name, second)
                },
                openByAsset = openByAsset.mapValues { (_, v) -> v.any { it.urgent } },
                selected = selection,
                onTap = { selection = it },
            )
            val sel = selection
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp)) {
                when (sel) {
                    is PlanSel.Block -> plan.blocks.firstOrNull { it.name == sel.name }?.let { b ->
                        BlockCard(vm, farm, b, campaign, today, onClose = { selection = null }, onParcel = { onParcel(b.parcelId) },
                            onWork = { onNewWork(b.parcelId, b.name) }, onIncident = { onNewIncident(null, b.parcelId) })
                    }
                    is PlanSel.Equip -> ops.asset(sel.id)?.let { a ->
                        AssetCard(vm, a, onClose = { selection = null }, onOpen = { onAsset(a.id) }, onIncident = { onNewIncident(a.id, a.parcelId) })
                    }
                    null -> if (layer == PlanLayer.CROPS) CropLegend(farm, plan, campaign) else if (layer == PlanLayer.RAIN) Text(
                        stringResource(R.string.plan_rain_legend), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(8.dp)).padding(8.dp),
                    )
                }
            }
        }
    }
}

private fun rainColor(mm: Double): Color {
    val t = (mm / 40.0).coerceIn(0.0, 1.0).toFloat()
    return if (mm < 0.5) Color(0xFFE0E0E0) else Color(red = 0.75f - 0.65f * t, green = 0.87f - 0.55f * t, blue = 1f - 0.25f * t)
}

@Composable
private fun CampaignPicker(campaign: Int, campaigns: List<Int>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            stringResource(R.string.plan_campaign, OdFmt.campaign(campaign)) + " ▾",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { open = true }.padding(vertical = 2.dp),
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            campaigns.forEach { c -> DropdownMenuItem(text = { Text(OdFmt.campaign(c)) }, onClick = { onPick(c); open = false }) }
        }
    }
}

@Composable
private fun PlanCanvas(
    plan: FarmPlan,
    opsAssets: List<Asset>,
    fill: (PlanBlock) -> Color?,
    label: (PlanBlock) -> List<String>,
    openByAsset: Map<String, Boolean>,
    selected: PlanSel?,
    onTap: (PlanSel?) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val outline = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val paths = remember(plan) {
        plan.blocks.associate { b -> b.name to b.rings.map { ring -> ringPath(ring) } }
    }
    val lines = remember(plan) { plan.lines.map { l -> l.w to Path().apply { moveTo(l.pts[0].toFloat(), l.pts[1].toFloat()); for (i in 2 until l.pts.size step 2) lineTo(l.pts[i].toFloat(), l.pts[i + 1].toFloat()) } } }
    val water = remember(plan) { plan.water.map { ringPath(it) } }
    val labels = remember(plan) { plan.blocks.associate { it.name to PlanGeometry.labelPoint(it) } }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // A wide plan on a tall phone screen is turned a quarter clockwise, so it fills the screen.
        val turned = h > w * 1.15f && plan.width > plan.height * 1.15
        val pw = (if (turned) plan.height else plan.width).toFloat()
        val ph = (if (turned) plan.width else plan.height).toFloat()
        val planH = plan.height.toFloat()
        val fit = min(w / pw, h / ph) * 0.96f
        val base = Offset((w - pw * fit) / 2, (h - ph * fit) / 2)
        val scale = fit * zoom
        val origin = base + pan
        fun turn(x: Float, y: Float) = if (turned) Offset(planH - y, x) else Offset(x, y)
        fun toScreen(x: Double, y: Double) = origin + turn(x.toFloat(), y.toFloat()) * scale
        fun toPlan(c: Offset, o: Offset, s: Float): Pair<Double, Double> {
            val q = (c - o) / s
            return if (turned) q.y.toDouble() to (planH - q.x).toDouble() else q.x.toDouble() to q.y.toDouble()
        }
        val markerR = with(density) { 7.dp.toPx() }

        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(plan, w, h) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        val newZoom = (zoom * zoomChange).coerceIn(0.8f, 10f)
                        val o = base + pan
                        val newOrigin = centroid - (centroid - o) * (newZoom / zoom) + panChange
                        zoom = newZoom
                        pan = newOrigin - base
                    }
                }
                .pointerInput(plan, opsAssets, w, h) {
                    detectTapGestures(
                        onDoubleTap = { c ->
                            val newZoom = if (zoom > 2.5f) 1f else zoom * 2f
                            val o = base + pan
                            pan = (c - (c - o) * (newZoom / zoom)) - base
                            zoom = newZoom
                            if (newZoom == 1f) pan = Offset.Zero
                        },
                        onTap = { c ->
                            val o = base + pan
                            val s = fit * zoom
                            fun dist(a: Asset) = (o + turn(a.planX!!.toFloat(), a.planY!!.toFloat()) * s - c).getDistance()
                            val hitAsset = opsAssets.filter { it.planX != null && it.planY != null }.minByOrNull(::dist)?.takeIf { dist(it) < markerR * 2.2f }
                            val (px, py) = toPlan(c, o, s)
                            onTap(
                                when {
                                    hitAsset != null -> PlanSel.Equip(hitAsset.id)
                                    else -> PlanGeometry.blockAt(plan, px, py)?.let { PlanSel.Block(it.name) }
                                },
                            )
                        },
                    )
                },
        ) {
            withTransform({
                translate(origin.x, origin.y)
                scale(scale, scale, pivot = Offset.Zero)
                if (turned) {
                    translate(planH, 0f)
                    rotate(90f, pivot = Offset.Zero)
                }
            }) {
                water.forEach { drawPath(it, Color(0xFF4FC3F7)) }
                plan.blocks.forEach { b ->
                    val c = fill(b)
                    paths[b.name]?.forEach { p ->
                        drawPath(p, c?.copy(alpha = if (c.alpha < 1f) c.alpha else 0.62f) ?: surface)
                        if (selected == PlanSel.Block(b.name)) drawPath(p, Color(0xFF1565C0), style = Stroke(width = 3.dp.toPx() / scale, join = StrokeJoin.Round))
                    }
                }
                lines.forEach { (lw, p) ->
                    drawPath(p, outline.copy(alpha = 0.75f), style = Stroke(width = (lw.toFloat() * 1.1f).coerceAtLeast(0.6f) * density.density / scale * 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            // Block labels (screen space, so they stay readable at any zoom).
            plan.blocks.forEach { b ->
                val (lx, ly) = labels[b.name] ?: return@forEach
                val text = label(b)
                val big = scale * 40 > 70.dp.toPx()
                val lines2 = if (big || zoom > 1.4f) text else text.take(1)
                val layout = measurer.measure(
                    lines2.joinToString("\n"),
                    TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1B1B1B), textAlign = TextAlign.Center),
                )
                val p = toScreen(lx, ly)
                val tl = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f)
                drawRoundRect(Color.White.copy(alpha = 0.78f), tl - Offset(4f, 2f), Size(layout.size.width + 8f, layout.size.height + 4f), androidx.compose.ui.geometry.CornerRadius(8f))
                drawText(layout, topLeft = tl)
            }
            // Equipment
            opsAssets.forEach { a ->
                val x = a.planX ?: return@forEach
                val y = a.planY ?: return@forEach
                val p = toScreen(x, y)
                val r = markerR
                val broken = openByAsset.containsKey(a.id)
                when (a.assetKind) {
                    AssetKind.WELL -> {
                        drawCircle(if (a.active) Color(0xFF2E7D32) else Color(0xFF6A1B5E), r, p)
                        drawCircle(Color.White, r * 0.35f, p)
                    }
                    AssetKind.POWER -> drawPath(hexagon(p, r), Color(0xFFD50000))
                    AssetKind.BASIN -> drawRect(Color(0xFF0288D1), p - Offset(r, r), Size(2 * r, 2 * r))
                    AssetKind.PIVOT -> {} // its rings are drawn on the plan; the centre stays tappable
                    else -> drawCircle(Color(0xFF546E7A), r * 0.8f, p)
                }
                if (broken) {
                    drawCircle(Color(0xFFD50000), r * 1.7f, p, style = Stroke(width = 3.dp.toPx()))
                    if (openByAsset[a.id] == true) drawCircle(Color(0xFFFFD600), r * 0.45f, p + Offset(r * 1.2f, -r * 1.2f))
                }
                if (selected == PlanSel.Equip(a.id)) drawCircle(Color(0xFF1565C0), r * 2.1f, p, style = Stroke(width = 2.5.dp.toPx()))
                if (zoom > 1.7f && a.code.isNotBlank() && a.assetKind != AssetKind.PIVOT) {
                    val t = measurer.measure(if (a.assetKind == AssetKind.POWER) "P${a.code}" else a.code, TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B1B1B)))
                    drawText(t, topLeft = p + Offset(r + 2f, -t.size.height / 2f))
                }
            }
            // North arrow
            val c = Offset(size.width - 28.dp.toPx(), 28.dp.toPx())
            rotate(plan.northDeg.toFloat() + if (turned) 90f else 0f, pivot = c) {
                val arrow = Path().apply {
                    moveTo(c.x, c.y - 16.dp.toPx()); lineTo(c.x + 7.dp.toPx(), c.y + 10.dp.toPx()); lineTo(c.x, c.y + 5.dp.toPx()); lineTo(c.x - 7.dp.toPx(), c.y + 10.dp.toPx()); close()
                }
                drawPath(arrow, outline)
                val n = measurer.measure("N", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = outline))
                drawText(n, topLeft = Offset(c.x - n.size.width / 2f, c.y - 16.dp.toPx() - n.size.height))
            }
        }
    }
}

private fun ringPath(ring: List<Double>) = Path().apply {
    moveTo(ring[0].toFloat(), ring[1].toFloat())
    for (i in 2 until ring.size step 2) lineTo(ring[i].toFloat(), ring[i + 1].toFloat())
    close()
}

private fun hexagon(c: Offset, r: Float) = Path().apply {
    for (i in 0 until 6) {
        val a = Math.toRadians(60.0 * i - 30)
        val p = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CropLegend(farm: FarmState, plan: FarmPlan, campaign: Int) {
    val context = LocalContext.current
    val totals = plan.blocks.mapNotNull { b -> cropFor(farm, b, campaign)?.let { it.cropKind to (it.areaHa ?: b.areaHa ?: 0.0) } }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }.entries.sortedByDescending { it.value }
    if (totals.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), shape = RoundedCornerShape(10.dp)) {
        FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            totals.forEach { (crop, ha) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(Color(crop.color), CircleShape))
                    Text(" ${crop.name(context.coreLang)} ${OdFmt.ha(context, ha)}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun SheetCard(title: String, subtitle: String?, onClose: () -> Unit, content: @Composable () -> Unit) {
    Card(elevation = CardDefaults.cardElevation(6.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.action_back)) }
            }
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockCard(vm: MainViewModel, farm: FarmState, b: PlanBlock, campaign: Int, today: Long, onClose: () -> Unit, onParcel: () -> Unit, onWork: () -> Unit, onIncident: () -> Unit) {
    val context = LocalContext.current
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val entry = cropFor(farm, b, campaign)
    val subtitle = listOfNotNull(entry?.let { OdFmt.cropLabel(context, it) } ?: stringResource(R.string.plan_no_crop), OdFmt.ha(context, entry?.areaHa ?: b.areaHa)).joinToString(" · ")
    SheetCard(b.name, subtitle, onClose) {
        val r7 = rainOver(farm, b.parcelId, 7, today)
        val r30 = rainOver(farm, b.parcelId, 30, today)
        if (r7 != null) Text(stringResource(R.string.plan_rain_line, OdFmt.mm(context, r7), OdFmt.mm(context, r30)), style = MaterialTheme.typography.bodyMedium)
        val works = ops.data.works.filter { it.parcelId == b.parcelId && (it.block.isBlank() || it.block == b.name) }
        works.filter { it.done }.maxByOrNull { it.endDate ?: it.date }?.let { w ->
            Text(stringResource(R.string.plan_last_work, "${w.workType.emoji} ${w.title.ifBlank { Labels.work(context, w.workType) }}", OdFmt.shortDate(context, w.endDate ?: w.date)), style = MaterialTheme.typography.bodyMedium)
        }
        works.filter { !it.done }.minByOrNull { it.date }?.let { w ->
            Text(stringResource(R.string.plan_next_work, "${w.workType.emoji} ${w.title.ifBlank { Labels.work(context, w.workType) }}", OdFmt.shortDate(context, w.date)),
                style = MaterialTheme.typography.bodyMedium, color = if (w.overdue(today)) MaterialTheme.colorScheme.error else Color.Unspecified)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onParcel) { Text(stringResource(R.string.plan_open_parcel)) }
            OutlinedButton(onClick = onWork) { Text(stringResource(R.string.plan_add_work)) }
            OutlinedButton(onClick = onIncident) { Text(stringResource(R.string.plan_report)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssetCard(vm: MainViewModel, a: Asset, onClose: () -> Unit, onOpen: () -> Unit, onIncident: () -> Unit) {
    val context = LocalContext.current
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val open = ops.data.incidents.filter { it.open && it.assetId == a.id }
    SheetCard("${a.assetKind.emoji} ${a.name}", assetSubtitle(context, a), onClose) {
        if (open.isNotEmpty()) open.forEach {
            Text("⚠️ ${it.title} · ${Labels.incident(context, it.incidentStatus)}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        } else Text(stringResource(if (a.active) R.string.asset_ok else R.string.asset_inactive), style = MaterialTheme.typography.bodyMedium)
        ops.data.readings.filter { it.assetId == a.id }.maxByOrNull { it.date }?.let { r ->
            Text(stringResource(R.string.asset_last_reading, Labels.reading(context, r.readingKind), qtyText(context, r.value, r.readingKind.unit), OdFmt.shortDate(context, r.date)), style = MaterialTheme.typography.bodyMedium)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpen) { Text(stringResource(R.string.plan_open_asset)) }
            OutlinedButton(onClick = onIncident) { Text(stringResource(R.string.plan_report)) }
        }
    }
}

fun assetSubtitle(context: android.content.Context, a: Asset): String = listOfNotNull(
    a.contract.takeIf { it.isNotBlank() }?.let { context.getString(R.string.asset_contract_short, it) },
    a.powerKva?.let { "${it.toInt()} kVA" },
    a.flowM3h?.let { qtyText(context, it, "m³/h") },
    a.radiusM?.let { context.getString(R.string.asset_radius_short, it.toInt()) },
).joinToString(" · ").ifBlank { Labels.asset(context, a.assetKind) }
