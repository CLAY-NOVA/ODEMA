package ma.odema.core.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import ma.odema.core.CurrentConditions
import ma.odema.core.DailyForecast
import ma.odema.core.HttpClient
import ma.odema.core.OdemaJson
import ma.odema.core.Place
import ma.odema.core.PrecipSample
import ma.odema.core.SourceId
import ma.odema.core.SourceOutcome
import ma.odema.core.buildUrl
import ma.odema.core.fetch

/**
 * Open-Meteo "best match": blends the best local models. Over Morocco that means
 * Météo-France ARPEGE (0.1°), DWD ICON-EU in the north, and ECMWF. Gives 15-minute
 * precipitation for 24 h, hourly to 7 days, current conditions and daily totals.
 * https://open-meteo.com/en/docs — free, no key, CC BY 4.0.
 */
class OpenMeteoBestMatch(private val timezone: String = "Africa/Casablanca") : WeatherProvider {
    override val sources = listOf(SourceId.BEST_MATCH)
    override val ttlSeconds = 10 * 60L

    fun url(place: Place): String = buildUrl(
        "https://api.open-meteo.com/v1/forecast",
        "latitude" to place.point.lat4,
        "longitude" to place.point.lon4,
        *elevation(place),
        "minutely_15" to "precipitation,snowfall",
        "hourly" to "precipitation,precipitation_probability,weather_code,snowfall,temperature_2m",
        "daily" to "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,precipitation_hours,snowfall_sum",
        "current" to "temperature_2m,precipitation,weather_code,is_day",
        "past_minutely_15" to "1",
        "forecast_minutely_15" to "96",
        "past_hours" to "1",
        "forecast_hours" to "168",
        "forecast_days" to "7",
        "timeformat" to "unixtime",
        "timezone" to timezone,
    )

    override suspend fun fetch(place: Place, sources: Set<SourceId>, now: Long, client: HttpClient): ProviderResult =
        guarded(sources) {
            val result = parse(client.fetch(url(place)))
            result.copy(outcomes = result.outcomes.mapValues { (_, o) -> o.clipped(now) })
        }

    companion object {
        /** Open-Meteo values describe the period *preceding* each timestamp. */
        fun parse(body: String): ProviderResult {
            val root = OdemaJson.parseToJsonElement(body).jsonObject
            val hourly = root.obj("hourly") ?: throw IllegalStateException("no hourly data")
            val times = hourly.longs("time").orEmpty()
            val hPrecip = hourly.doubles("precipitation").orEmpty()
            val hProb = hourly.doubles("precipitation_probability").orEmpty()
            val hCode = hourly.doubles("weather_code").orEmpty()
            val hSnow = hourly.doubles("snowfall").orEmpty()
            val hTemp = hourly.doubles("temperature_2m").orEmpty()

            val hourlySamples = times.indices.mapNotNull { i ->
                val t = times[i] ?: return@mapNotNull null
                val amount = hPrecip.at(i)
                val probability = hProb.at(i)
                if (amount == null && probability == null) return@mapNotNull null
                PrecipSample(
                    start = t - 3600, duration = 3600,
                    intensity = amount, probability = probability,
                    snow = hSnow.at(i), code = hCode.at(i)?.toInt(), temp = hTemp.at(i),
                )
            }

            val minuteSamples = mutableListOf<PrecipSample>()
            root.obj("minutely_15")?.let { m ->
                val mt = m.longs("time").orEmpty()
                val mp = m.doubles("precipitation").orEmpty()
                val ms = m.doubles("snowfall").orEmpty()
                for (i in mt.indices) {
                    val t = mt[i] ?: continue
                    val amount = mp.at(i) ?: continue
                    val start = t - 900
                    val hour = hourlySamples.firstOrNull { start >= it.start && start < it.end }
                    // The hourly probability only applies to quarter-hours that actually get rain:
                    // otherwise a dry 14:00–14:15 would count as rain because 14:30 is wet.
                    minuteSamples += PrecipSample(
                        start = start, duration = 900,
                        intensity = amount * 4, probability = if (amount > 0) hour?.probability else null,
                        snow = ms.at(i), code = hour?.code, temp = hour?.temp,
                    )
                }
            }
            val fineEnd = minuteSamples.maxOfOrNull { it.end } ?: Long.MIN_VALUE
            val samples = minuteSamples + hourlySamples.filter { it.start >= fineEnd }

            val current = root.obj("current")?.let { c ->
                CurrentConditions(
                    time = c["time"].asDouble()?.toLong() ?: 0,
                    temperature = c["temperature_2m"].asDouble(),
                    precipitation = c["precipitation"].asDouble(),
                    code = c["weather_code"].asDouble()?.toInt(),
                    isDay = c["is_day"].asDouble()?.let { it > 0 },
                )
            }
            val daily = root.obj("daily")?.let { d ->
                val dt = d.longs("time").orEmpty()
                dt.indices.mapNotNull { i ->
                    val t = dt[i] ?: return@mapNotNull null
                    DailyForecast(
                        date = t,
                        precipitationSum = d.doubles("precipitation_sum")?.at(i),
                        probabilityMax = d.doubles("precipitation_probability_max")?.at(i),
                        precipitationHours = d.doubles("precipitation_hours")?.at(i),
                        snowfallSum = d.doubles("snowfall_sum")?.at(i),
                        code = d.doubles("weather_code")?.at(i)?.toInt(),
                        tempMax = d.doubles("temperature_2m_max")?.at(i),
                        tempMin = d.doubles("temperature_2m_min")?.at(i),
                    )
                }
            }.orEmpty()
            return ProviderResult(mapOf(SourceId.BEST_MATCH to SourceOutcome.Success(samples)), current, daily)
        }
    }
}

