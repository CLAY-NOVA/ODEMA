package ma.odema.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ma.odema.core.providers.OpenMeteoBestMatch

class AnalysisTest {
    private val now = t("2026-11-10T12:00:00Z")
    private val settings = AlertSettings(lookaheadMinutes = 120, minIntensity = 0.2, minProbability = 60.0, requiredAgreement = 0.5)

    @Test
    fun rainyByIntensityOrProbability() {
        assertTrue(RainAnalyzer.isRainy(sample("2026-11-10T12:00:00Z", mm = 0.2), settings))
        assertFalse(RainAnalyzer.isRainy(sample("2026-11-10T12:00:00Z", mm = 0.1), settings))
        assertTrue(RainAnalyzer.isRainy(sample("2026-11-10T12:00:00Z", pct = 60.0), settings))
        assertFalse(RainAnalyzer.isRainy(sample("2026-11-10T12:00:00Z", mm = 0.0, pct = 30.0), settings))
    }

    @Test
    fun allDryMeansNoRain() {
        val o = RainAnalyzer.outlook(
            report(now, SourceId.GFS to ok(sample("2026-11-10T12:00:00Z", 120, mm = 0.0)), SourceId.ICON to ok(sample("2026-11-10T12:00:00Z", 120, mm = 0.1, pct = 20.0))),
            settings, now,
        )
        assertFalse(o.willRain)
        assertEquals(0.0, o.confidence)
        assertEquals(2, o.available)
        assertNull(o.expectedStart)
    }

    @Test
    fun majorityTriggersAndNotCoveredDoesNotVote() {
        val o = RainAnalyzer.outlook(
            report(
                now,
                SourceId.GFS to ok(sample("2026-11-10T12:00:00Z", mm = 0.0), sample("2026-11-10T13:00:00Z", mm = 1.5)),
                SourceId.ICON to ok(sample("2026-11-10T12:00:00Z", mm = 0.0), sample("2026-11-10T13:00:00Z", mm = 2.0)),
                SourceId.UKMO to ok(sample("2026-11-10T12:00:00Z", 120, mm = 0.0)),
                SourceId.BRIGHT_SKY to SourceOutcome.NotCovered,
                SourceId.JMA to SourceOutcome.Failure("HTTP 500"),
            ),
            settings, now,
        )
        assertTrue(o.willRain)
        assertEquals(2, o.agreeing)
        assertEquals(3, o.available)
        assertEquals(2.0 / 3, o.confidence, 1e-9)
        assertEquals(t("2026-11-10T13:00:00Z"), o.expectedStart)
        assertEquals(1.75, o.peakIntensity!!, 1e-9)
        assertEquals(1.75, o.expectedTotal!!, 1e-9)
    }

    @Test
    fun disabledSourcesAreIgnored() {
        val s = settings.withSource(SourceId.GFS, enabled = false)
        val o = RainAnalyzer.outlook(
            report(now, SourceId.GFS to ok(sample("2026-11-10T12:00:00Z", 120, mm = 5.0)), SourceId.ICON to ok(sample("2026-11-10T12:00:00Z", 120, mm = 0.0))),
            s, now,
        )
        assertFalse(o.willRain)
        assertEquals(listOf(SourceId.ICON), o.verdicts.map { it.source })
    }

    @Test
    fun dryShortSourceOnlyVouchesForItsCoverage() {
        val s = settings.copy(lookaheadMinutes = 6 * 60)
        val o = RainAnalyzer.outlook(
            report(
                now,
                // Best match (weight 1.5) dry but only covers 1.5 of 6 hours: counts 1.5 × 0.25.
                SourceId.BEST_MATCH to ok(sample("2026-11-10T12:00:00Z", 90, mm = 0.0)),
                SourceId.GFS to ok(sample("2026-11-10T12:00:00Z", 240, mm = 0.0), sample("2026-11-10T16:00:00Z", 120, mm = 2.0)),
            ),
            s, now,
        )
        assertEquals(0.25, o.verdicts.first { it.source == SourceId.BEST_MATCH }.coverage, 1e-9)
        assertEquals(1.0 / 1.375, o.confidence, 1e-9)
        assertTrue(o.willRain)
    }

    @Test
    fun weightedMedianFavoursHeavierSources() {
        val o = RainAnalyzer.outlook(
            report(
                now,
                SourceId.ECMWF_ENS to ok(sample("2026-11-10T12:00:00Z", 30, pct = 70.0), sample("2026-11-10T12:30:00Z", 90, pct = 70.0)),
                SourceId.GFS to ok(sample("2026-11-10T12:00:00Z", 60, mm = 0.0), sample("2026-11-10T13:00:00Z", 60, mm = 1.0)),
            ),
            settings, now,
        )
        assertEquals(now, o.expectedStart, "ensemble (1.5) outweighs GFS (1.0)")
        assertTrue(o.isRainingNow(now))
    }

    // Hazards on the synthetic Ifrane storm (best match) plus two models.

