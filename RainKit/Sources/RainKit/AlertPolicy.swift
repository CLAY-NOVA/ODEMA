import Foundation

/// What we remember per location between refreshes, to avoid repeat alerts.
public struct AlertMemory: Codable, Hashable, Sendable {
    public var lastNotifiedAt: Date?
    /// True from the moment we alert (or decide not to) until the forecast goes dry again.
    public var episodeActive: Bool

    public init(lastNotifiedAt: Date? = nil, episodeActive: Bool = false) {
        self.lastNotifiedAt = lastNotifiedAt
        self.episodeActive = episodeActive
    }
}

public enum AlertDecision: Equatable, Sendable {
    case notify
    case suppressed(Reason)
    /// No rain in the window.
    case dry

    public enum Reason: String, Equatable, Sendable {
        case alreadyAlerted = "already alerted for this rain"
        case quietHours = "quiet hours"
        case cooldown = "cooldown"
        case alertsOff = "alerts off for this location"
    }
}

public enum AlertPolicy {
    /// Alerts once per rain episode: when the forecast turns wet. A dry forecast ends
    /// the episode. The cooldown merges episodes that are close together, and quiet
    /// hours postpone (not cancel) the alert.
    public static func evaluate(
        outlook: RainOutlook,
        memory: AlertMemory,
        settings: AlertSettings,
        alertsEnabled: Bool = true,
        now: Date,
        calendar: Calendar = .current
    ) -> (decision: AlertDecision, memory: AlertMemory) {
        var memory = memory
        guard outlook.willRain else {
            memory.episodeActive = false
            return (.dry, memory)
        }
        if memory.episodeActive {
            return (.suppressed(.alreadyAlerted), memory)
        }
        if !alertsEnabled {
            return (.suppressed(.alertsOff), memory)
        }
        if settings.quietHoursEnabled, isQuiet(now, start: settings.quietStart, end: settings.quietEnd, calendar: calendar) {
            return (.suppressed(.quietHours), memory)
        }
        if let last = memory.lastNotifiedAt, now.timeIntervalSince(last) < settings.cooldown {
            memory.episodeActive = true
            return (.suppressed(.cooldown), memory)
        }
        memory.episodeActive = true
        memory.lastNotifiedAt = now
        return (.notify, memory)
    }

    /// `start`/`end` are minutes after local midnight; the range may wrap past midnight.
    public static func isQuiet(_ date: Date, start: Int, end: Int, calendar: Calendar) -> Bool {
        guard start != end else { return false }
        let parts = calendar.dateComponents([.hour, .minute], from: date)
        let minute = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
        return start < end ? (minute >= start && minute < end) : (minute >= start || minute < end)
    }
}

public struct AlertContent: Equatable, Sendable {
    public var title: String
    public var body: String
}

public enum AlertComposer {
    public static func content(
        locationName: String,
        outlook: RainOutlook,
        now: Date,
        timeZone: TimeZone = .current,
        locale: Locale = .current
    ) -> AlertContent {
        var parts: [String] = []
        let title: String
        if outlook.isRainingNow(now) {
            title = "Rain now in \(locationName)"
        } else {
            title = "Rain soon in \(locationName)"
            if let start = outlook.expectedStart {
                let formatter = DateFormatter()
                formatter.locale = locale
                formatter.timeZone = timeZone
                formatter.dateStyle = .none
                formatter.timeStyle = .short
                parts.append("Starting around \(formatter.string(from: start)) (\(relative(start.timeIntervalSince(now))))")
            }
        }
        if let peak = outlook.peakIntensity {
            let level = RainIntensity(mmPerHour: peak).rawValue
            let text = "\(level), up to \(String(format: "%.1f", peak)) mm/h"
            parts.append(parts.isEmpty ? text.prefix(1).uppercased() + text.dropFirst() : text)
        }
        parts.append("\(outlook.agreeingCount) of \(outlook.availableCount) sources agree")
        return AlertContent(title: title, body: parts.joined(separator: " · "))
    }

    /// "in 35 min", "in 1 h 20 min", rounded to 5 minutes.
    public static func relative(_ interval: TimeInterval) -> String {
        let minutes = max(5, Int((interval / 300).rounded()) * 5)
        if minutes < 60 { return "in \(minutes) min" }
        let h = minutes / 60, m = minutes % 60
        return m == 0 ? "in \(h) h" : "in \(h) h \(m) min"
    }
}
