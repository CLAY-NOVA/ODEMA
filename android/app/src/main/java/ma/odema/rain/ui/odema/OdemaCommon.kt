package ma.odema.rain.ui.odema

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ma.odema.core.farm.Campaigns
import ma.odema.core.farm.Crop
import ma.odema.core.farm.CropEntry
import ma.odema.rain.Fmt
import ma.odema.rain.R
import ma.odema.rain.coreLang
import ma.odema.rain.locale
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object OdFmt {
    /** "2025-2026" kept left-to-right inside Arabic text (Unicode isolate), else it reads "2026-2025". */
    fun campaign(c: Int): String = "\u2066${Campaigns.label(c)}\u2069"

    fun date(context: Context, epochDay: Long): String =
        LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(context.locale))

    fun shortDate(context: Context, epochDay: Long): String =
        LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("d MMM", context.locale))

    fun ha(context: Context, v: Double?): String = v?.let { context.getString(R.string.od_ha, Fmt.number(context, it, if (it < 10) 2 else 1)) } ?: "–"

    fun mm(context: Context, v: Double?): String = v?.let { Fmt.mmText(context, it) } ?: "–"

    fun percent(v: Double?): String = v?.let { "${(it * 100).toInt()}%" } ?: "–"

    fun cropLabel(context: Context, e: CropEntry): String = buildList {
        add(e.cropKind.name(context.coreLang))
        if (e.variety.isNotBlank()) add(e.variety)
        if (e.generation.isNotBlank()) add(e.generation)
    }.joinToString(" · ")

    /** Short month names Sept → Aug in the reader's language. */
    fun campaignMonths(context: Context): List<String> = (0 until 12).map { m ->
        java.time.Month.of(((8 + m) % 12) + 1).getDisplayName(java.time.format.TextStyle.NARROW_STANDALONE, context.locale)
    }
}

fun Crop.composeColor(): Color = Color(color)

/** Coloured crop chip: emoji + code, used in lists and the rotation table. */
@Composable
fun CropChip(crop: Crop, text: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(crop.composeColor().copy(alpha = 0.18f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(crop.composeColor(), CircleShape))
        Text(" ${crop.emoji} ${text ?: crop.code}", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A small statistic: label above, value below. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null, accent: Color? = null) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = accent ?: MaterialTheme.colorScheme.onSurface, maxLines = 1)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
        }
    }
}

/** Two tiles side by side. */
@Composable
fun TileRow(content: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        content(Modifier.weight(1f))
    }
}

/** Colour for "% of normal": dry is warm, wet is blue. */
@Composable
fun ratioColor(r: Double?): Color = when {
    r == null -> MaterialTheme.colorScheme.onSurface
    r < 0.6 -> Color(0xFFD84315)
    r < 0.9 -> Color(0xFFF9A825)
    r <= 1.1 -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
}

@Composable
fun BigNumber(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(text, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = color, fontSize = 40.sp)
}
