package ma.odema.core

import kotlinx.coroutines.runBlocking
import ma.odema.core.providers.OpenMeteoOverview
import org.junit.Assume.assumeTrue
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Calls the real APIs. Run with: ./gradlew :core:test -Plive --tests '*LiveTest*' */
class LiveTest {
    @Before
    fun onlyWhenAsked() = assumeTrue("set -Plive to call the real APIs", System.getProperty("odema.live") == "1")

    @Test
    fun everySourceAnswersForMorocco() = runBlocking {
        val service = ForecastService()
        val now = System.currentTimeMillis() / 1000
        val succeeded = mutableSetOf<SourceId>()
        for (id in listOf("casablanca", "ifrane", "marrakesh", "dakhla")) {
            val city = MoroccoCities.byId(id)!!
            val place = Place(id, city.fr, city.point, city.id, city.region, city.elevation)
            val r = service.report(place, SourceId.entries.toSet(), now)
            for (s in SourceId.entries) {
                val o = r.outcomes[s]
                val text = when (o) {
                    is SourceOutcome.Success -> { succeeded += s; "${o.samples.size} samples${o.note?.let { ", $it" } ?: ""}" }
                    SourceOutcome.NotCovered -> "not covered"
                    is SourceOutcome.Failure -> "FAILED ${o.message}"
                    null -> "missing"
                }
                println("[live] ${city.fr} ${s.name}: $text")
            }
            val o = RainAnalyzer.outlook(r, AlertSettings(), now)
            println("[live] ${city.fr}: now ${r.current?.temperature}°C code ${r.current?.code}; rain in 2 h: ${o.willRain} (${o.agreeing}/${o.available}); 7 days: ${r.daily.map { it.precipitationSum }}")
        }
        assertEquals(SourceId.entries.toSet(), succeeded, "every source should work somewhere in Morocco")
    }

    @Test
    fun overviewAndGeocoder() = runBlocking {
        val capitals = MoroccoRegions.all.map { MoroccoCities.byId(it.capitalId)!! }
        val entries = OpenMeteoOverview.fetch(capitals.map { it.lat to it.lon }, UrlConnectionHttpClient())
        assertEquals(12, entries.size)
        capitals.zip(entries).forEach { (c, e) -> println("[live] overview ${c.fr}: ${e.daily.map { it.precipitationSum }}") }
        val results = MoroccoGeocoder().search("Chefchaouen", "ar")
        println("[live] geocoder: $results")
        assertTrue(results.isNotEmpty())
    }
}
