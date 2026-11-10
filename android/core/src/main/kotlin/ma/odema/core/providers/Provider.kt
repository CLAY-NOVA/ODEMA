package ma.odema.core.providers

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import ma.odema.core.CurrentConditions
import ma.odema.core.DailyForecast
import ma.odema.core.HttpClient
import ma.odema.core.Place
import ma.odema.core.ProviderException
import ma.odema.core.SourceId
import ma.odema.core.SourceOutcome
import java.time.Instant

/** What one API call produced. */
data class ProviderResult(
    val outcomes: Map<SourceId, SourceOutcome>,
    val current: CurrentConditions? = null,
    val daily: List<DailyForecast> = emptyList(),
)

/** A public weather API. One provider may serve several forecast feeds. */
interface WeatherProvider {
    val sources: List<SourceId>

    /** How long a successful answer stays fresh; models only update every few hours. */
    val ttlSeconds: Long

    /**
     * Fetches the requested subset of [sources]. Never throws (except cancellation):
     * every requested source gets an outcome so one broken API can't sink the others.
     */
    suspend fun fetch(place: Place, sources: Set<SourceId>, now: Long, client: HttpClient): ProviderResult
}

internal suspend fun WeatherProvider.guarded(
    sources: Set<SourceId>,
    body: suspend () -> ProviderResult,
): ProviderResult = try {
    body()
} catch (e: CancellationException) {
    throw e
} catch (e: ProviderException) {
    val outcome = if (e.notCovered) SourceOutcome.NotCovered else SourceOutcome.Failure(e.message ?: "error")
    ProviderResult(sources.associateWith { outcome })
} catch (e: Exception) {
    val message = e.message?.take(140) ?: e.javaClass.simpleName
    ProviderResult(sources.associateWith { SourceOutcome.Failure(message) })
}

/** Keeps samples from an hour ago up to [horizonSeconds] ahead, sorted. */
internal fun <T : ma.odema.core.PrecipSample> List<T>.clip(now: Long, horizonSeconds: Long = 8 * 86_400L): List<T> =
    filter { it.end > now - 3600 && it.start < now + horizonSeconds }.sortedBy { it.start }

// JSON helpers for the column-oriented arrays the APIs return.

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.doubles(key: String): List<Double?>? =
    (this[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.doubleOrNull }

internal fun JsonObject.longs(key: String): List<Long?>? =
    (this[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.let { p -> p.longOrNull ?: p.doubleOrNull?.toLong() } }

internal fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.doubleOrNull

internal fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

internal fun parseIso(text: String): Long? = try {
    java.time.OffsetDateTime.parse(text).toEpochSecond()
} catch (_: Exception) {
    try {
        Instant.parse(text).epochSecond
    } catch (_: Exception) {
        null
    }
}

internal fun <T> List<T>.at(i: Int): T? = if (i in indices) this[i] else null
