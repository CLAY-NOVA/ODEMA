package ma.odema.rain.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.max

/** Nice round axis maximum for rainfall values. */
private fun niceMax(v: Double): Double {
    if (v <= 0) return 10.0
    val steps = listOf(5.0, 10.0, 20.0, 25.0, 50.0, 100.0, 150.0, 200.0, 250.0, 300.0, 400.0, 500.0, 600.0, 800.0, 1000.0, 1200.0, 1500.0, 2000.0)
    return steps.firstOrNull { it >= v } ?: (ceil(v / 500) * 500)
}

/**
 * Vertical bars (e.g. monthly rain) with optional reference markers (e.g. the normal),
 * value labels on top, and RTL mirroring.
 */
@Composable
fun BarChart(
    values: List<Double?>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    reference: List<Double?>? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    highlight: Int? = null,
    height: Dp = 170.dp,
    barWidth: Dp? = null,
    showValues: Boolean = true,
    referenceLine: Double? = null,
) {
    val measurer = rememberTextMeasurer()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val refColor = MaterialTheme.colorScheme.tertiary
    val style = TextStyle(fontSize = 9.sp, color = label)
    val maxV = niceMax(max(values.maxOf { it ?: 0.0 }, max(reference?.maxOf { it ?: 0.0 } ?: 0.0, referenceLine ?: 0.0)))
    val width = barWidth?.let { Modifier.width(it * values.size + 34.dp) } ?: Modifier.fillMaxWidth()
    Canvas(modifier.then(width).height(height)) {
        if (values.isEmpty()) return@Canvas
        val axis = 30.dp.toPx()
        val bottom = 16.dp.toPx()
        val top = 12.dp.toPx()
        val w = size.width - axis
        val h = size.height - bottom - top
        val slot = w / values.size
        fun x(i: Int) = if (rtl) size.width - axis - (i + 1) * slot else axis + i * slot
        fun y(v: Double) = top + h * (1 - (v / maxV)).toFloat()
        listOf(0.0, maxV / 2, maxV).forEach { g ->
            val yy = y(g)
            drawLine(grid, Offset(if (rtl) 0f else axis, yy), Offset(if (rtl) w else size.width, yy), 1f)
            val t = measurer.measure("${g.toInt()}", style)
            drawText(t, topLeft = Offset(if (rtl) size.width - t.size.width else 0f, yy - t.size.height / 2f))
        }
        values.forEachIndexed { i, v ->
            val c = if (highlight == null || highlight == i) color else color.copy(alpha = 0.45f)
            if (v != null && v > 0) {
                drawRect(c, Offset(x(i) + slot * 0.15f, y(v)), Size(slot * 0.7f, (top + h) - y(v)))
                if (showValues && slot > 18.dp.toPx()) {
                    val t = measurer.measure(if (v >= 10) "${v.toInt()}" else "%.1f".format(v), style)
                    drawText(t, topLeft = Offset(x(i) + slot / 2 - t.size.width / 2f, y(v) - t.size.height))
                }
            }
            reference?.getOrNull(i)?.let { r ->
                val yy = y(r)
                drawLine(refColor, Offset(x(i) + slot * 0.05f, yy), Offset(x(i) + slot * 0.95f, yy), 3f)
            }
            labels.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                val t = measurer.measure(it, style)
                drawText(t, topLeft = Offset(x(i) + slot / 2 - t.size.width / 2f, top + h + 2.dp.toPx()))
            }
        }
        referenceLine?.let { r ->
            drawLine(refColor, Offset(if (rtl) 0f else axis, y(r)), Offset(if (rtl) w else size.width, y(r)), 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
    }
}

/** Long series of bars (one per campaign since 1940) that scrolls to the most recent. */
@Composable
fun ScrollingBarChart(values: List<Double?>, labels: List<String>, average: Double?, color: Color, highlight: Int? = null) {
    val scroll = rememberScrollState()
    LaunchedEffect(values.size) { scroll.scrollTo(scroll.maxValue) }
    Box(Modifier.horizontalScroll(scroll)) {
        BarChart(values, labels, barWidth = 14.dp, color = color, showValues = false, referenceLine = average, highlight = highlight)
    }
}

data class Line(val values: List<Double>, val color: Color, val dashed: Boolean = false, val width: Float = 5f)

/** Running totals since 1 September: this campaign vs normal vs last campaign. */
@Composable
fun LineChart(lines: List<Line>, xLabels: List<Pair<Int, String>>, modifier: Modifier = Modifier, height: Dp = 180.dp) {
    val measurer = rememberTextMeasurer()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val style = TextStyle(fontSize = 9.sp, color = label)
    val maxV = niceMax(lines.maxOfOrNull { l -> l.values.maxOrNull() ?: 0.0 } ?: 0.0)
    val n = lines.maxOfOrNull { it.values.size }?.coerceAtLeast(2) ?: 2
    val total = max(n, xLabels.maxOfOrNull { it.first + 1 } ?: n)
    Canvas(modifier.fillMaxWidth().height(height)) {
        val axis = 34.dp.toPx()
        val bottom = 16.dp.toPx()
        val top = 8.dp.toPx()
        val w = size.width - axis
        val h = size.height - bottom - top
        fun x(i: Int): Float {
            val f = i.toFloat() / (total - 1)
            return if (rtl) w * (1 - f) else axis + w * f
        }
        fun y(v: Double) = top + h * (1 - (v / maxV)).toFloat()
        listOf(0.0, maxV / 2, maxV).forEach { g ->
            drawLine(grid, Offset(if (rtl) 0f else axis, y(g)), Offset(if (rtl) w else size.width, y(g)), 1f)
            val t = measurer.measure("${g.toInt()}", style)
            drawText(t, topLeft = Offset(if (rtl) size.width - t.size.width else 0f, y(g) - t.size.height / 2f))
        }
        xLabels.forEach { (i, text) ->
            val t = measurer.measure(text, style)
            drawText(t, topLeft = Offset((x(i) - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), top + h + 2.dp.toPx()))
        }
        lines.forEach { line ->
            if (line.values.size < 2) return@forEach
            val path = Path()
            line.values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
            drawPath(path, line.color, style = Stroke(width = line.width, pathEffect = if (line.dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 10f)) else null))
        }
    }
}

@Composable
fun LegendItem(color: Color, text: String, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
        Box(Modifier.size(width = 14.dp, height = if (dashed) 2.dp else 4.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Horizontal share bar, e.g. area per crop. */
@Composable
fun ShareBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
    }
}
