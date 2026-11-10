package ma.odema.core.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import ma.odema.core.HttpClient
import ma.odema.core.OdemaJson
import ma.odema.core.Place
import ma.odema.core.PrecipSample
import ma.odema.core.ProviderException
import ma.odema.core.SourceId
import ma.odema.core.SourceOutcome
import ma.odema.core.buildUrl
import ma.odema.core.fetch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * MET Norway Locationforecast 2.0 — worldwide, hourly for ~2.5 days, then 6-hourly.
 * https://api.met.no/weatherapi/locationforecast/2.0/documentation — free, CC BY 4.0.
 * Requires an identifying User-Agent (set by the HTTP client).
 */
class MetNorway : WeatherProvider {
    override val sources = listOf(SourceId.MET_NORWAY)
    override val ttlSeconds = 30 * 60L

    fun url(place: Place): String = buildUrl(
        "https://api.met.no/weatherapi/locationforecast/2.0/complete",
        "lat" to place.point.lat4,
        "lon" to place.point.lon4,
        *(place.elevation?.let { arrayOf("altitude" to it.toInt().toString()) } ?: emptyArray()),
    )

    override suspend fun fetch(place: Place, sources: Set<SourceId>, now: Long, client: HttpClient): ProviderResult =
        guarded(sources) {
            val samples = parse(client.fetch(url(place), notCoveredStatuses = setOf(422)))
            ProviderResult(mapOf(SourceId.MET_NORWAY to SourceOutcome.Success(samples.clip(now))))
        }

    companion object {
        /** `next_N_hours` describes the period *after* each timestamp. */
        fun parse(body: String): List<PrecipSample> {
            val series = OdemaJson.parseToJsonElement(body).jsonObject.obj("properties")?.get("timeseries") as? JsonArray
                ?: throw IllegalStateException("no timeseries")
            val samples = mutableListOf<PrecipSample>()
            var coveredUntil = Long.MIN_VALUE
            for (element in series) {
                val entry = element.jsonObject
                val start = entry["time"].asString()?.let(::parseIso) ?: continue
                if (start < coveredUntil) continue
                val data = entry.obj("data") ?: continue
                val temp = data.obj("instant")?.obj("details")?.get("air_temperature").asDouble()
                val one = data.obj("next_1_hours")
                val six = data.obj("next_6_hours")
                val period = one?.takeIf { it.obj("details")?.get("precipitation_amount") != null } ?: six ?: continue
                val hours = if (period === one) 1 else 6
                val details = period.obj("details") ?: continue
                val amount = details["precipitation_amount"].asDouble() ?: continue
                val symbol = period.obj("summary")?.get("symbol_code").asString()
                samples += PrecipSample(
                    start = start, duration = hours * 3600L,
                    intensity = amount / hours,
                    probability = details["probability_of_precipitation"].asDouble(),
                    code = symbol?.let(::symbolToCode),
                    temp = temp,
                )
                coveredUntil = start + hours * 3600L
            }
            return samples
        }

        /** Maps MET symbol codes onto WMO weather codes, enough for storm/snow detection. */
        fun symbolToCode(symbol: String): Int? = when {
            "thunder" in symbol -> 95
            "snow" in symbol -> 73
            "sleet" in symbol -> 68
            "heavyrain" in symbol -> 65
            "lightrain" in symbol -> 61
            "rain" in symbol -> 63
            "fog" in symbol -> 45
            "cloudy" in symbol -> 3
            "partlycloudy" in symbol -> 2
            "fair" in symbol -> 1
            "clearsky" in symbol -> 0
            else -> null
        }
    }
}

/**
 * Bright Sky — the German Weather Service's MOSMIX station forecasts as JSON.
 * https://brightsky.dev/docs — free, no key. Moroccan stations include Tanger,
 * Casablanca, Fès-Saïs, Marrakech, Agadir and Oujda; with no station within
 * 50 km the API answers 404 and we report "not covered".
 */
class BrightSky : WeatherProvider {
    override val sources = listOf(SourceId.BRIGHT_SKY)
    override val ttlSeconds = 60 * 60L

    fun url(place: Place, now: Long): String {
        val f = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)
        val from = (now / 3600) * 3600
        return buildUrl(
            "https://api.brightsky.dev/weather",
            "lat" to place.point.lat4,
            "lon" to place.point.lon4,
            "date" to f.format(Instant.ofEpochSecond(from)),
            "last_date" to f.format(Instant.ofEpochSecond(from + 8 * 86_400L)),
            "tz" to "Etc/UTC",
        )
    }

    override suspend fun fetch(place: Place, sources: Set<SourceId>, now: Long, client: HttpClient): ProviderResult =
        guarded(sources) {
            val (samples, note) = parse(client.fetch(url(place, now), notCoveredStatuses = setOf(404)))
            if (samples.isEmpty()) throw ProviderException("no station", notCovered = true)
            ProviderResult(mapOf(SourceId.BRIGHT_SKY to SourceOutcome.Success(samples.clip(now), note)))
        }

    companion object {
        /** Values describe the hour *preceding* each timestamp. Returns samples and "station, distance". */
        fun parse(body: String): Pair<List<PrecipSample>, String?> {
            val root = OdemaJson.parseToJsonElement(body).jsonObject
            val records = root["weather"] as? JsonArray ?: throw IllegalStateException("no weather")
            val samples = records.mapNotNull { element ->
                val r = element as? JsonObject ?: return@mapNotNull null
                val end = r["timestamp"].asString()?.let(::parseIso) ?: return@mapNotNull null
                val amount = r["precipitation"].asDouble()
                val probability = r["precipitation_probability"].asDouble()
                if (amount == null && probability == null) return@mapNotNull null
                PrecipSample(
                    start = end - 3600, duration = 3600,
                    intensity = amount, probability = probability,
                    code = when (r["condition"].asString()) {
                        "thunderstorm" -> 95
                        "hail" -> 96
                        "snow" -> 73
                        "sleet" -> 68
                        "rain" -> 61
                        else -> null
                    },
                    temp = r["temperature"].asDouble(),
                )
            }
            val station = (root["sources"] as? JsonArray)?.firstOrNull()?.jsonObject
            val note = station?.let {
                val name = it["station_name"].asString() ?: return@let null
                val km = it["distance"].asDouble()?.div(1000)
                if (km != null) "%s, %.0f km".format(java.util.Locale.ROOT, name, km) else name
            }
            return samples to note
        }
    }
}
