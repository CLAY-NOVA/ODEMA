package ma.odema.rain

import android.content.Context
import ma.odema.core.AlertSettings
import ma.odema.core.ForecastReport
import ma.odema.core.HazardOutlook
import ma.odema.core.RainAnalyzer
import ma.odema.core.RainIntensity
import ma.odema.core.RainOutlook
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import kotlin.math.roundToInt

/** Localized formatting shared by the UI, notifications and the widget. */
object Fmt {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** 24-hour clock, as used in Morocco. */
    fun time(epoch: Long): String = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochSecond(epoch))

    fun hour(epoch: Long): String = DateTimeFormatter.ofPattern("HH").withZone(zone).format(Instant.ofEpochSecond(epoch))

    /** "30 min", "2 h", "1 h 20 min" — or the Arabic equivalents with correct plural forms. */
    fun duration(context: Context, seconds: Long): String {
        val minutes = (seconds / 60).toInt().coerceAtLeast(0)
        val h = minutes / 60
        val m = minutes % 60
        val res = context.resources
        return when {
            h == 0 -> res.getQuantityString(R.plurals.duration_minutes, m, m)
            m == 0 -> res.getQuantityString(R.plurals.duration_hours, h, h)
            else -> context.getString(
                R.string.duration_combo,
                res.getQuantityString(R.plurals.duration_hours, h, h),
                res.getQuantityString(R.plurals.duration_minutes, m, m),
            )
        }
    }

    /** "in 35 min", rounded to 5 minutes. */
    fun relative(context: Context, seconds: Long): String {
        if (seconds < 5 * 60) return context.getString(R.string.relative_now)
        val rounded = ((seconds / 300.0).roundToInt() * 300).toLong()
        return context.getString(R.string.relative_in, duration(context, rounded))
    }

    fun number(context: Context, value: Double, decimals: Int = 1): String =
        String.format(context.locale, "%.${decimals}f", value)

    fun mm(context: Context, value: Double): String = number(context, value, if (value < 10) 1 else 0)

    fun mmText(context: Context, value: Double): String = context.getString(R.string.unit_mm, mm(context, value))
    fun mmhText(context: Context, value: Double): String = context.getString(R.string.unit_mmh, mm(context, value))
    fun cmText(context: Context, value: Double): String = context.getString(R.string.unit_cm, mm(context, value))

    fun temp(context: Context, value: Double?): String = value?.let { "${it.roundToInt()}°" } ?: "–"

    fun percent(value: Double): String = "${(value * 100).roundToInt()}%"

    fun intensity(context: Context, mmPerHour: Double): String = context.getString(
        when (RainIntensity.of(mmPerHour)) {
            RainIntensity.VERY_LIGHT -> R.string.intensity_very_light
            RainIntensity.LIGHT -> R.string.intensity_light
            RainIntensity.MODERATE -> R.string.intensity_moderate
            RainIntensity.HEAVY -> R.string.intensity_heavy
            RainIntensity.VIOLENT -> R.string.intensity_violent
        },
    )

    /** "Today", "Tomorrow" or "Thu 13". */
    fun day(context: Context, epoch: Long): String {
        val date = Instant.ofEpochSecond(epoch + 3600).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        return when (date) {
            today -> context.getString(R.string.day_today)
            today.plusDays(1) -> context.getString(R.string.day_tomorrow)
            else -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, context.locale) + " " + date.dayOfMonth
        }
    }

    fun ago(context: Context, epoch: Long, now: Long = System.currentTimeMillis() / 1000): String {
        val seconds = now - epoch
        return if (seconds < 90) context.getString(R.string.just_now)
        else context.getString(R.string.time_ago, duration(context, (seconds / 60) * 60))
    }
}

/** WMO weather code → emoji and short description. */
object Weather {
    fun emoji(code: Int?, isDay: Boolean = true): String = when (code) {
        null -> "☁️"
        0 -> if (isDay) "☀️" else "🌙"
        1, 2 -> if (isDay) "🌤️" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..57 -> "🌦️"
        in 61..65, in 80..82 -> "🌧️"
        66, 67 -> "🌧️"
        in 71..77, 85, 86 -> "❄️"
        in 95..99 -> "⛈️"
        else -> "☁️"
    }

    fun description(context: Context, code: Int?): String = context.getString(
        when (code) {
            0 -> R.string.wmo_clear
            1, 2 -> R.string.wmo_partly
            3 -> R.string.wmo_cloudy
            45, 48 -> R.string.wmo_fog
            in 51..57 -> R.string.wmo_drizzle
            61, 63, 66 -> R.string.wmo_rain
            65, 67 -> R.string.wmo_heavy_rain
            in 71..77, 85, 86 -> R.string.wmo_snow
            in 80..82 -> R.string.wmo_showers
            in 95..99 -> R.string.wmo_storm
            else -> R.string.wmo_cloudy
        },
    )
}

/** The one-line status used in the list, the detail header and the widget. */
data class Status(val emoji: String, val text: String, val badge: String?, val isRain: Boolean)

object StatusText {
    fun of(context: Context, report: ForecastReport?, settings: AlertSettings, refreshing: Boolean, now: Long = System.currentTimeMillis() / 1000): Status {
        if (report == null) {
            return Status("☁️", context.getString(if (refreshing) R.string.status_checking else R.string.status_no_forecast), null, false)
        }
        val o = RainAnalyzer.outlook(report, settings, now)
        return of(context, o, settings, now, report.current?.code, report.current?.isDay ?: true)
    }

    fun of(context: Context, o: RainOutlook, settings: AlertSettings, now: Long, currentCode: Int? = null, isDay: Boolean = true): Status {
        val window = Fmt.duration(context, settings.lookaheadSeconds)
        val badge = Fmt.percent(o.confidence)
        return when {
            o.available == 0 -> Status("⚠️", context.getString(R.string.status_no_source), null, false)
            o.isRainingNow(now) -> Status("🌧️", context.getString(R.string.status_rain_now), badge, true)
            o.willRain && o.expectedStart != null -> Status(
                "🌦️",
                context.getString(R.string.status_rain_at, Fmt.relative(context, o.expectedStart!! - now), Fmt.time(o.expectedStart!!)),
                badge, true,
            )
            o.agreeing > 0 -> Status("🌥️", context.getString(R.string.status_some_sources, window), badge, false)
            else -> Status(Weather.emoji(currentCode, isDay).takeUnless { currentCode == null } ?: "☀️", context.getString(R.string.status_dry, window), null, false)
        }
    }

    /** Short line for an active hazard, shown as a warning card. */
    fun hazard(context: Context, h: HazardOutlook, now: Long): String? {
        if (!h.triggered) return null
        val start = h.start?.let { Fmt.time(maxOf(it, now)) } ?: "–"
        return when (h.kind) {
            ma.odema.core.AlertKind.HEAVY_RAIN -> context.getString(R.string.hazard_heavy, Fmt.mm(context, h.amount ?: 0.0), start)
            ma.odema.core.AlertKind.STORM -> context.getString(R.string.hazard_storm, start)
            ma.odema.core.AlertKind.SNOW -> context.getString(R.string.hazard_snow, Fmt.mm(context, h.amount ?: 0.0))
            ma.odema.core.AlertKind.RAIN_AHEAD -> null
            ma.odema.core.AlertKind.RAIN_STOPPING -> context.getString(R.string.hazard_stopping, start)
            ma.odema.core.AlertKind.RAIN_SOON -> null
        }
    }
}