    private val storm = OpenMeteoBestMatch.parse(Fixtures.text("bestmatch_ifrane_synthetic.json"))
    private val stormReport = ForecastReport(
        fetchedAt = T0,
        outcomes = mapOf(
            SourceId.BEST_MATCH to storm.outcomes.getValue(SourceId.BEST_MATCH),
            SourceId.ICON to ok(
                PrecipSample(T0 + 3600, 3600, 3.0, code = 63), PrecipSample(T0 + 7200, 3600, 12.0, code = 95),
                PrecipSample(T0 + 10800, 20 * 3600L, 0.0, code = 3, snow = 0.0),
            ),
            SourceId.GFS to ok(PrecipSample(T0, 24 * 3600L, 0.0, code = 3, snow = 0.0)),
        ),
        daily = storm.daily,
    )

    @Test
    fun heavyRainFromPeakHour() {
        val h = RainAnalyzer.heavyRain(stormReport, AlertSettings(heavyHourly = 8.0, severeAgreement = 0.35), T0)
        assertTrue(h.triggered, "$h")
        assertEquals(2, h.agreeing)
        assertEquals(3, h.available)
        assertEquals(T0 + 7200, h.start)
        assertEquals(11.0, h.amount!!, 1e-9) // median of 10 and 12 mm/h
        // With "all sources must agree" it does not trigger.
        assertFalse(RainAnalyzer.heavyRain(stormReport, AlertSettings(severeAgreement = 1.0), T0).triggered)
    }

    @Test
    fun thunderstormFromWeatherCodes() {
        val h = RainAnalyzer.storm(stormReport, AlertSettings(), T0)
        assertTrue(h.triggered)
        assertEquals(T0 + 7200, h.start)
        assertEquals(2, h.agreeing)
    }

    @Test
    fun snowInTheAtlas() {
        val h = RainAnalyzer.snow(stormReport, AlertSettings(snowThresholdCm = 1.0, severeAgreement = 0.35), T0)
        assertTrue(h.triggered, "$h")
        assertEquals(6.4, h.amount!!, 0.01)
        assertEquals(T0 + 18 * 3600, h.start)
    }

    @Test
    fun rainAheadUsesDailyTotalsBeyondHourlyData() {
        val s = AlertSettings(aheadEnabled = true, aheadDays = 3, aheadMm = 10.0, requiredAgreement = 0.5)
        val onlyDaily = stormReport.copy(outcomes = mapOf(SourceId.GFS to ok(PrecipSample(T0, 24 * 3600L, 0.0))))
        val h = RainAnalyzer.rainAhead(onlyDaily, s, T0)
        // GFS covers 1 of 3 days so it can't vote; best match's daily sums give 15.5 + 0 + 6.5 = 22 mm.
        assertEquals(1, h.available)
        assertTrue(h.triggered)
        assertEquals(22.0, h.amount!!, 0.01)
    }

    @Test
    fun rainStoppingSoon() {
        val s = AlertSettings(requiredAgreement = 0.5, minIntensity = 0.2)
        val r = report(
            now,
            SourceId.BEST_MATCH to ok(sample("2026-11-10T11:45:00Z", 15, mm = 2.0), sample("2026-11-10T12:00:00Z", 30, mm = 1.0), sample("2026-11-10T12:30:00Z", 120, mm = 0.0)),
            SourceId.GFS to ok(sample("2026-11-10T12:00:00Z", 60, mm = 1.0), sample("2026-11-10T13:00:00Z", 120, mm = 0.0)),
        )
        val h = RainAnalyzer.stopping(r, s, now)
        assertTrue(h.triggered, "$h")
        assertEquals(t("2026-11-10T12:30:00Z"), h.start)
    }

    @Test
    fun timelineSlots() {
        val slots = RainAnalyzer.timeline(
            report(
                now,
                SourceId.GFS to ok(sample("2026-11-10T12:00:00Z", mm = 0.0), sample("2026-11-10T13:00:00Z", mm = 3.0)),
                SourceId.ICON to ok(sample("2026-11-10T12:00:00Z", mm = 0.0), sample("2026-11-10T13:00:00Z", mm = 0.0)),
            ),
            settings, now + 600, hours = 3, step = 3600,
        )
        assertEquals(3, slots.size)
        assertEquals(now, slots[0].start)
        assertEquals(0.0, slots[0].chance)
        assertEquals(0.5, slots[1].chance, 1e-9)
        assertEquals(1.5, slots[1].intensity!!, 1e-9)
        assertEquals(0, slots[2].sourceCount)
    }

    @Test
    fun coveredFractionHandlesGapsAndOverlap() {
        val samples = listOf(sample("2026-11-10T11:00:00Z", 120), sample("2026-11-10T12:30:00Z", 60), sample("2026-11-10T15:00:00Z", 120))
        assertEquals(2.5 / 4, RainAnalyzer.coveredFraction(now, now + 4 * 3600, samples), 1e-9)
    }

    @Test
    fun intensityWording() {
        assertEquals(RainIntensity.VERY_LIGHT, RainIntensity.of(0.3))
        assertEquals(RainIntensity.LIGHT, RainIntensity.of(1.0))
        assertEquals(RainIntensity.MODERATE, RainIntensity.of(4.0))
        assertEquals(RainIntensity.HEAVY, RainIntensity.of(12.0))
        assertEquals(RainIntensity.VIOLENT, RainIntensity.of(80.0))
    }
}