/**
 * The individual national weather models, all via Open-Meteo in one request.
 * Only models with genuine data over Morocco are listed (checked against the API):
 * "seamless" regional models such as KNMI or DMI just fall back to ECMWF here.
 */
class OpenMeteoModels : WeatherProvider {
    companion object {
        val modelNames: Map<SourceId, String> = linkedMapOf(
            SourceId.ECMWF to "ecmwf_ifs",
            SourceId.ECMWF_AIFS to "ecmwf_aifs025_single",
            SourceId.METEOFRANCE to "meteofrance_seamless",
            SourceId.ICON to "icon_seamless",
            SourceId.UKMO to "ukmo_seamless",
            SourceId.GFS to "gfs_seamless",
            SourceId.GEM to "gem_seamless",
            SourceId.JMA to "jma_seamless",
            SourceId.CMA to "cma_grapes_global",
        )

        fun parse(body: String, sources: Collection<SourceId>): Map<SourceId, SourceOutcome> {
            val hourly = OdemaJson.parseToJsonElement(body).jsonObject.obj("hourly")
                ?: throw IllegalStateException("no hourly data")
            val times = hourly.longs("time").orEmpty()
            return sources.associateWith { source ->
                val model = modelNames[source] ?: return@associateWith SourceOutcome.NotCovered
                val amounts = hourly.doubles("precipitation_$model").orEmpty()
                val probabilities = hourly.doubles("precipitation_probability_$model").orEmpty()
                val codes = hourly.doubles("weather_code_$model").orEmpty()
                val snow = hourly.doubles("snowfall_$model").orEmpty()
                val samples = times.indices.mapNotNull { i ->
                    val t = times[i] ?: return@mapNotNull null
                    val amount = amounts.at(i)
                    val probability = probabilities.at(i)
                    if (amount == null && probability == null) return@mapNotNull null
                    PrecipSample(
                        start = t - 3600, duration = 3600,
                        intensity = amount, probability = probability,
                        snow = snow.at(i), code = codes.at(i)?.toInt(),
                    )
                }
                // A model answers all-null where it has no data for the point.
                if (samples.isEmpty()) SourceOutcome.NotCovered else SourceOutcome.Success(samples)
            }
        }
    }

    override val sources = modelNames.keys.toList()
    override val ttlSeconds = 60 * 60L

    fun url(place: Place, models: List<String>): String = buildUrl(
        "https://api.open-meteo.com/v1/forecast",
        "latitude" to place.point.lat4,
        "longitude" to place.point.lon4,
        *elevation(place),
        "hourly" to "precipitation,precipitation_probability,weather_code,snowfall",
        "models" to models.joinToString(","),
        "past_hours" to "1",
        "forecast_hours" to "72",
        "timeformat" to "unixtime",
        "timezone" to "GMT",
    )

    override suspend fun fetch(place: Place, sources: Set<SourceId>, now: Long, client: HttpClient): ProviderResult {
        val wanted = this.sources.filter { it in sources }
        if (wanted.isEmpty()) return ProviderResult(emptyMap())
        return guarded(wanted.toSet()) {
            val body = client.fetch(url(place, wanted.mapNotNull { modelNames[it] }))
            ProviderResult(parse(body, wanted).mapValues { (_, o) -> o.clipped(now) })
        }
    }
}

/**
 * Ensemble forecasts: the same model run 31–51 times with slightly different
 * starting conditions. The share of members that produce rain is a real,
 * calibrated probability — the best answer to "how sure is this?".
 */
