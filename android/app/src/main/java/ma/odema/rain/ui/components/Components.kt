package ma.odema.rain.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ma.odema.core.TimelineSlot
import ma.odema.rain.Fmt
import ma.odema.rain.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        actions = { actions() },
    )
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 6.dp),
    )
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
fun ClickRow(title: String, value: String? = null, subtitle: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.outline)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp))
        } else {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

/** A row that opens a single-choice dialog. */
@Composable
fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, enabled: Boolean = true, subtitle: String? = null, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == selected }?.second ?: options.firstOrNull()?.second.orEmpty()
    ClickRow(title, value = label, subtitle = subtitle, enabled = enabled) { open = true }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                LazyColumn {
                    itemsIndexed(options) { _, (value, text) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = value == selected, role = Role.RadioButton) {
                                    onSelect(value)
                                    open = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == selected, onClick = null)
                            Text(text, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** A row showing a time of day that opens a 24-hour time picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeRow(title: String, minuteOfDay: Int, enabled: Boolean = true, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ClickRow(title, value = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60), enabled = enabled) { open = true }
    if (open) {
        val state = rememberTimePickerState(minuteOfDay / 60, minuteOfDay % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onChange(state.hour * 60 + state.minute)
                    open = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * Hourly bars of the share of sources expecting rain, coloured by intensity, with the
 * alert threshold (dashed line) and the alert window (shaded). Mirrors itself in RTL.
 */
@Composable
fun RainChart(slots: List<TimelineSlot>, threshold: Double, windowEnd: Long, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val barColor = MaterialTheme.colorScheme.primary
    val heavyColor = Color(0xFF3949AB)
    val violentColor = Color(0xFF7B1FA2)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val windowShade = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
    val labelStyle = TextStyle(fontSize = 10.sp, color = label)
    Box(modifier) {
        Canvas(Modifier.fillMaxWidth().height(170.dp)) {
            if (slots.isEmpty()) return@Canvas
            val bottomPad = 18.dp.toPx()
            val leftPad = 30.dp.toPx()
            val chartW = size.width - leftPad
            val chartH = size.height - bottomPad
            val slotW = chartW / slots.size
            fun x(i: Int): Float = if (rtl) size.width - leftPad - (i + 1) * slotW else leftPad + i * slotW

            // Axis labels and grid at 0, 50, 100 %.
            listOf(0, 50, 100).forEach { p ->
                val y = chartH * (1 - p / 100f)
                drawLine(grid, Offset(if (rtl) 0f else leftPad, y), Offset(if (rtl) chartW else size.width, y), strokeWidth = 1f)
                val text = measurer.measure("$p%", labelStyle)
                drawText(text, topLeft = Offset(if (rtl) size.width - text.size.width else 0f, y - text.size.height / 2f))
            }
            // Alert window.
            val windowSlots = slots.count { it.start < windowEnd }.coerceAtMost(slots.size)
            if (windowSlots > 0) {
                val left = if (rtl) x(windowSlots - 1) else leftPad
                drawRect(windowShade, topLeft = Offset(left, 0f), size = Size(windowSlots * slotW, chartH))
            }
            slots.forEachIndexed { i, s ->
                val h = (s.chance * chartH).toFloat()
                val intensity = s.intensity ?: 0.0
                val color = when {
                    intensity >= 7.6 -> violentColor
                    intensity >= 2.5 -> heavyColor
                    else -> barColor.copy(alpha = if (s.chance >= threshold) 1f else 0.45f)
                }
                if (h > 0) drawRect(color, topLeft = Offset(x(i) + slotW * 0.12f, chartH - h), size = Size(slotW * 0.76f, h))
                if (i % 3 == 0) {
                    val text = measurer.measure(Fmt.hour(s.start), labelStyle)
                    drawText(text, topLeft = Offset(x(i) + slotW / 2 - text.size.width / 2f, chartH + 3.dp.toPx()))
                }
            }
            val ty = (chartH * (1 - threshold)).toFloat()
            drawLine(
                label, Offset(if (rtl) 0f else leftPad, ty), Offset(if (rtl) chartW else size.width, ty),
                strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )
        }
    }
}

@Composable
fun Pill(text: String, color: Color, onColor: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = onColor,
        modifier = modifier
            .background(color, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun Spaced(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
