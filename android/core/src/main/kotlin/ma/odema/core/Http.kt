package ma.odema.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class HttpResponse(val status: Int, val body: String)

/** Minimal HTTP abstraction so providers can be tested with recorded responses. */
fun interface HttpClient {
    suspend fun get(url: String, headers: Map<String, String>): HttpResponse
}

/** HttpURLConnection-based client; works on Android and the JVM without extra libraries. */
class UrlConnectionHttpClient(
    private val userAgent: String = "OdemaMaroc/1.0 (rain alerts; github.com/CLAY-NOVA/ODEMA)",
    private val timeoutMs: Int = 20_000,
) : HttpClient {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", userAgent)
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            HttpResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }
}

class ProviderException(message: String, val notCovered: Boolean = false) : IOException(message)

/** GET that throws on non-2xx; [notCoveredStatuses] mean "this place isn't covered". */
internal suspend fun HttpClient.fetch(
    url: String,
    headers: Map<String, String> = emptyMap(),
    notCoveredStatuses: Set<Int> = emptySet(),
): String {
    val response = get(url, headers)
    if (response.status in notCoveredStatuses) throw ProviderException("not covered", notCovered = true)
    if (response.status !in 200..299) {
        val reason = errorReason(response.body)
        val prefix = if (response.status == 429) "Rate limited (HTTP 429)" else "HTTP ${response.status}"
        throw ProviderException(if (reason != null) "$prefix: $reason" else prefix)
    }
    return response.body
}

/** Open-Meteo says {"reason": ...}; Bright Sky says {"detail": ...}. */
internal fun errorReason(body: String): String? = try {
    val obj = OdemaJson.parseToJsonElement(body) as? JsonObject
    (obj?.get("reason") ?: obj?.get("detail"))?.jsonPrimitive?.contentOrNull?.take(140)
} catch (_: Exception) {
    null
}

internal fun buildUrl(base: String, vararg query: Pair<String, String>): String =
    base + "?" + query.joinToString("&") { (k, v) ->
        // Keep commas readable: the APIs take comma-separated lists.
        "$k=${URLEncoder.encode(v, "UTF-8").replace("%2C", ",")}"
    }
