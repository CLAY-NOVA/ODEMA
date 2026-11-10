package ma.odema.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Latitude/longitude in decimal degrees (WGS84). */
@Serializable
data class GeoPoint(val lat: Double, val lon: Double) {
    /** Four decimals (~11 m), as MET Norway asks for. */
    val lat4: String get() = "%.4f".format(java.util.Locale.ROOT, lat)
    val lon4: String get() = "%.4f".format(java.util.Locale.ROOT, lon)
}

/** A place the user watches. */
@Serializable
data class Place(
    val id: String,
    val name: String,
    val point: GeoPoint,
    /** Key into [MoroccoCities] when the place is a built-in town, for localized names. */
    val cityId: String? = null,
    /** Moroccan region number 1–12, see [MoroccoRegions]. */
    val region: Int? = null,
    val elevation: Double? = null,
    /** Follows the phone's location while the app is open. */
    val followsDevice: Boolean = false,
    val alertsEnabled: Boolean = true,
    /** True once the user gives the place their own name. */
    val renamed: Boolean = false,
) {
    /** Built-in towns show in the reader's language unless the user renamed them. */
    fun displayName(lang: Lang): String =
        if (!renamed) MoroccoCities.byId(cityId)?.name(lang) ?: name else name
}

/**
 * One forecast step from one source. Times are epoch seconds.
 *
 * [intensity] is mm/h averaged over the step, [probability] the chance of
 * measurable precipitation in % (0–100), [snow] the snowfall in cm during the
 * step, [code] the WMO weather code (95–99 = thunderstorm), [temp] °C.
 * Any of them may be missing: each source publishes a different subset.
 */
@Serializable
data class PrecipSample(
    val start: Long,
    val duration: Long,
    val intensity: Double? = null,
    val probability: Double? = null,
    val snow: Double? = null,
    val code: Int? = null,
    val temp: Double? = null,
) {
    val end: Long get() = start + duration

    /** Precipitation (mm) falling inside [from, to). */
    fun amountBetween(from: Long, to: Long): Double {
        val i = intensity ?: return 0.0
        val overlap = (minOf(end, to) - maxOf(start, from)).coerceAtLeast(0)
        return i * overlap / 3600.0
    }

    /** Snow (cm) falling inside [from, to). */
    fun snowBetween(from: Long, to: Long): Double {
        val s = snow ?: return 0.0
        val overlap = (minOf(end, to) - maxOf(start, from)).coerceAtLeast(0)
        return if (duration > 0) s * overlap / duration else 0.0
    }
}

enum class SourceKind { BLEND, ENSEMBLE, MODEL, STATION }

/**
 * Every forecast feed the app combines. Several feeds come from one API.
 * [weight] is how much the feed's vote counts in the consensus.
 */
@Serializable
enum class SourceId(
    val displayName: String,
    val provider: String,
    val kind: SourceKind,
    val weight: Double,
) {
    BEST_MATCH("Open-Meteo Best Match", "open-meteo.com", SourceKind.BLEND, 1.5),
    ECMWF_ENS("ECMWF Ensemble (51 members)", "open-meteo.com", SourceKind.ENSEMBLE, 1.5),
    GFS_ENS("NOAA GEFS Ensemble (31 members)", "open-meteo.com", SourceKind.ENSEMBLE, 1.2),
    ICON_ENS("DWD ICON Ensemble (40 members)", "open-meteo.com", SourceKind.ENSEMBLE, 1.2),
    ECMWF("ECMWF IFS 9 km", "open-meteo.com", SourceKind.MODEL, 1.0),
    ECMWF_AIFS("ECMWF AIFS (AI model)", "open-meteo.com", SourceKind.MODEL, 1.0),
    METEOFRANCE("Météo-France ARPEGE", "open-meteo.com", SourceKind.MODEL, 1.0),
    ICON("DWD ICON", "open-meteo.com", SourceKind.MODEL, 1.0),
    UKMO("UK Met Office", "open-meteo.com", SourceKind.MODEL, 1.0),
    GFS("NOAA GFS", "open-meteo.com", SourceKind.MODEL, 1.0),
    GEM("Environment Canada GEM", "open-meteo.com", SourceKind.MODEL, 1.0),
    JMA("JMA (Japan)", "open-meteo.com", SourceKind.MODEL, 1.0),
    CMA("CMA GRAPES (China)", "open-meteo.com", SourceKind.MODEL, 1.0),
    MET_NORWAY("MET Norway", "api.met.no", SourceKind.MODEL, 1.0),
    BRIGHT_SKY("DWD MOSMIX stations", "brightsky.dev", SourceKind.STATION, 1.0),
}

