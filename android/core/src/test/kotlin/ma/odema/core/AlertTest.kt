package ma.odema.core

import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertTest {
    private val utc = ZoneOffset.UTC
    private val base = AlertSettings(cooldownMinutes = 180)

    private fun eval(
        triggered: Boolean, memory: AlertMemory, at: String, settings: AlertSettings = base,
        kind: AlertKind = AlertKind.RAIN_SOON, placeOn: Boolean = true,
    ) = AlertPolicy.evaluate(kind, triggered, memory, settings, placeOn, t(at), utc)

    @Test
    fun oncePerEpisode() {
        var (d, m) = eval(true, AlertMemory(), "2026-11-10T12:00:00Z")
        assertEquals(AlertDecision.Notify, d)
        assertEquals(t("2026-11-10T12:00:00Z"), m.lastNotifiedAt)
        eval(true, m, "2026-11-10T12:15:00Z").also { (d2, m2) -> d = d2; m = m2 }
        assertEquals(AlertDecision.Suppressed(SuppressReason.ALREADY_ALERTED), d)
        eval(false, m, "2026-11-10T12:30:00Z").also { (d2, m2) -> d = d2; m = m2 }
        assertEquals(AlertDecision.Clear, d)
        assertFalse(m.episodeActive)
    }

    @Test
    fun cooldownMergesCloseEpisodes() {
        var m = eval(true, AlertMemory(), "2026-11-10T12:00:00Z").second
        m = eval(false, m, "2026-11-10T13:00:00Z").second
        val (d, m2) = eval(true, m, "2026-11-10T14:00:00Z")
        assertEquals(AlertDecision.Suppressed(SuppressReason.COOLDOWN), d)
        assertTrue(m2.episodeActive)
        m = eval(false, m2, "2026-11-10T15:30:00Z").second
        assertEquals(AlertDecision.Notify, eval(true, m, "2026-11-10T16:00:00Z").first)
    }

    @Test
    fun quietHoursPostponeButSevereGetsThrough() {
        val quiet = base.copy(quietEnabled = true, quietStart = 22 * 60, quietEnd = 7 * 60, severeIgnoresQuiet = true)
        val (d, m) = eval(true, AlertMemory(), "2026-11-10T23:30:00Z", quiet)
        assertEquals(AlertDecision.Suppressed(SuppressReason.QUIET_HOURS), d)
        assertFalse(m.episodeActive)
        assertEquals(AlertDecision.Notify, eval(true, m, "2026-11-11T07:05:00Z", quiet).first)
        assertEquals(AlertDecision.Notify, eval(true, AlertMemory(), "2026-11-10T23:30:00Z", quiet, AlertKind.HEAVY_RAIN).first)
        val strict = quiet.copy(severeIgnoresQuiet = false)
        assertEquals(AlertDecision.Suppressed(SuppressReason.QUIET_HOURS), eval(true, AlertMemory(), "2026-11-10T23:30:00Z", strict, AlertKind.STORM).first)
    }

    @Test
    fun switchesOff() {
        assertEquals(AlertDecision.Suppressed(SuppressReason.ALERTS_OFF), eval(true, AlertMemory(), "2026-11-10T12:00:00Z", placeOn = false).first)
        val noSnow = base.copy(snowEnabled = false)
        val (d, m) = eval(true, AlertMemory(), "2026-11-10T12:00:00Z", noSnow, AlertKind.SNOW)
        assertEquals(AlertDecision.Suppressed(SuppressReason.KIND_OFF), d)
        assertNull(m.lastNotifiedAt)
    }

    @Test
    fun quietRangeWrapsMidnight() {
        fun q(at: String, s: Int, e: Int) = AlertPolicy.isQuiet(t(at), s, e, utc)
        assertTrue(q("2026-11-10T23:30:00Z", 1320, 420))
        assertTrue(q("2026-11-10T06:59:00Z", 1320, 420))
        assertFalse(q("2026-11-10T07:00:00Z", 1320, 420))
        assertFalse(q("2026-11-10T12:00:00Z", 1320, 420))
        assertTrue(q("2026-11-10T13:00:00Z", 720, 840))
        assertFalse(q("2026-11-10T13:00:00Z", 600, 600))
    }

    @Test
    fun engineReportsSevereFirstAndRemembers() {
        val storm = ma.odema.core.providers.OpenMeteoBestMatch.parse(Fixtures.text("bestmatch_ifrane_synthetic.json"))
        val r = ForecastReport(T0, storm.outcomes, daily = storm.daily)
        val settings = AlertSettings(severeAgreement = 0.35, aheadEnabled = true, rainStoppingEnabled = false)
        val result = AlertEngine.evaluate(ifrane, r, settings, emptyMap(), T0, utc)
        val kinds = result.events.map { it.kind }
        assertTrue(AlertKind.RAIN_SOON in kinds && AlertKind.HEAVY_RAIN in kinds && AlertKind.STORM in kinds && AlertKind.SNOW in kinds && AlertKind.RAIN_AHEAD in kinds, "$kinds")
        assertTrue(result.events.first().kind.severe)
        // Same forecast ten minutes later: nothing new.
        val again = AlertEngine.evaluate(ifrane, r, settings, result.memory, T0 + 600, utc)
        assertTrue(again.events.isEmpty(), "${again.events}")
    }

    @Test
    fun settingsSurviveUnknownAndMissingFields() {
        val decoded = OdemaJson.decodeFromString(AlertSettings.serializer(), """{"lookaheadMinutes":60,"futureField":1,"disabledSourceNames":["GFS","REMOVED"]}""")
        assertEquals(60, decoded.lookaheadMinutes)
        assertFalse(SourceId.GFS in decoded.enabledSources)
        assertEquals(AlertSettings().heavyHourly, decoded.heavyHourly)
        val reportJson = OdemaJson.encodeToString(ForecastReport.serializer(), ForecastReport(1, mapOf(SourceId.GFS to SourceOutcome.NotCovered, SourceId.ICON to ok(sample("2026-11-10T12:00:00Z", mm = 1.0)))))
        assertEquals(SourceOutcome.NotCovered, OdemaJson.decodeFromString(ForecastReport.serializer(), reportJson).outcomes[SourceId.GFS])
    }
}
