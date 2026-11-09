import Foundation

/// What one source says about the alert window.
public struct SourceVerdict: Hashable, Sendable, Identifiable {
    public var source: SourceID
    public var predictsRain: Bool
    /// When this source expects rain to begin (clamped to the window start).
    public var firstRain: Date?
    /// Highest intensity in the window, mm/h, if the source publishes amounts.
    public var peakIntensity: Double?
    /// Highest probability in the window, %, if the source publishes probabilities.
    public var peakProbability: Double?
    /// Share of the window (0–1) this source has data for.
    public var coverage: Double

    public var id: SourceID { source }
}

/// The combined answer to "will it rain at this place soon?".
public struct RainOutlook: Hashable, Sendable {
    public var window: DateInterval
    /// Sources with data for the window: rainy ones first, earliest rain first.
    public var verdicts: [SourceVerdict]
    /// Weighted share of sources predicting rain, 0–1.
    public var confidence: Double
    public var willRain: Bool
    /// Weighted median of the agreeing sources' start times.
    public var expectedStart: Date?
    /// Median of the agreeing sources' peak intensities, mm/h.
    public var peakIntensity: Double?

    public var agreeingCount: Int { verdicts.filter(\.predictsRain).count }
    public var availableCount: Int { verdicts.count }

    public func isRainingNow(_ now: Date) -> Bool {
        guard willRain, let start = expectedStart else { return false }
        return start <= now.addingTimeInterval(10 * 60)
    }
}

/// One bar in the "next hours" chart.
public struct TimelineSlot: Hashable, Sendable, Identifiable {
    public var start: Date
    public var duration: TimeInterval
    /// Weighted share of sources predicting rain in this slot, 0–1.
    public var chance: Double
    /// Median intensity across sources that publish amounts, mm/h.
    public var intensity: Double?
    public var sourceCount: Int

    public var id: Date { start }
    public var end: Date { start.addingTimeInterval(duration) }
}

public enum RainAnalyzer {
    /// A step is rainy when it is wet enough *or* likely enough.
    public static func isRainy(_ sample: PrecipitationSample, settings: AlertSettings) -> Bool {
        if let intensity = sample.intensity, intensity >= settings.minIntensity { return true }
        if let probability = sample.probability, probability >= settings.minProbability { return true }
        return false
    }

    public static func verdict(
        source: SourceID,
        samples: [PrecipitationSample],
        window: DateInterval,
        settings: AlertSettings
    ) -> SourceVerdict? {
        let inWindow = samples
            .filter { $0.end > window.start && $0.start < window.end }
            .sorted { $0.start < $1.start }
        let coverage = coveredFraction(of: window, by: inWindow)
        guard coverage > 0 else { return nil }

        let rainy = inWindow.filter { isRainy($0, settings: settings) }
        return SourceVerdict(
            source: source,
            predictsRain: !rainy.isEmpty,
            firstRain: rainy.first.map { max($0.start, window.start) },
            peakIntensity: inWindow.compactMap(\.intensity).max(),
            peakProbability: inWindow.compactMap(\.probability).max(),
            coverage: coverage
        )
    }

    public static func outlook(report: ForecastReport, settings: AlertSettings, now: Date) -> RainOutlook {
        let window = DateInterval(start: now, duration: settings.lookahead)
        let verdicts = report.outcomes
            .filter { settings.enabledSources.contains($0.key) }
            .compactMap { source, outcome -> SourceVerdict? in
                guard let samples = outcome.samples else { return nil }
                return verdict(source: source, samples: samples, window: window, settings: settings)
            }
            .sorted(by: verdictOrder)

        // A dry source only vouches for the part of the window it covers
        // (the radar nowcast stops after 2 hours, for example).
        let total = verdicts.reduce(0) { $0 + ($1.predictsRain ? $1.source.weight : $1.source.weight * $1.coverage) }
        let agreeing = verdicts.filter(\.predictsRain)
        let agreeingWeight = agreeing.reduce(0) { $0 + $1.source.weight }
        let confidence = total > 0 ? min(1, agreeingWeight / total) : 0
        let willRain = !agreeing.isEmpty && confidence + 1e-9 >= settings.requiredAgreement

        return RainOutlook(
            window: window,
            verdicts: verdicts,
            confidence: confidence,
            willRain: willRain,
            expectedStart: weightedMedianStart(agreeing),
            peakIntensity: median(agreeing.compactMap(\.peakIntensity))
        )
    }