/** What happened when a source was asked for a forecast. */
@Serializable
sealed interface SourceOutcome {
    @Serializable
    @SerialName("ok")
    data class Success(val samples: List<PrecipSample>, val note: String? = null) : SourceOutcome

    @Serializable
    @SerialName("nc")
    data object NotCovered : SourceOutcome

    @Serializable
    @SerialName("err")
    data class Failure(val message: String) : SourceOutcome
}

val SourceOutcome.samples: List<PrecipSample>?
    get() = (this as? SourceOutcome.Success)?.samples

@Serializable
data class CurrentConditions(
    val time: Long,
    val temperature: Double? = null,
    val precipitation: Double? = null,
    val code: Int? = null,
    val isDay: Boolean? = null,
)

/** One local calendar day. [date] is the epoch second of local midnight. */
@Serializable
data class DailyForecast(
    val date: Long,
    val precipitationSum: Double? = null,
    val probabilityMax: Double? = null,
    val precipitationHours: Double? = null,
    val snowfallSum: Double? = null,
    val code: Int? = null,
    val tempMax: Double? = null,
    val tempMin: Double? = null,
)

/** Everything known about one place at one moment. */
@Serializable
data class ForecastReport(
    val fetchedAt: Long,
    val outcomes: Map<SourceId, SourceOutcome>,
    /** When each source's data was actually downloaded (cached data keeps its old time). */
    val sourceTimes: Map<SourceId, Long> = emptyMap(),
    val current: CurrentConditions? = null,
    val daily: List<DailyForecast> = emptyList(),
) {
    val hasData: Boolean get() = outcomes.values.any { it is SourceOutcome.Success }
}

/** Thresholds and switches for every alert type. */
@Serializable
data class AlertSettings(
    // Rain soon
    val rainSoonEnabled: Boolean = true,
    val lookaheadMinutes: Int = 120,
    val minIntensity: Double = 0.2,
    val minProbability: Double = 60.0,
    val requiredAgreement: Double = 0.5,
    // Rain ending
    val rainStoppingEnabled: Boolean = false,
    // Heavy rain / flood risk
    val heavyEnabled: Boolean = true,
    val heavyHourly: Double = 8.0,
    val heavyDaily: Double = 30.0,
    // Thunderstorms
    val stormEnabled: Boolean = true,
    // Snow (Atlas)
    val snowEnabled: Boolean = true,
    val snowThresholdCm: Double = 1.0,
    /** Agreement needed for heavy rain, storms and snow, which models rarely agree on exactly. */
    val severeAgreement: Double = 0.35,
    // Significant rain in the coming days
    val aheadEnabled: Boolean = false,
    val aheadDays: Int = 3,
    val aheadMm: Double = 10.0,
    // Daily summary
    val dailySummaryEnabled: Boolean = false,
    val dailySummaryMinute: Int = 7 * 60,
    // Throttling
    val cooldownMinutes: Int = 180,
    val quietEnabled: Boolean = false,
    val quietStart: Int = 22 * 60,
    val quietEnd: Int = 7 * 60,
    val severeIgnoresQuiet: Boolean = true,
    /** Stored as names so removing a source in a later version never breaks saved settings. */
    val disabledSourceNames: Set<String> = emptySet(),
) {
    val enabledSources: Set<SourceId>
        get() = SourceId.entries.filterTo(mutableSetOf()) { it.name !in disabledSourceNames }

    val lookaheadSeconds: Long get() = lookaheadMinutes * 60L

    fun withSource(source: SourceId, enabled: Boolean): AlertSettings = copy(
        disabledSourceNames = if (enabled) disabledSourceNames - source.name else disabledSourceNames + source.name,
    )
}

/** Shared JSON configuration: tolerant of fields added or removed across versions. */
val OdemaJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
    explicitNulls = false
}
