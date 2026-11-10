package ma.odema.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/** What we remember per place and alert kind between checks, to avoid repeat alerts. */
@Serializable
data class AlertMemory(
    val lastNotifiedAt: Long? = null,
    /** True from the alert until the condition clears again. */
    val episodeActive: Boolean = false,
)

enum class SuppressReason { ALREADY_ALERTED, QUIET_HOURS, COOLDOWN, ALERTS_OFF, KIND_OFF }

sealed interface AlertDecision {
    data object Notify : AlertDecision
    data class Suppressed(val reason: SuppressReason) : AlertDecision
    /** Condition not met. */
    data object Clear : AlertDecision
}

/**
 * One alert per episode: when a condition starts. Clearing ends the episode;
 * the cooldown merges episodes that are close together; quiet hours postpone
 * (not cancel) alerts, except severe ones if the user allows.
 */
object AlertPolicy {
    fun evaluate(
        kind: AlertKind,
        triggered: Boolean,
        memory: AlertMemory,
        settings: AlertSettings,
        placeAlertsEnabled: Boolean,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Pair<AlertDecision, AlertMemory> {
        if (!triggered) return AlertDecision.Clear to memory.copy(episodeActive = false)
        if (memory.episodeActive) return AlertDecision.Suppressed(SuppressReason.ALREADY_ALERTED) to memory
        if (!placeAlertsEnabled) return AlertDecision.Suppressed(SuppressReason.ALERTS_OFF) to memory
        if (!kindEnabled(kind, settings)) return AlertDecision.Suppressed(SuppressReason.KIND_OFF) to memory
        val bypass = kind.severe && settings.severeIgnoresQuiet
        if (settings.quietEnabled && !bypass && isQuiet(now, settings.quietStart, settings.quietEnd, zone)) {
            return AlertDecision.Suppressed(SuppressReason.QUIET_HOURS) to memory
        }
        val cooldown = if (kind == AlertKind.RAIN_AHEAD) 24 * 3600L else settings.cooldownMinutes * 60L
        val last = memory.lastNotifiedAt
        if (last != null && now - last < cooldown) {
            return AlertDecision.Suppressed(SuppressReason.COOLDOWN) to memory.copy(episodeActive = true)
        }
        return AlertDecision.Notify to AlertMemory(lastNotifiedAt = now, episodeActive = true)
    }

    fun kindEnabled(kind: AlertKind, s: AlertSettings): Boolean = when (kind) {
        AlertKind.RAIN_SOON -> s.rainSoonEnabled
        AlertKind.RAIN_STOPPING -> s.rainStoppingEnabled
        AlertKind.HEAVY_RAIN -> s.heavyEnabled
        AlertKind.STORM -> s.stormEnabled
        AlertKind.SNOW -> s.snowEnabled
        AlertKind.RAIN_AHEAD -> s.aheadEnabled
    }

    /** [start]/[end] are minutes after local midnight; the range may wrap past midnight. */
    fun isQuiet(now: Long, start: Int, end: Int, zone: ZoneId): Boolean {
        if (start == end) return false
        val t = Instant.ofEpochSecond(now).atZone(zone)
        val minute = t.hour * 60 + t.minute
        return if (start < end) minute in start until end else minute >= start || minute < end
    }
}

/** Everything needed to word one alert; the app turns it into localized text. */
data class AlertEvent(
    val kind: AlertKind,
    val placeId: String,
    val start: Long?,
    /** mm/h peak for rain soon & heavy rain, mm for totals, cm for snow. */
    val amount: Double?,
    val amount2: Double?,
    val agreeing: Int,
    val available: Int,
    val confidence: Double,
    val rainingNow: Boolean = false,
)

/** Runs every alert check for one place and returns what to notify plus the new memory. */
object AlertEngine {
    data class Result(val events: List<AlertEvent>, val memory: Map<AlertKind, AlertMemory>)

    fun evaluate(
        place: Place,
        report: ForecastReport,
        settings: AlertSettings,
        memory: Map<AlertKind, AlertMemory>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Result {
        val events = mutableListOf<AlertEvent>()
        val updated = memory.toMutableMap()
        fun check(kind: AlertKind, triggered: Boolean, event: () -> AlertEvent) {
            val (decision, newMemory) = AlertPolicy.evaluate(
                kind, triggered, memory[kind] ?: AlertMemory(), settings, place.alertsEnabled, now, zone,
            )
            updated[kind] = newMemory
            if (decision == AlertDecision.Notify) events += event()
        }

        val soon = RainAnalyzer.outlook(report, settings, now)
        check(AlertKind.RAIN_SOON, soon.willRain) {
            AlertEvent(
                AlertKind.RAIN_SOON, place.id, soon.expectedStart, soon.peakIntensity, soon.expectedTotal,
                soon.agreeing, soon.available, soon.confidence, rainingNow = soon.isRainingNow(now),
            )
        }
        for (h in RainAnalyzer.hazards(report, settings, now)) {
            check(h.kind, h.triggered) {
                AlertEvent(h.kind, place.id, h.start, h.amount, h.amount2, h.agreeing, h.available, h.confidence)
            }
        }
        // Severe first, so the most important alert is on top.
        return Result(events.sortedByDescending { it.kind.severe }, updated)
    }
}