    /// Per-slot consensus for charting the next `hours` hours.
    public static func timeline(
        report: ForecastReport,
        settings: AlertSettings,
        now: Date,
        hours: Int = 12,
        step: TimeInterval = 1800
    ) -> [TimelineSlot] {
        let first = Date(timeIntervalSince1970: (now.timeIntervalSince1970 / step).rounded(.down) * step)
        let series = report.outcomes
            .filter { settings.enabledSources.contains($0.key) }
            .compactMap { source, outcome in outcome.samples.map { (source, $0) } }

        let slotCount = Int((Double(hours) * 3600 / step).rounded(.up))
        return (0..<slotCount).map { index in
            let slot = DateInterval(start: first.addingTimeInterval(Double(index) * step), duration: step)
            var total = 0.0, rainy = 0.0, count = 0
            var intensities: [Double] = []
            for (source, samples) in series {
                let overlapping = samples.filter { $0.end > slot.start && $0.start < slot.end }
                guard !overlapping.isEmpty else { continue }
                count += 1
                total += source.weight
                if overlapping.contains(where: { isRainy($0, settings: settings) }) { rainy += source.weight }
                if let peak = overlapping.compactMap(\.intensity).max() { intensities.append(peak) }
            }
            return TimelineSlot(
                start: slot.start,
                duration: step,
                chance: total > 0 ? rainy / total : 0,
                intensity: median(intensities),
                sourceCount: count
            )
        }
    }

    // MARK: - Helpers

    static func verdictOrder(_ a: SourceVerdict, _ b: SourceVerdict) -> Bool {
        if a.predictsRain != b.predictsRain { return a.predictsRain }
        if let fa = a.firstRain, let fb = b.firstRain, fa != fb { return fa < fb }
        if a.source.weight != b.source.weight { return a.source.weight > b.source.weight }
        return a.source.displayName < b.source.displayName
    }

    static func coveredFraction(of window: DateInterval, by samples: [PrecipitationSample]) -> Double {
        guard window.duration > 0 else { return 0 }
        var covered = 0.0
        var cursor = window.start
        for sample in samples.sorted(by: { $0.start < $1.start }) {
            let start = max(sample.start, cursor)
            let end = min(sample.end, window.end)
            if end > start {
                covered += end.timeIntervalSince(start)
                cursor = end
            }
        }
        return min(1, covered / window.duration)
    }

    static func weightedMedianStart(_ verdicts: [SourceVerdict]) -> Date? {
        let points = verdicts
            .compactMap { v in v.firstRain.map { ($0, v.source.weight) } }
            .sorted { $0.0 < $1.0 }
        let half = points.reduce(0) { $0 + $1.1 } / 2
        var running = 0.0
        for (date, weight) in points {
            running += weight
            if running >= half { return date }
        }
        return points.last?.0
    }

    static func median(_ values: [Double]) -> Double? {
        guard !values.isEmpty else { return nil }
        let sorted = values.sorted()
        let mid = sorted.count / 2
        return sorted.count % 2 == 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid]
    }
}

/// Human wording for precipitation rates (mm/h), roughly following the AMS glossary.
public enum RainIntensity: String, Sendable {
    case veryLight = "very light"
    case light
    case moderate
    case heavy
    case violent

    public init(mmPerHour value: Double) {
        switch value {
        case ..<0.5: self = .veryLight
        case ..<2.5: self = .light
        case ..<7.6: self = .moderate
        case ..<50: self = .heavy
        default: self = .violent
        }
    }
}