class OpenMeteoEnsemble : WeatherProvider {
    companion object {
        val modelNames: Map<SourceId, String> = linkedMapOf(
            SourceId.ECMWF_ENS to "ecmwf_ifs025",
            SourceId.GFS_ENS to "gfs025",
            SourceId.ICON_ENS to "icon_seamless",
        )

        /** The response suffixes each series with the ensemble's name, e.g. `_ncep_gefs025`. */
        private fun sourceForSuffix(suffix: String): SourceId? = when {
            "ecmwf" in suffix -> SourceId.ECMWF_ENS
            "gefs" in suffix || "gfs" in suffix -> SourceId.GFS_ENS
            "icon" in suffix -> SourceId.ICON_ENS
            else -> null
        }

        private val memberKey = Regex("^precipitation(?:_member\\d+)?_(.+)$")

        fun parse(body: String, sources: Collection<SourceId>): Map<SourceId, SourceOutcome> {
            val hourly = OdemaJson.parseToJsonElement(body).jsonObject.obj("hourly")
                ?: throw IllegalStateException("no hourly data")
            val times = hourly.longs("time").orEmpty()
            val series = mutableMapOf<SourceId, MutableList<List<Double?>>>()
            for (key in hourly.keys) {
                val suffix = memberKey.find(key)?.groupValues?.get(1) ?: continue
                val source = sourceForSuffix(suffix) ?: continue
                hourly.doubles(key)?.let { series.getOrPut(source) { mutableListOf() } += it }
            }
            return sources.associateWith { source ->
                val members = series[source].orEmpty()
                val samples = times.indices.mapNotNull { i ->
                    val t = times[i] ?: return@mapNotNull null
                    val values = members.mapNotNull { it.at(i) }
                    if (values.isEmpty()) return@mapNotNull null
                    PrecipSample(
                        start = t - 3600, duration = 3600,
                        intensity = values.average(),
                        probability = 100.0 * values.count { it >= 0.1 } / values.size,
                    )
                }
                if (samples.isEmpty()) SourceOutcome.NotCovered
                else SourceOutcome.Success(samples, note = "${members.size} members")
            }
        }
    }

    override val sources = modelNames.keys.toList()
    override val ttlSeconds = 2 * 60 * 60L

    fun url(place: Place, models: List<String>): String = buildUrl(
        "https://ensemble-api.open-meteo.com/v1/ensemble",
        "latitude" to place.point.lat4,
        "longitude" to place.point.lon4,
        "hourly" to "precipitation",
        "models" to models.joinToString(","),
        "past_hours" to "1",
        "forecast_hours" to "168",
        "timeformat" to "unixtime",
        "timezone" to "GMT",
    )

    override suspend fun fetch(place: Place, sources: Set<SourceId>, now: Long, client: HttpClient): ProviderResult {
        val wanted = this.sources.filter { it in sources }
        if (wanted.isEmpty()) return ProviderResult(emptyMap())
        return guarded(wanted.toSet()) {
            val body = client.fetch(url(place, wanted.mapNotNull { modelNames[it] }))
            ProviderResult(parse(body, wanted).mapValues { (_, o) -> o.clipped(now) })
        }
    }
}

/** Uses the town's real elevation when we know it, so mountain towns aren't averaged with valleys. */
private fun elevation(place: Place): Array<Pair<String, String>> =
    place.elevation?.let { arrayOf("elevation" to "%.0f".format(java.util.Locale.ROOT, it)) } ?: emptyArray()

internal fun SourceOutcome.clipped(now: Long): SourceOutcome =
    if (this is SourceOutcome.Success) copy(samples = samples.clip(now)) else this

/** Open-Meteo's multi-location form: one request, an array of answers. Used by the Morocco overview. */
object OpenMeteoOverview {
    fun url(points: List<Pair<Double, Double>>, timezone: String = "Africa/Casablanca"): String = buildUrl(
        "https://api.open-meteo.com/v1/forecast",
        "latitude" to points.joinToString(",") { "%.4f".format(java.util.Locale.ROOT, it.first) },
        "longitude" to points.joinToString(",") { "%.4f".format(java.util.Locale.ROOT, it.second) },
        "daily" to "weather_code,precipitation_sum,precipitation_probability_max,snowfall_sum,temperature_2m_max",
        "current" to "temperature_2m,precipitation,weather_code,is_day",
        "forecast_days" to "3",
        "timeformat" to "unixtime",
        "timezone" to timezone,
    )

    data class Entry(val current: CurrentConditions?, val daily: List<DailyForecast>)

    fun parse(body: String): List<Entry> {
        val element = OdemaJson.parseToJsonElement(body)
        val objects = when (element) {
            is kotlinx.serialization.json.JsonArray -> element.map { it.jsonObject }
            is JsonObject -> listOf(element)
            else -> emptyList()
        }
        return objects.map { root ->
            val parsed = OpenMeteoBestMatch.parse(
                // Reuse the best-match parser: add an empty hourly block it requires.
                OdemaJson.encodeToString(JsonObject.serializer(), JsonObject(root + ("hourly" to OdemaJson.parseToJsonElement("{\"time\":[]}")))),
            )
            Entry(parsed.current, parsed.daily)
        }
    }

    suspend fun fetch(points: List<Pair<Double, Double>>, client: HttpClient): List<Entry> =
        parse(client.fetch(url(points)))
}
