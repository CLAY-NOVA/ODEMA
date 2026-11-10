package ma.odema.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import ma.odema.core.providers.BrightSky
import ma.odema.core.providers.MetNorway
import ma.odema.core.providers.OpenMeteoBestMatch
import ma.odema.core.providers.OpenMeteoEnsemble
import ma.odema.core.providers.OpenMeteoModels
import ma.odema.core.providers.ProviderResult
import ma.odema.core.providers.WeatherProvider
import ma.odema.core.providers.asDouble
import ma.odema.core.providers.asString

/**
 * Asks every enabled public weather API at once and merges the answers. Data that is
 * still fresh (models update every few hours) is reused from [previous] to save calls.
 */
class ForecastService(
    private val client: HttpClient = UrlConnectionHttpClient(),
    val providers: List<WeatherProvider> = defaultProviders(),
) {
    companion object {
        fun defaultProviders(): List<WeatherProvider> = listOf(
            OpenMeteoBestMatch(),
            OpenMeteoEnsemble(),
            OpenMeteoModels(),
            MetNorway(),
            BrightSky(),
        )
    }

    suspend fun report(
        place: Place,
        sources: Set<SourceId>,
        now: Long,
        previous: ForecastReport? = null,
        forceFresh: Boolean = false,
    ): ForecastReport = coroutineScope {
        val jobs = providers.mapNotNull { provider ->
            val wanted = provider.sources.filter { it in sources }.toSet()
            if (wanted.isEmpty()) return@mapNotNull null
            val cached = previous?.takeIf { !forceFresh }?.let { reuse(it, provider, wanted, now) }
            async {
                if (cached != null) cached to false
                else provider.fetch(place, wanted, now, client) to true
            }
        }
        val results = jobs.awaitAll()
        val outcomes = mutableMapOf<SourceId, SourceOutcome>()
        val times = mutableMapOf<SourceId, Long>()
        var current: CurrentConditions? = null
        var daily: List<DailyForecast> = emptyList()
        for ((result, fresh) in results) {
            for ((source, outcome) in result.outcomes) {
                // On failure, keep the last good data if it is less than 6 hours old.
                val old = previous?.outcomes?.get(source)
                val oldTime = previous?.sourceTimes?.get(source)
                if (outcome is SourceOutcome.Failure && old is SourceOutcome.Success && oldTime != null && now - oldTime < 6 * 3600) {
                    outcomes[source] = old
                    times[source] = oldTime
                } else {
                    outcomes[source] = outcome
                    times[source] = if (fresh) now else previous?.sourceTimes?.get(source) ?: now
                }
            }
            if (result.current != null) current = result.current
            if (result.daily.isNotEmpty()) daily = result.daily
        }
        if (current == null) current = previous?.current
        if (daily.isEmpty()) daily = previous?.daily.orEmpty()
        ForecastReport(fetchedAt = now, outcomes = outcomes, sourceTimes = times, current = current, daily = daily)
    }

    private fun reuse(previous: ForecastReport, provider: WeatherProvider, wanted: Set<SourceId>, now: Long): ProviderResult? {
        val fresh = wanted.all { source ->
            val time = previous.sourceTimes[source] ?: return@all false
            val outcome = previous.outcomes[source] ?: return@all false
            // "Not covered" is stable; successes expire after the provider's TTL.
            when (outcome) {
                is SourceOutcome.Success -> now - time < provider.ttlSeconds
                SourceOutcome.NotCovered -> now - time < 24 * 3600
                is SourceOutcome.Failure -> false
            }
        }
        if (!fresh) return null
        val isBestMatch = SourceId.BEST_MATCH in wanted
        return ProviderResult(
            outcomes = wanted.associateWith { previous.outcomes.getValue(it) },
            current = if (isBestMatch) previous.current else null,
            daily = if (isBestMatch) previous.daily else emptyList(),
        )
    }
}

/** A place-name search result. */
data class GeocodingResult(
    val id: Long,
    val name: String,
    val region: String?,
    val point: GeoPoint,
    val elevation: Double?,
)

/** Open-Meteo geocoding (GeoNames data), limited to Morocco. Free, no key. */
class MoroccoGeocoder(private val client: HttpClient = UrlConnectionHttpClient()) {
    fun url(query: String, language: String, countryCode: String): String = buildUrl(
        "https://geocoding-api.open-meteo.com/v1/search",
        "name" to query,
        "count" to "20",
        "language" to language,
        "countryCode" to countryCode,
        "format" to "json",
    )

    /** Searches Morocco, including the southern provinces (which GeoNames files separately). */
    suspend fun search(query: String, language: String): List<GeocodingResult> = coroutineScope {
        val q = query.trim()
        if (q.length < 2) return@coroutineScope emptyList()
        listOf("MA", "EH")
            .map { cc -> async { parse(client.fetch(url(q, language, cc))) } }
            .awaitAll()
            .flatten()
            .filter { MoroccoBounds.contains(it.point) }
            .distinctBy { it.id }
    }

    companion object {
        fun parse(body: String): List<GeocodingResult> {
            val results = OdemaJson.parseToJsonElement(body).jsonObject["results"] as? JsonArray ?: return emptyList()
            return results.mapNotNull { element ->
                val o = element.jsonObject
                val lat = o["latitude"].asDouble() ?: return@mapNotNull null
                val lon = o["longitude"].asDouble() ?: return@mapNotNull null
                GeocodingResult(
                    id = o["id"].asDouble()?.toLong() ?: return@mapNotNull null,
                    name = o["name"].asString() ?: return@mapNotNull null,
                    region = o["admin1"].asString(),
                    point = GeoPoint(lat, lon),
                    elevation = o["elevation"].asDouble(),
                )
            }
        }
    }
}
