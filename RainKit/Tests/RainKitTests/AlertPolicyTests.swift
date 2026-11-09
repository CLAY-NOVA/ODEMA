import Foundation
import XCTest
@testable import RainKit

final class AlertPolicyTests: XCTestCase {
    let settings = AlertSettings(cooldown: 3 * 3600, quietHoursEnabled: false)

    func outlook(rain: Bool, start: Date? = nil) -> RainOutlook {
        RainOutlook(
            window: DateInterval(start: Date(), duration: 7200),
            verdicts: [],
            confidence: rain ? 1 : 0,
            willRain: rain,
            expectedStart: start,
            peakIntensity: nil
        )
    }

    func evaluate(_ rain: Bool, _ memory: AlertMemory, at iso: String, settings: AlertSettings? = nil, enabled: Bool = true) -> (AlertDecision, AlertMemory) {
        let result = AlertPolicy.evaluate(
            outlook: outlook(rain: rain),
            memory: memory,
            settings: settings ?? self.settings,
            alertsEnabled: enabled,
            now: date(iso),
            calendar: utcCalendar
        )
        return (result.decision, result.memory)
    }

    func testAlertsOncePerEpisode() {
        var (decision, memory) = evaluate(true, AlertMemory(), at: "2026-11-09T12:00:00Z")
        XCTAssertEqual(decision, .notify)
        XCTAssertEqual(memory.lastNotifiedAt, date("2026-11-09T12:00:00Z"))
        (decision, memory) = evaluate(true, memory, at: "2026-11-09T12:15:00Z")
        XCTAssertEqual(decision, .suppressed(.alreadyAlerted))
        (decision, memory) = evaluate(false, memory, at: "2026-11-09T12:30:00Z")
        XCTAssertEqual(decision, .dry)
        XCTAssertFalse(memory.episodeActive)
    }

    func testCooldownMergesCloseEpisodes() {
        var (decision, memory) = evaluate(true, AlertMemory(), at: "2026-11-09T12:00:00Z")
        (decision, memory) = evaluate(false, memory, at: "2026-11-09T13:00:00Z")
        (decision, memory) = evaluate(true, memory, at: "2026-11-09T14:00:00Z")
        XCTAssertEqual(decision, .suppressed(.cooldown))
        XCTAssertTrue(memory.episodeActive, "covered by the earlier alert")
        (decision, memory) = evaluate(false, memory, at: "2026-11-09T15:30:00Z")
        (decision, memory) = evaluate(true, memory, at: "2026-11-09T16:00:00Z")
        XCTAssertEqual(decision, .notify)
    }

    func testQuietHoursPostponeTheAlert() {
        var quiet = settings
        quiet.quietHoursEnabled = true
        quiet.quietStart = 22 * 60
        quiet.quietEnd = 7 * 60
        var (decision, memory) = evaluate(true, AlertMemory(), at: "2026-11-09T23:30:00Z", settings: quiet)
        XCTAssertEqual(decision, .suppressed(.quietHours))
        XCTAssertFalse(memory.episodeActive)
        (decision, memory) = evaluate(true, memory, at: "2026-11-10T07:05:00Z", settings: quiet)
        XCTAssertEqual(decision, .notify)
    }

    func testAlertsOffForLocation() {
        let (decision, memory) = evaluate(true, AlertMemory(), at: "2026-11-09T12:00:00Z", enabled: false)
        XCTAssertEqual(decision, .suppressed(.alertsOff))
        XCTAssertNil(memory.lastNotifiedAt)
    }

    func testQuietRangeWrapsMidnight() {
        let c = utcCalendar
        func quiet(_ iso: String, _ s: Int, _ e: Int) -> Bool { AlertPolicy.isQuiet(date(iso), start: s, end: e, calendar: c) }
        XCTAssertTrue(quiet("2026-11-09T23:30:00Z", 1320, 420))
        XCTAssertTrue(quiet("2026-11-09T06:59:00Z", 1320, 420))
        XCTAssertFalse(quiet("2026-11-09T07:00:00Z", 1320, 420))
        XCTAssertFalse(quiet("2026-11-09T12:00:00Z", 1320, 420))
        XCTAssertTrue(quiet("2026-11-09T13:00:00Z", 720, 840))
        XCTAssertFalse(quiet("2026-11-09T13:00:00Z", 600, 600), "empty range is never quiet")
    }

    func testComposerUpcoming() {
        let now = date("2026-11-09T18:40:00Z")
        let o = RainOutlook(
            window: DateInterval(start: now, duration: 7200),
            verdicts: [
                SourceVerdict(source: .gfs, predictsRain: true, firstRain: nil, peakIntensity: 1.2, peakProbability: nil, coverage: 1),
                SourceVerdict(source: .icon, predictsRain: true, firstRain: nil, peakIntensity: 1.2, peakProbability: nil, coverage: 1),
                SourceVerdict(source: .ukmo, predictsRain: false, firstRain: nil, peakIntensity: 0, peakProbability: nil, coverage: 1),
            ],
            confidence: 0.66,
            willRain: true,
            expectedStart: date("2026-11-09T19:15:00Z"),
            peakIntensity: 1.2
        )
        let content = AlertComposer.content(
            locationName: "Paris", outlook: o, now: now,
            timeZone: TimeZone(identifier: "UTC")!, locale: Locale(identifier: "en_GB")
        )
        XCTAssertEqual(content.title, "Rain soon in Paris")
        XCTAssertEqual(content.body, "Starting around 19:15 (in 35 min) · light, up to 1.2 mm/h · 2 of 3 sources agree")
    }

    func testComposerRainingNow() {
        let now = date("2026-11-09T18:40:00Z")
        let o = RainOutlook(
            window: DateInterval(start: now, duration: 7200), verdicts: [],
            confidence: 1, willRain: true, expectedStart: now, peakIntensity: 4
        )
        let content = AlertComposer.content(locationName: "Oslo", outlook: o, now: now, timeZone: TimeZone(identifier: "UTC")!, locale: Locale(identifier: "en_GB"))
        XCTAssertEqual(content.title, "Rain now in Oslo")
        XCTAssertEqual(content.body, "Moderate, up to 4.0 mm/h · 0 of 0 sources agree")
    }

    func testRelativeTime() {
        XCTAssertEqual(AlertComposer.relative(60), "in 5 min")
        XCTAssertEqual(AlertComposer.relative(35 * 60), "in 35 min")
        XCTAssertEqual(AlertComposer.relative(60 * 60), "in 1 h")
        XCTAssertEqual(AlertComposer.relative(83 * 60), "in 1 h 25 min")
    }

    func testSettingsDecodeLenientlyAndRoundTrip() throws {
        let partial = Data(#"{"lookahead": 3600, "disabledSources": ["gfs", "someRemovedSource"]}"#.utf8)
        let decoded = try JSONDecoder().decode(AlertSettings.self, from: partial)
        XCTAssertEqual(decoded.lookahead, 3600)
        XCTAssertEqual(decoded.disabledSources, [.gfs])
        XCTAssertEqual(decoded.minProbability, AlertSettings.default.minProbability)
        let again = try JSONDecoder().decode(AlertSettings.self, from: JSONEncoder().encode(decoded))
        XCTAssertEqual(again, decoded)
    }
}
